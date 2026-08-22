#!/usr/bin/env python3
"""Seal the final V3 pre-2025 Binance BTCUSDT funding-carry corpus."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import io
import json
from pathlib import Path
import re
from typing import Any

try:
    from research import binance_btcusdt_funding_carry_corpus_v1 as base
    from research import binance_btcusdt_funding_carry_corpus_v2 as v2
except ModuleNotFoundError:  # Direct script launch adds research/ instead of repo root.
    import binance_btcusdt_funding_carry_corpus_v1 as base
    import binance_btcusdt_funding_carry_corpus_v2 as v2


REPO_ROOT = Path(__file__).resolve().parents[1]
CLOSURE_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-binance-usdm-delta-neutral-funding-carry-historical.v3.source-integrity-closure.json"
)
CLOSURE_SHA256 = "35e048656e72dd1e273f67fa8149c25a43e62f62e25202132fa4f96ebb9f04a0"
KLINE_HEADER = [
    "open_time",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "close_time",
    "quote_volume",
    "count",
    "taker_buy_volume",
    "taker_buy_quote_volume",
    "ignore",
]
SPOT_PROXY_TIMES = {
    1_581_213_600_000,
    1_582_110_000_000,
    1_582_113_600_000,
    1_582_117_200_000,
    1_582_120_800_000,
    1_582_124_400_000,
    1_582_128_000_000,
    1_583_312_400_000,
    1_583_316_000_000,
    1_587_780_000_000,
    1_587_783_600_000,
    1_593_309_600_000,
    1_593_313_200_000,
    1_593_316_800_000,
    1_606_716_000_000,
    1_608_559_200_000,
    1_608_562_800_000,
    1_608_566_400_000,
    1_608_570_000_000,
    1_608_861_600_000,
    1_613_012_400_000,
    1_613_016_000_000,
    1_614_996_000_000,
    1_618_884_000_000,
    1_618_887_600_000,
    1_619_323_200_000,
    1_619_326_800_000,
    1_619_330_400_000,
    1_619_334_000_000,
    1_628_816_400_000,
    1_628_820_000_000,
    1_628_823_600_000,
    1_628_827_200_000,
    1_628_830_800_000,
    1_632_898_800_000,
    1_632_902_400_000,
    1_640_318_400_000,
    1_679_659_200_000,
    1_679_662_800_000,
}
MARK_DAILY_DAYS = [
    "2021-07-01",
    "2021-07-24",
    "2021-07-25",
    "2021-07-26",
    "2021-07-27",
    "2022-07-31",
    "2022-10-02",
    "2023-02-24",
]
INDEX_DAILY_DAYS = [
    "2022-04-27",
    "2022-07-24",
    "2022-07-25",
    "2022-07-27",
    "2022-07-28",
    "2022-07-30",
    "2022-07-31",
    "2022-10-02",
    "2023-02-13",
    "2023-02-24",
    "2023-04-07",
    "2023-04-08",
]
MONTHLY_DATASETS = v2.DATASETS
FUNDING_DECIMAL_PATTERN = re.compile(
    r"-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?"
)


@dataclass(frozen=True)
class ParsedKlines:
    bars: list[base.HourBar]
    incomplete_rows: list[dict[str, str | int]]
    header_present: bool


@dataclass(frozen=True)
class NormalizedFunding:
    slot_time_ms: int
    actual_calc_time_ms: int
    offset_ms: int
    interval_hours: int
    rate: Decimal


def parse_funding_decimal(raw: str, *, context: str) -> Decimal:
    """Parse the publisher's exact finite decimal syntax, including exponents."""
    if FUNDING_DECIMAL_PATTERN.fullmatch(raw) is None:
        raise base.CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    try:
        value = Decimal(raw)
    except InvalidOperation as error:
        raise base.CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}") from error
    if not value.is_finite():
        raise base.CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    return value


