import importlib.util
import json
import sys
from decimal import Decimal
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT
    / "research"
    / "btc_monthly_volatility_of_volatility_half_risk_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-monthly-volatility-of-volatility-half-risk-historical.v1.manifest.json"
)


def _runner():
    spec = importlib.util.spec_from_file_location("btc_vov_half_risk_test_runner", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcMonthlyVolatilityOfVolatilityHalfRiskHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _runner()

    def test_manifest_freezes_exact_three_variants_and_denies_oos(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        variants = manifest["strategy_policy"]["variants"]
        self.assertEqual(3, len(variants))
        self.assertEqual("0.8", variants[1]["threshold"])
        self.assertEqual("BTC_50_PERCENT_CASH_50_PERCENT", manifest["strategy_policy"]["high_risk_target"])
        self.assertEqual("DENY", manifest["oos_access"])

        changed = json.loads(json.dumps(manifest))
        changed["strategy_policy"]["variants"][1]["threshold"] = "0.85"
        with self.assertRaisesRegex(self.runner.ResearchReject, "VARIANTS"):
            self.runner.validate_manifest(changed)

    def test_midrank_uses_exact_prior_252_values(self) -> None:
        d = Decimal
        prior = [d("1")] * 126 + [d("2")] * 126
        self.assertEqual(d("0.75"), self.runner.midrank_percentile(d("2"), prior))
        with self.assertRaisesRegex(self.runner.ResearchReject, "PERCENTILE_LOOKBACK"):
            self.runner.midrank_percentile(d("2"), prior[:-1])

    def test_volatility_of_volatility_uses_exact_twenty_logs(self) -> None:
        d = Decimal
        values = [d(index) for index in range(20)]
        actual = self.runner._population_standard_deviation(values)
        expected = (d("33.25")).sqrt()
        self.assertLess(abs(actual - expected), d("1e-45"))
        with self.assertRaisesRegex(self.runner.ResearchReject, "VOV_LOOKBACK"):
            self.runner._population_standard_deviation(values[:-1])

    def test_spearman_detects_redundant_and_independent_ordering(self) -> None:
        d = Decimal
        ascending = [d(index) for index in range(1, 7)]
        descending = list(reversed(ascending))
        mixed = [d(value) for value in (1, 4, 2, 6, 3, 5)]
        self.assertEqual(d("1"), self.runner.spearman_correlation(ascending, ascending))
        self.assertEqual(d("-1"), self.runner.spearman_correlation(ascending, descending))
        self.assertLess(abs(self.runner.spearman_correlation(ascending, mixed)), d("0.80"))

    def test_frozen_source_hashes_match(self) -> None:
        self.assertEqual(
            self.runner.EXPECTED_EXECUTION_REFERENCE_SHA256,
            self.runner.sha256(self.runner.EXECUTION_REFERENCE_SOURCE),
        )
        self.assertEqual(
            self.runner.EXPECTED_ECONOMIC_BASE_SHA256,
            self.runner.sha256(self.runner.ECONOMIC_BASE_SOURCE),
        )
        self.assertEqual(
            self.runner.EXPECTED_PARSER_SHA256,
            self.runner.sha256(self.runner.PARSER_SOURCE),
        )
        self.assertEqual(
            self.runner.EXPECTED_PRIOR_SHA256,
            self.runner.sha256(self.runner.PRIOR_SOURCE),
        )


if __name__ == "__main__":
    unittest.main()
