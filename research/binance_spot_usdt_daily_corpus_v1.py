#!/usr/bin/env python3
"""Build one checksum-verified pre-2025 Binance Spot USDT daily corpus."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile

try:
    from research import binance_spot_usdt_archive_inventory_probe as inventory_probe
except ModuleNotFoundError:  # Direct script launch adds research/ instead of repo root.
    import binance_spot_usdt_archive_inventory_probe as inventory_probe


REPO_ROOT = Path(__file__).resolve().parents[1]
INVENTORY_PATH = REPO_ROOT / ".research-state" / "experiments" / "liquid-crypto-cross-sectional-momentum-long-only-source-feasibility-v1" / "artifacts" / "run1.json"
INVENTORY_SHA256 = "9f8ee95df9b04595c83fcd341c97c77286884a39b4f96adc463de0aa02f84e6f"
DATA_BASE_URL = "https://data.binance.vision/"
COHORT_MONTH = "2019-12"
FIRST_CORPUS_MONTH = "2018-01"
LAST_CORPUS_MONTH = "2024-12"
MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
MAX_CHECKSUM_BYTES = 4096
REQUEST_TIMEOUT_SECONDS = 30
DOWNLOAD_WORKERS = 16
DAY_MS = 86_400_000
MAX_ARCHIVES = 20_000
MIN_COHORT_SYMBOLS = 20
MAX_COHORT_SYMBOLS = 300
DECIMAL_TEXT = re.compile(r"^(?:0|[1-9]\d*)(?:[.]\d+)?$")
CHECKSUM_TEXT = re.compile(r"^([0-9a-fA-F]{64})\s+[*]?([^\s]+)\s*$")
ARCHIVE_NAME = re.compile(r"^(?P<symbol>[A-Z0-9]+)-1d-(?P<month>\d{4}-\d{2})[.]zip$")

EXCLUDED_BASES = frozenset(
    {
        "AEUR", "AUD", "BIDR", "BRL", "BUSD", "BVND", "DAI", "EUR",
        "EURI", "EURT", "FDUSD", "FRAX", "GBP", "GUSD", "HUSD", "IDRT",
        "LUSD", "NGN", "PAX", "RUB", "SUSD", "TUSD", "TRY", "UAH", "USDC",
        "USDD", "USDE", "USDN", "USDP", "USDS", "USDX", "UST", "USTC",
        "VAI", "XUSD", "ZAR",
        "BTCB", "ETHW", "STETH", "WBETH", "WBTC", "WETH",
    }
)
LEVERAGED_SUFFIXES = ("UP", "DOWN", "BULL", "BEAR", "3L", "3S", "5L", "5S")
EXPECTED_ACQUISITION_POLICY = {
    "source": "BINANCE_PUBLIC_DATA_SPOT_MONTHLY_1D_ARCHIVES",
    "quote_asset": "USDT",
    "cohort_month": COHORT_MONTH,
    "first_corpus_month": FIRST_CORPUS_MONTH,
    "last_corpus_month": LAST_CORPUS_MONTH,
    "cohort_rule": "ASCII_USDT_SYMBOL_WITH_2019_12_DAILY_ARCHIVE_AFTER_FROZEN_STABLE_FIAT_WRAPPED_AND_LEVERAGED_EXCLUSIONS",
    "archive_checksum": "REQUIRE_EXACT_PUBLISHER_SHA256_FOR_EVERY_ZIP",
    "partial_terminal_session": "EXCLUDE_ONLY_FINAL_UTC_ALIGNED_SUB_24H_ROW_AND_RECORD_IDENTITY",
    "current_exchange_info": "DENY",
    "price_or_return_selection": "DENY",
    "strategy_outcome": "DENY",
}


class CorpusReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise CorpusReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


@dataclass(frozen=True)
class DailyBar:
    symbol: str
    day: str
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    quote_volume: Decimal


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def state_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise CorpusReject(f"PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise CorpusReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def read_inventory() -> dict[str, Any]:
    raw = INVENTORY_PATH.read_bytes()
    if sha256(raw) != INVENTORY_SHA256:
        raise CorpusReject("BINDING_REJECT:INVENTORY_SHA256")
    value = json.loads(raw)
    if value.get("status") != "SOURCE_FEASIBILITY_PASS_POINT_IN_TIME_BAR_PRESENCE_UNIVERSE_AVAILABLE":
        raise CorpusReject("BINDING_REJECT:INVENTORY_STATUS")
    return value


def verify_source_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise CorpusReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("research_classification") != "PRE_OUTCOME_SOURCE_ACQUISITION_ONLY":
        raise CorpusReject("MANIFEST_REJECT:CLASSIFICATION")
    if manifest.get("acquisition_policy") != EXPECTED_ACQUISITION_POLICY:
        raise CorpusReject("MANIFEST_REJECT:POLICY")
    for binding in manifest.get("source_bindings", []):
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or sha256(bound.read_bytes()) != binding["sha256"]:
            raise CorpusReject(f"BINDING_REJECT:{binding['role']}")
    return manifest


def base_asset(symbol: str) -> str:
    if not symbol.endswith("USDT") or len(symbol) <= 4:
        raise CorpusReject(f"SYMBOL_REJECT:{symbol}")
    return symbol[:-4]


def is_allowed_symbol(symbol: str) -> bool:
    base = base_asset(symbol)
    return base not in EXCLUDED_BASES and not base.endswith(LEVERAGED_SUFFIXES)


def fetch_url(url: str, *, maximum_bytes: int) -> bytes:
    request = Request(
        url,
        method="GET",
        headers={"User-Agent": "AgoraResearchBinanceDailyCorpus/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise CorpusReject(f"SOURCE_REJECT:HTTP:{response.status}:{url}")
            raw = response.read(maximum_bytes + 1)
    except CorpusReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise CorpusReject(
            f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}:{url}"
        ) from error
    if not raw or len(raw) > maximum_bytes:
        raise CorpusReject(f"SOURCE_REJECT:BYTES:{len(raw)}:{url}")
    return raw


def list_symbol_archives(
    symbol: str,
    fetcher: Callable[[dict[str, str]], bytes] = inventory_probe.fetch,
) -> dict[str, tuple[str, str]]:
    prefix = f"{inventory_probe.ROOT_PREFIX}{symbol}/1d/"
    raw = fetcher({"prefix": prefix, "max-keys": str(inventory_probe.MAX_KEYS)})
    page = inventory_probe.parse_bucket_page(
        raw, expected_prefix=prefix, expected_marker=""
    )
    if page["is_truncated"]:
        raise CorpusReject(f"SOURCE_REJECT:ARCHIVE_PAGE_TRUNCATED:{symbol}")
    keys = set(page["keys"])
    archives: dict[str, tuple[str, str]] = {}
    for key in sorted(keys):
        if not key.endswith(".zip"):
            continue
        name = key.rsplit("/", 1)[-1]
        match = ARCHIVE_NAME.fullmatch(name)
        if match is None or match.group("symbol") != symbol:
            raise CorpusReject(f"SOURCE_REJECT:ARCHIVE_KEY:{symbol}:{key}")
        checksum_key = f"{key}.CHECKSUM"
        if checksum_key not in keys:
            raise CorpusReject(f"SOURCE_REJECT:MISSING_CHECKSUM:{symbol}:{key}")
        month = match.group("month")
        if month in archives:
            raise CorpusReject(f"SOURCE_REJECT:DUPLICATE_MONTH:{symbol}:{month}")
        archives[month] = (key, checksum_key)
    return archives


def select_cohort(
    archives_by_symbol: dict[str, dict[str, tuple[str, str]]]
) -> list[str]:
    cohort = sorted(
        symbol
        for symbol, archives in archives_by_symbol.items()
        if COHORT_MONTH in archives
    )
    if not MIN_COHORT_SYMBOLS <= len(cohort) <= MAX_COHORT_SYMBOLS:
        raise CorpusReject(f"DATA_REJECT:COHORT_SIZE:{len(cohort)}")
    return cohort


def parse_checksum(raw: bytes, *, expected_filename: str) -> str:
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as error:
        raise CorpusReject("SOURCE_REJECT:CHECKSUM_ASCII") from error
    match = CHECKSUM_TEXT.fullmatch(text)
    if match is None or match.group(2) != expected_filename:
        raise CorpusReject(f"SOURCE_REJECT:CHECKSUM_FORMAT:{expected_filename}")
    return match.group(1).lower()


def _decimal(raw: str, *, field: str, row: int) -> Decimal:
    if DECIMAL_TEXT.fullmatch(raw) is None:
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{field}:{row}:{raw!r}")
    try:
        value = Decimal(raw)
    except InvalidOperation as error:
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{field}:{row}:{raw!r}") from error
    if not value.is_finite():
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{field}:{row}:{raw!r}")
    return value


def parse_daily_zip_with_diagnostics(
    raw: bytes, *, symbol: str, month: str
) -> tuple[list[DailyBar], list[dict[str, Any]]]:
    expected_filename = f"{symbol}-1d-{month}.csv"
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            if names != [expected_filename]:
                raise CorpusReject(
                    f"DATA_REJECT:ZIP_MEMBERS:{symbol}:{month}:{names}"
                )
            if archive.getinfo(expected_filename).file_size > MAX_ARCHIVE_BYTES:
                raise CorpusReject(f"DATA_REJECT:ZIP_EXPANSION:{symbol}:{month}")
            payload = archive.read(expected_filename)
    except CorpusReject:
        raise
    except (zipfile.BadZipFile, KeyError, OSError) as error:
        raise CorpusReject(f"DATA_REJECT:ZIP:{symbol}:{month}") from error
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise CorpusReject(f"DATA_REJECT:UTF8:{symbol}:{month}") from error
    if not rows:
        raise CorpusReject(f"DATA_REJECT:NO_ROWS:{symbol}:{month}")
    parsed: list[DailyBar] = []
    excluded_partial_terminal_rows: list[dict[str, Any]] = []
    seen_days: set[str] = set()
    for index, row in enumerate(rows):
        if len(row) != 12:
            raise CorpusReject(f"DATA_REJECT:ROW_WIDTH:{symbol}:{month}:{index}")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
            trade_count = int(row[8])
        except ValueError as error:
            raise CorpusReject(f"DATA_REJECT:INTEGER:{symbol}:{month}:{index}") from error
        if open_ms % DAY_MS != 0 or close_ms != open_ms + DAY_MS - 1:
            partial_terminal = (
                index == len(rows) - 1
                and open_ms % DAY_MS == 0
                and open_ms <= close_ms < open_ms + DAY_MS - 1
            )
            if not partial_terminal:
                raise CorpusReject(f"DATA_REJECT:DAILY_CLOCK:{symbol}:{month}:{index}")
            excluded_partial_terminal_rows.append(
                {
                    "symbol": symbol,
                    "month": month,
                    "row_index": index,
                    "open_time_ms": str(open_ms),
                    "close_time_ms": str(close_ms),
                    "duration_ms_inclusive": str(close_ms - open_ms + 1),
                }
            )
            continue
        day = datetime.fromtimestamp(open_ms / 1000, tz=timezone.utc).date().isoformat()
        if not day.startswith(f"{month}-") or day in seen_days:
            raise CorpusReject(f"DATA_REJECT:DAY_IDENTITY:{symbol}:{month}:{index}")
        seen_days.add(day)
        open_value = _decimal(row[1], field="open", row=index)
        high = _decimal(row[2], field="high", row=index)
        low = _decimal(row[3], field="low", row=index)
        close = _decimal(row[4], field="close", row=index)
        base_volume = _decimal(row[5], field="base_volume", row=index)
        quote_volume = _decimal(row[7], field="quote_volume", row=index)
        if min(open_value, high, low, close) <= 0:
            raise CorpusReject(f"DATA_REJECT:NONPOSITIVE_PRICE:{symbol}:{month}:{index}")
        if high < max(open_value, low, close) or low > min(open_value, high, close):
            raise CorpusReject(f"DATA_REJECT:OHLC:{symbol}:{month}:{index}")
        if base_volume < 0 or quote_volume < 0 or trade_count < 0:
            raise CorpusReject(f"DATA_REJECT:ACTIVITY:{symbol}:{month}:{index}")
        parsed.append(
            DailyBar(symbol, day, open_value, high, low, close, quote_volume)
        )
    if parsed != sorted(parsed, key=lambda bar: bar.day):
        raise CorpusReject(f"DATA_REJECT:ROW_ORDER:{symbol}:{month}")
    if not parsed:
        raise CorpusReject(f"DATA_REJECT:NO_COMPLETE_ROWS:{symbol}:{month}")
    return parsed, excluded_partial_terminal_rows


def parse_daily_zip(raw: bytes, *, symbol: str, month: str) -> list[DailyBar]:
    return parse_daily_zip_with_diagnostics(raw, symbol=symbol, month=month)[0]


def download_archive(
    item: tuple[str, str, str, str]
) -> tuple[str, str, list[DailyBar], dict[str, Any]]:
    symbol, month, key, checksum_key = item
    zip_url = f"{DATA_BASE_URL}{quote(key, safe='/')}"
    checksum_url = f"{DATA_BASE_URL}{quote(checksum_key, safe='/')}"
    checksum_raw = fetch_url(checksum_url, maximum_bytes=MAX_CHECKSUM_BYTES)
    expected_sha256 = parse_checksum(
        checksum_raw, expected_filename=key.rsplit("/", 1)[-1]
    )
    zip_raw = fetch_url(zip_url, maximum_bytes=MAX_ARCHIVE_BYTES)
    actual_sha256 = sha256(zip_raw)
    if actual_sha256 != expected_sha256:
        raise CorpusReject(f"DATA_REJECT:ARCHIVE_SHA256:{symbol}:{month}")
    try:
        bars, excluded_partial_terminal_rows = parse_daily_zip_with_diagnostics(
            zip_raw, symbol=symbol, month=month
        )
    except CorpusReject as error:
        raise CorpusReject(f"{error}:{symbol}:{month}") from error
    return symbol, month, bars, {
        "symbol": symbol,
        "month": month,
        "zip_key": key,
        "checksum_key": checksum_key,
        "zip_bytes": len(zip_raw),
        "zip_sha256": actual_sha256,
        "checksum_response_sha256": sha256(checksum_raw),
        "rows": len(bars),
        "first_day": bars[0].day,
        "last_day": bars[-1].day,
        "excluded_partial_terminal_rows": excluded_partial_terminal_rows,
    }


def deterministic_gzip(raw: bytes) -> bytes:
    target = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as compressed:
        compressed.write(raw)
    return target.getvalue()


def normalized_csv(bars: list[DailyBar]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(["symbol", "date", "open", "high", "low", "close", "quote_volume"])
    for bar in sorted(bars, key=lambda value: (value.symbol, value.day)):
        writer.writerow(
            [
                bar.symbol,
                bar.day,
                format(bar.open, "f"),
                format(bar.high, "f"),
                format(bar.low, "f"),
                format(bar.close, "f"),
                format(bar.quote_volume, "f"),
            ]
        )
    return output.getvalue().encode("ascii")


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    bundle_path = state_path(args.bundle)
    normalized_path = state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise CorpusReject("PATH_REJECT:DUPLICATE")
    source_manifest_path = Path(args.source_manifest).resolve()
    verify_source_manifest(source_manifest_path)
    source_inventory = read_inventory()
    all_usdt_symbols = source_inventory["inventory"]["usdt_symbols"]
    allowed_symbols = [
        symbol for symbol in all_usdt_symbols if is_allowed_symbol(symbol)
    ]
    archives_by_symbol: dict[str, dict[str, tuple[str, str]]] = {}
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=DOWNLOAD_WORKERS
    ) as executor:
        future_by_symbol = {
            executor.submit(list_symbol_archives, symbol): symbol
            for symbol in allowed_symbols
        }
        for future in concurrent.futures.as_completed(future_by_symbol):
            symbol = future_by_symbol[future]
            archives_by_symbol[symbol] = future.result()
    cohort = select_cohort(archives_by_symbol)
    download_items: list[tuple[str, str, str, str]] = []
    for symbol in cohort:
        for month, (key, checksum_key) in sorted(archives_by_symbol[symbol].items()):
            if FIRST_CORPUS_MONTH <= month <= LAST_CORPUS_MONTH:
                download_items.append((symbol, month, key, checksum_key))
    if not download_items or len(download_items) > MAX_ARCHIVES:
        raise CorpusReject(f"DATA_REJECT:ARCHIVE_COUNT:{len(download_items)}")
    downloaded: list[tuple[str, str, list[DailyBar], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=DOWNLOAD_WORKERS
    ) as executor:
        for result in executor.map(download_archive, download_items):
            downloaded.append(result)
    bars: list[DailyBar] = []
    ledger: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for symbol, month, month_bars, evidence in sorted(
        downloaded, key=lambda value: (value[0], value[1])
    ):
        for bar in month_bars:
            identity = (bar.symbol, bar.day)
            if identity in seen:
                raise CorpusReject(f"DATA_REJECT:DUPLICATE_BAR:{identity}")
            seen.add(identity)
            bars.append(bar)
        ledger.append(evidence)
    normalized_raw = normalized_csv(bars)
    normalized_gzip = deterministic_gzip(normalized_raw)
    annual_rows = {
        str(year): sum(bar.day.startswith(f"{year}-") for bar in bars)
        for year in range(2018, 2025)
    }
    excluded_partial_terminal_rows = [
        row
        for archive in ledger
        for row in archive["excluded_partial_terminal_rows"]
    ]
    bundle = {
        "schema_version": "1",
        "document_type": "BINANCE_SPOT_USDT_POINT_IN_TIME_DAILY_CORPUS_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_PRE_2025_CORPUS_NO_STRATEGY_OUTCOME",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_acquisition_manifest": {
            "path": source_manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(source_manifest_path.read_bytes()),
        },
        "source_inventory": {
            "path": INVENTORY_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": INVENTORY_SHA256,
            "usdt_symbol_count": len(all_usdt_symbols),
        },
        "cohort": {
            "selection_month": COHORT_MONTH,
            "rule": "ASCII_USDT_SYMBOL_WITH_2019_12_DAILY_ARCHIVE_AFTER_FROZEN_STABLE_FIAT_WRAPPED_AND_LEVERAGED_EXCLUSIONS",
            "allowed_symbol_inventory_count": len(allowed_symbols),
            "cohort_symbol_count": len(cohort),
            "symbols": cohort,
            "symbols_sha256": sha256(("\n".join(cohort) + "\n").encode("ascii")),
            "excluded_bases": sorted(EXCLUDED_BASES),
            "leveraged_suffixes": list(LEVERAGED_SUFFIXES),
        },
        "corpus": {
            "first_month": FIRST_CORPUS_MONTH,
            "last_month": LAST_CORPUS_MONTH,
            "archive_count": len(ledger),
            "row_count": len(bars),
            "annual_rows": annual_rows,
            "first_day": min(bar.day for bar in bars),
            "last_day": max(bar.day for bar in bars),
            "normalized_gzip_path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": sha256(normalized_gzip),
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": sha256(normalized_raw),
            "columns": ["symbol", "date", "open", "high", "low", "close", "quote_volume"],
        },
        "archive_ledger": ledger,
        "integrity": {
            "every_archive_matches_publisher_checksum": True,
            "every_archive_has_one_expected_csv_member": True,
            "pre_2025_millisecond_daily_clock_only": True,
            "duplicate_symbol_day_rows": 0,
            "excluded_partial_terminal_row_count": len(excluded_partial_terminal_rows),
            "excluded_partial_terminal_rows": excluded_partial_terminal_rows,
            "current_exchange_info_used": False,
            "price_or_return_selection_used": False,
        },
        "scope_note": "Private offline research corpus only. No strategy return, PnL, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(normalized_path, normalized_gzip)
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
                "normalized_gzip_sha256": sha256(normalized_gzip),
                "cohort_symbol_count": len(cohort),
                "archive_count": len(ledger),
                "row_count": len(bars),
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
