from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_daily_obv_ma_long_cash_historical.py"
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-obv-ma-long-cash-historical.v1.manifest.json"
)
DECISION_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-obv-ma-long-cash-historical.v1.decision.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location("tested_btc_daily_obv_ma", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcDailyObvMaLongCashHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_manifest_freezes_primary_and_rejection_only_neighbors(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        policy = manifest["strategy_policy"]
        self.assertEqual(
            policy["primary"],
            {"short_complete_days": 2, "long_complete_days": 24},
        )
        self.assertEqual(
            policy["rejection_only_neighbors"],
            [
                {"short_complete_days": 2, "long_complete_days": 18},
                {"short_complete_days": 2, "long_complete_days": 30},
            ],
        )
        self.assertEqual(policy["neighbor_use"], "STABILITY_REJECTION_ONLY_NO_SELECTION")
        self.assertEqual(policy["variants"], 3)

    def test_frozen_non_outcome_source_bindings_match(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.REFERENCE_SOURCE),
            self.runner.EXPECTED_REFERENCE_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.PARSER_SOURCE),
            self.runner.EXPECTED_PARSER_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.PRIOR_SOURCE),
            self.runner.EXPECTED_PRIOR_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.HYPOTHESIS_SOURCE),
            self.runner.EXPECTED_HYPOTHESIS_SHA256,
        )

    def test_obv_signal_uses_only_points_available_at_execution_time(self) -> None:
        start = datetime(2020, 1, 2)
        daily = [
            self.runner.DailyPoint(
                close_time=start + timedelta(days=index),
                close=Decimal(index + 1),
                volume=Decimal("10"),
                obv=Decimal(value),
            )
            for index, value in enumerate((0, 1, 2, -3, -4))
        ]
        targets = self.runner.target_by_execution_time(daily, 2, 3)
        self.assertEqual(list(targets), [point.close_time for point in daily[2:]])
        self.assertTrue(targets[daily[2].close_time])
        self.assertFalse(targets[daily[3].close_time])

    def test_decision_permanently_closes_failed_family_without_oos(self) -> None:
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            decision["status"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_OBV_MA_LONG_CASH_FAMILY",
        )
        self.assertTrue(decision["prohibited_reopen"])
        self.assertFalse(decision["oos_opened"])
        self.assertTrue(decision["deterministic_replication"]["byte_identical"])


if __name__ == "__main__":
    unittest.main()
