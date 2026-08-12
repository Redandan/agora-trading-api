from __future__ import annotations

import hashlib
import json
import re
from datetime import date, datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

from .forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    ROOT_TRIGGER_FINGERPRINT,
    ROOT_TRIGGER_ID,
)
from .forward_volatility_persistence import (
    CLOSE as VOLATILITY_CLOSE,
    DOCUMENT_TYPE as VOLATILITY_DOCUMENT_TYPE,
    RETAIN as VOLATILITY_RETAIN,
    _validate_snapshot as _validate_volatility_snapshot,
)
from .models import RESEARCH_AUTHORIZATION
from .post_shock_factor import (
    CONTINUATION,
    DOCUMENT_TYPE as DIRECTIONAL_V1_DOCUMENT_TYPE,
    NO_FACTOR,
    REVERSAL,
    V2_DOCUMENT_TYPE as DIRECTIONAL_V2_DOCUMENT_TYPE,
    _validate_result_snapshot as _validate_directional_v1,
    _validate_result_snapshot_v2 as _validate_directional_v2,
)


DOCUMENT_TYPE = "BTC_UTC_DAY_3PCT_POST_SHOCK_DECISION_ROUTE_V1"
SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-post-shock-decision-route.v1.schema.json"
)
DECISION_SCOPE = "PREOUTCOME_RESEARCH_QUESTION_ROUTING_ONLY"
SELECTION_POLICY = "SHOCK_SIGNED_H24_DIRECTION_FIRST_THEN_VOLATILITY_ONLY_V1"
DIRECTIONAL_ROUTE = "ONE_DIRECTIONAL_POST_SHOCK_RESEARCH_QUESTION_READY"
VOLATILITY_ROUTE = "ONE_VOLATILITY_RISK_RESEARCH_QUESTION_READY"
CLOSE_ROUTE = "POST_SHOCK_RESEARCH_FAMILY_CLOSE"
TERMINAL_ROUTES = {DIRECTIONAL_ROUTE, VOLATILITY_ROUTE, CLOSE_ROUTE}

_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{2,79}$")
_UTC_TIMESTAMP = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)


def build_post_shock_decision_route(
    *,
    directional_artifact_path: str,
    directional_artifact_sha256: str,
    directional_artifact_bytes: bytes,
    volatility_artifact_path: str,
    volatility_artifact_sha256: str,
    volatility_artifact_bytes: bytes,
    sealed_at: str,
) -> dict[str, Any]:
    """Route two terminal diagnostics without creating a strategy or hypothesis."""
    directional = _canonical_object(directional_artifact_bytes, "directional artifact")
    volatility = _canonical_object(volatility_artifact_bytes, "volatility artifact")
    directional_hash = _sha256(directional_artifact_bytes)
    volatility_hash = _sha256(volatility_artifact_bytes)
    if directional_artifact_sha256 != directional_hash:
        raise ValueError("directional artifact SHA-256 mismatch")
    if volatility_artifact_sha256 != volatility_hash:
        raise ValueError("volatility artifact SHA-256 mismatch")

    lineage = _lineage_from_volatility(volatility)
    _validate_directional_source(directional, lineage)
    _validate_volatility_snapshot(volatility, lineage=lineage)
    if directional.get("terminal") is not True:
        raise ValueError("directional diagnostic is not terminal")
    if volatility.get("terminal") is not True:
        raise ValueError("volatility diagnostic is not terminal")

    _require_shared_lineage(directional, volatility, lineage)
    directional_disposition = str(directional["disposition"])
    volatility_disposition = str(volatility["disposition"])
    route, next_action = _route(directional_disposition, volatility_disposition)
    decision_available = max(
        _timestamp(str(directional["sealed_at"]), "directional sealed_at"),
        _timestamp(str(volatility["sealed_at"]), "volatility sealed_at"),
    )
    sealed = _timestamp(sealed_at, "route sealed_at")
    if sealed < decision_available:
        raise ValueError("decision route predates its latest terminal input")

    record = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "decision_scope": DECISION_SCOPE,
        "selection_policy": SELECTION_POLICY,
        "root_trigger_id": lineage.root_trigger["trigger_id"],
        "root_trigger_fingerprint": lineage.root_trigger["fingerprint"],
        "leaf_trigger_id": lineage.leaf_trigger["trigger_id"],
        "leaf_trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
        "directional_diagnostic": {
            "artifact_path": _artifact_path(
                directional_artifact_path, "directional artifact path"
            ),
            "artifact_sha256": directional_hash,
            "document_type": directional["document_type"],
            "disposition": directional_disposition,
            "sealed_at": directional["sealed_at"],
            "latest_outcome_day": directional["latest_outcome_day"],
            "latest_outcome_chain_head": directional["cumulative_chain_binding"],
        },
        "volatility_diagnostic": {
            "artifact_path": _artifact_path(
                volatility_artifact_path, "volatility artifact path"
            ),
            "artifact_sha256": volatility_hash,
            "document_type": volatility["document_type"],
            "disposition": volatility_disposition,
            "sealed_at": volatility["sealed_at"],
            "latest_outcome_day": volatility["latest_outcome_day"],
            "latest_outcome_chain_head": volatility["latest_outcome_chain_head"],
            "activation_receipt_sha256": volatility["activation_receipt_sha256"],
        },
        "decision_available_at": _timestamp_text(decision_available),
        "route_disposition": route,
        "permitted_next_action": next_action,
        "terminal": True,
        "sealed_at": _timestamp_text(sealed),
        "guardrails": {
            "immediate_pnl_effect": "ZERO",
            "immediate_drawdown_effect": "ZERO",
            "joint_predictive_value": "MISSING_PROOF",
            "shared_discovery_evidence_is_oos": False,
            "joint_interaction_evaluated": False,
            "strategy_mapping_evaluated": False,
            "hypothesis_created": False,
            "candidate_created": False,
            "oos_opened": False,
            "trading_action_attempted": False,
        },
        "authorization": RESEARCH_AUTHORIZATION,
    }
    validate_post_shock_decision_route(record)
    validate_post_shock_decision_source_bindings(
        record,
        directional_artifact_bytes=directional_artifact_bytes,
        volatility_artifact_bytes=volatility_artifact_bytes,
    )
    return record


