from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import unittest

from research.btc_coinbase_binance_relative_volume_predictive_v1 import (
    calculate_states,
    daily_open_prices,
    downside_semideviation,
    median,
)
from research.btc_dra_reversal_confirmed_exit_v2c import Bar


D = Decimal


class BtcCoinbaseBinanceRelativeVolumePredictiveV1Test(unittest.TestCase):
    def test_exact_median_and_downside_semideviation(self) -> None:
        self.assertEqual(D("2.5"), median([D("1"), D("2"), D("3"), D("4")]))
        self.assertEqual(D("0.5"), downside_semideviation([D("-1"), D("0"), D("1"), D("2")]))

    def test_state_uses_current_28_days_and_excludes_current_from_reference(self) -> None:
        volumes: dict[str, dict[str, D]] = {}
        current = date(2020, 1, 1)
        for index in range(500):
            coinbase = D("100") if index < 450 else D("300")
            volumes[current.isoformat()] = {
                "COINBASE": coinbase,
                "BINANCE": D("100"),
            }
            current += timedelta(days=1)
        states, diagnostics = calculate_states(volumes)
        self.assertEqual(108, len(states))
        self.assertFalse(diagnostics[0]["high_state"])
        self.assertTrue(diagnostics[-1]["high_state"])
        self.assertGreater(
            diagnostics[-1]["smoothed_share"],
            diagnostics[-1]["reference_median"],
        )

    def test_daily_open_prices_adds_terminal_close_as_next_day_open(self) -> None:
        bars = [
            Bar(
                datetime(2024, 12, 31, hour),
                datetime(2024, 12, 31, hour) + timedelta(hours=1),
                D(str(100 + hour)),
                D("200"),
                D("90"),
                D(str(101 + hour)),
                D("1"),
            )
            for hour in range(24)
        ]
        opens = daily_open_prices(bars)
        self.assertEqual(D("100"), opens["2024-12-31"])
        self.assertEqual(D("124"), opens["2025-01-01"])


if __name__ == "__main__":
    unittest.main()
