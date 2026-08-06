from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import hashlib
import inspect
import json
import os
from pathlib import Path
import stat
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_intake import (
    RecoveryBlocked,
    load_canonical_state_bytes,
    state_lock_path,
    state_temp_path,
)
from research_pipeline.microstructure_intake_cli import (
    MINIMUM_FREE_BYTES,
    PublishedDay,
    RuntimePaths,
    _apply_metadata_freeze,
    _apply_metadata_entries_fd,
    _metadata_targets,
    main,
    run,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    DAY_SCHEMA_SHA256,
    DIAGNOSTIC_CONTRACT_SHA256,
    REQUIRED_DAYS,
    SOURCE_CONTRACT_SHA256,
    canonical_json_bytes,
    validate_day_bundle,
)
from research_pipeline.tests.test_microstructure_source_contract import (
    DIAGNOSTIC_ID,
    _day_bundle,
    _envelope,
    _reseal_envelope,
)


class MicrostructureIntakeCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.start_day = date(2026, 1, 2)
        self.now = datetime(2026, 1, 1, 12, tzinfo=timezone.utc)
        self.ingest_now = datetime(2026, 2, 1, 12, tzinfo=timezone.utc)
        self.release = root / "deterministic-fixture-release"
        (self.release / ".release").mkdir(parents=True)
        self.manifest_bytes = b"fixture manifest\n"
        self.manifest_hash = hashlib.sha256(self.manifest_bytes).hexdigest()
        (self.release / ".release" / "source.sha256").write_bytes(
            self.manifest_bytes
        )
        (self.release / ".release" / "provenance.json").write_bytes(
            canonical_json_bytes(
                {
                    "schema_version": "1",
                    "release_id": self.release.name,
                    "source_manifest_sha256": self.manifest_hash,
                }
            )
        )
        self.paths = RuntimePaths(
            binding=root / "binding.json",
            drop_root=root / "drop",
            staging_root=root / "staging",
            state_root=root / "state",
            release=self.release,
        )
        self.paths.drop_root.mkdir()
        self.paths.staging_root.mkdir()
        self.paths.state_root.mkdir()
        self._write_binding()
        self.free_bytes = lambda _path: MINIMUM_FREE_BYTES
        self.device_id = lambda _path: 7

    @property
    def state_path(self) -> Path:
        return self.paths.state_root / f"{DIAGNOSTIC_ID}.json"

    def _write_binding(self, **changes: object) -> None:
        binding: dict[str, object] = {
            "schema_version": "1",
            "authorization": AUTHORIZATION,
            "forward_start_day": self.start_day.isoformat(),
            "required_complete_utc_days": REQUIRED_DAYS,
            "diagnostic_id": DIAGNOSTIC_ID,
            "source_contract_sha256": SOURCE_CONTRACT_SHA256,
            "day_schema_sha256": DAY_SCHEMA_SHA256,
            "diagnostic_contract_sha256": DIAGNOSTIC_CONTRACT_SHA256,
            "producer_release_id": self.release.name,
            "producer_manifest_sha256": self.manifest_hash,
        }
        binding.update(changes)
        self.paths.binding.write_bytes(canonical_json_bytes(binding))

    def _run(self, command: str, **overrides: object) -> str:
        arguments: dict[str, object] = {
            "paths": self.paths,
            "clock": lambda: self.now if command == "initialize" else self.ingest_now,
            "free_bytes": self.free_bytes,
            "device_id": self.device_id,
            "freezer": self._read_only_freezer,
        }
        arguments.update(overrides)
        return run(command, **arguments)  # type: ignore[arg-type]

    @staticmethod
    def _read_only_freezer(published: object) -> tuple[bytes, bytes]:
        return published.bundle.read_bytes(), published.envelope.read_bytes()  # type: ignore[attr-defined]

    def _publish_days(self, count: int, *, reservations: bool = True) -> None:
        predecessor_day: date | None = None
        predecessor_hash: str | None = None
        for index in range(count):
            day = self.start_day + timedelta(days=index)
            bundle = _day_bundle(day)
            bundle_bytes = canonical_json_bytes(bundle)
            envelope = _envelope(
                bundle,
                predecessor_day=predecessor_day,
                predecessor_bundle_sha256=predecessor_hash,
            )
            envelope["producer_release_id"] = self.release.name
            envelope["producer_manifest_sha256"] = self.manifest_hash
            _reseal_envelope(envelope)
            day_root = self.paths.drop_root / day.isoformat()
            day_root.mkdir()
            (day_root / f"okx-btc-usdt-microstructure-{day}.json").write_bytes(
                bundle_bytes
            )
            (
                day_root
                / f"okx-btc-usdt-microstructure-{day}.envelope.json"
            ).write_bytes(canonical_json_bytes(envelope))
            if reservations:
                (self.paths.drop_root / f".{day}.publish-reserved").write_bytes(b"")
            predecessor_day = day
            predecessor_hash = str(
                validate_day_bundle(bundle, raw_bytes=bundle_bytes)["bundle_sha256"]
            )

    def _initialize(self) -> None:
        self.assertEqual("WAITING_FOR_DAY", self._run("initialize"))

    def test_initialize_is_future_only_and_restart_validates_without_overwrite(self) -> None:
        self._initialize()
        original = self.state_path.read_bytes()
        self.assertEqual("WAITING_FOR_DAY", self._run("initialize"))
        self.assertEqual(original, self.state_path.read_bytes())
        self._write_binding(forward_start_day=self.now.date().isoformat())
        with self.assertRaisesRegex(RecoveryBlocked, "NONFUTURE"):
            self._run("initialize")

    def test_binding_is_exact_canonical_and_release_bound(self) -> None:
        value = json.loads(self.paths.binding.read_text(encoding="utf-8"))
        value["extra"] = True
        self.paths.binding.write_bytes(canonical_json_bytes(value))
        with self.assertRaisesRegex(RecoveryBlocked, "BINDING"):
            self._run("initialize")
        self._write_binding(producer_manifest_sha256="0" * 64)
        with self.assertRaisesRegex(RecoveryBlocked, "MANIFEST"):
            self._run("initialize")
        self.paths.binding.write_bytes(
            b'{"schema_version":"1","schema_version":"1"}'
        )
        with self.assertRaises(Exception):
            self._run("initialize")

    def test_cli_has_only_two_fixed_zero_configuration_commands(self) -> None:
        self.assertEqual(2, main([]))
        self.assertEqual(2, main(["initialize", "/tmp/other"]))
        self.assertEqual(2, main(["scan"]))

    def test_sorted_scan_reservations_duplicate_and_unchanged_bytes(self) -> None:
        self._initialize()
        self._publish_days(3, reservations=True)
        order: list[date] = []
        before = {
            path: path.read_bytes()
            for path in self.paths.drop_root.rglob("*")
            if path.is_file()
        }

        def freezer(published: object) -> tuple[bytes, bytes]:
            order.append(published.day)  # type: ignore[attr-defined]
            return self._read_only_freezer(published)

        self.assertEqual("WAITING_FOR_DAY", self._run("ingest", freezer=freezer))
        self.assertEqual(
            [self.start_day + timedelta(days=index) for index in range(3)], order
        )
        state_after = self.state_path.read_bytes()
        order.clear()
        self.assertEqual(
            "IDEMPOTENT_DUPLICATE", self._run("ingest", freezer=freezer)
        )
        self.assertEqual(state_after, self.state_path.read_bytes())
        self.assertEqual(
            before,
            {
                path: path.read_bytes()
                for path in self.paths.drop_root.rglob("*")
                if path.is_file()
            },
        )

    def test_invalid_evidence_commits_blocked_and_terminal_is_noop(self) -> None:
        self._initialize()
        self._publish_days(1)
        bundle = (
            self.paths.drop_root
            / self.start_day.isoformat()
            / f"okx-btc-usdt-microstructure-{self.start_day}.json"
        )
        bundle.write_bytes(b" " + bundle.read_bytes())
        self.assertEqual("INTEGRITY_BLOCKED", self._run("ingest"))
        blocked = self.state_path.read_bytes()

        def forbidden_freezer(_published: object) -> tuple[bytes, bytes]:
            raise AssertionError("terminal intake touched evidence")

        self.assertEqual(
            "INTEGRITY_BLOCKED",
            self._run(
                "ingest",
                free_bytes=lambda _path: 0,
                device_id=lambda _path: 999,
                freezer=forbidden_freezer,
            ),
        )
        self.assertEqual(blocked, self.state_path.read_bytes())

    def test_envelope_release_mismatch_commits_validated_blocked_state(self) -> None:
        self._initialize()
        self._publish_days(1)
        envelope_path = (
            self.paths.drop_root
            / self.start_day.isoformat()
            / f"okx-btc-usdt-microstructure-{self.start_day}.envelope.json"
        )
        envelope = json.loads(envelope_path.read_text(encoding="utf-8"))
        envelope["producer_release_id"] = "different-release"
        _reseal_envelope(envelope)
        envelope_path.write_bytes(canonical_json_bytes(envelope))
        self.assertEqual("INTEGRITY_BLOCKED", self._run("ingest"))
        state = load_canonical_state_bytes(self.state_path.read_bytes())
        self.assertEqual("INTEGRITY_BLOCKED", state["status"])
        self.assertEqual("CONTRACT_HASH_MISMATCH", state["failure"]["code"])

    def test_fourteen_days_reach_terminal_readiness(self) -> None:
        self._initialize()
        self._publish_days(REQUIRED_DAYS, reservations=True)
        self.assertEqual("DIAGNOSTIC_READY", self._run("ingest"))
        state = load_canonical_state_bytes(self.state_path.read_bytes())
        self.assertEqual(REQUIRED_DAYS, len(state["accepted_days"]))
        self.assertEqual("DIAGNOSTIC_READY", state["status"])

    def test_incomplete_extra_and_symlink_shapes_are_recovery_blocked(self) -> None:
        self._initialize()
        day_root = self.paths.drop_root / self.start_day.isoformat()
        day_root.mkdir()
        (self.paths.drop_root / f".{self.start_day}.publish-reserved").write_bytes(
            b""
        )
        (day_root / "unexpected").write_bytes(b"preserve")
        with self.assertRaisesRegex(RecoveryBlocked, "DAY_SHAPE"):
            self._run("ingest")
        self.assertEqual(b"preserve", (day_root / "unexpected").read_bytes())
        (day_root / "unexpected").unlink()
        (self.paths.drop_root / "unexpected-root").write_bytes(b"preserve")
        with self.assertRaisesRegex(RecoveryBlocked, "DROP_ENTRY"):
            self._run("ingest")

        class SymlinkEntry:
            name = "linked-day"
            path = "linked-day"

            @staticmethod
            def is_symlink() -> bool:
                return True

        with patch(
            "research_pipeline.microstructure_intake_cli.os.scandir",
            return_value=[SymlinkEntry()],
        ):
            with self.assertRaisesRegex(RecoveryBlocked, "SYMLINK"):
                self._run("ingest")

    def test_stale_lock_and_temp_recovery_are_never_repaired(self) -> None:
        self._initialize()
        lock = state_lock_path(self.state_path)
        lock.mkdir()
        with self.assertRaisesRegex(RecoveryBlocked, "LOCK_PRESENT"):
            self._run("ingest")
        self.assertTrue(lock.is_dir())
        lock.rmdir()
        temporary = state_temp_path(self.state_path)
        temporary.write_bytes(b"preserve")
        with self.assertRaisesRegex(RecoveryBlocked, "STALE_TEMP"):
            self._run("ingest")
        self.assertEqual(b"preserve", temporary.read_bytes())

    def test_capacity_same_filesystem_and_entry_bounds_fail_closed(self) -> None:
        with self.assertRaisesRegex(RecoveryBlocked, "CAPACITY"):
            self._run("initialize", free_bytes=lambda _path: MINIMUM_FREE_BYTES - 1)
        with self.assertRaisesRegex(RecoveryBlocked, "FILESYSTEM"):
            self._run(
                "initialize",
                device_id=lambda path: 1 if path == self.paths.staging_root else 2,
            )
        for index in range(REQUIRED_DAYS + 1):
            day = self.start_day + timedelta(days=index)
            (self.paths.drop_root / day.isoformat()).mkdir()
        with self.assertRaisesRegex(RecoveryBlocked, "ENTRY_BOUND"):
            self._run("initialize")

    def test_reservation_must_be_zero_byte_matched_and_in_window(self) -> None:
        unmatched = self.paths.drop_root / f".{self.start_day}.publish-reserved"
        unmatched.write_bytes(b"")
        with self.assertRaisesRegex(RecoveryBlocked, "UNMATCHED"):
            self._run("initialize")
        unmatched.write_bytes(b"not-empty")
        with self.assertRaisesRegex(RecoveryBlocked, "RESERVATION"):
            self._run("initialize")

    def test_day_without_reservation_is_recovery_blocked(self) -> None:
        self._publish_days(1, reservations=False)
        with self.assertRaisesRegex(RecoveryBlocked, "UNMATCHED"):
            self._run("initialize")

    def test_metadata_freeze_protects_directory_before_children(self) -> None:
        day = self.start_day
        directory = self.paths.drop_root / day.isoformat()
        published = PublishedDay(
            day=day,
            directory=directory,
            bundle=directory / f"okx-btc-usdt-microstructure-{day}.json",
            envelope=(
                directory / f"okx-btc-usdt-microstructure-{day}.envelope.json"
            ),
            reservation=self.paths.drop_root / f".{day}.publish-reserved",
        )
        targets = _metadata_targets(published)
        self.assertEqual((published.directory, 0o550), targets[0])
        self.assertEqual((published.reservation, 0o440), targets[1])
        self.assertEqual({published.bundle, published.envelope}, {item[0] for item in targets[2:]})

    def test_second_metadata_freeze_is_a_zero_mutation_noop(self) -> None:
        day = self.start_day
        directory = self.paths.drop_root / day.isoformat()
        directory.mkdir()
        bundle = directory / f"okx-btc-usdt-microstructure-{day}.json"
        envelope = directory / f"okx-btc-usdt-microstructure-{day}.envelope.json"
        reservation = self.paths.drop_root / f".{day}.publish-reserved"
        bundle.write_bytes(b"bundle")
        envelope.write_bytes(b"envelope")
        reservation.write_bytes(b"")
        published = PublishedDay(day, directory, bundle, envelope, reservation)
        targets = _metadata_targets(published)
        metadata = {
            path: {
                "uid": 1001,
                "gid": 1002,
                "mode": (stat.S_IFDIR if index == 0 else stat.S_IFREG) | 0o750,
            }
            for index, (path, _mode) in enumerate(targets)
        }
        mutations: list[tuple[str, Path]] = []

        def fake_lstat(path: Path) -> object:
            value = metadata[path]
            return SimpleNamespace(
                st_uid=value["uid"], st_gid=value["gid"], st_mode=value["mode"]
            )

        def fake_chown(path: Path, uid: int, gid: int) -> None:
            mutations.append(("chown", path))
            metadata[path]["uid"] = uid
            metadata[path]["gid"] = gid

        def fake_chmod(path: Path, mode: int) -> None:
            mutations.append(("chmod", path))
            metadata[path]["mode"] = (metadata[path]["mode"] & stat.S_IFMT(metadata[path]["mode"])) | mode

        before = {path: path.read_bytes() for path in (bundle, envelope, reservation)}
        _apply_metadata_freeze(
            published,
            root_uid=0,
            research_gid=2000,
            lstat=fake_lstat,  # type: ignore[arg-type]
            chown=fake_chown,
            chmod=fake_chmod,
        )
        self.assertEqual(len(targets) * 2, len(mutations))
        mutations.clear()
        _apply_metadata_freeze(
            published,
            root_uid=0,
            research_gid=2000,
            lstat=fake_lstat,  # type: ignore[arg-type]
            chown=fake_chown,
            chmod=fake_chmod,
        )
        self.assertEqual([], mutations)
        self.assertEqual(
            before, {path: path.read_bytes() for path in (bundle, envelope, reservation)}
        )

    def test_mode_change_uses_post_protection_portable_chmod(self) -> None:
        source = inspect.getsource(_apply_metadata_entries_fd)
        self.assertIn('getattr(os, "O_NOFOLLOW", None)', source)
        self.assertIn("os.fchown(descriptor, root_uid, research_gid)", source)
        self.assertIn("os.fchmod(descriptor, mode)", source)
        self.assertNotIn("os.chmod(", source)

    def test_units_freeze_capabilities_and_parent_boundary_are_exact(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        service = (
            repository
            / "scripts/research-worker/agora-research-microstructure-intake.service"
        ).read_text(encoding="utf-8")
        path_unit = (
            repository
            / "scripts/research-worker/agora-research-microstructure-intake.path"
        ).read_text(encoding="utf-8")
        installer = (
            repository / "scripts/research-worker/install-upgrade.sh"
        ).read_text(encoding="utf-8")
        capabilities = "CAP_DAC_READ_SEARCH CAP_CHOWN CAP_FOWNER"
        self.assertIn(f"CapabilityBoundingSet={capabilities}", service)
        self.assertIn(f"AmbientCapabilities={capabilities}", service)
        self.assertNotIn("CAP_DAC_OVERRIDE", service)
        self.assertNotIn("SupplementaryGroups=", service)
        self.assertIn("IPAddressDeny=any", service)
        self.assertIn("Restart=no", service)
        self.assertIn(
            "ReadWritePaths=/var/lib/agora-evidence-source/microstructure-drop",
            service,
        )
        self.assertIn(
            "PathChanged=/var/lib/agora-evidence-source/microstructure-drop",
            path_unit,
        )
        self.assertNotIn("OnCalendar=", path_unit)
        self.assertIn(
            'install -d -o root -g "$EVIDENCE_GROUP" -m 1770 "$MICROSTRUCTURE_DROP"',
            installer,
        )
        self.assertNotIn('usermod -a -G "$EVIDENCE_GROUP" "$WORKER_USER"', installer)
        self.assertIn('gpasswd -d "$WORKER_USER" "$EVIDENCE_GROUP"', installer)
        self.assertIn('chown "$WORKER_USER:$WORKER_GROUP" "$validated_state_file"', installer)
        self.assertIn('chmod 0600 "$validated_state_file"', installer)
        for unit_name in (
            "agora-research-mcp.service",
            "agora-research-evidence-ingest.service",
        ):
            unit = (repository / "scripts/research-worker" / unit_name).read_text(
                encoding="utf-8"
            )
            self.assertIn("SupplementaryGroups=agora-evidence", unit)


if __name__ == "__main__":
    unittest.main()
