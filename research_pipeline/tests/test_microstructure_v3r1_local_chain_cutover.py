from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import tarfile
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_handoff import MANIFEST_NAME, RESULT_NAME
from research_pipeline.microstructure_handoff_export import export_handoff
from research_pipeline.microstructure_handoff_receive import HandoffReceiveBlocked
from research_pipeline.microstructure_handoff_runner import HandoffRunnerBlocked
from research_pipeline.microstructure_handoff_receive_v3r1 import (
    RuntimePaths as ReceivePaths,
    receive_handoff,
)
from research_pipeline.microstructure_handoff_runner_v3r1 import (
    DIAGNOSTIC_TASK_ID,
    DIAGNOSTIC_TASK_SHA256,
    RuntimePaths as HandoffPaths,
    run_handoff,
)
from research_pipeline.microstructure_handoff_v3r1 import (
    HandoffContractError,
    validate_handoff_package,
)
from research_pipeline.microstructure_interpretation_runner import (
    InterpretationRunnerBlocked,
)
from research_pipeline.microstructure_interpretation_runner_v3r1 import (
    RuntimePaths as InterpretationPaths,
    run_interpretation,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests.test_microstructure_handoff_export import (
    TASK_ID as EXPORT_FIXTURE_TASK_ID,
    _Fixture as ExportFixture,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class MicrostructureV3R1LocalChainCutoverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls._source = TemporaryDirectory()
        root = Path(cls._source.name)
        paths = ExportFixture().install(root)
        export_handoff(paths=paths)
        package_root = paths.final_root / EXPORT_FIXTURE_TASK_ID
        manifest_path = package_root / MANIFEST_NAME
        manifest = json.loads(manifest_path.read_bytes())
        manifest["task_id"] = DIAGNOSTIC_TASK_ID
        manifest["task_sha256"] = DIAGNOSTIC_TASK_SHA256
        cls._seal(manifest)
        manifest_path.write_bytes(canonical_json_bytes(manifest))
        cls.package_files = {
            path.relative_to(package_root).as_posix(): path.read_bytes()
            for path in package_root.rglob("*")
            if path.is_file()
        }
        if len(cls.package_files) != 30:
            raise AssertionError("fixture must contain exactly thirty files")

    @classmethod
    def tearDownClass(cls) -> None:
        cls._source.cleanup()

    @staticmethod
    def _seal(value: dict[str, object]) -> None:
        value["seal"]["payload_sha256"] = hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest()

    @staticmethod
    def _write(path: Path, raw: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    @classmethod
    def _install_package(
        cls, base: Path, *, files: dict[str, bytes] | None = None
    ) -> Path:
        root = base / DIAGNOSTIC_TASK_ID
        for name, raw in (cls.package_files if files is None else files).items():
            cls._write(root.joinpath(*name.split("/")), raw)
        return root

    @staticmethod
    def _inventory(root: Path) -> list[tuple[str, Path]]:
        return [
            (path.relative_to(root).as_posix(), path)
            for path in root.rglob("*")
            if path.is_file()
        ]

    @classmethod
    def _write_archive(cls, archive_path: Path, package_root: Path) -> None:
        with tarfile.open(archive_path, "w", format=tarfile.USTAR_FORMAT) as archive:
            archive.add(
                package_root,
                arcname=DIAGNOSTIC_TASK_ID,
                recursive=True,
            )

    def test_exact_schema_and_thirty_file_missingness_closure(self) -> None:
        v1 = REPOSITORY_ROOT / "research_pipeline/microstructure-handoff-result.v1.schema.json"
        v3r1 = REPOSITORY_ROOT / "research_pipeline/microstructure-handoff-result.v3r1.schema.json"
        self.assertEqual(v1.read_bytes(), v3r1.read_bytes())
        with TemporaryDirectory() as directory:
            root = self._install_package(Path(directory))
            context = validate_handoff_package(
                root,
                self._inventory(root),
                expected_task_id=DIAGNOSTIC_TASK_ID,
                expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
            )
            self.assertEqual(14, len(context.days))
            manifest = json.loads((root / MANIFEST_NAME).read_bytes())
            self.assertFalse(manifest["missingness"]["rejected_days_as_market_input"])
            self.assertFalse(manifest["missingness"]["nonselected_prefixes_as_market_input"])
            self.assertEqual(
                manifest["canonical_state"]["selected_streak_chain_head_sha256"],
                context.chain_head_sha256,
            )

    def test_missing_extra_cross_lane_link_and_missingness_drift_fail_closed(self) -> None:
        variants: list[tuple[str, dict[str, bytes]]] = []
        missing = dict(self.package_files)
        missing.pop(next(name for name in missing if name.endswith(".json") and name != MANIFEST_NAME))
        variants.append(("missing", missing))
        extra = dict(self.package_files)
        extra["extra.json"] = b"{}"
        variants.append(("extra", extra))
        for label in ("cross_lane", "missingness"):
            changed = dict(self.package_files)
            manifest = json.loads(changed[MANIFEST_NAME])
            if label == "cross_lane":
                manifest["manifest_type"] = "MICROSTRUCTURE_V3_CREATE_ONLY_HANDOFF_MANIFEST"
            else:
                manifest["missingness"]["rejected_days_as_market_input"] = True
            self._seal(manifest)
            changed[MANIFEST_NAME] = canonical_json_bytes(manifest)
            variants.append((label, changed))
        for label, files in variants:
            with self.subTest(label=label), TemporaryDirectory() as directory:
                root = self._install_package(Path(directory), files=files)
                with self.assertRaises(HandoffContractError):
                    validate_handoff_package(
                        root,
                        self._inventory(root),
                        expected_task_id=DIAGNOSTIC_TASK_ID,
                        expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
                    )
        if hasattr(os, "symlink"):
            with TemporaryDirectory() as directory:
                root = self._install_package(Path(directory))
                target = next(path for path in root.rglob("*.json") if path.name != MANIFEST_NAME)
                raw = target.read_bytes()
                target.unlink()
                outside = Path(directory) / "outside.json"
                outside.write_bytes(raw)
                try:
                    os.symlink(outside, target)
                except OSError:
                    return
                with self.assertRaises(HandoffContractError):
                    validate_handoff_package(
                        root,
                        self._inventory(root),
                        expected_task_id=DIAGNOSTIC_TASK_ID,
                        expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
                    )

    def test_diagnostic_and_terminal_interpretation_are_create_once(self) -> None:
        with TemporaryDirectory() as directory:
            base = Path(directory)
            source = self._install_package(base / "source")
            first = run_handoff(HandoffPaths(REPOSITORY_ROOT, source))
            self.assertEqual("CREATED", first["status"])
            diagnostic_raw = (source / RESULT_NAME).read_bytes()
            diagnostic = json.loads(diagnostic_raw)["diagnostic_result"]
            second = run_handoff(
                HandoffPaths(REPOSITORY_ROOT, source),
                analyzer=lambda *_args, **_kwargs: diagnostic,
            )
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            self.assertEqual(diagnostic_raw, (source / RESULT_NAME).read_bytes())

            output = base / "outbox"
            output.mkdir()
            interpreted = run_interpretation(
                InterpretationPaths(REPOSITORY_ROOT, source, output)
            )
            self.assertEqual("INSUFFICIENT_FORWARD_EVIDENCE", interpreted["disposition"])
            self.assertEqual("CREATED", interpreted["status"])
            retry = run_interpretation(
                InterpretationPaths(REPOSITORY_ROOT, source, output)
            )
            self.assertEqual("IDEMPOTENT_IDENTICAL", retry["status"])
            (output / "interpretation-result.json").write_bytes(b"{}")
            with self.assertRaises(InterpretationRunnerBlocked):
                run_interpretation(InterpretationPaths(REPOSITORY_ROOT, source, output))

    def test_archive_receipt_is_exact_idempotent_and_conflict_rejecting(self) -> None:
        with TemporaryDirectory() as directory:
            base = Path(directory)
            package = self._install_package(base / "package")
            transport = base / "transport"
            staging = base / "staging"
            final = base / "final"
            for root in (transport, staging, final):
                root.mkdir()
            archive = transport / f"{DIAGNOSTIC_TASK_ID}.tar"
            self._write_archive(archive, package)
            paths = ReceivePaths(REPOSITORY_ROOT, archive, staging, final)
            first = receive_handoff(paths)
            self.assertEqual("RECEIVED", first["status"])
            archive.unlink()
            second = receive_handoff(paths)
            self.assertEqual("IDEMPOTENT_IDENTICAL", second["status"])
            (final / DIAGNOSTIC_TASK_ID / "extra.json").write_bytes(b"{}")
            with self.assertRaises(HandoffReceiveBlocked):
                receive_handoff(paths)

    def test_active_gate_declares_only_v3r1_terminal_chain_and_mismatch_checks(self) -> None:
        script = (
            REPOSITORY_ROOT / "scripts/verify_local_research_active_chain.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("local-node-microstructure-v3r1-evidence-diagnostic-v1", script)
        self.assertIn("local-node-microstructure-v3r1-handoff-transfer-v1", script)
        self.assertIn("local-node-microstructure-v3r1-interpretation-runner-v1", script)
        self.assertIn("MICROSTRUCTURE_DISCOVERY_V3R1_CREATE_ONLY_HANDOFF_MANIFEST", script)
        self.assertIn("/var/lib/agora-research/microstructure-v3r1-handoff-export", script)
        self.assertIn("exporter authority drift", script)
        self.assertIn("Local execution authority drift", script)
        self.assertNotIn("local-node-microstructure-v3-hypothesis-design-runner-v3", script)
        self.assertNotIn("local-node-microstructure-positive-route-design-runner-v4", script)


if __name__ == "__main__":
    unittest.main()
