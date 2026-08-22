#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const PRIOR_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-daily-bollinger20-2-long-cash-primary-prior.v1.json");
const HYPOTHESIS_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-daily-bollinger20-2-long-cash-v1.hypothesis.json");
const PARITY_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json");

const EXPERIMENT_ID = "btc-daily-bollinger20-2-long-cash-historical-v1";
const EXPECTED_MANIFEST_TYPE = "BTC_DAILY_BOLLINGER20_2_LONG_CASH_HISTORICAL_MANIFEST_V1";
const EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_DATA_ROWS = 52608;
const EXPECTED_DAILY_ROWS = 2192;
const EXPECTED_PRIOR_SHA256 = "19b3be4627da6d4552dca2075a86bf5a6e2a0a7e55475f1b821dbde930c045f2";
const EXPECTED_HYPOTHESIS_SHA256 = "470033cb2019b28ab36b145b7109412b98a241c5a74aa07e141a6a7669c6c8e3";
const EXPECTED_PARITY_SHA256 = "8410d722eb02702771c4fe9174b2cdcfaffbc6fbeb3df0fa63569906556259bc";

const HOUR_MS = 3600000;
const SCENARIOS = {
  NORMAL: [0.001, 0.0005],
  STRESS: [0.002, 0.001],
};
const WINDOWS = {
  design: ["2020-01-01T00:00:00", "2023-01-01T00:00:00"],
  validation: ["2023-01-01T00:00:00", "2025-01-01T00:00:00"],
};
const ANNUAL = Object.fromEntries(
  [2020, 2021, 2022, 2023, 2024].map((year) => [
    String(year),
    [`${year}-01-01T00:00:00`, `${year + 1}-01-01T00:00:00`],
  ]),
);

class ResearchReject extends Error {}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function q(value) {
  if (!Number.isFinite(value)) throw new ResearchReject("ECONOMIC_REJECT:NON_FINITE");
  return value.toFixed(8);
}

function instant(value) {
  return Date.parse(`${value}Z`);
}

function iso(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function percentile(values, fraction) {
  if (!values.length) return null;
  const ordered = [...values].sort((a, b) => a - b);
  if (ordered.length === 1) return ordered[0];
  const position = (ordered.length - 1) * fraction;
  const low = Math.floor(position);
  const high = Math.min(low + 1, ordered.length - 1);
  return ordered[low] + (ordered[high] - ordered[low]) * (position - low);
}

function parseRows(file) {
  const bytes = fs.readFileSync(file);
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== EXPECTED_DATA_SHA256) throw new ResearchReject(`DATA_REJECT:SHA256:${digest}`);
  const lines = bytes.toString("utf8").trimEnd().split(/\r?\n/);
  if (lines.length !== EXPECTED_DATA_ROWS) throw new ResearchReject(`DATA_REJECT:ROWS:${lines.length}`);
  const bars = lines.map((line, index) => {
    const fields = line.split("\t");
    if (fields.length !== 7) throw new ResearchReject(`DATA_REJECT:FIELDS:${index}`);
    const [openText, closeText] = fields;
    const values = fields.slice(2).map(Number);
    if (values.some((value) => !Number.isFinite(value))) throw new ResearchReject(`DATA_REJECT:FINITE:${index}`);
    const [open, high, low, close, volume] = values;
    const openTime = instant(openText);
    const closeTime = instant(closeText);
    if (
      closeTime - openTime !== HOUR_MS ||
      high < Math.max(open, close) ||
      low > Math.min(open, close) ||
      high < low ||
      open <= 0 || close <= 0 || volume < 0
    ) throw new ResearchReject(`DATA_REJECT:OHLCV:${index}`);
    return { openText, closeText, openTime, closeTime, open, high, low, close, volume };
  });
  for (let index = 1; index < bars.length; index += 1) {
    if (bars[index].openTime !== bars[index - 1].closeTime) throw new ResearchReject(`DATA_REJECT:LATTICE:${index}`);
  }
  if (bars[0].openText !== "2019-01-01T00:00:00" || bars.at(-1).closeText !== "2025-01-01T00:00:00") {
    throw new ResearchReject("DATA_REJECT:BOUNDARY");
  }
  return { bars, digest };
}

