#!/usr/bin/env python3
"""Deterministic pre-economic screen for weekly BTC exchange net-inflow pressure."""

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
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52608
EXPECTED_FLOW_SHA256 = "725bab20952e98c3c7cbb62a9d5bc3f96590a62ea2940fa3feb8bcd2f66e91c1"
EXPECTED_FLOW_ROWS = 2557
EXPECTED_COMPLETE_WEEKS = 365
EXPECTED_SOURCE_BUNDLE_SHA256 = "0b0ab780d3efd64b56e4a5d8d55b15ae225d12371659eef0e09123368119e918"
EXPECTED_PRIOR_SHA256 = "1729b6339eedd68ee94c5330a9e754176fb0e8cc3e249a708ec7a40c50bef726"
EXPECTED_ERRATUM_SHA256 = "2620ba192905412024037b33b11bbb91945d9b691b20e561e2636340cfb82ab1"
EXPECTED_HYPOTHESIS_SHA256 = "298e614a1d2aab86573cdbe4359d502ec521b2a8459f4da4df00a8d898face55"
EXPERIMENT_ID = "btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_COINMETRICS_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_HISTORICAL_MANIFEST_V1"

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_PRE_ECONOMIC_RUNNER": "research/btc_coinmetrics_exchange_net_inflow_sell_pressure_historical.py",
    "FROZEN_H1_PARSER_AND_DETERMINISTIC_STATISTICS": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_FAIL_CLOSED_COINMETRICS_SOURCE_PROBE": "research/coinmetrics_btc_exchange_net_flow_source_probe.cjs",
    "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-primary-prior.v1.json",
    "SEALED_PRE_OUTCOME_TRANSPORT_ERRATUM": "research_pipeline/examples/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-primary-prior.v1.transport-erratum.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-v1.hypothesis.json",
    "SEALED_COINMETRICS_SOURCE_BUNDLE": ".research-state/experiments/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1/inputs/coinmetrics-source-bundle.json",
    "SEALED_NORMALIZED_EXCHANGE_NET_FLOW_CORPUS": ".research-state/experiments/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1/inputs/coinmetrics-btc-exchange-net-flow-2018-2024.csv",
}

