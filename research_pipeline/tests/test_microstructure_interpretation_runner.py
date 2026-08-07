from __future__ import annotations

from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_CONTRACT_RELATIVE,
    EXPECTED_REPOSITORY_INPUTS as SOURCE_REPOSITORY_INPUTS,
    RESULT_NAME as SOURCE_RESULT_NAME,
    _validate_fixed_package,
    run_handoff,
)
from research_pipeline.microstructure_interpretation import (
    TIER_ORDER,
    interpret_handoff_result_bytes,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_interpretation_runner import (
    EXPECTED_REPOSITORY_INPUTS,
    OUTPUT_RESULT_NAME,
    OUTPUT_ROOT,
    PRODUCTION_PATHS,
    REPOSITORY_ROOT,
    RUNNER_TASK_ID,
    RUNNER_TASK_RELATIVE,
    RUNNER_TASK_SHA256,
    SOURCE_ROOT,
    InterpretationRunnerBlocked,
    RuntimePaths,
    main,
    run_interpretation,
)
from research_pipeline.tests.test_microstructure_handoff_contract import (
    _diagnostic_result,
)
from research_pipeline.tests.test_microstructure_handoff_runner import (
    _Fixture,
)
from research_pipeline.tests.test_microstructure_interpretation import (
    MIXED,
    NEGATIVE,
    POSITIVE,
    _parsed,
    _reseal_handoff,
    _set_screen_metrics,
)


class MicrostructureInterpretationRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = _Fixture()
        with TemporaryDirectory() as directory:
            source_paths = cls.fixture.install(Path(directory))
            context, _observed = _validate_fixed_package(source_paths)
            diagnostic = _diagnostic_result(context)
            run_handoff(
                source_paths,
                analyzer=lambda *_args, **_kwargs: diagnostic,
            )
            cls.insufficient_raw = (
                source_paths.task_owned_root / SOURCE_RESULT_NAME
            ).read_bytes()

    @staticmethod
    def _write(path: Path, raw: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def _ready_raw(
        self,
        states: dict[str, tuple[tuple[str, str, str], tuple[str, str, str]]] | None = None,
    ) -> bytes:
        handoff = _parsed(self.insufficient_raw)
        diagnostic = handoff["diagnostic_result"]
        diagnostic["status"] = "FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION"
        states = states or {}
        for tier_name in TIER_ORDER:
            tier = diagnostic["tiers"][tier_name]
            tier["gates"] = {
                "minimum_30_events": True,
                "minimum_10_events_first_seven_days": True,
                "minimum_10_events_second_seven_days": True,
                "minimum_80_pct_matched_controls": True,
            }
            tier["gate_status"] = "PASS"
            confirmatory, primary = states.get(tier_name, (NEGATIVE, NEGATIVE))
            _set_screen_metrics(
                tier["metrics_by_horizon_minutes"]["15"], confirmatory
            )
            _set_screen_metrics(
                tier["metrics_by_horizon_minutes"]["60"], primary
            )
        return _reseal_handoff(handoff)

    def _install(
        self,
        base: Path,
        *,
        source_result: bytes | None = None,
        provision_output: bool = True,
    ) -> RuntimePaths:
        source_paths = self.fixture.install(base)
        repository_root = source_paths.repository_root
        required = (
            set(EXPECTED_REPOSITORY_INPUTS)
            | set(SOURCE_REPOSITORY_INPUTS)
            | {RUNNER_TASK_RELATIVE, DIAGNOSTIC_CONTRACT_RELATIVE}
        )
        for relative_name in required:
            source = REPOSITORY_ROOT.joinpath(*relative_name.split("/"))
            self._write(
                repository_root.joinpath(*relative_name.split("/")),
                source.read_bytes(),
            )
        self._write(
            source_paths.task_owned_root / SOURCE_RESULT_NAME,
            self.insufficient_raw if source_result is None else source_result,
        )
        output_root = base / "outbox" / RUNNER_TASK_ID
        if provision_output:
            output_root.mkdir(parents=True)
        return RuntimePaths(
            repository_root=repository_root,
            source_root=source_paths.task_owned_root,
            output_root=output_root,
        )

    @staticmethod
    def _inventory(root: Path) -> dict[str, bytes]:
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file() and not path.is_symlink()
        }

    def test_zero_argument_production_paths_are_exact(self) -> None:
        self.assertEqual(REPOSITORY_ROOT, PRODUCTION_PATHS.repository_root)
        self.assertEqual(SOURCE_ROOT, PRODUCTION_PATHS.source_root)
        self.assertEqual(OUTPUT_ROOT, PRODUCTION_PATHS.output_root)
        self.assertEqual(
            SOURCE_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/inbox/"
            "local-node-microstructure-v3-evidence-diagnostic-v1",
        )
        self.assertEqual(
            OUTPUT_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3-interpretation-runner-v1",
        )
        with patch(
            "research_pipeline.microstructure_interpretation_runner.run_interpretation",
            return_value={"status": "CREATED"},
        ) as mocked, redirect_stdout(io.StringIO()):
            self.assertEqual(main([]), 0)
            mocked.assert_called_once_with(PRODUCTION_PATHS)
            mocked.reset_mock()
            self.assertEqual(main(["--source", "elsewhere"]), 2)
            mocked.assert_not_called()

    def test_exact_task_repository_source_and_schema_valid_output(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            result = run_interpretation(paths)
            output = paths.output_root / OUTPUT_RESULT_NAME
            raw = output.read_bytes()
            parsed = validate_interpretation_result_bytes(raw)
            self.assertEqual("CREATED", result["status"])
            self.assertEqual(OUTPUT_RESULT_NAME, result["result"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), result["sha256"])
            self.assertEqual(parsed["disposition"], result["disposition"])
            self.assertEqual(
                "INSUFFICIENT_FORWARD_EVIDENCE", result["disposition"]
            )
            self.assertEqual(
                RUNNER_TASK_SHA256,
                hashlib.sha256(
                    paths.repository_root.joinpath(
                        *RUNNER_TASK_RELATIVE.split("/")
                    ).read_bytes()
                ).hexdigest(),
            )

    def test_all_four_frozen_dispositions(self) -> None:
        cases = {
            "insufficient": (
                self.insufficient_raw,
                "INSUFFICIENT_FORWARD_EVIDENCE",
            ),
            "ready": (
                self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)}),
                "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            ),
            "no_candidate": (
                self._ready_raw(),
                "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
            ),
            "ambiguous": (
                self._ready_raw({TIER_ORDER[0]: (MIXED, MIXED)}),
                "AMBIGUOUS_NO_HYPOTHESIS",
            ),
        }
        for name, (source_result, expected) in cases.items():
            with self.subTest(name=name), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory), source_result=source_result
                )
                result = run_interpretation(paths)
                self.assertEqual(expected, result["disposition"])
                self.assertEqual(
                    expected,
                    validate_interpretation_result_bytes(
                        (paths.output_root / OUTPUT_RESULT_NAME).read_bytes()
                    )["disposition"],
                )

    def test_exact_retry_is_idempotent_and_source_is_unchanged(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                source_result=self._ready_raw(
                    {TIER_ORDER[1]: (POSITIVE, POSITIVE)}
                ),
            )
            source_before = self._inventory(paths.source_root)
            first = run_interpretation(paths)
            output = paths.output_root / OUTPUT_RESULT_NAME
            raw = output.read_bytes()
            modified = output.stat().st_mtime_ns
            second = run_interpretation(paths)
            self.assertEqual("CREATED", first["status"])
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            self.assertEqual(raw, output.read_bytes())
            self.assertEqual(modified, output.stat().st_mtime_ns)
            self.assertEqual(source_before, self._inventory(paths.source_root))

    def test_missing_result_task_and_repository_drift_create_no_output(self) -> None:
        cases = ("missing_result", "task", "repository")
        for mode in cases:
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                if mode == "missing_result":
                    (paths.source_root / SOURCE_RESULT_NAME).unlink()
                elif mode == "task":
                    task_path = paths.repository_root.joinpath(
                        *RUNNER_TASK_RELATIVE.split("/")
                    )
                    task_path.write_bytes(task_path.read_bytes() + b" ")
                else:
                    relative_name = "research_pipeline/microstructure_interpretation.py"
                    target = paths.repository_root.joinpath(
                        *relative_name.split("/")
                    )
                    target.write_bytes(target.read_bytes() + b"\n")
                with self.assertRaises(InterpretationRunnerBlocked):
                    run_interpretation(paths)
                self.assertFalse(
                    (paths.output_root / OUTPUT_RESULT_NAME).exists()
                )

    def test_source_result_and_package_drift_create_no_output(self) -> None:
        for mode in ("result", "package"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                if mode == "result":
                    target = paths.source_root / SOURCE_RESULT_NAME
                    target.write_bytes(target.read_bytes() + b" ")
                else:
                    (paths.source_root / "extra.json").write_bytes(b"{}")
                with self.assertRaises(InterpretationRunnerBlocked):
                    run_interpretation(paths)
                self.assertFalse(
                    (paths.output_root / OUTPUT_RESULT_NAME).exists()
                )

    def test_post_interpretation_source_mutation_is_detected_before_output(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))

            def mutating_interpreter(raw, context):
                result = interpret_handoff_result_bytes(raw, context)
                (paths.source_root / SOURCE_RESULT_NAME).write_bytes(raw + b" ")
                return result

            with self.assertRaises(InterpretationRunnerBlocked):
                run_interpretation(paths, interpreter=mutating_interpreter)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_post_interpretation_repository_mutation_is_detected_before_output(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))

            def mutating_interpreter(raw, context):
                result = interpret_handoff_result_bytes(raw, context)
                relative_name = "research_pipeline/microstructure_interpretation.py"
                target = paths.repository_root.joinpath(*relative_name.split("/"))
                target.write_bytes(target.read_bytes() + b"\n")
                return result

            with self.assertRaises(InterpretationRunnerBlocked):
                run_interpretation(paths, interpreter=mutating_interpreter)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_interpreter_failure_or_wrong_binding_creates_no_output(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))

            def failing_interpreter(_raw, _context):
                raise RuntimeError("synthetic failure")

            with self.assertRaisesRegex(
                InterpretationRunnerBlocked, "interpreter failed"
            ):
                run_interpretation(paths, interpreter=failing_interpreter)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        with TemporaryDirectory() as first_directory, TemporaryDirectory() as second_directory:
            first = self._install(Path(first_directory))
            second = self._install(
                Path(second_directory),
                source_result=self._ready_raw(
                    {TIER_ORDER[0]: (POSITIVE, POSITIVE)}
                ),
            )
            second_context, _observed = _validate_fixed_package(
                type("Paths", (), {
                    "repository_root": second.repository_root,
                    "task_owned_root": second.source_root,
                })()
            )
            other = interpret_handoff_result_bytes(
                (second.source_root / SOURCE_RESULT_NAME).read_bytes(),
                second_context,
            )
            with self.assertRaisesRegex(
                InterpretationRunnerBlocked, "source binding"
            ):
                run_interpretation(first, interpreter=lambda *_args: other)
            self.assertFalse((first.output_root / OUTPUT_RESULT_NAME).exists())

    def test_unprovisioned_extra_partial_and_conflicting_output_fail_closed(self) -> None:
        for mode in ("unprovisioned", "extra", "partial", "conflicting"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    provision_output=mode != "unprovisioned",
                )
                if mode == "extra":
                    (paths.output_root / "extra.json").write_bytes(b"{}")
                elif mode == "partial":
                    (paths.output_root / OUTPUT_RESULT_NAME).write_bytes(b"{")
                elif mode == "conflicting":
                    (paths.output_root / OUTPUT_RESULT_NAME).write_bytes(b"{}")
                before = (
                    self._inventory(paths.output_root)
                    if paths.output_root.exists()
                    else {}
                )
                with self.assertRaises(InterpretationRunnerBlocked):
                    run_interpretation(paths)
                after = (
                    self._inventory(paths.output_root)
                    if paths.output_root.exists()
                    else {}
                )
                self.assertEqual(before, after)

    def test_linked_source_or_output_is_rejected_where_supported(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            link = paths.source_root / "linked.json"
            try:
                os.symlink(paths.source_root / SOURCE_RESULT_NAME, link)
            except OSError as error:
                self.skipTest(f"symlink unavailable: {error}")
            with self.assertRaises(InterpretationRunnerBlocked):
                run_interpretation(paths)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            target = Path(directory) / "outside.json"
            target.write_bytes(b"{}")
            os.symlink(target, paths.output_root / OUTPUT_RESULT_NAME)
            with self.assertRaises(InterpretationRunnerBlocked):
                run_interpretation(paths)
            self.assertEqual(b"{}", target.read_bytes())

    def test_source_and_output_roots_must_be_separate(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            cases = (
                RuntimePaths(
                    repository_root=paths.repository_root,
                    source_root=paths.source_root,
                    output_root=paths.source_root,
                ),
                RuntimePaths(
                    repository_root=paths.repository_root,
                    source_root=paths.source_root,
                    output_root=paths.repository_root / "outbox",
                ),
                RuntimePaths(
                    repository_root=paths.repository_root,
                    source_root=paths.repository_root / "source",
                    output_root=paths.output_root,
                ),
            )
            for overlapping in cases:
                with self.subTest(overlapping=overlapping), self.assertRaisesRegex(
                    InterpretationRunnerBlocked, "roots must (differ|not overlap)"
                ):
                    run_interpretation(overlapping)


if __name__ == "__main__":
    unittest.main()