function buildDaily(bars) {
  const daily = [];
  for (let start = 0; start < bars.length; start += 24) {
    const slice = bars.slice(start, start + 24);
    if (
      slice.length !== 24 ||
      new Date(slice[0].openTime).getUTCHours() !== 0 ||
      new Date(slice.at(-1).closeTime).getUTCHours() !== 0 ||
      slice.at(-1).closeTime - slice[0].openTime !== 24 * HOUR_MS
    ) throw new ResearchReject(`DATA_REJECT:UTC_DAY:${start}`);
    daily.push({ closeTime: slice.at(-1).closeTime, close: slice.at(-1).close });
  }
  if (daily.length !== EXPECTED_DAILY_ROWS) throw new ResearchReject(`DATA_REJECT:UTC_DAY_COUNT:${daily.length}`);
  return daily;
}

function populationBands(values) {
  if (values.length !== 20 || values.some((value) => !Number.isFinite(value))) {
    throw new ResearchReject("FORMULA_REJECT:BOLLINGER_WINDOW");
  }
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const variance = values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / values.length;
  const deviation = Math.sqrt(variance);
  return { mean, lower: mean - 2 * deviation, upper: mean + 2 * deviation };
}

function targetsByExecutionTime(daily) {
  if (daily.length < 21) throw new ResearchReject("DATA_REJECT:BOLLINGER_HISTORY");
  const bands = [];
  for (let index = 19; index < daily.length; index += 1) {
    bands[index] = populationBands(daily.slice(index - 19, index + 1).map((point) => point.close));
  }
  const targets = new Map();
  let targetLong = false;
  for (let index = 20; index < daily.length; index += 1) {
    const previous = daily[index - 1];
    const current = daily[index];
    const crossedBelowLower = previous.close >= bands[index - 1].lower && current.close < bands[index].lower;
    const crossedAboveUpper = previous.close <= bands[index - 1].upper && current.close > bands[index].upper;
    if (crossedBelowLower && crossedAboveUpper) throw new ResearchReject("FORMULA_REJECT:AMBIGUOUS_CROSS");
    if (crossedBelowLower) targetLong = true;
    if (crossedAboveUpper) targetLong = false;
    targets.set(current.closeTime, targetLong);
  }
  return targets;
}

class PathAccumulator {
  constructor() {
    this.peak = 1;
    this.maximumDrawdown = 0;
    this.currentUnderwaterHours = 0;
    this.maximumUnderwaterHours = 0;
    this.exposureSum = 0;
    this.observations = 0;
  }
  observe(equity, exposure) {
    if (!(equity > 0) || exposure < 0) throw new ResearchReject("ECONOMIC_REJECT:INVALID_PATH");
    if (equity > this.peak) {
      this.peak = equity;
      this.currentUnderwaterHours = 0;
    } else if (equity < this.peak) {
      this.currentUnderwaterHours += 1;
      this.maximumUnderwaterHours = Math.max(this.maximumUnderwaterHours, this.currentUnderwaterHours);
      this.maximumDrawdown = Math.max(this.maximumDrawdown, (this.peak - equity) / this.peak);
    } else {
      this.currentUnderwaterHours = 0;
    }
    this.exposureSum += exposure;
    this.observations += 1;
  }
  metrics(finalEquity) {
    const totalReturn = (finalEquity - 1) * 100;
    const drawdown = this.maximumDrawdown * 100;
    const calmar = drawdown > 0 ? totalReturn / drawdown : 0;
    return {
      output: {
        total_return_pct: q(totalReturn),
        maximum_drawdown_pct: q(drawdown),
        maximum_underwater_duration_hours: this.maximumUnderwaterHours,
        average_exposure_pct: q((this.exposureSum / this.observations) * 100),
        calmar_ratio: drawdown > 0 ? q(calmar) : null,
      },
      raw: { totalReturn, drawdown, calmar },
    };
  }
}

