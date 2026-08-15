from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import (
    POLICY_ID,
    POLICY_SHA256,
    PRIMARY_METRIC,
    canonical_json_bytes,
    canonical_json_document_bytes,
    validate_local_research_dispatch,
)
from .local_node import (
    AUTHORIZATION,
    MANDATORY_FORBIDDEN_ACTIONS,
    STATE_AUTHORITY,
    TIMER_AUTHORITY,
    validate_local_research_task,
)
from .local_strategy_path import (
    validate_local_strategy_path,
    validate_local_strategy_path_context,
)
from .local_weekly_output_classification import (
    validate_weekly_output_classification_record,
)


DOCUMENT_TYPE = "LOCAL_DIRECT_SCREEN_PACKAGE_BLUEPRINT_V1"
OUTPUT_CLASS = "MECHANISM_CONCLUSION"
TASK_TYPE = "EVIDENCE_DIAGNOSTIC"
EXECUTION_MODE = "READ_ONLY"
SCHEMA_VERSION = "1"

_ID = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")
_FAMILY = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_ACTION = re.compile(r"^[A-Z0-9_]{3,100}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REPOSITORY_PATH = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")

_ROOT_KEYS = {
    "canonical_research_status",
    "classification",
    "decision_contract",
    "document_type",
    "inputs",
    "issued_at",
    "limits",
    "local_thread_id",
    "manager_thread_id",
    "objective",
    "package_id",
    "performance_case",
    "schema_version",
    "strategy_path",
    "task_contract",
}
_ISSUED_AT_KEYS = {"dispatch", "intent", "strategy_path", "task"}
_CLASSIFICATION_KEYS = {
    "disposition_actions",
    "duplicate_family_key",
    "independence_semantics",
}
_DECISION_KEYS = {
    "insufficient_evidence_disposition",
    "negative_disposition",
    "positive_disposition",
}
_PERFORMANCE_KEYS = {
    "causal_mechanism",
    "claim_boundary",
    "drawdown_hypothesis",
    "expected_direction",
    "opportunity_cost",
    "performance_hypothesis",
}
_TASK_CONTRACT_KEYS = {
    "allowed_actions",
    "expected_outputs",
    "forbidden_actions",
    "stop_conditions",
}
_LIMIT_KEYS = {"timeout_seconds"}
_STRATEGY_KEYS = {
    "candidate_path",
    "decision_time",
    "evidence_bindings",
}
_CANDIDATE_KEYS = {
    "matched_comparator_id",
    "maximum_additional_research_steps",
    "parent_strategy_id",
    "positive_next_step",
    "runner_id",
    "status",
}
_DECISION_TIME_KEYS = {
    "availability_rule",
    "decision_clock",
    "feature_name",
}
_BINDING_ROLES = {
    "decision_feature",
    "execution_runner",
    "matched_comparator",
    "parent_strategy",
}
_INPUT_KEYS = {"kind", "locator", "sha256"}
_INPUT_KINDS = {
    "CANONICAL_STATUS",
    "REPOSITORY_PATH",
    "SEALED_ARTIFACT",
    "TASK_MESSAGE",
}


def _exact_object(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} does not satisfy the closed blueprint contract")
    return value


