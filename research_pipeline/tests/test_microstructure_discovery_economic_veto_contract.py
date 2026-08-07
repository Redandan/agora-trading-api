from __future__ import annotations

import copy
import hashlib
import json
import re
import unittest
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "research_pipeline" / "okx-microstructure-discovery-economic-veto-contract.v1.json"
SCHEMA_PATH = ROOT / "research_pipeline" / "microstructure-discovery-economic-veto-result.v1.schema.json"

TASK_SHA256 = "e5c574d5cdfb9603a639f7f0873626a1129192c24a75ff29f17af26000396287"
ROUTE_SHA256 = "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e"
INTERPRETATION_SHA256 = "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509"
HASH = "1" * 64


class SchemaValidationError(AssertionError):
    pass


def _resolve(schema_root: dict, reference: str) -> dict:
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


def _passes(value: object, schema: dict, schema_root: dict) -> bool:
    try:
        _validate_schema_instance(value, schema, schema_root)
    except SchemaValidationError:
        return False
    return True


def _validate_schema_instance(
    value: object, schema: dict, schema_root: dict, path: str = "$"
) -> None:
    reference = schema.get("$ref")
    if reference is not None:
        if not isinstance(reference, str):
            raise SchemaValidationError(f"{path}: invalid schema reference")
        _validate_schema_instance(value, _resolve(schema_root, reference), schema_root, path)
        return

    if "const" in schema and value != schema["const"]:
        raise SchemaValidationError(f"{path}: value differs from frozen const")
    if "enum" in schema and value not in schema["enum"]:
        raise SchemaValidationError(f"{path}: value is outside enum")

    for keyword, required_matches in (("allOf", None), ("anyOf", 1), ("oneOf", 1)):
        candidates = schema.get(keyword)
        if candidates is None:
            continue
        if not isinstance(candidates, list) or not candidates:
            raise SchemaValidationError(f"{path}: invalid {keyword}")
        matches = sum(
            1
            for candidate in candidates
            if isinstance(candidate, dict) and _passes(value, candidate, schema_root)
        )
        if keyword == "allOf" and matches != len(candidates):
            raise SchemaValidationError(f"{path}: allOf failed")
        if keyword == "anyOf" and matches < required_matches:
            raise SchemaValidationError(f"{path}: anyOf failed")
        if keyword == "oneOf" and matches != required_matches:
            raise SchemaValidationError(f"{path}: oneOf failed")

    expected_type = schema.get("type")
    if expected_type is not None:
        matches_type = {
            "object": isinstance(value, dict),
            "array": isinstance(value, list),
            "string": isinstance(value, str),
            "integer": isinstance(value, int) and not isinstance(value, bool),
            "boolean": isinstance(value, bool),
        }.get(str(expected_type))
        if matches_type is not True:
            raise SchemaValidationError(f"{path}: expected {expected_type}")

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
                    raise SchemaValidationError(f"{path}.{name}: invalid child schema")
                _validate_schema_instance(value[name], child_schema, schema_root, f"{path}.{name}")

    if isinstance(value, list):
        minimum = schema.get("minItems")
        maximum = schema.get("maxItems")
        if isinstance(minimum, int) and len(value) < minimum:
            raise SchemaValidationError(f"{path}: fewer than {minimum} items")
        if isinstance(maximum, int) and len(value) > maximum:
            raise SchemaValidationError(f"{path}: more than {maximum} items")
        if schema.get("uniqueItems") is True:
            keys = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            if len(keys) != len(set(keys)):
                raise SchemaValidationError(f"{path}: duplicate items")
        item_schema = schema.get("items")
        if item_schema is not None:
            if not isinstance(item_schema, dict):
                raise SchemaValidationError(f"{path}: invalid items schema")
            for index, item in enumerate(value):
                _validate_schema_instance(item, item_schema, schema_root, f"{path}[{index}]")

    if isinstance(value, str):
        pattern = schema.get("pattern")
        if isinstance(pattern, str) and re.search(pattern, value) is None:
            raise SchemaValidationError(f"{path}: string does not match {pattern}")

    if isinstance(value, int) and not isinstance(value, bool):
        minimum = schema.get("minimum")
        if isinstance(minimum, int) and value < minimum:
            raise SchemaValidationError(f"{path}: below minimum {minimum}")


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _seal(value: dict) -> None:
    payload = {key: item for key, item in value.items() if key != "seal"}
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _sha256(_canonical_bytes(payload)),
        "canonicalization": "UTF-8 compact JSON excluding seal; object keys sorted lexicographically",
    }


