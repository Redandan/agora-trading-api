#!/usr/bin/env python3
"""Deterministic matched-capital audit of one fixed UTC Monday BTC policy."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
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
EXPERIMENT_ID = "btc-utc-monday-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_UTC_MONDAY_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-utc-monday-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-utc-monday-long-cash-v1.hypothesis.json"
EXPECTED_BINDINGS = {
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "primary_prior": (PRIOR_SOURCE, "b3b544416811eb80bbaa7c461ac5820895b866913ea966c6edfdc5d737fe4e00"),
    "hypothesis": (HYPOTHESIS_SOURCE, "942ce04b60c039d94830595d78e7da92835665ec96a1d0f49b32b46a27ee6d7c"),
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
    "single_variant_fixed_utc_monday_no_rescue_contract_pass",
    "design_daily_signals_1461_with_208_monday_targets",
    "validation_daily_signals_731_with_105_monday_targets",
    "design_completed_monday_episodes_exactly_208",
    "validation_completed_monday_episodes_exactly_105",
    "design_gross_monday_mean_strictly_above_non_monday_mean",
    "validation_gross_monday_mean_strictly_above_non_monday_mean",
    "design_normal_total_return_positive",
    "design_stress_total_return_positive",
    "design_drawdown_at_most_50pct_of_buy_hold",
    "design_upside_capture_at_least_15pct",
    "design_calmar_at_least_75pct_of_buy_hold",
    "validation_normal_total_return_positive",
    "validation_stress_total_return_positive",
    "validation_drawdown_at_most_50pct_of_buy_hold",
    "validation_upside_capture_at_least_15pct",
    "validation_calmar_at_least_75pct_of_buy_hold",
    "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_return_at_least_4_of_6",
    "stress_positive_annual_return_at_least_4_of_6",
    "annual_drawdown_non_worse_6_of_6",
    "annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6",
    "annual_upside_capture_at_least_10pct_at_least_4_of_6",
    "top_year_positive_contribution_at_most_50pct",
    "validation_top_positive_episode_contribution_at_most_10pct",
    "validation_p90_hold_exactly_24_hours",
    "validation_terminal_position_false",
    "validation_terminal_liquidation_adjusted_return_positive",
    "validation_terminal_liquidation_cost_zero",
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


def _q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def _median(values: list[D]) -> D:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D("2")


def calendar_targets() -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    current = datetime(2019, 1, 1)
    end = datetime(2025, 1, 1)
    while current < end:
        targets[current] = current.weekday() == 0
        current += timedelta(days=1)
    states = list(targets.values())
    if len(targets) != 2_192 or sum(states) != 313:
        raise ResearchReject("FEATURE_REJECT:UTC_MONDAY_INVENTORY")
    return targets, {
        "factor_identity": "UTC_CALENDAR_DAY_WEEKDAY_IS_MONDAY_V1",
        "evaluation_count": len(targets),
        "monday_target_count": sum(states),
        "non_monday_target_count": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def weekday_return_diagnostic(
    bars: list[Any], window: tuple[datetime, datetime]
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    opens = {bar.open_time: bar.open for bar in bars if bar.open_time.hour == 0}
    monday: list[D] = []
    other: list[D] = []
    current = start
    while current < end:
        next_day = current + timedelta(days=1)
        if current not in opens or next_day not in opens:
            raise ResearchReject(f"DATA_REJECT:UTC_DAY_OPEN:{current.isoformat()}")
        value = opens[next_day] / opens[current] - ONE
        (monday if current.weekday() == 0 else other).append(value)
        current = next_day
    if not monday or not other:
        raise ResearchReject("METRIC_REJECT:WEEKDAY_SUPPORT")
    monday_mean = sum(monday, ZERO) / D(len(monday))
    other_mean = sum(other, ZERO) / D(len(other))
    return {
        "monday_observations": len(monday),
        "non_monday_observations": len(other),
        "monday_mean_open_to_open_return_pct": _q(monday_mean * HUNDRED),
        "non_monday_mean_open_to_open_return_pct": _q(other_mean * HUNDRED),
        "monday_median_open_to_open_return_pct": _q(_median(monday) * HUNDRED),
        "non_monday_median_open_to_open_return_pct": _q(_median(other) * HUNDRED),
        "monday_positive_share_pct": _q(D(sum(value > ZERO for value in monday)) / D(len(monday)) * HUNDRED),
        "non_monday_positive_share_pct": _q(D(sum(value > ZERO for value in other)) / D(len(other)) * HUNDRED),
    }, {"monday_mean": monday_mean, "non_monday_mean": other_mean}


def evaluate_gates(
    design_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, D]]]],
    design_weekday: dict[str, D],
    validation_weekday: dict[str, D],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    design_candidate = design_output["scenarios"]["NORMAL"]["candidate"]
    validation_candidate = validation_output["scenarios"]["NORMAL"]["candidate"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.10") for value in annual_raw.values())
    positive_year_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates = {
        "btc_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_2192_complete_utc_days_pass": True,
        "frozen_runner_kernel_ledger_reference_parser_prior_and_hypothesis_sha256_match": True,
        "single_variant_fixed_utc_monday_no_rescue_contract_pass": True,
        "design_daily_signals_1461_with_208_monday_targets": design_candidate["signal_evaluation_count"] == 1461 and design_candidate["long_target_count"] == 208,
        "validation_daily_signals_731_with_105_monday_targets": validation_candidate["signal_evaluation_count"] == 731 and validation_candidate["long_target_count"] == 105,
        "design_completed_monday_episodes_exactly_208": design_candidate["completed_episode_count"] == 208,
        "validation_completed_monday_episodes_exactly_105": validation_candidate["completed_episode_count"] == 105,
        "design_gross_monday_mean_strictly_above_non_monday_mean": design_weekday["monday_mean"] > design_weekday["non_monday_mean"],
        "validation_gross_monday_mean_strictly_above_non_monday_mean": validation_weekday["monday_mean"] > validation_weekday["non_monday_mean"],
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_50pct_of_buy_hold": dn["drawdown"] <= D("0.50") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_15pct": dn["upside_capture"] >= D("0.15"),
        "design_calmar_at_least_75pct_of_buy_hold": dn["calmar"] >= D("0.75") * dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_50pct_of_buy_hold": vn["drawdown"] <= D("0.50") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_15pct": vn["upside_capture"] >= D("0.15"),
        "validation_calmar_at_least_75pct_of_buy_hold": vn["calmar"] >= D("0.75") * vn["buy_hold_calmar"],
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_6": normal_positive >= 4,
        "stress_positive_annual_return_at_least_4_of_6": stress_positive >= 4,
        "annual_drawdown_non_worse_6_of_6": drawdown_nonworse == 6,
        "annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6": calmar_breadth >= 4,
        "annual_upside_capture_at_least_10pct_at_least_4_of_6": upside_breadth >= 4,
        "top_year_positive_contribution_at_most_50pct": top_year <= D("50"),
        "validation_top_positive_episode_contribution_at_most_10pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("10"),
        "validation_p90_hold_exactly_24_hours": vn["p90_hold"] == D("24"),
        "validation_terminal_position_false": validation_candidate["terminal_position"] is False,
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_zero": vn["terminal_liquidation_cost"] == ZERO,
    }
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, {
        "normal_positive_years": f"{normal_positive}_of_6",
        "stress_positive_years": f"{stress_positive}_of_6",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_6",
        "normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_6",
        "normal_upside_capture_at_least_10pct_years": f"{upside_breadth}_of_6",
        "top_year_positive_total_return_contribution_pct": _q(top_year),
    }


def validate_manifest(manifest: dict[str, Any], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS")
    if manifest.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": EXPECTED_DATA_SHA256,
        "hourly_rows": EXPECTED_DATA_ROWS,
        "expected_complete_utc_days": 2192,
        "first_open_time": "2019-01-01T00:00:00",
        "last_close_time": "2025-01-01T00:00:00",
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_UTC_MONDAY_LONG_CASH_V1",
        "decision_clock": "EACH_COMPLETE_UTC_DAY_BOUNDARY_H1_OPEN",
        "long_condition": "UTC_WEEKDAY_IS_MONDAY",
        "cash_condition": "UTC_WEEKDAY_IS_NOT_MONDAY",
        "holding_interval": "MONDAY_00_00_UTC_OPEN_TO_TUESDAY_00_00_UTC_OPEN",
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
        "id": "BTC_UTC_MONDAY_LONG_CASH_MATCHED_CAPITAL_GATES_V1",
        "required": list(EXPECTED_GATE_NAMES),
        "decision": "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    runner = manifest.get("runner_binding", {})
    if runner.get("path") != runner_path.relative_to(REPO_ROOT).as_posix() or runner.get("sha256") != sha256(runner_path):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    actual = {item.get("key"): (item.get("path"), item.get("sha256")) for item in bindings}
    expected = {key: (path.relative_to(REPO_ROOT).as_posix(), digest) for key, (path, digest) in EXPECTED_BINDINGS.items()}
    if actual != expected:
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
    kernel = load_module("frozen_monday_economic_kernel", KERNEL_SOURCE)
    ledger = load_module("frozen_monday_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("frozen_monday_path_reference", REFERENCE_SOURCE)
    parser = load_module("frozen_monday_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    targets, feature = calendar_targets()
    design_output, design_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, targets, feature, window) for year, window in ANNUAL.items()}
    design_weekday_output, design_weekday_raw = weekday_return_diagnostic(bars, DESIGN)
    validation_weekday_output, validation_weekday_raw = weekday_return_diagnostic(bars, VALIDATION)
    gates, failed, breadth = evaluate_gates(
        design_output, design_raw, validation_output, validation_raw, annual,
        design_weekday_raw, validation_weekday_raw,
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_UTC_MONDAY_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_UTC_MONDAY_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_UTC_MONDAY_00_00_TO_TUESDAY_00_00_LONG_CASH_FAMILY_WITHOUT_WEEKDAY_TIMEZONE_BOUNDARY_WEIGHT_DIRECTION_OR_GATE_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
        "source_bindings": {label: expected for label, (_, expected) in EXPECTED_BINDINGS.items()},
        "feature": feature,
        "policy": {"long_weekday": "MONDAY", "timezone": "UTC", "holding_hours": 24, "variants": 1, "cash_return": "0"},
        "weekday_return_diagnostic": {"design": design_weekday_output, "validation": validation_weekday_output},
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "primary_gates": gates,
        "failed_primary_gates": failed,
        "all_gates_pass": passed,
        "economics_opened": True,
        "oos_opened": False,
        "candidate_created": passed,
        "claim_boundary": "Historical post-literature replication on one sealed venue path only. A pass remains REPORTED_NOT_ACTIVATED, requires independent sealed OOS and never authorizes Trading.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists():
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_primary_gates": result["failed_primary_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
