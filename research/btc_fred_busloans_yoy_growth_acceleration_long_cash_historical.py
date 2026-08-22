#!/usr/bin/env python3
"""Deterministic matched-capital audit for lagged BUSLOANS YoY acceleration."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime
from decimal import Decimal, InvalidOperation
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
EXPERIMENT_ID = "btc-fred-busloans-yoy-growth-acceleration-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_FRED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
BUSLOANS_SOURCE = REPO_ROOT / ".research-state/experiments/btc-fred-busloans-yoy-growth-acceleration-long-cash-historical-v1/inputs/busloans-monthly-2017-2024.csv"
BUSLOANS_BUNDLE = REPO_ROOT / ".research-state/experiments/btc-fred-busloans-yoy-growth-acceleration-long-cash-historical-v1/inputs/busloans-source-bundle.json"
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-busloans-yoy-growth-acceleration-long-cash-primary-prior.v1.json"
SPEC_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-busloans-yoy-growth-acceleration-source-feasibility.v1.spec.json"
PROBE_SOURCE = REPO_ROOT / "research/fred_busloans_source_probe.py"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-busloans-yoy-growth-acceleration-long-cash-v1.hypothesis.json"
EXPECTED_BINDINGS = {
    "normalized_busloans": (BUSLOANS_SOURCE, "19b7fcea00e959b7d64a2417cafaad9d81cf310b0c3df2694972bbcede4c6ae5"),
    "source_bundle": (BUSLOANS_BUNDLE, "28056b5fcd067e33a8f18235d1d252e0f5b383b384ea78bcbc15d242cb75f87e"),
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "primary_prior": (PRIOR_SOURCE, "93b0496705c1784809b8c175d6d9712b76a424e3025aa2b7758295c6e5eed574"),
    "source_spec": (SPEC_SOURCE, "6ea26038813586f4a3e73508096550b364793b98ec4c020fae6bd0841ee8d135"),
    "source_probe": (PROBE_SOURCE, "b47b0e85519f6ebf101807fc130da90769d26a905e8b7f7e1648febda8c57cea"),
    "hypothesis": (HYPOTHESIS_SOURCE, "c6d506613db49fbd22f05b102292921927a3f413a0e3ef355edc3d11f3a05cde"),
}
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1)) for year in range(2019, 2025)}
GATES = (
    "btc_sha256_and_52608_rows_match",
    "busloans_sha256_bundle_and_96_months_match",
    "hourly_lattice_monthly_continuity_and_conservative_availability_pass",
    "frozen_runner_kernel_ledger_reference_parser_prior_hypothesis_probe_and_spec_sha256_match",
    "single_variant_strict_adjacent_yoy_growth_acceleration_no_rescue_contract_pass",
    "design_signals_exactly_48_with_25_accelerating_and_23_nonaccelerating",
    "validation_signals_exactly_24_with_7_accelerating_and_17_nonaccelerating",
    "design_position_changes_between_6_and_48",
    "validation_position_changes_between_4_and_24",
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
    "validation_p90_hold_at_most_8760_hours",
    "validation_terminal_holding_age_at_most_8760_hours",
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


def add_months(value: str, count: int) -> str:
    year, month = map(int, value.split("-"))
    absolute = year * 12 + month - 1 + count
    return f"{absolute // 12:04d}-{absolute % 12 + 1:02d}"


def load_busloans(path: Path) -> list[tuple[str, D]]:
    rows: list[tuple[str, D]] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["observation_date", "busloans_billions_usd_seasonally_adjusted"]:
            raise ResearchReject(f"DATA_REJECT:BUSLOANS_COLUMNS:{reader.fieldnames}")
        for row in reader:
            try:
                month = row["observation_date"][:7]
                value = D(row["busloans_billions_usd_seasonally_adjusted"])
            except (KeyError, InvalidOperation) as error:
                raise ResearchReject("DATA_REJECT:BUSLOANS_PARSE") from error
            if row["observation_date"] != f"{month}-01" or value <= ZERO or value > D("100000"):
                raise ResearchReject(f"DATA_REJECT:BUSLOANS_VALUE:{month}")
            if rows and add_months(rows[-1][0], 1) != month:
                raise ResearchReject(f"DATA_REJECT:BUSLOANS_CONTINUITY:{rows[-1][0]}:{month}")
            rows.append((month, value))
    if len(rows) != 96 or rows[0][0] != "2017-01" or rows[-1][0] != "2024-12":
        raise ResearchReject("DATA_REJECT:BUSLOANS_INVENTORY")
    return rows


def targets_by_execution_time(rows: list[tuple[str, D]]) -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    changes: list[D] = []
    for index in range(13, len(rows)):
        month, current = rows[index]
        change = (current / rows[index - 12][1] - ONE) - (rows[index - 1][1] / rows[index - 13][1] - ONE)
        effective = datetime.fromisoformat(f"{add_months(month, 2)}-01T00:00:00")
        targets[effective] = change > ZERO
        changes.append(change)
    if len(targets) != 83:
        raise ResearchReject("FEATURE_REJECT:EVALUATION_COUNT")
    states = list(targets.values())
    return targets, {
        "formula": "(BUSLOANS_t/BUSLOANS_t_minus_12-1)-(BUSLOANS_t_minus_1/BUSLOANS_t_minus_13-1)",
        "threshold": "STRICTLY_GREATER_THAN_ZERO",
        "evaluation_count": len(states),
        "accelerating_count": sum(states),
        "nonaccelerating_count": sum(not state for state in states),
        "state_transition_count": sum(a != b for a, b in zip(states, states[1:], strict=False)),
        "minimum_adjacent_yoy_change": str(min(changes)),
        "maximum_adjacent_yoy_change": str(max(changes)),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def evaluate(
    design_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, D]],
    annual: dict[str, Any],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    dc = design_output["scenarios"]["NORMAL"]["candidate"]
    vc = validation_output["scenarios"]["NORMAL"]["candidate"]
    raw = {year: item[1] for year, item in annual.items()}
    normal_positive = sum(item["NORMAL"]["total_return"] > ZERO for item in raw.values())
    stress_positive = sum(item["STRESS"]["total_return"] > ZERO for item in raw.values())
    drawdown_nonworse = sum(item["NORMAL"]["drawdown"] <= item["NORMAL"]["buy_hold_drawdown"] for item in raw.values())
    calmar_breadth = sum(item["NORMAL"]["calmar"] >= D("0.80") * item["NORMAL"]["buy_hold_calmar"] for item in raw.values())
    upside_breadth = sum(item["NORMAL"]["upside_capture"] >= D("0.50") for item in raw.values())
    positives = [max(item["NORMAL"]["total_return"], ZERO) for item in raw.values()]
    positive_total = sum(positives, ZERO)
    top_year = max(positives, default=ZERO) / positive_total * HUNDRED if positive_total > ZERO else HUNDRED
    gates = {
        GATES[0]: True,
        GATES[1]: True,
        GATES[2]: True,
        GATES[3]: True,
        GATES[4]: True,
        GATES[5]: dc["signal_evaluation_count"] == 48 and dc["long_target_count"] == 25 and dc["cash_target_count"] == 23,
        GATES[6]: vc["signal_evaluation_count"] == 24 and vc["long_target_count"] == 7 and vc["cash_target_count"] == 17,
        GATES[7]: D("6") <= dn["position_changes"] <= D("48"),
        GATES[8]: D("4") <= vn["position_changes"] <= D("24"),
        GATES[9]: dn["total_return"] > ZERO,
        GATES[10]: ds["total_return"] > ZERO,
        GATES[11]: dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        GATES[12]: dn["upside_capture"] >= D("0.60"),
        GATES[13]: dn["calmar"] >= dn["buy_hold_calmar"],
        GATES[14]: vn["total_return"] > ZERO,
        GATES[15]: vs["total_return"] > ZERO,
        GATES[16]: vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        GATES[17]: vn["upside_capture"] >= D("0.60"),
        GATES[18]: vn["calmar"] >= D("0.80") * vn["buy_hold_calmar"],
        GATES[19]: vs["drawdown"] <= vn["drawdown"] + D("3"),
        GATES[20]: normal_positive >= 4,
        GATES[21]: stress_positive >= 4,
        GATES[22]: drawdown_nonworse >= 5,
        GATES[23]: calmar_breadth >= 4,
        GATES[24]: upside_breadth >= 4,
        GATES[25]: top_year <= D("60"),
        GATES[26]: vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        GATES[27]: vn["p90_hold"] <= D("8760"),
        GATES[28]: vn["terminal_holding_age"] <= D("8760"),
        GATES[29]: vn["terminal_liquidation_return"] > ZERO,
        GATES[30]: vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != GATES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_6",
        "stress_positive_years": f"{stress_positive}_of_6",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_6",
        "normal_calmar_at_least_80pct_buy_hold_years": f"{calmar_breadth}_of_6",
        "normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_6",
        "top_year_positive_total_return_contribution_pct": str(top_year),
    }
    return gates, [name for name, passed in gates.items() if not passed], breadth


def validate_manifest(manifest: dict[str, Any], runner: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    expected_dataset = {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_DATA_SHA256, "rows": EXPECTED_DATA_ROWS, "selection_cutoff": "2025-01-01T00:00:00"}
    if manifest.get("dataset") != expected_dataset:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    expected_policy = {
        "policy_id": "BTC_FRED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_V1",
        "availability": "OBSERVATION_MONTH_PLUS_TWO_MONTHS_FIRST_DAY_AT_00_00_UTC",
        "formula": "(BUSLOANS_t/BUSLOANS_t_minus_12-1)-(BUSLOANS_t_minus_1/BUSLOANS_t_minus_13-1)",
        "long_condition": "ADJACENT_YOY_GROWTH_CHANGE_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "OTHERWISE",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }
    if manifest.get("strategy_policy") != expected_policy:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    expected_costs = {"NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"}, "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"}}
    if manifest.get("cost_scenarios") != expected_costs:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    expected_windows = {"design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"}, "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"}, "annual_fair_reset_years": [2019, 2020, 2021, 2022, 2023, 2024]}
    if manifest.get("windows") != expected_windows:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set", {}).get("required") != list(GATES):
        raise ResearchReject("MANIFEST_REJECT:GATES")
    runner_binding = manifest.get("runner_binding", {})
    if runner_binding.get("path") != runner.relative_to(REPO_ROOT).as_posix() or runner_binding.get("sha256") != sha256(runner):
        raise ResearchReject("MANIFEST_REJECT:RUNNER")
    actual = {item.get("key"): (item.get("path"), item.get("sha256")) for item in manifest.get("source_bindings", [])}
    expected = {key: (path.relative_to(REPO_ROOT).as_posix(), digest) for key, (path, digest) in EXPECTED_BINDINGS.items()}
    if actual != expected:
        raise ResearchReject("MANIFEST_REJECT:BINDINGS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    for key, (path, expected) in EXPECTED_BINDINGS.items():
        if sha256(path) != expected:
            raise ResearchReject(f"SOURCE_REJECT:{key.upper()}_SHA256")
    runner = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner)
    kernel = load_module("busloans_kernel", KERNEL_SOURCE)
    ledger = load_module("busloans_ledger", LEDGER_SOURCE)
    reference = load_module("busloans_reference", REFERENCE_SOURCE)
    parser = load_module("busloans_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    busloans = load_busloans(BUSLOANS_SOURCE)
    targets, feature = targets_by_execution_time(busloans)
    design_output, design_result = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_result = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, targets, feature, window) for year, window in ANNUAL.items()}
    gates, failed, breadth = evaluate(design_output, design_result, validation_output, validation_result, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_FRED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_FRED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_LAGGED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_FAMILY_WITHOUT_LEVEL_LAG_THRESHOLD_DIRECTION_OR_SIZING_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner)},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars)},
        "feature": feature,
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: item[0] for year, item in annual.items()},
        "breadth_and_concentration": breadth,
        "primary_gates": gates,
        "failed_primary_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage C&I-loan replication only; a pass requires independent sealed OOS and never authorizes activation.",
        "scope_note": "No paid API, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
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
    if not input_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT) or not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists():
        raise ResearchReject("PATH_OR_OUTPUT_REJECT")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_primary_gates": result["failed_primary_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
