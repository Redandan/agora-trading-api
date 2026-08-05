from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
import shlex
import sys
from pathlib import Path
from typing import Any

from .adapters import (
    ADAPTERS,
    build_run,
    classify_result,
    execute,
    next_action,
    validate_adapter_manifest,
)
from .evidence import (
    MANIFEST_TYPE,
    evidence_progress,
    register_evidence_source_contract,
    seal_daily_evidence,
    seal_deterministic_evidence_review,
    validate_evidence_manifest,
)
from .learning import build_learning
from .hypotheses import (
    build_hypothesis,
    build_imported_hypothesis,
    refresh_readiness,
    select_next,
    sync_hypothesis_record,
)
from .heartbeat import parse_heartbeat_now, record_heartbeat_failure, run_heartbeat_cycle
from .models import ExperimentManifest, Stage, is_terminal_stage, parse_timestamp
from .policy import load_policy, policy_sha256
from .report import monthly_report, weekly_report
from .storage import ResearchStore, atomic_write_json, atomic_write_text, sha256_file
from .waiting import (
    build_evidence_review,
    build_evidence_trigger,
    effective_trigger_status,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_POLICY = Path(__file__).with_name("policy.v3.json")
MAX_OPEN_EVIDENCE_TRIGGERS = 3


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Research-only autonomous trading pipeline")
    result.add_argument(
        "--state-dir",
        type=Path,
        default=Path(os.environ.get("AGORA_RESEARCH_STATE_DIR", REPO_ROOT / ".research-state")),
    )
    result.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("bootstrap")
    commands.add_parser("catalog")
    validate = commands.add_parser("validate-manifest")
    validate.add_argument("manifest", type=Path)
    register = commands.add_parser("register")
    register.add_argument("manifest", type=Path)
    register.add_argument("--hypothesis-id")
    status = commands.add_parser("status")
    status.add_argument("--json", action="store_true")
    publish = commands.add_parser("publish-learning")
    publish.add_argument("experiment_id")
    propose = commands.add_parser("propose-hypothesis")
    propose.add_argument("hypothesis", type=Path)
    hypotheses = commands.add_parser("hypotheses")
    hypotheses.add_argument("--json", action="store_true")
    commands.add_parser("refresh-hypotheses")
    next_hypothesis = commands.add_parser("next-hypothesis")
    next_hypothesis.add_argument("--json", action="store_true")
    link = commands.add_parser("link-hypothesis")
    link.add_argument("hypothesis_id")
    link.add_argument("experiment_id")
    imported = commands.add_parser("import-experiment-hypothesis")
    imported.add_argument("experiment_id")
    imported.add_argument("--hypothesis-id")
    tick = commands.add_parser("tick")
    tick.add_argument("--dry-run", action="store_true")
    heartbeat = commands.add_parser("heartbeat")
    heartbeat.add_argument("--now")
    report = commands.add_parser("weekly-report")
    report.add_argument("--days", type=int, default=7)
    report.add_argument("--output", type=Path)
    monthly = commands.add_parser("monthly-report")
    monthly.add_argument("--days", type=int, default=30)
    monthly.add_argument("--output", type=Path)
    register_trigger = commands.add_parser("register-evidence-trigger")
    register_trigger.add_argument("trigger", type=Path)
    evidence_triggers = commands.add_parser("evidence-triggers")
    evidence_triggers.add_argument("--json", action="store_true")
    commands.add_parser("refresh-evidence-triggers")
    review_trigger = commands.add_parser("review-evidence-trigger")
    review_trigger.add_argument("trigger_id")
    review_trigger.add_argument("review", type=Path)
    validate_evidence = commands.add_parser("validate-evidence-manifest")
    validate_evidence.add_argument("trigger_id")
    validate_evidence.add_argument("manifest", type=Path)
    ingest_evidence = commands.add_parser("ingest-evidence-day")
    ingest_evidence.add_argument("trigger_id")
    ingest_evidence.add_argument("bundle", type=Path)
    register_source = commands.add_parser("register-evidence-source-contract")
    register_source.add_argument("trigger_id")
    register_source.add_argument("contract", type=Path)
    candidate_bundle = commands.add_parser("register-candidate-bundle")
    candidate_bundle.add_argument("bundle", type=Path)
    link_trigger = commands.add_parser("link-evidence-trigger")
    link_trigger.add_argument("trigger_id")
    link_trigger.add_argument("hypothesis_id")
    return result


def load_manifest(path: Path, policy: dict[str, Any]) -> ExperimentManifest:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("manifest must be a JSON object")
    manifest = ExperimentManifest.from_dict(
        value,
        max_variants=int(policy["budget"]["max_candidate_variants"]),
    )
    validate_adapter_manifest(manifest.to_dict())
    return manifest


def checked_evidence_triggers(
    store: ResearchStore,
) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    entries = store.evidence_trigger_entries()
    for trigger, state in entries:
        trigger_id = str(trigger.get("trigger_id"))
        if state.get("trigger_id") != trigger_id:
            raise ValueError("evidence trigger/state identity mismatch")
        trigger_path = store.evidence_trigger_dir(trigger_id) / "trigger.json"
        if state.get("trigger_sha256") != sha256_file(trigger_path):
            raise ValueError(f"registered evidence trigger changed: {trigger_id}")
    return entries


def _candidate_registration_sla(
    state: dict[str, Any],
    *,
    now: datetime,
) -> dict[str, Any] | None:
    ready_text = state.get("evidence_ready_at")
    if not isinstance(ready_text, str) or not ready_text.strip():
        return None
    ready_at = parse_timestamp(ready_text, "evidence_ready_at")
    deadline = ready_at + timedelta(hours=24)
    deadline_text = deadline.isoformat(timespec="seconds").replace("+00:00", "Z")
    recorded_status = state.get("candidate_lead_time_sla")
    recorded_seconds = state.get("candidate_lead_time_seconds")
    if recorded_status in {"PASS", "BREACH"} and isinstance(recorded_seconds, int):
        return {
            "status": recorded_status,
            "deadline": deadline_text,
            "lead_time_seconds": recorded_seconds,
            "seconds_remaining": None,
        }
    seconds_remaining = int((deadline - now).total_seconds())
    return {
        "status": (
            "PENDING_WITHIN_SLA"
            if seconds_remaining >= 0
            else "BREACH_PENDING_REGISTRATION"
        ),
        "deadline": deadline_text,
        "lead_time_seconds": None,
        "seconds_remaining": seconds_remaining,
    }


def status_payload(
    store: ResearchStore,
    policy: dict[str, Any],
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    entries = store.entries()
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    capture_max_lag_seconds = int(
        policy.get("evidence", {}).get("capture_max_lag_seconds", 21600)
    )
    return {
        "state_dir": str(store.root),
        "experiments": [
            {
                "experiment_id": manifest["experiment_id"],
                "title": manifest["title"],
                "adapter": manifest["adapter"],
                "stage": state["stage"],
                "outcome": state.get("outcome"),
                "updated_at": state["updated_at"],
            }
            for manifest, state in entries
        ],
        "evidence_triggers": [
            {
                "trigger_id": trigger["trigger_id"],
                "title": trigger["title"],
                "status": effective_trigger_status(state, now=current),
                "next_review_at": state.get("next_review_at"),
                "review_count": state.get("review_count", 0),
                "evidence_ready_at": state.get("evidence_ready_at"),
                "candidate_registration_sla": _candidate_registration_sla(
                    state,
                    now=current,
                ),
                "diagnostic_summary": (state.get("detail") or {}).get(
                    "diagnostic_summary"
                ),
                "progress": evidence_progress(
                    store,
                    trigger,
                    state,
                    now=current,
                    capture_max_lag_seconds=capture_max_lag_seconds,
                ),
            }
            for trigger, state in checked_evidence_triggers(store)
        ],
    }


def select_actionable(store: ResearchStore) -> tuple[dict[str, Any], dict[str, Any]] | None:
    actionable = [
        pair
        for pair in store.entries()
        if pair[1]["stage"] in {Stage.PREREGISTERED.value, Stage.OOS_READY.value}
    ]
    if not actionable:
        return None
    actionable.sort(key=lambda pair: (pair[1]["created_at"], pair[0]["experiment_id"]))
    return actionable[0]


def sync_linked_hypothesis(store: ResearchStore, state: dict[str, Any]) -> None:
    hypothesis_id = state.get("hypothesis_id")
    if not hypothesis_id:
        return
    record = store.load_hypothesis(str(hypothesis_id))
    store.save_hypothesis(sync_hypothesis_record(record, state))
    store.append_hypothesis_event(
        str(hypothesis_id),
        "EXPERIMENT_SYNCED",
        {
            "experiment_id": state["experiment_id"],
            "stage": state["stage"],
            "outcome": state.get("outcome"),
        },
    )


def link_hypothesis(
    store: ResearchStore,
    hypothesis_id: str,
    experiment_id: str,
) -> dict[str, Any]:
    record = store.load_hypothesis(hypothesis_id)
    linked_experiment = record.get("experiment_id")
    if linked_experiment and linked_experiment != experiment_id:
        raise ValueError(f"hypothesis already linked to {linked_experiment}")
    state = store.load_state(experiment_id)
    linked_hypothesis = state.get("hypothesis_id")
    if linked_hypothesis and linked_hypothesis != hypothesis_id:
        raise ValueError(f"experiment already linked to {linked_hypothesis}")
    if not linked_experiment and record.get("status") != "READY":
        raise ValueError(f"hypothesis is not executable: {record.get('status')}")
    state["hypothesis_id"] = hypothesis_id
    store.save_state(state)
    sync_linked_hypothesis(store, state)
    store.append_event(experiment_id, "HYPOTHESIS_LINKED", {"hypothesis_id": hypothesis_id})
    return store.load_hypothesis(hypothesis_id)


def register_candidate_bundle(
    store: ResearchStore,
    policy: dict[str, Any],
    value: dict[str, Any],
    *,
    current_policy_hash: str,
    now: datetime | None = None,
) -> dict[str, Any]:
    required = {
        "schema_version",
        "trigger_id",
        "hypothesis",
        "manifest",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"candidate bundle missing fields: {', '.join(missing)}")
    unknown = sorted(set(value).difference(required))
    if unknown:
        raise ValueError(f"candidate bundle has unknown fields: {', '.join(unknown)}")
    if value["schema_version"] != "1":
        raise ValueError("candidate bundle schema_version must be 1")
    authorization = str(value["authorization"])
    if authorization != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ValueError("candidate bundle authorization must remain research-only")
    trigger_id = str(value["trigger_id"])
    hypothesis_value = value["hypothesis"]
    manifest_value = value["manifest"]
    if not isinstance(hypothesis_value, dict) or not isinstance(manifest_value, dict):
        raise ValueError("candidate bundle hypothesis and manifest must be objects")

    trigger = store.load_evidence_trigger(trigger_id)
    trigger_state = store.load_evidence_trigger_state(trigger_id)
    trigger_path = store.evidence_trigger_dir(trigger_id) / "trigger.json"
    if trigger_state.get("trigger_sha256") != sha256_file(trigger_path):
        raise ValueError("registered evidence trigger changed")
    if effective_trigger_status(trigger_state) == "CLOSED":
        linked = trigger_state.get("hypothesis_id")
        if linked and linked == hypothesis_value.get("hypothesis_id"):
            hypothesis = store.load_hypothesis(str(linked))
            experiment_id = hypothesis.get("experiment_id")
            if experiment_id:
                return {
                    "status": "CANDIDATE_BUNDLE_ALREADY_REGISTERED",
                    "trigger_id": trigger_id,
                    "hypothesis_id": linked,
                    "experiment_id": experiment_id,
                    "lead_time_seconds": trigger_state.get("candidate_lead_time_seconds"),
                    "lead_time_sla": trigger_state.get("candidate_lead_time_sla"),
                }
        raise ValueError("evidence trigger is already closed")
    if effective_trigger_status(trigger_state) != "READY_FOR_HYPOTHESIS":
        raise ValueError("evidence trigger is not ready for a candidate bundle")
    reviews = trigger_state.get("reviews")
    if not isinstance(reviews, list) or not reviews:
        raise ValueError("ready evidence trigger has no sealed review")
    latest_review_ref = reviews[-1]
    review_path = (store.root / str(latest_review_ref.get("path", ""))).resolve()
    try:
        review_path.relative_to(store.root)
    except ValueError as error:
        raise ValueError("evidence review path escapes research state") from error
    if not review_path.is_file() or sha256_file(review_path) != latest_review_ref.get("sha256"):
        raise ValueError("sealed evidence review changed or disappeared")
    review = json.loads(review_path.read_text(encoding="utf-8"))
    if review.get("outcome") != "READY_FOR_HYPOTHESIS":
        raise ValueError("latest evidence review is not ready")
    verified = (trigger_state.get("detail") or {}).get("verified_evidence")
    if not isinstance(verified, list) or len(verified) != 1:
        raise ValueError("ready evidence trigger lacks one verified evidence manifest")
    reverified = verify_review_artifacts(store, trigger, review)
    if reverified != verified:
        raise ValueError("ready evidence verification no longer matches sealed trigger state")
    if int(verified[0].get("observation_count", 0)) < int(trigger["minimum_observations"]):
        raise ValueError("verified evidence observation count is below the trigger minimum")

    hypothesis = build_hypothesis(
        hypothesis_value,
        available_capabilities=set(ADAPTERS),
    )
    expected_source = f"EVIDENCE_TRIGGER:{trigger_id}"
    if hypothesis["source"] != expected_source:
        raise ValueError("candidate hypothesis source must name the ready evidence trigger")
    if hypothesis["status"] != "READY":
        raise ValueError(f"candidate hypothesis is not executable: {hypothesis['status']}")
    required_metrics = set(str(item) for item in policy["objective"]["required_metrics"])
    if not required_metrics.issubset(set(hypothesis["expected_metrics"])):
        raise ValueError("candidate hypothesis omits policy-required performance metrics")

    manifest = ExperimentManifest.from_dict(
        manifest_value,
        max_variants=int(policy["budget"]["max_candidate_variants"]),
    )
    validate_adapter_manifest(manifest.to_dict())
    hypothesis_id = str(hypothesis["hypothesis_id"])
    if manifest.experiment_id != hypothesis_id:
        raise ValueError("candidate experiment_id must equal hypothesis_id")
    if manifest.hypothesis_source != f"HYPOTHESIS:{hypothesis_id}":
        raise ValueError("candidate manifest hypothesis_source must bind the hypothesis id")
    for field, actual, expected in (
        ("thesis", manifest.thesis, hypothesis["thesis"]),
        ("economic_rationale", manifest.economic_rationale, hypothesis["economic_rationale"]),
        ("parent", manifest.parent, hypothesis["parent"]),
        ("adapter", manifest.adapter, hypothesis["required_capability"]),
    ):
        if actual != expected:
            raise ValueError(f"candidate manifest {field} does not match the hypothesis")
    if manifest.objective.get("primary_metric") != policy["objective"]["primary_metric"]:
        raise ValueError("candidate manifest primary metric must match policy")
    required_constraints = set(str(item) for item in policy["objective"]["constraints"])
    manifest_constraints = set(str(item) for item in manifest.objective.get("constraints", []))
    if not required_constraints.issubset(manifest_constraints):
        raise ValueError("candidate manifest omits policy constraints")
    hypothesis_created = parse_timestamp(hypothesis["created_at"], "hypothesis created_at")
    manifest_created = parse_timestamp(manifest.created_at, "manifest created_at")
    reviewed_at = parse_timestamp(str(review["reviewed_at"]), "evidence reviewed_at")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if hypothesis_created < reviewed_at or manifest_created != hypothesis_created:
        raise ValueError("candidate hypothesis/manifest timestamps must follow the sealed review")
    if hypothesis_created > current + timedelta(minutes=5):
        raise ValueError("candidate hypothesis created_at must not be in the future")

    existing_hypotheses = store.hypothesis_entries()
    existing = next(
        (record for record in existing_hypotheses if record.get("hypothesis_id") == hypothesis_id),
        None,
    )
    if existing is not None and existing.get("fingerprint") != hypothesis["fingerprint"]:
        raise ValueError("candidate hypothesis id already exists with different content")
    if existing is not None and existing.get("status") != "READY":
        raise ValueError("partially registered candidate hypothesis is not executable")
    duplicate = next(
        (
            record
            for record in existing_hypotheses
            if record.get("fingerprint") == hypothesis["fingerprint"]
            and record.get("hypothesis_id") != hypothesis_id
        ),
        None,
    )
    if duplicate:
        raise ValueError(f"duplicate hypothesis fingerprint: {duplicate['hypothesis_id']}")
    experiment_path = store.experiment_dir(manifest.experiment_id)
    if experiment_path.exists() and existing is None:
        raise ValueError("candidate experiment id already exists without the bundled hypothesis")

    if not experiment_path.exists():
        active = sum(
            item_state["stage"] in {Stage.PREREGISTERED.value, Stage.OOS_READY.value}
            for _, item_state in store.entries()
        )
        maximum = int(policy["budget"]["max_active_experiments"])
        if active >= maximum:
            raise ValueError(f"active experiment limit reached: {active}/{maximum}")

    if existing is None:
        store.register_hypothesis(
            hypothesis,
            max_new_per_cycle=int(policy["budget"]["max_new_hypotheses_per_cycle"]),
        )
    else:
        hypothesis = existing
    if not experiment_path.exists():
        experiment_state = store.register(manifest, current_policy_hash)
        experiment_state["hypothesis_id"] = hypothesis_id
        store.save_state(experiment_state)
        sync_linked_hypothesis(store, experiment_state)
    else:
        experiment_state = store.load_state(manifest.experiment_id)
        if experiment_state.get("hypothesis_id") != hypothesis_id:
            raise ValueError("candidate experiment is not linked to the bundled hypothesis")

    lead_time_seconds = max(0, int((current - reviewed_at).total_seconds()))
    trigger_state["status"] = "CLOSED"
    trigger_state["hypothesis_id"] = hypothesis_id
    trigger_state["next_review_at"] = None
    trigger_state["candidate_lead_time_seconds"] = lead_time_seconds
    trigger_state["candidate_lead_time_sla"] = "PASS" if lead_time_seconds <= 86400 else "BREACH"
    store.save_evidence_trigger_state(trigger_state)
    store.append_evidence_trigger_event(
        trigger_id,
        "CANDIDATE_BUNDLE_REGISTERED",
        {
            "hypothesis_id": hypothesis_id,
            "experiment_id": manifest.experiment_id,
            "lead_time_seconds": lead_time_seconds,
            "lead_time_sla": trigger_state["candidate_lead_time_sla"],
        },
    )
    return {
        "status": "CANDIDATE_BUNDLE_REGISTERED",
        "trigger_id": trigger_id,
        "hypothesis_id": hypothesis_id,
        "experiment_id": manifest.experiment_id,
        "experiment_stage": experiment_state["stage"],
        "lead_time_seconds": lead_time_seconds,
        "lead_time_sla": trigger_state["candidate_lead_time_sla"],
        "authorization": authorization,
    }


def verify_review_artifacts(
    store: ResearchStore,
    trigger: dict[str, Any],
    review: dict[str, Any],
) -> list[dict[str, Any]]:
    verified_manifests: list[dict[str, Any]] = []
    for artifact in review["evidence_artifacts"]:
        path = (store.root / artifact["path"]).resolve()
        try:
            path.relative_to(store.root)
        except ValueError as error:
            raise ValueError("evidence artifact must stay inside research state") from error
        if not path.is_file():
            raise ValueError(f"evidence artifact does not exist: {artifact['path']}")
        if sha256_file(path) != artifact["sha256"]:
            raise ValueError(f"evidence artifact hash mismatch: {artifact['path']}")
        if artifact.get("artifact_type") == MANIFEST_TYPE:
            value = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("forward evidence manifest must be a JSON object")
            verified_manifests.append(validate_evidence_manifest(value, trigger, store))
    if review["outcome"] == "READY_FOR_HYPOTHESIS" and len(verified_manifests) != 1:
        raise ValueError("READY_FOR_HYPOTHESIS requires one verified evidence manifest")
    return verified_manifests


def evidence_wait_payload(
    store: ResearchStore,
    *,
    capture_max_lag_seconds: int,
    now: datetime | None = None,
) -> dict[str, Any] | None:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    entries = checked_evidence_triggers(store)
    ready = [
        (trigger, state)
        for trigger, state in entries
        if effective_trigger_status(state, now=current) == "READY_FOR_HYPOTHESIS"
    ]
    if ready:
        ready.sort(key=lambda pair: (pair[1]["updated_at"], pair[0]["trigger_id"]))
        trigger, state = ready[0]
        return {
            "status": "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS",
            "trigger_id": trigger["trigger_id"],
            "title": trigger["title"],
            "review_count": state.get("review_count", 0),
            "evidence_ready_at": state.get("evidence_ready_at"),
            "diagnostic_summary": (state.get("detail") or {}).get(
                "diagnostic_summary"
            ),
            "authorization": trigger["authorization"],
        }
    due = [
        (trigger, state, progress)
        for trigger, state in entries
        if effective_trigger_status(state, now=current) == "REVIEW_DUE"
        for progress in [
            evidence_progress(
                store,
                trigger,
                state,
                now=current,
                capture_max_lag_seconds=capture_max_lag_seconds,
            )
        ]
        if progress["status"] == "COMPLETE"
    ]
    if due:
        due.sort(key=lambda item: (item[1]["next_review_at"], item[0]["trigger_id"]))
        trigger, state, progress = due[0]
        return {
            "status": "EVIDENCE_REVIEW_DUE",
            "trigger_id": trigger["trigger_id"],
            "title": trigger["title"],
            "next_review_at": state["next_review_at"],
            "source": trigger["source"],
            "evidence_progress": progress,
            "authorization": trigger["authorization"],
        }
    waiting = [
        (trigger, state)
        for trigger, state in entries
        if effective_trigger_status(state, now=current) in {"WAITING", "REVIEW_DUE"}
    ]
    if waiting:
        waiting.sort(key=lambda pair: (pair[1]["next_review_at"], pair[0]["trigger_id"]))
        trigger, state = waiting[0]
        progress = evidence_progress(
            store,
            trigger,
            state,
            now=current,
            capture_max_lag_seconds=capture_max_lag_seconds,
        )
        if progress["status"] in {"SOURCE_UNBOUND", "MISSED_CAPTURE_WINDOW"}:
            return {
                "status": (
                    "EVIDENCE_SOURCE_UNBOUND"
                    if progress["status"] == "SOURCE_UNBOUND"
                    else "EVIDENCE_CAPTURE_MISSED"
                ),
                "trigger_id": trigger["trigger_id"],
                "title": trigger["title"],
                "next_review_at": state["next_review_at"],
                "evidence_progress": progress,
                "authorization": trigger["authorization"],
            }
        return {
            "status": "WAITING_FOR_EVIDENCE",
            "trigger_id": trigger["trigger_id"],
            "title": trigger["title"],
            "next_review_at": state["next_review_at"],
            "minimum_observations": trigger["minimum_observations"],
            "observation_unit": trigger["observation_unit"],
            "evidence_progress": progress,
            "authorization": trigger["authorization"],
        }
    return None


def run_tick(
    store: ResearchStore,
    policy: dict[str, Any],
    *,
    dry_run: bool,
    current_policy_hash: str,
    now: datetime | None = None,
) -> dict[str, Any]:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    selected = select_actionable(store)
    if selected is None:
        hypotheses = store.hypothesis_entries()
        next_hypothesis = select_next(hypotheses)
        blocked = [
            record
            for record in hypotheses
            if record.get("status") in {"BLOCKED_CAPABILITY", "BLOCKED_DATA"}
        ]
        if next_hypothesis:
            return {
                "status": "READY_HYPOTHESIS_REQUIRES_FROZEN_MANIFEST",
                "hypothesis_id": next_hypothesis["hypothesis_id"],
                "title": next_hypothesis["title"],
                "required_capability": next_hypothesis["required_capability"],
                "authorization": next_hypothesis["authorization"],
            }
        evidence_wait = evidence_wait_payload(
            store,
            capture_max_lag_seconds=int(
                policy.get("evidence", {}).get("capture_max_lag_seconds", 21600)
            ),
            now=current,
        )
        if evidence_wait:
            if evidence_wait["status"] == "EVIDENCE_REVIEW_DUE":
                if dry_run:
                    return {
                        **evidence_wait,
                        "status": "DRY_RUN",
                        "action": "BUILD_DETERMINISTIC_FORWARD_EVIDENCE_REVIEW",
                    }
                trigger_id = str(evidence_wait["trigger_id"])
                return seal_deterministic_evidence_review(
                    store,
                    store.load_evidence_trigger(trigger_id),
                    store.load_evidence_trigger_state(trigger_id),
                    now=current,
                    capture_max_lag_seconds=int(
                        policy.get("evidence", {}).get("capture_max_lag_seconds", 21600)
                    ),
                )
            return evidence_wait
        return {
            "status": "IDLE_NO_ACTIONABLE_EXPERIMENT",
            "closed_experiments": sum(
                state.get("stage") == Stage.CLOSED.value
                for _, state in store.entries()
            ),
            "blocked_hypotheses": len(blocked),
            "codex_next_action": (
                "REVIEW_BLOCKED_DEPENDENCIES"
                if blocked
                else "FORMULATE_ONE_CAUSAL_HYPOTHESIS_OR_RECORD_WAIT_TRIGGER"
            ),
        }
    manifest, state = selected
    manifest_path = store.experiment_dir(state["experiment_id"]) / "manifest.json"
    boundary_error = None
    if state.get("policy_sha256") != current_policy_hash:
        boundary_error = "POLICY_HASH_CHANGED"
    elif state.get("manifest_sha256") != sha256_file(manifest_path):
        boundary_error = "REGISTERED_MANIFEST_CHANGED"
    if boundary_error:
        if not dry_run:
            state["stage"] = Stage.BLOCKED.value
            state["outcome"] = boundary_error
            store.save_state(state)
            store.append_event(
                state["experiment_id"], "BLOCKED", {"reason": boundary_error}
            )
        return {
            "status": "BLOCKED",
            "experiment_id": state["experiment_id"],
            "reason": boundary_error,
            "dry_run": dry_run,
        }
    action = next_action(manifest, state)
    if action is None:
        state["stage"] = Stage.BLOCKED.value
        state["outcome"] = "NO_EXECUTABLE_ACTION"
        if not dry_run:
            store.save_state(state)
            store.append_event(state["experiment_id"], "BLOCKED", {"reason": state["outcome"]})
        return {
            "status": "BLOCKED",
            "experiment_id": state["experiment_id"],
            "reason": state["outcome"],
            "dry_run": dry_run,
        }
    run = build_run(REPO_ROOT, store.artifact_dir(state["experiment_id"]), manifest, state)
    preview = {
        "status": "DRY_RUN" if dry_run else "RUNNING",
        "experiment_id": state["experiment_id"],
        "action": action,
        "command": shlex.join(run.command),
        "artifact": str(run.artifact_path),
    }
    if dry_run:
        return preview
    store.append_event(state["experiment_id"], "RUN_STARTED", {"action": action})
    try:
        returncode, result = execute(
            run,
            REPO_ROOT,
            int(policy["budget"]["runner_timeout_seconds"]),
        )
        stage, outcome = classify_result(manifest, result)
        state["stage"] = stage
        state["outcome"] = outcome
        state["run_count"] = int(state.get("run_count", 0)) + 1
        state["artifacts"][action] = str(run.artifact_path.relative_to(store.root))
        if is_terminal_stage(stage):
            learning_path = store.artifact_dir(state["experiment_id"]) / "learning.json"
            if learning_path.exists():
                raise ValueError(f"sealed learning artifact already exists: {learning_path}")
            atomic_write_json(learning_path, build_learning(manifest, result, outcome))
            state["artifacts"]["learning"] = str(learning_path.relative_to(store.root))
        state["detail"] = {"runner_exit_code": returncode}
        store.save_state(state)
        try:
            sync_linked_hypothesis(store, state)
        except Exception as sync_error:
            store.append_event(
                state["experiment_id"],
                "HYPOTHESIS_SYNC_FAILED",
                {"type": type(sync_error).__name__, "message": str(sync_error)},
            )
        store.append_event(
            state["experiment_id"],
            "RUN_FINISHED",
            {"action": action, "stage": stage, "outcome": outcome, "exit_code": returncode},
        )
        return {
            **preview,
            "status": stage,
            "outcome": outcome,
            "runner_exit_code": returncode,
        }
    except Exception as error:
        state["stage"] = Stage.FAILED.value
        state["outcome"] = "INFRASTRUCTURE_FAILURE"
        state["detail"] = {"type": type(error).__name__, "message": str(error)}
        store.save_state(state)
        store.append_event(state["experiment_id"], "RUN_FAILED", state["detail"])
        raise


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        policy = load_policy(args.policy)
        store = ResearchStore(
            args.state_dir,
            lock_stale_seconds=int(policy["budget"]["lock_stale_seconds"]),
        )
        if args.command == "bootstrap":
            store.bootstrap()
            print(json.dumps({"status": "BOOTSTRAPPED", "state_dir": str(store.root)}))
            return 0
        if args.command == "catalog":
            print(
                json.dumps(
                    {
                        key: {
                            "runner": value.runner,
                            "initial_action": value.initial_action,
                            "supports_oos": value.supports_oos,
                            "selection_cutoff": value.selection_cutoff,
                            "candidate_variants": value.candidate_variants,
                            "description": value.description,
                        }
                        for key, value in ADAPTERS.items()
                    },
                    indent=2,
                )
            )
            return 0
        if args.command == "validate-manifest":
            manifest = load_manifest(args.manifest, policy)
            print(
                json.dumps(
                    {"status": "VALID", "experiment_id": manifest.experiment_id},
                    ensure_ascii=False,
                )
            )
            return 0
        if args.command == "register":
            manifest = load_manifest(args.manifest, policy)
            with store.lock():
                if args.hypothesis_id:
                    hypothesis = store.load_hypothesis(args.hypothesis_id)
                    if hypothesis.get("status") != "READY":
                        raise ValueError(
                            f"hypothesis is not executable: {hypothesis.get('status')}"
                        )
                    if hypothesis.get("experiment_id"):
                        raise ValueError("hypothesis is already linked to an experiment")
                active = sum(
                    state["stage"] in {Stage.PREREGISTERED.value, Stage.OOS_READY.value}
                    for _, state in store.entries()
                )
                maximum = int(policy["budget"]["max_active_experiments"])
                if active >= maximum:
                    raise ValueError(
                        f"active experiment limit reached: {active}/{maximum}"
                    )
                state = store.register(manifest, policy_sha256(args.policy))
                if args.hypothesis_id:
                    state["hypothesis_id"] = args.hypothesis_id
                    store.save_state(state)
                    sync_linked_hypothesis(store, state)
            print(json.dumps({"status": "REGISTERED", "state": state}, ensure_ascii=False))
            return 0
        if args.command == "register-candidate-bundle":
            value = json.loads(args.bundle.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("candidate bundle must be a JSON object")
            with store.lock():
                result = register_candidate_bundle(
                    store,
                    policy,
                    value,
                    current_policy_hash=policy_sha256(args.policy),
                )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "propose-hypothesis":
            value = json.loads(args.hypothesis.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("hypothesis must be a JSON object")
            record = build_hypothesis(value, available_capabilities=set(ADAPTERS))
            with store.lock():
                store.register_hypothesis(
                    record,
                    max_new_per_cycle=int(policy["budget"]["max_new_hypotheses_per_cycle"]),
                )
            print(json.dumps({"status": "HYPOTHESIS_PROPOSED", "hypothesis": record}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "hypotheses":
            records = store.hypothesis_entries()
            if args.json:
                print(json.dumps({"hypotheses": records}, ensure_ascii=False, indent=2))
            elif not records:
                print("hypotheses=0")
            else:
                for record in records:
                    print(
                        f"{record['hypothesis_id']} status={record['status']} "
                        f"score={record['rank_score']} capability={record['required_capability']}"
                    )
            return 0
        if args.command == "register-evidence-trigger":
            value = json.loads(args.trigger.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("evidence trigger must be a JSON object")
            record = build_evidence_trigger(value)
            with store.lock():
                open_count = sum(
                    effective_trigger_status(state) != "CLOSED"
                    for _, state in checked_evidence_triggers(store)
                )
                if open_count >= MAX_OPEN_EVIDENCE_TRIGGERS:
                    raise ValueError(
                        f"open evidence trigger limit reached: "
                        f"{open_count}/{MAX_OPEN_EVIDENCE_TRIGGERS}"
                    )
                state = store.register_evidence_trigger(record)
            print(
                json.dumps(
                    {"status": "EVIDENCE_TRIGGER_REGISTERED", "trigger": record, "state": state},
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        if args.command == "evidence-triggers":
            records = [
                {
                    **trigger,
                    "state": {**state, "effective_status": effective_trigger_status(state)},
                }
                for trigger, state in checked_evidence_triggers(store)
            ]
            if args.json:
                print(json.dumps({"evidence_triggers": records}, ensure_ascii=False, indent=2))
            elif not records:
                print("evidence_triggers=0")
            else:
                for record in records:
                    print(
                        f"{record['trigger_id']} status={record['state']['effective_status']} "
                        f"next_review_at={record['state'].get('next_review_at') or 'NONE'}"
                    )
            return 0
        if args.command == "refresh-evidence-triggers":
            changed = []
            with store.lock():
                for trigger, state in checked_evidence_triggers(store):
                    before = str(state["status"])
                    after = effective_trigger_status(state)
                    if after != before:
                        state["status"] = after
                        store.save_evidence_trigger_state(state)
                        store.append_evidence_trigger_event(
                            trigger["trigger_id"],
                            "EVIDENCE_TRIGGER_REFRESHED",
                            {"before": before, "after": after},
                        )
                        changed.append(
                            {"trigger_id": trigger["trigger_id"], "before": before, "after": after}
                        )
            print(
                json.dumps(
                    {"status": "EVIDENCE_TRIGGERS_REFRESHED", "changed": changed},
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        if args.command == "review-evidence-trigger":
            value = json.loads(args.review.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("evidence review must be a JSON object")
            review = build_evidence_review(value)
            if review["trigger_id"] != args.trigger_id:
                raise ValueError("evidence review trigger_id mismatch")
            with store.lock():
                trigger = store.load_evidence_trigger(args.trigger_id)
                state = store.load_evidence_trigger_state(args.trigger_id)
                trigger_path = store.evidence_trigger_dir(args.trigger_id) / "trigger.json"
                if state.get("trigger_sha256") != sha256_file(trigger_path):
                    raise ValueError("registered evidence trigger changed")
                if parse_timestamp(review["reviewed_at"], "reviewed_at") < parse_timestamp(
                    str(state["created_at"]), "trigger state created_at"
                ):
                    raise ValueError("reviewed_at must not precede trigger registration")
                if (
                    effective_trigger_status(state) != "REVIEW_DUE"
                    and review["outcome"] != "CLOSE"
                ):
                    raise ValueError("evidence trigger is not due for review")
                verified_manifests = verify_review_artifacts(store, trigger, review)
                sequence = int(state.get("review_count", 0)) + 1
                review_path = store.evidence_review_dir(args.trigger_id) / f"{sequence:03d}.json"
                if review_path.exists():
                    raise ValueError(f"sealed evidence review already exists: {review_path}")
                atomic_write_json(review_path, review)
                outcome = review["outcome"]
                state["review_count"] = sequence
                state.setdefault("reviews", []).append(
                    {
                        "path": str(review_path.relative_to(store.root)),
                        "sha256": sha256_file(review_path),
                        "outcome": outcome,
                    }
                )
                state["detail"] = {
                    "conclusion": review["conclusion"],
                    "verified_evidence": verified_manifests,
                }
                if outcome == "WAIT":
                    state["status"] = "WAITING"
                    state["next_review_at"] = review["next_review_at"]
                elif outcome == "READY_FOR_HYPOTHESIS":
                    state["status"] = "READY_FOR_HYPOTHESIS"
                    state["next_review_at"] = None
                    state["evidence_ready_at"] = review["reviewed_at"]
                else:
                    state["status"] = "CLOSED"
                    state["next_review_at"] = None
                store.save_evidence_trigger_state(state)
                store.append_evidence_trigger_event(
                    trigger["trigger_id"],
                    "EVIDENCE_REVIEW_RECORDED",
                    {"sequence": sequence, "outcome": outcome, "status": state["status"]},
                )
            print(
                json.dumps(
                    {"status": "EVIDENCE_REVIEW_RECORDED", "trigger": trigger, "state": state},
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        if args.command == "validate-evidence-manifest":
            trigger = store.load_evidence_trigger(args.trigger_id)
            value = json.loads(args.manifest.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("forward evidence manifest must be a JSON object")
            summary = validate_evidence_manifest(value, trigger, store)
            print(
                json.dumps(
                    {"status": "EVIDENCE_MANIFEST_VALID", "summary": summary},
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        if args.command == "ingest-evidence-day":
            value = json.loads(args.bundle.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("daily evidence bundle must be a JSON object")
            with store.lock():
                trigger = store.load_evidence_trigger(args.trigger_id)
                state = store.load_evidence_trigger_state(args.trigger_id)
                trigger_path = store.evidence_trigger_dir(args.trigger_id) / "trigger.json"
                if state.get("trigger_sha256") != sha256_file(trigger_path):
                    raise ValueError("registered evidence trigger changed")
                if effective_trigger_status(state) not in {"WAITING", "REVIEW_DUE"}:
                    raise ValueError("evidence trigger does not accept daily observations")
                result = seal_daily_evidence(
                    store,
                    trigger,
                    state,
                    value,
                    capture_max_lag_seconds=int(
                        policy.get("evidence", {}).get("capture_max_lag_seconds", 21600)
                    ),
                )
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0
        if args.command == "register-evidence-source-contract":
            value = json.loads(args.contract.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("evidence source contract must be a JSON object")
            with store.lock():
                trigger = store.load_evidence_trigger(args.trigger_id)
                state = store.load_evidence_trigger_state(args.trigger_id)
                trigger_path = store.evidence_trigger_dir(args.trigger_id) / "trigger.json"
                if state.get("trigger_sha256") != sha256_file(trigger_path):
                    raise ValueError("registered evidence trigger changed")
                if effective_trigger_status(state) != "WAITING":
                    raise ValueError("evidence source contract requires a waiting trigger")
                reference = register_evidence_source_contract(
                    store,
                    trigger,
                    state,
                    value,
                )
            print(
                json.dumps(
                    {"status": "EVIDENCE_SOURCE_CONTRACT_REGISTERED", "source": reference},
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        if args.command == "link-evidence-trigger":
            with store.lock():
                trigger = store.load_evidence_trigger(args.trigger_id)
                state = store.load_evidence_trigger_state(args.trigger_id)
                hypothesis = store.load_hypothesis(args.hypothesis_id)
                trigger_path = store.evidence_trigger_dir(args.trigger_id) / "trigger.json"
                if state.get("trigger_sha256") != sha256_file(trigger_path):
                    raise ValueError("registered evidence trigger changed")
                if effective_trigger_status(state) != "READY_FOR_HYPOTHESIS":
                    raise ValueError("evidence trigger is not ready for hypothesis linkage")
                if hypothesis.get("source") != f"EVIDENCE_TRIGGER:{args.trigger_id}":
                    raise ValueError("hypothesis source does not name the evidence trigger")
                state["status"] = "CLOSED"
                state["hypothesis_id"] = args.hypothesis_id
                state["next_review_at"] = None
                store.save_evidence_trigger_state(state)
                store.append_evidence_trigger_event(
                    trigger["trigger_id"],
                    "EVIDENCE_TRIGGER_LINKED",
                    {"hypothesis_id": args.hypothesis_id},
                )
            print(
                json.dumps(
                    {
                        "status": "EVIDENCE_TRIGGER_LINKED",
                        "trigger_id": args.trigger_id,
                        "hypothesis_id": args.hypothesis_id,
                    },
                    ensure_ascii=False,
                )
            )
            return 0
        if args.command == "refresh-hypotheses":
            changed = []
            with store.lock():
                for record in store.hypothesis_entries():
                    before = record["status"]
                    refreshed = refresh_readiness(
                        record,
                        available_capabilities=set(ADAPTERS),
                    )
                    if refreshed["status"] != before:
                        store.save_hypothesis(refreshed)
                        store.append_hypothesis_event(
                            refreshed["hypothesis_id"],
                            "READINESS_REFRESHED",
                            {"before": before, "after": refreshed["status"]},
                        )
                        changed.append(
                            {
                                "hypothesis_id": refreshed["hypothesis_id"],
                                "before": before,
                                "after": refreshed["status"],
                            }
                        )
            print(json.dumps({"status": "HYPOTHESES_REFRESHED", "changed": changed}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "next-hypothesis":
            selected = select_next(store.hypothesis_entries())
            payload = (
                {"status": "NO_READY_HYPOTHESIS"}
                if selected is None
                else {"status": "NEXT_HYPOTHESIS", "hypothesis": selected}
            )
            if args.json:
                print(json.dumps(payload, ensure_ascii=False, indent=2))
            elif selected is None:
                print("NO_READY_HYPOTHESIS")
            else:
                print(
                    f"{selected['hypothesis_id']} score={selected['rank_score']} "
                    f"title={selected['title']}"
                )
            return 0
        if args.command == "link-hypothesis":
            with store.lock():
                record = link_hypothesis(store, args.hypothesis_id, args.experiment_id)
            print(json.dumps({"status": "HYPOTHESIS_LINKED", "hypothesis": record}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "import-experiment-hypothesis":
            with store.lock():
                manifest = store.load_manifest(args.experiment_id)
                state = store.load_state(args.experiment_id)
                if state.get("hypothesis_id"):
                    raise ValueError("experiment already has a linked hypothesis")
                hypothesis_id = args.hypothesis_id or args.experiment_id
                record = build_imported_hypothesis(hypothesis_id, manifest, state)
                store.register_hypothesis(
                    record,
                    max_new_per_cycle=int(policy["budget"]["max_new_hypotheses_per_cycle"]),
                    enforce_cycle_budget=False,
                )
                state["hypothesis_id"] = hypothesis_id
                store.save_state(state)
                store.append_event(
                    args.experiment_id,
                    "HYPOTHESIS_IMPORTED",
                    {"hypothesis_id": hypothesis_id},
                )
            print(json.dumps({"status": "EXPERIMENT_HYPOTHESIS_IMPORTED", "hypothesis": record}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "status":
            payload = status_payload(store, policy)
            if args.json:
                print(json.dumps(payload, ensure_ascii=False, indent=2))
            else:
                print(f"state_dir={payload['state_dir']}")
                if not payload["experiments"]:
                    print("experiments=0")
                for experiment in payload["experiments"]:
                    print(
                        f"{experiment['experiment_id']} stage={experiment['stage']} "
                        f"outcome={experiment['outcome'] or 'PENDING'} adapter={experiment['adapter']}"
                    )
                for trigger in payload["evidence_triggers"]:
                    print(
                        f"evidence-trigger {trigger['trigger_id']} status={trigger['status']} "
                        f"next_review_at={trigger['next_review_at'] or 'NONE'}"
                    )
            return 0
        if args.command == "publish-learning":
            with store.lock():
                manifest = store.load_manifest(args.experiment_id)
                state = store.load_state(args.experiment_id)
                if not is_terminal_stage(str(state.get("stage"))):
                    raise ValueError("learning can only be published for a terminal experiment")
                if "learning" in state.get("artifacts", {}):
                    raise ValueError("sealed learning artifact already exists")
                source_relative = next(
                    (
                        state.get("artifacts", {}).get(key)
                        for key in ("oos", "preselect", "diagnostic")
                        if state.get("artifacts", {}).get(key)
                    ),
                    None,
                )
                if not source_relative or not state.get("outcome"):
                    raise ValueError("experiment has no completed result to publish")
                source = store.root / source_relative
                result = json.loads(source.read_text(encoding="utf-8"))
                learning_path = store.artifact_dir(args.experiment_id) / "learning.json"
                atomic_write_json(
                    learning_path,
                    build_learning(manifest, result, str(state["outcome"])),
                )
                state["artifacts"]["learning"] = str(learning_path.relative_to(store.root))
                store.save_state(state)
                store.append_event(
                    args.experiment_id,
                    "LEARNING_PUBLISHED",
                    {"outcome": state["outcome"]},
                )
                sync_linked_hypothesis(store, state)
            print(
                json.dumps(
                    {"status": "LEARNING_PUBLISHED", "experiment_id": args.experiment_id}
                )
            )
            return 0
        if args.command == "tick":
            current_policy_hash = policy_sha256(args.policy)
            if args.dry_run:
                payload = run_tick(
                    store,
                    policy,
                    dry_run=True,
                    current_policy_hash=current_policy_hash,
                )
            else:
                with store.lock():
                    payload = run_tick(
                        store,
                        policy,
                        dry_run=False,
                        current_policy_hash=current_policy_hash,
                    )
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            return 0
        if args.command == "heartbeat":
            heartbeat_now = parse_heartbeat_now(args.now)
            tick_preview = None
            exit_code = 0
            with store.lock():
                try:
                    current_policy_hash = policy_sha256(args.policy)
                    tick_preview = run_tick(
                        store,
                        policy,
                        dry_run=True,
                        current_policy_hash=current_policy_hash,
                        now=heartbeat_now,
                    )
                    tick_result = tick_preview
                    if tick_preview.get("status") == "DRY_RUN":
                        tick_result = run_tick(
                            store,
                            policy,
                            dry_run=False,
                            current_policy_hash=current_policy_hash,
                            now=heartbeat_now,
                        )
                    payload = run_heartbeat_cycle(
                        store,
                        policy,
                        now=heartbeat_now,
                        tick_preview=tick_preview,
                        tick_result=tick_result,
                    )
                except Exception as heartbeat_error:
                    payload = record_heartbeat_failure(
                        store,
                        now=heartbeat_now,
                        error=heartbeat_error,
                        tick_preview=tick_preview,
                    )
                    exit_code = 2
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            return exit_code
        if args.command == "weekly-report":
            if args.days <= 0:
                raise ValueError("--days must be positive")
            content = weekly_report(
                store.entries(),
                days=args.days,
                policy_id=str(policy["policy_id"]),
                state_root=store.root,
                hypotheses=store.hypothesis_entries(),
                evidence_triggers=checked_evidence_triggers(store),
            )
            if args.output:
                if args.output.exists():
                    raise ValueError(f"sealed report already exists: {args.output}")
                args.output.parent.mkdir(parents=True, exist_ok=True)
                atomic_write_text(args.output, content + "\n")
            print(content)
            return 0
        if args.command == "monthly-report":
            if args.days <= 0:
                raise ValueError("--days must be positive")
            content = monthly_report(
                store.entries(),
                days=args.days,
                policy_id=str(policy["policy_id"]),
                state_root=store.root,
                hypotheses=store.hypothesis_entries(),
                evidence_triggers=checked_evidence_triggers(store),
            )
            if args.output:
                if args.output.exists():
                    raise ValueError(f"sealed report already exists: {args.output}")
                args.output.parent.mkdir(parents=True, exist_ok=True)
                atomic_write_text(args.output, content + "\n")
            print(content)
            return 0
        raise AssertionError(args.command)
    except Exception as error:
        print(
            json.dumps(
                {"status": "PIPELINE_ERROR", "type": type(error).__name__, "detail": str(error)},
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
