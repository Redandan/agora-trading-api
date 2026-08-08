from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
from typing import Any, Callable, Iterable

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_interpretation import (
    RESULT_TYPE as INTERPRETATION_RESULT_TYPE,
    TIER_ORDER,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_positive_route_hypothesis_design import (
    NON_POSITIVE_DISPOSITIONS,
    POSITIVE_DISPOSITION,
    PROPOSAL_FIELDS,
    ROUTE_CONTRACT_SHA256,
    ROUTE_ID,
    build_positive_route_hypothesis_design_result_bytes,
    validate_positive_route_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    canonical_json_bytes,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-positive-route-design-runner.v3.json"
)
RUNNER_TASK_ID = "local-node-microstructure-positive-route-design-runner-v3"
RUNNER_TASK_SHA256 = (
    "63f38fe038e0795fc970dfe2d1557a481696eb492132d3af4594ab2f0e60a153"
)
SOURCE_COMMIT = "5e033de21d2b1c8fa1a6e21aa7aa87841448f568"
SOURCE_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-v3-interpretation-runner-v2"
)
SOURCE_RESULT_NAME = "interpretation-result.json"
PROPOSAL_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox/"
    "local-node-microstructure-positive-route-design-runner-v3"
)
PROPOSAL_NAME = "coach-proposal.json"
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox/"
    "local-node-microstructure-positive-route-design-runner-v3"
)
OUTPUT_RESULT_NAME = "positive-route-hypothesis-design-result.json"

PROPOSAL_TYPE = "OKX_MICROSTRUCTURE_POSITIVE_ROUTE_COACH_HYPOTHESIS_PROPOSAL_V2"
PROPOSAL_SCHEMA_RELATIVE = (
    "research_pipeline/"
    "microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json"
)
PROPOSAL_SCHEMA_SHA256 = (
    "827f7b6f48d881ca2811a255d2efd4e8b936261a775e1b5ef5214936c4769262"
)
PROPOSAL_SCHEMA_PATH = Path(__file__).with_name(
    "microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json"
)
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)

