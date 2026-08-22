#!/usr/bin/env python3
"""Deterministic matched-capital audit for fixed November-April BTC exposure."""

from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal
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
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-halloween-november-april-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_HALLOWEEN_NOVEMBER_APRIL_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-halloween-november-april-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-halloween-november-april-long-cash-v1.hypothesis.json"
ERRATUM_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-halloween-november-april-long-cash-post-outcome-signal-count-binding-erratum.v1.json"
EXPECTED_BINDINGS = {
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "primary_prior": (PRIOR_SOURCE, "f97c34876bf180a046df1e65e4608884a5e870604735536eece80942b46776e1"),
    "hypothesis": (HYPOTHESIS_SOURCE, "f1e7dc9a0ab9809bb663d7407c2ac822f90d6a82d533b5d7676b9aff4712dadf"),
    "signal_count_erratum": (ERRATUM_SOURCE, "b26c02322ae2c02c7361f4b11ae38827b8bb00898529686bca0381998880f579"),
}
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2019, 2025)
}
EXPECTED_GATE_NAMES = (
    "btc_sha256_and_52608_rows_match",
    "hourly_lattice_and_2192_complete_utc_days_pass",
    "frozen_runner_kernel_ledger_reference_parser_prior_and_hypothesis_sha256_match",
    "single_variant_fixed_utc_november_april_no_rescue_contract_pass",
    "design_monthly_signals_exactly_48_with_24_long_and_24_cash_targets",
    "validation_monthly_signals_exactly_24_with_12_long_and_12_cash_targets",
    "design_position_changes_exactly_9",
    "validation_position_changes_exactly_5",
    "design_normal_total_return_positive",
    "design_stress_total_return_positive",
    "design_drawdown_at_most_90pct_of_buy_hold",
    "design_upside_capture_at_least_60pct",
    "design_calmar_at_least_buy_hold",
    "validation_normal_total_return_positive",
    "validation_stress_total_return_positive",
    "validation_drawdown_at_most_90pct_of_buy_hold",
    "validation_upside_capture_at_least_60pct",
    "validation_calmar_at_least_80pct_of_buy_hold",
    "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_return_at_least_4_of_6",
    "stress_positive_annual_return_at_least_4_of_6",
    "annual_drawdown_non_worse_at_least_5_of_6",
    "annual_calmar_at_least_80pct_buy_hold_at_least_4_of_6",
    "annual_upside_capture_at_least_50pct_at_least_4_of_6",
    "top_year_positive_contribution_at_most_60pct",
    "validation_top_positive_episode_contribution_at_most_60pct",
    "validation_p90_hold_at_most_4400_hours",
    "validation_terminal_holding_age_at_most_1500_hours",
    "validation_terminal_liquidation_adjusted_return_positive",
    "validation_terminal_liquidation_cost_at_most_1pp",
)


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


