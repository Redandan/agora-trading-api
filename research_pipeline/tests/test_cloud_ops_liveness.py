from __future__ import annotations

from contextlib import redirect_stdout
from copy import deepcopy
from io import StringIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.cli import main as pipeline_main
from research_pipeline.cloud_ops_liveness import (
    AUDIT_SCHEMA_PATH,
    READBACK_DOCUMENT_TYPE,
    READBACK_SCHEMA_PATH,
    V11_CONTRACT_ID,
    V11_CONTRACT_SHA256,
    V11_FAILURE_LIFECYCLE,
    V12_CONTRACT_ID,
    V12_CONTRACT_SHA256,
    build_cloud_ops_liveness_audit,
    validate_cloud_ops_liveness_audit,
    validate_control_surface_readback,
)


V10_SHA256 = "90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa"
SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"
OPS_THREAD_ID = "6a71a167-be58-83ec-aed2-f1736e31dd45"
COACH_THREAD_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"
TRIGGER_ID = "prospective-mechanism-neutral-evidence-refresh-test"
CHAIN_HEAD = "7" * 64
BUNDLE_SHA256 = "9" * 64
REQUEST_ID = "1cb2d5f0a27729490d34d94aa31869f4"


def canonical_status() -> dict:
    progress = {
        "status": "AWAITING_DAY_CLOSE",
        "observation_count": 4,
        "expected_observations": 4,
        "lag_observations": 0,
        "next_observation_day": "2026-08-26",
        "next_capture_deadline": "2026-08-27T06:00:00Z",
        "chain_head": CHAIN_HEAD,
    }
    return {
        "server_time": "2026-08-27T00:30:00Z",
        "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        "state_authority": "SERVER_CANONICAL",
        "policy": {"status": "READY"},
        "worker_release": {"status": "READY"},
        "ops_schedule_contract": {
            "status": "READY",
            "schema_version": "10",
            "contract_id": "CLOUD_OPS_SCHEDULE_V10",
            "sha256": V10_SHA256,
            "document_status": "FROZEN",
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
            "timer_authority": "CODEX_CLOUD_OPS_ONLY",
            "state_authority": "SERVER_CANONICAL",
            "schedule_count": 1,
            "recurrence": {
                "frequency": "DAILY",
                "timezone": "Asia/Taipei",
                "local_time": "09:05",
                "end": "NEVER",
            },
            "dispatch_margin": {"scheduled_seconds_after_canonical_due": 300},
            "platform_schedule": {"existing_active_schedule_id": SCHEDULE_ID},
            "coach_delivery": {
                "target_thread_id": COACH_THREAD_ID,
                "cross_task_operations_required": False,
                "heartbeat_outcome_separation": (
                    "CANONICAL_RESEARCH_ADVANCEMENT_NEVER_IMPLIES_COACH_DELIVERY"
                ),
            },
        },
        "queue": {"status": "IDLE"},
        "evidence_capture_queue": {"status": "IDLE"},
        "evidence_ingest_queue": {"status": "IDLE"},
        "latest_heartbeat": {
            "status": "HEARTBEAT_OK",
            "next_due": "2026-08-27T01:00:00Z",
        },
        "latest_evidence_capture": {
            "status": "COMPLETED",
            "bundle_sha256": BUNDLE_SHA256,
            "request": {"request_id": REQUEST_ID},
        },
        "latest_evidence_ingest": {
            "status": "COMPLETED",
            "bundle_sha256": BUNDLE_SHA256,
            "request_id": REQUEST_ID,
            "result": {
                "status": "EVIDENCE_DAY_SEALED",
                "observation": {
                    "day": "2026-08-25",
                    "chain_head": CHAIN_HEAD,
                },
            },
        },
        "registry": {
            "evidence_triggers": [
                {
                    "trigger_id": TRIGGER_ID,
                    "status": "WAITING",
                    "progress": progress,
                }
            ]
        },
    }


