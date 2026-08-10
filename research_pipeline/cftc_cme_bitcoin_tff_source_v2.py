from __future__ import annotations

from copy import deepcopy
import csv
from datetime import date, datetime, timezone
import hashlib
from pathlib import Path
from typing import Any

from . import cftc_cme_bitcoin_tff_source as v1


AUTHORIZATION = v1.AUTHORIZATION
SOURCE_LABEL = "CFTC_CME_BITCOIN_TFF_FUTURES_ONLY_V2"
SOURCE_CONTRACT_ID = "CFTC_CME_BITCOIN_TFF_SOURCE_CONTRACT_V2"
SOURCE_CONTRACT_SHA256 = "726d7ebff05d1c9fb5df9399996ff9817b28025d9d696f207191ec8c62e7dde5"
SOURCE_CONTRACT_SCHEMA_SHA256 = "f8daf4c6f9014c4874bb2c62865597c764ffd46833b4b31222fbc20617013a73"
OBSERVATION_SCHEMA_SHA256 = "44d46dc857dab5d874f6b730060c798266e6ed386597a7ab18245274ee98f53b"
OBSERVATION_SCHEMA_VERSION = "CFTC_CME_BITCOIN_TFF_OBSERVATION_V2"
DOCUMENT_TYPE = "OFFLINE_CFTC_TFF_HEADERLESS_SOURCE_EVALUATION_V2"
MARKET_NAME = v1.MARKET_NAME
CONTRACT_CODE = v1.CONTRACT_CODE
REPORT_FAMILY_MARKER = v1.REPORT_FAMILY_MARKER
GENESIS_SHA256 = v1.GENESIS_SHA256
ORDERED_FIELDS = v1.ORDERED_FIELDS
STATES = v1.STATES
HEADER_POLICY = "HEADER_ABSENT_POSITIONAL_87_FIELDS"
SYNTHETIC_FIXTURE_MARKER = "SYNTHETIC_FIXTURE_NOT_CFTC_DATA"

PACKAGE_DIR = Path(__file__).resolve().parent
SOURCE_CONTRACT_PATH = PACKAGE_DIR / "cftc-cme-bitcoin-tff-source-contract.v2.json"
SOURCE_CONTRACT_SCHEMA_PATH = PACKAGE_DIR / "cftc-cme-bitcoin-tff-source-contract.v2.schema.json"
OBSERVATION_SCHEMA_PATH = PACKAGE_DIR / "cftc-cme-bitcoin-tff-observation.v2.schema.json"
SOURCE_CONTRACT_SCHEMA_ID = "https://agora.local/research/cftc-cme-bitcoin-tff-source-contract.v2.schema.json"
OBSERVATION_SCHEMA_ID = "https://agora.local/research/cftc-cme-bitcoin-tff-observation.v2.schema.json"

V1_FROZEN_HASHES = {
    "source_contract_sha256": "9be18251e3b74fbc4d6f4ef6147e26900e18092ee526cefb16c8b107841153d8",
    "source_contract_schema_sha256": "53d803202839c2b7d6882544289f2db4c03337159d0a2da8f3a0d913afb6ece3",
    "observation_schema_sha256": "91630c4a2c7275a766e7f9b3d3036ba69598098ed07c08fa8c4df5b629aa7e82",
    "source_module_sha256": "b56f9ceecb6f1ed1cf10d77756c109c1cd9ad25540ede6c70d555867aaed9eb0",
    "source_test_sha256": "5379044b3366bcb3eb0ff52981f488fd5f79ad1606fa733c7cce316b408d5376",
}
V1_SOURCE_PATH = PACKAGE_DIR / "cftc_cme_bitcoin_tff_source.py"
V1_TEST_PATH = PACKAGE_DIR / "tests" / "test_cftc_cme_bitcoin_tff_source.py"

ContractViolation = v1.ContractViolation


def _reject(code: str, message: str) -> None:
    raise ContractViolation(code, message)


def canonical_json_bytes(value: Any) -> bytes:
    return v1.canonical_json_bytes(value)


def document_sha256(raw_bytes: bytes) -> str:
    return v1.document_sha256(raw_bytes)


def file_sha256(path: Path) -> str:
    return v1.file_sha256(path)


