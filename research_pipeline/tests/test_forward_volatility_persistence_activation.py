from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from research_pipeline.forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    ROOT_TRIGGER_FINGERPRINT,
    ROOT_TRIGGER_ID,
)
from research_pipeline.forward_volatility_persistence_activation import (
    ACCEPTED_IMPLEMENTATION_COMMIT,
    ACCEPTED_RESULT_RELATIVE,
    ACCEPTED_RESULT_SHA256,
    ACTIVATION_MODULE_RELATIVE,
    ACTIVATION_RECEIPT_RETIRED,
    ACTIVATION_STATE_KEY,
    ActivationDecision,
    ActivationIntegrityError,
    EVALUATOR_MODULE_RELATIVE,
    EVALUATOR_MODULE_SHA256,
    EVALUATOR_SCHEMA_RELATIVE,
    EVALUATOR_SCHEMA_SHA256,
    HEARTBEAT_MODULE_RELATIVE,
    prepare_forward_volatility_persistence_activation,
)
from research_pipeline.heartbeat import run_heartbeat_cycle
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.storage import ResearchStore


NOW = datetime(2026, 8, 18, 1, 5, tzinfo=timezone.utc)
ROLLOVER_AT = datetime(2026, 8, 12, 1, 5, tzinfo=timezone.utc)
PRIOR_SUCCESS = "2026-08-17T01:05:00Z"


