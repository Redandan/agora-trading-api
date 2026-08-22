from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
import unittest

from research.btc_daily_chaikin_money_flow_long_cash_historical import (
    DailyPoint,
    ResearchReject,
    build_daily_points,
    targets_by_execution_time,
)


D = Decimal


def _bar(hour: int, *, high: str = "12", low: str = "8", close: str = "11", volume: str = "2") -> SimpleNamespace:
    start = datetime(2024, 1, 1) + timedelta(hours=hour)
    return SimpleNamespace(
        open_time=start,
        close_time=start + timedelta(hours=1),
        open=D("10"),
        high=D(high),
        low=D(low),
        close=D(close),
        volume=D(volume),
    )


class DailyChaikinMoneyFlowLongCashTest(unittest.TestCase):
    def test_complete_day_uses_close_location_and_total_base_volume(self) -> None:
        bars = [_bar(hour) for hour in range(24)]
        points = build_daily_points(bars, expected_rows=1)
        self.assertEqual(1, len(points))
        self.assertEqual(D("0.5"), points[0].money_flow_multiplier)
        self.assertEqual(D("48"), points[0].volume)
        self.assertEqual(D("24.0"), points[0].money_flow_volume)

    def test_zero_range_day_has_zero_money_flow_multiplier(self) -> None:
        bars = [_bar(hour, high="10", low="10", close="10") for hour in range(24)]
        point = build_daily_points(bars, expected_rows=1)[0]
        self.assertEqual(D("0"), point.money_flow_multiplier)
        self.assertEqual(D("0"), point.money_flow_volume)

    def test_target_uses_only_complete_days_through_execution_time(self) -> None:
        start = datetime(2024, 1, 2)
        daily = []
        for index in range(29):
            multiplier = D("0.5") if index < 20 else D("-0.5")
            volume = D("10")
            daily.append(
                DailyPoint(
                    start + timedelta(days=index),
                    D("12"),
                    D("8"),
                    D("11") if multiplier > 0 else D("9"),
                    volume,
                    multiplier,
                    multiplier * volume,
                )
            )
        targets, feature = targets_by_execution_time(daily, 20)
        first_time = start + timedelta(days=19)
        self.assertTrue(targets[first_time])
        self.assertEqual(10, feature["evaluation_count"])
        self.assertEqual(first_time.isoformat(), feature["first_effective_time"])

    def test_unregistered_lookback_fails_closed(self) -> None:
        with self.assertRaisesRegex(ResearchReject, "CMF_LOOKBACK"):
            targets_by_execution_time([], 21)


if __name__ == "__main__":
    unittest.main()
