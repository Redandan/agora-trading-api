from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import stat
import subprocess
from typing import Any, Iterable

from .local_dispatch import canonical_json_bytes, load_and_validate_dispatch
from .local_candidate_enabling_capability import (
    DUPLICATE_FAMILY_KEY as CANDIDATE_ENABLING_FAMILY_KEY,
    load_and_validate_candidate_enabling_capability,
    validate_candidate_enabling_capability_context,
)
from .local_weekly_output_classification import (
    load_and_validate_weekly_output_classification_document,
    validate_weekly_output_classification,
)
from .local_strategy_path import (
    load_and_validate_local_strategy_path,
    validate_local_strategy_path_context,
)


PREFLIGHT_DOCUMENT_TYPE = "LOCAL_MANAGER_PREFLIGHT_RECEIPT_V1"
STRATEGY_PREFLIGHT_DOCUMENT_TYPE = "LOCAL_MANAGER_STRATEGY_PREFLIGHT_RECEIPT_V1"
ALLOCATION_PREFLIGHT_DOCUMENT_TYPE = "LOCAL_MANAGER_ALLOCATION_PREFLIGHT_RECEIPT_V1"
KPI_DOCUMENT_TYPE = "LOCAL_RESEARCH_THROUGHPUT_KPI_V1"
GOAL_AUDIT_DOCUMENT_TYPE = "LOCAL_RESEARCH_GOAL_AUDIT_V1"
WEEKLY_FLOOR_MECHANISMS = 3
WEEKLY_FLOOR_SLICES = 1
WEEKLY_STRETCH_MECHANISMS = 4
WEEKLY_STRETCH_SLICES = 2
MAX_KPI_PERIOD = timedelta(days=7)
CANDIDATE_DELIVERY_MIN_ACCEPTED_OUTPUTS = 5
CANDIDATE_DELIVERY_MIN_DIRECT_MECHANISMS = 3
CANDIDATE_DELIVERY_MIN_DIRECT_FAMILIES = 3
CANDIDATE_DELIVERY_REQUIRED_CONSECUTIVE_DAILY_AUDITS = 2
RESEARCH_FACTORY_MIN_WEEKLY_ECONOMIC_CLOSURES = 2
RESEARCH_FACTORY_MAX_DECISION_LATENCY_HOURS = 48
RESEARCH_FACTORY_MAX_GOVERNANCE_RATIO_BPS = 2_000


def _git(root: Path, *arguments: str) -> bytes:
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise ValueError(f"required local Git proof failed: {detail or 'unknown error'}")
    return completed.stdout


def _git_text(root: Path, *arguments: str) -> str:
    return _git(root, *arguments).decode("utf-8").strip()


def _exact_repository_root(repository_root: Path | str) -> Path:
    root = Path(repository_root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("repository_root must be a directory")
    git_root = Path(_git_text(root, "rev-parse", "--show-toplevel")).resolve(strict=True)
    if git_root != root:
        raise ValueError("repository_root must be the exact Git top level")
    return root


def _reject_link_or_reparse(path: Path, label: str) -> None:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode):
        raise ValueError(f"{label} must not be a symbolic link")
    reparse = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    attributes = getattr(metadata, "st_file_attributes", 0)
    if reparse and attributes & reparse:
        raise ValueError(f"{label} must not be a reparse point")


def _contained_regular_file(
    root: Path,
    path: Path,
    label: str,
) -> tuple[Path, str]:
    candidate = path if path.is_absolute() else root / path
    resolved = candidate.resolve(strict=True)
    try:
        relative = resolved.relative_to(root)
    except ValueError as error:
        raise ValueError(f"{label} escapes the repository") from error
    cursor = root
    for part in relative.parts:
        cursor = cursor / part
        _reject_link_or_reparse(cursor, label)
    if not resolved.is_file():
        raise ValueError(f"{label} must be a regular file")
    return resolved, relative.as_posix()


def _sealed_regular_file(root: Path, locator: str, label: str) -> Path:
    supplied = Path(locator)
    if supplied.is_absolute():
        candidate = supplied
    else:
        candidate, _ = _contained_regular_file(root, supplied, label)
        return candidate
    resolved = candidate.resolve(strict=True)
    _reject_link_or_reparse(candidate, label)
    if not resolved.is_file():
        raise ValueError(f"{label} must be a regular file")
    return resolved


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _require_head_bytes(root: Path, head: str, relative: str, current: bytes, label: str) -> None:
    committed = _git(root, "--no-pager", "show", f"{head}:{relative}")
    if committed != current:
        raise ValueError(f"{label} bytes do not match current HEAD")


