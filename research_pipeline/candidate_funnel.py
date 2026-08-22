from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import re
from typing import Any

from .forward_trigger_lineage import resolve_active_forward_trigger_lineage
from .forward_volatility_persistence import (
    CLOSE as VOLATILITY_CLOSE,
    HARD_CAP_EPISODES as VOLATILITY_HARD_CAP_EPISODES,
    MINIMUM_EPISODES as VOLATILITY_MINIMUM_EPISODES,
    RETAIN as VOLATILITY_RETAIN,
    _canonical_bytes as _volatility_canonical_bytes,
    _load_snapshots as _load_volatility_snapshots,
)
from .forward_volatility_persistence_activation import (
    ACTIVATION_RECEIPT_RETIRED,
    ACTIVATION_STATE_KEY,
    prepare_forward_volatility_persistence_activation,
)
from .storage import ResearchStore, sha256_file, store_relative_reference


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "PRE_CANDIDATE_POOL_CATALOG_V1"
OUTPUT_TYPE = "CANDIDATE_FUNNEL_SNAPSHOT_V1"
STATE_AUTHORITY = "SERVER_CANONICAL"
TIMER_AUTHORITY = "CODEX_CLOUD_OPS_ONLY"
CATALOG_PATH = Path(__file__).with_name("pre-candidate-pool.v1.json")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
TERMINAL_EXPERIMENT_STAGES = {
    "BLOCKED",
    "CLOSED",
    "FAILED",
    "REPORTED_NOT_ACTIVATED",
}
INTEGRITY_BLOCKING_MICROSTRUCTURE_STATUSES = {
    "CAPTURE_OVERDUE",
    "INTEGRITY_BLOCKED",
    "RECOVERY_BLOCKED",
    "SOURCE_INVALID",
}
READINESS_SCORES = {
    "READY_FOR_HYPOTHESIS": 100,
    "CANDIDATE_FROZEN": 90,
    "REGISTERED_EXPERIMENT": 85,
    "FORWARD_EVIDENCE": 70,
    "PREREGISTRATION_READY": 55,
    "HISTORICAL_PRIOR": 40,
    "DEFERRED": 20,
    "INTEGRITY_BLOCKED": 0,
}
ATTENTION_CLASSES = {
    "INTEGRITY_BLOCKED": 0,
    "READY_FOR_HYPOTHESIS": 1,
    "CANDIDATE_FROZEN": 2,
    "REGISTERED_EXPERIMENT": 2,
    "FORWARD_EVIDENCE": 3,
    "PREREGISTRATION_READY": 4,
    "HISTORICAL_PRIOR": 5,
    "DEFERRED": 6,
}
REQUIRED_ECONOMIC_METRICS = (
    "fees",
    "adverse_slippage",
    "realized_pnl",
    "unrealized_pnl",
    "total_pnl",
    "maximum_drawdown",
    "holding_age",
    "terminal_inventory",
    "breadth_and_path_risk",
)


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _fingerprint(*parts: str) -> str:
    return hashlib.sha256(_canonical_bytes(list(parts))).hexdigest()


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _require_string(value: Any, label: str, *, allow_missing: bool = False) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    if not allow_missing and value == "MISSING_PROOF":
        raise ValueError(f"{label} cannot be MISSING_PROOF")
    return value


def _verify_binding(repo_root: Path, binding: dict[str, Any], label: str) -> dict[str, Any]:
    _exact_keys(binding, {"path", "role", "sha256"}, label)
    relative = _require_string(binding["path"], f"{label}.path")
    if "\\" in relative or Path(relative).is_absolute():
        raise ValueError(f"{label}.path must be repository-relative POSIX")
    expected = _require_string(binding["sha256"], f"{label}.sha256")
    if SHA256_PATTERN.fullmatch(expected) is None:
        raise ValueError(f"{label}.sha256 is invalid")
    _require_string(binding["role"], f"{label}.role")
    path = (repo_root / relative).resolve(strict=True)
    try:
        path.relative_to(repo_root)
    except ValueError as error:
        raise ValueError(f"{label}.path escapes the repository") from error
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"{label}.path must be a regular non-link file")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"{label}.sha256 mismatch")
    return {**binding, "verified": True}


