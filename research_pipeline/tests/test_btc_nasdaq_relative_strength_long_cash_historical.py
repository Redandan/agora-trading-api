from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from research.btc_nasdaq_relative_strength_long_cash_historical import (
    NasdaqPoint,
    ResearchReject,
    load_nasdaq,
    targets_by_execution_time,
)


D = Decimal


@dataclass(frozen=True)
class DailyPoint:
    close_time: datetime
    close: D


def business_dates(count: int) -> list[date]:
    current = date(2020, 1, 2)
    result: list[date] = []
    while len(result) < count:
        if current.weekday() < 5:
            result.append(current)
        current += timedelta(days=1)
    return result


class BtcNasdaqRelativeStrengthLongCashHistoricalTest(unittest.TestCase):
    def test_load_nasdaq_validates_identity_columns_order_and_positive_close(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nasdaq.csv"
            path.write_text(
                "date,nasdaq_composite_close\n"
                "2020-01-02,100.0\n"
                "2020-01-03,101.0\n",
                encoding="utf-8",
            )
            points = load_nasdaq(path, expected_rows=2)
        self.assertEqual(points[0], NasdaqPoint(date(2020, 1, 2), D("100.0")))
        self.assertEqual(points[-1].observation_date, date(2020, 1, 3))

    def test_weekly_target_uses_final_business_date_and_next_day_availability(self) -> None:
        dates = business_dates(100)
        daily = [
            DailyPoint(datetime.combine(day + timedelta(days=1), time.min), D(100 + 2 * index))
            for index, day in enumerate(dates)
        ]
        nasdaq = [NasdaqPoint(day, D(100 + index)) for index, day in enumerate(dates)]
        targets, feature = targets_by_execution_time(daily, nasdaq, 42)
        first_effective = min(targets)
        first_observation = first_effective.date() - timedelta(days=1)
        self.assertEqual(first_observation.weekday(), 4)
        self.assertTrue(targets[first_effective])
        self.assertEqual(feature["lookback_paired_business_days"], 42)
        self.assertEqual(feature["evaluation_count"], len(targets))

    def test_relative_strength_direction_is_btc_return_minus_nasdaq_return(self) -> None:
        dates = business_dates(90)
        daily = [
            DailyPoint(datetime.combine(day + timedelta(days=1), time.min), D(100 + index))
            for index, day in enumerate(dates)
        ]
        nasdaq = [NasdaqPoint(day, D(100 + 3 * index)) for index, day in enumerate(dates)]
        targets, _ = targets_by_execution_time(daily, nasdaq, 63)
        self.assertTrue(targets)
        self.assertTrue(all(not target for target in targets.values()))

    def test_unregistered_lookback_and_missing_pair_fail_closed(self) -> None:
        dates = business_dates(90)
        daily = [
            DailyPoint(datetime.combine(day + timedelta(days=1), time.min), D(100 + index))
            for index, day in enumerate(dates)
        ]
        nasdaq = [NasdaqPoint(day, D(100 + index)) for index, day in enumerate(dates)]
        with self.assertRaisesRegex(ResearchReject, "RELATIVE_STRENGTH_LOOKBACK"):
            targets_by_execution_time(daily, nasdaq, 60)
        with self.assertRaisesRegex(ResearchReject, "MISSING_BTC_PAIR"):
            targets_by_execution_time(daily[:50] + daily[51:], nasdaq, 63)


if __name__ == "__main__":
    unittest.main()
