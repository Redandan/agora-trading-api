from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import unittest

from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    BUNDLE_DOCUMENT_CANONICALIZATION,
    DAY_CANONICALIZATION,
    DROP_ENVELOPE_SCHEMA_SHA256,
    ENVELOPE_CANONICALIZATION,
    ENVELOPE_DOCUMENT_CANONICALIZATION,
    INTAKE_STATE_SCHEMA_SHA256,
    SOURCE_CONTRACT_PATH,
    SOURCE_CONTRACT_SHA256,
    ContractViolation,
    accept_intake_day,
    block_intake_state,
    canonical_json_bytes,
    canonical_sha256,
    initial_intake_state,
    load_json_strict,
    transition_drop,
    transition_producer,
    validate_day_bundle,
    validate_drop_envelope,
    validate_frozen_contract_files,
    validate_intake_state,
    validate_source_contract,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
DROP_SCHEMA_PATH = (
    REPO_ROOT / "research_pipeline/okx-microstructure-drop-envelope.v1.schema.json"
)
INTAKE_SCHEMA_PATH = (
    REPO_ROOT / "research_pipeline/okx-microstructure-intake-state.v1.schema.json"
)
DIAGNOSTIC_ID = "microstructure-v2-fixture"


def _timestamp(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _minute(value: datetime) -> dict[str, object]:
    return {
        "minute": _timestamp(value),
        "trade_record_count": 1,
        "match_count": 1,
        "buy_quote_notional": "60",
        "sell_quote_notional": "40",
        "total_quote_notional": "100",
        "net_taker_quote_notional": "20",
        "trade_open_price": "100",
        "trade_high_price": "101",
        "trade_low_price": "99",
        "trade_close_price": "100",
        "trade_vwap_price": "100",
        "first_trade_at": _timestamp(value + timedelta(seconds=5)),
        "last_trade_at": _timestamp(value + timedelta(seconds=55)),
        "book_sample_count": 1,
        "average_top5_bid_quote_depth": "1000",
        "average_top5_ask_quote_depth": "900",
        "average_book_imbalance": "0.05",
        "average_spread_bps": "1",
        "bid_replenishment_quote_proxy": "10",
        "mid_price_start": "100",
        "mid_price_high": "101",
        "mid_price_low": "99",
        "mid_price_end": "100",
        "first_book_at": _timestamp(value + timedelta(seconds=1)),
        "last_book_at": _timestamp(value + timedelta(seconds=59)),
    }


def _day_bundle(bundle_day: date) -> dict[str, object]:
    start = datetime.combine(bundle_day, datetime.min.time(), tzinfo=timezone.utc)
    end = start + timedelta(days=1)
    bundle: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_FORWARD_DAY_V2",
        "bundle_type": "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY",
        "authorization": AUTHORIZATION,
        "source": {
            "venue": "OKX",
            "instrument": "BTC-USDT",
            "channels": ["trades", "books5"],
            "mode": "FORWARD_ONLY",
            "historical_backfill": False,
            "raw_messages_persisted": False,
            "aggregation_timezone": "UTC",
        },
        "day": bundle_day.isoformat(),
        "capture": {
            "started_at": _timestamp(start),
            "ended_at": _timestamp(end),
            "acknowledged_channels": ["books5", "trades"],
        },
        "integrity": {
            "status": "CLEAN",
            "anomaly_count": 0,
            "raw_message_count": 2880,
            "arrival_chain_sha256": "a" * 64,
        },
        "minutes": [_minute(start + timedelta(minutes=index)) for index in range(1440)],
    }
    bundle["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": canonical_sha256(bundle, exclude_key="seal"),
        "canonicalization": DAY_CANONICALIZATION,
        "sealed_at": _timestamp(end),
    }
    return bundle


