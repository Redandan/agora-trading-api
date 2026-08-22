#!/usr/bin/env python3
"""Deterministic pre-economic screen for the frozen RRPONTSYD liquidity hypothesis."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import hashlib
import json
from math import comb
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
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
HORIZON_HOURS = 168
LOOKBACK_OBSERVATIONS = 4
AVAILABILITY_LAG_DAYS = 3
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52608
EXPECTED_RRP_SHA256 = "ef424a4861a04a9731b269224662c0ebda3e5d3050019e02b8a29ebad8f5f0a5"
EXPECTED_RRP_ROWS = 1826
EXPECTED_COMPLETE_WEEKS = 365
EXPECTED_SOURCE_BUNDLE_SHA256 = "21b4ad77c0a37413aa0524aac7b3e18188740e51bc3d70682795330c39dae9d5"
EXPECTED_PRIOR_SHA256 = "e1df8f64645caa79809065f0053f7e486cb91e8f78f10ea3d400a01d158ced93"
EXPECTED_HYPOTHESIS_SHA256 = "434e0d870ebbbe088367c197b6126165ed1b8608eac339407dd84e4bbb4cca3d"
EXPERIMENT_ID = "btc-fred-on-rrp-drawdown-liquidity-support-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_FRED_ON_RRP_DRAWDOWN_LIQUIDITY_SUPPORT_HISTORICAL_MANIFEST_V1"

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_PRE_ECONOMIC_RUNNER": "research/btc_fred_on_rrp_drawdown_liquidity_support_historical.py",
    "FROZEN_H1_PARSER_AND_DETERMINISTIC_STATISTICS": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_FAIL_CLOSED_RRPONTSYD_SOURCE_PROBE": "research/fred_rrpontsyd_source_probe.py",
    "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-fred-on-rrp-drawdown-liquidity-support-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-fred-on-rrp-drawdown-liquidity-support-long-cash-v1.hypothesis.json",
    "SEALED_OFFICIAL_RRPONTSYD_SOURCE_BUNDLE": ".research-state/experiments/btc-fred-on-rrp-drawdown-liquidity-support-historical-v1/inputs/fred-rrpontsyd-source-bundle.json",
    "SEALED_NORMALIZED_RRPONTSYD_CORPUS": ".research-state/experiments/btc-fred-on-rrp-drawdown-liquidity-support-historical-v1/inputs/fred-rrpontsyd-2018-2024.normalized.csv",
}

PREDICTIVE_GATE_NAMES = (
    "minimum_evaluable_episodes",
    "minimum_supportive_episodes",
    "minimum_other_episodes",
    "both_states_chronological_quartile_breadth",
    "both_states_anchor_year_breadth",
    "maximum_state_anchor_month_share",
    "supportive_median_terminal_return_at_least_25bp_higher",
    "supportive_negative_terminal_rate_at_least_5pp_lower",
    "one_sided_fisher_negative_rate_p_value_at_most_0_10",
    "supportive_median_path_drawdown_non_worse",
    "supportive_p75_path_drawdown_non_worse",
    "annual_median_return_direction_breadth",
    "top_positive_annual_median_return_delta_contribution_at_most_60pct",
)
EXPECTED_GATES = (
    "btc_sha256_and_52608_rows_match",
    "rrpontsyd_sha256_rows_weekly_lattice_and_day3_availability_match",
    "source_bundle_runner_prior_hypothesis_and_probe_sha256_match",
    *(f"design_{name}" for name in PREDICTIVE_GATE_NAMES),
    *(f"validation_{name}" for name in PREDICTIVE_GATE_NAMES),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class FactorPoint:
    observation_date: date
    eligible_at: datetime
    current_value: D
    prior_four_week_value: D

    @property
    def supportive(self) -> bool:
        return self.current_value < self.prior_four_week_value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_rrp(path: Path) -> list[tuple[date, D | None]]:
    if not path.is_file() or sha256(path) != EXPECTED_RRP_SHA256:
        raise ResearchReject("DATA_REJECT:RRPONTSYD_SHA256")
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if not rows or rows[0] != ["observation_date", "rrpontsyd_billions_usd"]:
        raise ResearchReject("DATA_REJECT:RRPONTSYD_HEADER")
    parsed: list[tuple[date, D | None]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise ResearchReject(f"DATA_REJECT:RRPONTSYD_ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1]) if row[1] else None
        except Exception as error:
            raise ResearchReject(f"DATA_REJECT:RRPONTSYD_PARSE:{index}") from error
        if value is not None and (value < ZERO or value > D("5000")):
            raise ResearchReject(f"DATA_REJECT:RRPONTSYD_VALUE:{index}")
        parsed.append((day, value))
    if len(parsed) != EXPECTED_RRP_ROWS:
        raise ResearchReject(f"DATA_REJECT:RRPONTSYD_ROWS:{len(parsed)}")
    if parsed[0][0] != date(2018, 1, 2) or parsed[-1][0] != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:RRPONTSYD_BOUNDARY")
    if len({day for day, _ in parsed}) != len(parsed):
        raise ResearchReject("DATA_REJECT:RRPONTSYD_DUPLICATE")
    for prior, current in zip(parsed, parsed[1:], strict=False):
        gap = current[0] - prior[0]
        if gap <= timedelta(0) or gap > timedelta(days=5):
            raise ResearchReject("DATA_REJECT:RRPONTSYD_DATE_ORDER_OR_GAP")
    return parsed


def build_factor_points(rows: list[tuple[date, D | None]]) -> list[FactorPoint]:
    by_week: dict[date, tuple[date, D]] = {}
    last_complete_week_start = date(2024, 12, 23)
    for day, value in rows:
        if value is None:
            continue
        week_start = day - timedelta(days=day.weekday())
        if week_start > last_complete_week_start:
            continue
        prior = by_week.get(week_start)
        if prior is None or day > prior[0]:
            by_week[week_start] = (day, value)
    expected_starts = [
        date(2018, 1, 1) + timedelta(days=7 * index)
        for index in range(EXPECTED_COMPLETE_WEEKS)
    ]
    if any(week not in by_week for week in expected_starts):
        raise ResearchReject("DATA_REJECT:RRPONTSYD_EMPTY_COMPLETE_WEEK")
    endpoints = [by_week[week] for week in expected_starts]
    points = []
    for index in range(LOOKBACK_OBSERVATIONS, len(endpoints)):
        current = endpoints[index]
        prior = endpoints[index - LOOKBACK_OBSERVATIONS]
        week_start = expected_starts[index]
        points.append(
            FactorPoint(
                observation_date=current[0],
                eligible_at=datetime.combine(
                    week_start + timedelta(days=6 + AVAILABILITY_LAG_DAYS),
                    datetime.min.time(),
                ),
                current_value=current[1],
                prior_four_week_value=prior[1],
            )
        )
    if len(points) != EXPECTED_COMPLETE_WEEKS - LOOKBACK_OBSERVATIONS:
        raise ResearchReject("DATA_REJECT:RRPONTSYD_FACTOR_COUNT")
    return points


def one_sided_fisher_less(a: int, b: int, c: int, d: int) -> D | None:
    total = a + b + c + d
    supportive_total = a + b
    negative_total = a + c
    if total == 0 or supportive_total == 0 or negative_total == 0:
        return None
    lower = max(0, supportive_total - (total - negative_total))
    upper = min(supportive_total, negative_total)
    denominator = comb(total, supportive_total)
    probability = ZERO
    for value in range(lower, min(a, upper) + 1):
        probability += D(
            comb(negative_total, value)
            * comb(total - negative_total, supportive_total - value)
        ) / D(denominator)
    return probability


def _rate(count: int, total: int) -> D | None:
    return None if total == 0 else D(count) / D(total)


def _state_values(episodes: list[dict[str, Any]], state: str, key: str) -> list[D]:
    return [D(item[key]) for item in episodes if item["state"] == state]


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
    missing_paths = 0
    for point in selected:
        anchor_at = point.eligible_at
        terminal_at = anchor_at + timedelta(hours=HORIZON_HOURS)
        if terminal_at > window[1]:
            missing_paths += 1
            continue
        anchor = bars_by_open.get(anchor_at)
        terminal = bars_by_open.get(terminal_at)
        path = [
            bars_by_open.get(anchor_at + timedelta(hours=offset))
            for offset in range(HORIZON_HOURS)
        ]
        if anchor is None or terminal is None or any(bar is None for bar in path):
            missing_paths += 1
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
                "observation_date": point.observation_date.isoformat(),
                "eligible_at": anchor_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "state": "SUPPORTIVE" if point.supportive else "OTHER",
                "current_rrpontsyd_billions_usd": shared.q(point.current_value),
                "prior_four_week_rrpontsyd_billions_usd": shared.q(
                    point.prior_four_week_value
                ),
                "terminal_return_168h": shared.q(terminal_return),
                "peak_to_trough_max_drawdown_168h": shared.q(maximum_drawdown),
            }
        )

    supportive = [item for item in episodes if item["state"] == "SUPPORTIVE"]
    other = [item for item in episodes if item["state"] == "OTHER"]
    supportive_returns = _state_values(episodes, "SUPPORTIVE", "terminal_return_168h")
    other_returns = _state_values(episodes, "OTHER", "terminal_return_168h")
    supportive_dd = _state_values(
        episodes, "SUPPORTIVE", "peak_to_trough_max_drawdown_168h"
    )
    other_dd = _state_values(
        episodes, "OTHER", "peak_to_trough_max_drawdown_168h"
    )
    supportive_negative = sum(value < ZERO for value in supportive_returns)
    other_negative = sum(value < ZERO for value in other_returns)
    supportive_negative_rate = _rate(supportive_negative, len(supportive))
    other_negative_rate = _rate(other_negative, len(other))
    fisher_p = one_sided_fisher_less(
        supportive_negative,
        len(supportive) - supportive_negative,
        other_negative,
        len(other) - other_negative,
    )
    supportive_median_return = shared.percentile(supportive_returns, D("0.5"))
    other_median_return = shared.percentile(other_returns, D("0.5"))
    supportive_median_dd = shared.percentile(supportive_dd, D("0.5"))
    other_median_dd = shared.percentile(other_dd, D("0.5"))
    supportive_p75_dd = shared.percentile(supportive_dd, D("0.75"))
    other_p75_dd = shared.percentile(other_dd, D("0.75"))

    quartile_counts = {state: [0, 0, 0, 0] for state in ("SUPPORTIVE", "OTHER")}
    month_counts = {state: {} for state in quartile_counts}
    year_counts = {state: set() for state in quartile_counts}
    for index, item in enumerate(episodes):
        state = item["state"]
        quartile_counts[state][min(3, index * 4 // len(episodes))] += 1
        month = item["eligible_at"][:7]
        month_counts[state][month] = month_counts[state].get(month, 0) + 1
        year_counts[state].add(item["eligible_at"][:4])
    maximum_month_share = max(
        (
            D(max(month_counts[state].values()))
            / D(sum(month_counts[state].values()))
            * HUNDRED
            for state in month_counts
            if month_counts[state]
        ),
        default=HUNDRED,
    )
    annual_deltas: dict[str, D] = {}
    for year in sorted({item["eligible_at"][:4] for item in episodes}):
        annual_supportive = [
            D(item["terminal_return_168h"])
            for item in supportive
            if item["eligible_at"].startswith(year)
        ]
        annual_other = [
            D(item["terminal_return_168h"])
            for item in other
            if item["eligible_at"].startswith(year)
        ]
        supportive_median = shared.percentile(annual_supportive, D("0.5"))
        other_median = shared.percentile(annual_other, D("0.5"))
        if supportive_median is not None and other_median is not None:
            annual_deltas[year] = supportive_median - other_median
    annual_wins = sum(value > ZERO for value in annual_deltas.values())
    positive_annual = [value for value in annual_deltas.values() if value > ZERO]
    top_positive_annual_share = (
        HUNDRED
        if not positive_annual
        else max(positive_annual) / sum(positive_annual, ZERO) * HUNDRED
    )

    minimum_total = 180 if label == "design" else 90
    minimum_each = 40 if label == "design" else 20
    minimum_quartiles = 4 if label == "design" else 3
    minimum_years = 3 if label == "design" else 2
    minimum_annual_wins = 3 if label == "design" else 2
    gates = {
        "minimum_evaluable_episodes": len(episodes) >= minimum_total,
        "minimum_supportive_episodes": len(supportive) >= minimum_each,
        "minimum_other_episodes": len(other) >= minimum_each,
        "both_states_chronological_quartile_breadth": all(
            sum(count > 0 for count in counts) >= minimum_quartiles
            for counts in quartile_counts.values()
        ),
        "both_states_anchor_year_breadth": all(
            len(years) >= minimum_years for years in year_counts.values()
        ),
        "maximum_state_anchor_month_share": maximum_month_share <= D("15"),
        "supportive_median_terminal_return_at_least_25bp_higher": supportive_median_return is not None
        and other_median_return is not None
        and supportive_median_return >= other_median_return + D("0.0025"),
        "supportive_negative_terminal_rate_at_least_5pp_lower": supportive_negative_rate is not None
        and other_negative_rate is not None
        and supportive_negative_rate <= other_negative_rate - D("0.05"),
        "one_sided_fisher_negative_rate_p_value_at_most_0_10": fisher_p is not None
        and fisher_p <= D("0.10"),
        "supportive_median_path_drawdown_non_worse": supportive_median_dd is not None
        and other_median_dd is not None
        and supportive_median_dd <= other_median_dd,
        "supportive_p75_path_drawdown_non_worse": supportive_p75_dd is not None
        and other_p75_dd is not None
        and supportive_p75_dd <= other_p75_dd,
        "annual_median_return_direction_breadth": annual_wins >= minimum_annual_wins,
        "top_positive_annual_median_return_delta_contribution_at_most_60pct": top_positive_annual_share
        <= D("60"),
    }
    if tuple(gates) != PREDICTIVE_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:PREDICTIVE_GATE_DRIFT")
    return {
        "episodes": episodes,
        "exclusions": {"missing_or_out_of_window_path": missing_paths},
        "thresholds": {
            "minimum_evaluable_episodes": minimum_total,
            "minimum_episodes_per_state": minimum_each,
            "minimum_chronological_quartiles_per_state": minimum_quartiles,
            "minimum_anchor_years_per_state": minimum_years,
            "maximum_state_anchor_month_share_pct": "15.00000000",
            "minimum_median_terminal_return_delta": "0.00250000",
            "minimum_negative_terminal_rate_delta": "0.05000000",
            "maximum_fisher_p_value": "0.10000000",
            "minimum_annual_median_return_direction_wins": minimum_annual_wins,
            "maximum_top_positive_annual_delta_contribution_pct": "60.00000000",
        },
        "statistics": {
            "episode_count": len(episodes),
            "supportive_count": len(supportive),
            "other_count": len(other),
            "supportive_quartile_counts": quartile_counts["SUPPORTIVE"],
            "other_quartile_counts": quartile_counts["OTHER"],
            "supportive_anchor_years": sorted(year_counts["SUPPORTIVE"]),
            "other_anchor_years": sorted(year_counts["OTHER"]),
            "maximum_state_anchor_month_share_pct": shared.q(maximum_month_share),
            "supportive_median_terminal_return_168h": shared.nullable(supportive_median_return),
            "other_median_terminal_return_168h": shared.nullable(other_median_return),
            "supportive_negative_terminal_rate": shared.nullable(supportive_negative_rate),
            "other_negative_terminal_rate": shared.nullable(other_negative_rate),
            "one_sided_fisher_negative_rate_p_value": shared.nullable(fisher_p),
            "supportive_median_path_drawdown_168h": shared.nullable(supportive_median_dd),
            "other_median_path_drawdown_168h": shared.nullable(other_median_dd),
            "supportive_p75_path_drawdown_168h": shared.nullable(supportive_p75_dd),
            "other_p75_path_drawdown_168h": shared.nullable(other_p75_dd),
            "annual_median_return_deltas": {
                year: shared.q(value) for year, value in annual_deltas.items()
            },
            "annual_median_return_direction_wins": annual_wins,
            "top_positive_annual_median_return_delta_contribution_pct": shared.q(
                top_positive_annual_share
            ),
        },
        "gates": gates,
    }


def expected_policy() -> dict[str, Any]:
    return {
        "factor_identity": "FRED_RRPONTSYD_COMPLETE_WEEK_LAST_AVAILABLE_BUSINESS_DAY_STRICTLY_BELOW_VALUE_FOUR_COMPLETE_WEEKS_EARLIER_V1",
        "supportive_condition": "CURRENT_COMPLETE_WEEK_LAST_AVAILABLE_RRPONTSYD_STRICTLY_BELOW_VALUE_FOUR_COMPLETE_WEEKS_EARLIER",
        "other_condition": "OTHERWISE",
        "availability": "COMPLETE_WEEK_SUNDAY_PLUS_3_CALENDAR_DAYS_AT_00_00_UTC",
        "execution_anchor": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "predictive_horizon_hours": HORIZON_HOURS,
        "variants": 1,
        "strategy_economics": "DENY_UNLESS_ALL_PRE_ECONOMIC_GATES_PASS",
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != {
        "btc": {
            "path": ".research-state/java-parity/selection-2019-2024.tsv",
            "sha256": EXPECTED_BTC_SHA256,
            "hourly_rows": EXPECTED_BTC_ROWS,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "rrpontsyd": {
            "path": ".research-state/experiments/btc-fred-on-rrp-drawdown-liquidity-support-historical-v1/inputs/fred-rrpontsyd-2018-2024.normalized.csv",
            "sha256": EXPECTED_RRP_SHA256,
            "rows": EXPECTED_RRP_ROWS,
            "first_date": "2018-01-02",
            "last_date": "2024-12-31",
            "missing_value_rows": 81,
            "present_vintage": True,
        },
        "source_bundle": {
            "path": ".research-state/experiments/btc-fred-on-rrp-drawdown-liquidity-support-historical-v1/inputs/fred-rrpontsyd-source-bundle.json",
            "sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
        },
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("predictive_policy") != expected_policy():
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("windows") != {
        "design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "predictive_horizon_hours": HORIZON_HOURS,
        "predictive_overlap_rule": "WEEKLY_168H_EPISODES_NON_OVERLAPPING_BY_CONSTRUCTION",
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "required": list(EXPECTED_GATES),
        "pass": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE",
        "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or sha256(path) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}:SHA256")


def evaluate_gates(predictive: dict[str, dict[str, Any]]) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "rrpontsyd_sha256_rows_weekly_lattice_and_day3_availability_match": True,
        "source_bundle_runner_prior_hypothesis_and_probe_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_GATES:
        raise ResearchReject("MANIFEST_REJECT:GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def build_output(btc_input: Path, rrp_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    if sha256(btc_input) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    bars = shared.cftc_reused.base.parse_rows(btc_input.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or shared.cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    if bars[-1].close_time > VALIDATION[1]:
        raise ResearchReject("OOS_REJECT:BTC_CUTOFF")
    rrp_rows = load_rrp(rrp_input)
    points = build_factor_points(rrp_rows)
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(bars, points, VALIDATION, label="validation"),
    }
    gates, failed = evaluate_gates(predictive)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_FRED_ON_RRP_DRAWDOWN_LIQUIDITY_SUPPORT_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BTC_FRED_ON_RRP_DRAWDOWN_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        "decision": "FREEZE_SEPARATE_MATCHED_CAPITAL_ECONOMIC_EXPERIMENT_WITHOUT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_RRPONTSYD_FOUR_WEEK_DRAWDOWN_FAMILY_WITHOUT_ECONOMIC_ACCESS_OR_TUNING",
        "candidate_created": False,
        "economic_evidence_accessed": False,
        "oos_opened": False,
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_input), "hourly_rows": len(bars)},
            "rrpontsyd": {"path": rrp_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(rrp_input), "rows": len(rrp_rows)},
            "source_bundle_sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points": len(points),
            "supportive_points": sum(point.supportive for point in points),
            "other_points": sum(not point.supportive for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": gates,
        "failed_pre_economic_gates": failed,
        "economic_evidence": {
            metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_SCREEN"
            for metric in ("fees", "adverse_slippage", "realized_pnl", "unrealized_pnl", "total_pnl", "maximum_drawdown", "holding_age", "terminal_inventory", "breadth_and_path_risk")
        },
        "claim_boundary": "Historical present-vintage RRPONTSYD and pre-2025 BTC evidence only. Federal Reserve balance-sheet mechanics do not establish independent BTC alpha, and this screen does not create a strategy candidate or authorize activation.",
        "scope_note": "No strategy economics, paid API, second timer, second writer, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--rrp-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.rrp_input.resolve(), args.manifest.resolve()]
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
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_pre_economic_gates": result["failed_pre_economic_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
