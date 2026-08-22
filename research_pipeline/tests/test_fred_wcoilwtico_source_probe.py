from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_wcoilwtico_source_probe.py"
SPEC = importlib.util.spec_from_file_location("fred_wcoilwtico_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class FredWcoilwticoSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["expected_rows"], 365)
        self.assertEqual(spec["feature_contract"]["lookback_observations"], 4)
        self.assertEqual(
            spec["feature_contract"]["uptrend_condition"],
            "FOUR_WEEK_PRICE_CHANGE_STRICTLY_GREATER_THAN_ZERO",
        )

    def test_fixture_passes_lattice_and_produces_expected_evaluations(self) -> None:
        rows = []
        current = date(2018, 1, 5)
        for index in range(365):
            cycle = index % 16
            value = Decimal(60 + (cycle if cycle < 8 else 16 - cycle))
            rows.append((current, value, str(value)))
            current += timedelta(days=7)
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertEqual(feasibility["evaluations"], 361)
        self.assertEqual(feasibility["design"]["evaluations"], 209)
        self.assertEqual(feasibility["validation"]["evaluations"], 104)

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,WCOILWTICO\n2018-01-05,61.0\n")


if __name__ == "__main__":
    unittest.main()
