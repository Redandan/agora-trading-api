from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from research.btc_us_treasury_3m_yield_easing_long_cash_historical import (
    ResearchReject,
    TreasuryPoint,
    load_treasury,
    targets_by_execution_time,
)


D = Decimal


def business_dates(count: int) -> list[date]:
    current = date(2020, 1, 2)
    result: list[date] = []
    while len(result) < count:
        if current.weekday() < 5:
            result.append(current)
        current += timedelta(days=1)
    return result


class BtcUsTreasury3mYieldEasingLongCashHistoricalTest(unittest.TestCase):
    def test_load_treasury_validates_exact_columns_order_and_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "treasury.csv"
            path.write_text(
                "date,three_month_pct,one_year_pct,two_year_pct,ten_year_pct\n"
                "2020-01-02,1.50,1.55,1.60,1.75\n"
                "2020-01-03,1.49,1.54,1.59,1.74\n",
                encoding="utf-8",
            )
            points = load_treasury(path, expected_rows=2)
        self.assertEqual(points[0], TreasuryPoint(date(2020, 1, 2), D("1.50")))
        self.assertEqual(points[-1].observation_date, date(2020, 1, 3))

    def test_weekly_target_uses_final_business_date_and_next_day_availability(self) -> None:
        dates = business_dates(100)
        points = [TreasuryPoint(day, D(500 - index)) for index, day in enumerate(dates)]
        targets, feature = targets_by_execution_time(points, 42)
        first_effective = min(targets)
        first_observation = first_effective.date() - timedelta(days=1)
        self.assertEqual(first_observation.weekday(), 4)
        self.assertEqual(first_effective.hour, 0)
        self.assertTrue(targets[first_effective])
        self.assertEqual(feature["lookback_valid_business_observations"], 42)
        self.assertEqual(feature["evaluation_count"], len(targets))

    def test_change_uses_exact_valid_observation_index_and_strict_direction(self) -> None:
        dates = business_dates(100)
        rising = [TreasuryPoint(day, D(index)) for index, day in enumerate(dates)]
        falling = [TreasuryPoint(day, D(500 - index)) for index, day in enumerate(dates)]
        flat = [TreasuryPoint(day, D("1.00")) for day in dates]
        rising_targets, _ = targets_by_execution_time(rising, 63)
        falling_targets, _ = targets_by_execution_time(falling, 63)
        flat_targets, _ = targets_by_execution_time(flat, 63)
        self.assertTrue(rising_targets)
        self.assertTrue(all(not target for target in rising_targets.values()))
        self.assertTrue(all(falling_targets.values()))
        self.assertTrue(all(not target for target in flat_targets.values()))

    def test_unregistered_lookback_fails_closed(self) -> None:
        points = [
            TreasuryPoint(day, D("1.00"))
            for day in business_dates(100)
        ]
        with self.assertRaisesRegex(ResearchReject, "YIELD_LOOKBACK"):
            targets_by_execution_time(points, 60)


if __name__ == "__main__":
    unittest.main()
