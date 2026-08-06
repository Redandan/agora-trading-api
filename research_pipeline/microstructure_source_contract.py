from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
PURPOSE = "HYPOTHESIS_DISCOVERY_ONLY"
ENDPOINT = "wss://ws.okx.com:8443/ws/v5/public"
SOURCE_CONTRACT_ID = "OKX_MICROSTRUCTURE_CONTINUOUS_SOURCE_V1"
SOURCE_CONTRACT_SHA256 = (
    "f2b353fc211d86755488bb7d9ee63057c6def8b9cd5353b86f7514981cc3e51e"
)
DROP_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V1"
DROP_ENVELOPE_SCHEMA_SHA256 = (
    "285a3f12094c24365304a73a0a1e48c921fca78e21c6a3a799912f1dd7133234"
)
INTAKE_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_INTAKE_STATE_V1"
INTAKE_STATE_SCHEMA_SHA256 = (
    "2a8e42f8e0358dcc84d63a3472860ed956f739990c7c9ecba94764a7be2b1995"
)
DAY_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_FORWARD_DAY_V2"
DAY_SCHEMA_SHA256 = (
    "916525b47fcd7f8862522ca740bf987cbb5d5082237d94d8814087b8b3853fc1"
)
DIAGNOSTIC_CONTRACT_SHA256 = (
    "b58ae60f76bcdb7c60114c0b076730225056e11ca5cfe604fe7415b4e41ffe6c"
)
REQUIRED_DAYS = 14
MINUTES_PER_DAY = 1440
ZERO_SHA256 = "0" * 64
DAY_CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
ENVELOPE_CANONICALIZATION = (
    "UTF-8 compact JSON excluding envelope_seal; object keys sorted "
    "lexicographically"
)
BUNDLE_DOCUMENT_CANONICALIZATION = (
    "UTF-8 compact JSON including seal; object keys sorted lexicographically"
)
ENVELOPE_DOCUMENT_CANONICALIZATION = (
    "UTF-8 compact JSON including envelope_seal; object keys sorted "
    "lexicographically"
)
CHAIN_ALGORITHM = (
    "SHA-256(UTF-8(previous_chain_sha256 + LF + day + LF + bundle_sha256 + "
    "LF + envelope_sha256)); first previous is 64 zeroes"
)

PACKAGE_DIR = Path(__file__).resolve().parent
SOURCE_CONTRACT_PATH = (
    PACKAGE_DIR / "okx-microstructure-continuous-source-contract.v1.json"
)
DROP_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-drop-envelope.v1.schema.json"
INTAKE_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-intake-state.v1.schema.json"

V3_SOURCE_CONTRACT_ID = "OKX_MICROSTRUCTURE_CONTINUOUS_SOURCE_V3"
V3_SOURCE_CONTRACT_SHA256 = (
    "8a581cc03eb9381af4bfecddb8f40c7d23759ce239647447bc37351e4f293422"
)
V3_DROP_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V3"
V3_DROP_ENVELOPE_SCHEMA_SHA256 = (
    "ad6e23797240a9e4a86affff40e801d7d659a8a408ffad65270a42dec2b46418"
)
V3_INTAKE_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_INTAKE_STATE_V3"
V3_INTAKE_STATE_SCHEMA_SHA256 = (
    "935da25d8f5e66bb4ec13625ff2e8eb7480e503f8c4d580abd41514ee90aa7fc"
)
V3_DAY_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_FORWARD_DAY_V3"
V3_DAY_SCHEMA_SHA256 = (
    "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709"
)
V3_DIAGNOSTIC_CONTRACT_SHA256 = (
    "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a"
)
V3_SOURCE_CONTRACT_PATH = (
    PACKAGE_DIR / "okx-microstructure-continuous-source-contract.v3.json"
)
V3_DROP_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-drop-envelope.v3.schema.json"
V3_INTAKE_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-intake-state.v3.schema.json"
V3_DAY_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-forward-day.v3.schema.json"
V3_DIAGNOSTIC_CONTRACT_PATH = (
    PACKAGE_DIR / "okx-microstructure-forward-diagnostic-contract.v3.json"
)

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DIAGNOSTIC_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")
DECIMAL_PATTERN = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")

SOURCE_KEYS = {
    "producer_type",
    "producer_identity",
    "endpoint",
    "venue",
    "instrument",
    "channels",
    "aggregation_timezone",
    "mode",
    "historical_backfill",
    "credentials",
    "raw_messages_persisted",
    "network_scope",
}
COLLECTION_KEYS = {
    "required_complete_utc_days",
    "minutes_per_day",
    "day_policy",
    "seal_trigger",
    "bundle_document_canonicalization",
    "envelope_document_canonicalization",
    "gap_policy",
    "reconnect_policy",
    "predecessor_binding_required",
    "raw_arrival_chain_required",
}
BINDING_KEYS = {
    "day_schema_path",
    "day_schema_sha256",
    "diagnostic_contract_path",
    "diagnostic_contract_sha256",
    "drop_envelope_schema_id",
    "intake_state_schema_id",
}
LIFECYCLE_KEYS = {
    "timer_authority",
    "cloud_schedule_count",
    "state_authority",
    "supervision_role",
    "server_intake_network_access",
    "producer_canonical_state_access",
    "producer_canonical_state_write",
    "producer_can_enqueue_research_actions",
    "producer_can_select_research_actions",
    "producer_can_retry_research_actions",
    "producer_can_time_research_actions",
}
SEPARATION_KEYS = {
    "transport",
    "canonical_namespace",
    "candle_chain_reuse",
    "candle_trigger_reuse",
    "advances_candle_evidence",
    "trading_runtime_access",
    "database_access",
    "order_or_fund_access",
}
READINESS_KEYS = {
    "status",
    "accepted_day_count",
    "authorizes",
    "candidate_authorized",
    "oos_authorized",
    "pnl_claim_authorized",
    "promotion_authorized",
    "missing_proof",
}

