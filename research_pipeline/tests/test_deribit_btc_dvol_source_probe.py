from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/deribit_btc_dvol_source_probe.py"
SPEC = importlib.util.spec_from_file_location("deribit_btc_dvol_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(probe)


class DeribitBtcDvolSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["maximum_pages"], 4)
        self.assertEqual(spec["feature_contract"]["comparison_lag_days"], 7)

    def test_alternating_week_fixture_passes_support(self) -> None:
        rows = []
        timestamp = probe.START_TIMESTAMP_MS
        while timestamp <= int(datetime(2024, 12, 31, tzinfo=timezone.utc).timestamp() * 1000):
            week = (timestamp - probe.START_TIMESTAMP_MS) // (7 * probe.DAY_MS)
            close = Decimal("60") if week % 2 else Decimal("50")
            rows.append((timestamp, close, close, close, close)); timestamp += probe.DAY_MS
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_rpc_error_or_wrong_result_is_rejected(self) -> None:
        raw = json.dumps({"jsonrpc": "2.0", "error": {"code": 1}, "result": {"data": [], "continuation": None}}).encode("utf-8")
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:RPC_ERROR"):
            probe.parse_page(raw, 0)


if __name__ == "__main__":
    unittest.main()
