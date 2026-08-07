from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
from datetime import date, timedelta
import hashlib
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_handoff import (
    HANDOFF_CANONICALIZATION,
    INFERENCE_BOUNDARIES,
    MANIFEST_NAME,
    MANIFEST_TYPE,
    RESULT_NAME,
)
from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_ID,
    DIAGNOSTIC_TASK_ID,
    DIAGNOSTIC_TASK_RELATIVE,
    DIAGNOSTIC_TASK_SHA256,
    EXPECTED_REPOSITORY_INPUTS,
    ORDERED_DAYS,
    PRODUCTION_PATHS,
    REPOSITORY_ROOT,
    START_DAY,
    TASK_OWNED_ROOT,
    HandoffRunnerBlocked,
    RuntimePaths,
    main,
    run_handoff,
)
from research_pipeline.microstructure_intake import canonical_v3_state_bytes
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    V3_DROP_ENVELOPE_SCHEMA_SHA256,
    V3_INTAKE_STATE_SCHEMA_SHA256,
    V3_SOURCE_CONTRACT_SHA256,
    accept_v3_intake_day,
    canonical_json_bytes,
    canonical_sha256,
    initial_v3_intake_state,
)
from research_pipeline.tests.test_microstructure_v3_intake_isolation import (
    _accepted_at,
    _v3_day_bundle,
    _v3_envelope,
)


PRODUCER_RELEASE_ID = "deterministic-v3-local-runner-fixture"
PRODUCER_MANIFEST_SHA256 = "b" * 64


def _seal_manifest(value: dict[str, object]) -> None:
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": HANDOFF_CANONICALIZATION,
    }


def _bundle_name(bundle_day: date) -> str:
    day_text = bundle_day.isoformat()
    return f"days/{day_text}/okx-btc-usdt-microstructure-{day_text}.json"


def _envelope_name(bundle_day: date) -> str:
    day_text = bundle_day.isoformat()
    return (
        f"days/{day_text}/okx-btc-usdt-microstructure-{day_text}.envelope.json"
    )


