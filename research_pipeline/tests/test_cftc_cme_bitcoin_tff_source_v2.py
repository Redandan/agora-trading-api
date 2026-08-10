from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import csv
import hashlib
import io
import json
import unittest

try:
    from jsonschema import Draft202012Validator, ValidationError
except ModuleNotFoundError:
    Draft202012Validator = None
    ValidationError = Exception

from research_pipeline import cftc_cme_bitcoin_tff_source_v2 as source


EXPECTED_TUESDAY = "2026-08-04"
RELEASE_AT = "2026-08-07T15:30:00-04:00"
SCHEDULED_CYCLE = "2026-08-08T01:05:00Z"
EVALUATED_AT = "2026-08-08T01:05:05Z"
DECISION_AT = "2026-08-09T00:00:00Z"
SYNTHETIC_NOTICE = "SYNTHETIC_FIXTURE_NOT_CFTC_DATA"


def _release_proof(
    *,
    expected_tuesday: str = EXPECTED_TUESDAY,
    release_at: str = RELEASE_AT,
    coverage_start: str = "2026-01-01",
    coverage_end: str = "2026-12-31",
) -> dict[str, str]:
    return {
        "release_schedule_version": "CFTC_COT_RELEASE_SCHEDULE_SYNTHETIC_V2",
        "release_schedule_sha256": "1" * 64,
        "coverage_start": coverage_start,
        "coverage_end": coverage_end,
        "expected_tuesday": expected_tuesday,
        "release_at": release_at,
        "release_timezone": "America/New_York",
    }


def _decision(*, decision_at: str = DECISION_AT) -> dict[str, str]:
    return {
        "schedule_id": "DRA_DAILY_DECISION_SYNTHETIC",
        "schedule_version": "V2",
        "schedule_sha256": "2" * 64,
        "decision_at": decision_at,
    }


def _synthetic_row(
    *,
    report_day: str = EXPECTED_TUESDAY,
    market_name: str = source.MARKET_NAME,
    contract_code: str = source.CONTRACT_CODE,
    family: str = source.REPORT_FAMILY_MARKER,
) -> list[str]:
    day = datetime.strptime(report_day, "%Y-%m-%d")
    row = [f"SYNTHETIC_{index:02d}" for index in range(87)]
    row[0] = market_name
    row[1] = day.strftime("%y%m%d")
    row[2] = day.strftime("%Y-%m-%d")
    row[3] = contract_code
    row[4] = "SYNTHETIC_CME"
    row[6] = "SYNTHETIC_BTC"
    row[59] = "·"
    row[81] = "SYNTHETIC_CONTRACT_UNITS"
    row[82] = contract_code
    row[83] = row[4]
    row[84] = row[6]
    row[85] = "SYNTHETIC_SUBGROUP"
    row[86] = family
    return row


def _csv_bytes(
    rows: list[list[str]], *, header: bool = False, terminator: str = "\r\n"
) -> bytes:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator=terminator)
    if header:
        writer.writerow(source.ORDERED_FIELDS)
    writer.writerows(rows)
    return buffer.getvalue().encode("utf-8")


def _raw(
    *,
    row: list[str] | None = None,
    rows: list[list[str]] | None = None,
    header: bool = False,
    terminator: str = "\r\n",
) -> bytes:
    if rows is None:
        rows = [_synthetic_row() if row is None else row]
    return _csv_bytes(rows, header=header, terminator=terminator)


def _evaluate(
    raw_response: bytes | None,
    *,
    proof: dict[str, str] | None = None,
    scheduled_cycle_at: str = SCHEDULED_CYCLE,
    evaluated_at: str = EVALUATED_AT,
    predecessor_sha256: str = source.GENESIS_SHA256,
    prior_accepted: dict[str, object] | None = None,
    decision_schedule: dict[str, str] | None = None,
) -> bytes:
    return source.evaluate_source(
        raw_response,
        release_proof=_release_proof() if proof is None else proof,
        scheduled_cycle_at=scheduled_cycle_at,
        evaluated_at=evaluated_at,
        predecessor_sha256=predecessor_sha256,
        prior_accepted=prior_accepted,
        decision_schedule=decision_schedule,
    )


def _document(raw_bytes: bytes) -> dict[str, object]:
    return source.validate_observation_bytes(raw_bytes)


