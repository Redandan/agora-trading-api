from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
from typing import Any, Callable, Iterable, Sequence

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_diagnostic import (
    CONTRACT_ID as DIAGNOSTIC_CONTRACT_ID,
    analyze_files,
)
from research_pipeline.microstructure_handoff import (
    HANDOFF_CANONICALIZATION,
    INFERENCE_BOUNDARIES,
    MANIFEST_NAME,
    RESULT_TYPE,
    HandoffContext,
    create_result_once,
    validate_handoff_package,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    canonical_json_bytes,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DIAGNOSTIC_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3-evidence-diagnostic.v1.json"
)
DIAGNOSTIC_TASK_ID = "local-node-microstructure-v3-evidence-diagnostic-v1"
DIAGNOSTIC_TASK_SHA256 = (
    "d50e41e5fe98e76c1ff9930baeb89ba357040dd70b2cfdd51656edbc8c03ad86"
)
DIAGNOSTIC_ID = "okx-btcusdt-microstructure-forward-v3-20260808"
START_DAY = date(2026, 8, 8)
REQUIRED_DAYS = 14
ORDERED_DAYS = tuple(START_DAY + timedelta(days=index) for index in range(REQUIRED_DAYS))
WINDOWS_INBOX_ROOT = Path("C:/Users/Redan/.codex/local-research-node/inbox")
TASK_OWNED_ROOT = WINDOWS_INBOX_ROOT / DIAGNOSTIC_TASK_ID
RESULT_NAME = "diagnostic-result.json"
DIAGNOSTIC_CONTRACT_RELATIVE = (
    "research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json"
)

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/microstructure_handoff.py": (
        "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865"
    ),
    "research_pipeline/microstructure-handoff-manifest.v1.schema.json": (
        "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090"
    ),
    "research_pipeline/microstructure-handoff-result.v1.schema.json": (
        "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9"
    ),
    "research_pipeline/microstructure_diagnostic.py": (
        "f8227dda823a1bd276353f3d9c8cfd0a57e3aa2f087c4a2a4bc2576997314d82"
    ),
    DIAGNOSTIC_CONTRACT_RELATIVE: V3_DIAGNOSTIC_CONTRACT_SHA256,
}

_REPARSE_POINT = 0x400


class HandoffRunnerBlocked(ValueError):
    pass


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    task_owned_root: Path


@dataclass(frozen=True)
class _FrozenTaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]


class _CanonicalAnalyzerPath:
    """Read physical package bytes while exposing the frozen POSIX identity."""

    def __init__(self, physical_path: Path, relative_name: str) -> None:
        self._physical_path = physical_path
        self._relative_name = relative_name

    def read_text(self, *, encoding: str) -> str:
        return self._physical_path.read_text(encoding=encoding)

    def read_bytes(self) -> bytes:
        return self._physical_path.read_bytes()

    def __str__(self) -> str:
        return self._relative_name


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    task_owned_root=TASK_OWNED_ROOT,
)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _has_reparse_point(info: os.stat_result) -> bool:
    return bool(getattr(info, "st_file_attributes", 0) & _REPARSE_POINT)


