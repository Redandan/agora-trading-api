from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator

from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    ALLOWED_REJECTION_REASONS,
    BINDING_SCHEMA_PATH,
    CALENDAR_DAY_BUDGET,
    CONTRACT_SHA256,
    DiscoveryRecoveryBlocked,
    REJECTION_SCHEMA_PATH,
    REQUIRED_STREAK_DAYS,
    STATE_SCHEMA_PATH,
    advance_complete_day,
    advance_rejected_day,
    block_intake_state,
    build_rejection_envelope,
    build_source_binding,
    classify_control_event,
    initial_intake_state,
    next_control_event_chain,
    validate_frozen_files,
    validate_intake_state,
    validate_rejection_envelope,
    validate_source_binding,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes


START = date(2026, 9, 1)
AS_OF = date(2026, 8, 12)
RECORDED = datetime(2026, 9, 2, 0, 0, 1, tzinfo=timezone.utc)
ZERO = "0" * 64


def _h(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


class MicrostructureDiscoveryRecoveryV3R1Test(unittest.TestCase):
    def setUp(self) -> None:
        self.binding = build_source_binding(
            generation_id="okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
            diagnostic_id="okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
            producer_release_id="20260812T020000Z",
            producer_manifest_sha256=_h("manifest"),
            start_day=START,
            as_of_day=AS_OF,
        )
        self.state = initial_intake_state(self.binding)

    def _complete(self, state: dict, day_value: date) -> dict:
        return advance_complete_day(
            state,
            binding_value=self.binding,
            day=day_value,
            bundle_sha256=_h(f"bundle:{day_value}"),
            envelope_sha256=_h(f"envelope:{day_value}"),
            accepted_at=RECORDED + timedelta(days=(day_value - START).days),
        )

    def _rejection(
        self,
        day_value: date,
        reason: str = "TRANSPORT_DISCONNECT_UNPROVED_GAP",
    ) -> dict:
        sanitized = (
            {"event": "notice", "code": "64008"}
            if reason == "SERVICE_UPGRADE_NOTICE_64008"
            else None
        )
        return build_rejection_envelope(
            binding_value=self.binding,
            day=day_value,
            reason=reason,
            started_at=datetime.combine(
                day_value, datetime.min.time(), tzinfo=timezone.utc
            ),
            last_observed_at=datetime.combine(
                day_value, datetime.min.time(), tzinfo=timezone.utc
            )
            + timedelta(hours=9),
            acknowledged_channels=["trades", "books5"],
            completed_minute_count=539,
            data_message_count=10_000,
            control_event_count=3,
            raw_arrival_chain_sha256=_h(f"arrival:{day_value}"),
            control_event_chain_sha256=_h(f"control:{day_value}"),
            rejected_at=datetime.combine(
                day_value, datetime.min.time(), tzinfo=timezone.utc
            )
            + timedelta(hours=9, seconds=1),
            sanitized_control_event=sanitized,
        )

    def _reject(self, state: dict, day_value: date, reason: str) -> dict:
        rejection = self._rejection(day_value, reason)
        return advance_rejected_day(
            state,
            rejection,
            raw_rejection_bytes=canonical_json_bytes(rejection),
            binding_value=self.binding,
        )

    def test_frozen_files_and_schemas_are_valid(self) -> None:
        hashes = validate_frozen_files()
        self.assertEqual(len(hashes), 6)
        for path in (BINDING_SCHEMA_PATH, REJECTION_SCHEMA_PATH, STATE_SCHEMA_PATH):
            Draft202012Validator.check_schema(
                json.loads(path.read_text(encoding="utf-8"))
            )

    def test_binding_is_strictly_future_and_fixes_42_day_deadline(self) -> None:
        validated = validate_source_binding(self.binding)
        self.assertEqual(validated["recovery_contract_sha256"], CONTRACT_SHA256)
        self.assertEqual(validated["calendar_day_budget"], CALENDAR_DAY_BUDGET)
        self.assertEqual(validated["required_consecutive_complete_days"], 14)
        self.assertEqual(validated["end_day"], "2026-10-12")
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "BACKFILL_FORBIDDEN"):
            build_source_binding(
                generation_id=self.binding["generation_id"],
                diagnostic_id=self.binding["diagnostic_id"],
                producer_release_id=self.binding["producer_release_id"],
                producer_manifest_sha256=self.binding["producer_manifest_sha256"],
                start_day=AS_OF,
                as_of_day=AS_OF,
            )
        changed = deepcopy(self.binding)
        changed["diagnostic_id"] = (
            "okx-btcusdt-microstructure-forward-v3r1-20260902-r3"
        )
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "WRONG_IDENTITY"):
            validate_source_binding(changed)
        changed = deepcopy(self.binding)
        changed["diagnostic_id"] = (
            "okx-btcusdt-microstructure-forward-v3r1-20260901-r4"
        )
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "WRONG_IDENTITY"):
            validate_source_binding(changed)

    def test_fourteen_consecutive_days_select_first_streak(self) -> None:
        state = self.state
        for offset in range(REQUIRED_STREAK_DAYS):
            state = self._complete(state, START + timedelta(days=offset))
        self.assertEqual(state["status"], "DIAGNOSTIC_READY")
        self.assertEqual(len(state["selected_streak"]), 14)
        self.assertEqual(state["selected_streak"][0]["day"], "2026-09-01")
        self.assertEqual(state["selected_streak"][-1]["day"], "2026-09-14")
        self.assertEqual(state["current_streak"], [])
        self.assertIsNone(state["next_calendar_day"])
        self.assertEqual(
            state["readiness"]["disposition"],
            "FROZEN_V3R1_DISCOVERY_ANALYSIS_ONLY",
        )
        self.assertFalse(state["readiness"]["candidate_authorized"])
        self.assertFalse(state["readiness"]["oos_authorized"])

    def test_rejection_archives_prefix_and_second_streak_is_selected(self) -> None:
        state = self._complete(self.state, START)
        state = self._reject(
            state, START + timedelta(days=1), "SERVICE_UPGRADE_NOTICE_64008"
        )
        self.assertEqual(state["current_streak"], [])
        self.assertEqual(len(state["nonselected_complete_prefixes"]), 1)
        self.assertEqual(state["nonselected_complete_prefixes"][0][0]["day"], "2026-09-01")
        for offset in range(2, 16):
            state = self._complete(state, START + timedelta(days=offset))
        self.assertEqual(state["status"], "DIAGNOSTIC_READY")
        self.assertEqual(state["selected_streak"][0]["day"], "2026-09-03")
        self.assertEqual(state["selected_streak"][-1]["day"], "2026-09-16")
        selected_days = {record["day"] for record in state["selected_streak"]}
        self.assertNotIn("2026-09-01", selected_days)

    def test_three_thirteen_day_prefixes_close_at_fixed_deadline(self) -> None:
        state = self.state
        day_value = START
        for cycle in range(3):
            for _ in range(13):
                state = self._complete(state, day_value)
                day_value += timedelta(days=1)
            state = self._reject(
                state,
                day_value,
                ALLOWED_REJECTION_REASONS[cycle],
            )
            day_value += timedelta(days=1)
        self.assertEqual(len(state["calendar_dispositions"]), 42)
        self.assertEqual(state["status"], "NO_COMPLETE_STREAK_CLOSE")
        self.assertEqual(len(state["nonselected_complete_prefixes"]), 3)
        self.assertTrue(
            all(len(prefix) == 13 for prefix in state["nonselected_complete_prefixes"])
        )
        self.assertIsNone(state["selected_streak"])
        self.assertIsNone(state["next_calendar_day"])
        self.assertEqual(
            state["readiness"]["disposition"], "NO_COMPLETE_STREAK_CLOSE"
        )

    def test_calendar_gap_backfill_and_terminal_replay_fail_closed(self) -> None:
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "WRONG_DAY"):
            self._complete(self.state, START + timedelta(days=1))
        state = self._complete(self.state, START)
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "BACKFILL_FORBIDDEN"):
            self._complete(state, START)
        for offset in range(1, 14):
            state = self._complete(state, START + timedelta(days=offset))
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "CONFLICTING_DUPLICATE"):
            self._complete(state, START + timedelta(days=14))

    def test_state_tampering_and_cross_gap_stitching_are_rejected(self) -> None:
        state = self._complete(self.state, START)
        changed = deepcopy(state)
        changed["cloud_schedule_count"] = 2
        with self.assertRaisesRegex(
            DiscoveryRecoveryBlocked, "SECOND_CLOCK_OR_WRITER_FORBIDDEN"
        ):
            validate_intake_state(changed, self.binding)
        changed = deepcopy(state)
        changed["current_streak"][0]["day"] = "2026-09-02"
        with self.assertRaises(DiscoveryRecoveryBlocked):
            validate_intake_state(changed, self.binding)

        state = self._complete(self.state, START)
        state = self._complete(state, START + timedelta(days=1))
        state = self._reject(
            state,
            START + timedelta(days=2),
            "TRANSPORT_DISCONNECT_UNPROVED_GAP",
        )
        changed = deepcopy(state)
        original_prefix = changed["nonselected_complete_prefixes"][0]
        changed["nonselected_complete_prefixes"] = [
            [original_prefix[0]],
            [original_prefix[1]],
        ]
        with self.assertRaisesRegex(
            DiscoveryRecoveryBlocked, "streak grouping changed"
        ):
            validate_intake_state(changed, self.binding)

    def test_integrity_failure_is_bounded_terminal_state(self) -> None:
        state = self._complete(self.state, START)
        state = block_intake_state(
            state,
            binding_value=self.binding,
            code="UNKNOWN_EVENT",
            detail="unrecognized control event",
        )
        self.assertEqual(state["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(state["failure"]["day"], "2026-09-02")
        self.assertEqual(state["readiness"]["disposition"], "INTEGRITY_BLOCKED")
        self.assertFalse(state["readiness"]["candidate_authorized"])
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "CONFLICTING_DUPLICATE"):
            self._complete(state, START + timedelta(days=1))
        changed = deepcopy(state)
        changed["failure"]["day"] = "2026-09-03"
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "WRONG_DAY"):
            validate_intake_state(changed, self.binding)

    def test_notice_and_channel_count_are_narrowly_classified(self) -> None:
        notice = classify_control_event({"event": "notice", "code": "64008"})
        self.assertEqual(notice["action"], "REJECT_ACTIVE_DAY")
        self.assertEqual(notice["reason"], "SERVICE_UPGRADE_NOTICE_64008")
        count = classify_control_event(
            {
                "event": "channel-conn-count",
                "channel": "books5",
                "connCount": "1",
                "connId": "opaque",
            }
        )
        self.assertEqual(count["action"], "SEAL_CONTROL_EVENT_AND_CONTINUE")
        ack = classify_control_event(
            {
                "event": "subscribe",
                "arg": {"channel": "trades", "instId": "BTC-USDT"},
            }
        )
        self.assertEqual(ack, {"action": "ACKNOWLEDGE", "channel": "trades", "sanitized": None})
        for event in (
            {"event": "notice", "code": "99999"},
            {"event": "error", "code": "60012"},
            {"event": "channel-conn-count-error", "channel": "trades"},
            {"event": "unsubscribe", "arg": {}},
            {"event": "future-new-event"},
        ):
            with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "UNKNOWN_EVENT"):
                classify_control_event(event)

    def test_control_event_chain_is_raw_byte_sensitive(self) -> None:
        first = next_control_event_chain(ZERO, b'{"event":"notice","code":"64008"}')
        repeated = next_control_event_chain(ZERO, b'{"event":"notice","code":"64008"}')
        changed = next_control_event_chain(ZERO, b'{"code":"64008","event":"notice"}')
        self.assertEqual(first, repeated)
        self.assertNotEqual(first, changed)

    def test_rejection_envelope_is_canonical_private_and_atomic(self) -> None:
        rejection = self._rejection(START, "SERVICE_UPGRADE_NOTICE_64008")
        raw = canonical_json_bytes(rejection)
        validated = validate_rejection_envelope(
            rejection,
            raw_bytes=raw,
            binding_value=self.binding,
            expected_day=START,
            delivered_via_atomic_rename=True,
            source_path_is_symlink=False,
            overwrite_attempted=False,
            observed_producer_identity="agora-evidence-source",
        )
        self.assertEqual(validated["reason"], "SERVICE_UPGRADE_NOTICE_64008")
        self.assertNotIn("return", json.dumps(rejection).lower())
        self.assertNotIn("feature", json.dumps(rejection).lower())
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "NON_ATOMIC_DELIVERY"):
            validate_rejection_envelope(
                rejection,
                raw_bytes=raw,
                binding_value=self.binding,
                expected_day=START,
                delivered_via_atomic_rename=False,
                source_path_is_symlink=False,
                overwrite_attempted=False,
                observed_producer_identity="agora-evidence-source",
            )
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "CONTRACT_HASH_MISMATCH"):
            validate_rejection_envelope(
                rejection,
                raw_bytes=raw + b"\n",
                binding_value=self.binding,
                expected_day=START,
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
                observed_producer_identity="agora-evidence-source",
            )

    def test_non_notice_rejection_cannot_claim_notice_and_partial_day_is_bounded(self) -> None:
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "UNKNOWN_EVENT"):
            build_rejection_envelope(
                binding_value=self.binding,
                day=START,
                reason="PROCESS_RESTART_BEFORE_DAY_COMPLETE",
                started_at=None,
                last_observed_at=None,
                acknowledged_channels=[],
                completed_minute_count=0,
                data_message_count=0,
                control_event_count=0,
                raw_arrival_chain_sha256=ZERO,
                control_event_chain_sha256=ZERO,
                rejected_at=RECORDED,
                sanitized_control_event={"event": "notice", "code": "64008"},
            )
        with self.assertRaisesRegex(DiscoveryRecoveryBlocked, "completed_minute_count"):
            build_rejection_envelope(
                binding_value=self.binding,
                day=START,
                reason="PROCESS_RESTART_BEFORE_DAY_COMPLETE",
                started_at=None,
                last_observed_at=None,
                acknowledged_channels=[],
                completed_minute_count=1440,
                data_message_count=0,
                control_event_count=0,
                raw_arrival_chain_sha256=ZERO,
                control_event_chain_sha256=ZERO,
                rejected_at=RECORDED,
                sanitized_control_event=None,
            )


if __name__ == "__main__":
    unittest.main()
