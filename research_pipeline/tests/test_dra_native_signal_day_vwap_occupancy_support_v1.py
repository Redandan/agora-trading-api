from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_signal_day_vwap_occupancy_support_v1 as support


D = Decimal


class DraNativeSignalDayVwapOccupancySupportV1Test(unittest.TestCase):
    def test_feature_uses_typical_price_base_volume_vwap(self) -> None:
        bars = [(D("9"), D("9"), D("9"), D("1"))] * 12
        bars += [(D("15"), D("15"), D("15"), D("1"))] * 12
        vwap, above_count, share = support.vwap_occupancy(bars) or (None, None, None)
        self.assertEqual(vwap, D("12"))
        self.assertEqual(above_count, 12)
        self.assertEqual(share, D("0.50000000"))

    def test_feature_is_unavailable_without_complete_day_or_positive_volume(self) -> None:
        incomplete = [(D("1"), D("1"), D("1"), D("1"))] * 23
        zero_volume = [(D("1"), D("1"), D("1"), D("0"))] * 24
        self.assertIsNone(support.vwap_occupancy(incomplete))
        self.assertIsNone(support.vwap_occupancy(zero_volume))

    def test_actionable_veto_is_capacity_first_and_strictly_below_count(self) -> None:
        at_threshold = {"capacity_admissible": True, "h1_closes_above_vwap": 12}
        below = {"capacity_admissible": True, "h1_closes_above_vwap": 11}
        blocked = {"capacity_admissible": False, "h1_closes_above_vwap": 0}
        unavailable = {"capacity_admissible": True, "h1_closes_above_vwap": None}
        self.assertFalse(support.actionable_veto(at_threshold, 12))
        self.assertTrue(support.actionable_veto(below, 12))
        self.assertFalse(support.actionable_veto(blocked, 12))
        self.assertTrue(support.actionable_veto(unavailable, 12))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual(
            [minimum_count for _, minimum_count, _ in support.VARIANTS],
            [8, 12, 16],
        )

    def test_action_fingerprints_are_distinct(self) -> None:
        self.assertEqual(len(set(support._expected_action_fingerprints().values())), 6)


if __name__ == "__main__":
    unittest.main()