IMPLEMENTATION_FILES = (
    PROPOSAL_SCHEMA_RELATIVE,
    "research_pipeline/microstructure_positive_route_hypothesis_design_runner.py",
    "research_pipeline/tests/test_microstructure_positive_route_hypothesis_design_runner.py",
    "docs/okx-microstructure-positive-route-hypothesis-design-runner-v3.md",
)
EXPECTED_REPOSITORY_INPUTS = {
    "AGENTS.md": "acae1925d4f2247dc8ffbd60efd7d9bd86844ef6ce9bf8f3b5bdf57906177e50",
    ".agents/skills/autonomous-trading-research/SKILL.md": "7627a7b10d3a4d70ec6e1453a6c1f75fcf22249faaa6283855bc24dd82e6be65",
    "docs/autonomous-research-charter.md": "2465198fea55728cd087d244827b37bf6196723334f05c54db8f076b84bd5334",
    "docs/local-codex-research-node-v1.md": "7de32f0e2ed160cbba8553230c790e5a002dae2367c278aa4ed4630ada6f56bc",
    "docs/strategy-driven-minimal-runtime.md": "70fb3767d14cefa0a5093f24fc001be2697a760e729a60a6c860ba7f52a4cd64",
    "research_pipeline/local_node.py": "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed",
    "research_pipeline/policy.v3.json": "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6",
    "research_pipeline/microstructure_source_contract.py": "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee",
    "research_pipeline/microstructure_handoff_runner.py": "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc",
    "research_pipeline/examples/local-research-task.microstructure-v3-interpretation-runner.v2.json": "0607f48c3542dbbb2f662f401998904c483f6d60e453c7ba6fea9a9eebf9155f",
    "research_pipeline/microstructure_interpretation_runner.py": "5d1de7e1e8006ca066fb857c55ad834b24bbe709a10d25ace5ea16a13dc0c04f",
    "research_pipeline/microstructure_interpretation.py": "3892ae7a14161de3505bcb31de4b26ea897f52bc20a54db642b7b5706c520e39",
    "research_pipeline/microstructure-interpretation-result.v1.schema.json": "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564",
    "research_pipeline/okx-microstructure-forward-interpretation-contract.v1.json": "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509",
    "research_pipeline/examples/local-research-task.microstructure-positive-route-design-bridge.v1.json": "4d99360b0faa034498eb85f4f4d6f2e2edf3cc91784942bd3376c54e53e90504",
    "research_pipeline/okx-microstructure-positive-route-hypothesis-design-contract.v2.json": "802fda49c9b0a3d6a32b3e8e6d66dc6fa25312eb4ea3f0deddb715216b489f41",
    "research_pipeline/microstructure-positive-route-hypothesis-design-result.v2.schema.json": "1c829c05288d7a4d5a925cad1b1738eb5fe156f4f6ed2babc423efd4b4a9cb94",
    "research_pipeline/microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json": "827f7b6f48d881ca2811a255d2efd4e8b936261a775e1b5ef5214936c4769262",
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json": "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e",
    "research_pipeline/microstructure_positive_route_hypothesis_design.py": "f01fdae107d362bb53d1f9bec0bec7b2f24c6c905ef6eed83e8693cbd572fcfb",
    "research_pipeline/tests/test_microstructure_positive_route_hypothesis_design.py": "18604a518bd36444bf08b3a7ec7e7bd0de357ff109e1b582904985c8d09e0bf2",
    "docs/okx-microstructure-positive-route-hypothesis-design-v2.md": "fcef768dab696e2399a05f29b174488b91bc49ff1f409ef577a31c87ae1f6e61",
    "research_pipeline/microstructure-coach-hypothesis-proposal.v1.schema.json": "c2a1db83aa62c92fd86d2c6fc3b1516829d282f81c683d9d082375600de28bb0",
    "research_pipeline/examples/local-research-task.microstructure-v3-hypothesis-design-runner.v2.json": "7224171c14252fd0b6e0e0c14e0a30820fdc614fcf47e107b1836af3910f9114",
    "research_pipeline/microstructure_hypothesis_design_runner.py": "8b59e8cbabc5bfe8bff7ff7d19bc8cf9b677583eeb0203571be23f25bfebfc95",
    "research_pipeline/tests/test_microstructure_hypothesis_design_runner.py": "cc41cbb3263b533c199cafe6d19c145fa9e55bd635e1d48b20c337f12d2d9723",
    "docs/okx-microstructure-hypothesis-design-runner-v2.md": "9c62ff0427428752037aaf77b17f0e282883bff3ff32c471e882b77741176c5a",
    "research_pipeline/examples/local-research-task.microstructure-positive-route-design-runner.v2.json": "7f5461a5354596a5bdaec57074eafadb0993f2cc8e3c6e997c9275b219345a5a",
    "docs/okx-microstructure-positive-route-hypothesis-design-runner-v2.md": "964a1d7e193100444c9b1a009571590647e015ef5ab1f0d71865eee7920b6689",
    "docs/okx-microstructure-positive-route-hypothesis-design-runner-v3.md": "554593065b9b7cfa92dade274cc08f8280f969e077fd90ea1482de04695608e6",
}
EXPECTED_ALLOWED_ACTIONS = {
    "READ_FROZEN_REPOSITORY_CONTRACTS",
    "VERIFY_ALL_NON_NULL_INPUT_HASHES",
    "ADD_VERSIONED_POSITIVE_ROUTE_RUNNER_SELF_TASK",
    "MODIFY_FIXED_ROOT_POSITIVE_ROUTE_DESIGN_RUNNER",
    "MODIFY_FOCUSED_OFFLINE_POSITIVE_ROUTE_RUNNER_TESTS",
    "ADD_VERSIONED_POSITIVE_ROUTE_RUNNER_DOCUMENTATION",
    "BUILD_SYNTHETIC_PROPOSAL_ENVELOPES",
    "VALIDATE_SOURCE_INTERPRETATION",
    "VALIDATE_SOURCE_BOUND_PROPOSAL",
    "CALL_EXISTING_PURE_V2_DESIGN_BUILDER",
    "REVALIDATE_ALL_INPUTS_BEFORE_OUTPUT",
    "CREATE_LOCAL_DESIGN_RESULT_ONCE_IN_TEMPORARY_FIXTURES",
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
    "LOCAL_INBOX_OR_OUTBOX_ACCESS",
    "SERVER_NETWORK_OR_SSH_ACCESS",
    "CLOUD_SCHEDULE_CREATE_UPDATE_OR_DELETE",
    "SOURCE_INTERPRETATION_WRITE_DELETE_OR_REPAIR",
    "COACH_THESIS_INVENTION_ENRICHMENT_OR_SELECTION",
    "TIER_ROUTE_OVERRIDE_FALLBACK_OR_SWITCH",
    "HYPOTHESIS_OR_CANDIDATE_REGISTRATION",
    "SOURCE_MANIFEST_ADAPTER_OR_OOS_IMPLEMENTATION",
    "SCIENTIFIC_CONTRACT_SCHEMA_THRESHOLD_TIER_HORIZON_OR_GATE_CHANGE",
    "HISTORICAL_TASK_DOCUMENTATION_CONTRACT_SCHEMA_OR_PURE_BUILDER_EDIT",
    "RESEARCH_STATE_WRITE_OR_UNLISTED_ARTIFACT_ACCESS",
    "JAVA_MAVEN_SPRING_OR_EXTERNAL_SUBPROCESS_EXECUTION",
    "GIT_STAGE_COMMIT_PUSH_RESET_OR_CLEAN",
    "UNLISTED_FILE_EDIT",
}
PROPOSAL_SAFETY_ASSERTIONS = {
    "hypothesis_registration_authorized": False,
    "manifest_creation_authorized": False,
    "adapter_implementation_authorized": False,
    "source_instantiation_authorized": False,
    "candidate_registration_authorized": False,
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
    "route_selection",
    "coach_proposal",
    "safety_assertions",
    "seal",
}


