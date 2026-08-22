from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
import unittest

from research.binance_btcusdt_funding_carry_corpus_v1 import FundingEvent, HourBar
from research.binance_btcusdt_funding_carry_corpus_v3 import (
    CLOSURE_PATH,
    KLINE_HEADER,
    SPOT_PROXY_TIMES,
    normalized_csv,
    parse_funding_decimal,
    parse_funding,
    parse_klines,
    verify_closure,
)


class FundingCarryCorpusV3Test(unittest.TestCase):
    def test_final_closure_binds_complete_proxy_and_daily_fallback_inventory(self) -> None:
        value = verify_closure(CLOSURE_PATH)
        self.assertEqual(39, len(SPOT_PROXY_TIMES))
        self.assertEqual(320, value["final_acquisition_contract"]["total_archives"])
        self.assertEqual("DATA_REJECT_NO_FURTHER_ERRATUM", value["final_acquisition_contract"]["unexpected_source_anomaly"])

    def test_exact_publisher_header_is_allowed_but_unknown_header_rejects(self) -> None:
        moment = datetime(2022, 2, 1, tzinfo=timezone.utc)
        opened = int(moment.timestamp() * 1000)
        row = ",".join(
            [
                str(opened),
                "100",
                "102",
                "99",
                "101",
                "1",
                str(opened + 3_600_000 - 1),
                "100",
                "1",
                "0.5",
                "50",
                "0",
            ]
        )
        raw = (",".join(KLINE_HEADER) + "\n" + row + "\n").encode("ascii")
        parsed = parse_klines(
            raw,
            period="2022-02",
            dataset="usdm_contract_klines_1h",
            daily=False,
        )
        self.assertTrue(parsed.header_present)
        self.assertEqual(1, len(parsed.bars))

    def test_funding_actual_time_is_bound_to_unique_slot_with_offset(self) -> None:
        actual = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000) + 47
        raw = (
            "calc_time,funding_interval_hours,last_funding_rate\n"
            f"{actual},8,0.0001\n"
        ).encode("ascii")
        event = parse_funding(raw, month="2020-01")[0]
        self.assertEqual(47, event.offset_ms)
        self.assertEqual(actual - 47, event.slot_time_ms)
        self.assertEqual(Decimal("0.0001"), event.rate)

    def test_funding_rate_accepts_exact_scientific_notation(self) -> None:
        self.assertEqual(
            Decimal("8.4E-7"),
            parse_funding_decimal("8.4E-7", context="funding:rate:test"),
        )

    def test_normalized_rows_preserve_funding_time_and_offset(self) -> None:
        timestamp = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
        bar = HourBar(timestamp, Decimal("100"), Decimal("102"), Decimal("99"), Decimal("101"))
        from research.binance_btcusdt_funding_carry_corpus_v3 import NormalizedFunding

        event = NormalizedFunding(timestamp, timestamp + 47, 47, 8, Decimal("0.0001"))
        raw = normalized_csv(
            {timestamp: bar},
            {timestamp: bar},
            {timestamp: bar},
            {timestamp: bar},
            {timestamp: event},
            [timestamp],
        ).decode("ascii")
        self.assertIn(f",{timestamp + 47},47,0.0001", raw)


if __name__ == "__main__":
    unittest.main()
