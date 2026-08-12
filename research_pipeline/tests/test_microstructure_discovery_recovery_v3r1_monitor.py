from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    advance_complete_day,
    advance_rejected_day,
    block_intake_state,
    build_rejection_envelope,
    build_source_binding,
    initial_intake_state,
)
from research_pipeline.heartbeat import run_heartbeat_cycle
from research_pipeline.microstructure_monitor import (
    microstructure_discovery_recovery_status,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.storage import ResearchStore


START = date(2026, 9, 1)
AS_OF = date(2026, 8, 12)
ZERO = "0" * 64


def _h(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


class MicrostructureDiscoveryRecoveryV3R1MonitorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.binding = build_source_binding(
            generation_id="okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
            diagnostic_id="okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
            producer_release_id="20260812T020000Z",
            producer_manifest_sha256=_h("manifest"),
            start_day=START,
            as_of_day=AS_OF,
        )

    def _write(self, root: Path, state: dict) -> Path:
        namespace = root / "microstructure-v3r1"
        namespace.mkdir()
        binding_path = root / "binding.json"
        binding_path.write_bytes(canonical_json_bytes(self.binding))
        (namespace / f"{self.binding['generation_id']}.json").write_bytes(
            canonical_json_bytes(state)
        )
        return binding_path

    def _status(self, root: Path, binding_path: Path, now: datetime) -> dict:
        return microstructure_discovery_recovery_status(
            root, binding_path=binding_path, now=now
        )

    def _complete(self, state: dict, day_value: date) -> dict:
        return advance_complete_day(
            state,
            binding_value=self.binding,
            day=day_value,
            bundle_sha256=_h(f"bundle:{day_value}"),
            envelope_sha256=_h(f"envelope:{day_value}"),
            accepted_at=datetime.combine(
                day_value + timedelta(days=1),
                datetime.min.time(),
                tzinfo=timezone.utc,
            ),
        )

    def _reject(self, state: dict, day_value: date) -> dict:
        rejected_at = datetime.combine(
            day_value, datetime.min.time(), tzinfo=timezone.utc
        ) + timedelta(hours=8)
        envelope = build_rejection_envelope(
            binding_value=self.binding,
            day=day_value,
            reason="TRANSPORT_DISCONNECT_UNPROVED_GAP",
            started_at=rejected_at - timedelta(hours=8),
            last_observed_at=rejected_at,
            acknowledged_channels=["books5", "trades"],
            completed_minute_count=479,
            data_message_count=1000,
            control_event_count=2,
            raw_arrival_chain_sha256=ZERO,
            control_event_chain_sha256=ZERO,
            rejected_at=rejected_at,
            sanitized_control_event=None,
        )
        return advance_rejected_day(
            state,
            envelope,
            raw_rejection_bytes=canonical_json_bytes(envelope),
            binding_value=self.binding,
        )

    def test_prestart_progress_ready_and_deadline_close_are_distinct(self) -> None:
        initial = initial_intake_state(self.binding)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, initial)
            status = self._status(
                root, binding_path, datetime(2026, 8, 31, tzinfo=timezone.utc)
            )
            self.assertEqual("WAITING_FOR_DAY", status["status"])
            self.assertEqual("PRE_START", status["lag_classification"])
            self.assertEqual(0, status["calendar_day_count"])

        state = self._complete(initial, START)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, state)
            status = self._status(
                root, binding_path, datetime(2026, 9, 2, tzinfo=timezone.utc)
            )
            self.assertEqual("WAITING_FOR_DAY", status["status"])
            self.assertEqual(1, status["current_streak_count"])
            self.assertEqual(1, status["complete_day_count"])

        ready = initial
        for offset in range(14):
            ready = self._complete(ready, START + timedelta(days=offset))
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, ready)
            status = self._status(
                root, binding_path, datetime(2026, 9, 15, tzinfo=timezone.utc)
            )
            self.assertEqual("DIAGNOSTIC_READY", status["status"])
            self.assertEqual(14, status["selected_streak_count"])

        closed = initial
        for offset in range(42):
            closed = self._reject(closed, START + timedelta(days=offset))
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, closed)
            status = self._status(
                root, binding_path, datetime(2026, 10, 13, tzinfo=timezone.utc)
            )
            self.assertEqual("NO_COMPLETE_STREAK_CLOSE", status["status"])
            self.assertEqual("COMPLETE_NO_EVIDENCE", status["lag_classification"])
            self.assertEqual(42, status["rejected_day_count"])

    def test_heartbeat_prefers_active_v3r1_over_retired_v3_namespace(self) -> None:
        initial = initial_intake_state(self.binding)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            store = ResearchStore(root, lock_stale_seconds=60)
            store.bootstrap()
            binding_path = self._write(root, initial)
            retired = root / "microstructure-v3"
            retired.mkdir()

            tick = {"status": "IDLE_NO_ACTIONABLE_EXPERIMENT"}
            heartbeat = run_heartbeat_cycle(
                store,
                {"policy_id": "TEST_RESEARCH_ONLY"},
                now=datetime(2026, 8, 31, tzinfo=timezone.utc),
                tick_preview=tick,
                tick_result=tick,
                microstructure_binding_path=binding_path,
            )

            diagnostic = heartbeat["microstructure_diagnostic"]
            self.assertEqual("HEARTBEAT_OK", heartbeat["status"])
            self.assertEqual("WAITING_FOR_DAY", diagnostic["status"])
            self.assertEqual("PRE_START", diagnostic["lag_classification"])
            self.assertEqual(self.binding["generation_id"], diagnostic["generation_id"])
            self.assertEqual(self.binding["diagnostic_id"], diagnostic["diagnostic_id"])

    def test_overdue_blocked_and_ambiguous_state_fail_closed(self) -> None:
        initial = initial_intake_state(self.binding)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, initial)
            status = self._status(
                root, binding_path, datetime(2026, 9, 2, tzinfo=timezone.utc)
            )
            self.assertEqual("CAPTURE_OVERDUE", status["status"])

        blocked = block_intake_state(
            initial,
            binding_value=self.binding,
            code="UNKNOWN_EVENT",
            detail="unknown control event",
        )
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, blocked)
            status = self._status(
                root, binding_path, datetime(2026, 9, 1, tzinfo=timezone.utc)
            )
            self.assertEqual("INTEGRITY_BLOCKED", status["status"])

        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, initial)
            namespace = root / "microstructure-v3r1"
            (namespace / "okx-btcusdt-microstructure-discovery-v3r1-20260901-r4.json").write_bytes(
                canonical_json_bytes(initial)
            )
            self.assertEqual(
                "RECOVERY_BLOCKED",
                self._status(root, binding_path, datetime.now(timezone.utc))["status"],
            )

    def test_noncanonical_binding_state_and_symlink_are_rejected(self) -> None:
        initial = initial_intake_state(self.binding)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, initial)
            binding_path.write_bytes(canonical_json_bytes(self.binding) + b"\n")
            self.assertEqual(
                "RECOVERY_BLOCKED",
                self._status(root, binding_path, datetime.now(timezone.utc))["status"],
            )

        with TemporaryDirectory() as directory:
            root = Path(directory)
            binding_path = self._write(root, initial)
            state_path = (
                root / "microstructure-v3r1" / f"{self.binding['generation_id']}.json"
            )
            state_path.write_bytes(canonical_json_bytes(initial) + b"\n")
            self.assertEqual(
                "RECOVERY_BLOCKED",
                self._status(root, binding_path, datetime.now(timezone.utc))["status"],
            )

        with TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "target"
            target.mkdir()
            namespace = root / "microstructure-v3r1"
            try:
                namespace.symlink_to(target, target_is_directory=True)
            except OSError:
                self.skipTest("directory symlink creation is unavailable")
            binding_path = root / "binding.json"
            binding_path.write_bytes(canonical_json_bytes(self.binding))
            self.assertEqual(
                "RECOVERY_BLOCKED",
                self._status(root, binding_path, datetime.now(timezone.utc))["status"],
            )


if __name__ == "__main__":
    unittest.main()
