#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=SOPR&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 30000;
const EXPECTED_ROWS = 2557;
const DAY_MS = 24 * 60 * 60 * 1000;
const AVAILABILITY_LAG_DAYS = 3;
const SUPPORT_GATES = Object.freeze({
  DESIGN: Object.freeze({
    start: "2020-01-01",
    end_exclusive: "2023-01-01",
    minimum_evaluations: 1000,
    minimum_loss_realization_days: 30,
    minimum_other_days: 500,
    minimum_transitions: 12,
    minimum_loss_realization_years: 2,
    minimum_other_years: 3,
    maximum_single_year_loss_share: 0.65,
  }),
  VALIDATION: Object.freeze({
    start: "2023-01-01",
    end_exclusive: "2025-01-01",
    minimum_evaluations: 700,
    minimum_loss_realization_days: 10,
    minimum_other_days: 300,
    minimum_transitions: 4,
    minimum_loss_realization_years: 1,
    minimum_other_years: 2,
    maximum_single_year_loss_share: 0.85,
  }),
});

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
  if (payload.data.length !== EXPECTED_ROWS) {
    throw new SourceReject(`SOURCE_REJECT:ROWS:${payload.data.length}`);
  }
  const decimal = /^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;
  const rows = payload.data.map((row, index) => {
    const keys = Object.keys(row).sort();
    if (JSON.stringify(keys) !== JSON.stringify(["SOPR", "asset", "time"])) {
      throw new SourceReject(`SOURCE_REJECT:ROW_KEYS:${index}:${keys.join(",")}`);
    }
    if (row.asset !== "btc" || !/^\d{4}-\d{2}-\d{2}T00:00:00(?:\.0+)?Z$/.test(row.time)) {
      throw new SourceReject(`SOURCE_REJECT:IDENTITY:${index}`);
    }
    if (typeof row.SOPR !== "string" || !decimal.test(row.SOPR)) {
      throw new SourceReject(`SOURCE_REJECT:SOPR_DECIMAL:${index}`);
    }
    const sopr = Number(row.SOPR);
    if (!Number.isFinite(sopr) || sopr <= 0 || sopr > 1000) {
      throw new SourceReject(`SOURCE_REJECT:SOPR_RANGE:${index}`);
    }
    return {
      date: row.time.slice(0, 10),
      time: Date.parse(row.time),
      sopr,
      rawSopr: row.SOPR,
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

function windowSupport(rows, gate) {
  const start = Date.parse(`${gate.start}T00:00:00Z`);
  const end = Date.parse(`${gate.end_exclusive}T00:00:00Z`);
  const observations = rows
    .map((row) => ({
      ...row,
      effectiveTime: row.time + AVAILABILITY_LAG_DAYS * DAY_MS,
      lossRealization: row.sopr < 1,
    }))
    .filter((row) => row.effectiveTime >= start && row.effectiveTime < end);
  let transitions = 0;
  for (let index = 1; index < observations.length; index += 1) {
    if (observations[index].lossRealization !== observations[index - 1].lossRealization) transitions += 1;
  }
  const lossRows = observations.filter((row) => row.lossRealization);
  const otherRows = observations.filter((row) => !row.lossRealization);
  const lossByYear = {};
  const otherYears = new Set();
  for (const row of observations) {
    const year = new Date(row.effectiveTime).getUTCFullYear().toString();
    if (row.lossRealization) lossByYear[year] = (lossByYear[year] || 0) + 1;
    else otherYears.add(year);
  }
  const maximumSingleYearLossShare = lossRows.length
    ? Math.max(...Object.values(lossByYear)) / lossRows.length
    : null;
  const checks = {
    evaluations: observations.length >= gate.minimum_evaluations,
    loss_realization_days: lossRows.length >= gate.minimum_loss_realization_days,
    other_days: otherRows.length >= gate.minimum_other_days,
    transitions: transitions >= gate.minimum_transitions,
    loss_realization_years: Object.keys(lossByYear).length >= gate.minimum_loss_realization_years,
    other_years: otherYears.size >= gate.minimum_other_years,
    single_year_loss_concentration: maximumSingleYearLossShare !== null
      && maximumSingleYearLossShare <= gate.maximum_single_year_loss_share,
  };
  return {
    start: gate.start,
    end_exclusive: gate.end_exclusive,
    evaluations: observations.length,
    loss_realization_days: lossRows.length,
    profit_or_breakeven_days: otherRows.length,
    transitions,
    loss_realization_years: Object.keys(lossByYear).sort(),
    profit_or_breakeven_years: [...otherYears].sort(),
    loss_realization_days_by_effective_year: lossByYear,
    maximum_single_year_loss_share: maximumSingleYearLossShare,
    checks,
    support_pass: Object.values(checks).every(Boolean),
  };
}

function featureFeasibility(rows) {
  const design = windowSupport(rows, SUPPORT_GATES.DESIGN);
  const validation = windowSupport(rows, SUPPORT_GATES.VALIDATION);
  return {
    threshold: "STRICTLY_BELOW_1",
    availability_lag_days: AVAILABILITY_LAG_DAYS,
    design,
    validation,
    all_support_gates_pass: design.support_pass && validation.support_pass,
  };
}

function requireOutputPath(file) {
  const resolved = path.resolve(file);
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!resolved.startsWith(`${stateRoot}${path.sep}`)) {
    throw new SourceReject(`OUTPUT_PATH_REJECT:${resolved}`);
  }
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
      headers: {
        Accept: "application/json",
        "User-Agent": "AgoraResearchCoinMetricsBtcSoprSourceProbe/1.0",
      },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) {
    throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  }
  const rows = parseRows(rawBytes);
  const feasibility = featureFeasibility(rows);
  if (!feasibility.all_support_gates_pass) {
    throw new SourceReject(`SOURCE_REJECT:STATE_SUPPORT:${JSON.stringify(feasibility)}`);
  }
  const normalizedBytes = Buffer.from(
    ["date,sopr", ...rows.map((row) => `${row.date},${row.rawSopr}`)].join("\n") + "\n",
    "utf8",
  );
  const bundle = {
    schema_version: "1",
    document_type: "COIN_METRICS_BTC_SOPR_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_AND_SUPPORT_PASS_NO_BTC_OUTCOME_ACCESS",
    publisher: "Coin Metrics Community API",
    source_metric: "SOPR",
    request_contract: {
      method: "GET",
      url: SOURCE_URL,
      credentials: "DENY",
      redirect: "DENY",
      automatic_retry: "DENY",
      maximum_response_bytes: MAX_RESPONSE_BYTES,
    },
    raw_response: {
      status: response.status,
      content_type: response.headers.get("content-type"),
      bytes: rawBytes.length,
      sha256: sha256(rawBytes),
      rows: rows.length,
    },
    normalized_subset: {
      sha256: sha256(normalizedBytes),
      bytes: normalizedBytes.length,
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "sopr"],
    },
    pre_outcome_feature_feasibility: feasibility,
    publication_timing_boundary: "A source day D becomes usable only at D plus three calendar days 00:00 UTC. This conservative lag is historical research timing, not proof of original point-in-time publication.",
    feature_boundary: "SOPR strictly below one is loss realization; SOPR greater than or equal to one is profit or break-even realization. No smoothing, alternate metric, threshold, cohort or inverse relation is permitted.",
    revision_boundary: "The exact Community API response is a sealed present-vintage historical input. Original daily publication timestamps and original vintages remain MISSING_PROOF; any historical pass requires untouched prospective evidence before candidate promotion.",
    license_boundary: "Coin Metrics documents Community API data as no-key and free for non-commercial use under a Creative Commons license. Raw and normalized bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized.",
    scope_note: "Free source and state-support gate only. No BTC outcome, strategy, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
    raw: {
      path: path.relative(REPO_ROOT, rawFile).replaceAll("\\", "/"),
      ...bundle.raw_response,
    },
    normalized: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      ...bundle.normalized_subset,
    },
    feasibility,
  })}\n`);
}

module.exports = {
  AVAILABILITY_LAG_DAYS,
  EXPECTED_ROWS,
  SOURCE_URL,
  SUPPORT_GATES,
  SourceReject,
  featureFeasibility,
  parseRows,
  windowSupport,
};

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  });
}
