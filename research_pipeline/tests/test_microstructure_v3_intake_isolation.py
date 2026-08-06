from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_intake import (
    ObservedDelivery,
    apply_observed_v3_delivery,
    canonical_v3_state_bytes,
    initial_state_bytes,
    initial_v3_state_bytes,
    load_canonical_state_bytes,
    load_canonical_v3_state_bytes,
)
from research_pipeline.microstructure_monitor import microstructure_diagnostic_status
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    BUNDLE_DOCUMENT_CANONICALIZATION,
    DAY_CANONICALIZATION,
    ENVELOPE_CANONICALIZATION,
    ENVELOPE_DOCUMENT_CANONICALIZATION,
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    V3_DROP_ENVELOPE_SCHEMA_SHA256,
    V3_INTAKE_STATE_SCHEMA_SHA256,
    V3_SOURCE_CONTRACT_SHA256,
    ContractViolation,
    canonical_json_bytes,
    canonical_sha256,
    validate_day_bundle,
    validate_drop_envelope,
    validate_v3_day_bundle,
    validate_v3_drop_envelope,
    validate_v3_frozen_contract_files,
)
from research_pipeline.tests.test_microstructure_source_contract import (
    _day_bundle as _v2_day_bundle,
    _envelope as _v2_envelope,
    _timestamp,
)


DIAGNOSTIC_ID = "microstructure-v3-isolation"
START_DAY = date(2026, 9, 1)
AS_OF_DAY = date(2026, 8, 31)


