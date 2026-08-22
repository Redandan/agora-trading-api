from __future__ import annotations

from decimal import Decimal
import unittest

from research.btc_coinbase_binance_close_premium_predictive_v1 import (
    build_observations,
    calculate_states,
)


D = Decimal


class BtcCoinbaseBinanceClosePremiumPredictiveV1Test(unittest.TestCase):
    def test_state_uses_strict_natural_zero_threshold(self) -> None:
        states, diagnostics = calculate_states(
            {
                "2020-01-01": {"COINBASE": D("101"), "BINANCE": D("100")},
                "2020-01-02": {"COINBASE": D("100"), "BINANCE": D("100")},
                "2020-01-03": {"COINBASE": D("99"), "BINANCE": D("100")},
            }
        )

        self.assertEqual(
            {"2020-01-01": True, "2020-01-02": False, "2020-01-03": False},
            states,
        )
        self.assertEqual(D("0.01"), diagnostics[0]["premium"])

    def test_next_day_outcome_uses_prior_complete_day_state(self) -> None:
        states: dict[str, bool] = {}
        opens: dict[str, D] = {}
        from datetime import date, timedelta

        current = date(2020, 1, 1)
        terminal = date(2025, 1, 1)
        while current <= terminal:
            day = current.isoformat()
            states[day] = current.day % 2 == 1
            opens[day] = D("100") + D((current - date(2020, 1, 1)).days)
            current += timedelta(days=1)

        observations = build_observations(states, opens)

        self.assertEqual("2020-01-02", observations[0]["outcome_day"])
        self.assertTrue(observations[0]["high_state"])
        self.assertEqual(D("102") / D("101") - D(1), observations[0]["return"])
        self.assertEqual("2024-12-31", observations[-1]["outcome_day"])


if __name__ == "__main__":
    unittest.main()
