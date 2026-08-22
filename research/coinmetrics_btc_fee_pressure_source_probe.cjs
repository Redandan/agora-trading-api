#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=FeeTotNtv&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000&status=reviewed";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS = 2557;
const DAY_MS = 24 * 60 * 60 * 1000;
const WINDOW_DAYS = 28;
const FULL_WEEK_DAYS = 2555;

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
  if (payload.data.length !== EXPECTED_ROWS) throw new SourceReject(`SOURCE_REJECT:ROWS:${payload.data.length}`);
  const decimal = /^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
  const rows = payload.data.map((row, index) => {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["FeeTotNtv", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (row.asset !== "btc" || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    if (typeof row.FeeTotNtv !== "string" || !decimal.test(row.FeeTotNtv)) {
      throw new SourceReject(`SOURCE_REJECT:FEETOTNTV_DECIMAL:${index}`);
    }
    const totalFeesBtc = Number(row.FeeTotNtv);
    if (!Number.isFinite(totalFeesBtc) || totalFeesBtc < 0 || totalFeesBtc > 1e7) {
      throw new SourceReject(`SOURCE_REJECT:FEETOTNTV_RANGE:${index}`);
    }
    return { date: row.time.slice(0, 10), time: Date.parse(row.time), totalFeesBtc, rawTotalFeesBtc: row.FeeTotNtv };
  });
  if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].time - rows[index - 1].time !== DAY_MS) throw new SourceReject(`SOURCE_REJECT:DAILY_CONTINUITY:${index}`);
  }
  return rows;
}

function featureFeasibility(rows) {
  if (new Date(rows[0].time).getUTCDay() !== 1) throw new SourceReject("SOURCE_REJECT:FIRST_DAY_NOT_MONDAY");
  const fullRows = rows.slice(0, FULL_WEEK_DAYS);
  if (new Date(fullRows.at(-1).time).getUTCDay() !== 0) throw new SourceReject("SOURCE_REJECT:LAST_FULL_DAY_NOT_SUNDAY");
  const prefix = [0];
  for (const row of fullRows) prefix.push(prefix.at(-1) + row.totalFeesBtc);
  const states = [];
  let firstReportDate = null;
  for (let end = 2 * WINDOW_DAYS - 1; end < fullRows.length; end += 7) {
    const current = prefix[end + 1] - prefix[end + 1 - WINDOW_DAYS];
    const prior = prefix[end + 1 - WINDOW_DAYS] - prefix[end + 1 - 2 * WINDOW_DAYS];
    states.push(current > prior);
    if (firstReportDate === null) firstReportDate = fullRows[end].date;
  }
  let transitions = 0;
  for (let index = 1; index < states.length; index += 1) if (states[index] !== states[index - 1]) transitions += 1;
  return {
    evaluations: states.length,
    positive_growth_weeks: states.filter(Boolean).length,
    nonpositive_growth_weeks: states.filter((value) => !value).length,
    transitions,
    complete_week_count: FULL_WEEK_DAYS / 7,
    excluded_incomplete_tail_days: rows.length - FULL_WEEK_DAYS,
    first_evaluable_week_end: firstReportDate,
    first_effective_time: new Date(Date.parse(`${firstReportDate}T00:00:00Z`) + 3 * DAY_MS).toISOString().replace(".000Z", "Z"),
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
  if (new Set([bundleFile, rawFile, normalizedFile]).size !== 3) throw new SourceReject("OUTPUT_PATH_REJECT:DUPLICATE");

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(SOURCE_URL, {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: { Accept: "application/json", "User-Agent": "AgoraResearchCoinMetricsFeePressureSourceProbe/1.0" },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  const rows = parseRows(rawBytes);
  const normalizedBytes = Buffer.from([
    "date,total_fees_btc",
    ...rows.map((row) => `${row.date},${row.rawTotalFeesBtc}`),
  ].join("\n") + "\n", "utf8");
  const feasibility = featureFeasibility(rows);
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_FEE_PRESSURE_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS",
    publisher: "Coin Metrics Community API",
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      url: SOURCE_URL,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes: MAX_RESPONSE_BYTES,
      requested_status: "reviewed",
    },
    raw_response: {
      path: path.relative(REPO_ROOT, rawFile).replaceAll("\\", "/"),
      status: response.status,
      content_type: response.headers.get("content-type"),
      etag: response.headers.get("etag"),
      last_modified: response.headers.get("last-modified"),
      bytes: rawBytes.length,
      sha256: sha256(rawBytes),
      rows: rows.length,
    },
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      bytes: normalizedBytes.length,
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "total_fees_btc"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Aggregate only complete Monday-through-Sunday weeks. Week-ending Sunday D becomes usable at D plus three calendar days 00:00 UTC and remains valid for at most 168 hours.",
    revision_boundary: "The exact Community API response is a sealed present-vintage reviewed historical input. Original daily review timestamps and original vintages remain MISSING_PROOF; a historical pass therefore requires untouched sealed forward OOS.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use under a Creative Commons license. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source only. No BTC/DRA outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
