#!/usr/bin/env python3
"""Preregistered on-chain activity breadth admission screen for BTC DRA V1."""

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
DOCUMENT_TYPE = "BTC_DRA_ONCHAIN_ACTIVITY_BREADTH_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_ONCHAIN_ACTIVITY_BREADTH_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_ONCHAIN_ACTIVITY_BREADTH_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITIES = {
    "PRIMARY_BOTH": "COIN_METRICS_BTC_TXCNT_ADRACTCNT_28D_MEAN_ABOVE_364D_LAGGED_MEAN_D_PLUS_2_DRA_ENTRY_ADMISSION_V1",
    "NEIGHBOR_TXCNT_ONLY": "COIN_METRICS_BTC_TXCNT_28D_MEAN_ABOVE_364D_LAGGED_MEAN_D_PLUS_2_DRA_ENTRY_ADMISSION_V1",
    "NEIGHBOR_ADRACTCNT_ONLY": "COIN_METRICS_BTC_ADRACTCNT_28D_MEAN_ABOVE_364D_LAGGED_MEAN_D_PLUS_2_DRA_ENTRY_ADMISSION_V1",
}
EXPERIMENT_ID = "dra-onchain-activity-breadth-entry-admission-historical-v1"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = reused.GATE_SET
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION
EXPECTED_ROWS = 2557
EXPECTED_FIRST = date(2018, 1, 1)
EXPECTED_LAST = date(2024, 12, 31)
MEAN_DAYS = 28
COMPARISON_LAG_DAYS = 364
PUBLICATION_LAG_DAYS = 2
FACTOR_VALID_HOURS = 24
VARIANT_ORDER = ("PRIMARY_BOTH", "NEIGHBOR_TXCNT_ONLY", "NEIGHBOR_ADRACTCNT_ONLY")


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
    if manifest["dataset"] != {
        "canonical_sha256": reused.base.SELECTION_SHA256,
        "rows": reused.base.SELECTION_ROWS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "dataset identity drift")
    if manifest["economics"] != {
        "fee_rate": "0.0010", "initial_equity_usdt": "250",
        "slippage_rate": "0.0005", "slot_capacity_usdt": "240",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "economic assumptions drift")
    if manifest["factor"] != {
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_BOTH_LAGGED_NETWORK_ACTIVITY_AXES_ARE_POSITIVE",
        "comparison_lag_calendar_days": COMPARISON_LAG_DAYS,
        "current_mean_days": MEAN_DAYS,
        "factor_identity": FACTOR_IDENTITIES["PRIMARY_BOTH"],
        "neighbor_modes": ["TXCNT_ONLY", "ADRACTCNT_ONLY"],
        "negative_action": "HOLD_CASH",
        "positive_action": "ADMIT",
        "primary_mode": "BOTH_AXES",
        "series_ids": ["TxCnt", "AdrActCnt"],
        "zero_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "OBSERVATION_DAY_PLUS_2_CALENDAR_DAYS_AT_00_00_UTC",
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "predictive_sampling": "SUNDAY_OBSERVATION_ONLY_FOR_NON_OVERLAPPING_168H_EPISODES",
        "publication_lag_calendar_days": PUBLICATION_LAG_DAYS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["source"] != {
        "first_date": EXPECTED_FIRST.isoformat(),
        "last_date": EXPECTED_LAST.isoformat(),
        "metrics": ["TxCnt", "AdrActCnt"],
        "normalized_bytes": 64411,
        "present_vintage": True,
        "publisher": "Coin Metrics Community API",
        "rows": EXPECTED_ROWS,
        "sampling": "DAILY_BTC_BOTH_ACTIVITY_AXES_PRESENT",
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
            "base_runner", "capacity_runner", "manifest_schema", "negative_long_cash_decision",
            "normalized_source", "primary_prior", "raw_source_response", "reused_economic_runner",
            "runner", "source_bundle", "source_metadata", "source_probe",
        },
        "bindings",
    )
    expected_paths = {
        "base_runner": "research/btc_dra_reversal_confirmed_exit_v2c.py",
        "capacity_runner": "research/btc_dra_equal_capital_capacity_v1.py",
        "manifest_schema": "research_pipeline/btc-dra-onchain-activity-breadth-entry-admission-manifest.v1.schema.json",
        "negative_long_cash_decision": "research_pipeline/examples/btc-onchain-activity-breadth-long-cash-historical.v1.decision.json",
        "normalized_source": ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-btc-onchain-activity-2018-2024.csv",
        "primary_prior": "research_pipeline/examples/dra-onchain-activity-breadth-entry-admission-primary-prior.v1.json",
        "raw_source_response": ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-btc-onchain-activity-2018-2024-raw.json",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_onchain_activity_breadth_entry_admission_historical_v1.py",
        "source_bundle": ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-source-bundle.json",
        "source_metadata": "research_pipeline/examples/coinmetrics-btc-onchain-activity-daily-2018-2024.v1.source.json",
        "source_probe": "research/coinmetrics_btc_onchain_activity_source_probe.cjs",
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


def load_activity_rows(
    bindings: dict[str, Any],
) -> tuple[dict[date, tuple[D, D]], dict[str, Any]]:
    normalized_path = _resolved_binding(bindings, "normalized_source")
    raw = normalized_path.read_bytes()
    try:
        csv_rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise reused.ScreenReject("DATA_REJECT", "normalized activity source is not UTF-8") from error
    if not csv_rows or csv_rows[0] != ["date", "active_address_count", "transaction_count"]:
        raise reused.ScreenReject("DATA_REJECT", "normalized activity header drift")
    rows: dict[date, tuple[D, D]] = {}
    for index, row in enumerate(csv_rows[1:]):
        if len(row) != 3:
            raise reused.ScreenReject("DATA_REJECT", f"normalized activity row malformed: {index}")
        try:
            day = date.fromisoformat(row[0])
            address_count, transaction_count = D(row[1]), D(row[2])
        except (ValueError, InvalidOperation) as error:
            raise reused.ScreenReject("DATA_REJECT", f"normalized activity value malformed: {index}") from error
        if day in rows or any(
            not value.is_finite() or value <= 0 or value != value.to_integral_value()
            for value in (address_count, transaction_count)
        ):
            raise reused.ScreenReject("DATA_REJECT", f"normalized activity identity drift: {index}")
        rows[day] = (transaction_count, address_count)
    ordered = sorted(rows)
    if len(ordered) != EXPECTED_ROWS or ordered[0] != EXPECTED_FIRST or ordered[-1] != EXPECTED_LAST:
        raise reused.ScreenReject("DATA_REJECT", "normalized activity sample boundary drift")
    if len(raw) != 64411 or any(
        current - prior != timedelta(days=1)
        for prior, current in zip(ordered, ordered[1:], strict=False)
    ):
        raise reused.ScreenReject("DATA_REJECT", "normalized activity continuity drift")

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
        bundle.get("status") != "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS"
        or normalized.get("path") != bindings["normalized_source"]["path"]
        or normalized.get("sha256") != bindings["normalized_source"]["sha256"]
        or normalized.get("rows") != EXPECTED_ROWS
        or source_raw.get("path") != bindings["raw_source_response"]["path"]
        or source_raw.get("sha256") != bindings["raw_source_response"]["sha256"]
        or source_raw.get("rows") != EXPECTED_ROWS
    ):
        raise reused.ScreenReject("DATA_REJECT", "source bundle cross-binding drift")
    metadata = json.loads(_resolved_binding(bindings, "source_metadata").read_text(encoding="utf-8"))
    if (
        metadata.get("status") != "SEALED_HISTORICAL_SOURCE"
        or metadata.get("sealed_subset", {}).get("sha256") != bindings["normalized_source"]["sha256"]
        or metadata.get("raw_bundle", {}).get("sha256") != bindings["source_bundle"]["sha256"]
        or metadata.get("raw_response", {}).get("sha256") != bindings["raw_source_response"]["sha256"]
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
        "present_vintage_revision_boundary": "ORIGINAL_DAILY_REVIEW_TIMESTAMPS_AND_VINTAGES_MISSING_PROOF",
    }


