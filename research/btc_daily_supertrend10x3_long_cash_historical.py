#!/usr/bin/env python3
"""Deterministic historical screen for a frozen daily Supertrend long/cash family."""

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
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
ECONOMIC_SUPPORT_SOURCE = (
    REPO_ROOT / "research" / "btc_daily_obv_ma_long_cash_historical.py"
)
REFERENCE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-supertrend10x3-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-supertrend10x3-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-daily-supertrend10x3-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_SUPERTREND10X3_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_ECONOMIC_SUPPORT_SHA256 = "449f6790007cc0fc57298059f9f3f3dee812e37ec3539c9fc8c63593fe99a3d5"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "12ce9bb7c779346e0c66d280bb76397890bb8787ca2648a6cd7adf33fd7029d7"
EXPECTED_HYPOTHESIS_SHA256 = "43e2751070fb5735f9d42bc205f060e75da5673992f494467e281f4b5d679bfc"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_SUPERTREND10X3": {
        "period": 10,
        "multiplier": D("3.0"),
        "role": "PRIMARY",
    },
    "NEIGHBOR_SUPERTREND10X2_5": {
        "period": 10,
        "multiplier": D("2.5"),
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
    "NEIGHBOR_SUPERTREND10X3_5": {
        "period": 10,
        "multiplier": D("3.5"),
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyPoint:
    close_time: datetime
    high: D
    low: D
    close: D


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


def build_daily_points(bars: list[object]) -> list[DailyPoint]:
    points: list[DailyPoint] = []
    day_bars: list[object] = []
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
        points.append(
            DailyPoint(
                close_time=bar.close_time,
                high=max(item.high for item in day_bars),
                low=min(item.low for item in day_bars),
                close=day_bars[-1].close,
            )
        )
        day_bars = []
    if day_bars:
        raise ResearchReject(f"DATA_REJECT:INCOMPLETE_FINAL_UTC_DAY:{len(day_bars)}")
    if len(points) != EXPECTED_DAILY_ROWS:
        raise ResearchReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(points)}")
    for index, point in enumerate(points):
        if point.high < point.low or point.close <= ZERO:
            raise ResearchReject(f"DATA_REJECT:DAILY_OHLC:{point.close_time}")
        if index and (point.close_time - points[index - 1].close_time).days != 1:
            raise ResearchReject(
                f"DATA_REJECT:UTC_DAY_GAP:{points[index - 1].close_time}:{point.close_time}"
            )
    return points


def target_by_execution_time(
    daily: list[DailyPoint], period: int, multiplier: D
) -> dict[datetime, bool]:
    multiplier = D(str(multiplier))
    if period != 10 or multiplier not in {D("2.5"), D("3.0"), D("3.5")}:
        raise ResearchReject("MANIFEST_REJECT:SUPERTREND_POLICY")

    targets: dict[datetime, bool] = {}
    true_ranges: list[D] = []
    atr: D | None = None
    previous_upper: D | None = None
    previous_lower: D | None = None
    previous_supertrend: D | None = None
    previous_close: D | None = None

    for index, point in enumerate(daily):
        true_range = point.high - point.low
        if previous_close is not None:
            true_range = max(
                true_range,
                abs(point.high - previous_close),
                abs(point.low - previous_close),
            )
        true_ranges.append(true_range)
        if index == period - 1:
            atr = sum(true_ranges[:period], ZERO) / D(period)
        elif index >= period:
            if atr is None:
                raise ResearchReject("CALCULATION_REJECT:ATR_STATE")
            atr = (atr * D(period - 1) + true_range) / D(period)

        if atr is None:
            previous_close = point.close
            continue

        midpoint = (point.high + point.low) / D("2")
        basic_upper = midpoint + multiplier * atr
        basic_lower = midpoint - multiplier * atr
        if previous_upper is None or previous_lower is None or previous_close is None:
            upper = basic_upper
            lower = basic_lower
            uptrend = False
        else:
            upper = (
                basic_upper
                if basic_upper < previous_upper or previous_close > previous_upper
                else previous_upper
            )
            lower = (
                basic_lower
                if basic_lower > previous_lower or previous_close < previous_lower
                else previous_lower
            )
            if previous_supertrend == previous_upper:
                uptrend = point.close > upper
            else:
                uptrend = not (point.close < lower)
        supertrend = lower if uptrend else upper
        targets[point.close_time] = uptrend
        previous_upper = upper
        previous_lower = lower
        previous_supertrend = supertrend
        previous_close = point.close

    return targets


def simulate_window(
    support: ModuleType,
    reference: ModuleType,
    bars: list[object],
    daily: list[DailyPoint],
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
                variant["period"],
                variant["multiplier"],
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
    primary = "PRIMARY_SUPERTREND10X3"
    dn = design[primary]["NORMAL"]
    ds = design[primary]["STRESS"]
    vn = validation[primary]["NORMAL"]
    vs = validation[primary]["STRESS"]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_ohlcv_and_2192_complete_utc_days_pass": True,
        "frozen_support_reference_parser_prior_and_hypothesis_sha256_match": True,
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"]
        <= D("0.95") * dn["buy_hold_drawdown"],
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"]
        > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"]
        > ZERO,
        "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold": vn[
            "drawdown"
        ]
        <= D("0.90") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_60pct": vn[
            "upside_capture"
        ]
        >= D("0.60"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"]
        >= vn["buy_hold_calmar"],
        "primary_validation_position_changes_between_2_and_250": D("2")
        <= vn["position_changes"]
        <= D("250"),
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("3"),
    }

    for neighbor in (
        "NEIGHBOR_SUPERTREND10X2_5",
        "NEIGHBOR_SUPERTREND10X3_5",
    ):
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[
                f"{neighbor.lower()}_validation_{scenario.lower()}_total_return_pct_gt_0"
            ] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{neighbor.lower()}_validation_normal_drawdown_non_worse"] = value[
            "drawdown"
        ] <= value["buy_hold_drawdown"]
        gates[
            f"{neighbor.lower()}_validation_normal_calmar_at_least_75pct_buy_hold"
        ] = value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        gates[
            f"{neighbor.lower()}_validation_normal_upside_capture_at_least_50pct"
        ] = value["upside_capture"] >= D("0.50")

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
        value["NORMAL"]["upside_capture"] >= D("0.50")
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
            "primary_normal_annual_upside_capture_at_least_50pct_at_least_4_of_5": upside_breadth
            >= 4,
            "primary_top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "primary_validation_top_positive_episode_contribution_at_most_60pct": (
                vn["has_positive_episode"] == ZERO
                or vn["top_positive_episode_contribution"] <= D("60")
            ),
            "primary_validation_p90_hold_at_most_17520_hours": vn["p90_hold"]
            <= D("17520"),
            "primary_validation_terminal_holding_age_at_most_17520_hours": vn[
                "terminal_holding_age"
            ]
            <= D("17520"),
            "primary_validation_terminal_liquidation_adjusted_return_pct_gt_0": vn[
                "terminal_liquidation_return"
            ]
            > ZERO,
            "primary_validation_terminal_liquidation_cost_at_most_1pp": vn[
                "terminal_liquidation_cost"
            ]
            <= D("1"),
        }
    )
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": support.q(top_year),
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
    if policy.get("primary") != {
        "atr_complete_days": 10,
        "multiplier": "3.0",
    }:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"atr_complete_days": 10, "multiplier": "2.5"},
        {"atr_complete_days": 10, "multiplier": "3.5"},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        ECONOMIC_SUPPORT_SOURCE: EXPECTED_ECONOMIC_SUPPORT_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
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

    parser = load_module("frozen_supertrend_h1_parser", PARSER_SOURCE)
    reference = load_module("frozen_supertrend_economic_reference", REFERENCE_SOURCE)
    support = load_module("frozen_supertrend_economic_support", ECONOMIC_SUPPORT_SOURCE)
    support.target_by_execution_time = target_by_execution_time
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = build_daily_points(bars)

    design_output, design_raw = simulate_window(
        support, reference, bars, daily, DESIGN
    )
    validation_output, validation_raw = simulate_window(
        support, reference, bars, daily, VALIDATION
    )
    annual = {
        year: simulate_window(support, reference, bars, daily, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        support, design_raw, validation_output, validation_raw, annual
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_SUPERTREND10X3_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_DAILY_SUPERTREND10X3_LONG_CASH_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_DAILY_SUPERTREND10_MULTIPLIER_2_5_3_0_3_5_LONG_CASH_FAMILY_WITHOUT_TUNING"
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
            "first_complete_day_close": daily[0].close_time.isoformat(),
            "last_complete_day_close": daily[-1].close_time.isoformat(),
        },
        "source_bindings": {
            "economic_support_sha256": EXPECTED_ECONOMIC_SUPPORT_SHA256,
            "economic_reference_sha256": EXPECTED_REFERENCE_SHA256,
            "frozen_h1_parser_sha256": EXPECTED_PARSER_SHA256,
            "primary_prior_sha256": EXPECTED_PRIOR_SHA256,
            "hypothesis_sha256": EXPECTED_HYPOTHESIS_SHA256,
        },
        "policy": {
            "true_range": "MAX_HIGH_LOW_ABS_HIGH_PREVIOUS_CLOSE_ABS_LOW_PREVIOUS_CLOSE",
            "atr": "WILDER_RMA_10_INITIALIZED_BY_FIRST_10_TRUE_RANGE_MEAN",
            "bands": "COMPLETE_DAY_MIDPOINT_PLUS_MINUS_MULTIPLIER_ATR_WITH_PRIOR_BAND_RATCHET",
            "initial_direction": "DOWNTREND_UNTIL_ATR10_EXISTS",
            "primary": "SUPERTREND10_MULTIPLIER_3_0_UPTREND_LONG_ELSE_CASH",
            "rejection_only_neighbors": [
                "SUPERTREND10_MULTIPLIER_2_5_UPTREND_LONG_ELSE_CASH",
                "SUPERTREND10_MULTIPLIER_3_5_UPTREND_LONG_ELSE_CASH",
            ],
            "delay": "ZERO",
            "minimum_hold": "UNTIL_DIFFERENT_SIGNAL",
            "execution": "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY",
            "variants": 3,
        },
        "windows": {
            "design": design_output,
            "validation": validation_output,
        },
        "annual_fair_reset_primary": {
            year: value[0]["PRIMARY_SUPERTREND10X3"]
            for year, value in annual.items()
        },
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": (
            "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or proof that another Supertrend tuple works."
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
