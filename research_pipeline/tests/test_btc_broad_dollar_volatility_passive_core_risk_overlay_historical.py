from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import jsonschema


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_broad_dollar_volatility_passive_core_risk_overlay_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-broad-dollar-volatility-passive-core-risk-overlay-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline" / "examples" / "btc-broad-dollar-volatility-passive-core-risk-overlay-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline" / "hypothesis.schema.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("dollar_volatility_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class Base:
    @staticmethod
    def percentile(values, fraction):
        assert fraction == Decimal("0.5")
        ordered = sorted(values)
        midpoint = len(ordered) // 2
        return (ordered[midpoint - 1] + ordered[midpoint]) / Decimal("2")


class DollarVolatilityRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_hypothesis_is_schema_complete_and_research_only(self) -> None:
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        jsonschema.validate(hypothesis, schema)
        self.assertEqual(hypothesis["authorization"], "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE")

    def test_manifest_accepts_exact_volatility_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(manifest["strategy_policy"]["primary"]["lookback_weeks"], 13)

    def test_manifest_rejects_direction_reinterpretation(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["primary"]["high_risk_relation"] = "CURRENT_DOLLAR_CHANGE_POSITIVE"
        with self.assertRaisesRegex(self.runner.ResearchReject, "PRIMARY"):
            self.runner.validate_manifest(manifest)

    def test_volatility_discards_direction_and_uses_lagged_reference(self) -> None:
        weekly = []
        start = date(2018, 1, 5)
        value = Decimal("100")
        for index in range(90):
            value *= Decimal("1.01") if index % 2 == 0 else Decimal("0.99")
            weekly.append((start + timedelta(days=7 * index), value))
        points = self.runner.build_volatility_points(weekly, 8, Base())
        self.assertIsNone(points[51].lagged_median)
        self.assertIsNotNone(points[52].lagged_median)
        self.assertGreater(points[52].value, Decimal("0"))

    def test_daily_target_relation_is_strict_and_never_below_half(self) -> None:
        when = datetime(2020, 1, 1)
        points = [
            self.runner.DollarVolatilityPoint(when, Decimal("2"), Decimal("2")),
            self.runner.DollarVolatilityPoint(when + timedelta(days=7), Decimal("3"), Decimal("2")),
        ]
        targets = self.runner.build_daily_targets(points, when, when + timedelta(days=14))
        self.assertEqual(targets[when], Decimal("1"))
        self.assertEqual(targets[when + timedelta(days=7)], Decimal("0.5"))
        self.assertEqual(set(targets.values()), {Decimal("1"), Decimal("0.5")})


if __name__ == "__main__":
    unittest.main()
