from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import canonical_json_document_bytes


DOCUMENT_TYPE = "LOCAL_FEATURE_DATA_PATH_PROOF_V1"
ACCESS_MODE = "PUBLIC_NO_CREDENTIAL_OR_EXISTING_SEALED"
PROOF_VALIDITY = timedelta(hours=24)

_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_FAMILY = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_FIELD = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")
_HTTPS = re.compile(r"^https://[^ ]+$")
_REPOSITORY_LOCATOR = re.compile(r"^(?![A-Za-z]:)(?!/)(?!.*(?:^|/)\.\.(?:/|$))[^\\]+$")
_UNPROVEN = re.compile(r"(?:MISSING_PROOF|UNKNOWN|UNAVAILABLE)", re.IGNORECASE)
_ROOT_KEYS = {
    "access_mode",
    "checked_at",
    "discovery_exception_id",
    "document_type",
    "expires_at",
    "feature_data_paths",
    "proof_id",
    "safety_assertions",
    "schema_version",
    "strategy_family",
    "task_id",
    "task_sha256",
}
_PATH_KEYS = {
    "access_status",
    "credential_required",
    "decision_time_known",
    "feature_id",
    "feature_semantics",
    "historical_coverage",
    "locator",
    "machine_readable",
    "manual_export_required",
    "paid_api_required",
    "point_in_time_rule",
    "probe_receipt_sha256",
    "probe_status",
    "prospective_coverage",
    "provider",
    "revision_identity",
    "schema_fields",
    "timestamp_field",
    "transport",
}
_HISTORICAL_KEYS = {"end", "minimum_observations", "start", "status"}
_PROSPECTIVE_KEYS = {
    "capture_without_backfill",
    "maximum_availability_lag_seconds",
    "status",
}
_SAFETY_KEYS = {
    "api_key_used",
    "canonical_state_changed",
    "factor_values_retained",
    "oos_opened",
    "outcome_accessed",
    "paid_api_used",
    "repository_written",
    "runner_executed",
    "second_timer_created",
    "server_research_mcp_write_attempted",
    "trading_action_attempted",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"feature data path proof contains duplicate key: {key}")
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


def _strict_text(value: Any, label: str, *, minimum: int = 3) -> str:
    if (
        not isinstance(value, str)
        or not minimum <= len(value.strip()) <= 512
        or _UNPROVEN.search(value) is not None
    ):
        raise ValueError(f"{label} must be explicit and proven")
    return value