class PositiveRouteHypothesisDesignRunnerBlocked(ValueError):
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


def _has_reparse_point(info: os.stat_result) -> bool:
    flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return bool(getattr(info, "st_file_attributes", 0) & flag)


def _require_type(path: Path, *, directory: bool, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} is unavailable"
        ) from error
    expected = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if path.is_symlink() or _has_reparse_point(info) or not expected:
        kind = "directory" if directory else "file"
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} must be a regular non-link {kind}"
        )
    return info


def _repository_path(root: Path, relative_name: str) -> Path:
    relative = PurePosixPath(relative_name)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise PositiveRouteHypothesisDesignRunnerBlocked("repository path is unsafe")
    root = _absolute(root)
    current = root
    _require_type(current, directory=True, label="repository root")
    for part in relative.parts[:-1]:
        current = current / part
        _require_type(current, directory=True, label=f"repository directory {part}")
    target = root.joinpath(*relative.parts)
    try:
        if os.path.commonpath((str(root), str(_absolute(target)))) != str(root):
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "repository path escaped its root"
            )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "repository path escaped its root"
        ) from error
    return target


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
        raise PositiveRouteHypothesisDesignRunnerBlocked("all fixed roots must differ")
    for index, left in enumerate(normalized):
        for right in normalized[index + 1 :]:
            try:
                common = os.path.commonpath((left, right))
            except ValueError:
                continue
            if common in {left, right}:
                raise PositiveRouteHypothesisDesignRunnerBlocked(
                    "fixed roots must not overlap"
                )


def _read_regular(path: Path, label: str) -> bytes:
    _require_type(path, directory=False, label=label)
    try:
        return path.read_bytes()
    except OSError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} is inaccessible"
        ) from error


