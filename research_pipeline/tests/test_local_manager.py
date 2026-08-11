from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import hashlib
from io import StringIO
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.cli import main as pipeline_main
from research_pipeline.local_dispatch import canonical_json_bytes, canonical_json_document_bytes
from research_pipeline.local_manager import (
    build_local_manager_preflight,
    summarize_local_research_kpi,
)


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
FORBIDDEN = [
    "CANONICAL_STATE_WRITE",
    "EXTERNAL_BACKFILL_OR_IMPORT",
    "OOS_OPEN_OR_GATE_RELAXATION",
    "PAID_API_OR_API_KEY",
    "PRODUCTION_OR_DATABASE_MUTATION",
    "SECOND_TIMER_OR_WRITER",
    "SERVER_RESEARCH_MCP_WRITE",
    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
]


def _run(root: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout.strip()


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


class PreflightRepository:
    def __init__(self, testcase: unittest.TestCase) -> None:
        self._temporary = TemporaryDirectory()
        testcase.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name)
        _run(self.root, "init", "-b", "main")
        _run(self.root, "config", "user.email", "local@example.invalid")
        _run(self.root, "config", "user.name", "Local Fixture")
        input_path = self.root / "records" / "input.txt"
        input_path.parent.mkdir(parents=True)
        input_path.write_bytes(b"frozen input\n")
        input_sha256 = _sha256(input_path.read_bytes())
        self.task = {
            "allowed_actions": ["READ_EXACT_FROZEN_INPUTS"],
            "authorization": AUTHORIZATION,
            "canonical_research_status": "FROZEN_SYNTHETIC_STATUS",
            "execution_mode": "READ_ONLY",
            "expected_outputs": ["SYNTHETIC_OUTPUT"],
            "forbidden_actions": FORBIDDEN,
            "inputs": [
                {
                    "kind": "REPOSITORY_PATH",
                    "locator": "records/input.txt",
                    "sha256": input_sha256,
                },
                {
                    "kind": "TASK_MESSAGE",
                    "locator": "Manager semantic validation remains authoritative for this fixture.",
                    "sha256": None,
                },
            ],
            "issued_at": "2026-08-11T00:00:00Z",
            "limits": {
                "max_candidate_variants": 0,
                "max_files_changed": 0,
                "network_access": "NONE",
                "timeout_seconds": 60,
            },
            "manager_thread_id": "manager-synthetic-thread",
            "objective": "Prove one deterministic Manager preflight without any state or research write.",
            "schema_version": "1",
            "state_authority": "SERVER_CANONICAL",
            "stop_conditions": ["Stop immediately on any synthetic identity or hash drift."],
            "task_id": "local-node-manager-preflight-v1",
            "task_type": "CAPABILITY_READINESS",
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        }
        self.task_path = self.root / "records" / "task.json"
        task_raw = canonical_json_document_bytes(self.task)
        self.task_path.write_bytes(task_raw)
        stop_hash = _sha256(canonical_json_bytes(self.task["stop_conditions"]))
        self.dispatch = {
            "authorization": AUTHORIZATION,
            "decision_contract": {
                "insufficient_evidence_disposition": "INSUFFICIENT_PROOF",
                "max_candidate_variants": 0,
                "negative_disposition": "NEGATIVE_CLOSE",
                "oos_access": "DENY",
                "outcome_tuning": "DENY",
                "positive_disposition": "POSITIVE_READY",
                "stop_condition_count": 1,
                "stop_conditions_sha256": stop_hash,
            },
            "dispatch_id": "manager-preflight-fixture-v1",
            "execution_mode": "READ_ONLY",
            "issued_at": "2026-08-11T00:01:00Z",
            "local_thread_id": "local-synthetic-thread",
            "manager_thread_id": "manager-synthetic-thread",
            "performance_case": {
                "causal_mechanism": "Mechanical preflight reduces repeated Manager validation assembly time.",
                "claim_boundary": "This proves only local mechanical identity and input closure, never research value.",
                "drawdown_hypothesis": "Immediate drawdown effect is zero because no strategy is executed or changed.",
                "expected_direction": "ZERO_IMMEDIATE_EFFECT",
                "opportunity_cost": "A thin reusable receipt reduces repeated recovery work without a new orchestrator.",
                "performance_hypothesis": "Mechanical validation latency falls while semantic authority stays unchanged.",
                "primary_metric": "fee_adjusted_total_pnl_delta_under_equal_capital",
                "research_phase": "CAPABILITY",
            },
            "policy_binding": {
                "policy_id": "AUTONOMOUS_TRADING_RESEARCH_V3",
                "policy_sha256": "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6",
                "primary_metric": "fee_adjusted_total_pnl_delta_under_equal_capital",
            },
            "schema_version": "1",
            "state_authority": "SERVER_CANONICAL",
            "task_id": self.task["task_id"],
            "task_sha256": _sha256(task_raw),
            "task_type": "CAPABILITY_READINESS",
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        }
        self.dispatch_path = self.root / "records" / "dispatch.json"
        self.dispatch_path.write_bytes(canonical_json_document_bytes(self.dispatch))
        _run(self.root, "add", ".")
        _run(self.root, "commit", "-m", "frozen preflight fixture")
        _run(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")


class LocalManagerPreflightTest(unittest.TestCase):
    def test_preflight_aggregates_validation_git_and_input_proof(self) -> None:
        repository = PreflightRepository(self)
        result = build_local_manager_preflight(
            repository.root,
            repository.dispatch_path,
            repository.task_path,
        )
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["head_commit"], result["origin_commit"])
        self.assertTrue(result["worktree_clean"])
        self.assertEqual(result["file_input_count"], 1)
        self.assertEqual(result["task_bound_file_input_count"], 1)
        self.assertEqual(result["input_proofs"][0]["verification"], "CURRENT_HEAD_REGULAR_NON_LINK_FILE")

    def test_preflight_rejects_dirty_worktree_and_origin_drift(self) -> None:
        repository = PreflightRepository(self)
        (repository.root / "untracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "clean worktree"):
            build_local_manager_preflight(repository.root, repository.dispatch_path, repository.task_path)

        (repository.root / "untracked.txt").unlink()
        (repository.root / "later.txt").write_text("later", encoding="utf-8")
        _run(repository.root, "add", "later.txt")
        _run(repository.root, "commit", "-m", "local commit beyond origin")
        with self.assertRaisesRegex(ValueError, "local origin"):
            build_local_manager_preflight(repository.root, repository.dispatch_path, repository.task_path)

    def test_preflight_rejects_task_bound_input_drift(self) -> None:
        repository = PreflightRepository(self)
        (repository.root / "records" / "input.txt").write_bytes(b"changed input\n")
        _run(repository.root, "add", "records/input.txt")
        _run(repository.root, "commit", "-m", "drift input")
        _run(repository.root, "update-ref", "refs/remotes/origin/main", "HEAD")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            build_local_manager_preflight(repository.root, repository.dispatch_path, repository.task_path)

    def test_top_level_preflight_runs_before_policy_or_state(self) -> None:
        repository = PreflightRepository(self)
        stdout = StringIO()
        stderr = StringIO()
        with (
            patch("research_pipeline.cli.load_policy") as load_policy,
            patch("research_pipeline.cli.ResearchStore") as research_store,
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            exit_code = pipeline_main(
                [
                    "local-research-manager-preflight",
                    str(repository.dispatch_path),
                    "--task",
                    str(repository.task_path),
                    "--repository-root",
                    str(repository.root),
                ]
            )
        self.assertEqual(exit_code, 0)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(json.loads(stdout.getvalue())["status"], "VALID")
        load_policy.assert_not_called()
        research_store.assert_not_called()


class LocalResearchKpiTest(unittest.TestCase):
    def test_kpi_reports_floor_stretch_overhead_and_missing_forward_proof(self) -> None:
        rows = []
        for index in range(4):
            rows.append(
                {
                    "classification_outcome": "COUNT",
                    "output_class": "MECHANISM_CONCLUSION",
                    "output_id": f"mechanism-{index}",
                }
            )
        for index in range(2):
            rows.append(
                {
                    "classification_outcome": "COUNT",
                    "output_class": "SPEC_OR_CAPABILITY_SLICE",
                    "output_id": f"slice-{index}",
                }
            )
        rows.append(
            {
                "classification_outcome": "EXCLUDE",
                "output_class": "NON_COUNTING",
                "output_id": "recovery-0",
            }
        )
        classification = {
            "period": {"start_inclusive": "2026-08-10T00:00:00Z", "end_exclusive": "2026-08-12T00:00:00Z"},
            "rows": rows,
            "status": "VALID",
            "unique_family_totals": {
                "MECHANISM_CONCLUSION": 4,
                "NON_COUNTING": 0,
                "SPEC_OR_CAPABILITY_SLICE": 2,
            },
        }
        result = summarize_local_research_kpi(classification)
        self.assertEqual(result["goal_assessment"]["weekly_floor"]["status"], "MET")
        self.assertEqual(result["goal_assessment"]["weekly_stretch"]["status"], "MET")
        self.assertEqual(result["goal_assessment"]["operational_overhead"]["status"], "MET")
        self.assertEqual(result["goal_assessment"]["rolling_four_week_forward_terminal"]["status"], "MISSING_PROOF")
        self.assertEqual(result["scientific_claim"], "NO_ALPHA_OR_PERFORMANCE_CLAIM")

    def test_top_level_kpi_uses_explicit_allowlist_without_policy_or_state(self) -> None:
        classification = {
            "period": {"start_inclusive": "2026-08-10T00:00:00Z", "end_exclusive": "2026-08-11T00:00:00Z"},
            "rows": [],
            "status": "VALID",
            "unique_family_totals": {
                "MECHANISM_CONCLUSION": 0,
                "NON_COUNTING": 0,
                "SPEC_OR_CAPABILITY_SLICE": 0,
            },
        }
        stdout = StringIO()
        stderr = StringIO()
        with (
            patch("research_pipeline.local_manager.validate_weekly_output_classification", return_value=classification) as validator,
            patch("research_pipeline.cli.load_policy") as load_policy,
            patch("research_pipeline.cli.ResearchStore") as research_store,
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            exit_code = pipeline_main(
                [
                    "local-research-throughput-kpi",
                    "--repository-root",
                    "C:/synthetic/repository",
                    "--period-start",
                    "2026-08-10T00:00:00Z",
                    "--period-end",
                    "2026-08-11T00:00:00Z",
                    "--acceptance",
                    "records/acceptance.json",
                ]
            )
        self.assertEqual(exit_code, 0)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(json.loads(stdout.getvalue())["status"], "VALID")
        validator.assert_called_once_with(
            Path("C:/synthetic/repository"),
            ["records/acceptance.json"],
            "2026-08-10T00:00:00Z",
            "2026-08-11T00:00:00Z",
        )
        load_policy.assert_not_called()
        research_store.assert_not_called()


if __name__ == "__main__":
    unittest.main()
