#!/usr/bin/env python3
"""Preregistered Coin Metrics BTC MVRV relative-value admission screen for DRA V1."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
import io
import json
from pathlib import Path
import sys
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_cftc_tff_entry_admission_historical_v1 as reused


D = Decimal
AUTHORIZATION = reused.AUTHORIZATION
DOCUMENT_TYPE = "BTC_DRA_BITCOIN_MVRV_RELATIVE_VALUE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_BITCOIN_MVRV_RELATIVE_VALUE_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_BITCOIN_MVRV_RELATIVE_VALUE_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITY = "COIN_METRICS_BTC_MVRV_STRICT_BELOW_PRIOR_365D_MEDIAN_SUNDAY_PLUS_3D_168H_V1"
EXPERIMENT_ID = "dra-bitcoin-mvrv-relative-value-entry-admission-historical-v1"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = reused.GATE_SET
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION
FOLDS = reused.FOLDS
EXPECTED_ROWS = 2557
EXPECTED_FIRST = date(2018, 1, 1)
EXPECTED_LAST = date(2024, 12, 31)
FULL_WEEK_DAYS = 2555
REFERENCE_DAYS = 365
PUBLICATION_LAG_DAYS = 3
FACTOR_VALID_HOURS = 168


def _binding(value: Any, expected_path: str, label: str) -> dict[str, Any]:
    return reused._validate_binding(value, expected_path, label)


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = reused._exact_keys(
        value,
        {
            "authorization", "availability", "bindings", "dataset", "document_type",
            "economics", "experiment_id", "factor", "gate_set", "oos_access",
            "parent_strategy", "schema_version", "selection_cutoff", "source", "windows",
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
    if manifest["dataset"] != {"canonical_sha256": reused.base.SELECTION_SHA256, "rows": reused.base.SELECTION_ROWS}:
        raise reused.ScreenReject("CONTRACT_REJECT", "dataset identity drift")
    if manifest["economics"] != {
        "fee_rate": "0.0010", "initial_equity_usdt": "250",
        "slippage_rate": "0.0005", "slot_capacity_usdt": "240",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "economic assumptions drift")
    if manifest["factor"] != {
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_CURRENT_SUNDAY_MVRV_LT_PRIOR_365D_MEDIAN",
        "factor_identity": FACTOR_IDENTITY,
        "formula": "prior_365d_median-current_sunday_mvrv",
        "negative_action": "HOLD_CASH",
        "positive_action": "ADMIT",
        "series_id": "CapMVRVCur",
        "zero_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "SUNDAY_OBSERVATION_PLUS_3_CALENDAR_DAYS_AT_00_00_UTC",
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "non_sunday_action": "IGNORE",
        "publication_lag_calendar_days": PUBLICATION_LAG_DAYS,
        "reference_window_days": REFERENCE_DAYS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["source"] != {
        "first_date": EXPECTED_FIRST.isoformat(),
        "last_date": EXPECTED_LAST.isoformat(),
        "metric": "CapMVRVCur",
        "normalized_bytes": 61746,
        "present_vintage": True,
        "publisher": "Coin Metrics Community API",
        "rows": EXPECTED_ROWS,
        "sampling": "SUNDAY_OBSERVATION_WITH_PRIOR_365_DAILY_MEDIAN",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "source identity drift")
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
            "base_runner", "capacity_runner", "hypothesis", "manifest_schema",
            "normalized_source", "primary_prior", "raw_source_response",
            "reused_economic_runner", "runner", "source_bundle", "source_metadata", "source_probe",
        },
        "bindings",
    )
    expected_paths = {
        "base_runner": "research/btc_dra_reversal_confirmed_exit_v2c.py",
        "capacity_runner": "research/btc_dra_equal_capital_capacity_v1.py",
        "hypothesis": "research_pipeline/examples/dra-bitcoin-mvrv-relative-value-entry-admission-v1.hypothesis.json",
        "manifest_schema": "research_pipeline/btc-dra-bitcoin-mvrv-relative-value-entry-admission-manifest.v1.schema.json",
        "normalized_source": ".research-state/experiments/dra-bitcoin-mvrv-relative-value-entry-admission-historical-v1/inputs/coinmetrics-btc-mvrv-2018-2024.csv",
        "primary_prior": "research_pipeline/examples/dra-bitcoin-mvrv-relative-value-primary-prior.v1.json",
        "raw_source_response": ".research-state/experiments/dra-bitcoin-mvrv-relative-value-entry-admission-historical-v1/inputs/coinmetrics-btc-mvrv-2018-2024-raw.json",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_bitcoin_mvrv_relative_value_entry_admission_historical_v1.py",
        "source_bundle": ".research-state/experiments/dra-bitcoin-mvrv-relative-value-entry-admission-historical-v1/inputs/coinmetrics-source-bundle.json",
        "source_metadata": "research_pipeline/examples/coinmetrics-btc-mvrv-daily-2018-2024.v1.source.json",
        "source_probe": "research/coinmetrics_btc_mvrv_source_probe.cjs",
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


def _resolved_binding(bindings: dict[str, Any], name: str) -> Path:
    path = REPOSITORY_ROOT.joinpath(*bindings[name]["path"].split("/"))
    resolved = path.resolve(strict=True)
    try:
        resolved.relative_to(REPOSITORY_ROOT)
    except ValueError as error:
        raise reused.ScreenReject("DATA_REJECT", f"{name} escapes repository") from error
    return resolved


def load_mvrv(bindings: dict[str, Any]) -> tuple[dict[date, D], dict[str, Any]]:
    normalized_path = _resolved_binding(bindings, "normalized_source")
    raw = normalized_path.read_bytes()
    try:
        csv_rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise reused.ScreenReject("DATA_REJECT", "normalized MVRV source is not UTF-8") from error
    if not csv_rows or csv_rows[0] != ["date", "mvrv"]:
        raise reused.ScreenReject("DATA_REJECT", "normalized MVRV header drift")
    rows: dict[date, D] = {}
    for index, row in enumerate(csv_rows[1:]):
        if len(row) != 2:
            raise reused.ScreenReject("DATA_REJECT", f"normalized MVRV row malformed: {index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1])
        except (ValueError, InvalidOperation) as error:
            raise reused.ScreenReject("DATA_REJECT", f"normalized MVRV value malformed: {index}") from error
        if day in rows or not value.is_finite() or value <= 0 or value > D("100"):
            raise reused.ScreenReject("DATA_REJECT", f"normalized MVRV identity drift: {index}")
        rows[day] = value
    ordered = sorted(rows)
    if len(ordered) != EXPECTED_ROWS or ordered[0] != EXPECTED_FIRST or ordered[-1] != EXPECTED_LAST:
        raise reused.ScreenReject("DATA_REJECT", "normalized MVRV sample boundary drift")
    if any(current - prior != timedelta(days=1) for prior, current in zip(ordered, ordered[1:], strict=False)):
        raise reused.ScreenReject("DATA_REJECT", "normalized MVRV daily continuity drift")

    bundle_path = _resolved_binding(bindings, "source_bundle")
    bundle_raw = bundle_path.read_bytes()
    try:
        bundle = json.loads(bundle_raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("DATA_REJECT", "source bundle is not strict JSON") from error
    if bundle_raw != reused.canonical_document_bytes(bundle):
        raise reused.ScreenReject("DATA_REJECT", "source bundle is not canonical")
    normalized = bundle.get("normalized_subset", {})
    source_raw = bundle.get("raw_response", {})
    if (
        bundle.get("status") != "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS"
        or normalized.get("path") != bindings["normalized_source"]["path"]
        or normalized.get("sha256") != bindings["normalized_source"]["sha256"]
        or normalized.get("rows") != EXPECTED_ROWS
        or source_raw.get("path") != bindings["raw_source_response"]["path"]
        or source_raw.get("sha256") != bindings["raw_source_response"]["sha256"]
        or source_raw.get("rows") != EXPECTED_ROWS
    ):
        raise reused.ScreenReject("DATA_REJECT", "source bundle cross-binding drift")
    metadata_path = _resolved_binding(bindings, "source_metadata")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if (
        metadata.get("status") != "SEALED_HISTORICAL_SOURCE"
        or metadata.get("metric") != "CapMVRVCur"
        or metadata.get("raw_bundle", {}).get("sha256") != bindings["source_bundle"]["sha256"]
        or metadata.get("sealed_subset", {}).get("sha256") != bindings["normalized_source"]["sha256"]
    ):
        raise reused.ScreenReject("DATA_REJECT", "source metadata cross-binding drift")
    return rows, {
        "bundle_path": bindings["source_bundle"]["path"],
        "bundle_sha256": bindings["source_bundle"]["sha256"],
        "normalized_path": bindings["normalized_source"]["path"],
        "normalized_sha256": bindings["normalized_source"]["sha256"],
        "raw_response_path": bindings["raw_source_response"]["path"],
        "raw_response_sha256": bindings["raw_source_response"]["sha256"],
        "rows": len(rows),
        "complete_week_count": metadata["pre_outcome_feature_feasibility"]["complete_week_count"],
        "present_vintage_revision_boundary": "ORIGINAL_DAILY_REVIEW_TIMESTAMPS_AND_VINTAGES_MISSING_PROOF",
    }


def build_factor_points(rows: dict[date, D]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    ordered = sorted(rows)
    full_days = ordered[:FULL_WEEK_DAYS]
    if full_days[0].weekday() != 0 or full_days[-1].weekday() != 6:
        raise reused.ScreenReject("DATA_REJECT", "MVRV complete-week boundary drift")
    points: list[dict[str, Any]] = []
    exclusions = {
        "MISSING_PRIOR_365D_WINDOW": 52,
        "INCOMPLETE_TAIL_DAYS": len(ordered) - len(full_days),
        "DECISION_AT_OR_AFTER_CUTOFF": 0,
    }
    for index in range(REFERENCE_DAYS, len(full_days)):
        report_day = full_days[index]
        if report_day.weekday() != 6:
            continue
        eligible_at = datetime.combine(report_day + timedelta(days=PUBLICATION_LAG_DAYS), datetime.min.time())
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        reference_values = sorted(rows[day] for day in full_days[index - REFERENCE_DAYS:index])
        reference_median = reference_values[REFERENCE_DAYS // 2]
        score = reference_median - rows[report_day]
        points.append({
            "report_date": report_day.isoformat(),
            "prior_report_date": full_days[index - 1].isoformat(),
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
    bars = reused.load_selection(input_path, manifest)
    mvrv_rows, source_evidence = load_mvrv(bindings)
    factor_points, exclusions = build_factor_points(mvrv_rows)
    baseline = reused.parent_baseline(bars)
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
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "predictive_evidence": predictive,
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_BITCOIN_MVRV_RELATIVE_VALUE_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "1",
        "source_evidence": source_evidence,
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BITCOIN_MVRV_RELATIVE_VALUE_FAMILY",
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
    print(json.dumps({
        "economic_gates": result["economic_gates"],
        "oos_opened": result["oos_opened"],
        "status": result["status"],
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
