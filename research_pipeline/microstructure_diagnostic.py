from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path
import re
from statistics import median
from typing import Any, Iterable, Sequence


DAY_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_FORWARD_DAY_V3"
CONTRACT_ID = "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DAY_BUNDLE_TYPE = "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY"
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
TIER_KEYS = (
    "MIDLINE_RATIO_1_5_ONLY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT",
)
HORIZONS = (5, 15, 60, 240, 1440)
MIDLINE_RATIO_THRESHOLD = Decimal("1.50")
COOLDOWN_MINUTES = 60
MINIMUM_EVENTS = 30
MINIMUM_HALF_EVENTS = 10
MINIMUM_MATCH_COVERAGE = Decimal("80.00")
EXPECTED_DAYS = 14
MINUTES_PER_DAY = 1440

DAY_KEYS = {
    "schema_version",
    "bundle_type",
    "authorization",
    "source",
    "day",
    "capture",
    "integrity",
    "minutes",
    "seal",
}
SOURCE_KEYS = {
    "venue",
    "instrument",
    "channels",
    "mode",
    "historical_backfill",
    "raw_messages_persisted",
    "aggregation_timezone",
    "midline_formula",
    "midline_reference",
    "unreferenced_trade_disposition",
}
CAPTURE_KEYS = {"started_at", "ended_at", "acknowledged_channels"}
INTEGRITY_KEYS = {
    "status",
    "anomaly_count",
    "raw_message_count",
    "arrival_chain_sha256",
    "midline_unreferenced_trade_count",
    "crossed_book_count",
}
MINUTE_KEYS = {
    "minute",
    "trade_record_count",
    "match_count",
    "midline_reference_count",
    "buy_quote_notional",
    "sell_quote_notional",
    "total_quote_notional",
    "net_taker_quote_notional",
    "above_mid_buy_quote_notional",
    "below_mid_sell_quote_notional",
    "midline_other_quote_notional",
    "trade_open_price",
    "trade_high_price",
    "trade_low_price",
    "trade_close_price",
    "trade_vwap_price",
    "first_trade_at",
    "last_trade_at",
    "book_sample_count",
    "average_top5_bid_quote_depth",
    "average_top5_ask_quote_depth",
    "average_book_imbalance",
    "average_spread_bps",
    "bid_replenishment_quote_proxy",
    "mid_price_start",
    "mid_price_high",
    "mid_price_low",
    "mid_price_end",
    "first_book_at",
    "last_book_at",
}
SEAL_KEYS = {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class MinuteRecord:
    minute: datetime
    total_quote_notional: Decimal
    net_taker_quote_notional: Decimal
    above_mid_buy_quote_notional: Decimal
    below_mid_sell_quote_notional: Decimal
    average_book_imbalance: Decimal
    bid_replenishment_quote_proxy: Decimal
    trade_open_price: Decimal
    trade_high_price: Decimal
    trade_low_price: Decimal
    trade_close_price: Decimal


@dataclass(frozen=True)
class ValidatedDay:
    day: date
    records: tuple[MinuteRecord, ...]
    payload_sha256: str


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _integer(value: Any, label: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"{label} must be an integer at least {minimum}")
    return value


def _decimal(
    value: Any,
    label: str,
    *,
    minimum: Decimal | None = None,
    maximum: Decimal | None = None,
) -> Decimal:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a non-empty decimal string")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{label} must be a finite decimal string") from error
    if not parsed.is_finite():
        raise ValueError(f"{label} must be finite")
    if minimum is not None and parsed < minimum:
        raise ValueError(f"{label} must be at least {minimum}")
    if maximum is not None and parsed > maximum:
        raise ValueError(f"{label} must be at most {maximum}")
    return parsed


def _timestamp(value: Any, label: str, *, minute_aligned: bool = False) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp ending in Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp") from error
    if minute_aligned and (parsed.second != 0 or parsed.microsecond != 0):
        raise ValueError(f"{label} must be aligned to a complete UTC minute")
    return parsed.astimezone(timezone.utc)


def _date(value: Any, label: str) -> date:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be an ISO date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO date") from error
    if value != parsed.isoformat():
        raise ValueError(f"{label} must use canonical YYYY-MM-DD form")
    return parsed


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
        raise ValueError(f"could not read strict JSON {path}: {type(error).__name__}") from error
    return _object(value, str(path))


