from __future__ import annotations

from copy import deepcopy
import csv
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
from typing import Any
from zoneinfo import ZoneInfo


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SOURCE_LABEL = "CFTC_CME_BITCOIN_TFF_FUTURES_ONLY_V1"
SOURCE_CONTRACT_ID = "CFTC_CME_BITCOIN_TFF_SOURCE_CONTRACT_V1"
SOURCE_CONTRACT_SHA256 = (
    "9be18251e3b74fbc4d6f4ef6147e26900e18092ee526cefb16c8b107841153d8"
)
SOURCE_CONTRACT_SCHEMA_SHA256 = (
    "53d803202839c2b7d6882544289f2db4c03337159d0a2da8f3a0d913afb6ece3"
)
OBSERVATION_SCHEMA_SHA256 = (
    "91630c4a2c7275a766e7f9b3d3036ba69598098ed07c08fa8c4df5b629aa7e82"
)
OBSERVATION_SCHEMA_VERSION = "CFTC_CME_BITCOIN_TFF_OBSERVATION_V1"
DOCUMENT_TYPE = "OFFLINE_CFTC_TFF_SOURCE_EVALUATION"
MARKET_NAME = "BITCOIN - CHICAGO MERCANTILE EXCHANGE"
CONTRACT_CODE = "133741"
REPORT_FAMILY_MARKER = "FutOnly"
GENESIS_SHA256 = "0" * 64

PACKAGE_DIR = Path(__file__).resolve().parent
SOURCE_CONTRACT_PATH = PACKAGE_DIR / "cftc-cme-bitcoin-tff-source-contract.v1.json"
SOURCE_CONTRACT_SCHEMA_PATH = (
    PACKAGE_DIR / "cftc-cme-bitcoin-tff-source-contract.v1.schema.json"
)
OBSERVATION_SCHEMA_PATH = (
    PACKAGE_DIR / "cftc-cme-bitcoin-tff-observation.v1.schema.json"
)

SOURCE_CONTRACT_SCHEMA_ID = (
    "https://agora.local/research/"
    "cftc-cme-bitcoin-tff-source-contract.v1.schema.json"
)
OBSERVATION_SCHEMA_ID = (
    "https://agora.local/research/"
    "cftc-cme-bitcoin-tff-observation.v1.schema.json"
)

STATES = (
    "NO_NEW_REPORT",
    "CONTRACT_ABSENT",
    "DUPLICATE_CONTRACT_ROW",
    "MALFORMED_REPORT",
    "WRONG_REPORT_FAMILY",
    "REPORT_DATE_IN_FUTURE",
    "RELEASE_NOT_DUE",
    "SOURCE_UNAVAILABLE",
    "SAME_REPORT_BYTES_DRIFT",
    "NEW_REPORT_SEALED",
)

