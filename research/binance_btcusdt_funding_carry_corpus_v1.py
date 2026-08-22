#!/usr/bin/env python3
"""Seal the frozen pre-2025 Binance BTCUSDT spot/perpetual/funding corpus."""

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
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-binance-usdm-delta-neutral-funding-carry-historical.v1.source-ledger-spec.json"
)
SPEC_SHA256 = "41745a4c173714534f4bdeb63ca594a0250c056143b356cc22b2344212dfad79"
BASE_URL = "https://data.binance.vision/"
FIRST_MONTH = "2020-01"
LAST_MONTH = "2024-12"
HOUR_MS = 3_600_000
EXPECTED_HOURS = 43_848
EXPECTED_FUNDING_EVENTS = 5_481
MAX_ZIP_BYTES = 8 * 1024 * 1024
MAX_EXPANDED_BYTES = 32 * 1024 * 1024
MAX_CHECKSUM_BYTES = 4096
MAX_TOTAL_DOWNLOAD_BYTES = 512 * 1024 * 1024
TIMEOUT_SECONDS = 30
DOWNLOAD_WORKERS = 8
CHECKSUM_TEXT = re.compile(r"^([0-9a-fA-F]{64})\s+[*]?([^\s]+)\s*$")
UNSIGNED_DECIMAL = re.compile(r"^(?:0|[1-9]\d*)(?:[.]\d+)?$")
SIGNED_DECIMAL = re.compile(r"^-?(?:0|[1-9]\d*)(?:[.]\d+)?$")
FUNDING_HEADER = ["calc_time", "funding_interval_hours", "last_funding_rate"]
DATASETS = {
    "spot_klines_1h": (
        "data/spot/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip",
        "BTCUSDT-1h-{month}.csv",
    ),
    "usdm_contract_klines_1h": (
        "data/futures/um/monthly/klines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip",
        "BTCUSDT-1h-{month}.csv",
    ),
    "usdm_mark_price_klines_1h": (
        "data/futures/um/monthly/markPriceKlines/BTCUSDT/1h/BTCUSDT-1h-{month}.zip",
        "BTCUSDT-1h-{month}.csv",
    ),
    "usdm_funding_rate": (
        "data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-{month}.zip",
        "BTCUSDT-fundingRate-{month}.csv",
    ),
}


class CorpusReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        raise CorpusReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


@dataclass(frozen=True)
class HourBar:
    open_time_ms: int
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal


@dataclass(frozen=True)
class FundingEvent:
    calc_time_ms: int
    interval_hours: int
    rate: Decimal


def sha256(raw_or_path: bytes | Path) -> str:
    raw = raw_or_path.read_bytes() if isinstance(raw_or_path, Path) else raw_or_path
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("ascii")


def months() -> list[str]:
    values: list[str] = []
    year, month = 2020, 1
    while (year, month) <= (2024, 12):
        values.append(f"{year:04d}-{month:02d}")
        month += 1
        if month == 13:
            year += 1
            month = 1
    return values


def _decimal(raw: str, *, signed: bool, context: str) -> Decimal:
    pattern = SIGNED_DECIMAL if signed else UNSIGNED_DECIMAL
    if pattern.fullmatch(raw) is None:
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    try:
        value = Decimal(raw)
    except InvalidOperation as error:
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}") from error
    if not value.is_finite():
        raise CorpusReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    return value


