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
except ModuleNotFoundError:  # Local sandbox may lack the Manager's schema runtime.
    Draft202012Validator = None
    ValidationError = Exception

from research_pipeline import cftc_cme_bitcoin_tff_source as source


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
        "release_schedule_version": "CFTC_COT_RELEASE_SCHEDULE_SYNTHETIC_V1",
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
        "schedule_version": "V1",
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
    row[2] = day.strftime("%m/%d/%Y")
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


def _csv_bytes(rows: list[list[str]], *, terminator: str = "\r\n") -> bytes:
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator=terminator)
    writer.writerow(source.ORDERED_FIELDS)
    writer.writerows(rows)
    return buffer.getvalue().encode("utf-8")


def _raw(
    *,
    row: list[str] | None = None,
    rows: list[list[str]] | None = None,
    terminator: str = "\r\n",
) -> bytes:
    if rows is None:
        rows = [_synthetic_row() if row is None else row]
    return _csv_bytes(rows, terminator=terminator)


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
    raw_response = _raw() if raw_response is None else raw_response
    return _document(_evaluate(raw_response, decision_schedule=_decision()))


def _prior(document: dict[str, object]) -> dict[str, object]:
    evidence = document["state_evidence"]
    assert isinstance(evidence, dict)
    raw_seal = evidence["raw_seal"]
    record_seal = evidence["record_seal"]
    assert isinstance(raw_seal, dict)
    assert isinstance(record_seal, dict)
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


class CftcCmeBitcoinTffSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract_schema = json.loads(
            source.SOURCE_CONTRACT_SCHEMA_PATH.read_text("utf-8")
        )
        cls.observation_schema = json.loads(
            source.OBSERVATION_SCHEMA_PATH.read_text("utf-8")
        )
        cls.contract_document = json.loads(
            source.SOURCE_CONTRACT_PATH.read_text("utf-8")
        )
        cls.contract_validator = (
            None
            if Draft202012Validator is None
            else Draft202012Validator(cls.contract_schema)
        )
        cls.observation_validator = (
            None
            if Draft202012Validator is None
            else Draft202012Validator(cls.observation_schema)
        )

    def assert_violation(self, code: str, action) -> None:
        with self.assertRaises(source.ContractViolation) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def assert_schema_valid(self, document: dict[str, object]) -> None:
        if self.observation_validator is not None:
            self.observation_validator.validate(document)

    def test_frozen_package_hashes_contract_schema_and_closed_objects(self) -> None:
        self.assertEqual(
            {
                "source_contract_sha256": source.SOURCE_CONTRACT_SHA256,
                "source_contract_schema_sha256": source.SOURCE_CONTRACT_SCHEMA_SHA256,
                "observation_schema_sha256": source.OBSERVATION_SCHEMA_SHA256,
            },
            source.validate_frozen_package(),
        )
        if Draft202012Validator is not None:
            Draft202012Validator.check_schema(self.contract_schema)
            Draft202012Validator.check_schema(self.observation_schema)
            self.contract_validator.validate(self.contract_document)
        self.assertEqual(87, len(self.contract_document["ordered_fields"]))
        self.assertEqual(list(source.STATES), self.contract_document["state_machine"]["states"])
        for schema in (self.contract_schema, self.observation_schema):
            for object_schema in _walk_object_schemas(schema):
                self.assertIs(False, object_schema.get("additionalProperties"))

    def test_new_report_seals_raw_record_canonical_row_chain_and_suppression(self) -> None:
        raw = _raw()
        document = _new_document(raw)
        self.assertEqual("NEW_REPORT_SEALED", document["state"])
        evidence = document["state_evidence"]
        self.assertEqual(hashlib.sha256(raw).hexdigest(), evidence["raw_seal"]["raw_response_sha256"])
        self.assertEqual("CRLF", evidence["record_seal"]["selected_record_terminator"])
        self.assertEqual(87, evidence["field_count"])
        self.assertEqual(source.GENESIS_SHA256, evidence["predecessor_sha256"])
        self.assertEqual(SYNTHETIC_NOTICE, SYNTHETIC_NOTICE)
        self.assertIn("·", raw.decode("utf-8"))
        self.assertEqual(raw, _raw())
        self.assert_schema_valid(document)

    def test_all_ten_states_are_reachable_with_synthetic_inputs(self) -> None:
        states: set[str] = set()
        states.add(_document(_evaluate(None, evaluated_at="2026-08-08T01:04:59Z"))["state"])
        states.add(_document(_evaluate(None))["state"])
        states.add(_document(_evaluate(_raw(row=_synthetic_row(contract_code="999999"))))["state"])
        duplicate = _raw(rows=[_synthetic_row(), _synthetic_row()])
        states.add(_document(_evaluate(duplicate))["state"])
        malformed = _csv_bytes([_synthetic_row()])
        malformed = malformed.replace(b"Market_and_Exchange_Names", b"Wrong_Header", 1)
        states.add(_document(_evaluate(malformed))["state"])
        states.add(_document(_evaluate(_raw(row=_synthetic_row(family="Combined"))))["state"])
        future = _raw(row=_synthetic_row(report_day="2026-08-11"))
        states.add(_document(_evaluate(future))["state"])
        new = _new_document()
        states.add(new["state"])
        prior = _prior(new)
        states.add(
            _document(
                _evaluate(
                    _raw(),
                    predecessor_sha256=prior["chain_sha256"],
                    prior_accepted=prior,
                )
            )["state"]
        )
        changed = _synthetic_row()
        changed[8] = "SYNTHETIC_CHANGED_RAW_VALUE"
        states.add(
            _document(
                _evaluate(
                    _raw(row=changed),
                    predecessor_sha256=prior["chain_sha256"],
                    prior_accepted=prior,
                )
            )["state"]
        )
        self.assertEqual(set(source.STATES), states)

    def test_86_and_88_field_rows_fail_closed(self) -> None:
        for row in (_synthetic_row()[:-1], _synthetic_row() + ["EXTRA"]):
            with self.subTest(field_count=len(row)):
                document = _document(_evaluate(_raw(row=row)))
                self.assertEqual("MALFORMED_REPORT", document["state"])
                self.assertEqual("FIELD_COUNT_INVALID", document["state_evidence"]["reason"])

    def test_identity_family_and_date_drift_fail_closed(self) -> None:
        cases = []
        cases.append((_synthetic_row(market_name="SYNTHETIC OTHER MARKET"), "WRONG_REPORT_FAMILY", "MARKET_IDENTITY_DRIFT"))
        cases.append((_synthetic_row(family="Combined"), "WRONG_REPORT_FAMILY", "REPORT_FAMILY_DRIFT"))
        secondary = _synthetic_row()
        secondary[83] = "SYNTHETIC_MISMATCH"
        cases.append((secondary, "WRONG_REPORT_FAMILY", "SECONDARY_IDENTITY_DRIFT"))
        non_tuesday = _synthetic_row(report_day="2026-08-05")
        cases.append((non_tuesday, "MALFORMED_REPORT", "NON_TUESDAY_REPORT_DATE"))
        unexpected = _synthetic_row(report_day="2026-07-28")
        cases.append((unexpected, "MALFORMED_REPORT", "UNEXPECTED_REPORT_DATE"))
        for row, state, reason in cases:
            with self.subTest(state=state, reason=reason):
                document = _document(_evaluate(_raw(row=row)))
                self.assertEqual(state, document["state"])
                self.assertEqual(reason, document["state_evidence"]["reason"])

    def test_malformed_encoding_csv_and_date_are_state_bound(self) -> None:
        invalid_utf8 = b"\xff\xfe"
        utf8_document = _document(_evaluate(invalid_utf8))
        self.assertEqual("UTF8_INVALID", utf8_document["state_evidence"]["reason"])
        self.assertEqual(hashlib.sha256(invalid_utf8).hexdigest(), utf8_document["state_evidence"]["raw_seal"]["raw_response_sha256"])

        bad_csv = _raw().replace(b'"', b'', 1) + b'"'
        self.assertEqual("MALFORMED_REPORT", _document(_evaluate(bad_csv))["state"])

        bad_date = _synthetic_row()
        bad_date[2] = "2026-08-04"
        date_document = _document(_evaluate(_raw(row=bad_date)))
        self.assertEqual("DATE_INVALID", date_document["state_evidence"]["reason"])

    def test_observation_duplicate_key_and_noncanonical_bytes_are_rejected(self) -> None:
        raw = _evaluate(_raw(), decision_schedule=_decision())
        duplicate = b'{"authorization":"' + source.AUTHORIZATION.encode("ascii") + b'",' + raw[1:]
        self.assert_violation(
            "DUPLICATE_JSON_KEY",
            lambda: source.validate_observation_bytes(duplicate),
        )
        self.assert_violation(
            "NONCANONICAL_JSON",
            lambda: source.validate_observation_bytes(b" " + raw),
        )

    def test_raw_and_selected_record_mutation_break_chain_validation(self) -> None:
        document = _new_document()
        for path in (
            ("raw_seal", "raw_response_sha256"),
            ("record_seal", "selected_record_sha256"),
        ):
            changed = deepcopy(document)
            changed["state_evidence"][path[0]][path[1]] = "f" * 64
            with self.subTest(path=path):
                self.assert_violation(
                    "HASH_MISMATCH",
                    lambda changed=changed: source.validate_observation_bytes(
                        source.canonical_json_bytes(changed)
                    ),
                )

    def test_release_dst_coverage_and_clock_drift_are_rejected(self) -> None:
        wrong_offset = _release_proof(release_at="2026-08-07T15:30:00-05:00")
        out_of_coverage = _release_proof(coverage_start="2026-09-01")
        seven_day_lag = _release_proof(release_at="2026-08-11T15:30:00-04:00")
        cases = (
            (wrong_offset, SCHEDULED_CYCLE, EVALUATED_AT, "CLOCK_DRIFT"),
            (out_of_coverage, SCHEDULED_CYCLE, EVALUATED_AT, "RELEASE_PROOF_INVALID"),
            (seven_day_lag, "2026-08-12T01:05:00Z", "2026-08-12T01:05:05Z", "CLOCK_DRIFT"),
            (_release_proof(), "2026-08-08T01:04:59Z", EVALUATED_AT, "CLOCK_DRIFT"),
            (_release_proof(), SCHEDULED_CYCLE, "2026-08-09T01:05:00Z", "CLOCK_DRIFT"),
        )
        for proof, scheduled, evaluated, code in cases:
            with self.subTest(code=code, release_at=proof["release_at"]):
                self.assert_violation(
                    code,
                    lambda proof=proof, scheduled=scheduled, evaluated=evaluated: _evaluate(
                        None,
                        proof=proof,
                        scheduled_cycle_at=scheduled,
                        evaluated_at=evaluated,
                    ),
                )

        winter_proof = _release_proof(
            expected_tuesday="2026-12-01",
            release_at="2026-12-04T15:30:00-05:00",
        )
        winter = _document(
            _evaluate(
                None,
                proof=winter_proof,
                scheduled_cycle_at="2026-12-05T01:05:00Z",
                evaluated_at="2026-12-05T01:05:05Z",
            )
        )
        self.assertEqual("SOURCE_UNAVAILABLE", winter["state"])

    def test_same_cycle_or_earlier_decision_is_leakage(self) -> None:
        for decision_at in (EVALUATED_AT, "2026-08-08T01:05:04Z"):
            self.assert_violation(
                "LEAKAGE",
                lambda decision_at=decision_at: _evaluate(
                    _raw(), decision_schedule=_decision(decision_at=decision_at)
                ),
            )

    def test_predecessor_and_prior_identity_drift_fail_closed(self) -> None:
        self.assert_violation(
            "PREDECESSOR_MISMATCH",
            lambda: _evaluate(_raw(), predecessor_sha256="9" * 64, decision_schedule=_decision()),
        )
        first = _new_document()
        prior = _prior(first)
        self.assert_violation(
            "PREDECESSOR_MISMATCH",
            lambda: _evaluate(
                _raw(),
                predecessor_sha256="8" * 64,
                prior_accepted=prior,
            ),
        )
        drifted_prior = deepcopy(prior)
        drifted_prior["row_identity"]["contract_units"] = "OTHER_SYNTHETIC_UNITS"
        document = _document(
            _evaluate(
                _raw(),
                predecessor_sha256=drifted_prior["chain_sha256"],
                prior_accepted=drifted_prior,
            )
        )
        self.assertEqual("WRONG_REPORT_FAMILY", document["state"])

    def test_closed_schemas_reject_extras(self) -> None:
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
        self.assert_violation(
            "CONTRACT_MISMATCH",
            lambda: source.validate_observation_bytes(
                source.canonical_json_bytes(changed_observation)
            ),
        )

        changed_evidence = deepcopy(observation)
        changed_evidence["state_evidence"]["extra"] = True
        if self.observation_validator is not None:
            with self.assertRaises(ValidationError):
                self.observation_validator.validate(changed_evidence)

    def test_forbidden_derived_fields_are_not_admitted(self) -> None:
        observation = _new_document()
        forbidden = {
            "factor",
            "formula",
            "normalization",
            "threshold",
            "direction",
            "horizon",
            "signal",
            "return",
            "fee",
            "slippage",
            "pnl",
            "drawdown",
            "strategy",
        }

        def keys(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    yield key.lower()
                    yield from keys(child)
            elif isinstance(value, list):
                for child in value:
                    yield from keys(child)

        self.assertTrue(forbidden.isdisjoint(set(keys(observation))))
        for field in sorted(forbidden):
            changed = deepcopy(observation)
            changed[field] = SYNTHETIC_NOTICE
            with self.subTest(field=field):
                if self.observation_validator is not None:
                    with self.assertRaises(ValidationError):
                        self.observation_validator.validate(changed)
                self.assert_violation(
                    "CONTRACT_MISMATCH",
                    lambda changed=changed: source.validate_observation_bytes(
                        source.canonical_json_bytes(changed)
                    ),
                )


if __name__ == "__main__":
    unittest.main()
