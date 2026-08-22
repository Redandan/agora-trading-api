#!/usr/bin/env python3
"""Seal V2 carry corpus with one exact official index valuation exception."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import dataclass
from datetime import datetime, timezone
import io
import json
from pathlib import Path
from typing import Any

try:
    from research import binance_btcusdt_funding_carry_corpus_v1 as base
except ModuleNotFoundError:  # Direct script launch adds research/ instead of repo root.
    import binance_btcusdt_funding_carry_corpus_v1 as base


REPO_ROOT = Path(__file__).resolve().parents[1]
ERRATUM_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-binance-usdm-delta-neutral-funding-carry-historical.v2.source-integrity-erratum.json"
)
ERRATUM_SHA256 = "63b1de3d0e89338bbccc781f1f707c9f1dc1d7b7e16f70d9299e106a6d68a722"
EXPECTED_PROXY_TIMES = {
    1_582_110_000_000,
    1_582_113_600_000,
    1_582_117_200_000,
    1_582_120_800_000,
    1_582_124_400_000,
    1_582_128_000_000,
}
DATASETS = {
    **base.DATASETS,
    "usdm_index_price_klines_1h": (
        "data/futures/um/monthly/indexPriceKlines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip",
        "BTCUSDT-1h-{month}.csv",
    ),
}


@dataclass(frozen=True)
class SpotParse:
    bars: list[base.HourBar]
    incomplete_rows: list[dict[str, str | int]]


def verify_erratum(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    if base.sha256(raw) != ERRATUM_SHA256:
        raise base.CorpusReject(f"SPEC_REJECT:ERRATUM_SHA256:{base.sha256(raw)}")
    value = json.loads(raw)
    if (
        value.get("document_type")
        != "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_SOURCE_INTEGRITY_ERRATUM_V2"
        or value.get("research_classification")
        != "PRE_OUTCOME_SOURCE_INTEGRITY_ERRATUM_NO_STRATEGY_OUTCOME"
        or value.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise base.CorpusReject("SPEC_REJECT:ERRATUM_IDENTITY")
    closure = value.get("revised_source_closure", {})
    expected_times = {int(item) for item in closure.get("expected_v1_failure_proxy_open_times_ms", [])}
    if (
        closure.get("archive_count") != 300
        or closure.get("expected_normalized_hourly_rows") != base.EXPECTED_HOURS
        or closure.get("maximum_total_spot_proxy_hours") != 12
        or expected_times != EXPECTED_PROXY_TIMES
    ):
        raise base.CorpusReject("SPEC_REJECT:ERRATUM_CLOSURE")
    predecessor = value.get("supersedes_source_integrity_only", {})
    if (
        predecessor.get("path")
        != base.SPEC_PATH.relative_to(REPO_ROOT).as_posix()
        or predecessor.get("sha256") != base.SPEC_SHA256
    ):
        raise base.CorpusReject("SPEC_REJECT:PREDECESSOR")
    base.verify_spec(base.SPEC_PATH)
    return value


def parse_spot_csv(payload: bytes, *, month: str) -> SpotParse:
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise base.CorpusReject(f"DATA_REJECT:UTF8:spot:{month}") from error
    bars: list[base.HourBar] = []
    incomplete: list[dict[str, str | int]] = []
    seen: set[int] = set()
    for index, row in enumerate(rows):
        if len(row) != 12:
            raise base.CorpusReject(f"DATA_REJECT:ROW_WIDTH:spot:{month}:{index}")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
            int(row[8])
        except ValueError as error:
            raise base.CorpusReject(f"DATA_REJECT:INTEGER:spot:{month}:{index}") from error
        observed_month = datetime.fromtimestamp(
            open_ms / 1000, tz=timezone.utc
        ).strftime("%Y-%m")
        if open_ms % base.HOUR_MS or observed_month != month or open_ms in seen:
            raise base.CorpusReject(f"DATA_REJECT:HOUR_IDENTITY:spot:{month}:{index}")
        seen.add(open_ms)
        if close_ms != open_ms + base.HOUR_MS - 1:
            if not open_ms <= close_ms < open_ms + base.HOUR_MS - 1:
                raise base.CorpusReject(f"DATA_REJECT:HOUR_CLOCK:spot:{month}:{index}")
            incomplete.append(
                {
                    "month": month,
                    "row_index": index,
                    "open_time_ms": str(open_ms),
                    "close_time_ms": str(close_ms),
                    "duration_ms_inclusive": str(close_ms - open_ms + 1),
                }
            )
            continue
        open_value = base._decimal(row[1], signed=False, context=f"spot:open:{index}")
        high = base._decimal(row[2], signed=False, context=f"spot:high:{index}")
        low = base._decimal(row[3], signed=False, context=f"spot:low:{index}")
        close = base._decimal(row[4], signed=False, context=f"spot:close:{index}")
        if min(open_value, high, low, close) <= 0:
            raise base.CorpusReject(f"DATA_REJECT:NONPOSITIVE_PRICE:spot:{month}:{index}")
        if high < max(open_value, low, close) or low > min(open_value, high, close):
            raise base.CorpusReject(f"DATA_REJECT:OHLC:spot:{month}:{index}")
        bars.append(base.HourBar(open_ms, open_value, high, low, close))
    if bars != sorted(bars, key=lambda value: value.open_time_ms):
        raise base.CorpusReject(f"DATA_REJECT:HOUR_ORDER:spot:{month}")
    return SpotParse(bars, incomplete)


def download_one(item: tuple[str, str]) -> tuple[str, str, list[Any], dict[str, Any]]:
    dataset, month = item
    key_template, member_template = DATASETS[dataset]
    key = key_template.format(month=month)
    member = member_template.format(month=month)
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
    actual_sha = base.sha256(zip_raw)
    if actual_sha != expected_sha:
        raise base.CorpusReject(f"DATA_REJECT:ARCHIVE_SHA256:{dataset}:{month}")
    payload = base.unzip_one(zip_raw, member=member)
    incomplete_rows: list[dict[str, str | int]] = []
    if dataset == "usdm_funding_rate":
        parsed: list[Any] = base.parse_funding_csv(payload, month=month)
    elif dataset == "spot_klines_1h":
        spot = parse_spot_csv(payload, month=month)
        parsed = spot.bars
        incomplete_rows = spot.incomplete_rows
    else:
        parsed = base.parse_kline_csv(payload, month=month, dataset=dataset)
    first_time = (
        parsed[0].calc_time_ms
        if dataset == "usdm_funding_rate"
        else parsed[0].open_time_ms
    )
    last_time = (
        parsed[-1].calc_time_ms
        if dataset == "usdm_funding_rate"
        else parsed[-1].open_time_ms
    )
    evidence = {
        "dataset": dataset,
        "month": month,
        "zip_key": key,
        "checksum_key": checksum_key,
        "zip_bytes": len(zip_raw),
        "zip_sha256": actual_sha,
        "checksum_response_sha256": base.sha256(checksum_raw),
        "csv_bytes": len(payload),
        "csv_sha256": base.sha256(payload),
        "rows": len(parsed),
        "first_time_ms": str(first_time),
        "last_time_ms": str(last_time),
        "excluded_incomplete_rows": incomplete_rows,
    }
    return dataset, month, parsed, evidence


def normalized_csv(
    spot: dict[int, base.HourBar],
    index: dict[int, base.HourBar],
    perp: dict[int, base.HourBar],
    mark: dict[int, base.HourBar],
    funding: dict[int, base.FundingEvent],
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
            "funding_rate",
        ]
    )
    for timestamp in expected_timestamps:
        if timestamp in spot:
            spot_bar = spot[timestamp]
            source = "BINANCE_SPOT_ARCHIVE"
        else:
            spot_bar = index[timestamp]
            source = "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP"
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
                "" if timestamp not in funding else format(funding[timestamp].rate, "f"),
            ]
        )
    return output.getvalue().encode("ascii")


def is_execution_boundary(timestamp: int, expected_timestamps: list[int]) -> bool:
    moment = datetime.fromtimestamp(timestamp / 1000, tz=timezone.utc)
    quarter_reset = (
        moment.month in {1, 4, 7, 10}
        and moment.day == 1
        and moment.hour == 1
    )
    annual_terminal = moment.month == 12 and moment.day == 31 and moment.hour == 23
    return quarter_reset or annual_terminal or timestamp == expected_timestamps[-1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--erratum", type=Path, default=ERRATUM_PATH)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    erratum_path = args.erratum.resolve()
    if erratum_path != ERRATUM_PATH:
        raise base.CorpusReject(f"SPEC_REJECT:ERRATUM_PATH:{erratum_path}")
    verify_erratum(erratum_path)
    bundle_path = base.state_path(args.bundle)
    normalized_path = base.state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise base.CorpusReject("PATH_REJECT:DUPLICATE")

    items = [(dataset, month) for dataset in DATASETS for month in base.months()]
    downloaded: list[tuple[str, str, list[Any], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=base.DOWNLOAD_WORKERS) as executor:
        for result in executor.map(download_one, items):
            downloaded.append(result)
    ledger = [result[3] for result in sorted(downloaded, key=lambda x: (x[0], x[1]))]
    total_zip_bytes = sum(item["zip_bytes"] for item in ledger)
    if len(ledger) != 300 or total_zip_bytes > base.MAX_TOTAL_DOWNLOAD_BYTES:
        raise base.CorpusReject(
            f"DATA_REJECT:ARCHIVE_CLOSURE:{len(ledger)}:{total_zip_bytes}"
        )

    by_dataset: dict[str, dict[int, Any]] = {dataset: {} for dataset in DATASETS}
    for dataset, month, rows, _ in downloaded:
        for row in rows:
            timestamp = (
                row.calc_time_ms if dataset == "usdm_funding_rate" else row.open_time_ms
            )
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
            raise base.CorpusReject(f"DATA_REJECT:HOURLY_LATTICE:{dataset}")
    spot_missing = expected_set - set(by_dataset["spot_klines_1h"])
    spot_extra = set(by_dataset["spot_klines_1h"]) - expected_set
    if spot_missing != EXPECTED_PROXY_TIMES or spot_extra:
        raise base.CorpusReject(
            f"DATA_REJECT:SPOT_GAP_IDENTITY:{sorted(spot_missing)}:{sorted(spot_extra)}"
        )
    incomplete = [
        row
        for archive in ledger
        if archive["dataset"] == "spot_klines_1h"
        for row in archive["excluded_incomplete_rows"]
    ]
    if len(incomplete) != 1 or int(incomplete[0]["open_time_ms"]) != min(EXPECTED_PROXY_TIMES):
        raise base.CorpusReject(f"DATA_REJECT:SPOT_INCOMPLETE_IDENTITY:{incomplete}")
    if any(is_execution_boundary(timestamp, expected_timestamps) for timestamp in spot_missing):
        raise base.CorpusReject("DATA_REJECT:SPOT_PROXY_AT_EXECUTION_BOUNDARY")
    expected_funding = set(expected_timestamps[::8])
    if set(by_dataset["usdm_funding_rate"]) != expected_funding:
        raise base.CorpusReject("DATA_REJECT:FUNDING_LATTICE")

    normalized_raw = normalized_csv(
        by_dataset["spot_klines_1h"],
        by_dataset["usdm_index_price_klines_1h"],
        by_dataset["usdm_contract_klines_1h"],
        by_dataset["usdm_mark_price_klines_1h"],
        by_dataset["usdm_funding_rate"],
        expected_timestamps,
    )
    normalized_gzip = base.deterministic_gzip(normalized_raw)
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_CORPUS_V2",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_PRE_2025_CORPUS_WITH_EXACT_BOUNDED_SPOT_INDEX_PROXY_NO_STRATEGY_OUTCOME",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_integrity_erratum": {
            "path": erratum_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": base.sha256(erratum_path),
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
            "total_zip_bytes": total_zip_bytes,
            "spot_native_hours": base.EXPECTED_HOURS - len(spot_missing),
            "spot_proxy_hours": len(spot_missing),
            "spot_proxy_open_times_ms": [str(value) for value in sorted(spot_missing)],
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
                "funding_rate",
            ],
        },
        "archive_ledger": ledger,
        "integrity": {
            "every_archive_matches_publisher_checksum": True,
            "every_archive_has_one_exact_expected_member": True,
            "perpetual_mark_and_index_hourly_lattices_complete": True,
            "funding_events_exactly_eight_hourly": True,
            "spot_proxy_identity_matches_frozen_erratum": True,
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
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
