#!/usr/bin/env python3
"""Deterministic historical screen for frozen CFTC Dealer positioning."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_dra_cftc_tff_entry_admission_historical_v1 as cftc_reused


LEDGER_SOURCE = RESEARCH_ROOT / "btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = RESEARCH_ROOT / "btc_monthly_12m_time_series_momentum_historical.py"
REUSED_CFTC_SOURCE = RESEARCH_ROOT / "btc_dra_cftc_tff_entry_admission_historical_v1.py"
SOURCE_FIELD_SOURCE = REPO_ROOT / "research_pipeline" / "cftc_cme_bitcoin_tff_source.py"
DECIMAL_PARSER_SOURCE = REPO_ROOT / "research_pipeline" / "cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py"
SOURCE_CONTRACT = REPO_ROOT / "research_pipeline" / "cftc-cme-bitcoin-tff-source-contract.v2.json"
HISTORICAL_SOURCE_MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "cftc-tff-dra-entry-admission-historical.v1.manifest.json"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-cftc-dealer-net-position-change-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-cftc-dealer-net-position-change-long-cash-v1.hypothesis.json"

EXPERIMENT_ID = "btc-cftc-dealer-net-position-change-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_CFTC_DEALER_NET_POSITION_CHANGE_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_SOURCE_MANIFEST_SHA256 = "f56ff773e14b2ed54388d2747905e8fbc7d585b54b36126958ecbc7cd238111a"
EXPECTED_LEDGER_SHA256 = "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_REUSED_CFTC_SHA256 = "fdde75be9efe7b9dfe065c46429de75594da88c4d2360b0c7891ee467649635b"
EXPECTED_SOURCE_FIELD_SHA256 = "b56f9ceecb6f1ed1cf10d77756c109c1cd9ad25540ede6c70d555867aaed9eb0"
EXPECTED_DECIMAL_PARSER_SHA256 = "a0a9f3c9d1b190e1cac21dfd9255b7d19b7f0c88afa48c482744c0d5da78eb70"
EXPECTED_SOURCE_CONTRACT_SHA256 = "726d7ebff05d1c9fb5df9399996ff9817b28025d9d696f207191ec8c62e7dde5"
EXPECTED_PRIOR_SHA256 = "f96b8c524d8b679566cb59a2a5d517467035010eb57e2d57f5979d31d645fef2"
EXPECTED_HYPOTHESIS_SHA256 = "14c912b5f74f51a5999cc32e79921c5d519c18bb30d306d66a5879efa105313e"
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_RUNNER": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_CFTC_ARCHIVE_LOADER": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py",
    "FROZEN_CFTC_SOURCE_CONTRACT": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json",
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-cftc-dealer-net-position-change-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-cftc-dealer-net-position-change-long-cash-v1.hypothesis.json",
}
EXPECTED_NON_RUNNER_HASHES = {
    "FROZEN_LONG_CASH_LEDGER": EXPECTED_LEDGER_SHA256,
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": EXPECTED_REFERENCE_SHA256,
    "FROZEN_CFTC_ARCHIVE_LOADER": EXPECTED_REUSED_CFTC_SHA256,
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": EXPECTED_SOURCE_FIELD_SHA256,
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": EXPECTED_DECIMAL_PARSER_SHA256,
    "FROZEN_CFTC_SOURCE_CONTRACT": EXPECTED_SOURCE_CONTRACT_SHA256,
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": EXPECTED_SOURCE_MANIFEST_SHA256,
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": EXPECTED_PRIOR_SHA256,
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": EXPECTED_HYPOTHESIS_SHA256,
}

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
AVAILABILITY_LAG_DAYS = 7
HORIZON_HOURS = 168
ION_EXCLUSION_START = date(2023, 1, 31)
ION_EXCLUSION_END = date(2023, 3, 14)
REDUNDANCY_LIMIT = D("0.80")

DEALER_LONG_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Dealer_Long_All")
DEALER_SHORT_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Dealer_Short_All")
LEVERAGED_LONG_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Lev_Money_Long_All")
LEVERAGED_SHORT_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Lev_Money_Short_All")
ASSET_LONG_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Asset_Mgr_Long_All")
ASSET_SHORT_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index("Pct_of_OI_Asset_Mgr_Short_All")

EXPECTED_PRE_ECONOMIC_GATES = (
    "btc_sha256_and_52608_rows_match",
    "cftc_archives_manifest_and_exact_rows_match",
    "weekly_tuesday_predecessor_ion_exclusion_and_day7_availability_pass",
    "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match",
    "design_dealer_delta_abs_spearman_to_leveraged_money_delta_at_most_0_80",
    "design_dealer_delta_abs_spearman_to_asset_manager_delta_at_most_0_80",
    "validation_dealer_delta_abs_spearman_to_leveraged_money_delta_at_most_0_80",
    "validation_dealer_delta_abs_spearman_to_asset_manager_delta_at_most_0_80",
    "design_minimum_26_nonzero_evaluable_transitions",
    "design_minimum_8_positive_factor_transitions",
    "design_minimum_8_negative_factor_transitions",
    "design_chronological_quartile_breadth_at_least_4_each",
    "design_anchor_month_breadth_at_least_6",
    "design_maximum_anchor_month_share_at_most_25pct",
    "design_median_signed_response_strictly_positive",
    "design_positive_factor_median_raw_return_strictly_positive",
    "design_negative_factor_median_raw_return_strictly_negative",
    "design_one_sided_sign_test_p_value_at_most_0_10",
    "design_top_absolute_signed_response_contribution_at_most_20pct",
    "validation_minimum_26_nonzero_evaluable_transitions",
    "validation_minimum_8_positive_factor_transitions",
    "validation_minimum_8_negative_factor_transitions",
    "validation_chronological_quartile_breadth_at_least_4_each",
    "validation_anchor_month_breadth_at_least_6",
    "validation_maximum_anchor_month_share_at_most_25pct",
    "validation_median_signed_response_strictly_positive",
    "validation_positive_factor_median_raw_return_strictly_positive",
    "validation_negative_factor_median_raw_return_strictly_negative",
    "validation_one_sided_sign_test_p_value_at_most_0_10",
    "validation_top_absolute_signed_response_contribution_at_most_20pct",
)
EXPECTED_ECONOMIC_GATES = (
    "primary_design_normal_total_return_pct_gt_0",
    "primary_design_stress_total_return_pct_gt_0",
    "primary_design_normal_drawdown_at_most_95pct_of_buy_hold",
    "primary_design_normal_calmar_at_least_buy_hold",
    "primary_validation_normal_total_return_pct_gt_0",
    "primary_validation_stress_total_return_pct_gt_0",
    "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold",
    "primary_validation_normal_upside_capture_at_least_60pct",
    "primary_validation_normal_calmar_at_least_buy_hold",
    "primary_validation_position_changes_between_2_and_100",
    "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "primary_normal_positive_annual_total_return_at_least_4_of_5",
    "primary_stress_positive_annual_total_return_at_least_4_of_5",
    "primary_normal_annual_drawdown_non_worse_at_least_4_of_5",
    "primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5",
    "primary_normal_annual_upside_capture_at_least_50pct_at_least_4_of_5",
    "primary_top_year_positive_total_return_contribution_at_most_60pct",
    "primary_validation_top_positive_episode_contribution_at_most_60pct",
    "primary_validation_p90_hold_at_most_17520_hours",
    "primary_validation_terminal_holding_age_at_most_17520_hours",
    "primary_validation_terminal_liquidation_adjusted_return_pct_gt_0",
    "primary_validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class FactorPoint:
    report_date: date
    eligible_at: datetime
    dealer_delta: D
    leveraged_money_delta: D
    asset_manager_delta: D

    @property
    def target_long(self) -> bool:
        return self.dealer_delta > ZERO


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(Q8, rounding=ROUND_HALF_UP), "f")


def nullable(value: D | None) -> str | None:
    return None if value is None else q(value)


def percentile(values: list[D], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = D(len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - D(low))


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _level(row: list[str], long_index: int, short_index: int) -> D:
    parser = cftc_reused.factor_evaluator.parse_factor_decimal
    return parser(row[long_index]) - parser(row[short_index])


def build_factor_points(
    rows: dict[date, list[str]],
    *,
    cutoff: datetime = VALIDATION[1],
) -> tuple[list[FactorPoint], dict[str, int]]:
    points: list[FactorPoint] = []
    exclusions = {
        "non_tuesday": 0,
        "ion_delay": 0,
        "missing_exact_predecessor": 0,
        "eligible_at_or_after_cutoff": 0,
    }
    excluded = {
        day
        for day in rows
        if day.weekday() != 1 or ION_EXCLUSION_START <= day <= ION_EXCLUSION_END
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["non_tuesday"] += 1
            continue
        if ION_EXCLUSION_START <= day <= ION_EXCLUSION_END:
            exclusions["ion_delay"] += 1
            continue
        prior_day = day - timedelta(days=7)
        if prior_day not in rows or prior_day in excluded:
            exclusions["missing_exact_predecessor"] += 1
            continue
        eligible_at = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= cutoff:
            exclusions["eligible_at_or_after_cutoff"] += 1
            continue
        current = rows[day]
        prior = rows[prior_day]
        points.append(
            FactorPoint(
                day,
                eligible_at,
                _level(current, DEALER_LONG_INDEX, DEALER_SHORT_INDEX)
                - _level(prior, DEALER_LONG_INDEX, DEALER_SHORT_INDEX),
                _level(current, LEVERAGED_LONG_INDEX, LEVERAGED_SHORT_INDEX)
                - _level(prior, LEVERAGED_LONG_INDEX, LEVERAGED_SHORT_INDEX),
                _level(current, ASSET_LONG_INDEX, ASSET_SHORT_INDEX)
                - _level(prior, ASSET_LONG_INDEX, ASSET_SHORT_INDEX),
            )
        )
    if not points:
        raise ResearchReject("DATA_REJECT:NO_ELIGIBLE_CFTC_FACTOR_POINTS")
    return points, exclusions


def _midranks(values: list[D]) -> list[D]:
    indexed = sorted(enumerate(values), key=lambda item: item[1])
    ranks = [ZERO] * len(values)
    cursor = 0
    while cursor < len(indexed):
        end = cursor + 1
        while end < len(indexed) and indexed[end][1] == indexed[cursor][1]:
            end += 1
        rank = (D(cursor + 1) + D(end)) / D("2")
        for offset in range(cursor, end):
            ranks[indexed[offset][0]] = rank
        cursor = end
    return ranks


def spearman_correlation(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 3:
        raise ResearchReject("FEATURE_REJECT:CORRELATION_INVENTORY")
    left_rank = _midranks(left)
    right_rank = _midranks(right)
    left_mean = sum(left_rank, ZERO) / D(len(left_rank))
    right_mean = sum(right_rank, ZERO) / D(len(right_rank))
    covariance = sum(
        ((a - left_mean) * (b - right_mean) for a, b in zip(left_rank, right_rank)),
        ZERO,
    )
    left_variance = sum(((value - left_mean) ** 2 for value in left_rank), ZERO)
    right_variance = sum(((value - right_mean) ** 2 for value in right_rank), ZERO)
    denominator = (left_variance * right_variance).sqrt()
    if denominator == ZERO:
        raise ResearchReject("FEATURE_REJECT:CONSTANT_CORRELATION_INPUT")
    return covariance / denominator


def correlation_evidence(
    points: list[FactorPoint], window: tuple[datetime, datetime]
) -> dict[str, Any]:
    selected = [point for point in points if window[0] <= point.eligible_at < window[1]]
    dealer = [point.dealer_delta for point in selected]
    leveraged = spearman_correlation(
        dealer, [point.leveraged_money_delta for point in selected]
    )
    asset = spearman_correlation(
        dealer, [point.asset_manager_delta for point in selected]
    )
    return {
        "observation_count": len(selected),
        "absolute_spearman_limit": q(REDUNDANCY_LIMIT),
        "dealer_to_leveraged_money": {
            "spearman": q(leveraged),
            "absolute_spearman": q(abs(leveraged)),
            "pass": abs(leveraged) <= REDUNDANCY_LIMIT,
        },
        "dealer_to_asset_manager": {
            "spearman": q(asset),
            "absolute_spearman": q(abs(asset)),
            "pass": abs(asset) <= REDUNDANCY_LIMIT,
        },
    }


def _sign_test(successes: int, failures: int) -> D | None:
    total = successes + failures
    if total == 0:
        return None
    numerator = sum(
        D(__import__("math").comb(total, index))
        for index in range(successes, total + 1)
    )
    return numerator / (D(2) ** total)


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
) -> dict[str, Any]:
    bars_by_open = {bar.open_time: bar for bar in bars}
    selected = [
        point
        for point in points
        if window[0] <= point.eligible_at < window[1] and point.dealer_delta != ZERO
    ]
    episodes: list[dict[str, Any]] = []
    last_terminal: datetime | None = None
    overlap_exclusions = 0
    missing_path_exclusions = 0
    for point in selected:
        anchor_at = point.eligible_at
        terminal_at = anchor_at + timedelta(hours=HORIZON_HOURS)
        if terminal_at > window[1]:
            missing_path_exclusions += 1
            continue
        if last_terminal is not None and anchor_at < last_terminal:
            overlap_exclusions += 1
            continue
        anchor = bars_by_open.get(anchor_at)
        terminal = bars_by_open.get(terminal_at)
        path = [
            bars_by_open.get(anchor_at + timedelta(hours=offset))
            for offset in range(HORIZON_HOURS)
        ]
        if anchor is None or terminal is None or any(bar is None for bar in path):
            missing_path_exclusions += 1
            continue
        sign = ONE if point.dealer_delta > ZERO else -ONE
        raw_return = terminal.open / anchor.open - ONE
        signed_response = sign * raw_return
        adverse = max(
            (
                max(ZERO, -(sign * (bar.close / anchor.open - ONE)))
                for bar in path
                if bar is not None
            ),
            default=ZERO,
        )
        episodes.append(
            {
                "report_date": point.report_date.isoformat(),
                "eligible_at": anchor_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "dealer_delta_pct_oi": q(point.dealer_delta),
                "factor_sign": 1 if sign > ZERO else -1,
                "raw_return_168h": q(raw_return),
                "signed_response_168h": q(signed_response),
                "sign_adjusted_mae_168h": q(adverse),
            }
        )
        last_terminal = terminal_at
    signed = [D(item["signed_response_168h"]) for item in episodes]
    positive = [
        D(item["raw_return_168h"])
        for item in episodes
        if item["factor_sign"] > 0
    ]
    negative = [
        D(item["raw_return_168h"])
        for item in episodes
        if item["factor_sign"] < 0
    ]
    successes = sum(value > ZERO for value in signed)
    failures = sum(value < ZERO for value in signed)
    p_value = _sign_test(successes, failures)
    absolute = [abs(value) for value in signed]
    absolute_total = sum(absolute, ZERO)
    top_absolute_share = (
        HUNDRED if absolute_total == ZERO else max(absolute) / absolute_total * HUNDRED
    )
    month_counts: dict[str, int] = {}
    for item in episodes:
        month = item["eligible_at"][:7]
        month_counts[month] = month_counts.get(month, 0) + 1
    maximum_month_share = (
        HUNDRED
        if not episodes
        else D(max(month_counts.values())) / D(len(episodes)) * HUNDRED
    )
    quartiles = [0, 0, 0, 0]
    for index in range(len(episodes)):
        quartiles[min(3, index * 4 // len(episodes))] += 1
    median_signed = percentile(signed, D("0.5"))
    positive_median = percentile(positive, D("0.5"))
    negative_median = percentile(negative, D("0.5"))
    gates = {
        "minimum_26_nonzero_evaluable_transitions": len(episodes) >= 26,
        "minimum_8_positive_factor_transitions": len(positive) >= 8,
        "minimum_8_negative_factor_transitions": len(negative) >= 8,
        "chronological_quartile_breadth_at_least_4_each": all(
            count >= 4 for count in quartiles
        ),
        "anchor_month_breadth_at_least_6": len(month_counts) >= 6,
        "maximum_anchor_month_share_at_most_25pct": maximum_month_share <= D("25"),
        "median_signed_response_strictly_positive": median_signed is not None
        and median_signed > ZERO,
        "positive_factor_median_raw_return_strictly_positive": positive_median
        is not None
        and positive_median > ZERO,
        "negative_factor_median_raw_return_strictly_negative": negative_median
        is not None
        and negative_median < ZERO,
        "one_sided_sign_test_p_value_at_most_0_10": p_value is not None
        and p_value <= D("0.10"),
        "top_absolute_signed_response_contribution_at_most_20pct": top_absolute_share
        <= D("20"),
    }
    return {
        "episodes": episodes,
        "exclusions": {
            "overlapping_window": overlap_exclusions,
            "missing_or_out_of_window_path": missing_path_exclusions,
        },
        "statistics": {
            "episode_count": len(episodes),
            "positive_factor_count": len(positive),
            "negative_factor_count": len(negative),
            "quartile_counts": quartiles,
            "anchor_month_count": len(month_counts),
            "maximum_anchor_month_share_pct": q(maximum_month_share),
            "median_signed_response_168h": nullable(median_signed),
            "positive_factor_median_raw_return_168h": nullable(positive_median),
            "negative_factor_median_raw_return_168h": nullable(negative_median),
            "sign_test_successes": successes,
            "sign_test_failures": failures,
            "one_sided_sign_test_p_value": nullable(p_value),
            "top_absolute_signed_response_contribution_pct": q(top_absolute_share),
            "median_absolute_return_168h": nullable(percentile(absolute, D("0.5"))),
            "p90_absolute_return_168h": nullable(percentile(absolute, D("0.9"))),
            "median_sign_adjusted_mae_168h": nullable(
                percentile(
                    [D(item["sign_adjusted_mae_168h"]) for item in episodes],
                    D("0.5"),
                )
            ),
        },
        "gates": gates,
    }


def evaluate_pre_economic_gates(
    correlations: dict[str, dict[str, Any]],
    predictive: dict[str, dict[str, Any]],
) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "cftc_archives_manifest_and_exact_rows_match": True,
        "weekly_tuesday_predecessor_ion_exclusion_and_day7_availability_pass": True,
        "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match": True,
    }
    for label in ("design", "validation"):
        correlation = correlations[label]
        gates[
            f"{label}_dealer_delta_abs_spearman_to_leveraged_money_delta_at_most_0_80"
        ] = correlation["dealer_to_leveraged_money"]["pass"]
        gates[
            f"{label}_dealer_delta_abs_spearman_to_asset_manager_delta_at_most_0_80"
        ] = correlation["dealer_to_asset_manager"]["pass"]
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_PRE_ECONOMIC_GATES:
        raise ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def simulate_all(
    ledger: ModuleType,
    reference: ModuleType,
    bars: list[Any],
    points: list[FactorPoint],
) -> tuple[dict[str, Any], dict[str, Any]]:
    targets = {point.eligible_at: point.target_long for point in points}

    def one_window(
        window: tuple[datetime, datetime],
    ) -> tuple[dict[str, Any], dict[str, dict[str, D]]]:
        output: dict[str, Any] = {}
        raw: dict[str, dict[str, D]] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            output[scenario], raw[scenario] = ledger.simulate_scenario(
                reference, bars, targets, window, fee, slippage
            )
        return output, raw

    design_output, design_raw = one_window(DESIGN)
    validation_output, validation_raw = one_window(VALIDATION)
    annual = {year: one_window(window) for year, window in ANNUAL.items()}
    outputs = {
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
    }
    raw = {
        "design": design_raw,
        "validation": validation_raw,
        "annual": {year: value[1] for year, value in annual.items()},
    }
    return outputs, raw


def evaluate_economic_gates(
    output: dict[str, Any], raw: dict[str, Any]
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn = raw["design"]["NORMAL"]
    ds = raw["design"]["STRESS"]
    vn = raw["validation"]["NORMAL"]
    vs = raw["validation"]["STRESS"]
    gates: dict[str, bool] = {
        "primary_design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "primary_design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "primary_design_normal_drawdown_at_most_95pct_of_buy_hold": dn["drawdown"]
        <= D("0.95") * dn["buy_hold_drawdown"],
        "primary_design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "primary_validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "primary_validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "primary_validation_normal_drawdown_at_most_90pct_of_buy_hold": vn[
            "drawdown"
        ]
        <= D("0.90") * vn["buy_hold_drawdown"],
        "primary_validation_normal_upside_capture_at_least_60pct": vn[
            "upside_capture"
        ]
        >= D("0.60"),
        "primary_validation_normal_calmar_at_least_buy_hold": vn["calmar"]
        >= vn["buy_hold_calmar"],
        "primary_validation_position_changes_between_2_and_100": D("2")
        <= vn["position_changes"]
        <= D("100"),
        "primary_validation_stress_drawdown_no_more_than_normal_plus_3pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("3"),
    }
    annual = raw["annual"]
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual.values())
    drawdown_nonworse = sum(
        value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"]
        for value in annual.values()
    )
    calmar_breadth = sum(
        value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"]
        for value in annual.values()
    )
    upside_breadth = sum(
        value["NORMAL"]["upside_capture"] >= D("0.50") for value in annual.values()
    )
    positive_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual.values()]
    positive_sum = sum(positive_returns, ZERO)
    top_year = (
        HUNDRED
        if positive_sum == ZERO
        else max(positive_returns) / positive_sum * HUNDRED
    )
    gates.update(
        {
            "primary_normal_positive_annual_total_return_at_least_4_of_5": normal_positive
            >= 4,
            "primary_stress_positive_annual_total_return_at_least_4_of_5": stress_positive
            >= 4,
            "primary_normal_annual_drawdown_non_worse_at_least_4_of_5": drawdown_nonworse
            >= 4,
            "primary_normal_annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5": calmar_breadth
            >= 3,
            "primary_normal_annual_upside_capture_at_least_50pct_at_least_4_of_5": upside_breadth
            >= 4,
            "primary_top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "primary_validation_top_positive_episode_contribution_at_most_60pct": vn[
                "has_positive_episode"
            ]
            == ZERO
            or vn["top_positive_episode_contribution"] <= D("60"),
            "primary_validation_p90_hold_at_most_17520_hours": vn["p90_hold"]
            <= D("17520"),
            "primary_validation_terminal_holding_age_at_most_17520_hours": vn[
                "terminal_holding_age"
            ]
            <= D("17520"),
            "primary_validation_terminal_liquidation_adjusted_return_pct_gt_0": vn[
                "terminal_liquidation_return"
            ]
            > ZERO,
            "primary_validation_terminal_liquidation_cost_at_most_1pp": vn[
                "terminal_liquidation_cost"
            ]
            <= ONE,
        }
    )
    if tuple(gates) != EXPECTED_ECONOMIC_GATES:
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_GATE_DRIFT")
    breadth = {
        "primary_normal_positive_years": f"{normal_positive}_of_5",
        "primary_stress_positive_years": f"{stress_positive}_of_5",
        "primary_normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "primary_normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "primary_normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_5",
        "primary_top_year_positive_total_return_contribution_pct": q(top_year),
        "primary_validation_top_positive_episode_contribution_pct": output[
            "validation"
        ]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get(
        "experiment_id"
    ) != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get(
        "oos_access"
    ) != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != {
        "btc": {
            "path": ".research-state/java-parity/selection-2019-2024.tsv",
            "sha256": EXPECTED_BTC_SHA256,
            "hourly_rows": EXPECTED_BTC_ROWS,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "cftc": {
            "historical_source_manifest_path": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
            "historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256,
            "archive_years": [2019, 2020, 2021, 2022, 2023, 2024],
            "exact_contract_rows": 313,
        },
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_CFTC_DEALER_NET_POSITION_CHANGE_LONG_CASH_V1",
        "factor_identity": "CFTC_TFF_DEALER_NET_PCT_OI_WEEKLY_DELTA_CONTINUATION_LONG_CASH_V1",
        "current_fields": [
            "Pct_of_OI_Dealer_Long_All",
            "Pct_of_OI_Dealer_Short_All",
        ],
        "prior_fields": [
            "Pct_of_OI_Dealer_Long_All",
            "Pct_of_OI_Dealer_Short_All",
        ],
        "exact_predecessor_calendar_days": 7,
        "factor_formula": "CURRENT_DEALER_NET_PCT_OI_MINUS_EXACT_PRIOR_WEEK_DEALER_NET_PCT_OI",
        "long_condition": "FACTOR_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "FACTOR_LESS_THAN_OR_EQUAL_TO_ZERO",
        "availability": "REPORT_DATE_PLUS_7_CALENDAR_DAYS_AT_00_00_UTC",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "ion_exclusion": {
            "start_inclusive": "2023-01-31",
            "end_inclusive": "2023-03-14",
        },
        "position_sizing": "FULL_AVAILABLE_EQUITY_NO_LEVERAGE_NO_SHORT",
        "variants": 1,
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {
            "fee_rate_per_side": "0.0010",
            "adverse_slippage_rate_per_side": "0.0005",
        },
        "STRESS": {
            "fee_rate_per_side": "0.0020",
            "adverse_slippage_rate_per_side": "0.0010",
        },
    }:
        raise ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    if manifest.get("windows") != {
        "design": {
            "start": "2020-01-01T00:00:00",
            "end_exclusive": "2023-01-01T00:00:00",
        },
        "validation": {
            "start": "2023-01-01T00:00:00",
            "end_exclusive": "2025-01-01T00:00:00",
        },
        "predictive_horizon_hours": 168,
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_sets") != {
        "pre_economic": {
            "required": list(EXPECTED_PRE_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
        },
        "economic_if_pre_economic_passes": {
            "required": list(EXPECTED_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_TUNING_OR_OOS",
        },
        "decision": "PRE_ECONOMIC_ALL_PASS_THEN_ECONOMIC_ALL_PASS_OR_CLOSE",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    if {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        if role in EXPECTED_NON_RUNNER_HASHES and binding.get("sha256") != EXPECTED_NON_RUNNER_HASHES[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:FROZEN_SHA256")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or sha256(path) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}:CURRENT_SHA256")


def build_output(
    btc_input: Path, manifest_path: Path
) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    source_manifest, source_manifest_raw = cftc_reused.load_manifest(
        HISTORICAL_SOURCE_MANIFEST
    )
    if hashlib.sha256(source_manifest_raw).hexdigest() != EXPECTED_SOURCE_MANIFEST_SHA256:
        raise ResearchReject("SOURCE_REJECT:HISTORICAL_SOURCE_MANIFEST_SHA256")
    cftc_reused.verify_bindings(source_manifest)
    bars = cftc_reused.load_selection(btc_input, source_manifest)
    if len(bars) != EXPECTED_BTC_ROWS or cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_SHA256")
    rows, archive_evidence = cftc_reused.load_historical_rows(source_manifest)
    points, exclusions = build_factor_points(rows)
    correlations = {
        "design": correlation_evidence(points, DESIGN),
        "validation": correlation_evidence(points, VALIDATION),
    }
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN),
        "validation": predictive_evidence(bars, points, VALIDATION),
    }
    pre_gates, failed_pre = evaluate_pre_economic_gates(correlations, predictive)
    correlation_failures = [
        name for name in failed_pre if "spearman" in name
    ]
    base_result: dict[str, Any] = {
        "schema_version": "1",
        "document_type": "BTC_CFTC_DEALER_NET_POSITION_CHANGE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
        },
        "datasets": {
            "btc": {
                "path": btc_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(btc_input),
                "hourly_rows": len(bars),
            },
            "cftc": {
                "historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256,
                "archive_evidence": archive_evidence,
                "exact_contract_rows": len(rows),
            },
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points": len(points),
            "positive_points": sum(point.dealer_delta > ZERO for point in points),
            "negative_points": sum(point.dealer_delta < ZERO for point in points),
            "zero_points": sum(point.dealer_delta == ZERO for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
            "exclusions": exclusions,
        },
        "nonredundancy": correlations,
        "predictive_evidence": predictive,
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage CME TFF Dealer-positioning and pre-2025 BTC evidence only; a pass is not independent alpha, source-continuity proof, a runtime strategy or activation permission.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        duplicate = bool(correlation_failures)
        base_result.update(
            {
                "status": "DUPLICATE_REJECT_CLOSE_BTC_CFTC_DEALER_NET_POSITION_CHANGE_FAMILY"
                if duplicate
                else "NO_CANDIDATE_CLOSE_BTC_CFTC_DEALER_NET_POSITION_CHANGE_FAMILY_PRE_ECONOMIC",
                "decision": "PERMANENTLY_CLOSE_EXACT_DEALER_WEEKLY_DELTA_FAMILY_WITHOUT_ECONOMIC_ACCESS_DIRECTION_INVERSION_OR_TUNING",
                "candidate_created": False,
                "economic_evidence_accessed": False,
                "economic_evidence": {
                    metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_STOP"
                    for metric in (
                        "fees",
                        "adverse_slippage",
                        "realized_pnl",
                        "unrealized_pnl",
                        "total_pnl",
                        "maximum_drawdown",
                        "holding_age",
                        "terminal_inventory",
                        "breadth_and_path_risk",
                    )
                },
                "economic_gates": "NOT_ACCESSED",
                "failed_economic_gates": [],
            }
        )
        return base_result
    ledger = load_module("dealer_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("dealer_frozen_long_cash_reference", REFERENCE_SOURCE)
    economic_output, economic_raw = simulate_all(ledger, reference, bars, points)
    economic_gates, failed_economic, breadth = evaluate_economic_gates(
        economic_output, economic_raw
    )
    passed = not failed_economic
    base_result.update(
        {
            "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_CFTC_DEALER_NET_POSITION_CHANGE_FAMILY",
            "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_DEALER_WEEKLY_DELTA_FAMILY_WITHOUT_TUNING_OR_OOS",
            "candidate_created": passed,
            "economic_evidence_accessed": True,
            "economic_evidence": economic_output,
            "breadth_and_concentration": breadth,
            "economic_gates": economic_gates,
            "failed_economic_gates": failed_economic,
        }
    )
    return base_result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.manifest.resolve()]
    output_path = args.output.resolve()
    if not all(path.is_relative_to(REPO_ROOT) for path in inputs):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(*inputs)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(
            result,
            stream,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_pre_economic_gates": result["failed_pre_economic_gates"],
                "failed_economic_gates": result["failed_economic_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
