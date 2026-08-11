from __future__ import annotations

import copy
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from jsonschema import Draft202012Validator

from research_pipeline.evidence import (
    register_evidence_source_contract,
    rollover_missed_discovery_window,
    seal_daily_evidence,
)
from research_pipeline.heartbeat import run_heartbeat_cycle
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.shock_attribution import (
    DIAGNOSTIC_NAMESPACE,
    R1_SOURCE,
    R1_TRIGGER_FINGERPRINT,
    R1_TRIGGER_ID,
    SCHEMA_PATH,
    V2_DIAGNOSTIC_NAMESPACE,
    V2_SCHEMA_PATH,
    build_shock_diagnostic,
    seal_r1_shock_diagnostics,
)
from research_pipeline.storage import ResearchStore, read_json
from research_pipeline.waiting import build_evidence_trigger


UTC = timezone.utc


class ShockAttributionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(cls.schema)
        cls.v2_schema = json.loads(V2_SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.v2_schema)
        cls.v2_validator = Draft202012Validator(cls.v2_schema)

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = ResearchStore(self.root, lock_stale_seconds=60)
        self.store.bootstrap()
        self.trigger = build_evidence_trigger(self._trigger_value())
        self.assertEqual(self.trigger["fingerprint"], R1_TRIGGER_FINGERPRINT)
        self.store.register_evidence_trigger(self.trigger)
        state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        register_evidence_source_contract(
            self.store,
            self.trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": R1_TRIGGER_ID,
                "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
                "source": R1_SOURCE,
                "producer": "shock-fixture-producer",
                "transport": "SEALED_ONE_WAY_DROP_V1",
                "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
                "worker_network_access": "DENY",
                "worker_database_access": "DENY",
                "backfill": "DENY",
                "authorization": RESEARCH_AUTHORIZATION,
            },
            registered_at=datetime(2026, 8, 5, 12, tzinfo=UTC),
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_below_threshold_creates_no_artifact(self) -> None:
        self._seal_pair("100", "102.99")
        events = self._seal_shocks(activation="2026-08-07T00:00:00Z")
        self.assertEqual(events, [])
        self.assertEqual(self._artifacts(), [])

    def test_exact_positive_three_percent_is_included_and_schema_valid(self) -> None:
        self._seal_pair("100", "103")
        events = self._seal_shocks(activation="2026-08-07T00:00:00Z")
        self.assertEqual(len(events), 1)
        diagnostic = read_json(self._artifacts()[0])
        self.validator.validate(diagnostic)
        self.assertEqual(diagnostic["path"]["simple_return"], "0.03")
        self.assertEqual(diagnostic["path"]["direction"], "UP")
        closed_violation = {**diagnostic, "unexpected": True}
        self.assertTrue(list(self.validator.iter_errors(closed_violation)))

    def test_exact_negative_three_percent_is_included(self) -> None:
        self._seal_pair("100", "97")
        events = self._seal_shocks(activation="2026-08-07T00:00:00Z")
        self.assertEqual(len(events), 1)
        diagnostic = read_json(self._artifacts()[0])
        self.assertEqual(diagnostic["path"]["simple_return"], "-0.03")
        self.assertEqual(diagnostic["path"]["absolute_simple_return"], "0.03")
        self.assertEqual(diagnostic["path"]["direction"], "DOWN")

    def test_builder_rejects_nonadjacent_days(self) -> None:
        prior_ref, prior, target_ref, target = self._direct_pair("100", "103")
        target_ref["day"] = "2026-08-08"
        target["day"] = "2026-08-08"
        with self.assertRaisesRegex(ValueError, "adjacent UTC days"):
            build_shock_diagnostic(
                prior_ref=prior_ref,
                prior_bundle=prior,
                target_ref=target_ref,
                target_bundle=target,
                contract_activated_at="2026-08-07T00:00:00Z",
                sealed_at="2026-08-08T02:00:00Z",
                eligibility="FORWARD_FACTOR_ELIGIBLE",
            )

    def test_r1_identity_drift_is_rejected(self) -> None:
        self._seal_pair("100", "103")
        trigger_path = self.store.evidence_trigger_dir(R1_TRIGGER_ID) / "trigger.json"
        trigger = read_json(trigger_path)
        trigger["source"] = "wrong-source"
        trigger_path.write_text(json.dumps(trigger), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            self._seal_shocks(activation="2026-08-07T00:00:00Z")

    def test_changed_artifact_hash_is_rejected(self) -> None:
        self._seal_pair("100", "103")
        state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        state["evidence_observations"][1]["sha256"] = "f" * 64
        self.store.save_evidence_trigger_state(state)
        with self.assertRaisesRegex(ValueError, "changed or disappeared"):
            self._seal_shocks(activation="2026-08-07T00:00:00Z")

    def test_broken_chain_is_rejected(self) -> None:
        self._seal_pair("100", "103")
        state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        state["evidence_observations"][1]["chain_head"] = "e" * 64
        state["evidence_chain_head"] = "e" * 64
        self.store.save_evidence_trigger_state(state)
        with self.assertRaisesRegex(ValueError, "chain mismatch"):
            self._seal_shocks(activation="2026-08-07T00:00:00Z")

    def test_context_only_artifact_creates_no_coach_event(self) -> None:
        self._seal_pair("100", "103")
        events = self._seal_shocks(activation="2026-08-08T01:30:00Z")
        self.assertEqual(events, [])
        diagnostic = read_json(self._artifacts()[0])
        self.assertEqual(diagnostic["eligibility"], "CONTEXT_ONLY")

    def test_forward_artifact_creates_one_generic_event(self) -> None:
        self._seal_pair("100", "103")
        events = self._seal_shocks(activation="2026-08-07T00:00:00Z")
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["event_type"], "MATERIAL_LEARNING")
        self.assertEqual(events[0]["evidence_diagnostic"]["causal_explanation"], "UNKNOWN")
        self.assertEqual(events[0]["pnl_drawdown_evidence"]["immediate_effect"], "ZERO")

    def test_repeated_heartbeat_is_idempotent_and_reuses_outbox(self) -> None:
        self._seal_day("2026-08-06", "100", received_at="2026-08-07T01:00:00Z")
        self._heartbeat(datetime(2026, 8, 7, 2, tzinfo=UTC))
        self._seal_day("2026-08-07", "103", received_at="2026-08-08T01:00:00Z")
        first = self._heartbeat(datetime(2026, 8, 8, 2, tzinfo=UTC))
        second = self._heartbeat(datetime(2026, 8, 8, 3, tzinfo=UTC))
        first_shocks = [
            event
            for event in first["events"]
            if event.get("research_status") == "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_READY"
        ]
        second_shocks = [
            event
            for event in second["events"]
            if event.get("research_status") == "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_READY"
        ]
        self.assertEqual(len(first_shocks), 1)
        self.assertEqual(second_shocks, [])
        state = read_json(self.root / "heartbeat" / "state.json")
        pending = state["coach_delivery"]["pending_events"]
        self.assertEqual(
            len(
                [
                    event
                    for event in pending
                    if event.get("research_status")
                    == "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_READY"
                ]
            ),
            1,
        )

    def test_conflicting_sealed_artifact_fails_closed(self) -> None:
        self._seal_pair("100", "103")
        self._seal_shocks(activation="2026-08-07T00:00:00Z")
        artifact = self._artifacts()[0]
        diagnostic = read_json(artifact)
        diagnostic["guardrails"]["causal_explanation"] = "UNSUPPORTED_STORY"
        artifact.write_text(json.dumps(diagnostic, sort_keys=True) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "changed or conflicts"):
            self._seal_shocks(activation="2026-08-07T00:00:00Z")

    def test_deterministic_earliest_ties_and_null_rules(self) -> None:
        prior_ref, prior, target_ref, target = self._direct_pair(
            "100", "103", target_open="103", volume="0"
        )
        diagnostic = build_shock_diagnostic(
            prior_ref=prior_ref,
            prior_bundle=prior,
            target_ref=target_ref,
            target_bundle=target,
            contract_activated_at="2026-08-07T00:00:00Z",
            sealed_at="2026-08-08T02:00:00Z",
            eligibility="FORWARD_FACTOR_ELIGIBLE",
        )
        self.assertIsNotNone(diagnostic)
        path = diagnostic["path"]
        self.assertEqual(path["peak_interval_start"], "2026-08-07T00:00:00Z")
        self.assertEqual(path["trough_interval_start"], "2026-08-07T00:00:00Z")
        self.assertEqual(
            path["largest_absolute_hourly_return_interval_start"],
            "2026-08-07T00:00:00Z",
        )
        self.assertIsNone(path["close_in_range"])
        self.assertIsNone(path["largest_hour_volume_interval_start"])
        self.assertIsNone(path["largest_hour_volume_share"])

    def test_heartbeat_orders_event_and_preserves_r1_state(self) -> None:
        self._seal_day("2026-08-06", "100", received_at="2026-08-07T01:00:00Z")
        self._heartbeat(datetime(2026, 8, 7, 2, tzinfo=UTC))
        self._seal_day("2026-08-07", "103", received_at="2026-08-08T01:00:00Z")
        trigger_path = self.store.evidence_trigger_dir(R1_TRIGGER_ID) / "trigger.json"
        state_path = self.store.evidence_trigger_dir(R1_TRIGGER_ID) / "state.json"
        trigger_before = trigger_path.read_bytes()
        state_before = state_path.read_bytes()
        result = self._heartbeat(datetime(2026, 8, 8, 2, tzinfo=UTC))
        self.assertEqual(result["event_type"], "MATERIAL_LEARNING")
        self.assertEqual(
            result["events"][0]["research_status"],
            "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_READY",
        )
        self.assertEqual(trigger_path.read_bytes(), trigger_before)
        self.assertEqual(state_path.read_bytes(), state_before)

    def test_rollover_v2_requires_two_successor_days_and_is_idempotent(self) -> None:
        self._seal_day(
            "2026-08-06", "97", received_at="2026-08-07T01:00:00Z"
        )
        successor = self._rollover()
        self._seal_leaf_day(
            successor, "2026-08-09", "100", received_at="2026-08-10T01:00:00Z"
        )
        self.assertEqual(
            seal_r1_shock_diagnostics(
                self.store,
                now=datetime(2026, 8, 10, 2, tzinfo=UTC),
                contract_activated_at="2026-08-09T00:00:00Z",
            ),
            [],
        )
        self.assertEqual(self._v2_artifacts(successor), [])
        self._seal_leaf_day(
            successor, "2026-08-10", "103", received_at="2026-08-11T01:00:00Z"
        )
        first = seal_r1_shock_diagnostics(
            self.store,
            now=datetime(2026, 8, 11, 2, tzinfo=UTC),
            contract_activated_at="2026-08-09T00:00:00Z",
        )
        self.assertEqual(len(first), 1)
        artifact = self._v2_artifacts(successor)[0]
        before = artifact.read_bytes()
        diagnostic = read_json(artifact)
        self.v2_validator.validate(diagnostic)
        self.assertEqual(diagnostic["root_trigger_id"], R1_TRIGGER_ID)
        self.assertEqual(diagnostic["leaf_trigger_id"], successor["trigger_id"])
        self.assertEqual(diagnostic["prior_day"]["day"], "2026-08-09")
        self.assertEqual(diagnostic["target_day"]["day"], "2026-08-10")
        repeated = seal_r1_shock_diagnostics(
            self.store,
            now=datetime(2026, 8, 11, 3, tzinfo=UTC),
            contract_activated_at="2026-08-09T00:00:00Z",
        )
        self.assertEqual(repeated, [])
        self.assertEqual(artifact.read_bytes(), before)
        self.assertEqual(self._artifacts(), [])

    def test_rollover_partial_missing_successor_fails_closed(self) -> None:
        state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        state.update(
            {
                "status": "CLOSED",
                "next_review_at": None,
                "rollover_reason": "MISSED_CAPTURE_WINDOW_NO_BACKFILL",
                "rollover_successor_trigger_id": "missing-rollover-successor",
                "rollover_successor_fingerprint": "f" * 64,
                "rollover_closed_at": "2026-08-08T07:00:00Z",
            }
        )
        self.store.save_evidence_trigger_state(state)
        with self.assertRaisesRegex(ValueError, "successor is missing"):
            seal_r1_shock_diagnostics(
                self.store,
                now=datetime(2026, 8, 10, 2, tzinfo=UTC),
                contract_activated_at="2026-08-09T00:00:00Z",
            )

    def test_rollover_lineage_tamper_and_ambiguous_leaf_fail_closed(self) -> None:
        successor = self._rollover()
        successor_state_path = (
            self.store.evidence_trigger_dir(successor["trigger_id"]) / "state.json"
        )
        successor_state = read_json(successor_state_path)
        successor_state["rollover_predecessor_fingerprint"] = "f" * 64
        successor_state_path.write_text(
            json.dumps(successor_state, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "predecessor_fingerprint mismatch"):
            seal_r1_shock_diagnostics(
                self.store,
                now=datetime(2026, 8, 10, 2, tzinfo=UTC),
                contract_activated_at="2026-08-09T00:00:00Z",
            )

        successor_state["rollover_predecessor_fingerprint"] = R1_TRIGGER_FINGERPRINT
        successor_state_path.write_text(
            json.dumps(successor_state, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        fork_value = {
            **self._trigger_value(),
            "trigger_id": "synthetic-rollover-fork",
            "title": "Synthetic rollover fork",
            "rationale": "Must be rejected as an ambiguous active leaf.",
            "evidence_start": "2026-08-10T00:00:00Z",
            "review_not_before": "2026-11-08T00:00:00Z",
            "created_at": successor["created_at"],
        }
        fork = build_evidence_trigger(fork_value)
        self.store.register_evidence_trigger(fork)
        fork_state = self.store.load_evidence_trigger_state(fork["trigger_id"])
        fork_state.update(
            {
                "rollover_predecessor_trigger_id": R1_TRIGGER_ID,
                "rollover_predecessor_fingerprint": R1_TRIGGER_FINGERPRINT,
                "rollover_reason": "MISSED_CAPTURE_WINDOW_NO_BACKFILL",
            }
        )
        self.store.save_evidence_trigger_state(fork_state)
        with self.assertRaisesRegex(ValueError, "ambiguous fork"):
            seal_r1_shock_diagnostics(
                self.store,
                now=datetime(2026, 8, 10, 2, tzinfo=UTC),
                contract_activated_at="2026-08-09T00:00:00Z",
            )

    def test_rollover_lineage_cycle_fails_closed(self) -> None:
        successor = self._rollover()
        successor_state = self.store.load_evidence_trigger_state(successor["trigger_id"])
        successor_state.update(
            {
                "status": "CLOSED",
                "next_review_at": None,
                "rollover_successor_trigger_id": R1_TRIGGER_ID,
                "rollover_successor_fingerprint": R1_TRIGGER_FINGERPRINT,
                "rollover_closed_at": self.trigger["created_at"],
            }
        )
        self.store.save_evidence_trigger_state(successor_state)
        root_state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        root_state.update(
            {
                "rollover_predecessor_trigger_id": successor["trigger_id"],
                "rollover_predecessor_fingerprint": successor["fingerprint"],
            }
        )
        self.store.save_evidence_trigger_state(root_state)
        with self.assertRaisesRegex(ValueError, "cycle"):
            seal_r1_shock_diagnostics(
                self.store,
                now=datetime(2026, 8, 10, 2, tzinfo=UTC),
                contract_activated_at="2026-08-09T00:00:00Z",
            )

    def _trigger_value(self) -> dict[str, object]:
        return {
            "schema_version": "1",
            "trigger_id": R1_TRIGGER_ID,
            "title": "Prospective mechanism-neutral evidence refresh R1",
            "rationale": "Frozen synthetic R1 fixture.",
            "source": R1_SOURCE,
            "evidence_start": "2026-08-06T00:00:00Z",
            "review_not_before": "2026-11-04T00:00:00Z",
            "minimum_observations": 90,
            "observation_unit": "COMPLETE_UTC_DAY",
            "required_integrity_checks": [
                "closed_bar_causality",
                "no_gap_or_duplicate_complete_hours",
                "immutable_row_count_and_sha256",
                "mechanism_neutral_diagnostic_before_strategy_mapping",
                "new_hypothesis_fingerprint_not_in_closed_tree",
            ],
            "prohibited_inferences": [
                "the accumulation window is not OOS for a hypothesis derived from it",
                "no strategy performance selection before a later hypothesis and manifest are frozen",
                "no reopening or retuning a closed branch",
                "no SHADOW PAPER LIVE Production order database scheduler or deployment implication",
            ],
            "excluded_branches": [
                "ordinary EMA5 downturn or simple post-1R peak giveback",
                "V10 V11 V12 failed-reclaim variants",
                "flat-regime stale-inventory veto and cooldown route substitution",
                "one-slot profitable-incumbent fresh-signal rotation",
                "flat-range upper-touch qualification and neighboring threshold scans",
            ],
            "created_at": "2026-08-04T05:30:00Z",
            "authorization": RESEARCH_AUTHORIZATION,
        }

    def _daily_bundle(
        self,
        day: str,
        close: str,
        *,
        target_open: str = "100",
        volume: str = "10",
    ) -> dict[str, object]:
        start = datetime.fromisoformat(day).replace(tzinfo=UTC)
        close_value = Decimal(close)
        open_value = Decimal(target_open)
        high = max(open_value, close_value)
        low = min(open_value, close_value)
        bars = []
        for hour in range(24):
            interval_start = start + timedelta(hours=hour)
            bars.append(
                {
                    "interval_start": self._iso(interval_start),
                    "interval_end": self._iso(interval_start + timedelta(hours=1)),
                    "open": str(open_value),
                    "high": str(high),
                    "low": str(low),
                    "close": str(close_value),
                    "volume": volume,
                }
            )
        return {
            "schema_version": "1",
            "bundle_type": "FORWARD_EVIDENCE_DAY",
            "trigger_id": R1_TRIGGER_ID,
            "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
            "source": R1_SOURCE,
            "day": day,
            "bars": bars,
            "source_provenance": {
                "producer": "shock-fixture-producer",
                "artifact_id": f"source-{day}",
                "sha256": "1" * 64,
            },
            "authorization": RESEARCH_AUTHORIZATION,
        }

    def _seal_day(self, day: str, close: str, *, received_at: str) -> None:
        state = self.store.load_evidence_trigger_state(R1_TRIGGER_ID)
        seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle(day, close),
            received_at=datetime.fromisoformat(received_at.replace("Z", "+00:00")),
        )

    def _seal_pair(self, prior_close: str, target_close: str) -> None:
        self._seal_day("2026-08-06", prior_close, received_at="2026-08-07T01:00:00Z")
        self._seal_day("2026-08-07", target_close, received_at="2026-08-08T01:00:00Z")

    def _rollover(self) -> dict[str, object]:
        result = rollover_missed_discovery_window(
            self.store,
            self.trigger,
            self.store.load_evidence_trigger_state(R1_TRIGGER_ID),
            now=datetime(2026, 8, 8, 7, tzinfo=UTC),
        )
        return self.store.load_evidence_trigger(str(result["successor_trigger_id"]))

    def _seal_leaf_day(
        self,
        trigger: dict[str, object],
        day: str,
        close: str,
        *,
        received_at: str,
    ) -> None:
        bundle = self._daily_bundle(day, close)
        bundle["trigger_id"] = trigger["trigger_id"]
        bundle["trigger_fingerprint"] = trigger["fingerprint"]
        state = self.store.load_evidence_trigger_state(str(trigger["trigger_id"]))
        seal_daily_evidence(
            self.store,
            trigger,
            state,
            bundle,
            received_at=datetime.fromisoformat(received_at.replace("Z", "+00:00")),
        )

    def _seal_shocks(self, *, activation: str) -> list[dict[str, object]]:
        return seal_r1_shock_diagnostics(
            self.store,
            now=datetime(2026, 8, 8, 2, tzinfo=UTC),
            contract_activated_at=activation,
        )

    def _seal_shocks_v2(
        self, *, now: datetime, activation: str
    ) -> list[dict[str, object]]:
        return seal_r1_shock_diagnostics(
            self.store,
            now=now,
            contract_activated_at=activation,
        )

    def _heartbeat(self, now: datetime) -> dict[str, object]:
        tick = {"status": "IDLE_NO_ACTIONABLE_EXPERIMENT"}
        microstructure_status = {
            "status": "WAITING_FOR_DAY",
            "diagnostic_id": None,
            "start_day": None,
            "accepted_day_count": None,
            "required_day_count": None,
            "next_expected_day": None,
            "artifact_path": None,
            "sha256": None,
            "lag_classification": "PRE_START",
        }
        with patch(
            "research_pipeline.heartbeat.microstructure_diagnostic_status",
            return_value=microstructure_status,
        ):
            return run_heartbeat_cycle(
                self.store,
                {"policy_id": "TEST_RESEARCH_ONLY"},
                now=now,
                tick_preview=tick,
                tick_result=tick,
            )

    def _artifacts(self) -> list[Path]:
        namespace = self.root / DIAGNOSTIC_NAMESPACE
        return sorted(namespace.glob("*.json")) if namespace.exists() else []

    def _v2_artifacts(self, trigger: dict[str, object]) -> list[Path]:
        namespace = self.root / V2_DIAGNOSTIC_NAMESPACE / str(trigger["fingerprint"])
        return sorted(namespace.glob("*.json")) if namespace.exists() else []

    def _direct_pair(
        self,
        prior_close: str,
        target_close: str,
        *,
        target_open: str = "100",
        volume: str = "10",
    ) -> tuple[dict[str, str], dict[str, object], dict[str, str], dict[str, object]]:
        prior = self._daily_bundle("2026-08-06", prior_close)
        target = self._daily_bundle(
            "2026-08-07", target_close, target_open=target_open, volume=volume
        )
        prior_ref = {
            "day": "2026-08-06",
            "artifact_path": "evidence-triggers/r1/observations/2026-08-06.json",
            "artifact_sha256": "a" * 64,
            "chain_head": "b" * 64,
            "received_at": "2026-08-07T01:00:00Z",
        }
        target_ref = {
            "day": "2026-08-07",
            "artifact_path": "evidence-triggers/r1/observations/2026-08-07.json",
            "artifact_sha256": "c" * 64,
            "chain_head": "d" * 64,
            "received_at": "2026-08-08T01:00:00Z",
        }
        return prior_ref, prior, target_ref, target

    @staticmethod
    def _iso(value: datetime) -> str:
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    unittest.main()
