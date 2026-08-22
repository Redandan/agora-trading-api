#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const engine = require("./btc_treasury_term_spread_long_cash_historical.cjs");

const REPO_ROOT = path.resolve(__dirname, "..");
const PRIOR_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-onchain-activity-breadth-primary-prior.v1.json");
const SOURCE_METADATA = path.join(REPO_ROOT, "research_pipeline/examples/coinmetrics-btc-onchain-activity-daily-2018-2024.v1.source.json");
const HYPOTHESIS_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-onchain-activity-breadth-long-cash-v1.hypothesis.json");
const PARITY_REFERENCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json");
const SOURCE_BUNDLE = path.join(REPO_ROOT, ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-source-bundle.json");

const EXPERIMENT_ID = "btc-onchain-activity-breadth-long-cash-historical-v1";
const EXPECTED_MANIFEST_TYPE = "BTC_ONCHAIN_ACTIVITY_BREADTH_LONG_CASH_HISTORICAL_MANIFEST_V1";
const EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_BTC_ROWS = 52608;
const EXPECTED_ACTIVITY_SHA256 = "f47e2c082f21683befa4092f7bdc83de92c3717859fee5ec70f9b52117e4f669";
const EXPECTED_ACTIVITY_ROWS = 2557;
const EXPECTED_PRIOR_SHA256 = "6e199ca7cbaae2374b7f0a0aad2b5bcf08f8f788c6a5f57ff7895465da11d9de";
const EXPECTED_SOURCE_METADATA_SHA256 = "c06b986f8a5878998e04be91f6066070fd20ec07d12eed2ef2bdbef2c45bb27d";
const EXPECTED_SOURCE_BUNDLE_SHA256 = "41ed62da5ca1fdc7e36361db9318939ede775993958175cc6e7181cfb90f0aca";
const EXPECTED_HYPOTHESIS_SHA256 = "5f5e9ca72aa304a2c7b0a5041ae27d64baa433cb6f2508ae397645c1143d1033";
const EXPECTED_PARITY_SHA256 = "8410d722eb02702771c4fe9174b2cdcfaffbc6fbeb3df0fa63569906556259bc";
const DAY_MS = 24 * 60 * 60 * 1000;
const MEAN_DAYS = 28;
const COMPARISON_LAG_DAYS = 364;

const VARIANTS = [
  ["PRIMARY_BOTH", "primary", "BOTH"],
  ["NEIGHBOR_TXCNT_ONLY", "neighbor", "TXCNT_ONLY"],
  ["NEIGHBOR_ADRACTCNT_ONLY", "neighbor", "ADRACTCNT_ONLY"],
];
const SCENARIOS = {
  NORMAL: [0.0010, 0.0005],
  STRESS: [0.0020, 0.0010],
};
const WINDOWS = {
  design: ["2020-01-01T00:00:00", "2023-01-01T00:00:00"],
  validation: ["2023-01-01T00:00:00", "2025-01-01T00:00:00"],
};
const ANNUAL_WINDOWS = Object.fromEntries(
  [2020, 2021, 2022, 2023, 2024].map((year) => [
    String(year),
    [`${year}-01-01T00:00:00`, `${year + 1}-01-01T00:00:00`],
  ]),
);

class ResearchReject extends Error {}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function q(value) {
  if (!Number.isFinite(value)) return null;
  return value.toFixed(8);
}

function parseActivityRows(file) {
  const bytes = fs.readFileSync(file);
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== EXPECTED_ACTIVITY_SHA256) throw new ResearchReject(`ACTIVITY_REJECT:SHA256:${digest}`);
  const lines = bytes.toString("utf8").trimEnd().split(/\r?\n/);
  if (lines[0] !== "date,active_address_count,transaction_count") throw new ResearchReject("ACTIVITY_REJECT:HEADER");
  if (lines.length - 1 !== EXPECTED_ACTIVITY_ROWS) throw new ResearchReject(`ACTIVITY_REJECT:ROWS:${lines.length - 1}`);
  const rows = lines.slice(1).map((line, index) => {
    const fields = line.split(",");
    if (fields.length !== 3 || !/^\d{4}-\d{2}-\d{2}$/.test(fields[0])) {
      throw new ResearchReject(`ACTIVITY_REJECT:ROW:${index}`);
    }
    if (!/^\d+$/.test(fields[1]) || !/^\d+$/.test(fields[2])) throw new ResearchReject(`ACTIVITY_REJECT:INTEGER:${index}`);
    const activeAddressCount = Number(fields[1]);
    const txCount = Number(fields[2]);
    if (!Number.isSafeInteger(activeAddressCount) || activeAddressCount <= 0 || activeAddressCount > 100000000) {
      throw new ResearchReject(`ACTIVITY_REJECT:ADDRESS_VALUE:${index}`);
    }
    if (!Number.isSafeInteger(txCount) || txCount <= 0 || txCount > 10000000) {
      throw new ResearchReject(`ACTIVITY_REJECT:TX_VALUE:${index}`);
    }
    return { date: fields[0], dateTime: Date.parse(`${fields[0]}T00:00:00Z`), activeAddressCount, txCount };
  });
  if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") throw new ResearchReject("ACTIVITY_REJECT:BOUNDARY");
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].dateTime - rows[index - 1].dateTime !== DAY_MS) throw new ResearchReject(`ACTIVITY_REJECT:DAILY_CONTINUITY:${index}`);
  }
  return { rows, digest };
}

