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
    REPO_ROOT / "research" / "btc_daily_macd12_26_9_histogram_long_cash_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-macd12-26-9-histogram-long-cash-historical.v1.manifest.json"
)
DECISION_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-macd12-26-9-histogram-long-cash-historical.v1.decision.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "tested_btc_daily_macd12_26_9", RUNNER_PATH
    )
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcDailyMacd12269HistogramLongCashHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_frozen_base_runner_hash_matches(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.BASE_RUNNER_SOURCE),
            self.runner.EXPECTED_BASE_RUNNER_SHA256,
        )

    def test_frozen_preregistration_hashes_match(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.PRIOR_SOURCE),
            self.runner.EXPECTED_PRIOR_SHA256,
        )
        self.assertEqual(
            self.runner.sha256(self.runner.HYPOTHESIS_SOURCE),
            self.runner.EXPECTED_HYPOTHESIS_SHA256,
        )

    def test_manifest_and_all_source_bindings_match(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        for binding in manifest["source_bindings"]:
            source = REPO_ROOT / binding["path"]
            self.assertTrue(source.is_file(), binding["path"])
            self.assertEqual(self.runner.sha256(source), binding["sha256"])

    def test_decision_matches_sealed_runs_and_manifest(self) -> None:
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            decision["manifest_sha256"], self.runner.sha256(MANIFEST_PATH)
        )
        artifact = REPO_ROOT / decision["artifact"]["path"]
        replication = REPO_ROOT / decision["deterministic_replication"]["path"]
        self.assertEqual(self.runner.sha256(artifact), decision["artifact"]["sha256"])
        self.assertEqual(
            self.runner.sha256(replication),
            decision["deterministic_replication"]["sha256"],
        )
        self.assertEqual(artifact.read_bytes(), replication.read_bytes())
        result = json.loads(artifact.read_text(encoding="utf-8"))
        self.assertEqual(decision["status"], result["status"])
        self.assertEqual(decision["decision"], result["decision"])
        self.assertEqual(decision["failed_gates"], result["failed_gates"])

    def test_ema_initializes_with_simple_mean_then_recurses(self) -> None:
        values = [Decimal(value) for value in (1, 2, 3, 4, 5)]

        self.assertEqual(
            self.runner.ema_series(values, 3),
            [None, None, Decimal("2"), Decimal("3"), Decimal("4")],
        )

    def test_histogram_state_is_causal_and_flips_after_momentum_changes(self) -> None:
        start = datetime(2020, 1, 1)
        closes = [Decimal("100")] * 34
        closes.extend(Decimal(value) for value in (110, 120, 130, 120, 100, 80))
        daily = [
            SimpleNamespace(
                close_time=start + timedelta(days=index + 1), close=close
            )
            for index, close in enumerate(closes)
        ]

        targets = self.runner.target_by_execution_time(daily, 12, 26)

        self.assertEqual(list(targets), [point.close_time for point in daily[33:]])
        self.assertEqual(
            list(targets.values()), [False, True, True, True, True, True, False]
        )

    def test_only_frozen_primary_and_rejection_neighbors_are_accepted(self) -> None:
        start = datetime(2020, 1, 1)
        daily = [
            SimpleNamespace(
                close_time=start + timedelta(days=index + 1),
                close=Decimal(100 + index),
            )
            for index in range(40)
        ]

        for fast in (10, 12, 14):
            self.assertEqual(
                len(self.runner.target_by_execution_time(daily, fast, 26)), 7
            )
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:MACD_POLICY"
        ):
            self.runner.target_by_execution_time(daily, 16, 26)
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:MACD_POLICY"
        ):
            self.runner.target_by_execution_time(daily, 12, 30)


if __name__ == "__main__":
    unittest.main()
