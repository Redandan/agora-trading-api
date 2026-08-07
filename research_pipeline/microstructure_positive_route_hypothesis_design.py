from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import hashlib
from pathlib import Path
import re
from typing import Any

from research_pipeline.microstructure_interpretation import (
    CONTRACT_ID as INTERPRETATION_CONTRACT_ID,
    CONTRACT_SHA256 as INTERPRETATION_CONTRACT_SHA256,
    RESULT_SCHEMA_SHA256 as INTERPRETATION_SCHEMA_SHA256,
    RESULT_TYPE as INTERPRETATION_RESULT_TYPE,
    TIER_ORDER,
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    canonical_json_bytes,
    load_json_bytes_strict,
)


CONTRACT_ID = "OKX_MICROSTRUCTURE_POSITIVE_ROUTE_HYPOTHESIS_DESIGN_V2"
CONTRACT_SHA256 = "802fda49c9b0a3d6a32b3e8e6d66dc6fa25312eb4ea3f0deddb715216b489f41"
RESULT_TYPE = "OKX_MICROSTRUCTURE_POSITIVE_ROUTE_HYPOTHESIS_DESIGN_RESULT_V2"
RESULT_SCHEMA_SHA256 = "1c829c05288d7a4d5a925cad1b1738eb5fe156f4f6ed2babc423efd4b4a9cb94"
POLICY_SHA256 = "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6"
ROUTE_ID = "OKX_MICROSTRUCTURE_INTRADAY_ECONOMIC_ROUTE_V1"
ROUTE_CONTRACT_SHA256 = (
    "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e"
)
REQUIRED_CAPABILITY = (
    "MICROSTRUCTURE_STANDALONE_INTRADAY_ECONOMIC_ADAPTER_V1_NOT_IMPLEMENTED"
)
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)

CONTRACT_PATH = Path(__file__).with_name(
    "okx-microstructure-positive-route-hypothesis-design-contract.v2.json"
)
RESULT_SCHEMA_PATH = Path(__file__).with_name(
    "microstructure-positive-route-hypothesis-design-result.v2.schema.json"
)
POLICY_PATH = Path(__file__).with_name("policy.v3.json")
INTERPRETATION_CONTRACT_PATH = Path(__file__).with_name(
    "okx-microstructure-forward-interpretation-contract.v1.json"
)
INTERPRETATION_SCHEMA_PATH = Path(__file__).with_name(
    "microstructure-interpretation-result.v1.schema.json"
)
ROUTE_CONTRACT_PATH = Path(__file__).with_name(
    "okx-microstructure-intraday-economic-route-contract.v1.json"
)

POSITIVE_DISPOSITION = "READY_FOR_ONE_HYPOTHESIS_DESIGN"
NON_POSITIVE_DISPOSITIONS = {
    "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
    "AMBIGUOUS_NO_HYPOTHESIS",
    "INSUFFICIENT_FORWARD_EVIDENCE",
}
PROPOSAL_FIELDS = {
    "design_id",
    "created_at",
    "title",
    "thesis",
    "economic_rationale",
    "performance_thesis",
    "drawdown_thesis",
    "opportunity_cost",
}

