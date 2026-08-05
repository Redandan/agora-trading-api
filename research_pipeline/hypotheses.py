from __future__ import annotations

import hashlib
import re
from typing import Any

from .models import EXPERIMENT_ID, RESEARCH_AUTHORIZATION, Stage, now_utc, parse_timestamp


HYPOTHESIS_STATUSES = {
    "READY",
    "BLOCKED_CAPABILITY",
    "BLOCKED_DATA",
    "REGISTERED",
    "CLOSED_REJECTED",
    "CLOSED_LEARNED",
    "BLOCKED_EVIDENCE",
}
READINESS_VALUES = {"READY", "MISSING_DATA"}
RANKING_FIELDS = (
    "economic_mechanism",
    "interpretability",
    "evidence_readiness",
    "opportunity_cost_reduction",
)


def build_hypothesis(
    value: dict[str, Any],
    *,
    available_capabilities: set[str],
) -> dict[str, Any]:
    required = {
        "schema_version",
        "hypothesis_id",
        "title",
        "thesis",
        "mechanism",
        "economic_rationale",
        "source",
        "parent",
        "required_capability",
        "data_readiness",
        "expected_metrics",
        "ranking",
        "research_cycle_id",
        "created_at",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"hypothesis missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1":
        raise ValueError("hypothesis schema_version must be 1")
    hypothesis_id = _nonblank(value, "hypothesis_id")
    if not EXPERIMENT_ID.fullmatch(hypothesis_id):
        raise ValueError("hypothesis_id must be a 3-80 character lowercase slug")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("hypothesis authorization must remain research-only")
    parse_timestamp(str(value["created_at"]), "created_at")
    readiness = _nonblank(value, "data_readiness")
    if readiness not in READINESS_VALUES:
        raise ValueError(f"data_readiness must be one of {sorted(READINESS_VALUES)}")
    expected_metrics = value["expected_metrics"]
    if not isinstance(expected_metrics, list) or not expected_metrics:
        raise ValueError("expected_metrics must be a non-empty list")
    metrics = [str(metric).strip() for metric in expected_metrics]
    if any(not metric for metric in metrics):
        raise ValueError("expected_metrics must not contain blanks")
    ranking_value = value["ranking"]
    if not isinstance(ranking_value, dict):
        raise ValueError("ranking must be an object")
    ranking: dict[str, int] = {}
    for field in RANKING_FIELDS:
        score = int(ranking_value.get(field, 0))
        if score < 1 or score > 5:
            raise ValueError(f"ranking.{field} must be between 1 and 5")
        ranking[field] = score
    capability = _nonblank(value, "required_capability")
    if readiness != "READY":
        status = "BLOCKED_DATA"
    elif capability not in available_capabilities:
        status = "BLOCKED_CAPABILITY"
    else:
        status = "READY"
    thesis = _nonblank(value, "thesis")
    mechanism = _nonblank(value, "mechanism")
    parent = _nonblank(value, "parent")
    created_at = str(value["created_at"])
    return {
        "schema_version": "1",
        "hypothesis_id": hypothesis_id,
        "title": _nonblank(value, "title"),
        "thesis": thesis,
        "mechanism": mechanism,
        "economic_rationale": _nonblank(value, "economic_rationale"),
        "source": _nonblank(value, "source"),
        "parent": parent,
        "required_capability": capability,
        "data_readiness": readiness,
        "expected_metrics": metrics,
        "ranking": ranking,
        "rank_score": sum(ranking.values()),
        "research_cycle_id": _nonblank(value, "research_cycle_id"),
        "fingerprint": hypothesis_fingerprint(parent, thesis, mechanism),
        "status": status,
        "experiment_id": None,
        "experiment_stage": None,
        "outcome": None,
        "learning_artifact": None,
        "created_at": created_at,
        "updated_at": now_utc(),
        "authorization": RESEARCH_AUTHORIZATION,
    }


def build_imported_hypothesis(
    hypothesis_id: str,
    manifest: dict[str, Any],
    state: dict[str, Any],
) -> dict[str, Any]:
    if not EXPERIMENT_ID.fullmatch(hypothesis_id):
        raise ValueError("hypothesis_id must be a 3-80 character lowercase slug")
    thesis = str(manifest["thesis"])
    parent = str(manifest["parent"])
    mechanism = f"Historical experiment executed by adapter {manifest['adapter']}"
    objective = manifest.get("objective", {})
    metrics = [str(objective.get("primary_metric", "historical_outcome"))]
    record = {
        "schema_version": "1",
        "hypothesis_id": hypothesis_id,
        "title": str(manifest["title"]),
        "thesis": thesis,
        "mechanism": mechanism,
        "economic_rationale": str(manifest["economic_rationale"]),
        "source": "IMPORTED_EXISTING_EXPERIMENT",
        "parent": parent,
        "required_capability": str(manifest["adapter"]),
        "data_readiness": "READY",
        "expected_metrics": metrics,
        "ranking": {field: 1 for field in RANKING_FIELDS},
        "rank_score": len(RANKING_FIELDS),
        "research_cycle_id": "HISTORICAL_IMPORT",
        "fingerprint": hypothesis_fingerprint(parent, thesis, mechanism),
        "status": "REGISTERED",
        "experiment_id": state["experiment_id"],
        "experiment_stage": state["stage"],
        "outcome": state.get("outcome"),
        "learning_artifact": state.get("artifacts", {}).get("learning"),
        "created_at": str(manifest["created_at"]),
        "updated_at": now_utc(),
        "authorization": RESEARCH_AUTHORIZATION,
        "imported_existing_experiment": True,
    }
    return sync_hypothesis_record(record, state)


def hypothesis_fingerprint(parent: str, thesis: str, mechanism: str) -> str:
    normalized = "\n".join(_normalize(value) for value in (parent, thesis, mechanism))
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def select_next(records: list[dict[str, Any]]) -> dict[str, Any] | None:
    ready = [record for record in records if record.get("status") == "READY"]
    if not ready:
        return None
    ready.sort(
        key=lambda record: (
            -int(record.get("rank_score", 0)),
            str(record.get("created_at", "")),
            str(record["hypothesis_id"]),
        )
    )
    return ready[0]


def refresh_readiness(
    record: dict[str, Any],
    *,
    available_capabilities: set[str],
) -> dict[str, Any]:
    if record.get("experiment_id"):
        return record
    if record.get("data_readiness") != "READY":
        status = "BLOCKED_DATA"
    elif record.get("required_capability") not in available_capabilities:
        status = "BLOCKED_CAPABILITY"
    else:
        status = "READY"
    record["status"] = status
    record["updated_at"] = now_utc()
    return record


def sync_hypothesis_record(
    record: dict[str, Any],
    state: dict[str, Any],
) -> dict[str, Any]:
    stage = str(state["stage"])
    outcome = state.get("outcome")
    if stage in {Stage.PREREGISTERED.value, Stage.OOS_READY.value}:
        status = "REGISTERED"
    elif stage == Stage.CLOSED.value:
        status = "CLOSED_REJECTED" if _is_rejected_outcome(str(outcome)) else "CLOSED_LEARNED"
    else:
        status = "BLOCKED_EVIDENCE"
    record["status"] = status
    record["experiment_id"] = state["experiment_id"]
    record["experiment_stage"] = stage
    record["outcome"] = outcome
    record["learning_artifact"] = state.get("artifacts", {}).get("learning")
    record["updated_at"] = now_utc()
    return record


def _is_rejected_outcome(outcome: str) -> bool:
    return outcome.startswith("NO_CANDIDATE") or outcome in {
        "NO_NEXT_HYPOTHESIS",
        "DATA_REJECT",
        "LEAKAGE_REJECT",
        "BASELINE_REJECT",
        "ACCOUNTING_REJECT",
        "OUTPUT_SEAL_REJECT",
        "OOS_SEAL_REJECT",
        "OUT_OF_SAMPLE_FAIL",
    }


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def _nonblank(value: dict[str, Any], field: str) -> str:
    result = str(value[field]).strip()
    if not result:
        raise ValueError(f"{field} must not be blank")
    return result
