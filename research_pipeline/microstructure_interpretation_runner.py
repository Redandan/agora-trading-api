from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Iterable

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_handoff import (
    HandoffContext,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_TASK_ID as SOURCE_TASK_ID,
    DIAGNOSTIC_TASK_SHA256 as SOURCE_TASK_SHA256,
    RESULT_NAME as SOURCE_RESULT_NAME,
    HandoffRunnerBlocked,
    RuntimePaths as HandoffRuntimePaths,
    _has_reparse_point,
    _repository_path,
    _require_type,
    _validate_fixed_package,
)
from research_pipeline.microstructure_interpretation import (
    interpret_handoff_result_bytes,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3-interpretation-runner.v2.json"
)
RUNNER_TASK_ID = "local-node-microstructure-v3-interpretation-runner-v2"
RUNNER_TASK_SHA256 = (
    "0607f48c3542dbbb2f662f401998904c483f6d60e453c7ba6fea9a9eebf9155f"
)
SOURCE_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox/"
    "local-node-microstructure-v3-evidence-diagnostic-v1"
)
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-v3-interpretation-runner-v2"
)
OUTPUT_RESULT_NAME = "interpretation-result.json"
INTERPRETATION_CONTRACT_RELATIVE = (
    "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json"
)

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/examples/local-research-task.microstructure-v3-evidence-diagnostic.v1.json": (
        "d50e41e5fe98e76c1ff9930baeb89ba357040dd70b2cfdd51656edbc8c03ad86"
    ),
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/microstructure_handoff.py": (
        "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865"
    ),
    "research_pipeline/microstructure_handoff_runner.py": (
        "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc"
    ),
    "research_pipeline/microstructure-handoff-manifest.v1.schema.json": (
        "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090"
    ),
    "research_pipeline/microstructure-handoff-result.v1.schema.json": (
        "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9"
    ),
    "research_pipeline/microstructure_interpretation.py": (
        "3892ae7a14161de3505bcb31de4b26ea897f52bc20a54db642b7b5706c520e39"
    ),
    "research_pipeline/microstructure-interpretation-result.v1.schema.json": (
        "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564"
    ),
    INTERPRETATION_CONTRACT_RELATIVE: (
        "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509"
    ),
    "research_pipeline/microstructure_source_contract.py": (
        "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee"
    ),
}


class InterpretationRunnerBlocked(ValueError):
    pass


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    source_root: Path
    output_root: Path


@dataclass(frozen=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class _SourceSnapshot:
    context: HandoffContext
    inventory_hashes: tuple[tuple[str, str], ...]
    result_raw: bytes
    handoff_payload_sha256: str
    diagnostic_payload_sha256: str
    diagnostic_document_sha256: str


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    source_root=SOURCE_ROOT,
    output_root=OUTPUT_ROOT,
)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _absolute(path: Path) -> Path:
    return Path(os.path.abspath(path))


def _validate_separate_roots(paths: RuntimePaths) -> None:
    roots = tuple(_absolute(path) for path in (
        paths.repository_root,
        paths.source_root,
        paths.output_root,
    ))
    normalized = tuple(os.path.normcase(str(path)) for path in roots)
    if len(set(normalized)) != 3:
        raise InterpretationRunnerBlocked("repository, source, and output roots must differ")
    for index, left in enumerate(normalized):
        for right in normalized[index + 1:]:
            try:
                common = os.path.commonpath((left, right))
            except ValueError:
                continue
            if common in {left, right}:
                raise InterpretationRunnerBlocked(
                    "repository, source, and output roots must not overlap"
                )


def _require_runner_type(path: Path, *, directory: bool, label: str) -> os.stat_result:
    try:
        return _require_type(path, directory=directory, label=label)
    except HandoffRunnerBlocked as error:
        raise InterpretationRunnerBlocked(str(error)) from error


def _validate_runner_task(paths: RuntimePaths) -> _TaskSnapshot:
    repository_root = _absolute(paths.repository_root)
    _require_runner_type(repository_root, directory=True, label="repository root")
    task_path = _repository_path(repository_root, RUNNER_TASK_RELATIVE)
    _require_runner_type(
        task_path,
        directory=False,
        label="fixed interpretation runner task",
    )
    task_raw = task_path.read_bytes()
    task_hash = _sha256(task_raw)
    if task_hash != RUNNER_TASK_SHA256:
        raise InterpretationRunnerBlocked("fixed interpretation runner task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(task_raw, "fixed interpretation runner task")
        )
    except ValueError as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    if (
        task["task_id"] != RUNNER_TASK_ID
        or task["task_type"] != "TOOLING_VERTICAL_SLICE"
        or task["execution_mode"] != "WORKTREE_WRITE"
        or task["authorization"] != AUTHORIZATION
        or task["state_authority"] != "SERVER_CANONICAL"
        or task["timer_authority"] != "CODEX_CLOUD_OPS_ONLY"
        or task["limits"]
        != {
            "timeout_seconds": 7200,
            "max_files_changed": 2,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        }
    ):
        raise InterpretationRunnerBlocked("fixed interpretation runner authority changed")
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise InterpretationRunnerBlocked("fixed repository input contract changed")
    observed: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(repository_root, relative_name)
        _require_runner_type(
            target,
            directory=False,
            label=f"repository input {relative_name}",
        )
        actual_hash = _sha256(target.read_bytes())
        if actual_hash != expected_hash:
            raise InterpretationRunnerBlocked(
                f"repository input hash changed: {relative_name}"
            )
        observed.append((relative_name, actual_hash))
    return _TaskSnapshot(task_hash, tuple(observed))


