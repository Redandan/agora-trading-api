#!/usr/bin/env python3
"""Preregistered CFTC asset-manager contrarian admission screen for BTC DRA V1."""

from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
from decimal import Decimal
import json
from pathlib import Path
import sys
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_cftc_tff_entry_admission_historical_v1 as reused
from research_pipeline import cftc_cme_bitcoin_tff_source as cftc_source


D = Decimal
AUTHORIZATION = reused.AUTHORIZATION
DOCUMENT_TYPE = "BTC_DRA_CFTC_TFF_ASSET_MANAGER_CONTRARIAN_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_CFTC_TFF_ASSET_MANAGER_CONTRARIAN_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_CFTC_TFF_ASSET_MANAGER_CONTRARIAN_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITY = "CFTC_TFF_ASSET_MANAGER_NET_PCT_OI_WEEKLY_CONTRACTION_CONTRARIAN_168H_V1"
EXPERIMENT_ID = "cftc-tff-asset-manager-contrarian-dra-entry-admission-historical-v1"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = reused.GATE_SET
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION
FOLDS = reused.FOLDS
ASSET_LONG_FIELD = "Pct_of_OI_Asset_Mgr_Long_All"
ASSET_SHORT_FIELD = "Pct_of_OI_Asset_Mgr_Short_All"
ASSET_LONG_INDEX = cftc_source.ORDERED_FIELDS.index(ASSET_LONG_FIELD)
ASSET_SHORT_INDEX = cftc_source.ORDERED_FIELDS.index(ASSET_SHORT_FIELD)

