#!/usr/bin/env python3
"""Deterministic historical screen for frozen U.S. 3M yield easing."""

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


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
LEDGER_SOURCE = REPO_ROOT / "research" / "btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-us-treasury-3m-yield-easing-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-us-treasury-3m-yield-easing-long-cash-v1.hypothesis.json"
TREASURY_METADATA_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "us-treasury-par-yield-curve-2018-2024.v1.source.json"

EXPERIMENT_ID = "btc-us-treasury-3m-yield-easing-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_US_TREASURY_3M_YIELD_EASING_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_BTC_DAILY_ROWS = 2_192
EXPECTED_TREASURY_SHA256 = "045ce4646a4595697fc16d8e32c0fd08efd431680e998c9f85d2dcac1732c82a"
EXPECTED_TREASURY_ROWS = 1_750
EXPECTED_LEDGER_SHA256 = "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "7d272580657f56d24ec8e33ae32fff9617000e5036a1e62c23990ca707482992"
EXPECTED_HYPOTHESIS_SHA256 = "b73caeffdd95cfacf1ac2cb1b53adb51e9244f7fd4357da27ce56f7dc094e83d"
EXPECTED_TREASURY_METADATA_SHA256 = "0b66812eaf7f4da1b8c4a2b282887a256226b100048c98dce957f650f7bbc4b5"

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_YIELD63": {"lookback": 63, "role": "PRIMARY"},
    "NEIGHBOR_YIELD42": {"lookback": 42, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_YIELD84": {"lookback": 84, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_ECONOMIC_RUNNER": "research/btc_us_treasury_3m_yield_easing_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER_REFERENCE": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_H1_PARSER_AND_DATA_INTEGRITY_REFERENCE_ONLY": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR": "research_pipeline/examples/btc-us-treasury-3m-yield-easing-long-cash-primary-prior.v1.json",
    "FROZEN_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-us-treasury-3m-yield-easing-long-cash-v1.hypothesis.json",
    "SEALED_OFFICIAL_TREASURY_SOURCE_METADATA": "research_pipeline/examples/us-treasury-par-yield-curve-2018-2024.v1.source.json",
    "SEALED_NORMALIZED_TREASURY_CORPUS": ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/inputs/treasury-yield-curve-2018-2024.csv",
}
EXPECTED_GATE_NAMES = (
    "btc_sha256_and_52608_rows_match",
    "treasury_sha256_and_1750_rows_match",
    "hourly_lattice_weekly_selection_and_next_day_availability_pass",
    "frozen_runner_ledger_reference_parser_prior_hypothesis_and_source_sha256_match",
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
    "neighbor_yield42_validation_normal_total_return_pct_gt_0",
    "neighbor_yield42_validation_stress_total_return_pct_gt_0",
    "neighbor_yield42_validation_normal_drawdown_non_worse",
    "neighbor_yield42_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_yield42_validation_normal_upside_capture_at_least_50pct",
    "neighbor_yield84_validation_normal_total_return_pct_gt_0",
    "neighbor_yield84_validation_stress_total_return_pct_gt_0",
    "neighbor_yield84_validation_normal_drawdown_non_worse",
    "neighbor_yield84_validation_normal_calmar_at_least_75pct_buy_hold",
    "neighbor_yield84_validation_normal_upside_capture_at_least_50pct",
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


@dataclass(frozen=True)
class TreasuryPoint:
    observation_date: date
    three_month_yield_pct: D


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


def load_treasury(
    path: Path, expected_rows: int = EXPECTED_TREASURY_ROWS
) -> list[TreasuryPoint]:
    expected_columns = [
        "date",
        "three_month_pct",
        "one_year_pct",
        "two_year_pct",
        "ten_year_pct",
    ]
    points: list[TreasuryPoint] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != expected_columns:
            raise ResearchReject(f"DATA_REJECT:TREASURY_COLUMNS:{reader.fieldnames}")
        for row in reader:
            try:
                observation_date = date.fromisoformat(row["date"])
                values = [D(row[column]) for column in expected_columns[1:]]
            except (KeyError, ValueError, InvalidOperation) as exc:
                raise ResearchReject("DATA_REJECT:TREASURY_PARSE") from exc
            if any(not value.is_finite() or value < ZERO for value in values):
                raise ResearchReject(f"DATA_REJECT:TREASURY_VALUE:{observation_date}")
            if points and observation_date <= points[-1].observation_date:
                raise ResearchReject(f"DATA_REJECT:TREASURY_ORDER:{observation_date}")
            points.append(TreasuryPoint(observation_date, values[0]))
    if len(points) != expected_rows:
        raise ResearchReject(f"DATA_REJECT:TREASURY_ROWS:{len(points)}")
    return points


def targets_by_execution_time(
    treasury: list[TreasuryPoint], lookback: int
) -> tuple[dict[datetime, bool], dict[str, Any]]:
    if lookback not in {42, 63, 84}:
        raise ResearchReject(f"MANIFEST_REJECT:YIELD_LOOKBACK:{lookback}")
    final_index_by_iso_week: dict[tuple[int, int], int] = {}
    for index, point in enumerate(treasury):
        iso = point.observation_date.isocalendar()
        final_index_by_iso_week[(iso.year, iso.week)] = index
    targets: dict[datetime, bool] = {}
    changes: list[D] = []
    for index in sorted(final_index_by_iso_week.values()):
        if index < lookback:
            continue
        point = treasury[index]
        change = point.three_month_yield_pct - treasury[index - lookback].three_month_yield_pct
        effective_time = datetime.combine(
            point.observation_date + timedelta(days=1), time.min
        )
        if effective_time in targets:
            raise ResearchReject(f"DATA_REJECT:DUPLICATE_EFFECTIVE_TIME:{effective_time}")
        targets[effective_time] = change < ZERO
        changes.append(change)
    if not changes:
        raise ResearchReject("DATA_REJECT:NO_YIELD_EVALUATIONS")
    return targets, {
        "lookback_valid_business_observations": lookback,
        "evaluation_frequency": "FINAL_VALID_TREASURY_BUSINESS_DATE_OF_EACH_ISO_WEEK",
        "evaluation_count": len(changes),
        "easing_count": sum(change < ZERO for change in changes),
        "non_easing_count": sum(change >= ZERO for change in changes),
        "minimum_change_pct_points": q(min(changes)),
        "maximum_change_pct_points": q(max(changes)),
        "median_change_pct_points": q(percentile(changes, D("0.5")) or ZERO),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def simulate_window(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    treasury: list[TreasuryPoint],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, Any] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for variant_name, variant in VARIANTS.items():
        targets, feature = targets_by_execution_time(treasury, variant["lookback"])
        output[variant_name] = {
            "role": variant["role"],
            "feature": feature,
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


def evaluate_gates(
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn = design["PRIMARY_YIELD63"]["NORMAL"]
    ds = design["PRIMARY_YIELD63"]["STRESS"]
    vn = validation["PRIMARY_YIELD63"]["NORMAL"]
    vs = validation["PRIMARY_YIELD63"]["STRESS"]
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "treasury_sha256_and_1750_rows_match": True,
        "hourly_lattice_weekly_selection_and_next_day_availability_pass": True,
        "frozen_runner_ledger_reference_parser_prior_hypothesis_and_source_sha256_match": True,
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
    for neighbor in ("NEIGHBOR_YIELD42", "NEIGHBOR_YIELD84"):
        label = neighbor.lower()
        for scenario in ("NORMAL", "STRESS"):
            value = validation[neighbor][scenario]
            gates[f"{label}_validation_{scenario.lower()}_total_return_pct_gt_0"] = value["total_return"] > ZERO
        value = validation[neighbor]["NORMAL"]
        gates[f"{label}_validation_normal_drawdown_non_worse"] = value["drawdown"] <= value["buy_hold_drawdown"]
        gates[f"{label}_validation_normal_calmar_at_least_75pct_buy_hold"] = value["calmar"] >= D("0.75") * value["buy_hold_calmar"]
        gates[f"{label}_validation_normal_upside_capture_at_least_50pct"] = value["upside_capture"] >= D("0.50")
    annual_raw = {year: value[1]["PRIMARY_YIELD63"] for year, value in annual.items()}
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
        "primary_validation_top_positive_episode_contribution_pct": validation_output["PRIMARY_YIELD63"]["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != {
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "expected_complete_utc_days": EXPECTED_BTC_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "treasury": {"path": ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/inputs/treasury-yield-curve-2018-2024.csv", "sha256": EXPECTED_TREASURY_SHA256, "rows": EXPECTED_TREASURY_ROWS, "first_date": "2018-01-02", "last_date": "2024-12-31", "present_vintage": True},
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_US_TREASURY_3M_YIELD_EASING_LONG_CASH_V1",
        "source_series": "US_TREASURY_DAILY_PAR_THREE_MONTH_YIELD_PERCENT",
        "evaluation_frequency": "FINAL_VALID_TREASURY_BUSINESS_DATE_OF_EACH_ISO_WEEK",
        "availability": "OBSERVATION_DATE_PLUS_ONE_CALENDAR_DAY_AT_00_00_UTC",
        "primary_lookback_valid_business_observations": 63,
        "rejection_only_neighbor_lookbacks_valid_business_observations": [42, 84],
        "change_formula": "LATEST_THREE_MONTH_YIELD_MINUS_YIELD_EXACTLY_N_VALID_OBSERVATIONS_EARLIER",
        "long_condition": "YIELD_CHANGE_STRICTLY_LESS_THAN_ZERO",
        "cash_condition": "YIELD_CHANGE_GREATER_THAN_OR_EQUAL_TO_ZERO",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "missing_observation_rule": "USE_ONLY_VALID_PUBLISHED_ROWS_WITHOUT_INTERPOLATION_OR_SOURCE_REPAIR",
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
        "id": "BTC_US_TREASURY_3M_YIELD_EASING_MATCHED_CAPITAL_GATES_V1",
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


def build_output(
    btc_input: Path, treasury_input: Path, manifest_path: Path
) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        LEDGER_SOURCE: EXPECTED_LEDGER_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
        TREASURY_METADATA_SOURCE: EXPECTED_TREASURY_METADATA_SHA256,
    }
    for source, expected in expected_sources.items():
        if sha256(source) != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source.relative_to(REPO_ROOT)}")
    if sha256(btc_input) != EXPECTED_BTC_SHA256 or sha256(treasury_input) != EXPECTED_TREASURY_SHA256:
        raise ResearchReject("DATA_REJECT:INPUT_SHA256")
    parser = load_module("short_rate_frozen_h1_parser", PARSER_SOURCE)
    reference = load_module("short_rate_frozen_long_cash_reference", REFERENCE_SOURCE)
    ledger = load_module("short_rate_frozen_ledger", LEDGER_SOURCE)
    bars = parser.parse_rows(btc_input.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    btc_daily = ledger.build_daily_points(bars)
    treasury = load_treasury(treasury_input)
    design_output, design_raw = simulate_window(ledger, reference, bars, treasury, DESIGN)
    validation_output, validation_raw = simulate_window(ledger, reference, bars, treasury, VALIDATION)
    annual = {
        year: simulate_window(ledger, reference, bars, treasury, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        design_raw, validation_output, validation_raw, annual
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_US_TREASURY_3M_YIELD_EASING_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_US_TREASURY_3M_YIELD_EASING_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_YIELD42_63_84_LONG_CASH_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_input), "hourly_rows": len(bars), "complete_utc_days": len(btc_daily)},
            "treasury": {"path": treasury_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(treasury_input), "rows": len(treasury), "first_date": treasury[0].observation_date.isoformat(), "last_date": treasury[-1].observation_date.isoformat(), "present_vintage": True},
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "policy": {"primary_lookback_valid_business_observations": 63, "rejection_only_neighbors": [42, 84], "threshold": "STRICTLY_LESS_THAN_ZERO", "evaluation_frequency": "WEEKLY", "variants": 3},
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-capital present-vintage nominal three-month Treasury yield-change evidence only; a pass is not independent alpha, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--treasury-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.treasury_input.resolve(), args.manifest.resolve()]
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
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
