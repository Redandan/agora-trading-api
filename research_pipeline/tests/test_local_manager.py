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
    build_local_research_allocation_preflight,
    build_local_manager_preflight,
    build_local_strategy_manager_preflight,
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
    def __init__(self, testcase: unittest.TestCase, *, strategy_ready: bool = False) -> None:
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
        strategy_inputs = []
        if strategy_ready:
            strategy_files = {
                "records/decision-feature.json": b'{"feature":"synthetic-predecision-feature"}\n',
                "records/parent-strategy.json": b'{"strategy":"synthetic-parent-strategy-v1"}\n',
                "records/matched-comparator.json": b'{"comparator":"synthetic-equal-capital-parent-ledger"}\n',
                "records/runner.py": b"def run():\n    return None\n",
            }
            for relative, raw in strategy_files.items():
                path = self.root.joinpath(*relative.split("/"))
                path.write_bytes(raw)
                strategy_inputs.append(
                    {
                        "kind": "REPOSITORY_PATH",
                        "locator": relative,
                        "sha256": _sha256(raw),
                    }
                )
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
            ] + strategy_inputs,
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
            "task_type": (
                "EVIDENCE_DIAGNOSTIC" if strategy_ready else "CAPABILITY_READINESS"
            ),
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
                "expected_direction": (
                    "DIAGNOSTIC_ONLY" if strategy_ready else "ZERO_IMMEDIATE_EFFECT"
                ),
                "opportunity_cost": "A thin reusable receipt reduces repeated recovery work without a new orchestrator.",
                "performance_hypothesis": "Mechanical validation latency falls while semantic authority stays unchanged.",
                "primary_metric": "fee_adjusted_total_pnl_delta_under_equal_capital",
                "research_phase": "DIAGNOSTIC" if strategy_ready else "CAPABILITY",
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
            "task_type": self.task["task_type"],
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        }
        self.dispatch_path = self.root / "records" / "dispatch.json"
        self.dispatch_path.write_bytes(canonical_json_document_bytes(self.dispatch))
        self.intent = {
            "authorization": AUTHORIZATION,
            "claim_boundary_sha256": _sha256(
                canonical_json_bytes(
                    self.dispatch["performance_case"]["claim_boundary"]
                )
            ),
            "dispatch_id": self.dispatch["dispatch_id"],
            "dispatch_path": "records/dispatch.json",
            "dispatch_sha256": _sha256(self.dispatch_path.read_bytes()),
            "disposition_actions": [
                {"action": "COUNT", "disposition": "POSITIVE_READY"},
                {"action": "COUNT", "disposition": "NEGATIVE_CLOSE"},
                {"action": "EXCLUDE", "disposition": "INSUFFICIENT_PROOF"},
            ],
            "document_type": "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_V1",
            "duplicate_family_key": (
                "synthetic-manager-preflight-mechanism"
                if strategy_ready
                else "synthetic-manager-preflight-capability"
            ),
            "independence_semantics": "UNIQUE_FAMILY",
            "intent_id": "intent-manager-preflight-fixture-v1",
            "intent_path": "records/intent.json",
            "issued_at": "2026-08-11T00:00:30Z",
            "manager_thread_id": "manager-synthetic-thread",
            "max_candidate_variants": 0,
            "output_class": (
                "MECHANISM_CONCLUSION"
                if strategy_ready
                else "SPEC_OR_CAPABILITY_SLICE"
            ),
            "output_id": "output-manager-preflight-fixture-v1",
            "record_stage": "PRE_DISPATCH_INTENT",
            "schema_version": "1",
            "task_id": self.task["task_id"],
            "task_path": "records/task.json",
            "task_sha256": self.dispatch["task_sha256"],
        }
        self.intent_path = self.root / "records" / "intent.json"
        self.intent_path.write_bytes(canonical_json_document_bytes(self.intent))
        self.strategy_path = None
        if strategy_ready:
            self.strategy_path = self.root / "records" / "strategy-path.json"
            strategy = {
                "admission_id": "strategy-path-manager-preflight-fixture-v1",
                "authorization": AUTHORIZATION,
                "candidate_path": {
                    "existing_adapter_or_direct_runner": True,
                    "implementation_before_economic_test": "DENY",
                    "matched_comparator_id": "synthetic-equal-capital-parent-ledger",
                    "maximum_additional_research_steps": 1,
                    "parent_strategy_id": "synthetic-parent-strategy-v1",
                    "positive_next_step": "FROZEN_HYPOTHESIS_MANIFEST",
                    "runner_id": "synthetic-direct-runner-v1",
                    "status": "DIRECT_TO_FROZEN_HYPOTHESIS",
                },
                "decision_time": {
                    "availability_rule": "Use only observations sealed before the synthetic decision timestamp.",
                    "availability_status": "KNOWN_BEFORE_DECISION",
                    "decision_clock": "synthetic-hour-close",
                    "feature_name": "synthetic-predecision-feature",
                    "post_outcome_dependency": "DENY",
                },
                "dispatch_id": self.dispatch["dispatch_id"],
                "dispatch_sha256": _sha256(self.dispatch_path.read_bytes()),
                "disposition": {
                    "independent_forward_or_oos_boundary_preserved": True,
                    "insufficient_stops_without_permission": True,
                    "negative_closes_family": True,
                },
                "document_type": "LOCAL_RESEARCH_STRATEGY_PATH_V1",
                "evidence_bindings": {
                    "decision_feature": {
                        "kind": "REPOSITORY_PATH",
                        "locator": "records/decision-feature.json",
                        "sha256": strategy_inputs[0]["sha256"],
                        "subject_id": "synthetic-predecision-feature",
                    },
                    "execution_runner": {
                        "kind": "REPOSITORY_PATH",
                        "locator": "records/runner.py",
                        "sha256": strategy_inputs[3]["sha256"],
                        "subject_id": "synthetic-direct-runner-v1",
                    },
                    "matched_comparator": {
                        "kind": "REPOSITORY_PATH",
                        "locator": "records/matched-comparator.json",
                        "sha256": strategy_inputs[2]["sha256"],
                        "subject_id": "synthetic-equal-capital-parent-ledger",
                    },
                    "parent_strategy": {
                        "kind": "REPOSITORY_PATH",
                        "locator": "records/parent-strategy.json",
                        "sha256": strategy_inputs[1]["sha256"],
                        "subject_id": "synthetic-parent-strategy-v1",
                    },
                },
                "economics": {
                    "adverse_slippage_required": True,
                    "drawdown_required": True,
                    "equal_capital_comparator_required": True,
                    "fees_required": True,
                    "holding_age_required": True,
                    "inventory_path_required": True,
                    "total_pnl_required": True,
                },
                "intent_id": self.intent["intent_id"],
                "intent_sha256": _sha256(self.intent_path.read_bytes()),
                "issued_at": "2026-08-11T00:00:45Z",
                "manager_thread_id": self.task["manager_thread_id"],
                "output_class": self.intent["output_class"],
                "schema_version": "1",
                "state_authority": "SERVER_CANONICAL",
                "task_id": self.task["task_id"],
                "task_sha256": self.dispatch["task_sha256"],
                "timer_authority": "CODEX_CLOUD_OPS_ONLY",
            }
            self.strategy_path.write_bytes(canonical_json_document_bytes(strategy))
        _run(self.root, "add", ".")
        _run(self.root, "commit", "-m", "frozen preflight fixture")
        _run(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")

    def commit_intent(self) -> None:
        self.intent_path.write_bytes(canonical_json_document_bytes(self.intent))
        _run(self.root, "add", "records/intent.json")
        _run(self.root, "commit", "-m", "update preflight intent")
        _run(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")

    def commit_intent_bytes(self, raw: bytes) -> None:
        self.intent_path.write_bytes(raw)
        _run(self.root, "add", "records/intent.json")
        _run(self.root, "commit", "-m", "update preflight intent bytes")
        _run(self.root, "update-ref", "refs/remotes/origin/main", "HEAD")


class LocalManagerPreflightTest(unittest.TestCase):
    def test_preflight_aggregates_validation_git_and_input_proof(self) -> None:
        repository = PreflightRepository(self)
        result = build_local_manager_preflight(
            repository.root,
            repository.dispatch_path,
            repository.task_path,
            repository.intent_path,
        )
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["head_commit"], result["origin_commit"])
        self.assertTrue(result["worktree_clean"])
        self.assertEqual(result["file_input_count"], 1)
        self.assertEqual(result["task_bound_file_input_count"], 1)
        self.assertEqual(result["input_proofs"][0]["verification"], "CURRENT_HEAD_REGULAR_NON_LINK_FILE")
        self.assertEqual(
            result["research_value_gate"],
            {
                "countable_disposition_count": 2,
                "non_counting_integrity_exception": False,
                "output_class": "SPEC_OR_CAPABILITY_SLICE",
                "status": "COUNTABLE_OUTPUT_REQUIRED",
            },
        )

    def test_preflight_rejects_dirty_worktree_and_origin_drift(self) -> None:
        repository = PreflightRepository(self)
        (repository.root / "untracked.txt").write_text("dirty", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "clean worktree"):
            build_local_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
            )

        (repository.root / "untracked.txt").unlink()
        (repository.root / "later.txt").write_text("later", encoding="utf-8")
        _run(repository.root, "add", "later.txt")
        _run(repository.root, "commit", "-m", "local commit beyond origin")
        with self.assertRaisesRegex(ValueError, "local origin"):
            build_local_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
            )

    def test_preflight_rejects_task_bound_input_drift(self) -> None:
        repository = PreflightRepository(self)
        (repository.root / "records" / "input.txt").write_bytes(b"changed input\n")
        _run(repository.root, "add", "records/input.txt")
        _run(repository.root, "commit", "-m", "drift input")
        _run(repository.root, "update-ref", "refs/remotes/origin/main", "HEAD")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            build_local_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
            )

    def test_preflight_rejects_non_counting_work_without_integrity_exception(self) -> None:
        repository = PreflightRepository(self)
        repository.intent["output_class"] = "NON_COUNTING"
        repository.intent["independence_semantics"] = "NON_COUNTING_NOT_APPLICABLE"
        for mapping in repository.intent["disposition_actions"]:
            mapping["action"] = "EXCLUDE"
        repository.commit_intent()

        with self.assertRaisesRegex(ValueError, "no countable disposition"):
            build_local_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
            )

        result = build_local_manager_preflight(
            repository.root,
            repository.dispatch_path,
            repository.task_path,
            repository.intent_path,
            allow_non_counting_integrity_repair=True,
        )
        self.assertEqual(
            result["research_value_gate"]["status"],
            "NON_COUNTING_ACTIVE_INTEGRITY_EXCEPTION",
        )

    def test_preflight_rejects_noncanonical_classification_intent(self) -> None:
        repository = PreflightRepository(self)
        repository.commit_intent_bytes(
            json.dumps(repository.intent, indent=2, sort_keys=True).encode("utf-8")
        )

        with self.assertRaisesRegex(ValueError, "canonical JSON document bytes"):
            build_local_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
            )

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
                    "--intent",
                    str(repository.intent_path),
                    "--repository-root",
                    str(repository.root),
                ]
            )
        self.assertEqual(exit_code, 0)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(json.loads(stdout.getvalue())["status"], "VALID")
        load_policy.assert_not_called()
        research_store.assert_not_called()

    def test_strategy_preflight_accepts_direct_candidate_path(self) -> None:
        repository = PreflightRepository(self, strategy_ready=True)

        result = build_local_strategy_manager_preflight(
            repository.root,
            repository.dispatch_path,
            repository.task_path,
            repository.intent_path,
            repository.strategy_path,
        )

        self.assertEqual(
            result["document_type"],
            "LOCAL_MANAGER_STRATEGY_PREFLIGHT_RECEIPT_V1",
        )
        self.assertEqual(
            result["strategy_path_gate"]["status"],
            "DIRECT_CANDIDATE_PATH_REQUIRED",
        )
        self.assertEqual(
            result["strategy_path_gate"]["maximum_additional_research_steps"],
            1,
        )
        self.assertEqual(
            result["strategy_path_gate"]["runner_id"],
            "synthetic-direct-runner-v1",
        )

    def test_strategy_preflight_rejects_task_input_binding_drift(self) -> None:
        repository = PreflightRepository(self, strategy_ready=True)
        strategy = json.loads(repository.strategy_path.read_text(encoding="utf-8"))
        strategy["evidence_bindings"]["execution_runner"]["sha256"] = "0" * 64
        repository.strategy_path.write_bytes(canonical_json_document_bytes(strategy))
        _run(repository.root, "add", "records/strategy-path.json")
        _run(repository.root, "commit", "-m", "strategy path drift")
        _run(repository.root, "update-ref", "refs/remotes/origin/main", "HEAD")

        with self.assertRaisesRegex(ValueError, "does not bind an exact frozen task input"):
            build_local_strategy_manager_preflight(
                repository.root,
                repository.dispatch_path,
                repository.task_path,
                repository.intent_path,
                repository.strategy_path,
            )

    def test_top_level_strategy_preflight_runs_before_policy_or_state(self) -> None:
        repository = PreflightRepository(self, strategy_ready=True)
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
                    "local-research-strategy-preflight",
                    str(repository.dispatch_path),
                    "--task",
                    str(repository.task_path),
                    "--intent",
                    str(repository.intent_path),
                    "--strategy-path",
                    str(repository.strategy_path),
                    "--repository-root",
                    str(repository.root),
                ]
            )
        self.assertEqual(exit_code, 0)
        self.assertEqual(stderr.getvalue(), "")
        receipt = json.loads(stdout.getvalue())
        self.assertEqual(receipt["status"], "VALID")
        self.assertEqual(
            receipt["strategy_path_gate"]["availability_status"],
            "KNOWN_BEFORE_DECISION",
        )
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
                    "strategy_path_admitted": True,
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
        self.assertEqual(
            result["goal_assessment"]["candidate_delivery_efficiency"]["status"],
            "MET",
        )
        self.assertEqual(
            result["goal_assessment"]["candidate_delivery_efficiency"][
                "direct_mechanism_ratio_basis_points"
            ],
            5714,
        )
        allocation = result["goal_assessment"]["candidate_delivery_efficiency"][
            "next_dispatch_policy"
        ]
        self.assertEqual(
            allocation["direct_strategy_path"]["status"],
            "ALLOW_IMPROVES_OR_PRESERVES_RATIO",
        )
        self.assertEqual(
            allocation["support_work"]["status"],
            "DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY",
        )
        self.assertEqual(allocation["support_outputs_available_before_target_loss"], 0)
        self.assertEqual(result["goal_assessment"]["operational_overhead"]["status"], "MET")
        self.assertEqual(result["goal_assessment"]["rolling_four_week_forward_terminal"]["status"], "MISSING_PROOF")
        self.assertEqual(result["scientific_claim"], "NO_ALPHA_OR_PERFORMANCE_CLAIM")

    def test_kpi_keeps_unverified_mechanism_labels_as_proxy_only(self) -> None:
        rows = [
            {
                "classification_outcome": "COUNT",
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": f"legacy-mechanism-{index}",
                "strategy_path_admitted": False,
            }
            for index in range(5)
        ]
        rows.extend(
            {
                "classification_outcome": "COUNT",
                "output_class": "SPEC_OR_CAPABILITY_SLICE",
                "output_id": f"slice-{index}",
            }
            for index in range(21)
        )
        classification = {
            "period": {
                "start_inclusive": "2026-08-10T00:00:00Z",
                "end_exclusive": "2026-08-17T00:00:00Z",
            },
            "rows": rows,
            "status": "VALID",
            "unique_family_totals": {
                "MECHANISM_CONCLUSION": 5,
                "NON_COUNTING": 0,
                "SPEC_OR_CAPABILITY_SLICE": 21,
            },
        }

        result = summarize_local_research_kpi(classification)
        efficiency = result["goal_assessment"]["candidate_delivery_efficiency"]
        self.assertEqual(efficiency["accepted_output_count"], 26)
        self.assertEqual(efficiency["labelled_mechanism_proxy_count"], 5)
        self.assertEqual(efficiency["direct_mechanism_count"], 0)
        self.assertEqual(efficiency["direct_mechanism_ratio_basis_points"], 0)
        self.assertEqual(efficiency["status"], "BELOW_TARGET")
        self.assertEqual(
            efficiency["natural_recovery_forecast"]["status"],
            "MISSING_PROOF",
        )

    def test_kpi_forecasts_natural_recovery_without_fabricating_work(self) -> None:
        rows = [
            {
                "classification_outcome": "COUNT",
                "completed_at": "2026-08-10T12:00:00Z",
                "output_class": "SPEC_OR_CAPABILITY_SLICE",
                "output_id": "support-old",
            },
            {
                "classification_outcome": "COUNT",
                "completed_at": "2026-08-15T02:00:00Z",
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": "legacy-mechanism",
                "strategy_path_admitted": False,
            },
            {
                "classification_outcome": "COUNT",
                "completed_at": "2026-08-15T08:00:00Z",
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": "direct-mechanism-1",
                "strategy_path_admitted": True,
            },
            {
                "classification_outcome": "COUNT",
                "completed_at": "2026-08-15T08:15:00Z",
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": "direct-mechanism-2",
                "strategy_path_admitted": True,
            },
        ]
        classification = {
            "period": {
                "start_inclusive": "2026-08-08T09:00:00Z",
                "end_exclusive": "2026-08-15T09:00:00Z",
            },
            "rows": rows,
            "status": "VALID",
            "unique_family_totals": {
                "MECHANISM_CONCLUSION": 3,
                "NON_COUNTING": 0,
                "SPEC_OR_CAPABILITY_SLICE": 1,
            },
        }

        result = summarize_local_research_kpi(classification)
        forecast = result["goal_assessment"]["candidate_delivery_efficiency"][
            "natural_recovery_forecast"
        ]
        self.assertEqual(forecast["status"], "PROJECTED")
        self.assertEqual(forecast["strictly_after"], "2026-08-17T12:00:00Z")
        self.assertEqual(forecast["remaining_output_count_after_boundary"], 3)
        self.assertEqual(forecast["direct_mechanism_count_after_boundary"], 2)
        self.assertEqual(
            forecast["direct_mechanism_ratio_basis_points_after_boundary"],
            6666,
        )
        self.assertEqual(forecast["window_seconds"], 604800)
        allocation = result["goal_assessment"]["candidate_delivery_efficiency"][
            "next_dispatch_policy"
        ]
        self.assertEqual(
            allocation["direct_strategy_path"]["projected_ratio_basis_points"],
            6000,
        )
        self.assertEqual(
            allocation["support_work"]["status"],
            "DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY",
        )

    def test_kpi_allows_one_support_output_only_with_strict_majority_headroom(self) -> None:
        rows = [
            {
                "classification_outcome": "COUNT",
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": f"direct-{index}",
                "strategy_path_admitted": True,
            }
            for index in range(3)
        ]
        rows.append(
            {
                "classification_outcome": "COUNT",
                "output_class": "SPEC_OR_CAPABILITY_SLICE",
                "output_id": "support-existing",
            }
        )
        classification = {
            "period": {
                "start_inclusive": "2026-08-10T00:00:00Z",
                "end_exclusive": "2026-08-17T00:00:00Z",
            },
            "rows": rows,
            "status": "VALID",
            "unique_family_totals": {
                "MECHANISM_CONCLUSION": 3,
                "NON_COUNTING": 0,
                "SPEC_OR_CAPABILITY_SLICE": 1,
            },
        }

        result = summarize_local_research_kpi(classification)
        allocation = result["goal_assessment"]["candidate_delivery_efficiency"][
            "next_dispatch_policy"
        ]
        self.assertEqual(
            allocation["support_work"]["status"],
            "ALLOW_WITHIN_STRICT_MAJORITY_BUDGET",
        )
        self.assertEqual(allocation["support_outputs_available_before_target_loss"], 1)
        self.assertEqual(
            allocation["support_work"]["projected_ratio_basis_points"],
            6000,
        )

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


