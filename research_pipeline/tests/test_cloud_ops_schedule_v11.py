from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
V10_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v10.json"
V11_PATH = ROOT / "research_pipeline" / "cloud-ops-schedule-contract.v11.json"
PROMPT_PATH = ROOT / "research_pipeline" / "prompts" / "daily-research-tick.md"

V10_SHA256 = "90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa"
V11_SHA256 = "9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61"
SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


class CloudOpsScheduleV11Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v10 = _load(V10_PATH)
        cls.v11 = _load(V11_PATH)
        cls.prompt = PROMPT_PATH.read_text(encoding="utf-8")

    def test_v11_is_frozen_and_preserves_v10_bytes_and_single_clock(self) -> None:
        self.assertEqual(V10_SHA256, _sha256(V10_PATH))
        self.assertEqual(V11_SHA256, _sha256(V11_PATH))
        self.assertEqual("CLOUD_OPS_SCHEDULE_V11", self.v11["contract_id"])
        self.assertEqual("FROZEN", self.v11["document_status"])
        self.assertNotIn("status", self.v11)
        self.assertEqual("CLOUD_OPS_SCHEDULE_V10", self.v11["predecessor"]["contract_id"])
        self.assertEqual(V10_SHA256, self.v11["predecessor"]["sha256"])
        self.assertFalse(self.v11["predecessor"]["bytes_modified"])
        self.assertTrue(self.v11["predecessor"]["platform_activation_proven"])
        self.assertEqual(1, self.v11["schedule_count"])
        self.assertEqual(self.v10["recurrence"], self.v11["recurrence"])
        self.assertEqual(
            self.v10["canonical_heartbeat_due"],
            self.v11["canonical_heartbeat_due"],
        )
        self.assertEqual(self.v10["dispatch_margin"], self.v11["dispatch_margin"])
        self.assertEqual(
            self.v10["allowed_mcp_operations"],
            self.v11["allowed_mcp_operations"],
        )

    def test_v11_scopes_failure_to_one_occurrence_and_keeps_clock_enabled(self) -> None:
        lifecycle = self.v11["failure_lifecycle"]
        self.assertEqual(
            "FAIL_CLOSED_CURRENT_OCCURRENCE_ONLY",
            lifecycle["failed_occurrence_effect"],
        )
        self.assertEqual("KEEP_ENABLED", lifecycle["schedule_enabled_state_after_failure"])
        self.assertEqual("DENY", lifecycle["automatic_pause_disable_or_delete"])
        self.assertEqual("DENY", lifecycle["schedule_self_mutation"])
        self.assertEqual("PRESERVE", lifecycle["next_normal_occurrence"])
        self.assertEqual("DENY", lifecycle["same_occurrence_heartbeat_retry"])
        self.assertEqual("DENY", lifecycle["manual_catchup"])
        self.assertEqual("DENY", lifecycle["evidence_backfill"])
        self.assertEqual(
            "EXPLICIT_USER_AUTHORIZATION_ONLY",
            lifecycle["schedule_mutation_authority"],
        )
        self.assertIn(
            "FAILED_OCCURRENCE_PRESERVES_ENABLED_SCHEDULE",
            self.v11["required_guards"],
        )
        self.assertIn(
            "AUTOMATIC_SCHEDULE_PAUSE_DISABLE_OR_DELETE",
            self.v11["forbidden_actions"],
        )
        self.assertIn("SCHEDULE_SELF_MUTATION", self.v11["forbidden_actions"])

    def test_v11_reuses_exact_schedule_and_requires_zero_overlap_cutover(self) -> None:
        platform = self.v11["platform_schedule"]
        self.assertEqual(SCHEDULE_ID, platform["existing_active_schedule_id"])
        self.assertEqual("CLOUD_OPS_SCHEDULE_V10", platform["migration_source_contract_id"])
        self.assertEqual("DENY", platform["create_second_schedule"])
        self.assertFalse(self.v11["cutover"]["activation_authorized_by_repository_preparation"])
        self.assertEqual("DENY", self.v11["cutover"]["new_schedule_creation"])
        self.assertEqual(
            [
                "PAUSE_EXACT_ACTIVE_V10_PLATFORM_SCHEDULE_AND_PROVE_ZERO_ACTIVE",
                "DEPLOY_AND_VERIFY_SERVER_V11_ATTESTATION",
                "UPDATE_EXACT_EXISTING_PAUSED_PLATFORM_SCHEDULE_TO_V11",
                "PROVE_UPDATED_PLATFORM_SCHEDULE_REMAINS_PAUSED_AND_ZERO_ACTIVE",
                "ACTIVATE_UPDATED_PLATFORM_SCHEDULE_AND_PROVE_EXACTLY_ONE_ACTIVE",
            ],
            self.v11["cutover"]["required_order"],
        )

    def test_prompt_is_v11_hash_bound_and_denies_schedule_self_mutation(self) -> None:
        self.assertIn("CLOUD_OPS_SCHEDULE_V11", self.prompt)
        self.assertIn(V11_SHA256, self.prompt)
        self.assertIn("PREPARED_NOT_ACTIVE_V11", self.prompt)
        self.assertIn("FAIL_CLOSED_CURRENT_OCCURRENCE_ONLY", self.prompt)
        self.assertIn("schedule_enabled_state_after_failure=KEEP_ENABLED", self.prompt)
        self.assertIn("automatic_pause_disable_or_delete=DENY", self.prompt)
        self.assertIn("schedule_self_mutation=DENY", self.prompt)
        self.assertIn("Do not invoke an automation-management operation", self.prompt)
        self.assertIn("never catch up, backfill, or retry", self.prompt)


if __name__ == "__main__":
    unittest.main()
