#!/usr/bin/env python3
"""Deterministic matched-capital DRA native trend-acceleration screen."""

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
import btc_dra_stale_inventory_age_entry_admission_v1 as economic_common
import dra_native_trend_acceleration_support_v1 as support


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
MANIFEST_TYPE = "DRA_NATIVE_TREND_ACCELERATION_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "DRA_NATIVE_TREND_ACCELERATION_ENTRY_ADMISSION_ECONOMIC_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_NATIVE_TREND_ACCELERATION_ENTRY_ADMISSION_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1_BASELINE_250_USDT_FIXED_30_USDT_LOTS"
GATE_SET = "DRA_NATIVE_TREND_ACCELERATION_ENTRY_ADMISSION_ECONOMIC_GATES_V1"
DATA_SHA256 = support.DATA_SHA256
DATA_ROWS = support.DATA_ROWS
DESIGN = support.DESIGN
VALIDATION = support.VALIDATION
FOLDS = support.ANNUAL
INITIAL_EQUITY_USDT = support.INITIAL_EQUITY_USDT
SLOT_CAPACITY_USDT = support.SLOT_CAPACITY_USDT
LOT_COST_USDT = support.LOT_COST_USDT
LOT_LIMIT = 8
DD_TOLERANCE_PP = economic_common.DD_TOLERANCE_PP
PCT_Q = D("0.000001")
PRIMARY_GATE_NAMES = list(economic_common.PRIMARY_GATE_NAMES)
NEIGHBOR_GATE_NAMES = list(economic_common.NEIGHBOR_GATE_NAMES)
FORBIDDEN_RESCUE_ACTIONS = [
    "THRESHOLD_TUNING_AFTER_OUTCOME",
    "EMA20_WINDOW_ATR_UPDATE_OR_DIRECTION_TUNING_AFTER_OUTCOME",
    "COOLDOWN_OR_CAPACITY_ROUTE_TUNING_AFTER_OUTCOME",
    "INVENTORY_OR_PNL_AWARE_DECISION_FILTER_AFTER_OUTCOME",
    "PARENT_SIGNAL_OR_EXIT_CHANGE_AFTER_OUTCOME",
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
        raise ScreenReject(
            "OUTPUT_SEAL_REJECT", f"output escapes research state: {path}"
        ) from error
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
        "decision_clock": "UNCHANGED_DRA_SIGNAL_AFTER_COMPLETE_DAY_BEFORE_NEXT_H1_OPEN",
        "capacity_first": True,
        "feature": "EMA20_SECOND_FIVE_DAY_DIFFERENCE_DIV_UPDATED_ATR14",
        "complete_day_offsets": [0, 5, 10],
        "relation": "VETO_BELOW_THRESHOLD_ATR",
        "veto_reserves_cooldown": "SEVEN_DAYS_FROM_ORIGINAL_SIGNAL",
        "feature_unavailable": "VETO_AND_RESERVE_ORIGINAL_SIGNAL_COOLDOWN",
        "capacity_block": "UNCHANGED_PARENT_BLOCK_WITHOUT_COOLDOWN_RESERVATION",
        "inventory_or_pnl_state_read": "DENY",
        "sell_or_resize_existing_lots": "DENY",
    }:
        raise ScreenReject("CONTRACT_REJECT", "mechanism")
    expected_variants = [
        {"role": role, "threshold_atr": str(threshold), "variant_id": variant_id}
        for role, threshold, variant_id in support.VARIANTS
    ]
    if value.get("variants") != expected_variants:
        raise ScreenReject("CONTRACT_REJECT", "variants")
    if value.get("gate_contract") != {
        "primary_all_required": PRIMARY_GATE_NAMES,
        "each_neighbor_all_required": NEIGHBOR_GATE_NAMES,
        "drawdown_tolerance_percentage_points": "0.25",
        "failure_disposition": "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_NATIVE_TREND_ACCELERATION_FAMILY",
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
    helper_path = Path(economic_common.__file__).resolve()
    if value.get("economic_gate_helper_binding") != {
        "path": helper_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(helper_path),
    }:
        raise ScreenReject("CONTRACT_REJECT", "economic gate helper binding")
    hypothesis = _verify_binding(value["hypothesis_binding"], role="hypothesis")
    if json.loads(repository_path(hypothesis["path"]).read_bytes()).get(
        "hypothesis_id"
    ) != value.get("hypothesis_id"):
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


class NativeTrendAccelerationAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    """Change only entry admission using complete-day EMA20 curvature."""

    def __init__(self, *, threshold_atr: D) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.threshold_atr = D(threshold_atr)
        self.signal_opportunity_count = 0
        self.capacity_blocked_signal_count = 0
        self.capacity_admissible_signal_count = 0
        self.feature_available_signal_count = 0
        self.feature_unavailable_signal_count = 0
        self.vetoed_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_cooldown_reservation_count = 0
        self.minimum_observed_acceleration_atr: D | None = None
        self.maximum_observed_acceleration_atr: D | None = None

    def _decision_feature(self) -> D | None:
        points = list(self.daily_points)
        current = points[-1] if len(points) >= 1 else None
        five_days_ago = points[-6] if len(points) >= 6 else None
        ten_days_ago = points[-11] if len(points) >= 11 else None
        return support.trend_acceleration(
            None if current is None else current.ema20,
            None if five_days_ago is None else five_days_ago.ema20,
            None if ten_days_ago is None else ten_days_ago.ema20,
            self.atr14,
        )

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
                acceleration = self._decision_feature()
                if acceleration is None:
                    self.feature_unavailable_signal_count += 1
                else:
                    self.feature_available_signal_count += 1
                    self.minimum_observed_acceleration_atr = (
                        acceleration
                        if self.minimum_observed_acceleration_atr is None
                        else min(self.minimum_observed_acceleration_atr, acceleration)
                    )
                    self.maximum_observed_acceleration_atr = (
                        acceleration
                        if self.maximum_observed_acceleration_atr is None
                        else max(self.maximum_observed_acceleration_atr, acceleration)
                    )
                snapshot = {
                    "capacity_admissible": True,
                    "ema20_acceleration_atr": (
                        None if acceleration is None else str(acceleration)
                    ),
                }
                if support.actionable_veto(snapshot, self.threshold_atr):
                    self.vetoed_signal_count += 1
                    self.vetoed_cooldown_reservation_count += 1
                    self.last_entry_signal = bar.open_time
                else:
                    self.pending_signal = bar.open_time
                    self.pending_atr = self.atr14
                    self.last_entry_signal = bar.open_time
                    self.admitted_signal_count += 1
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
            and self.capacity_admissible_signal_count
            == self.feature_available_signal_count + self.feature_unavailable_signal_count
            and self.admitted_signal_count == self.buy_count + pending_count
            and self.capacity_blocked_signal_count == self.blocked_count
            and self.vetoed_signal_count == self.vetoed_cooldown_reservation_count
            and D(result["max_open_cost_usdt"]) <= SLOT_CAPACITY_USDT
            and len(result["terminal_inventory"]) <= LOT_LIMIT
        )
        result.update(
            {
                "runner_identity": RUNNER_IDENTITY,
                "threshold_atr": str(self.threshold_atr),
                "decision_state_fields_read": [
                    "complete_day_ema20_t",
                    "complete_day_ema20_t_minus_5d",
                    "complete_day_ema20_t_minus_10d",
                    "updated_atr14_t",
                ],
                "inventory_or_pnl_state_read_for_decision": False,
                "signal_opportunity_count": self.signal_opportunity_count,
                "capacity_blocked_signal_count": self.capacity_blocked_signal_count,
                "capacity_admissible_signal_count": self.capacity_admissible_signal_count,
                "feature_available_signal_count": self.feature_available_signal_count,
                "feature_unavailable_signal_count": self.feature_unavailable_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "vetoed_cooldown_reservation_count": self.vetoed_cooldown_reservation_count,
                "minimum_observed_acceleration_atr": (
                    None
                    if self.minimum_observed_acceleration_atr is None
                    else str(self.minimum_observed_acceleration_atr)
                ),
                "maximum_observed_acceleration_atr": (
                    None
                    if self.maximum_observed_acceleration_atr is None
                    else str(self.maximum_observed_acceleration_atr)
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
    threshold_atr: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start}..{end}")
    engine = NativeTrendAccelerationAdmissionEngine(threshold_atr=threshold_atr)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def variant_evidence(
    bars: list[base.Bar],
    baseline: dict[str, Any],
    variant: dict[str, Any],
) -> dict[str, Any]:
    threshold = D(variant["threshold_atr"])
    design = simulate_candidate(bars, DESIGN, threshold_atr=threshold)
    validation = simulate_candidate(bars, VALIDATION, threshold_atr=threshold)
    folds = {
        year: simulate_candidate(bars, window, threshold_atr=threshold)
        for year, window in FOLDS.items()
    }
    annual_total_deltas = {
        year: economic_common._value(folds[year], "total_pnl_usdt")
        - economic_common._value(baseline["folds"][year], "total_pnl_usdt")
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
        "threshold_atr": str(threshold),
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
            "design": str(
                economic_common.risk_adjusted_score(design).quantize(D("0.00000001"))
            ),
            "validation": str(
                economic_common.risk_adjusted_score(validation).quantize(D("0.00000001"))
            ),
        },
        "annual_total_pnl_delta": {
            year: str(value) for year, value in annual_total_deltas.items()
        },
        "annual_total_wins": sum(value > 0 for value in annual_total_deltas.values()),
        "annual_drawdown_non_worse": sum(
            economic_common._value(folds[year], "max_drawdown_pct")
            <= economic_common._value(baseline["folds"][year], "max_drawdown_pct")
            + DD_TOLERANCE_PP
            for year in FOLDS
        ),
        "annual_risk_adjusted_wins": sum(
            economic_common.risk_adjusted_score(folds[year])
            > economic_common.risk_adjusted_score(baseline["folds"][year])
            for year in FOLDS
        ),
        "top_year_positive_delta_contribution_pct": str(
            concentration.quantize(PCT_Q, rounding=ROUND_HALF_UP)
        ),
    }


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    output = state_output_path(output_path)
    manifest, manifest_raw = load_manifest(manifest_path)
    bars = load_selection(input_path, manifest)
    baseline = economic_common.parent_baseline(bars)
    variants = [
        variant_evidence(bars, baseline, variant) for variant in manifest["variants"]
    ]
    primary = next(item for item in variants if item["role"] == "primary")
    primary_checks = economic_common.primary_gates(primary, baseline)
    neighbor_checks = {
        item["variant_id"]: economic_common.neighbor_gates(item, baseline)
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
            else "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_NATIVE_TREND_ACCELERATION_FAMILY"
        ),
        "recommended_next_action": (
            "FREEZE_SEPARATE_INDEPENDENT_OOS_CONTRACT_WITHOUT_ACTIVATION"
            if passed
            else "CLOSE_FAMILY_WITHOUT_THRESHOLD_EMA_WINDOW_ATR_DIRECTION_COOLDOWN_ROUTE_OR_GATE_TUNING"
        ),
        "manifest_sha256": sha256(manifest_raw),
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256(Path(__file__).resolve()),
        "support_runner_sha256": sha256(Path(support.__file__).resolve()),
        "economic_gate_helper_sha256": sha256(Path(economic_common.__file__).resolve()),
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
    except (
        ScreenReject,
        support.SupportReject,
        economic_common.ScreenReject,
        base.ResearchReject,
        OSError,
        ValueError,
    ) as error:
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
