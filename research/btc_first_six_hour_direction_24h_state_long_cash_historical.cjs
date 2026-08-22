#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const engine = require("./btc_treasury_term_spread_long_cash_historical.cjs");
const probe = require("./btc_first_six_hour_direction_state_probe.cjs");

const REPO_ROOT = path.resolve(__dirname, "..");
const PRIOR_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-primary-prior.v1.json");
const FEASIBILITY_SOURCE = path.join(REPO_ROOT, ".research-state/experiments/btc-first-six-hour-direction-24h-state-long-cash-historical-v1/inputs/first-six-hour-direction-state-feasibility.json");
const HYPOTHESIS_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-v1.hypothesis.json");
const AMENDMENT_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-pre-execution-initial-state-amendment.v1.json");
const ENGINE_SOURCE = path.join(REPO_ROOT, "research/btc_treasury_term_spread_long_cash_historical.cjs");
const PROBE_SOURCE = path.join(REPO_ROOT, "research/btc_first_six_hour_direction_state_probe.cjs");

const EXPERIMENT_ID = "btc-first-six-hour-direction-24h-state-long-cash-historical-v1";
const EXPECTED_MANIFEST_TYPE = "BTC_FIRST_SIX_HOUR_DIRECTION_24H_STATE_LONG_CASH_HISTORICAL_MANIFEST_V1";
const EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_BTC_ROWS = 52608;
const EXPECTED_PRIOR_SHA256 = "a1535b9429b97bd7a923b686a3b82fd49cb3351b747962b1ad4bf03ad972775b";
const EXPECTED_FEASIBILITY_SHA256 = "d698b2b1c734d42b1dc4958b9457828dc3dae1c310392b20a1e423ebd716b3fc";
const EXPECTED_HYPOTHESIS_SHA256 = "b570258b0a6f581a3be92a2ee90ab34fce72cfd2f4dca53980768df313daa7dd";
const EXPECTED_AMENDMENT_SHA256 = "389cb2db45c44ec94eef8e312c439a7f4d8f2e120b79f2fde4d7930ae17de9f8";
const EXPECTED_ENGINE_SHA256 = "cd375a15ed0f6cf5801f9fd26563753c06af895a81da34e6c23643c6e00320e9";
const EXPECTED_PROBE_SHA256 = "a69039e782edce52c871a6c7608dcf413782fad3f857f1970d4029c5ddcbdcaf";

const VARIANT = "PRIMARY_FIRST_SIX_HOUR_DIRECTION";
const SCENARIOS = { NORMAL: [0.0010, 0.0005], STRESS: [0.0020, 0.0010] };
const WINDOWS = {
  design: ["2019-01-01T00:00:00", "2023-01-01T00:00:00"],
  validation: ["2023-01-01T00:00:00", "2025-01-01T00:00:00"],
};
const ANNUAL_WINDOWS = Object.fromEntries([2019, 2020, 2021, 2022, 2023, 2024].map((year) => [String(year), [`${year}-01-01T00:00:00`, `${year + 1}-01-01T00:00:00`]]));
const FROZEN_GATES = {
  source_feature_counts: {
    design: { evaluations: 1461, positive: 689, nonpositive: 772, transitions: 764 },
    validation: { evaluations: 731, positive: 370, nonpositive: 361, transitions: 378 },
  },
  upside_capture_at_least: 0.65,
  validation_position_changes_min: 100,
  validation_position_changes_max: 500,
  stress_drawdown_max_above_normal_pp: 3,
  top_positive_episode_contribution_max_pct: 40,
  annual_normal_positive_delta_min_years_of_6: 4,
  annual_stress_positive_delta_min_years_of_6: 4,
  annual_drawdown_non_worse_min_years_of_6: 5,
  annual_calmar_non_worse_min_years_of_6: 4,
  top_positive_annual_delta_contribution_max_pct: 60,
  validation_terminal_liquidation_cost_max_pp: 1,
  validation_p90_hold_max_hours: 720,
  validation_terminal_holding_age_max_hours: 720
};

class ResearchReject extends Error {}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  return value;
}

function q(value) {
  return Number.isFinite(value) ? value.toFixed(8) : null;
}

