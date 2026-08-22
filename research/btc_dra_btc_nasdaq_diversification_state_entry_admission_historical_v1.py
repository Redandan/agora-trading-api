#!/usr/bin/env python3
"""Preregistered lagged BTC-Nasdaq diversification-state admission screen for DRA V1."""

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
DOCUMENT_TYPE = "BTC_DRA_BTC_NASDAQ_DIVERSIFICATION_STATE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_BTC_NASDAQ_DIVERSIFICATION_STATE_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_BTC_NASDAQ_DIVERSIFICATION_STATE_ENTRY_ADMISSION_RUNNER_V1"
FACTOR_IDENTITY = "LAGGED_63_PAIR_BTC_NASDAQ_RETURN_CORRELATION_AT_OR_BELOW_WEEKLY_168H_V1"
EXPERIMENT_ID = "dra-btc-nasdaq-diversification-state-entry-admission-historical-v1"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = "BTC_DRA_BTC_NASDAQ_DIVERSIFICATION_STATE_ENTRY_ADMISSION_GATES_V1"
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION
EXPECTED_NASDAQ_ROWS = 1762
EXPECTED_NASDAQ_FIRST = date(2018, 1, 2)
EXPECTED_NASDAQ_LAST = date(2024, 12, 31)
EXPECTED_BTC_DAILY_ROWS = 2192
EXPECTED_BTC_FIRST = date(2019, 1, 1)
EXPECTED_BTC_LAST = date(2024, 12, 31)
CORRELATION_PAIRS = 63
PUBLICATION_LAG_DAYS = 1
FACTOR_VALID_HOURS = 168
PRIMARY_THRESHOLD = D("0.00")
NEIGHBOR_THRESHOLDS = (D("-0.10"), D("0.10"))
THRESHOLDS = (NEIGHBOR_THRESHOLDS[0], PRIMARY_THRESHOLD, NEIGHBOR_THRESHOLDS[1])


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
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_LAGGED_63_PAIR_BTC_NASDAQ_RETURN_CORRELATION_AT_OR_BELOW_THRESHOLD",
        "correlation_estimator": "DECIMAL_PEARSON_SIMPLE_CLOSE_TO_CLOSE_RETURNS",
        "factor_identity": FACTOR_IDENTITY,
        "primary_threshold": "0.00",
        "relation": "AT_OR_BELOW",
        "stability_neighbors": ["-0.10", "0.10"],
        "veto_action": "HOLD_CASH",
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "factor semantics drift")
    if manifest["availability"] != {
        "eligible_time": "FINAL_NASDAQ_BUSINESS_DATE_OF_ISO_WEEK_PLUS_1_CALENDAR_DAY_AT_00_00_UTC",
        "evaluation_frequency": "ONE_POINT_PER_ISO_WEEK",
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "missing_pair_action": "SKIP_WITHOUT_INTERPOLATION",
        "paired_business_day_returns": CORRELATION_PAIRS,
        "publication_lag_calendar_days": PUBLICATION_LAG_DAYS,
    }:
        raise reused.ScreenReject("CONTRACT_REJECT", "availability policy drift")
    if manifest["source"] != {
        "first_date": EXPECTED_NASDAQ_FIRST.isoformat(),
        "last_date": EXPECTED_NASDAQ_LAST.isoformat(),
        "normalized_bytes": 36406,
        "present_vintage": True,
        "publisher": "Nasdaq, Inc., redistributed by Federal Reserve Bank of St. Louis FRED",
        "rows": EXPECTED_NASDAQ_ROWS,
        "series_id": "NASDAQCOM",
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
        "hypothesis": "research_pipeline/examples/dra-btc-nasdaq-diversification-state-entry-admission-v1.hypothesis.json",
        "manifest_schema": "research_pipeline/btc-dra-btc-nasdaq-diversification-state-entry-admission-manifest.v1.schema.json",
        "normalized_source": ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/inputs/nasdaq-composite-2018-2024.csv",
        "primary_prior": "research_pipeline/examples/dra-btc-nasdaq-diversification-state-entry-admission-primary-prior.v1.json",
        "raw_source_response": ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/inputs/fred-nasdaqcom-2018-2024-raw.csv",
        "reused_economic_runner": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
        "runner": "research/btc_dra_btc_nasdaq_diversification_state_entry_admission_historical_v1.py",
        "source_bundle": ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/inputs/nasdaq-source-bundle.json",
        "source_metadata": "research_pipeline/examples/nasdaq-composite-daily-2018-2024.v1.source.json",
        "source_probe": "research/nasdaq_composite_source_probe.cjs",
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


def load_nasdaq(bindings: dict[str, Any]) -> tuple[dict[date, D], dict[str, Any]]:
    normalized_path = _resolved_binding(bindings, "normalized_source")
    raw = normalized_path.read_bytes()
    try:
        csv_rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise reused.ScreenReject("DATA_REJECT", "normalized NASDAQCOM source is not UTF-8") from error
    if not csv_rows or csv_rows[0] != ["date", "nasdaq_composite_close"]:
        raise reused.ScreenReject("DATA_REJECT", "normalized NASDAQCOM header drift")
    rows: dict[date, D] = {}
    for index, row in enumerate(csv_rows[1:]):
        if len(row) != 2:
            raise reused.ScreenReject("DATA_REJECT", f"normalized NASDAQCOM row malformed: {index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1])
        except (ValueError, InvalidOperation) as error:
            raise reused.ScreenReject("DATA_REJECT", f"normalized NASDAQCOM value malformed: {index}") from error
        if day in rows or day.weekday() > 4 or not value.is_finite() or value <= 0:
            raise reused.ScreenReject("DATA_REJECT", f"normalized NASDAQCOM identity drift: {index}")
        rows[day] = value
    ordered = sorted(rows)
    if len(ordered) != EXPECTED_NASDAQ_ROWS or ordered[0] != EXPECTED_NASDAQ_FIRST or ordered[-1] != EXPECTED_NASDAQ_LAST:
        raise reused.ScreenReject("DATA_REJECT", "normalized NASDAQCOM sample boundary drift")
    if any(current <= prior for prior, current in zip(ordered, ordered[1:], strict=False)):
        raise reused.ScreenReject("DATA_REJECT", "normalized NASDAQCOM ordering drift")

    bundle_path = _resolved_binding(bindings, "source_bundle")
    bundle_raw = bundle_path.read_bytes()
    try:
        bundle = json.loads(bundle_raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("DATA_REJECT", "NASDAQCOM source bundle is not strict JSON") from error
    if bundle_raw != reused.canonical_document_bytes(bundle):
        raise reused.ScreenReject("DATA_REJECT", "NASDAQCOM source bundle is not canonical")
    normalized = bundle.get("normalized_subset", {})
    source_raw = bundle.get("raw_response", {})
    if (
        bundle.get("status") != "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS"
        or normalized.get("path") != bindings["normalized_source"]["path"]
        or normalized.get("sha256") != bindings["normalized_source"]["sha256"]
        or normalized.get("rows") != EXPECTED_NASDAQ_ROWS
        or source_raw.get("path") != bindings["raw_source_response"]["path"]
        or source_raw.get("sha256") != bindings["raw_source_response"]["sha256"]
    ):
        raise reused.ScreenReject("DATA_REJECT", "NASDAQCOM source bundle cross-binding drift")
    metadata_path = _resolved_binding(bindings, "source_metadata")
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("DATA_REJECT", "NASDAQCOM metadata is not strict JSON") from error
    if (
        metadata.get("status") != "SEALED_HISTORICAL_SOURCE"
        or metadata.get("series_id") != "NASDAQCOM"
        or metadata.get("raw_bundle", {}).get("sha256") != bindings["source_bundle"]["sha256"]
        or metadata.get("sealed_subset", {}).get("sha256") != bindings["normalized_source"]["sha256"]
    ):
        raise reused.ScreenReject("DATA_REJECT", "NASDAQCOM metadata cross-binding drift")
    return rows, {
        "bundle_path": bindings["source_bundle"]["path"],
        "bundle_sha256": bindings["source_bundle"]["sha256"],
        "normalized_path": bindings["normalized_source"]["path"],
        "normalized_sha256": bindings["normalized_source"]["sha256"],
        "raw_response_path": bindings["raw_source_response"]["path"],
        "raw_response_sha256": bindings["raw_source_response"]["sha256"],
        "rows": len(rows),
        "present_vintage_revision_boundary": "ORIGINAL_NASDAQCOM_RELEASE_VINTAGES_AND_REVISIONS_MISSING_PROOF",
    }


def btc_daily_closes(bars: list[reused.base.Bar]) -> dict[date, D]:
    closes: dict[date, D] = {}
    for bar in bars:
        if bar.open_time.hour != 23 or bar.open_time.minute != 0 or bar.close_time != bar.open_time + timedelta(hours=1):
            continue
        day = bar.open_time.date()
        if day in closes:
            raise reused.ScreenReject("DATA_REJECT", f"duplicate BTC daily close: {day}")
        closes[day] = bar.close
    ordered = sorted(closes)
    if len(ordered) != EXPECTED_BTC_DAILY_ROWS or ordered[0] != EXPECTED_BTC_FIRST or ordered[-1] != EXPECTED_BTC_LAST:
        raise reused.ScreenReject("DATA_REJECT", "BTC daily close boundary drift")
    if any(current - prior != timedelta(days=1) for prior, current in zip(ordered, ordered[1:], strict=False)):
        raise reused.ScreenReject("DATA_REJECT", "BTC daily close continuity drift")
    return closes


def pearson(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 2:
        raise reused.ScreenReject("DATA_REJECT", "correlation sample length drift")
    count = D(len(left))
    left_mean = sum(left, D("0")) / count
    right_mean = sum(right, D("0")) / count
    covariance = sum(((x - left_mean) * (y - right_mean) for x, y in zip(left, right, strict=True)), D("0"))
    left_variance = sum(((x - left_mean) ** 2 for x in left), D("0"))
    right_variance = sum(((y - right_mean) ** 2 for y in right), D("0"))
    if left_variance <= 0 or right_variance <= 0:
        raise reused.ScreenReject("DATA_REJECT", "correlation variance is not positive")
    return covariance / (left_variance * right_variance).sqrt()


def build_factor_points(
    btc_closes: dict[date, D], nasdaq_closes: dict[date, D], threshold: D
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    if threshold not in THRESHOLDS:
        raise reused.ScreenReject("CONTRACT_REJECT", "unregistered diversification threshold")
    nasdaq_dates = sorted(nasdaq_closes)
    pairs: list[dict[str, Any]] = []
    skipped = 0
    for index in range(1, len(nasdaq_dates)):
        day = nasdaq_dates[index]
        prior_nasdaq_day = nasdaq_dates[index - 1]
        prior_btc_day = day - timedelta(days=1)
        if day not in btc_closes or prior_btc_day not in btc_closes:
            skipped += 1
            continue
        pairs.append({
            "date": day,
            "btc_return": btc_closes[day] / btc_closes[prior_btc_day] - D("1"),
            "nasdaq_return": nasdaq_closes[day] / nasdaq_closes[prior_nasdaq_day] - D("1"),
        })
    final_pair_by_iso_week: dict[tuple[int, int], int] = {}
    for index in range(CORRELATION_PAIRS - 1, len(pairs)):
        iso = pairs[index]["date"].isocalendar()
        final_pair_by_iso_week[(iso.year, iso.week)] = index
    points: list[dict[str, Any]] = []
    cutoff_count = 0
    for index in sorted(final_pair_by_iso_week.values()):
        window = pairs[index - CORRELATION_PAIRS + 1:index + 1]
        report_date = pairs[index]["date"]
        eligible_at = datetime.combine(report_date + timedelta(days=PUBLICATION_LAG_DAYS), datetime.min.time())
        if eligible_at >= SELECTION_CUTOFF:
            cutoff_count += 1
            continue
        correlation = pearson(
            [pair["btc_return"] for pair in window],
            [pair["nasdaq_return"] for pair in window],
        )
        points.append({
            "report_date": report_date.isoformat(),
            "prior_report_date": pairs[index - 1]["date"].isoformat(),
            "eligible_at": eligible_at.isoformat(),
            "factor_delta": str(threshold - correlation),
            "factor_sign": 1 if correlation <= threshold else -1,
            "pearson_correlation": str(correlation),
            "threshold": format(threshold, ".2f"),
            "window_first_pair_date": window[0]["date"].isoformat(),
            "window_pair_count": len(window),
        })
    return points, {
        "MISSING_INITIAL_63_PAIRED_RETURNS": CORRELATION_PAIRS - 1,
        "NASDAQ_DATES_SKIPPED_WITHOUT_SAME_DATE_BTC_PAIR": skipped,
        "NON_FINAL_ISO_WEEK_PAIR_EVALUATIONS": max(0, len(pairs) - (CORRELATION_PAIRS - 1) - len(final_pair_by_iso_week)),
        "DECISION_AT_OR_AFTER_CUTOFF": cutoff_count,
    }


def _run_variant(
    bars: list[reused.base.Bar], baseline: dict[str, Any], btc_closes: dict[date, D],
    nasdaq_closes: dict[date, D], threshold: D,
) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, int]]:
    factor_points, exclusions = build_factor_points(btc_closes, nasdaq_closes, threshold)
    original_factor_identity = reused.FACTOR_IDENTITY
    original_runner_identity = reused.RUNNER_IDENTITY
    try:
        reused.FACTOR_IDENTITY = f"{FACTOR_IDENTITY}_THRESHOLD_{format(threshold, '.2f')}"
        reused.RUNNER_IDENTITY = RUNNER_IDENTITY
        economics = reused.economic_evidence(bars, baseline, factor_points)
    finally:
        reused.FACTOR_IDENTITY = original_factor_identity
        reused.RUNNER_IDENTITY = original_runner_identity
    return economics, factor_points, exclusions


def _neighbor_stability_gates(variants: dict[str, dict[str, Any]], baseline: dict[str, Any]) -> dict[str, bool]:
    gates: dict[str, bool] = {}
    for threshold in NEIGHBOR_THRESHOLDS:
        threshold_text = format(threshold, ".2f")
        label = threshold_text.replace("-", "neg_").replace(".", "_")
        evidence = variants[threshold_text]["economic_evidence"]
        design = evidence["design"]
        validation = evidence["validation"]
        gates[f"neighbor_{label}_design_total_pnl_improves"] = reused._value(design, "total_pnl_usdt") > reused._value(baseline["design"], "total_pnl_usdt")
        gates[f"neighbor_{label}_validation_total_pnl_improves"] = reused._value(validation, "total_pnl_usdt") > reused._value(baseline["validation"], "total_pnl_usdt")
        gates[f"neighbor_{label}_validation_realized_pnl_improves"] = reused._value(validation, "realized_usdt") > reused._value(baseline["validation"], "realized_usdt")
        gates[f"neighbor_{label}_validation_drawdown_within_0_25pp"] = reused._value(validation, "max_drawdown_pct") <= reused._value(baseline["validation"], "max_drawdown_pct") + reused.DD_TOLERANCE_PP
        gates[f"neighbor_{label}_validation_interventions_at_least_4"] = int(validation["vetoed_signal_count"]) >= 4
    return gates


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = reused.verify_bindings(manifest)
    bars = reused.load_selection(input_path, manifest)
    nasdaq_closes, source_evidence = load_nasdaq(bindings)
    btc_closes = btc_daily_closes(bars)
    baseline = reused.parent_baseline(bars)
    variants: dict[str, dict[str, Any]] = {}
    primary_points: list[dict[str, Any]] = []
    primary_exclusions: dict[str, int] = {}
    for threshold in THRESHOLDS:
        economics, factor_points, exclusions = _run_variant(bars, baseline, btc_closes, nasdaq_closes, threshold)
        label = format(threshold, ".2f")
        variants[label] = {"economic_evidence": economics, "factor_point_count": len(factor_points), "threshold": label}
        if threshold == PRIMARY_THRESHOLD:
            primary_points = factor_points
            primary_exclusions = exclusions
    primary_economics = variants["0.00"]["economic_evidence"]
    primary_economic_gates = reused.economic_gates(primary_economics, baseline)
    primary_predictive = {
        "design": reused.predictive_evidence(reused.build_predictive_episodes(bars, primary_points, DESIGN)),
        "validation": reused.predictive_evidence(reused.build_predictive_episodes(bars, primary_points, VALIDATION)),
    }
    neighbor_stability_gates = _neighbor_stability_gates(variants, baseline)
    passed = all(primary_economic_gates.values()) and all(all(window["gates"].values()) for window in primary_predictive.values()) and all(neighbor_stability_gates.values())
    result = {
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "bindings": bindings,
        "dataset": {"canonical_sha256": reused.base.data_hash(bars), "rows": len(bars), "selection_cutoff": SELECTION_CUTOFF.isoformat()},
        "document_type": RESULT_TYPE,
        "experiment_id": EXPERIMENT_ID,
        "factor_identity": FACTOR_IDENTITY,
        "factor_point_exclusions": primary_exclusions,
        "gate_set": GATE_SET,
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "neighbor_stability_gates": neighbor_stability_gates,
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "primary_economic_gates": primary_economic_gates,
        "primary_factor_points": primary_points,
        "primary_predictive_evidence": primary_predictive,
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_BTC_NASDAQ_DIVERSIFICATION_STATE_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "1",
        "source_evidence": source_evidence,
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BTC_NASDAQ_DIVERSIFICATION_STATE_FAMILY",
        "variant_evidence": variants,
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
