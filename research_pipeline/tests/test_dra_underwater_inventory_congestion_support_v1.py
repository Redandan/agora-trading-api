from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_dra_reversal_confirmed_exit_v2c as base
import dra_underwater_inventory_congestion_support_v1 as support


D = Decimal


def _lot(cost: str, quantity: str, price: str = "100") -> base.Lot:
    now = datetime(2024, 1, 1)
    return base.Lot(
        signal_time=now,
        fill_time=now,
        cost=D(cost),
        buy_price=D(price),
        quantity=D(quantity),
        entry_atr=None,
        highest_close=D(price),
    )


class DraUnderwaterInventoryCongestionSupportV1Test(unittest.TestCase):
    def test_inventory_state_uses_current_close_estimated_net_and_actual_cost(self) -> None:
        lots = [_lot("30", "0.300000000000"), _lot("30", "0.300000000000")]
        state = support.inventory_state(lots, D("90"))

        self.assertEqual(state["open_lot_count"], 2)
        self.assertEqual(state["open_cost_usdt"], "60.00000000")
        self.assertLess(D(state["aggregate_estimated_net_pnl_usdt"]), 0)
        self.assertTrue(state["capacity_admissible"])

    def test_primary_veto_requires_capacity_underwater_and_four_lots(self) -> None:
        eligible = {
            "open_cost_usdt": "120.00000000",
            "aggregate_estimated_net_pnl_usdt": "-1.00000000",
            "capacity_admissible": True,
        }
        profitable = dict(eligible, aggregate_estimated_net_pnl_usdt="0.00000001")
        too_small = dict(eligible, open_cost_usdt="90.00000000")
        blocked = dict(eligible, capacity_admissible=False)

        self.assertTrue(support.actionable_veto(eligible, D("120")))
        self.assertFalse(support.actionable_veto(profitable, D("120")))
        self.assertFalse(support.actionable_veto(too_small, D("120")))
        self.assertFalse(support.actionable_veto(blocked, D("120")))

    def test_closed_action_fingerprints_are_distinct(self) -> None:
        fingerprints = {
            support.NEW_ACTION_FINGERPRINT,
            support.CLOSED_ONE_SLOT_ROTATION_FINGERPRINT,
            support.CLOSED_FLAT_VETO_FINGERPRINT,
            support.CLOSED_CAPACITY_FINGERPRINT,
            support.CLOSED_VARIABLE_SIZING_FINGERPRINT,
        }
        self.assertEqual(len(fingerprints), 5)

    def test_variant_thresholds_are_frozen_and_monotonic(self) -> None:
        self.assertEqual(
            [threshold for _, threshold, _ in support.VARIANTS],
            [D("90"), D("120"), D("150")],
        )


if __name__ == "__main__":
    unittest.main()
