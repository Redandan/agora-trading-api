from __future__ import annotations

from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_stablecoin_liquidity_growth_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline" / "examples" / "dra-stablecoin-liquidity-growth-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "btc-dra-stablecoin-liquidity-growth-entry-admission-manifest.v1.schema.json"
DECISION_PATH = ROOT / "research_pipeline" / "examples" / "dra-stablecoin-liquidity-growth-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run1.json"
RUN2_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run2.json"


class StablecoinLiquidityGrowthDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_exact_28_day_lag_two_day_delay_and_frozen_modes(self) -> None:
        first = date(2024, 1, 1)
        rows = {
            first + timedelta(days=index): (
                runner.D("100") + index,
                runner.D("200") - index,
            )
            for index in range(30)
        }
        with patch.object(runner, "SELECTION_CUTOFF", datetime(2025, 1, 1)):
            combined, exclusions = runner.build_factor_points(rows, "PRIMARY_COMBINED")
            usdt, _ = runner.build_factor_points(rows, "NEIGHBOR_USDT_ONLY")
            usdc, _ = runner.build_factor_points(rows, "NEIGHBOR_USDC_ONLY")
        self.assertEqual(2, len(combined))
        self.assertEqual("2024-01-29", combined[0]["report_date"])
        self.assertEqual("2024-01-01", combined[0]["prior_report_date"])
        self.assertEqual("2024-01-31T00:00:00", combined[0]["eligible_at"])
        self.assertEqual((0, 1, -1), (
            combined[0]["factor_sign"], usdt[0]["factor_sign"], usdc[0]["factor_sign"]
        ))
        self.assertEqual(28, exclusions["MISSING_PRIOR_28D_OBSERVATION"])

    def test_sealed_source_has_2287_rows_and_2257_pre_cutoff_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.load_stablecoin_rows(bindings)
        points, exclusions = runner.build_factor_points(rows, "PRIMARY_COMBINED")
        self.assertEqual((2287, 2257), (len(rows), len(points)))
        self.assertEqual(2, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(2287, evidence["rows"])
        self.assertTrue(any(point["factor_sign"] > 0 for point in points))
        self.assertTrue(any(point["factor_sign"] < 0 for point in points))

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual(
            "2d339679af0883b6a84e0cb28a8f3e0902469fc81f0206c89a16a656f44b2589",
            hashlib.sha256(run1).hexdigest(),
        )
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            "NO_CANDIDATE_CLOSE_STABLECOIN_LIQUIDITY_DRA_ADMISSION_FAMILY",
            result["status"],
        )
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual(
            "69.12835451",
            result["variants"]["PRIMARY_COMBINED"]["economic_evidence"]["validation"]["total_pnl_usdt"],
        )


if __name__ == "__main__":
    unittest.main()