def _validate_runner_task(paths: RuntimePaths) -> _TaskSnapshot:
    repository_root = _absolute(paths.repository_root)
    task_path = _repository_path(repository_root, RUNNER_TASK_RELATIVE)
    task_raw = _read_regular(task_path, "fixed positive route runner task")
    if _sha256(task_raw) != RUNNER_TASK_SHA256:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "fixed positive route runner task bytes changed"
        )
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(task_raw, "fixed positive route runner task")
        )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
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
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "fixed positive route runner task authority changed"
        )
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "fixed repository input contract changed"
        )

    repository_hashes: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        actual_hash = _sha256(
            _read_regular(
                _repository_path(repository_root, relative_name),
                f"repository input {relative_name}",
            )
        )
        if actual_hash != expected_hash:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                f"repository input hash changed: {relative_name}"
            )
        repository_hashes.append((relative_name, actual_hash))

    implementation_hashes = tuple(
        (
            relative_name,
            _sha256(
                _read_regular(
                    _repository_path(repository_root, relative_name),
                    f"implementation file {relative_name}",
                )
            ),
        )
        for relative_name in IMPLEMENTATION_FILES
    )
    return _TaskSnapshot(
        RUNNER_TASK_SHA256,
        tuple(repository_hashes),
        implementation_hashes,
    )


def _directory_entries(root: Path, label: str) -> list[os.DirEntry[str]]:
    root = _absolute(root)
    _require_type(root, directory=True, label=label)
    try:
        return list(os.scandir(root))
    except OSError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} is inaccessible"
        ) from error


def _entry_file(entry: os.DirEntry[str], label: str) -> Path:
    try:
        info = entry.stat(follow_symlinks=False)
    except OSError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} is inaccessible"
        ) from error
    if entry.is_symlink() or _has_reparse_point(info) or not stat.S_ISREG(info.st_mode):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"{label} must be a regular non-link file"
        )
    return Path(entry.path)


def _validate_source(paths: RuntimePaths) -> _SourceSnapshot:
    entries = _directory_entries(paths.source_root, "fixed interpretation source root")
    if len(entries) != 1 or entries[0].name != SOURCE_RESULT_NAME:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "fixed interpretation source inventory changed"
        )
    raw = _entry_file(entries[0], "fixed interpretation result").read_bytes()
    try:
        result = validate_interpretation_result_bytes(raw)
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
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


def _route_binding(selected_tier: str | None) -> dict[str, Any]:
    if selected_tier not in TIER_ORDER:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive interpretation has no selected tier"
        )
    return {
        "route_id": ROUTE_ID,
        "route_contract_sha256": ROUTE_CONTRACT_SHA256,
        "priority": "SOLE_PRIMARY",
        "source_selected_tier": selected_tier,
        "maximum_routes": 1,
        "maximum_designs": 1,
        "maximum_eventual_candidate_variants": 1,
        "caller_override_authorized": False,
        "multiple_routes_authorized": False,
        "dra_fallback_authorized": False,
        "route_switch_after_design_outcome_authorized": False,
        "route_switch_after_validation_outcome_authorized": False,
        "route_switch_after_oos_outcome_authorized": False,
    }


def _payload_sha256(value: dict[str, Any]) -> str:
    return _sha256(canonical_json_bytes(value, exclude_key="seal"))


def _validate_proposal_schema_file() -> None:
    raw = _read_regular(PROPOSAL_SCHEMA_PATH, "positive route Coach proposal schema")
    if _sha256(raw) != PROPOSAL_SCHEMA_SHA256:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal schema hash changed"
        )
    try:
        schema = load_json_bytes_strict(raw, "positive route Coach proposal schema")
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
    if (
        schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
        or schema.get("$id")
        != "urn:agora-research:microstructure-positive-route-coach-hypothesis-proposal:v2"
        or schema.get("additionalProperties") is not False
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal schema identity changed"
        )


def _validated_inner_proposal(
    interpretation_raw: bytes,
    proposal: Any,
) -> dict[str, str]:
    try:
        design_raw = build_positive_route_hypothesis_design_result_bytes(
            interpretation_raw,
            proposal,
        )
        design_result = validate_positive_route_hypothesis_design_result_bytes(
            design_raw,
            interpretation_raw,
        )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
    design = design_result["hypothesis_design"]
    if not isinstance(design, dict):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "Coach proposal did not produce a design"
        )
    validated = {name: design[name] for name in PROPOSAL_FIELDS}
    if proposal != validated:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "Coach proposal content changed"
        )
    return validated