def _new_document(raw_response: bytes | None = None) -> dict[str, object]:
    return _document(
        _evaluate(_raw() if raw_response is None else raw_response, decision_schedule=_decision())
    )


def _prior(document: dict[str, object]) -> dict[str, object]:
    evidence = document["state_evidence"]
    assert isinstance(evidence, dict)
    raw_seal = evidence["raw_seal"]
    record_seal = evidence["record_seal"]
    assert isinstance(raw_seal, dict) and isinstance(record_seal, dict)
    return {
        "report_date": evidence["report_date"],
        "raw_response_sha256": raw_seal["raw_response_sha256"],
        "selected_record_sha256": record_seal["selected_record_sha256"],
        "canonical_row_sha256": record_seal["canonical_row_sha256"],
        "chain_sha256": evidence["chain_sha256"],
        "row_identity": deepcopy(evidence["row_identity"]),
    }


def _walk_object_schemas(value):
    if isinstance(value, dict):
        if value.get("type") == "object":
            yield value
        for child in value.values():
            yield from _walk_object_schemas(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_object_schemas(child)


class CftcCmeBitcoinTffSourceV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract_schema = json.loads(source.SOURCE_CONTRACT_SCHEMA_PATH.read_text("utf-8"))
        cls.observation_schema = json.loads(source.OBSERVATION_SCHEMA_PATH.read_text("utf-8"))
        cls.contract_document = json.loads(source.SOURCE_CONTRACT_PATH.read_text("utf-8"))
        cls.contract_validator = None if Draft202012Validator is None else Draft202012Validator(cls.contract_schema)
        cls.observation_validator = None if Draft202012Validator is None else Draft202012Validator(cls.observation_schema)

    def assert_violation(self, code: str, action) -> None:
        with self.assertRaises(source.ContractViolation) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def assert_schema_valid(self, document: dict[str, object]) -> None:
        if self.observation_validator is not None:
            self.observation_validator.validate(document)

    def test_frozen_package_is_distinct_disabled_closed_and_preserves_v1(self) -> None:
        self.assertEqual(
            {
                "source_contract_sha256": source.SOURCE_CONTRACT_SHA256,
                "source_contract_schema_sha256": source.SOURCE_CONTRACT_SCHEMA_SHA256,
                "observation_schema_sha256": source.OBSERVATION_SCHEMA_SHA256,
            },
            source.validate_frozen_package(),
        )
        self.assertEqual("2", self.contract_document["schema_version"])
        self.assertEqual("OFFLINE_DISABLED_NOT_REGISTERED", self.contract_document["document_status"])
        self.assertEqual(source.HEADER_POLICY, self.contract_document["row_policy"]["transport"])
        self.assertNotEqual(source.SOURCE_LABEL, "CFTC_CME_BITCOIN_TFF_FUTURES_ONLY_V1")
        self.assertEqual(source.V1_FROZEN_HASHES["source_module_sha256"], source.file_sha256(source.V1_SOURCE_PATH))
        self.assertEqual(source.V1_FROZEN_HASHES["source_test_sha256"], source.file_sha256(source.V1_TEST_PATH))
        if Draft202012Validator is not None:
            Draft202012Validator.check_schema(self.contract_schema)
            Draft202012Validator.check_schema(self.observation_schema)
            self.contract_validator.validate(self.contract_document)
        for schema in (self.contract_schema, self.observation_schema):
            for object_schema in _walk_object_schemas(schema):
                self.assertIs(False, object_schema.get("additionalProperties"))

    def test_valid_headerless_row_seals_raw_record_row_and_chain(self) -> None:
        raw = _raw()
        document = _new_document(raw)
        self.assertEqual("NEW_REPORT_SEALED", document["state"])
        evidence = document["state_evidence"]
        self.assertEqual(hashlib.sha256(raw).hexdigest(), evidence["raw_seal"]["raw_response_sha256"])
        self.assertEqual("CRLF", evidence["record_seal"]["selected_record_terminator"])
        self.assertEqual(87, evidence["field_count"])
        self.assertEqual(source.GENESIS_SHA256, evidence["predecessor_sha256"])
        self.assertEqual(SYNTHETIC_NOTICE, source.SYNTHETIC_FIXTURE_MARKER)
        self.assertEqual(raw, _raw())
        self.assert_schema_valid(document)

    def test_header_present_is_explicitly_rejected(self) -> None:
        document = _document(_evaluate(_raw(header=True)))
        self.assertEqual("MALFORMED_REPORT", document["state"])
        self.assertEqual("HEADER_PRESENT", document["state_evidence"]["reason"])

    def test_86_and_88_field_rows_fail_closed(self) -> None:
        for row in (_synthetic_row()[:-1], _synthetic_row() + ["EXTRA"]):
            with self.subTest(field_count=len(row)):
                document = _document(_evaluate(_raw(row=row)))
                self.assertEqual("FIELD_COUNT_INVALID", document["state_evidence"]["reason"])

    def test_old_v1_date_unequal_date_and_non_tuesday_fail_closed(self) -> None:
        old = _synthetic_row()
        old[2] = "08/04/2026"
        unequal = _synthetic_row()
        unequal[2] = "2026-07-28"
        non_tuesday = _synthetic_row(report_day="2026-08-05")
        cases = ((old, "DATE_INVALID"), (unequal, "DATE_FIELDS_MISMATCH"), (non_tuesday, "NON_TUESDAY_REPORT_DATE"))
        for row, reason in cases:
            with self.subTest(reason=reason):
                document = _document(_evaluate(_raw(row=row)))
                self.assertEqual("MALFORMED_REPORT", document["state"])
                self.assertEqual(reason, document["state_evidence"]["reason"])

    def test_every_nonempty_record_obeys_positional_date_contract(self) -> None:
        target = _synthetic_row()
        other = _synthetic_row(contract_code="999999")
        other[2] = "08/04/2026"
        document = _document(_evaluate(_raw(rows=[other, target])))
        self.assertEqual("DATE_INVALID", document["state_evidence"]["reason"])

    def test_values_are_not_trimmed_normalized_aliased_or_repaired(self) -> None:
        spaced_contract = _synthetic_row()
        spaced_contract[3] = " 133741"
        absent = _document(_evaluate(_raw(row=spaced_contract)))
        self.assertEqual("CONTRACT_ABSENT", absent["state"])
        spaced_date = _synthetic_row()
        spaced_date[1] = " " + spaced_date[1]
        invalid = _document(_evaluate(_raw(row=spaced_date)))
        self.assertEqual("DATE_INVALID", invalid["state_evidence"]["reason"])

    def test_target_absence_and_duplicate_are_exact_states(self) -> None:
        absent = _document(_evaluate(_raw(row=_synthetic_row(contract_code="999999"))))
        duplicate = _document(_evaluate(_raw(rows=[_synthetic_row(), _synthetic_row()])))
        self.assertEqual(("CONTRACT_ABSENT", 0), (absent["state"], absent["state_evidence"]["match_count"]))
        self.assertEqual(("DUPLICATE_CONTRACT_ROW", 2), (duplicate["state"], duplicate["state_evidence"]["match_count"]))

    def test_market_family_and_secondary_identity_fail_closed(self) -> None:
        secondary = _synthetic_row()
        secondary[83] = "SYNTHETIC_MISMATCH"
        cases = (
            (_synthetic_row(market_name="SYNTHETIC OTHER MARKET"), "MARKET_IDENTITY_DRIFT"),
            (_synthetic_row(family="Combined"), "REPORT_FAMILY_DRIFT"),
            (secondary, "SECONDARY_IDENTITY_DRIFT"),
        )
        for row, reason in cases:
            with self.subTest(reason=reason):
                document = _document(_evaluate(_raw(row=row)))
                self.assertEqual("WRONG_REPORT_FAMILY", document["state"])
                self.assertEqual(reason, document["state_evidence"]["reason"])

    def test_all_ten_retained_states_are_reachable(self) -> None:
        states: set[str] = set()
        states.add(_document(_evaluate(None, evaluated_at="2026-08-08T01:04:59Z"))["state"])
        states.add(_document(_evaluate(None))["state"])
        states.add(_document(_evaluate(_raw(row=_synthetic_row(contract_code="999999"))))["state"])
        states.add(_document(_evaluate(_raw(rows=[_synthetic_row(), _synthetic_row()])))["state"])
        states.add(_document(_evaluate(_raw(header=True)))["state"])
        states.add(_document(_evaluate(_raw(row=_synthetic_row(family="Combined"))))["state"])
        states.add(_document(_evaluate(_raw(row=_synthetic_row(report_day="2026-08-11"))))["state"])
        new = _new_document()
        states.add(new["state"])
        prior = _prior(new)
        states.add(_document(_evaluate(_raw(), predecessor_sha256=prior["chain_sha256"], prior_accepted=prior))["state"])
        changed = _synthetic_row()
        changed[8] = "SYNTHETIC_CHANGED_RAW_VALUE"
        states.add(_document(_evaluate(_raw(row=changed), predecessor_sha256=prior["chain_sha256"], prior_accepted=prior))["state"])
        self.assertEqual(set(source.STATES), states)

    def test_release_receipt_decision_and_predecessor_gates_fail_closed(self) -> None:
        wrong_offset = _release_proof(release_at="2026-08-07T15:30:00-05:00")
        self.assert_violation("CLOCK_DRIFT", lambda: _evaluate(None, proof=wrong_offset))
        self.assert_violation("LEAKAGE", lambda: _evaluate(_raw(), decision_schedule=_decision(decision_at=EVALUATED_AT)))
        self.assert_violation("PREDECESSOR_MISMATCH", lambda: _evaluate(_raw(), predecessor_sha256="9" * 64, decision_schedule=_decision()))

    def test_same_date_idempotency_and_byte_drift_are_separate(self) -> None:
        new = _new_document()
        prior = _prior(new)
        same = _document(_evaluate(_raw(), predecessor_sha256=prior["chain_sha256"], prior_accepted=prior))
        changed = _synthetic_row()
        changed[8] = "SYNTHETIC_CHANGED_RAW_VALUE"
        drift = _document(_evaluate(_raw(row=changed), predecessor_sha256=prior["chain_sha256"], prior_accepted=prior))
        self.assertEqual("NO_NEW_REPORT", same["state"])
        self.assertEqual("SAME_REPORT_BYTES_DRIFT", drift["state"])
        self.assertIs(True, drift["state_evidence"]["integrity_blocked"])

    def test_observation_canonical_duplicate_key_and_chain_tamper_rejected(self) -> None:
        raw = _evaluate(_raw(), decision_schedule=_decision())
        duplicate = b'{"authorization":"' + source.AUTHORIZATION.encode("ascii") + b'",' + raw[1:]
        self.assert_violation("DUPLICATE_JSON_KEY", lambda: source.validate_observation_bytes(duplicate))
        self.assert_violation("NONCANONICAL_JSON", lambda: source.validate_observation_bytes(b" " + raw))
        changed = deepcopy(_document(raw))
        changed["state_evidence"]["record_seal"]["selected_record_sha256"] = "f" * 64
        self.assert_violation("HASH_MISMATCH", lambda: source.validate_observation_bytes(source.canonical_json_bytes(changed)))

    def test_closed_schemas_and_manual_validator_reject_extras(self) -> None:
        changed_contract = deepcopy(self.contract_document)
        changed_contract["extra"] = True
        if self.contract_validator is not None:
            with self.assertRaises(ValidationError):
                self.contract_validator.validate(changed_contract)
        observation = _new_document()
        changed_observation = deepcopy(observation)
        changed_observation["extra"] = True
        if self.observation_validator is not None:
            with self.assertRaises(ValidationError):
                self.observation_validator.validate(changed_observation)
        self.assert_violation("CONTRACT_MISMATCH", lambda: source.validate_observation_bytes(source.canonical_json_bytes(changed_observation)))

    def test_no_derived_factor_strategy_or_economic_fields(self) -> None:
        observation = _new_document()
        forbidden = {"factor", "formula", "normalization", "threshold", "direction", "horizon", "signal", "return", "fee", "slippage", "pnl", "drawdown", "strategy"}

        def keys(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    yield key.lower()
                    yield from keys(child)
            elif isinstance(value, list):
                for child in value:
                    yield from keys(child)

        self.assertTrue(forbidden.isdisjoint(set(keys(observation))))
        for field in forbidden:
            changed = deepcopy(observation)
            changed[field] = SYNTHETIC_NOTICE
            self.assert_violation("CONTRACT_MISMATCH", lambda changed=changed: source.validate_observation_bytes(source.canonical_json_bytes(changed)))


if __name__ == "__main__":
    unittest.main()