def canonical_payload_bytes(value: dict[str, Any]) -> bytes:
    payload = {key: item for key, item in value.items() if key != "seal"}
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def payload_sha256(value: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_payload_bytes(value)).hexdigest()


def _validate_seal(bundle: dict[str, Any], ended_at: datetime) -> str:
    seal = _object(bundle["seal"], "seal")
    _exact_keys(seal, SEAL_KEYS, "seal")
    if seal["algorithm"] != "SHA-256":
        raise ValueError("seal.algorithm must be SHA-256")
    declared = seal["payload_sha256"]
    if not isinstance(declared, str) or SHA256_PATTERN.fullmatch(declared) is None:
        raise ValueError("seal.payload_sha256 must be lowercase SHA-256")
    if seal["canonicalization"] != CANONICALIZATION:
        raise ValueError("seal canonicalization contract changed")
    if _timestamp(seal["sealed_at"], "seal.sealed_at") < ended_at:
        raise ValueError("seal.sealed_at must not precede capture.ended_at")
    actual = payload_sha256(bundle)
    if declared != actual:
        raise ValueError("seal.payload_sha256 does not match the canonical payload")
    return actual


def _validate_minute(value: Any, expected_at: datetime, index: int) -> MinuteRecord:
    label = f"minutes[{index}]"
    minute = _object(value, label)
    _exact_keys(minute, MINUTE_KEYS, label)
    minute_at = _timestamp(minute["minute"], f"{label}.minute", minute_aligned=True)
    if minute_at != expected_at:
        raise ValueError(f"{label}.minute must be the exact contiguous UTC minute")

    trade_record_count = _integer(
        minute["trade_record_count"], f"{label}.trade_record_count", minimum=1
    )
    match_count = _integer(minute["match_count"], f"{label}.match_count", minimum=1)
    if match_count < trade_record_count:
        raise ValueError(f"{label}.match_count must be at least trade_record_count")
    midline_reference_count = _integer(
        minute["midline_reference_count"],
        f"{label}.midline_reference_count",
        minimum=1,
    )
    if midline_reference_count != trade_record_count:
        raise ValueError(
            f"{label}.midline_reference_count must equal trade_record_count"
        )

    buy_quote = _decimal(
        minute["buy_quote_notional"], f"{label}.buy_quote_notional", minimum=Decimal(0)
    )
    sell_quote = _decimal(
        minute["sell_quote_notional"], f"{label}.sell_quote_notional", minimum=Decimal(0)
    )
    total_quote = _decimal(
        minute["total_quote_notional"],
        f"{label}.total_quote_notional",
        minimum=Decimal(0),
    )
    net_quote = _decimal(
        minute["net_taker_quote_notional"], f"{label}.net_taker_quote_notional"
    )
    above_mid_buy = _decimal(
        minute["above_mid_buy_quote_notional"],
        f"{label}.above_mid_buy_quote_notional",
        minimum=Decimal(0),
    )
    below_mid_sell = _decimal(
        minute["below_mid_sell_quote_notional"],
        f"{label}.below_mid_sell_quote_notional",
        minimum=Decimal(0),
    )
    midline_other = _decimal(
        minute["midline_other_quote_notional"],
        f"{label}.midline_other_quote_notional",
        minimum=Decimal(0),
    )
    if total_quote <= 0 or total_quote != buy_quote + sell_quote:
        raise ValueError(f"{label}.total_quote_notional must equal positive buy plus sell")
    if net_quote != buy_quote - sell_quote:
        raise ValueError(f"{label}.net_taker_quote_notional must equal buy minus sell")
    if total_quote != above_mid_buy + below_mid_sell + midline_other:
        raise ValueError(
            f"{label} midline notional buckets must reconcile to total_quote_notional"
        )

    trade_open = _decimal(
        minute["trade_open_price"], f"{label}.trade_open_price", minimum=Decimal(0)
    )
    trade_high = _decimal(
        minute["trade_high_price"], f"{label}.trade_high_price", minimum=Decimal(0)
    )
    trade_low = _decimal(
        minute["trade_low_price"], f"{label}.trade_low_price", minimum=Decimal(0)
    )
    trade_close = _decimal(
        minute["trade_close_price"], f"{label}.trade_close_price", minimum=Decimal(0)
    )
    trade_vwap = _decimal(
        minute["trade_vwap_price"], f"{label}.trade_vwap_price", minimum=Decimal(0)
    )
    if min(trade_open, trade_high, trade_low, trade_close, trade_vwap) <= 0:
        raise ValueError(f"{label} trade prices must be positive")
    if trade_high < max(trade_open, trade_close, trade_vwap) or trade_low > min(
        trade_open, trade_close, trade_vwap
    ):
        raise ValueError(f"{label} trade OHLC/VWAP bounds are inconsistent")
    first_trade = _timestamp(minute["first_trade_at"], f"{label}.first_trade_at")
    last_trade = _timestamp(minute["last_trade_at"], f"{label}.last_trade_at")
    if not minute_at <= first_trade <= last_trade < minute_at + timedelta(minutes=1):
        raise ValueError(f"{label} trade timestamps must fall inside their minute")

    _integer(minute["book_sample_count"], f"{label}.book_sample_count", minimum=1)
    bid_depth = _decimal(
        minute["average_top5_bid_quote_depth"],
        f"{label}.average_top5_bid_quote_depth",
        minimum=Decimal(0),
    )
    ask_depth = _decimal(
        minute["average_top5_ask_quote_depth"],
        f"{label}.average_top5_ask_quote_depth",
        minimum=Decimal(0),
    )
    if bid_depth + ask_depth <= 0:
        raise ValueError(f"{label} average book depth must be positive")
    imbalance = _decimal(
        minute["average_book_imbalance"],
        f"{label}.average_book_imbalance",
        minimum=Decimal(-1),
        maximum=Decimal(1),
    )
    _decimal(
        minute["average_spread_bps"],
        f"{label}.average_spread_bps",
        minimum=Decimal(0),
    )
    replenishment = _decimal(
        minute["bid_replenishment_quote_proxy"],
        f"{label}.bid_replenishment_quote_proxy",
        minimum=Decimal(0),
    )
    mid_start = _decimal(
        minute["mid_price_start"], f"{label}.mid_price_start", minimum=Decimal(0)
    )
    mid_high = _decimal(
        minute["mid_price_high"], f"{label}.mid_price_high", minimum=Decimal(0)
    )
    mid_low = _decimal(
        minute["mid_price_low"], f"{label}.mid_price_low", minimum=Decimal(0)
    )
    mid_end = _decimal(
        minute["mid_price_end"], f"{label}.mid_price_end", minimum=Decimal(0)
    )
    if min(mid_start, mid_high, mid_low, mid_end) <= 0:
        raise ValueError(f"{label} mid prices must be positive")
    if mid_high < max(mid_start, mid_end) or mid_low > min(mid_start, mid_end):
        raise ValueError(f"{label} mid OHLC bounds are inconsistent")
    first_book = _timestamp(minute["first_book_at"], f"{label}.first_book_at")
    last_book = _timestamp(minute["last_book_at"], f"{label}.last_book_at")
    if not minute_at <= first_book <= last_book < minute_at + timedelta(minutes=1):
        raise ValueError(f"{label} book timestamps must fall inside their minute")

    return MinuteRecord(
        minute=minute_at,
        total_quote_notional=total_quote,
        net_taker_quote_notional=net_quote,
        above_mid_buy_quote_notional=above_mid_buy,
        below_mid_sell_quote_notional=below_mid_sell,
        average_book_imbalance=imbalance,
        bid_replenishment_quote_proxy=replenishment,
        trade_open_price=trade_open,
        trade_high_price=trade_high,
        trade_low_price=trade_low,
        trade_close_price=trade_close,
    )


