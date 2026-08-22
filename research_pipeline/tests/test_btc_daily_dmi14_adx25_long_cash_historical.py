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
    REPO_ROOT / "research" / "btc_daily_dmi14_adx25_long_cash_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-dmi14-adx25-long-cash-historical.v1.manifest.json"
)
DECISION_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-dmi14-adx25-long-cash-historical.v1.decision.json"
)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "tested_btc_daily_dmi14_adx25", RUNNER_PATH
    )
    if spec is None or spec.loader is None:
        raise AssertionError("runner import spec unavailable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def daily_point(start: datetime, index: int, center: int) -> SimpleNamespace:
    value = Decimal(center)
    return SimpleNamespace(
        close_time=start + timedelta(days=index + 1),
        high=value + Decimal("2"),
        low=value - Decimal("2"),
        close=value,
    )


class BtcDailyDmi14Adx25LongCashHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_frozen_sources_match(self) -> None:
        self.assertEqual(
            self.runner.sha256(self.runner.BASE_RUNNER_SOURCE),
            self.runner.EXPECTED_BASE_RUNNER_SHA256,
        )
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

    def test_wilder_smoothed_sum_initializes_then_recurses(self) -> None:
        values = [Decimal("1")] * 5
        self.assertEqual(
            self.runner.wilder_smoothed_sums(values, 3),
            [None, None, Decimal("3"), Decimal("3"), Decimal("3")],
        )

    def test_wilder_average_initializes_then_recurses(self) -> None:
        values = [Decimal(value) for value in (1, 2, 3, 4, 5)]
        self.assertEqual(
            self.runner.wilder_average(values, 3),
            [
                None,
                None,
                Decimal("2"),
                Decimal("8") / Decimal("3"),
                Decimal("3.444444444444444444444444444444443"),
            ],
        )

    def test_monotonic_uptrend_has_defined_bullish_adx_targets(self) -> None:
        start = datetime(2020, 1, 1)
        daily = [daily_point(start, index, 100 + index) for index in range(60)]

        for threshold in (20, 25, 30):
            targets = self.runner.target_by_execution_time(daily, 14, threshold)
            self.assertEqual(list(targets), [point.close_time for point in daily[27:]])
            self.assertEqual(len(targets), 33)
            self.assertTrue(all(targets.values()))

    def test_future_days_do_not_change_existing_targets(self) -> None:
        start = datetime(2020, 1, 1)
        daily = [daily_point(start, index, 100 + index) for index in range(50)]
        extended = daily + [
            daily_point(start, index, 250 - index) for index in range(50, 75)
        ]

        original = self.runner.target_by_execution_time(daily, 14, 25)
        future = self.runner.target_by_execution_time(extended, 14, 25)
        self.assertEqual(original, {key: future[key] for key in original})

    def test_only_frozen_primary_and_neighbors_are_accepted(self) -> None:
        start = datetime(2020, 1, 1)
        daily = [daily_point(start, index, 100 + index) for index in range(40)]
        for threshold in (20, 25, 30):
            self.assertEqual(
                len(self.runner.target_by_execution_time(daily, 14, threshold)), 13
            )
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:DMI_ADX_POLICY"
        ):
            self.runner.target_by_execution_time(daily, 10, 25)
        with self.assertRaisesRegex(
            self.runner.ResearchReject, "MANIFEST_REJECT:DMI_ADX_POLICY"
        ):
            self.runner.target_by_execution_time(daily, 14, 35)


if __name__ == "__main__":
    unittest.main()
