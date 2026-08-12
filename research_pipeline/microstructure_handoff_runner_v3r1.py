from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Iterable, Sequence

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_diagnostic import analyze_files
from research_pipeline.microstructure_handoff import HandoffContext, RESULT_NAME
from research_pipeline.microstructure_handoff_runner import (
    HandoffRunnerBlocked,
    _CanonicalAnalyzerPath,
    _has_reparse_point,
    _repository_path,
    _require_type,
    _wrap_result,
)
from research_pipeline.microstructure_handoff_v3r1 import (
    MANIFEST_NAME,
    create_result_once,
    expected_file_names,
    load_manifest_bytes,
    validate_handoff_package,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DIAGNOSTIC_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json"
)
DIAGNOSTIC_TASK_ID = "local-node-microstructure-v3r1-evidence-diagnostic-v1"
DIAGNOSTIC_TASK_SHA256 = (
    "7c18f791996ddd1b55ba43ee0a2e194284574155d4b4e536e857e56a83a8596b"
)
WINDOWS_INBOX_ROOT = Path("C:/Users/Redan/.codex/local-research-node/inbox")
TASK_OWNED_ROOT = WINDOWS_INBOX_ROOT / DIAGNOSTIC_TASK_ID
DIAGNOSTIC_CONTRACT_RELATIVE = (
    "research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json"
)
EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/microstructure-discovery-handoff-manifest.v3r1.schema.json": (
        "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617"
    ),
    "research_pipeline/microstructure_discovery_recovery_v3r1.py": (
        "856cf47012d83fc46280f87312f35dd15c51ea8f62083fd073f23d5847208adb"
    ),
    "research_pipeline/microstructure_diagnostic.py": (
        "f8227dda823a1bd276353f3d9c8cfd0a57e3aa2f087c4a2a4bc2576997314d82"
    ),
    "research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json": (
        "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a"
    ),
}


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    task_owned_root: Path


@dataclass(frozen=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]


PRODUCTION_PATHS = RuntimePaths(REPOSITORY_ROOT, TASK_OWNED_ROOT)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _validate_fixed_task(paths: RuntimePaths) -> _TaskSnapshot:
    root = Path(os.path.abspath(paths.repository_root))
    _require_type(root, directory=True, label="repository root")
    task_path = _repository_path(root, DIAGNOSTIC_TASK_RELATIVE)
    _require_type(task_path, directory=False, label="fixed V3R1 diagnostic task")
    raw = task_path.read_bytes()
    if _sha256(raw) != DIAGNOSTIC_TASK_SHA256:
        raise HandoffRunnerBlocked("fixed V3R1 diagnostic task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(raw, "fixed V3R1 diagnostic task")
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
        raise HandoffRunnerBlocked("fixed V3R1 diagnostic authority changed")
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise HandoffRunnerBlocked("fixed V3R1 diagnostic inputs changed")
    observed: list[tuple[str, str]] = []
    for name, expected in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(root, name)
        _require_type(target, directory=False, label=f"repository input {name}")
        actual = _sha256(target.read_bytes())
        if actual != expected:
            raise HandoffRunnerBlocked(f"repository input hash changed: {name}")
        observed.append((name, actual))
    return _TaskSnapshot(DIAGNOSTIC_TASK_SHA256, tuple(observed))


def _scan_exact_package(
    root: Path,
) -> tuple[dict[str, Path], dict[str, Any]]:
    root = Path(os.path.abspath(root))
    _require_type(root, directory=True, label="fixed V3R1 task-owned root")
    manifest_path = root / MANIFEST_NAME
    _require_type(manifest_path, directory=False, label="fixed V3R1 manifest")
    try:
        manifest = load_manifest_bytes(
            manifest_path.read_bytes(),
            expected_task_id=DIAGNOSTIC_TASK_ID,
            expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
        )
    except ValueError as error:
        raise HandoffRunnerBlocked(str(error)) from error
    expected_files = set(expected_file_names(manifest))
    expected_directories = {
        str(Path(name).parent).replace("\\", "/")
        for name in expected_files
        if Path(name).parent != Path(".")
    }
    expected_directories.add("days")
    observed_files: dict[str, Path] = {}
    observed_directories: set[str] = set()
    pending: list[tuple[Path, str]] = [(root, "")]
    while pending:
        directory, prefix = pending.pop()
        try:
            entries = list(os.scandir(directory))
        except OSError as error:
            raise HandoffRunnerBlocked("V3R1 package inventory is inaccessible") from error
        for entry in entries:
            name = f"{prefix}/{entry.name}" if prefix else entry.name
            path = Path(entry.path)
            info = entry.stat(follow_symlinks=False)
            if entry.is_symlink() or _has_reparse_point(info):
                raise HandoffRunnerBlocked(f"V3R1 package entry is linked: {name}")
            if stat.S_ISDIR(info.st_mode):
                observed_directories.add(name)
                pending.append((path, name))
            elif stat.S_ISREG(info.st_mode):
                observed_files[name] = path
            else:
                raise HandoffRunnerBlocked(f"V3R1 package entry type changed: {name}")
    allowed_files = expected_files | {RESULT_NAME}
    if frozenset(observed_files) not in {
        frozenset(expected_files),
        frozenset(allowed_files),
    }:
        raise HandoffRunnerBlocked("V3R1 package file closure changed")
    if observed_directories != expected_directories:
        raise HandoffRunnerBlocked("V3R1 package directory closure changed")
    return observed_files, manifest


def _validate_fixed_package(
    paths: RuntimePaths,
) -> tuple[HandoffContext, dict[str, Path]]:
    observed, manifest = _scan_exact_package(paths.task_owned_root)
    names = expected_file_names(manifest)
    try:
        context = validate_handoff_package(
            paths.task_owned_root,
            [(name, observed[name]) for name in names],
            expected_task_id=DIAGNOSTIC_TASK_ID,
            expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
        )
    except ValueError as error:
        raise HandoffRunnerBlocked(str(error)) from error
    if tuple(item["day"] for item in context.days) != tuple(
        item["day"] for item in manifest["days"]
    ):
        raise HandoffRunnerBlocked("V3R1 selected-day identity changed")
    return context, observed


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
    after_context, _ = _validate_fixed_package(paths)
    if before_task != after_task or before_context != after_context:
        raise HandoffRunnerBlocked("V3R1 inputs changed during analysis")
    raw = _wrap_result(after_context, diagnostic)
    try:
        result_path, write_status = create_result_once(
            paths.task_owned_root, raw, after_context
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
    if list(sys.argv[1:] if argv is None else argv):
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_handoff(PRODUCTION_PATHS)
    except Exception as error:
        print(json.dumps({"status": "BLOCKED", "reason": f"{type(error).__name__}: {error}"}, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