function targetsByEffectiveTime(rows, mode) {
  if (!["BOTH", "TXCNT_ONLY", "ADRACTCNT_ONLY"].includes(mode)) throw new ResearchReject(`FORMULA_REJECT:MODE:${mode}`);
  const targets = new Map();
  const txPrefix = [0];
  const addressPrefix = [0];
  for (const row of rows) {
    txPrefix.push(txPrefix.at(-1) + row.txCount);
    addressPrefix.push(addressPrefix.at(-1) + row.activeAddressCount);
  }
  for (let index = COMPARISON_LAG_DAYS + MEAN_DAYS - 1; index < rows.length; index += 1) {
    const row = rows[index];
    const currentStart = index - MEAN_DAYS + 1;
    const priorEnd = index - COMPARISON_LAG_DAYS;
    const priorStart = priorEnd - MEAN_DAYS + 1;
    const txPositive = (txPrefix[index + 1] - txPrefix[currentStart]) > (txPrefix[priorEnd + 1] - txPrefix[priorStart]);
    const addressPositive = (addressPrefix[index + 1] - addressPrefix[currentStart]) > (addressPrefix[priorEnd + 1] - addressPrefix[priorStart]);
    const target = mode === "BOTH" ? txPositive && addressPositive : mode === "TXCNT_ONLY" ? txPositive : addressPositive;
    const effectiveTime = row.dateTime + 2 * DAY_MS;
    if (targets.has(effectiveTime)) throw new ResearchReject(`FORMULA_REJECT:DUPLICATE_EFFECTIVE:${row.date}`);
    targets.set(effectiveTime, target);
  }
  return targets;
}

function simulateWindow(bars, targetsByVariant, window) {
  const output = {};
  const raw = {};
  for (const [variant] of VARIANTS) {
    output[variant] = {};
    raw[variant] = {};
    for (const [scenario, [feeRate, slippage]] of Object.entries(SCENARIOS)) {
      const result = engine.simulateScenario(bars, targetsByVariant[variant], window, feeRate, slippage);
      output[variant][scenario] = result.output;
      raw[variant][scenario] = result.raw;
    }
  }
  return { output, raw };
}

