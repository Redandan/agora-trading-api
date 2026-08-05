#!/usr/bin/env python3
"""Causal, read-only lower-range entry / upper-range reversal DRA research."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_flat_regime_liquidity_harvest_sleeve_v1 as v1

base = v1.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = (
    "BTC_DRA_FLAT_RANGE_LOWER_THIRD_UPPER_THIRD_REVERSAL_HARVEST_V3_RESEARCH"
)
CANDIDATE = (
    "FLAT_DONCHIAN20_LOWER_THIRD_RECLAIM_UPPER_THIRD_TOUCH_EMA5_REVERSAL"
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = (
    ROOT
    / "docs"
    / "btc-dra-flat-range-lower-third-upper-third-reversal-harvest-v3-research.md"
)
EXPECTED_SPEC_SHA256 = "0d5df70d421e50e9946fe5861602ff93602a1c5795cd5c1d1da4246a3099315f"

SELECTION_CUTOFF = v1.SELECTION_CUTOFF
SELECTION_ROWS = v1.SELECTION_ROWS
SELECTION_SHA256 = v1.SELECTION_SHA256
DESIGN = v1.DESIGN
VALIDATION = v1.VALIDATION
FOLDS = v1.FOLDS
JULY_POST_HOC = v1.JULY_POST_HOC

EXPECTED_V1_VALIDATION = v1.EXPECTED_V1_VALIDATION
EXPECTED_FLAT_V1_VALIDATION = (
    "35.82305220",
    "-1.56726556",
    "34.25578664",
    "5.798793",
    245.0,
    1951.2,
    24,
    23,
    1,
    0,
    "11.017100",
    "725.82305220",
)

PRIOR_RANGE_DAYS = 20
ONE_THIRD = D("1") / D("3")
ONE_HALF = D("0.5")
MIN_VALIDATION_SELLS = 10


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


class RangeHarvestEngine(v1.FlatRegimeEngine):
    def __init__(
        self,
        *,
        cap: D = base.REFERENCE_CAP,
        record_details: bool = False,
    ) -> None:
        super().__init__(cap=cap, record_details=record_details)
        self.mode = "flat_range_lower_upper_reversal_harvest_v3"
        self.factor = CANDIDATE
        self.completed_ranges: deque[dict] = deque(maxlen=64)
        self.gap_cancel_records: list[dict] = []
        self.touch_records: list[dict] = []

    def _finish_day(self, bar: base.Bar) -> None:
        super()._finish_day(bar)
        if bar.open_time.hour == 23:
            self.completed_ranges.append(
                {
                    "day": bar.open_time,
                    "high": self.daily_high,
                    "low": self.daily_low,
                    "close": self.daily_close,
                }
            )

    def _range_state(self, bar: base.Bar) -> tuple[bool, dict]:
        completed = list(self.completed_ranges)
        if completed and completed[-1]["day"].date() == bar.open_time.date():
            completed = completed[:-1]
        prior = completed[-PRIOR_RANGE_DAYS:]
        if len(prior) < PRIOR_RANGE_DAYS:
            return False, {
                "range_ready": False,
                "range_reason": "INSUFFICIENT_PRIOR_COMPLETE_UTC_DAYS",
                "prior_complete_day_count": len(prior),
            }
        range_low = min(row["low"] for row in prior)
        range_high = max(row["high"] for row in prior)
        width = range_high - range_low
        if width <= ZERO:
            return False, {
                "range_ready": False,
                "range_reason": "NON_POSITIVE_RANGE_WIDTH",
                "prior_complete_day_count": len(prior),
            }
        lower = range_low + width * ONE_THIRD
        midpoint = range_low + width * ONE_HALF
        upper = range_high - width * ONE_THIRD
        return True, {
            "range_ready": True,
            "range_reason": "PRIOR_20_COMPLETE_UTC_DAYS_EXCLUDING_CURRENT_DAY",
            "prior_complete_day_count": len(prior),
            "range_first_day": prior[0]["day"].isoformat(),
            "range_last_day": prior[-1]["day"].isoformat(),
            "range_low20": str(range_low),
            "range_high20": str(range_high),
            "range_width20": str(width),
            "lower_third": str(lower),
            "midpoint": str(midpoint),
            "upper_third": str(upper),
            "current_day_excluded": all(
                row["day"].date() < bar.open_time.date() for row in prior
            ),
        }

    def _signal(self, bar: base.Bar) -> bool:
        if len(self.close_history) < 2:
            return False
        flat, flat_values = self._flat_state()
        range_ready, range_values = self._range_state(bar)
        if not flat or not range_ready:
            return False
        previous_close = self.close_history[-2][1]
        current_close = self.close_history[-1][1]
        lower = D(range_values["lower_third"])
        midpoint = D(range_values["midpoint"])
        lower_reclaim = previous_close <= lower and current_close > lower
        lower_half_location = current_close <= midpoint
        passed = lower_reclaim and lower_half_location
        if passed:
            width = D(range_values["range_width20"])
            range_low = D(range_values["range_low20"])
            self.signal_meta[bar.open_time] = {
                **flat_values,
                **range_values,
                "signal_time": bar.open_time.isoformat(),
                "previous_hourly_close": str(previous_close),
                "current_hourly_close": str(current_close),
                "lower_third_reclaim_pass": lower_reclaim,
                "current_close_at_or_below_midpoint": lower_half_location,
                "signal_range_position": str((current_close - range_low) / width),
                "positive_trend_required": False,
            }
        return passed

    def _fill_buy(self, bar: base.Bar) -> None:
        if self.pending_signal is None:
            return
        signal = self.pending_signal
        meta = self.signal_meta.get(signal)
        if meta is None:
            raise base.ResearchReject(
                "ACCOUNTING_REJECT",
                {"reason": "MISSING_SIGNAL_RANGE_META", "signal": signal.isoformat()},
            )
        effective_buy = base.adverse_buy(bar.open)
        midpoint = D(meta["midpoint"])
        if effective_buy > midpoint:
            self.gap_cancel_records.append(
                {
                    "signal_time": signal.isoformat(),
                    "cancel_time": bar.open_time.isoformat(),
                    "next_open": str(bar.open),
                    "effective_adverse_buy_price": str(effective_buy),
                    "signal_midpoint": str(midpoint),
                    "decision": "CANCEL_EFFECTIVE_BUY_ABOVE_SIGNAL_MIDPOINT",
                }
            )
            self.pending_signal = None
            self.pending_atr = None
            return
        before = len(self.lots)
        base.Engine._fill_buy(self, bar)
        if len(self.lots) != before + 1:
            raise base.ResearchReject("ACCOUNTING_REJECT", "range buy fill missing")
        lot = self.lots[-1]
        self.lot_state[lot.fill_time] = {
            "route": "FLAT_RANGE_SLEEVE",
            "frozen_range_low20": D(meta["range_low20"]),
            "frozen_range_high20": D(meta["range_high20"]),
            "frozen_lower_third": D(meta["lower_third"]),
            "frozen_midpoint": midpoint,
            "frozen_upper_third": D(meta["upper_third"]),
            "upper_touch_time": None,
        }
        self.entry_records.append(
            {
                "route": "FLAT_RANGE_SLEEVE",
                "signal_time": signal.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "effective_buy_price": str(lot.buy_price),
                "quantity": str(lot.quantity),
                "cost_usdt": str(lot.cost),
                "fill_at_or_below_signal_midpoint": lot.buy_price <= midpoint,
                "frozen_upper_third": meta["upper_third"],
                **meta,
            }
        )

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            if state["upper_touch_time"] is None:
                if bar.open_time > lot.fill_time and bar.close >= state["frozen_upper_third"]:
                    state["upper_touch_time"] = bar.open_time
                    self.touch_records.append(
                        {
                            "entry_fill_time": lot.fill_time.isoformat(),
                            "touch_time": bar.open_time.isoformat(),
                            "hourly_close": str(bar.close),
                            "frozen_upper_third": str(state["frozen_upper_third"]),
                            "close_reached_frozen_upper_third": True,
                        }
                    )
                continue
            current_pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            passed = (
                bar.open_time > state["upper_touch_time"]
                and self.hourly_ema5 is not None
                and bar.close < self.hourly_ema5
                and current_pnl > ZERO
            )
            if not passed:
                continue
            lot.exit_queued_at = bar.open_time
            self._count_trigger("FROZEN_UPPER_THIRD_TOUCH_THEN_EMA5_REVERSAL")
            self.queue_records.append(
                {
                    "route": state["route"],
                    "entry_fill_time": lot.fill_time.isoformat(),
                    "upper_touch_time": state["upper_touch_time"].isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "frozen_upper_third": str(state["frozen_upper_third"]),
                    "hourly_close": str(bar.close),
                    "hourly_ema5": str(self.hourly_ema5),
                    "current_net_pnl_usdt": str(current_pnl),
                    "queue_strictly_after_touch": bar.open_time
                    > state["upper_touch_time"],
                    "ema5_reversal_pass": bar.close < self.hourly_ema5,
                    "estimated_net_positive_pass": current_pnl > ZERO,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = base.Engine.result(self, final_bar, start, end)
        entry_to_touch_hours = [
            (
                datetime.fromisoformat(row["touch_time"])
                - datetime.fromisoformat(row["entry_fill_time"])
            ).total_seconds()
            / 3600
            for row in self.touch_records
        ]
        touch_to_queue_hours = [
            (
                datetime.fromisoformat(row["queue_time"])
                - datetime.fromisoformat(row["upper_touch_time"])
            ).total_seconds()
            / 3600
            for row in self.queue_records
        ]
        result["candidate"] = CANDIDATE
        result["range_harvest_audit"] = {
            "flat_formula": "ABS(EMA20_NOW-EMA20_5D_AGO)<=0.25*ATR14",
            "range_formula": "PRIOR_20_COMPLETE_UTC_DAYS_EXCLUDING_CURRENT_DAY",
            "entry_formula": "LOWER_THIRD_UPWARD_RECLAIM_AND_CLOSE_LE_MIDPOINT",
            "fill_formula": "EFFECTIVE_ADVERSE_BUY_PRICE_LE_SIGNAL_MIDPOINT",
            "qualification_formula": "LATER_HOURLY_CLOSE_GE_FROZEN_ENTRY_UPPER_THIRD",
            "exit_formula": "STRICTLY_LATER_CLOSE_LT_EMA5_AND_ESTIMATED_NET_PNL_GT_0",
            "fixed_profit_target_used": False,
            "one_r_arm_used": False,
            "positive_trend_required": False,
            "entry_count": len(self.entry_records),
            "gap_cancel_count": len(self.gap_cancel_records),
            "upper_touch_count": len(self.touch_records),
            "queue_count": len(self.queue_records),
            "strictly_positive_exit_count": len(self.exit_records),
            "deferred_exit_count": len(self.deferred_exit_records),
            "median_entry_to_upper_touch_hours": base.percentile(
                entry_to_touch_hours, 0.5
            ),
            "p90_entry_to_upper_touch_hours": base.percentile(
                entry_to_touch_hours, 0.9
            ),
            "median_upper_touch_to_queue_hours": base.percentile(
                touch_to_queue_hours, 0.5
            ),
            "p90_upper_touch_to_queue_hours": base.percentile(
                touch_to_queue_hours, 0.9
            ),
            "all_entries_flat_pass": all(
                row.get("flat") is True for row in self.entry_records
            ),
            "all_entry_ranges_causal": all(
                row.get("current_day_excluded") is True
                and row.get("prior_complete_day_count") == PRIOR_RANGE_DAYS
                for row in self.entry_records
            ),
            "all_entries_lower_reclaims": all(
                row.get("lower_third_reclaim_pass") is True
                for row in self.entry_records
            ),
            "all_signal_closes_in_lower_half": all(
                row.get("current_close_at_or_below_midpoint") is True
                for row in self.entry_records
            ),
            "all_buy_fills_at_or_below_midpoint": all(
                row.get("fill_at_or_below_signal_midpoint") is True
                for row in self.entry_records
            ),
            "all_gap_cancels_above_midpoint": all(
                D(row["effective_adverse_buy_price"]) > D(row["signal_midpoint"])
                for row in self.gap_cancel_records
            ),
            "all_touches_reach_frozen_upper": all(
                row.get("close_reached_frozen_upper_third") is True
                and D(row["hourly_close"]) >= D(row["frozen_upper_third"])
                for row in self.touch_records
            ),
            "all_queues_after_touch": all(
                row.get("queue_strictly_after_touch") is True
                for row in self.queue_records
            ),
            "all_queues_confirm_reversal_and_profit": all(
                row.get("ema5_reversal_pass") is True
                and row.get("estimated_net_positive_pass") is True
                for row in self.queue_records
            ),
            "all_exit_fills_positive": all(
                D(row["realized_net_pnl_usdt"]) > ZERO
                for row in self.exit_records
            ),
        }
        if self.record_details:
            result["entry_records"] = self.entry_records
            result["gap_cancel_records"] = self.gap_cancel_records
            result["touch_records"] = self.touch_records
            result["queue_records"] = self.queue_records
            result["exit_records"] = self.exit_records
            result["deferred_exit_records"] = self.deferred_exit_records
        return result


def dec(result: dict, field: str) -> D:
    return D(result[field])


def candidate_gates(
    candidate: dict,
    dra_v1: dict,
    flat_v1: dict,
    folds: dict[str, dict],
) -> dict:
    audit = candidate["range_harvest_audit"]
    audit_keys = (
        "all_entries_flat_pass",
        "all_entry_ranges_causal",
        "all_entries_lower_reclaims",
        "all_signal_closes_in_lower_half",
        "all_buy_fills_at_or_below_midpoint",
        "all_gap_cancels_above_midpoint",
        "all_touches_reach_frozen_upper",
        "all_queues_after_touch",
        "all_queues_confirm_reversal_and_profit",
        "all_exit_fills_positive",
    )
    return {
        "validation_total_at_least_flat_v1": dec(candidate, "total_pnl_usdt")
        >= dec(flat_v1, "total_pnl_usdt"),
        "validation_realized_positive": dec(candidate, "realized_usdt") > ZERO,
        "validation_unrealized_no_worse_than_dra_v1": dec(
            candidate, "unrealized_usdt"
        )
        >= dec(dra_v1, "unrealized_usdt"),
        "validation_drawdown_no_higher_than_dra_v1": dec(
            candidate, "max_drawdown_pct"
        )
        <= dec(dra_v1, "max_drawdown_pct"),
        "validation_median_no_higher_than_dra_v1": candidate[
            "median_hold_hours"
        ]
        is not None
        and candidate["median_hold_hours"] <= dra_v1["median_hold_hours"],
        "validation_p90_no_higher_than_dra_v1": candidate["p90_hold_hours"]
        is not None
        and candidate["p90_hold_hours"] <= dra_v1["p90_hold_hours"],
        "validation_completed_sells_at_least_10": candidate["sell_count"]
        >= MIN_VALIDATION_SELLS,
        "positive_total_folds_at_least_4_of_5": sum(
            dec(folds[name], "total_pnl_usdt") > ZERO for name in FOLDS
        )
        >= 4,
        "all_causal_audits_pass": all(audit[key] for key in audit_keys),
        "positive_total_folds": sum(
            dec(folds[name], "total_pnl_usdt") > ZERO for name in FOLDS
        ),
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
    dra_v1 = {
        name: base.simulate(selection_bars, window, "v1")
        for name, window in windows.items()
    }
    flat_v1 = {
        name: v1.simulate_engine(selection_bars, window, lambda: v1.FlatRegimeEngine())
        for name, window in windows.items()
    }
    candidate = {
        name: v1.simulate_engine(
            selection_bars,
            window,
            lambda: RangeHarvestEngine(record_details=True),
        )
        for name, window in windows.items()
    }
    overlay = {
        name: v1.simulate_engine(
            selection_bars,
            window,
            lambda: RangeHarvestEngine(cap=base.LOT_COST, record_details=True),
        )
        for name, window in windows.items()
    }

    checkpoints = {
        "dra_v1_validation": {
            "actual": base.checkpoint_tuple(dra_v1["validation"]),
            "expected": EXPECTED_V1_VALIDATION,
        },
        "flat_v1_validation": {
            "actual": base.checkpoint_tuple(flat_v1["validation"]),
            "expected": EXPECTED_FLAT_V1_VALIDATION,
        },
    }
    for name, values in checkpoints.items():
        if values["actual"] != values["expected"]:
            raise base.ResearchReject(
                "BASELINE_PARITY_REJECT",
                {
                    "checkpoint": name,
                    "actual": values["actual"],
                    "expected": values["expected"],
                },
            )

    gate_values = candidate_gates(
        candidate["validation"],
        dra_v1["validation"],
        flat_v1["validation"],
        {name: candidate[name] for name in FOLDS},
    )
    historical_pass = all(
        value for key, value in gate_values.items() if key != "positive_total_folds"
    )
    status = (
        "HISTORICAL_GATE_PASS_FORWARD_PENDING"
        if historical_pass
        else "NO_CANDIDATE"
    )

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
            "range": "PRIOR_20_COMPLETE_UTC_DAYS_EXCLUDING_CURRENT_DAY",
            "entry": "LOWER_THIRD_UPWARD_RECLAIM_AND_CLOSE_LE_MIDPOINT",
            "fill": "EFFECTIVE_ADVERSE_BUY_PRICE_LE_SIGNAL_MIDPOINT",
            "qualification": "LATER_CLOSE_GE_FROZEN_ENTRY_UPPER_THIRD",
            "exit": "STRICTLY_LATER_CLOSE_LT_EMA5_AND_ESTIMATED_NET_PNL_GT_0",
        },
        "checkpoints": {
            name: {
                "actual": list(values["actual"]),
                "expected": list(values["expected"]),
                "passed": values["actual"] == values["expected"],
            }
            for name, values in checkpoints.items()
        },
        "historical": {
            "dra_v1": dra_v1,
            "flat_sleeve_v1": flat_v1,
            "range_harvest_v3": candidate,
            "one_slot_30usdt_overlay_v3": overlay,
        },
        "gates": {
            "range_harvest_v3": gate_values,
            "historical_gate_pass": historical_pass,
        },
    }

    if include_posthoc_july:
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": base.data_hash(bars),
            "dra_v1": base.simulate(bars, JULY_POST_HOC, "v1"),
            "flat_sleeve_v1": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: v1.FlatRegimeEngine(record_details=True),
            ),
            "range_harvest_v3": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: RangeHarvestEngine(record_details=True),
            ),
            "one_slot_30usdt_overlay_v3": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: RangeHarvestEngine(
                    cap=base.LOT_COST,
                    record_details=True,
                ),
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
