from __future__ import annotations

from pathlib import Path
import unittest

from research.binance_btc_paxg_archive_inventory_probe_v1 import (
    DEFAULT_SPEC,
    EXPECTED_SYMBOLS,
    ROOT_PREFIX,
    SourceReject,
    build_report,
    load_spec,
    month_sequence,
)


def bucket_xml(*, prefix: str, keys: tuple[str, ...]) -> bytes:
    key_values = "".join(
        f"<Contents><Key>{value}</Key></Contents>" for value in sorted(keys)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
        f"<Prefix>{prefix}</Prefix><Marker></Marker>"
        f"<IsTruncated>false</IsTruncated>{key_values}</ListBucketResult>"
    ).encode("utf-8")


def keys_for(symbol: str, months: list[str]) -> tuple[str, ...]:
    values: list[str] = []
    prefix = f"{ROOT_PREFIX}{symbol}/1d/"
    for month in months:
        key = f"{prefix}{symbol}-1d-{month}.zip"
        values.extend((key, f"{key}.CHECKSUM"))
    return tuple(values)


class BinanceBtcPaxgArchiveInventoryProbeV1Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = load_spec(Path(DEFAULT_SPEC))
        cls.months = month_sequence("2020-09", "2024-12")

    def test_frozen_spec_and_complete_metadata_build_deterministic_report(self) -> None:
        def complete_fetcher(query: dict[str, str]) -> bytes:
            symbol = next(
                symbol
                for symbol in EXPECTED_SYMBOLS
                if query["prefix"] == f"{ROOT_PREFIX}{symbol}/1d/"
            )
            return bucket_xml(prefix=query["prefix"], keys=keys_for(symbol, self.months))

        run1 = build_report(self.spec, fetcher=complete_fetcher)
        run2 = build_report(self.spec, fetcher=complete_fetcher)
        self.assertEqual(run1, run2)
        self.assertEqual(
            "SOURCE_METADATA_GATE_PASS_PRICE_ACCESS_STILL_DENIED", run1["status"]
        )
        self.assertEqual(52, run1["inventory"]["required_month_count_per_symbol"])
        self.assertEqual(104, run1["inventory"]["common_required_zip_count"])

    def test_missing_required_paxg_month_fails_closed(self) -> None:
        def missing_fetcher(query: dict[str, str]) -> bytes:
            symbol = "PAXGUSDT" if "PAXGUSDT" in query["prefix"] else "BTCUSDT"
            months = self.months[:-1] if symbol == "PAXGUSDT" else self.months
            return bucket_xml(prefix=query["prefix"], keys=keys_for(symbol, months))

        with self.assertRaisesRegex(
            SourceReject, "SOURCE_REJECT:REQUIRED_MONTHS:PAXGUSDT:2024-12"
        ):
            build_report(self.spec, fetcher=missing_fetcher)

    def test_missing_checksum_fails_before_value_access(self) -> None:
        def missing_checksum_fetcher(query: dict[str, str]) -> bytes:
            symbol = "PAXGUSDT" if "PAXGUSDT" in query["prefix"] else "BTCUSDT"
            keys = list(keys_for(symbol, self.months))
            if symbol == "BTCUSDT":
                keys.remove(
                    f"{ROOT_PREFIX}BTCUSDT/1d/BTCUSDT-1d-2020-09.zip.CHECKSUM"
                )
            return bucket_xml(prefix=query["prefix"], keys=tuple(keys))

        with self.assertRaisesRegex(
            SourceReject, "SOURCE_REJECT:MISSING_CHECKSUM:BTCUSDT"
        ):
            build_report(self.spec, fetcher=missing_checksum_fetcher)


if __name__ == "__main__":
    unittest.main()
