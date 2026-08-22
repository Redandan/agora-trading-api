#!/usr/bin/env python3
"""Deterministic pre-economic screen for the frozen CFTC carry-crash proxy."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import hashlib
import json
from math import ceil, comb
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_cftc_dealer_net_position_change_long_cash_historical as shared


D = shared.D
ZERO = shared.ZERO
ONE = shared.ONE
HUNDRED = shared.HUNDRED
DESIGN = shared.DESIGN
VALIDATION = shared.VALIDATION
AVAILABILITY_LAG_DAYS = shared.AVAILABILITY_LAG_DAYS
ION_EXCLUSION_START = shared.ION_EXCLUSION_START
ION_EXCLUSION_END = shared.ION_EXCLUSION_END
HORIZON_HOURS = 672
LOOKBACK_POINTS = 52
HIGH_RISK_PERCENTILE = D("0.75")
CRASH_DRAWDOWN = D("0.15")

HISTORICAL_SOURCE_MANIFEST = shared.HISTORICAL_SOURCE_MANIFEST
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-leveraged-fund-net-short-level-carry-crash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-leveraged-fund-net-short-level-carry-crash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-cftc-leveraged-fund-net-short-level-carry-crash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_CFTC_LEVERAGED_FUND_NET_SHORT_LEVEL_CARRY_CRASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BTC_SHA256 = shared.EXPECTED_BTC_SHA256
EXPECTED_BTC_ROWS = shared.EXPECTED_BTC_ROWS
EXPECTED_SOURCE_MANIFEST_SHA256 = shared.EXPECTED_SOURCE_MANIFEST_SHA256
EXPECTED_PRIOR_SHA256 = (
    "3c66bc3d514ce6aecaaf673fa6fbaa57a3e886b419db37e9b234fa9e44fccb89"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "cfb0ec1f21fcadffa9b1c59f8242c43782defd0f183770bde041cd2b0d0da9b2"
)

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_PREDICTIVE_RUNNER": "research/btc_cftc_leveraged_fund_net_short_level_carry_crash_historical.py",
    "FROZEN_CFTC_ARCHIVE_LOADER": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py",
    "FROZEN_CFTC_SOURCE_CONTRACT": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json",
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
    "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR": "research_pipeline/examples/btc-cftc-leveraged-fund-net-short-level-carry-crash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-cftc-leveraged-fund-net-short-level-carry-crash-v1.hypothesis.json",
}
EXPECTED_NON_RUNNER_HASHES = {
    "FROZEN_CFTC_ARCHIVE_LOADER": shared.EXPECTED_REUSED_CFTC_SHA256,
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": shared.EXPECTED_SOURCE_FIELD_SHA256,
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": shared.EXPECTED_DECIMAL_PARSER_SHA256,
    "FROZEN_CFTC_SOURCE_CONTRACT": shared.EXPECTED_SOURCE_CONTRACT_SHA256,
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": EXPECTED_SOURCE_MANIFEST_SHA256,
    "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR": EXPECTED_PRIOR_SHA256,
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": EXPECTED_HYPOTHESIS_SHA256,
}

FIELDS = shared.cftc_reused.cftc_source.ORDERED_FIELDS
LONG_INDEX = FIELDS.index("Pct_of_OI_Lev_Money_Long_All")
SHORT_INDEX = FIELDS.index("Pct_of_OI_Lev_Money_Short_All")

PREDICTIVE_GATE_NAMES = (
    "minimum_required_evaluable_episodes",
    "minimum_required_high_risk_episodes",
    "minimum_required_normal_state_episodes",
    "high_risk_chronological_quartile_breadth",
    "high_risk_anchor_year_breadth",
    "high_risk_maximum_anchor_month_share",
    "high_risk_median_path_drawdown_strictly_worse",
    "high_risk_p75_path_drawdown_strictly_worse",
    "high_risk_crash_rate_at_least_10pp_worse",
    "high_risk_median_terminal_return_strictly_worse",
    "high_risk_negative_terminal_rate_at_least_10pp_worse",
    "one_sided_fisher_crash_rate_p_value_at_most_0_10",
    "top_high_risk_path_drawdown_contribution_at_most_40pct",
)
EXPECTED_PRE_ECONOMIC_GATES = (
    "btc_sha256_and_52608_rows_match",
    "cftc_archives_manifest_and_exact_rows_match",
    "weekly_tuesday_ion_exclusion_day7_availability_52_point_warmup_pass",
    "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match",
    *(f"design_{name}" for name in PREDICTIVE_GATE_NAMES),
    *(f"validation_{name}" for name in PREDICTIVE_GATE_NAMES),
)


@dataclass(frozen=True)
class FactorPoint:
    report_date: date
    eligible_at: datetime
    net_short_level: D
    prior_52_p75: D

    @property
    def high_risk(self) -> bool:
        return self.net_short_level > self.prior_52_p75


def nearest_rank(values: list[D], quantile: D) -> D:
    if not values:
        raise shared.ResearchReject("DATA_REJECT:EMPTY_NEAREST_RANK")
    ordered = sorted(values)
    rank = max(1, ceil(float(quantile * D(len(ordered)))))
    return ordered[rank - 1]


def net_short_level(row: list[str]) -> D:
    parser = shared.cftc_reused.factor_evaluator.parse_factor_decimal
    long_value = parser(row[LONG_INDEX])
    short_value = parser(row[SHORT_INDEX])
    if long_value < ZERO or short_value < ZERO:
        raise shared.ResearchReject("DATA_REJECT:NEGATIVE_LEVERAGED_FUND_PERCENT")
    if long_value > HUNDRED or short_value > HUNDRED:
        raise shared.ResearchReject("DATA_REJECT:LEVERAGED_FUND_PERCENT_ABOVE_100")
    return short_value - long_value


def build_factor_points(
    rows: dict[date, list[str]], *, cutoff: datetime = VALIDATION[1]
) -> tuple[list[FactorPoint], dict[str, int]]:
    eligible_levels: list[tuple[date, datetime, D]] = []
    exclusions = {
        "non_tuesday": 0,
        "ion_delay": 0,
        "eligible_at_or_after_cutoff": 0,
        "warmup_prior_52": 0,
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["non_tuesday"] += 1
            continue
        if ION_EXCLUSION_START <= day <= ION_EXCLUSION_END:
            exclusions["ion_delay"] += 1
            continue
        eligible_at = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= cutoff:
            exclusions["eligible_at_or_after_cutoff"] += 1
            continue
        eligible_levels.append((day, eligible_at, net_short_level(rows[day])))

    points: list[FactorPoint] = []
    for index, (day, eligible_at, level) in enumerate(eligible_levels):
        if index < LOOKBACK_POINTS:
            exclusions["warmup_prior_52"] += 1
            continue
        prior = [item[2] for item in eligible_levels[index - LOOKBACK_POINTS : index]]
        points.append(
            FactorPoint(
                report_date=day,
                eligible_at=eligible_at,
                net_short_level=level,
                prior_52_p75=nearest_rank(prior, HIGH_RISK_PERCENTILE),
            )
        )
    if not points:
        raise shared.ResearchReject("DATA_REJECT:NO_ELIGIBLE_CFTC_FACTOR_POINTS")
    return points, exclusions


def one_sided_fisher_greater(a: int, b: int, c: int, d: int) -> D | None:
    total = a + b + c + d
    high_total = a + b
    crash_total = a + c
    if total == 0 or high_total == 0 or crash_total == 0:
        return None
    lower = max(0, high_total - (total - crash_total))
    upper = min(high_total, crash_total)
    denominator = comb(total, high_total)
    probability = ZERO
    for value in range(max(a, lower), upper + 1):
        probability += D(comb(crash_total, value) * comb(total - crash_total, high_total - value)) / D(denominator)
    return probability


def _rate(count: int, total: int) -> D | None:
    return None if total == 0 else D(count) / D(total)


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
    *,
    label: str,
) -> dict[str, Any]:
    bars_by_open = {bar.open_time: bar for bar in bars}
    selected = [point for point in points if window[0] <= point.eligible_at < window[1]]
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
        running_peak = anchor.open
        maximum_drawdown = ZERO
        for bar in path:
            assert bar is not None
            running_peak = max(running_peak, bar.high)
            maximum_drawdown = max(
                maximum_drawdown, (running_peak - bar.low) / running_peak
            )
        terminal_return = terminal.open / anchor.open - ONE
        episodes.append(
            {
                "report_date": point.report_date.isoformat(),
                "eligible_at": anchor_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "net_short_level_pct_oi": shared.q(point.net_short_level),
                "prior_52_p75_pct_oi": shared.q(point.prior_52_p75),
                "state": "HIGH_RISK" if point.high_risk else "NORMAL",
                "terminal_return_672h": shared.q(terminal_return),
                "peak_to_trough_max_drawdown_672h": shared.q(maximum_drawdown),
                "crash_15pct": maximum_drawdown >= CRASH_DRAWDOWN,
            }
        )
        last_terminal = terminal_at

    high = [item for item in episodes if item["state"] == "HIGH_RISK"]
    normal = [item for item in episodes if item["state"] == "NORMAL"]
    high_dd = [D(item["peak_to_trough_max_drawdown_672h"]) for item in high]
    normal_dd = [D(item["peak_to_trough_max_drawdown_672h"]) for item in normal]
    high_returns = [D(item["terminal_return_672h"]) for item in high]
    normal_returns = [D(item["terminal_return_672h"]) for item in normal]
    high_crashes = sum(bool(item["crash_15pct"]) for item in high)
    normal_crashes = sum(bool(item["crash_15pct"]) for item in normal)
    high_negative = sum(value < ZERO for value in high_returns)
    normal_negative = sum(value < ZERO for value in normal_returns)
    high_crash_rate = _rate(high_crashes, len(high))
    normal_crash_rate = _rate(normal_crashes, len(normal))
    high_negative_rate = _rate(high_negative, len(high))
    normal_negative_rate = _rate(normal_negative, len(normal))
    fisher_p = one_sided_fisher_greater(
        high_crashes,
        len(high) - high_crashes,
        normal_crashes,
        len(normal) - normal_crashes,
    )
    high_median_dd = shared.percentile(high_dd, D("0.5"))
    normal_median_dd = shared.percentile(normal_dd, D("0.5"))
    high_p75_dd = shared.percentile(high_dd, D("0.75"))
    normal_p75_dd = shared.percentile(normal_dd, D("0.75"))
    high_median_return = shared.percentile(high_returns, D("0.5"))
    normal_median_return = shared.percentile(normal_returns, D("0.5"))
    total_high_dd = sum(high_dd, ZERO)
    top_high_dd_share = (
        HUNDRED
        if total_high_dd == ZERO
        else max(high_dd) / total_high_dd * HUNDRED
    )
    high_month_counts: dict[str, int] = {}
    for item in high:
        month = item["eligible_at"][:7]
        high_month_counts[month] = high_month_counts.get(month, 0) + 1
    maximum_high_month_share = (
        HUNDRED
        if not high
        else D(max(high_month_counts.values())) / D(len(high)) * HUNDRED
    )
    quartile_high_counts = [0, 0, 0, 0]
    for index, item in enumerate(episodes):
        if item["state"] == "HIGH_RISK":
            quartile_high_counts[min(3, index * 4 // len(episodes))] += 1
    high_years = sorted({item["eligible_at"][:4] for item in high})

    minimum_total = 30 if label == "design" else 20
    minimum_high = 6 if label == "design" else 4
    minimum_normal = 18 if label == "design" else 12
    minimum_quartiles = 3 if label == "design" else 2
    minimum_years = 2
    maximum_month_pct = D("25") if label == "design" else D("35")
    gates = {
        "minimum_required_evaluable_episodes": len(episodes) >= minimum_total,
        "minimum_required_high_risk_episodes": len(high) >= minimum_high,
        "minimum_required_normal_state_episodes": len(normal) >= minimum_normal,
        "high_risk_chronological_quartile_breadth": sum(count > 0 for count in quartile_high_counts) >= minimum_quartiles,
        "high_risk_anchor_year_breadth": len(high_years) >= minimum_years,
        "high_risk_maximum_anchor_month_share": maximum_high_month_share <= maximum_month_pct,
        "high_risk_median_path_drawdown_strictly_worse": high_median_dd is not None and normal_median_dd is not None and high_median_dd > normal_median_dd,
        "high_risk_p75_path_drawdown_strictly_worse": high_p75_dd is not None and normal_p75_dd is not None and high_p75_dd > normal_p75_dd,
        "high_risk_crash_rate_at_least_10pp_worse": high_crash_rate is not None and normal_crash_rate is not None and high_crash_rate >= normal_crash_rate + D("0.10"),
        "high_risk_median_terminal_return_strictly_worse": high_median_return is not None and normal_median_return is not None and high_median_return < normal_median_return,
        "high_risk_negative_terminal_rate_at_least_10pp_worse": high_negative_rate is not None and normal_negative_rate is not None and high_negative_rate >= normal_negative_rate + D("0.10"),
        "one_sided_fisher_crash_rate_p_value_at_most_0_10": fisher_p is not None and fisher_p <= D("0.10"),
        "top_high_risk_path_drawdown_contribution_at_most_40pct": top_high_dd_share <= D("40"),
    }
    if tuple(gates) != PREDICTIVE_GATE_NAMES:
        raise shared.ResearchReject("MANIFEST_REJECT:PREDICTIVE_GATE_DRIFT")
    return {
        "episodes": episodes,
        "exclusions": {
            "overlapping_window": overlap_exclusions,
            "missing_or_out_of_window_path": missing_path_exclusions,
        },
        "thresholds": {
            "minimum_evaluable_episodes": minimum_total,
            "minimum_high_risk_episodes": minimum_high,
            "minimum_normal_state_episodes": minimum_normal,
            "minimum_high_risk_chronological_quartiles": minimum_quartiles,
            "minimum_high_risk_anchor_years": minimum_years,
            "maximum_high_risk_anchor_month_share_pct": shared.q(maximum_month_pct),
            "crash_drawdown_pct": shared.q(CRASH_DRAWDOWN * HUNDRED),
            "minimum_crash_and_negative_rate_delta_pp": "10.00000000",
            "maximum_fisher_p_value": "0.10000000",
            "maximum_top_high_risk_path_drawdown_contribution_pct": "40.00000000",
        },
        "statistics": {
            "episode_count": len(episodes),
            "high_risk_count": len(high),
            "normal_state_count": len(normal),
            "high_risk_quartile_counts": quartile_high_counts,
            "high_risk_anchor_years": high_years,
            "high_risk_anchor_month_count": len(high_month_counts),
            "maximum_high_risk_anchor_month_share_pct": shared.q(maximum_high_month_share),
            "high_risk_median_path_drawdown_672h": shared.nullable(high_median_dd),
            "normal_state_median_path_drawdown_672h": shared.nullable(normal_median_dd),
            "high_risk_p75_path_drawdown_672h": shared.nullable(high_p75_dd),
            "normal_state_p75_path_drawdown_672h": shared.nullable(normal_p75_dd),
            "high_risk_crash_count": high_crashes,
            "normal_state_crash_count": normal_crashes,
            "high_risk_crash_rate": shared.nullable(high_crash_rate),
            "normal_state_crash_rate": shared.nullable(normal_crash_rate),
            "high_risk_median_terminal_return_672h": shared.nullable(high_median_return),
            "normal_state_median_terminal_return_672h": shared.nullable(normal_median_return),
            "high_risk_negative_terminal_rate": shared.nullable(high_negative_rate),
            "normal_state_negative_terminal_rate": shared.nullable(normal_negative_rate),
            "one_sided_fisher_crash_rate_p_value": shared.nullable(fisher_p),
            "top_high_risk_path_drawdown_contribution_pct": shared.q(top_high_dd_share),
        },
        "gates": gates,
    }


def evaluate_pre_economic_gates(
    predictive: dict[str, dict[str, Any]],
) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "cftc_archives_manifest_and_exact_rows_match": True,
        "weekly_tuesday_ion_exclusion_day7_availability_52_point_warmup_pass": True,
        "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_PRE_ECONOMIC_GATES:
        raise shared.ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def expected_policy() -> dict[str, Any]:
    return {
        "factor_identity": "CFTC_TFF_LEVERAGED_FUND_NET_SHORT_PCT_OI_LEVEL_TOP_QUARTILE_CARRY_CRASH_PROXY_V1",
        "long_field": "Pct_of_OI_Lev_Money_Long_All",
        "short_field": "Pct_of_OI_Lev_Money_Short_All",
        "level_formula": "CURRENT_LEVERAGED_FUND_SHORT_PCT_OI_MINUS_CURRENT_LEVERAGED_FUND_LONG_PCT_OI",
        "lookback_eligible_weekly_points": LOOKBACK_POINTS,
        "percentile": "0.75",
        "percentile_rule": "NEAREST_RANK_ASCENDING_WITHOUT_CURRENT_OBSERVATION",
        "high_risk_condition": "LEVEL_STRICTLY_GREATER_THAN_PRIOR_52_P75",
        "availability": "REPORT_DATE_PLUS_7_CALENDAR_DAYS_AT_00_00_UTC",
        "execution_anchor": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "ion_exclusion": {
            "start_inclusive": "2023-01-31",
            "end_inclusive": "2023-03-14",
        },
        "predictive_horizon_hours": HORIZON_HOURS,
        "crash_definition": "PEAK_TO_TROUGH_MAX_DRAWDOWN_AT_LEAST_15_PERCENT_WITHIN_672H",
        "variants": 1,
        "strategy_economics": "DENY_UNLESS_ALL_PRE_ECONOMIC_GATES_PASS",
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise shared.ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise shared.ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
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
        raise shared.ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("predictive_policy") != expected_policy():
        raise shared.ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("windows") != {
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "predictive_horizon_hours": HORIZON_HOURS,
        "predictive_overlap_rule": "KEEP_FIRST_THEN_SKIP_ANCHORS_BEFORE_672H_TERMINAL",
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "required": list(EXPECTED_PRE_ECONOMIC_GATES),
        "pass": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE",
        "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise shared.ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    if {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise shared.ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:PATH")
        if role in EXPECTED_NON_RUNNER_HASHES and binding.get("sha256") != EXPECTED_NON_RUNNER_HASHES[role]:
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:FROZEN_SHA256")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or shared.sha256(path) != binding.get("sha256"):
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:CURRENT_SHA256")


def build_output(btc_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    source_manifest, source_manifest_raw = shared.cftc_reused.load_manifest(HISTORICAL_SOURCE_MANIFEST)
    if hashlib.sha256(source_manifest_raw).hexdigest() != EXPECTED_SOURCE_MANIFEST_SHA256:
        raise shared.ResearchReject("SOURCE_REJECT:HISTORICAL_SOURCE_MANIFEST_SHA256")
    shared.cftc_reused.verify_bindings(source_manifest)
    bars = shared.cftc_reused.load_selection(btc_input, source_manifest)
    if len(bars) != EXPECTED_BTC_ROWS or shared.cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise shared.ResearchReject("DATA_REJECT:BTC_ROWS_OR_SHA256")
    rows, archive_evidence = shared.cftc_reused.load_historical_rows(source_manifest)
    points, exclusions = build_factor_points(rows)
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(bars, points, VALIDATION, label="validation"),
    }
    gates, failed = evaluate_pre_economic_gates(predictive)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_CFTC_LEVERAGED_FUND_NET_SHORT_LEVEL_CARRY_CRASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BTC_CFTC_LEVERAGED_FUND_NET_SHORT_CARRY_CRASH_PROXY_FAMILY_PRE_ECONOMIC",
        "decision": "FREEZE_SEPARATE_MATCHED_CAPITAL_ECONOMIC_EXPERIMENT_WITHOUT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_NET_SHORT_LEVEL_CARRY_CRASH_PROXY_WITHOUT_ECONOMIC_ACCESS_OR_TUNING",
        "candidate_created": False,
        "economic_evidence_accessed": False,
        "oos_opened": False,
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(btc_input), "hourly_rows": len(bars)},
            "cftc": {"historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256, "archive_evidence": archive_evidence, "exact_contract_rows": len(rows)},
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points_after_warmup": len(points),
            "high_risk_points": sum(point.high_risk for point in points),
            "normal_state_points": sum(not point.high_risk for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
            "exclusions": exclusions,
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": gates,
        "failed_pre_economic_gates": failed,
        "economic_evidence": {
            metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_SCREEN"
            for metric in ("fees", "adverse_slippage", "realized_pnl", "unrealized_pnl", "total_pnl", "maximum_drawdown", "holding_age", "terminal_inventory", "breadth_and_path_risk")
        },
        "claim_boundary": "Historical present-vintage CME TFF and pre-2025 BTC evidence only. The screen tests one public carry-crash proxy; it does not measure actual futures basis, establish independent alpha, create a strategy candidate or authorize activation.",
        "scope_note": "No strategy economics, paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


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
        raise shared.ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise shared.ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise shared.ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(*inputs)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": shared.sha256(output_path),
        "failed_pre_economic_gates": result["failed_pre_economic_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
