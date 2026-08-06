from copy import deepcopy
from dataclasses import replace
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_intake import (
    ObservedDelivery,
    RecoveryBlocked,
    apply_observed_delivery,
    commit_canonical_state,
    initial_state_bytes,
    load_canonical_state_bytes,
    state_lock_path,
    state_temp_path,
)
from research_pipeline.microstructure_source_contract import (
    ContractViolation,
    canonical_json_bytes,
)
from research_pipeline.tests.test_microstructure_source_contract import (
    DIAGNOSTIC_ID,
    _day_bundle,
    _envelope,
    _reseal_day,
    _timestamp,
)


START_DAY = date(2026, 1, 2)
AS_OF_DAY = date(2026, 1, 1)


def _observed_delivery() -> ObservedDelivery:
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


def _accepted_at(day: date) -> str:
    return _timestamp(
        datetime.combine(day + timedelta(days=1), time.min, timezone.utc)
        + timedelta(seconds=3)
    )


def _apply(
    state_bytes: bytes,
    bundle: dict,
    envelope: dict,
    *,
    observed: ObservedDelivery | None = None,
    bundle_bytes: bytes | None = None,
    envelope_bytes: bytes | None = None,
):
    return apply_observed_delivery(
        state_bytes=state_bytes,
        bundle_bytes=bundle_bytes or canonical_json_bytes(bundle),
        envelope_bytes=envelope_bytes or canonical_json_bytes(envelope),
        observed=observed or _observed_delivery(),
        accepted_at=_accepted_at(date.fromisoformat(bundle["day"])),
    )


def _initial_bytes() -> bytes:
    return initial_state_bytes(DIAGNOSTIC_ID, START_DAY, as_of_day=AS_OF_DAY)


def _drop_envelope(
    bundle: dict,
    predecessor_day: date | None,
    predecessor_bundle_sha256: str | None,
) -> dict:
    return _envelope(
        bundle,
        predecessor_day=predecessor_day,
        predecessor_bundle_sha256=predecessor_bundle_sha256,
    )


