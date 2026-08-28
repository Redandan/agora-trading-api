from __future__ import annotations

from datetime import datetime, timedelta, timezone
from functools import lru_cache
import json
from pathlib import Path
import re
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "CLOUD_OPS_LIVENESS_AUDIT_V1"
READBACK_DOCUMENT_TYPE = "CLOUD_OPS_CONTROL_SURFACE_READBACK_V1"
MAX_CROSS_SURFACE_SKEW_SECONDS = 900
PACKAGE_DIR = Path(__file__).resolve().parent
READBACK_SCHEMA_PATH = PACKAGE_DIR / "cloud-ops-control-surface-readback.v1.schema.json"
AUDIT_SCHEMA_PATH = PACKAGE_DIR / "cloud-ops-liveness-audit.v1.schema.json"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_HEARTBEAT_STATUSES = {"HEARTBEAT_OK", "HEARTBEAT_FAILED_CLOSED"}

V10_CONTRACT_ID = "CLOUD_OPS_SCHEDULE_V10"
V10_CONTRACT_SHA256 = (
    "90e0de95fa34beff9447640a5dcdbb972278014664806df0a4bf5f36e2598faa"
)
V11_CONTRACT_ID = "CLOUD_OPS_SCHEDULE_V11"
V11_CONTRACT_SHA256 = (
    "9b30c944f2a7d3d1d23a7b01a87eb72dadb1368749039e6ea279c1b07be37c61"
)
SOLE_SCHEDULE_ID = "6a71a1ed2f608191b0621c52bed3fd81"
FROZEN_SCHEDULE_CONTRACTS = {
    V10_CONTRACT_ID: {
        "schema_version": "10",
        "sha256": V10_CONTRACT_SHA256,
        "schedule_id": SOLE_SCHEDULE_ID,
    },
    V11_CONTRACT_ID: {
        "schema_version": "11",
        "sha256": V11_CONTRACT_SHA256,
        "schedule_id": SOLE_SCHEDULE_ID,
    },
}
V11_FAILURE_LIFECYCLE = {
    "failed_occurrence_effect": "FAIL_CLOSED_CURRENT_OCCURRENCE_ONLY",
    "schedule_enabled_state_after_failure": "KEEP_ENABLED",
    "automatic_pause_disable_or_delete": "DENY",
    "schedule_self_mutation": "DENY",
    "next_normal_occurrence": "PRESERVE",
    "same_occurrence_heartbeat_retry": "DENY",
    "manual_catchup": "DENY",
    "evidence_backfill": "DENY",
    "schedule_mutation_authority": "EXPLICIT_USER_AUTHORIZATION_ONLY",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"control-surface readback contains duplicate key: {key}")
        value[key] = item
    return value


def _mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be a UTC timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError(f"{label} must be timezone-aware UTC")
    return parsed


def _optional_timestamp(value: Any, label: str) -> datetime | None:
    if value is None:
        return None
    return _timestamp(value, label)


@lru_cache(maxsize=2)
def _schema_validator(path: Path) -> Draft202012Validator:
    try:
        schema = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"schema is not readable strict JSON: {path.name}") from error
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def _validate_schema(value: Any, path: Path, label: str) -> dict[str, Any]:
    document = _mapping(value, label)
    errors = sorted(
        _schema_validator(path).iter_errors(document),
        key=lambda error: tuple(str(item) for item in error.absolute_path),
    )
    if errors:
        error = errors[0]
        location = ".".join(str(item) for item in error.absolute_path) or "$"
        raise ValueError(
            f"{label} does not satisfy the closed schema at {location}: {error.message}"
        )
    return document


def validate_control_surface_readback(value: Any) -> dict[str, Any]:
    document = _validate_schema(
        value,
        READBACK_SCHEMA_PATH,
        "control-surface readback",
    )
    _timestamp(document["observed_at"], "observed_at")

    clocks = document["clocks"]
    writers = document["writers"]

    seen_clock_ids: set[str] = set()
    for index, clock in enumerate(clocks):
        clock_id = clock["clock_id"]
        if clock_id in seen_clock_ids:
            raise ValueError("control-surface clock ids must be distinct")
        seen_clock_ids.add(clock_id)
        _optional_timestamp(clock["next_run_time"], f"clocks[{index}].next_run_time")

    seen_writer_ids: set[str] = set()
    for writer in writers:
        writer_id = writer["writer_id"]
        if writer_id in seen_writer_ids:
            raise ValueError("control-surface writer ids must be distinct")
        seen_writer_ids.add(writer_id)

    occurrence = document["latest_occurrence"]
    if occurrence is not None:
        _timestamp(occurrence["scheduled_for"], "latest_occurrence.scheduled_for")
    return document


