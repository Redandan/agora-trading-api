from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import hashlib
import io
import zipfile
import unittest

from research_pipeline import binance_usdm_archive as archive


def metrics_rows(day: date = date(2024, 1, 2)) -> list[str]:
    header = ",".join(archive.EXPECTED_HEADER)
    start = datetime.combine(day, datetime.min.time())
    rows = [header]
    for index in range(archive.EXPECTED_ROWS_PER_DAY):
        timestamp = start + timedelta(minutes=archive.EXPECTED_INTERVAL_MINUTES * index)
        rows.append(
            ",".join(
                (
                    timestamp.strftime("%Y-%m-%d %H:%M:%S"),
                    archive.SYMBOL,
                    str(1000 + index),
                    str(40_000_000 + index),
                    "1.20",
                    "1.10",
                    "0.95",
                    "1.05",
                )
            )
        )
    return rows


def zipped(
    rows: list[str],
    *,
    archive_name: str = "BTCUSDT-metrics-2024-01-02.zip",
    member_name: str | None = None,
    compression: int = zipfile.ZIP_DEFLATED,
) -> tuple[bytes, bytes]:
    output = io.BytesIO()
    csv_name = member_name or archive_name.removesuffix(".zip") + ".csv"
    with zipfile.ZipFile(output, "w", compression=compression) as target:
        target.writestr(csv_name, ("\n".join(rows) + "\n").encode("utf-8"))
    raw = output.getvalue()
    checksum = f"{hashlib.sha256(raw).hexdigest()}  {archive_name}\n".encode("ascii")
    return raw, checksum


class BinanceUsdmArchiveTest(unittest.TestCase):
    def test_official_checksum_accepts_complete_day_and_exact_duplicate(self) -> None:
        rows = metrics_rows()
        rows.insert(2, rows[1])
        raw, checksum = zipped(rows)

        bundle = archive.load_daily_metrics_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(bundle.day, date(2024, 1, 2))
        self.assertEqual(len(bundle.observations), archive.EXPECTED_ROWS_PER_DAY)
        self.assertEqual(bundle.archive_sha256, hashlib.sha256(raw).hexdigest())
        self.assertRegex(bundle.normalized_payload_sha256, r"^[0-9a-f]{64}$")
        self.assertEqual(bundle.observations[0].sum_open_interest, "1000")
        self.assertEqual(bundle.observations[0].count_toptrader_long_short_ratio, "1.20")

    def test_checksum_mismatch_fails_before_zip_parsing(self) -> None:
        raw, checksum = zipped(metrics_rows())
        bad = ("0" * 64 + "  BTCUSDT-metrics-2024-01-02.zip\n").encode("ascii")
        with self.assertRaisesRegex(archive.ArchiveReject, "does not match"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, bad
            )
        self.assertNotEqual(checksum, bad)

    def test_conflicting_duplicate_timestamp_is_rejected(self) -> None:
        rows = metrics_rows()
        conflicting = rows[1].replace(",1000,", ",9999,")
        rows.insert(2, conflicting)
        raw, checksum = zipped(rows)
        with self.assertRaisesRegex(archive.ArchiveReject, "conflicting duplicate"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

    def test_gap_and_incomplete_day_are_rejected(self) -> None:
        rows = metrics_rows()
        del rows[100]
        raw, checksum = zipped(rows)
        with self.assertRaisesRegex(archive.ArchiveReject, "complete gap-free"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

    def test_post_2024_archive_is_rejected_without_opening_contents(self) -> None:
        raw, checksum = zipped(
            metrics_rows(date(2025, 1, 1)),
            archive_name="BTCUSDT-metrics-2025-01-01.zip",
        )
        with self.assertRaisesRegex(archive.ArchiveReject, "2024-12-31 cutoff"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2025-01-01.zip", raw, checksum
            )

    def test_traversal_member_and_bomb_limit_are_rejected(self) -> None:
        raw, checksum = zipped(metrics_rows(), member_name="../escape.csv")
        with self.assertRaisesRegex(archive.ArchiveReject, "unsafe"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

        raw, checksum = zipped(metrics_rows())
        with self.assertRaisesRegex(archive.ArchiveReject, "uncompressed-byte"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip",
                raw,
                checksum,
                limits=archive.ArchiveLimits(max_uncompressed_bytes=128),
            )

    def test_invalid_ratio_and_wrong_symbol_are_rejected(self) -> None:
        rows = metrics_rows()
        rows[1] = rows[1].replace(",1.20,", ",NaN,")
        raw, checksum = zipped(rows)
        with self.assertRaisesRegex(archive.ArchiveReject, "exact unsigned decimal"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

        rows = metrics_rows()
        rows[1] = rows[1].replace(",BTCUSDT,", ",ETHUSDT,")
        raw, checksum = zipped(rows)
        with self.assertRaisesRegex(archive.ArchiveReject, "not BTCUSDT"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

    def test_exact_scientific_zero_ratio_is_preserved(self) -> None:
        rows = metrics_rows()
        rows[1] = rows[1].removesuffix(",1.05") + ",0E-8"
        raw, checksum = zipped(rows)
        bundle = archive.load_daily_metrics_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )
        self.assertEqual(bundle.observations[0].sum_taker_long_short_vol_ratio, "0E-8")
        self.assertEqual(
            bundle.observations[0].decimal("sum_taker_long_short_vol_ratio"),
            Decimal("0"),
        )

    def test_unused_missing_ratio_is_allowed_only_for_bound_flush_family(self) -> None:
        rows = metrics_rows()
        rows[1] = rows[1].removesuffix(",1.05") + ","
        raw, checksum = zipped(rows)
        with self.assertRaisesRegex(archive.ArchiveReject, "exact unsigned decimal"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )
        bundle = archive.load_daily_metrics_archive(
            "BTCUSDT-metrics-2024-01-02.zip",
            raw,
            checksum,
            feature_family="joint-price-open-interest-deleveraging-flush",
        )
        self.assertEqual(bundle.feature_family, "joint-price-open-interest-deleveraging-flush")
        self.assertEqual(bundle.observations[0].sum_taker_long_short_vol_ratio, "")

        with self.assertRaisesRegex(archive.ArchiveReject, "exact unsigned decimal"):
            archive.load_daily_metrics_archive(
                "BTCUSDT-metrics-2024-01-02.zip",
                raw,
                checksum,
                feature_family="joint-perpetual-taker-flow-open-interest-confirmation",
            )


if __name__ == "__main__":
    unittest.main()
