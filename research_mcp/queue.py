from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from research_pipeline.evidence import evidence_progress
from research_pipeline.storage import ResearchStore, sha256_file
from research_source.contract import (
    PRODUCER as FORWARD_SOURCE_PRODUCER,
    SOURCE as FORWARD_SOURCE,
    TRANSPORT as FORWARD_SOURCE_TRANSPORT,
    build_capture_request,
)


APP_DIR = Path(os.environ.get("AGORA_RESEARCH_APP_DIR", "/opt/agora-research-worker/current"))
STATE_DIR = Path(os.environ.get("AGORA_RESEARCH_STATE_DIR", "/var/lib/agora-research/state"))
INBOX_DIR = Path(os.environ.get("AGORA_RESEARCH_INBOX_DIR", "/var/lib/agora-research/inbox"))
REQUEST_DIR = Path(os.environ.get("AGORA_RESEARCH_REQUEST_DIR", "/var/lib/agora-research/requests"))
SOURCE_REQUEST_DIR = Path(
    os.environ.get("AGORA_RESEARCH_SOURCE_REQUEST_DIR", "/var/lib/agora-research/source-requests")
)
SOURCE_DROP_DIR = Path(
    os.environ.get("AGORA_RESEARCH_SOURCE_DROP_DIR", "/var/lib/agora-research/source-drop")
)
POLICY_FILE = Path(
    os.environ.get("AGORA_RESEARCH_POLICY_FILE", str(APP_DIR / "research_pipeline/policy.v3.json"))
)
RUN_ID = re.compile(r"^[a-f0-9]{32}$")
TERMINAL_RUN_STATUSES = {"COMPLETED", "FAILED", "STALE_RECOVERED"}
MAX_CANDIDATE_BUNDLE_BYTES = 128 * 1024
CANDIDATE_AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"


def _now(value: datetime | None = None) -> str:
    current = value or datetime.now(timezone.utc)
    return current.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _read_json(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None
    return value if isinstance(value, dict) else None


def _policy_summary() -> dict[str, Any]:
    try:
        raw = POLICY_FILE.read_bytes()
        value = json.loads(raw.decode("utf-8"))
    except (FileNotFoundError, UnicodeDecodeError, json.JSONDecodeError, OSError):
        return {"status": "POLICY_READ_FAILED"}
    if not isinstance(value, dict):
        return {"status": "POLICY_INVALID"}
    return {
        "status": "READY",
        "schema_version": value.get("schema_version"),
        "policy_id": value.get("policy_id"),
        "authorization": value.get("authorization"),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def _worker_release_summary() -> dict[str, Any]:
    release_dir = APP_DIR / ".release"
    try:
        raw = (release_dir / "provenance.json").read_bytes()
    except OSError:
        return {
            "status": "RELEASE_PROVENANCE_READ_FAILED",
            "reason": "release provenance is unavailable",
        }
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {
            "status": "RELEASE_PROVENANCE_INVALID",
            "reason": "release provenance is not valid UTF-8 JSON",
        }
    if not isinstance(value, dict) or value.get("schema_version") != "1":
        return {
            "status": "RELEASE_PROVENANCE_INVALID",
            "reason": "release provenance schema is invalid",
        }

    release_id = value.get("release_id")
    source_git_commit = value.get("source_git_commit")
    source_git_branch = value.get("source_git_branch")
    source_git_dirty = value.get("source_git_dirty")
    source_manifest_sha256 = value.get("source_manifest_sha256")
    installed_at = _parse_time(value.get("installed_at"))
    if not isinstance(release_id, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9._-]*", release_id
    ):
        reason = "release id is invalid"
    elif not isinstance(source_git_commit, str) or not re.fullmatch(
        r"[0-9a-f]{40}", source_git_commit
    ):
        reason = "source Git commit is invalid"
    elif not isinstance(source_git_branch, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9._/-]*", source_git_branch
    ):
        reason = "source Git branch is invalid"
    elif (
        source_git_branch.endswith("/")
        or ".." in source_git_branch
        or "//" in source_git_branch
    ):
        reason = "source Git branch is unsafe"
    elif not isinstance(source_git_dirty, bool):
        reason = "source Git dirty flag is invalid"
    elif not isinstance(source_manifest_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", source_manifest_sha256
    ):
        reason = "source manifest hash is invalid"
    elif installed_at is None:
        reason = "install timestamp is invalid"
    else:
        try:
            manifest_sha256 = hashlib.sha256(
                (release_dir / "source.sha256").read_bytes()
            ).hexdigest()
        except OSError:
            reason = "installed source manifest is unavailable"
        else:
            if manifest_sha256 != source_manifest_sha256:
                reason = "installed source manifest hash does not match provenance"
            else:
                return {
                    "status": "DIRTY_SOURCE" if source_git_dirty else "READY",
                    "schema_version": "1",
                    "release_id": release_id,
                    "source_git_commit": source_git_commit,
                    "source_git_branch": source_git_branch,
                    "source_git_dirty": source_git_dirty,
                    "source_manifest_sha256": source_manifest_sha256,
                    "installed_at": _now(installed_at),
                }
    return {"status": "RELEASE_PROVENANCE_INVALID", "reason": reason}