function requireBuyHoldParity(design, validation) {
  const actual = {
    designNormal: [
      design.output.PRIMARY_BOTH.NORMAL.buy_and_hold.total_return_pct,
      design.output.PRIMARY_BOTH.NORMAL.buy_and_hold.maximum_drawdown_pct,
      design.output.PRIMARY_BOTH.NORMAL.buy_and_hold.calmar_ratio,
    ],
    designStress: [design.output.PRIMARY_BOTH.STRESS.buy_and_hold.total_return_pct],
    validationNormal: [
      validation.output.PRIMARY_BOTH.NORMAL.buy_and_hold.total_return_pct,
      validation.output.PRIMARY_BOTH.NORMAL.buy_and_hold.maximum_drawdown_pct,
      validation.output.PRIMARY_BOTH.NORMAL.buy_and_hold.calmar_ratio,
    ],
    validationStress: [validation.output.PRIMARY_BOTH.STRESS.buy_and_hold.total_return_pct],
  };
  const expected = {
    designNormal: ["129.60544229", "77.18955925", "1.67905405"],
    designStress: ["129.26172157"],
    validationNormal: ["464.75475156", "32.28416349", "14.39575015"],
    validationStress: ["463.90931032"],
  };
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new ResearchReject(`ECONOMIC_REJECT:BUY_HOLD_PARITY:${JSON.stringify(actual)}`);
  }
}

function evaluateGates(design, validation, annual) {
  const primary = "PRIMARY_BOTH";
  const dn = design.raw[primary].NORMAL;
  const ds = design.raw[primary].STRESS;
  const vn = validation.raw[primary].NORMAL;
  const vs = validation.raw[primary].STRESS;
  const gates = {
    btc_dataset_sha256_and_52608_rows_match: true,
    coinmetrics_source_sha256_and_2557_consecutive_daily_rows_match: true,
    frozen_source_bundle_metadata_prior_hypothesis_runner_and_manifest_bindings_match: true,
    second_calendar_day_0000_utc_availability_and_frozen_28d_364d_formula_match: true,
    buy_hold_reference_ledger_parity_pass: true,
    primary_design_normal_total_return_pct_gt_0: dn.totalReturn > 0,
    primary_design_stress_total_return_pct_gt_0: ds.totalReturn > 0,
    primary_design_normal_drawdown_at_most_85pct_of_buy_hold: dn.drawdown <= 0.85 * dn.buyHoldDrawdown,
    primary_design_normal_upside_capture_at_least_60pct: dn.upsideCapture !== null && dn.upsideCapture >= 0.6,
    primary_design_normal_calmar_at_least_buy_hold: dn.calmar >= dn.buyHoldCalmar,
    primary_validation_normal_total_return_pct_gt_0: vn.totalReturn > 0,
    primary_validation_stress_total_return_pct_gt_0: vs.totalReturn > 0,
    primary_validation_normal_drawdown_at_most_85pct_of_buy_hold: vn.drawdown <= 0.85 * vn.buyHoldDrawdown,
    primary_validation_normal_upside_capture_at_least_60pct: vn.upsideCapture !== null && vn.upsideCapture >= 0.6,
    primary_validation_normal_calmar_at_least_buy_hold: vn.calmar >= vn.buyHoldCalmar,
    primary_validation_signal_evaluations_at_least_400: vn.signalEvaluations >= 400,
    primary_validation_position_changes_between_2_and_60: vn.positionChanges >= 2 && vn.positionChanges <= 60,
    primary_validation_stress_drawdown_no_more_than_normal_plus_3pp: vs.drawdown <= vn.drawdown + 3,
  };

  for (const neighbor of ["NEIGHBOR_TXCNT_ONLY", "NEIGHBOR_ADRACTCNT_ONLY"]) {
    const label = neighbor.toLowerCase();
    const nd = design.raw[neighbor].NORMAL;
    const nv = validation.raw[neighbor].NORMAL;
    const nvs = validation.raw[neighbor].STRESS;
    gates[`${label}_design_normal_total_return_pct_gt_0`] = nd.totalReturn > 0;
    gates[`${label}_validation_normal_total_return_pct_gt_0`] = nv.totalReturn > 0;
    gates[`${label}_validation_stress_total_return_pct_gt_0`] = nvs.totalReturn > 0;
    gates[`${label}_validation_drawdown_non_worse_than_buy_hold`] = nv.drawdown <= nv.buyHoldDrawdown;
    gates[`${label}_validation_calmar_at_least_75pct_of_buy_hold`] = nv.calmar >= 0.75 * nv.buyHoldCalmar;
  }

  const yearlyPrimary = Object.values(annual).map((value) => value.raw[primary]);
  const normalPositive = yearlyPrimary.filter((value) => value.NORMAL.totalReturn > 0).length;
  const stressPositive = yearlyPrimary.filter((value) => value.STRESS.totalReturn > 0).length;
  const drawdownNonworse = yearlyPrimary.filter((value) => value.NORMAL.drawdown <= value.NORMAL.buyHoldDrawdown).length;
  const calmarNonworse = yearlyPrimary.filter((value) => value.NORMAL.calmar >= value.NORMAL.buyHoldCalmar).length;
  const positiveReturns = yearlyPrimary.map((value) => Math.max(value.NORMAL.totalReturn, 0));
  const positiveSum = positiveReturns.reduce((sum, value) => sum + value, 0);
  const topYear = positiveSum > 0 ? (Math.max(...positiveReturns) / positiveSum) * 100 : 100;
  Object.assign(gates, {
    primary_normal_positive_annual_return_at_least_4_of_5: normalPositive >= 4,
    primary_stress_positive_annual_return_at_least_4_of_5: stressPositive >= 4,
    primary_annual_drawdown_non_worse_5_of_5: drawdownNonworse === 5,
    primary_annual_calmar_non_worse_at_least_3_of_5: calmarNonworse >= 3,
    primary_top_year_positive_return_contribution_at_most_60pct: topYear <= 60,
    primary_validation_terminal_liquidation_adjusted_return_pct_gt_0: vn.terminalLiquidationReturn > 0,
    primary_validation_terminal_liquidation_cost_at_most_1pp: vn.terminalLiquidationCost <= 1,
    primary_validation_p90_hold_at_most_17520_hours: vn.p90Hold <= 17520,
    primary_validation_terminal_holding_age_at_most_17520_hours: vn.terminalHoldingAge <= 17520,
  });

  const neighborBreadth = {};
  for (const neighbor of ["NEIGHBOR_TXCNT_ONLY", "NEIGHBOR_ADRACTCNT_ONLY"]) {
    const positiveYears = Object.values(annual).filter((value) => value.raw[neighbor].NORMAL.totalReturn > 0).length;
    neighborBreadth[neighbor] = `${positiveYears}_of_5`;
    gates[`${neighbor.toLowerCase()}_normal_positive_annual_return_at_least_3_of_5`] = positiveYears >= 3;
  }
  return {
    gates,
    failed: Object.entries(gates).filter(([, passed]) => !passed).map(([name]) => name),
    breadth: {
      primary_normal_positive_years: `${normalPositive}_of_5`,
      primary_stress_positive_years: `${stressPositive}_of_5`,
      primary_drawdown_non_worse_years: `${drawdownNonworse}_of_5`,
      primary_calmar_non_worse_years: `${calmarNonworse}_of_5`,
      primary_top_year_positive_return_contribution_pct: q(topYear),
      neighbor_normal_positive_years: neighborBreadth,
      primary_validation_top_positive_episode_contribution_pct:
        validation.output[primary].NORMAL.candidate.top_positive_episode_contribution_pct,
    },
  };
}

