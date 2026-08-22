#!/usr/bin/env python3
"""Seal one present-vintage FRED NFCI source without opening BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import subprocess
from typing import Any
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_RANGES = tuple(
    (
        year,
        f"https://fred.stlouisfed.org/graph/fredgraph.csv?id=NFCI&cosd={year}-01-01&coed={year}-12-31",
    )
    for year in range(2018, 2025)
)
EXPECTED_ROWS = 365
EXPECTED_FIRST = date(2018, 1, 5)
EXPECTED_LAST = date(2024, 12, 27)
PUBLICATION_LAG_DAYS = 7
MAX_RESPONSE_BYTES = 16 * 1024
REQUEST_TIMEOUT_SECONDS = 60
FETCH_AMENDMENT_PATH = REPO_ROOT / "research_pipeline" / "examples" / "dra-nfci-loose-financial-conditions-source-fetch-amendment.v1.json"


class SourceReject(RuntimeError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


def parse_rows(raw: bytes, expected_year: int) -> list[tuple[date, Decimal]]:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    rows = list(csv.reader(io.StringIO(text, newline="")))
    if not rows or rows[0] != ["observation_date", "NFCI"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, Decimal]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2 or not row[1] or row[1] == ".":
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = Decimal(row[1])
        except (ValueError, InvalidOperation) as error:
            raise SourceReject(f"SOURCE_REJECT:VALUE:{index}") from error
        if day.year != expected_year or not value.is_finite() or abs(value) > Decimal("50"):
            raise SourceReject(f"SOURCE_REJECT:IDENTITY:{index}")
        parsed.append((day, value))
    if not parsed:
        raise SourceReject(f"SOURCE_REJECT:NO_ROWS:{expected_year}")
    return parsed


def validate_combined_rows(rows: list[tuple[date, Decimal]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if len({day for day, _ in rows}) != len(rows):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DATE")
    if rows[0][0] != EXPECTED_FIRST or rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, ((prior_day, _), (current_day, _)) in enumerate(zip(rows, rows[1:], strict=False), start=1):
        if current_day - prior_day != timedelta(days=7) or current_day.weekday() != 4:
            raise SourceReject(f"SOURCE_REJECT:WEEKLY_CONTINUITY:{index}")


def deterministic_zip(parts: list[tuple[int, bytes]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for year, raw in parts:
            info = zipfile.ZipInfo(f"fred-nfci-{year}.csv", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, raw)
    return output.getvalue()


def feature_feasibility(rows: list[tuple[date, Decimal]]) -> dict[str, Any]:
    states = [value < 0 for _, value in rows]
    transitions = sum(current != prior for prior, current in zip(states, states[1:], strict=False))
    effective = datetime.combine(rows[0][0] + timedelta(days=PUBLICATION_LAG_DAYS), time.min, tzinfo=timezone.utc)
    return {
        "evaluations": len(states),
        "loose_negative_weeks": sum(states),
        "tight_or_zero_weeks": sum(not state for state in states),
        "zero_weeks": sum(value == 0 for _, value in rows),
        "transitions": transitions,
        "first_observation_day": rows[0][0].isoformat(),
        "first_effective_time": effective.isoformat().replace("+00:00", "Z"),
    }


def state_path(value: str, *, existing_directory: bool = False) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"OUTPUT_PATH_REJECT:{resolved}") from error
    if existing_directory:
        if resolved.exists() and not resolved.is_dir():
            raise SourceReject(f"OUTPUT_PATH_REJECT:NOT_DIRECTORY:{resolved}")
        resolved.mkdir(parents=True, exist_ok=True)
    elif resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def fetch(url: str) -> tuple[bytes, dict[str, Any]]:
    try:
        completed = subprocess.run(
            [
                "curl.exe", "--proto", "=https", "--max-redirs", "0",
                "--max-time", str(REQUEST_TIMEOUT_SECONDS), "--fail", "--silent",
                "--show-error", "--user-agent", "AgoraResearchFredNfciSourceProbe/1.1",
                "--url", url,
            ],
            check=False,
            capture_output=True,
            timeout=REQUEST_TIMEOUT_SECONDS + 5,
        )
    except subprocess.TimeoutExpired as error:
        raise SourceReject("SOURCE_REJECT:RECOVERY_TIMEOUT") from error
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:160]
        raise SourceReject(f"SOURCE_REJECT:CURL:{completed.returncode}:{detail}")
    raw = completed.stdout
    metadata = {"content_type": "text/csv", "etag": None, "last_modified": None, "cache_reused": False}
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw, metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    parser.add_argument("--cache-dir", required=True)
    args = parser.parse_args()
    bundle_path = state_path(args.bundle)
    raw_path = state_path(args.raw)
    normalized_path = state_path(args.normalized)
    cache_dir = state_path(args.cache_dir, existing_directory=True)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    source_parts: list[tuple[int, bytes]] = []
    response_parts: list[dict[str, Any]] = []
    rows: list[tuple[date, Decimal]] = []
    for year, url in SOURCE_RANGES:
        cache_path = cache_dir / f"fred-nfci-{year}.csv"
        if cache_path.exists():
            body = cache_path.read_bytes()
            headers = {"content_type": None, "etag": None, "last_modified": None, "cache_reused": True}
        else:
            body, headers = fetch(url)
            write_create_once(cache_path, body)
        annual_rows = parse_rows(body, year)
        rows.extend(annual_rows)
        source_parts.append((year, body))
        response_parts.append({"year": year, "url": url, "bytes": len(body), "sha256": sha256(body), "rows": len(annual_rows), **headers})
    rows.sort(key=lambda row: row[0])
    validate_combined_rows(rows)
    raw = deterministic_zip(source_parts)
    normalized = ("observation_date,nfci_index\n" + "".join(f"{day.isoformat()},{value}\n" for day, value in rows)).encode("utf-8")
    feasibility = feature_feasibility(rows)
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_NFCI_WEEKLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_DRA_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agency": "Federal Reserve Bank of Chicago",
        "request_contract": {
            "method": "GET",
            "urls": [url for _, url in SOURCE_RANGES],
            "credentials": "DENY",
            "transport": "CURL_EXE_EXACT_URL_TRANSPORT_RECOVERY",
            "redirect": "DENY",
            "retry": "ONE_RECORDED_ZERO_BYTE_TIMEOUT_RECOVERY_ONLY_THEN_DENY",
            "maximum_response_bytes_per_request": MAX_RESPONSE_BYTES,
            "source_fetch_amendment": {
                "path": FETCH_AMENDMENT_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(FETCH_AMENDMENT_PATH.read_bytes()),
            },
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
            "columns": ["observation_date", "nfci_index"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Week-ending Friday D is usable only at D plus seven calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "revision_boundary": "The exact annual FRED CSV responses are sealed as one deterministic present-vintage archive. Original weekly release values and revision vintages remain MISSING_PROOF.",
        "license_boundary": "FRED identifies the Chicago Fed NFCI as copyrighted with citation required. The exact bytes are retained only in private internal research state and are not claimed as redistributable.",
        "scope_note": "No BTC or DRA outcome, paid API, credential, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
        "raw_sha256": sha256(raw),
        "normalized_sha256": sha256(normalized),
        "normalized_bytes": len(normalized),
        "feasibility": feasibility,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