def _fixture(contract: dict, schema_sha256: str) -> dict:
    start = date(2099, 1, 1)
    days = []
    for offset in range(14):
        marker = f"{offset + 1:064x}"
        days.append(
            {
                "day": (start + timedelta(days=offset)).isoformat(),
                "integrity_status": "CLEAN",
                "valid_minute_count": 1440,
                "bundle_document_sha256": marker,
                "bundle_payload_sha256": marker,
                "envelope_document_sha256": marker,
                "envelope_payload_sha256": marker,
                "chain_sha256": marker,
                "anomaly_count": 0,
            }
        )
    true_integrity = {
        "fourteen_contiguous_clean_days": True,
        "exactly_1440_valid_minutes_each_day": True,
        "bundle_hashes_and_chain_valid": True,
        "trade_open_price_and_feature_bytes_valid": True,
        "minimum_30_selected_tier_trades": True,
        "minimum_10_trades_first_half": True,
        "minimum_10_trades_second_half": True,
        "minimum_80_pct_matched_control_coverage": True,
        "zero_duplicate_controls": True,
        "zero_cross_fold_labels": True,
        "zero_integrity_anomalies": True,
        "zero_terminal_inventory": True,
        "all_required_integrity_gates_passed": True,
    }
    true_economic = {
        "positive_candidate_net_total_pnl": True,
        "positive_candidate_minus_control_total_pnl": True,
        "positive_median_candidate_net_return": True,
        "positive_trade_share_strictly_above_50_pct": True,
        "candidate_drawdown_no_worse_than_control": True,
        "positive_first_half_candidate_minus_control": True,
        "positive_second_half_candidate_minus_control": True,
        "top_one_contribution_at_most_40_pct": True,
        "zero_terminal_inventory": True,
        "all_required_economic_gates_passed": True,
    }
    result = {
        "schema_version": "1",
        "result_type": "OKX_MICROSTRUCTURE_DISCOVERY_ECONOMIC_VETO_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "contract_binding": {
            "contract_id": contract["contract_id"],
            "document_sha256": _sha256(CONTRACT_PATH.read_bytes()),
            "payload_sha256": contract["seal"]["payload_sha256"],
            "result_schema_sha256": schema_sha256,
        },
        "source_handoff": {
            "schema_version": "1",
            "result_type": "MICROSTRUCTURE_V3_CREATE_ONLY_HANDOFF_RESULT",
            "result_schema_sha256": "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9",
            "document_sha256": HASH,
            "payload_sha256": HASH,
            "manifest_schema_sha256": "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090",
            "manifest_document_sha256": HASH,
            "manifest_payload_sha256": HASH,
            "diagnostic_contract_id": "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3",
            "diagnostic_contract_sha256": "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
        },
        "source_interpretation": {
            "result_type": "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_RESULT_V1",
            "result_schema_sha256": "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564",
            "document_sha256": HASH,
            "payload_sha256": HASH,
            "contract_id": "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_V1",
            "contract_sha256": INTERPRETATION_SHA256,
            "handoff_document_sha256": HASH,
            "handoff_payload_sha256": HASH,
            "disposition": "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            "selected_tier": "MIDLINE_RATIO_1_5_ONLY",
            "selection_rule": "FIRST_TIER_CLASSIFIED_PASS_IN_FROZEN_ORDER",
            "caller_override_authorized": False,
            "fallback_tier_authorized": False,
            "magnitude_ranking_authorized": False,
            "multi_tier_variants_authorized": False,
            "tuning_authorized": False,
        },
        "discovery_inventory": {
            "role": "DISCOVERY_ONLY_NOT_DESIGN_VALIDATION_OR_OOS",
            "complete_contiguous_utc_days": 14,
            "valid_minutes_per_day": 1440,
            "required_feature_fields": [
                "trade_open_price",
                "above_mid_buy_quote_notional",
                "below_mid_sell_quote_notional",
                "net_taker_quote_notional",
                "average_book_imbalance",
                "bid_replenishment_quote_proxy",
            ],
            "all_exported_raw_v3_bundles_used": True,
            "backfill_or_substitution_used": False,
            "cross_window_bytes_used": False,
            "days": days,
        },
        "gate_evaluation": {
            "integrity_metrics": {
                "selected_tier_trade_count": 40,
                "first_seven_day_trade_count": 20,
                "second_seven_day_trade_count": 20,
                "matched_control_coverage_pct": "100.00",
                "duplicate_control_count": 0,
                "cross_fold_label_count": 0,
                "integrity_anomaly_count": 0,
                "excluded_without_full_exit_count": 0,
                "candidate_terminal_inventory": "0",
                "control_terminal_inventory": "0",
            },
            "integrity_gates": true_integrity,
            "economic_metrics": {
                "candidate_net_total_pnl_usdt": "1.00",
                "matched_control_net_total_pnl_usdt": "0.50",
                "candidate_minus_control_total_pnl_usdt": "0.50",
                "median_candidate_net_return_bps": "1.00",
                "positive_candidate_net_trade_share_pct": "60.00",
                "candidate_max_drawdown_usdt": "0.50",
                "matched_control_max_drawdown_usdt": "0.50",
                "first_half_candidate_minus_control_pnl_usdt": "0.25",
                "second_half_candidate_minus_control_pnl_usdt": "0.25",
                "top_one_positive_incremental_contribution_pct": "20.00",
                "raw_break_even_hurdle_bps": "30.0550826113908",
            },
            "economic_gates": true_economic,
            "all_required_gates_passed": True,
        },
        "disposition": "PERMIT_LATER_V4",
        "inference_boundaries": {
            "evidence_role": "DISCOVERY_ONLY_NOT_DESIGN_VALIDATION_OR_OOS",
            "permitted_claim": "VETO_ROUTE_OR_PERMIT_SEPARATELY_FROZEN_LATER_V4_SLICE_ONLY",
            "proves_alpha": False,
            "authorizes_candidate": False,
            "authorizes_design_validation_or_oos": False,
            "authorizes_v4_source_or_manifest_creation": False,
            "authorizes_activation": False,
            "veto_closes_without_tuning": True,
            "permit_requires_separate_later_v4_freeze": True,
        },
        "missing_proof": {
            "false_negative_rate_across_regimes": "MISSING_PROOF",
            "generalization_beyond_discovery": "MISSING_PROOF",
            "future_v4_source_and_manifest": "MISSING_PROOF",
            "design_validation_and_oos_value": "MISSING_PROOF",
            "strategy_pnl": "MISSING_PROOF",
            "drawdown": "MISSING_PROOF",
            "capital_utilization": "MISSING_PROOF",
            "capacity": "MISSING_PROOF",
            "candidate_readiness": "MISSING_PROOF",
            "activation": "MISSING_PROOF",
        },
        "safety_assertions": {
            "canonical_state_changed": False,
            "research_state_changed": False,
            "server_research_mcp_write_attempted": False,
            "second_timer_created": False,
            "runner_or_economic_execution_attempted": False,
            "candidate_or_hypothesis_registered": False,
            "oos_opened": False,
            "trading_action_attempted": False,
            "paid_api_used": False,
        },
    }
    _seal(result)
    return result