def validate_feature_data_path_proof(value: Any) -> dict[str, Any]:
    document = _exact_keys(value, _ROOT_KEYS, "feature data path proof")
    if document["schema_version"] != "1" or document["document_type"] != DOCUMENT_TYPE:
        raise ValueError("feature data path proof identity is unsupported")
    if document["access_mode"] != ACCESS_MODE:
        raise ValueError("feature data path proof access mode is unsupported")
    for name in ("proof_id", "discovery_exception_id", "task_id"):
        _pattern(document[name], _IDENTIFIER, name)
    _pattern(document["task_sha256"], _SHA256, "task_sha256")
    _pattern(document["strategy_family"], _FAMILY, "strategy_family")
    checked_at = _timestamp(document["checked_at"], "checked_at")
    expires_at = _timestamp(document["expires_at"], "expires_at")
    if expires_at - checked_at != PROOF_VALIDITY:
        raise ValueError("feature data path proof must use the fixed 24-hour validity")

    safety = _exact_keys(document["safety_assertions"], _SAFETY_KEYS, "safety_assertions")
    if any(item is not False for item in safety.values()):
        raise ValueError("feature data path proof safety assertions must all be false")

    paths = document["feature_data_paths"]
    if not isinstance(paths, list) or not 1 <= len(paths) <= 3:
        raise ValueError("feature data path proof requires one to three paths")
    identities: set[tuple[str, str]] = set()
    for index, raw_path in enumerate(paths):
        path = _exact_keys(raw_path, _PATH_KEYS, f"feature_data_paths[{index}]")
        feature_id = _pattern(path["feature_id"], _FAMILY, f"feature_data_paths[{index}].feature_id")
        _strict_text(path["provider"], f"feature_data_paths[{index}].provider", minimum=2)
        transport = path["transport"]
        locator = path["locator"]
        if transport == "PUBLIC_HTTPS_GET":
            _pattern(locator, _HTTPS, f"feature_data_paths[{index}].locator")
            expected_probe_status = "READ_SUCCEEDED"
        elif transport == "EXISTING_SEALED_REPOSITORY_ARTIFACT":
            _pattern(locator, _REPOSITORY_LOCATOR, f"feature_data_paths[{index}].locator")
            expected_probe_status = "SEALED_ARTIFACT_VERIFIED"
        else:
            raise ValueError("feature data path transport is unsupported")
        if path["probe_status"] != expected_probe_status:
            raise ValueError("feature data path probe status does not match its transport")
        if path["access_status"] != "EXECUTABLE_NOW":
            raise ValueError("feature data path is not executable now")
        if any(
            path[name] is not False
            for name in ("credential_required", "paid_api_required", "manual_export_required")
        ) or any(
            path[name] is not True
            for name in ("machine_readable", "decision_time_known")
        ):
            raise ValueError("feature data path requires a no-cost unattended machine-readable route")
        _pattern(
            path["probe_receipt_sha256"],
            _SHA256,
            f"feature_data_paths[{index}].probe_receipt_sha256",
        )
        fields = path["schema_fields"]
        if (
            not isinstance(fields, list)
            or not 2 <= len(fields) <= 64
            or len(set(fields)) != len(fields)
        ):
            raise ValueError("feature data path schema fields are invalid")
        for field_index, field in enumerate(fields):
            _pattern(field, _FIELD, f"feature_data_paths[{index}].schema_fields[{field_index}]")
        timestamp_field = _pattern(
            path["timestamp_field"],
            _FIELD,
            f"feature_data_paths[{index}].timestamp_field",
        )
        if timestamp_field not in fields:
            raise ValueError("feature data path timestamp field is absent from the schema")
        if path["point_in_time_rule"] not in {
            "FIRST_SEEN_IMMUTABLE",
            "PROVIDER_FINALIZED_NO_BACKDATED_REVISION",
        }:
            raise ValueError("feature data path point-in-time rule is unsupported")
        _strict_text(path["revision_identity"], f"feature_data_paths[{index}].revision_identity")
        _strict_text(path["feature_semantics"], f"feature_data_paths[{index}].feature_semantics", minimum=8)

        historical = _exact_keys(
            path["historical_coverage"],
            _HISTORICAL_KEYS,
            f"feature_data_paths[{index}].historical_coverage",
        )
        if historical["status"] != "AVAILABLE":
            raise ValueError("feature data path historical coverage is unavailable")
        start = _timestamp(historical["start"], f"feature_data_paths[{index}].historical_coverage.start")
        end = _timestamp(historical["end"], f"feature_data_paths[{index}].historical_coverage.end")
        observations = historical["minimum_observations"]
        if start >= end or not isinstance(observations, int) or isinstance(observations, bool) or observations < 30:
            raise ValueError("feature data path historical coverage is insufficient")

        prospective = _exact_keys(
            path["prospective_coverage"],
            _PROSPECTIVE_KEYS,
            f"feature_data_paths[{index}].prospective_coverage",
        )
        lag = prospective["maximum_availability_lag_seconds"]
        if (
            prospective["status"] != "AVAILABLE"
            or prospective["capture_without_backfill"] is not True
            or not isinstance(lag, int)
            or isinstance(lag, bool)
            or not 0 <= lag <= 2_678_400
        ):
            raise ValueError("feature data path prospective coverage is insufficient")
        identities.add((feature_id, locator))
    if len(identities) != len(paths):
        raise ValueError("feature data paths must be distinct")
    return document


def load_and_validate_feature_data_path_proof(
    raw_or_path: bytes | Path,
) -> tuple[dict[str, Any], bytes]:
    raw = raw_or_path if isinstance(raw_or_path, bytes) else raw_or_path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("feature data path proof must be strict UTF-8 JSON") from error
    if not isinstance(value, dict) or raw != canonical_json_document_bytes(value):
        raise ValueError("feature data path proof must use canonical JSON document bytes")
    return validate_feature_data_path_proof(value), raw


def validate_feature_data_path_proof_context(
    proof: dict[str, Any],
    *,
    discovery: dict[str, Any],
    task: dict[str, Any],
    task_sha256: str,
    allocation_time: datetime,
) -> None:
    expected = {
        "discovery_exception_id": discovery["exception_id"],
        "strategy_family": discovery["discovery_contract"]["strategy_family"],
        "task_id": task["task_id"],
        "task_sha256": task_sha256,
    }
    for name, value in expected.items():
        if proof[name] != value:
            raise ValueError(f"feature data path proof {name} does not bind the discovery")
    checked_at = _timestamp(proof["checked_at"], "checked_at")
    expires_at = _timestamp(proof["expires_at"], "expires_at")
    if not checked_at <= allocation_time <= expires_at:
        raise ValueError("feature data path proof is not fresh at allocation time")
    if "PROBE_LISTED_PUBLIC_FEATURE_DATA_PATHS" not in task["allowed_actions"]:
        raise ValueError("feature data path proof requires a bounded task probe action")
    task_messages = [
        item["locator"] for item in task["inputs"] if item["kind"] == "TASK_MESSAGE"
    ]
    for path in proof["feature_data_paths"]:
        if not any(
            path["feature_id"] in message
            and path["locator"] in message
            and path["probe_receipt_sha256"] in message
            for message in task_messages
        ):
            raise ValueError("each executable feature data path must be frozen in one task message")
