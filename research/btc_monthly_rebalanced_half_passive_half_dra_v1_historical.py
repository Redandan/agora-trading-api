#!/usr/bin/env python3
"""Deterministic monthly 50/50 passive BTC and frozen DRA V1 risk-budget screen."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
import hashlib
import json
from pathlib import Path
import sys
from typing import Any

try:
    from research import btc_static_half_passive_half_dra_v1_historical as static
except ModuleNotFoundError:  # Direct script launch adds research/ instead of repo root.
    import btc_static_half_passive_half_dra_v1_historical as static


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HALF = D("0.5")
HUNDRED = D("100")
Q8 = D("0.00000001")
TWO_PP = D("2")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-monthly-rebalanced-half-passive-half-dra-v1-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_MONTHLY_REBALANCED_HALF_PASSIVE_HALF_DRA_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
NORMAL_REBALANCE_FRICTION = D("0.0015")
STRESS_REBALANCE_FRICTION = D("0.0030")
EXPECTED_SOURCE_PATHS = {
    "DETERMINISTIC_MONTHLY_REBALANCE_RUNNER": "research/btc_monthly_rebalanced_half_passive_half_dra_v1_historical.py",
    "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-monthly-rebalanced-half-passive-half-dra-v1-primary-prior.v1.json",
    "FROZEN_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-monthly-rebalanced-half-passive-half-dra-v1.hypothesis.json",
    "SEALED_STATIC_PARENT_DECISION": "research_pipeline/examples/btc-static-half-passive-half-dra-v1-historical.v1.decision.json",
    "SEALED_STATIC_PARENT_RUNNER": "research/btc_static_half_passive_half_dra_v1_historical.py",
    "FROZEN_DRA_V1_ENGINE_AND_CHECKPOINTS": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "FROZEN_PASSIVE_BTC_BENCHMARK_VALUATION_REFERENCE": "src/main/java/com/agora/research/BtcDonchianStandaloneHistoricalCli.java",
}
EXPECTED_GATE_NAMES = (
    "dataset_sha256_and_52608_rows_match",
    "hourly_lattice_and_ohlcv_invariants_pass",
    "frozen_dra_source_passive_reference_static_parent_and_hypothesis_hashes_match",
    "dra_design_and_validation_checkpoints_exact",
    "normal_design_candidate_total_return_gt_primary",
    "normal_validation_candidate_total_return_gt_primary",
    "stress_design_candidate_total_return_gt_primary",
    "stress_validation_candidate_total_return_gt_primary",
    "normal_design_candidate_total_return_gt_dra",
    "normal_validation_candidate_total_return_gt_dra",
    "normal_design_drawdown_at_most_primary_plus_2pp",
    "normal_validation_drawdown_at_most_primary_plus_2pp",
    "normal_design_calmar_at_least_primary",
    "normal_validation_calmar_at_least_primary",
    "normal_design_drawdown_at_most_75pct_passive",
    "normal_validation_drawdown_at_most_75pct_passive",
    "normal_validation_upside_capture_at_least_45pct",
    "stress_design_total_return_positive",
    "stress_validation_total_return_positive",
    "stress_validation_drawdown_within_normal_plus_2pp",
    "normal_design_max_pre_rebalance_component_weight_at_most_70pct",
    "normal_validation_max_pre_rebalance_component_weight_at_most_70pct",
    "normal_validation_rebalance_cost_at_most_20pct_positive_incremental_return",
    "candidate_positive_annual_total_return_at_least_4_of_5",
    "candidate_total_return_gt_primary_at_least_4_of_5",
    "candidate_annual_drawdown_at_most_primary_plus_2pp_at_least_4_of_5",
    "candidate_annual_drawdown_at_most_passive_at_least_4_of_5",
    "top_year_positive_incremental_return_contribution_at_most_60pct",
    "validation_terminal_dra_unrealized_share_of_dra_total_return_at_most_40pct",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def quantized(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


def ratio(numerator: D, denominator: D) -> D | None:
    return None if denominator == ZERO else numerator / denominator


def nullable(value: D | None) -> str | None:
    return None if value is None else quantized(value)


@dataclass
class RebalancedComponents:
    first_value: D
    second_value: D
    first_is_tradable: bool = True
    second_is_tradable: bool = True
    rebalance_count: int = 0
    turnover: D = ZERO
    cost: D = ZERO
    maximum_pre_rebalance_weight: D = HALF

    @property
    def equity(self) -> D:
        return self.first_value + self.second_value

    def observe_weight(self) -> None:
        total = self.equity
        if total <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NONPOSITIVE_COMPONENT_EQUITY")
        self.maximum_pre_rebalance_weight = max(
            self.maximum_pre_rebalance_weight,
            self.first_value / total,
            self.second_value / total,
        )

    def rebalance(self, friction: D) -> None:
        total = self.equity
        if total <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NONPOSITIVE_COMPONENT_EQUITY")
        self.observe_weight()
        target = total * HALF
        traded = ZERO
        if self.first_is_tradable:
            traded += abs(self.first_value - target)
        if self.second_is_tradable:
            traded += abs(self.second_value - target)
        cost = traded * friction
        remaining = total - cost
        if remaining <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:REBALANCE_COST")
        self.first_value = remaining * HALF
        self.second_value = remaining * HALF
        self.turnover += traded
        self.cost += cost
        self.rebalance_count += 1

    def apply_returns(self, first_return: D, second_return: D) -> None:
        if first_return <= ZERO or second_return <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NONPOSITIVE_COMPONENT_RETURN")
        self.first_value *= first_return
        self.second_value *= second_return


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("portfolio_policy", {})
    expected = {
        "policy_id": "BTC_MONTHLY_REBALANCED_50_PASSIVE_50_DRA_V1",
        "target_weights": ["0.50", "0.50"],
        "rebalance_clock": "FIRST_H1_OPEN_OF_EACH_UTC_CALENDAR_MONTH",
        "normal_rebalance_friction_per_traded_component_notional": "0.0015",
        "stress_rebalance_friction_per_traded_component_notional": "0.0030",
        "component_transfer": "SYNTHETIC_INVESTABLE_COMPONENT_INDEX_UNITS",
        "leverage": "DENY",
        "short": "DENY",
        "variants": 1,
    }
    if policy != expected:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    dataset = manifest.get("dataset", {})
    if dataset != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": EXPECTED_DATA_SHA256,
        "rows": EXPECTED_DATA_ROWS,
        "first_open_time": "2019-01-01T00:00:00",
        "last_close_time": "2025-01-01T00:00:00",
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    if manifest.get("windows") != {
        "design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "id": "BTC_MONTHLY_REBALANCED_HALF_PASSIVE_HALF_DRA_ECONOMIC_GATES_V1",
        "required": list(EXPECTED_GATE_NAMES),
        "decision": "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings", [])
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    actual_roles = {binding.get("role") for binding in bindings}
    if actual_roles != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        if binding.get("path") != EXPECTED_SOURCE_PATHS[binding["role"]]:
            raise ResearchReject(f"BINDING_REJECT:{binding['role']}:PATH")
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or sha256(bound) != binding["sha256"]:
            raise ResearchReject(f"BINDING_REJECT:{binding['role']}")


def _path_output(
    path: static.PathMetrics,
    final_equity: D,
    components: RebalancedComponents,
) -> dict[str, Any]:
    output = path.output(final_equity)
    output.update(
        {
            "rebalance_count": components.rebalance_count,
            "rebalance_turnover_pct_initial_equity": quantized(components.turnover * HUNDRED),
            "rebalance_cost_pct_initial_equity": quantized(components.cost * HUNDRED),
            "maximum_pre_rebalance_component_weight_pct": quantized(
                components.maximum_pre_rebalance_weight * HUNDRED
            ),
        }
    )
    return output


def simulate_window(
    module: Any,
    bars: list[Any],
    window: tuple[datetime, datetime],
    friction: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    engine = module.Engine("v1", cap=module.REFERENCE_CAP)
    passive_quantity, passive_cash = static.passive_position(module, trading[0].open)
    candidate_path = static.PathMetrics()
    primary_path = static.PathMetrics()
    dra_path = static.PathMetrics()
    passive_path = static.PathMetrics()
    candidate: RebalancedComponents | None = None
    primary: RebalancedComponents | None = None
    prior_passive_index = ONE
    prior_dra_index = ONE
    passive_equity = dra_equity = ONE

    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
            continue
        month_boundary = (
            bar.open_time > start
            and bar.open_time.day == 1
            and bar.open_time.hour == 0
        )
        if month_boundary:
            assert candidate is not None and primary is not None
            candidate.rebalance(friction)
            primary.rebalance(friction)

        engine.step(bar)
        unrealized = static.dra_unrealized(module, engine, bar.close)
        dra_equity = (module.REFERENCE_CAP + engine.realized + unrealized) / module.REFERENCE_CAP
        passive_market_value = passive_quantity * bar.close
        passive_equity = passive_cash + passive_market_value
        passive_return = passive_equity / prior_passive_index
        dra_return = dra_equity / prior_dra_index
        if candidate is None:
            candidate = RebalancedComponents(HALF * passive_equity, HALF * dra_equity)
            primary = RebalancedComponents(HALF * passive_equity, HALF, True, False)
        else:
            candidate.apply_returns(passive_return, dra_return)
            primary.apply_returns(passive_return, ONE)
        prior_passive_index = passive_equity
        prior_dra_index = dra_equity

        dra_market_value = sum((lot.quantity * bar.close for lot in engine.lots), ZERO)
        passive_market_fraction = passive_market_value / passive_equity
        dra_market_fraction = (
            dra_market_value / module.REFERENCE_CAP / dra_equity
            if dra_equity > ZERO
            else ZERO
        )
        candidate_equity = candidate.equity
        primary_equity = primary.equity
        candidate_passive_market = candidate.first_value * passive_market_fraction
        candidate_dra_market = candidate.second_value * dra_market_fraction
        candidate_path.observe(
            candidate_equity,
            passive_market_value=candidate_passive_market,
            dra_deployed_fraction=candidate.second_value
            / candidate_equity
            * (module.LOT_COST * D(len(engine.lots)) / module.REFERENCE_CAP),
            total_market_value=candidate_passive_market + candidate_dra_market,
        )
        primary_passive_market = primary.first_value * passive_market_fraction
        primary_path.observe(
            primary_equity,
            passive_market_value=primary_passive_market,
            total_market_value=primary_passive_market,
        )
        dra_path.observe(
            dra_equity,
            dra_deployed_fraction=module.LOT_COST * D(len(engine.lots)) / module.REFERENCE_CAP,
            total_market_value=dra_market_value / module.REFERENCE_CAP,
        )
        passive_path.observe(
            passive_equity,
            passive_market_value=passive_market_value,
            total_market_value=passive_market_value,
        )

    assert candidate is not None and primary is not None
    candidate.observe_weight()
    primary.observe_weight()
    final_bar = trading[-1]
    dra_result = engine.result(final_bar, start, end)
    candidate_output = _path_output(candidate_path, candidate.equity, candidate)
    primary_output = _path_output(primary_path, primary.equity, primary)
    dra_output = dra_path.output(dra_equity)
    passive_output = passive_path.output(passive_equity)
    dra_realized_return = D(dra_result["realized_usdt"]) / module.REFERENCE_CAP * HUNDRED
    dra_unrealized_return = D(dra_result["unrealized_usdt"]) / module.REFERENCE_CAP * HUNDRED
    dra_total_return = D(dra_result["total_pnl_usdt"]) / module.REFERENCE_CAP * HUNDRED
    candidate_return = D(candidate_output["total_return_pct"])
    primary_return = D(primary_output["total_return_pct"])
    candidate_drawdown = D(candidate_output["maximum_drawdown_pct"])
    primary_drawdown = D(primary_output["maximum_drawdown_pct"])
    candidate_calmar = D(candidate_output["calmar_ratio"])
    primary_calmar = D(primary_output["calmar_ratio"])
    passive_return_pct = D(passive_output["total_return_pct"])
    incremental = candidate_return - primary_return
    output = {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate_monthly_50_passive_50_dra": candidate_output,
        "primary_monthly_50_passive_50_cash": primary_output,
        "frozen_dra_v1": {
            **dra_output,
            "checkpoint": list(module.checkpoint_tuple(dra_result)),
            "realized_return_pct": quantized(dra_realized_return),
            "unrealized_return_pct": quantized(dra_unrealized_return),
            "total_return_pct": quantized(dra_total_return),
            "completed_lot_count": dra_result["sell_count"],
            "median_hold_hours": dra_result["median_hold_hours"],
            "p90_hold_hours": dra_result["p90_hold_hours"],
            "terminal_open_lots": dra_result["open_lots"],
            "terminal_open_cost_usdt": dra_result["ending_open_cost_usdt"],
        },
        "full_passive_btc": passive_output,
        "comparison": {
            "candidate_minus_primary_total_return_pp": quantized(incremental),
            "candidate_minus_primary_maximum_drawdown_pp": quantized(
                candidate_drawdown - primary_drawdown
            ),
            "candidate_minus_primary_calmar_ratio": quantized(
                candidate_calmar - primary_calmar
            ),
            "candidate_upside_capture_vs_passive": nullable(
                ratio(candidate_return, passive_return_pct)
                if passive_return_pct > ZERO
                else None
            ),
            "rebalance_cost_pct_of_positive_incremental_return": quantized(
                candidate.cost * HUNDRED / incremental * HUNDRED
                if incremental > ZERO
                else HUNDRED
            ),
            "terminal_dra_unrealized_share_of_dra_total_return_pct": quantized(
                max(dra_unrealized_return, ZERO) / dra_total_return * HUNDRED
                if dra_total_return > ZERO
                else ZERO
            ),
        },
    }
    raw = {
        "candidate_return": candidate_return,
        "candidate_drawdown": candidate_drawdown,
        "candidate_calmar": candidate_calmar,
        "candidate_rebalance_cost": candidate.cost * HUNDRED,
        "candidate_max_component_weight": candidate.maximum_pre_rebalance_weight * HUNDRED,
        "primary_return": primary_return,
        "primary_drawdown": primary_drawdown,
        "primary_calmar": primary_calmar,
        "dra_return": dra_total_return,
        "dra_unrealized_return": dra_unrealized_return,
        "passive_return": passive_return_pct,
        "passive_drawdown": D(passive_output["maximum_drawdown_pct"]),
        "upside_capture": candidate_return / passive_return_pct if passive_return_pct > ZERO else ZERO,
        "rebalance_cost_to_incremental": candidate.cost * HUNDRED / incremental * HUNDRED if incremental > ZERO else HUNDRED,
    }
    return output, raw


def evaluate_gates(
    module: Any,
    windows: dict[str, dict[str, tuple[dict[str, Any], dict[str, D]]]],
    annual: dict[str, dict[str, tuple[dict[str, Any], dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    normal_design_output, normal_design = windows["NORMAL"]["design"]
    normal_validation_output, normal_validation = windows["NORMAL"]["validation"]
    stress_design_output, stress_design = windows["STRESS"]["design"]
    stress_validation_output, stress_validation = windows["STRESS"]["validation"]
    gates = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_ohlcv_invariants_pass": True,
        "frozen_dra_source_passive_reference_static_parent_and_hypothesis_hashes_match": True,
        "dra_design_and_validation_checkpoints_exact": (
            tuple(normal_design_output["frozen_dra_v1"]["checkpoint"])
            == module.EXPECTED["v1_design"]
            and tuple(normal_validation_output["frozen_dra_v1"]["checkpoint"])
            == module.EXPECTED["v1_validation"]
        ),
        "normal_design_candidate_total_return_gt_primary": normal_design["candidate_return"] > normal_design["primary_return"],
        "normal_validation_candidate_total_return_gt_primary": normal_validation["candidate_return"] > normal_validation["primary_return"],
        "stress_design_candidate_total_return_gt_primary": stress_design["candidate_return"] > stress_design["primary_return"],
        "stress_validation_candidate_total_return_gt_primary": stress_validation["candidate_return"] > stress_validation["primary_return"],
        "normal_design_candidate_total_return_gt_dra": normal_design["candidate_return"] > normal_design["dra_return"],
        "normal_validation_candidate_total_return_gt_dra": normal_validation["candidate_return"] > normal_validation["dra_return"],
        "normal_design_drawdown_at_most_primary_plus_2pp": normal_design["candidate_drawdown"] <= normal_design["primary_drawdown"] + TWO_PP,
        "normal_validation_drawdown_at_most_primary_plus_2pp": normal_validation["candidate_drawdown"] <= normal_validation["primary_drawdown"] + TWO_PP,
        "normal_design_calmar_at_least_primary": normal_design["candidate_calmar"] >= normal_design["primary_calmar"],
        "normal_validation_calmar_at_least_primary": normal_validation["candidate_calmar"] >= normal_validation["primary_calmar"],
        "normal_design_drawdown_at_most_75pct_passive": normal_design["candidate_drawdown"] <= D("0.75") * normal_design["passive_drawdown"],
        "normal_validation_drawdown_at_most_75pct_passive": normal_validation["candidate_drawdown"] <= D("0.75") * normal_validation["passive_drawdown"],
        "normal_validation_upside_capture_at_least_45pct": normal_validation["upside_capture"] >= D("0.45"),
        "stress_design_total_return_positive": stress_design["candidate_return"] > ZERO,
        "stress_validation_total_return_positive": stress_validation["candidate_return"] > ZERO,
        "stress_validation_drawdown_within_normal_plus_2pp": stress_validation["candidate_drawdown"] <= normal_validation["candidate_drawdown"] + TWO_PP,
        "normal_design_max_pre_rebalance_component_weight_at_most_70pct": normal_design["candidate_max_component_weight"] <= D("70"),
        "normal_validation_max_pre_rebalance_component_weight_at_most_70pct": normal_validation["candidate_max_component_weight"] <= D("70"),
        "normal_validation_rebalance_cost_at_most_20pct_positive_incremental_return": normal_validation["rebalance_cost_to_incremental"] <= D("20"),
    }
    annual_normal = {year: value["NORMAL"][1] for year, value in annual.items()}
    positive_years = sum(value["candidate_return"] > ZERO for value in annual_normal.values())
    primary_wins = sum(value["candidate_return"] > value["primary_return"] for value in annual_normal.values())
    primary_dd = sum(value["candidate_drawdown"] <= value["primary_drawdown"] + TWO_PP for value in annual_normal.values())
    passive_dd = sum(value["candidate_drawdown"] <= value["passive_drawdown"] for value in annual_normal.values())
    increments = [max(value["candidate_return"] - value["primary_return"], ZERO) for value in annual_normal.values()]
    increment_total = sum(increments, ZERO)
    top_year = max(increments, default=ZERO) / increment_total * HUNDRED if increment_total > ZERO else HUNDRED
    terminal = D(normal_validation_output["comparison"]["terminal_dra_unrealized_share_of_dra_total_return_pct"])
    gates.update(
        {
            "candidate_positive_annual_total_return_at_least_4_of_5": positive_years >= 4,
            "candidate_total_return_gt_primary_at_least_4_of_5": primary_wins >= 4,
            "candidate_annual_drawdown_at_most_primary_plus_2pp_at_least_4_of_5": primary_dd >= 4,
            "candidate_annual_drawdown_at_most_passive_at_least_4_of_5": passive_dd >= 4,
            "top_year_positive_incremental_return_contribution_at_most_60pct": top_year <= D("60"),
            "validation_terminal_dra_unrealized_share_of_dra_total_return_at_most_40pct": terminal <= D("40"),
        }
    )
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    breadth = {
        "positive_candidate_years": f"{positive_years}_of_5",
        "candidate_total_return_gt_primary_years": f"{primary_wins}_of_5",
        "candidate_drawdown_at_most_primary_plus_2pp_years": f"{primary_dd}_of_5",
        "candidate_drawdown_at_most_passive_years": f"{passive_dd}_of_5",
        "top_year_positive_incremental_return_contribution_pct": quantized(top_year),
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    if sha256(input_path) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    module = static.load_dra_module()
    bars = module.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or module.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    windows = {
        name: {
            "design": simulate_window(module, bars, module.DESIGN, friction),
            "validation": simulate_window(module, bars, module.VALIDATION, friction),
        }
        for name, friction in {"NORMAL": NORMAL_REBALANCE_FRICTION, "STRESS": STRESS_REBALANCE_FRICTION}.items()
    }
    annual = {
        year: {
            name: simulate_window(module, bars, window, friction)
            for name, friction in {"NORMAL": NORMAL_REBALANCE_FRICTION, "STRESS": STRESS_REBALANCE_FRICTION}.items()
        }
        for year, window in module.FOLDS.items()
    }
    gates, failed, breadth = evaluate_gates(module, windows, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_MONTHLY_REBALANCED_HALF_PASSIVE_HALF_DRA_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_MONTHLY_REBALANCED_HALF_PASSIVE_HALF_DRA_V1_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_MONTHLY_REBALANCED_50_50_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(input_path), "rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
        "policy": {"target_weights": ["0.50", "0.50"], "rebalance": "FIRST_H1_OPEN_OF_EACH_UTC_CALENDAR_MONTH", "variants": 1},
        "windows": {scenario: {window: value[0] for window, value in values.items()} for scenario, values in windows.items()},
        "annual_fair_reset": {year: {scenario: value[0] for scenario, value in values.items()} for year, values in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-risk-budget component-index evidence only; a pass is not independent alpha, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    if not input_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