def _validate_family(repo_root: Path, value: Any, index: int) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"families[{index}] must be an object")
    expected = {
        "base_stage",
        "canonical_binding",
        "causal_hypothesis",
        "compute_cost_class",
        "decision_feature",
        "duplicate_family_key",
        "economic_evidence_status",
        "economic_hypothesis",
        "estimated_days_to_next_gate",
        "evidence_bindings",
        "family_id",
        "independence_key",
        "matched_comparator_id",
        "missing_proof",
        "next_gate",
        "parent_strategy_id",
        "runner_id",
        "title",
    }
    _exact_keys(value, expected, f"families[{index}]")
    for key in (
        "family_id",
        "title",
        "decision_feature",
        "causal_hypothesis",
        "economic_hypothesis",
        "duplicate_family_key",
        "independence_key",
        "next_gate",
    ):
        _require_string(value[key], f"families[{index}].{key}")
    for key in ("parent_strategy_id", "matched_comparator_id", "runner_id"):
        _require_string(
            value[key],
            f"families[{index}].{key}",
            allow_missing=True,
        )
    if value["base_stage"] not in READINESS_SCORES:
        raise ValueError(f"families[{index}].base_stage is unsupported")
    if value["compute_cost_class"] not in {"LOW", "MEDIUM", "HIGH"}:
        raise ValueError(f"families[{index}].compute_cost_class is unsupported")
    days = value["estimated_days_to_next_gate"]
    if days is not None and (isinstance(days, bool) or not isinstance(days, int) or days < 0):
        raise ValueError(f"families[{index}].estimated_days_to_next_gate is invalid")
    missing = value["missing_proof"]
    if not isinstance(missing, list) or not missing:
        raise ValueError(f"families[{index}].missing_proof must be nonempty")
    if any(not isinstance(item, str) or not item.strip() for item in missing):
        raise ValueError(f"families[{index}].missing_proof entries are invalid")
    economics = value["economic_evidence_status"]
    if not isinstance(economics, dict) or set(economics) != set(REQUIRED_ECONOMIC_METRICS):
        raise ValueError(f"families[{index}].economic_evidence_status is incomplete")
    allowed_economic_statuses = {
        "MISSING_PROOF",
        "REQUIRED_NOT_MEASURED",
        "HISTORICAL_PRIOR_ONLY",
        "MATCHED_CAPITAL_READY",
        "VALIDATION_PASS",
        "OOS_PASS",
    }
    if any(status not in allowed_economic_statuses for status in economics.values()):
        raise ValueError(f"families[{index}].economic_evidence_status is unsupported")
    canonical = value["canonical_binding"]
    if not isinstance(canonical, dict):
        raise ValueError(f"families[{index}].canonical_binding must be an object")
    _exact_keys(canonical, {"id", "kind"}, f"families[{index}].canonical_binding")
    if canonical["kind"] not in {
        "FORWARD_MECHANISM",
        "FORWARD_VOLATILITY_PERSISTENCE",
        "MICROSTRUCTURE",
        "NONE",
    }:
        raise ValueError(f"families[{index}].canonical_binding.kind is unsupported")
    _require_string(canonical["id"], f"families[{index}].canonical_binding.id")
    bindings = value["evidence_bindings"]
    if not isinstance(bindings, list) or not bindings:
        raise ValueError(f"families[{index}].evidence_bindings must be nonempty")
    verified = [
        _verify_binding(repo_root, binding, f"families[{index}].evidence_bindings[{offset}]")
        for offset, binding in enumerate(bindings)
    ]
    return {**value, "evidence_bindings": verified}


def _validate_closed_family(repo_root: Path, value: Any, index: int) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"closed_families[{index}] must be an object")
    expected = {
        "decision_feature",
        "disposition",
        "duplicate_family_key",
        "evidence_bindings",
        "family_id",
        "parent_strategy_id",
        "prohibited_reopen",
        "title",
    }
    _exact_keys(value, expected, f"closed_families[{index}]")
    for key in expected - {"evidence_bindings", "prohibited_reopen"}:
        _require_string(value[key], f"closed_families[{index}].{key}")
    if value["prohibited_reopen"] is not True:
        raise ValueError(f"closed_families[{index}] must prohibit reopening")
    bindings = value["evidence_bindings"]
    if not isinstance(bindings, list) or len(bindings) < 2:
        raise ValueError(f"closed_families[{index}] requires result and acceptance evidence")
    verified = [
        _verify_binding(
            repo_root,
            binding,
            f"closed_families[{index}].evidence_bindings[{offset}]",
        )
        for offset, binding in enumerate(bindings)
    ]
    return {**value, "evidence_bindings": verified}


