#!/usr/bin/env python3
"""Seal one present-vintage FRED WTREGEN source without opening BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import time as wall_time
from typing import Any
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_RANGES = tuple(
    (
        year,
        f"https://fred.stlouisfed.org/graph/fredgraph.csv?id=WTREGEN&cosd={year}-01-01&coed={year}-12-31",
    )
    for year in range(2018, 2025)
)
EXPECTED_ROWS = 365
EXPECTED_FIRST = date(2018, 1, 3)
EXPECTED_LAST = date(2024, 12, 25)
FEATURE_LAG_DAYS = 28
AVAILABILITY_LAG_DAYS = 2
MAX_RESPONSE_BYTES = 16 * 1024
REQUEST_TIMEOUT_SECONDS = 30
INTEGER = re.compile(r"^[0-9]+$")


class SourceReject(RuntimeError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def parse_rows(raw: bytes, expected_year: int) -> list[tuple[date, int]]:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    rows = list(csv.reader(io.StringIO(text, newline="")))
    if not rows or rows[0] != ["observation_date", "WTREGEN"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, int]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2 or not INTEGER.fullmatch(row[1]):
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
        except ValueError as error:
            raise SourceReject(f"SOURCE_REJECT:DATE:{index}") from error
        value = int(row[1])
        if day.year != expected_year:
            raise SourceReject(f"SOURCE_REJECT:YEAR:{index}")
        if value <= 0 or value > 10_000_000:
            raise SourceReject(f"SOURCE_REJECT:VALUE:{index}")
        parsed.append((day, value))
    if not parsed:
        raise SourceReject(f"SOURCE_REJECT:NO_ROWS:{expected_year}")
    return parsed


def validate_combined_rows(rows: list[tuple[date, int]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if len({day for day, _ in rows}) != len(rows):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DATE")
    if rows[0][0] != EXPECTED_FIRST or rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, ((prior_day, _), (current_day, _)) in enumerate(
        zip(rows, rows[1:], strict=False), start=1
    ):
        if current_day - prior_day != timedelta(days=7) or current_day.weekday() != 2:
            raise SourceReject(f"SOURCE_REJECT:WEEKLY_CONTINUITY:{index}")


def deterministic_zip(parts: list[tuple[int, bytes]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for year, raw in parts:
            info = zipfile.ZipInfo(
                f"fred-wtregen-{year}.csv", date_time=(1980, 1, 1, 0, 0, 0)
            )
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, raw)
    return output.getvalue()


def feature_feasibility(rows: list[tuple[date, int]]) -> dict[str, Any]:
    by_day = dict(rows)
    states: list[bool] = []
    first_day: date | None = None
    for day, value in rows:
        predecessor = day - timedelta(days=FEATURE_LAG_DAYS)
        if predecessor not in by_day:
            continue
        if first_day is None:
            first_day = day
        states.append(value < by_day[predecessor])
    if first_day is None:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    transitions = sum(
        current != prior for prior, current in zip(states, states[1:], strict=False)
    )
    effective = datetime.combine(
        first_day + timedelta(days=AVAILABILITY_LAG_DAYS),
        time.min,
        tzinfo=timezone.utc,
    )
    return {
        "evaluations": len(states),
        "supportive_weeks": sum(states),
        "drain_or_neutral_weeks": sum(not state for state in states),
        "transitions": transitions,
        "first_evaluable_observation_day": first_day.isoformat(),
        "first_effective_time": effective.isoformat().replace("+00:00", "Z"),
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


def cache_directory(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"CACHE_PATH_REJECT:{resolved}") from error
    if resolved.exists() and not resolved.is_dir():
        raise SourceReject(f"CACHE_PATH_REJECT:NOT_DIRECTORY:{resolved}")
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def fetch(url: str, year: int) -> bytes:
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
                "AgoraResearchFredWtregenSourceProbe/1.0",
                "--url",
                url,
            ],
            check=False,
            capture_output=True,
            timeout=REQUEST_TIMEOUT_SECONDS + 5,
        )
    except subprocess.TimeoutExpired as error:
        raise SourceReject(f"SOURCE_REJECT:TIMEOUT:{year}") from error
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:160]
        raise SourceReject(
            f"SOURCE_REJECT:CURL:{year}:{completed.returncode}:{detail}"
        )
    return completed.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    parser.add_argument("--cache-dir", required=True)
    args = parser.parse_args()
    bundle_path = output_path(args.bundle)
    raw_path = output_path(args.raw)
    normalized_path = output_path(args.normalized)
    cache_dir = cache_directory(args.cache_dir)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    source_parts: list[tuple[int, bytes]] = []
    response_parts: list[dict[str, Any]] = []
    rows: list[tuple[date, int]] = []
    for year, url in SOURCE_RANGES:
        cache_path = cache_dir / f"fred-wtregen-{year}.csv"
        if cache_path.exists():
            body = cache_path.read_bytes()
            cache_reused = True
        else:
            body = fetch(url, year)
            cache_reused = False
        if not body or len(body) > MAX_RESPONSE_BYTES:
            raise SourceReject(f"SOURCE_REJECT:BYTES:{year}:{len(body)}")
        annual_rows = parse_rows(body, year)
        if not cache_reused:
            write_create_once(cache_path, body)
            wall_time.sleep(1)
        rows.extend(annual_rows)
        source_parts.append((year, body))
        response_parts.append(
            {
                "year": year,
                "url": url,
                "bytes": len(body),
                "sha256": sha256(body),
                "rows": len(annual_rows),
                "cache_reused": cache_reused,
            }
        )
    rows.sort(key=lambda row: row[0])
    validate_combined_rows(rows)
    raw = deterministic_zip(source_parts)
    normalized = (
        "observation_date,wtregen_millions_usd\n"
        + "".join(f"{day.isoformat()},{value}\n" for day, value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_WTREGEN_WEEKLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agency": "Board of Governors of the Federal Reserve System (US)",
        "series": "WTREGEN",
        "release": "H.4.1 Factors Affecting Reserve Balances",
        "request_contract": {
            "method": "GET",
            "urls": [url for _, url in SOURCE_RANGES],
            "credentials": "DENY",
            "transport": "curl.exe",
            "redirect": "DENY",
            "retry": "DENY",
            "maximum_response_bytes_per_request": MAX_RESPONSE_BYTES,
        },
        "raw_response_archive": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "rows": len(rows),
            "format": "DETERMINISTIC_ZIP_STORED_EXACT_RESPONSE_BODIES",
            "parts": response_parts,
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "columns": ["observation_date", "wtregen_millions_usd"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Observation Wednesday D is usable only at D plus two calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "revision_boundary": "The seven exact annual FRED CSV responses are sealed as one deterministic present-vintage archive. Original weekly release values and revision vintages remain MISSING_PROOF.",
        "license_boundary": "FRED tags WTREGEN as Public Domain: Citation Requested. Exact sealed bytes are retained for internal reproducible research.",
        "scope_note": "No BTC outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
    print(
        json.dumps(
            {
                "status": bundle["status"],
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
