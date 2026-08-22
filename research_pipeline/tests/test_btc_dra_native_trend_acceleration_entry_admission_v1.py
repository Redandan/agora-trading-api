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

import btc_dra_native_trend_acceleration_entry_admission_v1 as runner


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
        entry_atr=D("10"),
        highest_close=D("100"),
    )


def arm_for_signal(
    engine: runner.NativeTrendAccelerationAdmissionEngine,
    signal_time: datetime,
) -> None:
    engine.armed_at = signal_time - timedelta(days=1)
    engine.arm_expires_at = engine.armed_at + timedelta(days=30)
    engine._signal = lambda _: True  # type: ignore[method-assign]


def seed_feature(
    engine: runner.NativeTrendAccelerationAdmissionEngine,
    signal_time: datetime,
    *,
    ema_t_minus_10: D,
    ema_t_minus_5: D,
    ema_t: D,
) -> None:
    for offset in range(11):
        value = D("102")
        if offset == 0:
            value = ema_t_minus_10
        elif offset == 5:
            value = ema_t_minus_5
        elif offset == 10:
            value = ema_t
        engine.daily_points.append(
            runner.base.DailyPoint(
                signal_time - timedelta(days=10 - offset),
                value,
                value,
                value,
                value,
                D("10"),
            )
        )
    engine.atr14 = D("10")


class BtcDraNativeTrendAccelerationEntryAdmissionV1Test(unittest.TestCase):
    def test_accelerating_signal_is_admitted_at_primary_threshold(self) -> None:
        when = datetime(2024, 1, 11, 23)
        engine = runner.NativeTrendAccelerationAdmissionEngine(threshold_atr=D("0"))
        arm_for_signal(engine, when)
        seed_feature(
            engine,
            when,
            ema_t_minus_10=D("100"),
            ema_t_minus_5=D("105"),
            ema_t=D("112"),
        )
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.admitted_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertEqual(engine.pending_signal, when)

    def test_decelerating_signal_is_vetoed_and_reserves_cooldown(self) -> None:
        when = datetime(2024, 1, 11, 23)
        engine = runner.NativeTrendAccelerationAdmissionEngine(threshold_atr=D("0"))
        arm_for_signal(engine, when)
        seed_feature(
            engine,
            when,
            ema_t_minus_10=D("100"),
            ema_t_minus_5=D("105"),
            ema_t=D("108"),
        )
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.vetoed_signal_count, 1)
        self.assertEqual(engine.vetoed_cooldown_reservation_count, 1)
        self.assertIsNone(engine.pending_signal)
        self.assertEqual(engine.last_entry_signal, when)

    def test_unavailable_feature_fails_closed_and_reserves_cooldown(self) -> None:
        when = datetime(2024, 1, 11, 23)
        engine = runner.NativeTrendAccelerationAdmissionEngine(threshold_atr=D("0"))
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.feature_unavailable_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 1)
        self.assertEqual(engine.last_entry_signal, when)

    def test_capacity_block_precedes_feature_and_does_not_reserve_cooldown(self) -> None:
        when = datetime(2024, 1, 11, 23)
        engine = runner.NativeTrendAccelerationAdmissionEngine(threshold_atr=D("0"))
        engine.lots = [lot_filled(when - timedelta(hours=index + 1)) for index in range(8)]
        arm_for_signal(engine, when)
        engine._entry_lifecycle(bar_at(when))
        self.assertEqual(engine.capacity_blocked_signal_count, 1)
        self.assertEqual(engine.feature_available_signal_count, 0)
        self.assertEqual(engine.feature_unavailable_signal_count, 0)
        self.assertEqual(engine.vetoed_signal_count, 0)
        self.assertIsNone(engine.last_entry_signal)
        self.assertEqual(engine.armed_at, when)

    def test_result_reconciles_actions_and_declares_only_price_feature_state(self) -> None:
        when = datetime(2024, 1, 11, 23)
        engine = runner.NativeTrendAccelerationAdmissionEngine(threshold_atr=D("0"))
        arm_for_signal(engine, when)
        seed_feature(
            engine,
            when,
            ema_t_minus_10=D("100"),
            ema_t_minus_5=D("105"),
            ema_t=D("112"),
        )
        current = bar_at(when)
        engine._entry_lifecycle(current)
        engine._track(current)
        result = engine.result(current, when, when + timedelta(hours=1))
        self.assertTrue(result["action_accounting_reconciles"])
        self.assertEqual(
            result["decision_state_fields_read"],
            [
                "complete_day_ema20_t",
                "complete_day_ema20_t_minus_5d",
                "complete_day_ema20_t_minus_10d",
                "updated_atr14_t",
            ],
        )
        self.assertFalse(result["inventory_or_pnl_state_read_for_decision"])

    def test_frozen_gate_contract_reuses_exact_verified_path_risk_lattice(self) -> None:
        self.assertEqual(runner.PRIMARY_GATE_NAMES, runner.economic_common.PRIMARY_GATE_NAMES)
        self.assertEqual(runner.NEIGHBOR_GATE_NAMES, runner.economic_common.NEIGHBOR_GATE_NAMES)
        self.assertIn("validation_realized_pnl_improves", runner.PRIMARY_GATE_NAMES)
        self.assertIn(
            "validation_max_underwater_duration_non_worse", runner.PRIMARY_GATE_NAMES
        )


if __name__ == "__main__":
    unittest.main()
