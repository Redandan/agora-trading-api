from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_prior_day_momentum_support_v1 as support


D = Decimal


class DraNativePriorDayMomentumSupportV1Test(unittest.TestCase):
    def test_feature_is_signal_day_move_normalized_by_updated_atr(self) -> None:
        self.assertEqual(
            support.prior_day_momentum_atr(D("105"), D("100"), D("10")),
            D("0.50000000"),
        )

    def test_parent_feature_uses_close_exactly_24_h1_bars_earlier(self) -> None:
        self.assertEqual(
            support.parent_momentum_atr(D("105"), D("101"), D("10")),
            D("0.40000000"),
        )

    def test_feature_is_unavailable_without_day_open_or_atr(self) -> None:
        self.assertIsNone(support.prior_day_momentum_atr(D("105"), None, D("10")))
        self.assertIsNone(support.prior_day_momentum_atr(D("105"), D("100"), None))

    def test_actionable_veto_is_capacity_first_and_strictly_below_threshold(self) -> None:
        at_threshold = {"capacity_admissible": True, "prior_day_momentum_atr": "0.00000000"}
        below = {"capacity_admissible": True, "prior_day_momentum_atr": "-0.00000001"}
        blocked = {"capacity_admissible": False, "prior_day_momentum_atr": "-1.00000000"}
        unavailable = {"capacity_admissible": True, "prior_day_momentum_atr": None}
        self.assertFalse(support.actionable_veto(at_threshold, D("0.00")))
        self.assertTrue(support.actionable_veto(below, D("0.00")))
        self.assertFalse(support.actionable_veto(blocked, D("0.00")))
        self.assertTrue(support.actionable_veto(unavailable, D("0.00")))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("-0.25"), D("0.00"), D("0.25")],
        )


if __name__ == "__main__":
    unittest.main()