def _validate_result(value: dict, schema: dict, contract: dict, schema_sha256: str) -> None:
    _validate_schema_instance(value, schema, schema)
    binding = value["contract_binding"]
    if binding["document_sha256"] != _sha256(CONTRACT_PATH.read_bytes()):
        raise SchemaValidationError("contract document hash mismatch")
    if binding["payload_sha256"] != contract["seal"]["payload_sha256"]:
        raise SchemaValidationError("contract payload hash mismatch")
    if binding["result_schema_sha256"] != schema_sha256:
        raise SchemaValidationError("result schema hash mismatch")
    handoff = value["source_handoff"]
    interpretation = value["source_interpretation"]
    if interpretation["handoff_document_sha256"] != handoff["document_sha256"]:
        raise SchemaValidationError("interpretation handoff document mismatch")
    if interpretation["handoff_payload_sha256"] != handoff["payload_sha256"]:
        raise SchemaValidationError("interpretation handoff payload mismatch")
    days = [date.fromisoformat(item["day"]) for item in value["discovery_inventory"]["days"]]
    if len(days) != 14 or any(days[index] != days[0] + timedelta(days=index) for index in range(14)):
        raise SchemaValidationError("inventory is not exactly 14 contiguous UTC days")
    payload = {key: item for key, item in value.items() if key != "seal"}
    if value["seal"]["payload_sha256"] != _sha256(_canonical_bytes(payload)):
        raise SchemaValidationError("result payload seal mismatch")


class DiscoveryEconomicVetoContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = _load(CONTRACT_PATH)
        cls.schema = _load(SCHEMA_PATH)
        cls.schema_sha256 = _sha256(SCHEMA_PATH.read_bytes())

    def assert_valid(self, value: dict) -> None:
        _validate_result(value, self.schema, self.contract, self.schema_sha256)

    def assert_invalid(self, value: dict) -> None:
        with self.assertRaises(SchemaValidationError):
            _validate_result(value, self.schema, self.contract, self.schema_sha256)

    def test_contract_source_hashes_and_seal_are_frozen(self) -> None:
        self.assertEqual(TASK_SHA256, self.contract["frozen_sources"]["task"]["sha256"])
        self.assertEqual(ROUTE_SHA256, self.contract["frozen_sources"]["route_contract"]["sha256"])
        self.assertEqual(INTERPRETATION_SHA256, self.contract["frozen_sources"]["interpretation_contract"]["sha256"])
        self.assertEqual(self.schema_sha256, self.contract["frozen_sources"]["result_schema"]["sha256"])
        payload = {key: value for key, value in self.contract.items() if key != "seal"}
        self.assertEqual(_sha256(_canonical_bytes(payload)), self.contract["seal"]["payload_sha256"])

    def test_contract_freezes_clock_cost_controls_and_gates(self) -> None:
        self.assertEqual("m+2_MINUTE_OPEN", self.contract["causal_clock"]["entry_at"])
        self.assertEqual("m+62_MINUTE_OPEN", self.contract["causal_clock"]["exit_at"])
        self.assertEqual(60, self.contract["causal_clock"]["held_minute_count"])
        self.assertEqual("30.0550826113908", self.contract["friction_ledger"]["raw_break_even_hurdle_bps_context"])
        self.assertEqual("CLOSEST_UNUSED_STRICTLY_EARLIER_DAY", self.contract["comparators"]["matched_control"]["selection"])
        self.assertEqual(30, self.contract["integrity_gates"]["minimum_selected_tier_trades"])
        self.assertEqual("50.00", self.contract["economic_gates"]["positive_candidate_net_trade_share_pct_gt"])
        self.assertFalse(self.contract["friction_ledger"]["cost_or_hurdle_tuning_authorized"])

    def test_contract_freezes_tier_and_preoutcome_boundary(self) -> None:
        binding = self.contract["source_binding"]
        self.assertEqual("UNINSTANTIATED", binding["selected_tier_value_in_template"])
        self.assertFalse(binding["caller_override_authorized"])
        self.assertFalse(binding["fallback_tier_authorized"])
        self.assertFalse(binding["magnitude_ranking_authorized"])
        self.assertFalse(binding["multi_tier_variants_authorized"])
        self.assertFalse(self.contract["inference_boundaries"]["actual_handoff_present"])
        self.assertFalse(self.contract["inference_boundaries"]["actual_date_event_metric_pnl_or_drawdown_present"])

    def test_schema_accepts_only_consistent_permit(self) -> None:
        self.assert_valid(_fixture(self.contract, self.schema_sha256))

    def test_schema_accepts_consistent_veto_and_rejects_contradictions(self) -> None:
        veto = _fixture(self.contract, self.schema_sha256)
        veto["gate_evaluation"]["economic_gates"]["positive_candidate_net_total_pnl"] = False
        veto["gate_evaluation"]["economic_gates"]["all_required_economic_gates_passed"] = False
        veto["gate_evaluation"]["all_required_gates_passed"] = False
        veto["disposition"] = "VETO_BEFORE_V4"
        _seal(veto)
        self.assert_valid(veto)

        contradictory = copy.deepcopy(veto)
        contradictory["disposition"] = "PERMIT_LATER_V4"
        _seal(contradictory)
        self.assert_invalid(contradictory)

        aggregate_drift = _fixture(self.contract, self.schema_sha256)
        aggregate_drift["gate_evaluation"]["integrity_gates"]["minimum_30_selected_tier_trades"] = False
        _seal(aggregate_drift)
        self.assert_invalid(aggregate_drift)

    def test_schema_rejects_authorization_hash_tier_and_inference_drift(self) -> None:
        for mutate in (
            lambda value: value.__setitem__("authorization", "LIVE"),
            lambda value: value["source_handoff"].__setitem__("diagnostic_contract_sha256", HASH),
            lambda value: value["source_interpretation"].__setitem__("selected_tier", "CALLER_TIER"),
            lambda value: value["source_interpretation"].__setitem__("caller_override_authorized", True),
            lambda value: value["inference_boundaries"].__setitem__("proves_alpha", True),
            lambda value: value["safety_assertions"].__setitem__("oos_opened", True),
        ):
            changed = _fixture(self.contract, self.schema_sha256)
            mutate(changed)
            _seal(changed)
            self.assert_invalid(changed)

    def test_schema_rejects_inventory_and_extra_property_drift(self) -> None:
        short = _fixture(self.contract, self.schema_sha256)
        short["discovery_inventory"]["days"].pop()
        _seal(short)
        self.assert_invalid(short)

        noncanonical = _fixture(self.contract, self.schema_sha256)
        noncanonical["discovery_inventory"]["days"][1]["day"] = noncanonical["discovery_inventory"]["days"][0]["day"]
        _seal(noncanonical)
        self.assert_invalid(noncanonical)

        extra = _fixture(self.contract, self.schema_sha256)
        extra["runner"] = "FORBIDDEN"
        _seal(extra)
        self.assert_invalid(extra)

    def test_contract_representative_drift_changes_payload_hash(self) -> None:
        original = self.contract["seal"]["payload_sha256"]
        mutations = [
            ("causal_clock", "entry_at", "m+1_MINUTE_OPEN"),
            ("friction_ledger", "entry_adverse_slippage_rate", "0.0004"),
            ("comparators", "matched_control", {"selection": "SAME_DAY"}),
            ("economic_gates", "positive_candidate_net_trade_share_pct_gt", "49.00"),
            ("dispositions", "all_required_gates_pass", "PASS"),
        ]
        for section, key, value in mutations:
            changed = copy.deepcopy(self.contract)
            changed[section][key] = value
            payload = {name: item for name, item in changed.items() if name != "seal"}
            self.assertNotEqual(original, _sha256(_canonical_bytes(payload)))


if __name__ == "__main__":
    unittest.main()
