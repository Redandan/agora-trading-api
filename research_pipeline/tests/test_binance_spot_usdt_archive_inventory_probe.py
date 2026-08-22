from __future__ import annotations

from research.binance_spot_usdt_archive_inventory_probe import (
    ROOT_PREFIX,
    SourceReject,
    inspect_daily_archives,
    parse_bucket_page,
    symbols_from_prefixes,
)
import unittest


def bucket_xml(
    *,
    prefix: str,
    marker: str = "",
    truncated: bool = False,
    next_marker: str | None = None,
    prefixes: tuple[str, ...] = (),
    keys: tuple[str, ...] = (),
) -> bytes:
    next_value = "" if next_marker is None else f"<NextMarker>{next_marker}</NextMarker>"
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
        f"<Prefix>{prefix}</Prefix><Marker>{marker}</Marker>"
        f"<IsTruncated>{str(truncated).lower()}</IsTruncated>{next_value}"
        f"{key_values}{prefix_values}</ListBucketResult>"
    ).encode("utf-8")


class BinanceSpotUsdtArchiveInventoryProbeTest(unittest.TestCase):
    def test_namespaced_symbol_page_is_parsed_and_normalized(self) -> None:
        prefixes = (
            f"{ROOT_PREFIX}BCCUSDT/",
            f"{ROOT_PREFIX}BTCUSDT/",
            f"{ROOT_PREFIX}MCOUSDT/",
        )
        raw = bucket_xml(prefix=ROOT_PREFIX, prefixes=prefixes)
        page = parse_bucket_page(raw, expected_prefix=ROOT_PREFIX, expected_marker="")
        self.assertFalse(page["is_truncated"])
        self.assertEqual(
            ["BCCUSDT", "BTCUSDT", "MCOUSDT"],
            symbols_from_prefixes(page["prefixes"]),
        )

    def test_truncated_page_requires_next_marker(self) -> None:
        raw = bucket_xml(prefix=ROOT_PREFIX, truncated=True)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:NEXT_MARKER"):
            parse_bucket_page(raw, expected_prefix=ROOT_PREFIX, expected_marker="")

    def test_non_ascii_symbol_can_be_excluded_before_usdt_universe_design(self) -> None:
        prefixes = (
            f"{ROOT_PREFIX}BTCUSDT/",
            f"{ROOT_PREFIX}幣安人生TRY/",
        )
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:SYMBOL"):
            symbols_from_prefixes(list(prefixes))
        self.assertEqual(
            ["BTCUSDT"],
            symbols_from_prefixes(list(prefixes), reject_non_ascii=False),
        )

    def test_daily_archives_require_one_checksum_per_zip(self) -> None:
        symbol = "MCOUSDT"
        prefix = f"{ROOT_PREFIX}{symbol}/1d/"
        zip_key = f"{prefix}{symbol}-1d-2020-10.zip"

        def complete_fetcher(query: dict[str, str]) -> bytes:
            self.assertEqual(prefix, query["prefix"])
            return bucket_xml(
                prefix=prefix,
                keys=(zip_key, f"{zip_key}.CHECKSUM"),
            )

        result = inspect_daily_archives(symbol, complete_fetcher)
        self.assertEqual("2020-10", result["first_month"])
        self.assertEqual(1, result["monthly_archive_count"])

        def missing_fetcher(_: dict[str, str]) -> bytes:
            return bucket_xml(prefix=prefix, keys=(zip_key,))

        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:MISSING_CHECKSUM"):
            inspect_daily_archives(symbol, missing_fetcher)


if __name__ == "__main__":
    unittest.main()
