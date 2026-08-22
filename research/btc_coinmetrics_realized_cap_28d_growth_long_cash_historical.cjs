#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const engine = require("./btc_treasury_term_spread_long_cash_historical.cjs");

const REPO_ROOT = path.resolve(__dirname, "..");
const PRIOR_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-primary-prior.v1.json");
const SOURCE_METADATA = path.join(REPO_ROOT, "research_pipeline/examples/coinmetrics-btc-realized-cap-28d-growth-daily-2018-2024.v1.source.json");
const SOURCE_BUNDLE = path.join(REPO_ROOT, ".research-state/experiments/btc-coinmetrics-realized-cap-28d-growth-long-cash-historical-v1/inputs/coinmetrics-realized-cap-source-bundle.json");
const HYPOTHESIS_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-v1.hypothesis.json");
const ENGINE_SOURCE = path.join(REPO_ROOT, "research/btc_treasury_term_spread_long_cash_historical.cjs");

const EXPERIMENT_ID = "btc-coinmetrics-realized-cap-28d-growth-long-cash-historical-v1";
const EXPECTED_MANIFEST_TYPE = "BTC_COINMETRICS_REALIZED_CAP_28D_GROWTH_LONG_CASH_HISTORICAL_MANIFEST_V1";
const EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_BTC_ROWS = 52608;
const EXPECTED_REALIZED_CAP_SHA256 = "d24ab2b1445226894feb8fef0f7843485f9fc520d79992b2ecb14a825c96056a";
const EXPECTED_REALIZED_CAP_ROWS = 2557;
const EXPECTED_PRIOR_SHA256 = "6103cb5805abfb7bfadd5582b0b04074ea50522a332e2469448ba3d5443fc110";
const EXPECTED_SOURCE_METADATA_SHA256 = "ea018692d18db827da8d2ae789766a985581edf9544d3024a2e3e60aa09a8559";
const EXPECTED_SOURCE_BUNDLE_SHA256 = "e097f4c7da2153ab2f1387d54f0400ef745972bda645a4202d83783ac812d33b";
const EXPECTED_HYPOTHESIS_SHA256 = "75a929314ccd787593ee5bdd5badc604ed9bbe1a88a4bd7184541893387c9cc1";
const EXPECTED_ENGINE_SHA256 = "cd375a15ed0f6cf5801f9fd26563753c06af895a81da34e6c23643c6e00320e9";
const DAY_MS = 24 * 60 * 60 * 1000;
const GROWTH_LAG_DAYS = 28;
const AVAILABILITY_LAG_DAYS = 3;

const VARIANT = "PRIMARY_REALIZED_CAP_28D_GROWTH";
const SCENARIOS = {
  NORMAL: [0.0010, 0.0005],
  STRESS: [0.0020, 0.0010],
};
const WINDOWS = {
  design: ["2019-01-01T00:00:00", "2023-01-01T00:00:00"],
  validation: ["2023-01-01T00:00:00", "2025-01-01T00:00:00"],
};
const ANNUAL_WINDOWS = Object.fromEntries(
  [2019, 2020, 2021, 2022, 2023, 2024].map((year) => [
    String(year),
    [`${year}-01-01T00:00:00`, `${year + 1}-01-01T00:00:00`],
  ]),
);
const FROZEN_GATES = {
  direct_total_return_delta_pp_strictly_positive_design_normal_and_stress: true,
  direct_total_return_delta_pp_strictly_positive_validation_normal_and_stress: true,
  maximum_drawdown_non_worse_than_buy_hold_design_and_validation_normal: true,
  calmar_at_least_buy_hold_design_and_validation_normal: true,
  upside_capture_at_least: 0.60,
  source_feature_counts: {
    design: { evaluations: 209, positive: 128, nonpositive: 81, transitions: 12 },
    validation: { evaluations: 104, positive: 89, nonpositive: 15, transitions: 9 },
  },
  validation_position_changes_min: 2,
  validation_position_changes_max: 30,
  validation_stress_drawdown_max_above_normal_pp: 3,
  top_positive_episode_contribution_max_pct: 60,
  annual_normal_positive_delta_min_years_of_6: 4,
  annual_stress_positive_delta_min_years_of_6: 4,
  annual_drawdown_non_worse_min_years_of_6: 5,
  annual_calmar_non_worse_min_years_of_6: 4,
  top_positive_annual_delta_contribution_max_pct: 60,
  validation_terminal_liquidation_delta_pp_strictly_positive: true,
  validation_terminal_liquidation_cost_max_pp: 1,
  validation_p90_hold_max_hours: 17520,
  validation_terminal_holding_age_max_hours: 17520,
};

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
  return Number.isFinite(value) ? value.toFixed(8) : null;
}

