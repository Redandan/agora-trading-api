from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_binance_usdm_external_entry_admission_v1 as runner
from research_pipeline import binance_usdm_archive as archive


D = Decimal
FAMILIES = {
    "dra-binance-usdm-deleveraging-flush-entry-admission": (
        "joint-price-open-interest-deleveraging-flush",
        {
            "oi_value_return_at_or_below": "-0.05",
            "price_return_at_or_below": "-0.03",
        },
    ),
    "dra-binance-usdm-positioning-divergence-entry-admission": (
        "top-trader-versus-global-positioning-divergence",
        {"absolute_positioning_gap_at_or_above": "0.20"},
    ),
    "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission": (
        "joint-perpetual-taker-flow-open-interest-confirmation",
        {
            "oi_value_return_at_or_above": "0",
            "taker_long_short_ratio_at_or_above": "1.10",
        },
    ),
}


def manifest(family_key: str) -> dict:
    family, thresholds = FAMILIES[family_key]
    return {
        "authorization": runner.AUTHORIZATION,
        "dataset": {"canonical_sha256": "1" * 64, "rows": 100},
        "document_type": runner.DOCUMENT_TYPE,
        "economics": {
            "fee_rate": "0.0010",
            "initial_equity_usdt": "250",
            "slippage_rate": "0.0005",
            "slot_capacity_usdt": "240",
        },
        "experiment_id": "synthetic-binance-external-dra-v1",
        "external_dataset": {
            "archive_inventory_sha256": "2" * 64,
            "complete_utc_days": 1,
            "dataset": "BINANCE_USDM_DAILY_METRICS",
            "instrument": "BTCUSDT",
            "latest_permitted_observation": "2024-12-31T23:59:59Z",
            "normalized_payload_sha256": "3" * 64,
        },
        "feature": {
            "decision_time": "LATEST_COMPLETE_UTC_SOURCE_DAY_BEFORE_NEXT_DRA_FILL",
            "family_key": family_key,
            "feature_family": family,
        },
        "gate_set": runner.GATE_SET,
        "oos_access": "DENY",
        "parent_strategy": runner.PARENT_STRATEGY,
        "prior_evidence": {
            "disposition": "SOURCE_READY_FOR_ONE_SHARED_OFFLINE_ADAPTER_AND_DECLARATIVE_DRA_RUNNER_NO_HYPOTHESIS_OR_CANDIDATE_YET",
            "path": "research_pipeline/examples/binance-usdm-derivatives-archive-source-capability.v1.json",
            "sha256": "4" * 64,
        },
        "schema_version": "1",
        "selection_cutoff": runner.SELECTION_CUTOFF,
        "variants": [
            {"role": "primary", "thresholds": thresholds, "variant_id": "primary-v1"}
        ],
    }


def hourly_bar(opened: datetime, *, open_price: str = "100", close: str = "100") -> runner.base.Bar:
    opened_value = D(open_price)
    close_value = D(close)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=opened_value,
        high=max(opened_value, close_value),
        low=min(opened_value, close_value),
        close=close_value,
        volume=D("1"),
    )


def bundle(
    day: date = date(2024, 1, 2),
    *,
    first_oi_value: str = "1000",
    last_oi_value: str = "900",
    top_ratio: str = "1.30",
    global_ratio: str = "0.90",
    taker_ratio: str = "1.20",
) -> archive.DailyMetricsBundle:
    start = datetime.combine(day, datetime.min.time())
    observations = []
    for index in range(archive.EXPECTED_ROWS_PER_DAY):
        oi_value = first_oi_value if index == 0 else last_oi_value
        observations.append(
            archive.MetricsObservation(
                timestamp=start + timedelta(minutes=5 * index),
                symbol=archive.SYMBOL,
                sum_open_interest="100",
                sum_open_interest_value=oi_value,
                count_toptrader_long_short_ratio=top_ratio,
                sum_toptrader_long_short_ratio=top_ratio,
                count_long_short_ratio=global_ratio,
                sum_taker_long_short_vol_ratio=taker_ratio,
            )
        )
    return archive.DailyMetricsBundle(
        archive_name=f"BTCUSDT-metrics-{day.isoformat()}.zip",
        archive_sha256="5" * 64,
        checksum_sidecar_sha256="6" * 64,
        day=day,
        normalized_payload_sha256="7" * 64,
        observations=tuple(observations),
    )


class BinanceUsdmExternalDraRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "binance-usdm-external-dra-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(cls.schema)

    def test_schema_and_manual_contract_accept_exactly_three_joint_families(self) -> None:
        self.assertEqual(set(runner.FEATURES), set(FAMILIES))
        for family_key in FAMILIES:
            value = manifest(family_key)
            self.validator.validate(value)
            self.assertIs(runner.validate_manifest(value), value)

    def test_closed_univariate_and_carry_families_are_not_exposed(self) -> None:
        schema_text = json.dumps(self.schema, sort_keys=True)
        for forbidden in ("funding", "premium", "carry", "univariate"):
            self.assertNotIn(forbidden, schema_text.lower())

        value = manifest("dra-binance-usdm-deleveraging-flush-entry-admission")
        value["feature"] = {
            "decision_time": "LATEST_COMPLETE_UTC_SOURCE_DAY_BEFORE_NEXT_DRA_FILL",
            "family_key": "dra-crypto-carry-risk-veto",
            "feature_family": "funding-carry",
        }
        with self.assertRaisesRegex(runner.ScreenReject, "supported joint family"):
            runner.validate_manifest(value)

    def test_more_than_three_variants_and_wrong_joint_threshold_shape_fail_closed(self) -> None:
        value = manifest("dra-binance-usdm-positioning-divergence-entry-admission")
        value["variants"] = [
            {
                "role": role,
                "thresholds": {"absolute_positioning_gap_at_or_above": str(index + 1)},
                "variant_id": f"variant-{index}",
            }
            for index, role in enumerate(
                ("lower_neighbor", "primary", "upper_neighbor", "primary")
            )
        ]
        with self.assertRaisesRegex(runner.ScreenReject, "one to three"):
            runner.validate_manifest(value)

        wrong = manifest("dra-binance-usdm-deleveraging-flush-entry-admission")
        wrong["variants"][0]["thresholds"] = {"price_return_at_or_below": "-0.03"}
        with self.assertRaisesRegex(runner.ScreenReject, "closed object"):
            runner.validate_manifest(wrong)

    def test_external_day_uses_complete_day_and_preserves_next_fill_availability(self) -> None:
        day = date(2024, 1, 2)
        bars = [
            hourly_bar(
                datetime.combine(day, datetime.min.time()) + timedelta(hours=hour),
                open_price="100",
                close="96" if hour == 23 else "100",
            )
            for hour in range(24)
        ]
        observation = runner.external_day_from_bundle(bundle(day), bars)
        self.assertEqual(observation.day, day)
        self.assertEqual(observation.available_at, datetime(2024, 1, 3))
        self.assertEqual(observation.price_return, D("-0.04"))
        self.assertEqual(observation.oi_value_return, D("-0.1"))
        self.assertEqual(observation.positioning_gap, D("0.40"))
        self.assertEqual(observation.taker_long_short_ratio, D("1.20"))

    def test_decision_time_rejects_same_fill_day_or_later_observation(self) -> None:
        signal = hourly_bar(datetime(2024, 1, 2, 23))
        valid = runner.ExternalDay(
            day=date(2024, 1, 2),
            available_at=datetime(2024, 1, 3),
            price_return=D("-0.04"),
            oi_value_return=D("-0.10"),
            top_trader_long_short_ratio=D("1.3"),
            global_long_short_ratio=D("0.9"),
            taker_long_short_ratio=D("1.2"),
            source_normalized_sha256="7" * 64,
        )
        runner.validate_decision_observation(valid, signal)
        later = runner.ExternalDay(
            **{**valid.__dict__, "day": date(2024, 1, 3), "available_at": datetime(2024, 1, 4)}
        )
        with self.assertRaisesRegex(runner.ScreenReject, "immediately before"):
            runner.validate_decision_observation(later, signal)

    def test_each_family_requires_its_joint_inputs(self) -> None:
        observation = runner.ExternalDay(
            day=date(2024, 1, 2),
            available_at=datetime(2024, 1, 3),
            price_return=D("-0.04"),
            oi_value_return=D("-0.10"),
            top_trader_long_short_ratio=D("1.3"),
            global_long_short_ratio=D("0.9"),
            taker_long_short_ratio=D("1.2"),
            source_normalized_sha256="7" * 64,
        )
        self.assertTrue(
            runner.admits(
                "dra-binance-usdm-deleveraging-flush-entry-admission",
                FAMILIES["dra-binance-usdm-deleveraging-flush-entry-admission"][1],
                observation,
            )
        )
        self.assertTrue(
            runner.admits(
                "dra-binance-usdm-positioning-divergence-entry-admission",
                FAMILIES["dra-binance-usdm-positioning-divergence-entry-admission"][1],
                observation,
            )
        )
        confirmation = runner.ExternalDay(
            **{**observation.__dict__, "oi_value_return": D("0.01")}
        )
        self.assertTrue(
            runner.admits(
                "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission",
                FAMILIES[
                    "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission"
                ][1],
                confirmation,
            )
        )

        no_oi_flush = runner.ExternalDay(
            **{**observation.__dict__, "oi_value_return": D("0.01")}
        )
        self.assertFalse(
            runner.admits(
                "dra-binance-usdm-deleveraging-flush-entry-admission",
                FAMILIES["dra-binance-usdm-deleveraging-flush-entry-admission"][1],
                no_oi_flush,
            )
        )
        no_oi_confirmation = runner.ExternalDay(
            **{**observation.__dict__, "oi_value_return": D("-0.01")}
        )
        self.assertFalse(
            runner.admits(
                "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission",
                FAMILIES[
                    "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission"
                ][1],
                no_oi_confirmation,
            )
        )

    def test_equal_capital_economics_and_output_contract_remain_frozen(self) -> None:
        value = manifest("dra-binance-usdm-positioning-divergence-entry-admission")
        value["economics"]["fee_rate"] = "0.0009"
        with self.assertRaisesRegex(runner.ScreenReject, "equal-capital parent"):
            runner.validate_manifest(value)

        engine = runner.ExternalEntryAdmissionEngine(
            family_key="dra-binance-usdm-positioning-divergence-entry-admission",
            thresholds={"absolute_positioning_gap_at_or_above": "0.20"},
            external_days=[],
        )
        self.assertEqual(engine.initial_equity, D("250.00"))
        self.assertEqual(engine.cap, D("240.00"))
        self.assertEqual(runner.base.FEE, D("0.0010"))
        self.assertEqual(runner.base.SLIPPAGE, D("0.0005"))
        bar = hourly_bar(datetime(2024, 1, 2))
        engine._track(bar)
        result = engine.result(bar, bar.open_time, bar.close_time)
        self.assertTrue(set(runner.REQUIRED_ECONOMIC_OUTPUTS).issuperset({
            "fees_paid_usdt", "adverse_slippage_cost_usdt", "inventory_path", "holding"
        }))
        self.assertIn("realized_usdt", result)
        self.assertIn("unrealized_usdt", result)
        self.assertIn("total_pnl_usdt", result)
        self.assertIn("max_drawdown_pct", result)
        self.assertIn("inventory_path", result)
        self.assertIn("holding", result)
        self.assertIn("interventions", result)
        self.assertEqual(result["fee_rate"], "0.0010")
        self.assertEqual(result["adverse_slippage_rate"], "0.0005")


if __name__ == "__main__":
    unittest.main()
