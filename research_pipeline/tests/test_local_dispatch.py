from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import hashlib
from io import StringIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.cli import main as pipeline_main
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


def valid_result(task_sha256: str) -> dict[str, object]:
    return {
        "schema_version": "1",
        "task_id": "local-node-microstructure-v3-evidence-diagnostic-v1",
        "task_sha256": task_sha256,
        "status": "COMPLETED",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "started_at": "2026-08-22T00:05:00Z",
        "completed_at": "2026-08-22T00:06:00Z",
        "source_git_commit": "a" * 40,
        "source_git_dirty_before": False,
        "source_git_dirty_after": False,
        "summary": "The frozen diagnostic completed without repository writes.",
        "checks": [
            {
                "name": "FROZEN_DIAGNOSTIC",
                "status": "PASS",
                "evidence": "The result remains bound to the exact task and handoff.",
            }
        ],
        "artifacts": [],
        "files_changed": [],
        "uncertainty": ["Matched-capital PnL and drawdown remain MISSING_PROOF."],
        "recommended_next_action": "Apply the frozen dispatch disposition without tuning.",
        "safety_assertions": {
            "canonical_state_changed": False,
            "server_research_mcp_write_attempted": False,
            "second_timer_created": False,
            "trading_action_attempted": False,
            "oos_opened": False,
            "paid_api_used": False,
        },
    }


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

    def test_result_closure_binds_dispatch_task_and_result_hashes(self) -> None:
        with TemporaryDirectory() as temporary:
            result_path = Path(temporary) / "result.json"
            result_raw = canonical_json_document_bytes(valid_result(self.task_sha256))
            result_path.write_bytes(result_raw)
            validation = load_and_validate_dispatch(
                DISPATCH_PATH,
                TASK_PATH,
                result_path,
            )
        self.assertEqual(
            validation["closure_status"],
            "VALIDATED_RESULT_BOUND_TO_PERFORMANCE_DISPATCH",
        )
        self.assertEqual(validation["result_status"], "COMPLETED")
        self.assertEqual(
            validation["result_sha256"],
            hashlib.sha256(result_raw).hexdigest(),
        )

    def test_result_closure_rejects_wrong_task_hash_or_unsafe_assertion(self) -> None:
        for mutation, message in (
            (("task_sha256",), "does not match task bytes"),
            (("safety_assertions", "paid_api_used"), "unsafe result assertions"),
        ):
            with self.subTest(mutation=mutation), TemporaryDirectory() as temporary:
                result = valid_result(self.task_sha256)
                if len(mutation) == 1:
                    result[mutation[0]] = "0" * 64
                else:
                    result[mutation[0]][mutation[1]] = True
                result_path = Path(temporary) / "result.json"
                result_path.write_bytes(canonical_json_document_bytes(result))
                with self.assertRaisesRegex(ValueError, message):
                    load_and_validate_dispatch(
                        DISPATCH_PATH,
                        TASK_PATH,
                        result_path,
                    )

    def test_top_level_cli_task_only_matches_reusable_validator_before_store(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_dir = root / "state-must-not-exist"
            missing_policy = root / "policy-must-not-be-read.json"
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
                        "--state-dir",
                        str(state_dir),
                        "--policy",
                        str(missing_policy),
                        "validate-local-research-dispatch",
                        str(DISPATCH_PATH),
                        "--task",
                        str(TASK_PATH),
                    ]
                )
            expected = load_and_validate_dispatch(DISPATCH_PATH, TASK_PATH)
            self.assertEqual(exit_code, 0)
            self.assertEqual(stderr.getvalue(), "")
            self.assertEqual(
                stdout.getvalue(),
                canonical_json_bytes(expected).decode("utf-8") + "\n",
            )
            self.assertFalse(state_dir.exists())
            load_policy.assert_not_called()
            research_store.assert_not_called()

    def test_top_level_cli_optional_result_matches_reusable_validator(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_dir = root / "state-must-not-exist"
            result_path = root / "result.json"
            result_path.write_bytes(
                canonical_json_document_bytes(valid_result(self.task_sha256))
            )
            stdout = StringIO()
            stderr = StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = pipeline_main(
                    [
                        "--state-dir",
                        str(state_dir),
                        "validate-local-research-dispatch",
                        str(DISPATCH_PATH),
                        "--task",
                        str(TASK_PATH),
                        "--result",
                        str(result_path),
                    ]
                )
            expected = load_and_validate_dispatch(
                DISPATCH_PATH,
                TASK_PATH,
                result_path,
            )
            self.assertEqual(exit_code, 0)
            self.assertEqual(stderr.getvalue(), "")
            self.assertEqual(
                stdout.getvalue(),
                canonical_json_bytes(expected).decode("utf-8") + "\n",
            )
            self.assertFalse(state_dir.exists())

    def test_top_level_cli_invalid_dispatch_fails_without_state(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_dir = root / "state-must-not-exist"
            invalid_dispatch = root / "invalid-dispatch.json"
            invalid_dispatch.write_text(
                json.dumps(self.dispatch, indent=2),
                encoding="utf-8",
            )
            stdout = StringIO()
            stderr = StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                exit_code = pipeline_main(
                    [
                        "--state-dir",
                        str(state_dir),
                        "validate-local-research-dispatch",
                        str(invalid_dispatch),
                        "--task",
                        str(TASK_PATH),
                    ]
                )
            error = json.loads(stderr.getvalue())
            self.assertEqual(exit_code, 2)
            self.assertEqual(stdout.getvalue(), "")
            self.assertEqual(error["status"], "PIPELINE_ERROR")
            self.assertEqual(error["type"], "ValueError")
            self.assertIn("canonical JSON", error["detail"])
            self.assertFalse(state_dir.exists())


if __name__ == "__main__":
    unittest.main()
