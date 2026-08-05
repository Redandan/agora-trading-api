#!/usr/bin/env python3
"""Causal DRA V2D sparse-breakout-runner research with sealed 2025+ OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_reversal_confirmed_exit_v2c as base

D = Decimal
ZERO = D("0")
RESEARCH_IDENTITY = "BTC_DRA_ATR_TARGET_SPARSE_BREAKOUT_RUNNER_V2D_RESEARCH"
SPEC_PATH = (
    Path(__file__).resolve().parents[1]
    / "docs"
    / "btc-dra-atr-target-sparse-breakout-runner-v2d-research.md"
)
EXPECTED_SPEC_SHA256 = "de3279688e1362360cd5f3d91ed6ba387a40ece95e6cac4f571c7bd411b4af3e"
EXPECTED_V2C_RUNNER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS
V2A_MULTIPLIER = base.V2A_MULTIPLIER
CANDIDATES = (
    "FRESH_DONCHIAN20_RUNNER",
    "FRESH_DONCHIAN20_PLUS_7D_MOMENTUM_ACCELERATION",
    "FRESH_DONCHIAN20_PLUS_DAILY_RANGE_EXPANSION",
)


@dataclass(frozen=True)
class DailyRecord:
    day: datetime
    high: D
    low: D
    close: D
    ema20: D
    atr14: D | None
    true_range: D


class V2DEngine(base.Engine):
    def __init__(self, candidate: str, *, cap: D = base.REFERENCE_CAP) -> None:
        if candidate not in CANDIDATES:
            raise ValueError(candidate)
        super().__init__("v2d", factor=candidate, cap=cap)
        self.candidate = candidate
        self.daily_records: list[DailyRecord] = []
        self.trading_active = False
        self.runner_epoch_by_fill: dict[datetime, int] = {}
        self.runner_assignment_records: list[dict] = []
        self.fresh_event_records: list[dict] = []
        self.epoch_runner_counts: dict[int, int] = {}
        self.epochs_observed: set[int] = set()
        self.epoch_id = 0
        self.epoch_slot_used = False
        self.epoch_reset_count = 0
        self.fresh_breakout_events = 0
        self.candidate_confirmed_events = 0
        self.candidate_filter_rejected_events = 0
        self.fresh_breakout_no_eligible_events = 0
        self.runner_rejected_epoch_used = 0
        self.runner_rejected_same_event = 0
        self.runner_exit_fills = 0

    def start_window(self) -> None:
        """Reset window-owned epoch/audit state after causal indicator warm-up."""
        self.trading_active = True
        self.runner_epoch_by_fill.clear()
        self.runner_assignment_records.clear()
        self.fresh_event_records.clear()
        self.epoch_runner_counts.clear()
        self.epochs_observed = {0}
        self.epoch_id = 0
        self.epoch_slot_used = False
        self.epoch_reset_count = 0
        self.fresh_breakout_events = 0
        self.candidate_confirmed_events = 0
        self.candidate_filter_rejected_events = 0
        self.fresh_breakout_no_eligible_events = 0
        self.runner_rejected_epoch_used = 0
        self.runner_rejected_same_event = 0
        self.runner_exit_fills = 0

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        if bar.open_time.hour != 23 or self.ema20 is None:
            return
        previous_close = self.daily_records[-1].close if self.daily_records else None
        true_range = self.daily_high - self.daily_low
        if previous_close is not None:
            true_range = max(
                true_range,
                abs(self.daily_high - previous_close),
                abs(self.daily_low - previous_close),
            )
        record = DailyRecord(
            day=bar.open_time,
            high=self.daily_high,
            low=self.daily_low,
            close=bar.close,
            ema20=self.ema20,
            atr14=self.atr14,
            true_range=true_range,
        )
        if self.trading_active:
            self._daily_runner_state(record)
        self.daily_records.append(record)

    def _daily_runner_state(self, current: DailyRecord) -> None:
        previous = self.daily_records[-1] if self.daily_records else None
        if previous is not None and current.close < current.ema20 and previous.close >= previous.ema20:
            self.epoch_id += 1
            self.epoch_slot_used = False
            self.epoch_reset_count += 1
        self.epochs_observed.add(self.epoch_id)

        fresh = self._is_fresh_breakout(current)
        if not fresh:
            return
        self.fresh_breakout_events += 1
        filter_pass, filter_values = self._candidate_filter(current)
        event = {
            "day": current.day.isoformat(),
            "epoch": self.epoch_id,
            "candidate": self.candidate,
            "filter_pass": filter_pass,
            "filter_values": filter_values,
            "epoch_slot_used_before_event": self.epoch_slot_used,
        }
        if not filter_pass:
            self.candidate_filter_rejected_events += 1
            event["decision"] = "CANDIDATE_FILTER_REJECTED"
            self.fresh_event_records.append(event)
            return

        self.candidate_confirmed_events += 1
        eligible = [
            lot
            for lot in self.lots
            if lot.exit_queued_at is None
            and lot.entry_atr is not None
            and lot.fill_time not in self.runner_epoch_by_fill
            and base.money(base.estimated_net(lot.quantity, current.close) - lot.cost) > ZERO
        ]
        event["eligible_lot_count"] = len(eligible)
        if not eligible:
            self.fresh_breakout_no_eligible_events += 1
            event["decision"] = "NO_ELIGIBLE_NET_POSITIVE_LOT"
            self.fresh_event_records.append(event)
            return
        if self.epoch_slot_used:
            self.runner_rejected_epoch_used += len(eligible)
            event["decision"] = "EPOCH_SLOT_ALREADY_USED"
            event["rejected_lot_count"] = len(eligible)
            self.fresh_event_records.append(event)
            return

        selected = max(eligible, key=lambda lot: (lot.fill_time, lot.signal_time))
        self.runner_epoch_by_fill[selected.fill_time] = self.epoch_id
        self.epoch_runner_counts[self.epoch_id] = self.epoch_runner_counts.get(self.epoch_id, 0) + 1
        self.epoch_slot_used = True
        rejected = len(eligible) - 1
        self.runner_rejected_same_event += rejected
        assignment = {
            "epoch": self.epoch_id,
            "breakout_day": current.day.isoformat(),
            "signal_time": selected.signal_time.isoformat(),
            "fill_time": selected.fill_time.isoformat(),
            "estimated_net_pnl_usdt": str(
                base.money(base.estimated_net(selected.quantity, current.close) - selected.cost)
            ),
            "eligible_lot_count": len(eligible),
            "same_event_rejected_lots": rejected,
        }
        self.runner_assignment_records.append(assignment)
        event["decision"] = "RUNNER_ASSIGNED"
        event["selected_fill_time"] = selected.fill_time.isoformat()
        event["rejected_lot_count"] = rejected
        self.fresh_event_records.append(event)

    def _is_fresh_breakout(self, current: DailyRecord) -> bool:
        if len(self.daily_records) < 21:
            return False
        current_prior20 = max(point.high for point in self.daily_records[-20:])
        previous_prior20 = max(point.high for point in self.daily_records[-21:-1])
        return current.close > current_prior20 and self.daily_records[-1].close <= previous_prior20

    def _candidate_filter(self, current: DailyRecord) -> tuple[bool, dict]:
        if self.candidate == "FRESH_DONCHIAN20_RUNNER":
            return True, {"fresh_breakout": True}
        if self.candidate == "FRESH_DONCHIAN20_PLUS_7D_MOMENTUM_ACCELERATION":
            if len(self.daily_records) < 14:
                return False, {"reason": "INSUFFICIENT_14_COMPLETE_DAYS"}
            close_t7 = self.daily_records[-7].close
            close_t14 = self.daily_records[-14].close
            recent = current.close / close_t7 - base.ONE
            previous = close_t7 / close_t14 - base.ONE
            acceleration = recent - previous
            return acceleration > ZERO, {
                "m7_recent": str(recent),
                "m7_previous": str(previous),
                "acceleration": str(acceleration),
                "threshold": "0",
            }
        if self.candidate == "FRESH_DONCHIAN20_PLUS_DAILY_RANGE_EXPANSION":
            prior_atr = self.daily_records[-1].atr14 if self.daily_records else None
            if prior_atr is None:
                return False, {"reason": "PRIOR_ATR14_UNAVAILABLE"}
            return current.true_range > prior_atr, {
                "current_true_range": str(current.true_range),
                "prior_atr14": str(prior_atr),
                "range_to_prior_atr": str(current.true_range / prior_atr),
                "threshold": "1.0",
            }
        raise ValueError(self.candidate)

    def _fill_exits(self, bar: base.Bar) -> None:
        runner_fills = 0
        for lot in self.lots:
            if lot.exit_queued_at is None or lot.fill_time not in self.runner_epoch_by_fill:
                continue
            net = base.estimated_net(lot.quantity, bar.open)
            if base.money(net - lot.cost) > ZERO:
                runner_fills += 1
        super()._fill_exits(bar)
        self.runner_exit_fills += runner_fills

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            lot.highest_close = max(lot.highest_close, bar.close)
            pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            if lot.fill_time in self.runner_epoch_by_fill:
                if self.atr14 is None:
                    continue
                candidate_stop = lot.highest_close - self.atr14 * V2A_MULTIPLIER
                lot.ratchet_stop = (
                    candidate_stop
                    if lot.ratchet_stop is None
                    else max(lot.ratchet_stop, candidate_stop)
                )
                if bar.close <= lot.ratchet_stop and pnl > ZERO:
                    lot.exit_queued_at = bar.open_time
                    self._count_trigger("RUNNER_ATR_TRAIL_1_50")
            elif lot.entry_atr is not None and pnl >= lot.entry_atr * lot.quantity:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("DEFAULT_ENTRY_ATR_TARGET_1_00")

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        runner_count = len(self.runner_assignment_records)
        sparse_limit = math.ceil(self.buy_count * 0.10)
        uniqueness = all(count <= 1 for count in self.epoch_runner_counts.values())
        active_runner_count = sum(
            lot.fill_time in self.runner_epoch_by_fill for lot in self.lots
        )
        result["candidate"] = self.candidate
        result["sparse_runner_audit"] = {
            "fresh_breakout_events": self.fresh_breakout_events,
            "candidate_confirmed_events": self.candidate_confirmed_events,
            "candidate_filter_rejected_events": self.candidate_filter_rejected_events,
            "fresh_breakout_no_eligible_lot_events": self.fresh_breakout_no_eligible_events,
            "trend_epoch_resets": self.epoch_reset_count,
            "distinct_epochs_observed": len(self.epochs_observed),
            "runner_assignments": runner_count,
            "runner_share_of_buys_pct": (
                round(runner_count * 100 / self.buy_count, 6) if self.buy_count else 0.0
            ),
            "runner_exit_fills": self.runner_exit_fills,
            "target_exit_fills": self.sell_count - self.runner_exit_fills,
            "active_open_runners": active_runner_count,
            "rejected_runner_lots_total": (
                self.runner_rejected_epoch_used + self.runner_rejected_same_event
            ),
            "rejected_runner_lots_epoch_used": self.runner_rejected_epoch_used,
            "rejected_runner_lots_same_event_cap": self.runner_rejected_same_event,
            "epoch_runner_counts": {
                str(epoch): count for epoch, count in sorted(self.epoch_runner_counts.items())
            },
            "epoch_uniqueness_pass": uniqueness,
            "validation_style_sparse_limit_10pct_of_buys": sparse_limit,
            "validation_style_sparse_count_pass": runner_count <= sparse_limit,
            "runner_assignments_detail": self.runner_assignment_records,
            "fresh_breakout_events_detail": self.fresh_event_records,
        }
        return result


EXPECTED_V2A_DESIGN = (
    "277.82610201", "-101.42144167", "176.40466034", "22.420205", 371.0, 1561.8,
    99, 93, 6, 7, "42.945585", "3067.82610201",
)
EXPECTED_V2C = {
    "EMA20_SLOPE_NONPOSITIVE": (
        "113.10322216", "-3.20820121", "109.89502095", "8.978650", 384.0, 1704.4,
        51, 50, 1, 0, "26.770862", "1613.10322216",
    ),
    "CLOSE_BELOW_EMA5": (
        "81.23826208", "-3.20820121", "78.03006087", "5.648317", 288.0, 1416.0,
        51, 50, 1, 0, "20.227086", "1581.23826208",
    ),
    "ATR1_REVERSAL": (
        "85.36169189", "-3.20820121", "82.15349068", "7.192787", 336.0, 1468.8,
        51, 50, 1, 0, "24.397401", "1585.36169189",
    ),
    "DONCHIAN5_NEGATIVE_MOMENTUM": (
        "116.85354741", "-3.20820121", "113.64534620", "8.936078", 392.5, 1846.6,
        51, 50, 1, 0, "28.963748", "1616.85354741",
    ),
    "CONSENSUS_2_OF_4": (
        "87.71294693", "-3.20820121", "84.50474572", "7.095375", 348.0, 1487.0,
        51, 50, 1, 0, "25.004788", "1587.71294693",
    ),
}
EXPECTED_V2C_WINS = {
    "EMA20_SLOPE_NONPOSITIVE": (4, 0),
    "CLOSE_BELOW_EMA5": (3, 0),
    "ATR1_REVERSAL": (2, 0),
    "DONCHIAN5_NEGATIVE_MOMENTUM": (4, 0),
    "CONSENSUS_2_OF_4": (3, 0),
}


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def dependency_hash() -> str:
    return file_sha256(Path(base.__file__))


def specification_hash() -> str:
    return file_sha256(SPEC_PATH)


def verify_preregistration_artifacts() -> tuple[str, str]:
    spec_sha = specification_hash()
    dependency_sha = dependency_hash()
    problems: list[dict] = []
    if spec_sha != EXPECTED_SPEC_SHA256:
        problems.append(
            {"artifact": "specification", "expected": EXPECTED_SPEC_SHA256, "actual": spec_sha}
        )
    if dependency_sha != EXPECTED_V2C_RUNNER_SHA256:
        problems.append(
            {
                "artifact": "v2c_checkpoint_dependency",
                "expected": EXPECTED_V2C_RUNNER_SHA256,
                "actual": dependency_sha,
            }
        )
    if problems:
        raise base.ResearchReject("PREREGISTRATION_REJECT", problems)
    return spec_sha, dependency_sha


def simulate_v2d(
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
        raise base.ResearchReject(
            "DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}"
        )
    engine = V2DEngine(candidate, cap=cap)
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


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = base.reproduce_checkpoints(bars)
    mismatches: list[dict] = []
    actual_v2a_design = base.checkpoint_tuple(baselines["v2a"]["design"])
    if actual_v2a_design != EXPECTED_V2A_DESIGN:
        mismatches.append(
            {
                "checkpoint": "v2a_design",
                "expected": EXPECTED_V2A_DESIGN,
                "actual": actual_v2a_design,
            }
        )

    v2c: dict[str, dict] = {}
    for factor in base.FACTORS:
        validation = base.simulate(bars, VALIDATION, "v2c", factor=factor)
        folds = {
            name: base.simulate(bars, window, "v2c", factor=factor)
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
        actual = base.checkpoint_tuple(validation)
        if actual != EXPECTED_V2C[factor]:
            mismatches.append(
                {
                    "checkpoint": f"v2c_{factor}",
                    "expected": EXPECTED_V2C[factor],
                    "actual": actual,
                }
            )
        if (total_wins, hold_wins) != EXPECTED_V2C_WINS[factor]:
            mismatches.append(
                {
                    "checkpoint": f"v2c_{factor}_annual_wins",
                    "expected": EXPECTED_V2C_WINS[factor],
                    "actual": (total_wins, hold_wins),
                }
            )
        v2c[factor] = {
            "validation": validation,
            "folds": folds,
            "annual_total_wins": total_wins,
            "annual_median_hold_wins": hold_wins,
        }
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["v2c"] = v2c
    return baselines


def candidate_gates(
    result: dict,
    v1: dict,
    v2a: dict,
    total_wins: int,
    hold_wins: int,
) -> dict[str, bool]:
    audit = result["sparse_runner_audit"]
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
        "epoch_uniqueness": audit["epoch_uniqueness_pass"],
        "validation_runner_count_at_most_ceil_10pct_buys": (
            audit["validation_style_sparse_count_pass"]
        ),
    }


def freeze_hash(
    data_sha: str,
    spec_sha: str,
    dependency_sha: str,
    runner_sha: str,
    candidate: str,
) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "selection_data_sha256": data_sha,
        "specification_sha256": spec_sha,
        "v2c_dependency_sha256": dependency_sha,
        "runner_sha256": runner_sha,
        "candidate": candidate,
    }
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def run_preselect(output: Path) -> dict:
    spec_sha, dependency_sha = verify_preregistration_artifacts()
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
    v1_validation = baselines["v1"]["validation"]
    v2a_validation = baselines["v2a"]["validation"]
    candidates: list[dict] = []
    for candidate in CANDIDATES:
        design = simulate_v2d(bars, DESIGN, candidate)
        validation = simulate_v2d(bars, VALIDATION, candidate)
        folds = {
            name: simulate_v2d(bars, window, candidate)
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
        gates = candidate_gates(
            validation, v1_validation, v2a_validation, total_wins, hold_wins
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
    qualified = [candidate for candidate in candidates if candidate["pass"]]
    qualified.sort(
        key=lambda candidate: (
            base.dec(candidate["validation"], "total_pnl_usdt"),
            -base.dec(candidate["validation"], "max_drawdown_pct"),
            -D(str(candidate["validation"]["p90_hold_hours"])),
            -D(str(candidate["validation"]["median_hold_hours"])),
            -D(str(candidate["validation"]["sparse_runner_audit"]["runner_assignments"])),
        ),
        reverse=True,
    )
    selected = qualified[0] if qualified else None
    runner_sha = source_hash()
    result = {
        "status": "CANDIDATE_FROZEN" if selected else "NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        "specification_sha256": spec_sha,
        "v2c_dependency_sha256": dependency_sha,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_V1_V2A_V2B_V2C",
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
        result["freeze_sha256"] = freeze_hash(
            digest, spec_sha, dependency_sha, runner_sha, candidate
        )
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["sparse_runner_audit"]
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
        "epoch_uniqueness": audit["epoch_uniqueness_pass"],
    }


def run_oos(preselect_path: Path, cutoff: datetime, output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OOS_SEAL_REJECT", f"output already exists: {output}")
    preselection = json.loads(preselect_path.read_text(encoding="utf-8"))
    if preselection.get("status") != "CANDIDATE_FROZEN":
        raise base.ResearchReject("OOS_SEAL_REJECT", "preselection froze no candidate")
    spec_sha, dependency_sha = verify_preregistration_artifacts()
    runner_sha = source_hash()
    candidate = preselection.get("frozen_candidate_key")
    if candidate not in CANDIDATES:
        raise base.ResearchReject("OOS_SEAL_REJECT", f"unknown candidate {candidate}")
    exact = {
        "selection_data_sha256": SELECTION_SHA256,
        "specification_sha256": spec_sha,
        "v2c_dependency_sha256": dependency_sha,
        "runner_sha256": runner_sha,
    }
    for field, expected in exact.items():
        if preselection.get(field) != expected:
            raise base.ResearchReject("OOS_SEAL_REJECT", f"{field} mismatch")
    expected_freeze = freeze_hash(
        SELECTION_SHA256, spec_sha, dependency_sha, runner_sha, candidate
    )
    if preselection.get("freeze_sha256") != expected_freeze:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    if cutoff <= SELECTION_CUTOFF:
        raise base.ResearchReject("OOS_SEAL_REJECT", "cutoff must be after 2025-01-01")

    bars = base.parse_rows(base.fetch_rows(cutoff))
    available_end = bars[-1].close_time
    window = (SELECTION_CUTOFF, available_end)
    v1 = base.simulate(bars, window, "v1")
    v2a = base.simulate(bars, window, "v2a")
    v2d = simulate_v2d(bars, window, candidate)
    gates = oos_gates(v2d, v1, v2a)
    one_slot = {
        "design": simulate_v2d(bars, DESIGN, candidate, cap=base.LOT_COST),
        "validation": simulate_v2d(bars, VALIDATION, candidate, cap=base.LOT_COST),
        "folds": {
            name: simulate_v2d(bars, fold, candidate, cap=base.LOT_COST)
            for name, fold in FOLDS.items()
        },
        "oos": simulate_v2d(bars, window, candidate, cap=base.LOT_COST),
    }
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
        "oos": {
            "v1_reference_250": v1,
            "v2a_reference_250": v2a,
            "v2d_reference_250": v2d,
            "gates": gates,
        },
        "one_slot_overlay_30": one_slot,
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