function buyAll(cash, price, feeRate, slippage) {
  const gross = cash / (1 + feeRate);
  const fee = gross * feeRate;
  return { quantity: gross / (price * (1 + slippage)), cash: cash - gross - fee, fee, gross };
}

function sellAll(quantity, price, feeRate, slippage) {
  const gross = quantity * price * (1 - slippage);
  const fee = gross * feeRate;
  return { net: gross - fee, fee, gross };
}

function benchmark(trading, feeRate, slippage) {
  const purchase = buyAll(1, trading[0].open, feeRate, slippage);
  const pathState = new PathAccumulator();
  let finalEquity = 1;
  for (const bar of trading) {
    const marketValue = purchase.quantity * bar.close;
    finalEquity = purchase.cash + marketValue;
    pathState.observe(finalEquity, marketValue / finalEquity);
  }
  const metrics = pathState.metrics(finalEquity);
  metrics.output.fees_equity_units = q(purchase.fee);
  metrics.output.turnover_equity_units = q(purchase.gross);
  return metrics;
}

function simulateScenario(bars, targets, window, feeRate, slippage) {
  const [startText, endText] = window;
  const start = instant(startText);
  const end = instant(endText);
  const trading = bars.filter((bar) => start <= bar.openTime && bar.openTime < end);
  if (!trading.length || trading[0].openTime !== start || trading.at(-1).closeTime !== end) {
    throw new ResearchReject(`DATA_REJECT:WINDOW:${startText}:${endText}`);
  }
  let cash = 1;
  let quantity = 0;
  let entryEquity = null;
  let entryTime = null;
  let realized = 0;
  let fees = 0;
  let turnover = 0;
  let signalEvaluations = 0;
  let longTargets = 0;
  let cashTargets = 0;
  let positionChanges = 0;
  let finalEquity = 1;
  const episodePnls = [];
  const holdHours = [];
  const pathState = new PathAccumulator();
  for (const bar of trading) {
    if (targets.has(bar.openTime)) {
      const target = targets.get(bar.openTime);
      signalEvaluations += 1;
      if (target) longTargets += 1; else cashTargets += 1;
      if (target && quantity === 0) {
        entryEquity = cash;
        const purchase = buyAll(cash, bar.open, feeRate, slippage);
        ({ quantity, cash } = purchase);
        entryTime = bar.openTime;
        fees += purchase.fee;
        turnover += purchase.gross;
        positionChanges += 1;
      } else if (!target && quantity > 0) {
        const sale = sellAll(quantity, bar.open, feeRate, slippage);
        cash += sale.net;
        const pnl = cash - entryEquity;
        realized += pnl;
        episodePnls.push(pnl);
        holdHours.push((bar.openTime - entryTime) / HOUR_MS);
        quantity = 0;
        entryEquity = null;
        entryTime = null;
        fees += sale.fee;
        turnover += sale.gross;
        positionChanges += 1;
      }
    }
    const marketValue = quantity * bar.close;
    finalEquity = cash + marketValue;
    pathState.observe(finalEquity, marketValue / finalEquity);
  }
  const metrics = pathState.metrics(finalEquity);
  const totalPnl = finalEquity - 1;
  const unrealized = totalPnl - realized;
  let terminalLiquidationEquity = finalEquity;
  if (quantity > 0) terminalLiquidationEquity = cash + sellAll(quantity, trading.at(-1).close, feeRate, slippage).net;
  const terminalLiquidationReturn = (terminalLiquidationEquity - 1) * 100;
  const positivePnls = episodePnls.filter((value) => value > 0);
  const topPositiveEpisodeContribution = positivePnls.length
    ? (Math.max(...positivePnls) / positivePnls.reduce((sum, value) => sum + value, 0)) * 100
    : 0;
  const p90Hold = percentile(holdHours, 0.9) ?? 0;
  const terminalHoldingAge = entryTime === null ? 0 : (end - entryTime) / HOUR_MS;
  Object.assign(metrics.output, {
    realized_return_pct: q(realized * 100),
    unrealized_return_pct: q(unrealized * 100),
    terminal_liquidation_adjusted_return_pct: q(terminalLiquidationReturn),
    terminal_liquidation_cost_pp: q(metrics.raw.totalReturn - terminalLiquidationReturn),
    fees_equity_units: q(fees),
    turnover_equity_units: q(turnover),
    signal_evaluation_count: signalEvaluations,
    long_target_count: longTargets,
    cash_target_count: cashTargets,
    position_change_count: positionChanges,
    completed_episode_count: episodePnls.length,
    winning_episode_count: positivePnls.length,
    median_hold_hours: holdHours.length ? q(percentile(holdHours, 0.5)) : null,
    p90_hold_hours: holdHours.length ? q(p90Hold) : null,
    terminal_position: quantity > 0,
    terminal_holding_age_hours: entryTime === null ? null : q(terminalHoldingAge),
    top_positive_episode_contribution_pct: positivePnls.length ? q(topPositiveEpisodeContribution) : null,
  });
  Object.assign(metrics.raw, {
    terminalLiquidationReturn,
    terminalLiquidationCost: metrics.raw.totalReturn - terminalLiquidationReturn,
    positionChanges,
    completedEpisodes: episodePnls.length,
    p90Hold,
    terminalHoldingAge,
    topPositiveEpisodeContribution,
    hasPositiveEpisode: positivePnls.length > 0,
  });
  const passive = benchmark(trading, feeRate, slippage);
  const upsideCapture = passive.raw.totalReturn > 0 ? metrics.raw.totalReturn / passive.raw.totalReturn : null;
  Object.assign(metrics.raw, {
    buyHoldReturn: passive.raw.totalReturn,
    buyHoldDrawdown: passive.raw.drawdown,
    buyHoldCalmar: passive.raw.calmar,
    upsideCapture,
  });
  return {
    output: {
      start: startText,
      end_exclusive: endText,
      candidate: metrics.output,
      buy_and_hold: passive.output,
      comparison: {
        total_return_delta_pp: q(metrics.raw.totalReturn - passive.raw.totalReturn),
        maximum_drawdown_delta_pp: q(metrics.raw.drawdown - passive.raw.drawdown),
        upside_capture_ratio: upsideCapture === null ? null : q(upsideCapture),
        calmar_ratio_to_buy_hold: passive.raw.calmar !== 0 ? q(metrics.raw.calmar / passive.raw.calmar) : null,
      },
    },
    raw: metrics.raw,
  };
}

