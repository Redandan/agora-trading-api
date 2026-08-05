from __future__ import annotations

import json
import re
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

from .models import parse_timestamp
from .report import load_result, monthly_report, performance_lines, weekly_report
from .storage import ResearchStore, atomic_write_json, atomic_write_text, read_json, sha256_file


TAIPEI = timezone(timedelta(hours=8), name="Asia/Taipei")
HEARTBEAT_HOUR = 9
WEEKLY_PREFIX = "weekly-learning-brief-"
MONTHLY_PREFIX = "monthly-learning-review-"
REPORT_DATE = re.compile(r"^(?:weekly-learning-brief|monthly-learning-review)-(\d{4}-\d{2}-\d{2})(?:[-.].*)?\.md$")


def parse_heartbeat_now(value: str | None) -> datetime:
    if value is None:
        return datetime.now(timezone.utc)
    return parse_timestamp(value, "--now").astimezone(timezone.utc)


def run_heartbeat_cycle(
    store: ResearchStore,
    policy: dict[str, Any],
    *,
    now: datetime,
    tick_preview: dict[str, Any],
    tick_result: dict[str, Any],
) -> dict[str, Any]:
    state = _load_state(store)
    _adopt_existing_reports(store, state)
    _verify_report_record(store, state.get("last_weekly"))
    _verify_report_record(store, state.get("last_monthly"))

    previous_research_fingerprint = state.get("last_research_fingerprint")
    research_fingerprint = _research_fingerprint(tick_result)
    research_changed = research_fingerprint != previous_research_fingerprint
    events: list[dict[str, Any]] = []

    tick_executed = tick_preview.get("status") == "DRY_RUN"
    research_status = str(tick_result.get("status") or "UNKNOWN")
    if tick_executed and research_status in {"BLOCKED", "FAILED"}:
        events.append(_integrity_event_for_tick(store, tick_result))
    elif tick_executed and research_status == "CLOSED":
        events.append(_material_learning_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_SOURCE_UNBOUND":
        events.append(_evidence_source_unbound_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_CAPTURE_MISSED":
        events.append(_evidence_capture_missed_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_REVIEW_DUE":
        events.append(_evidence_due_event(store, tick_result))
    elif research_changed and research_status == "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS":
        events.append(_evidence_ready_event(store, tick_result))

    due = _schedule(now, state, tick_result)
    report_events: list[dict[str, Any]] = []
    if due["monthly_due"]:
        content = monthly_report(
            store.entries(),
            days=30,
            policy_id=str(policy["policy_id"]),
            state_root=store.root,
            hypotheses=store.hypothesis_entries(),
            evidence_triggers=store.evidence_trigger_entries(),
        )
        record = _seal_report(store, MONTHLY_PREFIX, now, content, "monthly")
        state["last_monthly"] = record
        report_events.append(
            _report_event(
                store,
                record,
                event_type="MONTHLY_REVIEW_READY",
                research_status=research_status,
                conclusion="A deterministic thirty-day program review was sealed.",
            )
        )
    if due["weekly_due"]:
        content = weekly_report(
            store.entries(),
            days=7,
            policy_id=str(policy["policy_id"]),
            state_root=store.root,
            hypotheses=store.hypothesis_entries(),
            evidence_triggers=store.evidence_trigger_entries(),
        )
        record = _seal_report(store, WEEKLY_PREFIX, now, content, "weekly")
        state["last_weekly"] = record
        report_events.append(
            _report_event(
                store,
                record,
                event_type="WEEKLY_BRIEF_READY",
                research_status=research_status,
                conclusion="A deterministic seven-day learning brief was sealed.",
            )
        )
    events.extend(report_events)

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
            "last_result": {
                "research_status": research_status,
                "trigger_id": tick_result.get("trigger_id"),
                "experiment_id": tick_result.get("experiment_id"),
                "outcome": tick_result.get("outcome"),
            },
        }
    )
    _write_state(store, state)

    events.sort(key=lambda event: _event_priority(str(event["event_type"])))
    primary = events[0] if events else None
    return {
        "status": "HEARTBEAT_OK",
        "research_status": research_status,
        "tick_preview": tick_preview,
        "tick_result": tick_result,
        "event_type": None if primary is None else primary["event_type"],
        "artifact_path": None if primary is None else primary["artifact_path"],
        "sha256": None if primary is None else primary["sha256"],
        "material_conclusion": None if primary is None else primary["material_conclusion"],
        "pnl_drawdown_evidence": None if primary is None else primary["pnl_drawdown_evidence"],
        "uncertainty": None if primary is None else primary["uncertainty"],
        "next_action": _next_action(tick_result),
        "concept_to_teach": None if primary is None else primary["concept_to_teach"],
        "should_notify_coach": bool(events),
        "events": events,
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
        "uncertainty": str(error),
        "next_action": "INSPECT_FAILURE_ARTIFACT_WITHOUT_ADVANCING_RESEARCH",
        "concept_to_teach": "A control-plane failure is not strategy evidence and cannot justify relaxing a gate.",
    }
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
            "last_result": None,
        }
    state = read_json(path)
    if verify_schema and state.get("schema_version") != "1":
        raise ValueError("heartbeat state schema_version must be 1")
    return state


