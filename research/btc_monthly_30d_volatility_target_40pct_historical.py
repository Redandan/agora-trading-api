#!/usr/bin/env python3
"""Deterministic historical screen for monthly unlevered BTC volatility targeting."""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 50

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
EPSILON = D("1e-28")
TARGET_VOLATILITY = D("0.40")
ANNUALIZATION_DAYS = D("365")

REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_RUNNER_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "dra-volatility-management-primary-prior-audit.v4.json"
)
EXPERIMENT_ID = "btc-monthly-30d-volatility-target-40pct-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_MONTHLY_30D_VOLATILITY_TARGET_40PCT_HISTORICAL_MANIFEST_V1"
)
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_BASE_RUNNER_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_PRIOR_SHA256 = "1d94c0af9e6bdabbda34862cd6740eef9d77932a77183a027ac75246435427d3"
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


def load_module(name: str, source: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, source)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{source}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def is_day_end_bar(bar: object) -> bool:
    return (
        bar.open_time.date() != bar.close_time.date()
        and bar.close_time.hour == 0
        and bar.close_time.minute == 0
        and bar.close_time.second == 0
    )


def is_month_boundary_bar(bar: object) -> bool:
    return is_day_end_bar(bar) and bar.close_time.day == 1


def validate_time_inventory(bars: list[object]) -> tuple[list[object], list[object]]:
    daily = [bar for bar in bars if is_day_end_bar(bar)]
    monthly = [bar for bar in daily if is_month_boundary_bar(bar)]
    if (
        len(daily) != 2_192
        or daily[0].close_time != datetime(2019, 1, 2)
        or daily[-1].close_time != datetime(2025, 1, 1)
    ):
        raise ResearchReject(
            f"DATA_REJECT:DAILY_INVENTORY:{len(daily)}:"
            f"{daily[0].close_time if daily else None}:"
            f"{daily[-1].close_time if daily else None}"
        )
    if (
        len(monthly) != 72
        or monthly[0].close_time != datetime(2019, 2, 1)
        or monthly[-1].close_time != datetime(2025, 1, 1)
    ):
        raise ResearchReject(
            f"DATA_REJECT:MONTHLY_INVENTORY:{len(monthly)}:"
            f"{monthly[0].close_time if monthly else None}:"
            f"{monthly[-1].close_time if monthly else None}"
        )
    for previous, current in zip(daily, daily[1:]):
        if current.close_time - previous.close_time != timedelta(days=1):
            raise ResearchReject("DATA_REJECT:DAILY_LATTICE")
    return daily, monthly


def realized_volatility(daily_closes: list[D]) -> D:
    if len(daily_closes) != 31 or any(value <= ZERO for value in daily_closes):
        raise ResearchReject("FEATURE_REJECT:THIRTY_DAY_CLOSE_WINDOW")
    squared = ZERO
    for previous, current in zip(daily_closes, daily_closes[1:]):
        value = (current / previous).ln()
        squared += value * value
    return (ANNUALIZATION_DAYS * squared / D("30")).sqrt()


@dataclass
class Lot:
    quantity: D
    cost_basis: D
    entry_time: datetime


def total_quantity(lots: list[Lot]) -> D:
    return sum((lot.quantity for lot in lots), ZERO)


