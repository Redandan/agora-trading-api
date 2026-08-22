#!/usr/bin/env python3
"""Inventory pre-2025 Binance BTCUSDT delivery archives without opening values."""

from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
from typing import Any, Callable

try:
    from research import binance_spot_usdt_archive_inventory_probe as bucket
except ModuleNotFoundError:  # Direct script launch from the research directory.
    import binance_spot_usdt_archive_inventory_probe as bucket


REPO_ROOT = Path(__file__).resolve().parents[1]
FUTURES_KLINE_ROOT = "data/futures/um/monthly/klines/"
FUTURES_MARK_ROOT = "data/futures/um/monthly/markPriceKlines/"
SPOT_PREFIX = "data/spot/monthly/klines/BTCUSDT/1h/"
INDEX_PREFIX = "data/futures/um/monthly/indexPriceKlines/BTCUSDT/1h/"
CONTRACT = re.compile(r"^BTCUSDT_(?P<expiry>[0-9]{6})$")
INTERVAL = "1h"
MAX_PAGES = 4


class InventoryReject(RuntimeError):
    pass


def repo_file(value: str) -> Path:
    resolved = (REPO_ROOT / value).resolve()
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError as error:
        raise InventoryReject(f"SPEC_REJECT:PATH:{resolved}") from error
    if not resolved.is_file() or resolved.is_symlink():
        raise InventoryReject(f"SPEC_REJECT:FILE:{resolved}")
    return resolved


def load_spec(value: str) -> tuple[dict[str, Any], Path, str]:
    path = repo_file(value)
    raw = path.read_bytes()
    try:
        spec = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise InventoryReject("SPEC_REJECT:JSON") from error
    if (
        spec.get("document_type")
        != "BTC_BINANCE_FIXED_MATURITY_DELIVERY_SOURCE_INVENTORY_SPEC_V1"
    ):
        raise InventoryReject("SPEC_REJECT:DOCUMENT_TYPE")
    expected_probe = spec.get("source_bindings", {}).get("probe_sha256")
    actual_probe = bucket.sha256(Path(__file__).resolve().read_bytes())
    if expected_probe != actual_probe:
        raise InventoryReject("SPEC_REJECT:PROBE_SHA256")
    return spec, path, bucket.sha256(raw)


def month_range(first: str, last: str) -> list[str]:
    try:
        current = datetime.strptime(first, "%Y-%m")
        end = datetime.strptime(last, "%Y-%m")
    except ValueError as error:
        raise InventoryReject("SPEC_REJECT:MONTH") from error
    if current > end:
        raise InventoryReject("SPEC_REJECT:MONTH_ORDER")
    values: list[str] = []
    while current <= end:
        values.append(current.strftime("%Y-%m"))
        year = current.year + (1 if current.month == 12 else 0)
        month = 1 if current.month == 12 else current.month + 1
        current = current.replace(year=year, month=month)
    return values


def contract_expiry(symbol: str) -> datetime:
    match = CONTRACT.fullmatch(symbol)
    if match is None:
        raise InventoryReject(f"SOURCE_REJECT:CONTRACT_SYMBOL:{symbol}")
    try:
        return datetime.strptime(match.group("expiry"), "%y%m%d")
    except ValueError as error:
        raise InventoryReject(f"SOURCE_REJECT:CONTRACT_EXPIRY:{symbol}") from error


def enumerate_prefixes(
    root: str,
    fetcher: Callable[[dict[str, str]], bytes] = bucket.fetch,
) -> tuple[list[str], list[dict[str, Any]]]:
    marker = ""
    prefixes: list[str] = []
    pages: list[dict[str, Any]] = []
    for page_number in range(1, MAX_PAGES + 1):
        query = {"delimiter": "/", "prefix": root, "max-keys": str(bucket.MAX_KEYS)}
        if marker:
            query["marker"] = marker
        raw = fetcher(query)
        page = bucket.parse_bucket_page(
            raw, expected_prefix=root, expected_marker=marker
        )
        page_prefixes = list(page["prefixes"])
        if prefixes and page_prefixes and page_prefixes[0] <= prefixes[-1]:
            raise InventoryReject("SOURCE_REJECT:CROSS_PAGE_PREFIX_ORDER")
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
        raise InventoryReject("SOURCE_REJECT:MAX_PAGES")
    if not prefixes:
        raise InventoryReject(f"SOURCE_REJECT:NO_PREFIXES:{root}")
    return prefixes, pages


