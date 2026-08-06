from __future__ import annotations

from copy import deepcopy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from research_pipeline.cli import main, parser
from research_pipeline.microstructure import (
    CANONICALIZATION,
    METRIC_SEMANTICS,
    payload_sha256,
    validate_okx_microstructure_bundle,
    validate_okx_microstructure_bundle_file,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "okx-microstructure-forward-bundle.schema.json"
)


def valid_bundle() -> dict[str, object]:
    bundle: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_FORWARD_BUNDLE_V1",
        "status": "CAPTURE_COMPLETE_RESEARCH_ONLY",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "canonical_evidence_eligible": False,
        "source": {
            "venue": "OKX",
            "endpoint": "wss://ws.okx.com:8443/ws/v5/public",
            "instrument": "BTC-USDT",
            "channels": ["trades", "books5"],
            "mode": "FORWARD_ONLY_BOUNDED_CAPTURE",
            "historical_backfill": False,
            "raw_messages_persisted": False,
            "minute_aggregation_timezone": "UTC",
        },
        "capture": {
            "requested_duration_seconds": 5,
            "started_at": "2024-01-01T00:00:00Z",
            "ended_at": "2024-01-01T00:00:05Z",
            "acknowledged_channels": ["books5", "trades"],
            "trade_payloads": 1,
            "books5_payloads": 1,
            "listener_error": None,
        },
        "integrity": {
            "status": "CLEAN",
            "raw_message_count": 4,
            "arrival_chain_algorithm": "SHA-256(previous_digest || raw_utf8_message)",
            "arrival_chain_sha256": "1" * 64,
            "malformed_record_count": 0,
            "exchange_error_count": 0,
            "crossed_book_count": 0,
            "trade_timestamp_regression_count": 0,
            "book_timestamp_regression_count": 0,
            "trade_sequence_regression_count": 0,
            "book_sequence_regression_count": 0,
            "trade_id_non_increasing_count": 0,
            "trade_source_record_counts": {"0": 1},
        },
        "eligibility": {
            "full_utc_day_1440_contiguous_minutes": False,
            "integrity_clean": True,
            "both_channels_acknowledged": True,
            "both_streams_observed": True,
            "note": "A short smoke capture is diagnostic only and cannot enter canonical evidence.",
        },
        "metric_semantics": deepcopy(METRIC_SEMANTICS),
        "minutes": [
            {
                "minute": "2024-01-01T00:00:00Z",
                "trade_record_count": 1,
                "match_count": 1,
                "buy_base_quantity": "1",
                "sell_base_quantity": "0",
                "buy_quote_notional": "100",
                "sell_quote_notional": "0",
                "net_taker_quote_notional": "100",
                "book_sample_count": 1,
                "average_top5_bid_quote_depth": "198",
                "average_top5_ask_quote_depth": "101",
                "average_book_imbalance": "0.324414715719",
                "average_spread_bps": "200",
                "bid_replenishment_quote_proxy": "0",
                "mid_price_start": "100",
                "mid_price_end": "100",
            }
        ],
        "seal": {
            "algorithm": "SHA-256",
            "payload_sha256": "0" * 64,
            "canonicalization": CANONICALIZATION,
            "sealed_at": "2024-01-01T00:00:06Z",
        },
    }
    reseal(bundle)
    return bundle