ORDERED_FIELDS = (
    "Market_and_Exchange_Names",
    "As_of_Date_In_Form_YYMMDD",
    "Report_Date_as_MM_DD_YYYY",
    "CFTC_Contract_Market_Code",
    "CFTC_Market_Code",
    "CFTC_Region_Code",
    "CFTC_Commodity_Code",
    "Open_Interest_All",
    "Dealer_Positions_Long_All",
    "Dealer_Positions_Short_All",
    "Dealer_Positions_Spread_All",
    "Asset_Mgr_Positions_Long_All",
    "Asset_Mgr_Positions_Short_All",
    "Asset_Mgr_Positions_Spread_All",
    "Lev_Money_Positions_Long_All",
    "Lev_Money_Positions_Short_All",
    "Lev_Money_Positions_Spread_All",
    "Other_Rept_Positions_Long_All",
    "Other_Rept_Positions_Short_All",
    "Other_Rept_Positions_Spread_All",
    "Tot_Rept_Positions_Long_All",
    "Tot_Rept_Positions_Short_All",
    "NonRept_Positions_Long_All",
    "NonRept_Positions_Short_All",
    "Change_in_Open_Interest_All",
    "Change_in_Dealer_Long_All",
    "Change_in_Dealer_Short_All",
    "Change_in_Dealer_Spread_All",
    "Change_in_Asset_Mgr_Long_All",
    "Change_in_Asset_Mgr_Short_All",
    "Change_in_Asset_Mgr_Spread_All",
    "Change_in_Lev_Money_Long_All",
    "Change_in_Lev_Money_Short_All",
    "Change_in_Lev_Money_Spread_All",
    "Change_in_Other_Rept_Long_All",
    "Change_in_Other_Rept_Short_All",
    "Change_in_Other_Rept_Spread_All",
    "Change_in_Tot_Rept_Long_All",
    "Change_in_Tot_Rept_Short_All",
    "Change_in_NonRept_Long_All",
    "Change_in_NonRept_Short_All",
    "Pct_of_Open_Interest_All",
    "Pct_of_OI_Dealer_Long_All",
    "Pct_of_OI_Dealer_Short_All",
    "Pct_of_OI_Dealer_Spread_All",
    "Pct_of_OI_Asset_Mgr_Long_All",
    "Pct_of_OI_Asset_Mgr_Short_All",
    "Pct_of_OI_Asset_Mgr_Spread_All",
    "Pct_of_OI_Lev_Money_Long_All",
    "Pct_of_OI_Lev_Money_Short_All",
    "Pct_of_OI_Lev_Money_Spread_All",
    "Pct_of_OI_Other_Rept_Long_All",
    "Pct_of_OI_Other_Rept_Short_All",
    "Pct_of_OI_Other_Rept_Spread_All",
    "Pct_of_OI_Tot_Rept_Long_All",
    "Pct_of_OI_Tot_Rept_Short_All",
    "Pct_of_OI_NonRept_Long_All",
    "Pct_of_OI_NonRept_Short_All",
    "Traders_Tot_All",
    "Traders_Dealer_Long_All",
    "Traders_Dealer_Short_All",
    "Traders_Dealer_Spread_All",
    "Traders_Asset_Mgr_Long_All",
    "Traders_Asset_Mgr_Short_All",
    "Traders_Asset_Mgr_Spread_All",
    "Traders_Lev_Money_Long_All",
    "Traders_Lev_Money_Short_All",
    "Traders_Lev_Money_Spread_All",
    "Traders_Other_Rept_Long_All",
    "Traders_Other_Rept_Short_All",
    "Traders_Other_Rept_Spread_All",
    "Traders_Tot_Rept_Long_All",
    "Traders_Tot_Rept_Short_All",
    "Conc_Gross_LE_4_TDR_Long_All",
    "Conc_Gross_LE_4_TDR_Short_All",
    "Conc_Gross_LE_8_TDR_Long_All",
    "Conc_Gross_LE_8_TDR_Short_All",
    "Conc_Net_LE_4_TDR_Long_All",
    "Conc_Net_LE_4_TDR_Short_All",
    "Conc_Net_LE_8_TDR_Long_All",
    "Conc_Net_LE_8_TDR_Short_All",
    "Contract_Units",
    "CFTC_Contract_Market_Code_Quotes",
    "CFTC_Market_Code_Quotes",
    "CFTC_Commodity_Code_Quotes",
    "CFTC_SubGroup_Code",
    "FutOnly_or_Combined",
)

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
DAY_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
UTC_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)
OFFSET_RE = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}$"
)
VERSION_RE = re.compile(r"^[A-Z0-9][A-Z0-9_.-]{0,127}$")

TOP_KEYS = {
    "schema_version",
    "document_type",
    "authorization",
    "source_label",
    "source_contract_sha256",
    "source_contract_schema_sha256",
    "observation_schema_sha256",
    "state",
    "expected_report_date",
    "release_proof",
    "scheduled_cycle_at",
    "evaluated_at",
    "state_evidence",
}
RELEASE_KEYS = {
    "release_schedule_version",
    "release_schedule_sha256",
    "coverage_start",
    "coverage_end",
    "expected_tuesday",
    "release_at",
    "release_timezone",
}
PRIOR_KEYS = {
    "report_date",
    "raw_response_sha256",
    "selected_record_sha256",
    "canonical_row_sha256",
    "chain_sha256",
    "row_identity",
}
DECISION_KEYS = {
    "schedule_id",
    "schedule_version",
    "schedule_sha256",
    "decision_at",
}
ROW_IDENTITY_KEYS = {
    "market_and_exchange_names",
    "cftc_contract_market_code",
    "cftc_market_code",
    "cftc_commodity_code",
    "contract_units",
    "cftc_contract_market_code_quotes",
    "cftc_market_code_quotes",
    "cftc_commodity_code_quotes",
    "cftc_subgroup_code",
    "futonly_or_combined",
}


class ContractViolation(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class _ReportState(Exception):
    def __init__(self, state: str, evidence: dict[str, Any]) -> None:
        super().__init__(state)
        self.state = state
        self.evidence = evidence


def _reject(code: str, message: str) -> None:
    raise ContractViolation(code, message)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _reject("CONTRACT_MISMATCH", f"{label} must be an object")
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
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        _reject("HASH_MISMATCH", f"{label} must be a lowercase SHA-256")
    return value


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str) or DAY_RE.fullmatch(value) is None:
        _reject("CLOCK_DRIFT", f"{label} must use YYYY-MM-DD")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", f"{label} is invalid") from error
    if parsed.isoformat() != value:
        _reject("CLOCK_DRIFT", f"{label} is not canonical")
    return parsed


def _utc_timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or UTC_RE.fullmatch(value) is None:
        _reject("CLOCK_DRIFT", f"{label} must use second-precision UTC Z form")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", f"{label} is invalid") from error
    return parsed.replace(tzinfo=timezone.utc)


