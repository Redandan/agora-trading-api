from __future__ import annotations

from datetime import date
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_pcoppusdm_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_pcoppusdm_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def monthly_rows() -> list[tuple[date, Decimal, str]]:
    pattern = [1000, 1100, 1200, 1300, 1200, 1100, 1000, 900, 800, 700, 800, 900]
    rows = []
    current = probe.EXPECTED_FIRST
    for index in range(probe.EXPECTED_ROWS):
        value = Decimal(pattern[index % len(pattern)])
        rows.append((current, value, str(value)))
        current = probe.add_months(current, 1)
    return rows


class FredPcoppusdmSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(
            spec["source_contract"]["expected_unique_ordered_month_rows"], 84
        )
        self.assertEqual(spec["factor_contract"]["warmup_months"], 3)
        self.assertEqual(
            spec["factor_contract"]["effective_time"],
            "OBSERVATION_MONTH_START_PLUS_75_CALENDAR_DAYS_AT_0000_UTC",
        )

    def test_fixture_passes_lattice_and_has_expected_windows(self) -> None:
        rows = monthly_rows()
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertEqual(feasibility["evaluations"], 81)
        self.assertEqual(feasibility["design"]["evaluations"], 48)
        self.assertEqual(feasibility["validation"]["evaluations"], 24)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_missing_month_is_rejected(self) -> None:
        rows = monthly_rows()
        rows.pop(10)
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:ROWS"):
            probe.validate_rows(rows)

    def test_missing_value_and_wrong_header_are_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:MISSING_OR_ROW"):
            probe.parse_rows(b"observation_date,PCOPPUSDM\n2018-01-01,.\n")
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,PCOPPUSDM\n2018-01-01,7000\n")


if __name__ == "__main__":
    unittest.main()
