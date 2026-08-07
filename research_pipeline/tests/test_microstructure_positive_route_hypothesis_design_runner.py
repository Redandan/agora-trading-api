from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
import hashlib
import inspect
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.microstructure_interpretation import TIER_ORDER
from research_pipeline.microstructure_positive_route_hypothesis_design import (
    NON_POSITIVE_DISPOSITIONS,
    POSITIVE_DISPOSITION,
    PROPOSAL_FIELDS,
    ROUTE_CONTRACT_SHA256,
    ROUTE_ID,
    build_positive_route_hypothesis_design_result_bytes,
    validate_positive_route_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_positive_route_hypothesis_design_runner import (
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
    SOURCE_COMMIT,
    SOURCE_RESULT_NAME,
    SOURCE_ROOT,
    PositiveRouteHypothesisDesignRunnerBlocked,
    RuntimePaths,
    _run_positive_route_hypothesis_design,
    build_positive_route_coach_proposal_envelope_bytes,
    main,
    run_positive_route_hypothesis_design,
    validate_positive_route_coach_proposal_envelope_bytes,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests.test_microstructure_positive_route_hypothesis_design import (
    PROPOSAL as BUILDER_PROPOSAL,
    _interpretation,
)


PROPOSAL = {
    **BUILDER_PROPOSAL,
    "design_id": "synthetic-positive-route-runner-design-v2",
    "created_at": "2026-08-08T01:00:00Z",
}


def _parsed(raw: bytes) -> dict[str, object]:
    value = json.loads(raw.decode("utf-8"))
    assert isinstance(value, dict)
    return value


def _reseal(value: dict[str, object]) -> bytes:
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": (
            "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
        ),
    }
    return canonical_json_bytes(value)


def _validate_draft_2020_12_fixture(
    schema: dict[str, object],
    value: object,
) -> None:
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(
        schema,
        format_checker=FormatChecker(),
    ).validate(value)


