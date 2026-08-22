from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_signal_day_ema20_occupancy_support_v1 as support


D = Decimal


class DraNativeSignalDayEma20OccupancySupportV1Test(unittest.TestCase):
    def test_feature_counts_exactly_24_signal_day_closes(self) -> None:
        self.assertEqual(
            support.ema20_occupancy(24, 18, D("100")),
            D("0.75000000"),
        )

    def test_feature_is_unavailable_without_prior_ema_or_complete_day(self) -> None:
        self.assertIsNone(support.ema20_occupancy(24, 18, None))
        self.assertIsNone(support.ema20_occupancy(23, 18, D("100")))

    def test_actionable_veto_is_capacity_first_and_strictly_below_threshold(self) -> None:
        at_threshold = {"capacity_admissible": True, "ema20_occupancy_share": "0.75000000"}
        below = {"capacity_admissible": True, "ema20_occupancy_share": "0.70833333"}
        blocked = {"capacity_admissible": False, "ema20_occupancy_share": "0.00000000"}
        unavailable = {"capacity_admissible": True, "ema20_occupancy_share": None}
        self.assertFalse(support.actionable_veto(at_threshold, D("0.75")))
        self.assertTrue(support.actionable_veto(below, D("0.75")))
        self.assertFalse(support.actionable_veto(blocked, D("0.75")))
        self.assertTrue(support.actionable_veto(unavailable, D("0.75")))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("0.50"), D("0.75"), D("1.00")],
        )

    def test_action_fingerprints_are_distinct(self) -> None:
        self.assertEqual(len(set(support._expected_action_fingerprints().values())), 6)


if __name__ == "__main__":
    unittest.main()
