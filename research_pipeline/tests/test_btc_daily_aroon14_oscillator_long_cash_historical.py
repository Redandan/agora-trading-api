from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_aroon14_oscillator_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-aroon14-oscillator-long-cash-historical.v1.manifest.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("aroon14_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass(frozen=True)
class Point:
    close_time: datetime
    high: Decimal
    low: Decimal


class Aroon14RunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def points(self, highs: list[str], lows: list[str]) -> list[Point]:
        start = datetime(2020, 1, 2)
        return [
            Point(
                start + timedelta(days=index),
                Decimal(high),
                Decimal(low),
            )
            for index, (high, low) in enumerate(zip(highs, lows, strict=True))
        ]

    def test_manifest_accepts_exact_three_variant_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_manifest_rejects_neighbor_change(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rejection_only_neighbors"][1][
            "oscillator_long_threshold"
        ] = "50"
        with self.assertRaisesRegex(self.runner.ResearchReject, "NEIGHBORS"):
            self.runner.validate_manifest(manifest)

    def test_newest_high_and_oldest_low_produce_positive_one_hundred(self) -> None:
        highs = [str(value) for value in range(1, 16)]
        lows = [str(value) for value in range(1, 16)]
        targets, values = self.runner.aroon_targets(self.points(highs, lows), 14, 0)
        self.assertEqual(values, [Decimal("100")])
        self.assertEqual(list(targets.values()), [True])

    def test_oldest_high_and_newest_low_produce_negative_one_hundred(self) -> None:
        highs = [str(value) for value in range(15, 0, -1)]
        lows = [str(value) for value in range(15, 0, -1)]
        targets, values = self.runner.aroon_targets(self.points(highs, lows), 14, 0)
        self.assertEqual(values, [Decimal("-100")])
        self.assertEqual(list(targets.values()), [False])

    def test_equal_extrema_choose_most_recent_occurrence(self) -> None:
        targets, values = self.runner.aroon_targets(
            self.points(["10"] * 15, ["5"] * 15), 14, 0
        )
        self.assertEqual(values, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_threshold_is_strict(self) -> None:
        highs = ["10"] * 11 + ["11"] + ["10"] * 3
        lows = ["5"] * 15
        targets, values = self.runner.aroon_targets(self.points(highs, lows), 14, -25)
        self.assertEqual(values, [Decimal("-21.42857142857142857142857142857143")])
        self.assertEqual(list(targets.values()), [True])

    def test_rejects_unregistered_threshold(self) -> None:
        with self.assertRaisesRegex(self.runner.ResearchReject, "AROON_POLICY"):
            self.runner.aroon_targets(
                self.points(["10"] * 15, ["5"] * 15), 14, 50
            )


if __name__ == "__main__":
    unittest.main()