def build_local_manager_preflight(
    repository_root: Path | str,
    dispatch_path: Path | str,
    task_path: Path | str,
    classification_intent_path: Path | str,
    *,
    allow_non_counting_integrity_repair: bool = False,
    allow_candidate_enabling_capability: bool = False,
) -> dict[str, Any]:
    if allow_non_counting_integrity_repair and allow_candidate_enabling_capability:
        raise ValueError("non-counting exceptions cannot be combined")
    root = _exact_repository_root(repository_root)
    if _git(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("Manager preflight requires a clean worktree")
    branch = _git_text(root, "symbolic-ref", "--quiet", "--short", "HEAD")
    head = _git_text(root, "rev-parse", "HEAD")
    origin_ref = f"refs/remotes/origin/{branch}"
    origin_commit = _git_text(root, "rev-parse", "--verify", origin_ref)
    if head != origin_commit:
        raise ValueError("HEAD must equal the local origin branch before dispatch")

    dispatch_file, dispatch_relative = _contained_regular_file(
        root,
        Path(dispatch_path),
        "dispatch path",
    )
    task_file, task_relative = _contained_regular_file(
        root,
        Path(task_path),
        "task path",
    )
    intent_file, intent_relative = _contained_regular_file(
        root,
        Path(classification_intent_path),
        "classification intent path",
    )
    validation = load_and_validate_dispatch(dispatch_file, task_file)
    dispatch_raw = dispatch_file.read_bytes()
    task_raw = task_file.read_bytes()
    intent_raw = intent_file.read_bytes()
    _require_head_bytes(root, head, dispatch_relative, dispatch_raw, "dispatch")
    _require_head_bytes(root, head, task_relative, task_raw, "task")
    _require_head_bytes(
        root,
        head,
        intent_relative,
        intent_raw,
        "classification intent",
    )

    task = json.loads(task_raw.decode("utf-8"))
    dispatch = json.loads(dispatch_raw.decode("utf-8"))
    intent = load_and_validate_weekly_output_classification_document(
        intent_raw,
        "classification intent",
    )
    if intent["record_stage"] != "PRE_DISPATCH_INTENT":
        raise ValueError("Manager preflight requires a pre-dispatch classification intent")
    expected_intent_bindings = {
        "authorization": dispatch["authorization"],
        "dispatch_id": validation["dispatch_id"],
        "dispatch_path": dispatch_relative,
        "dispatch_sha256": validation["dispatch_sha256"],
        "intent_path": intent_relative,
        "manager_thread_id": dispatch["manager_thread_id"],
        "max_candidate_variants": dispatch["decision_contract"][
            "max_candidate_variants"
        ],
        "task_id": validation["task_id"],
        "task_path": task_relative,
        "task_sha256": validation["task_sha256"],
    }
    for field, expected in expected_intent_bindings.items():
        if intent.get(field) != expected:
            raise ValueError(f"classification intent {field} does not bind the dispatch")
    claim_boundary_sha256 = hashlib.sha256(
        canonical_json_bytes(dispatch["performance_case"]["claim_boundary"])
    ).hexdigest()
    if intent["claim_boundary_sha256"] != claim_boundary_sha256:
        raise ValueError("classification intent claim boundary does not bind the dispatch")
    countable_disposition_count = sum(
        mapping["action"] == "COUNT" for mapping in intent["disposition_actions"]
    )
    output_class = intent["output_class"]
    countable = (
        output_class in {"MECHANISM_CONCLUSION", "SPEC_OR_CAPABILITY_SLICE"}
        and countable_disposition_count > 0
    )
    if allow_non_counting_integrity_repair or allow_candidate_enabling_capability:
        if output_class != "NON_COUNTING":
            raise ValueError(
                "non-counting exceptions are only valid for NON_COUNTING work"
            )
        value_gate_status = (
            "NON_COUNTING_CANDIDATE_ENABLING_CAPABILITY_EXCEPTION"
            if allow_candidate_enabling_capability
            else "NON_COUNTING_ACTIVE_INTEGRITY_EXCEPTION"
        )
    elif not countable:
        raise ValueError(
            "Manager value gate rejects work with no countable disposition; "
            "use the explicit integrity-repair exception only for an active evidence risk"
        )
    else:
        value_gate_status = "COUNTABLE_OUTPUT_REQUIRED"

    locators = [item["locator"] for item in task["inputs"]]
    if len(locators) != len(set(locators)):
        raise ValueError("task input locators must be unique for Manager preflight")

    input_proofs: list[dict[str, Any]] = []
    repository_input_count = 0
    sealed_artifact_count = 0
    file_input_count = 0
    task_bound_file_input_count = 0
    for index, item in enumerate(task["inputs"]):
        kind = item["kind"]
        locator = item["locator"]
        expected = item["sha256"]
        observed: str | None = None
        verification = "MESSAGE_BOUND_BY_TASK_BYTES"
        if kind == "REPOSITORY_PATH":
            if Path(locator).is_absolute() or "\\" in locator:
                raise ValueError(f"inputs[{index}] repository locator must be relative POSIX")
            file_path, relative = _contained_regular_file(
                root,
                Path(locator),
                f"inputs[{index}]",
            )
            raw = file_path.read_bytes()
            observed = hashlib.sha256(raw).hexdigest()
            _require_head_bytes(root, head, relative, raw, f"inputs[{index}]")
            repository_input_count += 1
            file_input_count += 1
            verification = "CURRENT_HEAD_REGULAR_NON_LINK_FILE"
        elif kind == "SEALED_ARTIFACT":
            file_path = _sealed_regular_file(root, locator, f"inputs[{index}]")
            observed = _sha256(file_path)
            sealed_artifact_count += 1
            file_input_count += 1
            verification = "REGULAR_NON_LINK_SEALED_FILE"
        elif expected is not None:
            raise ValueError(f"inputs[{index}] non-file hash semantics are unsupported")
        if expected is not None:
            if observed != expected:
                raise ValueError(f"inputs[{index}] SHA-256 does not match the task")
            task_bound_file_input_count += 1
        input_proofs.append(
            {
                "kind": kind,
                "locator": locator,
                "observed_sha256": observed,
                "task_sha256": expected,
                "verification": verification,
            }
        )

    decision = dispatch["decision_contract"]
    return {
        "authorization": dispatch["authorization"],
        "branch": branch,
        "classification_intent_id": intent["intent_id"],
        "classification_intent_path": intent_relative,
        "classification_intent_sha256": hashlib.sha256(intent_raw).hexdigest(),
        "dispatch_id": validation["dispatch_id"],
        "dispatch_path": dispatch_relative,
        "dispatch_sha256": validation["dispatch_sha256"],
        "document_type": PREFLIGHT_DOCUMENT_TYPE,
        "file_input_count": file_input_count,
        "head_commit": head,
        "input_count": len(input_proofs),
        "input_proofs": input_proofs,
        "manager_thread_id": dispatch["manager_thread_id"],
        "origin_commit": origin_commit,
        "repository_input_count": repository_input_count,
        "research_value_gate": {
            "candidate_enabling_capability_exception": allow_candidate_enabling_capability,
            "countable_disposition_count": countable_disposition_count,
            "non_counting_integrity_exception": allow_non_counting_integrity_repair,
            "output_class": output_class,
            "status": value_gate_status,
        },
        "schema_version": "1",
        "sealed_artifact_count": sealed_artifact_count,
        "state_authority": dispatch["state_authority"],
        "status": "VALID",
        "stop_condition_count": decision["stop_condition_count"],
        "stop_conditions_sha256": decision["stop_conditions_sha256"],
        "task_bound_file_input_count": task_bound_file_input_count,
        "task_id": validation["task_id"],
        "task_path": task_relative,
        "task_sha256": validation["task_sha256"],
        "timer_authority": dispatch["timer_authority"],
        "worktree_clean": True,
    }


def build_local_strategy_manager_preflight(
    repository_root: Path | str,
    dispatch_path: Path | str,
    task_path: Path | str,
    classification_intent_path: Path | str,
    strategy_path_path: Path | str,
) -> dict[str, Any]:
    base = build_local_manager_preflight(
        repository_root,
        dispatch_path,
        task_path,
        classification_intent_path,
    )
    root = _exact_repository_root(repository_root)
    strategy_file, strategy_relative = _contained_regular_file(
        root,
        Path(strategy_path_path),
        "strategy path",
    )
    strategy, strategy_raw = load_and_validate_local_strategy_path(strategy_file)
    _require_head_bytes(
        root,
        base["head_commit"],
        strategy_relative,
        strategy_raw,
        "strategy path",
    )

    dispatch_file, _ = _contained_regular_file(root, Path(dispatch_path), "dispatch path")
    task_file, _ = _contained_regular_file(root, Path(task_path), "task path")
    intent_file, _ = _contained_regular_file(
        root,
        Path(classification_intent_path),
        "classification intent path",
    )
    dispatch = json.loads(dispatch_file.read_text(encoding="utf-8"))
    task = json.loads(task_file.read_text(encoding="utf-8"))
    intent_raw = intent_file.read_bytes()
    intent = load_and_validate_weekly_output_classification_document(
        intent_raw,
        "classification intent",
    )
    validate_local_strategy_path_context(
        strategy,
        task=task,
        task_sha256=base["task_sha256"],
        dispatch=dispatch,
        dispatch_sha256=base["dispatch_sha256"],
        intent=intent,
        intent_sha256=base["classification_intent_sha256"],
    )

    candidate = strategy["candidate_path"]
    decision = strategy["decision_time"]
    result = dict(base)
    result["document_type"] = STRATEGY_PREFLIGHT_DOCUMENT_TYPE
    result["strategy_path_gate"] = {
        "admission_id": strategy["admission_id"],
        "availability_status": decision["availability_status"],
        "decision_clock": decision["decision_clock"],
        "evidence_bindings": strategy["evidence_bindings"],
        "existing_adapter_or_direct_runner": candidate[
            "existing_adapter_or_direct_runner"
        ],
        "matched_comparator_id": candidate["matched_comparator_id"],
        "maximum_additional_research_steps": candidate[
            "maximum_additional_research_steps"
        ],
        "parent_strategy_id": candidate["parent_strategy_id"],
        "positive_next_step": candidate["positive_next_step"],
        "runner_id": candidate["runner_id"],
        "status": "DIRECT_CANDIDATE_PATH_REQUIRED",
        "strategy_path": strategy_relative,
        "strategy_path_sha256": hashlib.sha256(strategy_raw).hexdigest(),
    }
    return result


def _utc_timestamp(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as error:
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError(f"{label} must be an ISO-8601 UTC timestamp")
    return parsed


def _candidate_delivery_stats(rows: list[dict[str, Any]]) -> dict[str, int]:
    direct_rows = [
        row
        for row in rows
        if row["classification_outcome"] == "COUNT"
        and row["output_class"] == "MECHANISM_CONCLUSION"
        and row.get("strategy_path_admitted") is True
    ]
    direct_families = {
        row.get("duplicate_family_key")
        for row in direct_rows
        if isinstance(row.get("duplicate_family_key"), str)
    }
    return {
        "accepted_output_count": len(rows),
        "direct_mechanism_count": len(direct_rows),
        "direct_mechanism_family_count": len(direct_families),
    }


def _candidate_delivery_goal_met(stats: dict[str, int]) -> bool:
    return (
        stats["accepted_output_count"] >= CANDIDATE_DELIVERY_MIN_ACCEPTED_OUTPUTS
        and stats["direct_mechanism_count"]
        >= CANDIDATE_DELIVERY_MIN_DIRECT_MECHANISMS
        and stats["direct_mechanism_family_count"]
        >= CANDIDATE_DELIVERY_MIN_DIRECT_FAMILIES
        and stats["direct_mechanism_count"] * 2
        > stats["accepted_output_count"]
    )


def _candidate_delivery_recovery_forecast(
    rows: list[dict[str, Any]],
    period: dict[str, str],
) -> dict[str, Any]:
    current = _candidate_delivery_stats(rows)
    if _candidate_delivery_goal_met(current):
        return {
            "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
            "status": "ALREADY_MET",
        }
    if not rows:
        return {
            "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
            "reason": "No accepted outputs are present in the current window.",
            "status": "NOT_PROJECTED_BEFORE_CURRENT_ROWS_EXPIRE",
        }

    try:
        start = _utc_timestamp(period["start_inclusive"], "period start")
        end = _utc_timestamp(period["end_exclusive"], "period end")
        completed = [
            _utc_timestamp(row["completed_at"], f"rows[{index}] completed_at")
            for index, row in enumerate(rows)
        ]
    except (KeyError, TypeError, ValueError) as error:
        return {
            "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
            "reason": f"Recovery forecast requires every validated row completion time: {error}",
            "status": "MISSING_PROOF",
        }

    window = end - start
    if window <= timedelta(0):
        return {
            "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
            "reason": "Recovery forecast requires a nonempty rolling window.",
            "status": "MISSING_PROOF",
        }
    expirations = [timestamp + window for timestamp in completed]
    for boundary in sorted(set(expirations)):
        remaining_indexes = [
            index for index, expiration in enumerate(expirations) if expiration > boundary
        ]
        remaining = _candidate_delivery_stats(
            [rows[index] for index in remaining_indexes]
        )
        if _candidate_delivery_goal_met(remaining):
            return {
                "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
                "direct_mechanism_count_after_boundary": remaining[
                    "direct_mechanism_count"
                ],
                "direct_mechanism_family_count_after_boundary": remaining[
                    "direct_mechanism_family_count"
                ],
                "direct_mechanism_ratio_basis_points_after_boundary": (
                    remaining["direct_mechanism_count"]
                    * 10_000
                    // remaining["accepted_output_count"]
                ),
                "remaining_output_count_after_boundary": remaining[
                    "accepted_output_count"
                ],
                "status": "PROJECTED",
                "strictly_after": boundary.isoformat().replace("+00:00", "Z"),
                "window_seconds": int(window.total_seconds()),
            }
    return {
        "assumption": "NO_NEW_ACCEPTED_OUTPUTS",
        "reason": "The target is not reached before every current row leaves the rolling window.",
        "status": "NOT_PROJECTED_BEFORE_CURRENT_ROWS_EXPIRE",
        "window_seconds": int(window.total_seconds()),
    }


def _candidate_delivery_next_dispatch_policy(
    denominator: int,
    direct_count: int,
) -> dict[str, Any]:
    projected_denominator = denominator + 1
    direct_projection = direct_count + 1
    support_headroom = max(0, 2 * direct_count - denominator - 1)
    support_preserves_target = direct_count * 2 > projected_denominator
    return {
        "active_evidence_integrity_exception": (
            "ALLOW_ONLY_WHEN_SEPARATELY_PROVEN_AND_CLASSIFIED_NON_COUNTING"
        ),
        "candidate_enabling_capability_exception": {
            "minimum_unlocked_direct_families": 3,
            "rolling_budget_days": 7,
            "status": "ALLOW_ONCE_PER_WINDOW_WHEN_SCHEMA_PROVEN_AND_NON_COUNTING",
        },
        "direct_strategy_path": {
            "projected_direct_mechanism_count": direct_projection,
            "projected_ratio_basis_points": (
                direct_projection * 10_000 // projected_denominator
            ),
            "status": "ALLOW_IMPROVES_OR_PRESERVES_RATIO",
        },
        "policy": "DIRECT_FIRST_STRICT_MAJORITY_ALLOCATION",
        "support_outputs_available_before_target_loss": support_headroom,
        "support_work": {
            "projected_direct_mechanism_count": direct_count,
            "projected_ratio_basis_points": (
                direct_count * 10_000 // projected_denominator
            ),
            "status": (
                "ALLOW_WITHIN_STRICT_MAJORITY_BUDGET"
                if support_preserves_target
                else "DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY"
            ),
        },
    }


def _research_factory_next_dispatch_policy(
    denominator: int,
    direct_count: int,
) -> dict[str, Any]:
    support_count = denominator - direct_count
    projected_denominator = denominator + 1
    projected_support_count = support_count + 1
    support_preserves_target = (
        projected_support_count * 10_000
        <= RESEARCH_FACTORY_MAX_GOVERNANCE_RATIO_BPS * projected_denominator
    )
    support_headroom = max(0, (denominator - 5 * support_count) // 4)
    return {
        "active_evidence_integrity_exception": (
            "ALLOW_ONLY_WHEN_SEPARATELY_PROVEN_AND_CLASSIFIED_NON_COUNTING"
        ),
        "candidate_enabling_capability_exception": {
            "minimum_unlocked_direct_families": 3,
            "rolling_budget_days": 7,
            "status": "ALLOW_ONCE_PER_WINDOW_WHEN_SCHEMA_PROVEN_AND_NON_COUNTING",
        },
        "direct_strategy_path": {
            "projected_direct_economic_closure_count": direct_count + 1,
            "projected_governance_ratio_basis_points": (
                support_count * 10_000 // projected_denominator
            ),
            "status": "ALLOW_ECONOMIC_CLOSURE",
        },
        "policy": "RESEARCH_FACTORY_V2_ECONOMIC_CLOSURE_ALLOCATION",
        "support_outputs_available_before_target_loss": support_headroom,
        "support_work": {
            "projected_direct_economic_closure_count": direct_count,
            "projected_governance_ratio_basis_points": (
                projected_support_count * 10_000 // projected_denominator
            ),
            "status": (
                "ALLOW_WITHIN_20_PERCENT_GOVERNANCE_BUDGET"
                if support_preserves_target
                else "DEFER_UNLESS_APPROVED_NON_COUNTING_EXCEPTION"
            ),
        },
    }


def _research_factory_v2_assessment(
    rows: list[dict[str, Any]],
    direct_mechanisms: list[dict[str, Any]],
) -> dict[str, Any]:
    denominator = len(rows)
    support_count = denominator - len(direct_mechanisms)
    governance_ratio_bps = (
        0 if denominator == 0 else support_count * 10_000 // denominator
    )
    latency_rows: list[dict[str, Any]] = []
    missing_latency_ids: list[str] = []
    for row in direct_mechanisms:
        issued = row.get("intent_issued_at")
        completed = row.get("completed_at")
        if not isinstance(issued, str) or not isinstance(completed, str):
            missing_latency_ids.append(row["output_id"])
            continue
        latency_hours = (
            _utc_timestamp(completed, "completed_at")
            - _utc_timestamp(issued, "intent_issued_at")
        ).total_seconds() / 3600
        if latency_hours < 0:
            raise ValueError("economic closure completed before its frozen intent")
        latency_rows.append(
            {"latency_hours": latency_hours, "output_id": row["output_id"]}
        )
    closures_met = (
        len(direct_mechanisms) >= RESEARCH_FACTORY_MIN_WEEKLY_ECONOMIC_CLOSURES
    )
    governance_met = (
        denominator > 0
        and governance_ratio_bps <= RESEARCH_FACTORY_MAX_GOVERNANCE_RATIO_BPS
    )
    latency_met = (
        bool(direct_mechanisms)
        and not missing_latency_ids
        and all(
            row["latency_hours"] <= RESEARCH_FACTORY_MAX_DECISION_LATENCY_HOURS
            for row in latency_rows
        )
    )
    return {
        "decision_latency": {
            "measure": "PRE_DISPATCH_INTENT_TO_ECONOMIC_DECISION_PROXY",
            "missing_output_ids": sorted(missing_latency_ids),
            "observations": sorted(latency_rows, key=lambda row: row["output_id"]),
            "status": (
                "MET"
                if latency_met
                else ("MISSING_PROOF" if missing_latency_ids or not direct_mechanisms else "ABOVE_TARGET")
            ),
            "target_hours_at_most": RESEARCH_FACTORY_MAX_DECISION_LATENCY_HOURS,
        },
        "direct_economic_closures": {
            "count": len(direct_mechanisms),
            "output_ids": sorted(row["output_id"] for row in direct_mechanisms),
            "required_per_rolling_week": RESEARCH_FACTORY_MIN_WEEKLY_ECONOMIC_CLOSURES,
            "status": "MET" if closures_met else "BELOW_TARGET",
        },
        "governance_and_tooling": {
            "count": support_count,
            "denominator": denominator,
            "ratio_basis_points": governance_ratio_bps,
            "status": "MET" if governance_met else "ABOVE_TARGET",
            "target_basis_points_at_most": RESEARCH_FACTORY_MAX_GOVERNANCE_RATIO_BPS,
        },
        "next_dispatch_policy": _research_factory_next_dispatch_policy(
            denominator,
            len(direct_mechanisms),
        ),
        "status": "MET" if closures_met and governance_met and latency_met else "NOT_YET_MET",
    }


def summarize_local_research_kpi(classification: dict[str, Any]) -> dict[str, Any]:
    rows = classification["rows"]
    candidate_enabling_capability_outputs = [
        row
        for row in rows
        if row.get("duplicate_family_key") == CANDIDATE_ENABLING_FAMILY_KEY
    ]
    counted = [row for row in rows if row["classification_outcome"] == "COUNT"]
    excluded = [row for row in rows if row["classification_outcome"] == "EXCLUDE"]
    labelled_mechanisms = [
        row
        for row in counted
        if row["output_class"] == "MECHANISM_CONCLUSION"
    ]
    direct_mechanisms = [
        row for row in labelled_mechanisms if row.get("strategy_path_admitted") is True
    ]
    mechanism_count = classification["unique_family_totals"]["MECHANISM_CONCLUSION"]
    slice_count = classification["unique_family_totals"]["SPEC_OR_CAPABILITY_SLICE"]
    overhead = [
        row
        for row in rows
        if row["output_class"] == "NON_COUNTING"
        or row["classification_outcome"] == "EXCLUDE"
    ]
    denominator = len(rows)
    ratio_bps = 0 if denominator == 0 else len(overhead) * 10_000 // denominator
    direct_ratio_bps = (
        0 if denominator == 0 else len(direct_mechanisms) * 10_000 // denominator
    )
    delivery_stats = _candidate_delivery_stats(rows)
    delivery_goal_met = _candidate_delivery_goal_met(delivery_stats)

    def target(required_mechanisms: int, required_slices: int) -> dict[str, Any]:
        return {
            "actual_mechanism_families": mechanism_count,
            "actual_spec_or_capability_families": slice_count,
            "required_mechanism_families": required_mechanisms,
            "required_spec_or_capability_families": required_slices,
            "status": (
                "MET"
                if mechanism_count >= required_mechanisms and slice_count >= required_slices
                else "NOT_YET_MET"
            ),
        }

    return {
        "classification_status": classification["status"],
        "candidate_enabling_capability_accepted_output_ids": sorted(
            row["output_id"] for row in candidate_enabling_capability_outputs
        ),
        "counted_output_count": len(counted),
        "counted_output_ids": sorted(row["output_id"] for row in counted),
        "direct_mechanism_output_count": len(direct_mechanisms),
        "direct_mechanism_output_ids": sorted(
            row["output_id"] for row in direct_mechanisms
        ),
        "document_type": KPI_DOCUMENT_TYPE,
        "excluded_output_count": len(excluded),
        "excluded_output_ids": sorted(row["output_id"] for row in excluded),
        "goal_assessment": {
            "research_factory_v2": _research_factory_v2_assessment(
                rows,
                direct_mechanisms,
            ),
            "candidate_delivery_efficiency": {
                "accepted_output_count": denominator,
                "direct_mechanism_count": len(direct_mechanisms),
                "direct_mechanism_family_count": delivery_stats[
                    "direct_mechanism_family_count"
                ],
                "direct_mechanism_ratio_basis_points": direct_ratio_bps,
                "labelled_mechanism_proxy_count": len(labelled_mechanisms),
                "natural_recovery_forecast": _candidate_delivery_recovery_forecast(
                    rows,
                    classification["period"],
                ),
                "next_dispatch_policy": _candidate_delivery_next_dispatch_policy(
                    denominator,
                    len(direct_mechanisms),
                ),
                "proof_standard": "COUNTED_MECHANISM_WITH_VERIFIED_STRATEGY_PATH_ADMISSION",
                "required_accepted_output_count": CANDIDATE_DELIVERY_MIN_ACCEPTED_OUTPUTS,
                "required_direct_mechanism_count": CANDIDATE_DELIVERY_MIN_DIRECT_MECHANISMS,
                "required_direct_mechanism_family_count": CANDIDATE_DELIVERY_MIN_DIRECT_FAMILIES,
                "status": "MET" if delivery_goal_met else "BELOW_TARGET",
                "support_or_excluded_count": denominator - len(direct_mechanisms),
                "target": "AT_LEAST_5_ACCEPTED_AT_LEAST_3_DIRECT_AT_LEAST_3_DIRECT_FAMILIES_AND_STRICTLY_MORE_THAN_50_PERCENT",
                "two_daily_audit_stability": {
                    "current_audit_met": delivery_goal_met,
                    "required_consecutive_daily_audits": CANDIDATE_DELIVERY_REQUIRED_CONSECUTIVE_DAILY_AUDITS,
                    "status": "REQUIRES_LOCAL_RESEARCH_GOAL_AUDIT",
                },
            },
            "operational_overhead": {
                "denominator": denominator,
                "non_counting_or_excluded_count": len(overhead),
                "ratio_basis_points": ratio_bps,
                "status": "MET" if denominator > 0 and len(overhead) * 2 < denominator else "ABOVE_TARGET",
                "target": "STRICTLY_LESS_THAN_50_PERCENT",
            },
            "rolling_four_week_forward_terminal": {
                "reason": "The V1 classification contract has no typed forward-terminal field; do not infer it from names.",
                "status": "MISSING_PROOF",
                "target": "AT_LEAST_ONE",
            },
            "weekly_floor": target(WEEKLY_FLOOR_MECHANISMS, WEEKLY_FLOOR_SLICES),
            "weekly_stretch": target(WEEKLY_STRETCH_MECHANISMS, WEEKLY_STRETCH_SLICES),
        },
        "period": classification["period"],
        "schema_version": "1",
        "scientific_claim": "NO_ALPHA_OR_PERFORMANCE_CLAIM",
        "status": "VALID",
        "unique_family_totals": classification["unique_family_totals"],
        "validated_acceptance_count": denominator,
    }


def build_local_research_kpi(
    repository_root: Path | str,
    acceptance_paths: Iterable[str],
    period_start: str,
    period_end: str,
) -> dict[str, Any]:
    start = _utc_timestamp(period_start, "period_start")
    end = _utc_timestamp(period_end, "period_end")
    if start >= end:
        raise ValueError("KPI period must be a nonempty half-open interval")
    if end - start > MAX_KPI_PERIOD:
        raise ValueError("KPI period must not exceed seven days")
    classification = validate_weekly_output_classification(
        repository_root,
        acceptance_paths,
        period_start,
        period_end,
    )
    return summarize_local_research_kpi(classification)


def build_local_research_goal_audit(
    repository_root: Path | str,
    previous_acceptance_paths: Iterable[str],
    previous_period_start: str,
    previous_period_end: str,
    current_acceptance_paths: Iterable[str],
    current_period_start: str,
    current_period_end: str,
) -> dict[str, Any]:
    previous_start = _utc_timestamp(previous_period_start, "previous_period_start")
    previous_end = _utc_timestamp(previous_period_end, "previous_period_end")
    current_start = _utc_timestamp(current_period_start, "current_period_start")
    current_end = _utc_timestamp(current_period_end, "current_period_end")
    if current_start - previous_start != timedelta(days=1):
        raise ValueError("goal audit rolling-window starts must be exactly one day apart")
    if current_end - previous_end != timedelta(days=1):
        raise ValueError("goal audit rolling-window ends must be exactly one day apart")
    if previous_end - previous_start != current_end - current_start:
        raise ValueError("goal audit rolling windows must have identical duration")

    previous = build_local_research_kpi(
        repository_root,
        previous_acceptance_paths,
        previous_period_start,
        previous_period_end,
    )
    current = build_local_research_kpi(
        repository_root,
        current_acceptance_paths,
        current_period_start,
        current_period_end,
    )
    previous_efficiency = previous["goal_assessment"][
        "candidate_delivery_efficiency"
    ]
    current_efficiency = current["goal_assessment"][
        "candidate_delivery_efficiency"
    ]
    previous_met = previous_efficiency["status"] == "MET"
    current_met = current_efficiency["status"] == "MET"
    consecutive_met = 2 if previous_met and current_met else (1 if current_met else 0)
    workflow_met = consecutive_met == CANDIDATE_DELIVERY_REQUIRED_CONSECUTIVE_DAILY_AUDITS
    return {
        "audits": [
            {
                "candidate_delivery_efficiency": previous_efficiency,
                "period": previous["period"],
            },
            {
                "candidate_delivery_efficiency": current_efficiency,
                "period": current["period"],
            },
        ],
        "document_type": GOAL_AUDIT_DOCUMENT_TYPE,
        "schema_version": "1",
        "scientific_claim": "NO_ALPHA_OR_PERFORMANCE_CLAIM",
        "status": "VALID",
        "strategy_success": {
            "reason": "Throughput classification does not prove fee-adjusted PnL, drawdown, holding-path, breadth, Validation, or OOS gates.",
            "status": "MISSING_PROOF",
        },
        "workflow_efficiency_goal": {
            "consecutive_daily_audits_met": consecutive_met,
            "required_consecutive_daily_audits": CANDIDATE_DELIVERY_REQUIRED_CONSECUTIVE_DAILY_AUDITS,
            "status": "MET" if workflow_met else "NOT_YET_MET",
        },
    }


def build_local_research_allocation_preflight(
    repository_root: Path | str,
    dispatch_path: Path | str,
    task_path: Path | str,
    classification_intent_path: Path | str,
    acceptance_paths: Iterable[str],
    period_start: str,
    period_end: str,
    *,
    strategy_path_path: Path | str | None = None,
    allow_non_counting_integrity_repair: bool = False,
    candidate_enabling_capability_path: Path | str | None = None,
) -> dict[str, Any]:
    exception_count = sum(
        (
            strategy_path_path is not None,
            allow_non_counting_integrity_repair,
            candidate_enabling_capability_path is not None,
        )
    )
    if exception_count > 1:
        raise ValueError("direct work and non-counting exceptions cannot be combined")
    if strategy_path_path is None:
        manager = build_local_manager_preflight(
            repository_root,
            dispatch_path,
            task_path,
            classification_intent_path,
            allow_non_counting_integrity_repair=(
                allow_non_counting_integrity_repair
            ),
            allow_candidate_enabling_capability=(
                candidate_enabling_capability_path is not None
            ),
        )
    else:
        manager = build_local_strategy_manager_preflight(
            repository_root,
            dispatch_path,
            task_path,
            classification_intent_path,
            strategy_path_path,
        )

    kpi = build_local_research_kpi(
        repository_root,
        acceptance_paths,
        period_start,
        period_end,
    )
    efficiency = kpi["goal_assessment"]["candidate_delivery_efficiency"]
    policy = _research_factory_next_dispatch_policy(
        efficiency["accepted_output_count"],
        efficiency["direct_mechanism_count"],
    )
    direct = strategy_path_path is not None
    capability_exception = None
    if candidate_enabling_capability_path is not None:
        root = _exact_repository_root(repository_root)
        capability_file, capability_relative = _contained_regular_file(
            root,
            Path(candidate_enabling_capability_path),
            "candidate-enabling capability path",
        )
        capability, capability_raw = load_and_validate_candidate_enabling_capability(
            capability_file
        )
        head = manager["head_commit"]
        _require_head_bytes(
            root,
            head,
            capability_relative,
            capability_raw,
            "candidate-enabling capability",
        )
        task_file, _ = _contained_regular_file(root, Path(task_path), "task path")
        dispatch_file, _ = _contained_regular_file(root, Path(dispatch_path), "dispatch path")
        intent_file, _ = _contained_regular_file(
            root,
            Path(classification_intent_path),
            "classification intent path",
        )
        task_raw = task_file.read_bytes()
        dispatch_raw = dispatch_file.read_bytes()
        intent_raw = intent_file.read_bytes()
        task = json.loads(task_raw.decode("utf-8"))
        dispatch = json.loads(dispatch_raw.decode("utf-8"))
        intent = load_and_validate_weekly_output_classification_document(
            intent_raw,
            "classification intent",
        )
        validate_candidate_enabling_capability_context(
            capability,
            task=task,
            task_sha256=hashlib.sha256(task_raw).hexdigest(),
            dispatch=dispatch,
            dispatch_sha256=hashlib.sha256(dispatch_raw).hexdigest(),
            intent=intent,
            intent_sha256=hashlib.sha256(intent_raw).hexdigest(),
        )
        prior_uses = kpi.get(
            "candidate_enabling_capability_accepted_output_ids",
            [],
        )
        if prior_uses:
            raise ValueError(
                "candidate-enabling capability rolling seven-day budget is already used"
            )
        capability_exception = {
            "path": capability_relative,
            "sha256": hashlib.sha256(capability_raw).hexdigest(),
            "unlocked_direct_family_count": len(
                capability["capability"]["unlocked_direct_families"]
            ),
            "rolling_window_prior_accepted_use_count": 0,
            "status": "VALID",
        }
    if direct:
        gate_status = "DIRECT_STRATEGY_PATH_ALLOWED"
        projection = policy["direct_strategy_path"]
    elif allow_non_counting_integrity_repair:
        gate_status = "ACTIVE_EVIDENCE_INTEGRITY_EXCEPTION_ALLOWED"
        projection = policy["support_work"]
    elif candidate_enabling_capability_path is not None:
        gate_status = "CANDIDATE_ENABLING_CAPABILITY_EXCEPTION_ALLOWED"
        projection = policy["support_work"]
    else:
        projection = policy["support_work"]
        if projection["status"] != "ALLOW_WITHIN_20_PERCENT_GOVERNANCE_BUDGET":
            raise ValueError(
                "allocation gate defers support work until the 20 percent "
                "governance budget has headroom"
            )
        gate_status = "SUPPORT_WITHIN_20_PERCENT_GOVERNANCE_BUDGET_ALLOWED"

    return {
        "allocation_gate": {
            "current_direct_mechanism_count": efficiency[
                "direct_mechanism_count"
            ],
            "current_direct_mechanism_ratio_basis_points": efficiency[
                "direct_mechanism_ratio_basis_points"
            ],
            "current_output_count": efficiency["accepted_output_count"],
            "policy": policy["policy"],
            "projection": projection,
            "status": gate_status,
            "support_outputs_available_before_target_loss": policy[
                "support_outputs_available_before_target_loss"
            ],
        },
        "authorization": manager["authorization"],
        "candidate_enabling_capability": capability_exception,
        "document_type": ALLOCATION_PREFLIGHT_DOCUMENT_TYPE,
        "manager_preflight": manager,
        "period": kpi["period"],
        "proposed_output_class": manager["research_value_gate"]["output_class"],
        "schema_version": "1",
        "status": "VALID",
    }
