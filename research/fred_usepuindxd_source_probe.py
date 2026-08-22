#!/usr/bin/env python3
"""Fetch, validate and seal present-vintage FRED USEPUINDXD history without BTC outcome access."""

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
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = (
    "https://fred.stlouisfed.org/graph/fredgraph.csv?"
    "id=USEPUINDXD&cosd=2018-01-01&coed=2024-12-31"
)
EXPECTED_DAILY_ROWS = 2557
EXPECTED_FIRST_DAY = date(2018, 1, 1)
EXPECTED_LAST_DAY = date(2024, 12, 31)
EXPECTED_WEEKLY_ROWS = 365
EXPECTED_FIRST_WEEK = date(2018, 1, 7)
EXPECTED_LAST_WEEK = date(2024, 12, 29)
PRIOR_WEEKS = 52
PUBLICATION_LAG_DAYS = 7
THRESHOLDS = (Decimal("0.80"), Decimal("1.00"), Decimal("1.20"))
MAX_RESPONSE_BYTES = 256 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DECIMAL_VALUE = re.compile(r"^[0-9]+(?:[.][0-9]+)?$")


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


def state_path(value: str, *, must_not_exist: bool) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"PATH_REJECT:{resolved}") from error
    if must_not_exist and resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def fetch_source() -> bytes:
    request = Request(
        SOURCE_URL,
        method="GET",
        headers={"Accept": "text/csv", "User-Agent": "AgoraResearchFredUsepuindxdSourceProbe/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}") from error
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw


def parse_daily(raw: bytes) -> list[tuple[date, Decimal]]:
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    if not rows or rows[0] != ["observation_date", "USEPUINDXD"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, Decimal]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
        except ValueError as error:
            raise SourceReject(f"SOURCE_REJECT:DATE:{index}") from error
        if DECIMAL_VALUE.fullmatch(row[1]) is None:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{index}")
        try:
            value = Decimal(row[1])
        except InvalidOperation as error:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{index}") from error
        if not value.is_finite() or value <= 0 or value > Decimal("10000"):
            raise SourceReject(f"SOURCE_REJECT:RANGE:{index}")
        parsed.append((day, value))
    if len(parsed) != EXPECTED_DAILY_ROWS:
        raise SourceReject(f"SOURCE_REJECT:DAILY_ROWS:{len(parsed)}")
    if parsed[0][0] != EXPECTED_FIRST_DAY or parsed[-1][0] != EXPECTED_LAST_DAY:
        raise SourceReject("SOURCE_REJECT:DAILY_BOUNDARY")
    if any(current[0] - prior[0] != timedelta(days=1) for prior, current in zip(parsed, parsed[1:], strict=False)):
        raise SourceReject("SOURCE_REJECT:DAILY_CONTINUITY")
    return parsed


def aggregate_weeks(rows: list[tuple[date, Decimal]]) -> list[tuple[date, Decimal]]:
    by_day = dict(rows)
    if len(by_day) != len(rows):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DATE")
    weekly: list[tuple[date, Decimal]] = []
    week_start = EXPECTED_FIRST_DAY
    while week_start + timedelta(days=6) <= EXPECTED_LAST_WEEK:
        days = [week_start + timedelta(days=offset) for offset in range(7)]
        if any(day not in by_day for day in days):
            raise SourceReject(f"SOURCE_REJECT:INCOMPLETE_WEEK:{week_start}")
        weekly.append((days[-1], sum((by_day[day] for day in days), Decimal("0")) / Decimal("7")))
        week_start += timedelta(days=7)
    if len(weekly) != EXPECTED_WEEKLY_ROWS:
        raise SourceReject(f"SOURCE_REJECT:WEEKLY_ROWS:{len(weekly)}")
    if weekly[0][0] != EXPECTED_FIRST_WEEK or weekly[-1][0] != EXPECTED_LAST_WEEK:
        raise SourceReject("SOURCE_REJECT:WEEKLY_BOUNDARY")
    return weekly


def median(values: list[Decimal]) -> Decimal:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / Decimal("2")


def feature_feasibility(weekly: list[tuple[date, Decimal]]) -> dict[str, Any]:
    ratios: list[Decimal] = []
    for index in range(PRIOR_WEEKS, len(weekly)):
        prior_median = median([value for _, value in weekly[index - PRIOR_WEEKS:index]])
        if prior_median <= 0:
            raise SourceReject(f"SOURCE_REJECT:NONPOSITIVE_PRIOR_MEDIAN:{index}")
        ratios.append(weekly[index][1] / prior_median)
    if not ratios:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    threshold_diagnostics: dict[str, dict[str, int]] = {}
    for threshold in THRESHOLDS:
        states = [ratio >= threshold for ratio in ratios]
        threshold_diagnostics[format(threshold, ".2f")] = {
            "at_or_above": sum(states),
            "below": sum(not state for state in states),
            "state_transitions": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        }
    first_week = weekly[PRIOR_WEEKS][0]
    effective = datetime.combine(first_week + timedelta(days=PUBLICATION_LAG_DAYS), time.min, tzinfo=timezone.utc)
    return {
        "evaluations": len(ratios),
        "first_evaluable_week": first_week.isoformat(),
        "first_effective_time": effective.isoformat().replace("+00:00", "Z"),
        "threshold_diagnostics": threshold_diagnostics,
    }


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw-response", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    bundle_path = state_path(args.bundle, must_not_exist=True)
    raw_path = state_path(args.raw_response, must_not_exist=True)
    normalized_path = state_path(args.normalized, must_not_exist=True)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("PATH_REJECT:DUPLICATE")

    raw = fetch_source()
    daily = parse_daily(raw)
    weekly = aggregate_weeks(daily)
    feasibility = feature_feasibility(weekly)
    normalized = (
        "observation_date,usepuindxd\n"
        + "".join(f"{day.isoformat()},{format(value, 'f')}\n" for day, value in daily)
    ).encode("utf-8")
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_USEPUINDXD_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_series": "Daily News Index: Economic Policy Uncertainty",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "format": "EXACT_RESPONSE_BODY_CSV",
        },
        "normalized_daily_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(daily),
            "first_day": daily[0][0].isoformat(),
            "last_day": daily[-1][0].isoformat(),
            "columns": ["observation_date", "usepuindxd"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "A complete Monday-Sunday mean ending D is usable only at D plus seven calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "aggregation_boundary": "Arithmetic mean of exactly seven published daily observations; no interpolation or missing-day substitution.",
        "feature_boundary": "Current complete-week mean divided by the median of the prior 52 non-overlapping complete-week means; current week is excluded from its denominator.",
        "revision_boundary": "This is a sealed present-vintage FRED snapshot. Original news-index release vintages and subsequent revisions remain MISSING_PROOF, so any historical pass still requires untouched prospective evidence.",
        "scope_note": "No BTC or DRA outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(raw_path, raw)
    try:
        write_create_once(normalized_path, normalized)
        write_create_once(bundle_path, bundle_raw)
    except Exception:
        raw_path.unlink(missing_ok=True)
        normalized_path.unlink(missing_ok=True)
        raise
    print(json.dumps({
        "status": bundle["status"],
        "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
        "bundle_sha256": sha256(bundle_raw),
        "raw_response_sha256": sha256(raw),
        "normalized_sha256": sha256(normalized),
        "daily_rows": len(daily),
        "weekly_rows": len(weekly),
        "feasibility": feasibility,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
