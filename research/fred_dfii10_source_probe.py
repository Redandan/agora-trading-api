#!/usr/bin/env python3
"""Fetch, validate and seal present-vintage FRED DFII10 history without BTC outcome access."""

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
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
YEARS = tuple(range(2018, 2025))
SOURCE_URLS = {
    year: f"https://fred.stlouisfed.org/graph/fredgraph.csv?id=DFII10&cosd={year}-01-01&coed={year}-12-31"
    for year in YEARS
}
EXPECTED_WEEKLY_ROWS = 365
EXPECTED_FIRST_WEEK = date(2018, 1, 5)
EXPECTED_LAST_WEEK = date(2024, 12, 27)
CHANGE_LAG_DAYS = 28
PUBLICATION_LAG_DAYS = 5
MAX_PART_BYTES = 64 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DECIMAL_VALUE = re.compile(r"^-?[0-9]+(?:[.][0-9]+)?$")


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


def fetch_part(year: int) -> bytes:
    request = Request(
        SOURCE_URLS[year],
        method="GET",
        headers={"Accept": "text/csv", "User-Agent": "AgoraResearchFredDfii10SourceProbe/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{year}:{response.status}")
            raw = response.read(MAX_PART_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(f"SOURCE_REJECT:TRANSPORT:{year}:{type(error).__name__}") from error
    if not raw or len(raw) > MAX_PART_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{year}:{len(raw)}")
    return raw


def parse_annual(raw: bytes, expected_year: int) -> tuple[list[tuple[date, Decimal]], list[date], int]:
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
    except UnicodeDecodeError as error:
        raise SourceReject(f"SOURCE_REJECT:UTF8:{expected_year}") from error
    if not rows or rows[0] != ["observation_date", "DFII10"]:
        raise SourceReject(f"SOURCE_REJECT:HEADER:{expected_year}")
    parsed: list[tuple[date, Decimal]] = []
    empty_dates: list[date] = []
    all_dates: list[date] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise SourceReject(f"SOURCE_REJECT:ROW:{expected_year}:{index}")
        try:
            day = date.fromisoformat(row[0])
        except ValueError as error:
            raise SourceReject(f"SOURCE_REJECT:DATE:{expected_year}:{index}") from error
        if day.year != expected_year or day.weekday() > 4:
            raise SourceReject(f"SOURCE_REJECT:IDENTITY:{expected_year}:{index}")
        all_dates.append(day)
        if row[1] == "":
            empty_dates.append(day)
            continue
        if DECIMAL_VALUE.fullmatch(row[1]) is None:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{expected_year}:{index}")
        try:
            value = Decimal(row[1])
        except InvalidOperation as error:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{expected_year}:{index}") from error
        if not value.is_finite() or value < Decimal("-10") or value > Decimal("25"):
            raise SourceReject(f"SOURCE_REJECT:RANGE:{expected_year}:{index}")
        parsed.append((day, value))
    if len(parsed) < 240 or len(parsed) > 270:
        raise SourceReject(f"SOURCE_REJECT:ANNUAL_ROWS:{expected_year}:{len(parsed)}")
    if len(empty_dates) < 5 or len(empty_dates) > 15:
        raise SourceReject(f"SOURCE_REJECT:EMPTY_HOLIDAY_ROWS:{expected_year}:{len(empty_dates)}")
    if len(set(all_dates)) != len(all_dates) or any(current <= prior for prior, current in zip(all_dates, all_dates[1:], strict=False)):
        raise SourceReject(f"SOURCE_REJECT:ORDER:{expected_year}")
    return parsed, empty_dates, len(rows) - 1


def aggregate_weeks(rows: list[tuple[date, Decimal]]) -> tuple[list[tuple[date, Decimal]], dict[str, int]]:
    by_day: dict[date, Decimal] = {}
    by_week: dict[date, list[Decimal]] = {}
    for day, value in rows:
        if day in by_day:
            raise SourceReject(f"SOURCE_REJECT:DUPLICATE_DATE:{day}")
        by_day[day] = value
        week_end = day + timedelta(days=4 - day.weekday())
        if EXPECTED_FIRST_WEEK <= week_end <= EXPECTED_LAST_WEEK:
            by_week.setdefault(week_end, []).append(value)
    weekly: list[tuple[date, Decimal]] = []
    observation_counts: dict[str, int] = {}
    for week_end in sorted(by_week):
        values = by_week[week_end]
        if len(values) < 3 or len(values) > 5:
            raise SourceReject(f"SOURCE_REJECT:WEEK_OBSERVATIONS:{week_end}:{len(values)}")
        observation_counts[str(len(values))] = observation_counts.get(str(len(values)), 0) + 1
        weekly.append((week_end, sum(values, Decimal("0")) / Decimal(len(values))))
    if len(weekly) != EXPECTED_WEEKLY_ROWS:
        raise SourceReject(f"SOURCE_REJECT:WEEKLY_ROWS:{len(weekly)}")
    if weekly[0][0] != EXPECTED_FIRST_WEEK or weekly[-1][0] != EXPECTED_LAST_WEEK:
        raise SourceReject("SOURCE_REJECT:WEEKLY_BOUNDARY")
    if any(current[0] - prior[0] != timedelta(days=7) for prior, current in zip(weekly, weekly[1:], strict=False)):
        raise SourceReject("SOURCE_REJECT:WEEKLY_CONTINUITY")
    return weekly, observation_counts


def feature_feasibility(weekly: list[tuple[date, Decimal]]) -> dict[str, Any]:
    by_week = dict(weekly)
    signs: list[int] = []
    first: date | None = None
    for week_end, value in weekly:
        prior = week_end - timedelta(days=CHANGE_LAG_DAYS)
        if prior not in by_week:
            continue
        if first is None:
            first = week_end
        score = by_week[prior] - value
        signs.append(1 if score > 0 else -1 if score < 0 else 0)
    if first is None:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    effective = datetime.combine(first + timedelta(days=PUBLICATION_LAG_DAYS), time.min, tzinfo=timezone.utc)
    return {
        "evaluations": len(signs),
        "real_yield_easing_weeks": sum(sign > 0 for sign in signs),
        "real_yield_noneasing_weeks": sum(sign <= 0 for sign in signs),
        "zero_change_weeks": sum(sign == 0 for sign in signs),
        "sign_transitions": sum(current != prior for prior, current in zip(signs, signs[1:], strict=False)),
        "first_evaluable_week": first.isoformat(),
        "first_effective_time": effective.isoformat().replace("+00:00", "Z"),
    }


def deterministic_zip(parts: list[tuple[int, bytes]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for year, raw in parts:
            info = zipfile.ZipInfo(f"fred-dfii10-{year}.csv", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, raw)
    return output.getvalue()


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw-archive", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    bundle_path = state_path(args.bundle, must_not_exist=True)
    archive_path = state_path(args.raw_archive, must_not_exist=True)
    normalized_path = state_path(args.normalized, must_not_exist=True)
    if len({bundle_path, archive_path, normalized_path}) != 3:
        raise SourceReject("PATH_REJECT:DUPLICATE")

    all_rows: list[tuple[date, Decimal]] = []
    parts: list[tuple[int, bytes]] = []
    response_parts: list[dict[str, Any]] = []
    for year in YEARS:
        raw = fetch_part(year)
        parsed, empty_dates, source_rows = parse_annual(raw, year)
        parts.append((year, raw))
        all_rows.extend(parsed)
        response_parts.append({
            "year": year,
            "url": SOURCE_URLS[year],
            "bytes": len(raw),
            "sha256": sha256(raw),
            "source_rows": source_rows,
            "published_rows": len(parsed),
            "empty_holiday_rows": len(empty_dates),
        })
    weekly, weekly_observation_counts = aggregate_weeks(all_rows)
    feasibility = feature_feasibility(weekly)
    raw_archive = deterministic_zip(parts)
    normalized = (
        "week_ending_friday,dfii10_mean_pct\n"
        + "".join(f"{week_end.isoformat()},{format(value, 'f')}\n" for week_end, value in weekly)
    ).encode("utf-8")
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_DFII10_WEEKLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agency": "Board of Governors of the Federal Reserve System (US)",
        "request_contract": {
            "method": "GET",
            "urls": [SOURCE_URLS[year] for year in YEARS],
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes_per_request": MAX_PART_BYTES,
        },
        "raw_response_archive": {
            "path": archive_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw_archive),
            "sha256": sha256(raw_archive),
            "format": "DETERMINISTIC_ZIP_STORED_EXACT_RESPONSE_BODIES",
            "parts": response_parts,
        },
        "normalized_weekly_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(weekly),
            "first_week": weekly[0][0].isoformat(),
            "last_week": weekly[-1][0].isoformat(),
            "columns": ["week_ending_friday", "dfii10_mean_pct"],
            "weekly_observation_counts": weekly_observation_counts,
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Calendar week ending Friday D is usable only at D plus five calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "aggregation_boundary": "Arithmetic mean of three to five actually published Monday-Friday observations; holidays are not interpolated.",
        "revision_boundary": "The exact annual FRED responses are a sealed present-vintage snapshot. Original H.15 release values and revision vintages remain MISSING_PROOF.",
        "license_boundary": "FRED tags DFII10 as Public Domain: Citation Requested.",
        "scope_note": "No BTC or DRA outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(archive_path, raw_archive)
    try:
        write_create_once(normalized_path, normalized)
        write_create_once(bundle_path, bundle_raw)
    except Exception:
        archive_path.unlink(missing_ok=True)
        normalized_path.unlink(missing_ok=True)
        raise
    print(json.dumps({
        "status": bundle["status"],
        "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
        "bundle_sha256": sha256(bundle_raw),
        "raw_archive_sha256": sha256(raw_archive),
        "normalized_sha256": sha256(normalized),
        "daily_rows": len(all_rows),
        "weekly_rows": len(weekly),
        "feasibility": feasibility,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
