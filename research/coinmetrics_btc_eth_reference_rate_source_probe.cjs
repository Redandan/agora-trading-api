#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc,eth&metrics=ReferenceRateUSD&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000";
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS_PER_ASSET = 2557;
const EXPECTED_TOTAL_ROWS = EXPECTED_ROWS_PER_ASSET * 2;
const DAY_MS = 24 * 60 * 60 * 1000;
const LOOKBACK_DAYS = 28;
const FULL_WEEK_DAYS = 2555;
const THRESHOLDS = [-0.05, 0, 0.05];

class SourceReject extends Error {}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function parseRows(bytes) {
  let payload;
  try {
    payload = JSON.parse(bytes.toString("utf8"));
  } catch (error) {
    throw new SourceReject(`SOURCE_REJECT:JSON:${error.message}`);
  }
  if (!payload || !Array.isArray(payload.data) || Object.keys(payload).some((key) => key !== "data")) {
    throw new SourceReject("SOURCE_REJECT:ENVELOPE_OR_PAGINATION");
  }
  if (payload.data.length !== EXPECTED_TOTAL_ROWS) {
    throw new SourceReject(`SOURCE_REJECT:ROWS:${payload.data.length}`);
  }
  const decimal = /^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
  const byAsset = { btc: [], eth: [] };
  payload.data.forEach((row, index) => {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["ReferenceRateUSD", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (!(row.asset in byAsset) || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    if (typeof row.ReferenceRateUSD !== "string" || !decimal.test(row.ReferenceRateUSD)) {
      throw new SourceReject(`SOURCE_REJECT:REFERENCE_RATE_DECIMAL:${index}`);
    }
    const rate = Number(row.ReferenceRateUSD);
    if (!Number.isFinite(rate) || rate <= 0 || rate > 10000000) {
      throw new SourceReject(`SOURCE_REJECT:REFERENCE_RATE_RANGE:${index}`);
    }
    byAsset[row.asset].push({
      asset: row.asset,
      date: row.time.slice(0, 10),
      time: Date.parse(row.time),
      rate,
      rawRate: row.ReferenceRateUSD,
    });
  });

  for (const asset of ["btc", "eth"]) {
    const rows = byAsset[asset].sort((left, right) => left.time - right.time);
    if (rows.length !== EXPECTED_ROWS_PER_ASSET) throw new SourceReject(`SOURCE_REJECT:${asset.toUpperCase()}_ROWS:${rows.length}`);
    if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") {
      throw new SourceReject(`SOURCE_REJECT:${asset.toUpperCase()}_BOUNDARY`);
    }
    for (let index = 1; index < rows.length; index += 1) {
      if (rows[index].time - rows[index - 1].time !== DAY_MS) {
        throw new SourceReject(`SOURCE_REJECT:${asset.toUpperCase()}_DAILY_CONTINUITY:${index}`);
      }
    }
  }
  for (let index = 0; index < EXPECTED_ROWS_PER_ASSET; index += 1) {
    if (byAsset.btc[index].time !== byAsset.eth[index].time) {
      throw new SourceReject(`SOURCE_REJECT:PAIRED_TIME:${index}`);
    }
  }
  return byAsset;
}

function featureFeasibility(byAsset) {
  const btc = byAsset.btc.slice(0, FULL_WEEK_DAYS);
  const eth = byAsset.eth.slice(0, FULL_WEEK_DAYS);
  if (new Date(btc[0].time).getUTCDay() !== 1) throw new SourceReject("SOURCE_REJECT:FIRST_DAY_NOT_MONDAY");
  if (new Date(btc.at(-1).time).getUTCDay() !== 0) throw new SourceReject("SOURCE_REJECT:LAST_FULL_DAY_NOT_SUNDAY");
  const differences = [];
  let firstReportDate = null;
  for (let index = LOOKBACK_DAYS; index < btc.length; index += 1) {
    if (new Date(btc[index].time).getUTCDay() !== 0) continue;
    const btcReturn = btc[index].rate / btc[index - LOOKBACK_DAYS].rate - 1;
    const ethReturn = eth[index].rate / eth[index - LOOKBACK_DAYS].rate - 1;
    differences.push(ethReturn - btcReturn);
    if (firstReportDate === null) firstReportDate = btc[index].date;
  }
  const thresholdDiagnostics = Object.fromEntries(THRESHOLDS.map((threshold) => {
    const states = differences.map((difference) => difference >= threshold);
    let transitions = 0;
    for (let index = 1; index < states.length; index += 1) if (states[index] !== states[index - 1]) transitions += 1;
    const label = (threshold * 100).toFixed(2);
    return [label, {
      at_or_above: states.filter(Boolean).length,
      below: states.filter((value) => !value).length,
      state_transitions: transitions,
    }];
  }));
  return {
    evaluations: differences.length,
    threshold_diagnostics: thresholdDiagnostics,
    complete_week_count: FULL_WEEK_DAYS / 7,
    excluded_incomplete_tail_days: byAsset.btc.length - FULL_WEEK_DAYS,
    first_evaluable_week_end: firstReportDate,
    first_effective_time: new Date(Date.parse(`${firstReportDate}T00:00:00Z`) + DAY_MS).toISOString().replace(".000Z", "Z"),
  };
}

function requireOutputPath(file) {
  const resolved = path.resolve(file);
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!resolved.startsWith(`${stateRoot}${path.sep}`)) throw new SourceReject(`OUTPUT_PATH_REJECT:${resolved}`);
  if (fs.existsSync(resolved)) throw new SourceReject(`SEALED_OUTPUT_EXISTS:${resolved}`);
  return resolved;
}

