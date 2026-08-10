from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
V7_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v7.json"
V8_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v8.json"
PROMPT_PATH = ROOT / "research_pipeline" / "prompts" / "daily-research-tick.md"
DOC_PATHS = [
    ROOT / "docs" / "autonomous-research-charter.md",
    ROOT / "docs" / "autonomous-research-acceleration-v1.md",
    ROOT / "docs" / "server-research-worker-v2.md",
    ROOT / "docs" / "deploy-runbook.md",
]

V7_SHA256 = "426f4a9d1f252a610a89e30fcd2a7f890b6bc26f2cb9e7fbf003a08839d5f144"
V8_SHA256 = "7c3df0a2ecd0279ce48f2b58d12f84ce8757270e616ab85e1db173a5df2301d1"
TARGET_THREAD_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"
EXISTING_CLOUD_SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class CloudOpsCrossTaskDeliveryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v7 = _load(V7_PATH)
        cls.v8 = _load(V8_PATH)
        cls.prompt = PROMPT_PATH.read_text(encoding="utf-8")
        cls.docs = [path.read_text(encoding="utf-8") for path in DOC_PATHS]

    def test_v8_is_frozen_and_preserves_the_v7_single_clock(self) -> None:
        self.assertEqual(V7_SHA256, _sha256(V7_PATH))
        self.assertEqual(V8_SHA256, _sha256(V8_PATH))
        self.assertEqual("CLOUD_OPS_SCHEDULE_V8", self.v8["contract_id"])
        self.assertEqual("FROZEN", self.v8["document_status"])
        self.assertNotIn("status", self.v8)
        self.assertEqual(self.v7["recurrence"], self.v8["recurrence"])
        self.assertEqual(
            self.v7["canonical_heartbeat_due"],
            self.v8["canonical_heartbeat_due"],
        )
        self.assertEqual(self.v7["dispatch_margin"], self.v8["dispatch_margin"])
        self.assertEqual(self.v7["allowed_mcp_operations"], self.v8["allowed_mcp_operations"])
        self.assertEqual(1, self.v8["schedule_count"])

    def test_v8_reuses_the_exact_paused_cloud_schedule(self) -> None:
        platform = self.v8["platform_schedule"]
        self.assertEqual(EXISTING_CLOUD_SCHEDULE_ID, platform["existing_paused_schedule_id"])
        self.assertEqual("CHATGPT_WORK_CLOUD_SCHEDULE", platform["execution_surface"])
        self.assertTrue(platform["local_computer_may_be_off"])
        self.assertEqual("UPDATE_EXISTING_PAUSED_SCHEDULE_IN_PLACE", platform["migration"])
        self.assertEqual("DENY", platform["create_second_schedule"])
        self.assertEqual("DENY", self.v8["cutover"]["new_schedule_creation"])
        self.assertFalse(self.v8["cutover"]["activation_authorized_by_repository_preparation"])

    def test_cross_task_delivery_is_exact_read_send_readback_only(self) -> None:
        self.assertEqual(
            ["list_threads", "read_thread", "send_message_to_thread"],
            self.v8["allowed_codex_operations"],
        )
        delivery = self.v8["coach_delivery"]
        self.assertEqual("SEALED_COACH_CROSS_TASK_DELIVERY_V4", delivery["contract_id"])
        self.assertEqual(TARGET_THREAD_ID, delivery["target_thread_id"])
        self.assertTrue(delivery["cross_task_operations_required"])
        self.assertEqual(
            [
                "READ_FRESH_CANONICAL_STATUS_FIRST",
                "RESOLVE_AND_PREFLIGHT_READ_EXACT_COACH_TASK",
                "FORM_RECEIPTS_ONLY_FROM_EXACT_PREFLIGHT_TOKEN_AND_PENDING_ID",
                "INVOKE_AT_MOST_THE_NORMALLY_DUE_HEARTBEAT_WITH_PREFLIGHT_RECEIPTS",
                "READ_FRESH_CANONICAL_STATUS_AFTER_HEARTBEAT",
                "DELIVER_STILL_PENDING_EVENTS_AND_REQUIRE_POST_SEND_READBACK",
                "DEFER_POST_SEND_RECEIPTS_TO_NEXT_NORMALLY_DUE_HEARTBEAT",
            ],
            delivery["cycle_order"],
        )
        self.assertEqual(
            "READ_TARGET_THREAD_AND_SKIP_IF_EXACT_FULL_DELIVERY_TOKEN_PRESENT",
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
        delivery = self.v8["coach_delivery"]
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

    def test_prompt_is_v8_hash_bound_and_uses_only_exact_cross_task_delivery(self) -> None:
        self.assertIn("CLOUD_OPS_SCHEDULE_V8", self.prompt)
        self.assertIn(V8_SHA256, self.prompt)
        self.assertIn("PREPARED_NOT_ACTIVE_V8", self.prompt)
        self.assertIn("SEALED_COACH_CROSS_TASK_DELIVERY_V4", self.prompt)
        self.assertIn(TARGET_THREAD_ID, self.prompt)
        self.assertIn("Use only `list_threads`, `read_thread`, and `send_message_to_thread`", self.prompt)
        self.assertIn("read the exact Coach task again", self.prompt)
        self.assertIn("post-send read contains the exact full token", self.prompt)
        self.assertIn("next normally due", self.prompt)
        self.assertIn("CROSS_TASK_DELIVERY_PENDING", self.prompt)
        self.assertNotIn("SEALED_COACH_SAME_CHAT_DELIVERY_V1", self.prompt)
        self.assertNotIn("current assistant turn is never receipt proof", self.prompt)

    def test_current_docs_distinguish_prepared_v8_from_live_v7(self) -> None:
        for content in self.docs:
            self.assertIn("CLOUD_OPS_SCHEDULE_V8", content)
            self.assertIn(V8_SHA256, content)
            self.assertIn("MISSING_PROOF", content)
        for content in self.docs[1:]:
            self.assertIn("CROSS_TASK_DELIVERY_PENDING", content)
        self.assertIn("deployed canonical contract remains V7", self.docs[0])
        self.assertIn("zero active", self.docs[1])
        self.assertIn(EXISTING_CLOUD_SCHEDULE_ID, self.docs[2])
        self.assertIn(EXISTING_CLOUD_SCHEDULE_ID, self.docs[3])


if __name__ == "__main__":
    unittest.main()
