#!/usr/bin/env python3
"""Deterministic historical screen for the canonical BTC turn-of-month interval."""

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

REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_RUNNER_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-turn-of-month-primary-prior.v1.json"
)

EXPERIMENT_ID = "btc-turn-of-month-last-day-plus-three-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_TURN_OF_MONTH_LAST_DAY_PLUS_THREE_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_MANIFEST_SHA256 = (
    "b770f089e0128c02e68db12edae698be8465aa68830e446f9c948262926b3ffd"
)
EXPECTED_DATA_SHA256 = (
    "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
)
EXPECTED_DATA_ROWS = 52_608
EXPECTED_PARSER_SHA256 = (
    "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
)
EXPECTED_BASE_RUNNER_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_PRIOR_SHA256 = (
    "a2281fc9e080ec3de19b7d2555b92b51ec48a03099701397576a4ffe1a2c2ca9"
)

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def is_final_calendar_day(value: datetime) -> bool:
    return (value + timedelta(days=1)).month != value.month


def is_turn_of_month_hour(value: datetime) -> bool:
    return value.day <= 3 or is_final_calendar_day(value)


def expected_transition_times(start: datetime, end: datetime) -> list[datetime]:
    if start >= end or start.minute or start.second or start.microsecond:
        raise ResearchReject("POLICY_REJECT:INVALID_WINDOW")
    values: list[datetime] = []
    previous: bool | None = None
    current = start
    while current < end:
        target = is_turn_of_month_hour(current)
        if previous is None or target != previous:
            values.append(current)
            previous = target
        current += timedelta(hours=1)
    return values


def validate_time_inventory(bars: list[object]) -> dict[str, int]:
    if len(bars) != EXPECTED_DATA_ROWS:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(bars)}")
    for index, bar in enumerate(bars):
        if bar.close_time - bar.open_time != timedelta(hours=1):
            raise ResearchReject(f"DATA_REJECT:BAR_WIDTH:{index}")
        if index and bars[index - 1].close_time != bar.open_time:
            raise ResearchReject(f"DATA_REJECT:HOURLY_GAP:{index}")
        if not (
            bar.low > ZERO
            and bar.low <= bar.open <= bar.high
            and bar.low <= bar.close <= bar.high
            and bar.volume >= ZERO
        ):
            raise ResearchReject(f"DATA_REJECT:OHLCV:{index}")
    if bars[0].open_time != datetime(2019, 1, 1):
        raise ResearchReject("DATA_REJECT:FIRST_OPEN")
    if bars[-1].close_time != datetime(2025, 1, 1):
        raise ResearchReject("DATA_REJECT:LAST_CLOSE")
    complete_days = sum(
        bar.close_time.hour == 0
        and bar.close_time.minute == 0
        and bar.close_time.second == 0
        for bar in bars
    )
    corpus_transitions = expected_transition_times(
        bars[0].open_time, bars[-1].close_time
    )
    if complete_days != 2_192 or len(corpus_transitions) != 145:
        raise ResearchReject(
            f"DATA_REJECT:INVENTORY:{complete_days}:{len(corpus_transitions)}"
        )
    return {
        "complete_day_count": complete_days,
        "calendar_transition_count": len(corpus_transitions),
    }