def contract_symbols(prefixes: list[str], root: str) -> list[str]:
    symbols: list[str] = []
    for prefix in prefixes:
        if not prefix.startswith(root) or not prefix.endswith("/"):
            raise InventoryReject(f"SOURCE_REJECT:PREFIX:{prefix}")
        symbol = prefix[len(root) : -1]
        if CONTRACT.fullmatch(symbol):
            symbols.append(symbol)
    if symbols != sorted(symbols) or len(symbols) != len(set(symbols)):
        raise InventoryReject("SOURCE_REJECT:CONTRACT_ORDER_OR_DUPLICATE")
    return symbols


def archive_inventory(
    *,
    prefix: str,
    stem: str,
    fetcher: Callable[[dict[str, str]], bytes] = bucket.fetch,
) -> dict[str, Any]:
    raw = fetcher({"prefix": prefix, "max-keys": str(bucket.MAX_KEYS)})
    page = bucket.parse_bucket_page(raw, expected_prefix=prefix, expected_marker="")
    if page["is_truncated"]:
        raise InventoryReject(f"SOURCE_REJECT:ARCHIVE_PAGE_TRUNCATED:{prefix}")
    escaped = re.escape(prefix + stem + f"-{INTERVAL}-")
    archive_pattern = re.compile(escaped + r"(?P<month>[0-9]{4}-[0-9]{2})[.]zip$")
    keys = list(page["keys"])
    zip_keys = [key for key in keys if key.endswith(".zip")]
    checksum_keys = {key for key in keys if key.endswith(".zip.CHECKSUM")}
    months: list[str] = []
    for key in zip_keys:
        match = archive_pattern.fullmatch(key)
        if match is None:
            raise InventoryReject(f"SOURCE_REJECT:ARCHIVE_KEY:{key}")
        if f"{key}.CHECKSUM" not in checksum_keys:
            raise InventoryReject(f"SOURCE_REJECT:MISSING_CHECKSUM:{key}")
        months.append(match.group("month"))
    if not months or months != sorted(months) or len(months) != len(set(months)):
        raise InventoryReject(f"SOURCE_REJECT:ARCHIVE_MONTHS:{prefix}")
    if len(checksum_keys) != len(zip_keys):
        raise InventoryReject(f"SOURCE_REJECT:ORPHAN_CHECKSUM:{prefix}")
    if months != month_range(months[0], months[-1]):
        raise InventoryReject(f"SOURCE_REJECT:MONTH_GAP:{prefix}")
    return {
        "prefix": prefix,
        "archive_count": len(months),
        "checksum_count": len(checksum_keys),
        "first_month": months[0],
        "last_month": months[-1],
        "months": months,
        "raw_bytes": page["raw_bytes"],
        "raw_sha256": page["raw_sha256"],
    }


def reference_inventory(
    *,
    prefix: str,
    stem: str,
    required_first: str,
    required_last: str,
    fetcher: Callable[[dict[str, str]], bytes] = bucket.fetch,
) -> dict[str, Any]:
    inventory = archive_inventory(prefix=prefix, stem=stem, fetcher=fetcher)
    required = month_range(required_first, required_last)
    available = set(inventory["months"])
    missing = [month for month in required if month not in available]
    if missing:
        raise InventoryReject(
            f"SOURCE_REJECT:REFERENCE_COVERAGE:{prefix}:{','.join(missing)}"
        )
    inventory["required_first_month"] = required_first
    inventory["required_last_month"] = required_last
    inventory["required_month_count"] = len(required)
    return inventory