POLICY_BINDING = {
    "policy_id": "AUTONOMOUS_TRADING_RESEARCH_V3",
    "policy_sha256": POLICY_SHA256,
    "primary_metric": "fee_adjusted_total_pnl_delta_under_equal_capital",
    "required_metrics": [
        "realized_pnl",
        "unrealized_pnl",
        "total_pnl",
        "maximum_drawdown",
        "capital_utilization",
        "blocked_entries",
        "holding_age",
    ],
    "constraints": [
        "causal_point_in_time_data",
        "positive_incremental_validation_and_oos_evidence",
        "no_material_drawdown_deterioration",
        "no_hidden_terminal_inventory_loss",
        "stable_neighboring_behavior",
        "year_and_regime_concentration_visible",
    ],
}
EVIDENCE_PLAN = {
    "discovery_window_role": "DISCOVERY_ONLY_NOT_ECONOMIC_EVIDENCE_OR_OOS",
    "discovery_complete_utc_days": 14,
    "discovery_window_reuse_as_economic_evidence": False,
    "future_stage_order": [
        "V4_SOURCE_AND_ECONOMIC_MANIFEST_FREEZE",
        "OFFLINE_ADAPTER_AND_LEDGER_PARITY",
        "DESIGN",
        "VALIDATION",
        "CANONICAL_CANDIDATE_PATH",
        "SEALED_OOS",
    ],
    "v4_source_instance": "BYTE_SEPARATED_SINGLE_ACTIVE_V4_REQUIRED_LATER",
    "economic_manifest": (
        "EXACT_DATES_AND_CONTRACT_INSTANCE_REQUIRED_BEFORE_ANY_ECONOMIC_BYTE"
    ),
    "total_new_complete_utc_days": 42,
    "design_complete_utc_days": 14,
    "validation_complete_utc_days": 14,
    "sealed_oos_complete_utc_days": 14,
    "stages_consecutive": True,
    "exact_stage_dates": "MISSING_PROOF",
    "adapter_and_ledger_parity_required_before_evaluation": True,
    "design_and_validation_must_pass_before_candidate_path": True,
    "canonical_candidate_path_before_oos_open": True,
    "oos_server_sealed": True,
    "oos_nondisclosure_until_design_validation_pass_and_route_frozen": True,
    "stage_skip_or_collapse_authorized": False,
    "required_future_evaluations": [
        "realized_pnl",
        "unrealized_pnl",
        "total_pnl",
        "maximum_drawdown",
        "capital_utilization",
        "holding_age",
        "terminal_inventory",
        "fees",
        "adverse_slippage",
        "round_trip_friction",
        "matched_control_coverage",
        "matched_control_breadth",
        "year_and_regime_concentration",
        "event_cadence",
        "capacity",
    ],
}
MISSING_PROOF = {
    "v4_source_instance": "MISSING_PROOF",
    "exact_dates_and_manifest": "MISSING_PROOF",
    "event_cadence": "MISSING_PROOF",
    "matched_control_coverage": "MISSING_PROOF",
    "source_reliability": "MISSING_PROOF",
    "adapter_and_ledger_parity": "MISSING_PROOF",
    "fees_slippage_friction_value": "MISSING_PROOF",
    "strategy_pnl": "MISSING_PROOF",
    "drawdown": "MISSING_PROOF",
    "capital_utilization": "MISSING_PROOF",
    "holding_risk": "MISSING_PROOF",
    "capacity": "MISSING_PROOF",
    "candidate_readiness": "MISSING_PROOF",
    "oos_value": "MISSING_PROOF",
    "activation": "MISSING_PROOF",
}
SAFETY_ASSERTIONS = {
    "canonical_state_write_authorized": False,
    "research_state_write_authorized": False,
    "source_instantiation_authorized": False,
    "manifest_creation_authorized": False,
    "adapter_implementation_authorized": False,
    "hypothesis_registration_authorized": False,
    "candidate_registration_authorized": False,
    "oos_access_authorized": False,
    "activation_authorized": False,
    "second_timer_or_writer_authorized": False,
    "trading_database_order_fund_action_authorized": False,
    "paid_api_authorized": False,
}

_CONTRACT_ROUTE_SELECTION = {
    "route_id": ROUTE_ID,
    "route_contract_sha256": ROUTE_CONTRACT_SHA256,
    "priority": "SOLE_PRIMARY",
    "tier_binding": "INTERPRETATION_SELECTED_FIRST_PASS",
    "maximum_routes": 1,
    "maximum_designs": 1,
    "maximum_eventual_candidate_variants": 1,
    "caller_override_authorized": False,
    "multiple_routes_authorized": False,
    "dra_fallback_authorized": False,
    "route_switch_after_design_outcome_authorized": False,
    "route_switch_after_validation_outcome_authorized": False,
    "route_switch_after_oos_outcome_authorized": False,
}
_DESIGN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")
_TOP_LEVEL_KEYS = {
    "schema_version",
    "result_type",
    "authorization",
    "design_contract",
    "source_interpretation",
    "source_disposition",
    "status",
    "route_selection",
    "hypothesis_design",
    "policy_binding",
    "evidence_plan",
    "missing_proof",
    "safety_assertions",
    "seal",
}


class PositiveRouteHypothesisDesignError(ValueError):
    pass


def _fail(message: str) -> None:
    raise PositiveRouteHypothesisDesignError(message)


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        _fail(f"{label} keys changed")
    return value


def _file_bytes(path: Path, expected_sha256: str, label: str) -> bytes:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise PositiveRouteHypothesisDesignError(f"{label} is unavailable") from error
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        _fail(f"{label} hash changed")
    return raw