function parseRealizedCapRows(file) {
  const bytes = fs.readFileSync(file);
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== EXPECTED_REALIZED_CAP_SHA256) throw new ResearchReject(`REALIZED_CAP_REJECT:SHA256:${digest}`);
  const lines = bytes.toString("utf8").trimEnd().split(/\r?\n/);
  if (lines[0] !== "date,cap_mrkt_cur_usd,mvrv,cap_real_usd") throw new ResearchReject("REALIZED_CAP_REJECT:HEADER");
  if (lines.length - 1 !== EXPECTED_REALIZED_CAP_ROWS) throw new ResearchReject(`REALIZED_CAP_REJECT:ROWS:${lines.length - 1}`);
  const rows = lines.slice(1).map((line, index) => {
    const fields = line.split(",");
    if (fields.length !== 4 || !/^\d{4}-\d{2}-\d{2}$/.test(fields[0])) throw new ResearchReject(`REALIZED_CAP_REJECT:ROW:${index}`);
    if (!fields.slice(1).every((value) => /^\d+(?:\.\d+)?$/.test(value))) throw new ResearchReject(`REALIZED_CAP_REJECT:DECIMAL:${index}`);
    const marketCap = Number(fields[1]);
    const mvrv = Number(fields[2]);
    const realizedCap = Number(fields[3]);
    if (![marketCap, mvrv, realizedCap].every((value) => Number.isFinite(value) && value > 0)) {
      throw new ResearchReject(`REALIZED_CAP_REJECT:VALUE:${index}`);
    }
    const derived = marketCap / mvrv;
    if (Math.abs(derived - realizedCap) / realizedCap > 1e-12) throw new ResearchReject(`REALIZED_CAP_REJECT:IDENTITY:${index}`);
    return { date: fields[0], dateTime: Date.parse(`${fields[0]}T00:00:00Z`), realizedCap };
  });
  if (rows[0].date !== "2018-01-01" || rows.at(-1).date !== "2024-12-31") throw new ResearchReject("REALIZED_CAP_REJECT:BOUNDARY");
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].dateTime - rows[index - 1].dateTime !== DAY_MS) throw new ResearchReject(`REALIZED_CAP_REJECT:DAILY_CONTINUITY:${index}`);
  }
  return { rows, digest };
}

function targetsByEffectiveTime(rows) {
  const targets = new Map();
  for (let index = GROWTH_LAG_DAYS; index < rows.length; index += 1) {
    const current = rows[index];
    if (new Date(current.dateTime).getUTCDay() !== 0) continue;
    const prior = rows[index - GROWTH_LAG_DAYS];
    const effectiveTime = current.dateTime + AVAILABILITY_LAG_DAYS * DAY_MS;
    if (targets.has(effectiveTime)) throw new ResearchReject(`FORMULA_REJECT:DUPLICATE_EFFECTIVE:${current.date}`);
    targets.set(effectiveTime, current.realizedCap > prior.realizedCap);
  }
  const times = [...targets.keys()];
  for (let index = 1; index < times.length; index += 1) {
    if (times[index] - times[index - 1] !== 7 * DAY_MS) throw new ResearchReject(`FORMULA_REJECT:WEEKLY_LATTICE:${index}`);
  }
  return targets;
}

