from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
import unittest

from research.binance_btcusdt_funding_carry_corpus_v1 import FundingEvent, HourBar
from research.binance_btcusdt_funding_carry_corpus_v2 import (
    ERRATUM_PATH,
    EXPECTED_PROXY_TIMES,
    normalized_csv,
    parse_spot_csv,
    verify_erratum,
)


class FundingCarryCorpusV2Test(unittest.TestCase):
    def test_erratum_binds_exact_six_hour_publisher_gap(self) -> None:
        value = verify_erratum(ERRATUM_PATH)
        actual = {
            int(item)
            for item in value["revised_source_closure"][
                "expected_v1_failure_proxy_open_times_ms"
            ]
        }
        self.assertEqual(EXPECTED_PROXY_TIMES, actual)

    def test_incomplete_spot_hour_is_excluded_and_recorded(self) -> None:
        opened = 1_582_110_000_000
        row = ",".join(
            [
                str(opened),
                "100",
                "102",
                "99",
                "101",
                "1",
                str(opened + 2_132_287),
                "100",
                "1",
                "0.5",
                "50",
                "0",
            ]
        )
        parsed = parse_spot_csv((row + "\n").encode("ascii"), month="2020-02")
        self.assertEqual([], parsed.bars)
        self.assertEqual("1582110000000", parsed.incomplete_rows[0]["open_time_ms"])

    def test_normalized_gap_uses_index_only_and_labels_source(self) -> None:
        timestamp = min(EXPECTED_PROXY_TIMES)
        bar = HourBar(timestamp, Decimal("100"), Decimal("102"), Decimal("99"), Decimal("101"))
        raw = normalized_csv(
            {},
            {timestamp: bar},
            {timestamp: bar},
            {timestamp: bar},
            {timestamp: FundingEvent(timestamp, 8, Decimal("0.0001"))},
            [timestamp],
        ).decode("ascii")
        self.assertIn("BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP", raw)
        self.assertIn("0.0001", raw)


if __name__ == "__main__":
    unittest.main()
