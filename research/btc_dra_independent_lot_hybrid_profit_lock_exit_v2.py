#!/usr/bin/env python3
"""Causal DRA hybrid per-lot profit-lock research with sealed 2025+ OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_independent_lot_profit_lock_exit_v1 as prior

previous = prior.previous
base = prior.base
D = Decimal
ZERO = D("0")
HALF = D("0.50")

RESEARCH_IDENTITY = "BTC_DRA_INDEPENDENT_LOT_HYBRID_PROFIT_LOCK_EXIT_V2_RESEARCH"
CANDIDATE = "ENTRY_ATR_1R_ARM_MAX_HALF_PEAK_OR_PEAK_MINUS_1R_LOCK_EXIT"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-hybrid-profit-lock-exit-v2-research.md"
PRIOR_SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-profit-lock-exit-v1-research.md"
PREVIOUS_STRUCTURAL_SPEC_PATH = (
    ROOT / "docs" / "btc-dra-sell-condition-structural-solution-v1-research.md"
)

EXPECTED_SPEC_SHA256 = "755c1b15e36bdd86ab70aa3f456fccafd5a711ff3fdfe34330b50ef509bf27e4"
EXPECTED_PRIOR_SPEC_SHA256 = "98b13326ebf22a345162a4046b2c0c64723aaaaf0753fa1ca031bec8b3008277"
EXPECTED_PRIOR_RUNNER_SHA256 = "739fea38ec89d2ea6dfc9161addc8a69213b13fbb6dd40d103155d49e06aae6c"
EXPECTED_PREVIOUS_STRUCTURAL_SPEC_SHA256 = prior.EXPECTED_PREVIOUS_SPEC_SHA256
EXPECTED_PREVIOUS_STRUCTURAL_RUNNER_SHA256 = prior.EXPECTED_PREVIOUS_RUNNER_SHA256
EXPECTED_V2C_RUNNER_SHA256 = prior.EXPECTED_V2C_RUNNER_SHA256
EXPECTED_V2D_RUNNER_SHA256 = prior.EXPECTED_V2D_RUNNER_SHA256
EXPECTED_V2E_RUNNER_SHA256 = prior.EXPECTED_V2E_RUNNER_SHA256

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_PRIOR_DESIGN = (
    "524.42667149", "14.89205948", "539.31873097", "48.036338", 375.0,
    4095.8, 95, 89, 6, 23, "61.818617", "3194.42667149",
)
EXPECTED_PRIOR_VALIDATION = (
    "79.52385626", "17.20327680", "96.72713306", "16.478718", 210.0,
    1263.0, 51, 48, 3, 0, "25.544460", "1519.52385626",
)
EXPECTED_PRIOR_AUDIT = (
    0, 50, 48, 48, 0, 2, 1, 0, 0, 0,
    True, True, True, True, "79.52385626",
)
EXPECTED_PRIOR_WINS = (3, 1)


class HybridProfitLockEngine(prior.ProfitLockEngine):
    """Use the tighter of a half-peak lock and a maximum 1R giveback."""

    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.mode = "hybrid_profit_lock_v2"
        self.factor = CANDIDATE
        self.trigger_binding_counts = {
            "HALF_PEAK": 0,
            "PEAK_MINUS_1R": 0,
            "EXACT_TIE": 0,
        }

    @staticmethod
    def _floors(peak_pnl: D, entry_risk: D) -> tuple[D, D, D, str]:
        half_peak = base.money(peak_pnl * HALF)
        peak_minus_one_r = base.money(peak_pnl - entry_risk)
        effective = max(half_peak, peak_minus_one_r)
        if half_peak > peak_minus_one_r:
            branch = "HALF_PEAK"
        elif peak_minus_one_r > half_peak:
            branch = "PEAK_MINUS_1R"
        else:
            branch = "EXACT_TIE"
        return half_peak, peak_minus_one_r, effective, branch

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
                None if lot.entry_atr is None else lot.entry_atr * lot.quantity
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
            half_peak, peak_minus_one_r, effective_floor, binding = self._floors(
                peak_pnl, entry_risk
            )
            previous_floor = state["last_profit_floor"]
            if previous_floor is not None and effective_floor < previous_floor:
                state["floor_monotone"] = False
                self.profit_floor_decrease_violations += 1
            state["last_profit_floor"] = effective_floor
            state["half_peak_floor"] = half_peak
            state["peak_minus_one_r_floor"] = peak_minus_one_r
            state["binding_branch"] = binding

            if current_pnl <= ZERO or current_pnl > effective_floor:
                continue
            condition_pass = (
                entry_risk is not None
                and peak_pnl >= entry_risk
                and current_pnl > ZERO
                and current_pnl <= effective_floor
                and effective_floor == max(half_peak, peak_minus_one_r)
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
                "half_peak_floor_usdt": str(half_peak),
                "peak_minus_1r_floor_usdt": str(peak_minus_one_r),
                "effective_profit_floor_usdt": str(effective_floor),
                "binding_branch": binding,
                "giveback_usdt": str(giveback),
                "giveback_fraction_of_peak": str(giveback_fraction),
                "armed": state["armed"],
                "floor_monotone": state["floor_monotone"],
                "condition_pass": condition_pass,
            }
            lot.exit_queued_at = bar.open_time
            self._count_trigger("HYBRID_HALF_PEAK_OR_MAX_1R_GIVEBACK_LOCK")
            self.trigger_binding_counts[binding] += 1
            self.trigger_records.append(trigger)
            self.queue_snapshot_by_fill[lot.fill_time] = trigger

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        audit = result.pop("profit_lock_audit")
        open_lot_by_fill = {
            lot.fill_time.isoformat(): lot for lot in self.lots
        }
        for row in audit["ending_open_lot_state"]:
            old_floor = row.pop("profit_floor_50pct_usdt")
            if row["armed"]:
                lot = open_lot_by_fill[row["fill_time"]]
                peak = self.lot_state[lot.fill_time]["peak_pnl"]
                entry_risk = lot.entry_atr * lot.quantity
                half_peak, peak_minus_one_r, effective, binding = self._floors(
                    peak, entry_risk
                )
                row["half_peak_floor_usdt"] = str(half_peak)
                row["peak_minus_1r_floor_usdt"] = str(peak_minus_one_r)
                row["effective_profit_floor_usdt"] = str(effective)
                row["binding_branch"] = binding
                row["legacy_floor_field_matched_effective"] = old_floor == str(effective)
            else:
                row["half_peak_floor_usdt"] = None
                row["peak_minus_1r_floor_usdt"] = None
                row["effective_profit_floor_usdt"] = None
                row["binding_branch"] = None
                row["legacy_floor_field_matched_effective"] = old_floor is None
        audit["trigger_binding_counts"] = self.trigger_binding_counts
        audit["hybrid_formula"] = "MAX_HALF_PEAK_OR_PEAK_MINUS_ENTRY_RISK_1R"
        audit["all_open_floor_fields_match_effective_pass"] = all(
            row["legacy_floor_field_matched_effective"]
            for row in audit["ending_open_lot_state"]
        )
        result["hybrid_profit_lock_audit"] = audit
        result["candidate"] = CANDIDATE
        result["mode"] = "hybrid_profit_lock_v2"
        result["factor"] = CANDIDATE
        return result


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "prior_specification_sha256": file_sha256(PRIOR_SPEC_PATH),
        "prior_profit_lock_dependency_sha256": file_sha256(Path(prior.__file__)),
        "previous_structural_specification_sha256": file_sha256(
            PREVIOUS_STRUCTURAL_SPEC_PATH
        ),
        "previous_structural_dependency_sha256": file_sha256(Path(previous.__file__)),
        "v2c_dependency_sha256": file_sha256(Path(base.__file__)),
        "v2d_dependency_sha256": file_sha256(Path(previous.v2d.__file__)),
        "v2e_dependency_sha256": file_sha256(Path(previous.v2e.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "prior_specification_sha256": EXPECTED_PRIOR_SPEC_SHA256,
        "prior_profit_lock_dependency_sha256": EXPECTED_PRIOR_RUNNER_SHA256,
        "previous_structural_specification_sha256": (
            EXPECTED_PREVIOUS_STRUCTURAL_SPEC_SHA256
        ),
        "previous_structural_dependency_sha256": (
            EXPECTED_PREVIOUS_STRUCTURAL_RUNNER_SHA256
        ),
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
    engine = HybridProfitLockEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def prior_audit_tuple(result: dict) -> tuple:
    audit = result["profit_lock_audit"]
    return (
        audit["entry_atr_missing_buy_count"],
        audit["lots_armed"],
        audit["exit_queues"],
        audit["successful_exit_fills"],
        audit["deferred_exit_fills"],
        audit["ending_open_armed_lots"],
        audit["ending_open_unarmed_lots"],
        audit["trigger_condition_violations"],
        audit["profit_floor_decrease_violations"],
        audit["nonpositive_or_accounting_exit_violations"],
        audit["entry_atr_complete_pass"],
        audit["all_trigger_conditions_pass"],
        audit["profit_floor_monotonicity_pass"],
        audit["all_realized_exits_strictly_net_positive_pass"],
        audit["realized_pnl_from_exit_records_usdt"],
    )


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = prior.reproduce_checkpoints(bars)
    design = prior.simulate_candidate(bars, DESIGN)
    validation = prior.simulate_candidate(bars, VALIDATION)
    folds = {
        name: prior.simulate_candidate(bars, window)
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
        "prior_profit_lock_design": (
            base.checkpoint_tuple(design), EXPECTED_PRIOR_DESIGN
        ),
        "prior_profit_lock_validation": (
            base.checkpoint_tuple(validation), EXPECTED_PRIOR_VALIDATION
        ),
        "prior_profit_lock_validation_audit": (
            prior_audit_tuple(validation), EXPECTED_PRIOR_AUDIT
        ),
        "prior_profit_lock_annual_wins": (
            (total_wins, hold_wins), EXPECTED_PRIOR_WINS
        ),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["prior_profit_lock"] = {
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
    audit = result["hybrid_profit_lock_audit"]
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
        "all_open_floor_fields_match_effective": (
            audit["all_open_floor_fields_match_effective_pass"]
        ),
    }


def metric_delta(left: dict, right: dict, field: str) -> str:
    return str(base.money(base.dec(left, field) - base.dec(right, field)))


def comparative_diagnosis(candidate: dict, baselines: dict, gates: dict) -> dict:
    v1 = baselines["v1"]["validation"]
    v2a = baselines["v2a"]["validation"]
    prior_result = baselines["prior_profit_lock"]["validation"]
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
        "validation_delta_vs_prior_profit_lock_usdt": {
            "realized": metric_delta(candidate, prior_result, "realized_usdt"),
            "unrealized": metric_delta(candidate, prior_result, "unrealized_usdt"),
            "total": metric_delta(candidate, prior_result, "total_pnl_usdt"),
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
        "baseline_parity": (
            "PASS_V1_V2A_V2B_V2C_V2D_V2E_PREVIOUS_STRUCTURAL_PRIOR_PROFIT_LOCK"
        ),
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
    audit = candidate["hybrid_profit_lock_audit"]
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
        "all_open_floor_fields_match_effective": (
            audit["all_open_floor_fields_match_effective_pass"]
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