def validate_post_shock_decision_route(value: Any) -> None:
    root_keys = {
        "schema_version", "document_type", "decision_scope", "selection_policy",
        "root_trigger_id", "root_trigger_fingerprint", "leaf_trigger_id",
        "leaf_trigger_fingerprint", "directional_diagnostic",
        "volatility_diagnostic", "decision_available_at", "route_disposition",
        "permitted_next_action", "terminal", "sealed_at", "guardrails",
        "authorization",
    }
    _exact_object(value, root_keys, "decision route")
    constants = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "decision_scope": DECISION_SCOPE,
        "selection_policy": SELECTION_POLICY,
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "terminal": True,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, expected in constants.items():
        if value.get(key) != expected:
            raise ValueError(f"decision route {key} mismatch")
    leaf_id = _identifier(value["leaf_trigger_id"], "leaf trigger id")
    leaf_fingerprint = _hex64(
        value["leaf_trigger_fingerprint"], "leaf trigger fingerprint"
    )

    directional = value["directional_diagnostic"]
    _exact_object(
        directional,
        {
            "artifact_path", "artifact_sha256", "document_type", "disposition",
            "sealed_at", "latest_outcome_day", "latest_outcome_chain_head",
        },
        "directional binding",
    )
    _artifact_path(directional["artifact_path"], "directional artifact path")
    _hex64(directional["artifact_sha256"], "directional artifact SHA-256")
    _hex64(directional["latest_outcome_chain_head"], "directional chain head")
    if directional["document_type"] not in {
        DIRECTIONAL_V1_DOCUMENT_TYPE,
        DIRECTIONAL_V2_DOCUMENT_TYPE,
    }:
        raise ValueError("directional document type is invalid")
    if directional["document_type"] == DIRECTIONAL_V1_DOCUMENT_TYPE:
        if leaf_id != ROOT_TRIGGER_ID or leaf_fingerprint != ROOT_TRIGGER_FINGERPRINT:
            raise ValueError("directional V1 must bind the root leaf")
    elif leaf_id == ROOT_TRIGGER_ID:
        raise ValueError("directional V2 requires a rolled-over leaf")
    if directional["disposition"] not in {CONTINUATION, REVERSAL, NO_FACTOR}:
        raise ValueError("directional disposition is not terminal")
    directional_sealed = _timestamp(directional["sealed_at"], "directional sealed_at")
    _day(directional["latest_outcome_day"], "directional latest outcome day")

    volatility = value["volatility_diagnostic"]
    _exact_object(
        volatility,
        {
            "artifact_path", "artifact_sha256", "document_type", "disposition",
            "sealed_at", "latest_outcome_day", "latest_outcome_chain_head",
            "activation_receipt_sha256",
        },
        "volatility binding",
    )
    _artifact_path(volatility["artifact_path"], "volatility artifact path")
    _hex64(volatility["artifact_sha256"], "volatility artifact SHA-256")
    _hex64(volatility["latest_outcome_chain_head"], "volatility chain head")
    _hex64(volatility["activation_receipt_sha256"], "activation receipt SHA-256")
    if volatility["document_type"] != VOLATILITY_DOCUMENT_TYPE:
        raise ValueError("volatility document type is invalid")
    if volatility["disposition"] not in {VOLATILITY_RETAIN, VOLATILITY_CLOSE}:
        raise ValueError("volatility disposition is not terminal")
    volatility_sealed = _timestamp(volatility["sealed_at"], "volatility sealed_at")
    _day(volatility["latest_outcome_day"], "volatility latest outcome day")

    route, next_action = _route(
        str(directional["disposition"]), str(volatility["disposition"])
    )
    if value["route_disposition"] != route:
        raise ValueError("decision route disposition is not reproducible")
    if value["permitted_next_action"] != next_action:
        raise ValueError("decision route next action is not reproducible")
    available = max(directional_sealed, volatility_sealed)
    if _timestamp(value["decision_available_at"], "decision_available_at") != available:
        raise ValueError("decision availability does not match terminal inputs")
    if _timestamp(value["sealed_at"], "route sealed_at") < available:
        raise ValueError("decision route predates terminal inputs")

    expected_guardrails = {
        "immediate_pnl_effect": "ZERO",
        "immediate_drawdown_effect": "ZERO",
        "joint_predictive_value": "MISSING_PROOF",
        "shared_discovery_evidence_is_oos": False,
        "joint_interaction_evaluated": False,
        "strategy_mapping_evaluated": False,
        "hypothesis_created": False,
        "candidate_created": False,
        "oos_opened": False,
        "trading_action_attempted": False,
    }
    if value["guardrails"] != expected_guardrails:
        raise ValueError("decision route guardrails drift")
    if value["route_disposition"] not in TERMINAL_ROUTES:
        raise ValueError("decision route is invalid")


