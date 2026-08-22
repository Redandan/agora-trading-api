from __future__ import annotations

from datetime import datetime
import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_halloween_november_april_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-halloween-november-april-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_halloween_november_april", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcHalloweenNovemberAprilLongCashHistoricalTest(unittest.TestCase):
    def test_calendar_feature_is_single_fixed_balanced_variant(self) -> None:
        targets, feature = research.calendar_targets()
        self.assertEqual(len(targets), 72)
        self.assertEqual(feature["long_target_count"], 36)
        self.assertEqual(feature["cash_target_count"], 36)
        self.assertEqual(feature["state_transition_count"], 12)
        self.assertTrue(targets[datetime(2019, 1, 1)])
        self.assertFalse(targets[datetime(2019, 5, 1)])
        self.assertTrue(targets[datetime(2019, 11, 1)])

    def test_manifest_is_hash_bound_to_runner_and_sources(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