def _atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _parse_time(value: Any) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


def _queue_stale_seconds() -> int:
    configured = os.environ.get("AGORA_RESEARCH_QUEUE_STALE_SECONDS")
    if configured:
        try:
            return max(60, int(configured))
        except ValueError:
            return 21600
    policy = _read_json(POLICY_FILE)
    try:
        return max(60, int(policy["budget"]["lock_stale_seconds"])) if policy else 21600
    except (KeyError, TypeError, ValueError):
        return 21600


def _candidate_bundle_byte_limit() -> int:
    policy = _read_json(POLICY_FILE)
    try:
        configured = int(policy["server_worker"]["max_candidate_bundle_bytes"]) if policy else 0
    except (KeyError, TypeError, ValueError):
        configured = 0
    return configured if configured == MAX_CANDIDATE_BUNDLE_BYTES else MAX_CANDIDATE_BUNDLE_BYTES


def _recover_stale_queue(now: datetime) -> list[dict[str, Any]]:
    recovered: list[dict[str, Any]] = []
    threshold = _queue_stale_seconds()
    runs = REQUEST_DIR / "runs"
    for path, prior_status in (
        (REQUEST_DIR / "running.json", "RUNNING"),
        (REQUEST_DIR / "pending.json", "QUEUED"),
    ):
        value = _read_json(path)
        if not value:
            continue
        try:
            age_seconds = now.timestamp() - path.stat().st_mtime
        except FileNotFoundError:
            continue
        if age_seconds <= threshold:
            continue
        request_id = str(value.get("request_id", ""))
        if not RUN_ID.fullmatch(request_id):
            request_id = uuid.uuid4().hex
        run_path = runs / f"{request_id}.json"
        existing = _read_json(run_path)
        if not existing or str(existing.get("status")) not in TERMINAL_RUN_STATUSES:
            final = {
                **value,
                "request_id": request_id,
                "status": "STALE_RECOVERED",
                "recovered_at": _now(now),
                "prior_status": prior_status,
                "age_seconds": int(age_seconds),
                "reason": "queue lease exceeded policy lock_stale_seconds",
            }
            _atomic_write_json(run_path, final)
        current = _read_json(path)
        if current and current.get("request_id") == value.get("request_id"):
            path.unlink(missing_ok=True)
        recovered.append(
            {"request_id": request_id, "prior_status": prior_status, "age_seconds": int(age_seconds)}
        )
    return recovered


def _heartbeat_due(now: datetime) -> dict[str, Any]:
    state_path = STATE_DIR / "heartbeat" / "state.json"
    if not state_path.exists():
        return {"due": True, "reason": "HEARTBEAT_STATE_NOT_BOOTSTRAPPED"}
    state = _read_json(state_path)
    if not state:
        return {"due": False, "reason": "HEARTBEAT_STATE_INVALID"}
    next_due = _parse_time(state.get("next_due"))
    if next_due is None:
        return {"due": False, "reason": "HEARTBEAT_NEXT_DUE_INVALID"}
    return {
        "due": now >= next_due,
        "reason": "HEARTBEAT_DUE" if now >= next_due else "HEARTBEAT_NOT_DUE",
        "next_due": _now(next_due),
        "last_success": state.get("last_success"),
    }


