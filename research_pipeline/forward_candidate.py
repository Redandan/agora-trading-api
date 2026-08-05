from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
from typing import Any

from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .storage import ResearchStore, read_json, sha256_file
from .waiting import build_evidence_trigger


DIAGNOSTIC_CONTRACT_PATH = Path(__file__).with_name(
    "forward-diagnostic-contract.v1.json"
)
DIAGNOSTIC_CONTRACT_ID = "PROSPECTIVE_MARKET_MECHANISM_DIAGNOSTIC_V1"
FORWARD_ADAPTER_KEY = "dra-forward-entry-admission-v1"
FORWARD_ADAPTER_CONTRACT_ID = "DRA_FORWARD_ENTRY_ADMISSION_V1"
FORWARD_PARENT = "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH"
FORWARD_SELECTION_CUTOFF = "2025-01-01T00:00:00Z"
FORWARD_SOURCE = (
    "server-local read-only OKX BTCUSDT complete 1h bars aggregated into complete UTC days"
)
OOS_OBSERVATION_DAYS = 90


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def load_diagnostic_contract() -> dict[str, Any]:
    if not DIAGNOSTIC_CONTRACT_PATH.is_file():
        raise ValueError("forward diagnostic contract is missing")
    value = read_json(DIAGNOSTIC_CONTRACT_PATH)
    if not isinstance(value, dict):
        raise ValueError("forward diagnostic contract must be a JSON object")
    required = {
        "schema_version",
        "contract_id",
        "observation_unit",
        "minimum_observations",
        "response_horizon_days",
        "mechanisms",
        "predictive_gates",
        "selection_rule",
        "guardrails",
        "authorization",
    }
    if set(value) != required:
        raise ValueError("forward diagnostic contract fields are not frozen")
    if value["schema_version"] != "1" or value["contract_id"] != DIAGNOSTIC_CONTRACT_ID:
        raise ValueError("forward diagnostic contract identity is invalid")
    if value["observation_unit"] != "COMPLETE_UTC_DAY":
        raise ValueError("forward diagnostic contract observation unit is invalid")
    if int(value["minimum_observations"]) != OOS_OBSERVATION_DAYS:
        raise ValueError("forward diagnostic contract minimum observations changed")
    if int(value["response_horizon_days"]) != 1:
        raise ValueError("forward diagnostic response horizon changed")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("forward diagnostic contract must remain research-only")
    mechanisms = value["mechanisms"]
    if not isinstance(mechanisms, list) or len(mechanisms) != 2:
        raise ValueError("forward diagnostic contract must freeze exactly two mechanisms")
    keys: set[str] = set()
    expected_features = {
        "DRA_ENTRY_VOLUME_CONFIRMATION_20D": "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN",
        "DRA_ENTRY_RANGE_CONFIRMATION_20D": "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN",
    }
    for item in mechanisms:
        if not isinstance(item, dict):
            raise ValueError("forward diagnostic mechanism must be an object")
        if set(item) != {"key", "feature", "lookback_days", "direction", "thresholds"}:
            raise ValueError("forward diagnostic mechanism fields changed")
        key = str(item["key"])
        if key in keys:
            raise ValueError("forward diagnostic mechanism keys must be unique")
        keys.add(key)
        if expected_features.get(key) != item["feature"]:
            raise ValueError("forward diagnostic mechanism key/feature mapping changed")
        if int(item["lookback_days"]) != 20:
            raise ValueError("forward diagnostic lookback must remain 20 days")
        if item["direction"] != "GREATER_THAN_OR_EQUAL":
            raise ValueError("forward diagnostic direction changed")
        thresholds = item["thresholds"]
        if not isinstance(thresholds, dict) or set(thresholds) != {
            "lower_neighbor",
            "primary",
            "upper_neighbor",
        }:
            raise ValueError("forward diagnostic thresholds are invalid")
        if thresholds != {
            "lower_neighbor": "1.25",
            "primary": "1.50",
            "upper_neighbor": "1.75",
        }:
            raise ValueError("forward diagnostic thresholds changed")
    if keys != set(expected_features):
        raise ValueError("forward diagnostic mechanism inventory changed")
    gates = value["predictive_gates"]
    expected_gates = {
        "minimum_labeled_events": 8,
        "minimum_events_per_half": 3,
        "minimum_event_coverage_pct": "8.00",
        "maximum_event_coverage_pct": "50.00",
        "minimum_median_next_day_return_delta_pct": "0.05000000",
        "minimum_positive_next_day_share_pct": "55.00",
        "maximum_top_month_positive_contribution_pct": "70.00",
        "require_positive_first_half_delta": True,
        "require_positive_second_half_delta": True,
    }
    if gates != expected_gates:
        raise ValueError("forward diagnostic predictive gates changed")
    selection = value["selection_rule"]
    if selection != {
        "maximum_selected_mechanisms": 1,
        "rank_by": [
            "median_next_day_return_delta_pct_desc",
            "labeled_event_count_desc",
            "mechanism_key_asc",
        ],
        "neighbor_thresholds_are_stability_checks_not_candidates": True,
    }:
        raise ValueError("forward diagnostic selection must remain single-mechanism")
    if value["guardrails"] != [
        "diagnostic_window_is_discovery_not_oos",
        "no_strategy_pnl_is_evaluated",
        "no_threshold_is_changed_after_outcome_access",
        "closed_branch_exclusions_remain_binding",
        "at_most_one_mechanism_can_seed_the_next_hypothesis",
    ]:
        raise ValueError("forward diagnostic guardrails changed")
    return value


