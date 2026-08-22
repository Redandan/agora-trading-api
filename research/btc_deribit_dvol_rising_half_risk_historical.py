#!/usr/bin/env python3
"""Deterministic matched-capital screen for rising BTC DVOL half-risk sizing."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-deribit-dvol-rising-half-risk-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DERIBIT_DVOL_RISING_HALF_RISK_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
DVOL_SOURCE = REPO_ROOT / ".research-state/experiments/btc-deribit-dvol-rising-half-risk-historical-v1/inputs/dvol-daily-2021-2024.csv"
DVOL_BUNDLE = REPO_ROOT / ".research-state/experiments/btc-deribit-dvol-rising-half-risk-historical-v1/inputs/dvol-source-bundle.json"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-deribit-dvol-rising-half-risk-primary-prior.v1.json"
SOURCE_SPEC = REPO_ROOT / "research_pipeline/examples/btc-deribit-dvol-rising-half-risk-source-feasibility.v1.spec.json"
SOURCE_PROBE = REPO_ROOT / "research/deribit_btc_dvol_source_probe.py"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-deribit-dvol-rising-half-risk-v1.hypothesis.json"
VOV_REFERENCE = REPO_ROOT / "research/btc_monthly_volatility_of_volatility_half_risk_historical.py"
EXECUTION_REFERENCE = REPO_ROOT / "research/btc_monthly_30d_volatility_target_40pct_historical.py"
ECONOMIC_BASE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
EXPECTED_BINDINGS = {
    "dvol_normalized": (DVOL_SOURCE, "19524e87db8ada24b1cc4a6c051e2d3953125357d9654a2e88e417caffca4b94"),
    "dvol_bundle": (DVOL_BUNDLE, "e90919ba1ffb2304cf662cd6fcbf349f5ad52b15b2c2f68ab97ecedcb2b4a67b"),
    "primary_prior": (PRIOR_SOURCE, "26a274b2cfddf9bf145b1f103659cc8cfb95a6655495b4f6ef765b64611eb1d4"),
    "source_spec": (SOURCE_SPEC, "a444c66ead6b7d100e89edc51f7644d96ee5a78d3d42f06ee0640427ff07fba4"),
    "source_probe": (SOURCE_PROBE, "b9dcd8af284ffbc2a0721ce2d6cb3e1c2b0f54ad534739b8f1942f6b6d3ba56f"),
    "hypothesis": (HYPOTHESIS_SOURCE, "3de2a1e9ed9a91355b5c02c9f170d87e867e86ea570f7d0a1767a7d79695bf03"),
    "vov_reference": (VOV_REFERENCE, "295d4f098d660260c69fe315960434495281f4c5a69762525e39abc02237eb2b"),
    "execution_reference": (EXECUTION_REFERENCE, "8eb185644904b62152feb9170964fa86032ee561680a1ba92786746dc9a466d6"),
    "economic_base": (ECONOMIC_BASE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
}
DESIGN = (datetime(2021, 4, 12), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    "2021_PARTIAL": (datetime(2021, 4, 12), datetime(2022, 1, 1)),
    "2022": (datetime(2022, 1, 1), datetime(2023, 1, 1)),
    "2023": (datetime(2023, 1, 1), datetime(2024, 1, 1)),
    "2024": (datetime(2024, 1, 1), datetime(2025, 1, 1)),
}


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


def add_days(day: date, days: int) -> date:
    return day + timedelta(days=days)


def parse_dvol(path: Path) -> list[tuple[date, D, D, D, D]]:
    if sha256(path) != EXPECTED_BINDINGS["dvol_normalized"][1]:
        raise ResearchReject("SOURCE_REJECT:DVOL_SHA256")
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if not rows or rows[0] != ["date", "dvol_open", "dvol_high", "dvol_low", "dvol_close"]:
        raise ResearchReject("SOURCE_REJECT:DVOL_HEADER")
    parsed: list[tuple[date, D, D, D, D]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 5:
            raise ResearchReject(f"SOURCE_REJECT:DVOL_ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            values = tuple(D(value) for value in row[1:])
        except (ValueError, InvalidOperation) as error:
            raise ResearchReject(f"SOURCE_REJECT:DVOL_PARSE:{index}") from error
        open_value, high, low, close = values
        if any(not value.is_finite() or value <= ZERO or value > D("1000") for value in values):
            raise ResearchReject(f"SOURCE_REJECT:DVOL_RANGE:{index}")
        if high < max(open_value, close) or low > min(open_value, close) or high < low:
            raise ResearchReject(f"SOURCE_REJECT:DVOL_OHLC:{index}")
        parsed.append((day, open_value, high, low, close))
    if len(parsed) != 1371 or parsed[0][0] != date(2021, 4, 1) or parsed[-1][0] != date(2024, 12, 31):
        raise ResearchReject("SOURCE_REJECT:DVOL_INVENTORY")
    for index, (prior, current) in enumerate(zip(parsed, parsed[1:], strict=False), start=1):
        if current[0] != add_days(prior[0], 1):
            raise ResearchReject(f"SOURCE_REJECT:DVOL_CONTINUITY:{index}")
    return parsed


def build_signals(rows: list[tuple[date, D, D, D, D]], vov: ModuleType) -> dict[datetime, object]:
    closes = {day: close for day, _, _, _, close in rows}
    signals: dict[datetime, object] = {}
    for day, _, _, _, close in rows:
        if day.weekday() != 6:
            continue
        prior = closes.get(day - timedelta(days=7))
        if prior is None:
            continue
        effective = datetime.combine(day + timedelta(days=1), datetime.min.time())
        high_state = close > prior
        signals[effective] = vov.FeaturePoint(effective, ZERO, ZERO, ONE if high_state else ZERO)
    if len(signals) != 195:
        raise ResearchReject(f"FEATURE_REJECT:SIGNAL_INVENTORY:{len(signals)}")
    return signals


def validate_manifest(manifest: dict[str, object], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS")
    policy = manifest.get("strategy_policy", {})
    if policy.get("variants") != [{"variant_id": "dvol-rising-half-risk-v1", "role": "primary", "threshold": "CURRENT_SUNDAY_CLOSE_STRICTLY_ABOVE_PRIOR_SUNDAY_CLOSE", "high_risk_btc_weight": "0.50"}]:
        raise ResearchReject("MANIFEST_REJECT:VARIANT")
    if policy.get("high_risk_target") != "BTC_50_PERCENT_CASH_50_PERCENT":
        raise ResearchReject("MANIFEST_REJECT:HIGH_RISK_TARGET")
    runner = manifest.get("runner_binding", {})
    if runner.get("path") != runner_path.relative_to(REPO_ROOT).as_posix() or runner.get("sha256") != sha256(runner_path):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")


def primary_gates(design: dict[str, dict[str, D]], validation: dict[str, dict[str, D]], annual: dict[str, object]) -> dict[str, bool]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_80pct": dn["upside_capture"] >= D("0.80"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_80pct": vn["upside_capture"] >= D("0.80"),
        "validation_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "design_signal_evaluations_exactly_90": dn["signal_evaluations"] == D("90"),
        "validation_signal_evaluations_exactly_105": vn["signal_evaluations"] == D("105"),
        "design_both_states_observed": dn["high_risk_targets"] >= ONE and dn["full_risk_targets"] >= ONE,
        "validation_both_states_observed": vn["high_risk_targets"] >= ONE and vn["full_risk_targets"] >= ONE,
        "validation_at_least_10_rebalance_trades": vn["rebalance_trades"] >= D("10"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_3_of_4": annual["normal_positive_years"] >= 3,
        "stress_positive_annual_return_at_least_3_of_4": annual["stress_positive_years"] >= 3,
        "annual_drawdown_non_worse_at_least_3_of_4": annual["normal_drawdown_non_worse_years"] >= 3,
        "annual_calmar_non_worse_at_least_3_of_4": annual["normal_calmar_non_worse_years"] >= 3,
        "top_year_positive_contribution_at_most_60pct": annual["top_year_raw"] <= D("60"),
        "validation_top_positive_realized_lot_contribution_at_most_60pct": vn["has_positive_realized_slice"] == ZERO or vn["top_positive_realized_contribution"] <= D("60"),
        "validation_p90_realized_lot_hold_at_most_17520_hours": vn["p90_hold"] <= D("17520"),
        "validation_terminal_oldest_lot_age_at_most_17520_hours": vn["terminal_oldest_age"] <= D("17520"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    for label, (path, expected) in EXPECTED_BINDINGS.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")
    runner_path = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner_path)
    vov = load_module("frozen_dvol_vov_reference", VOV_REFERENCE)
    execution = load_module("frozen_dvol_execution_reference", EXECUTION_REFERENCE)
    base = load_module("frozen_dvol_economic_base", ECONOMIC_BASE)
    parser = load_module("frozen_dvol_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    dvol_rows = parse_dvol(DVOL_SOURCE)
    signals = build_signals(dvol_rows, vov)
    design_output, design_raw = vov.simulate_window(bars, signals, DESIGN, D("0.5"), base, execution)
    validation_output, validation_raw = vov.simulate_window(bars, signals, VALIDATION, D("0.5"), base, execution)
    annual_outputs = {year: vov.simulate_window(bars, signals, window, D("0.5"), base, execution) for year, window in ANNUAL.items()}
    annual_breadth = vov.breadth({year: value[1] for year, value in annual_outputs.items()}, base)
    gate_breadth = dict(annual_breadth)
    annual_breadth.pop("top_year_raw")
    gates = primary_gates(design_raw, validation_raw, gate_breadth)
    failed = [name for name, passed in gates.items() if not passed]
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DERIBIT_DVOL_RISING_HALF_RISK_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_DERIBIT_DVOL_RISING_HALF_RISK_FAMILY",
        "decision": "DESIGN_VALIDATION_GATES_PASS_SEALED_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_DVOL_RISING_HALF_RISK_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
        "source_bindings": {label: expected for label, (_, expected) in EXPECTED_BINDINGS.items()},
        "feature": {"daily_dvol_observations": len(dvol_rows), "weekly_signals": len(signals), "design_signals": sum(DESIGN[0] <= point < DESIGN[1] for point in signals), "validation_signals": sum(VALIDATION[0] <= point < VALIDATION[1] for point in signals)},
        "policy": {"decision_clock": "MONDAY_00_00_UTC", "high_expected_movement_state": "CURRENT_SUNDAY_DVOL_CLOSE_STRICTLY_ABOVE_PRIOR_SUNDAY_CLOSE", "low_risk_target": "BTC_100_PERCENT", "high_risk_target": "BTC_50_PERCENT_CASH_50_PERCENT", "variants": 1},
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual_outputs.items()},
        "breadth_and_concentration": annual_breadth,
        "primary_gates": gates,
        "failed_primary_gates": failed,
        "all_gates_pass": passed,
        "economics_opened": True,
        "oos_opened": False,
        "candidate_created": passed,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass requires separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True); parser.add_argument("--manifest", type=Path, required=True); parser.add_argument("--output", type=Path, required=True)
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