def validate_day_bundle(value: Any) -> ValidatedDay:
    bundle = _object(value, "forward day bundle")
    _exact_keys(bundle, DAY_KEYS, "forward day bundle")
    if bundle["schema_version"] != DAY_SCHEMA_VERSION:
        raise ValueError("unsupported forward day schema_version")
    if bundle["bundle_type"] != DAY_BUNDLE_TYPE:
        raise ValueError("bundle_type must remain research-only")
    if bundle["authorization"] != AUTHORIZATION:
        raise ValueError("authorization must remain research-only")

    source = _object(bundle["source"], "source")
    _exact_keys(source, SOURCE_KEYS, "source")
    if source != {
        "venue": "OKX",
        "instrument": "BTC-USDT",
        "channels": ["trades", "books5"],
        "mode": "FORWARD_ONLY",
        "historical_backfill": False,
        "raw_messages_persisted": False,
        "aggregation_timezone": "UTC",
        "midline_formula": "BEST_BID_1_PLUS_BEST_ASK_1_DIVIDED_BY_2",
        "midline_reference": "LATEST_BOOKS5_AT_OR_BEFORE_TRADE",
        "unreferenced_trade_disposition": "INTEGRITY_ANOMALY",
    }:
        raise ValueError("source must match the frozen public forward-only contract")

    bundle_day = _date(bundle["day"], "day")
    day_start = datetime.combine(bundle_day, datetime.min.time(), tzinfo=timezone.utc)
    capture = _object(bundle["capture"], "capture")
    _exact_keys(capture, CAPTURE_KEYS, "capture")
    started_at = _timestamp(capture["started_at"], "capture.started_at")
    ended_at = _timestamp(capture["ended_at"], "capture.ended_at")
    if capture["acknowledged_channels"] != ["books5", "trades"]:
        raise ValueError("capture must acknowledge exactly books5 and trades")
    if started_at > day_start or ended_at < day_start + timedelta(days=1):
        raise ValueError("capture window must cover the complete declared UTC day")

    integrity = _object(bundle["integrity"], "integrity")
    _exact_keys(integrity, INTEGRITY_KEYS, "integrity")
    anomaly_count = _integer(
        integrity["anomaly_count"], "integrity.anomaly_count", minimum=0
    )
    if integrity["status"] != "CLEAN" or anomaly_count != 0:
        raise ValueError("integrity must be CLEAN with zero anomalies")
    unreferenced_count = _integer(
        integrity["midline_unreferenced_trade_count"],
        "integrity.midline_unreferenced_trade_count",
        minimum=0,
    )
    crossed_book_count = _integer(
        integrity["crossed_book_count"],
        "integrity.crossed_book_count",
        minimum=0,
    )
    if unreferenced_count != 0 or crossed_book_count != 0:
        raise ValueError("integrity must have zero unreferenced trades and crossed books")
    _integer(integrity["raw_message_count"], "integrity.raw_message_count", minimum=1)
    arrival_hash = integrity["arrival_chain_sha256"]
    if not isinstance(arrival_hash, str) or SHA256_PATTERN.fullmatch(arrival_hash) is None:
        raise ValueError("integrity.arrival_chain_sha256 must be lowercase SHA-256")

    raw_minutes = bundle["minutes"]
    if not isinstance(raw_minutes, list) or len(raw_minutes) != MINUTES_PER_DAY:
        raise ValueError("minutes must contain exactly 1440 complete observations")
    records = tuple(
        _validate_minute(raw, day_start + timedelta(minutes=index), index)
        for index, raw in enumerate(raw_minutes)
    )
    return ValidatedDay(
        day=bundle_day,
        records=records,
        payload_sha256=_validate_seal(bundle, ended_at),
    )


