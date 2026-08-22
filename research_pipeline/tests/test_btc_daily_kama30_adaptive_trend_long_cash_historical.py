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
RUNNER = REPO_ROOT / "research" / "btc_daily_kama30_adaptive_trend_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-kama30-adaptive-trend-long-cash-historical.v1.manifest.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("kama_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass(frozen=True)
class Point:
    close_time: datetime
    close: Decimal


class KamaRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def points(self, closes: list[str]) -> list[Point]:
        start = datetime(2020, 1, 2)
        return [Point(start + timedelta(days=i), Decimal(value)) for i, value in enumerate(closes)]

    def test_manifest_accepts_exact_three_variant_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_manifest_rejects_neighbor_change(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rejection_only_neighbors"][1]["efficiency_period"] = 50
        with self.assertRaisesRegex(self.runner.ResearchReject, "NEIGHBORS"):
            self.runner.validate_manifest(manifest)

    def test_monotonic_path_uses_fast_talib_smoothing_constant(self) -> None:
        closes = [str(i) for i in range(1, 22)]
        targets, gaps = self.runner.kama_targets(self.points(closes), 20)
        expected_kama = Decimal("20") + (Decimal("21") - Decimal("20")) * (Decimal("4") / Decimal("9"))
        expected_gap = Decimal("100") * (Decimal("21") - expected_kama) / expected_kama
        self.assertEqual(gaps, [expected_gap])
        self.assertEqual(list(targets.values()), [True])

    def test_flat_path_maps_zero_volatility_to_fast_ratio_and_cash(self) -> None:
        targets, gaps = self.runner.kama_targets(self.points(["5"] * 21), 20)
        self.assertEqual(gaps, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_next_value_rolls_the_efficiency_window(self) -> None:
        closes = [str(i) for i in range(1, 22)] + ["20"]
        targets, gaps = self.runner.kama_targets(self.points(closes), 20)
        self.assertEqual(len(gaps), 2)
        self.assertEqual(len(targets), 2)
        self.assertLess(gaps[-1], Decimal("0"))
        self.assertFalse(list(targets.values())[-1])

    def test_rejects_unregistered_period_or_constants(self) -> None:
        points = self.points(["5"] * 50)
        with self.assertRaisesRegex(self.runner.ResearchReject, "KAMA_POLICY"):
            self.runner.kama_targets(points, 25)
        with self.assertRaisesRegex(self.runner.ResearchReject, "KAMA_POLICY"):
            self.runner.kama_targets(points, 30, 3, 30)

    def test_gate_labels_preserve_frozen_kama_periods(self) -> None:
        class GateSupport:
            @staticmethod
            def evaluate_gates(*_args):
                return (
                    {"primary_rsi14_gate": True, "neighbor_rsi14_gt45_gate": False, "neighbor_rsi14_gt55_gate": False},
                    ["neighbor_rsi14_gt45_gate", "neighbor_rsi14_gt55_gate"],
                    {"PRIMARY_RSI14_BREADTH": 1},
                )

        gates, failed, breadth = self.runner.evaluate_gates(object(), GateSupport(), {}, {}, {}, {})
        self.assertEqual(set(gates), {"primary_kama30_gate", "neighbor_kama20_gate", "neighbor_kama40_gate"})
        self.assertEqual(failed, ["neighbor_kama20_gate", "neighbor_kama40_gate"])
        self.assertEqual(breadth, {"PRIMARY_KAMA30_BREADTH": 1})


if __name__ == "__main__":
    unittest.main()
