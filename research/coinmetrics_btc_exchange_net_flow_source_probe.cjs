#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const BASE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000";
const SOURCES = {
  FlowInExNtv: `${BASE_URL}&metrics=FlowInExNtv`,
  FlowOutExNtv: `${BASE_URL}&metrics=FlowOutExNtv`,
};
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS = 2557;
const COMPLETE_WEEKS = 365;
const DAY_MS = 24 * 60 * 60 * 1000;
const DECIMAL = /^\d+(?:\.\d{1,8})?$/;

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

function decimal8(value, context) {
  if (!DECIMAL.test(value)) throw new SourceReject(`SOURCE_REJECT:DECIMAL:${context}`);
  const [whole, fraction = ""] = value.split(".");
  const scaled = BigInt(whole) * 100000000n + BigInt(fraction.padEnd(8, "0"));
  if (scaled <= 0n || scaled > 100000000000000000n) throw new SourceReject(`SOURCE_REJECT:RANGE:${context}`);
  return scaled;
}

function formatDecimal8(value) {
  const whole = value / 100000000n;
  const fraction = (value % 100000000n).toString().padStart(8, "0");
  return `${whole}.${fraction}`;
}

function parseRows(bytes, metric) {
  let payload;
  try {
    payload = JSON.parse(bytes.toString("utf8"));
  } catch (error) {
    throw new SourceReject(`SOURCE_REJECT:JSON:${metric}:${error.message}`);
  }
  if (!payload || !Array.isArray(payload.data) || Object.keys(payload).some((key) => key !== "data")) {
    throw new SourceReject(`SOURCE_REJECT:ENVELOPE_OR_PAGINATION:${metric}`);
  }
  if (payload.data.length !== EXPECTED_ROWS) throw new SourceReject(`SOURCE_REJECT:ROWS:${metric}:${payload.data.length}`);
  const statusKey = `${metric}-status`;
  const statusTimeKey = `${metric}-status-time`;
  const expectedKeys = [metric, statusKey, statusTimeKey, "asset", "time"].sort();
  const rows = payload.data.map((row, index) => {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(expectedKeys)) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${metric}:${index}:${keys.join(",")}`);
    }
    if (row.asset !== "btc" || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${metric}:${index}`);
    }
    if (row[statusKey] !== "flash" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(row[statusTimeKey])) {
      throw new SourceReject(`SOURCE_REJECT:STATUS:${metric}:${index}`);
    }
    return {
      date: row.time.slice(0, 10),
      time: Date.parse(row.time),
      value: decimal8(row[metric], `${metric}:${index}`),
      statusTime: row[statusTimeKey],
    };
  });
  if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject(`SOURCE_REJECT:BOUNDARY:${metric}`);
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].time - rows[index - 1].time !== DAY_MS) {
      throw new SourceReject(`SOURCE_REJECT:DAILY_CONTINUITY:${metric}:${index}`);
    }
  }
  return rows;
}

function joinRows(flowIn, flowOut) {
  if (flowIn.length !== flowOut.length) throw new SourceReject("SOURCE_REJECT:JOIN_LENGTH");
  return flowIn.map((input, index) => {
    const output = flowOut[index];
    if (input.date !== output.date || input.time !== output.time) throw new SourceReject(`SOURCE_REJECT:JOIN_DATE:${index}`);
    return {
      date: input.date,
      time: input.time,
      flowIn: input.value,
      flowOut: output.value,
      net: input.value - output.value,
    };
  });
}