def validate_day_bundle_file(path: Path) -> ValidatedDay:
    return validate_day_bundle(load_json_strict(path))


def _ratio(records: Sequence[MinuteRecord], index: int) -> Decimal | None:
    record = records[index]
    if record.below_mid_sell_quote_notional <= 0:
        return None
    with localcontext() as context:
        context.prec = 50
        return (
            record.above_mid_buy_quote_notional
            / record.below_mid_sell_quote_notional
        )


def _tier_matches(record: MinuteRecord, ratio: Decimal, tier: str) -> bool:
    if ratio < MIDLINE_RATIO_THRESHOLD:
        return False
    if tier == TIER_KEYS[0]:
        return True
    if record.net_taker_quote_notional <= 0:
        return False
    if tier == TIER_KEYS[1]:
        return True
    return (
        record.average_book_imbalance > 0
        and record.bid_replenishment_quote_proxy > 0
    )


def _bps(price: Decimal, entry: Decimal) -> Decimal:
    with localcontext() as context:
        context.prec = 50
        return (price / entry - Decimal(1)) * Decimal(10000)


def _response(records: Sequence[MinuteRecord], index: int) -> dict[int, dict[str, Decimal]]:
    entry = records[index + 1].trade_open_price
    result: dict[int, dict[str, Decimal]] = {}
    for horizon in HORIZONS:
        path = records[index + 1 : index + horizon + 1]
        result[horizon] = {
            "return_bps": _bps(records[index + horizon].trade_close_price, entry),
            "mfe_bps": _bps(max(record.trade_high_price for record in path), entry),
            "mae_bps": _bps(min(record.trade_low_price for record in path), entry),
        }
    return result


