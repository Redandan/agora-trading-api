from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from research_pipeline import forward_trigger_lineage as lineage_module
from research_pipeline.evidence import MISSED_DISCOVERY_ROLLOVER_REASON
from research_pipeline.forward_trigger_lineage import ActiveForwardTriggerLineage
from research_pipeline.forward_volatility_persistence import (
    ACCEPTED_TASK_ID,
    ACCEPTED_TASK_SHA256,
    ACTIVATION_DOCUMENT_TYPE,
    CLOSE,
    DOCUMENT_TYPE,
    FAILED_RETAIN_GATES,
    FORMULA_VERSION,
    HARD_CAP_INCOMPLETE_BREADTH,
    RETAIN,
    ROOT_TRIGGER_FINGERPRINT,
    ROOT_TRIGGER_ID,
    SCHEMA_PATH,
    SNAPSHOT_NAMESPACE,
    WAIT,
    _canonical_bytes,
    _coach_event,
    _create_only,
    _gates_and_statistics,
    _sha256_bytes,
    _validate_activation_receipt,
    _validate_snapshot,
    build_forward_volatility_episode,
    build_forward_volatility_snapshot,
    seal_forward_volatility_persistence_snapshots,
)
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.shock_attribution import build_shock_diagnostic
from research_pipeline.storage import ResearchStore, sha256_file


NOW = datetime(2026, 6, 1, 12, tzinfo=timezone.utc)
ROOT_SOURCE = (
    "server-local read-only OKX BTCUSDT complete 1h bars aggregated into "
    "complete UTC days"
)


