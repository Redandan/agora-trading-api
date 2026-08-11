from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from typing import Any

from .evidence import evidence_progress
from .forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    resolve_active_forward_trigger_lineage,
)
from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .shock_attribution import (
    DIAGNOSTIC_NAMESPACE,
    R1_SOURCE,
    R1_TRIGGER_FINGERPRINT,
    R1_TRIGGER_ID,
    SCHEMA_PATH as SHOCK_SCHEMA_PATH,
    SCHEMA_SHA256 as SHOCK_SCHEMA_SHA256,
    V2_DIAGNOSTIC_NAMESPACE,
    V2_SCHEMA_PATH as SHOCK_V2_SCHEMA_PATH,
    V2_SCHEMA_SHA256 as SHOCK_V2_SCHEMA_SHA256,
)
from .storage import (
    ResearchStore,
    read_json,
    resolve_store_reference,
    sha256_file,
    store_relative_reference,
)


DOCUMENT_TYPE = "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_RESULT_V1"
SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-post-shock-factor-result.v1.schema.json"
)
SCHEMA_SHA256 = "8f41ecce6cf5820ee28404fef9bcefac4db1d42771ed01586752488142c6b317"
SNAPSHOT_NAMESPACE = (
    Path("post-shock-factor") / "btc-utc-day-3pct-v1" / "snapshots"
)
V2_DOCUMENT_TYPE = "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_RESULT_V2"
V2_SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-post-shock-factor-result.v2.schema.json"
)
V2_SCHEMA_SHA256 = "dc5ffb60b80cd3f190473373ff0956d52149e567579e2d9bbe9376cebfb05980"
V2_SNAPSHOT_NAMESPACE = Path("post-shock-factor") / "btc-utc-day-3pct-v2"

WAIT = "WAIT_FOR_MORE_UNTOUCHED_EVIDENCE"
CONTINUATION = "CONTINUATION_FACTOR_READY_FOR_MANAGER_REVIEW"
REVERSAL = "REVERSAL_FACTOR_READY_FOR_MANAGER_REVIEW"
NO_FACTOR = "NO_DIRECTIONAL_POST_SHOCK_FACTOR_CLOSE"
TERMINAL_DISPOSITIONS = {CONTINUATION, REVERSAL, NO_FACTOR}


def seal_r1_post_shock_factor_snapshots(
    store: ResearchStore,
    *,
    now: datetime,
) -> list[dict[str, Any]]:
    root_matches = [
        pair
        for pair in store.evidence_trigger_entries()
        if pair[0].get("trigger_id") == R1_TRIGGER_ID
    ]
    if not root_matches or (
        len(root_matches) == 1 and root_matches[0][1].get("status") != "CLOSED"
    ):
        return _seal_v1_post_shock_factor_snapshots(store, now=now)
    lineage = resolve_active_forward_trigger_lineage(store)
    if lineage is None:
        return []
    if not lineage.rolled_over:
        return _seal_v1_post_shock_factor_snapshots(store, now=now)
    return _seal_v2_post_shock_factor_snapshots(store, lineage=lineage, now=now)


def _seal_v1_post_shock_factor_snapshots(
    store: ResearchStore,
    *,
    now: datetime,
) -> list[dict[str, Any]]:
    """Seal one immutable snapshot and return at most one terminal Coach event."""
    current = now.astimezone(timezone.utc)
    _validate_schema_bindings()
    matches = [
        pair
        for pair in store.evidence_trigger_entries()
        if pair[0].get("trigger_id") == R1_TRIGGER_ID
    ]
    if not matches:
        return []
    if len(matches) != 1:
        raise ValueError("post-shock evaluator requires exactly one R1 trigger")
    trigger, trigger_state = matches[0]
    _validate_trigger_identity(trigger, trigger_state)
    evidence_progress(store, trigger, trigger_state, now=current)

    observations = trigger_state.get("evidence_observations")
    if not isinstance(observations, list):
        raise ValueError("post-shock observations must be a list")
    references = [_validated_reference(store, item) for item in observations]
    _require_unique_reference_days(references)

    snapshots = _load_snapshots(store)
    terminals = [item for _, item in snapshots if item["terminal"]]
    if len(terminals) > 1:
        raise ValueError("post-shock evaluator has conflicting terminal snapshots")
    if terminals:
        _revalidate_snapshot_sources(store, terminals[0], references)
        return []

    prior_episode_by_id: dict[str, dict[str, Any]] = {}
    if snapshots:
        latest = max(
            (item for _, item in snapshots),
            key=lambda item: (len(item["episodes"]), item["latest_outcome_day"]),
        )
        prior_episode_by_id = {
            str(item["episode_id"]): item for item in latest["episodes"]
        }

    episodes = _eligible_episodes(
        store,
        references,
        current=current,
        prior_episode_by_id=prior_episode_by_id,
    )
    if not episodes:
        return []

    selected = episodes
    for end in range(1, len(episodes) + 1):
        gates, _ = _gates_and_statistics(episodes[:end])
        if gates["all_breadth_pass"]:
            selected = episodes[:end]
            break

    latest_outcome = selected[-1]["outcome_day_reference"]
    snapshot_path = _snapshot_path(
        store,
        str(latest_outcome["day"]),
        str(latest_outcome["chain_head"]),
    )
    existing = _load_json_if_present(snapshot_path)
    sealed_at = (
        str(existing["sealed_at"])
        if existing is not None
        else _iso_utc(current)
    )
    snapshot = build_post_shock_snapshot(selected, sealed_at=sealed_at)
    canonical = _canonical_bytes(snapshot)
    if existing is not None:
        if snapshot_path.read_bytes() != canonical:
            raise ValueError("post-shock snapshot changed or conflicts")
        return []

    created = _create_only(snapshot_path, canonical)
    if not created:
        if snapshot_path.read_bytes() != canonical:
            raise ValueError("concurrent post-shock snapshot conflicts")
        return []
    if not snapshot["terminal"]:
        return []
    return [
        _coach_event(
            snapshot,
            artifact_path=store_relative_reference(store.root, snapshot_path),
            artifact_sha256=sha256_file(snapshot_path),
        )
    ]


def _seal_v2_post_shock_factor_snapshots(
    store: ResearchStore,
    *,
    lineage: ActiveForwardTriggerLineage,
    now: datetime,
) -> list[dict[str, Any]]:
    current = now.astimezone(timezone.utc)
    _validate_v2_schema_bindings()
    trigger = lineage.leaf_trigger
    trigger_state = lineage.leaf_state
    evidence_progress(store, trigger, trigger_state, now=current)
    observations = trigger_state.get("evidence_observations")
    if not isinstance(observations, list):
        raise ValueError("post-shock rollover observations must be a list")
    references = [_validated_reference(store, item) for item in observations]
    _require_unique_reference_days(references)

    snapshots = _load_snapshots_v2(store, lineage)
    terminals = [item for _, item in snapshots if item["terminal"]]
    if len(terminals) > 1:
        raise ValueError("post-shock V2 evaluator has conflicting terminal snapshots")
    if terminals:
        _revalidate_snapshot_sources_v2(store, terminals[0], references, lineage)
        return []

    prior_episode_by_id: dict[str, dict[str, Any]] = {}
    if snapshots:
        latest = max(
            (item for _, item in snapshots),
            key=lambda item: (len(item["episodes"]), item["latest_outcome_day"]),
        )
        prior_episode_by_id = {
            str(item["episode_id"]): item for item in latest["episodes"]
        }

    episodes = _eligible_episodes_v2(
        store,
        references,
        lineage=lineage,
        current=current,
        prior_episode_by_id=prior_episode_by_id,
    )
    if not episodes:
        return []
    selected = episodes
    for end in range(1, len(episodes) + 1):
        gates, _ = _gates_and_statistics(episodes[:end])
        if gates["all_breadth_pass"]:
            selected = episodes[:end]
            break

    latest_outcome = selected[-1]["outcome_day_reference"]
    snapshot_path = _snapshot_path_v2(
        store,
        lineage,
        str(latest_outcome["day"]),
        str(latest_outcome["chain_head"]),
    )
    existing = _load_json_if_present(snapshot_path)
    sealed_at = str(existing["sealed_at"]) if existing else _iso_utc(current)
    snapshot = _build_post_shock_snapshot_v2(
        selected, lineage=lineage, sealed_at=sealed_at
    )
    canonical = _canonical_bytes(snapshot)
    if existing is not None:
        if snapshot_path.read_bytes() != canonical:
            raise ValueError("post-shock V2 snapshot changed or conflicts")
        return []
    created = _create_only(snapshot_path, canonical)
    if not created and snapshot_path.read_bytes() != canonical:
        raise ValueError("concurrent post-shock V2 snapshot conflicts")
    if not created or not snapshot["terminal"]:
        return []
    event = _coach_event(
        snapshot,
        artifact_path=store_relative_reference(store.root, snapshot_path),
        artifact_sha256=sha256_file(snapshot_path),
    )
    event["evidence_diagnostic"]["diagnostic_type"] = V2_DOCUMENT_TYPE
    return [event]


