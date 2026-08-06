from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest

from research_pipeline.local_node import (
    validate_local_research_result,
    validate_local_research_task,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
TASK_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "local-research-task.capability-readiness.v1.json"
)


def load_task() -> dict[str, object]:
    return json.loads(TASK_PATH.read_text(encoding="utf-8"))


def task_sha256() -> str:
    return hashlib.sha256(TASK_PATH.read_bytes()).hexdigest()


def valid_result() -> dict[str, object]:
    return {
        "schema_version": "1",
        "task_id": "local-node-capability-readiness-v1",
        "task_sha256": task_sha256(),
        "status": "COMPLETED",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "started_at": "2026-08-06T08:10:00Z",
        "completed_at": "2026-08-06T08:11:00Z",
        "source_git_commit": "a" * 40,
        "source_git_dirty_before": True,
        "source_git_dirty_after": True,
        "summary": "The local capability contract was verified without writes.",
        "checks": [
            {
                "name": "TASK_CONTRACT_VALIDATION",
                "status": "PASS",
                "evidence": "The validator accepted the exact task bytes.",
            }
        ],
        "artifacts": [],
        "files_changed": [],
        "uncertainty": [],
        "recommended_next_action": "Dispatch one bounded tooling task.",
        "safety_assertions": {
            "canonical_state_changed": False,
            "server_research_mcp_write_attempted": False,
            "second_timer_created": False,
            "trading_action_attempted": False,
            "oos_opened": False,
            "paid_api_used": False,
        },
    }


class LocalResearchNodeContractTest(unittest.TestCase):
    def test_capability_task_and_result_validate(self) -> None:
        task = validate_local_research_task(load_task())
        result = validate_local_research_result(
            valid_result(),
            task=task,
            task_sha256=task_sha256(),
        )
        self.assertEqual(result["status"], "COMPLETED")

    def test_missing_fixed_prohibition_fails_closed(self) -> None:
        task = deepcopy(load_task())
        task["forbidden_actions"].remove("PAID_API_OR_API_KEY")
        task["forbidden_actions"].append("UNREGISTERED_LOCAL_ACTION")
        with self.assertRaisesRegex(ValueError, "mandatory forbidden actions missing"):
            validate_local_research_task(task)

    def test_wrong_task_hash_fails_closed(self) -> None:
        task = validate_local_research_task(load_task())
        result = valid_result()
        result["task_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "does not match task bytes"):
            validate_local_research_result(
                result,
                task=task,
                task_sha256=task_sha256(),
            )

    def test_read_only_result_cannot_report_changed_files(self) -> None:
        task = validate_local_research_task(load_task())
        result = valid_result()
        result["files_changed"] = ["README.md"]
        with self.assertRaisesRegex(ValueError, "exceeds the task limit"):
            validate_local_research_result(
                result,
                task=task,
                task_sha256=task_sha256(),
            )


if __name__ == "__main__":
    unittest.main()
