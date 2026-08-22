#!/usr/bin/env python3
"""Deterministic matched-capital audit for lagged CPI YoY disinflation."""

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


D = Decimal; ZERO = D("0"); ONE = D("1"); HUNDRED = D("100")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-fred-cpiaucsl-yoy-disinflation-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_FRED_CPIAUCSL_YOY_DISINFLATION_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
CPI_SOURCE = REPO_ROOT / ".research-state/experiments/btc-fred-cpiaucsl-yoy-disinflation-long-cash-historical-v1/inputs/cpiaucsl-monthly-2017-2024.csv"
CPI_BUNDLE = REPO_ROOT / ".research-state/experiments/btc-fred-cpiaucsl-yoy-disinflation-long-cash-historical-v1/inputs/cpiaucsl-source-bundle.json"
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-cpiaucsl-yoy-disinflation-long-cash-primary-prior.v1.json"
SPEC_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-cpiaucsl-yoy-disinflation-source-feasibility.v1.spec.json"
PROBE_SOURCE = REPO_ROOT / "research/fred_cpiaucsl_disinflation_source_probe.py"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-cpiaucsl-yoy-disinflation-long-cash-v1.hypothesis.json"
EXPECTED_BINDINGS = {
    "normalized_cpi": (CPI_SOURCE, "ecbdf60fdfbb3de3e7d7b9e0e54913a6f64a17f3b452aa4c840374c8f4825e4b"),
    "source_bundle": (CPI_BUNDLE, "7b144ff464c3f8cbb9c211d453deac008804a6b0795f2e9737518d355840c312"),
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "primary_prior": (PRIOR_SOURCE, "bf7ce3266109c68b5530b70bc5677c547bfa5404f5754f484a77e71a0a0d1504"),
    "source_spec": (SPEC_SOURCE, "5446551dc74adb9c04d07281242a8f1bae90cd65010ec6ada3c1fdfe86b1b0ab"),
    "source_probe": (PROBE_SOURCE, "694e2c55d79efceec72741798fe9503ccc71f0f30130d84a06d91cf6d75f8ea3"),
    "hypothesis": (HYPOTHESIS_SOURCE, "15795a809448f854c9f08838580ccebb0abbde8887fc64c5e436a90483d24c7c"),
}
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1)); VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1)) for year in range(2019, 2025)}
GATES = (
    "btc_sha256_and_52608_rows_match", "cpi_sha256_bundle_and_96_months_match",
    "hourly_lattice_monthly_continuity_and_conservative_availability_pass",
    "frozen_runner_kernel_ledger_reference_parser_prior_hypothesis_probe_and_spec_sha256_match",
    "single_variant_strict_adjacent_yoy_disinflation_no_rescue_contract_pass",
    "design_signals_exactly_48_with_20_disinflation_and_28_other",
    "validation_signals_exactly_24_with_17_disinflation_and_7_other",
    "design_position_changes_between_6_and_48", "validation_position_changes_between_4_and_24",
    "design_normal_total_return_positive", "design_stress_total_return_positive",
    "design_drawdown_at_most_90pct_of_buy_hold", "design_upside_capture_at_least_60pct", "design_calmar_at_least_buy_hold",
    "validation_normal_total_return_positive", "validation_stress_total_return_positive",
    "validation_drawdown_at_most_90pct_of_buy_hold", "validation_upside_capture_at_least_60pct",
    "validation_calmar_at_least_80pct_of_buy_hold", "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_return_at_least_4_of_6", "stress_positive_annual_return_at_least_4_of_6",
    "annual_drawdown_non_worse_at_least_5_of_6", "annual_calmar_at_least_80pct_buy_hold_at_least_4_of_6",
    "annual_upside_capture_at_least_50pct_at_least_4_of_6", "top_year_positive_contribution_at_most_60pct",
    "validation_top_positive_episode_contribution_at_most_60pct", "validation_p90_hold_at_most_8760_hours",
    "validation_terminal_holding_age_at_most_8760_hours", "validation_terminal_liquidation_adjusted_return_positive",
    "validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError): pass


def sha256(path: Path) -> str: return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, source: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, source)
    if spec is None or spec.loader is None: raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{source}")
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module; spec.loader.exec_module(module); return module


def add_months(value: str, count: int) -> str:
    year, month = map(int, value.split("-")); absolute = year * 12 + month - 1 + count
    return f"{absolute // 12:04d}-{absolute % 12 + 1:02d}"