def build_inventory(
    spec: dict[str, Any],
    fetcher: Callable[[dict[str, str]], bytes] = bucket.fetch,
) -> dict[str, Any]:
    selection = spec["selection_boundary"]
    expected = list(selection["exact_contract_symbols"])
    if expected != sorted(expected) or len(expected) != len(set(expected)):
        raise InventoryReject("SPEC_REJECT:EXPECTED_CONTRACT_ORDER_OR_DUPLICATE")
    if any(contract_expiry(symbol).strftime("%Y-%m-%d") > selection["cutoff_date"] for symbol in expected):
        raise InventoryReject("SPEC_REJECT:CONTRACT_AFTER_CUTOFF")

    roots: dict[str, dict[str, Any]] = {}
    root_symbols: dict[str, list[str]] = {}
    for label, root in (
        ("contract_klines", FUTURES_KLINE_ROOT),
        ("mark_price_klines", FUTURES_MARK_ROOT),
    ):
        prefixes, pages = enumerate_prefixes(root, fetcher)
        symbols = contract_symbols(prefixes, root)
        selected = [symbol for symbol in symbols if symbol in expected]
        if selected != expected:
            raise InventoryReject(
                f"SOURCE_REJECT:EXPECTED_CONTRACT_SET:{label}:{','.join(selected)}"
            )
        roots[label] = {
            "root": root,
            "pages": pages,
            "all_delivery_contract_count": len(symbols),
            "selected_contract_count": len(selected),
        }
        root_symbols[label] = selected

    contracts: list[dict[str, Any]] = []
    for symbol in expected:
        expiry = contract_expiry(symbol)
        expiry_month = expiry.strftime("%Y-%m")
        trade = archive_inventory(
            prefix=f"{FUTURES_KLINE_ROOT}{symbol}/{INTERVAL}/",
            stem=symbol,
            fetcher=fetcher,
        )
        mark = archive_inventory(
            prefix=f"{FUTURES_MARK_ROOT}{symbol}/{INTERVAL}/",
            stem=symbol,
            fetcher=fetcher,
        )
        if trade["last_month"] != expiry_month or mark["last_month"] != expiry_month:
            raise InventoryReject(f"SOURCE_REJECT:EXPIRY_MONTH_COVERAGE:{symbol}")
        intersection = sorted(set(trade["months"]) & set(mark["months"]))
        if not intersection or intersection != month_range(intersection[0], expiry_month):
            raise InventoryReject(f"SOURCE_REJECT:CONTRACT_MARK_INTERSECTION:{symbol}")
        contracts.append(
            {
                "symbol": symbol,
                "expiry_code_date": expiry.strftime("%Y-%m-%d"),
                "contract_klines": trade,
                "mark_price_klines": mark,
                "overlap_first_month": intersection[0],
                "overlap_last_month": intersection[-1],
                "overlap_month_count": len(intersection),
            }
        )

    references = {
        "spot_btcusdt": reference_inventory(
            prefix=SPOT_PREFIX,
            stem="BTCUSDT",
            required_first=selection["reference_first_month"],
            required_last=selection["reference_last_month"],
            fetcher=fetcher,
        ),
        "index_btcusdt": reference_inventory(
            prefix=INDEX_PREFIX,
            stem="BTCUSDT",
            required_first=selection["reference_first_month"],
            required_last=selection["reference_last_month"],
            fetcher=fetcher,
        ),
    }
    return {
        "root_inventories": roots,
        "contracts": contracts,
        "reference_archives": references,
        "exact_contract_count": len(contracts),
        "all_required_archive_checksum_pairs_present": True,
        "market_data_rows_opened": False,
        "price_or_basis_values_opened": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    spec, spec_path, spec_sha256 = load_spec(args.spec)
    output = bucket.state_path(args.output)
    inventory = build_inventory(spec)
    report = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_FIXED_MATURITY_DELIVERY_ARCHIVE_INVENTORY_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SOURCE_METADATA_GATE_PASS_VALUE_ACCESS_STILL_DENIED",
        "family_id": spec["family_id"],
        "spec": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": spec_sha256,
        },
        "request_contract": {
            "method": "GET",
            "bucket_url": bucket.BUCKET_URL,
            "operation": "S3_LIST_OBJECTS_V1_METADATA_ONLY",
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes_per_request": bucket.MAX_RESPONSE_BYTES,
            "archive_zip_body_downloaded": False,
            "checksum_body_downloaded": False,
            "delivery_price_endpoint_called": False,
            "current_exchange_info_called": False,
        },
        "inventory": inventory,
        "decision": "FREEZE_VALUE_ACCESS_AND_LEDGER_CONTRACT_BEFORE_ANY_ZIP_OR_SETTLEMENT_RESPONSE",
        "missing_proof": [
            "Object-name and checksum-sidecar coverage does not prove row continuity, payload checksum validity or correct timestamp units.",
            "Historical listing time, contract specification, delivery-price response coverage and settlement reconciliation remain unproven.",
            "No contract-selection rule, roll clock, maturity band, collateral policy, costs, ledger, hypothesis, manifest, runner, PnL, drawdown, candidate or OOS exists.",
        ],
        "scope_note": "S3 object metadata only. No ZIP body, CHECKSUM body, market-data row, price, basis, delivery-price response, return, PnL, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    raw = bucket.canonical_bytes(report)
    bucket.write_create_once(output, raw)
    print(
        json.dumps(
            {
                "status": report["status"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": bucket.sha256(raw),
                "exact_contract_count": inventory["exact_contract_count"],
                "market_data_rows_opened": inventory["market_data_rows_opened"],
                "price_or_basis_values_opened": inventory[
                    "price_or_basis_values_opened"
                ],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