class ForwardVolatilityPersistenceActivationTest(unittest.TestCase):
    def test_root_or_same_rollover_cycle_remains_dormant_before_release_access(self) -> None:
        store = self._store()
        root = _lineage(rolled_over=False)
        successor = _lineage()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=root,
        ):
            decision = prepare_forward_volatility_persistence_activation(
                store,
                now=NOW,
                previous_success=PRIOR_SUCCESS,
                existing_receipt=None,
            )
        self.assertEqual("ACTIVATION_DORMANT_AWAITING_ROLLOVER", decision.status)

        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=successor,
        ):
            decision = prepare_forward_volatility_persistence_activation(
                store,
                now=NOW,
                previous_success="2026-08-11T01:05:00Z",
                existing_receipt=None,
            )
        self.assertEqual(
            "ACTIVATION_DORMANT_AWAITING_POST_ROLLOVER_HEARTBEAT",
            decision.status,
        )

    def test_missing_release_provenance_remains_dormant(self) -> None:
        store = self._store()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=_lineage(),
        ):
            decision = prepare_forward_volatility_persistence_activation(
                store,
                now=NOW,
                previous_success=PRIOR_SUCCESS,
                existing_receipt=None,
                worker_root=Path(self.directory.name) / "missing-worker",
            )
        self.assertEqual(
            "ACTIVATION_DORMANT_RELEASE_PROVENANCE_UNAVAILABLE", decision.status
        )

    def test_verified_release_creates_exact_receipt_once(self) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        lineage = _lineage()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=lineage,
        ):
            created = self._prepare(store, release, existing=None)
            repeated = self._prepare(store, release, existing=created.receipt)

        self.assertTrue(created.created)
        self.assertEqual("ACTIVATION_RECEIPT_READY_TO_PERSIST", created.status)
        self.assertEqual(ACCEPTED_IMPLEMENTATION_COMMIT, created.receipt["implementation_commit"])
        self.assertEqual(ACCEPTED_RESULT_SHA256, created.receipt["accepted_result_sha256"])
        self.assertEqual(release.release_id, created.receipt["worker_release_id"])
        self.assertEqual(release.source_commit, created.receipt["worker_source_commit"])
        self.assertEqual(release.manifest_hash, created.receipt["worker_manifest_sha256"])
        self.assertEqual(RESEARCH_AUTHORIZATION, created.receipt["authorization"])
        self.assertFalse(repeated.created)
        self.assertEqual(created.receipt, repeated.receipt)

    def test_lawful_multi_rollover_lineage_binds_latest_active_leaf(self) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        lineage = _lineage(rollover_depth=2)
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=lineage,
        ):
            created = self._prepare(store, release, existing=None)

        self.assertTrue(created.created)
        self.assertEqual(
            lineage.leaf_trigger["trigger_id"], created.receipt["leaf_trigger_id"]
        )
        self.assertEqual(
            lineage.leaf_trigger["fingerprint"],
            created.receipt["leaf_trigger_fingerprint"],
        )

    def test_existing_receipt_is_retired_after_later_lawful_rollover(self) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        first_leaf = _lineage(rollover_depth=1)
        later_leaf = _lineage(rollover_depth=2)
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=first_leaf,
        ):
            created = self._prepare(store, release, existing=None)
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=later_leaf,
        ):
            retired = self._prepare(store, release, existing=created.receipt)

        self.assertFalse(retired.created)
        self.assertEqual(ACTIVATION_RECEIPT_RETIRED, retired.status)
        self.assertEqual(created.receipt, retired.receipt)
        self.assertNotEqual(
            retired.receipt["leaf_trigger_id"],
            later_leaf.leaf_trigger["trigger_id"],
        )

    def test_existing_conflicting_leaf_fails_but_verified_release_upgrade_survives(
        self,
    ) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        lineage = _lineage()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=lineage,
        ):
            created = self._prepare(store, release, existing=None)
            changed = dict(created.receipt)
            changed["leaf_trigger_fingerprint"] = "c" * 64
            with self.assertRaises(ActivationIntegrityError):
                self._prepare(store, release, existing=changed)

            other = _ReleaseFixture(
                Path(self.directory.name) / "other",
                release_id="20260818T020000Z",
            )
            upgraded = self._prepare(store, other, existing=created.receipt)

        self.assertFalse(upgraded.created)
        self.assertEqual("ACTIVATION_RECEIPT_REVALIDATED", upgraded.status)
        self.assertEqual(created.receipt, upgraded.receipt)
        self.assertNotEqual(
            other.release_id, upgraded.receipt["worker_release_id"]
        )

    def test_existing_receipt_rejects_upgraded_release_with_changed_evaluator(
        self,
    ) -> None:
        store = self._store()
        original = _ReleaseFixture(Path(self.directory.name))
        lineage = _lineage()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=lineage,
        ):
            created = self._prepare(store, original, existing=None)
            upgraded = _ReleaseFixture(
                Path(self.directory.name) / "changed-upgrade",
                release_id="20260818T020000Z",
            )
            (upgraded.release / EVALUATOR_MODULE_RELATIVE).write_bytes(b"changed")
            with self.assertRaises(ActivationIntegrityError):
                self._prepare(store, upgraded, existing=created.receipt)

    def test_non_symlink_stale_or_escaped_control_current_fails_closed(self) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        release.control.unlink()
        release.control.mkdir()
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=_lineage(),
        ):
            with self.assertRaisesRegex(ActivationIntegrityError, "not a symlink"):
                self._prepare(store, release, existing=None)

        release.control.rmdir()
        escaped = Path(self.directory.name) / "escaped"
        escaped.mkdir()
        os.symlink(escaped, release.control, target_is_directory=True)
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=_lineage(),
        ):
            with self.assertRaisesRegex(ActivationIntegrityError, "not the current"):
                self._prepare(store, release, existing=None)

    def test_dirty_malformed_or_mismatched_provenance_fails_closed(self) -> None:
        mutations = (
            lambda value: value.__setitem__("source_git_dirty", True),
            lambda value: value.__setitem__("source_git_commit", "bad"),
            lambda value: value.__setitem__("source_manifest_sha256", "0" * 64),
            lambda value: value.__setitem__("unexpected", True),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                root = Path(self.directory.name) / f"provenance-{index}"
                root.mkdir()
                release = _ReleaseFixture(root)
                value = json.loads(release.provenance.read_text(encoding="utf-8"))
                mutate(value)
                release.provenance.write_text(
                    json.dumps(value, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8",
                )
                with patch(
                    "research_pipeline.forward_volatility_persistence_activation."
                    "resolve_active_forward_trigger_lineage",
                    return_value=_lineage(),
                ):
                    with self.assertRaises(ActivationIntegrityError):
                        self._prepare(self._store(), release, existing=None)

    def test_manifest_and_evaluator_byte_mismatch_fail_closed(self) -> None:
        for target in ("manifest", "evaluator"):
            with self.subTest(target=target):
                root = Path(self.directory.name) / target
                root.mkdir()
                release = _ReleaseFixture(root)
                if target == "manifest":
                    release.manifest.write_bytes(release.manifest.read_bytes() + b"bad\n")
                    provenance = json.loads(release.provenance.read_text(encoding="utf-8"))
                    provenance["source_manifest_sha256"] = hashlib.sha256(
                        release.manifest.read_bytes()
                    ).hexdigest()
                    release.provenance.write_text(
                        json.dumps(provenance, indent=2, sort_keys=True) + "\n",
                        encoding="utf-8",
                    )
                else:
                    (release.release / EVALUATOR_MODULE_RELATIVE).write_bytes(b"changed")
                with patch(
                    "research_pipeline.forward_volatility_persistence_activation."
                    "resolve_active_forward_trigger_lineage",
                    return_value=_lineage(),
                ):
                    with self.assertRaises(ActivationIntegrityError):
                        self._prepare(self._store(), release, existing=None)

    def test_group_writable_metadata_fails_closed(self) -> None:
        if os.name == "nt":
            self.skipTest("Windows does not preserve POSIX group-write mode semantics")
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        release.provenance.chmod(0o664)
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=_lineage(),
        ):
            with self.assertRaisesRegex(ActivationIntegrityError, "writable"):
                self._prepare(store, release, existing=None)

    def test_non_root_owned_release_fails_closed(self) -> None:
        store = self._store()
        release = _ReleaseFixture(Path(self.directory.name))
        with patch(
            "research_pipeline.forward_volatility_persistence_activation."
            "resolve_active_forward_trigger_lineage",
            return_value=_lineage(),
        ):
            with self.assertRaisesRegex(ActivationIntegrityError, "root-owned"):
                prepare_forward_volatility_persistence_activation(
                    store,
                    now=NOW,
                    previous_success=PRIOR_SUCCESS,
                    existing_receipt=None,
                    worker_root=release.worker_root,
                    control_current=release.control,
                    activation_module_path=release.release
                    / ACTIVATION_MODULE_RELATIVE,
                    expected_root_uid=_uid() + 1,
                    enforce_posix_permissions=False,
                )

    def test_heartbeat_persists_created_receipt_before_evaluator_failure(self) -> None:
        store = self._store()
        receipt = {"receipt": "synthetic"}
        state: dict[str, object] = {
            "schema_version": "1",
            "last_success": PRIOR_SUCCESS,
            "last_weekly": None,
            "last_monthly": None,
            "last_research_fingerprint": "research",
            "last_microstructure_fingerprint": "microstructure",
            "btc_utc_day_3pct_shock_contract_activated_at": "2026-08-01T00:00:00Z",
        }
        order: list[str] = []

        def persist(_store: ResearchStore, value: dict[str, object]) -> None:
            order.append("persist")
            self.assertIs(receipt, value[ACTIVATION_STATE_KEY])

        def fail_evaluator(*_args: object, **kwargs: object) -> list[dict[str, object]]:
            order.append("evaluate")
            self.assertIs(receipt, kwargs["activation_receipt"])
            raise RuntimeError("synthetic evaluator failure")

        with patch("research_pipeline.heartbeat._load_state", return_value=state), patch(
            "research_pipeline.heartbeat._verify_report_record"
        ), patch(
            "research_pipeline.heartbeat._adopt_existing_reports", return_value=[]
        ), patch(
            "research_pipeline.heartbeat._read_microstructure_diagnostic",
            return_value={"status": "DIAGNOSTIC_READY"},
        ), patch(
            "research_pipeline.heartbeat._research_fingerprint", return_value="research"
        ), patch(
            "research_pipeline.heartbeat._microstructure_fingerprint",
            return_value="microstructure",
        ), patch(
            "research_pipeline.heartbeat._new_closed_evidence_review_events",
            return_value=([], {}),
        ), patch(
            "research_pipeline.heartbeat.seal_r1_shock_diagnostics", return_value=[]
        ), patch(
            "research_pipeline.heartbeat.seal_r1_post_shock_factor_snapshots",
            return_value=[],
        ), patch(
            "research_pipeline.heartbeat.prepare_forward_volatility_persistence_activation",
            return_value=ActivationDecision(
                receipt, True, "ACTIVATION_RECEIPT_READY_TO_PERSIST"
            ),
        ), patch(
            "research_pipeline.heartbeat._write_state", side_effect=persist
        ), patch(
            "research_pipeline.heartbeat.seal_forward_volatility_persistence_snapshots",
            side_effect=fail_evaluator,
        ):
            with self.assertRaisesRegex(RuntimeError, "synthetic evaluator failure"):
                run_heartbeat_cycle(
                    store,
                    {"policy_id": "test"},
                    now=NOW,
                    tick_preview={"status": "IDLE"},
                    tick_result={"status": "WAITING_FOR_EVIDENCE"},
                )
        self.assertEqual(["persist", "evaluate"], order)

    def test_heartbeat_source_persists_receipt_before_evaluator(self) -> None:
        source = Path("research_pipeline/heartbeat.py").read_text(encoding="utf-8")
        prepare = source.index("prepare_forward_volatility_persistence_activation(")
        persist = source.index("_write_state(store, state)", prepare)
        evaluate = source.index("seal_forward_volatility_persistence_snapshots(", prepare)
        self.assertLess(prepare, persist)
        self.assertLess(persist, evaluate)
        self.assertIn("if volatility_activation.created:", source[prepare:evaluate])
        self.assertNotIn("run_heartbeat_cycle(", source[persist:evaluate])

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self.directory.cleanup()

    def _store(self) -> ResearchStore:
        return ResearchStore(Path(self.directory.name) / "state", lock_stale_seconds=3600)

    def _prepare(
        self,
        store: ResearchStore,
        release: "_ReleaseFixture",
        *,
        existing: object,
    ) -> ActivationDecision:
        return prepare_forward_volatility_persistence_activation(
            store,
            now=NOW,
            previous_success=PRIOR_SUCCESS,
            existing_receipt=existing,
            worker_root=release.worker_root,
            control_current=release.control,
            activation_module_path=release.release / ACTIVATION_MODULE_RELATIVE,
            expected_root_uid=_uid(),
        )


class _ReleaseFixture:
    def __init__(self, root: Path, *, release_id: str = "20260818T010000Z") -> None:
        self.worker_root = root / "worker"
        self.release_id = release_id
        self.source_commit = "5" * 40
        self.release = self.worker_root / "releases" / release_id
        metadata = self.release / ".release"
        package = self.release / "research_pipeline"
        metadata.mkdir(parents=True)
        package.mkdir(parents=True)

        source_root = Path(__file__).resolve().parents[2]
        relative_paths = (
            EVALUATOR_SCHEMA_RELATIVE,
            EVALUATOR_MODULE_RELATIVE,
            ACCEPTED_RESULT_RELATIVE,
            ACTIVATION_MODULE_RELATIVE,
            HEARTBEAT_MODULE_RELATIVE,
        )
        entries: list[str] = []
        for relative in relative_paths:
            content = (source_root / relative).read_bytes()
            target = self.release / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            target.chmod(0o644)
            entries.append(f"{hashlib.sha256(content).hexdigest()}  {relative.as_posix()}")
        self.manifest = metadata / "source.sha256"
        self.manifest.write_text("\n".join(sorted(entries)) + "\n", encoding="utf-8")
        self.manifest.chmod(0o644)
        self.manifest_hash = hashlib.sha256(self.manifest.read_bytes()).hexdigest()
        self.provenance = metadata / "provenance.json"
        self.provenance.write_text(
            json.dumps(
                {
                    "schema_version": "1",
                    "release_id": release_id,
                    "source_git_commit": self.source_commit,
                    "source_git_branch": "codex/autonomous-research-control-plane-v3",
                    "source_git_dirty": False,
                    "source_manifest_sha256": self.manifest_hash,
                    "installed_at": "2026-08-18T00:30:00Z",
                },
                indent=2,
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        self.provenance.chmod(0o644)
        for directory in (
            self.worker_root,
            self.worker_root / "releases",
            self.release,
            metadata,
            package,
        ):
            directory.chmod(0o755)
        self.control = self.worker_root / "control-current"
        os.symlink(self.release, self.control, target_is_directory=True)


def _lineage(
    *, rolled_over: bool = True, rollover_depth: int = 1
) -> ActiveForwardTriggerLineage:
    root = {
        "trigger_id": ROOT_TRIGGER_ID,
        "fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "created_at": "2026-08-01T00:00:00Z",
    }
    root_state: dict[str, object] = {
        "trigger_id": ROOT_TRIGGER_ID,
        "status": "WAITING" if not rolled_over else "CLOSED",
    }
    if not rolled_over:
        return ActiveForwardTriggerLineage(
            root_trigger=root,
            root_state=root_state,
            leaf_trigger=root,
            leaf_state=root_state,
            trigger_ids=(ROOT_TRIGGER_ID,),
            trigger_identities=(
                (
                    ROOT_TRIGGER_ID,
                    ROOT_TRIGGER_FINGERPRINT,
                    root["created_at"],
                ),
            ),
        )
    if rollover_depth < 1:
        raise ValueError("rollover_depth must be positive")
    descendants = []
    for index in range(1, rollover_depth + 1):
        created_at = ROLLOVER_AT + timedelta(days=index - 1)
        descendants.append(
            {
                "trigger_id": (
                    "prospective-mechanism-neutral-evidence-refresh-rollover-r2"
                    if index == 1
                    else "prospective-mechanism-neutral-evidence-refresh-"
                    f"rollover-r{index + 1}"
                ),
                "fingerprint": format(10 + index, "x") * 64,
                "created_at": created_at.isoformat(timespec="seconds").replace(
                    "+00:00", "Z"
                ),
            }
        )
    leaf = descendants[-1]
    root_state["rollover_closed_at"] = ROLLOVER_AT.isoformat(
        timespec="seconds"
    ).replace("+00:00", "Z")
    leaf_state = {"trigger_id": leaf["trigger_id"], "status": "WAITING"}
    trigger_ids = [ROOT_TRIGGER_ID, *(item["trigger_id"] for item in descendants)]
    trigger_identities = [
        (ROOT_TRIGGER_ID, ROOT_TRIGGER_FINGERPRINT, root["created_at"]),
        *(
            (item["trigger_id"], item["fingerprint"], item["created_at"])
            for item in descendants
        ),
    ]
    return ActiveForwardTriggerLineage(
        root_trigger=root,
        root_state=root_state,
        leaf_trigger=leaf,
        leaf_state=leaf_state,
        trigger_ids=tuple(trigger_ids),
        trigger_identities=tuple(trigger_identities),
    )


def _uid() -> int:
    return os.getuid() if hasattr(os, "getuid") else 0


if __name__ == "__main__":
    unittest.main()
