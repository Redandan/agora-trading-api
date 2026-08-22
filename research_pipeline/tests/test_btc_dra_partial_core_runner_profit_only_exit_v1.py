from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_partial_core_runner_profit_only_exit_v1 as runner


D = Decimal


def bar(opened: datetime, *, open_price: str, close: str) -> runner.base.Bar:
    opened_price = D(open_price)
    close_price = D(close)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=opened_price,
        high=max(opened_price, close_price),
        low=min(opened_price, close_price),
        close=close_price,
        volume=D("1"),
    )


class PartialCoreRunnerProfitOnlyExitTest(unittest.TestCase):
    def test_frozen_bindings_and_sealed_input_identity_verify(self) -> None:
        verified = runner.verify_bindings()
        self.assertEqual(
            "797c82c2a8a79e86c4eba483c7019679a6ff172ee6f928cbc7bbed43e3eeada8",
            verified["specification"]["sha256"],
        )
        bars, digest = runner.load_selection(
            ROOT / ".research-state" / "java-parity" / "selection-2019-2024.tsv"
        )
        self.assertEqual(runner.base.SELECTION_ROWS, len(bars))
        self.assertEqual(runner.base.SELECTION_SHA256, digest)

    def test_parent_fill_splits_quantity_and_cost_exactly(self) -> None:
        engine = runner.PartialCoreRunnerEngine(D("0.50"))
        opened = datetime(2024, 1, 2)
        engine.pending_signal = opened - timedelta(hours=1)
        engine.pending_atr = D("10")
        engine._fill_buy(bar(opened, open_price="100", close="100"))

        self.assertEqual(1, engine.buy_count)
        self.assertEqual(2, len(engine.lots))
        core = next(lot for lot in engine.lots if lot.path == "CORE")
        residual = next(lot for lot in engine.lots if lot.path == "RUNNER")
        self.assertEqual(D("30"), core.cost + residual.cost)
        self.assertEqual(D("15.00000000"), core.cost)
        self.assertEqual(
            runner.base.quantity((runner.base.LOT_COST - D("0.03000000")) / D("100.05000000")),
            core.quantity + residual.quantity,
        )
        self.assertEqual(D("30.00000000"), engine._open_cost())
        final_bar = bar(opened, open_price="100", close="100")
        engine._track(final_bar)
        self.assertTrue(
            engine.result(
                final_bar,
                opened,
                opened + timedelta(hours=1),
            )["split_feasibility"]["pass"]
        )

    def test_core_and_runner_use_distinct_frozen_profit_only_exits(self) -> None:
        engine = runner.PartialCoreRunnerEngine(D("0.50"))
        opened = datetime(2024, 1, 2)
        engine.pending_signal = opened - timedelta(hours=1)
        engine.pending_atr = D("10")
        engine._fill_buy(bar(opened, open_price="100", close="100"))
        engine.atr14 = D("10")

        rising = bar(opened + timedelta(hours=1), open_price="121", close="121")
        engine._queue_exits(rising)
        core = next(lot for lot in engine.lots if lot.path == "CORE")
        residual = next(lot for lot in engine.lots if lot.path == "RUNNER")
        self.assertIsNotNone(core.exit_queued_at)
        self.assertIsNone(residual.exit_queued_at)

        engine._fill_exits(
            bar(opened + timedelta(hours=2), open_price="121", close="121")
        )
        self.assertEqual(D("15.00000000"), engine._open_cost())
        falling = bar(opened + timedelta(hours=2), open_price="105", close="105")
        engine._queue_exits(falling)
        residual = next(lot for lot in engine.lots if lot.path == "RUNNER")
        self.assertIsNotNone(residual.exit_queued_at)

    def test_sealed_runs_and_decision_close_the_exact_family(self) -> None:
        experiment = (
            ROOT
            / ".research-state"
            / "experiments"
            / "dra-partial-core-runner-profit-only-exit-v1"
            / "artifacts"
        )
        run1 = (experiment / "run1.json").read_bytes()
        run2 = (experiment / "run2.json").read_bytes()
        expected_hash = (
            "c0f06a6113cdd6a361cbb6bfdcd23e8da8f3c10e6695ee7559461949a8da9af0"
        )
        self.assertEqual(run1, run2)
        self.assertEqual(expected_hash, hashlib.sha256(run1).hexdigest())

        result = json.loads(run1)
        decision = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "examples"
                / "dra-partial-core-runner-profit-only-exit-v1.decision.json"
            ).read_text(encoding="utf-8")
        )
        primary = next(
            variant for variant in result["variants"] if variant["role"] == "primary"
        )
        self.assertEqual(
            "NO_CANDIDATE_CLOSE_PARTIAL_CORE_RUNNER_EXIT_FAMILY",
            result["status"],
        )
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual("87.55452012", primary["validation"]["total_pnl_usdt"])
        self.assertEqual(3, primary["annual_total_wins"])
        self.assertEqual(0, primary["annual_capital_weighted_median_hold_wins"])
        self.assertEqual(
            sorted(
                gate for gate, passed in result["primary_gates"].items() if not passed
            ),
            sorted(decision["failed_primary_gates"]),
        )


if __name__ == "__main__":
    unittest.main()
