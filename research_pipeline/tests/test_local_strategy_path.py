from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.local_dispatch import canonical_json_document_bytes
from research_pipeline.local_strategy_path import (
    load_and_validate_local_strategy_path,
    validate_local_strategy_path,
)


def valid_strategy_path() -> dict:
    return {
        "admission_id": "strategy-path-synthetic-v1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "candidate_path": {
            "existing_adapter_or_direct_runner": True,
            "implementation_before_economic_test": "DENY",
            "matched_comparator_id": "equal-capital-parent-ledger",
            "maximum_additional_research_steps": 1,
            "parent_strategy_id": "synthetic-parent-v1",
            "positive_next_step": "FROZEN_HYPOTHESIS_MANIFEST",
            "runner_id": "synthetic-runner-v1",
            "status": "DIRECT_TO_FROZEN_HYPOTHESIS",
        },
        "decision_time": {
            "availability_rule": "Seal the feature at the hour close before the next decision.",
            "availability_status": "KNOWN_BEFORE_DECISION",
            "decision_clock": "hour-close",
            "feature_name": "synthetic-predecision-feature",
            "post_outcome_dependency": "DENY",
        },
        "dispatch_id": "dispatch-synthetic-v1",
        "dispatch_sha256": "2" * 64,
        "disposition": {
            "independent_forward_or_oos_boundary_preserved": True,
            "insufficient_stops_without_permission": True,
            "negative_closes_family": True,
        },
        "document_type": "LOCAL_RESEARCH_STRATEGY_PATH_V1",
        "evidence_bindings": {
            "decision_feature": {
                "kind": "TASK_MESSAGE",
                "locator": "Freeze synthetic-predecision-feature before the decision.",
                "sha256": None,
                "subject_id": "synthetic-predecision-feature",
            },
            "execution_runner": {
                "kind": "REPOSITORY_PATH",
                "locator": "research_pipeline/synthetic_runner.py",
                "sha256": "7" * 64,
                "subject_id": "synthetic-runner-v1",
            },
            "matched_comparator": {
                "kind": "SEALED_ARTIFACT",
                "locator": ".research-state/synthetic/comparator.json",
                "sha256": "6" * 64,
                "subject_id": "equal-capital-parent-ledger",
            },
            "parent_strategy": {
                "kind": "REPOSITORY_PATH",
                "locator": "research_pipeline/synthetic_parent.json",
                "sha256": "5" * 64,
                "subject_id": "synthetic-parent-v1",
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
        "intent_id": "intent-synthetic-v1",
        "intent_sha256": "3" * 64,
        "issued_at": "2026-08-15T00:00:00Z",
        "manager_thread_id": "manager-synthetic-thread",
        "output_class": "MECHANISM_CONCLUSION",
        "schema_version": "1",
        "state_authority": "SERVER_CANONICAL",
        "task_id": "task-synthetic-v1",
        "task_sha256": "1" * 64,
        "timer_authority": "CODEX_CLOUD_OPS_ONLY",
    }


class LocalStrategyPathTest(unittest.TestCase):
    def test_portable_schema_accepts_the_executable_fixture(self) -> None:
        schema_path = (
            Path(__file__).parents[1]
            / "local-research-strategy-path.v1.schema.json"
        )
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(
            schema,
            format_checker=FormatChecker(),
        ).validate(valid_strategy_path())

    def test_valid_strategy_path_is_accepted(self) -> None:
        value = valid_strategy_path()
        self.assertIs(validate_local_strategy_path(value), value)

    def test_rejects_post_outcome_feature(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["decision_time"]["post_outcome_dependency"] = "ALLOW"
        with self.assertRaisesRegex(ValueError, "deny post-outcome"):
            validate_local_strategy_path(value)

    def test_rejects_support_slice_as_direct_candidate_delivery(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["output_class"] = "SPEC_OR_CAPABILITY_SLICE"
        with self.assertRaisesRegex(ValueError, "must be MECHANISM_CONCLUSION"):
            validate_local_strategy_path(value)

    def test_rejects_missing_economic_path_proof(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["economics"]["inventory_path_required"] = False
        with self.assertRaisesRegex(ValueError, "inventory_path_required must be true"):
            validate_local_strategy_path(value)

    def test_rejects_evidence_subject_drift(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["evidence_bindings"]["parent_strategy"]["subject_id"] = "different-parent"
        with self.assertRaisesRegex(ValueError, "does not bind its strategy subject"):
            validate_local_strategy_path(value)

    def test_rejects_unhashed_runner_binding(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["evidence_bindings"]["execution_runner"]["sha256"] = None
        with self.assertRaisesRegex(ValueError, "hash-verified repository runner"):
            validate_local_strategy_path(value)

    def test_rejects_more_than_one_additional_research_step(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["candidate_path"]["maximum_additional_research_steps"] = 2
        with self.assertRaisesRegex(ValueError, "at most one additional research step"):
            validate_local_strategy_path(value)

    def test_rejects_status_next_step_drift(self) -> None:
        value = deepcopy(valid_strategy_path())
        value["candidate_path"]["positive_next_step"] = "MATCHED_CAPITAL_EXPERIMENT"
        with self.assertRaisesRegex(ValueError, "status and positive next step"):
            validate_local_strategy_path(value)

    def test_file_loader_requires_canonical_bytes(self) -> None:
        with TemporaryDirectory() as directory:
            path = Path(directory) / "strategy-path.json"
            path.write_text(json.dumps(valid_strategy_path(), indent=2), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "canonical JSON bytes"):
                load_and_validate_local_strategy_path(path)

            path.write_bytes(canonical_json_document_bytes(valid_strategy_path()))
            result, raw = load_and_validate_local_strategy_path(path)
            self.assertEqual(result["admission_id"], "strategy-path-synthetic-v1")
            self.assertEqual(raw, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
