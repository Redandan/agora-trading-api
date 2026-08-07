from __future__ import annotations

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


CONTRACT_ID = "OKX_MICROSTRUCTURE_HYPOTHESIS_DESIGN_V1"
CONTRACT_SHA256 = "d3e3df7d629938a33cddec00f251bbaaefb4ce17b51eb0b0b558061c692f6948"
RESULT_TYPE = "OKX_MICROSTRUCTURE_HYPOTHESIS_DESIGN_RESULT_V1"
RESULT_SCHEMA_SHA256 = "af82d3aa81257eb74cf04026fc9a43ae5c0576049d850b3263b90b7f2930e63d"
POLICY_SHA256 = "a82ccff13c13765d1e94a29698a43b35b847ed19190965590fa72e9a102981f6"
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)

CONTRACT_PATH = Path(__file__).with_name(
    "okx-microstructure-hypothesis-design-contract.v1.json"
)
RESULT_SCHEMA_PATH = Path(__file__).with_name(
    "microstructure-hypothesis-design-result.v1.schema.json"
)
POLICY_PATH = Path(__file__).with_name("policy.v3.json")

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
PARENT = {
    "key": "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH",
    "status": "PROPOSED_PENDING_CLOCK_AND_FEATURE_COMPATIBILITY",
}
REQUIRED_CAPABILITY = (
    "DRA_MICROSTRUCTURE_ENTRY_ADMISSION_ADAPTER_V1_NOT_IMPLEMENTED"
)
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
    "discovery_window_role": "DISCOVERY_ONLY_NOT_OOS",
    "discovery_window_reuse_as_oos": False,
    "hypothesis_and_manifest_freeze_before_future_oos_start": True,
    "future_candidate_oos_window": "SEPARATELY_FROZEN_AFTER_DESIGN_AND_MANIFEST",
    "exact_oos_dates": "MISSING_PROOF_PENDING_CANONICAL_REGISTRATION",
    "required_future_evaluations": [
        "realized_pnl",
        "unrealized_pnl",
        "total_pnl",
        "drawdown_path",
        "capital_utilization",
        "blocked_entries",
        "holding_age",
        "year_and_regime_breadth",
        "terminal_inventory",
        "fees",
        "slippage",
        "fills",
        "capacity",
    ],
}
MISSING_PROOF = {
    "strategy_pnl": "MISSING_PROOF",
    "drawdown": "MISSING_PROOF",
    "capital_utilization": "MISSING_PROOF",
    "holding_risk": "MISSING_PROOF",
    "fees_slippage_fills_capacity": "MISSING_PROOF",
    "dra_clock_compatibility": "MISSING_PROOF",
    "adapter_readiness": "MISSING_PROOF",
    "candidate_readiness": "MISSING_PROOF",
    "oos_value": "MISSING_PROOF",
}
SAFETY_ASSERTIONS = {
    "canonical_state_write_authorized": False,
    "research_state_write_authorized": False,
    "candidate_registration_authorized": False,
    "oos_access_authorized": False,
    "activation_authorized": False,
    "second_timer_or_writer_authorized": False,
    "trading_database_order_fund_action_authorized": False,
    "paid_api_authorized": False,
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
    "hypothesis_design",
    "policy_binding",
    "evidence_plan",
    "missing_proof",
    "safety_assertions",
    "seal",
}


class HypothesisDesignContractError(ValueError):
    pass


def _fail(message: str) -> None:
    raise HypothesisDesignContractError(message)


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        _fail(f"{label} keys changed")
    return value


def _file_bytes(path: Path, expected_sha256: str, label: str) -> bytes:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise HypothesisDesignContractError(f"{label} is unavailable") from error
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        _fail(f"{label} hash changed")
    return raw


def _load_frozen_contracts() -> None:
    contract_raw = _file_bytes(CONTRACT_PATH, CONTRACT_SHA256, "design contract")
    schema_raw = _file_bytes(RESULT_SCHEMA_PATH, RESULT_SCHEMA_SHA256, "result schema")
    policy_raw = _file_bytes(POLICY_PATH, POLICY_SHA256, "policy V3")
    try:
        contract = load_json_bytes_strict(contract_raw, "design contract")
        load_json_bytes_strict(schema_raw, "result schema")
        policy = load_json_bytes_strict(policy_raw, "policy V3")
    except ValueError as error:
        raise HypothesisDesignContractError(str(error)) from error
    if (
        contract.get("contract_id") != CONTRACT_ID
        or contract.get("result_contract", {}).get("schema_sha256")
        != RESULT_SCHEMA_SHA256
        or contract.get("source_interpretation", {}).get("contract_sha256")
        != INTERPRETATION_CONTRACT_SHA256
        or contract.get("policy_binding") != POLICY_BINDING
    ):
        _fail("design contract binding changed")
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
        raise HypothesisDesignContractError(
            "Coach proposal created_at must be canonical UTC seconds"
        ) from error
    return result


