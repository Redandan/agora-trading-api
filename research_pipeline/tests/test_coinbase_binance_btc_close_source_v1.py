from __future__ import annotations

import csv
from datetime import date
import io
import json
import unittest
import zipfile

from research import coinbase_binance_btc_base_volume_source_v1 as volume_source
from research.coinbase_binance_btc_close_source_v1 import (
    SourceReject,
    normalized_csv,
    parse_binance_month,
    parse_coinbase_partition,
)
from research_pipeline.tests.test_coinbase_binance_btc_base_volume_source_v1 import (
    binance_zip,
    coinbase_rows,
)


class CoinbaseBinanceBtcCloseSourceV1Test(unittest.TestCase):
    def test_coinbase_parser_selects_close_not_volume(self) -> None:
        start = date(2020, 1, 1)
        end = date(2020, 1, 3)
        bars, excluded = parse_coinbase_partition(
            coinbase_rows(start, end), start=start, end=end
        )

        self.assertEqual([], excluded)
        self.assertEqual("2", format(bars[0].close, "f"))
        self.assertEqual(
            b"venue,symbol,date,close\n"
            b"COINBASE,BTC-USD,2020-01-01,2\n"
            b"COINBASE,BTC-USD,2020-01-02,2\n",
            normalized_csv(bars),
        )

    def test_coinbase_nonpositive_close_rejects(self) -> None:
        start = date(2020, 1, 1)
        end = date(2020, 1, 2)
        rows = json.loads(coinbase_rows(start, end))
        rows[0][4] = "0"

        with self.assertRaisesRegex(SourceReject, "DATA_REJECT:NONPOSITIVE_CLOSE"):
            parse_coinbase_partition(
                json.dumps(rows, separators=(",", ":")).encode("ascii"),
                start=start,
                end=end,
            )

    def test_binance_parser_selects_close_not_volume(self) -> None:
        bars = parse_binance_month(binance_zip("2020-01"), month="2020-01")

        self.assertEqual("110", format(bars[0].close, "f"))

    def test_binance_partial_day_rejects(self) -> None:
        raw = binance_zip("2020-01", days=1)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            name = archive.namelist()[0]
            rows = list(csv.reader(io.StringIO(archive.read(name).decode("ascii"))))
        rows[0][6] = str(int(rows[0][6]) - 1)
        payload = io.StringIO(newline="")
        csv.writer(payload, lineterminator="\n").writerows(rows)
        altered = io.BytesIO()
        with zipfile.ZipFile(altered, mode="w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr(name, payload.getvalue().encode("ascii"))

        with self.assertRaisesRegex(SourceReject, "DATA_REJECT:BINANCE_CLOCK"):
            parse_binance_month(altered.getvalue(), month="2020-01")

    def test_frozen_calendar_reuses_complete_1827_day_contract(self) -> None:
        self.assertEqual(1_827, len(volume_source.expected_days()))


if __name__ == "__main__":
    unittest.main()