class LocalResearchAllocationPreflightTest(unittest.TestCase):
    def _kpi(self, *, support_status: str) -> dict:
        return {
            "goal_assessment": {
                "candidate_delivery_efficiency": {
                    "accepted_output_count": 28,
                    "direct_mechanism_count": 2,
                    "direct_mechanism_ratio_basis_points": 714,
                    "next_dispatch_policy": {
                        "direct_strategy_path": {
                            "projected_direct_mechanism_count": 3,
                            "projected_ratio_basis_points": 1034,
                            "status": "ALLOW_IMPROVES_OR_PRESERVES_RATIO",
                        },
                        "policy": "DIRECT_FIRST_STRICT_MAJORITY_ALLOCATION",
                        "support_outputs_available_before_target_loss": 0,
                        "support_work": {
                            "projected_direct_mechanism_count": 2,
                            "projected_ratio_basis_points": 689,
                            "status": support_status,
                        },
                    },
                }
            },
            "period": {
                "start_inclusive": "2026-08-08T00:00:00Z",
                "end_exclusive": "2026-08-15T00:00:00Z",
            },
        }

    def test_allocation_preflight_allows_verified_direct_path(self) -> None:
        manager = {
            "authorization": AUTHORIZATION,
            "research_value_gate": {"output_class": "MECHANISM_CONCLUSION"},
        }
        with (
            patch(
                "research_pipeline.local_manager.build_local_strategy_manager_preflight",
                return_value=manager,
            ) as strategy_preflight,
            patch(
                "research_pipeline.local_manager.build_local_manager_preflight"
            ) as manager_preflight,
            patch(
                "research_pipeline.local_manager.build_local_research_kpi",
                return_value=self._kpi(
                    support_status="DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY"
                ),
            ),
        ):
            result = build_local_research_allocation_preflight(
                "C:/repo",
                "dispatch.json",
                "task.json",
                "intent.json",
                ["acceptance.json"],
                "2026-08-08T00:00:00Z",
                "2026-08-15T00:00:00Z",
                strategy_path_path="strategy-path.json",
            )
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(
            result["allocation_gate"]["status"],
            "DIRECT_STRATEGY_PATH_ALLOWED",
        )
        strategy_preflight.assert_called_once()
        manager_preflight.assert_not_called()

    def test_allocation_preflight_rejects_support_without_headroom(self) -> None:
        manager = {
            "authorization": AUTHORIZATION,
            "research_value_gate": {"output_class": "SPEC_OR_CAPABILITY_SLICE"},
        }
        with (
            patch(
                "research_pipeline.local_manager.build_local_manager_preflight",
                return_value=manager,
            ),
            patch(
                "research_pipeline.local_manager.build_local_research_kpi",
                return_value=self._kpi(
                    support_status="DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY"
                ),
            ),
        ):
            with self.assertRaisesRegex(ValueError, "allocation gate defers support"):
                build_local_research_allocation_preflight(
                    "C:/repo",
                    "dispatch.json",
                    "task.json",
                    "intent.json",
                    ["acceptance.json"],
                    "2026-08-08T00:00:00Z",
                    "2026-08-15T00:00:00Z",
                )

    def test_allocation_preflight_allows_explicit_integrity_exception(self) -> None:
        manager = {
            "authorization": AUTHORIZATION,
            "research_value_gate": {"output_class": "NON_COUNTING"},
        }
        with (
            patch(
                "research_pipeline.local_manager.build_local_manager_preflight",
                return_value=manager,
            ) as manager_preflight,
            patch(
                "research_pipeline.local_manager.build_local_research_kpi",
                return_value=self._kpi(
                    support_status="DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY"
                ),
            ),
        ):
            result = build_local_research_allocation_preflight(
                "C:/repo",
                "dispatch.json",
                "task.json",
                "intent.json",
                ["acceptance.json"],
                "2026-08-08T00:00:00Z",
                "2026-08-15T00:00:00Z",
                allow_non_counting_integrity_repair=True,
            )
        self.assertEqual(
            result["allocation_gate"]["status"],
            "ACTIVE_EVIDENCE_INTEGRITY_EXCEPTION_ALLOWED",
        )
        self.assertTrue(
            manager_preflight.call_args.kwargs[
                "allow_non_counting_integrity_repair"
            ]
        )


if __name__ == "__main__":
    unittest.main()
