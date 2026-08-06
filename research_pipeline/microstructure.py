from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any


SCHEMA_VERSION = "OKX_MICROSTRUCTURE_FORWARD_BUNDLE_V1"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
ENDPOINT = "wss://ws.okx.com:8443/ws/v5/public"
INSTRUMENT = "BTC-USDT"
CHANNELS = ["trades", "books5"]
ACKNOWLEDGED_CHANNELS = ["books5", "trades"]
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
ARRIVAL_CHAIN_ALGORITHM = "SHA-256(previous_digest || raw_utf8_message)"
SMOKE_NOTE = (
    "A short smoke capture is diagnostic only and cannot enter canonical evidence."
)
METRIC_SEMANTICS = {
    "net_taker_quote_notional": (
        "buy_quote_notional minus sell_quote_notional; side is taker side"
    ),
    "book_imbalance": (
        "(top5_bid_quote_depth - top5_ask_quote_depth) / total_top5_quote_depth"
    ),
    "bid_replenishment_quote_proxy": (
        "sum of positive changes in total top5 bid quote depth; "
        "price-level shifts can confound it"
    ),
}

TOP_LEVEL_KEYS = {
    "schema_version",
    "status",
    "authorization",
    "canonical_evidence_eligible",
    "source",
    "capture",
    "integrity",
    "eligibility",
    "metric_semantics",
    "minutes",
    "seal",
}
SOURCE_KEYS = {
    "venue",
    "endpoint",
    "instrument",
    "channels",
    "mode",
    "historical_backfill",
    "raw_messages_persisted",
    "minute_aggregation_timezone",
}
CAPTURE_KEYS = {
    "requested_duration_seconds",
    "started_at",
    "ended_at",
    "acknowledged_channels",
    "trade_payloads",
    "books5_payloads",
    "listener_error",
}
ANOMALY_KEYS = {
    "malformed_record_count",
    "exchange_error_count",
    "crossed_book_count",
    "trade_timestamp_regression_count",
    "book_timestamp_regression_count",
    "trade_sequence_regression_count",
    "book_sequence_regression_count",
    "trade_id_non_increasing_count",
}
INTEGRITY_KEYS = {
    "status",
    "raw_message_count",
    "arrival_chain_algorithm",
    "arrival_chain_sha256",
    *ANOMALY_KEYS,
    "trade_source_record_counts",
}
ELIGIBILITY_KEYS = {
    "full_utc_day_1440_contiguous_minutes",
    "integrity_clean",
    "both_channels_acknowledged",
    "both_streams_observed",
    "note",
}
MINUTE_KEYS = {
    "minute",
    "trade_record_count",
    "match_count",
    "buy_base_quantity",
    "sell_base_quantity",
    "buy_quote_notional",
    "sell_quote_notional",
    "net_taker_quote_notional",
    "book_sample_count",
    "average_top5_bid_quote_depth",
    "average_top5_ask_quote_depth",
    "average_book_imbalance",
    "average_spread_bps",
    "bid_replenishment_quote_proxy",
    "mid_price_start",
    "mid_price_end",
}
SEAL_KEYS = {
    "algorithm",
    "payload_sha256",
    "canonicalization",
    "sealed_at",
}

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DECIMAL_PATTERN = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _string(value: Any, label: str, *, maximum: int = 2000) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValueError(f"{label} must be a non-empty string up to {maximum} characters")
    return value


def _integer(
    value: Any,
    label: str,
    *,
    minimum: int = 0,
    maximum: int | None = None,
) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{label} must be an integer")
    if value < minimum or (maximum is not None and value > maximum):
        suffix = f"..{maximum}" if maximum is not None else " or greater"
        raise ValueError(f"{label} must be {minimum}{suffix}")
    return value


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{label} must be a boolean")
    return value


def _utc_timestamp(value: Any, label: str, *, minute_aligned: bool = False) -> datetime:
    text = _string(value, label, maximum=64)
    if not text.endswith("Z"):
        raise ValueError(f"{label} must use the UTC Z suffix")
    try:
        parsed = datetime.fromisoformat(text[:-1] + "+00:00")
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO-8601 timestamp") from error
    if parsed.utcoffset() != timedelta(0):
        raise ValueError(f"{label} must be UTC")
    if minute_aligned and (parsed.second != 0 or parsed.microsecond != 0):
        raise ValueError(f"{label} must be aligned to an exact UTC minute")
    return parsed.astimezone(timezone.utc)


