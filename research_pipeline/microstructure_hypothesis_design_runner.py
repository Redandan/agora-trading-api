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
from research_pipeline.microstructure_handoff_runner import (
    HandoffRunnerBlocked,
    _has_reparse_point,
    _repository_path,
    _require_type,
)
from research_pipeline.microstructure_hypothesis_design import (
    NON_POSITIVE_DISPOSITIONS,
    POSITIVE_DISPOSITION,
    PROPOSAL_FIELDS,
    build_hypothesis_design_result_bytes,
    validate_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_interpretation import (
    RESULT_TYPE as INTERPRETATION_RESULT_TYPE,
    TIER_ORDER,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    canonical_json_bytes,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3-hypothesis-design-runner.v1.json"
)
RUNNER_TASK_ID = "local-node-microstructure-v3-hypothesis-design-runner-v1"
RUNNER_TASK_SHA256 = (
    "fd0e4270f5f459b35e986f1e46f6aace568dc9b14a23ecfd82e8d342f1a97dc2"
)
SOURCE_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-v3-interpretation-runner-v1"
)
SOURCE_RESULT_NAME = "interpretation-result.json"
PROPOSAL_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox/"
    "local-node-microstructure-v3-hypothesis-design-runner-v1"
)
PROPOSAL_NAME = "coach-proposal.json"
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-v3-hypothesis-design-runner-v1"
)
OUTPUT_RESULT_NAME = "hypothesis-design-result.json"

PROPOSAL_TYPE = "OKX_MICROSTRUCTURE_COACH_HYPOTHESIS_PROPOSAL_V1"
PROPOSAL_SCHEMA_RELATIVE = (
    "research_pipeline/microstructure-coach-hypothesis-proposal.v1.schema.json"
)
PROPOSAL_SCHEMA_SHA256 = (
    "c2a1db83aa62c92fd86d2c6fc3b1516829d282f81c683d9d082375600de28bb0"
)
PROPOSAL_SCHEMA_PATH = Path(__file__).with_name(
    "microstructure-coach-hypothesis-proposal.v1.schema.json"
)
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)