def execute_target(
    *,
    lots: list[Lot],
    cash: D,
    target_weight: D,
    open_price: D,
    fee_rate: D,
    slippage: D,
    execution_time: datetime,
) -> tuple[D, D, D, D, str | None, list[D], list[D]]:
    quantity = total_quantity(lots)
    pretrade_equity = cash + quantity * open_price
    if pretrade_equity <= ZERO or not (ZERO <= target_weight <= ONE):
        raise ResearchReject("ECONOMIC_REJECT:INVALID_PRETRADE_STATE")
    desired_quantity = pretrade_equity * target_weight / open_price
    fees = ZERO
    turnover = ZERO
    realized = ZERO
    trade_side: str | None = None
    realized_slices: list[D] = []
    holding_hours: list[D] = []

    if desired_quantity > quantity + EPSILON:
        fill = open_price * (ONE + slippage)
        maximum_add = cash / (fill * (ONE + fee_rate))
        added = min(desired_quantity - quantity, maximum_add)
        if added > EPSILON:
            gross = added * fill
            fee = gross * fee_rate
            cash -= gross + fee
            if -EPSILON < cash < ZERO:
                cash = ZERO
            if cash < ZERO:
                raise ResearchReject("ECONOMIC_REJECT:NEGATIVE_CASH_AFTER_BUY")
            lots.append(Lot(added, gross + fee, execution_time))
            fees += fee
            turnover += gross
            trade_side = "BUY"
    elif desired_quantity + EPSILON < quantity:
        remaining = quantity - desired_quantity
        fill = open_price * (ONE - slippage)
        original_sell_quantity = remaining
        gross = original_sell_quantity * fill
        fee = gross * fee_rate
        cash += gross - fee
        fees += fee
        turnover += gross
        trade_side = "SELL"
        while remaining > EPSILON:
            if not lots:
                raise ResearchReject("ECONOMIC_REJECT:FIFO_UNDERFLOW")
            lot = lots[0]
            sold = min(remaining, lot.quantity)
            fraction = sold / lot.quantity
            allocated_basis = lot.cost_basis * fraction
            net_proceeds = sold * fill * (ONE - fee_rate)
            pnl = net_proceeds - allocated_basis
            realized += pnl
            realized_slices.append(pnl)
            holding_hours.append(
                D(str((execution_time - lot.entry_time).total_seconds() / 3600))
            )
            lot.quantity -= sold
            lot.cost_basis -= allocated_basis
            remaining -= sold
            if lot.quantity <= EPSILON:
                lots.pop(0)
        if remaining > EPSILON:
            raise ResearchReject("ECONOMIC_REJECT:FIFO_REMAINDER")

    return cash, fees, turnover, realized, trade_side, realized_slices, holding_hours


def simulate_scenario(
    bars: list[object],
    window: tuple[datetime, datetime],
    fee_rate: D,
    slippage: D,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    warmup_start = start - timedelta(days=40)
    selected = [
        bar for bar in bars if bar.close_time > warmup_start and bar.close_time <= end
    ]
    trading = [bar for bar in selected if start <= bar.open_time < end]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    daily_closes: list[D] = []
    pending_target: D | None = None
    cash = ONE
    lots: list[Lot] = []
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_evaluations = 0
    rebalance_trades = 0
    buy_trades = 0
    sell_trades = 0
    target_weights: list[D] = []
    realized_volatilities: list[D] = []
    realized_slices: list[D] = []
    holding_hours: list[D] = []
    path = base.PathAccumulator()
    final_equity = ONE

    for bar in selected:
        in_window = start <= bar.open_time < end
        if in_window and pending_target is not None:
            (
                cash,
                trade_fees,
                trade_turnover,
                trade_realized,
                trade_side,
                trade_slices,
                trade_holds,
            ) = execute_target(
                lots=lots,
                cash=cash,
                target_weight=pending_target,
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
            pending_target = None

        if in_window:
            quantity = total_quantity(lots)
            market_value = quantity * bar.close
            final_equity = cash + market_value
            path.observe(
                final_equity,
                market_value / final_equity if final_equity > ZERO else ZERO,
            )

        if is_day_end_bar(bar):
            daily_closes.append(bar.close)
            if is_month_boundary_bar(bar) and start <= bar.close_time < end:
                if len(daily_closes) < 31:
                    raise ResearchReject("FEATURE_REJECT:INSUFFICIENT_WARMUP")
                volatility = realized_volatility(daily_closes[-31:])
                pending_target = (
                    ONE
                    if volatility == ZERO
                    else min(ONE, TARGET_VOLATILITY / volatility)
                )
                realized_volatilities.append(volatility)
                target_weights.append(pending_target)
                signal_evaluations += 1

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    quantity = total_quantity(lots)
    terminal_net = ZERO
    if quantity > ZERO:
        terminal_net = quantity * trading[-1].close * (ONE - slippage) * (
            ONE - fee_rate
        )
    terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (terminal_liquidation_equity - ONE) * HUNDRED
    positive_slices = [value for value in realized_slices if value > ZERO]
    terminal_oldest_age = (
        ZERO
        if not lots
        else D(str((end - min(lot.entry_time for lot in lots)).total_seconds() / 3600))
    )
    median_target = base.percentile(target_weights, D("0.5"))
    median_volatility = base.percentile(realized_volatilities, D("0.5"))
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
                None if median_target is None else median_target * HUNDRED
            ),
            "median_annualized_realized_volatility_pct": base.nullable(
                None if median_volatility is None else median_volatility * HUNDRED
            ),
            "top_positive_realized_lot_contribution_pct": (
                None
                if not positive_slices
                else base.q(max(positive_slices) / sum(positive_slices, ZERO) * HUNDRED)
            ),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"]
            - terminal_liquidation_return,
            "signal_evaluations": D(signal_evaluations),
            "rebalance_trades": D(rebalance_trades),
            "average_exposure": D(candidate["average_exposure_pct"]),
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
    candidate_calmar = raw["calmar"]
    benchmark_calmar = benchmark_raw["calmar"]
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
                candidate_calmar / benchmark_calmar
                if benchmark_calmar != ZERO
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
    bars: list[object], window: tuple[datetime, datetime], base: ModuleType
) -> tuple[dict[str, object], dict[str, dict[str, D]]]:
    outputs: dict[str, object] = {}
    raws: dict[str, dict[str, D]] = {}
    for name, (fee_rate, slippage) in SCENARIOS.items():
        outputs[name], raws[name] = simulate_scenario(
            bars, window, fee_rate, slippage, base
        )
    return outputs, raws


