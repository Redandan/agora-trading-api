from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/coinmetrics_btc_rctc_market_top_source_probe.py"
SPEC = importlib.util.spec_from_file_location("coinmetrics_btc_rctc_market_top_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class CoinMetricsBtcRctcMarketTopSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["expected_rows"], 2557)
        self.assertEqual(spec["feature_contract"]["threshold"], "10_EXACT_FROM_PRIMARY_SOURCE")

    def test_alternating_quarter_fixture_passes_support(self) -> None:
        rows = []
        current = date(2018, 1, 1)
        for index in range(2557):
            risk_off = (index // 91) % 2 == 0
            value = Decimal("10.5") if risk_off else Decimal("9.5")
            rows.append((current, value, str(value)))
            current += timedelta(days=1)
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_pagination_or_extra_envelope_field_is_rejected(self) -> None:
        raw = json.dumps({"data": [], "next_page_url": "https://example.invalid"}).encode("utf-8")
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:ENVELOPE_OR_PAGINATION"):
            probe.parse_rows(raw)


if __name__ == "__main__":
    unittest.main()
