from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_monthly_constant_mix_v1_historical.py"
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-constant-mix-v1-historical.v1.manifest.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location("tested_btc_monthly_constant_mix", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcMonthlyConstantMixHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_manifest_freezes_primary_and_rejection_only_neighbors(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        policy = manifest["strategy_policy"]
        self.assertEqual(policy["primary_target_weight"], "0.50")
        self.assertEqual(policy["neighbor_target_weights"], ["0.40", "0.60"])
        self.assertEqual(policy["neighbor_use"], "STABILITY_REJECTION_ONLY_NO_SELECTION")
        self.assertEqual(policy["variants"], 3)

    def test_frozen_non_outcome_source_bindings_match(self) -> None:
        self.assertEqual(self.runner.sha256(self.runner.ENGINE_SOURCE), self.runner.EXPECTED_ENGINE_SHA256)
        self.assertEqual(self.runner.sha256(self.runner.BASE_SOURCE), self.runner.EXPECTED_BASE_SHA256)
        self.assertEqual(self.runner.sha256(self.runner.PARSER_SOURCE), self.runner.EXPECTED_PARSER_SHA256)
        self.assertEqual(self.runner.sha256(self.runner.PRIOR_SOURCE), self.runner.EXPECTED_PRIOR_SHA256)
        self.assertEqual(
            self.runner.sha256(self.runner.HYPOTHESIS_SOURCE),
            self.runner.EXPECTED_HYPOTHESIS_SHA256,
        )


if __name__ == "__main__":
    unittest.main()
