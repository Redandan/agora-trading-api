from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_hypothesis_design import (
    CONTRACT_ID,
    CONTRACT_SHA256,
    EVIDENCE_PLAN,
    MISSING_PROOF,
    POLICY_BINDING,
    RESULT_SCHEMA_SHA256,
    RESULT_TYPE,
    SAFETY_ASSERTIONS,
    HypothesisDesignContractError,
    build_hypothesis_design_result_bytes,
    validate_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_interpretation import (
    TIER_ORDER,
    InterpretationContractError,
    interpret_handoff_result_bytes,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests import test_microstructure_interpretation as source_fixture


REPOSITORY = Path(__file__).resolve().parents[2]
PROPOSAL = {
    "design_id": "synthetic-coach-design-v1",
    "created_at": "2026-08-08T00:00:00Z",
    "title": "Synthetic entry-admission design",
    "thesis": "Caller-authored synthetic thesis for contract testing only.",
    "economic_rationale": "Caller-authored synthetic rationale for contract testing only.",
    "performance_thesis": "Caller-authored synthetic performance expectation.",
    "drawdown_thesis": "Caller-authored synthetic drawdown expectation.",
    "opportunity_cost": "Caller-authored synthetic opportunity-cost statement.",
}


def _parsed(raw: bytes) -> dict[str, object]:
    value = json.loads(raw.decode("utf-8"))
    assert isinstance(value, dict)
    return value


def _reseal(value: dict[str, object]) -> bytes:
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": (
            "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
        ),
    }
    return canonical_json_bytes(value)


class MicrostructureHypothesisDesignTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        source_fixture.MicrostructureInterpretationTest.setUpClass()
        cls.source = source_fixture.MicrostructureInterpretationTest(
            methodName="test_all_four_terminal_dispositions"
        )

    @classmethod
    def tearDownClass(cls) -> None:
        source_fixture.MicrostructureInterpretationTest.tearDownClass()

    def _interpretation(
        self,
        states: dict[
            str,
            tuple[tuple[str, str, str], tuple[str, str, str]],
        ]
        | None = None,
        *,
        insufficient: bool = False,
    ) -> bytes:
        handoff = (
            self.source.insufficient_raw
            if insufficient
            else self.source._ready_raw(states)
        )
        return interpret_handoff_result_bytes(handoff, self.source.context)

    def _positive(self, tier: str = TIER_ORDER[0]) -> bytes:
        return self._interpretation(
            {tier: (source_fixture.POSITIVE, source_fixture.POSITIVE)}
        )

    def test_contract_schema_hashes_and_identities_are_exact(self) -> None:
        contract_path = (
            REPOSITORY
            / "research_pipeline"
            / "okx-microstructure-hypothesis-design-contract.v1.json"
        )
        schema_path = (
            REPOSITORY
            / "research_pipeline"
            / "microstructure-hypothesis-design-result.v1.schema.json"
        )
        self.assertEqual(
            CONTRACT_SHA256,
            hashlib.sha256(contract_path.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            RESULT_SCHEMA_SHA256,
            hashlib.sha256(schema_path.read_bytes()).hexdigest(),
        )
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(CONTRACT_ID, contract["contract_id"])
        self.assertEqual(RESULT_TYPE, contract["result_contract"]["result_type"])
        self.assertEqual(
            RESULT_SCHEMA_SHA256,
            contract["result_contract"]["schema_sha256"],
        )
        self.assertEqual(
            "urn:agora-research:microstructure-hypothesis-design-result:v1",
            schema["$id"],
        )

    def test_all_four_dispositions_close_or_design_deterministically(self) -> None:
        cases = (
            (
                self._positive(),
                PROPOSAL,
                "DESIGN_ONLY_NOT_REGISTERED",
                False,
            ),
            (
                self._interpretation(),
                None,
                "CLOSED_NO_HYPOTHESIS_DESIGN",
                True,
            ),
            (
                self._interpretation(
                    {TIER_ORDER[0]: (source_fixture.MIXED, source_fixture.MIXED)}
                ),
                None,
                "CLOSED_NO_HYPOTHESIS_DESIGN",
                True,
            ),
            (
                self._interpretation(insufficient=True),
                None,
                "CLOSED_NO_HYPOTHESIS_DESIGN",
                True,
            ),
        )
        for interpretation, proposal, status, closed in cases:
            with self.subTest(status=status, closed=closed):
                raw = build_hypothesis_design_result_bytes(
                    interpretation, proposal
                )
                result = validate_hypothesis_design_result_bytes(
                    raw, interpretation
                )
                self.assertEqual(status, result["status"])
                self.assertEqual(closed, result["hypothesis_design"] is None)
                self.assertEqual(closed, result["evidence_plan"] is None)

    def test_positive_requires_exactly_one_closed_coach_proposal(self) -> None:
        interpretation = self._positive()
        with self.assertRaises(HypothesisDesignContractError):
            build_hypothesis_design_result_bytes(interpretation)
        with self.assertRaises(HypothesisDesignContractError):
            build_hypothesis_design_result_bytes(
                interpretation, [PROPOSAL, PROPOSAL]  # type: ignore[arg-type]
            )
        for mutation in (
            {**PROPOSAL, "mechanism": "caller-selected"},
            {key: value for key, value in PROPOSAL.items() if key != "thesis"},
            {**PROPOSAL, "thesis": ""},
            {**PROPOSAL, "created_at": "not-a-timestamp"},
            {**PROPOSAL, "design_id": "INVALID"},
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaises(HypothesisDesignContractError):
                    build_hypothesis_design_result_bytes(
                        interpretation, mutation
                    )

    def test_non_positive_dispositions_reject_every_proposal(self) -> None:
        interpretations = (
            self._interpretation(),
            self._interpretation(
                {TIER_ORDER[0]: (source_fixture.MIXED, source_fixture.MIXED)}
            ),
            self._interpretation(insufficient=True),
        )
        for interpretation in interpretations:
            with self.assertRaises(HypothesisDesignContractError):
                build_hypothesis_design_result_bytes(
                    interpretation, PROPOSAL
                )

    def test_selected_tier_is_copied_without_mechanism_or_threshold_choice(self) -> None:
        interpretation = self._positive(TIER_ORDER[2])
        result = validate_hypothesis_design_result_bytes(
            build_hypothesis_design_result_bytes(interpretation, PROPOSAL),
            interpretation,
        )
        design = result["hypothesis_design"]
        self.assertEqual(
            TIER_ORDER[2],
            design["proposed_mechanism"]["source_selected_tier"],
        )
        self.assertEqual(1, design["maximum_candidate_variants"])
        self.assertFalse(
            design["proposed_mechanism"]["threshold_tuning_authorized"]
        )
        self.assertFalse(
            design["proposed_mechanism"]["magnitude_claim_authorized"]
        )
        self.assertFalse(
            design["proposed_mechanism"][
                "more_complex_tier_selection_authorized"
            ]
        )

    def test_policy_metrics_missing_proof_and_future_oos_are_frozen(self) -> None:
        interpretation = self._positive()
        result = validate_hypothesis_design_result_bytes(
            build_hypothesis_design_result_bytes(interpretation, PROPOSAL),
            interpretation,
        )
        self.assertEqual(POLICY_BINDING, result["policy_binding"])
        self.assertEqual(MISSING_PROOF, result["missing_proof"])
        self.assertEqual(EVIDENCE_PLAN, result["evidence_plan"])
        self.assertFalse(result["evidence_plan"]["discovery_window_reuse_as_oos"])
        self.assertTrue(
            result["evidence_plan"][
                "hypothesis_and_manifest_freeze_before_future_oos_start"
            ]
        )

    def test_policy_drift_fails_before_output(self) -> None:
        interpretation = self._positive()
        with TemporaryDirectory() as directory:
            path = Path(directory) / "policy.v3.json"
            path.write_bytes(
                (REPOSITORY / "research_pipeline" / "policy.v3.json").read_bytes()
                + b" "
            )
            with patch(
                "research_pipeline.microstructure_hypothesis_design.POLICY_PATH",
                path,
            ), self.assertRaisesRegex(
                HypothesisDesignContractError, "policy V3 hash changed"
            ):
                build_hypothesis_design_result_bytes(
                    interpretation, PROPOSAL
                )

    def test_source_contract_hash_and_noncanonical_mutations_fail_closed(self) -> None:
        interpretation = self._positive()
        changed = _parsed(interpretation)
        changed["interpretation_contract"]["sha256"] = "0" * 64
        with self.assertRaises(InterpretationContractError):
            build_hypothesis_design_result_bytes(_reseal(changed), PROPOSAL)
        with self.assertRaises(InterpretationContractError):
            build_hypothesis_design_result_bytes(interpretation + b" ", PROPOSAL)

        result_raw = build_hypothesis_design_result_bytes(
            interpretation, PROPOSAL
        )
        result = _parsed(result_raw)
        result["source_interpretation"]["document_sha256"] = "0" * 64
        with self.assertRaisesRegex(
            HypothesisDesignContractError, "source interpretation binding"
        ):
            validate_hypothesis_design_result_bytes(
                _reseal(result), interpretation
            )

    def test_seal_noncanonical_and_branch_mutations_fail_closed(self) -> None:
        interpretation = self._positive()
        raw = build_hypothesis_design_result_bytes(interpretation, PROPOSAL)
        with self.assertRaises(HypothesisDesignContractError):
            validate_hypothesis_design_result_bytes(raw + b" ", interpretation)
        changed = _parsed(raw)
        changed["seal"]["payload_sha256"] = "0" * 64
        with self.assertRaisesRegex(HypothesisDesignContractError, "seal"):
            validate_hypothesis_design_result_bytes(
                canonical_json_bytes(changed), interpretation
            )
        changed = _parsed(raw)
        changed["status"] = "CLOSED_NO_HYPOTHESIS_DESIGN"
        with self.assertRaisesRegex(
            HypothesisDesignContractError, "positive design branch"
        ):
            validate_hypothesis_design_result_bytes(
                _reseal(changed), interpretation
            )

    def test_deterministic_output_input_immutability_and_false_boundaries(self) -> None:
        interpretation = self._positive()
        interpretation_before = bytes(interpretation)
        proposal = deepcopy(PROPOSAL)
        proposal_before = deepcopy(proposal)
        first = build_hypothesis_design_result_bytes(interpretation, proposal)
        second = build_hypothesis_design_result_bytes(interpretation, proposal)
        self.assertEqual(first, second)
        self.assertEqual(interpretation_before, interpretation)
        self.assertEqual(proposal_before, proposal)
        result = validate_hypothesis_design_result_bytes(first, interpretation)
        self.assertEqual(SAFETY_ASSERTIONS, result["safety_assertions"])
        self.assertFalse(any(result["safety_assertions"].values()))
        self.assertEqual(canonical_json_bytes(_parsed(first)), first)

    def test_bare_interpretation_dict_is_rejected(self) -> None:
        interpretation = self._positive()
        with self.assertRaises(HypothesisDesignContractError):
            build_hypothesis_design_result_bytes(
                _parsed(interpretation), PROPOSAL  # type: ignore[arg-type]
            )


if __name__ == "__main__":
    unittest.main()
