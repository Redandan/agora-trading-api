from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_coinmetrics_hash_ribbon_health_state_long_cash_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-hash-ribbon-health-state-long-cash-historical.v1.manifest.json"
SPEC = importlib.util.spec_from_file_location("btc_coinmetrics_hash_ribbon_health_state", RUNNER)
assert SPEC is not None and SPEC.loader is not None
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class BtcCoinmetricsHashRibbonHealthStateLongCashHistoricalTest(unittest.TestCase):
    def test_hash_ribbon_uses_latest_30_and_60_complete_days_plus_three_days(self) -> None:
        first = date(2020, 1, 1)
        rows = [
            (first + timedelta(days=index), Decimal(index + 1))
            for index in range(100)
        ]

        targets, feature = research.build_hash_ribbon_targets(rows)

        first_effective = datetime.combine(first + timedelta(days=62), datetime.min.time())
        self.assertEqual(len(targets), 41)
        self.assertTrue(targets[first_effective])
        self.assertEqual(feature["health_count"], 41)
        self.assertEqual(feature["stress_count"], 0)
        self.assertEqual(feature["first_effective_time"], first_effective.isoformat())

    def test_closed_weekly_28d_comparator_uses_adjacent_nonoverlapping_windows(self) -> None:
        first = date(2024, 1, 1)
        rows = [
            (first + timedelta(days=index), Decimal("10") if index < 28 else Decimal("20"))
            for index in range(56)
        ]

        targets = research.build_closed_28d_weekly_targets(rows)

        self.assertEqual(targets, {datetime(2024, 2, 28): True})

    def test_binary_phi_has_expected_independence_and_direction(self) -> None:
        self.assertEqual(
            research.binary_phi([True, True, False, False], [True, False, True, False]),
            Decimal("0"),
        )
        self.assertEqual(
            research.binary_phi([True, True, False, False], [True, True, False, False]),
            Decimal("1"),
        )
        self.assertEqual(
            research.binary_phi([True, True, False, False], [False, False, True, True]),
            Decimal("-1"),
        )

    def test_manifest_is_hash_bound_to_sources_policy_and_frozen_gates(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

        research.validate_manifest(manifest, RUNNER)

        self.assertEqual(manifest["strategy_policy"]["variants"], 1)
        self.assertEqual(manifest["strategy_policy"]["price_confirmation"], "DENY")
        self.assertEqual(manifest["oos_access"], "DENY")
        self.assertEqual(manifest["determinism"]["reruns"], 2)


if __name__ == "__main__":
    unittest.main()