def _load_frozen_contracts() -> None:
    contract_raw = _file_bytes(CONTRACT_PATH, CONTRACT_SHA256, "design contract")
    schema_raw = _file_bytes(RESULT_SCHEMA_PATH, RESULT_SCHEMA_SHA256, "result schema")
    policy_raw = _file_bytes(POLICY_PATH, POLICY_SHA256, "policy V3")
    interpretation_contract_raw = _file_bytes(
        INTERPRETATION_CONTRACT_PATH,
        INTERPRETATION_CONTRACT_SHA256,
        "interpretation contract",
    )
    interpretation_schema_raw = _file_bytes(
        INTERPRETATION_SCHEMA_PATH,
        INTERPRETATION_SCHEMA_SHA256,
        "interpretation schema",
    )
    route_raw = _file_bytes(
        ROUTE_CONTRACT_PATH,
        ROUTE_CONTRACT_SHA256,
        "intraday route contract",
    )
    try:
        contract = load_json_bytes_strict(contract_raw, "design contract")
        schema = load_json_bytes_strict(schema_raw, "result schema")
        policy = load_json_bytes_strict(policy_raw, "policy V3")
        interpretation_contract = load_json_bytes_strict(
            interpretation_contract_raw,
            "interpretation contract",
        )
        interpretation_schema = load_json_bytes_strict(
            interpretation_schema_raw,
            "interpretation schema",
        )
        route = load_json_bytes_strict(route_raw, "intraday route contract")
    except ValueError as error:
        raise PositiveRouteHypothesisDesignError(str(error)) from error

    if (
        contract.get("contract_id") != CONTRACT_ID
        or contract.get("result_contract", {}).get("schema_sha256")
        != RESULT_SCHEMA_SHA256
        or contract.get("source_interpretation", {}).get("contract_sha256")
        != INTERPRETATION_CONTRACT_SHA256
        or contract.get("positive_route_selection") != _CONTRACT_ROUTE_SELECTION
        or contract.get("policy_binding") != POLICY_BINDING
        or contract.get("evidence_plan") != EVIDENCE_PLAN
    ):
        _fail("design contract binding changed")
    if (
        schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema"
        or schema.get("$id")
        != "urn:agora-research:microstructure-positive-route-hypothesis-design-result:v2"
        or schema.get("additionalProperties") is not False
    ):
        _fail("result schema identity or strictness changed")
    expected_policy = {
        "policy_id": POLICY_BINDING["policy_id"],
        "primary_metric": POLICY_BINDING["primary_metric"],
        "required_metrics": POLICY_BINDING["required_metrics"],
        "constraints": POLICY_BINDING["constraints"],
    }
    objective = policy.get("objective")
    if not isinstance(objective, dict) or {
        "policy_id": policy.get("policy_id"),
        "primary_metric": objective.get("primary_metric"),
        "required_metrics": objective.get("required_metrics"),
        "constraints": objective.get("constraints"),
    } != expected_policy:
        _fail("policy V3 objective changed")
    if (
        interpretation_contract.get("contract_id") != INTERPRETATION_CONTRACT_ID
        or interpretation_contract.get("result_contract", {}).get("schema_sha256")
        != INTERPRETATION_SCHEMA_SHA256
        or interpretation_schema.get("$id")
        != "urn:agora-research:microstructure-forward-interpretation-result:v1"
    ):
        _fail("interpretation contract binding changed")
    if (
        route.get("contract_id") != ROUTE_ID
        or route.get("status") != "PREOUTCOME_TEMPLATE_NOT_INSTANTIATED"
        or route.get("forward_stages", {}).get("total_complete_utc_days") != 42
        or route.get("forward_stages", {}).get("complete_utc_days_per_stage") != 14
        or route.get("friction_ledger", {}).get(
            "planning_round_trip_friction_bps"
        )
        != "30.00"
    ):
        _fail("intraday route contract binding changed")


def _payload_sha256(value: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json_bytes(value, exclude_key="seal")).hexdigest()


def _source_binding(raw: bytes, interpretation: dict[str, Any]) -> dict[str, Any]:
    handoff = interpretation["source_handoff_result"]
    diagnostic = interpretation["source_diagnostic_result"]
    return {
        "result_type": INTERPRETATION_RESULT_TYPE,
        "result_schema_sha256": INTERPRETATION_SCHEMA_SHA256,
        "document_sha256": hashlib.sha256(raw).hexdigest(),
        "payload_sha256": interpretation["seal"]["payload_sha256"],
        "contract_id": INTERPRETATION_CONTRACT_ID,
        "contract_sha256": INTERPRETATION_CONTRACT_SHA256,
        "handoff_document_sha256": handoff["document_sha256"],
        "handoff_payload_sha256": handoff["payload_sha256"],
        "diagnostic_contract_id": diagnostic["contract_id"],
        "diagnostic_contract_sha256": diagnostic["contract_sha256"],
        "diagnostic_payload_sha256": diagnostic["payload_sha256"],
        "diagnostic_document_sha256": diagnostic["canonical_document_sha256"],
        "selected_tier": interpretation["screen"]["selected_tier"],
    }


