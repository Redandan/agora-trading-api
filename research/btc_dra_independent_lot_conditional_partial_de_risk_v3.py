#!/usr/bin/env python3
"""Causal DRA conditional 20/10 partial de-risk research with sealed OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_independent_lot_hybrid_profit_lock_exit_v2 as hybrid

base = hybrid.base
D = Decimal
ZERO = D("0")
ONE = D("1")
TWO = D("2")
THREE = D("3")
ORIGINAL_COST = D("30.00")
PARTIAL_COST = D("20.00")
REMAINDER_COST = D("10.00")
HOURLY_EMA5_ALPHA = D("0.3333333333333333")

RESEARCH_IDENTITY = "BTC_DRA_INDEPENDENT_LOT_CONDITIONAL_PARTIAL_DE_RISK_V3_RESEARCH"
CANDIDATES = (
    "ENTRY_7D_MOMENTUM_ACCELERATION_FULL_V2A_ELSE_PARTIAL_20_10",
    "ENTRY_DAILY_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_20_10",
    "ENTRY_ACCELERATION_OR_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_20_10",
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-conditional-partial-de-risk-v3-research.md"
HYBRID_SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-hybrid-profit-lock-exit-v2-research.md"
EXPECTED_SPEC_SHA256 = "b5276fc2666a56fb0c26cc79880fa8681684857751c7c3da7977b4f2a32a8c5f"
EXPECTED_HYBRID_SPEC_SHA256 = "755c1b15e36bdd86ab70aa3f456fccafd5a711ff3fdfe34330b50ef509bf27e4"
EXPECTED_HYBRID_RUNNER_SHA256 = "6e4e43c29765a5d487214a0981bd802b134d9904f0ac558948552908940d8673"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_HYBRID_DESIGN = (
    "200.85777417", "14.89205948", "215.74983365", "51.118356", 233.0,
    1399.2, 99, 93, 6, 6, "47.264545", "2990.85777417",
)
EXPECTED_HYBRID_VALIDATION = (
    "74.18950294", "-3.20820121", "70.98130173", "6.999583", 192.0,
    854.4, 51, 50, 1, 0, "16.873461", "1574.18950294",
)
EXPECTED_HYBRID_WINS = (3, 2)


@dataclass(frozen=True)
class DailyRecord:
    day: datetime
    close: D
    high: D
    low: D
    atr14: D | None
    true_range: D


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def weighted_percentile_by_cost(records: list[tuple[float, D]], p: float) -> float | None:
    """Expand whole-USDT allocated cost so equal 30-USDT lots match V1 exactly."""
    expanded: list[float] = []
    for hours, allocated_cost in records:
        units = int(allocated_cost)
        if allocated_cost != D(units) or units <= 0:
            raise base.ResearchReject(
                "ACCOUNTING_REJECT",
                f"non-whole or non-positive allocated cost weight {allocated_cost}",
            )
        expanded.extend([hours] * units)
    return base.percentile(expanded, p)


class ConditionalPartialEngine(base.Engine):
    """Route full V2A or a causal 20/10 partial de-risk path per entry."""

    def __init__(self, candidate: str, *, cap: D = base.REFERENCE_CAP) -> None:
        if candidate not in CANDIDATES:
            raise ValueError(candidate)
        super().__init__("conditional_partial_v3", factor=candidate, cap=cap)
        self.candidate = candidate
        self.daily_records: list[DailyRecord] = []
        self.hourly_ema5: D | None = None
        self.lot_state: dict[datetime, dict] = {}
        self.queue_kind_by_fill: dict[datetime, str] = {}
        self.entry_route_records: list[dict] = []
        self.partial_queue_records: list[dict] = []
        self.exit_fill_records: list[dict] = []
        self.deferred_fill_records: list[dict] = []
        self.cost_weighted_holds: list[tuple[float, D]] = []
        self.exit_slice_holds: list[float] = []
        self.first_realization_holds: list[float] = []
        self.final_completion_holds: list[float] = []
        self.partial_fill_count = 0
        self.direct_full_v2a_fill_count = 0
        self.remainder_v2a_fill_count = 0
        self.original_lot_completion_count = 0
        self.entry_route_missing_count = 0
        self.partial_condition_violations = 0
        self.nonpositive_fill_violations = 0
        self.multiple_partial_violations = 0

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        self.hourly_ema5 = (
            base.money(bar.close)
            if self.hourly_ema5 is None
            else base.money(
                bar.close * HOURLY_EMA5_ALPHA
                + self.hourly_ema5 * (ONE - HOURLY_EMA5_ALPHA)
            )
        )
        if bar.open_time.hour != 23:
            return
        previous_close = self.daily_records[-1].close if self.daily_records else None
        true_range = self.daily_high - self.daily_low
        if previous_close is not None:
            true_range = max(
                true_range,
                abs(self.daily_high - previous_close),
                abs(self.daily_low - previous_close),
            )
        self.daily_records.append(
            DailyRecord(
                day=bar.open_time,
                close=bar.close,
                high=self.daily_high,
                low=self.daily_low,
                atr14=self.atr14,
                true_range=true_range,
            )
        )

    def _entry_route(self) -> tuple[str, dict]:
        if len(self.daily_records) < 15:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_INSUFFICIENT_14_COMPLETE_DAYS",
                "inception_fallback": True,
            }
        current = self.daily_records[-1]
        close_t7 = self.daily_records[-8].close
        close_t14 = self.daily_records[-15].close
        recent = current.close / close_t7 - ONE
        previous = close_t7 / close_t14 - ONE
        acceleration = recent > ZERO and recent > previous
        prior_atr = self.daily_records[-2].atr14
        if prior_atr is None:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_PRIOR_ATR14_UNAVAILABLE",
                "inception_fallback": True,
            }
        range_expansion = current.true_range > prior_atr
        if self.candidate == CANDIDATES[0]:
            strong = acceleration
        elif self.candidate == CANDIDATES[1]:
            strong = range_expansion
        else:
            strong = acceleration or range_expansion
        values = {
            "signal_day": current.day.isoformat(),
            "recent_7d_return": str(recent),
            "prior_7d_return": str(previous),
            "momentum_acceleration_pass": acceleration,
            "signal_day_true_range": str(current.true_range),
            "prior_complete_day_atr14": str(prior_atr),
            "range_expansion_pass": range_expansion,
        }
        return ("FULL_V2A" if strong else "PARTIAL_ELIGIBLE"), values

    def _fill_buy(self, bar: base.Bar) -> None:
        if self.pending_signal is None:
            return
        pending_signal = self.pending_signal
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) != before + 1:
            raise base.ResearchReject("ACCOUNTING_REJECT", "buy fill count drift")
        lot = self.lots[-1]
        route, values = self._entry_route()
        if lot.entry_atr is None and route != "FULL_V2A":
            raise base.ResearchReject("CANDIDATE_REJECT", "partial route missing entry ATR")
        self.lot_state[lot.fill_time] = {
            "route": route,
            "original_quantity": lot.quantity,
            "original_cost": ORIGINAL_COST,
            "entry_risk_1r": None if lot.entry_atr is None else lot.entry_atr * lot.quantity,
            "peak_full_net_pnl": None,
            "armed": False,
            "armed_at": None,
            "partial_queued": False,
            "partial_done": False,
            "partial_fill_count": 0,
            "sold_quantity": ZERO,
            "allocated_cost_exited": ZERO,
            "realized_pnl": ZERO,
            "first_realization_at": None,
            "completed_at": None,
        }
        self.entry_route_records.append(
            {
                "candidate": self.candidate,
                "signal_time": pending_signal.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "route": route,
                "entry_atr14": str(lot.entry_atr),
                **values,
            }
        )

    def _fill_exits(self, bar: base.Bar) -> None:
        survivors: list[base.Lot] = []
        for lot in self.lots:
            if lot.exit_queued_at is None:
                survivors.append(lot)
                continue
            state = self.lot_state[lot.fill_time]
            kind = self.queue_kind_by_fill.get(lot.fill_time)
            if kind not in ("PARTIAL_20", "FULL_V2A"):
                raise base.ResearchReject("ACCOUNTING_REJECT", f"missing queue kind {kind}")
            if kind == "PARTIAL_20":
                if state["partial_done"]:
                    self.multiple_partial_violations += 1
                sell_quantity = base.quantity(state["original_quantity"] * TWO / THREE)
                allocated_cost = PARTIAL_COST
            else:
                sell_quantity = lot.quantity
                allocated_cost = lot.cost
            net = base.estimated_net(sell_quantity, bar.open)
            pnl = base.money(net - allocated_cost)
            record = {
                "fill_time": lot.fill_time.isoformat(),
                "exit_time": bar.open_time.isoformat(),
                "kind": kind,
                "route": state["route"],
                "quantity": str(sell_quantity),
                "allocated_cost_usdt": str(base.money(allocated_cost)),
                "net_proceeds_usdt": str(net),
                "realized_net_pnl_usdt": str(pnl),
            }
            if pnl <= ZERO:
                lot.exit_queued_at = None
                self.queue_kind_by_fill.pop(lot.fill_time, None)
                self.deferred_count += 1
                record["decision"] = "DEFERRED_NOT_STRICTLY_NET_POSITIVE"
                self.deferred_fill_records.append(record)
                survivors.append(lot)
                continue

            age = (bar.open_time - lot.fill_time).total_seconds() / 3600
            self.realized += pnl
            self.total_sell_proceeds += net
            self.sell_count += 1
            self.hold_hours.append(age)
            self.exit_slice_holds.append(age)
            self.cost_weighted_holds.append((age, allocated_cost))
            state["sold_quantity"] += sell_quantity
            state["allocated_cost_exited"] += allocated_cost
            state["realized_pnl"] += pnl
            if state["first_realization_at"] is None:
                state["first_realization_at"] = bar.open_time
                self.first_realization_holds.append(age)
            record["decision"] = "STRICTLY_NET_POSITIVE_FILL"
            self.exit_fill_records.append(record)
            self.queue_kind_by_fill.pop(lot.fill_time, None)

            if kind == "PARTIAL_20":
                state["partial_done"] = True
                state["partial_fill_count"] += 1
                self.partial_fill_count += 1
                remainder_quantity = state["original_quantity"] - sell_quantity
                lot.quantity = remainder_quantity
                lot.cost = REMAINDER_COST
                lot.highest_close = bar.open
                lot.ratchet_stop = None
                lot.exit_queued_at = None
                survivors.append(lot)
            else:
                if state["partial_done"]:
                    self.remainder_v2a_fill_count += 1
                else:
                    self.direct_full_v2a_fill_count += 1
                state["completed_at"] = bar.open_time
                self.original_lot_completion_count += 1
                self.final_completion_holds.append(age)
        self.lots = survivors

    def _queue_full_v2a(self, lot: base.Lot, bar: base.Bar) -> None:
        if self.atr14 is None:
            return
        lot.highest_close = max(lot.highest_close, bar.close)
        candidate_stop = lot.highest_close - self.atr14 * base.V2A_MULTIPLIER
        lot.ratchet_stop = (
            candidate_stop
            if lot.ratchet_stop is None
            else max(lot.ratchet_stop, candidate_stop)
        )
        pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
        if bar.close <= lot.ratchet_stop and pnl > ZERO:
            lot.exit_queued_at = bar.open_time
            self.queue_kind_by_fill[lot.fill_time] = "FULL_V2A"
            self._count_trigger("FULL_OR_REMAINDER_ATR_TRAIL_1_50")

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            if state["route"] == "FULL_V2A" or state["partial_done"]:
                self._queue_full_v2a(lot, bar)
                continue

            current_full_pnl = base.money(
                base.estimated_net(lot.quantity, bar.close) - ORIGINAL_COST
            )
            previous_peak = state["peak_full_net_pnl"]
            peak = current_full_pnl if previous_peak is None else max(previous_peak, current_full_pnl)
            state["peak_full_net_pnl"] = peak
            if not state["armed"] and peak >= state["entry_risk_1r"]:
                state["armed"] = True
                state["armed_at"] = bar.open_time
            if not state["armed"] or self.hourly_ema5 is None or bar.close >= self.hourly_ema5:
                continue
            partial_quantity = base.quantity(state["original_quantity"] * TWO / THREE)
            partial_pnl = base.money(
                base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
            )
            condition_pass = (
                peak >= state["entry_risk_1r"]
                and bar.close < self.hourly_ema5
                and partial_pnl > ZERO
                and not state["partial_done"]
            )
            if not condition_pass:
                if partial_pnl > ZERO:
                    self.partial_condition_violations += 1
                continue
            lot.exit_queued_at = bar.open_time
            self.queue_kind_by_fill[lot.fill_time] = "PARTIAL_20"
            state["partial_queued"] = True
            self._count_trigger("ARMED_1R_HOURLY_CLOSE_BELOW_EMA5_PARTIAL_20")
            self.partial_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "entry_risk_1r_usdt": str(base.money(state["entry_risk_1r"])),
                    "peak_full_net_pnl_usdt": str(peak),
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "estimated_partial_net_pnl_usdt": str(partial_pnl),
                    "condition_pass": condition_pass,
                }
            )

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            open_cost = sum((lot.cost for lot in self.lots), ZERO)
            if open_cost + base.LOT_COST > self.cap:
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
        open_cost = sum((lot.cost for lot in self.lots), ZERO)
        unrealized = base.money(
            sum(
                (base.estimated_net(lot.quantity, bar.close) - lot.cost for lot in self.lots),
                ZERO,
            )
        )
        equity = base.money(self.cap + self.realized + unrealized)
        self.peak_equity = max(self.peak_equity, equity)
        drawdown = (self.peak_equity - equity) / self.peak_equity if self.peak_equity > ZERO else ZERO
        self.max_drawdown = max(self.max_drawdown, drawdown)
        self.max_open_cost = max(self.max_open_cost, open_cost)
        self.utilization_sum += open_cost / self.cap
        self.utilization_points += 1

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        unrealized = base.money(
            sum(
                (base.estimated_net(lot.quantity, final_bar.close) - lot.cost for lot in self.lots),
                ZERO,
            )
        )
        realized = base.money(self.realized)
        open_cost = base.money(sum((lot.cost for lot in self.lots), ZERO))
        utilization = self.utilization_sum / D(self.utilization_points)
        open_ages = sorted((end - lot.fill_time).total_seconds() / 3600 for lot in self.lots)
        current_by_fill = {lot.fill_time: lot for lot in self.lots}
        cost_reconciles = True
        quantity_reconciles = True
        multiple_partial = False
        for fill_time, state in self.lot_state.items():
            remaining_cost = current_by_fill[fill_time].cost if fill_time in current_by_fill else ZERO
            remaining_quantity = current_by_fill[fill_time].quantity if fill_time in current_by_fill else ZERO
            cost_reconciles &= state["allocated_cost_exited"] + remaining_cost == ORIGINAL_COST
            quantity_reconciles &= state["sold_quantity"] + remaining_quantity == state["original_quantity"]
            multiple_partial |= state["partial_fill_count"] > 1
        if multiple_partial:
            self.multiple_partial_violations += 1
        route_counts = {
            route: sum(state["route"] == route for state in self.lot_state.values())
            for route in ("FULL_V2A", "PARTIAL_ELIGIBLE")
        }
        route_realized = {
            route: str(base.money(sum(
                (state["realized_pnl"] for state in self.lot_state.values() if state["route"] == route),
                ZERO,
            )))
            for route in route_counts
        }
        route_unrealized = {route: ZERO for route in route_counts}
        for lot in self.lots:
            route = self.lot_state[lot.fill_time]["route"]
            route_unrealized[route] += base.estimated_net(lot.quantity, final_bar.close) - lot.cost
        route_total = {
            route: str(base.money(D(route_realized[route]) + route_unrealized[route]))
            for route in route_counts
        }
        primary_median = weighted_percentile_by_cost(self.cost_weighted_holds, 0.5)
        primary_p90 = weighted_percentile_by_cost(self.cost_weighted_holds, 0.9)
        audit = {
            "entry_route_records": self.entry_route_records,
            "entry_route_counts": route_counts,
            "entry_route_missing_count": self.entry_route_missing_count,
            "partial_queue_count": len(self.partial_queue_records),
            "partial_fill_count": self.partial_fill_count,
            "direct_full_v2a_fill_count": self.direct_full_v2a_fill_count,
            "remainder_v2a_fill_count": self.remainder_v2a_fill_count,
            "original_lot_completion_count": self.original_lot_completion_count,
            "partial_condition_violations": self.partial_condition_violations,
            "nonpositive_fill_violations": self.nonpositive_fill_violations,
            "multiple_partial_violations": self.multiple_partial_violations,
            "cost_allocation_reconciles_pass": cost_reconciles,
            "quantity_conservation_pass": quantity_reconciles,
            "all_entry_routes_complete_pass": self.entry_route_missing_count == 0,
            "all_partial_conditions_pass": self.partial_condition_violations == 0,
            "all_exit_fills_strictly_net_positive_pass": all(
                D(row["realized_net_pnl_usdt"]) > ZERO for row in self.exit_fill_records
            ),
            "at_most_one_partial_fill_per_lot_pass": self.multiple_partial_violations == 0,
            "partial_queue_records": self.partial_queue_records,
            "exit_fill_records": self.exit_fill_records,
            "deferred_fill_records": self.deferred_fill_records,
            "route_realized_pnl_usdt": route_realized,
            "route_total_pnl_usdt": route_total,
            "unweighted_exit_slice_median_hold_hours": base.percentile(self.exit_slice_holds, 0.5),
            "unweighted_exit_slice_p90_hold_hours": base.percentile(self.exit_slice_holds, 0.9),
            "median_time_to_first_realization_hours": base.percentile(self.first_realization_holds, 0.5),
            "p90_time_to_first_realization_hours": base.percentile(self.first_realization_holds, 0.9),
            "median_time_to_final_completion_hours": base.percentile(self.final_completion_holds, 0.5),
            "p90_time_to_final_completion_hours": base.percentile(self.final_completion_holds, 0.9),
            "capital_hold_hours_usdt_hours": str(sum((D(str(h)) * c for h, c in self.cost_weighted_holds), ZERO)),
        }
        return {
            "mode": "conditional_partial_v3",
            "candidate": self.candidate,
            "factor": self.candidate,
            "start": start.isoformat(),
            "end_exclusive": end.isoformat(),
            "reference_cap_usdt": str(base.money(self.cap)),
            "realized_usdt": str(realized),
            "unrealized_usdt": str(unrealized),
            "total_pnl_usdt": str(base.money(realized + unrealized)),
            "max_drawdown_pct": str((self.max_drawdown * D(100)).quantize(D("0.000001"))),
            "buy_count": self.buy_count,
            "sell_count": self.sell_count,
            "open_lots": len(self.lots),
            "blocked_entries": self.blocked_count,
            "deferred_exits": self.deferred_count,
            "ending_open_cost_usdt": str(open_cost),
            "max_open_cost_usdt": str(base.money(self.max_open_cost)),
            "avg_utilization_pct": str((utilization * D(100)).quantize(D("0.000001"))),
            "peak_utilization_pct": str(((self.max_open_cost / self.cap) * D(100)).quantize(D("0.000001"))),
            "median_hold_hours": primary_median,
            "p90_hold_hours": primary_p90,
            "median_open_age_hours": base.percentile(open_ages, 0.5),
            "p90_open_age_hours": base.percentile(open_ages, 0.9),
            "turnover_usdt": str(base.money(self.total_sell_proceeds)),
            "exit_trigger_counts": self.exit_trigger_counts,
            "conditional_partial_audit": audit,
        }


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "hybrid_specification_sha256": file_sha256(HYBRID_SPEC_PATH),
        "hybrid_dependency_sha256": file_sha256(Path(hybrid.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "hybrid_specification_sha256": EXPECTED_HYBRID_SPEC_SHA256,
        "hybrid_dependency_sha256": EXPECTED_HYBRID_RUNNER_SHA256,
    }
    problems = [
        {"artifact": key, "expected": expected[key], "actual": actual[key]}
        for key in expected
        if actual[key] != expected[key]
    ]
    if problems:
        raise base.ResearchReject("PREREGISTRATION_REJECT", problems)
    return actual


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    candidate: str,
    *,
    cap: D = base.REFERENCE_CAP,
) -> dict:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = ConditionalPartialEngine(candidate, cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = hybrid.reproduce_checkpoints(bars)
    design = hybrid.simulate_candidate(bars, DESIGN)
    validation = hybrid.simulate_candidate(bars, VALIDATION)
    folds = {name: hybrid.simulate_candidate(bars, window) for name, window in FOLDS.items()}
    total_wins = sum(
        base.dec(folds[name], "total_pnl_usdt")
        > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        folds[name]["median_hold_hours"] is not None
        and folds[name]["median_hold_hours"]
        < baselines["v1"]["folds"][name]["median_hold_hours"]
        for name in FOLDS
    )
    checks = {
        "hybrid_design": (base.checkpoint_tuple(design), EXPECTED_HYBRID_DESIGN),
        "hybrid_validation": (base.checkpoint_tuple(validation), EXPECTED_HYBRID_VALIDATION),
        "hybrid_annual_wins": ((total_wins, hold_wins), EXPECTED_HYBRID_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["hybrid_profit_lock"] = {
        "design": design,
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
    }
    return baselines


def candidate_gates(
    result: dict,
    v1: dict,
    v2a: dict,
    total_wins: int,
    hold_wins: int,
) -> dict[str, bool]:
    audit = result["conditional_partial_audit"]
    return {
        "validation_total_at_least_v1": base.dec(result, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "validation_total_retains_90pct_v2a": base.dec(result, "total_pnl_usdt") >= base.dec(v2a, "total_pnl_usdt") * D("0.90"),
        "validation_realized_at_least_v1": base.dec(result, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "validation_unrealized_no_worse": base.dec(result, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "validation_drawdown_at_most_9_121498pct": base.dec(result, "max_drawdown_pct") <= D("9.121498"),
        "validation_cost_weighted_median_at_most_182_5h": result["median_hold_hours"] is not None and D(str(result["median_hold_hours"])) <= D("182.5"),
        "validation_cost_weighted_p90_at_most_1418_3h": result["p90_hold_hours"] is not None and D(str(result["p90_hold_hours"])) <= D("1418.3"),
        "annual_total_wins_at_least_3_of_5": total_wins >= 3,
        "annual_cost_weighted_median_wins_at_least_3_of_5": hold_wins >= 3,
        "all_entry_routes_complete": audit["all_entry_routes_complete_pass"],
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def freeze_hash(data_sha: str, hashes: dict[str, str], runner_sha: str, candidate: str) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": candidate,
        "selection_data_sha256": data_sha,
        **hashes,
        "runner_sha256": runner_sha,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def run_preselect(output: Path) -> dict:
    artifact_hashes = verify_preregistration_artifacts()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {"expected_rows": SELECTION_ROWS, "actual_rows": len(bars), "expected_sha256": SELECTION_SHA256, "actual_sha256": digest},
        )
    baselines = reproduce_checkpoints(bars)
    candidates: list[dict] = []
    for candidate in CANDIDATES:
        design = simulate_candidate(bars, DESIGN, candidate)
        validation = simulate_candidate(bars, VALIDATION, candidate)
        folds = {name: simulate_candidate(bars, window, candidate) for name, window in FOLDS.items()}
        total_wins = sum(
            base.dec(folds[name], "total_pnl_usdt")
            > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
            for name in FOLDS
        )
        hold_wins = sum(
            folds[name]["median_hold_hours"] is not None
            and folds[name]["median_hold_hours"]
            < baselines["v1"]["folds"][name]["median_hold_hours"]
            for name in FOLDS
        )
        gates = candidate_gates(
            validation,
            baselines["v1"]["validation"],
            baselines["v2a"]["validation"],
            total_wins,
            hold_wins,
        )
        candidates.append(
            {
                "candidate": candidate,
                "design": design,
                "validation": validation,
                "folds": folds,
                "annual_total_wins": total_wins,
                "annual_median_hold_wins": hold_wins,
                "gates": gates,
                "pass": all(gates.values()),
            }
        )
    qualified = [row for row in candidates if row["pass"]]
    qualified.sort(
        key=lambda row: (
            base.dec(row["validation"], "total_pnl_usdt"),
            -base.dec(row["validation"], "max_drawdown_pct"),
            -D(str(row["validation"]["p90_hold_hours"])),
            -D(str(row["validation"]["median_hold_hours"])),
            -D(row["validation"]["ending_open_cost_usdt"]),
        ),
        reverse=True,
    )
    selected = qualified[0] if qualified else None
    runner_sha = source_hash()
    result = {
        "status": "CANDIDATE_FROZEN" if selected else "NO_CANDIDATE_KEEP_DRA_V1",
        "selection_decision": "CANDIDATE_FROZEN" if selected else "NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_THROUGH_HYBRID_PROFIT_LOCK_V2",
        "oos_opened": False,
        "baselines": baselines,
        "candidates": candidates,
        "qualified_count": len(qualified),
        "selected": selected,
        "one_slot_overlay": None,
    }
    if selected:
        candidate = selected["candidate"]
        result["frozen_candidate_key"] = candidate
        result["freeze_sha256"] = freeze_hash(digest, artifact_hashes, runner_sha, candidate)
        result["one_slot_overlay"] = {
            "design": simulate_candidate(bars, DESIGN, candidate, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, candidate, cap=base.LOT_COST),
            "folds": {name: simulate_candidate(bars, fold, candidate, cap=base.LOT_COST) for name, fold in FOLDS.items()},
        }
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["conditional_partial_audit"]
    return {
        "oos_total_at_least_v1": base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "oos_total_retains_90pct_v2a": base.dec(candidate, "total_pnl_usdt") >= base.dec(v2a, "total_pnl_usdt") * D("0.90"),
        "oos_realized_at_least_v1": base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "oos_unrealized_no_worse": base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "oos_drawdown_within_v1_plus_2pp": base.dec(candidate, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct") + D("2.0"),
        "oos_cost_weighted_median_no_worse": candidate["median_hold_hours"] is not None and candidate["median_hold_hours"] <= v1["median_hold_hours"],
        "oos_cost_weighted_p90_no_worse": candidate["p90_hold_hours"] is not None and candidate["p90_hold_hours"] <= v1["p90_hold_hours"],
        "accounting_and_trigger_audit": all((
            audit["all_entry_routes_complete_pass"],
            audit["cost_allocation_reconciles_pass"],
            audit["quantity_conservation_pass"],
            audit["all_partial_conditions_pass"],
            audit["all_exit_fills_strictly_net_positive_pass"],
            audit["at_most_one_partial_fill_per_lot_pass"],
        )),
    }


def run_oos(preselect_path: Path, cutoff: datetime, output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OOS_SEAL_REJECT", f"output already exists: {output}")
    preselection = json.loads(preselect_path.read_text(encoding="utf-8"))
    if preselection.get("status") != "CANDIDATE_FROZEN":
        raise base.ResearchReject("OOS_SEAL_REJECT", "preselection froze no candidate")
    artifact_hashes = verify_preregistration_artifacts()
    runner_sha = source_hash()
    if preselection.get("selection_data_sha256") != SELECTION_SHA256:
        raise base.ResearchReject("OOS_SEAL_REJECT", "selection data hash mismatch")
    for field, expected in {**artifact_hashes, "runner_sha256": runner_sha}.items():
        if preselection.get(field) != expected:
            raise base.ResearchReject("OOS_SEAL_REJECT", f"{field} mismatch")
    candidate = preselection.get("frozen_candidate_key")
    if candidate not in CANDIDATES:
        raise base.ResearchReject("OOS_SEAL_REJECT", f"unknown candidate {candidate}")
    expected_freeze = freeze_hash(SELECTION_SHA256, artifact_hashes, runner_sha, candidate)
    if preselection.get("freeze_sha256") != expected_freeze:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    if cutoff <= SELECTION_CUTOFF:
        raise base.ResearchReject("OOS_SEAL_REJECT", "cutoff must be after 2025-01-01")
    bars = base.parse_rows(base.fetch_rows(cutoff))
    available_end = bars[-1].close_time
    window = (SELECTION_CUTOFF, available_end)
    v1 = base.simulate(bars, window, "v1")
    v2a = base.simulate(bars, window, "v2a")
    candidate_result = simulate_candidate(bars, window, candidate)
    gates = oos_gates(candidate_result, v1, v2a)
    result = {
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "frozen_candidate_key": candidate,
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_requested_cutoff": cutoff.isoformat(),
        "oos_last_complete_close": available_end.isoformat(),
        "full_data_rows": len(bars),
        "full_data_sha256": base.data_hash(bars),
        "oos": {"v1_reference_250": v1, "v2a_reference_250": v2a, "candidate_reference_250": candidate_result, "gates": gates},
        "one_slot_overlay_30": {
            "design": simulate_candidate(bars, DESIGN, candidate, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, candidate, cap=base.LOT_COST),
            "folds": {name: simulate_candidate(bars, fold, candidate, cap=base.LOT_COST) for name, fold in FOLDS.items()},
            "oos": simulate_candidate(bars, window, candidate, cap=base.LOT_COST),
        },
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidates", "selected", "one_slot_overlay", "oos", "one_slot_overlay_30"}
    return {key: value for key, value in result.items() if key not in omitted}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="stage", required=True)
    preselect = subparsers.add_parser("preselect")
    preselect.add_argument("--output", type=Path, required=True)
    oos = subparsers.add_parser("oos")
    oos.add_argument("--preselect", type=Path, required=True)
    oos.add_argument("--cutoff", required=True)
    oos.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output: Path = args.output
    try:
        if args.stage == "preselect":
            result = run_preselect(output)
        else:
            cutoff = datetime.fromisoformat(args.cutoff)
            if cutoff.tzinfo is not None:
                cutoff = cutoff.astimezone(UTC).replace(tzinfo=None)
            result = run_oos(args.preselect, cutoff, output)
    except base.ResearchReject as reject:
        result = {
            "status": reject.status,
            "research_identity": RESEARCH_IDENTITY,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
            "detail": reject.detail,
        }
        if not (args.stage == "oos" and output.exists()):
            base.write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
