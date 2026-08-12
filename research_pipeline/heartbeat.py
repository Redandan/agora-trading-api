from __future__ import annotations

import json
import math
import os
import re
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

from .evidence import MISSED_DISCOVERY_ROLLOVER_STATUS
from .forward_volatility_persistence import (
    seal_forward_volatility_persistence_snapshots,
)
from .microstructure_monitor import (
    microstructure_diagnostic_status,
    microstructure_discovery_recovery_status,
)
from .models import parse_timestamp
from .post_shock_factor import seal_r1_post_shock_factor_snapshots
from .report import load_result, monthly_report, performance_lines, weekly_report
from .shock_attribution import seal_r1_shock_diagnostics
from .storage import (
    ResearchStore,
    atomic_write_json,
    atomic_write_text,
    read_json,
    resolve_store_reference,
    sha256_file,
    store_relative_reference,
)


TAIPEI = timezone(timedelta(hours=8), name="Asia/Taipei")
HEARTBEAT_HOUR = 9
WEEKLY_PREFIX = "weekly-learning-brief-"
MONTHLY_PREFIX = "monthly-learning-review-"
REPORT_DATE = re.compile(r"^(?:weekly-learning-brief|monthly-learning-review)-(\d{4}-\d{2}-\d{2})(?:[-.].*)?\.md$")
COACH_TASK_ID = "019fca63-4f8f-71e3-9d88-297bca468eb9"
COACH_DELIVERY_TOKEN_PREFIX = "SEALED_RESEARCH_DELIVERY:"
COACH_DELIVERY_STATUSES = {
    "DELIVERED_TO_COACH_TASK_VERIFIED",
    "ALREADY_DELIVERED_TO_COACH_TASK",
}
MAX_COACH_PENDING_EVENTS = 32
MAX_COACH_DELIVERED_RECEIPTS = 256
MAX_COACH_RECEIPTS_PER_HEARTBEAT = 8
MICROSTRUCTURE_V3R1_BINDING_PATH = Path(
    "/etc/agora-research/okx-microstructure-continuous-source-v3r1.json"
)
COACH_DELIVERY_PROOF_CYCLE_WINDOW = timedelta(hours=3)
COACH_DELIVERY_EVENT_TIMING_FIELDS = {
    "delivery_queued_at",
    "delivery_deadline_at",
}


def parse_heartbeat_now(value: str | None) -> datetime:
    if value is None:
        return datetime.now(timezone.utc)
    return parse_timestamp(value, "--now").astimezone(timezone.utc)