function targetsAndCounts(bars) {
  const states = probe.statesFromBars(bars);
  const targets = new Map(states.map((state) => [state.effectiveTime, state.target]));
  targets.set(Date.parse("2019-01-01T00:00:00Z"), false);
  return {
    targets: new Map([...targets.entries()].sort(([left], [right]) => left - right)),
    counts: {
      design: compactSummary(probe.summarize(states, "2019-01-01T00:00:00Z", "2023-01-01T00:00:00Z")),
      validation: compactSummary(probe.summarize(states, "2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z")),
    },
  };
}

function compactSummary(value) {
  return { evaluations: value.evaluations, positive: value.positive, nonpositive: value.nonpositive, transitions: value.transitions };
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
  const normalPositiveDeltas = [];
  const stressPositiveDeltas = [];
  let drawdownNonworse = 0;
  let calmarNonworse = 0;
  for (const value of Object.values(annual)) {
    const normal = value.raw[VARIANT].NORMAL;
    const stress = value.raw[VARIANT].STRESS;
    normalPositiveDeltas.push(Math.max(normal.totalReturn - normal.buyHoldReturn, 0));
    stressPositiveDeltas.push(Math.max(stress.totalReturn - stress.buyHoldReturn, 0));
    if (normal.drawdown <= normal.buyHoldDrawdown) drawdownNonworse += 1;
    if (normal.calmar >= normal.buyHoldCalmar) calmarNonworse += 1;
  }
  const normalPositiveYears = normalPositiveDeltas.filter((value) => value > 0).length;
  const stressPositiveYears = stressPositiveDeltas.filter((value) => value > 0).length;
  const positiveDeltaSum = normalPositiveDeltas.reduce((sum, value) => sum + value, 0);
  const topYearContribution = positiveDeltaSum > 0 ? Math.max(...normalPositiveDeltas) / positiveDeltaSum * 100 : 100;
  const gates = {
    btc_dataset_sha256_and_52608_rows_match: true,
    frozen_prior_feasibility_hypothesis_engine_probe_runner_and_manifest_bindings_match: true,
    first_six_complete_hours_0600_utc_decision_and_24h_validity_match: true,
    frozen_design_feature_counts_match: JSON.stringify(sourceCounts.design) === JSON.stringify(FROZEN_GATES.source_feature_counts.design),
    frozen_validation_feature_counts_match: JSON.stringify(sourceCounts.validation) === JSON.stringify(FROZEN_GATES.source_feature_counts.validation),
    design_normal_total_return_delta_vs_buy_hold_gt_0: dn.totalReturn > dn.buyHoldReturn,
    design_stress_total_return_delta_vs_buy_hold_gt_0: ds.totalReturn > ds.buyHoldReturn,
    design_normal_drawdown_non_worse_than_buy_hold: dn.drawdown <= dn.buyHoldDrawdown,
    design_normal_calmar_at_least_buy_hold: dn.calmar >= dn.buyHoldCalmar,
    design_normal_upside_capture_at_least_65pct: dn.upsideCapture !== null && dn.upsideCapture >= FROZEN_GATES.upside_capture_at_least,
    validation_normal_total_return_delta_vs_buy_hold_gt_0: vn.totalReturn > vn.buyHoldReturn,
    validation_stress_total_return_delta_vs_buy_hold_gt_0: vs.totalReturn > vs.buyHoldReturn,
    validation_normal_drawdown_non_worse_than_buy_hold: vn.drawdown <= vn.buyHoldDrawdown,
    validation_normal_calmar_at_least_buy_hold: vn.calmar >= vn.buyHoldCalmar,
    validation_normal_upside_capture_at_least_65pct: vn.upsideCapture !== null && vn.upsideCapture >= FROZEN_GATES.upside_capture_at_least,
    validation_position_changes_between_100_and_500: vn.positionChanges >= FROZEN_GATES.validation_position_changes_min && vn.positionChanges <= FROZEN_GATES.validation_position_changes_max,
    validation_stress_drawdown_no_more_than_normal_plus_3pp: vs.drawdown <= vn.drawdown + FROZEN_GATES.stress_drawdown_max_above_normal_pp,
    design_has_positive_episode_and_top_contribution_at_most_40pct: dn.hasPositiveEpisode && dn.topPositiveEpisodeContribution <= FROZEN_GATES.top_positive_episode_contribution_max_pct,
    validation_has_positive_episode_and_top_contribution_at_most_40pct: vn.hasPositiveEpisode && vn.topPositiveEpisodeContribution <= FROZEN_GATES.top_positive_episode_contribution_max_pct,
    annual_normal_positive_delta_at_least_4_of_6: normalPositiveYears >= FROZEN_GATES.annual_normal_positive_delta_min_years_of_6,
    annual_stress_positive_delta_at_least_4_of_6: stressPositiveYears >= FROZEN_GATES.annual_stress_positive_delta_min_years_of_6,
    annual_drawdown_non_worse_at_least_5_of_6: drawdownNonworse >= FROZEN_GATES.annual_drawdown_non_worse_min_years_of_6,
    annual_calmar_non_worse_at_least_4_of_6: calmarNonworse >= FROZEN_GATES.annual_calmar_non_worse_min_years_of_6,
    top_positive_annual_delta_contribution_at_most_60pct: topYearContribution <= FROZEN_GATES.top_positive_annual_delta_contribution_max_pct,
    validation_terminal_liquidation_delta_vs_buy_hold_gt_0: vn.terminalLiquidationReturn > vn.buyHoldReturn,
    validation_terminal_liquidation_cost_at_most_1pp: vn.terminalLiquidationCost <= FROZEN_GATES.validation_terminal_liquidation_cost_max_pp,
    validation_p90_hold_at_most_720_hours: vn.p90Hold <= FROZEN_GATES.validation_p90_hold_max_hours,
    validation_terminal_holding_age_at_most_720_hours: vn.terminalHoldingAge <= FROZEN_GATES.validation_terminal_holding_age_max_hours,
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
    policy.variant_id !== "first-six-hour-direction-24h-state-v1" || policy.variants !== 1 ||
    policy.observation_window !== "UTC_0000_THROUGH_0559_SIX_COMPLETE_H1_BARS" ||
    policy.relation !== "CLOSE_AT_0600_STRICTLY_ABOVE_OPEN_AT_0000" || policy.decision_clock !== "0600_UTC" ||
    policy.signal_validity_hours !== 24 || policy.execution !== "STATE_CHANGE_ONLY_AT_0600_UTC_HOURLY_OPEN" ||
    policy.long_target !== "BTC_100_PERCENT" || policy.risk_off_target !== "CASH_100_PERCENT" || policy.cash_yield !== "ZERO"
  ) throw new ResearchReject("MANIFEST_REJECT:POLICY");
  if (JSON.stringify(canonical(manifest.frozen_gates)) !== JSON.stringify(canonical(FROZEN_GATES))) throw new ResearchReject("MANIFEST_REJECT:FROZEN_GATES");
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  const expected = {
    "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
    ".research-state/experiments/btc-first-six-hour-direction-24h-state-long-cash-historical-v1/inputs/first-six-hour-direction-state-feasibility.json": EXPECTED_FEASIBILITY_SHA256,
    "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-pre-execution-initial-state-amendment.v1.json": EXPECTED_AMENDMENT_SHA256,
    "research/btc_treasury_term_spread_long_cash_historical.cjs": EXPECTED_ENGINE_SHA256,
    "research/btc_first_six_hour_direction_state_probe.cjs": EXPECTED_PROBE_SHA256,
    "research/btc_first_six_hour_direction_24h_state_long_cash_historical.cjs": sha256File(__filename),
  };
  if (Object.keys(bindings).length !== Object.keys(expected).length || Object.entries(expected).some(([file, digest]) => bindings[file] !== digest)) throw new ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS");
}

