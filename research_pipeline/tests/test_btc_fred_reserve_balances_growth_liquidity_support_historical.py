from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

from research import btc_fred_reserve_balances_growth_liquidity_support_historical as runner


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-reserve-balances-growth-liquidity-support-historical.v1.manifest.json"
WRESBAL_PATH = REPO_ROOT / ".research-state/experiments/btc-fred-reserve-balances-growth-liquidity-support-historical-v1/inputs/fred-wresbal-2018-2024.normalized.csv"
ARTIFACT_DIR = REPO_ROOT / ".research-state/experiments/btc-fred-reserve-balances-growth-liquidity-support-historical-v1/artifacts"


class FredReserveBalancesGrowthPredictiveScreenTest(unittest.TestCase):
    def test_frozen_manifest_source_and_factor_inventory_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        runner.validate_manifest(manifest)
        rows = runner.load_wresbal(WRESBAL_PATH)
        points = runner.build_factor_points(rows)
        self.assertEqual(len(rows), 365)
        self.assertEqual(len(points), 361)
        self.assertEqual(sum(point.supportive for point in points), 187)
        self.assertEqual(sum(not point.supportive for point in points), 174)

    def test_sealed_runs_are_byte_identical_and_failed_pre_economic(self) -> None:
        raw1 = (ARTIFACT_DIR / "run1.json").read_bytes()
        raw2 = (ARTIFACT_DIR / "run2.json").read_bytes()
        self.assertEqual(raw1, raw2)
        self.assertEqual(
            hashlib.sha256(raw1).hexdigest(),
            "8125d8513354907c42d5a9e209cb697517ebb5a8d0fde2ca0daf02e54f789f8d",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_RESERVE_BALANCES_GROWTH_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual(len(result["failed_pre_economic_gates"]), 10)
        self.assertIn(
            "validation_supportive_median_terminal_return_at_least_25bp_higher",
            result["failed_pre_economic_gates"],
        )
        self.assertEqual(
            result["predictive_evidence"]["validation"]["statistics"][
                "annual_median_return_direction_wins"
            ],
            1,
        )


if __name__ == "__main__":
    unittest.main()
