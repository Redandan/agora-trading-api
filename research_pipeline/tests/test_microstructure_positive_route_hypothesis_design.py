from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_interpretation import (
    CANONICALIZATION as INTERPRETATION_CANONICALIZATION,
    CONTRACT_ID as INTERPRETATION_CONTRACT_ID,
    CONTRACT_SHA256 as INTERPRETATION_CONTRACT_SHA256,
    HANDOFF_RESULT_SCHEMA_SHA256,
    HANDOFF_RESULT_TYPE,
    INFERENCE_BOUNDARIES,
    RESULT_SCHEMA_SHA256 as INTERPRETATION_SCHEMA_SHA256,
    RESULT_TYPE as INTERPRETATION_RESULT_TYPE,
    TIER_ORDER,
)
from research_pipeline.microstructure_positive_route_hypothesis_design import (
    CONTRACT_ID,
    CONTRACT_SHA256,
    EVIDENCE_PLAN,
    MISSING_PROOF,
    POLICY_BINDING,
    REQUIRED_CAPABILITY,
    RESULT_SCHEMA_SHA256,
    RESULT_TYPE,
    ROUTE_CONTRACT_SHA256,
    ROUTE_ID,
    SAFETY_ASSERTIONS,
    PositiveRouteHypothesisDesignError,
    build_positive_route_hypothesis_design_result_bytes,
    validate_positive_route_hypothesis_design_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    canonical_json_bytes,
)


REPOSITORY = Path(__file__).resolve().parents[2]
PROPOSAL = {
    "design_id": "synthetic-positive-route-design-v2",
    "created_at": "2026-08-08T00:00:00Z",
    "title": "Synthetic standalone route design",
    "thesis": "Caller-authored synthetic thesis for contract testing only.",
    "economic_rationale": "Caller-authored synthetic rationale for contract testing only.",
    "performance_thesis": "Caller-authored synthetic performance expectation.",
    "drawdown_thesis": "Caller-authored synthetic drawdown expectation.",
    "opportunity_cost": "Caller-authored synthetic opportunity-cost statement.",
}
V1_HASHES = {
    "research_pipeline/okx-microstructure-hypothesis-design-contract.v1.json": "d3e3df7d629938a33cddec00f251bbaaefb4ce17b51eb0b0b558061c692f6948",
    "research_pipeline/microstructure-hypothesis-design-result.v1.schema.json": "af82d3aa81257eb74cf04026fc9a43ae5c0576049d850b3263b90b7f2930e63d",
    "research_pipeline/microstructure_hypothesis_design.py": "ef9864342c62e0415496638a63901194fac06e9cef42120196befb9e9ffa3c4c",
    "research_pipeline/tests/test_microstructure_hypothesis_design.py": "198c78c28d2672f459647e3d47698a2aea7f973ff5c36413ac4647a417b8874c",
    "docs/okx-microstructure-hypothesis-design-v1.md": "ee3677face8ed0e425dd5f26c1ecd6fc3d061eaf3da94959fc15bcbe29bd516a",
    "research_pipeline/okx-microstructure-dra-entry-admission-contract.v1.json": "e67211b5bcd716f15ff7f0344fe0fdc587596a40fbc58732204cdc31bf13654c",
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json": "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e",
}


def _parsed(raw: bytes) -> dict[str, object]:
    value = json.loads(raw.decode("utf-8"))
    assert isinstance(value, dict)
    return value


def _reseal(value: dict[str, object], canonicalization: str) -> bytes:
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": canonicalization,
    }
    return canonical_json_bytes(value)


def _evaluation(state: str) -> dict[str, str]:
    if state == "PASS":
        return {
            "confirmatory_horizon_state": "POSITIVE",
            "primary_horizon_state": "POSITIVE",
            "tier_disposition": "PASS",
        }
    if state == "REJECT":
        return {
            "confirmatory_horizon_state": "NEGATIVE",
            "primary_horizon_state": "NEGATIVE",
            "tier_disposition": "REJECT",
        }
    return {
        "confirmatory_horizon_state": "MIXED",
        "primary_horizon_state": "MIXED",
        "tier_disposition": "AMBIGUOUS",
    }