def control_readback() -> dict:
    return {
        "schema_version": "1",
        "document_type": READBACK_DOCUMENT_TYPE,
        "observed_at": "2026-08-27T00:31:00Z",
        "readback_status": "AVAILABLE",
        "clock_readback_status": "AVAILABLE",
        "writer_readback_status": "AVAILABLE",
        "clocks": [
            {
                "clock_id": SCHEDULE_ID,
                "clock_kind": "CODEX_CLOUD_OPS",
                "active": True,
                "contract_id": "CLOUD_OPS_SCHEDULE_V10",
                "contract_sha256": V10_SHA256,
                "destination_thread_id": OPS_THREAD_ID,
                "next_run_time": "2026-08-27T01:05:00Z",
                "recurrence": {
                    "frequency": "DAILY",
                    "timezone": "Asia/Taipei",
                    "local_time": "09:05",
                    "end": "NEVER",
                },
            }
        ],
        "writers": [
            {
                "writer_id": "agora-research-server-canonical",
                "active": True,
                "state_authority": "SERVER_CANONICAL",
            }
        ],
        "latest_occurrence": {
            "clock_id": SCHEDULE_ID,
            "scheduled_for": "2026-08-26T01:05:00Z",
            "terminal_status": "HEARTBEAT_OK",
            "heartbeat_queued": True,
            "canonical_request_id": "prior-heartbeat-request",
            "automation_disabled": False,
        },
    }


def blocker_codes(result: dict) -> set[str]:
    return {value["code"] for value in result["blockers"]}


