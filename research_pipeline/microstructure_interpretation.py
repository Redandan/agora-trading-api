from __future__ import annotations

from decimal import Decimal
import hashlib
from typing import Any

from research_pipeline.microstructure_diagnostic import AUTHORIZATION
from research_pipeline.microstructure_handoff import (
    HandoffContext,
    RESULT_TYPE as HANDOFF_RESULT_TYPE,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    canonical_json_bytes,
    load_json_bytes_strict,
)


CONTRACT_ID = "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_V1"
CONTRACT_SHA256 = "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509"
RESULT_SCHEMA_SHA256 = "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564"
HANDOFF_RESULT_SCHEMA_SHA256 = (
    "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9"
)
RESULT_TYPE = "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_RESULT_V1"
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
PRIMARY_HORIZON = "60"
CONFIRMATORY_HORIZON = "15"
DESCRIPTIVE_HORIZONS = (5, 240, 1440)
TIER_ORDER = (
    "MIDLINE_RATIO_1_5_ONLY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY",
    "MIDLINE_RATIO_1_5_PLUS_NET_TAKER_BUY_PLUS_BOOK_SUPPORT",
)
DISPOSITIONS = {
    "READY_FOR_ONE_HYPOTHESIS_DESIGN",
    "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE",
    "AMBIGUOUS_NO_HYPOTHESIS",
    "INSUFFICIENT_FORWARD_EVIDENCE",
}
INFERENCE_BOUNDARIES = {
    "positive_disposition_authority": (
        "ONE_SEPARATELY_FROZEN_HYPOTHESIS_DESIGN_TASK_ONLY"
    ),
    "statistical_significance": "MISSING_PROOF",
    "raw_message_producer_correctness": "MISSING_PROOF",
    "dra_clock_feature_compatibility": "MISSING_PROOF",
    "fees_slippage_fills_capacity": "MISSING_PROOF",
    "pnl_drawdown_utilization_holding_risk": "MISSING_PROOF",
    "candidate_readiness": "MISSING_PROOF",
    "oos_value": "MISSING_PROOF",
    "canonical_state_write_authorized": False,
    "candidate_registration_authorized": False,
    "oos_access_authorized": False,
    "activation_authorized": False,
}

_SCREEN_METRICS = (
    "median_return_bps",
    "positive_return_share_pct",
    "matched_median_return_delta_bps",
)
_READINESS_GATES = {
    "minimum_30_events",
    "minimum_10_events_first_seven_days",
    "minimum_10_events_second_seven_days",
    "minimum_80_pct_matched_controls",
}
_SHA256 = set("0123456789abcdef")


class InterpretationContractError(ValueError):
    pass