def diagnostic_contract_status() -> dict[str, Any]:
    try:
        value = load_diagnostic_contract()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return {
            "status": "INVALID",
            "contract_id": DIAGNOSTIC_CONTRACT_ID,
            "reason": type(error).__name__,
        }
    return {
        "status": "READY",
        "contract_id": DIAGNOSTIC_CONTRACT_ID,
        "sha256": sha256_file(DIAGNOSTIC_CONTRACT_PATH),
        "mechanisms": [str(item["key"]) for item in value["mechanisms"]],
        "minimum_observations": int(value["minimum_observations"]),
    }


def _next_midnight_after(value: datetime) -> datetime:
    current = value.astimezone(timezone.utc)
    return datetime.combine(
        current.date() + timedelta(days=1),
        datetime.min.time(),
        tzinfo=timezone.utc,
    )


def candidate_oos_window(
    evidence_ready_at: datetime,
    *,
    now: datetime,
) -> dict[str, Any]:
    deadline = evidence_ready_at.astimezone(timezone.utc) + timedelta(hours=24)
    stable_start = _next_midnight_after(deadline)
    current_safe_start = _next_midnight_after(now)
    start = max(stable_start, current_safe_start)
    end = start + timedelta(days=OOS_OBSERVATION_DAYS)
    return {
        "observation_days": OOS_OBSERVATION_DAYS,
        "start_at": start.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "end_at": end.isoformat(timespec="seconds").replace("+00:00", "Z"),
    }


