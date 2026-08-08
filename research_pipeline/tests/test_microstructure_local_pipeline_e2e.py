from __future__ import annotations

import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_handoff import validate_handoff_result_bytes
from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_TASK_SHA256,
    RESULT_NAME as HANDOFF_RESULT_NAME,
    RuntimePaths as HandoffRuntimePaths,
    _validate_fixed_package,
    run_handoff,
)
from research_pipeline.microstructure_hypothesis_design import (
    MISSING_PROOF,
    SAFETY_ASSERTIONS,
    validate_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_hypothesis_design_runner import (
    EXPECTED_REPOSITORY_INPUTS as DESIGN_REPOSITORY_INPUTS,
    IMPLEMENTATION_FILES as DESIGN_IMPLEMENTATION_FILES,
    OUTPUT_RESULT_NAME as DESIGN_RESULT_NAME,
    PROPOSAL_NAME,
    REPOSITORY_ROOT as DESIGN_REPOSITORY_ROOT,
    RUNNER_TASK_ID as DESIGN_TASK_ID,
    RUNNER_TASK_RELATIVE as DESIGN_TASK_RELATIVE,
    RuntimePaths as DesignRuntimePaths,
    build_coach_proposal_envelope_bytes,
    run_hypothesis_design,
    validate_coach_proposal_envelope_bytes,
)
from research_pipeline.microstructure_interpretation import (
    TIER_ORDER,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_interpretation_runner import (
    OUTPUT_RESULT_NAME as INTERPRETATION_RESULT_NAME,
    run_interpretation,
)
from research_pipeline.microstructure_positive_route_hypothesis_design import (
    MISSING_PROOF as POSITIVE_ROUTE_MISSING_PROOF,
    ROUTE_CONTRACT_SHA256,
    ROUTE_ID,
    SAFETY_ASSERTIONS as POSITIVE_ROUTE_SAFETY_ASSERTIONS,
    validate_positive_route_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_positive_route_hypothesis_design_runner import (
    EXPECTED_REPOSITORY_INPUTS as POSITIVE_ROUTE_REPOSITORY_INPUTS,
    IMPLEMENTATION_FILES as POSITIVE_ROUTE_IMPLEMENTATION_FILES,
    OUTPUT_RESULT_NAME as POSITIVE_ROUTE_RESULT_NAME,
    PROPOSAL_NAME as POSITIVE_ROUTE_PROPOSAL_NAME,
    REPOSITORY_ROOT as POSITIVE_ROUTE_REPOSITORY_ROOT,
    RUNNER_TASK_ID as POSITIVE_ROUTE_TASK_ID,
    RUNNER_TASK_RELATIVE as POSITIVE_ROUTE_TASK_RELATIVE,
    RuntimePaths as PositiveRouteRuntimePaths,
    build_positive_route_coach_proposal_envelope_bytes,
    run_positive_route_hypothesis_design,
    validate_positive_route_coach_proposal_envelope_bytes,
)
from research_pipeline.tests import (
    test_microstructure_interpretation_runner as interpretation_fixture_module,
)
from research_pipeline.tests.test_microstructure_handoff_runner import _Fixture


SYNTHETIC_PROPOSAL = {
    "design_id": "synthetic-local-pipeline-e2e-v1",
    "created_at": "2026-08-08T02:00:00Z",
    "title": "Synthetic local pipeline E2E design",
    "thesis": "Synthetic test-only thesis; this is not a real strategy hypothesis.",
    "economic_rationale": "Synthetic test-only rationale with no economic claim.",
    "performance_thesis": "Synthetic test-only performance statement; PnL is unproved.",
    "drawdown_thesis": "Synthetic test-only drawdown statement; drawdown is unproved.",
    "opportunity_cost": "Synthetic test-only opportunity-cost statement.",
}
SYNTHETIC_POSITIVE_ROUTE_PROPOSAL = {
    "design_id": "synthetic-positive-route-local-pipeline-e2e-v1",
    "created_at": "2026-08-08T02:00:00Z",
    "title": "Synthetic positive-route local pipeline E2E design",
    "thesis": "Synthetic test-only thesis; this is not a real strategy hypothesis.",
    "economic_rationale": "Synthetic test-only rationale with no economic claim.",
    "performance_thesis": "Synthetic test-only performance statement; PnL is unproved.",
    "drawdown_thesis": "Synthetic test-only drawdown statement; drawdown is unproved.",
    "opportunity_cost": "Synthetic test-only opportunity-cost statement.",
}


class MicrostructureLocalPipelineE2ETest(unittest.TestCase):
    def test_synthetic_handoff_interpretation_and_design_hash_closure(self) -> None:
        fixture = _Fixture()
        interpretation_fixture = (
            interpretation_fixture_module.MicrostructureInterpretationRunnerTest(
                methodName="test_all_four_frozen_dispositions"
            )
        )
        interpretation_fixture.fixture = fixture

        with TemporaryDirectory() as directory:
            base = Path(directory)
            interpretation_paths = interpretation_fixture._install(
                base,
                source_result=b"",
            )
            handoff_result_path = (
                interpretation_paths.source_root / HANDOFF_RESULT_NAME
            )
            handoff_result_path.unlink()
            handoff_paths = HandoffRuntimePaths(
                repository_root=interpretation_paths.repository_root,
                task_owned_root=interpretation_paths.source_root,
            )
            context, _inventory = _validate_fixed_package(handoff_paths)

            handoff_run = run_handoff(handoff_paths)
            handoff_raw = handoff_result_path.read_bytes()
            validate_handoff_result_bytes(handoff_raw, context)
            self.assertEqual("CREATED", handoff_run["status"])

            interpretation_fixture.insufficient_raw = handoff_raw
            positive_handoff_raw = interpretation_fixture._ready_raw(
                {
                    tier: (
                        interpretation_fixture_module.POSITIVE,
                        interpretation_fixture_module.POSITIVE,
                    )
                    for tier in TIER_ORDER
                }
            )
            positive_handoff = validate_handoff_result_bytes(
                positive_handoff_raw,
                context,
            )
            handoff_result_path.write_bytes(positive_handoff_raw)

            interpretation_run = run_interpretation(interpretation_paths)
            interpretation_raw = (
                interpretation_paths.output_root / INTERPRETATION_RESULT_NAME
            ).read_bytes()
            interpretation = validate_interpretation_result_bytes(
                interpretation_raw
            )
            self.assertEqual("CREATED", interpretation_run["status"])
            self.assertEqual(
                "READY_FOR_ONE_HYPOTHESIS_DESIGN",
                interpretation["disposition"],
            )
            self.assertEqual(TIER_ORDER[0], interpretation["screen"]["selected_tier"])
            for tier in TIER_ORDER:
                self.assertEqual(
                    {
                        "confirmatory_horizon_state": "POSITIVE",
                        "primary_horizon_state": "POSITIVE",
                        "tier_disposition": "PASS",
                    },
                    interpretation["screen"]["tier_evaluations"][tier],
                )

            proposal_raw = build_coach_proposal_envelope_bytes(
                interpretation_raw,
                SYNTHETIC_PROPOSAL,
            )
            proposal = validate_coach_proposal_envelope_bytes(
                proposal_raw,
                interpretation_raw,
            )
            required_design_files = (
                set(DESIGN_REPOSITORY_INPUTS)
                | set(DESIGN_IMPLEMENTATION_FILES)
                | {DESIGN_TASK_RELATIVE}
            )
            for relative_name in required_design_files:
                source = DESIGN_REPOSITORY_ROOT.joinpath(
                    *relative_name.split("/")
                )
                target = interpretation_paths.repository_root.joinpath(
                    *relative_name.split("/")
                )
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            proposal_root = base / "inbox" / DESIGN_TASK_ID
            design_output_root = base / "outbox" / DESIGN_TASK_ID
            proposal_root.mkdir(parents=True)
            design_output_root.mkdir(parents=True)
            (proposal_root / PROPOSAL_NAME).write_bytes(proposal_raw)
            design_paths = DesignRuntimePaths(
                repository_root=interpretation_paths.repository_root,
                source_root=interpretation_paths.output_root,
                proposal_root=proposal_root,
                output_root=design_output_root,
            )
            design_run = run_hypothesis_design(design_paths)
            design_raw = (design_paths.output_root / DESIGN_RESULT_NAME).read_bytes()
            design = validate_hypothesis_design_result_bytes(
                design_raw,
                interpretation_raw,
            )

            positive_route_proposal_raw = (
                build_positive_route_coach_proposal_envelope_bytes(
                    interpretation_raw,
                    SYNTHETIC_POSITIVE_ROUTE_PROPOSAL,
                )
            )
            positive_route_proposal = (
                validate_positive_route_coach_proposal_envelope_bytes(
                    positive_route_proposal_raw,
                    interpretation_raw,
                )
            )
            required_positive_route_files = (
                set(POSITIVE_ROUTE_REPOSITORY_INPUTS)
                | set(POSITIVE_ROUTE_IMPLEMENTATION_FILES)
                | {POSITIVE_ROUTE_TASK_RELATIVE}
            )
            for relative_name in required_positive_route_files:
                source = POSITIVE_ROUTE_REPOSITORY_ROOT.joinpath(
                    *relative_name.split("/")
                )
                target = interpretation_paths.repository_root.joinpath(
                    *relative_name.split("/")
                )
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            positive_route_proposal_root = (
                base / "inbox" / POSITIVE_ROUTE_TASK_ID
            )
            positive_route_output_root = (
                base / "outbox" / POSITIVE_ROUTE_TASK_ID
            )
            positive_route_proposal_root.mkdir(parents=True)
            positive_route_output_root.mkdir(parents=True)
            (
                positive_route_proposal_root / POSITIVE_ROUTE_PROPOSAL_NAME
            ).write_bytes(positive_route_proposal_raw)
            positive_route_paths = PositiveRouteRuntimePaths(
                repository_root=interpretation_paths.repository_root,
                source_root=interpretation_paths.output_root,
                proposal_root=positive_route_proposal_root,
                output_root=positive_route_output_root,
            )
            positive_route_run = run_positive_route_hypothesis_design(
                positive_route_paths
            )
            positive_route_raw = (
                positive_route_paths.output_root / POSITIVE_ROUTE_RESULT_NAME
            ).read_bytes()
            positive_route_design = (
                validate_positive_route_hypothesis_design_result_bytes(
                    positive_route_raw,
                    interpretation_raw,
                )
            )

            handoff_document_sha256 = hashlib.sha256(
                positive_handoff_raw
            ).hexdigest()
            interpretation_document_sha256 = hashlib.sha256(
                interpretation_raw
            ).hexdigest()
            self.assertEqual(DIAGNOSTIC_TASK_SHA256, positive_handoff["task_sha256"])
            self.assertEqual(
                context.manifest_sha256,
                positive_handoff["input_manifest"]["sha256"],
            )
            self.assertEqual(
                context.state_sha256,
                positive_handoff["canonical_state"]["sha256"],
            )
            self.assertEqual(
                handoff_document_sha256,
                interpretation["source_handoff_result"]["document_sha256"],
            )
            self.assertEqual(
                positive_handoff["seal"]["payload_sha256"],
                interpretation["source_handoff_result"]["payload_sha256"],
            )
            self.assertEqual(
                positive_handoff["diagnostic_payload_hashes"]["payload_sha256"],
                interpretation["source_diagnostic_result"]["payload_sha256"],
            )
            self.assertEqual(
                interpretation_document_sha256,
                proposal["source_interpretation"]["document_sha256"],
            )
            self.assertEqual(
                interpretation["seal"]["payload_sha256"],
                proposal["source_interpretation"]["payload_sha256"],
            )
            self.assertEqual(
                interpretation_document_sha256,
                design["source_interpretation"]["document_sha256"],
            )
            self.assertEqual(
                handoff_document_sha256,
                design["source_interpretation"]["handoff_document_sha256"],
            )
            self.assertEqual(
                positive_handoff["diagnostic_payload_hashes"]["payload_sha256"],
                design["source_interpretation"]["diagnostic_payload_sha256"],
            )
            self.assertEqual(
                TIER_ORDER[0],
                design["source_interpretation"]["selected_tier"],
            )

            self.assertEqual(
                interpretation_document_sha256,
                positive_route_proposal["source_interpretation"][
                    "document_sha256"
                ],
            )
            self.assertEqual(
                interpretation["seal"]["payload_sha256"],
                positive_route_proposal["source_interpretation"]["payload_sha256"],
            )
            positive_route_source = positive_route_design["source_interpretation"]
            self.assertEqual(
                interpretation_document_sha256,
                positive_route_source["document_sha256"],
            )
            self.assertEqual(
                interpretation["seal"]["payload_sha256"],
                positive_route_source["payload_sha256"],
            )
            self.assertEqual(
                handoff_document_sha256,
                positive_route_source["handoff_document_sha256"],
            )
            self.assertEqual(
                positive_handoff["seal"]["payload_sha256"],
                positive_route_source["handoff_payload_sha256"],
            )
            self.assertEqual(
                positive_handoff["diagnostic_payload_hashes"]["payload_sha256"],
                positive_route_source["diagnostic_payload_sha256"],
            )
            self.assertEqual(TIER_ORDER[0], positive_route_source["selected_tier"])

            expected_route = {
                "route_id": ROUTE_ID,
                "route_contract_sha256": ROUTE_CONTRACT_SHA256,
                "priority": "SOLE_PRIMARY",
                "source_selected_tier": TIER_ORDER[0],
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
            self.assertEqual(expected_route, positive_route_proposal["route_selection"])
            self.assertEqual(expected_route, positive_route_design["route_selection"])
            self.assertEqual(
                TIER_ORDER[0],
                positive_route_design["hypothesis_design"]["source_selected_tier"],
            )

            self.assertEqual("CREATED", design_run["status"])
            self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", design_run["design_status"])
            self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", design["status"])
            self.assertEqual(
                SYNTHETIC_PROPOSAL,
                {
                    name: design["hypothesis_design"][name]
                    for name in SYNTHETIC_PROPOSAL
                },
            )
            self.assertEqual(MISSING_PROOF, design["missing_proof"])
            self.assertEqual("MISSING_PROOF", design["missing_proof"]["strategy_pnl"])
            self.assertEqual("MISSING_PROOF", design["missing_proof"]["drawdown"])
            self.assertEqual(
                "MISSING_PROOF",
                design["missing_proof"]["fees_slippage_fills_capacity"],
            )
            self.assertFalse(any(proposal["safety_assertions"].values()))
            self.assertEqual(SAFETY_ASSERTIONS, design["safety_assertions"])
            self.assertFalse(any(design["safety_assertions"].values()))

            self.assertEqual("CREATED", positive_route_run["status"])
            self.assertEqual(ROUTE_ID, positive_route_run["route_id"])
            self.assertEqual(
                "DESIGN_ONLY_NOT_REGISTERED",
                positive_route_run["design_status"],
            )
            self.assertEqual(
                "DESIGN_ONLY_NOT_REGISTERED",
                positive_route_design["status"],
            )
            self.assertEqual(
                SYNTHETIC_POSITIVE_ROUTE_PROPOSAL,
                {
                    name: positive_route_design["hypothesis_design"][name]
                    for name in SYNTHETIC_POSITIVE_ROUTE_PROPOSAL
                },
            )
            self.assertEqual(
                POSITIVE_ROUTE_MISSING_PROOF,
                positive_route_design["missing_proof"],
            )
            for economic_boundary in (
                "fees_slippage_friction_value",
                "strategy_pnl",
                "drawdown",
                "capacity",
                "candidate_readiness",
                "oos_value",
                "activation",
            ):
                self.assertEqual(
                    "MISSING_PROOF",
                    positive_route_design["missing_proof"][economic_boundary],
                )
            self.assertFalse(
                any(positive_route_proposal["safety_assertions"].values())
            )
            self.assertEqual(
                POSITIVE_ROUTE_SAFETY_ASSERTIONS,
                positive_route_design["safety_assertions"],
            )
            self.assertFalse(
                any(positive_route_design["safety_assertions"].values())
            )
            for boundary in (
                "canonical_state_write_authorized",
                "candidate_registration_authorized",
                "oos_access_authorized",
                "activation_authorized",
            ):
                self.assertFalse(interpretation["inference_boundaries"][boundary])


if __name__ == "__main__":
    unittest.main()
