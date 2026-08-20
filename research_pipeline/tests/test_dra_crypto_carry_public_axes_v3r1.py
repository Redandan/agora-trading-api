from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

from research_pipeline import dra_crypto_carry_public_axes_v3r1 as probe


REPO_ROOT = Path(__file__).resolve().parents[2]


def _identity(endpoint_id: str) -> dict[str, str]:
    if endpoint_id == "FUTURES_OPEN_INTEREST":
        return {"instId": "BTC-USDT-260925", "instType": "FUTURES"}
    if endpoint_id == "SWAP_OPEN_INTEREST":
        return {"instId": "BTC-USDT-SWAP", "instType": "SWAP"}
    if endpoint_id == "SWAP_FUNDING_RATE_HISTORY":
        return {"formulaType": "withRate", "instId": "BTC-USDT-SWAP", "method": "current_period"}
    return {"ccy": "USDT"}


def valid_payload() -> dict[str, object]:
    contract = probe.frozen_contract()
    fingerprints = probe.expected_request_fingerprints()
    endpoints: list[dict[str, object]] = []
    for index, request in enumerate(contract["requests"]):
        endpoint_id = request["endpoint_id"]
        endpoints.append({
            "endpoint_id": endpoint_id,
            "request_fingerprint_sha256": fingerprints[endpoint_id],
            "credential_sent": False,
            "http_status": 200,
            "response_observed_at": f"2026-08-19T06:23:0{index}Z",
            "top_level_keys": request["expected_top_level_keys"],
            "api_code": "0",
            "response_data_row_count": 2 if endpoint_id == "FUTURES_OPEN_INTEREST" else 1,
            "inspected_data_row_index": 0,
            "inspected_data_row_keys": request["expected_data_row_keys"],
            "inspected_data_value_types": {key: "STRING" for key in request["expected_data_row_keys"]},
            "identity_values": _identity(endpoint_id),
            "required_timestamp_fields_present": request["timestamp_fields"],
            "raw_response_sha256": hashlib.sha256(endpoint_id.encode("utf-8")).hexdigest(),
            "raw_response_bytes": 100 + index,
            "values_redacted": True,
            "schema_match": True,
        })
    return {
        "schema_version": "3.1",
        "document_type": probe.DOCUMENT_TYPE,
        "authorization": probe.AUTHORIZATION,
        "source_contract_id": probe.CONTRACT_ID,
        "source_contract_sha256": probe.CONTRACT_SHA256,
        "probed_at": "2026-08-19T06:23:10Z",
        "probe_host_identity": "APPROVED_ISOLATED_CREDENTIAL_FREE_SOURCE_HOST",
        "network_path": "SOURCE_HOST_HTTPS_GET_ONLY_NO_RESEARCH_WORKER_NETWORK",
        "endpoints": endpoints,
        "no_market_values_persisted": True,
        "raw_responses_persisted": False,
        "raw_responses_transported": False,
        "source_activation_authorized": False,
    }


