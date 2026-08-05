#!/usr/bin/env python3
"""Causal per-lot DRA profit-lock exit research with sealed 2025+ OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_sell_condition_structural_solution_v1 as previous

base = previous.base
D = Decimal
ZERO = D("0")
HALF = D("0.50")

RESEARCH_IDENTITY = "BTC_DRA_INDEPENDENT_LOT_PROFIT_LOCK_EXIT_V1_RESEARCH"
CANDIDATE = "ENTRY_ATR_1R_ARM_PEAK_PROFIT_50PCT_LOCK_EXIT"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-profit-lock-exit-v1-research.md"
PREVIOUS_SPEC_PATH = ROOT / "docs" / "btc-dra-sell-condition-structural-solution-v1-research.md"

EXPECTED_SPEC_SHA256 = "98b13326ebf22a345162a4046b2c0c64723aaaaf0753fa1ca031bec8b3008277"
EXPECTED_PREVIOUS_SPEC_SHA256 = "33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be"
EXPECTED_PREVIOUS_RUNNER_SHA256 = "41e1150510a1a68bff2d12fe4b3edf7d594623e701ab46f13485039e4bb74fa3"
EXPECTED_V2C_RUNNER_SHA256 = previous.EXPECTED_V2C_RUNNER_SHA256
EXPECTED_V2D_RUNNER_SHA256 = previous.EXPECTED_V2D_RUNNER_SHA256
EXPECTED_V2E_RUNNER_SHA256 = previous.EXPECTED_V2E_RUNNER_SHA256

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_PREVIOUS_DESIGN = (
    "186.21921267", "-14.17647454", "172.04273813", "54.868711", 155.5,
    1684.6, 96, 88, 8, 20, "50.759754", "2826.21921267",
)
EXPECTED_PREVIOUS_VALIDATION = (
    "89.40956909", "-3.20820121", "86.20136788", "6.624292", 181.5,
    833.4, 51, 50, 1, 0, "17.041040", "1589.40956909",
)
EXPECTED_PREVIOUS_AUDIT = (
    38, 40, 41, 16, 4, 3, 5, 11, 11, 11, 39, 15, 1, True, 6, False,
)
EXPECTED_PREVIOUS_ATTRIBUTION = (
    39, "51.73947360", 11, "37.67009549", "13.32682158",
    "24.34327391", "26.68392228",
)
EXPECTED_PREVIOUS_WINS = (3, 1)


class ProfitLockEngine(base.Engine):
    """Independent lot state with 1R arm and a monotone 50% peak-profit floor."""

    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__("profit_lock_v1", factor=CANDIDATE, cap=cap)
        self.lot_state: dict[datetime, dict] = {}
        self.queue_snapshot_by_fill: dict[datetime, dict] = {}
        self.arming_records: list[dict] = []
        self.trigger_records: list[dict] = []
        self.exit_fill_records: list[dict] = []
        self.deferred_fill_records: list[dict] = []
        self.entry_atr_missing_fills: set[datetime] = set()
        self.trigger_condition_violations = 0
        self.profit_floor_decrease_violations = 0
        self.nonpositive_exit_fill_violations = 0

    def _fill_buy(self, bar: base.Bar) -> None:
        existing = {lot.fill_time for lot in self.lots}
        super()._fill_buy(bar)
        for lot in self.lots:
            if lot.fill_time in existing or lot.fill_time in self.lot_state:
                continue
            if lot.entry_atr is None:
                self.entry_atr_missing_fills.add(lot.fill_time)
            self.lot_state[lot.fill_time] = {
                "peak_pnl": None,
                "armed": False,
                "armed_at": None,
                "last_profit_floor": None,
                "floor_monotone": True,
            }

    def _fill_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is None:
                continue
            fill_pnl = base.money(base.estimated_net(lot.quantity, bar.open) - lot.cost)
            snapshot = self.queue_snapshot_by_fill.get(lot.fill_time, {})
            record = {
                **snapshot,
                "exit_fill_time": bar.open_time.isoformat(),
                "realized_net_pnl_usdt": str(fill_pnl),
                "hold_hours": (
                    bar.open_time - lot.fill_time
                ).total_seconds() / 3600,
            }
            if fill_pnl > ZERO:
                record["decision"] = "NET_POSITIVE_NEXT_OPEN_FILL"
                self.exit_fill_records.append(record)
            else:
                record["decision"] = "NEXT_OPEN_FILL_DEFERRED_NOT_NET_POSITIVE"
                self.deferred_fill_records.append(record)
            self.queue_snapshot_by_fill.pop(lot.fill_time, None)
        super()._fill_exits(bar)

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            current_pnl = base.money(
                base.estimated_net(lot.quantity, bar.close) - lot.cost
            )
            previous_peak = state["peak_pnl"]
            peak_pnl = (
                current_pnl
                if previous_peak is None
                else max(previous_peak, current_pnl)
            )
            state["peak_pnl"] = peak_pnl
            entry_risk = (
                None
                if lot.entry_atr is None
                else lot.entry_atr * lot.quantity
            )

            if (
                not state["armed"]
                and entry_risk is not None
                and peak_pnl >= entry_risk
            ):
                state["armed"] = True
                state["armed_at"] = bar.open_time
                self.arming_records.append(
                    {
                        "signal_time": lot.signal_time.isoformat(),
                        "fill_time": lot.fill_time.isoformat(),
                        "armed_at": bar.open_time.isoformat(),
                        "hours_fill_to_arm": (
                            bar.open_time - lot.fill_time
                        ).total_seconds() / 3600,
                        "entry_risk_1r_usdt": str(base.money(entry_risk)),
                        "peak_net_pnl_at_arm_usdt": str(peak_pnl),
                    }
                )

            if not state["armed"]:
                continue
            profit_floor = base.money(peak_pnl * HALF)
            previous_floor = state["last_profit_floor"]
            if previous_floor is not None and profit_floor < previous_floor:
                state["floor_monotone"] = False
                self.profit_floor_decrease_violations += 1
            state["last_profit_floor"] = profit_floor

            if current_pnl <= ZERO or current_pnl > profit_floor:
                continue
            condition_pass = (
                entry_risk is not None
                and peak_pnl >= entry_risk
                and current_pnl > ZERO
                and current_pnl <= profit_floor
                and state["floor_monotone"]
            )
            if not condition_pass:
                self.trigger_condition_violations += 1
            giveback = base.money(peak_pnl - current_pnl)
            giveback_fraction = giveback / peak_pnl if peak_pnl > ZERO else ZERO
            trigger = {
                "signal_time": lot.signal_time.isoformat(),
                "fill_time": lot.fill_time.isoformat(),
                "queue_time": bar.open_time.isoformat(),
                "entry_risk_1r_usdt": str(base.money(entry_risk)),
                "current_net_pnl_usdt": str(current_pnl),
                "peak_net_pnl_usdt": str(peak_pnl),
                "profit_floor_50pct_usdt": str(profit_floor),
                "giveback_usdt": str(giveback),
                "giveback_fraction_of_peak": str(giveback_fraction),
                "armed": state["armed"],
                "floor_monotone": state["floor_monotone"],
                "condition_pass": condition_pass,
            }
            lot.exit_queued_at = bar.open_time
            self._count_trigger("ENTRY_ATR_1R_ARM_PEAK_PROFIT_50PCT_LOCK")
            self.trigger_records.append(trigger)
            self.queue_snapshot_by_fill[lot.fill_time] = trigger

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        open_details = []
        for lot in self.lots:
            state = self.lot_state[lot.fill_time]
            current_pnl = base.money(
                base.estimated_net(lot.quantity, final_bar.close) - lot.cost
            )
            entry_risk = (
                None if lot.entry_atr is None else lot.entry_atr * lot.quantity
            )
            open_details.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "entry_risk_1r_usdt": (
                        str(base.money(entry_risk)) if entry_risk is not None else None
                    ),
                    "current_net_pnl_usdt": str(current_pnl),
                    "peak_net_pnl_usdt": (
                        str(state["peak_pnl"])
                        if state["peak_pnl"] is not None else None
                    ),
                    "armed": state["armed"],
                    "armed_at": (
                        state["armed_at"].isoformat()
                        if state["armed_at"] is not None else None
                    ),
                    "profit_floor_50pct_usdt": (
                        str(state["last_profit_floor"])
                        if state["last_profit_floor"] is not None else None
                    ),
                    "floor_monotone": state["floor_monotone"],
                }
            )
        realized_from_records = base.money(
            sum(
                (D(row["realized_net_pnl_usdt"]) for row in self.exit_fill_records),
                ZERO,
            )
        )
        if realized_from_records != base.money(self.realized):
            self.nonpositive_exit_fill_violations += 1
        result["candidate"] = CANDIDATE
        result["profit_lock_audit"] = {
            "runner_or_entitlement_concept_present": False,
            "entry_atr_missing_buy_count": len(self.entry_atr_missing_fills),
            "lots_armed": len(self.arming_records),
            "exit_queues": len(self.trigger_records),
            "successful_exit_fills": len(self.exit_fill_records),
            "deferred_exit_fills": len(self.deferred_fill_records),
            "ending_open_armed_lots": sum(row["armed"] for row in open_details),
            "ending_open_unarmed_lots": sum(not row["armed"] for row in open_details),
            "trigger_condition_violations": self.trigger_condition_violations,
            "profit_floor_decrease_violations": self.profit_floor_decrease_violations,
            "nonpositive_or_accounting_exit_violations": (
                self.nonpositive_exit_fill_violations
            ),
            "entry_atr_complete_pass": not self.entry_atr_missing_fills,
            "all_trigger_conditions_pass": self.trigger_condition_violations == 0,
            "profit_floor_monotonicity_pass": (
                self.profit_floor_decrease_violations == 0
            ),
            "all_realized_exits_strictly_net_positive_pass": (
                self.nonpositive_exit_fill_violations == 0
                and all(
                    D(row["realized_net_pnl_usdt"]) > ZERO
                    for row in self.exit_fill_records
                )
            ),
            "realized_pnl_from_exit_records_usdt": str(realized_from_records),
            "arming_records": self.arming_records,
            "trigger_records": self.trigger_records,
            "exit_fill_records": self.exit_fill_records,
            "deferred_fill_records": self.deferred_fill_records,
            "ending_open_lot_state": open_details,
        }
        return result


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "previous_specification_sha256": file_sha256(PREVIOUS_SPEC_PATH),
        "v2c_dependency_sha256": file_sha256(Path(base.__file__)),
        "v2d_dependency_sha256": file_sha256(Path(previous.v2d.__file__)),
        "v2e_dependency_sha256": file_sha256(Path(previous.v2e.__file__)),
        "previous_structural_dependency_sha256": file_sha256(Path(previous.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "previous_specification_sha256": EXPECTED_PREVIOUS_SPEC_SHA256,
        "v2c_dependency_sha256": EXPECTED_V2C_RUNNER_SHA256,
        "v2d_dependency_sha256": EXPECTED_V2D_RUNNER_SHA256,
        "v2e_dependency_sha256": EXPECTED_V2E_RUNNER_SHA256,
        "previous_structural_dependency_sha256": EXPECTED_PREVIOUS_RUNNER_SHA256,
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
    engine = ProfitLockEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def previous_audit_tuple(result: dict) -> tuple:
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
        audit["runner_assignments"],
        audit["runner_exit_fills"],
        audit["target_exit_fills"],
        audit["fresh_breakout_events_rejected_active_runner"],
        audit["maximum_simultaneous_open_runners"],
        audit["global_one_active_runner_pass"],
        audit["validation_style_sparse_limit_10pct_of_buys"],
        audit["validation_style_sparse_count_pass"],
    )


def previous_attribution_tuple(result: dict) -> tuple:
    value = result["exit_path_attribution"]
    return (
        value["core_exit_count"],
        value["core_realized_pnl_usdt"],
        value["runner_exit_count"],
        value["runner_realized_pnl_usdt"],
        value["runner_counterfactual_core_pnl_usdt"],
        value["runner_incremental_pnl_vs_core_usdt"],
        value["oracle_top_6_runner_incremental_pnl_usdt"],
    )


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = previous.reproduce_checkpoints(bars)
    design = previous.simulate_candidate(bars, DESIGN)
    validation = previous.simulate_candidate(bars, VALIDATION)
    folds = {
        name: previous.simulate_candidate(bars, window)
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
        "previous_structural_design": (
            base.checkpoint_tuple(design), EXPECTED_PREVIOUS_DESIGN
        ),
        "previous_structural_validation": (
            base.checkpoint_tuple(validation), EXPECTED_PREVIOUS_VALIDATION
        ),
        "previous_structural_validation_audit": (
            previous_audit_tuple(validation), EXPECTED_PREVIOUS_AUDIT
        ),
        "previous_structural_validation_attribution": (
            previous_attribution_tuple(validation), EXPECTED_PREVIOUS_ATTRIBUTION
        ),
        "previous_structural_annual_wins": (
            (total_wins, hold_wins), EXPECTED_PREVIOUS_WINS
        ),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["previous_structural"] = {
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
    audit = result["profit_lock_audit"]
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
        "entry_atr_complete": audit["entry_atr_complete_pass"],
        "all_trigger_conditions": audit["all_trigger_conditions_pass"],
        "profit_floor_monotonicity": audit["profit_floor_monotonicity_pass"],
        "all_realized_exits_strictly_net_positive": (
            audit["all_realized_exits_strictly_net_positive_pass"]
        ),
    }


def metric_delta(left: dict, right: dict, field: str) -> str:
    return str(base.money(base.dec(left, field) - base.dec(right, field)))


def comparative_diagnosis(candidate: dict, baselines: dict, gates: dict) -> dict:
    v1 = baselines["v1"]["validation"]
    v2a = baselines["v2a"]["validation"]
    previous_result = baselines["previous_structural"]["validation"]
    return {
        "validation_delta_vs_v1_usdt": {
            "realized": metric_delta(candidate, v1, "realized_usdt"),
            "unrealized": metric_delta(candidate, v1, "unrealized_usdt"),
            "total": metric_delta(candidate, v1, "total_pnl_usdt"),
        },
        "validation_delta_vs_v2a_usdt": {
            "realized": metric_delta(candidate, v2a, "realized_usdt"),
            "unrealized": metric_delta(candidate, v2a, "unrealized_usdt"),
            "total": metric_delta(candidate, v2a, "total_pnl_usdt"),
        },
        "validation_delta_vs_previous_structural_usdt": {
            "realized": metric_delta(candidate, previous_result, "realized_usdt"),
            "unrealized": metric_delta(candidate, previous_result, "unrealized_usdt"),
            "total": metric_delta(candidate, previous_result, "total_pnl_usdt"),
        },
        "failed_gates": [name for name, passed in gates.items() if not passed],
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
        "baseline_parity": "PASS_V1_V2A_V2B_V2C_V2D_V2E_PREVIOUS_STRUCTURAL",
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "comparative_diagnosis": comparative_diagnosis(validation, baselines, gates),
        "qualified_count": 1 if passed else 0,
        "one_slot_overlay": None,
    }
    if passed:
        result["frozen_candidate_key"] = CANDIDATE
        result["freeze_sha256"] = freeze_hash(digest, artifact_hashes, runner_sha)
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["profit_lock_audit"]
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
        "entry_atr_complete": audit["entry_atr_complete_pass"],
        "all_trigger_conditions": audit["all_trigger_conditions_pass"],
        "profit_floor_monotonicity": audit["profit_floor_monotonicity_pass"],
        "all_realized_exits_strictly_net_positive": (
            audit["all_realized_exits_strictly_net_positive_pass"]
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
        "comparative_diagnosis",
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