class MicrostructurePositiveRouteHypothesisDesignRunnerTest(unittest.TestCase):
    @staticmethod
    def _positive(tier: str = TIER_ORDER[0]) -> bytes:
        return _interpretation(
            POSITIVE_DISPOSITION,
            selected_tier=tier,
        )

    @staticmethod
    def _non_positive() -> tuple[bytes, bytes, bytes]:
        return tuple(_interpretation(item) for item in sorted(NON_POSITIVE_DISPOSITIONS))

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

    def test_zero_argument_fixed_paths_and_public_surface_are_exact(self) -> None:
        self.assertEqual(REPOSITORY_ROOT, PRODUCTION_PATHS.repository_root)
        self.assertEqual(SOURCE_ROOT, PRODUCTION_PATHS.source_root)
        self.assertEqual(PROPOSAL_ROOT, PRODUCTION_PATHS.proposal_root)
        self.assertEqual(OUTPUT_ROOT, PRODUCTION_PATHS.output_root)
        self.assertEqual(
            SOURCE_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-v3-interpretation-runner-v1",
        )
        self.assertEqual(
            PROPOSAL_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/inbox/"
            "local-node-microstructure-positive-route-design-runner-v2",
        )
        self.assertEqual(
            OUTPUT_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/outbox/"
            "local-node-microstructure-positive-route-design-runner-v2",
        )
        self.assertEqual(
            ["paths"],
            list(inspect.signature(run_positive_route_hypothesis_design).parameters),
        )
        with patch(
            "research_pipeline.microstructure_positive_route_hypothesis_design_runner."
            "run_positive_route_hypothesis_design",
            return_value={"status": "CREATED"},
        ) as mocked, redirect_stdout(io.StringIO()):
            self.assertEqual(main([]), 0)
            mocked.assert_called_once_with(PRODUCTION_PATHS)
            mocked.reset_mock()
            self.assertEqual(main(["--source", "elsewhere"]), 2)
            mocked.assert_not_called()

    def test_exact_task_repository_contract_and_four_file_inventory(self) -> None:
        task_raw = REPOSITORY_ROOT.joinpath(*RUNNER_TASK_RELATIVE.split("/")).read_bytes()
        task = _parsed(task_raw)
        listed = {
            item["locator"]: item["sha256"]
            for item in task["inputs"]
            if item["kind"] == "REPOSITORY_PATH"
        }
        self.assertEqual(RUNNER_TASK_SHA256, hashlib.sha256(task_raw).hexdigest())
        self.assertEqual(RUNNER_TASK_ID, task["task_id"])
        self.assertEqual("5e033de21d2b1c8fa1a6e21aa7aa87841448f568", SOURCE_COMMIT)
        self.assertEqual(EXPECTED_REPOSITORY_INPUTS, listed)
        self.assertEqual(
            (
                "research_pipeline/microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json",
                "research_pipeline/microstructure_positive_route_hypothesis_design_runner.py",
                "research_pipeline/tests/test_microstructure_positive_route_hypothesis_design_runner.py",
                "docs/okx-microstructure-positive-route-hypothesis-design-runner-v2.md",
            ),
            IMPLEMENTATION_FILES,
        )

    def test_schema_canonical_envelope_and_positive_result_validate(self) -> None:
        interpretation = self._positive(TIER_ORDER[1])
        proposal_before = deepcopy(PROPOSAL)
        first = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        second = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        self.assertEqual(first, second)
        self.assertEqual(proposal_before, PROPOSAL)
        envelope = validate_positive_route_coach_proposal_envelope_bytes(
            first,
            interpretation,
        )
        schema_raw = REPOSITORY_ROOT.joinpath(*PROPOSAL_SCHEMA_RELATIVE.split("/")).read_bytes()
        self.assertEqual(PROPOSAL_SCHEMA_SHA256, hashlib.sha256(schema_raw).hexdigest())
        schema = _parsed(schema_raw)
        _validate_draft_2020_12_fixture(schema, envelope)
        result_raw = build_positive_route_hypothesis_design_result_bytes(
            interpretation,
            envelope["coach_proposal"],
        )
        result_schema = _parsed(
            (
                REPOSITORY_ROOT
                / "research_pipeline"
                / "microstructure-positive-route-hypothesis-design-result.v2.schema.json"
            ).read_bytes()
        )
        _validate_draft_2020_12_fixture(result_schema, _parsed(result_raw))
        self.assertEqual(ROUTE_ID, envelope["route_selection"]["route_id"])
        self.assertEqual(
            ROUTE_CONTRACT_SHA256,
            envelope["route_selection"]["route_contract_sha256"],
        )
        self.assertEqual("SOLE_PRIMARY", envelope["route_selection"]["priority"])
        self.assertEqual(TIER_ORDER[1], envelope["route_selection"]["source_selected_tier"])
        self.assertFalse(any(envelope["safety_assertions"].values()))

    def test_all_four_dispositions_create_exact_branch(self) -> None:
        cases = ((self._positive(), POSITIVE_DISPOSITION),) + tuple(
            (raw, disposition)
            for raw, disposition in zip(
                self._non_positive(),
                sorted(NON_POSITIVE_DISPOSITIONS),
            )
        )
        for interpretation, disposition in cases:
            with self.subTest(disposition=disposition), TemporaryDirectory() as directory:
                proposal = (
                    build_positive_route_coach_proposal_envelope_bytes(
                        interpretation,
                        PROPOSAL,
                    )
                    if disposition == POSITIVE_DISPOSITION
                    else None
                )
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal,
                )
                result = run_positive_route_hypothesis_design(paths)
                raw = (paths.output_root / OUTPUT_RESULT_NAME).read_bytes()
                parsed = validate_positive_route_hypothesis_design_result_bytes(
                    raw,
                    interpretation,
                )
                self.assertEqual("CREATED", result["status"])
                self.assertEqual(disposition, result["source_disposition"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["sha256"])
                self.assertEqual(
                    {
                        "status",
                        "result",
                        "sha256",
                        "source_disposition",
                        "design_status",
                        "route_id",
                        "design_id",
                    },
                    set(result),
                )
                if disposition == POSITIVE_DISPOSITION:
                    self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", result["design_status"])
                    self.assertEqual(ROUTE_ID, result["route_id"])
                    self.assertEqual(PROPOSAL["design_id"], result["design_id"])
                    self.assertIsNotNone(parsed["hypothesis_design"])
                else:
                    self.assertEqual("CLOSED_NO_HYPOTHESIS_DESIGN", result["design_status"])
                    self.assertIsNone(result["route_id"])
                    self.assertIsNone(result["design_id"])

    def test_positive_proposal_inventory_bytes_and_bindings_fail_closed(self) -> None:
        interpretation = self._positive()
        valid = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        other_source = self._positive(TIER_ORDER[1])
        source_mismatch = build_positive_route_coach_proposal_envelope_bytes(
            other_source,
            PROPOSAL,
        )
        mutations: dict[str, bytes | None] = {
            "missing": None,
            "malformed": b"{",
            "noncanonical": valid + b" ",
            "source_mismatch": source_mismatch,
            "bare_proposal": canonical_json_bytes(PROPOSAL),
        }
        for name, key, value in (
            ("route", "route_id", "OTHER_ROUTE"),
            ("tier", "source_selected_tier", TIER_ORDER[1]),
            ("fallback", "dra_fallback_authorized", True),
        ):
            changed = _parsed(valid)
            changed["route_selection"][key] = value
            mutations[name] = _reseal(changed)
        changed = _parsed(valid)
        changed["source_interpretation"]["document_sha256"] = "0" * 64
        mutations["source_hash"] = _reseal(changed)
        changed = _parsed(valid)
        changed["seal"]["payload_sha256"] = "0" * 64
        mutations["seal"] = canonical_json_bytes(changed)

        for mode, proposal_raw in mutations.items():
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=proposal_raw,
                )
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        for extra_name in ("coach-proposal-copy.json", "note.txt"):
            with self.subTest(extra_name=extra_name), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=valid,
                )
                (paths.proposal_root / extra_name).write_bytes(b"{}")
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)

    def test_non_positive_forbids_any_proposal(self) -> None:
        for interpretation in self._non_positive():
            with TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=b"{}",
                )
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_preflight_task_and_repository_drift_fail_closed(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        for relative in (
            RUNNER_TASK_RELATIVE,
            "research_pipeline/policy.v3.json",
        ):
            with self.subTest(relative=relative), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=envelope,
                )
                target = paths.repository_root.joinpath(*relative.split("/"))
                target.write_bytes(target.read_bytes() + b" ")
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_task_repository_source_and_proposal_concurrent_drift(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        for mode in ("task", "repository", "implementation", "source", "proposal"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=envelope,
                )

                def mutating_builder(raw, proposal):
                    result = build_positive_route_hypothesis_design_result_bytes(raw, proposal)
                    relative = {
                        "task": RUNNER_TASK_RELATIVE,
                        "repository": "research_pipeline/policy.v3.json",
                        "implementation": IMPLEMENTATION_FILES[3],
                    }.get(mode)
                    if relative is not None:
                        target = paths.repository_root.joinpath(*relative.split("/"))
                    elif mode == "source":
                        target = paths.source_root / SOURCE_RESULT_NAME
                    else:
                        target = paths.proposal_root / PROPOSAL_NAME
                    target.write_bytes(target.read_bytes() + b" ")
                    return result

                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    _run_positive_route_hypothesis_design(paths, mutating_builder)
                self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_builder_failure_or_proposal_change_creates_no_output(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                interpretation_raw=interpretation,
                proposal_raw=envelope,
            )

            def failing_builder(_raw, _proposal):
                raise RuntimeError("synthetic failure")

            with self.assertRaisesRegex(
                PositiveRouteHypothesisDesignRunnerBlocked,
                "design builder failed",
            ):
                _run_positive_route_hypothesis_design(paths, failing_builder)
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                interpretation_raw=interpretation,
                proposal_raw=envelope,
            )
            changed = {**PROPOSAL, "design_id": "different-positive-route-design-v2"}
            with self.assertRaisesRegex(
                PositiveRouteHypothesisDesignRunnerBlocked,
                "changed the Coach proposal",
            ):
                _run_positive_route_hypothesis_design(
                    paths,
                    lambda raw, _proposal: build_positive_route_hypothesis_design_result_bytes(
                        raw,
                        changed,
                    ),
                )
            self.assertFalse((paths.output_root / OUTPUT_RESULT_NAME).exists())

    def test_output_preprovision_inventory_conflict_and_exact_retry(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
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
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)
                after = self._inventory(paths.output_root) if paths.output_root.exists() else {}
                self.assertEqual(before, after)

        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                interpretation_raw=interpretation,
                proposal_raw=envelope,
            )
            source_before = self._inventory(paths.source_root)
            proposal_before = self._inventory(paths.proposal_root)
            first = run_positive_route_hypothesis_design(paths)
            output = paths.output_root / OUTPUT_RESULT_NAME
            raw = output.read_bytes()
            modified = output.stat().st_mtime_ns
            second = run_positive_route_hypothesis_design(paths)
            self.assertEqual("CREATED", first["status"])
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            self.assertEqual(raw, output.read_bytes())
            self.assertEqual(modified, output.stat().st_mtime_ns)
            self.assertEqual(source_before, self._inventory(paths.source_root))
            self.assertEqual(proposal_before, self._inventory(paths.proposal_root))

    def test_links_are_rejected_where_supported(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        for mode in ("source", "proposal", "output"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self._install(
                    Path(directory),
                    interpretation_raw=interpretation,
                    proposal_raw=envelope,
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
                with self.assertRaises(PositiveRouteHypothesisDesignRunnerBlocked):
                    run_positive_route_hypothesis_design(paths)
                self.assertEqual(
                    interpretation
                    if mode == "source"
                    else envelope
                    if mode == "proposal"
                    else b"{}",
                    target.read_bytes(),
                )

    def test_roots_must_be_distinct_and_non_overlapping(self) -> None:
        interpretation = self._positive()
        envelope = build_positive_route_coach_proposal_envelope_bytes(
            interpretation,
            PROPOSAL,
        )
        with TemporaryDirectory() as directory:
            paths = self._install(
                Path(directory),
                interpretation_raw=interpretation,
                proposal_raw=envelope,
            )
            cases = (
                RuntimePaths(
                    paths.repository_root,
                    paths.source_root,
                    paths.source_root,
                    paths.output_root,
                ),
                RuntimePaths(
                    paths.repository_root,
                    paths.source_root,
                    paths.proposal_root,
                    paths.proposal_root,
                ),
                RuntimePaths(
                    paths.repository_root,
                    paths.repository_root / "source",
                    paths.proposal_root,
                    paths.output_root,
                ),
            )
            for changed in cases:
                with self.subTest(paths=changed), self.assertRaisesRegex(
                    PositiveRouteHypothesisDesignRunnerBlocked,
                    "roots must (differ|not overlap)",
                ):
                    run_positive_route_hypothesis_design(changed)


if __name__ == "__main__":
    unittest.main()