def build_factor_points(
    rows: dict[date, tuple[D, D]], mode: str
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    if mode not in VARIANT_ORDER:
        raise reused.ScreenReject("CONTRACT_REJECT", f"unknown variant: {mode}")
    ordered = sorted(rows)
    tx_prefix = [D("0")]
    address_prefix = [D("0")]
    for day in ordered:
        tx_prefix.append(tx_prefix[-1] + rows[day][0])
        address_prefix.append(address_prefix[-1] + rows[day][1])
    points: list[dict[str, Any]] = []
    warmup_days = COMPARISON_LAG_DAYS + MEAN_DAYS - 1
    exclusions = {
        "MISSING_CURRENT_AND_LAGGED_28D_WINDOWS": warmup_days,
        "DECISION_AT_OR_AFTER_CUTOFF": 0,
    }
    for index in range(warmup_days, len(ordered)):
        report_day = ordered[index]
        eligible_at = datetime.combine(
            report_day + timedelta(days=PUBLICATION_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        current_start = index - MEAN_DAYS + 1
        prior_end = index - COMPARISON_LAG_DAYS
        prior_start = prior_end - MEAN_DAYS + 1
        current_tx = tx_prefix[index + 1] - tx_prefix[current_start]
        prior_tx = tx_prefix[prior_end + 1] - tx_prefix[prior_start]
        current_address = address_prefix[index + 1] - address_prefix[current_start]
        prior_address = address_prefix[prior_end + 1] - address_prefix[prior_start]
        tx_growth = current_tx / prior_tx - D("1")
        address_growth = current_address / prior_address - D("1")
        score = (
            min(tx_growth, address_growth)
            if mode == "PRIMARY_BOTH"
            else tx_growth if mode == "NEIGHBOR_TXCNT_ONLY" else address_growth
        )
        points.append({
            "report_date": report_day.isoformat(),
            "prior_report_date": ordered[prior_end].isoformat(),
            "eligible_at": eligible_at.isoformat(),
            "factor_delta": str(score),
            "factor_sign": 1 if score > 0 else -1 if score < 0 else 0,
            "transaction_count_growth": str(tx_growth),
            "active_address_count_growth": str(address_growth),
        })
    return points, exclusions


def _variant_evidence(
    bars: list[reused.base.Bar], baseline: dict[str, Any], points: list[dict[str, Any]], mode: str
) -> dict[str, Any]:
    original_factor_identity = reused.FACTOR_IDENTITY
    original_runner_identity = reused.RUNNER_IDENTITY
    original_valid_hours = reused.FACTOR_VALID_HOURS
    try:
        reused.FACTOR_IDENTITY = FACTOR_IDENTITIES[mode]
        reused.RUNNER_IDENTITY = RUNNER_IDENTITY
        reused.FACTOR_VALID_HOURS = FACTOR_VALID_HOURS
        economics = reused.economic_evidence(bars, baseline, points)
    finally:
        reused.FACTOR_IDENTITY = original_factor_identity
        reused.RUNNER_IDENTITY = original_runner_identity
        reused.FACTOR_VALID_HOURS = original_valid_hours
    weekly_points = [
        point for point in points if date.fromisoformat(point["report_date"]).weekday() == 6
    ]
    return {
        "economic_evidence": economics,
        "economic_gates": reused.economic_gates(economics, baseline),
        "predictive_evidence": {
            "design": reused.predictive_evidence(
                reused.build_predictive_episodes(bars, weekly_points, DESIGN)
            ),
            "validation": reused.predictive_evidence(
                reused.build_predictive_episodes(bars, weekly_points, VALIDATION)
            ),
        },
    }


def _neighbor_gates(evidence: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    candidate = evidence["economic_evidence"]["validation"]
    parent = baseline["validation"]
    return {
        "validation_total_pnl_non_worse": D(candidate["total_pnl_usdt"]) >= D(parent["total_pnl_usdt"]),
        "validation_realized_pnl_non_worse": D(candidate["realized_usdt"]) >= D(parent["realized_usdt"]),
        "validation_drawdown_within_0_25pp": D(candidate["max_drawdown_pct"]) <= D(parent["max_drawdown_pct"]) + reused.DD_TOLERANCE_PP,
        "validation_underwater_duration_non_worse": int(candidate["inventory_path"]["maximum_underwater_duration_hours"]) <= int(parent["inventory_path"]["maximum_underwater_duration_hours"]),
        "validation_terminal_inventory_count_non_worse": len(candidate["terminal_inventory"]) <= len(parent["terminal_inventory"]),
        "validation_interventions_at_least_4": int(candidate["vetoed_signal_count"]) >= 4,
    }


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = reused.verify_bindings(manifest)
    bars = reused.load_selection(input_path, manifest)
    rows, source_evidence = load_activity_rows(bindings)
    baseline = reused.parent_baseline(bars)
    variants: dict[str, Any] = {}
    exclusions: dict[str, Any] = {}
    point_counts: dict[str, int] = {}
    for mode in VARIANT_ORDER:
        points, mode_exclusions = build_factor_points(rows, mode)
        variants[mode] = _variant_evidence(bars, baseline, points, mode)
        exclusions[mode] = mode_exclusions
        point_counts[mode] = len(points)
    primary = variants["PRIMARY_BOTH"]
    neighbor_stability = {
        mode: _neighbor_gates(variants[mode], baseline)
        for mode in VARIANT_ORDER[1:]
    }
    passed = (
        all(primary["economic_gates"].values())
        and all(
            all(window["gates"].values())
            for window in primary["predictive_evidence"].values()
        )
        and all(all(gates.values()) for gates in neighbor_stability.values())
    )
    result = {
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "bindings": bindings,
        "dataset": {
            "canonical_sha256": reused.base.data_hash(bars),
            "rows": len(bars),
            "selection_cutoff": SELECTION_CUTOFF.isoformat(),
        },
        "document_type": RESULT_TYPE,
        "experiment_id": EXPERIMENT_ID,
        "factor_identities": FACTOR_IDENTITIES,
        "factor_point_counts": point_counts,
        "factor_point_exclusions": exclusions,
        "gate_set": GATE_SET,
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "neighbor_stability_gates": neighbor_stability,
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "primary_gates": primary["economic_gates"],
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_ONCHAIN_ACTIVITY_BREADTH_DRA_ADMISSION_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "1",
        "source_evidence": source_evidence,
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_ONCHAIN_ACTIVITY_BREADTH_DRA_ADMISSION_FAMILY",
        "variants": variants,
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
        print(json.dumps({
            "detail": getattr(error, "detail", str(error)),
            "status": getattr(error, "status", "DATA_REJECT"),
        }, ensure_ascii=False))
        return 2
    print(json.dumps({
        "oos_opened": result["oos_opened"],
        "status": result["status"],
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