def load_heartbeat_request_payload(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    payload = read_json(path)
    if set(payload) != {"schema_version", "coach_delivery_receipts"}:
        raise ValueError("heartbeat request payload fields are invalid")
    if payload.get("schema_version") != "1":
        raise ValueError("heartbeat request payload schema_version must be 1")
    receipts = payload.get("coach_delivery_receipts")
    if not isinstance(receipts, list):
        raise ValueError("coach_delivery_receipts must be a list")
    if len(receipts) > MAX_COACH_RECEIPTS_PER_HEARTBEAT:
        raise ValueError("coach delivery receipt count exceeds the bounded limit")
    return receipts


def run_heartbeat_cycle(
    store: ResearchStore,
    policy: dict[str, Any],
    *,
    now: datetime,
    tick_preview: dict[str, Any],
    tick_result: dict[str, Any],
    coach_delivery_receipts: list[dict[str, Any]] | None = None,
    microstructure_binding_path: Path | None = None,
) -> dict[str, Any]:
    state = _load_state(store)
    _verify_report_record(store, state.get("last_weekly"))
    _verify_report_record(store, state.get("last_monthly"))
    adopted_reports = _adopt_existing_reports(store, state, now=now)
    _verify_report_record(store, state.get("last_weekly"))
    _verify_report_record(store, state.get("last_monthly"))

    binding_path = (
        MICROSTRUCTURE_V3R1_BINDING_PATH
        if microstructure_binding_path is None
        else Path(microstructure_binding_path)
    )
    if os.path.lexists(store.root / "microstructure-v3r1") or os.path.lexists(
        binding_path
    ):
        microstructure_diagnostic = microstructure_discovery_recovery_status(
            store.root,
            binding_path=binding_path,
            now=now,
        )
    else:
        microstructure_diagnostic = microstructure_diagnostic_status(
            store.root,
            now=now,
        )
    previous_research_fingerprint = state.get("last_research_fingerprint")
    research_fingerprint = _research_fingerprint(tick_result)
    research_changed = research_fingerprint != previous_research_fingerprint
    previous_microstructure_fingerprint = state.get(
        "last_microstructure_fingerprint"
    )
    microstructure_fingerprint = _microstructure_fingerprint(
        microstructure_diagnostic
    )
    microstructure_changed = (
        microstructure_fingerprint != previous_microstructure_fingerprint
    )
    events: list[dict[str, Any]] = []

    tick_executed = tick_preview.get("status") == "DRY_RUN"
    research_status = str(tick_result.get("status") or "UNKNOWN")
    if tick_executed and research_status in {"BLOCKED", "FAILED"}:
        events.append(_integrity_event_for_tick(store, tick_result))
    elif tick_executed and research_status == "CLOSED":
        events.append(_material_learning_event(store, tick_result))
    elif tick_executed and research_status == "OOS_READY":
        events.append(_candidate_frozen_event(store, tick_result))
    elif (
        tick_executed
        and research_changed
        and research_status == MISSED_DISCOVERY_ROLLOVER_STATUS
    ):
        events.append(_evidence_rollover_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_SOURCE_UNBOUND":
        events.append(_evidence_source_unbound_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_CAPTURE_MISSED":
        events.append(_evidence_capture_missed_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_REVIEW_DUE":
        events.append(_evidence_due_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS":
        events.append(_evidence_ready_event(store, tick_result))

    microstructure_status = str(microstructure_diagnostic["status"])
    if microstructure_changed and microstructure_status in {
        "DIAGNOSTIC_READY",
        "INTEGRITY_BLOCKED",
        "CAPTURE_OVERDUE",
        "RECOVERY_BLOCKED",
    }:
        events.append(_microstructure_event(store, microstructure_diagnostic))

    review_events, announced_reviews = _new_closed_evidence_review_events(store, state)
    events.extend(review_events)

    shock_contract_activated_at = state.get(
        "btc_utc_day_3pct_shock_contract_activated_at"
    )
    if shock_contract_activated_at is None:
        shock_contract_activated_at = _iso_utc(now)
        state[
            "btc_utc_day_3pct_shock_contract_activated_at"
        ] = shock_contract_activated_at
    elif not isinstance(shock_contract_activated_at, str):
        raise ValueError("shock contract activation state is invalid")
    events.extend(
        seal_r1_shock_diagnostics(
            store,
            now=now,
            contract_activated_at=shock_contract_activated_at,
        )
    )
    events.extend(
        seal_r1_post_shock_factor_snapshots(
            store,
            now=now,
        )
    )
    events.extend(
        seal_forward_volatility_persistence_snapshots(
            store,
            now=now,
            activation_receipt=state.get(
                "btc_utc_day_3pct_forward_volatility_persistence_activation"
            ),
        )
    )

    due = _schedule(now, state, tick_result)
    report_events = [
        _report_event_for_kind(
            store,
            record,
            kind=kind,
            research_status=research_status,
        )
        for kind, record in adopted_reports
    ]
    if due["monthly_due"]:
        local_date = now.astimezone(TAIPEI).date()
        content = monthly_report(
            store.entries(),
            days=30,
            policy_id=str(policy["policy_id"]),
            state_root=store.root,
            hypotheses=store.hypothesis_entries(),
            evidence_triggers=store.evidence_trigger_entries(),
            as_of=now,
            report_period=local_date.strftime("%Y-%m"),
        )
        record = _seal_report(store, MONTHLY_PREFIX, now, content, "monthly")
        state["last_monthly"] = record
        report_events.append(
            _report_event_for_kind(
                store,
                record,
                kind="monthly",
                research_status=research_status,
            )
        )
    if due["weekly_due"]:
        local_date = now.astimezone(TAIPEI).date()
        content = weekly_report(
            store.entries(),
            days=7,
            policy_id=str(policy["policy_id"]),
            state_root=store.root,
            hypotheses=store.hypothesis_entries(),
            evidence_triggers=store.evidence_trigger_entries(),
            as_of=now,
            report_period=_weekly_period(local_date),
        )
        record = _seal_report(store, WEEKLY_PREFIX, now, content, "weekly")
        state["last_weekly"] = record
        report_events.append(
            _report_event_for_kind(
                store,
                record,
                kind="weekly",
                research_status=research_status,
            )
        )
    events.extend(report_events)

    events.sort(key=lambda event: _event_priority(str(event["event_type"])))
    coach_delivery = _advance_coach_delivery(
        state,
        receipts=coach_delivery_receipts or [],
        new_events=events,
        now=now,
    )

    post_schedule = _schedule(now, state, tick_result)
    state.update(
        {
            "schema_version": "1",
            "last_success": _iso_utc(now),
            "next_due": post_schedule["earliest"],
            "due_schedule": {
                "daily": post_schedule["daily"],
                "weekly": post_schedule["weekly"],
                "monthly": post_schedule["monthly"],
                "evidence": post_schedule["evidence"],
            },
            "consecutive_failures": 0,
            "last_research_fingerprint": research_fingerprint,
            "last_microstructure_fingerprint": microstructure_fingerprint,
            "announced_closed_evidence_reviews": announced_reviews,
            "last_result": {
                "research_status": research_status,
                "trigger_id": tick_result.get("trigger_id"),
                "experiment_id": tick_result.get("experiment_id"),
                "outcome": tick_result.get("outcome"),
            },
        }
    )
    _write_state(store, state)

    primary = events[0] if events else None
    return {
        "status": "HEARTBEAT_OK",
        "research_status": research_status,
        "tick_preview": tick_preview,
        "tick_result": tick_result,
        "microstructure_diagnostic": microstructure_diagnostic,
        "event_type": None if primary is None else primary["event_type"],
        "artifact_path": None if primary is None else primary["artifact_path"],
        "sha256": None if primary is None else primary["sha256"],
        "material_conclusion": None if primary is None else primary["material_conclusion"],
        "pnl_drawdown_evidence": None if primary is None else primary["pnl_drawdown_evidence"],
        "evidence_diagnostic": None if primary is None else primary.get("evidence_diagnostic"),
        "uncertainty": None if primary is None else primary["uncertainty"],
        "next_action": _next_action(tick_result),
        "concept_to_teach": None if primary is None else primary["concept_to_teach"],
        "should_notify_coach": bool(events),
        "events": events,
        "coach_delivery": coach_delivery,
        "heartbeat_state": _relative(store, _state_path(store)),
        "next_due": state["next_due"],
        "consecutive_failures": 0,
    }


def record_heartbeat_failure(
    store: ResearchStore,
    *,
    now: datetime,
    error: Exception,
    tick_preview: dict[str, Any] | None,
) -> dict[str, Any]:
    state = _load_state(store, verify_schema=False)
    failures = int(state.get("consecutive_failures", 0)) + 1
    failure = {
        "schema_version": "1",
        "recorded_at": _iso_utc(now),
        "consecutive_failures": failures,
        "type": type(error).__name__,
        "detail": str(error),
        "tick_preview": tick_preview,
    }
    path = (
        store.root
        / "heartbeat"
        / "failures"
        / f"{now.astimezone(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')}-{failures}.json"
    )
    atomic_write_json(path, failure)
    event = {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "HEARTBEAT_FAILED_CLOSED",
        "material_conclusion": f"The research heartbeat failed closed: {type(error).__name__}.",
        "pnl_drawdown_evidence": None,
        "evidence_diagnostic": None,
        "uncertainty": str(error),
        "next_action": "INSPECT_FAILURE_ARTIFACT_WITHOUT_ADVANCING_RESEARCH",
        "concept_to_teach": "A control-plane failure is not strategy evidence and cannot justify relaxing a gate.",
    }
    coach_delivery = _advance_coach_delivery(
        state,
        receipts=[],
        new_events=[event],
        now=now,
    )
    daily = _next_daily(now)
    state.update(
        {
            "schema_version": "1",
            "next_due": _iso_utc(daily),
            "consecutive_failures": failures,
            "last_failure": {
                "recorded_at": failure["recorded_at"],
                "artifact_path": event["artifact_path"],
                "sha256": event["sha256"],
                "type": failure["type"],
                "detail": failure["detail"],
            },
        }
    )
    _write_state(store, state)
    return {
        "status": "HEARTBEAT_FAILED_CLOSED",
        "research_status": "UNKNOWN",
        **event,
        "should_notify_coach": True,
        "events": [event],
        "coach_delivery": coach_delivery,
        "heartbeat_state": _relative(store, _state_path(store)),
        "next_due": state["next_due"],
        "consecutive_failures": failures,
    }


def _load_state(store: ResearchStore, *, verify_schema: bool = True) -> dict[str, Any]:
    path = _state_path(store)
    if not path.exists():
        return {
            "schema_version": "1",
            "last_success": None,
            "last_weekly": None,
            "last_monthly": None,
            "next_due": None,
            "due_schedule": {},
            "consecutive_failures": 0,
            "last_failure": None,
            "last_research_fingerprint": None,
            "last_microstructure_fingerprint": None,
            "announced_closed_evidence_reviews": {},
            "coach_delivery": _empty_coach_delivery_state(),
            "last_result": None,
        }
    state = read_json(path)
    if verify_schema and state.get("schema_version") != "1":
        raise ValueError("heartbeat state schema_version must be 1")
    return state


def _empty_coach_delivery_state() -> dict[str, Any]:
    return {
        "schema_version": "1",
        "pending_events": [],
        "delivered_receipts": [],
    }


def _advance_coach_delivery(
    heartbeat_state: dict[str, Any],
    *,
    receipts: list[dict[str, Any]],
    new_events: list[dict[str, Any]],
    now: datetime,
) -> dict[str, Any]:
    if len(receipts) > MAX_COACH_RECEIPTS_PER_HEARTBEAT:
        raise ValueError("coach delivery receipt count exceeds the bounded limit")
    raw = heartbeat_state.get("coach_delivery")
    if raw is None:
        delivery = _empty_coach_delivery_state()
    elif not isinstance(raw, dict) or raw.get("schema_version") != "1":
        raise ValueError("coach delivery state is invalid")
    else:
        delivery = {
            "schema_version": "1",
            "pending_events": list(raw.get("pending_events", [])),
            "delivered_receipts": list(raw.get("delivered_receipts", [])),
        }
    pending = delivery["pending_events"]
    delivered = delivery["delivered_receipts"]
    if not isinstance(pending, list) or not isinstance(delivered, list):
        raise ValueError("coach delivery state lists are invalid")
    if len(pending) > MAX_COACH_PENDING_EVENTS:
        raise ValueError("coach delivery pending event count exceeds the bounded limit")
    if len(delivered) > MAX_COACH_DELIVERED_RECEIPTS:
        raise ValueError("coach delivered receipt count exceeds the bounded limit")

    pending_by_id: dict[str, dict[str, Any]] = {}
    for event in pending:
        delivery_id = _event_delivery_id(event)
        _validate_pending_coach_event_timing(event)
        pending_by_id[delivery_id] = event
    delivered_by_id = {_receipt_delivery_id(receipt): receipt for receipt in delivered}
    if len(pending_by_id) != len(pending) or len(delivered_by_id) != len(delivered):
        raise ValueError("coach delivery state contains duplicate delivery ids")
    if set(pending_by_id).intersection(delivered_by_id):
        raise ValueError("coach delivery id cannot be both pending and delivered")

    acknowledged: list[str] = []
    receipt_ids: set[str] = set()
    for raw_receipt in receipts:
        receipt = _validate_coach_delivery_receipt(raw_receipt)
        delivery_id = receipt["delivery_id"]
        if delivery_id in receipt_ids:
            raise ValueError("coach delivery receipts contain a duplicate delivery id")
        receipt_ids.add(delivery_id)
        if delivery_id in delivered_by_id:
            acknowledged.append(delivery_id)
            continue
        if delivery_id not in pending_by_id:
            raise ValueError("coach delivery receipt does not match a canonical pending event")
        pending_event = pending_by_id[delivery_id]
        pending = [event for event in pending if _event_delivery_id(event) != delivery_id]
        pending_by_id.pop(delivery_id)
        queued_text = pending_event.get("delivery_queued_at")
        deadline_text = pending_event.get("delivery_deadline_at")
        if isinstance(queued_text, str) and isinstance(deadline_text, str):
            queued_at = parse_timestamp(queued_text, "delivery_queued_at").astimezone(
                timezone.utc
            )
            deadline_at = parse_timestamp(
                deadline_text, "delivery_deadline_at"
            ).astimezone(timezone.utc)
            if deadline_at <= queued_at:
                raise ValueError("coach delivery deadline must be after queued_at")
            if now < queued_at:
                raise ValueError("coach delivery receipt predates queued_at")
            delivery_proof_lead_time_seconds: int | None = math.ceil(
                (now - queued_at).total_seconds()
            )
            delivery_proof_sla = "PASS" if now <= deadline_at else "BREACH"
        elif queued_text is None and deadline_text is None:
            queued_text = None
            deadline_text = None
            delivery_proof_lead_time_seconds = None
            delivery_proof_sla = "MISSING_PROOF_LEGACY_EVENT"
        else:
            raise ValueError("coach delivery event timing metadata is incomplete")
        sealed_receipt = {
            **receipt,
            "acknowledged_at": _iso_utc(now),
            "delivery_queued_at": queued_text,
            "delivery_deadline_at": deadline_text,
            "delivery_proof_lead_time_seconds": delivery_proof_lead_time_seconds,
            "delivery_proof_sla": delivery_proof_sla,
        }
        delivered.append(sealed_receipt)
        delivered_by_id[delivery_id] = sealed_receipt
        acknowledged.append(delivery_id)

    for event in new_events:
        delivery_id = _event_delivery_id(event)
        if delivery_id in delivered_by_id:
            continue
        existing = pending_by_id.get(delivery_id)
        if existing is not None:
            existing_payload = {
                key: value
                for key, value in existing.items()
                if key not in COACH_DELIVERY_EVENT_TIMING_FIELDS
            }
            if _canonical_json(existing_payload) != _canonical_json(event):
                raise ValueError("coach pending event changed under the same delivery id")
            continue
        if len(pending) >= MAX_COACH_PENDING_EVENTS:
            raise ValueError("coach delivery pending event count exceeds the bounded limit")
        sealed_event = {
            **event,
            "delivery_queued_at": _iso_utc(now),
            "delivery_deadline_at": _iso_utc(_coach_delivery_deadline(now)),
        }
        pending.append(sealed_event)
        pending_by_id[delivery_id] = sealed_event

    if len(delivered) > MAX_COACH_DELIVERED_RECEIPTS:
        delivered = delivered[-MAX_COACH_DELIVERED_RECEIPTS:]
    delivery = {
        "schema_version": "1",
        "pending_events": pending,
        "delivered_receipts": delivered,
    }
    heartbeat_state["coach_delivery"] = delivery
    return {
        "status": "PENDING" if pending else "IDLE",
        "pending_count": len(pending),
        "delivered_receipt_count": len(delivered),
        "acknowledged_delivery_ids": acknowledged,
    }


def _validate_coach_delivery_receipt(raw: Any) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise ValueError("coach delivery receipt must be an object")
    required = {
        "schema_version",
        "delivery_id",
        "delivery_token",
        "target_thread_id",
        "delivery_status",
    }
    if set(raw) != required:
        raise ValueError("coach delivery receipt fields are invalid")
    delivery_id = str(raw.get("delivery_id", ""))
    if not re.fullmatch(r"[0-9a-f]{64}", delivery_id):
        raise ValueError("coach delivery receipt id is invalid")
    if raw.get("schema_version") != "1":
        raise ValueError("coach delivery receipt schema_version must be 1")
    if raw.get("delivery_token") != f"{COACH_DELIVERY_TOKEN_PREFIX}{delivery_id}":
        raise ValueError("coach delivery receipt token is invalid")
    if raw.get("target_thread_id") != COACH_TASK_ID:
        raise ValueError("coach delivery receipt target is invalid")
    if raw.get("delivery_status") not in COACH_DELIVERY_STATUSES:
        raise ValueError("coach delivery receipt status is not verified")
    return {key: str(raw[key]) for key in required}


def _event_delivery_id(event: Any) -> str:
    if not isinstance(event, dict):
        raise ValueError("coach pending event must be an object")
    delivery_id = event.get("sha256")
    if not isinstance(delivery_id, str) or not re.fullmatch(r"[0-9a-f]{64}", delivery_id):
        raise ValueError("coach pending event artifact hash is invalid")
    return delivery_id


def _validate_pending_coach_event_timing(event: dict[str, Any]) -> None:
    queued_text = event.get("delivery_queued_at")
    deadline_text = event.get("delivery_deadline_at")
    if queued_text is None and deadline_text is None:
        return
    if not isinstance(queued_text, str) or not isinstance(deadline_text, str):
        raise ValueError("coach pending event timing metadata is incomplete")
    queued_at = parse_timestamp(queued_text, "delivery_queued_at").astimezone(
        timezone.utc
    )
    deadline_at = parse_timestamp(
        deadline_text, "delivery_deadline_at"
    ).astimezone(timezone.utc)
    if deadline_at != _coach_delivery_deadline(queued_at):
        raise ValueError(
            "coach pending event deadline is not the next cloud cycle completion"
        )


def _receipt_delivery_id(receipt: Any) -> str:
    if not isinstance(receipt, dict):
        raise ValueError("coach delivered receipt must be an object")
    delivery_id = receipt.get("delivery_id")
    if not isinstance(delivery_id, str) or not re.fullmatch(r"[0-9a-f]{64}", delivery_id):
        raise ValueError("coach delivered receipt id is invalid")
    legacy_fields = {
        "schema_version",
        "delivery_id",
        "delivery_token",
        "target_thread_id",
        "delivery_status",
        "acknowledged_at",
    }
    proof_fields = {
        "delivery_queued_at",
        "delivery_deadline_at",
        "delivery_proof_lead_time_seconds",
        "delivery_proof_sla",
    }
    receipt_fields = frozenset(receipt)
    if receipt_fields not in {
        frozenset(legacy_fields),
        frozenset(legacy_fields | proof_fields),
    }:
        raise ValueError("coach delivered receipt fields are invalid")
    if receipt.get("schema_version") != "1":
        raise ValueError("coach delivered receipt schema_version is invalid")
    if receipt.get("delivery_token") != f"{COACH_DELIVERY_TOKEN_PREFIX}{delivery_id}":
        raise ValueError("coach delivered receipt token is invalid")
    if receipt.get("target_thread_id") != COACH_TASK_ID:
        raise ValueError("coach delivered receipt target is invalid")
    if receipt.get("delivery_status") not in COACH_DELIVERY_STATUSES:
        raise ValueError("coach delivered receipt status is invalid")
    acknowledged_at = parse_timestamp(
        str(receipt.get("acknowledged_at")), "acknowledged_at"
    ).astimezone(timezone.utc)
    if proof_fields.issubset(receipt):
        proof_sla = receipt.get("delivery_proof_sla")
        queued_text = receipt.get("delivery_queued_at")
        deadline_text = receipt.get("delivery_deadline_at")
        lead_time = receipt.get("delivery_proof_lead_time_seconds")
        if proof_sla == "MISSING_PROOF_LEGACY_EVENT":
            if any(value is not None for value in (queued_text, deadline_text, lead_time)):
                raise ValueError("legacy coach delivery proof fields are inconsistent")
        elif proof_sla in {"PASS", "BREACH"}:
            if not isinstance(queued_text, str) or not isinstance(deadline_text, str):
                raise ValueError("coach delivered receipt timing is invalid")
            queued_at = parse_timestamp(
                queued_text, "delivery_queued_at"
            ).astimezone(timezone.utc)
            deadline_at = parse_timestamp(
                deadline_text, "delivery_deadline_at"
            ).astimezone(timezone.utc)
            expected_lead = math.ceil((acknowledged_at - queued_at).total_seconds())
            expected_sla = "PASS" if acknowledged_at <= deadline_at else "BREACH"
            if (
                deadline_at != _coach_delivery_deadline(queued_at)
                or not isinstance(lead_time, int)
                or lead_time < 0
                or lead_time != expected_lead
                or proof_sla != expected_sla
            ):
                raise ValueError("coach delivered receipt proof SLA is inconsistent")
        else:
            raise ValueError("coach delivered receipt proof SLA status is invalid")
    return delivery_id


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"), sort_keys=True)


def _write_state(store: ResearchStore, state: dict[str, Any]) -> None:
    atomic_write_json(_state_path(store), state)


def _state_path(store: ResearchStore) -> Path:
    return store.root / "heartbeat" / "state.json"


def _adopt_existing_reports(
    store: ResearchStore,
    state: dict[str, Any],
    *,
    now: datetime,
) -> list[tuple[str, dict[str, Any]]]:
    reports = store.root / "reports"
    local = now.astimezone(TAIPEI)
    current_periods = {
        "monthly": local.strftime("%Y-%m"),
        "weekly": _weekly_period(local.date()),
    }
    adopted: list[tuple[str, dict[str, Any]]] = []
    for kind, state_field, prefix in (
        ("monthly", "last_monthly", MONTHLY_PREFIX),
        ("weekly", "last_weekly", WEEKLY_PREFIX),
    ):
        latest = _latest_report_record(store, reports, prefix, kind)
        if latest is None:
            continue
        current = state.get(state_field)
        current_date = _report_record_date(current)
        latest_date = _report_record_date(latest)
        if current_date is not None and latest_date <= current_date:
            continue
        state[state_field] = latest
        if _record_period(latest) == current_periods[kind]:
            adopted.append((kind, latest))
    return adopted


def _report_record_date(record: Any) -> date | None:
    if not isinstance(record, dict):
        return None
    try:
        return date.fromisoformat(str(record.get("report_date", "")))
    except ValueError:
        return None


def _latest_report_record(
    store: ResearchStore,
    reports: Path,
    prefix: str,
    kind: str,
) -> dict[str, Any] | None:
    candidates: list[tuple[date, str, Path]] = []
    for path in reports.glob(f"{prefix}*.md"):
        match = REPORT_DATE.fullmatch(path.name)
        if match:
            report_date = date.fromisoformat(match.group(1))
            candidates.append((report_date, path.name, path))
    if not candidates:
        return None
    report_date, _, path = max(candidates)
    return _report_record(store, path, report_date, kind)


def _verify_report_record(store: ResearchStore, record: Any) -> None:
    if record is None:
        return
    if not isinstance(record, dict):
        raise ValueError("heartbeat report record must be an object")
    relative = record.get("artifact_path")
    expected_hash = record.get("sha256")
    report_date = _report_record_date(record)
    if (
        not isinstance(relative, str)
        or not isinstance(expected_hash, str)
        or report_date is None
        or not isinstance(record.get("period"), str)
    ):
        raise ValueError("heartbeat report record is incomplete")
    try:
        path = resolve_store_reference(store.root, relative)
    except ValueError as error:
        raise ValueError("heartbeat report artifact must stay inside research state") from error
    if not path.is_file() or sha256_file(path) != expected_hash:
        raise ValueError(f"sealed heartbeat report changed or disappeared: {relative}")


def _seal_report(
    store: ResearchStore,
    prefix: str,
    now: datetime,
    content: str,
    kind: str,
) -> dict[str, Any]:
    local_date = now.astimezone(TAIPEI).date()
    path = store.root / "reports" / f"{prefix}{local_date.isoformat()}.md"
    if path.exists():
        raise ValueError(f"sealed report already exists: {path}")
    atomic_write_text(path, content + "\n")
    return _report_record(store, path, local_date, kind)


def _report_record(store: ResearchStore, path: Path, report_date: date, kind: str) -> dict[str, Any]:
    period = _weekly_period(report_date) if kind == "weekly" else report_date.strftime("%Y-%m")
    return {
        "period": period,
        "report_date": report_date.isoformat(),
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
    }


def _schedule(now: datetime, state: dict[str, Any], tick_result: dict[str, Any]) -> dict[str, Any]:
    local = now.astimezone(TAIPEI)
    weekly_period = _weekly_period(local.date())
    monthly_period = local.strftime("%Y-%m")
    weekly_at = datetime.combine(
        local.date() - timedelta(days=local.weekday()),
        time(HEARTBEAT_HOUR),
        TAIPEI,
    )
    monthly_at = datetime.combine(local.date().replace(day=1), time(HEARTBEAT_HOUR), TAIPEI)
    weekly_done = _record_period(state.get("last_weekly")) == weekly_period
    monthly_done = _record_period(state.get("last_monthly")) == monthly_period
    weekly_due = local >= weekly_at and not weekly_done
    monthly_due = local >= monthly_at and not monthly_done
    if weekly_done or local >= weekly_at:
        weekly_next = weekly_at + timedelta(days=7)
    else:
        weekly_next = weekly_at
    if not weekly_done and local >= weekly_at:
        weekly_next = local
    next_month = (monthly_at.replace(day=28) + timedelta(days=4)).replace(day=1)
    if monthly_done or local >= monthly_at:
        monthly_next = next_month
    else:
        monthly_next = monthly_at
    if not monthly_done and local >= monthly_at:
        monthly_next = local
    evidence = tick_result.get("next_review_at")
    evidence_at = None
    if isinstance(evidence, str):
        evidence_at = parse_timestamp(evidence, "next_review_at").astimezone(timezone.utc)
    daily = _next_daily(now)
    candidates = [daily, weekly_next.astimezone(timezone.utc), monthly_next.astimezone(timezone.utc)]
    if evidence_at is not None:
        candidates.append(evidence_at)
    return {
        "weekly_due": weekly_due,
        "monthly_due": monthly_due,
        "daily": _iso_utc(daily),
        "weekly": _iso_utc(weekly_next),
        "monthly": _iso_utc(monthly_next),
        "evidence": None if evidence_at is None else _iso_utc(evidence_at),
        "earliest": _iso_utc(min(candidates)),
    }


def _next_daily(now: datetime) -> datetime:
    local = now.astimezone(TAIPEI)
    candidate = datetime.combine(local.date(), time(HEARTBEAT_HOUR), TAIPEI)
    if candidate <= local:
        candidate += timedelta(days=1)
    return candidate.astimezone(timezone.utc)


def _coach_delivery_deadline(now: datetime) -> datetime:
    return _next_daily(now) + COACH_DELIVERY_PROOF_CYCLE_WINDOW


def _weekly_period(value: date) -> str:
    iso = value.isocalendar()
    return f"{iso.year}-W{iso.week:02d}"


def _record_period(record: Any) -> str | None:
    return str(record.get("period")) if isinstance(record, dict) and record.get("period") else None


def _research_fingerprint(result: dict[str, Any]) -> str:
    material = {
        "status": result.get("status"),
        "trigger_id": result.get("trigger_id"),
        "experiment_id": result.get("experiment_id"),
        "outcome": result.get("outcome"),
        "next_review_at": result.get("next_review_at"),
        "evidence_progress_status": (
            result.get("evidence_progress", {}).get("status")
            if isinstance(result.get("evidence_progress"), dict)
            else None
        ),
        "evidence_observation_count": (
            result.get("evidence_progress", {}).get("observation_count")
            if isinstance(result.get("evidence_progress"), dict)
            else None
        ),
    }
    return json.dumps(material, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _microstructure_fingerprint(summary: dict[str, Any]) -> str:
    return json.dumps(
        summary,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _microstructure_event(
    store: ResearchStore,
    summary: dict[str, Any],
) -> dict[str, Any]:
    relative = summary.get("artifact_path")
    expected_hash = summary.get("sha256")
    if not isinstance(relative, str) or not isinstance(expected_hash, str):
        raise ValueError(
            "microstructure recovery has no safely hashable canonical artifact"
        )
    try:
        path = resolve_store_reference(store.root, relative)
    except ValueError as error:
        raise ValueError(
            "microstructure event artifact escapes canonical state"
        ) from error
    if path.is_symlink() or not path.is_file() or sha256_file(path) != expected_hash:
        raise ValueError("microstructure event artifact changed or disappeared")

    status = str(summary["status"])
    if status == "DIAGNOSTIC_READY":
        return {
            "event_type": "EVIDENCE_REVIEW_DUE",
            "artifact_path": relative,
            "sha256": expected_hash,
            "research_status": status,
            "material_conclusion": (
                "The frozen 14-day microstructure intake is ready for "
                "discovery-only diagnostic analysis."
            ),
            "pnl_drawdown_evidence": None,
            "evidence_diagnostic": summary,
            "uncertainty": (
                "Predictive value, PnL, drawdown, fees, slippage, and strategy "
                "usefulness remain MISSING_PROOF."
            ),
            "next_action": "DISPATCH_VALIDATED_LOCAL_MICROSTRUCTURE_DIAGNOSTIC_TASK",
            "concept_to_teach": (
                "A complete discovery diagnostic is not candidate evidence or OOS."
            ),
        }

    conclusion = (
        "The dedicated microstructure intake state is integrity blocked."
        if status in {"INTEGRITY_BLOCKED", "RECOVERY_BLOCKED"}
        else "The next expected microstructure UTC day is overdue."
    )
    return {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": relative,
        "sha256": expected_hash,
        "research_status": status,
        "material_conclusion": conclusion,
        "pnl_drawdown_evidence": None,
        "evidence_diagnostic": summary,
        "uncertainty": (
            "No retry, repair, backfill, source restart, or lifecycle write is authorized."
        ),
        "next_action": "INSPECT_MICROSTRUCTURE_INTEGRITY_WITHOUT_MUTATING_EVIDENCE",
        "concept_to_teach": (
            "A capture or recovery failure is an integrity condition, not alpha evidence."
        ),
    }


def _material_learning_event(store: ResearchStore, result: dict[str, Any]) -> dict[str, Any]:
    experiment_id = str(result.get("experiment_id"))
    state = store.load_state(experiment_id)
    relative = state.get("artifacts", {}).get("learning")
    if not relative:
        return _integrity_event_for_tick(store, {**result, "reason": "MISSING_SEALED_LEARNING"})
    path = resolve_store_reference(store.root, relative)
    learning = read_json(path)
    evidence = learning.get("evidence") if isinstance(learning.get("evidence"), dict) else {}
    uncertainty_parts = [
        str(evidence[key])
        for key in ("contamination_status", "next_required")
        if evidence.get(key)
    ]
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": str(result.get("status")),
        "material_conclusion": str(learning.get("conclusion") or result.get("outcome")),
        "pnl_drawdown_evidence": _performance_evidence(store, experiment_id),
        "uncertainty": "; ".join(uncertainty_parts) or "No additional uncertainty field was sealed.",
        "next_action": "KEEP_REPORTED_NOT_ACTIVATED_AND_FOLLOW_FROZEN_NEXT_ACTION",
        "concept_to_teach": "Matched-capital total PnL must stay paired with drawdown and terminal inventory.",
    }


def _integrity_event_for_tick(store: ResearchStore, result: dict[str, Any]) -> dict[str, Any]:
    experiment_id = result.get("experiment_id")
    path = store.load_state(str(experiment_id)) if experiment_id else None
    artifact = store.experiment_dir(str(experiment_id)) / "state.json" if path else _state_path(store)
    return {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": _relative(store, artifact),
        "sha256": sha256_file(artifact) if artifact.is_file() else None,
        "research_status": str(result.get("status")),
        "material_conclusion": "The deterministic research step failed closed.",
        "pnl_drawdown_evidence": None,
        "uncertainty": str(result.get("reason") or result.get("outcome") or "UNKNOWN"),
        "next_action": "INSPECT_INTEGRITY_FAILURE_WITHOUT_RELAXING_GATES",
        "concept_to_teach": "Infrastructure or integrity failure is not negative alpha evidence.",
    }


def _evidence_due_event(store: ResearchStore, result: dict[str, Any]) -> dict[str, Any]:
    trigger_id = str(result["trigger_id"])
    path = store.evidence_trigger_dir(trigger_id) / "trigger.json"
    return {
        "event_type": "EVIDENCE_REVIEW_DUE",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "EVIDENCE_REVIEW_DUE",
        "material_conclusion": "The frozen prospective evidence window is due for integrity review only.",
        "pnl_drawdown_evidence": None,
        "uncertainty": "The due window is discovery evidence and is not clean OOS for any derived hypothesis.",
        "next_action": "PERFORM_ONE_FROZEN_READ_ONLY_EVIDENCE_REVIEW",
        "concept_to_teach": "Evidence used to discover a hypothesis cannot also serve as its clean OOS.",
    }


def _evidence_capture_missed_event(
    store: ResearchStore, result: dict[str, Any]
) -> dict[str, Any]:
    trigger_id = str(result["trigger_id"])
    path = store.evidence_trigger_dir(trigger_id) / "state.json"
    progress = result.get("evidence_progress", {})
    return {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "EVIDENCE_CAPTURE_MISSED",
        "material_conclusion": "A frozen forward-evidence capture deadline was missed; backfill remains prohibited.",
        "pnl_drawdown_evidence": None,
        "uncertainty": (
            f"expected={progress.get('expected_observations')} "
            f"sealed={progress.get('observation_count')} "
            f"deadline={progress.get('next_capture_deadline')}"
        ),
        "next_action": "CLOSE_THE_TRIGGER_OR_RESTORE_A_LAWFUL_FORWARD_SOURCE_WITH_A_NEW_UNTOUCHED_START",
        "concept_to_teach": "A missed prospective capture cannot be repaired by relabelling later backfill as forward evidence.",
    }


def _evidence_rollover_event(
    store: ResearchStore, result: dict[str, Any]
) -> dict[str, Any]:
    predecessor_id = str(result["predecessor_trigger_id"])
    successor_id = str(result["successor_trigger_id"])
    path = store.evidence_trigger_dir(predecessor_id) / "state.json"
    predecessor_state = read_json(path)
    if (
        predecessor_state.get("status") != "CLOSED"
        or predecessor_state.get("rollover_reason")
        != "MISSED_CAPTURE_WINDOW_NO_BACKFILL"
        or predecessor_state.get("rollover_successor_trigger_id") != successor_id
    ):
        raise ValueError("rollover heartbeat event predecessor state mismatch")
    successor = store.load_evidence_trigger(successor_id)
    successor_state = store.load_evidence_trigger_state(successor_id)
    if (
        successor.get("evidence_start") != result.get("successor_evidence_start")
        or successor_state.get("status") != "WAITING"
        or int(successor_state.get("evidence_observation_count", 0)) != 0
    ):
        raise ValueError("rollover heartbeat event successor state mismatch")
    return {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": MISSED_DISCOVERY_ROLLOVER_STATUS,
        "material_conclusion": (
            "The missed discovery window was preserved and closed without backfill; "
            f"successor {successor_id} starts from a new untouched UTC boundary."
        ),
        "pnl_drawdown_evidence": None,
        "evidence_diagnostic": {
            "predecessor_trigger_id": predecessor_id,
            "successor_trigger_id": successor_id,
            "successor_evidence_start": successor["evidence_start"],
            "successor_review_not_before": successor["review_not_before"],
            "successor_observation_count": 0,
            "successor_source_contract_sha256": result[
                "successor_source_contract_sha256"
            ],
        },
        "uncertainty": (
            "Future capture continuity, predictive value, PnL, drawdown, candidate "
            "readiness and OOS remain MISSING_PROOF."
        ),
        "next_action": "WAIT_FOR_SUCCESSOR_FIRST_COMPLETE_UTC_DAY_WITHOUT_BACKFILL",
        "concept_to_teach": (
            "Autonomous recovery starts a new prospective clock; it never repairs the "
            "failed window or reuses its observations."
        ),
    }


def _evidence_source_unbound_event(
    store: ResearchStore, result: dict[str, Any]
) -> dict[str, Any]:
    trigger_id = str(result["trigger_id"])
    path = store.evidence_trigger_dir(trigger_id) / "state.json"
    progress = result.get("evidence_progress", {})
    return {
        "event_type": "INTEGRITY_ALERT",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "EVIDENCE_SOURCE_UNBOUND",
        "material_conclusion": "The forward-evidence trigger has no sealed source contract.",
        "pnl_drawdown_evidence": None,
        "uncertainty": (
            f"next_day={progress.get('next_observation_day')} "
            f"capture_deadline={progress.get('next_capture_deadline')}"
        ),
        "next_action": "CONNECT_ONE_LAWFUL_ONE_WAY_SOURCE_BEFORE_EVIDENCE_START_OR_CLOSE_THE_TRIGGER",
        "concept_to_teach": "A schedule is not an evidence source; provenance must be frozen before observations begin.",
    }


def _evidence_ready_event(store: ResearchStore, result: dict[str, Any]) -> dict[str, Any]:
    trigger_id = str(result["trigger_id"])
    state = store.load_evidence_trigger_state(trigger_id)
    reviews = state.get("reviews") if isinstance(state.get("reviews"), list) else []
    if not reviews:
        return _evidence_due_event(store, {**result, "status": "EVIDENCE_REVIEW_DUE"})
    latest = reviews[-1]
    path = resolve_store_reference(store.root, latest["path"])
    review = read_json(path)
    diagnostic_summary = (state.get("detail") or {}).get("diagnostic_summary")
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS",
        "material_conclusion": str(review.get("conclusion") or "Evidence review is ready."),
        "pnl_drawdown_evidence": None,
        "evidence_diagnostic": diagnostic_summary,
        "uncertainty": "The reviewed discovery window remains excluded from clean OOS.",
        "next_action": "PROPOSE_AT_MOST_ONE_CAUSAL_HYPOTHESIS_FROM_THE_SEALED_REVIEW",
        "concept_to_teach": "A sealed discovery review may justify one hypothesis, not a promotion claim.",
    }


def _new_closed_evidence_review_events(
    store: ResearchStore,
    heartbeat_state: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """Surface source-ingest review closures once on the next heartbeat.

    The forward source can seal the final observation and deterministic review
    outside the heartbeat lock.  Without this durable scan, a CLOSE outcome
    disappears from the actionable queue before the Coach outbox can see it.
    """

    raw_announced = heartbeat_state.get("announced_closed_evidence_reviews")
    announced = (
        {str(key): str(value) for key, value in raw_announced.items()}
        if isinstance(raw_announced, dict)
        else {}
    )
    previous_success = heartbeat_state.get("last_success")
    previous_success_at = (
        parse_timestamp(str(previous_success), "heartbeat last_success").astimezone(
            timezone.utc
        )
        if previous_success
        else None
    )
    pending: list[tuple[datetime, str, str, dict[str, Any]]] = []
    for trigger, trigger_state in store.evidence_trigger_entries():
        reviews = trigger_state.get("reviews")
        if not isinstance(reviews, list):
            continue
        for reference in reviews:
            if not isinstance(reference, dict) or reference.get("outcome") != "CLOSE":
                continue
            relative = str(reference.get("path") or "")
            expected_hash = str(reference.get("sha256") or "")
            try:
                path = resolve_store_reference(store.root, relative)
            except ValueError as error:
                raise ValueError("closed evidence review path escapes research state") from error
            if not path.is_file() or sha256_file(path) != expected_hash:
                raise ValueError("sealed closed evidence review changed or disappeared")
            review = read_json(path)
            if review.get("outcome") != "CLOSE":
                raise ValueError("closed evidence review reference outcome mismatch")
            reviewed_at = parse_timestamp(
                str(review.get("reviewed_at")), "evidence reviewed_at"
            ).astimezone(timezone.utc)
            trigger_id = str(trigger["trigger_id"])
            if announced.get(trigger_id) == expected_hash:
                continue
            # On upgrade, adopt old sealed reviews without replaying stale Coach events.
            if previous_success_at is not None and reviewed_at <= previous_success_at:
                announced[trigger_id] = expected_hash
                continue
            pending.append(
                (
                    reviewed_at,
                    trigger_id,
                    expected_hash,
                    {
                        "event_type": "MATERIAL_LEARNING",
                        "artifact_path": _relative(store, path),
                        "sha256": expected_hash,
                        "research_status": "NO_CANDIDATE_FORWARD_DIAGNOSTIC",
                        "material_conclusion": str(
                            review.get("conclusion")
                            or "No preregistered mechanism passed the frozen diagnostic gates."
                        ),
                        "pnl_drawdown_evidence": None,
                        "evidence_diagnostic": (trigger_state.get("detail") or {}).get(
                            "diagnostic_summary"
                        ),
                        "uncertainty": (
                            "The closed discovery window tested only its preregistered "
                            "mechanisms and is not strategy OOS evidence."
                        ),
                        "next_action": (
                            "KEEP_THE_BRANCH_CLOSED_AND_WAIT_FOR_A_NEW_PREREGISTERED_"
                            "MECHANISM_OR_UNTOUCHED_DATA_WINDOW"
                        ),
                        "concept_to_teach": (
                            "A clean no-candidate result prevents post-hoc tuning and is "
                            "useful progress, not a stalled pipeline."
                        ),
                    },
                )
            )
    pending.sort(key=lambda item: (item[0], item[3]["artifact_path"]))
    selected = pending[:8]
    for _, trigger_id, expected_hash, _ in selected:
        announced[trigger_id] = expected_hash
    return [event for _, _, _, event in selected], announced


def _candidate_frozen_event(
    store: ResearchStore,
    result: dict[str, Any],
) -> dict[str, Any]:
    experiment_id = str(result["experiment_id"])
    state = store.load_state(experiment_id)
    relative = state.get("artifacts", {}).get("preselect")
    if not isinstance(relative, str):
        return _integrity_event_for_tick(
            store,
            {**result, "reason": "MISSING_SEALED_PRESELECTION"},
        )
    path = resolve_store_reference(store.root, relative)
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "CANDIDATE_FROZEN_OOS_SEALED_WAIT",
        "material_conclusion": (
            "One evidence-bound candidate passed its frozen Design and Validation gates; "
            "its independent future OOS remains sealed and unopened."
        ),
        "pnl_drawdown_evidence": _performance_evidence(store, experiment_id),
        "evidence_diagnostic": None,
        "uncertainty": "Passing historical gates is not OOS evidence or activation authority.",
        "next_action": "WAIT_FOR_COMPLETE_SEALED_CANDIDATE_OOS_WITHOUT_CHANGING_GATES",
        "concept_to_teach": (
            "A frozen candidate is a promise about what will be tested, not proof that the "
            "strategy generalizes."
        ),
    }


def _report_event(
    store: ResearchStore,
    record: dict[str, Any],
    *,
    event_type: str,
    research_status: str,
    conclusion: str,
) -> dict[str, Any]:
    return {
        "event_type": event_type,
        "artifact_path": record["artifact_path"],
        "sha256": record["sha256"],
        "research_status": research_status,
        "material_conclusion": conclusion,
        "pnl_drawdown_evidence": _performance_evidence(store),
        "uncertainty": "Historical or diagnostic evidence does not imply forward performance or activation authority.",
        "next_action": "COACH_THE_SPONSOR_FROM_THE_SEALED_REPORT_WITHOUT_CHANGING_FROZEN_GATES",
        "concept_to_teach": "Research quality improves when rejected mechanisms and opportunity costs remain visible.",
    }


def _report_event_for_kind(
    store: ResearchStore,
    record: dict[str, Any],
    *,
    kind: str,
    research_status: str,
) -> dict[str, Any]:
    if kind == "monthly":
        return _report_event(
            store,
            record,
            event_type="MONTHLY_REVIEW_READY",
            research_status=research_status,
            conclusion="A deterministic thirty-day program review was sealed.",
        )
    if kind == "weekly":
        return _report_event(
            store,
            record,
            event_type="WEEKLY_BRIEF_READY",
            research_status=research_status,
            conclusion="A deterministic seven-day learning brief was sealed.",
        )
    raise ValueError(f"unsupported heartbeat report kind: {kind}")


def _performance_evidence(store: ResearchStore, experiment_id: str | None = None) -> Any:
    entries = store.entries()
    if experiment_id is not None:
        entries = [pair for pair in entries if pair[1].get("experiment_id") == experiment_id]
    entries.sort(key=lambda pair: str(pair[1].get("updated_at") or ""), reverse=True)
    for _, state in entries:
        lines = performance_lines(load_result(store.root, state))
        if lines:
            return {"experiment_id": state["experiment_id"], "summary": lines}
    return None


def _next_action(result: dict[str, Any]) -> str:
    status = str(result.get("status") or "UNKNOWN")
    if status == "WAITING_FOR_EVIDENCE":
        progress = result.get("evidence_progress", {})
        return (
            f"SEAL_NEXT_FORWARD_DAY_BY_{progress.get('next_capture_deadline')}"
            if progress.get("status") == "CAPTURE_DUE"
            else f"WAIT_FOR_NEXT_COMPLETE_UTC_DAY_UNTIL_{progress.get('next_capture_deadline')}"
        )
    if status == "EVIDENCE_CAPTURE_MISSED":
        return "FAIL_CLOSED_WITHOUT_BACKFILL_AND_REGISTER_A_NEW_UNTOUCHED_TRIGGER_IF_NEEDED"
    if status == MISSED_DISCOVERY_ROLLOVER_STATUS:
        return "WAIT_FOR_SUCCESSOR_FIRST_COMPLETE_UTC_DAY_WITHOUT_BACKFILL"
    if status == "EVIDENCE_SOURCE_UNBOUND":
        return "CONNECT_ONE_LAWFUL_ONE_WAY_SOURCE_BEFORE_EVIDENCE_START_OR_CLOSE_THE_TRIGGER"
    if status == "EVIDENCE_REVIEW_DUE":
        return "PERFORM_ONE_FROZEN_READ_ONLY_EVIDENCE_REVIEW"
    if status == "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS":
        return "PROPOSE_AT_MOST_ONE_HYPOTHESIS_FROM_SEALED_EVIDENCE"
    if status == "WAITING_FOR_CANDIDATE_OOS":
        progress = result.get("evidence_progress", {})
        return (
            f"SEAL_NEXT_CANDIDATE_OOS_DAY_BY_{progress.get('next_capture_deadline')}"
            if progress.get("status") == "CAPTURE_DUE"
            else f"WAIT_FOR_COMPLETE_SEALED_CANDIDATE_OOS_UNTIL_{progress.get('next_capture_deadline')}"
        )
    if status == "READY_HYPOTHESIS_REQUIRES_FROZEN_MANIFEST":
        return "FREEZE_VALIDATE_AND_REGISTER_THE_SELECTED_HYPOTHESIS_MANIFEST"
    if status in {"BLOCKED", "FAILED"}:
        return "FAIL_CLOSED_AND_INSPECT_INTEGRITY_EVIDENCE"
    if status == "IDLE_NO_ACTIONABLE_EXPERIMENT":
        return str(result.get("codex_next_action") or "REMAIN_IDLE")
    return "FOLLOW_THE_FROZEN_RESEARCH_LIFECYCLE"


def _event_priority(event_type: str) -> int:
    return {
        "INTEGRITY_ALERT": 0,
        "EVIDENCE_REVIEW_DUE": 1,
        "MATERIAL_LEARNING": 2,
        "MONTHLY_REVIEW_READY": 3,
        "WEEKLY_BRIEF_READY": 4,
    }.get(event_type, 99)


def _relative(store: ResearchStore, path: Path) -> str:
    return store_relative_reference(store.root, path)


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