function summarizeTargets(targets, window) {
  const start = Date.parse(`${window[0]}Z`);
  const end = Date.parse(`${window[1]}Z`);
  const values = [...targets.entries()].filter(([time]) => start <= time && time < end).map(([, target]) => target);
  let transitions = 0;
  for (let index = 1; index < values.length; index += 1) if (values[index] !== values[index - 1]) transitions += 1;
  return {
    evaluations: values.length,
    positive: values.filter(Boolean).length,
    nonpositive: values.filter((value) => !value).length,
    transitions,
  };
}

function simulateWindow(bars, targets, window) {
  const output = { [VARIANT]: {} };
  const raw = { [VARIANT]: {} };
  for (const [scenario, [feeRate, slippage]] of Object.entries(SCENARIOS)) {
    const result = engine.simulateScenario(bars, targets, window, feeRate, slippage);
    output[VARIANT][scenario] = result.output;
    raw[VARIANT][scenario] = result.raw;
  }
  return { output, raw };
}

function evaluateGates(design, validation, annual, sourceCounts) {
  const dn = design.raw[VARIANT].NORMAL;
  const ds = design.raw[VARIANT].STRESS;
  const vn = validation.raw[VARIANT].NORMAL;
  const vs = validation.raw[VARIANT].STRESS;
  const normalPositiveDelta = [];
  const stressPositiveDelta = [];
  let drawdownNonworse = 0;
  let calmarNonworse = 0;
  for (const value of Object.values(annual)) {
    const normal = value.raw[VARIANT].NORMAL;
    const stress = value.raw[VARIANT].STRESS;
    normalPositiveDelta.push(Math.max(normal.totalReturn - normal.buyHoldReturn, 0));
    stressPositiveDelta.push(Math.max(stress.totalReturn - stress.buyHoldReturn, 0));
    if (normal.drawdown <= normal.buyHoldDrawdown) drawdownNonworse += 1;
    if (normal.calmar >= normal.buyHoldCalmar) calmarNonworse += 1;
  }
  const normalPositiveYears = normalPositiveDelta.filter((value) => value > 0).length;
  const stressPositiveYears = stressPositiveDelta.filter((value) => value > 0).length;
  const positiveDeltaSum = normalPositiveDelta.reduce((sum, value) => sum + value, 0);
  const topYearContribution = positiveDeltaSum > 0 ? Math.max(...normalPositiveDelta) / positiveDeltaSum * 100 : 100;
  const gates = {
    btc_dataset_sha256_and_52608_rows_match: true,
    coinmetrics_realized_cap_sha256_and_2557_consecutive_daily_rows_match: true,
    frozen_prior_source_bundle_metadata_hypothesis_engine_runner_and_manifest_bindings_match: true,
    sunday_28d_growth_plus_3d_availability_and_168h_validity_match: true,
    frozen_design_feature_counts_match: JSON.stringify(sourceCounts.design) === JSON.stringify(FROZEN_GATES.source_feature_counts.design),
    frozen_validation_feature_counts_match: JSON.stringify(sourceCounts.validation) === JSON.stringify(FROZEN_GATES.source_feature_counts.validation),
    design_normal_total_return_delta_vs_buy_hold_gt_0: dn.totalReturn > dn.buyHoldReturn,
    design_stress_total_return_delta_vs_buy_hold_gt_0: ds.totalReturn > ds.buyHoldReturn,
    design_normal_drawdown_non_worse_than_buy_hold: dn.drawdown <= dn.buyHoldDrawdown,
    design_normal_calmar_at_least_buy_hold: dn.calmar >= dn.buyHoldCalmar,
    design_normal_upside_capture_at_least_60pct: dn.upsideCapture !== null && dn.upsideCapture >= FROZEN_GATES.upside_capture_at_least,
    validation_normal_total_return_delta_vs_buy_hold_gt_0: vn.totalReturn > vn.buyHoldReturn,
    validation_stress_total_return_delta_vs_buy_hold_gt_0: vs.totalReturn > vs.buyHoldReturn,
    validation_normal_drawdown_non_worse_than_buy_hold: vn.drawdown <= vn.buyHoldDrawdown,
    validation_normal_calmar_at_least_buy_hold: vn.calmar >= vn.buyHoldCalmar,
    validation_normal_upside_capture_at_least_60pct: vn.upsideCapture !== null && vn.upsideCapture >= FROZEN_GATES.upside_capture_at_least,
    validation_position_changes_between_2_and_30: vn.positionChanges >= FROZEN_GATES.validation_position_changes_min && vn.positionChanges <= FROZEN_GATES.validation_position_changes_max,
    validation_stress_drawdown_no_more_than_normal_plus_3pp: vs.drawdown <= vn.drawdown + FROZEN_GATES.validation_stress_drawdown_max_above_normal_pp,
    design_has_positive_episode_and_top_contribution_at_most_60pct: dn.hasPositiveEpisode && dn.topPositiveEpisodeContribution <= FROZEN_GATES.top_positive_episode_contribution_max_pct,
    validation_has_positive_episode_and_top_contribution_at_most_60pct: vn.hasPositiveEpisode && vn.topPositiveEpisodeContribution <= FROZEN_GATES.top_positive_episode_contribution_max_pct,
    annual_normal_positive_delta_at_least_4_of_6: normalPositiveYears >= FROZEN_GATES.annual_normal_positive_delta_min_years_of_6,
    annual_stress_positive_delta_at_least_4_of_6: stressPositiveYears >= FROZEN_GATES.annual_stress_positive_delta_min_years_of_6,
    annual_drawdown_non_worse_at_least_5_of_6: drawdownNonworse >= FROZEN_GATES.annual_drawdown_non_worse_min_years_of_6,
    annual_calmar_non_worse_at_least_4_of_6: calmarNonworse >= FROZEN_GATES.annual_calmar_non_worse_min_years_of_6,
    top_positive_annual_delta_contribution_at_most_60pct: topYearContribution <= FROZEN_GATES.top_positive_annual_delta_contribution_max_pct,
    validation_terminal_liquidation_delta_vs_buy_hold_gt_0: vn.terminalLiquidationReturn > vn.buyHoldReturn,
    validation_terminal_liquidation_cost_at_most_1pp: vn.terminalLiquidationCost <= FROZEN_GATES.validation_terminal_liquidation_cost_max_pp,
    validation_p90_hold_at_most_17520_hours: vn.p90Hold <= FROZEN_GATES.validation_p90_hold_max_hours,
    validation_terminal_holding_age_at_most_17520_hours: vn.terminalHoldingAge <= FROZEN_GATES.validation_terminal_holding_age_max_hours,
  };
  return {
    gates,
    failed: Object.entries(gates).filter(([, passed]) => !passed).map(([name]) => name),
    breadth: {
      annual_normal_positive_delta_years: `${normalPositiveYears}_of_6`,
      annual_stress_positive_delta_years: `${stressPositiveYears}_of_6`,
      annual_drawdown_non_worse_years: `${drawdownNonworse}_of_6`,
      annual_calmar_non_worse_years: `${calmarNonworse}_of_6`,
      top_positive_annual_delta_contribution_pct: q(topYearContribution),
      design_top_positive_episode_contribution_pct: dn.hasPositiveEpisode ? q(dn.topPositiveEpisodeContribution) : null,
      validation_top_positive_episode_contribution_pct: vn.hasPositiveEpisode ? q(vn.topPositiveEpisodeContribution) : null,
    },
  };
}

