from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
from decimal import Decimal, localcontext
import hashlib
import re
from statistics import median
from typing import Any, Sequence

from research_pipeline.microstructure_diagnostic import (
    TIER_KEYS,
    MinuteRecord,
    validate_day_bundle as validate_diagnostic_day_bundle,
)
from research_pipeline.microstructure_handoff import (
    HandoffContext,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_interpretation import (
    validate_interpretation_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    canonical_json_bytes,
    load_json_bytes_strict,
    validate_v3_day_bundle,
)


CONTRACT_ID = "OKX_MICROSTRUCTURE_DISCOVERY_ECONOMIC_VETO_V1"
CONTRACT_SHA256 = "8dd1ba498270237758be77d89b14819a2bd02b8d16e602aad54683e9ce1a8ffd"
CONTRACT_PAYLOAD_SHA256 = (
    "c48a55f1524af39ea9fb2738f5b4b456f2f8222937929ea6cc37568064aedb65"
)
RESULT_SCHEMA_SHA256 = (
    "19b914871f39b2703229e716332021f8be7932845cfa5de2f2ff0c52886b2771"
)
ROUTE_CONTRACT_SHA256 = (
    "33fdef52654845911eda5f9f0dc9a3d1281ae6a6e0d4c0aab1bc93b51f34304e"
)
HANDOFF_RESULT_SCHEMA_SHA256 = (
    "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9"
)
HANDOFF_MANIFEST_SCHEMA_SHA256 = (
    "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090"
)
INTERPRETATION_RESULT_SCHEMA_SHA256 = (
    "58b704babf80ed381d2cf1c50afb61cf9e5e73e8eac43fa88d0f26c7f724f564"
)
INTERPRETATION_CONTRACT_SHA256 = (
    "b3230b0b5e07a7cdf12b4e057c5e01a11c2ba36c8f2271d52552ceafec97b509"
)
DIAGNOSTIC_CONTRACT_SHA256 = (
    "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a"
)

RESULT_TYPE = "OKX_MICROSTRUCTURE_DISCOVERY_ECONOMIC_VETO_RESULT_V1"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
REQUIRED_DAYS = 14
MINUTES_PER_DAY = 1440
TOTAL_MINUTES = REQUIRED_DAYS * MINUTES_PER_DAY
HOLD_MINUTES = 60
ENTRY_OFFSET = 2
EXIT_OFFSET = 62
MIDLINE_RATIO = Decimal("1.50")
GROSS_ENTRY_USDT = Decimal("30.00")
ENTRY_SLIPPAGE_MULTIPLIER = Decimal("1.0005")
EXIT_SLIPPAGE_MULTIPLIER = Decimal("0.9995")
FEE_RATE = Decimal("0.0010")
RAW_BREAK_EVEN_BPS = "30.0550826113908"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DECIMAL = re.compile(r"^-?(0|[1-9][0-9]*)(\.[0-9]+)?$")

REQUIRED_FEATURE_FIELDS = [
    "trade_open_price",
    "above_mid_buy_quote_notional",
    "below_mid_sell_quote_notional",
    "net_taker_quote_notional",
    "average_book_imbalance",
    "bid_replenishment_quote_proxy",
]

INFERENCE_BOUNDARIES = {
    "evidence_role": "DISCOVERY_ONLY_NOT_DESIGN_VALIDATION_OR_OOS",
    "permitted_claim": "VETO_ROUTE_OR_PERMIT_SEPARATELY_FROZEN_LATER_V4_SLICE_ONLY",
    "proves_alpha": False,
    "authorizes_candidate": False,
    "authorizes_design_validation_or_oos": False,
    "authorizes_v4_source_or_manifest_creation": False,
    "authorizes_activation": False,
    "veto_closes_without_tuning": True,
    "permit_requires_separate_later_v4_freeze": True,
}

MISSING_PROOF = {
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
}

SAFETY_ASSERTIONS = {
    "canonical_state_changed": False,
    "research_state_changed": False,
    "server_research_mcp_write_attempted": False,
    "second_timer_created": False,
    "runner_or_economic_execution_attempted": False,
    "candidate_or_hypothesis_registered": False,
    "oos_opened": False,
    "trading_action_attempted": False,
    "paid_api_used": False,
}


class EconomicVetoError(ValueError):
    pass


@dataclass(frozen=True)
class DayEvidence:
    binding: dict[str, Any]
    bundle_raw: bytes
    envelope_raw: bytes


@dataclass(frozen=True)
class _Signal:
    index: int
    fold: int


@dataclass(frozen=True)
class _Trade:
    signal_index: int
    entry_index: int
    exit_index: int
    fold: int
    pnl_usdt: Decimal
    return_bps: Decimal


def _fail(message: str) -> None:
    raise EconomicVetoError(message)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _payload_sha256(value: dict[str, Any]) -> str:
    return _sha256(canonical_json_bytes(value, exclude_key="seal"))


def _decimal_text(value: Decimal) -> str:
    if not value.is_finite():
        _fail("non-finite economic value")
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    if text in {"", "-0"}:
        return "0"
    return text


def _require_hash(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        _fail(f"{label} must be a lowercase SHA-256")
    return value


def _validate_frozen_file(raw: bytes, expected: str, label: str) -> dict[str, Any]:
    if _sha256(raw) != expected:
        _fail(f"{label} hash changed")
    try:
        return load_json_bytes_strict(raw, label)
    except ValueError as error:
        raise EconomicVetoError(f"{label} is not strict JSON") from error


def _validate_contract_sources(
    contract_raw: bytes, result_schema_raw: bytes, route_contract_raw: bytes
) -> None:
    contract = _validate_frozen_file(contract_raw, CONTRACT_SHA256, "veto contract")
    _validate_frozen_file(result_schema_raw, RESULT_SCHEMA_SHA256, "veto result schema")
    _validate_frozen_file(route_contract_raw, ROUTE_CONTRACT_SHA256, "route contract")
    if contract.get("contract_id") != CONTRACT_ID:
        _fail("veto contract identity changed")
    seal = contract.get("seal")
    if not isinstance(seal, dict) or seal != {
        "algorithm": "SHA-256",
        "payload_sha256": CONTRACT_PAYLOAD_SHA256,
        "canonicalization": CANONICALIZATION,
    }:
        _fail("veto contract seal changed")
    if _payload_sha256(contract) != CONTRACT_PAYLOAD_SHA256:
        _fail("veto contract payload changed")


def _tier_matches(record: MinuteRecord, tier: str) -> bool:
    below = record.below_mid_sell_quote_notional
    if below <= 0:
        return False
    with localcontext() as context:
        context.prec = 50
        ratio = record.above_mid_buy_quote_notional / below
    if ratio < MIDLINE_RATIO:
        return False
    if tier == TIER_KEYS[0]:
        return True
    if record.net_taker_quote_notional <= 0:
        return False
    if tier == TIER_KEYS[1]:
        return True
    if tier != TIER_KEYS[2]:
        _fail("interpretation selected an unknown tier")
    return (
        record.average_book_imbalance > 0
        and record.bid_replenishment_quote_proxy > 0
    )


def _control_matches(record: MinuteRecord) -> bool:
    below = record.below_mid_sell_quote_notional
    if below <= 0:
        return False
    with localcontext() as context:
        context.prec = 50
        return record.above_mid_buy_quote_notional / below < MIDLINE_RATIO


def _friction_trade(
    records: Sequence[MinuteRecord], signal_index: int, fold: int
) -> _Trade:
    entry_index = signal_index + ENTRY_OFFSET
    exit_index = signal_index + EXIT_OFFSET
    if exit_index >= len(records):
        _fail("attempted to price a signal without a full m+62 exit")
    raw_entry = records[entry_index].trade_open_price
    raw_exit = records[exit_index].trade_open_price
    if raw_entry <= 0 or raw_exit <= 0:
        _fail("trade_open_price must be positive")
    with localcontext() as context:
        context.prec = 50
        entry_execution = raw_entry * ENTRY_SLIPPAGE_MULTIPLIER
        gross_base = GROSS_ENTRY_USDT / entry_execution
        buy_fee_base = gross_base * FEE_RATE
        net_base = gross_base - buy_fee_base
        exit_execution = raw_exit * EXIT_SLIPPAGE_MULTIPLIER
        gross_quote = net_base * exit_execution
        exit_fee_quote = gross_quote * FEE_RATE
        net_exit_quote = gross_quote - exit_fee_quote
        pnl = net_exit_quote - GROSS_ENTRY_USDT
        return_bps = pnl / GROSS_ENTRY_USDT * Decimal(10000)
    return _Trade(
        signal_index=signal_index,
        entry_index=entry_index,
        exit_index=exit_index,
        fold=fold,
        pnl_usdt=pnl,
        return_bps=return_bps,
    )


def _candidate_signals(
    records: Sequence[MinuteRecord], tier: str
) -> tuple[list[_Signal], int]:
    selected: list[_Signal] = []
    excluded_without_exit = 0
    last_signal: int | None = None
    for index, record in enumerate(records):
        if not _tier_matches(record, tier):
            continue
        if last_signal is not None and index - last_signal < HOLD_MINUTES:
            continue
        last_signal = index
        if index + EXIT_OFFSET >= len(records):
            excluded_without_exit += 1
            continue
        selected.append(
            _Signal(index=index, fold=0 if index < 7 * MINUTES_PER_DAY else 1)
        )
    return selected, excluded_without_exit


def _overlaps(intervals: Sequence[tuple[int, int]], entry: int, exit_: int) -> bool:
    return any(entry < old_exit and old_entry < exit_ for old_entry, old_exit in intervals)


def _match_controls(
    records: Sequence[MinuteRecord], candidates: Sequence[_Signal]
) -> list[tuple[_Signal, _Signal]]:
    used: set[int] = set()
    intervals: list[tuple[int, int]] = []
    pairs: list[tuple[_Signal, _Signal]] = []
    for candidate in candidates:
        candidate_day = candidate.index // MINUTES_PER_DAY
        minute_of_day = candidate.index % MINUTES_PER_DAY
        fold_start = 0 if candidate.fold == 0 else 7
        chosen: int | None = None
        for control_day in range(candidate_day - 1, fold_start - 1, -1):
            control_index = control_day * MINUTES_PER_DAY + minute_of_day
            entry = control_index + ENTRY_OFFSET
            exit_ = control_index + EXIT_OFFSET
            if control_index in used or exit_ >= len(records):
                continue
            if not _control_matches(records[control_index]):
                continue
            if _overlaps(intervals, entry, exit_):
                continue
            chosen = control_index
            break
        if chosen is None:
            continue
        used.add(chosen)
        intervals.append((chosen + ENTRY_OFFSET, chosen + EXIT_OFFSET))
        pairs.append((candidate, _Signal(index=chosen, fold=candidate.fold)))
    return pairs


def _max_drawdown(trades: Sequence[_Trade]) -> Decimal:
    equity = Decimal(0)
    peak = Decimal(0)
    drawdown = Decimal(0)
    for trade in sorted(trades, key=lambda value: (value.exit_index, value.entry_index)):
        equity += trade.pnl_usdt
        if equity > peak:
            peak = equity
        current = peak - equity
        if current > drawdown:
            drawdown = current
    return drawdown


def _metrics(
    records: Sequence[MinuteRecord],
    candidates: Sequence[_Signal],
    pairs: Sequence[tuple[_Signal, _Signal]],
    *,
    excluded_without_exit: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    candidate_trades = [_friction_trade(records, pair[0].index, pair[0].fold) for pair in pairs]
    control_trades = [_friction_trade(records, pair[1].index, pair[1].fold) for pair in pairs]
    candidate_total = sum((trade.pnl_usdt for trade in candidate_trades), Decimal(0))
    control_total = sum((trade.pnl_usdt for trade in control_trades), Decimal(0))
    deltas = [left.pnl_usdt - right.pnl_usdt for left, right in zip(candidate_trades, control_trades)]
    first_delta = sum(
        (delta for delta, trade in zip(deltas, candidate_trades) if trade.fold == 0),
        Decimal(0),
    )
    second_delta = sum(
        (delta for delta, trade in zip(deltas, candidate_trades) if trade.fold == 1),
        Decimal(0),
    )
    positive_deltas = [value for value in deltas if value > 0]
    with localcontext() as context:
        context.prec = 50
        coverage = (
            Decimal(len(pairs)) / Decimal(len(candidates)) * Decimal(100)
            if candidates
            else Decimal(0)
        )
        positive_share = (
            Decimal(sum(trade.pnl_usdt > 0 for trade in candidate_trades))
            / Decimal(len(candidate_trades))
            * Decimal(100)
            if candidate_trades
            else Decimal(0)
        )
        concentration = (
            max(positive_deltas) / sum(positive_deltas, Decimal(0)) * Decimal(100)
            if positive_deltas
            else Decimal(0)
        )
    candidate_median = (
        median([trade.return_bps for trade in candidate_trades])
        if candidate_trades
        else Decimal(0)
    )
    candidate_drawdown = _max_drawdown(candidate_trades)
    control_drawdown = _max_drawdown(control_trades)
    first_count = sum(candidate.fold == 0 for candidate in candidates)
    second_count = len(candidates) - first_count
    integrity = {
        "selected_tier_trade_count": len(candidates),
        "first_seven_day_trade_count": first_count,
        "second_seven_day_trade_count": second_count,
        "matched_control_coverage_pct": _decimal_text(coverage),
        "duplicate_control_count": 0,
        "cross_fold_label_count": 0,
        "integrity_anomaly_count": 0,
        "excluded_without_full_exit_count": excluded_without_exit,
        "candidate_terminal_inventory": "0",
        "control_terminal_inventory": "0",
    }
    economic = {
        "candidate_net_total_pnl_usdt": _decimal_text(candidate_total),
        "matched_control_net_total_pnl_usdt": _decimal_text(control_total),
        "candidate_minus_control_total_pnl_usdt": _decimal_text(candidate_total - control_total),
        "median_candidate_net_return_bps": _decimal_text(candidate_median),
        "positive_candidate_net_trade_share_pct": _decimal_text(positive_share),
        "candidate_max_drawdown_usdt": _decimal_text(candidate_drawdown),
        "matched_control_max_drawdown_usdt": _decimal_text(control_drawdown),
        "first_half_candidate_minus_control_pnl_usdt": _decimal_text(first_delta),
        "second_half_candidate_minus_control_pnl_usdt": _decimal_text(second_delta),
        "top_one_positive_incremental_contribution_pct": _decimal_text(concentration),
        "raw_break_even_hurdle_bps": RAW_BREAK_EVEN_BPS,
    }
    return integrity, economic


def _gate_evaluation(
    integrity: dict[str, Any], economic: dict[str, Any]
) -> dict[str, Any]:
    d = {key: Decimal(value) for key, value in economic.items()}
    integrity_gates = {
        "fourteen_contiguous_clean_days": True,
        "exactly_1440_valid_minutes_each_day": True,
        "bundle_hashes_and_chain_valid": True,
        "trade_open_price_and_feature_bytes_valid": True,
        "minimum_30_selected_tier_trades": integrity["selected_tier_trade_count"] >= 30,
        "minimum_10_trades_first_half": integrity["first_seven_day_trade_count"] >= 10,
        "minimum_10_trades_second_half": integrity["second_seven_day_trade_count"] >= 10,
        "minimum_80_pct_matched_control_coverage": Decimal(
            integrity["matched_control_coverage_pct"]
        ) >= Decimal("80.00"),
        "zero_duplicate_controls": integrity["duplicate_control_count"] == 0,
        "zero_cross_fold_labels": integrity["cross_fold_label_count"] == 0,
        "zero_integrity_anomalies": integrity["integrity_anomaly_count"] == 0,
        "zero_terminal_inventory": (
            Decimal(integrity["candidate_terminal_inventory"]) == 0
            and Decimal(integrity["control_terminal_inventory"]) == 0
        ),
    }
    integrity_gates["all_required_integrity_gates_passed"] = all(
        integrity_gates.values()
    )
    economic_gates = {
        "positive_candidate_net_total_pnl": d["candidate_net_total_pnl_usdt"] > 0,
        "positive_candidate_minus_control_total_pnl": d[
            "candidate_minus_control_total_pnl_usdt"
        ] > 0,
        "positive_median_candidate_net_return": d["median_candidate_net_return_bps"] > 0,
        "positive_trade_share_strictly_above_50_pct": d[
            "positive_candidate_net_trade_share_pct"
        ] > 50,
        "candidate_drawdown_no_worse_than_control": d[
            "candidate_max_drawdown_usdt"
        ] <= d["matched_control_max_drawdown_usdt"],
        "positive_first_half_candidate_minus_control": d[
            "first_half_candidate_minus_control_pnl_usdt"
        ] > 0,
        "positive_second_half_candidate_minus_control": d[
            "second_half_candidate_minus_control_pnl_usdt"
        ] > 0,
        "top_one_contribution_at_most_40_pct": d[
            "top_one_positive_incremental_contribution_pct"
        ] <= 40,
        "zero_terminal_inventory": integrity_gates["zero_terminal_inventory"],
    }
    economic_gates["all_required_economic_gates_passed"] = all(economic_gates.values())
    all_passed = (
        integrity_gates["all_required_integrity_gates_passed"]
        and economic_gates["all_required_economic_gates_passed"]
    )
    return {
        "integrity_metrics": integrity,
        "integrity_gates": integrity_gates,
        "economic_metrics": economic,
        "economic_gates": economic_gates,
        "all_required_gates_passed": all_passed,
    }


def evaluate_records(
    records: Sequence[MinuteRecord], selected_tier: str
) -> dict[str, Any]:
    """Pure ledger calculation used by the sealed-package evaluator and synthetic tests."""
    if selected_tier not in TIER_KEYS:
        _fail("selected tier is outside the frozen order")
    if len(records) != TOTAL_MINUTES:
        _fail("discovery ledger requires exactly 20,160 contiguous minutes")
    for index, record in enumerate(records):
        if index and record.minute - records[index - 1].minute != timedelta(minutes=1):
            _fail("discovery minutes are not contiguous")
    candidates, excluded = _candidate_signals(records, selected_tier)
    pairs = _match_controls(records, candidates)
    integrity, economic = _metrics(
        records, candidates, pairs, excluded_without_exit=excluded
    )
    return _gate_evaluation(integrity, economic)


def _validate_day_evidence(
    context: HandoffContext, days: Sequence[DayEvidence]
) -> tuple[list[MinuteRecord], list[dict[str, Any]]]:
    if len(days) != REQUIRED_DAYS or len(context.days) != REQUIRED_DAYS:
        _fail("exactly fourteen handoff days are required")
    records: list[MinuteRecord] = []
    inventory: list[dict[str, Any]] = []
    for expected, evidence in zip(context.days, days):
        if evidence.binding != expected:
            _fail("day evidence binding changed")
        bundle = load_json_bytes_strict(evidence.bundle_raw, "V3 day bundle")
        envelope = load_json_bytes_strict(evidence.envelope_raw, "V3 day envelope")
        source_result = validate_v3_day_bundle(bundle, raw_bytes=evidence.bundle_raw)
        diagnostic_day = validate_diagnostic_day_bundle(bundle)
        if (
            source_result["bundle_sha256"] != expected["bundle_sha256"]
            or bundle["seal"]["payload_sha256"] != expected["payload_sha256"]
            or _sha256(evidence.envelope_raw) != expected["envelope_sha256"]
            or diagnostic_day.day.isoformat() != expected["day"]
        ):
            _fail("day bundle or envelope hash binding changed")
        envelope_payload = envelope.get("seal", {}).get("payload_sha256")
        _require_hash(envelope_payload, "envelope payload hash")
        records.extend(diagnostic_day.records)
        inventory.append(
            {
                "day": expected["day"],
                "integrity_status": "CLEAN",
                "valid_minute_count": MINUTES_PER_DAY,
                "bundle_document_sha256": expected["bundle_sha256"],
                "bundle_payload_sha256": expected["payload_sha256"],
                "envelope_document_sha256": expected["envelope_sha256"],
                "envelope_payload_sha256": envelope_payload,
                "chain_sha256": expected["cumulative_chain_sha256"],
                "anomaly_count": 0,
            }
        )
    if len(records) != TOTAL_MINUTES:
        _fail("validated day inventory does not contain 20,160 minutes")
    return records, inventory


def evaluate_economic_veto(
    *,
    handoff_context: HandoffContext,
    handoff_result_raw: bytes,
    interpretation_result_raw: bytes,
    days: Sequence[DayEvidence],
    contract_raw: bytes,
    result_schema_raw: bytes,
    route_contract_raw: bytes,
) -> bytes:
    _validate_contract_sources(contract_raw, result_schema_raw, route_contract_raw)
    handoff = validate_handoff_result_bytes(handoff_result_raw, handoff_context)
    interpretation = validate_interpretation_result_bytes(interpretation_result_raw)
    handoff_document_hash = _sha256(handoff_result_raw)
    handoff_payload_hash = handoff["seal"]["payload_sha256"]
    interpretation_handoff = interpretation["source_handoff_result"]
    if (
        interpretation_handoff["document_sha256"] != handoff_document_hash
        or interpretation_handoff["payload_sha256"] != handoff_payload_hash
    ):
        _fail("interpretation is not bound to the exact handoff result")
    if interpretation["disposition"] != "READY_FOR_ONE_HYPOTHESIS_DESIGN":
        _fail("interpretation is not positive")
    selected_tier = interpretation["screen"]["selected_tier"]
    if selected_tier not in TIER_KEYS:
        _fail("interpretation selected tier is invalid")
    records, day_inventory = _validate_day_evidence(handoff_context, days)
    gates = evaluate_records(records, selected_tier)
    result: dict[str, Any] = {
        "schema_version": "1",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "contract_binding": {
            "contract_id": CONTRACT_ID,
            "document_sha256": CONTRACT_SHA256,
            "payload_sha256": CONTRACT_PAYLOAD_SHA256,
            "result_schema_sha256": RESULT_SCHEMA_SHA256,
        },
        "source_handoff": {
            "schema_version": "1",
            "result_type": "MICROSTRUCTURE_V3_CREATE_ONLY_HANDOFF_RESULT",
            "result_schema_sha256": HANDOFF_RESULT_SCHEMA_SHA256,
            "document_sha256": handoff_document_hash,
            "payload_sha256": handoff_payload_hash,
            "manifest_schema_sha256": HANDOFF_MANIFEST_SCHEMA_SHA256,
            "manifest_document_sha256": handoff["input_manifest"]["sha256"],
            "manifest_payload_sha256": handoff["input_manifest"]["payload_sha256"],
            "diagnostic_contract_id": "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_V3",
            "diagnostic_contract_sha256": DIAGNOSTIC_CONTRACT_SHA256,
        },
        "source_interpretation": {
            "result_type": "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_RESULT_V1",
            "result_schema_sha256": INTERPRETATION_RESULT_SCHEMA_SHA256,
            "document_sha256": _sha256(interpretation_result_raw),
            "payload_sha256": interpretation["seal"]["payload_sha256"],
            "contract_id": "OKX_MICROSTRUCTURE_FORWARD_INTERPRETATION_V1",
            "contract_sha256": INTERPRETATION_CONTRACT_SHA256,
            "handoff_document_sha256": handoff_document_hash,
            "handoff_payload_sha256": handoff_payload_hash,
            "disposition": "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            "selected_tier": selected_tier,
            "selection_rule": "FIRST_TIER_CLASSIFIED_PASS_IN_FROZEN_ORDER",
            "caller_override_authorized": False,
            "fallback_tier_authorized": False,
            "magnitude_ranking_authorized": False,
            "multi_tier_variants_authorized": False,
            "tuning_authorized": False,
        },
        "discovery_inventory": {
            "role": "DISCOVERY_ONLY_NOT_DESIGN_VALIDATION_OR_OOS",
            "complete_contiguous_utc_days": REQUIRED_DAYS,
            "valid_minutes_per_day": MINUTES_PER_DAY,
            "required_feature_fields": REQUIRED_FEATURE_FIELDS,
            "all_exported_raw_v3_bundles_used": True,
            "backfill_or_substitution_used": False,
            "cross_window_bytes_used": False,
            "days": day_inventory,
        },
        "gate_evaluation": gates,
        "disposition": (
            "PERMIT_LATER_V4" if gates["all_required_gates_passed"] else "VETO_BEFORE_V4"
        ),
        "inference_boundaries": INFERENCE_BOUNDARIES,
        "missing_proof": MISSING_PROOF,
        "safety_assertions": SAFETY_ASSERTIONS,
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }
    raw = canonical_json_bytes(result)
    validate_economic_veto_result_bytes(raw)
    return raw


def validate_economic_veto_result_bytes(raw: bytes) -> dict[str, Any]:
    try:
        result = load_json_bytes_strict(raw, "economic veto result")
    except ValueError as error:
        raise EconomicVetoError("economic veto result is not strict JSON") from error
    if canonical_json_bytes(result) != raw:
        _fail("economic veto result is not compact canonical JSON")
    expected_root = {
        "schema_version",
        "result_type",
        "authorization",
        "contract_binding",
        "source_handoff",
        "source_interpretation",
        "discovery_inventory",
        "gate_evaluation",
        "disposition",
        "inference_boundaries",
        "missing_proof",
        "safety_assertions",
        "seal",
    }
    if set(result) != expected_root:
        _fail("economic veto result root is not closed")
    if (
        result["schema_version"] != "1"
        or result["result_type"] != RESULT_TYPE
        or result["authorization"] != AUTHORIZATION
    ):
        _fail("economic veto result identity changed")
    if result["contract_binding"] != {
        "contract_id": CONTRACT_ID,
        "document_sha256": CONTRACT_SHA256,
        "payload_sha256": CONTRACT_PAYLOAD_SHA256,
        "result_schema_sha256": RESULT_SCHEMA_SHA256,
    }:
        _fail("economic veto contract binding changed")
    if result["inference_boundaries"] != INFERENCE_BOUNDARIES:
        _fail("economic veto inference boundary changed")
    if result["missing_proof"] != MISSING_PROOF:
        _fail("economic veto missing-proof boundary changed")
    if result["safety_assertions"] != SAFETY_ASSERTIONS:
        _fail("economic veto safety boundary changed")
    inventory = result["discovery_inventory"]
    if not isinstance(inventory, dict) or len(inventory.get("days", [])) != REQUIRED_DAYS:
        _fail("economic veto inventory is incomplete")
    interpretation = result["source_interpretation"]
    if interpretation.get("selected_tier") not in TIER_KEYS:
        _fail("economic veto selected tier changed")
    gate = result["gate_evaluation"]
    if not isinstance(gate, dict):
        _fail("economic veto gate evaluation is invalid")
    integrity_metrics = gate.get("integrity_metrics")
    economic_metrics = gate.get("economic_metrics")
    if not isinstance(integrity_metrics, dict) or not isinstance(economic_metrics, dict):
        _fail("economic veto metrics are invalid")
    for value in (
        integrity_metrics.get("matched_control_coverage_pct"),
        integrity_metrics.get("candidate_terminal_inventory"),
        integrity_metrics.get("control_terminal_inventory"),
        *economic_metrics.values(),
    ):
        if not isinstance(value, str) or _DECIMAL.fullmatch(value) is None:
            _fail("economic veto decimal is not canonical")
    integrity_gates = gate.get("integrity_gates")
    economic_gates = gate.get("economic_gates")
    if not isinstance(integrity_gates, dict) or not isinstance(economic_gates, dict):
        _fail("economic veto gates are invalid")
    integrity_all = all(
        value for key, value in integrity_gates.items() if key != "all_required_integrity_gates_passed"
    )
    economic_all = all(
        value for key, value in economic_gates.items() if key != "all_required_economic_gates_passed"
    )
    if integrity_gates.get("all_required_integrity_gates_passed") is not integrity_all:
        _fail("integrity gate aggregation changed")
    if economic_gates.get("all_required_economic_gates_passed") is not economic_all:
        _fail("economic gate aggregation changed")
    all_passed = integrity_all and economic_all
    if gate.get("all_required_gates_passed") is not all_passed:
        _fail("overall gate aggregation changed")
    expected_disposition = "PERMIT_LATER_V4" if all_passed else "VETO_BEFORE_V4"
    if result["disposition"] != expected_disposition:
        _fail("economic veto disposition does not match the gates")
    seal = result["seal"]
    if seal != {
        "algorithm": "SHA-256",
        "payload_sha256": _payload_sha256(result),
        "canonicalization": CANONICALIZATION,
    }:
        _fail("economic veto result seal changed")
    return result
