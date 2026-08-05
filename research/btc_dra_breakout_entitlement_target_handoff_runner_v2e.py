#!/usr/bin/env python3
"""Final causal DRA V2E entitlement/handoff research with sealed 2025+ OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_atr_target_sparse_breakout_runner_v2d as v2d

base = v2d.base
D = Decimal
ZERO = D("0")
RESEARCH_IDENTITY = "BTC_DRA_BREAKOUT_ENTITLEMENT_TARGET_HANDOFF_RUNNER_V2E_RESEARCH"
CANDIDATE = "FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_HANDOFF_RUNNER"
SPEC_PATH = (
    Path(__file__).resolve().parents[1]
    / "docs"
    / "btc-dra-breakout-entitlement-target-handoff-runner-v2e-research.md"
)
V2D_SPEC_PATH = (
    Path(__file__).resolve().parents[1]
    / "docs"
    / "btc-dra-atr-target-sparse-breakout-runner-v2d-research.md"
)
EXPECTED_SPEC_SHA256 = "cc75af188264a49c4e915d72a911aac23f512ed749392324f10795477f8713f2"
EXPECTED_V2D_SPEC_SHA256 = "de3279688e1362360cd5f3d91ed6ba387a40ece95e6cac4f571c7bd411b4af3e"
EXPECTED_V2C_RUNNER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_V2D_RUNNER_SHA256 = "5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952"
SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS


class V2EEngine(v2d.V2DEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(v2d.CANDIDATES[0], cap=cap)
        self.mode = "v2e"
        self.factor = CANDIDATE
        self.candidate = CANDIDATE
        self.entitlement_state = "NONE"
        self.entitlement_created_at: datetime | None = None
        self.entitlement_creations = 0
        self.entitlement_expirations = 0
        self.entitlement_handoffs = 0
        self.duplicate_events_pending = 0
        self.duplicate_events_consumed = 0
        self.same_bar_handoff_rejections = 0
        self.entitlement_event_records: list[dict] = []
        self.handoff_records: list[dict] = []
        self.handoff_latency_hours: list[float] = []
        self.epoch_entitlement_counts: dict[int, int] = {}

    def start_window(self) -> None:
        super().start_window()
        self.entitlement_state = "NONE"
        self.entitlement_created_at = None
        self.entitlement_creations = 0
        self.entitlement_expirations = 0
        self.entitlement_handoffs = 0
        self.duplicate_events_pending = 0
        self.duplicate_events_consumed = 0
        self.same_bar_handoff_rejections = 0
        self.entitlement_event_records.clear()
        self.handoff_records.clear()
        self.handoff_latency_hours.clear()
        self.epoch_entitlement_counts.clear()

    def _daily_runner_state(self, current: v2d.DailyRecord) -> None:
        previous = self.daily_records[-1] if self.daily_records else None
        if previous is not None and current.close < current.ema20 and previous.close >= previous.ema20:
            if self.entitlement_state == "PENDING":
                self.entitlement_expirations += 1
                self.entitlement_event_records.append(
                    {
                        "day": current.day.isoformat(),
                        "epoch": self.epoch_id,
                        "decision": "PENDING_ENTITLEMENT_EXPIRED_ON_EMA20_DOWN_CROSS",
                        "entitlement_created_at": self.entitlement_created_at.isoformat(),
                    }
                )
            self.epoch_id += 1
            self.epoch_slot_used = False
            self.epoch_reset_count += 1
            self.entitlement_state = "NONE"
            self.entitlement_created_at = None
        self.epochs_observed.add(self.epoch_id)

        if not self._is_fresh_breakout(current):
            return
        self.fresh_breakout_events += 1
        event = {
            "day": current.day.isoformat(),
            "epoch": self.epoch_id,
            "state_before": self.entitlement_state,
        }
        if self.entitlement_state == "NONE":
            self.entitlement_state = "PENDING"
            self.entitlement_created_at = current.day
            self.entitlement_creations += 1
            self.epoch_entitlement_counts[self.epoch_id] = (
                self.epoch_entitlement_counts.get(self.epoch_id, 0) + 1
            )
            event["decision"] = "ENTITLEMENT_CREATED"
        elif self.entitlement_state == "PENDING":
            self.duplicate_events_pending += 1
            event["decision"] = "DUPLICATE_EVENT_REJECTED_PENDING"
            event["entitlement_created_at"] = self.entitlement_created_at.isoformat()
        else:
            self.duplicate_events_consumed += 1
            event["decision"] = "DUPLICATE_EVENT_REJECTED_CONSUMED"
        self.entitlement_event_records.append(event)

    def _queue_exits(self, bar: base.Bar) -> None:
        target_ready: list[tuple[base.Lot, D, D]] = []
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            lot.highest_close = max(lot.highest_close, bar.close)
            if lot.fill_time in self.runner_epoch_by_fill or lot.entry_atr is None:
                continue
            pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            target = lot.entry_atr * lot.quantity
            if pnl >= target:
                target_ready.append((lot, pnl, target))

        selected_fill: datetime | None = None
        if self.entitlement_state == "PENDING" and target_ready:
            selected_lot, selected_pnl, selected_target = max(
                target_ready, key=lambda item: (item[0].fill_time, item[0].signal_time)
            )
            selected_fill = selected_lot.fill_time
            self.runner_epoch_by_fill[selected_fill] = self.epoch_id
            self.epoch_runner_counts[self.epoch_id] = (
                self.epoch_runner_counts.get(self.epoch_id, 0) + 1
            )
            self.epoch_slot_used = True
            self.entitlement_state = "CONSUMED"
            self.entitlement_handoffs += 1
            rejected = len(target_ready) - 1
            self.same_bar_handoff_rejections += rejected
            latency = (bar.open_time - self.entitlement_created_at).total_seconds() / 3600
            self.handoff_latency_hours.append(latency)
            self.handoff_records.append(
                {
                    "epoch": self.epoch_id,
                    "entitlement_created_at": self.entitlement_created_at.isoformat(),
                    "target_handoff_at": bar.open_time.isoformat(),
                    "latency_hours": latency,
                    "signal_time": selected_lot.signal_time.isoformat(),
                    "fill_time": selected_lot.fill_time.isoformat(),
                    "estimated_net_pnl_usdt": str(selected_pnl),
                    "entry_atr_target_usdt": str(base.money(selected_target)),
                    "target_ready_lot_count": len(target_ready),
                    "same_bar_rejected_lots": rejected,
                }
            )

        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            if lot.fill_time in self.runner_epoch_by_fill:
                if self.atr14 is None:
                    continue
                candidate_stop = lot.highest_close - self.atr14 * base.V2A_MULTIPLIER
                lot.ratchet_stop = (
                    candidate_stop
                    if lot.ratchet_stop is None
                    else max(lot.ratchet_stop, candidate_stop)
                )
                if bar.close <= lot.ratchet_stop and pnl > ZERO:
                    lot.exit_queued_at = bar.open_time
                    self._count_trigger("ENTITLEMENT_RUNNER_ATR_TRAIL_1_50")
            elif lot.entry_atr is not None and pnl >= lot.entry_atr * lot.quantity:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("DEFAULT_ENTRY_ATR_TARGET_1_00")

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = base.Engine.result(self, final_bar, start, end)
        runner_count = len(self.handoff_records)
        sparse_limit = math.ceil(self.buy_count * 0.10)
        entitlement_unique = all(
            count <= 1 for count in self.epoch_entitlement_counts.values()
        )
        runner_unique = all(count <= 1 for count in self.epoch_runner_counts.values())
        active_runners = sum(
            lot.fill_time in self.runner_epoch_by_fill for lot in self.lots
        )
        result["candidate"] = CANDIDATE
        result["entitlement_runner_audit"] = {
            "fresh_breakout_events": self.fresh_breakout_events,
            "trend_epoch_resets": self.epoch_reset_count,
            "distinct_epochs_observed": len(self.epochs_observed),
            "entitlement_creations": self.entitlement_creations,
            "entitlement_duplicate_events_pending": self.duplicate_events_pending,
            "entitlement_duplicate_events_consumed": self.duplicate_events_consumed,
            "entitlement_expirations": self.entitlement_expirations,
            "entitlement_handoffs": self.entitlement_handoffs,
            "pending_entitlement_at_end": self.entitlement_state == "PENDING",
            "ending_entitlement_state": self.entitlement_state,
            "runner_assignments": runner_count,
            "runner_share_of_buys_pct": (
                round(runner_count * 100 / self.buy_count, 6) if self.buy_count else 0.0
            ),
            "runner_exit_fills": self.runner_exit_fills,
            "target_exit_fills": self.sell_count - self.runner_exit_fills,
            "active_open_runners": active_runners,
            "same_bar_handoff_rejected_lots": self.same_bar_handoff_rejections,
            "median_entitlement_to_handoff_hours": base.percentile(
                self.handoff_latency_hours, 0.5
            ),
            "p90_entitlement_to_handoff_hours": base.percentile(
                self.handoff_latency_hours, 0.9
            ),
            "epoch_entitlement_counts": {
                str(epoch): count
                for epoch, count in sorted(self.epoch_entitlement_counts.items())
            },
            "epoch_runner_counts": {
                str(epoch): count
                for epoch, count in sorted(self.epoch_runner_counts.items())
            },
            "epoch_entitlement_uniqueness_pass": entitlement_unique,
            "epoch_runner_uniqueness_pass": runner_unique,
            "validation_style_sparse_limit_10pct_of_buys": sparse_limit,
            "validation_style_sparse_count_pass": runner_count <= sparse_limit,
            "entitlement_events_detail": self.entitlement_event_records,
            "handoffs_detail": self.handoff_records,
        }
        return result


EXPECTED_V2D = {
    "FRESH_DONCHIAN20_RUNNER": {
        "base": (
            "85.25456892", "-3.20820121", "82.04636771", "6.653979", 167.5,
            833.4, 51, 50, 1, 0, "16.370041", "1585.25456892",
        ),
        "audit": (38, 38, 0, 26, 40, 41, 9, 17.647059, 9, 41, 0, 5, 5, 0, True, 6, False),
        "wins": (3, 2),
    },
    "FRESH_DONCHIAN20_PLUS_7D_MOMENTUM_ACCELERATION": {
        "base": (
            "80.66650314", "-3.20820121", "77.45830193", "6.772957", 162.0,
            830.7, 51, 50, 1, 0, "16.118331", "1580.66650314",
        ),
        "audit": (38, 30, 8, 22, 40, 41, 7, 13.72549, 7, 43, 0, 2, 1, 1, True, 6, False),
        "wins": (2, 2),
    },
    "FRESH_DONCHIAN20_PLUS_DAILY_RANGE_EXPANSION": {
        "base": (
            "75.63344136", "-3.20820121", "72.42524015", "6.858124", 159.0,
            833.4, 51, 50, 1, 0, "16.023256", "1575.63344136",
        ),
        "audit": (38, 32, 6, 22, 40, 41, 8, 15.686275, 8, 42, 0, 3, 3, 0, True, 6, False),
        "wins": (2, 2),
    },
}


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v2d_specification_sha256": file_sha256(V2D_SPEC_PATH),
        "v2c_dependency_sha256": file_sha256(Path(base.__file__)),
        "v2d_dependency_sha256": file_sha256(Path(v2d.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v2d_specification_sha256": EXPECTED_V2D_SPEC_SHA256,
        "v2c_dependency_sha256": EXPECTED_V2C_RUNNER_SHA256,
        "v2d_dependency_sha256": EXPECTED_V2D_RUNNER_SHA256,
    }
    problems = [
        {"artifact": key, "expected": expected[key], "actual": actual[key]}
        for key in expected
        if actual[key] != expected[key]
    ]
    if problems:
        raise base.ResearchReject("PREREGISTRATION_REJECT", problems)
    return actual


def simulate_v2e(
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
        raise base.ResearchReject(
            "DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}"
        )
    engine = V2EEngine(cap=cap)
    started = False
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            if not started:
                engine.start_window()
                started = True
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def v2d_audit_tuple(result: dict) -> tuple:
    audit = result["sparse_runner_audit"]
    return (
        audit["fresh_breakout_events"],
        audit["candidate_confirmed_events"],
        audit["candidate_filter_rejected_events"],
        audit["fresh_breakout_no_eligible_lot_events"],
        audit["trend_epoch_resets"],
        audit["distinct_epochs_observed"],
        audit["runner_assignments"],
        audit["runner_share_of_buys_pct"],
        audit["runner_exit_fills"],
        audit["target_exit_fills"],
        audit["active_open_runners"],
        audit["rejected_runner_lots_total"],
        audit["rejected_runner_lots_epoch_used"],
        audit["rejected_runner_lots_same_event_cap"],
        audit["epoch_uniqueness_pass"],
        audit["validation_style_sparse_limit_10pct_of_buys"],
        audit["validation_style_sparse_count_pass"],
    )


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = v2d.reproduce_checkpoints(bars)
    mismatches: list[dict] = []
    v2d_results: dict[str, dict] = {}
    for candidate in v2d.CANDIDATES:
        validation = v2d.simulate_v2d(bars, VALIDATION, candidate)
        folds = {
            name: v2d.simulate_v2d(bars, window, candidate)
            for name, window in FOLDS.items()
        }
        total_wins = sum(
            base.dec(folds[name], "total_pnl_usdt")
            > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
            for name in FOLDS
        )
        hold_wins = sum(
            folds[name]["median_hold_hours"] is not None
            and baselines["v1"]["folds"][name]["median_hold_hours"] is not None
            and folds[name]["median_hold_hours"]
            < baselines["v1"]["folds"][name]["median_hold_hours"]
            for name in FOLDS
        )
        expected = EXPECTED_V2D[candidate]
        actual_base = base.checkpoint_tuple(validation)
        actual_audit = v2d_audit_tuple(validation)
        actual_wins = (total_wins, hold_wins)
        if actual_base != expected["base"]:
            mismatches.append(
                {
                    "checkpoint": f"v2d_{candidate}_validation",
                    "expected": expected["base"],
                    "actual": actual_base,
                }
            )
        if actual_audit != expected["audit"]:
            mismatches.append(
                {
                    "checkpoint": f"v2d_{candidate}_runner_audit",
                    "expected": expected["audit"],
                    "actual": actual_audit,
                }
            )
        if actual_wins != expected["wins"]:
            mismatches.append(
                {
                    "checkpoint": f"v2d_{candidate}_annual_wins",
                    "expected": expected["wins"],
                    "actual": actual_wins,
                }
            )
        v2d_results[candidate] = {
            "validation": validation,
            "folds": folds,
            "annual_total_wins": total_wins,
            "annual_median_hold_wins": hold_wins,
        }
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["v2d"] = v2d_results
    return baselines


def candidate_gates(
    result: dict,
    v1: dict,
    v2a: dict,
    total_wins: int,
    hold_wins: int,
) -> dict[str, bool]:
    audit = result["entitlement_runner_audit"]
    return {
        "validation_total_at_least_v1": (
            base.dec(result, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt")
        ),
        "validation_total_retains_90pct_v2a": (
            base.dec(result, "total_pnl_usdt")
            >= base.dec(v2a, "total_pnl_usdt") * D("0.90")
        ),
        "validation_realized_at_least_v1": (
            base.dec(result, "realized_usdt") >= base.dec(v1, "realized_usdt")
        ),
        "validation_unrealized_no_worse": (
            base.dec(result, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt")
        ),
        "validation_drawdown_at_most_9_121498pct": (
            base.dec(result, "max_drawdown_pct") <= D("9.121498")
        ),
        "validation_median_hold_at_most_182_5h": (
            result["median_hold_hours"] is not None
            and D(str(result["median_hold_hours"])) <= D("182.5")
        ),
        "validation_p90_hold_at_most_1418_3h": (
            result["p90_hold_hours"] is not None
            and D(str(result["p90_hold_hours"])) <= D("1418.3")
        ),
        "annual_total_wins_at_least_3_of_5": total_wins >= 3,
        "annual_median_hold_wins_at_least_3_of_5": hold_wins >= 3,
        "epoch_entitlement_uniqueness": audit["epoch_entitlement_uniqueness_pass"],
        "epoch_runner_uniqueness": audit["epoch_runner_uniqueness_pass"],
        "validation_runner_count_at_most_ceil_10pct_buys": (
            audit["validation_style_sparse_count_pass"]
        ),
    }


def freeze_hash(
    data_sha: str,
    hashes: dict[str, str],
    runner_sha: str,
) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": CANDIDATE,
        "selection_data_sha256": data_sha,
        **hashes,
        "runner_sha256": runner_sha,
    }
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


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
    baselines = reproduce_checkpoints(bars)
    design = simulate_v2e(bars, DESIGN)
    validation = simulate_v2e(bars, VALIDATION)
    folds = {
        name: simulate_v2e(bars, window) for name, window in FOLDS.items()
    }
    total_wins = sum(
        base.dec(folds[name], "total_pnl_usdt")
        > base.dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    )
    hold_wins = sum(
        folds[name]["median_hold_hours"] is not None
        and baselines["v1"]["folds"][name]["median_hold_hours"] is not None
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
        "status": "CANDIDATE_FROZEN" if passed else "DRA_DYNAMIC_EXIT_RESEARCH_STOP_KEEP_V1",
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
        "baseline_parity": "PASS_V1_V2A_V2B_V2C_V2D",
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "qualified_count": 1 if passed else 0,
        "one_slot_overlay": None,
    }
    if passed:
        result["frozen_candidate_key"] = CANDIDATE
        result["freeze_sha256"] = freeze_hash(digest, artifact_hashes, runner_sha)
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["entitlement_runner_audit"]
    return {
        "oos_total_at_least_v1": (
            base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt")
        ),
        "oos_total_retains_90pct_v2a": (
            base.dec(candidate, "total_pnl_usdt")
            >= base.dec(v2a, "total_pnl_usdt") * D("0.90")
        ),
        "oos_realized_at_least_v1": (
            base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt")
        ),
        "oos_unrealized_no_worse": (
            base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt")
        ),
        "oos_drawdown_within_v1_plus_2pp": (
            base.dec(candidate, "max_drawdown_pct")
            <= base.dec(v1, "max_drawdown_pct") + D("2.0")
        ),
        "oos_median_hold_no_worse": (
            candidate["median_hold_hours"] is not None
            and v1["median_hold_hours"] is not None
            and candidate["median_hold_hours"] <= v1["median_hold_hours"]
        ),
        "oos_p90_hold_no_worse": (
            candidate["p90_hold_hours"] is not None
            and v1["p90_hold_hours"] is not None
            and candidate["p90_hold_hours"] <= v1["p90_hold_hours"]
        ),
        "epoch_entitlement_uniqueness": audit["epoch_entitlement_uniqueness_pass"],
        "epoch_runner_uniqueness": audit["epoch_runner_uniqueness_pass"],
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
    v2e = simulate_v2e(bars, window)
    gates = oos_gates(v2e, v1, v2a)
    one_slot = {
        "design": simulate_v2e(bars, DESIGN, cap=base.LOT_COST),
        "validation": simulate_v2e(bars, VALIDATION, cap=base.LOT_COST),
        "folds": {
            name: simulate_v2e(bars, fold, cap=base.LOT_COST)
            for name, fold in FOLDS.items()
        },
        "oos": simulate_v2e(bars, window, cap=base.LOT_COST),
    }
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
            "v2e_reference_250": v2e,
            "gates": gates,
        },
        "one_slot_overlay_30": one_slot,
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {
        "baselines",
        "candidate_result",
        "one_slot_overlay",
        "oos",
        "one_slot_overlay_30",
    }
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
