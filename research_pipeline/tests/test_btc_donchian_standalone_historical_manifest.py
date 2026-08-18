from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator


REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = REPO_ROOT / "research_pipeline"
SCHEMA_PATH = PACKAGE_ROOT / "btc-donchian-standalone-historical-manifest.v1.schema.json"
MANIFEST_PATH = (
    PACKAGE_ROOT
    / "examples"
    / "btc-donchian-20d-10d-standalone-historical.v1.manifest.json"
)
DECISION_PATH = (
    PACKAGE_ROOT
    / "examples"
    / "btc-donchian-20d-10d-standalone-historical.v1.decision.json"
)


class BtcDonchianStandaloneHistoricalManifestTest(unittest.TestCase):
    def test_manifest_is_strict_and_preoutcome_sources_are_frozen(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(manifest)

        self.assertEqual(1, manifest["strategy_policy"]["variants"])
        self.assertEqual(2, manifest["determinism"]["reruns"])
        self.assertEqual(
            "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
            manifest["gate_set"]["decision"],
        )
        self.assertIn("OOS_OPEN", manifest["prohibited_actions"])

        for binding in manifest["source_bindings"]:
            path = REPO_ROOT / binding["path"]
            if path.is_file():
                self.assertEqual(
                    binding["sha256"],
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                )

        dataset = REPO_ROOT / manifest["dataset"]["path"]
        if dataset.is_file():
            self.assertEqual(
                manifest["dataset"]["sha256"],
                hashlib.sha256(dataset.read_bytes()).hexdigest(),
            )
            with dataset.open(encoding="utf-8") as handle:
                self.assertEqual(
                    manifest["dataset"]["rows"],
                    sum(1 for _ in handle),
                )

    def test_decision_binds_byte_identical_runs_and_denies_oos(self) -> None:
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            "NO_CANDIDATE_CLOSE_BTC_DONCHIAN_20D_10D_STANDALONE_FAMILY",
            decision["status"],
        )
        self.assertTrue(decision["deterministic_replication"]["byte_identical"])
        self.assertEqual(
            decision["artifact"]["sha256"],
            decision["deterministic_replication"]["sha256"],
        )
        self.assertFalse(decision["oos_opened"])
        self.assertTrue(decision["prohibited_reopen"])
        self.assertEqual(5, len(decision["failed_gates"]))

        runner = REPO_ROOT / decision["runner"]["path"]
        if runner.is_file():
            self.assertEqual(
                decision["runner"]["sha256"],
                hashlib.sha256(runner.read_bytes()).hexdigest(),
            )
        artifact = REPO_ROOT / decision["artifact"]["path"]
        replica = REPO_ROOT / decision["deterministic_replication"]["path"]
        if artifact.is_file() and replica.is_file():
            self.assertEqual(
                decision["artifact"]["sha256"],
                hashlib.sha256(artifact.read_bytes()).hexdigest(),
            )
            self.assertEqual(artifact.read_bytes(), replica.read_bytes())


if __name__ == "__main__":
    unittest.main()
