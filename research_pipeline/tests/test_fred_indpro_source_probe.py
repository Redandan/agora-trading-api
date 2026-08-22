from __future__ import annotations

from datetime import date
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_indpro_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_indpro_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class FredIndproSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["expected_rows"], 96)
        self.assertEqual(spec["feature_contract"]["lookback_observations"], 12)

    def test_fixture_passes_support(self) -> None:
        rows = []
        current = date(2017, 1, 1)
        for index in range(96):
            cycle = (index // 6) % 2
            value = Decimal(100 + index + cycle * 20)
            rows.append((current, value, str(value)))
            current = probe.add_months(current, 1)
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertEqual(feasibility["evaluations"], 84)

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,INDPRO\n2017-01-01,100\n")


if __name__ == "__main__":
    unittest.main()
