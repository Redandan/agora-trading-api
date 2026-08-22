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

import btc_dra_signal_confirmation_latency_entry_admission_v1 as runner


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


def arm_for_signal(
    engine: runner.SignalConfirmationLatencyAdmissionEngine,
    signal_time: datetime,
    latency_hours: int,
) -> None:
    engine.armed_at = signal_time - timedelta(hours=latency_hours)
    engine.arm_expires_at = engine.armed_at + timedelta(days=30)
    engine._signal = lambda _: True  # type: ignore[method-assign]


class BtcDraSignalConfirmationLatencyEntryAdmissionV1Test(unittest.TestCase):
    def test_prompt_signal_is_admitted_without_price_or_inventory_state(self) -> None:
        when = datetime(2024, 1, 8, 23)
        engine = runner.SignalConfirmationLatencyAdmissionEngine(
            threshold_hours=D("168")
        )
        arm_for_signal(engine, when, 167)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.admitted_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertEqual(engine.pending_signal, when)

    def test_primary_veto_is_inclusive_and_reserves_cooldown(self) -> None:
        when = datetime(2024, 1, 8, 23)
        engine = runner.SignalConfirmationLatencyAdmissionEngine(
            threshold_hours=D("168")
        )
        arm_for_signal(engine, when, 168)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.vetoed_signal_count, 1)
        self.assertEqual(engine.vetoed_cooldown_reservation_count, 1)
        self.assertIsNone(engine.pending_signal)
        self.assertEqual(engine.last_entry_signal, when)
        self.assertIsNone(engine.armed_at)

    def test_capacity_block_precedes_latency_and_does_not_reserve_cooldown(self) -> None:
        when = datetime(2024, 1, 8, 23)
        engine = runner.SignalConfirmationLatencyAdmissionEngine(
            threshold_hours=D("168")
        )
        engine.lots = [lot_filled(when - timedelta(hours=index + 1)) for index in range(8)]
        arm_for_signal(engine, when, 500)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.capacity_blocked_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertIsNone(engine.last_entry_signal)
        self.assertEqual(engine.armed_at, when)

    def test_parent_expiry_prevents_signal_on_expiry_bar_and_rearms_unchanged(self) -> None:
        when = datetime(2024, 1, 31, 0)
        engine = runner.SignalConfirmationLatencyAdmissionEngine(
            threshold_hours=D("168")
        )
        engine.armed_at = when - timedelta(days=30)
        engine.arm_expires_at = when
        engine._signal = lambda _: True  # type: ignore[method-assign]
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.signal_opportunity_count, 0)
        self.assertEqual(engine.armed_at, when)
        self.assertEqual(engine.arm_expires_at, when + timedelta(days=30))

    def test_result_reconciles_actions_and_binds_only_lifecycle_clocks(self) -> None:
        when = datetime(2024, 1, 8, 23)
        engine = runner.SignalConfirmationLatencyAdmissionEngine(
            threshold_hours=D("168")
        )
        arm_for_signal(engine, when, 24)
        current = bar_at(when)
        engine._entry_lifecycle(current)
        engine._track(current)
        result = engine.result(current, when, when + timedelta(hours=1))
        self.assertTrue(result["action_accounting_reconciles"])
        self.assertEqual(
            result["decision_state_fields_read"], ["parent_armed_at", "signal_time"]
        )
        self.assertFalse(result["price_or_inventory_state_read_for_decision"])

    def test_frozen_gate_contract_reuses_exact_verified_path_risk_lattice(self) -> None:
        self.assertEqual(runner.PRIMARY_GATE_NAMES, runner.economic_common.PRIMARY_GATE_NAMES)
        self.assertEqual(runner.NEIGHBOR_GATE_NAMES, runner.economic_common.NEIGHBOR_GATE_NAMES)
        self.assertIn("validation_realized_pnl_improves", runner.PRIMARY_GATE_NAMES)
        self.assertIn(
            "validation_max_underwater_duration_non_worse", runner.PRIMARY_GATE_NAMES
        )


if __name__ == "__main__":
    unittest.main()
