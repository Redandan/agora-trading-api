from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from research_pipeline.cli import register_candidate_bundle, verify_review_artifacts
from research_pipeline.evidence import (
    evidence_progress,
    register_evidence_source_contract,
    seal_daily_evidence,
    validate_evidence_manifest,
)
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.heartbeat import run_heartbeat_cycle
from research_pipeline.policy import load_policy, policy_sha256
from research_pipeline.storage import ResearchStore, atomic_write_json, sha256_file
from research_pipeline.waiting import build_evidence_review, build_evidence_trigger


class EvidenceManifestContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = ResearchStore(self.root, lock_stale_seconds=60)
        self.store.bootstrap()
        self.trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "forward-evidence-test",
                "title": "Forward evidence test",
                "rationale": "Prove the evidence gate from deterministic observations.",
                "source": "sealed test source",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": ["closed_bar_causality", "no_gap_or_duplicate"],
                "prohibited_inferences": ["no performance selection"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        self.store.register_evidence_trigger(self.trigger)
        evidence_dir = self.store.evidence_trigger_dir(self.trigger["trigger_id"])
        self.dataset = evidence_dir / "dataset.tsv"
        self.diagnostic = evidence_dir / "diagnostic.json"
        self.dataset.write_text("sealed dataset\n", encoding="utf-8")
        self.diagnostic.write_text('{"diagnostic":"mechanism-neutral"}\n', encoding="utf-8")
        self.manifest = self._manifest()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _manifest(self) -> dict[str, object]:
        evidence_dir = self.store.evidence_trigger_dir(self.trigger["trigger_id"])
        return {
            "schema_version": "1",
            "manifest_type": "FORWARD_EVIDENCE_MANIFEST",
            "trigger_id": self.trigger["trigger_id"],
            "trigger_fingerprint": self.trigger["fingerprint"],
            "source": self.trigger["source"],
            "observation_unit": self.trigger["observation_unit"],
            "coverage_start": "2026-01-01T00:00:00Z",
            "coverage_end": "2026-01-03T00:00:00Z",
            "observations": [
                {
                    "observation_id": "2026-01-01",
                    "start_at": "2026-01-01T00:00:00Z",
                    "end_at": "2026-01-02T00:00:00Z",
                    "source_row_count": 24,
                },
                {
                    "observation_id": "2026-01-02",
                    "start_at": "2026-01-02T00:00:00Z",
                    "end_at": "2026-01-03T00:00:00Z",
                    "source_row_count": 24,
                },
            ],
            "dataset_artifact": {
                "path": str(self.dataset.relative_to(self.root)),
                "sha256": sha256_file(self.dataset),
            },
            "diagnostic_artifact": {
                "path": str(self.diagnostic.relative_to(self.root)),
                "sha256": sha256_file(self.diagnostic),
            },
            "integrity_checks": [
                {"name": "closed_bar_causality", "status": "PASS", "evidence": "UTC close"},
                {"name": "no_gap_or_duplicate", "status": "PASS", "evidence": "contiguous index"},
            ],
            "created_at": "2026-01-03T00:01:00Z",
            "authorization": RESEARCH_AUTHORIZATION,
        }

    def _daily_bundle(self, day: str = "2026-01-01") -> dict[str, object]:
        start = datetime.fromisoformat(day).replace(tzinfo=timezone.utc)
        bars = []
        for hour in range(24):
            interval_start = start + timedelta(hours=hour)
            bars.append(
                {
                    "interval_start": interval_start.isoformat().replace("+00:00", "Z"),
                    "interval_end": (interval_start + timedelta(hours=1)).isoformat().replace(
                        "+00:00", "Z"
                    ),
                    "open": "100",
                    "high": "102",
                    "low": "99",
                    "close": "101",
                    "volume": "10",
                }
            )
        return {
            "schema_version": "1",
            "bundle_type": "FORWARD_EVIDENCE_DAY",
            "trigger_id": self.trigger["trigger_id"],
            "trigger_fingerprint": self.trigger["fingerprint"],
            "source": self.trigger["source"],
            "day": day,
            "bars": bars,
            "source_provenance": {
                "producer": "contract-test",
                "artifact_id": f"source-{day}",
                "sha256": "1" * 64,
            },
            "authorization": RESEARCH_AUTHORIZATION,
        }

    def _register_source(self) -> None:
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        register_evidence_source_contract(
            self.store,
            self.trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": self.trigger["trigger_id"],
                "trigger_fingerprint": self.trigger["fingerprint"],
                "source": self.trigger["source"],
                "producer": "contract-test",
                "transport": "SEALED_ONE_WAY_DROP",
                "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
                "worker_network_access": "DENY",
                "worker_database_access": "DENY",
                "backfill": "DENY",
                "authorization": RESEARCH_AUTHORIZATION,
            },
            registered_at=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )

    def _ready_candidate_bundle(self) -> tuple[dict[str, object], dict[str, object]]:
        path = self.store.evidence_trigger_dir(self.trigger["trigger_id"]) / "evidence-manifest.json"
        path.write_text(json.dumps(self.manifest), encoding="utf-8")
        reviewed_at = datetime.now(timezone.utc) - timedelta(minutes=2)
        review = build_evidence_review(
            {
                "schema_version": "1",
                "trigger_id": self.trigger["trigger_id"],
                "reviewed_at": reviewed_at.isoformat(),
                "outcome": "READY_FOR_HYPOTHESIS",
                "conclusion": "The prospective discovery evidence is ready.",
                "evidence_artifacts": [
                    {
                        "path": str(path.relative_to(self.root)),
                        "sha256": sha256_file(path),
                        "artifact_type": "FORWARD_EVIDENCE_MANIFEST",
                    }
                ],
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        verified = verify_review_artifacts(self.store, self.trigger, review)
        review_path = self.store.evidence_review_dir(self.trigger["trigger_id"]) / "001.json"
        atomic_write_json(review_path, review)
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        state.update(
            {
                "status": "READY_FOR_HYPOTHESIS",
                "next_review_at": None,
                "review_count": 1,
                "reviews": [
                    {
                        "path": str(review_path.relative_to(self.root)),
                        "sha256": sha256_file(review_path),
                        "outcome": "READY_FOR_HYPOTHESIS",
                    }
                ],
                "detail": {
                    "conclusion": review["conclusion"],
                    "verified_evidence": verified,
                },
            }
        )
        self.store.save_evidence_trigger_state(state)
        created_at = (reviewed_at + timedelta(minutes=1)).isoformat()
        policy = load_policy(Path(__file__).parents[1] / "policy.v3.json")
        hypothesis_id = "forward-candidate-test"
        thesis = "One frozen mechanism improves matched-capital economics."
        rationale = "The prospective diagnostic identified one independent opportunity cost."
        parent = "sealed-parent"
        hypothesis = {
            "schema_version": "1",
            "hypothesis_id": hypothesis_id,
            "title": "Forward candidate test",
            "thesis": thesis,
            "mechanism": "One interpretable causal mechanism.",
            "economic_rationale": rationale,
            "source": f"EVIDENCE_TRIGGER:{self.trigger['trigger_id']}",
            "parent": parent,
            "required_capability": "java-dra-v1-parity",
            "data_readiness": "READY",
            "expected_metrics": policy["objective"]["required_metrics"],
            "ranking": {
                "economic_mechanism": 5,
                "interpretability": 5,
                "evidence_readiness": 5,
                "opportunity_cost_reduction": 5,
            },
            "research_cycle_id": "forward-cycle-test",
            "created_at": created_at,
            "authorization": RESEARCH_AUTHORIZATION,
        }
        manifest = {
            "schema_version": "1",
            "experiment_id": hypothesis_id,
            "title": "Forward candidate test",
            "thesis": thesis,
            "economic_rationale": rationale,
            "hypothesis_source": f"HYPOTHESIS:{hypothesis_id}",
            "parent": parent,
            "adapter": "java-dra-v1-parity",
            "created_at": created_at,
            "selection_cutoff": "2025-01-01T00:00:00Z",
            "oos_cutoff": None,
            "max_variants": 1,
            "authorization": RESEARCH_AUTHORIZATION,
            "objective": {
                "primary_metric": policy["objective"]["primary_metric"],
                "constraints": policy["objective"]["constraints"],
            },
        }
        return {
            "schema_version": "1",
            "trigger_id": self.trigger["trigger_id"],
            "hypothesis": hypothesis,
            "manifest": manifest,
            "authorization": RESEARCH_AUTHORIZATION,
        }, policy

    def test_valid_manifest_derives_observation_count(self) -> None:
        summary = validate_evidence_manifest(self.manifest, self.trigger, self.store)
        self.assertEqual(summary["observation_count"], 2)
        self.assertEqual(summary["minimum_observations"], 2)

    def test_manifest_rejects_insufficient_observations(self) -> None:
        value = copy.deepcopy(self.manifest)
        value["observations"] = value["observations"][:1]
        value["coverage_end"] = "2026-01-02T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "does not reach trigger review_not_before"):
            validate_evidence_manifest(value, self.trigger, self.store)

    def test_ready_review_requires_and_verifies_typed_manifest(self) -> None:
        path = self.store.evidence_trigger_dir(self.trigger["trigger_id"]) / "evidence-manifest.json"
        path.write_text(json.dumps(self.manifest), encoding="utf-8")
        review = build_evidence_review(
            {
                "schema_version": "1",
                "trigger_id": self.trigger["trigger_id"],
                "reviewed_at": "2026-01-03T00:02:00Z",
                "outcome": "READY_FOR_HYPOTHESIS",
                "conclusion": "The prospective evidence contract is complete.",
                "evidence_artifacts": [
                    {
                        "path": str(path.relative_to(self.root)),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "artifact_type": "FORWARD_EVIDENCE_MANIFEST",
                    }
                ],
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        verified = verify_review_artifacts(self.store, self.trigger, review)
        self.assertEqual(verified[0]["observation_count"], 2)

    def test_ready_review_rejects_untyped_artifact(self) -> None:
        with self.assertRaisesRegex(ValueError, "exactly one forward evidence manifest"):
            build_evidence_review(
                {
                    "schema_version": "1",
                    "trigger_id": self.trigger["trigger_id"],
                    "reviewed_at": "2026-01-03T00:02:00Z",
                    "outcome": "READY_FOR_HYPOTHESIS",
                    "conclusion": "Unverified.",
                    "evidence_artifacts": [
                        {"path": "anything", "sha256": "0" * 64}
                    ],
                    "authorization": RESEARCH_AUTHORIZATION,
                }
            )

    def test_daily_evidence_seals_in_order_with_hash_chain(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        result = seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle(),
            received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
        )
        self.assertEqual(result["status"], "EVIDENCE_DAY_SEALED")
        self.assertEqual(result["progress"]["observation_count"], 1)
        self.assertEqual(result["progress"]["status"], "AWAITING_DAY_CLOSE")
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        repeated = seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle(),
            received_at=datetime(2026, 1, 10, tzinfo=timezone.utc),
        )
        self.assertEqual(repeated["status"], "EVIDENCE_DAY_ALREADY_SEALED")
        verified = evidence_progress(
            self.store,
            self.trigger,
            state,
            now=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
        )
        self.assertEqual(verified["observation_count"], 1)
        self.assertNotEqual(verified["chain_head"], "0" * 64)

    def test_daily_evidence_rejects_incomplete_or_out_of_order_day(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        incomplete = self._daily_bundle()
        incomplete["bars"] = incomplete["bars"][:-1]
        with self.assertRaisesRegex(ValueError, "exactly 24"):
            seal_daily_evidence(
                self.store,
                self.trigger,
                state,
                incomplete,
                received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
            )
        with self.assertRaisesRegex(ValueError, "next untouched day"):
            seal_daily_evidence(
                self.store,
                self.trigger,
                state,
                self._daily_bundle("2026-01-02"),
                received_at=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
            )

    def test_daily_evidence_rejects_late_backfill(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        with self.assertRaisesRegex(ValueError, "backfill is prohibited"):
            seal_daily_evidence(
                self.store,
                self.trigger,
                state,
                self._daily_bundle(),
                received_at=datetime(2026, 1, 2, 7, tzinfo=timezone.utc),
            )

    def test_progress_fails_closed_after_missed_capture_deadline(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        progress = evidence_progress(
            self.store,
            self.trigger,
            state,
            now=datetime(2026, 1, 2, 7, tzinfo=timezone.utc),
        )
        self.assertEqual(progress["status"], "MISSED_CAPTURE_WINDOW")
        self.assertEqual(progress["lag_observations"], 1)

    def test_progress_exposes_unbound_source_before_evidence_start(self) -> None:
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        progress = evidence_progress(
            self.store,
            self.trigger,
            state,
            now=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )
        self.assertEqual(progress["status"], "SOURCE_UNBOUND")
        self.assertIsNone(progress["source_contract"])

    def test_source_contract_must_precede_start_and_bind_producer(self) -> None:
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        contract = {
            "schema_version": "1",
            "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
            "trigger_id": self.trigger["trigger_id"],
            "trigger_fingerprint": self.trigger["fingerprint"],
            "source": self.trigger["source"],
            "producer": "contract-test",
            "transport": "SEALED_ONE_WAY_DROP",
            "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
            "worker_network_access": "DENY",
            "worker_database_access": "DENY",
            "backfill": "DENY",
            "authorization": RESEARCH_AUTHORIZATION,
        }
        with self.assertRaisesRegex(ValueError, "before evidence_start"):
            register_evidence_source_contract(
                self.store,
                self.trigger,
                state,
                contract,
                registered_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
            )
        register_evidence_source_contract(
            self.store,
            self.trigger,
            state,
            contract,
            registered_at=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )
        bundle = self._daily_bundle()
        bundle["source_provenance"]["producer"] = "different-producer"
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        with self.assertRaisesRegex(ValueError, "producer does not match"):
            seal_daily_evidence(
                self.store,
                self.trigger,
                state,
                bundle,
                received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
            )

    def test_heartbeat_notifies_when_source_is_unbound(self) -> None:
        progress = evidence_progress(
            self.store,
            self.trigger,
            self.store.load_evidence_trigger_state(self.trigger["trigger_id"]),
            now=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )
        tick = {
            "status": "EVIDENCE_SOURCE_UNBOUND",
            "trigger_id": self.trigger["trigger_id"],
            "next_review_at": self.trigger["review_not_before"],
            "evidence_progress": progress,
        }
        heartbeat = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertTrue(heartbeat["should_notify_coach"])
        self.assertEqual(heartbeat["event_type"], "INTEGRITY_ALERT")
        self.assertEqual(heartbeat["research_status"], "EVIDENCE_SOURCE_UNBOUND")

    def test_candidate_bundle_registers_atomically_with_24h_sla(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        result = register_candidate_bundle(
            self.store,
            policy,
            bundle,
            current_policy_hash=policy_sha256(policy_path),
        )
        self.assertEqual(result["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertEqual(result["lead_time_sla"], "PASS")
        self.assertEqual(result["experiment_stage"], "PREREGISTERED")
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        self.assertEqual(state["status"], "CLOSED")
        repeated = register_candidate_bundle(
            self.store,
            policy,
            bundle,
            current_policy_hash=policy_sha256(policy_path),
        )
        self.assertEqual(repeated["status"], "CANDIDATE_BUNDLE_ALREADY_REGISTERED")

    def test_candidate_bundle_rejects_missing_performance_metric(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        bundle["hypothesis"]["expected_metrics"] = ["total_pnl"]
        with self.assertRaisesRegex(ValueError, "policy-required performance metrics"):
            register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash="2" * 64,
            )

    def test_candidate_bundle_reverifies_sealed_evidence_before_registration(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        self.dataset.write_text("mutated after review\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "artifact hash mismatch"):
            register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash="3" * 64,
            )
        hypothesis_path = self.store.hypothesis_path("forward-candidate-test")
        self.assertFalse(hypothesis_path.exists())


if __name__ == "__main__":
    unittest.main()