def _write_state(store: ResearchStore, state: dict[str, Any]) -> None:
    atomic_write_json(_state_path(store), state)


def _state_path(store: ResearchStore) -> Path:
    return store.root / "heartbeat" / "state.json"


def _adopt_existing_reports(store: ResearchStore, state: dict[str, Any]) -> None:
    reports = store.root / "reports"
    if state.get("last_weekly") is None:
        state["last_weekly"] = _latest_report_record(store, reports, WEEKLY_PREFIX, "weekly")
    if state.get("last_monthly") is None:
        state["last_monthly"] = _latest_report_record(store, reports, MONTHLY_PREFIX, "monthly")


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
    if not isinstance(relative, str) or not isinstance(expected_hash, str):
        raise ValueError("heartbeat report record is incomplete")
    path = (store.root / relative).resolve()
    try:
        path.relative_to(store.root)
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


def _material_learning_event(store: ResearchStore, result: dict[str, Any]) -> dict[str, Any]:
    experiment_id = str(result.get("experiment_id"))
    state = store.load_state(experiment_id)
    relative = state.get("artifacts", {}).get("learning")
    if not relative:
        return _integrity_event_for_tick(store, {**result, "reason": "MISSING_SEALED_LEARNING"})
    path = store.root / str(relative)
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
    path = store.root / str(latest["path"])
    review = read_json(path)
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": _relative(store, path),
        "sha256": sha256_file(path),
        "research_status": "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS",
        "material_conclusion": str(review.get("conclusion") or "Evidence review is ready."),
        "pnl_drawdown_evidence": None,
        "uncertainty": "The reviewed discovery window remains excluded from clean OOS.",
        "next_action": "PROPOSE_AT_MOST_ONE_CAUSAL_HYPOTHESIS_FROM_THE_SEALED_REVIEW",
        "concept_to_teach": "A sealed discovery review may justify one hypothesis, not a promotion claim.",
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
    if status == "EVIDENCE_SOURCE_UNBOUND":
        return "CONNECT_ONE_LAWFUL_ONE_WAY_SOURCE_BEFORE_EVIDENCE_START_OR_CLOSE_THE_TRIGGER"
    if status == "EVIDENCE_REVIEW_DUE":
        return "PERFORM_ONE_FROZEN_READ_ONLY_EVIDENCE_REVIEW"
    if status == "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS":
        return "PROPOSE_AT_MOST_ONE_HYPOTHESIS_FROM_SEALED_EVIDENCE"
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
    return str(path.resolve().relative_to(store.root)).replace("\\", "/")


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