def load_candidate_pool_catalog(
    repo_root: Path | None = None,
    catalog_path: Path | None = None,
) -> dict[str, Any]:
    root = (repo_root or Path(__file__).resolve().parents[1]).resolve(strict=True)
    path = (catalog_path or CATALOG_PATH).resolve(strict=True)
    try:
        path.relative_to(root)
    except ValueError as error:
        raise ValueError("candidate pool catalog escapes the repository") from error
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("candidate pool catalog must be an object")
    expected = {
        "authorization",
        "catalog_id",
        "closed_families",
        "document_type",
        "families",
        "pool_constraints",
        "ranking_contract",
        "schema_version",
        "state_authority",
        "timer_authority",
    }
    _exact_keys(value, expected, "candidate pool catalog")
    if value["schema_version"] != "1" or value["document_type"] != DOCUMENT_TYPE:
        raise ValueError("candidate pool catalog version or document type is unsupported")
    if value["authorization"] != AUTHORIZATION:
        raise ValueError("candidate pool catalog authorization changed")
    if value["state_authority"] != STATE_AUTHORITY:
        raise ValueError("candidate pool catalog state authority changed")
    if value["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("candidate pool catalog timer authority changed")
    _require_string(value["catalog_id"], "catalog_id")
    constraints = value["pool_constraints"]
    if constraints != {
        "maximum_active_experiments": 1,
        "maximum_candidate_oos": 1,
        "maximum_open_families": 10,
        "minimum_open_families": 5,
    }:
        raise ValueError("candidate pool constraints changed")
    ranking = value["ranking_contract"]
    if ranking != {
        "closed_family_rank": "EXCLUDED",
        "integrity_blocker_preempts": True,
        "ordered_dimensions": [
            "active_evidence_integrity",
            "evidence_readiness",
            "matched_capital_economic_visibility",
            "path_risk_visibility",
            "estimated_days_to_next_gate",
            "mechanism_independence",
            "compute_cost",
            "family_id",
        ],
        "score_semantics": "READINESS_NOT_ALPHA",
    }:
        raise ValueError("candidate pool ranking contract changed")
    families = value["families"]
    closed = value["closed_families"]
    if not isinstance(families, list) or not isinstance(closed, list):
        raise ValueError("candidate pool families must be arrays")
    if not constraints["minimum_open_families"] <= len(families) <= constraints[
        "maximum_open_families"
    ]:
        raise ValueError("candidate pool open-family count is outside the frozen bounds")
    validated_families = [
        _validate_family(root, family, index) for index, family in enumerate(families)
    ]
    validated_closed = [
        _validate_closed_family(root, family, index)
        for index, family in enumerate(closed)
    ]
    open_ids = [family["family_id"] for family in validated_families]
    closed_ids = [family["family_id"] for family in validated_closed]
    if len(open_ids) != len(set(open_ids)) or len(closed_ids) != len(set(closed_ids)):
        raise ValueError("candidate pool family ids must be unique")
    if set(open_ids) & set(closed_ids):
        raise ValueError("candidate pool family cannot be both open and closed")
    open_keys = [family["duplicate_family_key"] for family in validated_families]
    closed_keys = [family["duplicate_family_key"] for family in validated_closed]
    if len(open_keys) != len(set(open_keys)) or len(closed_keys) != len(set(closed_keys)):
        raise ValueError("candidate pool duplicate family keys must be unique")
    if set(open_keys) & set(closed_keys):
        raise ValueError("open candidate pool duplicates a closed family")
    return {
        **value,
        "catalog_path": path.relative_to(root).as_posix(),
        "catalog_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "families": validated_families,
        "closed_families": validated_closed,
    }


def _forward_mechanism_state(
    family: dict[str, Any],
    registry: dict[str, Any],
) -> dict[str, Any]:
    mechanism_id = family["canonical_binding"]["id"]
    experiments = registry.get("experiments", [])
    matching = [
        experiment
        for experiment in experiments
        if isinstance(experiment, dict)
        and experiment.get("stage") not in TERMINAL_EXPERIMENT_STAGES
        and experiment.get("candidate_mechanism_key") == mechanism_id
    ]
    if len(matching) > 1:
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "MULTIPLE_ACTIVE_EXPERIMENTS_FOR_MECHANISM",
            "progress": None,
            "next_gate": "RESTORE_SINGLE_ACTIVE_EXPERIMENT",
            "estimated_days": None,
        }
    if matching:
        experiment = matching[0]
        frozen = bool(experiment.get("candidate_frozen_at")) and bool(
            experiment.get("oos_evidence_trigger_id")
        )
        return {
            "stage": "CANDIDATE_FROZEN" if frozen else "REGISTERED_EXPERIMENT",
            "integrity_status": "READY",
            "progress": {
                "experiment_id": experiment.get("experiment_id"),
                "experiment_stage": experiment.get("stage"),
                "candidate_frozen_at": experiment.get("candidate_frozen_at"),
                "oos_evidence_trigger_id": experiment.get("oos_evidence_trigger_id"),
            },
            "next_gate": (
                "WAIT_FOR_INDEPENDENT_CANDIDATE_OOS"
                if frozen
                else "COMPLETE_FROZEN_CANDIDATE_REGISTRATION"
            ),
            "estimated_days": None,
        }
    readiness = registry.get("forward_candidate_readiness")
    if not isinstance(readiness, dict):
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "CANONICAL_FORWARD_READINESS_MISSING",
            "progress": None,
            "next_gate": "RESTORE_CANONICAL_FORWARD_READINESS",
            "estimated_days": None,
        }
    contract = readiness.get("diagnostic_contract")
    mechanisms = contract.get("mechanisms") if isinstance(contract, dict) else None
    if readiness.get("status") != "READY" or not isinstance(mechanisms, list):
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": f"FORWARD_READINESS_{readiness.get('status', 'MISSING')}",
            "progress": None,
            "next_gate": "RESTORE_FORWARD_CANDIDATE_READINESS",
            "estimated_days": None,
        }
    if mechanism_id not in mechanisms:
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "MECHANISM_NOT_IN_CANONICAL_CONTRACT",
            "progress": None,
            "next_gate": "REJECT_CATALOG_CONTRACT_DRIFT",
            "estimated_days": None,
        }
    triggers = registry.get("evidence_triggers")
    open_triggers = [
        trigger
        for trigger in (triggers if isinstance(triggers, list) else [])
        if isinstance(trigger, dict)
        and trigger.get("purpose") == "HYPOTHESIS_DISCOVERY"
        and trigger.get("status") != "CLOSED"
    ]
    if len(open_triggers) != 1:
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "EXPECTED_EXACTLY_ONE_OPEN_DISCOVERY_TRIGGER",
            "progress": None,
            "next_gate": "RESTORE_SINGLE_CANONICAL_DISCOVERY_TRIGGER",
            "estimated_days": None,
        }
    trigger = open_triggers[0]
    context = trigger.get("candidate_context")
    eligible = context.get("eligible_mechanisms") if isinstance(context, dict) else []
    if not isinstance(eligible, list):
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "ELIGIBLE_MECHANISMS_INVALID",
            "progress": None,
            "next_gate": "RESTORE_CANONICAL_CANDIDATE_CONTEXT",
            "estimated_days": None,
        }
    eligible_ids = {
        str(item.get("mechanism_key")) if isinstance(item, dict) else str(item)
        for item in eligible
    }
    stage = "READY_FOR_HYPOTHESIS" if mechanism_id in eligible_ids else "FORWARD_EVIDENCE"
    progress = trigger.get("progress") if isinstance(trigger.get("progress"), dict) else {}
    observed = progress.get("observation_count")
    required = progress.get("minimum_observations")
    remaining = required - observed if isinstance(observed, int) and isinstance(required, int) else None
    return {
        "stage": stage,
        "integrity_status": "READY",
        "progress": {
            "trigger_id": trigger.get("trigger_id"),
            "status": progress.get("status"),
            "observation_count": observed,
            "minimum_observations": required,
            "remaining_observations": remaining,
            "next_review_at": trigger.get("next_review_at"),
        },
        "next_gate": (
            "FREEZE_ONE_EVIDENCE_BOUND_HYPOTHESIS"
            if stage == "READY_FOR_HYPOTHESIS"
            else "COMPLETE_FROZEN_90_DAY_DISCOVERY"
        ),
        "estimated_days": max(0, remaining) if isinstance(remaining, int) else None,
    }


