#!/usr/bin/env python3
"""Preregistered CFTC option asset-manager admission screen for BTC DRA V1."""

from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta
from decimal import Decimal
import json
from pathlib import Path
import re
import sys
from typing import Any
import urllib.error
import urllib.request


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_cftc_tff_entry_admission_historical_v1 as reused


D = Decimal
AUTHORIZATION = reused.AUTHORIZATION
DOCUMENT_TYPE = "BTC_DRA_CFTC_TFF_OPTION_ASSET_MANAGER_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_CFTC_TFF_OPTION_ASSET_MANAGER_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_CFTC_TFF_OPTION_ASSET_MANAGER_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITY = "CFTC_TFF_OPTION_ASSET_MANAGER_NET_PCT_OI_WEEKLY_CONTRACTION_168H_V1"
EXPERIMENT_ID = "cftc-tff-option-asset-manager-dra-entry-admission-historical-v1"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = reused.GATE_SET
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION
FOLDS = reused.FOLDS
AVAILABILITY_LAG_DAYS = 7
FACTOR_VALID_HOURS = 168
COMBINED_QUERY_URL = (
    "https://publicreporting.cftc.gov/resource/yw9f-hn96.json?"
    "%24select=report_date_as_yyyy_mm_dd%2Copen_interest_all%2C"
    "asset_mgr_positions_long%2Casset_mgr_positions_short&"
    "%24where=cftc_contract_market_code%3D%27133741%27%20AND%20"
    "report_date_as_yyyy_mm_dd%3E%3D%272020-01-01T00%3A00%3A00.000%27%20AND%20"
    "report_date_as_yyyy_mm_dd%3C%272025-01-01T00%3A00%3A00.000%27&"
    "%24order=report_date_as_yyyy_mm_dd%20ASC&%24limit=5000"
)
COMBINED_DATASET_ID = "yw9f-hn96"
COMBINED_FIELDS = (
    "report_date_as_yyyy_mm_dd",
    "open_interest_all",
    "asset_mgr_positions_long",
    "asset_mgr_positions_short",
)
FUTURES_OPEN_INTEREST_INDEX = reused.cftc_source.ORDERED_FIELDS.index("Open_Interest_All")
FUTURES_ASSET_LONG_INDEX = reused.cftc_source.ORDERED_FIELDS.index(
    "Asset_Mgr_Positions_Long_All"
)
FUTURES_ASSET_SHORT_INDEX = reused.cftc_source.ORDERED_FIELDS.index(
    "Asset_Mgr_Positions_Short_All"
)
_NONNEGATIVE_INTEGER = re.compile(r"^(?:0|[1-9][0-9]*)$")


