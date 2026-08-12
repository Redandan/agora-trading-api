from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.local_node import MANDATORY_FORBIDDEN_ACTIONS
from research_pipeline.microstructure_handoff import MANIFEST_NAME
from research_pipeline.microstructure_handoff_export import (
    BINDING_PATH,
    CANONICAL_STATE_ROOT,
    EXPORT_FINAL_ROOT,
    EXPORT_STAGING_ROOT,
    LOCAL_DIAGNOSTIC_TASK,
    RETAINED_DAY_ROOT,
    ExportBlocked,
    RuntimePaths,
    export_handoff,
    main,
)
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    _REQUIRED_RELEASE_FILES,
)
from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    AUTHORIZATION,
    DiscoveryRecoveryBlocked,
    advance_complete_envelope,
    build_complete_envelope,
    build_source_binding,
    canonical_intake_state_bytes,
    initial_intake_state,
)
from research_pipeline.microstructure_source_contract import (
    canonical_json_bytes,
)
from research_pipeline.tests.test_microstructure_v3_intake_isolation import (
    _v3_day_bundle,
)


DIAGNOSTIC_ID = "okx-btcusdt-microstructure-forward-v3r1-20260901-r3"
GENERATION_ID = "okx-btcusdt-microstructure-discovery-v3r1-20260901-r3"
START_DAY = date(2026, 9, 1)
RELEASE_ID = "deterministic-v3r1-export-fixture"
TASK_ID = "local-node-v3r1-offline-evidence-diagnostic-v1"


def _task() -> dict[str, object]:
    return {
        "schema_version": "1",
        "task_id": TASK_ID,
        "issued_at": "2026-09-15T00:00:00Z",
        "manager_thread_id": "019fca63-4f8f-71e3-9d88-297bca468eb9",
        "task_type": "EVIDENCE_DIAGNOSTIC",
        "execution_mode": "READ_ONLY",
        "objective": "Analyze one exact validated V3 handoff package offline.",
        "canonical_research_status": "WAITING_FOR_EVIDENCE",
        "authorization": AUTHORIZATION,
        "state_authority": "SERVER_CANONICAL",
        "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        "inputs": [],
        "allowed_actions": ["READ_REPOSITORY_CONTRACTS"],
        "forbidden_actions": sorted(MANDATORY_FORBIDDEN_ACTIONS),
        "expected_outputs": ["MISSING_PROOF_REGISTER"],
        "stop_conditions": ["Remain read-only and fail closed."],
        "limits": {
            "timeout_seconds": 7200,
            "max_files_changed": 0,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        },
    }


