#!/usr/bin/env python3
"""Deterministic matched-capital DRA stale-inventory-age economic screen."""

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
import dra_stale_inventory_age_support_v1 as support


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
MANIFEST_TYPE = "DRA_STALE_INVENTORY_AGE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "DRA_STALE_INVENTORY_AGE_ENTRY_ADMISSION_ECONOMIC_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_STALE_INVENTORY_AGE_ENTRY_ADMISSION_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1_BASELINE_250_USDT_FIXED_30_USDT_LOTS"
GATE_SET = "DRA_STALE_INVENTORY_AGE_ENTRY_ADMISSION_ECONOMIC_GATES_V1"
DATA_SHA256 = support.DATA_SHA256
DATA_ROWS = support.DATA_ROWS
DESIGN = support.DESIGN
VALIDATION = support.VALIDATION
FOLDS = support.ANNUAL
INITIAL_EQUITY_USDT = support.INITIAL_EQUITY_USDT
SLOT_CAPACITY_USDT = support.SLOT_CAPACITY_USDT
LOT_COST_USDT = support.LOT_COST_USDT
LOT_LIMIT = 8
DD_TOLERANCE_PP = D("0.25")
PCT_Q = D("0.000001")

PRIMARY_GATE_NAMES = [
    "all_action_accounting_reconciles",
    "design_total_pnl_improves",
    "validation_total_pnl_improves",
    "design_realized_pnl_improves",
    "validation_realized_pnl_improves",
    "validation_unrealized_non_worse",
    "design_risk_adjusted_score_improves",
    "validation_risk_adjusted_score_improves",
    "design_drawdown_within_0_25pp",
    "validation_drawdown_within_0_25pp",
    "design_max_underwater_duration_non_worse",
    "validation_max_underwater_duration_non_worse",
    "validation_median_hold_non_worse",
    "validation_p90_hold_non_worse",
    "design_terminal_inventory_count_non_worse",
    "validation_terminal_inventory_count_non_worse",
    "validation_terminal_inventory_cost_non_worse",
    "validation_terminal_unrealized_non_worse",
    "design_interventions_at_least_8",
    "validation_interventions_at_least_4",
    "annual_total_wins_at_least_3_of_5",
    "annual_drawdown_non_worse_at_least_4_of_5",
    "annual_risk_adjusted_wins_at_least_3_of_5",
    "top_year_positive_delta_contribution_at_most_60pct",
]
NEIGHBOR_GATE_NAMES = [
    "action_accounting_reconciles",
    "validation_total_pnl_non_worse",
    "validation_realized_non_worse",
    "validation_risk_adjusted_score_non_worse",
    "validation_drawdown_within_0_25pp",
    "validation_max_underwater_duration_non_worse",
    "validation_terminal_inventory_count_non_worse",
    "validation_interventions_at_least_4",
]
FORBIDDEN_RESCUE_ACTIONS = [
    "THRESHOLD_TUNING_AFTER_OUTCOME",
    "AGE_CLOCK_OR_INVENTORY_STATE_TUNING_AFTER_OUTCOME",
    "COOLDOWN_OR_CAPACITY_ROUTE_TUNING_AFTER_OUTCOME",
    "PNL_AWARE_DECISION_FILTER_AFTER_OUTCOME",
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


def state_output_path(value: Path) -> Path:
    path = value.resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        path.relative_to(state_root)
    except ValueError as error:
        raise ScreenReject("OUTPUT_SEAL_REJECT", f"output escapes research state: {path}") from error
    if path.exists():
        raise ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    return path


def _verify_binding(binding: dict[str, Any], *, role: str) -> dict[str, Any]:
    path = repository_path(binding["path"])
    if not path.is_file() or path.is_symlink() or sha256(path) != binding["sha256"]:
        raise ScreenReject("CONTRACT_REJECT", f"binding mismatch: {role}")
    return binding


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
        "lot_cost_usdt": "30",
        "fee_rate_each_side": "0.0010",
        "adverse_slippage_rate_each_side": "0.0005",
        "fill": "UNCHANGED_NEXT_H1_OPEN",
        "exit": "UNCHANGED_DRA_V1_PROFIT_ONLY_5PCT_NET_ESTIMATE",
    }:
        raise ScreenReject("CONTRACT_REJECT", "economics")
    if value.get("mechanism") != {
        "decision_clock": "UNCHANGED_DRA_SIGNAL_BEFORE_NEXT_H1_OPEN",
        "capacity_first": True,
        "age_clock": "DECISION_TIME_MINUS_OLDEST_OPEN_PARENT_LOT_ACTUAL_FILL_TIME_IN_HOURS",
        "no_open_inventory": "ADMIT",
        "relation": "VETO_AT_OR_ABOVE_THRESHOLD_HOURS",
        "veto_reserves_cooldown": "SEVEN_DAYS_FROM_ORIGINAL_SIGNAL",
        "capacity_block": "UNCHANGED_PARENT_BLOCK_WITHOUT_COOLDOWN_RESERVATION",
        "inventory_pnl_read": "DENY",
        "sell_or_resize_existing_lots": "DENY",
    }:
        raise ScreenReject("CONTRACT_REJECT", "mechanism")
    expected_variants = [
        {"role": role, "threshold_hours": str(threshold), "variant_id": variant_id}
        for role, threshold, variant_id in support.VARIANTS
    ]
    if value.get("variants") != expected_variants:
        raise ScreenReject("CONTRACT_REJECT", "variants")
    if value.get("gate_contract") != {
        "primary_all_required": PRIMARY_GATE_NAMES,
        "each_neighbor_all_required": NEIGHBOR_GATE_NAMES,
        "drawdown_tolerance_percentage_points": "0.25",
        "failure_disposition": "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_STALE_INVENTORY_AGE_FAMILY",
        "pass_disposition": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED",
    }:
        raise ScreenReject("CONTRACT_REJECT", "gate contract")
    if value.get("forbidden_rescue_actions") != FORBIDDEN_RESCUE_ACTIONS:
        raise ScreenReject("CONTRACT_REJECT", "forbidden rescue actions")
    runner_path = Path(__file__).resolve()
    if value.get("runner_binding") != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
    }:
        raise ScreenReject("CONTRACT_REJECT", "runner binding")
    support_path = Path(support.__file__).resolve()
    if value.get("support_runner_binding") != {
        "path": support_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(support_path),
    }:
        raise ScreenReject("CONTRACT_REJECT", "support runner binding")
    hypothesis = _verify_binding(value["hypothesis_binding"], role="hypothesis")
    if json.loads(repository_path(hypothesis["path"]).read_bytes()).get("hypothesis_id") != value.get("hypothesis_id"):
        raise ScreenReject("CONTRACT_REJECT", "hypothesis identity")
    acceptance = _verify_binding(value["prior_evidence"], role="preoutcome acceptance")
    acceptance_value = json.loads(repository_path(acceptance["path"]).read_bytes())
    if (
        acceptance_value.get("disposition")
        != "PASS_PREOUTCOME_DEDUP_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS"
        or acceptance_value.get("authorization") != AUTHORIZATION
        or not acceptance_value.get("support_result", {}).get("byte_identical")
    ):
        raise ScreenReject("CONTRACT_REJECT", "preoutcome acceptance identity")
    return value, raw


