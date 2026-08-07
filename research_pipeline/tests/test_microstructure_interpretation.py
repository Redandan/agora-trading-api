from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_diagnostic import (
    CANONICALIZATION as DIAGNOSTIC_CANONICALIZATION,
    payload_sha256 as diagnostic_payload_sha256,
)
from research_pipeline.microstructure_handoff import (
    HandoffContractError,
    validate_handoff_package,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_interpretation import (
    CONTRACT_ID,
    CONTRACT_SHA256,
    HANDOFF_RESULT_SCHEMA_SHA256,
    InterpretationContractError,
    RESULT_SCHEMA_SHA256,
    RESULT_TYPE,
    TIER_ORDER,
    interpret_handoff_result_bytes,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_source_contract import canonical_json_bytes
from research_pipeline.tests.test_microstructure_handoff_contract import (
    TASK_ID,
    TASK_SHA256,
    _Fixture,
    _result_bytes,
    _seal,
)


POSITIVE = ("1", "50.01", "1")
NEGATIVE = ("0", "50", "0")
MIXED = ("1", "50", "1")


def _parsed(raw: bytes) -> dict[str, object]:
    value = json.loads(raw.decode("utf-8"))
    assert isinstance(value, dict)
    return value


def _reseal_handoff(handoff: dict[str, object]) -> bytes:
    diagnostic = handoff["diagnostic_result"]
    assert isinstance(diagnostic, dict)
    diagnostic["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": diagnostic_payload_sha256(diagnostic),
        "canonicalization": DIAGNOSTIC_CANONICALIZATION,
    }
    handoff["diagnostic_payload_hashes"] = {
        "payload_sha256": diagnostic["seal"]["payload_sha256"],
        "canonical_document_sha256": hashlib.sha256(
            canonical_json_bytes(diagnostic)
        ).hexdigest(),
    }
    _seal(handoff)
    return canonical_json_bytes(handoff)


def _set_screen_metrics(metrics: dict[str, object], values: tuple[str, str, str]) -> None:
    metrics["median_return_bps"] = values[0]
    metrics["positive_return_share_pct"] = values[1]
    metrics["matched_median_return_delta_bps"] = values[2]


class MicrostructureInterpretationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.directory = TemporaryDirectory()
        root = Path(cls.directory.name)
        cls.fixture = _Fixture()
        inventory = cls.fixture.materialize(root)
        cls.context = validate_handoff_package(
            root,
            inventory,
            expected_task_id=TASK_ID,
            expected_task_sha256=TASK_SHA256,
        )
        cls.insufficient_raw = _result_bytes(cls.context)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def _ready_raw(
        self,
        states: dict[str, tuple[tuple[str, str, str], tuple[str, str, str]]] | None = None,
    ) -> bytes:
        handoff = _parsed(self.insufficient_raw)
        diagnostic = handoff["diagnostic_result"]
        assert isinstance(diagnostic, dict)
        diagnostic["status"] = "FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION"
        tiers = diagnostic["tiers"]
        assert isinstance(tiers, dict)
        states = states or {}
        for tier_name in TIER_ORDER:
            tier = tiers[tier_name]
            assert isinstance(tier, dict)
            tier["gates"] = {
                "minimum_30_events": True,
                "minimum_10_events_first_seven_days": True,
                "minimum_10_events_second_seven_days": True,
                "minimum_80_pct_matched_controls": True,
            }
            tier["gate_status"] = "PASS"
            confirmatory, primary = states.get(tier_name, (NEGATIVE, NEGATIVE))
            metrics = tier["metrics_by_horizon_minutes"]
            assert isinstance(metrics, dict)
            _set_screen_metrics(metrics["15"], confirmatory)
            _set_screen_metrics(metrics["60"], primary)
        return _reseal_handoff(handoff)

    def _interpret(self, raw: bytes) -> dict[str, object]:
        return validate_interpretation_result_bytes(
            interpret_handoff_result_bytes(raw, self.context)
        )

    def test_contract_schema_hashes_and_identities_are_exact(self) -> None:
        root = Path(__file__).resolve().parents[2]
        contract_path = root / "research_pipeline" / "okx-microstructure-forward-interpretation-contract.v1.json"
        schema_path = root / "research_pipeline" / "microstructure-interpretation-result.v1.schema.json"
        self.assertEqual(CONTRACT_SHA256, hashlib.sha256(contract_path.read_bytes()).hexdigest())
        self.assertEqual(RESULT_SCHEMA_SHA256, hashlib.sha256(schema_path.read_bytes()).hexdigest())
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        self.assertEqual(CONTRACT_ID, contract["contract_id"])
        self.assertEqual(RESULT_TYPE, contract["result_contract"]["result_type"])
        self.assertEqual(RESULT_SCHEMA_SHA256, contract["result_contract"]["schema_sha256"])
        self.assertEqual(HANDOFF_RESULT_SCHEMA_SHA256, contract["source_handoff_result_contract"]["schema_sha256"])
        self.assertEqual(
            "urn:agora-research:microstructure-forward-interpretation-result:v1",
            schema["$id"],
        )

    def test_valid_handoff_is_validated_before_interpretation(self) -> None:
        handoff = validate_handoff_result_bytes(self.insufficient_raw, self.context)
        result = self._interpret(self.insufficient_raw)
        self.assertEqual(handoff["seal"]["payload_sha256"], result["source_handoff_result"]["payload_sha256"])
        self.assertEqual("INSUFFICIENT_FORWARD_EVIDENCE", result["disposition"])

    def test_all_four_terminal_dispositions(self) -> None:
        cases = {
            "insufficient": (
                self.insufficient_raw,
                "INSUFFICIENT_FORWARD_EVIDENCE",
            ),
            "ready": (
                self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)}),
                "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            ),
            "no_candidate": (
                self._ready_raw(),
                "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
            ),
            "ambiguous": (
                self._ready_raw({TIER_ORDER[0]: (MIXED, MIXED)}),
                "AMBIGUOUS_NO_HYPOTHESIS",
            ),
        }
        for name, (raw, expected) in cases.items():
            with self.subTest(name=name):
                self.assertEqual(expected, self._interpret(raw)["disposition"])

    def test_simplest_sufficient_tier_wins_when_multiple_pass(self) -> None:
        raw = self._ready_raw(
            {tier: (POSITIVE, POSITIVE) for tier in TIER_ORDER}
        )
        result = self._interpret(raw)
        self.assertEqual(TIER_ORDER[0], result["screen"]["selected_tier"])
        self.assertEqual("READY_FOR_ONE_HYPOTHESIS_DESIGN", result["disposition"])

    def test_stricter_only_pass_selects_exactly_that_tier(self) -> None:
        raw = self._ready_raw({TIER_ORDER[2]: (POSITIVE, POSITIVE)})
        result = self._interpret(raw)
        self.assertEqual(TIER_ORDER[2], result["screen"]["selected_tier"])
        self.assertEqual("READY_FOR_ONE_HYPOTHESIS_DESIGN", result["disposition"])

    def test_each_metric_boundary_is_mixed_when_other_metrics_are_positive(self) -> None:
        boundaries = {
            "median_return_bps": ("0", "50.01", "1"),
            "positive_return_share_pct": ("1", "50.00", "1"),
            "matched_median_return_delta_bps": ("1", "50.01", "0"),
        }
        for name, values in boundaries.items():
            with self.subTest(metric=name):
                raw = self._ready_raw({TIER_ORDER[0]: (values, values)})
                result = self._interpret(raw)
                evaluation = result["screen"]["tier_evaluations"][TIER_ORDER[0]]
                self.assertEqual("MIXED", evaluation["confirmatory_horizon_state"])
                self.assertEqual("MIXED", evaluation["primary_horizon_state"])
                self.assertEqual("AMBIGUOUS_NO_HYPOTHESIS", result["disposition"])

    def test_all_exact_boundaries_are_negative(self) -> None:
        result = self._interpret(self._ready_raw())
        for evaluation in result["screen"]["tier_evaluations"].values():
            self.assertEqual("NEGATIVE", evaluation["confirmatory_horizon_state"])
            self.assertEqual("NEGATIVE", evaluation["primary_horizon_state"])
            self.assertEqual("REJECT", evaluation["tier_disposition"])

    def test_both_horizons_are_required(self) -> None:
        for confirmatory, primary in ((POSITIVE, NEGATIVE), (NEGATIVE, POSITIVE)):
            with self.subTest(confirmatory=confirmatory, primary=primary):
                raw = self._ready_raw({TIER_ORDER[0]: (confirmatory, primary)})
                result = self._interpret(raw)
                self.assertEqual("AMBIGUOUS", result["screen"]["tier_evaluations"][TIER_ORDER[0]]["tier_disposition"])
                self.assertEqual("AMBIGUOUS_NO_HYPOTHESIS", result["disposition"])

    def test_null_required_metric_is_insufficient_not_ambiguous(self) -> None:
        handoff = _parsed(self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)}))
        handoff["diagnostic_result"]["tiers"][TIER_ORDER[2]]["metrics_by_horizon_minutes"]["15"]["median_return_bps"] = None
        result = self._interpret(_reseal_handoff(handoff))
        self.assertEqual("INCOMPLETE", result["screen"]["global_eligibility"])
        self.assertEqual("INSUFFICIENT_FORWARD_EVIDENCE", result["disposition"])
        self.assertIsNone(result["screen"]["selected_tier"])

    def test_invalid_handoff_seal_and_context_fail_closed(self) -> None:
        changed = _parsed(self.insufficient_raw)
        changed["task_id"] = "changed"
        invalid_seal = canonical_json_bytes(changed)
        with self.assertRaises(HandoffContractError):
            interpret_handoff_result_bytes(invalid_seal, self.context)
        wrong_context = replace(self.context, task_sha256="0" * 64)
        with self.assertRaises(HandoffContractError):
            interpret_handoff_result_bytes(self.insufficient_raw, wrong_context)

    def test_canonical_seal_and_idempotent_bytes(self) -> None:
        raw = self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)})
        first = interpret_handoff_result_bytes(raw, self.context)
        second = interpret_handoff_result_bytes(raw, self.context)
        self.assertEqual(first, second)
        self.assertEqual(canonical_json_bytes(_parsed(first)), first)
        result = validate_interpretation_result_bytes(first)
        expected = hashlib.sha256(
            canonical_json_bytes(result, exclude_key="seal")
        ).hexdigest()
        self.assertEqual(expected, result["seal"]["payload_sha256"])

    def test_incomplete_result_rejects_non_schema_tier_shape(self) -> None:
        result = _parsed(interpret_handoff_result_bytes(self.insufficient_raw, self.context))
        result["screen"]["tier_evaluations"][TIER_ORDER[0]] = {"unexpected": None}
        result["seal"]["payload_sha256"] = hashlib.sha256(
            canonical_json_bytes(result, exclude_key="seal")
        ).hexdigest()
        with self.assertRaises(InterpretationContractError):
            validate_interpretation_result_bytes(canonical_json_bytes(result))

    def test_result_rejects_tier_disposition_inconsistent_with_horizons(self) -> None:
        result = _parsed(
            interpret_handoff_result_bytes(
                self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)}),
                self.context,
            )
        )
        result["screen"]["tier_evaluations"][TIER_ORDER[0]]["tier_disposition"] = "AMBIGUOUS"
        result["screen"]["selected_tier"] = None
        result["disposition"] = "AMBIGUOUS_NO_HYPOTHESIS"
        result["seal"]["payload_sha256"] = hashlib.sha256(
            canonical_json_bytes(result, exclude_key="seal")
        ).hexdigest()
        with self.assertRaises(InterpretationContractError):
            validate_interpretation_result_bytes(canonical_json_bytes(result))

    def test_interpreter_does_not_mutate_input_or_context(self) -> None:
        raw = self._ready_raw({TIER_ORDER[0]: (POSITIVE, POSITIVE)})
        raw_before = bytes(raw)
        context_before = deepcopy(self.context)
        interpret_handoff_result_bytes(raw, self.context)
        self.assertEqual(raw_before, raw)
        self.assertEqual(context_before, self.context)

    def test_descriptive_horizons_have_no_decision_effect(self) -> None:
        baseline_raw = self._ready_raw({TIER_ORDER[1]: (POSITIVE, POSITIVE)})
        changed = _parsed(baseline_raw)
        tiers = changed["diagnostic_result"]["tiers"]
        for tier in tiers.values():
            metrics = tier["metrics_by_horizon_minutes"]
            for horizon in ("5", "240", "1440"):
                metrics[horizon] = {
                    "median_return_bps": "-999",
                    "median_mfe_bps": "999",
                    "median_mae_bps": "-999",
                    "positive_return_share_pct": "0",
                    "matched_median_return_delta_bps": "-999",
                }
        changed_raw = _reseal_handoff(changed)
        baseline = self._interpret(baseline_raw)
        altered = self._interpret(changed_raw)
        self.assertEqual(baseline["screen"], altered["screen"])
        self.assertEqual(baseline["disposition"], altered["disposition"])
        self.assertNotEqual(baseline["source_handoff_result"], altered["source_handoff_result"])


if __name__ == "__main__":
    unittest.main()
