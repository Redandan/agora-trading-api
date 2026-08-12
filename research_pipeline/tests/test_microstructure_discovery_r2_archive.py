from __future__ import annotations

from datetime import date, datetime, timezone
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from jsonschema import Draft202012Validator

from research_pipeline.microstructure_discovery_r2_archive import (
    DIAGNOSTIC_ID,
    R2ArchiveBlocked,
    RuntimePaths,
    SCHEMA_SHA256,
    SOURCE_UNIT,
    create_or_verify,
    verify,
)
from research_pipeline.microstructure_intake import initial_v3_state_bytes
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    REQUIRED_DAYS,
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    V3_SOURCE_CONTRACT_SHA256,
    canonical_json_bytes,
)


PROPERTIES = (
    b"LoadState=loaded\n"
    b"ActiveState=inactive\n"
    b"SubState=dead\n"
    b"UnitFileState=disabled\n"
    b"MainPID=0\n"
    b"Result=success\n"
    b"ExecMainCode=1\n"
    b"ExecMainStatus=1\n"
    b"FragmentPath=/etc/systemd/system/agora-research-microstructure-source.service\n"
)


class MicrostructureDiscoveryR2ArchiveTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        release = root / "releases" / "r2-release"
        (release / ".release").mkdir(parents=True)
        manifest_bytes = b"r2 frozen source manifest\n"
        manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
        (release / ".release" / "source.sha256").write_bytes(manifest_bytes)
        (release / ".release" / "provenance.json").write_bytes(
            canonical_json_bytes(
                {
                    "release_id": release.name,
                    "source_manifest_sha256": manifest_hash,
                }
            )
        )
        binding = root / "binding.json"
        start = date(2026, 8, 11)
        binding.write_bytes(
            canonical_json_bytes(
                {
                    "schema_version": "1",
                    "authorization": AUTHORIZATION,
                    "forward_start_day": start.isoformat(),
                    "required_complete_utc_days": REQUIRED_DAYS,
                    "diagnostic_id": DIAGNOSTIC_ID,
                    "source_contract_sha256": V3_SOURCE_CONTRACT_SHA256,
                    "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
                    "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
                    "producer_release_id": release.name,
                    "producer_manifest_sha256": manifest_hash,
                }
            )
        )
        state = root / "state"
        drop = root / "drop"
        staging = root / "staging"
        for directory in (state, drop, staging):
            directory.mkdir()
        (state / f"{DIAGNOSTIC_ID}.json").write_bytes(
            initial_v3_state_bytes(
                DIAGNOSTIC_ID, start, as_of_day=start - date.resolution
            )
        )
        (staging / "failure.marker").write_bytes(b"unexpected exchange event\n")
        self.paths = RuntimePaths(
            binding=binding,
            drop_root=drop,
            staging_root=staging,
            state_root=state,
            release=release,
            archive=root / "archive" / DIAGNOSTIC_ID,
            schema=Path(__file__).resolve().parents[1]
            / "okx-microstructure-discovery-r2-archive-manifest.v1.schema.json",
        )
        self.commands: list[tuple[str, ...]] = []

    def runner(self, arguments: tuple[str, ...]) -> bytes:
        self.commands.append(tuple(arguments))
        if arguments[0] == "systemctl":
            return PROPERTIES
        if arguments[0] == "journalctl":
            return b"2026-08-11 source stopped after unexpected exchange event\n"
        raise AssertionError(arguments)

    def test_schema_is_frozen_valid_draft_202012(self) -> None:
        payload = self.paths.schema.read_bytes()
        self.assertEqual(SCHEMA_SHA256, hashlib.sha256(payload).hexdigest())
        Draft202012Validator.check_schema(json.loads(payload))

    def test_create_is_hash_verified_idempotent_and_preserves_originals(self) -> None:
        originals = {
            path: path.read_bytes()
            for path in (
                self.paths.binding,
                next(self.paths.state_root.glob("*.json")),
                self.paths.release / ".release" / "source.sha256",
                self.paths.release / ".release" / "provenance.json",
            )
        }
        result = create_or_verify(
            paths=self.paths,
            runner=self.runner,
            clock=lambda: datetime(2026, 8, 12, 3, tzinfo=timezone.utc),
        )
        self.assertEqual(64, len(result))
        self.assertEqual(result, verify(paths=self.paths))
        self.assertEqual(result, create_or_verify(paths=self.paths, runner=self.runner))
        self.assertEqual(2, len(self.commands))
        self.assertEqual(originals, {path: path.read_bytes() for path in originals})
        manifest = json.loads(
            (self.paths.archive / "archive-manifest.json").read_bytes()
        )
        self.assertEqual(9, len(manifest["entries"]))
        self.assertEqual(DIAGNOSTIC_ID, manifest["diagnostic_id"])
        self.assertEqual(SOURCE_UNIT, manifest["source_unit"])

    def test_original_drift_and_nonquiescent_source_fail_closed(self) -> None:
        create_or_verify(
            paths=self.paths,
            runner=self.runner,
            clock=lambda: datetime(2026, 8, 12, 3, tzinfo=timezone.utc),
        )
        self.paths.binding.write_bytes(self.paths.binding.read_bytes() + b"\n")
        with self.assertRaisesRegex(R2ArchiveBlocked, "ORIGINAL_DRIFT"):
            verify(paths=self.paths)

        second = Path(self.temporary.name) / "second"
        second.mkdir()
        changed = PROPERTIES.replace(b"ActiveState=inactive", b"ActiveState=active")
        with self.assertRaisesRegex(R2ArchiveBlocked, "SOURCE_NOT_QUIESCENT"):
            create_or_verify(
                paths=RuntimePaths(
                    binding=self.paths.binding,
                    drop_root=self.paths.drop_root,
                    staging_root=self.paths.staging_root,
                    state_root=self.paths.state_root,
                    release=self.paths.release,
                    archive=second / DIAGNOSTIC_ID,
                    schema=self.paths.schema,
                ),
                runner=lambda arguments: changed if arguments[0] == "systemctl" else b"",
                clock=lambda: datetime(2026, 8, 12, 3, tzinfo=timezone.utc),
            )


if __name__ == "__main__":
    unittest.main()
