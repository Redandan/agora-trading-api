from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import jsonschema


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_ulcer28_passive_core_risk_overlay_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-ulcer28-passive-core-risk-overlay-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-ulcer28-passive-core-risk-overlay-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline" / "hypothesis.schema.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("ulcer_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass(frozen=True)
class Point:
    close_time: datetime
    close: Decimal


class Base:
    @staticmethod
    def percentile(values, fraction):
        assert fraction == Decimal("0.5")
        ordered = sorted(values)
        midpoint = len(ordered) // 2
        return (ordered[midpoint - 1] + ordered[midpoint]) / Decimal("2")


class UlcerRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_hypothesis_is_schema_complete_and_research_only(self) -> None:
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        jsonschema.validate(hypothesis, schema)
        self.assertEqual(
            hypothesis["authorization"],
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        )

    def test_manifest_accepts_exact_state_change_only_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(
            manifest["strategy_policy"]["rebalance_rule"],
            "ONLY_WHEN_REGIME_TARGET_CHANGES",
        )

    def test_manifest_rejects_daily_weight_restoration(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rebalance_rule"] = "EVERY_DAY"
        with self.assertRaisesRegex(self.runner.ResearchReject, "REBALANCE_RULE"):
            self.runner.validate_manifest(manifest)

    def test_ulcer_index_uses_running_peak_depth_and_duration(self) -> None:
        actual = self.runner.ulcer_index(
            [Decimal("100"), Decimal("90"), Decimal("81")]
        )
        expected = (Decimal("461") / Decimal("3")).sqrt()
        self.assertEqual(actual, expected)
        self.assertEqual(
            self.runner.ulcer_index(
                [Decimal("100"), Decimal("110"), Decimal("120")]
            ),
            Decimal("0"),
        )

    def test_first_lagged_median_excludes_current_ulcer_value(self) -> None:
        start = datetime(2019, 1, 1)
        daily = [
            Point(start + timedelta(days=index + 1), Decimal(100 + index % 17))
            for index in range(21 + 252)
        ]
        points = self.runner.build_ulcer_points(daily, 21, Base())
        self.assertIsNone(points[251].lagged_median)
        expected = Base.percentile(
            [point.value for point in points[:252]], Decimal("0.5")
        )
        self.assertEqual(points[252].lagged_median, expected)

    def test_target_relation_is_strict_and_never_below_half(self) -> None:
        when = datetime(2020, 1, 1)
        points = [
            self.runner.UlcerPoint(when, Decimal("2"), Decimal("2")),
            self.runner.UlcerPoint(
                when + timedelta(days=1), Decimal("2.0001"), Decimal("2")
            ),
        ]
        targets = self.runner.build_targets(points)
        self.assertEqual(list(targets.values()), [Decimal("1"), Decimal("0.5")])

    def test_all_frozen_source_hashes_match(self) -> None:
        for path, expected in self.runner.EXPECTED_SOURCE_HASHES.items():
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(actual, expected, str(path))


if __name__ == "__main__":
    unittest.main()
