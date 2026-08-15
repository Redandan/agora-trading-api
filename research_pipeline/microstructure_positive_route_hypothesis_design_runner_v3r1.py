from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
import sys
from typing import Any, Callable, Iterable

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_positive_route_hypothesis_design import (
    build_positive_route_hypothesis_design_result_bytes,
    validate_positive_route_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_positive_route_hypothesis_design_runner import (
    OUTPUT_RESULT_NAME,
    RuntimePaths,
    _absolute,
    _create_output_once,
    _read_regular,
    _repository_path,
    _require_type,
    _sha256,
    _validate_proposal,
    _validate_result_branch,
    _validate_separate_roots,
    _validate_source,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
RUNNER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3r1-positive-route-design-runner.v1.json"
)
RUNNER_TASK_ID = "local-node-microstructure-v3r1-positive-route-design-runner-v1"
RUNNER_TASK_SHA256 = (
    "a9dd90ffa3cc8252b46942d0b454d025865075cb29599522cf3af189e33101fe"
)
SOURCE_RUNNER_TASK_ID = "local-node-microstructure-v3r1-interpretation-runner-v1"
SOURCE_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox"
) / SOURCE_RUNNER_TASK_ID
PROPOSAL_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/inbox"
) / RUNNER_TASK_ID
OUTPUT_ROOT = Path(
    "C:/Users/Redan/.codex/local-research-node/outbox"
) / RUNNER_TASK_ID

IMPLEMENTATION_FILES = (
    "research_pipeline/microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json",
    "research_pipeline/microstructure_positive_route_hypothesis_design_runner_v3r1.py",
)
EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/policy.v3.json": (
        "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6"
    ),
    "research_pipeline/microstructure_source_contract.py": (
        "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee"
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
    "research_pipeline/microstructure_interpretation_runner_v3r1.py": (
        "dfbc66dd2bbc39843481f2c9f006013a21f0858b546cacdd58032d5a1d268c1c"
    ),
    "research_pipeline/examples/local-research-task.microstructure-v3r1-interpretation-runner.v1.json": (
        "bf022e30e429c3859c80aead880fc71f6e84a3c3598421ddf3aa289127334a77"
    ),
    "research_pipeline/microstructure_positive_route_hypothesis_design.py": (
        "f01fdae107d362bb53d1f9bec0bec7b2f24c6c905ef6eed83e8693cbd572fcfb"
    ),
    "research_pipeline/okx-microstructure-positive-route-hypothesis-design-contract.v2.json": (
        "802fda49c9b0a3d6a32b3e8e6d66dc6fa25312eb4ea3f0deddb715216b489f41"
    ),
    "research_pipeline/microstructure-positive-route-hypothesis-design-result.v2.schema.json": (
        "1c829c05288d7a4d5a925cad1b1738eb5fe156f4f6ed2babc423efd4b4a9cb94"
    ),
    "research_pipeline/microstructure-positive-route-coach-hypothesis-proposal.v2.schema.json": (
        "827f7b6f48d881ca2811a255d2efd4e8b936261a775e1b5ef5214936c4769262"
    ),
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json": (
        "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e"
    ),
    "research_pipeline/microstructure_positive_route_hypothesis_design_runner.py": (
        "c241972b92c5c4cd8347f5366f3931f1b6e27a96b092d5cdef4f4e8a9ea34f56"
    ),
}
EXPECTED_ALLOWED_ACTIONS = {
    "READ_FROZEN_REPOSITORY_CONTRACTS",
    "VERIFY_ALL_NON_NULL_INPUT_HASHES",
    "ADD_FIXED_V3R1_POSITIVE_ROUTE_RUNNER",
    "ADD_V3R1_POSITIVE_ROUTE_RUNNER_SELF_TASK",
    "ADD_FOCUSED_SYNTHETIC_TESTS",
    "ADD_V3R1_POSITIVE_ROUTE_RUNNER_DOCUMENTATION",
    "REUSE_FROZEN_V3R1_INTERPRETATION_CONTRACTS",
    "REUSE_FROZEN_POSITIVE_ROUTE_DESIGN_CONTRACTS",
    "REUSE_SOURCE_BOUND_POSITIVE_ROUTE_COACH_PROPOSAL",
    "REUSE_STANDALONE_MATCHED_CONTROL_ROUTE",
    "VALIDATE_SOURCE_INTERPRETATION",
    "CALL_EXISTING_PURE_POSITIVE_ROUTE_DESIGN_BUILDER",
    "CREATE_LOCAL_DESIGN_RESULT_ONCE_IN_TEMPORARY_FIXTURES",
    "RUN_LOCAL_TASK_VALIDATION",
    "RUN_FOCUSED_OFFLINE_PYTHON_TESTS",
    "RUN_PYTHON_COMPILE_CHECK",
    "RUN_GIT_DIFF_CHECK",
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
    "SERVER_NETWORK_OR_SSH_EXECUTION",
    "CLOUD_SCHEDULE_CREATE_UPDATE_OR_DELETE",
    "REAL_FIXED_ROOT_RUNNER_EXECUTION",
    "FUTURE_EVIDENCE_OR_OUTCOME_ACCESS",
    "CALLER_SELECTED_SOURCE_PROPOSAL_OUTPUT_TASK_TIER_ROUTE_OR_MECHANISM",
    "SOURCE_INTERPRETATION_WRITE_DELETE_REPAIR_OR_CLEANUP",
    "PROPOSAL_OR_OUTPUT_OVERWRITE_DELETE_REPAIR_OR_CLEANUP",
    "COACH_THESIS_INVENTION_FROM_ABSENT_EVIDENCE",
    "HYPOTHESIS_MANIFEST_CANDIDATE_OR_EXPERIMENT_REGISTRATION",
    "ECONOMIC_EVALUATOR_ADAPTER_OR_OOS_EXECUTION",
    "SCIENTIFIC_CONTRACT_SCHEMA_THRESHOLD_TIER_ROUTE_HORIZON_OR_GATE_CHANGE",
    "HISTORICAL_V3_RUNNER_TASK_DOCUMENTATION_OR_ROOT_CHANGE",
    "ACTIVE_V3R1_CHAIN_OR_GENERIC_DESIGN_RUNNER_CHANGE",
    "RESEARCH_STATE_WRITE_OR_UNLISTED_ARTIFACT_ACCESS",
    "JAVA_MAVEN_SPRING_OR_TRADING_EXECUTION",
    "GIT_STAGE_COMMIT_PUSH_RESET_OR_CLEAN",
    "OTHER_FILE_EDIT",
}