def _fail(message: str) -> None:
    raise InterpretationContractError(message)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(f"{label} must be an object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        _fail(f"{label} keys changed")


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or len(value) != 64 or set(value) - _SHA256:
        _fail(f"{label} must be lowercase SHA-256")
    return value


def _payload_sha256(value: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json_bytes(value, exclude_key="seal")).hexdigest()


def _global_eligible(diagnostic: dict[str, Any]) -> bool:
    if (
        diagnostic["status"] != "FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION"
        or diagnostic["entry_reference"] != "NEXT_COMPLETE_MINUTE_OPEN"
        or diagnostic["fees_and_slippage"] != "NOT_APPLIED_DIAGNOSTIC_NOT_PNL"
    ):
        return False
    for tier_name in TIER_ORDER:
        tier = diagnostic["tiers"][tier_name]
        gates = tier["gates"]
        if (
            tier["gate_status"] != "PASS"
            or set(gates) != _READINESS_GATES
            or any(value is not True for value in gates.values())
        ):
            return False
        for horizon in (CONFIRMATORY_HORIZON, PRIMARY_HORIZON):
            metrics = tier["metrics_by_horizon_minutes"][horizon]
            if any(metrics[name] is None for name in _SCREEN_METRICS):
                return False
    return True


def _horizon_state(metrics: dict[str, Any]) -> str:
    median_return = Decimal(metrics["median_return_bps"])
    positive_share = Decimal(metrics["positive_return_share_pct"])
    matched_delta = Decimal(metrics["matched_median_return_delta_bps"])
    if median_return > 0 and positive_share > Decimal("50.00") and matched_delta > 0:
        return "POSITIVE"
    if median_return <= 0 and positive_share <= Decimal("50.00") and matched_delta <= 0:
        return "NEGATIVE"
    return "MIXED"


def _tier_evaluation(tier: dict[str, Any]) -> dict[str, str]:
    confirmatory = _horizon_state(
        tier["metrics_by_horizon_minutes"][CONFIRMATORY_HORIZON]
    )
    primary = _horizon_state(tier["metrics_by_horizon_minutes"][PRIMARY_HORIZON])
    if confirmatory == primary == "POSITIVE":
        disposition = "PASS"
    elif confirmatory == primary == "NEGATIVE":
        disposition = "REJECT"
    else:
        disposition = "AMBIGUOUS"
    return {
        "confirmatory_horizon_state": confirmatory,
        "primary_horizon_state": primary,
        "tier_disposition": disposition,
    }


def _incomplete_evaluations() -> dict[str, dict[str, None]]:
    return {
        tier: {
            "confirmatory_horizon_state": None,
            "primary_horizon_state": None,
            "tier_disposition": None,
        }
        for tier in TIER_ORDER
    }


def interpret_handoff_result_bytes(raw: bytes, context: HandoffContext) -> bytes:
    if not isinstance(raw, bytes):
        _fail("raw handoff result must be bytes")
    if not isinstance(context, HandoffContext):
        _fail("context must be a caller-supplied HandoffContext")
    handoff = validate_handoff_result_bytes(raw, context)
    diagnostic = handoff["diagnostic_result"]
    eligible = _global_eligible(diagnostic)
    selected_tier: str | None = None
    if eligible:
        evaluations = {
            tier: _tier_evaluation(diagnostic["tiers"][tier])
            for tier in TIER_ORDER
        }
        selected_tier = next(
            (
                tier
                for tier in TIER_ORDER
                if evaluations[tier]["tier_disposition"] == "PASS"
            ),
            None,
        )
        if selected_tier is not None:
            disposition = "READY_FOR_ONE_HYPOTHESIS_DESIGN"
        elif all(
            evaluation["tier_disposition"] == "REJECT"
            for evaluation in evaluations.values()
        ):
            disposition = "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE"
        else:
            disposition = "AMBIGUOUS_NO_HYPOTHESIS"
    else:
        evaluations = _incomplete_evaluations()
        disposition = "INSUFFICIENT_FORWARD_EVIDENCE"

    result: dict[str, Any] = {
        "schema_version": "1",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "interpretation_contract": {
            "contract_id": CONTRACT_ID,
            "sha256": CONTRACT_SHA256,
        },
        "source_handoff_result": {
            "schema_version": handoff["schema_version"],
            "result_type": handoff["result_type"],
            "schema_sha256": HANDOFF_RESULT_SCHEMA_SHA256,
            "document_sha256": hashlib.sha256(raw).hexdigest(),
            "payload_sha256": handoff["seal"]["payload_sha256"],
        },
        "source_diagnostic_result": {
            "contract_id": diagnostic["contract_id"],
            "contract_sha256": diagnostic["contract_file_sha256"],
            "payload_sha256": handoff["diagnostic_payload_hashes"]["payload_sha256"],
            "canonical_document_sha256": handoff["diagnostic_payload_hashes"][
                "canonical_document_sha256"
            ],
        },
        "screen": {
            "global_eligibility": "PASS" if eligible else "INCOMPLETE",
            "primary_horizon_minutes": int(PRIMARY_HORIZON),
            "confirmatory_horizon_minutes": int(CONFIRMATORY_HORIZON),
            "descriptive_only_horizons_minutes": list(DESCRIPTIVE_HORIZONS),
            "tier_order": list(TIER_ORDER),
            "tier_evaluations": evaluations,
            "selected_tier": selected_tier,
        },
        "disposition": disposition,
        "inference_boundaries": dict(INFERENCE_BOUNDARIES),
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }
    result_bytes = canonical_json_bytes(result)
    validate_interpretation_result_bytes(result_bytes)
    return result_bytes


def validate_interpretation_result_bytes(raw: bytes) -> dict[str, Any]:
    try:
        result = load_json_bytes_strict(raw, "interpretation result")
    except ValueError as error:
        raise InterpretationContractError(str(error)) from error
    if raw != canonical_json_bytes(result):
        _fail("interpretation result bytes must be canonical compact UTF-8 JSON")
    _exact_keys(
        result,
        {
            "schema_version",
            "result_type",
            "authorization",
            "interpretation_contract",
            "source_handoff_result",
            "source_diagnostic_result",
            "screen",
            "disposition",
            "inference_boundaries",
            "seal",
        },
        "interpretation result",
    )
    if (
        result["schema_version"] != "1"
        or result["result_type"] != RESULT_TYPE
        or result["authorization"] != AUTHORIZATION
        or result["disposition"] not in DISPOSITIONS
    ):
        _fail("interpretation result identity or disposition changed")
    if result["interpretation_contract"] != {
        "contract_id": CONTRACT_ID,
        "sha256": CONTRACT_SHA256,
    }:
        _fail("interpretation contract binding changed")

    handoff = _object(result["source_handoff_result"], "source_handoff_result")
    _exact_keys(
        handoff,
        {"schema_version", "result_type", "schema_sha256", "document_sha256", "payload_sha256"},
        "source_handoff_result",
    )
    if (
        handoff["schema_version"] != "1"
        or handoff["result_type"] != HANDOFF_RESULT_TYPE
        or handoff["schema_sha256"] != HANDOFF_RESULT_SCHEMA_SHA256
    ):
        _fail("source handoff result contract changed")
    _sha256(handoff["document_sha256"], "source handoff document hash")
    _sha256(handoff["payload_sha256"], "source handoff payload hash")

    diagnostic = _object(result["source_diagnostic_result"], "source_diagnostic_result")
    _exact_keys(
        diagnostic,
        {"contract_id", "contract_sha256", "payload_sha256", "canonical_document_sha256"},
        "source_diagnostic_result",
    )
    if (
        diagnostic["contract_id"] != "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3"
        or diagnostic["contract_sha256"] != V3_DIAGNOSTIC_CONTRACT_SHA256
    ):
        _fail("source diagnostic contract changed")
    _sha256(diagnostic["payload_sha256"], "source diagnostic payload hash")
    _sha256(diagnostic["canonical_document_sha256"], "source diagnostic document hash")

    screen = _object(result["screen"], "screen")
    _exact_keys(
        screen,
        {
            "global_eligibility",
            "primary_horizon_minutes",
            "confirmatory_horizon_minutes",
            "descriptive_only_horizons_minutes",
            "tier_order",
            "tier_evaluations",
            "selected_tier",
        },
        "screen",
    )
    if (
        screen["primary_horizon_minutes"] != 60
        or screen["confirmatory_horizon_minutes"] != 15
        or screen["descriptive_only_horizons_minutes"] != list(DESCRIPTIVE_HORIZONS)
        or screen["tier_order"] != list(TIER_ORDER)
    ):
        _fail("screen contract changed")
    evaluations = _object(screen["tier_evaluations"], "tier_evaluations")
    _exact_keys(evaluations, set(TIER_ORDER), "tier_evaluations")
    selected = screen["selected_tier"]
    if selected is not None and selected not in TIER_ORDER:
        _fail("selected tier is invalid")
    if screen["global_eligibility"] == "INCOMPLETE":
        if result["disposition"] != "INSUFFICIENT_FORWARD_EVIDENCE" or selected is not None:
            _fail("incomplete result disposition changed")
        for tier in TIER_ORDER:
            item = _object(evaluations[tier], f"tier_evaluations.{tier}")
            _exact_keys(
                item,
                {"confirmatory_horizon_state", "primary_horizon_state", "tier_disposition"},
                f"tier_evaluations.{tier}",
            )
            if any(value is not None for value in item.values()):
                _fail("incomplete result must not expose tier classifications")
    elif screen["global_eligibility"] == "PASS":
        tier_states: list[str] = []
        for tier in TIER_ORDER:
            item = _object(evaluations[tier], f"tier_evaluations.{tier}")
            _exact_keys(
                item,
                {"confirmatory_horizon_state", "primary_horizon_state", "tier_disposition"},
                f"tier_evaluations.{tier}",
            )
            if item["confirmatory_horizon_state"] not in {"POSITIVE", "NEGATIVE", "MIXED"}:
                _fail("confirmatory horizon state changed")
            if item["primary_horizon_state"] not in {"POSITIVE", "NEGATIVE", "MIXED"}:
                _fail("primary horizon state changed")
            if item["tier_disposition"] not in {"PASS", "REJECT", "AMBIGUOUS"}:
                _fail("tier disposition changed")
            if (
                item["confirmatory_horizon_state"]
                == item["primary_horizon_state"]
                == "POSITIVE"
            ):
                expected_tier_disposition = "PASS"
            elif (
                item["confirmatory_horizon_state"]
                == item["primary_horizon_state"]
                == "NEGATIVE"
            ):
                expected_tier_disposition = "REJECT"
            else:
                expected_tier_disposition = "AMBIGUOUS"
            if item["tier_disposition"] != expected_tier_disposition:
                _fail("tier disposition is inconsistent with horizon states")
            tier_states.append(item["tier_disposition"])
        first_pass = next(
            (tier for tier in TIER_ORDER if evaluations[tier]["tier_disposition"] == "PASS"),
            None,
        )
        expected = (
            "READY_FOR_ONE_HYPOTHESIS_DESIGN"
            if first_pass is not None
            else (
                "NO_CANDIDATE_SHORT_HORIZON_BUY_PRESSURE"
                if tier_states == ["REJECT", "REJECT", "REJECT"]
                else "AMBIGUOUS_NO_HYPOTHESIS"
            )
        )
        if selected != first_pass or result["disposition"] != expected:
            _fail("screen selection or disposition is inconsistent")
    else:
        _fail("global eligibility changed")

    if result["inference_boundaries"] != INFERENCE_BOUNDARIES:
        _fail("inference boundaries changed")
    seal = _object(result["seal"], "seal")
    if seal != {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }:
        _fail("interpretation result seal changed")
    return result
