from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT / "research" / "btc_daily_rsi14_midline_long_cash_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-rsi14-midline-long-cash-historical.v1.manifest.json"
)
DECISION_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-rsi14-midline-long-cash-historical.v1.decision.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "tested_btc_daily_rsi14_midline", RUNNER_PATH
    )
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcDailyRsi14MidlineLongCashHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_manifest_freezes_primary_and_rejection_only_neighbors(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        policy = manifest["strategy_policy"]
        self.assertEqual(
            policy["primary"],
            {"rsi_complete_day_changes": 14, "long_threshold": "50"},
        )
        self.assertEqual(
            policy["rejection_only_neighbors"],
            [
                {"rsi_complete_day_changes": 14, "long_threshold": "45"},
                {"rsi_complete_day_changes": 14, "long_threshold": "55"},
            ],
        )
        self.assertEqual(
            policy["neighbor_use"], "STABILITY_REJECTION_ONLY_NO_SELECTION"
        )
        self.assertEqual(policy["variants"], 3)

    def test_frozen_non_outcome_source_bindings_match(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.ECONOMIC_SUPPORT_SOURCE),
            self.runner.EXPECTED_ECONOMIC_SUPPORT_SHA256,
        )
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

    def test_rsi_signal_uses_fourteen_prior_complete_changes(self) -> None:
        start = datetime(2020, 1, 1)
        closes = [Decimal("100")]
        for change in [Decimal("1")] * 8 + [Decimal("-1")] * 7:
            closes.append(closes[-1] + change)
        daily = [
            SimpleNamespace(
                close_time=start + timedelta(days=index + 1),
                close=close,
            )
            for index, close in enumerate(closes)
        ]
        targets = self.runner.target_by_execution_time(daily, 14, 50)
        self.assertEqual(list(targets), [daily[14].close_time, daily[15].close_time])
        self.assertTrue(targets[daily[14].close_time])
        self.assertFalse(targets[daily[15].close_time])

    def test_decision_permanently_closes_failed_family_without_oos(self) -> None:
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            decision["status"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_RSI14_MIDLINE_LONG_CASH_FAMILY",
        )
        self.assertTrue(decision["prohibited_reopen"])
        self.assertFalse(decision["oos_opened"])
        self.assertTrue(decision["deterministic_replication"]["byte_identical"])


if __name__ == "__main__":
    unittest.main()