def _validate_source(paths: RuntimePaths) -> _SourceSnapshot:
    try:
        context, observed = _validate_fixed_package(
            HandoffRuntimePaths(
                repository_root=paths.repository_root,
                task_owned_root=paths.source_root,
            )
        )
    except (HandoffRunnerBlocked, ValueError) as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    if SOURCE_RESULT_NAME not in observed:
        raise InterpretationRunnerBlocked("fixed source diagnostic result is missing")
    result_path = observed[SOURCE_RESULT_NAME]
    _require_runner_type(
        result_path,
        directory=False,
        label="fixed source diagnostic result",
    )
    result_raw = result_path.read_bytes()
    try:
        handoff = validate_handoff_result_bytes(result_raw, context)
    except ValueError as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    inventory_hashes = tuple(
        (name, _sha256(path.read_bytes()))
        for name, path in sorted(observed.items())
    )
    return _SourceSnapshot(
        context=context,
        inventory_hashes=inventory_hashes,
        result_raw=result_raw,
        handoff_payload_sha256=handoff["seal"]["payload_sha256"],
        diagnostic_payload_sha256=handoff["diagnostic_payload_hashes"][
            "payload_sha256"
        ],
        diagnostic_document_sha256=handoff["diagnostic_payload_hashes"][
            "canonical_document_sha256"
        ],
    )


def _validate_interpretation_binding(
    raw: bytes, source: _SourceSnapshot
) -> dict[str, Any]:
    try:
        result = validate_interpretation_result_bytes(raw)
    except ValueError as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    handoff = result["source_handoff_result"]
    diagnostic = result["source_diagnostic_result"]
    if (
        handoff["document_sha256"] != _sha256(source.result_raw)
        or handoff["payload_sha256"] != source.handoff_payload_sha256
        or diagnostic["payload_sha256"] != source.diagnostic_payload_sha256
        or diagnostic["canonical_document_sha256"]
        != source.diagnostic_document_sha256
    ):
        raise InterpretationRunnerBlocked("interpretation source binding changed")
    return result


def _scan_output(root: Path) -> Path | None:
    root = _absolute(root)
    _require_runner_type(root, directory=True, label="fixed interpretation outbox")
    try:
        entries = list(os.scandir(root))
    except OSError as error:
        raise InterpretationRunnerBlocked("fixed interpretation outbox is inaccessible") from error
    if not entries:
        return None
    if len(entries) != 1 or entries[0].name != OUTPUT_RESULT_NAME:
        raise InterpretationRunnerBlocked("fixed interpretation outbox inventory changed")
    entry = entries[0]
    try:
        info = entry.stat(follow_symlinks=False)
    except OSError as error:
        raise InterpretationRunnerBlocked("interpretation output is inaccessible") from error
    if entry.is_symlink() or _has_reparse_point(info) or not stat.S_ISREG(info.st_mode):
        raise InterpretationRunnerBlocked(
            "interpretation output must be a regular non-link file"
        )
    return Path(entry.path)


def _create_output_once(root: Path, raw: bytes) -> tuple[Path, str]:
    _validate_interpretation_binding_only(raw)
    target = _scan_output(root)
    if target is not None:
        existing = target.read_bytes()
        if existing != raw:
            raise InterpretationRunnerBlocked("conflicting interpretation output exists")
        _validate_interpretation_binding_only(existing)
        return target, "IDEMPOTENT_IDENTICAL"
    target = _absolute(root) / OUTPUT_RESULT_NAME
    try:
        with target.open("xb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except FileExistsError:
        existing_target = _scan_output(root)
        if existing_target is None or existing_target.read_bytes() != raw:
            raise InterpretationRunnerBlocked(
                "conflicting interpretation output won the create race"
            )
        _validate_interpretation_binding_only(existing_target.read_bytes())
        return existing_target, "IDEMPOTENT_IDENTICAL"
    created = _scan_output(root)
    if created is None or created.read_bytes() != raw:
        raise InterpretationRunnerBlocked("created interpretation output bytes changed")
    _validate_interpretation_binding_only(created.read_bytes())
    return created, "CREATED"


def _validate_interpretation_binding_only(raw: bytes) -> dict[str, Any]:
    try:
        return validate_interpretation_result_bytes(raw)
    except ValueError as error:
        raise InterpretationRunnerBlocked(str(error)) from error


def run_interpretation(
    paths: RuntimePaths,
    *,
    interpreter: Callable[[bytes, HandoffContext], bytes] | None = None,
) -> dict[str, Any]:
    _validate_separate_roots(paths)
    before_task = _validate_runner_task(paths)
    before_source = _validate_source(paths)
    interpreter_function = (
        interpret_handoff_result_bytes if interpreter is None else interpreter
    )
    try:
        result_raw = interpreter_function(
            before_source.result_raw,
            before_source.context,
        )
    except Exception as error:
        raise InterpretationRunnerBlocked(
            f"interpreter failed: {type(error).__name__}: {error}"
        ) from error
    if not isinstance(result_raw, bytes):
        raise InterpretationRunnerBlocked("interpreter must return bytes")
    interpretation = _validate_interpretation_binding(result_raw, before_source)

    after_task = _validate_runner_task(paths)
    after_source = _validate_source(paths)
    if before_task != after_task or before_source != after_source:
        raise InterpretationRunnerBlocked("inputs changed during interpretation")

    result_path, write_status = _create_output_once(paths.output_root, result_raw)
    output_raw = result_path.read_bytes()
    _validate_interpretation_binding(output_raw, after_source)
    return {
        "status": write_status,
        "result": OUTPUT_RESULT_NAME,
        "sha256": _sha256(output_raw),
        "disposition": interpretation["disposition"],
    }


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_interpretation(PRODUCTION_PATHS)
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
