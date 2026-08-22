#!/usr/bin/env python3
"""Preregistered Coin Metrics BTC native transaction-fee pressure admission screen for DRA V1."""

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

import btc_dra_bitcoin_hashrate_growth_entry_admission_historical_v1 as template
import btc_dra_cftc_tff_entry_admission_historical_v1 as reused


D = Decimal
AUTHORIZATION = reused.AUTHORIZATION
DOCUMENT_TYPE = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITY = "COIN_METRICS_BTC_FEETOTNTV_STRICT_POSITIVE_ADJACENT_28D_GROWTH_168H_V1"
EXPERIMENT_ID = "dra-bitcoin-fee-pressure-entry-admission-historical-v1"
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
WINDOW_DAYS = 28
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
        "fee_rate": "0.0010",
        "initial_equity_usdt": "250",
        "slippage_rate": "0.0005",
        "slot_capacity_usdt": "240",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "economic assumptions drift")
    if manifest["factor"] != {
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_CURRENT_28D_MEAN_FEETOTNTV_GT_IMMEDIATELY_PRECEDING_28D_MEAN",
        "factor_identity": FACTOR_IDENTITY,
        "formula": "current_28d_mean_feetotntv-prior_nonoverlapping_28d_mean_feetotntv",
        "negative_action": "HOLD_CASH",
        "positive_action": "ADMIT",
        "series_id": "FeeTotNtv",
        "zero_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "WEEK_ENDING_SUNDAY_PLUS_3_CALENDAR_DAYS_AT_00_00_UTC",
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "non_sunday_action": "REJECT_SOURCE",
        "publication_lag_calendar_days": PUBLICATION_LAG_DAYS,
        "window_days": WINDOW_DAYS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["source"] != {
        "first_date": EXPECTED_FIRST.isoformat(),
        "last_date": EXPECTED_LAST.isoformat(),
        "metric": "FeeTotNtv",
        "normalized_bytes": 59333,
        "present_vintage": True,
        "publisher": "Coin Metrics Community API",
        "rows": EXPECTED_ROWS,
        "weekly_aggregation": "SEVEN_COMPLETE_DAYS_MONDAY_THROUGH_SUNDAY",
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
        "hypothesis": "research_pipeline/examples/dra-bitcoin-fee-pressure-entry-admission-v1.hypothesis.json",
        "manifest_schema": "research_pipeline/btc-dra-bitcoin-fee-pressure-entry-admission-manifest.v1.schema.json",
        "normalized_source": ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/inputs/coinmetrics-btc-fee-pressure-2018-2024.csv",
        "primary_prior": "research_pipeline/examples/dra-bitcoin-fee-pressure-primary-prior.v1.json",
        "raw_source_response": ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/inputs/coinmetrics-btc-fee-pressure-2018-2024-raw.json",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_bitcoin_fee_pressure_entry_admission_historical_v1.py",
        "source_bundle": ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/inputs/coinmetrics-source-bundle.json",
        "source_metadata": "research_pipeline/examples/coinmetrics-btc-fee-pressure-daily-2018-2024.v1.source.json",
        "source_probe": "research/coinmetrics_btc_fee_pressure_source_probe.cjs",
    }
    for key, path in expected_paths.items():
        _binding(bindings[key], path, f"bindings.{key}")
    return manifest


def _resolved_binding(bindings: dict[str, Any], name: str) -> Path:
    path = REPOSITORY_ROOT.joinpath(*bindings[name]["path"].split("/"))
    resolved = path.resolve(strict=True)
    try:
        resolved.relative_to(REPOSITORY_ROOT)
    except ValueError as error:
        raise reused.ScreenReject("DATA_REJECT", f"{name} escapes repository") from error
    return resolved


def load_fee_pressure(bindings: dict[str, Any]) -> tuple[dict[date, D], dict[str, Any]]:
    normalized_path = _resolved_binding(bindings, "normalized_source")
    raw = normalized_path.read_bytes()
    try:
        csv_rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise reused.ScreenReject("DATA_REJECT", "normalized FeeTotNtv source is not UTF-8") from error
    if not csv_rows or csv_rows[0] != ["date", "total_fees_btc"]:
        raise reused.ScreenReject("DATA_REJECT", "normalized FeeTotNtv header drift")
    rows: dict[date, D] = {}
    for index, row in enumerate(csv_rows[1:]):
        if len(row) != 2:
            raise reused.ScreenReject("DATA_REJECT", f"normalized FeeTotNtv row malformed: {index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1])
        except (ValueError, InvalidOperation) as error:
            raise reused.ScreenReject("DATA_REJECT", f"normalized FeeTotNtv value malformed: {index}") from error
        if day in rows or not value.is_finite() or value < 0:
            raise reused.ScreenReject("DATA_REJECT", f"normalized FeeTotNtv identity drift: {index}")
        rows[day] = value
    ordered = sorted(rows)
    if len(ordered) != EXPECTED_ROWS or ordered[0] != EXPECTED_FIRST or ordered[-1] != EXPECTED_LAST:
        raise reused.ScreenReject("DATA_REJECT", "normalized FeeTotNtv sample boundary drift")
    if any(current - prior != timedelta(days=1) for prior, current in zip(ordered, ordered[1:], strict=False)):
        raise reused.ScreenReject("DATA_REJECT", "normalized FeeTotNtv daily continuity drift")

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
        or metadata.get("metric") != "FeeTotNtv"
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
        raise reused.ScreenReject("DATA_REJECT", "FeeTotNtv complete-week boundary drift")
    points: list[dict[str, Any]] = []
    exclusions = {
        "MISSING_TWO_COMPLETE_28D_WINDOWS": 7,
        "INCOMPLETE_TAIL_DAYS": len(ordered) - len(full_days),
        "DECISION_AT_OR_AFTER_CUTOFF": 0,
    }
    for end_index in range(2 * WINDOW_DAYS - 1, len(full_days), 7):
        week_end = full_days[end_index]
        eligible_at = datetime.combine(week_end + timedelta(days=PUBLICATION_LAG_DAYS), datetime.min.time())
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        current_days = full_days[end_index - WINDOW_DAYS + 1:end_index + 1]
        prior_days = full_days[end_index - 2 * WINDOW_DAYS + 1:end_index - WINDOW_DAYS + 1]
        current_mean = sum((rows[day] for day in current_days), D("0")) / D(WINDOW_DAYS)
        prior_mean = sum((rows[day] for day in prior_days), D("0")) / D(WINDOW_DAYS)
        score = current_mean - prior_mean
        points.append({
            "report_date": week_end.isoformat(),
            "prior_report_date": prior_days[-1].isoformat(),
            "eligible_at": eligible_at.isoformat(),
            "factor_delta": str(score),
            "factor_sign": 1 if score > 0 else -1 if score < 0 else 0,
        })
    return points, exclusions


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    original = {
        "document_type": template.DOCUMENT_TYPE,
        "result_type": template.RESULT_TYPE,
        "runner_identity": template.RUNNER_IDENTITY,
        "factor_identity": template.FACTOR_IDENTITY,
        "experiment_id": template.EXPERIMENT_ID,
        "validate_manifest": template.validate_manifest,
        "load_hashrate": template.load_hashrate,
        "build_factor_points": template.build_factor_points,
        "file": template.__file__,
    }
    try:
        template.DOCUMENT_TYPE = DOCUMENT_TYPE
        template.RESULT_TYPE = RESULT_TYPE
        template.RUNNER_IDENTITY = RUNNER_IDENTITY
        template.FACTOR_IDENTITY = FACTOR_IDENTITY
        template.EXPERIMENT_ID = EXPERIMENT_ID
        template.validate_manifest = validate_manifest
        template.load_hashrate = load_fee_pressure
        template.build_factor_points = build_factor_points
        template.__file__ = __file__
        return template.run_screen(manifest_path, input_path, output_path)
    finally:
        template.DOCUMENT_TYPE = original["document_type"]
        template.RESULT_TYPE = original["result_type"]
        template.RUNNER_IDENTITY = original["runner_identity"]
        template.FACTOR_IDENTITY = original["factor_identity"]
        template.EXPERIMENT_ID = original["experiment_id"]
        template.validate_manifest = original["validate_manifest"]
        template.load_hashrate = original["load_hashrate"]
        template.build_factor_points = original["build_factor_points"]
        template.__file__ = original["file"]


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