function verifyFrozenSources() {
  for (const [file, digest] of [[PRIOR_SOURCE, EXPECTED_PRIOR_SHA256], [FEASIBILITY_SOURCE, EXPECTED_FEASIBILITY_SHA256], [HYPOTHESIS_SOURCE, EXPECTED_HYPOTHESIS_SHA256], [AMENDMENT_SOURCE, EXPECTED_AMENDMENT_SHA256], [ENGINE_SOURCE, EXPECTED_ENGINE_SHA256], [PROBE_SOURCE, EXPECTED_PROBE_SHA256]]) {
    if (!fs.existsSync(file) || sha256File(file) !== digest) throw new ResearchReject(`BINDING_REJECT:${path.relative(REPO_ROOT, file)}`);
  }
}

function buildOutput(inputFile, manifestFile) {
  verifyFrozenSources();
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  validateManifest(manifest);
  const btc = engine.parseBtcRows(inputFile);
  if (btc.digest !== EXPECTED_BTC_SHA256 || btc.bars.length !== EXPECTED_BTC_ROWS) throw new ResearchReject("BTC_REJECT:BINDING");
  const feature = targetsAndCounts(btc.bars);
  const design = simulateWindow(btc.bars, feature.targets, WINDOWS.design);
  const validation = simulateWindow(btc.bars, feature.targets, WINDOWS.validation);
  const annual = Object.fromEntries(Object.entries(ANNUAL_WINDOWS).map(([year, window]) => [year, simulateWindow(btc.bars, feature.targets, window)]));
  const evaluation = evaluateGates(design, validation, annual, feature.counts);
  const passed = evaluation.failed.length === 0;
  return {
    schema_version: "1",
    document_type: "BTC_FIRST_SIX_HOUR_DIRECTION_24H_STATE_LONG_CASH_HISTORICAL_RESULT_V1",
    experiment_id: EXPERIMENT_ID,
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    research_classification: "HISTORICAL_PREREGISTERED_DESIGN_VALIDATION_NO_OOS",
    status: passed ? "HISTORICAL_CANDIDATE_FROZEN_OOS_UNOPENED_REPORTED_NOT_ACTIVATED" : "NO_CANDIDATE_CLOSE_BTC_FIRST_SIX_HOUR_DIRECTION_24H_STATE_LONG_CASH_FAMILY",
    decision: passed ? "PRESERVE_EXACT_FROZEN_POLICY_FOR_SEPARATELY_SEALED_INDEPENDENT_OOS_WITHOUT_ACTIVATION" : "PERMANENTLY_CLOSE_EXACT_SINGLE_VARIANT_FIRST_SIX_HOUR_DIRECTION_24H_STATE_FAMILY_WITHOUT_TUNING",
    inputs: { btc: { path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"), sha256: btc.digest, rows: btc.bars.length, selection_cutoff: "2025-01-01T00:00:00" } },
    policy: { variant: "FIRST_SIX_COMPLETE_UTC_HOURS_NET_DIRECTION_STRICTLY_POSITIVE", decision_time: "0600_UTC", validity_hours: 24, execution: "STATE_CHANGE_ONLY_AT_0600_UTC_HOURLY_OPEN", long_target: "BTC_100_PERCENT", risk_off_target: "ZERO_YIELD_CASH_100_PERCENT" },
    source_feature_counts: feature.counts,
    windows: { design: design.output, validation: validation.output },
    annual_fair_reset: Object.fromEntries(Object.entries(annual).map(([year, value]) => [year, value.output])),
    breadth_and_concentration: evaluation.breadth,
    gates: evaluation.gates,
    failed_gates: evaluation.failed,
    all_gates_pass: passed,
    oos_opened: false,
    claim_boundary: "Historical preregistered Design and Validation through 2024 only. A pass still requires untouched independent OOS and remains REPORTED_NOT_ACTIVATED; a failure permanently closes only the exact first-six-hour positive-direction 06:00 UTC rolling-state policy.",
    scope_note: "No paid API, second timer, second writer, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
  };
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--input", "--manifest", "--output"]) if (!values[name]) throw new ResearchReject(`ARGUMENT_REJECT:${name}`);
  return values;
}

function main() {
  const args = argumentsByName(process.argv.slice(2));
  const inputFile = path.resolve(args["--input"]);
  const manifestFile = path.resolve(args["--manifest"]);
  const outputFile = path.resolve(args["--output"]);
  for (const file of [inputFile, manifestFile]) if (!file.startsWith(`${REPO_ROOT}${path.sep}`)) throw new ResearchReject(`PATH_REJECT:${file}`);
  if (!outputFile.startsWith(`${path.join(REPO_ROOT, ".research-state")}${path.sep}`)) throw new ResearchReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new ResearchReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const result = buildOutput(inputFile, manifestFile);
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, `${JSON.stringify(canonical(result))}\n`, { flag: "wx" });
  process.stdout.write(`${JSON.stringify({ status: result.status, output: path.relative(REPO_ROOT, outputFile).replaceAll("\\", "/"), sha256: sha256File(outputFile), failed_gates: result.failed_gates })}\n`);
}

if (require.main === module) {
  try { main(); } catch (error) { process.stderr.write(`${error.name}:${error.message}\n`); process.exitCode = 1; }
}

module.exports = { FROZEN_GATES, ResearchReject, buildOutput, evaluateGates, targetsAndCounts, validateManifest };