IMPLEMENTATION_FILES = (
    "research_pipeline/microstructure-coach-hypothesis-proposal.v1.schema.json",
    "research_pipeline/microstructure_hypothesis_design_runner.py",
    "research_pipeline/tests/test_microstructure_hypothesis_design_runner.py",
    "docs/okx-microstructure-hypothesis-design-runner-v1.md",
)
EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/examples/local-research-task.microstructure-v3-interpretation-runner.v1.json": (
        "cc6bcf53cd31e134ce31b92a38a56499dbcc2a43c1f81bc18a708c801d31bcc5"
    ),
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/microstructure_interpretation_runner.py": (
        "499ca3564f34e7a9a6de59ea09b0b981e254d0ea142429642a610aa36774363f"
    ),
    "research_pipeline/microstructure_interpretation.py": (
        "3892ae7a14161de3505bcb31de4b26ea897f52bc20a54db642b7b5706c520e39"
    ),
    "research_pipeline/microstructure-interpretation-result.v1.schema.json": (
        "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564"
    ),
    "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json": (
        "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509"
    ),
    "research_pipeline/microstructure_hypothesis_design.py": (
        "ef9864342c62e0415496638a63901194fac06e9cef42120196befb9e9ffa3c4c"
    ),
    "research_pipeline/microstructure-hypothesis-design-result.v1.schema.json": (
        "af82d3aa81257eb74cf04026fc9a43ae5c0576049d850b3263b90b7f2930e63d"
    ),
    "research_pipeline/okx-microstructure-hypothesis-design-contract.v1.json": (
        "d3e3df7d629938a33cddec00f251bbaaefb4ce17b51eb0b0b558061c692f6948"
    ),
    "research_pipeline/microstructure_source_contract.py": (
        "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee"
    ),
    "research_pipeline/policy.v3.json": (
        "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6"
    ),
}
EXPECTED_ALLOWED_ACTIONS = {
    "READ_FROZEN_REPOSITORY_CONTRACTS",
    "VERIFY_ALL_NON_NULL_INPUT_HASHES",
    "ADD_COACH_PROPOSAL_SCHEMA",
    "ADD_FIXED_ROOT_DESIGN_RUNNER",
    "ADD_FOCUSED_OFFLINE_RUNNER_TESTS",
    "ADD_DESIGN_RUNNER_DOCUMENTATION",
    "BUILD_SYNTHETIC_PROPOSAL_ENVELOPES",
    "VALIDATE_SOURCE_INTERPRETATION",
    "VALIDATE_SOURCE_BOUND_PROPOSAL",
    "CALL_EXISTING_PURE_DESIGN_BUILDER",
    "REVALIDATE_ALL_INPUTS_BEFORE_OUTPUT",
    "CREATE_LOCAL_DESIGN_RESULT_ONCE",
    "RUN_LOCAL_TASK_VALIDATION",
    "RUN_FOCUSED_OFFLINE_PYTHON_TESTS",
    "RUN_PYTHON_COMPILE_CHECK",
    "RUN_GIT_DIFF_CHECK",
    "VALIDATE_JSON_SCHEMAS_AND_SEALS",
}
EXPECTED_FORBIDDEN_ACTIONS = {
    "CANONICAL_STATE_WRITE",
    "SERVER_RESEARCH_MCP_WRITE",
    "SECOND_TIMER_OR_WRITER",
    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
    "OOS_OPEN_OR_GATE_RELAXATION",
    "EXTERNAL_BACKFILL_OR_IMPORT",
    "PAID_API_OR_API_KEY",
    "PRODUCTION_OR_DATABASE_MUTATION",
    "FUTURE_EVIDENCE_OR_OUTCOME_ACCESS",
    "REAL_FIXED_ROOT_RUNNER_EXECUTION",
    "SERVER_NETWORK_OR_SSH_ACCESS",
    "CLOUD_SCHEDULE_CREATE_UPDATE_OR_DELETE",
    "SOURCE_INTERPRETATION_WRITE_DELETE_OR_REPAIR",
    "COACH_THESIS_INVENTION_OR_SELECTION",
    "HYPOTHESIS_OR_CANDIDATE_REGISTRATION",
    "ADAPTER_MANIFEST_OR_OOS_IMPLEMENTATION",
    "CALLER_SELECTED_PATH_TASK_CONTRACT_TIER_OR_MECHANISM",
    "JAVA_MAVEN_SPRING_OR_SUBPROCESS_EXECUTION",
    "GIT_STAGE_COMMIT_PUSH_OR_RESET",
    "EXISTING_FILE_EDIT",
    "OTHER_FILE_EDIT",
}
PROPOSAL_SAFETY_ASSERTIONS = {
    "hypothesis_registration_authorized": False,
    "oos_access_authorized": False,
    "activation_authorized": False,
    "second_timer_or_writer_authorized": False,
    "trading_database_order_fund_action_authorized": False,
    "paid_api_authorized": False,
}
_PROPOSAL_KEYS = {
    "schema_version",
    "proposal_type",
    "authorization",
    "source_interpretation",
    "coach_proposal",
    "safety_assertions",
    "seal",
}


class HypothesisDesignRunnerBlocked(ValueError):
    pass


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    source_root: Path
    proposal_root: Path
    output_root: Path


@dataclass(frozen=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]
    implementation_hashes: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class _SourceSnapshot:
    raw: bytes
    document_sha256: str
    payload_sha256: str
    disposition: str
    selected_tier: str | None


@dataclass(frozen=True)
class _ProposalSnapshot:
    raw: bytes | None
    proposal_items: tuple[tuple[str, str], ...] | None

    def proposal(self) -> dict[str, str] | None:
        return None if self.proposal_items is None else dict(self.proposal_items)


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    source_root=SOURCE_ROOT,
    proposal_root=PROPOSAL_ROOT,
    output_root=OUTPUT_ROOT,
)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _absolute(path: Path) -> Path:
    return Path(os.path.abspath(path))


