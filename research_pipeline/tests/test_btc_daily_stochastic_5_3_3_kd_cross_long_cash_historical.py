from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import json
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_stochastic_5_3_3_kd_cross_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-stochastic-5-3-3-kd-cross-long-cash-historical.v1.manifest.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("stochastic_runner_test", RUNNER)
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


class StochasticRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def points(self, closes: list[str], high: str = "10", low: str = "0") -> list[Point]:
        start = datetime(2020, 1, 2)
        return [Point(start + timedelta(days=i), Decimal(high), Decimal(low), Decimal(value)) for i, value in enumerate(closes)]

    def test_manifest_accepts_exact_three_variant_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_manifest_rejects_neighbor_change(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rejection_only_neighbors"][1]["kd_gap_threshold"] = "20"
        with self.assertRaisesRegex(self.runner.ResearchReject, "NEIGHBORS"):
            self.runner.validate_manifest(manifest)

    def test_flat_range_maps_fast_k_and_gap_to_zero_cash(self) -> None:
        points = [Point(datetime(2020, 1, 2) + timedelta(days=i), Decimal("5"), Decimal("5"), Decimal("5")) for i in range(9)]
        targets, values = self.runner.stochastic_targets(points, 5, 3, 3, 0)
        self.assertEqual(values, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_accelerating_range_position_produces_positive_kd_gap(self) -> None:
        targets, values = self.runner.stochastic_targets(self.points(["1", "2", "3", "4", "5", "4", "5", "7", "9"]), 5, 3, 3, 0)
        self.assertGreater(values[0], Decimal("0"))
        self.assertEqual(list(targets.values()), [True])

    def test_primary_boundary_is_strict(self) -> None:
        targets, values = self.runner.stochastic_targets(self.points(["5"] * 9), 5, 3, 3, 0)
        self.assertEqual(values, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_rejects_unregistered_parameter(self) -> None:
        with self.assertRaisesRegex(self.runner.ResearchReject, "STOCHASTIC_POLICY"):
            self.runner.stochastic_targets(self.points(["5"] * 18), 14, 3, 3, 0)

    def test_gate_labels_preserve_frozen_stochastic_thresholds(self) -> None:
        class GateSupport:
            @staticmethod
            def evaluate_gates(*_args):
                return (
                    {
                        "primary_rsi14_gate": True,
                        "neighbor_rsi14_gt45_gate": False,
                        "neighbor_rsi14_gt55_gate": False,
                    },
                    ["neighbor_rsi14_gt45_gate", "neighbor_rsi14_gt55_gate"],
                    {"PRIMARY_RSI14_BREADTH": 1},
                )

        gates, failed, breadth = self.runner.evaluate_gates(
            object(), GateSupport(), {}, {}, {}, {},
        )
        self.assertEqual(
            set(gates),
            {
                "primary_stochastic_5_3_3_kd_gate",
                "neighbor_stochastic_5_3_3_kd_gt_negative10_gate",
                "neighbor_stochastic_5_3_3_kd_gt_positive10_gate",
            },
        )
        self.assertEqual(
            failed,
            [
                "neighbor_stochastic_5_3_3_kd_gt_negative10_gate",
                "neighbor_stochastic_5_3_3_kd_gt_positive10_gate",
            ],
        )
        self.assertEqual(breadth, {"PRIMARY_STOCHASTIC_5_3_3_KD_BREADTH": 1})


if __name__ == "__main__":
    unittest.main()
