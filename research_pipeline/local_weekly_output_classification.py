from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import stat
import subprocess
from typing import Any, Iterable

from .local_dispatch import (
    canonical_json_bytes,
    canonical_json_document_bytes,
    load_and_validate_dispatch,
)


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_V1"
SCHEMA_RELATIVE_PATH = "research_pipeline/local-weekly-output-classification.v1.schema.json"
OUTPUT_CLASSES = (
    "MECHANISM_CONCLUSION",
    "NON_COUNTING",
    "SPEC_OR_CAPABILITY_SLICE",
)
SAFETY_KEYS = {
    "canonical_state_changed",
    "oos_opened",
    "paid_api_used",
    "second_timer_created",
    "server_research_mcp_write_attempted",
    "trading_action_attempted",
}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
GIT_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
DISPOSITION_PATTERN = re.compile(r"^[A-Z0-9_]{3,100}$")
FAMILY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
REPOSITORY_PATH_PATTERN = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")
PRE_INTENT_KEYS = {
    "authorization",
    "claim_boundary_sha256",
    "dispatch_id",
    "dispatch_path",
    "dispatch_sha256",
    "disposition_actions",
    "document_type",
    "duplicate_family_key",
    "independence_semantics",
    "intent_id",
    "intent_path",
    "issued_at",
    "manager_thread_id",
    "max_candidate_variants",
    "output_class",
    "output_id",
    "record_stage",
    "schema_version",
    "task_id",
    "task_path",
    "task_sha256",
}
ACCEPTANCE_KEYS = {
    "acceptance_id",
    "accepted_at",
    "accepted_disposition",
    "accepted_result_commit",
    "authorization",
    "classification_outcome",
    "dispatch_id",
    "dispatch_path",
    "dispatch_sha256",
    "document_type",
    "exclusion_reason",
    "intent_path",
    "intent_sha256",
    "manager_thread_id",
    "output_id",
    "record_stage",
    "result_completed_at",
    "result_path",
    "result_sha256",
    "result_source_git_commit",
    "result_status",
    "result_task_id",
    "safety_assertions",
    "schema_version",
    "task_id",
    "task_path",
    "task_sha256",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("classification JSON contains a duplicate key")
        value[key] = item
    return value


def _load_json(raw: bytes, label: str) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} must be strict UTF-8 JSON") from error


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _utc_timestamp(value: str, label: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be a UTC timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError(f"{label} must be timezone-aware UTC")
    return parsed


def _repository_relative_path(value: str, label: str) -> str:
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 1000
        or REPOSITORY_PATH_PATTERN.fullmatch(value) is None
    ):
        raise ValueError(f"{label} must be a repository-relative POSIX path")
    path = Path(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"{label} must be a contained repository path")
    return value


def _contained_regular_file(root: Path, relative: str, label: str) -> Path:
    relative = _repository_relative_path(relative, label)
    candidate = root.joinpath(*relative.split("/"))
    resolved_root = root.resolve(strict=True)
    resolved = candidate.resolve(strict=True)
    try:
        resolved.relative_to(resolved_root)
    except ValueError as error:
        raise ValueError(f"{label} escapes the repository") from error
    metadata = candidate.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"{label} must be a regular non-link file")
    attributes = getattr(metadata, "st_file_attributes", 0)
    reparse = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    if reparse and attributes & reparse:
        raise ValueError(f"{label} must not be a reparse point")
    return candidate


def _git(root: Path, *arguments: str, ok_returncodes: set[int] | None = None) -> bytes:
    completed = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    allowed = {0} if ok_returncodes is None else ok_returncodes
    if completed.returncode not in allowed:
        raise ValueError("required exact Git proof failed")
    return completed.stdout


def _git_object(root: Path, commit: str, relative: str) -> bytes:
    _repository_relative_path(relative, "Git object path")
    if len(commit) != 40 or any(character not in "0123456789abcdef" for character in commit):
        raise ValueError("Git object commit must be exact 40-hex")
    return _git(root, "--no-pager", "show", f"{commit}:{relative}")


