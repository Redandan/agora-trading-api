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

import dra_corwin_schultz_spread_support_v1 as support


D = Decimal


def observation(day: int, *, low: str, high: str) -> support.DailyObservation:
    return support.DailyObservation(
        day=date(2024, 1, day),
        open=D(low),
        high=D(high),
        low=D(low),
        close=D(high),
        hourly_closes=tuple(D(high) for _ in range(24)),
        quote_volume_proxy=D("1000000"),
    )


class CorwinSchultzSpreadSupportTest(unittest.TestCase):
    def test_positive_stationary_two_day_range_produces_positive_spread(self) -> None:
        first = observation(1, low="100", high="110")
        second = observation(2, low="100", high="110")
        self.assertGreater(support.corwin_schultz_spread(first, second), D("0"))

    def test_large_two_day_range_is_zero_floored(self) -> None:
        first = observation(1, low="100", high="101")
        second = observation(2, low="200", high="202")
        self.assertEqual(support.corwin_schultz_spread(first, second), D("0"))

    def test_normalized_series_uses_only_prior_twenty_values(self) -> None:
        raw = [(date(2024, 1, index + 1), D(index + 1)) for index in range(21)]
        normalized = support.normalized_series(raw)
        self.assertEqual(list(normalized), [date(2024, 1, 21)])
        self.assertEqual(normalized[date(2024, 1, 21)], D("21") / D("10.5"))

    def test_zero_prior_median_fails_closed_for_that_day(self) -> None:
        raw = [(date(2024, 1, index + 1), D("0")) for index in range(20)]
        raw.append((date(2024, 1, 21), D("1")))
        self.assertEqual(support.normalized_series(raw), {})

    def test_spearman_handles_ties_and_detects_same_ordering(self) -> None:
        left = [D(value) for value in (1, 1, 2, 3, 4)]
        self.assertEqual(support.spearman(left, left), D("1"))
        reverse = list(reversed(left))
        self.assertLess(support.spearman(left, reverse), D("-0.8"))


if __name__ == "__main__":
    unittest.main()
