from __future__ import annotations

from research import binance_btcusdt_delivery_archive_inventory_probe_v1 as probe
import unittest


def bucket_xml(
    *,
    prefix: str,
    prefixes: tuple[str, ...] = (),
    keys: tuple[str, ...] = (),
) -> bytes:
    prefix_values = "".join(
        f"<CommonPrefixes><Prefix>{value}</Prefix></CommonPrefixes>"
        for value in prefixes
    )
    key_values = "".join(
        f"<Contents><Key>{value}</Key></Contents>" for value in keys
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
        f"<Prefix>{prefix}</Prefix><Marker></Marker><IsTruncated>false</IsTruncated>"
        f"{key_values}{prefix_values}</ListBucketResult>"
    ).encode("utf-8")


def archive_keys(prefix: str, stem: str, months: tuple[str, ...]) -> tuple[str, ...]:
    values: list[str] = []
    for month in months:
        key = f"{prefix}{stem}-1h-{month}.zip"
        values.extend((key, f"{key}.CHECKSUM"))
    return tuple(values)


class BinanceBtcusdtDeliveryArchiveInventoryProbeV1Test(unittest.TestCase):
    def setUp(self) -> None:
        self.symbol = "BTCUSDT_210326"
        self.spec = {
            "selection_boundary": {
                "exact_contract_symbols": [self.symbol],
                "cutoff_date": "2024-12-31",
                "reference_first_month": "2021-03",
                "reference_last_month": "2021-03",
            }
        }

    def fetcher(self, query: dict[str, str]) -> bytes:
        prefix = query["prefix"]
        if prefix == probe.FUTURES_KLINE_ROOT:
            return bucket_xml(
                prefix=prefix, prefixes=(f"{prefix}{self.symbol}/",)
            )
        if prefix == probe.FUTURES_MARK_ROOT:
            return bucket_xml(
                prefix=prefix, prefixes=(f"{prefix}{self.symbol}/",)
            )
        if prefix == f"{probe.FUTURES_KLINE_ROOT}{self.symbol}/1h/":
            return bucket_xml(
                prefix=prefix,
                keys=archive_keys(
                    prefix, self.symbol, ("2021-01", "2021-02", "2021-03")
                ),
            )
        if prefix == f"{probe.FUTURES_MARK_ROOT}{self.symbol}/1h/":
            return bucket_xml(
                prefix=prefix,
                keys=archive_keys(prefix, self.symbol, ("2021-02", "2021-03")),
            )
        if prefix == probe.SPOT_PREFIX:
            return bucket_xml(
                prefix=prefix,
                keys=archive_keys(prefix, "BTCUSDT", ("2021-03",)),
            )
        if prefix == probe.INDEX_PREFIX:
            return bucket_xml(
                prefix=prefix,
                keys=archive_keys(prefix, "BTCUSDT", ("2021-03",)),
            )
        raise AssertionError(query)

    def test_metadata_only_inventory_requires_exact_trade_mark_and_reference_coverage(
        self,
    ) -> None:
        result = probe.build_inventory(self.spec, self.fetcher)
        self.assertEqual(1, result["exact_contract_count"])
        self.assertEqual(self.symbol, result["contracts"][0]["symbol"])
        self.assertEqual("2021-02", result["contracts"][0]["overlap_first_month"])
        self.assertTrue(result["all_required_archive_checksum_pairs_present"])
        self.assertFalse(result["market_data_rows_opened"])
        self.assertFalse(result["price_or_basis_values_opened"])

    def test_contract_archive_must_end_in_expiry_month(self) -> None:
        def incomplete_fetcher(query: dict[str, str]) -> bytes:
            prefix = query["prefix"]
            if prefix == f"{probe.FUTURES_MARK_ROOT}{self.symbol}/1h/":
                return bucket_xml(
                    prefix=prefix,
                    keys=archive_keys(prefix, self.symbol, ("2021-02",)),
                )
            return self.fetcher(query)

        with self.assertRaisesRegex(
            probe.InventoryReject, "SOURCE_REJECT:EXPIRY_MONTH_COVERAGE"
        ):
            probe.build_inventory(self.spec, incomplete_fetcher)

    def test_missing_checksum_is_rejected(self) -> None:
        prefix = f"{probe.FUTURES_KLINE_ROOT}{self.symbol}/1h/"
        zip_key = f"{prefix}{self.symbol}-1h-2021-03.zip"

        def missing_checksum_fetcher(query: dict[str, str]) -> bytes:
            if query["prefix"] == prefix:
                return bucket_xml(prefix=prefix, keys=(zip_key,))
            return self.fetcher(query)

        with self.assertRaisesRegex(
            probe.InventoryReject, "SOURCE_REJECT:MISSING_CHECKSUM"
        ):
            probe.build_inventory(self.spec, missing_checksum_fetcher)


if __name__ == "__main__":
    unittest.main()