def _hypothesis_design(proposal: dict[str, str], selected_tier: str) -> dict[str, Any]:
    return {
        **proposal,
        "proposed_parent": dict(PARENT),
        "required_capability": REQUIRED_CAPABILITY,
        "proposed_mechanism": {
            "mechanism_id": "DRA_SELECTED_MICROSTRUCTURE_TIER_ENTRY_ADMISSION_V1",
            "source_selected_tier": selected_tier,
            "direction": "ENTRY_ADMISSION_ONLY",
            "threshold_tuning_authorized": False,
            "magnitude_claim_authorized": False,
            "more_complex_tier_selection_authorized": False,
        },
        "maximum_candidate_variants": 1,
    }


def build_hypothesis_design_result_bytes(
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
        design = _hypothesis_design(proposal_value, selected_tier)
        evidence_plan: dict[str, Any] | None = dict(EVIDENCE_PLAN)
        evidence_plan["required_future_evaluations"] = list(
            EVIDENCE_PLAN["required_future_evaluations"]
        )
    elif disposition in NON_POSITIVE_DISPOSITIONS:
        if proposal is not None:
            _fail("non-positive interpretation forbids a Coach proposal")
        status = "CLOSED_NO_HYPOTHESIS_DESIGN"
        design = None
        evidence_plan = None
    else:
        _fail("interpretation disposition is unsupported")

    result: dict[str, Any] = {
        "schema_version": "1",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "design_contract": {
            "contract_id": CONTRACT_ID,
            "sha256": CONTRACT_SHA256,
        },
        "source_interpretation": _source_binding(
            interpretation_raw, interpretation
        ),
        "source_disposition": disposition,
        "status": status,
        "hypothesis_design": design,
        "policy_binding": {
            **POLICY_BINDING,
            "required_metrics": list(POLICY_BINDING["required_metrics"]),
            "constraints": list(POLICY_BINDING["constraints"]),
        },
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
    validate_hypothesis_design_result_bytes(raw, interpretation_raw)
    return raw


def _validate_design(value: Any, selected_tier: str) -> None:
    expected = PROPOSAL_FIELDS | {
        "proposed_parent",
        "required_capability",
        "proposed_mechanism",
        "maximum_candidate_variants",
    }
    design = _exact_keys(value, expected, "hypothesis_design")
    proposal = {name: design[name] for name in PROPOSAL_FIELDS}
    _proposal(proposal)
    if (
        design["proposed_parent"] != PARENT
        or design["required_capability"] != REQUIRED_CAPABILITY
        or design["maximum_candidate_variants"] != 1
    ):
        _fail("positive design parent, capability, or variant bound changed")
    mechanism = {
        "mechanism_id": "DRA_SELECTED_MICROSTRUCTURE_TIER_ENTRY_ADMISSION_V1",
        "source_selected_tier": selected_tier,
        "direction": "ENTRY_ADMISSION_ONLY",
        "threshold_tuning_authorized": False,
        "magnitude_claim_authorized": False,
        "more_complex_tier_selection_authorized": False,
    }
    if design["proposed_mechanism"] != mechanism:
        _fail("positive design mechanism changed")


def validate_hypothesis_design_result_bytes(
    raw: bytes,
    interpretation_raw: bytes,
) -> dict[str, Any]:
    if not isinstance(raw, bytes) or not isinstance(interpretation_raw, bytes):
        _fail("design and interpretation must be canonical bytes")
    _load_frozen_contracts()
    interpretation = validate_interpretation_result_bytes(interpretation_raw)
    try:
        result = load_json_bytes_strict(raw, "hypothesis design result")
    except ValueError as error:
        raise HypothesisDesignContractError(str(error)) from error
    if raw != canonical_json_bytes(result):
        _fail("hypothesis design result bytes must be canonical compact UTF-8 JSON")
    _exact_keys(result, _TOP_LEVEL_KEYS, "hypothesis design result")
    if (
        result["schema_version"] != "1"
        or result["result_type"] != RESULT_TYPE
        or result["authorization"] != AUTHORIZATION
        or result["design_contract"]
        != {"contract_id": CONTRACT_ID, "sha256": CONTRACT_SHA256}
    ):
        _fail("hypothesis design result identity changed")
    if result["source_interpretation"] != _source_binding(
        interpretation_raw, interpretation
    ):
        _fail("source interpretation binding changed")
    disposition = interpretation["disposition"]
    selected_tier = interpretation["screen"]["selected_tier"]
    if result["source_disposition"] != disposition:
        _fail("source disposition changed")
    expected_policy = {
        **POLICY_BINDING,
        "required_metrics": list(POLICY_BINDING["required_metrics"]),
        "constraints": list(POLICY_BINDING["constraints"]),
    }
    if result["policy_binding"] != expected_policy:
        _fail("policy binding changed")
    if result["missing_proof"] != MISSING_PROOF:
        _fail("missing-proof boundary changed")
    if result["safety_assertions"] != SAFETY_ASSERTIONS:
        _fail("safety authorization boundary changed")
    if disposition == POSITIVE_DISPOSITION:
        if (
            result["status"] != "DESIGN_ONLY_NOT_REGISTERED"
            or selected_tier not in TIER_ORDER
            or result["evidence_plan"] != EVIDENCE_PLAN
        ):
            _fail("positive design branch changed")
        _validate_design(result["hypothesis_design"], selected_tier)
    elif disposition in NON_POSITIVE_DISPOSITIONS:
        if (
            result["status"] != "CLOSED_NO_HYPOTHESIS_DESIGN"
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
        _fail("hypothesis design result seal changed")
    return result
