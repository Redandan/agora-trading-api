from __future__ import annotations

from contextlib import redirect_stdout
import hashlib
import io
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_hypothesis_design import (
    POSITIVE_DISPOSITION,
    validate_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_hypothesis_design_runner import (
    OUTPUT_RESULT_NAME,
    PROPOSAL_NAME,
    SOURCE_RESULT_NAME,
    HypothesisDesignRunnerBlocked,
    RuntimePaths,
    build_coach_proposal_envelope_bytes,
)
from research_pipeline.microstructure_hypothesis_design_runner_v3r1 import (
    EXPECTED_REPOSITORY_INPUTS,
    IMPLEMENTATION_FILES,
    OUTPUT_ROOT,
    PRODUCTION_PATHS,
    PROPOSAL_ROOT,
    REPOSITORY_ROOT,
    RUNNER_TASK_ID,
    RUNNER_TASK_RELATIVE,
    RUNNER_TASK_SHA256,
    SOURCE_ROOT,
    main,
    run_hypothesis_design_v3r1,
)
from research_pipeline.microstructure_interpretation import TIER_ORDER
from research_pipeline.tests import test_microstructure_hypothesis_design as design_fixture


PROPOSAL = {
    "design_id": "synthetic-v3r1-coach-design-v1",
    "created_at": "2026-08-15T04:51:43Z",
    "title": "Synthetic V3R1 runner design",
    "thesis": "Synthetic thesis for V3R1 runner testing only.",
    "economic_rationale": "Synthetic economic rationale for testing only.",
    "performance_thesis": "Synthetic performance statement for testing only.",
    "drawdown_thesis": "Synthetic drawdown statement for testing only.",
    "opportunity_cost": "Synthetic opportunity-cost statement for testing only.",
}


class MicrostructureHypothesisDesignRunnerV3R1Test(unittest.TestCase):
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

    def test_fixed_v3r1_paths_and_zero_argument_entrypoint(self) -> None:
        self.assertEqual(REPOSITORY_ROOT, PRODUCTION_PATHS.repository_root)
        self.assertEqual(SOURCE_ROOT, PRODUCTION_PATHS.source_root)
        self.assertEqual(PROPOSAL_ROOT, PRODUCTION_PATHS.proposal_root)
        self.assertEqual(OUTPUT_ROOT, PRODUCTION_PATHS.output_root)
        self.assertEqual(
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3r1-interpretation-runner-v1",
            SOURCE_ROOT.as_posix(),
        )
        self.assertEqual(
            "C:/Users/Redan/.codex/local-research-node/inbox/"
            "local-node-microstructure-v3r1-hypothesis-design-runner-v1",
            PROPOSAL_ROOT.as_posix(),
        )
        self.assertEqual(
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3r1-hypothesis-design-runner-v1",
            OUTPUT_ROOT.as_posix(),
        )
        with patch(
            "research_pipeline.microstructure_hypothesis_design_runner_v3r1."
            "run_hypothesis_design_v3r1",
            return_value={"status": "CREATED"},
        ) as mocked, redirect_stdout(io.StringIO()):
            self.assertEqual(0, main([]))
            mocked.assert_called_once_with(PRODUCTION_PATHS)
            mocked.reset_mock()
            self.assertEqual(2, main(["--source", "elsewhere"]))
            mocked.assert_not_called()

    def test_self_task_and_repository_bindings_are_exact(self) -> None:
        task_path = REPOSITORY_ROOT.joinpath(*RUNNER_TASK_RELATIVE.split("/"))
        task_raw = task_path.read_bytes()
        self.assertEqual(RUNNER_TASK_SHA256, hashlib.sha256(task_raw).hexdigest())
        self.assertIn(RUNNER_TASK_ID.encode("utf-8"), task_raw)
        self.assertEqual(12, len(EXPECTED_REPOSITORY_INPUTS))
        self.assertIn(
            "research_pipeline/microstructure_interpretation_runner_v3r1.py",
            EXPECTED_REPOSITORY_INPUTS,
        )
        self.assertIn(
            "research_pipeline/examples/"
            "local-research-task.microstructure-v3r1-interpretation-runner.v1.json",
            EXPECTED_REPOSITORY_INPUTS,
        )
        self.assertNotIn(
            "research_pipeline/microstructure_hypothesis_design_runner.py",
            EXPECTED_REPOSITORY_INPUTS,
        )

    def test_all_four_dispositions_finish_in_one_design_step(self) -> None:
        cases = ((self._positive(), POSITIVE_DISPOSITION),) + tuple(
            zip(
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
                result = run_hypothesis_design_v3r1(paths)
                raw = (paths.output_root / OUTPUT_RESULT_NAME).read_bytes()
                parsed = validate_hypothesis_design_result_bytes(raw, interpretation)
                self.assertEqual("CREATED", result["status"])
                self.assertEqual(disposition, result["source_disposition"])
                if disposition == POSITIVE_DISPOSITION:
                    self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", result["design_status"])
                    self.assertEqual(PROPOSAL["design_id"], result["design_id"])
                    self.assertIsNotNone(parsed["hypothesis_design"])
                else:
                    self.assertEqual("CLOSED_NO_HYPOTHESIS_DESIGN", result["design_status"])
                    self.assertIsNone(result["design_id"])
                    self.assertIsNone(parsed["hypothesis_design"])

    def test_positive_requires_bound_proposal_and_non_positive_requires_empty_root(self) -> None:
        positive = self._positive()
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=positive, proposal_raw=None
            )
            with self.assertRaises(HypothesisDesignRunnerBlocked):
                run_hypothesis_design_v3r1(paths)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        non_positive = self._non_positive()[0]
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory), interpretation_raw=non_positive, proposal_raw=b"{}"
            )
            with self.assertRaises(HypothesisDesignRunnerBlocked):
                run_hypothesis_design_v3r1(paths)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_task_source_proposal_and_implementation_drift_fail_before_output(self) -> None:
        interpretation = self._positive()
        proposal = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        for mode in ("task", "source", "proposal", "implementation"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal,
                )

                def mutating_builder(raw, inner_proposal):
                    result = design_fixture.build_hypothesis_design_result_bytes(
                        raw, inner_proposal
                    )
                    if mode == "task":
                        target = paths.repository_root.joinpath(
                            *RUNNER_TASK_RELATIVE.split("/")
                        )
                    elif mode == "source":
                        target = paths.source_root / SOURCE_RESULT_NAME
                    elif mode == "proposal":
                        target = paths.proposal_root / PROPOSAL_NAME
                    else:
                        target = paths.repository_root.joinpath(
                            *IMPLEMENTATION_FILES[1].split("/")
                        )
                    target.write_bytes(target.read_bytes() + b" ")
                    return result

                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design_v3r1(
                        paths,
                        design_builder=mutating_builder,
                    )
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_create_once_idempotency_and_conflicting_inventory(self) -> None:
        interpretation = self._positive(TIER_ORDER[1])
        proposal = build_coach_proposal_envelope_bytes(interpretation, PROPOSAL)
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                interpretation_raw=interpretation,
                proposal_raw=proposal,
            )
            source_before = self._inventory(paths.source_root)
            proposal_before = self._inventory(paths.proposal_root)
            first = run_hypothesis_design_v3r1(paths)
            output = paths.output_root / OUTPUT_RESULT_NAME
            raw = output.read_bytes()
            modified = output.stat().st_mtime_ns
            second = run_hypothesis_design_v3r1(paths)
            self.assertEqual("CREATED", first["status"])
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            self.assertEqual(raw, output.read_bytes())
            self.assertEqual(modified, output.stat().st_mtime_ns)
            self.assertEqual(source_before, self._inventory(paths.source_root))
            self.assertEqual(proposal_before, self._inventory(paths.proposal_root))

        for name, raw in (("extra.json", b"{}"), (OUTPUT_RESULT_NAME, b"{}")):
            with self.subTest(name=name), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal,
                )
                (paths.output_root / name).write_bytes(raw)
                before = self._inventory(paths.output_root)
                with self.assertRaises(HypothesisDesignRunnerBlocked):
                    run_hypothesis_design_v3r1(paths)
                self.assertEqual(before, self._inventory(paths.output_root))

    def test_active_chain_still_stops_at_terminal_interpretation(self) -> None:
        script = (
            REPOSITORY_ROOT / "scripts/verify_local_research_active_chain.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "local-node-microstructure-v3r1-interpretation-runner-v1",
            script,
        )
        self.assertNotIn(RUNNER_TASK_ID, script)


if __name__ == "__main__":
    unittest.main()
