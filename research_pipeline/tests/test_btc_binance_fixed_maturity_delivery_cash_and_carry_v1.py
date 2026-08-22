from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal as D
import json
import unittest

from research import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v1 as corpus
from research import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v2 as corpus_v2
from research import binance_btcusdt_fixed_maturity_delivery_carry_corpus_v3 as corpus_v3
from research import btc_binance_fixed_maturity_delivery_cash_and_carry_v1 as runner


class BtcBinanceFixedMaturityDeliveryCashAndCarryV1Test(unittest.TestCase):
    def test_delivery_response_maps_one_settlement_to_next_contract(self) -> None:
        delivery_time = int(
            datetime(2021, 6, 25, 8, tzinfo=timezone.utc).timestamp() * 1000
        )
        raw = json.dumps(
            [{"deliveryTime": delivery_time, "deliveryPrice": "35000.25"}],
            separators=(",", ":"),
        ).encode("ascii")
        deliveries = corpus.parse_delivery_prices(raw)
        schedule = corpus.selected_schedule(
            {
                "source_contract": {
                    "exact_contract_symbols": [
                        "BTCUSDT_210326",
                        "BTCUSDT_210625",
                    ]
                }
            },
            deliveries,
        )
        self.assertEqual("BTCUSDT_210625", schedule[0]["symbol"])
        self.assertEqual("2021-03", schedule[0]["months"][0])
        self.assertEqual("2021-06", schedule[0]["months"][-1])

    def test_v2_validates_then_sorts_unordered_delivery_rows(self) -> None:
        raw = json.dumps(
            [
                {"deliveryTime": 7_200_000, "deliveryPrice": "101"},
                {"deliveryTime": 3_600_000, "deliveryPrice": "100"},
            ],
            separators=(",", ":"),
        ).encode("ascii")
        values = corpus_v2.parse_delivery_prices(raw)
        self.assertEqual([3_600_000, 7_200_000], [value.delivery_time_ms for value in values])

    def test_v3_preserves_original_v1_validator_before_monkey_patch(self) -> None:
        self.assertIs(corpus.load_spec, corpus_v3.ORIGINAL_V1_LOAD_SPEC)

    @staticmethod
    def cycle(future_open: D) -> list[runner.Row]:
        entry = 3_600_000
        delivery = entry + 24 * 3_600_000
        return [
            runner.Row(
                "BTCUSDT_210625",
                entry + index * 3_600_000,
                entry,
                delivery,
                D("100"),
                D("100"),
                D("100"),
                D("100"),
                future_open,
                D("100"),
            )
            for index in range(24)
        ]

    def test_positive_basis_cycle_is_delta_neutral_and_terminally_flat(self) -> None:
        result = runner.simulate_cycle(
            self.cycle(D("101")), runner.INITIAL_EQUITY, runner.COSTS["NORMAL"]
        )
        self.assertTrue(result["entered"])
        self.assertGreater(result["pnl"], D("0"))
        self.assertFalse(result["liquidated"])
        self.assertGreater(result["minimum_margin_buffer"], D("2"))
        self.assertLess(
            abs(
                result["pnl"]
                - (
                    result["gross_pre_cost_pnl"]
                    - result["fees"]
                    - result["slippage"]
                )
            ),
            D("0.000000000001"),
        )

    def test_raw_basis_below_frozen_cost_floor_stays_in_cash(self) -> None:
        result = runner.simulate_cycle(
            self.cycle(D("100.50")),
            runner.INITIAL_EQUITY,
            runner.COSTS["NORMAL"],
        )
        self.assertFalse(result["entered"])
        self.assertEqual(runner.INITIAL_EQUITY, result["final_equity"])
        self.assertEqual(D("0"), result["pnl"])


if __name__ == "__main__":
    unittest.main()