def validate_frozen_package() -> dict[str, str]:
    if v1.validate_frozen_package() != {
        key: V1_FROZEN_HASHES[key]
        for key in ("source_contract_sha256", "source_contract_schema_sha256", "observation_schema_sha256")
    }:
        _reject("FROZEN_FILE_MISMATCH", "V1 contract package changed")
    if file_sha256(V1_SOURCE_PATH) != V1_FROZEN_HASHES["source_module_sha256"]:
        _reject("FROZEN_FILE_MISMATCH", "V1 source module changed")
    if file_sha256(V1_TEST_PATH) != V1_FROZEN_HASHES["source_test_sha256"]:
        _reject("FROZEN_FILE_MISMATCH", "V1 source tests changed")

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
        _reject("FROZEN_FILE_MISMATCH", "V2 contract or schema hash changed")

    source = v1._load_frozen(SOURCE_CONTRACT_PATH, "V2 source contract")
    exact = {
        "schema_version": "2",
        "contract_id": SOURCE_CONTRACT_ID,
        "authorization": AUTHORIZATION,
        "document_status": "OFFLINE_DISABLED_NOT_REGISTERED",
        "source_label": SOURCE_LABEL,
    }
    for key, value in exact.items():
        if source.get(key) != value:
            _reject("FROZEN_FILE_MISMATCH", f"V2 source contract {key} changed")
    if tuple(source.get("ordered_fields", ())) != ORDERED_FIELDS:
        _reject("FROZEN_FILE_MISMATCH", "V2 ordered positional fields changed")
    if tuple(v1._object(source.get("state_machine"), "state machine").get("states", ())) != STATES:
        _reject("FROZEN_FILE_MISMATCH", "V2 ten-state contract changed")
    identity = v1._object(source.get("source_identity"), "source identity")
    if identity.get("market_and_exchange_name") != MARKET_NAME or identity.get("cftc_contract_market_code") != CONTRACT_CODE or identity.get("report_variant") != "FUTURES_ONLY":
        _reject("FROZEN_FILE_MISMATCH", "V2 source identity changed")
    row_policy = v1._object(source.get("row_policy"), "row policy")
    expected_policy = {
        "transport": HEADER_POLICY,
        "field_count": 87,
        "field_2_grammar": "YYMMDD_COMPACT/%y%m%d",
        "field_3_grammar": "YYYY_DASH_MM_DASH_DD/%Y-%m-%d",
        "date_equality": "LOSSLESS_SAME_TUESDAY",
        "observation_weekday": "TUESDAY",
        "target_row": "EXACTLY_ONE_CONTRACT_CODE_133741",
        "raw_values": "PRESERVE_ALL_STRINGS",
        "suppression_markers": "PRESERVE_RAW",
        "secondary_identity": "NONEMPTY_THEN_EXACT_PREDECESSOR_BOUND",
        "derived_value_selection": "DENY",
    }
    if row_policy != expected_policy:
        _reject("FROZEN_FILE_MISMATCH", "V2 headerless row policy changed")
    if source.get("schemas") != {
        "contract": {"path": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.schema.json", "id": SOURCE_CONTRACT_SCHEMA_ID, "sha256": SOURCE_CONTRACT_SCHEMA_SHA256},
        "observation": {"path": "research_pipeline/cftc-cme-bitcoin-tff-observation.v2.schema.json", "id": OBSERVATION_SCHEMA_ID, "sha256": OBSERVATION_SCHEMA_SHA256},
    }:
        _reject("FROZEN_FILE_MISMATCH", "V2 schema bindings changed")
    for path, schema_id, label in (
        (SOURCE_CONTRACT_SCHEMA_PATH, SOURCE_CONTRACT_SCHEMA_ID, "V2 contract schema"),
        (OBSERVATION_SCHEMA_PATH, OBSERVATION_SCHEMA_ID, "V2 observation schema"),
    ):
        schema = v1._load_frozen(path, label)
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema" or schema.get("$id") != schema_id:
            _reject("FROZEN_FILE_MISMATCH", f"{label} identity changed")
        if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
            _reject("FROZEN_FILE_MISMATCH", f"{label} root is not closed")
    return hashes


def _raw_seal(raw_response: bytes) -> dict[str, Any]:
    return {"raw_response_size_bytes": len(raw_response), "raw_response_sha256": hashlib.sha256(raw_response).hexdigest()}


def _parse_positional_date(row: list[str]) -> date:
    try:
        compact = datetime.strptime(row[1], "%y%m%d").date()
        dashed = datetime.strptime(row[2], "%Y-%m-%d").date()
    except ValueError as error:
        raise v1._ReportState("MALFORMED_REPORT", {"reason": "DATE_INVALID"}) from error
    if compact.strftime("%y%m%d") != row[1] or dashed.strftime("%Y-%m-%d") != row[2]:
        raise v1._ReportState("MALFORMED_REPORT", {"reason": "DATE_INVALID"})
    if compact != dashed:
        raise v1._ReportState("MALFORMED_REPORT", {"reason": "DATE_FIELDS_MISMATCH"})
    if dashed.weekday() != 1:
        raise v1._ReportState("MALFORMED_REPORT", {"reason": "NON_TUESDAY_REPORT_DATE"})
    return dashed


def _parse_report(raw_response: bytes, expected_report_date: date, evaluated_at: datetime) -> tuple[date, dict[str, Any], dict[str, str]]:
    raw = _raw_seal(raw_response)
    if not raw_response:
        raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "EMPTY_RESPONSE"})
    try:
        raw_response.decode("utf-8")
    except UnicodeError as error:
        raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "UTF8_INVALID"}) from error
    records = [record for record in raw_response.splitlines(keepends=True) if v1._record_content(record) != b""]
    if not records:
        raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "EMPTY_RESPONSE"})
    parsed: list[tuple[bytes, list[str], date]] = []
    for record in records:
        try:
            row = v1._parse_csv_record(record)
        except v1._ReportState as state:
            raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": state.evidence["reason"]}) from state
        if len(row) != 87:
            raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "FIELD_COUNT_INVALID"})
        if tuple(row) == ORDERED_FIELDS:
            raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "HEADER_PRESENT"})
        try:
            report_date = _parse_positional_date(row)
        except v1._ReportState as state:
            raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": state.evidence["reason"]}) from state
        parsed.append((record, row, report_date))

    candidates = [(record, row, report_date) for record, row, report_date in parsed if row[3] == CONTRACT_CODE]
    if not candidates:
        raise v1._ReportState("CONTRACT_ABSENT", {"raw_seal": raw, "match_count": 0})
    if len(candidates) != 1:
        raise v1._ReportState("DUPLICATE_CONTRACT_ROW", {"raw_seal": raw, "match_count": len(candidates)})
    record, row, report_date = candidates[0]
    record_seal = v1._record_seal(record, row)
    identity = v1._row_identity(row)
    if report_date > evaluated_at.date():
        raise v1._ReportState("REPORT_DATE_IN_FUTURE", {"raw_seal": raw, "record_seal": record_seal, "observed_report_date": report_date.isoformat()})
    if report_date != expected_report_date or any(item[2] != expected_report_date for item in parsed):
        raise v1._ReportState("MALFORMED_REPORT", {"raw_seal": raw, "reason": "UNEXPECTED_REPORT_DATE"})
    if row[0] != MARKET_NAME:
        raise v1._ReportState("WRONG_REPORT_FAMILY", {"raw_seal": raw, "record_seal": record_seal, "reason": "MARKET_IDENTITY_DRIFT"})
    if row[86] != REPORT_FAMILY_MARKER:
        raise v1._ReportState("WRONG_REPORT_FAMILY", {"raw_seal": raw, "record_seal": record_seal, "reason": "REPORT_FAMILY_DRIFT"})
    if any(value == "" for value in (row[4], row[6], row[81], row[85])) or row[82] != CONTRACT_CODE or row[83] != row[4] or row[84] != row[6]:
        raise v1._ReportState("WRONG_REPORT_FAMILY", {"raw_seal": raw, "record_seal": record_seal, "reason": "SECONDARY_IDENTITY_DRIFT"})
    return report_date, {"raw_seal": raw, "record_seal": record_seal}, identity