def _microstructure_state(
    family: dict[str, Any],
    microstructure: dict[str, Any] | None,
) -> dict[str, Any]:
    if not isinstance(microstructure, dict):
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "CANONICAL_MICROSTRUCTURE_STATUS_MISSING",
            "progress": None,
            "next_gate": "RESTORE_CANONICAL_MICROSTRUCTURE_STATUS",
            "estimated_days": None,
        }
    status = str(microstructure.get("status", "MISSING"))
    if status in INTEGRITY_BLOCKING_MICROSTRUCTURE_STATUSES:
        stage = "INTEGRITY_BLOCKED"
        next_gate = "REPAIR_ACTIVE_EVIDENCE_INTEGRITY_WITHOUT_BACKFILL"
    elif status in {"DIAGNOSTIC_READY", "READY"}:
        stage = "READY_FOR_HYPOTHESIS"
        next_gate = "RUN_FROZEN_INTERPRETATION_AND_DESIGN_AT_MOST_ONE_HYPOTHESIS"
    else:
        stage = "FORWARD_EVIDENCE"
        next_gate = family["next_gate"]
    observed = microstructure.get("complete_day_count", microstructure.get("accepted_day_count"))
    required = microstructure.get("required_day_count")
    if required is None:
        required = microstructure.get("calendar_day_budget")
    remaining = required - observed if isinstance(observed, int) and isinstance(required, int) else None
    return {
        "stage": stage,
        "integrity_status": status,
        "progress": {
            "diagnostic_id": microstructure.get("diagnostic_id"),
            "status": status,
            "observation_count": observed,
            "minimum_observations": required,
            "remaining_observations": remaining,
            "next_observation_day": microstructure.get("next_calendar_day"),
        },
        "next_gate": next_gate,
        "estimated_days": max(0, remaining) if isinstance(remaining, int) else None,
    }


