from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
import hashlib

from research_mcp import queue
from research_pipeline.evidence import register_evidence_source_contract
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.storage import ResearchStore
from research_pipeline.waiting import build_evidence_trigger
from research_source.contract import PRODUCER, SOURCE, TRANSPORT


class DurableQueueContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.requests = self.root / "requests"
        self.source_requests = self.root / "source-requests"
        self.source_drop = self.root / "source-drop"
        self.inbox = self.root / "inbox"
        self.policy = self.root / "policy.json"
        policy_source = Path(queue.__file__).resolve().parents[1] / "research_pipeline" / "policy.v3.json"
        policy_value = json.loads(policy_source.read_text(encoding="utf-8"))
        policy_value["budget"]["lock_stale_seconds"] = 60
        self.policy.write_text(json.dumps(policy_value), encoding="utf-8")
        self.previous = (
            queue.STATE_DIR,
            queue.REQUEST_DIR,
            queue.SOURCE_REQUEST_DIR,
            queue.SOURCE_DROP_DIR,
            queue.INBOX_DIR,
            queue.POLICY_FILE,
        )
        queue.STATE_DIR = self.state
        queue.REQUEST_DIR = self.requests
        queue.SOURCE_REQUEST_DIR = self.source_requests
        queue.SOURCE_DROP_DIR = self.source_drop
        queue.INBOX_DIR = self.inbox
        queue.POLICY_FILE = self.policy

    def tearDown(self) -> None:
        (
            queue.STATE_DIR,
            queue.REQUEST_DIR,
            queue.SOURCE_REQUEST_DIR,
            queue.SOURCE_DROP_DIR,
            queue.INBOX_DIR,
            queue.POLICY_FILE,
        ) = self.previous
        self.temporary.cleanup()

    def _heartbeat_state(self, next_due: str) -> None:
        path = self.state / "heartbeat" / "state.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"schema_version": "1", "next_due": next_due}), encoding="utf-8"
        )

    def _candidate_bundle(self) -> dict[str, object]:
        return {
            "schema_version": "1",
            "trigger_id": "forward-trigger-test",
            "hypothesis": {"hypothesis_id": "candidate-test"},
            "manifest": {"experiment_id": "candidate-test"},
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        }

    def _forward_trigger(self, *, with_source_contract: bool = True) -> None:
        store = ResearchStore(self.state, lock_stale_seconds=60)
        trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "forward-source-queue-test",
                "title": "Forward source queue test",
                "rationale": "Prove the heartbeat companion queues exactly one capture.",
                "source": SOURCE,
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": ["closed_bar_causality"],
                "prohibited_inferences": ["no performance selection"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        store.register_evidence_trigger(trigger)
        if with_source_contract:
            state = store.load_evidence_trigger_state(trigger["trigger_id"])
            register_evidence_source_contract(
                store,
                trigger,
                state,
                {
                    "schema_version": "1",
                    "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                    "trigger_id": trigger["trigger_id"],
                    "trigger_fingerprint": trigger["fingerprint"],
                    "source": SOURCE,
                    "producer": PRODUCER,
                    "transport": TRANSPORT,
                    "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
                    "worker_network_access": "DENY",
                    "worker_database_access": "DENY",
                    "backfill": "DENY",
                    "authorization": RESEARCH_AUTHORIZATION,
                },
                registered_at=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
            )

    def test_not_due_does_not_enqueue(self) -> None:
        self._heartbeat_state("2026-01-02T00:00:00Z")
        result = queue.request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        self.assertEqual(result["status"], "NOT_DUE")
        self.assertFalse((self.requests / "pending.json").exists())

    def test_due_request_is_idempotent_and_visible_before_dispatch(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        now = datetime(2026, 1, 1, tzinfo=timezone.utc)
        first = queue.request_heartbeat(now=now)
        second = queue.request_heartbeat(now=now)
        self.assertEqual(first["status"], "QUEUED")
        self.assertEqual(second["request_id"], first["request_id"])
        self.assertEqual(queue.get_run(first["request_id"])["status"], "QUEUED")

    def test_due_heartbeat_queues_one_deterministic_companion_capture(self) -> None:
        self._heartbeat_state("2026-01-02T00:00:00Z")
        self._forward_trigger()
        now = datetime(2026, 1, 2, 1, tzinfo=timezone.utc)
        result = queue.request_heartbeat(now=now)
        capture = result["evidence_capture"]
        self.assertEqual(result["status"], "QUEUED")
        self.assertEqual(capture["status"], "QUEUED")
        self.assertEqual(capture["day"], "2026-01-01")
        pending = json.loads((self.source_requests / "pending.json").read_text(encoding="utf-8"))
        self.assertEqual(pending["request_id"], capture["request_id"])
        self.assertNotIn("url", pending)
        self.assertNotIn("instrument", pending)

    def test_preclose_unbound_and_expired_days_never_queue_capture(self) -> None:
        for label, current, with_source in (
            ("preclose", datetime(2026, 1, 1, 12, tzinfo=timezone.utc), True),
            ("unbound", datetime(2026, 1, 2, 1, tzinfo=timezone.utc), False),
            ("expired", datetime(2026, 1, 2, 7, tzinfo=timezone.utc), True),
        ):
            with self.subTest(label=label):
                if self.state.exists():
                    shutil.rmtree(self.state)
                self.requests.mkdir(parents=True, exist_ok=True)
                for path in self.requests.glob("*.json"):
                    path.unlink()
                self._heartbeat_state("2025-12-31T00:00:00Z")
                self._forward_trigger(with_source_contract=with_source)
                result = queue.request_heartbeat(now=current)
                self.assertEqual(result["evidence_capture"]["status"], "NOT_CAPTURE_DUE")
                self.assertFalse((self.source_requests / "pending.json").exists())

    def test_candidate_request_is_bounded_idempotent_and_not_clock_gated(self) -> None:
        bundle = self._candidate_bundle()
        first = queue.request_candidate_bundle(bundle)
        repeated = queue.request_candidate_bundle(bundle)
        self.assertEqual(first["status"], "QUEUED")
        self.assertEqual(repeated["request_id"], first["request_id"])
        self.assertEqual(first["operation"], "REGISTER_CANDIDATE_BUNDLE")
        self.assertRegex(first["payload_sha256"], r"^[a-f0-9]{64}$")
        self.assertEqual(queue.get_run(first["request_id"])["status"], "QUEUED")

    def test_different_operation_cannot_replace_active_request(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = queue.request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        candidate = queue.request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(candidate["status"], "QUEUE_BUSY")
        self.assertEqual(candidate["active_request_id"], heartbeat["request_id"])
        self.assertEqual(candidate["active_operation"], "RESEARCH_HEARTBEAT")

    def test_completed_candidate_submission_is_not_enqueued_again(self) -> None:
        first = queue.request_candidate_bundle(self._candidate_bundle())
        pending = self.requests / "pending.json"
        completed = json.loads(pending.read_text(encoding="utf-8"))
        completed.update(
            {
                "status": "COMPLETED",
                "completed_at": "2026-01-01T00:00:00Z",
                "result": {"status": "CANDIDATE_BUNDLE_REGISTERED"},
            }
        )
        run = self.requests / "runs" / f"{first['request_id']}.json"
        run.parent.mkdir(parents=True, exist_ok=True)
        run.write_text(json.dumps(completed), encoding="utf-8")
        pending.unlink()
        repeated = queue.request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(repeated["status"], "ALREADY_COMPLETED")
        self.assertEqual(repeated["request_id"], first["request_id"])
        self.assertFalse(pending.exists())

    def test_candidate_request_rejects_oversized_payload(self) -> None:
        bundle = self._candidate_bundle()
        bundle["hypothesis"]["padding"] = "x" * queue.MAX_CANDIDATE_BUNDLE_BYTES
        with self.assertRaisesRegex(ValueError, "byte limit"):
            queue.request_candidate_bundle(bundle)

    def test_stale_running_request_is_preserved_then_replaced(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        self.requests.mkdir(parents=True)
        stale_id = "a" * 32
        running = self.requests / "running.json"
        running.write_text(
            json.dumps({"request_id": stale_id, "operation": "RESEARCH_HEARTBEAT"}),
            encoding="utf-8",
        )
        stale_epoch = datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp()
        os.utime(running, (stale_epoch, stale_epoch))
        result = queue.request_heartbeat(
            now=datetime(2026, 1, 1, 0, 2, tzinfo=timezone.utc)
        )
        self.assertEqual(result["status"], "QUEUED")
        recovered = queue.get_run(stale_id)
        self.assertEqual(recovered["status"], "STALE_RECOVERED")
        self.assertEqual(recovered["prior_status"], "RUNNING")

    def test_briefing_is_sealed_with_hash_and_artifact_id(self) -> None:
        report = self.state / "reports" / "weekly-learning-brief-2026-01-01.md"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(
            "# Weekly\n\n- Policy: `AUTONOMOUS_TRADING_RESEARCH_V2`\n\nEvidence.\n",
            encoding="utf-8",
        )
        heartbeat = self.state / "heartbeat" / "state.json"
        heartbeat.parent.mkdir(parents=True, exist_ok=True)
        heartbeat.write_text(
            json.dumps(
                {
                    "last_weekly": {
                        "artifact_path": str(report.relative_to(self.state)),
                        "sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
                        "report_date": "2026-01-01",
                    }
                }
            ),
            encoding="utf-8",
        )
        result = queue.research_briefing("weekly")
        artifact = self.state / result["artifact_path"]
        self.assertEqual(result["status"], "REPORT_READY")
        self.assertTrue(artifact.is_file())
        self.assertTrue(result["artifact_id"].startswith("weekly-learning-brief-"))
        self.assertEqual(result["report_policy_id"], "AUTONOMOUS_TRADING_RESEARCH_V2")
        self.assertEqual(result["current_policy_id"], "AUTONOMOUS_TRADING_RESEARCH_V3")
        self.assertEqual(result["policy_alignment"], "SEALED_HISTORICAL_POLICY")


if __name__ == "__main__":
    unittest.main()
