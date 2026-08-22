from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.liquid_crypto_cross_sectional_momentum_long_only_v1 import (
    Bar,
    PortfolioEngine,
    build_targets,
    paired_summary,
)


D = Decimal


def synthetic_bars(symbols: int = 20) -> tuple[dict[str, dict[date, Bar]], list[str]]:
    start = date(2018, 12, 1)
    end = date(2020, 2, 1)
    cohort = [f"A{index:02d}USDT" for index in range(symbols)]
    result: dict[str, dict[date, Bar]] = {}
    for index, symbol in enumerate(cohort):
        rows: dict[date, Bar] = {}
        day = start
        offset = D(index) / D(10000)
        counter = 0
        while day < end:
            price = D("100") + D(counter) * (D("0.01") + offset)
            rows[day] = Bar(day, price, price + 1, price - 1, price + D("0.5"), D(1000 + index))
            counter += 1
            day += timedelta(days=1)
        result[symbol] = rows
    return result, cohort


class LiquidCryptoCrossSectionalMomentumLongOnlyV1Test(unittest.TestCase):
    def test_targets_use_exact_top20_and_top6_with_causal_history(self) -> None:
        bars, cohort = synthetic_bars()
        targets = build_targets(bars, cohort, date(2020, 1, 1), date(2020, 1, 3))
        first = targets[date(2020, 1, 1)]
        self.assertEqual(20, first["eligible_count"])
        self.assertEqual(20, len(first["parent"]))
        self.assertEqual(6, len(first["candidate"]))
        self.assertEqual(
            ["A19USDT", "A18USDT", "A17USDT", "A16USDT", "A15USDT", "A14USDT"],
            first["candidate"],
        )

    def test_missing_current_execution_bar_fails_closed_for_that_asset(self) -> None:
        bars, cohort = synthetic_bars()
        del bars["A19USDT"][date(2020, 1, 1)]
        targets = build_targets(bars, cohort, date(2020, 1, 1), date(2020, 1, 2))
        first = targets[date(2020, 1, 1)]
        self.assertEqual(19, first["eligible_count"])
        self.assertEqual([], first["candidate"])
        self.assertEqual([], first["parent"])

    def test_trade_costs_and_total_ledger_reconcile(self) -> None:
        bars, cohort = synthetic_bars()
        engine = PortfolioEngine(D("0.0010"), D("0.0005"))
        day = date(2020, 1, 1)
        engine.rebalance(day, cohort[:6], bars)
        engine.track_close(day, bars)
        result = engine.result(day + timedelta(days=1))
        self.assertTrue(result["ledger_reconciled"])
        self.assertEqual(6, result["terminal_position_count"])
        self.assertGreater(D(result["trading_cost_usdt"]), 0)
        self.assertEqual(
            D(result["total_pnl_usdt"]),
            D(result["realized_pnl_usdt"]) + D(result["unrealized_pnl_usdt"]),
        )

    def test_paired_summary_reports_asset_concentration(self) -> None:
        candidate = {
            "total_pnl_usdt": "20", "total_return_pct": "0.2", "max_drawdown_pct": "2",
            "zero_recovery_total_pnl_usdt": "20", "asset_total_pnl_usdt": {"A": "10", "B": "10"},
        }
        parent = {
            "total_pnl_usdt": "0", "total_return_pct": "0", "max_drawdown_pct": "1",
            "zero_recovery_total_pnl_usdt": "0", "asset_total_pnl_usdt": {"A": "0", "B": "0"},
        }
        paired = paired_summary(candidate, parent)
        self.assertEqual("50.000000", paired["top_asset_positive_incremental_contribution_pct"])
        self.assertEqual(2, paired["positive_incremental_asset_count"])


if __name__ == "__main__":
    unittest.main()
