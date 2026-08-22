#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const PRIOR_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-treasury-term-spread-primary-prior.v1.json");
const SOURCE_METADATA = path.join(REPO_ROOT, "research_pipeline/examples/us-treasury-par-yield-curve-2018-2024.v1.source.json");
const HYPOTHESIS_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-treasury-term-spread-long-cash-v1.hypothesis.json");
const PARITY_SOURCE = path.join(REPO_ROOT, "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json");

const EXPERIMENT_ID = "btc-treasury-term-spread-long-cash-historical-v1";
const EXPECTED_MANIFEST_TYPE = "BTC_TREASURY_TERM_SPREAD_LONG_CASH_HISTORICAL_MANIFEST_V1";
const EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_DATA_ROWS = 52608;
const EXPECTED_TREASURY_SHA256 = "045ce4646a4595697fc16d8e32c0fd08efd431680e998c9f85d2dcac1732c82a";
const EXPECTED_TREASURY_ROWS = 1750;
const EXPECTED_SOURCE_BUNDLE_SHA256 = "5eeda69a5b3a5ad289e0ee9aff6020638266d062d667b902a4b353e593b80965";
const EXPECTED_PRIOR_SHA256 = "90c0bd75cb641f031ae155aea7b56ac0b0dc2e952939c9e859af87c42832bc65";
const EXPECTED_SOURCE_METADATA_SHA256 = "0b66812eaf7f4da1b8c4a2b282887a256226b100048c98dce957f650f7bbc4b5";
const EXPECTED_HYPOTHESIS_SHA256 = "989ff5cba33c98ebff0e43dd78668a83ab446175a13c71debf4ba46c03f7afdb";
const EXPECTED_PARITY_SHA256 = "8410d722eb02702771c4fe9174b2cdcfaffbc6fbeb3df0fa63569906556259bc";

const HOUR_MS = 3600000;
const DAY_MS = 24 * HOUR_MS;
const VARIANTS = [
  ["PRIMARY_10Y_3M", "three_month_pct"],
  ["NEIGHBOR_10Y_1Y", "one_year_pct"],
  ["NEIGHBOR_10Y_2Y", "two_year_pct"],
];
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
  const ordered = [...values].sort((left, right) => left - right);
  if (ordered.length === 1) return ordered[0];
  const position = (ordered.length - 1) * fraction;
  const low = Math.floor(position);
  const high = Math.min(low + 1, ordered.length - 1);
  return ordered[low] + (ordered[high] - ordered[low]) * (position - low);
}

function parseBtcRows(file) {
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
      open <= 0 ||
      close <= 0 ||
      volume < 0
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

function parseTreasuryRows(file) {
  const bytes = fs.readFileSync(file);
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (digest !== EXPECTED_TREASURY_SHA256) throw new ResearchReject(`TREASURY_REJECT:SHA256:${digest}`);
  const lines = bytes.toString("utf8").trimEnd().split(/\r?\n/);
  if (lines.length !== EXPECTED_TREASURY_ROWS + 1) throw new ResearchReject(`TREASURY_REJECT:ROWS:${lines.length - 1}`);
  if (lines[0] !== "date,three_month_pct,one_year_pct,two_year_pct,ten_year_pct") {
    throw new ResearchReject("TREASURY_REJECT:HEADER");
  }
  const rows = lines.slice(1).map((line, index) => {
    const fields = line.split(",");
    if (fields.length !== 5) throw new ResearchReject(`TREASURY_REJECT:FIELDS:${index}`);
    const [date, threeMonthText, oneYearText, twoYearText, tenYearText] = fields;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new ResearchReject(`TREASURY_REJECT:DATE:${index}`);
    const values = [threeMonthText, oneYearText, twoYearText, tenYearText].map(Number);
    if (values.some((value) => !Number.isFinite(value) || value < 0 || value > 25)) {
      throw new ResearchReject(`TREASURY_REJECT:VALUE:${index}`);
    }
    return {
      date,
      dateTime: Date.parse(`${date}T00:00:00Z`),
      three_month_pct: values[0],
      one_year_pct: values[1],
      two_year_pct: values[2],
      ten_year_pct: values[3],
    };
  });
  if (rows[0].date !== "2018-01-02" || rows.at(-1).date !== "2024-12-31") throw new ResearchReject("TREASURY_REJECT:BOUNDARY");
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].dateTime <= rows[index - 1].dateTime) throw new ResearchReject(`TREASURY_REJECT:ORDER:${index}`);
  }
  return { rows, digest };
}