def _reseal_day(bundle: dict[str, object]) -> None:
    seal = bundle["seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = canonical_sha256(bundle, exclude_key="seal")


def _envelope(
    bundle: dict[str, object],
    *,
    predecessor_day: date | None,
    predecessor_bundle_sha256: str | None,
) -> dict[str, object]:
    validated = validate_day_bundle(bundle, raw_bytes=canonical_json_bytes(bundle))
    bundle_day = validated["day"]
    assert isinstance(bundle_day, date)
    published_at = datetime.combine(
        bundle_day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc
    ) + timedelta(seconds=1)
    envelope: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V1",
        "envelope_type": "IMMUTABLE_ONE_WAY_MICROSTRUCTURE_DAY",
        "authorization": AUTHORIZATION,
        "diagnostic_id": DIAGNOSTIC_ID,
        "source_contract_sha256": SOURCE_CONTRACT_SHA256,
        "producer_release_id": "deterministic-fixture-release",
        "producer_manifest_sha256": "b" * 64,
        "producer_identity": "agora-evidence-source",
        "day": bundle_day.isoformat(),
        "predecessor_day": None if predecessor_day is None else predecessor_day.isoformat(),
        "predecessor_bundle_sha256": predecessor_bundle_sha256,
        "bundle_name": f"okx-btc-usdt-microstructure-{bundle_day.isoformat()}.json",
        "bundle_size_bytes": len(canonical_json_bytes(bundle)),
        "bundle_sha256": validated["bundle_sha256"],
        "published_at": _timestamp(published_at),
        "idempotency_key": (
            f"{DIAGNOSTIC_ID}:{bundle_day.isoformat()}:{validated['bundle_sha256']}"
        ),
        "delivery_semantics": {
            "transport": "MICROSTRUCTURE_ONLY_ONE_WAY_DROP",
            "bundle_document_canonicalization": BUNDLE_DOCUMENT_CANONICALIZATION,
            "envelope_document_canonicalization": ENVELOPE_DOCUMENT_CANONICALIZATION,
            "atomic_rename": True,
            "overwrite": False,
            "source_read_after_publish": False,
            "symlinks": False,
            "canonical_state_access": False,
            "candle_chain_reuse": False,
        },
    }
    envelope["envelope_seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": canonical_sha256(envelope, exclude_key="envelope_seal"),
        "canonicalization": ENVELOPE_CANONICALIZATION,
        "sealed_at": _timestamp(published_at + timedelta(seconds=1)),
    }
    return envelope


def _reseal_envelope(envelope: dict[str, object]) -> None:
    seal = envelope["envelope_seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = canonical_sha256(
        envelope, exclude_key="envelope_seal"
    )


def _accept(
    state: dict[str, object],
    envelope: dict[str, object],
    bundle: dict[str, object],
    **overrides: object,
) -> dict[str, object]:
    day = date.fromisoformat(str(envelope["day"]))
    arguments: dict[str, object] = {
        "raw_envelope_bytes": canonical_json_bytes(envelope),
        "raw_bundle_bytes": canonical_json_bytes(bundle),
        "accepted_at": _timestamp(
            datetime.combine(
                day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc
            )
            + timedelta(seconds=3)
        ),
        "observed_producer_identity": "agora-evidence-source",
        "delivered_via_atomic_rename": True,
        "source_path_is_symlink": False,
        "overwrite_attempted": False,
    }
    arguments.update(overrides)
    return accept_intake_day(state, envelope, bundle, **arguments)


class MicrostructureContinuousContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.start_day = date(2026, 1, 2)
        self.state = initial_intake_state(
            DIAGNOSTIC_ID, self.start_day, as_of_day=date(2026, 1, 1)
        )

    def test_frozen_files_hash_exactly_and_schemas_are_closed(self) -> None:
        hashes = validate_frozen_contract_files()
        self.assertEqual(SOURCE_CONTRACT_SHA256, hashes[SOURCE_CONTRACT_PATH.name])
        self.assertEqual(DROP_ENVELOPE_SCHEMA_SHA256, hashes[DROP_SCHEMA_PATH.name])
        self.assertEqual(INTAKE_STATE_SCHEMA_SHA256, hashes[INTAKE_SCHEMA_PATH.name])

        for path in (DROP_SCHEMA_PATH, INTAKE_SCHEMA_PATH):
            schema = json.loads(path.read_text(encoding="utf-8"))
            self.assertFalse(schema["additionalProperties"])

    def test_source_contract_exact_keys_and_single_clock_are_frozen(self) -> None:
        contract = load_json_strict(SOURCE_CONTRACT_PATH)
        self.assertEqual(contract, validate_source_contract(contract))
        self.assertEqual("wss://ws.okx.com:8443/ws/v5/public", contract["source"]["endpoint"])
        self.assertEqual("CODEX_CLOUD_OPS_ONLY", contract["lifecycle"]["timer_authority"])
        self.assertEqual(1, contract["lifecycle"]["cloud_schedule_count"])
        self.assertFalse(contract["separation"]["candle_chain_reuse"])
        self.assertEqual(
            BUNDLE_DOCUMENT_CANONICALIZATION,
            contract["collection"]["bundle_document_canonicalization"],
        )
        self.assertEqual(
            ENVELOPE_DOCUMENT_CANONICALIZATION,
            contract["collection"]["envelope_document_canonicalization"],
        )

        changed = deepcopy(contract)
        changed["bindings"]["day_schema_sha256"] = "0" * 64
        with self.assertRaises(ContractViolation) as caught:
            validate_source_contract(changed)
        self.assertEqual("CONTRACT_HASH_MISMATCH", caught.exception.code)

        changed = deepcopy(contract)
        changed["lifecycle"]["producer_can_time_research_actions"] = True
        with self.assertRaises(ContractViolation) as caught:
            validate_source_contract(changed)
        self.assertEqual("LIFECYCLE_CLOCK_FORBIDDEN", caught.exception.code)

    def test_only_explicit_producer_and_drop_transitions_are_allowed(self) -> None:
        producer = "UNBOUND"
        for event in (
            "BIND_FUTURE_WINDOW",
            "START_DATA_PLANE",
            "BOTH_CHANNELS_ACKNOWLEDGED",
            "FIRST_VALID_NEXT_DAY_MESSAGE",
            "PUBLISH_ATOMIC_DROP",
            "FOURTEENTH_DAY_PUBLISHED",
        ):
            producer = transition_producer(producer, event)
        self.assertEqual("STOPPED_COMPLETE", producer)

        drop = "STAGING"
        for event in (
            "HASH_STAGED_BYTES",
            "ATOMIC_RENAME",
            "OBSERVE_EXACT_BYTES",
            "INGEST_EXACT_BYTES",
        ):
            drop = transition_drop(drop, event)
        self.assertEqual("INGESTED", drop)
        with self.assertRaises(ContractViolation):
            transition_producer("CAPTURING_UTC_DAY", "TIME_RESEARCH_ACTION")

    def test_valid_day_accepts_and_identical_duplicate_is_idempotent(self) -> None:
        bundle = _day_bundle(self.start_day)
        envelope = _envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        first = _accept(self.state, envelope, bundle)
        duplicate = _accept(first, envelope, bundle)

        self.assertEqual(first, duplicate)
        self.assertEqual("WAITING_FOR_DAY", first["status"])
        self.assertEqual(1, len(first["accepted_days"]))
        self.assertEqual("2026-01-03", first["next_expected_day"])

    def test_fourteen_contiguous_days_end_only_at_discovery_readiness(self) -> None:
        state = self.state
        for index in range(14):
            bundle_day = self.start_day + timedelta(days=index)
            bundle = _day_bundle(bundle_day)
            previous = state["accepted_days"][-1] if state["accepted_days"] else None
            envelope = _envelope(
                bundle,
                predecessor_day=(
                    None if previous is None else date.fromisoformat(previous["day"])
                ),
                predecessor_bundle_sha256=(
                    None if previous is None else previous["bundle_sha256"]
                ),
            )
            state = _accept(state, envelope, bundle)
            if index < 13:
                self.assertEqual("WAITING_FOR_DAY", state["status"])

        self.assertEqual("DIAGNOSTIC_READY", state["status"])
        self.assertIsNone(state["next_expected_day"])
        self.assertEqual(
            "FROZEN_V2_DISCOVERY_ANALYSIS_ONLY",
            state["readiness"]["disposition"],
        )
        self.assertEqual("MISSING_PROOF", state["readiness"]["performance_value"])
        for key in (
            "candidate_authorized",
            "oos_authorized",
            "pnl_claim_authorized",
            "promotion_authorized",
        ):
            self.assertFalse(state["readiness"][key])
        validate_intake_state(state)

    def test_transport_identity_and_delivery_violations_fail_closed(self) -> None:
        bundle = _day_bundle(self.start_day)
        envelope = _envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        cases = (
            ({"observed_producer_identity": "wrong"}, "WRONG_IDENTITY"),
            ({"source_path_is_symlink": True}, "SYMLINK_REJECT"),
            ({"overwrite_attempted": True}, "OVERWRITE_REJECT"),
            ({"delivered_via_atomic_rename": False}, "NON_ATOMIC_DELIVERY"),
            ({"historical_backfill_requested": True}, "BACKFILL_FORBIDDEN"),
            ({"candle_chain_reuse_requested": True}, "CANDLE_CHAIN_REUSE_FORBIDDEN"),
            ({"research_lifecycle_action_requested": True}, "LIFECYCLE_CLOCK_FORBIDDEN"),
        )
        for overrides, expected_code in cases:
            with self.subTest(expected_code=expected_code):
                with self.assertRaises(ContractViolation) as caught:
                    _accept(self.state, envelope, bundle, **overrides)
                self.assertEqual(expected_code, caught.exception.code)

        changed = deepcopy(envelope)
        changed["producer_identity"] = "agora-research"
        _reseal_envelope(changed)
        with self.assertRaises(ContractViolation) as caught:
            _accept(self.state, changed, bundle)
        self.assertEqual("WRONG_IDENTITY", caught.exception.code)

    def test_incomplete_unclean_stream_gap_and_bad_hash_days_fail_closed(self) -> None:
        base = _day_bundle(self.start_day)

        incomplete = deepcopy(base)
        incomplete["minutes"].pop()
        _reseal_day(incomplete)
        with self.assertRaises(ContractViolation) as caught:
            validate_day_bundle(incomplete, raw_bytes=canonical_json_bytes(incomplete))
        self.assertEqual("INCOMPLETE_DAY", caught.exception.code)

        unclean = deepcopy(base)
        unclean["integrity"]["status"] = "ANOMALIES_PRESENT"
        unclean["integrity"]["anomaly_count"] = 1
        _reseal_day(unclean)
        with self.assertRaises(ContractViolation) as caught:
            validate_day_bundle(unclean, raw_bytes=canonical_json_bytes(unclean))
        self.assertEqual("INTEGRITY_NOT_CLEAN", caught.exception.code)

        gap = deepcopy(base)
        gap["minutes"][10]["book_sample_count"] = 0
        _reseal_day(gap)
        with self.assertRaises(ContractViolation) as caught:
            validate_day_bundle(gap, raw_bytes=canonical_json_bytes(gap))
        self.assertEqual("STREAM_GAP", caught.exception.code)

        bad_hash = deepcopy(base)
        bad_hash["integrity"]["arrival_chain_sha256"] = "c" * 64
        with self.assertRaises(ContractViolation) as caught:
            validate_day_bundle(bad_hash, raw_bytes=canonical_json_bytes(bad_hash))
        self.assertEqual("HASH_MISMATCH", caught.exception.code)

    def test_out_of_order_and_predecessor_mismatch_fail_closed(self) -> None:
        day_two = self.start_day + timedelta(days=1)
        skipped_bundle = _day_bundle(day_two)
        skipped_envelope = _envelope(
            skipped_bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        with self.assertRaises(ContractViolation) as caught:
            _accept(self.state, skipped_envelope, skipped_bundle)
        self.assertEqual("NONCONTIGUOUS_DAY", caught.exception.code)

        first_bundle = _day_bundle(self.start_day)
        first_envelope = _envelope(
            first_bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        state = _accept(self.state, first_envelope, first_bundle)
        second_bundle = _day_bundle(day_two)
        second_envelope = _envelope(
            second_bundle,
            predecessor_day=self.start_day,
            predecessor_bundle_sha256="f" * 64,
        )
        with self.assertRaises(ContractViolation) as caught:
            _accept(state, second_envelope, second_bundle)
        self.assertEqual("PREDECESSOR_MISMATCH", caught.exception.code)

    def test_conflicting_duplicate_fails_closed(self) -> None:
        bundle = _day_bundle(self.start_day)
        envelope = _envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        state = _accept(self.state, envelope, bundle)

        changed_bundle = deepcopy(bundle)
        minute = changed_bundle["minutes"][0]
        minute["buy_quote_notional"] = "61"
        minute["total_quote_notional"] = "101"
        minute["net_taker_quote_notional"] = "21"
        _reseal_day(changed_bundle)
        changed_envelope = _envelope(
            changed_bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        with self.assertRaises(ContractViolation) as caught:
            _accept(state, changed_envelope, changed_bundle)
        self.assertEqual("CONFLICTING_DUPLICATE", caught.exception.code)

    def test_backfill_start_and_explicit_block_are_terminal(self) -> None:
        with self.assertRaises(ContractViolation) as caught:
            initial_intake_state(
                DIAGNOSTIC_ID, date(2026, 1, 1), as_of_day=date(2026, 1, 1)
            )
        self.assertEqual("BACKFILL_FORBIDDEN", caught.exception.code)

        blocked = block_intake_state(
            self.state,
            code="STREAM_GAP",
            day=self.start_day,
            detail="books5 missing in one UTC minute",
        )
        self.assertEqual("INTEGRITY_BLOCKED", blocked["status"])
        self.assertIsNone(blocked["next_expected_day"])
        self.assertEqual("MISSING_PROOF", blocked["readiness"]["performance_value"])
        validate_intake_state(blocked)

    def test_noncanonical_bundle_and_envelope_bytes_fail_closed(self) -> None:
        bundle = _day_bundle(self.start_day)
        envelope = _envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        noncanonical_bundle_bytes = (
            json.dumps(bundle, ensure_ascii=False, indent=2).encode("utf-8"),
            json.dumps(
                bundle,
                ensure_ascii=False,
                sort_keys=False,
                separators=(",", ":"),
            ).encode("utf-8"),
        )
        noncanonical_envelope_bytes = (
            json.dumps(envelope, ensure_ascii=False, indent=2).encode("utf-8"),
            json.dumps(
                envelope,
                ensure_ascii=False,
                sort_keys=False,
                separators=(",", ":"),
            ).encode("utf-8"),
        )

        for raw_bytes in noncanonical_bundle_bytes:
            with self.subTest(document="bundle", length=len(raw_bytes)):
                self.assertEqual(bundle, json.loads(raw_bytes))
                with self.assertRaises(ContractViolation) as caught:
                    _accept(
                        self.state,
                        envelope,
                        bundle,
                        raw_bundle_bytes=raw_bytes,
                    )
                self.assertEqual("HASH_MISMATCH", caught.exception.code)

        for raw_bytes in noncanonical_envelope_bytes:
            with self.subTest(document="envelope", length=len(raw_bytes)):
                self.assertEqual(envelope, json.loads(raw_bytes))
                with self.assertRaises(ContractViolation) as caught:
                    _accept(
                        self.state,
                        envelope,
                        bundle,
                        raw_envelope_bytes=raw_bytes,
                    )
                self.assertEqual("HASH_MISMATCH", caught.exception.code)

    def test_envelope_hashing_is_deterministic_and_tampering_is_rejected(self) -> None:
        bundle = _day_bundle(self.start_day)
        envelope = _envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        kwargs = {
            "raw_envelope_bytes": canonical_json_bytes(envelope),
            "raw_bundle_bytes": canonical_json_bytes(bundle),
            "expected_diagnostic_id": DIAGNOSTIC_ID,
            "expected_day": self.start_day,
            "expected_predecessor_day": None,
            "expected_predecessor_bundle_sha256": None,
            "observed_producer_identity": "agora-evidence-source",
            "delivered_via_atomic_rename": True,
            "source_path_is_symlink": False,
            "overwrite_attempted": False,
        }
        first = validate_drop_envelope(envelope, bundle, **kwargs)
        second = validate_drop_envelope(deepcopy(envelope), deepcopy(bundle), **kwargs)
        self.assertEqual(first, second)
        self.assertEqual(canonical_sha256(envelope), first["envelope_sha256"])

        tampered = deepcopy(envelope)
        tampered["producer_manifest_sha256"] = "d" * 64
        with self.assertRaises(ContractViolation) as caught:
            validate_drop_envelope(tampered, bundle, **kwargs)
        self.assertEqual("HASH_MISMATCH", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
