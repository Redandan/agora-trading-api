from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SOURCE_LABEL = "LAGGED_OKX_BTC_USDT_EXPIRY_FUTURES_BASIS_ATOMS_V1"
REJECTED_SOURCE_LABEL = "LAGGED_OKX_BTC_USDT_PERPETUAL_FUNDING_STATE_V1"
SOURCE_CONTRACT_ID = "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_SOURCE_V1"
SOURCE_CONTRACT_SHA256 = (
    "0944ab401717360f6eccc31ab967461af7ebc8122f7b77ee7b1f46eaa8fac48e"
)
INVENTORY_SCHEMA_VERSION = (
    "OKX_DRA_CRYPTO_CARRY_EXPIRY_FUTURES_INVENTORY_V1"
)
INVENTORY_SCHEMA_SHA256 = (
    "8dd38f2ea2e73f236f56416aa1db86f6f82818ed7d8a9f69738b194c1965b340"
)
DAY_SCHEMA_VERSION = "OKX_DRA_CRYPTO_CARRY_BASIS_ATOMS_DAY_V1"
DAY_SCHEMA_SHA256 = (
    "1028d4b6f53cae6ad096038142173b650726fe744d358c2c16c7c57bc32dd8d8"
)
INVENTORY_SCHEMA_ID = (
    "https://agora.local/research/"
    "okx-dra-crypto-carry-expiry-futures-inventory.v1.schema.json"
)
DAY_SCHEMA_ID = (
    "https://agora.local/research/"
    "okx-dra-crypto-carry-basis-atoms-day.v1.schema.json"
)
INVENTORY_CANONICALIZATION = (
    "UTF-8 compact sorted-key JSON excluding inventory_seal"
)
DAY_CANONICALIZATION = "UTF-8 compact sorted-key JSON excluding day_seal"

PACKAGE_DIR = Path(__file__).resolve().parent
SOURCE_CONTRACT_PATH = (
    PACKAGE_DIR / "okx-dra-crypto-carry-expiry-futures-source-contract.v1.json"
)
INVENTORY_SCHEMA_PATH = (
    PACKAGE_DIR / "okx-dra-crypto-carry-expiry-futures-inventory.v1.schema.json"
)
DAY_SCHEMA_PATH = PACKAGE_DIR / "okx-dra-crypto-carry-basis-atoms-day.v1.schema.json"

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DAY_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
TIMESTAMP_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)
MILLIS_PATTERN = re.compile(r"^(?:0|[1-9][0-9]*)$")
EXPIRY_INST_ID_PATTERN = re.compile(r"^BTC-USDT-[0-9]{6}$")

INVENTORY_REQUEST = {
    "method": "GET",
    "origin": "https://www.okx.com",
    "path": "/api/v5/public/instruments",
    "query": {"instType": "FUTURES", "instFamily": "BTC-USDT"},
    "credentials": "NONE",
}
DAY_REQUESTS = {
    "futures": {
        "method": "GET",
        "origin": "https://www.okx.com",
        "path": "/api/v5/market/candles",
        "instId": "INVENTORY_DERIVED_ONLY",
        "bar": "1Dutc",
        "credentials": "NONE",
    },
    "index": {
        "method": "GET",
        "origin": "https://www.okx.com",
        "path": "/api/v5/market/index-candles",
        "instId": "BTC-USDT",
        "bar": "1Dutc",
        "credentials": "NONE",
    },
}

INVENTORY_KEYS = {
    "schema_version",
    "document_type",
    "authorization",
    "source_label",
    "source_contract_sha256",
    "target_day",
    "scheduled_cycle_at",
    "captured_at",
    "request",
    "inventory_count",
    "instruments",
    "inventory_seal",
}
INVENTORY_PAYLOAD_KEYS = INVENTORY_KEYS - {"inventory_seal"}
INSTRUMENT_KEYS = {
    "instId",
    "instType",
    "instFamily",
    "uly",
    "ctType",
    "settleCcy",
    "state",
    "ruleType",
    "listTime",
    "expTime",
}
DAY_KEYS = {
    "schema_version",
    "document_type",
    "authorization",
    "source_label",
    "source_contract_sha256",
    "inventory_schema_sha256",
    "day_schema_sha256",
    "inventory_sha256",
    "target_day",
    "scheduled_cycle_at",
    "captured_at",
    "first_eligible_utc_decision_day",
    "requests",
    "expected_instrument_count",
    "observed_instrument_count",
    "cache_order_semantics",
    "futures",
    "index",
    "eligibility",
    "day_seal",
}
DAY_PAYLOAD_KEYS = DAY_KEYS - {"day_seal"}
ATOM_KEYS = {"instId", "row"}
ELIGIBILITY_KEYS = {
    "target_day_use",
    "d_plus_1_use",
    "first_eligible_utc_decision_day",
    "retroactive_admission",
    "late_retry",
    "backfill",
    "partial_day_salvage",
}
SEAL_KEYS = {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}


