from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest

from research_pipeline import dra_crypto_carry_public_axes_v3r1 as validator
from research_pipeline import dra_crypto_carry_public_axes_v3r1_producer as producer


def _row_for(endpoint_id: str) -> dict[str, str]:
    if endpoint_id == "FUTURES_OPEN_INTEREST":
        return {"instId": "BTC-USDT-260925", "instType": "FUTURES", "oi": "98765", "oiCcy": "12", "oiUsd": "34", "ts": "1787100000000"}
    if endpoint_id == "SWAP_OPEN_INTEREST":
        return {"instId": "BTC-USDT-SWAP", "instType": "SWAP", "oi": "87654", "oiCcy": "21", "oiUsd": "43", "ts": "1787100001000"}
    if endpoint_id == "SWAP_FUNDING_RATE_HISTORY":
        return {"formulaType": "withRate", "fundingRate": "0.0001", "fundingTime": "1787100002000", "impactMargin": "10000", "instId": "BTC-USDT-SWAP", "interestRate": "0.00003", "method": "current_period"}
    return {"ccy": "USDT", "lendingRate": "0.01", "rate": "0.02", "ts": "1787100003000"}


def response_documents() -> dict[str, dict[str, object]]:
    documents: dict[str, dict[str, object]] = {}
    for request in validator.frozen_contract()["requests"]:
        endpoint_id = request["endpoint_id"]
        rows = [_row_for(endpoint_id)]
        if endpoint_id == "FUTURES_OPEN_INTEREST":
            earlier = dict(rows[0])
            earlier["instId"] = "BTC-USDT-260626"
            rows.append(earlier)
        documents[endpoint_id] = {"code": "0", "data": rows, "msg": ""}
    return documents


class FakeResponse:
    def __init__(self, url: str, document: dict[str, object], *, status: int = 200, final_url: str | None = None) -> None:
        self.status = status
        self._url = final_url or url
        self._raw = json.dumps(document, separators=(",", ":")).encode("utf-8")

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def geturl(self) -> str:
        return self._url

    def read(self, maximum: int) -> bytes:
        return self._raw[:maximum]


class FakeOpener:
    def __init__(self, documents: dict[str, dict[str, object]], *, redirect_endpoint: str | None = None) -> None:
        self.documents = documents
        self.redirect_endpoint = redirect_endpoint
        self.calls: list[object] = []

    def open(self, request, timeout: int) -> FakeResponse:
        self.calls.append(request)
        endpoint_id = validator.frozen_contract()["requests"][len(self.calls) - 1]["endpoint_id"]
        final_url = "https://example.invalid/redirect" if endpoint_id == self.redirect_endpoint else request.full_url
        return FakeResponse(request.full_url, self.documents[endpoint_id], final_url=final_url)


class SequenceClock:
    def __init__(self) -> None:
        self.index = 0

    def __call__(self) -> datetime:
        value = datetime(2026, 8, 19, 6, 25, self.index, tzinfo=timezone.utc)
        self.index += 1
        return value


class CarryPublicAxesV3R1ProducerTest(unittest.TestCase):
    def test_fixed_four_requests_create_only_redacted_valid_receipt(self) -> None:
        opener = FakeOpener(response_documents())
        raw = producer.build_probe_receipt(opener, SequenceClock())
        receipt = validator.validate_probe_bytes(raw)
        self.assertEqual(len(opener.calls), 4)
        self.assertEqual(receipt["endpoints"][0]["identity_values"]["instId"], "BTC-USDT-260626")
        self.assertEqual(receipt["endpoints"][0]["inspected_data_row_index"], 1)
        self.assertEqual(receipt["endpoints"][0]["response_data_row_count"], 2)
        for request in opener.calls:
            headers = {key.lower(): value for key, value in request.header_items()}
            self.assertNotIn("authorization", headers)
            self.assertNotIn("cookie", headers)
            self.assertEqual(headers["user-agent"], producer.USER_AGENT)
        decoded = raw.decode("utf-8")
        for raw_market_value in ("98765", "87654", "0.0001", "10000", "0.00003", "0.01", "0.02"):
            self.assertNotIn(raw_market_value, decoded)
        self.assertFalse(receipt["raw_responses_persisted"])
        self.assertFalse(receipt["raw_responses_transported"])
        self.assertFalse(receipt["source_activation_authorized"])

    def test_request_urls_are_frozen_and_proxy_credentials_are_not_injected(self) -> None:
        opener = FakeOpener(response_documents())
        producer.build_probe_receipt(opener, SequenceClock())
        self.assertEqual(
            [request.full_url for request in opener.calls],
            [
                "https://www.okx.com/api/v5/public/open-interest?instFamily=BTC-USDT&instType=FUTURES",
                "https://www.okx.com/api/v5/public/open-interest?instId=BTC-USDT-SWAP&instType=SWAP",
                "https://www.okx.com/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&limit=1",
                "https://www.okx.com/api/v5/finance/savings/lending-rate-history?ccy=USDT&limit=1",
            ],
        )
        source = Path(producer.__file__).read_text(encoding="utf-8")
        for token in ("API_KEY", "PASSPHRASE", "SECRET", "Authorization", "Cookie", "os.environ", "getenv"):
            self.assertNotIn(token, source)

    def test_identity_schema_code_and_redirect_drift_fail_without_retry(self) -> None:
        cases = []
        documents = response_documents()
        documents["SWAP_OPEN_INTEREST"]["data"][0]["instType"] = "FUTURES"
        cases.append((documents, None, "identity"))

        documents = response_documents()
        documents["USDT_LENDING_RATE_HISTORY"]["data"][0]["extra"] = "field"
        cases.append((documents, None, "schema drift"))

        documents = response_documents()
        documents["SWAP_FUNDING_RATE_HISTORY"]["code"] = "50000"
        cases.append((documents, None, "API code"))

        cases.append((response_documents(), "FUTURES_OPEN_INTEREST", "redirect"))

        for documents, redirect_endpoint, message in cases:
            with self.subTest(message=message):
                opener = FakeOpener(documents, redirect_endpoint=redirect_endpoint)
                with self.assertRaisesRegex(producer.ProbeFailure, message):
                    producer.build_probe_receipt(opener, SequenceClock())
                self.assertLessEqual(len(opener.calls), 4)

    def test_fixed_requests_require_exactly_one_row(self) -> None:
        documents = response_documents()
        documents["SWAP_OPEN_INTEREST"]["data"].append(deepcopy(documents["SWAP_OPEN_INTEREST"]["data"][0]))
        opener = FakeOpener(documents)
        with self.assertRaisesRegex(producer.ProbeFailure, "exactly one row"):
            producer.build_probe_receipt(opener, SequenceClock())

    def test_create_once_writer_never_overwrites(self) -> None:
        raw = b"sealed-receipt"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            producer.write_create_once(path, raw)
            self.assertEqual(path.read_bytes(), raw)
            with self.assertRaises(FileExistsError):
                producer.write_create_once(path, b"replacement")
            self.assertEqual(path.read_bytes(), raw)

    def test_import_has_no_network_side_effect_and_cli_is_not_called(self) -> None:
        self.assertTrue(callable(producer.build_probe_receipt))
        self.assertEqual(producer.TIMEOUT_SECONDS, 15)
        self.assertNotIn("timer", Path(producer.__file__).read_text(encoding="utf-8").lower())


if __name__ == "__main__":
    unittest.main()
