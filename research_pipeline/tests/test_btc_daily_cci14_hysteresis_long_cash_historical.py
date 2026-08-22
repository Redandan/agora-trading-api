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
RUNNER = REPO_ROOT / "research" / "btc_daily_cci14_hysteresis_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-cci14-hysteresis-long-cash-historical.v1.manifest.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("cci_runner_test", RUNNER)
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
    close: Decimal


class CciRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def points(self, typical_prices: list[str]) -> list[Point]:
        start = datetime(2020, 1, 2)
        return [
            Point(
                start + timedelta(days=index),
                Decimal(value) + Decimal("1"),
                Decimal(value) - Decimal("1"),
                Decimal(value),
            )
            for index, value in enumerate(typical_prices)
        ]

    def test_manifest_accepts_exact_three_variant_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_manifest_rejects_asymmetric_neighbor(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rejection_only_neighbors"][1]["exit_threshold"] = "-100"
        with self.assertRaisesRegex(self.runner.ResearchReject, "NEIGHBORS"):
            self.runner.validate_manifest(manifest)

    def test_cci_matches_talib_formula_for_monotonic_window(self) -> None:
        points = self.points([str(value) for value in range(1, 15)])
        values = self.runner.cci_values(points)
        average = Decimal("7.5")
        mean_deviation = sum(
            (abs(Decimal(value) - average) for value in range(1, 15)),
            Decimal("0"),
        ) / Decimal("14")
        expected = (Decimal("14") - average) / (Decimal("0.015") * mean_deviation)
        self.assertEqual(values, [(points[-1].close_time, expected)])

    def test_flat_window_maps_zero_deviation_to_zero_and_stays_cash(self) -> None:
        points = self.points(["5"] * 14)
        self.assertEqual(self.runner.cci_values(points)[0][1], Decimal("0"))
        targets, values = self.runner.cci_hysteresis_targets(points, Decimal("100"))
        self.assertEqual(values, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_hysteresis_enters_holds_and_exits_only_on_strict_extremes(self) -> None:
        sequence = ["1"] * 13 + ["100", "50", "-100"]
        targets, values = self.runner.cci_hysteresis_targets(self.points(sequence), Decimal("100"))
        self.assertGreater(values[0], Decimal("100"))
        self.assertTrue(list(targets.values())[0])
        self.assertTrue(list(targets.values())[1])
        self.assertLess(values[2], Decimal("-100"))
        self.assertFalse(list(targets.values())[2])

    def test_rejects_unregistered_period_or_threshold(self) -> None:
        points = self.points(["5"] * 30)
        with self.assertRaisesRegex(self.runner.ResearchReject, "CCI_PERIOD"):
            self.runner.cci_values(points, 20)
        with self.assertRaisesRegex(self.runner.ResearchReject, "CCI_THRESHOLD"):
            self.runner.cci_hysteresis_targets(points, Decimal("50"))

    def test_gate_labels_preserve_frozen_cci_thresholds(self) -> None:
        class GateSupport:
            @staticmethod
            def evaluate_gates(*_args):
                return (
                    {"primary_rsi14_gate": True, "neighbor_rsi14_gt45_gate": False, "neighbor_rsi14_gt55_gate": False},
                    ["neighbor_rsi14_gt45_gate", "neighbor_rsi14_gt55_gate"],
                    {"PRIMARY_RSI14_BREADTH": 1},
                )

        gates, failed, breadth = self.runner.evaluate_gates(object(), GateSupport(), {}, {}, {}, {})
        self.assertEqual(set(gates), {"primary_cci14_100_gate", "neighbor_cci14_75_gate", "neighbor_cci14_125_gate"})
        self.assertEqual(failed, ["neighbor_cci14_75_gate", "neighbor_cci14_125_gate"])
        self.assertEqual(breadth, {"PRIMARY_CCI14_100_BREADTH": 1})


if __name__ == "__main__":
    unittest.main()
