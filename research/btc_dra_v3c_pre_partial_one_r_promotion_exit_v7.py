#!/usr/bin/env python3
"""Causal DRA V3C pre-partial 1R promotion research with sealed OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_post_entry_breakout_promotion_partial_exit_v6 as v6

v3c = v6.v3c
base = v6.base
D = Decimal
ZERO = D("0")
FOUR = D("4")
FIVE = D("5")
ORIGINAL_COST = D("30.00")
PARTIAL_COST = D("24.00")

RESEARCH_IDENTITY = "BTC_DRA_V3C_PRE_PARTIAL_ONE_R_PROMOTION_EXIT_V7_RESEARCH"
CANDIDATE = "V3C_NONSTRONG_PRE_PARTIAL_1R_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md"
V6_SPEC_PATH = ROOT / "docs" / "btc-dra-post-entry-breakout-promotion-partial-exit-v6-research.md"
EXPECTED_SPEC_SHA256 = "b4034444510411a5e45681f5a9b12744e072bfee0b14e94842a09e5d9ee7be79"
EXPECTED_V6_SPEC_SHA256 = "7e2245eb6478a9b3a5708d7b5758ae6e89424c7282ab7836742b40b050a5b130"
EXPECTED_V6_RUNNER_SHA256 = "03babd93d146318ec779031bf5b5623655bf62d4430ff0f94a122c8208882697"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V6_VALIDATION = (
    "63.80546411", "-0.64164024", "63.16382387", "4.273596", 45.0,
    711.0, 51, 96, 1, 0, "8.821888", "1587.80546411",
)
EXPECTED_V6_AUDIT = (0, 51, 46, 5, 45, 38, 5, 1, 46)
EXPECTED_V6_WINS = (1, 5)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def json_hash(value: object) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


class PrePartialOneRPromotionEngine(v3c.NetPositiveEmaPartialEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.candidate = CANDIDATE
        self.factor = CANDIDATE
        self.mode = "v3c_pre_partial_one_r_promotion_v7"
        self.promotion_records: list[dict] = []
        self.same_hour_partial_prevented_count = 0

    def _fill_buy(self, bar: base.Bar) -> None:
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) == before:
            return
        lot = self.lots[-1]
        state = self.lot_state[lot.fill_time]
        state["promoted"] = False
        state["promoted_at"] = None

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
            if not state["promoted"] and peak >= threshold:
                partial_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
                partial_pnl = base.money(
                    base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
                )
                ema_partial_would_pass = (
                    self.hourly_ema5 is not None
                    and bar.close < self.hourly_ema5
                    and partial_pnl > ZERO
                )
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
                        "first_crossing_pass": previous_peak is None or previous_peak < threshold,
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
                and peak < threshold
                and bar.close < self.hourly_ema5
                and partial_pnl > ZERO
                and not state["partial_done"]
            )
            if not condition_pass:
                if partial_pnl > ZERO:
                    self.partial_condition_violations += 1
                continue
            lot.exit_queued_at = bar.open_time
            self.queue_kind_by_fill[lot.fill_time] = "PARTIAL_24"
            state["partial_queued"] = True
            self._count_trigger("V3C_BELOW_1R_NET_POSITIVE_HOURLY_CLOSE_BELOW_EMA5_PARTIAL_24")
            self.partial_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "entry_risk_1r_usdt": str(base.money(threshold)),
                    "peak_full_net_pnl_usdt": str(peak),
                    "below_1r_at_queue_pass": peak < threshold,
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "estimated_partial_net_pnl_usdt": str(partial_pnl),
                    "one_r_wait_required": False,
                    "condition_pass": condition_pass,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "v3c_pre_partial_one_r_promotion_v7"
        result["candidate"] = CANDIDATE
        result["factor"] = CANDIDATE
        audit = result["conditional_partial_audit"]
        promoted_states = [state for state in self.lot_state.values() if state["promoted"]]
        promotion_fill_times = [row["fill_time"] for row in self.promotion_records]
        promotion_delays = [
            (
                datetime.fromisoformat(row["promotion_time"])
                - datetime.fromisoformat(row["fill_time"])
            ).total_seconds() / 3600
            for row in self.promotion_records
        ]
        promotion_counts_by_hour: dict[str, int] = {}
        for row in self.promotion_records:
            hour = row["promotion_time"]
            promotion_counts_by_hour[hour] = promotion_counts_by_hour.get(hour, 0) + 1
        promotion_audit = {
            "promotion_count": len(self.promotion_records),
            "promotion_share_of_partial_eligible_pct": (
                round(
                    len(self.promotion_records) * 100
                    / audit["entry_route_counts"]["PARTIAL_ELIGIBLE"],
                    6,
                )
                if audit["entry_route_counts"]["PARTIAL_ELIGIBLE"] else 0.0
            ),
            "maximum_same_hour_promotions": max(promotion_counts_by_hour.values(), default=0),
            "same_hour_partial_prevented_count": self.same_hour_partial_prevented_count,
            "rejected_by_runner_quota_or_tiebreak": 0,
            "promoted_completed_lots": sum(state["completed_at"] is not None for state in promoted_states),
            "promoted_open_lots": sum(state["completed_at"] is None for state in promoted_states),
            "nonpromoted_partial_fill_count": sum(
                state["partial_fill_count"]
                for state in self.lot_state.values()
                if not state["promoted"]
            ),
            "median_promotion_delay_hours": base.percentile(promotion_delays, 0.5),
            "p90_promotion_delay_hours": base.percentile(promotion_delays, 0.9),
            "unique_promotion_per_lot_pass": len(promotion_fill_times) == len(set(promotion_fill_times)),
            "all_promotions_first_crossing_pass": all(
                row["first_crossing_pass"] for row in self.promotion_records
            ),
            "all_promotions_threshold_pass": all(
                row["threshold_pass"] for row in self.promotion_records
            ),
            "all_promotions_pre_partial_pass": all(
                not row["partial_done_before_promotion"] for row in self.promotion_records
            ),
            "no_promoted_lot_partial_fill_pass": all(
                state["partial_fill_count"] == 0 for state in promoted_states
            ),
            "all_nonpromoted_partial_queues_below_1r_pass": all(
                row["below_1r_at_queue_pass"] for row in self.partial_queue_records
            ),
            "same_hour_promotion_precedence_pass": (
                self.same_hour_partial_prevented_count
                == sum(
                    row["same_hour_ema5_partial_would_pass"]
                    for row in self.promotion_records
                )
            ),
            "no_nonpromoted_one_r_wait_pass": all(
                row["one_r_wait_required"] is False
                for row in self.partial_queue_records
            ),
            "no_entry_block_or_resize_by_promotion_pass": True,
            "promotion_counts_by_hour": {
                hour: promotion_counts_by_hour[hour]
                for hour in sorted(promotion_counts_by_hour)
            },
            "promotion_records": self.promotion_records,
        }
        audit["promotion_formula"] = "V3C_PARTIAL_ELIGIBLE_FIRST_PEAK_NET_PNL_GE_ENTRY_ATR_TIMES_ORIGINAL_QUANTITY"
        audit["partial_trigger_formula"] = "NONPROMOTED_BELOW_1R_AND_NET_POSITIVE_AND_HOURLY_CLOSE_BELOW_CAUSAL_EMA5"
        audit["one_r_wait_required_for_nonpromoted"] = False
        audit["pre_partial_one_r_promotion_audit"] = promotion_audit
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v6_specification_sha256": file_sha256(V6_SPEC_PATH),
        "v6_dependency_sha256": file_sha256(Path(v6.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v6_specification_sha256": EXPECTED_V6_SPEC_SHA256,
        "v6_dependency_sha256": EXPECTED_V6_RUNNER_SHA256,
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
    engine = PrePartialOneRPromotionEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def reproduce_v6_checkpoint(bars: list[base.Bar]) -> dict:
    baselines = v6.reproduce_v5_checkpoint(bars)
    validation = v6.simulate_candidate(bars, VALIDATION)
    folds = {name: v6.simulate_candidate(bars, window) for name, window in FOLDS.items()}
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
    promotion = audit["post_entry_promotion_audit"]
    actual_audit = (
        audit["entry_route_counts"]["FULL_V2A"],
        audit["entry_route_counts"]["PARTIAL_ELIGIBLE"],
        audit["partial_fill_count"],
        audit["direct_full_v2a_fill_count"],
        audit["remainder_v2a_fill_count"],
        promotion["fresh_breakout_events"],
        promotion["promotion_count"],
        promotion["maximum_same_event_promotions"],
        promotion["unpromoted_partial_fill_count"],
    )
    checks = {
        "v6_validation": (base.checkpoint_tuple(validation), EXPECTED_V6_VALIDATION),
        "v6_promotion_audit": (actual_audit, EXPECTED_V6_AUDIT),
        "v6_annual_wins": ((total_wins, hold_wins), EXPECTED_V6_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["post_entry_breakout_promotion_partial_v6"] = {
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
    }
    return baselines


def candidate_gates(result: dict, v1: dict, v2a: dict, total_wins: int, hold_wins: int) -> dict[str, bool]:
    audit = result["conditional_partial_audit"]
    promotion = audit["pre_partial_one_r_promotion_audit"]
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
        "unique_promotion_per_lot": promotion["unique_promotion_per_lot_pass"],
        "all_promotions_first_crossing": promotion["all_promotions_first_crossing_pass"],
        "all_promotions_threshold": promotion["all_promotions_threshold_pass"],
        "all_promotions_pre_partial": promotion["all_promotions_pre_partial_pass"],
        "no_promoted_lot_partial_fill": promotion["no_promoted_lot_partial_fill_pass"],
        "all_nonpromoted_partial_queues_below_1r": promotion["all_nonpromoted_partial_queues_below_1r_pass"],
        "same_hour_promotion_precedence": promotion["same_hour_promotion_precedence_pass"],
        "no_nonpromoted_one_r_wait": promotion["no_nonpromoted_one_r_wait_pass"],
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
    if output.exists():
        raise base.ResearchReject("PRESELECTION_REJECT", f"output already exists: {output}")
    artifact_hashes = verify_preregistration_artifacts()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {"expected_rows": SELECTION_ROWS, "actual_rows": len(bars), "expected_sha256": SELECTION_SHA256, "actual_sha256": digest},
        )
    baselines = reproduce_v6_checkpoint(bars)
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
    gates = candidate_gates(
        validation,
        baselines["v1"]["validation"],
        baselines["v2a"]["validation"],
        total_wins,
        hold_wins,
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
        "baseline_parity": "PASS_THROUGH_POST_ENTRY_BREAKOUT_PROMOTION_PARTIAL_V6",
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


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    promotion = candidate["conditional_partial_audit"]["pre_partial_one_r_promotion_audit"]
    return {
        "oos_total_at_least_v1": base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "oos_total_retains_90pct_v2a": base.dec(candidate, "total_pnl_usdt") >= base.dec(v2a, "total_pnl_usdt") * D("0.90"),
        "oos_realized_at_least_v1": base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "oos_unrealized_no_worse": base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "oos_drawdown_within_v1_plus_2pp": base.dec(candidate, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct") + D("2"),
        "oos_cost_weighted_median_no_worse": candidate["median_hold_hours"] <= v1["median_hold_hours"],
        "oos_cost_weighted_p90_no_worse": candidate["p90_hold_hours"] <= v1["p90_hold_hours"],
        "promotion_audit": all(
            (
                promotion["unique_promotion_per_lot_pass"],
                promotion["all_promotions_first_crossing_pass"],
                promotion["all_promotions_threshold_pass"],
                promotion["all_promotions_pre_partial_pass"],
                promotion["no_promoted_lot_partial_fill_pass"],
                promotion["all_nonpromoted_partial_queues_below_1r_pass"],
                promotion["same_hour_promotion_precedence_pass"],
                promotion["no_nonpromoted_one_r_wait_pass"],
                promotion["rejected_by_runner_quota_or_tiebreak"] == 0,
            )
        ),
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
    if preselection.get("frozen_candidate_key") != CANDIDATE:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate key mismatch")
    expected_freeze = freeze_hash(SELECTION_SHA256, artifact_hashes, runner_sha)
    if preselection.get("freeze_sha256") != expected_freeze:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    if cutoff <= SELECTION_CUTOFF:
        raise base.ResearchReject("OOS_SEAL_REJECT", "cutoff must be after 2025-01-01")
    bars = base.parse_rows(base.fetch_rows(cutoff))
    available_end = bars[-1].close_time
    window = (SELECTION_CUTOFF, available_end)
    v1 = base.simulate(bars, window, "v1")
    v2a = base.simulate(bars, window, "v2a")
    candidate = simulate_candidate(bars, window)
    gates = oos_gates(candidate, v1, v2a)
    result = {
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "frozen_candidate_key": CANDIDATE,
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_requested_cutoff": cutoff.isoformat(),
        "oos_last_complete_close": available_end.isoformat(),
        "full_data_rows": len(bars),
        "full_data_sha256": base.data_hash(bars),
        "oos": {
            "v1_reference_250": v1,
            "v2a_reference_250": v2a,
            "candidate_reference_250": candidate,
            "gates": gates,
        },
        "one_slot_overlay_30": {
            "design": simulate_candidate(bars, DESIGN, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, cap=base.LOT_COST),
            "folds": {
                name: simulate_candidate(bars, fold, cap=base.LOT_COST)
                for name, fold in FOLDS.items()
            },
            "oos": simulate_candidate(bars, window, cap=base.LOT_COST),
        },
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidate_result", "one_slot_overlay", "oos", "one_slot_overlay_30"}
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
        if not output.exists():
            base.write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