def _text(value: Any, label: str, *, minimum: int = 1, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError(f"{label} must be a string of length {minimum}..{maximum}")
    return value


def _pattern(value: Any, pattern: re.Pattern[str], label: str) -> str:
    text = _text(value, label, maximum=2000)
    if pattern.fullmatch(text) is None:
        raise ValueError(f"{label} has an invalid format")
    return text


def _timestamp(value: Any, label: str) -> str:
    text = _text(value, label, maximum=64)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp")
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
    result: list[str] = []
    for index, item in enumerate(value):
        text = _text(item, f"{label}[{index}]", maximum=item_maximum)
        if pattern is not None and pattern.fullmatch(text) is None:
            raise ValueError(f"{label}[{index}] has an invalid format")
        result.append(text)
    if len(result) != len(set(result)):
        raise ValueError(f"{label} must contain unique items")
    return result


def _strict_json(raw: bytes, label: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError(f"{label} contains duplicate key {key}")
            value[key] = item
        return value

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} must be strict UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    if raw != canonical_json_document_bytes(value):
        raise ValueError(f"{label} must use canonical JSON document bytes")
    return value


def _contained_file(root: Path, locator: str, label: str) -> Path:
    if _REPOSITORY_PATH.fullmatch(locator) is None:
        raise ValueError(f"{label} must be a repository-relative path")
    candidate = root.joinpath(*locator.split("/"))
    resolved = candidate.resolve(strict=True)
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(f"{label} escapes the repository") from error
    if not resolved.is_file() or candidate.is_symlink():
        raise ValueError(f"{label} must be a regular non-link file")
    return resolved


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _materialize_inputs(
    root: Path,
    raw_inputs: Any,
) -> list[dict[str, Any]]:
    if not isinstance(raw_inputs, list) or not 1 <= len(raw_inputs) <= 32:
        raise ValueError("inputs must contain 1..32 items")
    inputs: list[dict[str, Any]] = []
    locators: set[str] = set()
    for index, raw_item in enumerate(raw_inputs):
        item = _exact_object(raw_item, _INPUT_KEYS, f"inputs[{index}]")
        kind = item["kind"]
        if kind not in _INPUT_KINDS:
            raise ValueError(f"inputs[{index}].kind is unsupported")
        locator = _text(item["locator"], f"inputs[{index}].locator", maximum=1000)
        if locator in locators:
            raise ValueError("input locators must be unique")
        locators.add(locator)
        supplied = item["sha256"]
        digest: str | None
        if kind == "REPOSITORY_PATH":
            if supplied != "AUTO":
                raise ValueError("REPOSITORY_PATH inputs must use sha256 AUTO")
            digest = _sha256(_contained_file(root, locator, f"inputs[{index}]").read_bytes())
        elif kind == "SEALED_ARTIFACT":
            digest = _pattern(supplied, _SHA256, f"inputs[{index}].sha256")
            actual = _sha256(_contained_file(root, locator, f"inputs[{index}]").read_bytes())
            if actual != digest:
                raise ValueError(f"inputs[{index}] sealed artifact hash mismatch")
        else:
            if supplied is not None:
                raise ValueError(f"{kind} inputs must use a null sha256")
            digest = None
        inputs.append({"kind": kind, "locator": locator, "sha256": digest})
    return inputs


def validate_direct_screen_blueprint(value: Any) -> dict[str, Any]:
    blueprint = _exact_object(value, _ROOT_KEYS, "direct-screen blueprint")
    if blueprint["schema_version"] != SCHEMA_VERSION or blueprint["document_type"] != DOCUMENT_TYPE:
        raise ValueError("direct-screen blueprint identity is unsupported")
    _pattern(blueprint["package_id"], _ID, "package_id")
    _text(blueprint["manager_thread_id"], "manager_thread_id", maximum=128)
    _text(blueprint["local_thread_id"], "local_thread_id", maximum=128)
    _text(blueprint["objective"], "objective", maximum=2000)
    _text(blueprint["canonical_research_status"], "canonical_research_status", maximum=128)

    issued = _exact_object(blueprint["issued_at"], _ISSUED_AT_KEYS, "issued_at")
    timestamps = [_timestamp(issued[key], f"issued_at.{key}") for key in ("task", "dispatch", "intent", "strategy_path")]
    if timestamps != sorted(timestamps):
        raise ValueError("blueprint issued_at timestamps must be nondecreasing")

    performance = _exact_object(blueprint["performance_case"], _PERFORMANCE_KEYS, "performance_case")
    for key in _PERFORMANCE_KEYS - {"expected_direction"}:
        _text(performance[key], f"performance_case.{key}", minimum=20, maximum=2000)
    if performance["expected_direction"] not in {"POSITIVE", "NON_NEGATIVE"}:
        raise ValueError("direct economic screen expected_direction must be POSITIVE or NON_NEGATIVE")

    decision = _exact_object(blueprint["decision_contract"], _DECISION_KEYS, "decision_contract")
    for key in _DECISION_KEYS:
        _pattern(decision[key], _ACTION, f"decision_contract.{key}")
    if len(set(decision.values())) != 3:
        raise ValueError("decision dispositions must be distinct")

    classification = _exact_object(blueprint["classification"], _CLASSIFICATION_KEYS, "classification")
    _pattern(classification["duplicate_family_key"], _FAMILY, "duplicate_family_key")
    if classification["independence_semantics"] != "UNIQUE_FAMILY":
        raise ValueError("direct-screen blueprint must declare UNIQUE_FAMILY")
    actions = classification["disposition_actions"]
    if not isinstance(actions, list) or len(actions) != 3:
        raise ValueError("classification must map exactly three dispositions")
    mappings: dict[str, str] = {}
    for index, raw_action in enumerate(actions):
        action = _exact_object(raw_action, {"action", "disposition"}, f"disposition_actions[{index}]")
        disposition = _pattern(action["disposition"], _ACTION, f"disposition_actions[{index}].disposition")
        if action["action"] not in {"COUNT", "EXCLUDE"} or disposition in mappings:
            raise ValueError("classification disposition mapping is invalid")
        mappings[disposition] = action["action"]
    if set(mappings) != set(decision.values()):
        raise ValueError("classification must map every frozen disposition")
    if mappings[decision["positive_disposition"]] != "COUNT" or mappings[decision["negative_disposition"]] != "COUNT" or mappings[decision["insufficient_evidence_disposition"]] != "EXCLUDE":
        raise ValueError("direct-screen classification must count valid conclusions and exclude missing proof")

    task_contract = _exact_object(blueprint["task_contract"], _TASK_CONTRACT_KEYS, "task_contract")
    _string_list(task_contract["allowed_actions"], "allowed_actions", minimum=1, maximum=32, item_maximum=80, pattern=_ACTION)
    forbidden = set(_string_list(task_contract["forbidden_actions"], "forbidden_actions", minimum=8, maximum=32, item_maximum=100, pattern=_ACTION))
    if not MANDATORY_FORBIDDEN_ACTIONS <= forbidden:
        raise ValueError("task contract omits mandatory forbidden actions")
    _string_list(task_contract["expected_outputs"], "expected_outputs", minimum=1, maximum=32, item_maximum=100, pattern=_ACTION)
    _string_list(task_contract["stop_conditions"], "stop_conditions", minimum=1, maximum=32, item_maximum=500)
    limits = _exact_object(blueprint["limits"], _LIMIT_KEYS, "limits")
    timeout = limits["timeout_seconds"]
    if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 7200:
        raise ValueError("limits.timeout_seconds must be within 1..7200")

    strategy = _exact_object(blueprint["strategy_path"], _STRATEGY_KEYS, "strategy_path")
    candidate = _exact_object(strategy["candidate_path"], _CANDIDATE_KEYS, "strategy_path.candidate_path")
    for key in ("matched_comparator_id", "parent_strategy_id", "runner_id"):
        _text(candidate[key], f"candidate_path.{key}", minimum=3, maximum=200)
    if candidate["status"] != "DIRECT_TO_FROZEN_HYPOTHESIS" or candidate["positive_next_step"] != "FROZEN_HYPOTHESIS_MANIFEST" or candidate["maximum_additional_research_steps"] != 1:
        raise ValueError("direct screen must be at most one step from a frozen hypothesis")
    decision_time = _exact_object(strategy["decision_time"], _DECISION_TIME_KEYS, "strategy_path.decision_time")
    _text(decision_time["feature_name"], "decision_time.feature_name", minimum=3, maximum=200)
    _text(decision_time["availability_rule"], "decision_time.availability_rule", minimum=20, maximum=1000)
    _text(decision_time["decision_clock"], "decision_time.decision_clock", minimum=3, maximum=200)
    bindings = _exact_object(strategy["evidence_bindings"], _BINDING_ROLES, "strategy_path.evidence_bindings")
    for role, locator in bindings.items():
        _text(locator, f"evidence_bindings.{role}", maximum=1000)
    return blueprint


def load_direct_screen_blueprint(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    return validate_direct_screen_blueprint(_strict_json(raw, "direct-screen blueprint")), raw


def _input_by_locator(inputs: list[dict[str, Any]], locator: str) -> dict[str, Any]:
    matches = [item for item in inputs if item["locator"] == locator]
    if len(matches) != 1:
        raise ValueError(f"strategy binding locator {locator!r} must name exactly one task input")
    return matches[0]


def build_direct_screen_package(
    repository_root: Path | str,
    blueprint_path: Path | str,
) -> tuple[dict[str, bytes], dict[str, Any]]:
    root = Path(repository_root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("repository_root must be a directory")
    blueprint_file = Path(blueprint_path)
    if not blueprint_file.is_absolute():
        blueprint_file = root / blueprint_file
    blueprint_file = blueprint_file.resolve(strict=True)
    try:
        blueprint_file.relative_to(root)
    except ValueError as error:
        raise ValueError("blueprint must be contained by the repository") from error
    blueprint, _ = load_direct_screen_blueprint(blueprint_file)
    inputs = _materialize_inputs(root, blueprint["inputs"])
    package_id = blueprint["package_id"]
    issued = blueprint["issued_at"]
    task_id = f"local-node-{package_id}"
    dispatch_id = f"manager-{package_id}"
    intent_id = f"intent-{package_id}"
    output_id = f"output-{package_id}"
    admission_id = f"admit-{package_id}"
    for label, identifier in {
        "task_id": task_id,
        "dispatch_id": dispatch_id,
        "intent_id": intent_id,
        "output_id": output_id,
        "admission_id": admission_id,
    }.items():
        _pattern(identifier, _ID, label)

    task = {
        "schema_version": SCHEMA_VERSION,
        "task_id": task_id,
        "issued_at": issued["task"],
        "manager_thread_id": blueprint["manager_thread_id"],
        "task_type": TASK_TYPE,
        "execution_mode": EXECUTION_MODE,
        "objective": blueprint["objective"],
        "canonical_research_status": blueprint["canonical_research_status"],
        "authorization": AUTHORIZATION,
        "state_authority": STATE_AUTHORITY,
        "timer_authority": TIMER_AUTHORITY,
        "inputs": inputs,
        "allowed_actions": blueprint["task_contract"]["allowed_actions"],
        "forbidden_actions": blueprint["task_contract"]["forbidden_actions"],
        "expected_outputs": blueprint["task_contract"]["expected_outputs"],
        "stop_conditions": blueprint["task_contract"]["stop_conditions"],
        "limits": {
            "timeout_seconds": blueprint["limits"]["timeout_seconds"],
            "max_files_changed": 0,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        },
    }
    validate_local_research_task(task)
    task_raw = canonical_json_document_bytes(task)
    task_sha = _sha256(task_raw)

    performance = dict(blueprint["performance_case"])
    performance.update({"primary_metric": PRIMARY_METRIC, "research_phase": "DIAGNOSTIC"})
    stop_sha = _sha256(canonical_json_bytes(task["stop_conditions"]))
    dispositions = blueprint["decision_contract"]
    dispatch = {
        "schema_version": SCHEMA_VERSION,
        "dispatch_id": dispatch_id,
        "issued_at": issued["dispatch"],
        "manager_thread_id": blueprint["manager_thread_id"],
        "local_thread_id": blueprint["local_thread_id"],
        "task_id": task_id,
        "task_sha256": task_sha,
        "task_type": TASK_TYPE,
        "execution_mode": EXECUTION_MODE,
        "performance_case": performance,
        "decision_contract": {
            "positive_disposition": dispositions["positive_disposition"],
            "negative_disposition": dispositions["negative_disposition"],
            "insufficient_evidence_disposition": dispositions["insufficient_evidence_disposition"],
            "stop_condition_count": len(task["stop_conditions"]),
            "stop_conditions_sha256": stop_sha,
            "max_candidate_variants": 0,
            "outcome_tuning": "DENY",
            "oos_access": "DENY",
        },
        "policy_binding": {
            "policy_id": POLICY_ID,
            "policy_sha256": POLICY_SHA256,
            "primary_metric": PRIMARY_METRIC,
        },
        "authorization": AUTHORIZATION,
        "state_authority": STATE_AUTHORITY,
        "timer_authority": TIMER_AUTHORITY,
    }
    validate_local_research_dispatch(dispatch, task=task, task_sha256=task_sha)
    dispatch_raw = canonical_json_document_bytes(dispatch)
    dispatch_sha = _sha256(dispatch_raw)

    base = "research_pipeline/examples"
    paths = {
        "task": f"{base}/local-research-task.{package_id}.json",
        "dispatch": f"{base}/local-research-dispatch.{package_id}.json",
        "intent": f"{base}/local-weekly-output-classification.intent.{package_id}.json",
        "strategy_path": f"{base}/local-research-strategy-path.{package_id}.json",
    }
    claim_sha = _sha256(canonical_json_bytes(performance["claim_boundary"]))
    intent = {
        "authorization": AUTHORIZATION,
        "claim_boundary_sha256": claim_sha,
        "dispatch_id": dispatch_id,
        "dispatch_path": paths["dispatch"],
        "dispatch_sha256": dispatch_sha,
        "disposition_actions": blueprint["classification"]["disposition_actions"],
        "document_type": "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_V1",
        "duplicate_family_key": blueprint["classification"]["duplicate_family_key"],
        "independence_semantics": blueprint["classification"]["independence_semantics"],
        "intent_id": intent_id,
        "intent_path": paths["intent"],
        "issued_at": issued["intent"],
        "manager_thread_id": blueprint["manager_thread_id"],
        "max_candidate_variants": 0,
        "output_class": OUTPUT_CLASS,
        "output_id": output_id,
        "record_stage": "PRE_DISPATCH_INTENT",
        "schema_version": SCHEMA_VERSION,
        "task_id": task_id,
        "task_path": paths["task"],
        "task_sha256": task_sha,
    }
    validate_weekly_output_classification_record(intent)
    intent_raw = canonical_json_document_bytes(intent)
    intent_sha = _sha256(intent_raw)

    strategy_source = blueprint["strategy_path"]
    candidate_source = strategy_source["candidate_path"]
    decision_source = strategy_source["decision_time"]
    subject_by_role = {
        "decision_feature": decision_source["feature_name"],
        "execution_runner": candidate_source["runner_id"],
        "matched_comparator": candidate_source["matched_comparator_id"],
        "parent_strategy": candidate_source["parent_strategy_id"],
    }
    evidence_bindings: dict[str, dict[str, Any]] = {}
    for role, locator in strategy_source["evidence_bindings"].items():
        binding = _input_by_locator(inputs, locator)
        evidence_bindings[role] = {"subject_id": subject_by_role[role], **binding}
    strategy = {
        "admission_id": admission_id,
        "authorization": AUTHORIZATION,
        "candidate_path": {
            **candidate_source,
            "existing_adapter_or_direct_runner": True,
            "implementation_before_economic_test": "DENY",
        },
        "decision_time": {
            **decision_source,
            "availability_status": "KNOWN_BEFORE_DECISION",
            "post_outcome_dependency": "DENY",
        },
        "dispatch_id": dispatch_id,
        "dispatch_sha256": dispatch_sha,
        "disposition": {
            "independent_forward_or_oos_boundary_preserved": True,
            "insufficient_stops_without_permission": True,
            "negative_closes_family": True,
        },
        "document_type": "LOCAL_RESEARCH_STRATEGY_PATH_V1",
        "economics": {
            "adverse_slippage_required": True,
            "drawdown_required": True,
            "equal_capital_comparator_required": True,
            "fees_required": True,
            "holding_age_required": True,
            "inventory_path_required": True,
            "total_pnl_required": True,
        },
        "evidence_bindings": evidence_bindings,
        "intent_id": intent_id,
        "intent_sha256": intent_sha,
        "issued_at": issued["strategy_path"],
        "manager_thread_id": blueprint["manager_thread_id"],
        "output_class": OUTPUT_CLASS,
        "schema_version": SCHEMA_VERSION,
        "state_authority": STATE_AUTHORITY,
        "task_id": task_id,
        "task_sha256": task_sha,
        "timer_authority": TIMER_AUTHORITY,
    }
    validate_local_strategy_path(strategy)
    validate_local_strategy_path_context(
        strategy,
        task=task,
        task_sha256=task_sha,
        dispatch=dispatch,
        dispatch_sha256=dispatch_sha,
        intent=intent,
        intent_sha256=intent_sha,
    )
    strategy_raw = canonical_json_document_bytes(strategy)
    artifacts = {
        paths["task"]: task_raw,
        paths["dispatch"]: dispatch_raw,
        paths["intent"]: intent_raw,
        paths["strategy_path"]: strategy_raw,
    }
    receipt = {
        "document_type": "LOCAL_DIRECT_SCREEN_PACKAGE_BUILD_RECEIPT_V1",
        "package_id": package_id,
        "artifacts": [
            {"path": path, "sha256": _sha256(raw)}
            for path, raw in sorted(artifacts.items())
        ],
        "status": "VALID",
    }
    return artifacts, receipt


def write_direct_screen_package(
    repository_root: Path | str,
    blueprint_path: Path | str,
) -> dict[str, Any]:
    root = Path(repository_root).resolve(strict=True)
    artifacts, receipt = build_direct_screen_package(root, blueprint_path)
    targets = [root.joinpath(*relative.split("/")) for relative in artifacts]
    existing = [str(path) for path in targets if path.exists()]
    if existing:
        raise ValueError(f"direct-screen package is create-only; targets already exist: {existing}")
    for relative, raw in artifacts.items():
        target = root.joinpath(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    return receipt
