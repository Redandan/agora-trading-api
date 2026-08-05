#!/usr/bin/env python3
"""Causal DRA net-positive EMA-deterioration partial research with sealed OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_independent_lot_conjunctive_partial_de_risk_v3b as v3b

base = v3b.base
D = Decimal
ZERO = D("0")
FOUR = D("4")
FIVE = D("5")
PARTIAL_COST = D("24.00")

RESEARCH_IDENTITY = "BTC_DRA_NET_POSITIVE_EMA_DETERIORATION_PARTIAL_V3C_RESEARCH"
CANDIDATE = "ENTRY_ACCELERATION_AND_RANGE_EXPANSION_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-net-positive-ema-deterioration-partial-v3c-research.md"
V3B_SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-conjunctive-partial-de-risk-v3b-research.md"
EXPECTED_SPEC_SHA256 = "2fad0cfcf7851064e084a2bc969497c5cded562f50c9c09a875b1e40e972cfe9"
EXPECTED_V3B_SPEC_SHA256 = "882db75ad87bad2ce8111c3ba43c7ad27f3a5244f82853d4e623967c57ea007b"
EXPECTED_V3B_RUNNER_SHA256 = "9df19359fed929e0c305c46eea94c163753d2d72956719907831c1a96a324d57"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V3B_VALIDATION = (
    "108.20391069", "-3.20820121", "104.99570948", "6.388173", 317.0,
    1329.1, 51, 82, 1, 0, "20.723803", "1608.20391069",
)
EXPECTED_V3B_AUDIT = (18, 33, 32, 18, 32)
EXPECTED_V3B_WINS = (4, 0)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


class NetPositiveEmaPartialEngine(v3b.ConjunctivePartialEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.candidate = CANDIDATE
        self.factor = CANDIDATE
        self.mode = "net_positive_ema_partial_v3c"

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            if state["route"] == "FULL_V2A" or state["partial_done"]:
                self._queue_full_v2a(lot, bar)
                continue
            if self.hourly_ema5 is None or bar.close >= self.hourly_ema5:
                continue
            partial_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
            partial_pnl = base.money(
                base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
            )
            condition_pass = (
                bar.close < self.hourly_ema5
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
            self._count_trigger("NET_POSITIVE_HOURLY_CLOSE_BELOW_EMA5_PARTIAL_24")
            self.partial_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "estimated_partial_net_pnl_usdt": str(partial_pnl),
                    "one_r_arm_required": False,
                    "condition_pass": condition_pass,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "net_positive_ema_partial_v3c"
        result["candidate"] = CANDIDATE
        result["factor"] = CANDIDATE
        audit = result["conditional_partial_audit"]
        audit["partial_trigger_formula"] = "NET_POSITIVE_AND_HOURLY_CLOSE_BELOW_CAUSAL_EMA5"
        audit["one_r_arm_required"] = False
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v3b_specification_sha256": file_sha256(V3B_SPEC_PATH),
        "v3b_dependency_sha256": file_sha256(Path(v3b.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v3b_specification_sha256": EXPECTED_V3B_SPEC_SHA256,
        "v3b_dependency_sha256": EXPECTED_V3B_RUNNER_SHA256,
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
    engine = NetPositiveEmaPartialEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = v3b.reproduce_checkpoints(bars)
    validation = v3b.simulate_candidate(bars, VALIDATION)
    folds = {name: v3b.simulate_candidate(bars, window) for name, window in FOLDS.items()}
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
    actual_audit = (
        audit["entry_route_counts"]["FULL_V2A"],
        audit["entry_route_counts"]["PARTIAL_ELIGIBLE"],
        audit["partial_fill_count"],
        audit["direct_full_v2a_fill_count"],
        audit["remainder_v2a_fill_count"],
    )
    checks = {
        "v3b_validation": (base.checkpoint_tuple(validation), EXPECTED_V3B_VALIDATION),
        "v3b_route_audit": (actual_audit, EXPECTED_V3B_AUDIT),
        "v3b_annual_wins": ((total_wins, hold_wins), EXPECTED_V3B_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["conjunctive_partial_v3b"] = {
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
    audit = result["conditional_partial_audit"]
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
        "all_entry_routes_complete": audit["all_entry_routes_complete_pass"],
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def freeze_hash(data_sha: str, hashes: dict[str, str], runner_sha: str) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": CANDIDATE,
        "selection_data_sha256": data_sha,
        **hashes,
        "runner_sha256": runner_sha,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def run_preselect(output: Path) -> dict:
    artifact_hashes = verify_preregistration_artifacts()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {"expected_rows": SELECTION_ROWS, "actual_rows": len(bars), "expected_sha256": SELECTION_SHA256, "actual_sha256": digest},
        )
    baselines = reproduce_checkpoints(bars)
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
        "baseline_parity": "PASS_THROUGH_CONJUNCTIVE_PARTIAL_V3B",
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
            "folds": {name: simulate_candidate(bars, fold, cap=base.LOT_COST) for name, fold in FOLDS.items()},
        }
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["conditional_partial_audit"]
    return {
        "oos_total_at_least_v1": base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "oos_total_retains_90pct_v2a": base.dec(candidate, "total_pnl_usdt") >= base.dec(v2a, "total_pnl_usdt") * D("0.90"),
        "oos_realized_at_least_v1": base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "oos_unrealized_no_worse": base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "oos_drawdown_within_v1_plus_2pp": base.dec(candidate, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct") + D("2.0"),
        "oos_cost_weighted_median_no_worse": candidate["median_hold_hours"] is not None and candidate["median_hold_hours"] <= v1["median_hold_hours"],
        "oos_cost_weighted_p90_no_worse": candidate["p90_hold_hours"] is not None and candidate["p90_hold_hours"] <= v1["p90_hold_hours"],
        "accounting_and_trigger_audit": all((
            audit["all_entry_routes_complete_pass"], audit["cost_allocation_reconciles_pass"],
            audit["quantity_conservation_pass"], audit["all_partial_conditions_pass"],
            audit["all_exit_fills_strictly_net_positive_pass"], audit["at_most_one_partial_fill_per_lot_pass"],
        )),
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
        "oos": {"v1_reference_250": v1, "v2a_reference_250": v2a, "candidate_reference_250": candidate, "gates": gates},
        "one_slot_overlay_30": {
            "design": simulate_candidate(bars, DESIGN, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, cap=base.LOT_COST),
            "folds": {name: simulate_candidate(bars, fold, cap=base.LOT_COST) for name, fold in FOLDS.items()},
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
        result = {"status": reject.status, "research_identity": RESEARCH_IDENTITY, "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE", "detail": reject.detail}
        if not (args.stage == "oos" and output.exists()):
            base.write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