def _base_document(hashes: dict[str, str], proof: dict[str, Any], expected_report_date: date, scheduled: datetime, evaluated: datetime) -> dict[str, Any]:
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
        "scheduled_cycle_at": v1._format_utc(scheduled),
        "evaluated_at": v1._format_utc(evaluated),
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
    return hashlib.sha256(canonical_json_bytes({"predecessor_sha256": predecessor, "observation": observation})).hexdigest()


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
    predecessor = v1._sha256(predecessor_sha256, "predecessor_sha256")
    prior = v1._validate_prior(prior_accepted)
    if prior is None:
        if predecessor != GENESIS_SHA256:
            _reject("PREDECESSOR_MISMATCH", "genesis predecessor must be 64 zeroes")
    elif predecessor != prior["chain_sha256"]:
        _reject("PREDECESSOR_MISMATCH", "predecessor does not match accepted chain")
    proof, expected_report_date, _release, scheduled, evaluated = v1._validate_release_context(release_proof, scheduled_cycle_at, evaluated_at)
    base = _base_document(hashes, proof, expected_report_date, scheduled, evaluated)
    if evaluated < scheduled:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden before release due")
        return _finish(base, "RELEASE_NOT_DUE", {"release_at": proof["release_at"], "first_due_cycle_at": v1._format_utc(scheduled)})
    if raw_response is None:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden without source bytes")
        return _finish(base, "SOURCE_UNAVAILABLE", {"reason": "NO_RESPONSE_BYTES"})
    if not isinstance(raw_response, bytes):
        _reject("CONTRACT_MISMATCH", "raw_response must be bytes or None")
    try:
        report_date, seals, identity = _parse_report(raw_response, expected_report_date, evaluated)
    except v1._ReportState as state:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden for non-sealed state")
        return _finish(base, state.state, state.evidence)
    identity = v1._validate_row_identity(identity, "row_identity")
    if prior is not None and identity != prior["row_identity"]:
        if decision_schedule is not None:
            _reject("LEAKAGE", "decision schedule is forbidden for identity drift")
        return _finish(base, "WRONG_REPORT_FAMILY", {"raw_seal": seals["raw_seal"], "record_seal": seals["record_seal"], "reason": "SECONDARY_IDENTITY_DRIFT"})
    if prior is not None:
        prior_date = v1._day(prior["report_date"], "prior report_date")
        if report_date < prior_date:
            if decision_schedule is not None:
                _reject("LEAKAGE", "decision schedule is forbidden for regressed report")
            return _finish(base, "MALFORMED_REPORT", {"raw_seal": seals["raw_seal"], "reason": "REPORT_DATE_REGRESSION"})
        if report_date == prior_date:
            if decision_schedule is not None:
                _reject("LEAKAGE", "decision schedule is forbidden for existing report")
            current_hashes = (seals["raw_seal"]["raw_response_sha256"], seals["record_seal"]["selected_record_sha256"], seals["record_seal"]["canonical_row_sha256"])
            prior_hashes = (prior["raw_response_sha256"], prior["selected_record_sha256"], prior["canonical_row_sha256"])
            if current_hashes == prior_hashes:
                return _finish(base, "NO_NEW_REPORT", {"report_date": report_date.isoformat(), "raw_seal": seals["raw_seal"], "record_seal": seals["record_seal"], "row_identity": identity, "prior_chain_sha256": prior["chain_sha256"]})
            return _finish(base, "SAME_REPORT_BYTES_DRIFT", {"report_date": report_date.isoformat(), "current_raw_seal": seals["raw_seal"], "current_record_seal": seals["record_seal"], "prior_raw_response_sha256": prior["raw_response_sha256"], "prior_selected_record_sha256": prior["selected_record_sha256"], "prior_canonical_row_sha256": prior["canonical_row_sha256"], "prior_chain_sha256": prior["chain_sha256"], "integrity_blocked": True})
    decision = v1._validate_decision(decision_schedule, evaluated)
    evidence = {"report_date": report_date.isoformat(), "received_at": v1._format_utc(evaluated), "field_count": 87, "raw_seal": seals["raw_seal"], "record_seal": seals["record_seal"], "row_identity": identity, "predecessor_sha256": predecessor, "decision_schedule": decision}
    evidence["chain_sha256"] = _chain_sha256(base, evidence, predecessor)
    return _finish(base, "NEW_REPORT_SEALED", evidence)