def discovery_candidate_context(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    *,
    now: datetime,
) -> dict[str, Any] | None:
    if state.get("status") != "READY_FOR_HYPOTHESIS":
        return None
    detail = state.get("detail")
    verified = detail.get("verified_evidence") if isinstance(detail, dict) else None
    if not isinstance(verified, list) or len(verified) != 1:
        return None
    evidence = verified[0]
    diagnostic_ref = evidence.get("diagnostic_artifact")
    if not isinstance(diagnostic_ref, dict):
        return None
    diagnostic_path = (store.root / str(diagnostic_ref.get("path", ""))).resolve()
    try:
        diagnostic_path.relative_to(store.root)
    except ValueError:
        return None
    if (
        not diagnostic_path.is_file()
        or sha256_file(diagnostic_path) != diagnostic_ref.get("sha256")
    ):
        return None
    diagnostic = read_json(diagnostic_path)
    contract = diagnostic_contract_status()
    if (
        contract["status"] != "READY"
        or diagnostic.get("diagnostic_contract_id") != contract["contract_id"]
        or diagnostic.get("diagnostic_contract_sha256") != contract["sha256"]
    ):
        return None
    eligible = diagnostic.get("eligible_mechanisms")
    if not isinstance(eligible, list):
        return None
    ready_at = parse_timestamp(str(state.get("evidence_ready_at")), "evidence_ready_at")
    review = state.get("reviews")
    if not isinstance(review, list) or not review:
        return None
    review_ref = review[-1]
    review_path = (store.root / str(review_ref.get("path", ""))).resolve()
    if not review_path.is_file() or sha256_file(review_path) != review_ref.get("sha256"):
        return None
    review_value = read_json(review_path)
    manifest_refs = [
        item
        for item in review_value.get("evidence_artifacts", [])
        if item.get("artifact_type") == "FORWARD_EVIDENCE_MANIFEST"
    ]
    if len(manifest_refs) != 1:
        return None
    oos_window = candidate_oos_window(ready_at, now=now)
    evidence_binding = {
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "evidence_manifest_sha256": manifest_refs[0]["sha256"],
        "discovery_dataset_sha256": evidence["dataset_artifact"]["sha256"],
        "diagnostic_sha256": diagnostic_ref["sha256"],
        "diagnostic_contract_sha256": contract["sha256"],
        "coverage_end": evidence["coverage_end"],
        "excluded_branches_sha256": _canonical_sha256(trigger["excluded_branches"]),
    }
    return {
        "status": "READY" if eligible else "NO_SUPPORTED_MECHANISM",
        "adapter": FORWARD_ADAPTER_KEY,
        "adapter_contract_id": FORWARD_ADAPTER_CONTRACT_ID,
        "parent": FORWARD_PARENT,
        "selection_cutoff": FORWARD_SELECTION_CUTOFF,
        "max_variants": 3,
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "evidence_manifest_sha256": manifest_refs[0]["sha256"],
        "discovery_dataset_sha256": evidence["dataset_artifact"]["sha256"],
        "diagnostic_sha256": diagnostic_ref["sha256"],
        "diagnostic_contract": contract,
        "coverage_end": evidence["coverage_end"],
        "eligible_mechanisms": eligible,
        "excluded_branches": trigger["excluded_branches"],
        "oos_window": oos_window,
        "adapter_config_template": {
            "schema_version": "1",
            "contract_id": FORWARD_ADAPTER_CONTRACT_ID,
            "mechanism_key": "COPY_ONE_ELIGIBLE_MECHANISM_KEY",
            "evidence_binding": evidence_binding,
            "oos_window": oos_window,
        },
    }


