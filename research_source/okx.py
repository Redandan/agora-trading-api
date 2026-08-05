from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import json
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .contract import AUTHORIZATION, PRODUCER, canonical_bytes, canonical_sha256


OKX_URL = "https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1H&limit=100"
MAX_RESPONSE_BYTES = 1024 * 1024


class TemporarySourceError(RuntimeError):
    """A bounded retry may succeed before the frozen capture deadline."""


class SourceIntegrityError(ValueError):
    """The public response cannot satisfy the frozen evidence contract."""


def fetch_okx_rows(
    *,
    opener: Callable[..., Any] = urlopen,
    timeout_seconds: int = 20,
) -> list[Any]:
    request = Request(
        OKX_URL,
        headers={"Accept": "application/json", "User-Agent": "agora-okx-forward-source-v1"},
        method="GET",
    )
    try:
        with opener(request, timeout=timeout_seconds) as response:
            length = response.headers.get("Content-Length")
            if length is not None and int(length) > MAX_RESPONSE_BYTES:
                raise SourceIntegrityError("OKX response exceeds the fixed byte limit")
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise TemporarySourceError(f"OKX public endpoint unavailable: {type(error).__name__}") from error
    if len(body) > MAX_RESPONSE_BYTES:
        raise SourceIntegrityError("OKX response exceeds the fixed byte limit")
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceIntegrityError("OKX response is not valid UTF-8 JSON") from error
    if not isinstance(payload, dict) or payload.get("code") != "0":
        code = payload.get("code") if isinstance(payload, dict) else None
        raise TemporarySourceError(f"OKX API did not return success code: {code}")
    rows = payload.get("data")
    if not isinstance(rows, list):
        raise SourceIntegrityError("OKX response data must be an array")
    return rows


def _decimal_text(value: Any, field: str, *, positive: bool) -> str:
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, ValueError) as error:
        raise SourceIntegrityError(f"OKX {field} is not decimal") from error
    if not parsed.is_finite() or (parsed <= 0 if positive else parsed < 0):
        raise SourceIntegrityError(f"OKX {field} is outside the allowed range")
    normalized = format(parsed, "f")
    if "." in normalized:
        normalized = normalized.rstrip("0").rstrip(".")
    return normalized or "0"


def selected_complete_rows(rows: list[Any], day_text: str) -> list[list[str]]:
    try:
        day = date.fromisoformat(day_text)
    except ValueError as error:
        raise SourceIntegrityError("target day must be YYYY-MM-DD") from error
    start = datetime.combine(day, time.min, tzinfo=timezone.utc)
    start_ms = int(start.timestamp() * 1000)
    end_ms = int((start + timedelta(days=1)).timestamp() * 1000)
    selected: dict[int, list[str]] = {}
    for index, row in enumerate(rows):
        if not isinstance(row, list) or len(row) < 9:
            raise SourceIntegrityError(f"OKX row {index} is malformed")
        try:
            timestamp = int(str(row[0]))
        except ValueError as error:
            raise SourceIntegrityError(f"OKX row {index} timestamp is invalid") from error
        if timestamp < start_ms or timestamp >= end_ms:
            continue
        if timestamp in selected:
            raise SourceIntegrityError("OKX response contains a duplicate target-hour timestamp")
        normalized = [str(item) for item in row[:9]]
        if normalized[8] != "1":
            raise TemporarySourceError("OKX target day still contains an incomplete candle")
        selected[timestamp] = normalized
    expected = [start_ms + hour * 3_600_000 for hour in range(24)]
    if sorted(selected) != expected:
        raise TemporarySourceError("OKX target day does not contain the exact 24-hour grid")
    return [selected[timestamp] for timestamp in expected]


def build_day_bundle(request: dict[str, Any], rows: list[Any]) -> tuple[dict[str, Any], list[list[str]]]:
    selected = selected_complete_rows(rows, str(request["day"]))
    day = date.fromisoformat(str(request["day"]))
    start = datetime.combine(day, time.min, tzinfo=timezone.utc)
    bars: list[dict[str, str]] = []
    for index, row in enumerate(selected):
        open_price = _decimal_text(row[1], "open", positive=True)
        high_price = _decimal_text(row[2], "high", positive=True)
        low_price = _decimal_text(row[3], "low", positive=True)
        close_price = _decimal_text(row[4], "close", positive=True)
        volume = _decimal_text(row[5], "volume", positive=False)
        if Decimal(low_price) > min(Decimal(open_price), Decimal(close_price)):
            raise SourceIntegrityError("OKX candle low violates OHLC bounds")
        if Decimal(high_price) < max(Decimal(open_price), Decimal(close_price)):
            raise SourceIntegrityError("OKX candle high violates OHLC bounds")
        bar_start = start + timedelta(hours=index)
        bars.append(
            {
                "interval_start": bar_start.isoformat(timespec="seconds").replace("+00:00", "Z"),
                "interval_end": (bar_start + timedelta(hours=1)).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "open": open_price,
                "high": high_price,
                "low": low_price,
                "close": close_price,
                "volume": volume,
            }
        )
    rows_hash = canonical_sha256(selected)
    bundle = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": request["trigger_id"],
        "trigger_fingerprint": request["trigger_fingerprint"],
        "source": request["source"],
        "day": request["day"],
        "bars": bars,
        "source_provenance": {
            "producer": PRODUCER,
            "artifact_id": f"okx-btc-usdt-1h-{request['day']}-{rows_hash[:16]}",
            "sha256": rows_hash,
        },
        "authorization": AUTHORIZATION,
    }
    canonical_bytes(bundle)
    return bundle, selected


def probe_okx(*, opener: Callable[..., Any] = urlopen) -> dict[str, Any]:
    rows = fetch_okx_rows(opener=opener)
    if not rows:
        raise TemporarySourceError("OKX probe returned no candle rows")
    first = rows[0]
    if not isinstance(first, list) or len(first) < 9 or str(first[8]) not in {"0", "1"}:
        raise SourceIntegrityError("OKX probe response shape is incompatible")
    return {
        "status": "SOURCE_PROBE_OK",
        "producer": PRODUCER,
        "endpoint": OKX_URL.split("?")[0],
        "row_count": len(rows),
    }
