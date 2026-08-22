#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=AdrBalCnt&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS = 2557;
const FULL_WEEK_DAYS = 2555;
const DAY_MS = 24 * 60 * 60 * 1000;
const LOOKBACK_DAYS = 28;
const AVAILABILITY_LAG_DAYS = 3;
const DESIGN_START = Date.parse("2019-01-01T00:00:00Z");
const VALIDATION_START = Date.parse("2023-01-01T00:00:00Z");
const STUDY_END = Date.parse("2025-01-01T00:00:00Z");
const SUPPORT_GATES = {
  design: { minimum_evaluations: 180, minimum_per_state: 40 },
  validation: { minimum_evaluations: 90, minimum_per_state: 20 },
};

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
  const rows = payload.data.map((row, index) => {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["AdrBalCnt", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (row.asset !== "btc" || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    if (typeof row.AdrBalCnt !== "string" || !/^[1-9]\d*$/.test(row.AdrBalCnt)) {
      throw new SourceReject(`SOURCE_REJECT:ADDRESS_BALANCE_COUNT_INTEGER:${index}`);
    }
    const value = BigInt(row.AdrBalCnt);
    if (value > 1000000000n) throw new SourceReject(`SOURCE_REJECT:ADDRESS_BALANCE_COUNT_RANGE:${index}`);
    return {
      date: row.time.slice(0, 10),
      rawValue: row.AdrBalCnt,
      time: Date.parse(row.time),
      value,
    };
  });
  if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].time - rows[index - 1].time !== DAY_MS) {
      throw new SourceReject(`SOURCE_REJECT:DAILY_CONTINUITY:${index}`);
    }
  }
  return rows;
}

function summarizeWindow(states, start, end, gate) {
  const selected = states.filter((state) => state.effectiveTime >= start && state.effectiveTime < end);
  const expansion = selected.filter((state) => state.expansion).length;
  const nonexpansion = selected.length - expansion;
  return {
    evaluations: selected.length,
    expansion_weeks: expansion,
    nonexpansion_weeks: nonexpansion,
    first_effective_time: selected.length ? new Date(selected[0].effectiveTime).toISOString().replace(".000Z", "Z") : null,
    last_effective_time: selected.length ? new Date(selected.at(-1).effectiveTime).toISOString().replace(".000Z", "Z") : null,
    support_gate: gate,
    support_pass: selected.length >= gate.minimum_evaluations && expansion >= gate.minimum_per_state && nonexpansion >= gate.minimum_per_state,
  };
}

function featureFeasibility(rows) {
  if (new Date(rows[0].time).getUTCDay() !== 1) throw new SourceReject("SOURCE_REJECT:FIRST_DAY_NOT_MONDAY");
  const fullRows = rows.slice(0, FULL_WEEK_DAYS);
  if (new Date(fullRows.at(-1).time).getUTCDay() !== 0) throw new SourceReject("SOURCE_REJECT:LAST_FULL_DAY_NOT_SUNDAY");
  const states = [];
  for (let index = LOOKBACK_DAYS; index < fullRows.length; index += 1) {
    if (new Date(fullRows[index].time).getUTCDay() !== 0) continue;
    states.push({
      date: fullRows[index].date,
      effectiveTime: fullRows[index].time + AVAILABILITY_LAG_DAYS * DAY_MS,
      expansion: fullRows[index].value > fullRows[index - LOOKBACK_DAYS].value,
    });
  }
  if (!states.length) throw new SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE");
  let transitions = 0;
  for (let index = 1; index < states.length; index += 1) {
    if (states[index].expansion !== states[index - 1].expansion) transitions += 1;
  }
  const design = summarizeWindow(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES.design);
  const validation = summarizeWindow(states, VALIDATION_START, STUDY_END, SUPPORT_GATES.validation);
  return {
    evaluations: states.length,
    expansion_weeks: states.filter((state) => state.expansion).length,
    nonexpansion_weeks: states.filter((state) => !state.expansion).length,
    transitions,
    complete_week_count: FULL_WEEK_DAYS / 7,
    excluded_incomplete_tail_days: rows.length - FULL_WEEK_DAYS,
    first_evaluable_week_end: states[0].date,
    first_effective_time: new Date(states[0].effectiveTime).toISOString().replace(".000Z", "Z"),
    design,
    validation,
    admission_status: design.support_pass && validation.support_pass
      ? "PASS_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
      : "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS",
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
      headers: {
        Accept: "application/json",
        "User-Agent": "AgoraResearchCoinMetricsBtcAddressBalanceCountSourceProbe/1.0",
      },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  const rows = parseRows(rawBytes);
  const normalizedBytes = Buffer.from([
    "date,address_balance_count",
    ...rows.map((row) => `${row.date},${row.rawValue}`),
  ].join("\n") + "\n", "utf8");
  const feasibility = featureFeasibility(rows);
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_ADDRESS_BALANCE_COUNT_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: feasibility.admission_status === "PASS_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
      ? "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
      : "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS",
    publisher: "Coin Metrics Community API",
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      url: SOURCE_URL,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes: MAX_RESPONSE_BYTES,
      response_vintage: "PRESENT_VINTAGE_NO_ROW_STATUS_OR_ORIGINAL_PUBLICATION_TIMESTAMP",
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
      columns: ["date", "address_balance_count"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Evaluate only a complete Sunday AdrBalCnt observation against the value exactly 28 calendar days earlier. Treat the Sunday value as usable no earlier than Wednesday 00:00 UTC and valid for 168 hours.",
    revision_boundary: "The exact Community API response is a sealed present-vintage historical input. Original daily publication timestamps, revisions and vintage history remain MISSING_PROOF; any later historical pass remains discovery and requires untouched forward evidence.",
    interpretation_boundary: "AdrBalCnt counts funded addresses, not unique users. Wallet change behavior, dust, consolidation, exchanges and custodians can change the metric without changing economic adoption.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source and pre-outcome state-support check only. No BTC price or DRA outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
  };
  const bundleBytes = Buffer.from(`${JSON.stringify(canonical(bundle))}\n`, "utf8");

  const created = [];
  try {
    writeCreateOnce(rawFile, rawBytes); created.push(rawFile);
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
