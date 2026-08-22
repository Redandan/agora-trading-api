from __future__ import annotations

from decimal import Decimal
from datetime import datetime, timedelta
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_signal_prior_24h_breakout_support_v1 as support


D = Decimal


class DraNativeSignalPrior24hBreakoutSupportV1Test(unittest.TestCase):
    def test_clearance_uses_maximum_of_exact_prior_twenty_four_highs(self) -> None:
        highs = [D(str(value)) for value in range(80, 104)]
        self.assertEqual(
            support.breakout_clearance(D("105"), highs, D("4")),
            D("0.50000000"),
        )

    def test_clearance_is_unavailable_without_exact_history_or_atr(self) -> None:
        highs = [D("100")] * 24
        self.assertIsNone(support.breakout_clearance(D("101"), highs[:-1], D("2")))
        self.assertIsNone(support.breakout_clearance(D("101"), highs, None))

    def test_actionable_veto_is_capacity_first_and_strict_breakout(self) -> None:
        at_threshold = {"capacity_admissible": True, "breakout_clearance_atr": "0.00000000"}
        above = {"capacity_admissible": True, "breakout_clearance_atr": "0.00000001"}
        blocked = {"capacity_admissible": False, "breakout_clearance_atr": "-1.00000000"}
        unavailable = {"capacity_admissible": True, "breakout_clearance_atr": None}

        self.assertTrue(support.actionable_veto(at_threshold, D("0.00")))
        self.assertFalse(support.actionable_veto(above, D("0.00")))
        self.assertFalse(support.actionable_veto(blocked, D("0.00")))
        self.assertTrue(support.actionable_veto(unavailable, D("0.00")))

    def test_observer_excludes_current_signal_bar_high_from_prior_window(self) -> None:
        engine = support.ParentPrior24hBreakoutObserver()
        engine.prior_hourly_highs.extend([D("100")] * 24)
        signal_time = datetime(2024, 1, 2, 23)
        engine.armed_at = signal_time - timedelta(hours=1)
        engine.arm_expires_at = signal_time + timedelta(days=1)
        engine.atr14 = D("2")
        engine._signal = lambda _bar: True
        bar = support.base.Bar(
            open_time=signal_time,
            close_time=signal_time + timedelta(hours=1),
            open=D("100"),
            high=D("200"),
            low=D("99"),
            close=D("101"),
            volume=D("1"),
        )

        engine._entry_lifecycle(bar)

        self.assertEqual(engine.signal_snapshots[0]["prior_24h_high_usdt"], "100")
        self.assertEqual(engine.signal_snapshots[0]["breakout_clearance_atr"], "0.50000000")

    def test_frozen_neighbors_are_monotonic_rejection_thresholds(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("-0.25"), D("0.00"), D("0.25")],
        )
        row = {"capacity_admissible": True, "breakout_clearance_atr": "0.10"}
        self.assertEqual(
            [support.actionable_veto(row, threshold) for _, threshold, _ in support.VARIANTS],
            [False, False, True],
        )

    def test_closed_feature_fingerprints_are_distinct(self) -> None:
        fingerprints = {
            support.NEW_ACTION_FINGERPRINT,
            support.CLOSED_LONG_TREND_FINGERPRINT,
            support.CLOSED_DONCHIAN_FINGERPRINT,
            support.CLOSED_CLOSE_LOCATION_FINGERPRINT,
            support.CLOSED_PATH_EFFICIENCY_FINGERPRINT,
            support.CLOSED_SIGNAL_OVEREXTENSION_FINGERPRINT,
        }
        self.assertEqual(len(fingerprints), 6)


if __name__ == "__main__":
    unittest.main()
