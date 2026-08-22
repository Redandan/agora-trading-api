#!/usr/bin/env python3
"""Deterministic historical screen for frozen U.S. M2 acceleration long/cash."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime
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
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-m2-liquidity-acceleration-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-m2-liquidity-acceleration-long-cash-v1.hypothesis.json"
SOURCE_BUNDLE = REPO_ROOT / ".research-state" / "experiments" / "btc-m2-liquidity-acceleration-long-cash-historical-v1" / "inputs" / "federal-reserve-h6-m2-source-bundle.json"
SOURCE_PROBE = REPO_ROOT / "research" / "federal_reserve_h6_m2_source_probe.py"
ERRATUM_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-m2-liquidity-acceleration-long-cash-v1-runner-field-binding-erratum.json"

EXPERIMENT_ID = "btc-m2-liquidity-acceleration-long-cash-historical-v2"
EXPECTED_MANIFEST_TYPE = "BTC_M2_LIQUIDITY_ACCELERATION_LONG_CASH_HISTORICAL_MANIFEST_V2"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_BTC_DAILY_ROWS = 2_192
EXPECTED_M2_SHA256 = "383a0d5b4a95c95147e420801cb0a153a663c57776d171dc4134b07a55ec6c7c"
EXPECTED_M2_ROWS = 96
EXPECTED_LEDGER_SHA256 = "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "128f64cf503274ceb9c94359944f28f2dcaf6ea55befef64b0ced04d041df6c9"
EXPECTED_HYPOTHESIS_SHA256 = "a1c14f2c8ad7dc8d3e9654f2cd9c646e1e8ec6251cab0925ec24e15e03f1342b"
EXPECTED_SOURCE_BUNDLE_SHA256 = "e570684156387e96c63243d9ce913c7a1a83ebf0edfe181f4546c4640950bb55"
EXPECTED_SOURCE_PROBE_SHA256 = "c33e1a9bcc74e9988d622c21e37b60916fe051ab1f6da17a9b818e48911bd1df"
EXPECTED_ERRATUM_SHA256 = "152fbd6243ec46d430d2170d1b26bf716fd538b6b506a9bdd466e9a21db8214e"

DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2019, 2025)
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_ECONOMIC_RUNNER": "research/btc_m2_liquidity_acceleration_long_cash_historical_v2.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_PATH_AND_BUY_HOLD_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_H1_PARSER": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-m2-liquidity-acceleration-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_HYPOTHESIS": "research_pipeline/examples/btc-m2-liquidity-acceleration-long-cash-v1.hypothesis.json",
    "SEALED_OFFICIAL_H6_SOURCE_BUNDLE": ".research-state/experiments/btc-m2-liquidity-acceleration-long-cash-historical-v1/inputs/federal-reserve-h6-m2-source-bundle.json",
    "SEALED_NORMALIZED_H6_M2_CORPUS": ".research-state/experiments/btc-m2-liquidity-acceleration-long-cash-historical-v1/inputs/federal-reserve-h6-m2-monthly-2017-2024.csv",
    "FROZEN_FAIL_CLOSED_H6_SOURCE_PROBE": "research/federal_reserve_h6_m2_source_probe.py",
    "SEALED_V1_PRE_ARTIFACT_FIELD_BINDING_ERRATUM": "research_pipeline/examples/btc-m2-liquidity-acceleration-long-cash-v1-runner-field-binding-erratum.json",
}
EXPECTED_GATE_NAMES = (
    "btc_sha256_and_52608_rows_match",
    "m2_sha256_and_96_months_match",
    "h6_source_bundle_sha256_matches",
    "hourly_lattice_monthly_continuity_and_conservative_availability_pass",
    "frozen_runner_ledger_reference_parser_prior_hypothesis_and_source_sha256_match",
    "single_variant_zero_threshold_no_rescue_contract_pass",
    "design_normal_total_return_pct_gt_0",
    "design_stress_total_return_pct_gt_0",
    "design_normal_drawdown_at_most_90pct_of_buy_hold",
    "design_normal_upside_capture_at_least_50pct",
    "design_normal_calmar_at_least_buy_hold",
    "design_normal_position_changes_between_2_and_48",
    "validation_normal_total_return_pct_gt_0",
    "validation_stress_total_return_pct_gt_0",
    "validation_normal_drawdown_at_most_90pct_of_buy_hold",
    "validation_normal_upside_capture_at_least_60pct",
    "validation_normal_calmar_at_least_75pct_of_buy_hold",
    "validation_normal_signal_evaluations_at_least_24",
    "validation_normal_position_changes_between_2_and_24",
    "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_total_return_at_least_5_of_6",
    "stress_positive_annual_total_return_at_least_5_of_6",
    "normal_annual_drawdown_non_worse_at_least_5_of_6",
    "normal_annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6",
    "normal_annual_upside_capture_at_least_50pct_at_least_5_of_6",
    "top_year_positive_total_return_contribution_at_most_60pct",
    "validation_top_positive_episode_contribution_at_most_60pct",
    "validation_p90_hold_at_most_8760_hours",
    "validation_terminal_holding_age_at_most_8760_hours",
    "validation_terminal_liquidation_adjusted_return_pct_gt_0",
    "validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


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


def next_month(value: str) -> str:
    year, month = (int(part) for part in value.split("-"))
    if month == 12:
        return f"{year + 1:04d}-01"
    return f"{year:04d}-{month + 1:02d}"


def add_months(value: str, count: int) -> str:
    result = value
    for _ in range(count):
        result = next_month(result)
    return result


def load_m2(path: Path) -> list[tuple[str, D]]:
    rows: list[tuple[str, D]] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["observation_month", "m2_billions_usd"]:
            raise ResearchReject(f"DATA_REJECT:M2_COLUMNS:{reader.fieldnames}")
        for row in reader:
            try:
                month = row["observation_month"]
                value = D(row["m2_billions_usd"])
            except (KeyError, InvalidOperation) as error:
                raise ResearchReject("DATA_REJECT:M2_PARSE") from error
            if value <= ZERO:
                raise ResearchReject(f"DATA_REJECT:M2_VALUE:{month}")
            if rows and next_month(rows[-1][0]) != month:
                raise ResearchReject(f"DATA_REJECT:M2_CONTINUITY:{rows[-1][0]}:{month}")
            rows.append((month, value))
    if len(rows) != EXPECTED_M2_ROWS:
        raise ResearchReject(f"DATA_REJECT:M2_ROWS:{len(rows)}")
    if rows[0][0] != "2017-01" or rows[-1][0] != "2024-12":
        raise ResearchReject("DATA_REJECT:M2_BOUNDARY")
    return rows


def targets_by_execution_time(rows: list[tuple[str, D]]) -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    accelerations: list[D] = []
    for index in range(13, len(rows)):
        month, current = rows[index]
        year_ago = rows[index - 12][1]
        previous = rows[index - 1][1]
        previous_year_ago = rows[index - 13][1]
        acceleration = (current / year_ago - ONE) - (previous / previous_year_ago - ONE)
        effective_time = datetime.fromisoformat(f"{add_months(month, 2)}-01T00:00:00")
        if effective_time in targets:
            raise ResearchReject(f"DATA_REJECT:DUPLICATE_EFFECTIVE_TIME:{effective_time}")
        targets[effective_time] = acceleration > ZERO
        accelerations.append(acceleration)
    if len(targets) != 83:
        raise ResearchReject(f"DATA_REJECT:M2_EVALUATIONS:{len(targets)}")
    states = list(targets.values())
    return targets, {
        "formula": "((M2_t/M2_t_minus_12)-1)-((M2_t_minus_1/M2_t_minus_13)-1)",
        "threshold": "STRICTLY_GREATER_THAN_ZERO",
        "evaluation_count": len(accelerations),
        "positive_count": sum(states),
        "nonpositive_count": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "minimum_acceleration": q(min(accelerations)),
        "maximum_acceleration": q(max(accelerations)),
        "median_acceleration": q(percentile(accelerations, D("0.5")) or ZERO),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def simulate_window(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    targets: dict[datetime, bool],
    feature: dict[str, Any],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, D]]]:
    output: dict[str, Any] = {"feature": feature, "scenarios": {}}
    raw: dict[str, dict[str, D]] = {}
    for scenario_name, (fee_rate, slippage) in SCENARIOS.items():
        scenario_output, scenario_raw = ledger.simulate_scenario(
            reference, bars, targets, window, fee_rate, slippage
        )
        output["scenarios"][scenario_name] = scenario_output
        raw[scenario_name] = scenario_raw
    return output, raw


def evaluate_gates(
    validation_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.50") for value in annual_raw.values())
    positive_year_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "m2_sha256_and_96_months_match": True,
        "h6_source_bundle_sha256_matches": True,
        "hourly_lattice_monthly_continuity_and_conservative_availability_pass": True,
        "frozen_runner_ledger_reference_parser_prior_hypothesis_and_source_sha256_match": True,
        "single_variant_zero_threshold_no_rescue_contract_pass": True,
        "design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "design_normal_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_normal_upside_capture_at_least_50pct": dn["upside_capture"] >= D("0.50"),
        "design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "design_normal_position_changes_between_2_and_48": D("2") <= dn["position_changes"] <= D("48"),
        "validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "validation_normal_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_normal_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "validation_normal_calmar_at_least_75pct_of_buy_hold": vn["calmar"] >= D("0.75") * vn["buy_hold_calmar"],
        "validation_normal_signal_evaluations_at_least_24": D(str(validation_output["scenarios"]["NORMAL"]["candidate"]["signal_evaluation_count"])) >= D("24"),
        "validation_normal_position_changes_between_2_and_24": D("2") <= vn["position_changes"] <= D("24"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_total_return_at_least_5_of_6": normal_positive >= 5,
        "stress_positive_annual_total_return_at_least_5_of_6": stress_positive >= 5,
        "normal_annual_drawdown_non_worse_at_least_5_of_6": drawdown_nonworse >= 5,
        "normal_annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6": calmar_breadth >= 4,
        "normal_annual_upside_capture_at_least_50pct_at_least_5_of_6": upside_breadth >= 5,
        "top_year_positive_total_return_contribution_at_most_60pct": top_year <= D("60"),
        "validation_top_positive_episode_contribution_at_most_60pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        "validation_p90_hold_at_most_8760_hours": vn["p90_hold"] <= D("8760"),
        "validation_terminal_holding_age_at_most_8760_hours": vn["terminal_holding_age"] <= D("8760"),
        "validation_terminal_liquidation_adjusted_return_pct_gt_0": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_6",
        "stress_positive_years": f"{stress_positive}_of_6",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_6",
        "normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_6",
        "normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_6",
        "top_year_positive_total_return_contribution_pct": q(top_year),
        "validation_top_positive_episode_contribution_pct": validation_output["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != {
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "expected_complete_utc_days": EXPECTED_BTC_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "m2": {"path": ".research-state/experiments/btc-m2-liquidity-acceleration-long-cash-historical-v1/inputs/federal-reserve-h6-m2-monthly-2017-2024.csv", "sha256": EXPECTED_M2_SHA256, "rows": EXPECTED_M2_ROWS, "first_month": "2017-01", "last_month": "2024-12", "present_vintage": True},
        "source_bundle": {"path": ".research-state/experiments/btc-m2-liquidity-acceleration-long-cash-historical-v1/inputs/federal-reserve-h6-m2-source-bundle.json", "sha256": EXPECTED_SOURCE_BUNDLE_SHA256},
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_M2_LIQUIDITY_ACCELERATION_LONG_CASH_V1",
        "source_series": "FEDERAL_RESERVE_H6_H6_M2_M2_M_SEASONALLY_ADJUSTED",
        "evaluation_frequency": "MONTHLY",
        "availability": "OBSERVATION_MONTH_PLUS_TWO_MONTHS_FIRST_DAY_AT_00_00_UTC",
        "formula": "((M2_t/M2_t_minus_12)-1)-((M2_t_minus_1/M2_t_minus_13)-1)",
        "long_condition": "ACCELERATION_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "ACCELERATION_LESS_THAN_OR_EQUAL_TO_ZERO",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "missing_observation_rule": "FAIL_CLOSED_NO_INTERPOLATION_OR_SOURCE_REPAIR",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
        "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2019, 2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "id": "BTC_M2_LIQUIDITY_ACCELERATION_MATCHED_CAPITAL_GATES_V1",
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


def build_output(btc_path: Path, m2_path: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        LEDGER_SOURCE: EXPECTED_LEDGER_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
        SOURCE_BUNDLE: EXPECTED_SOURCE_BUNDLE_SHA256,
        SOURCE_PROBE: EXPECTED_SOURCE_PROBE_SHA256,
        ERRATUM_SOURCE: EXPECTED_ERRATUM_SHA256,
    }
    for source, expected in expected_sources.items():
        if sha256(source) != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source.relative_to(REPO_ROOT)}")
    if sha256(btc_path) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    if sha256(m2_path) != EXPECTED_M2_SHA256:
        raise ResearchReject("DATA_REJECT:M2_SHA256")
    parser = load_module("m2_frozen_h1_parser", PARSER_SOURCE)
    ledger = load_module("m2_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("m2_frozen_path_reference", REFERENCE_SOURCE)
    bars = parser.parse_rows(btc_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    m2 = load_m2(m2_path)
    targets, feature = targets_by_execution_time(m2)
    design_output, design_raw = simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_raw = simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {
        year: simulate_window(ledger, reference, bars, targets, feature, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(validation_output, design_raw, validation_raw, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_M2_LIQUIDITY_ACCELERATION_LONG_CASH_HISTORICAL_RESULT_V2",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_M2_LIQUIDITY_ACCELERATION_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_M2_YOY_GROWTH_ACCELERATION_LONG_CASH_FAMILY_WITHOUT_TRANSFORMATION_LAG_THRESHOLD_OR_DIRECTION_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_path), "hourly_rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
            "m2": {"path": m2_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(m2_path), "rows": len(m2), "present_vintage": True},
            "source_bundle": {"path": SOURCE_BUNDLE.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(SOURCE_BUNDLE)},
        },
        "policy": {"feature": feature, "variants": 1, "availability_lag_months": 2, "cash_return": "0"},
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-capital present-vintage H.6 M2 evidence only. A pass is not independent alpha, point-in-time revision proof, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--m2-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.m2_input.resolve(), args.manifest.resolve()]
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