def simulate_scenario(
    bars: list[object],
    window: tuple[datetime, datetime],
    fee_rate: D,
    slippage: D,
    base: ModuleType,
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
    quantity = ZERO
    entry_cost = ZERO
    entry_time: datetime | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    realized_lots: list[D] = []
    holding_hours: list[D] = []
    signal_times: list[datetime] = []
    trade_count = 0
    buy_trades = 0
    sell_trades = 0
    previous_target: bool | None = None
    path = base.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        target = is_turn_of_month_hour(bar.open_time)
        if previous_target is None or target != previous_target:
            signal_times.append(bar.open_time)
            if target:
                if quantity != ZERO or cash <= ZERO:
                    raise ResearchReject("ECONOMIC_REJECT:INVALID_BUY_STATE")
                quantity, cash, trade_fee, gross = base.buy_all(
                    cash, bar.open, fee_rate, slippage
                )
                entry_cost = gross + trade_fee
                entry_time = bar.open_time
                fees += trade_fee
                turnover += gross
                trade_count += 1
                buy_trades += 1
            else:
                if quantity <= ZERO or entry_time is None or entry_cost <= ZERO:
                    raise ResearchReject("ECONOMIC_REJECT:INVALID_SELL_STATE")
                proceeds, trade_fee, gross = base.sell_all(
                    quantity, bar.open, fee_rate, slippage
                )
                lot_return = proceeds - entry_cost
                realized += lot_return
                realized_lots.append(lot_return)
                holding_hours.append(
                    D(str((bar.open_time - entry_time).total_seconds() / 3600))
                )
                cash = proceeds
                quantity = ZERO
                entry_cost = ZERO
                entry_time = None
                fees += trade_fee
                turnover += gross
                trade_count += 1
                sell_trades += 1
            previous_target = target

        market_value = quantity * bar.close
        final_equity = cash + market_value
        path.observe(
            final_equity,
            market_value / final_equity if final_equity > ZERO else ZERO,
        )

    expected_signals = expected_transition_times(start, end)
    if signal_times != expected_signals:
        raise ResearchReject("POLICY_REJECT:CALENDAR_TRANSITION_INVENTORY")

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    terminal_net = (
        ZERO
        if quantity == ZERO
        else quantity * trading[-1].close * (ONE - slippage) * (ONE - fee_rate)
    )
    terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (
        terminal_liquidation_equity - ONE
    ) * HUNDRED
    terminal_oldest_age = (
        ZERO
        if entry_time is None
        else D(str((end - entry_time).total_seconds() / 3600))
    )
    positive_lots = [value for value in realized_lots if value > ZERO]
    top_positive_lot = (
        ZERO
        if not positive_lots
        else max(positive_lots) / sum(positive_lots, ZERO) * HUNDRED
    )
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
            "signal_evaluation_count": len(signal_times),
            "trade_count": trade_count,
            "buy_trade_count": buy_trades,
            "sell_trade_count": sell_trades,
            "realized_lot_count": len(realized_lots),
            "median_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.5"))
            ),
            "p90_realized_lot_hold_hours": base.nullable(
                base.percentile(holding_hours, D("0.9"))
            ),
            "terminal_position": quantity > ZERO,
            "terminal_oldest_lot_age_hours": (
                None if entry_time is None else base.q(terminal_oldest_age)
            ),
            "terminal_inventory_cost_basis": base.q(entry_cost),
            "top_positive_realized_lot_contribution_pct": (
                None if not positive_lots else base.q(top_positive_lot)
            ),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"]
            - terminal_liquidation_return,
            "signal_evaluations": D(len(signal_times)),
            "trade_count": D(trade_count),
            "buy_trades": D(buy_trades),
            "sell_trades": D(sell_trades),
            "average_exposure": D(candidate["average_exposure_pct"]),
            "p90_hold": base.percentile(holding_hours, D("0.9")) or ZERO,
            "terminal_oldest_age": terminal_oldest_age,
            "top_positive_realized_contribution": top_positive_lot,
            "has_positive_realized_lot": ONE if positive_lots else ZERO,
        }
    )
    benchmark, benchmark_raw = base.passive_benchmark(
        trading, fee_rate, slippage
    )
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
        "calendar_transition_inventory_matches_exact_policy": True,
        "design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "design_normal_maximum_drawdown_at_most_50pct_of_buy_hold": dn[
            "drawdown"
        ]
        <= D("0.50") * dn["buy_hold_drawdown"],
        "design_normal_upside_capture_at_least_20pct": dn["upside_capture"]
        >= D("0.20"),
        "design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "design_normal_average_exposure_between_10pct_and_16pct": D("10")
        <= dn["average_exposure"]
        <= D("16"),
        "design_normal_signal_evaluations_exactly_73": dn["signal_evaluations"]
        == D("73"),
        "design_normal_sell_trades_exactly_36": dn["sell_trades"] == D("36"),
        "validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "validation_normal_maximum_drawdown_at_most_60pct_of_buy_hold": vn[
            "drawdown"
        ]
        <= D("0.60") * vn["buy_hold_drawdown"],
        "validation_normal_upside_capture_at_least_20pct": vn["upside_capture"]
        >= D("0.20"),
        "validation_normal_calmar_at_least_buy_hold": vn["calmar"]
        >= vn["buy_hold_calmar"],
        "validation_normal_average_exposure_between_10pct_and_16pct": D("10")
        <= vn["average_exposure"]
        <= D("16"),
        "validation_normal_signal_evaluations_exactly_49": vn[
            "signal_evaluations"
        ]
        == D("49"),
        "validation_normal_sell_trades_exactly_24": vn["sell_trades"]
        == D("24"),
        "validation_stress_maximum_drawdown_no_more_than_normal_plus_2pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("2"),
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
        value["NORMAL"]["calmar"] >= value["NORMAL"]["buy_hold_calmar"]
        for value in annual_raw.values()
    )
    upside_eligible = [
        value["NORMAL"]
        for value in annual_raw.values()
        if value["NORMAL"]["buy_hold_return"] > ZERO
    ]
    upside_breadth = sum(
        value["upside_capture"] >= D("0.20") for value in upside_eligible
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
            "normal_annual_calmar_at_least_buy_hold_at_least_4_of_5": calmar_breadth
            >= 4,
            "normal_annual_upside_capture_at_least_20pct_at_least_3_of_5_when_buy_hold_positive": upside_breadth
            >= 3,
            "top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "top_positive_realized_lot_contribution_at_most_40pct_when_positive_sales_exist": (
                vn["has_positive_realized_lot"] == ZERO or top_realized <= D("40")
            ),
            "validation_normal_p90_realized_lot_hold_at_most_96_hours": vn[
                "p90_hold"
            ]
            <= D("96"),
            "validation_normal_terminal_oldest_lot_age_at_most_24_hours_when_position_open": vn[
                "terminal_oldest_age"
            ]
            <= D("24"),
            "validation_normal_terminal_liquidation_adjusted_return_pct_gt_0": vn[
                "terminal_liquidation_return"
            ]
            > ZERO,
            "validation_normal_terminal_liquidation_cost_at_most_0_5pp_of_mark_to_market_return": vn[
                "terminal_liquidation_cost"
            ]
            <= D("0.5"),
        }
    )
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_5",
        "stress_positive_years": f"{stress_positive}_of_5",
        "normal_drawdown_non_worse_than_buy_hold_years": f"{drawdown_nonworse}_of_5",
        "normal_calmar_at_least_buy_hold_years": f"{calmar_breadth}_of_5",
        "normal_upside_capture_at_least_20pct_eligible_years": (
            f"{upside_breadth}_of_{len(upside_eligible)}"
        ),
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
    if (
        policy.get("decision_feature")
        != "UTC_CALENDAR_POSITION_LAST_DAY_OR_FIRST_THREE_DAYS_OF_MONTH"
    ):
        raise ResearchReject("MANIFEST_REJECT:DECISION_FEATURE")
    if policy.get("target_exposure") != "ONE_WHEN_IN_LONG_INTERVAL_OTHERWISE_ZERO":
        raise ResearchReject("MANIFEST_REJECT:TARGET_EXPOSURE")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    source_hashes = {
        "manifest": sha256(manifest_path),
        "base": sha256(BASE_RUNNER_SOURCE),
        "parser": sha256(PARSER_SOURCE),
        "prior": sha256(PRIOR_SOURCE),
        "dataset": sha256(input_path),
    }
    expected = {
        "manifest": EXPECTED_MANIFEST_SHA256,
        "base": EXPECTED_BASE_RUNNER_SHA256,
        "parser": EXPECTED_PARSER_SHA256,
        "prior": EXPECTED_PRIOR_SHA256,
        "dataset": EXPECTED_DATA_SHA256,
    }
    if source_hashes != expected:
        raise ResearchReject(f"SOURCE_REJECT:HASH_BINDING:{source_hashes}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    base = load_module("frozen_monthly_economic_reference", BASE_RUNNER_SOURCE)
    parser = load_module("frozen_turn_of_month_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != source_hashes[
        "dataset"
    ]:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    inventory = validate_time_inventory(bars)

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
        "document_type": "BTC_TURN_OF_MONTH_LAST_DAY_PLUS_THREE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_TURN_OF_MONTH_LAST_DAY_PLUS_THREE_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_TURN_OF_MONTH_FOUR_DAY_LONG_CASH_FAMILY_WITHOUT_TUNING"
        ),
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": source_hashes["manifest"],
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": source_hashes["dataset"],
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
            **inventory,
            "first_open_time": bars[0].open_time.isoformat(),
            "last_close_time": bars[-1].close_time.isoformat(),
        },
        "source_bindings": {
            "frozen_h1_parser_sha256": source_hashes["parser"],
            "frozen_passive_and_path_reference_sha256": source_hashes["base"],
            "sealed_mechanism_prior_sha256": source_hashes["prior"],
        },
        "policy": {
            "long_days": "LAST_UTC_CALENDAR_DAY_AND_FIRST_THREE_UTC_CALENDAR_DAYS",
            "target_exposure": "BINARY_ONE_OR_ZERO",
            "execution": "CALENDAR_TRANSITION_FIRST_HOURLY_OPEN",
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
        "claim_boundary": "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority, evidence for a neighboring calendar interval or proof of the proposed capital-flow cause.",
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
        json.dump(
            result,
            stream,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
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
