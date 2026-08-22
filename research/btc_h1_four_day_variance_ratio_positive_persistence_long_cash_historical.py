#!/usr/bin/env python3
"""Deterministic matched-capital audit for a frozen daily variance-ratio filter."""

from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_SOURCE = REPO_ROOT / ".research-state/java-parity/selection-2019-2024.tsv"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
SUPPORT_PROBE_SOURCE = REPO_ROOT / "research/btc_h1_four_day_variance_ratio_support_v2.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-primary-prior.v1.json"
SPEC_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome.v2.spec.json"
ERRATUM_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome-output-boolean-erratum.v1.json"
INVALIDATION_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome-v1-invalidation.json"
V2_RECURSION_ERRATUM_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome-v2-recursion-erratum.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-v1.hypothesis.json"
SUPPORT_RESULT_SOURCE = REPO_ROOT / ".research-state/experiments/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-historical-v1/inputs/feature-support.v2.json"

EXPERIMENT_ID = "btc-h1-four-day-variance-ratio-positive-persistence-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_FEATURE_EVALUATIONS = 2_164
EXPECTED_FEATURE_LATTICE_SHA256 = "067fa12477292207e843fe31f40c2a4dd10b08a7d860c8f62a358ec4becbffc8"
EXPECTED_BINDINGS = {
    "frozen_long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "frozen_path_and_buy_hold_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "frozen_h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "sealed_primary_prior": (PRIOR_SOURCE, "d692e290fead48f5ff09ad8d39375cee4cececc85b01328fd34eb748fe2d6111"),
    "frozen_preoutcome_spec_v2": (SPEC_SOURCE, "69534ac53d7370a7e0b326c4927903aaded154d4661c4f2d8538aa0a843fba43"),
    "frozen_support_probe_v2": (SUPPORT_PROBE_SOURCE, "90a085f2700b4a4965088ecca2ceb9ce432d45ed14e549f0866084d04261f032"),
    "sealed_support_result_v2": (SUPPORT_RESULT_SOURCE, "b102c7d421fdd1593b9904684a38e730f2269b91f05c03a63ea9c025de6ef293"),
    "recorded_output_boolean_erratum": (ERRATUM_SOURCE, "f14239d1c0d808baa3e3e8e4646a6b741e502deefee755f16d0e59fb3ac7709b"),
    "recorded_v1_precision_invalidation": (INVALIDATION_SOURCE, "5a598ca31e747a42c7e99d7d421022b1c505dd4168b297764942fc9a895e1e48"),
    "recorded_v2_recursion_erratum": (V2_RECURSION_ERRATUM_SOURCE, "f876fac7af59b33cc2cd6b6eeb17414e5c6989bd33eab464671f90f3e793c385"),
    "frozen_schema_valid_hypothesis": (HYPOTHESIS_SOURCE, "2b81b5eff519002abb87dc87fd04551bbe1ed92f91474f4c48750bdfd92e02df"),
}

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
EXPECTED_SUPPORT = {
    "design": {"evaluations": 1432, "direction_positive": 800, "joint_long": 209, "joint_cash": 1223, "joint_transitions": 82, "trend_parent_vetoes": 591},
    "validation": {"evaluations": 731, "direction_positive": 463, "joint_long": 208, "joint_cash": 523, "joint_transitions": 60, "trend_parent_vetoes": 255},
}
GATES = (
    "btc_sha256_52608_rows_and_2192_complete_days_match",
    "preoutcome_support_result_pass_hash_and_feature_lattice_match",
    "frozen_ledger_reference_parser_prior_spec_probe_erratum_hypothesis_and_runner_hashes_match",
    "single_variant_28_day_direction_four_day_ratio_one_threshold_no_rescue_contract_pass",
    "design_support_exactly_1432_evaluations_800_trend_long_209_joint_long_591_vetoes_82_transitions",
    "validation_support_exactly_731_evaluations_463_trend_long_208_joint_long_255_vetoes_60_transitions",
    "each_annual_support_summary_matches_sealed_preoutcome_result",
    "design_candidate_position_changes_between_20_and_200",
    "validation_candidate_position_changes_between_10_and_150",
    "design_candidate_normal_total_return_positive",
    "design_candidate_stress_total_return_positive",
    "design_candidate_drawdown_at_most_90pct_of_buy_hold",
    "design_candidate_upside_capture_at_least_50pct",
    "design_candidate_calmar_at_least_buy_hold",
    "validation_candidate_normal_total_return_positive",
    "validation_candidate_stress_total_return_positive",
    "validation_candidate_drawdown_at_most_90pct_of_buy_hold",
    "validation_candidate_upside_capture_at_least_60pct",
    "validation_candidate_calmar_at_least_80pct_of_buy_hold",
    "validation_candidate_stress_drawdown_no_more_than_normal_plus_3pp",
    "design_candidate_normal_total_return_strictly_above_trend_parent",
    "design_candidate_stress_total_return_strictly_above_trend_parent",
    "design_candidate_drawdown_non_worse_than_trend_parent",
    "design_candidate_calmar_at_least_trend_parent",
    "validation_candidate_normal_total_return_strictly_above_trend_parent",
    "validation_candidate_stress_total_return_strictly_above_trend_parent",
    "validation_candidate_drawdown_non_worse_than_trend_parent",
    "validation_candidate_calmar_at_least_trend_parent",
    "candidate_normal_positive_annual_return_at_least_4_of_6",
    "candidate_stress_positive_annual_return_at_least_4_of_6",
    "candidate_annual_drawdown_non_worse_than_buy_hold_at_least_5_of_6",
    "candidate_annual_calmar_at_least_80pct_buy_hold_at_least_4_of_6",
    "candidate_annual_upside_capture_at_least_50pct_at_least_4_of_6",
    "candidate_normal_annual_total_return_above_trend_parent_at_least_4_of_6",
    "candidate_stress_annual_total_return_above_trend_parent_at_least_4_of_6",
    "candidate_annual_drawdown_non_worse_than_trend_parent_at_least_5_of_6",
    "candidate_annual_calmar_at_least_trend_parent_at_least_4_of_6",
    "candidate_top_year_positive_return_contribution_at_most_60pct",
    "positive_annual_incremental_return_over_trend_parent_top_year_at_most_60pct",
    "validation_candidate_top_positive_episode_contribution_at_most_60pct",
    "validation_candidate_p90_hold_at_most_8760_hours",
    "validation_candidate_terminal_holding_age_at_most_8760_hours",
    "validation_candidate_terminal_liquidation_adjusted_return_positive",
    "validation_candidate_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path | bytes) -> str:
    payload = path if isinstance(path, bytes) else path.read_bytes()
    return hashlib.sha256(payload).hexdigest()


def q(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def feature_targets(
    support: ModuleType, bars: list[Any]
) -> tuple[dict[datetime, bool], dict[datetime, bool], list[Any], str]:
    daily = support.base.build_daily_closes(bars)
    states = support.build_feature_states(daily)
    lattice = [
        {
            "effective_time": state.effective_time.isoformat(),
            "variance_ratio": str(state.variance_ratio),
            "direction_positive": state.direction_positive,
            "persistence": state.persistence,
            "joint_long": state.joint_long,
        }
        for state in states
    ]
    lattice_hash = sha256(support.base.canonical_bytes(lattice))
    candidate = {state.effective_time: state.joint_long for state in states}
    parent = {state.effective_time: state.direction_positive for state in states}
    return candidate, parent, states, lattice_hash


def simulate_policy(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    targets: dict[datetime, bool],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, D]]]:
    output: dict[str, Any] = {"scenarios": {}}
    raw: dict[str, dict[str, D]] = {}
    for name, (fee, slippage) in SCENARIOS.items():
        scenario_output, scenario_raw = ledger.simulate_scenario(
            reference, bars, targets, window, fee, slippage
        )
        output["scenarios"][name] = scenario_output
        raw[name] = scenario_raw
    return output, raw


