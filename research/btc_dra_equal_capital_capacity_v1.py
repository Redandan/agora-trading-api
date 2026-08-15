#!/usr/bin/env python3
"""Equal-capital DRA V1 capacity runner without data access or outcome gates."""

from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
PCT_Q = D("0.000001")
IDENTITY = "BTC_DRA_EQUAL_CAPITAL_CAPACITY_RUNNER_V1"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"


def _percentage(numerator: D, denominator: D) -> str:
    if denominator <= 0:
        raise ValueError("percentage denominator must be positive")
    return str(
        ((numerator / denominator) * D("100")).quantize(
            PCT_Q, rounding=ROUND_HALF_UP
        )
    )


class EqualCapitalCapacityEngine(base.Engine):
    """Run unchanged DRA V1 logic with capacity separated from initial equity."""

    def __init__(self, *, slot_capacity_usdt: D, initial_equity_usdt: D) -> None:
        capacity = base.money(D(slot_capacity_usdt))
        initial_equity = base.money(D(initial_equity_usdt))
        if capacity <= 0 or initial_equity <= 0:
            raise ValueError("capacity and initial equity must be positive")
        if capacity > initial_equity:
            raise ValueError("slot capacity must not exceed initial equity")
        if capacity % base.LOT_COST != 0:
            raise ValueError("slot capacity must be an exact multiple of one DRA lot")

        super().__init__("v1", cap=capacity)
        self.initial_equity = initial_equity
        self.slot_count_limit = int(capacity / base.LOT_COST)
        self.peak_equity = initial_equity
        self.equity_utilization_sum = base.ZERO
        self.realized_lot_ledger: list[dict[str, Any]] = []
        self.inventory_hour_counts: dict[int, int] = {}
        self.underwater_hours = 0
        self.current_underwater_hours = 0
        self.maximum_underwater_duration_hours = 0
        self.underwater_episode_count = 0
        self.maximum_drawdown_at: datetime | None = None
        self.minimum_equity = initial_equity

    def _fill_exits(self, bar: base.Bar) -> None:
        queued: dict[datetime, dict[str, Any]] = {}
        for lot in self.lots:
            if lot.exit_queued_at is None:
                continue
            sell_price = base.adverse_sell(bar.open)
            gross = base.money(lot.quantity * sell_price)
            fee = base.money(gross * base.FEE)
            net = gross - fee
            queued[lot.fill_time] = {
                "signal_time": lot.signal_time.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "exit_fill_time": bar.open_time.isoformat(),
                "cost_usdt": str(base.money(lot.cost)),
                "realized_pnl_usdt": str(base.money(net - lot.cost)),
                "hold_hours": (
                    bar.open_time - lot.fill_time
                ).total_seconds()
                / 3600,
            }

        super()._fill_exits(bar)
        surviving_fill_times = {lot.fill_time for lot in self.lots}
        for fill_time, record in queued.items():
            if fill_time not in surviving_fill_times:
                self.realized_lot_ledger.append(record)

    def _track(self, bar: base.Bar) -> None:
        open_cost = base.LOT_COST * D(len(self.lots))
        unrealized = base.money(
            sum(
                (
                    base.estimated_net(lot.quantity, bar.close) - lot.cost
                    for lot in self.lots
                ),
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
        open_lot_count = len(self.lots)
        self.inventory_hour_counts[open_lot_count] = (
            self.inventory_hour_counts.get(open_lot_count, 0) + 1
        )
        self.utilization_sum += open_cost / self.cap
        self.equity_utilization_sum += open_cost / self.initial_equity
        self.utilization_points += 1

    def result(
        self, final_bar: base.Bar, start: datetime, end: datetime
    ) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        if self.utilization_points <= 0:
            raise base.ResearchReject(
                "DATA_REJECT", "equal-capital runner has no tracking points"
            )

        result["runner_identity"] = IDENTITY
        result["authorization"] = AUTHORIZATION
        result["reference_cap_usdt"] = str(self.initial_equity)
        result["initial_equity_usdt"] = str(self.initial_equity)
        result["slot_capacity_usdt"] = str(self.cap)
        result["slot_count_limit"] = self.slot_count_limit
        result["avg_slot_capacity_utilization_pct"] = result.pop(
            "avg_utilization_pct"
        )
        result["peak_slot_capacity_utilization_pct"] = result.pop(
            "peak_utilization_pct"
        )
        result["avg_equity_utilization_pct"] = _percentage(
            self.equity_utilization_sum, D(self.utilization_points)
        )
        result["peak_equity_utilization_pct"] = _percentage(
            self.max_open_cost, self.initial_equity
        )
        result["inventory_path"] = {
            "hour_counts_by_open_lot_count": {
                str(count): hours
                for count, hours in sorted(self.inventory_hour_counts.items())
            },
            "underwater_hours": self.underwater_hours,
            "underwater_episode_count": self.underwater_episode_count,
            "maximum_underwater_duration_hours": (
                self.maximum_underwater_duration_hours
            ),
            "maximum_drawdown_at": (
                None
                if self.maximum_drawdown_at is None
                else self.maximum_drawdown_at.isoformat()
            ),
            "minimum_equity_usdt": str(base.money(self.minimum_equity)),
        }
        result["realized_lot_ledger"] = list(self.realized_lot_ledger)
        result["terminal_inventory"] = [
            {
                "signal_time": lot.signal_time.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "cost_usdt": str(base.money(lot.cost)),
                "unrealized_pnl_usdt": str(
                    base.money(
                        base.estimated_net(lot.quantity, final_bar.close) - lot.cost
                    )
                ),
                "age_hours": (end - lot.fill_time).total_seconds() / 3600,
            }
            for lot in sorted(self.lots, key=lambda item: item.fill_time)
        ]
        return result


def simulate_capacity(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    slot_capacity_usdt: D,
    initial_equity_usdt: D,
) -> dict[str, Any]:
    """Run one frozen window; callers own data integrity and study gates."""

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
    engine = EqualCapitalCapacityEngine(
        slot_capacity_usdt=slot_capacity_usdt,
        initial_equity_usdt=initial_equity_usdt,
    )
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def equal_capital_deltas(
    parent: dict[str, Any], candidate: dict[str, Any]
) -> dict[str, Any]:
    """Produce paired deltas only; this function deliberately has no pass gate."""

    if parent.get("start") != candidate.get("start") or parent.get(
        "end_exclusive"
    ) != candidate.get("end_exclusive"):
        raise ValueError("equal-capital ledgers must use the same window")
    parent_equity = D(str(parent["initial_equity_usdt"]))
    candidate_equity = D(str(candidate["initial_equity_usdt"]))
    if parent_equity != candidate_equity:
        raise ValueError("equal-capital ledgers must use the same initial equity")

    decimal_fields = (
        "realized_usdt",
        "unrealized_usdt",
        "total_pnl_usdt",
        "max_drawdown_pct",
        "avg_equity_utilization_pct",
        "peak_equity_utilization_pct",
    )
    deltas = {
        f"{field}_delta": str(
            base.money(D(str(candidate[field])) - D(str(parent[field])))
            if field.endswith("_usdt")
            else (
                D(str(candidate[field])) - D(str(parent[field]))
            ).quantize(PCT_Q, rounding=ROUND_HALF_UP)
        )
        for field in decimal_fields
    }
    deltas["blocked_entries_delta"] = (
        int(candidate["blocked_entries"]) - int(parent["blocked_entries"])
    )
    deltas["buy_count_delta"] = int(candidate["buy_count"]) - int(
        parent["buy_count"]
    )
    return {
        "initial_equity_usdt": str(parent_equity),
        "parent_slot_capacity_usdt": parent["slot_capacity_usdt"],
        "candidate_slot_capacity_usdt": candidate["slot_capacity_usdt"],
        "deltas": deltas,
    }
