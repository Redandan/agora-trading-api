#!/usr/bin/env python3
"""Seal one fixed CoinMarketCap keyless FGI source-feasibility acquisition."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-coinmarketcap-extreme-fear-contrarian-long-cash-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-coinmarketcap-extreme-fear-contrarian-long-cash-primary-prior.v1.json"
EXPECTED_DOCUMENT_TYPE = "BTC_COINMARKETCAP_FGI_HISTORICAL_SOURCE_FEASIBILITY_SPEC_V1"
FAMILY_ID = "btc-coinmarketcap-extreme-fear-contrarian-long-cash"
BASE_URL = "https://pro-api.coinmarketcap.com/public-api/v3/fear-and-greed/historical"
PAGE_STARTS = (1, 501, 1001, 1501, 2001, 2501)
PAGE_LIMIT = 500
MAX_PAGE_BYTES = 256 * 1024
REQUEST_TIMEOUT_SECONDS = 30
SELECTED_FIRST = date(2020, 1, 1)
DESIGN_LAST = date(2022, 12, 31)
VALIDATION_FIRST = date(2023, 1, 1)
SELECTED_LAST = date(2024, 11, 25)
MIN_EXTREME_FEAR = 50
MIN_OTHER = 50
MIN_TRANSITIONS = 4
ALLOWED_CLASSIFICATIONS = {
    "Extreme Fear",
    "Fear",
    "Neutral",
    "Greed",
    "Extreme Greed",
}


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, request: Any, fp: Any, code: int, message: str, headers: Any, new_url: str
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{new_url}")


@dataclass(frozen=True)
class FgiRow:
    day: date
    timestamp: int
    value: int
    classification: str


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def sha256(raw_or_path: bytes | Path) -> str:
    raw = raw_or_path.read_bytes() if isinstance(raw_or_path, Path) else raw_or_path
    return hashlib.sha256(raw).hexdigest()


def request_url(start: int) -> str:
    return f"{BASE_URL}?start={start}&limit={PAGE_LIMIT}"


def _expected_requests() -> list[dict[str, Any]]:
    return [
        {
            "method": "GET",
            "url": request_url(start),
            "start": start,
            "limit": PAGE_LIMIT,
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes": MAX_PAGE_BYTES,
            "timeout_seconds": REQUEST_TIMEOUT_SECONDS,
        }
        for start in PAGE_STARTS
    ]


def verify_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    if (
        spec.get("document_type") != EXPECTED_DOCUMENT_TYPE
        or spec.get("family_id") != FAMILY_ID
        or spec.get("status") != "FROZEN_BEFORE_ANY_CMC_HISTORICAL_VALUE_OR_BTC_OUTCOME_ACCESS"
        or prior.get("family_id") != FAMILY_ID
        or spec.get("request_contract", {}).get("requests") != _expected_requests()
    ):
        raise SourceReject("SPEC_REJECT:CONTRACT")
    expected_support = {
        "selected_first_day": SELECTED_FIRST.isoformat(),
        "design_last_day": DESIGN_LAST.isoformat(),
        "validation_first_day": VALIDATION_FIRST.isoformat(),
        "selected_last_day": SELECTED_LAST.isoformat(),
        "require_every_selected_calendar_day": True,
        "minimum_extreme_fear_days_per_window": MIN_EXTREME_FEAR,
        "minimum_other_label_days_per_window": MIN_OTHER,
        "minimum_label_transitions_per_window": MIN_TRANSITIONS,
        "btc_outcome_access": "DENY",
    }
    if spec.get("pre_outcome_source_support_gate") != expected_support:
        raise SourceReject("SPEC_REJECT:SUPPORT_GATE")
    for binding in spec.get("bindings", []):
        path = REPO_ROOT / str(binding.get("path", ""))
        if not path.is_file() or sha256(path) != binding.get("sha256"):
            raise SourceReject(f"BINDING_REJECT:{binding.get('role')}")
    return spec


def fetch_page(start: int) -> tuple[bytes, dict[str, str | None]]:
    request = Request(
        request_url(start),
        method="GET",
        headers={
            "Accept": "application/json",
            "User-Agent": "AgoraResearchCoinMarketCapFgiSourceProbe/1.0",
        },
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{start}:{response.status}")
            raw = response.read(MAX_PAGE_BYTES + 1)
            metadata = {
                "content_type": response.headers.get("content-type"),
                "etag": response.headers.get("etag"),
                "last_modified": response.headers.get("last-modified"),
            }
    except SourceReject:
        raise
    except HTTPError as error:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{start}:{error.code}") from error
    except (URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{start}:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_PAGE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{start}:{len(raw)}")
    return raw, metadata


def parse_page(raw: bytes, start: int) -> list[FgiRow]:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceReject(f"SOURCE_REJECT:JSON:{start}") from error
    if not isinstance(payload, dict) or set(payload) != {"data", "status"}:
        raise SourceReject(f"SOURCE_REJECT:ENVELOPE:{start}")
    status = payload["status"]
    if (
        not isinstance(status, dict)
        or status.get("error_code") != 0
        or status.get("error_message") not in (None, "")
    ):
        raise SourceReject(f"SOURCE_REJECT:STATUS:{start}")
    data = payload["data"]
    if not isinstance(data, list) or len(data) > PAGE_LIMIT:
        raise SourceReject(f"SOURCE_REJECT:PAGE_ROWS:{start}")
    rows: list[FgiRow] = []
    for index, item in enumerate(data):
        if not isinstance(item, dict) or set(item) != {
            "timestamp",
            "value",
            "value_classification",
        }:
            raise SourceReject(f"SOURCE_REJECT:ROW_FIELDS:{start}:{index}")
        timestamp_raw = item["timestamp"]
        value = item["value"]
        classification = item["value_classification"]
        if (
            not isinstance(timestamp_raw, str)
            or not timestamp_raw.isascii()
            or not timestamp_raw.isdigit()
            or isinstance(value, bool)
            or not isinstance(value, int)
            or not 0 <= value <= 100
            or classification not in ALLOWED_CLASSIFICATIONS
        ):
            raise SourceReject(f"SOURCE_REJECT:ROW_VALUE:{start}:{index}")
        timestamp = int(timestamp_raw)
        if timestamp % 86400 != 0:
            raise SourceReject(f"SOURCE_REJECT:NOT_UTC_MIDNIGHT:{start}:{index}")
        try:
            day = datetime.fromtimestamp(timestamp, tz=timezone.utc).date()
        except (OverflowError, OSError, ValueError) as error:
            raise SourceReject(f"SOURCE_REJECT:TIMESTAMP:{start}:{index}") from error
        rows.append(FgiRow(day, timestamp, value, classification))
    return rows


def merge_rows(pages: list[list[FgiRow]]) -> list[FgiRow]:
    rows = [row for page in pages for row in page]
    if not rows:
        raise SourceReject("SOURCE_REJECT:NO_ROWS")
    days = [row.day for row in rows]
    if len(days) != len(set(days)):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DAY")
    return sorted(rows, key=lambda row: row.day)


def _days(first: date, last: date) -> list[date]:
    return [first + timedelta(days=offset) for offset in range((last - first).days + 1)]


def _window_support(rows: list[FgiRow], first: date, last: date) -> dict[str, Any]:
    selected = [row for row in rows if first <= row.day <= last]
    extreme = sum(row.classification == "Extreme Fear" for row in selected)
    other = len(selected) - extreme
    transitions = sum(
        current.classification != prior.classification
        for prior, current in zip(selected, selected[1:], strict=False)
    )
    gate_results = {
        "minimum_extreme_fear_days": extreme >= MIN_EXTREME_FEAR,
        "minimum_other_label_days": other >= MIN_OTHER,
        "minimum_label_transitions": transitions >= MIN_TRANSITIONS,
    }
    return {
        "first_day": first.isoformat(),
        "last_day": last.isoformat(),
        "row_count": len(selected),
        "extreme_fear_days": extreme,
        "other_label_days": other,
        "label_transitions": transitions,
        "classification_counts": {
            label: sum(row.classification == label for row in selected)
            for label in sorted(ALLOWED_CLASSIFICATIONS)
        },
        "gate_results": gate_results,
        "support_pass": all(gate_results.values()),
    }


def summarize_feasibility(rows: list[FgiRow]) -> dict[str, Any]:
    observed = {row.day for row in rows}
    required = _days(SELECTED_FIRST, SELECTED_LAST)
    missing = [day.isoformat() for day in required if day not in observed]
    design = _window_support(rows, SELECTED_FIRST, DESIGN_LAST)
    validation = _window_support(rows, VALIDATION_FIRST, SELECTED_LAST)
    coverage_pass = not missing
    passed = coverage_pass and design["support_pass"] and validation["support_pass"]
    return {
        "observed_row_count": len(rows),
        "observed_first_day": rows[0].day.isoformat(),
        "observed_last_day": rows[-1].day.isoformat(),
        "selected_required_days": len(required),
        "selected_observed_days": len(required) - len(missing),
        "selected_missing_days": len(missing),
        "first_missing_days": missing[:20],
        "complete_selected_coverage": coverage_pass,
        "design": design,
        "validation": validation,
        "admission_status": (
            "PASS_COMPLETE_COVERAGE_AND_BOTH_WINDOW_LABEL_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
            if passed
            else "DATA_REJECT_COVERAGE_OR_LABEL_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
        ),
    }


def normalized_csv(rows: list[FgiRow]) -> bytes:
    selected = [row for row in rows if SELECTED_FIRST <= row.day <= SELECTED_LAST]
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(["date", "timestamp", "value", "value_classification"])
    for row in selected:
        writer.writerow(
            [row.day.isoformat(), row.timestamp, row.value, row.classification]
        )
    return output.getvalue().encode("ascii")


def output_directory(value: str) -> Path:
    path = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        path.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"OUTPUT_PATH_REJECT:{path}") from error
    if path.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{path}")
    return path


def acquire(
    fetcher: Callable[[int], tuple[bytes, dict[str, str | None]]] = fetch_page,
) -> tuple[list[bytes], list[dict[str, str | None]], list[FgiRow]]:
    raw_pages: list[bytes] = []
    metadata: list[dict[str, str | None]] = []
    parsed: list[list[FgiRow]] = []
    for start in PAGE_STARTS:
        raw, page_metadata = fetcher(start)
        raw_pages.append(raw)
        metadata.append(page_metadata)
        parsed.append(parse_page(raw, start))
    return raw_pages, metadata, merge_rows(parsed)


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    spec = verify_spec()
    target = output_directory(args.output_dir)
    raw_pages, metadata, rows = acquire()
    feasibility = summarize_feasibility(rows)
    normalized = normalized_csv(rows)
    status = (
        "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if feasibility["admission_status"].startswith("PASS_")
        else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    )
    raw_records = []
    for start, raw, page_metadata in zip(PAGE_STARTS, raw_pages, metadata, strict=True):
        raw_records.append(
            {
                "start": start,
                "path": f"raw-start-{start}.json",
                "bytes": len(raw),
                "sha256": sha256(raw),
                **page_metadata,
            }
        )
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_COINMARKETCAP_FGI_HISTORICAL_SOURCE_FEASIBILITY_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "family_id": FAMILY_ID,
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_contract": {
            "publisher": "CoinMarketCap",
            "endpoint": BASE_URL,
            "requests": _expected_requests(),
            "request_count": len(PAGE_STARTS),
            "present_vintage": True,
            "credentials": "DENY",
            "paid_api": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
        },
        "frozen_bindings": {
            "source_spec": {
                "path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(SPEC_PATH),
            },
            "primary_prior": {
                "path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(PRIOR_PATH),
            },
            "source_probe": {
                "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(Path(__file__).resolve()),
            },
        },
        "raw_pages": raw_records,
        "normalized_selected_subset": {
            "path": "normalized-selected-2020-2024.csv",
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "columns": ["date", "timestamp", "value", "value_classification"],
        },
        "pre_outcome_source_feasibility": feasibility,
        "replacement_boundary": spec["replacement_boundary"],
        "revision_boundary": "Present-vintage proprietary history only. Original daily publication vintages, later corrections and construction inputs are MISSING_PROOF; no historical result can be treated as prospective evidence.",
        "scope_note": "Source values and source-support diagnostics only. No BTC price, return, factor outcome, strategy ledger, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    created: list[Path] = []
    try:
        for record, raw in zip(raw_records, raw_pages, strict=True):
            path = target / str(record["path"])
            write_create_once(path, raw)
            created.append(path)
        normalized_path = target / "normalized-selected-2020-2024.csv"
        write_create_once(normalized_path, normalized)
        created.append(normalized_path)
        bundle_path = target / "bundle.json"
        bundle_raw = canonical_bytes(bundle)
        write_create_once(bundle_path, bundle_raw)
        created.append(bundle_path)
    except Exception:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        if target.exists() and not any(target.iterdir()):
            target.rmdir()
        raise
    print(
        json.dumps(
            {
                "status": status,
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "normalized_sha256": sha256(normalized),
                "feasibility": feasibility,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