class ContractViolation(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _reject(code: str, message: str) -> None:
    raise ContractViolation(code, message)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _reject("CONTRACT_MISMATCH", f"{label} must be an object")
    return value


def _array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        _reject("CONTRACT_MISMATCH", f"{label} must be an array")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        _reject(
            "CONTRACT_MISMATCH",
            f"{label} keys mismatch: missing={missing} extra={extra}",
        )


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        _reject("HASH_MISMATCH", f"{label} must be a lowercase SHA-256")
    return value


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str) or DAY_PATTERN.fullmatch(value) is None:
        _reject("CLOCK_DRIFT", f"{label} must use YYYY-MM-DD")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", f"{label} is invalid") from error
    if parsed.isoformat() != value:
        _reject("CLOCK_DRIFT", f"{label} is not canonical")
    return parsed


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or TIMESTAMP_PATTERN.fullmatch(value) is None:
        _reject("CLOCK_DRIFT", f"{label} must use second-precision UTC Z form")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", f"{label} is invalid") from error
    return parsed.replace(tzinfo=timezone.utc)


def _utc_start(day_value: date) -> datetime:
    return datetime.combine(day_value, time.min, tzinfo=timezone.utc)


def _epoch_millis(value: datetime) -> int:
    return int(value.timestamp()) * 1000


def _millis(value: Any, label: str) -> int:
    if not isinstance(value, str) or MILLIS_PATTERN.fullmatch(value) is None:
        _reject("INVENTORY_INVALID", f"{label} must be an integer millisecond string")
    return int(value)


def _decimal(value: Any, label: str, *, positive: bool) -> Decimal:
    if not isinstance(value, str) or value == "":
        _reject("DAY_ROW_INVALID", f"{label} must be an exact decimal string")
    try:
        parsed = Decimal(value)
    except InvalidOperation as error:
        raise ContractViolation(
            "DAY_ROW_INVALID", f"{label} must be a decimal string"
        ) from error
    if not parsed.is_finite():
        _reject("DAY_ROW_INVALID", f"{label} must be finite")
    if positive and parsed <= 0:
        _reject("DAY_ROW_INVALID", f"{label} must be positive")
    if not positive and parsed < 0:
        _reject("DAY_ROW_INVALID", f"{label} must be nonnegative")
    return parsed


def canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractViolation(
            "NONCANONICAL_JSON", "value cannot be encoded as strict JSON"
        ) from error


def document_sha256(raw_bytes: bytes) -> str:
    if not isinstance(raw_bytes, bytes):
        _reject("HASH_MISMATCH", "raw bytes are required")
    return hashlib.sha256(raw_bytes).hexdigest()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _reject("DUPLICATE_JSON_KEY", f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json_bytes_strict(
    raw_bytes: bytes, label: str, *, require_canonical: bool
) -> dict[str, Any]:
    if not isinstance(raw_bytes, bytes):
        _reject("NONCANONICAL_JSON", f"{label} raw bytes are required")
    try:
        text = raw_bytes.decode("utf-8")
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=lambda token: _reject(
                "NONCANONICAL_JSON", f"non-finite JSON number: {token}"
            ),
        )
    except ContractViolation:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ContractViolation(
            "NONCANONICAL_JSON", f"{label} is not strict UTF-8 JSON"
        ) from error
    document = _object(value, label)
    if require_canonical and raw_bytes != canonical_json_bytes(document):
        _reject(
            "NONCANONICAL_JSON",
            f"{label} must be compact UTF-8 sorted-key canonical JSON",
        )
    return document


