from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

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
        self.app = self.root / "app"
        self.policy = self.root / "policy.json"
        self.app.mkdir()
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
            queue.APP_DIR,
        )
        queue.STATE_DIR = self.state
        queue.REQUEST_DIR = self.requests
        queue.SOURCE_REQUEST_DIR = self.source_requests
        queue.SOURCE_DROP_DIR = self.source_drop
        queue.INBOX_DIR = self.inbox
        queue.POLICY_FILE = self.policy
        queue.APP_DIR = self.app
        self.ops_schedule_contract_sha256 = self._ops_schedule_contract()
        self._release_provenance()

    def tearDown(self) -> None:
        (
            queue.STATE_DIR,
            queue.REQUEST_DIR,
            queue.SOURCE_REQUEST_DIR,
            queue.SOURCE_DROP_DIR,
            queue.INBOX_DIR,
            queue.POLICY_FILE,
            queue.APP_DIR,
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

    def _candidate_run(
        self,
        bundle: dict[str, object],
        *,
        request_id: str,
        status: str,
    ) -> tuple[dict[str, object], str]:
        payload, payload_sha256 = queue._validated_candidate_payload(bundle)
        value: dict[str, object] = {
            "schema_version": "1",
            "request_id": request_id,
            "requested_at": "2026-01-01T00:00:00Z",
            "source": "CODEX_CLOUD_OPS",
            "operation": "REGISTER_CANDIDATE_BUNDLE",
            "payload": payload,
            "payload_sha256": payload_sha256,
            "status": status,
            "completed_at": "2026-01-01T00:01:00Z",
            "exit_code": 2 if status == "FAILED" else 0,
        }
        path = self.requests / "runs" / f"{request_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return value, payload_sha256

    def _partial_candidate_registration(
        self,
        bundle: dict[str, object],
        *,
        candidate_frozen_at: str | None = "2026-01-01T00:00:30Z",
    ) -> None:
        trigger_id = str(bundle["trigger_id"])
        hypothesis = dict(bundle["hypothesis"])
        manifest = dict(bundle["manifest"])
        hypothesis_id = str(hypothesis["hypothesis_id"])
        experiment_id = str(manifest["experiment_id"])
        trigger_dir = self.state / "evidence-triggers" / trigger_id
        trigger_dir.mkdir(parents=True, exist_ok=True)
        (trigger_dir / "state.json").write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "trigger_id": trigger_id,
                    "status": "READY_FOR_HYPOTHESIS",
                }
            ),
            encoding="utf-8",
        )
        hypothesis_dir = self.state / "hypotheses"
        hypothesis_dir.mkdir(parents=True, exist_ok=True)
        (hypothesis_dir / f"{hypothesis_id}.json").write_text(
            json.dumps({**hypothesis, "status": "REGISTERED"}),
            encoding="utf-8",
        )
        experiment_dir = self.state / "experiments" / experiment_id
        experiment_dir.mkdir(parents=True, exist_ok=True)
        (experiment_dir / "manifest.json").write_text(
            json.dumps(manifest),
            encoding="utf-8",
        )
        experiment_state: dict[str, object] = {
            "schema_version": "1",
            "experiment_id": experiment_id,
            "stage": "PREREGISTERED",
            "run_count": 0,
            "artifacts": {},
            "hypothesis_id": hypothesis_id,
        }
        if candidate_frozen_at is not None:
            experiment_state["candidate_frozen_at"] = candidate_frozen_at
        (experiment_dir / "state.json").write_text(
            json.dumps(experiment_state),
            encoding="utf-8",
        )

    def _release_provenance(self, *, dirty: bool = False) -> dict[str, object]:
        release_dir = self.app / ".release"
        release_dir.mkdir(parents=True, exist_ok=True)
        (self.app / "fixture.py").write_text("fixture source\n", encoding="utf-8")
        manifest_lines = []
        for path in sorted(
            (
                item
                for item in self.app.rglob("*")
                if item.is_file() and ".release" not in item.relative_to(self.app).parts
            ),
            key=lambda item: item.relative_to(self.app).as_posix(),
        ):
            relative = path.relative_to(self.app).as_posix()
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            manifest_lines.append(f"{digest}  {relative}")
        manifest = ("\n".join(manifest_lines) + "\n").encode("utf-8")
        (release_dir / "source.sha256").write_bytes(manifest)
        value: dict[str, object] = {
            "schema_version": "1",
            "release_id": "20260805T120000Z",
            "source_git_commit": "a" * 40,
            "source_git_branch": "codex/autonomous-research-control-plane-v3",
            "source_git_dirty": dirty,
            "source_manifest_sha256": hashlib.sha256(manifest).hexdigest(),
            "installed_at": "2026-08-05T12:00:00Z",
        }
        (release_dir / "provenance.json").write_text(
            json.dumps(value), encoding="utf-8"
        )
        return value

    def _ops_schedule_contract(self) -> str:
        source = (
            Path(queue.__file__).resolve().parents[1]
            / queue.OPS_SCHEDULE_CONTRACT_RELATIVE_PATH
        )
        target = self.app / queue.OPS_SCHEDULE_CONTRACT_RELATIVE_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        return hashlib.sha256(target.read_bytes()).hexdigest()

    def _request_heartbeat(
        self,
        *,
        now: datetime,
        coach_delivery_receipts: list[dict[str, object]] | None = None,
    ) -> dict[str, object]:
        return queue.request_heartbeat(
            self.ops_schedule_contract_sha256,
            coach_delivery_receipts,
            now=now,
        )

    def _request_candidate_bundle(
        self,
        bundle: dict[str, object],
    ) -> dict[str, object]:
        return queue.request_candidate_bundle(
            bundle,
            self.ops_schedule_contract_sha256,
        )

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

    def _r1_forward_trigger(self) -> None:
        examples = (
            Path(queue.__file__).resolve().parents[1]
            / "research_pipeline"
            / "examples"
        )
        trigger = build_evidence_trigger(
            json.loads(
                (
                    examples
                    / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.trigger.json"
                )
                .read_text(encoding="utf-8")
            )
        )
        source_contract = json.loads(
            (
                examples
                / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.source-contract.json"
            ).read_text(encoding="utf-8")
        )
        store = ResearchStore(self.state, lock_stale_seconds=60)
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            source_contract,
            registered_at=datetime(2026, 8, 4, 12, tzinfo=timezone.utc),
        )

    def test_not_due_does_not_enqueue(self) -> None:
        self._heartbeat_state("2026-01-02T00:00:00Z")
        result = self._request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        self.assertEqual(result["status"], "NOT_DUE")
        self.assertFalse((self.requests / "pending.json").exists())

    def test_due_request_is_idempotent_and_visible_before_dispatch(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        now = datetime(2026, 1, 1, tzinfo=timezone.utc)
        first = self._request_heartbeat(now=now)
        second = self._request_heartbeat(now=now)
        self.assertEqual(first["status"], "QUEUED")
        self.assertEqual(second["request_id"], first["request_id"])
        self.assertEqual(queue.get_run(first["request_id"])["status"], "QUEUED")

    def test_due_heartbeat_seals_verified_coach_receipts_in_hashed_payload(self) -> None:
        artifact = self.state / "events" / "material-learning.json"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text('{"sealed":true}\n', encoding="utf-8")
        delivery_id = hashlib.sha256(artifact.read_bytes()).hexdigest()
        heartbeat = self.state / "heartbeat" / "state.json"
        heartbeat.parent.mkdir(parents=True, exist_ok=True)
        heartbeat.write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "next_due": "2026-01-01T00:00:00Z",
                    "coach_delivery": {
                        "schema_version": "1",
                        "pending_events": [
                            {
                                "event_type": "MATERIAL_LEARNING",
                                "artifact_path": str(artifact.relative_to(self.state)),
                                "sha256": delivery_id,
                                "research_status": "CLOSED",
                                "material_conclusion": "A sealed learning is ready.",
                                "pnl_drawdown_evidence": None,
                                "evidence_diagnostic": None,
                                "uncertainty": "No prospective evidence exists.",
                                "next_action": "PRESERVE_THE_CLOSED_BRANCH",
                                "concept_to_teach": "A closed branch stays closed.",
                            }
                        ],
                        "delivered_receipts": [],
                    },
                }
            ),
            encoding="utf-8",
        )
        receipt = {
            "schema_version": "1",
            "delivery_id": delivery_id,
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{delivery_id}",
            "target_thread_id": queue.COACH_TASK_ID,
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
        }
        now = datetime(2026, 1, 1, tzinfo=timezone.utc)
        first = self._request_heartbeat(now=now, coach_delivery_receipts=[receipt])
        second = self._request_heartbeat(now=now, coach_delivery_receipts=[receipt])
        self.assertEqual(first["status"], "QUEUED")
        self.assertEqual(second["request_id"], first["request_id"])
        self.assertEqual(first["payload"]["coach_delivery_receipts"], [receipt])
        self.assertRegex(first["payload_sha256"], r"^[a-f0-9]{64}$")

    def test_invalid_canonical_coach_outbox_blocks_queue_mutation(self) -> None:
        heartbeat = self.state / "heartbeat" / "state.json"
        heartbeat.parent.mkdir(parents=True, exist_ok=True)
        heartbeat.write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "next_due": "2026-01-01T00:00:00Z",
                    "coach_delivery": {
                        "schema_version": "1",
                        "pending_events": [{"sha256": "d" * 64}],
                        "delivered_receipts": [],
                    },
                }
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "canonical coach outbox is invalid"):
            self._request_heartbeat(now=datetime(2026, 1, 1, tzinfo=timezone.utc))

        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

    def test_receipt_cannot_ack_event_outside_verified_outbox_batch(self) -> None:
        events = []
        for index in range(queue.MAX_COACH_OUTBOX_BATCH + 1):
            artifact = self.state / "events" / f"material-learning-{index}.json"
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text(
                json.dumps({"sealed": True, "index": index}) + "\n",
                encoding="utf-8",
            )
            events.append(
                {
                    "event_type": "MATERIAL_LEARNING",
                    "artifact_path": str(artifact.relative_to(self.state)),
                    "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                    "research_status": "CLOSED",
                    "material_conclusion": f"Sealed learning {index} is ready.",
                    "pnl_drawdown_evidence": None,
                    "evidence_diagnostic": None,
                    "uncertainty": "No prospective evidence exists.",
                    "next_action": "PRESERVE_THE_CLOSED_BRANCH",
                    "concept_to_teach": "A closed branch stays closed.",
                }
            )
        heartbeat = self.state / "heartbeat" / "state.json"
        heartbeat.parent.mkdir(parents=True, exist_ok=True)
        heartbeat.write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "next_due": "2026-01-01T00:00:00Z",
                    "coach_delivery": {
                        "schema_version": "1",
                        "pending_events": events,
                        "delivered_receipts": [],
                    },
                }
            ),
            encoding="utf-8",
        )
        delivery_id = events[-1]["sha256"]
        receipt = {
            "schema_version": "1",
            "delivery_id": delivery_id,
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{delivery_id}",
            "target_thread_id": queue.COACH_TASK_ID,
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
        }

        with self.assertRaisesRegex(ValueError, "hash-verified canonical outbox batch"):
            self._request_heartbeat(
                now=datetime(2026, 1, 1, tzinfo=timezone.utc),
                coach_delivery_receipts=[receipt],
            )

        self.assertFalse((self.requests / "pending.json").exists())

    def test_coach_receipt_must_match_canonical_pending_or_delivered_state(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        delivery_id = "e" * 64
        receipt = {
            "schema_version": "1",
            "delivery_id": delivery_id,
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{delivery_id}",
            "target_thread_id": queue.COACH_TASK_ID,
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
        }
        with self.assertRaisesRegex(ValueError, "does not match canonical state"):
            self._request_heartbeat(
                now=datetime(2026, 1, 1, tzinfo=timezone.utc),
                coach_delivery_receipts=[receipt],
            )
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

    def test_due_heartbeat_queues_one_deterministic_companion_capture(self) -> None:
        self._heartbeat_state("2026-01-02T00:00:00Z")
        self._forward_trigger()
        now = datetime(2026, 1, 2, 1, tzinfo=timezone.utc)
        result = self._request_heartbeat(now=now)
        capture = result["evidence_capture"]
        self.assertEqual(result["status"], "QUEUED")
        self.assertEqual(capture["status"], "QUEUED")
        self.assertEqual(capture["day"], "2026-01-01")
        pending = json.loads((self.source_requests / "pending.json").read_text(encoding="utf-8"))
        self.assertEqual(pending["request_id"], capture["request_id"])
        self.assertNotIn("url", pending)
        self.assertNotIn("instrument", pending)

    def test_r1_daily_cycles_wait_then_queue_first_complete_day_once(self) -> None:
        self._r1_forward_trigger()
        self._heartbeat_state("2026-08-06T01:00:00Z")

        preclose = self._request_heartbeat(
            now=datetime(2026, 8, 6, 1, tzinfo=timezone.utc)
        )
        self.assertEqual(preclose["status"], "QUEUED")
        self.assertEqual(preclose["evidence_capture"]["status"], "NOT_CAPTURE_DUE")
        self.assertFalse((self.source_requests / "pending.json").exists())

        (self.requests / "pending.json").unlink()
        self._heartbeat_state("2026-08-07T01:00:00Z")
        first_due = self._request_heartbeat(
            now=datetime(2026, 8, 7, 1, tzinfo=timezone.utc)
        )
        due_epoch = datetime(2026, 8, 7, 1, tzinfo=timezone.utc).timestamp()
        os.utime(self.requests / "pending.json", (due_epoch, due_epoch))
        source_pending = (self.source_requests / "pending.json").read_bytes()
        repeated = self._request_heartbeat(
            now=datetime(2026, 8, 7, 1, tzinfo=timezone.utc)
        )

        self.assertEqual(first_due["status"], "QUEUED")
        self.assertEqual(first_due["evidence_capture"]["status"], "QUEUED")
        self.assertEqual(first_due["evidence_capture"]["day"], "2026-08-06")
        self.assertEqual(
            first_due["evidence_capture"]["capture_deadline"],
            "2026-08-07T06:00:00Z",
        )
        self.assertEqual(repeated["request_id"], first_due["request_id"])
        self.assertEqual(
            (self.source_requests / "pending.json").read_bytes(), source_pending
        )

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
                result = self._request_heartbeat(now=current)
                self.assertEqual(result["evidence_capture"]["status"], "NOT_CAPTURE_DUE")
                self.assertFalse((self.source_requests / "pending.json").exists())

    def test_candidate_request_is_bounded_idempotent_and_not_clock_gated(self) -> None:
        bundle = self._candidate_bundle()
        first = self._request_candidate_bundle(bundle)
        repeated = self._request_candidate_bundle(bundle)
        self.assertEqual(first["status"], "QUEUED")
        self.assertEqual(repeated["request_id"], first["request_id"])
        self.assertEqual(first["operation"], "REGISTER_CANDIDATE_BUNDLE")
        self.assertRegex(first["payload_sha256"], r"^[a-f0-9]{64}$")
        self.assertEqual(queue.get_run(first["request_id"])["status"], "QUEUED")

    def test_different_operation_cannot_replace_active_request(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = self._request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        candidate = self._request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(candidate["status"], "QUEUE_BUSY")
        self.assertEqual(candidate["active_request_id"], heartbeat["request_id"])
        self.assertEqual(candidate["active_operation"], "RESEARCH_HEARTBEAT")

    def test_completed_candidate_submission_is_not_enqueued_again(self) -> None:
        first = self._request_candidate_bundle(self._candidate_bundle())
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
        repeated = self._request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(repeated["status"], "ALREADY_COMPLETED")
        self.assertEqual(repeated["request_id"], first["request_id"])
        self.assertFalse(pending.exists())

    def test_status_exposes_one_hash_verified_partial_candidate_for_exact_replay(self) -> None:
        bundle = self._candidate_bundle()
        self._partial_candidate_registration(bundle)
        failed, payload_sha256 = self._candidate_run(
            bundle,
            request_id="c" * 32,
            status="FAILED",
        )

        recovery = queue._candidate_registration_recovery()

        self.assertEqual(recovery["status"], "EXACT_REPLAY_REQUIRED")
        self.assertEqual(
            recovery["required_action"],
            "SUBMIT_EXACT_CANDIDATE_BUNDLE_ONCE",
        )
        self.assertEqual(recovery["request_id"], failed["request_id"])
        self.assertEqual(recovery["payload_sha256"], payload_sha256)
        self.assertEqual(recovery["bundle"], failed["payload"])
        self.assertEqual(recovery["retry_limit"], 1)
        self.assertTrue(
            recovery["partial_registration"]["experiment_preregistered"]
        )
        self.assertEqual(
            recovery["partial_registration"]["candidate_frozen_at"],
            "2026-01-01T00:00:30Z",
        )
        replayed = self._request_candidate_bundle(recovery["bundle"])
        self.assertEqual(replayed["status"], "QUEUED")
        self.assertEqual(replayed["payload_sha256"], payload_sha256)
        self.assertEqual(replayed["payload"], failed["payload"])
        repeated = self._request_candidate_bundle(recovery["bundle"])
        self.assertEqual(repeated["status"], "QUEUED")
        self.assertEqual(repeated["request_id"], replayed["request_id"])

    def test_candidate_write_gate_rejects_new_payload_while_exact_replay_is_required(self) -> None:
        bundle = self._candidate_bundle()
        self._partial_candidate_registration(bundle)
        _failed, payload_sha256 = self._candidate_run(
            bundle,
            request_id="c" * 32,
            status="FAILED",
        )
        different = self._candidate_bundle()
        different["hypothesis"]["hypothesis_id"] = "different-candidate"
        different["manifest"]["experiment_id"] = "different-candidate"

        rejected = self._request_candidate_bundle(different)

        self.assertEqual(rejected["status"], "EXACT_CANDIDATE_REPLAY_REQUIRED")
        self.assertEqual(rejected["required_payload_sha256"], payload_sha256)
        self.assertEqual(rejected["failed_request_id"], "c" * 32)
        self.assertEqual(rejected["failed_attempt_count"], 1)
        self.assertEqual(rejected["retry_limit"], 1)
        self.assertFalse((self.requests / "pending.json").exists())

    def test_status_blocks_a_second_failed_exact_candidate_replay(self) -> None:
        bundle = self._candidate_bundle()
        self._partial_candidate_registration(bundle)
        self._candidate_run(bundle, request_id="c" * 32, status="FAILED")
        self._candidate_run(bundle, request_id="d" * 32, status="FAILED")

        recovery = queue._candidate_registration_recovery()

        self.assertEqual(recovery["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(
            recovery["reason"],
            "CANDIDATE_EXACT_REPLAY_ALREADY_FAILED",
        )
        self.assertEqual(recovery["failed_attempt_count"], 2)
        self.assertNotIn("bundle", recovery)
        rejected = self._request_candidate_bundle(bundle)
        self.assertEqual(
            rejected["status"],
            "CANDIDATE_REGISTRATION_INTEGRITY_BLOCKED",
        )
        self.assertEqual(
            rejected["reason"],
            "CANDIDATE_EXACT_REPLAY_ALREADY_FAILED",
        )
        self.assertEqual(
            rejected["candidate_registration_recovery"]["failed_attempt_count"],
            2,
        )
        self.assertFalse((self.requests / "pending.json").exists())

    def test_status_blocks_failed_candidate_payload_drift_from_partial_state(self) -> None:
        bundle = self._candidate_bundle()
        self._partial_candidate_registration(bundle)
        changed = json.loads(json.dumps(bundle))
        changed["manifest"]["title"] = "changed after partial registration"
        self._candidate_run(changed, request_id="c" * 32, status="FAILED")

        recovery = queue._candidate_registration_recovery()

        self.assertEqual(recovery["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(
            recovery["reason"],
            "CANDIDATE_PARTIAL_STATE_MISMATCH",
        )
        self.assertEqual(
            recovery["details"][0]["reason"],
            "PARTIAL_EXPERIMENT_MISMATCH",
        )
        self.assertNotIn("bundle", recovery)

    def test_completed_exact_candidate_clears_failed_recovery_surface(self) -> None:
        bundle = self._candidate_bundle()
        self._partial_candidate_registration(bundle)
        self._candidate_run(bundle, request_id="c" * 32, status="FAILED")
        self._candidate_run(bundle, request_id="d" * 32, status="COMPLETED")

        recovery = queue._candidate_registration_recovery()

        self.assertEqual(recovery, {"status": "IDLE"})

    def test_candidate_request_rejects_oversized_payload(self) -> None:
        bundle = self._candidate_bundle()
        bundle["hypothesis"]["padding"] = "x" * queue.MAX_CANDIDATE_BUNDLE_BYTES
        with self.assertRaisesRegex(ValueError, "byte limit"):
            self._request_candidate_bundle(bundle)

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
        result = self._request_heartbeat(
            now=datetime(2026, 1, 1, 0, 2, tzinfo=timezone.utc)
        )
        self.assertEqual(result["status"], "QUEUED")
        recovered = queue.get_run(stale_id)
        self.assertEqual(recovered["status"], "STALE_RECOVERED")
        self.assertEqual(recovered["prior_status"], "RUNNING")

    def test_status_exposes_verified_clean_worker_release(self) -> None:
        expected = self._release_provenance()
        pipeline_result = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout='{"research_status":"WAITING_FOR_EVIDENCE"}',
            stderr="",
        )
        with patch.object(queue, "_pipeline", return_value=pipeline_result):
            result = queue.research_status()
        release = result["worker_release"]
        contract = result["ops_schedule_contract"]
        self.assertEqual(release["status"], "READY")
        self.assertEqual(release["source_git_commit"], expected["source_git_commit"])
        self.assertEqual(
            release["source_manifest_sha256"], expected["source_manifest_sha256"]
        )
        self.assertTrue(release["source_tree_verified"])
        self.assertEqual(release["source_file_count"], 2)
        self.assertEqual(result["coach_outbox"]["status"], "IDLE")
        self.assertEqual(
            result["candidate_registration_recovery"],
            {"status": "IDLE"},
        )
        self.assertEqual(
            result["coach_outbox"]["delivery_proof_sla"],
            {
                "basis": "CANONICAL_VERIFIED_RECEIPT_ACKNOWLEDGEMENT",
                "pass_count": 0,
                "breach_count": 0,
                "missing_proof_count": 0,
                "latest": None,
            },
        )
        self.assertEqual(result["evidence_capture_health"]["status"], "IDLE")
        self.assertEqual(result["evidence_ingest_queue"]["status"], "IDLE")
        self.assertEqual(contract["status"], "READY")
        self.assertEqual(contract["contract_id"], "CLOUD_OPS_SCHEDULE_V4")
        self.assertEqual(contract["schedule_count"], 1)
        self.assertEqual(contract["sha256"], self.ops_schedule_contract_sha256)
        self.assertEqual(
            contract["coach_delivery"]["contract_id"],
            "SEALED_COACH_THREAD_DELIVERY_V3",
        )
        self.assertEqual(
            contract["coach_delivery"]["delivery_proof_sla"],
            {
                "basis": "CANONICAL_VERIFIED_RECEIPT_ACKNOWLEDGEMENT",
                "queued_at_source": "CANONICAL_OUTBOX_INSERT_TIMESTAMP",
                "deadline_rule": "NEXT_NORMAL_CLOUD_CYCLE_PLUS_COMPLETION_WINDOW",
                "completion_window_seconds": 10800,
                "pending_status": "PENDING_WITHIN_SLA",
                "breach_status": "BREACH_PENDING_DELIVERY_PROOF",
                "terminal_statuses": ["PASS", "BREACH"],
                "legacy_missing_proof_statuses": [
                    "MISSING_PROOF_LEGACY_EVENT",
                    "MISSING_PROOF_LEGACY_RECEIPT",
                ],
            },
        )

    def test_status_exposes_correlated_sealed_capture_health(self) -> None:
        request_id = "b" * 32
        request = {
            "request_id": request_id,
            "day": "2026-08-06",
            "capture_deadline": "2026-08-07T06:00:00Z",
        }
        source = {
            "request_id": request_id,
            "request": request,
            "status": "COMPLETED",
            "completed_at": "2026-08-07T01:00:10Z",
            "request_sha256": "c" * 64,
            "bundle_sha256": "d" * 64,
        }
        ingest = {
            "request_id": request_id,
            "status": "COMPLETED",
            "completed_at": "2026-08-07T01:00:20Z",
            "request_sha256": "c" * 64,
            "bundle_sha256": "d" * 64,
            "result": {"artifact_path": "evidence/day.json", "sha256": "e" * 64},
        }
        self.source_requests.mkdir(parents=True)
        self.source_drop.mkdir(parents=True)
        (self.source_requests / "latest.json").write_text(
            json.dumps(source), encoding="utf-8"
        )
        (self.source_drop / "latest.json").write_text(
            json.dumps(ingest), encoding="utf-8"
        )
        pipeline_result = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout='{"research_status":"WAITING_FOR_EVIDENCE"}',
            stderr="",
        )
        with patch.object(queue, "_pipeline", return_value=pipeline_result):
            result = queue.research_status()
        health = result["evidence_capture_health"]
        self.assertEqual(health["status"], "SEALED")
        self.assertFalse(health["integrity_blocking"])
        self.assertEqual(health["request_id"], request_id)

    def test_status_fails_closed_on_terminal_source_or_hash_mismatch(self) -> None:
        request_id = "b" * 32
        self.source_requests.mkdir(parents=True)
        failed = {
            "request_id": request_id,
            "status": "FAILED",
            "error_type": "SourceIntegrityError",
            "detail": "malformed source response",
        }
        (self.source_requests / "latest.json").write_text(
            json.dumps(failed), encoding="utf-8"
        )
        pipeline_result = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout='{"research_status":"WAITING_FOR_EVIDENCE"}',
            stderr="",
        )
        with patch.object(queue, "_pipeline", return_value=pipeline_result):
            failed_status = queue.research_status()
        self.assertEqual(
            failed_status["evidence_capture_health"]["status"],
            "SOURCE_CAPTURE_FAILED",
        )
        self.assertTrue(
            failed_status["evidence_capture_health"]["integrity_blocking"]
        )

        source = {
            "request_id": request_id,
            "request": {"request_id": request_id},
            "status": "COMPLETED",
            "completed_at": "2026-08-07T01:00:10Z",
            "request_sha256": "c" * 64,
            "bundle_sha256": "d" * 64,
        }
        ingest = {
            "request_id": request_id,
            "status": "COMPLETED",
            "request_sha256": "c" * 64,
            "bundle_sha256": "f" * 64,
            "result": {},
        }
        self.source_drop.mkdir(parents=True)
        (self.source_requests / "latest.json").write_text(
            json.dumps(source), encoding="utf-8"
        )
        (self.source_drop / "latest.json").write_text(
            json.dumps(ingest), encoding="utf-8"
        )
        with patch.object(queue, "_pipeline", return_value=pipeline_result):
            mismatched = queue.research_status()
        self.assertEqual(
            mismatched["evidence_capture_health"]["status"], "INTEGRITY_BLOCKED"
        )
        self.assertIn(
            "bundle_sha256", mismatched["evidence_capture_health"]["reason"]
        )

    def test_capture_health_distinguishes_retry_ingest_and_dispatch_stall(self) -> None:
        current = datetime(2026, 8, 7, 1, 2, tzinfo=timezone.utc)
        request_id = "b" * 32
        request = {
            "request_id": request_id,
            "day": "2026-08-06",
            "capture_deadline": "2026-08-07T06:00:00Z",
        }
        retrying = queue._capture_health_summary(
            now=current,
            source_active={"status": "QUEUED", **request},
            ingest_pending=None,
            source_latest={
                "request_id": request_id,
                "status": "RETRYING",
                "error_type": "TemporarySourceError",
                "detail": "temporary endpoint failure",
            },
            ingest_latest=None,
        )
        self.assertEqual(retrying["status"], "SOURCE_CAPTURE_RETRYING")
        self.assertFalse(retrying["integrity_blocking"])

        completed_source = {
            "request_id": request_id,
            "request": request,
            "status": "COMPLETED",
            "completed_at": "2026-08-07T01:00:00Z",
            "request_sha256": "c" * 64,
            "bundle_sha256": "d" * 64,
        }
        ingest_retrying = queue._capture_health_summary(
            now=current,
            source_active=None,
            ingest_pending={"request_id": request_id},
            source_latest=completed_source,
            ingest_latest={
                "request_id": request_id,
                "status": "RETRYING",
                "error_type": "TemporarySourceError",
                "detail": "pipeline is locked",
            },
        )
        self.assertEqual(
            ingest_retrying["status"], "EVIDENCE_INGEST_RETRYING"
        )
        self.assertFalse(ingest_retrying["integrity_blocking"])

        stalled = queue._capture_health_summary(
            now=current,
            source_active=None,
            ingest_pending=None,
            source_latest=completed_source,
            ingest_latest=None,
        )
        self.assertEqual(stalled["status"], "EVIDENCE_INGEST_DISPATCH_STALLED")
        self.assertTrue(stalled["integrity_blocking"])
        self.assertEqual(stalled["dispatch_age_seconds"], 120)

        invalid = queue._capture_health_summary(
            now=current,
            source_active=None,
            ingest_pending=None,
            source_latest=None,
            ingest_latest=None,
            source_pending_invalid=True,
        )
        self.assertEqual(invalid["status"], "INTEGRITY_BLOCKED")
        self.assertTrue(invalid["integrity_blocking"])

    def test_capture_health_blocks_active_source_or_ingest_after_deadline(self) -> None:
        request_id = "b" * 32
        request = {
            "request_id": request_id,
            "day": "2026-08-06",
            "capture_deadline": "2026-08-07T06:00:00Z",
        }
        retry_record = {
            "request_id": request_id,
            "status": "RETRYING",
            "error_type": "TemporarySourceError",
            "detail": "temporary endpoint failure",
        }

        at_deadline = queue._capture_health_summary(
            now=datetime(2026, 8, 7, 6, tzinfo=timezone.utc),
            source_active={"status": "QUEUED", **request},
            ingest_pending=None,
            source_latest=retry_record,
            ingest_latest=None,
        )
        self.assertEqual(at_deadline["status"], "SOURCE_CAPTURE_RETRYING")
        self.assertFalse(at_deadline["integrity_blocking"])
        self.assertEqual(at_deadline["seconds_to_deadline"], 0)

        source_expired = queue._capture_health_summary(
            now=datetime(2026, 8, 7, 6, 0, 1, tzinfo=timezone.utc),
            source_active={"status": "QUEUED", **request},
            ingest_pending=None,
            source_latest=retry_record,
            ingest_latest=None,
        )
        self.assertEqual(source_expired["status"], "INTEGRITY_BLOCKED")
        self.assertTrue(source_expired["integrity_blocking"])
        self.assertEqual(source_expired["request_id"], request_id)
        self.assertEqual(source_expired["seconds_to_deadline"], -1)
        self.assertIn("deadline", source_expired["reason"])

        fractional_expiry = queue._capture_health_summary(
            now=datetime(2026, 8, 7, 6, 0, 0, 500_000, tzinfo=timezone.utc),
            source_active={"status": "QUEUED", **request},
            ingest_pending=None,
            source_latest=retry_record,
            ingest_latest=None,
        )
        self.assertEqual(fractional_expiry["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(fractional_expiry["seconds_to_deadline"], -1)

        completed_source = {
            "request_id": request_id,
            "request": request,
            "status": "COMPLETED",
            "completed_at": "2026-08-07T05:59:00Z",
            "request_sha256": "c" * 64,
            "bundle_sha256": "d" * 64,
        }
        ingest_expired = queue._capture_health_summary(
            now=datetime(2026, 8, 7, 6, 0, 1, tzinfo=timezone.utc),
            source_active=None,
            ingest_pending={"request_id": request_id},
            source_latest=completed_source,
            ingest_latest={
                "request_id": request_id,
                "status": "RETRYING",
                "error_type": "TemporarySourceError",
                "detail": "pipeline is locked",
            },
        )
        self.assertEqual(ingest_expired["status"], "INTEGRITY_BLOCKED")
        self.assertTrue(ingest_expired["integrity_blocking"])
        self.assertEqual(ingest_expired["request_id"], request_id)
        self.assertEqual(ingest_expired["seconds_to_deadline"], -1)
        self.assertIn("deadline", ingest_expired["reason"])

        missing_deadline = queue._capture_health_summary(
            now=datetime(2026, 8, 7, 1, tzinfo=timezone.utc),
            source_active={"status": "QUEUED", "request_id": request_id},
            ingest_pending=None,
            source_latest=None,
            ingest_latest=None,
        )
        self.assertEqual(missing_deadline["status"], "INTEGRITY_BLOCKED")
        self.assertTrue(missing_deadline["integrity_blocking"])
        self.assertIn("missing or invalid", missing_deadline["reason"])

    def test_status_exposes_hash_verified_canonical_coach_outbox(self) -> None:
        artifact = self.state / "events" / "material-learning.json"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text('{"sealed":true}\n', encoding="utf-8")
        event = {
            "event_type": "MATERIAL_LEARNING",
            "artifact_path": str(artifact.relative_to(self.state)),
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "research_status": "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS",
            "material_conclusion": "A sealed evidence review is ready.",
            "pnl_drawdown_evidence": None,
            "evidence_diagnostic": {"observation_count": 90},
            "uncertainty": "The discovery window is not clean OOS.",
            "next_action": "PROPOSE_AT_MOST_ONE_CAUSAL_HYPOTHESIS",
            "concept_to_teach": "Discovery evidence and OOS are different roles.",
        }
        heartbeat_state = self.state / "heartbeat" / "state.json"
        heartbeat_state.parent.mkdir(parents=True, exist_ok=True)
        heartbeat_state.write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "coach_delivery": {
                        "schema_version": "1",
                        "pending_events": [event],
                        "delivered_receipts": [],
                    },
                }
            ),
            encoding="utf-8",
        )
        pipeline_result = subprocess.CompletedProcess(
            args=[], returncode=0, stdout='{"research_status":"READY"}', stderr=""
        )
        with patch.object(queue, "_pipeline", return_value=pipeline_result):
            result = queue.research_status()
        outbox = result["coach_outbox"]
        self.assertEqual(outbox["status"], "EVENTS_PENDING_EXTERNAL_DELIVERY")
        self.assertEqual(outbox["event_count"], 1)
        self.assertEqual(
            outbox["delivery_contract"]["contract_id"],
            "SEALED_COACH_THREAD_DELIVERY_V3",
        )
        self.assertEqual(
            outbox["delivery_contract"]["target_thread_id"],
            queue.COACH_TASK_ID,
        )
        self.assertEqual(outbox["events"][0]["delivery_id"], event["sha256"])
        self.assertEqual(
            outbox["events"][0]["delivery_token"],
            f"SEALED_RESEARCH_DELIVERY:{event['sha256']}",
        )
        self.assertIn(event["sha256"], outbox["events"][0]["delivery_prompt"])
        self.assertIn(
            "STATE_SYNC_ONLY_NO_RESEARCH_WRITE_OR_TRADING_ACTION",
            outbox["events"][0]["delivery_prompt"],
        )
        envelope = json.loads(outbox["events"][0]["delivery_prompt"].split("\n", 1)[1])
        self.assertEqual(envelope["delivery_id"], event["sha256"])
        self.assertEqual(envelope["target_thread_id"], queue.COACH_TASK_ID)
        self.assertEqual(envelope["event"], event)
        self.assertTrue(envelope["canonical_reverification_required"])
        self.assertTrue(outbox["events"][0]["artifact_verified"])
        self.assertEqual(
            outbox["events"][0]["delivery_proof_sla"]["status"],
            "MISSING_PROOF_LEGACY_EVENT",
        )

        timed_event = {
            **event,
            "delivery_queued_at": "2025-12-31T12:00:00Z",
            "delivery_deadline_at": "2026-01-01T04:00:00Z",
        }
        pending = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [timed_event],
                "delivered_receipts": [],
            },
            now=datetime(2025, 12, 31, 13, tzinfo=timezone.utc),
        )
        self.assertEqual(
            pending["events"][0]["delivery_proof_sla"],
            {"status": "PENDING_WITHIN_SLA", "seconds_to_deadline": 54000},
        )
        at_deadline = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [timed_event],
                "delivered_receipts": [],
            },
            now=datetime(2026, 1, 1, 4, tzinfo=timezone.utc),
        )
        self.assertEqual(
            at_deadline["events"][0]["delivery_proof_sla"],
            {"status": "PENDING_WITHIN_SLA", "seconds_to_deadline": 0},
        )
        breached = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [timed_event],
                "delivered_receipts": [],
            },
            now=datetime(2026, 1, 1, 4, 0, 1, tzinfo=timezone.utc),
        )
        self.assertEqual(
            breached["events"][0]["delivery_proof_sla"],
            {
                "status": "BREACH_PENDING_DELIVERY_PROOF",
                "seconds_to_deadline": -1,
            },
        )

        verified_receipt = {
            "schema_version": "1",
            "delivery_id": event["sha256"],
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{event['sha256']}",
            "target_thread_id": queue.COACH_TASK_ID,
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
            "acknowledged_at": "2025-12-31T14:00:00Z",
            "delivery_queued_at": "2025-12-31T12:00:00Z",
            "delivery_deadline_at": "2026-01-01T04:00:00Z",
            "delivery_proof_lead_time_seconds": 7200,
            "delivery_proof_sla": "PASS",
        }
        delivered = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [],
                "delivered_receipts": [verified_receipt],
            },
            now=datetime(2025, 12, 31, 14, tzinfo=timezone.utc),
        )
        self.assertEqual(delivered["status"], "IDLE")
        self.assertEqual(delivered["delivery_proof_sla"]["pass_count"], 1)
        self.assertEqual(
            delivered["delivery_proof_sla"]["latest"]["status"], "PASS"
        )

        oversized = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [{**event, "material_conclusion": "x" * 70000}],
                "delivered_receipts": [],
            }
        )
        self.assertEqual(oversized["status"], "COACH_OUTBOX_INVALID")
        self.assertIn("bounded size", oversized["reason"])

        artifact.write_text("tampered\n", encoding="utf-8")
        invalid = queue._coach_outbox(
            {
                "schema_version": "1",
                "pending_events": [event],
                "delivered_receipts": [],
            }
        )
        self.assertEqual(invalid["status"], "COACH_OUTBOX_INVALID")
        self.assertIn("does not match", invalid["reason"])

    def test_dirty_worker_release_fails_closed_but_remains_attributable(self) -> None:
        self._release_provenance(dirty=True)
        release = queue._worker_release_summary()
        self.assertEqual(release["status"], "DIRTY_SOURCE")
        self.assertTrue(release["source_git_dirty"])
        self.assertRegex(release["source_git_commit"], r"^[0-9a-f]{40}$")

    def test_missing_or_tampered_release_provenance_fails_closed(self) -> None:
        shutil.rmtree(self.app / ".release")
        missing = queue._worker_release_summary()
        self.assertEqual(missing["status"], "RELEASE_PROVENANCE_READ_FAILED")
        self.assertNotIn(str(self.app), json.dumps(missing))

        self._release_provenance()
        (self.app / ".release" / "source.sha256").write_text(
            "tampered", encoding="utf-8"
        )
        tampered = queue._worker_release_summary()
        self.assertEqual(tampered["status"], "RELEASE_PROVENANCE_INVALID")
        self.assertIn("does not match", tampered["reason"])

    def test_modified_or_extra_installed_source_fails_release_integrity(self) -> None:
        (self.app / "fixture.py").write_text("modified\n", encoding="utf-8")
        modified = queue._worker_release_summary()
        self.assertEqual(modified["status"], "RELEASE_SOURCE_INTEGRITY_FAILED")
        self.assertIn("fixture.py", modified["reason"])
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = self._request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        candidate = self._request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(heartbeat["status"], "WORKER_RELEASE_INTEGRITY_BLOCKED")
        self.assertEqual(candidate["status"], "WORKER_RELEASE_INTEGRITY_BLOCKED")
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

        self._release_provenance()
        (self.app / "unexpected.py").write_text("unexpected\n", encoding="utf-8")
        extra = queue._worker_release_summary()
        self.assertEqual(extra["status"], "RELEASE_SOURCE_INTEGRITY_FAILED")
        self.assertIn("unexpected.py", extra["reason"])

        (self.app / "unexpected.py").unlink()
        self._release_provenance()
        (self.app / "fixture.py").unlink()
        missing = queue._worker_release_summary()
        self.assertEqual(missing["status"], "RELEASE_SOURCE_INTEGRITY_FAILED")
        self.assertIn("fixture.py", missing["reason"])

    def test_installed_source_manifest_rejects_unsafe_or_duplicate_paths(self) -> None:
        unsafe = queue._installed_source_tree_summary(
            f"{'a' * 64}  ../outside.py\n".encode("utf-8")
        )
        self.assertEqual(unsafe["status"], "RELEASE_SOURCE_INTEGRITY_FAILED")
        self.assertIn("unsafe", unsafe["reason"])

        fixture_hash = hashlib.sha256(
            (self.app / "fixture.py").read_bytes()
        ).hexdigest()
        duplicate = queue._installed_source_tree_summary(
            (
                f"{fixture_hash}  fixture.py\n"
                f"{fixture_hash}  fixture.py\n"
            ).encode("utf-8")
        )
        self.assertEqual(duplicate["status"], "RELEASE_SOURCE_INTEGRITY_FAILED")
        self.assertIn("duplicated", duplicate["reason"])

    def test_invalid_release_blocks_both_write_operations_without_queue_mutation(self) -> None:
        shutil.rmtree(self.app / ".release")
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = self._request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        candidate = self._request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(heartbeat["status"], "WORKER_RELEASE_INTEGRITY_BLOCKED")
        self.assertEqual(candidate["status"], "WORKER_RELEASE_INTEGRITY_BLOCKED")
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

    def test_invalid_ops_contract_blocks_both_writes_without_queue_mutation(self) -> None:
        contract = self.app / queue.OPS_SCHEDULE_CONTRACT_RELATIVE_PATH
        contract.unlink()
        self._release_provenance()
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = self._request_heartbeat(
            now=datetime(2026, 1, 1, tzinfo=timezone.utc)
        )
        candidate = self._request_candidate_bundle(self._candidate_bundle())
        self.assertEqual(
            heartbeat["status"], "OPS_SCHEDULE_CONTRACT_INTEGRITY_BLOCKED"
        )
        self.assertEqual(
            candidate["status"], "OPS_SCHEDULE_CONTRACT_INTEGRITY_BLOCKED"
        )
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

        self._ops_schedule_contract()
        value = json.loads(contract.read_text(encoding="utf-8"))
        value["schedule_count"] = 2
        contract.write_text(json.dumps(value), encoding="utf-8")
        self._release_provenance()
        invalid = queue._ops_schedule_contract_summary()
        self.assertEqual(invalid["status"], "OPS_SCHEDULE_CONTRACT_INVALID")

    def test_wrong_ops_contract_attestation_blocks_both_writes(self) -> None:
        self._heartbeat_state("2026-01-01T00:00:00Z")
        heartbeat = queue.request_heartbeat(
            "0" * 64,
            now=datetime(2026, 1, 1, tzinfo=timezone.utc),
        )
        candidate = queue.request_candidate_bundle(
            self._candidate_bundle(),
            "0" * 64,
        )
        self.assertEqual(
            heartbeat["status"], "OPS_SCHEDULE_CONTRACT_ATTESTATION_BLOCKED"
        )
        self.assertEqual(
            candidate["status"], "OPS_SCHEDULE_CONTRACT_ATTESTATION_BLOCKED"
        )
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

    def test_historical_v3_attestation_cannot_opt_out_of_delivery_sla(self) -> None:
        historical = (
            Path(queue.__file__).resolve().parents[1]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v3.json"
        ).read_bytes()
        historical_sha256 = hashlib.sha256(historical).hexdigest()
        self._heartbeat_state("2026-01-01T00:00:00Z")

        heartbeat = queue.request_heartbeat(
            historical_sha256,
            now=datetime(2026, 1, 1, tzinfo=timezone.utc),
        )
        candidate = queue.request_candidate_bundle(
            self._candidate_bundle(),
            historical_sha256,
        )

        self.assertEqual(
            heartbeat["status"], "OPS_SCHEDULE_CONTRACT_ATTESTATION_BLOCKED"
        )
        self.assertEqual(
            candidate["status"], "OPS_SCHEDULE_CONTRACT_ATTESTATION_BLOCKED"
        )
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertFalse((self.source_requests / "pending.json").exists())

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
