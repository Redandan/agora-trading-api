from __future__ import annotations

from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import (
    canonical_json_document_bytes,
    load_and_validate_dispatch,
)


DOCUMENT_TYPE = "LOCAL_PARTITIONED_EVENT_SEMANTIC_CLOSURE_V1"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
YEAR_BASIS = "TARGET_YEAR"
METRIC = "SIGNED_H24_MEDIAN"
DECISION_RULE = "ALL_REQUIRED_TARGET_YEAR_MEDIANS_NEGATIVE"
DISPOSITION = "HISTORICAL_REVERSAL_PRIOR_STABLE"

PARTITION_KEYS = {
    "DESIGN": ("2019", "2020", "2021", "2022"),
    "VALIDATION": ("2023", "2024"),
}
PARTITION_TOTALS = {
    "DESIGN": (420, 224, 196, 180, 240, 0),
    "VALIDATION": (143, 83, 60, 62, 81, 0),
}

ROOT_KEYS = {
    "schema_version",
    "document_type",
    "authorization",
    "bindings",
    "year_basis",
    "metric",
    "decision_rule",
    "partitions",
    "disposition",
    "boundaries",
}
BINDING_KEYS = {
    "task_id",
    "task_sha256",
    "dispatch_id",
    "dispatch_sha256",
    "result_task_id",
    "result_sha256",
    "result_source_git_commit",
}
PARTITION_OBJECT_KEYS = {"name", "required_keys", "rows", "totals"}
ROW_KEYS = {
    "check_name",
    "key",
    "event_count",
    "up_count",
    "down_count",
    "continuation_count",
    "reversal_count",
    "tie_count",
    "metric_value",
}
TOTAL_KEYS = {
    "event_count",
    "up_count",
    "down_count",
    "continuation_count",
    "reversal_count",
    "tie_count",
}
BOUNDARY_KEYS = {
    "immediate_fee_adjusted_pnl_effect",
    "immediate_drawdown_effect",
    "predictive_value",
    "canonical_state_write_authorized",
    "oos_authorized",
    "trading_action_authorized",
}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
GIT_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DECIMAL_PATTERN = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _load_json(raw: bytes, label: str) -> Any:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} must be strict UTF-8 JSON") from error


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _integer(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{label} must be a nonnegative integer")
    return value


def _sha256(value: Any, label: str) -> str:
    text = _string(value, label)
    if SHA256_PATTERN.fullmatch(text) is None:
        raise ValueError(f"{label} must be a lowercase SHA-256")
    return text


def _validate_boundaries(value: Any) -> None:
    boundaries = _object(value, "boundaries")
    _exact_keys(boundaries, BOUNDARY_KEYS, "boundaries")
    if boundaries["immediate_fee_adjusted_pnl_effect"] != "ZERO":
        raise ValueError("immediate fee-adjusted PnL effect must remain ZERO")
    if boundaries["immediate_drawdown_effect"] != "ZERO":
        raise ValueError("immediate drawdown effect must remain ZERO")
    if boundaries["predictive_value"] != "MISSING_PROOF":
        raise ValueError("predictive value must remain MISSING_PROOF")
    for key in (
        "canonical_state_write_authorized",
        "oos_authorized",
        "trading_action_authorized",
    ):
        if boundaries[key] is not False:
            raise ValueError(f"boundaries.{key} must be false")


def _evidence_prefix(row: dict[str, Any]) -> str:
    return (
        f"{row['key']} target-year: {row['event_count']} events, "
        f"UP/DOWN {row['up_count']}/{row['down_count']}; "
        f"median signed h24 {row['metric_value']}; "
        "continuation/reversal/tie "
        f"{row['continuation_count']}/{row['reversal_count']}/{row['tie_count']};"
    )


def _validate_row(
    value: Any,
    *,
    partition_name: str,
    expected_key: str,
    result_checks: dict[str, dict[str, Any]],
) -> tuple[int, int, int, int, int, int]:
    row = _object(value, f"{partition_name} row {expected_key}")
    _exact_keys(row, ROW_KEYS, f"{partition_name} row {expected_key}")
    if row["key"] != expected_key:
        raise ValueError(f"{partition_name} rows must use ordered TARGET_YEAR keys")
    expected_check = f"ANNUAL_{expected_key}_TARGET_YEAR"
    if row["check_name"] != expected_check:
        raise ValueError(f"{partition_name} row {expected_key} check_name mismatch")

    counts = tuple(
        _integer(row[key], f"{partition_name} row {expected_key}.{key}")
        for key in (
            "event_count",
            "up_count",
            "down_count",
            "continuation_count",
            "reversal_count",
            "tie_count",
        )
    )
    event_count, up_count, down_count, continuation_count, reversal_count, tie_count = counts
    if event_count != up_count + down_count:
        raise ValueError(f"{partition_name} row {expected_key} direction arithmetic mismatch")
    if event_count != continuation_count + reversal_count + tie_count:
        raise ValueError(f"{partition_name} row {expected_key} label arithmetic mismatch")

    metric_value = _string(row["metric_value"], f"{partition_name} row metric_value")
    if DECIMAL_PATTERN.fullmatch(metric_value) is None:
        raise ValueError(f"{partition_name} row {expected_key} metric_value is not decimal text")
    try:
        metric = Decimal(metric_value)
    except InvalidOperation as error:
        raise ValueError(f"{partition_name} row {expected_key} metric_value is invalid") from error
    if metric >= 0:
        raise ValueError("ALL_REQUIRED_TARGET_YEAR_MEDIANS_NEGATIVE failed")

    check = result_checks.get(expected_check)
    if check is None or check["status"] != "PASS":
        raise ValueError(f"bound result lacks exactly one PASS check {expected_check}")
    if not check["evidence"].startswith(_evidence_prefix(row)):
        raise ValueError(f"bound result evidence drift for {expected_check}")
    return counts


def _validate_partition(
    value: Any,
    *,
    expected_name: str,
    result_checks: dict[str, dict[str, Any]],
) -> None:
    partition = _object(value, f"partition {expected_name}")
    _exact_keys(partition, PARTITION_OBJECT_KEYS, f"partition {expected_name}")
    if partition["name"] != expected_name:
        raise ValueError("partitions must be ordered DESIGN then VALIDATION")
    required_keys = list(PARTITION_KEYS[expected_name])
    if partition["required_keys"] != required_keys:
        raise ValueError(f"{expected_name} required_keys must be exact ordered TARGET_YEAR keys")
    rows = partition["rows"]
    if not isinstance(rows, list) or len(rows) != len(required_keys):
        raise ValueError(f"{expected_name} rows must match required_keys exactly")
    row_counts = [
        _validate_row(
            row,
            partition_name=expected_name,
            expected_key=key,
            result_checks=result_checks,
        )
        for row, key in zip(rows, required_keys, strict=True)
    ]

    totals = _object(partition["totals"], f"partition {expected_name}.totals")
    _exact_keys(totals, TOTAL_KEYS, f"partition {expected_name}.totals")
    total_values = tuple(
        _integer(totals[key], f"partition {expected_name}.totals.{key}")
        for key in (
            "event_count",
            "up_count",
            "down_count",
            "continuation_count",
            "reversal_count",
            "tie_count",
        )
    )
    summed_values = tuple(sum(row[index] for row in row_counts) for index in range(6))
    if total_values != summed_values:
        raise ValueError(f"{expected_name} totals do not reconcile to rows")
    if total_values != PARTITION_TOTALS[expected_name]:
        raise ValueError(f"{expected_name} totals do not match the frozen closure")


def load_and_validate_semantic_closure(
    closure_path: Path,
    dispatch_path: Path,
    task_path: Path,
    result_path: Path,
) -> dict[str, Any]:
    dispatch_receipt = load_and_validate_dispatch(dispatch_path, task_path, result_path)

    closure_raw = closure_path.read_bytes()
    closure = _object(_load_json(closure_raw, "semantic closure"), "semantic closure")
    if closure_raw != canonical_json_document_bytes(closure):
        raise ValueError("semantic closure must use canonical JSON bytes")
    _exact_keys(closure, ROOT_KEYS, "semantic closure")
    if closure["schema_version"] != "1":
        raise ValueError("semantic closure schema_version must be 1")
    if closure["document_type"] != DOCUMENT_TYPE:
        raise ValueError("semantic closure document_type mismatch")
    if closure["authorization"] != AUTHORIZATION:
        raise ValueError("semantic closure authorization mismatch")
    if closure["year_basis"] != YEAR_BASIS:
        raise ValueError("semantic closure year_basis must be TARGET_YEAR")
    if closure["metric"] != METRIC:
        raise ValueError("semantic closure metric must be SIGNED_H24_MEDIAN")
    if closure["decision_rule"] != DECISION_RULE:
        raise ValueError("semantic closure decision rule mismatch")
    if closure["disposition"] != DISPOSITION:
        raise ValueError("semantic closure disposition mismatch")
    _validate_boundaries(closure["boundaries"])

    task_raw = task_path.read_bytes()
    dispatch_raw = dispatch_path.read_bytes()
    result_raw = result_path.read_bytes()
    result = _object(_load_json(result_raw, "local research result"), "local research result")

    bindings = _object(closure["bindings"], "bindings")
    _exact_keys(bindings, BINDING_KEYS, "bindings")
    actual_bindings = {
        "task_id": dispatch_receipt["task_id"],
        "task_sha256": hashlib.sha256(task_raw).hexdigest(),
        "dispatch_id": dispatch_receipt["dispatch_id"],
        "dispatch_sha256": hashlib.sha256(dispatch_raw).hexdigest(),
        "result_task_id": result["task_id"],
        "result_sha256": hashlib.sha256(result_raw).hexdigest(),
        "result_source_git_commit": result["source_git_commit"],
    }
    for name, actual in actual_bindings.items():
        if bindings[name] != actual:
            raise ValueError(f"semantic closure binding mismatch: {name}")
    for name in ("task_sha256", "dispatch_sha256", "result_sha256"):
        _sha256(bindings[name], f"bindings.{name}")
    if (
        not isinstance(bindings["result_source_git_commit"], str)
        or GIT_COMMIT_PATTERN.fullmatch(bindings["result_source_git_commit"]) is None
    ):
        raise ValueError("bindings.result_source_git_commit must be a Git commit")

    checks = result.get("checks")
    if not isinstance(checks, list):
        raise ValueError("bound result checks must be a list")
    result_checks: dict[str, dict[str, Any]] = {}
    for raw_check in checks:
        check = _object(raw_check, "bound result check")
        name = _string(check.get("name"), "bound result check.name")
        if name in result_checks:
            raise ValueError(f"bound result has duplicate check name: {name}")
        result_checks[name] = check

    partitions = closure["partitions"]
    if not isinstance(partitions, list) or len(partitions) != 2:
        raise ValueError("semantic closure must contain exactly two partitions")
    for partition, expected_name in zip(partitions, ("DESIGN", "VALIDATION"), strict=True):
        _validate_partition(
            partition,
            expected_name=expected_name,
            result_checks=result_checks,
        )
    summary = result.get("summary")
    if not isinstance(summary, str) or not summary.startswith(f"{DISPOSITION}."):
        raise ValueError("bound result summary disposition mismatch")

    return {
        "closure_sha256": hashlib.sha256(closure_raw).hexdigest(),
        "dispatch_id": bindings["dispatch_id"],
        "dispatch_sha256": bindings["dispatch_sha256"],
        "disposition": closure["disposition"],
        "result_sha256": bindings["result_sha256"],
        "status": "VALID",
        "task_id": bindings["task_id"],
        "task_sha256": bindings["task_sha256"],
        "validated_rows": 6,
    }
