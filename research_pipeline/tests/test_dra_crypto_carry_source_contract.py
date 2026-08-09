from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import unittest

from research_pipeline import dra_crypto_carry_source_contract as contract


TARGET_DAY = "2026-08-10"
INVENTORY_SCHEDULE = "2026-08-09T01:05:00Z"
INVENTORY_CAPTURE = "2026-08-09T01:05:02Z"
DAY_SCHEDULE = "2026-08-11T01:05:00Z"
DAY_CAPTURE = "2026-08-11T01:05:03Z"
ELIGIBLE_DAY = "2026-08-12"


def _millis(timestamp: str) -> str:
    parsed = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ").replace(
        tzinfo=timezone.utc
    )
    return str(int(parsed.timestamp()) * 1000)


def _instrument(inst_id: str, expiry: str) -> dict[str, str]:
    return {
        "instId": inst_id,
        "instType": "FUTURES",
        "instFamily": "BTC-USDT",
        "uly": "BTC-USDT",
        "ctType": "linear",
        "settleCcy": "USDT",
        "state": "live",
        "ruleType": "normal",
        "listTime": _millis("2026-08-01T00:00:00Z"),
        "expTime": _millis(expiry),
    }


def _inventory_payload(*, reversed_order: bool = False) -> dict[str, object]:
    instruments = [
        _instrument("BTC-USDT-260814", "2026-08-14T08:00:00Z"),
        _instrument("BTC-USDT-260821", "2026-08-21T08:00:00Z"),
    ]
    if reversed_order:
        instruments.reverse()
    return {
        "schema_version": contract.INVENTORY_SCHEMA_VERSION,
        "document_type": "PRIOR_CYCLE_EXPIRY_FUTURES_INVENTORY",
        "authorization": contract.AUTHORIZATION,
        "source_label": contract.SOURCE_LABEL,
        "source_contract_sha256": contract.SOURCE_CONTRACT_SHA256,
        "target_day": TARGET_DAY,
        "scheduled_cycle_at": INVENTORY_SCHEDULE,
        "captured_at": INVENTORY_CAPTURE,
        "request": deepcopy(contract.INVENTORY_REQUEST),
        "inventory_count": 2,
        "instruments": instruments,
    }


def _inventory_bytes(*, reversed_order: bool = False) -> bytes:
    return contract.seal_inventory_document(
        _inventory_payload(reversed_order=reversed_order)
    )


def _futures_atom(inst_id: str, close: str) -> dict[str, object]:
    return {
        "instId": inst_id,
        "row": [
            _millis("2026-08-10T00:00:00Z"),
            "100.00",
            "112.00",
            "91.00",
            close,
            "10.0",
            "1.0",
            "1000.0",
            "1",
        ],
    }


def _day_payload(
    inventory_raw: bytes, *, reversed_order: bool = False
) -> dict[str, object]:
    futures = [
        _futures_atom("BTC-USDT-260814", "105.00"),
        _futures_atom("BTC-USDT-260821", "106.00"),
    ]
    if reversed_order:
        futures.reverse()
    return {
        "schema_version": contract.DAY_SCHEMA_VERSION,
        "document_type": "COMPLETE_CONFIRMED_TARGET_DAY_RAW_ATOMS",
        "authorization": contract.AUTHORIZATION,
        "source_label": contract.SOURCE_LABEL,
        "source_contract_sha256": contract.SOURCE_CONTRACT_SHA256,
        "inventory_schema_sha256": contract.INVENTORY_SCHEMA_SHA256,
        "day_schema_sha256": contract.DAY_SCHEMA_SHA256,
        "inventory_sha256": contract.document_sha256(inventory_raw),
        "target_day": TARGET_DAY,
        "scheduled_cycle_at": DAY_SCHEDULE,
        "captured_at": DAY_CAPTURE,
        "first_eligible_utc_decision_day": ELIGIBLE_DAY,
        "requests": deepcopy(contract.DAY_REQUESTS),
        "expected_instrument_count": 2,
        "observed_instrument_count": 2,
        "cache_order_semantics": (
            "VALIDATE_COMPLETE_SET_THEN_SORT_BY_FROZEN_INST_ID"
        ),
        "futures": futures,
        "index": {
            "instId": "BTC-USDT",
            "row": [
                _millis("2026-08-10T00:00:00Z"),
                "100.00",
                "110.00",
                "90.00",
                "104.00",
                "1",
            ],
        },
        "eligibility": {
            "target_day_use": "DENY_LEAKAGE",
            "d_plus_1_use": "DENY_CAPTURE_AFTER_DECISION",
            "first_eligible_utc_decision_day": ELIGIBLE_DAY,
            "retroactive_admission": "DENY",
            "late_retry": "DENY",
            "backfill": "DENY",
            "partial_day_salvage": "DENY",
        },
    }