def _format_decimal(value: Decimal) -> str:
    quantized = value.quantize(Decimal("0.00000001"))
    text = format(quantized, "f").rstrip("0").rstrip(".")
    return "0" if text in {"", "-0"} else text


def _serialize_response(value: dict[int, dict[str, Decimal]]) -> dict[str, Any]:
    return {
        str(horizon): {key: _format_decimal(metric) for key, metric in metrics.items()}
        for horizon, metrics in value.items()
    }


def _overlap_pairs(indices: Sequence[int]) -> int:
    count = 0
    left = 0
    for right, current in enumerate(indices):
        while current - indices[left] >= max(HORIZONS):
            left += 1
        count += right - left
    return count


def _aggregate(
    events: Sequence[dict[str, Any]],
    matched: Sequence[tuple[dict[str, Any], dict[str, Any]]],
) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for horizon in HORIZONS:
        event_returns = [event["response"][horizon]["return_bps"] for event in events]
        event_mfe = [event["response"][horizon]["mfe_bps"] for event in events]
        event_mae = [event["response"][horizon]["mae_bps"] for event in events]
        paired_deltas = [
            event["response"][horizon]["return_bps"]
            - control["response"][horizon]["return_bps"]
            for event, control in matched
        ]
        positive_share = (
            Decimal(sum(value > 0 for value in event_returns))
            * Decimal(100)
            / Decimal(len(event_returns))
            if event_returns
            else None
        )
        metrics[str(horizon)] = {
            "median_return_bps": _format_decimal(median(event_returns))
            if event_returns
            else None,
            "median_mfe_bps": _format_decimal(median(event_mfe)) if event_mfe else None,
            "median_mae_bps": _format_decimal(median(event_mae)) if event_mae else None,
            "positive_return_share_pct": _format_decimal(positive_share)
            if positive_share is not None
            else None,
            "matched_median_return_delta_bps": _format_decimal(median(paired_deltas))
            if paired_deltas
            else None,
        }
    return metrics


