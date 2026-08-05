#!/usr/bin/env python3
"""Causal V7 profit-peak delayed failed-reclaim exit V12 research."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import btc_dra_v3c_pre_partial_one_r_promotion_exit_v7 as v7

base = v7.base
D = Decimal
ZERO = D("0")
HUNDRED = D("100")
THOUSAND = D("1000")

RESEARCH_IDENTITY = "BTC_DRA_V7_PROFIT_PEAK_DELAYED_FAILED_RECLAIM_EXIT_V12_RESEARCH"
CANDIDATE_WAITS = {
    "POST_1R_PRIOR24_LOW_BREAK_12H_FAILED_RECLAIM": 12,
    "POST_1R_PRIOR24_LOW_BREAK_24H_FAILED_RECLAIM": 24,
}
CANDIDATES = tuple(CANDIDATE_WAITS)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-v7-profit-peak-delayed-failed-reclaim-exit-v12-research.md"
MANIFEST_PATH = ROOT / "research" / "btc_dra_v7_profit_peak_delayed_failed_reclaim_exit_v12_manifest.json"
V7_SPEC_PATH = ROOT / "docs" / "btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md"
EXPECTED_SPEC_SHA256 = "5facd8e6f9f4d2cc177e4a53cb911f3b20cdf0cdb74fc1ffa64e93174120a168"
EXPECTED_MANIFEST_SHA256 = "12dfaa2adf69513f516737e3a85076a25a3b66dd2f34b3713dacb8be3c8ae8ff"
EXPECTED_V7_SPEC_SHA256 = "b4034444510411a5e45681f5a9b12744e072bfee0b14e94842a09e5d9ee7be79"
EXPECTED_V7_RUNNER_SHA256 = "9441ff63db551d5105082387822f7a4ccdcd01e247ad86c6db5382d6df21d532"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V7_DESIGN_TOTAL = D("94.90277533")
EXPECTED_V7_VALIDATION = (
    "96.02789691", "-0.64164024", "95.38625667", "6.832349", 192.0,
    836.0, 51, 75, 1, 0, "15.821888", "1620.02789691",
)
EXPECTED_V7_AUDIT = (18, 33, 25, 26, 24, 8, 8, 25, 0)
EXPECTED_V7_WINS = (2, 3)
V9_MANAGER_FILLS = {"design": 27, "validation": 23}


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
    result["capital_hours_usdt"] = str(
        capital_hours.quantize(D("0.000001"), rounding=ROUND_HALF_UP)
    )
    result["harvest_efficiency_usdt_per_1000_capital_hours"] = str(
        efficiency.quantize(D("0.00000001"), rounding=ROUND_HALF_UP)
    )
    return result


class DelayedFailedReclaimEngine(v7.PrePartialOneRPromotionEngine):
    def __init__(self, candidate: str, *, cap: D = base.REFERENCE_CAP) -> None:
        if candidate not in CANDIDATES:
            raise ValueError(candidate)
        super().__init__(cap=cap)
        self.candidate = candidate
        self.factor = candidate
        self.mode = "v7_profit_peak_delayed_failed_reclaim_exit_v12"
        self.wait_hours = CANDIDATE_WAITS[candidate]
        self.prior_hourly_lows: deque[D] = deque(maxlen=24)
        self.current_prior24_low: D | None = None
        self.current_prior24_lows: list[D] | None = None
        self.manager_arm_records: list[dict] = []
        self.attempt_records: list[dict] = []
        self.classification_records: list[dict] = []
        self.manager_queue_records: list[dict] = []
        self.manager_fill_records: list[dict] = []
        self.manager_deferred_fill_records: list[dict] = []
        self.manager_input_unavailable_count = 0
        self.overlapping_attempt_reject_count = 0

    def _indicators(self, bar: base.Bar) -> None:
        if len(self.prior_hourly_lows) == 24:
            self.current_prior24_lows = list(self.prior_hourly_lows)
            self.current_prior24_low = min(self.current_prior24_lows)
        else:
            self.current_prior24_lows = None
            self.current_prior24_low = None
        super()._indicators(bar)
        self.prior_hourly_lows.append(bar.low)

    def _fill_buy(self, bar: base.Bar) -> None:
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) == before:
            return
        state = self.lot_state[self.lots[-1].fill_time]
        state["v12_manager_armed_at"] = None
        state["v12_manager_peak_net_pnl"] = None
        state["v12_manager_peak_close"] = None
        state["v12_active_attempt"] = None
        state["v12_pending_queue"] = None

    def _record_classification(
        self,
        lot: base.Lot,
        state: dict,
        bar: base.Bar,
        classification: str,
        current_pnl: D,
    ) -> dict:
        pending = state["v12_active_attempt"]
        if pending is None:
            raise base.ResearchReject("CAUSAL_AUDIT_REJECT", "classification without attempt")
        decision_expected = classification in {
            "CONFIRMED_POSITIVE",
            "CONFIRMED_NONPOSITIVE",
            "CANCELLED_NO_DOWN_CONFIRM",
        }
        row = {
            "candidate": self.candidate,
            "signal_time": lot.signal_time.isoformat(),
            "fill_time": lot.fill_time.isoformat(),
            "arm_time": pending["arm_time"].isoformat(),
            "break_time": pending["break_time"].isoformat(),
            "decision_time": pending["decision_time"].isoformat(),
            "classification_time": bar.open_time.isoformat(),
            "classification": classification,
            "break_level": str(pending["break_level"]),
            "break_close": str(pending["break_close"]),
            "classification_close": str(bar.close),
            "post_break_close_count": pending["post_break_close_count"],
            "post_break_max_close": (
                None if pending["post_break_max_close"] is None
                else str(pending["post_break_max_close"])
            ),
            "current_net_pnl_usdt": str(current_pnl),
            "classification_at_exact_decision_pass": (
                not decision_expected or bar.open_time == pending["decision_time"]
            ),
            "classification_not_after_decision_pass": (
                bar.open_time <= pending["decision_time"]
            ),
        }
        self.classification_records.append(row)
        state["v12_active_attempt"] = None
        return row

    def _queue_exits(self, bar: base.Bar) -> None:
        # Frozen V7 promotion and V2A ratchet always run first.
        super()._queue_exits(bar)

        for lot in self.lots:
            state = self.lot_state[lot.fill_time]
            runner_path = (
                not state["partial_done"]
                and (state["route"] == "FULL_V2A" or state["promoted"])
            )
            if not runner_path:
                continue

            one_r = state["entry_risk_1r"]
            if one_r is None or one_r <= ZERO:
                self.manager_input_unavailable_count += 1
                continue

            current_pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            previous_peak = state["v12_manager_peak_net_pnl"]
            peak = current_pnl if previous_peak is None else max(previous_peak, current_pnl)
            if previous_peak is None or current_pnl >= previous_peak:
                state["v12_manager_peak_close"] = bar.close
            state["v12_manager_peak_net_pnl"] = peak

            armed_at = state["v12_manager_armed_at"]
            if armed_at is None and peak >= one_r:
                state["v12_manager_armed_at"] = bar.open_time
                armed_at = bar.open_time
                self.manager_arm_records.append(
                    {
                        "signal_time": lot.signal_time.isoformat(),
                        "fill_time": lot.fill_time.isoformat(),
                        "arm_time": bar.open_time.isoformat(),
                        "route": state["route"],
                        "promoted": state["promoted"],
                        "one_r_usdt": str(base.money(one_r)),
                        "previous_peak_net_pnl_usdt": (
                            None if previous_peak is None else str(previous_peak)
                        ),
                        "current_net_pnl_usdt": str(current_pnl),
                        "peak_net_pnl_usdt": str(peak),
                        "peak_close": str(state["v12_manager_peak_close"]),
                        "first_crossing_pass": previous_peak is None or previous_peak < one_r,
                        "threshold_pass": peak >= one_r,
                    }
                )

            if armed_at is None or bar.open_time <= armed_at:
                continue

            pending = state["v12_active_attempt"]
            classified_this_bar = False

            # Parent V2A has precedence even while a V12 observation is open.
            if lot.exit_queued_at is not None:
                if pending is not None:
                    self._record_classification(
                        lot, state, bar, "PARENT_V2A_PRECEDENCE", current_pnl
                    )
                continue

            if pending is not None:
                if bar.open_time <= pending["break_time"]:
                    raise base.ResearchReject("CAUSAL_AUDIT_REJECT", "non-future attempt observation")
                pending["post_break_close_count"] += 1
                prior_max = pending["post_break_max_close"]
                pending["post_break_max_close"] = (
                    bar.close if prior_max is None else max(prior_max, bar.close)
                )

                if bar.close > pending["break_level"]:
                    self._record_classification(lot, state, bar, "RECLAIMED", current_pnl)
                    classified_this_bar = True
                elif bar.open_time == pending["decision_time"]:
                    if bar.close <= pending["break_close"]:
                        classification = (
                            "CONFIRMED_POSITIVE" if current_pnl > ZERO
                            else "CONFIRMED_NONPOSITIVE"
                        )
                    else:
                        classification = "CANCELLED_NO_DOWN_CONFIRM"
                    classification_row = self._record_classification(
                        lot, state, bar, classification, current_pnl
                    )
                    classified_this_bar = True
                    if classification == "CONFIRMED_POSITIVE":
                        lot.exit_queued_at = bar.open_time
                        self.queue_kind_by_fill[lot.fill_time] = "FULL_V2A"
                        state["v12_pending_queue"] = bar.open_time
                        self._count_trigger(f"V12_{self.candidate}")
                        queue_row = dict(classification_row)
                        queue_row.update(
                            {
                                "queue_time": bar.open_time.isoformat(),
                                "estimated_net_positive_pass": current_pnl > ZERO,
                                "strictly_after_arm_pass": bar.open_time > armed_at,
                                "parent_v2a_same_hour_queue": False,
                            }
                        )
                        self.manager_queue_records.append(queue_row)
                elif bar.open_time > pending["decision_time"]:
                    raise base.ResearchReject("CAUSAL_AUDIT_REJECT", "missed exact decision hour")

            if classified_this_bar or state["v12_active_attempt"] is not None:
                continue
            if self.current_prior24_low is None or self.current_prior24_lows is None:
                continue
            if bar.close >= self.current_prior24_low:
                continue

            decision_time = bar.open_time + timedelta(hours=self.wait_hours)
            attempt = {
                "arm_time": armed_at,
                "break_time": bar.open_time,
                "decision_time": decision_time,
                "break_level": self.current_prior24_low,
                "break_close": bar.close,
                "prior_24_lows": list(self.current_prior24_lows),
                "post_break_close_count": 0,
                "post_break_max_close": None,
            }
            if state["v12_active_attempt"] is not None:
                self.overlapping_attempt_reject_count += 1
                continue
            state["v12_active_attempt"] = attempt
            self.attempt_records.append(
                {
                    "candidate": self.candidate,
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "arm_time": armed_at.isoformat(),
                    "break_time": bar.open_time.isoformat(),
                    "decision_time": decision_time.isoformat(),
                    "break_level": str(self.current_prior24_low),
                    "break_close": str(bar.close),
                    "prior_24_lows": [str(value) for value in self.current_prior24_lows],
                    "prior_24_count": len(self.current_prior24_lows),
                    "fresh_break_pass": bar.close < self.current_prior24_low,
                    "strictly_after_arm_pass": bar.open_time > armed_at,
                }
            )

    def _fill_exits(self, bar: base.Bar) -> None:
        pending = {
            lot.fill_time: self.lot_state[lot.fill_time].get("v12_pending_queue")
            for lot in self.lots
            if self.lot_state[lot.fill_time].get("v12_pending_queue") is not None
        }
        fill_before = len(self.exit_fill_records)
        deferred_before = len(self.deferred_fill_records)
        super()._fill_exits(bar)

        for row in self.exit_fill_records[fill_before:]:
            fill_time = datetime.fromisoformat(row["fill_time"])
            if fill_time in pending:
                manager_row = dict(row)
                manager_row["manager_queue_time"] = pending[fill_time].isoformat()
                manager_row["candidate"] = self.candidate
                self.manager_fill_records.append(manager_row)
                self.lot_state[fill_time]["v12_pending_queue"] = None
        for row in self.deferred_fill_records[deferred_before:]:
            fill_time = datetime.fromisoformat(row["fill_time"])
            if fill_time in pending:
                manager_row = dict(row)
                manager_row["manager_queue_time"] = pending[fill_time].isoformat()
                manager_row["candidate"] = self.candidate
                self.manager_deferred_fill_records.append(manager_row)
                self.lot_state[fill_time]["v12_pending_queue"] = None

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "v7_profit_peak_delayed_failed_reclaim_exit_v12"
        result["candidate"] = self.candidate
        result["factor"] = self.candidate
        audit = result["conditional_partial_audit"]
        arm_fill_times = [row["fill_time"] for row in self.manager_arm_records]
        queue_delays = [
            (
                datetime.fromisoformat(row["queue_time"])
                - datetime.fromisoformat(row["arm_time"])
            ).total_seconds() / 3600
            for row in self.manager_queue_records
        ]
        classifications = [row["classification"] for row in self.classification_records]
        exact_decision_classes = {
            "CONFIRMED_POSITIVE", "CONFIRMED_NONPOSITIVE", "CANCELLED_NO_DOWN_CONFIRM"
        }
        confirmed = [
            row for row in self.classification_records
            if row["classification"] in {"CONFIRMED_POSITIVE", "CONFIRMED_NONPOSITIVE"}
        ]
        manager_audit = {
            "candidate": self.candidate,
            "wait_hours": self.wait_hours,
            "manager_arm_count": len(self.manager_arm_records),
            "attempt_count": len(self.attempt_records),
            "reclaimed_count": classifications.count("RECLAIMED"),
            "cancelled_no_down_confirm_count": classifications.count("CANCELLED_NO_DOWN_CONFIRM"),
            "confirmed_positive_count": classifications.count("CONFIRMED_POSITIVE"),
            "confirmed_nonpositive_count": classifications.count("CONFIRMED_NONPOSITIVE"),
            "parent_precedence_count": classifications.count("PARENT_V2A_PRECEDENCE"),
            "manager_queue_count": len(self.manager_queue_records),
            "manager_completed_exit_count": len(self.manager_fill_records),
            "manager_deferred_fill_count": len(self.manager_deferred_fill_records),
            "manager_input_unavailable_observations": self.manager_input_unavailable_count,
            "overlapping_attempt_reject_count": self.overlapping_attempt_reject_count,
            "unique_arm_per_lot_pass": len(arm_fill_times) == len(set(arm_fill_times)),
            "all_arms_first_crossing_pass": all(
                row["first_crossing_pass"] for row in self.manager_arm_records
            ),
            "all_arms_threshold_pass": all(
                row["threshold_pass"] for row in self.manager_arm_records
            ),
            "all_attempts_prior24_exact_pass": all(
                row["prior_24_count"] == 24
                and D(row["break_level"]) == min(D(value) for value in row["prior_24_lows"])
                and D(row["break_close"]) < D(row["break_level"])
                for row in self.attempt_records
            ),
            "all_attempts_strictly_after_arm_pass": all(
                row["strictly_after_arm_pass"] for row in self.attempt_records
            ),
            "one_active_attempt_per_lot_pass": self.overlapping_attempt_reject_count == 0,
            "all_decision_classifications_exact_pass": all(
                row["classification"] not in exact_decision_classes
                or (
                    row["classification_at_exact_decision_pass"]
                    and row["post_break_close_count"] == self.wait_hours
                )
                for row in self.classification_records
            ),
            "all_classifications_not_after_decision_pass": all(
                row["classification_not_after_decision_pass"]
                for row in self.classification_records
            ),
            "all_confirmations_formula_pass": all(
                row["post_break_max_close"] is not None
                and D(row["post_break_max_close"]) <= D(row["break_level"])
                and D(row["classification_close"]) <= D(row["break_close"])
                and row["post_break_close_count"] == self.wait_hours
                for row in confirmed
            ),
            "all_queues_confirmed_positive_pass": all(
                row["classification"] == "CONFIRMED_POSITIVE"
                and row["estimated_net_positive_pass"]
                for row in self.manager_queue_records
            ),
            "all_queues_strictly_after_arm_pass": all(
                row["strictly_after_arm_pass"] for row in self.manager_queue_records
            ),
            "parent_v2a_same_hour_precedence_pass": all(
                not row["parent_v2a_same_hour_queue"] for row in self.manager_queue_records
            ),
            "all_manager_exit_fills_positive_pass": all(
                D(row["realized_net_pnl_usdt"]) > ZERO for row in self.manager_fill_records
            ),
            "no_entry_block_resize_quota_or_promotion_veto_pass": True,
            "median_arm_to_queue_hours": base.percentile(queue_delays, 0.5),
            "p90_arm_to_queue_hours": base.percentile(queue_delays, 0.9),
            "manager_arm_records": self.manager_arm_records,
            "attempt_records": self.attempt_records,
            "classification_records": self.classification_records,
            "manager_queue_records": self.manager_queue_records,
            "manager_fill_records": self.manager_fill_records,
            "manager_deferred_fill_records": self.manager_deferred_fill_records,
        }
        audit["v12_manager_formula"] = self.candidate
        audit["v12_profit_peak_delayed_failed_reclaim_audit"] = manager_audit
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "formula_manifest_sha256": file_sha256(MANIFEST_PATH),
        "v7_specification_sha256": file_sha256(V7_SPEC_PATH),
        "v7_dependency_sha256": file_sha256(Path(v7.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "formula_manifest_sha256": EXPECTED_MANIFEST_SHA256,
        "v7_specification_sha256": EXPECTED_V7_SPEC_SHA256,
        "v7_dependency_sha256": EXPECTED_V7_RUNNER_SHA256,
    }
    problems = [
        {"artifact": key, "expected": expected[key], "actual": actual[key]}
        for key in expected if actual[key] != expected[key]
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
    engine = DelayedFailedReclaimEngine(candidate, cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return add_harvest_metrics(engine.result(trading[-1], start, end), window)


def simulate_v7(
    bars: list[base.Bar], window: tuple[datetime, datetime], *, cap: D = base.REFERENCE_CAP
) -> dict:
    return add_harvest_metrics(v7.simulate_candidate(bars, window, cap=cap), window)


def reproduce_parent_checkpoints(bars: list[base.Bar]) -> dict:
    inherited = v7.reproduce_v6_checkpoint(bars)
    for key in ("v1", "v2a"):
        add_harvest_metrics(inherited[key]["design"], DESIGN)
        add_harvest_metrics(inherited[key]["validation"], VALIDATION)
        if "folds" in inherited[key]:
            for name, window in FOLDS.items():
                add_harvest_metrics(inherited[key]["folds"][name], window)

    design = simulate_v7(bars, DESIGN)
    validation = simulate_v7(bars, VALIDATION)
    folds = {name: simulate_v7(bars, window) for name, window in FOLDS.items()}
    total_wins = sum(
        base.dec(folds[name], "total_pnl_usdt")
        > base.dec(inherited["v1"]["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        folds[name]["median_hold_hours"]
        < inherited["v1"]["folds"][name]["median_hold_hours"]
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
        "v7_design_total": (base.dec(design, "total_pnl_usdt"), EXPECTED_V7_DESIGN_TOTAL),
        "v7_validation": (base.checkpoint_tuple(validation), EXPECTED_V7_VALIDATION),
        "v7_promotion_audit": (actual_audit, EXPECTED_V7_AUDIT),
        "v7_annual_wins": ((total_wins, hold_wins), EXPECTED_V7_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items() if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    return {
        "v1": inherited["v1"],
        "v2a": inherited["v2a"],
        "v7": {
            "design": design,
            "validation": validation,
            "folds": folds,
            "annual_total_wins_vs_v1": total_wins,
            "annual_median_hold_wins_vs_v1": hold_wins,
        },
        "checkpoint_audit": {
            name: {"actual": list(actual) if isinstance(actual, tuple) else str(actual), "passed": True}
            for name, (actual, _) in checks.items()
        },
    }


def invariance_audit(candidate: dict, parent: dict) -> dict[str, bool]:
    audit = candidate["conditional_partial_audit"]
    parent_audit = parent["conditional_partial_audit"]
    manager = audit["v12_profit_peak_delayed_failed_reclaim_audit"]
    promotion = audit["pre_partial_one_r_promotion_audit"]
    candidate_routes = [
        {key: value for key, value in row.items() if key != "candidate"}
        for row in audit["entry_route_records"]
    ]
    parent_routes = [
        {key: value for key, value in row.items() if key != "candidate"}
        for row in parent_audit["entry_route_records"]
    ]
    return {
        "buy_count_equal_v7": candidate["buy_count"] == parent["buy_count"],
        "blocked_entries_equal_v7": candidate["blocked_entries"] == parent["blocked_entries"],
        "entry_routes_equal_v7": candidate_routes == parent_routes,
        "promotion_records_equal_v7": (
            promotion["promotion_records"]
            == parent_audit["pre_partial_one_r_promotion_audit"]["promotion_records"]
        ),
        "partial_queue_records_equal_v7": (
            audit["partial_queue_records"] == parent_audit["partial_queue_records"]
        ),
        "promotion_count_equal_v7": (
            promotion["promotion_count"]
            == parent_audit["pre_partial_one_r_promotion_audit"]["promotion_count"]
        ),
        "all_promotions_unconditional": (
            promotion["rejected_by_runner_quota_or_tiebreak"] == 0
            and promotion["no_entry_block_or_resize_by_promotion_pass"]
        ),
        "manager_unique_arms": manager["unique_arm_per_lot_pass"],
        "manager_first_crossings": manager["all_arms_first_crossing_pass"],
        "manager_thresholds": manager["all_arms_threshold_pass"],
        "attempt_prior24_exact": manager["all_attempts_prior24_exact_pass"],
        "attempts_strictly_after_arm": manager["all_attempts_strictly_after_arm_pass"],
        "one_active_attempt_per_lot": manager["one_active_attempt_per_lot_pass"],
        "decision_times_exact": manager["all_decision_classifications_exact_pass"],
        "classifications_not_late": manager["all_classifications_not_after_decision_pass"],
        "confirmed_formula_exact": manager["all_confirmations_formula_pass"],
        "queues_confirmed_positive": manager["all_queues_confirmed_positive_pass"],
        "queues_strictly_after_arm": manager["all_queues_strictly_after_arm_pass"],
        "parent_same_hour_precedence": manager["parent_v2a_same_hour_precedence_pass"],
        "manager_actual_fills_positive": manager["all_manager_exit_fills_positive_pass"],
        "no_entry_block_resize_quota_or_promotion_veto": (
            manager["no_entry_block_resize_quota_or_promotion_veto_pass"]
        ),
        "entry_route_completeness_matches_v7_with_exact_inception_fallback": (
            audit["all_entry_routes_complete_pass"] == parent_audit["all_entry_routes_complete_pass"]
            and audit["entry_route_missing_count"] == parent_audit["entry_route_missing_count"]
        ),
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def per_lot_attribution(candidate: dict, parent: dict) -> list[dict]:
    manager = candidate["conditional_partial_audit"][
        "v12_profit_peak_delayed_failed_reclaim_audit"
    ]
    affected = sorted({row["fill_time"] for row in manager["manager_fill_records"]})

    def pnl_by_fill(result: dict) -> dict[str, D]:
        values: dict[str, D] = {}
        for row in result["conditional_partial_audit"]["exit_fill_records"]:
            fill_time = row["fill_time"]
            values[fill_time] = values.get(fill_time, ZERO) + D(row["realized_net_pnl_usdt"])
        return values

    candidate_pnl = pnl_by_fill(candidate)
    parent_pnl = pnl_by_fill(parent)
    rows = []
    for fill_time in affected:
        candidate_value = candidate_pnl.get(fill_time)
        parent_value = parent_pnl.get(fill_time)
        rows.append(
            {
                "fill_time": fill_time,
                "candidate_realized_pnl_usdt": (
                    None if candidate_value is None else str(base.money(candidate_value))
                ),
                "v7_realized_pnl_usdt": (
                    None if parent_value is None else str(base.money(parent_value))
                ),
                "candidate_minus_v7_realized_usdt": (
                    None if candidate_value is None or parent_value is None
                    else str(base.money(candidate_value - parent_value))
                ),
            }
        )
    return rows


def candidate_gates(
    result: dict,
    design: dict,
    parent_validation: dict,
    parent_design: dict,
    design_manager_exits: int,
    validation_manager_exits: int,
    total_wins: int,
    hold_wins: int,
    audits_pass: bool,
) -> dict[str, bool]:
    return {
        "design_total_strictly_greater_than_v7": (
            base.dec(design, "total_pnl_usdt") > base.dec(parent_design, "total_pnl_usdt")
        ),
        "validation_realized_strictly_greater_than_v7": (
            base.dec(result, "realized_usdt") > base.dec(parent_validation, "realized_usdt")
        ),
        "validation_total_strictly_greater_than_v7": (
            base.dec(result, "total_pnl_usdt") > base.dec(parent_validation, "total_pnl_usdt")
        ),
        "validation_unrealized_no_worse_than_v7": (
            base.dec(result, "unrealized_usdt") >= base.dec(parent_validation, "unrealized_usdt")
        ),
        "validation_drawdown_no_higher_than_v7": (
            base.dec(result, "max_drawdown_pct") <= base.dec(parent_validation, "max_drawdown_pct")
        ),
        "validation_median_no_higher_than_dra_v1_and_lower_than_v7": (
            result["median_hold_hours"] is not None
            and D(str(result["median_hold_hours"])) <= D("182.5")
            and D(str(result["median_hold_hours"])) < D(str(parent_validation["median_hold_hours"]))
        ),
        "validation_p90_no_higher_than_v7": (
            result["p90_hold_hours"] is not None
            and D(str(result["p90_hold_hours"])) <= D(str(parent_validation["p90_hold_hours"]))
        ),
        "validation_harvest_efficiency_strictly_greater_than_v7": (
            D(result["harvest_efficiency_usdt_per_1000_capital_hours"])
            > D(parent_validation["harvest_efficiency_usdt_per_1000_capital_hours"])
        ),
        "design_manager_completed_exits_at_least_3": design_manager_exits >= 3,
        "validation_manager_completed_exits_at_least_3": validation_manager_exits >= 3,
        "design_manager_fills_strictly_less_than_v9": (
            design_manager_exits < V9_MANAGER_FILLS["design"]
        ),
        "validation_manager_fills_strictly_less_than_v9": (
            validation_manager_exits < V9_MANAGER_FILLS["validation"]
        ),
        "annual_total_wins_vs_v7_at_least_3_of_5": total_wins >= 3,
        "annual_median_hold_wins_vs_v7_at_least_3_of_5": hold_wins >= 3,
        "all_invariance_causal_profit_and_accounting_audits_pass": audits_pass,
    }


def freeze_hash(candidate: str, data_sha: str, hashes: dict[str, str], runner_sha: str) -> str:
    return json_hash(
        {
            "research_identity": RESEARCH_IDENTITY,
            "candidate": candidate,
            "selection_data_sha256": data_sha,
            **hashes,
            "runner_sha256": runner_sha,
        }
    )


def run_preselect(output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
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
    baselines = reproduce_parent_checkpoints(bars)
    candidates: dict[str, dict] = {}
    for candidate in CANDIDATES:
        design = simulate_candidate(bars, DESIGN, candidate)
        validation = simulate_candidate(bars, VALIDATION, candidate)
        folds = {name: simulate_candidate(bars, window, candidate) for name, window in FOLDS.items()}
        design_invariance = invariance_audit(design, baselines["v7"]["design"])
        validation_invariance = invariance_audit(validation, baselines["v7"]["validation"])
        fold_invariance = {
            name: invariance_audit(folds[name], baselines["v7"]["folds"][name])
            for name in FOLDS
        }
        all_audits_pass = (
            all(design_invariance.values())
            and all(validation_invariance.values())
            and all(all(values.values()) for values in fold_invariance.values())
        )
        total_wins = sum(
            base.dec(folds[name], "total_pnl_usdt")
            > base.dec(baselines["v7"]["folds"][name], "total_pnl_usdt")
            for name in FOLDS
        )
        hold_wins = sum(
            folds[name]["median_hold_hours"]
            < baselines["v7"]["folds"][name]["median_hold_hours"]
            for name in FOLDS
        )
        design_manager = design["conditional_partial_audit"][
            "v12_profit_peak_delayed_failed_reclaim_audit"
        ]
        validation_manager = validation["conditional_partial_audit"][
            "v12_profit_peak_delayed_failed_reclaim_audit"
        ]
        gates = candidate_gates(
            validation,
            design,
            baselines["v7"]["validation"],
            baselines["v7"]["design"],
            design_manager["manager_completed_exit_count"],
            validation_manager["manager_completed_exit_count"],
            total_wins,
            hold_wins,
            all_audits_pass,
        )
        candidates[candidate] = {
            "candidate": candidate,
            "design": design,
            "validation": validation,
            "folds": folds,
            "annual_total_wins_vs_v7": total_wins,
            "annual_median_hold_wins_vs_v7": hold_wins,
            "invariance_audits": {
                "design": design_invariance,
                "validation": validation_invariance,
                "folds": fold_invariance,
            },
            "per_lot_validation_attribution": per_lot_attribution(
                validation, baselines["v7"]["validation"]
            ),
            "gates": gates,
            "pass": all(gates.values()),
        }

    eligible = [name for name in CANDIDATES if candidates[name]["pass"]]
    ranked = sorted(
        eligible,
        key=lambda name: (
            -base.dec(candidates[name]["validation"], "total_pnl_usdt"),
            -D(candidates[name]["validation"]["harvest_efficiency_usdt_per_1000_capital_hours"]),
            D(str(candidates[name]["validation"]["median_hold_hours"])),
            CANDIDATE_WAITS[name],
            name,
        ),
    )
    selected = ranked[0] if ranked else None
    runner_sha = source_hash()
    result = {
        "status": "CANDIDATE_FROZEN" if selected else "NO_CANDIDATE_KEEP_V7_AND_DRA_V1",
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
        "baseline_parity": "PASS_V1_V2A_V7",
        "oos_opened": False,
        "candidates": candidates,
        "baselines": baselines,
        "eligible_candidates": ranked,
        "qualified_count": len(ranked),
        "selected_candidate": selected,
        "one_slot_overlay": None,
    }
    if selected:
        result["freeze_sha256"] = freeze_hash(selected, digest, artifact_hashes, runner_sha)
        result["one_slot_overlay"] = {
            "design": simulate_candidate(bars, DESIGN, selected, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, selected, cap=base.LOT_COST),
            "folds": {
                name: simulate_candidate(bars, window, selected, cap=base.LOT_COST)
                for name, window in FOLDS.items()
            },
        }
    base.write_json(output, result)
    return result


def run_oos(preselect_path: Path, cutoff: datetime, output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    preselection = json.loads(preselect_path.read_text(encoding="utf-8"))
    if preselection.get("status") != "CANDIDATE_FROZEN":
        raise base.ResearchReject("OOS_SEAL_REJECT", "preselection froze no candidate")
    candidate = preselection.get("selected_candidate")
    if candidate not in CANDIDATES:
        raise base.ResearchReject("OOS_SEAL_REJECT", "invalid frozen candidate")
    artifact_hashes = verify_preregistration_artifacts()
    runner_sha = source_hash()
    for field, expected in {
        "selection_data_sha256": SELECTION_SHA256,
        **artifact_hashes,
        "runner_sha256": runner_sha,
    }.items():
        if preselection.get(field) != expected:
            raise base.ResearchReject("OOS_SEAL_REJECT", f"{field} mismatch")
    expected_freeze = freeze_hash(candidate, SELECTION_SHA256, artifact_hashes, runner_sha)
    if preselection.get("freeze_sha256") != expected_freeze:
        raise base.ResearchReject("OOS_SEAL_REJECT", "freeze hash mismatch")
    if cutoff <= SELECTION_CUTOFF:
        raise base.ResearchReject("OOS_SEAL_REJECT", "cutoff must be after selection")

    bars = base.parse_rows(base.fetch_rows(cutoff))
    end = bars[-1].close_time
    window = (SELECTION_CUTOFF, end)
    parent = simulate_v7(bars, window)
    result_candidate = simulate_candidate(bars, window, candidate)
    audit = invariance_audit(result_candidate, parent)
    gates = {
        "total_strictly_greater_than_v7": (
            base.dec(result_candidate, "total_pnl_usdt") > base.dec(parent, "total_pnl_usdt")
        ),
        "realized_strictly_greater_than_v7": (
            base.dec(result_candidate, "realized_usdt") > base.dec(parent, "realized_usdt")
        ),
        "unrealized_no_worse_than_v7": (
            base.dec(result_candidate, "unrealized_usdt") >= base.dec(parent, "unrealized_usdt")
        ),
        "drawdown_no_higher_than_v7": (
            base.dec(result_candidate, "max_drawdown_pct") <= base.dec(parent, "max_drawdown_pct")
        ),
        "median_no_higher_than_v7": result_candidate["median_hold_hours"] <= parent["median_hold_hours"],
        "p90_no_higher_than_v7": result_candidate["p90_hold_hours"] <= parent["p90_hold_hours"],
        "all_audits_pass": all(audit.values()),
    }
    result = {
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selected_candidate": candidate,
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_last_complete_close": end.isoformat(),
        "full_data_rows": len(bars),
        "full_data_sha256": base.data_hash(bars),
        "oos": {
            "v7_parent": parent,
            "candidate": result_candidate,
            "invariance_audit": audit,
            "gates": gates,
        },
        "one_slot_overlay_30": {
            "v7_parent": simulate_v7(bars, window, cap=base.LOT_COST),
            "candidate": simulate_candidate(bars, window, candidate, cap=base.LOT_COST),
        },
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"candidates", "baselines", "one_slot_overlay", "oos", "one_slot_overlay_30"}
    return {key: value for key, value in result.items() if key not in omitted}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="stage", required=True)
    preselect = subparsers.add_parser("preselect")
    preselect.add_argument("--output", type=Path, required=True)
    oos = subparsers.add_parser("oos")
    oos.add_argument("--preselect", type=Path, required=True)
    oos.add_argument("--cutoff", type=datetime.fromisoformat, required=True)
    oos.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    output = args.output
    if output.exists():
        result = {
            "status": "OUTPUT_SEAL_REJECT",
            "research_identity": RESEARCH_IDENTITY,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
            "detail": str(output),
        }
        print(json.dumps(result, ensure_ascii=False))
        return 2

    try:
        if args.stage == "preselect":
            result = run_preselect(output)
        else:
            result = run_oos(args.preselect, args.cutoff, output)
    except base.ResearchReject as reject:
        result = {
            "status": reject.status,
            "research_identity": RESEARCH_IDENTITY,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
            "detail": reject.detail,
        }
        base.write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
