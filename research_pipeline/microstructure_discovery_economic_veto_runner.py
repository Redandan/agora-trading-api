from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path, PurePosixPath
import stat
import sys
from typing import Any, Iterable

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_discovery_economic_veto import (
    DayEvidence,
    EconomicVetoError,
    evaluate_economic_veto,
    validate_economic_veto_result_bytes,
)
from research_pipeline.microstructure_handoff import (
    HandoffContext,
    validate_handoff_package,
)
from research_pipeline.microstructure_source_contract import load_json_bytes_strict


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-discovery-economic-veto-runner.v1.json"
)
RUNNER_TASK_ID = "local-node-microstructure-discovery-economic-veto-runner-v1"
RUNNER_TASK_SHA256 = (
    "3be0e4e58fdf4345b9f2e9263f032a26af80638185dd59d35569e643dfcfac05"
)
DIAGNOSTIC_TASK_ID = "local-node-microstructure-v3-evidence-diagnostic-v1"
DIAGNOSTIC_TASK_SHA256 = (
    "d50e41e5fe98e76c1ff9930baeb89ba357040dd70b2cfdd51656edbc8c03ad86"
)
RESULT_NAME = "economic-veto-result.json"
DIAGNOSTIC_RESULT_NAME = "diagnostic-result.json"

CONTRACT_RELATIVE = (
    "research_pipeline/okx-microstructure-discovery-economic-veto-contract.v1.json"
)
RESULT_SCHEMA_RELATIVE = (
    "research_pipeline/microstructure-discovery-economic-veto-result.v1.schema.json"
)
ROUTE_CONTRACT_RELATIVE = (
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json"
)

HANDOFF_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox/"
    "local-node-microstructure-v3-evidence-diagnostic-v1"
)
INTERPRETATION_PATH = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-v3-interpretation-runner-v2/interpretation-result.json"
)
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-discovery-economic-veto-runner-v1"
)

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/local_node.py": "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed",
    "research_pipeline/policy.v3.json": "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6",
    "research_pipeline/microstructure_source_contract.py": "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee",
    "research_pipeline/okx-microstructure-forward-day.v3.schema.json": "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709",
    "research_pipeline/microstructure_diagnostic.py": "f8227dda823a1bd276353f3d9c8cfd0a57e3aa2f087c4a2a4bc2576997314d82",
    "research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json": "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
    "research_pipeline/microstructure_handoff.py": "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865",
    "research_pipeline/microstructure_handoff_runner.py": "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc",
    "research_pipeline/microstructure-handoff-manifest.v1.schema.json": "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090",
    "research_pipeline/microstructure-handoff-result.v1.schema.json": "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9",
    "research_pipeline/examples/local-research-task.microstructure-v3-evidence-diagnostic.v1.json": DIAGNOSTIC_TASK_SHA256,
    "research_pipeline/microstructure_interpretation.py": "3892ae7a14161de3505bcb31de4b26ea897f52bc20a54db642b7b5706c520e39",
    "research_pipeline/microstructure_interpretation_runner.py": "5d1de7e1e8006ca066fb857c55ad834b24bbe709a10d25ace5ea16a13dc0c04f",
    "research_pipeline/microstructure-interpretation-result.v1.schema.json": "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564",
    "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json": "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509",
    CONTRACT_RELATIVE: "8dd1ba498270237758be77d89b14819a2bd02b8d16e602aad54683e9ce1a8ffd",
    RESULT_SCHEMA_RELATIVE: "19b914871f39b2703229e716332021f8be7932845cfa5de2f2ff0c52886b2771",
    ROUTE_CONTRACT_RELATIVE: "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e",
}

_REPARSE_POINT = 0x400


class EconomicVetoRunnerBlocked(ValueError):
    pass


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    handoff_root: Path
    interpretation_path: Path
    output_root: Path


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    handoff_root=HANDOFF_ROOT,
    interpretation_path=INTERPRETATION_PATH,
    output_root=OUTPUT_ROOT,
)


def _fail(message: str) -> None:
    raise EconomicVetoRunnerBlocked(message)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _has_reparse(info: os.stat_result) -> bool:
    return bool(getattr(info, "st_file_attributes", 0) & _REPARSE_POINT)


def _require_regular(path: Path, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise EconomicVetoRunnerBlocked(f"{label} is unavailable") from error
    if not stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode) or _has_reparse(info):
        _fail(f"{label} must be a regular non-link non-reparse file")
    return info


def _require_directory(path: Path, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise EconomicVetoRunnerBlocked(f"{label} is unavailable") from error
    if not stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode) or _has_reparse(info):
        _fail(f"{label} must be a regular non-link non-reparse directory")
    return info


