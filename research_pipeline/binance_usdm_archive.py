"""Fail-closed reader for official Binance USD-M daily metrics archives.

The module is deliberately side-effect free: callers provide the ZIP and its
CHECKSUM bytes, and receive an immutable normalized bundle.  It performs no
network access and writes no files.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
import csv
import hashlib
import io
import json
from pathlib import PurePosixPath
import re
from typing import Any
import zipfile


SYMBOL = "BTCUSDT"
MARKET = "BINANCE_USDM_PERPETUAL"
DATASET = "daily_metrics"
SELECTION_CUTOFF_DAY = date(2024, 12, 31)
EXPECTED_INTERVAL_MINUTES = 5
EXPECTED_ROWS_PER_DAY = 24 * 60 // EXPECTED_INTERVAL_MINUTES
MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MAX_ROWS = 1_000
EXPECTED_HEADER = (
    "create_time",
    "symbol",
    "sum_open_interest",
    "sum_open_interest_value",
    "count_toptrader_long_short_ratio",
    "sum_toptrader_long_short_ratio",
    "count_long_short_ratio",
    "sum_taker_long_short_vol_ratio",
)
ALL_FIELDS = "ALL_FIELDS"
REQUIRED_RATIO_FIELDS_BY_FEATURE_FAMILY = {
    "joint-price-open-interest-deleveraging-flush": frozenset(),
    "top-trader-versus-global-positioning-divergence": frozenset(
        {"sum_toptrader_long_short_ratio", "count_long_short_ratio"}
    ),
    "joint-perpetual-taker-flow-open-interest-confirmation": frozenset(
        {"sum_taker_long_short_vol_ratio"}
    ),
}
RATIO_FIELDS = frozenset(EXPECTED_HEADER[4:])

_ARCHIVE_NAME = re.compile(r"^BTCUSDT-metrics-(\d{4}-\d{2}-\d{2})\.zip$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DECIMAL_TEXT = re.compile(
    r"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?$"
)


class ArchiveReject(RuntimeError):
    def __init__(self, detail: str):
        super().__init__(detail)
        self.status = "DATA_REJECT"
        self.detail = detail


def canonical_document_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


@dataclass(frozen=True)
class ArchiveLimits:
    max_archive_bytes: int = MAX_ARCHIVE_BYTES
    max_uncompressed_bytes: int = MAX_UNCOMPRESSED_BYTES
    max_compression_ratio: int = MAX_COMPRESSION_RATIO
    max_rows: int = MAX_ROWS

    def validate(self) -> None:
        values = (
            self.max_archive_bytes,
            self.max_uncompressed_bytes,
            self.max_compression_ratio,
            self.max_rows,
        )
        if any(isinstance(value, bool) or not isinstance(value, int) or value <= 0 for value in values):
            raise ArchiveReject("archive limits must be positive integers")


@dataclass(frozen=True)
class MetricsObservation:
    timestamp: datetime
    symbol: str
    sum_open_interest: str
    sum_open_interest_value: str
    count_toptrader_long_short_ratio: str
    sum_toptrader_long_short_ratio: str
    count_long_short_ratio: str
    sum_taker_long_short_vol_ratio: str

    def decimal(self, field: str) -> Decimal:
        if field not in EXPECTED_HEADER[2:]:
            raise KeyError(field)
        return Decimal(getattr(self, field))

    def canonical(self) -> dict[str, str]:
        return {
            "count_long_short_ratio": self.count_long_short_ratio,
            "count_toptrader_long_short_ratio": self.count_toptrader_long_short_ratio,
            "create_time": self.timestamp.isoformat(timespec="seconds") + "Z",
            "sum_open_interest": self.sum_open_interest,
            "sum_open_interest_value": self.sum_open_interest_value,
            "sum_taker_long_short_vol_ratio": self.sum_taker_long_short_vol_ratio,
            "sum_toptrader_long_short_ratio": self.sum_toptrader_long_short_ratio,
            "symbol": self.symbol,
        }


@dataclass(frozen=True)
class DailyMetricsBundle:
    archive_name: str
    archive_sha256: str
    checksum_sidecar_sha256: str
    day: date
    normalized_payload_sha256: str
    observations: tuple[MetricsObservation, ...]
    feature_family: str = ALL_FIELDS

    def evidence(self) -> dict[str, Any]:
        return {
            "archive_name": self.archive_name,
            "archive_sha256": self.archive_sha256,
            "checksum_sidecar_sha256": self.checksum_sidecar_sha256,
            "complete_utc_day": self.day.isoformat(),
            "dataset": DATASET,
            "feature_family": self.feature_family,
            "instrument": SYMBOL,
            "market": MARKET,
            "normalized_payload_sha256": self.normalized_payload_sha256,
            "rows": len(self.observations),
        }


def _archive_day(archive_name: str) -> date:
    match = _ARCHIVE_NAME.fullmatch(archive_name)
    if match is None:
        raise ArchiveReject("archive name must bind BTCUSDT USD-M daily metrics")
    try:
        day = date.fromisoformat(match.group(1))
    except ValueError as error:
        raise ArchiveReject("archive name contains an invalid UTC day") from error
    if day > SELECTION_CUTOFF_DAY:
        raise ArchiveReject("archive day crosses the inclusive 2024-12-31 cutoff")
    return day


def verify_official_checksum(
    archive_name: str, archive_bytes: bytes, checksum_bytes: bytes
) -> str:
    if len(checksum_bytes) > 512:
        raise ArchiveReject("CHECKSUM sidecar is unexpectedly large")
    try:
        text = checksum_bytes.decode("ascii").strip()
    except UnicodeDecodeError as error:
        raise ArchiveReject("CHECKSUM sidecar must be ASCII") from error
    parts = text.split()
    if len(parts) != 2:
        raise ArchiveReject("CHECKSUM sidecar must contain one digest and filename")
    expected, named_archive = parts
    named_archive = named_archive.removeprefix("*")
    if _SHA256.fullmatch(expected) is None or named_archive != archive_name:
        raise ArchiveReject("CHECKSUM sidecar does not bind the requested archive")
    observed = sha256_bytes(archive_bytes)
    if observed != expected:
        raise ArchiveReject("archive SHA-256 does not match its official CHECKSUM")
    return observed


def _safe_csv_member(
    archive_name: str, archive_bytes: bytes, limits: ArchiveLimits
) -> tuple[zipfile.ZipFile, zipfile.ZipInfo]:
    if len(archive_bytes) > limits.max_archive_bytes:
        raise ArchiveReject("archive exceeds the compressed-byte limit")
    try:
        archive = zipfile.ZipFile(io.BytesIO(archive_bytes))
    except (zipfile.BadZipFile, OSError) as error:
        raise ArchiveReject("archive is not a valid ZIP") from error
    members = archive.infolist()
    if len(members) != 1:
        archive.close()
        raise ArchiveReject("archive must contain exactly one CSV member")
    member = members[0]
    path = PurePosixPath(member.filename.replace("\\", "/"))
    mode = (member.external_attr >> 16) & 0o170000
    if (
        member.is_dir()
        or member.flag_bits & 0x1
        or path.is_absolute()
        or len(path.parts) != 1
        or ".." in path.parts
        or mode == 0o120000
        or path.suffix.lower() != ".csv"
    ):
        archive.close()
        raise ArchiveReject("ZIP member is linked, encrypted, nested, or unsafe")
    expected_csv = archive_name.removesuffix(".zip") + ".csv"
    if path.name != expected_csv:
        archive.close()
        raise ArchiveReject("ZIP member name does not bind the archive")
    if member.file_size > limits.max_uncompressed_bytes:
        archive.close()
        raise ArchiveReject("ZIP member exceeds the uncompressed-byte limit")
    denominator = max(member.compress_size, 1)
    if member.file_size > denominator * limits.max_compression_ratio:
        archive.close()
        raise ArchiveReject("ZIP member exceeds the compression-ratio limit")
    return archive, member


def _decimal_text(value: str, label: str, *, allow_zero: bool) -> str:
    if _DECIMAL_TEXT.fullmatch(value) is None:
        raise ArchiveReject(f"{label} must use exact unsigned decimal text")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ArchiveReject(f"{label} is not a finite decimal") from error
    if not parsed.is_finite() or parsed < 0 or (parsed == 0 and not allow_zero):
        boundary = "nonnegative" if allow_zero else "positive"
        raise ArchiveReject(f"{label} must be {boundary}")
    return value


def _parse_row(
    raw_line: bytes,
    row_number: int,
    required_ratio_fields: frozenset[str],
) -> tuple[MetricsObservation, bytes]:
    try:
        text = raw_line.decode("utf-8")
        values = next(csv.reader([text], strict=True))
    except (UnicodeDecodeError, csv.Error, StopIteration) as error:
        raise ArchiveReject(f"metrics row {row_number} is not strict UTF-8 CSV") from error
    if len(values) != len(EXPECTED_HEADER):
        raise ArchiveReject(f"metrics row {row_number} has the wrong column count")
    try:
        timestamp = datetime.strptime(values[0], "%Y-%m-%d %H:%M:%S")
    except ValueError as error:
        raise ArchiveReject(f"metrics row {row_number} has an invalid UTC timestamp") from error
    if timestamp.second != 0 or timestamp.minute % EXPECTED_INTERVAL_MINUTES != 0:
        raise ArchiveReject(f"metrics row {row_number} is not on a five-minute boundary")
    if values[1] != SYMBOL:
        raise ArchiveReject(f"metrics row {row_number} is not BTCUSDT")
    decimal_values: list[str] = []
    for index, value in enumerate(values[2:], start=2):
        field = EXPECTED_HEADER[index]
        if value == "" and field in RATIO_FIELDS - required_ratio_fields:
            decimal_values.append(value)
            continue
        decimal_values.append(
            _decimal_text(value, field, allow_zero=field in RATIO_FIELDS)
        )
    return (
        MetricsObservation(timestamp, values[1], *decimal_values),
        raw_line,
    )


def load_daily_metrics_archive(
    archive_name: str,
    archive_bytes: bytes,
    checksum_bytes: bytes,
    *,
    limits: ArchiveLimits | None = None,
    feature_family: str = ALL_FIELDS,
) -> DailyMetricsBundle:
    """Verify and normalize exactly one complete pre-2025 BTCUSDT UTC day."""

    active_limits = limits or ArchiveLimits()
    active_limits.validate()
    if feature_family == ALL_FIELDS:
        required_ratio_fields = RATIO_FIELDS
    else:
        required_ratio_fields = REQUIRED_RATIO_FIELDS_BY_FEATURE_FAMILY.get(
            feature_family
        )
        if required_ratio_fields is None:
            raise ArchiveReject("feature family is not supported by the archive contract")
    day = _archive_day(archive_name)
    archive_sha = verify_official_checksum(archive_name, archive_bytes, checksum_bytes)
    archive, member = _safe_csv_member(archive_name, archive_bytes, active_limits)
    try:
        with archive.open(member, "r") as source:
            payload = source.read(active_limits.max_uncompressed_bytes + 1)
    except (RuntimeError, OSError, zipfile.BadZipFile) as error:
        raise ArchiveReject("ZIP member could not be read safely") from error
    finally:
        archive.close()
    if len(payload) > active_limits.max_uncompressed_bytes:
        raise ArchiveReject("ZIP member exceeds the bounded read limit")
    lines = payload.splitlines()
    if not lines:
        raise ArchiveReject("metrics CSV is empty")
    try:
        header = tuple(next(csv.reader([lines[0].decode("utf-8")], strict=True)))
    except (UnicodeDecodeError, csv.Error, StopIteration) as error:
        raise ArchiveReject("metrics header is invalid") from error
    if header != EXPECTED_HEADER:
        raise ArchiveReject("metrics header does not match the frozen contract")
    if len(lines) - 1 > active_limits.max_rows:
        raise ArchiveReject("metrics CSV exceeds the row limit")

    by_timestamp: dict[datetime, tuple[MetricsObservation, bytes]] = {}
    for row_number, raw_line in enumerate(lines[1:], start=2):
        if not raw_line:
            raise ArchiveReject(f"metrics row {row_number} is blank")
        observation, exact_row = _parse_row(
            raw_line, row_number, required_ratio_fields
        )
        prior = by_timestamp.get(observation.timestamp)
        if prior is not None:
            if prior[1] != exact_row:
                raise ArchiveReject("conflicting duplicate metrics timestamp")
            continue
        by_timestamp[observation.timestamp] = (observation, exact_row)

    expected = [
        datetime.combine(day, datetime.min.time())
        + timedelta(minutes=EXPECTED_INTERVAL_MINUTES * index)
        for index in range(EXPECTED_ROWS_PER_DAY)
    ]
    observed = sorted(by_timestamp)
    if observed != expected:
        raise ArchiveReject("metrics archive is not one complete gap-free UTC day")
    observations = tuple(by_timestamp[timestamp][0] for timestamp in observed)
    normalized_bytes = canonical_document_bytes(
        {
            "complete_utc_day": day.isoformat(),
            "dataset": DATASET,
            "feature_family": feature_family,
            "instrument": SYMBOL,
            "market": MARKET,
            "observations": [item.canonical() for item in observations],
            "schema_version": "1",
        }
    )
    return DailyMetricsBundle(
        archive_name=archive_name,
        archive_sha256=archive_sha,
        checksum_sidecar_sha256=sha256_bytes(checksum_bytes),
        day=day,
        normalized_payload_sha256=sha256_bytes(normalized_bytes),
        observations=observations,
        feature_family=feature_family,
    )
