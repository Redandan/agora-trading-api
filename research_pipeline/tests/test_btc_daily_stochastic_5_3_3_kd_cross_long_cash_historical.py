from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_stochastic_5_3_3_kd_cross_long_cash_historical.py"


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


if __name__ == "__main__":
    unittest.main()
