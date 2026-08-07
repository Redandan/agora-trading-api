from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import unittest


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = (
    PACKAGE_ROOT / "okx-microstructure-dra-entry-admission-contract.v1.json"
)
SCHEMA_PATH = (
    PACKAGE_ROOT / "okx-microstructure-dra-entry-admission-contract.v1.schema.json"
)

EXACT_FILES = [
    "research_pipeline/okx-microstructure-dra-entry-admission-contract.v1.json",
    "research_pipeline/okx-microstructure-dra-entry-admission-contract.v1.schema.json",
    "research_pipeline/tests/test_microstructure_dra_entry_admission_contract.py",
    "docs/okx-microstructure-dra-entry-admission-v1.md",
]
TIER_ORDER = [
    "MIDLINE_RATIO_1_5_ONLY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT",
]


class SchemaValidationError(AssertionError):
    pass


def _resolve(schema_root: dict[str, object], reference: str) -> dict[str, object]:
    if not reference.startswith("#/"):
        raise SchemaValidationError(f"non-local schema reference: {reference}")
    value: object = schema_root
    for token in reference[2:].split("/"):
        if not isinstance(value, dict) or token not in value:
            raise SchemaValidationError(f"unresolved schema reference: {reference}")
        value = value[token]
    if not isinstance(value, dict):
        raise SchemaValidationError(f"schema reference is not an object: {reference}")
    return value


def _validate_schema_instance(
    value: object,
    schema: dict[str, object],
    schema_root: dict[str, object],
    path: str = "$",
) -> None:
    reference = schema.get("$ref")
    if reference is not None:
        if not isinstance(reference, str):
            raise SchemaValidationError(f"{path}: invalid schema reference")
        _validate_schema_instance(value, _resolve(schema_root, reference), schema_root, path)
        return

    if "const" in schema and value != schema["const"]:
        raise SchemaValidationError(f"{path}: value differs from frozen const")

    expected_type = schema.get("type")
    if expected_type is not None:
        matches = {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "boolean": isinstance(value, bool),
        }.get(str(expected_type))
        if matches is not True:
            raise SchemaValidationError(f"{path}: expected {expected_type}")

    if isinstance(value, dict):
        required = schema.get("required", [])
        if not isinstance(required, list):
            raise SchemaValidationError(f"{path}: schema required is not an array")
        missing = [name for name in required if name not in value]
        if missing:
            raise SchemaValidationError(f"{path}: missing keys {missing}")
        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            raise SchemaValidationError(f"{path}: schema properties is not an object")
        if schema.get("additionalProperties") is False:
            extra = sorted(set(value) - set(properties))
            if extra:
                raise SchemaValidationError(f"{path}: extra keys {extra}")
        for name, child_schema in properties.items():
            if name in value:
                if not isinstance(child_schema, dict):
                    raise SchemaValidationError(f"{path}.{name}: invalid child schema")
                _validate_schema_instance(
                    value[name], child_schema, schema_root, f"{path}.{name}"
                )

    if isinstance(value, list):
        minimum = schema.get("minItems")
        if isinstance(minimum, int) and len(value) < minimum:
            raise SchemaValidationError(f"{path}: fewer than {minimum} items")
        if schema.get("uniqueItems") is True:
            keys = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            if len(keys) != len(set(keys)):
                raise SchemaValidationError(f"{path}: duplicate items")
        item_schema = schema.get("items")
        if item_schema is not None:
            if not isinstance(item_schema, dict):
                raise SchemaValidationError(f"{path}: invalid items schema")
            for index, item in enumerate(value):
                _validate_schema_instance(
                    item, item_schema, schema_root, f"{path}[{index}]"
                )

    if isinstance(value, str):
        minimum_length = schema.get("minLength")
        if isinstance(minimum_length, int) and len(value) < minimum_length:
            raise SchemaValidationError(f"{path}: string is too short")
        pattern = schema.get("pattern")
        if isinstance(pattern, str) and re.search(pattern, value) is None:
            raise SchemaValidationError(f"{path}: string does not match {pattern}")