def _load_frozen(path: Path, label: str) -> dict[str, Any]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise ContractViolation("FROZEN_FILE_MISMATCH", f"cannot read {label}") from error
    return load_json_bytes_strict(raw, label, require_canonical=False)


def validate_frozen_files() -> dict[str, str]:
    hashes = {
        "source_contract_sha256": file_sha256(SOURCE_CONTRACT_PATH),
        "inventory_schema_sha256": file_sha256(INVENTORY_SCHEMA_PATH),
        "day_schema_sha256": file_sha256(DAY_SCHEMA_PATH),
    }
    expected = {
        "source_contract_sha256": SOURCE_CONTRACT_SHA256,
        "inventory_schema_sha256": INVENTORY_SCHEMA_SHA256,
        "day_schema_sha256": DAY_SCHEMA_SHA256,
    }
    if hashes != expected:
        _reject("FROZEN_FILE_MISMATCH", "frozen contract or schema hash changed")

    source = _load_frozen(SOURCE_CONTRACT_PATH, "source contract")
    if source.get("contract_id") != SOURCE_CONTRACT_ID:
        _reject("FROZEN_FILE_MISMATCH", "source contract identity changed")
    if source.get("document_status") != "OFFLINE_DISABLED_NOT_REGISTERED":
        _reject("FROZEN_FILE_MISMATCH", "source contract is not disabled")
    if source.get("selected_source_label") != SOURCE_LABEL:
        _reject("FROZEN_FILE_MISMATCH", "selected source changed")
    if source.get("rejected_source_labels") != [REJECTED_SOURCE_LABEL]:
        _reject("FROZEN_FILE_MISMATCH", "rejected source closure changed")
    if source.get("authorization") != AUTHORIZATION:
        _reject("FROZEN_FILE_MISMATCH", "authorization changed")
    schemas = _object(source.get("schemas"), "source schemas")
    if _object(schemas.get("inventory"), "inventory schema binding") != {
        "path": (
            "research_pipeline/"
            "okx-dra-crypto-carry-expiry-futures-inventory.v1.schema.json"
        ),
        "id": INVENTORY_SCHEMA_ID,
        "sha256": INVENTORY_SCHEMA_SHA256,
    }:
        _reject("FROZEN_FILE_MISMATCH", "inventory schema binding changed")
    if _object(schemas.get("day"), "day schema binding") != {
        "path": "research_pipeline/okx-dra-crypto-carry-basis-atoms-day.v1.schema.json",
        "id": DAY_SCHEMA_ID,
        "sha256": DAY_SCHEMA_SHA256,
    }:
        _reject("FROZEN_FILE_MISMATCH", "day schema binding changed")

    inventory_schema = _load_frozen(INVENTORY_SCHEMA_PATH, "inventory schema")
    day_schema = _load_frozen(DAY_SCHEMA_PATH, "day schema")
    for schema, schema_id, label in (
        (inventory_schema, INVENTORY_SCHEMA_ID, "inventory schema"),
        (day_schema, DAY_SCHEMA_ID, "day schema"),
    ):
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            _reject("FROZEN_FILE_MISMATCH", f"{label} dialect changed")
        if schema.get("$id") != schema_id:
            _reject("FROZEN_FILE_MISMATCH", f"{label} id changed")
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            _reject("FROZEN_FILE_MISMATCH", f"{label} is not closed")
    return hashes


def _validate_instrument(
    value: Any, *, captured_at: datetime, target_end: datetime
) -> dict[str, Any]:
    instrument = _object(value, "instrument")
    _exact_keys(instrument, INSTRUMENT_KEYS, "instrument")
    inst_id = instrument["instId"]
    if not isinstance(inst_id, str) or EXPIRY_INST_ID_PATTERN.fullmatch(inst_id) is None:
        _reject("INVENTORY_INVALID", "instId must be an exact BTC-USDT expiry FUTURES id")
    expected = {
        "instType": "FUTURES",
        "instFamily": "BTC-USDT",
        "uly": "BTC-USDT",
        "ctType": "linear",
        "settleCcy": "USDT",
        "state": "live",
        "ruleType": "normal",
    }
    for key, required in expected.items():
        if instrument[key] != required:
            _reject("INVENTORY_INVALID", f"instrument {key} changed")
    list_time = _millis(instrument["listTime"], "listTime")
    expiry_time = _millis(instrument["expTime"], "expTime")
    if list_time > _epoch_millis(captured_at):
        _reject("INVENTORY_INVALID", "listTime is after inventory capture")
    if expiry_time <= _epoch_millis(target_end):
        _reject("INVENTORY_INVALID", "expTime is not strictly after target day end")
    return instrument