class ForwardVolatilityPersistenceTest(unittest.TestCase):
    def test_schema_is_closed_and_binds_terminal_alternatives(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        self.assertEqual("https://json-schema.org/draft/2020-12/schema", schema["$schema"])
        self.assertFalse(schema["additionalProperties"])
        self.assertFalse(schema["$defs"]["episode"]["additionalProperties"])
        self.assertFalse(schema["$defs"]["dayReference"]["additionalProperties"])
        self.assertFalse(schema["$defs"]["gateEvidence"]["additionalProperties"])
        self.assertFalse(schema["$defs"]["statistics"]["additionalProperties"])
        self.assertEqual(3, len(schema["allOf"]))

    def test_absent_activation_is_strict_noop_before_lineage_or_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = ResearchStore(Path(directory), lock_stale_seconds=3600)
            with patch(
                "research_pipeline.forward_volatility_persistence.resolve_active_forward_trigger_lineage",
                side_effect=AssertionError("lineage must not be inspected"),
            ):
                self.assertEqual(
                    [],
                    seal_forward_volatility_persistence_snapshots(
                        store, now=NOW, activation_receipt=None
                    ),
                )
            self.assertFalse((store.root / SNAPSHOT_NAMESPACE).exists())

    def test_activation_receipt_is_exact_and_never_created_or_persisted(self) -> None:
        lineage = _lineage()
        receipt = _activation(lineage)
        validated = _validate_activation_receipt(receipt, current=NOW)
        self.assertEqual(receipt, validated)
        for mutation in (
            lambda value: value.__setitem__("unexpected", True),
            lambda value: value.__setitem__("authorization", "PAPER"),
            lambda value: value.__setitem__("implementation_commit", "a" * 39),
            lambda value: value.__setitem__("accepted_task_sha256", "b" * 64),
            lambda value: value.__setitem__("activated_at", "2027-01-01T00:00:00Z"),
        ):
            candidate = copy.deepcopy(receipt)
            mutation(candidate)
            with self.assertRaises(ValueError):
                _validate_activation_receipt(candidate, current=NOW)

    def test_valid_receipt_for_stale_leaf_is_dormant_without_write(self) -> None:
        lineage = _lineage()
        receipt = _activation(lineage)
        receipt["leaf_trigger_id"] = "different-active-leaf"
        receipt["leaf_trigger_fingerprint"] = "d" * 64
        with tempfile.TemporaryDirectory() as directory:
            store = ResearchStore(Path(directory), lock_stale_seconds=3600)
            with patch(
                "research_pipeline.forward_volatility_persistence.resolve_active_forward_trigger_lineage",
                return_value=lineage,
            ):
                self.assertEqual(
                    [],
                    seal_forward_volatility_persistence_snapshots(
                        store, now=NOW, activation_receipt=receipt
                    ),
                )
            self.assertFalse((store.root / SNAPSHOT_NAMESPACE).exists())

    def test_resolved_fresh_leaf_normalizes_only_missing_observation_inventory(
        self,
    ) -> None:
        leaf_id = "prospective-mechanism-neutral-evidence-refresh-rollover-r2"
        leaf_fingerprint = "b" * 64
        created_at = "2026-05-02T00:00:00Z"
        root = {
            "trigger_id": ROOT_TRIGGER_ID,
            "fingerprint": ROOT_TRIGGER_FINGERPRINT,
            "created_at": "2026-05-01T00:00:00Z",
        }
        root_state = {
            "trigger_id": ROOT_TRIGGER_ID,
            "status": "CLOSED",
            "rollover_reason": MISSED_DISCOVERY_ROLLOVER_REASON,
            "rollover_successor_trigger_id": leaf_id,
            "rollover_successor_fingerprint": leaf_fingerprint,
            "rollover_closed_at": created_at,
        }
        leaf = {
            "trigger_id": leaf_id,
            "fingerprint": leaf_fingerprint,
            "created_at": created_at,
        }
        leaf_state = {
            "trigger_id": leaf_id,
            "status": "WAITING",
            "rollover_predecessor_trigger_id": ROOT_TRIGGER_ID,
            "rollover_predecessor_fingerprint": ROOT_TRIGGER_FINGERPRINT,
            "rollover_reason": MISSED_DISCOVERY_ROLLOVER_REASON,
        }
        with tempfile.TemporaryDirectory() as directory:
            store = ResearchStore(Path(directory), lock_stale_seconds=3600)
            with patch.object(
                lineage_module,
                "_safe_trigger_entries",
                return_value=[(root, root_state), (leaf, leaf_state)],
            ), patch.object(
                lineage_module, "_verify_registered_trigger"
            ), patch.object(lineage_module, "_verify_discovery_contract"):
                lineage = lineage_module.resolve_active_forward_trigger_lineage(store)

        self.assertIsNotNone(lineage)
        self.assertEqual([], lineage.leaf_state["evidence_observations"])
        self.assertNotIn("evidence_observations", leaf_state)

        corrupt = dict(leaf_state)
        corrupt["evidence_observations"] = {}
        self.assertIs(
            corrupt,
            lineage_module._normalized_leaf_state_for_readers(corrupt),
        )

    def test_exact_formula_and_immutable_episode_identity(self) -> None:
        lineage = _lineage()
        references, bundles, diagnostic = _episode_sources(lineage)
        episode = _build_episode(lineage, references, bundles, diagnostic)
        self.assertEqual(FORMULA_VERSION, episode["formula_version"])
        self.assertEqual("UP", episode["shock_direction"])
        self.assertGreaterEqual(Decimal(episode["absolute_simple_return"]), Decimal("0.0300"))
        self.assertGreater(Decimal(episode["baseline_rv20"]), 0)
        self.assertGreater(Decimal(episode["outcome_rv24"]), 0)
        self.assertGreater(Decimal(episode["outcome_ratio"]), 0)
        changed = copy.deepcopy(episode)
        changed["outcome_ratio"] = "9"
        with self.assertRaises(ValueError):
            from research_pipeline.forward_volatility_persistence import _validate_episode

            _validate_episode(changed)

    def test_episode_requires_exact_contiguous_23_day_same_leaf_closure(self) -> None:
        lineage = _lineage()
        references, bundles, diagnostic = _episode_sources(lineage)
        with self.assertRaisesRegex(ValueError, "exactly 23"):
            _build_episode(lineage, references[:-1], bundles[:-1], diagnostic)
        broken = copy.deepcopy(references)
        broken[10]["day"] = "2026-05-20"
        with self.assertRaisesRegex(ValueError, "not contiguous"):
            _build_episode(lineage, broken, bundles, diagnostic)
        wrong = copy.deepcopy(bundles)
        wrong[4]["trigger_fingerprint"] = "e" * 64
        with self.assertRaisesRegex(ValueError, "trigger_fingerprint"):
            _build_episode(lineage, references, wrong, diagnostic)

    def test_root_and_rollover_episode_bind_distinct_leaf_identity(self) -> None:
        root = _lineage()
        root_refs, root_bundles, root_diagnostic = _episode_sources(root)
        root_episode = _build_episode(root, root_refs, root_bundles, root_diagnostic)
        rollover = _lineage(rolled_over=True)
        roll_refs, roll_bundles, roll_diagnostic = _episode_sources(rollover)
        roll_episode = _build_episode(rollover, roll_refs, roll_bundles, roll_diagnostic)
        self.assertEqual(ROOT_TRIGGER_ID, root_episode["leaf_trigger_id"])
        self.assertNotEqual(ROOT_TRIGGER_ID, roll_episode["leaf_trigger_id"])
        self.assertNotEqual(root_episode["episode_id"], roll_episode["episode_id"])

    def test_wait_retain_and_failed_retain_close_table(self) -> None:
        lineage = _lineage()
        base = _base_episode(lineage)
        wait = build_forward_volatility_snapshot(
            [_gate_episode(base, 0, "1.2", "UP", date(2026, 1, 15))],
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        self.assertEqual(WAIT, wait["disposition"])
        self.assertFalse(wait["terminal"])

        retained_episodes = _breadth_episodes(base, ratio="1.2")
        retained = build_forward_volatility_snapshot(
            retained_episodes,
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        self.assertEqual(RETAIN, retained["disposition"])
        self.assertTrue(retained["statistics"]["retain_conditions_met"])

        closed = build_forward_volatility_snapshot(
            _breadth_episodes(base, ratio="0.9"),
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        self.assertEqual(CLOSE, closed["disposition"])
        self.assertEqual(FAILED_RETAIN_GATES, closed["close_reason"])

    def test_incomplete_breadth_closes_exactly_at_hard_cap(self) -> None:
        lineage = _lineage()
        base = _base_episode(lineage)
        episodes = [
            _gate_episode(
                base,
                index,
                "1.1" if index % 2 else "0.9",
                "UP" if index % 2 else "DOWN",
                date(2026, 1, index + 1),
            )
            for index in range(24)
        ]
        snapshot = build_forward_volatility_snapshot(
            episodes,
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        self.assertEqual(CLOSE, snapshot["disposition"])
        self.assertEqual(HARD_CAP_INCOMPLETE_BREADTH, snapshot["close_reason"])
        self.assertFalse(snapshot["gate_evidence"]["all_breadth_pass"])

    def test_month_and_episode_concentration_are_fail_closed(self) -> None:
        lineage = _lineage()
        base = _base_episode(lineage)
        concentrated = [
            _gate_episode(base, index, "1.2", "UP" if index < 6 else "DOWN", date(2026, 1, index + 1))
            for index in range(12)
        ]
        gates, _ = _gates_and_statistics(concentrated)
        self.assertFalse(gates["target_month_concentration_pass"])
        episodes = _breadth_episodes(base, ratio="1.01")
        episodes[0] = _gate_episode(base, 0, "10", "UP", date(2026, 1, 15))
        gates, _ = _gates_and_statistics(episodes)
        self.assertFalse(gates["episode_concentration_pass"])

    def test_snapshot_validator_rejects_extra_and_contradictory_fields(self) -> None:
        lineage = _lineage()
        snapshot = build_forward_volatility_snapshot(
            _breadth_episodes(_base_episode(lineage), ratio="1.2"),
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        extra = copy.deepcopy(snapshot)
        extra["unexpected"] = True
        with self.assertRaises(ValueError):
            _validate_snapshot(extra, lineage=lineage)
        nested = copy.deepcopy(snapshot)
        nested["episodes"][0]["unexpected"] = True
        with self.assertRaises(ValueError):
            _validate_snapshot(nested, lineage=lineage)
        contradictory = copy.deepcopy(snapshot)
        contradictory["disposition"] = WAIT
        contradictory["terminal"] = False
        with self.assertRaises(ValueError):
            _validate_snapshot(contradictory, lineage=lineage)

    def test_create_only_is_idempotent_and_preserves_conflict_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            self.assertTrue(_create_only(path, b"one\n"))
            self.assertFalse(_create_only(path, b"one\n"))
            self.assertFalse(_create_only(path, b"two\n"))
            self.assertEqual(b"one\n", path.read_bytes())

    def test_terminal_event_is_research_only_and_economics_missing(self) -> None:
        lineage = _lineage()
        snapshot = build_forward_volatility_snapshot(
            _breadth_episodes(_base_episode(lineage), ratio="1.2"),
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-06-01T12:00:00Z",
        )
        event = _coach_event(snapshot, artifact_path="artifact.json", artifact_sha256="f" * 64)
        self.assertEqual("MATERIAL_LEARNING", event["event_type"])
        self.assertEqual("ZERO", event["pnl_drawdown_evidence"]["immediate_effect"])
        self.assertEqual("MISSING_PROOF", event["pnl_drawdown_evidence"]["economic_value"])
        self.assertNotIn("side", json.dumps(event).lower())

    def test_synthetic_entrypoint_seals_wait_once_and_reuses_bytes(self) -> None:
        lineage = _lineage()
        references, bundles, diagnostic = _episode_sources(lineage)
        with tempfile.TemporaryDirectory() as directory:
            store = ResearchStore(Path(directory), lock_stale_seconds=3600)
            stored_references = _store_sources(store, references, bundles)
            lineage.leaf_state["evidence_observations"] = stored_references
            lineage.leaf_state["evidence_observation_count"] = len(stored_references)
            diagnostic["prior_day"] = _stored_public_reference(stored_references[20])
            diagnostic["target_day"] = _stored_public_reference(stored_references[21])
            diagnostic_path = store.root / "shock-diagnostics" / "btc-utc-day-3pct-v1" / f"{diagnostic['target_day']['day']}.json"
            diagnostic_path.parent.mkdir(parents=True)
            diagnostic_path.write_bytes(_canonical_bytes(diagnostic))
            receipt = _activation(lineage)
            with patch(
                "research_pipeline.forward_volatility_persistence.resolve_active_forward_trigger_lineage",
                return_value=lineage,
            ), patch(
                "research_pipeline.forward_volatility_persistence.evidence_progress",
                return_value={"status": "WAITING"},
            ):
                self.assertEqual([], seal_forward_volatility_persistence_snapshots(store, now=NOW, activation_receipt=receipt))
                snapshots = list((store.root / SNAPSHOT_NAMESPACE / ROOT_TRIGGER_FINGERPRINT / "snapshots").glob("*.json"))
                self.assertEqual(1, len(snapshots))
                original = snapshots[0].read_bytes()
                self.assertEqual([], seal_forward_volatility_persistence_snapshots(store, now=NOW, activation_receipt=receipt))
                self.assertEqual(original, snapshots[0].read_bytes())

    def test_heartbeat_child_is_after_directional_child_and_before_schedule(self) -> None:
        source = Path("research_pipeline/heartbeat.py").read_text(encoding="utf-8")
        directional = source.index("seal_r1_post_shock_factor_snapshots(")
        persistence = source.index("seal_forward_volatility_persistence_snapshots(")
        schedule = source.index("due = _schedule(now, state, tick_result)")
        self.assertLess(directional, persistence)
        self.assertLess(persistence, schedule)
        self.assertIn(
            'state.get(\n                "btc_utc_day_3pct_forward_volatility_persistence_activation"',
            source,
        )


def _lineage(*, rolled_over: bool = False) -> ActiveForwardTriggerLineage:
    root = {
        "trigger_id": ROOT_TRIGGER_ID,
        "fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "source": ROOT_SOURCE,
    }
    leaf = root if not rolled_over else {
        "trigger_id": "prospective-mechanism-neutral-evidence-refresh-rollover-r2",
        "fingerprint": "b" * 64,
        "source": ROOT_SOURCE,
    }
    root_state: dict[str, object] = {"trigger_id": ROOT_TRIGGER_ID}
    leaf_state: dict[str, object] = {"trigger_id": leaf["trigger_id"], "evidence_observations": []}
    return ActiveForwardTriggerLineage(
        root_trigger=root,
        root_state=root_state,
        leaf_trigger=leaf,
        leaf_state=leaf_state,
        trigger_ids=(ROOT_TRIGGER_ID,) if not rolled_over else (ROOT_TRIGGER_ID, leaf["trigger_id"]),
    )


def _activation(lineage: ActiveForwardTriggerLineage) -> dict[str, object]:
    return {
        "schema_version": "1",
        "document_type": ACTIVATION_DOCUMENT_TYPE,
        "activated_at": "2026-05-01T00:00:00Z",
        "implementation_commit": "1" * 40,
        "accepted_task_id": ACCEPTED_TASK_ID,
        "accepted_task_sha256": ACCEPTED_TASK_SHA256,
        "accepted_result_sha256": "2" * 64,
        "evaluator_schema_sha256": sha256_file(SCHEMA_PATH),
        "evaluator_module_sha256": sha256_file(Path("research_pipeline/forward_volatility_persistence.py")),
        "worker_release_id": "synthetic-worker-release",
        "worker_source_commit": "3" * 40,
        "worker_manifest_sha256": "4" * 64,
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "leaf_trigger_id": lineage.leaf_trigger["trigger_id"],
        "leaf_trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _episode_sources(
    lineage: ActiveForwardTriggerLineage,
) -> tuple[list[dict[str, str]], list[dict[str, object]], dict[str, object]]:
    start = date(2026, 5, 1)
    references: list[dict[str, str]] = []
    bundles: list[dict[str, object]] = []
    last_prices: list[Decimal] = []
    for index in range(23):
        day = start + timedelta(days=index)
        if index == 21:
            last_price = last_prices[20] * Decimal("1.03")
        elif index == 22:
            last_price = last_prices[21] * Decimal("1.02")
        else:
            last_price = Decimal("100") + Decimal(index)
        last_prices.append(last_price)
        bars = _bars_for_day(day, last_price)
        received = datetime.combine(day + timedelta(days=1), time(hour=1), timezone.utc)
        reference = {
            "day": day.isoformat(),
            "artifact_path": f"synthetic/{day.isoformat()}.json",
            "artifact_sha256": hashlib.sha256(f"artifact-{index}".encode()).hexdigest(),
            "chain_head": hashlib.sha256(f"chain-{index}".encode()).hexdigest(),
            "received_at": received.isoformat().replace("+00:00", "Z"),
        }
        bundle = {
            "schema_version": "1",
            "bundle_type": "FORWARD_EVIDENCE_DAY",
            "trigger_id": lineage.leaf_trigger["trigger_id"],
            "trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
            "source": lineage.leaf_trigger["source"],
            "day": day.isoformat(),
            "bars": bars,
            "source_provenance": {"producer": "synthetic", "artifact_id": day.isoformat(), "sha256": "5" * 64},
            "received_at": reference["received_at"],
            "authorization": RESEARCH_AUTHORIZATION,
        }
        references.append(reference)
        bundles.append(bundle)
    projected_prior = copy.deepcopy(bundles[20])
    projected_target = copy.deepcopy(bundles[21])
    for bundle in (projected_prior, projected_target):
        bundle["trigger_id"] = ROOT_TRIGGER_ID
        bundle["trigger_fingerprint"] = ROOT_TRIGGER_FINGERPRINT
    diagnostic = build_shock_diagnostic(
        prior_ref=references[20],
        prior_bundle=projected_prior,
        target_ref=references[21],
        target_bundle=projected_target,
        contract_activated_at="2026-05-01T00:00:00Z",
        sealed_at="2026-05-23T01:30:00Z",
        eligibility="FORWARD_FACTOR_ELIGIBLE",
    )
    assert diagnostic is not None
    if lineage.rolled_over:
        diagnostic["diagnostic_type"] = "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V2"
        diagnostic["trigger_id"] = lineage.leaf_trigger["trigger_id"]
        diagnostic["trigger_fingerprint"] = lineage.leaf_trigger["fingerprint"]
        diagnostic["root_trigger_id"] = ROOT_TRIGGER_ID
        diagnostic["root_trigger_fingerprint"] = ROOT_TRIGGER_FINGERPRINT
        diagnostic["leaf_trigger_id"] = lineage.leaf_trigger["trigger_id"]
        diagnostic["leaf_trigger_fingerprint"] = lineage.leaf_trigger["fingerprint"]
    return references, bundles, diagnostic


def _bars_for_day(day: date, last_price: Decimal) -> list[dict[str, str]]:
    first = last_price * Decimal("0.99")
    step = (last_price - first) / Decimal("23")
    result: list[dict[str, str]] = []
    for hour in range(24):
        close = first + step * Decimal(hour)
        start = datetime.combine(day, time.min, timezone.utc) + timedelta(hours=hour)
        result.append(
            {
                "interval_start": start.isoformat().replace("+00:00", "Z"),
                "interval_end": (start + timedelta(hours=1)).isoformat().replace("+00:00", "Z"),
                "open": format(close, "f"),
                "high": format(close * Decimal("1.001"), "f"),
                "low": format(close * Decimal("0.999"), "f"),
                "close": format(close, "f"),
                "volume": "1",
            }
        )
    return result


def _build_episode(
    lineage: ActiveForwardTriggerLineage,
    references: list[dict[str, str]],
    bundles: list[dict[str, object]],
    diagnostic: dict[str, object],
) -> dict[str, object]:
    return build_forward_volatility_episode(
        diagnostic=diagnostic,
        diagnostic_path=f"diagnostic/{diagnostic['target_day']['day']}.json",
        diagnostic_sha256="6" * 64,
        source_references=references,
        source_bundles=bundles,
        lineage=lineage,
        activation_receipt_sha256="a" * 64,
        evaluator_schema_sha256="b" * 64,
        evaluator_module_sha256="c" * 64,
        sealed_at="2026-06-01T12:00:00Z",
    )


def _base_episode(lineage: ActiveForwardTriggerLineage) -> dict[str, object]:
    references, bundles, diagnostic = _episode_sources(lineage)
    return _build_episode(lineage, references, bundles, diagnostic)


def _gate_episode(
    base: dict[str, object],
    index: int,
    ratio: str,
    direction: str,
    target_day: date,
) -> dict[str, object]:
    episode = copy.deepcopy(base)
    source_start = target_day - timedelta(days=21)
    source_days = episode["source_days"]
    assert isinstance(source_days, list)
    for offset, reference in enumerate(source_days):
        day = source_start + timedelta(days=offset)
        reference["day"] = day.isoformat()
        reference["artifact_path"] = f"synthetic-gates/{index}/{day.isoformat()}.json"
        reference["artifact_sha256"] = hashlib.sha256(f"artifact-{index}-{offset}".encode()).hexdigest()
        reference["chain_head"] = hashlib.sha256(f"chain-{index}-{offset}".encode()).hexdigest()
        reference["received_at"] = datetime.combine(day + timedelta(days=1), time(hour=1), timezone.utc).isoformat().replace("+00:00", "Z")
    episode["target_day"] = source_days[21]["day"]
    episode["target_day_reference"] = copy.deepcopy(source_days[21])
    episode["outcome_day_reference"] = copy.deepcopy(source_days[22])
    episode["shock_direction"] = direction
    episode["outcome_ratio"] = ratio
    episode["label"] = "PERSISTENT_VOLATILITY" if Decimal(ratio) > 1 else "NON_PERSISTENT_VOLATILITY"
    body = {key: value for key, value in episode.items() if key not in {"episode_id", "sealed_at"}}
    episode["episode_id"] = _sha256_bytes(_canonical_bytes(body))
    return episode


def _breadth_episodes(base: dict[str, object], *, ratio: str) -> list[dict[str, object]]:
    targets = [
        date(2026, 1, 15), date(2026, 1, 25), date(2026, 1, 28),
        date(2026, 2, 15), date(2026, 2, 20), date(2026, 2, 25),
        date(2026, 3, 15), date(2026, 3, 20), date(2026, 3, 25),
        date(2026, 4, 15), date(2026, 4, 20), date(2026, 4, 25),
    ]
    return [
        _gate_episode(base, index, ratio, "UP" if index % 2 == 0 else "DOWN", target)
        for index, target in enumerate(targets)
    ]


def _store_sources(
    store: ResearchStore,
    references: list[dict[str, str]],
    bundles: list[dict[str, object]],
) -> list[dict[str, str]]:
    stored: list[dict[str, str]] = []
    for reference, bundle in zip(references, bundles, strict=True):
        path = store.root / "synthetic" / f"{reference['day']}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_canonical_bytes(bundle))
        stored.append(
            {
                "day": reference["day"],
                "path": str(path.relative_to(store.root)).replace("\\", "/"),
                "sha256": sha256_file(path),
                "chain_head": reference["chain_head"],
                "received_at": reference["received_at"],
            }
        )
    return stored


def _stored_public_reference(reference: dict[str, str]) -> dict[str, str]:
    return {
        "day": reference["day"],
        "artifact_path": reference["path"],
        "artifact_sha256": reference["sha256"],
        "chain_head": reference["chain_head"],
        "received_at": reference["received_at"],
    }


if __name__ == "__main__":
    unittest.main()