def build_post_shock_episode(
    *,
    diagnostic: dict[str, Any],
    diagnostic_path: str,
    diagnostic_sha256: str,
    outcome_reference: dict[str, str],
    outcome_bundle: dict[str, Any],
    sealed_at: str,
) -> dict[str, Any]:
    if diagnostic.get("eligibility") != "FORWARD_FACTOR_ELIGIBLE":
        raise ValueError("post-shock episode requires forward-factor eligibility")
    target = diagnostic.get("target_day")
    path = diagnostic.get("path")
    if not isinstance(target, dict) or not isinstance(path, dict):
        raise ValueError("post-shock diagnostic shape is invalid")
    target_day = date.fromisoformat(str(target.get("day")))
    outcome_day = date.fromisoformat(str(outcome_reference.get("day")))
    if outcome_day != target_day + timedelta(days=1):
        raise ValueError("post-shock outcome must be the adjacent UTC day")
    if outcome_bundle.get("day") != outcome_day.isoformat():
        raise ValueError("post-shock outcome reference and bundle disagree")
    if outcome_bundle.get("received_at") != outcome_reference.get("received_at"):
        raise ValueError("post-shock outcome receipt and bundle disagree")
    _require_outcome_identity(outcome_bundle, outcome_day.isoformat())

    diagnostic_sealed = parse_timestamp(
        str(diagnostic.get("sealed_at")), "shock diagnostic sealed_at"
    ).astimezone(timezone.utc)
    outcome_received = parse_timestamp(
        str(outcome_reference.get("received_at")), "post-shock outcome received_at"
    ).astimezone(timezone.utc)
    episode_sealed = parse_timestamp(sealed_at, "post-shock episode sealed_at").astimezone(
        timezone.utc
    )
    if outcome_received <= diagnostic_sealed:
        raise ValueError("post-shock outcome was not received after diagnostic seal")
    if episode_sealed < outcome_received:
        raise ValueError("post-shock episode predates accepted outcome evidence")

    t0 = datetime.combine(outcome_day, datetime.min.time(), tzinfo=timezone.utc)
    bars = outcome_bundle.get("bars")
    if not isinstance(bars, list) or len(bars) != 24:
        raise ValueError("post-shock outcome requires exactly 24 bars")
    closes: list[Decimal] = []
    for index, bar in enumerate(bars):
        if not isinstance(bar, dict):
            raise ValueError("post-shock outcome bars must be objects")
        expected_start = t0 + timedelta(hours=index)
        expected_end = expected_start + timedelta(hours=1)
        actual_start = parse_timestamp(
            str(bar.get("interval_start")), "post-shock interval_start"
        ).astimezone(timezone.utc)
        actual_end = parse_timestamp(
            str(bar.get("interval_end")), "post-shock interval_end"
        ).astimezone(timezone.utc)
        if actual_start != expected_start or actual_end != expected_end:
            raise ValueError("post-shock outcome hourly grid is not contiguous")
        closes.append(_positive_decimal(bar.get("close"), "outcome close"))

    shock_close = _positive_decimal(path.get("target_close"), "shock close")
    direction = path.get("direction")
    if direction not in {"UP", "DOWN"}:
        raise ValueError("post-shock direction is invalid")
    sign = Decimal("1") if direction == "UP" else Decimal("-1")
    with localcontext() as context:
        context.prec = 50
        responses = {
            "h1": sign * ((closes[0] / shock_close) - Decimal("1")),
            "h6": sign * ((closes[5] / shock_close) - Decimal("1")),
            "h24": sign * ((closes[23] / shock_close) - Decimal("1")),
        }
    primary = responses["h24"]
    label = "CONTINUATION" if primary > 0 else "REVERSAL" if primary < 0 else "TIE"
    target_reference = _public_day_reference(target)
    outcome_public = _public_day_reference(outcome_reference)
    identity = {
        "diagnostic_sha256": diagnostic_sha256,
        "target_artifact_sha256": target_reference["artifact_sha256"],
        "outcome_artifact_sha256": outcome_public["artifact_sha256"],
        "outcome_chain_head": outcome_public["chain_head"],
    }
    episode_id = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
    return {
        "episode_id": episode_id,
        "shock_diagnostic_path": diagnostic_path,
        "shock_diagnostic_sha256": diagnostic_sha256,
        "diagnostic_target_reference": target_reference,
        "outcome_day_reference": outcome_public,
        "t0": _iso_utc(t0),
        "sealed_at": sealed_at,
        "shock_direction": direction,
        "signed_response_h1": _decimal_text(responses["h1"]),
        "signed_response_h6": _decimal_text(responses["h6"]),
        "signed_response_h24": _decimal_text(primary),
        "primary_label": label,
    }


def build_post_shock_snapshot(
    episodes: list[dict[str, Any]],
    *,
    sealed_at: str,
) -> dict[str, Any]:
    if not episodes:
        raise ValueError("post-shock snapshot requires at least one episode")
    ordered = sorted(episodes, key=lambda item: (item["t0"], item["episode_id"]))
    ids = [str(item["episode_id"]) for item in ordered]
    if len(ids) != len(set(ids)):
        raise ValueError("post-shock episode identity is duplicated")
    gates, statistics = _gates_and_statistics(ordered)
    if not gates["all_breadth_pass"]:
        disposition = WAIT
    elif statistics["continuation_conditions_met"]:
        disposition = CONTINUATION
    elif statistics["reversal_conditions_met"]:
        disposition = REVERSAL
    else:
        disposition = NO_FACTOR
    latest = ordered[-1]["outcome_day_reference"]
    snapshot = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "trigger_id": R1_TRIGGER_ID,
        "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
        "snapshot_key": f"{latest['day']}:{latest['chain_head']}",
        "latest_outcome_day": latest["day"],
        "cumulative_chain_binding": latest["chain_head"],
        "sealed_at": sealed_at,
        "disposition": disposition,
        "terminal": disposition in TERMINAL_DISPOSITIONS,
        "episodes": ordered,
        "gate_evidence": gates,
        "statistics": statistics,
        "guardrails": {
            "primary_horizon": "H24",
            "path_diagnostic_horizons": ["H1", "H6"],
            "immediate_pnl_effect": "ZERO",
            "immediate_drawdown_effect": "ZERO",
            "predictive_value": "MISSING_PROOF",
            "strategy_mapping_evaluated": False,
            "hypothesis_created": False,
            "candidate_created": False,
            "oos_opened": False,
            "trading_action_attempted": False,
        },
        "authorization": RESEARCH_AUTHORIZATION,
    }
    _validate_result_snapshot(snapshot)
    return snapshot


