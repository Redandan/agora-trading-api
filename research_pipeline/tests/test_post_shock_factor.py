from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

from jsonschema import Draft202012Validator

from research_pipeline.heartbeat import run_heartbeat_cycle
from research_pipeline.post_shock_factor import (
    CONTINUATION,
    DOCUMENT_TYPE,
    NO_FACTOR,
    REVERSAL,
    SCHEMA_PATH,
    V2_SCHEMA_PATH,
    WAIT,
    _canonical_bytes,
    _create_only,
    build_post_shock_episode,
    build_post_shock_snapshot,
    seal_r1_post_shock_factor_snapshots,
)
from research_pipeline.storage import ResearchStore, read_json, sha256_file
from research_pipeline.tests import test_shock_attribution as shock_fixture


UTC = timezone.utc


class PostShockFactorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(cls.schema)
        cls.v2_schema = json.loads(V2_SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.v2_schema)
        cls.v2_validator = Draft202012Validator(cls.v2_schema)

    def test_schema_is_closed_and_conditionally_binds_wait(self) -> None:
        snapshot = build_post_shock_snapshot(
            [self._episode(0, "2026-08-08", "UP", "0.01")],
            sealed_at="2026-08-09T02:00:00Z",
        )
        self.validator.validate(snapshot)
        self.assertEqual(snapshot["disposition"], WAIT)
        self.assertFalse(snapshot["terminal"])
        invalid = {**snapshot, "unexpected": True}
        self.assertTrue(list(self.validator.iter_errors(invalid)))
        contradictory = copy.deepcopy(snapshot)
        contradictory["terminal"] = True
        self.assertTrue(list(self.validator.iter_errors(contradictory)))

    def test_heartbeat_runtime_import_does_not_require_jsonschema(self) -> None:
        program = """
import builtins
real_import = builtins.__import__
def guarded_import(name, *args, **kwargs):
    if name == 'jsonschema' or name.startswith('jsonschema.'):
        raise ImportError('jsonschema is intentionally unavailable')
    return real_import(name, *args, **kwargs)
builtins.__import__ = guarded_import
import research_pipeline.heartbeat
"""
        completed = subprocess.run(
            [sys.executable, "-B", "-c", program],
            cwd=Path(__file__).resolve().parents[2],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_exact_up_formula_uses_h24_as_primary(self) -> None:
        episode = self._built_episode("UP", ("101", "102", "103"))
        self.assertEqual(episode["signed_response_h1"], "0.01")
        self.assertEqual(episode["signed_response_h6"], "0.02")
        self.assertEqual(episode["signed_response_h24"], "0.03")
        self.assertEqual(episode["primary_label"], "CONTINUATION")

    def test_exact_down_formula_is_shock_signed(self) -> None:
        episode = self._built_episode("DOWN", ("99", "98", "97"))
        self.assertEqual(episode["signed_response_h1"], "0.01")
        self.assertEqual(episode["signed_response_h6"], "0.02")
        self.assertEqual(episode["signed_response_h24"], "0.03")
        self.assertEqual(episode["primary_label"], "CONTINUATION")

    def test_h1_h6_cannot_override_h24_label(self) -> None:
        episode = self._built_episode("UP", ("102", "103", "99"))
        self.assertEqual(episode["signed_response_h1"], "0.02")
        self.assertEqual(episode["signed_response_h6"], "0.03")
        self.assertEqual(episode["signed_response_h24"], "-0.01")
        self.assertEqual(episode["primary_label"], "REVERSAL")

    def test_tie_is_exact_zero(self) -> None:
        episode = self._built_episode("UP", ("101", "99", "100"))
        self.assertEqual(episode["signed_response_h24"], "0")
        self.assertEqual(episode["primary_label"], "TIE")

    def test_context_only_diagnostic_is_rejected(self) -> None:
        diagnostic, outcome_reference, outcome = self._episode_inputs(
            "UP", ("101", "102", "103")
        )
        diagnostic["eligibility"] = "CONTEXT_ONLY"
        with self.assertRaisesRegex(ValueError, "forward-factor eligibility"):
            self._build(diagnostic, outcome_reference, outcome)

    def test_outcome_must_be_adjacent_and_received_after_diagnostic(self) -> None:
        diagnostic, outcome_reference, outcome = self._episode_inputs(
            "UP", ("101", "102", "103")
        )
        outcome_reference["day"] = "2026-08-09"
        outcome["day"] = "2026-08-09"
        with self.assertRaisesRegex(ValueError, "adjacent UTC day"):
            self._build(diagnostic, outcome_reference, outcome)
        diagnostic, outcome_reference, outcome = self._episode_inputs(
            "UP", ("101", "102", "103")
        )
        outcome_reference["received_at"] = diagnostic["sealed_at"]
        outcome["received_at"] = diagnostic["sealed_at"]
        with self.assertRaisesRegex(ValueError, "after diagnostic seal"):
            self._build(diagnostic, outcome_reference, outcome)

    def test_outcome_grid_gap_and_duplicate_are_rejected(self) -> None:
        diagnostic, outcome_reference, outcome = self._episode_inputs(
            "UP", ("101", "102", "103")
        )
        outcome["bars"][5]["interval_start"] = outcome["bars"][4]["interval_start"]
        with self.assertRaisesRegex(ValueError, "not contiguous"):
            self._build(diagnostic, outcome_reference, outcome)

    def test_wait_exposes_individual_breadth_failures(self) -> None:
        episodes = [
            self._episode(0, "2026-08-08", "UP", "0.01"),
            self._episode(1, "2026-08-15", "DOWN", "0.01"),
            self._episode(2, "2026-08-22", "UP", "0.01"),
        ]
        snapshot = build_post_shock_snapshot(
            episodes, sealed_at="2026-08-23T02:00:00Z"
        )
        gates = snapshot["gate_evidence"]
        self.assertEqual(snapshot["disposition"], WAIT)
        self.assertFalse(gates["minimum_episode_count_pass"])
        self.assertFalse(gates["down_breadth_pass"])
        self.assertFalse(gates["utc_month_breadth_pass"])

    def test_month_concentration_blocks_terminal_sample(self) -> None:
        days = [
            "2026-08-01", "2026-08-05", "2026-08-09", "2026-08-13",
            "2026-08-17", "2026-09-01", "2026-09-05", "2026-10-01",
        ]
        snapshot = build_post_shock_snapshot(
            [
                self._episode(i, day, "UP" if i % 2 == 0 else "DOWN", "0.01")
                for i, day in enumerate(days)
            ],
            sealed_at="2026-10-02T02:00:00Z",
        )
        self.assertEqual(snapshot["disposition"], WAIT)
        self.assertFalse(snapshot["gate_evidence"]["month_concentration_pass"])

    def test_single_episode_concentration_blocks_terminal_sample(self) -> None:
        episodes = self._broad_episodes("0.01")
        episodes[0]["signed_response_h24"] = "1"
        episodes[0]["primary_label"] = "CONTINUATION"
        snapshot = build_post_shock_snapshot(
            episodes, sealed_at="2026-10-11T02:00:00Z"
        )
        self.assertEqual(snapshot["disposition"], WAIT)
        self.assertFalse(snapshot["gate_evidence"]["episode_concentration_pass"])

    def test_continuation_terminal_disposition(self) -> None:
        snapshot = build_post_shock_snapshot(
            self._broad_episodes("0.01"), sealed_at="2026-10-11T02:00:00Z"
        )
        self.validator.validate(snapshot)
        self.assertEqual(snapshot["disposition"], CONTINUATION)
        self.assertTrue(snapshot["terminal"])
        self.assertTrue(snapshot["statistics"]["continuation_conditions_met"])
        self.assertFalse(snapshot["statistics"]["reversal_conditions_met"])

    def test_reversal_terminal_disposition(self) -> None:
        snapshot = build_post_shock_snapshot(
            self._broad_episodes("-0.01"), sealed_at="2026-10-11T02:00:00Z"
        )
        self.validator.validate(snapshot)
        self.assertEqual(snapshot["disposition"], REVERSAL)
        self.assertTrue(snapshot["statistics"]["reversal_conditions_met"])

    def test_mixed_and_tie_sample_closes_no_factor(self) -> None:
        episodes = self._broad_episodes("0.01")
        for index in (1, 3, 5, 7):
            episodes[index]["signed_response_h24"] = "-0.01"
            episodes[index]["primary_label"] = "REVERSAL"
        snapshot = build_post_shock_snapshot(
            episodes, sealed_at="2026-10-11T02:00:00Z"
        )
        self.assertEqual(snapshot["disposition"], NO_FACTOR)
        self.assertFalse(snapshot["statistics"]["continuation_conditions_met"])
        self.assertFalse(snapshot["statistics"]["reversal_conditions_met"])

    def test_duplicate_episode_is_rejected(self) -> None:
        episodes = self._broad_episodes("0.01")
        episodes[1]["episode_id"] = episodes[0]["episode_id"]
        with self.assertRaisesRegex(ValueError, "duplicated"):
            build_post_shock_snapshot(
                episodes, sealed_at="2026-10-11T02:00:00Z"
            )

    def test_create_only_is_idempotent_and_conflict_preserves_bytes(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            path = Path(temporary) / "snapshot.json"
            original = _canonical_bytes({"sealed": "original"})
            conflict = _canonical_bytes({"sealed": "conflict"})
            self.assertTrue(_create_only(path, original))
            self.assertFalse(_create_only(path, original))
            self.assertFalse(_create_only(path, conflict))
            self.assertEqual(path.read_bytes(), original)

    def test_real_synthetic_store_seals_wait_once_without_r1_mutation(self) -> None:
        fixture = shock_fixture.ShockAttributionTest(
            "test_below_threshold_creates_no_artifact"
        )
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        fixture._seal_day(
            "2026-08-06", "100", received_at="2026-08-07T01:00:00Z"
        )
        fixture._heartbeat(datetime(2026, 8, 7, 2, tzinfo=UTC))
        fixture._seal_day(
            "2026-08-07", "103", received_at="2026-08-08T01:00:00Z"
        )
        fixture._heartbeat(datetime(2026, 8, 8, 2, tzinfo=UTC))
        fixture._seal_day(
            "2026-08-08", "104", received_at="2026-08-09T01:00:00Z"
        )
        r1_state = fixture.store.evidence_trigger_dir(
            "prospective-mechanism-neutral-evidence-refresh-2026q4-r1"
        ) / "state.json"
        state_before = r1_state.read_bytes()
        first = fixture._heartbeat(datetime(2026, 8, 9, 2, tzinfo=UTC))
        namespace = (
            fixture.root
            / "post-shock-factor"
            / "btc-utc-day-3pct-v1"
            / "snapshots"
        )
        snapshots = sorted(namespace.glob("*.json"))
        self.assertEqual(len(snapshots), 1)
        self.assertEqual(read_json(snapshots[0])["disposition"], WAIT)
        snapshot_before = snapshots[0].read_bytes()
        second = fixture._heartbeat(datetime(2026, 8, 9, 3, tzinfo=UTC))
        self.assertFalse(
            any(
                event.get("research_status")
                == "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_TERMINAL"
                for event in first["events"] + second["events"]
            )
        )
        self.assertEqual(snapshots[0].read_bytes(), snapshot_before)
        self.assertEqual(r1_state.read_bytes(), state_before)

    def test_rollover_v2_seals_one_fingerprint_isolated_wait_snapshot(self) -> None:
        fixture = shock_fixture.ShockAttributionTest(
            "test_below_threshold_creates_no_artifact"
        )
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        successor = fixture._rollover()
        fixture._seal_leaf_day(
            successor, "2026-08-09", "100", received_at="2026-08-10T01:00:00Z"
        )
        fixture._seal_leaf_day(
            successor, "2026-08-10", "103", received_at="2026-08-11T01:00:00Z"
        )
        fixture._seal_shocks_v2(
            now=datetime(2026, 8, 11, 2, tzinfo=UTC),
            activation="2026-08-09T00:00:00Z",
        )
        fixture._seal_leaf_day(
            successor, "2026-08-11", "104", received_at="2026-08-12T01:00:00Z"
        )
        first = seal_r1_post_shock_factor_snapshots(
            fixture.store, now=datetime(2026, 8, 12, 2, tzinfo=UTC)
        )
        self.assertEqual(first, [])
        namespace = (
            fixture.root
            / "post-shock-factor"
            / "btc-utc-day-3pct-v2"
            / str(successor["fingerprint"])
            / "snapshots"
        )
        snapshots = sorted(namespace.glob("*.json"))
        self.assertEqual(len(snapshots), 1)
        snapshot = read_json(snapshots[0])
        self.v2_validator.validate(snapshot)
        self.assertEqual(snapshot["disposition"], WAIT)
        self.assertEqual(snapshot["root_trigger_id"], shock_fixture.R1_TRIGGER_ID)
        self.assertEqual(snapshot["leaf_trigger_id"], successor["trigger_id"])
        self.assertEqual(len(snapshot["episodes"]), 1)
        self.assertEqual(
            snapshot["episodes"][0]["leaf_trigger_fingerprint"],
            successor["fingerprint"],
        )
        before = snapshots[0].read_bytes()
        repeated = seal_r1_post_shock_factor_snapshots(
            fixture.store, now=datetime(2026, 8, 12, 3, tzinfo=UTC)
        )
        self.assertEqual(repeated, [])
        self.assertEqual(snapshots[0].read_bytes(), before)

    def test_rollover_v2_zero_observation_successor_keeps_heartbeat_live(self) -> None:
        fixture = shock_fixture.ShockAttributionTest(
            "test_below_threshold_creates_no_artifact"
        )
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        successor = fixture._rollover()
        successor_state = fixture.store.load_evidence_trigger_state(
            str(successor["trigger_id"])
        )
        self.assertNotIn("evidence_observations", successor_state)

        heartbeat = fixture._heartbeat(datetime(2026, 8, 8, 8, tzinfo=UTC))

        self.assertEqual("HEARTBEAT_OK", heartbeat["status"])
        self.assertEqual(
            [],
            seal_r1_post_shock_factor_snapshots(
                fixture.store, now=datetime(2026, 8, 8, 9, tzinfo=UTC)
            ),
        )
        namespace = (
            fixture.root
            / "post-shock-factor"
            / "btc-utc-day-3pct-v2"
            / str(successor["fingerprint"])
            / "snapshots"
        )
        self.assertEqual([], sorted(namespace.glob("*.json")))

    def test_rollover_v2_rejects_cross_lineage_diagnostic(self) -> None:
        fixture = shock_fixture.ShockAttributionTest(
            "test_below_threshold_creates_no_artifact"
        )
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        successor = fixture._rollover()
        fixture._seal_leaf_day(
            successor, "2026-08-09", "100", received_at="2026-08-10T01:00:00Z"
        )
        fixture._seal_leaf_day(
            successor, "2026-08-10", "103", received_at="2026-08-11T01:00:00Z"
        )
        fixture._seal_shocks_v2(
            now=datetime(2026, 8, 11, 2, tzinfo=UTC),
            activation="2026-08-09T00:00:00Z",
        )
        fixture._seal_leaf_day(
            successor, "2026-08-11", "104", received_at="2026-08-12T01:00:00Z"
        )
        artifact = fixture._v2_artifacts(successor)[0]
        diagnostic = read_json(artifact)
        diagnostic["leaf_trigger_fingerprint"] = "f" * 64
        artifact.write_text(
            json.dumps(diagnostic, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "leaf_trigger_fingerprint mismatch"):
            seal_r1_post_shock_factor_snapshots(
                fixture.store, now=datetime(2026, 8, 12, 2, tzinfo=UTC)
            )

    def test_heartbeat_child_orders_event_and_reuses_single_outbox_entry(self) -> None:
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            root = Path(temporary)
            store = ResearchStore(root, lock_stale_seconds=60)
            store.bootstrap()
            artifact = root / "synthetic" / "post-shock.json"
            artifact.parent.mkdir(parents=True)
            artifact.write_text('{"synthetic":true}\n', encoding="utf-8")
            event = self._coach_event(
                "synthetic/post-shock.json", sha256_file(artifact)
            )
            first = self._heartbeat(store, [event], datetime(2026, 8, 8, 2, tzinfo=UTC))
            second = self._heartbeat(store, [], datetime(2026, 8, 8, 3, tzinfo=UTC))
            self.assertEqual(first["events"][0]["research_status"], event["research_status"])
            self.assertEqual(second["events"], [])
            state = read_json(root / "heartbeat" / "state.json")
            pending = [
                item
                for item in state["coach_delivery"]["pending_events"]
                if item.get("research_status") == event["research_status"]
            ]
            self.assertEqual(len(pending), 1)

    def _built_episode(
        self, direction: str, closes: tuple[str, str, str]
    ) -> dict[str, object]:
        diagnostic, outcome_reference, outcome = self._episode_inputs(direction, closes)
        return self._build(diagnostic, outcome_reference, outcome)

    def _build(
        self,
        diagnostic: dict[str, object],
        outcome_reference: dict[str, str],
        outcome: dict[str, object],
    ) -> dict[str, object]:
        return build_post_shock_episode(
            diagnostic=diagnostic,
            diagnostic_path="shock-diagnostics/btc-utc-day-3pct-v1/2026-08-07.json",
            diagnostic_sha256="a" * 64,
            outcome_reference=outcome_reference,
            outcome_bundle=outcome,
            sealed_at="2026-08-09T02:00:00Z",
        )

    def _episode_inputs(
        self, direction: str, closes: tuple[str, str, str]
    ) -> tuple[dict[str, object], dict[str, str], dict[str, object]]:
        target_reference = {
            "day": "2026-08-07",
            "artifact_path": "evidence-triggers/r1/observations/2026-08-07.json",
            "artifact_sha256": "b" * 64,
            "chain_head": "c" * 64,
            "received_at": "2026-08-08T01:00:00Z",
        }
        diagnostic = {
            "eligibility": "FORWARD_FACTOR_ELIGIBLE",
            "target_day": target_reference,
            "sealed_at": "2026-08-08T02:00:00Z",
            "path": {"direction": direction, "target_close": "100"},
        }
        outcome_reference = {
            "day": "2026-08-08",
            "artifact_path": "evidence-triggers/r1/observations/2026-08-08.json",
            "artifact_sha256": "d" * 64,
            "chain_head": "e" * 64,
            "received_at": "2026-08-09T01:00:00Z",
        }
        start = datetime(2026, 8, 8, tzinfo=UTC)
        bars = []
        for hour in range(24):
            close = "100"
            if hour == 0:
                close = closes[0]
            elif hour == 5:
                close = closes[1]
            elif hour == 23:
                close = closes[2]
            interval_start = start + timedelta(hours=hour)
            bars.append(
                {
                    "interval_start": self._iso(interval_start),
                    "interval_end": self._iso(interval_start + timedelta(hours=1)),
                    "open": close,
                    "high": close,
                    "low": close,
                    "close": close,
                    "volume": "1",
                }
            )
        outcome = {
            "schema_version": "1",
            "bundle_type": "FORWARD_EVIDENCE_DAY",
            "trigger_id": "prospective-mechanism-neutral-evidence-refresh-2026q4-r1",
            "trigger_fingerprint": "0e5a4675e937613202f0a4a243360a405e9ace1823c4b999edb5d479849d2589",
            "source": (
                "server-local read-only OKX BTCUSDT complete 1h bars aggregated "
                "into complete UTC days"
            ),
            "day": "2026-08-08",
            "received_at": "2026-08-09T01:00:00Z",
            "bars": bars,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        }
        return diagnostic, outcome_reference, outcome

    def _broad_episodes(self, response: str) -> list[dict[str, object]]:
        days = [
            "2026-08-08", "2026-08-15", "2026-08-22",
            "2026-09-05", "2026-09-12", "2026-09-19",
            "2026-10-03", "2026-10-10",
        ]
        return [
            self._episode(i, day, "UP" if i % 2 == 0 else "DOWN", response)
            for i, day in enumerate(days)
        ]

    def _episode(
        self, index: int, outcome_day: str, direction: str, response: str
    ) -> dict[str, object]:
        outcome = date.fromisoformat(outcome_day)
        target = outcome - timedelta(days=1)
        episode_id = hashlib.sha256(
            f"episode-{index}-{outcome_day}".encode("utf-8")
        ).hexdigest()
        label = "CONTINUATION" if response.startswith("0.") and response != "0" else (
            "REVERSAL" if response.startswith("-") else "TIE"
        )
        return {
            "episode_id": episode_id,
            "shock_diagnostic_path": f"shock-diagnostics/{target.isoformat()}.json",
            "shock_diagnostic_sha256": hashlib.sha256(
                f"diagnostic-{index}".encode("utf-8")
            ).hexdigest(),
            "diagnostic_target_reference": self._reference(target, index, "a"),
            "outcome_day_reference": self._reference(outcome, index, "b"),
            "t0": f"{outcome_day}T00:00:00Z",
            "sealed_at": f"{outcome_day}T02:00:00Z",
            "shock_direction": direction,
            "signed_response_h1": response,
            "signed_response_h6": response,
            "signed_response_h24": response,
            "primary_label": label,
        }

    @staticmethod
    def _reference(day: date, index: int, salt: str) -> dict[str, str]:
        digest = hashlib.sha256(f"{salt}-{index}-{day}".encode("utf-8")).hexdigest()
        chain = hashlib.sha256(f"chain-{index}-{day}".encode("utf-8")).hexdigest()
        return {
            "day": day.isoformat(),
            "artifact_path": f"evidence/{day.isoformat()}.json",
            "artifact_sha256": digest,
            "chain_head": chain,
            "received_at": f"{(day + timedelta(days=1)).isoformat()}T01:00:00Z",
        }

    @staticmethod
    def _coach_event(path: str, sha256: str) -> dict[str, object]:
        return {
            "event_type": "MATERIAL_LEARNING",
            "artifact_path": path,
            "sha256": sha256,
            "research_status": "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_TERMINAL",
            "material_conclusion": "Synthetic terminal post-shock disposition.",
            "pnl_drawdown_evidence": {
                "immediate_effect": "ZERO",
                "economic_value": "MISSING_PROOF",
            },
            "evidence_diagnostic": {
                "diagnostic_type": DOCUMENT_TYPE,
                "disposition": CONTINUATION,
                "episode_count": 8,
                "primary_horizon": "H24",
            },
            "uncertainty": "Predictive and economic value remain MISSING_PROOF.",
            "next_action": "MANAGER_REVIEW_ONLY_NO_HYPOTHESIS_CANDIDATE_OR_OOS",
            "concept_to_teach": "Synthetic post-shock fixture.",
        }

    @staticmethod
    def _heartbeat(
        store: ResearchStore,
        events: list[dict[str, object]],
        now: datetime,
    ) -> dict[str, object]:
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
        with (
            patch(
                "research_pipeline.heartbeat.microstructure_diagnostic_status",
                return_value=microstructure_status,
            ),
            patch(
                "research_pipeline.heartbeat.seal_r1_shock_diagnostics",
                return_value=[],
            ),
            patch(
                "research_pipeline.heartbeat.seal_r1_post_shock_factor_snapshots",
                return_value=events,
            ),
        ):
            return run_heartbeat_cycle(
                store,
                {"policy_id": "TEST_RESEARCH_ONLY"},
                now=now,
                tick_preview=tick,
                tick_result=tick,
            )

    @staticmethod
    def _iso(value: datetime) -> str:
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


if __name__ == "__main__":
    unittest.main()
