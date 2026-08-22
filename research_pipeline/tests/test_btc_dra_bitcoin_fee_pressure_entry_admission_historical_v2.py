from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
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

import btc_dra_bitcoin_fee_pressure_entry_admission_historical_v2 as runner


MANIFEST_PATH = ROOT / "research_pipeline" / "examples" / "dra-bitcoin-fee-pressure-entry-admission-historical.v2.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "btc-dra-bitcoin-fee-pressure-entry-admission-manifest.v2.schema.json"
DECISION_PATH = ROOT / "research_pipeline" / "examples" / "dra-bitcoin-fee-pressure-entry-admission-historical.v2.decision.json"
RUN1_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run1.json"
RUN2_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run2.json"
INVALID_V1_RUN_PATH = ROOT / ".research-state" / "experiments" / runner.frozen.EXPERIMENT_ID / "artifacts" / "run1.json"


class BitcoinFeePressureDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_adjacent_nonoverlapping_28_day_means_and_three_day_lag(self) -> None:
        first = date(2024, 1, 1)
        rows = {
            first + timedelta(days=index): Decimal("1" if index < 28 else "2")
            for index in range(56)
        }
        with patch.object(runner.frozen, "FULL_WEEK_DAYS", 56):
            points, exclusions = runner.frozen.build_factor_points(rows)
        self.assertEqual(1, len(points))
        self.assertEqual("2024-02-25", points[0]["report_date"])
        self.assertEqual("2024-01-28", points[0]["prior_report_date"])
        self.assertEqual("2024-02-28T00:00:00", points[0]["eligible_at"])
        self.assertEqual(("1", 1), (points[0]["factor_delta"], points[0]["factor_sign"]))
        self.assertEqual(0, exclusions["INCOMPLETE_TAIL_DAYS"])

    def test_sealed_source_has_2557_rows_and_357_usable_factor_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.frozen.load_fee_pressure(bindings)
        points, exclusions = runner.frozen.build_factor_points(rows)
        self.assertEqual((2557, 357), (len(rows), len(points)))
        self.assertEqual(7, exclusions["MISSING_TWO_COMPLETE_28D_WINDOWS"])
        self.assertEqual(2, exclusions["INCOMPLETE_TAIL_DAYS"])
        self.assertEqual(1, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(365, evidence["complete_week_count"])
        self.assertEqual("ORIGINAL_DAILY_REVIEW_TIMESTAMPS_AND_VINTAGES_MISSING_PROOF", evidence["present_vintage_revision_boundary"])

    def test_invalid_v1_run_is_retained_but_excluded_by_identity(self) -> None:
        invalid = json.loads(INVALID_V1_RUN_PATH.read_text(encoding="utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_BITCOIN_HASHRATE_GROWTH_FAMILY", invalid["status"])
        self.assertEqual(
            "INVALID_IDENTITY_RETAINED_NOT_EVIDENCE",
            decision["sealed_evidence"]["invalid_v1_run"]["disposition"],
        )

    def test_sealed_v2_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual("d3c588d01da4cdf1ad1cbbe9b70a24d77c771e9c5a443332c38727695233e056", hashlib.sha256(run1).hexdigest())
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_BITCOIN_FEE_PRESSURE_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual("42.66744615", result["economic_evidence"]["validation"]["total_pnl_usdt"])
        self.assertEqual("-43.53553571", decision["performance_evidence"]["validation"]["total_pnl_delta_usdt"])


if __name__ == "__main__":
    unittest.main()
