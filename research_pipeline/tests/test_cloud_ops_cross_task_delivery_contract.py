from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
import unittest

from research_pipeline.heartbeat import _advance_coach_delivery


ROOT = Path(__file__).resolve().parents[2]
V7_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v7.json"
V8_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v8.json"
V9_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v9.json"
V10_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v10.json"
V11_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v11.json"
V12_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v12.json"
PROMPT_PATH = ROOT / "research_pipeline" / "prompts" / "daily-research-tick.md"
DOC_PATHS = [
    ROOT / "docs" / "autonomous-research-charter.md",
    ROOT / "docs" / "autonomous-research-acceleration-v1.md",
    ROOT / "docs" / "server-research-worker-v2.md",
    ROOT / "docs" / "deploy-runbook.md",
]

V7_SHA256 = "426f4a9d1f252a610a89e30fcd2a7f890b6bc26f2cb9e7fbf003a08839d5f144"
V8_SHA256 = "7c3df0a2ecd0279ce48f2b58d12f84ce8757270e616ab85e1db173a5df2301d1"
V9_SHA256 = "04d11ad095f64c6dda7d746cf36f26af773f53684765c368d6fe595533ab7d2c"
V10_SHA256 = "90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa"
V11_SHA256 = "9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61"
V12_SHA256 = "98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c"
TARGET_THREAD_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"
SCHEDULE_THREAD_ID = "6a71a167-be58-83ec-aed2-f1736e31dd45"
EXISTING_CLOUD_SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class CloudOpsCrossTaskDeliveryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v7 = _load(V7_PATH)
        cls.v8 = _load(V8_PATH)
        cls.v9 = _load(V9_PATH)
        cls.v10 = _load(V10_PATH)
        cls.v11 = _load(V11_PATH)
        cls.v12 = _load(V12_PATH)
        cls.prompt = PROMPT_PATH.read_text(encoding="utf-8")
        cls.docs = [path.read_text(encoding="utf-8") for path in DOC_PATHS]

    def test_v10_is_frozen_and_preserves_v9_bytes_and_single_clock(self) -> None:
        self.assertEqual(V7_SHA256, _sha256(V7_PATH))
        self.assertEqual(V8_SHA256, _sha256(V8_PATH))
        self.assertEqual(V9_SHA256, _sha256(V9_PATH))
        self.assertEqual(V10_SHA256, _sha256(V10_PATH))
        self.assertEqual("CLOUD_OPS_SCHEDULE_V10", self.v10["contract_id"])
        self.assertEqual("FROZEN", self.v10["document_status"])
        self.assertNotIn("status", self.v10)
        self.assertEqual(self.v9["recurrence"], self.v10["recurrence"])
        self.assertEqual(
            self.v9["canonical_heartbeat_due"],
            self.v10["canonical_heartbeat_due"],
        )
        self.assertEqual(
            self.v9["dispatch_margin"]["scheduled_seconds_after_canonical_due"],
            self.v10["dispatch_margin"]["scheduled_seconds_after_canonical_due"],
        )
        self.assertEqual(self.v9["allowed_mcp_operations"], self.v10["allowed_mcp_operations"])
        self.assertEqual(1, self.v10["schedule_count"])
        self.assertEqual("CLOUD_OPS_SCHEDULE_V9", self.v10["predecessor"]["contract_id"])
        self.assertEqual(V9_SHA256, self.v10["predecessor"]["sha256"])
        self.assertTrue(self.v10["predecessor"]["platform_activation_proven"])

    def test_v10_reuses_the_exact_active_cloud_schedule(self) -> None:
        platform = self.v10["platform_schedule"]
        self.assertEqual(EXISTING_CLOUD_SCHEDULE_ID, platform["existing_active_schedule_id"])
        self.assertEqual("CHATGPT_WORK_CLOUD_SCHEDULE", platform["execution_surface"])
        self.assertTrue(platform["local_computer_may_be_off"])
        self.assertEqual(
            "PAUSE_UPDATE_REACTIVATE_EXISTING_SCHEDULE_IN_PLACE",
            platform["migration"],
        )
        self.assertEqual("DENY", platform["create_second_schedule"])
        self.assertEqual("DENY", self.v10["cutover"]["new_schedule_creation"])
        self.assertFalse(self.v10["cutover"]["activation_authorized_by_repository_preparation"])

    def test_delivery_is_attempted_but_not_a_due_heartbeat_liveness_gate(self) -> None:
        self.assertEqual(
            ["list_threads", "read_thread", "send_message_to_thread"],
            self.v10["allowed_codex_operations"],
        )
        delivery = self.v10["coach_delivery"]
        self.assertEqual("SEALED_COACH_CROSS_TASK_DELIVERY_V6", delivery["contract_id"])
        self.assertEqual(TARGET_THREAD_ID, delivery["target_thread_id"])
        self.assertFalse(delivery["cross_task_operations_required"])
        self.assertEqual(
            "ATTEMPT_WHEN_AVAILABLE_NOT_HEARTBEAT_LIVENESS_GATE",
            delivery["cross_task_operations_mode"],
        )
        self.assertEqual(
            [
                "READ_FRESH_CANONICAL_STATUS_FIRST",
                "REQUIRE_NORMALLY_DUE_HEARTBEAT_BEFORE_CROSS_TASK_WRITE",
                "SNAPSHOT_INITIAL_PENDING_EVENTS",
                "ATTEMPT_INITIAL_PENDING_DELIVERY_WHEN_CAPABILITY_AVAILABLE",
                "FORM_RECEIPTS_ONLY_AFTER_EXACT_READBACK_OTHERWISE_ZERO_FOR_UNVERIFIED_EVENTS",
                "INVOKE_AT_MOST_THE_NORMALLY_DUE_HEARTBEAT_WITH_ZERO_TO_EIGHT_VERIFIED_RECEIPTS",
                "READ_FRESH_CANONICAL_STATUS_AFTER_HEARTBEAT",
                "ATTEMPT_STILL_PENDING_OR_NEW_EVENT_DELIVERY_WHEN_CAPABILITY_AVAILABLE",
                "DEFER_ONLY_POST_HEARTBEAT_NEW_EVENT_RECEIPTS_TO_NEXT_NORMALLY_DUE_HEARTBEAT",
            ],
            delivery["cycle_order"],
        )
        self.assertEqual(
            "ZERO_RECEIPT_FOR_EVENT_KEEP_PENDING_PROCEED_ONLY_IF_ALL_OTHER_HEARTBEAT_GATES_PASS",
            delivery["unverified_delivery_heartbeat_behavior"],
        )
        self.assertEqual(
            "CANONICAL_RESEARCH_ADVANCEMENT_NEVER_IMPLIES_COACH_DELIVERY",
            delivery["heartbeat_outcome_separation"],
        )
        self.assertEqual(
            "READ_TARGET_THREAD_AND_SKIP_SEND_IF_EXACT_FULL_DELIVERY_TOKEN_PRESENT",
            delivery["preflight"],
        )
        self.assertEqual("SEND_EXACT_CANONICAL_DELIVERY_PROMPT", delivery["send"])
        self.assertEqual(
            "READ_TARGET_THREAD_AFTER_SEND_AND_REQUIRE_EXACT_FULL_DELIVERY_TOKEN",
            delivery["verification"],
        )
        self.assertEqual(
            "KEEP_CANONICAL_EVENT_PENDING_AND_REPORT_CROSS_TASK_DELIVERY_PENDING",
            delivery["target_unavailable"],
        )

    def test_receipt_schema_deduplication_and_sla_remain_fail_closed(self) -> None:
        delivery = self.v10["coach_delivery"]
        self.assertEqual(
            [
                "schema_version",
                "delivery_id",
                "delivery_token",
                "target_thread_id",
                "delivery_status",
            ],
            delivery["receipt_schema"]["exact_fields"],
        )
        self.assertEqual(0, delivery["receipt_schema"]["minimum_receipts_per_heartbeat"])
        self.assertEqual(8, delivery["receipt_schema"]["maximum_receipts_per_heartbeat"])
        self.assertEqual(
            [
                "DELIVERED_TO_COACH_TASK_VERIFIED",
                "ALREADY_DELIVERED_TO_COACH_TASK",
            ],
            delivery["receipt_schema"]["verified_receipt_statuses"],
        )
        self.assertTrue(delivery["deduplication"]["exact_token_required"])
        self.assertEqual("IDEMPOTENT", delivery["deduplication"]["repeated_verified_receipt"])
        self.assertEqual(10800, delivery["delivery_proof_sla"]["completion_window_seconds"])
        self.assertEqual("DENY", delivery["delivery_proof_sla"]["queue_or_deadline_reset_on_cutover"])

    def test_prompt_is_v12_hash_bound_and_preserves_delivery_decoupling(self) -> None:
        self.assertEqual(V10_SHA256, _sha256(V10_PATH))
        self.assertEqual(V11_SHA256, _sha256(V11_PATH))
        self.assertEqual(V12_SHA256, _sha256(V12_PATH))
        self.assertIn("CLOUD_OPS_SCHEDULE_V12", self.prompt)
        self.assertIn(V12_SHA256, self.prompt)
        self.assertIn("PREPARED_NOT_ACTIVE_V12", self.prompt)
        self.assertIn("SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2", self.prompt)
        self.assertIn(SCHEDULE_THREAD_ID, self.prompt)
        self.assertIn("exact at-most-eight initial pending events", self.prompt)
        self.assertIn("exact full canonical `delivery_prompt`", self.prompt)
        self.assertIn("zero to eight", self.prompt)
        self.assertIn("Do not block the", self.prompt)
        self.assertIn("same-turn receipt", self.prompt)
        self.assertIn("MISSING_PROOF_PRIOR_ASSISTANT_V12_PROMPT", self.prompt)
        self.assertNotIn("Use only `list_threads`, `read_thread`, and `send_message_to_thread`", self.prompt)

    def test_current_docs_prepare_v12_and_preserve_v10_v11_as_history(self) -> None:
        for content in self.docs:
            self.assertIn("CLOUD_OPS_SCHEDULE_V10", content)
            self.assertIn(V10_SHA256, content)
            self.assertIn("MISSING_PROOF", content)
        for content in (self.docs[0], self.docs[2], self.docs[3]):
            self.assertIn("CLOUD_OPS_SCHEDULE_V9", content)
        for content in (self.docs[2], self.docs[3]):
            self.assertIn(V9_SHA256, content)
        for content in (self.docs[0], self.docs[2], self.docs[3]):
            self.assertIn("CLOUD_OPS_SCHEDULE_V11", content)
            self.assertIn(V11_SHA256, content)
        for content in (self.docs[0], self.docs[1], self.docs[2], self.docs[3]):
            self.assertIn("CLOUD_OPS_SCHEDULE_V12", content)
            self.assertIn(V12_SHA256, content)
        self.assertIn("Historical heartbeat liveness decoupling V10", self.docs[0])
        self.assertIn("Historical heartbeat liveness decoupling V10", self.docs[2])
        self.assertIn("Historical Cloud Ops V10", self.docs[3])
        self.assertIn("sole cloud clock", self.docs[1])
        self.assertIn(EXISTING_CLOUD_SCHEDULE_ID, self.docs[2])
        self.assertIn(EXISTING_CLOUD_SCHEDULE_ID, self.docs[3])

    def test_zero_receipts_preserve_pending_event_and_timing_debt(self) -> None:
        event = {
            "event_type": "MATERIAL_LEARNING",
            "artifact_path": "events/material-learning.json",
            "sha256": "a" * 64,
            "research_status": "CLOSED",
            "material_conclusion": "A sealed learning remains pending.",
            "pnl_drawdown_evidence": None,
            "evidence_diagnostic": None,
            "uncertainty": "Coach delivery remains unverified.",
            "next_action": "KEEP_PENDING_UNTIL_EXACT_READBACK",
            "concept_to_teach": "Heartbeat liveness is not delivery proof.",
            "delivery_queued_at": "2026-08-10T01:00:00Z",
            "delivery_deadline_at": "2026-08-11T04:00:00Z",
        }
        state = {
            "coach_delivery": {
                "schema_version": "1",
                "pending_events": [deepcopy(event)],
                "delivered_receipts": [],
            }
        }

        result = _advance_coach_delivery(
            state,
            receipts=[],
            new_events=[],
            now=datetime(2026, 8, 11, 5, tzinfo=timezone.utc),
        )

        self.assertEqual([event], state["coach_delivery"]["pending_events"])
        self.assertEqual([], state["coach_delivery"]["delivered_receipts"])
        self.assertEqual([], result["acknowledged_delivery_ids"])
        self.assertEqual(1, result["pending_count"])

    def test_v10_required_guards_keep_non_delivery_failures_blocking(self) -> None:
        guards = set(self.v10["required_guards"])
        for guard in (
            "WORKER_RELEASE_READY",
            "CLOUD_DISPATCH_AFTER_CANONICAL_DUE_MARGIN",
            "HEARTBEAT_DUE_AND_QUEUE_IDLE",
            "HASH_VERIFIED_COACH_OUTBOX",
            "COACH_RECEIPT_SCHEMA_AND_PENDING_ID_MATCH",
            "DISTINCT_SEALED_CANDIDATE_OOS",
            "ZERO_RECEIPT_HEARTBEAT_WHEN_DELIVERY_UNAVAILABLE_OR_UNVERIFIABLE",
            "PENDING_EVENT_AND_TIMING_DURABILITY_WITHOUT_VERIFIED_RECEIPT",
        ):
            self.assertIn(guard, guards)
        self.assertEqual("DENY", self.v10["dispatch_margin"]["additional_timer"])
        self.assertEqual("DENY", self.v10["cutover"]["new_schedule_creation"])


if __name__ == "__main__":
    unittest.main()