def _format_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


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
        value = json.loads(
            raw_bytes.decode("utf-8"),
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


def validate_frozen_package() -> dict[str, str]:
    hashes = {
        "source_contract_sha256": file_sha256(SOURCE_CONTRACT_PATH),
        "source_contract_schema_sha256": file_sha256(SOURCE_CONTRACT_SCHEMA_PATH),
        "observation_schema_sha256": file_sha256(OBSERVATION_SCHEMA_PATH),
    }
    expected = {
        "source_contract_sha256": SOURCE_CONTRACT_SHA256,
        "source_contract_schema_sha256": SOURCE_CONTRACT_SCHEMA_SHA256,
        "observation_schema_sha256": OBSERVATION_SCHEMA_SHA256,
    }
    if hashes != expected:
        _reject("FROZEN_FILE_MISMATCH", "frozen contract or schema hash changed")

    source = _load_frozen(SOURCE_CONTRACT_PATH, "source contract")
    if source.get("contract_id") != SOURCE_CONTRACT_ID:
        _reject("FROZEN_FILE_MISMATCH", "source contract identity changed")
    if source.get("document_status") != "OFFLINE_DISABLED_NOT_REGISTERED":
        _reject("FROZEN_FILE_MISMATCH", "source contract is not disabled")
    if source.get("source_label") != SOURCE_LABEL:
        _reject("FROZEN_FILE_MISMATCH", "source label changed")
    if source.get("authorization") != AUTHORIZATION:
        _reject("FROZEN_FILE_MISMATCH", "authorization changed")
    if tuple(source.get("ordered_fields", ())) != ORDERED_FIELDS:
        _reject("FROZEN_FILE_MISMATCH", "ordered 87-field contract changed")
    if tuple(_object(source.get("state_machine"), "state machine").get("states", ())) != STATES:
        _reject("FROZEN_FILE_MISMATCH", "ten-state contract changed")
    identity = _object(source.get("source_identity"), "source identity")
    if identity.get("market_and_exchange_name") != MARKET_NAME:
        _reject("FROZEN_FILE_MISMATCH", "market identity changed")
    if identity.get("cftc_contract_market_code") != CONTRACT_CODE:
        _reject("FROZEN_FILE_MISMATCH", "contract code changed")
    if identity.get("report_variant") != "FUTURES_ONLY":
        _reject("FROZEN_FILE_MISMATCH", "report variant changed")
    schemas = _object(source.get("schemas"), "schema bindings")
    if schemas != {
        "contract": {
            "path": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v1.schema.json",
            "id": SOURCE_CONTRACT_SCHEMA_ID,
            "sha256": SOURCE_CONTRACT_SCHEMA_SHA256,
        },
        "observation": {
            "path": "research_pipeline/cftc-cme-bitcoin-tff-observation.v1.schema.json",
            "id": OBSERVATION_SCHEMA_ID,
            "sha256": OBSERVATION_SCHEMA_SHA256,
        },
    }:
        _reject("FROZEN_FILE_MISMATCH", "schema bindings changed")

    for schema_path, schema_id, label in (
        (SOURCE_CONTRACT_SCHEMA_PATH, SOURCE_CONTRACT_SCHEMA_ID, "contract schema"),
        (OBSERVATION_SCHEMA_PATH, OBSERVATION_SCHEMA_ID, "observation schema"),
    ):
        schema = _load_frozen(schema_path, label)
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            _reject("FROZEN_FILE_MISMATCH", f"{label} dialect changed")
        if schema.get("$id") != schema_id:
            _reject("FROZEN_FILE_MISMATCH", f"{label} id changed")
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            _reject("FROZEN_FILE_MISMATCH", f"{label} root is not closed")
    return hashes


def _validate_release_context(
    release_proof_value: Any,
    scheduled_cycle_at_value: str,
    evaluated_at_value: str,
) -> tuple[dict[str, Any], date, datetime, datetime, datetime]:
    proof = deepcopy(_object(release_proof_value, "release_proof"))
    _exact_keys(proof, RELEASE_KEYS, "release_proof")
    version = proof["release_schedule_version"]
    if not isinstance(version, str) or VERSION_RE.fullmatch(version) is None:
        _reject("RELEASE_PROOF_INVALID", "release schedule version is invalid")
    _sha256(proof["release_schedule_sha256"], "release_schedule_sha256")
    coverage_start = _day(proof["coverage_start"], "coverage_start")
    coverage_end = _day(proof["coverage_end"], "coverage_end")
    expected_tuesday = _day(proof["expected_tuesday"], "expected_tuesday")
    if coverage_start > expected_tuesday or expected_tuesday > coverage_end:
        _reject("RELEASE_PROOF_INVALID", "expected Tuesday is outside schedule coverage")
    if expected_tuesday.weekday() != 1:
        _reject("CLOCK_DRIFT", "expected report date must be Tuesday")
    if proof["release_timezone"] != "America/New_York":
        _reject("CLOCK_DRIFT", "release timezone changed")
    release_value = proof["release_at"]
    if not isinstance(release_value, str) or OFFSET_RE.fullmatch(release_value) is None:
        _reject("CLOCK_DRIFT", "release_at must be offset-aware second precision")
    try:
        release_at = datetime.fromisoformat(release_value)
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", "release_at is invalid") from error
    new_york = ZoneInfo("America/New_York")
    release_local = release_at.astimezone(new_york)
    if (
        release_local.strftime("%Y-%m-%dT%H:%M:%S%z")
        != release_at.strftime("%Y-%m-%dT%H:%M:%S%z")
        or release_local.timetz().replace(tzinfo=None) != time(15, 30)
    ):
        _reject("CLOCK_DRIFT", "release_at is not 15:30 America/New_York")
    lag_days = (release_local.date() - expected_tuesday).days
    if lag_days < 3 or lag_days > 6:
        _reject("CLOCK_DRIFT", "release must be 3-6 calendar days after Tuesday")

    taipei = ZoneInfo("Asia/Taipei")
    release_taipei = release_at.astimezone(taipei)
    scheduled_local = datetime.combine(
        release_taipei.date(), time(9, 5), tzinfo=taipei
    )
    if scheduled_local <= release_taipei:
        scheduled_local += timedelta(days=1)
    scheduled = _utc_timestamp(scheduled_cycle_at_value, "scheduled_cycle_at")
    if scheduled != scheduled_local.astimezone(timezone.utc):
        _reject("CLOCK_DRIFT", "scheduled cycle is not the first 09:05 Asia/Taipei after release")
    evaluated = _utc_timestamp(evaluated_at_value, "evaluated_at")
    next_cycle = (scheduled_local + timedelta(days=1)).astimezone(timezone.utc)
    if evaluated >= next_cycle:
        _reject("CLOCK_DRIFT", "evaluation reached the next cloud cycle")
    return proof, expected_tuesday, release_at, scheduled, evaluated


def _raw_seal(raw_response: bytes) -> dict[str, Any]:
    return {
        "raw_response_size_bytes": len(raw_response),
        "raw_response_sha256": hashlib.sha256(raw_response).hexdigest(),
    }


def _record_terminator(record: bytes) -> str:
    if record.endswith(b"\r\n"):
        return "CRLF"
    if record.endswith(b"\n"):
        return "LF"
    if record.endswith(b"\r"):
        return "CR"
    return "NONE"


def _record_content(record: bytes) -> bytes:
    if record.endswith(b"\r\n"):
        return record[:-2]
    if record.endswith((b"\r", b"\n")):
        return record[:-1]
    return record


def _parse_csv_record(record: bytes) -> list[str]:
    try:
        text = _record_content(record).decode("utf-8")
        rows = list(csv.reader([text], strict=True))
    except (UnicodeError, csv.Error) as error:
        raise _ReportState("MALFORMED_REPORT", {"reason": "CSV_INVALID"}) from error
    if len(rows) != 1:
        raise _ReportState("MALFORMED_REPORT", {"reason": "CSV_INVALID"})
    return rows[0]


def _record_seal(record: bytes, row: list[str]) -> dict[str, Any]:
    row_document = dict(zip(ORDERED_FIELDS, row, strict=True))
    return {
        "selected_record_size_bytes": len(record),
        "selected_record_sha256": hashlib.sha256(record).hexdigest(),
        "selected_record_terminator": _record_terminator(record),
        "canonical_row_sha256": hashlib.sha256(
            canonical_json_bytes(row_document)
        ).hexdigest(),
    }


def _row_identity(row: list[str]) -> dict[str, str]:
    identity = {
        "market_and_exchange_names": row[0],
        "cftc_contract_market_code": row[3],
        "cftc_market_code": row[4],
        "cftc_commodity_code": row[6],
        "contract_units": row[81],
        "cftc_contract_market_code_quotes": row[82],
        "cftc_market_code_quotes": row[83],
        "cftc_commodity_code_quotes": row[84],
        "cftc_subgroup_code": row[85],
        "futonly_or_combined": row[86],
    }
    return identity


def _parse_report(
    raw_response: bytes,
    expected_report_date: date,
    evaluated_at: datetime,
) -> tuple[date, dict[str, Any], dict[str, str]]:
    raw = _raw_seal(raw_response)
    if not raw_response:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "EMPTY_RESPONSE"})
    try:
        raw_response.decode("utf-8")
    except UnicodeError as error:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "UTF8_INVALID"}) from error
    records = raw_response.splitlines(keepends=True)
    if not records:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "EMPTY_RESPONSE"})
    try:
        header = _parse_csv_record(records[0])
    except _ReportState as state:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": state.evidence["reason"]}) from state
    if tuple(header) != ORDERED_FIELDS:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "HEADER_MISMATCH"})

    candidates: list[tuple[bytes, list[str]]] = []
    for record in records[1:]:
        try:
            row = _parse_csv_record(record)
        except _ReportState as state:
            raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": state.evidence["reason"]}) from state
        if len(row) != 87:
            raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "FIELD_COUNT_INVALID"})
        if row[3] == CONTRACT_CODE:
            candidates.append((record, row))
    if not candidates:
        raise _ReportState("CONTRACT_ABSENT", {"raw_seal": raw, "match_count": 0})
    if len(candidates) != 1:
        raise _ReportState(
            "DUPLICATE_CONTRACT_ROW",
            {"raw_seal": raw, "match_count": len(candidates)},
        )

    record, row = candidates[0]
    record_seal = _record_seal(record, row)
    identity = _row_identity(row)
    if row[0] != MARKET_NAME:
        raise _ReportState(
            "WRONG_REPORT_FAMILY",
            {"raw_seal": raw, "record_seal": record_seal, "reason": "MARKET_IDENTITY_DRIFT"},
        )
    if row[86] != REPORT_FAMILY_MARKER:
        raise _ReportState(
            "WRONG_REPORT_FAMILY",
            {"raw_seal": raw, "record_seal": record_seal, "reason": "REPORT_FAMILY_DRIFT"},
        )
    secondary_values = (row[4], row[6], row[81], row[85])
    if (
        any(value == "" for value in secondary_values)
        or row[82] != CONTRACT_CODE
        or row[83] != row[4]
        or row[84] != row[6]
    ):
        raise _ReportState(
            "WRONG_REPORT_FAMILY",
            {"raw_seal": raw, "record_seal": record_seal, "reason": "SECONDARY_IDENTITY_DRIFT"},
        )

    try:
        report_date = datetime.strptime(row[2], "%m/%d/%Y").date()
    except ValueError as error:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "DATE_INVALID"}) from error
    if report_date.strftime("%m/%d/%Y") != row[2]:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "DATE_INVALID"})
    if row[1] != report_date.strftime("%y%m%d"):
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "DATE_FIELDS_MISMATCH"})
    if report_date.weekday() != 1:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "NON_TUESDAY_REPORT_DATE"})
    if report_date > evaluated_at.date():
        raise _ReportState(
            "REPORT_DATE_IN_FUTURE",
            {
                "raw_seal": raw,
                "record_seal": record_seal,
                "observed_report_date": report_date.isoformat(),
            },
        )
    if report_date != expected_report_date:
        raise _ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "UNEXPECTED_REPORT_DATE"})
    return report_date, {"raw_seal": raw, "record_seal": record_seal}, identity


