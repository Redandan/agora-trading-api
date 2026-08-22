from __future__ import annotations

import hashlib
import importlib.util
import json
from decimal import localcontext
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT
    / "research"
    / "btc_cftc_leveraged_fund_net_short_level_carry_crash_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-leveraged-fund-net-short-level-carry-crash-historical.v1.manifest.json"
)
ARTIFACT_DIR = (
    REPO_ROOT
    / ".research-state"
    / "experiments"
    / "btc-cftc-leveraged-fund-net-short-level-carry-crash-historical-v1"
    / "artifacts"
)


def _load_runner():
    spec = importlib.util.spec_from_file_location("carry_crash_frozen_runner", RUNNER_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class CftcCarryCrashPredictiveScreenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _load_runner()

    def test_frozen_manifest_and_source_hashes_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_nearest_rank_and_fisher_are_deterministic(self) -> None:
        values = [self.runner.D(value) for value in range(1, 53)]
        self.assertEqual(
            self.runner.nearest_rank(values, self.runner.D("0.75")),
            self.runner.D("39"),
        )
        with localcontext() as context:
            context.prec = 34
            self.assertEqual(
                self.runner.one_sided_fisher_greater(2, 0, 0, 2),
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
            "40c8ca813c8f5b4d7e559ccfdf820deb43d44fb448695e8f3443a897dede09ea",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_LEVERAGED_FUND_NET_SHORT_CARRY_CRASH_PROXY_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertIn(
            "design_high_risk_crash_rate_at_least_10pp_worse",
            result["failed_pre_economic_gates"],
        )
        self.assertIn(
            "validation_high_risk_median_path_drawdown_strictly_worse",
            result["failed_pre_economic_gates"],
        )


if __name__ == "__main__":
    unittest.main()