def _proposal(value: Any) -> dict[str, str]:
    proposal = _exact_keys(value, PROPOSAL_FIELDS, "Coach proposal")
    result: dict[str, str] = {}
    for name in sorted(PROPOSAL_FIELDS):
        item = proposal[name]
        if not isinstance(item, str) or not item.strip() or item != item.strip():
            _fail(f"Coach proposal {name} must be non-empty trimmed text")
        result[name] = item
    if _DESIGN_ID.fullmatch(result["design_id"]) is None:
        _fail("Coach proposal design_id is invalid")
    try:
        datetime.strptime(result["created_at"], "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise PositiveRouteHypothesisDesignError(
            "Coach proposal created_at must be canonical UTC seconds"
        ) from error
    return result


def _route_selection(selected_tier: str) -> dict[str, Any]:
    return {
        "route_id": ROUTE_ID,
        "route_contract_sha256": ROUTE_CONTRACT_SHA256,
        "priority": "SOLE_PRIMARY",
        "source_selected_tier": selected_tier,
        "maximum_routes": 1,
        "maximum_designs": 1,
        "maximum_eventual_candidate_variants": 1,
        "caller_override_authorized": False,
        "multiple_routes_authorized": False,
        "dra_fallback_authorized": False,
        "route_switch_after_design_outcome_authorized": False,
        "route_switch_after_validation_outcome_authorized": False,
        "route_switch_after_oos_outcome_authorized": False,
    }


def _hypothesis_design(proposal: dict[str, str], selected_tier: str) -> dict[str, Any]:
    return {
        **proposal,
        "route_id": ROUTE_ID,
        "route_contract_sha256": ROUTE_CONTRACT_SHA256,
        "source_selected_tier": selected_tier,
        "required_capability": REQUIRED_CAPABILITY,
        "proposed_mechanism": {
            "mechanism_id": "SELECTED_MICROSTRUCTURE_TIER_STANDALONE_60M_LONG_V1",
            "route_id": ROUTE_ID,
            "source_selected_tier": selected_tier,
            "market": "OKX_BTC_USDT_SPOT",
            "direction": "LONG_ONLY",
            "holding_period_minutes": 60,
            "threshold_tuning_authorized": False,
            "magnitude_claim_authorized": False,
            "more_complex_tier_selection_authorized": False,
        },
        "maximum_routes": 1,
        "maximum_designs": 1,
        "maximum_candidate_variants": 1,
    }


def build_positive_route_hypothesis_design_result_bytes(
    interpretation_raw: bytes,
    proposal: dict[str, Any] | None = None,
) -> bytes:
    if not isinstance(interpretation_raw, bytes):
        _fail("interpretation must be canonical bytes")
    _load_frozen_contracts()
    interpretation = validate_interpretation_result_bytes(interpretation_raw)
    disposition = interpretation["disposition"]
    selected_tier = interpretation["screen"]["selected_tier"]
    if disposition == POSITIVE_DISPOSITION:
        if proposal is None:
            _fail("positive interpretation requires exactly one Coach proposal")
        proposal_value = _proposal(proposal)
        if selected_tier not in TIER_ORDER:
            _fail("positive interpretation has no selected tier")
        status = "DESIGN_ONLY_NOT_REGISTERED"
        route_selection: dict[str, Any] | None = _route_selection(selected_tier)
        design: dict[str, Any] | None = _hypothesis_design(
            proposal_value,
            selected_tier,
        )
        evidence_plan: dict[str, Any] | None = deepcopy(EVIDENCE_PLAN)
    elif disposition in NON_POSITIVE_DISPOSITIONS:
        if proposal is not None:
            _fail("non-positive interpretation forbids a Coach proposal")
        status = "CLOSED_NO_HYPOTHESIS_DESIGN"
        route_selection = None
        design = None
        evidence_plan = None
    else:
        _fail("interpretation disposition is unsupported")

    result: dict[str, Any] = {
        "schema_version": "2",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "design_contract": {
            "contract_id": CONTRACT_ID,
            "sha256": CONTRACT_SHA256,
        },
        "source_interpretation": _source_binding(
            interpretation_raw,
            interpretation,
        ),
        "source_disposition": disposition,
        "status": status,
        "route_selection": route_selection,
        "hypothesis_design": design,
        "policy_binding": deepcopy(POLICY_BINDING),
        "evidence_plan": evidence_plan,
        "missing_proof": dict(MISSING_PROOF),
        "safety_assertions": dict(SAFETY_ASSERTIONS),
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }
    raw = canonical_json_bytes(result)
    validate_positive_route_hypothesis_design_result_bytes(
        raw,
        interpretation_raw,
    )
    return raw