def _binding(value: Any, expected_path: str, label: str) -> dict[str, Any]:
    return reused._validate_binding(value, expected_path, label)


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = reused._exact_keys(
        value,
        {
            "authorization", "availability", "bindings", "dataset", "document_type",
            "economics", "experiment_id", "factor", "gate_set", "oos_access",
            "parent_strategy", "schema_version", "selection_cutoff", "windows",
        },
        "manifest",
    )
    expected_scalars = {
        "authorization": AUTHORIZATION,
        "document_type": DOCUMENT_TYPE,
        "experiment_id": EXPERIMENT_ID,
        "gate_set": GATE_SET,
        "oos_access": "DENY",
        "parent_strategy": PARENT_STRATEGY,
        "schema_version": "1",
        "selection_cutoff": SELECTION_CUTOFF.isoformat(),
    }
    for key, expected in expected_scalars.items():
        if manifest[key] != expected:
            raise reused.ScreenReject("CONTRACT_REJECT", f"{key} drift")
    if manifest["dataset"] != {
        "canonical_sha256": reused.base.SELECTION_SHA256,
        "rows": reused.base.SELECTION_ROWS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "dataset identity drift")
    if manifest["economics"] != {
        "fee_rate": "0.0010",
        "initial_equity_usdt": "250",
        "slippage_rate": "0.0005",
        "slot_capacity_usdt": "240",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "economic assumptions drift")
    if manifest["factor"] != {
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_FACTOR_SCORE_GT_ZERO",
        "factor_identity": FACTOR_IDENTITY,
        "formula": "(prior_long_pct-prior_short_pct)-(current_long_pct-current_short_pct)",
        "negative_action": "HOLD_CASH",
        "participant_category": "ASSET_MANAGER_INSTITUTIONAL",
        "positive_action": "ADMIT",
        "zero_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "REPORT_DATE_PLUS_14_CALENDAR_DAYS_AT_00_00_UTC",
        "exact_predecessor_days": 7,
        "factor_valid_hours": 168,
        "ion_exclusion": {
            "end_inclusive": "2023-03-14",
            "reason": "CFTC_2023_ION_DELAYED_PUBLICATION",
            "start_inclusive": "2023-01-31",
        },
        "non_tuesday_action": "EXCLUDE",
        "report_lag_calendar_days": 14,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["windows"] != {
        "annual_folds": [str(year) for year in range(2020, 2025)],
        "design": {"end_exclusive": DESIGN[1].isoformat(), "start_inclusive": DESIGN[0].isoformat()},
        "outcome_horizon_hours": 168,
        "validation": {"end_exclusive": VALIDATION[1].isoformat(), "start_inclusive": VALIDATION[0].isoformat()},
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "research windows drift")
    bindings = reused._exact_keys(
        manifest["bindings"],
        {
            "base_runner", "capacity_runner", "historical_source_manifest",
            "manifest_schema", "primary_prior", "reused_economic_runner", "runner",
            "source_field_definition",
        },
        "bindings",
    )
    expected_paths = {
        "base_runner": "research/btc_dra_reversal_confirmed_exit_v2c.py",
        "capacity_runner": "research/btc_dra_equal_capital_capacity_v1.py",
        "historical_source_manifest": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
        "manifest_schema": "research_pipeline/btc-dra-cftc-tff-asset-manager-contrarian-entry-admission-manifest.v1.schema.json",
        "primary_prior": "research_pipeline/examples/dra-cftc-asset-manager-contrarian-primary-prior.v1.json",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_cftc_tff_asset_manager_contrarian_entry_admission_historical_v1.py",
        "source_field_definition": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    }
    for key, path in expected_paths.items():
        _binding(bindings[key], path, f"bindings.{key}")
    return manifest


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("CONTRACT_REJECT", "manifest must be strict UTF-8 JSON") from error
    if raw != reused.canonical_document_bytes(value):
        raise reused.ScreenReject("CONTRACT_REJECT", "manifest must use canonical JSON document bytes")
    return validate_manifest(value), raw


def _asset_manager_net_pct(row: list[str]) -> D:
    long_pct = reused.factor_evaluator.parse_factor_decimal(row[ASSET_LONG_INDEX])
    short_pct = reused.factor_evaluator.parse_factor_decimal(row[ASSET_SHORT_INDEX])
    return long_pct - short_pct


def build_factor_points(rows: dict[date, list[str]]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    points: list[dict[str, Any]] = []
    exclusions = {"NON_TUESDAY": 0, "ION_DELAY": 0, "MISSING_EXACT_PREDECESSOR": 0, "DECISION_AT_OR_AFTER_CUTOFF": 0}
    excluded_dates = {
        day for day in rows
        if day.weekday() != 1 or reused.ION_EXCLUSION_START <= day <= reused.ION_EXCLUSION_END
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["NON_TUESDAY"] += 1
            continue
        if reused.ION_EXCLUSION_START <= day <= reused.ION_EXCLUSION_END:
            exclusions["ION_DELAY"] += 1
            continue
        prior_day = day - timedelta(days=7)
        if prior_day not in rows or prior_day in excluded_dates:
            exclusions["MISSING_EXACT_PREDECESSOR"] += 1
            continue
        eligible_at = datetime.combine(day + timedelta(days=reused.AVAILABILITY_LAG_DAYS), datetime.min.time())
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        score = _asset_manager_net_pct(rows[prior_day]) - _asset_manager_net_pct(rows[day])
        points.append({
            "report_date": day.isoformat(),
            "prior_report_date": prior_day.isoformat(),
            "eligible_at": eligible_at.isoformat(),
            "factor_delta": str(score),
            "factor_sign": 1 if score > 0 else -1 if score < 0 else 0,
        })
    return points, exclusions


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = reused.verify_bindings(manifest)
    source_path = REPOSITORY_ROOT.joinpath(*bindings["historical_source_manifest"]["path"].split("/"))
    source_manifest, source_manifest_raw = reused.load_manifest(source_path)
    inherited_bindings = reused.verify_bindings(source_manifest)
    if source_manifest["dataset"] != manifest["dataset"] or source_manifest["economics"] != manifest["economics"]:
        raise reused.ScreenReject("CONTRACT_REJECT", "historical source manifest is not economically matched")
    bars = reused.load_selection(input_path, source_manifest)
    rows, archive_evidence = reused.load_historical_rows(source_manifest)
    factor_points, exclusions = build_factor_points(rows)
    baseline = reused.parent_baseline(bars)
    # The reused economic engine resolves these labels at execution time. Apply
    # the process-local labels only after the historical source manifest and its
    # sealed original runner identity have passed validation, then restore them.
    original_factor_identity = reused.FACTOR_IDENTITY
    original_runner_identity = reused.RUNNER_IDENTITY
    try:
        reused.FACTOR_IDENTITY = FACTOR_IDENTITY
        reused.RUNNER_IDENTITY = RUNNER_IDENTITY
        economics = reused.economic_evidence(bars, baseline, factor_points)
    finally:
        reused.FACTOR_IDENTITY = original_factor_identity
        reused.RUNNER_IDENTITY = original_runner_identity
    economic_checks = reused.economic_gates(economics, baseline)
    predictive = {
        "design": reused.predictive_evidence(reused.build_predictive_episodes(bars, factor_points, DESIGN)),
        "validation": reused.predictive_evidence(reused.build_predictive_episodes(bars, factor_points, VALIDATION)),
    }
    passed = all(economic_checks.values()) and all(all(window["gates"].values()) for window in predictive.values())
    result = {
        "authorization": AUTHORIZATION,
        "archive_evidence": archive_evidence,
        "baseline": baseline,
        "bindings": bindings,
        "dataset": {"canonical_sha256": reused.base.data_hash(bars), "rows": len(bars), "selection_cutoff": SELECTION_CUTOFF.isoformat()},
        "document_type": RESULT_TYPE,
        "economic_evidence": economics,
        "economic_gates": economic_checks,
        "experiment_id": EXPERIMENT_ID,
        "factor_identity": FACTOR_IDENTITY,
        "factor_points": factor_points,
        "factor_point_exclusions": exclusions,
        "gate_set": GATE_SET,
        "historical_source_manifest_sha256": reused.sha256_bytes(source_manifest_raw),
        "inherited_source_bindings": inherited_bindings,
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "predictive_evidence": predictive,
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_CFTC_ASSET_MANAGER_CONTRARIAN_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "1",
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_CFTC_ASSET_MANAGER_CONTRARIAN_FAMILY",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(reused.canonical_document_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_screen(args.manifest, args.input, args.output)
    except (reused.ScreenReject, reused.base.ResearchReject, ValueError) as error:
        print(json.dumps({"detail": getattr(error, "detail", str(error)), "status": getattr(error, "status", "DATA_REJECT")}, ensure_ascii=False))
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
