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

import btc_dra_onchain_activity_breadth_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline" / "examples" / "dra-onchain-activity-breadth-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "btc-dra-onchain-activity-breadth-entry-admission-manifest.v1.schema.json"
DECISION_PATH = ROOT / "research_pipeline" / "examples" / "dra-onchain-activity-breadth-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run1.json"
RUN2_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run2.json"


class OnchainActivityBreadthDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_exact_28_day_means_364_day_lag_and_frozen_modes(self) -> None:
        first = date(2023, 1, 1)
        rows = {
            first + timedelta(days=index): (
                runner.D("100") if index < 28 else runner.D("200"),
                runner.D("200") if index < 28 else runner.D("100"),
            )
            for index in range(393)
        }
        with patch.object(runner, "SELECTION_CUTOFF", datetime(2026, 1, 1)):
            both, exclusions = runner.build_factor_points(rows, "PRIMARY_BOTH")
            tx, _ = runner.build_factor_points(rows, "NEIGHBOR_TXCNT_ONLY")
            address, _ = runner.build_factor_points(rows, "NEIGHBOR_ADRACTCNT_ONLY")
        report = first + timedelta(days=391)
        self.assertEqual(2, len(both))
        self.assertEqual(report.isoformat(), both[0]["report_date"])
        self.assertEqual((report + timedelta(days=2)).isoformat() + "T00:00:00", both[0]["eligible_at"])
        self.assertEqual((-1, 1, -1), (
            both[0]["factor_sign"], tx[0]["factor_sign"], address[0]["factor_sign"]
        ))
        self.assertEqual(391, exclusions["MISSING_CURRENT_AND_LAGGED_28D_WINDOWS"])

    def test_sealed_source_has_2557_rows_and_2164_pre_cutoff_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.load_activity_rows(bindings)
        points, exclusions = runner.build_factor_points(rows, "PRIMARY_BOTH")
        self.assertEqual((2557, 2164), (len(rows), len(points)))
        self.assertEqual(2, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(2557, evidence["rows"])
        self.assertTrue(any(point["factor_sign"] > 0 for point in points))
        self.assertTrue(any(point["factor_sign"] < 0 for point in points))

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual(
            "1cfe32ff626f1736abba4264afede8b58109a786cf71b7191b16c9d201fd9e8a",
            hashlib.sha256(run1).hexdigest(),
        )
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            "NO_CANDIDATE_CLOSE_ONCHAIN_ACTIVITY_BREADTH_DRA_ADMISSION_FAMILY",
            result["status"],
        )
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual(
            "38.55468080",
            result["variants"]["PRIMARY_BOTH"]["economic_evidence"]["validation"]["total_pnl_usdt"],
        )


if __name__ == "__main__":
    unittest.main()