def _decision(parent_entry: bool, record_count: int, event_count: int) -> str:
    if record_count != 59:
        return "INVALID_COMPARISON_DATA_REJECT"
    if not parent_entry:
        return "NO_PARENT_ENTRY"
    return "ADMIT" if event_count >= 1 else "VETO"


class MicrostructureDraEntryAdmissionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    def test_contract_validates_against_strict_schema(self) -> None:
        self.assertEqual("https://json-schema.org/draft/2020-12/schema", self.schema["$schema"])
        self.assertEqual("object", self.schema["type"])
        self.assertFalse(self.schema["additionalProperties"])
        _validate_schema_instance(self.contract, self.schema, self.schema)

        extra = deepcopy(self.contract)
        extra["unexpected"] = True
        with self.assertRaises(SchemaValidationError):
            _validate_schema_instance(extra, self.schema, self.schema)

        boundary_drift = deepcopy(self.contract)
        boundary_drift["clock"]["eligible_minute_start_last"] = "23:59:00"
        with self.assertRaises(SchemaValidationError):
            _validate_schema_instance(boundary_drift, self.schema, self.schema)

    def test_selected_tier_is_interpretation_bound_and_simplest_first(self) -> None:
        binding = self.contract["selected_tier_binding"]
        self.assertEqual(TIER_ORDER, binding["tier_order"])
        self.assertEqual(
            "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            binding["required_interpretation_disposition"],
        )
        self.assertEqual(
            "CLOSE_WITHOUT_ADMISSION_DESIGN", binding["non_positive_action"]
        )
        for name in (
            "caller_selected_tier_authorized",
            "fallback_tier_authorized",
            "threshold_change_authorized",
            "magnitude_selection_authorized",
            "more_complex_tier_escalation_authorized",
        ):
            self.assertFalse(binding[name])

    def test_strictly_before_fill_window_has_exactly_59_minutes(self) -> None:
        clock = self.contract["clock"]
        first = datetime(2026, 1, 1, 23, 0, tzinfo=timezone.utc)
        fill = datetime(2026, 1, 2, 0, 0, tzinfo=timezone.utc)
        eligible = [first + timedelta(minutes=index) for index in range(59)]

        self.assertEqual(59, clock["eligible_minute_count"])
        self.assertEqual("23:58:00", eligible[-1].time().isoformat())
        self.assertTrue(all(minute + timedelta(minutes=1) < fill for minute in eligible))
        fill_boundary = first + timedelta(minutes=59)
        self.assertEqual("23:59:00", fill_boundary.time().isoformat())
        self.assertEqual(fill, fill_boundary + timedelta(minutes=1))
        self.assertEqual(
            "23:59:00", clock["excluded_fill_boundary_minute_start"]
        )

    def test_any_event_admits_and_missing_data_invalidates_comparison(self) -> None:
        admission = self.contract["admission"]
        integrity = self.contract["data_integrity"]
        self.assertEqual(
            "ANY_ELIGIBLE_COMPLETED_MINUTE_SATISFIES_SELECTED_TIER",
            admission["aggregation"],
        )
        self.assertEqual("VETO", _decision(True, 59, 0))
        self.assertEqual("ADMIT", _decision(True, 59, 1))
        self.assertEqual("NO_PARENT_ENTRY", _decision(False, 59, 59))
        self.assertEqual("INVALID_COMPARISON_DATA_REJECT", _decision(True, 58, 1))
        self.assertFalse(admission["invalid_data_can_admit"])
        self.assertFalse(admission["invalid_data_can_veto"])
        self.assertTrue(
            {
                "MISSING_ELIGIBLE_MINUTE",
                "DUPLICATE_ELIGIBLE_MINUTE",
                "MINUTE_NOT_STRICTLY_BEFORE_FILL",
                "DAY_INTEGRITY_NOT_CLEAN",
                "UNSEALED_OR_HASH_INVALID_DAY",
            }.issubset(integrity["failure_conditions"])
        )

    def test_tier_projection_excludes_future_and_diagnostic_only_fields(self) -> None:
        projection = self.contract["tier_feature_projection"]
        self.assertEqual("1.50", projection["ratio_threshold"])
        self.assertEqual("NO_EVENT", projection["zero_denominator_disposition"])
        common = set(projection["common_columns"])
        additions = projection["additional_columns_by_tier"]
        selected_columns = {
            tier: common | set(additions[tier]) for tier in TIER_ORDER
        }
        self.assertEqual(3, len(selected_columns[TIER_ORDER[0]]))
        self.assertIn("net_taker_quote_notional", selected_columns[TIER_ORDER[1]])
        self.assertIn("average_book_imbalance", selected_columns[TIER_ORDER[2]])
        forbidden = set(projection["forbidden_admission_inputs"])
        self.assertTrue(
            {
                "NEXT_COMPLETE_MINUTE_OPEN",
                "trade_open_price",
                "return_bps",
                "mfe_bps",
                "mae_bps",
                "matched_median_return_delta_bps",
                "cooldown_minutes_per_tier",
            }.issubset(forbidden)
        )
        self.assertTrue(all(not columns.intersection(forbidden) for columns in selected_columns.values()))

    def test_next_hour_fill_and_matched_parent_accounting_are_unchanged(self) -> None:
        parent = self.contract["matched_parent"]
        self.assertEqual("NEXT_1H_OPEN", parent["fill_timing"])
        self.assertEqual("250.00", parent["reference_capital_usdt"])
        self.assertEqual("30.00", parent["lot_notional_usdt"])
        self.assertEqual("0.0010", parent["fee_rate_per_side"])
        self.assertEqual("0.0005", parent["adverse_slippage_rate_per_side"])
        self.assertTrue(parent["same_parent_decisions"])
        self.assertTrue(parent["same_eligible_dates"])
        self.assertTrue(
            {
                "EXIT_LOGIC",
                "FINAL_VALUATION",
                "TERMINAL_INVENTORY",
                "FOLD_BOUNDARIES",
            }.issubset(parent["unchanged_semantics"])
        )
        self.assertEqual(4, len(parent["required_parity_ledgers"]))
        self.assertTrue(
            {
                "REALIZED_PNL",
                "UNREALIZED_PNL",
                "TOTAL_PNL",
                "MAXIMUM_DRAWDOWN_AND_PATH",
                "CAPITAL_UTILIZATION",
                "BLOCKED_ENTRIES",
                "HOLDING_AGE",
                "YEAR_AND_REGIME_CONCENTRATION",
                "TERMINAL_INVENTORY",
                "DATA_EXCLUSIONS",
            }.issubset(parent["required_reports"])
        )

    def test_exact_scope_preoutcome_and_all_safety_boundaries_false(self) -> None:
        self.assertEqual(
            "PREOUTCOME_DESIGN_ONLY_NOT_ADAPTER_NOT_CANDIDATE",
            self.contract["status"],
        )
        self.assertEqual(EXACT_FILES, self.contract["file_scope"]["exact_files"])
        self.assertEqual(4, self.contract["file_scope"]["maximum_files"])
        self.assertTrue(all(value is False for value in self.contract["safety"].values()))
        boundary = self.contract["evidence_boundary"]
        self.assertFalse(boundary["existing_14_day_window_reuse_as_candidate_validation"])
        self.assertFalse(boundary["existing_14_day_window_reuse_as_oos"])
        for name in (
            "adapter_run_authorized",
            "economic_evaluation_authorized",
            "hypothesis_registration_authorized",
            "candidate_registration_authorized",
            "oos_access_authorized",
            "runtime_integration_authorized",
            "state_write_authorized",
        ):
            self.assertFalse(boundary[name])
        self.assertEqual("ZERO_CONTRACT_ONLY", self.contract["performance_hypothesis"]["immediate_pnl_effect"])
        self.assertEqual("ZERO_CONTRACT_ONLY", self.contract["performance_hypothesis"]["immediate_drawdown_effect"])


if __name__ == "__main__":
    unittest.main()
