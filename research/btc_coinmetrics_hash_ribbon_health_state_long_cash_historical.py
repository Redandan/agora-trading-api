#!/usr/bin/env python3
"""Deterministic source, deduplication and economic audit of one BTC hash ribbon state."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-coinmetrics-hash-ribbon-health-state-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_HASHRATE_SHA256 = "1dfdfde2f8806f912c6dc3c3d48e2bf244c40af94e9aeb4389ed2cc8337ec273"
EXPECTED_RAW_SHA256 = "599a2ca96d1179875f102d2e3431267ea6c89ce1f83dddd1c463b59eeda6be58"
EXPECTED_BUNDLE_SHA256 = "177675c165dbb70c76ca4fb4c967dfdb80529a0bcdaa037071b6cce4f1e81e13"
EXPECTED_HASHRATE_ROWS = 2_557
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-hash-ribbon-health-state-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-hash-ribbon-health-state-long-cash-v1.hypothesis.json"
HASHRATE_SOURCE = REPO_ROOT / ".research-state/experiments/dra-bitcoin-hashrate-growth-entry-admission-historical-v1/inputs/coinmetrics-btc-hashrate-2018-2024.csv"
RAW_SOURCE = REPO_ROOT / ".research-state/experiments/dra-bitcoin-hashrate-growth-entry-admission-historical-v1/inputs/coinmetrics-btc-hashrate-2018-2024-raw.json"
BUNDLE_SOURCE = REPO_ROOT / ".research-state/experiments/dra-bitcoin-hashrate-growth-entry-admission-historical-v1/inputs/coinmetrics-source-bundle.json"
EXPECTED_BINDINGS = {
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "primary_prior": (PRIOR_SOURCE, "82972a8425f1f12bab182296002b1a5937c9c70f775ddbb8b5bbb2ba43c4830a"),
    "hypothesis": (HYPOTHESIS_SOURCE, "cf23073123ebec45fe3e66d5d2c5ebd4fb7ffc17f5205d204074ec7ef86b0869"),
    "normalized_hashrate": (HASHRATE_SOURCE, EXPECTED_HASHRATE_SHA256),
    "raw_hashrate": (RAW_SOURCE, EXPECTED_RAW_SHA256),
    "source_bundle": (BUNDLE_SOURCE, EXPECTED_BUNDLE_SHA256),
}
DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
PRE_ECONOMIC_GATE_NAMES = (
    "btc_sha256_and_52608_rows_match",
    "hashrate_normalized_raw_and_bundle_sha256_match",
    "hashrate_2557_positive_contiguous_daily_rows_2018_2024_pass",
    "frozen_runner_kernel_ledger_reference_parser_prior_and_hypothesis_sha256_match",
    "single_variant_30d_above_60d_plus3d_no_price_confirmation_contract_pass",
    "design_evaluations_at_least_1000",
    "design_each_state_at_least_60_days",
    "design_state_transitions_at_least_4",
    "design_both_states_in_at_least_2_years",
    "validation_evaluations_at_least_650",
    "validation_each_state_at_least_30_days",
    "validation_state_transitions_at_least_2",
    "validation_both_states_in_both_years",
    "design_closed_28d_comparisons_at_least_150",
    "validation_closed_28d_comparisons_at_least_90",
    "design_state_disagreement_with_closed_28d_family_at_least_15pct",
    "validation_state_disagreement_with_closed_28d_family_at_least_10pct",
    "design_abs_phi_to_lagged_btc_60d_price_trend_at_most_0_80",
    "validation_abs_phi_to_lagged_btc_60d_price_trend_at_most_0_80",
)
ECONOMIC_GATE_NAMES = (
    "design_normal_total_return_positive",
    "design_stress_total_return_positive",
    "design_drawdown_at_most_90pct_of_buy_hold",
    "design_upside_capture_at_least_70pct",
    "design_calmar_at_least_buy_hold",
    "validation_normal_total_return_positive",
    "validation_stress_total_return_positive",
    "validation_drawdown_at_most_90pct_of_buy_hold",
    "validation_upside_capture_at_least_70pct",
    "validation_calmar_at_least_buy_hold",
    "validation_position_changes_between_2_and_100",
    "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_return_at_least_4_of_5",
    "stress_positive_annual_return_at_least_4_of_5",
    "annual_drawdown_non_worse_at_least_4_of_5",
    "annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5",
    "annual_upside_capture_at_least_60pct_at_least_4_of_5",
    "top_year_positive_contribution_at_most_60pct",
    "validation_top_positive_episode_contribution_at_most_60pct",
    "validation_p90_hold_at_most_8760_hours",
    "validation_terminal_holding_age_at_most_8760_hours",
    "validation_terminal_liquidation_adjusted_return_positive",
    "validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, source: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, source)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{source}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def load_hashrate(path: Path) -> list[tuple[date, D]]:
    rows: list[tuple[date, D]] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["date", "hash_rate_th_per_s"]:
            raise ResearchReject("DATA_REJECT:HASHRATE_COLUMNS")
        for item in reader:
            observed = datetime.strptime(item["date"], "%Y-%m-%d").date()
            value = D(item["hash_rate_th_per_s"])
            if not value.is_finite() or value <= ZERO:
                raise ResearchReject(f"DATA_REJECT:HASHRATE_VALUE:{observed.isoformat()}")
            rows.append((observed, value))
    if len(rows) != EXPECTED_HASHRATE_ROWS:
        raise ResearchReject(f"DATA_REJECT:HASHRATE_ROWS:{len(rows)}")
    if rows[0][0] != date(2018, 1, 1) or rows[-1][0] != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:HASHRATE_BOUNDARIES")
    for prior, current in zip(rows, rows[1:], strict=False):
        if current[0] - prior[0] != timedelta(days=1):
            raise ResearchReject(f"DATA_REJECT:HASHRATE_GAP:{prior[0]}:{current[0]}")
    return rows


def build_hash_ribbon_targets(
    rows: list[tuple[date, D]],
) -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    for index in range(59, len(rows)):
        fast = sum((value for _, value in rows[index - 29 : index + 1]), ZERO) / D("30")
        slow = sum((value for _, value in rows[index - 59 : index + 1]), ZERO) / D("60")
        effective = datetime.combine(rows[index][0] + timedelta(days=3), time())
        if effective >= datetime(2025, 1, 1):
            continue
        targets[effective] = fast > slow
    if not targets:
        raise ResearchReject("FEATURE_REJECT:NO_HASH_RIBBON_TARGETS")
    states = list(targets.values())
    return targets, {
        "factor_identity": "COIN_METRICS_HASHRATE_30D_SMA_STRICTLY_ABOVE_60D_SMA_PLUS3D_V1",
        "evaluation_count": len(targets),
        "health_count": sum(states),
        "stress_count": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def build_closed_28d_weekly_targets(rows: list[tuple[date, D]]) -> dict[datetime, bool]:
    targets: dict[datetime, bool] = {}
    for index in range(55, len(rows)):
        observed = rows[index][0]
        if observed.weekday() != 6:
            continue
        current = sum((value for _, value in rows[index - 27 : index + 1]), ZERO) / D("28")
        prior = sum((value for _, value in rows[index - 55 : index - 27]), ZERO) / D("28")
        effective = datetime.combine(observed + timedelta(days=3), time())
        if effective < datetime(2025, 1, 1):
            targets[effective] = current > prior
    return targets


def feature_window_summary(
    targets: dict[datetime, bool], window: tuple[datetime, datetime]
) -> dict[str, Any]:
    start, end = window
    selected = [(clock, state) for clock, state in targets.items() if start <= clock < end]
    states = [state for _, state in selected]
    years_with_health = {clock.year for clock, state in selected if state}
    years_with_stress = {clock.year for clock, state in selected if not state}
    return {
        "evaluations": len(states),
        "health_days": sum(states),
        "stress_days": sum(not state for state in states),
        "transitions": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "years_with_health": len(years_with_health),
        "years_with_stress": len(years_with_stress),
    }


def binary_phi(left: list[bool], right: list[bool]) -> D:
    if len(left) != len(right) or not left:
        raise ResearchReject("METRIC_REJECT:PHI_SUPPORT")
    both_true = sum(a and b for a, b in zip(left, right, strict=True))
    left_only = sum(a and not b for a, b in zip(left, right, strict=True))
    right_only = sum(not a and b for a, b in zip(left, right, strict=True))
    both_false = len(left) - both_true - left_only - right_only
    denominator_squared = D((both_true + left_only) * (right_only + both_false) * (both_true + right_only) * (left_only + both_false))
    if denominator_squared == ZERO:
        raise ResearchReject("METRIC_REJECT:PHI_DEGENERATE")
    return D(both_true * both_false - left_only * right_only) / denominator_squared.sqrt()


def deduplication_summary(
    ribbon: dict[datetime, bool],
    closed: dict[datetime, bool],
    bars: list[Any],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    comparison_clocks = [clock for clock in closed if start <= clock < end and clock in ribbon]
    disagreements = sum(ribbon[clock] != closed[clock] for clock in comparison_clocks)
    disagreement = D(disagreements) / D(len(comparison_clocks)) if comparison_clocks else ZERO
    boundary_close = {bar.close_time: bar.close for bar in bars if bar.close_time.hour == 0}
    trend_clocks = [
        clock for clock in ribbon
        if start <= clock < end and clock in boundary_close and clock - timedelta(days=60) in boundary_close
    ]
    ribbon_states = [ribbon[clock] for clock in trend_clocks]
    trend_states = [boundary_close[clock] > boundary_close[clock - timedelta(days=60)] for clock in trend_clocks]
    phi = binary_phi(ribbon_states, trend_states)
    return {
        "closed_28d_comparisons": len(comparison_clocks),
        "closed_28d_state_disagreements": disagreements,
        "closed_28d_state_disagreement_pct": q(disagreement * HUNDRED),
        "lagged_btc_60d_trend_comparisons": len(trend_clocks),
        "hash_ribbon_to_lagged_btc_60d_trend_phi": q(phi),
    }, {"disagreement": disagreement, "phi": phi, "closed_comparisons": D(len(comparison_clocks))}


def evaluate_pre_economic_gates(
    design_support: dict[str, Any],
    validation_support: dict[str, Any],
    design_dedup: dict[str, D],
    validation_dedup: dict[str, D],
) -> tuple[dict[str, bool], list[str]]:
    gates = {
        "btc_sha256_and_52608_rows_match": True,
        "hashrate_normalized_raw_and_bundle_sha256_match": True,
        "hashrate_2557_positive_contiguous_daily_rows_2018_2024_pass": True,
        "frozen_runner_kernel_ledger_reference_parser_prior_and_hypothesis_sha256_match": True,
        "single_variant_30d_above_60d_plus3d_no_price_confirmation_contract_pass": True,
        "design_evaluations_at_least_1000": design_support["evaluations"] >= 1000,
        "design_each_state_at_least_60_days": min(design_support["health_days"], design_support["stress_days"]) >= 60,
        "design_state_transitions_at_least_4": design_support["transitions"] >= 4,
        "design_both_states_in_at_least_2_years": min(design_support["years_with_health"], design_support["years_with_stress"]) >= 2,
        "validation_evaluations_at_least_650": validation_support["evaluations"] >= 650,
        "validation_each_state_at_least_30_days": min(validation_support["health_days"], validation_support["stress_days"]) >= 30,
        "validation_state_transitions_at_least_2": validation_support["transitions"] >= 2,
        "validation_both_states_in_both_years": min(validation_support["years_with_health"], validation_support["years_with_stress"]) == 2,
        "design_closed_28d_comparisons_at_least_150": design_dedup["closed_comparisons"] >= D("150"),
        "validation_closed_28d_comparisons_at_least_90": validation_dedup["closed_comparisons"] >= D("90"),
        "design_state_disagreement_with_closed_28d_family_at_least_15pct": design_dedup["disagreement"] >= D("0.15"),
        "validation_state_disagreement_with_closed_28d_family_at_least_10pct": validation_dedup["disagreement"] >= D("0.10"),
        "design_abs_phi_to_lagged_btc_60d_price_trend_at_most_0_80": abs(design_dedup["phi"]) <= D("0.80"),
        "validation_abs_phi_to_lagged_btc_60d_price_trend_at_most_0_80": abs(validation_dedup["phi"]) <= D("0.80"),
    }
    if tuple(gates) != PRE_ECONOMIC_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed]


def evaluate_economic_gates(
    design_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation_output: dict[str, Any],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    validation_candidate = validation_output["scenarios"]["NORMAL"]["candidate"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.60") for value in annual_raw.values())
    positives = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positives, ZERO)
    top_year = max(positives, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates = {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_70pct": dn["upside_capture"] >= D("0.70"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_70pct": vn["upside_capture"] >= D("0.70"),
        "validation_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "validation_position_changes_between_2_and_100": D("2") <= vn["position_changes"] <= D("100"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_5": normal_positive >= 4,
        "stress_positive_annual_return_at_least_4_of_5": stress_positive >= 4,
        "annual_drawdown_non_worse_at_least_4_of_5": drawdown_nonworse >= 4,
        "annual_calmar_at_least_75pct_buy_hold_at_least_3_of_5": calmar_breadth >= 3,
        "annual_upside_capture_at_least_60pct_at_least_4_of_5": upside_breadth >= 4,
        "top_year_positive_contribution_at_most_60pct": top_year <= D("60"),
        "validation_top_positive_episode_contribution_at_most_60pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        "validation_p90_hold_at_most_8760_hours": vn["p90_hold"] <= D("8760"),
        "validation_terminal_holding_age_at_most_8760_hours": vn["terminal_holding_age"] <= D("8760"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != ECONOMIC_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed], {
        "normal_positive_years": f"{normal_positive}_of_5",
        "stress_positive_years": f"{stress_positive}_of_5",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_5",
        "normal_upside_capture_at_least_60pct_years": f"{upside_breadth}_of_5",
        "top_year_positive_total_return_contribution_pct": q(top_year),
        "validation_top_positive_episode_contribution_pct": validation_candidate["top_positive_episode_contribution_pct"],
    }


def validate_manifest(manifest: dict[str, Any], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS")
    if manifest.get("datasets") != {
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "hashrate": {"path": HASHRATE_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_HASHRATE_SHA256, "rows": EXPECTED_HASHRATE_ROWS, "first_date": "2018-01-01", "last_date": "2024-12-31", "present_vintage": True},
        "raw_hashrate": {"path": RAW_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_RAW_SHA256},
        "source_bundle": {"path": BUNDLE_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_BUNDLE_SHA256},
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_LONG_CASH_V1",
        "source_metric": "COIN_METRICS_BTC_HASHRATE_DAILY_REVIEWED_PRESENT_VINTAGE",
        "fast_average_complete_days": 30,
        "slow_average_complete_days": 60,
        "long_condition": "FAST_30D_SIMPLE_MEAN_STRICTLY_GREATER_THAN_SLOW_60D_SIMPLE_MEAN",
        "cash_condition": "FAST_30D_SIMPLE_MEAN_LESS_THAN_OR_EQUAL_TO_SLOW_60D_SIMPLE_MEAN",
        "availability": "OBSERVATION_DATE_PLUS_3_CALENDAR_DAYS_AT_00_00_UTC",
        "execution": "SAME_TIMESTAMP_BTC_H1_OPEN_ONLY_WHEN_TARGET_CHANGES",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "price_confirmation": "DENY",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
        "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "id": "BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_MATCHED_CAPITAL_GATES_V1",
        "pre_economic_required": list(PRE_ECONOMIC_GATE_NAMES),
        "economic_required": list(ECONOMIC_GATE_NAMES),
        "decision": "ALL_PRE_ECONOMIC_AND_ECONOMIC_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    runner = manifest.get("runner_binding", {})
    if runner.get("path") != runner_path.relative_to(REPO_ROOT).as_posix() or runner.get("sha256") != sha256(runner_path):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    actual = {item.get("key"): (item.get("path"), item.get("sha256")) for item in bindings}
    expected = {key: (path.relative_to(REPO_ROOT).as_posix(), digest) for key, (path, digest) in EXPECTED_BINDINGS.items()}
    if actual != expected:
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")


def build_output(btc_path: Path, manifest_path: Path) -> dict[str, Any]:
    if sha256(btc_path) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    for label, (path, expected) in EXPECTED_BINDINGS.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")
    bundle = json.loads(BUNDLE_SOURCE.read_text(encoding="utf-8"))
    if bundle.get("normalized_subset", {}).get("sha256") != EXPECTED_HASHRATE_SHA256 or bundle.get("raw_response", {}).get("sha256") != EXPECTED_RAW_SHA256:
        raise ResearchReject("SOURCE_REJECT:BUNDLE_INTERNAL_HASHES")
    runner_path = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner_path)
    parser = load_module("hash_ribbon_h1_parser", PARSER_SOURCE)
    bars = parser.parse_rows(btc_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    rows = load_hashrate(HASHRATE_SOURCE)
    ribbon, feature = build_hash_ribbon_targets(rows)
    closed = build_closed_28d_weekly_targets(rows)
    design_support = feature_window_summary(ribbon, DESIGN)
    validation_support = feature_window_summary(ribbon, VALIDATION)
    design_dedup_output, design_dedup_raw = deduplication_summary(ribbon, closed, bars, DESIGN)
    validation_dedup_output, validation_dedup_raw = deduplication_summary(ribbon, closed, bars, VALIDATION)
    pre_gates, failed_pre = evaluate_pre_economic_gates(
        design_support, validation_support, design_dedup_raw, validation_dedup_raw
    )
    base = {
        "schema_version": "1",
        "document_type": "BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "datasets": {
            "btc": {"path": btc_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_BTC_SHA256, "rows": len(bars)},
            "hashrate": {"path": HASHRATE_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_HASHRATE_SHA256, "rows": len(rows)},
            "raw_hashrate": {"path": RAW_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_RAW_SHA256},
            "source_bundle": {"path": BUNDLE_SOURCE.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_BUNDLE_SHA256},
        },
        "feature": feature,
        "window_support": {"design": design_support, "validation": validation_support},
        "deduplication": {"design": design_dedup_output, "validation": validation_dedup_output},
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "candidate_created": False,
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, post-2024 outcome, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        return {
            **base,
            "status": "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_FAMILY_PRE_ECONOMIC",
            "decision": "PERMANENTLY_CLOSE_EXACT_30D_60D_PLUS3D_HASH_RIBBON_HEALTH_STATE_FAMILY_AT_FROZEN_PRE_ECONOMIC_GATES_WITHOUT_RERUN_OR_RESCUE",
            "economics_opened": False,
            "failed_economic_gates": [],
            "claim_boundary": "Source, support and deduplication evidence only. BTC strategy economics were not opened because at least one frozen pre-economic gate failed.",
        }
    kernel = load_module("hash_ribbon_economic_kernel", KERNEL_SOURCE)
    ledger = load_module("hash_ribbon_long_cash_ledger", LEDGER_SOURCE)
    reference = load_module("hash_ribbon_path_reference", REFERENCE_SOURCE)
    design_output, design_raw = kernel.simulate_window(ledger, reference, bars, ribbon, feature, DESIGN)
    validation_output, validation_raw = kernel.simulate_window(ledger, reference, bars, ribbon, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, ribbon, feature, window) for year, window in ANNUAL.items()}
    economic_gates, failed_economic, breadth = evaluate_economic_gates(
        design_output, design_raw, validation_output, validation_raw, annual
    )
    passed = not failed_economic
    return {
        **base,
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_30D_60D_PLUS3D_HASH_RIBBON_HEALTH_STATE_LONG_CASH_FAMILY_WITHOUT_PERIOD_LAG_PRICE_CONFIRMATION_WEIGHT_DIRECTION_OR_GATE_TUNING",
        "economics_opened": True,
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "economic_gates": economic_gates,
        "failed_economic_gates": failed_economic,
        "candidate_created": passed,
        "claim_boundary": "Historical present-vintage single-source evidence only. A pass remains REPORTED_NOT_ACTIVATED, requires one independent sealed OOS and never authorizes Trading.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    btc_path = args.btc_input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (btc_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists():
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_output(btc_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_pre_economic_gates": result["failed_pre_economic_gates"],
        "failed_economic_gates": result["failed_economic_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
