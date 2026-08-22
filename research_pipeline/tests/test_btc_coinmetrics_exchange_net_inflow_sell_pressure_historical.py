from __future__ import annotations

import hashlib
import importlib.util
import json
from decimal import localcontext
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_coinmetrics_exchange_net_inflow_sell_pressure_historical.py"
MANIFEST_PATH = REPO_ROOT / "research_pipeline" / "examples" / "btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical.v1.manifest.json"
ARTIFACT_DIR = REPO_ROOT / ".research-state" / "experiments" / "btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1" / "artifacts"


def _load_runner():
    spec = importlib.util.spec_from_file_location("exchange_net_flow_frozen_runner", RUNNER_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class CoinMetricsExchangeNetFlowPredictiveScreenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _load_runner()

    def test_frozen_manifest_and_source_hashes_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_fisher_direction_is_deterministic(self) -> None:
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
            "8133e7d10181574627b8c5e17aaf0ee48a9c503d57ee4f80e1eefd1cb9db2b62",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_FAMILY_PRE_ECONOMIC",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["economic_evidence_accessed"])
        self.assertFalse(result["oos_opened"])
        self.assertIn(
            "design_one_sided_fisher_negative_rate_p_value_at_most_0_10",
            result["failed_pre_economic_gates"],
        )
        self.assertIn(
            "validation_top_positive_annual_downside_delta_contribution_at_most_60pct",
            result["failed_pre_economic_gates"],
        )


if __name__ == "__main__":
    unittest.main()