def _validate_inventory_payload(
    payload: dict[str, Any], hashes: dict[str, str], *, require_sorted: bool
) -> tuple[date, datetime, tuple[str, ...]]:
    _exact_keys(payload, INVENTORY_PAYLOAD_KEYS, "inventory payload")
    expected_scalars = {
        "schema_version": INVENTORY_SCHEMA_VERSION,
        "document_type": "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY",
        "authorization": AUTHORIZATION,
        "source_label": SOURCE_LABEL,
        "source_contract_sha256": hashes["source_contract_sha256"],
    }
    for key, expected in expected_scalars.items():
        if payload[key] != expected:
            _reject("SOURCE_HASH_MISMATCH", f"inventory {key} changed")
    if payload["request"] != INVENTORY_REQUEST:
        _reject("SOURCE_IDENTITY_MISMATCH", "inventory request identity changed")

    target_day = _day(payload["target_day"], "target_day")
    target_start = _utc_start(target_day)
    target_end = target_start + timedelta(days=1)
    scheduled = _timestamp(payload["scheduled_cycle_at"], "scheduled_cycle_at")
    captured = _timestamp(payload["captured_at"], "captured_at")
    expected_schedule = target_start - timedelta(days=1) + timedelta(hours=1, minutes=5)
    if scheduled != expected_schedule:
        _reject("CLOCK_DRIFT", "inventory schedule must be D-1 09:05 Asia/Taipei")
    if captured < scheduled or captured >= target_start:
        _reject("CLOCK_DRIFT", "inventory capture is outside the D-1 causal window")

    instruments = _array(payload["instruments"], "instruments")
    count = payload["inventory_count"]
    if isinstance(count, bool) or not isinstance(count, int) or count < 1:
        _reject("INVENTORY_INVALID", "inventory_count must be a positive integer")
    if count != len(instruments) or not instruments:
        _reject("INVENTORY_INVALID", "inventory_count does not match nonempty rows")
    inst_ids: list[str] = []
    for item in instruments:
        instrument = _validate_instrument(
            item, captured_at=captured, target_end=target_end
        )
        inst_ids.append(instrument["instId"])
    if len(inst_ids) != len(set(inst_ids)):
        _reject("INVENTORY_INVALID", "duplicate or conflicting instId")
    if require_sorted and inst_ids != sorted(inst_ids):
        _reject("NONCANONICAL_JSON", "inventory rows must be sorted by instId")
    return target_day, captured, tuple(sorted(inst_ids))


def _seal_payload(
    payload: dict[str, Any], *, seal_key: str, canonicalization: str, sealed_at: str
) -> bytes:
    document = deepcopy(payload)
    document[seal_key] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(canonical_json_bytes(payload)).hexdigest(),
        "canonicalization": canonicalization,
        "sealed_at": sealed_at,
    }
    return canonical_json_bytes(document)


def _verify_seal(
    document: dict[str, Any], *, seal_key: str, canonicalization: str, captured_at: str
) -> None:
    seal = _object(document[seal_key], seal_key)
    _exact_keys(seal, SEAL_KEYS, seal_key)
    if seal != {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(
                {key: value for key, value in document.items() if key != seal_key}
            )
        ).hexdigest(),
        "canonicalization": canonicalization,
        "sealed_at": captured_at,
    }:
        _reject("HASH_MISMATCH", f"{seal_key} does not seal the exact payload")


def seal_inventory_document(value: Any) -> bytes:
    hashes = validate_frozen_files()
    payload = deepcopy(_object(value, "inventory payload"))
    _validate_inventory_payload(payload, hashes, require_sorted=False)
    payload["instruments"] = sorted(
        payload["instruments"], key=lambda item: item["instId"]
    )
    _validate_inventory_payload(payload, hashes, require_sorted=True)
    return _seal_payload(
        payload,
        seal_key="inventory_seal",
        canonicalization=INVENTORY_CANONICALIZATION,
        sealed_at=payload["captured_at"],
    )


