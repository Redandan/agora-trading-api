from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import unittest

from research.fred_usepuindxd_source_probe import (
    EXPECTED_DAILY_ROWS,
    EXPECTED_FIRST_DAY,
    EXPECTED_FIRST_WEEK,
    EXPECTED_LAST_DAY,
    EXPECTED_LAST_WEEK,
    EXPECTED_WEEKLY_ROWS,
    SourceReject,
    aggregate_weeks,
    feature_feasibility,
    parse_daily,
)


def _daily_fixture() -> bytes:
    lines = ["observation_date,USEPUINDXD"]
    day = EXPECTED_FIRST_DAY
    while day <= EXPECTED_LAST_DAY:
        value = Decimal("50") + Decimal(day.toordinal() % 200)
        lines.append(f"{day.isoformat()},{format(value, 'f')}")
        day += timedelta(days=1)
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredUsepuindxdSourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_daily_weekly_and_feature_contracts(self) -> None:
        daily = parse_daily(_daily_fixture())
        self.assertEqual(len(daily), EXPECTED_DAILY_ROWS)
        self.assertEqual(daily[0][0], EXPECTED_FIRST_DAY)
        self.assertEqual(daily[-1][0], EXPECTED_LAST_DAY)

        weekly = aggregate_weeks(daily)
        self.assertEqual(len(weekly), EXPECTED_WEEKLY_ROWS)
        self.assertEqual(weekly[0][0], EXPECTED_FIRST_WEEK)
        self.assertEqual(weekly[-1][0], EXPECTED_LAST_WEEK)

        feasibility = feature_feasibility(weekly)
        self.assertEqual(feasibility["evaluations"], EXPECTED_WEEKLY_ROWS - 52)
        self.assertEqual(feasibility["first_evaluable_week"], "2019-01-06")
        self.assertEqual(feasibility["first_effective_time"], "2019-01-13T00:00:00Z")
        self.assertEqual(set(feasibility["threshold_diagnostics"]), {"0.80", "1.00", "1.20"})
        for diagnostic in feasibility["threshold_diagnostics"].values():
            self.assertEqual(diagnostic["at_or_above"] + diagnostic["below"], feasibility["evaluations"])

    def test_missing_daily_row_is_rejected(self) -> None:
        lines = _daily_fixture().decode("utf-8").splitlines()
        raw = ("\n".join(line for line in lines if not line.startswith("2019-01-01,")) + "\n").encode("utf-8")
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:DAILY_ROWS"):
            parse_daily(raw)

    def test_nonpositive_value_is_rejected(self) -> None:
        lines = _daily_fixture().decode("utf-8").splitlines()
        lines[1] = "2018-01-01,0"
        raw = ("\n".join(lines) + "\n").encode("utf-8")
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:RANGE"):
            parse_daily(raw)


if __name__ == "__main__":
    unittest.main()