function simulateWindow(bars, targets, window) {
  const output = {};
  const raw = {};
  for (const [scenario, [feeRate, slippage]] of Object.entries(SCENARIOS)) {
    const result = simulateScenario(bars, targets, window, feeRate, slippage);
    output[scenario] = result.output;
    raw[scenario] = result.raw;
  }
  return { output, raw };
}

function requireBuyHoldParity(design, validation) {
  const expected = {
    designNormal: ["129.60544229", "77.18955925", "1.67905405"],
    designStress: ["129.26172157"],
    validationNormal: ["464.75475156", "32.28416349", "14.39575015"],
    validationStress: ["463.90931032"],
  };
  const actual = {
    designNormal: [design.output.NORMAL.buy_and_hold.total_return_pct, design.output.NORMAL.buy_and_hold.maximum_drawdown_pct, design.output.NORMAL.buy_and_hold.calmar_ratio],
    designStress: [design.output.STRESS.buy_and_hold.total_return_pct],
    validationNormal: [validation.output.NORMAL.buy_and_hold.total_return_pct, validation.output.NORMAL.buy_and_hold.maximum_drawdown_pct, validation.output.NORMAL.buy_and_hold.calmar_ratio],
    validationStress: [validation.output.STRESS.buy_and_hold.total_return_pct],
  };
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new ResearchReject(`ECONOMIC_REJECT:BUY_HOLD_PARITY:${JSON.stringify(actual)}`);
}