def _require_runner_type(path: Path, *, directory: bool, label: str) -> os.stat_result:
    try:
        return _require_type(path, directory=directory, label=label)
    except HandoffRunnerBlocked as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error


def _validate_separate_roots(paths: RuntimePaths) -> None:
    roots = tuple(
        _absolute(path)
        for path in (
            paths.repository_root,
            paths.source_root,
            paths.proposal_root,
            paths.output_root,
        )
    )
    normalized = tuple(os.path.normcase(str(path)) for path in roots)
    if len(set(normalized)) != 4:
        raise HypothesisDesignRunnerBlocked("all fixed roots must differ")
    for index, left in enumerate(normalized):
        for right in normalized[index + 1 :]:
            try:
                common = os.path.commonpath((left, right))
            except ValueError:
                continue
            if common in {left, right}:
                raise HypothesisDesignRunnerBlocked("fixed roots must not overlap")


def _read_regular(path: Path, label: str) -> bytes:
    _require_runner_type(path, directory=False, label=label)
    try:
        return path.read_bytes()
    except OSError as error:
        raise HypothesisDesignRunnerBlocked(f"{label} is inaccessible") from error


def _validate_runner_task(paths: RuntimePaths) -> _TaskSnapshot:
    repository_root = _absolute(paths.repository_root)
    _require_runner_type(repository_root, directory=True, label="repository root")
    task_path = _repository_path(repository_root, RUNNER_TASK_RELATIVE)
    task_raw = _read_regular(task_path, "fixed hypothesis design runner task")
    if _sha256(task_raw) != RUNNER_TASK_SHA256:
        raise HypothesisDesignRunnerBlocked("fixed runner task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(task_raw, "fixed hypothesis design runner task")
        )
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
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
            "max_files_changed": 4,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        }
        or set(task["allowed_actions"]) != EXPECTED_ALLOWED_ACTIONS
        or set(task["forbidden_actions"]) != EXPECTED_FORBIDDEN_ACTIONS
    ):
        raise HypothesisDesignRunnerBlocked("fixed runner task authority changed")
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise HypothesisDesignRunnerBlocked("fixed repository input contract changed")

    repository_hashes: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(repository_root, relative_name)
        actual_hash = _sha256(_read_regular(target, f"repository input {relative_name}"))
        if actual_hash != expected_hash:
            raise HypothesisDesignRunnerBlocked(
                f"repository input hash changed: {relative_name}"
            )
        repository_hashes.append((relative_name, actual_hash))

    implementation_hashes: list[tuple[str, str]] = []
    for relative_name in IMPLEMENTATION_FILES:
        target = _repository_path(repository_root, relative_name)
        implementation_hashes.append(
            (
                relative_name,
                _sha256(_read_regular(target, f"implementation file {relative_name}")),
            )
        )
    return _TaskSnapshot(
        RUNNER_TASK_SHA256,
        tuple(repository_hashes),
        tuple(implementation_hashes),
    )


def _directory_entries(root: Path, label: str) -> list[os.DirEntry[str]]:
    root = _absolute(root)
    _require_runner_type(root, directory=True, label=label)
    try:
        return list(os.scandir(root))
    except OSError as error:
        raise HypothesisDesignRunnerBlocked(f"{label} is inaccessible") from error


def _entry_file(entry: os.DirEntry[str], label: str) -> Path:
    try:
        info = entry.stat(follow_symlinks=False)
    except OSError as error:
        raise HypothesisDesignRunnerBlocked(f"{label} is inaccessible") from error
    if entry.is_symlink() or _has_reparse_point(info) or not stat.S_ISREG(info.st_mode):
        raise HypothesisDesignRunnerBlocked(f"{label} must be a regular non-link file")
    return Path(entry.path)


