from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.fred_t5yie_source_probe import (
    EXPECTED_FIRST_WEEK,
    EXPECTED_LAST_WEEK,
    EXPECTED_WEEKLY_ROWS,
    SourceReject,
    aggregate_weeks,
    deterministic_zip,
    feature_feasibility,
    parse_annual,
)


BASE_WEEK = date(2017, 1, 2)


def _annual_fixture(year: int) -> bytes:
    day = date(year, 1, 1)
    weekdays: list[date] = []
    while day.year == year:
        if day.weekday() <= 4:
            weekdays.append(day)
        day += timedelta(days=1)
    omitted = set([candidate for candidate in weekdays if candidate.weekday() == 0][:8])
    lines = ["observation_date,T5YIE"]
    for observation_date in weekdays:
        if observation_date in omitted:
            value = ""
        else:
            week_index = (observation_date - BASE_WEEK).days // 7
            phase = week_index % 16
            triangle = phase if phase <= 8 else 16 - phase
            value = format(Decimal("1.00") + Decimal(triangle) / Decimal("10"), "f")
        lines.append(f"{observation_date.isoformat()},{value}")
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredT5yieSourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_lattice_support_and_concentration_gates(self) -> None:
        daily = []
        parts = []
        for year in range(2017, 2025):
            raw = _annual_fixture(year)
            parsed, empty_dates, source_rows = parse_annual(raw, year)
            self.assertGreaterEqual(len(parsed), 235)
            self.assertEqual(len(empty_dates), 8)
            self.assertEqual(source_rows, len(parsed) + len(empty_dates))
            daily.extend(parsed)
            parts.append((year, raw))

        weekly, counts = aggregate_weeks(daily)
        self.assertEqual(len(weekly), EXPECTED_WEEKLY_ROWS)
        self.assertEqual(weekly[0][0], EXPECTED_FIRST_WEEK)
        self.assertEqual(weekly[-1][0], EXPECTED_LAST_WEEK)
        self.assertEqual(sum(counts.values()), EXPECTED_WEEKLY_ROWS)

        feasibility = feature_feasibility(weekly)
        self.assertEqual(feasibility["evaluations"], EXPECTED_WEEKLY_ROWS - 4)
        self.assertEqual(feasibility["first_evaluable_week"], "2017-02-03")
        self.assertEqual(feasibility["first_effective_time"], "2017-02-08T00:00:00Z")
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))
        self.assertEqual(deterministic_zip(parts), deterministic_zip(parts))

    def test_parser_rejects_header_value_and_weekly_gap_drift(self) -> None:
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HEADER:2017"):
            parse_annual(_annual_fixture(2017).replace(b"T5YIE", b"T10YIE", 1), 2017)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:DECIMAL:2017"):
            parse_annual(_annual_fixture(2017).replace(b",1.0", b",not-a-number", 1), 2017)


if __name__ == "__main__":
    unittest.main()
