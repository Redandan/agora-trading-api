#!/usr/bin/env python3
"""Acquire one deterministic Coinbase/Binance BTC base-volume source corpus."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import io
import json
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

from research import binance_spot_usdt_daily_corpus_v1 as common


REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_COINBASE_BINANCE_RELATIVE_VOLUME_SOURCE_MANIFEST_V1"
EXPECTED_FAMILY_ID = "btc-coinbase-binance-relative-volume-share-long-cash"
COINBASE_URL = "https://api.exchange.coinbase.com/products/BTC-USD/candles"
BINANCE_SYMBOL = "BTCUSDT"
FIRST_DAY = date(2020, 1, 1)
LAST_DAY = date(2024, 12, 31)
FIRST_MONTH = "2020-01"
LAST_MONTH = "2024-12"
DAY_SECONDS = 86_400
DAY_MS = DAY_SECONDS * 1000
MAX_COINBASE_BYTES = 512 * 1024
REQUEST_TIMEOUT_SECONDS = 30
EXPECTED_ACQUISITION_POLICY = {
    "coinbase_source": "PUBLIC_EXCHANGE_BTC_USD_PRODUCT_CANDLES",
    "coinbase_granularity_seconds": DAY_SECONDS,
    "coinbase_partitions": "FIXED_CALENDAR_HALF_YEARS_2020_THROUGH_2024",
    "coinbase_out_of_partition_rows": "EXCLUDE_AND_RECORD_ONLY",
    "binance_source": "PUBLIC_DATA_BTCUSDT_SPOT_MONTHLY_1D_ARCHIVES",
    "binance_archive_checksum": "REQUIRE_EXACT_PUBLISHER_SHA256_FOR_EVERY_ZIP",
    "first_day": FIRST_DAY.isoformat(),
    "last_day": LAST_DAY.isoformat(),
    "common_day_policy": "REQUIRE_EVERY_COMPLETE_UTC_DAY_ON_BOTH_VENUES",
    "output_fields": ["venue", "symbol", "date", "base_volume_btc"],
    "price_fields": "TRANSPORTED_BY_SOURCE_RESPONSE_BUT_DISCARDED_WITHOUT_OUTPUT_OR_USE",
    "credentials": "DENY",
    "automatic_retry": "DENY",
    "strategy_outcome": "DENY",
}


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


@dataclass(frozen=True)
class VolumeBar:
    venue: str
    symbol: str
    day: str
    base_volume_btc: Decimal


def month_sequence() -> list[str]:
    values: list[str] = []
    year, month = 2020, 1
    while (year, month) <= (2024, 12):
        values.append(f"{year:04d}-{month:02d}")
        if month == 12:
            year += 1
            month = 1
        else:
            month += 1
    return values


def expected_days() -> list[str]:
    values: list[str] = []
    current = FIRST_DAY
    while current <= LAST_DAY:
        values.append(current.isoformat())
        current += timedelta(days=1)
    return values


def half_year_partitions() -> list[tuple[date, date]]:
    values: list[tuple[date, date]] = []
    for year in range(2020, 2025):
        values.extend(
            [
                (date(year, 1, 1), date(year, 7, 1)),
                (date(year, 7, 1), date(year + 1, 1, 1)),
            ]
        )
    return values


def _decimal(value: Any, *, identity: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise SourceReject(f"DATA_REJECT:DECIMAL:{identity}")
    try:
        parsed = Decimal(str(value))
    except InvalidOperation as error:
        raise SourceReject(f"DATA_REJECT:DECIMAL:{identity}") from error
    if not parsed.is_finite() or parsed <= 0:
        raise SourceReject(f"DATA_REJECT:NONPOSITIVE_VOLUME:{identity}")
    return parsed


def _utc_text(value: date) -> str:
    return f"{value.isoformat()}T00:00:00Z"


def parse_coinbase_partition(
    raw: bytes, *, start: date, end: date
) -> tuple[list[VolumeBar], list[str]]:
    try:
        rows = json.loads(raw, parse_float=Decimal)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SOURCE_REJECT:COINBASE_JSON") from error
    if not isinstance(rows, list) or len(rows) > 300:
        raise SourceReject(f"SOURCE_REJECT:COINBASE_ROWS:{len(rows) if isinstance(rows, list) else 'TYPE'}")
    bars: list[VolumeBar] = []
    excluded: list[str] = []
    seen: set[str] = set()
    for index, row in enumerate(rows):
        if not isinstance(row, list) or len(row) != 6:
            raise SourceReject(f"DATA_REJECT:COINBASE_ROW:{index}")
        timestamp = row[0]
        if isinstance(timestamp, bool) or not isinstance(timestamp, int):
            raise SourceReject(f"DATA_REJECT:COINBASE_TIME:{index}")
        if timestamp % DAY_SECONDS != 0:
            raise SourceReject(f"DATA_REJECT:COINBASE_CLOCK:{index}:{timestamp}")
        day = datetime.fromtimestamp(timestamp, tz=timezone.utc).date()
        identity = day.isoformat()
        if not start <= day < end:
            excluded.append(identity)
            continue
        if identity in seen:
            raise SourceReject(f"DATA_REJECT:COINBASE_DUPLICATE:{identity}")
        seen.add(identity)
        bars.append(
            VolumeBar(
                "COINBASE",
                "BTC-USD",
                identity,
                _decimal(row[5], identity=f"COINBASE:{identity}"),
            )
        )
    bars.sort(key=lambda value: value.day)
    required = []
    current = start
    while current < end:
        required.append(current.isoformat())
        current += timedelta(days=1)
    actual = [bar.day for bar in bars]
    if actual != required:
        missing = sorted(set(required) - set(actual))
        extra = sorted(set(actual) - set(required))
        raise SourceReject(
            f"DATA_REJECT:COINBASE_DAYS:{start}:{end}:missing={missing[:5]}:extra={extra[:5]}"
        )
    return bars, sorted(excluded)


def parse_binance_month(raw: bytes, *, month: str) -> list[VolumeBar]:
    expected_filename = f"{BINANCE_SYMBOL}-1d-{month}.csv"
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            if archive.namelist() != [expected_filename]:
                raise SourceReject(f"DATA_REJECT:BINANCE_MEMBERS:{month}")
            if archive.getinfo(expected_filename).file_size > common.MAX_ARCHIVE_BYTES:
                raise SourceReject(f"DATA_REJECT:BINANCE_EXPANSION:{month}")
            payload = archive.read(expected_filename)
    except SourceReject:
        raise
    except (zipfile.BadZipFile, KeyError, OSError) as error:
        raise SourceReject(f"DATA_REJECT:BINANCE_ZIP:{month}") from error
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeError as error:
        raise SourceReject(f"DATA_REJECT:BINANCE_UTF8:{month}") from error
    bars: list[VolumeBar] = []
    seen: set[str] = set()
    for index, row in enumerate(rows):
        if len(row) != 12:
            raise SourceReject(f"DATA_REJECT:BINANCE_ROW:{month}:{index}")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
        except ValueError as error:
            raise SourceReject(f"DATA_REJECT:BINANCE_TIME:{month}:{index}") from error
        if open_ms % DAY_MS != 0 or close_ms != open_ms + DAY_MS - 1:
            raise SourceReject(f"DATA_REJECT:BINANCE_CLOCK:{month}:{index}")
        day = datetime.fromtimestamp(open_ms / 1000, tz=timezone.utc).date().isoformat()
        if not day.startswith(f"{month}-") or day in seen:
            raise SourceReject(f"DATA_REJECT:BINANCE_DAY:{month}:{index}")
        seen.add(day)
        bars.append(
            VolumeBar(
                "BINANCE",
                BINANCE_SYMBOL,
                day,
                _decimal(row[5], identity=f"BINANCE:{day}"),
            )
        )
    if not bars or bars != sorted(bars, key=lambda value: value.day):
        raise SourceReject(f"DATA_REJECT:BINANCE_ORDER:{month}")
    return bars


def fetch_coinbase_partition(start: date, end: date) -> tuple[bytes, str]:
    query = urlencode(
        {
            "start": _utc_text(start),
            "end": _utc_text(end),
            "granularity": str(DAY_SECONDS),
        }
    )
    url = f"{COINBASE_URL}?{query}"
    request = Request(
        url,
        method="GET",
        headers={"User-Agent": "AgoraResearchCoinbaseBinanceVolume/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:COINBASE_HTTP:{response.status}")
            raw = response.read(MAX_COINBASE_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:COINBASE_TRANSPORT:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_COINBASE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:COINBASE_BYTES:{len(raw)}")
    return raw, url


def download_binance_month(
    item: tuple[str, str, str]
) -> tuple[str, list[VolumeBar], dict[str, Any]]:
    month, key, checksum_key = item
    zip_url = f"{common.DATA_BASE_URL}{quote(key, safe='/')}"
    checksum_url = f"{common.DATA_BASE_URL}{quote(checksum_key, safe='/')}"
    checksum_raw = common.fetch_url(
        checksum_url, maximum_bytes=common.MAX_CHECKSUM_BYTES
    )
    expected_sha256 = common.parse_checksum(
        checksum_raw, expected_filename=key.rsplit("/", 1)[-1]
    )
    zip_raw = common.fetch_url(zip_url, maximum_bytes=common.MAX_ARCHIVE_BYTES)
    actual_sha256 = common.sha256(zip_raw)
    if actual_sha256 != expected_sha256:
        raise SourceReject(f"DATA_REJECT:BINANCE_SHA256:{month}")
    bars = parse_binance_month(zip_raw, month=month)
    return month, bars, {
        "month": month,
        "zip_key": key,
        "checksum_key": checksum_key,
        "zip_sha256": actual_sha256,
        "checksum_response_sha256": common.sha256(checksum_raw),
        "rows": len(bars),
        "first_day": bars[0].day,
        "last_day": bars[-1].day,
    }


def acquire_coinbase(
    fetcher: Callable[[date, date], tuple[bytes, str]] = fetch_coinbase_partition,
) -> tuple[list[VolumeBar], list[dict[str, Any]]]:
    bars: list[VolumeBar] = []
    ledger: list[dict[str, Any]] = []
    for start, end in half_year_partitions():
        raw, url = fetcher(start, end)
        partition_bars, excluded = parse_coinbase_partition(
            raw, start=start, end=end
        )
        bars.extend(partition_bars)
        ledger.append(
            {
                "start": start.isoformat(),
                "end_exclusive": end.isoformat(),
                "url": url,
                "response_bytes": len(raw),
                "response_sha256": common.sha256(raw),
                "rows": len(partition_bars),
                "first_day": partition_bars[0].day,
                "last_day": partition_bars[-1].day,
                "excluded_out_of_partition_days": excluded,
            }
        )
    return bars, ledger


def acquire_binance() -> tuple[list[VolumeBar], list[dict[str, Any]]]:
    archives = common.list_symbol_archives(BINANCE_SYMBOL)
    months = month_sequence()
    missing = [month for month in months if month not in archives]
    if missing:
        raise SourceReject(f"DATA_REJECT:BINANCE_MONTHS:{','.join(missing)}")
    items = [(month, *archives[month]) for month in months]
    downloaded: list[tuple[str, list[VolumeBar], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=common.DOWNLOAD_WORKERS
    ) as executor:
        downloaded.extend(executor.map(download_binance_month, items))
    bars: list[VolumeBar] = []
    ledger: list[dict[str, Any]] = []
    for _, month_bars, evidence in sorted(downloaded, key=lambda value: value[0]):
        bars.extend(month_bars)
        ledger.append(evidence)
    return bars, ledger


def normalized_csv(bars: list[VolumeBar]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(["venue", "symbol", "date", "base_volume_btc"])
    for bar in sorted(bars, key=lambda value: (value.venue, value.day)):
        writer.writerow(
            [bar.venue, bar.symbol, bar.day, format(bar.base_volume_btc, "f")]
        )
    return output.getvalue().encode("ascii")


def verify_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("MANIFEST_REJECT:JSON") from error
    if (
        manifest.get("document_type") != EXPECTED_DOCUMENT_TYPE
        or manifest.get("family_id") != EXPECTED_FAMILY_ID
        or manifest.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("research_classification")
        != "PRE_OUTCOME_SOURCE_AND_PREDICTIVE_PREREGISTRATION"
        or manifest.get("acquisition_policy") != EXPECTED_ACQUISITION_POLICY
    ):
        raise SourceReject("MANIFEST_REJECT:CONTRACT")
    for binding in manifest.get("source_bindings", []):
        bound = REPO_ROOT / str(binding.get("path", ""))
        if (
            not bound.is_file()
            or common.sha256(bound.read_bytes()) != binding.get("sha256")
        ):
            raise SourceReject(f"BINDING_REJECT:{binding.get('role')}")
    return manifest


def validate_common_days(bars: list[VolumeBar]) -> dict[str, dict[str, int]]:
    required = expected_days()
    annual: dict[str, dict[str, int]] = {}
    for venue in ("COINBASE", "BINANCE"):
        actual = [bar.day for bar in bars if bar.venue == venue]
        if actual != required:
            missing = sorted(set(required) - set(actual))
            extra = sorted(set(actual) - set(required))
            duplicates = len(actual) - len(set(actual))
            raise SourceReject(
                f"DATA_REJECT:COMMON_DAYS:{venue}:missing={missing[:5]}:extra={extra[:5]}:duplicates={duplicates}"
            )
        annual[venue] = {
            str(year): sum(day.startswith(f"{year}-") for day in actual)
            for year in range(2020, 2025)
        }
    return annual


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.source_manifest).resolve()
    manifest = verify_manifest(manifest_path)
    bundle_path = common.state_path(args.bundle)
    normalized_path = common.state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise SourceReject("PATH_REJECT:DUPLICATE")

    coinbase_bars, coinbase_ledger = acquire_coinbase()
    binance_bars, binance_ledger = acquire_binance()
    bars = coinbase_bars + binance_bars
    annual_rows = validate_common_days(bars)
    normalized_raw = normalized_csv(bars)
    normalized_gzip = common.deterministic_gzip(normalized_raw)
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_COINBASE_BINANCE_BASE_VOLUME_SOURCE_CORPUS_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_FREE_COMPLETE_COMMON_DAY_BASE_VOLUME_SOURCE_NO_FACTOR_OR_OUTCOME",
        "family_id": EXPECTED_FAMILY_ID,
        "source_contract": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": common.sha256(manifest_path.read_bytes()),
            "created_at": manifest["created_at"],
        },
        "corpus": {
            "venues": ["COINBASE", "BINANCE"],
            "symbols": ["BTC-USD", BINANCE_SYMBOL],
            "first_day": FIRST_DAY.isoformat(),
            "last_day": LAST_DAY.isoformat(),
            "common_day_count": len(expected_days()),
            "row_count": len(bars),
            "annual_rows_by_venue": annual_rows,
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": common.sha256(normalized_gzip),
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": common.sha256(normalized_raw),
            "columns": ["venue", "symbol", "date", "base_volume_btc"],
        },
        "coinbase_request_ledger": coinbase_ledger,
        "binance_archive_ledger": binance_ledger,
        "integrity": {
            "every_coinbase_partition_has_every_complete_utc_day": True,
            "every_binance_archive_matches_publisher_checksum": True,
            "every_binance_archive_has_one_expected_csv_member": True,
            "complete_common_utc_day_intersection": True,
            "duplicate_venue_day_rows": 0,
            "positive_base_volume_only": True,
            "price_fields_output_or_used": False,
            "relative_volume_feature_computed": False,
            "strategy_outcome_computed": False,
        },
        "scope_note": "Free source acquisition only. Source responses transported OHLC fields but discarded them without output or use. No relative-volume feature, return, strategy path, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
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
                "bundle_sha256": common.sha256(bundle_raw),
                "normalized_gzip": normalized_path.relative_to(REPO_ROOT).as_posix(),
                "normalized_gzip_sha256": common.sha256(normalized_gzip),
                "common_day_count": len(expected_days()),
                "row_count": len(bars),
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
