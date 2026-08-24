#!/usr/bin/env python3
"""Deterministic historical screen for a daily trailing-drawdown BTC overlay."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
HALF = D("0.5")
HUNDRED = D("100")
LOOKBACK_DAYS = 365

REPO_ROOT = Path(__file__).resolve().parents[1]
DAILY_SUPPORT_SOURCE = (
    REPO_ROOT / "research" / "btc_daily_chaikin_money_flow_long_cash_historical.py"
)
EXECUTION_SOURCE = (
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
    / "btc-daily-trailing365d-drawdown20-passive-core-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-trailing365d-drawdown20-passive-core-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-daily-trailing365d-drawdown20-passive-core-risk-overlay-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_DAILY_TRAILING365D_DRAWDOWN20_PASSIVE_CORE_RISK_OVERLAY_HISTORICAL_MANIFEST_V1"
)
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_SOURCE_HASHES = {
    DAILY_SUPPORT_SOURCE: "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd",
    EXECUTION_SOURCE: "8eb185644904b62152feb9170964fa86032ee561680a1ba92786746dc9a466d6",
    ECONOMIC_BASE_SOURCE: "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b",
    PARSER_SOURCE: "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
    PRIOR_SOURCE: "df250f21f5ba1d0879e91d34ca75a658710ffe5ad47ca55b40b1d1c806493169",
    HYPOTHESIS_SOURCE: "33ff9a683b517391f2a50556a90557d703c6f430272fa9b841fc3efa5e5640e0",
}

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = (
    ("NEIGHBOR_DRAWDOWN15", "REJECTION_ONLY_NEIGHBOR", D("15")),
    ("PRIMARY_DRAWDOWN20", "PRIMARY", D("20")),
    ("NEIGHBOR_DRAWDOWN25", "REJECTION_ONLY_NEIGHBOR", D("25")),
)
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DrawdownPoint:
    effective_time: datetime
    value: D


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def trailing_close_drawdown_pct(closes: list[D]) -> D:
    if len(closes) != LOOKBACK_DAYS or any(value <= ZERO for value in closes):
        raise ResearchReject("FEATURE_REJECT:TRAILING365D_DRAWDOWN_INPUT")
    trailing_peak = max(closes)
    return (closes[-1] / trailing_peak - ONE) * HUNDRED


def build_drawdown_points(daily: list[object]) -> list[DrawdownPoint]:
    closes = [point.close for point in daily]
    return [
        DrawdownPoint(
            daily[index].close_time,
            trailing_close_drawdown_pct(
                closes[index - LOOKBACK_DAYS + 1 : index + 1]
            ),
        )
        for index in range(LOOKBACK_DAYS - 1, len(daily))
    ]


def build_targets(points: list[DrawdownPoint], threshold_pct: D) -> dict[datetime, D]:
    if threshold_pct not in {D("15"), D("20"), D("25")}:
        raise ResearchReject(f"MANIFEST_REJECT:DRAWDOWN_THRESHOLD:{threshold_pct}")
    return {
        point.effective_time: HALF if point.value <= -threshold_pct else ONE
        for point in points
    }


def simulate_scenario(
    bars: list[object],
    targets: dict[datetime, D],
    window: tuple[datetime, datetime],
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
    current_target: D | None = None
    signal_evaluations = 0
    high_risk_days = 0
    full_risk_days = 0
    regime_changes = 0
    buy_trades = 0
    sell_trades = 0
    realized_slices: list[D] = []
    holding_hours: list[D] = []
    observed_targets: list[D] = []
    path = base.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        target = targets.get(bar.open_time)
        if target is not None:
            signal_evaluations += 1
            high_risk_days += target == HALF
            full_risk_days += target == ONE
            observed_targets.append(target)
            if current_target is None or target != current_target:
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
                    regime_changes += 1
                    buy_trades += trade_side == "BUY"
                    sell_trades += trade_side == "SELL"
                current_target = target

        quantity = execution.total_quantity(lots)
        market_value = quantity * bar.close
        final_equity = cash + market_value
        path.observe(
            final_equity,
            market_value / final_equity if final_equity > ZERO else ZERO,
        )

    expected_days = (end - start).days
    if signal_evaluations != expected_days:
        raise ResearchReject(
            f"FEATURE_REJECT:DAILY_SIGNAL_INVENTORY:{signal_evaluations}:{expected_days}"
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
    terminal_liquidation_cost = raw["total_return"] - terminal_liquidation_return
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
            "terminal_liquidation_cost_pp": base.q(terminal_liquidation_cost),
            "fees_equity_units": base.q(fees),
            "turnover_equity_units": base.q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "high_risk_day_count": high_risk_days,
            "full_risk_day_count": full_risk_days,
            "regime_change_trade_count": regime_changes,
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
            "median_regime_target_exposure_pct": base.nullable(
                None
                if not observed_targets
                else (base.percentile(observed_targets, D("0.5")) or ZERO) * HUNDRED
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
            "terminal_liquidation_cost": terminal_liquidation_cost,
            "signal_evaluations": D(signal_evaluations),
            "high_risk_days": D(high_risk_days),
            "full_risk_days": D(full_risk_days),
            "regime_changes": D(regime_changes),
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
    bars: list[object], targets: dict[datetime, D], window: tuple[datetime, datetime],
    base: ModuleType, execution: ModuleType,
) -> tuple[dict[str, object], dict[str, dict[str, D]]]:
    outputs: dict[str, object] = {}
    raws: dict[str, dict[str, D]] = {}
    for name, (fee_rate, slippage) in SCENARIOS.items():
        outputs[name], raws[name] = simulate_scenario(
            bars, targets, window, fee_rate, slippage, base, execution
        )
    return outputs, raws


def breadth(
    raws: dict[str, dict[str, dict[str, D]]], base: ModuleType
) -> dict[str, object]:
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
    upside_at_least_half = sum(
        value["NORMAL"]["upside_capture"] >= D("0.5") for value in raws.values()
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
        "normal_upside_capture_at_least_50pct_years": upside_at_least_half,
        "top_year_positive_total_return_contribution_pct": base.q(top_year),
        "top_year_raw": top_year,
    }


def primary_gates(
    design: dict[str, dict[str, D]], validation: dict[str, dict[str, D]],
    annual: dict[str, object],
) -> dict[str, bool]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_70pct": dn["upside_capture"] >= D("0.70"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_85pct_of_buy_hold": vn["drawdown"] <= D("0.85") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_70pct": vn["upside_capture"] >= D("0.70"),
        "validation_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "validation_high_risk_days_at_least_60": vn["high_risk_days"] >= D("60"),
        "validation_full_risk_days_at_least_60": vn["full_risk_days"] >= D("60"),
        "validation_regime_changes_between_2_and_250": D("2") <= vn["regime_changes"] <= D("250"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_5": annual["normal_positive_years"] >= 4,
        "stress_positive_annual_return_at_least_4_of_5": annual["stress_positive_years"] >= 4,
        "annual_drawdown_non_worse_at_least_4_of_5": annual["normal_drawdown_non_worse_years"] >= 4,
        "annual_calmar_non_worse_at_least_3_of_5": annual["normal_calmar_non_worse_years"] >= 3,
        "annual_upside_capture_at_least_50pct_at_least_4_of_5": annual["normal_upside_capture_at_least_50pct_years"] >= 4,
        "top_year_positive_contribution_at_most_60pct": annual["top_year_raw"] <= D("60"),
        "validation_positive_realized_slice_present": vn["has_positive_realized_slice"] == ONE,
        "validation_top_positive_realized_lot_contribution_at_most_60pct": vn["has_positive_realized_slice"] == ONE and vn["top_positive_realized_contribution"] <= D("60"),
        "validation_p90_realized_lot_hold_at_most_17520_hours": vn["p90_hold"] <= D("17520"),
        "validation_terminal_oldest_lot_age_at_most_17520_hours": vn["terminal_oldest_age"] <= D("17520"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }


def neighbor_gates(
    design: dict[str, dict[str, D]], validation: dict[str, dict[str, D]],
    annual: dict[str, object],
) -> dict[str, bool]:
    dn, vn, vs = design["NORMAL"], validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_non_worse_than_buy_hold": vn["drawdown"] <= vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_65pct": vn["upside_capture"] >= D("0.65"),
        "validation_calmar_at_least_85pct_of_buy_hold": vn["calmar"] >= D("0.85") * vn["buy_hold_calmar"],
        "validation_both_regimes_observed_at_least_60_days": vn["high_risk_days"] >= D("60") and vn["full_risk_days"] >= D("60"),
        "normal_positive_annual_return_at_least_4_of_5": annual["normal_positive_years"] >= 4,
    }


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("primary") != {
        "lookback_days": 365,
        "high_risk_drawdown_threshold_pct": -20,
        "high_risk_relation": "CURRENT_DRAWDOWN_AT_OR_BELOW_NEGATIVE_THRESHOLD",
    }:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"lookback_days": 365, "high_risk_drawdown_threshold_pct": -15},
        {"lookback_days": 365, "high_risk_drawdown_threshold_pct": -25},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("rebalance_rule") != "ONLY_WHEN_REGIME_TARGET_CHANGES":
        raise ResearchReject("MANIFEST_REJECT:REBALANCE_RULE")
    if policy.get("high_risk_target") != "BTC_50_PERCENT_CASH_50_PERCENT":
        raise ResearchReject("MANIFEST_REJECT:HIGH_RISK_TARGET")
    if policy.get("variants") != 3 or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:VARIANTS_OR_OOS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    for path, expected in EXPECTED_SOURCE_HASHES.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{path}:{actual}")
    data_sha = sha256(input_path)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")

    parser = load_module("frozen_drawdown_h1_parser", PARSER_SOURCE)
    base = load_module("frozen_drawdown_economic_base", ECONOMIC_BASE_SOURCE)
    daily_support = load_module("frozen_drawdown_daily_support", DAILY_SUPPORT_SOURCE)
    execution = load_module("frozen_drawdown_fractional_execution", EXECUTION_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = daily_support.build_daily_points(bars)
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise ResearchReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(daily)}")

    variants: list[dict[str, object]] = []
    primary: dict[str, object] | None = None
    failed_neighbors: dict[str, list[str]] = {}
    points = build_drawdown_points(daily)
    for variant_id, role, threshold_pct in VARIANTS:
        targets = build_targets(points, threshold_pct)
        design_output, design_raw = simulate_window(
            bars, targets, DESIGN, base, execution
        )
        validation_output, validation_raw = simulate_window(
            bars, targets, VALIDATION, base, execution
        )
        annual_outputs = {
            year: simulate_window(bars, targets, window, base, execution)
            for year, window in ANNUAL.items()
        }
        annual_breadth = breadth(
            {year: value[1] for year, value in annual_outputs.items()}, base
        )
        gate_breadth = dict(annual_breadth)
        annual_breadth.pop("top_year_raw")
        available = points
        feature = {
            "lookback_days": LOOKBACK_DAYS,
            "high_risk_drawdown_threshold_pct": base.q(-threshold_pct),
            "available_observations": len(available),
            "first_effective_time": available[0].effective_time.isoformat(),
            "last_effective_time": available[-1].effective_time.isoformat(),
            "minimum": base.q(min(point.value for point in available)),
            "median": base.q(base.percentile([point.value for point in available], D("0.5")) or ZERO),
            "maximum": base.q(max(point.value for point in available)),
        }
        variant: dict[str, object] = {
            "variant_id": variant_id,
            "role": role,
            "lookback_days": LOOKBACK_DAYS,
            "high_risk_drawdown_threshold_pct": base.q(-threshold_pct),
            "feature": feature,
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {
                year: value[0] for year, value in annual_outputs.items()
            },
            "breadth_and_concentration": annual_breadth,
        }
        if role == "PRIMARY":
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
    failed_primary = [
        name for name, passed in primary["primary_gates"].items() if not passed
    ]
    passed = not failed_primary and not failed_neighbors
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_TRAILING365D_DRAWDOWN20_PASSIVE_CORE_RISK_OVERLAY_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "BTC_DAILY_TRAILING365D_DRAWDOWN20_PASSIVE_CORE_HISTORICAL_CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_DAILY_TRAILING365D_DRAWDOWN20_PASSIVE_CORE_RISK_OVERLAY_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_NEIGHBOR_GATES_PASS_SEALED_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_DAILY_TRAILING365D_DRAWDOWN15_20_25_100_50_PASSIVE_CORE_FAMILY_WITHOUT_TUNING"
        ),
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
            "sha256": data_sha,
            "hourly_rows": len(bars),
            "complete_utc_days": len(daily),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "source_bindings": {
            path.relative_to(REPO_ROOT).as_posix(): digest
            for path, digest in EXPECTED_SOURCE_HASHES.items()
        },
        "policy": {
            "drawdown_formula": "LATEST_CLOSE_DIVIDED_BY_MAXIMUM_CLOSE_OF_LATEST_365_COMPLETE_UTC_DAYS_MINUS_ONE",
            "primary_high_risk_drawdown_threshold_pct": -20,
            "rejection_only_neighbor_high_risk_drawdown_thresholds_pct": [-15, -25],
            "low_risk_target": "BTC_100_PERCENT",
            "high_risk_target": "BTC_50_PERCENT_CASH_50_PERCENT",
            "rebalance": "ONLY_ON_REGIME_TARGET_CHANGE_AT_NEXT_H1_OPEN",
            "variants": 3,
        },
        "variants": variants,
        "failed_primary_gates": failed_primary,
        "failed_neighbor_gates": failed_neighbors,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass requires separately sealed independent OOS and never authorizes activation.",
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
