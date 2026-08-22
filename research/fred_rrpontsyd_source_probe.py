#!/usr/bin/env python3
"""Seal one present-vintage FRED RRPONTSYD source without opening BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=RRPONTSYD&cosd=2018-01-01&coed=2024-12-31"
EXPECTED_FIRST_MIN = date(2018, 1, 1)
EXPECTED_FIRST_MAX = date(2018, 1, 5)
EXPECTED_LAST = date(2024, 12, 31)
MIN_ROWS = 1_700
MAX_ROWS = 1_900
COMPLETE_WEEKS = 365
LOOKBACK_WEEKS = 4
AVAILABILITY_LAG_DAYS = 3
MAX_RESPONSE_BYTES = 256 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES = {
    "design": {"minimum_evaluations": 180, "minimum_per_state": 40},
    "validation": {"minimum_evaluations": 90, "minimum_per_state": 20},
}
DECIMAL = re.compile(r"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


class SourceReject(RuntimeError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def parse_rows(raw: bytes) -> list[tuple[date, Decimal | None, str]]:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    rows = list(csv.reader(io.StringIO(text, newline="")))
    if not rows or rows[0] != ["observation_date", "RRPONTSYD"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, Decimal | None, str]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2 or (row[1] and not DECIMAL.fullmatch(row[1])):
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = Decimal(row[1]) if row[1] else None
        except (ValueError, InvalidOperation) as error:
            raise SourceReject(f"SOURCE_REJECT:VALUE_OR_DATE:{index}") from error
        if value is not None and (value < 0 or value > Decimal("5000")):
            raise SourceReject(f"SOURCE_REJECT:VALUE_RANGE:{index}")
        parsed.append((day, value, row[1]))
    return parsed


def validate_rows(rows: list[tuple[date, Decimal | None, str]]) -> None:
    if not MIN_ROWS <= len(rows) <= MAX_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if not EXPECTED_FIRST_MIN <= rows[0][0] <= EXPECTED_FIRST_MAX:
        raise SourceReject("SOURCE_REJECT:FIRST_BOUNDARY")
    if rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:LAST_BOUNDARY")
    counts_by_year = {year: 0 for year in range(2018, 2025)}
    for index, (current, following) in enumerate(
        zip(rows, rows[1:], strict=False), start=1
    ):
        counts_by_year[current[0].year] = counts_by_year.get(current[0].year, 0) + 1
        gap = following[0] - current[0]
        if gap <= timedelta(0) or gap > timedelta(days=5):
            raise SourceReject(f"SOURCE_REJECT:DATE_ORDER_OR_GAP:{index}")
    counts_by_year[rows[-1][0].year] = counts_by_year.get(rows[-1][0].year, 0) + 1
    if any(counts_by_year.get(year, 0) < 240 for year in range(2018, 2025)):
        raise SourceReject(f"SOURCE_REJECT:ANNUAL_COVERAGE:{counts_by_year}")


def _summarize_window(
    states: list[tuple[datetime, bool]],
    start: datetime,
    end: datetime,
    gate: dict[str, int],
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    supportive = sum(state[1] for state in selected)
    other = len(selected) - supportive
    return {
        "evaluations": len(selected),
        "supportive_weeks": supportive,
        "other_weeks": other,
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z")
        if selected
        else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z")
        if selected
        else None,
        "support_gate": gate,
        "support_pass": len(selected) >= gate["minimum_evaluations"]
        and supportive >= gate["minimum_per_state"]
        and other >= gate["minimum_per_state"],
    }


def feature_feasibility(rows: list[tuple[date, Decimal | None, str]]) -> dict[str, Any]:
    by_week: dict[date, tuple[date, Decimal]] = {}
    for day, value, _ in rows:
        if value is None:
            continue
        week_start = day - timedelta(days=day.weekday())
        if week_start > date(2024, 12, 23):
            continue
        prior = by_week.get(week_start)
        if prior is None or day > prior[0]:
            by_week[week_start] = (day, value)
    expected_starts = [date(2018, 1, 1) + timedelta(days=7 * index) for index in range(COMPLETE_WEEKS)]
    if any(week not in by_week for week in expected_starts):
        missing = [week.isoformat() for week in expected_starts if week not in by_week]
        raise SourceReject(f"SOURCE_REJECT:EMPTY_COMPLETE_WEEK:{','.join(missing[:5])}")
    endpoints = [by_week[week] for week in expected_starts]
    states: list[tuple[datetime, bool]] = []
    endpoint_days: list[str] = []
    for index in range(LOOKBACK_WEEKS, len(endpoints)):
        week_start = expected_starts[index]
        effective = datetime.combine(
            week_start + timedelta(days=6 + AVAILABILITY_LAG_DAYS),
            time.min,
            tzinfo=timezone.utc,
        )
        states.append((effective, endpoints[index][1] < endpoints[index - LOOKBACK_WEEKS][1]))
        endpoint_days.append(endpoints[index][0].isoformat())
    transitions = sum(
        current[1] != prior[1]
        for prior, current in zip(states, states[1:], strict=False)
    )
    design = _summarize_window(
        states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"]
    )
    validation = _summarize_window(
        states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"]
    )
    return {
        "complete_week_count": len(endpoints),
        "evaluations": len(states),
        "supportive_weeks": sum(state[1] for state in states),
        "other_weeks": sum(not state[1] for state in states),
        "transitions": transitions,
        "first_evaluable_endpoint_day": endpoint_days[0],
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_evaluable_endpoint_day": endpoint_days[-1],
        "design": design,
        "validation": validation,
        "admission_status": "PASS_WEEKLY_COVERAGE_AND_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
        if design["support_pass"] and validation["support_pass"]
        else "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS",
    }


def output_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"OUTPUT_PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def fetch() -> bytes:
    try:
        completed = subprocess.run(
            [
                "curl.exe",
                "--proto",
                "=https",
                "--max-redirs",
                "0",
                "--max-time",
                str(REQUEST_TIMEOUT_SECONDS),
                "--fail",
                "--silent",
                "--show-error",
                "--user-agent",
                "AgoraResearchFredRrpontsydSourceProbe/1.0",
                "--url",
                SOURCE_URL,
            ],
            check=False,
            capture_output=True,
            timeout=REQUEST_TIMEOUT_SECONDS + 5,
        )
    except subprocess.TimeoutExpired as error:
        raise SourceReject("SOURCE_REJECT:TIMEOUT") from error
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:160]
        raise SourceReject(f"SOURCE_REJECT:CURL:{completed.returncode}:{detail}")
    return completed.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    bundle_path = output_path(args.bundle)
    raw_path = output_path(args.raw)
    normalized_path = output_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    raw = fetch()
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    rows = parse_rows(raw)
    validate_rows(rows)
    normalized = (
        "observation_date,rrpontsyd_billions_usd\n"
        + "".join(f"{day.isoformat()},{raw_value}\n" for day, _, raw_value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = (
        "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if feasibility["admission_status"].startswith("PASS_")
        else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    )
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_RRPONTSYD_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agency": "Federal Reserve Bank of New York",
        "series": "RRPONTSYD",
        "release": "Temporary Open Market Operations",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "transport": "curl.exe",
            "redirect": "DENY",
            "retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "rows": len(rows),
            "missing_value_rows": sum(value is None for _, value, _ in rows),
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "columns": ["observation_date", "rrpontsyd_billions_usd"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "For each complete Monday-through-Sunday UTC week, use the last non-missing observation dated in that week. The complete week becomes usable only on Wednesday 00:00 UTC and remains valid for 168 hours.",
        "revision_boundary": "The exact FRED CSV is a sealed present-vintage history. Original daily release values, revisions and vintages remain MISSING_PROOF; any historical pass still requires untouched forward evidence.",
        "interpretation_boundary": "Lower ON RRP usage does not prove risk-asset inflow. Administered rates, Treasury bill supply, money-fund constraints and counterparty eligibility can dominate the balance.",
        "license_boundary": "FRED identifies the source and requires citation. Exact sealed bytes are retained only for internal reproducible research.",
        "scope_note": "No BTC outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    created: list[Path] = []
    try:
        write_create_once(raw_path, raw)
        created.append(raw_path)
        write_create_once(normalized_path, normalized)
        created.append(normalized_path)
        write_create_once(bundle_path, bundle_raw)
        created.append(bundle_path)
    except Exception:
        for target in created:
            target.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": status,
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "raw_sha256": sha256(raw),
                "normalized_sha256": sha256(normalized),
                "feasibility": feasibility,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
