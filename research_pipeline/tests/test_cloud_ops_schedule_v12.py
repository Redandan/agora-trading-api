from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
V11_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v11.json"
V12_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v12.json"
PROMPT_PATH = ROOT / "research_pipeline" / "prompts" / "daily-research-tick.md"

V11_SHA256 = "9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61"
V12_SHA256 = "98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c"
SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"
SCHEDULE_THREAD_ID = "6a71a167-be58-83ec-aed2-f1736e31dd45"
LEGACY_COACH_THREAD_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


class CloudOpsScheduleV12Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v11 = _load(V11_PATH)
        cls.v12 = _load(V12_PATH)
        cls.prompt = PROMPT_PATH.read_text(encoding="utf-8")

    def test_v12_is_frozen_and_preserves_v11_clock_and_lifecycle(self) -> None:
        self.assertEqual(V11_SHA256, _sha256(V11_PATH))
        self.assertEqual(V12_SHA256, _sha256(V12_PATH))
        self.assertEqual("CLOUD_OPS_SCHEDULE_V12", self.v12["contract_id"])
        self.assertEqual("FROZEN", self.v12["document_status"])
        self.assertNotIn("status", self.v12)
        self.assertEqual("CLOUD_OPS_SCHEDULE_V11", self.v12["predecessor"]["contract_id"])
        self.assertEqual(V11_SHA256, self.v12["predecessor"]["sha256"])
        self.assertFalse(self.v12["predecessor"]["bytes_modified"])
        self.assertEqual(1, self.v12["schedule_count"])
        self.assertEqual(self.v11["recurrence"], self.v12["recurrence"])
        self.assertEqual(
            self.v11["canonical_heartbeat_due"],
            self.v12["canonical_heartbeat_due"],
        )
        self.assertEqual(
            self.v11["failure_lifecycle"],
            self.v12["failure_lifecycle"],
        )
        self.assertEqual(
            self.v11["allowed_mcp_operations"],
            self.v12["allowed_mcp_operations"],
        )
        self.assertEqual([], self.v12["allowed_codex_operations"])

    def test_same_schedule_chat_replaces_only_the_inaccessible_target(self) -> None:
        platform = self.v12["platform_schedule"]
        delivery = self.v12["coach_delivery"]
        migration = delivery["target_migration"]
        self.assertEqual(SCHEDULE_ID, platform["existing_active_schedule_id"])
        self.assertEqual(SCHEDULE_THREAD_ID, platform["existing_schedule_thread_id"])
        self.assertEqual(SCHEDULE_THREAD_ID, delivery["target_thread_id"])
        self.assertEqual(
            "SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2",
            delivery["contract_id"],
        )
        self.assertEqual("DENY", delivery["cross_task_operations_mode"])
        self.assertFalse(delivery["cross_task_operations_required"])
        self.assertEqual(LEGACY_COACH_THREAD_ID, migration["source_target_thread_id"])
        self.assertEqual(SCHEDULE_THREAD_ID, migration["target_thread_id"])
        self.assertEqual("DELIVERED_RECEIPT_COUNT_ZERO", migration["canonical_precondition"])
        self.assertEqual("DENY", migration["pending_event_identity_change"])
        self.assertEqual("DENY", migration["pending_event_time_change"])
        self.assertEqual("DENY", migration["legacy_prompt_receipt_eligibility"])

    def test_receipt_requires_exact_prior_full_v12_prompt_and_fresh_pending_id(self) -> None:
        delivery = self.v12["coach_delivery"]
        proof = delivery["prior_context_proof"]
        self.assertEqual("assistant", proof["required_role"])
        self.assertEqual(
            "PRIOR_MESSAGE_IN_SAME_SCHEDULE_CHAT",
            proof["required_location"],
        )
        self.assertEqual("EXACT_FULL_CANONICAL_DELIVERY_PROMPT", proof["required_value"])
        self.assertEqual(
            "SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2",
            proof["required_delivery_contract_id"],
        )
        self.assertEqual(SCHEDULE_THREAD_ID, proof["required_target_thread_id"])
        self.assertEqual(
            "IDENTICAL_DELIVERY_ID_STILL_PENDING_IN_FRESH_STATUS",
            proof["required_canonical_state"],
        )
        self.assertTrue(
            {
                "CURRENT_TURN_OUTPUT",
                "TOKEN_WITHOUT_EXACT_FULL_CANONICAL_PROMPT",
                "V11_OR_EARLIER_DELIVERY_PROMPT",
                "USER_QUOTED_PROMPT_OR_TOKEN",
                "SUMMARIZED_CONTEXT",
                "TRUNCATED_CONTEXT",
                "ALTERED_PROMPT_OR_TOKEN",
                "SCHEDULED_INBOX",
                "NOTIFICATION",
                "INFERRED_CONTEXT",
            }.issubset(set(proof["insufficient_sources"]))
        )
        self.assertEqual("DENY", delivery["current_turn_render_receipt_proof"])
        self.assertEqual("DENY", delivery["context_loss"]["receipt"])
        self.assertEqual("KEEP_PENDING", delivery["context_loss"]["canonical_event_state"])

    def test_receipt_schema_debt_and_sla_are_preserved(self) -> None:
        delivery = self.v12["coach_delivery"]
        receipt = delivery["receipt_schema"]
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
        self.assertEqual(0, receipt["minimum_receipts_per_heartbeat"])
        self.assertEqual(8, receipt["maximum_receipts_per_heartbeat"])
        self.assertEqual(
            self.v11["coach_delivery"]["receipt_schema"]["verified_receipt_statuses"],
            receipt["verified_receipt_statuses"],
        )
        self.assertEqual(
            self.v11["coach_delivery"]["delivery_proof_sla"],
            delivery["delivery_proof_sla"],
        )
        debt = delivery["delivery_debt"]
        self.assertTrue(debt["preserve_delivery_id"])
        self.assertTrue(debt["preserve_delivery_queued_at"])
        self.assertTrue(debt["preserve_delivery_deadline_at"])
        self.assertTrue(debt["preserve_existing_breach"])
        self.assertEqual("DENY", debt["false_acknowledgement"])

    def test_zero_overlap_cutover_reuses_the_exact_schedule(self) -> None:
        cutover = self.v12["cutover"]
        self.assertTrue(cutover["explicit_user_lifecycle_authorization_obtained"])
        self.assertEqual("DENY", cutover["new_schedule_creation"])
        self.assertEqual("DENY", cutover["overlapping_active_schedules"])
        self.assertEqual(
            [
                "PAUSE_EXACT_ACTIVE_V11_PLATFORM_SCHEDULE_AND_PROVE_ZERO_ACTIVE",
                "REQUIRE_ZERO_CANONICAL_DELIVERED_RECEIPTS_AND_PRESERVE_PENDING_TIMES",
                "DEPLOY_AND_VERIFY_SERVER_V12_ATTESTATION",
                "UPDATE_EXACT_EXISTING_PAUSED_PLATFORM_SCHEDULE_TO_V12",
                "PROVE_UPDATED_PLATFORM_SCHEDULE_REMAINS_PAUSED_AND_ZERO_ACTIVE",
                "ACTIVATE_UPDATED_PLATFORM_SCHEDULE_AND_PROVE_EXACTLY_ONE_ACTIVE",
            ],
            cutover["required_order"],
        )

    def test_prompt_is_v12_hash_bound_and_contains_the_two_turn_boundary(self) -> None:
        self.assertIn("CLOUD_OPS_SCHEDULE_V12", self.prompt)
        self.assertIn(V12_SHA256, self.prompt)
        self.assertIn("PREPARED_NOT_ACTIVE_V12", self.prompt)
        self.assertIn("SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2", self.prompt)
        self.assertIn(SCHEDULE_THREAD_ID, self.prompt)
        self.assertIn("exact full canonical `delivery_prompt`", self.prompt)
        self.assertIn("prior assistant message", self.prompt)
        self.assertIn("same-turn receipt", self.prompt)
        self.assertIn("V11 or earlier", self.prompt)
        self.assertIn(
            '`schema_version` value must be the JSON string `"1"` (not numeric `1`).',
            self.prompt,
        )
        self.assertNotIn("Use only `list_threads`, `read_thread`, and `send_message_to_thread`", self.prompt)


if __name__ == "__main__":
    unittest.main()
