from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_variable_lot_sizing_v1 as runner


D = Decimal


class BtcDraVariableLotSizingV1Test(unittest.TestCase):
    def test_risk_adjusted_score_uses_total_pnl_and_drawdown(self) -> None:
        self.assertEqual(
            runner.risk_adjusted_score(
                {"total_pnl_usdt": "20", "max_drawdown_pct": "5"}
            ),
            D("4"),
        )

    def test_terminal_inventory_helpers_use_variable_costs(self) -> None:
        result = {
            "terminal_inventory": [
                {"cost_usdt": "15", "unrealized_pnl_usdt": "-2"},
                {"cost_usdt": "23.5", "unrealized_pnl_usdt": "1"},
            ]
        }
        self.assertEqual(runner.terminal_inventory_cost(result), D("38.5"))
        self.assertEqual(runner.terminal_inventory_unrealized(result), D("-1"))

    def test_variable_fill_uses_pending_cost_and_never_base_cost(self) -> None:
        engine = runner.VariableLotSizingEngine(
            normalized_variance={}, floor_usdt=D("15")
        )
        engine.pending_signal = datetime(2024, 1, 1, 23)
        engine.pending_lot_cost = D("15")
        engine.pending_atr = None
        bar = runner.base.Bar(
            datetime(2024, 1, 2, 0),
            datetime(2024, 1, 2, 1),
            D("100"),
            D("101"),
            D("99"),
            D("100"),
            D("1"),
        )
        engine._fill_buy(bar)
        self.assertEqual(engine.lots[0].cost, D("15.00000000"))
        self.assertEqual(engine.filled_scaled_lot_count, 1)
        self.assertEqual(engine.filled_lot_cost_usdt, D("15.00000000"))

    def test_terminal_result_overwrites_fixed_lot_open_cost(self) -> None:
        engine = runner.VariableLotSizingEngine(
            normalized_variance={}, floor_usdt=D("15")
        )
        engine.pending_signal = datetime(2024, 1, 1, 23)
        engine.pending_lot_cost = D("15")
        bar = runner.base.Bar(
            datetime(2024, 1, 2, 0),
            datetime(2024, 1, 2, 1),
            D("100"),
            D("101"),
            D("99"),
            D("100"),
            D("1"),
        )
        engine._fill_buy(bar)
        engine._track(bar)
        engine.parent_signal_count = 1
        engine.sized_signal_count = 1
        result = engine.result(bar, bar.open_time, bar.close_time)
        self.assertEqual(result["ending_open_cost_usdt"], "15.00000000")
        self.assertTrue(result["variable_sizing_accounting_reconciles"])


if __name__ == "__main__":
    unittest.main()