def _interpretation(
    disposition: str,
    *,
    selected_tier: str | None = None,
) -> bytes:
    if disposition == "INSUFFICIENT_FORWARD_EVIDENCE":
        global_eligibility = "INCOMPLETE"
        evaluations: dict[str, object] = {
            tier: {
                "confirmatory_horizon_state": None,
                "primary_horizon_state": None,
                "tier_disposition": None,
            }
            for tier in TIER_ORDER
        }
        selected_tier = None
    else:
        global_eligibility = "PASS"
        if disposition == "READY_FOR_ONE_HYPOTHESIS_DESIGN":
            assert selected_tier in TIER_ORDER
            evaluations = {
                tier: _evaluation("PASS" if tier == selected_tier else "REJECT")
                for tier in TIER_ORDER
            }
        elif disposition == "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE":
            evaluations = {tier: _evaluation("REJECT") for tier in TIER_ORDER}
            selected_tier = None
        else:
            evaluations = {tier: _evaluation("REJECT") for tier in TIER_ORDER}
            evaluations[TIER_ORDER[0]] = _evaluation("AMBIGUOUS")
            selected_tier = None
    result: dict[str, object] = {
        "schema_version": "1",
        "result_type": INTERPRETATION_RESULT_TYPE,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "interpretation_contract": {
            "contract_id": INTERPRETATION_CONTRACT_ID,
            "sha256": INTERPRETATION_CONTRACT_SHA256,
        },
        "source_handoff_result": {
            "schema_version": "1",
            "result_type": HANDOFF_RESULT_TYPE,
            "schema_sha256": HANDOFF_RESULT_SCHEMA_SHA256,
            "document_sha256": "1" * 64,
            "payload_sha256": "2" * 64,
        },
        "source_diagnostic_result": {
            "contract_id": "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3",
            "contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
            "payload_sha256": "3" * 64,
            "canonical_document_sha256": "4" * 64,
        },
        "screen": {
            "global_eligibility": global_eligibility,
            "primary_horizon_minutes": 60,
            "confirmatory_horizon_minutes": 15,
            "descriptive_only_horizons_minutes": [5, 240, 1440],
            "tier_order": list(TIER_ORDER),
            "tier_evaluations": evaluations,
            "selected_tier": selected_tier,
        },
        "disposition": disposition,
        "inference_boundaries": dict(INFERENCE_BOUNDARIES),
    }
    return _reseal(result, INTERPRETATION_CANONICALIZATION)


def _assert_strict_objects(test: unittest.TestCase, value: object) -> None:
    if isinstance(value, dict):
        if value.get("type") == "object":
            test.assertIs(False, value.get("additionalProperties"))
        for item in value.values():
            _assert_strict_objects(test, item)
    elif isinstance(value, list):
        for item in value:
            _assert_strict_objects(test, item)