def validate_cloud_ops_liveness_audit(value: Any) -> dict[str, Any]:
    return _validate_schema(value, AUDIT_SCHEMA_PATH, "cloud-ops liveness audit")


def load_control_surface_readback(path: Path | str) -> dict[str, Any]:
    raw = Path(path).read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("control-surface readback must be strict UTF-8 JSON") from error
    return validate_control_surface_readback(value)


def load_canonical_status(path: Path | str) -> dict[str, Any]:
    raw = Path(path).read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("canonical status must be strict UTF-8 JSON") from error
    return _mapping(value, "canonical status")


def _active_trigger(canonical: dict[str, Any]) -> dict[str, Any] | None:
    registry = _mapping(canonical.get("registry"), "canonical registry")
    raw_triggers = registry.get("evidence_triggers")
    if not isinstance(raw_triggers, list):
        raise ValueError("canonical evidence trigger inventory must be an array")
    active = [
        value
        for value in raw_triggers
        if isinstance(value, dict) and value.get("status") in {"WAITING", "REVIEW_DUE"}
    ]
    if len(active) > 1:
        raise ValueError("canonical status contains multiple active evidence triggers")
    return active[0] if active else None


def _queue_status(canonical: dict[str, Any], name: str) -> str:
    value = _mapping(canonical.get(name), f"canonical {name}")
    status = value.get("status")
    if not isinstance(status, str) or not status:
        raise ValueError(f"canonical {name}.status is missing")
    return status


def _coherent_latest_evidence(canonical: dict[str, Any]) -> dict[str, Any]:
    capture = canonical.get("latest_evidence_capture")
    ingest = canonical.get("latest_evidence_ingest")
    if not isinstance(capture, dict) or not isinstance(ingest, dict):
        return {"status": "MISSING_PROOF", "day": None, "chain_head": None}
    capture_request = capture.get("request")
    result = ingest.get("result")
    observation = result.get("observation") if isinstance(result, dict) else None
    coherent = (
        capture.get("status") == "COMPLETED"
        and ingest.get("status") == "COMPLETED"
        and isinstance(result, dict)
        and result.get("status") == "EVIDENCE_DAY_SEALED"
        and capture.get("bundle_sha256") == ingest.get("bundle_sha256")
        and isinstance(capture_request, dict)
        and capture_request.get("request_id") == ingest.get("request_id")
        and isinstance(observation, dict)
        and isinstance(observation.get("day"), str)
        and isinstance(observation.get("chain_head"), str)
        and _SHA256.fullmatch(observation["chain_head"]) is not None
    )
    if not coherent:
        return {"status": "INTEGRITY_BLOCKED", "day": None, "chain_head": None}
    return {
        "status": "SEALED",
        "day": observation["day"],
        "chain_head": observation["chain_head"],
    }