def _forward_source_policy() -> dict[str, Any]:
    policy = _read_json(POLICY_FILE)
    value = policy.get("forward_evidence_source") if policy else None
    if not isinstance(value, dict):
        raise ValueError("authorized forward evidence source policy is missing")
    exact = {
        "status": "AUTHORIZED",
        "clock": "CODEX_CLOUD_HEARTBEAT_COMPANION",
        "producer": FORWARD_SOURCE_PRODUCER,
        "transport": FORWARD_SOURCE_TRANSPORT,
        "public_origin": "https://www.okx.com",
        "endpoint": "/api/v5/market/candles",
        "instrument": "BTC-USDT",
        "bar": "1H",
        "confirm": "1",
        "source_identity": "agora-evidence-source",
        "worker_network_access": "DENY",
        "worker_database_access": "DENY",
        "producer_credentials": "DENY",
        "backfill": "DENY",
        "timer": "DENY",
    }
    for field, expected in exact.items():
        if value.get(field) != expected:
            raise ValueError(f"forward evidence source policy {field} is not frozen")
    if int(value.get("max_response_bytes", 0)) != 1048576:
        raise ValueError("forward evidence source response limit is not frozen")
    return value


def _evidence_capture_plan(now: datetime) -> dict[str, Any]:
    _forward_source_policy()
    policy = _read_json(POLICY_FILE)
    lag = int(policy.get("evidence", {}).get("capture_max_lag_seconds", 0)) if policy else 0
    stale = int(policy.get("budget", {}).get("lock_stale_seconds", 0)) if policy else 0
    if lag != 21600 or stale <= 0:
        raise ValueError("forward evidence timing policy is invalid")
    store = ResearchStore(STATE_DIR, lock_stale_seconds=stale)
    due: list[tuple[dict[str, Any], dict[str, Any], dict[str, Any]]] = []
    summaries: list[dict[str, Any]] = []
    for trigger, state in store.evidence_trigger_entries():
        trigger_id = str(trigger.get("trigger_id", ""))
        trigger_path = store.evidence_trigger_dir(trigger_id) / "trigger.json"
        if state.get("trigger_id") != trigger_id or sha256_file(trigger_path) != state.get("trigger_sha256"):
            raise ValueError(f"registered evidence trigger integrity failure: {trigger_id}")
        progress = evidence_progress(
            store,
            trigger,
            state,
            now=now,
            capture_max_lag_seconds=lag,
        )
        summaries.append(
            {
                "trigger_id": trigger_id,
                "status": progress["status"],
                "next_observation_day": progress.get("next_observation_day"),
                "next_capture_deadline": progress.get("next_capture_deadline"),
            }
        )
        if progress["status"] == "CAPTURE_DUE" and trigger.get("source") == FORWARD_SOURCE:
            due.append((trigger, state, progress))
    if len(due) > 1:
        raise ValueError("more than one forward evidence capture is due")
    if not due:
        return {"status": "NOT_CAPTURE_DUE", "evidence_progress": summaries}
    trigger, state, progress = due[0]
    request = build_capture_request(
        trigger=trigger,
        state=state,
        progress=progress,
        requested_at=now,
    )
    return {"status": "CAPTURE_DUE", "request": request, "evidence_progress": summaries}


def _source_active() -> dict[str, Any] | None:
    for path, status in (
        (SOURCE_REQUEST_DIR / "running.json", "RUNNING"),
        (SOURCE_REQUEST_DIR / "pending.json", "QUEUED"),
    ):
        value = _read_json(path)
        if value:
            return {"status": status, **value}
    return None


