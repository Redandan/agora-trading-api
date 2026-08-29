from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .evidence import MISSED_DISCOVERY_ROLLOVER_REASON
from .models import RESEARCH_AUTHORIZATION
from .storage import ResearchStore, read_json, sha256_file
from .waiting import build_evidence_trigger


ROOT_TRIGGER_ID = "prospective-mechanism-neutral-evidence-refresh-2026q4-r1"
ROOT_TRIGGER_FINGERPRINT = (
    "0e5a4675e937613202f0a4a243360a405e9ace1823c4b999edb5d479849d2589"
)


@dataclass(frozen=True)
class ActiveForwardTriggerLineage:
    root_trigger: dict[str, Any]
    root_state: dict[str, Any]
    leaf_trigger: dict[str, Any]
    leaf_state: dict[str, Any]
    trigger_ids: tuple[str, ...]
    trigger_identities: tuple[tuple[str, str, str], ...] = ()

    @property
    def rolled_over(self) -> bool:
        return self.leaf_trigger["trigger_id"] != self.root_trigger["trigger_id"]


def resolve_active_forward_trigger_lineage(
    store: ResearchStore,
) -> ActiveForwardTriggerLineage | None:
    """Resolve the sole active discovery leaf rooted at the frozen R1 trigger."""
    entries = _safe_trigger_entries(store)
    by_id: dict[str, tuple[dict[str, Any], dict[str, Any]]] = {}
    for trigger, state in entries:
        trigger_id = str(trigger.get("trigger_id", ""))
        if not trigger_id or trigger_id in by_id:
            raise ValueError("forward trigger lineage contains duplicate trigger identity")
        by_id[trigger_id] = (trigger, state)

    root_pair = by_id.get(ROOT_TRIGGER_ID)
    if root_pair is None:
        return None
    root, root_state = root_pair
    _verify_registered_trigger(store, root, root_state)
    if root.get("fingerprint") != ROOT_TRIGGER_FINGERPRINT:
        raise ValueError("forward trigger lineage root fingerprint mismatch")
    _verify_discovery_contract(root, root)

    children: dict[str, list[str]] = {}
    for trigger_id, (trigger, state) in by_id.items():
        predecessor_id = state.get("rollover_predecessor_trigger_id")
        if predecessor_id is None:
            continue
        if not isinstance(predecessor_id, str) or not predecessor_id:
            raise ValueError("forward trigger lineage predecessor identity is invalid")
        children.setdefault(predecessor_id, []).append(trigger_id)

    lineage: list[str] = []
    identities: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    current_trigger, current_state = root, root_state
    while True:
        current_id = str(current_trigger["trigger_id"])
        if current_id in seen:
            raise ValueError("forward trigger lineage contains a cycle")
        seen.add(current_id)
        lineage.append(current_id)
        _verify_registered_trigger(store, current_trigger, current_state)
        _verify_discovery_contract(current_trigger, root)
        fingerprint = current_trigger.get("fingerprint")
        created_at = current_trigger.get("created_at")
        if not isinstance(fingerprint, str) or not isinstance(created_at, str):
            raise ValueError("forward trigger lineage identity is incomplete")
        identities.append((current_id, fingerprint, created_at))

        linked_children = children.get(current_id, [])
        if len(linked_children) > 1:
            raise ValueError("forward trigger lineage contains an ambiguous fork")
        status = current_state.get("status")
        if status == "CLOSED":
            successor_id = current_state.get("rollover_successor_trigger_id")
            successor_fingerprint = current_state.get(
                "rollover_successor_fingerprint"
            )
            if (
                current_state.get("rollover_reason")
                != MISSED_DISCOVERY_ROLLOVER_REASON
                or not isinstance(successor_id, str)
                or not isinstance(successor_fingerprint, str)
            ):
                raise ValueError("forward trigger lineage closure is not a missed-window rollover")
            successor_pair = by_id.get(successor_id)
            if successor_pair is None:
                raise ValueError("forward trigger lineage successor is missing")
            if linked_children != [successor_id]:
                raise ValueError("forward trigger lineage successor link is ambiguous")
            successor, successor_state = successor_pair
            _verify_registered_trigger(store, successor, successor_state)
            if successor.get("fingerprint") != successor_fingerprint:
                raise ValueError("forward trigger lineage successor fingerprint mismatch")
            if current_state.get("rollover_closed_at") != successor.get("created_at"):
                raise ValueError("forward trigger lineage closure clock mismatch")
            _verify_successor_back_reference(successor, successor_state, current_trigger)
            current_trigger, current_state = successor, successor_state
            continue

        if status not in {"WAITING", "REVIEW_DUE"}:
            raise ValueError("forward trigger lineage active leaf status is invalid")
        if linked_children:
            raise ValueError("forward trigger lineage has an extra active leaf")
        if current_state.get("rollover_successor_trigger_id") is not None:
            raise ValueError("forward trigger lineage active leaf has a successor")
        break

    for predecessor_id, linked_children in children.items():
        if predecessor_id in seen and any(child not in seen for child in linked_children):
            raise ValueError("forward trigger lineage contains an extra active leaf")

    return ActiveForwardTriggerLineage(
        root_trigger=root,
        root_state=root_state,
        leaf_trigger=current_trigger,
        leaf_state=_normalized_leaf_state_for_readers(current_state),
        trigger_ids=tuple(lineage),
        trigger_identities=tuple(identities),
    )


