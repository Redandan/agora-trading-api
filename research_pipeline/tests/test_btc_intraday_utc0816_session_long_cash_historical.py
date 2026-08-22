from __future__ import annotations

import copy
import importlib.util
import json
import sys
import unittest
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = (
    REPO_ROOT / "research" / "btc_intraday_utc0816_session_long_cash_historical.py"
)
MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-intraday-utc0816-session-long-cash-historical.v1.manifest.json"
)
DECISION_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-intraday-utc0816-session-long-cash-historical.v1.decision.json"
)
ARTIFACT_DIR = (
    REPO_ROOT
    / ".research-state"
    / "experiments"
    / "btc-intraday-utc0816-session-long-cash-historical-v1"
    / "artifacts"
)


def load_runner():
    spec = importlib.util.spec_from_file_location(
        "btc_intraday_utc0816_session_long_cash_historical_test", RUNNER_PATH
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("runner import failed")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


runner = load_runner()
D = Decimal


@dataclass(frozen=True)
class Bar:
    open_time: datetime
    close_time: datetime
    open: D
    close: D


def one_day_bars(entry: str = "100", exit_price: str = "110") -> list[Bar]:
    start = datetime(2020, 1, 1)
    bars: list[Bar] = []
    for hour in range(24):
        price = D(entry) if hour < 16 else D(exit_price)
        bars.append(
            Bar(
                open_time=start + timedelta(hours=hour),
                close_time=start + timedelta(hours=hour + 1),
                open=price,
                close=price,
            )
        )
    return bars


class BtcIntradayUtc0816SessionLongCashHistoricalTest(unittest.TestCase):
    def test_frozen_source_hashes_and_manifest_bindings_match(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        expected = {
            runner.ECONOMIC_SUPPORT_SOURCE: runner.EXPECTED_ECONOMIC_SUPPORT_SHA256,
            runner.PARSER_SOURCE: runner.EXPECTED_PARSER_SHA256,
            runner.PRIOR_SOURCE: runner.EXPECTED_PRIOR_SHA256,
            runner.HYPOTHESIS_SOURCE: runner.EXPECTED_HYPOTHESIS_SHA256,
        }
        for path, digest in expected.items():
            self.assertEqual(runner.sha256(path), digest)

        bindings = {item["path"]: item["sha256"] for item in manifest["source_bindings"]}
        self.assertEqual(
            bindings[RUNNER_PATH.relative_to(REPO_ROOT).as_posix()],
            runner.sha256(RUNNER_PATH),
        )
        self.assertEqual(
            bindings[runner.PRIOR_SOURCE.relative_to(REPO_ROOT).as_posix()],
            runner.EXPECTED_PRIOR_SHA256,
        )
        self.assertEqual(
            bindings[runner.HYPOTHESIS_SOURCE.relative_to(REPO_ROOT).as_posix()],
            runner.EXPECTED_HYPOTHESIS_SHA256,
        )
        runner.validate_manifest(manifest)

    def test_exact_session_actions_and_rejects_policy_drift(self) -> None:
        day = datetime(2020, 1, 1)
        self.assertEqual(runner.session_action(day.replace(hour=8), 8, 16), "BUY")
        self.assertEqual(runner.session_action(day.replace(hour=16), 8, 16), "SELL")
        self.assertIsNone(runner.session_action(day.replace(hour=12), 8, 16))
        with self.assertRaisesRegex(
            runner.ResearchReject, "MANIFEST_REJECT:SESSION_POLICY"
        ):
            runner.session_action(day.replace(hour=10), 10, 18)

    def test_zero_cost_one_day_realizes_exact_ten_percent(self) -> None:
        support = runner.load_module(
            "frozen_intraday_session_economic_support_synthetic_gain",
            runner.ECONOMIC_SUPPORT_SOURCE,
        )
        output, raw = runner.simulate_scenario(
            support,
            one_day_bars(),
            (datetime(2020, 1, 1), datetime(2020, 1, 2)),
            8,
            16,
            D("0"),
            D("0"),
        )
        self.assertEqual(raw["total_return"], D("10.0"))
        self.assertEqual(raw["realized_return"], D("10.0"))
        self.assertEqual(raw["unrealized_return"], D("0.0"))
        self.assertEqual(output["candidate"]["completed_episode_count"], 1)
        self.assertEqual(output["candidate"]["position_change_count"], 2)
        self.assertEqual(output["candidate"]["median_hold_hours"], "8.00000000")
        self.assertEqual(output["candidate"]["p90_hold_hours"], "8.00000000")
        self.assertFalse(output["candidate"]["terminal_position"])

    def test_normal_two_sided_cost_is_negative_on_flat_price(self) -> None:
        support = runner.load_module(
            "frozen_intraday_session_economic_support_synthetic_cost",
            runner.ECONOMIC_SUPPORT_SOURCE,
        )
        output, raw = runner.simulate_scenario(
            support,
            one_day_bars(entry="100", exit_price="100"),
            (datetime(2020, 1, 1), datetime(2020, 1, 2)),
            8,
            16,
            D("0.0010"),
            D("0.0005"),
        )
        self.assertLess(raw["total_return"], D("0"))
        self.assertEqual(raw["unrealized_return"], D("0"))
        self.assertGreater(D(output["candidate"]["fees_equity_units"]), D("0"))

    def test_manifest_has_exact_three_variants_and_rejects_drift(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.assertEqual(len(runner.VARIANTS), 3)
        self.assertEqual(manifest["strategy_policy"]["variants"], 3)
        drifted = copy.deepcopy(manifest)
        drifted["strategy_policy"]["primary"]["entry_hour_utc"] = 10
        with self.assertRaisesRegex(runner.ResearchReject, "MANIFEST_REJECT:PRIMARY"):
            runner.validate_manifest(drifted)

    def test_sealed_failure_decision_and_replication_are_consistent(self) -> None:
        run1_path = ARTIFACT_DIR / "run1.json"
        run2_path = ARTIFACT_DIR / "run2.json"
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        result = json.loads(run1_path.read_text(encoding="utf-8"))
        self.assertEqual(run1_path.read_bytes(), run2_path.read_bytes())
        self.assertEqual(
            runner.sha256(run1_path),
            "8056aff649367fe371e04e3d6e18e22934d970104a6ddf941637c655ab9d1796",
        )
        self.assertEqual(runner.sha256(run1_path), runner.sha256(run2_path))
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_INTRADAY_FIXED_UTC_SESSION_LONG_CASH_FAMILY",
        )
        self.assertFalse(result["all_gates_pass"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual(decision["artifact"]["sha256"], runner.sha256(run1_path))
        self.assertTrue(decision["deterministic_replication"]["byte_identical"])
        self.assertTrue(decision["prohibited_reopen"])
        self.assertFalse(decision["oos_opened"])


if __name__ == "__main__":
    unittest.main()
