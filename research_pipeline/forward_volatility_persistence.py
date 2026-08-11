from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from collections import Counter
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from typing import Any

from .evidence import evidence_progress
from .forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    ROOT_TRIGGER_FINGERPRINT,
    ROOT_TRIGGER_ID,
    resolve_active_forward_trigger_lineage,
)
from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .post_shock_factor import (
    _validate_shock_diagnostic,
    _validate_shock_diagnostic_v2,
)
from .shock_attribution import DIAGNOSTIC_NAMESPACE, V2_DIAGNOSTIC_NAMESPACE
from .storage import (
    ResearchStore,
    read_json,
    resolve_store_reference,
    sha256_file,
    store_relative_reference,
)


DOCUMENT_TYPE = "BTC_UTC_DAY_3PCT_FORWARD_VOLATILITY_PERSISTENCE_SNAPSHOT_V1"
ACTIVATION_DOCUMENT_TYPE = (
    "BTC_UTC_DAY_3PCT_FORWARD_VOLATILITY_PERSISTENCE_ACTIVATION_V1"
)
ACCEPTED_TASK_ID = "local-node-btc-3pct-forward-volatility-persistence-evaluator-v2"
ACCEPTED_TASK_SHA256 = (
    "357277d7bdf8c14fded335cf5ae83dd092aa9b72781c205b2c51c54b68884747"
)
FORMULA_VERSION = "BTC_UTC_DAY_3PCT_RV24_BASELINE20_OUTCOME_RATIO_V1"
SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-forward-volatility-persistence.v1.schema.json"
)
SNAPSHOT_NAMESPACE = (
    Path("forward-volatility-persistence") / "btc-utc-day-3pct-v1"
)
WAIT = "WAIT_FOR_FORWARD_VOLATILITY_PERSISTENCE_EVIDENCE"
RETAIN = "FORWARD_VOLATILITY_PERSISTENCE_DIAGNOSTIC_RETAIN"
CLOSE = "FORWARD_VOLATILITY_PERSISTENCE_DIAGNOSTIC_CLOSE"
TERMINAL_DISPOSITIONS = {RETAIN, CLOSE}
FAILED_RETAIN_GATES = "FAILED_RETAIN_GATES"
HARD_CAP_INCOMPLETE_BREADTH = "HARD_CAP_INCOMPLETE_BREADTH"
SHOCK_THRESHOLD = Decimal("0.0300")
MINIMUM_EPISODES = 12
HARD_CAP_EPISODES = 24
_HEX40 = re.compile(r"^[0-9a-f]{40}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_DECIMAL_TEXT = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


def seal_forward_volatility_persistence_snapshots(
    store: ResearchStore,
    *,
    now: datetime,
    activation_receipt: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    """Seal at most one snapshot and return at most one terminal Coach event."""
    if activation_receipt is None:
        return []
    current = now.astimezone(timezone.utc)
    activation = _validate_activation_receipt(activation_receipt, current=current)
    lineage = resolve_active_forward_trigger_lineage(store)
    if lineage is None:
        return []
    _validate_lineage_root(lineage)
    if (
        activation["leaf_trigger_id"] != lineage.leaf_trigger["trigger_id"]
        or activation["leaf_trigger_fingerprint"]
        != lineage.leaf_trigger["fingerprint"]
    ):
        return []

    trigger = lineage.leaf_trigger
    state = lineage.leaf_state
    evidence_progress(store, trigger, state, now=current)
    raw_observations = state.get("evidence_observations")
    if not isinstance(raw_observations, list):
        raise ValueError("volatility persistence observations must be a list")
    references = [_validated_reference(store, item) for item in raw_observations]
    _require_ordered_unique_references(references)
    activation_hash = _sha256_bytes(_canonical_bytes(activation_receipt))

    snapshots = _load_snapshots(store, lineage=lineage)
    terminals = [value for _, value in snapshots if value["terminal"]]
    if len(terminals) > 1:
        raise ValueError("volatility persistence has conflicting terminal snapshots")
    if terminals:
        _revalidate_terminal_snapshot(
            store,
            terminals[0],
            references=references,
            lineage=lineage,
            activation=activation,
            activation_hash=activation_hash,
        )
        return []

    prior_by_id: dict[str, dict[str, Any]] = {}
    if snapshots:
        latest = snapshots[-1][1]
        prior_by_id = {str(item["episode_id"]): item for item in latest["episodes"]}

    episodes = _eligible_episodes(
        store,
        references,
        lineage=lineage,
        activation=activation,
        activation_hash=activation_hash,
        current=current,
        prior_by_id=prior_by_id,
    )
    if not episodes:
        return []

    selected = episodes[: min(len(episodes), HARD_CAP_EPISODES)]
    for end in range(1, min(len(episodes), HARD_CAP_EPISODES) + 1):
        gates, _ = _gates_and_statistics(episodes[:end])
        if gates["all_breadth_pass"] or end == HARD_CAP_EPISODES:
            selected = episodes[:end]
            break

    latest_outcome = selected[-1]["outcome_day_reference"]
    path = _snapshot_path(
        store,
        lineage=lineage,
        day=str(latest_outcome["day"]),
        chain_head=str(latest_outcome["chain_head"]),
    )
    existing = _load_json_if_present(path)
    sealed_at = str(existing["sealed_at"]) if existing else _iso_utc(current)
    snapshot = build_forward_volatility_snapshot(
        selected,
        lineage=lineage,
        activation_receipt_sha256=activation_hash,
        evaluator_schema_sha256=activation["evaluator_schema_sha256"],
        evaluator_module_sha256=activation["evaluator_module_sha256"],
        sealed_at=sealed_at,
    )
    canonical = _canonical_bytes(snapshot)
    if existing is not None:
        if path.read_bytes() != canonical:
            raise ValueError("volatility persistence snapshot changed or conflicts")
        return []
    created = _create_only(path, canonical)
    if not created:
        if path.read_bytes() != canonical:
            raise ValueError("concurrent volatility persistence snapshot conflicts")
        return []
    if not snapshot["terminal"]:
        return []
    return [
        _coach_event(
            snapshot,
            artifact_path=store_relative_reference(store.root, path),
            artifact_sha256=sha256_file(path),
        )
    ]


def build_forward_volatility_episode(
    *,
    diagnostic: dict[str, Any],
    diagnostic_path: str,
    diagnostic_sha256: str,
    source_references: list[dict[str, str]],
    source_bundles: list[dict[str, Any]],
    lineage: ActiveForwardTriggerLineage,
    activation_receipt_sha256: str,
    evaluator_schema_sha256: str,
    evaluator_module_sha256: str,
    sealed_at: str,
) -> dict[str, Any]:
    if len(source_references) != 23 or len(source_bundles) != 23:
        raise ValueError("volatility persistence episode requires exactly 23 days")
    leaf = lineage.leaf_trigger
    public_references = [_public_reference(item) for item in source_references]
    days = [date.fromisoformat(item["day"]) for item in public_references]
    if any(days[index] != days[0] + timedelta(days=index) for index in range(23)):
        raise ValueError("volatility persistence source days are not contiguous")
    for reference, bundle in zip(public_references, source_bundles, strict=True):
        _validate_bundle(bundle, reference=reference, trigger=leaf)
    for index in range(1, len(public_references)):
        if public_references[index]["chain_head"] == public_references[index - 1]["chain_head"]:
            raise ValueError("volatility persistence evidence chain did not advance")

    target_reference = public_references[21]
    outcome_reference = public_references[22]
    prior_reference = public_references[20]
    if _public_reference(diagnostic.get("target_day")) != target_reference:
        raise ValueError("shock target does not bind the exact accepted target day")
    if _public_reference(diagnostic.get("prior_day")) != prior_reference:
        raise ValueError("shock prior day does not bind the exact accepted predecessor")

    closes = [[_decimal(bar["close"], "close", positive=True) for bar in _bars(bundle, reference["day"])] for reference, bundle in zip(public_references, source_bundles, strict=True)]
    daily_rv: list[Decimal] = []
    with localcontext() as context:
        context.prec = 60
        for index in range(1, 23):
            previous = closes[index - 1][-1]
            squared: list[Decimal] = []
            for close in closes[index]:
                hourly_return = close / previous - Decimal("1")
                squared.append(hourly_return * hourly_return)
                previous = close
            rv = sum(squared, Decimal("0"))
            if not rv.is_finite() or rv <= 0:
                raise ValueError("volatility persistence RV24 must be positive")
            daily_rv.append(rv)
        baseline = _median(daily_rv[0:20])
        outcome_rv = daily_rv[21]
        if not baseline.is_finite() or baseline <= 0:
            raise ValueError("volatility persistence baseline RV20 must be positive")
        outcome_ratio = outcome_rv / baseline
        shock_return = closes[21][-1] / closes[20][-1] - Decimal("1")
        absolute_return = abs(shock_return)
    if absolute_return < SHOCK_THRESHOLD:
        raise ValueError("volatility persistence target is below the frozen shock threshold")
    direction = "UP" if shock_return > 0 else "DOWN"
    diagnostic_path_value = diagnostic.get("path")
    if not isinstance(diagnostic_path_value, dict):
        raise ValueError("shock diagnostic path is invalid")
    if diagnostic_path_value.get("direction") != direction:
        raise ValueError("shock diagnostic direction disagrees with source closes")
    diagnostic_absolute = _decimal(
        diagnostic_path_value.get("absolute_simple_return"),
        "shock absolute_simple_return",
        positive=False,
    )
    if diagnostic_absolute != absolute_return:
        raise ValueError("shock diagnostic return disagrees with source closes")

    body = {
        "formula_version": FORMULA_VERSION,
        "activation_receipt_sha256": _require_hex64(
            activation_receipt_sha256, "activation receipt hash"
        ),
        "evaluator_schema_sha256": _require_hex64(
            evaluator_schema_sha256, "evaluator schema hash"
        ),
        "evaluator_module_sha256": _require_hex64(
            evaluator_module_sha256, "evaluator module hash"
        ),
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "leaf_trigger_id": str(leaf["trigger_id"]),
        "leaf_trigger_fingerprint": _require_hex64(
            leaf["fingerprint"], "leaf trigger fingerprint"
        ),
        "target_day": target_reference["day"],
        "source_days": public_references,
        "target_day_reference": target_reference,
        "outcome_day_reference": outcome_reference,
        "shock_diagnostic_path": _require_nonempty_string(
            diagnostic_path, "shock diagnostic path"
        ),
        "shock_diagnostic_sha256": _require_hex64(
            diagnostic_sha256, "shock diagnostic hash"
        ),
        "shock_direction": direction,
        "absolute_simple_return": _decimal_text(absolute_return),
        "baseline_rv20": _decimal_text(baseline),
        "outcome_rv24": _decimal_text(outcome_rv),
        "outcome_ratio": _decimal_text(outcome_ratio),
        "label": (
            "PERSISTENT_VOLATILITY"
            if outcome_ratio > Decimal("1")
            else "NON_PERSISTENT_VOLATILITY"
        ),
    }
    episode = {
        "episode_id": _sha256_bytes(_canonical_bytes(body)),
        **body,
        "sealed_at": _canonical_timestamp(sealed_at, "episode sealed_at"),
    }
    _validate_episode(episode)
    return episode


def build_forward_volatility_snapshot(
    episodes: list[dict[str, Any]],
    *,
    lineage: ActiveForwardTriggerLineage,
    activation_receipt_sha256: str,
    evaluator_schema_sha256: str,
    evaluator_module_sha256: str,
    sealed_at: str,
) -> dict[str, Any]:
    if not episodes:
        raise ValueError("volatility persistence snapshot requires episodes")
    ordered = sorted(episodes, key=lambda item: (item["target_day"], item["episode_id"]))
    ids = [str(item["episode_id"]) for item in ordered]
    if len(ids) != len(set(ids)):
        raise ValueError("volatility persistence episode identity is duplicated")
    if len(ordered) > HARD_CAP_EPISODES:
        raise ValueError("volatility persistence snapshot exceeds the hard cap")
    gates, statistics = _gates_and_statistics(ordered)
    if gates["all_breadth_pass"]:
        if statistics["retain_conditions_met"]:
            disposition = RETAIN
            close_reason = None
        else:
            disposition = CLOSE
            close_reason = FAILED_RETAIN_GATES
    elif gates["hard_cap_reached"]:
        disposition = CLOSE
        close_reason = HARD_CAP_INCOMPLETE_BREADTH
    else:
        disposition = WAIT
        close_reason = None
    latest = ordered[-1]["outcome_day_reference"]
    leaf = lineage.leaf_trigger
    snapshot = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "leaf_trigger_id": str(leaf["trigger_id"]),
        "leaf_trigger_fingerprint": str(leaf["fingerprint"]),
        "activation_receipt_sha256": activation_receipt_sha256,
        "evaluator_schema_sha256": evaluator_schema_sha256,
        "evaluator_module_sha256": evaluator_module_sha256,
        "snapshot_key": f"{latest['day']}:{latest['chain_head']}",
        "latest_outcome_day": latest["day"],
        "latest_outcome_chain_head": latest["chain_head"],
        "sealed_at": _canonical_timestamp(sealed_at, "snapshot sealed_at"),
        "disposition": disposition,
        "terminal": disposition in TERMINAL_DISPOSITIONS,
        "episodes": ordered,
        "gate_evidence": gates,
        "statistics": statistics,
        "close_reason": close_reason,
        "guardrails": {
            "immediate_pnl_effect": "ZERO",
            "immediate_drawdown_effect": "ZERO",
            "predictive_value": "MISSING_PROOF",
            "causal_value": "MISSING_PROOF",
            "strategy_mapping_evaluated": False,
            "hypothesis_created": False,
            "candidate_created": False,
            "oos_opened": False,
            "trading_action_attempted": False,
        },
        "authorization": RESEARCH_AUTHORIZATION,
    }
    _validate_snapshot(snapshot, lineage=lineage)
    return snapshot


def _eligible_episodes(
    store: ResearchStore,
    references: list[dict[str, Any]],
    *,
    lineage: ActiveForwardTriggerLineage,
    activation: dict[str, Any],
    activation_hash: str,
    current: datetime,
    prior_by_id: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    leaf = lineage.leaf_trigger
    diagnostic_root = store.root / (
        DIAGNOSTIC_NAMESPACE
        if not lineage.rolled_over
        else V2_DIAGNOSTIC_NAMESPACE / str(leaf["fingerprint"])
    )
    if not diagnostic_root.exists():
        return []
    if diagnostic_root.is_symlink() or not diagnostic_root.is_dir():
        raise ValueError("volatility persistence diagnostic namespace is unsafe")
    children = sorted(diagnostic_root.iterdir())
    if any(
        child.is_symlink() or not child.is_file() or child.suffix != ".json"
        for child in children
    ):
        raise ValueError("volatility persistence diagnostic inventory is unsafe")
    reference_index = {item["day"]: index for index, item in enumerate(references)}
    activated_at = parse_timestamp(activation["activated_at"], "activated_at").astimezone(timezone.utc)
    episodes: list[dict[str, Any]] = []
    for path in children:
        diagnostic = read_json(path)
        if lineage.rolled_over:
            _validate_shock_diagnostic_v2(diagnostic, lineage)
        else:
            _validate_shock_diagnostic(diagnostic)
        if diagnostic.get("eligibility") != "FORWARD_FACTOR_ELIGIBLE":
            continue
        target = str(diagnostic["target_day"]["day"])
        if path.name != f"{target}.json":
            raise ValueError("volatility persistence diagnostic path binding mismatch")
        target_index = reference_index.get(target)
        if target_index is None or target_index < 21 or target_index + 1 >= len(references):
            continue
        window = references[target_index - 21 : target_index + 2]
        if len(window) != 23:
            continue
        target_received = parse_timestamp(window[21]["received_at"], "target received_at").astimezone(timezone.utc)
        diagnostic_sealed = parse_timestamp(str(diagnostic["sealed_at"]), "diagnostic sealed_at").astimezone(timezone.utc)
        outcome_received = parse_timestamp(window[22]["received_at"], "outcome received_at").astimezone(timezone.utc)
        if target_received <= activated_at or diagnostic_sealed <= activated_at:
            continue
        if diagnostic_sealed < target_received:
            raise ValueError("volatility persistence diagnostic predates target receipt")
        if outcome_received <= diagnostic_sealed:
            raise ValueError("volatility persistence outcome predates diagnostic seal")
        if current < outcome_received:
            raise ValueError("volatility persistence outcome is not yet accepted")
        bundles = [read_json(item["path"]) for item in window]
        provisional = build_forward_volatility_episode(
            diagnostic=diagnostic,
            diagnostic_path=store_relative_reference(store.root, path),
            diagnostic_sha256=sha256_file(path),
            source_references=[_public_reference(item) for item in window],
            source_bundles=bundles,
            lineage=lineage,
            activation_receipt_sha256=activation_hash,
            evaluator_schema_sha256=activation["evaluator_schema_sha256"],
            evaluator_module_sha256=activation["evaluator_module_sha256"],
            sealed_at=_iso_utc(current),
        )
        prior = prior_by_id.get(provisional["episode_id"])
        if prior is not None:
            expected = dict(provisional)
            expected["sealed_at"] = prior.get("sealed_at")
            if expected != prior:
                raise ValueError("sealed volatility persistence episode drifted")
            provisional = prior
        episodes.append(provisional)
    episodes.sort(key=lambda item: (item["target_day"], item["episode_id"]))
    ids = [item["episode_id"] for item in episodes]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate volatility persistence episode identity")
    return episodes


def _gates_and_statistics(
    episodes: list[dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not episodes:
        raise ValueError("volatility persistence gates require episodes")
    ratios = [_decimal(item["outcome_ratio"], "outcome_ratio", positive=True) for item in episodes]
    directions = [str(item["shock_direction"]) for item in episodes]
    if any(item not in {"UP", "DOWN"} for item in directions):
        raise ValueError("volatility persistence shock direction is invalid")
    count = len(episodes)
    up_values = [ratio for ratio, direction in zip(ratios, directions, strict=True) if direction == "UP"]
    down_values = [ratio for ratio, direction in zip(ratios, directions, strict=True) if direction == "DOWN"]
    split = count // 2
    months = Counter(str(item["target_day"])[:7] for item in episodes)
    maximum_month_share = Decimal(max(months.values())) / Decimal(count)
    contributions = [abs(value - Decimal("1")) for value in ratios]
    contribution_total = sum(contributions, Decimal("0"))
    maximum_contribution_share = (
        Decimal("0")
        if contribution_total == 0
        else max(contributions) / contribution_total
    )
    gates = {
        "episode_count": count,
        "minimum_episode_count_pass": count >= MINIMUM_EPISODES,
        "up_count": len(up_values),
        "up_count_pass": len(up_values) >= 3,
        "down_count": len(down_values),
        "down_count_pass": len(down_values) >= 3,
        "first_half_count": split,
        "first_half_pass": split >= 3,
        "second_half_count": count - split,
        "second_half_pass": count - split >= 3,
        "distinct_target_month_count": len(months),
        "target_month_count_pass": len(months) >= 4,
        "maximum_target_month_share": _decimal_text(maximum_month_share),
        "target_month_concentration_pass": maximum_month_share <= Decimal("0.40"),
        "maximum_episode_contribution_share": _decimal_text(maximum_contribution_share),
        "episode_concentration_pass": maximum_contribution_share <= Decimal("0.40"),
        "all_breadth_pass": False,
        "hard_cap_reached": count == HARD_CAP_EPISODES,
    }
    gates["all_breadth_pass"] = all(
        gates[key]
        for key in (
            "minimum_episode_count_pass", "up_count_pass", "down_count_pass",
            "first_half_pass", "second_half_pass", "target_month_count_pass",
            "target_month_concentration_pass", "episode_concentration_pass",
        )
    )
    overall_median = _median(ratios)
    up_median = _median(up_values) if up_values else None
    down_median = _median(down_values) if down_values else None
    above_count = sum(value > Decimal("1") for value in ratios)
    above_share = Decimal(above_count) / Decimal(count)
    statistics = {
        "overall_median_outcome_ratio": _decimal_text(overall_median),
        "up_median_outcome_ratio": None if up_median is None else _decimal_text(up_median),
        "down_median_outcome_ratio": None if down_median is None else _decimal_text(down_median),
        "strictly_above_one_count": above_count,
        "strictly_above_one_share": _decimal_text(above_share),
        "overall_median_pass": overall_median >= Decimal("1.10"),
        "up_median_pass": up_median is not None and up_median > Decimal("1"),
        "down_median_pass": down_median is not None and down_median > Decimal("1"),
        "strictly_above_one_share_pass": above_share >= Decimal("0.55"),
        "retain_conditions_met": False,
    }
    statistics["retain_conditions_met"] = all(
        statistics[key]
        for key in (
            "overall_median_pass", "up_median_pass", "down_median_pass",
            "strictly_above_one_share_pass",
        )
    )
    return gates, statistics


def _validate_activation_receipt(
    value: Any, *, current: datetime
) -> dict[str, Any]:
    keys = {
        "schema_version", "document_type", "activated_at", "implementation_commit",
        "accepted_task_id", "accepted_task_sha256", "accepted_result_sha256",
        "evaluator_schema_sha256", "evaluator_module_sha256", "worker_release_id",
        "worker_source_commit", "worker_manifest_sha256", "root_trigger_id",
        "root_trigger_fingerprint", "leaf_trigger_id", "leaf_trigger_fingerprint",
        "authorization",
    }
    _require_exact_object(value, keys, "activation receipt")
    expected = {
        "schema_version": "1",
        "document_type": ACTIVATION_DOCUMENT_TYPE,
        "accepted_task_id": ACCEPTED_TASK_ID,
        "accepted_task_sha256": ACCEPTED_TASK_SHA256,
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, expected_value in expected.items():
        if value.get(key) != expected_value:
            raise ValueError(f"activation receipt {key} mismatch")
    if _canonical_timestamp(value["activated_at"], "activation activated_at") != value["activated_at"]:
        raise ValueError("activation receipt activated_at is not canonical")
    activated = parse_timestamp(value["activated_at"], "activation activated_at").astimezone(timezone.utc)
    if activated > current:
        raise ValueError("activation receipt is in the future")
    _require_hex40(value["implementation_commit"], "implementation commit")
    _require_hex64(value["accepted_result_sha256"], "accepted result hash")
    schema_hash = _require_hex64(value["evaluator_schema_sha256"], "evaluator schema hash")
    module_hash = _require_hex64(value["evaluator_module_sha256"], "evaluator module hash")
    if not SCHEMA_PATH.is_file() or SCHEMA_PATH.is_symlink() or sha256_file(SCHEMA_PATH) != schema_hash:
        raise ValueError("activation receipt evaluator schema hash mismatch")
    module_path = Path(__file__)
    if module_path.is_symlink() or not module_path.is_file() or sha256_file(module_path) != module_hash:
        raise ValueError("activation receipt evaluator module hash mismatch")
    _require_nonempty_string(value["worker_release_id"], "worker release id")
    _require_hex40(value["worker_source_commit"], "worker source commit")
    _require_hex64(value["worker_manifest_sha256"], "worker manifest hash")
    _require_nonempty_string(value["leaf_trigger_id"], "leaf trigger id")
    _require_hex64(value["leaf_trigger_fingerprint"], "leaf trigger fingerprint")
    return dict(value)


def _validate_lineage_root(lineage: ActiveForwardTriggerLineage) -> None:
    if (
        lineage.root_trigger.get("trigger_id") != ROOT_TRIGGER_ID
        or lineage.root_trigger.get("fingerprint") != ROOT_TRIGGER_FINGERPRINT
    ):
        raise ValueError("volatility persistence root lineage drift")


def _validated_reference(store: ResearchStore, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("volatility persistence reference must be an object")
    required = {"day", "path", "sha256", "chain_head", "received_at"}
    if not required.issubset(value):
        raise ValueError("volatility persistence reference is incomplete")
    path = resolve_store_reference(store.root, value["path"])
    if path.is_symlink() or not path.is_file():
        raise ValueError("volatility persistence source artifact is unsafe")
    if sha256_file(path) != value["sha256"]:
        raise ValueError("volatility persistence source artifact hash mismatch")
    return {
        "day": str(value["day"]),
        "path": path,
        "artifact_path": store_relative_reference(store.root, path),
        "artifact_sha256": str(value["sha256"]),
        "chain_head": str(value["chain_head"]),
        "received_at": str(value["received_at"]),
    }


def _require_ordered_unique_references(references: list[dict[str, Any]]) -> None:
    days = [date.fromisoformat(item["day"]) for item in references]
    if len(days) != len(set(days)) or days != sorted(days):
        raise ValueError("volatility persistence references are not unique and ordered")
    for item in references:
        _require_hex64(item["artifact_sha256"], "artifact hash")
        _require_hex64(item["chain_head"], "chain head")
        _canonical_timestamp(item["received_at"], "received_at")


def _validate_bundle(
    bundle: dict[str, Any], *, reference: dict[str, str], trigger: dict[str, Any]
) -> None:
    expected = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "day": reference["day"],
        "received_at": reference["received_at"],
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, expected_value in expected.items():
        if bundle.get(key) != expected_value:
            raise ValueError(f"volatility persistence bundle {key} mismatch")
    bars = _bars(bundle, reference["day"])
    day_start = datetime.combine(date.fromisoformat(reference["day"]), time.min, timezone.utc)
    for index, bar in enumerate(bars):
        expected_start = day_start + timedelta(hours=index)
        if parse_timestamp(str(bar.get("interval_start")), "bar interval_start").astimezone(timezone.utc) != expected_start:
            raise ValueError("volatility persistence bar is off the hourly grid")
        if parse_timestamp(str(bar.get("interval_end")), "bar interval_end").astimezone(timezone.utc) != expected_start + timedelta(hours=1):
            raise ValueError("volatility persistence bar is incomplete")
        open_price = _decimal(bar.get("open"), "open", positive=True)
        high_price = _decimal(bar.get("high"), "high", positive=True)
        low_price = _decimal(bar.get("low"), "low", positive=True)
        close_price = _decimal(bar.get("close"), "close", positive=True)
        _decimal(bar.get("volume"), "volume", positive=False)
        if high_price < max(open_price, close_price) or low_price > min(open_price, close_price) or high_price < low_price:
            raise ValueError("volatility persistence OHLC bounds are invalid")


def _bars(bundle: dict[str, Any], label: str) -> list[dict[str, Any]]:
    bars = bundle.get("bars")
    if not isinstance(bars, list) or len(bars) != 24 or not all(isinstance(item, dict) for item in bars):
        raise ValueError(f"volatility persistence {label} requires 24 bars")
    return bars


def _snapshot_root(
    store: ResearchStore, *, lineage: ActiveForwardTriggerLineage
) -> Path:
    fingerprint = _require_hex64(lineage.leaf_trigger["fingerprint"], "leaf fingerprint")
    root = store.root.resolve()
    namespace = root / SNAPSHOT_NAMESPACE / fingerprint / "snapshots"
    namespace.mkdir(parents=True, exist_ok=True)
    resolved = namespace.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError("volatility persistence namespace escapes research state") from error
    if resolved != namespace:
        raise ValueError("volatility persistence namespace traverses a link")
    return namespace


def _snapshot_path(
    store: ResearchStore,
    *,
    lineage: ActiveForwardTriggerLineage,
    day: str,
    chain_head: str,
) -> Path:
    date.fromisoformat(day)
    _require_hex64(chain_head, "snapshot chain head")
    return _snapshot_root(store, lineage=lineage) / f"{day}--{chain_head}.json"


def _load_snapshots(
    store: ResearchStore, *, lineage: ActiveForwardTriggerLineage
) -> list[tuple[Path, dict[str, Any]]]:
    namespace = (
        store.root
        / SNAPSHOT_NAMESPACE
        / str(lineage.leaf_trigger["fingerprint"])
        / "snapshots"
    )
    if not namespace.exists():
        return []
    if namespace.is_symlink() or not namespace.is_dir():
        raise ValueError("volatility persistence snapshot namespace is unsafe")
    children = sorted(namespace.iterdir())
    if any(child.is_symlink() or not child.is_file() or child.suffix != ".json" for child in children):
        raise ValueError("volatility persistence snapshot inventory is unsafe")
    result: list[tuple[Path, dict[str, Any]]] = []
    for path in children:
        value = read_json(path)
        _validate_snapshot(value, lineage=lineage)
        expected = f"{value['latest_outcome_day']}--{value['latest_outcome_chain_head']}.json"
        if path.name != expected:
            raise ValueError("volatility persistence snapshot path binding mismatch")
        result.append((path, value))
    result.sort(key=lambda pair: (len(pair[1]["episodes"]), pair[1]["latest_outcome_day"], pair[0].name))
    for index in range(1, len(result)):
        previous = result[index - 1][1]
        current = result[index][1]
        count = len(previous["episodes"])
        if len(current["episodes"]) <= count or current["episodes"][:count] != previous["episodes"]:
            raise ValueError("volatility persistence snapshots are not append-only")
        if previous["terminal"]:
            raise ValueError("volatility persistence snapshot exists after terminal")
    return result


def _revalidate_terminal_snapshot(
    store: ResearchStore,
    snapshot: dict[str, Any],
    *,
    references: list[dict[str, Any]],
    lineage: ActiveForwardTriggerLineage,
    activation: dict[str, Any],
    activation_hash: str,
) -> None:
    if snapshot["activation_receipt_sha256"] != activation_hash:
        raise ValueError("terminal volatility persistence activation binding changed")
    by_day = {item["day"]: item for item in references}
    rebuilt: list[dict[str, Any]] = []
    for episode in snapshot["episodes"]:
        source_days = episode["source_days"]
        current_window: list[dict[str, Any]] = []
        for source in source_days:
            current = by_day.get(source["day"])
            if current is None or _public_reference(current) != source:
                raise ValueError("terminal volatility persistence source reference changed")
            current_window.append(current)
        diagnostic_path = resolve_store_reference(store.root, episode["shock_diagnostic_path"])
        if diagnostic_path.is_symlink() or not diagnostic_path.is_file() or sha256_file(diagnostic_path) != episode["shock_diagnostic_sha256"]:
            raise ValueError("terminal volatility persistence diagnostic changed")
        diagnostic = read_json(diagnostic_path)
        if lineage.rolled_over:
            _validate_shock_diagnostic_v2(diagnostic, lineage)
        else:
            _validate_shock_diagnostic(diagnostic)
        rebuilt_episode = build_forward_volatility_episode(
            diagnostic=diagnostic,
            diagnostic_path=episode["shock_diagnostic_path"],
            diagnostic_sha256=episode["shock_diagnostic_sha256"],
            source_references=[_public_reference(item) for item in current_window],
            source_bundles=[read_json(item["path"]) for item in current_window],
            lineage=lineage,
            activation_receipt_sha256=activation_hash,
            evaluator_schema_sha256=activation["evaluator_schema_sha256"],
            evaluator_module_sha256=activation["evaluator_module_sha256"],
            sealed_at=episode["sealed_at"],
        )
        if rebuilt_episode != episode:
            raise ValueError("terminal volatility persistence episode is not reproducible")
        rebuilt.append(rebuilt_episode)
    expected = build_forward_volatility_snapshot(
        rebuilt,
        lineage=lineage,
        activation_receipt_sha256=activation_hash,
        evaluator_schema_sha256=activation["evaluator_schema_sha256"],
        evaluator_module_sha256=activation["evaluator_module_sha256"],
        sealed_at=snapshot["sealed_at"],
    )
    if _canonical_bytes(expected) != _canonical_bytes(snapshot):
        raise ValueError("terminal volatility persistence snapshot is not reproducible")


def _validate_snapshot(value: Any, *, lineage: ActiveForwardTriggerLineage) -> None:
    keys = {
        "schema_version", "document_type", "root_trigger_id", "root_trigger_fingerprint",
        "leaf_trigger_id", "leaf_trigger_fingerprint", "activation_receipt_sha256",
        "evaluator_schema_sha256", "evaluator_module_sha256", "snapshot_key",
        "latest_outcome_day", "latest_outcome_chain_head", "sealed_at", "disposition",
        "terminal", "episodes", "gate_evidence", "statistics", "close_reason",
        "guardrails", "authorization",
    }
    _require_exact_object(value, keys, "volatility persistence snapshot")
    constants = {
        "schema_version": "1", "document_type": DOCUMENT_TYPE,
        "root_trigger_id": ROOT_TRIGGER_ID, "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "leaf_trigger_id": lineage.leaf_trigger["trigger_id"],
        "leaf_trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, expected in constants.items():
        if value.get(key) != expected:
            raise ValueError(f"volatility persistence snapshot {key} mismatch")
    for key in (
        "activation_receipt_sha256",
        "evaluator_schema_sha256",
        "evaluator_module_sha256",
        "leaf_trigger_fingerprint",
        "latest_outcome_chain_head",
    ):
        _require_hex64(value.get(key), key)
    episodes = value.get("episodes")
    if not isinstance(episodes, list) or not 1 <= len(episodes) <= HARD_CAP_EPISODES:
        raise ValueError("volatility persistence snapshot episode count is invalid")
    for episode in episodes:
        _validate_episode(episode)
        episode_bindings = {
            "activation_receipt_sha256": value["activation_receipt_sha256"],
            "evaluator_schema_sha256": value["evaluator_schema_sha256"],
            "evaluator_module_sha256": value["evaluator_module_sha256"],
            "leaf_trigger_id": value["leaf_trigger_id"],
            "leaf_trigger_fingerprint": value["leaf_trigger_fingerprint"],
        }
        if any(episode.get(key) != expected for key, expected in episode_bindings.items()):
            raise ValueError("volatility persistence episode lineage or activation binding drift")
    gates, statistics = _gates_and_statistics(episodes)
    if value.get("gate_evidence") != gates or value.get("statistics") != statistics:
        raise ValueError("volatility persistence snapshot gates or statistics drift")
    if value.get("disposition") == WAIT:
        expected_terminal, expected_close = False, None
        if gates["all_breadth_pass"] or gates["hard_cap_reached"]:
            raise ValueError("volatility persistence WAIT contradicts terminal gates")
    elif value.get("disposition") == RETAIN:
        expected_terminal, expected_close = True, None
        if not gates["all_breadth_pass"] or not statistics["retain_conditions_met"]:
            raise ValueError("volatility persistence RETAIN contradicts gates")
    elif value.get("disposition") == CLOSE:
        expected_terminal = True
        expected_close = FAILED_RETAIN_GATES if gates["all_breadth_pass"] else HARD_CAP_INCOMPLETE_BREADTH
        if expected_close == FAILED_RETAIN_GATES and statistics["retain_conditions_met"]:
            raise ValueError("volatility persistence CLOSE contradicts retain gates")
        if expected_close == HARD_CAP_INCOMPLETE_BREADTH and len(episodes) != HARD_CAP_EPISODES:
            raise ValueError("volatility persistence hard close is premature")
    else:
        raise ValueError("volatility persistence disposition is invalid")
    if value.get("terminal") is not expected_terminal or value.get("close_reason") != expected_close:
        raise ValueError("volatility persistence terminal state contradicts disposition")
    latest = episodes[-1]["outcome_day_reference"]
    if value.get("latest_outcome_day") != latest["day"] or value.get("latest_outcome_chain_head") != latest["chain_head"] or value.get("snapshot_key") != f"{latest['day']}:{latest['chain_head']}":
        raise ValueError("volatility persistence snapshot key drift")
    guardrails = value.get("guardrails")
    expected_guardrails = {
        "immediate_pnl_effect": "ZERO", "immediate_drawdown_effect": "ZERO",
        "predictive_value": "MISSING_PROOF", "causal_value": "MISSING_PROOF",
        "strategy_mapping_evaluated": False, "hypothesis_created": False,
        "candidate_created": False, "oos_opened": False,
        "trading_action_attempted": False,
    }
    if guardrails != expected_guardrails:
        raise ValueError("volatility persistence guardrails drift")
    _canonical_timestamp(value["sealed_at"], "snapshot sealed_at")


def _validate_episode(value: Any) -> None:
    keys = {
        "episode_id", "formula_version", "activation_receipt_sha256",
        "evaluator_schema_sha256", "evaluator_module_sha256", "root_trigger_id",
        "root_trigger_fingerprint", "leaf_trigger_id", "leaf_trigger_fingerprint",
        "target_day", "source_days", "target_day_reference", "outcome_day_reference",
        "shock_diagnostic_path", "shock_diagnostic_sha256", "shock_direction",
        "absolute_simple_return", "baseline_rv20", "outcome_rv24", "outcome_ratio",
        "label", "sealed_at",
    }
    _require_exact_object(value, keys, "volatility persistence episode")
    if value.get("formula_version") != FORMULA_VERSION or value.get("root_trigger_id") != ROOT_TRIGGER_ID or value.get("root_trigger_fingerprint") != ROOT_TRIGGER_FINGERPRINT:
        raise ValueError("volatility persistence episode contract drift")
    source_days = value.get("source_days")
    if not isinstance(source_days, list) or len(source_days) != 23:
        raise ValueError("volatility persistence episode source inventory is invalid")
    public = [_public_reference(item) for item in source_days]
    if value.get("target_day_reference") != public[21] or value.get("outcome_day_reference") != public[22] or value.get("target_day") != public[21]["day"]:
        raise ValueError("volatility persistence episode day binding drift")
    for key in ("activation_receipt_sha256", "evaluator_schema_sha256", "evaluator_module_sha256", "leaf_trigger_fingerprint", "shock_diagnostic_sha256"):
        _require_hex64(value.get(key), key)
    for key in ("absolute_simple_return", "baseline_rv20", "outcome_rv24", "outcome_ratio"):
        _decimal(value.get(key), key, positive=True)
    if value.get("shock_direction") not in {"UP", "DOWN"} or value.get("label") not in {"PERSISTENT_VOLATILITY", "NON_PERSISTENT_VOLATILITY"}:
        raise ValueError("volatility persistence episode classification is invalid")
    body = {key: value[key] for key in keys if key not in {"episode_id", "sealed_at"}}
    if value.get("episode_id") != _sha256_bytes(_canonical_bytes(body)):
        raise ValueError("volatility persistence episode identity mismatch")
    _canonical_timestamp(value["sealed_at"], "episode sealed_at")


def _load_json_if_present(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError("volatility persistence snapshot is unsafe")
    value = read_json(path)
    if not isinstance(value, dict):
        raise ValueError("volatility persistence snapshot must be an object")
    return value


def _create_only(path: Path, content: bytes) -> bool:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{path.name}.", suffix=".tmp",
            dir=path.parent, delete=False,
        ) as stream:
            temporary_name = stream.name
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary_name, path)
            return True
        except FileExistsError:
            return False
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _coach_event(
    snapshot: dict[str, Any], *, artifact_path: str, artifact_sha256: str
) -> dict[str, Any]:
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": artifact_path,
        "sha256": artifact_sha256,
        "research_status": "BTC_UTC_DAY_3PCT_FORWARD_VOLATILITY_PERSISTENCE_TERMINAL",
        "material_conclusion": (
            f"Forward volatility-persistence diagnostic sealed {snapshot['disposition']} "
            f"at the earliest terminal prefix of {len(snapshot['episodes'])} episodes."
        ),
        "pnl_drawdown_evidence": {"immediate_effect": "ZERO", "economic_value": "MISSING_PROOF"},
        "evidence_diagnostic": {
            "diagnostic_type": DOCUMENT_TYPE,
            "disposition": snapshot["disposition"],
            "episode_count": len(snapshot["episodes"]),
            "gate_evidence": snapshot["gate_evidence"],
        },
        "uncertainty": (
            "Prediction, causality, significance, strategy mapping, fees, slippage, "
            "capacity, matched-capital PnL, drawdown, candidate readiness, OOS, "
            "deployment, activation, and Trading value remain MISSING_PROOF."
        ),
        "next_action": "MANAGER_REVIEW_ONLY_NO_HYPOTHESIS_CANDIDATE_OR_OOS",
        "concept_to_teach": (
            "A frozen point-in-time volatility diagnostic can retain or close a "
            "mechanism without selecting a trading direction or tuning after outcomes."
        ),
    }


def _public_reference(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ValueError("volatility persistence public reference must be an object")
    result = {
        "day": value.get("day"),
        "artifact_path": value.get("artifact_path", value.get("path")),
        "artifact_sha256": value.get("artifact_sha256", value.get("sha256")),
        "chain_head": value.get("chain_head"),
        "received_at": value.get("received_at"),
    }
    if not all(isinstance(item, str) and item for item in result.values()):
        raise ValueError("volatility persistence public reference is incomplete")
    date.fromisoformat(result["day"])
    _require_hex64(result["artifact_sha256"], "reference artifact hash")
    _require_hex64(result["chain_head"], "reference chain head")
    _canonical_timestamp(result["received_at"], "reference received_at")
    return result


def _require_exact_object(value: Any, keys: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} fields are invalid")


def _require_nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a non-empty string")
    return value


def _require_hex40(value: Any, label: str) -> str:
    text = _require_nonempty_string(value, label)
    if _HEX40.fullmatch(text) is None:
        raise ValueError(f"{label} must be a lowercase 40-hex commit")
    return text


def _require_hex64(value: Any, label: str) -> str:
    text = _require_nonempty_string(value, label)
    if _HEX64.fullmatch(text) is None:
        raise ValueError(f"{label} must be lowercase sha256")
    return text


def _decimal(value: Any, label: str, *, positive: bool) -> Decimal:
    if not isinstance(value, (str, int, Decimal)) or isinstance(value, bool):
        raise ValueError(f"{label} is invalid")
    text = str(value)
    if isinstance(value, str) and _DECIMAL_TEXT.fullmatch(text) is None:
        raise ValueError(f"{label} is not canonical decimal text")
    try:
        result = Decimal(text)
    except InvalidOperation as error:
        raise ValueError(f"{label} is invalid") from error
    if not result.is_finite() or (result <= 0 if positive else result < 0):
        raise ValueError(f"{label} is outside its valid domain")
    return result


def _median(values: list[Decimal]) -> Decimal:
    if not values:
        raise ValueError("volatility persistence median requires values")
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / Decimal("2")


def _decimal_text(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in {"", "-0"} else text


def _canonical_timestamp(value: Any, label: str) -> str:
    text = _require_nonempty_string(value, label)
    parsed = parse_timestamp(text, label).astimezone(timezone.utc)
    return _iso_utc(parsed)


def _canonical_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