function evaluateGates(design, validation, annual) {
  const dn = design.raw.NORMAL;
  const ds = design.raw.STRESS;
  const vn = validation.raw.NORMAL;
  const vs = validation.raw.STRESS;
  const yearly = Object.values(annual).map((value) => value.raw);
  const normalPositive = yearly.filter((value) => value.NORMAL.totalReturn > 0).length;
  const stressPositive = yearly.filter((value) => value.STRESS.totalReturn > 0).length;
  const drawdownNonworse = yearly.filter((value) => value.NORMAL.drawdown <= value.NORMAL.buyHoldDrawdown).length;
  const calmarBreadth = yearly.filter((value) => value.NORMAL.calmar >= 0.75 * value.NORMAL.buyHoldCalmar).length;
  const opportunityBreadth = yearly.filter((value) => {
    const year = value.NORMAL;
    return year.buyHoldReturn > 0 ? year.totalReturn >= 0.25 * year.buyHoldReturn : year.totalReturn >= year.buyHoldReturn;
  }).length;
  const positiveReturns = yearly.map((value) => Math.max(value.NORMAL.totalReturn, 0));
  const positiveSum = positiveReturns.reduce((sum, value) => sum + value, 0);
  const topYear = positiveSum > 0 ? (Math.max(...positiveReturns) / positiveSum) * 100 : 100;
  const gates = {
    dataset_sha256_and_52608_rows_match: true,
    hourly_lattice_ohlcv_and_2192_complete_utc_days_pass: true,
    frozen_runner_prior_hypothesis_and_parity_sha256_match: true,
    buy_hold_reference_ledger_parity_pass: true,
    primary_design_normal_total_return_pct_gt_0: dn.totalReturn > 0,
    primary_design_stress_total_return_pct_gt_0: ds.totalReturn > 0,
    primary_design_normal_drawdown_at_most_80pct_of_buy_hold: dn.drawdown <= 0.8 * dn.buyHoldDrawdown,
    primary_design_normal_calmar_at_least_75pct_of_buy_hold: dn.calmar >= 0.75 * dn.buyHoldCalmar,
    primary_validation_normal_total_return_pct_gt_0: vn.totalReturn > 0,
    primary_validation_stress_total_return_pct_gt_0: vs.totalReturn > 0,
    primary_validation_normal_drawdown_at_most_70pct_of_buy_hold: vn.drawdown <= 0.7 * vn.buyHoldDrawdown,
    primary_validation_normal_upside_capture_at_least_40pct: vn.upsideCapture !== null && vn.upsideCapture >= 0.4,
    primary_validation_normal_calmar_at_least_buy_hold: vn.calmar >= vn.buyHoldCalmar,
    primary_validation_completed_episodes_between_4_and_50: vn.completedEpisodes >= 4 && vn.completedEpisodes <= 50,
    primary_validation_position_changes_between_8_and_100: vn.positionChanges >= 8 && vn.positionChanges <= 100,
    primary_validation_average_exposure_between_5_and_85pct:
      Number(validation.output.NORMAL.candidate.average_exposure_pct) >= 5 && Number(validation.output.NORMAL.candidate.average_exposure_pct) <= 85,
    primary_validation_stress_drawdown_no_more_than_normal_plus_3pp: vs.drawdown <= vn.drawdown + 3,
    primary_validation_terminal_liquidation_adjusted_return_pct_gt_0: vn.terminalLiquidationReturn > 0,
    primary_validation_terminal_liquidation_cost_at_most_1pp: vn.terminalLiquidationCost <= 1,
    primary_normal_positive_annual_total_return_at_least_4_of_5: normalPositive >= 4,
    primary_stress_positive_annual_total_return_at_least_4_of_5: stressPositive >= 4,
    primary_normal_annual_drawdown_non_worse_5_of_5: drawdownNonworse === 5,
    primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5: calmarBreadth >= 3,
    primary_normal_annual_opportunity_value_at_least_4_of_5: opportunityBreadth >= 4,
    primary_top_year_positive_total_return_contribution_at_most_60pct: topYear <= 60,
    primary_validation_top_positive_episode_contribution_at_most_60pct:
      vn.hasPositiveEpisode && vn.topPositiveEpisodeContribution <= 60,
    primary_validation_p90_hold_at_most_17520_hours: vn.p90Hold <= 17520,
    primary_validation_terminal_holding_age_at_most_17520_hours: vn.terminalHoldingAge <= 17520,
  };
  return {
    gates,
    failed: Object.entries(gates).filter(([, passed]) => !passed).map(([name]) => name),
    breadth: {
      primary_normal_positive_years: `${normalPositive}_of_5`,
      primary_stress_positive_years: `${stressPositive}_of_5`,
      primary_normal_drawdown_non_worse_years: `${drawdownNonworse}_of_5`,
      primary_normal_calmar_at_least_75pct_buy_hold_years: `${calmarBreadth}_of_5`,
      primary_normal_opportunity_value_years: `${opportunityBreadth}_of_5`,
      primary_top_year_positive_total_return_contribution_pct: q(topYear),
      primary_validation_top_positive_episode_contribution_pct: validation.output.NORMAL.candidate.top_positive_episode_contribution_pct,
    },
  };
}