def simulate_comparison(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    candidate_targets: dict[datetime, bool],
    parent_targets: dict[datetime, bool],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]:
    candidate_output, candidate_raw = simulate_policy(
        ledger, reference, bars, candidate_targets, window
    )
    parent_output, parent_raw = simulate_policy(
        ledger, reference, bars, parent_targets, window
    )
    comparisons = {
        name: {
            "candidate_minus_trend_parent_total_return_pp": q(candidate_raw[name]["total_return"] - parent_raw[name]["total_return"]),
            "candidate_minus_trend_parent_drawdown_pp": q(candidate_raw[name]["drawdown"] - parent_raw[name]["drawdown"]),
            "candidate_minus_trend_parent_calmar": q(candidate_raw[name]["calmar"] - parent_raw[name]["calmar"]),
        }
        for name in SCENARIOS
    }
    return {
        "candidate": candidate_output,
        "trend_parent": parent_output,
        "candidate_vs_trend_parent": comparisons,
    }, {"candidate": candidate_raw, "trend_parent": parent_raw}


def concentration(values: list[D]) -> D:
    positives = [max(value, ZERO) for value in values]
    total = sum(positives, ZERO)
    return max(positives, default=ZERO) / total * HUNDRED if total > ZERO else HUNDRED


def evaluate(
    support: ModuleType,
    support_result: dict[str, Any],
    states: list[Any],
    lattice_hash: str,
    design: dict[str, dict[str, dict[str, D]]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, dict[str, D]]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, dict[str, D]]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn = design["candidate"]["NORMAL"]
    ds = design["candidate"]["STRESS"]
    dpn = design["trend_parent"]["NORMAL"]
    dps = design["trend_parent"]["STRESS"]
    vn = validation["candidate"]["NORMAL"]
    vs = validation["candidate"]["STRESS"]
    vpn = validation["trend_parent"]["NORMAL"]
    vps = validation["trend_parent"]["STRESS"]
    annual_raw = {year: item[1] for year, item in annual.items()}

    normal_positive = sum(item["candidate"]["NORMAL"]["total_return"] > ZERO for item in annual_raw.values())
    stress_positive = sum(item["candidate"]["STRESS"]["total_return"] > ZERO for item in annual_raw.values())
    dd_buyhold = sum(item["candidate"]["NORMAL"]["drawdown"] <= item["candidate"]["NORMAL"]["buy_hold_drawdown"] for item in annual_raw.values())
    calmar_buyhold = sum(item["candidate"]["NORMAL"]["calmar"] >= D("0.80") * item["candidate"]["NORMAL"]["buy_hold_calmar"] for item in annual_raw.values())
    upside_buyhold = sum(item["candidate"]["NORMAL"]["upside_capture"] >= D("0.50") for item in annual_raw.values())
    normal_parent_wins = sum(item["candidate"]["NORMAL"]["total_return"] > item["trend_parent"]["NORMAL"]["total_return"] for item in annual_raw.values())
    stress_parent_wins = sum(item["candidate"]["STRESS"]["total_return"] > item["trend_parent"]["STRESS"]["total_return"] for item in annual_raw.values())
    dd_parent_nonworse = sum(item["candidate"]["NORMAL"]["drawdown"] <= item["trend_parent"]["NORMAL"]["drawdown"] for item in annual_raw.values())
    calmar_parent_nonworse = sum(item["candidate"]["NORMAL"]["calmar"] >= item["trend_parent"]["NORMAL"]["calmar"] for item in annual_raw.values())
    candidate_years = [item["candidate"]["NORMAL"]["total_return"] for item in annual_raw.values()]
    incremental_years = [item["candidate"]["NORMAL"]["total_return"] - item["trend_parent"]["NORMAL"]["total_return"] for item in annual_raw.values()]
    candidate_top_year = concentration(candidate_years)
    incremental_top_year = concentration(incremental_years)

    design_support = support.base.window_summary(states, *DESIGN)
    validation_support = support.base.window_summary(states, *VALIDATION)
    annual_support = {
        year: support.base.window_summary(states, *window) for year, window in ANNUAL.items()
    }
    expected_annual = support_result["support"]["annual"]
    gates = {
        GATES[0]: True,
        GATES[1]: support_result.get("support_pass") is True and lattice_hash == EXPECTED_FEATURE_LATTICE_SHA256,
        GATES[2]: True,
        GATES[3]: True,
        GATES[4]: all(design_support.get(key) == value for key, value in EXPECTED_SUPPORT["design"].items()),
        GATES[5]: all(validation_support.get(key) == value for key, value in EXPECTED_SUPPORT["validation"].items()),
        GATES[6]: annual_support == expected_annual,
        GATES[7]: D("20") <= dn["position_changes"] <= D("200"),
        GATES[8]: D("10") <= vn["position_changes"] <= D("150"),
        GATES[9]: dn["total_return"] > ZERO,
        GATES[10]: ds["total_return"] > ZERO,
        GATES[11]: dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        GATES[12]: dn["upside_capture"] >= D("0.50"),
        GATES[13]: dn["calmar"] >= dn["buy_hold_calmar"],
        GATES[14]: vn["total_return"] > ZERO,
        GATES[15]: vs["total_return"] > ZERO,
        GATES[16]: vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        GATES[17]: vn["upside_capture"] >= D("0.60"),
        GATES[18]: vn["calmar"] >= D("0.80") * vn["buy_hold_calmar"],
        GATES[19]: vs["drawdown"] <= vn["drawdown"] + D("3"),
        GATES[20]: dn["total_return"] > dpn["total_return"],
        GATES[21]: ds["total_return"] > dps["total_return"],
        GATES[22]: dn["drawdown"] <= dpn["drawdown"],
        GATES[23]: dn["calmar"] >= dpn["calmar"],
        GATES[24]: vn["total_return"] > vpn["total_return"],
        GATES[25]: vs["total_return"] > vps["total_return"],
        GATES[26]: vn["drawdown"] <= vpn["drawdown"],
        GATES[27]: vn["calmar"] >= vpn["calmar"],
        GATES[28]: normal_positive >= 4,
        GATES[29]: stress_positive >= 4,
        GATES[30]: dd_buyhold >= 5,
        GATES[31]: calmar_buyhold >= 4,
        GATES[32]: upside_buyhold >= 4,
        GATES[33]: normal_parent_wins >= 4,
        GATES[34]: stress_parent_wins >= 4,
        GATES[35]: dd_parent_nonworse >= 5,
        GATES[36]: calmar_parent_nonworse >= 4,
        GATES[37]: candidate_top_year <= D("60"),
        GATES[38]: incremental_top_year <= D("60"),
        GATES[39]: vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        GATES[40]: vn["p90_hold"] <= D("8760"),
        GATES[41]: vn["terminal_holding_age"] <= D("8760"),
        GATES[42]: vn["terminal_liquidation_return"] > ZERO,
        GATES[43]: vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != GATES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    breadth = {
        "candidate_normal_positive_years": f"{normal_positive}_of_6",
        "candidate_stress_positive_years": f"{stress_positive}_of_6",
        "candidate_drawdown_non_worse_than_buy_hold_years": f"{dd_buyhold}_of_6",
        "candidate_calmar_at_least_80pct_buy_hold_years": f"{calmar_buyhold}_of_6",
        "candidate_upside_capture_at_least_50pct_years": f"{upside_buyhold}_of_6",
        "candidate_normal_total_return_above_trend_parent_years": f"{normal_parent_wins}_of_6",
        "candidate_stress_total_return_above_trend_parent_years": f"{stress_parent_wins}_of_6",
        "candidate_drawdown_non_worse_than_trend_parent_years": f"{dd_parent_nonworse}_of_6",
        "candidate_calmar_at_least_trend_parent_years": f"{calmar_parent_nonworse}_of_6",
        "candidate_top_year_positive_return_contribution_pct": q(candidate_top_year),
        "positive_annual_incremental_return_over_trend_parent_top_year_pct": q(incremental_top_year),
        "validation_candidate_top_positive_episode_contribution_pct": validation_output["candidate"]["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    return gates, [name for name, passed in gates.items() if not passed], breadth


def validate_manifest(manifest: dict[str, Any], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": EXPECTED_DATA_SHA256,
        "hourly_rows": EXPECTED_DATA_ROWS,
        "complete_utc_days": EXPECTED_DAILY_ROWS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    expected_policy = {
        "policy_id": "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_LONG_CASH_V1",
        "feature_window": "29_COMPLETE_UTC_DAY_CLOSES_PRODUCING_28_ONE_DAY_LOG_RETURNS",
        "variance_ratio": "UNBIASED_SAMPLE_VARIANCE_OF_25_OVERLAPPING_FOUR_DAY_LOG_RETURN_SUMS_DIVIDED_BY_FOUR_TIMES_UNBIASED_SAMPLE_VARIANCE_OF_28_ONE_DAY_LOG_RETURNS",
        "persistence_condition": "VARIANCE_RATIO_STRICTLY_GREATER_THAN_ONE",
        "direction_condition": "LATEST_CLOSE_STRICTLY_GREATER_THAN_CLOSE_28_COMPLETE_DAYS_EARLIER",
        "candidate_long_condition": "PERSISTENCE_AND_DIRECTION_BOTH_TRUE",
        "trend_parent_long_condition": "DIRECTION_TRUE_WITHOUT_VARIANCE_RATIO_FILTER",
        "execution": "IMMEDIATELY_FOLLOWING_H1_OPEN_AT_COMPLETE_DAY_CLOSE_TIME",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }
    if manifest.get("strategy_policy") != expected_policy:
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
        "id": "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_MATCHED_CAPITAL_GATES_V1",
        "required": list(GATES),
        "decision": "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    by_role = {item.get("role"): item for item in bindings}
    if set(by_role) != set(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for role, (path, expected_hash) in EXPECTED_BINDINGS.items():
        binding = by_role[role]
        if binding.get("path") != path.relative_to(REPO_ROOT).as_posix() or binding.get("sha256") != expected_hash or sha256(path) != expected_hash:
            raise ResearchReject(f"BINDING_REJECT:{role}")
    if manifest.get("runner_binding") != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
        "launch": "DIRECT_PYTHON_NO_SPRING_NO_SERVER_NO_DATABASE",
    }:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    runner_path = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner_path)
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    parser = load_module("variance_ratio_economic_h1_parser", PARSER_SOURCE)
    ledger = load_module("variance_ratio_economic_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("variance_ratio_economic_path_reference", REFERENCE_SOURCE)
    support = load_module("variance_ratio_frozen_support", SUPPORT_PROBE_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    support_result = json.loads(SUPPORT_RESULT_SOURCE.read_text(encoding="utf-8"))
    candidate_targets, parent_targets, states, lattice_hash = feature_targets(support, bars)
    if len(states) != EXPECTED_FEATURE_EVALUATIONS or lattice_hash != EXPECTED_FEATURE_LATTICE_SHA256:
        raise ResearchReject("FEATURE_REJECT:SEALED_LATTICE_MISMATCH")
    getcontext().prec = 34
    design_output, design_raw = simulate_comparison(ledger, reference, bars, candidate_targets, parent_targets, DESIGN)
    validation_output, validation_raw = simulate_comparison(ledger, reference, bars, candidate_targets, parent_targets, VALIDATION)
    annual = {
        year: simulate_comparison(ledger, reference, bars, candidate_targets, parent_targets, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate(support, support_result, states, lattice_hash, design_raw, validation_output, validation_raw, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_28_DAY_DIRECTION_FOUR_DAY_VARIANCE_RATIO_ABOVE_ONE_LONG_CASH_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path)},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(input_path), "hourly_rows": len(bars), "complete_utc_days": EXPECTED_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "policy": {"feature_lattice_sha256": lattice_hash, "feature_evaluations": len(states), "variants": 1, "factor_ablation_parent": "28_COMPLETE_DAY_POSITIVE_TREND_LONG_CASH", "cash_return": "0"},
        "preoutcome_support": support_result["support"],
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: item[0] for year, item in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-capital single-venue evidence only. The variance-ratio filter must add value over the simpler 28-day positive-trend parent; a pass is not independent OOS alpha, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    if not input_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
