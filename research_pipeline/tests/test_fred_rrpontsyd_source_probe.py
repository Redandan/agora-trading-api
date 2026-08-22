from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.fred_rrpontsyd_source_probe import (
    SourceReject,
    feature_feasibility,
    parse_rows,
    validate_rows,
)


def _fixture(*, alternating: bool = True) -> bytes:
    lines = ["observation_date,RRPONTSYD"]
    week_start = date(2018, 1, 1)
    study_end = date(2024, 12, 31)
    week_index = 0
    while week_start <= study_end:
        block_offset = Decimal("500") if alternating and (week_index // 4) % 2 else Decimal("0")
        for day_offset in range(5):
            day = week_start + timedelta(days=day_offset)
            if day > study_end:
                break
            value = Decimal("1000") + block_offset + Decimal(week_index) / Decimal("1000")
            lines.append(f"{day.isoformat()},{value}")
        week_start += timedelta(days=7)
        week_index += 1
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredRrpontsydSourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_weekly_clock_and_both_state_support(self) -> None:
        rows = parse_rows(_fixture())
        validate_rows(rows)
        feasibility = feature_feasibility(rows)
        self.assertEqual(feasibility["complete_week_count"], 365)
        self.assertEqual(feasibility["evaluations"], 361)
        self.assertEqual(feasibility["first_evaluable_endpoint_day"], "2018-02-02")
        self.assertEqual(feasibility["first_effective_time"], "2018-02-07T00:00:00Z")
        self.assertEqual(feasibility["design"]["evaluations"], 209)
        self.assertEqual(feasibility["validation"]["evaluations"], 104)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))

    def test_one_sided_state_fails_before_btc_outcomes(self) -> None:
        rows = parse_rows(_fixture(alternating=False))
        validate_rows(rows)
        feasibility = feature_feasibility(rows)
        self.assertEqual(feasibility["supportive_weeks"], 0)
        self.assertFalse(feasibility["design"]["support_pass"])
        self.assertFalse(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("DATA_REJECT_"))

    def test_accepts_explicit_holiday_blank_but_rejects_dot_and_duplicate_date(self) -> None:
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HEADER"):
            parse_rows(_fixture().replace(b"RRPONTSYD", b"WTREGEN", 1))

        missing = _fixture().decode("utf-8").splitlines()
        missing[10] = missing[10].split(",")[0] + ","
        missing_rows = parse_rows(("\n".join(missing) + "\n").encode("utf-8"))
        validate_rows(missing_rows)
        self.assertEqual(sum(value is None for _, value, _ in missing_rows), 1)
        self.assertTrue(feature_feasibility(missing_rows)["admission_status"].startswith("PASS_"))

        invalid = _fixture().decode("utf-8").splitlines()
        invalid[10] = invalid[10].split(",")[0] + ",."
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:ROW"):
            parse_rows(("\n".join(invalid) + "\n").encode("utf-8"))

        rows = parse_rows(_fixture())
        rows[100] = (rows[99][0], rows[100][1], rows[100][2])
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:DATE_ORDER_OR_GAP"):
            validate_rows(rows)


if __name__ == "__main__":
    unittest.main()
