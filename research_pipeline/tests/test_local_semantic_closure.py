from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

try:
    from jsonschema import Draft202012Validator
except ModuleNotFoundError:  # Manager acceptance environment owns schema execution.
    Draft202012Validator = None

from research_pipeline.cli import main
from research_pipeline.local_dispatch import (
    canonical_json_bytes,
    canonical_json_document_bytes,
)
from research_pipeline.local_semantic_closure import (
    DISPOSITION,
    load_and_validate_semantic_closure,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "research_pipeline/local-partitioned-event-semantic-closure.v1.schema.json"
CLOSURE_PATH = REPO_ROOT / (
    "research_pipeline/examples/"
    "local-semantic-closure.btc-utc-day-3pct-historical-post-shock-stability-audit.v1.json"
)
TASK_PATH = REPO_ROOT / (
    "research_pipeline/examples/"
    "local-research-task.btc-utc-day-3pct-historical-post-shock-stability-audit.v1.json"
)
DISPATCH_PATH = REPO_ROOT / (
    "research_pipeline/examples/"
    "local-research-dispatch.btc-utc-day-3pct-historical-post-shock-stability-audit.v1.json"
)
RESULT_PATH = REPO_ROOT / (
    "research_pipeline/examples/"
    "local-research-result.btc-utc-day-3pct-historical-post-shock-stability-audit.v1.json"
)


class LocalSemanticClosureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        cls.closure = json.loads(CLOSURE_PATH.read_text(encoding="utf-8"))
        cls.result = json.loads(RESULT_PATH.read_text(encoding="utf-8"))

    def _validate(
        self,
        closure: dict[str, object],
        *,
        raw: bytes | None = None,
        result: dict[str, object] | None = None,
    ) -> dict[str, object]:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            closure_path = root / "closure.json"
            closure_path.write_bytes(raw if raw is not None else canonical_json_document_bytes(closure))
            result_path = RESULT_PATH
            if result is not None:
                result_path = root / "result.json"
                result_path.write_bytes(canonical_json_document_bytes(result))
            return load_and_validate_semantic_closure(
                closure_path,
                DISPATCH_PATH,
                TASK_PATH,
                result_path,
            )

    def test_official_schema_and_example_are_valid(self) -> None:
        if Draft202012Validator is None:
            self.skipTest("jsonschema is unavailable in the Local sandbox")
        Draft202012Validator.check_schema(self.schema)
        Draft202012Validator(self.schema).validate(self.closure)
        receipt = self._validate(self.closure)
        self.assertEqual("VALID", receipt["status"])
        self.assertEqual(DISPOSITION, receipt["disposition"])
        self.assertEqual(6, receipt["validated_rows"])

    def test_wrong_hash_bindings_fail_closed(self) -> None:
        for field in ("task_sha256", "dispatch_sha256", "result_sha256"):
            with self.subTest(field=field):
                closure = deepcopy(self.closure)
                closure["bindings"][field] = "0" * 64
                with self.assertRaisesRegex(ValueError, f"binding mismatch: {field}"):
                    self._validate(closure)

    def test_result_source_commit_drift_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["bindings"]["result_source_git_commit"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "result_source_git_commit"):
            self._validate(closure)

    def test_noncanonical_closure_fails_closed(self) -> None:
        raw = json.dumps(self.closure, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
        with self.assertRaisesRegex(ValueError, "canonical JSON bytes"):
            self._validate(self.closure, raw=raw)

    def test_duplicate_json_key_fails_closed(self) -> None:
        raw = b'{"schema_version":"1",' + CLOSURE_PATH.read_bytes()[1:]
        with self.assertRaisesRegex(ValueError, "duplicate JSON key: schema_version"):
            self._validate(self.closure, raw=raw)

    def test_wrong_year_basis_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["year_basis"] = "OUTCOME_YEAR"
        with self.assertRaisesRegex(ValueError, "TARGET_YEAR"):
            self._validate(closure)

    def test_reordered_year_rows_fail_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][0]["rows"][0], closure["partitions"][0]["rows"][1] = (
            closure["partitions"][0]["rows"][1],
            closure["partitions"][0]["rows"][0],
        )
        with self.assertRaisesRegex(ValueError, "ordered TARGET_YEAR"):
            self._validate(closure)

    def test_missing_year_row_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][1]["rows"].pop()
        with self.assertRaisesRegex(ValueError, "rows must match required_keys"):
            self._validate(closure)

    def test_row_direction_arithmetic_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][0]["rows"][0]["up_count"] += 1
        with self.assertRaisesRegex(ValueError, "direction arithmetic mismatch"):
            self._validate(closure)

    def test_row_label_arithmetic_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][0]["rows"][0]["tie_count"] += 1
        with self.assertRaisesRegex(ValueError, "label arithmetic mismatch"):
            self._validate(closure)

    def test_partition_total_drift_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][1]["totals"]["event_count"] += 1
        with self.assertRaisesRegex(ValueError, "totals do not reconcile"):
            self._validate(closure)

    def test_result_evidence_drift_fails_closed(self) -> None:
        result = deepcopy(self.result)
        check = next(item for item in result["checks"] if item["name"] == "ANNUAL_2024_TARGET_YEAR")
        check["evidence"] = "drifted evidence"
        closure = deepcopy(self.closure)
        closure["bindings"]["result_sha256"] = hashlib.sha256(
            canonical_json_document_bytes(result)
        ).hexdigest()
        with self.assertRaisesRegex(ValueError, "evidence drift"):
            self._validate(closure, result=result)

    def test_duplicate_result_check_name_fails_closed(self) -> None:
        result = deepcopy(self.result)
        result["checks"].append(deepcopy(result["checks"][-1]))
        closure = deepcopy(self.closure)
        closure["bindings"]["result_sha256"] = hashlib.sha256(
            canonical_json_document_bytes(result)
        ).hexdigest()
        with self.assertRaisesRegex(ValueError, "duplicate check name"):
            self._validate(closure, result=result)

    def test_nonnegative_median_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["partitions"][1]["rows"][1]["metric_value"] = "0"
        with self.assertRaisesRegex(ValueError, "ALL_REQUIRED_TARGET_YEAR_MEDIANS_NEGATIVE"):
            self._validate(closure)

    def test_disposition_mismatch_fails_closed(self) -> None:
        closure = deepcopy(self.closure)
        closure["disposition"] = "HISTORICAL_CONTINUATION_PRIOR_STABLE"
        with self.assertRaisesRegex(ValueError, "disposition mismatch"):
            self._validate(closure)

    def test_schema_rejects_extra_root_property(self) -> None:
        if Draft202012Validator is None:
            self.skipTest("jsonschema is unavailable in the Local sandbox")
        closure = deepcopy(self.closure)
        closure["unexpected"] = True
        errors = list(Draft202012Validator(self.schema).iter_errors(closure))
        self.assertTrue(errors)

    def test_top_level_cli_is_canonical_and_constructs_no_store(self) -> None:
        expected = load_and_validate_semantic_closure(
            CLOSURE_PATH,
            DISPATCH_PATH,
            TASK_PATH,
            RESULT_PATH,
        )
        with TemporaryDirectory() as temporary:
            state_dir = Path(temporary) / "must-not-exist"
            output = io.StringIO()
            with (
                patch("research_pipeline.cli.load_policy", side_effect=AssertionError("policy used")),
                patch("research_pipeline.cli.ResearchStore", side_effect=AssertionError("store used")),
                redirect_stdout(output),
            ):
                exit_code = main(
                    [
                        "--state-dir",
                        str(state_dir),
                        "validate-local-research-semantic-closure",
                        str(CLOSURE_PATH),
                        "--dispatch",
                        str(DISPATCH_PATH),
                        "--task",
                        str(TASK_PATH),
                        "--result",
                        str(RESULT_PATH),
                    ]
                )
            self.assertEqual(0, exit_code)
            self.assertEqual(
                canonical_json_bytes(expected).decode("utf-8") + "\n",
                output.getvalue(),
            )
            self.assertFalse(state_dir.exists())


if __name__ == "__main__":
    unittest.main()
