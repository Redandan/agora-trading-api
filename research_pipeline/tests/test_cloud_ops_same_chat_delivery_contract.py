from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
V6_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v6.json"
V7_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v7.json"
PROMPT_PATH = ROOT / "research_pipeline" / "prompts" / "daily-research-tick.md"
DOC_PATHS = [
    ROOT / "docs" / "autonomous-research-charter.md",
    ROOT / "docs" / "autonomous-research-acceleration-v1.md",
    ROOT / "docs" / "server-research-worker-v2.md",
]

V6_SHA256 = "d58468b509ffce9f26af2d631a67c97d97f23c8aee369a1c7a3dafbee7959c85"
V7_SHA256 = "426f4a9d1f252a610a89e30fcd2a7f890b6bc26f2cb9e7fbf003a08839d5f144"
TARGET_THREAD_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"
EXACT_FILES = [
    "research_pipeline/cloud-ops-schedule-contract.v7.json",
    "research_pipeline/tests/test_cloud_ops_same_chat_delivery_contract.py",
    "research_pipeline/prompts/daily-research-tick.md",
    "docs/autonomous-research-charter.md",
    "docs/autonomous-research-acceleration-v1.md",
    "docs/server-research-worker-v2.md",
]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class CloudOpsSameChatDeliveryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v6 = _load(V6_PATH)
        cls.v7 = _load(V7_PATH)
        cls.prompt = PROMPT_PATH.read_text(encoding="utf-8")
        cls.docs = [path.read_text(encoding="utf-8") for path in DOC_PATHS]

    def test_v7_document_is_frozen_and_preserves_v6_clock(self) -> None:
        self.assertEqual(V6_SHA256, _sha256(V6_PATH))
        self.assertEqual(V7_SHA256, _sha256(V7_PATH))
        self.assertEqual("CLOUD_OPS_SCHEDULE_V7", self.v7["contract_id"])
        self.assertEqual("FROZEN", self.v7["document_status"])
        self.assertNotIn("status", self.v7)
        self.assertEqual("CLOUD_OPS_ONLY", self.v7["timer_authority"].removeprefix("CODEX_"))
        self.assertEqual("SERVER_CANONICAL", self.v7["state_authority"])
        self.assertEqual(1, self.v7["schedule_count"])
        self.assertEqual(self.v6["recurrence"], self.v7["recurrence"])
        self.assertEqual(
            self.v6["canonical_heartbeat_due"], self.v7["canonical_heartbeat_due"]
        )
        self.assertEqual(self.v6["dispatch_margin"], self.v7["dispatch_margin"])
        self.assertEqual(self.v6["allowed_mcp_operations"], self.v7["allowed_mcp_operations"])
        self.assertEqual([], self.v7["allowed_codex_operations"])
        self.assertFalse(self.v7["cutover"]["activation_authorized_by_repository_preparation"])

    def test_same_chat_delivery_is_bound_without_cross_task_operations(self) -> None:
        delivery = self.v7["coach_delivery"]
        self.assertEqual("SEALED_COACH_SAME_CHAT_DELIVERY_V1", delivery["contract_id"])
        self.assertEqual(TARGET_THREAD_ID, delivery["target_thread_id"])
        self.assertEqual("RETURN_TO_SAME_CHAT_WITH_EXISTING_CONTEXT", delivery["destination"])
        self.assertFalse(delivery["cross_task_operations_required"])
        self.assertEqual("SEALED_ARTIFACT_SHA256", delivery["delivery_id_source"])
        self.assertEqual("SEALED_RESEARCH_DELIVERY:", delivery["dedupe_token_prefix"])

    def test_two_turn_causal_order_forbids_same_turn_ack(self) -> None:
        delivery = self.v7["coach_delivery"]
        self.assertEqual(
            [
                "SCAN_PRIOR_ASSISTANT_MESSAGES_FOR_EXACT_FULL_DELIVERY_TOKENS",
                "READ_FRESH_CANONICAL_STATUS",
                "FORM_RECEIPTS_ONLY_FOR_PRIOR_ASSISTANT_TOKENS_WITH_IDENTICAL_PENDING_IDS",
                "INVOKE_AT_MOST_THE_NORMALLY_DUE_HEARTBEAT",
                "READ_FRESH_CANONICAL_STATUS_AFTER_HEARTBEAT",
                "RENDER_EACH_STILL_PENDING_CANONICAL_PROMPT_WITHOUT_PRIOR_ASSISTANT_TOKEN_ONCE",
            ],
            delivery["turn_order"],
        )
        self.assertEqual("DENY", delivery["current_turn_render_receipt_proof"])
        self.assertEqual("assistant", delivery["prior_context_proof"]["required_role"])
        self.assertEqual(
            "IDENTICAL_DELIVERY_ID_STILL_PENDING_IN_FRESH_STATUS",
            delivery["prior_context_proof"]["required_canonical_state"],
        )

    def test_context_loss_and_insufficient_context_fail_closed(self) -> None:
        proof = self.v7["coach_delivery"]["prior_context_proof"]
        self.assertEqual(
            {
                "CURRENT_TURN_OUTPUT",
                "USER_QUOTED_TOKEN",
                "SUMMARIZED_CONTEXT",
                "TRUNCATED_CONTEXT",
                "ALTERED_TOKEN",
                "SCHEDULED_INBOX",
                "NOTIFICATION",
                "INFERRED_CONTEXT",
            },
            set(proof["insufficient_sources"]),
        )
        loss = self.v7["coach_delivery"]["context_loss"]
        self.assertEqual("DENY", loss["receipt"])
        self.assertEqual("KEEP_PENDING", loss["canonical_event_state"])
        self.assertEqual(
            "AT_MOST_ONCE_PER_TURN_USING_EXACT_CANONICAL_PROMPT", loss["rerender"]
        )
        self.assertEqual("DENY", loss["deadline_reset"])

    def test_receipt_deduplication_and_sla_are_preserved(self) -> None:
        receipt = self.v7["coach_delivery"]["receipt_schema"]
        self.assertEqual(
            [
                "schema_version",
                "delivery_id",
                "delivery_token",
                "target_thread_id",
                "delivery_status",
            ],
            receipt["exact_fields"],
        )
        self.assertEqual(8, receipt["maximum_receipts_per_heartbeat"])
        self.assertEqual(
            self.v6["coach_delivery"]["verified_receipt_statuses"],
            receipt["verified_receipt_statuses"],
        )
        self.assertEqual(
            [
                "DELIVERED_TO_COACH_TASK_VERIFIED",
                "ALREADY_DELIVERED_TO_COACH_TASK",
            ],
            receipt["verified_receipt_statuses"],
        )
        sla = self.v7["coach_delivery"]["delivery_proof_sla"]
        self.assertEqual(self.v6["coach_delivery"]["delivery_proof_sla"], {
            key: value for key, value in sla.items() if key != "queue_or_deadline_reset_on_cutover"
        })
        self.assertEqual("DENY", sla["queue_or_deadline_reset_on_cutover"])
        self.assertEqual("IDEMPOTENT", self.v7["coach_delivery"]["deduplication"]["repeated_verified_receipt"])

    def test_non_delivery_v6_guards_and_forbidden_actions_remain(self) -> None:
        replaced_guards = {
            "COACH_THREAD_READ_BEFORE_SEND",
            "COACH_DELIVERY_ID_DEDUPLICATION",
            "COACH_THREAD_POST_SEND_READBACK",
            "CROSS_TASK_DELIVERY_PENDING_IF_TARGET_UNAVAILABLE",
        }
        self.assertTrue(
            (set(self.v6["required_guards"]) - replaced_guards).issubset(
                set(self.v7["required_guards"])
            )
        )
        replaced_actions = {
            "UNVERIFIED_COACH_DELIVERY_CLAIM",
            "ACK_WITHOUT_THREAD_READBACK",
            "INFERRED_COACH_DELIVERY_TIMING",
        }
        self.assertTrue(
            (set(self.v6["forbidden_actions"]) - replaced_actions).issubset(
                set(self.v7["forbidden_actions"])
            )
        )

    def test_prompt_is_hash_bound_and_stops_on_current_v6(self) -> None:
        self.assertIn("CLOUD_OPS_SCHEDULE_V7", self.prompt)
        self.assertIn(V7_SHA256, self.prompt)
        self.assertNotIn(
            "6ff8979811d8b797d5cace5055b807ab7c0473f07ec11f77a1ac4c37c489d6a8",
            self.prompt,
        )
        self.assertIn("`document_status=FROZEN`", self.prompt)
        self.assertIn("`PREPARED_NOT_ACTIVE_V7`", self.prompt)
        self.assertIn("stop before every V7\nwrite call", self.prompt)
        self.assertIn("current assistant turn is never receipt proof", self.prompt)
        self.assertNotIn("Use `list_threads`", self.prompt)
        self.assertNotIn("call `send_message_to_thread`", self.prompt)

    def test_docs_freeze_document_and_external_rollout_state(self) -> None:
        for content in self.docs:
            self.assertIn("CURRENT_ACTIVE_V6", content)
            self.assertIn("PREPARED_NOT_ACTIVE_V7", content)
            self.assertIn("`document_status=FROZEN`", content)
            self.assertIn(V7_SHA256, content)
            self.assertIn("MISSING_PROOF", content)
            self.assertNotIn("V7 is active", content)
        self.assertEqual(6, len(EXACT_FILES))
        self.assertEqual(6, len(set(EXACT_FILES)))


if __name__ == "__main__":
    unittest.main()
