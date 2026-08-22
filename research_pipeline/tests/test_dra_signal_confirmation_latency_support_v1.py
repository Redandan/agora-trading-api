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

import dra_signal_confirmation_latency_support_v1 as support


D = Decimal


class DraSignalConfirmationLatencySupportV1Test(unittest.TestCase):
    def test_state_uses_only_parent_armed_and_signal_clocks(self) -> None:
        signal_time = datetime(2024, 1, 8, 0)
        state = support.latency_state(
            armed_at=signal_time - timedelta(hours=168),
            signal_time=signal_time,
            open_lot_count=3,
        )
        self.assertEqual(state["confirmation_latency_hours"], "168.00000000")
        self.assertEqual(
            set(state),
            {
                "armed_at",
                "signal_time",
                "confirmation_latency_hours",
                "capacity_admissible",
            },
        )
        self.assertNotIn("price", str(state).lower())
        self.assertNotIn("pnl", str(state).lower())

    def test_latency_clock_requires_strictly_later_signal_inside_parent_window(self) -> None:
        when = datetime(2024, 1, 1, 0)
        with self.assertRaises(support.SupportReject):
            support.latency_state(armed_at=None, signal_time=when, open_lot_count=0)
        with self.assertRaises(support.SupportReject):
            support.latency_state(armed_at=when, signal_time=when, open_lot_count=0)
        with self.assertRaises(support.SupportReject):
            support.latency_state(
                armed_at=when,
                signal_time=when + timedelta(hours=720),
                open_lot_count=0,
            )

    def test_primary_veto_is_capacity_first_and_inclusive_at_168_hours(self) -> None:
        when = datetime(2024, 1, 8, 0)
        admissible = support.latency_state(
            armed_at=when - timedelta(hours=168),
            signal_time=when,
            open_lot_count=7,
        )
        blocked = support.latency_state(
            armed_at=when - timedelta(hours=500),
            signal_time=when,
            open_lot_count=8,
        )
        self.assertTrue(support.actionable_veto(admissible, D("168")))
        self.assertFalse(support.actionable_veto(admissible, D("168.00000001")))
        self.assertFalse(support.actionable_veto(blocked, D("168")))

    def test_variant_thresholds_are_frozen_and_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("72"), D("168"), D("336")],
        )
        state = {
            "capacity_admissible": True,
            "confirmation_latency_hours": "200",
        }
        self.assertEqual(
            [support.actionable_veto(state, threshold) for _, threshold, _ in support.VARIANTS],
            [True, True, False],
        )

    def test_action_fingerprints_are_distinct(self) -> None:
        fingerprints = {
            support.NEW_ACTION_FINGERPRINT,
            support.CLOSED_STALE_INVENTORY_AGE_FINGERPRINT,
            support.CLOSED_PRIOR_BREAKOUT_FINGERPRINT,
            support.CLOSED_SIGNAL_OVEREXTENSION_FINGERPRINT,
            support.CLOSED_UNDERWATER_CONGESTION_FINGERPRINT,
            support.CLOSED_FLAT_VETO_FINGERPRINT,
        }
        self.assertEqual(len(fingerprints), 6)


if __name__ == "__main__":
    unittest.main()