def _validate_source(paths: RuntimePaths) -> _SourceSnapshot:
    entries = _directory_entries(paths.source_root, "fixed interpretation source root")
    if len(entries) != 1 or entries[0].name != SOURCE_RESULT_NAME:
        raise HypothesisDesignRunnerBlocked("fixed interpretation source inventory changed")
    raw = _entry_file(entries[0], "fixed interpretation result").read_bytes()
    try:
        result = validate_interpretation_result_bytes(raw)
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    return _SourceSnapshot(
        raw=raw,
        document_sha256=_sha256(raw),
        payload_sha256=result["seal"]["payload_sha256"],
        disposition=result["disposition"],
        selected_tier=result["screen"]["selected_tier"],
    )


def _source_binding(source: _SourceSnapshot) -> dict[str, Any]:
    return {
        "result_type": INTERPRETATION_RESULT_TYPE,
        "document_sha256": source.document_sha256,
        "payload_sha256": source.payload_sha256,
        "disposition": POSITIVE_DISPOSITION,
        "selected_tier": source.selected_tier,
    }


def _payload_sha256(value: dict[str, Any]) -> str:
    return _sha256(canonical_json_bytes(value, exclude_key="seal"))


def _validate_proposal_schema_file() -> None:
    raw = _read_regular(PROPOSAL_SCHEMA_PATH, "Coach proposal schema")
    if _sha256(raw) != PROPOSAL_SCHEMA_SHA256:
        raise HypothesisDesignRunnerBlocked("Coach proposal schema hash changed")
    try:
        schema = load_json_bytes_strict(raw, "Coach proposal schema")
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    if schema.get("$id") != "urn:agora-research:microstructure-coach-hypothesis-proposal:v1":
        raise HypothesisDesignRunnerBlocked("Coach proposal schema identity changed")


def _validated_inner_proposal(
    interpretation_raw: bytes,
    proposal: Any,
) -> dict[str, str]:
    try:
        design_raw = build_hypothesis_design_result_bytes(
            interpretation_raw,
            proposal,
        )
        design_result = validate_hypothesis_design_result_bytes(
            design_raw,
            interpretation_raw,
        )
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    design = design_result["hypothesis_design"]
    if not isinstance(design, dict):
        raise HypothesisDesignRunnerBlocked("Coach proposal did not produce a design")
    validated = {name: design[name] for name in PROPOSAL_FIELDS}
    if proposal != validated:
        raise HypothesisDesignRunnerBlocked("Coach proposal content changed")
    return validated


def build_coach_proposal_envelope_bytes(
    interpretation_raw: bytes,
    proposal: dict[str, Any],
) -> bytes:
    if not isinstance(interpretation_raw, bytes) or not isinstance(proposal, dict):
        raise HypothesisDesignRunnerBlocked(
            "canonical interpretation bytes and one Coach proposal are required"
        )
    _validate_proposal_schema_file()
    try:
        interpretation = validate_interpretation_result_bytes(interpretation_raw)
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    if (
        interpretation["disposition"] != POSITIVE_DISPOSITION
        or interpretation["screen"]["selected_tier"] not in TIER_ORDER
    ):
        raise HypothesisDesignRunnerBlocked(
            "Coach proposal envelope requires a positive interpretation"
        )
    source = _SourceSnapshot(
        raw=interpretation_raw,
        document_sha256=_sha256(interpretation_raw),
        payload_sha256=interpretation["seal"]["payload_sha256"],
        disposition=interpretation["disposition"],
        selected_tier=interpretation["screen"]["selected_tier"],
    )
    validated_proposal = _validated_inner_proposal(interpretation_raw, proposal)
    envelope: dict[str, Any] = {
        "schema_version": "1",
        "proposal_type": PROPOSAL_TYPE,
        "authorization": AUTHORIZATION,
        "source_interpretation": _source_binding(source),
        "coach_proposal": validated_proposal,
        "safety_assertions": dict(PROPOSAL_SAFETY_ASSERTIONS),
    }
    envelope["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(envelope),
        "canonicalization": CANONICALIZATION,
    }
    raw = canonical_json_bytes(envelope)
    validate_coach_proposal_envelope_bytes(raw, interpretation_raw)
    return raw


