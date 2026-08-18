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


if __name__ == "__main__":
    unittest.main()