def _validate_design(value: Any, selected_tier: str) -> None:
    expected = PROPOSAL_FIELDS | {
        "route_id",
        "route_contract_sha256",
        "source_selected_tier",
        "required_capability",
        "proposed_mechanism",
        "maximum_routes",
        "maximum_designs",
        "maximum_candidate_variants",
    }
    design = _exact_keys(value, expected, "hypothesis_design")
    _proposal({name: design[name] for name in PROPOSAL_FIELDS})
    if (
        design["route_id"] != ROUTE_ID
        or design["route_contract_sha256"] != ROUTE_CONTRACT_SHA256
        or design["source_selected_tier"] != selected_tier
        or design["required_capability"] != REQUIRED_CAPABILITY
        or design["maximum_routes"] != 1
        or design["maximum_designs"] != 1
        or design["maximum_candidate_variants"] != 1
    ):
        _fail("positive design route, capability, or cardinality changed")
    expected_mechanism = {
        "mechanism_id": "SELECTED_MICROSTRUCTURE_TIER_STANDALONE_60M_LONG_V1",
        "route_id": ROUTE_ID,
        "source_selected_tier": selected_tier,
        "market": "OKX_BTC_USDT_SPOT",
        "direction": "LONG_ONLY",
        "holding_period_minutes": 60,
        "threshold_tuning_authorized": False,
        "magnitude_claim_authorized": False,
        "more_complex_tier_selection_authorized": False,
    }
    if design["proposed_mechanism"] != expected_mechanism:
        _fail("positive design mechanism changed")


def validate_positive_route_hypothesis_design_result_bytes(
    raw: bytes,
    interpretation_raw: bytes,
) -> dict[str, Any]:
    if not isinstance(raw, bytes) or not isinstance(interpretation_raw, bytes):
        _fail("design and interpretation must be canonical bytes")
    _load_frozen_contracts()
    interpretation = validate_interpretation_result_bytes(interpretation_raw)
    try:
        result = load_json_bytes_strict(raw, "positive route hypothesis design result")
    except ValueError as error:
        raise PositiveRouteHypothesisDesignError(str(error)) from error
    if raw != canonical_json_bytes(result):
        _fail("positive route hypothesis design result bytes must be canonical compact UTF-8 JSON")
    _exact_keys(result, _TOP_LEVEL_KEYS, "positive route hypothesis design result")
    if (
        result["schema_version"] != "2"
        or result["result_type"] != RESULT_TYPE
        or result["authorization"] != AUTHORIZATION
        or result["design_contract"]
        != {"contract_id": CONTRACT_ID, "sha256": CONTRACT_SHA256}
    ):
        _fail("positive route hypothesis design result identity changed")
    if result["source_interpretation"] != _source_binding(
        interpretation_raw,
        interpretation,
    ):
        _fail("source interpretation binding changed")
    disposition = interpretation["disposition"]
    selected_tier = interpretation["screen"]["selected_tier"]
    if result["source_disposition"] != disposition:
        _fail("source disposition changed")
    if result["policy_binding"] != POLICY_BINDING:
        _fail("policy binding changed")
    if result["missing_proof"] != MISSING_PROOF:
        _fail("missing-proof boundary changed")
    if result["safety_assertions"] != SAFETY_ASSERTIONS:
        _fail("safety authorization boundary changed")
    if disposition == POSITIVE_DISPOSITION:
        if (
            result["status"] != "DESIGN_ONLY_NOT_REGISTERED"
            or selected_tier not in TIER_ORDER
            or result["route_selection"] != _route_selection(selected_tier)
            or result["evidence_plan"] != EVIDENCE_PLAN
        ):
            _fail("positive standalone route branch changed")
        _validate_design(result["hypothesis_design"], selected_tier)
    elif disposition in NON_POSITIVE_DISPOSITIONS:
        if (
            result["status"] != "CLOSED_NO_HYPOTHESIS_DESIGN"
            or result["route_selection"] is not None
            or result["hypothesis_design"] is not None
            or result["evidence_plan"] is not None
            or selected_tier is not None
        ):
            _fail("non-positive design closure changed")
    else:
        _fail("source disposition is unsupported")
    if result["seal"] != {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }:
        _fail("positive route hypothesis design result seal changed")
    return result
