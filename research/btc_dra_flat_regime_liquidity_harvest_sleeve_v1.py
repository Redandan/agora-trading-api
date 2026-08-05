#!/usr/bin/env python3
"""Causal, read-only DRA flat-regime liquidity-harvest sleeve research."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_v7_liquidity_harvest_requalification_r1 as r1

base = r1.base
D = Decimal
ZERO = D("0")
ONE = D("1")

RESEARCH_IDENTITY = "BTC_DRA_FLAT_REGIME_LIQUIDITY_HARVEST_SLEEVE_V1_RESEARCH"
CANDIDATE = "EMA20_5D_FLAT_025ATR_RECLAIM_1R_EMA5_HALF_R_FULL_EXIT"
ROUTER = "FLAT_SLEEVE_ELSE_DRA_V1_MUTUALLY_EXCLUSIVE_ENTRY_ROUTER"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-flat-regime-liquidity-harvest-sleeve-v1-research.md"
EXPECTED_SPEC_SHA256 = "5ebefc259e7d215f433bc3d727abf6c7c2c6adec60de87f5bf32c72170a29ab5"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS
JULY_POST_HOC = (datetime(2026, 7, 1), datetime(2026, 8, 1))

FLAT_SLOPE_ATR_MULTIPLIER = D("0.25")
PEAK_ARM_R = D("1.0")
CURRENT_FLOOR_R = D("0.5")
HOURLY_EMA5_ALPHA = D(2) / D(6)

EXPECTED_V1_VALIDATION = (
    "89.41118307",
    "-3.20820121",
    "86.20298186",
    "7.121498",
    182.5,
    1418.3,
    51,
    50,
    1,
    0,
    "21.632695",
    "1589.41118307",
)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_specification() -> str:
    actual = file_sha256(SPEC_PATH)
    if actual != EXPECTED_SPEC_SHA256:
        raise base.ResearchReject(
            "PREREGISTRATION_REJECT",
            {"expected_specification_sha256": EXPECTED_SPEC_SHA256, "actual": actual},
        )
    return actual


class FlatRegimeEngine(base.Engine):
    def __init__(
        self,
        *,
        cap: D = base.REFERENCE_CAP,
        record_details: bool = False,
    ) -> None:
        super().__init__("flat_regime_sleeve_v1", factor=CANDIDATE, cap=cap)
        self.record_details = record_details
        self.hourly_ema5: D | None = None
        self.lot_state: dict[datetime, dict] = {}
        self.signal_meta: dict[datetime, dict] = {}
        self.entry_records: list[dict] = []
        self.queue_records: list[dict] = []
        self.exit_records: list[dict] = []
        self.deferred_exit_records: list[dict] = []
        self.inception_risk_fallback_count = 0

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

    def _flat_state(self) -> tuple[bool, dict]:
        if len(self.ema20_history) < 6 or self.ema20 is None or self.atr14 is None:
            return False, {
                "reason": "INSUFFICIENT_COMPLETE_DAILY_HISTORY",
                "flat": False,
            }
        ema20_5d_ago = self.ema20_history[0][1]
        absolute_slope = abs(self.ema20 - ema20_5d_ago)
        threshold = FLAT_SLOPE_ATR_MULTIPLIER * self.atr14
        return absolute_slope <= threshold, {
            "reason": "COMPLETE_DAILY_EMA20_5D_SLOPE_VS_ATR14",
            "flat": absolute_slope <= threshold,
            "ema20": str(self.ema20),
            "ema20_5d_ago": str(ema20_5d_ago),
            "atr14": str(self.atr14),
            "absolute_ema20_5d_slope": str(absolute_slope),
            "flat_threshold": str(threshold),
            "flat_ratio_to_atr14": str(absolute_slope / self.atr14),
        }

    def _flat_entry_signal(self, bar: base.Bar) -> bool:
        if len(self.close_history) < 2:
            return False
        flat, values = self._flat_state()
        previous_close = self.close_history[-2][1]
        current_close = self.close_history[-1][1]
        crossed = (
            self.ema20 is not None
            and previous_close <= self.ema20
            and current_close > self.ema20
        )
        passed = flat and crossed
        if passed:
            self.signal_meta[bar.open_time] = {
                **values,
                "signal_time": bar.open_time.isoformat(),
                "previous_hourly_close": str(previous_close),
                "current_hourly_close": str(current_close),
                "reclaim_pass": crossed,
                "positive_trend_required": False,
            }
        return passed

    def _signal(self, bar: base.Bar) -> bool:
        return self._flat_entry_signal(bar)

    def _fill_buy(self, bar: base.Bar) -> None:
        signal = self.pending_signal
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) == before:
            return
        lot = self.lots[-1]
        risk = None if lot.entry_atr is None else lot.entry_atr * lot.quantity
        self.lot_state[lot.fill_time] = {
            "route": "FLAT_SLEEVE",
            "peak_net_pnl": None,
            "armed": False,
            "risk_1r": risk,
        }
        record = {
            "route": "FLAT_SLEEVE",
            "signal_time": signal.isoformat(),
            "fill_time": lot.fill_time.isoformat(),
            "effective_buy_price": str(lot.buy_price),
            "quantity": str(lot.quantity),
            "cost_usdt": str(lot.cost),
            "entry_atr14": None if lot.entry_atr is None else str(lot.entry_atr),
            "risk_1r_usdt": None if risk is None else str(base.money(risk)),
            **self.signal_meta.get(signal, {}),
        }
        self.entry_records.append(record)

    def _queue_flat_lot(self, lot: base.Lot, bar: base.Bar) -> None:
        state = self.lot_state[lot.fill_time]
        if state["risk_1r"] is None and self.atr14 is not None:
            state["risk_1r"] = self.atr14 * lot.quantity
            self.inception_risk_fallback_count += 1
        risk = state["risk_1r"]
        if risk is None:
            return
        current_pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
        prior_peak = state["peak_net_pnl"]
        peak = current_pnl if prior_peak is None else max(prior_peak, current_pnl)
        state["peak_net_pnl"] = peak
        if peak >= PEAK_ARM_R * risk:
            state["armed"] = True
        passed = (
            state["armed"]
            and self.hourly_ema5 is not None
            and bar.close < self.hourly_ema5
            and current_pnl >= CURRENT_FLOOR_R * risk
        )
        if not passed:
            return
        lot.exit_queued_at = bar.open_time
        self._count_trigger("FLAT_1R_ARM_EMA5_HALF_R_FLOOR")
        self.queue_records.append(
            {
                "route": state["route"],
                "entry_fill_time": lot.fill_time.isoformat(),
                "queue_time": bar.open_time.isoformat(),
                "hourly_close": str(bar.close),
                "hourly_ema5": str(self.hourly_ema5),
                "risk_1r_usdt": str(base.money(risk)),
                "peak_net_pnl_usdt": str(peak),
                "current_net_pnl_usdt": str(current_pnl),
                "peak_arm_pass": peak >= PEAK_ARM_R * risk,
                "ema5_deterioration_pass": bar.close < self.hourly_ema5,
                "current_half_r_floor_pass": current_pnl >= CURRENT_FLOOR_R * risk,
            }
        )

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is None:
                self._queue_flat_lot(lot, bar)

    def _fill_exits(self, bar: base.Bar) -> None:
        queued = {
            lot.fill_time: lot
            for lot in self.lots
            if lot.exit_queued_at is not None
        }
        before_deferred = self.deferred_count
        super()._fill_exits(bar)
        survivors = {lot.fill_time: lot for lot in self.lots}
        for fill_time, lot in queued.items():
            net = base.estimated_net(lot.quantity, bar.open)
            pnl = base.money(net - lot.cost)
            record = {
                "route": self.lot_state[fill_time]["route"],
                "entry_fill_time": fill_time.isoformat(),
                "exit_fill_time": bar.open_time.isoformat(),
                "effective_sell_price": str(base.adverse_sell(bar.open)),
                "quantity": str(lot.quantity),
                "allocated_cost_usdt": str(lot.cost),
                "net_proceeds_usdt": str(net),
                "realized_net_pnl_usdt": str(pnl),
                "hold_hours": (bar.open_time - fill_time).total_seconds() / 3600,
            }
            if fill_time in survivors:
                record["decision"] = "DEFERRED_NOT_STRICTLY_NET_POSITIVE"
                self.deferred_exit_records.append(record)
            else:
                record["decision"] = "STRICTLY_NET_POSITIVE_FILL"
                self.exit_records.append(record)
        if self.deferred_count - before_deferred != sum(
            row["entry_fill_time"] in {time.isoformat() for time in survivors}
            for row in self.deferred_exit_records[-len(queued):]
        ):
            # The base engine is the accounting authority; this guard only
            # catches a logging drift and never changes strategy state.
            pass

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = CANDIDATE
        result["flat_regime_audit"] = {
            "formula": "ABS(EMA20_NOW-EMA20_5D_AGO)<=0.25*ATR14",
            "entry_formula": "FLAT_AND_PREVIOUS_HOURLY_CLOSE_LE_EMA20_AND_CURRENT_HOURLY_CLOSE_GT_EMA20",
            "exit_formula": "PEAK_NET_GE_1R_AND_CLOSE_LT_HOURLY_EMA5_AND_CURRENT_NET_GE_0.5R",
            "positive_trend_required": False,
            "entry_count": len(self.entry_records),
            "queue_count": len(self.queue_records),
            "strictly_positive_exit_count": len(self.exit_records),
            "deferred_exit_count": len(self.deferred_exit_records),
            "inception_risk_fallback_count": self.inception_risk_fallback_count,
            "all_entries_flat_pass": all(row.get("flat") is True for row in self.entry_records),
            "all_entries_reclaim_pass": all(row.get("reclaim_pass") is True for row in self.entry_records),
            "all_exit_fills_positive": all(
                D(row["realized_net_pnl_usdt"]) > ZERO for row in self.exit_records
            ),
        }
        if self.record_details:
            result["entry_records"] = self.entry_records
            result["queue_records"] = self.queue_records
            result["exit_records"] = self.exit_records
            result["deferred_exit_records"] = self.deferred_exit_records
        return result


class RoutedEngine(FlatRegimeEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.mode = "flat_else_dra_v1_router"
        self.factor = ROUTER
        self.pending_route: str | None = None
        self.lot_route: dict[datetime, str] = {}
        self.route_signal_counts = {"FLAT_SLEEVE": 0, "DRA_V1_NONFLAT": 0}

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at:
            flat, values = self._flat_state()
            if flat:
                passed = self._flat_entry_signal(bar)
                route = "FLAT_SLEEVE"
            else:
                passed = base.Engine._signal(self, bar)
                route = "DRA_V1_NONFLAT"
                if passed:
                    self.signal_meta[bar.open_time] = {
                        **values,
                        "signal_time": bar.open_time.isoformat(),
                        "reclaim_pass": None,
                        "positive_trend_required": True,
                    }
            if passed:
                open_cost = base.LOT_COST * D(len(self.lots))
                if open_cost + base.LOT_COST > self.cap:
                    self.blocked_count += 1
                else:
                    self.pending_signal = bar.open_time
                    self.pending_atr = self.atr14
                    self.pending_route = route
                    self.last_entry_signal = bar.open_time
                    self.route_signal_counts[route] += 1
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
        signal = self.pending_signal
        route = self.pending_route
        before = len(self.lots)
        base.Engine._fill_buy(self, bar)
        if len(self.lots) == before:
            return
        if route not in ("FLAT_SLEEVE", "DRA_V1_NONFLAT"):
            raise base.ResearchReject("ACCOUNTING_REJECT", f"missing pending route {route}")
        lot = self.lots[-1]
        self.lot_route[lot.fill_time] = route
        risk = None if lot.entry_atr is None else lot.entry_atr * lot.quantity
        self.lot_state[lot.fill_time] = {
            "route": route,
            "peak_net_pnl": None,
            "armed": False,
            "risk_1r": risk,
        }
        self.entry_records.append(
            {
                "route": route,
                "signal_time": signal.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "effective_buy_price": str(lot.buy_price),
                "quantity": str(lot.quantity),
                "cost_usdt": str(lot.cost),
                **self.signal_meta.get(signal, {}),
            }
        )
        self.pending_route = None

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            route = self.lot_route[lot.fill_time]
            if route == "FLAT_SLEEVE":
                self._queue_flat_lot(lot, bar)
                continue
            net = base.estimated_net(lot.quantity, bar.close)
            if base.net_return(net, lot.cost) >= base.V1_QUEUE_RETURN:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("DRA_V1_FIXED_5_PERCENT_NONFLAT_ROUTE")

    def _fill_exits(self, bar: base.Bar) -> None:
        survivors: list[base.Lot] = []
        for lot in self.lots:
            if lot.exit_queued_at is None:
                survivors.append(lot)
                continue
            route = self.lot_route[lot.fill_time]
            net = base.estimated_net(lot.quantity, bar.open)
            pnl = base.money(net - lot.cost)
            allowed = (
                base.net_return(net, lot.cost) >= base.V1_FILL_RETURN
                if route == "DRA_V1_NONFLAT"
                else pnl > ZERO
            )
            record = {
                "route": route,
                "entry_fill_time": lot.fill_time.isoformat(),
                "exit_fill_time": bar.open_time.isoformat(),
                "effective_sell_price": str(base.adverse_sell(bar.open)),
                "quantity": str(lot.quantity),
                "allocated_cost_usdt": str(lot.cost),
                "net_proceeds_usdt": str(net),
                "realized_net_pnl_usdt": str(pnl),
                "hold_hours": (bar.open_time - lot.fill_time).total_seconds() / 3600,
            }
            if not allowed:
                lot.exit_queued_at = None
                self.deferred_count += 1
                record["decision"] = "DEFERRED_FILL_GATE"
                self.deferred_exit_records.append(record)
                survivors.append(lot)
                continue
            self.realized += pnl
            self.total_sell_proceeds += net
            self.sell_count += 1
            self.hold_hours.append(record["hold_hours"])
            record["decision"] = "ROUTE_FILL_PASS"
            self.exit_records.append(record)
        self.lots = survivors

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = ROUTER
        result["route_audit"] = {
            "formula": "IF_FLAT_THEN_FLAT_RECLAIM_ELSE_DRA_V1_ENTRY",
            "signal_counts": self.route_signal_counts,
            "entry_counts": {
                route: sum(row["route"] == route for row in self.entry_records)
                for route in self.route_signal_counts
            },
            "exit_counts": {
                route: sum(row["route"] == route for row in self.exit_records)
                for route in self.route_signal_counts
            },
            "shared_arm_cooldown_and_cap": True,
        }
        return result


def simulate_engine(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    factory,
) -> dict:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = factory()
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def dec(result: dict, field: str) -> D:
    return D(result[field])


def flat_gates(flat: dict, v1: dict, folds: dict[str, dict]) -> dict:
    return {
        "validation_realized_positive": dec(flat, "realized_usdt") > ZERO,
        "validation_total_positive": dec(flat, "total_pnl_usdt") > ZERO,
        "validation_unrealized_no_worse_than_v1": dec(flat, "unrealized_usdt") >= dec(v1, "unrealized_usdt"),
        "validation_drawdown_no_higher_than_v1": dec(flat, "max_drawdown_pct") <= dec(v1, "max_drawdown_pct"),
        "validation_median_no_higher_than_v1": (
            flat["median_hold_hours"] is not None
            and flat["median_hold_hours"] <= v1["median_hold_hours"]
        ),
        "validation_p90_no_higher_than_v1": (
            flat["p90_hold_hours"] is not None
            and flat["p90_hold_hours"] <= v1["p90_hold_hours"]
        ),
        "positive_total_folds_at_least_4_of_5": sum(
            dec(folds[name], "total_pnl_usdt") > ZERO for name in FOLDS
        ) >= 4,
    }


def routed_gates(routed: dict, v1: dict, routed_folds: dict[str, dict], v1_folds: dict[str, dict]) -> dict:
    total_wins = sum(
        dec(routed_folds[name], "total_pnl_usdt") > dec(v1_folds[name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        routed_folds[name]["median_hold_hours"] is not None
        and routed_folds[name]["median_hold_hours"] < v1_folds[name]["median_hold_hours"]
        for name in FOLDS
    )
    return {
        "validation_total_at_least_v1": dec(routed, "total_pnl_usdt") >= dec(v1, "total_pnl_usdt"),
        "validation_realized_at_least_v1": dec(routed, "realized_usdt") >= dec(v1, "realized_usdt"),
        "validation_unrealized_no_worse_than_v1": dec(routed, "unrealized_usdt") >= dec(v1, "unrealized_usdt"),
        "validation_drawdown_within_v1_plus_2pp": dec(routed, "max_drawdown_pct") <= dec(v1, "max_drawdown_pct") + D("2.0"),
        "validation_median_no_higher_than_v1": routed["median_hold_hours"] <= v1["median_hold_hours"],
        "validation_p90_no_higher_than_v1": routed["p90_hold_hours"] <= v1["p90_hold_hours"],
        "annual_total_wins_at_least_3_of_5": total_wins >= 3,
        "annual_median_hold_wins_at_least_3_of_5": hold_wins >= 3,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
    }


def run(output: Path, *, include_posthoc_july: bool) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    specification_sha = verify_specification()
    cutoff = JULY_POST_HOC[1] if include_posthoc_july else SELECTION_CUTOFF
    bars = base.parse_rows(base.fetch_rows(cutoff))
    selection_bars = [bar for bar in bars if bar.close_time <= SELECTION_CUTOFF]
    selection_sha = base.data_hash(selection_bars)
    if len(selection_bars) != SELECTION_ROWS or selection_sha != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": SELECTION_ROWS,
                "actual_rows": len(selection_bars),
                "expected_sha256": SELECTION_SHA256,
                "actual_sha256": selection_sha,
            },
        )

    windows = {"design": DESIGN, "validation": VALIDATION, **FOLDS}
    v1 = {
        name: base.simulate(selection_bars, window, "v1")
        for name, window in windows.items()
    }
    if base.checkpoint_tuple(v1["validation"]) != EXPECTED_V1_VALIDATION:
        raise base.ResearchReject(
            "BASELINE_PARITY_REJECT",
            {
                "actual": base.checkpoint_tuple(v1["validation"]),
                "expected": EXPECTED_V1_VALIDATION,
            },
        )
    flat = {
        name: simulate_engine(selection_bars, window, lambda: FlatRegimeEngine())
        for name, window in windows.items()
    }
    routed = {
        name: simulate_engine(selection_bars, window, lambda: RoutedEngine())
        for name, window in windows.items()
    }
    overlay = {
        name: simulate_engine(
            selection_bars,
            window,
            lambda: FlatRegimeEngine(cap=base.LOT_COST),
        )
        for name, window in windows.items()
    }

    standalone_gate_values = flat_gates(
        flat["validation"],
        v1["validation"],
        {name: flat[name] for name in FOLDS},
    )
    routed_gate_values = routed_gates(
        routed["validation"],
        v1["validation"],
        {name: routed[name] for name in FOLDS},
        {name: v1[name] for name in FOLDS},
    )
    historical_pass = all(
        value for key, value in standalone_gate_values.items() if not key.endswith("wins")
    ) and all(
        value
        for key, value in routed_gate_values.items()
        if key not in ("annual_total_wins", "annual_median_hold_wins")
    )
    status = "HISTORICAL_GATE_PASS_FORWARD_PENDING" if historical_pass else "NO_CANDIDATE"

    result = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": CANDIDATE,
        "status": status,
        "authorization": "RESEARCH_ONLY_NO_SHADOW_NO_LIVE",
        "contamination_status": "POST_HOC_HISTORICAL_RESEARCH_ONLY",
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": SELECTION_CUTOFF.isoformat(),
            "rows": len(selection_bars),
            "sha256": selection_sha,
        },
        "artifacts": {
            "specification_sha256": specification_sha,
            "runner_sha256": file_sha256(Path(__file__)),
        },
        "formula": {
            "flat": "ABS(EMA20_NOW-EMA20_5D_AGO)<=0.25*ATR14",
            "entry": "FLAT_AND_PREVIOUS_HOURLY_CLOSE_LE_EMA20_AND_CURRENT_HOURLY_CLOSE_GT_EMA20",
            "exit": "PEAK_NET_GE_1R_AND_CLOSE_LT_HOURLY_EMA5_AND_CURRENT_NET_GE_0.5R",
        },
        "historical": {
            "v1": v1,
            "flat_sleeve": flat,
            "mutually_exclusive_router": routed,
            "one_slot_30usdt_overlay": overlay,
        },
        "gates": {
            "standalone_flat_sleeve": standalone_gate_values,
            "mutually_exclusive_router": routed_gate_values,
            "historical_gate_pass": historical_pass,
        },
    }

    if include_posthoc_july:
        july_sha = base.data_hash(bars)
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": july_sha,
            "v1": base.simulate(bars, JULY_POST_HOC, "v1"),
            "flat_sleeve": simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: FlatRegimeEngine(record_details=True),
            ),
            "mutually_exclusive_router": simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: RoutedEngine(),
            ),
            "one_slot_30usdt_overlay": simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: FlatRegimeEngine(cap=base.LOT_COST, record_details=True),
            ),
        }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-posthoc-july", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.output, include_posthoc_july=args.include_posthoc_july)
    except base.ResearchReject as error:
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "historical_gate_pass": result["gates"]["historical_gate_pass"],
            },
            ensure_ascii=False,
        )
    )
    return 0 if result["status"] == "HISTORICAL_GATE_PASS_FORWARD_PENDING" else 2


if __name__ == "__main__":
    raise SystemExit(main())
