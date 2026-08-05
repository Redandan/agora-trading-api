from __future__ import annotations

import copy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from research_pipeline.adapters import ADAPTERS
from research_pipeline.cli import (
    candidate_oos_execution_context,
    close_candidate_oos_trigger,
    close_consumed_candidate_oos_trigger,
    register_candidate_bundle,
    run_tick,
    status_payload,
    verify_review_artifacts,
)
from research_pipeline.evidence import (
    evidence_progress,
    register_evidence_source_contract,
    seal_daily_evidence,
    validate_evidence_manifest,
)
from research_pipeline.forward_candidate import (
    FORWARD_ADAPTER_KEY,
    FORWARD_PARENT,
    diagnostic_contract_status,
    discovery_candidate_context,
)
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.heartbeat import _advance_coach_delivery, run_heartbeat_cycle
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
                "evidence_ready_at": reviewed_at.isoformat(),
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
            "oos_cutoff": "2027-01-01T00:00:00Z",
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

    def _eligible_forward_test_adapter(self):
        spec = ADAPTERS["java-dra-v1-parity"]
        return patch.dict(
            ADAPTERS,
            {
                "java-dra-v1-parity": replace(
                    spec,
                    supports_oos=True,
                    forward_candidate_eligible=True,
                )
            },
        )

    def test_valid_manifest_derives_observation_count(self) -> None:
        summary = validate_evidence_manifest(self.manifest, self.trigger, self.store)
        self.assertEqual(summary["observation_count"], 2)
        self.assertEqual(summary["minimum_observations"], 2)

    def test_complete_day_trigger_rejects_impossible_or_unsupported_review_contract(self) -> None:
        trigger_path = (
            Path(__file__).parents[1]
            / "examples"
            / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.trigger.json"
        )
        value = json.loads(trigger_path.read_text(encoding="utf-8"))
        impossible = copy.deepcopy(value)
        impossible["minimum_observations"] = 91
        with self.assertRaisesRegex(ValueError, "cannot exceed"):
            build_evidence_trigger(impossible)
        unsupported = copy.deepcopy(value)
        unsupported["required_integrity_checks"] = ["human_judgment_required"]
        with self.assertRaisesRegex(ValueError, "unsupported deterministic checks"):
            build_evidence_trigger(unsupported)

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

    def test_review_date_does_not_skip_the_missing_final_capture(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle(),
            received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
        )
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        policy = load_policy(policy_path)

        preview = run_tick(
            self.store,
            policy,
            dry_run=True,
            current_policy_hash=policy_sha256(policy_path),
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )

        self.assertEqual(preview["status"], "WAITING_FOR_EVIDENCE")
        self.assertEqual(preview["evidence_progress"]["status"], "CAPTURE_DUE")
        self.assertEqual(preview["evidence_progress"]["next_observation_day"], "2026-01-02")

    def test_final_day_seals_diagnostic_manifest_review_and_coach_event(self) -> None:
        self._register_source()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle(),
            received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
        )
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        final = seal_daily_evidence(
            self.store,
            self.trigger,
            state,
            self._daily_bundle("2026-01-02"),
            received_at=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )

        self.assertEqual(
            final["review"]["status"], "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS"
        )
        self.assertEqual(final["progress"]["status"], "READY_FOR_HYPOTHESIS")
        ready_state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        self.assertEqual(ready_state["status"], "READY_FOR_HYPOTHESIS")
        self.assertEqual(ready_state["evidence_ready_at"], "2026-01-03T01:00:00Z")
        review_path = self.root / ready_state["reviews"][0]["path"]
        review = json.loads(review_path.read_text(encoding="utf-8"))
        manifest_path = self.root / review["evidence_artifacts"][0]["path"]
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        verified = validate_evidence_manifest(manifest, self.trigger, self.store)
        diagnostic_path = self.root / manifest["diagnostic_artifact"]["path"]
        diagnostic = json.loads(diagnostic_path.read_text(encoding="utf-8"))
        self.assertEqual(verified["observation_count"], 2)
        self.assertFalse(diagnostic["guardrails"]["strategy_performance_evaluated"])
        self.assertFalse(diagnostic["guardrails"]["hypothesis_selected"])
        self.assertNotIn("pnl", json.dumps(diagnostic).lower())

        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        policy = load_policy(policy_path)
        canonical = status_payload(
            self.store,
            policy,
            now=datetime(2026, 1, 3, 2, tzinfo=timezone.utc),
        )
        trigger_status = next(
            item
            for item in canonical["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )
        self.assertEqual(trigger_status["evidence_ready_at"], "2026-01-03T01:00:00Z")
        self.assertEqual(
            trigger_status["candidate_registration_sla"],
            {
                "status": "PENDING_WITHIN_SLA",
                "deadline": "2026-01-04T01:00:00Z",
                "lead_time_seconds": None,
                "seconds_remaining": 82800,
            },
        )
        self.assertEqual(
            trigger_status["diagnostic_summary"], ready_state["detail"]["diagnostic_summary"]
        )
        self.assertEqual(
            canonical["forward_candidate_readiness"]["status"],
            "FORWARD_CANDIDATE_ADAPTER_BLOCKED",
        )
        self.assertEqual(
            canonical["forward_candidate_readiness"]["historical_selection_corpus"]["status"],
            "MISSING",
        )
        tick = run_tick(
            self.store,
            policy,
            dry_run=True,
            current_policy_hash=policy_sha256(policy_path),
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )
        heartbeat = run_heartbeat_cycle(
            self.store,
            policy,
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertEqual(tick["status"], "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS")
        self.assertEqual(heartbeat["event_type"], "MATERIAL_LEARNING")
        self.assertTrue(heartbeat["should_notify_coach"])
        self.assertEqual(
            heartbeat["evidence_diagnostic"], ready_state["detail"]["diagnostic_summary"]
        )

    def test_due_complete_window_is_reviewed_by_the_existing_tick_operation(self) -> None:
        trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "delayed-deterministic-review-test",
                "title": "Delayed deterministic review test",
                "rationale": "Prove the existing heartbeat operation can finish a due review.",
                "source": "sealed test source",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": ["closed_bar_causality"],
                "prohibited_inferences": ["no performance selection"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        isolated_root = self.root / "delayed-state"
        store = ResearchStore(isolated_root, lock_stale_seconds=60)
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": trigger["trigger_id"],
                "trigger_fingerprint": trigger["fingerprint"],
                "source": trigger["source"],
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
        bundle = self._daily_bundle()
        bundle["trigger_id"] = trigger["trigger_id"]
        bundle["trigger_fingerprint"] = trigger["fingerprint"]
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        seal_daily_evidence(
            store,
            trigger,
            state,
            bundle,
            received_at=datetime(2026, 1, 2, 1, tzinfo=timezone.utc),
        )
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        second = self._daily_bundle("2026-01-02")
        second["trigger_id"] = trigger["trigger_id"]
        second["trigger_fingerprint"] = trigger["fingerprint"]
        seal_daily_evidence(
            store,
            trigger,
            state,
            second,
            received_at=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )
        legacy_complete_state = store.load_evidence_trigger_state(trigger["trigger_id"])
        legacy_complete_state["status"] = "WAITING"
        legacy_complete_state["next_review_at"] = trigger["review_not_before"]
        legacy_complete_state["review_count"] = 0
        legacy_complete_state["reviews"] = []
        legacy_complete_state["detail"] = None
        legacy_complete_state.pop("evidence_ready_at", None)
        store.save_evidence_trigger_state(legacy_complete_state)
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        policy = load_policy(policy_path)
        current_hash = policy_sha256(policy_path)
        preview = run_tick(
            store,
            policy,
            dry_run=True,
            current_policy_hash=current_hash,
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )
        result = run_tick(
            store,
            policy,
            dry_run=False,
            current_policy_hash=current_hash,
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
        )

        self.assertEqual(preview["status"], "DRY_RUN")
        self.assertEqual(preview["action"], "BUILD_DETERMINISTIC_FORWARD_EVIDENCE_REVIEW")
        self.assertEqual(result["status"], "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS")
        self.assertEqual(
            store.load_evidence_trigger_state(trigger["trigger_id"])["status"],
            "READY_FOR_HYPOTHESIS",
        )

    def test_ninety_day_window_closes_when_no_frozen_mechanism_passes(self) -> None:
        trigger_path = (
            Path(__file__).parents[1]
            / "examples"
            / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.trigger.json"
        )
        value = json.loads(trigger_path.read_text(encoding="utf-8"))
        value.update(
            {
                "trigger_id": "prospective-mechanism-neutral-evidence-90-day-test",
                "title": "Prospective mechanism-neutral evidence 90-day test",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-04-01T00:00:00Z",
                "created_at": "2025-12-31T00:00:00Z",
            }
        )
        trigger = build_evidence_trigger(value)
        store = ResearchStore(self.root / "ninety-day-state", lock_stale_seconds=60)
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": trigger["trigger_id"],
                "trigger_fingerprint": trigger["fingerprint"],
                "source": trigger["source"],
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
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        final = None
        for offset in range(90):
            day_start = start + timedelta(days=offset)
            bundle = self._daily_bundle(day_start.date().isoformat())
            bundle["trigger_id"] = trigger["trigger_id"]
            bundle["trigger_fingerprint"] = trigger["fingerprint"]
            bundle["source"] = trigger["source"]
            state = store.load_evidence_trigger_state(trigger["trigger_id"])
            final = seal_daily_evidence(
                store,
                trigger,
                state,
                bundle,
                received_at=day_start + timedelta(days=1, hours=1),
            )
            if offset < 89:
                self.assertIsNone(final["review"])

        self.assertIsNotNone(final)
        self.assertEqual(final["progress"]["status"], "CLOSED")
        self.assertEqual(final["review"]["status"], "NO_CANDIDATE_FORWARD_DIAGNOSTIC")
        self.assertIsNone(final["review"]["evidence_ready_at"])
        ready_state = store.load_evidence_trigger_state(trigger["trigger_id"])
        self.assertEqual(ready_state["status"], "CLOSED")
        review = json.loads(
            (store.root / ready_state["reviews"][0]["path"]).read_text(encoding="utf-8")
        )
        manifest = json.loads(
            (store.root / review["evidence_artifacts"][0]["path"]).read_text(
                encoding="utf-8"
            )
        )
        dataset = json.loads(
            (store.root / manifest["dataset_artifact"]["path"]).read_text(encoding="utf-8")
        )
        self.assertEqual(dataset["observation_count"], 90)
        self.assertEqual(dataset["source_row_count"], 2160)
        diagnostic = json.loads(
            (store.root / manifest["diagnostic_artifact"]["path"]).read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(diagnostic["eligible_mechanisms"], [])
        self.assertEqual(
            {item["name"] for item in manifest["integrity_checks"]},
            set(trigger["required_integrity_checks"]),
        )

        tick = {"status": "IDLE_NO_ACTIONABLE_EXPERIMENT"}
        first_heartbeat = run_heartbeat_cycle(
            store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2026, 4, 2, 1, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertEqual(
            first_heartbeat["research_status"], "IDLE_NO_ACTIONABLE_EXPERIMENT"
        )
        no_candidate_events = [
            event
            for event in first_heartbeat["events"]
            if event["research_status"] == "NO_CANDIDATE_FORWARD_DIAGNOSTIC"
        ]
        self.assertEqual(len(no_candidate_events), 1)
        self.assertEqual(no_candidate_events[0]["sha256"], ready_state["reviews"][0]["sha256"])

        second_heartbeat = run_heartbeat_cycle(
            store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2026, 4, 3, 1, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertFalse(
            any(
                event["research_status"] == "NO_CANDIDATE_FORWARD_DIAGNOSTIC"
                for event in second_heartbeat["events"]
            )
        )

    def test_ninety_day_ready_review_registers_one_forward_candidate_end_to_end(
        self,
    ) -> None:
        trigger_path = (
            Path(__file__).parents[1]
            / "examples"
            / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.trigger.json"
        )
        value = json.loads(trigger_path.read_text(encoding="utf-8"))
        value.update(
            {
                "trigger_id": "prospective-mechanism-neutral-evidence-ready-e2e",
                "title": "Prospective evidence-ready candidate end-to-end",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-04-01T00:00:00Z",
                "created_at": "2025-12-31T00:00:00Z",
            }
        )
        trigger = build_evidence_trigger(value)
        store = ResearchStore(self.root / "ready-candidate-e2e", lock_stale_seconds=60)
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": trigger["trigger_id"],
                "trigger_fingerprint": trigger["fingerprint"],
                "source": trigger["source"],
                "producer": "agora-okx-forward-source-v1",
                "transport": "SEALED_ONE_WAY_DROP_V1",
                "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
                "worker_network_access": "DENY",
                "worker_database_access": "DENY",
                "backfill": "DENY",
                "authorization": RESEARCH_AUTHORIZATION,
            },
            registered_at=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )

        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        close = Decimal("100")
        final = None
        for offset in range(90):
            prior_event = (
                offset > 0 and (offset - 1) >= 20 and (offset - 1) % 5 == 0
            )
            close *= Decimal("1.01") if prior_event else Decimal("0.999")
            current_event = offset >= 20 and offset % 5 == 0
            day_start = start + timedelta(days=offset)
            bundle = self._daily_bundle(day_start.date().isoformat())
            bundle["trigger_id"] = trigger["trigger_id"]
            bundle["trigger_fingerprint"] = trigger["fingerprint"]
            bundle["source"] = trigger["source"]
            for index, bar in enumerate(bundle["bars"]):
                daily_volume = Decimal("200") if current_event else Decimal("100")
                bar.update(
                    {
                        "open": str(close),
                        "high": str(close * Decimal("1.01")),
                        "low": str(close * Decimal("0.99")),
                        "close": str(close),
                        "volume": str(daily_volume if index == 0 else Decimal("0")),
                    }
                )
            bundle["source_provenance"] = {
                "producer": "agora-okx-forward-source-v1",
                "artifact_id": f"e2e-source-{day_start.date().isoformat()}",
                "sha256": hashlib.sha256(
                    f"e2e-source-{day_start.date().isoformat()}".encode("utf-8")
                ).hexdigest(),
            }
            state = store.load_evidence_trigger_state(trigger["trigger_id"])
            final = seal_daily_evidence(
                store,
                trigger,
                state,
                bundle,
                received_at=day_start + timedelta(days=1, hours=1),
            )

        self.assertIsNotNone(final)
        self.assertEqual(final["progress"]["status"], "READY_FOR_HYPOTHESIS")
        self.assertEqual(
            final["review"]["status"],
            "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS",
        )
        ready_state = store.load_evidence_trigger_state(trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(ready_state["evidence_ready_at"]))
        current = ready_at + timedelta(minutes=2)
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        policy = load_policy(policy_path)
        tick = run_tick(
            store,
            policy,
            dry_run=True,
            current_policy_hash=policy_sha256(policy_path),
            now=current,
        )
        heartbeat = run_heartbeat_cycle(
            store,
            policy,
            now=current,
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertEqual(tick["status"], "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS")
        self.assertTrue(heartbeat["should_notify_coach"])
        self.assertEqual(heartbeat["event_type"], "MATERIAL_LEARNING")
        self.assertEqual(
            heartbeat["evidence_diagnostic"]["eligible_mechanisms"][0][
                "mechanism_key"
            ],
            "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
        )
        context = discovery_candidate_context(
            store,
            trigger,
            ready_state,
            now=current,
        )
        self.assertEqual(context["status"], "READY")
        self.assertEqual(len(context["eligible_mechanisms"]), 1)
        mechanism = context["eligible_mechanisms"][0]
        self.assertEqual(
            mechanism["mechanism_key"],
            "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
        )
        self.assertTrue(mechanism["all_predictive_gates_pass"])

        hypothesis_id = "forward-ready-e2e-candidate"
        created_at = (ready_at + timedelta(minutes=1)).isoformat()
        thesis = "Prospective volume confirmation improves DRA entry admission."
        rationale = "The frozen forward diagnostic found independent response breadth."
        hypothesis = {
            "schema_version": "1",
            "hypothesis_id": hypothesis_id,
            "title": "Forward ready end-to-end candidate",
            "thesis": thesis,
            "mechanism": "Require the single forward-supported volume confirmation mechanism.",
            "economic_rationale": rationale,
            "source": f"EVIDENCE_TRIGGER:{trigger['trigger_id']}",
            "parent": context["parent"],
            "required_capability": context["adapter"],
            "data_readiness": "READY",
            "expected_metrics": policy["objective"]["required_metrics"],
            "ranking": {
                "economic_mechanism": 5,
                "interpretability": 5,
                "evidence_readiness": 5,
                "opportunity_cost_reduction": 5,
            },
            "research_cycle_id": "forward-ready-e2e-cycle",
            "created_at": created_at,
            "authorization": RESEARCH_AUTHORIZATION,
        }
        adapter_config = copy.deepcopy(context["adapter_config_template"])
        adapter_config["mechanism_key"] = mechanism["mechanism_key"]
        manifest = {
            "schema_version": "1",
            "experiment_id": hypothesis_id,
            "title": "Forward ready end-to-end candidate",
            "thesis": thesis,
            "economic_rationale": rationale,
            "hypothesis_source": f"HYPOTHESIS:{hypothesis_id}",
            "parent": context["parent"],
            "adapter": context["adapter"],
            "created_at": created_at,
            "selection_cutoff": context["selection_cutoff"],
            "oos_cutoff": context["oos_window"]["end_at"],
            "max_variants": context["max_variants"],
            "adapter_config": adapter_config,
            "authorization": RESEARCH_AUTHORIZATION,
            "objective": {
                "primary_metric": policy["objective"]["primary_metric"],
                "constraints": policy["objective"]["constraints"],
            },
        }
        bundle = {
            "schema_version": "1",
            "trigger_id": trigger["trigger_id"],
            "hypothesis": hypothesis,
            "manifest": manifest,
            "authorization": RESEARCH_AUTHORIZATION,
        }
        with patch(
            "research_pipeline.cli.selection_corpus_status",
            return_value={"status": "READY"},
        ):
            registered = register_candidate_bundle(
                store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=current,
            )
            repeated = register_candidate_bundle(
                store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=current,
            )

        self.assertEqual(registered["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertEqual(registered["lead_time_sla"], "PASS")
        self.assertEqual(registered["lead_time_seconds"], 120)
        self.assertEqual(registered["experiment_stage"], "PREREGISTERED")
        self.assertEqual(repeated["status"], "CANDIDATE_BUNDLE_ALREADY_REGISTERED")
        self.assertEqual(
            store.load_evidence_trigger_state(trigger["trigger_id"])["status"],
            "CLOSED",
        )
        experiment_state = store.load_state(hypothesis_id)
        self.assertEqual(experiment_state["stage"], "PREREGISTERED")
        self.assertIsNone(experiment_state.get("outcome"))
        oos_id = experiment_state["oos_evidence_trigger_id"]
        oos_trigger = store.load_evidence_trigger(oos_id)
        oos_state = store.load_evidence_trigger_state(oos_id)
        self.assertEqual(oos_trigger["purpose"], "CANDIDATE_OOS")
        self.assertEqual(oos_state["status"], "WAITING")
        self.assertIsNotNone(oos_state["evidence_source_contract"])

    def test_candidate_oos_window_seals_without_exposing_market_path(self) -> None:
        store = ResearchStore(self.root / "candidate-oos-state", lock_stale_seconds=60)
        store.bootstrap()
        experiment_id = "candidate-oos-test"
        experiment_dir = store.experiment_dir(experiment_id)
        experiment_dir.mkdir(parents=True)
        (experiment_dir / "artifacts").mkdir()
        manifest = {
            "schema_version": "1",
            "experiment_id": experiment_id,
            "adapter": "dra-forward-entry-admission-v1",
            "adapter_config": {
                "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
            },
        }
        manifest_path = experiment_dir / "manifest.json"
        atomic_write_json(manifest_path, manifest)
        trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "candidate-oos-trigger-test",
                "title": "Candidate OOS trigger test",
                "rationale": "Prove OOS stays sealed until its complete window is ready.",
                "source": "sealed test source",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": [
                    "closed_bar_causality",
                    "candidate_manifest_frozen_before_oos_start",
                ],
                "prohibited_inferences": ["do not open candidate performance early"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
                "purpose": "CANDIDATE_OOS",
                "candidate_binding": {
                    "experiment_id": experiment_id,
                    "manifest_sha256": sha256_file(manifest_path),
                    "adapter": "dra-forward-entry-admission-v1",
                    "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
                },
            }
        )
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": trigger["trigger_id"],
                "trigger_fingerprint": trigger["fingerprint"],
                "source": trigger["source"],
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
        final = None
        for offset in range(2):
            day_start = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(
                days=offset
            )
            bundle = self._daily_bundle(day_start.date().isoformat())
            bundle["trigger_id"] = trigger["trigger_id"]
            bundle["trigger_fingerprint"] = trigger["fingerprint"]
            bundle["source"] = trigger["source"]
            state = store.load_evidence_trigger_state(trigger["trigger_id"])
            final = seal_daily_evidence(
                store,
                trigger,
                state,
                bundle,
                received_at=day_start + timedelta(days=1, hours=1),
            )

        self.assertEqual(final["progress"]["status"], "READY_FOR_OOS")
        self.assertEqual(final["review"]["status"], "CANDIDATE_OOS_READY")
        oos_state = store.load_evidence_trigger_state(trigger["trigger_id"])
        review = json.loads(
            (store.root / oos_state["reviews"][0]["path"]).read_text(encoding="utf-8")
        )
        evidence_manifest = json.loads(
            (store.root / review["evidence_artifacts"][0]["path"]).read_text(
                encoding="utf-8"
            )
        )
        diagnostic = json.loads(
            (store.root / evidence_manifest["diagnostic_artifact"]["path"]).read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(diagnostic["diagnostic_type"], "CANDIDATE_OOS_SEAL_ONLY")
        self.assertFalse(diagnostic["guardrails"]["market_path_summary_exposed"])
        self.assertNotIn("summary", diagnostic)
        close_candidate_oos_trigger(
            store,
            {
                "experiment_id": experiment_id,
                "oos_evidence_trigger_id": trigger["trigger_id"],
            },
            reason="PRESELECTION_FAILED_AFTER_OOS_CAPTURE",
        )
        closed_unopened = store.load_evidence_trigger_state(trigger["trigger_id"])
        self.assertEqual(closed_unopened["status"], "CLOSED")
        self.assertEqual(
            closed_unopened["detail"]["closed_reason"],
            "PRESELECTION_FAILED_AFTER_OOS_CAPTURE",
        )

    def test_candidate_oos_complete_window_recovers_delayed_review(self) -> None:
        store = ResearchStore(self.root / "candidate-oos-recovery", lock_stale_seconds=60)
        store.bootstrap()
        experiment_id = "candidate-oos-recovery-test"
        experiment_dir = store.experiment_dir(experiment_id)
        experiment_dir.mkdir(parents=True)
        (experiment_dir / "artifacts").mkdir()
        manifest = {
            "schema_version": "1",
            "experiment_id": experiment_id,
            "adapter": "dra-forward-entry-admission-v1",
            "adapter_config": {
                "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
            },
            "authorization": RESEARCH_AUTHORIZATION,
        }
        manifest_path = experiment_dir / "manifest.json"
        atomic_write_json(manifest_path, manifest)
        manifest_sha256 = sha256_file(manifest_path)
        trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "candidate-oos-recovery-trigger",
                "title": "Candidate OOS recovery trigger",
                "rationale": "Recover a complete OOS window after review interruption.",
                "source": "sealed test source",
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": [
                    "closed_bar_causality",
                    "candidate_manifest_frozen_before_oos_start",
                ],
                "prohibited_inferences": ["do not open candidate performance early"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
                "purpose": "CANDIDATE_OOS",
                "candidate_binding": {
                    "experiment_id": experiment_id,
                    "manifest_sha256": manifest_sha256,
                    "adapter": "dra-forward-entry-admission-v1",
                    "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
                },
            }
        )
        store.register_evidence_trigger(trigger)
        state = store.load_evidence_trigger_state(trigger["trigger_id"])
        register_evidence_source_contract(
            store,
            trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": trigger["trigger_id"],
                "trigger_fingerprint": trigger["fingerprint"],
                "source": trigger["source"],
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
        for offset in range(2):
            day_start = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(
                days=offset
            )
            bundle = self._daily_bundle(day_start.date().isoformat())
            bundle["trigger_id"] = trigger["trigger_id"]
            bundle["trigger_fingerprint"] = trigger["fingerprint"]
            bundle["source"] = trigger["source"]
            state = store.load_evidence_trigger_state(trigger["trigger_id"])
            if offset == 1:
                with patch(
                    "research_pipeline.evidence._finalize_review_if_due",
                    side_effect=lambda *args, progress, **kwargs: (progress, None),
                ):
                    final = seal_daily_evidence(
                        store,
                        trigger,
                        state,
                        bundle,
                        received_at=day_start + timedelta(days=1, hours=1),
                    )
            else:
                seal_daily_evidence(
                    store,
                    trigger,
                    state,
                    bundle,
                    received_at=day_start + timedelta(days=1, hours=1),
                )
        self.assertEqual(final["progress"]["status"], "COMPLETE")
        experiment_state = {
            "experiment_id": experiment_id,
            "manifest_sha256": manifest_sha256,
            "oos_evidence_trigger_id": trigger["trigger_id"],
        }
        preview = candidate_oos_execution_context(
            store,
            manifest,
            experiment_state,
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
            capture_max_lag_seconds=21600,
            dry_run=True,
        )
        self.assertEqual(preview["status"], "DRY_RUN")
        self.assertEqual(
            preview["action"],
            "BUILD_DETERMINISTIC_CANDIDATE_OOS_REVIEW_THEN_RUN_OOS",
        )
        recovered = candidate_oos_execution_context(
            store,
            manifest,
            experiment_state,
            now=datetime(2026, 1, 3, 1, tzinfo=timezone.utc),
            capture_max_lag_seconds=21600,
            dry_run=False,
        )
        self.assertEqual(recovered["status"], "READY_FOR_OOS_EXECUTION")
        self.assertTrue(Path(recovered["dataset_path"]).is_file())
        self.assertEqual(
            store.load_evidence_trigger_state(trigger["trigger_id"])["status"],
            "READY_FOR_OOS",
        )
        close_consumed_candidate_oos_trigger(
            store,
            experiment_state,
            outcome="OUT_OF_SAMPLE_FAIL",
        )
        consumed = store.load_evidence_trigger_state(trigger["trigger_id"])
        self.assertEqual(consumed["status"], "CLOSED")
        self.assertEqual(consumed["detail"]["oos_outcome"], "OUT_OF_SAMPLE_FAIL")

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

        delivery_id = heartbeat["sha256"]
        state_path = self.store.root / heartbeat["heartbeat_state"]
        state = json.loads(state_path.read_text(encoding="utf-8"))
        self.assertTrue(
            {"WEEKLY_BRIEF_READY", "MONTHLY_REVIEW_READY"}.issubset(
                {
                    event["event_type"]
                    for event in state["coach_delivery"]["pending_events"]
                }
            )
        )
        self.assertIn(
            delivery_id,
            [event["sha256"] for event in state["coach_delivery"]["pending_events"]],
        )
        pending_event = next(
            event
            for event in state["coach_delivery"]["pending_events"]
            if event["sha256"] == delivery_id
        )
        self.assertEqual(pending_event["delivery_queued_at"], "2025-12-31T12:00:00Z")
        self.assertEqual(
            pending_event["delivery_deadline_at"], "2026-01-01T04:00:00Z"
        )

        routine = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 12, 31, 13, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        self.assertFalse(routine["should_notify_coach"])
        state = json.loads(state_path.read_text(encoding="utf-8"))
        self.assertIn(
            delivery_id,
            [event["sha256"] for event in state["coach_delivery"]["pending_events"]],
        )

        receipt = {
            "schema_version": "1",
            "delivery_id": delivery_id,
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{delivery_id}",
            "target_thread_id": "019fca63-4f8f-71e3-9d88-297bca468eb9",
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
        }
        acknowledged = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 12, 31, 14, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
            coach_delivery_receipts=[receipt],
        )
        self.assertIn(delivery_id, acknowledged["coach_delivery"]["acknowledged_delivery_ids"])
        state = json.loads(state_path.read_text(encoding="utf-8"))
        self.assertNotIn(
            delivery_id,
            [event["sha256"] for event in state["coach_delivery"]["pending_events"]],
        )
        delivered = next(
            item
            for item in state["coach_delivery"]["delivered_receipts"]
            if item["delivery_id"] == delivery_id
        )
        self.assertEqual(delivered["delivery_proof_sla"], "PASS")
        self.assertEqual(delivered["delivery_proof_lead_time_seconds"], 7200)
        self.assertEqual(delivered["delivery_queued_at"], "2025-12-31T12:00:00Z")
        self.assertEqual(
            delivered["delivery_deadline_at"], "2026-01-01T04:00:00Z"
        )

        repeated = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 12, 31, 15, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
            coach_delivery_receipts=[receipt],
        )
        self.assertIn(delivery_id, repeated["coach_delivery"]["acknowledged_delivery_ids"])

        with self.assertRaisesRegex(ValueError, "does not match"):
            run_heartbeat_cycle(
                self.store,
                {"policy_id": "TEST_RESEARCH_ONLY"},
                now=datetime(2025, 12, 31, 16, tzinfo=timezone.utc),
                tick_preview=tick,
                tick_result=tick,
                coach_delivery_receipts=[
                    {
                        **receipt,
                        "delivery_id": "f" * 64,
                        "delivery_token": f"SEALED_RESEARCH_DELIVERY:{'f' * 64}",
                    }
                ],
            )

    def test_heartbeat_recovers_sealed_reports_after_state_commit_interruption(self) -> None:
        tick = {"status": "IDLE_NO_ACTIONABLE_EXPERIMENT"}
        initial = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 11, 24, 1, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )

        with patch(
            "research_pipeline.heartbeat._write_state",
            side_effect=RuntimeError("simulated heartbeat state commit interruption"),
        ):
            with self.assertRaisesRegex(RuntimeError, "state commit interruption"):
                run_heartbeat_cycle(
                    self.store,
                    {"policy_id": "TEST_RESEARCH_ONLY"},
                    now=datetime(2025, 12, 1, 1, tzinfo=timezone.utc),
                    tick_preview=tick,
                    tick_result=tick,
                )

        recovered = run_heartbeat_cycle(
            self.store,
            {"policy_id": "TEST_RESEARCH_ONLY"},
            now=datetime(2025, 12, 2, 1, tzinfo=timezone.utc),
            tick_preview=tick,
            tick_result=tick,
        )
        recovered_types = {event["event_type"] for event in recovered["events"]}
        self.assertEqual(
            recovered_types,
            {"MONTHLY_REVIEW_READY", "WEEKLY_BRIEF_READY"},
        )
        self.assertTrue(recovered["should_notify_coach"])
        reports = self.store.root / "reports"
        self.assertEqual(
            len(list(reports.glob("monthly-learning-review-2025-12-01.md"))),
            1,
        )
        self.assertEqual(
            len(list(reports.glob("weekly-learning-brief-2025-12-01.md"))),
            1,
        )
        state = json.loads(
            (self.store.root / recovered["heartbeat_state"]).read_text(
                encoding="utf-8"
            )
        )
        recovered_hashes = {event["sha256"] for event in recovered["events"]}
        initial_report_hashes = {
            event["sha256"]
            for event in initial["events"]
            if event["event_type"] in {"MONTHLY_REVIEW_READY", "WEEKLY_BRIEF_READY"}
        }
        self.assertTrue(recovered_hashes.isdisjoint(initial_report_hashes))
        self.assertIn(
            "- Report period: `2025-12`",
            (reports / "monthly-learning-review-2025-12-01.md").read_text(
                encoding="utf-8"
            ),
        )
        self.assertIn(
            "- Report period: `2025-W49`",
            (reports / "weekly-learning-brief-2025-12-01.md").read_text(
                encoding="utf-8"
            ),
        )
        pending = [
            event
            for event in state["coach_delivery"]["pending_events"]
            if event["sha256"] in recovered_hashes
        ]
        self.assertEqual(len(pending), 2)
        self.assertTrue(
            all(
                event["delivery_queued_at"] == "2025-12-02T01:00:00Z"
                for event in pending
            )
        )

    def test_coach_delivery_proof_sla_breach_and_legacy_are_preserved(self) -> None:
        target = "019fca63-4f8f-71e3-9d88-297bca468eb9"
        delivery_id = "a" * 64
        receipt = {
            "schema_version": "1",
            "delivery_id": delivery_id,
            "delivery_token": f"SEALED_RESEARCH_DELIVERY:{delivery_id}",
            "target_thread_id": target,
            "delivery_status": "DELIVERED_TO_COACH_TASK_VERIFIED",
        }
        state: dict[str, object] = {}
        _advance_coach_delivery(
            state,
            receipts=[],
            new_events=[{"sha256": delivery_id}],
            now=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )
        _advance_coach_delivery(
            state,
            receipts=[receipt],
            new_events=[],
            now=datetime(2026, 1, 1, 4, 0, 1, tzinfo=timezone.utc),
        )
        breached = state["coach_delivery"]["delivered_receipts"][0]
        self.assertEqual(breached["delivery_proof_sla"], "BREACH")
        self.assertEqual(breached["delivery_proof_lead_time_seconds"], 57601)
        tampered_receipt_state = copy.deepcopy(state)
        tampered_receipt_state["coach_delivery"]["delivered_receipts"][0][
            "delivery_proof_lead_time_seconds"
        ] += 1
        with self.assertRaisesRegex(ValueError, "proof SLA"):
            _advance_coach_delivery(
                tampered_receipt_state,
                receipts=[],
                new_events=[],
                now=datetime(2026, 1, 1, 2, tzinfo=timezone.utc),
            )

        pending_state: dict[str, object] = {}
        _advance_coach_delivery(
            pending_state,
            receipts=[],
            new_events=[{"sha256": "c" * 64}],
            now=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )
        pending_state["coach_delivery"]["pending_events"][0][
            "delivery_deadline_at"
        ] = "2026-01-01T02:00:00Z"
        with self.assertRaisesRegex(ValueError, "next cloud cycle"):
            _advance_coach_delivery(
                pending_state,
                receipts=[],
                new_events=[],
                now=datetime(2025, 12, 31, 13, tzinfo=timezone.utc),
            )

        legacy_id = "b" * 64
        legacy_state = {
            "coach_delivery": {
                "schema_version": "1",
                "pending_events": [{"sha256": legacy_id}],
                "delivered_receipts": [],
            }
        }
        _advance_coach_delivery(
            legacy_state,
            receipts=[
                {
                    **receipt,
                    "delivery_id": legacy_id,
                    "delivery_token": f"SEALED_RESEARCH_DELIVERY:{legacy_id}",
                }
            ],
            new_events=[],
            now=datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
        )
        legacy = legacy_state["coach_delivery"]["delivered_receipts"][0]
        self.assertEqual(
            legacy["delivery_proof_sla"], "MISSING_PROOF_LEGACY_EVENT"
        )
        self.assertIsNone(legacy["delivery_proof_lead_time_seconds"])

    def test_candidate_bundle_registers_atomically_with_24h_sla(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))
        with self._eligible_forward_test_adapter():
            result = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=ready_at + timedelta(hours=24),
            )
        self.assertEqual(result["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertEqual(result["lead_time_sla"], "PASS")
        self.assertEqual(result["lead_time_seconds"], 86400)
        self.assertEqual(result["experiment_stage"], "PREREGISTERED")
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        self.assertEqual(state["status"], "CLOSED")
        canonical = status_payload(
            self.store,
            policy,
            now=ready_at + timedelta(days=2),
        )
        trigger_status = next(
            item
            for item in canonical["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )
        self.assertEqual(trigger_status["candidate_registration_sla"]["status"], "PASS")
        with self._eligible_forward_test_adapter():
            repeated = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
            )
        self.assertEqual(repeated["status"], "CANDIDATE_BUNDLE_ALREADY_REGISTERED")
        changed = copy.deepcopy(bundle)
        changed["hypothesis"]["title"] = "Changed after registration"
        with self._eligible_forward_test_adapter():
            with self.assertRaisesRegex(ValueError, "bundle content changed"):
                register_candidate_bundle(
                    self.store,
                    policy,
                    changed,
                    current_policy_hash=policy_sha256(policy_path),
                )

    def test_candidate_registration_recovers_finalize_interruption_without_duplicate_audit(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))
        original_save = self.store.save_evidence_trigger_state

        def interrupt_closed_state(value: dict[str, object]) -> None:
            if (
                value.get("trigger_id") == self.trigger["trigger_id"]
                and value.get("status") == "CLOSED"
            ):
                raise RuntimeError("simulated candidate finalize interruption")
            original_save(value)

        with self._eligible_forward_test_adapter():
            with patch.object(
                self.store,
                "save_evidence_trigger_state",
                side_effect=interrupt_closed_state,
            ):
                with self.assertRaisesRegex(RuntimeError, "finalize interruption"):
                    register_candidate_bundle(
                        self.store,
                        policy,
                        bundle,
                        current_policy_hash=policy_sha256(policy_path),
                        now=ready_at + timedelta(minutes=2),
                    )

            interrupted = self.store.load_evidence_trigger_state(
                self.trigger["trigger_id"]
            )
            self.assertEqual(interrupted["status"], "READY_FOR_HYPOTHESIS")
            recovered = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=ready_at + timedelta(minutes=3),
            )
            repeated = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=ready_at + timedelta(minutes=4),
            )

        self.assertEqual(recovered["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertEqual(repeated["status"], "CANDIDATE_BUNDLE_ALREADY_REGISTERED")
        self.assertEqual(recovered["lead_time_seconds"], 120)
        self.assertEqual(repeated["lead_time_seconds"], 120)
        event_path = self.store.evidence_trigger_dir(
            self.trigger["trigger_id"]
        ) / "events.jsonl"
        events = [
            json.loads(line)
            for line in event_path.read_text(encoding="utf-8").splitlines()
        ]
        registrations = [
            event
            for event in events
            if event["event_type"] == "CANDIDATE_BUNDLE_REGISTERED"
        ]
        self.assertEqual(len(registrations), 1)
        self.assertEqual(
            registrations[0]["detail"]["candidate_bundle_sha256"],
            recovered["candidate_bundle_sha256"],
        )
        self.assertEqual(registrations[0]["detail"]["lead_time_seconds"], 120)

    def test_candidate_registration_status_breaches_immediately_after_deadline(self) -> None:
        _bundle, policy = self._ready_candidate_bundle()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))

        at_deadline = status_payload(
            self.store,
            policy,
            now=ready_at + timedelta(hours=24),
        )
        just_late = status_payload(
            self.store,
            policy,
            now=ready_at + timedelta(hours=24, microseconds=500_000),
        )

        exact = next(
            item
            for item in at_deadline["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )["candidate_registration_sla"]
        late = next(
            item
            for item in just_late["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )["candidate_registration_sla"]
        self.assertEqual(exact["status"], "PENDING_WITHIN_SLA")
        self.assertEqual(exact["seconds_remaining"], 0)
        self.assertEqual(late["status"], "BREACH_PENDING_REGISTRATION")
        self.assertEqual(late["seconds_remaining"], -1)

    def test_candidate_bundle_rejects_readiness_timestamp_drift(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        sealed_ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))
        state["evidence_ready_at"] = (
            sealed_ready_at - timedelta(seconds=1)
        ).isoformat()
        self.store.save_evidence_trigger_state(state)

        canonical = status_payload(
            self.store,
            policy,
            now=sealed_ready_at + timedelta(minutes=2),
        )
        trigger_status = next(
            item
            for item in canonical["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )
        self.assertEqual(
            trigger_status["candidate_registration_sla"]["status"],
            "INTEGRITY_BLOCKED",
        )
        self.assertEqual(
            trigger_status["candidate_registration_sla"]["reason"],
            "READY_TIMESTAMP_MISMATCH",
        )
        self.assertIsNone(trigger_status["candidate_context"])

        with self._eligible_forward_test_adapter():
            with self.assertRaisesRegex(
                ValueError,
                "evidence_ready_at does not match the sealed review",
            ):
                register_candidate_bundle(
                    self.store,
                    policy,
                    bundle,
                    current_policy_hash=policy_sha256(policy_path),
                    now=sealed_ready_at + timedelta(minutes=2),
                )

        self.assertFalse(
            self.store.experiment_dir(bundle["manifest"]["experiment_id"]).exists()
        )
        self.assertEqual(self.store.hypothesis_entries(), [])

    def test_candidate_registration_status_blocks_tampered_ready_review(self) -> None:
        _bundle, policy = self._ready_candidate_bundle()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        review_path = self.root / state["reviews"][-1]["path"]
        review_path.write_text("tampered\n", encoding="utf-8")

        canonical = status_payload(
            self.store,
            policy,
            now=datetime.now(timezone.utc),
        )
        trigger_status = next(
            item
            for item in canonical["evidence_triggers"]
            if item["trigger_id"] == self.trigger["trigger_id"]
        )
        self.assertEqual(
            trigger_status["candidate_registration_sla"],
            {
                "status": "INTEGRITY_BLOCKED",
                "reason": "SEALED_READY_REVIEW_HASH_MISMATCH",
                "deadline": (
                    datetime.fromisoformat(str(state["evidence_ready_at"]))
                    + timedelta(hours=24)
                ).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "lead_time_seconds": None,
                "seconds_remaining": None,
            },
        )
        self.assertIsNone(trigger_status["candidate_context"])

    def test_supported_forward_candidate_registers_with_separate_sealed_oos(self) -> None:
        contract = diagnostic_contract_status()
        self.diagnostic.write_text(
            json.dumps(
                {
                    "diagnostic_contract_id": contract["contract_id"],
                    "diagnostic_contract_sha256": contract["sha256"],
                    "eligible_mechanisms": [
                        {
                            "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
                            "all_predictive_gates_pass": True,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        self.manifest = self._manifest()
        bundle, policy = self._ready_candidate_bundle()
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))
        current = ready_at + timedelta(minutes=2)
        context = discovery_candidate_context(
            self.store,
            self.trigger,
            state,
            now=current,
        )
        self.assertEqual(context["status"], "READY")
        config = copy.deepcopy(context["adapter_config_template"])
        config["mechanism_key"] = "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
        bundle["hypothesis"]["parent"] = FORWARD_PARENT
        bundle["hypothesis"]["required_capability"] = FORWARD_ADAPTER_KEY
        bundle["manifest"].update(
            {
                "parent": FORWARD_PARENT,
                "adapter": FORWARD_ADAPTER_KEY,
                "selection_cutoff": context["selection_cutoff"],
                "oos_cutoff": context["oos_window"]["end_at"],
                "max_variants": 3,
                "adapter_config": config,
            }
        )
        with patch(
            "research_pipeline.cli.selection_corpus_status",
            return_value={"status": "READY"},
        ):
            with patch(
                "research_pipeline.cli.register_evidence_source_contract",
                side_effect=RuntimeError("simulated source-contract interruption"),
            ):
                with self.assertRaisesRegex(
                    RuntimeError, "simulated source-contract interruption"
                ):
                    register_candidate_bundle(
                        self.store,
                        policy,
                        bundle,
                        current_policy_hash="6" * 64,
                        now=current,
                    )
            result = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash="6" * 64,
                now=current,
            )

        self.assertEqual(result["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertIsNotNone(result["oos_evidence_trigger_id"])
        experiment_state = self.store.load_state(result["experiment_id"])
        oos_id = experiment_state["oos_evidence_trigger_id"]
        oos_trigger = self.store.load_evidence_trigger(oos_id)
        oos_state = self.store.load_evidence_trigger_state(oos_id)
        self.assertEqual(oos_trigger["purpose"], "CANDIDATE_OOS")
        self.assertEqual(
            oos_trigger["candidate_binding"]["manifest_sha256"],
            experiment_state["manifest_sha256"],
        )
        self.assertIsNotNone(oos_state["evidence_source_contract"])
        self.assertEqual(oos_state["status"], "WAITING")

    def test_candidate_bundle_preserves_a_measured_24h_sla_breach(self) -> None:
        bundle, policy = self._ready_candidate_bundle()
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        ready_at = datetime.fromisoformat(str(state["evidence_ready_at"]))

        with self._eligible_forward_test_adapter():
            result = register_candidate_bundle(
                self.store,
                policy,
                bundle,
                current_policy_hash=policy_sha256(policy_path),
                now=ready_at + timedelta(hours=24, microseconds=500_000),
            )

        self.assertEqual(result["status"], "CANDIDATE_BUNDLE_REGISTERED")
        self.assertEqual(result["lead_time_sla"], "BREACH")
        self.assertEqual(result["lead_time_seconds"], 86401)

    def test_candidate_bundle_cli_rejects_parity_as_a_strategy_candidate(self) -> None:
        bundle, _policy = self._ready_candidate_bundle()
        bundle_path = self.root / "candidate-bundle.json"
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        policy_path = Path(__file__).parents[1] / "policy.v3.json"
        repository = Path(__file__).resolve().parents[2]
        command = [
            sys.executable,
            "-m",
            "research_pipeline",
            "--state-dir",
            str(self.root),
            "--policy",
            str(policy_path),
            "register-candidate-bundle",
            str(bundle_path),
        ]

        first = subprocess.run(
            command,
            cwd=repository,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(first.returncode, 2)
        result = json.loads(first.stderr)
        self.assertEqual(result["status"], "PIPELINE_ERROR")
        self.assertIn("not eligible", result["detail"])
        self.assertFalse(self.store.hypothesis_path("forward-candidate-test").exists())

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