def validate_inventory_bytes(raw_bytes: bytes) -> dict[str, Any]:
    hashes = validate_frozen_files()
    document = load_json_bytes_strict(
        raw_bytes, "inventory document", require_canonical=True
    )
    _exact_keys(document, INVENTORY_KEYS, "inventory document")
    payload = {key: value for key, value in document.items() if key != "inventory_seal"}
    _validate_inventory_payload(payload, hashes, require_sorted=True)
    _verify_seal(
        document,
        seal_key="inventory_seal",
        canonicalization=INVENTORY_CANONICALIZATION,
        captured_at=document["captured_at"],
    )
    return deepcopy(document)


def _validate_ohlc(row: list[Any], label: str) -> None:
    opened = _decimal(row[1], f"{label}.o", positive=True)
    high = _decimal(row[2], f"{label}.h", positive=True)
    low = _decimal(row[3], f"{label}.l", positive=True)
    closed = _decimal(row[4], f"{label}.c", positive=True)
    if high < max(opened, low, closed) or low > min(opened, high, closed):
        _reject("DAY_ROW_INVALID", f"{label} OHLC ordering is invalid")


def _validate_futures_row(row_value: Any, expected_ts: str, label: str) -> list[Any]:
    row = _array(row_value, f"{label}.row")
    if len(row) != 9 or any(not isinstance(item, str) for item in row):
        _reject("DAY_ROW_INVALID", f"{label} must preserve the exact 9-string row")
    if row[0] != expected_ts or row[8] != "1":
        _reject("DAY_ROW_INVALID", f"{label} timestamp or confirm changed")
    _validate_ohlc(row, label)
    for index, name in ((5, "vol"), (6, "volCcy"), (7, "volCcyQuote")):
        _decimal(row[index], f"{label}.{name}", positive=False)
    return row


def _validate_index_row(row_value: Any, expected_ts: str) -> list[Any]:
    row = _array(row_value, "index.row")
    if len(row) != 6 or any(not isinstance(item, str) for item in row):
        _reject("DAY_ROW_INVALID", "index must preserve the exact 6-string row")
    if row[0] != expected_ts or row[5] != "1":
        _reject("DAY_ROW_INVALID", "index timestamp or confirm changed")
    _validate_ohlc(row, "index")
    return row