def _static_state(family: dict[str, Any]) -> dict[str, Any]:
    return {
        "stage": family["base_stage"],
        "integrity_status": "SEALED_REPOSITORY_EVIDENCE_VERIFIED",
        "progress": None,
        "next_gate": family["next_gate"],
        "estimated_days": family["estimated_days_to_next_gate"],
    }


def _volatility_persistence_state(
    family: dict[str, Any],
    *,
    heartbeat_state: dict[str, Any] | None,
    state_root: Path | None,
    as_of: datetime,
) -> dict[str, Any]:
    if not isinstance(heartbeat_state, dict):
        return _static_state(family)
    receipt = heartbeat_state.get(ACTIVATION_STATE_KEY)
    if receipt is None:
        return _static_state(family)
    if state_root is None:
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": "VOLATILITY_CANONICAL_STATE_ROOT_MISSING",
            "progress": None,
            "next_gate": "RESTORE_READ_ONLY_CANONICAL_STATE_BINDING",
            "estimated_days": None,
        }

    store = ResearchStore(Path(state_root), lock_stale_seconds=3600)
    try:
        activation = prepare_forward_volatility_persistence_activation(
            store,
            now=as_of,
            previous_success=heartbeat_state.get("last_success"),
            existing_receipt=receipt,
        )
        if activation.created or activation.receipt is None:
            raise ValueError("read-only funnel cannot create an activation receipt")
        lineage = resolve_active_forward_trigger_lineage(store)
        if lineage is None:
            raise ValueError("volatility activation lineage is unavailable")
        if activation.status == ACTIVATION_RECEIPT_RETIRED:
            return {
                "stage": "DEFERRED",
                "integrity_status": (
                    "LAWFUL_ROLLOVER_RETIRED_LEAF_BOUND_ACTIVATION"
                ),
                "progress": {
                    "activation_status": activation.status,
                    "evidence_collection_active": False,
                    "activated_at": activation.receipt["activated_at"],
                    "receipt_leaf_trigger_id": activation.receipt[
                        "leaf_trigger_id"
                    ],
                    "receipt_leaf_trigger_fingerprint": activation.receipt[
                        "leaf_trigger_fingerprint"
                    ],
                    "current_leaf_trigger_id": lineage.leaf_trigger[
                        "trigger_id"
                    ],
                    "current_leaf_trigger_fingerprint": lineage.leaf_trigger[
                        "fingerprint"
                    ],
                },
                "next_gate": "FREEZE_VERSIONED_ACTIVATION_RECOVERY_OR_CLOSE_FAMILY",
                "estimated_days": None,
                "missing_proof": [
                    *family["missing_proof"],
                    "Versioned post-rollover activation recovery is not implemented.",
                ],
            }
        snapshots = _load_volatility_snapshots(store, lineage=lineage)
        receipt_hash = hashlib.sha256(
            _volatility_canonical_bytes(activation.receipt)
        ).hexdigest()
        for _, snapshot in snapshots:
            if snapshot.get("activation_receipt_sha256") != receipt_hash:
                raise ValueError("volatility snapshot activation receipt binding drift")
    except (FileNotFoundError, OSError, ValueError) as error:
        return {
            "stage": "INTEGRITY_BLOCKED",
            "integrity_status": f"VOLATILITY_ACTIVATION_OR_SNAPSHOT_INVALID:{error}",
            "progress": None,
            "next_gate": "RESTORE_VOLATILITY_EVIDENCE_INTEGRITY_WITHOUT_REWRITE",
            "estimated_days": None,
        }

    latest_path: Path | None = None
    latest: dict[str, Any] | None = None
    if snapshots:
        latest_path, latest = snapshots[-1]
    episode_count = len(latest["episodes"]) if latest is not None else 0
    terminal = bool(latest and latest["terminal"])
    disposition = latest.get("disposition") if latest is not None else None
    latest_reference = (
        {
            "artifact_path": store_relative_reference(store.root, latest_path),
            "sha256": sha256_file(latest_path),
        }
        if latest_path is not None
        else None
    )
    progress = {
        "activation_status": activation.status,
        "activated_at": activation.receipt["activated_at"],
        "worker_release_id": activation.receipt["worker_release_id"],
        "worker_source_commit": activation.receipt["worker_source_commit"],
        "leaf_trigger_id": activation.receipt["leaf_trigger_id"],
        "leaf_trigger_fingerprint": activation.receipt["leaf_trigger_fingerprint"],
        "snapshot_count": len(snapshots),
        "episode_count": episode_count,
        "minimum_episodes": VOLATILITY_MINIMUM_EPISODES,
        "hard_cap_episodes": VOLATILITY_HARD_CAP_EPISODES,
        "terminal": terminal,
        "disposition": disposition,
        "latest_snapshot": latest_reference,
    }
    if terminal and disposition == VOLATILITY_RETAIN:
        return {
            "stage": "READY_FOR_HYPOTHESIS",
            "integrity_status": "SEALED_FORWARD_DIAGNOSTIC_RETAIN",
            "progress": progress,
            "next_gate": "FREEZE_AT_MOST_ONE_VOLATILITY_RISK_CONTROL_HYPOTHESIS",
            "estimated_days": 0,
            "missing_proof": family["missing_proof"][1:],
        }
    if terminal and disposition == VOLATILITY_CLOSE:
        return {
            "stage": "FORWARD_EVIDENCE",
            "integrity_status": "SEALED_FORWARD_DIAGNOSTIC_CLOSE",
            "progress": progress,
            "next_gate": "KEEP_FAMILY_CLOSED_NO_RETUNING",
            "estimated_days": 0,
            "dynamic_closure": {
                "disposition": disposition,
                "artifact_path": latest_reference["artifact_path"],
                "sha256": latest_reference["sha256"],
            },
        }
    return {
        "stage": "FORWARD_EVIDENCE",
        "integrity_status": "SEALED_ACTIVATION_FORWARD_EVIDENCE_COLLECTING",
        "progress": progress,
        "next_gate": (
            "ACCUMULATE_FIRST_ELIGIBLE_FORWARD_SHOCK_EPISODE"
            if episode_count == 0
            else "ACCUMULATE_TO_EARLIEST_FROZEN_TERMINAL_PREFIX"
        ),
        "estimated_days": None,
    }