def _repository_path(root: Path, relative_name: str) -> Path:
    relative = PurePosixPath(relative_name)
    if relative.is_absolute() or ".." in relative.parts or relative.as_posix() != relative_name:
        _fail("repository input identity is unsafe")
    candidate = root.joinpath(*relative.parts)
    _require_regular(candidate, relative_name)
    try:
        if not candidate.resolve(strict=True).is_relative_to(root.resolve(strict=True)):
            _fail("repository input escapes the repository")
    except OSError as error:
        raise EconomicVetoRunnerBlocked("repository containment proof failed") from error
    return candidate


def _validate_runner_task(paths: RuntimePaths) -> tuple[tuple[str, str], ...]:
    task_path = _repository_path(paths.repository_root, RUNNER_TASK_RELATIVE)
    task_raw = task_path.read_bytes()
    if _sha256(task_raw) != RUNNER_TASK_SHA256:
        _fail("runner task bytes changed")
    try:
        task = load_json_bytes_strict(task_raw, "runner task")
        validate_local_research_task(task)
    except ValueError as error:
        raise EconomicVetoRunnerBlocked("runner task is invalid") from error
    if task.get("task_id") != RUNNER_TASK_ID:
        _fail("runner task identity changed")
    snapshot: list[tuple[str, str]] = [(RUNNER_TASK_RELATIVE, RUNNER_TASK_SHA256)]
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        path = _repository_path(paths.repository_root, relative_name)
        if _sha256(path.read_bytes()) != expected_hash:
            _fail(f"frozen repository input changed: {relative_name}")
        snapshot.append((relative_name, expected_hash))
    for relative_name in (
        "research_pipeline/microstructure_discovery_economic_veto.py",
        "research_pipeline/microstructure_discovery_economic_veto_runner.py",
    ):
        path = _repository_path(paths.repository_root, relative_name)
        snapshot.append((relative_name, _sha256(path.read_bytes())))
    return tuple(snapshot)


def _walk_files(root: Path) -> dict[str, Path]:
    _require_directory(root, "handoff root")
    files: dict[str, Path] = {}
    stack: list[tuple[Path, PurePosixPath]] = [(root, PurePosixPath("."))]
    while stack:
        directory, relative_directory = stack.pop()
        try:
            entries = list(os.scandir(directory))
        except OSError as error:
            raise EconomicVetoRunnerBlocked("handoff inventory could not be read") from error
        for entry in entries:
            path = Path(entry.path)
            relative = PurePosixPath(entry.name) if relative_directory == PurePosixPath(".") else relative_directory / entry.name
            try:
                info = path.lstat()
            except OSError as error:
                raise EconomicVetoRunnerBlocked("handoff inventory changed during scan") from error
            if stat.S_ISLNK(info.st_mode) or _has_reparse(info):
                _fail("handoff inventory contains a link or reparse point")
            if stat.S_ISDIR(info.st_mode):
                stack.append((path, relative))
            elif stat.S_ISREG(info.st_mode):
                name = relative.as_posix()
                if name in files:
                    _fail("handoff inventory contains a duplicate identity")
                files[name] = path
            else:
                _fail("handoff inventory contains an unsupported file type")
    return files


def _expected_handoff_names(manifest: dict[str, Any]) -> set[str]:
    try:
        state_name = manifest["canonical_state"]["relative_name"]
        days = manifest["days"]
    except (KeyError, TypeError) as error:
        raise EconomicVetoRunnerBlocked("handoff manifest is malformed") from error
    expected = {"handoff-manifest.json", state_name, DIAGNOSTIC_RESULT_NAME}
    if not isinstance(days, list) or len(days) != 14:
        _fail("handoff manifest does not bind fourteen days")
    for day in days:
        if not isinstance(day, dict):
            _fail("handoff day binding is malformed")
        expected.add(day.get("bundle_relative_name"))
        expected.add(day.get("envelope_relative_name"))
    if None in expected or len(expected) != 31:
        _fail("handoff manifest identities are incomplete or duplicated")
    return expected


def _validate_handoff(paths: RuntimePaths) -> tuple[HandoffContext, dict[str, Path]]:
    files = _walk_files(paths.handoff_root)
    manifest_path = files.get("handoff-manifest.json")
    if manifest_path is None:
        _fail("handoff manifest is missing")
    manifest = load_json_bytes_strict(manifest_path.read_bytes(), "handoff manifest")
    expected = _expected_handoff_names(manifest)
    if set(files) != expected:
        _fail("handoff package has missing or extra entries")
    inventory = [(name, path) for name, path in sorted(files.items()) if name != DIAGNOSTIC_RESULT_NAME]
    try:
        context = validate_handoff_package(
            paths.handoff_root,
            inventory,
            expected_task_id=DIAGNOSTIC_TASK_ID,
            expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
        )
    except ValueError as error:
        raise EconomicVetoRunnerBlocked("handoff package validation failed") from error
    return context, files