function validateManifest(manifest) {
  if (manifest.document_type !== EXPECTED_MANIFEST_TYPE) throw new ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE");
  if (manifest.experiment_id !== EXPERIMENT_ID) throw new ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID");
  if (manifest.authorization !== "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE") throw new ResearchReject("MANIFEST_REJECT:AUTHORIZATION");
  if (manifest.oos_access !== "DENY") throw new ResearchReject("MANIFEST_REJECT:OOS");
  const policy = manifest.strategy_policy;
  const actualVariants = policy.variants.map((value) => [value.variant_id, value.role, value.axes, value.combination]);
  const expectedVariants = [
    ["onchain-activity-both-v1", "primary", ["TxCnt", "AdrActCnt"], "AND"],
    ["onchain-activity-txcnt-only-v1", "neighbor", ["TxCnt"], "SINGLE"],
    ["onchain-activity-adractcnt-only-v1", "neighbor", ["AdrActCnt"], "SINGLE"],
  ];
  if (
    policy.decision_clock !== "SECOND_CALENDAR_DAY_0000_UTC_AFTER_OBSERVATION_DAY" ||
    policy.relation !== "STRICTLY_ABOVE" ||
    policy.mean_days !== 28 ||
    policy.comparison_lag_days !== 364 ||
    policy.long_target !== "BTC_100_PERCENT" ||
    policy.risk_off_target !== "CASH_100_PERCENT" ||
    policy.cash_yield !== "ZERO" ||
    JSON.stringify(actualVariants) !== JSON.stringify(expectedVariants)
  ) throw new ResearchReject("MANIFEST_REJECT:POLICY");
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  const expectedBindings = {
    "research_pipeline/examples/btc-onchain-activity-breadth-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
    "research_pipeline/examples/coinmetrics-btc-onchain-activity-daily-2018-2024.v1.source.json": EXPECTED_SOURCE_METADATA_SHA256,
    ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-source-bundle.json": EXPECTED_SOURCE_BUNDLE_SHA256,
    ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-btc-onchain-activity-2018-2024.csv": EXPECTED_ACTIVITY_SHA256,
    "research_pipeline/examples/btc-onchain-activity-breadth-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json": EXPECTED_PARITY_SHA256,
    "research/btc_onchain_activity_breadth_long_cash_historical.cjs": sha256File(__filename),
  };
  if (JSON.stringify(bindings) !== JSON.stringify(expectedBindings)) {
    throw new ResearchReject(`MANIFEST_REJECT:SOURCE_BINDINGS:${JSON.stringify(bindings)}`);
  }
}