def _require_ancestor(root: Path, ancestor: str, descendant: str) -> None:
    completed = subprocess.run(
        ["git", "-C", str(root), "merge-base", "--is-ancestor", ancestor, descendant],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise ValueError("required Git commit ancestry is absent")


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise ValueError(f"{label} does not satisfy the closed companion schema")


def _pattern(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"{label} does not satisfy the closed companion schema")
    return value


def _bounded_string(value: Any, label: str, maximum: int) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= maximum:
        raise ValueError(f"{label} does not satisfy the closed companion schema")
    return value


def validate_weekly_output_classification_record(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("classification record does not satisfy the closed companion schema")
    stage = value.get("record_stage")
    if stage == "PRE_DISPATCH_INTENT":
        _exact_keys(value, PRE_INTENT_KEYS, "pre-dispatch intent")
        for name in ("output_id", "intent_id", "task_id", "dispatch_id"):
            _pattern(value[name], IDENTIFIER_PATTERN, name)
        for name in ("task_sha256", "dispatch_sha256", "claim_boundary_sha256"):
            _pattern(value[name], SHA256_PATTERN, name)
        for name in ("intent_path", "task_path", "dispatch_path"):
            _repository_relative_path(value[name], name)
        _pattern(value["duplicate_family_key"], FAMILY_PATTERN, "duplicate_family_key")
        _bounded_string(value["manager_thread_id"], "manager_thread_id", 128)
        _utc_timestamp(value["issued_at"], "issued_at")
        if value["output_class"] not in OUTPUT_CLASSES:
            raise ValueError("output_class does not satisfy the closed companion schema")
        if value["independence_semantics"] not in {
            "UNIQUE_FAMILY",
            "NESTED_NON_INDEPENDENT",
            "NON_COUNTING_NOT_APPLICABLE",
        }:
            raise ValueError("independence semantics do not satisfy the closed companion schema")
        if value["output_class"] == "NON_COUNTING":
            if value["independence_semantics"] != "NON_COUNTING_NOT_APPLICABLE":
                raise ValueError("NON_COUNTING requires non-counting independence semantics")
        elif value["independence_semantics"] == "NON_COUNTING_NOT_APPLICABLE":
            raise ValueError("countable classes require family independence semantics")
        mappings = value["disposition_actions"]
        if not isinstance(mappings, list) or not 1 <= len(mappings) <= 16:
            raise ValueError("disposition actions do not satisfy the closed companion schema")
        canonical_mappings: set[bytes] = set()
        for mapping in mappings:
            if not isinstance(mapping, dict):
                raise ValueError("disposition action must be a closed object")
            _exact_keys(mapping, {"action", "disposition"}, "disposition action")
            _pattern(mapping["disposition"], DISPOSITION_PATTERN, "disposition")
            if mapping["action"] not in {"COUNT", "EXCLUDE"}:
                raise ValueError("disposition action is unsupported")
            canonical_mappings.add(canonical_json_bytes(mapping))
        if len(canonical_mappings) != len(mappings):
            raise ValueError("disposition actions must be unique")
    elif stage == "MANAGER_ACCEPTANCE":
        _exact_keys(value, ACCEPTANCE_KEYS, "Manager acceptance")
        for name in ("acceptance_id", "output_id", "task_id", "dispatch_id", "result_task_id"):
            _pattern(value[name], IDENTIFIER_PATTERN, name)
        for name in ("task_sha256", "dispatch_sha256", "intent_sha256", "result_sha256"):
            _pattern(value[name], SHA256_PATTERN, name)
        for name in ("accepted_result_commit", "result_source_git_commit"):
            _pattern(value[name], GIT_COMMIT_PATTERN, name)
        for name in ("intent_path", "task_path", "dispatch_path", "result_path"):
            _repository_relative_path(value[name], name)
        _pattern(value["accepted_disposition"], DISPOSITION_PATTERN, "accepted_disposition")
        _bounded_string(value["manager_thread_id"], "manager_thread_id", 128)
        _utc_timestamp(value["accepted_at"], "accepted_at")
        _utc_timestamp(value["result_completed_at"], "result_completed_at")
        if value["result_status"] not in {"COMPLETED", "BLOCKED", "FAILED"}:
            raise ValueError("result status does not satisfy the closed companion schema")
        if value["classification_outcome"] not in {"COUNT", "EXCLUDE"}:
            raise ValueError("classification outcome does not satisfy the closed companion schema")
        reason = value["exclusion_reason"]
        if value["classification_outcome"] == "COUNT":
            if value["result_status"] != "COMPLETED" or reason is not None:
                raise ValueError("COUNT does not satisfy the closed companion schema")
        elif not isinstance(reason, str) or not 1 <= len(reason) <= 1000:
            raise ValueError("EXCLUDE does not satisfy the closed companion schema")
        if value["result_status"] in {"BLOCKED", "FAILED"} and value["classification_outcome"] != "EXCLUDE":
            raise ValueError("non-completed result does not satisfy the closed companion schema")
        if not _all_false_safety(value["safety_assertions"]):
            raise ValueError("safety assertions do not satisfy the closed companion schema")
    else:
        raise ValueError("classification record stage is unsupported")
    if value["schema_version"] != "1" or value["document_type"] != DOCUMENT_TYPE:
        raise ValueError("classification document identity is unsupported")
    if value["authorization"] != AUTHORIZATION:
        raise ValueError("classification authorization is unsupported")
    if stage == "PRE_DISPATCH_INTENT" and value["max_candidate_variants"] != 0:
        raise ValueError("classification requires zero candidate variants")
    return value


def load_and_validate_weekly_output_classification_document(
    raw: bytes,
    label: str = "classification record",
) -> dict[str, Any]:
    value = _load_json(raw, label)
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    if raw != canonical_json_document_bytes(value):
        raise ValueError(f"{label} must use canonical JSON document bytes")
    return validate_weekly_output_classification_record(value)


def _load_schema(root: Path) -> dict[str, Any]:
    path = _contained_regular_file(root, SCHEMA_RELATIVE_PATH, "classification schema")
    schema = _load_json(path.read_bytes(), "classification schema")
    if not isinstance(schema, dict):
        raise ValueError("classification schema must be an object")
    if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise ValueError("classification schema must declare Draft 2020-12")
    if not isinstance(schema.get("oneOf"), list) or len(schema["oneOf"]) != 2:
        raise ValueError("classification schema must contain exactly two alternatives")
    return schema


def _load_canonical_record(
    root: Path,
    relative: str,
    label: str,
) -> tuple[dict[str, Any], bytes]:
    path = _contained_regular_file(root, relative, label)
    raw = path.read_bytes()
    value = load_and_validate_weekly_output_classification_document(raw, label)
    return value, raw


def _exact_current_json(root: Path, relative: str, label: str) -> tuple[dict[str, Any], bytes]:
    path = _contained_regular_file(root, relative, label)
    raw = path.read_bytes()
    value = _load_json(raw, label)
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value, raw


def _all_false_safety(value: Any) -> bool:
    return (
        isinstance(value, dict)
        and set(value) == SAFETY_KEYS
        and all(state is False for state in value.values())
    )


def _validate_row(
    root: Path,
    head: str,
    acceptance_path: str,
    period_start: datetime,
    period_end: datetime,
) -> dict[str, Any]:
    acceptance, acceptance_raw = _load_canonical_record(
        root,
        acceptance_path,
        "Manager acceptance",
    )
    if acceptance["record_stage"] != "MANAGER_ACCEPTANCE":
        raise ValueError("allowlist entry must be MANAGER_ACCEPTANCE")
    if _git_object(root, head, acceptance_path) != acceptance_raw:
        raise ValueError("Manager acceptance bytes do not equal current HEAD object")

    intent, intent_raw = _load_canonical_record(
        root,
        acceptance["intent_path"],
        "pre-dispatch intent",
    )
    if intent["record_stage"] != "PRE_DISPATCH_INTENT":
        raise ValueError("acceptance intent must be PRE_DISPATCH_INTENT")
    if intent["intent_path"] != acceptance["intent_path"]:
        raise ValueError("intent self path does not match acceptance")
    if _sha256(intent_raw) != acceptance["intent_sha256"]:
        raise ValueError("intent hash does not match acceptance")
    source_commit = acceptance["result_source_git_commit"]
    if _git_object(root, source_commit, acceptance["intent_path"]) != intent_raw:
        raise ValueError("intent did not exist as exact source-commit bytes")

    for field in ("output_id", "task_path", "task_id", "task_sha256", "dispatch_path", "dispatch_id", "dispatch_sha256", "manager_thread_id"):
        if intent[field] != acceptance[field]:
            raise ValueError(f"intent and acceptance {field} drift")

    task, task_raw = _exact_current_json(root, acceptance["task_path"], "task")
    dispatch, dispatch_raw = _exact_current_json(root, acceptance["dispatch_path"], "dispatch")
    result, result_raw = _exact_current_json(root, acceptance["result_path"], "result")
    task_sha256 = _sha256(task_raw)
    dispatch_sha256 = _sha256(dispatch_raw)
    result_sha256 = _sha256(result_raw)
    if task_sha256 != acceptance["task_sha256"] or task["task_id"] != acceptance["task_id"]:
        raise ValueError("task identity or bytes drifted")
    if dispatch_sha256 != acceptance["dispatch_sha256"] or dispatch["dispatch_id"] != acceptance["dispatch_id"]:
        raise ValueError("dispatch identity or bytes drifted")
    if _git_object(root, source_commit, acceptance["task_path"]) != task_raw:
        raise ValueError("task did not exist as exact source-commit bytes")
    if _git_object(root, source_commit, acceptance["dispatch_path"]) != dispatch_raw:
        raise ValueError("dispatch did not exist as exact source-commit bytes")
    if result_sha256 != acceptance["result_sha256"] or result["task_id"] != acceptance["result_task_id"]:
        raise ValueError("result identity or bytes drifted")
    closure = load_and_validate_dispatch(
        root.joinpath(*acceptance["dispatch_path"].split("/")),
        root.joinpath(*acceptance["task_path"].split("/")),
        root.joinpath(*acceptance["result_path"].split("/")),
    )
    if (
        closure.get("closure_status") != "VALIDATED_RESULT_BOUND_TO_PERFORMANCE_DISPATCH"
        or closure.get("task_sha256") != task_sha256
        or closure.get("dispatch_sha256") != dispatch_sha256
        or closure.get("result_sha256") != result_sha256
    ):
        raise ValueError("task, dispatch and result semantic closure is invalid")

    if task["manager_thread_id"] != acceptance["manager_thread_id"]:
        raise ValueError("Manager authority drifted")
    if intent["max_candidate_variants"] != 0 or dispatch["decision_contract"]["max_candidate_variants"] != 0:
        raise ValueError("classification requires zero candidate variants")
    claim_sha256 = _sha256(canonical_json_bytes(dispatch["performance_case"]["claim_boundary"]))
    if claim_sha256 != intent["claim_boundary_sha256"]:
        raise ValueError("claim boundary digest drifted")

    if result["status"] != acceptance["result_status"]:
        raise ValueError("accepted result status drifted")
    if result["completed_at"] != acceptance["result_completed_at"]:
        raise ValueError("accepted result completion time drifted")
    if result["source_git_commit"] != source_commit:
        raise ValueError("accepted result source commit drifted")
    if result["source_git_commit"] is None:
        raise ValueError("classification requires a non-null source commit")
    if result["safety_assertions"] != acceptance["safety_assertions"] or not _all_false_safety(result["safety_assertions"]):
        raise ValueError("accepted result safety closure drifted")
    if acceptance["authorization"] != AUTHORIZATION or intent["authorization"] != AUTHORIZATION:
        raise ValueError("research-only authorization drifted")

    accepted_commit = acceptance["accepted_result_commit"]
    _require_ancestor(root, source_commit, accepted_commit)
    _require_ancestor(root, accepted_commit, head)
    if _git_object(root, accepted_commit, acceptance["result_path"]) != result_raw:
        raise ValueError("accepted result bytes do not match accepted-result commit")

    issued_at = _utc_timestamp(intent["issued_at"], "intent issued_at")
    started_at = _utc_timestamp(result["started_at"], "result started_at")
    completed_at = _utc_timestamp(result["completed_at"], "result completed_at")
    accepted_at = _utc_timestamp(acceptance["accepted_at"], "acceptance accepted_at")
    if issued_at > started_at or accepted_at < completed_at:
        raise ValueError("two-stage lifecycle timestamp order is invalid")
    if not period_start <= completed_at < period_end:
        raise ValueError("accepted result falls outside the half-open period")

    mappings: dict[str, str] = {}
    for mapping in intent["disposition_actions"]:
        disposition = mapping["disposition"]
        if disposition in mappings:
            raise ValueError("intent contains repeated disposition labels")
        mappings[disposition] = mapping["action"]
    disposition = acceptance["accepted_disposition"]
    if disposition not in mappings:
        raise ValueError("accepted disposition was not frozen pre-dispatch")
    outcome = acceptance["classification_outcome"]
    if outcome != mappings[disposition]:
        raise ValueError("classification outcome differs from pre-frozen action")
    if result["status"] in {"BLOCKED", "FAILED"} and outcome != "EXCLUDE":
        raise ValueError("BLOCKED and FAILED results must be excluded")
    if result["status"] != "COMPLETED" and outcome == "COUNT":
        raise ValueError("COMPLETED is necessary for COUNT")
    if intent["output_class"] == "NON_COUNTING" and outcome != "EXCLUDE":
        raise ValueError("NON_COUNTING may only be excluded")
    if intent["output_class"] == "NON_COUNTING" and any(action != "EXCLUDE" for action in mappings.values()):
        raise ValueError("NON_COUNTING dispositions must all freeze EXCLUDE")
    reason = acceptance["exclusion_reason"]
    if outcome == "COUNT" and reason is not None:
        raise ValueError("COUNT requires a null exclusion reason")
    if outcome == "EXCLUDE" and (not isinstance(reason, str) or not reason.strip()):
        raise ValueError("EXCLUDE requires a nonempty reason")

    return {
        "acceptance_id": acceptance["acceptance_id"],
        "acceptance_path": acceptance_path,
        "accepted_disposition": disposition,
        "classification_outcome": outcome,
        "completed_at": acceptance["result_completed_at"],
        "dispatch_sha256": acceptance["dispatch_sha256"],
        "duplicate_family_key": intent["duplicate_family_key"],
        "exclusion_reason": reason,
        "independence_semantics": intent["independence_semantics"],
        "intent_id": intent["intent_id"],
        "intent_path": acceptance["intent_path"],
        "output_class": intent["output_class"],
        "output_id": acceptance["output_id"],
        "result_path": acceptance["result_path"],
        "result_sha256": acceptance["result_sha256"],
        "result_status": acceptance["result_status"],
        "task_sha256": acceptance["task_sha256"],
    }


def validate_weekly_output_classification(
    repository_root: Path | str,
    acceptance_paths: Iterable[str],
    period_start: str,
    period_end: str,
) -> dict[str, Any]:
    root = Path(repository_root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("repository_root must be a directory")
    git_root = Path(_git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve(strict=True)
    if git_root != root:
        raise ValueError("repository_root must be the exact Git top level")
    if _git(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("classification requires a clean current repository")
    head = _git(root, "rev-parse", "HEAD").decode("ascii").strip()
    start = _utc_timestamp(period_start, "period_start")
    end = _utc_timestamp(period_end, "period_end")
    if start >= end:
        raise ValueError("period must be a nonempty half-open UTC interval")
    paths = [_repository_relative_path(path, "acceptance path") for path in acceptance_paths]
    if not paths or len(paths) != len(set(paths)):
        raise ValueError("acceptance allowlist must be explicit, nonempty and unique")
    _load_schema(root)
    rows = [
        _validate_row(root, head, path, start, end)
        for path in paths
    ]

    def _unique(values: Iterable[str], label: str) -> None:
        items = list(values)
        if len(items) != len(set(items)):
            raise ValueError(f"duplicate {label} is forbidden")

    _unique((row["output_id"] for row in rows), "output_id")
    _unique((row["acceptance_id"] for row in rows), "acceptance_id")
    _unique((row["intent_id"] for row in rows), "intent_id")
    _unique((row["intent_path"] for row in rows), "intent acceptance")
    _unique((row["result_path"] for row in rows), "result-to-intent binding")
    _unique((row["result_sha256"] for row in rows), "result-to-intent hash binding")

    counted = [row for row in rows if row["classification_outcome"] == "COUNT"]
    families: dict[str, list[dict[str, Any]]] = {}
    for row in counted:
        families.setdefault(row["duplicate_family_key"], []).append(row)
    for family_rows in families.values():
        if len({row["output_class"] for row in family_rows}) != 1:
            raise ValueError("one counted family cannot cross output classes")
        if len(family_rows) > 1 and any(
            row["independence_semantics"] != "NESTED_NON_INDEPENDENT"
            for row in family_rows
        ):
            raise ValueError("shared counted families must all be nested non-independent")

    rows.sort(key=lambda row: row["output_id"])
    exclusions = sorted(
        (
            {
                "exclusion_reason": row["exclusion_reason"],
                "output_id": row["output_id"],
            }
            for row in rows
            if row["classification_outcome"] == "EXCLUDE"
        ),
        key=lambda item: item["output_id"],
    )
    raw_counts = {
        output_class: sum(row["output_class"] == output_class for row in counted)
        for output_class in OUTPUT_CLASSES
    }
    family_counts = {
        output_class: len(
            {
                row["duplicate_family_key"]
                for row in counted
                if row["output_class"] == output_class
            }
        )
        for output_class in OUTPUT_CLASSES
    }
    return {
        "document_type": "LOCAL_WEEKLY_OUTPUT_CLASSIFICATION_SUPPLEMENT_V1",
        "exclusions": exclusions,
        "period": {
            "end_exclusive": period_end,
            "start_inclusive": period_start,
        },
        "raw_count_totals": raw_counts,
        "rows": rows,
        "status": "VALID",
        "unique_family_totals": family_counts,
    }


def canonical_weekly_output_classification_bytes(value: dict[str, Any]) -> bytes:
    return canonical_json_document_bytes(value)