def _family_snapshot(
    family: dict[str, Any],
    registry: dict[str, Any],
    microstructure: dict[str, Any] | None,
    *,
    heartbeat_state: dict[str, Any] | None,
    state_root: Path | None,
    as_of: datetime,
) -> dict[str, Any]:
    kind = family["canonical_binding"]["kind"]
    if kind == "FORWARD_MECHANISM":
        dynamic = _forward_mechanism_state(family, registry)
    elif kind == "FORWARD_VOLATILITY_PERSISTENCE":
        dynamic = _volatility_persistence_state(
            family,
            heartbeat_state=heartbeat_state,
            state_root=state_root,
            as_of=as_of,
        )
    elif kind == "MICROSTRUCTURE":
        dynamic = _microstructure_state(family, microstructure)
    else:
        dynamic = _static_state(family)
    stage = dynamic["stage"]
    readiness_score = READINESS_SCORES[stage]
    economics = family["economic_evidence_status"]
    economic_visibility_count = sum(
        status not in {"MISSING_PROOF", "REQUIRED_NOT_MEASURED"}
        for status in economics.values()
    )
    duplicate_fingerprint = _fingerprint(
        family["duplicate_family_key"],
        family["decision_feature"],
        family["parent_strategy_id"],
        family["matched_comparator_id"],
        family["runner_id"],
    )
    return {
        "family_id": family["family_id"],
        "title": family["title"],
        "stage": stage,
        "integrity_status": dynamic["integrity_status"],
        "decision_feature": family["decision_feature"],
        "causal_hypothesis": family["causal_hypothesis"],
        "economic_hypothesis": family["economic_hypothesis"],
        "parent_strategy_id": family["parent_strategy_id"],
        "matched_comparator_id": family["matched_comparator_id"],
        "runner_id": family["runner_id"],
        "economic_evidence_status": economics,
        "economic_visibility_count": economic_visibility_count,
        "required_economic_metric_count": len(REQUIRED_ECONOMIC_METRICS),
        "estimated_days_to_next_gate": dynamic["estimated_days"],
        "compute_cost_class": family["compute_cost_class"],
        "independence_key": family["independence_key"],
        "next_gate": dynamic["next_gate"],
        "missing_proof": dynamic.get("missing_proof", family["missing_proof"]),
        "evidence_bindings": family["evidence_bindings"],
        "canonical_binding": family["canonical_binding"],
        "progress": dynamic["progress"],
        "duplicate_family_key": family["duplicate_family_key"],
        "duplicate_fingerprint": duplicate_fingerprint,
        "ranking": {
            "attention_class": ATTENTION_CLASSES[stage],
            "readiness_score": readiness_score,
            "score_semantics": "READINESS_NOT_ALPHA",
        },
        "_dynamic_closure": dynamic.get("dynamic_closure"),
    }


