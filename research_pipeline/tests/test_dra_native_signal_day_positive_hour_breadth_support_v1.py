from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_signal_day_positive_hour_breadth_support_v1 as support


class DraNativeSignalDayPositiveHourBreadthSupportV1Test(unittest.TestCase):
    def test_feature_requires_exactly_24_returns(self) -> None:
        self.assertEqual(support.positive_hour_breadth(24, 13), (13, Decimal("0.54166667")))
        self.assertIsNone(support.positive_hour_breadth(23, 13))

    def test_actionable_veto_is_capacity_first_and_strictly_below_count(self) -> None:
        self.assertFalse(support.actionable_veto({"capacity_admissible": True, "positive_h1_return_count": 13}, 13))
        self.assertTrue(support.actionable_veto({"capacity_admissible": True, "positive_h1_return_count": 12}, 13))
        self.assertFalse(support.actionable_veto({"capacity_admissible": False, "positive_h1_return_count": 0}, 13))
        self.assertTrue(support.actionable_veto({"capacity_admissible": True, "positive_h1_return_count": None}, 13))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual([minimum_count for _, minimum_count, _ in support.VARIANTS], [11, 13, 15])

    def test_action_fingerprints_are_distinct(self) -> None:
        self.assertEqual(len(set(support._expected_action_fingerprints().values())), 6)


if __name__ == "__main__":
    unittest.main()
