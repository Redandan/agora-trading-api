from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_dra_reversal_confirmed_exit_v2c as base
import dra_stale_inventory_age_support_v1 as support


D = Decimal


def _lot(fill_time: datetime, cost: str = "30") -> base.Lot:
    return base.Lot(
        signal_time=fill_time - timedelta(hours=1),
        fill_time=fill_time,
        cost=D(cost),
        buy_price=D("100"),
        quantity=D("0.299000000000"),
        entry_atr=None,
        highest_close=D("100"),
    )


class DraStaleInventoryAgeSupportV1Test(unittest.TestCase):
    def test_state_uses_oldest_actual_fill_time_without_inventory_pnl(self) -> None:
        decision = datetime(2024, 2, 1)
        state = support.inventory_age_state(
            [_lot(decision - timedelta(hours=100)), _lot(decision - timedelta(hours=800))],
            decision,
        )

        self.assertEqual(state["open_lot_count"], 2)
        self.assertEqual(state["oldest_open_lot_age_hours"], "800.00000000")
        self.assertNotIn("pnl", " ".join(state).lower())
        self.assertTrue(state["capacity_admissible"])

    def test_no_open_inventory_is_admitted(self) -> None:
        state = support.inventory_age_state([], datetime(2024, 2, 1))
        self.assertFalse(state["has_open_inventory"])
        self.assertEqual(state["oldest_open_lot_age_hours"], "0E-8")
        self.assertFalse(support.actionable_veto(state, D("720")))

    def test_primary_veto_is_capacity_first_and_inclusive_at_720_hours(self) -> None:
        eligible = {
            "capacity_admissible": True,
            "has_open_inventory": True,
            "oldest_open_lot_age_hours": "720.00000000",
        }
        fresh = dict(eligible, oldest_open_lot_age_hours="719.99999999")
        blocked = dict(eligible, capacity_admissible=False)

        self.assertTrue(support.actionable_veto(eligible, D("720")))
        self.assertFalse(support.actionable_veto(fresh, D("720")))
        self.assertFalse(support.actionable_veto(blocked, D("720")))

    def test_variant_thresholds_are_frozen_and_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("336"), D("720"), D("1440")],
        )
        row = {
            "capacity_admissible": True,
            "has_open_inventory": True,
            "oldest_open_lot_age_hours": "800.00000000",
        }
        self.assertEqual(
            [support.actionable_veto(row, threshold) for _, threshold, _ in support.VARIANTS],
            [True, True, False],
        )

    def test_closed_action_fingerprints_are_distinct(self) -> None:
        fingerprints = {
            support.NEW_ACTION_FINGERPRINT,
            support.CLOSED_UNDERWATER_CONGESTION_FINGERPRINT,
            support.CLOSED_ONE_SLOT_ROTATION_FINGERPRINT,
            support.CLOSED_CAPACITY_FINGERPRINT,
            support.CLOSED_PARTIAL_EXIT_FINGERPRINT,
            support.CLOSED_FLAT_VETO_FINGERPRINT,
        }
        self.assertEqual(len(fingerprints), 6)


if __name__ == "__main__":
    unittest.main()
