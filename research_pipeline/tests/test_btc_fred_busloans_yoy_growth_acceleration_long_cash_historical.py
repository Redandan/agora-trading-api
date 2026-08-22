from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_fred_busloans_yoy_growth_acceleration_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-fred-busloans-yoy-growth-acceleration-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_fred_busloans_yoy_growth_acceleration", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcFredBusloansYoyGrowthAccelerationLongCashHistoricalTest(unittest.TestCase):
    def test_sealed_busloans_feature_inventory_matches_source_gate(self) -> None:
        rows = research.load_busloans(research.BUSLOANS_SOURCE)
        targets, feature = research.targets_by_execution_time(rows)
        self.assertEqual(len(targets), 83)
        self.assertEqual(feature["accelerating_count"], 40)
        self.assertEqual(feature["nonaccelerating_count"], 43)
        self.assertEqual(feature["state_transition_count"], 21)

    def test_manifest_is_hash_bound(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