def _require_type(path: Path, *, directory: bool, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise HandoffRunnerBlocked(f"{label} is missing or inaccessible") from error
    if stat.S_ISLNK(info.st_mode) or _has_reparse_point(info):
        raise HandoffRunnerBlocked(f"{label} must not be a link or reparse point")
    correct_type = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if not correct_type:
        raise HandoffRunnerBlocked(f"{label} has the wrong filesystem type")
    return info


def _repository_path(root: Path, relative_name: str) -> Path:
    if (
        not relative_name
        or "\\" in relative_name
        or ":" in relative_name
        or PurePosixPath(relative_name).is_absolute()
        or any(part in {"", ".", ".."} for part in relative_name.split("/"))
    ):
        raise HandoffRunnerBlocked("task repository input path is unsafe")
    parts = PurePosixPath(relative_name).parts
    current = root
    for part in parts[:-1]:
        current = current / part
        _require_type(
            current,
            directory=True,
            label=f"repository input parent {current}",
        )
    return current / parts[-1]


def _validate_fixed_task(paths: RuntimePaths) -> _FrozenTaskSnapshot:
    repository_root = Path(os.path.abspath(paths.repository_root))
    _require_type(repository_root, directory=True, label="repository root")
    task_path = _repository_path(repository_root, DIAGNOSTIC_TASK_RELATIVE)
    _require_type(task_path, directory=False, label="fixed diagnostic task")
    task_raw = task_path.read_bytes()
    task_hash = _sha256(task_raw)
    if task_hash != DIAGNOSTIC_TASK_SHA256:
        raise HandoffRunnerBlocked("fixed diagnostic task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(task_raw, "fixed diagnostic task")
        )
    except ValueError as error:
        raise HandoffRunnerBlocked(str(error)) from error
    if (
        task["task_id"] != DIAGNOSTIC_TASK_ID
        or task["task_type"] != "EVIDENCE_DIAGNOSTIC"
        or task["execution_mode"] != "READ_ONLY"
        or task["authorization"] != AUTHORIZATION
        or task["state_authority"] != "SERVER_CANONICAL"
        or task["timer_authority"] != "CODEX_CLOUD_OPS_ONLY"
        or task["limits"]
        != {
            "timeout_seconds": 7200,
            "max_files_changed": 0,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        }
    ):
        raise HandoffRunnerBlocked("fixed diagnostic task authority changed")

    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise HandoffRunnerBlocked("fixed diagnostic task repository inputs changed")
    observed: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(repository_root, relative_name)
        _require_type(target, directory=False, label=f"repository input {relative_name}")
        actual_hash = _sha256(target.read_bytes())
        if actual_hash != expected_hash:
            raise HandoffRunnerBlocked(
                f"repository input hash changed: {relative_name}"
            )
        observed.append((relative_name, actual_hash))
    return _FrozenTaskSnapshot(task_hash, tuple(observed))


def _bundle_name(bundle_day: date) -> str:
    day_text = bundle_day.isoformat()
    return f"days/{day_text}/okx-btc-usdt-microstructure-{day_text}.json"


def _envelope_name(bundle_day: date) -> str:
    day_text = bundle_day.isoformat()
    return (
        f"days/{day_text}/okx-btc-usdt-microstructure-{day_text}.envelope.json"
    )


def _expected_file_names() -> tuple[str, ...]:
    names = [MANIFEST_NAME, f"canonical/{DIAGNOSTIC_ID}.json"]
    for bundle_day in ORDERED_DAYS:
        names.extend((_bundle_name(bundle_day), _envelope_name(bundle_day)))
    return tuple(names)


def _expected_directory_names() -> set[str]:
    return {
        "canonical",
        "days",
        *(f"days/{bundle_day.isoformat()}" for bundle_day in ORDERED_DAYS),
    }


def _scan_exact_package(root: Path) -> dict[str, Path]:
    root = Path(os.path.abspath(root))
    _require_type(root, directory=True, label="fixed task-owned root")
    observed_files: dict[str, Path] = {}
    observed_directories: set[str] = set()
    pending: list[tuple[Path, str]] = [(root, "")]
    while pending:
        directory, prefix = pending.pop()
        try:
            entries = list(os.scandir(directory))
        except OSError as error:
            raise HandoffRunnerBlocked("fixed package inventory is inaccessible") from error
        for entry in entries:
            relative_name = f"{prefix}/{entry.name}" if prefix else entry.name
            path = Path(entry.path)
            try:
                info = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise HandoffRunnerBlocked(
                    f"package entry is inaccessible: {relative_name}"
                ) from error
            if entry.is_symlink() or _has_reparse_point(info):
                raise HandoffRunnerBlocked(
                    f"package entry must not be a link or reparse point: {relative_name}"
                )
            if stat.S_ISDIR(info.st_mode):
                observed_directories.add(relative_name)
                pending.append((path, relative_name))
            elif stat.S_ISREG(info.st_mode):
                observed_files[relative_name] = path
            else:
                raise HandoffRunnerBlocked(
                    f"package entry has the wrong filesystem type: {relative_name}"
                )

    expected_files = set(_expected_file_names())
    allowed_files = expected_files | {RESULT_NAME}
    if set(observed_files) != expected_files and set(observed_files) != allowed_files:
        raise HandoffRunnerBlocked(
            "fixed package has missing, extra, or partial inventory"
        )
    if observed_directories != _expected_directory_names():
        raise HandoffRunnerBlocked("fixed package directory closure changed")
    return observed_files


def _validate_fixed_package(paths: RuntimePaths) -> tuple[HandoffContext, dict[str, Path]]:
    observed = _scan_exact_package(paths.task_owned_root)
    inventory = [
        (name, observed[name])
        for name in _expected_file_names()
    ]
    try:
        context = validate_handoff_package(
            paths.task_owned_root,
            inventory,
            expected_task_id=DIAGNOSTIC_TASK_ID,
            expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
        )
    except ValueError as error:
        raise HandoffRunnerBlocked(str(error)) from error
    expected_days = tuple(bundle_day.isoformat() for bundle_day in ORDERED_DAYS)
    if (
        context.diagnostic_id != DIAGNOSTIC_ID
        or context.state_relative_name != f"canonical/{DIAGNOSTIC_ID}.json"
        or tuple(item["day"] for item in context.days) != expected_days
        or tuple(item["bundle_relative_name"] for item in context.days)
        != tuple(_bundle_name(bundle_day) for bundle_day in ORDERED_DAYS)
    ):
        raise HandoffRunnerBlocked("handoff identity or fixed 14-day window changed")
    return context, observed


def _wrap_result(context: HandoffContext, diagnostic: dict[str, Any]) -> bytes:
    result: dict[str, Any] = {
        "schema_version": "1",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "task_id": context.task_id,
        "task_sha256": context.task_sha256,
        "input_manifest": {
            "relative_name": MANIFEST_NAME,
            "sha256": context.manifest_sha256,
            "payload_sha256": context.manifest_payload_sha256,
        },
        "canonical_state": {
            "relative_name": context.state_relative_name,
            "sha256": context.state_sha256,
            "diagnostic_id": context.diagnostic_id,
            "chain_head_sha256": context.chain_head_sha256,
        },
        "diagnostic_contract": {
            "contract_id": DIAGNOSTIC_CONTRACT_ID,
            "sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        },
        "diagnostic_payload_hashes": {
            "payload_sha256": diagnostic.get("seal", {}).get("payload_sha256"),
            "canonical_document_sha256": _sha256(canonical_json_bytes(diagnostic)),
        },
        "diagnostic_result": diagnostic,
        "inference_boundaries": dict(INFERENCE_BOUNDARIES),
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _sha256(canonical_json_bytes(result, exclude_key="seal")),
        "canonicalization": HANDOFF_CANONICALIZATION,
    }
    return canonical_json_bytes(result)


def run_handoff(
    paths: RuntimePaths,
    *,
    analyzer: Callable[..., dict[str, Any]] | None = None,
) -> dict[str, Any]:
    before_task = _validate_fixed_task(paths)
    before_context, before_inventory = _validate_fixed_package(paths)
    analyzer_inputs: Sequence[_CanonicalAnalyzerPath] = tuple(
        _CanonicalAnalyzerPath(
            before_inventory[item["bundle_relative_name"]],
            item["bundle_relative_name"],
        )
        for item in before_context.days
    )
    analyzer_function = analyze_files if analyzer is None else analyzer
    diagnostic = analyzer_function(
        analyzer_inputs,
        contract_path=_repository_path(
            Path(os.path.abspath(paths.repository_root)),
            DIAGNOSTIC_CONTRACT_RELATIVE,
        ),
    )

    after_task = _validate_fixed_task(paths)
    after_context, _after_inventory = _validate_fixed_package(paths)
    if before_task != after_task or before_context != after_context:
        raise HandoffRunnerBlocked("handoff inputs changed during analysis")

    raw_result = _wrap_result(after_context, diagnostic)
    try:
        result_path, write_status = create_result_once(
            paths.task_owned_root,
            raw_result,
            after_context,
        )
    except ValueError as error:
        raise HandoffRunnerBlocked(str(error)) from error
    return {
        "status": write_status,
        "diagnostic_status": diagnostic.get("status"),
        "result": RESULT_NAME,
        "sha256": _sha256(result_path.read_bytes()),
    }


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_handoff(PRODUCTION_PATHS)
    except Exception as error:
        print(
            json.dumps(
                {"status": "BLOCKED", "reason": f"{type(error).__name__}: {error}"},
                sort_keys=True,
            )
        )
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
