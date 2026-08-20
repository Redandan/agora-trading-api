"""Pure offline validator for the corrected OKX carry V3R1 schema probe."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from typing import Any


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
CONTRACT_ID = "OKX_DRA_CRYPTO_CARRY_PUBLIC_AXES_SOURCE_V3R1"
CONTRACT_SHA256 = "2e44b85a5a3998bf7285adbaba62095f80f0c7fce3fec9c75a0ec26369d90bcd"
SCHEMA_SHA256 = "137eda117cdcecaaccdd5ca03c54f26be5f718d28e5718553dbbd46421f6787a"
DOCUMENT_TYPE = "OKX_DRA_CRYPTO_CARRY_PUBLIC_AXES_SCHEMA_ONLY_PROBE_V3R1"
PACKAGE_DIR = Path(__file__).resolve().parent
CONTRACT_PATH = PACKAGE_DIR / "okx-dra-crypto-carry-public-axes-source-contract.v3r1.json"
SCHEMA_PATH = PACKAGE_DIR / "okx-dra-crypto-carry-public-axes-schema-probe.v3r1.schema.json"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
TIMESTAMP_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
FUTURES_INST_ID_PATTERN = re.compile(r"^BTC-USDT-[0-9]{6}$")
ROOT_KEYS = {
    "schema_version", "document_type", "authorization", "source_contract_id",
    "source_contract_sha256", "probed_at", "probe_host_identity", "network_path",
    "endpoints", "no_market_values_persisted", "raw_responses_persisted",
    "raw_responses_transported", "source_activation_authorized", "probe_seal",
}
ENDPOINT_KEYS = {
    "endpoint_id", "request_fingerprint_sha256", "credential_sent", "http_status",
    "response_observed_at", "top_level_keys", "api_code",
    "response_data_row_count", "inspected_data_row_index", "inspected_data_row_keys",
    "inspected_data_value_types", "identity_values",
    "required_timestamp_fields_present", "raw_response_sha256", "raw_response_bytes",
    "values_redacted", "schema_match",
}
SEAL_KEYS = {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}


class ContractViolation(ValueError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(detail)
        self.code = code


def _reject(code: str, detail: str) -> None:
    raise ContractViolation(code, detail)


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
        raise ContractViolation("NONCANONICAL_JSON", "value is not strict JSON") from error


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        _reject("CONTRACT_MISMATCH", f"{label} must be a closed object")
    return value


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        _reject("HASH_MISMATCH", f"{label} must be lowercase SHA-256")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or TIMESTAMP_PATTERN.fullmatch(value) is None:
        _reject("CLOCK_DRIFT", f"{label} must be second-precision UTC")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ContractViolation("CLOCK_DRIFT", f"{label} is invalid") from error


def _duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _reject("DUPLICATE_JSON_KEY", f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json_bytes(raw: bytes, *, canonical: bool) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_duplicate_keys,
            parse_constant=lambda token: _reject("NONCANONICAL_JSON", token),
        )
    except ContractViolation:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ContractViolation("NONCANONICAL_JSON", "invalid UTF-8 JSON") from error
    if not isinstance(value, dict):
        _reject("CONTRACT_MISMATCH", "document must be an object")
    if canonical and raw != canonical_json_bytes(value):
        _reject("NONCANONICAL_JSON", "document bytes are not canonical")
    return value


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_frozen_files() -> dict[str, str]:
    hashes = {
        "contract_sha256": file_sha256(CONTRACT_PATH),
        "schema_sha256": file_sha256(SCHEMA_PATH),
    }
    if hashes != {"contract_sha256": CONTRACT_SHA256, "schema_sha256": SCHEMA_SHA256}:
        _reject("FROZEN_FILE_MISMATCH", "V3R1 contract or probe schema hash changed")
    contract = load_json_bytes(CONTRACT_PATH.read_bytes(), canonical=False)
    if contract.get("contract_id") != CONTRACT_ID:
        _reject("FROZEN_FILE_MISMATCH", "contract identity changed")
    if contract.get("document_status") != "FROZEN_AWAITING_ISOLATED_SCHEMA_ONLY_PROBE_NOT_REGISTERED":
        _reject("FROZEN_FILE_MISMATCH", "contract status changed")
    if contract.get("supersedes") != {
        "path": "research_pipeline/okx-dra-crypto-carry-public-axes-source-contract.v3.json",
        "sha256": "d9097fdf4da6b0e7267630bc3fb09da4557ffaceff1c7b2c7f3e524782ab0606",
        "activation_observed": False,
        "probe_observed": False,
        "rejection_reasons": [
            "FUTURE_DATED_FREEZE_CLOCK",
            "IDENTITY_VALUES_NOT_ATTESTED",
            "RESPONSE_COUNT_AND_INSPECTION_COUNT_AMBIGUOUS",
            "RAW_RESPONSE_RETENTION_SEMANTICS_AMBIGUOUS",
        ],
    }:
        _reject("FROZEN_FILE_MISMATCH", "V3 supersession record changed")
    predecessor_paths = [
        (PACKAGE_DIR / "okx-dra-crypto-carry-public-axes-source-contract.v3.json", contract["supersedes"]["sha256"]),
        (PACKAGE_DIR / "examples" / "dra-crypto-carry-v3-okx-api-reuse-terms-review.v1.json", contract["predecessor_evidence"]["conditional_reuse_terms_review"]["sha256"]),
    ]
    if any(file_sha256(path) != expected for path, expected in predecessor_paths):
        _reject("FROZEN_FILE_MISMATCH", "predecessor evidence changed")
    readiness = contract.get("readiness")
    if not isinstance(readiness, dict) or readiness.get("schema_contract_frozen") is not True:
        _reject("FROZEN_FILE_MISMATCH", "schema contract is not frozen")
    if readiness.get("reuse_terms_conditionally_reviewed") is not True:
        _reject("FROZEN_FILE_MISMATCH", "conditional terms review is not bound")
    for key in (
        "schema_only_probe_accepted", "source_registered", "capture_authorized",
        "canonical_integration", "factor_formula_authorized", "hypothesis_authorized",
        "candidate_authorized", "oos_authorized", "trading_authorized",
    ):
        if readiness.get(key) is not False:
            _reject("FROZEN_FILE_MISMATCH", f"unsafe readiness flag: {key}")
    requests = contract.get("requests")
    if not isinstance(requests, list) or len(requests) != 4:
        _reject("FROZEN_FILE_MISMATCH", "exactly four requests are required")
    for request in requests:
        if request.get("method") != "GET" or request.get("origin") != "https://www.okx.com":
            _reject("FROZEN_FILE_MISMATCH", "request is not a fixed public GET")
        if request.get("credentials") != "NONE" or "/account/" in str(request.get("path")):
            _reject("FROZEN_FILE_MISMATCH", "private or credentialed request is forbidden")
        if request.get("maximum_raw_response_bytes") != 262144 or request.get("maximum_inspected_rows") != 1:
            _reject("FROZEN_FILE_MISMATCH", "probe bounds changed")
    return hashes


def frozen_contract() -> dict[str, Any]:
    validate_frozen_files()
    return load_json_bytes(CONTRACT_PATH.read_bytes(), canonical=False)


def expected_request_fingerprints() -> dict[str, str]:
    return {
        request["endpoint_id"]: hashlib.sha256(canonical_json_bytes(request)).hexdigest()
        for request in frozen_contract()["requests"]
    }


def _validate_identity(endpoint_id: str, values: Any) -> None:
    if endpoint_id == "FUTURES_OPEN_INTEREST":
        values = _exact_keys(values, {"instId", "instType"}, "FUTURES identity_values")
        if values["instType"] != "FUTURES" or not isinstance(values["instId"], str) or FUTURES_INST_ID_PATTERN.fullmatch(values["instId"]) is None:
            _reject("SOURCE_IDENTITY_MISMATCH", "FUTURES identity is not an expiry BTC-USDT contract")
    elif endpoint_id == "SWAP_OPEN_INTEREST":
        if values != {"instId": "BTC-USDT-SWAP", "instType": "SWAP"}:
            _reject("SOURCE_IDENTITY_MISMATCH", "SWAP open-interest identity changed")
    elif endpoint_id == "SWAP_FUNDING_RATE_HISTORY":
        values = _exact_keys(values, {"formulaType", "instId", "method"}, "funding identity_values")
        if values["instId"] != "BTC-USDT-SWAP" or not all(isinstance(values[key], str) and values[key] for key in ("formulaType", "method")):
            _reject("SOURCE_IDENTITY_MISMATCH", "funding identity changed")
    elif endpoint_id == "USDT_LENDING_RATE_HISTORY":
        if values != {"ccy": "USDT"}:
            _reject("SOURCE_IDENTITY_MISMATCH", "lending currency identity changed")
    else:
        _reject("SOURCE_IDENTITY_MISMATCH", "unknown endpoint identity")


def seal_probe_payload(payload: dict[str, Any]) -> bytes:
    document = deepcopy(payload)
    if "probe_seal" in document:
        _reject("CONTRACT_MISMATCH", "payload must not already contain probe_seal")
    probed_at = document.get("probed_at")
    _timestamp(probed_at, "probed_at")
    document["probe_seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(canonical_json_bytes(document)).hexdigest(),
        "canonicalization": "UTF-8 compact sorted-key JSON excluding probe_seal",
        "sealed_at": probed_at,
    }
    raw = canonical_json_bytes(document)
    validate_probe_bytes(raw)
    return raw


def validate_probe_bytes(raw: bytes) -> dict[str, Any]:
    document = load_json_bytes(raw, canonical=True)
    _exact_keys(document, ROOT_KEYS, "probe")
    expected_scalars = {
        "schema_version": "3.1",
        "document_type": DOCUMENT_TYPE,
        "authorization": AUTHORIZATION,
        "source_contract_id": CONTRACT_ID,
        "source_contract_sha256": CONTRACT_SHA256,
        "probe_host_identity": "APPROVED_ISOLATED_CREDENTIAL_FREE_SOURCE_HOST",
        "network_path": "SOURCE_HOST_HTTPS_GET_ONLY_NO_RESEARCH_WORKER_NETWORK",
        "no_market_values_persisted": True,
        "raw_responses_persisted": False,
        "raw_responses_transported": False,
        "source_activation_authorized": False,
    }
    for key, expected in expected_scalars.items():
        if document[key] != expected:
            _reject("CONTRACT_MISMATCH", f"probe {key} drift")
    probed_at = _timestamp(document["probed_at"], "probed_at")
    contract = frozen_contract()
    frozen_at = _timestamp(contract["frozen_at"], "contract frozen_at")
    if probed_at <= frozen_at:
        _reject("CLOCK_DRIFT", "probe must be strictly after contract freeze")

    endpoints = document["endpoints"]
    requests = contract["requests"]
    fingerprints = expected_request_fingerprints()
    if not isinstance(endpoints, list) or len(endpoints) != 4:
        _reject("CONTRACT_MISMATCH", "exactly four endpoint probes are required")
    for endpoint, request in zip(endpoints, requests, strict=True):
        endpoint = _exact_keys(endpoint, ENDPOINT_KEYS, "endpoint probe")
        endpoint_id = request["endpoint_id"]
        if endpoint["endpoint_id"] != endpoint_id:
            _reject("SOURCE_IDENTITY_MISMATCH", "endpoint order or identity changed")
        if endpoint["request_fingerprint_sha256"] != fingerprints[endpoint_id]:
            _reject("SOURCE_IDENTITY_MISMATCH", f"{endpoint_id} request fingerprint changed")
        if endpoint["credential_sent"] is not False or endpoint["http_status"] != 200 or endpoint["api_code"] != request["expected_api_code"]:
            _reject("SOURCE_ACCESS_REJECT", f"{endpoint_id} was not a credential-free successful response")
        observed_at = _timestamp(endpoint["response_observed_at"], f"{endpoint_id} response_observed_at")
        if observed_at <= frozen_at or observed_at > probed_at:
            _reject("CLOCK_DRIFT", f"{endpoint_id} observation time is invalid")
        if endpoint["top_level_keys"] != request["expected_top_level_keys"]:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} top-level keys changed")
        row_count = endpoint["response_data_row_count"]
        row_index = endpoint["inspected_data_row_index"]
        if isinstance(row_count, bool) or not isinstance(row_count, int) or row_count < 1:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} has no response rows")
        if isinstance(row_index, bool) or not isinstance(row_index, int) or row_index < 0 or row_index >= row_count:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} inspected row index is invalid")
        if request["selection_rule"] == "EXACTLY_ONE_ROW" and (row_count != 1 or row_index != 0):
            _reject("SCHEMA_DRIFT", f"{endpoint_id} did not return exactly one row")
        if endpoint["inspected_data_row_keys"] != request["expected_data_row_keys"]:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} row keys changed")
        types = endpoint["inspected_data_value_types"]
        if not isinstance(types, dict) or list(types) != request["expected_data_row_keys"] or any(value != "STRING" for value in types.values()):
            _reject("SCHEMA_DRIFT", f"{endpoint_id} row value types changed")
        _validate_identity(endpoint_id, endpoint["identity_values"])
        if endpoint["required_timestamp_fields_present"] != request["timestamp_fields"]:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} timestamp fields changed")
        _sha256(endpoint["raw_response_sha256"], f"{endpoint_id} raw response")
        raw_bytes = endpoint["raw_response_bytes"]
        if isinstance(raw_bytes, bool) or not isinstance(raw_bytes, int) or not 1 <= raw_bytes <= request["maximum_raw_response_bytes"]:
            _reject("SOURCE_ACCESS_REJECT", f"{endpoint_id} raw response size is outside the frozen bound")
        if endpoint["values_redacted"] is not True or endpoint["schema_match"] is not True:
            _reject("SCHEMA_DRIFT", f"{endpoint_id} did not produce a redacted schema match")

    seal = _exact_keys(document["probe_seal"], SEAL_KEYS, "probe_seal")
    expected_seal = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(canonical_json_bytes({key: value for key, value in document.items() if key != "probe_seal"})).hexdigest(),
        "canonicalization": "UTF-8 compact sorted-key JSON excluding probe_seal",
        "sealed_at": document["probed_at"],
    }
    if seal != expected_seal:
        _reject("HASH_MISMATCH", "probe seal mismatch")
    return document
