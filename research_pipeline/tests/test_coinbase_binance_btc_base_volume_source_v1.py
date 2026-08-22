from __future__ import annotations

import csv
from datetime import date, datetime, timedelta, timezone
import io
import json
import unittest
import zipfile

from research.coinbase_binance_btc_base_volume_source_v1 import (
    SourceReject,
    expected_days,
    half_year_partitions,
    normalized_csv,
    parse_binance_month,
    parse_coinbase_partition,
)


def unix_day(value: date) -> int:
    return int(datetime(value.year, value.month, value.day, tzinfo=timezone.utc).timestamp())


def coinbase_rows(start: date, end: date) -> bytes:
    rows: list[list[object]] = []
    current = start
    while current < end:
        rows.append([unix_day(current), "1", "2", "1", "2", "10.5"])
        current += timedelta(days=1)
    rows.reverse()
    return json.dumps(rows, separators=(",", ":")).encode("ascii")


def binance_zip(month: str, days: int = 2) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    start = date.fromisoformat(f"{month}-01")
    for offset in range(days):
        current = start + timedelta(days=offset)
        open_ms = unix_day(current) * 1000
        writer.writerow(
            [
                open_ms,
                "100",
                "120",
                "90",
                "110",
                "25.75",
                open_ms + 86_400_000 - 1,
                "2800",
                10,
                "13",
                "1400",
                "0",
            ]
        )
    raw = io.BytesIO()
    filename = f"BTCUSDT-1d-{month}.csv"
    with zipfile.ZipFile(raw, mode="w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(filename, output.getvalue().encode("ascii"))
    return raw.getvalue()


class CoinbaseBinanceBtcBaseVolumeSourceV1Test(unittest.TestCase):
    def test_frozen_calendar_has_ten_partitions_and_1827_days(self) -> None:
        self.assertEqual(10, len(half_year_partitions()))
        self.assertEqual("2020-01-01", expected_days()[0])
        self.assertEqual("2024-12-31", expected_days()[-1])
        self.assertEqual(1827, len(expected_days()))

    def test_coinbase_reverse_rows_are_sorted_and_extra_day_is_recorded(self) -> None:
        start = date(2020, 1, 1)
        end = date(2020, 1, 4)
        rows = json.loads(coinbase_rows(start, end))
        rows.append([unix_day(date(2019, 12, 31)), "1", "2", "1", "2", "9"])
        bars, excluded = parse_coinbase_partition(
            json.dumps(rows, separators=(",", ":")).encode("ascii"),
            start=start,
            end=end,
        )
        self.assertEqual(["2020-01-01", "2020-01-02", "2020-01-03"], [bar.day for bar in bars])
        self.assertEqual(["2019-12-31"], excluded)
        self.assertEqual(b"venue,symbol,date,base_volume_btc\nCOINBASE,BTC-USD,2020-01-01,10.5\nCOINBASE,BTC-USD,2020-01-02,10.5\nCOINBASE,BTC-USD,2020-01-03,10.5\n", normalized_csv(bars))

    def test_coinbase_missing_day_rejects_before_feature(self) -> None:
        start = date(2020, 1, 1)
        end = date(2020, 1, 4)
        rows = json.loads(coinbase_rows(start, end))
        del rows[1]
        with self.assertRaisesRegex(SourceReject, "DATA_REJECT:COINBASE_DAYS"):
            parse_coinbase_partition(
                json.dumps(rows, separators=(",", ":")).encode("ascii"),
                start=start,
                end=end,
            )

    def test_binance_parser_uses_native_base_volume_and_exact_clock(self) -> None:
        bars = parse_binance_month(binance_zip("2020-01"), month="2020-01")
        self.assertEqual(["2020-01-01", "2020-01-02"], [bar.day for bar in bars])
        self.assertEqual("25.75", format(bars[0].base_volume_btc, "f"))

    def test_binance_partial_day_rejects(self) -> None:
        raw = binance_zip("2020-01", days=1)
        source = io.BytesIO(raw)
        with zipfile.ZipFile(source) as archive:
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


if __name__ == "__main__":
    unittest.main()
