#!/usr/bin/env python3
"""Acquire one exact pre-2025 archived Alternative.me FGI response."""

from __future__ import annotations

import argparse
import base64
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

from research import binance_spot_usdt_daily_corpus_v1 as common


REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_ALTERNATIVE_ME_FGI_WAYBACK_SOURCE_SPEC_V1"
EXPECTED_FAMILY_ID = "btc-alternative-me-extreme-fear-contrarian-long-cash"
CAPTURE_TIMESTAMP = "20241126094019"
ORIGINAL_URL = "https://api.alternative.me/fng/?limit=10000"
REPLAY_URL = (
    f"https://web.archive.org/web/{CAPTURE_TIMESTAMP}id_/"
    f"{ORIGINAL_URL}"
)
EXPECTED_CDX_DIGEST = "V4YE4TMPOQINOT55Y2WPDODDVBM7WTTO"
FIRST_DAY = date(2018, 2, 1)
LAST_DAY = date(2024, 11, 25)
MAX_SOURCE_BYTES = 2 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
ALLOWED_CLASSIFICATIONS = {
    "Extreme Fear",
    "Fear",
    "Neutral",
    "Greed",
    "Extreme Greed",
}
EXPECTED_ACQUISITION_POLICY = {
    "capture_timestamp": CAPTURE_TIMESTAMP,
    "original_url": ORIGINAL_URL,
    "replay_url": REPLAY_URL,
    "cdx_digest": EXPECTED_CDX_DIGEST,
    "response_format": "ARCHIVED_ORIGINAL_JSON_NO_MEMENTO_WRAPPER",
    "selected_first_day": FIRST_DAY.isoformat(),
    "selected_last_day": LAST_DAY.isoformat(),
    "selected_complete_calendar_days": "REQUIRE_EVERY_DAY",
    "selected_fields": [
        "date",
        "value",
        "value_classification",
        "source_timestamp",
    ],
    "credentials": "DENY",
    "redirect": "DENY",
    "automatic_retry": "DENY",
    "live_api": "DENY",
    "other_capture_or_provider": "DENY",
    "btc_outcome_access": "DENY",
}


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


@dataclass(frozen=True)
class FgiRow:
    day: str
    value: int
    classification: str
    source_timestamp: int


def sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expected_days() -> list[str]:
    values: list[str] = []
    current = FIRST_DAY
    while current <= LAST_DAY:
        values.append(current.isoformat())
        current += timedelta(days=1)
    return values


def cdx_digest(raw: bytes) -> str:
    return base64.b32encode(hashlib.sha1(raw).digest()).decode("ascii").rstrip("=")