function validateManifest(manifest) {
  if (manifest.document_type !== EXPECTED_MANIFEST_TYPE) throw new ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE");
  if (manifest.experiment_id !== EXPERIMENT_ID) throw new ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID");
  if (manifest.authorization !== "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE") throw new ResearchReject("MANIFEST_REJECT:AUTHORIZATION");
  const policy = manifest.strategy_policy;
  if (
    policy.complete_day_closes !== 20 ||
    policy.population_standard_deviation_multiplier !== 2 ||
    policy.variants !== 1 ||
    policy.standard_deviation_denominator !== "N_POPULATION"
  ) throw new ResearchReject("MANIFEST_REJECT:POLICY");
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  const expectedBindings = {
    "research/btc_daily_bollinger20_2_long_cash_historical.cjs": sha256(__filename),
    "research_pipeline/examples/btc-daily-bollinger20-2-long-cash-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
    "research_pipeline/examples/btc-daily-bollinger20-2-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json": EXPECTED_PARITY_SHA256,
  };
  if (JSON.stringify(bindings) !== JSON.stringify(expectedBindings)) {
    throw new ResearchReject(`MANIFEST_REJECT:SOURCE_BINDINGS:${JSON.stringify(bindings)}`);
  }
}

function buildOutput(inputFile, manifestFile) {
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  validateManifest(manifest);
  for (const [file, expected] of [
    [PRIOR_SOURCE, EXPECTED_PRIOR_SHA256],
    [HYPOTHESIS_SOURCE, EXPECTED_HYPOTHESIS_SHA256],
    [PARITY_SOURCE, EXPECTED_PARITY_SHA256],
  ]) {
    const actual = sha256(file);
    if (actual !== expected) throw new ResearchReject(`SOURCE_REJECT:SHA256:${file}:${actual}`);
  }
  const { bars, digest } = parseRows(inputFile);
  const daily = buildDaily(bars);
  const targets = targetsByExecutionTime(daily);
  const design = simulateWindow(bars, targets, WINDOWS.design);
  const validation = simulateWindow(bars, targets, WINDOWS.validation);
  const annual = Object.fromEntries(Object.entries(ANNUAL).map(([year, window]) => [year, simulateWindow(bars, targets, window)]));
  requireBuyHoldParity(design, validation);
  const evaluation = evaluateGates(design, validation, annual);
  const passed = evaluation.failed.length === 0;
  return {
    schema_version: "1",
    document_type: "BTC_DAILY_BOLLINGER20_2_LONG_CASH_HISTORICAL_RESULT_V1",
    experiment_id: EXPERIMENT_ID,
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: passed ? "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" : "NO_CANDIDATE_CLOSE_BTC_DAILY_BOLLINGER20_2_LONG_CASH_FAMILY",
    decision: passed ? "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" : "PERMANENTLY_CLOSE_EXACT_DAILY_BOLLINGER20_2_LONG_CASH_FAMILY_WITHOUT_TUNING",
    manifest: { path: path.relative(REPO_ROOT, manifestFile).replaceAll("\\", "/"), sha256: sha256(manifestFile) },
    runner: { path: path.relative(REPO_ROOT, __filename).replaceAll("\\", "/"), sha256: sha256(__filename), runtime: "DIRECT_NODE_NO_SPRING_NO_SERVER_NO_DATABASE" },
    dataset: {
      path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"),
      sha256: digest,
      hourly_rows: bars.length,
      complete_utc_days: daily.length,
      selection_cutoff: "2025-01-01T00:00:00",
      first_complete_day_close: iso(daily[0].closeTime),
      last_complete_day_close: iso(daily.at(-1).closeTime),
    },
    source_bindings: {
      primary_prior_sha256: EXPECTED_PRIOR_SHA256,
      hypothesis_sha256: EXPECTED_HYPOTHESIS_SHA256,
      buy_hold_parity_decision_sha256: EXPECTED_PARITY_SHA256,
    },
    policy: {
      formula: "SMA20_PLUS_OR_MINUS_2_TIMES_POPULATION_STANDARD_DEVIATION_OF_CURRENT_AND_PRIOR_19_COMPLETE_DAY_CLOSES",
      long_event: "STRICT_CLOSE_CROSS_FROM_AT_OR_ABOVE_TO_BELOW_LOWER_BAND",
      cash_event: "STRICT_CLOSE_CROSS_FROM_AT_OR_BELOW_TO_ABOVE_UPPER_BAND",
      execution: "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY",
      variants: 1,
    },
    windows: { design: design.output, validation: validation.output },
    annual_fair_reset_primary: Object.fromEntries(Object.entries(annual).map(([year, value]) => [year, value.output])),
    breadth_and_concentration: evaluation.breadth,
    gates: evaluation.gates,
    failed_gates: evaluation.failed,
    all_gates_pass: passed,
    oos_opened: false,
    claim_boundary: "Historical preregistered Design and Validation only. A pass still requires untouched independent OOS and is not activation authority; a failure permanently closes only the exact Bollinger20/2 lower-cross long and upper-cross cash family.",
    scope_note: "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
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
  for (const file of [inputFile, manifestFile]) {
    if (!file.startsWith(`${REPO_ROOT}${path.sep}`)) throw new ResearchReject(`PATH_REJECT:${file}`);
  }
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!outputFile.startsWith(`${stateRoot}${path.sep}`)) throw new ResearchReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new ResearchReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const result = buildOutput(inputFile, manifestFile);
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, `${JSON.stringify(canonical(result))}\n`, { flag: "wx" });
  process.stdout.write(`${JSON.stringify({
    status: result.status,
    output: path.relative(REPO_ROOT, outputFile).replaceAll("\\", "/"),
    sha256: sha256(outputFile),
    failed_gates: result.failed_gates,
  })}\n`);
}

module.exports = {
  ResearchReject,
  buildDaily,
  evaluateGates,
  parseRows,
  populationBands,
  targetsByExecutionTime,
  validateManifest,
};

if (require.main === module) main();