class MicrostructurePositiveRouteHypothesisDesignTest(unittest.TestCase):
    def _positive(self, tier: str = TIER_ORDER[0]) -> bytes:
        return _interpretation(
            "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            selected_tier=tier,
        )

    def test_contract_schema_hashes_schema_strictness_and_instance(self) -> None:
        contract_path = REPOSITORY / "research_pipeline" / (
            "okx-microstructure-positive-route-hypothesis-design-contract.v2.json"
        )
        schema_path = REPOSITORY / "research_pipeline" / (
            "microstructure-positive-route-hypothesis-design-result.v2.schema.json"
        )
        self.assertEqual(CONTRACT_SHA256, hashlib.sha256(contract_path.read_bytes()).hexdigest())
        self.assertEqual(RESULT_SCHEMA_SHA256, hashlib.sha256(schema_path.read_bytes()).hexdigest())
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(CONTRACT_ID, contract["contract_id"])
        self.assertEqual(RESULT_TYPE, contract["result_contract"]["result_type"])
        self.assertEqual(RESULT_SCHEMA_SHA256, contract["result_contract"]["schema_sha256"])
        self.assertEqual(
            "https://json-schema.org/draft/2020-12/schema",
            schema["$schema"],
        )
        self.assertEqual(
            "urn:agora-research:microstructure-positive-route-hypothesis-design-result:v2",
            schema["$id"],
        )
        _assert_strict_objects(self, schema)
        result = _parsed(
            build_positive_route_hypothesis_design_result_bytes(
                self._positive(),
                PROPOSAL,
            )
        )
        self.assertEqual(set(schema["required"]), set(result))
        self.assertEqual("2", result["schema_version"])
        self.assertEqual(RESULT_TYPE, result["result_type"])

    def test_positive_binds_only_standalone_route_and_copies_selected_tier(self) -> None:
        interpretation = self._positive(TIER_ORDER[2])
        result = validate_positive_route_hypothesis_design_result_bytes(
            build_positive_route_hypothesis_design_result_bytes(
                interpretation,
                PROPOSAL,
            ),
            interpretation,
        )
        selection = result["route_selection"]
        design = result["hypothesis_design"]
        self.assertEqual("DESIGN_ONLY_NOT_REGISTERED", result["status"])
        self.assertEqual(ROUTE_ID, selection["route_id"])
        self.assertEqual(ROUTE_CONTRACT_SHA256, selection["route_contract_sha256"])
        self.assertEqual("SOLE_PRIMARY", selection["priority"])
        self.assertEqual(TIER_ORDER[2], selection["source_selected_tier"])
        self.assertEqual(TIER_ORDER[2], design["source_selected_tier"])
        self.assertEqual(REQUIRED_CAPABILITY, design["required_capability"])
        self.assertEqual("LONG_ONLY", design["proposed_mechanism"]["direction"])
        self.assertEqual(60, design["proposed_mechanism"]["holding_period_minutes"])
        self.assertEqual(1, selection["maximum_routes"])
        self.assertEqual(1, selection["maximum_designs"])
        self.assertEqual(1, selection["maximum_eventual_candidate_variants"])
        for name in (
            "caller_override_authorized",
            "multiple_routes_authorized",
            "dra_fallback_authorized",
            "route_switch_after_design_outcome_authorized",
            "route_switch_after_validation_outcome_authorized",
            "route_switch_after_oos_outcome_authorized",
        ):
            self.assertFalse(selection[name])
        self.assertTrue(
            {"alternate_route_id", "fallback_routes", "routes"}.isdisjoint(selection)
        )

    def test_non_positive_dispositions_close_without_route_or_proposal(self) -> None:
        for disposition in (
            "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
            "AMBIGUOUS_NO_HYPOTHESIS",
            "INSUFFICIENT_FORWARD_EVIDENCE",
        ):
            with self.subTest(disposition=disposition):
                interpretation = _interpretation(disposition)
                raw = build_positive_route_hypothesis_design_result_bytes(interpretation)
                result = validate_positive_route_hypothesis_design_result_bytes(
                    raw,
                    interpretation,
                )
                self.assertEqual("CLOSED_NO_HYPOTHESIS_DESIGN", result["status"])
                self.assertIsNone(result["route_selection"])
                self.assertIsNone(result["hypothesis_design"])
                self.assertIsNone(result["evidence_plan"])
                with self.assertRaises(PositiveRouteHypothesisDesignError):
                    build_positive_route_hypothesis_design_result_bytes(
                        interpretation,
                        PROPOSAL,
                    )

    def test_positive_requires_one_exact_coach_proposal(self) -> None:
        interpretation = self._positive()
        with self.assertRaises(PositiveRouteHypothesisDesignError):
            build_positive_route_hypothesis_design_result_bytes(interpretation)
        with self.assertRaises(PositiveRouteHypothesisDesignError):
            build_positive_route_hypothesis_design_result_bytes(
                interpretation,
                [PROPOSAL, PROPOSAL],  # type: ignore[arg-type]
            )
        for mutation in (
            {**PROPOSAL, "route": "caller-selected"},
            {key: value for key, value in PROPOSAL.items() if key != "thesis"},
            {**PROPOSAL, "thesis": ""},
            {**PROPOSAL, "created_at": "not-a-timestamp"},
            {**PROPOSAL, "design_id": "INVALID"},
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaises(PositiveRouteHypothesisDesignError):
                    build_positive_route_hypothesis_design_result_bytes(
                        interpretation,
                        mutation,
                    )

    def test_route_and_policy_hash_drift_fail_closed(self) -> None:
        interpretation = self._positive()
        with TemporaryDirectory() as directory:
            root = Path(directory)
            route = root / "route.json"
            route.write_bytes(
                (REPOSITORY / "research_pipeline" / "okx-microstructure-intraday-economic-route-contract.v1.json").read_bytes()
                + b" "
            )
            with patch(
                "research_pipeline.microstructure_positive_route_hypothesis_design.ROUTE_CONTRACT_PATH",
                route,
            ), self.assertRaisesRegex(
                PositiveRouteHypothesisDesignError,
                "intraday route contract hash changed",
            ):
                build_positive_route_hypothesis_design_result_bytes(interpretation, PROPOSAL)
            policy = root / "policy.json"
            policy.write_bytes(
                (REPOSITORY / "research_pipeline" / "policy.v3.json").read_bytes()
                + b" "
            )
            with patch(
                "research_pipeline.microstructure_positive_route_hypothesis_design.POLICY_PATH",
                policy,
            ), self.assertRaisesRegex(
                PositiveRouteHypothesisDesignError,
                "policy V3 hash changed",
            ):
                build_positive_route_hypothesis_design_result_bytes(interpretation, PROPOSAL)

    def test_canonical_interpretation_result_and_seal_tamper_fail_closed(self) -> None:
        interpretation = self._positive()
        with self.assertRaises(ValueError):
            build_positive_route_hypothesis_design_result_bytes(
                interpretation + b" ",
                PROPOSAL,
            )
        with self.assertRaises(PositiveRouteHypothesisDesignError):
            build_positive_route_hypothesis_design_result_bytes(
                _parsed(interpretation),  # type: ignore[arg-type]
                PROPOSAL,
            )
        raw = build_positive_route_hypothesis_design_result_bytes(
            interpretation,
            PROPOSAL,
        )
        with self.assertRaises(PositiveRouteHypothesisDesignError):
            validate_positive_route_hypothesis_design_result_bytes(
                raw + b" ",
                interpretation,
            )
        changed = _parsed(raw)
        changed["seal"]["payload_sha256"] = "0" * 64
        with self.assertRaisesRegex(PositiveRouteHypothesisDesignError, "seal"):
            validate_positive_route_hypothesis_design_result_bytes(
                canonical_json_bytes(changed),
                interpretation,
            )
        changed = _parsed(raw)
        changed["route_selection"]["dra_fallback_authorized"] = True
        with self.assertRaisesRegex(
            PositiveRouteHypothesisDesignError,
            "positive standalone route branch",
        ):
            validate_positive_route_hypothesis_design_result_bytes(
                _reseal(changed, INTERPRETATION_CANONICALIZATION),
                interpretation,
            )

    def test_future_evidence_policy_and_missing_proof_are_exact(self) -> None:
        interpretation = self._positive()
        result = validate_positive_route_hypothesis_design_result_bytes(
            build_positive_route_hypothesis_design_result_bytes(
                interpretation,
                PROPOSAL,
            ),
            interpretation,
        )
        self.assertEqual(POLICY_BINDING, result["policy_binding"])
        self.assertEqual(EVIDENCE_PLAN, result["evidence_plan"])
        self.assertEqual(MISSING_PROOF, result["missing_proof"])
        self.assertEqual(SAFETY_ASSERTIONS, result["safety_assertions"])
        self.assertFalse(any(result["safety_assertions"].values()))
        self.assertEqual(42, result["evidence_plan"]["total_new_complete_utc_days"])
        self.assertEqual("MISSING_PROOF", result["evidence_plan"]["exact_stage_dates"])
        self.assertFalse(
            result["evidence_plan"]["discovery_window_reuse_as_economic_evidence"]
        )
        self.assertTrue(result["evidence_plan"]["oos_server_sealed"])
        self.assertTrue(
            result["evidence_plan"]
            ["oos_nondisclosure_until_design_validation_pass_and_route_frozen"]
        )

    def test_static_contract_has_no_tier_date_or_outcome_instance(self) -> None:
        path = REPOSITORY / "research_pipeline" / (
            "okx-microstructure-positive-route-hypothesis-design-contract.v2.json"
        )
        contract = json.loads(path.read_text(encoding="utf-8"))
        selection = contract["positive_route_selection"]
        self.assertEqual("INTERPRETATION_SELECTED_FIRST_PASS", selection["tier_binding"])
        self.assertTrue(set(TIER_ORDER).isdisjoint(selection.values()))
        self.assertEqual("MISSING_PROOF", contract["evidence_plan"]["exact_stage_dates"])
        self.assertNotIn("observed_outcomes", contract)
        self.assertNotIn("source_generation_id", contract)
        self.assertNotIn("actual_stage_dates", contract)
        self.assertNotIn("trade_counts", contract)

    def test_v1_contracts_and_implementations_remain_byte_exact(self) -> None:
        for relative, expected in V1_HASHES.items():
            with self.subTest(relative=relative):
                self.assertEqual(
                    expected,
                    hashlib.sha256((REPOSITORY / relative).read_bytes()).hexdigest(),
                )

    def test_output_is_deterministic_and_inputs_are_immutable(self) -> None:
        interpretation = self._positive()
        interpretation_before = bytes(interpretation)
        proposal = deepcopy(PROPOSAL)
        proposal_before = deepcopy(proposal)
        first = build_positive_route_hypothesis_design_result_bytes(
            interpretation,
            proposal,
        )
        second = build_positive_route_hypothesis_design_result_bytes(
            interpretation,
            proposal,
        )
        self.assertEqual(first, second)
        self.assertEqual(interpretation_before, interpretation)
        self.assertEqual(proposal_before, proposal)
        self.assertEqual(canonical_json_bytes(_parsed(first)), first)


if __name__ == "__main__":
    unittest.main()
