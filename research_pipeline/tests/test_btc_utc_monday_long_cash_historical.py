from __future__ import annotations

from datetime import datetime
import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_utc_monday_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-utc-monday-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_utc_monday_long_cash", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcUtcMondayLongCashHistoricalTest(unittest.TestCase):
    def test_calendar_feature_is_one_fixed_utc_monday_variant(self) -> None:
        targets, feature = research.calendar_targets()
        self.assertEqual(len(targets), 2192)
        self.assertEqual(feature["monday_target_count"], 313)
        self.assertEqual(feature["non_monday_target_count"], 1879)
        self.assertFalse(targets[datetime(2019, 1, 1)])
        self.assertTrue(targets[datetime(2019, 1, 7)])
        self.assertFalse(targets[datetime(2019, 1, 8)])

    def test_manifest_is_hash_bound_to_runner_sources_policy_and_gates(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        research.validate_manifest(manifest, RUNNER)
        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["strategy_policy"]["long_condition"], "UTC_WEEKDAY_IS_MONDAY")
        self.assertEqual(manifest["oos_access"], "DENY")


if __name__ == "__main__":
    unittest.main()