def _binding(value: Any, expected_path: str, label: str) -> dict[str, Any]:
    return reused._validate_binding(value, expected_path, label)


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = reused._exact_keys(
        value,
        {
            "authorization", "availability", "bindings", "combined_source",
            "dataset", "document_type", "economics", "experiment_id", "factor",
            "gate_set", "oos_access", "parent_strategy", "schema_version",
            "selection_cutoff", "windows",
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
        "formula": "prior_option_asset_manager_net_pct_oi-current_option_asset_manager_net_pct_oi",
        "negative_action": "HOLD_CASH",
        "participant_category": "OPTION_ASSET_MANAGER_INSTITUTIONAL",
        "positive_action": "ADMIT",
        "zero_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "REPORT_DATE_PLUS_7_CALENDAR_DAYS_AT_00_00_UTC",
        "exact_predecessor_days": 7,
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "ion_exclusion": {
            "end_inclusive": "2023-03-14",
            "reason": "CFTC_2023_ION_DELAYED_PUBLICATION",
            "start_inclusive": "2023-01-31",
        },
        "non_tuesday_action": "EXCLUDE",
        "report_lag_calendar_days": AVAILABILITY_LAG_DAYS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["windows"] != {
        "annual_folds": [str(year) for year in range(2020, 2025)],
        "design": {
            "end_exclusive": DESIGN[1].isoformat(),
            "start_inclusive": DESIGN[0].isoformat(),
        },
        "outcome_horizon_hours": 168,
        "validation": {
            "end_exclusive": VALIDATION[1].isoformat(),
            "start_inclusive": VALIDATION[0].isoformat(),
        },
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "research windows drift")
    if manifest["combined_source"] != {
        "credentials": "NONE",
        "dataset_id": COMBINED_DATASET_ID,
        "fields": list(COMBINED_FIELDS),
        "locator": COMBINED_QUERY_URL,
        "publisher": "U.S. COMMODITY FUTURES TRADING COMMISSION",
        "report_variant": "FUTURES_AND_OPTIONS_COMBINED",
        "selection_end_exclusive": "2025-01-01T00:00:00.000",
        "selection_start_inclusive": "2020-01-01T00:00:00.000",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "combined source drift")
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
        "manifest_schema": "research_pipeline/btc-dra-cftc-tff-option-asset-manager-entry-admission-manifest.v1.schema.json",
        "primary_prior": "research_pipeline/examples/dra-cftc-option-asset-manager-primary-prior.v1.json",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_cftc_tff_option_asset_manager_entry_admission_historical_v1.py",
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
        raise reused.ScreenReject(
            "CONTRACT_REJECT", "manifest must be strict UTF-8 JSON"
        ) from error
    if raw != reused.canonical_document_bytes(value):
        raise reused.ScreenReject(
            "CONTRACT_REJECT", "manifest must use canonical JSON document bytes"
        )
    return validate_manifest(value), raw


def _source_integer(value: Any, label: str) -> D:
    if not isinstance(value, str):
        raise reused.ScreenReject("DATA_REJECT", f"{label} is not an exact nonnegative integer")
    normalized = value.strip()
    if _NONNEGATIVE_INTEGER.fullmatch(normalized) is None:
        raise reused.ScreenReject("DATA_REJECT", f"{label} is not an exact nonnegative integer")
    return D(normalized)


def load_combined_rows(raw: bytes) -> dict[date, dict[str, D]]:
    if len(raw) > 2_000_000:
        raise reused.ScreenReject("DATA_REJECT", "combined response is unexpectedly large")
    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("DATA_REJECT", "combined response is not strict JSON") from error
    if not isinstance(document, list) or not document:
        raise reused.ScreenReject("DATA_REJECT", "combined response has no rows")
    rows: dict[date, dict[str, D]] = {}
    for index, item in enumerate(document):
        if not isinstance(item, dict) or set(item) != set(COMBINED_FIELDS):
            raise reused.ScreenReject("DATA_REJECT", f"combined row {index} schema drift")
        timestamp = item["report_date_as_yyyy_mm_dd"]
        if not isinstance(timestamp, str):
            raise reused.ScreenReject("DATA_REJECT", f"combined row {index} date drift")
        try:
            report_date = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%S.%f").date()
        except ValueError as error:
            raise reused.ScreenReject("DATA_REJECT", f"combined row {index} date malformed") from error
        if not date(2020, 1, 1) <= report_date < date(2025, 1, 1):
            raise reused.ScreenReject("DATA_REJECT", f"combined row {index} date out of contract")
        if report_date in rows:
            raise reused.ScreenReject("DATA_REJECT", f"duplicate combined report date: {report_date}")
        rows[report_date] = {
            "open_interest": _source_integer(item["open_interest_all"], "combined open interest"),
            "asset_long": _source_integer(item["asset_mgr_positions_long"], "combined asset long"),
            "asset_short": _source_integer(item["asset_mgr_positions_short"], "combined asset short"),
        }
    return rows


def capture_combined_source(path: Path) -> bytes:
    if path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "combined source receipt already exists")
    request = urllib.request.Request(
        COMBINED_QUERY_URL,
        headers={"Accept": "application/json", "User-Agent": "Agora-Research/1.0"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise reused.ScreenReject("SOURCE_UNAVAILABLE", f"combined source HTTP {response.status}")
            content_type = response.headers.get_content_type()
            if content_type != "application/json":
                raise reused.ScreenReject("DATA_REJECT", "combined source content type drift")
            raw = response.read(2_000_001)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise reused.ScreenReject("SOURCE_UNAVAILABLE", "combined source request failed") from error
    load_combined_rows(raw)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    return raw


def _futures_integer(row: list[str], index: int, label: str) -> D:
    return _source_integer(row[index], label)


def option_asset_manager_net_pct_oi(
    futures_row: list[str], combined_row: dict[str, D]
) -> D:
    option_open_interest = combined_row["open_interest"] - _futures_integer(
        futures_row, FUTURES_OPEN_INTEREST_INDEX, "futures open interest"
    )
    if option_open_interest <= 0:
        raise reused.ScreenReject("FACTOR_UNAVAILABLE", "option open interest is nonpositive")
    option_long = combined_row["asset_long"] - _futures_integer(
        futures_row, FUTURES_ASSET_LONG_INDEX, "futures asset long"
    )
    option_short = combined_row["asset_short"] - _futures_integer(
        futures_row, FUTURES_ASSET_SHORT_INDEX, "futures asset short"
    )
    return D("100") * (option_long - option_short) / option_open_interest


def build_factor_points(
    futures_rows: dict[date, list[str]], combined_rows: dict[date, dict[str, D]]
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    points: list[dict[str, Any]] = []
    exclusions = {
        "DECISION_AT_OR_AFTER_CUTOFF": 0,
        "ION_DELAY": 0,
        "MISSING_COMBINED_REPORT": 0,
        "MISSING_EXACT_PREDECESSOR": 0,
        "NON_POSITIVE_OPTION_OPEN_INTEREST": 0,
        "NON_TUESDAY": 0,
    }
    excluded_dates = {
        day
        for day in futures_rows
        if day.weekday() != 1 or reused.ION_EXCLUSION_START <= day <= reused.ION_EXCLUSION_END
    }
    for day in sorted(futures_rows):
        if day.weekday() != 1:
            exclusions["NON_TUESDAY"] += 1
            continue
        if reused.ION_EXCLUSION_START <= day <= reused.ION_EXCLUSION_END:
            exclusions["ION_DELAY"] += 1
            continue
        if day not in combined_rows:
            exclusions["MISSING_COMBINED_REPORT"] += 1
            continue
        prior_day = day - timedelta(days=7)
        if (
            prior_day not in futures_rows
            or prior_day not in combined_rows
            or prior_day in excluded_dates
        ):
            exclusions["MISSING_EXACT_PREDECESSOR"] += 1
            continue
        eligible_at = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        try:
            current = option_asset_manager_net_pct_oi(
                futures_rows[day], combined_rows[day]
            )
            prior = option_asset_manager_net_pct_oi(
                futures_rows[prior_day], combined_rows[prior_day]
            )
        except reused.ScreenReject as error:
            if error.status != "FACTOR_UNAVAILABLE":
                raise
            exclusions["NON_POSITIVE_OPTION_OPEN_INTEREST"] += 1
            continue
        score = prior - current
        points.append(
            {
                "current_option_asset_manager_net_pct_oi": str(current),
                "eligible_at": eligible_at.isoformat(),
                "factor_delta": str(score),
                "factor_sign": 1 if score > 0 else -1 if score < 0 else 0,
                "prior_option_asset_manager_net_pct_oi": str(prior),
                "prior_report_date": prior_day.isoformat(),
                "report_date": day.isoformat(),
            }
        )
    return points, exclusions


def run_screen(
    manifest_path: Path,
    input_path: Path,
    combined_input_path: Path,
    output_path: Path,
    *,
    allow_capture: bool = False,
) -> dict[str, Any]:
    if output_path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = reused.verify_bindings(manifest)
    source_path = REPOSITORY_ROOT.joinpath(
        *bindings["historical_source_manifest"]["path"].split("/")
    )
    source_manifest, source_manifest_raw = reused.load_manifest(source_path)
    inherited_bindings = reused.verify_bindings(source_manifest)
    if source_manifest["dataset"] != manifest["dataset"] or source_manifest["economics"] != manifest["economics"]:
        raise reused.ScreenReject("CONTRACT_REJECT", "historical source is not economically matched")
    bars = reused.load_selection(input_path, source_manifest)
    futures_rows, archive_evidence = reused.load_historical_rows(source_manifest)
    if combined_input_path.exists():
        combined_raw = combined_input_path.read_bytes()
    elif allow_capture:
        combined_raw = capture_combined_source(combined_input_path)
    else:
        raise reused.ScreenReject("SOURCE_UNAVAILABLE", "combined source receipt is missing")
    combined_rows = load_combined_rows(combined_raw)
    factor_points, exclusions = build_factor_points(futures_rows, combined_rows)
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
        "design": reused.predictive_evidence(
            reused.build_predictive_episodes(bars, factor_points, DESIGN)
        ),
        "validation": reused.predictive_evidence(
            reused.build_predictive_episodes(bars, factor_points, VALIDATION)
        ),
    }
    passed = all(economic_checks.values()) and all(
        all(window["gates"].values()) for window in predictive.values()
    )
    result = {
        "archive_evidence": archive_evidence,
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "bindings": bindings,
        "combined_source_evidence": {
            "dataset_id": COMBINED_DATASET_ID,
            "locator": COMBINED_QUERY_URL,
            "raw_response_bytes": len(combined_raw),
            "raw_response_sha256": reused.sha256_bytes(combined_raw),
            "row_count": len(combined_rows),
        },
        "dataset": {
            "canonical_sha256": reused.base.data_hash(bars),
            "rows": len(bars),
            "selection_cutoff": SELECTION_CUTOFF.isoformat(),
        },
        "document_type": RESULT_TYPE,
        "economic_evidence": economics,
        "economic_gates": economic_checks,
        "experiment_id": EXPERIMENT_ID,
        "factor_identity": FACTOR_IDENTITY,
        "factor_point_exclusions": exclusions,
        "factor_points": factor_points,
        "gate_set": GATE_SET,
        "historical_source_manifest_sha256": reused.sha256_bytes(source_manifest_raw),
        "inherited_source_bindings": inherited_bindings,
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "predictive_evidence": predictive,
        "recommended_next_action": (
            "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_CFTC_OPTION_ASSET_MANAGER_RAW_CHANGE_FAMILY_WITHOUT_TUNING"
        ),
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "1",
        "scientific_claim": "RAW_OPTION_POSITION_CHANGE_TRANSFER_TEST_NOT_NON_MOMENTUM_RESIDUAL_REPLICATION",
        "status": (
            "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE"
            if passed
            else "NO_CANDIDATE_CLOSE_CFTC_OPTION_ASSET_MANAGER_RAW_CHANGE_FAMILY"
        ),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(reused.canonical_document_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--combined-input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--capture-combined", action="store_true")
    args = parser.parse_args()
    try:
        result = run_screen(
            args.manifest,
            args.input,
            args.combined_input,
            args.output,
            allow_capture=args.capture_combined,
        )
    except (reused.ScreenReject, reused.base.ResearchReject, ValueError) as error:
        print(
            json.dumps(
                {
                    "detail": getattr(error, "detail", str(error)),
                    "status": getattr(error, "status", "DATA_REJECT"),
                },
                ensure_ascii=False,
            )
        )
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
