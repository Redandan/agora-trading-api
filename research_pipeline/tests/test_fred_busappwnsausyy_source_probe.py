from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.fred_busappwnsausyy_source_probe import (
    EXPECTED_FIRST_WEEK,
    EXPECTED_LAST_WEEK,
    EXPECTED_WEEKLY_ROWS,
    SourceReject,
    deterministic_zip,
    feature_feasibility,
    parse_annual,
    validate_weekly_lattice,
)


BASE_WEEK = date(2017, 1, 7)


def _annual_fixture(year: int) -> bytes:
    week_end = date(year, 1, 1)
    while week_end.weekday() != 5:
        week_end += timedelta(days=1)
    lines = ["observation_date,BUSAPPWNSAUSYY"]
    while week_end.year == year:
        week_index = (week_end - BASE_WEEK).days // 7
        phase = week_index % 16
        value = Decimal(phase - 8) / Decimal("2")
        lines.append(f"{week_end.isoformat()},{format(value, 'f')}")
        week_end += timedelta(days=7)
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredBusappwnsausyySourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_lattice_support_and_concentration_gates(self) -> None:
        rows = []
        parts = []
        for year in range(2017, 2025):
            raw = _annual_fixture(year)
            parsed = parse_annual(raw, year)
            self.assertIn(len(parsed), {52, 53})
            rows.extend(parsed)
            parts.append((year, raw))

        weekly = validate_weekly_lattice(rows)
        self.assertEqual(len(weekly), EXPECTED_WEEKLY_ROWS)
        self.assertEqual(weekly[0][0], EXPECTED_FIRST_WEEK)
        self.assertEqual(weekly[-1][0], EXPECTED_LAST_WEEK)

        feasibility = feature_feasibility(weekly)
        self.assertEqual(feasibility["evaluations"], EXPECTED_WEEKLY_ROWS)
        self.assertEqual(feasibility["first_evaluable_week"], "2017-01-07")
        self.assertEqual(feasibility["first_effective_time"], "2017-02-21T00:00:00Z")
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))
        self.assertEqual(deterministic_zip(parts), deterministic_zip(parts))

    def test_parser_rejects_header_value_and_weekly_gap_drift(self) -> None:
        raw = _annual_fixture(2017)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HEADER:2017"):
            parse_annual(raw.replace(b"BUSAPPWNSAUSYY", b"BUSAPPWNSAUS", 1), 2017)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:DECIMAL:2017"):
            parse_annual(raw.replace(b",-4", b",not-a-number", 1), 2017)

        rows = []
        for year in range(2017, 2025):
            rows.extend(parse_annual(_annual_fixture(year), year))
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:WEEKLY_ROWS:416"):
            validate_weekly_lattice(rows[1:])


if __name__ == "__main__":
    unittest.main()