def _enqueue_evidence_capture(now: datetime) -> dict[str, Any]:
    try:
        plan = _evidence_capture_plan(now)
    except Exception as error:
        return {
            "status": "CAPTURE_PLANNING_FAILED",
            "error_type": type(error).__name__,
            "detail": str(error)[:1000],
        }
    if plan["status"] != "CAPTURE_DUE":
        return plan
    request = plan["request"]
    request_id = str(request["request_id"])
    active = _source_active()
    if active:
        if active.get("request_id") == request_id:
            return {**active, "evidence_progress": plan["evidence_progress"]}
        return {
            "status": "SOURCE_QUEUE_BUSY",
            "active_request_id": active.get("request_id"),
            "active_day": active.get("day"),
            "due_request_id": request_id,
            "evidence_progress": plan["evidence_progress"],
        }
    completed = _read_json(SOURCE_REQUEST_DIR / "runs" / f"{request_id}.json")
    if completed:
        status = "ALREADY_COMPLETED" if completed.get("status") == "COMPLETED" else "SOURCE_CAPTURE_FAILED"
        return {
            "status": status,
            "request_id": request_id,
            "day": request["day"],
            "completed_at": completed.get("completed_at"),
            "detail": completed.get("detail"),
            "evidence_progress": plan["evidence_progress"],
        }
    SOURCE_REQUEST_DIR.mkdir(parents=True, exist_ok=True)
    pending = SOURCE_REQUEST_DIR / "pending.json"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    try:
        descriptor = os.open(pending, flags, 0o640)
    except FileExistsError:
        raced = _source_active()
        return raced or {"status": "SOURCE_QUEUE_RACE_RETRY"}
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(request, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(pending, 0o640)
    return {"status": "QUEUED", **request, "evidence_progress": plan["evidence_progress"]}


def _active_queue_response(
    operation: str,
    *,
    payload_sha256: str | None,
    recovered: list[dict[str, Any]],
) -> dict[str, Any] | None:
    pending = REQUEST_DIR / "pending.json"
    running = REQUEST_DIR / "running.json"
    for path, state in ((running, "RUNNING"), (pending, "QUEUED")):
        existing = _read_json(path)
        if existing:
            same_operation = existing.get("operation") == operation
            same_payload = payload_sha256 is None or existing.get("payload_sha256") == payload_sha256
            if same_operation and same_payload:
                return {"status": state, **existing, "recovered": recovered}
            return {
                "status": "QUEUE_BUSY",
                "active_status": state,
                "active_request_id": existing.get("request_id"),
                "active_operation": existing.get("operation"),
                "recovered": recovered,
            }
    return None


def _enqueue_request(
    operation: str,
    *,
    current: datetime,
    recovered: list[dict[str, Any]],
    payload: dict[str, Any] | None = None,
    payload_sha256: str | None = None,
) -> dict[str, Any]:
    active = _active_queue_response(
        operation,
        payload_sha256=payload_sha256,
        recovered=recovered,
    )
    if active:
        return active

    request = {
        "schema_version": "1",
        "request_id": uuid.uuid4().hex,
        "requested_at": _now(current),
        "source": "CODEX_CLOUD_OPS",
        "operation": operation,
    }
    if payload is not None:
        request["payload"] = payload
        request["payload_sha256"] = payload_sha256
    pending = REQUEST_DIR / "pending.json"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    try:
        descriptor = os.open(pending, flags, 0o600)
    except FileExistsError:
        raced = _active_queue_response(
            operation,
            payload_sha256=payload_sha256,
            recovered=recovered,
        )
        return raced or {"status": "QUEUE_RACE_RETRY", "recovered": recovered}
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(request, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    return {"status": "QUEUED", **request, "recovered": recovered}


def request_heartbeat(*, now: datetime | None = None) -> dict[str, Any]:
    """Create one due durable heartbeat; concurrent calls converge on the same run."""
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    REQUEST_DIR.mkdir(parents=True, exist_ok=True)
    recovered = _recover_stale_queue(current)
    active = _active_queue_response(
        "RESEARCH_HEARTBEAT",
        payload_sha256=None,
        recovered=recovered,
    )
    if active:
        return active
    due = _heartbeat_due(current)
    if not due["due"]:
        return {"status": "NOT_DUE", **due, "recovered": recovered}
    evidence_capture = _enqueue_evidence_capture(current)
    result = _enqueue_request(
        "RESEARCH_HEARTBEAT",
        current=current,
        recovered=recovered,
    )
    result["evidence_capture"] = evidence_capture
    return result


def _validated_candidate_payload(bundle: dict[str, Any]) -> tuple[dict[str, Any], str]:
    if not isinstance(bundle, dict):
        raise ValueError("candidate bundle must be an object")
    required = {"schema_version", "trigger_id", "hypothesis", "manifest", "authorization"}
    missing = sorted(required.difference(bundle))
    unknown = sorted(set(bundle).difference(required))
    if missing:
        raise ValueError(f"candidate bundle missing fields: {', '.join(missing)}")
    if unknown:
        raise ValueError(f"candidate bundle has unknown fields: {', '.join(unknown)}")
    if bundle.get("schema_version") != "1":
        raise ValueError("candidate bundle schema_version must be 1")
    if bundle.get("authorization") != CANDIDATE_AUTHORIZATION:
        raise ValueError("candidate bundle authorization must remain research-only")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,79}", str(bundle.get("trigger_id", ""))):
        raise ValueError("candidate bundle trigger_id is invalid")
    if not isinstance(bundle.get("hypothesis"), dict) or not isinstance(bundle.get("manifest"), dict):
        raise ValueError("candidate bundle hypothesis and manifest must be objects")
    try:
        encoded = json.dumps(
            bundle,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ValueError("candidate bundle must contain finite JSON values") from error
    byte_limit = _candidate_bundle_byte_limit()
    if len(encoded) > byte_limit:
        raise ValueError(
            f"candidate bundle exceeds {byte_limit} byte limit"
        )
    normalized = json.loads(encoded.decode("utf-8"))
    return normalized, hashlib.sha256(encoded).hexdigest()


def _completed_candidate(payload_sha256: str) -> dict[str, Any] | None:
    runs = REQUEST_DIR / "runs"
    try:
        paths = sorted(runs.glob("*.json"), key=lambda item: item.stat().st_mtime, reverse=True)
    except OSError:
        return None
    for path in paths:
        value = _read_json(path)
        if (
            value
            and value.get("operation") == "REGISTER_CANDIDATE_BUNDLE"
            and value.get("payload_sha256") == payload_sha256
            and value.get("status") == "COMPLETED"
        ):
            return value
    return None


def request_candidate_bundle(
    bundle: dict[str, Any],
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Queue one fixed research-only candidate registration operation."""
    payload, payload_sha256 = _validated_candidate_payload(bundle)
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    REQUEST_DIR.mkdir(parents=True, exist_ok=True)
    recovered = _recover_stale_queue(current)
    active = _active_queue_response(
        "REGISTER_CANDIDATE_BUNDLE",
        payload_sha256=payload_sha256,
        recovered=recovered,
    )
    if active:
        return active
    completed = _completed_candidate(payload_sha256)
    if completed:
        return {
            "status": "ALREADY_COMPLETED",
            "request_id": completed.get("request_id"),
            "payload_sha256": payload_sha256,
            "result": completed.get("result"),
            "completed_at": completed.get("completed_at"),
            "recovered": recovered,
        }
    return _enqueue_request(
        "REGISTER_CANDIDATE_BUNDLE",
        current=current,
        recovered=recovered,
        payload=payload,
        payload_sha256=payload_sha256,
    )


def get_run(run_id: str) -> dict[str, Any]:
    if not RUN_ID.fullmatch(run_id):
        return {"status": "INVALID_RUN_ID"}
    for path, state in (
        (REQUEST_DIR / "running.json", "RUNNING"),
        (REQUEST_DIR / "pending.json", "QUEUED"),
    ):
        active = _read_json(path)
        if active and active.get("request_id") == run_id:
            return {"status": state, **active}
    value = _read_json(REQUEST_DIR / "runs" / f"{run_id}.json")
    return value or {"status": "NOT_FOUND", "request_id": run_id}


def _pipeline(command: list[str], *, timeout: int = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            "-m",
            "research_pipeline",
            "--state-dir",
            str(STATE_DIR),
            "--policy",
            str(POLICY_FILE),
            *command,
        ],
        cwd=APP_DIR,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "PYTHONUNBUFFERED": "1"},
    )


def research_status() -> dict[str, Any]:
    queue: dict[str, Any] = {"status": "IDLE"}
    for path, state in (
        (REQUEST_DIR / "running.json", "RUNNING"),
        (REQUEST_DIR / "pending.json", "QUEUED"),
    ):
        value = _read_json(path)
        if value:
            queue = {"status": state, **value}
            break
    latest = _read_json(INBOX_DIR / "latest.json")
    result = _pipeline(["status", "--json"])
    registry: dict[str, Any]
    if result.returncode == 0:
        try:
            parsed = json.loads(result.stdout)
            registry = parsed if isinstance(parsed, dict) else {"status": "INVALID_STATUS_OUTPUT"}
        except json.JSONDecodeError:
            registry = {"status": "INVALID_STATUS_OUTPUT"}
    else:
        registry = {
            "status": "STATUS_READ_FAILED",
            "exit_code": result.returncode,
            "detail": result.stderr[-1000:],
        }
    return {
        "server_time": _now(),
        "timer_authority": "CODEX_CLOUD_OPS_ONLY",
        "state_authority": "SERVER_CANONICAL",
        "policy": _policy_summary(),
        "worker_release": _worker_release_summary(),
        "queue": queue,
        "evidence_capture_queue": _source_active() or {"status": "IDLE"},
        "latest_evidence_capture": _read_json(SOURCE_REQUEST_DIR / "latest.json"),
        "latest_evidence_ingest": _read_json(SOURCE_DROP_DIR / "latest.json"),
        "latest_heartbeat": latest,
        "registry": registry,
    }


def research_briefing(period: str) -> dict[str, Any]:
    if period not in {"weekly", "monthly"}:
        return {"status": "INVALID_PERIOD", "allowed": ["weekly", "monthly"]}
    heartbeat_state = _read_json(STATE_DIR / "heartbeat" / "state.json")
    record = heartbeat_state.get(f"last_{period}") if heartbeat_state else None
    if not isinstance(record, dict):
        return {
            "status": "REPORT_NOT_AVAILABLE",
            "period": period,
        }
    relative = str(record.get("artifact_path", "")).strip()
    expected_hash = str(record.get("sha256", "")).strip().lower()
    if not relative or not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
        return {"status": "REPORT_RECORD_INVALID", "period": period}
    artifact_path = (STATE_DIR / relative).resolve()
    try:
        artifact_path.relative_to(STATE_DIR.resolve())
    except ValueError:
        return {"status": "REPORT_PATH_REJECTED", "period": period}
    try:
        content = artifact_path.read_text(encoding="utf-8")
    except OSError:
        return {"status": "REPORT_ARTIFACT_MISSING", "period": period, "artifact_path": relative}
    actual_hash = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        return {"status": "REPORT_ARTIFACT_HASH_MISMATCH", "period": period}
    policy = _policy_summary()
    policy_match = re.search(r"^- Policy: `([^`]+)`\s*$", content, re.MULTILINE)
    report_policy_id = policy_match.group(1) if policy_match else None
    current_policy_id = policy.get("policy_id") if policy.get("status") == "READY" else None
    if report_policy_id is None or current_policy_id is None:
        policy_alignment = "MISSING_PROOF"
    elif report_policy_id == current_policy_id:
        policy_alignment = "CURRENT"
    else:
        policy_alignment = "SEALED_HISTORICAL_POLICY"
    return {
        "status": "REPORT_READY",
        "period": period,
        "generated_at": record.get("report_date"),
        "report_policy_id": report_policy_id,
        "current_policy_id": current_policy_id,
        "policy_alignment": policy_alignment,
        "artifact_id": artifact_path.stem,
        "artifact_path": relative,
        "sha256": expected_hash,
        "content": content,
    }