function writeCreateOnce(file, bytes) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, bytes, { flag: "wx" });
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--bundle", "--raw", "--normalized"]) {
    if (!values[name]) throw new SourceReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

async function main() {
  const args = argumentsByName(process.argv.slice(2));
  const bundleFile = requireOutputPath(args["--bundle"]);
  const rawFile = requireOutputPath(args["--raw"]);
  const normalizedFile = requireOutputPath(args["--normalized"]);
  if (new Set([bundleFile, rawFile, normalizedFile]).size !== 3) {
    throw new SourceReject("OUTPUT_PATH_REJECT:DUPLICATE");
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(SOURCE_URL, {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: { Accept: "application/json", "User-Agent": "AgoraResearchCoinMetricsBtcEthReferenceRateSourceProbe/1.0" },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) {
    throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  }
  const byAsset = parseRows(rawBytes);
  const normalizedBytes = Buffer.from(
    [
      "date,btc_reference_rate_usd,eth_reference_rate_usd",
      ...byAsset.btc.map((row, index) => `${row.date},${row.rawRate},${byAsset.eth[index].rawRate}`),
    ].join("\n") + "\n",
    "utf8",
  );
  const feasibility = featureFeasibility(byAsset);
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_ETH_REFERENCE_RATE_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_DRA_OUTCOME_ACCESS",
    publisher: "Coin Metrics Community API",
    source_metric: "ReferenceRateUSD",
    assets: ["btc", "eth"],
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      url: SOURCE_URL,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes: MAX_RESPONSE_BYTES,
    },
    raw_response: {
      path: path.relative(REPO_ROOT, rawFile).replaceAll("\\", "/"),
      status: response.status,
      content_type: response.headers.get("content-type"),
      etag: response.headers.get("etag"),
      last_modified: response.headers.get("last-modified"),
      bytes: rawBytes.length,
      sha256: sha256(rawBytes),
      rows: EXPECTED_TOTAL_ROWS,
      rows_per_asset: EXPECTED_ROWS_PER_ASSET,
    },
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      bytes: normalizedBytes.length,
      paired_rows: EXPECTED_ROWS_PER_ASSET,
      first_date: byAsset.btc[0].date,
      last_date: byAsset.btc.at(-1).date,
      columns: ["date", "btc_reference_rate_usd", "eth_reference_rate_usd"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Use only paired Sunday D reference rates. D becomes usable at D plus one calendar day 00:00 UTC and remains valid for at most 168 hours.",
    feature_boundary: "ETH 28-day simple return minus BTC 28-day simple return, relation AT_OR_ABOVE, thresholds minus 5.00, 0.00 and plus 5.00 percentage points.",
    revision_boundary: "The exact Community API response is a sealed present-vintage historical input. Original calculation constituents and historical revisions remain MISSING_PROOF; a historical pass therefore requires untouched prospective evidence.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use under a Creative Commons license. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source only. No DRA outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
  };
  const bundleBytes = Buffer.from(`${JSON.stringify(canonical(bundle))}\n`, "utf8");
  writeCreateOnce(rawFile, rawBytes);
  try {
    writeCreateOnce(normalizedFile, normalizedBytes);
    writeCreateOnce(bundleFile, bundleBytes);
  } catch (error) {
    fs.rmSync(rawFile, { force: true });
    fs.rmSync(normalizedFile, { force: true });
    throw error;
  }
  process.stdout.write(`${JSON.stringify({
    status: bundle.status,
    bundle: path.relative(REPO_ROOT, bundleFile).replaceAll("\\", "/"),
    bundle_sha256: sha256(bundleBytes),
    raw: bundle.raw_response,
    normalized: bundle.normalized_subset,
    feasibility,
  })}\n`);
}

module.exports = { SourceReject, featureFeasibility, parseRows };

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  });
}