def _v3_day_bundle(bundle_day: date) -> dict[str, object]:
    bundle = _v2_day_bundle(bundle_day)
    bundle["schema_version"] = "OKX_MICROSTRUCTURE_FORWARD_DAY_V3"
    source = bundle["source"]
    assert isinstance(source, dict)
    source.update(
        {
            "midline_formula": "BEST_BID_1_PLUS_BEST_ASK_1_DIVIDED_BY_2",
            "midline_reference": "LATEST_BOOKS5_AT_OR_BEFORE_TRADE",
            "unreferenced_trade_disposition": "INTEGRITY_ANOMALY",
        }
    )
    integrity = bundle["integrity"]
    assert isinstance(integrity, dict)
    integrity.update(
        {
            "midline_unreferenced_trade_count": 0,
            "crossed_book_count": 0,
        }
    )
    minutes = bundle["minutes"]
    assert isinstance(minutes, list)
    for minute in minutes:
        assert isinstance(minute, dict)
        minute.update(
            {
                "midline_reference_count": minute["trade_record_count"],
                "above_mid_buy_quote_notional": "50",
                "below_mid_sell_quote_notional": "30",
                "midline_other_quote_notional": "20",
            }
        )
    seal = bundle["seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = canonical_sha256(bundle, exclude_key="seal")
    return bundle


def _reseal_v3_day(bundle: dict[str, object]) -> None:
    seal = bundle["seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = canonical_sha256(bundle, exclude_key="seal")


def _v3_envelope(
    bundle: dict[str, object],
    *,
    predecessor_day: date | None,
    predecessor_bundle_sha256: str | None,
) -> dict[str, object]:
    bundle_bytes = canonical_json_bytes(bundle)
    validated = validate_v3_day_bundle(bundle, raw_bytes=bundle_bytes)
    bundle_day = validated["day"]
    assert isinstance(bundle_day, date)
    published_at = datetime.combine(
        bundle_day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc
    ) + timedelta(seconds=1)
    envelope: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_DROP_ENVELOPE_V3",
        "envelope_type": "IMMUTABLE_ONE_WAY_MICROSTRUCTURE_DAY",
        "authorization": AUTHORIZATION,
        "diagnostic_id": DIAGNOSTIC_ID,
        "source_contract_sha256": V3_SOURCE_CONTRACT_SHA256,
        "producer_release_id": "deterministic-v3-fixture-release",
        "producer_manifest_sha256": "b" * 64,
        "producer_identity": "agora-evidence-source",
        "day": bundle_day.isoformat(),
        "predecessor_day": (
            None if predecessor_day is None else predecessor_day.isoformat()
        ),
        "predecessor_bundle_sha256": predecessor_bundle_sha256,
        "bundle_name": f"okx-btc-usdt-microstructure-{bundle_day.isoformat()}.json",
        "bundle_size_bytes": len(bundle_bytes),
        "bundle_sha256": validated["bundle_sha256"],
        "published_at": _timestamp(published_at),
        "idempotency_key": (
            f"{DIAGNOSTIC_ID}:{bundle_day.isoformat()}:"
            f"{validated['bundle_sha256']}"
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
        "payload_sha256": canonical_sha256(
            envelope, exclude_key="envelope_seal"
        ),
        "canonicalization": ENVELOPE_CANONICALIZATION,
        "sealed_at": _timestamp(published_at + timedelta(seconds=1)),
    }
    return envelope


def _observed() -> ObservedDelivery:
    return ObservedDelivery(
        intake_identity="agora-research",
        network_access="DENY",
        producer_identity="agora-evidence-source",
        delivered_via_atomic_rename=True,
        source_path_is_symlink=False,
        overwrite_attempted=False,
        historical_backfill_requested=False,
        candle_chain_reuse_requested=False,
        research_lifecycle_action_requested=False,
    )


def _accepted_at(bundle_day: date) -> str:
    return _timestamp(
        datetime.combine(
            bundle_day + timedelta(days=1), datetime.min.time(), timezone.utc
        )
        + timedelta(seconds=3)
    )


def _apply(
    state_bytes: bytes,
    bundle: dict[str, object],
    envelope: dict[str, object],
):
    return apply_observed_v3_delivery(
        state_bytes,
        canonical_json_bytes(envelope),
        canonical_json_bytes(bundle),
        observed=_observed(),
        accepted_at=_accepted_at(date.fromisoformat(str(bundle["day"]))),
    )


class MicrostructureV3IntakeIsolationTest(unittest.TestCase):
    def test_frozen_v3_graph_and_initial_state_are_exact(self) -> None:
        hashes = validate_v3_frozen_contract_files()
        self.assertEqual(5, len(hashes))
        self.assertIn(V3_SOURCE_CONTRACT_SHA256, hashes.values())
        self.assertIn(V3_DROP_ENVELOPE_SCHEMA_SHA256, hashes.values())
        self.assertIn(V3_INTAKE_STATE_SCHEMA_SHA256, hashes.values())
        self.assertIn(V3_DAY_SCHEMA_SHA256, hashes.values())
        self.assertIn(V3_DIAGNOSTIC_CONTRACT_SHA256, hashes.values())

        state_bytes = initial_v3_state_bytes(
            DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
        )
        state = load_canonical_v3_state_bytes(state_bytes)
        self.assertEqual(
            "SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE", state["state_type"]
        )
        self.assertEqual(V3_SOURCE_CONTRACT_SHA256, state["source_contract_sha256"])
        self.assertEqual(state_bytes, canonical_v3_state_bytes(state))

    def test_v3_acceptance_exact_duplicate_and_conflict(self) -> None:
        bundle = _v3_day_bundle(START_DAY)
        envelope = _v3_envelope(
            bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        initial = initial_v3_state_bytes(
            DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
        )
        first = _apply(initial, bundle, envelope)
        self.assertEqual("WAITING_FOR_DAY", first.disposition)

        duplicate = _apply(first.state_bytes, bundle, envelope)
        self.assertEqual("IDEMPOTENT_DUPLICATE", duplicate.disposition)
        self.assertEqual(first.state_bytes, duplicate.state_bytes)

        changed = deepcopy(bundle)
        minute = changed["minutes"][0]
        assert isinstance(minute, dict)
        minute.update(
            {
                "buy_quote_notional": "61",
                "total_quote_notional": "101",
                "net_taker_quote_notional": "21",
                "above_mid_buy_quote_notional": "51",
            }
        )
        _reseal_v3_day(changed)
        conflict_envelope = _v3_envelope(
            changed, predecessor_day=None, predecessor_bundle_sha256=None
        )
        conflict = _apply(first.state_bytes, changed, conflict_envelope)
        blocked = load_canonical_v3_state_bytes(conflict.state_bytes)
        self.assertEqual("INTEGRITY_BLOCKED", conflict.disposition)
        self.assertEqual("CONFLICTING_DUPLICATE", blocked["failure"]["code"])

    def test_fourteen_v3_days_are_contiguous_and_terminal_discovery_only(self) -> None:
        state_bytes = initial_v3_state_bytes(
            DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
        )
        predecessor_day: date | None = None
        predecessor_hash: str | None = None
        for index in range(14):
            bundle_day = START_DAY + timedelta(days=index)
            bundle = _v3_day_bundle(bundle_day)
            envelope = _v3_envelope(
                bundle,
                predecessor_day=predecessor_day,
                predecessor_bundle_sha256=predecessor_hash,
            )
            result = _apply(state_bytes, bundle, envelope)
            state_bytes = result.state_bytes
            predecessor_day = bundle_day
            predecessor_hash = hashlib.sha256(
                canonical_json_bytes(bundle)
            ).hexdigest()
        state = load_canonical_v3_state_bytes(state_bytes)
        self.assertEqual("DIAGNOSTIC_READY", state["status"])
        self.assertEqual(14, len(state["accepted_days"]))
        self.assertEqual(
            "FROZEN_V3_DISCOVERY_ANALYSIS_ONLY",
            state["readiness"]["disposition"],
        )
        self.assertFalse(state["readiness"]["candidate_authorized"])
        self.assertFalse(state["readiness"]["oos_authorized"])
        self.assertFalse(state["readiness"]["promotion_authorized"])

    def test_v2_v3_documents_and_states_never_cross_accept(self) -> None:
        v2_bundle = _v2_day_bundle(START_DAY)
        v2_envelope = _v2_envelope(
            v2_bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        v3_bundle = _v3_day_bundle(START_DAY)
        v3_envelope = _v3_envelope(
            v3_bundle, predecessor_day=None, predecessor_bundle_sha256=None
        )
        with self.assertRaises(ContractViolation):
            validate_day_bundle(v3_bundle, raw_bytes=canonical_json_bytes(v3_bundle))
        with self.assertRaises(ContractViolation):
            validate_v3_day_bundle(v2_bundle, raw_bytes=canonical_json_bytes(v2_bundle))
        for validator, envelope, bundle in (
            (validate_drop_envelope, v3_envelope, v3_bundle),
            (validate_v3_drop_envelope, v2_envelope, v2_bundle),
            (validate_drop_envelope, v2_envelope, v3_bundle),
            (validate_v3_drop_envelope, v3_envelope, v2_bundle),
        ):
            with self.subTest(validator=validator.__name__):
                with self.assertRaises(ContractViolation):
                    validator(
                        envelope,
                        bundle,
                        raw_envelope_bytes=canonical_json_bytes(envelope),
                        raw_bundle_bytes=canonical_json_bytes(bundle),
                        expected_diagnostic_id=DIAGNOSTIC_ID,
                        expected_day=START_DAY,
                        expected_predecessor_day=None,
                        expected_predecessor_bundle_sha256=None,
                        observed_producer_identity="agora-evidence-source",
                        delivered_via_atomic_rename=True,
                        source_path_is_symlink=False,
                        overwrite_attempted=False,
                    )
        v2_state = initial_state_bytes(DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY)
        v3_state = initial_v3_state_bytes(
            DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
        )
        with self.assertRaises(ContractViolation):
            load_canonical_v3_state_bytes(v2_state)
        with self.assertRaises(ContractViolation):
            load_canonical_state_bytes(v3_state)

    def test_v3_midline_integrity_and_raw_bytes_fail_closed(self) -> None:
        bundle = _v3_day_bundle(START_DAY)
        mutations = []
        for key, value in (
            ("midline_reference_count", 2),
            ("midline_other_quote_notional", "21"),
        ):
            changed = deepcopy(bundle)
            changed["minutes"][0][key] = value
            _reseal_v3_day(changed)
            mutations.append(changed)
        changed = deepcopy(bundle)
        changed["integrity"]["crossed_book_count"] = 1
        _reseal_v3_day(changed)
        mutations.append(changed)
        for changed in mutations:
            with self.assertRaises(ContractViolation):
                validate_v3_day_bundle(
                    changed, raw_bytes=canonical_json_bytes(changed)
                )
        with self.assertRaises(ContractViolation):
            validate_v3_day_bundle(
                bundle, raw_bytes=canonical_json_bytes(bundle) + b"\n"
            )

    def test_monitor_uses_only_v3_namespace_and_hash_backed_state(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "microstructure").mkdir()
            (root / "microstructure" / "legacy.json").write_bytes(b"legacy-v2")
            namespace = root / "microstructure-v3"
            namespace.mkdir()
            state_bytes = initial_v3_state_bytes(
                DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
            )
            state_path = namespace / f"{DIAGNOSTIC_ID}.json"
            state_path.write_bytes(state_bytes)
            status = microstructure_diagnostic_status(
                root, now=datetime(2026, 8, 31, tzinfo=timezone.utc)
            )
            self.assertEqual("WAITING_FOR_DAY", status["status"])
            self.assertEqual("PRE_START", status["lag_classification"])
            self.assertEqual(
                f"microstructure-v3/{DIAGNOSTIC_ID}.json",
                status["artifact_path"],
            )
            self.assertEqual(hashlib.sha256(state_bytes).hexdigest(), status["sha256"])

    def test_monitor_blocks_absent_empty_multiple_and_recovery_markers(self) -> None:
        now = datetime(2026, 8, 31, tzinfo=timezone.utc)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(
                "RECOVERY_BLOCKED",
                microstructure_diagnostic_status(root, now=now)["status"],
            )
            namespace = root / "microstructure-v3"
            namespace.mkdir()
            self.assertEqual(
                "RECOVERY_BLOCKED",
                microstructure_diagnostic_status(root, now=now)["status"],
            )
            state = initial_v3_state_bytes(
                DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
            )
            path = namespace / f"{DIAGNOSTIC_ID}.json"
            path.write_bytes(state)
            second = namespace / "second-v3-state.json"
            second.write_bytes(state)
            self.assertEqual(
                "RECOVERY_BLOCKED",
                microstructure_diagnostic_status(root, now=now)["status"],
            )
            second.unlink()
            for suffix in ("lock", "tmp"):
                marker = namespace / f".{path.name}.{suffix}"
                marker.mkdir()
                self.assertEqual(
                    "RECOVERY_BLOCKED",
                    microstructure_diagnostic_status(root, now=now)["status"],
                )
                marker.rmdir()

    def test_monitor_blocks_bad_path_bytes_identity_and_unstable_reads(self) -> None:
        now = datetime(2026, 8, 31, tzinfo=timezone.utc)
        state_bytes = initial_v3_state_bytes(
            DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY
        )
        cases: list[tuple[str, bytes | None, str]] = [
            ("noncanonical", state_bytes + b"\n", f"{DIAGNOSTIC_ID}.json"),
            ("filename", state_bytes, "wrong-diagnostic.json"),
        ]
        v3_state = load_canonical_v3_state_bytes(state_bytes)
        wrong_version = deepcopy(v3_state)
        wrong_version["schema_version"] = "OKX_MICROSTRUCTURE_INTAKE_STATE_V1"
        cases.append(
            ("wrong-version", canonical_json_bytes(wrong_version), f"{DIAGNOSTIC_ID}.json")
        )
        wrong_hash = deepcopy(v3_state)
        wrong_hash["source_contract_sha256"] = "0" * 64
        cases.append(
            ("wrong-hash", canonical_json_bytes(wrong_hash), f"{DIAGNOSTIC_ID}.json")
        )
        for label, raw_bytes, filename in cases:
            with self.subTest(label=label), TemporaryDirectory() as directory:
                root = Path(directory)
                namespace = root / "microstructure-v3"
                namespace.mkdir()
                (namespace / filename).write_bytes(raw_bytes or b"")
                self.assertEqual(
                    "RECOVERY_BLOCKED",
                    microstructure_diagnostic_status(root, now=now)["status"],
                )

        with TemporaryDirectory() as directory:
            root = Path(directory)
            namespace = root / "microstructure-v3"
            namespace.mkdir()
            (namespace / f"{DIAGNOSTIC_ID}.json").mkdir()
            self.assertEqual(
                "RECOVERY_BLOCKED",
                microstructure_diagnostic_status(root, now=now)["status"],
            )

        with TemporaryDirectory() as directory:
            root = Path(directory)
            namespace = root / "microstructure-v3"
            namespace.mkdir()
            (namespace / f"{DIAGNOSTIC_ID}.json").write_bytes(state_bytes)
            with patch(
                "research_pipeline.microstructure_monitor._read_stable_regular_bytes",
                side_effect=ValueError("changed while observed"),
            ):
                self.assertEqual(
                    "RECOVERY_BLOCKED",
                    microstructure_diagnostic_status(root, now=now)["status"],
                )

        with TemporaryDirectory() as directory:
            root = Path(directory)
            namespace = root / "microstructure-v3"
            namespace.mkdir()
            with patch(
                "research_pipeline.microstructure_monitor._namespace_snapshot",
                return_value=((f"{DIAGNOSTIC_ID}.json", "symlink"),),
            ):
                self.assertEqual(
                    "RECOVERY_BLOCKED",
                    microstructure_diagnostic_status(root, now=now)["status"],
                )


if __name__ == "__main__":
    unittest.main()