ENVELOPE_KEYS = {
    "schema_version",
    "envelope_type",
    "authorization",
    "diagnostic_id",
    "source_contract_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "producer_identity",
    "day",
    "predecessor_day",
    "predecessor_bundle_sha256",
    "bundle_name",
    "bundle_size_bytes",
    "bundle_sha256",
    "published_at",
    "idempotency_key",
    "delivery_semantics",
    "envelope_seal",
}
DELIVERY_KEYS = {
    "transport",
    "bundle_document_canonicalization",
    "envelope_document_canonicalization",
    "atomic_rename",
    "overwrite",
    "source_read_after_publish",
    "symlinks",
    "canonical_state_access",
    "candle_chain_reuse",
}
SEAL_KEYS = {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}

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
DAY_SOURCE_KEYS = {
    "venue",
    "instrument",
    "channels",
    "mode",
    "historical_backfill",
    "raw_messages_persisted",
    "aggregation_timezone",
}
CAPTURE_KEYS = {"started_at", "ended_at", "acknowledged_channels"}
INTEGRITY_KEYS = {
    "status",
    "anomaly_count",
    "raw_message_count",
    "arrival_chain_sha256",
}
MINUTE_KEYS = {
    "minute",
    "trade_record_count",
    "match_count",
    "buy_quote_notional",
    "sell_quote_notional",
    "total_quote_notional",
    "net_taker_quote_notional",
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
V3_DAY_SOURCE_KEYS = DAY_SOURCE_KEYS | {
    "midline_formula",
    "midline_reference",
    "unreferenced_trade_disposition",
}
V3_INTEGRITY_KEYS = INTEGRITY_KEYS | {
    "midline_unreferenced_trade_count",
    "crossed_book_count",
}
V3_MINUTE_KEYS = MINUTE_KEYS | {
    "midline_reference_count",
    "above_mid_buy_quote_notional",
    "below_mid_sell_quote_notional",
    "midline_other_quote_notional",
}

STATE_KEYS = {
    "schema_version",
    "state_type",
    "authorization",
    "diagnostic_id",
    "source_contract_sha256",
    "drop_envelope_schema_sha256",
    "day_schema_sha256",
    "diagnostic_contract_sha256",
    "state_authority",
    "intake_identity",
    "network_access",
    "historical_backfill",
    "candle_chain_reuse",
    "candle_trigger_reuse",
    "research_lifecycle_clock",
    "cloud_schedule_count",
    "chain_algorithm",
    "start_day",
    "required_day_count",
    "accepted_days",
    "status",
    "next_expected_day",
    "chain_head_sha256",
    "failure",
    "readiness",
}
ACCEPTED_DAY_KEYS = {
    "day",
    "bundle_sha256",
    "envelope_sha256",
    "predecessor_bundle_sha256",
    "accepted_at",
    "cumulative_chain_sha256",
}
FAILURE_KEYS = {"code", "day", "detail"}
STATE_READINESS_KEYS = {
    "disposition",
    "candidate_authorized",
    "oos_authorized",
    "pnl_claim_authorized",
    "promotion_authorized",
    "performance_value",
}
FAILURE_CODES = {
    "CONTRACT_HASH_MISMATCH",
    "WRONG_IDENTITY",
    "SYMLINK_REJECT",
    "OVERWRITE_REJECT",
    "NON_ATOMIC_DELIVERY",
    "WRONG_DAY",
    "NONCONTIGUOUS_DAY",
    "INCOMPLETE_DAY",
    "INTEGRITY_NOT_CLEAN",
    "STREAM_GAP",
    "HASH_MISMATCH",
    "PREDECESSOR_MISMATCH",
    "CONFLICTING_DUPLICATE",
    "BACKFILL_FORBIDDEN",
    "CANDLE_CHAIN_REUSE_FORBIDDEN",
    "LIFECYCLE_CLOCK_FORBIDDEN",
}


@dataclass(frozen=True)
class _ContractProfile:
    source_contract_sha256: str
    drop_schema_version: str
    drop_envelope_schema_sha256: str
    intake_schema_version: str
    day_schema_version: str
    day_schema_sha256: str
    diagnostic_contract_sha256: str
    state_type: str
    ready_disposition: str
    day_source_keys: frozenset[str]
    integrity_keys: frozenset[str]
    minute_keys: frozenset[str]
    v3_midline: bool


_V2_PROFILE = _ContractProfile(
    source_contract_sha256=SOURCE_CONTRACT_SHA256,
    drop_schema_version=DROP_SCHEMA_VERSION,
    drop_envelope_schema_sha256=DROP_ENVELOPE_SCHEMA_SHA256,
    intake_schema_version=INTAKE_SCHEMA_VERSION,
    day_schema_version=DAY_SCHEMA_VERSION,
    day_schema_sha256=DAY_SCHEMA_SHA256,
    diagnostic_contract_sha256=DIAGNOSTIC_CONTRACT_SHA256,
    state_type="SERVER_CANONICAL_MICROSTRUCTURE_INTAKE",
    ready_disposition="FROZEN_V2_DISCOVERY_ANALYSIS_ONLY",
    day_source_keys=frozenset(DAY_SOURCE_KEYS),
    integrity_keys=frozenset(INTEGRITY_KEYS),
    minute_keys=frozenset(MINUTE_KEYS),
    v3_midline=False,
)
_V3_PROFILE = _ContractProfile(
    source_contract_sha256=V3_SOURCE_CONTRACT_SHA256,
    drop_schema_version=V3_DROP_SCHEMA_VERSION,
    drop_envelope_schema_sha256=V3_DROP_ENVELOPE_SCHEMA_SHA256,
    intake_schema_version=V3_INTAKE_SCHEMA_VERSION,
    day_schema_version=V3_DAY_SCHEMA_VERSION,
    day_schema_sha256=V3_DAY_SCHEMA_SHA256,
    diagnostic_contract_sha256=V3_DIAGNOSTIC_CONTRACT_SHA256,
    state_type="SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE",
    ready_disposition="FROZEN_V3_DISCOVERY_ANALYSIS_ONLY",
    day_source_keys=frozenset(V3_DAY_SOURCE_KEYS),
    integrity_keys=frozenset(V3_INTEGRITY_KEYS),
    minute_keys=frozenset(V3_MINUTE_KEYS),
    v3_midline=True,
)

PRODUCER_TRANSITIONS = {
    ("UNBOUND", "BIND_FUTURE_WINDOW"): "ARMED_FUTURE",
    ("ARMED_FUTURE", "START_DATA_PLANE"): "CONNECTING",
    ("CONNECTING", "BOTH_CHANNELS_ACKNOWLEDGED"): "CAPTURING_UTC_DAY",
    ("CAPTURING_UTC_DAY", "CONNECTION_LOST"): "RECONNECTING",
    ("RECONNECTING", "RECONNECTED_WITH_COMPLETE_CONTINUITY"): "CAPTURING_UTC_DAY",
    ("CAPTURING_UTC_DAY", "FIRST_VALID_NEXT_DAY_MESSAGE"): "SEAL_PENDING",
    ("SEAL_PENDING", "PUBLISH_ATOMIC_DROP"): "DROP_PUBLISHED",
    ("DROP_PUBLISHED", "CONTINUE_NEXT_DAY"): "CAPTURING_UTC_DAY",
    ("DROP_PUBLISHED", "FOURTEENTH_DAY_PUBLISHED"): "STOPPED_COMPLETE",
    ("CONNECTING", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
    ("CAPTURING_UTC_DAY", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
    ("RECONNECTING", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
    ("SEAL_PENDING", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
    ("DROP_PUBLISHED", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
}
DROP_TRANSITIONS = {
    ("STAGING", "HASH_STAGED_BYTES"): "HASHED",
    ("HASHED", "ATOMIC_RENAME"): "ATOMICALLY_PUBLISHED",
    ("ATOMICALLY_PUBLISHED", "OBSERVE_EXACT_BYTES"): "OBSERVED",
    ("OBSERVED", "INGEST_EXACT_BYTES"): "INGESTED",
    ("INGESTED", "OBSERVE_IDENTICAL_DUPLICATE"): "INGESTED",
    ("STAGING", "CONFLICT"): "CONFLICT_BLOCKED",
    ("HASHED", "CONFLICT"): "CONFLICT_BLOCKED",
    ("ATOMICALLY_PUBLISHED", "CONFLICT"): "CONFLICT_BLOCKED",
    ("OBSERVED", "CONFLICT"): "CONFLICT_BLOCKED",
    ("INGESTED", "CONFLICT"): "CONFLICT_BLOCKED",
}
INTAKE_TRANSITIONS = {
    ("WAITING_FOR_DAY", "DAY_ACCEPTED"): "WAITING_FOR_DAY",
    ("WAITING_FOR_DAY", "FOURTEENTH_DAY_ACCEPTED"): "DIAGNOSTIC_READY",
    ("WAITING_FOR_DAY", "INTEGRITY_FAILURE"): "INTEGRITY_BLOCKED",
}


class ContractViolation(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _reject(code: str, message: str) -> None:
    raise ContractViolation(code, message)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _reject("CONTRACT_HASH_MISMATCH", f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        _reject(
            "CONTRACT_HASH_MISMATCH",
            f"{label} keys mismatch: missing={missing} extra={extra}",
        )


def _integer(value: Any, label: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        _reject("INCOMPLETE_DAY", f"{label} must be an integer >= {minimum}")
    return value


def _sha256(value: Any, label: str, *, code: str = "HASH_MISMATCH") -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        _reject(code, f"{label} must be a lowercase SHA-256")
    return value


def _diagnostic_id(value: Any) -> str:
    if not isinstance(value, str) or DIAGNOSTIC_ID_PATTERN.fullmatch(value) is None:
        _reject("CONTRACT_HASH_MISMATCH", "diagnostic_id is invalid")
    return value


def _date(value: Any, label: str) -> date:
    if not isinstance(value, str):
        _reject("WRONG_DAY", f"{label} must be an ISO date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ContractViolation("WRONG_DAY", f"{label} must be an ISO date") from error
    if parsed.isoformat() != value:
        _reject("WRONG_DAY", f"{label} must use canonical YYYY-MM-DD form")
    return parsed


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        _reject("INCOMPLETE_DAY", f"{label} must use a UTC Z timestamp")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ContractViolation(
            "INCOMPLETE_DAY", f"{label} must be an ISO timestamp"
        ) from error
    if parsed.utcoffset() != timedelta(0):
        _reject("INCOMPLETE_DAY", f"{label} must be UTC")
    return parsed.astimezone(timezone.utc)


def _decimal(value: Any, label: str) -> Decimal:
    if not isinstance(value, str) or DECIMAL_PATTERN.fullmatch(value) is None:
        _reject("INCOMPLETE_DAY", f"{label} must be a canonical decimal string")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ContractViolation(
            "INCOMPLETE_DAY", f"{label} must be a finite decimal"
        ) from error
    canonical = "0" if parsed == 0 else format(parsed.normalize(), "f")
    if value != canonical:
        _reject("INCOMPLETE_DAY", f"{label} must be in canonical decimal form")
    return parsed


def canonical_json_bytes(value: Any, *, exclude_key: str | None = None) -> bytes:
    payload = value
    if exclude_key is not None:
        obj = _object(value, "canonical JSON value")
        payload = {key: item for key, item in obj.items() if key != exclude_key}
    try:
        return json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractViolation(
            "CONTRACT_HASH_MISMATCH", "value is not canonical JSON"
        ) from error


def canonical_sha256(value: Any, *, exclude_key: str | None = None) -> str:
    return hashlib.sha256(canonical_json_bytes(value, exclude_key=exclude_key)).hexdigest()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _reject("CONTRACT_HASH_MISMATCH", f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json_bytes_strict(raw_bytes: bytes, label: str) -> dict[str, Any]:
    if not isinstance(raw_bytes, bytes):
        _reject("HASH_MISMATCH", f"{label} raw bytes are required")
    try:
        text = raw_bytes.decode("utf-8")
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=lambda value: _reject(
                "CONTRACT_HASH_MISMATCH", f"non-finite JSON number: {value}"
            ),
        )
    except ContractViolation:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ContractViolation(
            "HASH_MISMATCH", f"{label} is not strict UTF-8 JSON"
        ) from error
    return _object(value, label)


def _verify_canonical_document_bytes(
    value: Any, raw_bytes: bytes, label: str
) -> bytes:
    parsed = load_json_bytes_strict(raw_bytes, label)
    if parsed != value:
        _reject("HASH_MISMATCH", f"{label} raw bytes do not match parsed value")
    canonical = canonical_json_bytes(value)
    if raw_bytes != canonical:
        _reject(
            "HASH_MISMATCH",
            f"{label} raw bytes are not canonical compact sorted-key JSON",
        )
    return raw_bytes


def load_json_strict(path: Path) -> dict[str, Any]:
    try:
        raw_bytes = path.read_bytes()
    except OSError as error:
        raise ContractViolation(
            "CONTRACT_HASH_MISMATCH", f"could not read {path.name}"
        ) from error
    return load_json_bytes_strict(raw_bytes, path.name)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_source_contract(value: Any) -> dict[str, Any]:
    contract = _object(value, "source contract")
    _exact_keys(
        contract,
        {
            "schema_version",
            "contract_id",
            "authorization",
            "purpose",
            "deployment_status",
            "source",
            "collection",
            "bindings",
            "lifecycle",
            "separation",
            "terminal_readiness",
        },
        "source contract",
    )
    expected_scalars = {
        "schema_version": "1",
        "contract_id": SOURCE_CONTRACT_ID,
        "authorization": AUTHORIZATION,
        "purpose": PURPOSE,
        "deployment_status": "OFFLINE_CONTRACT_ONLY_NOT_DEPLOYED",
    }
    for key, expected in expected_scalars.items():
        if contract[key] != expected:
            _reject("CONTRACT_HASH_MISMATCH", f"source contract {key} changed")

    source = _object(contract["source"], "source")
    _exact_keys(source, SOURCE_KEYS, "source")
    if source != {
        "producer_type": "CONTINUOUS_SUPERVISED_DATA_PLANE",
        "producer_identity": "agora-evidence-source",
        "endpoint": ENDPOINT,
        "venue": "OKX",
        "instrument": "BTC-USDT",
        "channels": ["trades", "books5"],
        "aggregation_timezone": "UTC",
        "mode": "FORWARD_ONLY",
        "historical_backfill": False,
        "credentials": "NONE",
        "raw_messages_persisted": False,
        "network_scope": "FIXED_PUBLIC_ENDPOINT_ONLY",
    }:
        _reject("CONTRACT_HASH_MISMATCH", "source binding changed")

    collection = _object(contract["collection"], "collection")
    _exact_keys(collection, COLLECTION_KEYS, "collection")
    if collection != {
        "required_complete_utc_days": REQUIRED_DAYS,
        "minutes_per_day": MINUTES_PER_DAY,
        "day_policy": "EXPLICIT_FUTURE_START_EXACTLY_14_CONTIGUOUS_UTC_DAYS",
        "seal_trigger": "FIRST_VALID_NEXT_UTC_DAY_MESSAGE",
        "bundle_document_canonicalization": BUNDLE_DOCUMENT_CANONICALIZATION,
        "envelope_document_canonicalization": ENVELOPE_DOCUMENT_CANONICALIZATION,
        "gap_policy": "REJECT_DAY_AND_BLOCK_DIAGNOSTIC",
        "reconnect_policy": "CONTINUE_ONLY_IF_NO_STREAM_OR_MINUTE_GAP",
        "predecessor_binding_required": True,
        "raw_arrival_chain_required": True,
    }:
        _reject("CONTRACT_HASH_MISMATCH", "collection contract changed")

    bindings = _object(contract["bindings"], "bindings")
    _exact_keys(bindings, BINDING_KEYS, "bindings")
    if bindings != {
        "day_schema_path": "research_pipeline/okx-microstructure-forward-day.v2.schema.json",
        "day_schema_sha256": DAY_SCHEMA_SHA256,
        "diagnostic_contract_path": "research_pipeline/okx-microstructure-forward-diagnostic-contract.v2.json",
        "diagnostic_contract_sha256": DIAGNOSTIC_CONTRACT_SHA256,
        "drop_envelope_schema_id": "https://agora.local/research/okx-microstructure-drop-envelope.v1.schema.json",
        "intake_state_schema_id": "https://agora.local/research/okx-microstructure-intake-state.v1.schema.json",
    }:
        _reject("CONTRACT_HASH_MISMATCH", "source contract binding hashes changed")

    lifecycle = _object(contract["lifecycle"], "lifecycle")
    _exact_keys(lifecycle, LIFECYCLE_KEYS, "lifecycle")
    if lifecycle != {
        "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        "cloud_schedule_count": 1,
        "state_authority": "SERVER_CANONICAL",
        "supervision_role": "DATA_PLANE_PROCESS_RECOVERY_ONLY",
        "server_intake_network_access": "DENY",
        "producer_canonical_state_access": False,
        "producer_canonical_state_write": False,
        "producer_can_enqueue_research_actions": False,
        "producer_can_select_research_actions": False,
        "producer_can_retry_research_actions": False,
        "producer_can_time_research_actions": False,
    }:
        _reject("LIFECYCLE_CLOCK_FORBIDDEN", "lifecycle boundary changed")

    separation = _object(contract["separation"], "separation")
    _exact_keys(separation, SEPARATION_KEYS, "separation")
    if separation != {
        "transport": "IMMUTABLE_MICROSTRUCTURE_ONLY_ONE_WAY_DROP",
        "canonical_namespace": "SEPARATE_MICROSTRUCTURE_14_DAY_CHAIN",
        "candle_chain_reuse": False,
        "candle_trigger_reuse": False,
        "advances_candle_evidence": False,
        "trading_runtime_access": False,
        "database_access": False,
        "order_or_fund_access": False,
    }:
        _reject("CANDLE_CHAIN_REUSE_FORBIDDEN", "separation boundary changed")

    readiness = _object(contract["terminal_readiness"], "terminal_readiness")
    _exact_keys(readiness, READINESS_KEYS, "terminal_readiness")
    if readiness != {
        "status": "DIAGNOSTIC_READY",
        "accepted_day_count": REQUIRED_DAYS,
        "authorizes": "FROZEN_V2_DISCOVERY_ANALYSIS_ONLY",
        "candidate_authorized": False,
        "oos_authorized": False,
        "pnl_claim_authorized": False,
        "promotion_authorized": False,
        "missing_proof": [
            "fees",
            "slippage",
            "drawdown_effect",
            "predictive_value",
            "strategy_usefulness",
        ],
    }:
        _reject("CONTRACT_HASH_MISMATCH", "terminal readiness boundary changed")
    return contract


def validate_frozen_contract_files() -> dict[str, str]:
    expected = {
        SOURCE_CONTRACT_PATH: SOURCE_CONTRACT_SHA256,
        DROP_SCHEMA_PATH: DROP_ENVELOPE_SCHEMA_SHA256,
        INTAKE_SCHEMA_PATH: INTAKE_STATE_SCHEMA_SHA256,
    }
    for path, expected_hash in expected.items():
        if file_sha256(path) != expected_hash:
            _reject("CONTRACT_HASH_MISMATCH", f"{path.name} hash changed")
    validate_source_contract(load_json_strict(SOURCE_CONTRACT_PATH))
    drop_schema = load_json_strict(DROP_SCHEMA_PATH)
    intake_schema = load_json_strict(INTAKE_SCHEMA_PATH)
    if drop_schema.get("additionalProperties") is not False:
        _reject("CONTRACT_HASH_MISMATCH", "drop schema is not exact-key closed")
    if intake_schema.get("additionalProperties") is not False:
        _reject("CONTRACT_HASH_MISMATCH", "intake schema is not exact-key closed")
    return {path.name: expected_hash for path, expected_hash in expected.items()}


def validate_v3_frozen_contract_files() -> dict[str, str]:
    expected = {
        V3_SOURCE_CONTRACT_PATH: V3_SOURCE_CONTRACT_SHA256,
        V3_DROP_SCHEMA_PATH: V3_DROP_ENVELOPE_SCHEMA_SHA256,
        V3_INTAKE_SCHEMA_PATH: V3_INTAKE_STATE_SCHEMA_SHA256,
        V3_DAY_SCHEMA_PATH: V3_DAY_SCHEMA_SHA256,
        V3_DIAGNOSTIC_CONTRACT_PATH: V3_DIAGNOSTIC_CONTRACT_SHA256,
    }
    for path, expected_hash in expected.items():
        if file_sha256(path) != expected_hash:
            _reject("CONTRACT_HASH_MISMATCH", f"{path.name} hash changed")

    source = load_json_strict(V3_SOURCE_CONTRACT_PATH)
    if (
        source.get("schema_version") != "3"
        or source.get("contract_id") != V3_SOURCE_CONTRACT_ID
        or source.get("authorization") != AUTHORIZATION
        or source.get("purpose") != PURPOSE
    ):
        _reject("CONTRACT_HASH_MISMATCH", "V3 source identity changed")
    bindings = _object(source.get("bindings"), "V3 source bindings")
    if (
        bindings.get("day_schema_sha256") != V3_DAY_SCHEMA_SHA256
        or bindings.get("diagnostic_contract_sha256")
        != V3_DIAGNOSTIC_CONTRACT_SHA256
        or bindings.get("drop_envelope_schema_id")
        != "https://agora.local/research/okx-microstructure-drop-envelope.v3.schema.json"
        or bindings.get("intake_state_schema_id")
        != "https://agora.local/research/okx-microstructure-intake-state.v3.schema.json"
    ):
        _reject("CONTRACT_HASH_MISMATCH", "V3 source binding hashes changed")

    schema_versions = {
        V3_DROP_SCHEMA_PATH: V3_DROP_SCHEMA_VERSION,
        V3_INTAKE_SCHEMA_PATH: V3_INTAKE_SCHEMA_VERSION,
        V3_DAY_SCHEMA_PATH: V3_DAY_SCHEMA_VERSION,
    }
    for path, version in schema_versions.items():
        schema = load_json_strict(path)
        if schema.get("additionalProperties") is not False:
            _reject("CONTRACT_HASH_MISMATCH", f"{path.name} is not exact-key closed")
        if (
            _object(schema.get("properties"), f"{path.name} properties")
            .get("schema_version", {})
            .get("const")
            != version
        ):
            _reject("CONTRACT_HASH_MISMATCH", f"{path.name} version changed")
    diagnostic = load_json_strict(V3_DIAGNOSTIC_CONTRACT_PATH)
    if (
        diagnostic.get("schema_version") != "3"
        or diagnostic.get("contract_id")
        != "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3"
        or _object(diagnostic.get("input"), "V3 diagnostic input").get(
            "bundle_schema_version"
        )
        != V3_DAY_SCHEMA_VERSION
    ):
        _reject("CONTRACT_HASH_MISMATCH", "V3 diagnostic binding changed")
    return {path.name: expected_hash for path, expected_hash in expected.items()}


def _transition(
    transitions: dict[tuple[str, str], str], state: str, event: str, label: str
) -> str:
    target = transitions.get((state, event))
    if target is None:
        _reject("LIFECYCLE_CLOCK_FORBIDDEN", f"invalid {label} transition {state}+{event}")
    return target


def transition_producer(state: str, event: str) -> str:
    return _transition(PRODUCER_TRANSITIONS, state, event, "producer")


def transition_drop(state: str, event: str) -> str:
    return _transition(DROP_TRANSITIONS, state, event, "drop")


def transition_intake(state: str, event: str) -> str:
    return _transition(INTAKE_TRANSITIONS, state, event, "intake")


def _validate_day_bundle(
    profile: _ContractProfile, value: Any, *, raw_bytes: bytes
) -> dict[str, Any]:
    bundle = _object(value, "day bundle")
    verified_raw_bytes = _verify_canonical_document_bytes(
        bundle, raw_bytes, "day bundle"
    )
    _exact_keys(bundle, DAY_KEYS, "day bundle")
    if bundle["schema_version"] != profile.day_schema_version:
        _reject("CONTRACT_HASH_MISMATCH", "day schema version changed")
    if bundle["bundle_type"] != "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY":
        _reject("CONTRACT_HASH_MISMATCH", "day bundle type changed")
    if bundle["authorization"] != AUTHORIZATION:
        _reject("CONTRACT_HASH_MISMATCH", "day authorization changed")

    source = _object(bundle["source"], "day source")
    _exact_keys(source, profile.day_source_keys, "day source")
    expected_source = {
        "venue": "OKX",
        "instrument": "BTC-USDT",
        "channels": ["trades", "books5"],
        "mode": "FORWARD_ONLY",
        "historical_backfill": False,
        "raw_messages_persisted": False,
        "aggregation_timezone": "UTC",
    }
    if profile.v3_midline:
        expected_source.update(
            {
                "midline_formula": "BEST_BID_1_PLUS_BEST_ASK_1_DIVIDED_BY_2",
                "midline_reference": "LATEST_BOOKS5_AT_OR_BEFORE_TRADE",
                "unreferenced_trade_disposition": "INTEGRITY_ANOMALY",
            }
        )
    if source != expected_source:
        code = (
            "BACKFILL_FORBIDDEN"
            if source.get("historical_backfill") is not False
            else "CONTRACT_HASH_MISMATCH"
        )
        _reject(code, "day source changed from the frozen forward-only contract")

    bundle_day = _date(bundle["day"], "day")
    day_start = datetime.combine(bundle_day, datetime.min.time(), tzinfo=timezone.utc)
    day_end = day_start + timedelta(days=1)
    capture = _object(bundle["capture"], "capture")
    _exact_keys(capture, CAPTURE_KEYS, "capture")
    if _timestamp(capture["started_at"], "capture.started_at") != day_start:
        _reject("INCOMPLETE_DAY", "capture must begin at exact UTC day start")
    if _timestamp(capture["ended_at"], "capture.ended_at") != day_end:
        _reject("INCOMPLETE_DAY", "capture must end at exact next UTC day start")
    if capture["acknowledged_channels"] != ["books5", "trades"]:
        _reject("STREAM_GAP", "both channels must be acknowledged")

    integrity = _object(bundle["integrity"], "integrity")
    _exact_keys(integrity, profile.integrity_keys, "integrity")
    if integrity["status"] != "CLEAN" or integrity["anomaly_count"] != 0:
        _reject("INTEGRITY_NOT_CLEAN", "day integrity must be CLEAN with zero anomalies")
    _integer(integrity["raw_message_count"], "integrity.raw_message_count", minimum=1)
    _sha256(integrity["arrival_chain_sha256"], "integrity.arrival_chain_sha256")
    if profile.v3_midline and (
        _integer(
            integrity["midline_unreferenced_trade_count"],
            "integrity.midline_unreferenced_trade_count",
            minimum=0,
        )
        != 0
        or _integer(
            integrity["crossed_book_count"],
            "integrity.crossed_book_count",
            minimum=0,
        )
        != 0
    ):
        _reject(
            "INTEGRITY_NOT_CLEAN",
            "V3 day must contain zero unreferenced trades and crossed books",
        )

    minutes = bundle["minutes"]
    if not isinstance(minutes, list) or len(minutes) != MINUTES_PER_DAY:
        _reject("INCOMPLETE_DAY", "day must contain exactly 1440 minutes")
    for index, raw_minute in enumerate(minutes):
        minute = _object(raw_minute, f"minutes[{index}]")
        _exact_keys(minute, profile.minute_keys, f"minutes[{index}]")
        expected_at = day_start + timedelta(minutes=index)
        minute_at = _timestamp(minute["minute"], f"minutes[{index}].minute")
        if minute_at != expected_at or minute_at.second or minute_at.microsecond:
            _reject("INCOMPLETE_DAY", "minutes must be exact contiguous UTC minutes")
        trade_records = _integer(
            minute["trade_record_count"],
            f"minutes[{index}].trade_record_count",
            minimum=1,
        )
        match_count = _integer(
            minute["match_count"], f"minutes[{index}].match_count", minimum=1
        )
        if match_count < trade_records:
            _reject("INCOMPLETE_DAY", "match_count is below trade_record_count")
        if profile.v3_midline and _integer(
            minute["midline_reference_count"],
            f"minutes[{index}].midline_reference_count",
            minimum=1,
        ) != trade_records:
            _reject(
                "INTEGRITY_NOT_CLEAN",
                "midline_reference_count must equal trade_record_count",
            )
        if _integer(
            minute["book_sample_count"],
            f"minutes[{index}].book_sample_count",
            minimum=0,
        ) < 1:
            _reject("STREAM_GAP", "books5 stream gap")

        buy = _decimal(minute["buy_quote_notional"], "buy_quote_notional")
        sell = _decimal(minute["sell_quote_notional"], "sell_quote_notional")
        total = _decimal(minute["total_quote_notional"], "total_quote_notional")
        net = _decimal(minute["net_taker_quote_notional"], "net_taker_quote_notional")
        if buy < 0 or sell < 0 or total <= 0 or total != buy + sell or net != buy - sell:
            _reject("INCOMPLETE_DAY", "trade quote notional identities are invalid")
        if profile.v3_midline:
            above = _decimal(
                minute["above_mid_buy_quote_notional"],
                "above_mid_buy_quote_notional",
            )
            below = _decimal(
                minute["below_mid_sell_quote_notional"],
                "below_mid_sell_quote_notional",
            )
            other = _decimal(
                minute["midline_other_quote_notional"],
                "midline_other_quote_notional",
            )
            if (
                above < 0
                or below < 0
                or other < 0
                or above > buy
                or below > sell
                or above + below + other != total
            ):
                _reject(
                    "INTEGRITY_NOT_CLEAN",
                    "V3 midline quote buckets do not reconcile",
                )

        open_price = _decimal(minute["trade_open_price"], "trade_open_price")
        high_price = _decimal(minute["trade_high_price"], "trade_high_price")
        low_price = _decimal(minute["trade_low_price"], "trade_low_price")
        close_price = _decimal(minute["trade_close_price"], "trade_close_price")
        vwap_price = _decimal(minute["trade_vwap_price"], "trade_vwap_price")
        if low_price <= 0 or high_price < low_price or any(
            price < low_price or price > high_price
            for price in (open_price, close_price, vwap_price)
        ):
            _reject("INCOMPLETE_DAY", "trade price bounds are invalid")

        first_trade = _timestamp(minute["first_trade_at"], "first_trade_at")
        last_trade = _timestamp(minute["last_trade_at"], "last_trade_at")
        first_book = _timestamp(minute["first_book_at"], "first_book_at")
        last_book = _timestamp(minute["last_book_at"], "last_book_at")
        minute_end = expected_at + timedelta(minutes=1)
        if not expected_at <= first_trade <= last_trade < minute_end:
            _reject("STREAM_GAP", "trade timestamps do not remain inside the minute")
        if not expected_at <= first_book <= last_book < minute_end:
            _reject("STREAM_GAP", "books5 timestamps do not remain inside the minute")

        bid_depth = _decimal(
            minute["average_top5_bid_quote_depth"], "average_top5_bid_quote_depth"
        )
        ask_depth = _decimal(
            minute["average_top5_ask_quote_depth"], "average_top5_ask_quote_depth"
        )
        imbalance = _decimal(minute["average_book_imbalance"], "average_book_imbalance")
        spread = _decimal(minute["average_spread_bps"], "average_spread_bps")
        replenishment = _decimal(
            minute["bid_replenishment_quote_proxy"], "bid_replenishment_quote_proxy"
        )
        if bid_depth < 0 or ask_depth < 0 or bid_depth + ask_depth <= 0:
            _reject("STREAM_GAP", "books5 depth is absent")
        if imbalance < -1 or imbalance > 1 or spread < 0 or replenishment < 0:
            _reject("INCOMPLETE_DAY", "book metrics are outside frozen bounds")

        mid_start = _decimal(minute["mid_price_start"], "mid_price_start")
        mid_high = _decimal(minute["mid_price_high"], "mid_price_high")
        mid_low = _decimal(minute["mid_price_low"], "mid_price_low")
        mid_end = _decimal(minute["mid_price_end"], "mid_price_end")
        if mid_low <= 0 or mid_high < mid_low or any(
            price < mid_low or price > mid_high for price in (mid_start, mid_end)
        ):
            _reject("INCOMPLETE_DAY", "mid-price bounds are invalid")

    seal = _object(bundle["seal"], "seal")
    _exact_keys(seal, SEAL_KEYS, "seal")
    if seal["algorithm"] != "SHA-256" or seal["canonicalization"] != DAY_CANONICALIZATION:
        _reject("HASH_MISMATCH", "day seal contract changed")
    if _timestamp(seal["sealed_at"], "seal.sealed_at") < day_end:
        _reject("HASH_MISMATCH", "day seal precedes complete UTC day close")
    actual_payload_hash = canonical_sha256(bundle, exclude_key="seal")
    if _sha256(seal["payload_sha256"], "seal.payload_sha256") != actual_payload_hash:
        _reject("HASH_MISMATCH", "day payload seal does not match")
    return {
        "day": bundle_day,
        "payload_sha256": actual_payload_hash,
        "bundle_sha256": hashlib.sha256(verified_raw_bytes).hexdigest(),
        "bundle_size_bytes": len(verified_raw_bytes),
    }


def validate_day_bundle(value: Any, *, raw_bytes: bytes) -> dict[str, Any]:
    return _validate_day_bundle(_V2_PROFILE, value, raw_bytes=raw_bytes)


def validate_v3_day_bundle(value: Any, *, raw_bytes: bytes) -> dict[str, Any]:
    return _validate_day_bundle(_V3_PROFILE, value, raw_bytes=raw_bytes)


def _validate_drop_envelope(
    profile: _ContractProfile,
    value: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    expected_diagnostic_id: str,
    expected_day: date,
    expected_predecessor_day: date | None,
    expected_predecessor_bundle_sha256: str | None,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    destination_existed: bool = False,
    existing_bundle_sha256: str | None = None,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    if historical_backfill_requested:
        _reject("BACKFILL_FORBIDDEN", "historical backfill is forbidden")
    if candle_chain_reuse_requested:
        _reject("CANDLE_CHAIN_REUSE_FORBIDDEN", "candle chain reuse is forbidden")
    if research_lifecycle_action_requested:
        _reject(
            "LIFECYCLE_CLOCK_FORBIDDEN",
            "producer cannot enqueue, select, retry, or time research actions",
        )
    if observed_producer_identity != "agora-evidence-source":
        _reject("WRONG_IDENTITY", "observed producer identity is not authorized")
    if source_path_is_symlink:
        _reject("SYMLINK_REJECT", "symlink delivery is forbidden")
    if overwrite_attempted:
        _reject("OVERWRITE_REJECT", "drop overwrite is forbidden")
    if not delivered_via_atomic_rename:
        _reject("NON_ATOMIC_DELIVERY", "drop must use atomic rename")

    envelope = _object(value, "drop envelope")
    verified_envelope_bytes = _verify_canonical_document_bytes(
        envelope, raw_envelope_bytes, "drop envelope"
    )
    _exact_keys(envelope, ENVELOPE_KEYS, "drop envelope")
    if envelope["schema_version"] != profile.drop_schema_version:
        _reject("CONTRACT_HASH_MISMATCH", "drop schema version changed")
    if envelope["envelope_type"] != "IMMUTABLE_ONE_WAY_MICROSTRUCTURE_DAY":
        _reject("CONTRACT_HASH_MISMATCH", "drop envelope type changed")
    if envelope["authorization"] != AUTHORIZATION:
        _reject("CONTRACT_HASH_MISMATCH", "drop authorization changed")
    if _diagnostic_id(envelope["diagnostic_id"]) != expected_diagnostic_id:
        _reject("CONTRACT_HASH_MISMATCH", "diagnostic binding changed")
    if envelope["source_contract_sha256"] != profile.source_contract_sha256:
        _reject("CONTRACT_HASH_MISMATCH", "source contract hash changed")
    if envelope["producer_identity"] != "agora-evidence-source":
        _reject("WRONG_IDENTITY", "declared producer identity is not authorized")
    if not isinstance(envelope["producer_release_id"], str) or not (
        1 <= len(envelope["producer_release_id"]) <= 128
    ):
        _reject("WRONG_IDENTITY", "producer release id is invalid")
    _sha256(envelope["producer_manifest_sha256"], "producer_manifest_sha256")

    envelope_day = _date(envelope["day"], "envelope.day")
    if envelope_day < expected_day:
        _reject("BACKFILL_FORBIDDEN", "a prior unaccepted day is backfill")
    if envelope_day > expected_day:
        _reject("NONCONTIGUOUS_DAY", "day skips the next expected UTC day")
    predecessor_day = (
        None
        if envelope["predecessor_day"] is None
        else _date(envelope["predecessor_day"], "predecessor_day")
    )
    predecessor_hash = envelope["predecessor_bundle_sha256"]
    if predecessor_hash is not None:
        _sha256(predecessor_hash, "predecessor_bundle_sha256")
    if (
        predecessor_day != expected_predecessor_day
        or predecessor_hash != expected_predecessor_bundle_sha256
    ):
        _reject("PREDECESSOR_MISMATCH", "predecessor binding does not match intake")

    validated_bundle = _validate_day_bundle(
        profile, bundle, raw_bytes=raw_bundle_bytes
    )
    if validated_bundle["day"] != expected_day:
        _reject("WRONG_DAY", "bundle day does not match envelope day")
    bundle_hash = validated_bundle["bundle_sha256"]
    if envelope["bundle_name"] != (
        f"okx-btc-usdt-microstructure-{expected_day.isoformat()}.json"
    ):
        _reject("WRONG_DAY", "bundle name does not match exact UTC day")
    if envelope["bundle_size_bytes"] != validated_bundle["bundle_size_bytes"]:
        _reject("HASH_MISMATCH", "bundle size does not match canonical delivered bytes")
    if _sha256(envelope["bundle_sha256"], "bundle_sha256") != bundle_hash:
        _reject("HASH_MISMATCH", "bundle hash does not match canonical delivered bytes")
    expected_idempotency = (
        f"{expected_diagnostic_id}:{expected_day.isoformat()}:{bundle_hash}"
    )
    if envelope["idempotency_key"] != expected_idempotency:
        _reject("HASH_MISMATCH", "idempotency key does not bind diagnostic/day/bytes")

    delivery = _object(envelope["delivery_semantics"], "delivery_semantics")
    _exact_keys(delivery, DELIVERY_KEYS, "delivery_semantics")
    if delivery != {
        "transport": "MICROSTRUCTURE_ONLY_ONE_WAY_DROP",
        "bundle_document_canonicalization": BUNDLE_DOCUMENT_CANONICALIZATION,
        "envelope_document_canonicalization": ENVELOPE_DOCUMENT_CANONICALIZATION,
        "atomic_rename": True,
        "overwrite": False,
        "source_read_after_publish": False,
        "symlinks": False,
        "canonical_state_access": False,
        "candle_chain_reuse": False,
    }:
        _reject("CONTRACT_HASH_MISMATCH", "one-way delivery semantics changed")

    published_at = _timestamp(envelope["published_at"], "published_at")
    bundle_sealed_at = _timestamp(bundle["seal"]["sealed_at"], "bundle.sealed_at")
    if published_at < bundle_sealed_at:
        _reject("NON_ATOMIC_DELIVERY", "publish time precedes bundle sealing")
    envelope_seal = _object(envelope["envelope_seal"], "envelope_seal")
    _exact_keys(envelope_seal, SEAL_KEYS, "envelope_seal")
    if (
        envelope_seal["algorithm"] != "SHA-256"
        or envelope_seal["canonicalization"] != ENVELOPE_CANONICALIZATION
    ):
        _reject("HASH_MISMATCH", "envelope seal contract changed")
    if _timestamp(envelope_seal["sealed_at"], "envelope_seal.sealed_at") < published_at:
        _reject("HASH_MISMATCH", "envelope seal precedes publication")
    payload_hash = canonical_sha256(envelope, exclude_key="envelope_seal")
    if _sha256(envelope_seal["payload_sha256"], "envelope payload hash") != payload_hash:
        _reject("HASH_MISMATCH", "envelope payload seal does not match")
    envelope_hash = hashlib.sha256(verified_envelope_bytes).hexdigest()

    status = "VALID_DROP_ENVELOPE"
    if destination_existed:
        if existing_bundle_sha256 != bundle_hash:
            _reject("CONFLICTING_DUPLICATE", "existing day has different bytes")
        status = "IDEMPOTENT_DUPLICATE"
    return {
        "status": status,
        "day": expected_day,
        "bundle_sha256": bundle_hash,
        "envelope_sha256": envelope_hash,
    }


def validate_drop_envelope(
    value: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    expected_diagnostic_id: str,
    expected_day: date,
    expected_predecessor_day: date | None,
    expected_predecessor_bundle_sha256: str | None,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    destination_existed: bool = False,
    existing_bundle_sha256: str | None = None,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    return _validate_drop_envelope(
        _V2_PROFILE,
        value,
        bundle,
        raw_envelope_bytes=raw_envelope_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        expected_diagnostic_id=expected_diagnostic_id,
        expected_day=expected_day,
        expected_predecessor_day=expected_predecessor_day,
        expected_predecessor_bundle_sha256=expected_predecessor_bundle_sha256,
        observed_producer_identity=observed_producer_identity,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        destination_existed=destination_existed,
        existing_bundle_sha256=existing_bundle_sha256,
        historical_backfill_requested=historical_backfill_requested,
        candle_chain_reuse_requested=candle_chain_reuse_requested,
        research_lifecycle_action_requested=research_lifecycle_action_requested,
    )


def validate_v3_drop_envelope(
    value: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    expected_diagnostic_id: str,
    expected_day: date,
    expected_predecessor_day: date | None,
    expected_predecessor_bundle_sha256: str | None,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    destination_existed: bool = False,
    existing_bundle_sha256: str | None = None,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    return _validate_drop_envelope(
        _V3_PROFILE,
        value,
        bundle,
        raw_envelope_bytes=raw_envelope_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        expected_diagnostic_id=expected_diagnostic_id,
        expected_day=expected_day,
        expected_predecessor_day=expected_predecessor_day,
        expected_predecessor_bundle_sha256=expected_predecessor_bundle_sha256,
        observed_producer_identity=observed_producer_identity,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        destination_existed=destination_existed,
        existing_bundle_sha256=existing_bundle_sha256,
        historical_backfill_requested=historical_backfill_requested,
        candle_chain_reuse_requested=candle_chain_reuse_requested,
        research_lifecycle_action_requested=research_lifecycle_action_requested,
    )


def _chain_hash(
    previous_chain_sha256: str,
    day_text: str,
    bundle_sha256: str,
    envelope_sha256: str,
) -> str:
    material = (
        previous_chain_sha256
        + "\n"
        + day_text
        + "\n"
        + bundle_sha256
        + "\n"
        + envelope_sha256
    ).encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def _readiness(disposition: str) -> dict[str, Any]:
    return {
        "disposition": disposition,
        "candidate_authorized": False,
        "oos_authorized": False,
        "pnl_claim_authorized": False,
        "promotion_authorized": False,
        "performance_value": "MISSING_PROOF",
    }


def _initial_intake_state(
    profile: _ContractProfile,
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> dict[str, Any]:
    _diagnostic_id(diagnostic_id)
    if start_day <= as_of_day:
        _reject("BACKFILL_FORBIDDEN", "start_day must be an untouched future UTC day")
    state = {
        "schema_version": profile.intake_schema_version,
        "state_type": profile.state_type,
        "authorization": AUTHORIZATION,
        "diagnostic_id": diagnostic_id,
        "source_contract_sha256": profile.source_contract_sha256,
        "drop_envelope_schema_sha256": profile.drop_envelope_schema_sha256,
        "day_schema_sha256": profile.day_schema_sha256,
        "diagnostic_contract_sha256": profile.diagnostic_contract_sha256,
        "state_authority": "SERVER_CANONICAL",
        "intake_identity": "agora-research",
        "network_access": "DENY",
        "historical_backfill": False,
        "candle_chain_reuse": False,
        "candle_trigger_reuse": False,
        "research_lifecycle_clock": "CODEX_CLOUD_OPS_ONLY",
        "cloud_schedule_count": 1,
        "chain_algorithm": CHAIN_ALGORITHM,
        "start_day": start_day.isoformat(),
        "required_day_count": REQUIRED_DAYS,
        "accepted_days": [],
        "status": "WAITING_FOR_DAY",
        "next_expected_day": start_day.isoformat(),
        "chain_head_sha256": None,
        "failure": None,
        "readiness": _readiness("NOT_READY"),
    }
    _validate_intake_state(profile, state)
    return state


def _validate_intake_state(
    profile: _ContractProfile, value: Any
) -> dict[str, Any]:
    state = _object(value, "intake state")
    _exact_keys(state, STATE_KEYS, "intake state")
    expected_constants = {
        "schema_version": profile.intake_schema_version,
        "state_type": profile.state_type,
        "authorization": AUTHORIZATION,
        "source_contract_sha256": profile.source_contract_sha256,
        "drop_envelope_schema_sha256": profile.drop_envelope_schema_sha256,
        "day_schema_sha256": profile.day_schema_sha256,
        "diagnostic_contract_sha256": profile.diagnostic_contract_sha256,
        "state_authority": "SERVER_CANONICAL",
        "intake_identity": "agora-research",
        "network_access": "DENY",
        "historical_backfill": False,
        "candle_chain_reuse": False,
        "candle_trigger_reuse": False,
        "research_lifecycle_clock": "CODEX_CLOUD_OPS_ONLY",
        "cloud_schedule_count": 1,
        "chain_algorithm": CHAIN_ALGORITHM,
        "required_day_count": REQUIRED_DAYS,
    }
    for key, expected in expected_constants.items():
        if state[key] != expected:
            code = (
                "CANDLE_CHAIN_REUSE_FORBIDDEN"
                if key in {"candle_chain_reuse", "candle_trigger_reuse"}
                else "LIFECYCLE_CLOCK_FORBIDDEN"
                if key in {"research_lifecycle_clock", "cloud_schedule_count"}
                else "CONTRACT_HASH_MISMATCH"
            )
            _reject(code, f"intake state {key} changed")
    _diagnostic_id(state["diagnostic_id"])
    start_day = _date(state["start_day"], "start_day")
    accepted_days = state["accepted_days"]
    if not isinstance(accepted_days, list) or len(accepted_days) > REQUIRED_DAYS:
        _reject("INCOMPLETE_DAY", "accepted_days must contain at most 14 days")

    previous_bundle: str | None = None
    previous_chain = ZERO_SHA256
    previous_accepted_at: datetime | None = None
    for index, raw_record in enumerate(accepted_days):
        record = _object(raw_record, f"accepted_days[{index}]")
        _exact_keys(record, ACCEPTED_DAY_KEYS, f"accepted_days[{index}]")
        expected_day = start_day + timedelta(days=index)
        if _date(record["day"], f"accepted_days[{index}].day") != expected_day:
            _reject("NONCONTIGUOUS_DAY", "accepted days are not exactly contiguous")
        bundle_hash = _sha256(record["bundle_sha256"], "accepted bundle hash")
        envelope_hash = _sha256(record["envelope_sha256"], "accepted envelope hash")
        predecessor = record["predecessor_bundle_sha256"]
        if predecessor is not None:
            _sha256(predecessor, "accepted predecessor hash")
        if predecessor != previous_bundle:
            _reject("PREDECESSOR_MISMATCH", "accepted predecessor chain changed")
        accepted_at = _timestamp(record["accepted_at"], "accepted_at")
        if previous_accepted_at is not None and accepted_at < previous_accepted_at:
            _reject("NONCONTIGUOUS_DAY", "acceptance timestamps regressed")
        expected_chain = _chain_hash(
            previous_chain,
            expected_day.isoformat(),
            bundle_hash,
            envelope_hash,
        )
        if record["cumulative_chain_sha256"] != expected_chain:
            _reject("HASH_MISMATCH", "cumulative intake chain changed")
        previous_bundle = bundle_hash
        previous_chain = expected_chain
        previous_accepted_at = accepted_at

    expected_head = None if not accepted_days else previous_chain
    if state["chain_head_sha256"] != expected_head:
        _reject("HASH_MISMATCH", "chain_head_sha256 does not match accepted days")

    readiness = _object(state["readiness"], "readiness")
    _exact_keys(readiness, STATE_READINESS_KEYS, "readiness")
    if any(
        readiness[key] is not False
        for key in (
            "candidate_authorized",
            "oos_authorized",
            "pnl_claim_authorized",
            "promotion_authorized",
        )
    ) or readiness["performance_value"] != "MISSING_PROOF":
        _reject("LIFECYCLE_CLOCK_FORBIDDEN", "readiness exceeds discovery authority")

    failure = state["failure"]
    if state["status"] == "INTEGRITY_BLOCKED":
        failure_obj = _object(failure, "failure")
        _exact_keys(failure_obj, FAILURE_KEYS, "failure")
        if failure_obj["code"] not in FAILURE_CODES:
            _reject("CONTRACT_HASH_MISMATCH", "unsupported failure code")
        _date(failure_obj["day"], "failure.day")
        if not isinstance(failure_obj["detail"], str) or not (
            1 <= len(failure_obj["detail"]) <= 500
        ):
            _reject("CONTRACT_HASH_MISMATCH", "failure detail is invalid")
        if state["next_expected_day"] is not None:
            _reject("INTEGRITY_NOT_CLEAN", "blocked state cannot expect another day")
        if readiness != _readiness("INTEGRITY_BLOCKED"):
            _reject("INTEGRITY_NOT_CLEAN", "blocked readiness is inconsistent")
    elif len(accepted_days) == REQUIRED_DAYS:
        if state["status"] != "DIAGNOSTIC_READY":
            _reject("INCOMPLETE_DAY", "14 accepted days must be DIAGNOSTIC_READY")
        if state["next_expected_day"] is not None or failure is not None:
            _reject("INTEGRITY_NOT_CLEAN", "ready state cannot expect or report failure")
        if readiness != _readiness(profile.ready_disposition):
            _reject(
                "LIFECYCLE_CLOCK_FORBIDDEN",
                (
                    "ready state exceeds V3 discovery"
                    if profile.v3_midline
                    else "ready state exceeds V2 discovery"
                ),
            )
    else:
        expected_next = (start_day + timedelta(days=len(accepted_days))).isoformat()
        if state["status"] != "WAITING_FOR_DAY":
            _reject("INCOMPLETE_DAY", "partial intake must wait for the next day")
        if state["next_expected_day"] != expected_next or failure is not None:
            _reject("NONCONTIGUOUS_DAY", "next expected day is inconsistent")
        if readiness != _readiness("NOT_READY"):
            _reject("LIFECYCLE_CLOCK_FORBIDDEN", "partial intake cannot be ready")
    return state


def _block_intake_state(
    profile: _ContractProfile,
    state: Any,
    *,
    code: str,
    day: date,
    detail: str,
) -> dict[str, Any]:
    _validate_intake_state(profile, state)
    if state["status"] != "WAITING_FOR_DAY":
        _reject("LIFECYCLE_CLOCK_FORBIDDEN", "only waiting intake can fail closed")
    if code not in FAILURE_CODES or not 1 <= len(detail) <= 500:
        _reject("CONTRACT_HASH_MISMATCH", "invalid fail-closed record")
    blocked = deepcopy(state)
    blocked["status"] = transition_intake("WAITING_FOR_DAY", "INTEGRITY_FAILURE")
    blocked["next_expected_day"] = None
    blocked["failure"] = {"code": code, "day": day.isoformat(), "detail": detail}
    blocked["readiness"] = _readiness("INTEGRITY_BLOCKED")
    _validate_intake_state(profile, blocked)
    return blocked


def _accept_intake_day(
    profile: _ContractProfile,
    state: Any,
    envelope: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    accepted_at: str,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    _validate_intake_state(profile, state)
    if state["status"] != "WAITING_FOR_DAY":
        _reject("LIFECYCLE_CLOCK_FORBIDDEN", "intake is terminal")
    envelope_obj = _object(envelope, "drop envelope")
    envelope_day = _date(envelope_obj.get("day"), "envelope.day")
    start_day = _date(state["start_day"], "start_day")
    expected_day = start_day + timedelta(days=len(state["accepted_days"]))

    if envelope_day < expected_day:
        index = (envelope_day - start_day).days
        if index < 0 or index >= len(state["accepted_days"]):
            _reject("BACKFILL_FORBIDDEN", "prior unaccepted day is backfill")
        existing = state["accepted_days"][index]
        previous = None if index == 0 else state["accepted_days"][index - 1]
        validated = _validate_drop_envelope(
            profile,
            envelope,
            bundle,
            raw_envelope_bytes=raw_envelope_bytes,
            raw_bundle_bytes=raw_bundle_bytes,
            expected_diagnostic_id=state["diagnostic_id"],
            expected_day=envelope_day,
            expected_predecessor_day=(
                None if previous is None else _date(previous["day"], "previous.day")
            ),
            expected_predecessor_bundle_sha256=(
                None if previous is None else previous["bundle_sha256"]
            ),
            observed_producer_identity=observed_producer_identity,
            delivered_via_atomic_rename=delivered_via_atomic_rename,
            source_path_is_symlink=source_path_is_symlink,
            overwrite_attempted=overwrite_attempted,
            destination_existed=True,
            existing_bundle_sha256=existing["bundle_sha256"],
            historical_backfill_requested=historical_backfill_requested,
            candle_chain_reuse_requested=candle_chain_reuse_requested,
            research_lifecycle_action_requested=research_lifecycle_action_requested,
        )
        if validated["envelope_sha256"] != existing["envelope_sha256"]:
            _reject("CONFLICTING_DUPLICATE", "duplicate envelope bytes changed")
        return deepcopy(state)
    if envelope_day > expected_day:
        _reject("NONCONTIGUOUS_DAY", "intake day skips the next expected day")

    previous_record = state["accepted_days"][-1] if state["accepted_days"] else None
    validated = _validate_drop_envelope(
        profile,
        envelope,
        bundle,
        raw_envelope_bytes=raw_envelope_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        expected_diagnostic_id=state["diagnostic_id"],
        expected_day=expected_day,
        expected_predecessor_day=(
            None if previous_record is None else _date(previous_record["day"], "previous.day")
        ),
        expected_predecessor_bundle_sha256=(
            None if previous_record is None else previous_record["bundle_sha256"]
        ),
        observed_producer_identity=observed_producer_identity,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        historical_backfill_requested=historical_backfill_requested,
        candle_chain_reuse_requested=candle_chain_reuse_requested,
        research_lifecycle_action_requested=research_lifecycle_action_requested,
    )
    accepted_timestamp = _timestamp(accepted_at, "accepted_at")
    if accepted_timestamp < _timestamp(envelope["envelope_seal"]["sealed_at"], "sealed_at"):
        _reject("NON_ATOMIC_DELIVERY", "intake acceptance precedes envelope seal")

    updated = deepcopy(state)
    previous_chain = updated["chain_head_sha256"] or ZERO_SHA256
    chain_hash = _chain_hash(
        previous_chain,
        expected_day.isoformat(),
        validated["bundle_sha256"],
        validated["envelope_sha256"],
    )
    updated["accepted_days"].append(
        {
            "day": expected_day.isoformat(),
            "bundle_sha256": validated["bundle_sha256"],
            "envelope_sha256": validated["envelope_sha256"],
            "predecessor_bundle_sha256": (
                None if previous_record is None else previous_record["bundle_sha256"]
            ),
            "accepted_at": accepted_at,
            "cumulative_chain_sha256": chain_hash,
        }
    )
    updated["chain_head_sha256"] = chain_hash
    if len(updated["accepted_days"]) == REQUIRED_DAYS:
        updated["status"] = transition_intake(
            "WAITING_FOR_DAY", "FOURTEENTH_DAY_ACCEPTED"
        )
        updated["next_expected_day"] = None
        updated["readiness"] = _readiness(profile.ready_disposition)
    else:
        updated["status"] = transition_intake("WAITING_FOR_DAY", "DAY_ACCEPTED")
        updated["next_expected_day"] = (
            expected_day + timedelta(days=1)
        ).isoformat()
    _validate_intake_state(profile, updated)
    return updated


def initial_intake_state(
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> dict[str, Any]:
    return _initial_intake_state(
        _V2_PROFILE, diagnostic_id, start_day, as_of_day=as_of_day
    )


def initial_v3_intake_state(
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> dict[str, Any]:
    return _initial_intake_state(
        _V3_PROFILE, diagnostic_id, start_day, as_of_day=as_of_day
    )


def validate_intake_state(value: Any) -> dict[str, Any]:
    return _validate_intake_state(_V2_PROFILE, value)


def validate_v3_intake_state(value: Any) -> dict[str, Any]:
    return _validate_intake_state(_V3_PROFILE, value)


def block_intake_state(
    state: Any,
    *,
    code: str,
    day: date,
    detail: str,
) -> dict[str, Any]:
    return _block_intake_state(
        _V2_PROFILE, state, code=code, day=day, detail=detail
    )


def block_v3_intake_state(
    state: Any,
    *,
    code: str,
    day: date,
    detail: str,
) -> dict[str, Any]:
    return _block_intake_state(
        _V3_PROFILE, state, code=code, day=day, detail=detail
    )


def accept_intake_day(
    state: Any,
    envelope: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    accepted_at: str,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    return _accept_intake_day(
        _V2_PROFILE,
        state,
        envelope,
        bundle,
        raw_envelope_bytes=raw_envelope_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        accepted_at=accepted_at,
        observed_producer_identity=observed_producer_identity,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        historical_backfill_requested=historical_backfill_requested,
        candle_chain_reuse_requested=candle_chain_reuse_requested,
        research_lifecycle_action_requested=research_lifecycle_action_requested,
    )


def accept_v3_intake_day(
    state: Any,
    envelope: Any,
    bundle: Any,
    *,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    accepted_at: str,
    observed_producer_identity: str,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    historical_backfill_requested: bool = False,
    candle_chain_reuse_requested: bool = False,
    research_lifecycle_action_requested: bool = False,
) -> dict[str, Any]:
    return _accept_intake_day(
        _V3_PROFILE,
        state,
        envelope,
        bundle,
        raw_envelope_bytes=raw_envelope_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        accepted_at=accepted_at,
        observed_producer_identity=observed_producer_identity,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        historical_backfill_requested=historical_backfill_requested,
        candle_chain_reuse_requested=candle_chain_reuse_requested,
        research_lifecycle_action_requested=research_lifecycle_action_requested,
    )
