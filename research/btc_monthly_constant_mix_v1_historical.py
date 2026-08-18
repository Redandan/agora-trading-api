#!/usr/bin/env python3
"""Deterministic matched-capital screen for monthly BTC/cash constant mix."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timedelta
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 50

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
TARGETS = {
    "W40": D("0.40"),
    "W50": D("0.50"),
    "W60": D("0.60"),
}
PRIMARY_VARIANT = "W50"
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    "2020": (datetime(2020, 1, 1), datetime(2021, 1, 1)),
    "2021": (datetime(2021, 1, 1), datetime(2022, 1, 1)),
    "2022": (datetime(2022, 1, 1), datetime(2023, 1, 1)),
    "2023": (datetime(2023, 1, 1), datetime(2024, 1, 1)),
    "2024": (datetime(2024, 1, 1), datetime(2025, 1, 1)),
}

REPO_ROOT = Path(__file__).resolve().parents[1]
ENGINE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_30d_volatility_target_40pct_historical.py"
)
BASE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-constant-mix-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-constant-mix-v1.hypothesis.json"
)
EXPERIMENT_ID = "btc-monthly-constant-mix-v1-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_MONTHLY_CONSTANT_MIX_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_ENGINE_SHA256 = "8eb185644904b62152feb9170964fa86032ee561680a1ba92786746dc9a466d6"
EXPECTED_BASE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "f211b62623a20fbf706d2b719967feb7e9f436b7afe0e342b3adf47327daed3c"
EXPECTED_HYPOTHESIS_SHA256 = "a4a3034823a9df6bf31888816fd3e238dffc25c4a06649d3ead54bdc55f08aa6"


class ResearchReject(RuntimeError):
    pass


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


def validate_source_bindings(input_path: Path) -> dict[str, str]:
    bindings = {
        "engine": sha256(ENGINE_SOURCE),
        "base": sha256(BASE_SOURCE),
        "parser": sha256(PARSER_SOURCE),
        "prior": sha256(PRIOR_SOURCE),
        "hypothesis": sha256(HYPOTHESIS_SOURCE),
        "data": sha256(input_path),
    }
    expected = {
        "engine": EXPECTED_ENGINE_SHA256,
        "base": EXPECTED_BASE_SHA256,
        "parser": EXPECTED_PARSER_SHA256,
        "prior": EXPECTED_PRIOR_SHA256,
        "hypothesis": EXPECTED_HYPOTHESIS_SHA256,
        "data": EXPECTED_DATA_SHA256,
    }
    for name, actual in bindings.items():
        if actual != expected[name]:
            prefix = "DATA_REJECT" if name == "data" else "SOURCE_REJECT"
            raise ResearchReject(f"{prefix}:{name.upper()}_SHA256:{actual}")
    return bindings


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    if policy.get("primary_target_weight") != "0.50":
        raise ResearchReject("MANIFEST_REJECT:PRIMARY_TARGET_WEIGHT")
    if policy.get("neighbor_target_weights") != ["0.40", "0.60"]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBOR_TARGET_WEIGHTS")
    if policy.get("rebalance") != "UTC_MONTH_BOUNDARY_NEXT_HOURLY_OPEN":
        raise ResearchReject("MANIFEST_REJECT:REBALANCE")


def validate_inventory(bars: list[object], engine: ModuleType) -> tuple[int, int]:
    daily, monthly = engine.validate_time_inventory(bars)
    return len(daily), len(monthly)


def simulate_policy(
    *,
    bars: list[object],
    window: tuple[datetime, datetime],
    target: D,
    rebalance: bool,
    fee_rate: D,
    slippage: D,
    engine: ModuleType,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    selected = [
        bar
        for bar in bars
        if bar.close_time > start - timedelta(hours=1) and bar.close_time <= end
    ]
    trading = [bar for bar in selected if start <= bar.open_time < end]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    cash = ONE
    lots: list[object] = []
    pending_target: D | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_evaluations = 0
    trade_count = 0
    buy_count = 0
    sell_count = 0
    blocked_entries = 0
    realized_slices: list[D] = []
    holding_hours: list[D] = []
    weight_error_sum = ZERO
    maximum_weight_error = ZERO
    final_weight = ZERO
    final_equity = ONE
    path = base.PathAccumulator()

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
            ) = engine.execute_target(
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
                trade_count += 1
                buy_count += int(trade_side == "BUY")
                sell_count += int(trade_side == "SELL")
            pending_target = None

        if in_window:
            quantity = engine.total_quantity(lots)
            market_value = quantity * bar.close
            final_equity = cash + market_value
            if final_equity <= ZERO:
                raise ResearchReject("ECONOMIC_REJECT:NON_POSITIVE_EQUITY")
            final_weight = market_value / final_equity
            error = abs(final_weight - target)
            weight_error_sum += error
            maximum_weight_error = max(maximum_weight_error, error)
            path.observe(final_equity, final_weight)

        if engine.is_month_boundary_bar(bar) and start <= bar.close_time < end:
            if rebalance or signal_evaluations == 0:
                pending_target = target
                signal_evaluations += 1

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    quantity = engine.total_quantity(lots)
    terminal_net = quantity * trading[-1].close * (ONE - slippage) * (ONE - fee_rate)
    terminal_equity = cash + terminal_net
    terminal_return = (terminal_equity - ONE) * HUNDRED
    positive_slices = [value for value in realized_slices if value > ZERO]
    terminal_oldest_age = (
        ZERO
        if not lots
        else D(str((end - min(lot.entry_time for lot in lots)).total_seconds() / 3600))
    )
    average_weight_error = weight_error_sum / D(path.observations) * HUNDRED
    terminal_cost_basis = sum((lot.cost_basis for lot in lots), ZERO)
    candidate.update(
        {
            "target_weight_pct": base.q(target * HUNDRED),
            "realized_return_pct": base.q(realized * HUNDRED),
            "unrealized_return_pct": base.q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": base.q(terminal_return),
            "terminal_liquidation_cost_pp": base.q(raw["total_return"] - terminal_return),
            "fees_equity_units": base.q(fees),
            "turnover_equity_units": base.q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "trade_count": trade_count,
            "buy_trade_count": buy_count,
            "sell_trade_count": sell_count,
            "blocked_entry_count": blocked_entries,
            "realized_lot_slice_count": len(realized_slices),
            "median_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.5"))
            ),
            "p90_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.9"))
            ),
            "average_absolute_weight_error_pp": base.q(average_weight_error),
            "maximum_absolute_weight_error_pp": base.q(maximum_weight_error * HUNDRED),
            "terminal_btc_weight_pct": base.q(final_weight * HUNDRED),
            "terminal_position": quantity > ZERO,
            "terminal_oldest_lot_age_hours": None if not lots else base.q(terminal_oldest_age),
            "terminal_inventory_cost_basis": base.q(terminal_cost_basis),
            "top_positive_realized_lot_contribution_pct": (
                None
                if not positive_slices
                else base.q(max(positive_slices) / sum(positive_slices, ZERO) * HUNDRED)
            ),
        }
    )
    raw.update(
        {
            "realized_return": realized * HUNDRED,
            "unrealized_return": unrealized * HUNDRED,
            "terminal_liquidation_return": terminal_return,
            "terminal_liquidation_cost": raw["total_return"] - terminal_return,
            "signal_evaluations": D(signal_evaluations),
            "trade_count": D(trade_count),
            "blocked_entries": D(blocked_entries),
            "average_weight_error": average_weight_error,
            "terminal_oldest_age": terminal_oldest_age,
            "p90_hold": base.percentile(holding_hours, D("0.9")) or ZERO,
            "top_positive_realized_contribution": (
                ZERO
                if not positive_slices
                else max(positive_slices) / sum(positive_slices, ZERO) * HUNDRED
            ),
            "has_positive_realized_slice": ONE if positive_slices else ZERO,
        }
    )
    return candidate, raw


def simulate_variant(
    *,
    bars: list[object],
    window: tuple[datetime, datetime],
    target: D,
    fee_rate: D,
    slippage: D,
    engine: ModuleType,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    candidate, candidate_raw = simulate_policy(
        bars=bars,
        window=window,
        target=target,
        rebalance=True,
        fee_rate=fee_rate,
        slippage=slippage,
        engine=engine,
        base=base,
    )
    primary, primary_raw = simulate_policy(
        bars=bars,
        window=window,
        target=target,
        rebalance=False,
        fee_rate=fee_rate,
        slippage=slippage,
        engine=engine,
        base=base,
    )
    trading = [bar for bar in bars if start <= bar.open_time < end]
    passive, passive_raw = base.passive_benchmark(trading, fee_rate, slippage)
    incremental_return = candidate_raw["total_return"] - primary_raw["total_return"]
    incremental_drawdown = candidate_raw["drawdown"] - primary_raw["drawdown"]
    terminal_incremental = (
        candidate_raw["terminal_liquidation_return"]
        - primary_raw["terminal_liquidation_return"]
    )
    return {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate_monthly_constant_mix": candidate,
        "primary_same_initial_weight_no_rebalance": primary,
        "secondary_full_passive_btc": passive,
        "comparison": {
            "candidate_minus_primary_total_return_pp": base.q(incremental_return),
            "candidate_minus_primary_maximum_drawdown_pp": base.q(incremental_drawdown),
            "candidate_minus_primary_terminal_liquidation_return_pp": base.q(
                terminal_incremental
            ),
            "candidate_calmar_ratio_to_primary": base.nullable(
                candidate_raw["calmar"] / primary_raw["calmar"]
                if primary_raw["calmar"] != ZERO
                else None
            ),
            "candidate_upside_capture_vs_full_passive": base.nullable(
                candidate_raw["total_return"] / passive_raw["total_return"]
                if passive_raw["total_return"] > ZERO
                else None
            ),
        },
    }, {
        "candidate_return": candidate_raw["total_return"],
        "candidate_drawdown": candidate_raw["drawdown"],
        "candidate_calmar": candidate_raw["calmar"],
        "candidate_terminal_return": candidate_raw["terminal_liquidation_return"],
        "candidate_terminal_cost": candidate_raw["terminal_liquidation_cost"],
        "candidate_signal_evaluations": candidate_raw["signal_evaluations"],
        "candidate_trade_count": candidate_raw["trade_count"],
        "candidate_blocked_entries": candidate_raw["blocked_entries"],
        "candidate_average_weight_error": candidate_raw["average_weight_error"],
        "candidate_terminal_oldest_age": candidate_raw["terminal_oldest_age"],
        "candidate_p90_hold": candidate_raw["p90_hold"],
        "candidate_top_positive_realized_contribution": candidate_raw[
            "top_positive_realized_contribution"
        ],
        "candidate_has_positive_realized_slice": candidate_raw[
            "has_positive_realized_slice"
        ],
        "primary_return": primary_raw["total_return"],
        "primary_drawdown": primary_raw["drawdown"],
        "primary_calmar": primary_raw["calmar"],
        "primary_terminal_return": primary_raw["terminal_liquidation_return"],
        "primary_signal_evaluations": primary_raw["signal_evaluations"],
        "primary_trade_count": primary_raw["trade_count"],
        "passive_return": passive_raw["total_return"],
        "passive_drawdown": passive_raw["drawdown"],
        "incremental_return": incremental_return,
        "incremental_drawdown": incremental_drawdown,
        "terminal_incremental_return": terminal_incremental,
    }


def simulate_window(
    bars: list[object],
    window: tuple[datetime, datetime],
    engine: ModuleType,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, object] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for scenario, (fee_rate, slippage) in SCENARIOS.items():
        output[scenario] = {}
        raw[scenario] = {}
        for label, target in TARGETS.items():
            variant_output, variant_raw = simulate_variant(
                bars=bars,
                window=window,
                target=target,
                fee_rate=fee_rate,
                slippage=slippage,
                engine=engine,
                base=base,
            )
            output[scenario][label] = variant_output
            raw[scenario][label] = variant_raw
    return output, raw


def evaluate_gates(
    validation_output: dict[str, object],
    design: dict[str, dict[str, dict[str, D]]],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[
        str,
        tuple[
            dict[str, object],
            dict[str, dict[str, dict[str, D]]],
        ],
    ],
    base: ModuleType,
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    dn = design["NORMAL"][PRIMARY_VARIANT]
    ds = design["STRESS"][PRIMARY_VARIANT]
    vn = validation["NORMAL"][PRIMARY_VARIANT]
    vs = validation["STRESS"][PRIMARY_VARIANT]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_ohlcv_daily_and_monthly_inventory_pass": True,
        "frozen_engine_base_parser_prior_and_hypothesis_sha256s_match": True,
        "design_primary_normal_candidate_total_return_pct_gt_0": dn["candidate_return"] > ZERO,
        "design_primary_stress_candidate_total_return_pct_gt_0": ds["candidate_return"] > ZERO,
        "design_primary_normal_incremental_total_return_pp_gt_0": dn["incremental_return"] > ZERO,
        "design_primary_stress_incremental_total_return_pp_gt_0": ds["incremental_return"] > ZERO,
        "design_primary_normal_drawdown_non_worse_than_matched_static": dn[
            "candidate_drawdown"
        ]
        <= dn["primary_drawdown"],
        "design_primary_normal_calmar_at_least_matched_static": dn["candidate_calmar"]
        >= dn["primary_calmar"],
        "design_primary_average_absolute_weight_error_at_most_5pp": dn[
            "candidate_average_weight_error"
        ]
        <= D("5"),
        "design_primary_signal_evaluations_exactly_36": dn[
            "candidate_signal_evaluations"
        ]
        == D("36"),
        "design_primary_trade_count_at_least_30": dn["candidate_trade_count"]
        >= D("30"),
        "design_matched_static_executes_exactly_one_initial_trade": dn[
            "primary_signal_evaluations"
        ]
        == ONE
        and dn["primary_trade_count"] == ONE,
        "validation_primary_normal_candidate_total_return_pct_gt_0": vn[
            "candidate_return"
        ]
        > ZERO,
        "validation_primary_stress_candidate_total_return_pct_gt_0": vs[
            "candidate_return"
        ]
        > ZERO,
        "validation_primary_normal_incremental_total_return_pp_gt_0": vn[
            "incremental_return"
        ]
        > ZERO,
        "validation_primary_stress_incremental_total_return_pp_gt_0": vs[
            "incremental_return"
        ]
        > ZERO,
        "validation_primary_normal_drawdown_non_worse_than_matched_static": vn[
            "candidate_drawdown"
        ]
        <= vn["primary_drawdown"],
        "validation_primary_normal_calmar_at_least_matched_static": vn[
            "candidate_calmar"
        ]
        >= vn["primary_calmar"],
        "validation_primary_average_absolute_weight_error_at_most_5pp": vn[
            "candidate_average_weight_error"
        ]
        <= D("5"),
        "validation_primary_signal_evaluations_exactly_24": vn[
            "candidate_signal_evaluations"
        ]
        == D("24"),
        "validation_primary_trade_count_at_least_20": vn["candidate_trade_count"]
        >= D("20"),
        "validation_primary_stress_drawdown_no_more_than_normal_plus_2pp": vs[
            "candidate_drawdown"
        ]
        <= vn["candidate_drawdown"] + D("2"),
        "validation_primary_normal_terminal_liquidation_incremental_return_pp_gt_0": vn[
            "terminal_incremental_return"
        ]
        > ZERO,
        "validation_primary_terminal_liquidation_cost_at_most_1pp": vn[
            "candidate_terminal_cost"
        ]
        <= ONE,
        "validation_primary_blocked_entries_exactly_0": vn["candidate_blocked_entries"]
        == ZERO,
        "validation_primary_p90_realized_lot_hold_at_most_17520_hours_when_sales_exist": vn[
            "candidate_p90_hold"
        ]
        <= D("17520"),
        "validation_primary_terminal_oldest_lot_age_at_most_17520_hours": vn[
            "candidate_terminal_oldest_age"
        ]
        <= D("17520"),
    }

    for label in ("W40", "W60"):
        gates.update(
            {
                f"neighbor_{label.lower()}_design_normal_incremental_return_gt_0": design[
                    "NORMAL"
                ][label]["incremental_return"]
                > ZERO,
                f"neighbor_{label.lower()}_design_stress_incremental_return_gt_0": design[
                    "STRESS"
                ][label]["incremental_return"]
                > ZERO,
                f"neighbor_{label.lower()}_validation_normal_incremental_return_gt_0": validation[
                    "NORMAL"
                ][label]["incremental_return"]
                > ZERO,
                f"neighbor_{label.lower()}_validation_stress_incremental_return_gt_0": validation[
                    "STRESS"
                ][label]["incremental_return"]
                > ZERO,
                f"neighbor_{label.lower()}_validation_drawdown_non_worse_than_own_static": validation[
                    "NORMAL"
                ][label]["candidate_drawdown"]
                <= validation["NORMAL"][label]["primary_drawdown"],
            }
        )

    annual_raw = {year: value[1]["NORMAL"][PRIMARY_VARIANT] for year, value in annual.items()}
    annual_stress = {
        year: value[1]["STRESS"][PRIMARY_VARIANT] for year, value in annual.items()
    }
    positive_years = sum(value["candidate_return"] > ZERO for value in annual_raw.values())
    stress_positive_years = sum(
        value["candidate_return"] > ZERO for value in annual_stress.values()
    )
    incremental_win_years = sum(
        value["incremental_return"] > ZERO for value in annual_raw.values()
    )
    stress_incremental_win_years = sum(
        value["incremental_return"] > ZERO for value in annual_stress.values()
    )
    drawdown_nonworse_years = sum(
        value["candidate_drawdown"] <= value["primary_drawdown"]
        for value in annual_raw.values()
    )
    calmar_nonworse_years = sum(
        value["candidate_calmar"] >= value["primary_calmar"]
        for value in annual_raw.values()
    )
    positive_incremental = [
        max(value["incremental_return"], ZERO) for value in annual_raw.values()
    ]
    positive_incremental_sum = sum(positive_incremental, ZERO)
    top_year_incremental = (
        max(positive_incremental, default=ZERO)
        / positive_incremental_sum
        * HUNDRED
        if positive_incremental_sum > ZERO
        else HUNDRED
    )
    top_realized = vn["candidate_top_positive_realized_contribution"]
    gates.update(
        {
            "primary_normal_positive_annual_total_return_at_least_4_of_5": positive_years
            >= 4,
            "primary_stress_positive_annual_total_return_at_least_4_of_5": stress_positive_years
            >= 4,
            "primary_normal_incremental_total_return_win_at_least_4_of_5_years": incremental_win_years
            >= 4,
            "primary_stress_incremental_total_return_win_at_least_4_of_5_years": stress_incremental_win_years
            >= 4,
            "primary_normal_drawdown_non_worse_at_least_4_of_5_years": drawdown_nonworse_years
            >= 4,
            "primary_normal_calmar_non_worse_at_least_4_of_5_years": calmar_nonworse_years
            >= 4,
            "top_year_positive_incremental_return_contribution_at_most_60pct": top_year_incremental
            <= D("60"),
            "validation_top_positive_realized_lot_contribution_at_most_60pct": (
                vn["candidate_has_positive_realized_slice"] == ZERO
                or top_realized <= D("60")
            ),
        }
    )
    breadth = {
        "primary_normal_positive_years": f"{positive_years}_of_5",
        "primary_stress_positive_years": f"{stress_positive_years}_of_5",
        "primary_normal_incremental_return_win_years": f"{incremental_win_years}_of_5",
        "primary_stress_incremental_return_win_years": f"{stress_incremental_win_years}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse_years}_of_5",
        "primary_normal_calmar_non_worse_years": f"{calmar_nonworse_years}_of_5",
        "top_year_positive_incremental_return_contribution_pct": base.q(
            top_year_incremental
        ),
        "validation_top_positive_realized_lot_contribution_pct": validation_output[
            "NORMAL"
        ][PRIMARY_VARIANT]["candidate_monthly_constant_mix"][
            "top_positive_realized_lot_contribution_pct"
        ],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    bindings = validate_source_bindings(input_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    engine = load_module("frozen_monthly_partial_rebalance_engine", ENGINE_SOURCE)
    base = load_module("frozen_monthly_path_reference", BASE_SOURCE)
    parser = load_module("frozen_constant_mix_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != bindings["data"]:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily_count, monthly_count = validate_inventory(bars, engine)

    design_output, design_raw = simulate_window(bars, DESIGN, engine, base)
    validation_output, validation_raw = simulate_window(bars, VALIDATION, engine, base)
    annual = {
        year: simulate_window(bars, window, engine, base) for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        validation_output, design_raw, validation_raw, annual, base
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_MONTHLY_CONSTANT_MIX_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_MONTHLY_CONSTANT_MIX_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_MONTHLY_40_50_60_CONSTANT_MIX_FAMILY_WITHOUT_TUNING"
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
            "sha256": bindings["data"],
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
            "complete_day_count": daily_count,
            "month_boundary_count": monthly_count,
        },
        "source_bindings": {
            "frozen_partial_rebalance_engine_sha256": bindings["engine"],
            "frozen_path_and_passive_reference_sha256": bindings["base"],
            "frozen_h1_parser_sha256": bindings["parser"],
            "sealed_primary_prior_sha256": bindings["prior"],
            "frozen_hypothesis_sha256": bindings["hypothesis"],
        },
        "policy": {
            "primary_target_weight": "0.50",
            "neighbor_target_weights": ["0.40", "0.60"],
            "cash_return": "0",
            "rebalance": "UTC_MONTH_BOUNDARY_NEXT_HOURLY_OPEN",
            "cost_basis": "FIFO",
            "variants": 3,
            "neighbor_selection": "DENY",
        },
        "windows": {
            "design": design_output,
            "validation": validation_output,
        },
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": (
            "Historical preregistered Design and Validation only. The fifty-percent policy is primary; forty and sixty percent are non-selection stability neighbors. A pass is not independent OOS or activation authority."
        ),
        "scope_note": (
            "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
        ),
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
                "failed_gates": result["failed_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
