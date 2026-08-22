#!/usr/bin/env python3
"""Inventory Binance Spot USDT daily archives without downloading bars or outcomes."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
BUCKET_URL = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
ROOT_PREFIX = "data/spot/monthly/klines/"
MAX_KEYS = 1000
MAX_PAGES = 20
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
SYMBOL = re.compile(r"^[A-Z0-9]+$")
MONTHLY_ZIP = re.compile(
    r"^data/spot/monthly/klines/(?P<symbol>[A-Z0-9]+)/1d/"
    r"(?P=symbol)-1d-(?P<month>\d{4}-\d{2})[.]zip$"
)
PROBE_SYMBOLS = ("BTCUSDT", "ETHUSDT", "MCOUSDT", "BCCUSDT")
KNOWN_RETIRED_SYMBOLS = ("MCOUSDT", "BCCUSDT")


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


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
        raise SourceReject(f"PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def fetch(query: dict[str, str]) -> bytes:
    url = f"{BUCKET_URL}?{urlencode(query)}"
    request = Request(
        url,
        method="GET",
        headers={
            "Accept": "application/xml",
            "User-Agent": "AgoraResearchBinanceSpotArchiveInventoryProbe/1.0",
        },
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
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


def _text(root: ET.Element, name: str) -> str | None:
    node = root.find(f"{{*}}{name}")
    return None if node is None else node.text


def parse_bucket_page(
    raw: bytes, *, expected_prefix: str, expected_marker: str
) -> dict[str, Any]:
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise SourceReject("SOURCE_REJECT:XML") from error
    if root.tag.rsplit("}", 1)[-1] != "ListBucketResult":
        raise SourceReject("SOURCE_REJECT:ROOT")
    if _text(root, "Prefix") != expected_prefix:
        raise SourceReject("SOURCE_REJECT:PREFIX_ECHO")
    if (_text(root, "Marker") or "") != expected_marker:
        raise SourceReject("SOURCE_REJECT:MARKER_ECHO")
    truncated_text = _text(root, "IsTruncated")
    if truncated_text not in {"true", "false"}:
        raise SourceReject("SOURCE_REJECT:TRUNCATION_FLAG")
    prefixes = [
        prefix.text or ""
        for prefix in root.findall("{*}CommonPrefixes/{*}Prefix")
    ]
    keys = [key.text or "" for key in root.findall("{*}Contents/{*}Key")]
    if prefixes != sorted(prefixes) or len(prefixes) != len(set(prefixes)):
        raise SourceReject("SOURCE_REJECT:PREFIX_ORDER_OR_DUPLICATE")
    if keys != sorted(keys) or len(keys) != len(set(keys)):
        raise SourceReject("SOURCE_REJECT:KEY_ORDER_OR_DUPLICATE")
    next_marker = _text(root, "NextMarker")
    if truncated_text == "true" and not next_marker:
        raise SourceReject("SOURCE_REJECT:NEXT_MARKER")
    return {
        "prefixes": prefixes,
        "keys": keys,
        "is_truncated": truncated_text == "true",
        "next_marker": next_marker,
        "raw_bytes": len(raw),
        "raw_sha256": sha256(raw),
    }


def enumerate_symbol_prefixes(
    fetcher: Callable[[dict[str, str]], bytes] = fetch,
) -> tuple[list[str], list[dict[str, Any]]]:
    marker = ""
    prefixes: list[str] = []
    pages: list[dict[str, Any]] = []
    for page_number in range(1, MAX_PAGES + 1):
        query = {
            "delimiter": "/",
            "prefix": ROOT_PREFIX,
            "max-keys": str(MAX_KEYS),
        }
        if marker:
            query["marker"] = marker
        raw = fetcher(query)
        page = parse_bucket_page(
            raw, expected_prefix=ROOT_PREFIX, expected_marker=marker
        )
        page_prefixes = page["prefixes"]
        if prefixes and page_prefixes and page_prefixes[0] <= prefixes[-1]:
            raise SourceReject("SOURCE_REJECT:CROSS_PAGE_PREFIX_ORDER")
        prefixes.extend(page_prefixes)
        pages.append(
            {
                "page": page_number,
                "prefix_count": len(page_prefixes),
                "raw_bytes": page["raw_bytes"],
                "raw_sha256": page["raw_sha256"],
            }
        )
        if not page["is_truncated"]:
            break
        marker = str(page["next_marker"])
    else:
        raise SourceReject("SOURCE_REJECT:MAX_PAGES")
    if not prefixes:
        raise SourceReject("SOURCE_REJECT:NO_SYMBOL_PREFIXES")
    return prefixes, pages


def symbols_from_prefixes(
    prefixes: list[str], *, reject_non_ascii: bool = True
) -> list[str]:
    symbols: list[str] = []
    for prefix in prefixes:
        if not prefix.startswith(ROOT_PREFIX) or not prefix.endswith("/"):
            raise SourceReject(f"SOURCE_REJECT:SYMBOL_PREFIX:{prefix}")
        symbol = prefix[len(ROOT_PREFIX) : -1]
        if SYMBOL.fullmatch(symbol) is None:
            if reject_non_ascii:
                raise SourceReject(f"SOURCE_REJECT:SYMBOL:{symbol}")
            continue
        symbols.append(symbol)
    if symbols != sorted(symbols) or len(symbols) != len(set(symbols)):
        raise SourceReject("SOURCE_REJECT:SYMBOL_ORDER_OR_DUPLICATE")
    return symbols


def inspect_daily_archives(
    symbol: str, fetcher: Callable[[dict[str, str]], bytes] = fetch
) -> dict[str, Any]:
    if SYMBOL.fullmatch(symbol) is None:
        raise SourceReject(f"SOURCE_REJECT:SYMBOL:{symbol}")
    prefix = f"{ROOT_PREFIX}{symbol}/1d/"
    raw = fetcher({"prefix": prefix, "max-keys": str(MAX_KEYS)})
    page = parse_bucket_page(raw, expected_prefix=prefix, expected_marker="")
    if page["is_truncated"]:
        raise SourceReject(f"SOURCE_REJECT:ARCHIVE_PAGE_TRUNCATED:{symbol}")
    keys = page["keys"]
    zip_keys = [key for key in keys if key.endswith(".zip")]
    checksum_keys = {key for key in keys if key.endswith(".zip.CHECKSUM")}
    months: list[str] = []
    for key in zip_keys:
        match = MONTHLY_ZIP.fullmatch(key)
        if match is None or match.group("symbol") != symbol:
            raise SourceReject(f"SOURCE_REJECT:ARCHIVE_KEY:{symbol}:{key}")
        if f"{key}.CHECKSUM" not in checksum_keys:
            raise SourceReject(f"SOURCE_REJECT:MISSING_CHECKSUM:{symbol}:{key}")
        months.append(match.group("month"))
    if not months or months != sorted(months) or len(months) != len(set(months)):
        raise SourceReject(f"SOURCE_REJECT:ARCHIVE_MONTHS:{symbol}")
    if len(checksum_keys) != len(zip_keys):
        raise SourceReject(f"SOURCE_REJECT:ORPHAN_CHECKSUM:{symbol}")
    return {
        "symbol": symbol,
        "monthly_archive_count": len(months),
        "checksum_count": len(checksum_keys),
        "first_month": months[0],
        "last_month": months[-1],
        "raw_bytes": page["raw_bytes"],
        "raw_sha256": page["raw_sha256"],
    }


def build_inventory(
    fetcher: Callable[[dict[str, str]], bytes] = fetch,
) -> dict[str, Any]:
    prefixes, pages = enumerate_symbol_prefixes(fetcher)
    symbols = symbols_from_prefixes(prefixes, reject_non_ascii=False)
    excluded_non_ascii_prefixes = len(prefixes) - len(symbols)
    usdt_symbols = [symbol for symbol in symbols if symbol.endswith("USDT")]
    if len(usdt_symbols) < 100:
        raise SourceReject(f"SOURCE_REJECT:USDT_SYMBOL_COUNT:{len(usdt_symbols)}")
    for required in PROBE_SYMBOLS:
        if required not in usdt_symbols:
            raise SourceReject(f"SOURCE_REJECT:REQUIRED_SYMBOL:{required}")
    samples = [inspect_daily_archives(symbol, fetcher) for symbol in PROBE_SYMBOLS]
    sample_by_symbol = {sample["symbol"]: sample for sample in samples}
    if any(sample_by_symbol[symbol]["last_month"] >= "2021-01" for symbol in KNOWN_RETIRED_SYMBOLS):
        raise SourceReject("SOURCE_REJECT:RETIRED_SYMBOL_COVERAGE")
    symbol_bytes = ("\n".join(usdt_symbols) + "\n").encode("ascii")
    return {
        "listing_pages": pages,
        "all_prefix_count": len(prefixes),
        "ascii_symbol_count": len(symbols),
        "excluded_non_ascii_prefix_count": excluded_non_ascii_prefixes,
        "usdt_symbol_count": len(usdt_symbols),
        "usdt_symbols_sha256": sha256(symbol_bytes),
        "usdt_symbols": usdt_symbols,
        "archive_samples": samples,
        "known_retired_symbols_present": list(KNOWN_RETIRED_SYMBOLS),
    }


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = state_path(args.output)
    inventory = build_inventory()
    report = {
        "schema_version": "1",
        "document_type": "BINANCE_SPOT_USDT_MONTHLY_DAILY_ARCHIVE_INVENTORY_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SOURCE_FEASIBILITY_PASS_POINT_IN_TIME_BAR_PRESENCE_UNIVERSE_AVAILABLE",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Binance public market-data archive",
        "request_contract": {
            "method": "GET",
            "bucket_url": BUCKET_URL,
            "prefix": ROOT_PREFIX,
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes_per_request": MAX_RESPONSE_BYTES,
            "market_data_downloaded": False,
        },
        "inventory": inventory,
        "point_in_time_universe_boundary": "Use the archive's all-history USDT symbol superset. At each rebalance an asset may enter only from bars and quote volume observed before that rebalance, after a frozen minimum history; it leaves when complete bars cease. Never use current exchangeInfo or a present-day hand-selected survivor list as historical membership.",
        "checksum_boundary": "The probe requires each sampled monthly 1d zip to have a sibling CHECKSUM object. A future corpus must verify every downloaded archive against its published checksum before normalization.",
        "timestamp_boundary": "The official archive documents microsecond timestamps for Spot data from 2025-01-01 onward. A pre-2025 Design and Validation corpus avoids that transition; any future extension must normalize units explicitly.",
        "feasibility_claim": "The public bucket can enumerate a historical USDT symbol superset and retains daily archives for known retired symbols BCCUSDT and MCOUSDT, so current-list survivorship is avoidable. This does not prove the archive is globally exhaustive relative to every historical Binance listing or authorize a strategy outcome.",
        "remaining_gates": [
            "Freeze stablecoin, wrapped-duplicate and leveraged-token exclusions before downloading outcomes.",
            "Freeze one minimum-history and prior quote-volume eligibility rule, formation horizon, holding horizon and portfolio size.",
            "Estimate the bounded pre-2025 corpus size and verify all archive checksums.",
            "Bind cash and point-in-time liquid-crypto market comparators, fees, adverse slippage, turnover, drawdown, tail and concentration gates.",
        ],
        "scope_note": "Archive metadata only. No kline zip, price, volume, return, PnL, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    raw = canonical_bytes(report)
    write_create_once(output, raw)
    print(
        json.dumps(
            {
                "status": report["status"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(raw),
                "all_prefix_count": inventory["all_prefix_count"],
                "ascii_symbol_count": inventory["ascii_symbol_count"],
                "usdt_symbol_count": inventory["usdt_symbol_count"],
                "known_retired_symbols_present": inventory[
                    "known_retired_symbols_present"
                ],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
