#!/usr/bin/env python3
"""Declarative, equal-capital DRA V1 entry-admission economic screen."""

from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re
from typing import Any

import btc_dra_equal_capital_capacity_v1 as capacity


base = capacity.base
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "DRA_DECLARATIVE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "DRA_DECLARATIVE_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_DECLARATIVE_ENTRY_ADMISSION_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1"
GATE_SET_V1 = "DRA_DECLARATIVE_ENTRY_ADMISSION_GATES_V1"
GATE_SET_V2 = "DRA_DECLARATIVE_ENTRY_ADMISSION_GATES_V2"
GATE_SET = GATE_SET_V1
SELECTION_CUTOFF = "2025-01-01T00:00:00"
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")
DD_TOLERANCE_PP = D("0.25")
RATIO_QUANTUM = D("0.00000001")
PI_OVER_TWO = D("1.5707963267948966192313216916397514")
FEATURES = {
    "LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_VOLATILITY_MANAGEMENT_DESIGN_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_VOLATILITY_MANAGEMENT_PRIMARY_PRIOR_AUDIT_V4",
    },
    "DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_REALIZED_VOLATILITY_TERM_STRUCTURE_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_REALIZED_VOLATILITY_TERM_STRUCTURE_PRIMARY_PRIOR_V1",
    },
    "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "prior_disposition": "PREREGISTERED_V1_FORWARD_DISCOVERY_MECHANISM",
        "prior_identity_field": "contract_id",
        "prior_identity_value": "PROSPECTIVE_MARKET_MECHANISM_DIAGNOSTIC_V1",
    },
    "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "prior_disposition": "PREREGISTERED_V1_FORWARD_DISCOVERY_MECHANISM",
        "prior_identity_field": "contract_id",
        "prior_identity_value": "PROSPECTIVE_MARKET_MECHANISM_DIAGNOSTIC_V1",
    },
    "DAILY_DOWNSIDE_SEMIVARIANCE_SHARE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DOWNSIDE_SEMIVARIANCE_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_DOWNSIDE_SEMIVARIANCE_PRIMARY_PRIOR_V1",
    },
    "DAILY_AMIHUD_ILLIQUIDITY_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_AMIHUD_ILLIQUIDITY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_AMIHUD_ILLIQUIDITY_PRIMARY_PRIOR_V1",
    },
    "DAILY_CORWIN_SCHULTZ_SPREAD_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PASS_PREOUTCOME_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_CORWIN_SCHULTZ_SPREAD_PREOUTCOME_SUPPORT_ACCEPTANCE_V2",
    },
    "DAILY_REALIZED_TO_BIPOWER_VARIATION_RATIO_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_BIPOWER_JUMPINESS_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_BIPOWER_JUMPINESS_PRIMARY_PRIOR_V1",
    },
    "DAILY_INTRADAY_SIGN_PERSISTENCE_SHARE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_INTRADAY_SIGN_PERSISTENCE_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_INTRADAY_SIGN_PERSISTENCE_PRIMARY_PRIOR_V1",
    },
    "DAILY_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DIRECTIONAL_VOLUME_PARTICIPATION_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_DIRECTIONAL_VOLUME_PARTICIPATION_PRIMARY_PRIOR_V1",
    },
    "DAILY_QUOTE_VOLUME_HERFINDAHL_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "prior_disposition": "PRIOR_SUPPORTS_ONE_INTRADAY_VOLUME_CONCENTRATION_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_INTRADAY_VOLUME_CONCENTRATION_PRIMARY_PRIOR_V1",
    },
    "DAILY_1500_2059_UTC_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_US_TRADITIONAL_SESSION_ACTIVITY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_US_TRADITIONAL_SESSION_ACTIVITY_PRIMARY_PRIOR_V1",
    },
    "DAILY_1800_2359_UTC_ABSOLUTE_LOG_RETURN_SHARE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DRA_LATE_DAY_PRICE_ACTIVITY_ENTRY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_LATE_DAY_PRICE_ACTIVITY_ENTRY_ADMISSION_PRIMARY_PRIOR_V1",
    },
    "DAILY_H1_CLOSE_PATH_MAX_DRAWDOWN_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_BELOW",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DRA_INTRADAY_CLOSE_PATH_DRAWDOWN_ENTRY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_INTRADAY_CLOSE_PATH_DRAWDOWN_ENTRY_ADMISSION_PRIMARY_PRIOR_V1",
    },
    "DAILY_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_CLOSE_LOCATION_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_CLOSE_LOCATION_PRIMARY_PRIOR_V1",
    },
    "DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_H1_VOLUME_WEIGHTED_CLOSE_LOCATION_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_H1_VOLUME_WEIGHTED_CLOSE_LOCATION_PRIMARY_PRIOR_V1",
    },
    "DAILY_REALIZED_PERFORMANCE_PRIOR_20D_PERCENTILE": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_REALIZED_PERFORMANCE_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_REALIZED_PERFORMANCE_PRIMARY_PRIOR_V1",
    },
    "DAILY_H1_LAG1_RETURN_AUTOCORRELATION_PRIOR_20D_PERCENTILE": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_H1_LAG1_RETURN_AUTOCORRELATION_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_H1_LAG1_RETURN_AUTOCORRELATION_PRIMARY_PRIOR_V1",
    },
    "DAILY_INTRADAY_PRICE_PATH_EFFICIENCY_PRIOR_20D_PERCENTILE": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_INTRADAY_PRICE_PATH_EFFICIENCY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_INTRADAY_PRICE_PATH_EFFICIENCY_PRIMARY_PRIOR_V1",
    },
    "DAILY_INTRADAY_REALIZED_SKEWNESS_PRIOR_20D_PERCENTILE": {
        "relation": "AT_OR_BELOW",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_REALIZED_SKEWNESS_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_REALIZED_SKEWNESS_PRIMARY_PRIOR_V1",
    },
    "DAILY_H1_ABSOLUTE_LOG_RETURN_TO_LOG_CLOSE_WEIGHTED_VOLUME_CORRELATION_PRIOR_20D_PERCENTILE": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DRA_H1_ABSOLUTE_RETURN_VOLUME_COUPLING_ENTRY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_H1_ABSOLUTE_RETURN_VOLUME_COUPLING_ENTRY_ADMISSION_PRIMARY_PRIOR_V1",
    },
    "DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "lookback_complete_days": 0,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_H1_FIRST_EXTREME_ORDER_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_H1_FIRST_EXTREME_ORDER_PRIMARY_PRIOR_V1",
    },
    "LATEST_COMPLETE_UTC_DAY_WEEKDAY_INDEX_MONDAY_ZERO": {
        "relation": "AT_OR_BELOW",
        "gate_set": GATE_SET_V2,
        "lookback_complete_days": 0,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_WEEKEND_CALENDAR_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_WEEKEND_CALENDAR_PRIMARY_PRIOR_V1",
    },
    "PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION": {
        "relation": "AT_OR_ABOVE",
        "gate_set": GATE_SET_V2,
        "lookback_complete_days": 90,
        "prior_disposition": "PRIOR_SUPPORTS_ONE_DRA_90D_DRAWDOWN_RECOVERY_ADMISSION_AUDIT",
        "prior_identity_field": "document_type",
        "prior_identity_value": "DRA_90D_DRAWDOWN_RECOVERY_PRIMARY_PRIOR_V1",
    },
}
PERCENTILE_FEATURES = {
    "DAILY_REALIZED_PERFORMANCE_PRIOR_20D_PERCENTILE",
    "DAILY_H1_LAG1_RETURN_AUTOCORRELATION_PRIOR_20D_PERCENTILE",
    "DAILY_INTRADAY_PRICE_PATH_EFFICIENCY_PRIOR_20D_PERCENTILE",
    "DAILY_INTRADAY_REALIZED_SKEWNESS_PRIOR_20D_PERCENTILE",
    "DAILY_H1_ABSOLUTE_LOG_RETURN_TO_LOG_CLOSE_WEIGHTED_VOLUME_CORRELATION_PRIOR_20D_PERCENTILE",
}
DIRECT_FEATURES = {
    "DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY",
    "LATEST_COMPLETE_UTC_DAY_WEEKDAY_INDEX_MONDAY_ZERO",
    "PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION",
}
ROLE_ORDER = {"lower_neighbor": 0, "primary": 1, "upper_neighbor": 2}
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REPOSITORY_PATH = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")


