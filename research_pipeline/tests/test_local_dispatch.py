from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.local_dispatch import (
    canonical_json_document_bytes,
    canonical_json_bytes,
    load_and_validate_dispatch,
    validate_local_research_dispatch,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
DISPATCH_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "local-research-dispatch.microstructure-v3-evidence-diagnostic.v1.json"
)
TASK_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "local-research-task.microstructure-v3-evidence-diagnostic.v1.json"
)
SCHEMA_PATH = REPO_ROOT / "research_pipeline" / "local-research-dispatch.schema.json"


def load(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


class LocalResearchDispatchContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dispatch = load(DISPATCH_PATH)
        self.task = load(TASK_PATH)
        self.task_sha256 = hashlib.sha256(TASK_PATH.read_bytes()).hexdigest()

    def test_schema_example_and_executable_validator_accept_exact_dispatch(self) -> None:
        schema = load(SCHEMA_PATH)
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(self.dispatch)
        result = load_and_validate_dispatch(DISPATCH_PATH, TASK_PATH)
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(result["task_sha256"], self.task_sha256)
        self.assertEqual(
            DISPATCH_PATH.read_bytes(),
            canonical_json_document_bytes(self.dispatch),
        )

    def test_task_identity_and_manager_binding_fail_closed(self) -> None:
        for field, replacement, message in (
            ("task_id", "another-task", "task_id"),
            ("task_sha256", "0" * 64, "task_sha256"),
            ("manager_thread_id", "another-manager", "manager_thread_id"),
            ("task_type", "CAPABILITY_READINESS", "task_type"),
            ("execution_mode", "WORKTREE_WRITE", "execution_mode"),
        ):
            with self.subTest(field=field):
                value = deepcopy(self.dispatch)
                value[field] = replacement
                with self.assertRaisesRegex(ValueError, message):
                    validate_local_research_dispatch(
                        value,
                        task=self.task,
                        task_sha256=self.task_sha256,
                    )

    def test_performance_case_is_required_and_policy_bound(self) -> None:
        for field in (
            "causal_mechanism",
            "performance_hypothesis",
            "drawdown_hypothesis",
            "opportunity_cost",
            "claim_boundary",
        ):
            with self.subTest(field=field):
                value = deepcopy(self.dispatch)
                value["performance_case"][field] = "too short"
                with self.assertRaisesRegex(ValueError, field):
                    validate_local_research_dispatch(
                        value,
                        task=self.task,
                        task_sha256=self.task_sha256,
                    )
        value = deepcopy(self.dispatch)
        value["policy_binding"]["policy_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "policy_binding"):
            validate_local_research_dispatch(
                value,
                task=self.task,
                task_sha256=self.task_sha256,
            )

    def test_decision_contract_is_bound_to_task_stops_and_variant_limit(self) -> None:
        for field, replacement, message in (
            ("stop_condition_count", 8, "stop_condition_count"),
            ("stop_conditions_sha256", "0" * 64, "stop_conditions_sha256"),
            ("max_candidate_variants", 1, "max_candidate_variants"),
            ("outcome_tuning", "ALLOW", "deny tuning"),
            ("oos_access", "ALLOW", "deny tuning"),
        ):
            with self.subTest(field=field):
                value = deepcopy(self.dispatch)
                value["decision_contract"][field] = replacement
                with self.assertRaisesRegex(ValueError, message):
                    validate_local_research_dispatch(
                        value,
                        task=self.task,
                        task_sha256=self.task_sha256,
                    )

    def test_noncanonical_or_duplicate_dispatch_bytes_fail_closed(self) -> None:
        noncanonical = DISPATCH_PATH.with_name("not-used.json")
        duplicate = DISPATCH_PATH.with_name("also-not-used.json")
        self.assertNotEqual(
            json.dumps(self.dispatch, indent=2).encode("utf-8"),
            canonical_json_bytes(self.dispatch),
        )
        raw = DISPATCH_PATH.read_bytes()
        duplicate_raw = raw.replace(
            b'{"authorization":',
            b'{"schema_version":"1","authorization":',
            1,
        )
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            noncanonical = root / noncanonical.name
            duplicate = root / duplicate.name
            noncanonical.write_text(json.dumps(self.dispatch, indent=2), encoding="utf-8")
            duplicate.write_bytes(duplicate_raw)
            with self.assertRaisesRegex(ValueError, "canonical JSON"):
                load_and_validate_dispatch(noncanonical, TASK_PATH)
            with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
                load_and_validate_dispatch(duplicate, TASK_PATH)


if __name__ == "__main__":
    unittest.main()