def load_selection(path: Path, manifest: dict[str, Any]) -> list[base.Bar]:
    if not path.is_file() or path.is_symlink() or sha256(path) != DATA_SHA256:
        raise ScreenReject("DATA_REJECT", "selection corpus hash")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    if len(bars) != DATA_ROWS or base.data_hash(bars) != DATA_SHA256:
        raise ScreenReject("DATA_REJECT", "selection corpus rows or canonical hash")
    cutoff = datetime.fromisoformat(manifest["dataset"]["selection_cutoff"])
    if bars[-1].close_time > cutoff:
        raise ScreenReject("OOS_REJECT", "selection corpus crosses cutoff")
    return bars


class StaleInventoryAgeAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    """Change only admission; decision state reads lot fill times, never inventory PnL."""

    def __init__(self, *, threshold_hours: D) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.threshold_hours = D(threshold_hours)
        self.signal_opportunity_count = 0
        self.capacity_blocked_signal_count = 0
        self.capacity_admissible_signal_count = 0
        self.vetoed_signal_count = 0
        self.admitted_signal_count = 0
        self.admitted_without_inventory_count = 0
        self.admitted_with_inventory_below_threshold_count = 0
        self.vetoed_cooldown_reservation_count = 0
        self.maximum_observed_oldest_open_lot_age_hours = D("0")

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            self.signal_opportunity_count += 1
            open_cost = LOT_COST_USDT * D(len(self.lots))
            if open_cost + LOT_COST_USDT > self.cap:
                self.blocked_count += 1
                self.capacity_blocked_signal_count += 1
            else:
                self.capacity_admissible_signal_count += 1
                state = support.inventory_age_state(self.lots, bar.open_time)
                if not state["capacity_admissible"]:
                    raise ScreenReject("ACCOUNTING_REJECT", "capacity-first state mismatch")
                age_hours = D(state["oldest_open_lot_age_hours"])
                self.maximum_observed_oldest_open_lot_age_hours = max(
                    self.maximum_observed_oldest_open_lot_age_hours, age_hours
                )
                if support.actionable_veto(state, self.threshold_hours):
                    self.vetoed_signal_count += 1
                    self.vetoed_cooldown_reservation_count += 1
                    self.last_entry_signal = bar.open_time
                else:
                    self.pending_signal = bar.open_time
                    self.pending_atr = self.atr14
                    self.last_entry_signal = bar.open_time
                    self.admitted_signal_count += 1
                    if state["has_open_inventory"]:
                        self.admitted_with_inventory_below_threshold_count += 1
                    else:
                        self.admitted_without_inventory_count += 1
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        pending_count = int(self.pending_signal is not None)
        action_accounting_reconciles = (
            self.signal_opportunity_count
            == self.capacity_blocked_signal_count
            + self.vetoed_signal_count
            + self.admitted_signal_count
            and self.admitted_signal_count == self.buy_count + pending_count
            and self.capacity_blocked_signal_count == self.blocked_count
            and self.vetoed_signal_count == self.vetoed_cooldown_reservation_count
            and self.admitted_signal_count
            == self.admitted_without_inventory_count
            + self.admitted_with_inventory_below_threshold_count
            and D(result["max_open_cost_usdt"]) <= SLOT_CAPACITY_USDT
            and len(result["terminal_inventory"]) <= LOT_LIMIT
        )
        result.update(
            {
                "runner_identity": RUNNER_IDENTITY,
                "threshold_hours": str(self.threshold_hours),
                "decision_state_fields_read": ["open_parent_lot_fill_time"],
                "inventory_pnl_read_for_decision": False,
                "signal_opportunity_count": self.signal_opportunity_count,
                "capacity_blocked_signal_count": self.capacity_blocked_signal_count,
                "capacity_admissible_signal_count": self.capacity_admissible_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "admitted_without_inventory_count": self.admitted_without_inventory_count,
                "admitted_with_inventory_below_threshold_count": self.admitted_with_inventory_below_threshold_count,
                "vetoed_cooldown_reservation_count": self.vetoed_cooldown_reservation_count,
                "maximum_observed_oldest_open_lot_age_hours": str(
                    self.maximum_observed_oldest_open_lot_age_hours
                ),
                "pending_admitted_signal_count": pending_count,
                "action_accounting_reconciles": action_accounting_reconciles,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    threshold_hours: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start}..{end}")
    engine = StaleInventoryAgeAdmissionEngine(threshold_hours=threshold_hours)
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
    baseline: dict[str, Any],
    variant: dict[str, Any],
) -> dict[str, Any]:
    threshold = D(variant["threshold_hours"])
    design = simulate_candidate(bars, DESIGN, threshold_hours=threshold)
    validation = simulate_candidate(bars, VALIDATION, threshold_hours=threshold)
    folds = {
        year: simulate_candidate(bars, window, threshold_hours=threshold)
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
        "threshold_hours": str(threshold),
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
        "annual_drawdown_non_worse": sum(
            _value(folds[year], "max_drawdown_pct")
            <= _value(baseline["folds"][year], "max_drawdown_pct")
            + DD_TOLERANCE_PP
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
        "all_action_accounting_reconciles": all(
            result["action_accounting_reconciles"]
            for result in [design, validation, *variant["folds"].values()]
        ),
        "design_total_pnl_improves": _value(design, "total_pnl_usdt")
        > _value(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": _value(validation, "total_pnl_usdt")
        > _value(parent_validation, "total_pnl_usdt"),
        "design_realized_pnl_improves": _value(design, "realized_usdt")
        > _value(parent_design, "realized_usdt"),
        "validation_realized_pnl_improves": _value(validation, "realized_usdt")
        > _value(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt")
        >= _value(parent_validation, "unrealized_usdt"),
        "design_risk_adjusted_score_improves": risk_adjusted_score(design)
        > risk_adjusted_score(parent_design),
        "validation_risk_adjusted_score_improves": risk_adjusted_score(validation)
        > risk_adjusted_score(parent_validation),
        "design_drawdown_within_0_25pp": _value(design, "max_drawdown_pct")
        <= _value(parent_design, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct")
        <= _value(parent_validation, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "design_max_underwater_duration_non_worse": int(
            design["inventory_path"]["maximum_underwater_duration_hours"]
        )
        <= int(parent_design["inventory_path"]["maximum_underwater_duration_hours"]),
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
        "design_terminal_inventory_count_non_worse": len(design["terminal_inventory"])
        <= len(parent_design["terminal_inventory"]),
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
        "design_interventions_at_least_8": int(design["vetoed_signal_count"]) >= 8,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"])
        >= 4,
        "annual_total_wins_at_least_3_of_5": int(variant["annual_total_wins"]) >= 3,
        "annual_drawdown_non_worse_at_least_4_of_5": int(
            variant["annual_drawdown_non_worse"]
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
        "action_accounting_reconciles": bool(validation["action_accounting_reconciles"]),
        "validation_total_pnl_non_worse": _value(validation, "total_pnl_usdt")
        >= _value(parent, "total_pnl_usdt"),
        "validation_realized_non_worse": _value(validation, "realized_usdt")
        >= _value(parent, "realized_usdt"),
        "validation_risk_adjusted_score_non_worse": risk_adjusted_score(validation)
        >= risk_adjusted_score(parent),
        "validation_drawdown_within_0_25pp": _value(
            validation, "max_drawdown_pct"
        )
        <= _value(parent, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_max_underwater_duration_non_worse": int(
            validation["inventory_path"]["maximum_underwater_duration_hours"]
        )
        <= int(parent["inventory_path"]["maximum_underwater_duration_hours"]),
        "validation_terminal_inventory_count_non_worse": len(
            validation["terminal_inventory"]
        )
        <= len(parent["terminal_inventory"]),
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"])
        >= 4,
    }


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    output = state_output_path(output_path)
    manifest, manifest_raw = load_manifest(manifest_path)
    bars = load_selection(input_path, manifest)
    baseline = parent_baseline(bars)
    variants = [variant_evidence(bars, baseline, variant) for variant in manifest["variants"]]
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
            else "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_STALE_INVENTORY_AGE_FAMILY"
        ),
        "recommended_next_action": (
            "FREEZE_SEPARATE_INDEPENDENT_OOS_CONTRACT_WITHOUT_ACTIVATION"
            if passed
            else "CLOSE_FAMILY_WITHOUT_THRESHOLD_CLOCK_STATE_COOLDOWN_ROUTE_OR_GATE_TUNING"
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
        },
        "parent_strategy": PARENT_STRATEGY,
        "economic_assumptions": manifest["economics"],
        "mechanism": manifest["mechanism"],
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
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as target:
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
    except (ScreenReject, support.SupportReject, base.ResearchReject, OSError, ValueError) as error:
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
    print(json.dumps({"status": result["status"], "output": args.output.as_posix()}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