class _Fixture:
    def __init__(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        self.release_files = {
            relative: (repository / relative).read_bytes()
            for relative in sorted(_REQUIRED_RELEASE_FILES)
        }
        self.release_manifest = "".join(
            f"{hashlib.sha256(raw).hexdigest()}  {relative}\n"
            for relative, raw in self.release_files.items()
        ).encode("utf-8")
        self.release_manifest_sha256 = hashlib.sha256(
            self.release_manifest
        ).hexdigest()
        self.task = _task()
        self.task_raw = canonical_json_bytes(self.task)
        self.task_sha256 = hashlib.sha256(self.task_raw).hexdigest()
        self.binding = build_source_binding(
            generation_id=GENERATION_ID,
            diagnostic_id=DIAGNOSTIC_ID,
            producer_release_id=RELEASE_ID,
            producer_manifest_sha256=self.release_manifest_sha256,
            start_day=START_DAY,
            as_of_day=START_DAY - timedelta(days=1),
        )
        self.source: dict[str, bytes] = {}
        state = initial_intake_state(self.binding)
        self.initial_state_raw = canonical_intake_state_bytes(
            state, self.binding
        )
        for index in range(14):
            bundle_day = START_DAY + timedelta(days=index)
            bundle = _v3_day_bundle(bundle_day)
            bundle_raw = canonical_json_bytes(bundle)
            published_at = datetime.combine(
                bundle_day + timedelta(days=1),
                datetime.min.time(),
                tzinfo=timezone.utc,
            ) + timedelta(seconds=1)
            envelope = build_complete_envelope(
                binding_value=self.binding,
                bundle_value=bundle,
                raw_bundle_bytes=bundle_raw,
                day=bundle_day,
                published_at=published_at,
            )
            envelope_raw = canonical_json_bytes(envelope)
            state = advance_complete_envelope(
                state,
                envelope,
                bundle,
                raw_complete_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                binding_value=self.binding,
                accepted_at=published_at + timedelta(seconds=1),
            )
            day_text = bundle_day.isoformat()
            base = f"okx-btc-usdt-microstructure-{day_text}"
            self.source[f"{day_text}/{base}.json"] = bundle_raw
            self.source[
                f"{day_text}/{base}.complete.envelope.json"
            ] = envelope_raw
        self.ready_state_raw = canonical_intake_state_bytes(
            state, self.binding
        )

    @staticmethod
    def _write(path: Path, raw: bytes, *, readonly: bool = False) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        if readonly:
            os.chmod(path, 0o444)

    def install(self, root: Path, *, ready: bool = True) -> RuntimePaths:
        binding = root / "etc" / "binding.json"
        task_path = root / "etc" / "task.json"
        state_root = root / "state"
        retained = root / "retained"
        staging = root / "staging"
        final = root / "final"
        release = root / "releases" / RELEASE_ID
        for directory in (state_root, retained, staging, final, release / ".release"):
            directory.mkdir(parents=True, exist_ok=True)
        self._write(binding, canonical_json_bytes(self.binding))
        self._write(task_path, self.task_raw, readonly=True)
        self._write(
            state_root / f"{GENERATION_ID}.json",
            self.ready_state_raw if ready else self.initial_state_raw,
        )
        for relative, raw in self.release_files.items():
            self._write(release / Path(relative), raw)
        self._write(release / ".release" / "source.sha256", self.release_manifest)
        self._write(
            release / ".release" / "provenance.json",
            canonical_json_bytes(
                {
                    "release_id": RELEASE_ID,
                    "source_manifest_sha256": self.release_manifest_sha256,
                }
            ),
        )
        if ready:
            for relative_name, raw in self.source.items():
                target = retained.joinpath(*relative_name.split("/"))
                self._write(target, raw, readonly=True)
            for index in range(14):
                day_text = (START_DAY + timedelta(days=index)).isoformat()
                self._write(
                    retained / f".{day_text}.publish-reserved",
                    b"",
                    readonly=True,
                )
                if os.name != "nt":
                    os.chmod(retained / day_text, 0o555)
        return RuntimePaths(
            binding=binding,
            canonical_state_root=state_root,
            retained_day_root=retained,
            local_task=task_path,
            staging_root=staging,
            final_root=final,
            release=release,
        )

    @staticmethod
    def first_bundle(paths: RuntimePaths) -> Path:
        day_text = START_DAY.isoformat()
        return (
            paths.retained_day_root
            / day_text
            / f"okx-btc-usdt-microstructure-{day_text}.json"
        )

    @staticmethod
    def first_envelope(paths: RuntimePaths) -> Path:
        day_text = START_DAY.isoformat()
        return (
            paths.retained_day_root
            / day_text
            / f"okx-btc-usdt-microstructure-{day_text}.complete.envelope.json"
        )


class MicrostructureHandoffExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = _Fixture()

    def test_fixed_production_paths_are_exact(self) -> None:
        self.assertEqual(
            Path("/etc/agora-research/okx-microstructure-continuous-source-v3r1.json"),
            BINDING_PATH,
        )
        self.assertEqual(
            Path("/var/lib/agora-research/state/microstructure-v3r1"),
            CANONICAL_STATE_ROOT,
        )
        self.assertEqual(
            Path("/var/lib/agora-evidence-source/microstructure-v3r1-drop"),
            RETAINED_DAY_ROOT,
        )
        self.assertEqual(
            Path("/etc/agora-research/local-tasks/microstructure-v3r1-evidence-diagnostic.v1.json"),
            LOCAL_DIAGNOSTIC_TASK,
        )
        self.assertEqual(
            Path("/var/lib/agora-research/microstructure-v3r1-handoff-staging"),
            EXPORT_STAGING_ROOT,
        )
        self.assertEqual(
            Path("/var/lib/agora-research/microstructure-v3r1-handoff-export"),
            EXPORT_FINAL_ROOT,
        )

    def test_zero_argument_cli_and_argument_rejection(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            output = io.StringIO()
            with patch(
                "research_pipeline.microstructure_handoff_export.fixed_runtime_paths",
                return_value=paths,
            ), redirect_stdout(output):
                self.assertEqual(0, main([]))
            self.assertEqual("EXPORTED", json.loads(output.getvalue())["status"])
        error = io.StringIO()
        with patch(
            "research_pipeline.microstructure_handoff_export.fixed_runtime_paths"
        ) as fixed, redirect_stderr(error):
            self.assertEqual(2, main(["unexpected"]))
            fixed.assert_not_called()
        self.assertIn("usage:", error.getvalue())

    def test_valid_thirty_file_export_and_exact_manifest(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            result = export_handoff(paths=paths)
            self.assertEqual("EXPORTED", result.status)
            final = paths.final_root / TASK_ID
            manifest_raw = (final / MANIFEST_NAME).read_bytes()
            manifest = json.loads(manifest_raw)
            self.assertEqual(canonical_json_bytes(manifest), manifest_raw)
            self.assertEqual(self.fixture.task_sha256, manifest["task_sha256"])
            self.assertEqual(DIAGNOSTIC_ID, manifest["canonical_state"]["diagnostic_id"])
            self.assertEqual(14, len(manifest["days"]))
            self.assertEqual(self.fixture.release_manifest_sha256, manifest["source_release"]["producer_manifest_sha256"])
            self.assertEqual(result.manifest_sha256, hashlib.sha256(manifest_raw).hexdigest())

    def test_not_ready_creates_no_staging_or_final_task_directory(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory), ready=False)
            result = export_handoff(paths=paths)
            self.assertEqual("NOT_READY", result.status)
            self.assertFalse((paths.staging_root / TASK_ID).exists())
            self.assertFalse((paths.final_root / TASK_ID).exists())
            self.assertEqual([], list(paths.staging_root.iterdir()))
            self.assertEqual([], list(paths.final_root.iterdir()))

    def test_invalid_noncanonical_duplicate_link_mutable_or_wrong_task_blocks(self) -> None:
        modes = ("noncanonical", "duplicate", "wrong", "mutable", "link")
        for mode in modes:
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory), ready=False)
                task_path = paths.local_task
                os.chmod(task_path, 0o644)
                if mode == "noncanonical":
                    task_path.write_bytes(json.dumps(self.fixture.task, indent=2).encode())
                    os.chmod(task_path, 0o444)
                elif mode == "duplicate":
                    task_path.write_bytes(
                        self.fixture.task_raw.replace(
                            b"{", b'{"schema_version":"1",', 1
                        )
                    )
                    os.chmod(task_path, 0o444)
                elif mode == "wrong":
                    changed = deepcopy(self.fixture.task)
                    changed["execution_mode"] = "WORKTREE_WRITE"
                    task_path.write_bytes(canonical_json_bytes(changed))
                    os.chmod(task_path, 0o444)
                elif mode == "mutable":
                    task_path.write_bytes(self.fixture.task_raw)
                else:
                    task_path.unlink()
                    elsewhere = task_path.with_name("elsewhere-task.json")
                    elsewhere.write_bytes(self.fixture.task_raw)
                    os.chmod(elsewhere, 0o444)
                    try:
                        task_path.symlink_to(elsewhere)
                    except OSError as error:
                        self.skipTest(f"symlinks unavailable: {error}")
                with self.assertRaises(ExportBlocked):
                    export_handoff(paths=paths)
                self.assertFalse((paths.staging_root / TASK_ID).exists())

    def test_state_chain_source_release_and_hash_drift_block_before_staging(self) -> None:
        for mode in ("chain", "release", "hash"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory))
                if mode == "chain":
                    state_path = paths.canonical_state_root / f"{GENERATION_ID}.json"
                    state = json.loads(state_path.read_bytes())
                    state["calendar_chain_head_sha256"] = "0" * 64
                    state_path.write_bytes(canonical_json_bytes(state))
                elif mode == "release":
                    envelope_path = self.fixture.first_envelope(paths)
                    os.chmod(envelope_path, 0o644)
                    envelope = json.loads(envelope_path.read_bytes())
                    envelope["producer_release_id"] = "wrong-release"
                    envelope_path.write_bytes(canonical_json_bytes(envelope))
                    os.chmod(envelope_path, 0o444)
                else:
                    bundle = self.fixture.first_bundle(paths)
                    os.chmod(bundle, 0o644)
                    bundle.write_bytes(bundle.read_bytes()[:-1])
                    os.chmod(bundle, 0o444)
                with self.assertRaises((ExportBlocked, DiscoveryRecoveryBlocked)):
                    export_handoff(paths=paths)
                self.assertFalse((paths.staging_root / TASK_ID).exists())

    def test_missing_extra_partial_and_link_source_inputs_block(self) -> None:
        for mode in ("missing", "extra", "partial", "link"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory))
                bundle = self.fixture.first_bundle(paths)
                if mode == "missing":
                    os.chmod(bundle, 0o644)
                    bundle.unlink()
                elif mode == "extra":
                    (paths.retained_day_root / "extra").write_bytes(b"x")
                elif mode == "partial":
                    os.chmod(bundle, 0o644)
                    bundle.write_bytes(bundle.read_bytes()[:-1])
                    os.chmod(bundle, 0o444)
                else:
                    raw = bundle.read_bytes()
                    os.chmod(bundle, 0o644)
                    bundle.unlink()
                    elsewhere = paths.retained_day_root / "linked-bundle.json"
                    elsewhere.write_bytes(raw)
                    os.chmod(elsewhere, 0o444)
                    try:
                        bundle.symlink_to(elsewhere)
                    except OSError as error:
                        self.skipTest(f"symlinks unavailable: {error}")
                with self.assertRaises(ExportBlocked):
                    export_handoff(paths=paths)
                self.assertFalse((paths.staging_root / TASK_ID).exists())

    def test_existing_exact_package_is_idempotent_without_rewrite(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            first = export_handoff(paths=paths)
            manifest = paths.final_root / TASK_ID / MANIFEST_NAME
            before = manifest.stat().st_mtime_ns
            second = export_handoff(paths=paths)
            self.assertEqual("EXPORTED", first.status)
            self.assertEqual("IDEMPOTENT_IDENTICAL", second.status)
            self.assertEqual(first.manifest_sha256, second.manifest_sha256)
            self.assertEqual(before, manifest.stat().st_mtime_ns)

    def test_existing_conflict_and_extra_package_block(self) -> None:
        for mode in ("conflict", "extra"):
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory))
                export_handoff(paths=paths)
                final = paths.final_root / TASK_ID
                if mode == "conflict":
                    manifest = final / MANIFEST_NAME
                    os.chmod(manifest, 0o600)
                    manifest.write_bytes(manifest.read_bytes()[:-1])
                else:
                    if os.name != "nt":
                        os.chmod(final, 0o700)
                    (final / "extra.json").write_bytes(b"{}")
                with self.assertRaises(ExportBlocked):
                    export_handoff(paths=paths)

    def test_stale_staging_blocks_without_cleanup(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            stale = paths.staging_root / TASK_ID
            stale.mkdir()
            marker = stale / "partial"
            marker.write_bytes(b"partial")
            with self.assertRaises(ExportBlocked):
                export_handoff(paths=paths)
            self.assertEqual(b"partial", marker.read_bytes())
            self.assertFalse((paths.final_root / TASK_ID).exists())

    def test_rename_race_fails_without_overwrite_or_cleanup(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))

            def race(_source: Path, target: Path) -> None:
                target.mkdir()
                (target / "race-marker").write_bytes(b"winner")
                raise ExportBlocked("EXPORT_BLOCKED_RENAME_RACE")

            with patch(
                "research_pipeline.microstructure_handoff_export._rename_exclusive",
                side_effect=race,
            ), self.assertRaises(ExportBlocked):
                export_handoff(paths=paths)
            self.assertEqual(
                b"winner", (paths.final_root / TASK_ID / "race-marker").read_bytes()
            )
            self.assertTrue((paths.staging_root / TASK_ID).is_dir())

    def test_cross_filesystem_device_ids_fail_before_staging(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            with self.assertRaisesRegex(ExportBlocked, "CROSS_FILESYSTEM"):
                export_handoff(
                    paths=paths,
                    device_id=lambda path: 1 if path == paths.staging_root else 2,
                )
            self.assertFalse((paths.staging_root / TASK_ID).exists())
            self.assertFalse((paths.final_root / TASK_ID).exists())


if __name__ == "__main__":
    unittest.main()