def load_cpi(path: Path) -> list[tuple[str, D]]:
    rows: list[tuple[str, D]] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["observation_date", "cpiaucsl_index_1982_84_100"]: raise ResearchReject(f"DATA_REJECT:CPI_COLUMNS:{reader.fieldnames}")
        for row in reader:
            try: month = row["observation_date"][:7]; value = D(row["cpiaucsl_index_1982_84_100"])
            except (KeyError, InvalidOperation) as error: raise ResearchReject("DATA_REJECT:CPI_PARSE") from error
            if row["observation_date"] != f"{month}-01" or value <= ZERO or value > D("1000"): raise ResearchReject(f"DATA_REJECT:CPI_VALUE:{month}")
            if rows and add_months(rows[-1][0], 1) != month: raise ResearchReject(f"DATA_REJECT:CPI_CONTINUITY:{rows[-1][0]}:{month}")
            rows.append((month, value))
    if len(rows) != 96 or rows[0][0] != "2017-01" or rows[-1][0] != "2024-12": raise ResearchReject("DATA_REJECT:CPI_INVENTORY")
    return rows


def targets_by_execution_time(rows: list[tuple[str, D]]) -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}; changes: list[D] = []
    for index in range(13, len(rows)):
        month, current = rows[index]
        change = (current / rows[index - 12][1] - ONE) - (rows[index - 1][1] / rows[index - 13][1] - ONE)
        effective = datetime.fromisoformat(f"{add_months(month, 2)}-01T00:00:00")
        targets[effective] = change < ZERO; changes.append(change)
    if len(targets) != 83: raise ResearchReject("FEATURE_REJECT:EVALUATION_COUNT")
    states = list(targets.values())
    return targets, {"formula": "(CPI_t/CPI_t_minus_12-1)-(CPI_t_minus_1/CPI_t_minus_13-1)", "threshold": "STRICTLY_LESS_THAN_ZERO", "evaluation_count": len(states), "disinflation_count": sum(states), "other_count": sum(not state for state in states), "state_transition_count": sum(a != b for a, b in zip(states, states[1:], strict=False)), "first_effective_time": min(targets).isoformat(), "last_effective_time": max(targets).isoformat()}


def evaluate(design_output: dict[str, Any], design: dict[str, dict[str, D]], validation_output: dict[str, Any], validation: dict[str, dict[str, D]], annual: dict[str, Any]) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]; vn, vs = validation["NORMAL"], validation["STRESS"]
    dc = design_output["scenarios"]["NORMAL"]["candidate"]; vc = validation_output["scenarios"]["NORMAL"]["candidate"]
    raw = {year: item[1] for year, item in annual.items()}
    np = sum(item["NORMAL"]["total_return"] > ZERO for item in raw.values()); sp = sum(item["STRESS"]["total_return"] > ZERO for item in raw.values())
    dd = sum(item["NORMAL"]["drawdown"] <= item["NORMAL"]["buy_hold_drawdown"] for item in raw.values())
    cal = sum(item["NORMAL"]["calmar"] >= D("0.80") * item["NORMAL"]["buy_hold_calmar"] for item in raw.values())
    up = sum(item["NORMAL"]["upside_capture"] >= D("0.50") for item in raw.values())
    positives = [max(item["NORMAL"]["total_return"], ZERO) for item in raw.values()]; total = sum(positives, ZERO)
    top = max(positives, default=ZERO) / total * HUNDRED if total > ZERO else HUNDRED
    gates = {
        GATES[0]: True, GATES[1]: True, GATES[2]: True, GATES[3]: True, GATES[4]: True,
        GATES[5]: dc["signal_evaluation_count"] == 48 and dc["long_target_count"] == 20 and dc["cash_target_count"] == 28,
        GATES[6]: vc["signal_evaluation_count"] == 24 and vc["long_target_count"] == 17 and vc["cash_target_count"] == 7,
        GATES[7]: D("6") <= dn["position_changes"] <= D("48"), GATES[8]: D("4") <= vn["position_changes"] <= D("24"),
        GATES[9]: dn["total_return"] > ZERO, GATES[10]: ds["total_return"] > ZERO,
        GATES[11]: dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"], GATES[12]: dn["upside_capture"] >= D("0.60"), GATES[13]: dn["calmar"] >= dn["buy_hold_calmar"],
        GATES[14]: vn["total_return"] > ZERO, GATES[15]: vs["total_return"] > ZERO,
        GATES[16]: vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"], GATES[17]: vn["upside_capture"] >= D("0.60"),
        GATES[18]: vn["calmar"] >= D("0.80") * vn["buy_hold_calmar"], GATES[19]: vs["drawdown"] <= vn["drawdown"] + D("3"),
        GATES[20]: np >= 4, GATES[21]: sp >= 4, GATES[22]: dd >= 5, GATES[23]: cal >= 4, GATES[24]: up >= 4,
        GATES[25]: top <= D("60"), GATES[26]: vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        GATES[27]: vn["p90_hold"] <= D("8760"), GATES[28]: vn["terminal_holding_age"] <= D("8760"),
        GATES[29]: vn["terminal_liquidation_return"] > ZERO, GATES[30]: vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != GATES: raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed], {"normal_positive_years": f"{np}_of_6", "stress_positive_years": f"{sp}_of_6", "normal_drawdown_non_worse_years": f"{dd}_of_6", "normal_calmar_at_least_80pct_buy_hold_years": f"{cal}_of_6", "normal_upside_capture_at_least_50pct_years": f"{up}_of_6", "top_year_positive_total_return_contribution_pct": str(top)}


