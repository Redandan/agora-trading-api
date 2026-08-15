from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_equal_capital_capacity_v1 as runner


D = Decimal


def _bar(hour: int, price: str = "100") -> runner.base.Bar:
    opened = datetime(2026, 1, 1, hour, tzinfo=UTC)
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


class EqualCapitalCapacityRunnerTest(unittest.TestCase):
    def test_capacity_must_be_bounded_whole_lots(self) -> None:
        with self.assertRaises(ValueError):
            runner.EqualCapitalCapacityEngine(
                slot_capacity_usdt=D("45"), initial_equity_usdt=D("60")
            )
        with self.assertRaises(ValueError):
            runner.EqualCapitalCapacityEngine(
                slot_capacity_usdt=D("90"), initial_equity_usdt=D("60")
            )

    def test_drawdown_uses_equal_initial_equity_not_slot_capacity(self) -> None:
        engine = runner.EqualCapitalCapacityEngine(
            slot_capacity_usdt=D("30"), initial_equity_usdt=D("60")
        )
        engine.realized = D("-6")
        engine._track(_bar(0))
        self.assertEqual(engine.max_drawdown, D("0.1"))

    def test_result_separates_slot_and_equity_utilization(self) -> None:
        engine = runner.EqualCapitalCapacityEngine(
            slot_capacity_usdt=D("30"), initial_equity_usdt=D("60")
        )
        opened = _bar(0)
        engine.lots.append(
            runner.base.Lot(
                signal_time=opened.open_time,
                fill_time=opened.open_time,
                cost=D("30"),
                buy_price=D("100"),
                quantity=D("0.299550674"),
                entry_atr=None,
                highest_close=D("100"),
            )
        )
        engine._track(opened)
        result = engine.result(
            opened,
            opened.open_time,
            opened.open_time + timedelta(hours=1),
        )
        self.assertEqual(result["reference_cap_usdt"], "60.00000000")
        self.assertEqual(result["slot_capacity_usdt"], "30.00000000")
        self.assertEqual(result["slot_count_limit"], 1)
        self.assertEqual(result["avg_slot_capacity_utilization_pct"], "100.000000")
        self.assertEqual(result["avg_equity_utilization_pct"], "50.000000")
        self.assertEqual(result["peak_equity_utilization_pct"], "50.000000")
        self.assertEqual(
            result["inventory_path"]["hour_counts_by_open_lot_count"], {"1": 1}
        )
        self.assertEqual(len(result["terminal_inventory"]), 1)

    def test_path_risk_counts_underwater_duration_and_timestamp(self) -> None:
        engine = runner.EqualCapitalCapacityEngine(
            slot_capacity_usdt=D("30"), initial_equity_usdt=D("60")
        )
        first = _bar(0)
        engine.realized = D("-6")
        engine._track(first)
        engine._track(_bar(1))
        engine.realized = D("1")
        engine._track(_bar(2))
        result = engine.result(
            _bar(2), first.open_time, first.open_time + timedelta(hours=3)
        )
        path = result["inventory_path"]
        self.assertEqual(path["underwater_hours"], 2)
        self.assertEqual(path["underwater_episode_count"], 1)
        self.assertEqual(path["maximum_underwater_duration_hours"], 2)
        self.assertEqual(path["maximum_drawdown_at"], first.open_time.isoformat())
        self.assertEqual(path["minimum_equity_usdt"], "54.00000000")

    def test_realized_lot_ledger_keeps_fill_and_holding_path(self) -> None:
        engine = runner.EqualCapitalCapacityEngine(
            slot_capacity_usdt=D("30"), initial_equity_usdt=D("60")
        )
        opened = _bar(0)
        fill_time = opened.open_time - timedelta(hours=10)
        engine.lots.append(
            runner.base.Lot(
                signal_time=fill_time - timedelta(hours=1),
                fill_time=fill_time,
                cost=D("30"),
                buy_price=D("1"),
                quantity=D("31"),
                entry_atr=None,
                highest_close=D("1"),
                exit_queued_at=fill_time + timedelta(hours=9),
            )
        )
        engine._fill_exits(_bar(0, "1"))
        self.assertEqual(len(engine.lots), 0)
        self.assertEqual(len(engine.realized_lot_ledger), 1)
        self.assertEqual(engine.realized_lot_ledger[0]["hold_hours"], 10.0)

    def test_equal_capital_deltas_reject_equity_mismatch(self) -> None:
        parent = {
            "start": "a",
            "end_exclusive": "b",
            "initial_equity_usdt": "60",
        }
        candidate = dict(parent, initial_equity_usdt="90")
        with self.assertRaises(ValueError):
            runner.equal_capital_deltas(parent, candidate)

    def test_equal_capital_deltas_are_paired_and_gate_free(self) -> None:
        parent = {
            "start": "a",
            "end_exclusive": "b",
            "initial_equity_usdt": "60",
            "slot_capacity_usdt": "30",
            "realized_usdt": "1",
            "unrealized_usdt": "-1",
            "total_pnl_usdt": "0",
            "max_drawdown_pct": "5",
            "avg_equity_utilization_pct": "25",
            "peak_equity_utilization_pct": "50",
            "blocked_entries": 4,
            "buy_count": 2,
        }
        candidate = dict(
            parent,
            slot_capacity_usdt="60",
            realized_usdt="3",
            total_pnl_usdt="2",
            max_drawdown_pct="6",
            avg_equity_utilization_pct="40",
            peak_equity_utilization_pct="100",
            blocked_entries=1,
            buy_count=5,
        )
        comparison = runner.equal_capital_deltas(parent, candidate)
        self.assertEqual(comparison["deltas"]["total_pnl_usdt_delta"], "2.00000000")
        self.assertEqual(comparison["deltas"]["max_drawdown_pct_delta"], "1.000000")
        self.assertEqual(comparison["deltas"]["blocked_entries_delta"], -3)
        self.assertNotIn("pass", comparison)


if __name__ == "__main__":
    unittest.main()
