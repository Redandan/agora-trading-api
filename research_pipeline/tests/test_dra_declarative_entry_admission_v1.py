from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_declarative_entry_admission_v1 as runner


D = Decimal


def manifest(*, feature: str = "LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN") -> dict:
    contract = runner.FEATURES[feature]
    return {
        "authorization": runner.AUTHORIZATION,
        "dataset": {
            "canonical_sha256": runner.base.SELECTION_SHA256,
            "rows": runner.base.SELECTION_ROWS,
        },
        "document_type": runner.DOCUMENT_TYPE,
        "economics": {
            "fee_rate": "0.0010",
            "initial_equity_usdt": "250",
            "slippage_rate": "0.0005",
            "slot_capacity_usdt": "240",
        },
        "experiment_id": "synthetic-declarative-dra-screen-v1",
        "feature": {
            "decision_time": "LATEST_COMPLETE_UTC_DAY_BEFORE_NEXT_BAR_FILL",
            "key": feature,
            "lookback_complete_days": 20,
            "relation": contract["relation"],
        },
        "gate_set": runner.GATE_SET,
        "oos_access": "DENY",
        "parent_strategy": runner.PARENT_STRATEGY,
        "prior_evidence": {
            "disposition": contract["prior_disposition"],
            "path": "research_pipeline/examples/synthetic-prior.json",
            "sha256": "0" * 64,
        },
        "schema_version": "1",
        "selection_cutoff": runner.SELECTION_CUTOFF,
        "variants": [
            {"role": "lower_neighbor", "threshold": "0.8", "variant_id": "lower-v1"},
            {"role": "primary", "threshold": "1.0", "variant_id": "primary-v1"},
            {"role": "upper_neighbor", "threshold": "1.2", "variant_id": "upper-v1"},
        ],
    }


def bar(opened: datetime, *, close: str = "100", volume: str = "1") -> runner.base.Bar:
    price = D(close)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=price,
        high=price,
        low=price,
        close=price,
        volume=D(volume),
    )


