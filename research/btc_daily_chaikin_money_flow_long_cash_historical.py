#!/usr/bin/env python3
"""Deterministic historical screen for the frozen daily CMF long/cash family."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-chaikin-money-flow-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-chaikin-money-flow-long-cash-v1.hypothesis.json"

EXPERIMENT_ID = "btc-daily-chaikin-money-flow-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_CHAIKIN_MONEY_FLOW_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "2ddd3e5397816f425c1535f69de15315f637f44291635ce584d0906c6ba7002d"
EXPECTED_HYPOTHESIS_SHA256 = "449fcd2750d31190a12e76649de727b25bca0c369ca20f588f830c5e562a47e5"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_CMF20": {"lookback": 20, "role": "PRIMARY"},
    "NEIGHBOR_CMF14": {"lookback": 14, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_CMF28": {"lookback": 28, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_ECONOMIC_RUNNER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER_PATH_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_H1_PARSER_AND_DATA_INTEGRITY_REFERENCE_ONLY": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR": "research_pipeline/examples/btc-daily-chaikin-money-flow-long-cash-primary-prior.v1.json",
    "FROZEN_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-daily-chaikin-money-flow-long-cash-v1.hypothesis.json",
}
EXPECTED_GATE_NAMES = (
    "dataset_sha256_and_52608_rows_match",
    "hourly_lattice_ohlcv_and_2192_complete_utc_days_pass",
    "frozen_runner_reference_parser_prior_and_hypothesis_sha256_match",
    "primary_design_normal_total_return_pct_gt_0",
    "primary_design_stress_total_return_pct_gt_0",
    "primary_design_normal_drawdown_at_most_95pct_of_buy_hold",
    "primary_design_normal_calmar_at_least_buy_hold",
    "primary_validation_normal_total_return_pct_gt_0",
    "primary_validation_stress_total_return_pct_gt_0",
    "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold",
    "primary_validation_normal_upside_capture_at_least_60pct",
    "primary_validation_normal_calmar_at_least_buy_hold",
    "primary_validation_position_changes_between_2_and_200",
    "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "neighbor_cmf14_validation_normal_total_return_pct_gt_0",
    "neighbor_cmf14_validation_stress_total_return_pct_gt_0",
    "neighbor_cmf14_validation_normal_drawdown_non_worse",
    "neighbor_cmf14_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_cmf14_validation_normal_upside_capture_at_least_50pct",
    "neighbor_cmf28_validation_normal_total_return_pct_gt_0",
    "neighbor_cmf28_validation_stress_total_return_pct_gt_0",
    "neighbor_cmf28_validation_normal_drawdown_non_worse",
    "neighbor_cmf28_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_cmf28_validation_normal_upside_capture_at_least_50pct",
    "primary_normal_positive_annual_total_return_at_least_4_of_5",
    "primary_stress_positive_annual_total_return_at_least_4_of_5",
    "primary_normal_annual_drawdown_non_worse_at_least_4_of_5",
    "primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5",
    "primary_normal_annual_upside_capture_at_least_50pct_at_least_4_of_5",
    "primary_top_year_positive_total_return_contribution_at_most_60pct",
    "primary_validation_top_positive_episode_contribution_at_most_60pct",
    "primary_validation_p90_hold_at_most_17520_hours",
    "primary_validation_terminal_holding_age_at_most_17520_hours",
    "primary_validation_terminal_liquidation_adjusted_return_pct_gt_0",
    "primary_validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


def nullable(value: D | None) -> str | None:
    return None if value is None else q(value)


def percentile(values: list[D], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = D(len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - D(low))


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass(frozen=True)
class DailyPoint:
    close_time: datetime
    high: D
    low: D
    close: D
    volume: D
    money_flow_multiplier: D
    money_flow_volume: D


def build_daily_points(
    bars: list[Any], expected_rows: int = EXPECTED_DAILY_ROWS
) -> list[DailyPoint]:
    points: list[DailyPoint] = []
    day_bars: list[Any] = []
    for bar in bars:
        day_bars.append(bar)
        if bar.close_time.hour != 0:
            continue
        if len(day_bars) != 24:
            raise ResearchReject(
                f"DATA_REJECT:UTC_DAY_BAR_COUNT:{bar.close_time.isoformat()}:{len(day_bars)}"
            )
        first = day_bars[0]
        if first.open_time.hour != 0 or first.open_time.date() == bar.close_time.date():
            raise ResearchReject(
                f"DATA_REJECT:UTC_DAY_BOUNDARY:{first.open_time.isoformat()}:{bar.close_time.isoformat()}"
            )
        high = max(item.high for item in day_bars)
        low = min(item.low for item in day_bars)
        close = day_bars[-1].close
        volume = sum((item.volume for item in day_bars), ZERO)
        if high < low or close < low or close > high or volume < ZERO:
            raise ResearchReject(f"DATA_REJECT:DAILY_OHLCV:{bar.close_time.isoformat()}")
        multiplier = (
            ZERO
            if high == low
            else ((close - low) - (high - close)) / (high - low)
        )
        if multiplier < -ONE or multiplier > ONE:
            raise ResearchReject(f"DATA_REJECT:MONEY_FLOW_MULTIPLIER:{bar.close_time.isoformat()}")
        points.append(
            DailyPoint(
                bar.close_time,
                high,
                low,
                close,
                volume,
                multiplier,
                multiplier * volume,
            )
        )
        day_bars = []
    if day_bars:
        raise ResearchReject(f"DATA_REJECT:INCOMPLETE_FINAL_UTC_DAY:{len(day_bars)}")
    if len(points) != expected_rows:
        raise ResearchReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(points)}")
    for index in range(1, len(points)):
        if (points[index].close_time - points[index - 1].close_time).days != 1:
            raise ResearchReject(
                f"DATA_REJECT:UTC_DAY_GAP:{points[index - 1].close_time}:{points[index].close_time}"
            )
    return points


def targets_by_execution_time(
    daily: list[DailyPoint], lookback: int
) -> tuple[dict[datetime, bool], dict[str, Any]]:
    if lookback not in {14, 20, 28}:
        raise ResearchReject(f"MANIFEST_REJECT:CMF_LOOKBACK:{lookback}")
    targets: dict[datetime, bool] = {}
    values: list[D] = []
    for index in range(lookback - 1, len(daily)):
        window = daily[index - lookback + 1 : index + 1]
        denominator = sum((point.volume for point in window), ZERO)
        if denominator <= ZERO:
            raise ResearchReject(
                f"DATA_REJECT:NONPOSITIVE_ROLLING_VOLUME:{daily[index].close_time.isoformat()}"
            )
        cmf = sum((point.money_flow_volume for point in window), ZERO) / denominator
        if cmf < -ONE or cmf > ONE:
            raise ResearchReject(f"DATA_REJECT:CMF_RANGE:{daily[index].close_time.isoformat()}")
        targets[daily[index].close_time] = cmf > ZERO
        values.append(cmf)
    return targets, {
        "lookback_complete_days": lookback,
        "evaluation_count": len(values),
        "positive_count": sum(value > ZERO for value in values),
        "nonpositive_count": sum(value <= ZERO for value in values),
        "minimum": q(min(values)),
        "maximum": q(max(values)),
        "median": q(percentile(values, D("0.5")) or ZERO),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def simulate_scenario(
    reference: ModuleType,
    bars: list[Any],
    targets: dict[datetime, bool],
    window: tuple[datetime, datetime],
    fee_rate: D,
    slippage: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    trading = [bar for bar in bars if start <= bar.open_time < end]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    cash = ONE
    quantity = ZERO
    entry_equity: D | None = None
    entry_time: datetime | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_evaluations = 0
    long_targets = 0
    cash_targets = 0
    position_changes = 0
    episode_pnls: list[D] = []
    hold_hours: list[D] = []
    path = reference.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        target = targets.get(bar.open_time)
        if target is not None:
            signal_evaluations += 1
            if target:
                long_targets += 1
            else:
                cash_targets += 1
            if target and quantity == ZERO:
                entry_equity = cash
                quantity, cash, fee, gross = reference.buy_all(
                    cash, bar.open, fee_rate, slippage
                )
                entry_time = bar.open_time
                fees += fee
                turnover += gross
                position_changes += 1
            elif not target and quantity > ZERO:
                net, fee, gross = reference.sell_all(
                    quantity, bar.open, fee_rate, slippage
                )
                cash += net
                pnl = cash - (entry_equity or ZERO)
                realized += pnl
                episode_pnls.append(pnl)
                hold_hours.append(
                    D(str((bar.open_time - entry_time).total_seconds() / 3600))
                )
                quantity = ZERO
                entry_equity = None
                entry_time = None
                fees += fee
                turnover += gross
                position_changes += 1

        market_value = quantity * bar.close
        final_equity = cash + market_value
        path.observe(
            final_equity,
            market_value / final_equity if final_equity > ZERO else ZERO,
        )

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    terminal_liquidation_equity = final_equity
    if quantity > ZERO:
        terminal_net, _, _ = reference.sell_all(
            quantity, trading[-1].close, fee_rate, slippage
        )
        terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (terminal_liquidation_equity - ONE) * HUNDRED
    positive_episodes = [pnl for pnl in episode_pnls if pnl > ZERO]
    top_positive_episode = (
        ZERO
        if not positive_episodes
        else max(positive_episodes) / sum(positive_episodes, ZERO) * HUNDRED
    )
    terminal_holding_age = (
        ZERO
        if entry_time is None
        else D(str((end - entry_time).total_seconds() / 3600))
    )
    candidate.update(
        {
            "realized_return_pct": q(realized * HUNDRED),
            "unrealized_return_pct": q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": q(terminal_liquidation_return),
            "terminal_liquidation_cost_pp": q(raw["total_return"] - terminal_liquidation_return),
            "fees_equity_units": q(fees),
            "turnover_equity_units": q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "long_target_count": long_targets,
            "cash_target_count": cash_targets,
            "position_change_count": position_changes,
            "completed_episode_count": len(episode_pnls),
            "winning_episode_count": len(positive_episodes),
            "median_hold_hours": nullable(percentile(hold_hours, D("0.5"))),
            "p90_hold_hours": nullable(percentile(hold_hours, D("0.9"))),
            "terminal_position": quantity > ZERO,
            "terminal_holding_age_hours": None if entry_time is None else q(terminal_holding_age),
            "top_positive_episode_contribution_pct": None if not positive_episodes else q(top_positive_episode),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"] - terminal_liquidation_return,
            "position_changes": D(position_changes),
            "p90_hold": percentile(hold_hours, D("0.9")) or ZERO,
            "terminal_holding_age": terminal_holding_age,
            "top_positive_episode_contribution": top_positive_episode,
            "has_positive_episode": ONE if positive_episodes else ZERO,
        }
    )
    benchmark, benchmark_raw = reference.passive_benchmark(
        trading, fee_rate, slippage
    )
    upside_capture = (
        raw["total_return"] / benchmark_raw["total_return"]
        if benchmark_raw["total_return"] > ZERO
        else None
    )
    raw.update(
        {
            "buy_hold_return": benchmark_raw["total_return"],
            "buy_hold_drawdown": benchmark_raw["drawdown"],
            "buy_hold_calmar": benchmark_raw["calmar"],
            "upside_capture": upside_capture if upside_capture is not None else ZERO,
        }
    )
    return {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate": candidate,
        "buy_and_hold": benchmark,
        "comparison": {
            "total_return_delta_pp": q(raw["total_return"] - benchmark_raw["total_return"]),
            "maximum_drawdown_delta_pp": q(raw["drawdown"] - benchmark_raw["drawdown"]),
            "upside_capture_ratio": nullable(upside_capture),
            "calmar_ratio_to_buy_hold": nullable(
                raw["calmar"] / benchmark_raw["calmar"]
                if benchmark_raw["calmar"] != ZERO
                else None
            ),
        },
    }, raw


def simulate_window(
    reference: ModuleType,
    bars: list[Any],
    daily: list[DailyPoint],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, Any] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for variant_name, variant in VARIANTS.items():
        targets, feature = targets_by_execution_time(daily, variant["lookback"])
        output[variant_name] = {"role": variant["role"], "feature": feature, "scenarios": {}}
        raw[variant_name] = {}
        for scenario_name, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = simulate_scenario(
                reference, bars, targets, window, fee_rate, slippage
            )
            output[variant_name]["scenarios"][scenario_name] = scenario_output
            raw[variant_name][scenario_name] = scenario_raw
    return output, raw


def evaluate_gates(
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn = design["PRIMARY_CMF20"]["NORMAL"]
    ds = design["PRIMARY_CMF20"]["STRESS"]
    vn = validation["PRIMARY_CMF20"]["NORMAL"]
    vs = validation["PRIMARY_CMF20"]["STRESS"]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_ohlcv_and_2192_complete_utc_days_pass": True,
        "frozen_runner_reference_parser_prior_and_hypothesis_sha256_match": True,
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"] <= D("0.95") * dn["buy_hold_drawdown"],
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "primary_validation_position_changes_between_2_and_200": D("2") <= vn["position_changes"] <= D("200"),
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
    }
    for neighbor in ("NEIGHBOR_CMF14", "NEIGHBOR_CMF28"):
        label = neighbor.lower()
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[f"{label}_validation_{scenario.lower()}_total_return_pct_gt_0"] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{label}_validation_normal_drawdown_non_worse"] = value["drawdown"] <= value["buy_hold_drawdown"]
        gates[f"{label}_validation_normal_calmar_at_least_75pct_buy_hold"] = value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        gates[f"{label}_validation_normal_upside_capture_at_least_50pct"] = value["upside_capture"] >= D("0.50")

    annual_raw = {year: value[1]["PRIMARY_CMF20"] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.50") for value in annual_raw.values())
    positive_year_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates.update(
        {
            "primary_normal_positive_annual_total_return_at_least_4_of_5": normal_positive >= 4,
            "primary_stress_positive_annual_total_return_at_least_4_of_5": stress_positive >= 4,
            "primary_normal_annual_drawdown_non_worse_at_least_4_of_5": drawdown_nonworse >= 4,
            "primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5": calmar_breadth >= 3,
            "primary_normal_annual_upside_capture_at_least_50pct_at_least_4_of_5": upside_breadth >= 4,
            "primary_top_year_positive_total_return_contribution_at_most_60pct": top_year <= D("60"),
            "primary_validation_top_positive_episode_contribution_at_most_60pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
            "primary_validation_p90_hold_at_most_17520_hours": vn["p90_hold"] <= D("17520"),
            "primary_validation_terminal_holding_age_at_most_17520_hours": vn["terminal_holding_age"] <= D("17520"),
            "primary_validation_terminal_liquidation_adjusted_return_pct_gt_0": vn["terminal_liquidation_return"] > ZERO,
            "primary_validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
        }
    )
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": q(top_year),
        "primary_validation_top_positive_episode_contribution_pct": validation_output["PRIMARY_CMF20"]["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": EXPECTED_DATA_SHA256,
        "hourly_rows": EXPECTED_DATA_ROWS,
        "expected_complete_utc_days": EXPECTED_DAILY_ROWS,
        "first_open_time": "2019-01-01T00:00:00",
        "last_close_time": "2025-01-01T00:00:00",
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    policy = manifest.get("strategy_policy")
    if policy != {
        "policy_id": "BTC_DAILY_CHAIKIN_MONEY_FLOW_LONG_CASH_V1",
        "primary_lookback_complete_days": 20,
        "rejection_only_neighbor_lookbacks_complete_days": [14, 28],
        "money_flow_multiplier": "((DAILY_CLOSE_MINUS_DAILY_LOW)-(DAILY_HIGH_MINUS_DAILY_CLOSE))/(DAILY_HIGH_MINUS_DAILY_LOW)",
        "zero_range_rule": "MONEY_FLOW_MULTIPLIER_ZERO",
        "money_flow_volume": "MONEY_FLOW_MULTIPLIER_TIMES_DAILY_BASE_VOLUME",
        "chaikin_money_flow": "ROLLING_SUM_MONEY_FLOW_VOLUME_DIVIDED_BY_ROLLING_SUM_BASE_VOLUME",
        "long_condition": "CMF_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "CMF_LESS_THAN_OR_EQUAL_TO_ZERO",
        "execution": "NEXT_H1_OPEN_AFTER_COMPLETE_UTC_DAY",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 3,
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
        "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
    }:
        raise ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    if manifest.get("windows") != {
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "id": "BTC_DAILY_CHAIKIN_MONEY_FLOW_MATCHED_CAPITAL_GATES_V1",
        "required": list(EXPECTED_GATE_NAMES),
        "decision": "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    if {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or sha256(bound) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
    }
    for source, expected in expected_sources.items():
        if sha256(source) != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source.relative_to(REPO_ROOT)}")
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    parser = load_module("cmf_frozen_h1_parser", PARSER_SOURCE)
    reference = load_module("cmf_frozen_long_cash_reference", REFERENCE_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = build_daily_points(bars)
    design_output, design_raw = simulate_window(reference, bars, daily, DESIGN)
    validation_output, validation_raw = simulate_window(reference, bars, daily, VALIDATION)
    annual = {
        year: simulate_window(reference, bars, daily, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        design_raw, validation_output, validation_raw, annual
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_CHAIKIN_MONEY_FLOW_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_DAILY_CHAIKIN_MONEY_FLOW_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_CMF14_20_28_LONG_CASH_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(input_path), "hourly_rows": len(bars), "complete_utc_days": len(daily), "selection_cutoff": "2025-01-01T00:00:00"},
        "policy": {"primary_lookback_complete_days": 20, "rejection_only_neighbors_complete_days": [14, 28], "threshold": "STRICTLY_GREATER_THAN_ZERO", "variants": 3},
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-capital single-venue CMF evidence only; a pass is not independent alpha, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    if not input_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_gates": result["failed_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
