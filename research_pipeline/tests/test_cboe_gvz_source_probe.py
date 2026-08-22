from __future__ import annotations

from datetime import timedelta
from decimal import Decimal
import unittest

from research.cboe_gvz_source_probe import (
    EXPECTED_FIRST_DAY,
    EXPECTED_LAST_DAY,
    SourceReject,
    aggregate_weeks,
    feature_feasibility,
    parse_daily,
)


def _daily_fixture() -> bytes:
    lines = ["DATE,OPEN,HIGH,LOW,CLOSE"]
    day = EXPECTED_FIRST_DAY
    while day <= EXPECTED_LAST_DAY:
        if day.weekday() < 5:
            close_value = Decimal("20") + Decimal(day.toordinal() % 17) / Decimal("10")
            lines.append(
                f"{day.strftime('%m/%d/%Y')},{format(close_value, 'f')},"
                f"{format(close_value + Decimal('2'), 'f')},"
                f"{format(close_value - Decimal('1'), 'f')},{format(close_value, 'f')}"
            )
        day += timedelta(days=1)
    return ("\n".join(lines) + "\n").encode("utf-8")


class CboeGvzSourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_daily_weekly_and_feature_contracts(self) -> None:
        daily = parse_daily(_daily_fixture())
        self.assertEqual(daily[0][0], EXPECTED_FIRST_DAY)
        self.assertEqual(daily[-1][0], EXPECTED_LAST_DAY)

        weekly = aggregate_weeks(daily)
        self.assertEqual(len(weekly), 366)
        feasibility = feature_feasibility(weekly)
        self.assertEqual(feasibility["evaluations"], 314)
        self.assertEqual(feasibility["first_evaluable_week_last_day"], "2019-01-04")
        self.assertEqual(feasibility["first_effective_time"], "2019-01-05T00:00:00Z")
        self.assertEqual(set(feasibility["threshold_diagnostics"]), {"0.80", "1.00", "1.20"})
        for diagnostic in feasibility["threshold_diagnostics"].values():
            self.assertEqual(diagnostic["at_or_below"] + diagnostic["above"], 314)

    def test_invalid_ohlc_is_rejected(self) -> None:
        lines = _daily_fixture().decode("utf-8").splitlines()
        lines[1] = "01/02/2018,20,19,18,20"
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HIGH"):
            parse_daily(("\n".join(lines) + "\n").encode("utf-8"))

    def test_missing_calendar_week_is_rejected(self) -> None:
        daily = parse_daily(_daily_fixture())
        shortened = [row for row in daily if not (row[0].isoformat() >= "2020-06-01" and row[0].isoformat() <= "2020-06-05")]
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:MISSING_WEEK"):
            aggregate_weeks(shortened)

    def test_wrong_header_is_rejected(self) -> None:
        raw = _daily_fixture().replace(b"DATE,OPEN,HIGH,LOW,CLOSE", b"DATE,CLOSE", 1)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HEADER"):
            parse_daily(raw)


if __name__ == "__main__":
    unittest.main()
