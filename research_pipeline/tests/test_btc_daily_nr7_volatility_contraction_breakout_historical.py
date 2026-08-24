from __future__ import annotations

from datetime import date
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import jsonschema


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_nr7_volatility_contraction_breakout_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-nr7-volatility-contraction-breakout-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-nr7-volatility-contraction-breakout-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline" / "hypothesis.schema.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("nr7_breakout_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class Nr7BreakoutRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_hypothesis_is_schema_complete_and_research_only(self) -> None:
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        jsonschema.validate(hypothesis, schema)
        self.assertEqual(hypothesis["authorization"], "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE")

    def test_manifest_accepts_only_exact_frozen_policy(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(manifest["strategy_policy"]["hold_hours"], 168)

    def test_manifest_rejects_hold_rescue(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["hold_hours"] = 72
        with self.assertRaisesRegex(self.runner.ResearchReject, "POLICY"):
            self.runner.validate_manifest(manifest)

    def test_nr_setup_is_strict_and_neighbor_specific(self) -> None:
        values = [Decimal(value) for value in ("0.09", "0.08", "0.07", "0.06", "0.05", "0.04", "0.03", "0.03", "0.02", "0.01")]
        days = [
            self.runner.DailyBar(date(2020, 1, index + 1), Decimal("1"), Decimal("1"), Decimal("1"), Decimal("1"), value)
            for index, value in enumerate(values)
        ]
        self.assertTrue(self.runner.is_nr_setup(days, 6, 7))
        self.assertFalse(self.runner.is_nr_setup(days, 7, 7))
        self.assertTrue(self.runner.is_nr_setup(days, 9, 10))

    def test_policy_has_three_frozen_variants_and_no_short(self) -> None:
        policy = self.runner.expected_policy()
        self.assertEqual(policy["variants"], 3)
        self.assertEqual(policy["short"], "DENY")
        self.assertEqual(policy["rejection_only_neighbors"], [
            "STRICTLY_SMALLEST_OF_5_COMPLETE_DAYS",
            "STRICTLY_SMALLEST_OF_10_COMPLETE_DAYS",
        ])


if __name__ == "__main__":
    unittest.main()