def analyze_records(
    records: Sequence[MinuteRecord],
    *,
    first_day: date | None = None,
) -> dict[str, Any]:
    if not records:
        raise ValueError("records must not be empty")
    for index in range(1, len(records)):
        if records[index].minute != records[index - 1].minute + timedelta(minutes=1):
            raise ValueError("records must be minute-contiguous")
    first_day = first_day or records[0].minute.date()
    latest_labeled_index = len(records) - max(HORIZONS) - 1
    ratios = [_ratio(records, index) for index in range(len(records))]
    controls_by_minute: dict[int, list[int]] = {}
    for index in range(max(latest_labeled_index + 1, 0)):
        ratio = ratios[index]
        if ratio is not None and ratio < MIDLINE_RATIO_THRESHOLD:
            minute_of_day = records[index].minute.hour * 60 + records[index].minute.minute
            controls_by_minute.setdefault(minute_of_day, []).append(index)

    tier_results: dict[str, Any] = {}
    for tier in TIER_KEYS:
        event_indices: list[int] = []
        last_event = -COOLDOWN_MINUTES
        for index in range(max(latest_labeled_index + 1, 0)):
            ratio = ratios[index]
            if ratio is None or not _tier_matches(records[index], ratio, tier):
                continue
            if index - last_event < COOLDOWN_MINUTES:
                continue
            event_indices.append(index)
            last_event = index

        used_controls: set[int] = set()
        events: list[dict[str, Any]] = []
        matched_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
        first_half_events = 0
        second_half_events = 0
        for index in event_indices:
            response = _response(records, index)
            event = {
                "index": index,
                "signal_at": records[index].minute,
                "entry_at": records[index + 1].minute,
                "entry_open_price": records[index + 1].trade_open_price,
                "midline_buy_sell_ratio": ratios[index],
                "response": response,
                "control": None,
            }
            day_offset = (records[index].minute.date() - first_day).days
            if day_offset < 7:
                first_half_events += 1
            else:
                second_half_events += 1

            minute_of_day = records[index].minute.hour * 60 + records[index].minute.minute
            candidates = controls_by_minute.get(minute_of_day, [])
            control_index = next(
                (
                    candidate
                    for candidate in reversed(candidates)
                    if candidate < index and candidate not in used_controls
                ),
                None,
            )
            if control_index is not None:
                used_controls.add(control_index)
                control = {
                    "index": control_index,
                    "signal_at": records[control_index].minute,
                    "entry_at": records[control_index + 1].minute,
                    "entry_open_price": records[control_index + 1].trade_open_price,
                    "midline_buy_sell_ratio": ratios[control_index],
                    "response": _response(records, control_index),
                }
                event["control"] = control
                matched_pairs.append((event, control))
            events.append(event)

        coverage = (
            Decimal(len(matched_pairs)) * Decimal(100) / Decimal(len(events))
            if events
            else Decimal(0)
        )
        gates = {
            "minimum_30_events": len(events) >= MINIMUM_EVENTS,
            "minimum_10_events_first_seven_days": first_half_events >= MINIMUM_HALF_EVENTS,
            "minimum_10_events_second_seven_days": second_half_events >= MINIMUM_HALF_EVENTS,
            "minimum_80_pct_matched_controls": coverage >= MINIMUM_MATCH_COVERAGE,
        }
        tier_results[tier] = {
            "event_count": len(events),
            "first_seven_day_event_count": first_half_events,
            "second_seven_day_event_count": second_half_events,
            "matched_control_count": len(matched_pairs),
            "matched_control_coverage_pct": _format_decimal(coverage),
            "overlapping_1440m_event_pair_count": _overlap_pairs(event_indices),
            "gates": gates,
            "gate_status": "PASS" if all(gates.values()) else "INSUFFICIENT_FORWARD_EVIDENCE",
            "metrics_by_horizon_minutes": _aggregate(events, matched_pairs),
            "events": [
                {
                    "signal_at": event["signal_at"].isoformat().replace("+00:00", "Z"),
                    "entry_at": event["entry_at"].isoformat().replace("+00:00", "Z"),
                    "entry_open_price": _format_decimal(event["entry_open_price"]),
                    "midline_buy_sell_ratio": _format_decimal(
                        event["midline_buy_sell_ratio"]
                    ),
                    "response": _serialize_response(event["response"]),
                    "matched_control": (
                        {
                            "signal_at": event["control"]["signal_at"]
                            .isoformat()
                            .replace("+00:00", "Z"),
                            "entry_at": event["control"]["entry_at"]
                            .isoformat()
                            .replace("+00:00", "Z"),
                            "midline_buy_sell_ratio": _format_decimal(
                                event["control"]["midline_buy_sell_ratio"]
                            ),
                            "response": _serialize_response(event["control"]["response"]),
                        }
                        if event["control"] is not None
                        else None
                    ),
                }
                for event in events
            ],
        }
    return tier_results


def _validate_day_sequence(days: Sequence[ValidatedDay]) -> None:
    if len(days) != EXPECTED_DAYS:
        raise ValueError("diagnostic requires exactly 14 sealed complete UTC days")
    for index, day in enumerate(days):
        if index and day.day != days[index - 1].day + timedelta(days=1):
            raise ValueError("the 14 input days must be strictly ordered and contiguous")


