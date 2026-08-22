from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import unittest

from research_pipeline.hypotheses import build_hypothesis


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_h1_four_day_variance_ratio_positive_persistence_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-v1.hypothesis.json"
SPEC = importlib.util.spec_from_file_location("btc_h1_variance_ratio_economic_test", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = research
SPEC.loader.exec_module(research)


class BtcH1FourDayVarianceRatioEconomicTest(unittest.TestCase):
    def test_hypothesis_is_schema_complete_and_research_only(self) -> None:
        value = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        record = build_hypothesis(
            value,
            available_capabilities={value["required_capability"]},
        )
        self.assertEqual(record["status"], "READY")
        self.assertEqual(record["parent"], "BTC_28_COMPLETE_DAY_POSITIVE_TREND_LONG_CASH_SAME_WINDOW_COST_AND_VALUATION")

    def test_manifest_and_all_frozen_hash_bindings_validate(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")
        required = manifest["gate_set"]["required"]
        self.assertEqual(tuple(required), research.GATES)
        self.assertTrue(any("trend_parent" in gate for gate in required))

    def test_feature_targets_reproduce_sealed_preoutcome_lattice(self) -> None:
        parser = research.load_module("variance_ratio_test_parser", research.PARSER_SOURCE)
        support = research.load_module("variance_ratio_test_support", research.SUPPORT_PROBE_SOURCE)
        bars = parser.parse_rows(research.DATA_SOURCE.read_text(encoding="utf-8"))
        candidate, parent, states, lattice_hash = research.feature_targets(support, bars)
        self.assertEqual(len(states), 2164)
        self.assertEqual(lattice_hash, research.EXPECTED_FEATURE_LATTICE_SHA256)
        design_candidate = [value for when, value in candidate.items() if research.DESIGN[0] <= when < research.DESIGN[1]]
        design_parent = [value for when, value in parent.items() if research.DESIGN[0] <= when < research.DESIGN[1]]
        validation_candidate = [value for when, value in candidate.items() if research.VALIDATION[0] <= when < research.VALIDATION[1]]
        validation_parent = [value for when, value in parent.items() if research.VALIDATION[0] <= when < research.VALIDATION[1]]
        self.assertEqual((len(design_candidate), sum(design_candidate), sum(design_parent)), (1432, 209, 800))
        self.assertEqual((len(validation_candidate), sum(validation_candidate), sum(validation_parent)), (731, 208, 463))


if __name__ == "__main__":
    unittest.main()
