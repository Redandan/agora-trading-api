from __future__ import annotations

from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import re
import unittest


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = (
    PACKAGE_ROOT / "okx-microstructure-intraday-economic-route-contract.v1.json"
)
SCHEMA_PATH = (
    PACKAGE_ROOT
    / "okx-microstructure-intraday-economic-route-contract.v1.schema.json"
)

EXACT_FILES = [
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.json",
    "research_pipeline/okx-microstructure-intraday-economic-route-contract.v1.schema.json",
    "research_pipeline/tests/test_microstructure_intraday_economic_route_contract.py",
    "docs/okx-microstructure-intraday-economic-route-v1.md",
]
TIER_ORDER = [
    "MIDLINE_RATIO_1_5_ONLY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT",
]


class SchemaValidationError(AssertionError):
    pass


def _validate_schema_instance(
    value: object, schema: dict[str, object], path: str = "$"
) -> None:
    if "const" in schema and value != schema["const"]:
        raise SchemaValidationError(f"{path}: value differs from frozen const")

    expected_type = schema.get("type")
    if expected_type == "object" and not isinstance(value, dict):
        raise SchemaValidationError(f"{path}: expected object")

    if isinstance(value, dict):
        required = schema.get("required", [])
        if not isinstance(required, list):
            raise SchemaValidationError(f"{path}: required must be an array")
        missing = [name for name in required if name not in value]
        if missing:
            raise SchemaValidationError(f"{path}: missing keys {missing}")
        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            raise SchemaValidationError(f"{path}: properties must be an object")
        if schema.get("additionalProperties") is False:
            extra = sorted(set(value) - set(properties))
            if extra:
                raise SchemaValidationError(f"{path}: extra keys {extra}")
        for name, child_schema in properties.items():
            if name in value:
                if not isinstance(child_schema, dict):
                    raise SchemaValidationError(f"{path}.{name}: invalid schema")
                _validate_schema_instance(value[name], child_schema, f"{path}.{name}")


def _settle(raw_entry: Decimal, raw_exit: Decimal) -> Decimal:
    entry_price = raw_entry * Decimal("1.0005")
    gross_base = Decimal("30.00") / entry_price
    net_base = gross_base - gross_base * Decimal("0.0010")
    exit_price = raw_exit * Decimal("0.9995")
    gross_exit_quote = net_base * exit_price
    net_exit_quote = gross_exit_quote - gross_exit_quote * Decimal("0.0010")
    return net_exit_quote - Decimal("30.00")


class MicrostructureIntradayEconomicRouteContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    def assert_invalid(self, value: dict[str, object]) -> None:
        with self.assertRaises(SchemaValidationError):
            _validate_schema_instance(value, self.schema)

    def test_contract_validates_and_scope_is_exact(self) -> None:
        _validate_schema_instance(self.contract, self.schema)
        self.assertEqual(
            "https://json-schema.org/draft/2020-12/schema", self.schema["$schema"]
        )
        self.assertFalse(self.schema["additionalProperties"])
        self.assertEqual(EXACT_FILES, self.contract["file_scope"]["exact_files"])
        self.assertEqual(4, self.contract["file_scope"]["maximum_files"])

        drift = deepcopy(self.contract)
        drift["unexpected"] = True
        self.assert_invalid(drift)

    def test_selection_is_uninstantiated_and_simplest_first(self) -> None:
        binding = self.contract["selection_binding"]
        self.assertEqual(TIER_ORDER, binding["tier_order"])
        self.assertEqual(
            "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            binding["required_interpretation_disposition"],
        )
        self.assertEqual("UNINSTANTIATED", binding["selected_tier_value_in_template"])
        for name in (
            "caller_override_authorized",
            "fallback_tier_authorized",
            "threshold_change_authorized",
            "magnitude_ranking_authorized",
            "multi_tier_variants_authorized",
        ):
            self.assertFalse(binding[name])

        for name, value in (
            ("caller_override_authorized", True),
            ("selected_tier_value_in_template", TIER_ORDER[0]),
        ):
            drift = deepcopy(self.contract)
            drift["selection_binding"][name] = value
            self.assert_invalid(drift)

    def test_causal_clock_and_one_slot_order_are_frozen(self) -> None:
        clock = self.contract["causal_clock"]
        ledger = self.contract["position_ledger"]
        self.assertEqual("m+1", clock["signal_available_at"])
        self.assertEqual("m+2_MINUTE_OPEN", clock["entry_at"])
        self.assertEqual("m+62_MINUTE_OPEN", clock["exit_at"])
        self.assertEqual(60, clock["held_minute_count"])
        self.assertEqual(["EXIT", "ENTRY"], clock["same_timestamp_event_order"])
        self.assertEqual(1, ledger["maximum_open_positions_per_lane"])
        self.assertEqual(60, ledger["cooldown_minutes_per_tier"])
        self.assertFalse(ledger["overlap_authorized"])

        for section, name, value in (
            ("causal_clock", "entry_at", "m+1_MINUTE_OPEN"),
            ("causal_clock", "exit_at", "m+61_MINUTE_OPEN"),
            ("causal_clock", "same_timestamp_event_order", ["ENTRY", "EXIT"]),
            ("position_ledger", "maximum_open_positions_per_lane", 2),
        ):
            drift = deepcopy(self.contract)
            drift[section][name] = value
            self.assert_invalid(drift)

    def test_friction_formulas_are_exact_and_identical_for_controls(self) -> None:
        friction = self.contract["friction_ledger"]
        self.assertEqual("0.0005", friction["entry_adverse_slippage_rate"])
        self.assertEqual("0.0010", friction["buy_fee_rate_base"])
        self.assertEqual("0.0005", friction["exit_adverse_slippage_rate"])
        self.assertEqual("0.0010", friction["exit_fee_rate_quote"])
        self.assertEqual("30.00", friction["planning_round_trip_friction_bps"])
        self.assertTrue(friction["identical_model_required_for_controls"])

        flat_market_pnl = _settle(Decimal("100"), Decimal("100"))
        self.assertLess(flat_market_pnl, Decimal("0"))
        self.assertGreater(flat_market_pnl, Decimal("-0.10"))
        self.assertEqual(flat_market_pnl, _settle(Decimal("100"), Decimal("100")))

        drift = deepcopy(self.contract)
        drift["friction_ledger"]["exit_fee_rate_quote"] = "0.0009"
        self.assert_invalid(drift)

    def test_cash_and_matched_control_are_both_required(self) -> None:
        comparators = self.contract["comparators"]
        self.assertEqual("30.00", comparators["cash"]["capital_usdt"])
        self.assertEqual(
            "ABSOLUTE_BENCHMARK_NOT_SOLE_ALPHA_COMPARATOR",
            comparators["cash"]["role"],
        )
        matched = comparators["matched_control"]
        self.assertEqual("CLOSEST_UNUSED_STRICTLY_EARLIER_DAY", matched["selection"])
        self.assertTrue(matched["same_utc_minute_of_day"])
        self.assertTrue(matched["unique_control_required"])
        self.assertTrue(matched["paired_candidate_and_control_trade_counts_equal"])
        self.assertFalse(matched["cross_fold_matching_authorized"])
        self.assertEqual("80.00", matched["minimum_match_coverage_pct"])

        drift = deepcopy(self.contract)
        drift["comparators"]["matched_control"]["same_fold_required"] = False
        self.assert_invalid(drift)

    def test_three_untouched_stages_and_fail_closed_gates_are_frozen(self) -> None:
        stages = self.contract["forward_stages"]
        integrity = self.contract["integrity_gates"]
        economic = self.contract["economic_gates"]
        self.assertEqual(["DESIGN", "VALIDATION", "SEALED_OOS"], stages["stage_order"])
        self.assertEqual(14, stages["complete_utc_days_per_stage"])
        self.assertEqual(42, stages["total_complete_utc_days"])
        self.assertTrue(stages["discovery_bytes_excluded_from_all_stages"])
        self.assertFalse(stages["backfill_authorized"])
        self.assertFalse(stages["oos_open_authorized_by_template"])
        self.assertEqual(30, integrity["minimum_selected_tier_trades"])
        self.assertEqual(10, integrity["minimum_selected_tier_trades_per_seven_day_half"])
        self.assertEqual("80.00", integrity["minimum_matched_control_coverage_pct"])
        self.assertEqual("40.00", economic["top_one_positive_incremental_contribution_pct_lte"])
        self.assertTrue(economic["design_and_validation_must_both_pass_before_oos"])

        for section, name, value in (
            ("forward_stages", "complete_utc_days_per_stage", 13),
            ("forward_stages", "discovery_bytes_excluded_from_all_stages", False),
            ("integrity_gates", "minimum_selected_tier_trades", 29),
            ("economic_gates", "positive_candidate_net_trade_share_pct_gt", "49.00"),
        ):
            drift = deepcopy(self.contract)
            drift[section][name] = value
            self.assert_invalid(drift)

    def test_template_contains_no_dates_tier_or_outcome(self) -> None:
        serialized = json.dumps(self.contract, sort_keys=True)
        self.assertIsNone(re.search(r"\b20\d{2}-\d{2}-\d{2}\b", serialized))
        boundary = self.contract["evidence_boundary"]
        self.assertFalse(boundary["actual_selected_tier_present"])
        self.assertFalse(boundary["actual_stage_dates_present"])
        self.assertFalse(boundary["observed_outcomes_present"])
        self.assertFalse(boundary["adapter_or_runner_implemented"])

        drift = deepcopy(self.contract)
        drift["evidence_boundary"]["observed_outcomes_present"] = True
        self.assert_invalid(drift)

    def test_authorization_performance_and_safety_remain_closed(self) -> None:
        self.assertEqual(
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
            self.contract["authorization"],
        )
        performance = self.contract["performance_boundary"]
        self.assertEqual("ZERO_CONTRACT_ONLY", performance["immediate_pnl_effect"])
        self.assertEqual(
            "ZERO_CONTRACT_ONLY", performance["immediate_drawdown_effect"]
        )
        self.assertEqual("MISSING_PROOF", performance["calendar_claim"])
        self.assertTrue(all(value is False for value in self.contract["safety"].values()))

        for section, name, value in (
            ("safety", "oos_access_authorized", True),
            ("safety", "shadow_paper_live_authorized", True),
            ("performance_boundary", "performance_claim_authorized", True),
        ):
            drift = deepcopy(self.contract)
            drift[section][name] = value
            self.assert_invalid(drift)


if __name__ == "__main__":
    unittest.main()
