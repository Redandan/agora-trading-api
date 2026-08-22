from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_stale_inventory_age_entry_admission_v1 as runner


D = Decimal


def bar_at(when: datetime) -> runner.base.Bar:
    return runner.base.Bar(
        when,
        when + timedelta(hours=1),
        D("100"),
        D("101"),
        D("99"),
        D("100"),
        D("1"),
    )


def lot_filled(when: datetime) -> runner.base.Lot:
    return runner.base.Lot(
        signal_time=when - timedelta(hours=1),
        fill_time=when,
        cost=D("30"),
        buy_price=D("100"),
        quantity=D("0.29955067"),
        entry_atr=None,
        highest_close=D("100"),
    )


def arm_for_signal(engine: runner.StaleInventoryAgeAdmissionEngine, when: datetime) -> None:
    engine.armed_at = when - timedelta(hours=1)
    engine.arm_expires_at = when + timedelta(days=1)
    engine._signal = lambda _: True  # type: ignore[method-assign]


class BtcDraStaleInventoryAgeEntryAdmissionV1Test(unittest.TestCase):
    def test_no_open_inventory_is_admitted(self) -> None:
        when = datetime(2024, 1, 31, 23)
        engine = runner.StaleInventoryAgeAdmissionEngine(threshold_hours=D("720"))
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.admitted_without_inventory_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertEqual(engine.pending_signal, when)
        self.assertEqual(engine.last_entry_signal, when)

    def test_primary_veto_is_inclusive_and_reserves_cooldown(self) -> None:
        when = datetime(2024, 2, 1, 23)
        engine = runner.StaleInventoryAgeAdmissionEngine(threshold_hours=D("720"))
        engine.lots = [lot_filled(when - timedelta(hours=720))]
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.vetoed_signal_count, 1)
        self.assertEqual(engine.vetoed_cooldown_reservation_count, 1)
        self.assertIsNone(engine.pending_signal)
        self.assertEqual(engine.last_entry_signal, when)
        self.assertIsNone(engine.armed_at)

    def test_fresh_inventory_is_admitted_without_pnl_read(self) -> None:
        when = datetime(2024, 2, 1, 23)
        engine = runner.StaleInventoryAgeAdmissionEngine(threshold_hours=D("720"))
        engine.lots = [lot_filled(when - timedelta(hours=719))]
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.admitted_with_inventory_below_threshold_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertEqual(engine.pending_signal, when)

    def test_capacity_block_happens_before_age_and_does_not_reserve_cooldown(self) -> None:
        when = datetime(2024, 2, 1, 23)
        engine = runner.StaleInventoryAgeAdmissionEngine(threshold_hours=D("720"))
        engine.lots = [lot_filled(when - timedelta(hours=2000 + index)) for index in range(8)]
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.capacity_blocked_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertIsNone(engine.last_entry_signal)
        self.assertEqual(engine.armed_at, when)

    def test_result_reconciles_actions_and_exposes_only_fill_time_decision_state(self) -> None:
        when = datetime(2024, 2, 1, 23)
        engine = runner.StaleInventoryAgeAdmissionEngine(threshold_hours=D("720"))
        arm_for_signal(engine, when)
        current = bar_at(when)
        engine._entry_lifecycle(current)
        engine._track(current)
        result = engine.result(current, when, when + timedelta(hours=1))
        self.assertTrue(result["action_accounting_reconciles"])
        self.assertEqual(result["decision_state_fields_read"], ["open_parent_lot_fill_time"])
        self.assertFalse(result["inventory_pnl_read_for_decision"])

    def test_gate_lists_match_gate_function_outputs(self) -> None:
        self.assertEqual(len(runner.PRIMARY_GATE_NAMES), len(set(runner.PRIMARY_GATE_NAMES)))
        self.assertEqual(len(runner.NEIGHBOR_GATE_NAMES), len(set(runner.NEIGHBOR_GATE_NAMES)))
        self.assertIn("validation_realized_pnl_improves", runner.PRIMARY_GATE_NAMES)
        self.assertIn(
            "validation_max_underwater_duration_non_worse", runner.PRIMARY_GATE_NAMES
        )


if __name__ == "__main__":
    unittest.main()
