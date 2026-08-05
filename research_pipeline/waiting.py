from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
import re
from typing import Any

from .models import EXPERIMENT_ID, RESEARCH_AUTHORIZATION, parse_timestamp


TRIGGER_STATUSES = {
    "WAITING",
    "REVIEW_DUE",
    "READY_FOR_HYPOTHESIS",
    "CLOSED",
}
REVIEW_OUTCOMES = {"WAIT", "READY_FOR_HYPOTHESIS", "CLOSE"}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DETERMINISTIC_COMPLETE_DAY_CHECKS = {
    "closed_bar_causality",
    "no_gap_or_duplicate",
    "no_gap_or_duplicate_complete_hours",
    "immutable_row_count_and_sha256",
    "mechanism_neutral_diagnostic_before_strategy_mapping",
    "new_hypothesis_fingerprint_not_in_closed_tree",
}


def build_evidence_trigger(value: dict[str, Any]) -> dict[str, Any]:
    required = {
        "schema_version",
        "trigger_id",
        "title",
        "rationale",
        "source",
        "evidence_start",
        "review_not_before",
        "minimum_observations",
        "observation_unit",
        "required_integrity_checks",
        "prohibited_inferences",
        "excluded_branches",
        "created_at",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"evidence trigger missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1":
        raise ValueError("evidence trigger schema_version must be 1")
    trigger_id = _nonblank(value, "trigger_id")
    if not EXPERIMENT_ID.fullmatch(trigger_id):
        raise ValueError("trigger_id must be a 3-80 character lowercase slug")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("evidence trigger authorization must remain research-only")
    created_at = str(value["created_at"])
    evidence_start = str(value["evidence_start"])
    review_not_before = str(value["review_not_before"])
    created = parse_timestamp(created_at, "created_at")
    evidence = parse_timestamp(evidence_start, "evidence_start")
    review = parse_timestamp(review_not_before, "review_not_before")
    if created > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise ValueError("created_at must not be in the future")
    if evidence < created:
        raise ValueError("evidence_start must not precede created_at")
    if review <= evidence:
        raise ValueError("review_not_before must be after evidence_start")
    minimum_observations = int(value["minimum_observations"])
    if minimum_observations < 1:
        raise ValueError("minimum_observations must be positive")
    observation_unit = _nonblank(value, "observation_unit")
    if observation_unit == "COMPLETE_UTC_DAY":
        if evidence.time() != datetime.min.time() or review.time() != datetime.min.time():
            raise ValueError("COMPLETE_UTC_DAY trigger boundaries must be UTC midnight")
        window_seconds = int((review - evidence).total_seconds())
        if window_seconds % 86400 != 0:
            raise ValueError("COMPLETE_UTC_DAY trigger window must contain whole UTC days")
        window_days = window_seconds // 86400
        if minimum_observations > window_days:
            raise ValueError(
                "minimum_observations cannot exceed the frozen complete-day window"
            )
    integrity = _string_list(value["required_integrity_checks"], "required_integrity_checks")
    if observation_unit == "COMPLETE_UTC_DAY":
        unsupported = sorted(set(integrity).difference(DETERMINISTIC_COMPLETE_DAY_CHECKS))
        if unsupported:
            raise ValueError(
                "COMPLETE_UTC_DAY trigger has unsupported deterministic checks: "
                + ", ".join(unsupported)
            )
    prohibited = _string_list(value["prohibited_inferences"], "prohibited_inferences")
    excluded = _string_list(value["excluded_branches"], "excluded_branches")
    record = {
        "schema_version": "1",
        "trigger_id": trigger_id,
        "title": _nonblank(value, "title"),
        "rationale": _nonblank(value, "rationale"),
        "source": _nonblank(value, "source"),
        "evidence_start": evidence_start,
        "review_not_before": review_not_before,
        "minimum_observations": minimum_observations,
        "observation_unit": observation_unit,
        "required_integrity_checks": integrity,
        "prohibited_inferences": prohibited,
        "excluded_branches": excluded,
        "created_at": created_at,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    record["fingerprint"] = trigger_fingerprint(record)
    return record


def effective_trigger_status(
    state: dict[str, Any],
    *,
    now: datetime | None = None,
) -> str:
    status = str(state.get("status"))
    if status not in TRIGGER_STATUSES:
        raise ValueError(f"invalid evidence trigger status: {status}")
    if status == "WAITING":
        current = now or datetime.now(timezone.utc)
        next_review = parse_timestamp(str(state["next_review_at"]), "next_review_at")
        if next_review <= current:
            return "REVIEW_DUE"
    return status


def build_evidence_review(
    value: dict[str, Any],
    *,
    now: datetime | None = None,
) -> dict[str, Any]:
    required = {
        "schema_version",
        "trigger_id",
        "reviewed_at",
        "outcome",
        "conclusion",
        "evidence_artifacts",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"evidence review missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1":
        raise ValueError("evidence review schema_version must be 1")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("evidence review authorization must remain research-only")
    trigger_id = _nonblank(value, "trigger_id")
    if not EXPERIMENT_ID.fullmatch(trigger_id):
        raise ValueError("trigger_id must be a 3-80 character lowercase slug")
    reviewed_at = str(value["reviewed_at"])
    reviewed = parse_timestamp(reviewed_at, "reviewed_at")
    current = now or datetime.now(timezone.utc)
    if reviewed > current + timedelta(minutes=5):
        raise ValueError("reviewed_at must not be in the future")
    outcome = _nonblank(value, "outcome")
    if outcome not in REVIEW_OUTCOMES:
        raise ValueError(f"outcome must be one of {sorted(REVIEW_OUTCOMES)}")
    artifacts_value = value["evidence_artifacts"]
    if not isinstance(artifacts_value, list):
        raise ValueError("evidence_artifacts must be a list")
    artifacts: list[dict[str, str]] = []
    for index, artifact in enumerate(artifacts_value):
        if not isinstance(artifact, dict):
            raise ValueError(f"evidence_artifacts[{index}] must be an object")
        path = str(artifact.get("path", "")).strip()
        sha256 = str(artifact.get("sha256", "")).strip().lower()
        if not path or not SHA256.fullmatch(sha256):
            raise ValueError(
                f"evidence_artifacts[{index}] requires path and lowercase sha256"
            )
        artifact_type = str(artifact.get("artifact_type", "SUPPORTING_ARTIFACT")).strip()
        if artifact_type not in {"SUPPORTING_ARTIFACT", "FORWARD_EVIDENCE_MANIFEST"}:
            raise ValueError(f"evidence_artifacts[{index}].artifact_type is invalid")
        artifacts.append(
            {"path": path, "sha256": sha256, "artifact_type": artifact_type}
        )
    if outcome == "READY_FOR_HYPOTHESIS":
        manifests = [
            artifact
            for artifact in artifacts
            if artifact["artifact_type"] == "FORWARD_EVIDENCE_MANIFEST"
        ]
        if len(manifests) != 1:
            raise ValueError(
                "READY_FOR_HYPOTHESIS requires exactly one forward evidence manifest"
            )
    next_review_at = value.get("next_review_at")
    if outcome == "WAIT":
        if next_review_at is None:
            raise ValueError("WAIT requires next_review_at")
        next_review = parse_timestamp(str(next_review_at), "next_review_at")
        if next_review <= reviewed:
            raise ValueError("next_review_at must be after reviewed_at")
    elif next_review_at is not None:
        raise ValueError("next_review_at is only valid for WAIT")
    return {
        "schema_version": "1",
        "trigger_id": trigger_id,
        "reviewed_at": reviewed_at,
        "outcome": outcome,
        "conclusion": _nonblank(value, "conclusion"),
        "evidence_artifacts": artifacts,
        "next_review_at": None if next_review_at is None else str(next_review_at),
        "authorization": RESEARCH_AUTHORIZATION,
        "created_at": current.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def trigger_fingerprint(record: dict[str, Any]) -> str:
    payload = {
        key: record[key]
        for key in (
            "source",
            "evidence_start",
            "minimum_observations",
            "observation_unit",
            "required_integrity_checks",
            "prohibited_inferences",
            "excluded_branches",
        )
    }
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _string_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{field} must be a non-empty list")
    result = [str(item).strip() for item in value]
    if any(not item for item in result):
        raise ValueError(f"{field} must not contain blanks")
    if len(result) != len(set(result)):
        raise ValueError(f"{field} must not contain duplicates")
    return result


def _nonblank(value: dict[str, Any], field: str) -> str:
    result = str(value[field]).strip()
    if not result:
        raise ValueError(f"{field} must not be blank")
    return result
