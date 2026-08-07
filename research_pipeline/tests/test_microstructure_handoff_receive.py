from __future__ import annotations

from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path, PurePosixPath
import tarfile
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_handoff_receive import (
    ARCHIVE_PATH,
    EXPECTED_REPOSITORY_INPUTS,
    FINAL_ROOT,
    PRODUCTION_PATHS,
    STAGING_ROOT,
    TRANSFER_TASK_ID,
    TRANSFER_TASK_RELATIVE,
    HandoffReceiveBlocked,
    RuntimePaths,
    _archive_expected,
    main,
    receive_handoff,
)
from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_TASK_ID,
    EXPECTED_REPOSITORY_INPUTS as SOURCE_REPOSITORY_INPUTS,
    REPOSITORY_ROOT,
)
from research_pipeline.tests.test_microstructure_handoff_runner import _Fixture


class HandoffReceiveTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = _Fixture()

    @staticmethod
    def _write(path: Path, raw: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def _install(self, base: Path) -> RuntimePaths:
        repository_root = base / "repository"
        required = set(EXPECTED_REPOSITORY_INPUTS) | set(SOURCE_REPOSITORY_INPUTS)
        required.add(TRANSFER_TASK_RELATIVE)
        for relative_name in sorted(required):
            source = REPOSITORY_ROOT.joinpath(*PurePosixPath(relative_name).parts)
            self._write(
                repository_root.joinpath(*PurePosixPath(relative_name).parts),
                source.read_bytes(),
            )
        local_root = base / "local-node"
        transport_parent = local_root / "transport"
        staging_parent = local_root / "staging"
        final_parent = local_root / "inbox"
        for directory in (transport_parent, staging_parent, final_parent):
            directory.mkdir(parents=True)
        return RuntimePaths(
            repository_root=repository_root,
            archive_path=transport_parent / f"{DIAGNOSTIC_TASK_ID}.tar",
            staging_parent=staging_parent,
            final_parent=final_parent,
        )

    def _entries(
        self,
        *,
        rename: dict[str, str] | None = None,
        omit: set[str] | None = None,
        type_overrides: dict[str, tuple[bytes, str]] | None = None,
        extras: list[tuple[tarfile.TarInfo, bytes]] | None = None,
    ) -> list[tuple[tarfile.TarInfo, bytes]]:
        rename = rename or {}
        omit = omit or set()
        type_overrides = type_overrides or {}
        expected_directories, expected_files = _archive_expected()
        entries: list[tuple[tarfile.TarInfo, bytes]] = []
        for original_name in sorted(expected_directories):
            if original_name in omit:
                continue
            name = rename.get(original_name, original_name)
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = 0o500
            info.mtime = 0
            entries.append((info, b""))
        for original_name in sorted(expected_files):
            if original_name in omit:
                continue
            name = rename.get(original_name, original_name)
            relative_name = original_name.removeprefix(f"{DIAGNOSTIC_TASK_ID}/")
            raw = self.fixture.package_files[relative_name]
            info = tarfile.TarInfo(name)
            info.mode = 0o400
            info.mtime = 0
            if original_name in type_overrides:
                member_type, linkname = type_overrides[original_name]
                info.type = member_type
                info.linkname = linkname
                info.size = 0
                raw = b""
            else:
                info.type = tarfile.REGTYPE
                info.size = len(raw)
            entries.append((info, raw))
        entries.extend(extras or [])
        return entries

    @staticmethod
    def _write_tar(path: Path, entries: list[tuple[tarfile.TarInfo, bytes]]) -> None:
        with tarfile.open(path, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for info, raw in entries:
                archive.addfile(info, io.BytesIO(raw) if info.isreg() else None)

    def _install_archive(self, paths: RuntimePaths, **kwargs: object) -> None:
        self._write_tar(paths.archive_path, self._entries(**kwargs))

    def test_fixed_production_identity_and_zero_argument_cli(self) -> None:
        self.assertEqual(PRODUCTION_PATHS.archive_path, ARCHIVE_PATH)
        self.assertEqual(PRODUCTION_PATHS.staging_root, STAGING_ROOT)
        self.assertEqual(PRODUCTION_PATHS.final_root, FINAL_ROOT)
        self.assertEqual(TRANSFER_TASK_ID, "local-node-microstructure-v3-handoff-transfer-v1")
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(main(["unexpected"]), 2)
        self.assertEqual(json.loads(output.getvalue())["status"], "BLOCKED")

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            self._install_archive(paths)
            with patch(
                "research_pipeline.microstructure_handoff_receive.PRODUCTION_PATHS",
                paths,
            ), redirect_stdout(io.StringIO()) as valid_output:
                self.assertEqual(main([]), 0)
            self.assertEqual(json.loads(valid_output.getvalue())["status"], "RECEIVED")

    def test_valid_archive_is_validated_published_and_idempotent_without_archive(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            self._install_archive(paths)
            archive_before = paths.archive_path.read_bytes()
            first = receive_handoff(paths)
            self.assertEqual(first["status"], "RECEIVED")
            self.assertEqual(first["task_id"], DIAGNOSTIC_TASK_ID)
            self.assertFalse(paths.staging_root.exists())
            self.assertTrue(paths.final_root.is_dir())
            self.assertEqual(paths.archive_path.read_bytes(), archive_before)
            self.assertEqual(
                len([item for item in paths.final_root.rglob("*") if item.is_file()]),
                30,
            )
            paths.archive_path.unlink()
            second = receive_handoff(paths)
            self.assertEqual(second, {**first, "status": "IDEMPOTENT_IDENTICAL"})

    def test_task_and_repository_drift_fail_before_archive_use(self) -> None:
        cases = (
            (TRANSFER_TASK_RELATIVE, "fixed transfer task bytes changed"),
            ("docs/local-codex-research-node-v1.md", "repository input hash changed"),
        )
        for relative_name, message in cases:
            with self.subTest(relative_name=relative_name), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                target = paths.repository_root.joinpath(
                    *PurePosixPath(relative_name).parts
                )
                target.write_bytes(target.read_bytes() + b" ")
                with self.assertRaisesRegex(HandoffReceiveBlocked, message):
                    receive_handoff(paths)
                self.assertFalse(paths.staging_root.exists())
                self.assertFalse(paths.final_root.exists())

    def test_missing_empty_extra_duplicate_and_truncated_archives_fail_pre_publish(self) -> None:
        expected_directories, expected_files = _archive_expected()
        missing_name = sorted(expected_files)[0]
        duplicate_source = sorted(expected_files)[1]
        duplicate_target = sorted(expected_files)[2]
        extra = tarfile.TarInfo(f"{DIAGNOSTIC_TASK_ID}/unexpected.json")
        extra.type = tarfile.REGTYPE
        extra.size = 2
        cases: list[tuple[str, object]] = [
            ("missing", {"omit": {missing_name}}),
            ("extra", {"extras": [(extra, b"{}")]}),
            ("duplicate", {"rename": {duplicate_target: duplicate_source}}),
        ]
        for label, options in cases:
            with self.subTest(label=label), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                self._install_archive(paths, **options)
                with self.assertRaises(HandoffReceiveBlocked):
                    receive_handoff(paths)
                self.assertFalse(paths.final_root.exists())
                self.assertFalse(paths.staging_root.exists())

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            paths.archive_path.write_bytes(b"")
            with self.assertRaises(HandoffReceiveBlocked):
                receive_handoff(paths)
            self.assertFalse(paths.staging_root.exists())
            self.assertFalse(paths.final_root.exists())

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            self._install_archive(paths)
            raw = paths.archive_path.read_bytes()
            paths.archive_path.write_bytes(raw[: len(raw) // 2])
            with self.assertRaises(HandoffReceiveBlocked):
                receive_handoff(paths)
            self.assertFalse(paths.staging_root.exists())
            self.assertFalse(paths.final_root.exists())

        self.assertGreater(len(expected_directories), 0)

    def test_unsafe_root_path_and_type_members_fail_before_writes(self) -> None:
        _expected_directories, expected_files = _archive_expected()
        target = sorted(expected_files)[0]
        unsafe_names = {
            "traversal": f"{DIAGNOSTIC_TASK_ID}/days/../escape.json",
            "backslash": f"{DIAGNOSTIC_TASK_ID}\\escape.json",
            "absolute": "/absolute.json",
            "wrong-root": f"another-task/{target.rsplit('/', 1)[-1]}",
        }
        for label, unsafe_name in unsafe_names.items():
            with self.subTest(label=label), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                self._install_archive(paths, rename={target: unsafe_name})
                with self.assertRaises(HandoffReceiveBlocked):
                    receive_handoff(paths)
                self.assertFalse(paths.staging_root.exists())
                self.assertFalse(paths.final_root.exists())

        for label, member_type, linkname in (
            ("symlink", tarfile.SYMTYPE, "handoff-manifest.json"),
            ("hardlink", tarfile.LNKTYPE, "handoff-manifest.json"),
            ("device", tarfile.CHRTYPE, ""),
            ("fifo", tarfile.FIFOTYPE, ""),
        ):
            with self.subTest(label=label), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                self._install_archive(
                    paths,
                    type_overrides={target: (member_type, linkname)},
                )
                with self.assertRaises(HandoffReceiveBlocked):
                    receive_handoff(paths)
                self.assertFalse(paths.staging_root.exists())
                self.assertFalse(paths.final_root.exists())

    def test_size_bounds_and_corrupt_payload_fail_closed(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            self._install_archive(paths)
            with patch(
                "research_pipeline.microstructure_handoff_receive.MAX_MEMBER_BYTES",
                1,
            ), self.assertRaisesRegex(HandoffReceiveBlocked, "size bound"):
                receive_handoff(paths)
            self.assertFalse(paths.staging_root.exists())

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            self._install_archive(paths)
            with patch(
                "research_pipeline.microstructure_handoff_receive.MAX_ARCHIVE_BYTES",
                1,
            ), self.assertRaisesRegex(HandoffReceiveBlocked, "archive size"):
                receive_handoff(paths)
            self.assertFalse(paths.staging_root.exists())

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            corrupted = dict(self.fixture.package_files)
            corrupted["handoff-manifest.json"] += b" "
            original = self.fixture.package_files
            try:
                self.fixture.package_files = corrupted
                self._install_archive(paths)
            finally:
                self.fixture.package_files = original
            with self.assertRaises(HandoffReceiveBlocked):
                receive_handoff(paths)
            self.assertTrue(paths.staging_root.exists())
            self.assertFalse(paths.final_root.exists())

    def test_stale_staging_and_conflicting_final_are_preserved(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            paths.staging_root.mkdir()
            marker = paths.staging_root / "partial"
            marker.write_bytes(b"preserve")
            with self.assertRaisesRegex(HandoffReceiveBlocked, "staging root"):
                receive_handoff(paths)
            self.assertEqual(marker.read_bytes(), b"preserve")

        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            paths.final_root.mkdir()
            marker = paths.final_root / "conflict"
            marker.write_bytes(b"preserve")
            with self.assertRaises(HandoffReceiveBlocked):
                receive_handoff(paths)
            self.assertEqual(marker.read_bytes(), b"preserve")

    def test_reparse_archive_is_rejected_when_supported(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self._install(Path(directory))
            outside = Path(directory) / "outside.tar"
            self._write_tar(outside, self._entries())
            try:
                paths.archive_path.symlink_to(outside)
            except OSError as error:
                self.skipTest(f"symlink creation unavailable: {error}")
            with self.assertRaisesRegex(HandoffReceiveBlocked, "link or reparse"):
                receive_handoff(paths)
            self.assertFalse(paths.staging_root.exists())
            self.assertFalse(paths.final_root.exists())

    def test_post_stage_package_repository_and_publish_race_are_blocked(self) -> None:
        def mutate_package(paths: RuntimePaths) -> None:
            manifest = paths.staging_root / "handoff-manifest.json"
            manifest.write_bytes(manifest.read_bytes() + b" ")

        def mutate_repository(paths: RuntimePaths) -> None:
            target = paths.repository_root / "docs" / "server-research-worker-v2.md"
            target.write_bytes(target.read_bytes() + b" ")

        def create_final(paths: RuntimePaths) -> None:
            paths.final_root.mkdir()

        for label, mutation in (
            ("package", mutate_package),
            ("repository", mutate_repository),
            ("publish-race", create_final),
        ):
            with self.subTest(label=label), TemporaryDirectory() as directory:
                paths = self._install(Path(directory))
                self._install_archive(paths)
                with self.assertRaises(HandoffReceiveBlocked):
                    receive_handoff(paths, before_publish=mutation)
                self.assertTrue(paths.staging_root.exists())
                if label != "publish-race":
                    self.assertFalse(paths.final_root.exists())

    def test_powershell_entrypoint_is_fixed_single_invocation_transport(self) -> None:
        script = (
            REPOSITORY_ROOT / "scripts" / "pull_microstructure_v3_handoff_ssh.ps1"
        ).read_text(encoding="utf-8")
        required = (
            "/var/lib/agora-research/microstructure-v3-handoff-export",
            "local-node-microstructure-v3-evidence-diagnostic-v1",
            "--format=ustar",
            "BatchMode=yes",
            "RedirectStandardOutput = $true",
            "BaseStream.CopyTo($partialStream)",
            "[System.IO.FileMode]::CreateNew",
            "[System.IO.File]::Move($partialPath, $archivePath)",
            "python -m research_pipeline.microstructure_handoff_receive",
            "Remove-Item -LiteralPath $partialPath -Force",
        )
        for text in required:
            self.assertIn(text, script)
        forbidden = (
            "Start-Sleep",
            "Register-ScheduledTask",
            "New-ScheduledTask",
            "New-Service",
            "Start-Service",
            " scp ",
            "microstructure_handoff_runner",
            "microstructure_interpretation_runner",
            "while (",
        )
        for text in forbidden:
            self.assertNotIn(text, script)
        top_level_parameters = script.split(")\n\nSet-StrictMode", 1)[0]
        self.assertTrue(top_level_parameters.startswith("param("))
        self.assertEqual(top_level_parameters.count("[string]$"), 2)
        self.assertIn("[string]$SshHost", top_level_parameters)
        self.assertIn("[string]$SshKey", top_level_parameters)


if __name__ == "__main__":
    unittest.main()
