from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

from research import btc_coinmetrics_native_issuance_contraction_supply_pressure_historical as runner


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-native-issuance-contraction-supply-pressure-historical.v1.manifest.json"
ISSUANCE_PATH = REPO_ROOT / ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/inputs/coinmetrics-btc-native-issuance-2017-2024.normalized.csv"
ARTIFACT_DIR = REPO_ROOT / ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/artifacts"


class CoinMetricsNativeIssuancePredictiveScreenTest(unittest.TestCase):
    def test_frozen_manifest_source_and_factor_inventory_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        runner.validate_manifest(manifest)
        rows = runner.load_issuance(ISSUANCE_PATH)
        points = runner.build_factor_points(rows)
        self.assertEqual(len(rows), 2922)
        self.assertEqual(len(points), 362)
        self.assertEqual(sum(point.supportive for point in points), 228)
        self.assertEqual(sum(not point.supportive for point in points), 134)

    def test_sealed_runs_are_byte_identical_and_failed_pre_economic(self) -> None:
        raw1 = (ARTIFACT_DIR / "run1.json").read_bytes()
        raw2 = (ARTIFACT_DIR / "run2.json").read_bytes()
        self.assertEqual(raw1, raw2)
        self.assertEqual(
            hashlib.sha256(raw1).hexdigest(),
            "ea200ff6e46c5f11c0efc28657fb55528dd6b2037af176a3b32e86bee4d5f918",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_NATIVE_ISSUANCE_CONTRACTION_SUPPLY_PRESSURE_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual(len(result["failed_pre_economic_gates"]), 8)
        self.assertIn(
            "validation_supportive_median_path_drawdown_non_worse",
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