def _validate_separate_roots(paths: RuntimePaths) -> None:
    _require_directory(paths.repository_root, "repository root")
    _require_directory(paths.handoff_root, "handoff root")
    _require_regular(paths.interpretation_path, "interpretation result")
    resolved_repository = paths.repository_root.resolve(strict=True)
    resolved_handoff = paths.handoff_root.resolve(strict=True)
    resolved_interpretation = paths.interpretation_path.resolve(strict=True)
    output_parent = paths.output_root.parent
    _require_directory(output_parent, "output parent")
    resolved_output_parent = output_parent.resolve(strict=True)
    if len({resolved_repository, resolved_handoff, resolved_interpretation.parent, resolved_output_parent}) != 4:
        _fail("repository, handoff, interpretation, and output roots must be distinct")
    if paths.output_root.exists():
        _require_directory(paths.output_root, "output root")
        resolved_output = paths.output_root.resolve(strict=True)
        if resolved_output in {resolved_repository, resolved_handoff, resolved_interpretation.parent}:
            _fail("output root aliases an input root")


def _snapshot_source(files: dict[str, Path], interpretation: Path) -> tuple[tuple[str, str], ...]:
    snapshot = [(f"handoff/{name}", _sha256(path.read_bytes())) for name, path in sorted(files.items())]
    snapshot.append(("interpretation/interpretation-result.json", _sha256(interpretation.read_bytes())))
    return tuple(snapshot)


def _create_once(output_root: Path, raw: bytes) -> bytes:
    if output_root.exists():
        _require_directory(output_root, "output root")
        entries = list(output_root.iterdir())
        if len(entries) != 1 or entries[0].name != RESULT_NAME:
            _fail("output root is partial or contains extra entries")
        _require_regular(entries[0], "existing economic veto result")
        existing = entries[0].read_bytes()
        validate_economic_veto_result_bytes(existing)
        if existing != raw:
            _fail("existing economic veto result conflicts with current inputs")
        return existing
    try:
        output_root.mkdir()
        output_path = output_root / RESULT_NAME
        with output_path.open("xb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError as error:
        raise EconomicVetoRunnerBlocked("create-once output conflict") from error
    except OSError as error:
        raise EconomicVetoRunnerBlocked("create-once output publication failed") from error
    _require_regular(output_root / RESULT_NAME, "created economic veto result")
    if (output_root / RESULT_NAME).read_bytes() != raw:
        _fail("created economic veto result read-back changed")
    return raw


def run_economic_veto(paths: RuntimePaths = PRODUCTION_PATHS) -> bytes:
    _validate_separate_roots(paths)
    repository_before = _validate_runner_task(paths)
    context, files = _validate_handoff(paths)
    source_before = _snapshot_source(files, paths.interpretation_path)
    handoff_result_raw = files[DIAGNOSTIC_RESULT_NAME].read_bytes()
    interpretation_raw = paths.interpretation_path.read_bytes()
    days = [
        DayEvidence(
            binding=binding,
            bundle_raw=files[binding["bundle_relative_name"]].read_bytes(),
            envelope_raw=files[binding["envelope_relative_name"]].read_bytes(),
        )
        for binding in context.days
    ]
    try:
        result = evaluate_economic_veto(
            handoff_context=context,
            handoff_result_raw=handoff_result_raw,
            interpretation_result_raw=interpretation_raw,
            days=days,
            contract_raw=_repository_path(paths.repository_root, CONTRACT_RELATIVE).read_bytes(),
            result_schema_raw=_repository_path(paths.repository_root, RESULT_SCHEMA_RELATIVE).read_bytes(),
            route_contract_raw=_repository_path(paths.repository_root, ROUTE_CONTRACT_RELATIVE).read_bytes(),
        )
    except ValueError as error:
        raise EconomicVetoRunnerBlocked("economic veto evaluation failed") from error
    if repository_before != _validate_runner_task(paths):
        _fail("repository task or runtime bytes changed during evaluation")
    context_after, files_after = _validate_handoff(paths)
    if context_after != context or source_before != _snapshot_source(files_after, paths.interpretation_path):
        _fail("handoff or interpretation bytes changed during evaluation")
    published = _create_once(paths.output_root, result)
    if repository_before != _validate_runner_task(paths):
        _fail("repository task or runtime bytes changed during publication")
    _, files_final = _validate_handoff(paths)
    if source_before != _snapshot_source(files_final, paths.interpretation_path):
        _fail("handoff or interpretation bytes changed during publication")
    return published


def main(argv: Iterable[str] | None = None) -> int:
    arguments = tuple(sys.argv[1:] if argv is None else argv)
    if arguments:
        print("economic veto runner accepts no arguments", file=sys.stderr)
        return 2
    try:
        run_economic_veto(PRODUCTION_PATHS)
    except (EconomicVetoRunnerBlocked, EconomicVetoError, OSError, ValueError):
        print("economic veto runner blocked", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
