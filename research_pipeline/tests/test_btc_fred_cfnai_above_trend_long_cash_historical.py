from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_fred_cfnai_above_trend_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-fred-cfnai-above-trend-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_fred_cfnai_above_trend", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcFredCfnaiAboveTrendLongCashHistoricalTest(unittest.TestCase):
    def test_sealed_feature_inventory_matches_source_gate(self) -> None:
        rows = research.load_cfnai(research.CFNAI_SOURCE)
        targets, feature = research.targets_by_execution_time(rows)
        self.assertEqual(len(targets), 84)
        self.assertEqual(feature["above_trend_count"], 42)
        self.assertEqual(feature["other_count"], 42)
        self.assertEqual(feature["state_transition_count"], 39)
        design = [state for when, state in targets.items() if research.DESIGN[0] <= when < research.DESIGN[1]]
        validation = [state for when, state in targets.items() if research.VALIDATION[0] <= when < research.VALIDATION[1]]
        self.assertEqual((len(design), sum(design), len(design) - sum(design)), (48, 27, 21))
        self.assertEqual((len(validation), sum(validation), len(validation) - sum(validation)), (24, 6, 18))

    def test_manifest_is_hash_bound(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
