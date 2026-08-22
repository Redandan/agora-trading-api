#!/usr/bin/env python3
"""Acquire one exact dated daily GPR vintage without BTC outcome access."""

from __future__ import annotations

import argparse
from datetime import date, timedelta
from decimal import Decimal
import hashlib
from importlib.metadata import PackageNotFoundError, version
import io
import json
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

import pandas as pd

from research import binance_spot_usdt_daily_corpus_v1 as common


REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_GPR_DAILY_VINTAGE_SOURCE_SPEC_V1"
EXPECTED_FAMILY_ID = "btc-gpr-high-risk-safe-haven-long-cash"
SOURCE_URL = (
    "https://www.matteoiacoviello.com/gpr_files/"
    "data_gpr_daily_recent_20250113.xls"
)
FIRST_DAY = date(2018, 1, 1)
LAST_DAY = date(2024, 12, 31)
REQUIRED_COLUMNS = {
    "date",
    "GPRD",
    "GPRD_ACT",
    "GPRD_THREAT",
    "GPRD_MA30",
    "GPRD_MA7",
    "N10D",
    "event",
}
MAX_SOURCE_BYTES = 8 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
EXPECTED_XLRD_VERSION = "2.0.1"
EXPECTED_ACQUISITION_POLICY = {
    "source_url": SOURCE_URL,
    "source_format": "LEGACY_XLS_FIRST_SHEET",
    "parser_dependency": f"xlrd=={EXPECTED_XLRD_VERSION}",
    "required_columns": sorted(REQUIRED_COLUMNS),
    "selected_field": "GPRD",
    "first_day": FIRST_DAY.isoformat(),
    "last_day": LAST_DAY.isoformat(),
    "complete_calendar_days": "REQUIRE_EVERY_DAY",
    "credentials": "DENY",
    "redirect": "DENY",
    "automatic_retry": "DENY",
    "latest_vintage_substitution": "DENY",
    "gpr_value_access": "ALLOW_EXACT_SELECTED_FIELD_ONLY_AFTER_SPEC_FREEZE",
    "btc_outcome_access": "DENY",
}


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


def sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch_source() -> tuple[bytes, str]:
    request = Request(
        SOURCE_URL,
        method="GET",
        headers={"User-Agent": "AgoraResearchGprDailyVintage/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            content_type = response.headers.get("Content-Type", "")
            raw = response.read(MAX_SOURCE_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_SOURCE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw, content_type


def expected_days() -> list[str]:
    values: list[str] = []
    current = FIRST_DAY
    while current <= LAST_DAY:
        values.append(current.isoformat())
        current += timedelta(days=1)
    return values


def validate_frame(frame: pd.DataFrame) -> tuple[list[tuple[str, Decimal]], dict[str, Any]]:
    columns = [str(value) for value in frame.columns]
    if len(columns) != len(set(columns)) or set(columns) != REQUIRED_COLUMNS:
        raise SourceReject(f"DATA_REJECT:COLUMNS:{sorted(columns)}")
    try:
        dates = pd.to_datetime(frame["date"], errors="raise")
        values = pd.to_numeric(frame["GPRD"], errors="raise")
    except Exception as error:
        raise SourceReject("DATA_REJECT:DATE_OR_GPRD_PARSE") from error
    all_rows: list[tuple[str, Decimal]] = []
    seen: set[str] = set()
    for index, (raw_day, raw_value) in enumerate(zip(dates, values, strict=True)):
        if pd.isna(raw_day) or pd.isna(raw_value):
            raise SourceReject(f"DATA_REJECT:MISSING:{index}")
        day = raw_day.date().isoformat()
        if day in seen:
            raise SourceReject(f"DATA_REJECT:DUPLICATE_DAY:{day}")
        seen.add(day)
        try:
            value = Decimal(str(raw_value))
        except Exception as error:
            raise SourceReject(f"DATA_REJECT:GPRD_DECIMAL:{index}") from error
        if not value.is_finite() or value < 0:
            raise SourceReject(f"DATA_REJECT:GPRD_VALUE:{index}")
        all_rows.append((day, value))
    if not all_rows or all_rows != sorted(all_rows, key=lambda value: value[0]):
        raise SourceReject("DATA_REJECT:ROW_ORDER")
    selected = [row for row in all_rows if FIRST_DAY.isoformat() <= row[0] <= LAST_DAY.isoformat()]
    actual_days = [row[0] for row in selected]
    required = expected_days()
    if actual_days != required:
        missing = sorted(set(required) - set(actual_days))
        extra = sorted(set(actual_days) - set(required))
        raise SourceReject(
            f"DATA_REJECT:COVERAGE:missing={missing[:5]}:extra={extra[:5]}"
        )
    return selected, {
        "workbook_row_count": len(all_rows),
        "workbook_first_day": all_rows[0][0],
        "workbook_last_day": all_rows[-1][0],
        "selected_row_count": len(selected),
        "selected_first_day": selected[0][0],
        "selected_last_day": selected[-1][0],
        "columns": columns,
    }


def parse_workbook(raw: bytes) -> tuple[list[tuple[str, Decimal]], dict[str, Any]]:
    try:
        actual_xlrd_version = version("xlrd")
    except PackageNotFoundError as error:
        raise SourceReject("DATA_REJECT:XLRD_NOT_INSTALLED") from error
    if actual_xlrd_version != EXPECTED_XLRD_VERSION:
        raise SourceReject(f"DATA_REJECT:XLRD_VERSION:{actual_xlrd_version}")
    try:
        workbook = pd.ExcelFile(io.BytesIO(raw), engine="xlrd")
        sheet_names = list(workbook.sheet_names)
        if not sheet_names:
            raise SourceReject("DATA_REJECT:NO_SHEETS")
        frame = workbook.parse(sheet_name=sheet_names[0])
    except SourceReject:
        raise
    except Exception as error:
        raise SourceReject(
            f"DATA_REJECT:XLS:{type(error).__name__}"
        ) from error
    rows, diagnostics = validate_frame(frame)
    diagnostics["sheet_names"] = sheet_names
    diagnostics["selected_sheet"] = sheet_names[0]
    diagnostics["xlrd_version"] = actual_xlrd_version
    return rows, diagnostics


def normalized_csv(rows: list[tuple[str, Decimal]]) -> bytes:
    values = ["date,gprd"]
    values.extend(f"{day},{format(value, 'f')}" for day, value in rows)
    return ("\n".join(values) + "\n").encode("ascii")


def verify_spec(path: Path) -> dict[str, Any]:
    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:JSON") from error
    if (
        spec.get("document_type") != EXPECTED_DOCUMENT_TYPE
        or spec.get("family_id") != EXPECTED_FAMILY_ID
        or spec.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or spec.get("research_classification")
        != "PRE_BTC_OUTCOME_EXACT_GPR_VINTAGE_SOURCE_AND_PREDICTIVE_PREREGISTRATION"
        or spec.get("acquisition_policy") != EXPECTED_ACQUISITION_POLICY
    ):
        raise SourceReject("SPEC_REJECT:CONTRACT")
    for binding in spec.get("bindings", []):
        bound = REPO_ROOT / str(binding.get("path", ""))
        if not bound.is_file() or sha256_path(bound) != binding.get("sha256"):
            raise SourceReject(f"BINDING_REJECT:{binding.get('role')}")
    return spec


def build_bundle(
    spec_path: Path,
    *,
    fetcher: Callable[[], tuple[bytes, str]] = fetch_source,
) -> tuple[dict[str, Any], bytes]:
    spec = verify_spec(spec_path)
    raw, content_type = fetcher()
    rows, diagnostics = parse_workbook(raw)
    normalized_raw = normalized_csv(rows)
    normalized_gzip = common.deterministic_gzip(normalized_raw)
    annual_rows = {
        str(year): sum(day.startswith(f"{year}-") for day, _ in rows)
        for year in range(2018, 2025)
    }
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_GPR_DAILY_VINTAGE_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_EXACT_DATED_COMPLETE_GPR_SOURCE_NO_BTC_OUTCOME",
        "family_id": EXPECTED_FAMILY_ID,
        "source_contract": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256_path(spec_path),
            "created_at": spec["created_at"],
        },
        "source": {
            "url": SOURCE_URL,
            "content_type": content_type,
            "response_bytes": len(raw),
            "response_sha256": hashlib.sha256(raw).hexdigest(),
            **diagnostics,
        },
        "corpus": {
            "field": "GPRD",
            "first_day": FIRST_DAY.isoformat(),
            "last_day": LAST_DAY.isoformat(),
            "row_count": len(rows),
            "annual_rows": annual_rows,
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": hashlib.sha256(normalized_raw).hexdigest(),
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": hashlib.sha256(normalized_gzip).hexdigest(),
        },
        "integrity": {
            "exact_dated_vintage_only": True,
            "every_required_calendar_day_present": True,
            "duplicate_days": 0,
            "nonfinite_or_negative_gprd_rows": 0,
            "imputed_or_repaired_rows": 0,
            "seven_day_availability_lag_frozen": True,
            "btc_outcome_accessed": False,
        },
        "scope_note": "Exact free dated GPR source acquisition only. No BTC price, return, factor outcome, strategy ledger, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    return bundle, normalized_gzip


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    spec_path = Path(args.spec).resolve()
    bundle_path = common.state_path(args.bundle)
    normalized_path = common.state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise SourceReject("PATH_REJECT:DUPLICATE")
    bundle, normalized_gzip = build_bundle(spec_path)
    bundle_raw = common.canonical_bytes(bundle)
    common.write_create_once(normalized_path, normalized_gzip)
    try:
        common.write_create_once(bundle_path, bundle_raw)
    except Exception:
        normalized_path.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256_path(bundle_path),
                "normalized_gzip": normalized_path.relative_to(REPO_ROOT).as_posix(),
                "normalized_gzip_sha256": sha256_path(normalized_path),
                "row_count": bundle["corpus"]["row_count"],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
