from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from research_pipeline.local_direct_screen_package import (
    build_direct_screen_package,
    write_direct_screen_package,
)
from research_pipeline.local_dispatch import canonical_json_document_bytes


class LocalDirectScreenPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "docs").mkdir()
        (self.root / "research").mkdir()
        (self.root / "data").mkdir()
        (self.root / "research_pipeline" / "examples").mkdir(parents=True)
        for relative, raw in {
            "docs/parent.md": b"parent\n",
            "docs/comparator.md": b"comparator\n",
            "research/runner.py": b"RUNNER = True\n",
            "data/sealed.tsv": b"1\t2\n",
        }.items():
            self.root.joinpath(*relative.split("/")).write_bytes(raw)
        self.blueprint_path = self.root / "research_pipeline" / "examples" / "blueprint.json"
        self.blueprint_path.write_bytes(canonical_json_document_bytes(self._blueprint()))

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _blueprint(self) -> dict:
        sealed = self.root / "data" / "sealed.tsv"
        sealed_sha = hashlib.sha256(sealed.read_bytes()).hexdigest()
        feature_message = (
            "Use the latest complete UTC-day volume ratio against the prior twenty complete-day median "
            "before the unchanged next-bar fill."
        )
        return {
            "canonical_research_status": "WAITING_FOR_EVIDENCE_QUEUE_IDLE",
            "classification": {
                "disposition_actions": [
                    {"action": "COUNT", "disposition": "DIRECT_SCREEN_PRIOR_RETAIN"},
                    {"action": "COUNT", "disposition": "DIRECT_SCREEN_BRANCH_CLOSE"},
                    {"action": "EXCLUDE", "disposition": "DIRECT_SCREEN_EVIDENCE_INSUFFICIENT"},
                ],
                "duplicate_family_key": "direct-screen-fixture",
                "independence_semantics": "UNIQUE_FAMILY",
            },
            "decision_contract": {
                "insufficient_evidence_disposition": "DIRECT_SCREEN_EVIDENCE_INSUFFICIENT",
                "negative_disposition": "DIRECT_SCREEN_BRANCH_CLOSE",
                "positive_disposition": "DIRECT_SCREEN_PRIOR_RETAIN",
            },
            "document_type": "LOCAL_DIRECT_SCREEN_PACKAGE_BLUEPRINT_V1",
            "inputs": [
                {"kind": "REPOSITORY_PATH", "locator": "docs/parent.md", "sha256": "AUTO"},
                {"kind": "REPOSITORY_PATH", "locator": "docs/comparator.md", "sha256": "AUTO"},
                {"kind": "REPOSITORY_PATH", "locator": "research/runner.py", "sha256": "AUTO"},
                {"kind": "SEALED_ARTIFACT", "locator": "data/sealed.tsv", "sha256": sealed_sha},
                {"kind": "TASK_MESSAGE", "locator": feature_message, "sha256": None},
            ],
            "issued_at": {
                "dispatch": "2026-08-15T10:01:00Z",
                "intent": "2026-08-15T10:02:00Z",
                "strategy_path": "2026-08-15T10:03:00Z",
                "task": "2026-08-15T10:00:00Z",
            },
            "limits": {"timeout_seconds": 3600},
            "local_thread_id": "local-direct-screen-fixture-thread",
            "manager_thread_id": "manager-direct-screen-fixture-thread",
            "objective": "Run one frozen direct economic screen and return one valid terminal mechanism conclusion without opening OOS.",
            "package_id": "direct-screen-fixture-v1",
            "performance_case": {
                "causal_mechanism": "Elevated complete-day participation can distinguish supported reversals from low-liquidity false starts before the next-bar fill.",
                "claim_boundary": "This development screen can retain one prior or close one feature family; it cannot prove independent alpha, create a candidate, open OOS, or authorize Trading.",
                "drawdown_hypothesis": "The admission rule is valuable only when total PnL rises under equal capital while drawdown, holding tails, underwater duration, and terminal inventory remain acceptable.",
                "expected_direction": "POSITIVE",
                "opportunity_cost": "A strict confirmation can miss profitable early reversals, while repairing a forward source for a mechanism that already fails historical economics wastes research capacity.",
                "performance_hypothesis": "The frozen decision-time admission rule improves fee-adjusted total PnL under equal capital in Design and Validation with broad annual and path-risk support.",
            },
            "schema_version": "1",
            "strategy_path": {
                "candidate_path": {
                    "matched_comparator_id": "DRA_EQUAL_CAPITAL_COMPARATOR",
                    "maximum_additional_research_steps": 1,
                    "parent_strategy_id": "BTC_DRA_V1",
                    "positive_next_step": "FROZEN_HYPOTHESIS_MANIFEST",
                    "runner_id": "BTC_DRA_DECLARATIVE_ENTRY_ADMISSION_RUNNER_V1",
                    "status": "DIRECT_TO_FROZEN_HYPOTHESIS",
                },
                "decision_time": {
                    "availability_rule": "The feature uses only the latest complete UTC day and its prior twenty complete days before the unchanged next-bar fill.",
                    "decision_clock": "UNCHANGED_DRA_COMPLETE_UTC_DAY_DECISION",
                    "feature_name": "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN",
                },
                "evidence_bindings": {
                    "decision_feature": feature_message,
                    "execution_runner": "research/runner.py",
                    "matched_comparator": "docs/comparator.md",
                    "parent_strategy": "docs/parent.md",
                },
            },
            "task_contract": {
                "allowed_actions": ["READ_FROZEN_INPUTS", "RUN_DIRECT_ECONOMIC_SCREEN", "RETURN_LOCAL_RESEARCH_RESULT"],
                "expected_outputs": ["EQUAL_CAPITAL_ECONOMIC_LEDGER", "FROZEN_GATE_MATRIX", "TERMINAL_DISPOSITION"],
                "forbidden_actions": [
                    "CANONICAL_STATE_WRITE",
                    "SERVER_RESEARCH_MCP_WRITE",
                    "SECOND_TIMER_OR_WRITER",
                    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
                    "OOS_OPEN_OR_GATE_RELAXATION",
                    "EXTERNAL_BACKFILL_OR_IMPORT",
                    "PAID_API_OR_API_KEY",
                    "PRODUCTION_OR_DATABASE_MUTATION",
                ],
                "stop_conditions": [
                    "Validate the exact task, dispatch, intent, strategy path, source hashes and sealed dataset before reading any result.",
                    "Return the retained-prior disposition only when every frozen economic and path-risk gate passes; otherwise close or report missing proof.",
                ],
            },
        }

    def test_builds_semantically_closed_four_artifact_package(self) -> None:
        artifacts, receipt = build_direct_screen_package(self.root, self.blueprint_path)
        self.assertEqual(receipt["status"], "VALID")
        self.assertEqual(len(artifacts), 4)
        task_path = "research_pipeline/examples/local-research-task.direct-screen-fixture-v1.json"
        dispatch_path = "research_pipeline/examples/local-research-dispatch.direct-screen-fixture-v1.json"
        intent_path = "research_pipeline/examples/local-weekly-output-classification.intent.direct-screen-fixture-v1.json"
        strategy_path = "research_pipeline/examples/local-research-strategy-path.direct-screen-fixture-v1.json"
        self.assertEqual(set(artifacts), {task_path, dispatch_path, intent_path, strategy_path})
        task = json.loads(artifacts[task_path])
        dispatch = json.loads(artifacts[dispatch_path])
        intent = json.loads(artifacts[intent_path])
        strategy = json.loads(artifacts[strategy_path])
        self.assertEqual(dispatch["task_sha256"], hashlib.sha256(artifacts[task_path]).hexdigest())
        self.assertEqual(intent["dispatch_sha256"], hashlib.sha256(artifacts[dispatch_path]).hexdigest())
        self.assertEqual(strategy["intent_sha256"], hashlib.sha256(artifacts[intent_path]).hexdigest())
        self.assertEqual(task["limits"]["max_candidate_variants"], 0)

    def test_writer_is_create_only(self) -> None:
        receipt = write_direct_screen_package(self.root, self.blueprint_path)
        self.assertEqual(receipt["status"], "VALID")
        with self.assertRaisesRegex(ValueError, "create-only"):
            write_direct_screen_package(self.root, self.blueprint_path)

    def test_rejects_sealed_artifact_hash_drift(self) -> None:
        blueprint = self._blueprint()
        for item in blueprint["inputs"]:
            if item["kind"] == "SEALED_ARTIFACT":
                item["sha256"] = "0" * 64
        self.blueprint_path.write_bytes(canonical_json_document_bytes(blueprint))
        with self.assertRaisesRegex(ValueError, "sealed artifact hash mismatch"):
            build_direct_screen_package(self.root, self.blueprint_path)


if __name__ == "__main__":
    unittest.main()
