from __future__ import annotations

from datetime import date
import hashlib
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_broad_dollar_weakness_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline" / "examples" / "dra-broad-dollar-weakness-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "btc-dra-broad-dollar-weakness-entry-admission-manifest.v1.schema.json"
DECISION_PATH = ROOT / "research_pipeline" / "examples" / "dra-broad-dollar-weakness-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run1.json"
RUN2_PATH = ROOT / ".research-state" / "experiments" / runner.EXPERIMENT_ID / "artifacts" / "run2.json"


class BroadDollarWeaknessDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_factor_uses_exact_28_day_predecessor_and_five_day_lag(self) -> None:
        rows = {
            date(2024, 1, 5): runner.D("100"),
            date(2024, 1, 12): runner.D("101"),
            date(2024, 2, 2): runner.D("98"),
            date(2024, 2, 9): runner.D("103"),
        }
        points, exclusions = runner.build_factor_points(rows)
        self.assertEqual(["2024-02-02", "2024-02-09"], [point["report_date"] for point in points])
        self.assertEqual("2024-02-07T00:00:00", points[0]["eligible_at"])
        self.assertEqual(("2", 1), (points[0]["factor_delta"], points[0]["factor_sign"]))
        self.assertEqual(("-2", -1), (points[1]["factor_delta"], points[1]["factor_sign"]))
        self.assertEqual(2, exclusions["MISSING_EXACT_28D_PREDECESSOR"])

    def test_sealed_source_has_365_rows_and_360_usable_factor_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        rows, evidence = runner.load_weekly_dollar(bindings)
        points, exclusions = runner.build_factor_points(rows)
        self.assertEqual((365, 360), (len(rows), len(points)))
        self.assertEqual(4, exclusions["MISSING_EXACT_28D_PREDECESSOR"])
        self.assertEqual(1, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual({"3": 5, "4": 72, "5": 288}, evidence["weekly_observation_counts"])
        self.assertEqual("ORIGINAL_H10_RELEASE_VALUES_AND_REVISION_VINTAGES_MISSING_PROOF", evidence["present_vintage_revision_boundary"])

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual("d2fc9bc56b70221f780f922f3b503976a43de7c1fcf2bf7b9a05b2753371e05e", hashlib.sha256(run1).hexdigest())
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_BROAD_DOLLAR_WEAKNESS_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual("55.74492565", result["economic_evidence"]["validation"]["total_pnl_usdt"])


if __name__ == "__main__":
    unittest.main()