class PositiveRouteHypothesisDesignRunnerV3R1Blocked(ValueError):
    pass


@dataclass(frozen=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]
    implementation_hashes: tuple[tuple[str, str], ...]


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    source_root=SOURCE_ROOT,
    proposal_root=PROPOSAL_ROOT,
    output_root=OUTPUT_ROOT,
)


def _validate_runner_task(paths: RuntimePaths) -> _TaskSnapshot:
    repository_root = _absolute(paths.repository_root)
    _require_type(repository_root, directory=True, label="repository root")
    task_path = _repository_path(repository_root, RUNNER_TASK_RELATIVE)
    task_raw = _read_regular(task_path, "fixed V3R1 positive-route runner task")
    if _sha256(task_raw) != RUNNER_TASK_SHA256:
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "fixed V3R1 positive-route runner task bytes changed"
        )
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(
                task_raw,
                "fixed V3R1 positive-route runner task",
            )
        )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(str(error)) from error
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
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "fixed V3R1 positive-route runner authority changed"
        )
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "fixed V3R1 positive-route repository inputs changed"
        )

    repository_hashes: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(repository_root, relative_name)
        actual_hash = _sha256(
            _read_regular(target, f"repository input {relative_name}")
        )
        if actual_hash != expected_hash:
            raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
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


def run_positive_route_hypothesis_design_v3r1(
    paths: RuntimePaths,
    *,
    design_builder: Callable[[bytes, dict[str, Any] | None], bytes] | None = None,
) -> dict[str, Any]:
    _validate_separate_roots(paths)
    before_task = _validate_runner_task(paths)
    before_source = _validate_source(paths)
    before_proposal = _validate_proposal(paths, before_source)
    builder = (
        build_positive_route_hypothesis_design_result_bytes
        if design_builder is None
        else design_builder
    )
    try:
        result_raw = builder(before_source.raw, before_proposal.proposal())
    except Exception as error:
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            f"design builder failed: {type(error).__name__}: {error}"
        ) from error
    if not isinstance(result_raw, bytes):
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "design builder must return bytes"
        )
    try:
        result = validate_positive_route_hypothesis_design_result_bytes(
            result_raw,
            before_source.raw,
        )
    except ValueError as error:
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(str(error)) from error
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
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "inputs changed during V3R1 positive-route hypothesis design"
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
        raise PositiveRouteHypothesisDesignRunnerV3R1Blocked(
            "created V3R1 route or design identity changed"
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


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = run_positive_route_hypothesis_design_v3r1(PRODUCTION_PATHS)
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
