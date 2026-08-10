from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from research_pipeline.local_dispatch import canonical_json_bytes, canonical_json_document_bytes
from research_pipeline.local_weekly_output_classification import (
    AUTHORIZATION,
    canonical_weekly_output_classification_bytes,
    validate_weekly_output_classification,
    validate_weekly_output_classification_record,
)


SAFETY = {
    "canonical_state_changed": False,
    "oos_opened": False,
    "paid_api_used": False,
    "second_timer_created": False,
    "server_research_mcp_write_attempted": False,
    "trading_action_attempted": False,
}
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
SCHEMA_SOURCE = Path(__file__).resolve().parents[1] / "local-weekly-output-classification.v1.schema.json"
PERIOD_START = "2026-07-01T00:00:00Z"
PERIOD_END = "2026-07-02T00:00:00Z"


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _run(root: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.stdout.strip()


def _write(root: Path, relative: str, value: object) -> bytes:
    raw = canonical_json_document_bytes(value)
    path = root.joinpath(*relative.split("/"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    return raw


class SyntheticRepository:
    def __init__(
        self,
        testcase: unittest.TestCase,
        *,
        rows: int = 1,
        nested: bool = False,
        same_family: bool = False,
        post_outcome_intent: bool = False,
        result_status: str = "COMPLETED",
        outcome: str = "COUNT",
        exclusion_reason: str | None = None,
        disposition: str = "POSITIVE_READY",
        repeated_disposition: bool = False,
        safety_true: bool = False,
        reuse_result: bool = False,
        post_source_dispatch: bool = False,
    ) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        testcase.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name)
        _run(self.root, "init", "-b", "main")
        _run(self.root, "config", "user.email", "local@example.invalid")
        _run(self.root, "config", "user.name", "Local Fixture")
        schema_path = self.root / "research_pipeline" / SCHEMA_SOURCE.name
        schema_path.parent.mkdir(parents=True)
        schema_path.write_bytes(SCHEMA_SOURCE.read_bytes())

        self.task_path = "records/task.json"
        self.dispatch_path = "records/dispatch.json"
        self.task = {
            "allowed_actions": ["READ_EXACT_FROZEN_INPUTS"],
            "authorization": AUTHORIZATION,
            "canonical_research_status": "FROZEN_SYNTHETIC_STATUS",
            "execution_mode": "READ_ONLY",
            "expected_outputs": ["SYNTHETIC_OUTPUT"],
            "forbidden_actions": FORBIDDEN,
            "inputs": [],
            "issued_at": "2026-06-29T00:00:00Z",
            "limits": {
                "max_candidate_variants": 0,
                "max_files_changed": 0,
                "network_access": "NONE",
                "timeout_seconds": 60,
            },
            "manager_thread_id": "manager-synthetic-thread",
            "objective": "Provide a deterministic synthetic capability fixture for classification validation.",
            "schema_version": "1",
            "state_authority": "SERVER_CANONICAL",
            "stop_conditions": ["Stop on any synthetic fixture drift."],
            "task_id": "local-node-synthetic-classification-v1",
            "task_type": "CAPABILITY_READINESS",
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        }
        task_raw = _write(self.root, self.task_path, self.task)
        self.task_sha256 = _sha256(task_raw)
        claim = "Synthetic claim boundary proves deterministic classification only and no economic outcome whatsoever."
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
            "dispatch_id": "manager-synthetic-classification-v1",
            "execution_mode": "READ_ONLY",
            "issued_at": "2026-06-29T00:01:00Z",
            "local_thread_id": "local-synthetic-thread",
            "manager_thread_id": "manager-synthetic-thread",
            "performance_case": {
                "causal_mechanism": "Synthetic committed intent precedes one synthetic result and later acceptance.",
                "claim_boundary": claim,
                "drawdown_hypothesis": "Immediate drawdown effect remains exactly zero for this synthetic validation.",
                "expected_direction": "ZERO_IMMEDIATE_EFFECT",
                "opportunity_cost": "Synthetic setup cost is bounded and cannot establish any forward economic value.",
                "performance_hypothesis": "Immediate performance remains unchanged while validation behavior becomes testable.",
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
            "task_sha256": self.task_sha256,
            "task_type": "CAPABILITY_READINESS",
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        }
        dispatch_raw = canonical_json_document_bytes(self.dispatch)
        self.dispatch_sha256 = _sha256(dispatch_raw)
        if not post_source_dispatch:
            _write(self.root, self.dispatch_path, self.dispatch)
        self.intent_paths: list[str] = []
        self.intents: list[dict[str, object]] = []
        for index in range(rows):
            intent_path = f"records/intent-{index}.json"
            mappings = [
                {"action": "COUNT", "disposition": "POSITIVE_READY"},
                {"action": "EXCLUDE", "disposition": "NEGATIVE_CLOSE"},
                {"action": "EXCLUDE", "disposition": "INSUFFICIENT_PROOF"},
            ]
            if repeated_disposition:
                mappings.append({"action": "EXCLUDE", "disposition": "POSITIVE_READY"})
            intent = {
                "authorization": AUTHORIZATION,
                "claim_boundary_sha256": _sha256(canonical_json_bytes(claim)),
                "dispatch_id": self.dispatch["dispatch_id"],
                "dispatch_path": self.dispatch_path,
                "dispatch_sha256": self.dispatch_sha256,
                "disposition_actions": mappings,
                "document_type": "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_V1",
                "duplicate_family_key": "shared-family" if same_family else f"family-{index}",
                "independence_semantics": "NESTED_NON_INDEPENDENT" if nested else "UNIQUE_FAMILY",
                "intent_id": f"intent-{index}",
                "intent_path": intent_path,
                "issued_at": "2026-06-30T00:00:00Z",
                "manager_thread_id": "manager-synthetic-thread",
                "max_candidate_variants": 0,
                "output_class": "MECHANISM_CONCLUSION",
                "output_id": f"output-{index}",
                "record_stage": "PRE_DISPATCH_INTENT",
                "schema_version": "1",
                "task_id": self.task["task_id"],
                "task_path": self.task_path,
                "task_sha256": self.task_sha256,
            }
            self.intent_paths.append(intent_path)
            self.intents.append(intent)
            if not post_outcome_intent:
                _write(self.root, intent_path, intent)
        _run(self.root, "add", ".")
        _run(self.root, "commit", "-m", "pre-dispatch-intents")
        self.source_commit = _run(self.root, "rev-parse", "HEAD")

        if post_source_dispatch:
            _write(self.root, self.dispatch_path, self.dispatch)

        self.result_paths: list[str] = []
        self.results: list[dict[str, object]] = []
        safety = dict(SAFETY)
        if safety_true:
            safety["paid_api_used"] = True
        for index in range(rows):
            result_path = f"records/result-{index}.json"
            result = {
                "artifacts": [],
                "authorization": AUTHORIZATION,
                "checks": [{"evidence": "Synthetic closure passed.", "name": "SYNTHETIC_CHECK", "status": "PASS"}],
                "completed_at": "2026-07-01T01:05:00Z",
                "files_changed": [],
                "recommended_next_action": "Retain this synthetic result only for deterministic validation.",
                "safety_assertions": safety,
                "schema_version": "1",
                "source_git_commit": self.source_commit,
                "source_git_dirty_after": False,
                "source_git_dirty_before": False,
                "started_at": "2026-07-01T01:00:00Z",
                "status": result_status,
                "summary": f"Synthetic result {index} has no predictive or economic meaning.",
                "task_id": self.task["task_id"],
                "task_sha256": self.task_sha256,
                "uncertainty": ["MISSING_PROOF: synthetic fixture has no economic evidence."],
            }
            self.result_paths.append(result_path)
            self.results.append(result)
            _write(self.root, result_path, result)
        _run(self.root, "add", ".")
        _run(self.root, "commit", "-m", "accepted-results")
        self.result_commit = _run(self.root, "rev-parse", "HEAD")

        if post_outcome_intent:
            for intent_path, intent in zip(self.intent_paths, self.intents):
                _write(self.root, intent_path, intent)

        self.acceptance_paths: list[str] = []
        self.acceptances: list[dict[str, object]] = []
        for index in range(rows):
            result_index = 0 if reuse_result else index
            intent_raw = canonical_json_document_bytes(self.intents[index])
            result_raw = canonical_json_document_bytes(self.results[result_index])
            acceptance_path = f"records/acceptance-{index}.json"
            acceptance = {
                "acceptance_id": f"acceptance-{index}",
                "accepted_at": "2026-07-01T02:00:00Z",
                "accepted_disposition": disposition,
                "accepted_result_commit": self.result_commit,
                "authorization": AUTHORIZATION,
                "classification_outcome": outcome,
                "dispatch_id": self.dispatch["dispatch_id"],
                "dispatch_path": self.dispatch_path,
                "dispatch_sha256": self.dispatch_sha256,
                "document_type": "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_V1",
                "exclusion_reason": exclusion_reason,
                "intent_path": self.intent_paths[index],
                "intent_sha256": _sha256(intent_raw),
                "manager_thread_id": "manager-synthetic-thread",
                "output_id": self.intents[index]["output_id"],
                "record_stage": "MANAGER_ACCEPTANCE",
                "result_completed_at": self.results[result_index]["completed_at"],
                "result_path": self.result_paths[result_index],
                "result_sha256": _sha256(result_raw),
                "result_source_git_commit": self.source_commit,
                "result_status": result_status,
                "result_task_id": self.task["task_id"],
                "safety_assertions": safety,
                "schema_version": "1",
                "task_id": self.task["task_id"],
                "task_path": self.task_path,
                "task_sha256": self.task_sha256,
            }
            self.acceptance_paths.append(acceptance_path)
            self.acceptances.append(acceptance)
            _write(self.root, acceptance_path, acceptance)
        _run(self.root, "add", ".")
        _run(self.root, "commit", "-m", "manager-acceptances")

    def validate(self, *, start: str = PERIOD_START, end: str = PERIOD_END) -> dict[str, object]:
        return validate_weekly_output_classification(self.root, self.acceptance_paths, start, end)

    def commit_acceptance_update(self, index: int = 0) -> None:
        _write(self.root, self.acceptance_paths[index], self.acceptances[index])
        _run(self.root, "add", self.acceptance_paths[index])
        _run(self.root, "commit", "-m", "acceptance-update")


class LocalWeeklyOutputClassificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_SOURCE.read_text(encoding="utf-8"))
        if cls.schema["$schema"] != "https://json-schema.org/draft/2020-12/schema":
            raise AssertionError("schema does not declare Draft 2020-12")
        if len(cls.schema["oneOf"]) != 2:
            raise AssertionError("schema does not expose exactly two alternatives")

    def test_schema_accepts_both_stages_and_rejects_unknown_field(self) -> None:
        repository = SyntheticRepository(self)
        validate_weekly_output_classification_record(repository.intents[0])
        validate_weekly_output_classification_record(repository.acceptances[0])
        invalid = dict(repository.intents[0])
        invalid["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "closed companion schema"):
            validate_weekly_output_classification_record(invalid)

    def test_manual_validator_rejects_schema_invalid_repository_path(self) -> None:
        repository = SyntheticRepository(self)
        invalid = copy.deepcopy(repository.intents[0])
        invalid["task_path"] = "records/task with space.json"
        with self.assertRaisesRegex(ValueError, "repository-relative POSIX path"):
            validate_weekly_output_classification_record(invalid)

    def test_positive_lifecycle_is_deterministic_and_writes_nothing(self) -> None:
        repository = SyntheticRepository(self)
        before = _run(repository.root, "status", "--porcelain=v1", "--untracked-files=all")
        first = repository.validate()
        second = repository.validate()
        after = _run(repository.root, "status", "--porcelain=v1", "--untracked-files=all")
        self.assertEqual(before, "")
        self.assertEqual(after, "")
        self.assertEqual(first, second)
        self.assertEqual(
            canonical_weekly_output_classification_bytes(first),
            canonical_weekly_output_classification_bytes(second),
        )
        self.assertEqual(first["raw_count_totals"]["MECHANISM_CONCLUSION"], 1)

    def test_two_nested_mechanisms_count_as_one_family(self) -> None:
        repository = SyntheticRepository(self, rows=2, nested=True, same_family=True)
        result = repository.validate()
        self.assertEqual(result["raw_count_totals"]["MECHANISM_CONCLUSION"], 2)
        self.assertEqual(result["unique_family_totals"]["MECHANISM_CONCLUSION"], 1)

    def test_post_outcome_intent_is_rejected(self) -> None:
        repository = SyntheticRepository(self, post_outcome_intent=True)
        with self.assertRaisesRegex(ValueError, "Git proof"):
            repository.validate()

    def test_dispatch_created_after_source_commit_is_rejected(self) -> None:
        repository = SyntheticRepository(self, post_source_dispatch=True)
        with self.assertRaisesRegex(ValueError, "Git proof"):
            repository.validate()

    def test_nonancestor_result_commit_is_rejected(self) -> None:
        repository = SyntheticRepository(self)
        tree = _run(repository.root, "rev-parse", f"{repository.result_commit}^{{tree}}")
        orphan = subprocess.run(
            ["git", "-C", str(repository.root), "commit-tree", tree, "-m", "orphan-result"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        repository.acceptances[0]["accepted_result_commit"] = orphan
        repository.commit_acceptance_update()
        with self.assertRaisesRegex(ValueError, "ancestry"):
            repository.validate()

    def test_current_task_drift_is_rejected(self) -> None:
        repository = SyntheticRepository(self)
        repository.task["objective"] += " Drift."
        _write(repository.root, repository.task_path, repository.task)
        _run(repository.root, "add", repository.task_path)
        _run(repository.root, "commit", "-m", "task-drift")
        with self.assertRaisesRegex(ValueError, "task identity"):
            repository.validate()

    def test_half_open_period_rejects_end_boundary(self) -> None:
        repository = SyntheticRepository(self)
        with self.assertRaisesRegex(ValueError, "half-open period"):
            repository.validate(end="2026-07-01T01:05:00Z")

    def test_blocked_result_cannot_count(self) -> None:
        repository = SyntheticRepository(self, result_status="BLOCKED")
        with self.assertRaisesRegex(ValueError, "closed companion schema"):
            repository.validate()

    def test_safety_true_is_rejected(self) -> None:
        repository = SyntheticRepository(self, safety_true=True)
        with self.assertRaisesRegex(ValueError, "closed companion schema"):
            repository.validate()

    def test_unknown_disposition_is_rejected(self) -> None:
        repository = SyntheticRepository(self, disposition="UNLISTED_DISPOSITION")
        with self.assertRaisesRegex(ValueError, "not frozen"):
            repository.validate()

    def test_count_with_exclusion_reason_is_rejected(self) -> None:
        repository = SyntheticRepository(self, exclusion_reason="not allowed")
        with self.assertRaisesRegex(ValueError, "closed companion schema"):
            repository.validate()

    def test_exclude_requires_nonempty_reason(self) -> None:
        repository = SyntheticRepository(
            self,
            outcome="EXCLUDE",
            disposition="NEGATIVE_CLOSE",
            exclusion_reason=None,
        )
        with self.assertRaisesRegex(ValueError, "closed companion schema"):
            repository.validate()

    def test_repeated_disposition_label_is_rejected(self) -> None:
        repository = SyntheticRepository(self, repeated_disposition=True)
        with self.assertRaisesRegex(ValueError, "repeated disposition"):
            repository.validate()

    def test_duplicate_output_id_is_rejected(self) -> None:
        repository = SyntheticRepository(self, rows=2)
        repository.acceptances[1]["output_id"] = repository.acceptances[0]["output_id"]
        repository.commit_acceptance_update(1)
        with self.assertRaisesRegex(ValueError, "output_id"):
            repository.validate()

    def test_one_result_cannot_bind_multiple_intents(self) -> None:
        repository = SyntheticRepository(self, rows=2, reuse_result=True)
        with self.assertRaisesRegex(ValueError, "result-to-intent"):
            repository.validate()

    def test_shared_family_requires_all_nested(self) -> None:
        repository = SyntheticRepository(self, rows=2, same_family=True, nested=False)
        with self.assertRaisesRegex(ValueError, "nested non-independent"):
            repository.validate()

    def test_allowlist_must_be_nonempty_and_unique(self) -> None:
        repository = SyntheticRepository(self)
        with self.assertRaisesRegex(ValueError, "explicit, nonempty and unique"):
            validate_weekly_output_classification(repository.root, [], PERIOD_START, PERIOD_END)
        with self.assertRaisesRegex(ValueError, "explicit, nonempty and unique"):
            validate_weekly_output_classification(
                repository.root,
                [repository.acceptance_paths[0], repository.acceptance_paths[0]],
                PERIOD_START,
                PERIOD_END,
            )


if __name__ == "__main__":
    unittest.main()
