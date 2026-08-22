from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_signal_overextension_support_v1 as support


D = Decimal


class DraNativeSignalOverextensionSupportV1Test(unittest.TestCase):
    def test_signal_margin_uses_causal_close_ema_and_atr(self) -> None:
        self.assertEqual(
            support.signal_margin(D("110"), D("100"), D("8")),
            D("1.25000000"),
        )

    def test_signal_margin_is_unavailable_until_indicators_exist(self) -> None:
        self.assertIsNone(support.signal_margin(D("110"), None, D("8")))
        self.assertIsNone(support.signal_margin(D("110"), D("100"), None))

    def test_actionable_veto_is_capacity_first_and_strictly_above_threshold(self) -> None:
        at_threshold = {"capacity_admissible": True, "signal_margin_atr": "1.00000000"}
        over = {"capacity_admissible": True, "signal_margin_atr": "1.00000001"}
        blocked = {"capacity_admissible": False, "signal_margin_atr": "2.00000000"}
        unavailable = {"capacity_admissible": True, "signal_margin_atr": None}

        self.assertFalse(support.actionable_veto(at_threshold, D("1.00")))
        self.assertTrue(support.actionable_veto(over, D("1.00")))
        self.assertFalse(support.actionable_veto(blocked, D("1.00")))
        self.assertTrue(support.actionable_veto(unavailable, D("1.00")))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("0.75"), D("1.00"), D("1.25")],
        )

    def test_closed_feature_fingerprints_are_distinct(self) -> None:
        self.assertEqual(
            len(
                {
                    support.NEW_ACTION_FINGERPRINT,
                    support.CLOSED_LONG_TREND_FINGERPRINT,
                    support.CLOSED_LATE_DAY_ACTIVITY_FINGERPRINT,
                    support.CLOSED_INTRADAY_DRAWDOWN_FINGERPRINT,
                }
            ),
            4,
        )


if __name__ == "__main__":
    unittest.main()
