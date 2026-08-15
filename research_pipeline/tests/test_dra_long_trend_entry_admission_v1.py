from __future__ import annotations

from collections import deque
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_long_trend_entry_admission_v1 as runner


D = Decimal


def _bar(day: int, price: str) -> runner.base.Bar:
    opened = datetime(2026, 1, 1, 23, tzinfo=UTC) + timedelta(days=day)
    value = D(price)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=value,
        high=value,
        low=value,
        close=value,
        volume=D("1"),
    )


class LongTrendEntryAdmissionRunnerTest(unittest.TestCase):
    def test_fixed_capacity_matches_whole_lot_reference_economics(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        self.assertEqual(engine.initial_equity, D("250.00000000"))
        self.assertEqual(engine.cap, D("240.00000000"))
        self.assertEqual(engine.slot_count_limit, 8)

    def test_feature_uses_prior_200_complete_days_only(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        engine.prior_daily_closes = deque([D("100")] * 200, maxlen=200)
        engine.feature_warmup(_bar(200, "101"))
        self.assertEqual(engine.current_prior_sma200, D("100"))
        self.assertEqual(engine.current_complete_day_close, D("101"))
        self.assertEqual(engine.prior_daily_closes[-1], D("101"))

    def test_parent_signal_above_trend_is_admitted(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        engine.current_prior_sma200 = D("100")
        engine.current_complete_day_close = D("101")
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertTrue(engine._signal(_bar(200, "101")))
        self.assertEqual(engine.parent_signal_count, 1)
        self.assertEqual(engine.admitted_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)

    def test_parent_signal_at_or_below_trend_is_vetoed(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        engine.current_prior_sma200 = D("100")
        engine.current_complete_day_close = D("100")
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertFalse(engine._signal(_bar(200, "100")))
        self.assertEqual(engine.parent_signal_count, 1)
        self.assertEqual(engine.admitted_signal_count, 0)
        self.assertEqual(engine.vetoed_signal_count, 1)

    def test_hard_inception_fallback_preserves_parent_signal(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertTrue(engine._signal(_bar(0, "100")))
        self.assertEqual(engine.hard_inception_fallback_admit_count, 1)
        self.assertEqual(engine.admitted_signal_count, 1)

    def test_result_exposes_admission_and_inventory_path_without_a_gate(self) -> None:
        engine = runner.LongTrendEntryAdmissionEngine()
        bar = _bar(0, "100")
        engine._track(bar)
        result = engine.result(bar, bar.open_time, bar.close_time)
        self.assertEqual(result["runner_identity"], runner.IDENTITY)
        self.assertTrue(result["admission_accounting_reconciles"])
        self.assertIn("inventory_path", result)
        self.assertIn("terminal_inventory", result)
        self.assertNotIn("qualified", result)
        self.assertNotIn("pass", result)

    def test_paired_deltas_remain_gate_free(self) -> None:
        common = {
            "start": "a",
            "end_exclusive": "b",
            "initial_equity_usdt": "250",
            "slot_capacity_usdt": "240",
            "realized_usdt": "1",
            "unrealized_usdt": "0",
            "total_pnl_usdt": "1",
            "max_drawdown_pct": "2",
            "avg_equity_utilization_pct": "10",
            "peak_equity_utilization_pct": "20",
            "blocked_entries": 0,
            "buy_count": 1,
        }
        result = runner.paired_deltas(common, dict(common, total_pnl_usdt="2"))
        self.assertEqual(result["deltas"]["total_pnl_usdt_delta"], "1.00000000")
        self.assertNotIn("pass", result)


if __name__ == "__main__":
    unittest.main()
