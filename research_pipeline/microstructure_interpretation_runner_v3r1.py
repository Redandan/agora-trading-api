from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Any, Callable, Iterable

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_handoff import HandoffContext, RESULT_NAME
from research_pipeline.microstructure_handoff_runner import (
    HandoffRunnerBlocked,
    _repository_path,
)
from research_pipeline.microstructure_handoff_runner_v3r1 import (
    RuntimePaths as HandoffRuntimePaths,
    _validate_fixed_package,
)
from research_pipeline.microstructure_handoff_v3r1 import (
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_interpretation import (
    interpret_handoff_result_bytes,
)
from research_pipeline.microstructure_interpretation_runner import (
    InterpretationRunnerBlocked,
    RuntimePaths,
    _SourceSnapshot,
    _absolute,
    _create_output_once,
    _require_runner_type,
    _validate_interpretation_binding,
    _validate_separate_roots,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3r1-interpretation-runner.v1.json"
)
RUNNER_TASK_ID = "local-node-microstructure-v3r1-interpretation-runner-v1"
RUNNER_TASK_SHA256 = (
    "bf022e30e429c3859c80aead880fc71f6e84a3c3598421ddf3aa289127334a77"
)
DIAGNOSTIC_TASK_ID = "local-node-microstructure-v3r1-evidence-diagnostic-v1"
SOURCE_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox"
) / DIAGNOSTIC_TASK_ID
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox"
) / RUNNER_TASK_ID
OUTPUT_RESULT_NAME = "interpretation-result.json"

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/examples/local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json": "7c18f791996ddd1b55ba43ee0a2e194284574155d4b4e536e857e56a83a8596b",
    "research_pipeline/examples/local-research-task.microstructure-v3r1-handoff-transfer.v1.json": "81affa9f98b436820209d15eed334663c441efd64de73a65abd5caa2975ed2b0",
    "research_pipeline/local_node.py": "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed",
    "research_pipeline/microstructure_handoff.py": "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865",
    "research_pipeline/microstructure_handoff_runner.py": "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc",
    "research_pipeline/microstructure_handoff_v3r1.py": "8bd8d7d02906251b10fcf43e08b9683ea24495495b27ac53bb76dd1f51eeb76d",
    "research_pipeline/microstructure_handoff_runner_v3r1.py": "d80f253c4bdf933855b067f75d95d55cbbafb5189d353df4756356b878382718",
    "research_pipeline/microstructure_handoff_receive_v3r1.py": "aaec65f602eddd60c0290f7f5bb384b99ab6ff42c97d88e91490974557aa223b",
    "research_pipeline/microstructure-discovery-handoff-manifest.v3r1.schema.json": "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617",
    "research_pipeline/microstructure-handoff-result.v3r1.schema.json": "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9",
    "research_pipeline/microstructure_interpretation.py": "3892ae7a14161de3505bcb31de4b26ea897f52bc20a54db642b7b5706c520e39",
    "research_pipeline/microstructure-interpretation-result.v1.schema.json": "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564",
    "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json": "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509",
    "research_pipeline/microstructure_source_contract.py": "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee",
}


@dataclass(frozen=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]


PRODUCTION_PATHS = RuntimePaths(REPOSITORY_ROOT, SOURCE_ROOT, OUTPUT_ROOT)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _validate_runner_task(paths: RuntimePaths) -> _TaskSnapshot:
    root = _absolute(paths.repository_root)
    _require_runner_type(root, directory=True, label="repository root")
    task_path = _repository_path(root, RUNNER_TASK_RELATIVE)
    _require_runner_type(task_path, directory=False, label="fixed V3R1 interpretation task")
    raw = task_path.read_bytes()
    if _sha256(raw) != RUNNER_TASK_SHA256:
        raise InterpretationRunnerBlocked("fixed V3R1 interpretation task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(raw, "fixed V3R1 interpretation task")
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
        raise InterpretationRunnerBlocked("fixed V3R1 interpretation authority changed")
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise InterpretationRunnerBlocked("fixed V3R1 interpretation inputs changed")
    observed: list[tuple[str, str]] = []
    for name, expected in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(root, name)
        _require_runner_type(target, directory=False, label=f"repository input {name}")
        actual = _sha256(target.read_bytes())
        if actual != expected:
            raise InterpretationRunnerBlocked(f"repository input hash changed: {name}")
        observed.append((name, actual))
    return _TaskSnapshot(RUNNER_TASK_SHA256, tuple(observed))


def _validate_source(paths: RuntimePaths) -> _SourceSnapshot:
    try:
        context, observed = _validate_fixed_package(
            HandoffRuntimePaths(paths.repository_root, paths.source_root)
        )
    except (HandoffRunnerBlocked, ValueError) as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    if RESULT_NAME not in observed:
        raise InterpretationRunnerBlocked("fixed V3R1 diagnostic result is missing")
    result_path = observed[RESULT_NAME]
    _require_runner_type(result_path, directory=False, label="fixed V3R1 diagnostic result")
    result_raw = result_path.read_bytes()
    try:
        handoff = validate_handoff_result_bytes(result_raw, context)
    except ValueError as error:
        raise InterpretationRunnerBlocked(str(error)) from error
    inventory_hashes = tuple(
        (name, _sha256(path.read_bytes())) for name, path in sorted(observed.items())
    )
    return _SourceSnapshot(
        context=context,
        inventory_hashes=inventory_hashes,
        result_raw=result_raw,
        handoff_payload_sha256=handoff["seal"]["payload_sha256"],
        diagnostic_payload_sha256=handoff["diagnostic_payload_hashes"]["payload_sha256"],
        diagnostic_document_sha256=handoff["diagnostic_payload_hashes"]["canonical_document_sha256"],
    )


def run_interpretation(
    paths: RuntimePaths,
    *,
    interpreter: Callable[[bytes, HandoffContext], bytes] | None = None,
) -> dict[str, Any]:
    _validate_separate_roots(paths)
    before_task = _validate_runner_task(paths)
    before_source = _validate_source(paths)
    interpreter_function = interpret_handoff_result_bytes if interpreter is None else interpreter
    try:
        raw = interpreter_function(before_source.result_raw, before_source.context)
    except Exception as error:
        raise InterpretationRunnerBlocked(f"interpreter failed: {type(error).__name__}: {error}") from error
    if not isinstance(raw, bytes):
        raise InterpretationRunnerBlocked("interpreter must return bytes")
    interpretation = _validate_interpretation_binding(raw, before_source)
    after_task = _validate_runner_task(paths)
    after_source = _validate_source(paths)
    if before_task != after_task or before_source != after_source:
        raise InterpretationRunnerBlocked("V3R1 inputs changed during interpretation")
    result_path, write_status = _create_output_once(paths.output_root, raw)
    output_raw = result_path.read_bytes()
    _validate_interpretation_binding(output_raw, after_source)
    return {
        "status": write_status,
        "result": OUTPUT_RESULT_NAME,
        "sha256": _sha256(output_raw),
        "disposition": interpretation["disposition"],
    }


def main(argv: Iterable[str] | None = None) -> int:
    if list(sys.argv[1:] if argv is None else argv):
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_interpretation(PRODUCTION_PATHS)
    except Exception as error:
        print(json.dumps({"status": "BLOCKED", "reason": f"{type(error).__name__}: {error}"}, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
