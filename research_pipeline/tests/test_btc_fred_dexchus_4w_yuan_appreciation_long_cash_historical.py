from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_fred_dexchus_4w_yuan_appreciation_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-fred-dexchus-4w-yuan-appreciation-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_fred_dexchus_4w_yuan_appreciation", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcFredDexchus4wYuanAppreciationLongCashHistoricalTest(unittest.TestCase):
    def test_sealed_feature_inventory_matches_source_gate(self) -> None:
        rows = research.load_dexchus(research.DEXCHUS_SOURCE)
        targets, feature = research.targets_by_execution_time(rows)
        self.assertEqual(len(targets), 361)
        self.assertEqual(feature["yuan_appreciation_count"], 175)
        self.assertEqual(feature["other_count"], 186)
        self.assertEqual(feature["state_transition_count"], 59)

    def test_manifest_is_hash_bound(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