function featureFeasibility(rows) {
  if (rows.length < COMPLETE_WEEKS * 7) throw new SourceReject("SOURCE_REJECT:INCOMPLETE_WEEK_LATTICE");
  const states = [];
  let firstWeekEnd;
  for (let week = 0; week < COMPLETE_WEEKS; week += 1) {
    const start = week * 7;
    const slice = rows.slice(start, start + 7);
    if (slice.length !== 7 || new Date(slice[0].time).getUTCDay() !== 1 || new Date(slice.at(-1).time).getUTCDay() !== 0) {
      throw new SourceReject(`SOURCE_REJECT:WEEK_ALIGNMENT:${week}`);
    }
    const net = slice.reduce((total, row) => total + row.net, 0n);
    states.push(net > 0n);
    if (week === 0) firstWeekEnd = slice.at(-1).time + DAY_MS;
  }
  let transitions = 0;
  for (let index = 1; index < states.length; index += 1) if (states[index] !== states[index - 1]) transitions += 1;
  return {
    complete_week_evaluations: states.length,
    positive_net_inflow_weeks: states.filter(Boolean).length,
    nonpositive_net_inflow_weeks: states.filter((value) => !value).length,
    transitions,
    first_complete_week_end: new Date(firstWeekEnd).toISOString().replace(".000Z", "Z"),
    first_effective_time: new Date(firstWeekEnd + 2 * DAY_MS).toISOString().replace(".000Z", "Z"),
    excluded_trailing_incomplete_days: rows.length - COMPLETE_WEEKS * 7,
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
  for (const name of ["--bundle", "--raw-in", "--raw-out", "--normalized"]) {
    if (!values[name]) throw new SourceReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

async function fetchSource(metric) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(SOURCES[metric], {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: { Accept: "application/json", "User-Agent": "AgoraResearchCoinMetricsExchangeNetFlowSourceProbe/1.0" },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${metric}:${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!bytes.length || bytes.length > MAX_RESPONSE_BYTES) throw new SourceReject(`SOURCE_REJECT:BYTES:${metric}:${bytes.length}`);
  return {
    metric,
    bytes,
    rows: parseRows(bytes, metric),
    response: {
      status: response.status,
      content_type: response.headers.get("content-type"),
      etag: response.headers.get("etag"),
      last_modified: response.headers.get("last-modified"),
    },
  };
}

async function main() {
  const args = argumentsByName(process.argv.slice(2));
  const bundleFile = requireOutputPath(args["--bundle"]);
  const rawInFile = requireOutputPath(args["--raw-in"]);
  const rawOutFile = requireOutputPath(args["--raw-out"]);
  const normalizedFile = requireOutputPath(args["--normalized"]);
  if (new Set([bundleFile, rawInFile, rawOutFile, normalizedFile]).size !== 4) throw new SourceReject("OUTPUT_PATH_REJECT:DUPLICATE");

  const input = await fetchSource("FlowInExNtv");
  const output = await fetchSource("FlowOutExNtv");
  const rows = joinRows(input.rows, output.rows);
  const normalizedBytes = Buffer.from([
    "date,flow_in_ex_ntv,flow_out_ex_ntv,net_inflow_ex_ntv",
    ...rows.map((row) => `${row.date},${formatDecimal8(row.flowIn)},${formatDecimal8(row.flowOut)},${formatDecimal8(row.net < 0n ? -row.net : row.net).replace(/^/, row.net < 0n ? "-" : "")}`),
  ].join("\n") + "\n", "utf8");
  const feasibility = featureFeasibility(rows);
  const rawEvidence = (source, file) => ({
    metric: source.metric,
    path: path.relative(REPO_ROOT, file).replaceAll("\\", "/"),
    url: SOURCES[source.metric],
    bytes: source.bytes.length,
    sha256: sha256(source.bytes),
    rows: source.rows.length,
    first_status_time: source.rows[0].statusTime,
    last_status_time: source.rows.at(-1).statusTime,
    ...source.response,
  });
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_EXCHANGE_NET_FLOW_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
    publisher: "Coin Metrics Community API",
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      urls: SOURCES,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes_per_request: MAX_RESPONSE_BYTES,
      response_status: "FLASH_PRESENT_VINTAGE",
    },
    raw_responses: [rawEvidence(input, rawInFile), rawEvidence(output, rawOutFile)],
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "flow_in_ex_ntv", "flow_out_ex_ntv", "net_inflow_ex_ntv"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Aggregate only seven complete Monday-through-Sunday UTC observations. Treat week end Monday D as usable no earlier than D plus two calendar days at 00:00 UTC; no incomplete week is used.",
    revision_boundary: "The exact Community API responses are sealed present-vintage flash histories whose status times are in 2026. Historical address labels, exchange coverage, original review timestamps and original vintages remain MISSING_PROOF; a historical pass cannot create a candidate without untouched forward evidence.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source only. No BTC outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
  };
  const bundleBytes = Buffer.from(`${JSON.stringify(canonical(bundle))}\n`, "utf8");

  const created = [];
  try {
    writeCreateOnce(rawInFile, input.bytes); created.push(rawInFile);
    writeCreateOnce(rawOutFile, output.bytes); created.push(rawOutFile);
    writeCreateOnce(normalizedFile, normalizedBytes); created.push(normalizedFile);
    writeCreateOnce(bundleFile, bundleBytes); created.push(bundleFile);
  } catch (error) {
    for (const file of created) fs.rmSync(file, { force: true });
    throw error;
  }
  process.stdout.write(`${JSON.stringify({
    status: bundle.status,
    bundle: path.relative(REPO_ROOT, bundleFile).replaceAll("\\", "/"),
    bundle_sha256: sha256(bundleBytes),
    raw_responses: bundle.raw_responses,
    normalized: bundle.normalized_subset,
    feasibility,
  })}\n`);
}

module.exports = { SourceReject, decimal8, featureFeasibility, formatDecimal8, joinRows, parseRows };

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  });
}
