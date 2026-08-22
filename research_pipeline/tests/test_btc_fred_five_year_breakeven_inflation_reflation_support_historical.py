from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

from research import btc_fred_five_year_breakeven_inflation_reflation_support_historical as runner


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-five-year-breakeven-inflation-reflation-support-historical.v1.manifest.json"
T5YIE_PATH = REPO_ROOT / ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/inputs/fred-t5yie-2017-2024.normalized.csv"
ARTIFACT_DIR = REPO_ROOT / ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/artifacts"


class FredFiveYearBreakevenInflationPredictiveScreenTest(unittest.TestCase):
    def test_frozen_manifest_source_and_factor_inventory_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        runner.validate_manifest(manifest)
        rows = runner.load_t5yie(T5YIE_PATH)
        points = runner.build_factor_points(rows)
        self.assertEqual(len(rows), 417)
        self.assertEqual(len(points), 413)
        self.assertEqual(sum(point.supportive for point in points), 209)
        self.assertEqual(sum(not point.supportive for point in points), 204)

    def test_sealed_runs_are_byte_identical_and_failed_pre_economic(self) -> None:
        raw1 = (ARTIFACT_DIR / "run1.json").read_bytes()
        raw2 = (ARTIFACT_DIR / "run2.json").read_bytes()
        self.assertEqual(raw1, raw2)
        self.assertEqual(
            hashlib.sha256(raw1).hexdigest(),
            "d87db25e09edd787c4ae2f869ba75ed3eec4bcf3008675b8dcde524381d0c452",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_FIVE_YEAR_BREAKEVEN_INFLATION_REFLATION_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual(len(result["failed_pre_economic_gates"]), 10)
        validation = result["predictive_evidence"]["validation"]["statistics"]
        self.assertEqual(validation["supportive_median_terminal_return_168h"], "0.00520634")
        self.assertEqual(validation["other_median_terminal_return_168h"], "0.01248801")
        self.assertEqual(validation["annual_median_return_direction_wins"], 1)
        self.assertEqual(
            validation["top_positive_annual_median_return_delta_contribution_pct"],
            "100.00000000",
        )


if __name__ == "__main__":
    unittest.main()
