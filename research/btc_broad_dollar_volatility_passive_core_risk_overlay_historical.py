#!/usr/bin/env python3
"""Preregistered broad-dollar volatility passive-core BTC risk overlay."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
from types import ModuleType


D = Decimal
ZERO = D("0")
ONE = D("1")
HALF = D("0.5")
HUNDRED = D("100")
REFERENCE_OBSERVATIONS = 52
ANNUALIZATION_WEEKS = D("52")

REPO_ROOT = Path(__file__).resolve().parents[1]
ULCER_SUPPORT_SOURCE = (
    REPO_ROOT / "research" / "btc_daily_ulcer28_passive_core_risk_overlay_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
ECONOMIC_BASE_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
EXECUTION_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_30d_volatility_target_40pct_historical.py"
)

EXPERIMENT_ID = "btc-broad-dollar-volatility-passive-core-risk-overlay-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_BROAD_DOLLAR_VOLATILITY_PASSIVE_CORE_RISK_OVERLAY_HISTORICAL_MANIFEST_V1"
)
EXPECTED_DATA_SHA256 = (
    "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
)
EXPECTED_DATA_ROWS = 52_608
EXPECTED_WEEKLY_ROWS = 365
EXPECTED_FIRST_WEEK = date(2018, 1, 5)
EXPECTED_LAST_WEEK = date(2024, 12, 27)

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = (
    ("PRIMARY_DTWEXBGS_VOL13", "PRIMARY", 13),
    ("REJECTION_ONLY_DTWEXBGS_VOL8", "REJECTION_ONLY", 8),
    ("REJECTION_ONLY_DTWEXBGS_VOL26", "REJECTION_ONLY", 26),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DollarVolatilityPoint:
    effective_time: datetime
    value: D
    lagged_median: D | None


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:OOS")
    policy = manifest.get("strategy_policy", {})
    if policy.get("primary") != {
        "lookback_weeks": 13,
        "reference_observations": 52,
        "high_risk_relation": "CURRENT_VOLATILITY_STRICTLY_GREATER_THAN_LAGGED_MEDIAN",
    }:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"lookback_weeks": 8, "reference_observations": 52},
        {"lookback_weeks": 26, "reference_observations": 52},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("availability") != "WEEK_ENDING_FRIDAY_PLUS_5D_0000_UTC":
        raise ResearchReject("MANIFEST_REJECT:AVAILABILITY")
    if policy.get("rebalance_rule") != "ONLY_WHEN_REGIME_TARGET_CHANGES":
        raise ResearchReject("MANIFEST_REJECT:REBALANCE_RULE")
    if policy.get("high_risk_target") != "BTC_50_PERCENT_CASH_50_PERCENT":
        raise ResearchReject("MANIFEST_REJECT:HIGH_RISK_TARGET")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) < 8:
        raise ResearchReject("MANIFEST_REJECT:BINDINGS")


def verified_bindings(manifest: dict[str, object]) -> dict[str, Path]:
    output: dict[str, Path] = {}
    for raw in manifest["source_bindings"]:
        if not isinstance(raw, dict) or set(raw) != {"path", "role", "sha256"}:
            raise ResearchReject("MANIFEST_REJECT:BINDING_FIELDS")
        relative = raw["path"]
        digest = raw["sha256"]
        if not isinstance(relative, str) or not isinstance(digest, str):
            raise ResearchReject("MANIFEST_REJECT:BINDING_TYPES")
        path = (REPO_ROOT / relative).resolve(strict=True)
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject("SOURCE_REJECT:BINDING_ESCAPE")
        if sha256(path) != digest:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{relative}")
        output[raw["role"]] = path
    required = {
        "FROZEN_DOLLAR_VOLATILITY_RUNNER",
        "FROZEN_FRACTIONAL_LEDGER_SUPPORT",
        "FROZEN_PASSIVE_PATH_METRICS_SUPPORT",
        "FROZEN_ULCER_GATE_SUPPORT",
        "FROZEN_H1_PARSER",
        "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
        "FROZEN_SCHEMA_VALID_HYPOTHESIS",
        "SEALED_DTWEXBGS_WEEKLY_SOURCE",
        "SEALED_DTWEXBGS_SOURCE_BUNDLE",
        "SEALED_DTWEXBGS_RAW_ARCHIVE",
        "SEALED_PREVIOUS_DIRECTION_FAMILY_DECISION",
    }
    if set(output) != required:
        raise ResearchReject("MANIFEST_REJECT:BINDING_ROLES")
    return output


def load_weekly_dollar(
    csv_path: Path, bundle_path: Path, archive_path: Path
) -> list[tuple[date, D]]:
    raw = csv_path.read_bytes()
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise ResearchReject("DATA_REJECT:DTWEXBGS_UTF8") from error
    if not rows or rows[0] != ["week_ending_friday", "dtwexbgs_mean"]:
        raise ResearchReject("DATA_REJECT:DTWEXBGS_HEADER")
    output: list[tuple[date, D]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise ResearchReject(f"DATA_REJECT:DTWEXBGS_ROW:{index}")
        try:
            week = date.fromisoformat(row[0])
            value = D(row[1])
        except Exception as error:
            raise ResearchReject(f"DATA_REJECT:DTWEXBGS_VALUE:{index}") from error
        if week.weekday() != 4 or not value.is_finite() or value <= ZERO:
            raise ResearchReject(f"DATA_REJECT:DTWEXBGS_IDENTITY:{index}")
        output.append((week, value))
    if (
        len(output) != EXPECTED_WEEKLY_ROWS
        or output[0][0] != EXPECTED_FIRST_WEEK
        or output[-1][0] != EXPECTED_LAST_WEEK
    ):
        raise ResearchReject("DATA_REJECT:DTWEXBGS_BOUNDARY")
    if any(
        current[0] - prior[0] != timedelta(days=7)
        for prior, current in zip(output, output[1:], strict=False)
    ):
        raise ResearchReject("DATA_REJECT:DTWEXBGS_CONTINUITY")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    normalized = bundle.get("normalized_weekly_subset", {})
    archive = bundle.get("raw_response_archive", {})
    if (
        bundle.get("status") != "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS"
        or normalized.get("sha256") != sha256(csv_path)
        or normalized.get("rows") != EXPECTED_WEEKLY_ROWS
        or archive.get("sha256") != sha256(archive_path)
    ):
        raise ResearchReject("DATA_REJECT:DTWEXBGS_BUNDLE")
    return output


def build_volatility_points(
    weekly: list[tuple[date, D]], lookback: int, base: ModuleType
) -> list[DollarVolatilityPoint]:
    if lookback not in {8, 13, 26}:
        raise ResearchReject(f"MANIFEST_REJECT:LOOKBACK:{lookback}")
    returns = [
        (weekly[index][1] / weekly[index - 1][1]).ln()
        for index in range(1, len(weekly))
    ]
    raw: list[tuple[datetime, D]] = []
    for end_index in range(lookback - 1, len(returns)):
        window = returns[end_index - lookback + 1 : end_index + 1]
        value = (
            sum((item * item for item in window), ZERO)
            / D(lookback)
            * ANNUALIZATION_WEEKS
        ).sqrt() * HUNDRED
        week_end = weekly[end_index + 1][0]
        raw.append((datetime.combine(week_end + timedelta(days=5), datetime.min.time()), value))
    output: list[DollarVolatilityPoint] = []
    for index, (effective_time, value) in enumerate(raw):
        lagged_median = None
        if index >= REFERENCE_OBSERVATIONS:
            lagged_median = base.percentile(
                [item[1] for item in raw[index - REFERENCE_OBSERVATIONS : index]],
                D("0.5"),
            )
            if lagged_median is None:
                raise ResearchReject("FEATURE_REJECT:LAGGED_MEDIAN")
        output.append(DollarVolatilityPoint(effective_time, value, lagged_median))
    return output


def build_daily_targets(
    points: list[DollarVolatilityPoint], start: datetime, end: datetime
) -> dict[datetime, D]:
    available = [point for point in points if point.lagged_median is not None]
    if not available or available[0].effective_time > start:
        raise ResearchReject("FEATURE_REJECT:PREWINDOW_HISTORY")
    targets: dict[datetime, D] = {}
    point_index = 0
    current: DollarVolatilityPoint | None = None
    day = start
    while day < end:
        while point_index < len(available) and available[point_index].effective_time <= day:
            current = available[point_index]
            point_index += 1
        if current is None:
            raise ResearchReject("FEATURE_REJECT:MISSING_DAILY_STATE")
        targets[day] = HALF if current.value > current.lagged_median else ONE
        day += timedelta(days=1)
    return targets


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    bindings = verified_bindings(manifest)
    data_sha = sha256(input_path)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")
    parser = load_module("dollar_volatility_h1_parser", PARSER_SOURCE)
    base = load_module("dollar_volatility_economic_base", ECONOMIC_BASE_SOURCE)
    execution = load_module("dollar_volatility_fractional_execution", EXECUTION_SOURCE)
    gates = load_module("dollar_volatility_gate_support", ULCER_SUPPORT_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:H1_ROWS_OR_HASH")
    weekly = load_weekly_dollar(
        bindings["SEALED_DTWEXBGS_WEEKLY_SOURCE"],
        bindings["SEALED_DTWEXBGS_SOURCE_BUNDLE"],
        bindings["SEALED_DTWEXBGS_RAW_ARCHIVE"],
    )

    variants: list[dict[str, object]] = []
    failed_neighbors: dict[str, list[str]] = {}
    failed_primary: list[str] = []
    for variant_id, role, lookback in VARIANTS:
        points = build_volatility_points(weekly, lookback, base)
        targets = build_daily_targets(points, DESIGN[0], VALIDATION[1])
        design_output, design_raw = gates.simulate_window(
            bars, targets, DESIGN, base, execution
        )
        validation_output, validation_raw = gates.simulate_window(
            bars, targets, VALIDATION, base, execution
        )
        annual_outputs = {
            year: gates.simulate_window(bars, targets, window, base, execution)
            for year, window in ANNUAL.items()
        }
        annual_breadth = gates.breadth(
            {year: value[1] for year, value in annual_outputs.items()}, base
        )
        gate_breadth = dict(annual_breadth)
        annual_breadth.pop("top_year_raw")
        available = [point for point in points if point.lagged_median is not None]
        feature = {
            "lookback_weeks": lookback,
            "reference_observations": REFERENCE_OBSERVATIONS,
            "available_observations": len(available),
            "first_effective_time": available[0].effective_time.isoformat(),
            "last_effective_time": available[-1].effective_time.isoformat(),
            "minimum_annualized_rms_log_return_volatility_pct": base.q(min(point.value for point in available)),
            "median_annualized_rms_log_return_volatility_pct": base.q(base.percentile([point.value for point in available], D("0.5")) or ZERO),
            "maximum_annualized_rms_log_return_volatility_pct": base.q(max(point.value for point in available)),
        }
        variant: dict[str, object] = {
            "variant_id": variant_id,
            "role": role,
            "lookback_weeks": lookback,
            "feature": feature,
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {
                year: value[0] for year, value in annual_outputs.items()
            },
            "breadth_and_concentration": annual_breadth,
        }
        if role == "PRIMARY":
            primary_checks = gates.primary_gates(
                design_raw, validation_raw, gate_breadth
            )
            variant["primary_gates"] = primary_checks
            failed_primary = [
                name for name, passed in primary_checks.items() if not passed
            ]
        else:
            neighbor_checks = gates.neighbor_gates(
                design_raw, validation_raw, gate_breadth
            )
            variant["neighbor_gates"] = neighbor_checks
            failed = [name for name, passed in neighbor_checks.items() if not passed]
            if failed:
                failed_neighbors[variant_id] = failed
        variants.append(variant)

    passed = not failed_primary and not failed_neighbors
    return {
        "schema_version": "1",
        "document_type": "BTC_BROAD_DOLLAR_VOLATILITY_PASSIVE_CORE_RISK_OVERLAY_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_BROAD_DOLLAR_VOLATILITY_PASSIVE_CORE_RISK_OVERLAY_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_NEIGHBOR_GATES_PASS_SEALED_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_DTWEXBGS_VOL8_13_26_LAGGED_MEDIAN_100_50_PASSIVE_CORE_FAMILY_WITHOUT_TUNING"
        ),
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": data_sha,
            "hourly_rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "source": {
            "series_id": "DTWEXBGS",
            "weekly_rows": len(weekly),
            "first_week": weekly[0][0].isoformat(),
            "last_week": weekly[-1][0].isoformat(),
            "present_vintage_revision_boundary": "ORIGINAL_H10_RELEASE_VALUES_AND_REVISIONS_MISSING_PROOF",
        },
        "policy": {
            "volatility_formula": "ANNUALIZED_RMS_OF_WEEKLY_LOG_CHANGES",
            "lagged_reference": "MEDIAN_PRIOR_52_AVAILABLE_VOLATILITY_VALUES_EXCLUDING_CURRENT",
            "primary_lookback_weeks": 13,
            "rejection_only_neighbor_lookback_weeks": [8, 26],
            "availability": "WEEK_ENDING_FRIDAY_PLUS_5D_0000_UTC",
            "low_risk_target": "BTC_100_PERCENT",
            "high_risk_target": "BTC_50_PERCENT_CASH_50_PERCENT",
            "rebalance": "ONLY_ON_REGIME_TARGET_CHANGE_AT_NEXT_H1_OPEN",
            "variants": 3,
        },
        "variants": variants,
        "failed_primary_gates": failed_primary,
        "failed_neighbor_gates": failed_neighbors,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass requires separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, network request, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
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
        "failed_neighbor_gates": result["failed_neighbor_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