def fetch_source() -> tuple[bytes, dict[str, str]]:
    request = Request(
        REPLAY_URL,
        method="GET",
        headers={"User-Agent": "AgoraResearchAlternativeMeFgiWayback/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            raw = response.read(MAX_SOURCE_BYTES + 1)
            headers = {
                str(key).lower(): str(value)
                for key, value in response.headers.items()
            }
    except SourceReject:
        raise
    except HTTPError as error:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{error.code}") from error
    except (URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_SOURCE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw, headers


def parse_source(raw: bytes) -> tuple[list[FgiRow], dict[str, Any]]:
    if cdx_digest(raw) != EXPECTED_CDX_DIGEST:
        raise SourceReject(f"DATA_REJECT:CDX_DIGEST:{cdx_digest(raw)}")
    try:
        payload = json.loads(raw)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("DATA_REJECT:JSON") from error
    if not isinstance(payload, dict) or set(payload) != {"name", "data", "metadata"}:
        raise SourceReject("DATA_REJECT:ENVELOPE")
    if payload["name"] != "Fear and Greed Index":
        raise SourceReject("DATA_REJECT:NAME")
    if payload["metadata"] != {"error": None}:
        raise SourceReject("DATA_REJECT:METADATA")
    data = payload["data"]
    if not isinstance(data, list) or not data:
        raise SourceReject("DATA_REJECT:ROWS")
    rows: list[FgiRow] = []
    seen: set[str] = set()
    for index, item in enumerate(data):
        if not isinstance(item, dict):
            raise SourceReject(f"DATA_REJECT:ROW_TYPE:{index}")
        keys = set(item)
        if keys not in (
            {"value", "value_classification", "timestamp"},
            {"value", "value_classification", "timestamp", "time_until_update"},
        ):
            raise SourceReject(f"DATA_REJECT:ROW_FIELDS:{index}:{sorted(keys)}")
        try:
            value = int(item["value"])
            timestamp = int(item["timestamp"])
        except (TypeError, ValueError) as error:
            raise SourceReject(f"DATA_REJECT:ROW_NUMBER:{index}") from error
        classification = item["value_classification"]
        if (
            str(value) != item["value"]
            or str(timestamp) != item["timestamp"]
            or not 0 <= value <= 100
            or classification not in ALLOWED_CLASSIFICATIONS
        ):
            raise SourceReject(f"DATA_REJECT:ROW_VALUE:{index}")
        try:
            day = datetime.fromtimestamp(timestamp, tz=timezone.utc).date().isoformat()
        except (OverflowError, OSError, ValueError) as error:
            raise SourceReject(f"DATA_REJECT:TIMESTAMP:{index}") from error
        if day in seen:
            raise SourceReject(f"DATA_REJECT:DUPLICATE_DAY:{day}")
        seen.add(day)
        rows.append(FgiRow(day, value, classification, timestamp))
    rows.sort(key=lambda row: row.day)
    selected = [row for row in rows if FIRST_DAY.isoformat() <= row.day <= LAST_DAY.isoformat()]
    actual = [row.day for row in selected]
    required = expected_days()
    if actual != required:
        missing = sorted(set(required) - set(actual))
        extra = sorted(set(actual) - set(required))
        raise SourceReject(
            f"DATA_REJECT:COVERAGE:missing={missing[:5]}:extra={extra[:5]}"
        )
    return selected, {
        "response_row_count": len(rows),
        "response_first_day": rows[0].day,
        "response_last_day": rows[-1].day,
        "selected_row_count": len(selected),
        "selected_first_day": selected[0].day,
        "selected_last_day": selected[-1].day,
        "selected_classification_counts": {
            classification: sum(
                row.classification == classification for row in selected
            )
            for classification in sorted(ALLOWED_CLASSIFICATIONS)
        },
    }


def normalized_csv(rows: list[FgiRow]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        ["date", "value", "value_classification", "source_timestamp"]
    )
    for row in rows:
        writer.writerow(
            [row.day, row.value, row.classification, row.source_timestamp]
        )
    return output.getvalue().encode("ascii")


def verify_spec(path: Path) -> dict[str, Any]:
    try:
        spec = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:JSON") from error
    if (
        spec.get("document_type") != EXPECTED_DOCUMENT_TYPE
        or spec.get("family_id") != EXPECTED_FAMILY_ID
        or spec.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or spec.get("research_classification")
        != "PRE_FGI_VALUE_EXACT_ARCHIVED_SOURCE_AND_PREDICTIVE_PREREGISTRATION"
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
    fetcher: Callable[[], tuple[bytes, dict[str, str]]] = fetch_source,
) -> tuple[dict[str, Any], bytes]:
    spec = verify_spec(spec_path)
    raw, headers = fetcher()
    rows, diagnostics = parse_source(raw)
    normalized_raw = normalized_csv(rows)
    normalized_gzip = common.deterministic_gzip(normalized_raw)
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_ALTERNATIVE_ME_FGI_WAYBACK_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_EXACT_ARCHIVED_PRE2025_FGI_SOURCE_NO_BTC_OUTCOME",
        "family_id": EXPECTED_FAMILY_ID,
        "attribution": "Crypto Fear and Greed Index data by Alternative.me",
        "source_contract": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256_path(spec_path),
            "created_at": spec["created_at"],
        },
        "source": {
            "capture_timestamp": CAPTURE_TIMESTAMP,
            "original_url": ORIGINAL_URL,
            "replay_url": REPLAY_URL,
            "cdx_digest": cdx_digest(raw),
            "response_bytes": len(raw),
            "response_sha256": hashlib.sha256(raw).hexdigest(),
            "content_type": headers.get("content-type", ""),
            "memento_datetime": headers.get("memento-datetime", ""),
            **diagnostics,
        },
        "corpus": {
            "first_day": FIRST_DAY.isoformat(),
            "last_day": LAST_DAY.isoformat(),
            "row_count": len(rows),
            "annual_rows": {
                str(year): sum(row.day.startswith(f"{year}-") for row in rows)
                for year in range(2018, 2025)
            },
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": hashlib.sha256(normalized_raw).hexdigest(),
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": hashlib.sha256(normalized_gzip).hexdigest(),
        },
        "integrity": {
            "exact_capture_and_original_url": True,
            "cdx_payload_digest_matches": True,
            "every_selected_calendar_day_present": True,
            "duplicate_days": 0,
            "unknown_classifications": 0,
            "imputed_or_repaired_rows": 0,
            "live_api_accessed": False,
            "post_2024_fgi_selected": False,
            "btc_outcome_accessed": False,
        },
        "scope_note": "Exact archived free FGI source acquisition only. No BTC price, return, factor outcome, strategy ledger, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
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
