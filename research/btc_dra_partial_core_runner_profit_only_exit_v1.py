#!/usr/bin/env python3
"""Offline DRA partial-core/residual-runner profit-only exit screen."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import sys


RESEARCH_DIR = Path(__file__).resolve().parent
if str(RESEARCH_DIR) not in sys.path:
    sys.path.insert(0, str(RESEARCH_DIR))

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
ZERO = D("0")
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
RESEARCH_IDENTITY = "BTC_DRA_PARTIAL_CORE_RESIDUAL_RUNNER_PROFIT_ONLY_EXIT_V1_RESEARCH"
RESULT_TYPE = "BTC_DRA_PARTIAL_CORE_RESIDUAL_RUNNER_PROFIT_ONLY_EXIT_V1_RESULT"
ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-partial-core-runner-profit-only-exit-v1-research.md"
STRUCTURAL_SPEC_PATH = ROOT / "docs" / "btc-dra-sell-condition-structural-solution-v1-research.md"
STRUCTURAL_RESULT_PATH = ROOT / "docs" / "btc-dra-sell-condition-structural-solution-v1-result-2026-08-02.md"
EXPECTED_SPEC_SHA256 = "797c82c2a8a79e86c4eba483c7019679a6ff172ee6f928cbc7bbed43e3eeada8"
EXPECTED_STRUCTURAL_SPEC_SHA256 = "33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be"
EXPECTED_STRUCTURAL_RESULT_SHA256 = "6fea2c2c2f64a7458e73af3030c374a54a5c62b8fd27858f05ddf423ed5ae27d"
EXPECTED_BASE_RUNNER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
MIN_SUBLOT_COST = D("10")
VARIANTS = (
    ("lower_neighbor", "core-040-runner-060-v1", D("0.40")),
    ("primary", "core-050-runner-050-v1", D("0.50")),
    ("upper_neighbor", "core-060-runner-040-v1", D("0.60")),
)


def sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def write_once(path: Path, value: object) -> None:
    if path.exists():
        raise base.ResearchReject("ARTIFACT_REJECT", f"output already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(value))


def verify_bindings() -> dict[str, dict[str, str]]:
    bindings = {
        "specification": {
            "path": SPEC_PATH,
            "expected": EXPECTED_SPEC_SHA256,
        },
        "structural_specification": {
            "path": STRUCTURAL_SPEC_PATH,
            "expected": EXPECTED_STRUCTURAL_SPEC_SHA256,
        },
        "structural_result": {
            "path": STRUCTURAL_RESULT_PATH,
            "expected": EXPECTED_STRUCTURAL_RESULT_SHA256,
        },
        "base_runner": {
            "path": Path(base.__file__),
            "expected": EXPECTED_BASE_RUNNER_SHA256,
        },
    }
    verified: dict[str, dict[str, str]] = {}
    defects = []
    for name, binding in bindings.items():
        path = binding["path"]
        actual = sha256_path(path)
        if actual != binding["expected"]:
            defects.append(
                {
                    "binding": name,
                    "expected_sha256": binding["expected"],
                    "actual_sha256": actual,
                }
            )
        verified[name] = {
            "path": path.relative_to(ROOT).as_posix(),
            "sha256": actual,
        }
    if defects:
        raise base.ResearchReject("PREREGISTRATION_REJECT", defects)
    return verified


@dataclass
class SplitLot:
    parent_id: int
    path: str
    signal_time: datetime
    fill_time: datetime
    cost: D
    buy_price: D
    quantity: D
    entry_atr: D | None
    highest_close: D
    ratchet_stop: D | None = None
    exit_queued_at: datetime | None = None


class PartialCoreRunnerEngine(base.Engine):
    def __init__(self, core_fraction: D, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__("v2a", cap=cap)
        self.mode = "partial_core_runner_v1"
        self.core_fraction = core_fraction
        self.runner_fraction = base.ONE - core_fraction
        self.lots: list[SplitLot] = []
        self.next_parent_id = 1
        self.parent_fill_time: dict[int, datetime] = {}
        self.parent_signal_time: dict[int, datetime] = {}
        self.parent_final_hold_hours: list[float] = []
        self.capital_weighted_hold_hours: list[float] = []
        self.path_realized = {"CORE": ZERO, "RUNNER": ZERO}
        self.path_sell_proceeds = {"CORE": ZERO, "RUNNER": ZERO}
        self.path_sell_counts = {"CORE": 0, "RUNNER": 0}
        self.entry_fees_paid = ZERO
        self.sell_fees_paid = ZERO
        self.quantity_reconciliation_failures = 0
        self.minimum_allocated_cost_observed: D | None = None
        self.split_count = 0
        self.current_underwater_hours = 0
        self.maximum_underwater_duration_hours = 0
        self.total_underwater_hours = 0

    def _open_cost(self) -> D:
        return base.money(sum((lot.cost for lot in self.lots), ZERO))

    def _fill_buy(self, bar: base.Bar) -> None:
        if self.pending_signal is None:
            return
        price = base.adverse_buy(bar.open)
        entry_fee = base.money(base.LOT_COST * base.FEE)
        parent_quantity = base.quantity((base.LOT_COST - entry_fee) / price)
        core_quantity = base.quantity(parent_quantity * self.core_fraction)
        runner_quantity = parent_quantity - core_quantity
        core_cost = base.money(base.LOT_COST * self.core_fraction)
        runner_cost = base.LOT_COST - core_cost
        allocations = (
            ("CORE", core_cost, core_quantity),
            ("RUNNER", runner_cost, runner_quantity),
        )
        if (
            core_quantity <= ZERO
            or runner_quantity <= ZERO
            or core_quantity + runner_quantity != parent_quantity
            or core_cost + runner_cost != base.LOT_COST
            or min(core_cost, runner_cost) < MIN_SUBLOT_COST
        ):
            self.quantity_reconciliation_failures += 1
            raise base.ResearchReject(
                "ACCOUNTING_REJECT",
                {
                    "core_fraction": str(self.core_fraction),
                    "parent_quantity": str(parent_quantity),
                    "core_quantity": str(core_quantity),
                    "runner_quantity": str(runner_quantity),
                    "core_cost": str(core_cost),
                    "runner_cost": str(runner_cost),
                },
            )
        parent_id = self.next_parent_id
        self.next_parent_id += 1
        self.parent_fill_time[parent_id] = bar.open_time
        self.parent_signal_time[parent_id] = self.pending_signal
        for path, cost, quantity in allocations:
            self.lots.append(
                SplitLot(
                    parent_id=parent_id,
                    path=path,
                    signal_time=self.pending_signal,
                    fill_time=bar.open_time,
                    cost=cost,
                    buy_price=price,
                    quantity=quantity,
                    entry_atr=self.pending_atr,
                    highest_close=bar.close,
                )
            )
            self.minimum_allocated_cost_observed = (
                cost
                if self.minimum_allocated_cost_observed is None
                else min(self.minimum_allocated_cost_observed, cost)
            )
        self.entry_fees_paid += entry_fee
        self.split_count += 1
        self.buy_count += 1
        self.pending_signal = None
        self.pending_atr = None

    def _fill_exits(self, bar: base.Bar) -> None:
        survivors: list[SplitLot] = []
        exited_parents: set[int] = set()
        for lot in self.lots:
            if lot.exit_queued_at is None:
                survivors.append(lot)
                continue
            sell_price = base.adverse_sell(bar.open)
            gross = base.money(lot.quantity * sell_price)
            fee = base.money(gross * base.FEE)
            net = gross - fee
            pnl = base.money(net - lot.cost)
            if pnl <= ZERO:
                lot.exit_queued_at = None
                self.deferred_count += 1
                survivors.append(lot)
                continue
            hold_hours = (bar.open_time - lot.fill_time).total_seconds() / 3600
            self.realized += pnl
            self.total_sell_proceeds += net
            self.sell_fees_paid += fee
            self.sell_count += 1
            self.hold_hours.append(hold_hours)
            self.capital_weighted_hold_hours.extend(
                [hold_hours] * int(lot.cost)
            )
            self.path_realized[lot.path] += pnl
            self.path_sell_proceeds[lot.path] += net
            self.path_sell_counts[lot.path] += 1
            exited_parents.add(lot.parent_id)
        self.lots = survivors
        open_parent_ids = {lot.parent_id for lot in self.lots}
        for parent_id in sorted(exited_parents - open_parent_ids):
            self.parent_final_hold_hours.append(
                (bar.open_time - self.parent_fill_time[parent_id]).total_seconds()
                / 3600
            )

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            if lot.path == "CORE":
                if (
                    lot.entry_atr is not None
                    and pnl >= lot.entry_atr * lot.quantity
                ):
                    lot.exit_queued_at = bar.open_time
                    self._count_trigger("CORE_ENTRY_ATR_TARGET_1_00")
                continue
            if self.atr14 is None:
                continue
            lot.highest_close = max(lot.highest_close, bar.close)
            candidate_stop = lot.highest_close - self.atr14 * base.V2A_MULTIPLIER
            lot.ratchet_stop = (
                candidate_stop
                if lot.ratchet_stop is None
                else max(lot.ratchet_stop, candidate_stop)
            )
            if bar.close <= lot.ratchet_stop and pnl > ZERO:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("RUNNER_ATR_TRAIL_1_50")

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            if self._open_cost() + base.LOT_COST > self.cap:
                self.blocked_count += 1
            else:
                self.pending_signal = bar.open_time
                self.pending_atr = self.atr14
                self.last_entry_signal = bar.open_time
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def _track(self, bar: base.Bar) -> None:
        open_cost = self._open_cost()
        unrealized = base.money(
            sum(
                (
                    base.estimated_net(lot.quantity, bar.close) - lot.cost
                    for lot in self.lots
                ),
                ZERO,
            )
        )
        equity = base.money(self.cap + self.realized + unrealized)
        if equity >= self.peak_equity:
            self.peak_equity = equity
            self.current_underwater_hours = 0
        else:
            self.current_underwater_hours += 1
            self.total_underwater_hours += 1
            self.maximum_underwater_duration_hours = max(
                self.maximum_underwater_duration_hours,
                self.current_underwater_hours,
            )
        drawdown = (
            (self.peak_equity - equity) / self.peak_equity
            if self.peak_equity > ZERO
            else ZERO
        )
        self.max_drawdown = max(self.max_drawdown, drawdown)
        self.max_open_cost = max(self.max_open_cost, open_cost)
        self.utilization_sum += open_cost / self.cap
        self.utilization_points += 1

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        unrealized_by_path = {"CORE": ZERO, "RUNNER": ZERO}
        terminal_inventory = []
        for lot in self.lots:
            lot_unrealized = base.money(
                base.estimated_net(lot.quantity, final_bar.close) - lot.cost
            )
            unrealized_by_path[lot.path] += lot_unrealized
            terminal_inventory.append(
                {
                    "parent_id": lot.parent_id,
                    "path": lot.path,
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "age_hours": (end - lot.fill_time).total_seconds() / 3600,
                    "allocated_cost_usdt": str(lot.cost),
                    "quantity_btc": str(lot.quantity),
                    "unrealized_pnl_usdt": str(lot_unrealized),
                }
            )
        realized = base.money(self.realized)
        unrealized = base.money(sum(unrealized_by_path.values(), ZERO))
        open_parent_ids = sorted({lot.parent_id for lot in self.lots})
        open_parent_ages = [
            (end - self.parent_fill_time[parent_id]).total_seconds() / 3600
            for parent_id in open_parent_ids
        ]
        utilization = self.utilization_sum / D(self.utilization_points)
        return {
            "authorization": AUTHORIZATION,
            "mode": self.mode,
            "core_fraction": str(self.core_fraction),
            "runner_fraction": str(self.runner_fraction),
            "start": start.isoformat(),
            "end_exclusive": end.isoformat(),
            "reference_cap_usdt": str(base.money(self.cap)),
            "realized_usdt": str(realized),
            "unrealized_usdt": str(unrealized),
            "total_pnl_usdt": str(base.money(realized + unrealized)),
            "max_drawdown_pct": str(
                (self.max_drawdown * D("100")).quantize(
                    D("0.000001"), rounding=ROUND_HALF_UP
                )
            ),
            "maximum_underwater_duration_hours": self.maximum_underwater_duration_hours,
            "total_underwater_hours": self.total_underwater_hours,
            "parent_buy_count": self.buy_count,
            "sublot_sell_count": self.sell_count,
            "open_sublots": len(self.lots),
            "open_parent_count": len(open_parent_ids),
            "blocked_entries": self.blocked_count,
            "deferred_exits": self.deferred_count,
            "ending_open_cost_usdt": str(self._open_cost()),
            "max_open_cost_usdt": str(base.money(self.max_open_cost)),
            "avg_utilization_pct": str(
                (utilization * D("100")).quantize(
                    D("0.000001"), rounding=ROUND_HALF_UP
                )
            ),
            "peak_utilization_pct": str(
                ((self.max_open_cost / self.cap) * D("100")).quantize(
                    D("0.000001"), rounding=ROUND_HALF_UP
                )
            ),
            "capital_weighted_median_hold_hours": base.percentile(
                self.capital_weighted_hold_hours, 0.5
            ),
            "capital_weighted_p90_hold_hours": base.percentile(
                self.capital_weighted_hold_hours, 0.9
            ),
            "final_parent_median_hold_hours": base.percentile(
                self.parent_final_hold_hours, 0.5
            ),
            "final_parent_p90_hold_hours": base.percentile(
                self.parent_final_hold_hours, 0.9
            ),
            "median_open_parent_age_hours": base.percentile(open_parent_ages, 0.5),
            "p90_open_parent_age_hours": base.percentile(open_parent_ages, 0.9),
            "turnover_usdt": str(base.money(self.total_sell_proceeds)),
            "entry_fees_paid_usdt": str(base.money(self.entry_fees_paid)),
            "sell_fees_paid_usdt": str(base.money(self.sell_fees_paid)),
            "path_accounting": {
                path: {
                    "realized_usdt": str(base.money(self.path_realized[path])),
                    "unrealized_usdt": str(base.money(unrealized_by_path[path])),
                    "sell_proceeds_usdt": str(
                        base.money(self.path_sell_proceeds[path])
                    ),
                    "sell_count": self.path_sell_counts[path],
                    "ending_open_cost_usdt": str(
                        base.money(
                            sum(
                                (lot.cost for lot in self.lots if lot.path == path),
                                ZERO,
                            )
                        )
                    ),
                }
                for path in ("CORE", "RUNNER")
            },
            "split_feasibility": {
                "split_count": self.split_count,
                "quantity_reconciliation_failures": self.quantity_reconciliation_failures,
                "minimum_allocated_cost_usdt": (
                    str(self.minimum_allocated_cost_observed)
                    if self.minimum_allocated_cost_observed is not None
                    else None
                ),
                "minimum_required_allocated_cost_usdt": str(MIN_SUBLOT_COST),
                "pass": (
                    self.quantity_reconciliation_failures == 0
                    and self.minimum_allocated_cost_observed is not None
                    and self.minimum_allocated_cost_observed >= MIN_SUBLOT_COST
                ),
            },
            "exit_trigger_counts": self.exit_trigger_counts,
            "terminal_inventory": terminal_inventory,
        }


def simulate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    core_fraction: D,
) -> dict:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar
        for bar in bars
        if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject(
            "DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}"
        )
    engine = PartialCoreRunnerEngine(core_fraction)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def load_selection(path: Path) -> tuple[list[base.Bar], str]:
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    digest = base.data_hash(bars)
    if (
        len(bars) != base.SELECTION_ROWS
        or digest != base.SELECTION_SHA256
        or bars[-1].close_time > base.SELECTION_CUTOFF
    ):
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": base.SELECTION_ROWS,
                "actual_rows": len(bars),
                "expected_sha256": base.SELECTION_SHA256,
                "actual_sha256": digest,
                "last_close": bars[-1].close_time.isoformat() if bars else None,
            },
        )
    return bars, digest


def baseline_checkpoints(bars: list[base.Bar]) -> dict[str, dict]:
    v1_design = base.simulate(bars, base.DESIGN, "v1")
    v1_validation = base.simulate(bars, base.VALIDATION, "v1")
    v2a_validation = base.simulate(bars, base.VALIDATION, "v2a")
    actual = {
        "v1_design": base.checkpoint_tuple(v1_design),
        "v1_validation": base.checkpoint_tuple(v1_validation),
        "v2a_validation": base.checkpoint_tuple(v2a_validation),
    }
    defects = [
        {"checkpoint": name, "expected": base.EXPECTED[name], "actual": value}
        for name, value in actual.items()
        if value != base.EXPECTED[name]
    ]
    if defects:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", defects)
    return {
        "v1": {
            "design": v1_design,
            "validation": v1_validation,
            "folds": {
                name: base.simulate(bars, window, "v1")
                for name, window in base.FOLDS.items()
            },
        },
        "v2a": {"validation": v2a_validation},
    }


def variant_evidence(
    bars: list[base.Bar],
    role: str,
    variant_id: str,
    core_fraction: D,
    v1_folds: dict[str, dict],
) -> dict:
    design = simulate(bars, base.DESIGN, core_fraction)
    validation = simulate(bars, base.VALIDATION, core_fraction)
    folds = {
        name: simulate(bars, window, core_fraction)
        for name, window in base.FOLDS.items()
    }
    total_wins = sum(
        D(folds[name]["total_pnl_usdt"]) > D(v1_folds[name]["total_pnl_usdt"])
        for name in base.FOLDS
    )
    hold_wins = sum(
        folds[name]["capital_weighted_median_hold_hours"] is not None
        and v1_folds[name]["median_hold_hours"] is not None
        and folds[name]["capital_weighted_median_hold_hours"]
        < v1_folds[name]["median_hold_hours"]
        for name in base.FOLDS
    )
    return {
        "role": role,
        "variant_id": variant_id,
        "core_fraction": str(core_fraction),
        "runner_fraction": str(base.ONE - core_fraction),
        "design": design,
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_capital_weighted_median_hold_wins": hold_wins,
    }


def primary_gates(primary: dict, baselines: dict, neighbor_stability: bool) -> dict[str, bool]:
    design = primary["design"]
    validation = primary["validation"]
    v1_design = baselines["v1"]["design"]
    v1_validation = baselines["v1"]["validation"]
    v2a_validation = baselines["v2a"]["validation"]
    return {
        "design_total_at_least_v1": D(design["total_pnl_usdt"]) >= D(v1_design["total_pnl_usdt"]),
        "design_realized_at_least_v1": D(design["realized_usdt"]) >= D(v1_design["realized_usdt"]),
        "design_drawdown_within_v1_plus_2pp": D(design["max_drawdown_pct"]) <= D(v1_design["max_drawdown_pct"]) + D("2"),
        "validation_total_at_least_v1": D(validation["total_pnl_usdt"]) >= D(v1_validation["total_pnl_usdt"]),
        "validation_realized_at_least_v1": D(validation["realized_usdt"]) >= D(v1_validation["realized_usdt"]),
        "validation_unrealized_no_worse": D(validation["unrealized_usdt"]) >= D(v1_validation["unrealized_usdt"]),
        "validation_drawdown_within_v1_plus_2pp": D(validation["max_drawdown_pct"]) <= D(v1_validation["max_drawdown_pct"]) + D("2"),
        "validation_capital_weighted_median_hold_no_worse": validation["capital_weighted_median_hold_hours"] is not None and validation["capital_weighted_median_hold_hours"] <= v1_validation["median_hold_hours"],
        "validation_capital_weighted_p90_hold_no_worse": validation["capital_weighted_p90_hold_hours"] is not None and validation["capital_weighted_p90_hold_hours"] <= v1_validation["p90_hold_hours"],
        "validation_final_parent_median_no_worse_than_v2a": validation["final_parent_median_hold_hours"] is not None and validation["final_parent_median_hold_hours"] <= v2a_validation["median_hold_hours"],
        "validation_final_parent_p90_no_worse_than_v2a": validation["final_parent_p90_hold_hours"] is not None and validation["final_parent_p90_hold_hours"] <= v2a_validation["p90_hold_hours"],
        "validation_open_cost_no_worse": D(validation["ending_open_cost_usdt"]) <= D(v1_validation["ending_open_cost_usdt"]),
        "validation_open_parent_count_no_worse": validation["open_parent_count"] <= v1_validation["open_lots"],
        "annual_total_wins_at_least_3_of_5": primary["annual_total_wins"] >= 3,
        "annual_capital_weighted_median_hold_wins_at_least_3_of_5": primary["annual_capital_weighted_median_hold_wins"] >= 3,
        "split_quantity_and_minimum_cost_feasibility": design["split_feasibility"]["pass"] and validation["split_feasibility"]["pass"],
        "at_least_one_neighbor_stable": neighbor_stability,
    }


def neighbor_stable(variant: dict, v1_validation: dict) -> bool:
    validation = variant["validation"]
    return all(
        (
            D(validation["total_pnl_usdt"]) >= D(v1_validation["total_pnl_usdt"]) * D("0.95"),
            D(validation["realized_usdt"]) >= D(v1_validation["realized_usdt"]) * D("0.95"),
            D(validation["max_drawdown_pct"]) <= D(v1_validation["max_drawdown_pct"]) + D("2"),
            validation["split_feasibility"]["pass"],
        )
    )


def run_preselect(input_path: Path) -> dict:
    bindings = verify_bindings()
    bars, data_sha = load_selection(input_path)
    baselines = baseline_checkpoints(bars)
    variants = [
        variant_evidence(
            bars,
            role,
            variant_id,
            core_fraction,
            baselines["v1"]["folds"],
        )
        for role, variant_id, core_fraction in VARIANTS
    ]
    primary = next(variant for variant in variants if variant["role"] == "primary")
    neighbors = [variant for variant in variants if variant["role"] != "primary"]
    stable_neighbors = [
        variant["variant_id"]
        for variant in neighbors
        if neighbor_stable(variant, baselines["v1"]["validation"])
    ]
    gates = primary_gates(primary, baselines, bool(stable_neighbors))
    passed = all(gates.values())
    runner_sha = sha256_path(Path(__file__))
    result = {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "research_identity": RESEARCH_IDENTITY,
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_PARTIAL_CORE_RUNNER_EXIT_FAMILY"
        ),
        "selection_data": {
            "path": input_path.relative_to(ROOT).as_posix(),
            "rows": len(bars),
            "sha256": data_sha,
            "selection_cutoff": base.SELECTION_CUTOFF.isoformat(),
        },
        "bindings": bindings,
        "runner": {
            "path": Path(__file__).relative_to(ROOT).as_posix(),
            "sha256": runner_sha,
        },
        "baseline_parity": "PASS_V1_DESIGN_VALIDATION_AND_V2A_VALIDATION",
        "baselines": baselines,
        "variants": variants,
        "primary_variant_id": primary["variant_id"],
        "primary_gates": gates,
        "stable_neighbor_variant_ids": stable_neighbors,
        "candidate_created": passed,
        "oos_opened": False,
        "recommended_next_action": (
            "FREEZE_PRIMARY_THEN_OPEN_ONE_INDEPENDENT_OOS"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_PARTIAL_CORE_RUNNER_EXIT_FAMILY_WITHOUT_SPLIT_TRIGGER_OR_GATE_TUNING"
        ),
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_preselect(args.input.resolve())
        write_once(args.output.resolve(), result)
    except base.ResearchReject as reject:
        result = {
            "schema_version": "1",
            "document_type": RESULT_TYPE,
            "authorization": AUTHORIZATION,
            "research_identity": RESEARCH_IDENTITY,
            "status": reject.status,
            "detail": reject.detail,
            "candidate_created": False,
            "oos_opened": False,
        }
    print(
        json.dumps(
            {
                key: value
                for key, value in result.items()
                if key not in {"baselines", "variants"}
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if result.get("status") == "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
