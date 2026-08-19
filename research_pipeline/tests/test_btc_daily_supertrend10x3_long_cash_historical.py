from __future__ import annotations

import importlib.util
import sys
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT / "research" / "btc_daily_supertrend10x3_long_cash_historical.py"
)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "tested_btc_daily_supertrend10x3", RUNNER_PATH
    )
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcDailySupertrend10x3LongCashHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_frozen_non_outcome_source_bindings_match(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.ECONOMIC_SUPPORT_SOURCE),
            self.runner.EXPECTED_ECONOMIC_SUPPORT_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.REFERENCE_SOURCE),
            self.runner.EXPECTED_REFERENCE_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.PARSER_SOURCE),
            self.runner.EXPECTED_PARSER_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.PRIOR_SOURCE),
            self.runner.EXPECTED_PRIOR_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.HYPOTHESIS_SOURCE),
            self.runner.EXPECTED_HYPOTHESIS_SHA256,
        )

    def test_atr10_rma_and_ratcheted_bands_flip_only_after_crossing(self) -> None:
        d = Decimal
        start = datetime(2020, 1, 1)
        points = [
            self.runner.DailyPoint(
                close_time=start + timedelta(days=index + 1),
                high=d("101"),
                low=d("99"),
                close=d("100"),
            )
            for index in range(10)
        ]
        points.extend(
            [
                self.runner.DailyPoint(
                    close_time=start + timedelta(days=11),
                    high=d("108"),
                    low=d("106"),
                    close=d("107"),
                ),
                self.runner.DailyPoint(
                    close_time=start + timedelta(days=12),
                    high=d("105"),
                    low=d("103"),
                    close=d("104"),
                ),
                self.runner.DailyPoint(
                    close_time=start + timedelta(days=13),
                    high=d("99"),
                    low=d("97"),
                    close=d("98"),
                ),
            ]
        )

        targets = self.runner.target_by_execution_time(points, 10, d("3.0"))

        self.assertEqual(list(targets), [point.close_time for point in points[9:]])
        self.assertEqual(list(targets.values()), [False, True, True, False])

    def test_only_frozen_primary_and_rejection_neighbors_are_accepted(self) -> None:
        d = Decimal
        start = datetime(2020, 1, 1)
        points = [
            self.runner.DailyPoint(
                close_time=start + timedelta(days=index + 1),
                high=d("101"),
                low=d("99"),
                close=d("100"),
            )
            for index in range(12)
        ]

        for multiplier in (d("2.5"), d("3.0"), d("3.5")):
            self.assertEqual(
                len(self.runner.target_by_execution_time(points, 10, multiplier)), 3
            )
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:SUPERTREND_POLICY"
        ):
            self.runner.target_by_execution_time(points, 10, d("2.0"))
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:SUPERTREND_POLICY"
        ):
            self.runner.target_by_execution_time(points, 14, d("3.0"))


if __name__ == "__main__":
    unittest.main()
