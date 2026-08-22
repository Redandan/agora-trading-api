from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
import unittest

from research.btc_binance_usdm_delta_neutral_funding_carry_historical import (
    COSTS,
    INITIAL_EQUITY,
    Row,
    close_position,
    is_quarter_reset,
    open_position,
    simulate,
)


class FundingCarryRunnerTest(unittest.TestCase):
    def test_quarterly_reset_occurs_only_at_first_day_01_utc(self) -> None:
        self.assertTrue(is_quarter_reset(datetime(2020, 1, 1, 1, tzinfo=timezone.utc)))
        self.assertTrue(is_quarter_reset(datetime(2020, 4, 1, 1, tzinfo=timezone.utc)))
        self.assertFalse(is_quarter_reset(datetime(2020, 1, 1, 0, tzinfo=timezone.utc)))
        self.assertFalse(is_quarter_reset(datetime(2020, 2, 1, 1, tzinfo=timezone.utc)))

    def test_open_book_uses_equal_btc_quantity_and_25_75_capital_split(self) -> None:
        row = Row(
            int(datetime(2020, 1, 1, 1, tzinfo=timezone.utc).timestamp() * 1000),
            *(Decimal("100") for _ in range(6)),
            None,
        )
        position, fees, slippage = open_position(INITIAL_EQUITY, row, COSTS["NORMAL"])
        self.assertGreater(position.quantity, Decimal("24"))
        self.assertLess(position.quantity, Decimal("25"))
        self.assertLess(position.futures_cash, Decimal("7500"))
        self.assertGreater(fees, Decimal("0"))
        self.assertGreater(slippage, Decimal("0"))
        final_equity, closing_fees, closing_slippage = close_position(
            position, Decimal("100"), Decimal("100"), COSTS["NORMAL"]
        )
        self.assertLess(final_equity, INITIAL_EQUITY)
        self.assertGreater(closing_fees, Decimal("0"))
        self.assertGreater(closing_slippage, Decimal("0"))

    def test_positive_funding_credits_short_and_negative_funding_debits_it(self) -> None:
        start = datetime(2020, 1, 1, tzinfo=timezone.utc)

        def rows(rate: Decimal) -> list[Row]:
            result = []
            for hour in range(4):
                moment = start + timedelta(hours=hour)
                result.append(
                    Row(
                        int(moment.timestamp() * 1000),
                        *(Decimal("100") for _ in range(6)),
                        rate if hour == 2 else None,
                    )
                )
            return result

        positive = simulate(rows(Decimal("0.001")), start, start + timedelta(hours=4), COSTS["NORMAL"])
        negative = simulate(rows(Decimal("-0.001")), start, start + timedelta(hours=4), COSTS["NORMAL"])
        self.assertGreater(positive["gross_funding_pnl"], Decimal("0"))
        self.assertLess(negative["gross_funding_pnl"], Decimal("0"))
        self.assertGreater(positive["total_pnl"], negative["total_pnl"])
        self.assertEqual(positive["terminal_position_quantity"], Decimal("0"))
        self.assertEqual(positive["terminal_unrealized_pnl"], Decimal("0"))


if __name__ == "__main__":
    unittest.main()