def build_cloud_ops_liveness_audit(
    canonical_status: dict[str, Any],
    control_surface_readback: dict[str, Any],
) -> dict[str, Any]:
    canonical = _mapping(canonical_status, "canonical status")
    readback = validate_control_surface_readback(control_surface_readback)
    server_time = _timestamp(canonical.get("server_time"), "canonical server_time")
    observed_at = _timestamp(readback["observed_at"], "observed_at")
    policy = _mapping(canonical.get("policy"), "canonical policy")
    worker = _mapping(canonical.get("worker_release"), "canonical worker_release")
    ops = _mapping(canonical.get("ops_schedule_contract"), "canonical ops_schedule_contract")
    latest_heartbeat = _mapping(canonical.get("latest_heartbeat"), "canonical latest_heartbeat")
    active_trigger = _active_trigger(canonical)

    blockers: list[dict[str, str]] = []

    def block(code: str, severity: str, detail: str) -> None:
        if code not in {item["code"] for item in blockers}:
            blockers.append({"code": code, "severity": severity, "detail": detail})

    heartbeat_terminal_status = latest_heartbeat.get("status")
    heartbeat_terminal_success = heartbeat_terminal_status == "HEARTBEAT_OK"
    if heartbeat_terminal_status == "HEARTBEAT_FAILED_CLOSED":
        block(
            "CANONICAL_HEARTBEAT_FAILED_CLOSED",
            "INTEGRITY",
            "A fail-closed heartbeat is a control-plane failure, never evidence success.",
        )
    elif heartbeat_terminal_status not in _HEARTBEAT_STATUSES:
        block(
            "CANONICAL_HEARTBEAT_OUTCOME_MISSING",
            "MISSING_PROOF",
            "Canonical heartbeat terminal outcome is absent or unsupported.",
        )

    authority_ready = (
        policy.get("status") == "READY"
        and worker.get("status") == "READY"
        and ops.get("status") == "READY"
        and canonical.get("timer_authority") == ops.get("timer_authority")
        and canonical.get("state_authority") == ops.get("state_authority")
    )
    contract_id = ops.get("contract_id")
    frozen_contract = FROZEN_SCHEDULE_CONTRACTS.get(contract_id)
    frozen_schedule_contract_proven = False
    v11_failure_lifecycle_contract_proven: bool | None = None
    if frozen_contract is None:
        block(
            "UNSUPPORTED_SCHEDULE_CONTRACT",
            "INTEGRITY",
            "Only the frozen V10 predecessor and V11 lifecycle contract are supported.",
        )
    else:
        frozen_schedule_contract_proven = (
            ops.get("schema_version") == frozen_contract["schema_version"]
            and ops.get("document_status") == "FROZEN"
            and ops.get("authorization") == AUTHORIZATION
            and ops.get("sha256") == frozen_contract["sha256"]
        )
        if not frozen_schedule_contract_proven:
            block(
                "CANONICAL_SCHEDULE_CONTRACT_FROZEN_IDENTITY_MISMATCH",
                "INTEGRITY",
                "Canonical schedule id, schema version, authorization and SHA-256 must match a known frozen contract.",
            )
        if contract_id == V11_CONTRACT_ID:
            v11_failure_lifecycle_contract_proven = (
                ops.get("failure_lifecycle") == V11_FAILURE_LIFECYCLE
            )
            if not v11_failure_lifecycle_contract_proven:
                block(
                    "V11_FAILURE_LIFECYCLE_CONTRACT_MISMATCH",
                    "INTEGRITY",
                    "V11 must preserve the exact fail-closed-cycle and keep-enabled-clock lifecycle contract.",
                )

    contract_ready = authority_ready and frozen_schedule_contract_proven and (
        contract_id != V11_CONTRACT_ID
        or v11_failure_lifecycle_contract_proven is True
    )
    if not contract_ready:
        block(
            "CANONICAL_CONTRACT_NOT_READY",
            "INTEGRITY",
            "Policy, Worker and schedule authority must all be READY and identity-consistent.",
        )
    if ops.get("schedule_count") != 1:
        block(
            "CANONICAL_DECLARED_SCHEDULE_COUNT_NOT_ONE",
            "INTEGRITY",
            "The frozen canonical schedule contract must declare exactly one clock.",
        )

    if abs((observed_at - server_time).total_seconds()) > MAX_CROSS_SURFACE_SKEW_SECONDS:
        block(
            "STALE_CROSS_SURFACE_READBACK",
            "MISSING_PROOF",
            "Canonical and control-surface observations are more than fifteen minutes apart.",
        )

    clock_readback_available = readback["clock_readback_status"] == "AVAILABLE"
    writer_readback_available = readback["writer_readback_status"] == "AVAILABLE"
    active_clocks = [clock for clock in readback["clocks"] if clock["active"]]
    active_writers = [writer for writer in readback["writers"] if writer["active"]]
    if not clock_readback_available and not writer_readback_available:
        block(
            "CONTROL_SURFACE_READBACK_UNAVAILABLE",
            "MISSING_PROOF",
            "A frozen repository contract cannot prove live platform clock or writer inventory.",
        )
    if not clock_readback_available:
        block(
            "CONTROL_SURFACE_CLOCK_READBACK_UNAVAILABLE",
            "MISSING_PROOF",
            "Live clock inventory and future next-run proof are unavailable.",
        )
    else:
        if len(readback["clocks"]) != 1:
            block(
                "RESEARCH_CLOCK_INVENTORY_COUNT_NOT_ONE",
                "INTEGRITY",
                "Exactly one total research clock may exist; paused or inactive duplicates are forbidden.",
            )
        if len(active_clocks) == 0:
            block(
                "ACTIVE_RESEARCH_CLOCK_MISSING",
                "OPERATIONAL",
                "No active research clock is visible on the control surface.",
            )
        elif len(active_clocks) > 1:
            block(
                "MULTIPLE_ACTIVE_RESEARCH_CLOCKS",
                "INTEGRITY",
                "More than one active research clock violates zero-overlap cutover.",
            )
    if not writer_readback_available:
        block(
            "CONTROL_SURFACE_WRITER_READBACK_UNAVAILABLE",
            "MISSING_PROOF",
            "Independent Server Canonical writer inventory is unavailable.",
        )
    else:
        if len(readback["writers"]) != 1:
            block(
                "CANONICAL_WRITER_INVENTORY_COUNT_NOT_ONE",
                "INTEGRITY",
                "Exactly one total canonical writer may exist; inactive duplicates are forbidden.",
            )
        if len(active_writers) != 1:
            block(
                "ACTIVE_CANONICAL_WRITER_COUNT_NOT_ONE",
                "INTEGRITY",
                "Exactly one active Server Canonical writer must be independently inventoried.",
            )

    delivery = _mapping(ops.get("coach_delivery"), "ops coach_delivery")
    expected_contract_id = ops.get("contract_id")
    expected_contract_sha256 = ops.get("sha256")
    expected_recurrence = ops.get("recurrence")
    expected_destination = delivery.get("target_thread_id")
    matching_clock: dict[str, Any] | None = active_clocks[0] if len(active_clocks) == 1 else None
    clock_identity_proven = False
    next_run_proven = False
    if matching_clock is not None:
        expected_schedule_id = (
            frozen_contract["schedule_id"] if frozen_contract is not None else None
        )
        clock_identity_proven = (
            matching_clock["clock_id"] == expected_schedule_id
            and matching_clock["clock_kind"] == "CODEX_CLOUD_OPS"
            and matching_clock["contract_id"] == expected_contract_id
            and matching_clock["contract_sha256"] == expected_contract_sha256
            and matching_clock["recurrence"] == expected_recurrence
            and matching_clock["destination_thread_id"] == expected_destination
        )
        if not clock_identity_proven:
            block(
                "ACTIVE_CLOCK_IDENTITY_MISMATCH",
                "INTEGRITY",
                "The active clock does not exactly match the frozen schedule id, canonical contract, recurrence and destination.",
            )
        next_run = _optional_timestamp(matching_clock["next_run_time"], "active clock next_run_time")
        next_run_proven = next_run is not None and next_run > observed_at
        if not next_run_proven:
            block(
                "ACTIVE_CLOCK_NEXT_RUN_MISSING",
                "OPERATIONAL",
                "An enabled schedule without a future next-run readback is not liveness proof.",
            )

    writer_identity_proven = (
        len(active_writers) == 1
        and active_writers[0]["state_authority"] == canonical.get("state_authority")
    )
    if len(active_writers) == 1 and not writer_identity_proven:
        block(
            "ACTIVE_WRITER_AUTHORITY_MISMATCH",
            "INTEGRITY",
            "The active writer does not match Server Canonical authority.",
        )

    latest_occurrence = readback["latest_occurrence"]
    occurrence_rejected = False
    occurrence_claims_success = False
    occurrence_disabled_schedule = False
    if latest_occurrence is not None:
        _timestamp(
            latest_occurrence["scheduled_for"],
            "latest_occurrence.scheduled_for",
        )
        if (
            matching_clock is not None
            and latest_occurrence["clock_id"] != matching_clock["clock_id"]
        ):
            block(
                "LATEST_OCCURRENCE_CLOCK_MISMATCH",
                "INTEGRITY",
                "The latest occurrence does not belong to the sole active clock.",
            )
        occurrence_rejected = (
            latest_occurrence["terminal_status"] == "REJECTED_BEFORE_QUEUEING"
            and not latest_occurrence["heartbeat_queued"]
        )
        occurrence_disabled_schedule = latest_occurrence["automation_disabled"]
        if occurrence_disabled_schedule:
            block(
                "SOLE_CLOCK_DISABLED_BY_FAILED_OCCURRENCE",
                "OPERATIONAL",
                "A scheduled occurrence disabled the sole research clock instead of failing only that occurrence.",
            )
        occurrence_claims_success = latest_occurrence["terminal_status"] == "HEARTBEAT_OK"
        if occurrence_claims_success and (
            not latest_occurrence["heartbeat_queued"]
            or latest_occurrence["canonical_request_id"] is None
        ):
            block(
                "PLATFORM_SUCCESS_WITHOUT_QUEUE_PROOF",
                "INTEGRITY",
                "A platform success label requires a durable queue claim and canonical request id.",
            )
        if occurrence_claims_success and not heartbeat_terminal_success:
            block(
                "PLATFORM_SUCCESS_WITHOUT_CANONICAL_HEARTBEAT_SUCCESS",
                "INTEGRITY",
                "Platform completion cannot override a non-success canonical heartbeat outcome.",
            )

    next_due = _timestamp(latest_heartbeat.get("next_due"), "latest_heartbeat.next_due")
    dispatch_margin_value = _mapping(
        ops.get("dispatch_margin"),
        "ops dispatch_margin",
    ).get(
        "scheduled_seconds_after_canonical_due",
        0,
    )
    if not isinstance(dispatch_margin_value, int) or isinstance(dispatch_margin_value, bool):
        raise ValueError("ops dispatch margin must be an integer")
    dispatch_margin = dispatch_margin_value
    if dispatch_margin < 0:
        raise ValueError("ops dispatch margin cannot be negative")
    due_overdue = server_time > next_due + timedelta(seconds=dispatch_margin)
    queue_statuses = {
        "heartbeat": _queue_status(canonical, "queue"),
        "capture": _queue_status(canonical, "evidence_capture_queue"),
        "ingest": _queue_status(canonical, "evidence_ingest_queue"),
    }
    queues_idle = set(queue_statuses.values()) == {"IDLE"}
    heartbeat_effect_proven = heartbeat_terminal_success and not due_overdue
    if due_overdue and queues_idle:
        severity = "INTEGRITY" if occurrence_claims_success else "OPERATIONAL"
        block(
            "CANONICAL_HEARTBEAT_EFFECT_MISSING",
            severity,
            "The due boundary plus dispatch margin passed, queues are idle and canonical next_due did not advance.",
        )
    elif due_overdue:
        block(
            "CANONICAL_HEARTBEAT_EFFECT_PENDING",
            "MISSING_PROOF",
            "The due boundary passed, but queue activity has not yet produced a fresh canonical next_due.",
        )
    if occurrence_rejected:
        block(
            "PLATFORM_HEARTBEAT_REJECTED_BEFORE_QUEUEING",
            "OPERATIONAL",
            "The latest due occurrence was rejected before any durable Research queue mutation.",
        )

    latest_evidence = _coherent_latest_evidence(canonical)
    if latest_evidence["status"] == "INTEGRITY_BLOCKED":
        block(
            "LATEST_EVIDENCE_CAPTURE_INGEST_MISMATCH",
            "INTEGRITY",
            "Latest capture and ingest identities do not form one sealed evidence day.",
        )
    elif latest_evidence["status"] == "MISSING_PROOF":
        block(
            "LATEST_COHERENT_EVIDENCE_MISSING",
            "MISSING_PROOF",
            "No coherent sealed capture and ingest history is available for liveness review.",
        )

    evidence_progress: dict[str, Any] | None = None
    current_evidence_capture_proven: bool | None = None
    if active_trigger is not None:
        progress = _mapping(active_trigger.get("progress"), "active trigger progress")
        deadline_value = progress.get("next_capture_deadline")
        deadline = _optional_timestamp(deadline_value, "active trigger next_capture_deadline")
        progress_status = progress.get("status")
        current_evidence_capture_proven = progress_status != "CAPTURE_DUE"
        if progress_status == "CAPTURE_DUE" and deadline is not None and server_time > deadline:
            block(
                "EVIDENCE_CAPTURE_DEADLINE_BREACHED",
                "INTEGRITY",
                "The active prospective day passed its frozen capture deadline without canonical ingestion.",
            )
        evidence_progress = {
            "trigger_id": active_trigger.get("trigger_id"),
            "status": progress_status,
            "observation_count": progress.get("observation_count"),
            "expected_observations": progress.get("expected_observations"),
            "lag_observations": progress.get("lag_observations"),
            "next_observation_day": progress.get("next_observation_day"),
            "next_capture_deadline": deadline_value,
            "current_capture_proven": current_evidence_capture_proven,
        }

    coach_delivery_decoupled = (
        delivery.get("cross_task_operations_required") is False
        and delivery.get("heartbeat_outcome_separation")
        == "CANONICAL_RESEARCH_ADVANCEMENT_NEVER_IMPLIES_COACH_DELIVERY"
    )
    if not coach_delivery_decoupled:
        block(
            "COACH_DELIVERY_REMAINS_HEARTBEAT_LIVENESS_GATE",
            "INTEGRITY",
            "Coach delivery must remain optional and separately evidenced.",
        )

    severities = {item["severity"] for item in blockers}
    if "INTEGRITY" in severities:
        status = "INTEGRITY_BLOCKED"
    elif "OPERATIONAL" in severities:
        status = "OPERATIONAL_BLOCKED"
    elif "MISSING_PROOF" in severities:
        status = "MISSING_PROOF"
    else:
        status = "READY"

    single_clock_proven = (
        clock_readback_available
        and len(readback["clocks"]) == 1
        and len(active_clocks) == 1
        and clock_identity_proven
    )
    single_writer_proven = (
        writer_readback_available
        and len(readback["writers"]) == 1
        and writer_identity_proven
    )
    schedule_lifecycle_preserved = (
        single_clock_proven
        and next_run_proven
        and not occurrence_disabled_schedule
    )
    platform_liveness_proven = (
        schedule_lifecycle_preserved
        and not occurrence_rejected
        and "STALE_CROSS_SURFACE_READBACK" not in {item["code"] for item in blockers}
    )
    audit = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "authorization": AUTHORIZATION,
        "observed_at": readback["observed_at"],
        "status": status,
        "claims": {
            "canonical_contract_ready": contract_ready,
            "frozen_schedule_contract_proven": frozen_schedule_contract_proven,
            "v11_failure_lifecycle_contract_proven": (
                v11_failure_lifecycle_contract_proven
            ),
            "single_clock_proven": single_clock_proven,
            "single_writer_proven": single_writer_proven,
            "schedule_lifecycle_preserved": schedule_lifecycle_preserved,
            "platform_liveness_proven": platform_liveness_proven,
            "heartbeat_terminal_success": heartbeat_terminal_success,
            "heartbeat_effect_proven": heartbeat_effect_proven,
            "current_evidence_capture_proven": current_evidence_capture_proven,
            "coach_delivery_decoupled": coach_delivery_decoupled,
        },
        "inventory": {
            "canonical_contract_id": contract_id,
            "canonical_contract_sha256": ops.get("sha256"),
            "canonical_declared_schedule_count": ops.get("schedule_count"),
            "control_surface_clock_count": len(readback["clocks"]),
            "active_clock_count": len(active_clocks),
            "control_surface_writer_count": len(readback["writers"]),
            "active_writer_count": len(active_writers),
            "queue_statuses": queue_statuses,
        },
        "heartbeat": {
            "canonical_status": heartbeat_terminal_status,
            "canonical_next_due": latest_heartbeat.get("next_due"),
            "due_boundary_overdue": due_overdue,
            "latest_platform_occurrence_status": (
                latest_occurrence["terminal_status"] if latest_occurrence else "MISSING_PROOF"
            ),
        },
        "evidence": {
            "latest_coherent_sealed_day": latest_evidence,
            "active_trigger_progress": evidence_progress,
        },
        "blockers": blockers,
        "cutover_admission": {
            "status": "NOT_AUTHORIZED_BY_READ_ONLY_AUDIT",
            "zero_overlap_required": True,
            "second_clock": "DENY",
            "second_writer": "DENY",
            "live_effectiveness": "MISSING_PROOF",
        },
        "performance_boundary": {
            "immediate_pnl_effect": "ZERO_CONTROL_PLANE_ONLY",
            "immediate_drawdown_effect": "ZERO_CONTROL_PLANE_ONLY",
            "candidate_creation": False,
            "oos_access": "DENY",
            "trading_action": False,
        },
    }
    return validate_cloud_ops_liveness_audit(audit)