def _day_bytes(inventory_raw: bytes, *, reversed_order: bool = False) -> bytes:
    return contract.seal_day_document(
        _day_payload(inventory_raw, reversed_order=reversed_order), inventory_raw
    )


def _reseal(raw: bytes, seal_key: str, mutate) -> bytes:
    document = json.loads(raw.decode("utf-8"))
    mutate(document)
    document.pop(seal_key, None)
    if seal_key == "inventory_seal":
        canonicalization = contract.INVENTORY_CANONICALIZATION
    else:
        canonicalization = contract.DAY_CANONICALIZATION
    document[seal_key] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            contract.canonical_json_bytes(document)
        ).hexdigest(),
        "canonicalization": canonicalization,
        "sealed_at": document["captured_at"],
    }
    return contract.canonical_json_bytes(document)


class DraCryptoCarrySourceContractTest(unittest.TestCase):
    def assert_violation(self, code: str, action) -> None:
        with self.assertRaises(contract.ContractViolation) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def test_frozen_files_hashes_and_closed_schemas(self) -> None:
        self.assertEqual(
            {
                "source_contract_sha256": contract.SOURCE_CONTRACT_SHA256,
                "inventory_schema_sha256": contract.INVENTORY_SCHEMA_SHA256,
                "day_schema_sha256": contract.DAY_SCHEMA_SHA256,
            },
            contract.validate_frozen_files(),
        )
        source = json.loads(contract.SOURCE_CONTRACT_PATH.read_text("utf-8"))
        self.assertEqual("OFFLINE_DISABLED_NOT_REGISTERED", source["document_status"])
        self.assertEqual(contract.SOURCE_LABEL, source["selected_source_label"])
        self.assertEqual(
            [contract.REJECTED_SOURCE_LABEL], source["rejected_source_labels"]
        )
        for path in (contract.INVENTORY_SCHEMA_PATH, contract.DAY_SCHEMA_PATH):
            schema = json.loads(path.read_text("utf-8"))
            self.assertEqual(
                "https://json-schema.org/draft/2020-12/schema", schema["$schema"]
            )
            self.assertIs(False, schema["additionalProperties"])
            for definition in schema["$defs"].values():
                if definition.get("type") == "object":
                    self.assertIs(False, definition["additionalProperties"])

    def test_valid_inventory_is_canonical_and_cache_order_independent(self) -> None:
        forward = _inventory_bytes()
        reverse = _inventory_bytes(reversed_order=True)
        self.assertEqual(forward, reverse)
        validated = contract.validate_inventory_bytes(forward)
        self.assertEqual(
            ["BTC-USDT-260814", "BTC-USDT-260821"],
            [item["instId"] for item in validated["instruments"]],
        )
        self.assertEqual(forward, contract.canonical_json_bytes(validated))

    def test_valid_day_is_canonical_and_cache_order_independent(self) -> None:
        inventory = _inventory_bytes()
        forward = _day_bytes(inventory)
        reverse = _day_bytes(inventory, reversed_order=True)
        self.assertEqual(forward, reverse)
        validated = contract.validate_day_bundle_bytes(forward, inventory)
        self.assertEqual(
            ["BTC-USDT-260814", "BTC-USDT-260821"],
            [item["instId"] for item in validated["futures"]],
        )
        contract.require_eligible_decision_day(validated, ELIGIBLE_DAY)

    def test_wrong_inventory_source_hash_is_rejected(self) -> None:
        raw = _inventory_bytes()
        changed = _reseal(
            raw,
            "inventory_seal",
            lambda value: value.__setitem__("source_contract_sha256", "0" * 64),
        )
        self.assert_violation(
            "SOURCE_HASH_MISMATCH",
            lambda: contract.validate_inventory_bytes(changed),
        )

    def test_wrong_source_label_is_rejected(self) -> None:
        inventory = _inventory_bytes()
        changed_inventory = _reseal(
            inventory,
            "inventory_seal",
            lambda value: value.__setitem__("source_label", "UNLISTED_SOURCE"),
        )
        self.assert_violation(
            "SOURCE_HASH_MISMATCH",
            lambda: contract.validate_inventory_bytes(changed_inventory),
        )
        day = _day_bytes(inventory)
        changed_day = _reseal(
            day,
            "day_seal",
            lambda value: value.__setitem__("source_label", "UNLISTED_SOURCE"),
        )
        self.assert_violation(
            "HASH_MISMATCH",
            lambda: contract.validate_day_bundle_bytes(changed_day, inventory),
        )

    def test_inventory_clock_drift_is_rejected(self) -> None:
        payload = _inventory_payload()
        for key, value in (
            ("scheduled_cycle_at", "2026-08-09T01:04:59Z"),
            ("captured_at", "2026-08-09T01:04:59Z"),
            ("captured_at", "2026-08-10T00:00:00Z"),
        ):
            with self.subTest(key=key, value=value):
                changed = deepcopy(payload)
                changed[key] = value
                self.assert_violation(
                    "CLOCK_DRIFT",
                    lambda changed=changed: contract.seal_inventory_document(changed),
                )

    def test_inventory_metadata_drift_is_rejected(self) -> None:
        for field, value in (
            ("instType", "SWAP"),
            ("instFamily", "ETH-USDT"),
            ("uly", "BTC-USD"),
            ("ctType", "inverse"),
            ("settleCcy", "BTC"),
            ("state", "suspend"),
            ("ruleType", "xrule"),
            ("instId", "BTC-USDT-SWAP"),
        ):
            with self.subTest(field=field):
                payload = _inventory_payload()
                payload["instruments"][0][field] = value
                self.assert_violation(
                    "INVENTORY_INVALID",
                    lambda payload=payload: contract.seal_inventory_document(payload),
                )

    def test_inventory_list_and_expiry_drift_are_rejected(self) -> None:
        payload = _inventory_payload()
        payload["instruments"][0]["listTime"] = _millis("2026-08-09T02:00:00Z")
        self.assert_violation(
            "INVENTORY_INVALID",
            lambda: contract.seal_inventory_document(payload),
        )
        payload = _inventory_payload()
        payload["instruments"][0]["expTime"] = _millis("2026-08-11T00:00:00Z")
        self.assert_violation(
            "INVENTORY_INVALID",
            lambda: contract.seal_inventory_document(payload),
        )

    def test_empty_and_duplicate_inventory_are_rejected(self) -> None:
        empty = _inventory_payload()
        empty["inventory_count"] = 0
        empty["instruments"] = []
        self.assert_violation(
            "INVENTORY_INVALID", lambda: contract.seal_inventory_document(empty)
        )
        duplicate = _inventory_payload()
        duplicate["instruments"][1]["instId"] = duplicate["instruments"][0]["instId"]
        self.assert_violation(
            "INVENTORY_INVALID",
            lambda: contract.seal_inventory_document(duplicate),
        )

    def test_alias_and_inferred_maturity_fields_are_rejected(self) -> None:
        for extra in ("alias", "maturityLabel"):
            payload = _inventory_payload()
            payload["instruments"][0][extra] = "forbidden"
            self.assert_violation(
                "CONTRACT_MISMATCH",
                lambda payload=payload: contract.seal_inventory_document(payload),
            )

    def test_noncanonical_and_duplicate_key_json_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        self.assert_violation(
            "NONCANONICAL_JSON",
            lambda: contract.validate_inventory_bytes(inventory + b"\n"),
        )
        duplicate = b'{"schema_version":"x","schema_version":"y"}'
        self.assert_violation(
            "DUPLICATE_JSON_KEY",
            lambda: contract.validate_inventory_bytes(duplicate),
        )

    def test_day_hash_bindings_are_rejected_on_drift(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for key in (
            "source_contract_sha256",
            "inventory_schema_sha256",
            "day_schema_sha256",
            "inventory_sha256",
        ):
            with self.subTest(key=key):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda value, key=key: value.__setitem__(key, "0" * 64),
                )
                self.assert_violation(
                    "HASH_MISMATCH",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_day_clock_and_deadline_drift_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for key, value in (
            ("scheduled_cycle_at", "2026-08-11T01:04:59Z"),
            ("captured_at", "2026-08-11T01:04:59Z"),
            ("captured_at", "2026-08-11T06:00:00Z"),
        ):
            with self.subTest(key=key, value=value):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda document, key=key, value=value: document.__setitem__(
                        key, value
                    ),
                )
                self.assert_violation(
                    "CLOCK_DRIFT",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_same_day_and_d_plus_1_use_are_leakage(self) -> None:
        inventory = _inventory_bytes()
        validated = contract.validate_day_bundle_bytes(
            _day_bytes(inventory), inventory
        )
        for decision_day in (TARGET_DAY, "2026-08-11", "2026-08-13"):
            with self.subTest(decision_day=decision_day):
                self.assert_violation(
                    "LEAKAGE",
                    lambda decision_day=decision_day: contract.require_eligible_decision_day(
                        validated, decision_day
                    ),
                )

    def test_first_eligibility_field_drift_is_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        changed = _reseal(
            raw,
            "day_seal",
            lambda value: value.__setitem__(
                "first_eligible_utc_decision_day", "2026-08-11"
            ),
        )
        self.assert_violation(
            "LEAKAGE",
            lambda: contract.validate_day_bundle_bytes(changed, inventory),
        )

    def test_missing_extra_and_duplicate_futures_rows_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)

        def missing(value) -> None:
            value["futures"].pop()
            value["observed_instrument_count"] = 1

        def extra(value) -> None:
            value["futures"].append(_futures_atom("BTC-USDT-260828", "107.00"))
            value["observed_instrument_count"] = 3

        def duplicate(value) -> None:
            value["futures"][1]["instId"] = value["futures"][0]["instId"]

        for label, mutate in (("missing", missing), ("extra", extra), ("duplicate", duplicate)):
            with self.subTest(label=label):
                changed = _reseal(raw, "day_seal", mutate)
                self.assert_violation(
                    "INVENTORY_COVERAGE_MISMATCH",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_unconfirmed_and_timestamp_mismatch_rows_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for position, value in ((8, "0"), (0, _millis("2026-08-10T00:00:01Z"))):
            with self.subTest(position=position):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda document, position=position, value=value: document[
                        "futures"
                    ][0]["row"].__setitem__(position, value),
                )
                self.assert_violation(
                    "DAY_ROW_INVALID",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_invalid_ohlc_and_volume_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for position, value in ((1, "NaN"), (2, "80"), (3, "120"), (5, "-1")):
            with self.subTest(position=position, value=value):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda document, position=position, value=value: document[
                        "futures"
                    ][0]["row"].__setitem__(position, value),
                )
                self.assert_violation(
                    "DAY_ROW_INVALID",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_index_identity_and_row_shape_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for mutate, code in (
            (lambda value: value["index"].__setitem__("instId", "ETH-USDT"), "SOURCE_IDENTITY_MISMATCH"),
            (lambda value: value["index"]["row"].append("extra"), "DAY_ROW_INVALID"),
            (lambda value: value["index"]["row"].__setitem__(5, "0"), "DAY_ROW_INVALID"),
        ):
            changed = _reseal(raw, "day_seal", mutate)
            self.assert_violation(
                code,
                lambda changed=changed: contract.validate_day_bundle_bytes(
                    changed, inventory
                ),
            )

    def test_derived_economic_fields_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for field in (
            "basis",
            "carry",
            "annualization",
            "funding",
            "tenor",
            "maturity_preference",
            "roll",
            "liquidity",
            "threshold",
            "signal",
            "return",
            "pnl",
            "drawdown",
        ):
            with self.subTest(field=field):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda value, field=field: value.__setitem__(field, "forbidden"),
                )
                self.assert_violation(
                    "CONTRACT_MISMATCH",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_source_request_overrides_are_rejected(self) -> None:
        inventory = _inventory_bytes()
        raw = _day_bytes(inventory)
        for key, value in (
            ("origin", "https://example.invalid"),
            ("path", "/alternate"),
            ("bar", "1H"),
            ("instId", "BTC-USDT-SWAP"),
        ):
            with self.subTest(key=key):
                changed = _reseal(
                    raw,
                    "day_seal",
                    lambda document, key=key, value=value: document["requests"][
                        "futures"
                    ].__setitem__(key, value),
                )
                self.assert_violation(
                    "SOURCE_IDENTITY_MISMATCH",
                    lambda changed=changed: contract.validate_day_bundle_bytes(
                        changed, inventory
                    ),
                )

    def test_seal_and_raw_bytes_are_hash_bound(self) -> None:
        inventory = _inventory_bytes()
        day = _day_bytes(inventory)
        changed_inventory = bytearray(inventory)
        changed_inventory[-2] = ord("0") if changed_inventory[-2] != ord("0") else ord("1")
        self.assert_violation(
            "NONCANONICAL_JSON",
            lambda: contract.validate_inventory_bytes(bytes(changed_inventory)),
        )
        changed_day = _reseal(
            day,
            "day_seal",
            lambda value: value["futures"][0]["row"].__setitem__(4, "105.01"),
        )
        self.assertNotEqual(day, changed_day)
        contract.validate_day_bundle_bytes(changed_day, inventory)


class StaticBoundaryTest(unittest.TestCase):
    def test_validator_has_no_runtime_entrypoint_or_network_import(self) -> None:
        source = Path(contract.__file__).read_text("utf-8")
        for token in (
            "import requests",
            "import urllib",
            "import socket",
            "import subprocess",
            "argparse",
            "__" + "main__",
        ):
            self.assertNotIn(token, source)
