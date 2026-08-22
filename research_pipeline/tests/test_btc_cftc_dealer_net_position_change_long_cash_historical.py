from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_cftc_dealer_net_position_change_long_cash_historical as runner


D = Decimal


class BtcCftcDealerNetPositionChangeLongCashHistoricalTest(unittest.TestCase):
    def row(
        self,
        dealer_long: str,
        dealer_short: str,
        leveraged_long: str = "10",
        leveraged_short: str = "5",
        asset_long: str = "20",
        asset_short: str = "10",
    ) -> list[str]:
        row = ["0"] * len(runner.cftc_reused.cftc_source.ORDERED_FIELDS)
        row[runner.DEALER_LONG_INDEX] = dealer_long
        row[runner.DEALER_SHORT_INDEX] = dealer_short
        row[runner.LEVERAGED_LONG_INDEX] = leveraged_long
        row[runner.LEVERAGED_SHORT_INDEX] = leveraged_short
        row[runner.ASSET_LONG_INDEX] = asset_long
        row[runner.ASSET_SHORT_INDEX] = asset_short
        return row

    def test_factor_requires_exact_tuesday_predecessor_and_day7_availability(self) -> None:
        rows = {
            date(2020, 1, 7): self.row("10", "4", "9", "5", "18", "10"),
            date(2020, 1, 14): self.row("12", "3", "11", "4", "17", "11"),
            date(2020, 1, 15): self.row("99", "1"),
            date(2020, 1, 28): self.row("15", "3"),
        }

        points, exclusions = runner.build_factor_points(rows)

        self.assertEqual(1, len(points))
        self.assertEqual(date(2020, 1, 14), points[0].report_date)
        self.assertEqual(datetime(2020, 1, 21), points[0].eligible_at)
        self.assertEqual(D("3"), points[0].dealer_delta)
        self.assertEqual(D("3"), points[0].leveraged_money_delta)
        self.assertEqual(D("-2"), points[0].asset_manager_delta)
        self.assertTrue(points[0].target_long)
        self.assertEqual(1, exclusions["non_tuesday"])
        self.assertEqual(2, exclusions["missing_exact_predecessor"])

    def test_ion_delay_rows_and_their_immediate_successor_are_ineligible(self) -> None:
        rows = {
            date(2020, 1, 7): self.row("10", "4"),
            date(2020, 1, 14): self.row("10", "4"),
            date(2023, 1, 24): self.row("10", "4"),
            date(2023, 1, 31): self.row("11", "4"),
            date(2023, 2, 7): self.row("12", "4"),
            date(2023, 3, 14): self.row("13", "4"),
            date(2023, 3, 21): self.row("14", "4"),
        }

        points, exclusions = runner.build_factor_points(rows)

        self.assertEqual([date(2020, 1, 14)], [point.report_date for point in points])
        self.assertEqual(3, exclusions["ion_delay"])
        self.assertEqual(3, exclusions["missing_exact_predecessor"])

    def test_zero_dealer_delta_is_cash_and_spearman_handles_ties(self) -> None:
        point = runner.FactorPoint(
            date(2020, 1, 14),
            datetime(2020, 1, 21),
            D("0"),
            D("1"),
            D("-1"),
        )
        self.assertFalse(point.target_long)
        tied = [D(value) for value in (1, 1, 2, 3)]
        self.assertEqual(D("1"), runner.spearman_correlation(tied, tied))
        with self.assertRaisesRegex(runner.ResearchReject, "CONSTANT"):
            runner.spearman_correlation([D("1")] * 4, tied)

    def test_predictive_response_uses_exact_168h_open_to_open_path(self) -> None:
        anchor = datetime(2020, 1, 21)
        bars = [
            SimpleNamespace(
                open_time=anchor + timedelta(hours=offset),
                open=D("100") if offset < 168 else D("110"),
                close=D("100"),
            )
            for offset in range(169)
        ]
        point = runner.FactorPoint(
            date(2020, 1, 14), anchor, D("1"), D("0"), D("0")
        )

        evidence = runner.predictive_evidence(
            bars, [point], (anchor, anchor + timedelta(hours=168))
        )

        self.assertEqual(1, evidence["statistics"]["episode_count"])
        self.assertEqual("0.10000000", evidence["episodes"][0]["raw_return_168h"])
        self.assertEqual("0.10000000", evidence["episodes"][0]["signed_response_168h"])
        self.assertEqual("0.00000000", evidence["episodes"][0]["sign_adjusted_mae_168h"])


if __name__ == "__main__":
    unittest.main()
