from __future__ import annotations

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

import btc_dra_declarative_entry_admission_v1 as runner


MANIFEST_PATH = (
    ROOT
    / "research_pipeline"
    / "examples"
    / "dra-h1-absolute-return-volume-coupling-entry-admission.v1.manifest.json"
)
SCHEMA_PATH = (
    ROOT
    / "research_pipeline"
    / "dra-declarative-entry-admission-manifest.v1.schema.json"
)
DECISION_PATH = (
    ROOT
    / "research_pipeline"
    / "examples"
    / "dra-h1-absolute-return-volume-coupling-entry-admission.v1.decision.json"
)
ARTIFACTS = (
    ROOT
    / ".research-state"
    / "experiments"
    / "dra-h1-absolute-return-volume-coupling-entry-admission-v1"
    / "artifacts"
)


class H1AbsoluteReturnVolumeCouplingDraScreenTest(unittest.TestCase):
    def test_manifest_is_schema_valid_and_binds_frozen_prior(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.verify_prior_evidence(manifest)
        self.assertEqual(
            "f77ea8fe2c09b3dc2258967e840286b80ad101d547d6d4fc1a7e73e01b545371",
            verified["sha256"],
        )

    def test_sealed_runs_are_byte_identical_and_decision_closes_family(self) -> None:
        run1 = (ARTIFACTS / "run1.json").read_bytes()
        run2 = (ARTIFACTS / "run2.json").read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual(
            "16ab465b0d205b99d5efafc63277dc13bf5e7a8460f77ea974e197ffb187d945",
            hashlib.sha256(run1).hexdigest(),
        )
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_MECHANISM_CLOSE_FEATURE_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        primary = next(
            variant for variant in result["variants"] if variant["role"] == "primary"
        )
        self.assertEqual("72.07094643", primary["validation"]["total_pnl_usdt"])
        self.assertEqual(0, primary["annual_total_wins"])


if __name__ == "__main__":
    unittest.main()