def _validate_row_identity(value: Any, label: str) -> dict[str, str]:
    identity = deepcopy(_object(value, label))
    _exact_keys(identity, ROW_IDENTITY_KEYS, label)
    if identity["market_and_exchange_names"] != MARKET_NAME:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} market changed")
    if identity["cftc_contract_market_code"] != CONTRACT_CODE:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} contract code changed")
    if identity["cftc_contract_market_code_quotes"] != CONTRACT_CODE:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} quoted contract code changed")
    if identity["futonly_or_combined"] != REPORT_FAMILY_MARKER:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} report family changed")
    for key in (
        "cftc_market_code",
        "cftc_commodity_code",
        "contract_units",
        "cftc_subgroup_code",
    ):
        if not isinstance(identity[key], str) or identity[key] == "":
            _reject("SOURCE_IDENTITY_MISMATCH", f"{label}.{key} must be nonempty")
    if identity["cftc_market_code_quotes"] != identity["cftc_market_code"]:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} market code quote drift")
    if identity["cftc_commodity_code_quotes"] != identity["cftc_commodity_code"]:
        _reject("SOURCE_IDENTITY_MISMATCH", f"{label} commodity code quote drift")
    return identity


def _validate_prior(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    prior = deepcopy(_object(value, "prior_accepted"))
    _exact_keys(prior, PRIOR_KEYS, "prior_accepted")
    _day(prior["report_date"], "prior report_date")
    for key in (
        "raw_response_sha256",
        "selected_record_sha256",
        "canonical_row_sha256",
        "chain_sha256",
    ):
        _sha256(prior[key], f"prior {key}")
    prior["row_identity"] = _validate_row_identity(
        prior["row_identity"], "prior row_identity"
    )
    return prior


def _validate_decision(value: Any, received_at: datetime) -> dict[str, str]:
    decision = deepcopy(_object(value, "decision_schedule"))
    _exact_keys(decision, DECISION_KEYS, "decision_schedule")
    for key in ("schedule_id", "schedule_version"):
        if not isinstance(decision[key], str) or VERSION_RE.fullmatch(decision[key]) is None:
            _reject("LEAKAGE", f"decision {key} is invalid")
    _sha256(decision["schedule_sha256"], "decision schedule_sha256")
    decision_at = _utc_timestamp(decision["decision_at"], "decision_at")
    if decision_at <= received_at:
        _reject("LEAKAGE", "DRA decision must be strictly after sealed receipt")
    return decision


def _base_document(
    hashes: dict[str, str],
    proof: dict[str, Any],
    expected_report_date: date,
    scheduled: datetime,
    evaluated: datetime,
) -> dict[str, Any]:
    return {
        "schema_version": OBSERVATION_SCHEMA_VERSION,
        "document_type": DOCUMENT_TYPE,
        "authorization": AUTHORIZATION,
        "source_label": SOURCE_LABEL,
        "source_contract_sha256": hashes["source_contract_sha256"],
        "source_contract_schema_sha256": hashes["source_contract_schema_sha256"],
        "observation_schema_sha256": hashes["observation_schema_sha256"],
        "expected_report_date": expected_report_date.isoformat(),
        "release_proof": proof,
        "scheduled_cycle_at": _format_utc(scheduled),
        "evaluated_at": _format_utc(evaluated),
    }


def _finish(base: dict[str, Any], state: str, evidence: dict[str, Any]) -> bytes:
    if state not in STATES:
        _reject("CONTRACT_MISMATCH", "unknown state")
    document = deepcopy(base)
    document["state"] = state
    document["state_evidence"] = deepcopy(evidence)
    return canonical_json_bytes(document)


def _chain_sha256(base: dict[str, Any], evidence: dict[str, Any], predecessor: str) -> str:
    observation = deepcopy(base)
    observation["state"] = "NEW_REPORT_SEALED"
    observation["state_evidence"] = deepcopy(evidence)
    material = {"predecessor_sha256": predecessor, "observation": observation}
    return hashlib.sha256(canonical_json_bytes(material)).hexdigest()


def evaluate_source(
    raw_response: bytes | None,
    *,
    release_proof: dict[str, Any],
    scheduled_cycle_at: str,
    evaluated_at: str,
    predecessor_sha256: str,
    prior_accepted: dict[str, Any] | None = None,
    decision_schedule: dict[str, Any] | None = None,
) -> bytes:
    hashes = validate_frozen_package()
    predecessor = _sha256(predecessor_sha256, "predecessor_sha256")
    prior = _validate_prior(prior_accepted)
    if prior is None:
        if predecessor != GENESIS_SHA256:
            _reject("PREDECESSOR_MISMATCH", "genesis predecessor must be 64 zeroes")
    elif predecessor != prior["chain_sha256"]:
        _reject("PREDECESSOR_MISMATCH", "predecessor does not match accepted chain")

    proof, expected_report_date, _release_at, scheduled, evaluated = (
        _validate_release_context(release_proof, scheduled_cycle_at, evaluated_at)
    )
    base = _base_document(hashes, proof, expected_report_date, scheduled, evaluated)
    if evaluated < scheduled:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden before release due")
        return _finish(
            base,
            "RELEASE_NOT_DUE",
            {
                "release_at": proof["release_at"],
                "first_due_cycle_at": _format_utc(scheduled),
            },
        )
    if raw_response is None:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden without source bytes")
        return _finish(base, "SOURCE_UNAVAILABLE", {"reason": "NO_RESPONSE_BYTES"})
    if not isinstance(raw_response, bytes):
        _reject("CONTRACT_MISMATCH", "raw_response must be bytes or None")

    try:
        report_date, seals, identity = _parse_report(
            raw_response, expected_report_date, evaluated
        )
    except _ReportState as state:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden for non-sealed state")
        return _finish(base, state.state, state.evidence)

    identity = _validate_row_identity(identity, "row_identity")
    if prior is not None and identity != prior["row_identity"]:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden for identity drift")
        return _finish(
            base,
            "WRONG_REPORT_FAMILY",
            {
                "raw_seal": seals["raw_seal"],
                "record_seal": seals["record_seal"],
                "reason": "SECONDARY_IDENTITY_DRIFT",
            },
        )
    if prior is not None:
        prior_date = _day(prior["report_date"], "prior report_date")
        if report_date < prior_date:
            if decision_schedule is not None:
                _reject("LEAKAGE", "decision schedule is forbidden for regressed report")
            return _finish(
                base,
                "MALFORMED_REPORT",
                {"raw_seal": seals["raw_seal"], "reason": "REPORT_DATE_REGRESSION"},
            )
        if report_date == prior_date:
            if decision_schedule is not None:
                _reject("LEAKAGE", "decision schedule is forbidden for existing report")
            current_hashes = (
                seals["raw_seal"]["raw_response_sha256"],
                seals["record_seal"]["selected_record_sha256"],
                seals["record_seal"]["canonical_row_sha256"],
            )
            prior_hashes = (
                prior["raw_response_sha256"],
                prior["selected_record_sha256"],
                prior["canonical_row_sha256"],
            )
            if current_hashes == prior_hashes:
                return _finish(
                    base,
                    "NO_NEW_REPORT",
                    {
                        "report_date": report_date.isoformat(),
                        "raw_seal": seals["raw_seal"],
                        "record_seal": seals["record_seal"],
                        "row_identity": identity,
                        "prior_chain_sha256": prior["chain_sha256"],
                    },
                )
            return _finish(
                base,
                "SAME_REPORT_BYTES_DRIFT",
                {
                    "report_date": report_date.isoformat(),
                    "current_raw_seal": seals["raw_seal"],
                    "current_record_seal": seals["record_seal"],
                    "prior_raw_response_sha256": prior["raw_response_sha256"],
                    "prior_selected_record_sha256": prior["selected_record_sha256"],
                    "prior_canonical_row_sha256": prior["canonical_row_sha256"],
                    "prior_chain_sha256": prior["chain_sha256"],
                    "integrity_blocked": True,
                },
            )

    decision = _validate_decision(decision_schedule, evaluated)
    evidence = {
        "report_date": report_date.isoformat(),
        "received_at": _format_utc(evaluated),
        "field_count": 87,
        "raw_seal": seals["raw_seal"],
        "record_seal": seals["record_seal"],
        "row_identity": identity,
        "predecessor_sha256": predecessor,
        "decision_schedule": decision,
    }
    evidence["chain_sha256"] = _chain_sha256(base, evidence, predecessor)
    return _finish(base, "NEW_REPORT_SEALED", evidence)


def _validate_seal_object(value: Any, *, record: bool) -> dict[str, Any]:
    seal = _object(value, "record_seal" if record else "raw_seal")
    if record:
        expected = {
            "selected_record_size_bytes",
            "selected_record_sha256",
            "selected_record_terminator",
            "canonical_row_sha256",
        }
        _exact_keys(seal, expected, "record_seal")
        if isinstance(seal["selected_record_size_bytes"], bool) or not isinstance(seal["selected_record_size_bytes"], int) or seal["selected_record_size_bytes"] < 1:
            _reject("CONTRACT_MISMATCH", "selected record size is invalid")
        _sha256(seal["selected_record_sha256"], "selected_record_sha256")
        _sha256(seal["canonical_row_sha256"], "canonical_row_sha256")
        if seal["selected_record_terminator"] not in {"NONE", "LF", "CRLF", "CR"}:
            _reject("CONTRACT_MISMATCH", "selected record terminator is invalid")
    else:
        _exact_keys(seal, {"raw_response_size_bytes", "raw_response_sha256"}, "raw_seal")
        if isinstance(seal["raw_response_size_bytes"], bool) or not isinstance(seal["raw_response_size_bytes"], int) or seal["raw_response_size_bytes"] < 0:
            _reject("CONTRACT_MISMATCH", "raw response size is invalid")
        _sha256(seal["raw_response_sha256"], "raw_response_sha256")
    return seal


def _validate_evidence(document: dict[str, Any], due: bool) -> None:
    state = document["state"]
    evidence = _object(document["state_evidence"], "state_evidence")
    if state == "RELEASE_NOT_DUE":
        if due:
            _reject("CLOCK_DRIFT", "release-not-due state is already due")
        _exact_keys(evidence, {"release_at", "first_due_cycle_at"}, "state_evidence")
        if evidence["release_at"] != document["release_proof"]["release_at"]:
            _reject("CLOCK_DRIFT", "release proof drift")
        if evidence["first_due_cycle_at"] != document["scheduled_cycle_at"]:
            _reject("CLOCK_DRIFT", "first due cycle drift")
        return
    if not due:
        _reject("CLOCK_DRIFT", "only RELEASE_NOT_DUE is allowed before schedule")
    if state == "SOURCE_UNAVAILABLE":
        _exact_keys(evidence, {"reason"}, "state_evidence")
        if evidence["reason"] != "NO_RESPONSE_BYTES":
            _reject("CONTRACT_MISMATCH", "source unavailable reason changed")
        return
    if state == "CONTRACT_ABSENT":
        _exact_keys(evidence, {"raw_seal", "match_count"}, "state_evidence")
        _validate_seal_object(evidence["raw_seal"], record=False)
        if evidence["match_count"] != 0:
            _reject("CONTRACT_MISMATCH", "absent match count changed")
        return
    if state == "DUPLICATE_CONTRACT_ROW":
        _exact_keys(evidence, {"raw_seal", "match_count"}, "state_evidence")
        _validate_seal_object(evidence["raw_seal"], record=False)
        if isinstance(evidence["match_count"], bool) or not isinstance(evidence["match_count"], int) or evidence["match_count"] < 2:
            _reject("CONTRACT_MISMATCH", "duplicate match count is invalid")
        return
    if state == "MALFORMED_REPORT":
        _exact_keys(evidence, {"raw_seal", "reason"}, "state_evidence")
        _validate_seal_object(evidence["raw_seal"], record=False)
        if evidence["reason"] not in {
            "EMPTY_RESPONSE", "UTF8_INVALID", "CSV_INVALID", "HEADER_MISMATCH",
            "FIELD_COUNT_INVALID", "DATE_INVALID", "DATE_FIELDS_MISMATCH",
            "NON_TUESDAY_REPORT_DATE", "UNEXPECTED_REPORT_DATE", "REPORT_DATE_REGRESSION",
        }:
            _reject("CONTRACT_MISMATCH", "malformed reason is invalid")
        return
    if state == "WRONG_REPORT_FAMILY":
        _exact_keys(evidence, {"raw_seal", "record_seal", "reason"}, "state_evidence")
        _validate_seal_object(evidence["raw_seal"], record=False)
        _validate_seal_object(evidence["record_seal"], record=True)
        if evidence["reason"] not in {"MARKET_IDENTITY_DRIFT", "REPORT_FAMILY_DRIFT", "SECONDARY_IDENTITY_DRIFT"}:
            _reject("CONTRACT_MISMATCH", "family reason is invalid")
        return
    if state == "REPORT_DATE_IN_FUTURE":
        _exact_keys(evidence, {"raw_seal", "record_seal", "observed_report_date"}, "state_evidence")
        _validate_seal_object(evidence["raw_seal"], record=False)
        _validate_seal_object(evidence["record_seal"], record=True)
        _day(evidence["observed_report_date"], "observed_report_date")
        return
    if state == "NO_NEW_REPORT":
        _exact_keys(evidence, {"report_date", "raw_seal", "record_seal", "row_identity", "prior_chain_sha256"}, "state_evidence")
        _day(evidence["report_date"], "report_date")
        _validate_seal_object(evidence["raw_seal"], record=False)
        _validate_seal_object(evidence["record_seal"], record=True)
        _validate_row_identity(evidence["row_identity"], "row_identity")
        _sha256(evidence["prior_chain_sha256"], "prior_chain_sha256")
        return
    if state == "SAME_REPORT_BYTES_DRIFT":
        _exact_keys(evidence, {
            "report_date", "current_raw_seal", "current_record_seal",
            "prior_raw_response_sha256", "prior_selected_record_sha256",
            "prior_canonical_row_sha256", "prior_chain_sha256", "integrity_blocked",
        }, "state_evidence")
        _day(evidence["report_date"], "report_date")
        _validate_seal_object(evidence["current_raw_seal"], record=False)
        _validate_seal_object(evidence["current_record_seal"], record=True)
        for key in ("prior_raw_response_sha256", "prior_selected_record_sha256", "prior_canonical_row_sha256", "prior_chain_sha256"):
            _sha256(evidence[key], key)
        if evidence["integrity_blocked"] is not True:
            _reject("CONTRACT_MISMATCH", "same-date drift must be integrity blocking")
        return
    if state == "NEW_REPORT_SEALED":
        _exact_keys(evidence, {
            "report_date", "received_at", "field_count", "raw_seal", "record_seal",
            "row_identity", "predecessor_sha256", "decision_schedule", "chain_sha256",
        }, "state_evidence")
        _day(evidence["report_date"], "report_date")
        received = _utc_timestamp(evidence["received_at"], "received_at")
        evaluated = _utc_timestamp(document["evaluated_at"], "evaluated_at")
        if received != evaluated:
            _reject("CLOCK_DRIFT", "received_at must equal the offline evaluation receipt")
        if evidence["field_count"] != 87:
            _reject("CONTRACT_MISMATCH", "field count changed")
        _validate_seal_object(evidence["raw_seal"], record=False)
        _validate_seal_object(evidence["record_seal"], record=True)
        _validate_row_identity(evidence["row_identity"], "row_identity")
        predecessor = _sha256(evidence["predecessor_sha256"], "predecessor_sha256")
        _validate_decision(evidence["decision_schedule"], received)
        expected_chain = _chain_sha256(
            {key: value for key, value in document.items() if key not in {"state", "state_evidence"}},
            {key: value for key, value in evidence.items() if key != "chain_sha256"},
            predecessor,
        )
        if evidence["chain_sha256"] != expected_chain:
            _reject("HASH_MISMATCH", "chain hash changed")
        return
    _reject("CONTRACT_MISMATCH", "unknown state evidence")


def validate_observation_bytes(raw_bytes: bytes) -> dict[str, Any]:
    hashes = validate_frozen_package()
    document = load_json_bytes_strict(
        raw_bytes, "observation document", require_canonical=True
    )
    _exact_keys(document, TOP_KEYS, "observation document")
    expected = {
        "schema_version": OBSERVATION_SCHEMA_VERSION,
        "document_type": DOCUMENT_TYPE,
        "authorization": AUTHORIZATION,
        "source_label": SOURCE_LABEL,
        "source_contract_sha256": hashes["source_contract_sha256"],
        "source_contract_schema_sha256": hashes["source_contract_schema_sha256"],
        "observation_schema_sha256": hashes["observation_schema_sha256"],
    }
    for key, value in expected.items():
        if document[key] != value:
            _reject("FROZEN_FILE_MISMATCH", f"observation {key} changed")
    if document["state"] not in STATES:
        _reject("CONTRACT_MISMATCH", "unknown observation state")
    proof, expected_day, _release, scheduled, evaluated = _validate_release_context(
        document["release_proof"],
        document["scheduled_cycle_at"],
        document["evaluated_at"],
    )
    if document["release_proof"] != proof:
        _reject("CONTRACT_MISMATCH", "release proof changed")
    if document["expected_report_date"] != expected_day.isoformat():
        _reject("CLOCK_DRIFT", "expected report date changed")
    _validate_evidence(document, evaluated >= scheduled)
    return deepcopy(document)