def validate_observation_bytes(raw_bytes: bytes) -> dict[str, Any]:
    hashes = validate_frozen_package()
    document = v1.load_json_bytes_strict(raw_bytes, "V2 observation document", require_canonical=True)
    v1._exact_keys(document, v1.TOP_KEYS, "V2 observation document")
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
        if document.get(key) != value:
            _reject("FROZEN_FILE_MISMATCH", f"V2 observation {key} changed")
    if document.get("state") not in STATES:
        _reject("CONTRACT_MISMATCH", "unknown V2 observation state")
    proof, expected_day, _release, scheduled, evaluated = v1._validate_release_context(document["release_proof"], document["scheduled_cycle_at"], document["evaluated_at"])
    if document["release_proof"] != proof or document["expected_report_date"] != expected_day.isoformat():
        _reject("CLOCK_DRIFT", "V2 release or expected report date changed")
    evidence = document["state_evidence"]
    if evaluated < scheduled and document["state"] != "RELEASE_NOT_DUE":
        _reject("CLOCK_DRIFT", "only RELEASE_NOT_DUE is allowed before schedule")
    if document["state"] == "MALFORMED_REPORT" and isinstance(evidence, dict) and evidence.get("reason") == "HEADER_PRESENT":
        v1._exact_keys(evidence, {"raw_seal", "reason"}, "state_evidence")
        v1._validate_seal_object(evidence["raw_seal"], record=False)
    else:
        v1._validate_evidence(document, evaluated >= scheduled)
    if document["state"] == "NEW_REPORT_SEALED":
        predecessor = document["state_evidence"]["predecessor_sha256"]
        evidence_without_chain = {key: value for key, value in document["state_evidence"].items() if key != "chain_sha256"}
        base = {key: value for key, value in document.items() if key not in {"state", "state_evidence"}}
        if document["state_evidence"]["chain_sha256"] != _chain_sha256(base, evidence_without_chain, predecessor):
            _reject("HASH_MISMATCH", "V2 chain hash changed")
    return deepcopy(document)