def _normalized_leaf_state_for_readers(
    state: dict[str, Any],
) -> dict[str, Any]:
    if "evidence_observations" in state:
        return state
    normalized = dict(state)
    normalized["evidence_observations"] = []
    return normalized


def _safe_trigger_entries(
    store: ResearchStore,
) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    if not store.evidence_triggers.exists():
        return []
    if store.evidence_triggers.is_symlink() or not store.evidence_triggers.is_dir():
        raise ValueError("forward trigger lineage root is unsafe")
    entries: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for directory in sorted(store.evidence_triggers.iterdir()):
        if directory.is_symlink():
            raise ValueError("forward trigger lineage directory is unsafe")
        if not directory.is_dir():
            continue
        trigger_path = directory / "trigger.json"
        state_path = directory / "state.json"
        if trigger_path.exists() != state_path.exists():
            raise ValueError("forward trigger lineage registration is partial")
        if not trigger_path.exists():
            continue
        if (
            trigger_path.is_symlink()
            or state_path.is_symlink()
            or not trigger_path.is_file()
            or not state_path.is_file()
        ):
            raise ValueError("forward trigger lineage files are unsafe")
        entries.append((read_json(trigger_path), read_json(state_path)))
    return entries


def _verify_registered_trigger(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
) -> None:
    trigger_id = str(trigger.get("trigger_id", ""))
    if state.get("trigger_id") != trigger_id:
        raise ValueError("forward trigger lineage trigger/state identity mismatch")
    directory = store.evidence_trigger_dir(trigger_id)
    if directory.name != trigger_id or directory.parent != store.evidence_triggers:
        raise ValueError("forward trigger lineage trigger path is invalid")
    trigger_path = directory / "trigger.json"
    state_path = directory / "state.json"
    if state.get("trigger_sha256") != sha256_file(trigger_path):
        raise ValueError("forward trigger lineage trigger hash mismatch")
    if read_json(state_path) != state or read_json(trigger_path) != trigger:
        raise ValueError("forward trigger lineage bytes changed during resolution")
    raw = {key: value for key, value in trigger.items() if key != "fingerprint"}
    canonical = build_evidence_trigger(raw)
    if _is_exact_legacy_discovery_root(trigger):
        canonical.pop("purpose")
        canonical.pop("candidate_binding")
    if canonical != trigger:
        raise ValueError("forward trigger lineage trigger is not canonical")


def _verify_discovery_contract(
    trigger: dict[str, Any], root: dict[str, Any]
) -> None:
    legacy_root = _is_exact_legacy_discovery_root(trigger)
    purpose = "HYPOTHESIS_DISCOVERY" if legacy_root else trigger.get("purpose")
    candidate_binding = None if legacy_root else trigger.get("candidate_binding")
    if purpose != "HYPOTHESIS_DISCOVERY":
        raise ValueError("forward trigger lineage purpose is not discovery")
    if candidate_binding is not None:
        raise ValueError("forward trigger lineage contains candidate binding")
    if trigger.get("authorization") != RESEARCH_AUTHORIZATION:
        raise ValueError("forward trigger lineage authorization drift")
    frozen_fields = {
        "source",
        "minimum_observations",
        "observation_unit",
        "required_integrity_checks",
        "prohibited_inferences",
        "excluded_branches",
        "authorization",
    }
    for field in frozen_fields:
        if trigger.get(field) != root.get(field):
            raise ValueError(f"forward trigger lineage {field} drift")


def _is_exact_legacy_discovery_root(trigger: dict[str, Any]) -> bool:
    return (
        trigger.get("trigger_id") == ROOT_TRIGGER_ID
        and trigger.get("fingerprint") == ROOT_TRIGGER_FINGERPRINT
        and "purpose" not in trigger
        and "candidate_binding" not in trigger
    )


def _verify_successor_back_reference(
    successor: dict[str, Any],
    successor_state: dict[str, Any],
    predecessor: dict[str, Any],
) -> None:
    expected = {
        "rollover_predecessor_trigger_id": predecessor.get("trigger_id"),
        "rollover_predecessor_fingerprint": predecessor.get("fingerprint"),
        "rollover_reason": MISSED_DISCOVERY_ROLLOVER_REASON,
    }
    for field, value in expected.items():
        if successor_state.get(field) != value:
            raise ValueError(f"forward trigger lineage successor {field} mismatch")
