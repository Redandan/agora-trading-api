from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

from .local_node import (
    AUTHORIZATION,
    EXECUTION_MODES,
    ID_PATTERN,
    SHA256_PATTERN,
    STATE_AUTHORITY,
    TASK_TYPES,
    TIMER_AUTHORITY,
    validate_local_research_result,
    validate_local_research_task,
)


POLICY_ID = "AUTONOMOUS_TRADING_RESEARCH_V3"
POLICY_SHA256 = "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6"
PRIMARY_METRIC = "fee_adjusted_total_pnl_delta_under_equal_capital"
RESEARCH_PHASES = {"CAPABILITY", "INFRASTRUCTURE", "DIAGNOSTIC", "EXPERIMENT"}
EXPECTED_DIRECTIONS = {
    "POSITIVE",
    "NON_NEGATIVE",
    "ZERO_IMMEDIATE_EFFECT",
    "DIAGNOSTIC_ONLY",
}
DISPOSITION_PATTERN = re.compile(r"^[A-Z0-9_]{3,100}$")

DISPATCH_KEYS = {
    "schema_version",
    "dispatch_id",
    "issued_at",
    "manager_thread_id",
    "local_thread_id",
    "task_id",
    "task_sha256",
    "task_type",
    "execution_mode",
    "performance_case",
    "decision_contract",
    "policy_binding",
    "authorization",
    "state_authority",
    "timer_authority",
}
PERFORMANCE_KEYS = {
    "research_phase",
    "causal_mechanism",
    "performance_hypothesis",
    "primary_metric",
    "expected_direction",
    "drawdown_hypothesis",
    "opportunity_cost",
    "claim_boundary",
}
DECISION_KEYS = {
    "positive_disposition",
    "negative_disposition",
    "insufficient_evidence_disposition",
    "stop_condition_count",
    "stop_conditions_sha256",
    "max_candidate_variants",
    "outcome_tuning",
    "oos_access",
}
POLICY_KEYS = {"policy_id", "policy_sha256", "primary_metric"}


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def canonical_json_document_bytes(value: Any) -> bytes:
    return canonical_json_bytes(value) + b"\n"


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _load_json_bytes(raw: bytes, label: str) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} must be strict UTF-8 JSON") from error


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


def _pattern(value: Any, label: str, pattern: re.Pattern[str]) -> str:
    text = _string(value, label, maximum=1000)
    if pattern.fullmatch(text) is None:
        raise ValueError(f"{label} has an invalid format")
    return text


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
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{label} must include a timezone")
    return parsed


def _stop_conditions_sha256(task: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json_bytes(task["stop_conditions"])).hexdigest()


