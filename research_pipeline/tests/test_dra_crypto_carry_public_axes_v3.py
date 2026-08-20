from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

from research_pipeline import dra_crypto_carry_public_axes_v3 as contract


def _payload() -> dict[str, object]:
    frozen = contract.frozen_contract()
    fingerprints = contract.expected_request_fingerprints()
    endpoints = []
    for request in frozen["requests"]:
        endpoint_id = request["endpoint_id"]
        endpoints.append({
            "endpoint_id": endpoint_id,
            "request_fingerprint_sha256": fingerprints[endpoint_id],
            "credential_sent": False,
            "http_status": 200,
            "response_observed_at": "2026-08-19T07:01:00Z",
            "top_level_keys": request["expected_top_level_keys"],
            "data_row_count": 1,
            "data_row_keys": request["expected_data_row_keys"],
            "data_value_types": {key: "STRING" for key in request["expected_data_row_keys"]},
            "required_identity_fields_present": request["required_identity_fields"],
            "required_timestamp_fields_present": request["required_timestamp_fields"],
            "raw_response_sha256": hashlib.sha256(endpoint_id.encode()).hexdigest(),
            "values_redacted": True,
            "schema_match": True,
        })
    return {
        "schema_version": "3",
        "document_type": contract.DOCUMENT_TYPE,
        "authorization": contract.AUTHORIZATION,
        "source_contract_id": contract.CONTRACT_ID,
        "source_contract_sha256": contract.CONTRACT_SHA256,
        "probed_at": "2026-08-19T07:02:00Z",
        "probe_host_identity": "APPROVED_ISOLATED_CREDENTIAL_FREE_SOURCE_HOST",
        "network_path": "SOURCE_HOST_HTTPS_GET_ONLY_NO_RESEARCH_WORKER_NETWORK",
        "endpoints": endpoints,
        "no_market_values_persisted": True,
        "raw_responses_retained_on_probe_host_only": True,
        "source_activation_authorized": False,
    }


class CarryPublicAxesV3Test(unittest.TestCase):
    def assert_violation(self, code: str, action) -> None:
        with self.assertRaises(contract.ContractViolation) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def test_frozen_contract_and_schema_are_safe(self) -> None:
        self.assertEqual(
            {"contract_sha256": contract.CONTRACT_SHA256, "schema_sha256": contract.SCHEMA_SHA256},
            contract.validate_frozen_files(),
        )
        frozen = contract.frozen_contract()
        self.assertFalse(frozen["readiness"]["source_registered"])
        self.assertFalse(frozen["readiness"]["capture_authorized"])
        self.assertFalse(frozen["readiness"]["factor_formula_authorized"])

    def test_schema_is_closed_and_meta_valid(self) -> None:
        schema = json.loads(contract.SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        self.assertFalse(schema["additionalProperties"])
        self.assertFalse(schema["$defs"]["endpointProbe"]["additionalProperties"])
        self.assertFalse(schema["$defs"]["seal"]["additionalProperties"])

    def test_valid_redacted_probe_is_canonical_and_deterministic(self) -> None:
        first = contract.seal_probe_payload(_payload())
        second = contract.seal_probe_payload(_payload())
        self.assertEqual(first, second)
        validated = contract.validate_probe_bytes(first)
        schema = json.loads(contract.SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator(schema).validate(validated)
        self.assertFalse(validated["source_activation_authorized"])

    def test_wrong_order_or_request_fingerprint_is_rejected(self) -> None:
        payload = _payload()
        payload["endpoints"][0], payload["endpoints"][1] = payload["endpoints"][1], payload["endpoints"][0]
        self.assert_violation("SOURCE_IDENTITY_MISMATCH", lambda: contract.seal_probe_payload(payload))
        payload = _payload()
        payload["endpoints"][0]["request_fingerprint_sha256"] = "0" * 64
        self.assert_violation("SOURCE_IDENTITY_MISMATCH", lambda: contract.seal_probe_payload(payload))

    def test_schema_key_or_type_drift_is_rejected(self) -> None:
        payload = _payload()
        payload["endpoints"][2]["data_row_keys"].pop()
        self.assert_violation("SCHEMA_DRIFT", lambda: contract.seal_probe_payload(payload))
        payload = _payload()
        first_key = payload["endpoints"][3]["data_row_keys"][0]
        payload["endpoints"][3]["data_value_types"][first_key] = "NULL"
        self.assert_violation("SCHEMA_DRIFT", lambda: contract.seal_probe_payload(payload))

    def test_credentials_values_or_activation_are_rejected(self) -> None:
        payload = _payload()
        payload["endpoints"][0]["credential_sent"] = True
        self.assert_violation("SOURCE_ACCESS_REJECT", lambda: contract.seal_probe_payload(payload))
        payload = _payload()
        payload["market_value"] = "forbidden"
        self.assert_violation("CONTRACT_MISMATCH", lambda: contract.seal_probe_payload(payload))
        payload = _payload()
        payload["source_activation_authorized"] = True
        self.assert_violation("CONTRACT_MISMATCH", lambda: contract.seal_probe_payload(payload))

    def test_precontract_or_postseal_time_is_rejected(self) -> None:
        payload = _payload()
        payload["endpoints"][0]["response_observed_at"] = "2026-08-19T06:59:59Z"
        self.assert_violation("CLOCK_DRIFT", lambda: contract.seal_probe_payload(payload))
        payload = _payload()
        payload["endpoints"][0]["response_observed_at"] = "2026-08-19T07:03:00Z"
        self.assert_violation("CLOCK_DRIFT", lambda: contract.seal_probe_payload(payload))

    def test_noncanonical_duplicate_and_tampered_seal_are_rejected(self) -> None:
        raw = contract.seal_probe_payload(_payload())
        self.assert_violation("NONCANONICAL_JSON", lambda: contract.validate_probe_bytes(raw + b"\n"))
        self.assert_violation("DUPLICATE_JSON_KEY", lambda: contract.validate_probe_bytes(b'{"schema_version":"3","schema_version":"4"}'))
        document = json.loads(raw)
        document["probe_seal"]["payload_sha256"] = "0" * 64
        self.assert_violation("HASH_MISMATCH", lambda: contract.validate_probe_bytes(contract.canonical_json_bytes(document)))


class CarryPublicAxesV3StaticBoundaryTest(unittest.TestCase):
    def test_validator_has_no_network_or_runtime_entrypoint(self) -> None:
        source = Path(contract.__file__).read_text(encoding="utf-8")
        for token in ("import requests", "import urllib", "import socket", "import subprocess", "argparse", "__" + "main__"):
            self.assertNotIn(token, source)


if __name__ == "__main__":
    unittest.main()
