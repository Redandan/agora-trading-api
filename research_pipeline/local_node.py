from __future__ import annotations

from datetime import datetime
import re
from typing import Any


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
STATE_AUTHORITY = "SERVER_CANONICAL"
TIMER_AUTHORITY = "CODEX_CLOUD_OPS_ONLY"

TASK_TYPES = {
    "CAPABILITY_READINESS",
    "TOOLING_VERTICAL_SLICE",
    "ADAPTER_IMPLEMENTATION",
    "EVIDENCE_DIAGNOSTIC",
    "REGISTERED_EXPERIMENT_STEP",
}
EXECUTION_MODES = {"READ_ONLY", "WORKTREE_WRITE"}
INPUT_KINDS = {
    "REPOSITORY_PATH",
    "SEALED_ARTIFACT",
    "CANONICAL_STATUS",
    "TASK_MESSAGE",
}
NETWORK_ACCESS = {"NONE", "PUBLIC_READ_ONLY"}
RESULT_STATUSES = {"COMPLETED", "BLOCKED", "FAILED"}
CHECK_STATUSES = {"PASS", "FAIL", "MISSING_PROOF"}

MANDATORY_FORBIDDEN_ACTIONS = {
    "CANONICAL_STATE_WRITE",
    "SERVER_RESEARCH_MCP_WRITE",
    "SECOND_TIMER_OR_WRITER",
    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
    "OOS_OPEN_OR_GATE_RELAXATION",
    "EXTERNAL_BACKFILL_OR_IMPORT",
    "PAID_API_OR_API_KEY",
    "PRODUCTION_OR_DATABASE_MUTATION",
}

