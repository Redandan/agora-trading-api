#!/usr/bin/env python3
"""Deterministic historical screen for a frozen fixed UTC session family."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from collections.abc import Callable
from datetime import datetime
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 34

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
ECONOMIC_SUPPORT_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-intraday-utc0816-session-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-intraday-utc0816-session-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-intraday-utc0816-session-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_INTRADAY_UTC0816_SESSION_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_DATA_SHA256 = (
    "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
)
EXPECTED_DATA_ROWS = 52_608
EXPECTED_ECONOMIC_SUPPORT_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_PARSER_SHA256 = (
    "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
)
EXPECTED_PRIOR_SHA256 = (
    "bc9189119e8cc2f3a9ed95dff148793a554e3b8ec0dc259ca4b1e18bf9a2307f"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "bbc592749f1713f6dc8c7c57475dad2d90820b0ff0c33c74a5311492b51d3afd"
)

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_UTC_08_16": {
        "entry_hour": 8,
        "exit_hour": 16,
        "role": "PRIMARY",
    },
    "NEIGHBOR_UTC_07_15": {
        "entry_hour": 7,
        "exit_hour": 15,
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
    "NEIGHBOR_UTC_09_17": {
        "entry_hour": 9,
        "exit_hour": 17,
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
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
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def validate_session(entry_hour: int, exit_hour: int) -> None:
    if (entry_hour, exit_hour) not in {(7, 15), (8, 16), (9, 17)}:
        raise ResearchReject("MANIFEST_REJECT:SESSION_POLICY")
    if exit_hour - entry_hour != 8:
        raise ResearchReject("MANIFEST_REJECT:SESSION_LENGTH")


def session_action(open_time: datetime, entry_hour: int, exit_hour: int) -> str | None:
    validate_session(entry_hour, exit_hour)
    if open_time.minute != 0 or open_time.second != 0 or open_time.microsecond != 0:
        raise ResearchReject("DATA_REJECT:NON_HOURLY_CLOCK")
    if open_time.hour == entry_hour:
        return "BUY"
    if open_time.hour == exit_hour:
        return "SELL"
    return None


def simulate_scenario(
    support: ModuleType,
    bars: list[object],
    window: tuple[datetime, datetime],
    entry_hour: int,
    exit_hour: int,
    fee_rate: D,
    slippage: D,
) -> tuple[dict[str, object], dict[str, D]]:
    validate_session(entry_hour, exit_hour)
    start, end = window
    trading = [bar for bar in bars if start <= bar.open_time < end]
    expected_hours = int((end - start).total_seconds() // 3600)
    expected_sessions = (end - start).days
    if (
        len(trading) != expected_hours
        or not trading
        or trading[0].open_time != start
        or trading[-1].close_time != end
    ):
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    cash = ONE
    quantity = ZERO
    entry_cost: D | None = None
    entry_time: datetime | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    session_count = 0
    position_changes = 0
    episode_pnls: list[D] = []
    hold_hours: list[D] = []
    path = support.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        action = session_action(bar.open_time, entry_hour, exit_hour)
        if action == "BUY":
            if quantity != ZERO or entry_cost is not None or entry_time is not None:
                raise ResearchReject("ECONOMIC_REJECT:OVERLAPPING_SESSION")
            entry_cost = cash
            quantity, cash, fee, gross = support.buy_all(
                cash, bar.open, fee_rate, slippage
            )
            entry_time = bar.open_time
            fees += fee
            turnover += gross
            session_count += 1
            position_changes += 1
        elif action == "SELL":
            if quantity <= ZERO or entry_cost is None or entry_time is None:
                raise ResearchReject("ECONOMIC_REJECT:MISSING_SESSION_ENTRY")
            net, fee, gross = support.sell_all(
                quantity, bar.open, fee_rate, slippage
            )
            pnl = net - entry_cost
            realized += pnl
            episode_pnls.append(pnl)
            hold_hours.append(
                D(str((bar.open_time - entry_time).total_seconds() / 3600))
            )
            cash += net
            quantity = ZERO
            entry_cost = None
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

    if quantity != ZERO or entry_cost is not None or entry_time is not None:
        raise ResearchReject("ECONOMIC_REJECT:TERMINAL_SESSION_POSITION")
    if session_count != expected_sessions or len(episode_pnls) != expected_sessions:
        raise ResearchReject(
            f"DATA_REJECT:SESSION_INVENTORY:{session_count}:{len(episode_pnls)}:{expected_sessions}"
        )

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    top_positive = (
        ZERO
        if not any(pnl > ZERO for pnl in episode_pnls)
        else max(pnl for pnl in episode_pnls if pnl > ZERO)
        / sum((pnl for pnl in episode_pnls if pnl > ZERO), ZERO)
        * HUNDRED
    )
    average_exposure = (
        path.exposure_sum / D(path.observations) * HUNDRED
        if path.observations
        else ZERO
    )
    candidate.update(
        {
            "realized_return_pct": support.q(realized * HUNDRED),
            "unrealized_return_pct": support.q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": support.q(
                raw["total_return"]
            ),
            "terminal_liquidation_cost_pp": support.q(ZERO),
            "fees_equity_units": support.q(fees),
            "turnover_equity_units": support.q(turnover),
            "signal_evaluation_count": expected_sessions,
            "long_target_count": expected_sessions,
            "cash_target_count": expected_sessions,
            "position_change_count": position_changes,
            "completed_episode_count": len(episode_pnls),
            "winning_episode_count": sum(pnl > ZERO for pnl in episode_pnls),
            "median_hold_hours": support.nullable(
                support.percentile(hold_hours, D("0.5"))
            ),
            "p90_hold_hours": support.nullable(
                support.percentile(hold_hours, D("0.9"))
            ),
            "terminal_position": False,
            "terminal_holding_age_hours": None,
            "top_positive_episode_contribution_pct": (
                None
                if not any(pnl > ZERO for pnl in episode_pnls)
                else support.q(top_positive)
            ),
        }
    )
    raw.update(
        {
            "realized_return": realized * HUNDRED,
            "unrealized_return": unrealized * HUNDRED,
            "terminal_liquidation_return": raw["total_return"],
            "terminal_liquidation_cost": ZERO,
            "position_changes": D(position_changes),
            "signal_evaluations": D(expected_sessions),
            "completed_episodes": D(len(episode_pnls)),
            "p90_hold": support.percentile(hold_hours, D("0.9")) or ZERO,
            "terminal_holding_age": ZERO,
            "terminal_position": ZERO,
            "top_positive_episode_contribution": top_positive,
            "has_positive_episode": (
                ONE if any(pnl > ZERO for pnl in episode_pnls) else ZERO
            ),
            "average_exposure": average_exposure,
        }
    )
    benchmark, benchmark_raw = support.passive_benchmark(
        trading, fee_rate, slippage
    )
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
            "total_return_delta_pp": support.q(
                raw["total_return"] - benchmark_raw["total_return"]
            ),
            "maximum_drawdown_delta_pp": support.q(
                raw["drawdown"] - benchmark_raw["drawdown"]
            ),
            "upside_capture_ratio": support.nullable(upside_capture),
            "calmar_ratio_to_buy_hold": support.nullable(
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
    support: ModuleType,
    bars: list[object],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, object] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for variant_name, variant in VARIANTS.items():
        output[variant_name] = {}
        raw[variant_name] = {}
        for scenario_name, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = simulate_scenario(
                support,
                bars,
                window,
                variant["entry_hour"],
                variant["exit_hour"],
                fee_rate,
                slippage,
            )
            output[variant_name][scenario_name] = scenario_output
            raw[variant_name][scenario_name] = scenario_raw
    return output, raw


def evaluate_gates(
    support: ModuleType,
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, object],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    primary = "PRIMARY_UTC_08_16"
    dn = design[primary]["NORMAL"]
    ds = design[primary]["STRESS"]
    vn = validation[primary]["NORMAL"]
    vs = validation[primary]["STRESS"]
    expected_design_sessions = D((DESIGN[1] - DESIGN[0]).days)
    expected_validation_sessions = D((VALIDATION[1] - VALIDATION[0]).days)
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_ohlcv_and_exact_session_inventory_pass": True,
        "frozen_support_parser_prior_hypothesis_and_runner_sha256_match": True,
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_75pct_of_buy_hold": dn["drawdown"]
        <= D("0.75") * dn["buy_hold_drawdown"],
        "primary_design_normal_upside_capture_at_least_25pct": dn["upside_capture"]
        >= D("0.25"),
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "primary_design_exact_daily_session_count": dn["signal_evaluations"]
        == expected_design_sessions,
        "primary_design_exact_two_position_changes_per_day": dn["position_changes"]
        == D("2") * expected_design_sessions,
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"]
        > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"]
        > ZERO,
        "primary_validation_normal_drawdown_at_most_75pct_of_buy_hold": vn["drawdown"]
        <= D("0.75") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_25pct": vn["upside_capture"]
        >= D("0.25"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"]
        >= vn["buy_hold_calmar"],
        "primary_validation_exact_daily_session_count": vn["signal_evaluations"]
        == expected_validation_sessions,
        "primary_validation_exact_two_position_changes_per_day": vn["position_changes"]
        == D("2") * expected_validation_sessions,
        "primary_validation_all_sessions_completed": vn["completed_episodes"]
        == expected_validation_sessions,
        "primary_validation_realized_equals_total_and_unrealized_zero": vn[
            "realized_return"
        ]
        == vn["total_return"]
        and vn["unrealized_return"] == ZERO,
        "primary_validation_average_exposure_between_30_and_37pct": D("30")
        <= vn["average_exposure"]
        <= D("37"),
        "primary_validation_p90_hold_exactly_8h": vn["p90_hold"] == D("8"),
        "primary_validation_no_terminal_inventory": vn["terminal_position"]
        == ZERO,
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("3"),
    }

    for neighbor in ("NEIGHBOR_UTC_07_15", "NEIGHBOR_UTC_09_17"):
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[
                f"{neighbor.lower()}_validation_{scenario.lower()}_total_return_pct_gt_0"
            ] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{neighbor.lower()}_validation_drawdown_non_worse_than_buy_hold"] = (
            value["drawdown"] <= value["buy_hold_drawdown"]
        )
        gates[f"{neighbor.lower()}_validation_calmar_at_least_75pct_buy_hold"] = (
            value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        )
        gates[f"{neighbor.lower()}_validation_upside_capture_at_least_20pct"] = (
            value["upside_capture"] >= D("0.20")
        )
        gates[f"{neighbor.lower()}_validation_exact_session_inventory"] = (
            value["signal_evaluations"] == expected_validation_sessions
            and value["completed_episodes"] == expected_validation_sessions
            and value["terminal_position"] == ZERO
        )

    annual_raw = {year: value[1][primary] for year, value in annual.items()}
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
    upside_breadth = sum(
        value["NORMAL"]["upside_capture"] >= D("0.20")
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
    gates.update(
        {
            "primary_normal_positive_annual_total_return_at_least_4_of_5": normal_positive
            >= 4,
            "primary_stress_positive_annual_total_return_at_least_4_of_5": stress_positive
            >= 4,
            "primary_normal_annual_drawdown_non_worse_at_least_4_of_5": drawdown_nonworse
            >= 4,
            "primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5": calmar_breadth
            >= 3,
            "primary_normal_annual_upside_capture_at_least_20pct_at_least_3_of_5": upside_breadth
            >= 3,
            "primary_top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "primary_validation_top_positive_episode_contribution_at_most_20pct": (
                vn["has_positive_episode"] == ZERO
                or vn["top_positive_episode_contribution"] <= D("20")
            ),
        }
    )
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_20pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": support.q(
            top_year
        ),
        "primary_validation_top_positive_episode_contribution_pct": validation_output[
            primary
        ]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
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
    if policy.get("primary") != {"entry_hour_utc": 8, "exit_hour_utc": 16}:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"entry_hour_utc": 7, "exit_hour_utc": 15},
        {"entry_hour_utc": 9, "exit_hour_utc": 17},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    runner_path = Path(__file__).resolve().relative_to(REPO_ROOT).as_posix()
    bindings = [
        binding
        for binding in manifest.get("source_bindings", [])
        if binding.get("path") == runner_path
    ]
    if len(bindings) != 1:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    if bindings[0].get("sha256") != sha256(Path(__file__).resolve()):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_SHA256")
    if bindings[0].get("role") != "FROZEN_DIRECT_ECONOMIC_RUNNER":
        raise ResearchReject("MANIFEST_REJECT:RUNNER_ROLE")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        ECONOMIC_SUPPORT_SOURCE: EXPECTED_ECONOMIC_SUPPORT_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
    }
    for path, expected in expected_sources.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{path}:{actual}")
    data_sha = sha256(input_path)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")

    support = load_module("frozen_intraday_session_economic_support", ECONOMIC_SUPPORT_SOURCE)
    parser = load_module("frozen_intraday_session_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")

    design_output, design_raw = simulate_window(support, bars, DESIGN)
    validation_output, validation_raw = simulate_window(support, bars, VALIDATION)
    annual = {
        year: simulate_window(support, bars, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        support, design_raw, validation_output, validation_raw, annual
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_INTRADAY_UTC0816_SESSION_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_INTRADAY_FIXED_UTC_SESSION_LONG_CASH_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_FIXED_UTC_07_15_08_16_09_17_SESSION_LONG_CASH_FAMILY_WITHOUT_TUNING"
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
            "selection_cutoff": "2025-01-01T00:00:00",
            "first_open_time": bars[0].open_time.isoformat(),
            "last_close_time": bars[-1].close_time.isoformat(),
        },
        "source_bindings": {
            "economic_support_sha256": EXPECTED_ECONOMIC_SUPPORT_SHA256,
            "frozen_h1_parser_sha256": EXPECTED_PARSER_SHA256,
            "primary_prior_sha256": EXPECTED_PRIOR_SHA256,
            "hypothesis_sha256": EXPECTED_HYPOTHESIS_SHA256,
        },
        "policy": {
            "decision_feature": "FIXED_UTC_CLOCK_KNOWN_BEFORE_FILL",
            "primary": "BUY_08_UTC_OPEN_SELL_16_UTC_OPEN_EVERY_COMPLETE_DAY",
            "rejection_only_neighbors": [
                "BUY_07_UTC_OPEN_SELL_15_UTC_OPEN_EVERY_COMPLETE_DAY",
                "BUY_09_UTC_OPEN_SELL_17_UTC_OPEN_EVERY_COMPLETE_DAY",
            ],
            "session_length_hours": 8,
            "sizing": "FULL_AVAILABLE_EQUITY_NO_LEVERAGE",
            "cash_return": "0",
            "short": "DENY",
            "variants": 3,
        },
        "windows": {
            "design": design_output,
            "validation": validation_output,
        },
        "annual_fair_reset_primary": {
            year: value[0]["PRIMARY_UTC_08_16"]
            for year, value in annual.items()
        },
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or proof that another clock interval works.",
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