class _Fixture:
    def __init__(self) -> None:
        state = initial_v3_intake_state(
            DIAGNOSTIC_ID,
            START_DAY,
            as_of_day=START_DAY - timedelta(days=1),
        )
        self.initial_state_raw = canonical_v3_state_bytes(state)
        day_material: list[tuple[date, bytes, bytes]] = []
        predecessor_day: date | None = None
        predecessor_hash: str | None = None
        for bundle_day in ORDERED_DAYS:
            bundle = _v3_day_bundle(bundle_day)
            envelope = _v3_envelope(
                bundle,
                predecessor_day=predecessor_day,
                predecessor_bundle_sha256=predecessor_hash,
            )
            bundle_raw = canonical_json_bytes(bundle)
            bundle_hash = hashlib.sha256(bundle_raw).hexdigest()
            envelope["diagnostic_id"] = DIAGNOSTIC_ID
            envelope["producer_release_id"] = PRODUCER_RELEASE_ID
            envelope["producer_manifest_sha256"] = PRODUCER_MANIFEST_SHA256
            envelope["idempotency_key"] = (
                f"{DIAGNOSTIC_ID}:{bundle_day.isoformat()}:{bundle_hash}"
            )
            envelope["envelope_seal"]["payload_sha256"] = canonical_sha256(
                envelope, exclude_key="envelope_seal"
            )
            envelope_raw = canonical_json_bytes(envelope)
            state = accept_v3_intake_day(
                state,
                envelope,
                bundle,
                raw_envelope_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                accepted_at=_accepted_at(bundle_day),
                observed_producer_identity="agora-evidence-source",
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
            )
            day_material.append((bundle_day, bundle_raw, envelope_raw))
            predecessor_day = bundle_day
            predecessor_hash = bundle_hash

        state_raw = canonical_v3_state_bytes(state)
        state_name = f"canonical/{DIAGNOSTIC_ID}.json"
        self.package_files: dict[str, bytes] = {state_name: state_raw}
        manifest_days: list[dict[str, object]] = []
        for (bundle_day, bundle_raw, envelope_raw), record in zip(
            day_material, state["accepted_days"]
        ):
            bundle_name = _bundle_name(bundle_day)
            envelope_name = _envelope_name(bundle_day)
            self.package_files[bundle_name] = bundle_raw
            self.package_files[envelope_name] = envelope_raw
            manifest_days.append(
                {
                    "day": bundle_day.isoformat(),
                    "bundle_relative_name": bundle_name,
                    "bundle_sha256": hashlib.sha256(bundle_raw).hexdigest(),
                    "envelope_relative_name": envelope_name,
                    "envelope_sha256": hashlib.sha256(envelope_raw).hexdigest(),
                    "predecessor_day": (
                        None
                        if bundle_day == START_DAY
                        else (bundle_day - timedelta(days=1)).isoformat()
                    ),
                    "predecessor_bundle_sha256": record[
                        "predecessor_bundle_sha256"
                    ],
                    "accepted_at": record["accepted_at"],
                    "cumulative_chain_sha256": record["cumulative_chain_sha256"],
                }
            )
        self.manifest: dict[str, object] = {
            "schema_version": "1",
            "manifest_type": MANIFEST_TYPE,
            "authorization": AUTHORIZATION,
            "task_id": DIAGNOSTIC_TASK_ID,
            "task_sha256": DIAGNOSTIC_TASK_SHA256,
            "canonical_state": {
                "relative_name": state_name,
                "sha256": hashlib.sha256(state_raw).hexdigest(),
                "intake_state_schema_sha256": V3_INTAKE_STATE_SCHEMA_SHA256,
                "state_type": "SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE",
                "state_authority": "SERVER_CANONICAL",
                "diagnostic_id": DIAGNOSTIC_ID,
                "status": "DIAGNOSTIC_READY",
                "start_day": START_DAY.isoformat(),
                "last_day": ORDERED_DAYS[-1].isoformat(),
                "required_day_count": 14,
                "accepted_day_count": 14,
                "chain_head_sha256": state["chain_head_sha256"],
                "source_contract_sha256": V3_SOURCE_CONTRACT_SHA256,
                "drop_envelope_schema_sha256": V3_DROP_ENVELOPE_SCHEMA_SHA256,
                "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
                "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
            },
            "source_release": {
                "producer_identity": "agora-evidence-source",
                "producer_release_id": PRODUCER_RELEASE_ID,
                "producer_manifest_sha256": PRODUCER_MANIFEST_SHA256,
            },
            "days": manifest_days,
            "inference_boundaries": deepcopy(INFERENCE_BOUNDARIES),
        }
        _seal_manifest(self.manifest)
        self.package_files[MANIFEST_NAME] = canonical_json_bytes(self.manifest)

    @staticmethod
    def _write(path: Path, raw: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def install(
        self,
        base: Path,
        *,
        package_files: dict[str, bytes] | None = None,
    ) -> RuntimePaths:
        repository_root = base / "repository"
        task_owned_root = base / "inbox" / DIAGNOSTIC_TASK_ID
        source_task = REPOSITORY_ROOT.joinpath(*DIAGNOSTIC_TASK_RELATIVE.split("/"))
        self._write(
            repository_root.joinpath(*DIAGNOSTIC_TASK_RELATIVE.split("/")),
            source_task.read_bytes(),
        )
        for relative_name in EXPECTED_REPOSITORY_INPUTS:
            source = REPOSITORY_ROOT.joinpath(*relative_name.split("/"))
            self._write(
                repository_root.joinpath(*relative_name.split("/")),
                source.read_bytes(),
            )
        selected = self.package_files if package_files is None else package_files
        for relative_name, raw in selected.items():
            self._write(task_owned_root.joinpath(*relative_name.split("/")), raw)
        return RuntimePaths(repository_root, task_owned_root)

    def chain_drift_files(self) -> dict[str, bytes]:
        changed = dict(self.package_files)
        envelope_name = _envelope_name(ORDERED_DAYS[1])
        envelope = json.loads(changed[envelope_name].decode("utf-8"))
        envelope["predecessor_bundle_sha256"] = "f" * 64
        envelope["envelope_seal"]["payload_sha256"] = canonical_sha256(
            envelope, exclude_key="envelope_seal"
        )
        envelope_raw = canonical_json_bytes(envelope)
        changed[envelope_name] = envelope_raw
        manifest = deepcopy(self.manifest)
        manifest["days"][1]["envelope_sha256"] = hashlib.sha256(
            envelope_raw
        ).hexdigest()
        _seal_manifest(manifest)
        changed[MANIFEST_NAME] = canonical_json_bytes(manifest)
        return changed


class MicrostructureHandoffRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = _Fixture()

    def test_production_constants_and_zero_argument_cli_are_fixed(self) -> None:
        self.assertEqual(REPOSITORY_ROOT, PRODUCTION_PATHS.repository_root)
        self.assertEqual(TASK_OWNED_ROOT, PRODUCTION_PATHS.task_owned_root)
        self.assertEqual(
            TASK_OWNED_ROOT.as_posix(),
            "C:/Users/Redan/.codex/local-research-node/inbox/"
            "local-node-microstructure-v3-evidence-diagnostic-v1",
        )
        self.assertEqual(ORDERED_DAYS[0], date(2026, 8, 8))
        self.assertEqual(ORDERED_DAYS[-1], date(2026, 8, 21))
        with patch(
            "research_pipeline.microstructure_handoff_runner.run_handoff",
            return_value={"status": "CREATED"},
        ) as mocked, redirect_stdout(io.StringIO()):
            self.assertEqual(main([]), 0)
            mocked.assert_called_once_with(PRODUCTION_PATHS)
            mocked.reset_mock()
            self.assertEqual(main(["--root", "elsewhere"]), 2)
            mocked.assert_not_called()

    def test_valid_creation_order_wrapping_idempotency_and_conflict_rejection(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            first = run_handoff(paths)
            result_path = paths.task_owned_root / RESULT_NAME
            raw = result_path.read_bytes()
            result = json.loads(raw.decode("utf-8"))
            diagnostic = result["diagnostic_result"]

            self.assertEqual(first["status"], "CREATED")
            self.assertEqual(raw, canonical_json_bytes(result))
            self.assertEqual(result["task_id"], DIAGNOSTIC_TASK_ID)
            self.assertEqual(result["task_sha256"], DIAGNOSTIC_TASK_SHA256)
            self.assertEqual(result["inference_boundaries"], INFERENCE_BOUNDARIES)
            self.assertEqual(
                [item["path"] for item in diagnostic["input"]["files"]],
                [_bundle_name(bundle_day) for bundle_day in ORDERED_DAYS],
            )
            self.assertEqual(
                first["sha256"], hashlib.sha256(raw).hexdigest()
            )
            modified_time = result_path.stat().st_mtime_ns
            second = run_handoff(paths, analyzer=lambda *_args, **_kwargs: diagnostic)
            self.assertEqual(second["status"], "IDEMPOTENT_IDENTICAL")
            self.assertEqual(result_path.stat().st_mtime_ns, modified_time)
            self.assertEqual(result_path.read_bytes(), raw)

        for conflicting in (b"{", canonical_json_bytes({"conflict": True})):
            with self.subTest(conflicting=conflicting), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory))
                result_path = paths.task_owned_root / RESULT_NAME
                result_path.write_bytes(conflicting)
                with self.assertRaisesRegex(
                    HandoffRunnerBlocked, "conflicting or partial"
                ):
                    run_handoff(paths, analyzer=lambda *_args, **_kwargs: diagnostic)
                self.assertEqual(result_path.read_bytes(), conflicting)

    def test_absent_or_not_ready_package_creates_no_result(self) -> None:
        with TemporaryDirectory() as directory:
            base = Path(directory)
            paths = self.fixture.install(base)
            for relative_name in self.fixture.package_files:
                target = paths.task_owned_root.joinpath(*relative_name.split("/"))
                target.unlink()
            for child in sorted(paths.task_owned_root.rglob("*"), reverse=True):
                if child.is_dir():
                    child.rmdir()
            paths.task_owned_root.rmdir()
            with self.assertRaisesRegex(HandoffRunnerBlocked, "missing or inaccessible"):
                run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            state_path = paths.task_owned_root / "canonical" / f"{DIAGNOSTIC_ID}.json"
            state_path.write_bytes(self.fixture.initial_state_raw)
            with self.assertRaises(HandoffRunnerBlocked):
                run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

    def test_task_and_repository_hash_drift_fail_before_analysis(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            task_path = paths.repository_root.joinpath(*DIAGNOSTIC_TASK_RELATIVE.split("/"))
            task_path.write_bytes(task_path.read_bytes() + b" ")
            with self.assertRaisesRegex(HandoffRunnerBlocked, "task bytes changed"):
                run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            relative_name = "research_pipeline/microstructure_diagnostic.py"
            target = paths.repository_root.joinpath(*relative_name.split("/"))
            target.write_bytes(target.read_bytes() + b" ")
            with self.assertRaisesRegex(HandoffRunnerBlocked, "input hash changed"):
                run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

    def test_missing_extra_hash_and_chain_drift_fail_closed(self) -> None:
        cases: list[tuple[str, dict[str, bytes]]] = []
        missing = dict(self.fixture.package_files)
        missing.pop(_bundle_name(ORDERED_DAYS[0]))
        cases.append(("missing", missing))
        extra = dict(self.fixture.package_files)
        extra["unexpected.json"] = b"{}"
        cases.append(("extra", extra))
        hash_drift = dict(self.fixture.package_files)
        first_bundle = _bundle_name(ORDERED_DAYS[0])
        hash_drift[first_bundle] = hash_drift[first_bundle] + b" "
        cases.append(("hash", hash_drift))
        identity_drift = dict(self.fixture.package_files)
        identity_manifest = deepcopy(self.fixture.manifest)
        identity_manifest["canonical_state"]["diagnostic_id"] = "alternate-diagnostic"
        _seal_manifest(identity_manifest)
        identity_drift[MANIFEST_NAME] = canonical_json_bytes(identity_manifest)
        cases.append(("identity", identity_drift))
        cases.append(("chain", self.fixture.chain_drift_files()))

        for label, files in cases:
            with self.subTest(label=label), TemporaryDirectory() as directory:
                paths = self.fixture.install(Path(directory), package_files=files)
                with self.assertRaises(HandoffRunnerBlocked):
                    run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
                self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

    def test_link_or_reparse_input_is_rejected_when_supported(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))
            target = paths.task_owned_root.joinpath(
                *_bundle_name(ORDERED_DAYS[0]).split("/")
            )
            outside = Path(directory) / "outside.json"
            outside.write_bytes(target.read_bytes())
            target.unlink()
            try:
                target.symlink_to(outside)
            except OSError as error:
                self.skipTest(f"symlink creation unavailable: {error}")
            with self.assertRaisesRegex(
                HandoffRunnerBlocked, "link or reparse point"
            ):
                run_handoff(paths, analyzer=lambda *_args, **_kwargs: {})
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

    def test_post_analysis_package_and_repository_mutation_are_blocked(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))

            def mutate_package(*_args: object, **_kwargs: object) -> dict[str, object]:
                target = paths.task_owned_root.joinpath(
                    *_bundle_name(ORDERED_DAYS[-1]).split("/")
                )
                target.write_bytes(target.read_bytes() + b" ")
                return {}

            with self.assertRaises(HandoffRunnerBlocked):
                run_handoff(paths, analyzer=mutate_package)
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))

            def mutate_repository(*_args: object, **_kwargs: object) -> dict[str, object]:
                relative_name = "research_pipeline/microstructure_diagnostic.py"
                target = paths.repository_root.joinpath(*relative_name.split("/"))
                target.write_bytes(target.read_bytes() + b" ")
                return {}

            with self.assertRaisesRegex(HandoffRunnerBlocked, "input hash changed"):
                run_handoff(paths, analyzer=mutate_repository)
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())

    def test_analyzer_failure_produces_no_result(self) -> None:
        with TemporaryDirectory() as directory:
            paths = self.fixture.install(Path(directory))

            def fail(*_args: object, **_kwargs: object) -> dict[str, object]:
                raise RuntimeError("deterministic analyzer failure")

            with self.assertRaisesRegex(RuntimeError, "analyzer failure"):
                run_handoff(paths, analyzer=fail)
            self.assertFalse((paths.task_owned_root / RESULT_NAME).exists())


if __name__ == "__main__":
    unittest.main()