def validate_post_shock_decision_source_bindings(
    value: Any,
    *,
    directional_artifact_bytes: bytes,
    volatility_artifact_bytes: bytes,
) -> None:
    validate_post_shock_decision_route(value)
    directional = _canonical_object(directional_artifact_bytes, "directional artifact")
    volatility = _canonical_object(volatility_artifact_bytes, "volatility artifact")
    lineage = _lineage_from_volatility(volatility)
    _validate_directional_source(directional, lineage)
    _validate_volatility_snapshot(volatility, lineage=lineage)
    _require_shared_lineage(directional, volatility, lineage)
    if (
        value["leaf_trigger_id"] != lineage.leaf_trigger["trigger_id"]
        or value["leaf_trigger_fingerprint"] != lineage.leaf_trigger["fingerprint"]
    ):
        raise ValueError("decision route source lineage binding drift")
    if value["directional_diagnostic"] != _directional_binding(
        value["directional_diagnostic"]["artifact_path"],
        directional_artifact_bytes,
        directional,
    ):
        raise ValueError("directional source binding drift")
    if value["volatility_diagnostic"] != _volatility_binding(
        value["volatility_diagnostic"]["artifact_path"],
        volatility_artifact_bytes,
        volatility,
    ):
        raise ValueError("volatility source binding drift")


def canonical_bytes(value: dict[str, Any]) -> bytes:
    validate_post_shock_decision_route(value)
    return _raw_canonical_bytes(value)


def _directional_binding(path: str, raw: bytes, value: dict[str, Any]) -> dict[str, Any]:
    return {
        "artifact_path": _artifact_path(path, "directional artifact path"),
        "artifact_sha256": _sha256(raw),
        "document_type": value["document_type"],
        "disposition": value["disposition"],
        "sealed_at": value["sealed_at"],
        "latest_outcome_day": value["latest_outcome_day"],
        "latest_outcome_chain_head": value["cumulative_chain_binding"],
    }


def _volatility_binding(path: str, raw: bytes, value: dict[str, Any]) -> dict[str, Any]:
    return {
        "artifact_path": _artifact_path(path, "volatility artifact path"),
        "artifact_sha256": _sha256(raw),
        "document_type": value["document_type"],
        "disposition": value["disposition"],
        "sealed_at": value["sealed_at"],
        "latest_outcome_day": value["latest_outcome_day"],
        "latest_outcome_chain_head": value["latest_outcome_chain_head"],
        "activation_receipt_sha256": value["activation_receipt_sha256"],
    }


def _route(directional: str, volatility: str) -> tuple[str, str]:
    if directional in {CONTINUATION, REVERSAL}:
        return (
            DIRECTIONAL_ROUTE,
            "MANAGER_REVIEW_ONE_PARENT_NEUTRAL_DIRECTIONAL_QUESTION_ONLY",
        )
    if directional == NO_FACTOR and volatility == VOLATILITY_RETAIN:
        return (
            VOLATILITY_ROUTE,
            "MANAGER_REVIEW_ONE_PARENT_NEUTRAL_VOLATILITY_RISK_QUESTION_ONLY",
        )
    if directional == NO_FACTOR and volatility == VOLATILITY_CLOSE:
        return CLOSE_ROUTE, "CLOSE_WITHOUT_RESCUE_TUNING_OR_INVERSE_RULE"
    raise ValueError("terminal diagnostic dispositions cannot be routed")