def validate_local_research_dispatch(
    value: Any,
    *,
    task: dict[str, Any],
    task_sha256: str,
) -> dict[str, Any]:
    validate_local_research_task(task)
    _pattern(task_sha256, "task_sha256", SHA256_PATTERN)

    dispatch = _object(value, "local research dispatch")
    _exact_keys(dispatch, DISPATCH_KEYS, "local research dispatch")
    if dispatch["schema_version"] != "1":
        raise ValueError("dispatch schema_version must be 1")
    _pattern(dispatch["dispatch_id"], "dispatch_id", ID_PATTERN)
    _timestamp(dispatch["issued_at"], "issued_at")
    _string(dispatch["manager_thread_id"], "manager_thread_id", maximum=128)
    _string(dispatch["local_thread_id"], "local_thread_id", maximum=128)
    if dispatch["task_id"] != task["task_id"]:
        raise ValueError("dispatch task_id does not match task")
    if dispatch["task_sha256"] != task_sha256:
        raise ValueError("dispatch task_sha256 does not match task bytes")
    if dispatch["manager_thread_id"] != task["manager_thread_id"]:
        raise ValueError("dispatch manager_thread_id does not match task")
    if dispatch["task_type"] not in TASK_TYPES or dispatch["task_type"] != task["task_type"]:
        raise ValueError("dispatch task_type does not match task")
    if (
        dispatch["execution_mode"] not in EXECUTION_MODES
        or dispatch["execution_mode"] != task["execution_mode"]
    ):
        raise ValueError("dispatch execution_mode does not match task")

    performance = _object(dispatch["performance_case"], "performance_case")
    _exact_keys(performance, PERFORMANCE_KEYS, "performance_case")
    if performance["research_phase"] not in RESEARCH_PHASES:
        raise ValueError("performance_case.research_phase is unsupported")
    for key in (
        "causal_mechanism",
        "performance_hypothesis",
        "drawdown_hypothesis",
        "opportunity_cost",
        "claim_boundary",
    ):
        _string(performance[key], f"performance_case.{key}", minimum=20, maximum=2000)
    if performance["primary_metric"] != PRIMARY_METRIC:
        raise ValueError("performance_case.primary_metric must match policy")
    if performance["expected_direction"] not in EXPECTED_DIRECTIONS:
        raise ValueError("performance_case.expected_direction is unsupported")
    if task["task_type"] == "EVIDENCE_DIAGNOSTIC" and performance["research_phase"] != "DIAGNOSTIC":
        raise ValueError("EVIDENCE_DIAGNOSTIC dispatch must use DIAGNOSTIC research_phase")
    if task["task_type"] == "CAPABILITY_READINESS" and performance["research_phase"] != "CAPABILITY":
        raise ValueError("CAPABILITY_READINESS dispatch must use CAPABILITY research_phase")

    decision = _object(dispatch["decision_contract"], "decision_contract")
    _exact_keys(decision, DECISION_KEYS, "decision_contract")
    for key in (
        "positive_disposition",
        "negative_disposition",
        "insufficient_evidence_disposition",
    ):
        _pattern(decision[key], f"decision_contract.{key}", DISPOSITION_PATTERN)
    stop_count = _integer(
        decision["stop_condition_count"],
        "decision_contract.stop_condition_count",
        minimum=1,
        maximum=32,
    )
    if stop_count != len(task["stop_conditions"]):
        raise ValueError("decision_contract.stop_condition_count does not match task")
    _pattern(
        decision["stop_conditions_sha256"],
        "decision_contract.stop_conditions_sha256",
        SHA256_PATTERN,
    )
    if decision["stop_conditions_sha256"] != _stop_conditions_sha256(task):
        raise ValueError("decision_contract.stop_conditions_sha256 does not match task")
    max_variants = _integer(
        decision["max_candidate_variants"],
        "decision_contract.max_candidate_variants",
        minimum=0,
        maximum=3,
    )
    if max_variants != task["limits"]["max_candidate_variants"]:
        raise ValueError("decision_contract.max_candidate_variants does not match task")
    if decision["outcome_tuning"] != "DENY" or decision["oos_access"] != "DENY":
        raise ValueError("decision_contract must deny tuning and OOS access")

    policy = _object(dispatch["policy_binding"], "policy_binding")
    _exact_keys(policy, POLICY_KEYS, "policy_binding")
    if (
        policy["policy_id"] != POLICY_ID
        or policy["policy_sha256"] != POLICY_SHA256
        or policy["primary_metric"] != PRIMARY_METRIC
    ):
        raise ValueError("policy_binding does not match AUTONOMOUS_TRADING_RESEARCH_V3")
    if dispatch["authorization"] != AUTHORIZATION:
        raise ValueError("dispatch authorization does not preserve research-only scope")
    if dispatch["state_authority"] != STATE_AUTHORITY:
        raise ValueError("dispatch state_authority must remain SERVER_CANONICAL")
    if dispatch["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("dispatch timer_authority must remain CODEX_CLOUD_OPS_ONLY")
    return dispatch


def load_and_validate_dispatch(
    dispatch_path: Path,
    task_path: Path,
    result_path: Path | None = None,
) -> dict[str, Any]:
    dispatch_raw = dispatch_path.read_bytes()
    dispatch = _load_json_bytes(dispatch_raw, "local research dispatch")
    if dispatch_raw != canonical_json_document_bytes(dispatch):
        raise ValueError("local research dispatch must use canonical JSON bytes")
    task_raw = task_path.read_bytes()
    task = _load_json_bytes(task_raw, "local research task")
    task_sha256 = hashlib.sha256(task_raw).hexdigest()
    validated = validate_local_research_dispatch(
        dispatch,
        task=task,
        task_sha256=task_sha256,
    )
    validation = {
        "dispatch_id": validated["dispatch_id"],
        "dispatch_sha256": hashlib.sha256(dispatch_raw).hexdigest(),
        "status": "VALID",
        "task_id": task["task_id"],
        "task_sha256": task_sha256,
    }
    if result_path is not None:
        result_raw = result_path.read_bytes()
        result = _load_json_bytes(result_raw, "local research result")
        validated_result = validate_local_research_result(
            result,
            task=task,
            task_sha256=task_sha256,
        )
        validation.update(
            {
                "closure_status": "VALIDATED_RESULT_BOUND_TO_PERFORMANCE_DISPATCH",
                "result_sha256": hashlib.sha256(result_raw).hexdigest(),
                "result_status": validated_result["status"],
            }
        )
    return validation


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate one Manager-to-Local research dispatch")
    parser.add_argument("dispatch", type=Path)
    parser.add_argument("--task", required=True, type=Path)
    parser.add_argument("--result", type=Path)
    arguments = parser.parse_args(list(sys.argv[1:] if argv is None else argv))
    try:
        result = load_and_validate_dispatch(
            arguments.dispatch,
            arguments.task,
            arguments.result,
        )
    except (OSError, ValueError) as error:
        print(json.dumps({"reason": str(error), "status": "BLOCKED"}, sort_keys=True))
        return 2
    print(canonical_json_bytes(result).decode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