class DeclarativeDraEntryAdmissionRunnerTest(unittest.TestCase):
    def test_manifest_schema_and_manual_contract_accept_three_frozen_variants(self) -> None:
        value = manifest()
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(
            schema,
            format_checker=FormatChecker(),
        ).validate(value)
        self.assertIs(runner.validate_manifest(value), value)

    def test_manifest_rejects_wrong_relation_and_more_than_three_variants(self) -> None:
        wrong_relation = manifest()
        wrong_relation["feature"]["relation"] = "AT_OR_ABOVE"
        with self.assertRaisesRegex(runner.ScreenReject, "relation"):
            runner.validate_manifest(wrong_relation)

        too_many = manifest()
        too_many["variants"].append(
            {"role": "primary", "threshold": "1.4", "variant_id": "fourth-v1"}
        )
        with self.assertRaisesRegex(runner.ScreenReject, "one to three"):
            runner.validate_manifest(too_many)

    def test_manifest_requires_canonical_bytes(self) -> None:
        value = manifest()
        with TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(value, indent=2), encoding="utf-8")
            with self.assertRaisesRegex(runner.ScreenReject, "canonical"):
                runner.load_manifest(path)

    def test_daily_feature_uses_only_complete_days_and_prior_twenty_day_median(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_VOLUME_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        start = datetime(2024, 1, 1)
        for day in range(21):
            for hour in range(24):
                volume = "2" if day == 20 else "1"
                engine._update_feature(
                    bar(start + timedelta(days=day, hours=hour), volume=volume)
                )
                if day == 20 and hour == 22:
                    self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(engine.current_feature_ratio, D("2.00000000"))
        self.assertEqual(engine.complete_feature_days, 21)

    def test_downside_semivariance_share_preserves_return_sign(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_DOWNSIDE_SEMIVARIANCE_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        engine.previous_hour_close = D("100")
        engine._update_feature(bar(datetime(2024, 1, 1), close="90"))
        engine._update_feature(bar(datetime(2024, 1, 1, 1), close="99"))
        self.assertEqual(engine.daily_squared_return_sum, D("0.02"))
        self.assertEqual(engine.daily_downside_squared_return_sum, D("0.01"))
        self.assertEqual(engine._daily_value(), D("0.5"))

    def test_manifest_binds_prior_disposition_to_feature(self) -> None:
        value = manifest(
            feature="DAILY_DOWNSIDE_SEMIVARIANCE_SHARE_TO_PRIOR_20D_MEDIAN"
        )
        self.assertIs(runner.validate_manifest(value), value)
        value["prior_evidence"]["disposition"] = (
            "PRIOR_SUPPORTS_ONE_VOLATILITY_MANAGEMENT_DESIGN_AUDIT"
        )
        with self.assertRaisesRegex(runner.ScreenReject, "does not bind"):
            runner.validate_manifest(value)

    def test_prior_identity_is_bound_to_feature_family(self) -> None:
        value = manifest(
            feature="DAILY_DOWNSIDE_SEMIVARIANCE_SHARE_TO_PRIOR_20D_MEDIAN"
        )
        prior = {
            "authorization": runner.AUTHORIZATION,
            "disposition": value["prior_evidence"]["disposition"],
            "document_type": "DRA_DOWNSIDE_SEMIVARIANCE_PRIMARY_PRIOR_V1",
        }
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "prior.json"
            path.write_text(json.dumps(prior), encoding="utf-8")
            value["prior_evidence"].update(
                {
                    "path": "prior.json",
                    "sha256": runner.sha256_path(path),
                }
            )
            with patch.object(runner, "REPOSITORY_ROOT", root):
                self.assertEqual(
                    runner.verify_prior_evidence(value)["sha256"],
                    value["prior_evidence"]["sha256"],
                )

    def test_signal_fails_closed_when_feature_unavailable(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        one_bar = bar(datetime(2024, 1, 1, 23))
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertFalse(engine._signal(one_bar))
        self.assertEqual(engine.parent_signal_count, 1)
        self.assertEqual(engine.feature_unavailable_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 1)

    def test_relation_controls_admission_without_changing_parent(self) -> None:
        one_bar = bar(datetime(2024, 1, 1, 23))
        low_vol = runner.DeclarativeEntryAdmissionEngine(
            feature_key="LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        low_vol.current_feature_ratio = D("0.9")
        high_volume = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_VOLUME_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        high_volume.current_feature_ratio = D("1.1")
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertTrue(low_vol._signal(one_bar))
            self.assertTrue(high_volume._signal(one_bar))

    def test_result_contains_full_equal_capital_path_ledger_without_gate(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        one_bar = bar(datetime(2024, 1, 1, 23))
        engine._track(one_bar)
        result = engine.result(one_bar, one_bar.open_time, one_bar.close_time)
        self.assertEqual(result["initial_equity_usdt"], "250.00000000")
        self.assertEqual(result["slot_capacity_usdt"], "240.00000000")
        self.assertIn("realized_usdt", result)
        self.assertIn("unrealized_usdt", result)
        self.assertIn("total_pnl_usdt", result)
        self.assertIn("max_drawdown_pct", result)
        self.assertIn("inventory_path", result)
        self.assertIn("realized_lot_ledger", result)
        self.assertIn("terminal_inventory", result)
        self.assertNotIn("pass", result)

    def test_selection_rejects_post_cutoff_data(self) -> None:
        bars = [
            bar(datetime(2025, 1, 1), close="100"),
        ]
        value = manifest()
        value["dataset"] = {
            "canonical_sha256": runner.base.data_hash(bars),
            "rows": 1,
        }
        with TemporaryDirectory() as directory:
            path = Path(directory) / "selection.tsv"
            path.write_text(bars[0].canonical() + "\n", encoding="utf-8")
            with self.assertRaisesRegex(runner.ScreenReject, "cutoff"):
                runner.load_selection(path, value)


if __name__ == "__main__":
    unittest.main()
