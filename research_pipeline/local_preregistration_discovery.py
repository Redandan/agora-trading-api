from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import canonical_json_document_bytes


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "LOCAL_PREREGISTRATION_DISCOVERY_V1"
DUPLICATE_FAMILY_KEY = "research-factory-preregistration-discovery-v1"
STATE_AUTHORITY = "SERVER_CANONICAL"
TIMER_AUTHORITY = "CODEX_CLOUD_OPS_ONLY"
ROLLING_BUDGET_DAYS = 7
MAX_ACCEPTED_USES = 1

_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_FAMILY = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DOI = re.compile(r"^10\.[0-9]{4,9}/[^ ]+$")
_HTTPS = re.compile(r"^https://[^ ]+$")
_ROOT_KEYS = {
    "authorization",
    "discovery_contract",
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
_DISCOVERY_KEYS = {
    "allocation_precedence",
    "direct_evidence_ready",
    "maximum_preregistration_ideas",
    "outcome_access",
    "primary_sources",
    "research_mode",
    "result_boundary",
    "runner_execution",
    "strategy_family",
}
_SOURCE_KEYS = {"doi", "publisher_url", "source_kind"}
_SAFETY_KEYS = {
    "adds_timer",
    "creates_candidate",
    "creates_hypothesis",
    "creates_strategy_result",
    "opens_oos",
    "runs_backtest",
    "trading_action",
    "uses_paid_api",
    "writes_canonical_state",
}
_REQUIRED_FORBIDDEN_ACTIONS = {
    "CANONICAL_STATE_WRITE",
    "FUTURE_EVIDENCE_OR_OUTCOME_ACCESS",
    "HISTORICAL_PERFORMANCE_RECOMPUTATION",
    "HYPOTHESIS_OR_CANDIDATE_REGISTRATION",
    "OOS_OPEN_OR_GATE_RELAXATION",
    "PAID_API_OR_API_KEY",
    "SECOND_TIMER_OR_WRITER",
    "SERVER_RESEARCH_MCP_WRITE",
    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"preregistration discovery contains duplicate key: {key}")
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


def validate_preregistration_discovery(value: Any) -> dict[str, Any]:
    document = _exact_keys(value, _ROOT_KEYS, "preregistration discovery")
    if document["schema_version"] != "1" or document["document_type"] != DOCUMENT_TYPE:
        raise ValueError("preregistration discovery identity is unsupported")
    if document["authorization"] != AUTHORIZATION:
        raise ValueError("preregistration discovery authorization is unsupported")
    if document["state_authority"] != STATE_AUTHORITY or document["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("preregistration discovery authority boundary is unsupported")
    if document["output_class"] != "SPEC_OR_CAPABILITY_SLICE":
        raise ValueError("preregistration discovery must remain a support slice")
    if document["duplicate_family_key"] != DUPLICATE_FAMILY_KEY:
        raise ValueError("preregistration discovery family key is unsupported")
    for name in ("exception_id", "task_id", "dispatch_id", "intent_id"):
        _pattern(document[name], _IDENTIFIER, name)
    for name in ("task_sha256", "dispatch_sha256", "intent_sha256"):
        _pattern(document[name], _SHA256, name)
    _timestamp(document["issued_at"], "issued_at")

    budget = _exact_keys(document["rolling_budget"], {"days", "maximum_accepted_uses"}, "rolling_budget")
    if budget != {"days": ROLLING_BUDGET_DAYS, "maximum_accepted_uses": MAX_ACCEPTED_USES}:
        raise ValueError("preregistration discovery must use the fixed seven-day budget")

    safety = _exact_keys(document["safety_boundaries"], _SAFETY_KEYS, "safety_boundaries")
    if any(item is not False for item in safety.values()):
        raise ValueError("preregistration discovery safety boundaries must all be false")

    discovery = _exact_keys(document["discovery_contract"], _DISCOVERY_KEYS, "discovery_contract")
    expected_scalars = {
        "allocation_precedence": "DIRECT_EVIDENCE_READY_WORK_PREEMPTS",
        "direct_evidence_ready": False,
        "maximum_preregistration_ideas": 1,
        "outcome_access": "DENY",
        "research_mode": "PUBLIC_PRIMARY_SOURCE_PREREGISTRATION_DISCOVERY",
        "result_boundary": "ONE_PREREGISTRATION_READY_IDEA_OR_CLOSE",
        "runner_execution": "DENY",
    }
    for name, expected in expected_scalars.items():
        if discovery[name] != expected:
            raise ValueError(f"preregistration discovery {name} is unsupported")
    _pattern(discovery["strategy_family"], _FAMILY, "strategy_family")
    sources = discovery["primary_sources"]
    if not isinstance(sources, list) or not 3 <= len(sources) <= 8:
        raise ValueError("preregistration discovery requires three to eight primary sources")
    identities: set[tuple[str, str]] = set()
    for index, raw_source in enumerate(sources):
        source = _exact_keys(raw_source, _SOURCE_KEYS, f"primary_sources[{index}]")
        doi = _pattern(source["doi"], _DOI, f"primary_sources[{index}].doi")
        url = _pattern(source["publisher_url"], _HTTPS, f"primary_sources[{index}].publisher_url")
        if source["source_kind"] != "PRIMARY_RESEARCH_PUBLISHER_PAGE":
            raise ValueError("preregistration discovery source kind is unsupported")
        identities.add((doi.lower(), url))
    if len(identities) != len(sources):
        raise ValueError("preregistration discovery primary sources must be distinct")
    return document


def load_and_validate_preregistration_discovery(
    raw_or_path: bytes | Path,
) -> tuple[dict[str, Any], bytes]:
    raw = raw_or_path if isinstance(raw_or_path, bytes) else raw_or_path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("preregistration discovery must be strict UTF-8 JSON") from error
    if not isinstance(value, dict) or raw != canonical_json_document_bytes(value):
        raise ValueError("preregistration discovery must use canonical JSON document bytes")
    return validate_preregistration_discovery(value), raw


def validate_preregistration_discovery_context(
    discovery: dict[str, Any],
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
        if discovery[name] != value:
            raise ValueError(f"preregistration discovery {name} does not bind the dispatch")
    if task["task_type"] != "EVIDENCE_DIAGNOSTIC" or task["execution_mode"] != "READ_ONLY":
        raise ValueError("preregistration discovery requires a read-only evidence diagnostic")
    limits = task["limits"]
    if limits["network_access"] != "PUBLIC_READ_ONLY" or limits["max_files_changed"] != 0 or limits["max_candidate_variants"] != 0:
        raise ValueError("preregistration discovery task limits exceed the read-only boundary")
    if dispatch["performance_case"]["research_phase"] != "DIAGNOSTIC":
        raise ValueError("preregistration discovery requires diagnostic research phase")
    if dispatch["decision_contract"]["max_candidate_variants"] != 0:
        raise ValueError("preregistration discovery cannot create candidate variants")
    if "READ_LISTED_PRIMARY_PUBLIC_SOURCES" not in task["allowed_actions"]:
        raise ValueError("preregistration discovery must restrict reading to listed primary sources")
    if not _REQUIRED_FORBIDDEN_ACTIONS.issubset(task["forbidden_actions"]):
        raise ValueError("preregistration discovery task is missing a required safety boundary")
    task_messages = [item["locator"] for item in task["inputs"] if item["kind"] == "TASK_MESSAGE"]
    for source in discovery["discovery_contract"]["primary_sources"]:
        if not any(source["doi"] in message and source["publisher_url"] in message for message in task_messages):
            raise ValueError("each preregistration discovery source must be frozen in one task message")