class MicrostructureIntakeTest(unittest.TestCase):
    def test_future_initialization_is_canonical_and_discovery_only(self):
        state_bytes = _initial_bytes()
        state = load_canonical_state_bytes(state_bytes)

        self.assertEqual(state_bytes, canonical_json_bytes(state))
        self.assertFalse(state_bytes.endswith(b"\n"))
        self.assertEqual(state["status"], "WAITING_FOR_DAY")
        self.assertEqual(state["next_expected_day"], START_DAY.isoformat())
        readiness = state["readiness"]
        self.assertFalse(readiness["candidate_authorized"])
        self.assertFalse(readiness["oos_authorized"])
        self.assertFalse(readiness["pnl_claim_authorized"])
        self.assertFalse(readiness["promotion_authorized"])
        self.assertEqual(readiness["performance_value"], "MISSING_PROOF")

        with self.assertRaises(ContractViolation):
            initial_state_bytes(DIAGNOSTIC_ID, START_DAY, as_of_day=START_DAY)

    def test_noncanonical_state_bytes_are_rejected(self):
        state_bytes = _initial_bytes()
        for mutated in (state_bytes + b"\n", b" " + state_bytes):
            with self.subTest(mutated=mutated[:1]):
                with self.assertRaisesRegex(ContractViolation, "canonical"):
                    load_canonical_state_bytes(mutated)

    def test_first_day_acceptance_and_exact_duplicate(self):
        bundle = _day_bundle(START_DAY)
        envelope = _drop_envelope(bundle, None, None)

        first = _apply(_initial_bytes(), bundle, envelope)
        first_state = load_canonical_state_bytes(first.state_bytes)
        self.assertEqual(first.disposition, "WAITING_FOR_DAY")
        self.assertEqual(len(first_state["accepted_days"]), 1)

        duplicate = _apply(first.state_bytes, bundle, envelope)
        self.assertEqual(duplicate.disposition, "IDEMPOTENT_DUPLICATE")
        self.assertEqual(duplicate.state_bytes, first.state_bytes)

    def test_conflicting_duplicate_blocks(self):
        bundle = _day_bundle(START_DAY)
        envelope = _drop_envelope(bundle, None, None)
        first = _apply(_initial_bytes(), bundle, envelope)

        conflict = deepcopy(bundle)
        conflict["minutes"][0]["buy_quote_notional"] = "61"
        conflict["minutes"][0]["total_quote_notional"] = "101"
        conflict["minutes"][0]["net_taker_quote_notional"] = "21"
        _reseal_day(conflict)
        conflicting_envelope = _drop_envelope(conflict, None, None)

        blocked = _apply(first.state_bytes, conflict, conflicting_envelope)
        blocked_state = load_canonical_state_bytes(blocked.state_bytes)
        self.assertEqual(blocked.disposition, "INTEGRITY_BLOCKED")
        self.assertEqual(blocked_state["failure"]["code"], "CONFLICTING_DUPLICATE")

    def test_wrong_predecessor_and_skipped_day_block(self):
        day_one_bundle = _day_bundle(START_DAY)
        day_one_envelope = _drop_envelope(day_one_bundle, None, None)
        day_one = _apply(_initial_bytes(), day_one_bundle, day_one_envelope)

        day_two = START_DAY + timedelta(days=1)
        day_two_bundle = _day_bundle(day_two)
        wrong_predecessor = _drop_envelope(day_two_bundle, START_DAY, "c" * 64)
        blocked = _apply(day_one.state_bytes, day_two_bundle, wrong_predecessor)
        blocked_state = load_canonical_state_bytes(blocked.state_bytes)
        self.assertEqual(blocked_state["failure"]["code"], "PREDECESSOR_MISMATCH")

        skipped = _apply(
            _initial_bytes(),
            day_two_bundle,
            _drop_envelope(day_two_bundle, START_DAY, "c" * 64),
        )
        skipped_state = load_canonical_state_bytes(skipped.state_bytes)
        self.assertEqual(skipped_state["failure"]["code"], "NONCONTIGUOUS_DAY")

    def test_noncanonical_and_malformed_evidence_block(self):
        bundle = _day_bundle(START_DAY)
        envelope = _drop_envelope(bundle, None, None)

        cases = (
            (canonical_json_bytes(bundle) + b"\n", canonical_json_bytes(envelope)),
            (canonical_json_bytes(bundle), b" " + canonical_json_bytes(envelope)),
            (b"{", canonical_json_bytes(envelope)),
        )
        for bundle_bytes, envelope_bytes in cases:
            with self.subTest(bundle_prefix=bundle_bytes[:1], envelope_prefix=envelope_bytes[:1]):
                blocked = _apply(
                    _initial_bytes(),
                    bundle,
                    envelope,
                    bundle_bytes=bundle_bytes,
                    envelope_bytes=envelope_bytes,
                )
                blocked_state = load_canonical_state_bytes(blocked.state_bytes)
                self.assertEqual(blocked.disposition, "INTEGRITY_BLOCKED")
                self.assertEqual(blocked_state["failure"]["code"], "HASH_MISMATCH")

    def test_observed_delivery_policy_failures_block(self):
        bundle = _day_bundle(START_DAY)
        envelope = _drop_envelope(bundle, None, None)
        valid = _observed_delivery()
        cases = (
            (replace(valid, intake_identity="wrong"), "WRONG_IDENTITY"),
            (replace(valid, network_access="ALLOW"), "LIFECYCLE_CLOCK_FORBIDDEN"),
            (replace(valid, producer_identity="wrong"), "WRONG_IDENTITY"),
            (replace(valid, delivered_via_atomic_rename=False), "NON_ATOMIC_DELIVERY"),
            (replace(valid, source_path_is_symlink=True), "SYMLINK_REJECT"),
            (replace(valid, overwrite_attempted=True), "OVERWRITE_REJECT"),
            (replace(valid, historical_backfill_requested=True), "BACKFILL_FORBIDDEN"),
            (replace(valid, candle_chain_reuse_requested=True), "CANDLE_CHAIN_REUSE_FORBIDDEN"),
            (
                replace(valid, research_lifecycle_action_requested=True),
                "LIFECYCLE_CLOCK_FORBIDDEN",
            ),
        )
        for observed, expected_code in cases:
            with self.subTest(expected_code=expected_code):
                blocked = _apply(
                    _initial_bytes(), bundle, envelope, observed=observed
                )
                blocked_state = load_canonical_state_bytes(blocked.state_bytes)
                self.assertEqual(blocked.disposition, "INTEGRITY_BLOCKED")
                self.assertEqual(blocked_state["failure"]["code"], expected_code)

    def test_atomic_commit_and_restart_reload(self):
        with TemporaryDirectory() as directory:
            state_path = Path(directory) / "intake-state.json"
            initial = _initial_bytes()
            commit_canonical_state(state_path, initial)
            self.assertEqual(state_path.read_bytes(), initial)
            self.assertFalse(state_lock_path(state_path).exists())
            self.assertFalse(state_temp_path(state_path).exists())

            bundle_one = _day_bundle(START_DAY)
            envelope_one = _drop_envelope(bundle_one, None, None)
            accepted_one = _apply(state_path.read_bytes(), bundle_one, envelope_one)
            commit_canonical_state(state_path, accepted_one.state_bytes)

            restarted = load_canonical_state_bytes(state_path.read_bytes())
            predecessor = restarted["accepted_days"][0]
            day_two = START_DAY + timedelta(days=1)
            bundle_two = _day_bundle(day_two)
            envelope_two = _drop_envelope(
                bundle_two,
                START_DAY,
                predecessor["bundle_sha256"],
            )
            accepted_two = _apply(state_path.read_bytes(), bundle_two, envelope_two)
            commit_canonical_state(state_path, accepted_two.state_bytes)

            reloaded = load_canonical_state_bytes(state_path.read_bytes())
            self.assertEqual(len(reloaded["accepted_days"]), 2)
            self.assertEqual(reloaded["next_expected_day"], (day_two + timedelta(days=1)).isoformat())

    def test_stale_lock_and_temp_are_recovery_blocked_without_cleanup(self):
        with TemporaryDirectory() as directory:
            state_path = Path(directory) / "intake-state.json"
            lock_path = state_lock_path(state_path)
            lock_path.mkdir()
            with self.assertRaises(RecoveryBlocked):
                commit_canonical_state(state_path, _initial_bytes())
            self.assertTrue(lock_path.is_dir())
            self.assertFalse(state_path.exists())

        with TemporaryDirectory() as directory:
            state_path = Path(directory) / "intake-state.json"
            temp_path = state_temp_path(state_path)
            temp_path.write_bytes(b"stale")
            with self.assertRaises(RecoveryBlocked):
                commit_canonical_state(state_path, _initial_bytes())
            self.assertTrue(temp_path.exists())
            self.assertTrue(state_lock_path(state_path).is_dir())
            self.assertFalse(state_path.exists())

    def test_days_one_to_thirteen_wait_and_day_fourteen_ready(self):
        state_bytes = _initial_bytes()
        predecessor_day = None
        predecessor_hash = None

        for offset in range(14):
            day = START_DAY + timedelta(days=offset)
            bundle = _day_bundle(day)
            envelope = _drop_envelope(bundle, predecessor_day, predecessor_hash)
            applied = _apply(state_bytes, bundle, envelope)
            state_bytes = applied.state_bytes
            state = load_canonical_state_bytes(state_bytes)

            if offset < 13:
                self.assertEqual(applied.disposition, "WAITING_FOR_DAY")
                self.assertEqual(state["status"], "WAITING_FOR_DAY")
            else:
                self.assertEqual(applied.disposition, "DIAGNOSTIC_READY")
                self.assertEqual(state["status"], "DIAGNOSTIC_READY")
                self.assertEqual(
                    state["readiness"]["disposition"],
                    "FROZEN_V2_DISCOVERY_ANALYSIS_ONLY",
                )
                self.assertFalse(state["readiness"]["candidate_authorized"])
                self.assertFalse(state["readiness"]["oos_authorized"])
                self.assertFalse(state["readiness"]["pnl_claim_authorized"])
                self.assertFalse(state["readiness"]["promotion_authorized"])
                self.assertEqual(
                    state["readiness"]["performance_value"], "MISSING_PROOF"
                )

            accepted = state["accepted_days"][-1]
            predecessor_day = day
            predecessor_hash = accepted["bundle_sha256"]


if __name__ == "__main__":
    unittest.main()
