from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_money_flow_index_14_midline_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-money-flow-index-14-midline-long-cash-historical.v1.manifest.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("mfi14_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass(frozen=True)
class Point:
    close_time: datetime
    high: Decimal
    low: Decimal
    close: Decimal
    volume: Decimal


class MoneyFlowIndexRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def points(self, typical: list[str], volumes: list[str] | None = None) -> list[Point]:
        volumes = volumes or ["1"] * len(typical)
        start = datetime(2020, 1, 2)
        return [
            Point(
                start + timedelta(days=index),
                Decimal(value),
                Decimal(value),
                Decimal(value),
                Decimal(volumes[index]),
            )
            for index, value in enumerate(typical)
        ]

    def test_manifest_accepts_exact_three_variant_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)

    def test_manifest_rejects_neighbor_change(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["rejection_only_neighbors"][1]["long_threshold"] = "60"
        with self.assertRaisesRegex(self.runner.ResearchReject, "NEIGHBORS"):
            self.runner.validate_manifest(manifest)

    def test_equal_positive_and_negative_raw_flow_is_not_above_50(self) -> None:
        typical = ["10"] + ["11", "10"] * 7
        volumes = ["1"] + ["10", "11"] * 7
        targets, values = self.runner.mfi_targets(
            self.points(typical, volumes), 14, 50
        )
        self.assertEqual(values, [Decimal("50")])
        self.assertEqual(list(targets.values()), [False])

    def test_volume_weighting_can_put_mfi_strictly_above_midline(self) -> None:
        typical = ["10"] + ["11", "10"] * 7
        volumes = ["1"] + ["2", "1"] * 7
        targets, values = self.runner.mfi_targets(
            self.points(typical, volumes), 14, 50
        )
        self.assertGreater(values[0], Decimal("50"))
        self.assertEqual(list(targets.values()), [True])

    def test_flat_typical_price_uses_frozen_zero_denominator_cash_rule(self) -> None:
        targets, values = self.runner.mfi_targets(self.points(["10"] * 15), 14, 45)
        self.assertEqual(values, [Decimal("0")])
        self.assertEqual(list(targets.values()), [False])

    def test_rejects_unregistered_threshold(self) -> None:
        with self.assertRaisesRegex(self.runner.ResearchReject, "MFI_POLICY"):
            self.runner.mfi_targets(self.points(["10"] * 15), 14, 60)


if __name__ == "__main__":
    unittest.main()
