#!/usr/bin/env python3
"""Causal DRA V7 trend-quality promotion liquidity-harvest V8 research."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import btc_dra_v3c_pre_partial_one_r_promotion_exit_v7 as v7

base = v7.base
D = Decimal
ZERO = D("0")
FOUR = D("4")
FIVE = D("5")
HUNDRED = D("100")
THOUSAND = D("1000")
ORIGINAL_COST = D("30.00")
PARTIAL_COST = D("24.00")

RESEARCH_IDENTITY = "BTC_DRA_V7_TREND_QUALITY_PROMOTION_LIQUIDITY_HARVEST_V8_RESEARCH"
CANDIDATE = (
    "V3C_PARTIAL_ELIGIBLE_FIRST_1R_7D_ACCEL_DAILY_EMA20_UP_"
    "PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6"
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-v7-trend-quality-promotion-liquidity-harvest-v8-research.md"
V7_SPEC_PATH = ROOT / "docs" / "btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md"
EXPECTED_SPEC_SHA256 = "3f00e56ff19cf4809247cc9fab5bac12f6cacedb9ad8f2da8042875c36f78592"
EXPECTED_V7_SPEC_SHA256 = "b4034444510411a5e45681f5a9b12744e072bfee0b14e94842a09e5d9ee7be79"
EXPECTED_V7_RUNNER_SHA256 = "9441ff63db551d5105082387822f7a4ccdcd01e247ad86c6db5382d6df21d532"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V7_VALIDATION = (
    "96.02789691", "-0.64164024", "95.38625667", "6.832349", 192.0,
    836.0, 51, 75, 1, 0, "15.821888", "1620.02789691",
)
EXPECTED_V7_AUDIT = (18, 33, 25, 26, 24, 8, 8, 25, 0)
EXPECTED_V7_WINS = (2, 3)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def json_hash(value: object) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


def add_harvest_metrics(result: dict, window: tuple[datetime, datetime]) -> dict:
    start, end = window
    window_hours = D(str((end - start).total_seconds())) / D("3600")
    reference_cap = D(result["reference_cap_usdt"])
    average_utilization = D(result["avg_utilization_pct"]) / HUNDRED
    capital_hours = reference_cap * window_hours * average_utilization
    if capital_hours <= ZERO:
        raise base.ResearchReject("ACCOUNTING_REJECT", "non-positive capital-hours")
    efficiency = D(result["realized_usdt"]) * THOUSAND / capital_hours
    result["window_hours"] = str(window_hours.quantize(D("0.000001"), rounding=ROUND_HALF_UP))
    result["capital_hours_usdt"] = str(capital_hours.quantize(D("0.000001"), rounding=ROUND_HALF_UP))
    result["harvest_efficiency_usdt_per_1000_capital_hours"] = str(
        efficiency.quantize(D("0.00000001"), rounding=ROUND_HALF_UP)
    )
    return result


class TrendQualityPromotionEngine(v7.PrePartialOneRPromotionEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.candidate = CANDIDATE
        self.factor = CANDIDATE
        self.mode = "v7_trend_quality_promotion_liquidity_harvest_v8"
        self.promotion_decision_records: list[dict] = []
        self.rejected_same_hour_partial_count = 0

    def _fill_buy(self, bar: base.Bar) -> None:
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) == before:
            return
        state = self.lot_state[self.lots[-1].fill_time]
        state["promotion_decided"] = False
        state["promotion_qualified"] = False
        state["promotion_rejected"] = False

    def _trend_quality(self) -> dict:
        points = list(self.daily_points)
        if len(points) < 15:
            return {
                "inputs_complete_pass": False,
                "reason": "INSUFFICIENT_15_COMPLETE_DAILY_POINTS",
                "qualification_pass": False,
            }
        current = points[-1]
        prior = points[-2]
        close_t7 = points[-8].close
        close_t14 = points[-15].close
        recent = current.close / close_t7 - D("1")
        previous = close_t7 / close_t14 - D("1")
        acceleration = recent > ZERO and recent > previous
        close_above_ema20 = current.close > current.ema20
        ema20_slope_up = current.ema20 > prior.ema20
        qualification = acceleration and close_above_ema20 and ema20_slope_up
        return {
            "inputs_complete_pass": True,
            "complete_daily_time": current.day.isoformat(),
            "recent_7d_return": str(recent),
            "prior_7d_return": str(previous),
            "momentum_acceleration_pass": acceleration,
            "complete_daily_close": str(current.close),
            "causal_daily_ema20": str(current.ema20),
            "prior_causal_daily_ema20": str(prior.ema20),
            "close_above_daily_ema20_pass": close_above_ema20,
            "daily_ema20_slope_up_pass": ema20_slope_up,
            "qualification_pass": qualification,
        }

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            if state["route"] == "FULL_V2A" or state["promoted"] or state["partial_done"]:
                self._queue_full_v2a(lot, bar)
                continue

            current_full_pnl = base.money(
                base.estimated_net(state["original_quantity"], bar.close) - ORIGINAL_COST
            )
            previous_peak = state["peak_full_net_pnl"]
            peak = current_full_pnl if previous_peak is None else max(previous_peak, current_full_pnl)
            state["peak_full_net_pnl"] = peak
            threshold = state["entry_risk_1r"]
            first_crossing = (
                not state["promotion_decided"]
                and peak >= threshold
                and (previous_peak is None or previous_peak < threshold)
            )

            if first_crossing:
                partial_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
                partial_pnl = base.money(
                    base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
                )
                ema_partial_would_pass = (
                    self.hourly_ema5 is not None
                    and bar.close < self.hourly_ema5
                    and partial_pnl > ZERO
                )
                trend = self._trend_quality()
                qualified = trend["qualification_pass"]
                state["promotion_decided"] = True
                state["promotion_qualified"] = qualified
                state["promotion_rejected"] = not qualified
                decision = {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "decision_time": bar.open_time.isoformat(),
                    "entry_atr14": str(lot.entry_atr),
                    "original_quantity": str(state["original_quantity"]),
                    "entry_risk_1r_usdt": str(base.money(threshold)),
                    "previous_peak_full_net_pnl_usdt": (
                        None if previous_peak is None else str(previous_peak)
                    ),
                    "current_full_net_pnl_usdt": str(current_full_pnl),
                    "peak_full_net_pnl_usdt": str(peak),
                    "first_crossing_pass": True,
                    "threshold_pass": peak >= threshold,
                    "partial_done_before_decision": state["partial_done"],
                    "same_hour_ema5_partial_would_pass": ema_partial_would_pass,
                    "decision": "PROMOTE_FULL_V2A" if qualified else "REJECT_KEEP_V3C_HARVEST",
                    **trend,
                }
                self.promotion_decision_records.append(decision)

                if qualified:
                    if ema_partial_would_pass:
                        self.same_hour_partial_prevented_count += 1
                    state["promoted"] = True
                    state["promoted_at"] = bar.open_time
                    lot.highest_close = bar.close
                    lot.ratchet_stop = None
                    self.promotion_records.append(
                        {
                            "signal_time": lot.signal_time.isoformat(),
                            "fill_time": lot.fill_time.isoformat(),
                            "promotion_time": bar.open_time.isoformat(),
                            "entry_atr14": str(lot.entry_atr),
                            "original_quantity": str(state["original_quantity"]),
                            "entry_risk_1r_usdt": str(base.money(threshold)),
                            "previous_peak_full_net_pnl_usdt": (
                                None if previous_peak is None else str(previous_peak)
                            ),
                            "current_full_net_pnl_usdt": str(current_full_pnl),
                            "peak_full_net_pnl_usdt": str(peak),
                            "first_crossing_pass": True,
                            "threshold_pass": peak >= threshold,
                            "partial_done_before_promotion": state["partial_done"],
                            "same_hour_ema5_partial_would_pass": ema_partial_would_pass,
                            "hourly_close": str(bar.close),
                            "causal_hourly_ema5": (
                                None if self.hourly_ema5 is None else str(self.hourly_ema5)
                            ),
                        }
                    )
                    self._queue_full_v2a(lot, bar)
                    continue

            if self.hourly_ema5 is None or bar.close >= self.hourly_ema5:
                continue
            partial_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
            partial_pnl = base.money(
                base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
            )
            condition_pass = (
                not state["promoted"]
                and bar.close < self.hourly_ema5
                and partial_pnl > ZERO
                and not state["partial_done"]
            )
            if not condition_pass:
                continue
            if first_crossing and state["promotion_rejected"]:
                self.rejected_same_hour_partial_count += 1
            lot.exit_queued_at = bar.open_time
            self.queue_kind_by_fill[lot.fill_time] = "PARTIAL_24"
            state["partial_queued"] = True
            self._count_trigger("V8_NONPROMOTED_NET_POSITIVE_HOURLY_CLOSE_BELOW_EMA5_PARTIAL_24")
            self.partial_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "entry_risk_1r_usdt": str(base.money(threshold)),
                    "peak_full_net_pnl_usdt": str(peak),
                    "below_1r_at_queue_pass": peak < threshold,
                    "promotion_decided": state["promotion_decided"],
                    "promotion_rejected": state["promotion_rejected"],
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "estimated_partial_net_pnl_usdt": str(partial_pnl),
                    "one_r_wait_required": False,
                    "condition_pass": condition_pass,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "v7_trend_quality_promotion_liquidity_harvest_v8"
        result["candidate"] = CANDIDATE
        result["factor"] = CANDIDATE
        audit = result["conditional_partial_audit"]
        decisions = self.promotion_decision_records
        qualified = [row for row in decisions if row["qualification_pass"]]
        rejected = [row for row in decisions if not row["qualification_pass"]]
        decision_fills = [row["fill_time"] for row in decisions]
        promoted_fills = {row["fill_time"] for row in self.promotion_records}
        rejected_fills = {row["fill_time"] for row in rejected}
        partial_after_rejection = [
            row for row in self.partial_queue_records
            if row["fill_time"] in rejected_fills
        ]
        promotion_audit = {
            "decision_count": len(decisions),
            "qualified_promotion_count": len(qualified),
            "rejected_promotion_count": len(rejected),
            "promotion_share_of_partial_eligible_pct": (
                round(
                    len(qualified) * 100
                    / audit["entry_route_counts"]["PARTIAL_ELIGIBLE"],
                    6,
                )
                if audit["entry_route_counts"]["PARTIAL_ELIGIBLE"] else 0.0
            ),
            "rejected_same_hour_partial_count": self.rejected_same_hour_partial_count,
            "qualified_same_hour_partial_prevented_count": self.same_hour_partial_prevented_count,
            "partial_after_rejected_promotion_count": len(partial_after_rejection),
            "rejected_by_runner_quota_or_tiebreak": 0,
            "unique_first_decision_per_lot_pass": len(decision_fills) == len(set(decision_fills)),
            "all_decisions_first_crossing_pass": all(row["first_crossing_pass"] for row in decisions),
            "all_decisions_threshold_pass": all(row["threshold_pass"] for row in decisions),
            "all_decisions_pre_partial_pass": all(not row["partial_done_before_decision"] for row in decisions),
            "all_decision_inputs_complete_pass": all(row["inputs_complete_pass"] for row in decisions),
            "all_promotions_four_factor_quality_pass": all(
                row["momentum_acceleration_pass"]
                and row["close_above_daily_ema20_pass"]
                and row["daily_ema20_slope_up_pass"]
                for row in qualified
            ),
            "all_qualified_decisions_promoted_pass": (
                {row["fill_time"] for row in qualified} == promoted_fills
            ),
            "all_rejected_decisions_never_promoted_pass": promoted_fills.isdisjoint(rejected_fills),
            "all_rejected_lots_remain_no_1r_wait_pass": all(
                row["one_r_wait_required"] is False for row in partial_after_rejection
            ),
            "same_hour_ordering_pass": (
                self.same_hour_partial_prevented_count
                == sum(row["same_hour_ema5_partial_would_pass"] for row in qualified)
            ),
            "no_entry_block_or_resize_by_promotion_pass": True,
            "decision_records": decisions,
        }
        audit["promotion_formula"] = (
            "FIRST_1R_CROSS_AND_7D_ACCEL_AND_DAILY_CLOSE_ABOVE_EMA20_AND_DAILY_EMA20_SLOPE_UP"
        )
        audit["partial_trigger_formula"] = (
            "NONPROMOTED_NET_POSITIVE_AND_HOURLY_CLOSE_BELOW_CAUSAL_EMA5"
        )
        audit["one_r_wait_required_for_nonpromoted"] = False
        audit["trend_quality_promotion_audit"] = promotion_audit
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v7_specification_sha256": file_sha256(V7_SPEC_PATH),
        "v7_dependency_sha256": file_sha256(Path(v7.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v7_specification_sha256": EXPECTED_V7_SPEC_SHA256,
        "v7_dependency_sha256": EXPECTED_V7_RUNNER_SHA256,
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
    *,
    cap: D = base.REFERENCE_CAP,
) -> dict:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = TrendQualityPromotionEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return add_harvest_metrics(engine.result(trading[-1], start, end), window)


def add_baseline_harvest_metrics(baselines: dict) -> None:
    for key in ("v1", "v2a"):
        add_harvest_metrics(baselines[key]["design"], DESIGN)
        add_harvest_metrics(baselines[key]["validation"], VALIDATION)
        if "folds" in baselines[key]:
            for name, window in FOLDS.items():
                add_harvest_metrics(baselines[key]["folds"][name], window)


def reproduce_v7_checkpoint(bars: list[base.Bar]) -> dict:
    baselines = v7.reproduce_v6_checkpoint(bars)
    add_baseline_harvest_metrics(baselines)
    validation = add_harvest_metrics(v7.simulate_candidate(bars, VALIDATION), VALIDATION)
    folds = {
        name: add_harvest_metrics(v7.simulate_candidate(bars, window), window)
        for name, window in FOLDS.items()
    }
    total_wins = sum(
        base.dec(folds[name], "total_pnl_usdt")
        > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        folds[name]["median_hold_hours"]
        < baselines["v1"]["folds"][name]["median_hold_hours"]
        for name in FOLDS
    )
    audit = validation["conditional_partial_audit"]
    promotion = audit["pre_partial_one_r_promotion_audit"]
    actual_audit = (
        audit["entry_route_counts"]["FULL_V2A"],
        audit["entry_route_counts"]["PARTIAL_ELIGIBLE"],
        audit["partial_fill_count"],
        audit["direct_full_v2a_fill_count"],
        audit["remainder_v2a_fill_count"],
        promotion["promotion_count"],
        promotion["promoted_completed_lots"],
        promotion["nonpromoted_partial_fill_count"],
        promotion["same_hour_partial_prevented_count"],
    )
    checks = {
        "v7_validation": (base.checkpoint_tuple(validation), EXPECTED_V7_VALIDATION),
        "v7_promotion_audit": (actual_audit, EXPECTED_V7_AUDIT),
        "v7_annual_wins": ((total_wins, hold_wins), EXPECTED_V7_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["v7_pre_partial_one_r_promotion"] = {
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
    }
    return baselines


def candidate_gates(
    result: dict,
    v1: dict,
    total_wins: int,
    efficiency_wins: int,
) -> dict[str, bool]:
    audit = result["conditional_partial_audit"]
    promotion = audit["trend_quality_promotion_audit"]
    return {
        "validation_total_at_least_v1": base.dec(result, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "validation_realized_at_least_v1": base.dec(result, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "validation_unrealized_no_worse_than_v1": base.dec(result, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "validation_drawdown_no_higher_than_v1": base.dec(result, "max_drawdown_pct") <= D("7.121498"),
        "validation_cost_weighted_median_at_most_200h": result["median_hold_hours"] is not None and D(str(result["median_hold_hours"])) <= D("200"),
        "validation_cost_weighted_p90_at_most_1000h": result["p90_hold_hours"] is not None and D(str(result["p90_hold_hours"])) <= D("1000"),
        "validation_turnover_at_least_v1": base.dec(result, "turnover_usdt") >= base.dec(v1, "turnover_usdt"),
        "validation_harvest_efficiency_greater_than_v1": (
            D(result["harvest_efficiency_usdt_per_1000_capital_hours"])
            > D(v1["harvest_efficiency_usdt_per_1000_capital_hours"])
        ),
        "annual_harvest_efficiency_wins_at_least_3_of_5": efficiency_wins >= 3,
        "annual_total_wins_at_least_2_of_5": total_wins >= 2,
        "unique_first_decision_per_lot": promotion["unique_first_decision_per_lot_pass"],
        "all_decisions_first_crossing": promotion["all_decisions_first_crossing_pass"],
        "all_decisions_threshold": promotion["all_decisions_threshold_pass"],
        "all_decisions_pre_partial": promotion["all_decisions_pre_partial_pass"],
        "all_decision_inputs_complete": promotion["all_decision_inputs_complete_pass"],
        "all_promotions_four_factor_quality": promotion["all_promotions_four_factor_quality_pass"],
        "all_qualified_decisions_promoted": promotion["all_qualified_decisions_promoted_pass"],
        "all_rejected_decisions_never_promoted": promotion["all_rejected_decisions_never_promoted_pass"],
        "all_rejected_lots_remain_no_1r_wait": promotion["all_rejected_lots_remain_no_1r_wait_pass"],
        "same_hour_ordering": promotion["same_hour_ordering_pass"],
        "no_quota_or_tiebreak_rejection": promotion["rejected_by_runner_quota_or_tiebreak"] == 0,
        "all_entry_routes_complete": audit["all_entry_routes_complete_pass"],
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
        "no_entry_block_or_resize_by_promotion": promotion["no_entry_block_or_resize_by_promotion_pass"],
    }


def freeze_hash(data_sha: str, hashes: dict[str, str], runner_sha: str) -> str:
    return json_hash(
        {
            "research_identity": RESEARCH_IDENTITY,
            "candidate": CANDIDATE,
            "selection_data_sha256": data_sha,
            **hashes,
            "runner_sha256": runner_sha,
        }
    )


def run_preselect(output: Path) -> dict:
    artifact_hashes = verify_preregistration_artifacts()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": SELECTION_ROWS,
                "actual_rows": len(bars),
                "expected_sha256": SELECTION_SHA256,
                "actual_sha256": digest,
            },
        )
    baselines = reproduce_v7_checkpoint(bars)
    design = simulate_candidate(bars, DESIGN)
    validation = simulate_candidate(bars, VALIDATION)
    folds = {name: simulate_candidate(bars, window) for name, window in FOLDS.items()}
    total_wins = sum(
        base.dec(folds[name], "total_pnl_usdt")
        > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        folds[name]["median_hold_hours"]
        < baselines["v1"]["folds"][name]["median_hold_hours"]
        for name in FOLDS
    )
    efficiency_wins = sum(
        D(folds[name]["harvest_efficiency_usdt_per_1000_capital_hours"])
        > D(baselines["v1"]["folds"][name]["harvest_efficiency_usdt_per_1000_capital_hours"])
        for name in FOLDS
    )
    gates = candidate_gates(
        validation,
        baselines["v1"]["validation"],
        total_wins,
        efficiency_wins,
    )
    passed = all(gates.values())
    runner_sha = source_hash()
    candidate_result = {
        "candidate": CANDIDATE,
        "design": design,
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
        "annual_harvest_efficiency_wins": efficiency_wins,
        "gates": gates,
        "pass": passed,
    }
    result = {
        "status": "CANDIDATE_FROZEN" if passed else "NO_CANDIDATE_KEEP_DRA_V1",
        "selection_decision": "CANDIDATE_FROZEN" if passed else "NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_THROUGH_V7_PRE_PARTIAL_ONE_R_PROMOTION",
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "qualified_count": 1 if passed else 0,
        "one_slot_overlay": None,
    }
    if passed:
        result["frozen_candidate_key"] = CANDIDATE
        result["freeze_sha256"] = freeze_hash(digest, artifact_hashes, runner_sha)
        result["one_slot_overlay"] = {
            "design": simulate_candidate(bars, DESIGN, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, cap=base.LOT_COST),
            "folds": {
                name: simulate_candidate(bars, fold, cap=base.LOT_COST)
                for name, fold in FOLDS.items()
            },
        }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidate_result", "one_slot_overlay"}
    return {key: value for key, value in result.items() if key not in omitted}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("preselect",))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_preselect(args.output)
    except base.ResearchReject as reject:
        result = {
            "status": reject.status,
            "research_identity": RESEARCH_IDENTITY,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
            "detail": reject.detail,
        }
        base.write_json(args.output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] == "CANDIDATE_FROZEN" else 2


if __name__ == "__main__":
    raise SystemExit(main())