def verify_closure(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    if base.sha256(raw) != CLOSURE_SHA256:
        raise base.CorpusReject(f"SPEC_REJECT:CLOSURE_SHA256:{base.sha256(raw)}")
    value = json.loads(raw)
    if (
        value.get("document_type")
        != "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_SOURCE_INTEGRITY_CLOSURE_V3"
        or value.get("research_classification")
        != "PRE_OUTCOME_COMPLETE_TIMESTAMP_AUDIT_AND_FINAL_SOURCE_CLOSURE"
        or value.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise base.CorpusReject("SPEC_REJECT:CLOSURE_IDENTITY")
    audit = value.get("complete_timestamp_audit", {})
    spot_times = {int(item) for item in audit.get("spot", {}).get("proxy_open_times_ms", [])}
    if (
        spot_times != SPOT_PROXY_TIMES
        or audit.get("usdm_mark_price", {}).get("daily_fallback_days") != MARK_DAILY_DAYS
        or audit.get("usdm_index_price", {}).get("daily_fallback_days") != INDEX_DAILY_DAYS
        or value.get("final_acquisition_contract", {}).get("total_archives") != 320
    ):
        raise base.CorpusReject("SPEC_REJECT:CLOSURE_INVENTORY")
    base.verify_spec(base.SPEC_PATH)
    v2.verify_erratum(v2.ERRATUM_PATH)
    return value


def parse_klines(
    payload: bytes,
    *,
    period: str,
    dataset: str,
    daily: bool,
    allow_incomplete: bool = False,
) -> ParsedKlines:
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise base.CorpusReject(f"DATA_REJECT:UTF8:{dataset}:{period}") from error
    header_present = bool(rows and rows[0] == KLINE_HEADER)
    if rows and not rows[0][0].isdigit():
        if not header_present:
            raise base.CorpusReject(f"DATA_REJECT:HEADER:{dataset}:{period}:{rows[0]}")
        rows = rows[1:]
    bars: list[base.HourBar] = []
    incomplete: list[dict[str, str | int]] = []
    seen: set[int] = set()
    for index, row in enumerate(rows):
        if len(row) != 12:
            raise base.CorpusReject(f"DATA_REJECT:ROW_WIDTH:{dataset}:{period}:{index}")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
            int(row[8])
        except ValueError as error:
            raise base.CorpusReject(f"DATA_REJECT:INTEGER:{dataset}:{period}:{index}") from error
        observed_period = datetime.fromtimestamp(open_ms / 1000, tz=timezone.utc).strftime(
            "%Y-%m-%d" if daily else "%Y-%m"
        )
        if open_ms % base.HOUR_MS or observed_period != period or open_ms in seen:
            raise base.CorpusReject(f"DATA_REJECT:HOUR_IDENTITY:{dataset}:{period}:{index}")
        seen.add(open_ms)
        if close_ms != open_ms + base.HOUR_MS - 1:
            if not allow_incomplete:
                raise base.CorpusReject(f"DATA_REJECT:HOUR_CLOCK:{dataset}:{period}:{index}")
            incomplete.append(
                {
                    "period": period,
                    "row_index": index,
                    "open_time_ms": str(open_ms),
                    "close_time_ms": str(close_ms),
                    "duration_ms_inclusive": str(close_ms - open_ms + 1),
                }
            )
            continue
        open_value = base._decimal(row[1], signed=False, context=f"{dataset}:open:{index}")
        high = base._decimal(row[2], signed=False, context=f"{dataset}:high:{index}")
        low = base._decimal(row[3], signed=False, context=f"{dataset}:low:{index}")
        close = base._decimal(row[4], signed=False, context=f"{dataset}:close:{index}")
        if min(open_value, high, low, close) <= 0:
            raise base.CorpusReject(f"DATA_REJECT:NONPOSITIVE_PRICE:{dataset}:{period}:{index}")
        if high < max(open_value, low, close) or low > min(open_value, high, close):
            raise base.CorpusReject(f"DATA_REJECT:OHLC:{dataset}:{period}:{index}")
        bars.append(base.HourBar(open_ms, open_value, high, low, close))
    if bars != sorted(bars, key=lambda value: value.open_time_ms):
        raise base.CorpusReject(f"DATA_REJECT:HOUR_ORDER:{dataset}:{period}")
    return ParsedKlines(bars, incomplete, header_present)


def parse_funding(payload: bytes, *, month: str) -> list[NormalizedFunding]:
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise base.CorpusReject(f"DATA_REJECT:UTF8:funding:{month}") from error
    if not rows or rows[0] != base.FUNDING_HEADER:
        raise base.CorpusReject(f"DATA_REJECT:FUNDING_HEADER:{month}")
    epoch_ms = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    events: list[NormalizedFunding] = []
    seen_slots: set[int] = set()
    for index, row in enumerate(rows[1:], start=1):
        if len(row) != 3:
            raise base.CorpusReject(f"DATA_REJECT:FUNDING_WIDTH:{month}:{index}")
        try:
            actual = int(row[0])
            interval = int(row[1])
        except ValueError as error:
            raise base.CorpusReject(f"DATA_REJECT:FUNDING_INTEGER:{month}:{index}") from error
        slot = round((actual - epoch_ms) / (8 * base.HOUR_MS)) * (8 * base.HOUR_MS) + epoch_ms
        offset = actual - slot
        observed_month = datetime.fromtimestamp(actual / 1000, tz=timezone.utc).strftime("%Y-%m")
        if (
            observed_month != month
            or interval != 8
            or not 0 <= offset <= 1000
            or slot in seen_slots
        ):
            raise base.CorpusReject(f"DATA_REJECT:FUNDING_CLOCK:{month}:{index}:{offset}")
        seen_slots.add(slot)
        events.append(
            NormalizedFunding(
                slot,
                actual,
                offset,
                interval,
                parse_funding_decimal(row[2], context=f"funding:rate:{index}"),
            )
        )
    if not events or events != sorted(events, key=lambda value: value.slot_time_ms):
        raise base.CorpusReject(f"DATA_REJECT:FUNDING_ORDER:{month}")
    return events


def fetch_verified(key: str, member: str) -> tuple[bytes, dict[str, Any]]:
    filename = key.rsplit("/", 1)[-1]
    checksum_key = f"{key}.CHECKSUM"
    checksum_raw = base.fetch_url(
        f"{base.BASE_URL}{base.quote(checksum_key, safe='/')}",
        maximum_bytes=base.MAX_CHECKSUM_BYTES,
    )
    expected_sha = base.parse_checksum(checksum_raw, filename=filename)
    zip_raw = base.fetch_url(
        f"{base.BASE_URL}{base.quote(key, safe='/')}", maximum_bytes=base.MAX_ZIP_BYTES
    )
    if base.sha256(zip_raw) != expected_sha:
        raise base.CorpusReject(f"DATA_REJECT:ARCHIVE_SHA256:{key}")
    payload = base.unzip_one(zip_raw, member=member)
    return payload, {
        "zip_key": key,
        "checksum_key": checksum_key,
        "zip_bytes": len(zip_raw),
        "zip_sha256": base.sha256(zip_raw),
        "checksum_response_sha256": base.sha256(checksum_raw),
        "csv_bytes": len(payload),
        "csv_sha256": base.sha256(payload),
    }


def download_monthly(item: tuple[str, str]) -> tuple[str, list[Any], dict[str, Any]]:
    dataset, month = item
    key_template, member_template = MONTHLY_DATASETS[dataset]
    key = key_template.format(month=month)
    member = member_template.format(month=month)
    payload, evidence = fetch_verified(key, member)
    incomplete_rows: list[dict[str, str | int]] = []
    header_present = False
    if dataset == "usdm_funding_rate":
        parsed: list[Any] = parse_funding(payload, month=month)
    else:
        result = parse_klines(
            payload,
            period=month,
            dataset=dataset,
            daily=False,
            allow_incomplete=dataset == "spot_klines_1h",
        )
        parsed = result.bars
        incomplete_rows = result.incomplete_rows
        header_present = result.header_present
    time_attr = "slot_time_ms" if dataset == "usdm_funding_rate" else "open_time_ms"
    evidence.update(
        {
            "frequency": "monthly",
            "dataset": dataset,
            "period": month,
            "rows": len(parsed),
            "first_time_ms": str(getattr(parsed[0], time_attr)),
            "last_time_ms": str(getattr(parsed[-1], time_attr)),
            "header_present": header_present,
            "excluded_incomplete_rows": incomplete_rows,
        }
    )
    return dataset, parsed, evidence


def download_daily(item: tuple[str, str]) -> tuple[str, list[base.HourBar], dict[str, Any]]:
    dataset, day = item
    archive_type = "markPriceKlines" if dataset == "usdm_mark_price_klines_1h" else "indexPriceKlines"
    key = f"data/futures/um/daily/{archive_type}/BTCUSDT/1h/BTCUSDT-1h-{day}.zip"
    member = f"BTCUSDT-1h-{day}.csv"
    payload, evidence = fetch_verified(key, member)
    result = parse_klines(
        payload, period=day, dataset=dataset, daily=True, allow_incomplete=False
    )
    if len(result.bars) != 24:
        raise base.CorpusReject(f"DATA_REJECT:DAILY_ROW_COUNT:{dataset}:{day}")
    evidence.update(
        {
            "frequency": "daily_fallback",
            "dataset": dataset,
            "period": day,
            "rows": len(result.bars),
            "first_time_ms": str(result.bars[0].open_time_ms),
            "last_time_ms": str(result.bars[-1].open_time_ms),
            "header_present": result.header_present,
            "excluded_incomplete_rows": [],
        }
    )
    return dataset, result.bars, evidence


def normalized_csv(
    spot: dict[int, base.HourBar],
    index: dict[int, base.HourBar],
    perp: dict[int, base.HourBar],
    mark: dict[int, base.HourBar],
    funding: dict[int, NormalizedFunding],
    expected_timestamps: list[int],
) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        [
            "open_time_ms",
            "spot_open",
            "spot_close",
            "spot_price_source",
            "perp_open",
            "perp_close",
            "mark_open",
            "mark_close",
            "funding_calc_time_ms",
            "funding_offset_ms",
            "funding_rate",
        ]
    )
    for timestamp in expected_timestamps:
        spot_bar = spot.get(timestamp, index[timestamp])
        source = (
            "BINANCE_SPOT_ARCHIVE"
            if timestamp in spot
            else "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP"
        )
        event = funding.get(timestamp)
        writer.writerow(
            [
                str(timestamp),
                format(spot_bar.open, "f"),
                format(spot_bar.close, "f"),
                source,
                format(perp[timestamp].open, "f"),
                format(perp[timestamp].close, "f"),
                format(mark[timestamp].open, "f"),
                format(mark[timestamp].close, "f"),
                "" if event is None else str(event.actual_calc_time_ms),
                "" if event is None else str(event.offset_ms),
                "" if event is None else format(event.rate, "f"),
            ]
        )
    return output.getvalue().encode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--closure", type=Path, default=CLOSURE_PATH)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    closure_path = args.closure.resolve()
    if closure_path != CLOSURE_PATH:
        raise base.CorpusReject(f"SPEC_REJECT:CLOSURE_PATH:{closure_path}")
    verify_closure(closure_path)
    bundle_path = base.state_path(args.bundle)
    normalized_path = base.state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise base.CorpusReject("PATH_REJECT:DUPLICATE")

    monthly_items = [
        (dataset, month) for dataset in MONTHLY_DATASETS for month in base.months()
    ]
    daily_items = [
        *(('usdm_mark_price_klines_1h', day) for day in MARK_DAILY_DAYS),
        *(('usdm_index_price_klines_1h', day) for day in INDEX_DAILY_DAYS),
    ]
    monthly: list[tuple[str, list[Any], dict[str, Any]]] = []
    daily: list[tuple[str, list[base.HourBar], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=base.DOWNLOAD_WORKERS) as executor:
        for result in executor.map(download_monthly, monthly_items):
            monthly.append(result)
        for result in executor.map(download_daily, daily_items):
            daily.append(result)
    ledger = sorted(
        [item[2] for item in monthly] + [item[2] for item in daily],
        key=lambda item: (item["dataset"], item["frequency"], item["period"]),
    )
    total_zip_bytes = sum(item["zip_bytes"] for item in ledger)
    if len(ledger) != 320 or total_zip_bytes > base.MAX_TOTAL_DOWNLOAD_BYTES:
        raise base.CorpusReject(
            f"DATA_REJECT:ARCHIVE_CLOSURE:{len(ledger)}:{total_zip_bytes}"
        )

    by_dataset: dict[str, dict[int, Any]] = {
        dataset: {} for dataset in MONTHLY_DATASETS
    }
    for dataset, rows, _ in monthly + daily:
        for row in rows:
            timestamp = row.slot_time_ms if dataset == "usdm_funding_rate" else row.open_time_ms
            if timestamp in by_dataset[dataset]:
                raise base.CorpusReject(
                    f"DATA_REJECT:DUPLICATE_TIMESTAMP:{dataset}:{timestamp}"
                )
            by_dataset[dataset][timestamp] = row

    start_ms = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    expected_timestamps = [
        start_ms + index * base.HOUR_MS for index in range(base.EXPECTED_HOURS)
    ]
    expected_set = set(expected_timestamps)
    for dataset in (
        "usdm_contract_klines_1h",
        "usdm_mark_price_klines_1h",
        "usdm_index_price_klines_1h",
    ):
        if set(by_dataset[dataset]) != expected_set:
            missing = sorted(expected_set - set(by_dataset[dataset]))[:3]
            extra = sorted(set(by_dataset[dataset]) - expected_set)[:3]
            raise base.CorpusReject(
                f"DATA_REJECT:FINAL_HOURLY_LATTICE:{dataset}:{missing}:{extra}"
            )
    spot_missing = expected_set - set(by_dataset["spot_klines_1h"])
    if spot_missing != SPOT_PROXY_TIMES:
        raise base.CorpusReject(
            f"DATA_REJECT:SPOT_PROXY_IDENTITY:{sorted(spot_missing)}"
        )
    if any(v2.is_execution_boundary(timestamp, expected_timestamps) for timestamp in spot_missing):
        raise base.CorpusReject("DATA_REJECT:SPOT_PROXY_AT_EXECUTION_BOUNDARY")
    expected_funding = set(expected_timestamps[::8])
    if set(by_dataset["usdm_funding_rate"]) != expected_funding:
        raise base.CorpusReject("DATA_REJECT:FINAL_FUNDING_LATTICE")
    funding_offsets = [
        event.offset_ms for event in by_dataset["usdm_funding_rate"].values()
    ]
    if min(funding_offsets) != 0 or max(funding_offsets) != 47:
        raise base.CorpusReject(
            f"DATA_REJECT:FUNDING_OFFSET_RANGE:{min(funding_offsets)}:{max(funding_offsets)}"
        )

    normalized_raw = normalized_csv(
        by_dataset["spot_klines_1h"],
        by_dataset["usdm_index_price_klines_1h"],
        by_dataset["usdm_contract_klines_1h"],
        by_dataset["usdm_mark_price_klines_1h"],
        by_dataset["usdm_funding_rate"],
        expected_timestamps,
    )
    normalized_gzip = base.deterministic_gzip(normalized_raw)
    incomplete_spot_rows = [
        row
        for archive in ledger
        if archive["dataset"] == "spot_klines_1h"
        for row in archive["excluded_incomplete_rows"]
    ]
    if len(incomplete_spot_rows) != 8:
        raise base.CorpusReject(
            f"DATA_REJECT:SPOT_INCOMPLETE_COUNT:{len(incomplete_spot_rows)}"
        )
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_CORPUS_V3",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_PRE_2025_FINAL_SOURCE_CLOSURE_NO_STRATEGY_OUTCOME",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_integrity_closure": {
            "path": closure_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": base.sha256(closure_path),
        },
        "predecessor_source_and_ledger_spec": {
            "path": base.SPEC_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": base.SPEC_SHA256,
        },
        "corpus": {
            "first_open_time_ms": str(expected_timestamps[0]),
            "last_open_time_ms": str(expected_timestamps[-1]),
            "hourly_rows": base.EXPECTED_HOURS,
            "funding_events": base.EXPECTED_FUNDING_EVENTS,
            "archive_count": len(ledger),
            "monthly_archive_count": 300,
            "daily_fallback_archive_count": 20,
            "total_zip_bytes": total_zip_bytes,
            "spot_native_hours": base.EXPECTED_HOURS - len(spot_missing),
            "spot_proxy_hours": len(spot_missing),
            "spot_proxy_open_times_ms": [str(value) for value in sorted(spot_missing)],
            "spot_incomplete_rows": incomplete_spot_rows,
            "funding_offset_min_ms": min(funding_offsets),
            "funding_offset_max_ms": max(funding_offsets),
            "normalized_gzip_path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": base.sha256(normalized_gzip),
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": base.sha256(normalized_raw),
            "columns": [
                "open_time_ms",
                "spot_open",
                "spot_close",
                "spot_price_source",
                "perp_open",
                "perp_close",
                "mark_open",
                "mark_close",
                "funding_calc_time_ms",
                "funding_offset_ms",
                "funding_rate",
            ],
        },
        "archive_ledger": ledger,
        "integrity": {
            "every_archive_matches_publisher_checksum": True,
            "every_archive_has_one_exact_expected_member": True,
            "monthly_plus_exact_daily_fallback_lattices_complete": True,
            "funding_events_uniquely_normalized_to_eight_hour_slots": True,
            "spot_proxy_identity_matches_final_closure": True,
            "spot_proxy_at_execution_boundary": False,
            "duplicates": 0,
            "post_2024_observation_accessed": False,
            "strategy_outcome_computed": False,
        },
        "scope_note": "Private offline research corpus only. No return, PnL, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    bundle_raw = base.canonical_bytes(bundle)
    base.write_create_once(normalized_path, normalized_gzip)
    try:
        base.write_create_once(bundle_path, bundle_raw)
    except Exception:
        normalized_path.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": base.sha256(bundle_raw),
                "normalized_gzip_sha256": base.sha256(normalized_gzip),
                "archive_count": len(ledger),
                "hourly_rows": base.EXPECTED_HOURS,
                "funding_events": base.EXPECTED_FUNDING_EVENTS,
                "spot_proxy_hours": len(spot_missing),
                "funding_offset_max_ms": max(funding_offsets),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