function verifyFrozenSources() {
  const expected = [
    [PRIOR_SOURCE, EXPECTED_PRIOR_SHA256],
    [SOURCE_METADATA, EXPECTED_SOURCE_METADATA_SHA256],
    [SOURCE_BUNDLE, EXPECTED_SOURCE_BUNDLE_SHA256],
    [HYPOTHESIS_SOURCE, EXPECTED_HYPOTHESIS_SHA256],
    [PARITY_REFERENCE, EXPECTED_PARITY_SHA256],
  ];
  for (const [file, digest] of expected) {
    if (!fs.existsSync(file) || sha256File(file) !== digest) throw new ResearchReject(`BINDING_REJECT:${path.relative(REPO_ROOT, file)}`);
  }
}

function buildOutput(inputFile, activityFile, manifestFile) {
  verifyFrozenSources();
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  validateManifest(manifest);
  const btc = engine.parseBtcRows(inputFile);
  if (btc.digest !== EXPECTED_BTC_SHA256 || btc.bars.length !== EXPECTED_BTC_ROWS) throw new ResearchReject("BTC_REJECT:BINDING");
  const activity = parseActivityRows(activityFile);
  const targets = Object.fromEntries(VARIANTS.map(([variant, , mode]) => [variant, targetsByEffectiveTime(activity.rows, mode)]));
  const design = simulateWindow(btc.bars, targets, WINDOWS.design);
  const validation = simulateWindow(btc.bars, targets, WINDOWS.validation);
  const annual = Object.fromEntries(
    Object.entries(ANNUAL_WINDOWS).map(([year, window]) => [year, simulateWindow(btc.bars, targets, window)]),
  );
  requireBuyHoldParity(design, validation);
  const evaluation = evaluateGates(design, validation, annual);
  const passed = evaluation.failed.length === 0;
  return {
    schema_version: "1",
    document_type: "BTC_ONCHAIN_ACTIVITY_BREADTH_LONG_CASH_HISTORICAL_RESULT_V1",
    experiment_id: EXPERIMENT_ID,
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    research_classification: "HISTORICAL_PREREGISTERED_DESIGN_VALIDATION_NO_OOS",
    status: passed
      ? "HISTORICAL_CANDIDATE_FROZEN_OOS_UNOPENED_REPORTED_NOT_ACTIVATED"
      : "NO_CANDIDATE_CLOSE_BTC_ONCHAIN_ACTIVITY_BREADTH_LONG_CASH_FAMILY",
    decision: passed
      ? "PRESERVE_FROZEN_PRIMARY_FOR_SEPARATELY_SEALED_INDEPENDENT_OOS_WITHOUT_ACTIVATION"
      : "PERMANENTLY_CLOSE_EXACT_ONCHAIN_ACTIVITY_BREADTH_LONG_CASH_FAMILY_AND_SINGLE_AXIS_NEIGHBORS_WITHOUT_TUNING",
    inputs: {
      btc: {
        path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"),
        sha256: btc.digest,
        rows: btc.bars.length,
        selection_cutoff: "2025-01-01T00:00:00",
      },
      onchain_activity: {
        path: path.relative(REPO_ROOT, activityFile).replaceAll("\\", "/"),
        sha256: activity.digest,
        rows: activity.rows.length,
        first_date: activity.rows[0].date,
        last_date: activity.rows.at(-1).date,
        source_bundle_sha256: EXPECTED_SOURCE_BUNDLE_SHA256,
      },
    },
    policy: {
      primary: "BOTH_28D_MEAN_TXCNT_AND_ADRACTCNT_STRICTLY_ABOVE_CORRESPONDING_28D_MEAN_364_DAYS_EARLIER",
      rejection_neighbors: [
        "28D_MEAN_TXCNT_STRICTLY_ABOVE_CORRESPONDING_28D_MEAN_364_DAYS_EARLIER",
        "28D_MEAN_ADRACTCNT_STRICTLY_ABOVE_CORRESPONDING_28D_MEAN_364_DAYS_EARLIER",
      ],
      mean_days: 28,
      comparison_lag_days: 364,
      effective_time: "SECOND_CALENDAR_DAY_0000_UTC_AFTER_OBSERVATION_DAY",
      execution: "NEXT_HOURLY_OPEN",
      missing_date_policy: "REJECT_NON_CONSECUTIVE_SOURCE_NO_FILL_NO_INTERPOLATION",
      long_target: "BTC_100_PERCENT",
      risk_off_target: "ZERO_YIELD_CASH_100_PERCENT",
    },
    windows: { design: design.output, validation: validation.output },
    annual_fair_reset: Object.fromEntries(Object.entries(annual).map(([year, value]) => [year, value.output])),
    breadth_and_concentration: evaluation.breadth,
    gates: evaluation.gates,
    failed_gates: evaluation.failed,
    all_gates_pass: passed,
    oos_opened: false,
    claim_boundary: "Historical preregistered Design and Validation through 2024 only. A pass still requires untouched independent OOS and remains REPORTED_NOT_ACTIVATED; a failure permanently closes only the exact 28-day versus 52-week lagged on-chain activity breadth policy and its frozen single-axis neighbors.",
    scope_note: "No paid API, second timer, second writer, unsealed backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
  };
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--input", "--activity", "--manifest", "--output"]) {
    if (!values[name]) throw new ResearchReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

function main() {
  const args = argumentsByName(process.argv.slice(2));
  const inputFile = path.resolve(args["--input"]);
  const activityFile = path.resolve(args["--activity"]);
  const manifestFile = path.resolve(args["--manifest"]);
  const outputFile = path.resolve(args["--output"]);
  for (const file of [inputFile, activityFile, manifestFile]) {
    if (!file.startsWith(`${REPO_ROOT}${path.sep}`)) throw new ResearchReject(`PATH_REJECT:${file}`);
  }
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!outputFile.startsWith(`${stateRoot}${path.sep}`)) throw new ResearchReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new ResearchReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const result = buildOutput(inputFile, activityFile, manifestFile);
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, `${JSON.stringify(canonical(result))}\n`, { flag: "wx" });
  process.stdout.write(`${JSON.stringify({
    status: result.status,
    output: path.relative(REPO_ROOT, outputFile).replaceAll("\\", "/"),
    sha256: sha256File(outputFile),
    failed_gates: result.failed_gates,
  })}\n`);
}

module.exports = {
  ResearchReject,
  buildOutput,
  evaluateGates,
  parseActivityRows,
  targetsByEffectiveTime,
  validateManifest,
};

if (require.main === module) main();
