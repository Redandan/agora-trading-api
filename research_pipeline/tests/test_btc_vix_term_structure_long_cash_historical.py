import importlib.util
import json
import sys
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = REPO_ROOT / "research" / "btc_vix_term_structure_long_cash_historical.py"
MANIFEST_PATH = REPO_ROOT / "research_pipeline" / "examples" / "btc-vix-term-structure-long-cash-historical.v1.manifest.json"


def _runner():
    spec = importlib.util.spec_from_file_location("btc_vix_term_structure_test_runner", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BtcVixTermStructureHistoricalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = _runner()

    def test_cboe_parser_requires_exact_columns_order_and_valid_ohlc(self) -> None:
        payload = (
            b"DATE,OPEN,HIGH,LOW,CLOSE\r\n"
            b"01/02/2024,15.00,16.00,14.00,15.50\r\n"
            b"01/03/2024,15.50,17.00,15.00,16.50\r\n"
        )
        rows = self.runner.parse_cboe_csv(payload, label="VIX3M")
        self.assertEqual([date(2024, 1, 2), date(2024, 1, 3)], [row.day for row in rows])
        self.assertEqual(Decimal("16.50"), rows[-1].close)

        with self.assertRaisesRegex(self.runner.ResearchReject, "COLUMNS"):
            self.runner.parse_cboe_csv(
                b"DATE,CLOSE\n01/02/2024,15.50\n",
                label="VIX3M",
            )
        with self.assertRaisesRegex(self.runner.ResearchReject, "OHLC"):
            self.runner.parse_cboe_csv(
                b"DATE,OPEN,HIGH,LOW,CLOSE\n01/02/2024,15,14,13,16\n",
                label="VIX3M",
            )
        with self.assertRaisesRegex(self.runner.ResearchReject, "ORDER"):
            self.runner.parse_cboe_csv(
                payload + b"01/02/2024,15.00,16.00,14.00,15.50\n",
                label="VIX3M",
            )

    def test_ratio_uses_only_matched_close_and_is_effective_next_utc_day(self) -> None:
        vix = [
            SimpleNamespace(day=date(2024, 1, 2), close=Decimal("15")),
            *[
                SimpleNamespace(day=day, close=Decimal("15"))
                for day in self.runner.EXPECTED_MISSING_VIX3M_DATES
            ],
        ]
        vix3m = [
            self.runner.CboeRow(
                day=date(2024, 1, 2),
                open=Decimal("19"),
                high=Decimal("21"),
                low=Decimal("18"),
                close=Decimal("20"),
            )
        ]
        original_matched = self.runner.EXPECTED_MATCHED_ROWS
        self.runner.EXPECTED_MATCHED_ROWS = 1
        try:
            self.assertEqual(
                {datetime(2024, 1, 3): Decimal("0.75")},
                self.runner.build_ratio_signals(vix, vix3m),
            )
        finally:
            self.runner.EXPECTED_MATCHED_ROWS = original_matched
        vix3m[0] = self.runner.CboeRow(
            day=date(2024, 1, 3),
            open=Decimal("19"),
            high=Decimal("21"),
            low=Decimal("18"),
            close=Decimal("20"),
        )
        with self.assertRaisesRegex(self.runner.ResearchReject, "DATE_ALIGNMENT"):
            self.runner.build_ratio_signals(vix, vix3m)

    def test_manifest_freezes_exact_three_variants_and_denies_oos(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        changed = json.loads(json.dumps(manifest))
        changed["strategy_policy"]["variants"][1]["threshold"] = "1.01"
        with self.assertRaisesRegex(self.runner.ResearchReject, "VARIANTS"):
            self.runner.validate_manifest(changed)
        changed = json.loads(json.dumps(manifest))
        changed["oos_access"] = "ALLOW"
        with self.assertRaisesRegex(self.runner.ResearchReject, "OOS_ACCESS"):
            self.runner.validate_manifest(changed)

    def test_capture_rejects_any_noncanonical_destination_before_network(self) -> None:
        with self.assertRaisesRegex(self.runner.ResearchReject, "DESTINATION"):
            self.runner.capture_vix3m_source(REPO_ROOT / "unexpected-vix3m.csv")


if __name__ == "__main__":
    unittest.main()
