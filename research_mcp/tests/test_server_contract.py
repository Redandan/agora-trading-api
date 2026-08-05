from __future__ import annotations

import asyncio
import hashlib
from pathlib import Path
import unittest

from research_mcp.server import mcp


class ResearchMcpServerContractTest(unittest.TestCase):
    def test_exact_five_tool_schema_includes_candidate_submission(self) -> None:
        tools = asyncio.run(mcp.list_tools())
        by_name = {tool.name: tool for tool in tools}
        self.assertEqual(
            set(by_name),
            {
                "get_research_status",
                "request_research_heartbeat",
                "submit_research_candidate_bundle",
                "get_research_run",
                "get_research_briefing",
            },
        )

        candidate = by_name["submit_research_candidate_bundle"]
        heartbeat = by_name["request_research_heartbeat"]
        self.assertEqual(
            heartbeat.inputSchema.get("required"),
            ["ops_schedule_contract_sha256"],
        )
        self.assertEqual(
            heartbeat.inputSchema["properties"]["ops_schedule_contract_sha256"]["type"],
            "string",
        )
        self.assertEqual(
            heartbeat.inputSchema["properties"]["coach_delivery_receipts"]["anyOf"][0][
                "type"
            ],
            "array",
        )
        self.assertEqual(
            candidate.inputSchema.get("required"),
            ["bundle", "ops_schedule_contract_sha256"],
        )
        self.assertEqual(
            candidate.inputSchema["properties"]["bundle"],
            {
                "additionalProperties": True,
                "title": "Bundle",
                "type": "object",
            },
        )
        self.assertFalse(candidate.annotations.readOnlyHint)
        self.assertFalse(candidate.annotations.destructiveHint)
        self.assertTrue(candidate.annotations.idempotentHint)

    def test_cloud_prompt_is_mcp_first_and_never_writes_local_research_state(self) -> None:
        prompt = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "prompts"
            / "daily-research-tick.md"
        ).read_text(encoding="utf-8")
        contract = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v4.json"
        ).read_bytes()
        contract_sha256 = hashlib.sha256(contract).hexdigest()
        historical_v3 = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v3.json"
        ).read_bytes()
        historical_v3_sha256 = hashlib.sha256(historical_v3).hexdigest()
        self.assertEqual(
            historical_v3_sha256,
            "2d66149bee9e6b44e139fe471bd32dc10a8afa13e7c47d12b2e165f2a3456e8b",
        )

        self.assertIn("get_research_status", prompt)
        self.assertIn("submit_research_candidate_bundle", prompt)
        self.assertIn("evidence_diagnostic", prompt)
        self.assertIn("worker_release.status=READY", prompt)
        self.assertIn("ops_schedule_contract.status=READY", prompt)
        self.assertIn("CLOUD_OPS_SCHEDULE_V4", prompt)
        self.assertIn(contract_sha256, prompt)
        self.assertNotIn("CLOUD_OPS_SCHEDULE_V3", prompt)
        self.assertNotIn(historical_v3_sha256, prompt)
        self.assertIn("ops_schedule_contract_sha256", prompt)
        self.assertIn("evidence_capture_health", prompt)
        self.assertIn("CAPTURE_OBSERVATION_PENDING", prompt)
        self.assertIn("EVIDENCE_INGEST_DISPATCH_STALLED", prompt)
        self.assertIn("INTEGRITY_BLOCKED", prompt)
        self.assertIn("source_git_dirty=false", prompt)
        self.assertIn("candidate_registration_sla", prompt)
        self.assertIn("candidate_registration_recovery", prompt)
        self.assertIn("EXACT_REPLAY_REQUIRED", prompt)
        self.assertIn("copy the canonical", prompt)
        self.assertIn("EXACT_CANDIDATE_REPLAY_REQUIRED", prompt)
        self.assertIn("CANDIDATE_REGISTRATION_INTEGRITY_BLOCKED", prompt)
        self.assertIn("CANDIDATE_TRIGGER_NOT_READY", prompt)
        self.assertIn("CANDIDATE_TRIGGER_INTEGRITY_BLOCKED", prompt)
        self.assertIn("BREACH_PENDING_REGISTRATION", prompt)
        self.assertIn("forward_candidate_readiness.status=READY", prompt)
        self.assertIn("candidate_context.status=READY", prompt)
        self.assertIn("adapter_config_template", prompt)
        self.assertIn("NO_SUPPORTED_MECHANISM", prompt)
        self.assertIn("CANDIDATE_OOS", prompt)
        self.assertIn("NO_ELIGIBLE_FORWARD_CANDIDATE_ADAPTER", prompt)
        self.assertIn("closed historical", prompt)
        self.assertIn("delivery_status=CROSS_TASK_DELIVERY_PENDING", prompt)
        self.assertIn("list_threads", prompt)
        self.assertIn("read_thread", prompt)
        self.assertIn("send_message_to_thread", prompt)
        self.assertIn("SEALED_COACH_THREAD_DELIVERY_V3", prompt)
        self.assertIn("delivery_proof_sla.completion_window_seconds=10800", prompt)
        self.assertIn("DELIVERED_TO_COACH_TASK_VERIFIED", prompt)
        self.assertIn("QUEUED_TO_COACH_TASK_UNVERIFIED", prompt)
        self.assertIn("ALREADY_DELIVERED_TO_COACH_TASK", prompt)
        self.assertIn("queue-to-verified-receipt proof", prompt)
        self.assertIn("BREACH_PENDING_DELIVERY_PROOF", prompt)
        self.assertIn("MISSING_PROOF_LEGACY_EVENT", prompt)
        self.assertIn("coach_outbox", prompt)
        self.assertIn("019fca63-4f8f-71e3-9d88-297bca468eb9", prompt)
        self.assertNotIn("read_research_worker_inbox_ssh", prompt)
        self.assertNotIn("Seal the evidence under `.research-state`", prompt)

    def test_dispatch_recovers_an_inflight_request_without_an_extra_timer(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        service = (
            repository
            / "scripts"
            / "research-worker"
            / "agora-research-dispatch.service"
        ).read_text(encoding="utf-8")
        path_unit = (
            repository
            / "scripts"
            / "research-worker"
            / "agora-research-dispatch.path"
        ).read_text(encoding="utf-8")
        runner = (
            repository / "scripts" / "research-worker" / "run-request.sh"
        ).read_text(encoding="utf-8")

        self.assertIn("Restart=on-abnormal", service)
        self.assertIn("StartLimitBurst=3", service)
        self.assertIn(
            "PathExists=/var/lib/agora-research/requests/running.json",
            path_unit,
        )
        self.assertIn('if [ -f "$RUNNING" ]; then', runner)
        self.assertIn('value["resume_count"]', runner)
        self.assertNotIn(".timer", path_unit)

    def test_server_verifier_runs_forward_candidate_and_corpus_contracts(self) -> None:
        verifier = (
            Path(__file__).resolve().parents[2]
            / "scripts"
            / "research-worker"
            / "verify-worker.sh"
        ).read_text(encoding="utf-8")

        self.assertIn("research_pipeline.tests.test_corpus", verifier)
        self.assertIn("research_pipeline.tests.test_forward_candidate", verifier)

    def test_charter_keeps_coach_outbox_read_only_without_denying_verified_receipts(self) -> None:
        charter = (
            Path(__file__).resolve().parents[2]
            / "docs"
            / "autonomous-research-charter.md"
        ).read_text(encoding="utf-8")

        self.assertIn("no standalone ACK operation", charter)
        self.assertIn("bounded verified receipt", charter)
        self.assertNotIn("has no canonical ACK", charter)


if __name__ == "__main__":
    unittest.main()