def validate_forward_adapter_config(
    manifest: dict[str, Any],
    context: dict[str, Any],
) -> dict[str, Any]:
    value = manifest.get("adapter_config")
    if not isinstance(value, dict):
        raise ValueError("forward candidate manifest requires adapter_config")
    required = {
        "schema_version",
        "contract_id",
        "mechanism_key",
        "evidence_binding",
        "oos_window",
    }
    if set(value) != required:
        raise ValueError("forward candidate adapter_config fields are not frozen")
    if value["schema_version"] != "1" or value["contract_id"] != FORWARD_ADAPTER_CONTRACT_ID:
        raise ValueError("forward candidate adapter contract is invalid")
    mechanism = str(value["mechanism_key"])
    eligible = {
        str(item.get("mechanism_key"))
        for item in context.get("eligible_mechanisms", [])
        if isinstance(item, dict) and item.get("all_predictive_gates_pass") is True
    }
    if mechanism not in eligible:
        raise ValueError("forward candidate mechanism did not pass the sealed predictive gates")
    binding = value["evidence_binding"]
    if not isinstance(binding, dict):
        raise ValueError("forward candidate evidence_binding must be an object")
    expected_binding = {
        "trigger_id": context["trigger_id"],
        "trigger_fingerprint": context["trigger_fingerprint"],
        "evidence_manifest_sha256": context["evidence_manifest_sha256"],
        "discovery_dataset_sha256": context["discovery_dataset_sha256"],
        "diagnostic_sha256": context["diagnostic_sha256"],
        "diagnostic_contract_sha256": context["diagnostic_contract"]["sha256"],
        "coverage_end": context["coverage_end"],
        "excluded_branches_sha256": _canonical_sha256(context["excluded_branches"]),
    }
    if binding != expected_binding:
        raise ValueError("forward candidate evidence binding does not match canonical evidence")
    if value["oos_window"] != context["oos_window"]:
        raise ValueError("forward candidate OOS window does not match canonical server time")
    if manifest.get("parent") != FORWARD_PARENT:
        raise ValueError(f"forward candidate parent must be {FORWARD_PARENT}")
    if manifest.get("selection_cutoff") != FORWARD_SELECTION_CUTOFF:
        raise ValueError("forward candidate historical selection cutoff changed")
    if manifest.get("oos_cutoff") != context["oos_window"]["end_at"]:
        raise ValueError("forward candidate oos_cutoff must equal the sealed OOS window end")
    if int(manifest.get("max_variants", 0)) != 3:
        raise ValueError("forward candidate must freeze one primary and two neighbor variants")
    return value


def build_candidate_oos_trigger(
    manifest: dict[str, Any],
    *,
    manifest_sha256: str,
    discovery_trigger: dict[str, Any],
    now: datetime,
) -> dict[str, Any]:
    config = manifest["adapter_config"]
    window = config["oos_window"]
    trigger_id = candidate_oos_trigger_id(str(manifest["experiment_id"]))
    value = {
        "schema_version": "1",
        "trigger_id": trigger_id,
        "title": f"Sealed OOS for {manifest['experiment_id']}",
        "rationale": (
            "Capture a new untouched window after the evidence-bound hypothesis and "
            "manifest were frozen; never reuse the discovery window as OOS."
        ),
        "source": FORWARD_SOURCE,
        "evidence_start": window["start_at"],
        "review_not_before": window["end_at"],
        "minimum_observations": int(window["observation_days"]),
        "observation_unit": "COMPLETE_UTC_DAY",
        "required_integrity_checks": [
            "closed_bar_causality",
            "no_gap_or_duplicate_complete_hours",
            "immutable_row_count_and_sha256",
            "candidate_manifest_frozen_before_oos_start",
        ],
        "prohibited_inferences": [
            "no candidate gate or threshold may change after OOS capture begins",
            "no OOS strategy performance may be read before the complete window is sealed",
            "no backfill retry SHADOW PAPER LIVE Production order database or scheduler implication",
        ],
        "excluded_branches": list(discovery_trigger["excluded_branches"]),
        "created_at": now.astimezone(timezone.utc).isoformat(timespec="seconds").replace(
            "+00:00", "Z"
        ),
        "authorization": RESEARCH_AUTHORIZATION,
        "purpose": "CANDIDATE_OOS",
        "candidate_binding": {
            "experiment_id": manifest["experiment_id"],
            "manifest_sha256": manifest_sha256,
            "adapter": manifest["adapter"],
            "mechanism_key": config["mechanism_key"],
        },
    }
    return build_evidence_trigger(value)


def candidate_oos_trigger_id(experiment_id: str) -> str:
    suffix = hashlib.sha256(experiment_id.encode("utf-8")).hexdigest()[:12]
    prefix = experiment_id[:56].rstrip("-")
    return f"{prefix}-oos-{suffix}"


def candidate_oos_source_contract(trigger: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1",
        "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "producer": "agora-okx-forward-source-v1",
        "transport": "SEALED_ONE_WAY_DROP_V1",
        "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
        "worker_network_access": "DENY",
        "worker_database_access": "DENY",
        "backfill": "DENY",
        "authorization": RESEARCH_AUTHORIZATION,
    }