function targetsByEffectiveTime(rows, shortField) {
  if (!VARIANTS.some(([, field]) => field === shortField)) throw new ResearchReject(`FORMULA_REJECT:FIELD:${shortField}`);
  const targets = new Map();
  for (const row of rows) {
    const effectiveTime = row.dateTime + DAY_MS;
    if (targets.has(effectiveTime)) throw new ResearchReject(`FORMULA_REJECT:DUPLICATE_EFFECTIVE:${row.date}`);
    targets.set(effectiveTime, row.ten_year_pct - row[shortField] >= 0);
  }
  return targets;
}

function latestTargetAtOrBefore(targets, time) {
  let found = false;
  let target = false;
  for (const [effectiveTime, value] of targets.entries()) {
    if (effectiveTime > time) break;
    found = true;
    target = value;
  }
  if (!found) throw new ResearchReject(`FORMULA_REJECT:NO_INITIAL_STATE:${iso(time)}`);
  return target;
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
  const initialTarget = latestTargetAtOrBefore(targets, start);

  for (const bar of trading) {
    let evaluate = false;
    let target = false;
    if (bar.openTime === start) {
      evaluate = true;
      target = initialTarget;
    }
    if (targets.has(bar.openTime) && bar.openTime !== start) {
      evaluate = true;
      target = targets.get(bar.openTime);
    }
    if (evaluate) {
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
    pathState.observe(finalEquity, finalEquity > 0 ? marketValue / finalEquity : 0);
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
    signalEvaluations,
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

function simulateWindow(bars, targetsByVariant, window) {
  const output = {};
  const raw = {};
  for (const [variant] of VARIANTS) {
    output[variant] = {};
    raw[variant] = {};
    for (const [scenario, [feeRate, slippage]] of Object.entries(SCENARIOS)) {
      const result = simulateScenario(bars, targetsByVariant[variant], window, feeRate, slippage);
      output[variant][scenario] = result.output;
      raw[variant][scenario] = result.raw;
    }
  }
  return { output, raw };
}

function requireBuyHoldParity(design, validation) {
  const actual = {
    designNormal: [
      design.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.total_return_pct,
      design.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.maximum_drawdown_pct,
      design.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.calmar_ratio,
    ],
    designStress: [design.output.PRIMARY_10Y_3M.STRESS.buy_and_hold.total_return_pct],
    validationNormal: [
      validation.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.total_return_pct,
      validation.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.maximum_drawdown_pct,
      validation.output.PRIMARY_10Y_3M.NORMAL.buy_and_hold.calmar_ratio,
    ],
    validationStress: [validation.output.PRIMARY_10Y_3M.STRESS.buy_and_hold.total_return_pct],
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
  const primary = "PRIMARY_10Y_3M";
  const dn = design.raw[primary].NORMAL;
  const ds = design.raw[primary].STRESS;
  const vn = validation.raw[primary].NORMAL;
  const vs = validation.raw[primary].STRESS;
  const gates = {
    btc_dataset_sha256_and_52608_rows_match: true,
    treasury_source_sha256_and_1750_rows_match: true,
    frozen_source_bundle_metadata_prior_hypothesis_runner_and_manifest_bindings_match: true,
    next_calendar_day_0000_utc_availability_and_zero_spread_policy_match: true,
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
    primary_validation_position_changes_between_2_and_50: vn.positionChanges >= 2 && vn.positionChanges <= 50,
    primary_validation_stress_drawdown_no_more_than_normal_plus_3pp: vs.drawdown <= vn.drawdown + 3,
  };

  for (const neighbor of ["NEIGHBOR_10Y_1Y", "NEIGHBOR_10Y_2Y"]) {
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
  for (const neighbor of ["NEIGHBOR_10Y_1Y", "NEIGHBOR_10Y_2Y"]) {
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
  if (
    policy.decision_clock !== "NEXT_CALENDAR_DAY_0000_UTC_AFTER_TREASURY_TRADING_DATE" ||
    policy.relation !== "AT_OR_ABOVE" ||
    policy.threshold_pct !== "0.00" ||
    policy.long_target !== "BTC_100_PERCENT" ||
    policy.risk_off_target !== "CASH_100_PERCENT" ||
    policy.cash_yield !== "ZERO" ||
    policy.variants.length !== 3
  ) throw new ResearchReject("MANIFEST_REJECT:POLICY");
  const expectedVariants = [
    ["treasury-10y-3m-noninversion-v1", "primary", "3 Mo"],
    ["treasury-10y-1y-noninversion-v1", "neighbor", "1 Yr"],
    ["treasury-10y-2y-noninversion-v1", "neighbor", "2 Yr"],
  ];
  const actualVariants = policy.variants.map((value) => [value.variant_id, value.role, value.short_maturity]);
  if (JSON.stringify(actualVariants) !== JSON.stringify(expectedVariants)) throw new ResearchReject("MANIFEST_REJECT:VARIANTS");
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  const expectedBindings = {
    "research_pipeline/examples/btc-treasury-term-spread-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
    "research_pipeline/examples/us-treasury-par-yield-curve-2018-2024.v1.source.json": EXPECTED_SOURCE_METADATA_SHA256,
    ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/inputs/treasury-source-bundle.json": EXPECTED_SOURCE_BUNDLE_SHA256,
    ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/inputs/treasury-yield-curve-2018-2024.csv": EXPECTED_TREASURY_SHA256,
    "research_pipeline/examples/btc-treasury-term-spread-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    "research_pipeline/examples/btc-daily-rsi14-midline-long-cash-historical.v1.decision.json": EXPECTED_PARITY_SHA256,
    "research/btc_treasury_term_spread_long_cash_historical.cjs": sha256(__filename),
  };
  if (JSON.stringify(bindings) !== JSON.stringify(expectedBindings)) {
    throw new ResearchReject(`MANIFEST_REJECT:SOURCE_BINDINGS:${JSON.stringify(bindings)}`);
  }
}

function buildOutput(inputFile, treasuryFile, manifestFile) {
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  validateManifest(manifest);
  for (const [file, expected] of [
    [PRIOR_SOURCE, EXPECTED_PRIOR_SHA256],
    [SOURCE_METADATA, EXPECTED_SOURCE_METADATA_SHA256],
    [HYPOTHESIS_SOURCE, EXPECTED_HYPOTHESIS_SHA256],
    [PARITY_SOURCE, EXPECTED_PARITY_SHA256],
  ]) {
    const actual = sha256(file);
    if (actual !== expected) throw new ResearchReject(`SOURCE_REJECT:SHA256:${file}:${actual}`);
  }
  const { bars, digest } = parseBtcRows(inputFile);
  const { rows: treasuryRows, digest: treasuryDigest } = parseTreasuryRows(treasuryFile);
  const targetsByVariant = Object.fromEntries(
    VARIANTS.map(([variant, field]) => [variant, targetsByEffectiveTime(treasuryRows, field)]),
  );
  const design = simulateWindow(bars, targetsByVariant, WINDOWS.design);
  const validation = simulateWindow(bars, targetsByVariant, WINDOWS.validation);
  const annual = Object.fromEntries(
    Object.entries(ANNUAL).map(([year, window]) => [year, simulateWindow(bars, targetsByVariant, window)]),
  );
  requireBuyHoldParity(design, validation);
  const evaluation = evaluateGates(design, validation, annual);
  const passed = evaluation.failed.length === 0;
  return {
    schema_version: "1",
    document_type: "BTC_TREASURY_TERM_SPREAD_LONG_CASH_HISTORICAL_RESULT_V1",
    experiment_id: EXPERIMENT_ID,
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: passed
      ? "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
      : "NO_CANDIDATE_CLOSE_BTC_TREASURY_TERM_SPREAD_LONG_CASH_FAMILY",
    decision: passed
      ? "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
      : "PERMANENTLY_CLOSE_EXACT_TREASURY_TERM_SPREAD_NONINVERSION_LONG_CASH_FAMILY_WITHOUT_MATURITY_THRESHOLD_RELATION_TIMING_OR_SIZING_TUNING",
    manifest: {
      path: path.relative(REPO_ROOT, manifestFile).replaceAll("\\", "/"),
      sha256: sha256(manifestFile),
    },
    runner: {
      path: path.relative(REPO_ROOT, __filename).replaceAll("\\", "/"),
      sha256: sha256(__filename),
      runtime: "DIRECT_NODE_NO_SPRING_NO_SERVER_NO_DATABASE",
    },
    datasets: {
      btc_h1: {
        path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"),
        sha256: digest,
        rows: bars.length,
        selection_cutoff: "2025-01-01T00:00:00",
      },
      treasury: {
        path: path.relative(REPO_ROOT, treasuryFile).replaceAll("\\", "/"),
        sha256: treasuryDigest,
        rows: treasuryRows.length,
        first_date: treasuryRows[0].date,
        last_date: treasuryRows.at(-1).date,
        source_bundle_sha256: EXPECTED_SOURCE_BUNDLE_SHA256,
      },
    },
    policy: {
      primary: "TEN_YEAR_CMT_MINUS_THREE_MONTH_CMT_AT_OR_ABOVE_ZERO",
      rejection_neighbors: [
        "TEN_YEAR_CMT_MINUS_ONE_YEAR_CMT_AT_OR_ABOVE_ZERO",
        "TEN_YEAR_CMT_MINUS_TWO_YEAR_CMT_AT_OR_ABOVE_ZERO",
      ],
      effective_time: "NEXT_CALENDAR_DAY_0000_UTC",
      execution: "NEXT_HOURLY_OPEN",
      missing_date_policy: "PERSIST_LATEST_STATE_NO_FILL_NO_INTERPOLATION",
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
    claim_boundary: "Historical preregistered Design and Validation through 2024 only. A pass still requires untouched independent OOS and remains REPORTED_NOT_ACTIVATED; a failure permanently closes only the exact 10Y-minus-3M non-inversion policy and its frozen 10Y-minus-1Y and 10Y-minus-2Y stability neighbors.",
    scope_note: "No paid API, second timer, second writer, unsealed backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
  };
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--input", "--treasury", "--manifest", "--output"]) {
    if (!values[name]) throw new ResearchReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

function main() {
  const args = argumentsByName(process.argv.slice(2));
  const inputFile = path.resolve(args["--input"]);
  const treasuryFile = path.resolve(args["--treasury"]);
  const manifestFile = path.resolve(args["--manifest"]);
  const outputFile = path.resolve(args["--output"]);
  for (const file of [inputFile, treasuryFile, manifestFile]) {
    if (!file.startsWith(`${REPO_ROOT}${path.sep}`)) throw new ResearchReject(`PATH_REJECT:${file}`);
  }
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!outputFile.startsWith(`${stateRoot}${path.sep}`)) throw new ResearchReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new ResearchReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const result = buildOutput(inputFile, treasuryFile, manifestFile);
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
  evaluateGates,
  latestTargetAtOrBefore,
  parseBtcRows,
  parseTreasuryRows,
  simulateScenario,
  targetsByEffectiveTime,
  validateManifest,
};

if (require.main === module) main();
