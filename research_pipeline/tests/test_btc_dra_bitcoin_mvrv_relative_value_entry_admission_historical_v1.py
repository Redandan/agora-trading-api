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

import btc_dra_bitcoin_mvrv_relative_value_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline" / "examples" / "dra-bitcoin-mvrv-relative-value-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "btc-dra-bitcoin-mvrv-relative-value-entry-admission-manifest.v1.schema.json"
DECISION_PATH = ROOT / "research_pipeline" / "examples" / "dra-bitcoin-mvrv-relative-value-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run1.json"
RUN2_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run2.json"


class BitcoinMvrvRelativeValueDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_prior_365_days_sunday_only_and_three_day_lag(self) -> None:
        first = date(2024, 1, 1)
        rows = {first + timedelta(days=index): runner.D("2") for index in range(371)}
        rows[first + timedelta(days=370)] = runner.D("1")
        with patch.object(runner, "FULL_WEEK_DAYS", 371), patch.object(
            runner, "SELECTION_CUTOFF", datetime(2026, 1, 1)
        ):
            points, exclusions = runner.build_factor_points(rows)
        self.assertEqual(1, len(points))
        self.assertEqual("2025-01-05", points[0]["report_date"])
        self.assertEqual("2025-01-04", points[0]["prior_report_date"])
        self.assertEqual("2025-01-08T00:00:00", points[0]["eligible_at"])
        self.assertEqual(("1", 1), (points[0]["factor_delta"], points[0]["factor_sign"]))
        self.assertEqual(0, exclusions["INCOMPLETE_TAIL_DAYS"])

    def test_sealed_source_has_2557_rows_and_312_usable_factor_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.load_mvrv(bindings)
        points, exclusions = runner.build_factor_points(rows)
        self.assertEqual((2557, 312), (len(rows), len(points)))
        self.assertEqual(52, exclusions["MISSING_PRIOR_365D_WINDOW"])
        self.assertEqual(2, exclusions["INCOMPLETE_TAIL_DAYS"])
        self.assertEqual(1, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(365, evidence["complete_week_count"])

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual("0ce943e88a385695d050ebb23817b4cf6ec1e89ce5b9bcb4e7ea77651de72ee2", hashlib.sha256(run1).hexdigest())
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_BITCOIN_MVRV_RELATIVE_VALUE_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual("13.15285938", result["economic_evidence"]["validation"]["total_pnl_usdt"])
        self.assertEqual("-73.05012248", decision["performance_evidence"]["validation"]["total_pnl_delta_usdt"])


if __name__ == "__main__":
    unittest.main()