def _eligible_episodes(
    store: ResearchStore,
    references: list[dict[str, Any]],
    *,
    current: datetime,
    prior_episode_by_id: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    reference_index = {item["day"]: index for index, item in enumerate(references)}
    namespace = store.root / DIAGNOSTIC_NAMESPACE
    if not namespace.exists():
        return []
    if namespace.is_symlink() or not namespace.is_dir():
        raise ValueError("shock diagnostic namespace is unsafe")
    episodes: list[dict[str, Any]] = []
    for path in sorted(namespace.glob("*.json")):
        if path.is_symlink() or not path.is_file():
            raise ValueError("shock diagnostic artifact is unsafe")
        diagnostic = read_json(path)
        if not isinstance(diagnostic, dict):
            raise ValueError("shock diagnostic artifact must be an object")
        _validate_shock_diagnostic(diagnostic)
        if diagnostic["eligibility"] != "FORWARD_FACTOR_ELIGIBLE":
            continue
        target = diagnostic["target_day"]
        target_day = str(target["day"])
        if path.name != f"{target_day}.json":
            raise ValueError("shock diagnostic path and target day disagree")
        index = reference_index.get(target_day)
        if index is None or index + 1 >= len(references):
            continue
        target_reference = references[index]
        outcome_reference = references[index + 1]
        if _public_day_reference(target) != _public_day_reference(target_reference):
            raise ValueError("shock target reference drifted from accepted evidence")
        if outcome_reference["chain_head"] == target_reference["chain_head"]:
            raise ValueError("post-shock outcome chain did not strictly advance")
        diagnostic_sealed = parse_timestamp(
            str(diagnostic["sealed_at"]), "shock diagnostic sealed_at"
        ).astimezone(timezone.utc)
        target_received = parse_timestamp(
            str(target_reference["received_at"]), "shock target received_at"
        ).astimezone(timezone.utc)
        outcome_received = parse_timestamp(
            str(outcome_reference["received_at"]), "post-shock outcome received_at"
        ).astimezone(timezone.utc)
        if diagnostic_sealed < target_received:
            raise ValueError("shock diagnostic predates accepted target evidence")
        if outcome_received <= diagnostic_sealed:
            raise ValueError("post-shock outcome was not received after diagnostic seal")
        if current < outcome_received:
            raise ValueError("post-shock outcome is not yet accepted")
        outcome_bundle = read_json(outcome_reference["path"])
        diagnostic_hash = sha256_file(path)
        provisional = build_post_shock_episode(
            diagnostic=diagnostic,
            diagnostic_path=store_relative_reference(store.root, path),
            diagnostic_sha256=diagnostic_hash,
            outcome_reference=_public_day_reference(outcome_reference),
            outcome_bundle=outcome_bundle,
            sealed_at=_iso_utc(current),
        )
        prior = prior_episode_by_id.get(provisional["episode_id"])
        if prior is not None:
            expected = dict(provisional)
            expected["sealed_at"] = prior.get("sealed_at")
            if prior != expected:
                raise ValueError("sealed post-shock episode changed or conflicts")
            provisional = prior
        episodes.append(provisional)
    episodes.sort(key=lambda item: (item["t0"], item["episode_id"]))
    ids = [item["episode_id"] for item in episodes]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate post-shock episode identity")
    return episodes


def _eligible_episodes_v2(
    store: ResearchStore,
    references: list[dict[str, Any]],
    *,
    lineage: ActiveForwardTriggerLineage,
    current: datetime,
    prior_episode_by_id: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    leaf = lineage.leaf_trigger
    reference_index = {item["day"]: index for index, item in enumerate(references)}
    namespace = store.root / V2_DIAGNOSTIC_NAMESPACE / str(leaf["fingerprint"])
    if not namespace.exists():
        return []
    if namespace.is_symlink() or not namespace.is_dir():
        raise ValueError("shock diagnostic V2 namespace is unsafe")
    episodes: list[dict[str, Any]] = []
    children = sorted(namespace.iterdir())
    if any(
        child.is_symlink() or not child.is_file() or child.suffix != ".json"
        for child in children
    ):
        raise ValueError("shock diagnostic V2 inventory is unsafe")
    for path in children:
        diagnostic = read_json(path)
        _validate_shock_diagnostic_v2(diagnostic, lineage)
        if diagnostic["eligibility"] != "FORWARD_FACTOR_ELIGIBLE":
            continue
        target = diagnostic["target_day"]
        target_day = str(target["day"])
        if path.name != f"{target_day}.json":
            raise ValueError("shock diagnostic V2 path and target day disagree")
        index = reference_index.get(target_day)
        if index is None or index + 1 >= len(references):
            continue
        target_reference = references[index]
        outcome_reference = references[index + 1]
        if _public_day_reference(target) != _public_day_reference(target_reference):
            raise ValueError("shock V2 target reference drifted from accepted evidence")
        if outcome_reference["chain_head"] == target_reference["chain_head"]:
            raise ValueError("post-shock V2 outcome chain did not strictly advance")
        diagnostic_sealed = parse_timestamp(
            str(diagnostic["sealed_at"]), "shock diagnostic V2 sealed_at"
        ).astimezone(timezone.utc)
        target_received = parse_timestamp(
            str(target_reference["received_at"]), "shock V2 target received_at"
        ).astimezone(timezone.utc)
        outcome_received = parse_timestamp(
            str(outcome_reference["received_at"]), "post-shock V2 outcome received_at"
        ).astimezone(timezone.utc)
        if diagnostic_sealed < target_received:
            raise ValueError("shock diagnostic V2 predates accepted target evidence")
        if outcome_received <= diagnostic_sealed:
            raise ValueError("post-shock V2 outcome was not received after diagnostic seal")
        if current < outcome_received:
            raise ValueError("post-shock V2 outcome is not yet accepted")
        outcome_bundle = read_json(outcome_reference["path"])
        _require_leaf_outcome_identity(
            outcome_bundle, outcome_reference["day"], leaf
        )
        provisional = _build_post_shock_episode_v2(
            diagnostic=diagnostic,
            diagnostic_path=store_relative_reference(store.root, path),
            diagnostic_sha256=sha256_file(path),
            outcome_reference=_public_day_reference(outcome_reference),
            outcome_bundle=outcome_bundle,
            lineage=lineage,
            sealed_at=_iso_utc(current),
        )
        prior = prior_episode_by_id.get(provisional["episode_id"])
        if prior is not None:
            expected = dict(provisional)
            expected["sealed_at"] = prior.get("sealed_at")
            if prior != expected:
                raise ValueError("sealed post-shock V2 episode changed or conflicts")
            provisional = prior
        episodes.append(provisional)
    episodes.sort(key=lambda item: (item["t0"], item["episode_id"]))
    ids = [item["episode_id"] for item in episodes]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate post-shock V2 episode identity")
    return episodes


def _build_post_shock_episode_v2(
    *,
    diagnostic: dict[str, Any],
    diagnostic_path: str,
    diagnostic_sha256: str,
    outcome_reference: dict[str, str],
    outcome_bundle: dict[str, Any],
    lineage: ActiveForwardTriggerLineage,
    sealed_at: str,
) -> dict[str, Any]:
    projected_outcome = dict(outcome_bundle)
    projected_outcome["trigger_id"] = R1_TRIGGER_ID
    projected_outcome["trigger_fingerprint"] = R1_TRIGGER_FINGERPRINT
    episode = build_post_shock_episode(
        diagnostic=diagnostic,
        diagnostic_path=diagnostic_path,
        diagnostic_sha256=diagnostic_sha256,
        outcome_reference=outcome_reference,
        outcome_bundle=projected_outcome,
        sealed_at=sealed_at,
    )
    leaf = lineage.leaf_trigger
    episode["root_trigger_id"] = lineage.root_trigger["trigger_id"]
    episode["root_trigger_fingerprint"] = lineage.root_trigger["fingerprint"]
    episode["leaf_trigger_id"] = leaf["trigger_id"]
    episode["leaf_trigger_fingerprint"] = leaf["fingerprint"]
    identity = {
        "diagnostic_sha256": diagnostic_sha256,
        "target_artifact_sha256": episode["diagnostic_target_reference"][
            "artifact_sha256"
        ],
        "outcome_artifact_sha256": episode["outcome_day_reference"][
            "artifact_sha256"
        ],
        "outcome_chain_head": episode["outcome_day_reference"]["chain_head"],
        "root_trigger_fingerprint": lineage.root_trigger["fingerprint"],
        "leaf_trigger_fingerprint": leaf["fingerprint"],
    }
    episode["episode_id"] = hashlib.sha256(_canonical_bytes(identity)).hexdigest()
    return episode


def _build_post_shock_snapshot_v2(
    episodes: list[dict[str, Any]],
    *,
    lineage: ActiveForwardTriggerLineage,
    sealed_at: str,
) -> dict[str, Any]:
    projected = [
        {
            key: value
            for key, value in episode.items()
            if key
            not in {
                "root_trigger_id",
                "root_trigger_fingerprint",
                "leaf_trigger_id",
                "leaf_trigger_fingerprint",
            }
        }
        for episode in episodes
    ]
    snapshot = build_post_shock_snapshot(projected, sealed_at=sealed_at)
    leaf = lineage.leaf_trigger
    snapshot["document_type"] = V2_DOCUMENT_TYPE
    snapshot["trigger_id"] = leaf["trigger_id"]
    snapshot["trigger_fingerprint"] = leaf["fingerprint"]
    snapshot["root_trigger_id"] = lineage.root_trigger["trigger_id"]
    snapshot["root_trigger_fingerprint"] = lineage.root_trigger["fingerprint"]
    snapshot["leaf_trigger_id"] = leaf["trigger_id"]
    snapshot["leaf_trigger_fingerprint"] = leaf["fingerprint"]
    snapshot["episodes"] = sorted(
        episodes, key=lambda item: (item["t0"], item["episode_id"])
    )
    _validate_result_snapshot_v2(snapshot, lineage)
    return snapshot


def _gates_and_statistics(
    episodes: list[dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    count = len(episodes)
    values = [Decimal(str(item["signed_response_h24"])) for item in episodes]
    up_values = [
        value
        for value, item in zip(values, episodes, strict=True)
        if item["shock_direction"] == "UP"
    ]
    down_values = [
        value
        for value, item in zip(values, episodes, strict=True)
        if item["shock_direction"] == "DOWN"
    ]
    half = count // 2
    first_half_count = half
    second_half_count = count - half
    month_counts = Counter(str(item["t0"])[:7] for item in episodes)
    maximum_month_share = Decimal(max(month_counts.values())) / Decimal(count)
    total_absolute = sum((abs(value) for value in values), Decimal("0"))
    maximum_contribution = (
        Decimal("0")
        if total_absolute == 0
        else max(abs(value) for value in values) / total_absolute
    )
    ids = [str(item["episode_id"]) for item in episodes]
    duplicate_count = count - len(set(ids))
    valid_labels = {"CONTINUATION", "REVERSAL", "TIE"}
    missing_label_count = sum(
        1 for item in episodes if item.get("primary_label") not in valid_labels
    )
    gates = {
        "episode_count": count,
        "up_count": len(up_values),
        "down_count": len(down_values),
        "first_half_count": first_half_count,
        "second_half_count": second_half_count,
        "utc_month_count": len(month_counts),
        "maximum_month_share": _decimal_text(maximum_month_share),
        "duplicate_episode_count": duplicate_count,
        "missing_label_count": missing_label_count,
        "maximum_single_absolute_h24_contribution_share": _decimal_text(
            maximum_contribution
        ),
        "minimum_episode_count_pass": count >= 8,
        "up_breadth_pass": len(up_values) >= 2,
        "down_breadth_pass": len(down_values) >= 2,
        "first_half_breadth_pass": first_half_count >= 2,
        "second_half_breadth_pass": second_half_count >= 2,
        "utc_month_breadth_pass": len(month_counts) >= 3,
        "month_concentration_pass": maximum_month_share <= Decimal("0.5"),
        "unique_episode_pass": duplicate_count == 0,
        "labels_complete_pass": missing_label_count == 0,
        "episode_concentration_pass": maximum_contribution <= Decimal("0.5"),
    }
    gates["all_breadth_pass"] = all(
        bool(gates[key])
        for key in (
            "minimum_episode_count_pass",
            "up_breadth_pass",
            "down_breadth_pass",
            "first_half_breadth_pass",
            "second_half_breadth_pass",
            "utc_month_breadth_pass",
            "month_concentration_pass",
            "unique_episode_pass",
            "labels_complete_pass",
            "episode_concentration_pass",
        )
    )
    overall_median = _median(values)
    up_median = _median(up_values) if up_values else None
    down_median = _median(down_values) if down_values else None
    up_positive = sum(value > 0 for value in up_values)
    down_positive = sum(value > 0 for value in down_values)
    up_negative = sum(value < 0 for value in up_values)
    down_negative = sum(value < 0 for value in down_values)
    continuation = bool(
        gates["all_breadth_pass"]
        and overall_median > 0
        and up_median is not None
        and up_median > 0
        and down_median is not None
        and down_median > 0
        and up_positive >= 2
        and down_positive >= 2
    )
    reversal = bool(
        gates["all_breadth_pass"]
        and overall_median < 0
        and up_median is not None
        and up_median < 0
        and down_median is not None
        and down_median < 0
        and up_negative >= 2
        and down_negative >= 2
    )
    if continuation and reversal:
        raise ValueError("post-shock interpretations are not mutually exclusive")
    statistics = {
        "overall_h24_median": _decimal_text(overall_median),
        "up_h24_median": None if up_median is None else _decimal_text(up_median),
        "down_h24_median": (
            None if down_median is None else _decimal_text(down_median)
        ),
        "up_positive_label_count": up_positive,
        "down_positive_label_count": down_positive,
        "up_negative_label_count": up_negative,
        "down_negative_label_count": down_negative,
        "continuation_conditions_met": continuation,
        "reversal_conditions_met": reversal,
    }
    return gates, statistics


def _validate_schema_bindings() -> None:
    if sha256_file(SCHEMA_PATH) != SCHEMA_SHA256:
        raise ValueError("post-shock result schema hash mismatch")
    if sha256_file(SHOCK_SCHEMA_PATH) != SHOCK_SCHEMA_SHA256:
        raise ValueError("shock diagnostic schema hash mismatch")
    if not isinstance(read_json(SCHEMA_PATH), dict):
        raise ValueError("post-shock result schema must be an object")
    if not isinstance(read_json(SHOCK_SCHEMA_PATH), dict):
        raise ValueError("shock diagnostic schema must be an object")


def _validate_v2_schema_bindings() -> None:
    if sha256_file(V2_SCHEMA_PATH) != V2_SCHEMA_SHA256:
        raise ValueError("post-shock result V2 schema hash mismatch")
    if sha256_file(SHOCK_V2_SCHEMA_PATH) != SHOCK_V2_SCHEMA_SHA256:
        raise ValueError("shock diagnostic V2 schema hash mismatch")
    if not isinstance(read_json(V2_SCHEMA_PATH), dict):
        raise ValueError("post-shock result V2 schema must be an object")
    if not isinstance(read_json(SHOCK_V2_SCHEMA_PATH), dict):
        raise ValueError("shock diagnostic V2 schema must be an object")


_LINEAGE_KEYS = {
    "root_trigger_id",
    "root_trigger_fingerprint",
    "leaf_trigger_id",
    "leaf_trigger_fingerprint",
}


def _validate_result_snapshot_v2(
    value: Any, lineage: ActiveForwardTriggerLineage
) -> None:
    if not isinstance(value, dict):
        raise ValueError("post-shock V2 snapshot must be an object")
    expected_keys = {
        "schema_version", "document_type", "trigger_id", "trigger_fingerprint",
        "snapshot_key", "latest_outcome_day", "cumulative_chain_binding",
        "sealed_at", "disposition", "terminal", "episodes", "gate_evidence",
        "statistics", "guardrails", "authorization",
    } | _LINEAGE_KEYS
    if set(value) != expected_keys:
        raise ValueError("post-shock V2 snapshot fields are invalid")
    _require_v2_lineage_binding(value, lineage, "post-shock V2 snapshot")
    if value.get("document_type") != V2_DOCUMENT_TYPE:
        raise ValueError("post-shock V2 document type mismatch")
    episodes = value.get("episodes")
    if not isinstance(episodes, list) or not episodes:
        raise ValueError("post-shock V2 snapshot requires episodes")
    for episode in episodes:
        _require_v2_lineage_binding(
            episode, lineage, "post-shock V2 episode", include_trigger=False
        )
    projected = {
        key: item for key, item in value.items() if key not in _LINEAGE_KEYS
    }
    projected["document_type"] = DOCUMENT_TYPE
    projected["trigger_id"] = R1_TRIGGER_ID
    projected["trigger_fingerprint"] = R1_TRIGGER_FINGERPRINT
    projected["episodes"] = [
        {key: item for key, item in episode.items() if key not in _LINEAGE_KEYS}
        for episode in episodes
    ]
    _validate_result_snapshot(projected)


def _validate_shock_diagnostic_v2(
    value: Any, lineage: ActiveForwardTriggerLineage
) -> None:
    if not isinstance(value, dict):
        raise ValueError("shock diagnostic V2 must be an object")
    v1_keys = {
        "schema_version", "diagnostic_type", "trigger_id", "trigger_fingerprint",
        "source", "observation_unit", "threshold_return", "prior_day",
        "target_day", "contract_activated_at", "sealed_at", "eligibility",
        "path", "guardrails", "authorization",
    }
    if set(value) != v1_keys | _LINEAGE_KEYS:
        raise ValueError("shock diagnostic V2 fields are invalid")
    _require_v2_lineage_binding(value, lineage, "shock diagnostic V2")
    if value.get("diagnostic_type") != "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V2":
        raise ValueError("shock diagnostic V2 document type mismatch")
    projected = {key: item for key, item in value.items() if key not in _LINEAGE_KEYS}
    projected["diagnostic_type"] = "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V1"
    projected["trigger_id"] = R1_TRIGGER_ID
    projected["trigger_fingerprint"] = R1_TRIGGER_FINGERPRINT
    _validate_shock_diagnostic(projected)


def _require_v2_lineage_binding(
    value: dict[str, Any],
    lineage: ActiveForwardTriggerLineage,
    label: str,
    *,
    include_trigger: bool = True,
) -> None:
    leaf = lineage.leaf_trigger
    expected = {
        "root_trigger_id": lineage.root_trigger["trigger_id"],
        "root_trigger_fingerprint": lineage.root_trigger["fingerprint"],
        "leaf_trigger_id": leaf["trigger_id"],
        "leaf_trigger_fingerprint": leaf["fingerprint"],
    }
    if include_trigger:
        expected.update(
            {
                "trigger_id": leaf["trigger_id"],
                "trigger_fingerprint": leaf["fingerprint"],
            }
        )
    for key, item in expected.items():
        if value.get(key) != item:
            raise ValueError(f"{label} {key} mismatch")


_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_DECIMAL_TEXT = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


def _validate_result_snapshot(value: Any) -> None:
    keys = {
        "schema_version",
        "document_type",
        "trigger_id",
        "trigger_fingerprint",
        "snapshot_key",
        "latest_outcome_day",
        "cumulative_chain_binding",
        "sealed_at",
        "disposition",
        "terminal",
        "episodes",
        "gate_evidence",
        "statistics",
        "guardrails",
        "authorization",
    }
    _require_exact_object(value, keys, "post-shock snapshot")
    constants = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "trigger_id": R1_TRIGGER_ID,
        "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    _require_constants(value, constants, "post-shock snapshot")
    latest_day = _require_day(value["latest_outcome_day"], "latest outcome day")
    chain = _require_hex64(value["cumulative_chain_binding"], "cumulative chain")
    if value["snapshot_key"] != f"{latest_day.isoformat()}:{chain}":
        raise ValueError("post-shock snapshot key binding mismatch")
    _require_timestamp(value["sealed_at"], "post-shock sealed_at")
    disposition = value["disposition"]
    if disposition not in {WAIT, CONTINUATION, REVERSAL, NO_FACTOR}:
        raise ValueError("post-shock snapshot disposition is invalid")
    if type(value["terminal"]) is not bool:
        raise ValueError("post-shock snapshot terminal must be boolean")
    episodes = value["episodes"]
    if not isinstance(episodes, list) or not episodes:
        raise ValueError("post-shock snapshot requires episodes")
    for episode in episodes:
        _validate_episode(episode)
    ordered = sorted(episodes, key=lambda item: (item["t0"], item["episode_id"]))
    if episodes != ordered:
        raise ValueError("post-shock snapshot episodes are not ordered")
    if len({item["episode_id"] for item in episodes}) != len(episodes):
        raise ValueError("post-shock snapshot episode identity is duplicated")
    latest = episodes[-1]["outcome_day_reference"]
    if latest["day"] != latest_day.isoformat() or latest["chain_head"] != chain:
        raise ValueError("post-shock snapshot latest episode binding mismatch")

    _validate_gate_evidence(value["gate_evidence"])
    _validate_statistics(value["statistics"])
    expected_gates, expected_statistics = _gates_and_statistics(episodes)
    if value["gate_evidence"] != expected_gates:
        raise ValueError("post-shock snapshot gate evidence is not reproducible")
    if value["statistics"] != expected_statistics:
        raise ValueError("post-shock snapshot statistics are not reproducible")
    if not expected_gates["all_breadth_pass"]:
        expected_disposition = WAIT
    elif expected_statistics["continuation_conditions_met"]:
        expected_disposition = CONTINUATION
    elif expected_statistics["reversal_conditions_met"]:
        expected_disposition = REVERSAL
    else:
        expected_disposition = NO_FACTOR
    if disposition != expected_disposition:
        raise ValueError("post-shock snapshot disposition is not reproducible")
    if value["terminal"] != (disposition in TERMINAL_DISPOSITIONS):
        raise ValueError("post-shock snapshot terminal binding mismatch")
    _require_constants(
        value["guardrails"],
        {
            "primary_horizon": "H24",
            "path_diagnostic_horizons": ["H1", "H6"],
            "immediate_pnl_effect": "ZERO",
            "immediate_drawdown_effect": "ZERO",
            "predictive_value": "MISSING_PROOF",
            "strategy_mapping_evaluated": False,
            "hypothesis_created": False,
            "candidate_created": False,
            "oos_opened": False,
            "trading_action_attempted": False,
        },
        "post-shock guardrails",
        exact=True,
    )


def _validate_episode(value: Any) -> None:
    keys = {
        "episode_id",
        "shock_diagnostic_path",
        "shock_diagnostic_sha256",
        "diagnostic_target_reference",
        "outcome_day_reference",
        "t0",
        "sealed_at",
        "shock_direction",
        "signed_response_h1",
        "signed_response_h6",
        "signed_response_h24",
        "primary_label",
    }
    _require_exact_object(value, keys, "post-shock episode")
    _require_hex64(value["episode_id"], "post-shock episode_id")
    _require_nonempty_string(value["shock_diagnostic_path"], "shock diagnostic path")
    _require_hex64(
        value["shock_diagnostic_sha256"], "shock diagnostic sha256"
    )
    target = _validate_day_reference(value["diagnostic_target_reference"], "target")
    outcome = _validate_day_reference(value["outcome_day_reference"], "outcome")
    target_day = _require_day(target["day"], "post-shock target day")
    outcome_day = _require_day(outcome["day"], "post-shock outcome day")
    if outcome_day != target_day + timedelta(days=1):
        raise ValueError("post-shock episode days are not adjacent")
    if target["chain_head"] == outcome["chain_head"]:
        raise ValueError("post-shock episode chain did not advance")
    t0 = _require_timestamp(value["t0"], "post-shock t0")
    expected_t0 = datetime.combine(outcome_day, datetime.min.time(), tzinfo=timezone.utc)
    if t0 != expected_t0:
        raise ValueError("post-shock t0 is not outcome-day UTC midnight")
    _require_timestamp(value["sealed_at"], "post-shock episode sealed_at")
    if value["shock_direction"] not in {"UP", "DOWN"}:
        raise ValueError("post-shock episode direction is invalid")
    responses = {
        key: _require_decimal_text(value[key], key)
        for key in ("signed_response_h1", "signed_response_h6", "signed_response_h24")
    }
    expected_label = (
        "CONTINUATION"
        if responses["signed_response_h24"] > 0
        else "REVERSAL"
        if responses["signed_response_h24"] < 0
        else "TIE"
    )
    if value["primary_label"] != expected_label:
        raise ValueError("post-shock episode primary label mismatch")


def _validate_gate_evidence(value: Any) -> None:
    integer_keys = {
        "episode_count",
        "up_count",
        "down_count",
        "first_half_count",
        "second_half_count",
        "utc_month_count",
        "duplicate_episode_count",
        "missing_label_count",
    }
    decimal_keys = {
        "maximum_month_share",
        "maximum_single_absolute_h24_contribution_share",
    }
    boolean_keys = {
        "minimum_episode_count_pass",
        "up_breadth_pass",
        "down_breadth_pass",
        "first_half_breadth_pass",
        "second_half_breadth_pass",
        "utc_month_breadth_pass",
        "month_concentration_pass",
        "unique_episode_pass",
        "labels_complete_pass",
        "episode_concentration_pass",
        "all_breadth_pass",
    }
    _require_exact_object(value, integer_keys | decimal_keys | boolean_keys, "gate evidence")
    for key in integer_keys:
        minimum = 1 if key in {"episode_count", "utc_month_count"} else 0
        _require_integer(value[key], key, minimum=minimum)
    for key in decimal_keys:
        _require_decimal_text(value[key], key)
    for key in boolean_keys:
        if type(value[key]) is not bool:
            raise ValueError(f"{key} must be boolean")


def _validate_statistics(value: Any) -> None:
    decimal_keys = {"overall_h24_median", "up_h24_median", "down_h24_median"}
    integer_keys = {
        "up_positive_label_count",
        "down_positive_label_count",
        "up_negative_label_count",
        "down_negative_label_count",
    }
    boolean_keys = {"continuation_conditions_met", "reversal_conditions_met"}
    _require_exact_object(value, decimal_keys | integer_keys | boolean_keys, "statistics")
    _require_decimal_text(value["overall_h24_median"], "overall_h24_median")
    for key in ("up_h24_median", "down_h24_median"):
        if value[key] is not None:
            _require_decimal_text(value[key], key)
    for key in integer_keys:
        _require_integer(value[key], key, minimum=0)
    for key in boolean_keys:
        if type(value[key]) is not bool:
            raise ValueError(f"{key} must be boolean")


def _validate_shock_diagnostic(value: Any) -> None:
    top_keys = {
        "schema_version",
        "diagnostic_type",
        "trigger_id",
        "trigger_fingerprint",
        "source",
        "observation_unit",
        "threshold_return",
        "prior_day",
        "target_day",
        "contract_activated_at",
        "sealed_at",
        "eligibility",
        "path",
        "guardrails",
        "authorization",
    }
    _require_exact_object(value, top_keys, "shock diagnostic")
    _require_constants(
        value,
        {
            "schema_version": "1",
            "diagnostic_type": "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V1",
            "trigger_id": R1_TRIGGER_ID,
            "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
            "source": R1_SOURCE,
            "observation_unit": "COMPLETE_UTC_DAY",
            "threshold_return": "0.0300",
            "authorization": RESEARCH_AUTHORIZATION,
        },
        "shock diagnostic",
    )
    _validate_day_reference(value["prior_day"], "shock prior day")
    _validate_day_reference(value["target_day"], "shock target day")
    _require_timestamp(value["contract_activated_at"], "shock contract_activated_at")
    _require_timestamp(value["sealed_at"], "shock sealed_at")
    if value["eligibility"] not in {"CONTEXT_ONLY", "FORWARD_FACTOR_ELIGIBLE"}:
        raise ValueError("shock eligibility is invalid")
    _validate_shock_path(value["path"])
    _require_constants(
        value["guardrails"],
        {
            "causal_explanation": "UNKNOWN",
            "prediction_evaluated": False,
            "strategy_mapping_evaluated": False,
            "pnl_evaluated": False,
            "drawdown_evaluated": False,
            "oos_opened": False,
            "news_or_llm_used": False,
        },
        "shock guardrails",
        exact=True,
    )


def _validate_shock_path(value: Any) -> None:
    required_decimal_keys = {
        "prior_close", "target_open", "target_high", "target_low", "target_close",
        "target_volume", "simple_return", "absolute_simple_return", "open_gap_return",
        "high_excursion_return", "low_excursion_return", "largest_absolute_hourly_return",
        "first_12h_return", "last_12h_return",
    }
    nullable_decimal_keys = {"close_in_range", "largest_hour_volume_share"}
    required_timestamp_keys = {
        "earliest_threshold_crossing_interval_end", "peak_interval_start",
        "trough_interval_start", "largest_absolute_hourly_return_interval_start",
    }
    nullable_timestamp_keys = {"largest_hour_volume_interval_start"}
    count_keys = {"positive_hour_count", "negative_hour_count", "flat_hour_count"}
    fixed_keys = {
        "qualifies", "direction", "hourly_incremental_returns", "hourly_cumulative_returns"
    }
    _require_exact_object(
        value,
        required_decimal_keys
        | nullable_decimal_keys
        | required_timestamp_keys
        | nullable_timestamp_keys
        | count_keys
        | fixed_keys,
        "shock path",
    )
    if value["qualifies"] is not True or value["direction"] not in {"UP", "DOWN"}:
        raise ValueError("shock path qualification is invalid")
    for key in required_decimal_keys:
        _require_decimal_text(value[key], key)
    for key in nullable_decimal_keys:
        if value[key] is not None:
            _require_decimal_text(value[key], key)
    for key in required_timestamp_keys:
        _require_timestamp(value[key], key)
    for key in nullable_timestamp_keys:
        if value[key] is not None:
            _require_timestamp(value[key], key)
    for key in count_keys:
        _require_integer(value[key], key, minimum=0, maximum=24)
    for key in ("hourly_incremental_returns", "hourly_cumulative_returns"):
        rows = value[key]
        if not isinstance(rows, list) or len(rows) != 24:
            raise ValueError(f"{key} must contain 24 rows")
        for row in rows:
            _require_exact_object(row, {"interval_start", "interval_end", "return"}, key)
            _require_timestamp(row["interval_start"], f"{key} interval_start")
            _require_timestamp(row["interval_end"], f"{key} interval_end")
            _require_decimal_text(row["return"], f"{key} return")


def _validate_day_reference(value: Any, label: str) -> dict[str, Any]:
    keys = {"day", "artifact_path", "artifact_sha256", "chain_head", "received_at"}
    _require_exact_object(value, keys, label)
    _require_day(value["day"], f"{label} day")
    _require_nonempty_string(value["artifact_path"], f"{label} artifact_path")
    _require_hex64(value["artifact_sha256"], f"{label} artifact_sha256")
    _require_hex64(value["chain_head"], f"{label} chain_head")
    _require_timestamp(value["received_at"], f"{label} received_at")
    return value


def _require_exact_object(value: Any, keys: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} fields are invalid")


def _require_constants(
    value: Any,
    constants: dict[str, Any],
    label: str,
    *,
    exact: bool = False,
) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    if exact and set(value) != set(constants):
        raise ValueError(f"{label} fields are invalid")
    for key, expected in constants.items():
        if value.get(key) != expected or type(value.get(key)) is not type(expected):
            raise ValueError(f"{label} {key} mismatch")


def _require_nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a non-empty string")
    return value


def _require_hex64(value: Any, label: str) -> str:
    text = _require_nonempty_string(value, label)
    if _HEX64.fullmatch(text) is None:
        raise ValueError(f"{label} must be lowercase sha256")
    return text


def _require_decimal_text(value: Any, label: str) -> Decimal:
    text = _require_nonempty_string(value, label)
    if _DECIMAL_TEXT.fullmatch(text) is None:
        raise ValueError(f"{label} is not canonical decimal text")
    result = Decimal(text)
    if not result.is_finite():
        raise ValueError(f"{label} must be finite")
    return result


def _require_day(value: Any, label: str) -> date:
    text = _require_nonempty_string(value, label)
    try:
        result = date.fromisoformat(text)
    except ValueError as error:
        raise ValueError(f"{label} is invalid") from error
    if result.isoformat() != text:
        raise ValueError(f"{label} is not canonical")
    return result


def _require_timestamp(value: Any, label: str) -> datetime:
    text = _require_nonempty_string(value, label)
    return parse_timestamp(text, label).astimezone(timezone.utc)


def _require_integer(
    value: Any,
    label: str,
    *,
    minimum: int,
    maximum: int | None = None,
) -> int:
    if type(value) is not int or value < minimum or (maximum is not None and value > maximum):
        raise ValueError(f"{label} is outside its integer bounds")
    return value


def _validate_trigger_identity(trigger: dict[str, Any], state: dict[str, Any]) -> None:
    expected = {
        "trigger_id": R1_TRIGGER_ID,
        "fingerprint": R1_TRIGGER_FINGERPRINT,
        "source": R1_SOURCE,
        "observation_unit": "COMPLETE_UTC_DAY",
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if trigger.get(key) != value:
            raise ValueError(f"post-shock R1 trigger {key} mismatch")
    if state.get("trigger_id") != R1_TRIGGER_ID:
        raise ValueError("post-shock R1 trigger state identity mismatch")


def _validated_reference(store: ResearchStore, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("post-shock observation reference must be an object")
    required = {"day", "path", "sha256", "chain_head", "received_at"}
    if not required.issubset(value):
        raise ValueError("post-shock observation reference is incomplete")
    path = resolve_store_reference(store.root, value["path"])
    if path.is_symlink() or not path.is_file():
        raise ValueError("post-shock observation artifact is unsafe")
    if sha256_file(path) != value["sha256"]:
        raise ValueError("post-shock observation artifact hash mismatch")
    return {
        "day": str(value["day"]),
        "path": path,
        "artifact_path": store_relative_reference(store.root, path),
        "artifact_sha256": str(value["sha256"]),
        "chain_head": str(value["chain_head"]),
        "received_at": str(value["received_at"]),
    }


def _require_unique_reference_days(references: list[dict[str, Any]]) -> None:
    days = [item["day"] for item in references]
    if len(days) != len(set(days)):
        raise ValueError("post-shock observation day is duplicated")
    if days != sorted(days):
        raise ValueError("post-shock observation days are not ordered")


def _require_outcome_identity(bundle: dict[str, Any], day: str) -> None:
    expected = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": R1_TRIGGER_ID,
        "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
        "source": R1_SOURCE,
        "day": day,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if bundle.get(key) != value:
            raise ValueError(f"post-shock outcome {key} mismatch")


def _require_leaf_outcome_identity(
    bundle: dict[str, Any], day: str, trigger: dict[str, Any]
) -> None:
    expected = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "day": day,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if bundle.get(key) != value:
            raise ValueError(f"post-shock rollover outcome {key} mismatch")


def _public_day_reference(value: dict[str, Any]) -> dict[str, str]:
    path = value.get("artifact_path", value.get("path"))
    sha = value.get("artifact_sha256", value.get("sha256"))
    required = {
        "day": value.get("day"),
        "artifact_path": path,
        "artifact_sha256": sha,
        "chain_head": value.get("chain_head"),
        "received_at": value.get("received_at"),
    }
    if not all(isinstance(item, str) and item for item in required.values()):
        raise ValueError("post-shock day reference is incomplete")
    return required


def _positive_decimal(value: Any, label: str) -> Decimal:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError) as error:
        raise ValueError(f"{label} is invalid") from error
    if not result.is_finite() or result <= 0:
        raise ValueError(f"{label} must be positive")
    return result


def _median(values: list[Decimal]) -> Decimal:
    if not values:
        raise ValueError("post-shock median requires values")
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


def _snapshot_root(store: ResearchStore) -> Path:
    root = store.root.resolve()
    namespace = root / SNAPSHOT_NAMESPACE
    namespace.mkdir(parents=True, exist_ok=True)
    resolved = namespace.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError("post-shock snapshot namespace escapes research state") from error
    if resolved != namespace:
        raise ValueError("post-shock snapshot namespace traverses a link")
    return namespace


def _snapshot_path(store: ResearchStore, day: str, chain_head: str) -> Path:
    date.fromisoformat(day)
    if len(chain_head) != 64 or any(ch not in "0123456789abcdef" for ch in chain_head):
        raise ValueError("post-shock chain binding is invalid")
    return _snapshot_root(store) / f"{day}--{chain_head}.json"


def _snapshot_root_v2(
    store: ResearchStore, lineage: ActiveForwardTriggerLineage
) -> Path:
    fingerprint = str(lineage.leaf_trigger["fingerprint"])
    _require_hex64(fingerprint, "post-shock V2 leaf fingerprint")
    root = store.root.resolve()
    namespace = root / V2_SNAPSHOT_NAMESPACE / fingerprint / "snapshots"
    namespace.mkdir(parents=True, exist_ok=True)
    resolved = namespace.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ValueError("post-shock V2 namespace escapes research state") from error
    if resolved != namespace:
        raise ValueError("post-shock V2 namespace traverses a link")
    return namespace


def _snapshot_path_v2(
    store: ResearchStore,
    lineage: ActiveForwardTriggerLineage,
    day: str,
    chain_head: str,
) -> Path:
    date.fromisoformat(day)
    _require_hex64(chain_head, "post-shock V2 chain binding")
    return _snapshot_root_v2(store, lineage) / f"{day}--{chain_head}.json"


def _load_snapshots(store: ResearchStore) -> list[tuple[Path, dict[str, Any]]]:
    namespace = store.root / SNAPSHOT_NAMESPACE
    if not namespace.exists():
        return []
    if namespace.is_symlink() or not namespace.is_dir():
        raise ValueError("post-shock snapshot namespace is unsafe")
    result: list[tuple[Path, dict[str, Any]]] = []
    children = sorted(namespace.iterdir())
    if any(
        child.is_symlink() or not child.is_file() or child.suffix != ".json"
        for child in children
    ):
        raise ValueError("post-shock snapshot inventory is unsafe")
    for path in children:
        value = _load_json_if_present(path)
        if value is None:
            continue
        _validate_result_snapshot(value)
        expected_name = (
            f"{value['latest_outcome_day']}--"
            f"{value['cumulative_chain_binding']}.json"
        )
        if path.name != expected_name:
            raise ValueError("post-shock snapshot path binding mismatch")
        result.append((path, value))
    ordered = sorted(
        result,
        key=lambda pair: (
            len(pair[1]["episodes"]),
            pair[1]["latest_outcome_day"],
            pair[0].name,
        ),
    )
    for index in range(1, len(ordered)):
        previous = ordered[index - 1][1]
        current = ordered[index][1]
        previous_count = len(previous["episodes"])
        if len(current["episodes"]) <= previous_count:
            raise ValueError("post-shock snapshot sequence did not advance")
        if current["episodes"][:previous_count] != previous["episodes"]:
            raise ValueError("post-shock snapshot history is not append-only")
        if previous["terminal"]:
            raise ValueError("post-shock snapshot exists after terminal disposition")
    return ordered


def _load_snapshots_v2(
    store: ResearchStore, lineage: ActiveForwardTriggerLineage
) -> list[tuple[Path, dict[str, Any]]]:
    namespace = (
        store.root
        / V2_SNAPSHOT_NAMESPACE
        / str(lineage.leaf_trigger["fingerprint"])
        / "snapshots"
    )
    if not namespace.exists():
        return []
    if namespace.is_symlink() or not namespace.is_dir():
        raise ValueError("post-shock V2 snapshot namespace is unsafe")
    children = sorted(namespace.iterdir())
    if any(
        child.is_symlink() or not child.is_file() or child.suffix != ".json"
        for child in children
    ):
        raise ValueError("post-shock V2 snapshot inventory is unsafe")
    result: list[tuple[Path, dict[str, Any]]] = []
    for path in children:
        value = read_json(path)
        _validate_result_snapshot_v2(value, lineage)
        expected_name = (
            f"{value['latest_outcome_day']}--"
            f"{value['cumulative_chain_binding']}.json"
        )
        if path.name != expected_name:
            raise ValueError("post-shock V2 snapshot path binding mismatch")
        result.append((path, value))
    ordered = sorted(
        result,
        key=lambda pair: (
            len(pair[1]["episodes"]),
            pair[1]["latest_outcome_day"],
            pair[0].name,
        ),
    )
    for index in range(1, len(ordered)):
        previous = ordered[index - 1][1]
        current = ordered[index][1]
        previous_count = len(previous["episodes"])
        if len(current["episodes"]) <= previous_count:
            raise ValueError("post-shock V2 snapshot sequence did not advance")
        if current["episodes"][:previous_count] != previous["episodes"]:
            raise ValueError("post-shock V2 snapshot history is not append-only")
        if previous["terminal"]:
            raise ValueError("post-shock V2 snapshot exists after terminal disposition")
    return ordered


def _revalidate_snapshot_sources(
    store: ResearchStore,
    snapshot: dict[str, Any],
    references: list[dict[str, Any]],
) -> None:
    by_day = {item["day"]: item for item in references}
    for episode in snapshot["episodes"]:
        diagnostic_path = resolve_store_reference(
            store.root, episode["shock_diagnostic_path"]
        )
        if diagnostic_path.is_symlink() or not diagnostic_path.is_file():
            raise ValueError("sealed post-shock diagnostic source is unsafe")
        if sha256_file(diagnostic_path) != episode["shock_diagnostic_sha256"]:
            raise ValueError("sealed post-shock diagnostic source changed")
        outcome = episode["outcome_day_reference"]
        current = by_day.get(outcome["day"])
        if current is None or _public_day_reference(current) != outcome:
            raise ValueError("sealed post-shock outcome source changed")
        rebuilt_episode = build_post_shock_episode(
            diagnostic=read_json(diagnostic_path),
            diagnostic_path=episode["shock_diagnostic_path"],
            diagnostic_sha256=episode["shock_diagnostic_sha256"],
            outcome_reference=outcome,
            outcome_bundle=read_json(current["path"]),
            sealed_at=episode["sealed_at"],
        )
        if rebuilt_episode != episode:
            raise ValueError("sealed post-shock episode bytes are not reproducible")
    rebuilt_snapshot = build_post_shock_snapshot(
        snapshot["episodes"], sealed_at=snapshot["sealed_at"]
    )
    if _canonical_bytes(rebuilt_snapshot) != _canonical_bytes(snapshot):
        raise ValueError("sealed post-shock terminal snapshot is not reproducible")


def _revalidate_snapshot_sources_v2(
    store: ResearchStore,
    snapshot: dict[str, Any],
    references: list[dict[str, Any]],
    lineage: ActiveForwardTriggerLineage,
) -> None:
    by_day = {item["day"]: item for item in references}
    rebuilt: list[dict[str, Any]] = []
    for episode in snapshot["episodes"]:
        diagnostic_path = resolve_store_reference(
            store.root, episode["shock_diagnostic_path"]
        )
        if diagnostic_path.is_symlink() or not diagnostic_path.is_file():
            raise ValueError("sealed post-shock V2 diagnostic source is unsafe")
        if sha256_file(diagnostic_path) != episode["shock_diagnostic_sha256"]:
            raise ValueError("sealed post-shock V2 diagnostic source changed")
        diagnostic = read_json(diagnostic_path)
        _validate_shock_diagnostic_v2(diagnostic, lineage)
        outcome = episode["outcome_day_reference"]
        current = by_day.get(outcome["day"])
        if current is None or _public_day_reference(current) != outcome:
            raise ValueError("sealed post-shock V2 outcome source changed")
        outcome_bundle = read_json(current["path"])
        _require_leaf_outcome_identity(
            outcome_bundle, current["day"], lineage.leaf_trigger
        )
        rebuilt_episode = _build_post_shock_episode_v2(
            diagnostic=diagnostic,
            diagnostic_path=episode["shock_diagnostic_path"],
            diagnostic_sha256=episode["shock_diagnostic_sha256"],
            outcome_reference=outcome,
            outcome_bundle=outcome_bundle,
            lineage=lineage,
            sealed_at=episode["sealed_at"],
        )
        if rebuilt_episode != episode:
            raise ValueError("sealed post-shock V2 episode bytes are not reproducible")
        rebuilt.append(rebuilt_episode)
    rebuilt_snapshot = _build_post_shock_snapshot_v2(
        rebuilt, lineage=lineage, sealed_at=snapshot["sealed_at"]
    )
    if _canonical_bytes(rebuilt_snapshot) != _canonical_bytes(snapshot):
        raise ValueError("sealed post-shock V2 terminal snapshot is not reproducible")


def _load_json_if_present(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError("post-shock snapshot is unsafe")
    value = read_json(path)
    if not isinstance(value, dict):
        raise ValueError("post-shock snapshot must be an object")
    return value


def _create_only(path: Path, content: bytes) -> bool:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
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


def _canonical_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _coach_event(
    snapshot: dict[str, Any], *, artifact_path: str, artifact_sha256: str
) -> dict[str, Any]:
    disposition = str(snapshot["disposition"])
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": artifact_path,
        "sha256": artifact_sha256,
        "research_status": "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_TERMINAL",
        "material_conclusion": (
            f"Post-shock diagnostic sealed terminal disposition {disposition} "
            f"from {len(snapshot['episodes'])} eligible forward episodes."
        ),
        "pnl_drawdown_evidence": {
            "immediate_effect": "ZERO",
            "economic_value": "MISSING_PROOF",
        },
        "evidence_diagnostic": {
            "diagnostic_type": DOCUMENT_TYPE,
            "disposition": disposition,
            "episode_count": len(snapshot["episodes"]),
            "primary_horizon": "H24",
        },
        "uncertainty": (
            "Predictive significance, fees, slippage, capacity, matched-capital "
            "PnL, drawdown, candidate readiness, OOS, deployment, and Trading "
            "value remain MISSING_PROOF."
        ),
        "next_action": "MANAGER_REVIEW_ONLY_NO_HYPOTHESIS_CANDIDATE_OR_OOS",
        "concept_to_teach": (
            "A preregistered delayed response can close as continuation, reversal, "
            "or no factor without selecting a horizon after outcomes arrive."
        ),
    }


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
