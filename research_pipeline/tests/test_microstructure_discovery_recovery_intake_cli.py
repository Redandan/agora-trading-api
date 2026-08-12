from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    FrozenDisposition,
    IntakeCliBlocked,
    RuntimePaths,
    _REQUIRED_RELEASE_FILES,
    run,
)
from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    build_complete_envelope,
    build_rejection_envelope,
    build_source_binding,
    block_intake_state,
    canonical_intake_state_bytes,
    load_canonical_intake_state_bytes,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests.test_microstructure_v3_intake_isolation import (
    _v3_day_bundle,
)


START = date(2026, 9, 1)
AS_OF = date(2026, 8, 12)
RELEASE_ID = "20260812T030000Z"
ZERO = "0" * 64
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _freeze(item) -> FrozenDisposition:
    return FrozenDisposition(
        envelope_bytes=item.envelope.read_bytes(),
        bundle_bytes=None if item.bundle is None else item.bundle.read_bytes(),
    )


class MicrostructureDiscoveryRecoveryIntakeCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        root = Path(self.temp.name)
        self.release = root / "releases" / RELEASE_ID
        self.release.mkdir(parents=True)
        manifest_lines: list[str] = []
        for relative in sorted(_REQUIRED_RELEASE_FILES):
            source = REPOSITORY_ROOT / relative
            destination = self.release / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            digest = hashlib.sha256(destination.read_bytes()).hexdigest()
            manifest_lines.append(f"{digest}  {relative}")
        release_metadata = self.release / ".release"
        release_metadata.mkdir()
        manifest_bytes = ("\n".join(manifest_lines) + "\n").encode("utf-8")
        (release_metadata / "source.sha256").write_bytes(manifest_bytes)
        self.manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
        (release_metadata / "provenance.json").write_bytes(
            canonical_json_bytes(
                {
                    "release_id": RELEASE_ID,
                    "source_manifest_sha256": self.manifest_hash,
                }
            )
        )
        self.binding = build_source_binding(
            generation_id="okx-btcusdt-microstructure-discovery-v3r1-20260901-r3",
            diagnostic_id="okx-btcusdt-microstructure-forward-v3r1-20260901-r3",
            producer_release_id=RELEASE_ID,
            producer_manifest_sha256=self.manifest_hash,
            start_day=START,
            as_of_day=AS_OF,
        )
        self.binding_path = root / "binding.json"
        self.binding_path.write_bytes(canonical_json_bytes(self.binding))
        self.drop_root = root / "drop"
        self.staging_root = root / "staging"
        self.state_root = root / "state"
        self.drop_root.mkdir()
        self.staging_root.mkdir()
        self.state_root.mkdir()
        self.paths = RuntimePaths(
            binding=self.binding_path,
            drop_root=self.drop_root,
            staging_root=self.staging_root,
            state_root=self.state_root,
            release=self.release,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _run(self, command: str, now: datetime) -> str:
        return run(
            command,
            paths=self.paths,
            clock=lambda: now,
            free_bytes=lambda _: 4 * 1024 * 1024 * 1024,
            device_id=lambda _: 1,
            freezer=_freeze,
        )

    def _state_path(self) -> Path:
        return self.state_root / f"{self.binding['generation_id']}.json"

    def _state(self) -> dict:
        return load_canonical_intake_state_bytes(
            self._state_path().read_bytes(), self.binding
        )

    def _reserve(self, day_value: date) -> Path:
        reservation = self.drop_root / f".{day_value.isoformat()}.publish-reserved"
        reservation.write_bytes(b"")
        directory = self.drop_root / day_value.isoformat()
        directory.mkdir()
        return directory

    def _publish_complete(self, day_value: date) -> None:
        directory = self._reserve(day_value)
        bundle = _v3_day_bundle(day_value)
        bundle_bytes = canonical_json_bytes(bundle)
        published_at = datetime.combine(
            day_value + timedelta(days=1),
            datetime.min.time(),
            tzinfo=timezone.utc,
        ) + timedelta(seconds=2)
        envelope = build_complete_envelope(
            binding_value=self.binding,
            bundle_value=bundle,
            raw_bundle_bytes=bundle_bytes,
            day=day_value,
            published_at=published_at,
        )
        prefix = f"okx-btc-usdt-microstructure-{day_value.isoformat()}"
        (directory / f"{prefix}.json").write_bytes(bundle_bytes)
        (directory / f"{prefix}.complete.envelope.json").write_bytes(
            canonical_json_bytes(envelope)
        )

    def _publish_rejection(self, day_value: date) -> None:
        directory = self._reserve(day_value)
        rejected_at = datetime.combine(
            day_value, datetime.min.time(), tzinfo=timezone.utc
        ) + timedelta(hours=9)
        envelope = build_rejection_envelope(
            binding_value=self.binding,
            day=day_value,
            reason="TRANSPORT_DISCONNECT_UNPROVED_GAP",
            started_at=rejected_at - timedelta(hours=9),
            last_observed_at=rejected_at,
            acknowledged_channels=["books5", "trades"],
            completed_minute_count=539,
            data_message_count=10_000,
            control_event_count=3,
            raw_arrival_chain_sha256=ZERO,
            control_event_chain_sha256=ZERO,
            rejected_at=rejected_at,
            sanitized_control_event=None,
        )
        prefix = f"okx-btc-usdt-microstructure-{day_value.isoformat()}"
        (directory / f"{prefix}.rejection.envelope.json").write_bytes(
            canonical_json_bytes(envelope)
        )

    def test_initialize_is_future_create_only_and_restart_idempotent(self) -> None:
        now = datetime(2026, 8, 12, tzinfo=timezone.utc)
        self.assertEqual("WAITING_FOR_CALENDAR_DAY", self._run("initialize", now))
        before = self._state_path().read_bytes()
        self.assertEqual("WAITING_FOR_CALENDAR_DAY", self._run("initialize", now))
        self.assertEqual(before, self._state_path().read_bytes())
        self.assertEqual(0, len(self._state()["calendar_dispositions"]))

    def test_delayed_batch_preserves_source_time_and_resets_streak(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        self._publish_complete(START)
        self._publish_rejection(START + timedelta(days=1))
        self._publish_complete(START + timedelta(days=2))
        status = self._run(
            "ingest", datetime(2026, 9, 4, 1, tzinfo=timezone.utc)
        )
        self.assertEqual("BUILDING_CONSECUTIVE_STREAK", status)
        state = self._state()
        self.assertEqual(3, len(state["calendar_dispositions"]))
        self.assertEqual(1, len(state["nonselected_complete_prefixes"]))
        self.assertEqual("2026-09-01", state["nonselected_complete_prefixes"][0][0]["day"])
        self.assertEqual("2026-09-03", state["current_streak"][0]["day"])
        self.assertEqual(
            "2026-09-02T00:00:02Z",
            state["calendar_dispositions"][0]["recorded_at"],
        )
        self.assertEqual(
            "2026-09-04T01:00:00Z",
            state["nonselected_complete_prefixes"][0][0]["accepted_at"],
        )

    def test_identical_restart_is_noop_but_changed_prior_bytes_block(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        self._publish_complete(START)
        now = datetime(2026, 9, 2, 1, tzinfo=timezone.utc)
        self.assertEqual("BUILDING_CONSECUTIVE_STREAK", self._run("ingest", now))
        before = self._state_path().read_bytes()
        self.assertEqual("IDEMPOTENT_DUPLICATE", self._run("ingest", now))
        self.assertEqual(before, self._state_path().read_bytes())
        envelope_path = next((self.drop_root / START.isoformat()).glob("*.complete.envelope.json"))
        changed = json.loads(envelope_path.read_text(encoding="utf-8"))
        changed["published_at"] = "2026-09-02T00:00:03Z"
        envelope_path.write_bytes(canonical_json_bytes(changed))
        self.assertEqual("INTEGRITY_BLOCKED", self._run("ingest", now))
        self.assertEqual("CONFLICTING_DUPLICATE", self._state()["failure"]["code"])

    def test_calendar_gap_fails_closed(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        self._publish_rejection(START + timedelta(days=1))
        self.assertEqual(
            "INTEGRITY_BLOCKED",
            self._run("ingest", datetime(2026, 9, 3, tzinfo=timezone.utc)),
        )
        self.assertEqual("WRONG_DAY", self._state()["failure"]["code"])


    def test_missing_recorded_drop_blocks_without_rewriting_state(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        self._publish_complete(START)
        now = datetime(2026, 9, 2, 1, tzinfo=timezone.utc)
        self._run("ingest", now)
        before = self._state_path().read_bytes()
        shutil.rmtree(self.drop_root / START.isoformat())
        (self.drop_root / f".{START.isoformat()}.publish-reserved").unlink()
        with self.assertRaisesRegex(IntakeCliBlocked, "RECORDED_DROP_MISSING"):
            self._run("ingest", now)
        self.assertEqual(before, self._state_path().read_bytes())

    def test_empty_prewrite_lock_is_automatically_rolled_back(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        state_path = self._state_path()
        before = state_path.read_bytes()
        lock = state_path.with_name(f".{state_path.name}.lock")
        lock.mkdir()
        self.assertEqual(
            "WAITING_FOR_CALENDAR_DAY",
            self._run("ingest", datetime(2026, 9, 1, tzinfo=timezone.utc)),
        )
        self.assertEqual(before, state_path.read_bytes())
        self.assertFalse(lock.exists())

    def test_hash_bound_temp_commit_is_completed_after_restart(self) -> None:
        self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))
        state_path = self._state_path()
        prior_bytes = state_path.read_bytes()
        blocked = block_intake_state(
            self._state(),
            binding_value=self.binding,
            code="UNKNOWN_EVENT",
            detail="interrupted commit fixture",
        )
        next_bytes = canonical_intake_state_bytes(blocked, self.binding)
        lock = state_path.with_name(f".{state_path.name}.lock")
        temp = state_path.with_name(f".{state_path.name}.tmp")
        lock.mkdir()
        (lock / "intent.json").write_bytes(
            canonical_json_bytes(
                {
                    "schema_version": "V3R1_CANONICAL_STATE_COMMIT_INTENT_V1",
                    "state_name": state_path.name,
                    "prior_sha256": hashlib.sha256(prior_bytes).hexdigest(),
                    "next_sha256": hashlib.sha256(next_bytes).hexdigest(),
                }
            )
        )
        temp.write_bytes(next_bytes)
        self.assertEqual(
            "INTEGRITY_BLOCKED",
            self._run("ingest", datetime(2026, 9, 1, tzinfo=timezone.utc)),
        )
        self.assertEqual(next_bytes, state_path.read_bytes())
        self.assertFalse(lock.exists())
        self.assertFalse(temp.exists())

    def test_release_manifest_and_binding_bytes_are_verified(self) -> None:
        manifest = self.release / ".release" / "source.sha256"
        manifest.write_bytes(manifest.read_bytes() + b"0" * 64 + b"  extra\n")
        with self.assertRaisesRegex(IntakeCliBlocked, "RELEASE_MANIFEST"):
            self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))

        manifest.write_bytes(b"invalid\n")
        self.binding_path.write_bytes(canonical_json_bytes(self.binding) + b"\n")
        with self.assertRaisesRegex(IntakeCliBlocked, "BINDING"):
            self._run("initialize", datetime(2026, 8, 12, tzinfo=timezone.utc))


if __name__ == "__main__":
    unittest.main()
