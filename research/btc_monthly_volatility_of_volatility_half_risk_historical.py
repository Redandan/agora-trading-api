#!/usr/bin/env python3
"""Deterministic historical screen for monthly BTC volatility-instability de-risking."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 50

D = Decimal
ZERO = D("0")
ONE = D("1")
HALF = D("0.5")
HUNDRED = D("100")
LOOKBACK_DAYS = 20
PERCENTILE_LOOKBACK = 252
REDUNDANCY_LIMIT = D("0.80")
VARIANTS = (
    ("volatility-of-volatility-p70-v1", "lower_neighbor", D("0.7")),
    ("volatility-of-volatility-p80-v1", "primary", D("0.8")),
    ("volatility-of-volatility-p90-v1", "upper_neighbor", D("0.9")),
)

REPO_ROOT = Path(__file__).resolve().parents[1]
EXECUTION_REFERENCE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_30d_volatility_target_40pct_historical.py"
)
ECONOMIC_BASE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-volatility-of-volatility-half-risk-primary-prior.v1.json"
)
EXPERIMENT_ID = "btc-monthly-volatility-of-volatility-half-risk-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_HISTORICAL_MANIFEST_V1"
)
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_EXECUTION_REFERENCE_SHA256 = (
    "8eb185644904b62152feb9170964fa86032ee561680a1ba92786746dc9a466d6"
)
EXPECTED_ECONOMIC_BASE_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "7ad992e99eda7a143f633ec8ec1372da0deb0683f9ac75a34a7909b32983486c"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    "2020": (datetime(2020, 1, 1), datetime(2021, 1, 1)),
    "2021": (datetime(2021, 1, 1), datetime(2022, 1, 1)),
    "2022": (datetime(2022, 1, 1), datetime(2023, 1, 1)),
    "2023": (datetime(2023, 1, 1), datetime(2024, 1, 1)),
    "2024": (datetime(2024, 1, 1), datetime(2025, 1, 1)),
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyRisk:
    effective_time: datetime
    realized_volatility: D


@dataclass(frozen=True)
class FeaturePoint:
    effective_time: datetime
    volatility_of_volatility: D
    mean_realized_volatility: D
    percentile: D | None


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, source: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, source)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{source}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def midrank_percentile(value: D, prior: list[D]) -> D:
    if len(prior) != PERCENTILE_LOOKBACK:
        raise ResearchReject(f"FEATURE_REJECT:PERCENTILE_LOOKBACK:{len(prior)}")
    less = sum(item < value for item in prior)
    equal = sum(item == value for item in prior)
    return (D(less) + D(equal) / D("2")) / D(PERCENTILE_LOOKBACK)


def _population_standard_deviation(values: list[D]) -> D:
    if len(values) != LOOKBACK_DAYS:
        raise ResearchReject(f"FEATURE_REJECT:VOV_LOOKBACK:{len(values)}")
    mean = sum(values, ZERO) / D(len(values))
    return (sum(((value - mean) ** 2 for value in values), ZERO) / D(len(values))).sqrt()


def build_daily_risk(bars: list[object]) -> list[DailyRisk]:
    if len(bars) != EXPECTED_DATA_ROWS:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(bars)}")
    output: list[DailyRisk] = []
    current_day = bars[0].open_time.date()
    previous_close = bars[0].open
    squared_returns = ZERO
    observations = 0
    for bar in bars:
        if bar.open_time.date() != current_day:
            raise ResearchReject("DATA_REJECT:DAY_CHANGED_BEFORE_DAY_END")
        if previous_close <= ZERO or bar.close <= ZERO:
            raise ResearchReject("DATA_REJECT:NONPOSITIVE_CLOSE")
        hourly_return = (bar.close / previous_close).ln()
        squared_returns += hourly_return * hourly_return
        observations += 1
        previous_close = bar.close
        if bar.close_time.date() != current_day:
            if observations != 24 or bar.close_time.hour != 0:
                raise ResearchReject(
                    f"DATA_REJECT:DAILY_HOURS:{current_day}:{observations}"
                )
            realized = squared_returns.sqrt()
            if realized <= ZERO:
                raise ResearchReject(f"FEATURE_REJECT:ZERO_DAILY_RV:{current_day}")
            output.append(DailyRisk(bar.close_time, realized))
            current_day = bar.close_time.date()
            squared_returns = ZERO
            observations = 0
    if observations != 0 or len(output) != 2_192:
        raise ResearchReject(
            f"DATA_REJECT:DAILY_INVENTORY:{len(output)}:{observations}"
        )
    if output[0].effective_time != datetime(2019, 1, 2):
        raise ResearchReject("DATA_REJECT:FIRST_COMPLETE_DAY")
    if output[-1].effective_time != datetime(2025, 1, 1):
        raise ResearchReject("DATA_REJECT:LAST_COMPLETE_DAY")
    return output


def build_feature_points(daily: list[DailyRisk]) -> list[FeaturePoint]:
    raw: list[tuple[datetime, D, D]] = []
    for index in range(LOOKBACK_DAYS - 1, len(daily)):
        window = daily[index - LOOKBACK_DAYS + 1 : index + 1]
        realized = [item.realized_volatility for item in window]
        log_realized = [value.ln() for value in realized]
        vov = _population_standard_deviation(log_realized)
        level = sum(realized, ZERO) / D(LOOKBACK_DAYS)
        raw.append((daily[index].effective_time, vov, level))

    output: list[FeaturePoint] = []
    for index, (effective_time, vov, level) in enumerate(raw):
        percentile = None
        if index >= PERCENTILE_LOOKBACK:
            percentile = midrank_percentile(
                vov,
                [item[1] for item in raw[index - PERCENTILE_LOOKBACK : index]],
            )
        output.append(FeaturePoint(effective_time, vov, level, percentile))
    return output


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


def feature_diagnostic(
    points: list[FeaturePoint], window: tuple[datetime, datetime]
) -> dict[str, object]:
    start, end = window
    selected = [
        point
        for point in points
        if point.percentile is not None
        and point.effective_time.day == 1
        and start <= point.effective_time < end
    ]
    expected = int((end.year - start.year) * 12 + end.month - start.month)
    if len(selected) != expected:
        raise ResearchReject(
            f"FEATURE_REJECT:MONTHLY_SIGNAL_INVENTORY:{len(selected)}:{expected}"
        )
    correlation = spearman_correlation(
        [point.volatility_of_volatility for point in selected],
        [point.mean_realized_volatility for point in selected],
    )
    return {
        "monthly_observations": len(selected),
        "spearman_to_20d_mean_realized_volatility": str(correlation),
        "absolute_spearman": str(abs(correlation)),
        "nonredundancy_limit": str(REDUNDANCY_LIMIT),
        "nonredundancy_pass": abs(correlation) <= REDUNDANCY_LIMIT,
    }


def simulate_scenario(
    bars: list[object],
    signals: dict[datetime, FeaturePoint],
    window: tuple[datetime, datetime],
    threshold: D,
    fee_rate: D,
    slippage: D,
    base: ModuleType,
    execution: ModuleType,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    trading = [bar for bar in bars if start <= bar.open_time < end]
    expected_hours = int((end - start).total_seconds() // 3600)
    if (
        len(trading) != expected_hours
        or not trading
        or trading[0].open_time != start
        or trading[-1].close_time != end
    ):
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}:{end.isoformat()}")

    cash = ONE
    lots: list[object] = []
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_evaluations = 0
    high_risk_targets = 0
    full_risk_targets = 0
    rebalance_trades = 0
    buy_trades = 0
    sell_trades = 0
    realized_slices: list[D] = []
    holding_hours: list[D] = []
    target_weights: list[D] = []
    path = base.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        point = signals.get(bar.open_time)
        if point is not None:
            target = HALF if point.percentile > threshold else ONE
            target_weights.append(target)
            signal_evaluations += 1
            high_risk_targets += target == HALF
            full_risk_targets += target == ONE
            (
                cash,
                trade_fees,
                trade_turnover,
                trade_realized,
                trade_side,
                trade_slices,
                trade_holds,
            ) = execution.execute_target(
                lots=lots,
                cash=cash,
                target_weight=target,
                open_price=bar.open,
                fee_rate=fee_rate,
                slippage=slippage,
                execution_time=bar.open_time,
            )
            fees += trade_fees
            turnover += trade_turnover
            realized += trade_realized
            realized_slices.extend(trade_slices)
            holding_hours.extend(trade_holds)
            if trade_side is not None:
                rebalance_trades += 1
                buy_trades += trade_side == "BUY"
                sell_trades += trade_side == "SELL"

        quantity = execution.total_quantity(lots)
        market_value = quantity * bar.close
        final_equity = cash + market_value
        path.observe(
            final_equity,
            market_value / final_equity if final_equity > ZERO else ZERO,
        )

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    quantity = execution.total_quantity(lots)
    terminal_net = (
        quantity * trading[-1].close * (ONE - slippage) * (ONE - fee_rate)
        if quantity > ZERO
        else ZERO
    )
    terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (terminal_liquidation_equity - ONE) * HUNDRED
    positive_slices = [value for value in realized_slices if value > ZERO]
    terminal_oldest_age = (
        ZERO
        if not lots
        else D(str((end - min(lot.entry_time for lot in lots)).total_seconds() / 3600))
    )
    terminal_cost_basis = sum((lot.cost_basis for lot in lots), ZERO)
    candidate.update(
        {
            "realized_return_pct": base.q(realized * HUNDRED),
            "unrealized_return_pct": base.q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": base.q(
                terminal_liquidation_return
            ),
            "terminal_liquidation_cost_pp": base.q(
                raw["total_return"] - terminal_liquidation_return
            ),
            "fees_equity_units": base.q(fees),
            "turnover_equity_units": base.q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "high_risk_target_count": high_risk_targets,
            "full_risk_target_count": full_risk_targets,
            "rebalance_trade_count": rebalance_trades,
            "buy_trade_count": buy_trades,
            "sell_trade_count": sell_trades,
            "realized_lot_slice_count": len(realized_slices),
            "median_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.5"))
            ),
            "p90_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.9"))
            ),
            "terminal_position": quantity > ZERO,
            "terminal_oldest_lot_age_hours": (
                None if not lots else base.q(terminal_oldest_age)
            ),
            "terminal_inventory_cost_basis": base.q(terminal_cost_basis),
            "median_target_exposure_pct": base.nullable(
                None
                if not target_weights
                else (base.percentile(target_weights, D("0.5")) or ZERO) * HUNDRED
            ),
            "top_positive_realized_lot_contribution_pct": (
                None
                if not positive_slices
                else base.q(
                    max(positive_slices) / sum(positive_slices, ZERO) * HUNDRED
                )
            ),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"]
            - terminal_liquidation_return,
            "signal_evaluations": D(signal_evaluations),
            "high_risk_targets": D(high_risk_targets),
            "full_risk_targets": D(full_risk_targets),
            "rebalance_trades": D(rebalance_trades),
            "p90_hold": base.percentile(holding_hours, D("0.9")) or ZERO,
            "terminal_oldest_age": terminal_oldest_age,
            "top_positive_realized_contribution": (
                ZERO
                if not positive_slices
                else max(positive_slices) / sum(positive_slices, ZERO) * HUNDRED
            ),
            "has_positive_realized_slice": ONE if positive_slices else ZERO,
        }
    )
    benchmark, benchmark_raw = base.passive_benchmark(trading, fee_rate, slippage)
    upside_capture = (
        raw["total_return"] / benchmark_raw["total_return"]
        if benchmark_raw["total_return"] > ZERO
        else None
    )
    return {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate": candidate,
        "buy_and_hold": benchmark,
        "comparison": {
            "total_return_delta_pp": base.q(
                raw["total_return"] - benchmark_raw["total_return"]
            ),
            "maximum_drawdown_delta_pp": base.q(
                raw["drawdown"] - benchmark_raw["drawdown"]
            ),
            "upside_capture_ratio": base.nullable(upside_capture),
            "calmar_ratio_to_buy_hold": base.nullable(
                raw["calmar"] / benchmark_raw["calmar"]
                if benchmark_raw["calmar"] != ZERO
                else None
            ),
        },
    }, {
        **raw,
        "buy_hold_return": benchmark_raw["total_return"],
        "buy_hold_drawdown": benchmark_raw["drawdown"],
        "buy_hold_calmar": benchmark_raw["calmar"],
        "upside_capture": upside_capture if upside_capture is not None else ZERO,
    }


def simulate_window(
    bars: list[object],
    signals: dict[datetime, FeaturePoint],
    window: tuple[datetime, datetime],
    threshold: D,
    base: ModuleType,
    execution: ModuleType,
) -> tuple[dict[str, object], dict[str, dict[str, D]]]:
    outputs: dict[str, object] = {}
    raws: dict[str, dict[str, D]] = {}
    for name, (fee_rate, slippage) in SCENARIOS.items():
        outputs[name], raws[name] = simulate_scenario(
            bars, signals, window, threshold, fee_rate, slippage, base, execution
        )
    return outputs, raws


def breadth(raws: dict[str, dict[str, dict[str, D]]], base: ModuleType) -> dict[str, object]:
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in raws.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in raws.values())
    drawdown_nonworse = sum(
        value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"]
        for value in raws.values()
    )
    calmar_nonworse = sum(
        value["NORMAL"]["calmar"] >= value["NORMAL"]["buy_hold_calmar"]
        for value in raws.values()
    )
    positives = [max(value["NORMAL"]["total_return"], ZERO) for value in raws.values()]
    positive_sum = sum(positives, ZERO)
    top_year = (
        max(positives, default=ZERO) / positive_sum * HUNDRED
        if positive_sum > ZERO
        else HUNDRED
    )
    return {
        "normal_positive_years": normal_positive,
        "stress_positive_years": stress_positive,
        "normal_drawdown_non_worse_years": drawdown_nonworse,
        "normal_calmar_non_worse_years": calmar_nonworse,
        "top_year_positive_total_return_contribution_pct": base.q(top_year),
        "top_year_raw": top_year,
    }


def primary_gates(
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, object],
) -> dict[str, bool]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"]
        <= D("0.95") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_80pct": dn["upside_capture"] >= D("0.80"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_95pct_of_buy_hold": vn["drawdown"]
        <= D("0.95") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_80pct": vn["upside_capture"] >= D("0.80"),
        "validation_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "design_signal_evaluations_exactly_36": dn["signal_evaluations"] == D("36"),
        "validation_signal_evaluations_exactly_24": vn["signal_evaluations"] == D("24"),
        "design_both_risk_states_observed": dn["high_risk_targets"] >= ONE
        and dn["full_risk_targets"] >= ONE,
        "validation_both_risk_states_observed": vn["high_risk_targets"] >= ONE
        and vn["full_risk_targets"] >= ONE,
        "validation_at_least_2_rebalance_trades": vn["rebalance_trades"] >= D("2"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"]
        <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_5": annual["normal_positive_years"] >= 4,
        "stress_positive_annual_return_at_least_4_of_5": annual["stress_positive_years"] >= 4,
        "annual_drawdown_non_worse_at_least_4_of_5": annual["normal_drawdown_non_worse_years"] >= 4,
        "annual_calmar_non_worse_at_least_3_of_5": annual["normal_calmar_non_worse_years"] >= 3,
        "top_year_positive_contribution_at_most_60pct": annual["top_year_raw"] <= D("60"),
        "validation_top_positive_realized_lot_contribution_at_most_60pct": (
            vn["has_positive_realized_slice"] == ZERO
            or vn["top_positive_realized_contribution"] <= D("60")
        ),
        "validation_p90_realized_lot_hold_at_most_17520_hours": vn["p90_hold"]
        <= D("17520"),
        "validation_terminal_oldest_lot_age_at_most_17520_hours": vn["terminal_oldest_age"]
        <= D("17520"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"]
        > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"]
        <= ONE,
    }


def neighbor_gates(
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, object],
) -> dict[str, bool]:
    dn, vn, vs = design["NORMAL"], validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_non_worse_than_buy_hold": vn["drawdown"]
        <= vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_80pct": vn["upside_capture"] >= D("0.80"),
        "validation_calmar_at_least_85pct_of_buy_hold": vn["calmar"]
        >= D("0.85") * vn["buy_hold_calmar"],
        "normal_positive_annual_return_at_least_4_of_5": annual["normal_positive_years"] >= 4,
    }


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    expected = [
        {"variant_id": variant_id, "role": role, "threshold": str(threshold)}
        for variant_id, role, threshold in VARIANTS
    ]
    policy = manifest.get("strategy_policy", {})
    if policy.get("variants") != expected:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    if policy.get("high_risk_target") != "BTC_50_PERCENT_CASH_50_PERCENT":
        raise ResearchReject("MANIFEST_REJECT:HIGH_RISK_TARGET")
    if manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:OOS_ACCESS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    bindings = {
        "dataset": (input_path, EXPECTED_DATA_SHA256),
        "execution_reference": (
            EXECUTION_REFERENCE_SOURCE,
            EXPECTED_EXECUTION_REFERENCE_SHA256,
        ),
        "economic_base": (ECONOMIC_BASE_SOURCE, EXPECTED_ECONOMIC_BASE_SHA256),
        "parser": (PARSER_SOURCE, EXPECTED_PARSER_SHA256),
        "prior": (PRIOR_SOURCE, EXPECTED_PRIOR_SHA256),
    }
    for label, (path, expected) in bindings.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    execution = load_module("frozen_vov_execution_reference", EXECUTION_REFERENCE_SOURCE)
    base = load_module("frozen_vov_economic_base", ECONOMIC_BASE_SOURCE)
    parser = load_module("frozen_vov_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")

    daily = build_daily_risk(bars)
    points = build_feature_points(daily)
    monthly_signals = {
        point.effective_time: point
        for point in points
        if point.percentile is not None and point.effective_time.day == 1
    }
    design_feature = feature_diagnostic(points, DESIGN)
    validation_feature = feature_diagnostic(points, VALIDATION)
    nonredundancy_pass = bool(design_feature["nonredundancy_pass"]) and bool(
        validation_feature["nonredundancy_pass"]
    )
    common = {
        "schema_version": "1",
        "document_type": "BTC_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": EXPECTED_DATA_SHA256,
            "rows": len(bars),
            "complete_days": len(daily),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "source_bindings": {label: expected for label, (_, expected) in bindings.items()},
        "feature": {
            "daily_risk_observations": len(daily),
            "volatility_of_volatility_observations": len(points),
            "monthly_signals_after_warmup": len(monthly_signals),
            "design_nonredundancy": design_feature,
            "validation_nonredundancy": validation_feature,
        },
        "policy": {
            "daily_realized_volatility": "SQRT_SUM_24_SQUARED_HOURLY_LOG_RETURNS",
            "volatility_of_volatility_lookback_days": LOOKBACK_DAYS,
            "percentile_lookback_days": PERCENTILE_LOOKBACK,
            "decision_clock": "UTC_MONTH_BOUNDARY",
            "execution": "NEXT_HOURLY_OPEN",
            "low_risk_target": "BTC_100_PERCENT",
            "high_risk_target": "BTC_50_PERCENT_CASH_50_PERCENT",
            "variants": 3,
        },
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass requires separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if not nonredundancy_pass:
        return {
            **common,
            "status": "DUPLICATE_REJECT_CLOSE_BTC_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_FAMILY",
            "decision": "CLOSE_AS_REALIZED_VOLATILITY_LEVEL_REPACKAGING_BEFORE_ECONOMICS",
            "variants": [],
            "failed_primary_gates": ["feature_nonredundancy_absolute_spearman_at_most_0_80"],
            "failed_neighbor_gates": {},
            "all_gates_pass": False,
            "economics_opened": False,
        }

    variants: list[dict[str, object]] = []
    primary: dict[str, object] | None = None
    failed_neighbors: dict[str, list[str]] = {}
    for variant_id, role, threshold in VARIANTS:
        design_output, design_raw = simulate_window(
            bars, monthly_signals, DESIGN, threshold, base, execution
        )
        validation_output, validation_raw = simulate_window(
            bars, monthly_signals, VALIDATION, threshold, base, execution
        )
        annual_outputs = {
            year: simulate_window(
                bars, monthly_signals, window, threshold, base, execution
            )
            for year, window in ANNUAL.items()
        }
        annual_breadth = breadth(
            {year: value[1] for year, value in annual_outputs.items()}, base
        )
        gate_breadth = dict(annual_breadth)
        annual_breadth.pop("top_year_raw")
        variant: dict[str, object] = {
            "variant_id": variant_id,
            "role": role,
            "threshold": str(threshold),
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {
                year: value[0] for year, value in annual_outputs.items()
            },
            "breadth_and_concentration": annual_breadth,
        }
        if role == "primary":
            gates = primary_gates(design_raw, validation_raw, gate_breadth)
            variant["primary_gates"] = gates
            primary = variant
        else:
            gates = neighbor_gates(design_raw, validation_raw, gate_breadth)
            variant["neighbor_gates"] = gates
            failed = [name for name, passed in gates.items() if not passed]
            if failed:
                failed_neighbors[variant_id] = failed
        variants.append(variant)

    if primary is None:
        raise ResearchReject("POLICY_REJECT:NO_PRIMARY")
    primary_gates_value = primary["primary_gates"]
    failed_primary = [
        name for name, passed in primary_gates_value.items() if not passed
    ]
    passed = not failed_primary and not failed_neighbors
    return {
        **common,
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_NEIGHBOR_GATES_PASS_SEALED_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_FAMILY_WITHOUT_TUNING"
        ),
        "variants": variants,
        "failed_primary_gates": failed_primary,
        "failed_neighbor_gates": failed_neighbors,
        "all_gates_pass": passed,
        "economics_opened": True,
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
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
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
                "failed_primary_gates": result["failed_primary_gates"],
                "failed_neighbor_gates": result["failed_neighbor_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