def evaluate_gates(
    validation_output: dict[str, object],
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, object], dict[str, dict[str, D]]]],
    base: ModuleType,
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_ohlcv_invariants_pass": True,
        "frozen_source_binding_sha256s_match": True,
        "complete_daily_close_inventory_and_month_boundary_signals_match": True,
        "design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "design_normal_maximum_drawdown_at_most_70pct_of_buy_hold": dn["drawdown"]
        <= D("0.70") * dn["buy_hold_drawdown"],
        "design_normal_upside_capture_at_least_35pct": dn["upside_capture"]
        >= D("0.35"),
        "design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "design_normal_average_exposure_between_25pct_and_85pct": D("25")
        <= dn["average_exposure"]
        <= D("85"),
        "design_normal_signal_evaluations_exactly_36": dn["signal_evaluations"]
        == D("36"),
        "design_normal_rebalance_trades_at_least_24": dn["rebalance_trades"]
        >= D("24"),
        "validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "validation_normal_maximum_drawdown_at_most_80pct_of_buy_hold": vn[
            "drawdown"
        ]
        <= D("0.80") * vn["buy_hold_drawdown"],
        "validation_normal_upside_capture_at_least_35pct": vn["upside_capture"]
        >= D("0.35"),
        "validation_normal_calmar_at_least_buy_hold": vn["calmar"]
        >= vn["buy_hold_calmar"],
        "validation_normal_average_exposure_between_25pct_and_85pct": D("25")
        <= vn["average_exposure"]
        <= D("85"),
        "validation_normal_signal_evaluations_exactly_24": vn["signal_evaluations"]
        == D("24"),
        "validation_normal_rebalance_trades_at_least_16": vn["rebalance_trades"]
        >= D("16"),
        "validation_stress_maximum_drawdown_no_more_than_normal_plus_3pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("3"),
    }

    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(
        value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values()
    )
    stress_positive = sum(
        value["STRESS"]["total_return"] > ZERO for value in annual_raw.values()
    )
    drawdown_nonworse = sum(
        value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"]
        for value in annual_raw.values()
    )
    calmar_breadth = sum(
        value["NORMAL"]["calmar"]
        >= D("0.75") * value["NORMAL"]["buy_hold_calmar"]
        for value in annual_raw.values()
    )
    positive_year_returns = [
        max(value["NORMAL"]["total_return"], ZERO)
        for value in annual_raw.values()
    ]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = (
        max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED
        if positive_sum > ZERO
        else HUNDRED
    )
    top_realized = vn["top_positive_realized_contribution"]
    gates.update(
        {
            "normal_positive_annual_total_return_at_least_4_of_5": normal_positive
            >= 4,
            "stress_positive_annual_total_return_at_least_4_of_5": stress_positive
            >= 4,
            "normal_annual_drawdown_non_worse_than_buy_hold_5_of_5": drawdown_nonworse
            == 5,
            "normal_annual_calmar_at_least_75pct_of_buy_hold_at_least_4_of_5": calmar_breadth
            >= 4,
            "top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "top_positive_realized_lot_contribution_at_most_60pct_when_positive_sales_exist": (
                vn["has_positive_realized_slice"] == ZERO
                or top_realized <= D("60")
            ),
            "validation_normal_p90_realized_lot_hold_at_most_17520_hours_when_sales_exist": vn[
                "p90_hold"
            ]
            <= D("17520"),
            "validation_normal_terminal_oldest_lot_age_at_most_17520_hours_when_position_open": vn[
                "terminal_oldest_age"
            ]
            <= D("17520"),
            "validation_normal_terminal_liquidation_adjusted_return_pct_gt_0": vn[
                "terminal_liquidation_return"
            ]
            > ZERO,
            "validation_normal_terminal_liquidation_cost_at_most_1pp_of_mark_to_market_return": vn[
                "terminal_liquidation_cost"
            ]
            <= ONE,
        }
    )
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_5",
        "stress_positive_years": f"{stress_positive}_of_5",
        "normal_drawdown_non_worse_than_buy_hold_years": f"{drawdown_nonworse}_of_5",
        "normal_calmar_at_least_75pct_of_buy_hold_years": f"{calmar_breadth}_of_5",
        "top_year_positive_total_return_contribution_pct": base.q(top_year),
        "validation_top_positive_realized_lot_contribution_pct": validation_output[
            "NORMAL"
        ]["candidate"]["top_positive_realized_lot_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("variants") != 1:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    if policy.get("target_exposure") != "MINIMUM_OF_ONE_AND_0_40_DIVIDED_BY_DECISION_FEATURE":
        raise ResearchReject("MANIFEST_REJECT:TARGET_EXPOSURE")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    base_sha = _sha256(BASE_RUNNER_SOURCE)
    parser_sha = _sha256(PARSER_SOURCE)
    prior_sha = _sha256(PRIOR_SOURCE)
    data_sha = _sha256(input_path)
    if base_sha != EXPECTED_BASE_RUNNER_SHA256:
        raise ResearchReject(f"SOURCE_REJECT:BASE_RUNNER_SHA256:{base_sha}")
    if parser_sha != EXPECTED_PARSER_SHA256:
        raise ResearchReject(f"SOURCE_REJECT:PARSER_SHA256:{parser_sha}")
    if prior_sha != EXPECTED_PRIOR_SHA256:
        raise ResearchReject(f"SOURCE_REJECT:PRIOR_SHA256:{prior_sha}")
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    base = load_module("frozen_monthly_economic_reference", BASE_RUNNER_SOURCE)
    parser = load_module("frozen_volatility_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily, monthly = validate_time_inventory(bars)

    design_output, design_raw = simulate_window(bars, DESIGN, base)
    validation_output, validation_raw = simulate_window(bars, VALIDATION, base)
    annual = {
        year: simulate_window(bars, window, base) for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        validation_output, design_raw, validation_raw, annual, base
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_MONTHLY_30D_VOLATILITY_TARGET_40PCT_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_MONTHLY_30D_VOLATILITY_TARGET_40PCT_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_MONTHLY_30D_VOLATILITY_TARGET_40PCT_FAMILY_WITHOUT_TUNING"
        ),
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": _sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": _sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": data_sha,
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
            "complete_day_count": len(daily),
            "month_boundary_count": len(monthly),
            "first_complete_day_close": daily[0].close_time.isoformat(),
            "last_complete_day_close": daily[-1].close_time.isoformat(),
        },
        "source_bindings": {
            "frozen_h1_parser_sha256": parser_sha,
            "frozen_passive_and_path_reference_sha256": base_sha,
            "sealed_mechanism_prior_sha256": prior_sha,
        },
        "policy": {
            "lookback_complete_daily_log_returns": 30,
            "annualized_volatility_target": "0.40",
            "exposure_cap": "1.00",
            "rebalance": "UTC_MONTH_BOUNDARY_NEXT_HOURLY_OPEN",
            "cost_basis": "FIFO",
            "variants": 1,
        },
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": (
            "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority, evidence for another risk target or a reopening of DRA volatility admission."
        ),
        "scope_note": (
            "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
        ),
    }


def _sha256(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes()).hexdigest()


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
                "sha256": _sha256(output_path),
                "failed_gates": result["failed_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