def build_positive_route_coach_proposal_envelope_bytes(
    interpretation_raw: bytes,
    proposal: dict[str, Any],
) -> bytes:
    if not isinstance(interpretation_raw, bytes) or not isinstance(proposal, dict):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "canonical interpretation bytes and one Coach proposal are required"
        )
    _validate_proposal_schema_file()
    try:
        interpretation = validate_interpretation_result_bytes(interpretation_raw)
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
    if (
        interpretation["disposition"] != POSITIVE_DISPOSITION
        or interpretation["screen"]["selected_tier"] not in TIER_ORDER
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
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
        "schema_version": "2",
        "proposal_type": PROPOSAL_TYPE,
        "authorization": AUTHORIZATION,
        "source_interpretation": _source_binding(source),
        "route_selection": _route_binding(source.selected_tier),
        "coach_proposal": validated_proposal,
        "safety_assertions": dict(PROPOSAL_SAFETY_ASSERTIONS),
    }
    envelope["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(envelope),
        "canonicalization": CANONICALIZATION,
    }
    raw = canonical_json_bytes(envelope)
    validate_positive_route_coach_proposal_envelope_bytes(raw, interpretation_raw)
    return raw


def validate_positive_route_coach_proposal_envelope_bytes(
    raw: bytes,
    interpretation_raw: bytes,
) -> dict[str, Any]:
    if not isinstance(raw, bytes) or not isinstance(interpretation_raw, bytes):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "proposal and interpretation must be canonical bytes"
        )
    _validate_proposal_schema_file()
    try:
        interpretation = validate_interpretation_result_bytes(interpretation_raw)
        envelope = load_json_bytes_strict(raw, "positive route Coach proposal envelope")
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
    if raw != canonical_json_bytes(envelope):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal bytes must be canonical"
        )
    if set(envelope) != _PROPOSAL_KEYS:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal envelope keys changed"
        )
    if (
        interpretation["disposition"] != POSITIVE_DISPOSITION
        or interpretation["screen"]["selected_tier"] not in TIER_ORDER
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
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
        envelope["schema_version"] != "2"
        or envelope["proposal_type"] != PROPOSAL_TYPE
        or envelope["authorization"] != AUTHORIZATION
        or envelope["source_interpretation"] != _source_binding(source)
        or envelope["route_selection"] != _route_binding(source.selected_tier)
        or envelope["safety_assertions"] != PROPOSAL_SAFETY_ASSERTIONS
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal binding changed"
        )
    _validated_inner_proposal(interpretation_raw, envelope["coach_proposal"])
    if envelope["seal"] != {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(envelope),
        "canonicalization": CANONICALIZATION,
    }:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "positive route Coach proposal seal changed"
        )
    return envelope


def _validate_proposal(
    paths: RuntimePaths,
    source: _SourceSnapshot,
) -> _ProposalSnapshot:
    entries = _directory_entries(paths.proposal_root, "fixed Coach proposal root")
    if source.disposition == POSITIVE_DISPOSITION:
        if len(entries) != 1 or entries[0].name != PROPOSAL_NAME:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "positive interpretation requires exactly one fixed Coach proposal"
            )
        raw = _entry_file(entries[0], "fixed Coach proposal").read_bytes()
        envelope = validate_positive_route_coach_proposal_envelope_bytes(
            raw,
            source.raw,
        )
        proposal = envelope["coach_proposal"]
        return _ProposalSnapshot(raw, tuple(sorted(proposal.items())))
    if source.disposition in NON_POSITIVE_DISPOSITIONS:
        if entries:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "non-positive interpretation requires an empty proposal root"
            )
        return _ProposalSnapshot(None, None)
    raise PositiveRouteHypothesisDesignRunnerBlocked(
        "source disposition is unsupported"
    )


def _scan_output(root: Path) -> Path | None:
    entries = _directory_entries(root, "fixed positive route design outbox")
    if not entries:
        return None
    if len(entries) != 1 or entries[0].name != OUTPUT_RESULT_NAME:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "fixed positive route design outbox changed"
        )
    return _entry_file(entries[0], "positive route hypothesis design output")


