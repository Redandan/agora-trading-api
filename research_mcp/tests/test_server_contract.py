from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime
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

    def test_cloud_prompt_is_v12_hash_bound_and_never_writes_local_research_state(self) -> None:
        prompt = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "prompts"
            / "daily-research-tick.md"
        ).read_text(encoding="utf-8")
        contract = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v12.json"
        ).read_bytes()
        contract_sha256 = hashlib.sha256(contract).hexdigest()
        contract_value = json.loads(contract.decode("utf-8"))
        frozen_v6 = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v6.json"
        ).read_bytes()
        self.assertEqual(
            hashlib.sha256(frozen_v6.replace(b"\r\n", b"\n")).hexdigest(),
            "d58468b509ffce9f26af2d631a67c97d97f23c8aee369a1c7a3dafbee7959c85",
        )
        frozen_v9 = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "cloud-ops-schedule-contract.v9.json"
        ).read_bytes()
        self.assertEqual(
            hashlib.sha256(frozen_v9.replace(b"\r\n", b"\n")).hexdigest(),
            "04d11ad095f64c6dda7d746cf36f26af773f53684765c368d6fe595533ab7d2c",
        )
        self.assertEqual(
            contract_sha256,
            "98cc2374961fb37c00a8396e6bd8126b7b39a32d7d85ea0e0fcd30c2b9c7fc0c",
        )
        self.assertEqual(contract_value["document_status"], "FROZEN")
        scheduled = datetime.strptime(
            contract_value["recurrence"]["local_time"], "%H:%M"
        )
        canonical_due = datetime.strptime(
            contract_value["canonical_heartbeat_due"]["local_time"], "%H:%M"
        )
        self.assertEqual(
            int((scheduled - canonical_due).total_seconds()),
            contract_value["dispatch_margin"][
                "scheduled_seconds_after_canonical_due"
            ],
        )
        self.assertEqual(
            contract_value["dispatch_margin"]["additional_timer"], "DENY"
        )

        self.assertIn("get_research_status", prompt)
        self.assertIn("submit_research_candidate_bundle", prompt)
        self.assertIn("evidence_diagnostic", prompt)
        self.assertIn("worker_release.status=READY", prompt)
        self.assertIn("ops_schedule_contract.status=READY", prompt)
        self.assertIn("CLOUD_OPS_SCHEDULE_V12", prompt)
        self.assertIn(contract_sha256, prompt)
        self.assertIn("recurrence.timezone=Asia/Taipei", prompt)
        self.assertIn("recurrence.local_time=09:05", prompt)
        self.assertIn("recurrence.end=NEVER", prompt)
        self.assertIn("canonical_heartbeat_due.local_time=09:00", prompt)
        self.assertIn(
            "dispatch_margin.scheduled_seconds_after_canonical_due=300", prompt
        )
        self.assertIn(
            "dispatch_margin.early_call_behavior=NOT_DUE_NO_DELIVERY_RECEIPT",
            prompt,
        )
        self.assertIn("dispatch_margin.additional_timer=DENY", prompt)
        self.assertIn("document_status=FROZEN", prompt)
        self.assertIn("PREPARED_NOT_ACTIVE_V12", prompt)
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
        self.assertIn("SEALED_COACH_SAME_SCHEDULE_CHAT_DELIVERY_V2", prompt)
        self.assertIn("6a71a167-be58-83ec-aed2-f1736e31dd45", prompt)
        self.assertIn("exact full canonical `delivery_prompt`", prompt)
        self.assertIn("prior assistant message", prompt)
        self.assertIn("same-turn receipt", prompt)
        self.assertNotIn(
            "Use only `list_threads`, `read_thread`, and `send_message_to_thread`",
            prompt,
        )
        self.assertIn("delivery_proof_sla.completion_window_seconds=10800", prompt)
        self.assertIn("DELIVERED_TO_COACH_TASK_VERIFIED", prompt)
        self.assertIn("QUEUED_TO_COACH_TASK_UNVERIFIED", prompt)
        self.assertIn("ALREADY_DELIVERED_TO_COACH_TASK", prompt)
        self.assertIn("complete token alone is insufficient", prompt)
        self.assertIn("queue-to-verified-receipt proof", prompt)
        self.assertIn("BREACH_PENDING_DELIVERY_PROOF", prompt)
        self.assertIn("MISSING_PROOF_LEGACY_EVENT", prompt)
        self.assertIn("coach_outbox", prompt)
        self.assertIn("019fca63-4f8f-71e3-9d88-297bca468eb9", prompt)
        self.assertIn("zero to eight\nreceipts proven", prompt)
        self.assertIn("Do not block the", prompt)
        self.assertIn("MISSING_PROOF_PRIOR_ASSISTANT_V12_PROMPT` debt", prompt)
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