def validate_coach_proposal_envelope_bytes(
    raw: bytes,
    interpretation_raw: bytes,
) -> dict[str, Any]:
    if not isinstance(raw, bytes) or not isinstance(interpretation_raw, bytes):
        raise HypothesisDesignRunnerBlocked("proposal and interpretation must be bytes")
    _validate_proposal_schema_file()
    try:
        interpretation = validate_interpretation_result_bytes(interpretation_raw)
        envelope = load_json_bytes_strict(raw, "Coach proposal envelope")
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    if raw != canonical_json_bytes(envelope):
        raise HypothesisDesignRunnerBlocked("Coach proposal bytes must be canonical")
    if set(envelope) != _PROPOSAL_KEYS:
        raise HypothesisDesignRunnerBlocked("Coach proposal envelope keys changed")
    if (
        interpretation["disposition"] != POSITIVE_DISPOSITION
        or interpretation["screen"]["selected_tier"] not in TIER_ORDER
    ):
        raise HypothesisDesignRunnerBlocked(
            "Coach proposal envelope requires a positive interpretation"
        )
    source = _SourceSnapshot(
        raw=interpretation_raw,
        document_sha256=_sha256(interpretation_raw),
        payload_sha256=interpretation["seal"]["payload_sha256"],
        disposition=interpretation["disposition"],
        selected_tier=interpretation["screen"]["selected_tier"],
    )
    if (
        envelope["schema_version"] != "1"
        or envelope["proposal_type"] != PROPOSAL_TYPE
        or envelope["authorization"] != AUTHORIZATION
        or envelope["source_interpretation"] != _source_binding(source)
        or envelope["safety_assertions"] != PROPOSAL_SAFETY_ASSERTIONS
    ):
        raise HypothesisDesignRunnerBlocked("Coach proposal binding changed")
    _validated_inner_proposal(interpretation_raw, envelope["coach_proposal"])
    if envelope["seal"] != {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(envelope),
        "canonicalization": CANONICALIZATION,
    }:
        raise HypothesisDesignRunnerBlocked("Coach proposal seal changed")
    return envelope


def _validate_proposal(paths: RuntimePaths, source: _SourceSnapshot) -> _ProposalSnapshot:
    entries = _directory_entries(paths.proposal_root, "fixed Coach proposal root")
    if source.disposition == POSITIVE_DISPOSITION:
        if len(entries) != 1 or entries[0].name != PROPOSAL_NAME:
            raise HypothesisDesignRunnerBlocked(
                "positive interpretation requires exactly one fixed Coach proposal"
            )
        raw = _entry_file(entries[0], "fixed Coach proposal").read_bytes()
        envelope = validate_coach_proposal_envelope_bytes(raw, source.raw)
        proposal = envelope["coach_proposal"]
        return _ProposalSnapshot(raw, tuple(sorted(proposal.items())))
    if source.disposition in NON_POSITIVE_DISPOSITIONS:
        if entries:
            raise HypothesisDesignRunnerBlocked(
                "non-positive interpretation requires an empty proposal root"
            )
        return _ProposalSnapshot(None, None)
    raise HypothesisDesignRunnerBlocked("source disposition is unsupported")


def _scan_output(root: Path) -> Path | None:
    entries = _directory_entries(root, "fixed hypothesis design outbox")
    if not entries:
        return None
    if len(entries) != 1 or entries[0].name != OUTPUT_RESULT_NAME:
        raise HypothesisDesignRunnerBlocked("fixed hypothesis design outbox changed")
    return _entry_file(entries[0], "hypothesis design output")