PREDICTIVE_GATE_NAMES = (
    "minimum_evaluable_episodes",
    "minimum_high_sell_pressure_episodes",
    "minimum_other_state_episodes",
    "both_states_chronological_quartile_breadth",
    "both_states_anchor_year_breadth",
    "maximum_state_anchor_month_share",
    "high_sell_pressure_median_terminal_return_at_least_25bp_lower",
    "high_sell_pressure_negative_terminal_rate_at_least_5pp_higher",
    "one_sided_fisher_negative_rate_p_value_at_most_0_10",
    "high_sell_pressure_median_path_drawdown_non_better",
    "high_sell_pressure_p75_path_drawdown_non_better",
    "annual_median_return_downside_direction_breadth",
    "top_positive_annual_downside_delta_contribution_at_most_60pct",
)
EXPECTED_GATES = (
    "btc_sha256_and_52608_rows_match",
    "exchange_flow_sha256_rows_daily_lattice_weekly_aggregation_and_day2_availability_match",
    "source_bundle_runner_prior_erratum_hypothesis_and_probe_sha256_match",
    *(f"design_{name}" for name in PREDICTIVE_GATE_NAMES),
    *(f"validation_{name}" for name in PREDICTIVE_GATE_NAMES),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyFlow:
    day: date
    flow_in: D
    flow_out: D
    net: D


@dataclass(frozen=True)
class FactorPoint:
    week_start: date
    week_end: date
    eligible_at: datetime
    weekly_flow_in: D
    weekly_flow_out: D

    @property
    def weekly_net(self) -> D:
        return self.weekly_flow_in - self.weekly_flow_out

    @property
    def high_sell_pressure(self) -> bool:
        return self.weekly_net > ZERO


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_flows(path: Path) -> list[DailyFlow]:
    if not path.is_file() or sha256(path) != EXPECTED_FLOW_SHA256:
        raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_SHA256")
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if not rows or rows[0] != ["date", "flow_in_ex_ntv", "flow_out_ex_ntv", "net_inflow_ex_ntv"]:
        raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_HEADER")
    parsed: list[DailyFlow] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 4:
            raise ResearchReject(f"DATA_REJECT:EXCHANGE_FLOW_ROW:{index}")
        try:
            item = DailyFlow(date.fromisoformat(row[0]), D(row[1]), D(row[2]), D(row[3]))
        except Exception as error:
            raise ResearchReject(f"DATA_REJECT:EXCHANGE_FLOW_PARSE:{index}") from error
        if item.flow_in <= ZERO or item.flow_out <= ZERO or item.net != item.flow_in - item.flow_out:
            raise ResearchReject(f"DATA_REJECT:EXCHANGE_FLOW_VALUE:{index}")
        parsed.append(item)
    if len(parsed) != EXPECTED_FLOW_ROWS:
        raise ResearchReject(f"DATA_REJECT:EXCHANGE_FLOW_ROWS:{len(parsed)}")
    if parsed[0].day != date(2018, 1, 1) or parsed[-1].day != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_BOUNDARY")
    if len({item.day for item in parsed}) != len(parsed):
        raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_DUPLICATE")
    for prior, current in zip(parsed, parsed[1:], strict=False):
        if current.day - prior.day != timedelta(days=1):
            raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_DAILY_LATTICE")
    return parsed


def build_factor_points(rows: list[DailyFlow]) -> list[FactorPoint]:
    points: list[FactorPoint] = []
    for week in range(EXPECTED_COMPLETE_WEEKS):
        segment = rows[week * 7 : week * 7 + 7]
        if len(segment) != 7 or segment[0].day.weekday() != 0 or segment[-1].day.weekday() != 6:
            raise ResearchReject(f"DATA_REJECT:EXCHANGE_FLOW_WEEK_ALIGNMENT:{week}")
        week_end = segment[-1].day + timedelta(days=1)
        points.append(
            FactorPoint(
                week_start=segment[0].day,
                week_end=week_end,
                eligible_at=datetime.combine(week_end + timedelta(days=2), datetime.min.time()),
                weekly_flow_in=sum((item.flow_in for item in segment), ZERO),
                weekly_flow_out=sum((item.flow_out for item in segment), ZERO),
            )
        )
    if len(rows) - EXPECTED_COMPLETE_WEEKS * 7 != 2:
        raise ResearchReject("DATA_REJECT:EXCHANGE_FLOW_TRAILING_DAYS")
    return points


def one_sided_fisher_greater(a: int, b: int, c: int, d: int) -> D | None:
    total = a + b + c + d
    high_total = a + b
    negative_total = a + c
    if total == 0 or high_total == 0 or negative_total == 0:
        return None
    lower = max(0, high_total - (total - negative_total))
    upper = min(high_total, negative_total)
    denominator = comb(total, high_total)
    probability = ZERO
    for value in range(max(a, lower), upper + 1):
        probability += D(
            comb(negative_total, value) * comb(total - negative_total, high_total - value)
        ) / D(denominator)
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
    missing_paths = 0
    for point in selected:
        anchor_at = point.eligible_at
        terminal_at = anchor_at + timedelta(hours=HORIZON_HOURS)
        if terminal_at > window[1]:
            missing_paths += 1
            continue
        anchor = bars_by_open.get(anchor_at)
        terminal = bars_by_open.get(terminal_at)
        path = [bars_by_open.get(anchor_at + timedelta(hours=offset)) for offset in range(HORIZON_HOURS)]
        if anchor is None or terminal is None or any(bar is None for bar in path):
            missing_paths += 1
            continue
        running_peak = anchor.open
        maximum_drawdown = ZERO
        for bar in path:
            assert bar is not None
            running_peak = max(running_peak, bar.high)
            maximum_drawdown = max(maximum_drawdown, (running_peak - bar.low) / running_peak)
        terminal_return = terminal.open / anchor.open - ONE
        episodes.append(
            {
                "week_start": point.week_start.isoformat(),
                "week_end": point.week_end.isoformat(),
                "eligible_at": anchor_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "state": "HIGH_SELL_PRESSURE" if point.high_sell_pressure else "OTHER",
                "weekly_flow_in_ex_ntv": shared.q(point.weekly_flow_in),
                "weekly_flow_out_ex_ntv": shared.q(point.weekly_flow_out),
                "weekly_net_inflow_ex_ntv": shared.q(point.weekly_net),
                "terminal_return_168h": shared.q(terminal_return),
                "peak_to_trough_max_drawdown_168h": shared.q(maximum_drawdown),
            }
        )

    high = [item for item in episodes if item["state"] == "HIGH_SELL_PRESSURE"]
    other = [item for item in episodes if item["state"] == "OTHER"]
    high_returns = [D(item["terminal_return_168h"]) for item in high]
    other_returns = [D(item["terminal_return_168h"]) for item in other]
    high_dd = [D(item["peak_to_trough_max_drawdown_168h"]) for item in high]
    other_dd = [D(item["peak_to_trough_max_drawdown_168h"]) for item in other]
    high_negative = sum(value < ZERO for value in high_returns)
    other_negative = sum(value < ZERO for value in other_returns)
    high_negative_rate = _rate(high_negative, len(high))
    other_negative_rate = _rate(other_negative, len(other))
    fisher_p = one_sided_fisher_greater(high_negative, len(high) - high_negative, other_negative, len(other) - other_negative)
    high_median_return = shared.percentile(high_returns, D("0.5"))
    other_median_return = shared.percentile(other_returns, D("0.5"))
    high_median_dd = shared.percentile(high_dd, D("0.5"))
    other_median_dd = shared.percentile(other_dd, D("0.5"))
    high_p75_dd = shared.percentile(high_dd, D("0.75"))
    other_p75_dd = shared.percentile(other_dd, D("0.75"))

    states = ("HIGH_SELL_PRESSURE", "OTHER")
    quartile_counts = {state: [0, 0, 0, 0] for state in states}
    month_counts = {state: {} for state in states}
    year_counts = {state: set() for state in states}
    for index, item in enumerate(episodes):
        state = item["state"]
        quartile_counts[state][min(3, index * 4 // len(episodes))] += 1
        month = item["eligible_at"][:7]
        month_counts[state][month] = month_counts[state].get(month, 0) + 1
        year_counts[state].add(item["eligible_at"][:4])
    maximum_month_share = max(
        (
            D(max(month_counts[state].values())) / D(sum(month_counts[state].values())) * HUNDRED
            for state in states
            if month_counts[state]
        ),
        default=HUNDRED,
    )
    annual_downside_deltas: dict[str, D] = {}
    for year in sorted({item["eligible_at"][:4] for item in episodes}):
        annual_high = [D(item["terminal_return_168h"]) for item in high if item["eligible_at"].startswith(year)]
        annual_other = [D(item["terminal_return_168h"]) for item in other if item["eligible_at"].startswith(year)]
        high_median = shared.percentile(annual_high, D("0.5"))
        other_median = shared.percentile(annual_other, D("0.5"))
        if high_median is not None and other_median is not None:
            annual_downside_deltas[year] = other_median - high_median
    annual_wins = sum(value > ZERO for value in annual_downside_deltas.values())
    positive_annual = [value for value in annual_downside_deltas.values() if value > ZERO]
    top_positive_annual_share = HUNDRED if not positive_annual else max(positive_annual) / sum(positive_annual, ZERO) * HUNDRED

    minimum_total = 180 if label == "design" else 90
    minimum_each = 60 if label == "design" else 30
    minimum_quartiles = 4 if label == "design" else 3
    minimum_years = 3 if label == "design" else 2
    minimum_annual_wins = 3 if label == "design" else 2
    gates = {
        "minimum_evaluable_episodes": len(episodes) >= minimum_total,
        "minimum_high_sell_pressure_episodes": len(high) >= minimum_each,
        "minimum_other_state_episodes": len(other) >= minimum_each,
        "both_states_chronological_quartile_breadth": all(sum(count > 0 for count in counts) >= minimum_quartiles for counts in quartile_counts.values()),
        "both_states_anchor_year_breadth": all(len(years) >= minimum_years for years in year_counts.values()),
        "maximum_state_anchor_month_share": maximum_month_share <= D("15"),
        "high_sell_pressure_median_terminal_return_at_least_25bp_lower": high_median_return is not None and other_median_return is not None and high_median_return <= other_median_return - D("0.0025"),
        "high_sell_pressure_negative_terminal_rate_at_least_5pp_higher": high_negative_rate is not None and other_negative_rate is not None and high_negative_rate >= other_negative_rate + D("0.05"),
        "one_sided_fisher_negative_rate_p_value_at_most_0_10": fisher_p is not None and fisher_p <= D("0.10"),
        "high_sell_pressure_median_path_drawdown_non_better": high_median_dd is not None and other_median_dd is not None and high_median_dd >= other_median_dd,
        "high_sell_pressure_p75_path_drawdown_non_better": high_p75_dd is not None and other_p75_dd is not None and high_p75_dd >= other_p75_dd,
        "annual_median_return_downside_direction_breadth": annual_wins >= minimum_annual_wins,
        "top_positive_annual_downside_delta_contribution_at_most_60pct": top_positive_annual_share <= D("60"),
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
            "minimum_median_terminal_return_downside_delta": "0.00250000",
            "minimum_negative_terminal_rate_delta": "0.05000000",
            "maximum_fisher_p_value": "0.10000000",
            "minimum_annual_median_return_downside_direction_wins": minimum_annual_wins,
            "maximum_top_positive_annual_downside_delta_contribution_pct": "60.00000000",
        },
        "statistics": {
            "episode_count": len(episodes),
            "high_sell_pressure_count": len(high),
            "other_state_count": len(other),
            "high_sell_pressure_quartile_counts": quartile_counts["HIGH_SELL_PRESSURE"],
            "other_state_quartile_counts": quartile_counts["OTHER"],
            "high_sell_pressure_anchor_years": sorted(year_counts["HIGH_SELL_PRESSURE"]),
            "other_state_anchor_years": sorted(year_counts["OTHER"]),
            "maximum_state_anchor_month_share_pct": shared.q(maximum_month_share),
            "high_sell_pressure_median_terminal_return_168h": shared.nullable(high_median_return),
            "other_state_median_terminal_return_168h": shared.nullable(other_median_return),
            "high_sell_pressure_negative_terminal_rate": shared.nullable(high_negative_rate),
            "other_state_negative_terminal_rate": shared.nullable(other_negative_rate),
            "one_sided_fisher_negative_rate_p_value": shared.nullable(fisher_p),
            "high_sell_pressure_median_path_drawdown_168h": shared.nullable(high_median_dd),
            "other_state_median_path_drawdown_168h": shared.nullable(other_median_dd),
            "high_sell_pressure_p75_path_drawdown_168h": shared.nullable(high_p75_dd),
            "other_state_p75_path_drawdown_168h": shared.nullable(other_p75_dd),
            "annual_median_return_downside_deltas": {year: shared.q(value) for year, value in annual_downside_deltas.items()},
            "annual_median_return_downside_direction_wins": annual_wins,
            "top_positive_annual_downside_delta_contribution_pct": shared.q(top_positive_annual_share),
        },
        "gates": gates,
    }


def expected_policy() -> dict[str, Any]:
    return {
        "factor_identity": "COINMETRICS_BTC_COMPLETE_UTC_WEEK_FLOWINEXNTV_MINUS_FLOWOUTEXNTV_STRICTLY_POSITIVE_SELL_PRESSURE_V1",
        "high_sell_pressure_condition": "SEVEN_DAY_FLOWINEXNTV_SUM_MINUS_FLOWOUTEXNTV_SUM_STRICTLY_GREATER_THAN_ZERO",
        "other_condition": "OTHERWISE",
        "week": "MONDAY_00_00_UTC_THROUGH_NEXT_MONDAY_00_00_UTC_EXCLUSIVE",
        "availability": "WEEK_END_MONDAY_PLUS_2_CALENDAR_DAYS_AT_00_00_UTC",
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
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "exchange_flows": {"path": ".research-state/experiments/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1/inputs/coinmetrics-btc-exchange-net-flow-2018-2024.csv", "sha256": EXPECTED_FLOW_SHA256, "rows": EXPECTED_FLOW_ROWS, "first_date": "2018-01-01", "last_date": "2024-12-31", "present_vintage_status": "FLASH"},
        "source_bundle": {"path": ".research-state/experiments/btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-historical-v1/inputs/coinmetrics-source-bundle.json", "sha256": EXPECTED_SOURCE_BUNDLE_SHA256},
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
    if manifest.get("gate_set") != {"required": list(EXPECTED_GATES), "pass": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE", "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING"}:
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
        "exchange_flow_sha256_rows_daily_lattice_weekly_aggregation_and_day2_availability_match": True,
        "source_bundle_runner_prior_erratum_hypothesis_and_probe_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_GATES:
        raise ResearchReject("MANIFEST_REJECT:GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def build_output(btc_input: Path, flow_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    if sha256(btc_input) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    bars = shared.cftc_reused.base.parse_rows(btc_input.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or shared.cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    if bars[-1].close_time > VALIDATION[1]:
        raise ResearchReject("OOS_REJECT:BTC_CUTOFF")
    flow_rows = load_flows(flow_input)
    points = build_factor_points(flow_rows)
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(bars, points, VALIDATION, label="validation"),
    }
    gates, failed = evaluate_gates(predictive)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_COINMETRICS_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_FAMILY_PRE_ECONOMIC",
        "decision": "FREEZE_SEPARATE_MATCHED_CAPITAL_ECONOMIC_EXPERIMENT_WITHOUT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_FAMILY_WITHOUT_ECONOMIC_ACCESS_OR_TUNING",
        "candidate_created": False,
        "economic_evidence_accessed": False,
        "oos_opened": False,
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_input), "hourly_rows": len(bars)},
            "exchange_flows": {"path": flow_input.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(flow_input), "daily_rows": len(flow_rows)},
            "source_bundle_sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "complete_week_points": len(points),
            "high_sell_pressure_points": sum(point.high_sell_pressure for point in points),
            "other_state_points": sum(not point.high_sell_pressure for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": gates,
        "failed_pre_economic_gates": failed,
        "economic_evidence": {metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_SCREEN" for metric in ("fees", "adverse_slippage", "realized_pnl", "unrealized_pnl", "total_pnl", "maximum_drawdown", "holding_age", "terminal_inventory", "breadth_and_path_risk")},
        "claim_boundary": "Historical present-vintage Coin Metrics exchange labels and pre-2025 BTC evidence only. The screen cannot establish point-in-time alpha, create a strategy candidate or authorize activation.",
        "scope_note": "No strategy economics, paid API, second timer, second writer, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--flow-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.flow_input.resolve(), args.manifest.resolve()]
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
