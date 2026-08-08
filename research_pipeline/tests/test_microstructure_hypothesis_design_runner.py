from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.microstructure_hypothesis_design import (
    NON_POSITIVE_DISPOSITIONS,
    POSITIVE_DISPOSITION,
    build_hypothesis_design_result_bytes,
    validate_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_hypothesis_design_runner import (
    EXPECTED_REPOSITORY_INPUTS,
    IMPLEMENTATION_FILES,
    OUTPUT_RESULT_NAME,
    OUTPUT_ROOT,
    PRODUCTION_PATHS,
    PROPOSAL_NAME,
    PROPOSAL_ROOT,
    PROPOSAL_SCHEMA_RELATIVE,
    PROPOSAL_SCHEMA_SHA256,
    REPOSITORY_ROOT,
    RUNNER_TASK_ID,
    RUNNER_TASK_RELATIVE,
    RUNNER_TASK_SHA256,
    SOURCE_RESULT_NAME,
    SOURCE_ROOT,
    HypothesisDesignRunnerBlocked,
    RuntimePaths,
    build_coach_proposal_envelope_bytes,
    main,
    run_hypothesis_design,
    validate_coach_proposal_envelope_bytes,
)
from research_pipeline.microstructure_interpretation import TIER_ORDER
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests import test_microstructure_hypothesis_design as design_fixture


PROPOSAL = {
    "design_id": "synthetic-runner-coach-design-v1",
    "created_at": "2026-08-08T01:00:00Z",
    "title": "Synthetic runner design",
    "thesis": "Caller-authored synthetic thesis for runner testing only.",
    "economic_rationale": "Caller-authored synthetic rationale for runner testing only.",
    "performance_thesis": "Caller-authored synthetic performance statement.",
    "drawdown_thesis": "Caller-authored synthetic drawdown statement.",
    "opportunity_cost": "Caller-authored synthetic opportunity-cost statement.",
}


def _parsed(raw: bytes) -> dict[str, object]:
    value = json.loads(raw.decode("utf-8"))
    assert isinstance(value, dict)
    return value


def _validate_draft_2020_12_fixture(
    schema: dict[str, object],
    value: object,
) -> None:
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(
        schema,
        format_checker=FormatChecker(),
    ).validate(value)


class MicrostructureHypothesisDesignRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        design_fixture.MicrostructureHypothesisDesignTest.setUpClass()
        cls.source = design_fixture.MicrostructureHypothesisDesignTest(
            methodName="test_all_four_dispositions_close_or_design_deterministically"
        )

    @classmethod
    def tearDownClass(cls) -> None:
        design_fixture.MicrostructureHypothesisDesignTest.tearDownClass()

    def _positive(self, tier: str = TIER_ORDER[0]) -> bytes:
        return self.source._positive(tier)

    def _non_positive(self) -> tuple[bytes, bytes, bytes]:
        return (
            self.source._interpretation(),
            self.source._interpretation(
                {TIER_ORDER[0]: (design_fixture.source_fixture.MIXED,) * 2}
            ),
            self.source._interpretation(insufficient=True),
        )

    @staticmethod
    def _write(path: Path, raw: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def _install(
        self,
        base: Path,
        *,
        interpretation_raw: bytes,
        proposal_raw: bytes | None,
        provision_output: bool = True,
    ) -> RuntimePaths:
        repository_root = base / "repository"
        required = (
            set(EXPECTED_REPOSITORY_INPUTS)
            | set(IMPLEMENTATION_FILES)
            | {RUNNER_TASK_RELATIVE}
        )
        for relative_name in required:
            source = REPOSITORY_ROOT.joinpath(*relative_name.split("/"))
            self._write(
                repository_root.joinpath(*relative_name.split("/")),
                source.read_bytes(),
            )
        source_root = base / "source"
        proposal_root = base / "proposal"
        output_root = base / "output"
        source_root.mkdir()
        proposal_root.mkdir()
        if provision_output:
            output_root.mkdir()
        self._write(source_root / SOURCE_RESULT_NAME, interpretation_raw)
        if proposal_raw is not None:
            self._write(proposal_root / PROPOSAL_NAME, proposal_raw)
        return RuntimePaths(
            repository_root=repository_root,
            source_root=source_root,
            proposal_root=proposal_root,
            output_root=output_root,
        )

    @staticmethod
    def _inventory(root: Path) -> dict[str, bytes]:
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file() and not path.is_symlink()
        }

    def test_zero_argument_fixed_paths_and_return_surface_are_exact(self) -> None:
        self.assertEqual(REPOSITORY_ROOT, PRODUCTION_PATHS.repository_root)
        self.assertEqual(SOURCE_ROOT, PRODUCTION_PATHS.source_root)
        self.assertEqual(PROPOSAL_ROOT, PRODUCTION_PATHS.proposal_root)
        self.assertEqual(OUTPUT_ROOT, PRODUCTION_PATHS.output_root)
        self.assertEqual(
            SOURCE_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3-interpretation-runner-v2",
        )
        self.assertEqual(
            PROPOSAL_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/inbox/"
            "local-node-microstructure-v3-hypothesis-design-runner-v3",
        )
        self.assertEqual(
            OUTPUT_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3-hypothesis-design-runner-v3",
        )
        with patch(
            "research_pipeline.microstructure_hypothesis_design_runner.run_hypothesis_design",
            return_value={"status": "CREATED"},
        ) as mocked, redirect_stdout(io.StringIO()):
            self.assertEqual(main([]), 0)
            mocked.assert_called_once_with(PRODUCTION_PATHS)
            mocked.reset_mock()
            self.assertEqual(main(["--proposal", "elsewhere"]), 2)
            mocked.assert_not_called()

    def test_exact_task_repository_contract_and_implementation_inventory(self) -> None:
        task_raw = REPOSITORY_ROOT.joinpath(*RUNNER_TASK_RELATIVE.split("/")).read_bytes()
        task = _parsed(task_raw)
        listed = {
            item["locator"]: item["sha256"]
            for item in task["inputs"]
            if item["kind"] == "REPOSITORY_PATH"
        }
        self.assertEqual(RUNNER_TASK_SHA256, hashlib.sha256(task_raw).hexdigest())
        self.assertEqual(RUNNER_TASK_ID, task["task_id"])
        self.assertEqual(EXPECTED_REPOSITORY_INPUTS, listed)
        self.assertEqual(
            (
                "research_pipeline/microstructure-coach-hypothesis-proposal.v1.schema.json",
                "research_pipeline/microstructure_hypothesis_design_runner.py",
            ),
            IMPLEMENTATION_FILES,
        )
        self.assertEqual(10, len(EXPECTED_REPOSITORY_INPUTS))
        self.assertEqual(
            {
                "research_pipeline/local_node.py",
                "research_pipeline/policy.v3.json",
                "research_pipeline/microstructure_source_contract.py",
                "research_pipeline/microstructure_interpretation.py",
                "research_pipeline/microstructure-interpretation-result.v1.schema.json",
                "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json",
                "research_pipeline/microstructure_hypothesis_design.py",
                "research_pipeline/okx-microstructure-hypothesis-design-contract.v1.json",
                "research_pipeline/microstructure-hypothesis-design-result.v1.schema.json",
                "research_pipeline/microstructure-coach-hypothesis-proposal.v1.schema.json",
            },
            set(EXPECTED_REPOSITORY_INPUTS),
        )
        prohibited = {
            "research_pipeline/microstructure_handoff_runner.py",
            "research_pipeline/microstructure_interpretation_runner.py",
            "research_pipeline/examples/local-research-task.microstructure-v3-interpretation-runner.v2.json",
            "research_pipeline/tests/test_microstructure_hypothesis_design_runner.py",
            "docs/okx-microstructure-hypothesis-design-runner-v3.md",
        }
        self.assertTrue(prohibited.isdisjoint(EXPECTED_REPOSITORY_INPUTS))
        historical = {
            "research_pipeline/examples/local-research-task.microstructure-v3-hypothesis-design-runner.v1.json": (
                "fd0e4270f5f459b35e986f1e46f6aace568dc9b14a23ecfd82e8d342f1a97dc2"
            ),
            "docs/okx-microstructure-hypothesis-design-runner-v1.md": (
                "5bafd8f0ce14948bf13872e43f90e61008ca927e7a1f5991d04a1e2000520bbb"
            ),
            "research_pipeline/examples/local-research-task.microstructure-v3-hypothesis-design-runner.v2.json": (
                "7224171c14252fd0b6e0e0c14e0a30820fdc614fcf47e107b1836af3910f9114"
            ),
            "docs/okx-microstructure-hypothesis-design-runner-v2.md": (
                "9c62ff0427428752037aaf77b17f0e282883bff3ff32c471e882b77741176c5a"
            ),
        }
        for relative_name, expected_hash in historical.items():
            self.assertEqual(
                expected_hash,
                hashlib.sha256(
                    REPOSITORY_ROOT.joinpath(*relative_name.split("/")).read_bytes()
                ).hexdigest(),
            )

    def test_proposal_and_result_schemas_validate_generated_positive_fixture(self) -> None:
        interpretation = self._positive(TIER_ORDER[1])
        envelope_raw = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        envelope = validate_coach_proposal_envelope_bytes(
            envelope_raw, interpretation
        )
        schema_path = REPOSITORY_ROOT.joinpath(*PROPOSAL_SCHEMA_RELATIVE.split("/"))
        schema_raw = schema_path.read_bytes()
        self.assertEqual(PROPOSAL_SCHEMA_SHA256, hashlib.sha256(schema_raw).hexdigest())
        proposal_schema = _parsed(schema_raw)
        result_schema = _parsed(
            (REPOSITORY_ROOT / "research_pipeline" / "microstructure-hypothesis-design-result.v1.schema.json").read_bytes()
        )
        _validate_draft_2020_12_fixture(proposal_schema, envelope)
        result_raw = build_hypothesis_design_result_bytes(
            interpretation, envelope["coach_proposal"]
        )
        _validate_draft_2020_12_fixture(result_schema, _parsed(result_raw))

    def test_all_four_interpretation_dispositions(self) -> None:
        cases = ((self._positive(), POSITIVE_DISPOSITION),) + tuple(
            (raw, disposition)
            for raw, disposition in zip(
                self._non_positive(),
                (
                    "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
                    "AMBIGUOUS_NO_HYPOTHESIS",
                    "INSUFFICIENT_FORWARD_EVIDENCE",
                ),
            )
        )
        for interpretation, disposition in cases:
            with self.subTest(disposition=disposition), TemporaryDirectory() as directory:
                proposal = (
                    build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
                    if disposition == POSITIVE_DISPOSITION
                    else None
                )
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal,
                )
                result = run_hypothesis_design(paths)
                raw = (paths.output_root / OUTPUT_RESULT_NAME).read_bytes()
                parsed = validate_hypothesis_design_result_bytes(raw, interpretation)
                self.assertEqual("CREATED", result["status"])
                self.assertEqual(disposition, result["source_disposition"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["sha256"])
                if disposition == POSITIVE_DISPOSITION:
                    self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", result["design_status"])
                    self.assertEqual(PROPOSAL["design_id"], result["design_id"])
                    self.assertIsNotNone(parsed["hypothesis_design"])
                else:
                    self.assertEqual("CLOSED_NO_HYPOTHESIS_DESIGN", result["design_status"])
                    self.assertIsNone(result["design_id"])
                    self.assertIsNone(parsed["hypothesis_design"])
                self.assertEqual(
                    {
                        "status",
                        "result",
                        "sha256",
                        "source_disposition",
                        "design_status",
                        "design_id",
                    },
                    set(result),
                )

    def test_positive_proposal_inventory_and_bytes_fail_closed(self) -> None:
        interpretation = self._positive()
        valid = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        other_source = self._positive(TIER_ORDER[1])
        mismatched = build_coach_proposal_envelope_bytes(other_source, PROPOSAL)
        seal_changed = _parsed(valid)
        seal_changed["seal"]["payload_sha256"] = "0" * 64
        cases = {
            "missing": None,
            "malformed": b"{",
            "source_mismatched": mismatched,
            "seal_mismatched": canonical_json_bytes(seal_changed),
        }
        for mode, proposal_raw in cases.items():
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal_raw,
                )
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        for mode, extra_name in (("multiple", "coach-proposal-copy.json"), ("extra", "note.txt")):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=valid,
                )
                (paths.proposal_root / extra_name).write_bytes(valid if mode == "multiple" else b"x")
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_non_positive_requires_empty_proposal_root(self) -> None:
        for interpretation in self._non_positive():
            with TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=None,
                )
                self.assertEqual("CREATED", run_hypothesis_design(paths)["status"])
            with TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=b"{}",
                )
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_selected_tier_binding_and_deterministic_immutable_inputs(self) -> None:
        interpretation = self._positive(TIER_ORDER[2])
        proposal = deepcopy(PROPOSAL)
        interpretation_before = bytes(interpretation)
        proposal_before = deepcopy(proposal)
        first = build_coach_proposal_envelope_bytes(interpretation, proposal)
        second = build_coach_proposal_envelope_bytes(interpretation, proposal)
        envelope = validate_coach_proposal_envelope_bytes(first, interpretation)
        self.assertEqual(first, second)
        self.assertEqual(TIER_ORDER[2], envelope["source_interpretation"]["selected_tier"])
        self.assertEqual(interpretation_before, interpretation)
        self.assertEqual(proposal_before, proposal)
        self.assertFalse(any(envelope["safety_assertions"].values()))

    def test_task_repository_source_and_proposal_concurrent_drift(self) -> None:
        interpretation = self._positive()
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        for mode in ("task", "repository", "runner", "schema", "source", "proposal"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=envelope,
                )

                def mutating_builder(raw, proposal):
                    result = build_hypothesis_design_result_bytes(raw, proposal)
                    if mode == "task":
                        target = paths.repository_root.joinpath(*RUNNER_TASK_RELATIVE.split("/"))
                    elif mode == "repository":
                        target = paths.repository_root / "research_pipeline" / "policy.v3.json"
                    elif mode == "runner":
                        target = paths.repository_root / IMPLEMENTATION_FILES[1]
                    elif mode == "schema":
                        target = paths.repository_root / IMPLEMENTATION_FILES[0]
                    elif mode == "source":
                        target = paths.source_root / SOURCE_RESULT_NAME
                    else:
                        target = paths.proposal_root / PROPOSAL_NAME
                    target.write_bytes(target.read_bytes() + b" ")
                    return result

                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths, design_builder=mutating_builder)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_builder_failure_or_changed_proposal_creates_no_output(self) -> None:
        interpretation = self._positive()
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=interpretation, proposal_raw=envelope
            )

            def failing_builder(_raw, _proposal):
                raise RuntimeError("synthetic failure")

            with self.assertRaisesRegex(HypothesisDesignRunnerBlocked, "design builder failed"):
                run_hypothesis_design(paths, design_builder=failing_builder)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=interpretation, proposal_raw=envelope
            )
            changed = {**PROPOSAL, "design_id": "different-synthetic-design-v1"}
            with self.assertRaisesRegex(HypothesisDesignRunnerBlocked, "changed the Coach proposal"):
                run_hypothesis_design(
                    paths,
                    design_builder=lambda raw, _proposal: build_hypothesis_design_result_bytes(raw, changed),
                )
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_unprovisioned_extra_partial_and_conflicting_output_fail_closed(self) -> None:
        interpretation = self._positive()
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        for mode in ("unprovisioned", "extra", "partial", "conflicting"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=envelope,
                    provision_output=mode != "unprovisioned",
                )
                if mode == "extra":
                    (paths.output_root / "extra.json").write_bytes(b"{}")
                elif mode == "partial":
                    (paths.output_root / OUTPUT_RESULT_NAME).write_bytes(b"{")
                elif mode == "conflicting":
                    (paths.output_root / OUTPUT_RESULT_NAME).write_bytes(b"{}")
                before = self._inventory(paths.output_root) if paths.output_root.exists() else {}
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths)
                after = self._inventory(paths.output_root) if paths.output_root.exists() else {}
                self.assertEqual(before, after)

    def test_exact_retry_and_input_immutability(self) -> None:
        interpretation = self._positive(TIER_ORDER[1])
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=interpretation, proposal_raw=envelope
            )
            source_before = self._inventory(paths.source_root)
            proposal_before = self._inventory(paths.proposal_root)
            first = run_hypothesis_design(paths)
            output = paths.output_root / OUTPUT_RESULT_NAME
            raw = output.read_bytes()
            modified = output.stat().st_mtime_ns
            second = run_hypothesis_design(paths)
            self.assertEqual("CREATED", first["status"])
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            self.assertEqual(raw, output.read_bytes())
            self.assertEqual(modified, output.stat().st_mtime_ns)
            self.assertEqual(source_before, self._inventory(paths.source_root))
            self.assertEqual(proposal_before, self._inventory(paths.proposal_root))

    def test_linked_source_proposal_or_output_is_rejected_where_supported(self) -> None:
        interpretation = self._positive()
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        for mode in ("source", "proposal", "output"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory), interpretation_raw=interpretation, proposal_raw=envelope
                )
                if mode == "source":
                    target = Path(directory) / "source-target.json"
                    target.write_bytes(interpretation)
                    (paths.source_root / SOURCE_RESULT_NAME).unlink()
                    link = paths.source_root / SOURCE_RESULT_NAME
                elif mode == "proposal":
                    target = Path(directory) / "proposal-target.json"
                    target.write_bytes(envelope)
                    (paths.proposal_root / PROPOSAL_NAME).unlink()
                    link = paths.proposal_root / PROPOSAL_NAME
                else:
                    target = Path(directory) / "output-target.json"
                    target.write_bytes(b"{}")
                    link = paths.output_root / OUTPUT_RESULT_NAME
                try:
                    os.symlink(target, link)
                except OSError as error:
                    self.skipTest(f"symlink unavailable: {error}")
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design(paths)
                self.assertEqual(
                    interpretation if mode == "source" else envelope if mode == "proposal" else b"{}",
                    target.read_bytes(),
                )

    def test_roots_must_be_distinct_and_non_overlapping(self) -> None:
        interpretation = self._positive()
        envelope = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=interpretation, proposal_raw=envelope
            )
            cases = (
                RuntimePaths(paths.repository_root, paths.source_root, paths.source_root, paths.output_root),
                RuntimePaths(paths.repository_root, paths.source_root, paths.proposal_root, paths.proposal_root),
                RuntimePaths(paths.repository_root, paths.repository_root / "source", paths.proposal_root, paths.output_root),
            )
            for changed in cases:
                with self.subTest(paths=changed), self.assertRaisesRegex(
                    HypothesisDesignRunnerBlocked, "roots must (differ|not overlap)"
                ):
                    run_hypothesis_design(changed)


if __name__ == "__main__":
    unittest.main()
