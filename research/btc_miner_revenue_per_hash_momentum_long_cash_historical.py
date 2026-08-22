#!/usr/bin/env python3
"""Frozen miner-revenue-per-hash nonredundancy gate and historical screen."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")
REDUNDANCY_LIMIT = D("0.80")

REPO_ROOT = Path(__file__).resolve().parents[1]
LEDGER_SOURCE = REPO_ROOT / "research" / "btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-miner-revenue-per-hash-momentum-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-miner-revenue-per-hash-momentum-long-cash-v1.hypothesis.json"
FEE_METADATA_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "coinmetrics-btc-fee-pressure-daily-2018-2024.v1.source.json"
HASHRATE_METADATA_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "coinmetrics-btc-hashrate-daily-2018-2024.v1.source.json"

EXPERIMENT_ID = "btc-miner-revenue-per-hash-momentum-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_MINER_REVENUE_PER_HASH_MOMENTUM_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_BTC_DAILY_ROWS = 2_192
EXPECTED_FEE_SHA256 = "6dd78283ff476ddca7bed52bf01ad76a6b49d6bf1125c9e360ad95aa5787feed"
EXPECTED_HASHRATE_SHA256 = "1dfdfde2f8806f912c6dc3c3d48e2bf244c40af94e9aeb4389ed2cc8337ec273"
EXPECTED_SOURCE_ROWS = 2_557
EXPECTED_LEDGER_SHA256 = "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "b29c41f17fac7ebe5f4edecc7e4883dde5f5ab699f2612cb96e8e5c716ecb2db"
EXPECTED_HYPOTHESIS_SHA256 = "83e8f5fd674c0fd1048f895dd69c930308a5be22925ab977a40769676d259d32"
EXPECTED_FEE_METADATA_SHA256 = "03cf5e5c5f18c19597bcd5fd61024158ef25369da806f32820c632457281d17d"
EXPECTED_HASHRATE_METADATA_SHA256 = "266e41fa72f8a612a9fc3327a5f31614d340d79a0ed4420925dc96151857ca08"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_MINER4": {"lookback": 4, "role": "PRIMARY"},
    "NEIGHBOR_MINER2": {"lookback": 2, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_MINER8": {"lookback": 8, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
NONREDUNDANCY_GATE_NAMES = (
    "primary_design_proxy_growth_abs_spearman_to_btc_price_growth_at_most_0_80",
    "primary_design_proxy_growth_abs_spearman_to_hashrate_growth_at_most_0_80",
    "primary_design_proxy_growth_abs_spearman_to_fee_growth_at_most_0_80",
    "primary_validation_proxy_growth_abs_spearman_to_btc_price_growth_at_most_0_80",
    "primary_validation_proxy_growth_abs_spearman_to_hashrate_growth_at_most_0_80",
    "primary_validation_proxy_growth_abs_spearman_to_fee_growth_at_most_0_80",
)
ECONOMIC_GATE_NAMES = (
    "primary_design_normal_total_return_pct_gt_0",
    "primary_design_stress_total_return_pct_gt_0",
    "primary_design_normal_drawdown_at_most_95pct_of_buy_hold",
    "primary_design_normal_calmar_at_least_buy_hold",
    "primary_validation_normal_total_return_pct_gt_0",
    "primary_validation_stress_total_return_pct_gt_0",
    "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold",
    "primary_validation_normal_upside_capture_at_least_60pct",
    "primary_validation_normal_calmar_at_least_buy_hold",
    "primary_validation_position_changes_between_2_and_100",
    "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "neighbor_miner2_validation_normal_total_return_pct_gt_0",
    "neighbor_miner2_validation_stress_total_return_pct_gt_0",
    "neighbor_miner2_validation_normal_drawdown_non_worse",
    "neighbor_miner2_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_miner2_validation_normal_upside_capture_at_least_50pct",
    "neighbor_miner8_validation_normal_total_return_pct_gt_0",
    "neighbor_miner8_validation_stress_total_return_pct_gt_0",
    "neighbor_miner8_validation_normal_drawdown_non_worse",
    "neighbor_miner8_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_miner8_validation_normal_upside_capture_at_least_50pct",
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
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_NONREDUNDANCY_AND_ECONOMIC_RUNNER": "research/btc_miner_revenue_per_hash_momentum_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER_REFERENCE": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_H1_PARSER_AND_DATA_INTEGRITY_REFERENCE_ONLY": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR": "research_pipeline/examples/btc-miner-revenue-per-hash-momentum-long-cash-primary-prior.v1.json",
    "FROZEN_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-miner-revenue-per-hash-momentum-long-cash-v1.hypothesis.json",
    "SEALED_FEE_SOURCE_METADATA": "research_pipeline/examples/coinmetrics-btc-fee-pressure-daily-2018-2024.v1.source.json",
    "SEALED_NORMALIZED_FEE_CORPUS": ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/inputs/coinmetrics-btc-fee-pressure-2018-2024.csv",
    "SEALED_HASHRATE_SOURCE_METADATA": "research_pipeline/examples/coinmetrics-btc-hashrate-daily-2018-2024.v1.source.json",
    "SEALED_NORMALIZED_HASHRATE_CORPUS": ".research-state/experiments/dra-bitcoin-hashrate-growth-entry-admission-historical-v1/inputs/coinmetrics-btc-hashrate-2018-2024.csv",
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyPoint:
    day: date
    btc_close: D
    fees_btc: D
    hashrate: D
    subsidy_btc_per_block: D
    theoretical_revenue_per_hash_usd: D


@dataclass(frozen=True)
class WeeklyPoint:
    week_end: date
    eligible_at: datetime
    proxy_mean: D
    btc_mean: D
    fee_mean: D
    hashrate_mean: D


@dataclass(frozen=True)
class FactorPoint:
    eligible_at: datetime
    week_end: date
    lookback_weeks: int
    proxy_growth: D
    btc_growth: D
    fee_growth: D
    hashrate_growth: D
    long_target: bool


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


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


def block_subsidy(day: date) -> D:
    if day <= date(2020, 5, 10):
        return D("12.5")
    if day <= date(2024, 4, 19):
        return D("6.25")
    return D("3.125")


def load_daily_series(
    path: Path,
    value_column: str,
    *,
    allow_zero: bool,
    expected_rows: int = EXPECTED_SOURCE_ROWS,
) -> dict[date, D]:
    rows: dict[date, D] = {}
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["date", value_column]:
            raise ResearchReject(f"DATA_REJECT:COLUMNS:{reader.fieldnames}")
        for row in reader:
            try:
                day = date.fromisoformat(row["date"])
                value = D(row[value_column])
            except (KeyError, ValueError, InvalidOperation) as exc:
                raise ResearchReject(f"DATA_REJECT:PARSE:{value_column}") from exc
            if day in rows or not value.is_finite() or value < ZERO or (
                not allow_zero and value == ZERO
            ):
                raise ResearchReject(f"DATA_REJECT:VALUE:{value_column}:{day}")
            rows[day] = value
    ordered = sorted(rows)
    if len(ordered) != expected_rows:
        raise ResearchReject(f"DATA_REJECT:ROWS:{value_column}:{len(ordered)}")
    if ordered and any(
        current - previous != timedelta(days=1)
        for previous, current in zip(ordered, ordered[1:], strict=False)
    ):
        raise ResearchReject(f"DATA_REJECT:CONTINUITY:{value_column}")
    return rows


def build_daily_points(
    btc_daily: list[Any], fees: dict[date, D], hashrate: dict[date, D]
) -> list[DailyPoint]:
    points: list[DailyPoint] = []
    for point in btc_daily:
        day = point.close_time.date() - timedelta(days=1)
        fee = fees.get(day)
        rate = hashrate.get(day)
        if fee is None or rate is None:
            raise ResearchReject(f"DATA_REJECT:MISSING_DAILY_PAIR:{day}")
        subsidy = block_subsidy(day)
        proxy = (D("144") * subsidy + fee) * point.close / rate
        if proxy <= ZERO:
            raise ResearchReject(f"DATA_REJECT:NONPOSITIVE_PROXY:{day}")
        points.append(DailyPoint(day, point.close, fee, rate, subsidy, proxy))
    return points


def build_weekly_points(daily: list[DailyPoint]) -> list[WeeklyPoint]:
    by_day = {point.day: point for point in daily}
    first_day = min(by_day)
    last_day = max(by_day)
    first_monday = first_day + timedelta(days=(7 - first_day.weekday()) % 7)
    last_sunday = last_day - timedelta(days=(last_day.weekday() - 6) % 7)
    points: list[WeeklyPoint] = []
    current = first_monday
    while current + timedelta(days=6) <= last_sunday:
        days = [current + timedelta(days=offset) for offset in range(7)]
        if any(day not in by_day for day in days):
            raise ResearchReject(f"DATA_REJECT:INCOMPLETE_WEEK:{current}")
        week = [by_day[day] for day in days]
        week_end = days[-1]
        points.append(
            WeeklyPoint(
                week_end,
                datetime.combine(week_end + timedelta(days=3), time.min),
                sum((point.theoretical_revenue_per_hash_usd for point in week), ZERO) / D("7"),
                sum((point.btc_close for point in week), ZERO) / D("7"),
                sum((point.fees_btc for point in week), ZERO) / D("7"),
                sum((point.hashrate for point in week), ZERO) / D("7"),
            )
        )
        current += timedelta(days=7)
    if not points:
        raise ResearchReject("DATA_REJECT:NO_COMPLETE_WEEKS")
    return points


def _growth(current: D, previous: D, label: str) -> D:
    if previous <= ZERO:
        raise ResearchReject(f"DATA_REJECT:NONPOSITIVE_PREVIOUS_MEAN:{label}")
    return current / previous - ONE


def build_factor_points(weekly: list[WeeklyPoint], lookback: int) -> list[FactorPoint]:
    if lookback not in {2, 4, 8}:
        raise ResearchReject(f"MANIFEST_REJECT:LOOKBACK:{lookback}")
    points: list[FactorPoint] = []
    for index in range(2 * lookback - 1, len(weekly)):
        current = weekly[index - lookback + 1 : index + 1]
        previous = weekly[index - 2 * lookback + 1 : index - lookback + 1]
        current_proxy = sum((point.proxy_mean for point in current), ZERO) / D(lookback)
        previous_proxy = sum((point.proxy_mean for point in previous), ZERO) / D(lookback)
        current_btc = sum((point.btc_mean for point in current), ZERO) / D(lookback)
        previous_btc = sum((point.btc_mean for point in previous), ZERO) / D(lookback)
        current_fee = sum((point.fee_mean for point in current), ZERO) / D(lookback)
        previous_fee = sum((point.fee_mean for point in previous), ZERO) / D(lookback)
        current_hashrate = sum((point.hashrate_mean for point in current), ZERO) / D(lookback)
        previous_hashrate = sum((point.hashrate_mean for point in previous), ZERO) / D(lookback)
        proxy_growth = _growth(current_proxy, previous_proxy, "proxy")
        points.append(
            FactorPoint(
                weekly[index].eligible_at,
                weekly[index].week_end,
                lookback,
                proxy_growth,
                _growth(current_btc, previous_btc, "btc"),
                _growth(current_fee, previous_fee, "fee"),
                _growth(current_hashrate, previous_hashrate, "hashrate"),
                proxy_growth > ZERO,
            )
        )
    return points


def _midranks(values: list[D]) -> list[D]:
    indexed = sorted(enumerate(values), key=lambda item: item[1])
    ranks = [ZERO] * len(values)
    cursor = 0
    while cursor < len(indexed):
        end = cursor + 1
        while end < len(indexed) and indexed[end][1] == indexed[cursor][1]:
            end += 1
        rank = (D(cursor + 1) + D(end)) / D("2")
        for offset in range(cursor, end):
            ranks[indexed[offset][0]] = rank
        cursor = end
    return ranks


def spearman_correlation(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 3:
        raise ResearchReject("FEATURE_REJECT:CORRELATION_INVENTORY")
    left_rank = _midranks(left)
    right_rank = _midranks(right)
    left_mean = sum(left_rank, ZERO) / D(len(left_rank))
    right_mean = sum(right_rank, ZERO) / D(len(right_rank))
    covariance = sum(
        ((a - left_mean) * (b - right_mean) for a, b in zip(left_rank, right_rank)),
        ZERO,
    )
    left_variance = sum(((value - left_mean) ** 2 for value in left_rank), ZERO)
    right_variance = sum(((value - right_mean) ** 2 for value in right_rank), ZERO)
    denominator = (left_variance * right_variance).sqrt()
    if denominator == ZERO:
        raise ResearchReject("FEATURE_REJECT:CONSTANT_CORRELATION_INPUT")
    return covariance / denominator


def nonredundancy_diagnostic(
    primary: list[FactorPoint], window: tuple[datetime, datetime], label: str
) -> tuple[dict[str, Any], dict[str, bool]]:
    start, end = window
    selected = [point for point in primary if start <= point.eligible_at < end]
    proxy = [point.proxy_growth for point in selected]
    correlations = {
        "btc_price_growth": spearman_correlation(proxy, [point.btc_growth for point in selected]),
        "hashrate_growth": spearman_correlation(proxy, [point.hashrate_growth for point in selected]),
        "fee_growth": spearman_correlation(proxy, [point.fee_growth for point in selected]),
    }
    diagnostic = {
        "observation_count": len(selected),
        "absolute_spearman_limit": q(REDUNDANCY_LIMIT),
        "correlations": {
            name: {"spearman": q(value), "absolute_spearman": q(abs(value)), "pass": abs(value) <= REDUNDANCY_LIMIT}
            for name, value in correlations.items()
        },
    }
    gates = {
        f"primary_{label}_proxy_growth_abs_spearman_to_btc_price_growth_at_most_0_80": abs(correlations["btc_price_growth"]) <= REDUNDANCY_LIMIT,
        f"primary_{label}_proxy_growth_abs_spearman_to_hashrate_growth_at_most_0_80": abs(correlations["hashrate_growth"]) <= REDUNDANCY_LIMIT,
        f"primary_{label}_proxy_growth_abs_spearman_to_fee_growth_at_most_0_80": abs(correlations["fee_growth"]) <= REDUNDANCY_LIMIT,
    }
    return diagnostic, gates


def feature_summary(points: list[FactorPoint]) -> dict[str, Any]:
    values = [point.proxy_growth for point in points]
    return {
        "lookback_complete_weeks": points[0].lookback_weeks,
        "evaluation_count": len(points),
        "positive_count": sum(value > ZERO for value in values),
        "nonpositive_count": sum(value <= ZERO for value in values),
        "minimum": q(min(values)),
        "maximum": q(max(values)),
        "median": q(percentile(values, D("0.5")) or ZERO),
        "first_effective_time": points[0].eligible_at.isoformat(),
        "last_effective_time": points[-1].eligible_at.isoformat(),
    }


def simulate_window(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    factor_by_variant: dict[str, list[FactorPoint]],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, Any] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for variant_name, variant in VARIANTS.items():
        points = factor_by_variant[variant_name]
        targets = {point.eligible_at: point.long_target for point in points}
        output[variant_name] = {
            "role": variant["role"],
            "feature": feature_summary(points),
            "scenarios": {},
        }
        raw[variant_name] = {}
        for scenario_name, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = ledger.simulate_scenario(
                reference, bars, targets, window, fee_rate, slippage
            )
            output[variant_name]["scenarios"][scenario_name] = scenario_output
            raw[variant_name][scenario_name] = scenario_raw
    return output, raw


def evaluate_economic_gates(
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn = design["PRIMARY_MINER4"]["NORMAL"]
    ds = design["PRIMARY_MINER4"]["STRESS"]
    vn = validation["PRIMARY_MINER4"]["NORMAL"]
    vs = validation["PRIMARY_MINER4"]["STRESS"]
    gates: dict[str, bool] = {
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"] <= D("0.95") * dn["buy_hold_drawdown"],
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "primary_validation_position_changes_between_2_and_100": D("2") <= vn["position_changes"] <= D("100"),
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
    }
    for neighbor in ("NEIGHBOR_MINER2", "NEIGHBOR_MINER8"):
        label = neighbor.lower()
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[f"{label}_validation_{scenario.lower()}_total_return_pct_gt_0"] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{label}_validation_normal_drawdown_non_worse"] = value["drawdown"] <= value["buy_hold_drawdown"]
        gates[f"{label}_validation_normal_calmar_at_least_75pct_buy_hold"] = value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        gates[f"{label}_validation_normal_upside_capture_at_least_50pct"] = value["upside_capture"] >= D("0.50")
    annual_raw = {year: value[1]["PRIMARY_MINER4"] for year, value in annual.items()}
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
    if tuple(gates) != ECONOMIC_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_GATE_DRIFT")
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": q(top_year),
        "primary_validation_top_positive_episode_contribution_pct": validation_output["PRIMARY_MINER4"]["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    expected_datasets = {
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "expected_complete_utc_days": EXPECTED_BTC_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "fees": {"path": ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/inputs/coinmetrics-btc-fee-pressure-2018-2024.csv", "sha256": EXPECTED_FEE_SHA256, "rows": EXPECTED_SOURCE_ROWS, "first_date": "2018-01-01", "last_date": "2024-12-31", "present_vintage": True},
        "hashrate": {"path": ".research-state/experiments/dra-bitcoin-hashrate-growth-entry-admission-historical-v1/inputs/coinmetrics-btc-hashrate-2018-2024.csv", "sha256": EXPECTED_HASHRATE_SHA256, "rows": EXPECTED_SOURCE_ROWS, "first_date": "2018-01-01", "last_date": "2024-12-31", "present_vintage": True},
    }
    if manifest.get("datasets") != expected_datasets:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    expected_policy = {
        "policy_id": "BTC_MINER_REVENUE_PER_HASH_MOMENTUM_LONG_CASH_V1",
        "daily_proxy": "((EXPECTED_144_BLOCKS_TIMES_PROTOCOL_BLOCK_SUBSIDY_BTC)+TOTAL_DAILY_FEES_BTC)_TIMES_BTC_COMPLETE_UTC_DAY_CLOSE_USD_DIVIDED_BY_HASH_RATE_TH_PER_S",
        "block_subsidy_schedule": {"through_2020_05_10": "12.5", "from_2020_05_11_through_2024_04_19": "6.25", "from_2024_04_20": "3.125"},
        "weekly_aggregation": "MEAN_OF_EXACTLY_SEVEN_COMPLETE_MONDAY_THROUGH_SUNDAY_DAILY_PROXY_VALUES",
        "primary_current_and_previous_adjacent_complete_weeks": 4,
        "rejection_only_neighbor_current_and_previous_adjacent_complete_weeks": [2, 8],
        "component_growth": "CURRENT_N_WEEK_MEAN_DIVIDED_BY_PREVIOUS_ADJACENT_N_WEEK_MEAN_MINUS_ONE",
        "availability": "WEEK_ENDING_SUNDAY_PLUS_THREE_CALENDAR_DAYS_AT_00_00_UTC",
        "long_condition": "PROXY_GROWTH_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "PROXY_GROWTH_LESS_THAN_OR_EQUAL_TO_ZERO",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "signal_validity_hours": 168,
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 3,
    }
    if manifest.get("strategy_policy") != expected_policy:
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
        "id": "BTC_MINER_REVENUE_PER_HASH_MOMENTUM_GATES_V1",
        "pre_economic_nonredundancy_required": list(NONREDUNDANCY_GATE_NAMES),
        "economic_required_only_after_nonredundancy_pass": list(ECONOMIC_GATE_NAMES),
        "decision": "DUPLICATE_REJECT_BEFORE_ECONOMICS_OR_ALL_ECONOMIC_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
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


def build_output(
    btc_input: Path,
    fee_input: Path,
    hashrate_input: Path,
    manifest_path: Path,
) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        LEDGER_SOURCE: EXPECTED_LEDGER_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
        FEE_METADATA_SOURCE: EXPECTED_FEE_METADATA_SHA256,
        HASHRATE_METADATA_SOURCE: EXPECTED_HASHRATE_METADATA_SHA256,
    }
    for source, expected in expected_sources.items():
        if sha256(source) != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source.relative_to(REPO_ROOT)}")
    if sha256(btc_input) != EXPECTED_BTC_SHA256 or sha256(fee_input) != EXPECTED_FEE_SHA256 or sha256(hashrate_input) != EXPECTED_HASHRATE_SHA256:
        raise ResearchReject("DATA_REJECT:INPUT_SHA256")

    parser = load_module("miner_proxy_frozen_h1_parser", PARSER_SOURCE)
    reference = load_module("miner_proxy_frozen_long_cash_reference", REFERENCE_SOURCE)
    ledger = load_module("miner_proxy_frozen_ledger", LEDGER_SOURCE)
    bars = parser.parse_rows(btc_input.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    btc_daily = ledger.build_daily_points(bars)
    fees = load_daily_series(fee_input, "total_fees_btc", allow_zero=True)
    hashrate = load_daily_series(hashrate_input, "hash_rate_th_per_s", allow_zero=False)
    daily = build_daily_points(btc_daily, fees, hashrate)
    weekly = build_weekly_points(daily)
    factor_by_variant = {
        name: build_factor_points(weekly, variant["lookback"])
        for name, variant in VARIANTS.items()
    }
    primary = factor_by_variant["PRIMARY_MINER4"]
    design_nonredundancy, design_gates = nonredundancy_diagnostic(primary, DESIGN, "design")
    validation_nonredundancy, validation_gates = nonredundancy_diagnostic(primary, VALIDATION, "validation")
    nonredundancy_gates = {**design_gates, **validation_gates}
    if tuple(nonredundancy_gates) != NONREDUNDANCY_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:NONREDUNDANCY_GATE_DRIFT")
    failed_nonredundancy = [name for name, passed in nonredundancy_gates.items() if not passed]
    base = {
        "schema_version": "1",
        "document_type": "BTC_MINER_REVENUE_PER_HASH_MOMENTUM_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_input), "hourly_rows": len(bars), "complete_utc_days": len(btc_daily)},
            "fees": {"path": fee_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(fee_input), "rows": len(fees)},
            "hashrate": {"path": hashrate_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(hashrate_input), "rows": len(hashrate)},
            "complete_overlap_weeks": len(weekly),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "policy": {"primary_current_and_previous_adjacent_complete_weeks": 4, "rejection_only_neighbors": [2, 8], "expected_blocks_per_day": 144, "threshold": "STRICTLY_GREATER_THAN_ZERO", "variants": 3},
        "feature_inventory": {name: feature_summary(points) for name, points in factor_by_variant.items()},
        "nonredundancy": {"design": design_nonredundancy, "validation": validation_nonredundancy, "gates": nonredundancy_gates, "failed_gates": failed_nonredundancy},
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage theoretical miner-revenue-per-hash proxy evidence only; the 144-block assumption is not actual miner revenue and no result authorizes runtime use.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_nonredundancy:
        return {
            **base,
            "status": "DUPLICATE_REJECT_CLOSE_BTC_MINER_REVENUE_PER_HASH_MOMENTUM_FAMILY",
            "decision": "PERMANENTLY_CLOSE_EXACT_MINER2_4_8_PROXY_FAMILY_BEFORE_ECONOMICS_WITHOUT_RETUNING",
            "economic_evidence_accessed": False,
            "design": None,
            "validation": None,
            "annual_fair_reset": None,
            "breadth_and_concentration": None,
            "gates": nonredundancy_gates,
            "failed_gates": failed_nonredundancy,
            "all_gates_pass": False,
            "candidate_created": False,
        }
    design_output, design_raw = simulate_window(ledger, reference, bars, factor_by_variant, DESIGN)
    validation_output, validation_raw = simulate_window(ledger, reference, bars, factor_by_variant, VALIDATION)
    annual = {
        year: simulate_window(ledger, reference, bars, factor_by_variant, window)
        for year, window in ANNUAL.items()
    }
    economic_gates, failed_economic, breadth = evaluate_economic_gates(
        design_raw, validation_output, validation_raw, annual
    )
    gates = {**nonredundancy_gates, **economic_gates}
    failed = failed_economic
    passed = not failed
    return {
        **base,
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_MINER_REVENUE_PER_HASH_MOMENTUM_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_MINER2_4_8_PROXY_FAMILY_WITHOUT_TUNING",
        "economic_evidence_accessed": True,
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--fee-input", type=Path, required=True)
    parser.add_argument("--hashrate-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.fee_input.resolve(), args.hashrate_input.resolve(), args.manifest.resolve()]
    output_path = args.output.resolve()
    if not all(path.is_relative_to(REPO_ROOT) for path in inputs):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(*inputs)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"], "economic_evidence_accessed": result["economic_evidence_accessed"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