def calendar_targets() -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    current = datetime(2019, 1, 1)
    end = datetime(2025, 1, 1)
    while current < end:
        targets[current] = current.month in {11, 12, 1, 2, 3, 4}
        current = datetime(current.year + 1, 1, 1) if current.month == 12 else datetime(current.year, current.month + 1, 1)
    states = list(targets.values())
    if len(targets) != 72 or sum(states) != 36 or sum(not state for state in states) != 36:
        raise ResearchReject("FEATURE_REJECT:CALENDAR_INVENTORY")
    return targets, {
        "factor_identity": "UTC_CALENDAR_MONTH_IS_NOVEMBER_DECEMBER_JANUARY_FEBRUARY_MARCH_OR_APRIL_V1",
        "evaluation_count": len(targets),
        "long_target_count": sum(states),
        "cash_target_count": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def evaluate_gates(
    design_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    design_candidate = design_output["scenarios"]["NORMAL"]["candidate"]
    validation_candidate = validation_output["scenarios"]["NORMAL"]["candidate"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.80") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.50") for value in annual_raw.values())
    positive_year_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates = {
        "btc_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_2192_complete_utc_days_pass": True,
        "frozen_runner_kernel_ledger_reference_parser_prior_and_hypothesis_sha256_match": True,
        "single_variant_fixed_utc_november_april_no_rescue_contract_pass": True,
        "design_monthly_signals_exactly_48_with_24_long_and_24_cash_targets": design_candidate["signal_evaluation_count"] == 48 and design_candidate["long_target_count"] == 24 and design_candidate["cash_target_count"] == 24,
        "validation_monthly_signals_exactly_24_with_12_long_and_12_cash_targets": validation_candidate["signal_evaluation_count"] == 24 and validation_candidate["long_target_count"] == 12 and validation_candidate["cash_target_count"] == 12,
        "design_position_changes_exactly_9": dn["position_changes"] == D("9"),
        "validation_position_changes_exactly_5": vn["position_changes"] == D("5"),
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_60pct": dn["upside_capture"] >= D("0.60"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "validation_calmar_at_least_80pct_of_buy_hold": vn["calmar"] >= D("0.80") * vn["buy_hold_calmar"],
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_6": normal_positive >= 4,
        "stress_positive_annual_return_at_least_4_of_6": stress_positive >= 4,
        "annual_drawdown_non_worse_at_least_5_of_6": drawdown_nonworse >= 5,
        "annual_calmar_at_least_80pct_buy_hold_at_least_4_of_6": calmar_breadth >= 4,
        "annual_upside_capture_at_least_50pct_at_least_4_of_6": upside_breadth >= 4,
        "top_year_positive_contribution_at_most_60pct": top_year <= D("60"),
        "validation_top_positive_episode_contribution_at_most_60pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        "validation_p90_hold_at_most_4400_hours": vn["p90_hold"] <= D("4400"),
        "validation_terminal_holding_age_at_most_1500_hours": vn["terminal_holding_age"] <= D("1500"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, {
        "normal_positive_years": f"{normal_positive}_of_6",
        "stress_positive_years": f"{stress_positive}_of_6",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_6",
        "normal_calmar_at_least_80pct_buy_hold_years": f"{calmar_breadth}_of_6",
        "normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_6",
        "top_year_positive_total_return_contribution_pct": _q(top_year),
    }


def _q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def validate_manifest(manifest: dict[str, Any], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS")
    if manifest.get("post_outcome_implementation_erratum") != {
        "path": "research_pipeline/examples/btc-halloween-november-april-long-cash-post-outcome-signal-count-binding-erratum.v1.json",
        "sha256": "b26c02322ae2c02c7361f4b11ae38827b8bb00898529686bca0381998880f579",
        "scope": "SIGNAL_COUNT_SERIALIZED_VIEW_BINDING_ONLY_NO_POLICY_COST_GATE_OR_DECISION_CHANGE",
    }:
        raise ResearchReject("MANIFEST_REJECT:POST_OUTCOME_IMPLEMENTATION_ERRATUM")
    dataset = manifest.get("dataset", {})
    if dataset.get("path") != ".research-state/java-parity/selection-2019-2024.tsv" or dataset.get("sha256") != EXPECTED_DATA_SHA256 or dataset.get("rows") != EXPECTED_DATA_ROWS:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    policy = manifest.get("strategy_policy", {})
    expected_policy = {
        "policy_id": "BTC_HALLOWEEN_NOVEMBER_APRIL_LONG_CASH_V1",
        "decision_clock": "FIRST_H1_OPEN_AT_EACH_UTC_CALENDAR_MONTH_BOUNDARY",
        "long_months": [11, 12, 1, 2, 3, 4],
        "cash_months": [5, 6, 7, 8, 9, 10],
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }
    if policy != expected_policy:
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
    required = manifest.get("gate_set", {}).get("required")
    if required != list(EXPECTED_GATE_NAMES) or manifest.get("gate_set", {}).get("decision") != "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING":
        raise ResearchReject("MANIFEST_REJECT:GATES")
    runner = manifest.get("runner_binding", {})
    if runner.get("path") != runner_path.relative_to(REPO_ROOT).as_posix() or runner.get("sha256") != sha256(runner_path):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    actual_bindings = {item.get("key"): (item.get("path"), item.get("sha256")) for item in bindings}
    expected_bindings = {key: (path.relative_to(REPO_ROOT).as_posix(), expected) for key, (path, expected) in EXPECTED_BINDINGS.items()}
    if actual_bindings != expected_bindings:
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    for label, (path, expected) in EXPECTED_BINDINGS.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")
    runner_path = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner_path)
    kernel = load_module("frozen_halloween_economic_kernel", KERNEL_SOURCE)
    ledger = load_module("frozen_halloween_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("frozen_halloween_path_reference", REFERENCE_SOURCE)
    parser = load_module("frozen_halloween_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    targets, feature = calendar_targets()
    design_output, design_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, targets, feature, window) for year, window in ANNUAL.items()}
    gates, failed, breadth = evaluate_gates(design_output, design_raw, validation_output, validation_raw, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_HALLOWEEN_NOVEMBER_APRIL_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_HALLOWEEN_NOVEMBER_APRIL_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_FIXED_UTC_NOVEMBER_APRIL_LONG_CASH_FAMILY_WITHOUT_MONTH_BOUNDARY_DIRECTION_SIZING_OR_INTERACTION_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
        "source_bindings": {label: expected for label, (_, expected) in EXPECTED_BINDINGS.items()},
        "feature": feature,
        "policy": {"long_months": [11, 12, 1, 2, 3, 4], "cash_months": [5, 6, 7, 8, 9, 10], "variants": 1, "cash_return": "0"},
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "primary_gates": gates,
        "failed_primary_gates": failed,
        "all_gates_pass": passed,
        "economics_opened": True,
        "oos_opened": False,
        "candidate_created": passed,
        "claim_boundary": "Historical replication on an outcome-contaminated literature family only; a pass requires separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_path = args.input.resolve(); manifest_path = args.manifest.resolve(); output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists():
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":")); stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_primary_gates": result["failed_primary_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
