from __future__ import annotations

from pathlib import Path
import unittest

from research.binance_exact_spot_pair_archive_inventory_probe_v1 import (
    ROOT_PREFIX,
    SourceReject,
    build_report,
    load_spec,
    month_sequence,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
SPEC = REPO_ROOT / (
    "research_pipeline/examples/"
    "btc-usdcusdt-peg-dislocation-risk-veto-source-inventory.v1.spec.json"
)


def bucket_xml(*, prefix: str, keys: tuple[str, ...]) -> bytes:
    contents = "".join(
        f"<Contents><Key>{key}</Key></Contents>" for key in sorted(keys)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
        f"<Prefix>{prefix}</Prefix><Marker></Marker><IsTruncated>false</IsTruncated>"
        f"{contents}</ListBucketResult>"
    ).encode("utf-8")


def keys_for(symbol: str, months: list[str]) -> tuple[str, ...]:
    prefix = f"{ROOT_PREFIX}{symbol}/1d/"
    keys: list[str] = []
    for month in months:
        key = f"{prefix}{symbol}-1d-{month}.zip"
        keys.extend((key, f"{key}.CHECKSUM"))
    return tuple(keys)


class BinanceExactSpotPairArchiveInventoryProbeV1Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = load_spec(SPEC)
        cls.months = month_sequence("2020-01", "2024-12")

    def test_exact_pair_complete_metadata_is_deterministic(self) -> None:
        def fetcher(query: dict[str, str]) -> bytes:
            symbol = "USDCUSDT" if "USDCUSDT" in query["prefix"] else "BTCUSDT"
            return bucket_xml(prefix=query["prefix"], keys=keys_for(symbol, self.months))

        run1 = build_report(self.spec, fetcher=fetcher)
        run2 = build_report(self.spec, fetcher=fetcher)
        self.assertEqual(run1, run2)
        self.assertEqual("SOURCE_METADATA_GATE_PASS_VALUE_ACCESS_STILL_DENIED", run1["status"])
        self.assertEqual(120, run1["inventory"]["common_required_zip_count"])

    def test_one_missing_usdcusdt_month_closes_exact_source(self) -> None:
        def fetcher(query: dict[str, str]) -> bytes:
            symbol = "USDCUSDT" if "USDCUSDT" in query["prefix"] else "BTCUSDT"
            months = self.months[1:] if symbol == "USDCUSDT" else self.months
            return bucket_xml(prefix=query["prefix"], keys=keys_for(symbol, months))

        with self.assertRaisesRegex(
            SourceReject, "SOURCE_REJECT:REQUIRED_MONTHS:USDCUSDT:2020-01"
        ):
            build_report(self.spec, fetcher=fetcher)

    def test_bad_month_grammar_is_rejected(self) -> None:
        with self.assertRaisesRegex(SourceReject, "SPEC_REJECT:MONTH_GRAMMAR"):
            month_sequence("2020-13", "2024-12")


if __name__ == "__main__":
    unittest.main()
