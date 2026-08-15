from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_forward_entry_admission_v1 as runner


def _ledger(*, total: str, realized: str, unrealized: str, drawdown: str) -> dict:
    return {
        "total_pnl_usdt": total,
        "realized_usdt": realized,
        "unrealized_usdt": unrealized,
        "max_drawdown_pct": drawdown,
        "median_hold_hours": "10",
        "p90_hold_hours": "20",
        "vetoed_signal_count": 10,
    }


class DraForwardEntryAdmissionRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.baseline = {
            "design": _ledger(
                total="1", realized="1", unrealized="0", drawdown="5"
            ),
            "validation": _ledger(
                total="1", realized="1", unrealized="0", drawdown="5"
            ),
        }
        self.variant = {
            "design": _ledger(
                total="2", realized="2", unrealized="0", drawdown="5"
            ),
            "validation": _ledger(
                total="2", realized="2", unrealized="0", drawdown="5"
            ),
            "annual_total_wins": 5,
            "annual_drawdown_non_worse": 4,
            "top_year_positive_delta_contribution_pct": "20",
        }

    def test_primary_requires_four_of_five_drawdown_non_worse_folds(self) -> None:
        four_of_five = runner.primary_gates(self.variant, self.baseline)
        self.assertTrue(
            four_of_five["annual_drawdown_non_worse_at_least_4_of_5"]
        )
        self.assertNotIn(
            "annual_drawdown_non_worse_at_least_3_of_5", four_of_five
        )

        self.variant["annual_drawdown_non_worse"] = 3
        three_of_five = runner.primary_gates(self.variant, self.baseline)
        self.assertFalse(
            three_of_five["annual_drawdown_non_worse_at_least_4_of_5"]
        )


if __name__ == "__main__":
    unittest.main()