function validateManifest(manifest) {
  if (manifest.document_type !== EXPECTED_MANIFEST_TYPE || manifest.experiment_id !== EXPERIMENT_ID) throw new ResearchReject("MANIFEST_REJECT:IDENTITY");
  if (manifest.authorization !== "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" || manifest.oos_access !== "DENY") throw new ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS");
  const policy = manifest.strategy_policy;
  if (
    policy.variant_id !== "coinmetrics-realized-cap-28d-growth-v1" ||
    policy.variants !== 1 ||
    policy.observation_clock !== "SUNDAY_0000_UTC_DAILY_ROW" ||
    policy.growth_lag_days !== GROWTH_LAG_DAYS ||
    policy.relation !== "STRICTLY_ABOVE" ||
    policy.decision_clock !== "OBSERVATION_PLUS_THREE_CALENDAR_DAYS_0000_UTC" ||
    policy.signal_validity_hours !== 168 ||
    policy.long_target !== "BTC_100_PERCENT" ||
    policy.risk_off_target !== "CASH_100_PERCENT" ||
    policy.cash_yield !== "ZERO"
  ) throw new ResearchReject("MANIFEST_REJECT:POLICY");
  if (JSON.stringify(canonical(manifest.frozen_gates)) !== JSON.stringify(canonical(FROZEN_GATES))) throw new ResearchReject("MANIFEST_REJECT:FROZEN_GATES");
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  const expectedBindings = {
    "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
    "research_pipeline/examples/coinmetrics-btc-realized-cap-28d-growth-daily-2018-2024.v1.source.json": EXPECTED_SOURCE_METADATA_SHA256,
    ".research-state/experiments/btc-coinmetrics-realized-cap-28d-growth-long-cash-historical-v1/inputs/coinmetrics-realized-cap-source-bundle.json": EXPECTED_SOURCE_BUNDLE_SHA256,
    ".research-state/experiments/btc-coinmetrics-realized-cap-28d-growth-long-cash-historical-v1/inputs/coinmetrics-btc-realized-cap-2018-2024.csv": EXPECTED_REALIZED_CAP_SHA256,
    "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    "research/btc_treasury_term_spread_long_cash_historical.cjs": EXPECTED_ENGINE_SHA256,
    "research/btc_coinmetrics_realized_cap_28d_growth_long_cash_historical.cjs": sha256File(__filename),
  };
  if (Object.keys(bindings).length !== Object.keys(expectedBindings).length || Object.entries(expectedBindings).some(([file, digest]) => bindings[file] !== digest)) {
    throw new ResearchReject(`MANIFEST_REJECT:SOURCE_BINDINGS:${JSON.stringify(bindings)}`);
  }
}

