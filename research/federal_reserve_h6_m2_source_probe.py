#!/usr/bin/env python3
"""Seal an official H.6 monthly M2 source without opening BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import re
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
STATE_ROOT = (REPO_ROOT / ".research-state").resolve()
SOURCE_URL = (
    "https://www.federalreserve.gov/datadownload/Output.aspx?rel=H6"
    "&series=798e2796917702a5f8423426ba7e6b42&lastobs=&from=&to="
    "&filetype=csv&label=include&layout=seriescolumn&type=package"
)
TARGET_COLUMN = "M2.M"
TARGET_UNIQUE_IDENTIFIER = "H6/H6_M2/M2.M"
TARGET_DESCRIPTION = "M2; Seasonally adjusted"
FIRST_MONTH = "2017-01"
LAST_MONTH = "2024-12"
EXPECTED_ROWS = 96
MAX_RAW_BYTES = 1024 * 1024
MONTH = re.compile(r"^[0-9]{4}-(0[1-9]|1[0-2])$")
VALUE = re.compile(r"^[0-9]+(?:\.[0-9]+)?$")


class SourceReject(RuntimeError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def resolve_state_path(value: str, *, must_exist: bool) -> Path:
    path = Path(value).resolve()
    try:
        path.relative_to(STATE_ROOT)
    except ValueError as error:
        raise SourceReject(f"PATH_REJECT:OUTSIDE_RESEARCH_STATE:{path}") from error
    if must_exist:
        if not path.is_file():
            raise SourceReject(f"SOURCE_REJECT:MISSING_FILE:{path}")
    elif path.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{path}")
    return path


def next_month(value: str) -> str:
    year, month = (int(part) for part in value.split("-"))
    if month == 12:
        return f"{year + 1:04d}-01"
    return f"{year:04d}-{month + 1:02d}"


def add_months(value: str, count: int) -> str:
    result = value
    for _ in range(count):
        result = next_month(result)
    return result


def parse_source(raw: bytes) -> tuple[list[tuple[str, str, Decimal]], dict[str, Any]]:
    if not raw or len(raw) > MAX_RAW_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    rows = list(csv.reader(io.StringIO(text, newline="")))
    if len(rows) < 7:
        raise SourceReject("SOURCE_REJECT:TOO_FEW_ROWS")
    expected_labels = [
        "Series Description",
        "Unit:",
        "Multiplier:",
        "Currency:",
        "Unique Identifier:",
        "Time Period",
    ]
    if [row[0] if row else None for row in rows[:6]] != expected_labels:
        raise SourceReject("SOURCE_REJECT:METADATA_ROWS")
    width = len(rows[5])
    if any(len(row) != width for row in rows):
        raise SourceReject("SOURCE_REJECT:ROW_WIDTH")
    matches = [index for index, value in enumerate(rows[5]) if value == TARGET_COLUMN]
    if matches != [12]:
        raise SourceReject(f"SOURCE_REJECT:TARGET_COLUMN:{matches}")
    index = matches[0]
    identity = {
        "series_description": rows[0][index],
        "unit": rows[1][index],
        "multiplier": rows[2][index],
        "currency": rows[3][index],
        "unique_identifier": rows[4][index],
        "column": rows[5][index],
    }
    if identity != {
        "series_description": TARGET_DESCRIPTION,
        "unit": "Currency",
        "multiplier": "1e+09",
        "currency": "USD",
        "unique_identifier": TARGET_UNIQUE_IDENTIFIER,
        "column": TARGET_COLUMN,
    }:
        raise SourceReject(f"SOURCE_REJECT:IDENTITY:{identity}")

    selected: list[tuple[str, str, Decimal]] = []
    seen: set[str] = set()
    for row_number, row in enumerate(rows[6:], start=7):
        observation_month = row[0]
        if not MONTH.fullmatch(observation_month):
            raise SourceReject(f"SOURCE_REJECT:MONTH:{row_number}")
        if observation_month in seen:
            raise SourceReject(f"SOURCE_REJECT:DUPLICATE_MONTH:{observation_month}")
        seen.add(observation_month)
        if observation_month < FIRST_MONTH or observation_month > LAST_MONTH:
            continue
        value_text = row[index]
        if not VALUE.fullmatch(value_text):
            raise SourceReject(f"SOURCE_REJECT:VALUE:{observation_month}")
        try:
            value = Decimal(value_text)
        except InvalidOperation as error:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{observation_month}") from error
        if value <= 0:
            raise SourceReject(f"SOURCE_REJECT:NONPOSITIVE:{observation_month}")
        selected.append((observation_month, value_text, value))

    if len(selected) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(selected)}")
    if selected[0][0] != FIRST_MONTH or selected[-1][0] != LAST_MONTH:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for prior, current in zip(selected, selected[1:], strict=False):
        if next_month(prior[0]) != current[0]:
            raise SourceReject(f"SOURCE_REJECT:MONTHLY_CONTINUITY:{prior[0]}:{current[0]}")
    return selected, identity


def feature_feasibility(rows: list[tuple[str, str, Decimal]]) -> dict[str, Any]:
    states: list[bool] = []
    for index in range(13, len(rows)):
        current = rows[index][2]
        year_ago = rows[index - 12][2]
        previous = rows[index - 1][2]
        previous_year_ago = rows[index - 13][2]
        acceleration = (current / year_ago - Decimal(1)) - (
            previous / previous_year_ago - Decimal(1)
        )
        states.append(acceleration > 0)
    if not states:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    first_observation = rows[13][0]
    last_observation = rows[-1][0]
    return {
        "evaluations": len(states),
        "positive_months": sum(states),
        "nonpositive_months": sum(not state for state in states),
        "transitions": sum(
            current != prior for prior, current in zip(states, states[1:], strict=False)
        ),
        "first_evaluable_observation_month": first_observation,
        "first_effective_time": f"{add_months(first_observation, 2)}-01T00:00:00Z",
        "last_evaluable_observation_month": last_observation,
        "last_effective_time": f"{add_months(last_observation, 2)}-01T00:00:00Z",
    }


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    parser.add_argument("--bundle", required=True)
    args = parser.parse_args()

    raw_path = resolve_state_path(args.raw, must_exist=True)
    normalized_path = resolve_state_path(args.normalized, must_exist=False)
    bundle_path = resolve_state_path(args.bundle, must_exist=False)
    if len({raw_path, normalized_path, bundle_path}) != 3:
        raise SourceReject("PATH_REJECT:DUPLICATE")

    raw = raw_path.read_bytes()
    rows, identity = parse_source(raw)
    normalized = (
        "observation_month,m2_billions_usd\n"
        + "".join(f"{month},{value_text}\n" for month, value_text, _ in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    bundle = {
        "schema_version": "1",
        "document_type": "FEDERAL_RESERVE_H6_M2_MONTHLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Board of Governors of the Federal Reserve System",
        "release": "H.6 Money Stock Measures",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "redirect": "FOLLOW_HTTPS_ONLY",
            "retry_after_success": "DENY",
            "response_format": "CSV",
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "contains_observations_outside_normalized_window": True,
        },
        "series_identity": identity,
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_month": rows[0][0],
            "last_month": rows[-1][0],
            "columns": ["observation_month", "m2_billions_usd"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Observation month t is usable only from the first calendar day of t plus two months at 00:00 UTC.",
        "revision_boundary": "This is one exact July 2026 present-vintage H.6 response. Original monthly release vintages, annual seasonal-factor revisions and the effect of the July 2026 methodology change remain MISSING_PROOF.",
        "license_boundary": "Federal Reserve Board statistical-release data is retained with source attribution for internal reproducible research; no resale or redistribution claim is made.",
        "scope_note": "No BTC outcome, PnL, drawdown, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(normalized_path, normalized)
    try:
        write_create_once(bundle_path, bundle_raw)
    except Exception:
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
