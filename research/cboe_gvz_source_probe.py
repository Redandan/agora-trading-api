#!/usr/bin/env python3
"""Fetch, validate and seal present-vintage Cboe GVZ history without BTC outcome access."""

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
SOURCE_URL = "https://cdn.cboe.com/api/global/us_indices/daily_prices/GVZ_History.csv"
EXPECTED_HEADER = ["DATE", "OPEN", "HIGH", "LOW", "CLOSE"]
WINDOW_START = date(2018, 1, 1)
WINDOW_END = date(2024, 12, 31)
EXPECTED_FIRST_DAY = date(2018, 1, 2)
EXPECTED_LAST_DAY = date(2024, 12, 31)
MIN_ROWS_PER_YEAR = 240
MAX_ROWS_PER_YEAR = 270
PRIOR_WEEKS = 52
THRESHOLDS = (Decimal("0.80"), Decimal("1.00"), Decimal("1.20"))
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
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
        headers={"Accept": "text/csv", "User-Agent": "AgoraResearchCboeGvzSourceProbe/1.0"},
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


def _parse_day(raw: str, index: int) -> date:
    for pattern in ("%m/%d/%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, pattern).date()
        except ValueError:
            continue
    raise SourceReject(f"SOURCE_REJECT:DATE:{index}")


def _parse_decimal(raw: str, index: int, column: str) -> Decimal:
    if DECIMAL_VALUE.fullmatch(raw) is None:
        raise SourceReject(f"SOURCE_REJECT:DECIMAL:{index}:{column}")
    try:
        value = Decimal(raw)
    except InvalidOperation as error:
        raise SourceReject(f"SOURCE_REJECT:DECIMAL:{index}:{column}") from error
    if not value.is_finite() or value <= 0 or value > Decimal("1000"):
        raise SourceReject(f"SOURCE_REJECT:RANGE:{index}:{column}")
    return value


def parse_daily(raw: bytes) -> list[tuple[date, Decimal, Decimal, Decimal, Decimal]]:
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    if not rows or rows[0] != EXPECTED_HEADER:
        raise SourceReject("SOURCE_REJECT:HEADER")

    parsed: list[tuple[date, Decimal, Decimal, Decimal, Decimal]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != len(EXPECTED_HEADER):
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        day = _parse_day(row[0], index)
        values = tuple(
            _parse_decimal(raw_value, index, column)
            for raw_value, column in zip(row[1:], EXPECTED_HEADER[1:], strict=True)
        )
        open_value, high_value, low_value, close_value = values
        if high_value < max(open_value, low_value, close_value):
            raise SourceReject(f"SOURCE_REJECT:HIGH:{index}")
        if low_value > min(open_value, high_value, close_value):
            raise SourceReject(f"SOURCE_REJECT:LOW:{index}")
        if WINDOW_START <= day <= WINDOW_END:
            parsed.append((day, open_value, high_value, low_value, close_value))

    if not parsed:
        raise SourceReject("SOURCE_REJECT:NO_WINDOW_ROWS")
    if parsed[0][0] != EXPECTED_FIRST_DAY or parsed[-1][0] != EXPECTED_LAST_DAY:
        raise SourceReject("SOURCE_REJECT:DAILY_BOUNDARY")
    if any(current[0] <= prior[0] for prior, current in zip(parsed, parsed[1:], strict=False)):
        raise SourceReject("SOURCE_REJECT:DATE_ORDER_OR_DUPLICATE")
    if any(row[0].weekday() >= 5 for row in parsed):
        raise SourceReject("SOURCE_REJECT:WEEKEND_ROW")
    if any((current[0] - prior[0]).days > 7 for prior, current in zip(parsed, parsed[1:], strict=False)):
        raise SourceReject("SOURCE_REJECT:BUSINESS_DAY_GAP")

    annual_counts = {year: sum(row[0].year == year for row in parsed) for year in range(2018, 2025)}
    if any(count < MIN_ROWS_PER_YEAR or count > MAX_ROWS_PER_YEAR for count in annual_counts.values()):
        raise SourceReject(f"SOURCE_REJECT:ANNUAL_COVERAGE:{annual_counts}")
    return parsed


def aggregate_weeks(
    rows: list[tuple[date, Decimal, Decimal, Decimal, Decimal]],
) -> list[tuple[date, Decimal]]:
    weeks: dict[date, list[tuple[date, Decimal]]] = {}
    for day, _, _, _, close_value in rows:
        week_start = day - timedelta(days=day.weekday())
        weeks.setdefault(week_start, []).append((day, close_value))

    ordered_starts = sorted(weeks)
    expected_first_week = WINDOW_START - timedelta(days=WINDOW_START.weekday())
    expected_last_week = WINDOW_END - timedelta(days=WINDOW_END.weekday())
    if ordered_starts[0] != expected_first_week or ordered_starts[-1] != expected_last_week:
        raise SourceReject("SOURCE_REJECT:WEEKLY_BOUNDARY")
    if any(current - prior != timedelta(days=7) for prior, current in zip(ordered_starts, ordered_starts[1:], strict=False)):
        raise SourceReject("SOURCE_REJECT:MISSING_WEEK")

    weekly: list[tuple[date, Decimal]] = []
    for week_start in ordered_starts:
        observations = sorted(weeks[week_start])
        weekly.append(observations[-1])
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
        states = [ratio <= threshold for ratio in ratios]
        threshold_diagnostics[format(threshold, ".2f")] = {
            "at_or_below": sum(states),
            "above": sum(not state for state in states),
            "state_transitions": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        }
    first_week_last_day = weekly[PRIOR_WEEKS][0]
    effective = datetime.combine(first_week_last_day + timedelta(days=1), time.min, tzinfo=timezone.utc)
    return {
        "evaluations": len(ratios),
        "weekly_rows": len(weekly),
        "first_evaluable_week_last_day": first_week_last_day.isoformat(),
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
        ",".join(EXPECTED_HEADER)
        + "\n"
        + "".join(
            f"{day.isoformat()},{format(open_value, 'f')},{format(high_value, 'f')},"
            f"{format(low_value, 'f')},{format(close_value, 'f')}\n"
            for day, open_value, high_value, low_value, close_value in daily
        )
    ).encode("utf-8")
    annual_counts = {str(year): sum(row[0].year == year for row in daily) for year in range(2018, 2025)}
    bundle = {
        "schema_version": "1",
        "document_type": "CBOE_GVZ_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Cboe Global Markets",
        "source_series": "Cboe Gold ETF Volatility Index (GVZ)",
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
            "annual_rows": annual_counts,
            "columns": EXPECTED_HEADER,
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "A weekly last published GVZ close on day D is usable only at D plus one calendar day 00:00 UTC and remains valid for at most 168 hours.",
        "aggregation_boundary": "Use only the last published GVZ close in each Monday-through-Friday calendar week; no interpolation or missing-week substitution.",
        "feature_boundary": "Current weekly last close divided by the median of the prior 52 non-overlapping weekly last closes; current week is excluded from its denominator; relation is AT_OR_BELOW.",
        "revision_boundary": "This is a sealed present-vintage Cboe snapshot. Original publication vintages and subsequent revisions remain MISSING_PROOF, so any historical pass still requires untouched prospective evidence.",
        "license_and_accuracy_boundary": "Cboe publishes historical index data for visitor convenience without guaranteed accuracy. The sealed copy is bounded research provenance, not redistribution, an index product, or execution authority.",
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
