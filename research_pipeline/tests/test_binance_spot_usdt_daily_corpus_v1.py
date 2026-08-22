from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import Decimal
import hashlib
import io
import unittest
import zipfile

from research.binance_spot_usdt_daily_corpus_v1 import (
    CorpusReject,
    DailyBar,
    deterministic_gzip,
    is_allowed_symbol,
    normalized_csv,
    parse_checksum,
    parse_daily_zip,
    parse_daily_zip_with_diagnostics,
    select_cohort,
    verify_source_manifest,
)
from pathlib import Path


def daily_row(day: date, close: str = "101") -> str:
    opened = datetime(day.year, day.month, day.day, tzinfo=timezone.utc)
    open_ms = int(opened.timestamp() * 1000)
    return ",".join(
        [
            str(open_ms), "100", "102", "99", close, "12.5",
            str(open_ms + 86_400_000 - 1), "1250", "10", "6", "600", "0",
        ]
    )


def daily_zip(symbol: str, month: str, rows: list[str]) -> bytes:
    target = io.BytesIO()
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{symbol}-1d-{month}.csv", "\n".join(rows) + "\n")
    return target.getvalue()


class BinanceSpotUsdtDailyCorpusV1Test(unittest.TestCase):
    def test_frozen_source_manifest_and_all_pre_outcome_bindings_verify(self) -> None:
        root = Path(__file__).resolve().parents[2]
        manifest = verify_source_manifest(
            root
            / "research_pipeline"
            / "examples"
            / "liquid-crypto-cross-sectional-momentum-long-only-corpus.v1.source-manifest.json"
        )
        self.assertEqual("PRE_OUTCOME_SOURCE_ACQUISITION_ONLY", manifest["research_classification"])

    def test_frozen_symbol_taxonomy_excludes_stable_wrapped_and_leveraged_bases(self) -> None:
        self.assertTrue(is_allowed_symbol("BTCUSDT"))
        self.assertTrue(is_allowed_symbol("MCOUSDT"))
        self.assertFalse(is_allowed_symbol("USDCUSDT"))
        self.assertFalse(is_allowed_symbol("WBTCUSDT"))
        self.assertFalse(is_allowed_symbol("BTCUPUSDT"))
        self.assertFalse(is_allowed_symbol("ETHBEARUSDT"))

    def test_checksum_and_daily_zip_are_strictly_validated(self) -> None:
        symbol = "BTCUSDT"
        month = "2019-12"
        raw = daily_zip(symbol, month, [daily_row(date(2019, 12, 1))])
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual(
            digest,
            parse_checksum(
                f"{digest}  {symbol}-1d-{month}.zip\n".encode("ascii"),
                expected_filename=f"{symbol}-1d-{month}.zip",
            ),
        )
        bars = parse_daily_zip(raw, symbol=symbol, month=month)
        self.assertEqual(1, len(bars))
        self.assertEqual(Decimal("1250"), bars[0].quote_volume)
        with self.assertRaisesRegex(CorpusReject, "CHECKSUM_FORMAT"):
            parse_checksum(
                f"{digest}  wrong.zip\n".encode("ascii"),
                expected_filename=f"{symbol}-1d-{month}.zip",
            )

    def test_only_final_utc_aligned_partial_session_is_excluded_and_recorded(self) -> None:
        symbol = "BEAMUSDT"
        month = "2023-01"
        complete = daily_row(date(2023, 1, 25))
        partial_fields = daily_row(date(2023, 1, 26)).split(",")
        partial_fields[6] = str(int(partial_fields[0]) + 9 * 60 * 60 * 1000 - 1)
        raw = daily_zip(symbol, month, [complete, ",".join(partial_fields)])
        bars, excluded = parse_daily_zip_with_diagnostics(
            raw, symbol=symbol, month=month
        )
        self.assertEqual(["2023-01-25"], [bar.day for bar in bars])
        self.assertEqual(1, len(excluded))
        self.assertEqual("32400000", excluded[0]["duration_ms_inclusive"])

        invalid = daily_zip(symbol, month, [",".join(partial_fields), complete])
        with self.assertRaisesRegex(CorpusReject, "DAILY_CLOCK"):
            parse_daily_zip_with_diagnostics(invalid, symbol=symbol, month=month)

    def test_cohort_is_pre_design_month_only_and_fail_closed(self) -> None:
        archives = {
            f"S{index:02d}USDT": {
                "2019-12": ("zip", "checksum"),
                "2020-01": ("zip2", "checksum2"),
            }
            for index in range(20)
        }
        archives["LATEUSDT"] = {"2020-01": ("zip", "checksum")}
        cohort = select_cohort(archives)
        self.assertEqual(20, len(cohort))
        self.assertNotIn("LATEUSDT", cohort)
        with self.assertRaisesRegex(CorpusReject, "COHORT_SIZE"):
            select_cohort({"BTCUSDT": archives["S00USDT"]})

    def test_normalized_gzip_is_byte_deterministic(self) -> None:
        bars = [
            DailyBar(
                "BTCUSDT", "2019-12-01", Decimal("100"), Decimal("102"),
                Decimal("99"), Decimal("101"), Decimal("1250")
            )
        ]
        normalized = normalized_csv(bars)
        self.assertEqual(deterministic_gzip(normalized), deterministic_gzip(normalized))


if __name__ == "__main__":
    unittest.main()
