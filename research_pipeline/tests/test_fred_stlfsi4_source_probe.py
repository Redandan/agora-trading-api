from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_stlfsi4_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_stlfsi4_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class FredStlfsi4SourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["expected_rows"], 365)
        self.assertEqual(spec["feature_contract"]["threshold"], "0")

    def test_alternating_weekly_fixture_passes_support(self) -> None:
        rows = []
        start = date(2018, 1, 5)
        for index in range(365):
            value = Decimal("-0.5") if index % 2 == 0 else Decimal("0.5")
            rows.append((start + timedelta(days=7 * index), value, str(value)))
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,STLFSI4\n2018-01-05,-1.0\n")


if __name__ == "__main__":
    unittest.main()
