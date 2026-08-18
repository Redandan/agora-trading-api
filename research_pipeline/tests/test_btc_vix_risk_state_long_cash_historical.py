import importlib.util
import json
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_vix_risk_state_long_cash_historical.py"
MANIFEST_PATH = REPO_ROOT / "research_pipeline" / "examples" / "btc-vix-risk-state-long-cash-historical.v1.manifest.json"


def _runner():
    spec = importlib.util.spec_from_file_location("btc_vix_test_runner", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcVixRiskStateHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _runner()

    def test_sealed_vix_source_inventory_and_hashes(self) -> None:
        rows = self.runner.parse_vix_rows(self.runner.VIX_SOURCE)
        self.assertEqual(1780, len(rows))
        self.assertEqual("2018-01-02", rows[0].day.isoformat())
        self.assertEqual("2024-12-31", rows[-1].day.isoformat())
        self.assertEqual(
            self.runner.EXPECTED_VIX_SHA256,
            self.runner.sha256(self.runner.VIX_SOURCE),
        )
        self.assertEqual(
            self.runner.EXPECTED_VIX_METADATA_SHA256,
            self.runner.sha256(self.runner.VIX_SOURCE_METADATA),
        )
        self.assertEqual(
            self.runner.EXPECTED_PRIOR_SHA256,
            self.runner.sha256(self.runner.PRIOR_SOURCE),
        )

    def test_midrank_uses_exact_prior_252_values(self) -> None:
        prior = [Decimal("1")] * 126 + [Decimal("2")] * 126
        self.assertEqual(Decimal("0.75"), self.runner.midrank_percentile(Decimal("2"), prior))
        with self.assertRaisesRegex(self.runner.ResearchReject, "VIX_LOOKBACK"):
            self.runner.midrank_percentile(Decimal("2"), prior[:-1])

    def test_signal_is_effective_only_on_following_utc_day(self) -> None:
        rows = self.runner.parse_vix_rows(self.runner.VIX_SOURCE)
        signals = self.runner.build_signal_percentiles(rows)
        first_source = rows[self.runner.LOOKBACK]
        first_effective = min(signals)
        self.assertEqual(
            datetime.combine(first_source.day, datetime.min.time()).replace()
            + self.runner.timedelta(days=1),
            first_effective,
        )
        self.assertNotIn(datetime.combine(first_source.day, datetime.min.time()), signals)
        self.assertEqual(1528, len(signals))

    def test_manifest_freezes_exact_three_variants_and_denies_oos(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(3, len(manifest["strategy_policy"]["variants"]))
        self.assertEqual("0.8", manifest["strategy_policy"]["variants"][1]["threshold"])
        self.assertEqual("DENY", manifest["oos_access"])
        changed = json.loads(json.dumps(manifest))
        changed["strategy_policy"]["variants"][1]["threshold"] = "0.85"
        with self.assertRaisesRegex(self.runner.ResearchReject, "VARIANTS"):
            self.runner.validate_manifest(changed)


if __name__ == "__main__":
    unittest.main()