function verifyFrozenSources() {
  for (const [file, digest] of [
    [PRIOR_SOURCE, EXPECTED_PRIOR_SHA256],
    [SOURCE_METADATA, EXPECTED_SOURCE_METADATA_SHA256],
    [SOURCE_BUNDLE, EXPECTED_SOURCE_BUNDLE_SHA256],
    [HYPOTHESIS_SOURCE, EXPECTED_HYPOTHESIS_SHA256],
    [ENGINE_SOURCE, EXPECTED_ENGINE_SHA256],
  ]) {
    if (!fs.existsSync(file) || sha256File(file) !== digest) throw new ResearchReject(`BINDING_REJECT:${path.relative(REPO_ROOT, file)}`);
  }
}

function buildOutput(inputFile, realizedCapFile, manifestFile) {
  verifyFrozenSources();
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  validateManifest(manifest);
  const btc = engine.parseBtcRows(inputFile);
  if (btc.digest !== EXPECTED_BTC_SHA256 || btc.bars.length !== EXPECTED_BTC_ROWS) throw new ResearchReject("BTC_REJECT:BINDING");
  const realizedCap = parseRealizedCapRows(realizedCapFile);
  const targets = targetsByEffectiveTime(realizedCap.rows);
  const sourceCounts = {
    design: summarizeTargets(targets, WINDOWS.design),
    validation: summarizeTargets(targets, WINDOWS.validation),
  };
  const design = simulateWindow(btc.bars, targets, WINDOWS.design);
  const validation = simulateWindow(btc.bars, targets, WINDOWS.validation);
  const annual = Object.fromEntries(Object.entries(ANNUAL_WINDOWS).map(([year, window]) => [year, simulateWindow(btc.bars, targets, window)]));
  const evaluation = evaluateGates(design, validation, annual, sourceCounts);
  const passed = evaluation.failed.length === 0;
  return {
    schema_version: "1",
    document_type: "BTC_COINMETRICS_REALIZED_CAP_28D_GROWTH_LONG_CASH_HISTORICAL_RESULT_V1",
    experiment_id: EXPERIMENT_ID,
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    research_classification: "HISTORICAL_PREREGISTERED_DESIGN_VALIDATION_NO_OOS",
    status: passed
      ? "HISTORICAL_CANDIDATE_FROZEN_OOS_UNOPENED_REPORTED_NOT_ACTIVATED"
      : "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_REALIZED_CAP_28D_GROWTH_LONG_CASH_FAMILY",
    decision: passed
      ? "PRESERVE_EXACT_FROZEN_POLICY_FOR_SEPARATELY_SEALED_INDEPENDENT_OOS_WITHOUT_ACTIVATION"
      : "PERMANENTLY_CLOSE_EXACT_SINGLE_VARIANT_REALIZED_CAP_28D_GROWTH_LONG_CASH_FAMILY_WITHOUT_TUNING",
    inputs: {
      btc: {
        path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"),
        sha256: btc.digest,
        rows: btc.bars.length,
        selection_cutoff: "2025-01-01T00:00:00",
      },
      realized_cap: {
        path: path.relative(REPO_ROOT, realizedCapFile).replaceAll("\\", "/"),
        sha256: realizedCap.digest,
        rows: realizedCap.rows.length,
        first_date: realizedCap.rows[0].date,
        last_date: realizedCap.rows.at(-1).date,
        source_bundle_sha256: EXPECTED_SOURCE_BUNDLE_SHA256,
      },
    },
    policy: {
      variant: "COINMETRICS_DERIVED_CAPREALUSD_SUNDAY_28_CALENDAR_DAY_GROWTH_STRICTLY_POSITIVE",
      variants: 1,
      growth_lag_days: GROWTH_LAG_DAYS,
      effective_time: "OBSERVATION_PLUS_THREE_CALENDAR_DAYS_0000_UTC",
      validity_hours: 168,
      execution: "NEXT_HOURLY_OPEN",
      missing_date_policy: "REJECT_NON_CONSECUTIVE_OR_INCOMPLETE_SOURCE_NO_FILL_NO_INTERPOLATION",
      long_target: "BTC_100_PERCENT",
      risk_off_target: "ZERO_YIELD_CASH_100_PERCENT",
    },
    source_feature_counts: sourceCounts,
    windows: { design: design.output, validation: validation.output },
    annual_fair_reset: Object.fromEntries(Object.entries(annual).map(([year, value]) => [year, value.output])),
    breadth_and_concentration: evaluation.breadth,
    gates: evaluation.gates,
    failed_gates: evaluation.failed,
    all_gates_pass: passed,
    oos_opened: false,
    claim_boundary: "Historical preregistered Design and Validation through 2024 only. A pass still requires untouched independent OOS and remains REPORTED_NOT_ACTIVATED; a failure permanently closes only the exact Sunday, 28-calendar-day, three-day-lagged realized-cap growth policy.",
    scope_note: "No paid API, second timer, second writer, unsealed backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
  };
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--input", "--realized-cap", "--manifest", "--output"]) {
    if (!values[name]) throw new ResearchReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

function main() {
  const args = argumentsByName(process.argv.slice(2));
  const inputFile = path.resolve(args["--input"]);
  const realizedCapFile = path.resolve(args["--realized-cap"]);
  const manifestFile = path.resolve(args["--manifest"]);
  const outputFile = path.resolve(args["--output"]);
  for (const file of [inputFile, realizedCapFile, manifestFile]) {
    if (!file.startsWith(`${REPO_ROOT}${path.sep}`)) throw new ResearchReject(`PATH_REJECT:${file}`);
  }
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!outputFile.startsWith(`${stateRoot}${path.sep}`)) throw new ResearchReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new ResearchReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const result = buildOutput(inputFile, realizedCapFile, manifestFile);
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, `${JSON.stringify(canonical(result))}\n`, { flag: "wx" });
  process.stdout.write(`${JSON.stringify({
    status: result.status,
    output: path.relative(REPO_ROOT, outputFile).replaceAll("\\", "/"),
    sha256: sha256File(outputFile),
    failed_gates: result.failed_gates,
  })}\n`);
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = {
  FROZEN_GATES,
  ResearchReject,
  buildOutput,
  evaluateGates,
  parseRealizedCapRows,
  summarizeTargets,
  targetsByEffectiveTime,
  validateManifest,
};
