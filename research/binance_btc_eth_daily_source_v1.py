#!/usr/bin/env python3
"""Acquire one deterministic checksum-bound BTCUSDT/ETHUSDT daily source corpus."""

from __future__ import annotations

import argparse
import concurrent.futures
from datetime import date, timedelta
import json
from pathlib import Path
from typing import Any

try:
    from research import binance_spot_usdt_daily_corpus_v1 as common
except ModuleNotFoundError:  # Direct script launch adds research/ instead of repo root.
    import binance_spot_usdt_daily_corpus_v1 as common


REPO_ROOT = Path(__file__).resolve().parents[1]
SYMBOLS = ("BTCUSDT", "ETHUSDT")
FIRST_MONTH = "2020-01"
LAST_MONTH = "2024-12"
FIRST_DAY = date(2020, 1, 1)
LAST_DAY = date(2024, 12, 31)
EXPECTED_ACQUISITION_POLICY = {
    "source": "BINANCE_PUBLIC_DATA_SPOT_MONTHLY_1D_ARCHIVES",
    "symbols": list(SYMBOLS),
    "first_month": FIRST_MONTH,
    "last_month": LAST_MONTH,
    "archive_checksum": "REQUIRE_EXACT_PUBLISHER_SHA256_FOR_EVERY_ZIP",
    "daily_intersection": "REQUIRE_EVERY_COMPLETE_UTC_DAY_FOR_BOTH_SYMBOLS",
    "partial_terminal_session": "DENY",
    "current_exchange_info": "DENY",
    "price_return_or_strategy_outcome": "DENY",
}


def expected_months() -> list[str]:
    months: list[str] = []
    year = 2020
    month = 1
    while (year, month) <= (2024, 12):
        months.append(f"{year:04d}-{month:02d}")
        if month == 12:
            year += 1
            month = 1
        else:
            month += 1
    return months


def expected_days() -> list[str]:
    days: list[str] = []
    current = FIRST_DAY
    while current <= LAST_DAY:
        days.append(current.isoformat())
        current += timedelta(days=1)
    return days


def verify_source_manifest(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    manifest = json.loads(raw)
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise common.CorpusReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("research_classification") != "PRE_OUTCOME_SOURCE_ACQUISITION_ONLY":
        raise common.CorpusReject("MANIFEST_REJECT:CLASSIFICATION")
    if manifest.get("acquisition_policy") != EXPECTED_ACQUISITION_POLICY:
        raise common.CorpusReject("MANIFEST_REJECT:POLICY")
    for binding in manifest.get("source_bindings", []):
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or common.sha256(bound.read_bytes()) != binding["sha256"]:
            raise common.CorpusReject(f"BINDING_REJECT:{binding['role']}")
    return manifest


def acquire_archives() -> tuple[list[common.DailyBar], list[dict[str, Any]]]:
    required_months = expected_months()
    archives_by_symbol: dict[str, dict[str, tuple[str, str]]] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(SYMBOLS)) as executor:
        futures = {
            executor.submit(common.list_symbol_archives, symbol): symbol
            for symbol in SYMBOLS
        }
        for future in concurrent.futures.as_completed(futures):
            symbol = futures[future]
            archives_by_symbol[symbol] = future.result()

    items: list[tuple[str, str, str, str]] = []
    for symbol in SYMBOLS:
        archives = archives_by_symbol[symbol]
        missing = [month for month in required_months if month not in archives]
        if missing:
            raise common.CorpusReject(
                f"DATA_REJECT:MISSING_MONTHS:{symbol}:{','.join(missing)}"
            )
        for month in required_months:
            key, checksum_key = archives[month]
            items.append((symbol, month, key, checksum_key))

    downloaded: list[tuple[str, str, list[common.DailyBar], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=common.DOWNLOAD_WORKERS
    ) as executor:
        for result in executor.map(common.download_archive, items):
            downloaded.append(result)

    bars: list[common.DailyBar] = []
    ledger: list[dict[str, Any]] = []
    for symbol, month, month_bars, evidence in sorted(
        downloaded, key=lambda value: (value[0], value[1])
    ):
        if evidence["excluded_partial_terminal_rows"]:
            raise common.CorpusReject(
                f"DATA_REJECT:PARTIAL_TERMINAL_SESSION:{symbol}:{month}"
            )
        bars.extend(month_bars)
        ledger.append(evidence)

    required_days = expected_days()
    for symbol in SYMBOLS:
        actual_days = [bar.day for bar in bars if bar.symbol == symbol]
        if actual_days != required_days:
            missing = sorted(set(required_days) - set(actual_days))
            extra = sorted(set(actual_days) - set(required_days))
            duplicates = len(actual_days) - len(set(actual_days))
            raise common.CorpusReject(
                "DATA_REJECT:DAILY_INTERSECTION:"
                f"{symbol}:missing={missing[:5]}:extra={extra[:5]}:duplicates={duplicates}"
            )
    return bars, ledger


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()

    source_manifest_path = Path(args.source_manifest).resolve()
    manifest = verify_source_manifest(source_manifest_path)
    bundle_path = common.state_path(args.bundle)
    normalized_path = common.state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise common.CorpusReject("PATH_REJECT:DUPLICATE")

    bars, ledger = acquire_archives()
    normalized_raw = common.normalized_csv(bars)
    normalized_gzip = common.deterministic_gzip(normalized_raw)
    annual_rows_by_symbol = {
        symbol: {
            str(year): sum(
                bar.symbol == symbol and bar.day.startswith(f"{year}-")
                for bar in bars
            )
            for year in range(2020, 2025)
        }
        for symbol in SYMBOLS
    }
    bundle = {
        "schema_version": "1",
        "document_type": "BINANCE_BTC_ETH_SPOT_DAILY_SOURCE_CORPUS_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_COMPLETE_BTC_ETH_2020_2024_SOURCE_NO_OUTCOME",
        "family_id": "btc-eth-monthly-equal-weight-rebalancing-premium",
        "source_contract": {
            "path": source_manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": common.sha256(source_manifest_path.read_bytes()),
            "created_at": manifest["created_at"],
        },
        "corpus": {
            "symbols": list(SYMBOLS),
            "first_day": FIRST_DAY.isoformat(),
            "last_day": LAST_DAY.isoformat(),
            "archive_count": len(ledger),
            "intersection_day_count": len(expected_days()),
            "row_count": len(bars),
            "annual_rows_by_symbol": annual_rows_by_symbol,
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": common.sha256(normalized_gzip),
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": common.sha256(normalized_raw),
            "columns": [
                "symbol",
                "date",
                "open",
                "high",
                "low",
                "close",
                "quote_volume",
            ],
        },
        "archive_ledger": ledger,
        "integrity": {
            "every_archive_matches_publisher_checksum": True,
            "every_archive_has_one_expected_csv_member": True,
            "exact_symbols_only": True,
            "complete_utc_day_intersection": True,
            "duplicate_symbol_day_rows": 0,
            "partial_terminal_session_rows": 0,
            "current_exchange_info_used": False,
            "price_return_or_strategy_outcome_computed": False,
        },
        "scope_note": "Offline source acquisition only. No return, relative return, ranking, strategy path, PnL, drawdown, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
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
                "archive_count": len(ledger),
                "intersection_day_count": len(expected_days()),
                "row_count": len(bars),
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