def _catalog_closed_snapshot(family: dict[str, Any]) -> dict[str, Any]:
    return {
        **family,
        "closed_fingerprint": _fingerprint(
            family["duplicate_family_key"],
            family["decision_feature"],
            family["parent_strategy_id"],
            family["disposition"],
        ),
        "stage": "CLOSED",
    }


def _registry_closed_snapshots(registry: dict[str, Any]) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    experiments = registry.get("experiments")
    for experiment in experiments if isinstance(experiments, list) else []:
        if not isinstance(experiment, dict) or experiment.get("stage") not in TERMINAL_EXPERIMENT_STAGES:
            continue
        experiment_id = str(experiment.get("experiment_id", ""))
        outcome = str(experiment.get("outcome") or "TERMINAL_WITHOUT_OUTCOME")
        values.append(
            {
                "family_id": f"canonical-experiment:{experiment_id}",
                "title": str(experiment.get("title") or experiment_id),
                "stage": "CLOSED",
                "disposition": outcome,
                "duplicate_family_key": experiment_id,
                "closed_fingerprint": _fingerprint(
                    experiment_id,
                    str(experiment.get("adapter", "")),
                    outcome,
                ),
                "evidence_bindings": [
                    {
                        "role": "SERVER_CANONICAL_EXPERIMENT_STATE",
                        "experiment_id": experiment_id,
                        "updated_at": experiment.get("updated_at"),
                    }
                ],
                "prohibited_reopen": True,
            }
        )
    return values


def _dynamic_closed_snapshot(
    family: dict[str, Any], closure: dict[str, Any]
) -> dict[str, Any]:
    return {
        "family_id": family["family_id"],
        "title": family["title"],
        "stage": "CLOSED",
        "disposition": closure["disposition"],
        "duplicate_family_key": family["duplicate_family_key"],
        "closed_fingerprint": _fingerprint(
            family["duplicate_family_key"],
            family["decision_feature"],
            family["parent_strategy_id"],
            closure["disposition"],
            closure["sha256"],
        ),
        "evidence_bindings": [
            *family["evidence_bindings"],
            {
                "role": "SERVER_CANONICAL_FORWARD_VOLATILITY_TERMINAL",
                "path": closure["artifact_path"],
                "sha256": closure["sha256"],
                "verified": True,
            },
        ],
        "prohibited_reopen": True,
    }


