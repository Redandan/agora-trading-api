from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import io
import unittest
import zipfile

from research.binance_btcusdt_funding_carry_corpus_v1 import (
    CorpusReject,
    deterministic_gzip,
    months,
    parse_checksum,
    parse_funding_csv,
    parse_kline_csv,
    unzip_one,
    verify_spec,
    SPEC_PATH,
)


def kline_row(moment: datetime) -> str:
    opened = int(moment.timestamp() * 1000)
    return ",".join(
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


class FundingCarryCorpusV1Test(unittest.TestCase):
    def test_frozen_spec_and_source_bindings_verify(self) -> None:
        value = verify_spec(SPEC_PATH)
        self.assertEqual(240, value["source_contract"]["archive_count"])
        self.assertEqual("0.25", value["strategy_policy"]["spot_budget_fraction"])

    def test_exact_sixty_month_pre2025_inventory(self) -> None:
        values = months()
        self.assertEqual(60, len(values))
        self.assertEqual("2020-01", values[0])
        self.assertEqual("2024-12", values[-1])

    def test_checksum_and_single_member_zip_are_strict(self) -> None:
        payload = b"payload\n"
        target = io.BytesIO()
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("expected.csv", payload)
        raw = target.getvalue()
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual(
            digest,
            parse_checksum(
                f"{digest}  expected.zip\n".encode("ascii"), filename="expected.zip"
            ),
        )
        self.assertEqual(payload, unzip_one(raw, member="expected.csv"))
        with self.assertRaisesRegex(CorpusReject, "CHECKSUM_FORMAT"):
            parse_checksum(
                f"{digest}  wrong.zip\n".encode("ascii"), filename="expected.zip"
            )

    def test_hour_and_funding_clocks_are_exact(self) -> None:
        moment = datetime(2020, 1, 1, tzinfo=timezone.utc)
        bars = parse_kline_csv(
            (kline_row(moment) + "\n").encode("ascii"),
            month="2020-01",
            dataset="spot_klines_1h",
        )
        self.assertEqual(1, len(bars))
        funding = parse_funding_csv(
            (
                "calc_time,funding_interval_hours,last_funding_rate\n"
                f"{int(moment.timestamp() * 1000)},8,0.0001\n"
            ).encode("ascii"),
            month="2020-01",
        )
        self.assertEqual("0.0001", format(funding[0].rate, "f"))
        invalid = kline_row(moment).split(",")
        invalid[6] = str(int(invalid[0]) + 1)
        with self.assertRaisesRegex(CorpusReject, "HOUR_CLOCK"):
            parse_kline_csv(
                (",".join(invalid) + "\n").encode("ascii"),
                month="2020-01",
                dataset="spot_klines_1h",
            )

    def test_normalized_gzip_is_deterministic(self) -> None:
        self.assertEqual(deterministic_gzip(b"abc\n"), deterministic_gzip(b"abc\n"))


if __name__ == "__main__":
    unittest.main()