def reseal(bundle: dict[str, object]) -> None:
    seal = bundle["seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = payload_sha256(bundle)


class OkxMicrostructureContractTest(unittest.TestCase):
    def test_valid_smoke_bundle_has_deterministic_seal_and_stays_noncanonical(self) -> None:
        bundle = valid_bundle()
        first = validate_okx_microstructure_bundle(bundle)
        second = validate_okx_microstructure_bundle(deepcopy(bundle))

        self.assertEqual(first, second)
        self.assertEqual(first["status"], "VALID_SMOKE_TOOLING_ONLY")
        self.assertFalse(first["canonical_evidence_eligible"])
        self.assertFalse(first["full_utc_day"])
        self.assertEqual(first["payload_sha256"], payload_sha256(bundle))

    def test_portable_schema_closes_nested_unknown_fields(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

        self.assertFalse(schema["additionalProperties"])
        for name in ("source", "capture", "integrity", "eligibility", "seal"):
            self.assertFalse(schema["properties"][name]["additionalProperties"])
        self.assertFalse(
            schema["properties"]["minutes"]["items"]["additionalProperties"]
        )
        self.assertEqual(
            schema["properties"]["canonical_evidence_eligible"]["const"],
            False,
        )

    def test_unknown_field_fails_closed(self) -> None:
        bundle = valid_bundle()
        bundle["capture"]["endpoint_override"] = "forbidden"
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "keys mismatch"):
            validate_okx_microstructure_bundle(bundle)

    def test_malformed_type_fails_closed(self) -> None:
        bundle = valid_bundle()
        bundle["capture"]["trade_payloads"] = True
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "must be an integer"):
            validate_okx_microstructure_bundle(bundle)

    def test_noncontiguous_minutes_fail_closed(self) -> None:
        bundle = valid_bundle()
        second = deepcopy(bundle["minutes"][0])
        second["minute"] = "2024-01-01T00:02:00Z"
        bundle["minutes"].append(second)
        bundle["capture"]["ended_at"] = "2024-01-01T00:02:05Z"
        bundle["integrity"]["trade_source_record_counts"] = {"0": 2}
        bundle["seal"]["sealed_at"] = "2024-01-01T00:02:06Z"
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "strictly ordered and contiguous"):
            validate_okx_microstructure_bundle(bundle)

    def test_stream_gap_fails_closed(self) -> None:
        bundle = valid_bundle()
        minute = bundle["minutes"][0]
        minute["book_sample_count"] = 0
        minute["average_top5_bid_quote_depth"] = None
        minute["average_top5_ask_quote_depth"] = None
        minute["average_book_imbalance"] = None
        minute["average_spread_bps"] = None
        minute["mid_price_start"] = None
        minute["mid_price_end"] = None
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "book_sample_count"):
            validate_okx_microstructure_bundle(bundle)

    def test_integrity_anomaly_fails_closed(self) -> None:
        bundle = valid_bundle()
        bundle["integrity"]["status"] = "ANOMALIES_PRESENT"
        bundle["integrity"]["malformed_record_count"] = 1
        bundle["eligibility"]["integrity_clean"] = False
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "integrity anomalies fail closed"):
            validate_okx_microstructure_bundle(bundle)

    def test_inconsistent_eligibility_fails_closed(self) -> None:
        bundle = valid_bundle()
        bundle["eligibility"]["both_streams_observed"] = False
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "stream observation is inconsistent"):
            validate_okx_microstructure_bundle(bundle)

    def test_altered_payload_fails_seal_validation(self) -> None:
        bundle = valid_bundle()
        bundle["capture"]["requested_duration_seconds"] = 6

        with self.assertRaisesRegex(ValueError, "does not match the canonical payload"):
            validate_okx_microstructure_bundle(bundle)

    def test_canonical_claim_fails_closed_even_with_a_recomputed_seal(self) -> None:
        bundle = valid_bundle()
        bundle["canonical_evidence_eligible"] = True
        reseal(bundle)

        with self.assertRaisesRegex(ValueError, "must remain false"):
            validate_okx_microstructure_bundle(bundle)

    def test_duplicate_json_keys_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"schema_version":"a","schema_version":"b"}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
                validate_okx_microstructure_bundle_file(path)

    def test_cli_exposes_offline_validator_without_state_initialization(self) -> None:
        arguments = parser().parse_args(
            ["validate-okx-microstructure-bundle", "bundle.json"]
        )

        self.assertEqual(arguments.command, "validate-okx-microstructure-bundle")
        self.assertEqual(arguments.bundle, Path("bundle.json"))

    def test_cli_validates_a_deterministic_fixture_without_research_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bundle.json"
            path.write_text(
                json.dumps(valid_bundle(), ensure_ascii=False),
                encoding="utf-8",
            )
            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = main(
                    ["validate-okx-microstructure-bundle", str(path)]
                )

        self.assertEqual(exit_code, 0)
        self.assertEqual(
            json.loads(output.getvalue())["status"],
            "VALID_SMOKE_TOOLING_ONLY",
        )


if __name__ == "__main__":
    unittest.main()
