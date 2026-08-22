#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=usdt%2Cusdc&metrics=CapMrktCurUSD&frequency=1d&start_time=2018-09-28&end_time=2024-12-31&page_size=10000&status=reviewed";
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_DAYS = 2287;
const EXPECTED_ROWS = EXPECTED_DAYS * 2;
const DAY_MS = 24 * 60 * 60 * 1000;
const GROWTH_LAG_DAYS = 28;

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

  const byDate = new Map();
  for (const [index, row] of payload.data.entries()) {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["CapMrktCurUSD", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (!["usdt", "usdc"].includes(row.asset) || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    if (!/^\d+(?:\.\d+)?$/.test(row.CapMrktCurUSD)) throw new SourceReject(`SOURCE_REJECT:DECIMAL:${index}`);
    const value = Number(row.CapMrktCurUSD);
    if (!Number.isFinite(value) || value <= 0 || value > 1_000_000_000_000) {
      throw new SourceReject(`SOURCE_REJECT:RANGE:${index}`);
    }
    const date = row.time.slice(0, 10);
    const values = byDate.get(date) || {};
    if (values[row.asset] !== undefined) throw new SourceReject(`SOURCE_REJECT:DUPLICATE:${date}:${row.asset}`);
    values[row.asset] = row.CapMrktCurUSD;
    byDate.set(date, values);
  }

  const rows = [...byDate.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([date, values]) => {
    if (values.usdt === undefined || values.usdc === undefined) throw new SourceReject(`SOURCE_REJECT:MISSING_ASSET:${date}`);
    return {
      date,
      time: Date.parse(`${date}T00:00:00Z`),
      usdt: values.usdt,
      usdc: values.usdc,
      usdtNumber: Number(values.usdt),
      usdcNumber: Number(values.usdc),
    };
  });
  if (rows.length !== EXPECTED_DAYS) throw new SourceReject(`SOURCE_REJECT:DAYS:${rows.length}`);
  if (rows[0].date !== "2018-09-28" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].time - rows[index - 1].time !== DAY_MS) throw new SourceReject(`SOURCE_REJECT:DAILY_CONTINUITY:${index}`);
  }
  return rows;
}

function featureFeasibility(rows) {
  const states = { PRIMARY_COMBINED: [], NEIGHBOR_USDT_ONLY: [], NEIGHBOR_USDC_ONLY: [] };
  for (let index = GROWTH_LAG_DAYS; index < rows.length; index += 1) {
    const current = rows[index];
    const prior = rows[index - GROWTH_LAG_DAYS];
    states.PRIMARY_COMBINED.push(current.usdtNumber + current.usdcNumber > prior.usdtNumber + prior.usdcNumber);
    states.NEIGHBOR_USDT_ONLY.push(current.usdtNumber > prior.usdtNumber);
    states.NEIGHBOR_USDC_ONLY.push(current.usdcNumber > prior.usdcNumber);
  }
  const summary = {};
  for (const [name, values] of Object.entries(states)) {
    let transitions = 0;
    for (let index = 1; index < values.length; index += 1) if (values[index] !== values[index - 1]) transitions += 1;
    summary[name] = {
      evaluations: values.length,
      positive_days: values.filter(Boolean).length,
      negative_days: values.filter((value) => !value).length,
      transitions,
    };
  }
  return {
    first_evaluable_observation_day: rows[GROWTH_LAG_DAYS].date,
    first_effective_time: new Date(rows[GROWTH_LAG_DAYS].time + 2 * DAY_MS).toISOString().replace(".000Z", "Z"),
    variants: summary,
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
      headers: { Accept: "application/json", "User-Agent": "AgoraResearchCoinMetricsStablecoinLiquiditySourceProbe/1.0" },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  const rows = parseRows(rawBytes);
  const normalizedBytes = Buffer.from([
    "date,usdt_cap_mrkt_cur_usd,usdc_cap_mrkt_cur_usd",
    ...rows.map((row) => `${row.date},${row.usdt},${row.usdc}`),
  ].join("\n") + "\n", "utf8");
  const feasibility = featureFeasibility(rows);
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_USDT_USDC_STABLECOIN_LIQUIDITY_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
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
      rows: EXPECTED_ROWS,
    },
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "usdt_cap_mrkt_cur_usd", "usdc_cap_mrkt_cur_usd"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Treat UTC observation day D as usable no earlier than D plus two calendar days at 00:00 UTC; the extra day is a conservative confirmation allowance and no current incomplete day is used.",
    revision_boundary: "The exact Community API response is a sealed present-vintage reviewed historical input. Original daily review timestamps, reference-rate vintages and original values are not exposed by this response and remain MISSING_PROOF; a historical pass therefore requires untouched sealed forward OOS.",
    metric_boundary: "CapMrktCurUSD equals current supply multiplied by the current USD reference rate. It is a capitalization/liquidity proxy, not pure issuance, and may incorporate depeg effects.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use under a Creative Commons license. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source only. No BTC outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