def verify_spec(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    if SPEC_SHA256 == "TO_BE_FROZEN" or sha256(raw) != SPEC_SHA256:
        raise CorpusReject(f"SPEC_REJECT:SHA256:{sha256(raw)}")
    value = json.loads(raw)
    if (
        value.get("document_type")
        != "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_SOURCE_LEDGER_SPEC_V1"
        or value.get("research_classification")
        != "PRE_OUTCOME_SOURCE_AND_ACCOUNTING_FREEZE"
        or value.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise CorpusReject("SPEC_REJECT:IDENTITY")
    contract = value.get("source_contract", {})
    if (
        contract.get("archive_count") != 240
        or contract.get("first_month") != FIRST_MONTH
        or contract.get("last_month") != LAST_MONTH
        or contract.get("credentials") != "DENY"
        or contract.get("paid_api") != "DENY"
    ):
        raise CorpusReject("SPEC_REJECT:SOURCE_CONTRACT")
    for binding in value.get("source_bindings", []):
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or sha256(bound) != binding["sha256"]:
            raise CorpusReject(f"SPEC_REJECT:BINDING:{binding.get('role')}")
    return value


def state_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not resolved.is_relative_to(state_root):
        raise CorpusReject(f"PATH_REJECT:{resolved}")
    if resolved.exists():
        raise CorpusReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def fetch_url(url: str, *, maximum_bytes: int) -> bytes:
    request = Request(
        url,
        method="GET",
        headers={"User-Agent": "AgoraResearchFundingCarryCorpus/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=TIMEOUT_SECONDS
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


def parse_checksum(raw: bytes, *, filename: str) -> str:
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as error:
        raise CorpusReject("SOURCE_REJECT:CHECKSUM_ASCII") from error
    match = CHECKSUM_TEXT.fullmatch(text)
    if match is None or match.group(2) != filename:
        raise CorpusReject(f"SOURCE_REJECT:CHECKSUM_FORMAT:{filename}")
    return match.group(1).lower()


def unzip_one(raw: bytes, *, member: str) -> bytes:
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            if names != [member]:
                raise CorpusReject(f"DATA_REJECT:ZIP_MEMBERS:{member}:{names}")
            info = archive.getinfo(member)
            if info.is_dir() or info.file_size > MAX_EXPANDED_BYTES:
                raise CorpusReject(f"DATA_REJECT:ZIP_EXPANSION:{member}")
            payload = archive.read(member)
    except CorpusReject:
        raise
    except (zipfile.BadZipFile, KeyError, OSError) as error:
        raise CorpusReject(f"DATA_REJECT:ZIP:{member}") from error
    if not payload or len(payload) > MAX_EXPANDED_BYTES:
        raise CorpusReject(f"DATA_REJECT:CSV_BYTES:{member}:{len(payload)}")
    return payload


def parse_kline_csv(payload: bytes, *, month: str, dataset: str) -> list[HourBar]:
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise CorpusReject(f"DATA_REJECT:UTF8:{dataset}:{month}") from error
    bars: list[HourBar] = []
    seen: set[int] = set()
    for index, row in enumerate(rows):
        if len(row) != 12:
            raise CorpusReject(f"DATA_REJECT:ROW_WIDTH:{dataset}:{month}:{index}")
        try:
            open_ms = int(row[0])
            close_ms = int(row[6])
            int(row[8])
        except ValueError as error:
            raise CorpusReject(f"DATA_REJECT:INTEGER:{dataset}:{month}:{index}") from error
        if open_ms % HOUR_MS or close_ms != open_ms + HOUR_MS - 1:
            raise CorpusReject(f"DATA_REJECT:HOUR_CLOCK:{dataset}:{month}:{index}")
        observed_month = datetime.fromtimestamp(
            open_ms / 1000, tz=timezone.utc
        ).strftime("%Y-%m")
        if observed_month != month or open_ms in seen:
            raise CorpusReject(f"DATA_REJECT:HOUR_IDENTITY:{dataset}:{month}:{index}")
        seen.add(open_ms)
        open_value = _decimal(row[1], signed=False, context=f"{dataset}:open:{index}")
        high = _decimal(row[2], signed=False, context=f"{dataset}:high:{index}")
        low = _decimal(row[3], signed=False, context=f"{dataset}:low:{index}")
        close = _decimal(row[4], signed=False, context=f"{dataset}:close:{index}")
        if min(open_value, high, low, close) <= 0:
            raise CorpusReject(f"DATA_REJECT:NONPOSITIVE_PRICE:{dataset}:{month}:{index}")
        if high < max(open_value, low, close) or low > min(open_value, high, close):
            raise CorpusReject(f"DATA_REJECT:OHLC:{dataset}:{month}:{index}")
        bars.append(HourBar(open_ms, open_value, high, low, close))
    if not bars or bars != sorted(bars, key=lambda value: value.open_time_ms):
        raise CorpusReject(f"DATA_REJECT:HOUR_ORDER:{dataset}:{month}")
    return bars


def parse_funding_csv(payload: bytes, *, month: str) -> list[FundingEvent]:
    try:
        rows = list(csv.reader(io.StringIO(payload.decode("utf-8"), newline="")))
    except UnicodeDecodeError as error:
        raise CorpusReject(f"DATA_REJECT:UTF8:funding:{month}") from error
    if not rows or rows[0] != FUNDING_HEADER:
        raise CorpusReject(f"DATA_REJECT:FUNDING_HEADER:{month}")
    events: list[FundingEvent] = []
    seen: set[int] = set()
    for index, row in enumerate(rows[1:], start=1):
        if len(row) != 3:
            raise CorpusReject(f"DATA_REJECT:FUNDING_WIDTH:{month}:{index}")
        try:
            calc_time = int(row[0])
            interval = int(row[1])
        except ValueError as error:
            raise CorpusReject(f"DATA_REJECT:FUNDING_INTEGER:{month}:{index}") from error
        observed_month = datetime.fromtimestamp(
            calc_time / 1000, tz=timezone.utc
        ).strftime("%Y-%m")
        if (
            observed_month != month
            or calc_time % (8 * HOUR_MS)
            or interval != 8
            or calc_time in seen
        ):
            raise CorpusReject(f"DATA_REJECT:FUNDING_CLOCK:{month}:{index}")
        seen.add(calc_time)
        events.append(
            FundingEvent(
                calc_time,
                interval,
                _decimal(row[2], signed=True, context=f"funding:rate:{index}"),
            )
        )
    if not events or events != sorted(events, key=lambda value: value.calc_time_ms):
        raise CorpusReject(f"DATA_REJECT:FUNDING_ORDER:{month}")
    return events


def download_one(item: tuple[str, str]) -> tuple[str, str, list[Any], dict[str, Any]]:
    dataset, month = item
    key_template, member_template = DATASETS[dataset]
    key = key_template.format(month=month)
    member = member_template.format(month=month)
    filename = key.rsplit("/", 1)[-1]
    checksum_key = f"{key}.CHECKSUM"
    checksum_raw = fetch_url(
        f"{BASE_URL}{quote(checksum_key, safe='/')}",
        maximum_bytes=MAX_CHECKSUM_BYTES,
    )
    expected_sha = parse_checksum(checksum_raw, filename=filename)
    zip_raw = fetch_url(
        f"{BASE_URL}{quote(key, safe='/')}", maximum_bytes=MAX_ZIP_BYTES
    )
    actual_sha = sha256(zip_raw)
    if actual_sha != expected_sha:
        raise CorpusReject(f"DATA_REJECT:ARCHIVE_SHA256:{dataset}:{month}")
    payload = unzip_one(zip_raw, member=member)
    parsed = (
        parse_funding_csv(payload, month=month)
        if dataset == "usdm_funding_rate"
        else parse_kline_csv(payload, month=month, dataset=dataset)
    )
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
        "checksum_response_sha256": sha256(checksum_raw),
        "csv_bytes": len(payload),
        "csv_sha256": sha256(payload),
        "rows": len(parsed),
        "first_time_ms": str(first_time),
        "last_time_ms": str(last_time),
    }
    return dataset, month, parsed, evidence


def deterministic_gzip(raw: bytes) -> bytes:
    target = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as stream:
        stream.write(raw)
    return target.getvalue()


def normalized_csv(
    spot: dict[int, HourBar],
    perp: dict[int, HourBar],
    mark: dict[int, HourBar],
    funding: dict[int, FundingEvent],
) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        [
            "open_time_ms",
            "spot_open",
            "spot_close",
            "perp_open",
            "perp_close",
            "mark_open",
            "mark_close",
            "funding_rate",
        ]
    )
    for timestamp in sorted(spot):
        writer.writerow(
            [
                str(timestamp),
                format(spot[timestamp].open, "f"),
                format(spot[timestamp].close, "f"),
                format(perp[timestamp].open, "f"),
                format(perp[timestamp].close, "f"),
                format(mark[timestamp].open, "f"),
                format(mark[timestamp].close, "f"),
                "" if timestamp not in funding else format(funding[timestamp].rate, "f"),
            ]
        )
    return output.getvalue().encode("ascii")


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=SPEC_PATH)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--normalized-gzip", required=True)
    args = parser.parse_args()
    spec_path = args.spec.resolve()
    if spec_path != SPEC_PATH:
        raise CorpusReject(f"SPEC_REJECT:PATH:{spec_path}")
    verify_spec(spec_path)
    bundle_path = state_path(args.bundle)
    normalized_path = state_path(args.normalized_gzip)
    if bundle_path == normalized_path:
        raise CorpusReject("PATH_REJECT:DUPLICATE")

    items = [(dataset, month) for dataset in DATASETS for month in months()]
    downloaded: list[tuple[str, str, list[Any], dict[str, Any]]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=DOWNLOAD_WORKERS) as executor:
        for result in executor.map(download_one, items):
            downloaded.append(result)
    ledger = [result[3] for result in sorted(downloaded, key=lambda x: (x[0], x[1]))]
    total_zip_bytes = sum(item["zip_bytes"] for item in ledger)
    if len(ledger) != 240 or total_zip_bytes > MAX_TOTAL_DOWNLOAD_BYTES:
        raise CorpusReject(f"DATA_REJECT:ARCHIVE_CLOSURE:{len(ledger)}:{total_zip_bytes}")

    by_dataset: dict[str, dict[int, Any]] = {dataset: {} for dataset in DATASETS}
    for dataset, month, rows, _ in downloaded:
        for row in rows:
            timestamp = (
                row.calc_time_ms if dataset == "usdm_funding_rate" else row.open_time_ms
            )
            if timestamp in by_dataset[dataset]:
                raise CorpusReject(f"DATA_REJECT:DUPLICATE_TIMESTAMP:{dataset}:{timestamp}")
            by_dataset[dataset][timestamp] = row

    start_ms = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    expected_timestamps = [start_ms + index * HOUR_MS for index in range(EXPECTED_HOURS)]
    expected_set = set(expected_timestamps)
    for dataset in (
        "spot_klines_1h",
        "usdm_contract_klines_1h",
        "usdm_mark_price_klines_1h",
    ):
        actual = set(by_dataset[dataset])
        if actual != expected_set:
            missing = sorted(expected_set - actual)[:3]
            extra = sorted(actual - expected_set)[:3]
            raise CorpusReject(f"DATA_REJECT:HOURLY_LATTICE:{dataset}:{missing}:{extra}")
    expected_funding = set(expected_timestamps[::8])
    actual_funding = set(by_dataset["usdm_funding_rate"])
    if len(expected_funding) != EXPECTED_FUNDING_EVENTS or actual_funding != expected_funding:
        missing = sorted(expected_funding - actual_funding)[:3]
        extra = sorted(actual_funding - expected_funding)[:3]
        raise CorpusReject(f"DATA_REJECT:FUNDING_LATTICE:{missing}:{extra}")

    normalized_raw = normalized_csv(
        by_dataset["spot_klines_1h"],
        by_dataset["usdm_contract_klines_1h"],
        by_dataset["usdm_mark_price_klines_1h"],
        by_dataset["usdm_funding_rate"],
    )
    normalized_gzip = deterministic_gzip(normalized_raw)
    bundle = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_CORPUS_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_CHECKSUM_VERIFIED_PRE_2025_CORPUS_NO_STRATEGY_OUTCOME",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "source_and_ledger_spec": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(spec_path),
        },
        "corpus": {
            "first_open_time_ms": str(expected_timestamps[0]),
            "last_open_time_ms": str(expected_timestamps[-1]),
            "hourly_rows": EXPECTED_HOURS,
            "funding_events": EXPECTED_FUNDING_EVENTS,
            "archive_count": len(ledger),
            "total_zip_bytes": total_zip_bytes,
            "normalized_gzip_path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "normalized_gzip_bytes": len(normalized_gzip),
            "normalized_gzip_sha256": sha256(normalized_gzip),
            "normalized_csv_bytes": len(normalized_raw),
            "normalized_csv_sha256": sha256(normalized_raw),
            "columns": [
                "open_time_ms",
                "spot_open",
                "spot_close",
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
            "spot_perpetual_and_mark_hourly_lattices_identical": True,
            "funding_events_exactly_eight_hourly": True,
            "duplicates": 0,
            "post_2024_observation_accessed": False,
            "strategy_outcome_computed": False,
        },
        "scope_note": "Private offline research corpus only. No return, PnL, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
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
                "archive_count": len(ledger),
                "hourly_rows": EXPECTED_HOURS,
                "funding_events": EXPECTED_FUNDING_EVENTS,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
