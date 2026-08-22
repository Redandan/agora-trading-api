from __future__ import annotations

from datetime import date, datetime, timedelta
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
import dra_corwin_schultz_spread_support_v2 as spread_support


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
            "lookback_complete_days": contract.get("lookback_complete_days", 20),
            "relation": contract["relation"],
        },
        "gate_set": contract.get("gate_set", runner.GATE_SET_V1),
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


def bar(
    opened: datetime,
    *,
    open_price: str | None = None,
    close: str = "100",
    high: str | None = None,
    low: str | None = None,
    volume: str = "1",
) -> runner.base.Bar:
    price = D(close)
    opened_price = D(open_price) if open_price is not None else price
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=opened_price,
        high=D(high) if high is not None else max(opened_price, price),
        low=D(low) if low is not None else min(opened_price, price),
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

    def test_realized_volatility_term_structure_uses_exact_five_and_twenty_days(self) -> None:
        prior = [D("1")] * 15 + [D("4")] * 4
        ratio, long_realized_volatility = runner.realized_volatility_term_structure(
            prior, D("4")
        )
        self.assertEqual(long_realized_volatility, D("35").sqrt())
        self.assertEqual(ratio, D("20").sqrt() / D("35").sqrt())
        with self.assertRaisesRegex(runner.ScreenReject, "19 prior"):
            runner.realized_volatility_term_structure(prior[:-1], D("4"))

    def test_realized_volatility_term_structure_is_v2_and_fails_closed_until_warm(self) -> None:
        feature = "DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN"
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        close = D("100")
        for day in range(40):
            for hour in range(24):
                close *= D("1.001") if hour % 2 == 0 else D("0.999")
                engine._update_feature(
                    bar(
                        opened + timedelta(days=day, hours=hour),
                        close=str(close),
                    )
                )
            if day < 19:
                self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(len(engine.rv_term_structure_observations), 21)
        self.assertIsNotNone(engine.current_feature_ratio)

    def test_spearman_correlation_detects_duplicate_ordering(self) -> None:
        ascending = [D(value) for value in range(1, 7)]
        mixed = [D(value) for value in (1, 4, 2, 6, 3, 5)]
        self.assertEqual(runner.spearman_correlation(ascending, ascending), D("1"))
        self.assertLess(abs(runner.spearman_correlation(ascending, mixed)), D("0.80"))

    def test_weekend_calendar_feature_requires_zero_lookback_and_v2_gates(self) -> None:
        value = manifest(
            feature="LATEST_COMPLETE_UTC_DAY_WEEKDAY_INDEX_MONDAY_ZERO"
        )
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["feature"]["lookback_complete_days"], 0)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

        value["feature"]["lookback_complete_days"] = 20
        with self.assertRaisesRegex(runner.ScreenReject, "lookback must be 0"):
            runner.validate_manifest(value)

    def test_weekend_calendar_feature_applies_nested_weekday_thresholds(self) -> None:
        def completed_day(
            day: datetime, threshold: str
        ) -> runner.DeclarativeEntryAdmissionEngine:
            engine = runner.DeclarativeEntryAdmissionEngine(
                feature_key="LATEST_COMPLETE_UTC_DAY_WEEKDAY_INDEX_MONDAY_ZERO",
                relation="AT_OR_BELOW",
                threshold=D(threshold),
            )
            for hour in range(24):
                engine._update_feature(bar(day + timedelta(hours=hour)))
            return engine

        friday = completed_day(datetime(2024, 1, 5), "4")
        saturday_primary = completed_day(datetime(2024, 1, 6), "4")
        saturday_upper = completed_day(datetime(2024, 1, 6), "5")
        sunday_upper = completed_day(datetime(2024, 1, 7), "5")
        self.assertEqual(friday.current_feature_ratio, D("4.00000000"))
        self.assertEqual(saturday_primary.current_feature_ratio, D("5.00000000"))
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine,
            "_signal",
            return_value=True,
        ):
            self.assertTrue(friday._signal(bar(datetime(2024, 1, 5, 23))))
            self.assertFalse(
                saturday_primary._signal(bar(datetime(2024, 1, 6, 23)))
            )
            self.assertTrue(saturday_upper._signal(bar(datetime(2024, 1, 6, 23))))
            self.assertFalse(sunday_upper._signal(bar(datetime(2024, 1, 7, 23))))

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

    def test_amihud_illiquidity_uses_hourly_open_close_return_and_dollar_volume(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_AMIHUD_ILLIQUIDITY_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    open_price="100",
                    close="110",
                    volume="2",
                )
            )
        expected_hourly = D("0.1") / D("220")
        self.assertLess(abs(engine._daily_value() - expected_hourly), D("1e-33"))

    def test_amihud_illiquidity_fails_closed_on_zero_volume_hour(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_AMIHUD_ILLIQUIDITY_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(23):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    open_price="100",
                    close="101",
                    volume="0" if hour == 7 else "2",
                )
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive-volume"):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=23),
                    open_price="100",
                    close="101",
                    volume="2",
                )
            )

    def test_corwin_schultz_formula_matches_frozen_support_runner(self) -> None:
        previous = spread_support.DailyObservation(
            day=date(2024, 1, 1),
            open=D("100"),
            high=D("110"),
            low=D("100"),
            close=D("105"),
            hourly_closes=tuple(D("105") for _ in range(24)),
            quote_volume_proxy=D("1000000"),
        )
        current = spread_support.DailyObservation(
            day=date(2024, 1, 2),
            open=D("102"),
            high=D("112"),
            low=D("101"),
            close=D("108"),
            hourly_closes=tuple(D("108") for _ in range(24)),
            quote_volume_proxy=D("1000000"),
        )
        self.assertEqual(
            runner.corwin_schultz_spread(
                previous.high, previous.low, current.high, current.low
            ),
            spread_support.corwin_schultz_spread(previous, current),
        )

    def test_corwin_schultz_feature_uses_prior_day_and_prior_twenty_spreads(self) -> None:
        feature = "DAILY_CORWIN_SCHULTZ_SPREAD_TO_PRIOR_20D_MEDIAN"
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for day_index in range(22):
            for hour in range(24):
                engine._update_feature(
                    bar(
                        opened + timedelta(days=day_index, hours=hour),
                        open_price="100",
                        close="105",
                        high="110",
                        low="100",
                    )
                )
            if day_index < 21:
                self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(engine.current_feature_ratio, D("1.00000000"))
        self.assertEqual(engine.complete_feature_days, 22)

    def test_realized_to_bipower_ratio_uses_adjacent_absolute_returns(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_REALIZED_TO_BIPOWER_VARIATION_RATIO_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        engine.previous_hour_close = D("100")
        opened = datetime(2024, 1, 1)
        close = D("100")
        for hour in range(24):
            close *= D("1.1") if hour % 2 == 0 else D("0.9")
            engine._update_feature(bar(opened + timedelta(hours=hour), close=str(close)))
        expected_rv = D("24") * D("0.1") * D("0.1")
        expected_bv = runner.PI_OVER_TWO * D("23") * D("0.1") * D("0.1")
        self.assertLess(
            abs(engine._daily_value() - (expected_rv / expected_bv)),
            D("1e-30"),
        )

    def test_realized_to_bipower_ratio_fails_closed_without_adjacent_variation(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_REALIZED_TO_BIPOWER_VARIATION_RATIO_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        engine.previous_hour_close = D("100")
        opened = datetime(2024, 1, 1)
        engine._update_feature(bar(opened, close="110"))
        for hour in range(1, 23):
            engine._update_feature(bar(opened + timedelta(hours=hour), close="110"))
        with self.assertRaisesRegex(runner.ScreenReject, "positive daily variation"):
            engine._update_feature(bar(opened + timedelta(hours=23), close="110"))

    def test_intraday_sign_persistence_uses_only_returns_inside_complete_day(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_INTRADAY_SIGN_PERSISTENCE_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        engine.previous_hour_close = D("50")
        opened = datetime(2024, 1, 1)
        close = D("100")
        engine._update_feature(bar(opened, close=str(close)))
        for hour in range(1, 24):
            close *= D("1.01") if hour <= 12 else D("0.99")
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close=str(close))
            )
        self.assertEqual(engine.daily_sign_pair_count, 22)
        self.assertEqual(engine.daily_sign_persistence_pair_count, 21)
        self.assertEqual(engine._daily_value(), D("21") / D("22"))

    def test_intraday_sign_persistence_keeps_zero_return_pairs_in_denominator(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_INTRADAY_SIGN_PERSISTENCE_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close="100")
            )
        self.assertEqual(engine.daily_sign_pair_count, 22)
        self.assertEqual(engine.daily_sign_persistence_pair_count, 0)
        self.assertEqual(engine._daily_value(), D("0"))

    def test_directional_volume_participation_uses_close_weighted_base_volume(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        engine._update_feature(
            bar(opened, open_price="100", close="110", volume="2")
        )
        engine._update_feature(
            bar(opened + timedelta(hours=1), open_price="100", close="90", volume="2")
        )
        for hour in range(2, 24):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    open_price="100",
                    close="100",
                    volume="0",
                )
            )
        self.assertEqual(engine.daily_positive_return_quote_volume, D("220"))
        self.assertEqual(engine.daily_total_quote_volume, D("400"))
        self.assertEqual(engine._daily_value(), D("0.55"))

    def test_directional_volume_participation_allows_zero_volume_hours(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    open_price="100",
                    close="101",
                    volume="1" if hour == 7 else "0",
                )
            )
        self.assertEqual(engine._daily_value(), D("1"))

    def test_directional_volume_participation_fails_closed_without_daily_volume(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(23):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), volume="0")
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive daily quote volume"):
            engine._update_feature(
                bar(opened + timedelta(hours=23), volume="0")
            )

    def test_intraday_volume_concentration_uses_quote_volume_herfindahl(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_QUOTE_VOLUME_HERFINDAHL_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close="100", volume="1")
            )
        self.assertEqual(engine.daily_total_quote_volume, D("2400"))
        self.assertEqual(engine.daily_quote_volume_square_sum, D("240000"))
        self.assertEqual(engine._daily_value(), D("1") / D("24"))

    def test_intraday_volume_concentration_keeps_zero_volume_hours(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_QUOTE_VOLUME_HERFINDAHL_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    close="100",
                    volume="1" if hour == 7 else "0",
                )
            )
        self.assertEqual(engine._daily_value(), D("1"))

    def test_intraday_volume_concentration_fails_closed_without_daily_volume(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_QUOTE_VOLUME_HERFINDAHL_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(23):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), volume="0")
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive daily quote volume"):
            engine._update_feature(
                bar(opened + timedelta(hours=23), volume="0")
            )

    def test_fixed_utc_traditional_session_share_uses_only_six_whole_h1_bars(self) -> None:
        feature = "DAILY_1500_2059_UTC_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN"
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(
                    opened + timedelta(hours=hour),
                    close="100",
                    volume="2" if 15 <= hour <= 20 else "1",
                )
            )
        self.assertEqual(engine.daily_1500_2059_utc_quote_volume, D("1200"))
        self.assertEqual(engine.daily_total_quote_volume, D("3000"))
        self.assertEqual(engine._daily_value(), D("0.4"))

    def test_fixed_utc_traditional_session_share_is_causal_and_uses_v2_gates(self) -> None:
        feature = "DAILY_1500_2059_UTC_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN"
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["feature"]["lookback_complete_days"], 20)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for day in range(21):
            for hour in range(24):
                session_volume = "2" if day == 20 and 15 <= hour <= 20 else "1"
                engine._update_feature(
                    bar(
                        opened + timedelta(days=day, hours=hour),
                        close="100",
                        volume=session_volume,
                    )
                )
                if day == 20 and hour == 22:
                    self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(engine.current_feature_ratio, D("1.60000000"))

    def test_close_location_uses_final_close_inside_complete_day_range(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(24):
            close = "108" if hour == 23 else ("90" if hour == 7 else "110")
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close=close)
            )
        self.assertEqual(engine.feature_low, D("90"))
        self.assertEqual(engine.feature_high, D("110"))
        self.assertEqual(engine.feature_close, D("108"))
        self.assertEqual(engine._daily_value(), D("0.9"))

    def test_close_location_requires_positive_complete_day_range(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(23):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close="100")
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive complete-day range"):
            engine._update_feature(
                bar(opened + timedelta(hours=23), close="100")
            )

    def test_close_location_requires_v2_gate_set(self) -> None:
        value = manifest(feature="DAILY_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN")
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)
        value["gate_set"] = runner.GATE_SET_V1
        with self.assertRaisesRegex(runner.ScreenReject, "does not bind"):
            runner.validate_manifest(value)

    def test_h1_volume_weighted_close_location_uses_base_volume_weights(self) -> None:
        feature = "DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN"
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        engine._update_feature(bar(opened, close="100", volume="1"))
        engine._update_feature(
            bar(opened + timedelta(hours=1), close="110", volume="3")
        )
        for hour in range(2, 23):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close="100", volume="0")
            )
        engine._update_feature(
            bar(opened + timedelta(hours=23), close="105", volume="0")
        )
        self.assertEqual(engine.feature_volume, D("4"))
        self.assertEqual(engine.daily_total_quote_volume, D("430"))
        self.assertEqual(engine.feature_close, D("105"))
        self.assertEqual(engine._daily_value(), D("420") / D("430"))

    def test_h1_volume_weighted_close_location_fails_without_daily_volume(self) -> None:
        feature = "DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN"
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        opened = datetime(2024, 1, 1)
        for hour in range(23):
            engine._update_feature(
                bar(opened + timedelta(hours=hour), close="100", volume="0")
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive daily base"):
            engine._update_feature(
                bar(opened + timedelta(hours=23), close="100", volume="0")
            )

    def test_h1_volume_weighted_close_location_requires_v2_gate_set(self) -> None:
        feature = "DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN"
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

    def test_realized_performance_root_reconciles_and_preserves_mean_sign(self) -> None:
        positive = [D("0.01")] * 13 + [D("-0.01")] * 11
        root = runner.realized_performance(positive)
        self.assertGreater(root, 0)
        with runner.localcontext() as context:
            context.prec = 50
            moment = sum((-root * value).exp() for value in positive) / D("24")
        self.assertLess(abs(moment - D("1")), D("1e-45"))
        negative_root = runner.realized_performance([-value for value in positive])
        self.assertEqual(
            negative_root.quantize(D("1e-24")),
            (-root).quantize(D("1e-24")),
        )

    def test_realized_performance_percentile_uses_midrank_and_twenty_days(self) -> None:
        prior = [D(value) for value in range(20)]
        self.assertEqual(runner.prior_percentile(D("10"), prior), D("0.525"))
        with self.assertRaisesRegex(runner.ScreenReject, "20 prior days"):
            runner.prior_percentile(D("1"), prior[:-1])

    def test_realized_performance_fails_closed_without_two_sided_intraday_path(self) -> None:
        with self.assertRaisesRegex(runner.ScreenReject, "positive and negative"):
            runner.realized_performance([D("0.01")] * 24)

    def test_late_day_price_activity_is_exact_final_six_hour_share_and_v2(self) -> None:
        feature = (
            "DAILY_1800_2359_UTC_ABSOLUTE_LOG_RETURN_SHARE_TO_PRIOR_20D_MEDIAN"
        )
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertEqual(value["feature"]["lookback_complete_days"], 20)
        self.assertIs(runner.validate_manifest(value), value)

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        engine.daily_bar_count = 24
        engine.daily_intraday_log_returns = [D("0.01")] * 18 + [D("0.02")] * 6
        self.assertEqual(engine._daily_value(), D("0.4"))

        engine.daily_intraday_log_returns = [D("0")] * 24
        self.assertIsNone(engine._daily_value())

    def test_intraday_close_path_drawdown_uses_ordered_peak_to_later_close_and_v2(self) -> None:
        feature = "DAILY_H1_CLOSE_PATH_MAX_DRAWDOWN_TO_PRIOR_20D_MEDIAN"
        value = manifest(feature=feature)
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertEqual(value["feature"]["relation"], "AT_OR_BELOW")
        self.assertIs(runner.validate_manifest(value), value)

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_BELOW",
            threshold=D("1"),
        )
        engine.feature_open = D("100")
        engine.daily_bar_count = 24
        engine.daily_intraday_closes = [D("100"), D("110"), D("99")] + [D("120")] * 21
        self.assertEqual(engine._daily_value(), D("0.1"))

    def test_realized_performance_feature_requires_v2_gate_set(self) -> None:
        feature = "DAILY_REALIZED_PERFORMANCE_PRIOR_20D_PERCENTILE"
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.4", "variant_id": "lower-rp-v1"},
            {"role": "primary", "threshold": "0.5", "variant_id": "primary-rp-v1"},
            {"role": "upper_neighbor", "threshold": "0.6", "variant_id": "upper-rp-v1"},
        ]
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)
        value["gate_set"] = runner.GATE_SET_V1
        with self.assertRaisesRegex(runner.ScreenReject, "does not bind"):
            runner.validate_manifest(value)

    def test_h1_lag1_return_autocorrelation_preserves_sequence_order(self) -> None:
        increasing = [D(value) for value in range(1, 25)]
        alternating = [D("1") if index % 2 == 0 else D("-1") for index in range(24)]
        self.assertEqual(runner.lag1_return_autocorrelation(increasing), D("1"))
        self.assertEqual(runner.lag1_return_autocorrelation(alternating), D("-1"))

    def test_h1_lag1_return_autocorrelation_fails_closed_without_variation(self) -> None:
        with self.assertRaisesRegex(runner.ScreenReject, "non-zero variation"):
            runner.lag1_return_autocorrelation([D("0.01")] * 24)

    def test_h1_lag1_return_autocorrelation_feature_requires_v2_gate_set(self) -> None:
        feature = "DAILY_H1_LAG1_RETURN_AUTOCORRELATION_PRIOR_20D_PERCENTILE"
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.4", "variant_id": "lower-ac-v1"},
            {"role": "primary", "threshold": "0.5", "variant_id": "primary-ac-v1"},
            {"role": "upper_neighbor", "threshold": "0.6", "variant_id": "upper-ac-v1"},
        ]
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

    def test_intraday_price_path_efficiency_separates_smooth_and_cancelled_paths(self) -> None:
        smooth = [D("0.01")] * 24
        cancelled = [D("0.01")] * 12 + [D("-0.01")] * 12
        self.assertEqual(runner.intraday_price_path_efficiency(smooth), D("1"))
        self.assertEqual(runner.intraday_price_path_efficiency(cancelled), D("0"))

    def test_intraday_price_path_efficiency_fails_closed_on_flat_day(self) -> None:
        with self.assertRaisesRegex(runner.ScreenReject, "positive gross price path"):
            runner.intraday_price_path_efficiency([D("0")] * 24)

    def test_intraday_price_path_efficiency_feature_requires_v2_gate_set(self) -> None:
        feature = "DAILY_INTRADAY_PRICE_PATH_EFFICIENCY_PRIOR_20D_PERCENTILE"
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.4", "variant_id": "lower-pe-v1"},
            {"role": "primary", "threshold": "0.5", "variant_id": "primary-pe-v1"},
            {"role": "upper_neighbor", "threshold": "0.6", "variant_id": "upper-pe-v1"},
        ]
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIs(runner.validate_manifest(value), value)

    def test_h1_absolute_return_log_volume_correlation_preserves_direction(self) -> None:
        absolute_returns = [D(index) / D("1000") for index in range(1, 25)]
        increasing_volumes = [D(index).exp() for index in range(1, 25)]
        decreasing_volumes = list(reversed(increasing_volumes))
        self.assertEqual(
            runner.absolute_return_log_volume_correlation(
                absolute_returns, increasing_volumes
            ).quantize(D("0.00000001")),
            D("1.00000000"),
        )
        self.assertEqual(
            runner.absolute_return_log_volume_correlation(
                absolute_returns, decreasing_volumes
            ).quantize(D("0.00000001")),
            D("-1.00000000"),
        )

    def test_h1_absolute_return_log_volume_correlation_fails_closed(self) -> None:
        with self.assertRaisesRegex(runner.ScreenReject, "exactly 24"):
            runner.absolute_return_log_volume_correlation(
                [D("0.01")] * 23,
                [D("100")] * 23,
            )
        with self.assertRaisesRegex(runner.ScreenReject, "positive"):
            runner.absolute_return_log_volume_correlation(
                [D(index) / D("1000") for index in range(1, 25)],
                [D("100")] * 23 + [D("0")],
            )
        with self.assertRaisesRegex(runner.ScreenReject, "non-zero variation"):
            runner.absolute_return_log_volume_correlation(
                [D("0.01")] * 24,
                [D(index).exp() for index in range(1, 25)],
            )

    def test_h1_absolute_return_volume_coupling_feature_is_percentile_v2(self) -> None:
        feature = (
            "DAILY_H1_ABSOLUTE_LOG_RETURN_TO_LOG_CLOSE_WEIGHTED_VOLUME_"
            "CORRELATION_PRIOR_20D_PERCENTILE"
        )
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.30", "variant_id": "lower-mv-v1"},
            {"role": "primary", "threshold": "0.50", "variant_id": "primary-mv-v1"},
            {"role": "upper_neighbor", "threshold": "0.70", "variant_id": "upper-mv-v1"},
        ]
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["feature"]["relation"], "AT_OR_ABOVE")
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIn(feature, runner.PERCENTILE_FEATURES)
        self.assertIs(runner.validate_manifest(value), value)

        log_returns = [D(index) / D("1000") for index in range(1, 25)]
        volumes = [D(index).exp() for index in range(1, 25)]
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_ABOVE",
            threshold=D("0.50"),
        )
        engine.daily_intraday_log_returns = log_returns
        engine.daily_intraday_close_weighted_volumes = volumes
        self.assertEqual(
            runner.absolute_return_log_volume_correlation(log_returns, volumes),
            engine._daily_value(),
        )

        engine.daily_intraday_close_weighted_volumes[-1] = D("0")
        self.assertIsNone(engine._daily_value())

        engine.daily_intraday_log_returns = [D("0.01")] * 24
        engine.daily_intraday_close_weighted_volumes = volumes
        self.assertIsNone(engine._daily_value())

    def test_realized_skewness_preserves_sign_and_scale(self) -> None:
        positive = [D("-0.01")] * 12 + [D("0.01")] * 11 + [D("0.08")]
        negative = [-value for value in positive]
        scaled = [value * D("2") for value in positive]
        positive_value = runner.realized_skewness(positive)
        self.assertGreater(positive_value, 0)
        self.assertEqual(
            (-positive_value).quantize(D("0.00000001")),
            runner.realized_skewness(negative).quantize(D("0.00000001")),
        )
        self.assertEqual(
            positive_value.quantize(D("0.00000001")),
            runner.realized_skewness(scaled).quantize(D("0.00000001")),
        )

    def test_realized_skewness_fails_closed_without_complete_variation(self) -> None:
        with self.assertRaisesRegex(runner.ScreenReject, "exactly 24"):
            runner.realized_skewness([D("0.01")] * 23)
        with self.assertRaisesRegex(runner.ScreenReject, "positive realized variance"):
            runner.realized_skewness([D("0")] * 24)

    def test_realized_skewness_feature_is_percentile_based_and_v2(self) -> None:
        feature = "DAILY_INTRADAY_REALIZED_SKEWNESS_PRIOR_20D_PERCENTILE"
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.3", "variant_id": "lower-sk-v1"},
            {"role": "primary", "threshold": "0.5", "variant_id": "primary-sk-v1"},
            {"role": "upper_neighbor", "threshold": "0.7", "variant_id": "upper-sk-v1"},
        ]
        self.assertEqual(value["feature"]["relation"], "AT_OR_BELOW")
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIn(feature, runner.PERCENTILE_FEATURES)
        self.assertIs(runner.validate_manifest(value), value)

        log_returns = [D("-0.01")] * 12 + [D("0.01")] * 11 + [D("0.08")]
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key=feature,
            relation="AT_OR_BELOW",
            threshold=D("0.5"),
        )
        engine.daily_intraday_log_returns = log_returns
        self.assertEqual(runner.realized_skewness(log_returns), engine._daily_value())

    def test_h1_first_extreme_order_feature_is_direct_binary_and_v2(self) -> None:
        feature = "DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY"
        value = manifest(feature=feature)
        value["variants"] = [
            {
                "role": "primary",
                "threshold": "1",
                "variant_id": "h1-first-low-before-high-v1",
            }
        ]
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["feature"]["lookback_complete_days"], 0)
        self.assertEqual(value["feature"]["relation"], "AT_OR_ABOVE")
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIn(feature, runner.DIRECT_FEATURES)
        self.assertIs(runner.validate_manifest(value), value)

    def test_h1_first_extreme_order_distinguishes_low_before_high(self) -> None:
        feature = "DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY"
        start = datetime(2024, 1, 1)
        outcomes = []
        for low_hour, high_hour in ((4, 18), (18, 4)):
            engine = runner.DeclarativeEntryAdmissionEngine(
                feature_key=feature,
                relation="AT_OR_ABOVE",
                threshold=D("1"),
            )
            for hour in range(24):
                engine._update_feature(
                    bar(
                        start + timedelta(hours=hour),
                        high="110" if hour == high_hour else "100",
                        low="90" if hour == low_hour else "100",
                    )
                )
            outcomes.append(engine.current_feature_ratio)
        self.assertEqual(outcomes, [D("1.00000000"), D("0E-8")])

    def test_h1_first_extreme_order_fails_closed_when_same_bar_has_both(self) -> None:
        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY",
            relation="AT_OR_ABOVE",
            threshold=D("1"),
        )
        start = datetime(2024, 1, 1)
        for hour in range(24):
            engine._update_feature(
                bar(
                    start + timedelta(hours=hour),
                    high="110" if hour == 12 else "100",
                    low="90" if hour == 12 else "100",
                )
            )
        self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(engine.complete_feature_days, 1)

    def test_90d_drawdown_recovery_feature_is_direct_point_in_time_and_v2(self) -> None:
        feature = "PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION"
        value = manifest(feature=feature)
        value["variants"] = [
            {"role": "lower_neighbor", "threshold": "0.40", "variant_id": "recovery-040-v1"},
            {"role": "primary", "threshold": "0.50", "variant_id": "recovery-050-v1"},
            {"role": "upper_neighbor", "threshold": "0.60", "variant_id": "recovery-060-v1"},
        ]
        schema = json.loads(
            (
                ROOT
                / "research_pipeline"
                / "dra-declarative-entry-admission-manifest.v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator(schema).validate(value)
        self.assertEqual(value["feature"]["lookback_complete_days"], 90)
        self.assertEqual(value["feature"]["relation"], "AT_OR_ABOVE")
        self.assertEqual(value["gate_set"], runner.GATE_SET_V2)
        self.assertIn(feature, runner.DIRECT_FEATURES)
        self.assertIs(runner.validate_manifest(value), value)

    def test_90d_drawdown_recovery_uses_first_peak_and_complete_day_close(self) -> None:
        days = [(D("110"), D("90"), D("100")) for _ in range(90)]
        days[10] = (D("120"), D("110"), D("115"))
        days[20] = (D("115"), D("80"), D("85"))
        days[50] = (D("120"), D("95"), D("110"))
        days[-1] = (D("110"), D("90"), D("100"))
        self.assertEqual(runner.drawdown_recovery_fraction(days), D("0.5"))
        self.assertIsNone(runner.drawdown_recovery_fraction(days[:-1]))

        engine = runner.DeclarativeEntryAdmissionEngine(
            feature_key="PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION",
            relation="AT_OR_ABOVE",
            threshold=D("0.50"),
        )
        start = datetime(2024, 1, 1)
        for day in range(90):
            day_high, day_low, day_close = days[day]
            for hour in range(24):
                engine._update_feature(
                    bar(
                        start + timedelta(days=day, hours=hour),
                        close=str(day_close),
                        high=str(day_high),
                        low=str(day_low),
                    )
                )
                if day == 89 and hour == 22:
                    self.assertIsNone(engine.current_feature_ratio)
        self.assertEqual(engine.current_feature_ratio, D("0.50000000"))
        self.assertEqual(engine.complete_feature_days, 90)


    def test_v2_primary_gate_fails_on_worse_underwater_duration(self) -> None:
        parent_design = {
            "total_pnl_usdt": "1",
            "realized_usdt": "1",
            "unrealized_usdt": "0",
            "max_drawdown_pct": "1",
            "median_hold_hours": 10,
            "p90_hold_hours": 20,
            "inventory_path": {"maximum_underwater_duration_hours": 10},
            "terminal_inventory": [{}],
        }
        parent_validation = dict(parent_design)
        candidate_design = {
            **parent_design,
            "total_pnl_usdt": "2",
            "realized_usdt": "2",
            "vetoed_signal_count": 8,
            "inventory_path": {"maximum_underwater_duration_hours": 9},
        }
        candidate_validation = {
            **parent_validation,
            "total_pnl_usdt": "2",
            "realized_usdt": "2",
            "vetoed_signal_count": 4,
            "inventory_path": {"maximum_underwater_duration_hours": 11},
        }
        variant = {
            "design": candidate_design,
            "validation": candidate_validation,
            "annual_total_wins": 3,
            "annual_drawdown_non_worse": 4,
            "top_year_positive_delta_contribution_pct": "50",
        }
        checks = runner.primary_gates(
            variant,
            {"design": parent_design, "validation": parent_validation},
            gate_set=runner.GATE_SET_V2,
        )
        self.assertFalse(checks["validation_max_underwater_duration_non_worse"])
        self.assertTrue(checks["validation_realized_pnl_improves"])

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
