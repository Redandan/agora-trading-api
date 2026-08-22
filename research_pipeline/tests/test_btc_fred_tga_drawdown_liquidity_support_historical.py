from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_fred_tga_drawdown_liquidity_support_historical.py"
MANIFEST_PATH = REPO_ROOT / "research_pipeline" / "examples" / "btc-fred-tga-drawdown-liquidity-support-historical.v1.manifest.json"
ARTIFACT_DIR = REPO_ROOT / ".research-state" / "experiments" / "btc-fred-tga-drawdown-liquidity-support-historical-v1" / "artifacts"


def _load_runner():
    spec = importlib.util.spec_from_file_location("tga_frozen_runner", RUNNER_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FredTgaLiquidityPredictiveScreenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _load_runner()

    def test_frozen_manifest_and_source_hashes_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_fisher_direction_is_deterministic(self) -> None:
        self.assertEqual(
            self.runner.one_sided_fisher_less(0, 2, 2, 0),
            self.runner.D("0.1666666666666666666666666666666667"),
        )

    def test_sealed_runs_are_byte_identical_and_economics_remain_closed(self) -> None:
        run1 = ARTIFACT_DIR / "run1.json"
        run2 = ARTIFACT_DIR / "run2.json"
        raw1 = run1.read_bytes()
        raw2 = run2.read_bytes()
        self.assertEqual(raw1, raw2)
        self.assertEqual(
            hashlib.sha256(raw1).hexdigest(),
            "d15a87d4f9e1e7cb75a48e8c3bb1292a37e165e769583f8cbb58dfa50075b655",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_TGA_DRAWDOWN_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertIn(
            "design_supportive_median_terminal_return_at_least_25bp_higher",
            result["failed_pre_economic_gates"],
        )
        self.assertIn(
            "validation_supportive_median_path_drawdown_non_worse",
            result["failed_pre_economic_gates"],
        )


if __name__ == "__main__":
    unittest.main()
