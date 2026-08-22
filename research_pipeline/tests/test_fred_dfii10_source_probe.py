from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.fred_dfii10_source_probe import (
    EXPECTED_FIRST_WEEK,
    EXPECTED_LAST_WEEK,
    EXPECTED_WEEKLY_ROWS,
    SourceReject,
    aggregate_weeks,
    deterministic_zip,
    feature_feasibility,
    parse_annual,
)


def _annual_fixture(year: int) -> bytes:
    day = date(year, 1, 1)
    weekdays: list[date] = []
    while day.year == year:
        if day.weekday() <= 4:
            weekdays.append(day)
        day += timedelta(days=1)
    omitted = set([day for day in weekdays if day.weekday() == 0][:8])
    lines = ["observation_date,DFII10"]
    for observation_date in weekdays:
        if observation_date in omitted:
            value = ""
        else:
            value = format(Decimal(observation_date.toordinal() % 200) / Decimal("100"), "f")
        lines.append(f"{observation_date.isoformat()},{value}")
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredDfii10SourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_annual_weekly_and_feature_contracts(self) -> None:
        daily: list[tuple[date, Decimal]] = []
        parts: list[tuple[int, bytes]] = []
        for year in range(2018, 2025):
            raw = _annual_fixture(year)
            parsed, empty_dates, source_rows = parse_annual(raw, year)
            self.assertGreaterEqual(len(parsed), 240)
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
        self.assertEqual(feasibility["first_evaluable_week"], "2018-02-02")
        self.assertEqual(feasibility["first_effective_time"], "2018-02-07T00:00:00Z")

        self.assertEqual(deterministic_zip(parts), deterministic_zip(parts))

    def test_nonempty_missing_marker_is_rejected(self) -> None:
        raw = _annual_fixture(2018).replace(b",\n", b",.\n", 1)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:DECIMAL:2018"):
            parse_annual(raw, 2018)


if __name__ == "__main__":
    unittest.main()
