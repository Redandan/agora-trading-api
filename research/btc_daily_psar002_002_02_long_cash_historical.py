#!/usr/bin/env python3
"""Deterministic historical screen for a frozen daily Parabolic SAR long/cash family."""

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


getcontext().prec = 34

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
ECONOMIC_SUPPORT_SOURCE = REPO_ROOT / "research" / "btc_daily_obv_ma_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
DAILY_SUPPORT_SOURCE = REPO_ROOT / "research" / "btc_daily_supertrend10x3_long_cash_historical.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-psar002-002-02-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-psar002-002-02-long-cash-v1.hypothesis.json"

EXPERIMENT_ID = "btc-daily-psar002-002-02-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_PSAR002_002_02_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_ECONOMIC_SUPPORT_SHA256 = "449f6790007cc0fc57298059f9f3f3dee812e37ec3539c9fc8c63593fe99a3d5"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_DAILY_SUPPORT_SHA256 = "46db811403ae61f70eff615e342d8c4d93e7d72c1bb8edc162c4cc6022d18213"
EXPECTED_PRIOR_SHA256 = "34c5591291eb69739661959337a7dd87ae6406997fa4fdfc95bedd88740d2e35"
EXPECTED_HYPOTHESIS_SHA256 = "32a5d43dad5215638a09f6f192d99291adb0e62ee41c33a90e3cf0665555146b"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_PSAR002": {"start_bps": 2, "maximum_bps": 20, "role": "PRIMARY"},
    "NEIGHBOR_PSAR001": {"start_bps": 1, "maximum_bps": 20, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_PSAR003": {"start_bps": 3, "maximum_bps": 20, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class SarState:
    close_time: datetime
    bullish: bool
    sar: D
    extreme_point: D
    acceleration: D


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


def q(value: D) -> str:
    return str(value.quantize(D("0.00000001")))


def parabolic_sar_states(
    daily: list[object], start_bps: int, maximum_bps: int
) -> list[SarState]:
    if start_bps not in {1, 2, 3} or maximum_bps != 20:
        raise ResearchReject("MANIFEST_REJECT:PSAR_PARAMETERS")
    if len(daily) < 2:
        raise ResearchReject("DATA_REJECT:PSAR_REQUIRES_TWO_COMPLETE_DAYS")
    start = D(start_bps) / HUNDRED
    increment = start
    maximum = D(maximum_bps) / HUNDRED

    first = daily[0]
    second = daily[1]
    bullish = second.close > first.close
    sar = first.low if bullish else first.high
    extreme = max(first.high, second.high) if bullish else min(first.low, second.low)
    acceleration = start
    states = [
        SarState(second.close_time, bullish, sar, extreme, acceleration)
    ]

    for index in range(2, len(daily)):
        point = daily[index]
        candidate = sar + acceleration * (extreme - sar)
        if bullish:
            if point.low < candidate:
                bullish = False
                candidate = extreme
                extreme = point.low
                acceleration = start
            else:
                candidate = min(candidate, daily[index - 1].low, daily[index - 2].low)
                if point.high > extreme:
                    extreme = point.high
                    acceleration = min(acceleration + increment, maximum)
        else:
            if point.high > candidate:
                bullish = True
                candidate = extreme
                extreme = point.high
                acceleration = start
            else:
                candidate = max(candidate, daily[index - 1].high, daily[index - 2].high)
                if point.low < extreme:
                    extreme = point.low
                    acceleration = min(acceleration + increment, maximum)
        sar = candidate
        states.append(
            SarState(point.close_time, bullish, sar, extreme, acceleration)
        )
    return states


def target_by_execution_time(
    daily: list[object], start_bps: int, maximum_bps: int
) -> dict[datetime, bool]:
    return {
        state.close_time: state.bullish
        for state in parabolic_sar_states(daily, start_bps, maximum_bps)
    }


def simulate_window(
    support: ModuleType,
    reference: ModuleType,
    bars: list[object],
    daily: list[object],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, object] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for variant_name, variant in VARIANTS.items():
        output[variant_name] = {}
        raw[variant_name] = {}
        for scenario_name, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = support.simulate_scenario(
                reference,
                bars,
                daily,
                window,
                variant["start_bps"],
                variant["maximum_bps"],
                fee_rate,
                slippage,
            )
            output[variant_name][scenario_name] = scenario_output
            raw[variant_name][scenario_name] = scenario_raw
    return output, raw


def require_buy_hold_parity(
    design_output: dict[str, object], validation_output: dict[str, object]
) -> None:
    actual = {
        "design_normal": [
            design_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["total_return_pct"],
            design_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["maximum_drawdown_pct"],
            design_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["calmar_ratio"],
        ],
        "design_stress": [
            design_output["PRIMARY_PSAR002"]["STRESS"]["buy_and_hold"]["total_return_pct"]
        ],
        "validation_normal": [
            validation_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["total_return_pct"],
            validation_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["maximum_drawdown_pct"],
            validation_output["PRIMARY_PSAR002"]["NORMAL"]["buy_and_hold"]["calmar_ratio"],
        ],
        "validation_stress": [
            validation_output["PRIMARY_PSAR002"]["STRESS"]["buy_and_hold"]["total_return_pct"]
        ],
    }
    expected = {
        "design_normal": ["129.60544229", "77.18955925", "1.67905405"],
        "design_stress": ["129.26172157"],
        "validation_normal": ["464.75475156", "32.28416349", "14.39575015"],
        "validation_stress": ["463.90931032"],
    }
    if actual != expected:
        raise ResearchReject(f"ECONOMIC_REJECT:BUY_HOLD_PARITY:{actual}")


def evaluate_gates(
    support: ModuleType,
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, object],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    primary = "PRIMARY_PSAR002"
    dn = design[primary]["NORMAL"]
    ds = design[primary]["STRESS"]
    vn = validation[primary]["NORMAL"]
    vs = validation[primary]["STRESS"]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_ohlcv_and_2192_complete_utc_days_pass": True,
        "frozen_runner_support_reference_parser_prior_and_hypothesis_sha256_match": True,
        "psar_state_path_formula_tests_pass_before_outcome": True,
        "buy_hold_reference_ledger_parity_pass": True,
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"] <= D("0.95") * dn["buy_hold_drawdown"],
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "primary_validation_position_changes_between_2_and_250": D("2") <= vn["position_changes"] <= D("250"),
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
    }
    for neighbor in ("NEIGHBOR_PSAR001", "NEIGHBOR_PSAR003"):
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[f"{neighbor.lower()}_validation_{scenario.lower()}_total_return_pct_gt_0"] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{neighbor.lower()}_validation_normal_drawdown_non_worse"] = value["drawdown"] <= value["buy_hold_drawdown"]
        gates[f"{neighbor.lower()}_validation_normal_calmar_at_least_75pct_buy_hold"] = value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        gates[f"{neighbor.lower()}_validation_normal_upside_capture_at_least_50pct"] = value["upside_capture"] >= D("0.50")

    annual_raw = {year: value[1][primary] for year, value in annual.items()}
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
            "primary_validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= D("1"),
        }
    )
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": support.q(top_year),
        "primary_validation_top_positive_episode_contribution_pct": validation_output[primary]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
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
    if policy.get("primary") != {"start": "0.02", "increment": "0.02", "maximum": "0.20"}:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"start": "0.01", "increment": "0.01", "maximum": "0.20"},
        {"start": "0.03", "increment": "0.03", "maximum": "0.20"},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    bindings = {item["path"]: item["sha256"] for item in manifest.get("source_bindings", [])}
    expected = {
        "research/btc_daily_psar002_002_02_long_cash_historical.py": sha256(Path(__file__).resolve()),
        "research/btc_daily_obv_ma_long_cash_historical.py": EXPECTED_ECONOMIC_SUPPORT_SHA256,
        "research/btc_monthly_12m_time_series_momentum_historical.py": EXPECTED_REFERENCE_SHA256,
        "research/btc_dra_reversal_confirmed_exit_v2c.py": EXPECTED_PARSER_SHA256,
        "research/btc_daily_supertrend10x3_long_cash_historical.py": EXPECTED_DAILY_SUPPORT_SHA256,
        "research_pipeline/examples/btc-daily-psar002-002-02-long-cash-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
        "research_pipeline/examples/btc-daily-psar002-002-02-long-cash-v1.hypothesis.json": EXPECTED_HYPOTHESIS_SHA256,
    }
    if bindings != expected:
        raise ResearchReject(f"MANIFEST_REJECT:SOURCE_BINDINGS:{bindings}")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        ECONOMIC_SUPPORT_SOURCE: EXPECTED_ECONOMIC_SUPPORT_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        DAILY_SUPPORT_SOURCE: EXPECTED_DAILY_SUPPORT_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
    }
    for source_path, expected in expected_sources.items():
        actual = sha256(source_path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source_path}:{actual}")
    data_sha = sha256(input_path)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")

    parser = load_module("frozen_psar_h1_parser", PARSER_SOURCE)
    reference = load_module("frozen_psar_economic_reference", REFERENCE_SOURCE)
    support = load_module("frozen_psar_economic_support", ECONOMIC_SUPPORT_SOURCE)
    daily_support = load_module("frozen_psar_daily_support", DAILY_SUPPORT_SOURCE)
    support.target_by_execution_time = target_by_execution_time
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = daily_support.build_daily_points(bars)
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise ResearchReject("DATA_REJECT:UTC_DAY_COUNT")

    design_output, design_raw = simulate_window(support, reference, bars, daily, DESIGN)
    validation_output, validation_raw = simulate_window(support, reference, bars, daily, VALIDATION)
    annual = {
        year: simulate_window(support, reference, bars, daily, window)
        for year, window in ANNUAL.items()
    }
    require_buy_hold_parity(design_output, validation_output)
    gates, failed, breadth = evaluate_gates(
        support, design_raw, validation_output, validation_raw, annual
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_PSAR002_002_02_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_DAILY_PSAR_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_DAILY_PSAR_START_INCREMENT_001_002_003_MAX02_LONG_CASH_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve()), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": data_sha,
            "hourly_rows": len(bars),
            "complete_utc_days": len(daily),
            "selection_cutoff": "2025-01-01T00:00:00",
            "first_complete_day_close": daily[0].close_time.isoformat(),
            "last_complete_day_close": daily[-1].close_time.isoformat(),
        },
        "source_bindings": {
            "economic_support_sha256": EXPECTED_ECONOMIC_SUPPORT_SHA256,
            "economic_reference_sha256": EXPECTED_REFERENCE_SHA256,
            "frozen_h1_parser_sha256": EXPECTED_PARSER_SHA256,
            "daily_aggregation_support_sha256": EXPECTED_DAILY_SUPPORT_SHA256,
            "primary_prior_sha256": EXPECTED_PRIOR_SHA256,
            "hypothesis_sha256": EXPECTED_HYPOTHESIS_SHA256,
        },
        "policy": {
            "initialization": "SECOND_DAY_DIRECTION_FROM_FIRST_TWO_CLOSES_SAR_AT_FIRST_OPPOSITE_EXTREME_EP_AT_TWO_DAY_TREND_EXTREME",
            "recurrence": "PRIOR_SAR_PLUS_AF_TIMES_PRIOR_EP_MINUS_PRIOR_SAR",
            "reversal": "STRICT_CURRENT_LOW_BELOW_CANDIDATE_IN_UPTREND_OR_HIGH_ABOVE_CANDIDATE_IN_DOWNTREND",
            "clamp": "OUTSIDE_PRIOR_TWO_COMPLETE_DAY_RANGES_ONLY_WHEN_NO_REVERSAL",
            "primary": "START_002_INCREMENT_002_MAX_020_BULLISH_LONG_ELSE_CASH",
            "rejection_only_neighbors": [
                "START_001_INCREMENT_001_MAX_020_BULLISH_LONG_ELSE_CASH",
                "START_003_INCREMENT_003_MAX_020_BULLISH_LONG_ELSE_CASH",
            ],
            "execution": "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY",
            "variants": 3,
        },
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset_primary": {year: value[0]["PRIMARY_PSAR002"] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; this trend-adjacent feature is not independent OOS. A pass is not activation authority and a failure cannot be rescued with DMI, Supertrend or alternate PSAR parameters.",
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
    for candidate in (input_path, manifest_path):
        if not candidate.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{candidate}")
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