def _validate_result_branch(
    result: dict[str, Any],
    source: _SourceSnapshot,
    proposal: _ProposalSnapshot,
) -> tuple[str | None, str | None]:
    if source.disposition == POSITIVE_DISPOSITION:
        expected = proposal.proposal()
        design = result["hypothesis_design"]
        route = result["route_selection"]
        if not isinstance(design, dict) or not isinstance(route, dict) or expected is None:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "positive result lost its route or proposal"
            )
        if {name: design[name] for name in PROPOSAL_FIELDS} != expected:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "design result changed the Coach proposal"
            )
        if route != _route_binding(source.selected_tier):
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "design result changed the sole-primary route"
            )
        return route["route_id"], design["design_id"]
    if (
        result["route_selection"] is not None
        or result["hypothesis_design"] is not None
        or proposal.proposal() is not None
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "closed result contains a route or proposal"
        )
    return None, None


def _create_output_once(
    root: Path,
    raw: bytes,
    interpretation_raw: bytes,
) -> tuple[Path, str]:
    validate_positive_route_hypothesis_design_result_bytes(raw, interpretation_raw)
    target = _scan_output(root)
    if target is not None:
        existing = target.read_bytes()
        if existing != raw:
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "conflicting positive route design output exists"
            )
        validate_positive_route_hypothesis_design_result_bytes(
            existing,
            interpretation_raw,
        )
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
            raise PositiveRouteHypothesisDesignRunnerBlocked(
                "conflicting positive route design output won the create race"
            )
        validate_positive_route_hypothesis_design_result_bytes(
            existing_target.read_bytes(),
            interpretation_raw,
        )
        return existing_target, "IDEMPOTENT_IDENTICAL"
    created = _scan_output(root)
    if created is None or created.read_bytes() != raw:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "created positive route design bytes changed"
        )
    validate_positive_route_hypothesis_design_result_bytes(
        created.read_bytes(),
        interpretation_raw,
    )
    return created, "CREATED"


def _run_positive_route_hypothesis_design(
    paths: RuntimePaths,
    design_builder: Callable[[bytes, dict[str, Any] | None], bytes],
) -> dict[str, Any]:
    _validate_separate_roots(paths)
    before_task = _validate_runner_task(paths)
    before_source = _validate_source(paths)
    before_proposal = _validate_proposal(paths, before_source)
    try:
        result_raw = design_builder(before_source.raw, before_proposal.proposal())
    except Exception as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            f"design builder failed: {type(error).__name__}: {error}"
        ) from error
    if not isinstance(result_raw, bytes):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "design builder must return bytes"
        )
    try:
        result = validate_positive_route_hypothesis_design_result_bytes(
            result_raw,
            before_source.raw,
        )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerBlocked(str(error)) from error
    route_id, design_id = _validate_result_branch(
        result,
        before_source,
        before_proposal,
    )

    after_task = _validate_runner_task(paths)
    after_source = _validate_source(paths)
    after_proposal = _validate_proposal(paths, after_source)
    if (
        before_task != after_task
        or before_source != after_source
        or before_proposal != after_proposal
    ):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "inputs changed during positive route hypothesis design"
        )

    output_path, write_status = _create_output_once(
        paths.output_root,
        result_raw,
        after_source.raw,
    )
    output_raw = output_path.read_bytes()
    output = validate_positive_route_hypothesis_design_result_bytes(
        output_raw,
        after_source.raw,
    )
    output_route_id, output_design_id = _validate_result_branch(
        output,
        after_source,
        after_proposal,
    )
    if (output_route_id, output_design_id) != (route_id, design_id):
        raise PositiveRouteHypothesisDesignRunnerBlocked(
            "created route or design identity changed"
        )
    return {
        "status": write_status,
        "result": OUTPUT_RESULT_NAME,
        "sha256": _sha256(output_raw),
        "source_disposition": output["source_disposition"],
        "design_status": output["status"],
        "route_id": route_id,
        "design_id": design_id,
    }


def run_positive_route_hypothesis_design(paths: RuntimePaths) -> dict[str, Any]:
    return _run_positive_route_hypothesis_design(
        paths,
        build_positive_route_hypothesis_design_result_bytes,
    )


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_positive_route_hypothesis_design(PRODUCTION_PATHS)
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
