from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_fred_wcoilwtico_4w_uptrend_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-fred-wcoilwtico-4w-uptrend-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_fred_wcoilwtico_4w_uptrend", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcFredWcoilwtico4wUptrendLongCashHistoricalTest(unittest.TestCase):
    def test_sealed_wti_feature_inventory_matches_source_gate(self) -> None:
        rows = research.load_wti(research.WTI_SOURCE)
        targets, feature = research.targets_by_execution_time(rows)
        self.assertEqual(len(targets), 361)
        self.assertEqual(feature["uptrend_count"], 199)
        self.assertEqual(feature["nonuptrend_count"], 162)
        self.assertEqual(feature["state_transition_count"], 52)

    def test_manifest_is_hash_bound(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