def _load_contract(path: Path) -> tuple[dict[str, Any], str]:
    contract = load_json_strict(path)
    _exact_keys(
        contract,
        {
            "schema_version",
            "contract_id",
            "supersedes",
            "authorization",
            "purpose",
            "input",
            "feature",
            "tiers",
            "event_sampling",
            "entry_reference",
            "response",
            "matched_control",
            "readiness_gates",
            "guardrails",
        },
        "diagnostic contract",
    )
    if contract["schema_version"] != "3":
        raise ValueError("unexpected diagnostic contract schema_version")
    if contract.get("contract_id") != CONTRACT_ID:
        raise ValueError("unexpected diagnostic contract_id")
    if contract["supersedes"] != "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V2_BEFORE_EVIDENCE":
        raise ValueError("diagnostic supersession lineage changed")
    if contract.get("authorization") != AUTHORIZATION:
        raise ValueError("diagnostic contract authorization changed")
    if contract.get("purpose") != "HYPOTHESIS_DISCOVERY_ONLY":
        raise ValueError("diagnostic contract purpose changed")
    if contract.get("input") != {
        "bundle_schema_version": DAY_SCHEMA_VERSION,
        "required_complete_utc_days": EXPECTED_DAYS,
        "minutes_per_day": MINUTES_PER_DAY,
        "historical_backfill": False,
        "required_channels": ["trades", "books5"],
        "required_integrity_status": "CLEAN",
    }:
        raise ValueError("diagnostic input contract changed")
    if contract["feature"] != {
        "name": "ABOVE_MID_BUY_TO_BELOW_MID_SELL_QUOTE_RATIO",
        "price_midline_formula": "(best_bid_price_1 + best_ask_price_1) / 2",
        "book_reference": "LATEST_BOOKS5_AT_OR_BEFORE_TRADE",
        "numerator": (
            "SUM_QUOTE_NOTIONAL_WHERE_TAKER_SIDE_BUY_AND_"
            "TRADE_PRICE_GT_REFERENCE_MID"
        ),
        "denominator": (
            "SUM_QUOTE_NOTIONAL_WHERE_TAKER_SIDE_SELL_AND_"
            "TRADE_PRICE_LT_REFERENCE_MID"
        ),
        "ratio_threshold": "1.50",
        "zero_denominator_disposition": "NO_EVENT",
        "equal_or_cross_classified_quote_disposition": "MIDLINE_OTHER_NOTIONAL",
        "minute_completion_required": True,
    }:
        raise ValueError("diagnostic feature contract changed")
    if contract["tiers"] != [
        {
            "key": TIER_KEYS[0],
            "conditions": [
                "below_mid_sell_quote_notional_gt_0",
                "midline_buy_sell_ratio_gte_1_50",
            ],
        },
        {
            "key": TIER_KEYS[1],
            "conditions": [
                "below_mid_sell_quote_notional_gt_0",
                "midline_buy_sell_ratio_gte_1_50",
                "net_taker_quote_notional_gt_0",
            ],
        },
        {
            "key": TIER_KEYS[2],
            "conditions": [
                "below_mid_sell_quote_notional_gt_0",
                "midline_buy_sell_ratio_gte_1_50",
                "net_taker_quote_notional_gt_0",
                "average_book_imbalance_gt_0",
                "bid_replenishment_quote_proxy_gt_0",
            ],
        },
    ]:
        raise ValueError("diagnostic tiers changed")
    if contract["event_sampling"] != {
        "cooldown_minutes_per_tier": COOLDOWN_MINUTES,
        "overlapping_horizon_labels_allowed": True,
        "overlap_must_be_reported": True,
    }:
        raise ValueError("diagnostic event-sampling contract changed")
    if contract["entry_reference"] != {
        "timing": "NEXT_COMPLETE_MINUTE_OPEN",
        "field": "trade_open_price",
        "signal_minute_price_is_prohibited": True,
    }:
        raise ValueError("diagnostic entry-reference contract changed")
    if contract["response"] != {
        "horizons_minutes": list(HORIZONS),
        "metrics": [
            "return_bps",
            "mfe_bps",
            "mae_bps",
            "positive_return_share_pct",
            "matched_median_return_delta_bps",
        ],
        "fees_and_slippage": "NOT_APPLIED_DIAGNOSTIC_NOT_PNL",
    }:
        raise ValueError("diagnostic response contract changed")
    if contract["matched_control"] != {
        "event_condition": (
            "below_mid_sell_quote_notional_gt_0_and_"
            "midline_buy_sell_ratio_lt_1_50"
        ),
        "same_utc_minute_of_day": True,
        "strictly_earlier_than_event": True,
        "selection": "CLOSEST_UNUSED_EARLIER_DAY",
        "minimum_match_coverage_pct": "80.00",
    }:
        raise ValueError("diagnostic matched-control contract changed")
    if contract["readiness_gates"] != {
        "minimum_labeled_events_per_tier": MINIMUM_EVENTS,
        "minimum_events_per_seven_day_half": MINIMUM_HALF_EVENTS,
        "minimum_match_coverage_pct": "80.00",
        "all_14_days_complete_and_contiguous": True,
        "all_input_seals_valid": True,
        "zero_integrity_anomalies": True,
        "zero_unreferenced_trades": True,
        "zero_crossed_books": True,
    }:
        raise ValueError("diagnostic readiness gates changed")
    if contract["guardrails"] != [
        "v2_total_volume_ratio_was_superseded_before_evidence",
        "diagnostic_is_not_a_strategy",
        "diagnostic_is_not_candidate_or_oos_evidence",
        "no_tier_selection_or_threshold_change_after_outcome_access",
        "no_buy_sell_order_or_runtime_action",
        "no_historical_backfill",
        "no_canonical_90_day_evidence_mutation",
        "insufficient_evidence_is_a_valid_result",
    ]:
        raise ValueError("diagnostic guardrails changed")
    return contract, hashlib.sha256(path.read_bytes()).hexdigest()


