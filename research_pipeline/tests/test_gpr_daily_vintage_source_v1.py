from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

import pandas as pd

from research import gpr_daily_vintage_source_v1 as source


def _complete_frame() -> pd.DataFrame:
    rows: list[dict[str, object]] = []
    current = source.FIRST_DAY
    while current <= source.LAST_DAY:
        rows.append(
            {
                "date": current.isoformat(),
                "GPRD": "100.25",
                "GPRD_ACT": 40,
                "GPRD_THREAT": 60,
                "GPRD_MA30": 99,
                "GPRD_MA7": 101,
                "N10D": 12,
                "event": "",
            }
        )
        current += timedelta(days=1)
    return pd.DataFrame(rows, columns=sorted(source.REQUIRED_COLUMNS))


class GprDailyVintageSourceV1Test(unittest.TestCase):
    def test_complete_daily_frame_is_accepted_without_value_repair(self) -> None:
        rows, diagnostics = source.validate_frame(_complete_frame())

        self.assertEqual(len(rows), 2_557)
        self.assertEqual(rows[0], ("2018-01-01", Decimal("100.25")))
        self.assertEqual(rows[-1], ("2024-12-31", Decimal("100.25")))
        self.assertEqual(diagnostics["selected_row_count"], 2_557)

    def test_missing_required_field_is_rejected(self) -> None:
        frame = _complete_frame().drop(columns=["GPRD_THREAT"])

        with self.assertRaisesRegex(source.SourceReject, "DATA_REJECT:COLUMNS"):
            source.validate_frame(frame)

    def test_duplicate_day_is_rejected(self) -> None:
        frame = _complete_frame()
        frame.loc[1, "date"] = frame.loc[0, "date"]

        with self.assertRaisesRegex(
            source.SourceReject, "DATA_REJECT:DUPLICATE_DAY:2018-01-01"
        ):
            source.validate_frame(frame)

    def test_missing_calendar_day_is_rejected(self) -> None:
        frame = _complete_frame().iloc[1:].reset_index(drop=True)

        with self.assertRaisesRegex(source.SourceReject, "DATA_REJECT:COVERAGE"):
            source.validate_frame(frame)

    def test_negative_gpr_value_is_rejected(self) -> None:
        frame = _complete_frame()
        frame.loc[0, "GPRD"] = -1

        with self.assertRaisesRegex(source.SourceReject, "DATA_REJECT:GPRD_VALUE"):
            source.validate_frame(frame)

    def test_normalized_csv_is_stable_and_minimal(self) -> None:
        raw = source.normalized_csv(
            [(date(2024, 1, 1).isoformat(), Decimal("1.2500"))]
        )

        self.assertEqual(raw, b"date,gprd\n2024-01-01,1.2500\n")


if __name__ == "__main__":
    unittest.main()
