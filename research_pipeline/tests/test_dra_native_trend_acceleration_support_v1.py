from __future__ import annotations

from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import dra_native_trend_acceleration_support_v1 as support


D = Decimal


class DraNativeTrendAccelerationSupportV1Test(unittest.TestCase):
    def test_feature_is_second_five_day_ema20_difference_normalized_by_atr(self) -> None:
        self.assertEqual(
            support.trend_acceleration(
                D("112"), D("105"), D("100"), D("10")
            ),
            D("0.20000000"),
        )

    def test_feature_is_unavailable_until_all_causal_inputs_exist(self) -> None:
        self.assertIsNone(
            support.trend_acceleration(None, D("105"), D("100"), D("10"))
        )
        self.assertIsNone(
            support.trend_acceleration(D("112"), D("105"), None, D("10"))
        )
        self.assertIsNone(
            support.trend_acceleration(D("112"), D("105"), D("100"), None)
        )

    def test_actionable_veto_is_capacity_first_and_strictly_below_threshold(self) -> None:
        at_threshold = {
            "capacity_admissible": True,
            "ema20_acceleration_atr": "0.00000000",
        }
        below = {
            "capacity_admissible": True,
            "ema20_acceleration_atr": "-0.00000001",
        }
        blocked = {
            "capacity_admissible": False,
            "ema20_acceleration_atr": "-1.00000000",
        }
        unavailable = {
            "capacity_admissible": True,
            "ema20_acceleration_atr": None,
        }

        self.assertFalse(support.actionable_veto(at_threshold, D("0.00")))
        self.assertTrue(support.actionable_veto(below, D("0.00")))
        self.assertFalse(support.actionable_veto(blocked, D("0.00")))
        self.assertTrue(support.actionable_veto(unavailable, D("0.00")))

    def test_frozen_neighbors_are_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("-0.10"), D("0.00"), D("0.10")],
        )

    def test_action_fingerprints_are_distinct(self) -> None:
        self.assertEqual(
            len(set(support._expected_action_fingerprints().values())),
            6,
        )


if __name__ == "__main__":
    unittest.main()