def analyze_files(
    input_paths: Sequence[Path],
    *,
    contract_path: Path | None = None,
) -> dict[str, Any]:
    contract_path = contract_path or Path(__file__).with_name(
        "okx-microstructure-forward-diagnostic-contract.v3.json"
    )
    _contract, contract_file_sha256 = _load_contract(contract_path)
    if len(input_paths) != EXPECTED_DAYS:
        raise ValueError("exactly 14 input day files are required")
    days = [validate_day_bundle_file(path) for path in input_paths]
    _validate_day_sequence(days)
    records = tuple(record for day in days for record in day.records)
    tiers = analyze_records(records, first_day=days[0].day)
    all_tiers_pass = all(tier["gate_status"] == "PASS" for tier in tiers.values())
    result: dict[str, Any] = {
        "schema_version": "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_RESULT_V3",
        "contract_id": CONTRACT_ID,
        "contract_file_sha256": contract_file_sha256,
        "authorization": AUTHORIZATION,
        "status": (
            "FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION"
            if all_tiers_pass
            else "INSUFFICIENT_FORWARD_EVIDENCE"
        ),
        "input": {
            "first_day": days[0].day.isoformat(),
            "last_day": days[-1].day.isoformat(),
            "complete_utc_days": len(days),
            "complete_minutes": len(records),
            "files": [
                {
                    "path": str(path),
                    "day": day.day.isoformat(),
                    "payload_sha256": day.payload_sha256,
                    "file_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                }
                for path, day in zip(input_paths, days)
            ],
        },
        "entry_reference": "NEXT_COMPLETE_MINUTE_OPEN",
        "fees_and_slippage": "NOT_APPLIED_DIAGNOSTIC_NOT_PNL",
        "tiers": tiers,
        "inference_boundary": [
            "result_is_hypothesis_discovery_only",
            "result_is_not_candidate_or_oos_evidence",
            "result_is_not_a_trading_strategy_or_order_instruction",
            "no_tier_selection_or_threshold_change_after_outcome_access",
            "insufficient_evidence_is_a_valid_result",
        ],
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }
    return result


def write_new_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False)
    try:
        with path.open("x", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.write("\n")
    except FileExistsError as error:
        raise ValueError(f"refusing to overwrite existing output: {path}") from error


def _parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze exactly 14 sealed OKX forward-only microstructure day bundles."
    )
    parser.add_argument(
        "--input",
        action="append",
        required=True,
        type=Path,
        help="Day V3 JSON file; repeat exactly 14 times in UTC-day order.",
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--contract", type=Path)
    return parser.parse_args(argv)


def main(argv: Iterable[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        result = analyze_files(args.input, contract_path=args.contract)
        write_new_json(args.output, result)
    except ValueError as error:
        print(json.dumps({"status": "DATA_REJECT", "reason": str(error)}, sort_keys=True))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output),
                "payload_sha256": result["seal"]["payload_sha256"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
