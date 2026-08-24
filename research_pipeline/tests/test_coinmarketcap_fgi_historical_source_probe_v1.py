from __future__ import annotations

from datetime import datetime, timezone
import json
import unittest

from research import coinmarketcap_fgi_historical_source_probe_v1 as source


def _raw(rows: list[dict[str, object]]) -> bytes:
    return json.dumps(
        {
            "data": rows,
            "status": {
                "timestamp": "2026-08-24T16:44:00.000Z",
                "error_code": 0,
                "error_message": "",
                "elapsed": 1,
                "credit_count": 1,
                "notice": "",
            },
        },
        separators=(",", ":"),
    ).encode("utf-8")


def _item(day: str, classification: str = "Fear", value: int = 25) -> dict[str, object]:
    timestamp = int(
        datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp()
    )
    return {
        "timestamp": str(timestamp),
        "value": value,
        "value_classification": classification,
    }


class CoinMarketCapFgiHistoricalSourceProbeV1Test(unittest.TestCase):
    def test_frozen_spec_and_all_bindings_verify(self) -> None:
        spec = source.verify_spec()
        self.assertEqual(spec["request_contract"]["fixed_request_count"], 6)

    def test_parse_accepts_official_shape_at_utc_midnight(self) -> None:
        rows = source.parse_page(
            _raw([_item("2024-01-02", "Extreme Fear", 18)]), 1
        )
        self.assertEqual(rows[0].day.isoformat(), "2024-01-02")
        self.assertEqual(rows[0].classification, "Extreme Fear")

    def test_parse_rejects_non_midnight_timestamp(self) -> None:
        item = _item("2024-01-02")
        item["timestamp"] = str(int(item["timestamp"]) + 1)
        with self.assertRaisesRegex(source.SourceReject, "NOT_UTC_MIDNIGHT"):
            source.parse_page(_raw([item]), 1)

    def test_merge_rejects_cross_page_duplicate_day(self) -> None:
        rows = source.parse_page(_raw([_item("2024-01-02")]), 1)
        with self.assertRaisesRegex(source.SourceReject, "DUPLICATE_DAY"):
            source.merge_rows([rows, rows])

    def test_support_fails_closed_on_incomplete_window(self) -> None:
        rows = source.parse_page(
            _raw(
                [
                    _item("2024-01-01", "Extreme Fear", 18),
                    _item("2024-01-02", "Fear", 25),
                ]
            ),
            1,
        )
        result = source.summarize_feasibility(rows)
        self.assertFalse(result["complete_selected_coverage"])
        self.assertTrue(result["admission_status"].startswith("DATA_REJECT_"))


if __name__ == "__main__":
    unittest.main()
