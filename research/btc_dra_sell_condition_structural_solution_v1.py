#!/usr/bin/env python3
"""Causal DRA structural core/runner research with sealed 2025+ OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_breakout_entitlement_target_handoff_runner_v2e as v2e

v2d = v2e.v2d
base = v2e.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = "BTC_DRA_SELL_CONDITION_STRUCTURAL_SOLUTION_V1_RESEARCH"
CANDIDATE = "GLOBAL_ONE_ACTIVE_FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_RUNNER"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-sell-condition-structural-solution-v1-research.md"
V2E_SPEC_PATH = ROOT / "docs" / "btc-dra-breakout-entitlement-target-handoff-runner-v2e-research.md"

EXPECTED_SPEC_SHA256 = "33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be"
EXPECTED_V2E_SPEC_SHA256 = "cc75af188264a49c4e915d72a911aac23f512ed749392324f10795477f8713f2"
EXPECTED_V2C_RUNNER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_V2D_RUNNER_SHA256 = "5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952"
EXPECTED_V2E_RUNNER_SHA256 = "0d0f8542d4d0863e7148d4ce69abb7ed41cb1abb053ff382effd9e3ab8d65d9b"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V2E_DESIGN = (
    "185.17761344", "-9.57666552", "175.60094792", "54.242826", 206.0,
    1596.4, 97, 89, 8, 15, "51.130048", "2855.17761344",
)
EXPECTED_V2E_VALIDATION = (
    "89.40956909", "-3.20820121", "86.20136788", "6.624292", 181.5,
    833.4, 51, 50, 1, 0, "17.041040", "1589.40956909",
)
EXPECTED_V2E_AUDIT = (
    38, 40, 41, 16, 4, 18, 5, 11, False, "NONE", 11, 21.568627,
    11, 39, 0, 0, 59.0, 213.0, True, True, 6, False,
)
EXPECTED_V2E_WINS = (3, 1)


class StructuralEngine(v2e.V2EEngine):
    """V2E core and handoff with one global open runner at any instant."""

    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.mode = "structural_solution_v1"
        self.factor = CANDIDATE
        self.candidate = CANDIDATE
        self.fresh_events_rejected_active_runner = 0
        self.max_simultaneous_open_runners = 0
        self.exit_records: list[dict] = []
        self.pending_runner_counterfactuals: set[datetime] = set()
        self.runner_core_counterfactual_by_fill: dict[datetime, D] = {}

    def start_window(self) -> None:
        super().start_window()
        self.fresh_events_rejected_active_runner = 0
        self.max_simultaneous_open_runners = 0
        self.exit_records.clear()
        self.pending_runner_counterfactuals.clear()
        self.runner_core_counterfactual_by_fill.clear()

    def _active_runner_count(self) -> int:
        return sum(lot.fill_time in self.runner_epoch_by_fill for lot in self.lots)

    def _observe_global_runner_count(self) -> None:
        self.max_simultaneous_open_runners = max(
            self.max_simultaneous_open_runners,
            self._active_runner_count(),
        )

    def _fill_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.fill_time in self.pending_runner_counterfactuals:
                counterfactual = base.money(
                    base.estimated_net(lot.quantity, bar.open) - lot.cost
                )
                self.runner_core_counterfactual_by_fill[lot.fill_time] = counterfactual
                self.pending_runner_counterfactuals.remove(lot.fill_time)
            if lot.exit_queued_at is None:
                continue
            pnl = base.money(base.estimated_net(lot.quantity, bar.open) - lot.cost)
            if pnl <= ZERO:
                continue
            self.exit_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "exit_fill_time": bar.open_time.isoformat(),
                    "path": (
                        "RUNNER_ATR_TRAIL_1_50"
                        if lot.fill_time in self.runner_epoch_by_fill
                        else "CORE_ENTRY_ATR_TARGET_1_00"
                    ),
                    "realized_pnl_usdt": str(pnl),
                    "hold_hours": (
                        bar.open_time - lot.fill_time
                    ).total_seconds() / 3600,
                }
            )
        super()._fill_exits(bar)
        self._observe_global_runner_count()

    def _queue_exits(self, bar: base.Bar) -> None:
        runners_before = set(self.runner_epoch_by_fill)
        super()._queue_exits(bar)
        self.pending_runner_counterfactuals.update(
            set(self.runner_epoch_by_fill) - runners_before
        )
        self._observe_global_runner_count()

    def _daily_runner_state(self, current: v2d.DailyRecord) -> None:
        previous = self.daily_records[-1] if self.daily_records else None
        if (
            previous is not None
            and current.close < current.ema20
            and previous.close >= previous.ema20
        ):
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
        active_runners = self._active_runner_count()
        event = {
            "day": current.day.isoformat(),
            "epoch": self.epoch_id,
            "state_before": self.entitlement_state,
            "active_open_runners": active_runners,
        }
        if active_runners:
            self.fresh_events_rejected_active_runner += 1
            event["decision"] = "FRESH_EVENT_REJECTED_GLOBAL_RUNNER_ACTIVE"
        elif self.entitlement_state == "NONE":
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

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        self._observe_global_runner_count()
        result = super().result(final_bar, start, end)
        audit = result["entitlement_runner_audit"]
        audit["fresh_breakout_events_rejected_active_runner"] = (
            self.fresh_events_rejected_active_runner
        )
        audit["maximum_simultaneous_open_runners"] = self.max_simultaneous_open_runners
        audit["global_one_active_runner_pass"] = (
            self.max_simultaneous_open_runners <= 1
        )
        runner_exits = [row for row in self.exit_records if row["path"].startswith("RUNNER")]
        core_exits = [row for row in self.exit_records if row["path"].startswith("CORE")]
        runner_details = []
        for row in runner_exits:
            fill_time = datetime.fromisoformat(row["fill_time"])
            counterfactual = self.runner_core_counterfactual_by_fill.get(fill_time)
            runner_pnl = D(row["realized_pnl_usdt"])
            runner_details.append(
                {
                    **row,
                    "counterfactual_core_next_open_pnl_usdt": (
                        str(counterfactual) if counterfactual is not None else None
                    ),
                    "runner_incremental_pnl_usdt": (
                        str(base.money(runner_pnl - counterfactual))
                        if counterfactual is not None else None
                    ),
                }
            )
        runner_uplifts = sorted(
            (
                D(row["runner_incremental_pnl_usdt"])
                for row in runner_details
                if row["runner_incremental_pnl_usdt"] is not None
            ),
            reverse=True,
        )
        result["exit_path_attribution"] = {
            "core_exit_count": len(core_exits),
            "core_realized_pnl_usdt": str(
                base.money(sum((D(row["realized_pnl_usdt"]) for row in core_exits), ZERO))
            ),
            "runner_exit_count": len(runner_exits),
            "runner_realized_pnl_usdt": str(
                base.money(sum((D(row["realized_pnl_usdt"]) for row in runner_exits), ZERO))
            ),
            "runner_counterfactual_core_pnl_usdt": str(
                base.money(
                    sum(
                        (
                            D(row["counterfactual_core_next_open_pnl_usdt"])
                            for row in runner_details
                            if row["counterfactual_core_next_open_pnl_usdt"] is not None
                        ),
                        ZERO,
                    )
                )
            ),
            "runner_incremental_pnl_vs_core_usdt": str(
                base.money(sum(runner_uplifts, ZERO))
            ),
            "oracle_top_6_runner_incremental_pnl_usdt": str(
                base.money(sum(runner_uplifts[:6], ZERO))
            ),
            "oracle_top_6_is_diagnostic_not_causal_selection": True,
            "open_runner_counterfactual_count": sum(
                lot.fill_time in self.runner_epoch_by_fill for lot in self.lots
            ),
            "exit_records": core_exits,
            "runner_exit_records": runner_details,
        }
        result["mode"] = "structural_solution_v1"
        result["factor"] = CANDIDATE
        result["candidate"] = CANDIDATE
        return result


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v2e_specification_sha256": file_sha256(V2E_SPEC_PATH),
        "v2c_dependency_sha256": file_sha256(Path(base.__file__)),
        "v2d_dependency_sha256": file_sha256(Path(v2d.__file__)),
        "v2e_dependency_sha256": file_sha256(Path(v2e.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v2e_specification_sha256": EXPECTED_V2E_SPEC_SHA256,
        "v2c_dependency_sha256": EXPECTED_V2C_RUNNER_SHA256,
        "v2d_dependency_sha256": EXPECTED_V2D_RUNNER_SHA256,
        "v2e_dependency_sha256": EXPECTED_V2E_RUNNER_SHA256,
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
    selected = [
        bar for bar in bars
        if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject(
            "DATA_REJECT",
            f"no bars for {start.isoformat()}..{end.isoformat()}",
        )
    engine = StructuralEngine(cap=cap)
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


def v2e_audit_tuple(result: dict) -> tuple:
    audit = result["entitlement_runner_audit"]
    return (
        audit["fresh_breakout_events"],
        audit["trend_epoch_resets"],
        audit["distinct_epochs_observed"],
        audit["entitlement_creations"],
        audit["entitlement_duplicate_events_pending"],
        audit["entitlement_duplicate_events_consumed"],
        audit["entitlement_expirations"],
        audit["entitlement_handoffs"],
        audit["pending_entitlement_at_end"],
        audit["ending_entitlement_state"],
        audit["runner_assignments"],
        audit["runner_share_of_buys_pct"],
        audit["runner_exit_fills"],
        audit["target_exit_fills"],
        audit["active_open_runners"],
        audit["same_bar_handoff_rejected_lots"],
        audit["median_entitlement_to_handoff_hours"],
        audit["p90_entitlement_to_handoff_hours"],
        audit["epoch_entitlement_uniqueness_pass"],
        audit["epoch_runner_uniqueness_pass"],
        audit["validation_style_sparse_limit_10pct_of_buys"],
        audit["validation_style_sparse_count_pass"],
    )


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = v2e.reproduce_checkpoints(bars)
    design = v2e.simulate_v2e(bars, DESIGN)
    validation = v2e.simulate_v2e(bars, VALIDATION)
    folds = {
        name: v2e.simulate_v2e(bars, window)
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
    checks = {
        "v2e_design": (base.checkpoint_tuple(design), EXPECTED_V2E_DESIGN),
        "v2e_validation": (
            base.checkpoint_tuple(validation), EXPECTED_V2E_VALIDATION
        ),
        "v2e_validation_audit": (v2e_audit_tuple(validation), EXPECTED_V2E_AUDIT),
        "v2e_annual_wins": ((total_wins, hold_wins), EXPECTED_V2E_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["v2e"] = {
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
        "global_one_active_runner": audit["global_one_active_runner_pass"],
        "epoch_entitlement_uniqueness": audit["epoch_entitlement_uniqueness_pass"],
        "epoch_runner_uniqueness": audit["epoch_runner_uniqueness_pass"],
        "validation_runner_count_at_most_ceil_10pct_buys": (
            audit["validation_style_sparse_count_pass"]
        ),
    }


def metric_delta(left: dict, right: dict, field: str) -> str:
    return str(base.money(base.dec(left, field) - base.dec(right, field)))


def structural_diagnosis(
    candidate: dict,
    baselines: dict,
    gates: dict[str, bool],
) -> dict:
    v1 = baselines["v1"]["validation"]
    v2a = baselines["v2a"]["validation"]
    v2e_result = baselines["v2e"]["validation"]
    failed = [name for name, passed in gates.items() if not passed]
    return {
        "constraint_set": [
            "FULL_30_USDT_INDEPENDENT_LOTS",
            "PRICE_ONLY_CAUSAL_EXIT",
            "PROFIT_ONLY_NO_FORCED_LOSS",
            "NO_FINAL_LIQUIDATION",
            "FAST_1_ENTRY_ATR_CORE",
            "GLOBAL_ONE_ACTIVE_RUNNER",
        ],
        "validation_frontier": {
            "v1": v1,
            "v2a": v2a,
            "v2d_fresh_donchian20": baselines["v2d"]["FRESH_DONCHIAN20_RUNNER"]["validation"],
            "v2e": v2e_result,
            "global_one_active_candidate": candidate,
        },
        "candidate_delta_vs_v1_usdt": {
            "realized": metric_delta(candidate, v1, "realized_usdt"),
            "unrealized": metric_delta(candidate, v1, "unrealized_usdt"),
            "total": metric_delta(candidate, v1, "total_pnl_usdt"),
        },
        "candidate_delta_vs_v2a_usdt": {
            "realized": metric_delta(candidate, v2a, "realized_usdt"),
            "unrealized": metric_delta(candidate, v2a, "unrealized_usdt"),
            "total": metric_delta(candidate, v2a, "total_pnl_usdt"),
        },
        "candidate_delta_vs_v2e_usdt": {
            "realized": metric_delta(candidate, v2e_result, "realized_usdt"),
            "unrealized": metric_delta(candidate, v2e_result, "unrealized_usdt"),
            "total": metric_delta(candidate, v2e_result, "total_pnl_usdt"),
        },
        "failed_gates": failed,
        "terminal_interpretation": (
            "FROZEN_ARCHITECTURE_PASSED"
            if not failed
            else "OBSERVED_CONSTRAINT_SET_CANNOT_JOINTLY_MEET_FROZEN_PROFIT_HOLD_AND_SPARSITY_GATES"
        ),
        "untested_minimum_contract_changes_requiring_new_authorization": [
            "PARTIAL_CORE_AND_RUNNER_EXIT_WITHIN_ONE_30_USDT_LOT",
            "ENTRY_QUALITY_SELECTION_BEFORE_CAPITAL_IS_COMMITTED",
            "LOSS_OR_TIME_RELEASE_FOR_STALE_INVENTORY",
        ] if failed else [],
    }


def freeze_hash(data_sha: str, hashes: dict[str, str], runner_sha: str) -> str:
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
    design = simulate_candidate(bars, DESIGN)
    validation = simulate_candidate(bars, VALIDATION)
    folds = {
        name: simulate_candidate(bars, window)
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
        "status": "CANDIDATE_FROZEN" if passed else "CONSTRAINT_SET_INFEASIBLE_KEEP_V1",
        "selection_decision": "CANDIDATE_FROZEN" if passed else "CONSTRAINT_SET_INFEASIBLE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_V1_V2A_V2B_V2C_V2D_V2E",
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "structural_diagnosis": structural_diagnosis(validation, baselines, gates),
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
        "global_one_active_runner": audit["global_one_active_runner_pass"],
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
    v1_result = base.simulate(bars, window, "v1")
    v2a_result = base.simulate(bars, window, "v2a")
    candidate = simulate_candidate(bars, window)
    gates = oos_gates(candidate, v1_result, v2a_result)
    one_slot = {
        "design": simulate_candidate(bars, DESIGN, cap=base.LOT_COST),
        "validation": simulate_candidate(bars, VALIDATION, cap=base.LOT_COST),
        "folds": {
            name: simulate_candidate(bars, fold, cap=base.LOT_COST)
            for name, fold in FOLDS.items()
        },
        "oos": simulate_candidate(bars, window, cap=base.LOT_COST),
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
            "v1_reference_250": v1_result,
            "v2a_reference_250": v2a_result,
            "candidate_reference_250": candidate,
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
        "structural_diagnosis",
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