class CarryPublicAxesV3R1Test(unittest.TestCase):
    def test_frozen_contract_schema_and_supersession_are_safe(self) -> None:
        self.assertEqual(
            probe.validate_frozen_files(),
            {"contract_sha256": probe.CONTRACT_SHA256, "schema_sha256": probe.SCHEMA_SHA256},
        )
        contract = probe.frozen_contract()
        self.assertFalse(contract["supersedes"]["probe_observed"])
        self.assertFalse(contract["supersedes"]["activation_observed"])
        self.assertTrue(contract["readiness"]["reuse_terms_conditionally_reviewed"])
        self.assertFalse(contract["readiness"]["source_registered"])
        self.assertEqual(len(contract["requests"]), 4)

    def test_schema_is_closed_and_meta_valid(self) -> None:
        schema = json.loads(probe.SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        self.assertFalse(schema["additionalProperties"])
        self.assertFalse(schema["$defs"]["endpointProbe"]["additionalProperties"])

    def test_valid_receipt_is_canonical_deterministic_and_schema_valid(self) -> None:
        raw1 = probe.seal_probe_payload(valid_payload())
        raw2 = probe.seal_probe_payload(valid_payload())
        self.assertEqual(raw1, raw2)
        self.assertEqual(probe.validate_probe_bytes(raw1)["source_activation_authorized"], False)
        schema = json.loads(probe.SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator(schema).validate(json.loads(raw1.decode("utf-8")))

    def test_fixed_identity_values_are_required(self) -> None:
        cases = [
            (0, {"instId": "ETH-USDT-260925", "instType": "FUTURES"}),
            (1, {"instId": "BTC-USDT-SWAP", "instType": "FUTURES"}),
            (2, {"formulaType": "", "instId": "BTC-USDT-SWAP", "method": "current_period"}),
            (3, {"ccy": "BTC"}),
        ]
        for index, identity_values in cases:
            with self.subTest(index=index):
                value = valid_payload()
                value["endpoints"][index]["identity_values"] = identity_values
                with self.assertRaisesRegex(probe.ContractViolation, "identity"):
                    probe.seal_probe_payload(value)

    def test_response_count_index_and_size_bounds_fail_closed(self) -> None:
        value = valid_payload()
        value["endpoints"][1]["response_data_row_count"] = 2
        with self.assertRaisesRegex(probe.ContractViolation, "exactly one row"):
            probe.seal_probe_payload(value)

        value = valid_payload()
        value["endpoints"][0]["inspected_data_row_index"] = 2
        with self.assertRaisesRegex(probe.ContractViolation, "row index"):
            probe.seal_probe_payload(value)

        value = valid_payload()
        value["endpoints"][0]["raw_response_bytes"] = 262145
        with self.assertRaisesRegex(probe.ContractViolation, "size"):
            probe.seal_probe_payload(value)

    def test_schema_types_fingerprint_and_timestamp_presence_fail_closed(self) -> None:
        value = valid_payload()
        value["endpoints"][0]["inspected_data_row_keys"] = ["instId"]
        with self.assertRaisesRegex(probe.ContractViolation, "row keys"):
            probe.seal_probe_payload(value)

        value = valid_payload()
        first_key = next(iter(value["endpoints"][0]["inspected_data_value_types"]))
        value["endpoints"][0]["inspected_data_value_types"][first_key] = "NUMBER"
        with self.assertRaisesRegex(probe.ContractViolation, "value types"):
            probe.seal_probe_payload(value)

        value = valid_payload()
        value["endpoints"][0]["request_fingerprint_sha256"] = "0" * 64
        with self.assertRaisesRegex(probe.ContractViolation, "fingerprint"):
            probe.seal_probe_payload(value)

        value = valid_payload()
        value["endpoints"][0]["required_timestamp_fields_present"] = []
        with self.assertRaisesRegex(probe.ContractViolation, "timestamp"):
            probe.seal_probe_payload(value)

    def test_credentials_values_activation_and_raw_transport_fail_closed(self) -> None:
        mutations = [
            (lambda value: value["endpoints"][0].__setitem__("credential_sent", True), "credential-free"),
            (lambda value: value["endpoints"][0].__setitem__("values_redacted", False), "redacted"),
            (lambda value: value.__setitem__("source_activation_authorized", True), "source_activation"),
            (lambda value: value.__setitem__("raw_responses_persisted", True), "raw_responses_persisted"),
            (lambda value: value.__setitem__("raw_responses_transported", True), "raw_responses_transported"),
        ]
        for mutate, error in mutations:
            with self.subTest(error=error):
                value = valid_payload()
                mutate(value)
                with self.assertRaisesRegex(probe.ContractViolation, error):
                    probe.seal_probe_payload(value)

    def test_clock_canonical_duplicate_and_seal_drift_are_rejected(self) -> None:
        value = valid_payload()
        value["probed_at"] = "2026-08-19T06:22:00Z"
        with self.assertRaisesRegex(probe.ContractViolation, "strictly after"):
            probe.seal_probe_payload(value)

        raw = probe.seal_probe_payload(valid_payload())
        document = json.loads(raw.decode("utf-8"))
        pretty = json.dumps(document, indent=2).encode("utf-8")
        with self.assertRaisesRegex(probe.ContractViolation, "not canonical"):
            probe.validate_probe_bytes(pretty)

        duplicate = raw.replace(b'{"algorithm":', b'{"algorithm":"SHA-256","algorithm":', 1)
        with self.assertRaisesRegex(probe.ContractViolation, "duplicate JSON key"):
            probe.validate_probe_bytes(duplicate)

        document["probe_seal"]["payload_sha256"] = "0" * 64
        with self.assertRaisesRegex(probe.ContractViolation, "seal mismatch"):
            probe.validate_probe_bytes(probe.canonical_json_bytes(document))


class CarryPublicAxesV3R1StaticBoundaryTest(unittest.TestCase):
    def test_validator_has_no_network_or_runtime_entrypoint(self) -> None:
        source = (REPO_ROOT / "research_pipeline" / "dra_crypto_carry_public_axes_v3r1.py").read_text(encoding="utf-8")
        for token in (
            "import requests", "import urllib", "import http.client", "import socket",
            "import aiohttp", "import subprocess", "if __name__ == \"__main__\"",
        ):
            self.assertNotIn(token, source)


if __name__ == "__main__":
    unittest.main()