def _validate_day_payload(
    payload: dict[str, Any],
    inventory: dict[str, Any],
    inventory_raw: bytes,
    hashes: dict[str, str],
    *,
    require_sorted: bool,
) -> tuple[date, tuple[str, ...]]:
    _exact_keys(payload, DAY_PAYLOAD_KEYS, "day payload")
    expected_scalars = {
        "schema_version": DAY_SCHEMA_VERSION,
        "document_type": "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS",
        "authorization": AUTHORIZATION,
        "source_label": SOURCE_LABEL,
        "source_contract_sha256": hashes["source_contract_sha256"],
        "inventory_schema_sha256": hashes["inventory_schema_sha256"],
        "day_schema_sha256": hashes["day_schema_sha256"],
        "inventory_sha256": document_sha256(inventory_raw),
        "cache_order_semantics": (
            "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID"
        ),
    }
    for key, expected in expected_scalars.items():
        if payload[key] != expected:
            _reject("HASH_MISMATCH", f"day {key} changed")
    if payload["requests"] != DAY_REQUESTS:
        _reject("SOURCE_IDENTITY_MISMATCH", "day request identity changed")

    target_day = _day(payload["target_day"], "target_day")
    if payload["target_day"] != inventory["target_day"]:
        _reject("CLOCK_DRIFT", "day does not match frozen inventory target")
    target_start = _utc_start(target_day)
    scheduled = _timestamp(payload["scheduled_cycle_at"], "scheduled_cycle_at")
    captured = _timestamp(payload["captured_at"], "captured_at")
    expected_schedule = target_start + timedelta(days=1, hours=1, minutes=5)
    deadline = target_start + timedelta(days=1, hours=6)
    if scheduled != expected_schedule:
        _reject("CLOCK_DRIFT", "day capture schedule must be D+1 09:05 Asia/Taipei")
    if captured < scheduled or captured >= deadline:
        _reject("CLOCK_DRIFT", "day capture is outside the D+1 deadline")
    eligible_day = target_day + timedelta(days=2)
    if payload["first_eligible_utc_decision_day"] != eligible_day.isoformat():
        _reject("LEAKAGE", "first eligibility must be the D+2 UTC decision")
    if payload["eligibility"] != {
        "target_day_use": "DENY_LEAKAGE",
        "d_plus_1_use": "DENY_CAPTURE_AFTER_DECISION",
        "first_eligible_utc_decision_day": eligible_day.isoformat(),
        "retroactive_admission": "DENY",
        "late_retry": "DENY",
        "backfill": "DENY",
        "partial_day_salvage": "DENY",
    }:
        _reject("LEAKAGE", "eligibility boundary changed")

    frozen_ids = tuple(item["instId"] for item in inventory["instruments"])
    expected_count = payload["expected_instrument_count"]
    observed_count = payload["observed_instrument_count"]
    for value, label in (
        (expected_count, "expected_instrument_count"),
        (observed_count, "observed_instrument_count"),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            _reject("INVENTORY_COVERAGE_MISMATCH", f"{label} is invalid")
    futures = _array(payload["futures"], "futures")
    if expected_count != len(frozen_ids) or observed_count != len(futures):
        _reject("INVENTORY_COVERAGE_MISMATCH", "instrument counts changed")
    expected_ts = str(_epoch_millis(target_start))
    observed_ids: list[str] = []
    for position, item_value in enumerate(futures):
        item = _object(item_value, f"futures[{position}]")
        _exact_keys(item, ATOM_KEYS, f"futures[{position}]")
        inst_id = item["instId"]
        if not isinstance(inst_id, str):
            _reject("INVENTORY_COVERAGE_MISMATCH", "futures instId must be a string")
        _validate_futures_row(item["row"], expected_ts, f"futures[{inst_id}]")
        observed_ids.append(inst_id)
    if len(observed_ids) != len(set(observed_ids)):
        _reject("INVENTORY_COVERAGE_MISMATCH", "duplicate futures instId")
    if set(observed_ids) != set(frozen_ids):
        _reject("INVENTORY_COVERAGE_MISMATCH", "missing or extra futures row")
    if require_sorted and observed_ids != sorted(observed_ids):
        _reject("NONCANONICAL_JSON", "futures rows must be sorted by frozen instId")

    index = _object(payload["index"], "index")
    _exact_keys(index, ATOM_KEYS, "index")
    if index["instId"] != "BTC-USDT":
        _reject("SOURCE_IDENTITY_MISMATCH", "index identity changed")
    _validate_index_row(index["row"], expected_ts)
    return target_day, tuple(sorted(observed_ids))


def seal_day_document(value: Any, inventory_raw: bytes) -> bytes:
    hashes = validate_frozen_files()
    inventory = validate_inventory_bytes(inventory_raw)
    payload = deepcopy(_object(value, "day payload"))
    _validate_day_payload(
        payload, inventory, inventory_raw, hashes, require_sorted=False
    )
    payload["futures"] = sorted(
        payload["futures"], key=lambda item: item["instId"]
    )
    _validate_day_payload(
        payload, inventory, inventory_raw, hashes, require_sorted=True
    )
    return _seal_payload(
        payload,
        seal_key="day_seal",
        canonicalization=DAY_CANONICALIZATION,
        sealed_at=payload["captured_at"],
    )


def validate_day_bundle_bytes(
    raw_bytes: bytes, inventory_raw: bytes
) -> dict[str, Any]:
    hashes = validate_frozen_files()
    inventory = validate_inventory_bytes(inventory_raw)
    document = load_json_bytes_strict(
        raw_bytes, "day document", require_canonical=True
    )
    _exact_keys(document, DAY_KEYS, "day document")
    payload = {key: value for key, value in document.items() if key != "day_seal"}
    _validate_day_payload(
        payload, inventory, inventory_raw, hashes, require_sorted=True
    )
    _verify_seal(
        document,
        seal_key="day_seal",
        canonicalization=DAY_CANONICALIZATION,
        captured_at=document["captured_at"],
    )
    return deepcopy(document)


def require_eligible_decision_day(day_document: Any, decision_day: str) -> None:
    document = _object(day_document, "validated day document")
    expected = document.get("first_eligible_utc_decision_day")
    actual = _day(decision_day, "decision_day").isoformat()
    if actual != expected:
        _reject(
            "LEAKAGE",
            "raw atoms may be used only at their exact D+2 UTC decision",
        )
