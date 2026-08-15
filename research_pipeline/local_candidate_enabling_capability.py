from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import canonical_json_document_bytes


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "LOCAL_CANDIDATE_ENABLING_CAPABILITY_V1"
DUPLICATE_FAMILY_KEY = "research-factory-candidate-enabling-capability-v1"
STATE_AUTHORITY = "SERVER_CANONICAL"
TIMER_AUTHORITY = "CODEX_CLOUD_OPS_ONLY"
MINIMUM_UNLOCKED_DIRECT_FAMILIES = 3
ROLLING_BUDGET_DAYS = 7
MAX_ACCEPTED_USES = 1

_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_FAMILY = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_ROOT_KEYS = {
    "authorization",
    "capability",
    "dispatch_id",
    "dispatch_sha256",
    "document_type",
    "duplicate_family_key",
    "exception_id",
    "intent_id",
    "intent_sha256",
    "issued_at",
    "output_class",
    "rolling_budget",
    "safety_boundaries",
    "schema_version",
    "state_authority",
    "task_id",
    "task_sha256",
    "timer_authority",
}
_CAPABILITY_KEYS = {
    "capability_id",
    "capability_kind",
    "maximum_candidate_variants_per_experiment",
    "required_economic_outputs",
    "runner_id",
    "unlocked_direct_families",
}
_FAMILY_KEYS = {
    "decision_time_availability",
    "family_key",
    "feature_family",
    "matched_comparator_id",
    "maximum_additional_research_steps",
    "parent_strategy_id",
    "positive_next_step",
    "runner_missing_only",
}
_REQUIRED_ECONOMIC_OUTPUTS = [
    "adverse_slippage",
    "drawdown",
    "fees",
    "holding_age",
    "inventory_path",
    "realized_pnl",
    "total_pnl",
    "unrealized_pnl",
]
_SAFETY_KEYS = {
    "adds_timer",
    "creates_candidate",
    "creates_strategy_result",
    "opens_oos",
    "trading_action",
    "uses_paid_api",
    "writes_canonical_state",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"candidate-enabling capability contains duplicate key: {key}")
        value[key] = item
    return value


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} does not satisfy the closed contract")
    return value


def _pattern(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"{label} has an invalid format")
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