def _decimal_text(
    value: Any,
    label: str,
    *,
    minimum: Decimal | None = None,
    maximum: Decimal | None = None,
    nullable: bool = False,
) -> Decimal | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str) or DECIMAL_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must be a canonical plain decimal string")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{label} must be a finite decimal") from error
    canonical = "0" if parsed == 0 else format(parsed.normalize(), "f")
    if value != canonical:
        raise ValueError(f"{label} is not in canonical decimal form")
    if minimum is not None and parsed < minimum:
        raise ValueError(f"{label} must be at least {minimum}")
    if maximum is not None and parsed > maximum:
        raise ValueError(f"{label} must be at most {maximum}")
    return parsed


def canonical_payload_bytes(bundle: dict[str, Any]) -> bytes:
    payload = {key: value for key, value in bundle.items() if key != "seal"}
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def payload_sha256(bundle: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_payload_bytes(bundle)).hexdigest()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> None:
    raise ValueError(f"non-finite JSON number is forbidden: {value}")


def load_json_strict(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"bundle JSON could not be read: {type(error).__name__}") from error
    return _object(value, "microstructure bundle")


def validate_okx_microstructure_bundle(value: Any) -> dict[str, Any]:
    bundle = _object(value, "microstructure bundle")
    _exact_keys(bundle, TOP_LEVEL_KEYS, "microstructure bundle")
    if bundle["schema_version"] != SCHEMA_VERSION:
        raise ValueError("unsupported microstructure schema_version")
    if bundle["authorization"] != AUTHORIZATION:
        raise ValueError("microstructure authorization must remain research-only")
    if bundle["canonical_evidence_eligible"] is not False:
        raise ValueError(
            "canonical_evidence_eligible must remain false for offline smoke tooling"
        )

    source = _object(bundle["source"], "source")
    _exact_keys(source, SOURCE_KEYS, "source")
    expected_source = {
        "venue": "OKX",
        "endpoint": ENDPOINT,
        "instrument": INSTRUMENT,
        "channels": CHANNELS,
        "mode": "FORWARD_ONLY_BOUNDED_CAPTURE",
        "historical_backfill": False,
        "raw_messages_persisted": False,
        "minute_aggregation_timezone": "UTC",
    }
    if source != expected_source:
        raise ValueError("source must match the frozen public forward-only contract")

    capture = _object(bundle["capture"], "capture")
    _exact_keys(capture, CAPTURE_KEYS, "capture")
    _integer(
        capture["requested_duration_seconds"],
        "capture.requested_duration_seconds",
        minimum=5,
        maximum=86_400,
    )
    started_at = _utc_timestamp(capture["started_at"], "capture.started_at")
    ended_at = _utc_timestamp(capture["ended_at"], "capture.ended_at")
    if ended_at <= started_at:
        raise ValueError("capture.ended_at must be after capture.started_at")
    acknowledgements = capture["acknowledged_channels"]
    if acknowledgements != ACKNOWLEDGED_CHANNELS:
        raise ValueError("capture must acknowledge exactly books5 and trades")
    trade_payloads = _integer(
        capture["trade_payloads"], "capture.trade_payloads", minimum=1
    )
    book_payloads = _integer(
        capture["books5_payloads"], "capture.books5_payloads", minimum=1
    )
    if capture["listener_error"] is not None:
        _string(capture["listener_error"], "capture.listener_error")
        raise ValueError("listener_error makes the bundle ineligible for strict validation")

    integrity = _object(bundle["integrity"], "integrity")
    _exact_keys(integrity, INTEGRITY_KEYS, "integrity")
    raw_message_count = _integer(
        integrity["raw_message_count"], "integrity.raw_message_count", minimum=1
    )
    minimum_raw_messages = trade_payloads + book_payloads + len(ACKNOWLEDGED_CHANNELS)
    if raw_message_count < minimum_raw_messages:
        raise ValueError("integrity.raw_message_count is below the derived minimum")
    if integrity["arrival_chain_algorithm"] != ARRIVAL_CHAIN_ALGORITHM:
        raise ValueError("integrity arrival-chain algorithm changed")
    arrival_hash = integrity["arrival_chain_sha256"]
    if not isinstance(arrival_hash, str) or SHA256_PATTERN.fullmatch(arrival_hash) is None:
        raise ValueError("integrity.arrival_chain_sha256 must be lowercase SHA-256")
    anomaly_counts = {
        key: _integer(integrity[key], f"integrity.{key}") for key in ANOMALY_KEYS
    }
    integrity_clean = all(count == 0 for count in anomaly_counts.values())
    expected_integrity_status = "CLEAN" if integrity_clean else "ANOMALIES_PRESENT"
    if integrity["status"] != expected_integrity_status:
        raise ValueError("integrity.status is inconsistent with anomaly counters")
    if not integrity_clean:
        raise ValueError("integrity anomalies fail closed")
    source_counts = _object(
        integrity["trade_source_record_counts"],
        "integrity.trade_source_record_counts",
    )
    normalized_source_counts: dict[str, int] = {}
    for source_name, count in source_counts.items():
        _string(source_name, "integrity.trade_source_record_counts key", maximum=128)
        normalized_source_counts[source_name] = _integer(
            count,
            f"integrity.trade_source_record_counts[{source_name}]",
            minimum=1,
        )

    metric_semantics = _object(bundle["metric_semantics"], "metric_semantics")
    _exact_keys(metric_semantics, set(METRIC_SEMANTICS), "metric_semantics")
    if metric_semantics != METRIC_SEMANTICS:
        raise ValueError("metric semantics changed from the frozen tooling contract")

    minutes = bundle["minutes"]
    if not isinstance(minutes, list) or not 1 <= len(minutes) <= 1_440:
        raise ValueError("minutes must contain 1..1440 observations")
    minute_times: list[datetime] = []
    total_trade_records = 0
    for index, raw_minute in enumerate(minutes):
        minute = _object(raw_minute, f"minutes[{index}]")
        _exact_keys(minute, MINUTE_KEYS, f"minutes[{index}]")
        minute_at = _utc_timestamp(
            minute["minute"], f"minutes[{index}].minute", minute_aligned=True
        )
        if minute_times and minute_at != minute_times[-1] + timedelta(minutes=1):
            raise ValueError("minutes must be strictly ordered and contiguous")
        minute_times.append(minute_at)

        trade_records = _integer(
            minute["trade_record_count"],
            f"minutes[{index}].trade_record_count",
            minimum=1,
        )
        match_count = _integer(
            minute["match_count"], f"minutes[{index}].match_count", minimum=1
        )
        if match_count < trade_records:
            raise ValueError(f"minutes[{index}].match_count is below trade_record_count")
        total_trade_records += trade_records

        buy_base = _decimal_text(
            minute["buy_base_quantity"],
            f"minutes[{index}].buy_base_quantity",
            minimum=Decimal(0),
        )
        sell_base = _decimal_text(
            minute["sell_base_quantity"],
            f"minutes[{index}].sell_base_quantity",
            minimum=Decimal(0),
        )
        buy_quote = _decimal_text(
            minute["buy_quote_notional"],
            f"minutes[{index}].buy_quote_notional",
            minimum=Decimal(0),
        )
        sell_quote = _decimal_text(
            minute["sell_quote_notional"],
            f"minutes[{index}].sell_quote_notional",
            minimum=Decimal(0),
        )
        net_quote = _decimal_text(
            minute["net_taker_quote_notional"],
            f"minutes[{index}].net_taker_quote_notional",
        )
        if buy_base + sell_base <= 0 or buy_quote + sell_quote <= 0:
            raise ValueError(f"minutes[{index}] has trade records without positive volume")
        if net_quote != buy_quote - sell_quote:
            raise ValueError(f"minutes[{index}].net_taker_quote_notional is inconsistent")

        book_samples = _integer(
            minute["book_sample_count"],
            f"minutes[{index}].book_sample_count",
            minimum=1,
        )
        if book_samples < 1:
            raise ValueError(f"minutes[{index}] has a books5 stream gap")
        _decimal_text(
            minute["average_top5_bid_quote_depth"],
            f"minutes[{index}].average_top5_bid_quote_depth",
            minimum=Decimal(0),
        )
        _decimal_text(
            minute["average_top5_ask_quote_depth"],
            f"minutes[{index}].average_top5_ask_quote_depth",
            minimum=Decimal(0),
        )
        _decimal_text(
            minute["average_book_imbalance"],
            f"minutes[{index}].average_book_imbalance",
            minimum=Decimal(-1),
            maximum=Decimal(1),
        )
        spread = _decimal_text(
            minute["average_spread_bps"],
            f"minutes[{index}].average_spread_bps",
            minimum=Decimal(0),
        )
        if spread <= 0:
            raise ValueError(f"minutes[{index}].average_spread_bps must be positive")
        _decimal_text(
            minute["bid_replenishment_quote_proxy"],
            f"minutes[{index}].bid_replenishment_quote_proxy",
            minimum=Decimal(0),
        )
        mid_start = _decimal_text(
            minute["mid_price_start"],
            f"minutes[{index}].mid_price_start",
            minimum=Decimal(0),
        )
        mid_end = _decimal_text(
            minute["mid_price_end"],
            f"minutes[{index}].mid_price_end",
            minimum=Decimal(0),
        )
        if mid_start <= 0 or mid_end <= 0:
            raise ValueError(f"minutes[{index}] mid prices must be positive")

    first_minute = minute_times[0]
    last_minute = minute_times[-1]
    capture_start_minute = started_at.replace(second=0, microsecond=0)
    capture_end_minute = ended_at.replace(second=0, microsecond=0)
    if first_minute < capture_start_minute or last_minute > capture_end_minute:
        raise ValueError("minute observations fall outside the declared capture window")
    if sum(normalized_source_counts.values()) != total_trade_records:
        raise ValueError("trade source record counts do not match minute trade records")

    full_utc_day = (
        len(minute_times) == 1_440
        and first_minute == first_minute.replace(hour=0, minute=0)
        and last_minute == first_minute + timedelta(minutes=1_439)
    )
    eligibility = _object(bundle["eligibility"], "eligibility")
    _exact_keys(eligibility, ELIGIBILITY_KEYS, "eligibility")
    if _boolean(
        eligibility["full_utc_day_1440_contiguous_minutes"],
        "eligibility.full_utc_day_1440_contiguous_minutes",
    ) != full_utc_day:
        raise ValueError("full UTC day eligibility is inconsistent with minutes")
    if _boolean(eligibility["integrity_clean"], "eligibility.integrity_clean") is not True:
        raise ValueError("eligibility.integrity_clean must match clean integrity counters")
    if _boolean(
        eligibility["both_channels_acknowledged"],
        "eligibility.both_channels_acknowledged",
    ) is not True:
        raise ValueError("eligibility channel acknowledgement is inconsistent")
    if _boolean(
        eligibility["both_streams_observed"],
        "eligibility.both_streams_observed",
    ) is not True:
        raise ValueError("eligibility stream observation is inconsistent")
    if eligibility["note"] != SMOKE_NOTE:
        raise ValueError("eligibility note must preserve the smoke-only boundary")
    if bundle["status"] != "CAPTURE_COMPLETE_RESEARCH_ONLY":
        raise ValueError("only complete clean smoke bundles pass strict local validation")

    seal = _object(bundle["seal"], "seal")
    _exact_keys(seal, SEAL_KEYS, "seal")
    if seal["algorithm"] != "SHA-256":
        raise ValueError("seal.algorithm must be SHA-256")
    declared_hash = seal["payload_sha256"]
    if not isinstance(declared_hash, str) or SHA256_PATTERN.fullmatch(declared_hash) is None:
        raise ValueError("seal.payload_sha256 must be lowercase SHA-256")
    if seal["canonicalization"] != CANONICALIZATION:
        raise ValueError("seal canonicalization contract changed")
    sealed_at = _utc_timestamp(seal["sealed_at"], "seal.sealed_at")
    if sealed_at < ended_at:
        raise ValueError("seal.sealed_at must not precede capture.ended_at")
    actual_hash = payload_sha256(bundle)
    if declared_hash != actual_hash:
        raise ValueError("seal.payload_sha256 does not match the canonical payload")

    return {
        "status": "VALID_SMOKE_TOOLING_ONLY",
        "payload_sha256": actual_hash,
        "minute_count": len(minutes),
        "full_utc_day": full_utc_day,
        "canonical_evidence_eligible": False,
    }


def validate_okx_microstructure_bundle_file(path: Path) -> dict[str, Any]:
    return validate_okx_microstructure_bundle(load_json_strict(path))
