from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import dra_lagged_realized_variance_scaled_lot_sizing_support_v1 as support


D = Decimal


class DraLaggedRealizedVarianceScaledLotSizingSupportTest(unittest.TestCase):
    def test_realized_variance_uses_previous_day_close_and_all_twenty_four_closes(self) -> None:
        closes = tuple(D("101") for _ in range(24))
        expected = (D("101") / D("100") - D("1")) ** 2
        self.assertEqual(support.realized_variance(D("100"), closes), expected)

    def test_normalized_series_uses_only_prior_twenty_values(self) -> None:
        raw = [(date(2024, 1, index + 1), D(index + 1)) for index in range(21)]
        normalized = support.normalized_series(raw)
        self.assertEqual(list(normalized), [date(2024, 1, 21)])
        self.assertEqual(
            normalized[date(2024, 1, 21)],
            (D("21") / D("10.5")).quantize(D("0.00000001")),
        )

    def test_primary_mapping_never_increases_and_respects_floor(self) -> None:
        self.assertEqual(support.scaled_lot_cost(D("0.5"), D("15")), D("30.00000000"))
        self.assertEqual(support.scaled_lot_cost(D("1"), D("15")), D("30.00000000"))
        self.assertEqual(support.scaled_lot_cost(D("1.5"), D("15")), D("20.00000000"))
        self.assertEqual(support.scaled_lot_cost(D("3"), D("15")), D("15.00000000"))

    def test_unavailable_feature_preserves_parent_lot_cost(self) -> None:
        self.assertEqual(support.action_lot_cost(None, D("15")), D("30"))

    def test_action_fingerprints_are_distinct_at_the_action_level(self) -> None:
        fingerprints = {
            support.NEW_ACTION_FINGERPRINT,
            support.CLOSED_ADMISSION_FINGERPRINT,
            support.CLOSED_MONTHLY_TARGET_FINGERPRINT,
        }
        self.assertEqual(len(fingerprints), 3)
        self.assertIn("SIGNALS_PASS_THROUGH", support.NEW_ACTION_FINGERPRINT)
        self.assertIn("SIGNAL_COST_ZERO", support.CLOSED_ADMISSION_FINGERPRINT)
        self.assertIn("CLOCK_MONTHLY", support.CLOSED_MONTHLY_TARGET_FINGERPRINT)


if __name__ == "__main__":
    unittest.main()
