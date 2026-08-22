from __future__ import annotations

from datetime import date
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_unrate_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_unrate_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class FredUnrateSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["expected_rows"], 96)
        self.assertEqual(spec["feature_contract"]["lookback_observations"], 12)
        self.assertEqual(
            spec["feature_contract"]["nondeterioration_condition"],
            "CURRENT_MONTH_UNRATE_LESS_THAN_OR_EQUAL_TO_UNRATE_12_MONTHS_EARLIER",
        )

    def test_fixture_passes_lattice_and_both_window_support_gates(self) -> None:
        rows = []
        current = date(2017, 1, 1)
        for index in range(96):
            value = Decimal(5 + ((index // 12) % 2))
            rows.append((current, value, str(value)))
            current = probe.add_months(current, 1)
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertEqual(feasibility["evaluations"], 84)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,UNRATE\n2017-01-01,4.7\n")

    def test_zero_or_more_than_one_hundred_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:IDENTITY_OR_RANGE"):
            probe.parse_rows(b"observation_date,UNRATE\n2017-01-01,0\n")
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:IDENTITY_OR_RANGE"):
            probe.parse_rows(b"observation_date,UNRATE\n2017-01-01,100.1\n")


if __name__ == "__main__":
    unittest.main()
