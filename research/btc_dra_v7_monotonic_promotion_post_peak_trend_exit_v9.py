#!/usr/bin/env python3
"""Causal DRA V7 monotonic-promotion post-peak trend-exit V9 research."""

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
HUNDRED = D("100")
THOUSAND = D("1000")

RESEARCH_IDENTITY = "BTC_DRA_V7_MONOTONIC_PROMOTION_POST_PEAK_TREND_EXIT_V9_RESEARCH"
CANDIDATES = (
    "POST_1R_PEAK_GIVEBACK_1R",
    "POST_1R_EMA5_DOWNTURN",
    "POST_1R_PEAK_GIVEBACK_1R_AND_EMA5_DOWNTURN",
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-v7-monotonic-promotion-post-peak-trend-exit-v9-research.md"
V7_SPEC_PATH = ROOT / "docs" / "btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md"
EXPECTED_SPEC_SHA256 = "048399d142804a0c52a2d2e3bbe21dfc28938d4bafdd225fae76128dc2f36449"
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


class MonotonicPostPeakTrendEngine(v7.PrePartialOneRPromotionEngine):
    def __init__(self, candidate: str, *, cap: D = base.REFERENCE_CAP) -> None:
        if candidate not in CANDIDATES:
            raise ValueError(candidate)
        super().__init__(cap=cap)
        self.candidate = candidate
        self.factor = candidate
        self.mode = "v7_monotonic_promotion_post_peak_trend_exit_v9"
        self.previous_hourly_ema5: D | None = None
        self.manager_arm_records: list[dict] = []
        self.manager_queue_records: list[dict] = []
        self.manager_fill_records: list[dict] = []
        self.manager_deferred_fill_records: list[dict] = []
        self.manager_input_unavailable_count = 0

    def _indicators(self, bar: base.Bar) -> None:
        prior = self.hourly_ema5
        super()._indicators(bar)
        self.previous_hourly_ema5 = prior

    def _fill_buy(self, bar: base.Bar) -> None:
        before = len(self.lots)
        super()._fill_buy(bar)
        if len(self.lots) == before:
            return
        state = self.lot_state[self.lots[-1].fill_time]
        state["v9_manager_armed_at"] = None
        state["v9_manager_peak_net_pnl"] = None
        state["v9_manager_peak_close"] = None
        state["v9_manager_pending_queue"] = None

    def _factor_truth(self, giveback_pass: bool, ema5_downturn: bool) -> bool:
        if self.candidate == CANDIDATES[0]:
            return giveback_pass
        if self.candidate == CANDIDATES[1]:
            return ema5_downturn
        if self.candidate == CANDIDATES[2]:
            return giveback_pass and ema5_downturn
        raise ValueError(self.candidate)

    def _queue_exits(self, bar: base.Bar) -> None:
        # Parent V7 always runs first. This preserves unconditional promotion
        # and gives the frozen V2A ratchet same-hour precedence.
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
            previous_peak = state["v9_manager_peak_net_pnl"]
            peak = current_pnl if previous_peak is None else max(previous_peak, current_pnl)
            if previous_peak is None or current_pnl >= previous_peak:
                state["v9_manager_peak_close"] = bar.close
            state["v9_manager_peak_net_pnl"] = peak

            armed_at = state["v9_manager_armed_at"]
            if armed_at is None and peak >= one_r:
                state["v9_manager_armed_at"] = bar.open_time
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
                        "first_crossing_pass": previous_peak is None or previous_peak < one_r,
                        "threshold_pass": peak >= one_r,
                    }
                )

            # Never act on the arm bar, and never override a parent queue.
            if armed_at is None or bar.open_time <= armed_at or lot.exit_queued_at is not None:
                continue

            giveback = peak - current_pnl
            giveback_pass = giveback >= one_r
            ema5_downturn = (
                self.hourly_ema5 is not None
                and self.previous_hourly_ema5 is not None
                and bar.close < self.hourly_ema5
                and self.hourly_ema5 < self.previous_hourly_ema5
            )
            factor_pass = self._factor_truth(giveback_pass, ema5_downturn)
            condition_pass = factor_pass and current_pnl > ZERO
            if not condition_pass:
                continue

            lot.exit_queued_at = bar.open_time
            self.queue_kind_by_fill[lot.fill_time] = "FULL_V2A"
            state["v9_manager_pending_queue"] = bar.open_time
            self._count_trigger(f"V9_{self.candidate}")
            self.manager_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "arm_time": armed_at.isoformat(),
                    "route": state["route"],
                    "promoted": state["promoted"],
                    "one_r_usdt": str(base.money(one_r)),
                    "current_net_pnl_usdt": str(current_pnl),
                    "peak_net_pnl_usdt": str(peak),
                    "giveback_usdt": str(base.money(giveback)),
                    "giveback_1r_pass": giveback_pass,
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "prior_causal_hourly_ema5": str(self.previous_hourly_ema5),
                    "ema5_downturn_pass": ema5_downturn,
                    "factor_pass": factor_pass,
                    "estimated_net_positive_pass": current_pnl > ZERO,
                    "strictly_after_arm_pass": bar.open_time > armed_at,
                    "parent_v2a_same_hour_queue": False,
                    "condition_pass": condition_pass,
                }
            )

    def _fill_exits(self, bar: base.Bar) -> None:
        pending = {
            lot.fill_time: self.lot_state[lot.fill_time].get("v9_manager_pending_queue")
            for lot in self.lots
            if self.lot_state[lot.fill_time].get("v9_manager_pending_queue") is not None
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
                self.lot_state[fill_time]["v9_manager_pending_queue"] = None
        for row in self.deferred_fill_records[deferred_before:]:
            fill_time = datetime.fromisoformat(row["fill_time"])
            if fill_time in pending:
                manager_row = dict(row)
                manager_row["manager_queue_time"] = pending[fill_time].isoformat()
                manager_row["candidate"] = self.candidate
                self.manager_deferred_fill_records.append(manager_row)
                self.lot_state[fill_time]["v9_manager_pending_queue"] = None

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "v7_monotonic_promotion_post_peak_trend_exit_v9"
        result["candidate"] = self.candidate
        result["factor"] = self.candidate
        audit = result["conditional_partial_audit"]
        arm_times = [row["fill_time"] for row in self.manager_arm_records]
        queue_delays = [
            (
                datetime.fromisoformat(row["queue_time"])
                - datetime.fromisoformat(row["arm_time"])
            ).total_seconds() / 3600
            for row in self.manager_queue_records
        ]
        manager_audit = {
            "candidate": self.candidate,
            "manager_arm_count": len(self.manager_arm_records),
            "manager_queue_count": len(self.manager_queue_records),
            "manager_completed_exit_count": len(self.manager_fill_records),
            "manager_deferred_fill_count": len(self.manager_deferred_fill_records),
            "manager_input_unavailable_observations": self.manager_input_unavailable_count,
            "unique_arm_per_lot_pass": len(arm_times) == len(set(arm_times)),
            "all_arms_first_crossing_pass": all(
                row["first_crossing_pass"] for row in self.manager_arm_records
            ),
            "all_arms_threshold_pass": all(
                row["threshold_pass"] for row in self.manager_arm_records
            ),
            "all_queues_strictly_after_arm_pass": all(
                row["strictly_after_arm_pass"] for row in self.manager_queue_records
            ),
            "all_queues_runner_path_pass": all(
                row["route"] == "FULL_V2A" or row["promoted"]
                for row in self.manager_queue_records
            ),
            "all_queues_factor_truth_pass": all(
                row["factor_pass"]
                == self._factor_truth(row["giveback_1r_pass"], row["ema5_downturn_pass"])
                for row in self.manager_queue_records
            ),
            "all_queues_estimated_positive_pass": all(
                row["estimated_net_positive_pass"] for row in self.manager_queue_records
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
            "manager_queue_records": self.manager_queue_records,
            "manager_fill_records": self.manager_fill_records,
            "manager_deferred_fill_records": self.manager_deferred_fill_records,
        }
        audit["v9_manager_formula"] = self.candidate
        audit["v9_monotonic_post_peak_manager_audit"] = manager_audit
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
    engine = MonotonicPostPeakTrendEngine(candidate, cap=cap)
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
        for name, (actual, expected) in checks.items()
        if actual != expected
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
    manager = audit["v9_monotonic_post_peak_manager_audit"]
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
        "manager_queues_strictly_after_arm": manager["all_queues_strictly_after_arm_pass"],
        "manager_queues_runner_path": manager["all_queues_runner_path_pass"],
        "manager_factor_truth": manager["all_queues_factor_truth_pass"],
        "manager_estimated_positive": manager["all_queues_estimated_positive_pass"],
        "parent_same_hour_precedence": manager["parent_v2a_same_hour_precedence_pass"],
        "manager_actual_fills_positive": manager["all_manager_exit_fills_positive_pass"],
        "no_entry_block_resize_quota_or_promotion_veto": (
            manager["no_entry_block_resize_quota_or_promotion_veto_pass"]
        ),
        "entry_route_completeness_matches_v7_with_exact_inception_fallback": (
            audit["all_entry_routes_complete_pass"]
            == parent_audit["all_entry_routes_complete_pass"]
            and audit["entry_route_missing_count"]
            == parent_audit["entry_route_missing_count"]
        ),
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def per_lot_attribution(candidate: dict, parent: dict) -> list[dict]:
    manager = candidate["conditional_partial_audit"]["v9_monotonic_post_peak_manager_audit"]
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
                    None
                    if candidate_value is None or parent_value is None
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
        "annual_total_wins_vs_v7_at_least_3_of_5": total_wins >= 3,
        "annual_median_hold_wins_vs_v7_at_least_3_of_5": hold_wins >= 3,
        "all_invariance_causal_profit_and_accounting_audits_pass": audits_pass,
    }


def freeze_hash(
    candidate: str, data_sha: str, hashes: dict[str, str], runner_sha: str
) -> str:
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
        folds = {
            name: simulate_candidate(bars, window, candidate)
            for name, window in FOLDS.items()
        }
        design_invariance = invariance_audit(design, baselines["v7"]["design"])
        validation_invariance = invariance_audit(
            validation, baselines["v7"]["validation"]
        )
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
            "v9_monotonic_post_peak_manager_audit"
        ]
        validation_manager = validation["conditional_partial_audit"][
            "v9_monotonic_post_peak_manager_audit"
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
        result["freeze_sha256"] = freeze_hash(
            selected, digest, artifact_hashes, runner_sha
        )
        result["one_slot_overlay"] = {
            "design": simulate_candidate(bars, DESIGN, selected, cap=base.LOT_COST),
            "validation": simulate_candidate(
                bars, VALIDATION, selected, cap=base.LOT_COST
            ),
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
        "median_no_higher_than_v7": (
            result_candidate["median_hold_hours"] <= parent["median_hold_hours"]
        ),
        "p90_no_higher_than_v7": (
            result_candidate["p90_hold_hours"] <= parent["p90_hold_hours"]
        ),
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
            "candidate": simulate_candidate(
                bars, window, candidate, cap=base.LOT_COST
            ),
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