def validate_candidate_enabling_capability(value: Any) -> dict[str, Any]:
    document = _exact_keys(value, _ROOT_KEYS, "candidate-enabling capability")
    if document["schema_version"] != "1" or document["document_type"] != DOCUMENT_TYPE:
        raise ValueError("candidate-enabling capability identity is unsupported")
    if document["authorization"] != AUTHORIZATION:
        raise ValueError("candidate-enabling capability authorization is unsupported")
    if document["state_authority"] != STATE_AUTHORITY or document["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("candidate-enabling capability authority boundary is unsupported")
    if document["output_class"] != "NON_COUNTING":
        raise ValueError("candidate-enabling capability must remain NON_COUNTING")
    if document["duplicate_family_key"] != DUPLICATE_FAMILY_KEY:
        raise ValueError("candidate-enabling capability family key is unsupported")
    for name in ("exception_id", "task_id", "dispatch_id", "intent_id"):
        _pattern(document[name], _IDENTIFIER, name)
    for name in ("task_sha256", "dispatch_sha256", "intent_sha256"):
        _pattern(document[name], _SHA256, name)
    _timestamp(document["issued_at"], "issued_at")

    budget = _exact_keys(
        document["rolling_budget"],
        {"days", "maximum_accepted_uses"},
        "rolling_budget",
    )
    if budget != {"days": ROLLING_BUDGET_DAYS, "maximum_accepted_uses": MAX_ACCEPTED_USES}:
        raise ValueError("candidate-enabling capability must use the fixed seven-day budget")

    safety = _exact_keys(document["safety_boundaries"], _SAFETY_KEYS, "safety_boundaries")
    if any(value is not False for value in safety.values()):
        raise ValueError("candidate-enabling capability safety boundaries must all be false")

    capability = _exact_keys(document["capability"], _CAPABILITY_KEYS, "capability")
    _pattern(capability["capability_id"], _IDENTIFIER, "capability_id")
    _pattern(capability["runner_id"], _IDENTIFIER, "runner_id")
    if capability["capability_kind"] != "DECLARATIVE_DRA_EXPERIMENT_RUNNER":
        raise ValueError("candidate-enabling capability kind is unsupported")
    if capability["maximum_candidate_variants_per_experiment"] != 3:
        raise ValueError("candidate-enabling capability must cap experiments at three variants")
    if capability["required_economic_outputs"] != _REQUIRED_ECONOMIC_OUTPUTS:
        raise ValueError("candidate-enabling capability economic outputs are incomplete")

    families = capability["unlocked_direct_families"]
    if not isinstance(families, list) or not MINIMUM_UNLOCKED_DIRECT_FAMILIES <= len(families) <= 8:
        raise ValueError("candidate-enabling capability must unlock three to eight direct families")
    family_keys: set[str] = set()
    feature_families: set[str] = set()
    for index, raw_family in enumerate(families):
        family = _exact_keys(raw_family, _FAMILY_KEYS, f"unlocked_direct_families[{index}]")
        family_key = _pattern(family["family_key"], _FAMILY, f"families[{index}].family_key")
        feature_family = _pattern(family["feature_family"], _FAMILY, f"families[{index}].feature_family")
        for name in ("parent_strategy_id", "matched_comparator_id"):
            _pattern(family[name], _IDENTIFIER, f"families[{index}].{name}")
        if family["decision_time_availability"] != "REQUIRED":
            raise ValueError("each unlocked family must require decision-time availability")
        if family["maximum_additional_research_steps"] != 1:
            raise ValueError("each unlocked family must be at most one step from a frozen hypothesis")
        if family["positive_next_step"] != "FROZEN_HYPOTHESIS_MANIFEST":
            raise ValueError("each unlocked family must lead directly to a frozen hypothesis")
        if family["runner_missing_only"] is not True:
            raise ValueError("each unlocked family must be blocked only by the shared runner")
        family_keys.add(family_key)
        feature_families.add(feature_family)
    if len(family_keys) != len(families) or len(feature_families) != len(families):
        raise ValueError("unlocked direct families and feature families must be distinct")
    return document


def load_and_validate_candidate_enabling_capability(
    raw_or_path: bytes | Path,
) -> tuple[dict[str, Any], bytes]:
    raw = raw_or_path if isinstance(raw_or_path, bytes) else raw_or_path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("candidate-enabling capability must be strict UTF-8 JSON") from error
    if not isinstance(value, dict) or raw != canonical_json_document_bytes(value):
        raise ValueError("candidate-enabling capability must use canonical JSON document bytes")
    return validate_candidate_enabling_capability(value), raw


def validate_candidate_enabling_capability_context(
    capability: dict[str, Any],
    *,
    task: dict[str, Any],
    task_sha256: str,
    dispatch: dict[str, Any],
    dispatch_sha256: str,
    intent: dict[str, Any],
    intent_sha256: str,
) -> None:
    expected = {
        "task_id": task["task_id"],
        "task_sha256": task_sha256,
        "dispatch_id": dispatch["dispatch_id"],
        "dispatch_sha256": dispatch_sha256,
        "intent_id": intent["intent_id"],
        "intent_sha256": intent_sha256,
        "output_class": intent["output_class"],
        "duplicate_family_key": intent["duplicate_family_key"],
    }
    for name, value in expected.items():
        if capability[name] != value:
            raise ValueError(f"candidate-enabling capability {name} does not bind the dispatch")
    if task["task_type"] not in {"TOOLING_VERTICAL_SLICE", "ADAPTER_IMPLEMENTATION"}:
        raise ValueError("candidate-enabling capability requires a tooling or adapter task")
    if task["execution_mode"] != "WORKTREE_WRITE":
        raise ValueError("candidate-enabling capability requires bounded WORKTREE_WRITE")
    if dispatch["performance_case"]["research_phase"] not in {"CAPABILITY", "INFRASTRUCTURE"}:
        raise ValueError("candidate-enabling capability requires capability or infrastructure phase")
    if task["limits"]["max_candidate_variants"] != 0:
        raise ValueError("candidate-enabling capability work cannot create candidate variants")
