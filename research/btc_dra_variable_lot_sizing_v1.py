#!/usr/bin/env python3
"""Deterministic matched-capital DRA variable-lot-sizing economic screen."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
from typing import Any

import btc_dra_equal_capital_capacity_v1 as capacity
import btc_dra_reversal_confirmed_exit_v2c as base
import dra_lagged_realized_variance_scaled_lot_sizing_support_v1 as support


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
MANIFEST_TYPE = "DRA_VARIABLE_LOT_SIZING_MANIFEST_V1"
RESULT_TYPE = "DRA_VARIABLE_LOT_SIZING_ECONOMIC_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_VARIABLE_LOT_SIZING_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1_BASELINE_250_USDT_FIXED_30_USDT_LOTS"
GATE_SET = "DRA_VARIABLE_LOT_SIZING_ECONOMIC_GATES_V1"
DATA_SHA256 = support.DATA_SHA256
DATA_ROWS = support.DATA_ROWS
DESIGN = support.DESIGN
VALIDATION = support.VALIDATION
FOLDS = support.ANNUAL
INITIAL_EQUITY_USDT = D("250")
SLOT_CAPACITY_USDT = support.OPEN_COST_CAP
BASE_LOT_COST = support.BASE_LOT_COST
LOT_LIMIT = support.OPEN_LOT_LIMIT
DD_REDUCTION_MIN_PP = D("0.25")
PCT_Q = D("0.000001")
PRIMARY_GATE_NAMES = [
    "all_variable_sizing_accounting_reconciles",
    "design_total_pnl_improves",
    "validation_total_pnl_improves",
    "design_risk_adjusted_score_improves",
    "validation_risk_adjusted_score_improves",
    "validation_realized_non_worse",
    "validation_unrealized_non_worse",
    "validation_drawdown_reduces_at_least_0_25pp",
    "validation_max_underwater_duration_non_worse",
    "validation_median_hold_non_worse",
    "validation_p90_hold_non_worse",
    "validation_terminal_inventory_count_non_worse",
    "validation_terminal_inventory_cost_non_worse",
    "validation_terminal_unrealized_non_worse",
    "design_filled_scaled_lots_at_least_8",
    "validation_filled_scaled_lots_at_least_4",
    "annual_total_wins_at_least_3_of_5",
    "annual_drawdown_improves_at_least_4_of_5",
    "annual_risk_adjusted_wins_at_least_3_of_5",
    "top_year_positive_delta_contribution_at_most_60pct",
]
NEIGHBOR_GATE_NAMES = [
    "validation_total_pnl_non_worse",
    "validation_risk_adjusted_score_non_worse",
    "validation_drawdown_strictly_improves",
    "validation_realized_non_worse",
    "validation_unrealized_non_worse",
    "validation_filled_scaled_lots_at_least_4",
    "variable_sizing_accounting_reconciles",
]
FORBIDDEN_RESCUE_ACTIONS = [
    "FORMULA_TUNING_AFTER_OUTCOME",
    "FLOOR_TUNING_AFTER_OUTCOME",
    "CAP_TUNING_AFTER_OUTCOME",
    "GATE_RELAXATION_AFTER_OUTCOME",
    "OOS_ACCESS_DURING_HISTORICAL_SCREEN",
    "FAMILY_REOPEN_AFTER_FAILED_FROZEN_SCREEN",
]


class ScreenReject(RuntimeError):
    def __init__(self, status: str, detail: Any) -> None:
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def sha256(path_or_bytes: Path | bytes) -> str:
    raw = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def repository_path(value: str) -> Path:
    path = (REPO_ROOT / value).resolve()
    try:
        path.relative_to(REPO_ROOT)
    except ValueError as error:
        raise ScreenReject("CONTRACT_REJECT", f"path escapes repository: {path}") from error
    return path


def _verify_binding(binding: dict[str, Any], *, role: str) -> dict[str, Any]:
    path = repository_path(binding["path"])
    if not path.is_file() or path.is_symlink() or sha256(path) != binding["sha256"]:
        raise ScreenReject("CONTRACT_REJECT", f"binding mismatch: {role}")
    return {"path": binding["path"], "sha256": binding["sha256"]}


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScreenReject("CONTRACT_REJECT", "manifest must be strict JSON") from error
    if value.get("schema_version") != "1" or value.get("document_type") != MANIFEST_TYPE:
        raise ScreenReject("CONTRACT_REJECT", "manifest identity")
    if value.get("authorization") != AUTHORIZATION:
        raise ScreenReject("CONTRACT_REJECT", "authorization")
    if value.get("parent_strategy") != PARENT_STRATEGY or value.get("gate_set") != GATE_SET:
        raise ScreenReject("CONTRACT_REJECT", "parent or gate set")
    if value.get("oos_access") != "DENY":
        raise ScreenReject("OOS_REJECT", "historical screen cannot open OOS")
    if value.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "canonical_sha256": DATA_SHA256,
        "rows": DATA_ROWS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ScreenReject("CONTRACT_REJECT", "dataset")
    if value.get("economics") != {
        "initial_equity_usdt": "250",
        "maximum_open_cost_usdt": "240",
        "maximum_open_lots": 8,
        "base_lot_cost_usdt": "30",
        "fee_rate_each_side": "0.0010",
        "adverse_slippage_rate_each_side": "0.0005",
        "fill": "UNCHANGED_NEXT_H1_OPEN",
        "exit": "UNCHANGED_DRA_V1_PROFIT_ONLY_5PCT_NET_ESTIMATE",
    }:
        raise ScreenReject("CONTRACT_REJECT", "economics")
    if value.get("feature") != {
        "key": "LATEST_COMPLETE_UTC_DAY_REALIZED_VARIANCE_TO_PRIOR_20_COMPLETE_DAY_MEDIAN",
        "decision_time": "UNCHANGED_DRA_SIGNAL_AT_COMPLETE_UTC_DAY_23_00_BEFORE_NEXT_H1_OPEN",
        "return": "H1_SIMPLE_CLOSE_TO_PREVIOUS_H1_CLOSE",
        "mapping": "MAX_FLOOR_MIN_30_30_DIV_NORMALIZED_VARIANCE_RATIO_QUANTIZED_HALF_UP_1E_8",
        "missing_value": "UNCHANGED_30_USDT_SIGNAL_PRESERVED",
    }:
        raise ScreenReject("CONTRACT_REJECT", "feature")
    if value.get("variants") != [
        {
            "role": "lower_floor_neighbor",
            "floor_usdt": "10",
            "variant_id": "rv-scaled-new-lot-floor-10-v1",
        },
        {
            "role": "primary",
            "floor_usdt": "15",
            "variant_id": "rv-scaled-new-lot-floor-15-v1",
        },
        {
            "role": "upper_floor_neighbor",
            "floor_usdt": "20",
            "variant_id": "rv-scaled-new-lot-floor-20-v1",
        },
    ]:
        raise ScreenReject("CONTRACT_REJECT", "variants")
    if value.get("gate_contract") != {
        "primary_all_required": PRIMARY_GATE_NAMES,
        "each_neighbor_all_required": NEIGHBOR_GATE_NAMES,
        "failure_disposition": "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_VARIABLE_LOT_SIZING_FAMILY",
        "pass_disposition": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED",
    }:
        raise ScreenReject("CONTRACT_REJECT", "gate contract")
    if value.get("forbidden_rescue_actions") != FORBIDDEN_RESCUE_ACTIONS:
        raise ScreenReject("CONTRACT_REJECT", "forbidden rescue actions")
    runner = value.get("runner_binding")
    runner_path = Path(__file__).resolve()
    if runner != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
    }:
        raise ScreenReject("CONTRACT_REJECT", "runner binding")
    support_binding = value.get("support_runner_binding")
    expected_support_path = Path(support.__file__).resolve()
    if support_binding != {
        "path": expected_support_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(expected_support_path),
    }:
        raise ScreenReject("CONTRACT_REJECT", "support runner binding")
    _verify_binding(value["hypothesis_binding"], role="hypothesis")
    acceptance = _verify_binding(value["prior_evidence"], role="preoutcome acceptance")
    acceptance_value = json.loads(repository_path(acceptance["path"]).read_text(encoding="utf-8"))
    if (
        acceptance_value.get("disposition")
        != "PASS_PREOUTCOME_DEDUP_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS"
        or acceptance_value.get("authorization") != AUTHORIZATION
    ):
        raise ScreenReject("CONTRACT_REJECT", "preoutcome acceptance identity")
    return value, raw


def load_selection(path: Path, manifest: dict[str, Any]) -> list[base.Bar]:
    if not path.is_file() or sha256(path) != DATA_SHA256:
        raise ScreenReject("DATA_REJECT", "selection corpus hash")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    if len(bars) != DATA_ROWS or base.data_hash(bars) != DATA_SHA256:
        raise ScreenReject("DATA_REJECT", "selection corpus rows or canonical hash")
    cutoff = datetime.fromisoformat(manifest["dataset"]["selection_cutoff"])
    if bars[-1].close_time > cutoff:
        raise ScreenReject("OOS_REJECT", "selection corpus crosses cutoff")
    return bars


def normalized_variance_lattice(bars: list[base.Bar]) -> dict[Any, D]:
    observations = support.aggregate_complete_days(bars)
    raw = support.raw_variances(observations)
    normalized = support.normalized_series(raw)
    if len(normalized) != support.NORMALIZED_ROWS:
        raise ScreenReject("DATA_REJECT", "normalized feature coverage")
    return normalized


def _percentage(numerator: D, denominator: D) -> str:
    if denominator <= 0:
        raise ScreenReject("ACCOUNTING_REJECT", "percentage denominator")
    return str((numerator / denominator * D("100")).quantize(PCT_Q, rounding=ROUND_HALF_UP))


class VariableLotSizingEngine(capacity.EqualCapitalCapacityEngine):
    def __init__(self, *, normalized_variance: dict[Any, D], floor_usdt: D) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.normalized_variance = normalized_variance
        self.floor_usdt = floor_usdt
        self.pending_lot_cost: D | None = None
        self.parent_signal_count = 0
        self.sized_signal_count = 0
        self.blocked_signal_count = 0
        self.feature_available_signal_count = 0
        self.feature_unavailable_signal_count = 0
        self.scaled_signal_count = 0
        self.filled_scaled_lot_count = 0
        self.filled_lot_cost_usdt = D("0")
        self.maximum_open_lot_count = 0

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            self.parent_signal_count += 1
            ratio = self.normalized_variance.get(bar.open_time.date())
            if ratio is None:
                self.feature_unavailable_signal_count += 1
            else:
                self.feature_available_signal_count += 1
            cost = support.action_lot_cost(ratio, self.floor_usdt)
            if cost < BASE_LOT_COST:
                self.scaled_signal_count += 1
            open_cost = sum((lot.cost for lot in self.lots), D("0"))
            if len(self.lots) >= LOT_LIMIT or open_cost + cost > SLOT_CAPACITY_USDT:
                self.blocked_count += 1
                self.blocked_signal_count += 1
            else:
                self.pending_signal = bar.open_time
                self.pending_atr = self.atr14
                self.pending_lot_cost = cost
                self.last_entry_signal = bar.open_time
                self.sized_signal_count += 1
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def _fill_buy(self, bar: base.Bar) -> None:
        if self.pending_signal is None or self.pending_lot_cost is None:
            return
        cost = base.money(self.pending_lot_cost)
        price = base.adverse_buy(bar.open)
        fee = base.money(cost * base.FEE)
        fill_quantity = base.quantity((cost - fee) / price)
        self.lots.append(
            base.Lot(
                signal_time=self.pending_signal,
                fill_time=bar.open_time,
                cost=cost,
                buy_price=price,
                quantity=fill_quantity,
                entry_atr=self.pending_atr,
                highest_close=bar.close,
            )
        )
        self.buy_count += 1
        self.filled_lot_cost_usdt += cost
        if cost < BASE_LOT_COST:
            self.filled_scaled_lot_count += 1
        self.pending_signal = None
        self.pending_atr = None
        self.pending_lot_cost = None

    def _track(self, bar: base.Bar) -> None:
        open_cost = sum((lot.cost for lot in self.lots), D("0"))
        unrealized = base.money(
            sum(
                (base.estimated_net(lot.quantity, bar.close) - lot.cost for lot in self.lots),
                base.ZERO,
            )
        )
        equity = base.money(self.initial_equity + self.realized + unrealized)
        self.peak_equity = max(self.peak_equity, equity)
        self.minimum_equity = min(self.minimum_equity, equity)
        drawdown = (
            (self.peak_equity - equity) / self.peak_equity
            if self.peak_equity > 0
            else base.ZERO
        )
        if drawdown > self.max_drawdown:
            self.max_drawdown = drawdown
            self.maximum_drawdown_at = bar.open_time
        if drawdown > base.ZERO:
            if self.current_underwater_hours == 0:
                self.underwater_episode_count += 1
            self.underwater_hours += 1
            self.current_underwater_hours += 1
            self.maximum_underwater_duration_hours = max(
                self.maximum_underwater_duration_hours,
                self.current_underwater_hours,
            )
        else:
            self.current_underwater_hours = 0
        self.max_open_cost = max(self.max_open_cost, open_cost)
        self.maximum_open_lot_count = max(self.maximum_open_lot_count, len(self.lots))
        self.inventory_hour_counts[len(self.lots)] = (
            self.inventory_hour_counts.get(len(self.lots), 0) + 1
        )
        self.utilization_sum += open_cost / self.cap
        self.equity_utilization_sum += open_cost / self.initial_equity
        self.utilization_points += 1

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        ending_open_cost = sum((lot.cost for lot in self.lots), D("0"))
        pending_count = int(self.pending_lot_cost is not None)
        result.update(
            {
                "runner_identity": RUNNER_IDENTITY,
                "sizing_feature": "LATEST_COMPLETE_UTC_DAY_REALIZED_VARIANCE_TO_PRIOR_20_COMPLETE_DAY_MEDIAN",
                "lot_floor_usdt": str(self.floor_usdt),
                "base_lot_cost_usdt": str(BASE_LOT_COST),
                "ending_open_cost_usdt": str(base.money(ending_open_cost)),
                "parent_signal_count": self.parent_signal_count,
                "sized_signal_count": self.sized_signal_count,
                "blocked_signal_count": self.blocked_signal_count,
                "feature_available_signal_count": self.feature_available_signal_count,
                "feature_unavailable_signal_count": self.feature_unavailable_signal_count,
                "scaled_signal_count": self.scaled_signal_count,
                "filled_scaled_lot_count": self.filled_scaled_lot_count,
                "filled_lot_cost_usdt": str(base.money(self.filled_lot_cost_usdt)),
                "maximum_open_lot_count": self.maximum_open_lot_count,
                "pending_sized_signal_count": pending_count,
                "variable_sizing_accounting_reconciles": (
                    self.parent_signal_count
                    == self.sized_signal_count + self.blocked_signal_count
                    and self.sized_signal_count == self.buy_count + pending_count
                    and self.blocked_signal_count == self.blocked_count
                    and ending_open_cost <= SLOT_CAPACITY_USDT
                    and self.maximum_open_lot_count <= LOT_LIMIT
                ),
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    normalized: dict[Any, D],
    window: tuple[datetime, datetime],
    *,
    floor_usdt: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start}..{end}")
    engine = VariableLotSizingEngine(
        normalized_variance=normalized, floor_usdt=floor_usdt
    )
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    return {
        "design": capacity.simulate_capacity(
            bars,
            DESIGN,
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        ),
        "validation": capacity.simulate_capacity(
            bars,
            VALIDATION,
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        ),
        "folds": {
            year: capacity.simulate_capacity(
                bars,
                window,
                slot_capacity_usdt=SLOT_CAPACITY_USDT,
                initial_equity_usdt=INITIAL_EQUITY_USDT,
            )
            for year, window in FOLDS.items()
        },
    }


def _value(result: dict[str, Any], field: str) -> D:
    return D(str(result[field]))


def risk_adjusted_score(result: dict[str, Any]) -> D:
    drawdown = _value(result, "max_drawdown_pct")
    if drawdown <= 0:
        raise ScreenReject("ACCOUNTING_REJECT", "nonpositive drawdown")
    return _value(result, "total_pnl_usdt") / drawdown


def terminal_inventory_cost(result: dict[str, Any]) -> D:
    return sum((D(item["cost_usdt"]) for item in result["terminal_inventory"]), D("0"))


def terminal_inventory_unrealized(result: dict[str, Any]) -> D:
    return sum(
        (D(item["unrealized_pnl_usdt"]) for item in result["terminal_inventory"]),
        D("0"),
    )


def _non_worse_holding(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    candidate_value = candidate.get(field)
    parent_value = parent.get(field)
    if candidate_value is None or parent_value is None:
        return candidate_value == parent_value
    return D(str(candidate_value)) <= D(str(parent_value))


def variant_evidence(
    bars: list[base.Bar],
    normalized: dict[Any, D],
    baseline: dict[str, Any],
    variant: dict[str, Any],
) -> dict[str, Any]:
    floor = D(variant["floor_usdt"])
    design = simulate_candidate(bars, normalized, DESIGN, floor_usdt=floor)
    validation = simulate_candidate(bars, normalized, VALIDATION, floor_usdt=floor)
    folds = {
        year: simulate_candidate(bars, normalized, window, floor_usdt=floor)
        for year, window in FOLDS.items()
    }
    annual_total_deltas = {
        year: _value(folds[year], "total_pnl_usdt")
        - _value(baseline["folds"][year], "total_pnl_usdt")
        for year in FOLDS
    }
    positive = [value for value in annual_total_deltas.values() if value > 0]
    positive_total = sum(positive, D("0"))
    concentration = (
        max(positive) / positive_total * D("100") if positive_total > 0 else D("100")
    )
    return {
        "variant_id": variant["variant_id"],
        "role": variant["role"],
        "floor_usdt": str(floor),
        "design": design,
        "validation": validation,
        "folds": folds,
        "paired_equal_capital": {
            "design": capacity.equal_capital_deltas(baseline["design"], design),
            "validation": capacity.equal_capital_deltas(
                baseline["validation"], validation
            ),
            "folds": {
                year: capacity.equal_capital_deltas(
                    baseline["folds"][year], folds[year]
                )
                for year in FOLDS
            },
        },
        "risk_adjusted_score": {
            "design": str(risk_adjusted_score(design).quantize(D("0.00000001"))),
            "validation": str(
                risk_adjusted_score(validation).quantize(D("0.00000001"))
            ),
        },
        "annual_total_pnl_delta": {
            year: str(value) for year, value in annual_total_deltas.items()
        },
        "annual_total_wins": sum(value > 0 for value in annual_total_deltas.values()),
        "annual_drawdown_improves": sum(
            _value(folds[year], "max_drawdown_pct")
            < _value(baseline["folds"][year], "max_drawdown_pct")
            for year in FOLDS
        ),
        "annual_risk_adjusted_wins": sum(
            risk_adjusted_score(folds[year])
            > risk_adjusted_score(baseline["folds"][year])
            for year in FOLDS
        ),
        "top_year_positive_delta_contribution_pct": str(
            concentration.quantize(PCT_Q, rounding=ROUND_HALF_UP)
        ),
    }


def primary_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    design = variant["design"]
    validation = variant["validation"]
    parent_design = baseline["design"]
    parent_validation = baseline["validation"]
    return {
        "all_variable_sizing_accounting_reconciles": all(
            result["variable_sizing_accounting_reconciles"]
            for result in [design, validation, *variant["folds"].values()]
        ),
        "design_total_pnl_improves": _value(design, "total_pnl_usdt")
        > _value(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": _value(validation, "total_pnl_usdt")
        > _value(parent_validation, "total_pnl_usdt"),
        "design_risk_adjusted_score_improves": risk_adjusted_score(design)
        > risk_adjusted_score(parent_design),
        "validation_risk_adjusted_score_improves": risk_adjusted_score(validation)
        > risk_adjusted_score(parent_validation),
        "validation_realized_non_worse": _value(validation, "realized_usdt")
        >= _value(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt")
        >= _value(parent_validation, "unrealized_usdt"),
        "validation_drawdown_reduces_at_least_0_25pp": _value(
            validation, "max_drawdown_pct"
        )
        <= _value(parent_validation, "max_drawdown_pct") - DD_REDUCTION_MIN_PP,
        "validation_max_underwater_duration_non_worse": int(
            validation["inventory_path"]["maximum_underwater_duration_hours"]
        )
        <= int(
            parent_validation["inventory_path"]["maximum_underwater_duration_hours"]
        ),
        "validation_median_hold_non_worse": _non_worse_holding(
            validation, parent_validation, "median_hold_hours"
        ),
        "validation_p90_hold_non_worse": _non_worse_holding(
            validation, parent_validation, "p90_hold_hours"
        ),
        "validation_terminal_inventory_count_non_worse": len(
            validation["terminal_inventory"]
        )
        <= len(parent_validation["terminal_inventory"]),
        "validation_terminal_inventory_cost_non_worse": terminal_inventory_cost(
            validation
        )
        <= terminal_inventory_cost(parent_validation),
        "validation_terminal_unrealized_non_worse": terminal_inventory_unrealized(
            validation
        )
        >= terminal_inventory_unrealized(parent_validation),
        "design_filled_scaled_lots_at_least_8": int(design["filled_scaled_lot_count"])
        >= 8,
        "validation_filled_scaled_lots_at_least_4": int(
            validation["filled_scaled_lot_count"]
        )
        >= 4,
        "annual_total_wins_at_least_3_of_5": int(variant["annual_total_wins"]) >= 3,
        "annual_drawdown_improves_at_least_4_of_5": int(
            variant["annual_drawdown_improves"]
        )
        >= 4,
        "annual_risk_adjusted_wins_at_least_3_of_5": int(
            variant["annual_risk_adjusted_wins"]
        )
        >= 3,
        "top_year_positive_delta_contribution_at_most_60pct": D(
            variant["top_year_positive_delta_contribution_pct"]
        )
        <= D("60"),
    }


def neighbor_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    validation = variant["validation"]
    parent = baseline["validation"]
    return {
        "validation_total_pnl_non_worse": _value(validation, "total_pnl_usdt")
        >= _value(parent, "total_pnl_usdt"),
        "validation_risk_adjusted_score_non_worse": risk_adjusted_score(validation)
        >= risk_adjusted_score(parent),
        "validation_drawdown_strictly_improves": _value(
            validation, "max_drawdown_pct"
        )
        < _value(parent, "max_drawdown_pct"),
        "validation_realized_non_worse": _value(validation, "realized_usdt")
        >= _value(parent, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt")
        >= _value(parent, "unrealized_usdt"),
        "validation_filled_scaled_lots_at_least_4": int(
            validation["filled_scaled_lot_count"]
        )
        >= 4,
        "variable_sizing_accounting_reconciles": bool(
            validation["variable_sizing_accounting_reconciles"]
        ),
    }


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bars = load_selection(input_path, manifest)
    normalized = normalized_variance_lattice(bars)
    baseline = parent_baseline(bars)
    variants = [
        variant_evidence(bars, normalized, baseline, variant)
        for variant in manifest["variants"]
    ]
    primary = next(item for item in variants if item["role"] == "primary")
    primary_checks = primary_gates(primary, baseline)
    neighbor_checks = {
        item["variant_id"]: neighbor_gates(item, baseline)
        for item in variants
        if item["role"] != "primary"
    }
    passed = all(primary_checks.values()) and all(
        all(checks.values()) for checks in neighbor_checks.values()
    )
    result = {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "experiment_id": manifest["experiment_id"],
        "hypothesis_id": manifest["hypothesis_id"],
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_VARIABLE_LOT_SIZING_FAMILY"
        ),
        "recommended_next_action": (
            "FREEZE_SEPARATE_INDEPENDENT_OOS_CONTRACT_WITHOUT_ACTIVATION"
            if passed
            else "CLOSE_FAMILY_WITHOUT_FORMULA_FLOOR_CAP_OR_GATE_TUNING"
        ),
        "manifest_sha256": sha256(manifest_raw),
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256(Path(__file__).resolve()),
        "support_runner_sha256": sha256(Path(support.__file__).resolve()),
        "dataset": {
            "path": manifest["dataset"]["path"],
            "canonical_sha256": base.data_hash(bars),
            "rows": len(bars),
            "selection_cutoff": manifest["dataset"]["selection_cutoff"],
            "normalized_feature_rows": len(normalized),
        },
        "parent_strategy": PARENT_STRATEGY,
        "economic_assumptions": manifest["economics"],
        "gate_set": GATE_SET,
        "baseline": baseline,
        "variants": variants,
        "primary_gates": primary_checks,
        "neighbor_stability_gates": neighbor_checks,
        "candidate_created": passed,
        "oos_opened": False,
        "activation": "REPORTED_NOT_ACTIVATED",
        "scope_note": "Historical matched-capital research only. No paid API, second timer, second writer, external backfill, canonical state write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("xb") as target:
        target.write(canonical_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_screen(
            args.manifest.resolve(), args.input.resolve(), args.output.resolve()
        )
    except (ScreenReject, support.SupportReject, base.ResearchReject) as error:
        print(
            json.dumps(
                {
                    "status": getattr(error, "status", "DATA_REJECT"),
                    "detail": getattr(error, "detail", str(error)),
                },
                ensure_ascii=False,
            )
        )
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": args.output.as_posix(),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