class ScreenReject(RuntimeError):
    def __init__(self, status: str, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def canonical_document_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be a closed object")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _ID.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid")
    return value


def _decimal(value: Any, label: str) -> D:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be an exact decimal")
    try:
        result = D(str(value))
    except Exception as error:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid") from error
    if not result.is_finite() or result <= 0:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be positive")
    return result


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = _exact_keys(
        value,
        {
            "authorization",
            "dataset",
            "document_type",
            "economics",
            "experiment_id",
            "feature",
            "gate_set",
            "oos_access",
            "parent_strategy",
            "prior_evidence",
            "schema_version",
            "selection_cutoff",
            "variants",
        },
        "manifest",
    )
    if manifest["schema_version"] != "1" or manifest["document_type"] != DOCUMENT_TYPE:
        raise ScreenReject("CONTRACT_REJECT", "manifest identity is unsupported")
    if manifest["authorization"] != AUTHORIZATION:
        raise ScreenReject("CONTRACT_REJECT", "manifest authorization is unsupported")
    _identifier(manifest["experiment_id"], "experiment_id")
    if manifest["parent_strategy"] != PARENT_STRATEGY:
        raise ScreenReject("CONTRACT_REJECT", "parent strategy must be BTC_DRA_V1")
    if manifest["selection_cutoff"] != SELECTION_CUTOFF or manifest["oos_access"] != "DENY":
        raise ScreenReject("CONTRACT_REJECT", "selection cutoff and OOS boundary are frozen")
    prior = _exact_keys(
        manifest["prior_evidence"],
        {"disposition", "path", "sha256"},
        "prior_evidence",
    )
    if not isinstance(prior["path"], str) or _REPOSITORY_PATH.fullmatch(prior["path"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "prior evidence path is invalid")
    if not isinstance(prior["sha256"], str) or _SHA256.fullmatch(prior["sha256"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "prior evidence sha256 is invalid")

    dataset = _exact_keys(manifest["dataset"], {"canonical_sha256", "rows"}, "dataset")
    if not isinstance(dataset["rows"], int) or isinstance(dataset["rows"], bool) or dataset["rows"] <= 0:
        raise ScreenReject("CONTRACT_REJECT", "dataset rows must be positive")
    if not isinstance(dataset["canonical_sha256"], str) or _SHA256.fullmatch(dataset["canonical_sha256"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "dataset canonical_sha256 is invalid")

    economics = _exact_keys(
        manifest["economics"],
        {"fee_rate", "initial_equity_usdt", "slippage_rate", "slot_capacity_usdt"},
        "economics",
    )
    if (
        _decimal(economics["initial_equity_usdt"], "initial_equity_usdt")
        != INITIAL_EQUITY_USDT
        or _decimal(economics["slot_capacity_usdt"], "slot_capacity_usdt")
        != SLOT_CAPACITY_USDT
        or _decimal(economics["fee_rate"], "fee_rate") != base.FEE
        or _decimal(economics["slippage_rate"], "slippage_rate") != base.SLIPPAGE
    ):
        raise ScreenReject("CONTRACT_REJECT", "economic assumptions differ from the frozen parent")

    feature = _exact_keys(
        manifest["feature"],
        {"decision_time", "key", "lookback_complete_days", "relation"},
        "feature",
    )
    feature_key = feature["key"]
    feature_contract = FEATURES.get(feature_key)
    if feature_contract is None or feature["relation"] != feature_contract["relation"]:
        raise ScreenReject("CONTRACT_REJECT", "feature or causal relation is unsupported")
    if prior["disposition"] != feature_contract["prior_disposition"]:
        raise ScreenReject("CONTRACT_REJECT", "prior disposition does not bind the feature")
    expected_gate_set = feature_contract.get("gate_set", GATE_SET_V1)
    if manifest["gate_set"] != expected_gate_set:
        raise ScreenReject("CONTRACT_REJECT", "gate set does not bind the feature")
    expected_lookback = feature_contract.get("lookback_complete_days", 20)
    if feature["lookback_complete_days"] != expected_lookback:
        raise ScreenReject(
            "CONTRACT_REJECT",
            f"feature lookback must be {expected_lookback} complete UTC days",
        )
    if feature["decision_time"] != "LATEST_COMPLETE_UTC_DAY_BEFORE_NEXT_BAR_FILL":
        raise ScreenReject("CONTRACT_REJECT", "feature must be known before the next-bar fill")

    variants = manifest["variants"]
    if not isinstance(variants, list) or not 1 <= len(variants) <= 3:
        raise ScreenReject("CONTRACT_REJECT", "manifest must freeze one to three variants")
    roles: set[str] = set()
    ids: set[str] = set()
    thresholds: set[D] = set()
    for index, raw_variant in enumerate(variants):
        variant = _exact_keys(raw_variant, {"role", "threshold", "variant_id"}, f"variants[{index}]")
        variant_id = _identifier(variant["variant_id"], f"variants[{index}].variant_id")
        role = variant["role"]
        if role not in ROLE_ORDER:
            raise ScreenReject("CONTRACT_REJECT", "variant role is unsupported")
        threshold = _decimal(variant["threshold"], f"variants[{index}].threshold")
        ids.add(variant_id)
        roles.add(role)
        thresholds.add(threshold)
    if len(ids) != len(variants) or len(roles) != len(variants) or len(thresholds) != len(variants):
        raise ScreenReject("CONTRACT_REJECT", "variant ids, roles, and thresholds must be distinct")
    if "primary" not in roles:
        raise ScreenReject("CONTRACT_REJECT", "exactly one primary variant is required")
    ordered = sorted(variants, key=lambda item: ROLE_ORDER[item["role"]])
    ordered_thresholds = [D(str(item["threshold"])) for item in ordered]
    if ordered_thresholds != sorted(ordered_thresholds):
        raise ScreenReject("CONTRACT_REJECT", "neighbor thresholds must be monotonic by role")
    return manifest


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScreenReject("CONTRACT_REJECT", "manifest must be strict UTF-8 JSON") from error
    if raw != canonical_document_bytes(value):
        raise ScreenReject("CONTRACT_REJECT", "manifest must use canonical JSON document bytes")
    return validate_manifest(value), raw


def median(values: list[D]) -> D:
    ordered = sorted(values)
    middle = len(ordered) // 2
    return (
        ordered[middle]
        if len(ordered) % 2
        else (ordered[middle - 1] + ordered[middle]) / D("2")
    )


def corwin_schultz_spread(
    previous_high: D,
    previous_low: D,
    current_high: D,
    current_low: D,
) -> D:
    if min(previous_high, previous_low, current_high, current_low) <= 0:
        raise ScreenReject(
            "DATA_REJECT", "Corwin-Schultz spread requires positive daily highs and lows"
        )
    with localcontext() as context:
        context.prec = 50
        previous_range = (previous_high / previous_low).ln()
        current_range = (current_high / current_low).ln()
        beta = previous_range * previous_range + current_range * current_range
        two_day_range = (
            max(previous_high, current_high) / min(previous_low, current_low)
        ).ln()
        gamma = two_day_range * two_day_range
        denominator = D("3") - D("2") * D("2").sqrt()
        alpha = (
            ((D("2") * beta).sqrt() - beta.sqrt()) / denominator
            - (gamma / denominator).sqrt()
        )
        raw_spread = D("2") * (alpha.exp() - D("1")) / (
            D("1") + alpha.exp()
        )
        return max(base.ZERO, raw_spread)


def realized_performance(log_returns: list[D]) -> D:
    return _realized_performance(tuple(log_returns))


def drawdown_recovery_fraction(
    complete_days: list[tuple[D, D, D]],
) -> D | None:
    if len(complete_days) != 90:
        return None
    peak_high = max(item[0] for item in complete_days)
    peak_index = next(
        index for index, item in enumerate(complete_days) if item[0] == peak_high
    )
    post_peak = complete_days[peak_index:]
    trough_low = min(item[1] for item in post_peak)
    peak_to_trough = peak_high - trough_low
    if peak_to_trough <= 0:
        return None
    latest_close = complete_days[-1][2]
    if latest_close < trough_low or latest_close > peak_high:
        raise ScreenReject(
            "DATA_REJECT",
            "drawdown recovery latest close falls outside the frozen peak-to-trough range",
        )
    return (latest_close - trough_low) / peak_to_trough


@lru_cache(maxsize=None)
def _realized_performance(log_returns: tuple[D, ...]) -> D:
    """Return the non-zero SW root E[exp(-lambda * r)] = 1."""
    if len(log_returns) != 24 or any(not value.is_finite() for value in log_returns):
        raise ScreenReject(
            "DATA_REJECT",
            "realized performance requires exactly 24 finite intraday log returns",
        )
    with localcontext() as context:
        context.prec = 50
        mean = sum(log_returns, D("0")) / D(len(log_returns))
        if mean == 0:
            return D("0")
        direction = D("1") if mean > 0 else D("-1")
        adjusted = [direction * value for value in log_returns]
        if min(adjusted) >= 0:
            raise ScreenReject(
                "DATA_REJECT",
                "realized performance requires both positive and negative intraday outcomes",
            )

        def moment(root: D) -> D:
            return sum((-root * value).exp() for value in adjusted) / D(
                len(adjusted)
            ) - D("1")

        lower = D("0")
        upper = D("1")
        for _ in range(256):
            if moment(upper) > 0:
                break
            upper *= D("2")
        else:
            raise ScreenReject(
                "DATA_REJECT", "realized performance root could not be bracketed"
            )
        for _ in range(256):
            midpoint = (lower + upper) / D("2")
            if moment(midpoint) > 0:
                upper = midpoint
            else:
                lower = midpoint
        return +(direction * ((lower + upper) / D("2")))


def prior_percentile(current: D, prior: list[D]) -> D:
    if len(prior) != 20:
        raise ScreenReject(
            "DATA_REJECT", "percentile feature requires 20 prior days"
        )
    below = sum(value < current for value in prior)
    equal = sum(value == current for value in prior)
    return (D(below) + D(equal) / D("2")) / D(len(prior))


def lag1_return_autocorrelation(log_returns: list[D]) -> D:
    if len(log_returns) != 24 or any(not value.is_finite() for value in log_returns):
        raise ScreenReject(
            "DATA_REJECT",
            "H1 lag-1 return autocorrelation requires exactly 24 finite intraday log returns",
        )
    leading = log_returns[:-1]
    lagged = log_returns[1:]
    count = D(len(leading))
    leading_mean = sum(leading, D("0")) / count
    lagged_mean = sum(lagged, D("0")) / count
    covariance_sum = sum(
        (left - leading_mean) * (right - lagged_mean)
        for left, right in zip(leading, lagged)
    )
    leading_square_sum = sum(
        (value - leading_mean) * (value - leading_mean) for value in leading
    )
    lagged_square_sum = sum(
        (value - lagged_mean) * (value - lagged_mean) for value in lagged
    )
    if leading_square_sum <= 0 or lagged_square_sum <= 0:
        raise ScreenReject(
            "DATA_REJECT",
            "H1 lag-1 return autocorrelation requires non-zero variation on both lagged sequences",
        )
    with localcontext() as context:
        context.prec = 50
        return covariance_sum / (leading_square_sum * lagged_square_sum).sqrt()


def absolute_return_log_volume_correlation(
    log_returns: list[D], close_weighted_volumes: list[D]
) -> D:
    if (
        len(log_returns) != 24
        or len(close_weighted_volumes) != 24
        or any(not value.is_finite() for value in log_returns)
        or any(not value.is_finite() or value <= 0 for value in close_weighted_volumes)
    ):
        raise ScreenReject(
            "DATA_REJECT",
            "H1 absolute-return log-volume correlation requires exactly 24 finite returns and positive close-weighted volumes",
        )
    absolute_returns = [abs(value) for value in log_returns]
    with localcontext() as context:
        context.prec = 50
        log_volumes = [value.ln() for value in close_weighted_volumes]
        count = D(len(absolute_returns))
        return_mean = sum(absolute_returns, D("0")) / count
        volume_mean = sum(log_volumes, D("0")) / count
        covariance_sum = sum(
            (left - return_mean) * (right - volume_mean)
            for left, right in zip(absolute_returns, log_volumes, strict=True)
        )
        return_square_sum = sum(
            (value - return_mean) * (value - return_mean)
            for value in absolute_returns
        )
        volume_square_sum = sum(
            (value - volume_mean) * (value - volume_mean)
            for value in log_volumes
        )
        if return_square_sum <= 0 or volume_square_sum <= 0:
            raise ScreenReject(
                "DATA_REJECT",
                "H1 absolute-return log-volume correlation requires non-zero variation on both axes",
            )
        return covariance_sum / (return_square_sum * volume_square_sum).sqrt()


def intraday_price_path_efficiency(log_returns: list[D]) -> D:
    if len(log_returns) != 24 or any(not value.is_finite() for value in log_returns):
        raise ScreenReject(
            "DATA_REJECT",
            "intraday price-path efficiency requires exactly 24 finite intraday log returns",
        )
    gross_path = sum((abs(value) for value in log_returns), D("0"))
    if gross_path <= 0:
        raise ScreenReject(
            "DATA_REJECT",
            "intraday price-path efficiency requires a positive gross price path",
        )
    return abs(sum(log_returns, D("0"))) / gross_path


def realized_skewness(log_returns: list[D]) -> D:
    if len(log_returns) != 24 or any(not value.is_finite() for value in log_returns):
        raise ScreenReject(
            "DATA_REJECT",
            "realized skewness requires exactly 24 finite intraday log returns",
        )
    second_moment_sum = sum((value * value for value in log_returns), D("0"))
    if second_moment_sum <= 0:
        raise ScreenReject(
            "DATA_REJECT", "realized skewness requires positive realized variance"
        )
    third_moment_sum = sum((value * value * value for value in log_returns), D("0"))
    with localcontext() as context:
        context.prec = 50
        return D(len(log_returns)).sqrt() * third_moment_sum / (
            second_moment_sum * second_moment_sum.sqrt()
        )


def realized_volatility_term_structure(
    prior_daily_variances: list[D], current_daily_variance: D
) -> tuple[D, D]:
    if len(prior_daily_variances) != 19:
        raise ScreenReject(
            "DATA_REJECT",
            "realized-volatility term structure requires 19 prior complete days",
        )
    twenty_day_variances = [*prior_daily_variances, current_daily_variance]
    short_variance = sum(twenty_day_variances[-5:], D("0"))
    long_variance = sum(twenty_day_variances, D("0"))
    if short_variance <= 0 or long_variance <= 0:
        raise ScreenReject(
            "DATA_REJECT",
            "realized-volatility term structure requires positive five- and twenty-day variation",
        )
    long_realized_volatility = long_variance.sqrt()
    return short_variance.sqrt() / long_realized_volatility, long_realized_volatility


def _midranks(values: list[D]) -> list[D]:
    ordered = sorted(enumerate(values), key=lambda item: item[1])
    ranks = [D("0")] * len(values)
    start = 0
    while start < len(ordered):
        end = start + 1
        while end < len(ordered) and ordered[end][1] == ordered[start][1]:
            end += 1
        rank = (D(start + 1) + D(end)) / D("2")
        for position in range(start, end):
            ranks[ordered[position][0]] = rank
        start = end
    return ranks


def spearman_correlation(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 3:
        raise ScreenReject(
            "DATA_REJECT", "Spearman correlation requires matched non-trivial samples"
        )
    left_ranks = _midranks(left)
    right_ranks = _midranks(right)
    count = D(len(left_ranks))
    left_mean = sum(left_ranks, D("0")) / count
    right_mean = sum(right_ranks, D("0")) / count
    covariance = sum(
        (a - left_mean) * (b - right_mean)
        for a, b in zip(left_ranks, right_ranks, strict=True)
    )
    left_variance = sum((value - left_mean) ** 2 for value in left_ranks)
    right_variance = sum((value - right_mean) ** 2 for value in right_ranks)
    if left_variance <= 0 or right_variance <= 0:
        raise ScreenReject(
            "DATA_REJECT", "Spearman correlation requires non-constant ranks"
        )
    return covariance / (left_variance * right_variance).sqrt()


class DeclarativeEntryAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    def __init__(self, *, feature_key: str, relation: str, threshold: D) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.feature_key = feature_key
        self.relation = relation
        self.threshold = threshold
        self.daily_history: deque[D] = deque(maxlen=20)
        self.drawdown_recovery_daily_history: deque[tuple[D, D, D]] = deque(
            maxlen=89
        )
        self.rv_term_daily_variance_history: deque[D] = deque(maxlen=19)
        self.rv_term_structure_observations: list[tuple[datetime, D, D]] = []
        self.feature_day: datetime | None = None
        self.feature_open: D | None = None
        self.feature_high: D | None = None
        self.feature_low: D | None = None
        self.previous_feature_high: D | None = None
        self.previous_feature_low: D | None = None
        self.feature_high_first_hour: int | None = None
        self.feature_low_first_hour: int | None = None
        self.feature_close: D | None = None
        self.feature_volume = base.ZERO
        self.daily_squared_return_sum = base.ZERO
        self.daily_downside_squared_return_sum = base.ZERO
        self.daily_amihud_sum = base.ZERO
        self.daily_amihud_invalid = False
        self.daily_bipower_product_sum = base.ZERO
        self.daily_previous_hour_return: D | None = None
        self.daily_sign_previous_close: D | None = None
        self.daily_sign_previous_return: D | None = None
        self.daily_sign_pair_count = 0
        self.daily_sign_persistence_pair_count = 0
        self.daily_total_quote_volume = base.ZERO
        self.daily_1500_2059_utc_quote_volume = base.ZERO
        self.daily_positive_return_quote_volume = base.ZERO
        self.daily_quote_volume_square_sum = base.ZERO
        self.daily_intraday_log_returns: list[D] = []
        self.daily_intraday_close_weighted_volumes: list[D] = []
        self.daily_intraday_closes: list[D] = []
        self.daily_bar_count = 0
        self.previous_hour_close: D | None = None
        self.current_feature_ratio: D | None = None
        self.complete_feature_days = 0
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0
        self.feature_unavailable_signal_count = 0

    def _daily_value(self) -> D | None:
        if self.feature_key == "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN":
            return self.feature_volume
        if self.feature_key == "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN":
            assert self.feature_open is not None
            assert self.feature_high is not None
            assert self.feature_low is not None
            return (self.feature_high - self.feature_low) / self.feature_open
        if self.feature_key == "LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN":
            return self.daily_squared_return_sum.sqrt()
        if self.feature_key == "DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN":
            if len(self.rv_term_daily_variance_history) < 19:
                return None
            ratio, _ = realized_volatility_term_structure(
                list(self.rv_term_daily_variance_history),
                self.daily_squared_return_sum,
            )
            return ratio
        if self.feature_key == "DAILY_DOWNSIDE_SEMIVARIANCE_SHARE_TO_PRIOR_20D_MEDIAN":
            if self.daily_squared_return_sum <= 0:
                return base.ZERO
            return self.daily_downside_squared_return_sum / self.daily_squared_return_sum
        if self.feature_key == "DAILY_AMIHUD_ILLIQUIDITY_TO_PRIOR_20D_MEDIAN":
            if self.daily_amihud_invalid or self.daily_bar_count != 24:
                raise ScreenReject(
                    "DATA_REJECT",
                    "Amihud-style illiquidity requires 24 positive-volume hourly bars",
                )
            return self.daily_amihud_sum / D(self.daily_bar_count)
        if (
            self.feature_key
            == "DAILY_CORWIN_SCHULTZ_SPREAD_TO_PRIOR_20D_MEDIAN"
        ):
            if (
                self.daily_bar_count != 24
                or self.feature_high is None
                or self.feature_low is None
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "Corwin-Schultz spread requires one complete 24-hour UTC day",
                )
            if self.previous_feature_high is None or self.previous_feature_low is None:
                return None
            return corwin_schultz_spread(
                self.previous_feature_high,
                self.previous_feature_low,
                self.feature_high,
                self.feature_low,
            )
        if (
            self.feature_key
            == "DAILY_REALIZED_TO_BIPOWER_VARIATION_RATIO_TO_PRIOR_20D_MEDIAN"
        ):
            bipower_variation = PI_OVER_TWO * self.daily_bipower_product_sum
            if self.daily_squared_return_sum <= 0 or bipower_variation <= 0:
                raise ScreenReject(
                    "DATA_REJECT",
                    "realized-to-bipower jumpiness requires positive daily variation",
                )
            return self.daily_squared_return_sum / bipower_variation
        if (
            self.feature_key
            == "DAILY_INTRADAY_SIGN_PERSISTENCE_SHARE_TO_PRIOR_20D_MEDIAN"
        ):
            if self.daily_bar_count != 24 or self.daily_sign_pair_count != 22:
                raise ScreenReject(
                    "DATA_REJECT",
                    "intraday sign persistence requires 24 hourly closes and 22 adjacent return pairs",
                )
            return D(self.daily_sign_persistence_pair_count) / D(
                self.daily_sign_pair_count
            )
        if (
            self.feature_key
            == "DAILY_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN"
        ):
            if self.daily_bar_count != 24 or self.daily_total_quote_volume <= 0:
                raise ScreenReject(
                    "DATA_REJECT",
                    "directional volume participation requires 24 hourly bars and positive daily quote volume",
                )
            return (
                self.daily_positive_return_quote_volume
                / self.daily_total_quote_volume
            )
        if (
            self.feature_key
            == "DAILY_QUOTE_VOLUME_HERFINDAHL_TO_PRIOR_20D_MEDIAN"
        ):
            if self.daily_bar_count != 24 or self.daily_total_quote_volume <= 0:
                raise ScreenReject(
                    "DATA_REJECT",
                    "intraday volume concentration requires 24 hourly bars and positive daily quote volume",
                )
            return self.daily_quote_volume_square_sum / (
                self.daily_total_quote_volume * self.daily_total_quote_volume
            )
        if (
            self.feature_key
            == "DAILY_1500_2059_UTC_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN"
        ):
            if self.daily_bar_count != 24 or self.daily_total_quote_volume <= 0:
                raise ScreenReject(
                    "DATA_REJECT",
                    "fixed UTC traditional-session activity requires 24 hourly bars and positive daily quote volume",
                )
            return (
                self.daily_1500_2059_utc_quote_volume
                / self.daily_total_quote_volume
            )
        if (
            self.feature_key
            == "DAILY_1800_2359_UTC_ABSOLUTE_LOG_RETURN_SHARE_TO_PRIOR_20D_MEDIAN"
        ):
            if self.daily_bar_count != 24 or len(self.daily_intraday_log_returns) != 24:
                raise ScreenReject(
                    "DATA_REJECT",
                    "late-day price activity requires exactly 24 hourly open-to-close log returns",
                )
            total_absolute_return = sum(
                (abs(value) for value in self.daily_intraday_log_returns),
                base.ZERO,
            )
            if total_absolute_return <= 0:
                return None
            late_absolute_return = sum(
                (abs(value) for value in self.daily_intraday_log_returns[18:24]),
                base.ZERO,
            )
            return late_absolute_return / total_absolute_return
        if (
            self.feature_key
            == "DAILY_H1_CLOSE_PATH_MAX_DRAWDOWN_TO_PRIOR_20D_MEDIAN"
        ):
            if (
                self.daily_bar_count != 24
                or self.feature_open is None
                or self.feature_open <= 0
                or len(self.daily_intraday_closes) != 24
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "intraday close-path drawdown requires the positive day open and exactly 24 hourly closes",
                )
            running_peak = self.feature_open
            maximum_drawdown = base.ZERO
            for close in self.daily_intraday_closes:
                if close > running_peak:
                    running_peak = close
                    continue
                drawdown = (running_peak - close) / running_peak
                maximum_drawdown = max(maximum_drawdown, drawdown)
            return maximum_drawdown
        if self.feature_key == "DAILY_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN":
            if (
                self.daily_bar_count != 24
                or self.feature_high is None
                or self.feature_low is None
                or self.feature_close is None
                or self.feature_high <= self.feature_low
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "close location requires 24 hourly bars and a positive complete-day range",
                )
            return (self.feature_close - self.feature_low) / (
                self.feature_high - self.feature_low
            )
        if (
            self.feature_key
            == "DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN"
        ):
            if (
                self.daily_bar_count != 24
                or self.feature_close is None
                or self.feature_volume <= 0
                or self.daily_total_quote_volume <= 0
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "H1 volume-weighted close location requires 24 hourly bars and positive daily base and quote volume",
                )
            return (
                self.feature_close
                * self.feature_volume
                / self.daily_total_quote_volume
            )
        if self.feature_key == "DAILY_REALIZED_PERFORMANCE_PRIOR_20D_PERCENTILE":
            return realized_performance(self.daily_intraday_log_returns)
        if (
            self.feature_key
            == "DAILY_H1_LAG1_RETURN_AUTOCORRELATION_PRIOR_20D_PERCENTILE"
        ):
            return lag1_return_autocorrelation(self.daily_intraday_log_returns)
        if (
            self.feature_key
            == "DAILY_INTRADAY_PRICE_PATH_EFFICIENCY_PRIOR_20D_PERCENTILE"
        ):
            return intraday_price_path_efficiency(self.daily_intraday_log_returns)
        if (
            self.feature_key
            == "DAILY_INTRADAY_REALIZED_SKEWNESS_PRIOR_20D_PERCENTILE"
        ):
            return realized_skewness(self.daily_intraday_log_returns)
        if (
            self.feature_key
            == "DAILY_H1_ABSOLUTE_LOG_RETURN_TO_LOG_CLOSE_WEIGHTED_VOLUME_CORRELATION_PRIOR_20D_PERCENTILE"
        ):
            if (
                len(self.daily_intraday_log_returns) != 24
                or len(self.daily_intraday_close_weighted_volumes) != 24
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "movement-volume coupling requires one complete 24-hour UTC day",
                )
            absolute_returns = {
                abs(value) for value in self.daily_intraday_log_returns
            }
            positive_volumes = {
                value
                for value in self.daily_intraday_close_weighted_volumes
                if value > 0
            }
            if (
                len(positive_volumes)
                != len(self.daily_intraday_close_weighted_volumes)
                or len(absolute_returns) < 2
                or len(positive_volumes) < 2
            ):
                return None
            return absolute_return_log_volume_correlation(
                self.daily_intraday_log_returns,
                self.daily_intraday_close_weighted_volumes,
            )
        if self.feature_key == "DAILY_H1_FIRST_LOW_BEFORE_FIRST_HIGH_BINARY":
            if (
                self.daily_bar_count != 24
                or self.feature_high is None
                or self.feature_low is None
                or self.feature_high <= self.feature_low
                or self.feature_high_first_hour is None
                or self.feature_low_first_hour is None
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "H1 first-extreme order requires 24 hourly bars and a positive complete-day range",
                )
            if self.feature_low_first_hour == self.feature_high_first_hour:
                return None
            return D(
                "1"
                if self.feature_low_first_hour < self.feature_high_first_hour
                else "0"
            )
        if self.feature_key == "LATEST_COMPLETE_UTC_DAY_WEEKDAY_INDEX_MONDAY_ZERO":
            if self.feature_day is None or self.daily_bar_count != 24:
                raise ScreenReject(
                    "DATA_REJECT",
                    "weekday index requires one complete 24-hour UTC day",
                )
            return D(self.feature_day.weekday())
        if self.feature_key == "PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION":
            if (
                self.daily_bar_count != 24
                or self.feature_high is None
                or self.feature_low is None
                or self.feature_close is None
            ):
                raise ScreenReject(
                    "DATA_REJECT",
                    "90-day drawdown recovery requires one complete 24-hour UTC day",
                )
            return drawdown_recovery_fraction(
                [
                    *self.drawdown_recovery_daily_history,
                    (self.feature_high, self.feature_low, self.feature_close),
                ]
            )
        raise ScreenReject("CONTRACT_REJECT", f"unsupported feature {self.feature_key}")

    def _update_feature(self, bar: base.Bar) -> None:
        if self.feature_day is None or self.feature_day.date() != bar.open_time.date():
            self.feature_day = bar.open_time
            self.feature_open = bar.open
            self.feature_high = bar.high
            self.feature_low = bar.low
            self.feature_high_first_hour = bar.open_time.hour
            self.feature_low_first_hour = bar.open_time.hour
            self.feature_close = bar.close
            self.feature_volume = base.ZERO
            self.daily_squared_return_sum = base.ZERO
            self.daily_downside_squared_return_sum = base.ZERO
            self.daily_amihud_sum = base.ZERO
            self.daily_amihud_invalid = False
            self.daily_bipower_product_sum = base.ZERO
            self.daily_previous_hour_return = None
            self.daily_sign_previous_close = None
            self.daily_sign_previous_return = None
            self.daily_sign_pair_count = 0
            self.daily_sign_persistence_pair_count = 0
            self.daily_total_quote_volume = base.ZERO
            self.daily_1500_2059_utc_quote_volume = base.ZERO
            self.daily_positive_return_quote_volume = base.ZERO
            self.daily_quote_volume_square_sum = base.ZERO
            self.daily_intraday_log_returns = []
            self.daily_intraday_close_weighted_volumes = []
            self.daily_intraday_closes = []
            self.daily_bar_count = 0
        assert self.feature_high is not None and self.feature_low is not None
        if bar.high > self.feature_high:
            self.feature_high = bar.high
            self.feature_high_first_hour = bar.open_time.hour
        if bar.low < self.feature_low:
            self.feature_low = bar.low
            self.feature_low_first_hour = bar.open_time.hour
        self.feature_close = bar.close
        self.feature_volume += bar.volume
        with localcontext() as context:
            context.prec = 50
            self.daily_intraday_log_returns.append((bar.close / bar.open).ln())
        self.daily_intraday_closes.append(bar.close)
        dollar_volume = bar.close * bar.volume
        self.daily_intraday_close_weighted_volumes.append(dollar_volume)
        self.daily_total_quote_volume += dollar_volume
        if 15 <= bar.open_time.hour <= 20:
            self.daily_1500_2059_utc_quote_volume += dollar_volume
        self.daily_quote_volume_square_sum += dollar_volume * dollar_volume
        if bar.close > bar.open:
            self.daily_positive_return_quote_volume += dollar_volume
        if dollar_volume <= 0:
            self.daily_amihud_invalid = True
        else:
            self.daily_amihud_sum += abs((bar.close / bar.open) - D("1")) / dollar_volume
        if self.previous_hour_close is not None:
            hourly_return = (bar.close / self.previous_hour_close) - D("1")
            self.daily_squared_return_sum += hourly_return * hourly_return
            if hourly_return < 0:
                self.daily_downside_squared_return_sum += hourly_return * hourly_return
            if self.daily_previous_hour_return is not None:
                self.daily_bipower_product_sum += abs(hourly_return) * abs(
                    self.daily_previous_hour_return
                )
            self.daily_previous_hour_return = hourly_return
        if self.daily_sign_previous_close is not None:
            intraday_return = (bar.close / self.daily_sign_previous_close) - D("1")
            if self.daily_sign_previous_return is not None:
                self.daily_sign_pair_count += 1
                if intraday_return * self.daily_sign_previous_return > 0:
                    self.daily_sign_persistence_pair_count += 1
            self.daily_sign_previous_return = intraday_return
        self.daily_sign_previous_close = bar.close
        self.previous_hour_close = bar.close
        self.daily_bar_count += 1
        if bar.open_time.hour != 23 or self.daily_bar_count != 24:
            return
        current = self._daily_value()
        if current is None:
            self.current_feature_ratio = None
        elif self.feature_key in DIRECT_FEATURES:
            self.current_feature_ratio = current.quantize(
                RATIO_QUANTUM, rounding=ROUND_HALF_UP
            )
        elif len(self.daily_history) == 20:
            if self.feature_key in PERCENTILE_FEATURES:
                self.current_feature_ratio = prior_percentile(
                    current, list(self.daily_history)
                ).quantize(RATIO_QUANTUM, rounding=ROUND_HALF_UP)
            else:
                prior_median = median(list(self.daily_history))
                self.current_feature_ratio = (
                    None
                    if prior_median <= 0
                    else (current / prior_median).quantize(
                        RATIO_QUANTUM, rounding=ROUND_HALF_UP
                    )
                )
        else:
            self.current_feature_ratio = None
        if current is not None and self.feature_key not in DIRECT_FEATURES:
            self.daily_history.append(current)
        if self.feature_key == "PRIOR_90D_DRAWDOWN_RECOVERY_FRACTION":
            assert (
                self.feature_high is not None
                and self.feature_low is not None
                and self.feature_close is not None
            )
            self.drawdown_recovery_daily_history.append(
                (self.feature_high, self.feature_low, self.feature_close)
            )
        if self.feature_key == "DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN":
            if current is not None:
                _, long_realized_volatility = realized_volatility_term_structure(
                    list(self.rv_term_daily_variance_history),
                    self.daily_squared_return_sum,
                )
                assert self.feature_day is not None
                self.rv_term_structure_observations.append(
                    (self.feature_day, current, long_realized_volatility)
                )
            self.rv_term_daily_variance_history.append(
                self.daily_squared_return_sum
            )
        if (
            self.feature_key
            == "DAILY_CORWIN_SCHULTZ_SPREAD_TO_PRIOR_20D_MEDIAN"
        ):
            assert self.feature_high is not None and self.feature_low is not None
            self.previous_feature_high = self.feature_high
            self.previous_feature_low = self.feature_low
        self.complete_feature_days += 1

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        self._update_feature(bar)

    def _signal(self, bar: base.Bar) -> bool:
        parent_signal = super()._signal(bar)
        if not parent_signal:
            return False
        self.parent_signal_count += 1
        ratio = self.current_feature_ratio
        if ratio is None:
            self.feature_unavailable_signal_count += 1
            self.vetoed_signal_count += 1
            return False
        admitted = (
            ratio <= self.threshold
            if self.relation == "AT_OR_BELOW"
            else ratio >= self.threshold
        )
        if admitted:
            self.admitted_signal_count += 1
        else:
            self.vetoed_signal_count += 1
        return admitted

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        result.update(
            {
                "runner_identity": RUNNER_IDENTITY,
                "admission_feature": self.feature_key,
                "admission_relation": self.relation,
                "admission_threshold": str(self.threshold),
                "complete_feature_days": self.complete_feature_days,
                "parent_signal_count": self.parent_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
                "feature_unavailable_signal_count": self.feature_unavailable_signal_count,
                "admission_accounting_reconciles": self.parent_signal_count
                == self.admitted_signal_count + self.vetoed_signal_count,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    feature_key: str,
    relation: str,
    threshold: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = DeclarativeEntryAdmissionEngine(
        feature_key=feature_key,
        relation=relation,
        threshold=threshold,
    )
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def load_selection(path: Path, manifest: dict[str, Any]) -> list[base.Bar]:
    if not path.is_file():
        raise ScreenReject("DATA_REJECT", "selection corpus is missing")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    digest = base.data_hash(bars)
    expected = manifest["dataset"]
    if len(bars) != expected["rows"] or digest != expected["canonical_sha256"]:
        raise ScreenReject(
            "DATA_REJECT",
            {"actual_rows": len(bars), "actual_sha256": digest, "expected": expected},
        )
    if bars[-1].close_time > datetime.fromisoformat(SELECTION_CUTOFF):
        raise ScreenReject("OOS_REJECT", "selection corpus crosses the frozen cutoff")
    return bars


def term_structure_redundancy_gate(bars: list[base.Bar]) -> dict[str, Any]:
    engine = DeclarativeEntryAdmissionEngine(
        feature_key="DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN",
        relation="AT_OR_BELOW",
        threshold=D("1"),
    )
    for bar in bars:
        engine._update_feature(bar)
    windows = {"design": DESIGN, "validation": VALIDATION}
    correlations: dict[str, str] = {}
    sample_counts: dict[str, int] = {}
    passed = True
    for label, (start, end) in windows.items():
        selected = [
            observation
            for observation in engine.rv_term_structure_observations
            if start <= observation[0] < end
        ]
        ratios = [observation[1] for observation in selected]
        levels = [observation[2] for observation in selected]
        correlation = spearman_correlation(ratios, levels)
        correlations[label] = str(
            correlation.quantize(D("0.00000001"), rounding=ROUND_HALF_UP)
        )
        sample_counts[label] = len(selected)
        passed = passed and abs(correlation) <= D("0.80")
    return {
        "absolute_spearman_limit": "0.80",
        "correlation_to_contemporaneous_20d_realized_volatility": correlations,
        "passed": passed,
        "sample_counts": sample_counts,
    }


def realized_skewness_redundancy_gate(bars: list[base.Bar]) -> dict[str, Any]:
    observations: list[tuple[datetime, D, D, D]] = []
    current_day: datetime | None = None
    log_returns: list[D] = []
    for bar in bars:
        if current_day is None or current_day.date() != bar.open_time.date():
            if log_returns:
                raise ScreenReject(
                    "DATA_REJECT",
                    "realized-skewness redundancy gate found an incomplete UTC day",
                )
            current_day = bar.open_time
        with localcontext() as context:
            context.prec = 50
            log_returns.append((bar.close / bar.open).ln())
        if bar.open_time.hour != 23:
            continue
        if len(log_returns) != 24 or current_day is None:
            raise ScreenReject(
                "DATA_REJECT",
                "realized-skewness redundancy gate requires 24 H1 bars per UTC day",
            )
        observations.append(
            (
                current_day,
                realized_skewness(log_returns),
                realized_performance(log_returns),
                intraday_price_path_efficiency(log_returns),
            )
        )
        current_day = None
        log_returns = []
    if log_returns:
        raise ScreenReject(
            "DATA_REJECT",
            "realized-skewness redundancy gate ends on an incomplete UTC day",
        )

    correlations: dict[str, dict[str, str]] = {}
    sample_counts: dict[str, int] = {}
    passed = True
    for label, (start, end) in {"design": DESIGN, "validation": VALIDATION}.items():
        selected = [item for item in observations if start <= item[0] < end]
        skewness = [item[1] for item in selected]
        comparators = {
            "realized_performance": [item[2] for item in selected],
            "intraday_price_path_efficiency": [item[3] for item in selected],
        }
        correlations[label] = {}
        sample_counts[label] = len(selected)
        for comparator, values in comparators.items():
            correlation = spearman_correlation(skewness, values)
            correlations[label][comparator] = str(
                correlation.quantize(D("0.00000001"), rounding=ROUND_HALF_UP)
            )
            passed = passed and abs(correlation) <= D("0.80")
    return {
        "absolute_spearman_limit": "0.80",
        "correlations": correlations,
        "passed": passed,
        "sample_counts": sample_counts,
    }


def verify_prior_evidence(manifest: dict[str, Any]) -> dict[str, Any]:
    binding = manifest["prior_evidence"]
    feature_contract = FEATURES[manifest["feature"]["key"]]
    candidate = REPOSITORY_ROOT.joinpath(*binding["path"].split("/"))
    resolved = candidate.resolve(strict=True)
    try:
        resolved.relative_to(REPOSITORY_ROOT)
    except ValueError as error:
        raise ScreenReject("PRIOR_REJECT", "prior evidence escapes the repository") from error
    if not resolved.is_file() or resolved.is_symlink():
        raise ScreenReject("PRIOR_REJECT", "prior evidence must be a regular non-link file")
    raw = resolved.read_bytes()
    if sha256_bytes(raw) != binding["sha256"]:
        raise ScreenReject("PRIOR_REJECT", "prior evidence hash mismatch")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScreenReject("PRIOR_REJECT", "prior evidence must be strict JSON") from error
    if (
        value.get("disposition") != binding["disposition"]
        or value.get("authorization") != AUTHORIZATION
        or value.get(feature_contract["prior_identity_field"])
        != feature_contract["prior_identity_value"]
    ):
        raise ScreenReject("PRIOR_REJECT", "prior evidence identity mismatch")
    return {"path": binding["path"], "sha256": binding["sha256"]}


def parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    return {
        "design": capacity.simulate_capacity(
            bars, DESIGN, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
        ),
        "validation": capacity.simulate_capacity(
            bars, VALIDATION, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
        ),
        "folds": {
            name: capacity.simulate_capacity(
                bars, window, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
            )
            for name, window in FOLDS.items()
        },
    }


def _value(result: dict[str, Any], field: str) -> D:
    return D(str(result[field]))


def _non_worse_holding(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    candidate_value = candidate.get(field)
    parent_value = parent.get(field)
    if candidate_value is None or parent_value is None:
        return candidate_value == parent_value
    return D(str(candidate_value)) <= D(str(parent_value))


def variant_evidence(
    bars: list[base.Bar],
    baseline: dict[str, Any],
    *,
    feature_key: str,
    relation: str,
    variant: dict[str, Any],
) -> dict[str, Any]:
    threshold = D(str(variant["threshold"]))
    design = simulate_candidate(
        bars, DESIGN, feature_key=feature_key, relation=relation, threshold=threshold
    )
    validation = simulate_candidate(
        bars, VALIDATION, feature_key=feature_key, relation=relation, threshold=threshold
    )
    folds = {
        name: simulate_candidate(
            bars, window, feature_key=feature_key, relation=relation, threshold=threshold
        )
        for name, window in FOLDS.items()
    }
    annual_deltas = {
        name: _value(folds[name], "total_pnl_usdt")
        - _value(baseline["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    }
    positive = [value for value in annual_deltas.values() if value > 0]
    positive_total = sum(positive, D("0"))
    concentration = (
        max(positive) / positive_total * D("100") if positive_total > 0 else D("100")
    )
    return {
        "variant_id": variant["variant_id"],
        "role": variant["role"],
        "threshold": str(threshold),
        "design": design,
        "validation": validation,
        "folds": folds,
        "paired_equal_capital": {
            "design": capacity.equal_capital_deltas(baseline["design"], design),
            "validation": capacity.equal_capital_deltas(baseline["validation"], validation),
            "folds": {
                name: capacity.equal_capital_deltas(baseline["folds"][name], folds[name])
                for name in FOLDS
            },
        },
        "annual_total_pnl_delta": {name: str(value) for name, value in annual_deltas.items()},
        "annual_total_wins": sum(value > 0 for value in annual_deltas.values()),
        "annual_drawdown_non_worse": sum(
            _value(folds[name], "max_drawdown_pct")
            <= _value(baseline["folds"][name], "max_drawdown_pct") + DD_TOLERANCE_PP
            for name in FOLDS
        ),
        "top_year_positive_delta_contribution_pct": str(
            concentration.quantize(D("0.000001"), rounding=ROUND_HALF_UP)
        ),
    }


def primary_gates(
    variant: dict[str, Any],
    baseline: dict[str, Any],
    *,
    gate_set: str = GATE_SET_V1,
) -> dict[str, bool]:
    design = variant["design"]
    validation = variant["validation"]
    parent_design = baseline["design"]
    parent_validation = baseline["validation"]
    checks = {
        "design_total_pnl_improves": _value(design, "total_pnl_usdt") > _value(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": _value(validation, "total_pnl_usdt") > _value(parent_validation, "total_pnl_usdt"),
        "validation_realized_non_worse": _value(validation, "realized_usdt") >= _value(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt") >= _value(parent_validation, "unrealized_usdt"),
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct") <= _value(parent_validation, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_median_hold_non_worse": _non_worse_holding(validation, parent_validation, "median_hold_hours"),
        "validation_p90_hold_non_worse": _non_worse_holding(validation, parent_validation, "p90_hold_hours"),
        "design_interventions_at_least_8": int(design["vetoed_signal_count"]) >= 8,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
        "annual_total_wins_at_least_3_of_5": int(variant["annual_total_wins"]) >= 3,
        "annual_drawdown_non_worse_at_least_4_of_5": int(variant["annual_drawdown_non_worse"]) >= 4,
        "top_year_positive_delta_contribution_at_most_60pct": D(str(variant["top_year_positive_delta_contribution_pct"])) <= D("60"),
    }
    if gate_set == GATE_SET_V2:
        checks.update(
            {
                "design_realized_pnl_improves": _value(design, "realized_usdt")
                > _value(parent_design, "realized_usdt"),
                "design_max_underwater_duration_non_worse": int(
                    design["inventory_path"]["maximum_underwater_duration_hours"]
                )
                <= int(
                    parent_design["inventory_path"][
                        "maximum_underwater_duration_hours"
                    ]
                ),
                "design_terminal_inventory_count_non_worse": len(
                    design["terminal_inventory"]
                )
                <= len(parent_design["terminal_inventory"]),
                "validation_realized_pnl_improves": _value(
                    validation, "realized_usdt"
                )
                > _value(parent_validation, "realized_usdt"),
                "validation_max_underwater_duration_non_worse": int(
                    validation["inventory_path"][
                        "maximum_underwater_duration_hours"
                    ]
                )
                <= int(
                    parent_validation["inventory_path"][
                        "maximum_underwater_duration_hours"
                    ]
                ),
                "validation_terminal_inventory_count_non_worse": len(
                    validation["terminal_inventory"]
                )
                <= len(parent_validation["terminal_inventory"]),
            }
        )
    elif gate_set != GATE_SET_V1:
        raise ScreenReject("CONTRACT_REJECT", "gate set is unsupported")
    return checks


def neighbor_gates(
    variant: dict[str, Any],
    baseline: dict[str, Any],
    *,
    gate_set: str = GATE_SET_V1,
) -> dict[str, bool]:
    validation = variant["validation"]
    parent = baseline["validation"]
    checks = {
        "validation_total_pnl_non_worse": _value(validation, "total_pnl_usdt") >= _value(parent, "total_pnl_usdt"),
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct") <= _value(parent, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
    }
    if gate_set == GATE_SET_V2:
        checks.update(
            {
                "validation_realized_non_worse": _value(
                    validation, "realized_usdt"
                )
                >= _value(parent, "realized_usdt"),
                "validation_max_underwater_duration_non_worse": int(
                    validation["inventory_path"][
                        "maximum_underwater_duration_hours"
                    ]
                )
                <= int(
                    parent["inventory_path"]["maximum_underwater_duration_hours"]
                ),
                "validation_terminal_inventory_count_non_worse": len(
                    validation["terminal_inventory"]
                )
                <= len(parent["terminal_inventory"]),
            }
        )
    elif gate_set != GATE_SET_V1:
        raise ScreenReject("CONTRACT_REJECT", "gate set is unsupported")
    return checks


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    prior_evidence = verify_prior_evidence(manifest)
    bars = load_selection(input_path, manifest)
    pre_economic_gates = None
    if feature_key := manifest["feature"]["key"]:
        if feature_key == "DAILY_RV5_TO_RV20_RATIO_TO_PRIOR_20D_MEDIAN":
            pre_economic_gates = term_structure_redundancy_gate(bars)
            if not pre_economic_gates["passed"]:
                raise ScreenReject("DUPLICATE_REJECT", pre_economic_gates)
        elif (
            feature_key
            == "DAILY_INTRADAY_REALIZED_SKEWNESS_PRIOR_20D_PERCENTILE"
        ):
            pre_economic_gates = realized_skewness_redundancy_gate(bars)
            if not pre_economic_gates["passed"]:
                raise ScreenReject("DUPLICATE_REJECT", pre_economic_gates)
    baseline = parent_baseline(bars)
    feature = manifest["feature"]
    variants = [
        variant_evidence(
            bars,
            baseline,
            feature_key=feature["key"],
            relation=feature["relation"],
            variant=variant,
        )
        for variant in manifest["variants"]
    ]
    primary = next(item for item in variants if item["role"] == "primary")
    gate_set = manifest["gate_set"]
    primary_checks = primary_gates(primary, baseline, gate_set=gate_set)
    neighbor_checks = {
        item["variant_id"]: neighbor_gates(item, baseline, gate_set=gate_set)
        for item in variants
        if item["role"] != "primary"
    }
    passed = all(primary_checks.values()) and all(
        all(checks.values()) for checks in neighbor_checks.values()
    )
    result = {
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "dataset": {
            "canonical_sha256": base.data_hash(bars),
            "rows": len(bars),
            "selection_cutoff": SELECTION_CUTOFF,
        },
        "document_type": RESULT_TYPE,
        "economic_assumptions": manifest["economics"],
        "experiment_id": manifest["experiment_id"],
        "feature": feature,
        "gate_set": gate_set,
        "manifest_sha256": sha256_bytes(manifest_raw),
        "neighbor_stability_gates": neighbor_checks,
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "prior_evidence": prior_evidence,
        **(
            {"pre_economic_gates": pre_economic_gates}
            if pre_economic_gates is not None
            else {}
        ),
        "primary_gates": primary_checks,
        "recommended_next_action": (
            "FREEZE_ONE_HYPOTHESIS_MANIFEST"
            if passed
            else "CLOSE_FEATURE_FAMILY_WITHOUT_TUNING"
        ),
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256_path(Path(__file__)),
        "schema_version": "1",
        "status": (
            "ECONOMIC_SCREEN_PASS_READY_FOR_FROZEN_HYPOTHESIS"
            if passed
            else "NO_MECHANISM_CLOSE_FEATURE_FAMILY"
        ),
        "variants": variants,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(canonical_document_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_screen(args.manifest, args.input, args.output)
    except (ScreenReject, base.ResearchReject) as error:
        status = getattr(error, "status", "DATA_REJECT")
        detail = getattr(error, "detail", str(error))
        print(json.dumps({"detail": detail, "status": status}, ensure_ascii=False))
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
