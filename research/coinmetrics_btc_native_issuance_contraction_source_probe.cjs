#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=IssTotNtv&frequency=1d&start_time=2017-01-01&end_time=2024-12-31&page_size=10000";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS = 2922;
const DAY_MS = 24 * 60 * 60 * 1000;
const WINDOW_DAYS = 28;
const COMPARISON_END_LAG_DAYS = 364;
const AVAILABILITY_LAG_DAYS = 3;
const SCALE = 100000000n;
const MAX_DAILY_ISSUANCE_SCALED = 5000n * SCALE;
const DESIGN_START = Date.parse("2019-01-01T00:00:00Z");
const VALIDATION_START = Date.parse("2023-01-01T00:00:00Z");
const STUDY_END = Date.parse("2025-01-01T00:00:00Z");
const HALVING_TIMES = [Date.parse("2020-05-11T00:00:00Z"), Date.parse("2024-04-20T00:00:00Z")];
const POST_HALVING_DAYS = 364;
const SUPPORT_GATES = {
  design: {
    minimum_evaluations: 180,
    minimum_per_state: 40,
    minimum_transitions: 8,
    minimum_calendar_years_per_state: 3,
    maximum_single_calendar_year_supportive_share: 0.60,
    minimum_supportive_weeks_outside_post_halving_364d: 40,
  },
  validation: {
    minimum_evaluations: 90,
    minimum_per_state: 20,
    minimum_transitions: 4,
    minimum_calendar_years_per_state: 2,
    maximum_single_calendar_year_supportive_share: 0.75,
    minimum_supportive_weeks_outside_post_halving_364d: 20,
  },
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

function parseDecimal8(raw, index) {
  if (typeof raw !== "string") throw new SourceReject(`SOURCE_REJECT:ISSUANCE_TYPE:${index}`);
  const match = /^(0|[1-9]\d*)(?:\.(\d{1,8}))?$/.exec(raw);
  if (!match) throw new SourceReject(`SOURCE_REJECT:ISSUANCE_DECIMAL:${index}`);
  const scaled = BigInt(match[1]) * SCALE + BigInt((match[2] || "").padEnd(8, "0") || "0");
  if (scaled <= 0n || scaled > MAX_DAILY_ISSUANCE_SCALED) {
    throw new SourceReject(`SOURCE_REJECT:ISSUANCE_RANGE:${index}`);
  }
  return scaled;
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
    if (JSON.stringify(keys) !== JSON.stringify(["IssTotNtv", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (row.asset !== "btc" || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    return {
      date: row.time.slice(0, 10),
      rawValue: row.IssTotNtv,
      time: Date.parse(row.time),
      value: parseDecimal8(row.IssTotNtv, index),
    };
  });
  if (rows[0].date !== "2017-01-01" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].time - rows[index - 1].time !== DAY_MS) {
      throw new SourceReject(`SOURCE_REJECT:DAILY_CONTINUITY:${index}`);
    }
  }
  return rows;
}

function isPostHalvingWindow(time) {
  return HALVING_TIMES.some((halving) => time >= halving && time < halving + POST_HALVING_DAYS * DAY_MS);
}

function summarizeWindow(states, start, end, gate) {
  const selected = states.filter((state) => state.effectiveTime >= start && state.effectiveTime < end);
  const supportiveStates = selected.filter((state) => state.supportive);
  const otherStates = selected.filter((state) => !state.supportive);
  let transitions = 0;
  for (let index = 1; index < selected.length; index += 1) {
    if (selected[index].supportive !== selected[index - 1].supportive) transitions += 1;
  }
  const supportiveByYear = {};
  const otherByYear = {};
  for (const state of selected) {
    const year = String(new Date(state.effectiveTime).getUTCFullYear());
    const target = state.supportive ? supportiveByYear : otherByYear;
    target[year] = (target[year] || 0) + 1;
  }
  const topYearSupportiveCount = Math.max(0, ...Object.values(supportiveByYear));
  const topYearSupportiveShare = supportiveStates.length ? topYearSupportiveCount / supportiveStates.length : 1;
  const supportiveOutsidePostHalving = supportiveStates.filter((state) => !state.postHalving364d).length;
  const gateResults = {
    minimum_evaluations: selected.length >= gate.minimum_evaluations,
    minimum_per_state: supportiveStates.length >= gate.minimum_per_state && otherStates.length >= gate.minimum_per_state,
    minimum_transitions: transitions >= gate.minimum_transitions,
    minimum_calendar_years_per_state:
      Object.keys(supportiveByYear).length >= gate.minimum_calendar_years_per_state &&
      Object.keys(otherByYear).length >= gate.minimum_calendar_years_per_state,
    maximum_single_calendar_year_supportive_share:
      topYearSupportiveShare <= gate.maximum_single_calendar_year_supportive_share,
    minimum_supportive_weeks_outside_post_halving_364d:
      supportiveOutsidePostHalving >= gate.minimum_supportive_weeks_outside_post_halving_364d,
  };
  return {
    evaluations: selected.length,
    supportive_weeks: supportiveStates.length,
    other_weeks: otherStates.length,
    transitions,
    supportive_calendar_years: Object.keys(supportiveByYear).length,
    other_calendar_years: Object.keys(otherByYear).length,
    supportive_by_year: supportiveByYear,
    other_by_year: otherByYear,
    top_year_supportive_count: topYearSupportiveCount,
    top_year_supportive_share: topYearSupportiveShare.toFixed(8),
    supportive_weeks_inside_post_halving_364d: supportiveStates.length - supportiveOutsidePostHalving,
    supportive_weeks_outside_post_halving_364d: supportiveOutsidePostHalving,
    first_effective_time: selected.length ? new Date(selected[0].effectiveTime).toISOString().replace(".000Z", "Z") : null,
    last_effective_time: selected.length ? new Date(selected.at(-1).effectiveTime).toISOString().replace(".000Z", "Z") : null,
    support_gate: gate,
    gate_results: gateResults,
    support_pass: Object.values(gateResults).every(Boolean),
  };
}

function rangeSum(rows, start, endInclusive) {
  let total = 0n;
  for (let index = start; index <= endInclusive; index += 1) total += rows[index].value;
  return total;
}

function featureFeasibility(rows) {
  if (new Date(rows[0].time).getUTCDay() !== 0) throw new SourceReject("SOURCE_REJECT:FIRST_DAY_NOT_SUNDAY");
  const states = [];
  const firstEligibleIndex = COMPARISON_END_LAG_DAYS + WINDOW_DAYS - 1;
  for (let index = firstEligibleIndex; index < rows.length; index += 1) {
    if (new Date(rows[index].time).getUTCDay() !== 0) continue;
    const currentSum = rangeSum(rows, index - WINDOW_DAYS + 1, index);
    const priorEnd = index - COMPARISON_END_LAG_DAYS;
    const priorSum = rangeSum(rows, priorEnd - WINDOW_DAYS + 1, priorEnd);
    states.push({
      date: rows[index].date,
      effectiveTime: rows[index].time + AVAILABILITY_LAG_DAYS * DAY_MS,
      postHalving364d: isPostHalvingWindow(rows[index].time),
      supportive: currentSum < priorSum,
    });
  }
  if (!states.length) throw new SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE");
  let transitions = 0;
  for (let index = 1; index < states.length; index += 1) {
    if (states[index].supportive !== states[index - 1].supportive) transitions += 1;
  }
  const design = summarizeWindow(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES.design);
  const validation = summarizeWindow(states, VALIDATION_START, STUDY_END, SUPPORT_GATES.validation);
  return {
    evaluations: states.length,
    supportive_weeks: states.filter((state) => state.supportive).length,
    other_weeks: states.filter((state) => !state.supportive).length,
    transitions,
    first_evaluable_week_end: states[0].date,
    first_effective_time: new Date(states[0].effectiveTime).toISOString().replace(".000Z", "Z"),
    last_evaluable_week_end: states.at(-1).date,
    last_effective_time: new Date(states.at(-1).effectiveTime).toISOString().replace(".000Z", "Z"),
    design,
    validation,
    admission_status: design.support_pass && validation.support_pass
      ? "PASS_ALL_PRE_OUTCOME_SUPPORT_AND_CONCENTRATION_GATES"
      : "DATA_REJECT_PRE_OUTCOME_SUPPORT_OR_CONCENTRATION_GATE_FAILURE",
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
        "User-Agent": "AgoraResearchCoinMetricsBtcNativeIssuanceSourceProbe/1.0",
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
    "date,issuance_total_native_units",
    ...rows.map((row) => `${row.date},${row.rawValue}`),
  ].join("\n") + "\n", "utf8");
  const feasibility = featureFeasibility(rows);
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_NATIVE_ISSUANCE_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: feasibility.admission_status === "PASS_ALL_PRE_OUTCOME_SUPPORT_AND_CONCENTRATION_GATES"
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
      columns: ["date", "issuance_total_native_units"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "Evaluate only a complete Sunday 28-day IssTotNtv sum against the same-weekday 28-day sum ending 364 days earlier. Treat the Sunday endpoint as usable no earlier than Wednesday 00:00 UTC and valid for 168 hours.",
    revision_boundary: "The exact Community API response is a sealed present-vintage historical input. Original daily publication timestamps, revisions and vintage history remain MISSING_PROOF; any later historical pass remains discovery and requires untouched forward evidence.",
    interpretation_boundary: "IssTotNtv is protocol issuance, not observed miner selling. Halvings are deterministic and anticipated; block-production variation can change raw daily issuance without a durable supply-pressure shock.",
    license_boundary: "Coin Metrics documents some Community Data as no-key and free. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
    scope_note: "Free source and pre-outcome support/concentration check only. No BTC price or DRA outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
