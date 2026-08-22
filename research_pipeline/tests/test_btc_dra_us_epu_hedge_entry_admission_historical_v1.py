from __future__ import annotations

from datetime import date, timedelta
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

import btc_dra_us_epu_hedge_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline/examples/dra-us-epu-hedge-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline/btc-dra-us-epu-hedge-entry-admission-manifest.v1.schema.json"
DECISION_PATH = ROOT / "research_pipeline/examples/dra-us-epu-hedge-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state/experiments" / runner.EXPERIMENT_ID / "artifacts/run1.json"
RUN2_PATH = ROOT / ".research-state/experiments" / runner.EXPERIMENT_ID / "artifacts/run2.json"


class UsEpuHedgeDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_prior_52_weeks_excludes_current_and_lags_seven_days(self) -> None:
        first = date(2023, 1, 2)
        rows = {
            first + timedelta(days=index): runner.D("100" if index < 52 * 7 else "120")
            for index in range(53 * 7)
        }
        with patch.object(runner, "FULL_WEEK_DAYS", 53 * 7), patch.object(runner, "EXPECTED_COMPLETE_WEEKS", 53):
            points, exclusions = runner.build_factor_points(rows, runner.PRIMARY_THRESHOLD)
        self.assertEqual(1, len(points))
        self.assertEqual("2024-01-07", points[0]["report_date"])
        self.assertEqual("2024-01-14T00:00:00", points[0]["eligible_at"])
        self.assertEqual("1.2", points[0]["factor_ratio"])
        self.assertEqual(("0.20", 1), (points[0]["factor_delta"], points[0]["factor_sign"]))
        self.assertEqual(52, exclusions["MISSING_PRIOR_52_COMPLETE_WEEKS"])

    def test_at_or_above_includes_exact_equality(self) -> None:
        first = date(2023, 1, 2)
        rows = {first + timedelta(days=index): runner.D("100") for index in range(53 * 7)}
        with patch.object(runner, "FULL_WEEK_DAYS", 53 * 7), patch.object(runner, "EXPECTED_COMPLETE_WEEKS", 53):
            points, _ = runner.build_factor_points(rows, runner.PRIMARY_THRESHOLD)
        self.assertEqual(("0.00", 1), (points[0]["factor_delta"], points[0]["factor_sign"]))

    def test_sealed_source_has_2557_rows_and_312_cutoff_safe_factor_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.load_epu(bindings)
        points, exclusions = runner.build_factor_points(rows, runner.PRIMARY_THRESHOLD)
        self.assertEqual((2557, 312), (len(rows), len(points)))
        self.assertEqual(52, exclusions["MISSING_PRIOR_52_COMPLETE_WEEKS"])
        self.assertEqual(2, exclusions["INCOMPLETE_TAIL_DAYS"])
        self.assertEqual(1, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(365, evidence["complete_week_count"])

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual("f1503643e747e3de48a71e7f7887bc9d70462001fef2a17b1d7e33605bbe04b2", hashlib.sha256(run1).hexdigest())
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_US_EPU_HEDGE_ENTRY_ADMISSION_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual("48.89993941", result["variant_evidence"]["1.00"]["economic_evidence"]["validation"]["total_pnl_usdt"])
        self.assertEqual("-37.30304245", decision["performance_evidence"]["primary_100_validation"]["total_pnl_delta_usdt"])


if __name__ == "__main__":
    unittest.main()
