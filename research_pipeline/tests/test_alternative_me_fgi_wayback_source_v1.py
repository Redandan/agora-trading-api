from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import unittest
from unittest.mock import patch

from research import alternative_me_fgi_wayback_source_v1 as source


def _timestamp(day: str) -> str:
    return str(
        int(
            datetime.fromisoformat(day)
            .replace(tzinfo=timezone.utc)
            .timestamp()
        )
    )


def _complete_payload() -> bytes:
    data = [
        {
            "value": "20" if index % 2 else "50",
            "value_classification": "Extreme Fear" if index % 2 else "Neutral",
            "timestamp": _timestamp(day),
        }
        for index, day in enumerate(source.expected_days())
    ]
    data.reverse()
    return json.dumps(
        {
            "name": "Fear and Greed Index",
            "data": data,
            "metadata": {"error": None},
        },
        separators=(",", ":"),
    ).encode("ascii")


class AlternativeMeFgiWaybackSourceV1Test(unittest.TestCase):
    def test_selected_calendar_has_2490_days(self) -> None:
        self.assertEqual(2_490, len(source.expected_days()))
        self.assertEqual("2018-02-01", source.expected_days()[0])
        self.assertEqual("2024-11-25", source.expected_days()[-1])

    def test_complete_payload_is_sorted_and_normalized(self) -> None:
        raw = _complete_payload()
        expected_digest = source.cdx_digest(raw)
        with patch.object(source, "EXPECTED_CDX_DIGEST", expected_digest):
            rows, diagnostics = source.parse_source(raw)

        self.assertEqual(2_490, len(rows))
        self.assertEqual("2018-02-01", rows[0].day)
        self.assertEqual("2024-11-25", rows[-1].day)
        self.assertEqual(2_490, diagnostics["selected_row_count"])
        normalized = source.normalized_csv(rows[:1])
        self.assertTrue(
            normalized.startswith(
                b"date,value,value_classification,source_timestamp\n2018-02-01,"
            )
        )

    def test_missing_day_is_rejected(self) -> None:
        payload = json.loads(_complete_payload())
        del payload["data"][10]
        raw = json.dumps(payload, separators=(",", ":")).encode("ascii")
        with patch.object(source, "EXPECTED_CDX_DIGEST", source.cdx_digest(raw)):
            with self.assertRaisesRegex(source.SourceReject, "DATA_REJECT:COVERAGE"):
                source.parse_source(raw)

    def test_unknown_label_is_rejected(self) -> None:
        payload = json.loads(_complete_payload())
        payload["data"][0]["value_classification"] = "Panic"
        raw = json.dumps(payload, separators=(",", ":")).encode("ascii")
        with patch.object(source, "EXPECTED_CDX_DIGEST", source.cdx_digest(raw)):
            with self.assertRaisesRegex(source.SourceReject, "DATA_REJECT:ROW_VALUE"):
                source.parse_source(raw)

    def test_digest_uses_wayback_sha1_base32_form(self) -> None:
        raw = b"archived payload"
        expected = (
            __import__("base64")
            .b32encode(hashlib.sha1(raw).digest())
            .decode("ascii")
            .rstrip("=")
        )
        self.assertEqual(expected, source.cdx_digest(raw))


if __name__ == "__main__":
    unittest.main()