class CloudOpsLivenessTest(unittest.TestCase):
    def test_ready_requires_live_clock_writer_and_future_next_run(self) -> None:
        result = build_cloud_ops_liveness_audit(canonical_status(), control_readback())
        self.assertEqual("READY", result["status"])
        self.assertEqual([], result["blockers"])
        self.assertTrue(result["claims"]["canonical_contract_ready"])
        self.assertTrue(result["claims"]["frozen_schedule_contract_proven"])
        self.assertIsNone(
            result["claims"]["v11_failure_lifecycle_contract_proven"]
        )
        self.assertTrue(result["claims"]["single_clock_proven"])
        self.assertTrue(result["claims"]["single_writer_proven"])
        self.assertTrue(result["claims"]["schedule_lifecycle_preserved"])
        self.assertTrue(result["claims"]["platform_liveness_proven"])
        self.assertTrue(result["claims"]["heartbeat_effect_proven"])
        self.assertTrue(result["claims"]["current_evidence_capture_proven"])
        self.assertTrue(result["claims"]["coach_delivery_decoupled"])

    def test_exact_v11_failure_lifecycle_and_live_clock_are_ready(self) -> None:
        canonical = canonical_status()
        canonical["ops_schedule_contract"].update(
            {
                "schema_version": "11",
                "contract_id": V11_CONTRACT_ID,
                "sha256": V11_CONTRACT_SHA256,
                "failure_lifecycle": deepcopy(V11_FAILURE_LIFECYCLE),
            }
        )
        readback = control_readback()
        readback["clocks"][0].update(
            {
                "contract_id": V11_CONTRACT_ID,
                "contract_sha256": V11_CONTRACT_SHA256,
            }
        )

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("READY", result["status"])
        self.assertTrue(result["claims"]["canonical_contract_ready"])
        self.assertTrue(result["claims"]["frozen_schedule_contract_proven"])
        self.assertTrue(
            result["claims"]["v11_failure_lifecycle_contract_proven"]
        )
        self.assertEqual(
            V11_CONTRACT_ID,
            result["inventory"]["canonical_contract_id"],
        )
        self.assertEqual(
            V11_CONTRACT_SHA256,
            result["inventory"]["canonical_contract_sha256"],
        )

    def test_exact_v12_same_schedule_chat_and_live_clock_are_ready(self) -> None:
        canonical = canonical_status()
        canonical["ops_schedule_contract"].update(
            {
                "schema_version": "12",
                "contract_id": V12_CONTRACT_ID,
                "sha256": V12_CONTRACT_SHA256,
                "failure_lifecycle": deepcopy(V11_FAILURE_LIFECYCLE),
            }
        )
        canonical["ops_schedule_contract"]["coach_delivery"].update(
            {
                "target_thread_id": OPS_THREAD_ID,
                "cross_task_operations_required": False,
                "cross_task_operations_mode": "DENY",
            }
        )
        readback = control_readback()
        readback["clocks"][0].update(
            {
                "contract_id": V12_CONTRACT_ID,
                "contract_sha256": V12_CONTRACT_SHA256,
            }
        )

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("READY", result["status"])
        self.assertTrue(result["claims"]["canonical_contract_ready"])
        self.assertTrue(result["claims"]["frozen_schedule_contract_proven"])
        self.assertTrue(
            result["claims"]["v11_failure_lifecycle_contract_proven"]
        )
        self.assertTrue(result["claims"]["coach_delivery_decoupled"])
        self.assertEqual(
            V12_CONTRACT_ID,
            result["inventory"]["canonical_contract_id"],
        )
        self.assertEqual(
            V12_CONTRACT_SHA256,
            result["inventory"]["canonical_contract_sha256"],
        )

    def test_v11_lifecycle_drift_fails_closed_even_when_platform_matches_hash(self) -> None:
        canonical = canonical_status()
        lifecycle = deepcopy(V11_FAILURE_LIFECYCLE)
        lifecycle["schedule_enabled_state_after_failure"] = "DISABLE"
        canonical["ops_schedule_contract"].update(
            {
                "schema_version": "11",
                "contract_id": V11_CONTRACT_ID,
                "sha256": V11_CONTRACT_SHA256,
                "failure_lifecycle": lifecycle,
            }
        )
        readback = control_readback()
        readback["clocks"][0].update(
            {
                "contract_id": V11_CONTRACT_ID,
                "contract_sha256": V11_CONTRACT_SHA256,
            }
        )

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn(
            "V11_FAILURE_LIFECYCLE_CONTRACT_MISMATCH",
            blocker_codes(result),
        )
        self.assertFalse(result["claims"]["canonical_contract_ready"])
        self.assertFalse(
            result["claims"]["v11_failure_lifecycle_contract_proven"]
        )

    def test_unknown_or_known_id_with_wrong_frozen_hash_fails_closed(self) -> None:
        canonical = canonical_status()
        canonical["ops_schedule_contract"]["sha256"] = "0" * 64
        readback = control_readback()
        readback["clocks"][0]["contract_sha256"] = "0" * 64

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn(
            "CANONICAL_SCHEDULE_CONTRACT_FROZEN_IDENTITY_MISMATCH",
            blocker_codes(result),
        )
        self.assertFalse(result["claims"]["frozen_schedule_contract_proven"])

        canonical = canonical_status()
        canonical["ops_schedule_contract"]["contract_id"] = (
            "CLOUD_OPS_SCHEDULE_V999"
        )
        readback = control_readback()
        readback["clocks"][0]["contract_id"] = "CLOUD_OPS_SCHEDULE_V999"

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("UNSUPPORTED_SCHEDULE_CONTRACT", blocker_codes(result))

    def test_replacement_clock_with_matching_contract_cannot_impersonate_sole_schedule(self) -> None:
        readback = control_readback()
        readback["clocks"][0]["clock_id"] = "replacement-cloud-clock"
        readback["latest_occurrence"]["clock_id"] = "replacement-cloud-clock"

        result = build_cloud_ops_liveness_audit(canonical_status(), readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("ACTIVE_CLOCK_IDENTITY_MISMATCH", blocker_codes(result))
        self.assertFalse(result["claims"]["single_clock_proven"])
        self.assertFalse(result["claims"]["schedule_lifecycle_preserved"])

    def test_paused_duplicate_clock_and_inactive_duplicate_writer_fail_zero_inventory(self) -> None:
        readback = control_readback()
        duplicate_clock = deepcopy(readback["clocks"][0])
        duplicate_clock.update(
            {
                "clock_id": "paused-duplicate-clock",
                "active": False,
                "next_run_time": None,
            }
        )
        readback["clocks"].append(duplicate_clock)
        readback["writers"].append(
            {
                "writer_id": "inactive-duplicate-writer",
                "active": False,
                "state_authority": "SERVER_CANONICAL",
            }
        )

        result = build_cloud_ops_liveness_audit(canonical_status(), readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn(
            "RESEARCH_CLOCK_INVENTORY_COUNT_NOT_ONE",
            blocker_codes(result),
        )
        self.assertIn(
            "CANONICAL_WRITER_INVENTORY_COUNT_NOT_ONE",
            blocker_codes(result),
        )
        self.assertFalse(result["claims"]["single_clock_proven"])
        self.assertFalse(result["claims"]["single_writer_proven"])
        self.assertFalse(result["claims"]["schedule_lifecycle_preserved"])

    def test_disabled_clock_and_rejected_occurrence_block_without_erasing_old_seal(self) -> None:
        canonical = canonical_status()
        canonical["server_time"] = "2026-08-27T02:25:00Z"
        canonical["registry"]["evidence_triggers"][0]["progress"].update(
            {
                "status": "CAPTURE_DUE",
                "expected_observations": 5,
                "lag_observations": 1,
            }
        )
        readback = control_readback()
        readback["observed_at"] = "2026-08-27T02:26:00Z"
        readback["clocks"][0]["active"] = False
        readback["clocks"][0]["next_run_time"] = None
        readback["latest_occurrence"] = {
            "clock_id": SCHEDULE_ID,
            "scheduled_for": "2026-08-27T01:05:00Z",
            "terminal_status": "REJECTED_BEFORE_QUEUEING",
            "heartbeat_queued": False,
            "canonical_request_id": None,
            "automation_disabled": True,
        }

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("OPERATIONAL_BLOCKED", result["status"])
        self.assertTrue(
            {
                "ACTIVE_RESEARCH_CLOCK_MISSING",
                "CANONICAL_HEARTBEAT_EFFECT_MISSING",
                "PLATFORM_HEARTBEAT_REJECTED_BEFORE_QUEUEING",
                "SOLE_CLOCK_DISABLED_BY_FAILED_OCCURRENCE",
            }.issubset(blocker_codes(result))
        )
        self.assertFalse(result["claims"]["platform_liveness_proven"])
        self.assertFalse(result["claims"]["schedule_lifecycle_preserved"])
        self.assertFalse(result["claims"]["heartbeat_effect_proven"])
        self.assertFalse(result["claims"]["current_evidence_capture_proven"])
        self.assertEqual("SEALED", result["evidence"]["latest_coherent_sealed_day"]["status"])

    def test_two_active_clocks_fail_zero_overlap(self) -> None:
        readback = control_readback()
        second = deepcopy(readback["clocks"][0])
        second["clock_id"] = "second-codex-cloud-clock"
        readback["clocks"].append(second)
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("MULTIPLE_ACTIVE_RESEARCH_CLOCKS", blocker_codes(result))
        self.assertFalse(result["claims"]["single_clock_proven"])

    def test_two_active_writers_fail_closed(self) -> None:
        readback = control_readback()
        readback["writers"].append(
            {
                "writer_id": "forbidden-local-writer",
                "active": True,
                "state_authority": "LOCAL_REPLICA",
            }
        )
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("ACTIVE_CANONICAL_WRITER_COUNT_NOT_ONE", blocker_codes(result))
        self.assertFalse(result["claims"]["single_writer_proven"])

    def test_latest_occurrence_must_belong_to_the_sole_active_clock(self) -> None:
        readback = control_readback()
        readback["latest_occurrence"]["clock_id"] = "different-cloud-clock"
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("LATEST_OCCURRENCE_CLOCK_MISMATCH", blocker_codes(result))

    def test_platform_success_cannot_replace_missing_canonical_effect(self) -> None:
        canonical = canonical_status()
        canonical["server_time"] = "2026-08-27T02:25:00Z"
        readback = control_readback()
        readback["observed_at"] = "2026-08-27T02:26:00Z"
        readback["clocks"][0]["next_run_time"] = "2026-08-28T01:05:00Z"
        readback["latest_occurrence"]["scheduled_for"] = "2026-08-27T01:05:00Z"
        result = build_cloud_ops_liveness_audit(canonical, readback)
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("CANONICAL_HEARTBEAT_EFFECT_MISSING", blocker_codes(result))
        self.assertFalse(result["claims"]["heartbeat_effect_proven"])

    def test_due_inflight_queue_is_missing_proof_until_canonical_effect(self) -> None:
        canonical = canonical_status()
        canonical["server_time"] = "2026-08-27T02:25:00Z"
        canonical["queue"]["status"] = "RUNNING"
        readback = control_readback()
        readback["observed_at"] = "2026-08-27T02:26:00Z"
        readback["clocks"][0]["next_run_time"] = "2026-08-28T01:05:00Z"
        result = build_cloud_ops_liveness_audit(canonical, readback)
        self.assertEqual("MISSING_PROOF", result["status"])
        self.assertIn("CANONICAL_HEARTBEAT_EFFECT_PENDING", blocker_codes(result))
        self.assertFalse(result["claims"]["heartbeat_effect_proven"])

    def test_rejected_latest_occurrence_blocks_even_when_canonical_is_fresh(self) -> None:
        canonical = canonical_status()
        canonical["latest_heartbeat"]["next_due"] = "2026-08-27T03:00:00Z"
        readback = control_readback()
        readback["latest_occurrence"].update(
            {
                "terminal_status": "REJECTED_BEFORE_QUEUEING",
                "heartbeat_queued": False,
                "canonical_request_id": None,
                "automation_disabled": True,
            }
        )
        result = build_cloud_ops_liveness_audit(canonical, readback)
        self.assertEqual("OPERATIONAL_BLOCKED", result["status"])
        self.assertIn(
            "PLATFORM_HEARTBEAT_REJECTED_BEFORE_QUEUEING",
            blocker_codes(result),
        )
        self.assertFalse(result["claims"]["platform_liveness_proven"])

    def test_rejected_occurrence_keeps_future_clock_without_self_disabling(self) -> None:
        canonical = canonical_status()
        canonical["server_time"] = "2026-08-27T02:25:00Z"
        canonical["ops_schedule_contract"].update(
            {
                "schema_version": "11",
                "contract_id": V11_CONTRACT_ID,
                "sha256": V11_CONTRACT_SHA256,
                "failure_lifecycle": deepcopy(V11_FAILURE_LIFECYCLE),
            }
        )
        readback = control_readback()
        readback["observed_at"] = "2026-08-27T02:26:00Z"
        readback["clocks"][0]["next_run_time"] = "2026-08-28T01:05:00Z"
        readback["clocks"][0].update(
            {
                "contract_id": V11_CONTRACT_ID,
                "contract_sha256": V11_CONTRACT_SHA256,
            }
        )
        readback["latest_occurrence"] = {
            "clock_id": SCHEDULE_ID,
            "scheduled_for": "2026-08-27T01:05:00Z",
            "terminal_status": "REJECTED_BEFORE_QUEUEING",
            "heartbeat_queued": False,
            "canonical_request_id": None,
            "automation_disabled": False,
        }

        result = build_cloud_ops_liveness_audit(canonical, readback)

        self.assertEqual("OPERATIONAL_BLOCKED", result["status"])
        self.assertIn(
            "PLATFORM_HEARTBEAT_REJECTED_BEFORE_QUEUEING",
            blocker_codes(result),
        )
        self.assertNotIn(
            "SOLE_CLOCK_DISABLED_BY_FAILED_OCCURRENCE",
            blocker_codes(result),
        )
        self.assertTrue(result["claims"]["schedule_lifecycle_preserved"])
        self.assertTrue(
            result["claims"]["v11_failure_lifecycle_contract_proven"]
        )
        self.assertFalse(result["claims"]["platform_liveness_proven"])

    def test_coach_delivery_target_is_not_the_cloud_clock_destination(self) -> None:
        readback = control_readback()
        readback["clocks"][0]["destination_thread_id"] = COACH_THREAD_ID

        result = build_cloud_ops_liveness_audit(canonical_status(), readback)

        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("ACTIVE_CLOCK_IDENTITY_MISMATCH", blocker_codes(result))
        self.assertFalse(result["claims"]["single_clock_proven"])

    def test_platform_success_requires_queue_and_canonical_request_id(self) -> None:
        readback = control_readback()
        readback["latest_occurrence"]["heartbeat_queued"] = False
        readback["latest_occurrence"]["canonical_request_id"] = None
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertIn("PLATFORM_SUCCESS_WITHOUT_QUEUE_PROOF", blocker_codes(result))

    def test_report_failure_next_due_advance_cannot_look_like_evidence_success(self) -> None:
        canonical = canonical_status()
        canonical["latest_heartbeat"].update(
            {
                "status": "HEARTBEAT_FAILED_CLOSED",
                "research_status": "UNKNOWN",
                "next_due": "2026-08-27T03:00:00Z",
                "uncertainty": "report generation fault injection",
            }
        )
        result = build_cloud_ops_liveness_audit(canonical, control_readback())
        self.assertEqual("INTEGRITY_BLOCKED", result["status"])
        self.assertTrue(
            {
                "CANONICAL_HEARTBEAT_FAILED_CLOSED",
                "PLATFORM_SUCCESS_WITHOUT_CANONICAL_HEARTBEAT_SUCCESS",
            }.issubset(blocker_codes(result))
        )
        self.assertFalse(result["claims"]["heartbeat_terminal_success"])
        self.assertFalse(result["claims"]["heartbeat_effect_proven"])
        self.assertEqual(
            "SEALED",
            result["evidence"]["latest_coherent_sealed_day"]["status"],
        )

    def test_coach_delivery_debt_does_not_become_evidence_failure_or_success(self) -> None:
        canonical = canonical_status()
        canonical["coach_outbox"] = {
            "status": "EVENTS_PENDING_EXTERNAL_DELIVERY",
            "pending_count": 10,
            "delivered_receipt_count": 0,
        }
        result = build_cloud_ops_liveness_audit(canonical, control_readback())
        self.assertEqual("READY", result["status"])
        self.assertTrue(result["claims"]["coach_delivery_decoupled"])
        self.assertTrue(result["claims"]["heartbeat_effect_proven"])

    def test_missing_coherent_evidence_history_is_not_ready(self) -> None:
        canonical = canonical_status()
        canonical["latest_evidence_capture"] = None
        canonical["latest_evidence_ingest"] = None
        result = build_cloud_ops_liveness_audit(canonical, control_readback())
        self.assertEqual("MISSING_PROOF", result["status"])
        self.assertIn("LATEST_COHERENT_EVIDENCE_MISSING", blocker_codes(result))

    def test_stale_cross_surface_readback_is_missing_proof(self) -> None:
        readback = control_readback()
        readback["observed_at"] = "2026-08-27T01:00:01Z"
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("MISSING_PROOF", result["status"])
        self.assertIn("STALE_CROSS_SURFACE_READBACK", blocker_codes(result))

    def test_unavailable_readback_cannot_infer_clock_or_writer(self) -> None:
        readback = {
            "schema_version": "1",
            "document_type": READBACK_DOCUMENT_TYPE,
            "observed_at": "2026-08-27T00:31:00Z",
            "readback_status": "MISSING_PROOF",
            "clock_readback_status": "MISSING_PROOF",
            "writer_readback_status": "MISSING_PROOF",
            "clocks": [],
            "writers": [],
            "latest_occurrence": None,
        }
        result = build_cloud_ops_liveness_audit(canonical_status(), readback)
        self.assertEqual("MISSING_PROOF", result["status"])
        self.assertIn("CONTROL_SURFACE_READBACK_UNAVAILABLE", blocker_codes(result))
        self.assertIn(
            "CONTROL_SURFACE_CLOCK_READBACK_UNAVAILABLE",
            blocker_codes(result),
        )
        self.assertIn(
            "CONTROL_SURFACE_WRITER_READBACK_UNAVAILABLE",
            blocker_codes(result),
        )
        self.assertFalse(result["claims"]["single_clock_proven"])
        self.assertFalse(result["claims"]["single_writer_proven"])

    def test_partial_clock_readback_preserves_clock_proof_without_writer_claim(self) -> None:
        readback = control_readback()
        readback.update(
            {
                "readback_status": "PARTIAL",
                "writer_readback_status": "MISSING_PROOF",
                "writers": [],
            }
        )

        result = build_cloud_ops_liveness_audit(canonical_status(), readback)

        self.assertEqual("MISSING_PROOF", result["status"])
        self.assertIn(
            "CONTROL_SURFACE_WRITER_READBACK_UNAVAILABLE",
            blocker_codes(result),
        )
        self.assertNotIn(
            "CONTROL_SURFACE_CLOCK_READBACK_UNAVAILABLE",
            blocker_codes(result),
        )
        self.assertTrue(result["claims"]["single_clock_proven"])
        self.assertTrue(result["claims"]["schedule_lifecycle_preserved"])
        self.assertFalse(result["claims"]["single_writer_proven"])

    def test_unknown_readback_field_is_rejected(self) -> None:
        readback = control_readback()
        readback["inferred_schedule_count"] = 1
        with self.assertRaisesRegex(ValueError, "closed schema"):
            validate_control_surface_readback(readback)

    def test_schema_files_are_closed_and_audit_output_is_schema_validated(self) -> None:
        self.assertTrue(READBACK_SCHEMA_PATH.is_file())
        self.assertTrue(AUDIT_SCHEMA_PATH.is_file())
        result = build_cloud_ops_liveness_audit(canonical_status(), control_readback())
        self.assertIs(result, validate_cloud_ops_liveness_audit(result))
        invalid = deepcopy(result)
        invalid["inferred_live_schedule"] = True
        with self.assertRaisesRegex(ValueError, "closed schema"):
            validate_cloud_ops_liveness_audit(invalid)

    def test_cli_require_ready_is_an_executable_acceptance_gate(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            canonical_path = root / "canonical.json"
            readback_path = root / "readback.json"
            canonical_path.write_text(json.dumps(canonical_status()), encoding="utf-8")
            readback = control_readback()
            readback["clocks"][0]["active"] = False
            readback["clocks"][0]["next_run_time"] = None
            readback_path.write_text(json.dumps(readback), encoding="utf-8")
            stdout = StringIO()
            with redirect_stdout(stdout):
                exit_code = pipeline_main(
                    [
                        "cloud-ops-liveness-audit",
                        str(canonical_path),
                        str(readback_path),
                        "--require-ready",
                    ]
                )
        self.assertEqual(3, exit_code)
        self.assertEqual("OPERATIONAL_BLOCKED", json.loads(stdout.getvalue())["status"])

    def test_cli_require_ready_accepts_only_schema_valid_ready_audit(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            canonical_path = root / "canonical.json"
            readback_path = root / "readback.json"
            canonical_path.write_text(json.dumps(canonical_status()), encoding="utf-8")
            readback_path.write_text(json.dumps(control_readback()), encoding="utf-8")
            stdout = StringIO()
            with redirect_stdout(stdout):
                exit_code = pipeline_main(
                    [
                        "cloud-ops-liveness-audit",
                        str(canonical_path),
                        str(readback_path),
                        "--require-ready",
                    ]
                )
        self.assertEqual(0, exit_code)
        result = json.loads(stdout.getvalue())
        self.assertEqual("READY", result["status"])
        self.assertEqual([], result["blockers"])


if __name__ == "__main__":
    unittest.main()