def build_candidate_funnel(
    registry: dict[str, Any],
    *,
    microstructure: dict[str, Any] | None = None,
    heartbeat_state: dict[str, Any] | None = None,
    state_root: Path | None = None,
    as_of: datetime | None = None,
    repo_root: Path | None = None,
    catalog_path: Path | None = None,
) -> dict[str, Any]:
    if not isinstance(registry, dict):
        raise ValueError("canonical registry must be an object")
    experiments = registry.get("experiments", [])
    triggers = registry.get("evidence_triggers", [])
    if not isinstance(experiments, list):
        raise ValueError("canonical experiments must be an array")
    if not isinstance(triggers, list):
        raise ValueError("canonical evidence triggers must be an array")
    catalog = load_candidate_pool_catalog(repo_root, catalog_path)
    terminal_mechanisms = {
        str(experiment.get("candidate_mechanism_key"))
        for experiment in experiments
        if isinstance(experiment, dict)
        and experiment.get("stage") in TERMINAL_EXPERIMENT_STAGES
        and experiment.get("candidate_mechanism_key")
    }
    current = (as_of or datetime.now(timezone.utc)).astimezone(timezone.utc)
    families: list[dict[str, Any]] = []
    dynamically_closed: list[dict[str, Any]] = []
    for family in catalog["families"]:
        if family["canonical_binding"]["id"] in terminal_mechanisms:
            continue
        snapshot = _family_snapshot(
            family,
            registry,
            microstructure,
            heartbeat_state=heartbeat_state,
            state_root=state_root,
            as_of=current,
        )
        closure = snapshot.pop("_dynamic_closure")
        if closure is None:
            families.append(snapshot)
        else:
            dynamically_closed.append(_dynamic_closed_snapshot(snapshot, closure))
    active_experiments = [
        experiment
        for experiment in experiments
        if isinstance(experiment, dict)
        and experiment.get("stage") not in TERMINAL_EXPERIMENT_STAGES
    ]
    candidate_oos = [
        trigger
        for trigger in triggers
        if isinstance(trigger, dict)
        and trigger.get("purpose") == "CANDIDATE_OOS"
        and trigger.get("status") != "CLOSED"
    ]
    constraint_violations: list[str] = []
    if not catalog["pool_constraints"]["minimum_open_families"] <= len(families) <= catalog[
        "pool_constraints"
    ]["maximum_open_families"]:
        constraint_violations.append("OPEN_FAMILY_COUNT_OUTSIDE_BOUNDS")
    if len(active_experiments) > catalog["pool_constraints"]["maximum_active_experiments"]:
        constraint_violations.append("MAXIMUM_ACTIVE_EXPERIMENTS_EXCEEDED")
    if len(candidate_oos) > catalog["pool_constraints"]["maximum_candidate_oos"]:
        constraint_violations.append("MAXIMUM_CANDIDATE_OOS_EXCEEDED")
    fingerprints = [family["duplicate_fingerprint"] for family in families]
    if len(fingerprints) != len(set(fingerprints)):
        constraint_violations.append("OPEN_FAMILY_DUPLICATE_FINGERPRINT")

    cost_rank = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
    families.sort(
        key=lambda family: (
            family["ranking"]["attention_class"],
            -family["ranking"]["readiness_score"],
            -family["economic_visibility_count"],
            family["estimated_days_to_next_gate"]
            if family["estimated_days_to_next_gate"] is not None
            else 1_000_000,
            cost_rank[family["compute_cost_class"]],
            family["family_id"],
        )
    )
    for rank, family in enumerate(families, start=1):
        family["rank"] = rank

    closed = [
        _catalog_closed_snapshot(family) for family in catalog["closed_families"]
    ]
    closed.extend(_registry_closed_snapshots(registry))
    closed.extend(dynamically_closed)
    closed_ids = [family["family_id"] for family in closed]
    if len(closed_ids) != len(set(closed_ids)):
        constraint_violations.append("CLOSED_FAMILY_ID_COLLISION")
    closed.sort(key=lambda family: family["family_id"])
    integrity_blocked = [
        family["family_id"] for family in families if family["stage"] == "INTEGRITY_BLOCKED"
    ]
    if constraint_violations:
        status = "INTEGRITY_BLOCKED"
    elif integrity_blocked:
        status = "READY_WITH_INTEGRITY_ALERT"
    else:
        status = "READY"
    return {
        "schema_version": "1",
        "document_type": OUTPUT_TYPE,
        "status": status,
        "authorization": AUTHORIZATION,
        "state_authority": STATE_AUTHORITY,
        "timer_authority": TIMER_AUTHORITY,
        "catalog": {
            "catalog_id": catalog["catalog_id"],
            "path": catalog["catalog_path"],
            "sha256": catalog["catalog_sha256"],
        },
        "ranking_contract": catalog["ranking_contract"],
        "pool_constraints": catalog["pool_constraints"],
        "constraint_violations": constraint_violations,
        "summary": {
            "open_family_count": len(families),
            "closed_family_count": len(closed),
            "active_experiment_count": len(active_experiments),
            "candidate_oos_count": len(candidate_oos),
            "integrity_blocked_family_count": len(integrity_blocked),
            "integrity_blocked_families": integrity_blocked,
            "formal_candidate_count": sum(
                bool(experiment.get("candidate_frozen_at"))
                and bool(experiment.get("oos_evidence_trigger_id"))
                for experiment in active_experiments
            ),
        },
        "ranked_families": families,
        "closed_families": closed,
        "safety": {
            "read_only_derived_view": True,
            "canonical_state_write": False,
            "second_timer_or_writer": False,
            "strategy_activation": False,
            "shadow_paper_live": False,
        },
    }


def candidate_funnel_status(
    registry: dict[str, Any],
    *,
    microstructure: dict[str, Any] | None = None,
    heartbeat_state: dict[str, Any] | None = None,
    state_root: Path | None = None,
    as_of: datetime | None = None,
    repo_root: Path | None = None,
) -> dict[str, Any]:
    try:
        return build_candidate_funnel(
            registry,
            microstructure=microstructure,
            heartbeat_state=heartbeat_state,
            state_root=state_root,
            as_of=as_of,
            repo_root=repo_root,
        )
    except (FileNotFoundError, json.JSONDecodeError, OSError, ValueError) as error:
        return {
            "schema_version": "1",
            "document_type": OUTPUT_TYPE,
            "status": "INTEGRITY_BLOCKED",
            "reason": str(error),
            "authorization": AUTHORIZATION,
            "state_authority": STATE_AUTHORITY,
            "timer_authority": TIMER_AUTHORITY,
            "safety": {
                "read_only_derived_view": True,
                "canonical_state_write": False,
                "second_timer_or_writer": False,
                "strategy_activation": False,
                "shadow_paper_live": False,
            },
        }
