from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal as D
from pathlib import Path
import unittest

from research import binance_btc_paxg_daily_source_v1 as source
from research.btc_paxg_monthly_relative_momentum_rotation_v1 import (
    Bar,
    formation_returns,
    selected_symbol,
    simulate_rotation,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_MANIFEST = REPO_ROOT / (
    "research_pipeline/examples/"
    "btc-paxg-monthly-relative-momentum-rotation-source.v1.manifest.json"
)


def synthetic_bars(first: date, last: date) -> dict[date, dict[str, Bar]]:
    by_day: dict[date, dict[str, Bar]] = {}
    current = first
    offset = 0
    while current <= last:
        btc = D("100") + D(offset) / D("10")
        paxg = D("100")
        by_day[current] = {
            "BTCUSDT": Bar("BTCUSDT", current, btc, btc, btc, btc),
            "PAXGUSDT": Bar("PAXGUSDT", current, paxg, paxg, paxg, paxg),
        }
        current += timedelta(days=1)
        offset += 1
    return by_day


class BtcPaxgMonthlyRelativeMomentumRotationV1Test(unittest.TestCase):
    def test_source_contract_reuses_existing_parser_and_exact_calendar(self) -> None:
        manifest = source.verify_source_manifest(SOURCE_MANIFEST)
        self.assertEqual(
            ["BTCUSDT", "PAXGUSDT"], manifest["acquisition_policy"]["symbols"]
        )
        self.assertEqual(52, len(source.expected_months()))
        self.assertEqual(1583, len(source.expected_days()))

    def test_formation_uses_six_month_start_open_and_prior_day_close(self) -> None:
        bars = synthetic_bars(date(2020, 9, 1), date(2021, 2, 28))
        returns = formation_returns(bars, date(2021, 3, 1))
        self.assertGreater(returns["BTCUSDT"], returns["PAXGUSDT"])
        self.assertEqual("BTCUSDT", selected_symbol(bars, date(2021, 3, 1)))

    def test_exact_tie_is_frozen_to_btc(self) -> None:
        bars = synthetic_bars(date(2020, 9, 1), date(2021, 2, 28))
        for day in bars:
            btc = bars[day]["BTCUSDT"]
            bars[day]["BTCUSDT"] = Bar(
                "BTCUSDT", day, D("100"), D("100"), D("100"), D("100")
            )
            self.assertEqual(day, btc.day)
        self.assertEqual("BTCUSDT", selected_symbol(bars, date(2021, 3, 1)))

    def test_full_year_monthly_clock_does_not_trade_when_leader_is_unchanged(self) -> None:
        bars = synthetic_bars(date(2021, 7, 1), date(2022, 12, 31))
        metrics, raw = simulate_rotation(
            bars,
            window=(date(2022, 1, 1), date(2023, 1, 1)),
            fee=D("0.0010"),
            slippage=D("0.0005"),
        )
        self.assertEqual(D(12), raw["signal_count"])
        self.assertEqual(D(0), raw["switch_count"])
        self.assertEqual(1, metrics["trade_count"])
        self.assertEqual("BTCUSDT", metrics["terminal_inventory"]["symbol"])


if __name__ == "__main__":
    unittest.main()
