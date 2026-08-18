from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import datetime
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT / "research" / "btc_turn_of_month_last_day_plus_three_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-turn-of-month-last-day-plus-three-long-cash-historical.v1.manifest.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location("tested_btc_turn_of_month", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcTurnOfMonthLastDayPlusThreeHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_manifest_freezes_exact_single_variant_policy(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(
            manifest["strategy_policy"]["decision_feature"],
            "UTC_CALENDAR_POSITION_LAST_DAY_OR_FIRST_THREE_DAYS_OF_MONTH",
        )
        self.assertEqual(
            manifest["strategy_policy"]["target_exposure"],
            "ONE_WHEN_IN_LONG_INTERVAL_OTHERWISE_ZERO",
        )

    def test_exact_calendar_interval_has_no_neighboring_days(self) -> None:
        long_hours = [
            datetime(2024, 1, 31, 0),
            datetime(2024, 2, 1, 12),
            datetime(2024, 2, 2, 12),
            datetime(2024, 2, 3, 23),
            datetime(2024, 2, 29, 0),
        ]
        cash_hours = [
            datetime(2024, 1, 30, 23),
            datetime(2024, 2, 4, 0),
            datetime(2024, 2, 28, 23),
        ]
        self.assertTrue(all(self.runner.is_turn_of_month_hour(value) for value in long_hours))
        self.assertTrue(
            all(not self.runner.is_turn_of_month_hour(value) for value in cash_hours)
        )

    def test_frozen_window_transition_counts(self) -> None:
        self.assertEqual(
            len(self.runner.expected_transition_times(*self.runner.DESIGN)), 73
        )
        self.assertEqual(
            len(self.runner.expected_transition_times(*self.runner.VALIDATION)), 49
        )
        self.assertTrue(
            all(
                len(self.runner.expected_transition_times(*window)) == 25
                for window in self.runner.ANNUAL.values()
            )
        )


if __name__ == "__main__":
    unittest.main()
