from __future__ import annotations

from datetime import date, datetime, time, timedelta
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from research.btc_miner_revenue_per_hash_momentum_long_cash_historical import (
    DailyPoint,
    FactorPoint,
    ResearchReject,
    WeeklyPoint,
    block_subsidy,
    build_factor_points,
    build_weekly_points,
    load_daily_series,
    nonredundancy_diagnostic,
    spearman_correlation,
)


D = Decimal


class BtcMinerRevenuePerHashMomentumLongCashHistoricalTest(unittest.TestCase):
    def test_protocol_subsidy_boundaries_are_exact(self) -> None:
        self.assertEqual(D("12.5"), block_subsidy(date(2020, 5, 10)))
        self.assertEqual(D("6.25"), block_subsidy(date(2020, 5, 11)))
        self.assertEqual(D("6.25"), block_subsidy(date(2024, 4, 19)))
        self.assertEqual(D("3.125"), block_subsidy(date(2024, 4, 20)))

    def test_daily_source_loader_fails_closed_on_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.csv"
            path.write_text(
                "date,total_fees_btc\n2020-01-01,1.5\n2020-01-02,0\n",
                encoding="utf-8",
            )
            rows = load_daily_series(
                path, "total_fees_btc", allow_zero=True, expected_rows=2
            )
            self.assertEqual(D("1.5"), rows[date(2020, 1, 1)])
            with self.assertRaisesRegex(ResearchReject, "ROWS"):
                load_daily_series(
                    path, "total_fees_btc", allow_zero=True, expected_rows=3
                )

    def test_complete_week_uses_monday_sunday_and_wednesday_availability(self) -> None:
        first = date(2021, 1, 4)
        daily = [
            DailyPoint(
                first + timedelta(days=index),
                D(100 + index),
                D("1"),
                D("10"),
                D("6.25"),
                D(20 + index),
            )
            for index in range(14)
        ]
        weekly = build_weekly_points(daily)
        self.assertEqual(2, len(weekly))
        self.assertEqual(date(2021, 1, 10), weekly[0].week_end)
        self.assertEqual(
            datetime.combine(date(2021, 1, 13), time.min), weekly[0].eligible_at
        )
        self.assertEqual(D("23"), weekly[0].proxy_mean)

    def test_factor_uses_current_and_previous_adjacent_week_means(self) -> None:
        first_sunday = date(2021, 1, 10)
        weekly = [
            WeeklyPoint(
                first_sunday + timedelta(days=7 * index),
                datetime.combine(
                    first_sunday + timedelta(days=7 * index + 3), time.min
                ),
                D(index + 1),
                D(100 + index),
                D(10 + index),
                D(20 + index),
            )
            for index in range(10)
        ]
        points = build_factor_points(weekly, 4)
        self.assertEqual(3, len(points))
        self.assertEqual(weekly[7].eligible_at, points[0].eligible_at)
        self.assertGreater(points[0].proxy_growth, D("0"))
        with self.assertRaisesRegex(ResearchReject, "LOOKBACK"):
            build_factor_points(weekly, 3)

    def test_spearman_and_window_nonredundancy_are_fail_closed(self) -> None:
        ascending = [D(value) for value in (1, 2, 3, 4, 5)]
        descending = list(reversed(ascending))
        self.assertEqual(D("1"), spearman_correlation(ascending, ascending))
        self.assertEqual(D("-1"), spearman_correlation(ascending, descending))
        start = datetime(2023, 1, 1)
        points = [
            FactorPoint(
                start + timedelta(days=7 * index),
                (start + timedelta(days=7 * index)).date(),
                4,
                D(index + 1),
                D(index + 1),
                D((index * 2) % 5 + 1),
                D((index * 3) % 5 + 1),
                True,
            )
            for index in range(8)
        ]
        diagnostic, gates = nonredundancy_diagnostic(
            points, (start, start + timedelta(days=60)), "validation"
        )
        self.assertFalse(
            gates[
                "primary_validation_proxy_growth_abs_spearman_to_btc_price_growth_at_most_0_80"
            ]
        )
        self.assertEqual("1.00000000", diagnostic["correlations"]["btc_price_growth"]["absolute_spearman"])


if __name__ == "__main__":
    unittest.main()