def _lineage_from_volatility(value: dict[str, Any]) -> ActiveForwardTriggerLineage:
    if value.get("root_trigger_id") != ROOT_TRIGGER_ID:
        raise ValueError("volatility root trigger id mismatch")
    if value.get("root_trigger_fingerprint") != ROOT_TRIGGER_FINGERPRINT:
        raise ValueError("volatility root trigger fingerprint mismatch")
    leaf_id = value.get("leaf_trigger_id")
    leaf_fingerprint = value.get("leaf_trigger_fingerprint")
    if not isinstance(leaf_id, str) or not leaf_id:
        raise ValueError("volatility leaf trigger id is invalid")
    _hex64(leaf_fingerprint, "volatility leaf fingerprint")
    ids = (ROOT_TRIGGER_ID,) if leaf_id == ROOT_TRIGGER_ID else (ROOT_TRIGGER_ID, leaf_id)
    return ActiveForwardTriggerLineage(
        root_trigger={"trigger_id": ROOT_TRIGGER_ID, "fingerprint": ROOT_TRIGGER_FINGERPRINT},
        root_state={},
        leaf_trigger={"trigger_id": leaf_id, "fingerprint": leaf_fingerprint},
        leaf_state={},
        trigger_ids=ids,
    )


def _validate_directional_source(
    value: dict[str, Any], lineage: ActiveForwardTriggerLineage
) -> None:
    if value.get("document_type") == DIRECTIONAL_V1_DOCUMENT_TYPE:
        if lineage.rolled_over:
            raise ValueError("directional V1 cannot bind a rolled-over leaf")
        _validate_directional_v1(value)
    elif value.get("document_type") == DIRECTIONAL_V2_DOCUMENT_TYPE:
        if not lineage.rolled_over:
            raise ValueError("directional V2 requires a rolled-over leaf")
        _validate_directional_v2(value, lineage)
    else:
        raise ValueError("directional document type is invalid")


def _require_shared_lineage(
    directional: dict[str, Any],
    volatility: dict[str, Any],
    lineage: ActiveForwardTriggerLineage,
) -> None:
    if directional.get("document_type") == DIRECTIONAL_V1_DOCUMENT_TYPE:
        expected_id = directional.get("trigger_id")
        expected_fingerprint = directional.get("trigger_fingerprint")
    else:
        expected_id = directional.get("leaf_trigger_id")
        expected_fingerprint = directional.get("leaf_trigger_fingerprint")
    if expected_id != lineage.leaf_trigger["trigger_id"]:
        raise ValueError("directional and volatility leaf trigger ids differ")
    if expected_fingerprint != lineage.leaf_trigger["fingerprint"]:
        raise ValueError("directional and volatility leaf fingerprints differ")
    if volatility.get("leaf_trigger_id") != expected_id:
        raise ValueError("volatility leaf trigger binding drift")


def _canonical_object(raw: bytes, label: str) -> dict[str, Any]:
    if not isinstance(raw, bytes):
        raise ValueError(f"{label} must be bytes")

    def reject_constant(token: str) -> None:
        raise ValueError(f"{label} contains non-finite JSON: {token}")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, item in pairs:
            if key in result:
                raise ValueError(f"{label} contains duplicate key")
            result[key] = item
        return result

    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not UTF-8 JSON") from error
    if not isinstance(value, dict) or _raw_canonical_bytes(value) != raw:
        raise ValueError(f"{label} bytes are not canonical")
    return value


def _raw_canonical_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _exact_object(value: Any, keys: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} fields are not exact")


def _hex64(value: Any, label: str) -> str:
    if not isinstance(value, str) or _HEX64.fullmatch(value) is None:
        raise ValueError(f"{label} must be lowercase SHA-256")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ValueError(f"{label} is invalid")
    return value


def _artifact_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError(f"{label} is not a contained relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"{label} is not a contained relative path")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or _UTC_TIMESTAMP.fullmatch(value) is None:
        raise ValueError(f"{label} must be canonical second-precision UTC")
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00").astimezone(timezone.utc)
    except ValueError as error:
        raise ValueError(f"{label} is invalid") from error


def _timestamp_text(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"{label} is invalid") from error
    if parsed.isoformat() != value:
        raise ValueError(f"{label} is not canonical")
    return parsed