def _validate_result_branch(
    result: dict[str, Any],
    source: _SourceSnapshot,
    proposal: _ProposalSnapshot,
) -> str | None:
    if source.disposition == POSITIVE_DISPOSITION:
        expected = proposal.proposal()
        design = result["hypothesis_design"]
        if not isinstance(design, dict) or expected is None:
            raise HypothesisDesignRunnerBlocked("positive result lost its proposal")
        if {name: design[name] for name in PROPOSAL_FIELDS} != expected:
            raise HypothesisDesignRunnerBlocked("design result changed the Coach proposal")
        return design["design_id"]
    if result["hypothesis_design"] is not None or proposal.proposal() is not None:
        raise HypothesisDesignRunnerBlocked("closed result contains a proposal")
    return None


def _create_output_once(
    root: Path,
    raw: bytes,
    interpretation_raw: bytes,
) -> tuple[Path, str]:
    validate_hypothesis_design_result_bytes(raw, interpretation_raw)
    target = _scan_output(root)
    if target is not None:
        existing = target.read_bytes()
        if existing != raw:
            raise HypothesisDesignRunnerBlocked("conflicting hypothesis design output exists")
        validate_hypothesis_design_result_bytes(existing, interpretation_raw)
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
            raise HypothesisDesignRunnerBlocked(
                "conflicting hypothesis design output won the create race"
            )
        validate_hypothesis_design_result_bytes(
            existing_target.read_bytes(), interpretation_raw
        )
        return existing_target, "IDEMPOTENT_IDENTICAL"
    created = _scan_output(root)
    if created is None or created.read_bytes() != raw:
        raise HypothesisDesignRunnerBlocked("created hypothesis design bytes changed")
    validate_hypothesis_design_result_bytes(created.read_bytes(), interpretation_raw)
    return created, "CREATED"


def run_hypothesis_design(
    paths: RuntimePaths,
    *,
    design_builder: Callable[[bytes, dict[str, Any] | None], bytes] | None = None,
) -> dict[str, Any]:
    _validate_separate_roots(paths)
    before_task = _validate_runner_task(paths)
    before_source = _validate_source(paths)
    before_proposal = _validate_proposal(paths, before_source)
    builder = build_hypothesis_design_result_bytes if design_builder is None else design_builder
    try:
        result_raw = builder(before_source.raw, before_proposal.proposal())
    except Exception as error:
        raise HypothesisDesignRunnerBlocked(
            f"design builder failed: {type(error).__name__}: {error}"
        ) from error
    if not isinstance(result_raw, bytes):
        raise HypothesisDesignRunnerBlocked("design builder must return bytes")
    try:
        result = validate_hypothesis_design_result_bytes(result_raw, before_source.raw)
    except ValueError as error:
        raise HypothesisDesignRunnerBlocked(str(error)) from error
    design_id = _validate_result_branch(result, before_source, before_proposal)

    after_task = _validate_runner_task(paths)
    after_source = _validate_source(paths)
    after_proposal = _validate_proposal(paths, after_source)
    if (
        before_task != after_task
        or before_source != after_source
        or before_proposal != after_proposal
    ):
        raise HypothesisDesignRunnerBlocked("inputs changed during hypothesis design")

    output_path, write_status = _create_output_once(
        paths.output_root,
        result_raw,
        after_source.raw,
    )
    output_raw = output_path.read_bytes()
    output = validate_hypothesis_design_result_bytes(output_raw, after_source.raw)
    output_design_id = _validate_result_branch(output, after_source, after_proposal)
    if output_design_id != design_id:
        raise HypothesisDesignRunnerBlocked("created design identity changed")
    return {
        "status": write_status,
        "result": OUTPUT_RESULT_NAME,
        "sha256": _sha256(output_raw),
        "source_disposition": output["source_disposition"],
        "design_status": output["status"],
        "design_id": design_id,
    }


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_hypothesis_design(PRODUCTION_PATHS)
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
