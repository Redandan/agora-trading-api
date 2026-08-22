from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_dexjpus_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_dexjpus_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class FredDexjpusSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["minimum_source_rows"], 1820)
        self.assertEqual(spec["feature_contract"]["lookback_complete_weeks"], 4)
        self.assertEqual(
            spec["feature_contract"]["yen_depreciation_condition"],
            "FOUR_WEEK_WEEKLY_MEAN_CHANGE_STRICTLY_GREATER_THAN_ZERO",
        )

    def test_fixture_passes_lattice_and_produces_expected_evaluations(self) -> None:
        rows = []
        for index, day in enumerate(probe.expected_weekdays()):
            week = (day - date(2018, 1, 1)).days // 7
            cycle = week % 16
            value = Decimal(100 + (cycle if cycle < 8 else 16 - cycle))
            rows.append((day, value, str(value)))
        probe.validate_rows(rows)
        weeks = probe.aggregate_complete_weeks(rows)
        feasibility = probe.feature_feasibility(weeks)
        self.assertEqual(len(weeks), 365)
        self.assertEqual(feasibility["evaluations"], 361)
        self.assertEqual(feasibility["design"]["evaluations"], 209)
        self.assertEqual(feasibility["validation"]["evaluations"], 104)

    def test_missing_weekday_breaks_lattice(self) -> None:
        rows = [(day, Decimal("100"), "100") for day in probe.expected_weekdays()]
        rows.pop(100)
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:ROWS"):
            probe.validate_rows(rows)

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,DEXJPUS\n2018-01-01,112.0\n")

    def test_fred_blank_and_dot_are_equivalent_missing_value_markers(self) -> None:
        rows = probe.parse_rows(
            b"observation_date,DEXJPUS\n2018-01-01,\n2018-01-02,.\n"
        )
        self.assertIsNone(rows[0][1])
        self.assertIsNone(rows[1][1])


if __name__ == "__main__":
    unittest.main()