TASK_KEYS = {
    "schema_version",
    "task_id",
    "issued_at",
    "manager_thread_id",
    "task_type",
    "execution_mode",
    "objective",
    "canonical_research_status",
    "authorization",
    "state_authority",
    "timer_authority",
    "inputs",
    "allowed_actions",
    "forbidden_actions",
    "expected_outputs",
    "stop_conditions",
    "limits",
}
RESULT_KEYS = {
    "schema_version",
    "task_id",
    "task_sha256",
    "status",
    "authorization",
    "started_at",
    "completed_at",
    "source_git_commit",
    "source_git_dirty_before",
    "source_git_dirty_after",
    "summary",
    "checks",
    "artifacts",
    "files_changed",
    "uncertainty",
    "recommended_next_action",
    "safety_assertions",
}
SAFETY_KEYS = {
    "canonical_state_changed",
    "server_research_mcp_write_attempted",
    "second_timer_created",
    "trading_action_attempted",
    "oos_opened",
    "paid_api_used",
}

ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")
NAME_PATTERN = re.compile(r"^[A-Z0-9_]{3,100}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
GIT_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _string(value: Any, label: str, *, minimum: int = 1, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError(f"{label} must be a string of length {minimum}..{maximum}")
    return value


def _integer(value: Any, label: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{label} must be an integer")
    if not minimum <= value <= maximum:
        raise ValueError(f"{label} must be within {minimum}..{maximum}")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    text = _string(value, label, maximum=64)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{label} must include a timezone")
    return parsed


def _pattern(value: Any, label: str, pattern: re.Pattern[str]) -> str:
    text = _string(value, label, maximum=1000)
    if pattern.fullmatch(text) is None:
        raise ValueError(f"{label} has an invalid format")
    return text


def _string_list(
    value: Any,
    label: str,
    *,
    minimum: int,
    maximum: int,
    item_maximum: int,
    pattern: re.Pattern[str] | None = None,
) -> list[str]:
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise ValueError(f"{label} must contain {minimum}..{maximum} items")
    result = []
    for index, item in enumerate(value):
        text = _string(item, f"{label}[{index}]", maximum=item_maximum)
        if pattern is not None and pattern.fullmatch(text) is None:
            raise ValueError(f"{label}[{index}] has an invalid format")
        result.append(text)
    if len(set(result)) != len(result):
        raise ValueError(f"{label} must contain unique items")
    return result


def validate_local_research_task(value: Any) -> dict[str, Any]:
    task = _object(value, "local research task")
    _exact_keys(task, TASK_KEYS, "local research task")
    if task["schema_version"] != "1":
        raise ValueError("schema_version must be 1")
    _pattern(task["task_id"], "task_id", ID_PATTERN)
    _timestamp(task["issued_at"], "issued_at")
    _string(task["manager_thread_id"], "manager_thread_id", maximum=128)
    if task["task_type"] not in TASK_TYPES:
        raise ValueError("unsupported task_type")
    if task["execution_mode"] not in EXECUTION_MODES:
        raise ValueError("unsupported execution_mode")
    _string(task["objective"], "objective", maximum=2000)
    _string(
        task["canonical_research_status"],
        "canonical_research_status",
        maximum=128,
    )
    if task["authorization"] != AUTHORIZATION:
        raise ValueError("authorization does not preserve the research-only boundary")
    if task["state_authority"] != STATE_AUTHORITY:
        raise ValueError("state_authority must remain SERVER_CANONICAL")
    if task["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("timer_authority must remain CODEX_CLOUD_OPS_ONLY")

    inputs = task["inputs"]
    if not isinstance(inputs, list) or len(inputs) > 32:
        raise ValueError("inputs must contain at most 32 items")
    for index, raw_input in enumerate(inputs):
        item = _object(raw_input, f"inputs[{index}]")
        _exact_keys(item, {"kind", "locator", "sha256"}, f"inputs[{index}]")
        if item["kind"] not in INPUT_KINDS:
            raise ValueError(f"inputs[{index}].kind is unsupported")
        _string(item["locator"], f"inputs[{index}].locator", maximum=1000)
        digest = item["sha256"]
        if digest is not None:
            _pattern(digest, f"inputs[{index}].sha256", SHA256_PATTERN)
        if item["kind"] == "SEALED_ARTIFACT" and digest is None:
            raise ValueError(f"inputs[{index}] sealed artifact requires sha256")

    _string_list(
        task["allowed_actions"],
        "allowed_actions",
        minimum=1,
        maximum=32,
        item_maximum=80,
        pattern=NAME_PATTERN,
    )
    forbidden = set(
        _string_list(
            task["forbidden_actions"],
            "forbidden_actions",
            minimum=8,
            maximum=32,
            item_maximum=100,
            pattern=NAME_PATTERN,
        )
    )
    missing_forbidden = sorted(MANDATORY_FORBIDDEN_ACTIONS - forbidden)
    if missing_forbidden:
        raise ValueError(f"mandatory forbidden actions missing: {missing_forbidden}")
    _string_list(
        task["expected_outputs"],
        "expected_outputs",
        minimum=1,
        maximum=32,
        item_maximum=100,
        pattern=NAME_PATTERN,
    )
    _string_list(
        task["stop_conditions"],
        "stop_conditions",
        minimum=1,
        maximum=32,
        item_maximum=500,
    )

    limits = _object(task["limits"], "limits")
    _exact_keys(
        limits,
        {"timeout_seconds", "max_files_changed", "max_candidate_variants", "network_access"},
        "limits",
    )
    _integer(limits["timeout_seconds"], "limits.timeout_seconds", minimum=1, maximum=7200)
    max_files = _integer(
        limits["max_files_changed"],
        "limits.max_files_changed",
        minimum=0,
        maximum=100,
    )
    max_variants = _integer(
        limits["max_candidate_variants"],
        "limits.max_candidate_variants",
        minimum=0,
        maximum=3,
    )
    if limits["network_access"] not in NETWORK_ACCESS:
        raise ValueError("unsupported network_access")
    if task["execution_mode"] == "READ_ONLY" and max_files != 0:
        raise ValueError("READ_ONLY tasks must set max_files_changed to 0")
    if task["task_type"] == "CAPABILITY_READINESS" and max_variants != 0:
        raise ValueError("CAPABILITY_READINESS must set max_candidate_variants to 0")
    return task


def validate_local_research_result(
    value: Any,
    *,
    task: dict[str, Any],
    task_sha256: str,
) -> dict[str, Any]:
    validate_local_research_task(task)
    _pattern(task_sha256, "task_sha256", SHA256_PATTERN)
    result = _object(value, "local research result")
    _exact_keys(result, RESULT_KEYS, "local research result")
    if result["schema_version"] != "1":
        raise ValueError("schema_version must be 1")
    if result["task_id"] != task["task_id"]:
        raise ValueError("result task_id does not match task")
    if result["task_sha256"] != task_sha256:
        raise ValueError("result task_sha256 does not match task bytes")
    if result["status"] not in RESULT_STATUSES:
        raise ValueError("unsupported result status")
    if result["authorization"] != AUTHORIZATION:
        raise ValueError("result authorization does not preserve research-only boundary")
    started = _timestamp(result["started_at"], "started_at")
    completed = _timestamp(result["completed_at"], "completed_at")
    if completed < started:
        raise ValueError("completed_at must not precede started_at")
    commit = result["source_git_commit"]
    if commit is not None:
        _pattern(commit, "source_git_commit", GIT_COMMIT_PATTERN)
    for name in ("source_git_dirty_before", "source_git_dirty_after"):
        if not isinstance(result[name], bool):
            raise ValueError(f"{name} must be boolean")
    _string(result["summary"], "summary", maximum=4000)

    checks = result["checks"]
    if not isinstance(checks, list) or not 1 <= len(checks) <= 64:
        raise ValueError("checks must contain 1..64 items")
    for index, raw_check in enumerate(checks):
        check = _object(raw_check, f"checks[{index}]")
        _exact_keys(check, {"name", "status", "evidence"}, f"checks[{index}]")
        _pattern(check["name"], f"checks[{index}].name", NAME_PATTERN)
        if check["status"] not in CHECK_STATUSES:
            raise ValueError(f"checks[{index}].status is unsupported")
        _string(check["evidence"], f"checks[{index}].evidence", maximum=2000)

    artifacts = result["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) > 64:
        raise ValueError("artifacts must contain at most 64 items")
    for index, raw_artifact in enumerate(artifacts):
        artifact = _object(raw_artifact, f"artifacts[{index}]")
        _exact_keys(artifact, {"path", "sha256"}, f"artifacts[{index}]")
        _string(artifact["path"], f"artifacts[{index}].path", maximum=1000)
        _pattern(artifact["sha256"], f"artifacts[{index}].sha256", SHA256_PATTERN)

    files_changed = _string_list(
        result["files_changed"],
        "files_changed",
        minimum=0,
        maximum=100,
        item_maximum=1000,
    )
    max_files = int(task["limits"]["max_files_changed"])
    if len(files_changed) > max_files:
        raise ValueError("files_changed exceeds the task limit")
    if task["execution_mode"] == "READ_ONLY" and files_changed:
        raise ValueError("READ_ONLY task result cannot report changed files")
    _string_list(
        result["uncertainty"],
        "uncertainty",
        minimum=0,
        maximum=32,
        item_maximum=1000,
    )
    _string(
        result["recommended_next_action"],
        "recommended_next_action",
        maximum=2000,
    )

    safety = _object(result["safety_assertions"], "safety_assertions")
    _exact_keys(safety, SAFETY_KEYS, "safety_assertions")
    unsafe = sorted(name for name, state in safety.items() if state is not False)
    if unsafe:
        raise ValueError(f"unsafe result assertions: {unsafe}")
    return result