def validate_manifest(manifest: dict[str, Any], runner: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID: raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY": raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("dataset") != {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_DATA_SHA256, "rows": EXPECTED_DATA_ROWS, "selection_cutoff": "2025-01-01T00:00:00"}: raise ResearchReject("MANIFEST_REJECT:DATASET")
    if manifest.get("strategy_policy") != {"policy_id": "BTC_FRED_CPIAUCSL_YOY_DISINFLATION_LONG_CASH_V1", "availability": "OBSERVATION_MONTH_PLUS_TWO_MONTHS_FIRST_DAY_AT_00_00_UTC", "formula": "(CPI_t/CPI_t_minus_12-1)-(CPI_t_minus_1/CPI_t_minus_13-1)", "long_condition": "ADJACENT_YOY_CHANGE_STRICTLY_LESS_THAN_ZERO", "cash_condition": "OTHERWISE", "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE", "cash_return": "0", "short": "DENY", "leverage": "DENY", "variants": 1}: raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {"NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"}, "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"}}: raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {"design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"}, "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"}, "annual_fair_reset_years": [2019, 2020, 2021, 2022, 2023, 2024]}: raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set", {}).get("required") != list(GATES): raise ResearchReject("MANIFEST_REJECT:GATES")
    rb = manifest.get("runner_binding", {})
    if rb.get("path") != runner.relative_to(REPO_ROOT).as_posix() or rb.get("sha256") != sha256(runner): raise ResearchReject("MANIFEST_REJECT:RUNNER")
    actual = {item.get("key"): (item.get("path"), item.get("sha256")) for item in manifest.get("source_bindings", [])}
    expected = {key: (path.relative_to(REPO_ROOT).as_posix(), digest) for key, (path, digest) in EXPECTED_BINDINGS.items()}
    if actual != expected: raise ResearchReject("MANIFEST_REJECT:BINDINGS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    if sha256(input_path) != EXPECTED_DATA_SHA256: raise ResearchReject("DATA_REJECT:SHA256")
    for key, (path, expected) in EXPECTED_BINDINGS.items():
        if sha256(path) != expected: raise ResearchReject(f"SOURCE_REJECT:{key.upper()}_SHA256")
    runner = Path(__file__).resolve(); manifest = json.loads(manifest_path.read_text(encoding="utf-8")); validate_manifest(manifest, runner)
    kernel = load_module("cpi_kernel", KERNEL_SOURCE); ledger = load_module("cpi_ledger", LEDGER_SOURCE); reference = load_module("cpi_reference", REFERENCE_SOURCE); parser = load_module("cpi_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256: raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    cpi = load_cpi(CPI_SOURCE); targets, feature = targets_by_execution_time(cpi)
    do, dr = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN); vo, vr = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, targets, feature, window) for year, window in ANNUAL.items()}
    gates, failed, breadth = evaluate(do, dr, vo, vr, annual); passed = not failed
    return {"schema_version": "1", "document_type": "BTC_FRED_CPIAUCSL_YOY_DISINFLATION_LONG_CASH_HISTORICAL_RESULT_V1", "experiment_id": EXPERIMENT_ID, "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE", "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_FRED_CPIAUCSL_YOY_DISINFLATION_LONG_CASH_FAMILY", "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_LAGGED_CPI_YOY_DISINFLATION_LONG_CASH_FAMILY_WITHOUT_INDEX_LAG_THRESHOLD_DIRECTION_OR_SIZING_TUNING", "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)}, "runner": {"path": runner.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner)}, "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars)}, "feature": feature, "windows": {"design": do, "validation": vo}, "annual_fair_reset": {year: item[0] for year, item in annual.items()}, "breadth_and_concentration": breadth, "primary_gates": gates, "failed_primary_gates": failed, "all_gates_pass": passed, "candidate_created": passed, "oos_opened": False, "claim_boundary": "Historical present-vintage CPI replication only; a pass requires independent sealed OOS and never authorizes activation.", "scope_note": "No paid API, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--input", type=Path, required=True); parser.add_argument("--manifest", type=Path, required=True); parser.add_argument("--output", type=Path, required=True); args = parser.parse_args()
    input_path = args.input.resolve(); manifest_path = args.manifest.resolve(); output_path = args.output.resolve()
    if not input_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT) or not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists(): raise ResearchReject("PATH_OR_OUTPUT_REJECT")
    result = build_output(input_path, manifest_path); output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream: json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":")); stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_primary_gates": result["failed_primary_gates"]}, sort_keys=True)); return 0


if __name__ == "__main__": raise SystemExit(main())
