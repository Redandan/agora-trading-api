from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT / "research" / "btc_monthly_30d_volatility_target_40pct_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-30d-volatility-target-40pct-historical.v1.manifest.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location("tested_btc_volatility_target", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcMonthlyVolatilityTargetHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_manifest_freezes_exact_single_variant_policy(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(
            manifest["strategy_policy"]["decision_feature"],
            "SQRT_365_TIMES_MEAN_OF_SQUARED_LOG_CLOSE_RETURNS_FOR_EXACTLY_30_COMPLETE_UTC_DAYS",
        )
        self.assertIn(
            "0_40_DIVIDED_BY_DECISION_FEATURE",
            manifest["strategy_policy"]["target_exposure"],
        )

    def test_realized_volatility_uses_exactly_thirty_log_returns(self) -> None:
        d = Decimal
        closes = [(d("0.01") * d(index)).exp() for index in range(31)]
        actual = self.runner.realized_volatility(closes)
        expected = d("0.01") * d("365").sqrt()
        self.assertLess(abs(actual - expected), d("1e-45"))
        with self.assertRaises(self.runner.ResearchReject):
            self.runner.realized_volatility(closes[:-1])

    def test_fifo_round_trip_preserves_realized_pnl_and_holding(self) -> None:
        d = Decimal
        entry = datetime(2024, 1, 1)
        lots = []
        cash, fees, turnover, realized, side, slices, holds = self.runner.execute_target(
            lots=lots,
            cash=d("1"),
            target_weight=d("0.5"),
            open_price=d("100"),
            fee_rate=d("0"),
            slippage=d("0"),
            execution_time=entry,
        )
        self.assertEqual(side, "BUY")
        self.assertEqual(cash, d("0.5"))
        self.assertEqual(fees, d("0"))
        self.assertEqual(turnover, d("0.5"))
        self.assertEqual(realized, d("0"))
        self.assertEqual(slices, [])
        self.assertEqual(holds, [])

        cash, fees, turnover, realized, side, slices, holds = self.runner.execute_target(
            lots=lots,
            cash=cash,
            target_weight=d("0"),
            open_price=d("120"),
            fee_rate=d("0"),
            slippage=d("0"),
            execution_time=entry + timedelta(days=1),
        )
        self.assertEqual(side, "SELL")
        self.assertEqual(cash, d("1.1"))
        self.assertEqual(fees, d("0"))
        self.assertEqual(turnover, d("0.6"))
        self.assertEqual(realized, d("0.1"))
        self.assertEqual(slices, [d("0.1")])
        self.assertEqual(holds, [d("24.0")])
        self.assertEqual(lots, [])


if __name__ == "__main__":
    unittest.main()
