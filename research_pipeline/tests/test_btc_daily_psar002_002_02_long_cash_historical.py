from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_daily_psar002_002_02_long_cash_historical.py"
PRIOR_PATH = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-psar002-002-02-long-cash-primary-prior.v1.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("psar_historical_runner_test", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("runner import failed")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


runner = load_runner()
D = Decimal


@dataclass(frozen=True)
class Point:
    close_time: datetime
    high: D
    low: D
    close: D


def point(day: int, high: str, low: str, close: str) -> Point:
    return Point(datetime(2020, 1, 1) + timedelta(days=day + 1), D(high), D(low), D(close))


class BtcDailyPsarHistoricalTest(unittest.TestCase):
    def test_known_uptrend_acceleration_and_reversal_path(self) -> None:
        daily = [
            point(0, "10", "8", "9"),
            point(1, "11", "9", "10"),
            point(2, "12", "10", "11"),
            point(3, "13", "11", "12"),
            point(4, "10", "7", "8"),
            point(5, "9", "6", "7"),
        ]
        states = runner.parabolic_sar_states(daily, 2, 20)
        self.assertEqual(len(states), 5)
        self.assertEqual(
            [(value.bullish, value.sar, value.extreme_point, value.acceleration) for value in states],
            [
                (True, D("8"), D("11"), D("0.02")),
                (True, D("8"), D("12"), D("0.04")),
                (True, D("8.16"), D("13"), D("0.06")),
                (False, D("13"), D("7"), D("0.02")),
                (False, D("13"), D("6"), D("0.04")),
            ],
        )

    def test_acceleration_is_capped_at_default_maximum(self) -> None:
        daily = [point(0, "10", "8", "9"), point(1, "11", "9", "10")]
        for day in range(2, 20):
            daily.append(point(day, str(10 + day), str(8 + day), str(9 + day)))
        states = runner.parabolic_sar_states(daily, 2, 20)
        self.assertTrue(all(value.bullish for value in states))
        self.assertEqual(states[-1].acceleration, D("0.20"))
        self.assertLessEqual(max(value.acceleration for value in states), D("0.20"))

    def test_targets_are_available_only_after_two_complete_days(self) -> None:
        daily = [
            point(0, "10", "8", "9"),
            point(1, "11", "9", "10"),
            point(2, "12", "10", "11"),
            point(3, "10", "7", "8"),
        ]
        targets = runner.target_by_execution_time(daily, 2, 20)
        self.assertNotIn(daily[0].close_time, targets)
        self.assertTrue(targets[daily[1].close_time])
        self.assertTrue(targets[daily[2].close_time])
        self.assertFalse(targets[daily[3].close_time])

    def test_prior_excludes_outcome_and_future_sources(self) -> None:
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
        serialized = json.dumps(prior, sort_keys=True).lower()
        self.assertNotIn("2025-01-01t00:00:00", serialized)
        self.assertNotIn("candidate_total_return", serialized)
        self.assertNotIn("failed_gates", serialized)
        self.assertEqual(
            prior["fingerprint_boundary"]["duplicate_family_key"],
            "btc-daily-psar-start-increment-001-002-003-max02-long-cash",
        )


if __name__ == "__main__":
    unittest.main()
