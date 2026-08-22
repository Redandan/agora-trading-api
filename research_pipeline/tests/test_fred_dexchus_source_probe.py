from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_dexchus_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_dexchus_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def weekday_rows() -> list[tuple[date, Decimal, str]]:
    rows = []
    current = probe.CALENDAR_START
    while current <= probe.CALENDAR_END:
        if current.weekday() < 5:
            week = (current - probe.CALENDAR_START).days // 7
            cycle = week % 16
            value = Decimal("6") + Decimal(cycle if cycle < 8 else 16 - cycle) / Decimal("100")
            rows.append((current, value, str(value)))
        current += timedelta(days=1)
    return rows


class FredDexchusSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["minimum_unique_weekday_rows"], 1820)
        self.assertEqual(spec["source_contract"]["missing_marker_policy"], "EMPTY_OR_DOT_IS_MISSING_AND_EXCLUDED_FROM_WEEKLY_MEAN")
        self.assertEqual(spec["feature_contract"]["lookback_complete_weeks"], 4)

    def test_one_omitted_holiday_still_produces_exact_week_inventory(self) -> None:
        rows = weekday_rows()
        rows.pop(0)
        probe.validate_rows(rows)
        weeks = probe.aggregate_complete_weeks(rows)
        feasibility = probe.feature_feasibility(weeks)
        self.assertEqual(len(weeks), 365)
        self.assertEqual(feasibility["evaluations"], 361)
        self.assertEqual(feasibility["design"]["evaluations"], 209)
        self.assertEqual(feasibility["validation"]["evaluations"], 104)

    def test_week_with_fewer_than_three_valid_values_is_rejected(self) -> None:
        rows = weekday_rows()
        rows = [row for row in rows if row[0] not in {date(2018, 1, 1), date(2018, 1, 2), date(2018, 1, 3)}]
        probe.validate_rows(rows)
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:WEEK_SUPPORT"):
            probe.aggregate_complete_weeks(rows)

    def test_blank_and_dot_are_missing_markers(self) -> None:
        rows = probe.parse_rows(b"observation_date,DEXCHUS\n2018-01-01,\n2018-01-02,.\n")
        self.assertIsNone(rows[0][1])
        self.assertIsNone(rows[1][1])

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,DEXCHUS\n2018-01-01,6.5\n")


if __name__ == "__main__":
    unittest.main()
