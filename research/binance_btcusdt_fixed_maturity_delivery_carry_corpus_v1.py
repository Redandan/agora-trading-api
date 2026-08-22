#!/usr/bin/env python3
"""Seal the frozen Binance BTCUSDT fixed-maturity delivery carry corpus."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import io
import json
from pathlib import Path
from typing import Any
from urllib.parse import quote

try:
    from research import binance_btcusdt_funding_carry_corpus_v1 as base
    from research import binance_btcusdt_delivery_archive_inventory_probe_v1 as inventory
except ModuleNotFoundError:  # Direct script launch from the research directory.
    import binance_btcusdt_funding_carry_corpus_v1 as base
    import binance_btcusdt_delivery_archive_inventory_probe_v1 as inventory


REPO_ROOT = Path(__file__).resolve().parents[1]
DELIVERY_URL = "https://fapi.binance.com/futures/data/delivery-price?pair=BTCUSDT"
HOUR_MS = 3_600_000
MAX_DELIVERY_BYTES = 256 * 1024
MAX_TOTAL_DOWNLOAD_BYTES = 512 * 1024 * 1024
DOWNLOAD_WORKERS = 8


class DeliveryCorpusReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DeliveryPrice:
    delivery_time_ms: int
    delivery_price: Decimal


@dataclass(frozen=True)
class ArchiveTask:
    dataset: str
    symbol: str
    month: str
    key: str
    member: str


def sha256(raw_or_path: bytes | Path) -> str:
    return base.sha256(raw_or_path)


def load_spec(path: Path) -> tuple[dict[str, Any], str]:
    resolved = path.resolve()
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError as error:
        raise DeliveryCorpusReject(f"SPEC_REJECT:PATH:{resolved}") from error
    if not resolved.is_file() or resolved.is_symlink():
        raise DeliveryCorpusReject(f"SPEC_REJECT:FILE:{resolved}")
    raw = resolved.read_bytes()
    try:
        spec = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DeliveryCorpusReject("SPEC_REJECT:JSON") from error
    if (
        spec.get("document_type")
        != "BTC_BINANCE_FIXED_MATURITY_DELIVERY_CARRY_SOURCE_LEDGER_SPEC_V1"
        or spec.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or spec.get("research_classification")
        != "PRE_OUTCOME_SOURCE_ACCOUNTING_AND_GATE_FREEZE"
    ):
        raise DeliveryCorpusReject("SPEC_REJECT:IDENTITY")
    bindings = spec.get("source_bindings", {})
    if bindings.get("corpus_adapter_sha256") != sha256(Path(__file__).resolve()):
        raise DeliveryCorpusReject("SPEC_REJECT:CORPUS_ADAPTER_SHA256")
    for binding in bindings.get("repository_files", []):
        source = (REPO_ROOT / binding["path"]).resolve()
        if not source.is_file() or source.is_symlink() or sha256(source) != binding["sha256"]:
            raise DeliveryCorpusReject(f"SPEC_REJECT:BINDING:{binding['role']}")
    return spec, sha256(raw)


def parse_delivery_prices(raw: bytes) -> list[DeliveryPrice]:
    try:
        payload = json.loads(raw, parse_float=str)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DeliveryCorpusReject("SOURCE_REJECT:DELIVERY_JSON") from error
    if not isinstance(payload, list) or not payload:
        raise DeliveryCorpusReject("SOURCE_REJECT:DELIVERY_ENVELOPE")
    values: list[DeliveryPrice] = []
    seen: set[int] = set()
    for index, item in enumerate(payload):
        if not isinstance(item, dict) or not {"deliveryTime", "deliveryPrice"}.issubset(item):
            raise DeliveryCorpusReject(f"SOURCE_REJECT:DELIVERY_ROW:{index}")
        try:
            delivery_time = int(item["deliveryTime"])
        except (TypeError, ValueError) as error:
            raise DeliveryCorpusReject(f"SOURCE_REJECT:DELIVERY_TIME:{index}") from error
        price = base._decimal(
            str(item["deliveryPrice"]),
            signed=False,
            context=f"delivery_price:{index}",
        )
        if (
            delivery_time <= 0
            or delivery_time % HOUR_MS
            or price <= 0
            or delivery_time in seen
        ):
            raise DeliveryCorpusReject(f"SOURCE_REJECT:DELIVERY_IDENTITY:{index}")
        seen.add(delivery_time)
        values.append(DeliveryPrice(delivery_time, price))
    if values != sorted(values, key=lambda value: value.delivery_time_ms):
        raise DeliveryCorpusReject("SOURCE_REJECT:DELIVERY_ORDER")
    return values


def selected_schedule(
    spec: dict[str, Any], deliveries: list[DeliveryPrice]
) -> list[dict[str, Any]]:
    symbols = list(spec["source_contract"]["exact_contract_symbols"])
    if symbols != sorted(symbols) or len(symbols) != len(set(symbols)):
        raise DeliveryCorpusReject("SPEC_REJECT:CONTRACT_ORDER_OR_DUPLICATE")
    by_day: dict[str, list[DeliveryPrice]] = {}
    for delivery in deliveries:
        day = datetime.fromtimestamp(
            delivery.delivery_time_ms / 1000, tz=timezone.utc
        ).strftime("%Y-%m-%d")
        by_day.setdefault(day, []).append(delivery)
    schedule: list[dict[str, Any]] = []
    for index, symbol in enumerate(symbols[1:], start=1):
        previous_expiry = inventory.contract_expiry(symbols[index - 1]).replace(
            tzinfo=timezone.utc
        )
        expiry = inventory.contract_expiry(symbol).replace(tzinfo=timezone.utc)
        matches = by_day.get(expiry.strftime("%Y-%m-%d"), [])
        if len(matches) != 1:
            raise DeliveryCorpusReject(
                f"SOURCE_REJECT:DELIVERY_MATCH:{symbol}:{len(matches)}"
            )
        delivery = matches[0]
        entry = (previous_expiry + timedelta(days=1)).replace(
            hour=0, minute=0, second=0, microsecond=0
        )
        entry_ms = int(entry.timestamp() * 1000)
        if entry_ms >= delivery.delivery_time_ms:
            raise DeliveryCorpusReject(f"SOURCE_REJECT:SCHEDULE_ORDER:{symbol}")
        months = inventory.month_range(
            entry.strftime("%Y-%m"), expiry.strftime("%Y-%m")
        )
        schedule.append(
            {
                "symbol": symbol,
                "previous_contract": symbols[index - 1],
                "entry_time_ms": entry_ms,
                "delivery_time_ms": delivery.delivery_time_ms,
                "delivery_price": delivery.delivery_price,
                "months": months,
            }
        )
    if len(schedule) != len(symbols) - 1:
        raise DeliveryCorpusReject("SOURCE_REJECT:SCHEDULE_COUNT")
    return schedule


def archive_task(dataset: str, symbol: str, month: str) -> ArchiveTask:
    if dataset == "spot":
        key = f"data/spot/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip"
        member = f"BTCUSDT-1h-{month}.csv"
    elif dataset == "index":
        key = f"data/futures/um/monthly/indexPriceKlines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip"
        member = f"BTCUSDT-1h-{month}.csv"
    elif dataset == "contract":
        key = f"data/futures/um/monthly/klines/{symbol}/1h/{symbol}-1h-{month}.zip"
        member = f"{symbol}-1h-{month}.csv"
    elif dataset == "mark":
        key = f"data/futures/um/monthly/markPriceKlines/{symbol}/1h/{symbol}-1h-{month}.zip"
        member = f"{symbol}-1h-{month}.csv"
    else:
        raise DeliveryCorpusReject(f"SPEC_REJECT:DATASET:{dataset}")
    return ArchiveTask(dataset, symbol, month, key, member)


def download_one(
    task: ArchiveTask,
) -> tuple[ArchiveTask, list[base.HourBar], dict[str, Any]]:
    filename = task.key.rsplit("/", 1)[-1]
    checksum_key = f"{task.key}.CHECKSUM"
    checksum_raw = base.fetch_url(
        f"{base.BASE_URL}{quote(checksum_key, safe='/')}",
        maximum_bytes=base.MAX_CHECKSUM_BYTES,
    )
    expected_sha = base.parse_checksum(checksum_raw, filename=filename)
    zip_raw = base.fetch_url(
        f"{base.BASE_URL}{quote(task.key, safe='/')}",
        maximum_bytes=base.MAX_ZIP_BYTES,
    )
    actual_sha = sha256(zip_raw)
    if actual_sha != expected_sha:
        raise DeliveryCorpusReject(
            f"DATA_REJECT:ARCHIVE_SHA256:{task.dataset}:{task.symbol}:{task.month}"
        )
    payload = base.unzip_one(zip_raw, member=task.member)
    bars = base.parse_kline_csv(
        payload,
        month=task.month,
        dataset=f"{task.dataset}:{task.symbol}",
    )
    evidence = {
        "dataset": task.dataset,
        "symbol": task.symbol,
        "month": task.month,
        "zip_key": task.key,
        "checksum_key": checksum_key,
        "zip_bytes": len(zip_raw),
        "zip_sha256": actual_sha,
        "checksum_response_sha256": sha256(checksum_raw),
        "csv_bytes": len(payload),
        "csv_sha256": sha256(payload),
        "rows": len(bars),
        "first_time_ms": str(bars[0].open_time_ms),
        "last_time_ms": str(bars[-1].open_time_ms),
    }
    return task, bars, evidence


def insert_bars(
    target: dict[int, base.HourBar], bars: list[base.HourBar], *, identity: str
) -> None:
    for bar in bars:
        if bar.open_time_ms in target:
            raise DeliveryCorpusReject(
                f"DATA_REJECT:DUPLICATE_TIMESTAMP:{identity}:{bar.open_time_ms}"
            )
        target[bar.open_time_ms] = bar


def normalized_csv(
    schedule: list[dict[str, Any]],
    spot: dict[int, base.HourBar],
    index: dict[int, base.HourBar],
    contracts: dict[str, dict[int, base.HourBar]],
    marks: dict[str, dict[int, base.HourBar]],
) -> tuple[bytes, list[dict[str, Any]]]:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        [
            "contract_symbol",
            "open_time_ms",
            "entry_time_ms",
            "delivery_time_ms",
            "delivery_price",
            "delivery_spot_open",
            "spot_open",
            "spot_high",
            "spot_low",
            "spot_close",
            "future_open",
            "future_high",
            "future_low",
            "future_close",
            "mark_open",
            "mark_high",
            "mark_low",
            "mark_close",
            "index_open",
            "index_high",
            "index_low",
            "index_close",
        ]
    )
    summaries: list[dict[str, Any]] = []
    for item in schedule:
        symbol = item["symbol"]
        expected = list(
            range(item["entry_time_ms"], item["delivery_time_ms"], HOUR_MS)
        )
        sources = {
            "spot": spot,
            "index": index,
            "contract": contracts[symbol],
            "mark": marks[symbol],
        }
        for name, values in sources.items():
            missing = [timestamp for timestamp in expected if timestamp not in values]
            if missing:
                raise DeliveryCorpusReject(
                    f"DATA_REJECT:HOURLY_LATTICE:{symbol}:{name}:{missing[0]}:{len(missing)}"
                )
        settlement_index = index[item["delivery_time_ms"] - HOUR_MS]
        if item["delivery_time_ms"] not in spot:
            raise DeliveryCorpusReject(
                f"DATA_REJECT:DELIVERY_SPOT_OPEN:{symbol}"
            )
        delivery_spot_open = spot[item["delivery_time_ms"]].open
        if not (
            settlement_index.low
            <= item["delivery_price"]
            <= settlement_index.high
        ):
            raise DeliveryCorpusReject(
                f"DATA_REJECT:DELIVERY_INDEX_RANGE:{symbol}"
            )
        for timestamp in expected:
            spot_bar = spot[timestamp]
            future_bar = contracts[symbol][timestamp]
            mark_bar = marks[symbol][timestamp]
            index_bar = index[timestamp]
            writer.writerow(
                [
                    symbol,
                    str(timestamp),
                    str(item["entry_time_ms"]),
                    str(item["delivery_time_ms"]),
                    format(item["delivery_price"], "f"),
                    format(delivery_spot_open, "f"),
                    *[
                        format(value, "f")
                        for value in (
                            spot_bar.open,
                            spot_bar.high,
                            spot_bar.low,
                            spot_bar.close,
                            future_bar.open,
                            future_bar.high,
                            future_bar.low,
                            future_bar.close,
                            mark_bar.open,
                            mark_bar.high,
                            mark_bar.low,
                            mark_bar.close,
                            index_bar.open,
                            index_bar.high,
                            index_bar.low,
                            index_bar.close,
                        )
                    ],
                ]
            )
        summaries.append(
            {
                "symbol": symbol,
                "previous_contract": item["previous_contract"],
                "entry_time_ms": str(item["entry_time_ms"]),
                "delivery_time_ms": str(item["delivery_time_ms"]),
                "delivery_price": format(item["delivery_price"], "f"),
                "hourly_rows": len(expected),
                "first_time_ms": str(expected[0]),
                "last_time_ms": str(expected[-1]),
                "delivery_within_preceding_index_hour_range": True,
            }
        )
    return output.getvalue().encode("ascii"), summaries


def state_path(value: str) -> Path:
    try:
        return base.state_path(value)
    except base.CorpusReject as error:
        raise DeliveryCorpusReject(str(error)) from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    spec, spec_sha256 = load_spec(args.spec)
    bundle_path = state_path(args.bundle)
    gzip_path = state_path(args.normalized_gzip)

    delivery_raw = base.fetch_url(DELIVERY_URL, maximum_bytes=MAX_DELIVERY_BYTES)
    deliveries = parse_delivery_prices(delivery_raw)
    schedule = selected_schedule(spec, deliveries)

    reference_months = inventory.month_range(
        spec["source_contract"]["reference_first_month"],
        spec["source_contract"]["reference_last_month"],
    )
    tasks: list[ArchiveTask] = []
    for month in reference_months:
        tasks.append(archive_task("spot", "BTCUSDT", month))
        tasks.append(archive_task("index", "BTCUSDT", month))
    for item in schedule:
        for month in item["months"]:
            tasks.append(archive_task("contract", item["symbol"], month))
            tasks.append(archive_task("mark", item["symbol"], month))
    task_keys = [(task.dataset, task.symbol, task.month) for task in tasks]
    if len(task_keys) != len(set(task_keys)):
        raise DeliveryCorpusReject("SPEC_REJECT:DUPLICATE_ARCHIVE_TASK")

    results: list[tuple[ArchiveTask, list[base.HourBar], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=DOWNLOAD_WORKERS
    ) as executor:
        futures = [executor.submit(download_one, task) for task in tasks]
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda result: (result[0].dataset, result[0].symbol, result[0].month))
    if sum(result[2]["zip_bytes"] for result in results) > MAX_TOTAL_DOWNLOAD_BYTES:
        raise DeliveryCorpusReject("SOURCE_REJECT:TOTAL_DOWNLOAD_BYTES")

    spot: dict[int, base.HourBar] = {}
    index_rows: dict[int, base.HourBar] = {}
    contract_rows: dict[str, dict[int, base.HourBar]] = {
        item["symbol"]: {} for item in schedule
    }
    mark_rows: dict[str, dict[int, base.HourBar]] = {
        item["symbol"]: {} for item in schedule
    }
    for task, bars, _ in results:
        if task.dataset == "spot":
            insert_bars(spot, bars, identity="spot")
        elif task.dataset == "index":
            insert_bars(index_rows, bars, identity="index")
        elif task.dataset == "contract":
            insert_bars(contract_rows[task.symbol], bars, identity=f"contract:{task.symbol}")
        else:
            insert_bars(mark_rows[task.symbol], bars, identity=f"mark:{task.symbol}")

    normalized, contract_summaries = normalized_csv(
        schedule, spot, index_rows, contract_rows, mark_rows
    )
    normalized_gzip = base.deterministic_gzip(normalized)
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_FIXED_MATURITY_DELIVERY_CARRY_CORPUS_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_PRE2025_CORPUS_NO_ECONOMIC_RESULT",
        "spec": {
            "path": args.spec.resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": spec_sha256,
        },
        "delivery_price_source": {
            "url": DELIVERY_URL,
            "response_bytes": len(delivery_raw),
            "response_sha256": sha256(delivery_raw),
            "response_rows": len(deliveries),
            "selected_delivery_rows": len(schedule),
        },
        "archive_evidence": [result[2] for result in results],
        "archive_count": len(results),
        "total_zip_bytes": sum(result[2]["zip_bytes"] for result in results),
        "contracts": contract_summaries,
        "contract_count": len(contract_summaries),
        "normalized": {
            "csv_rows": sum(item["hourly_rows"] for item in contract_summaries),
            "csv_sha256": sha256(normalized),
            "gzip_path": gzip_path.relative_to(REPO_ROOT).as_posix(),
            "gzip_sha256": sha256(normalized_gzip),
        },
        "value_boundary": {
            "first_entry_time_ms": contract_summaries[0]["entry_time_ms"],
            "last_delivery_time_ms": contract_summaries[-1]["delivery_time_ms"],
            "post_expiry_rows_included": False,
            "post_2024_rows_included": False,
            "oos_opened": False,
        },
        "scope_note": "Free official pre-2025 source corpus only. No strategy outcome, PnL, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    base.write_create_once(gzip_path, normalized_gzip)
    bundle_raw = base.canonical_bytes(bundle)
    base.write_create_once(bundle_path, bundle_raw)
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "normalized_gzip_sha256": sha256(normalized_gzip),
                "archive_count": len(results),
                "contract_count": len(contract_summaries),
                "csv_rows": bundle["normalized"]["csv_rows"],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
