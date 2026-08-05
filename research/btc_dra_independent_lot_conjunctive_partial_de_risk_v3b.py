#!/usr/bin/env python3
"""Causal DRA conjunctive 24/6 partial de-risk research with sealed OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_independent_lot_conditional_partial_de_risk_v3 as v3

base = v3.base
D = Decimal
ZERO = D("0")
ONE = D("1")
FOUR = D("4")
FIVE = D("5")
ORIGINAL_COST = D("30.00")
PARTIAL_COST = D("24.00")
REMAINDER_COST = D("6.00")

RESEARCH_IDENTITY = "BTC_DRA_INDEPENDENT_LOT_CONJUNCTIVE_PARTIAL_DE_RISK_V3B_RESEARCH"
CANDIDATE = "ENTRY_ACCELERATION_AND_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_24_6"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-conjunctive-partial-de-risk-v3b-research.md"
V3_SPEC_PATH = ROOT / "docs" / "btc-dra-independent-lot-conditional-partial-de-risk-v3-research.md"
EXPECTED_SPEC_SHA256 = "882db75ad87bad2ce8111c3ba43c7ad27f3a5244f82853d4e623967c57ea007b"
EXPECTED_V3_SPEC_SHA256 = "b5276fc2666a56fb0c26cc79880fa8681684857751c7c3da7977b4f2a32a8c5f"
EXPECTED_V3_RUNNER_SHA256 = "2945aa711b2067cc6acff50cc04aa596fb784011b54abdd235c24e0044c19397"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS

EXPECTED_V3_VALIDATION = {
    v3.CANDIDATES[0]: (
        "117.52474516", "-3.20820121", "114.31654395", "7.506541", 338.0,
        1608.0, 51, 72, 1, 0, "24.075923", "1617.52474516",
    ),
    v3.CANDIDATES[1]: (
        "109.37022098", "-3.20820121", "106.16201977", "7.734178", 338.0,
        1471.2, 51, 74, 1, 0, "23.675559", "1609.37022098",
    ),
    v3.CANDIDATES[2]: (
        "116.97808010", "-3.20820121", "113.76987889", "8.392661", 363.0,
        1608.0, 51, 64, 1, 0, "25.556544", "1616.97808010",
    ),
}
EXPECTED_V3_AUDIT = {
    v3.CANDIDATES[0]: (28, 23, 22, 28, 22),
    v3.CANDIDATES[1]: (27, 24, 24, 26, 24),
    v3.CANDIDATES[2]: (37, 14, 14, 36, 14),
}
EXPECTED_V3_WINS = {candidate: (4, 0) for candidate in v3.CANDIDATES}


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


class ConjunctivePartialEngine(v3.ConditionalPartialEngine):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(v3.CANDIDATES[0], cap=cap)
        self.candidate = CANDIDATE
        self.factor = CANDIDATE
        self.mode = "conjunctive_partial_v3b"

    def _entry_route(self) -> tuple[str, dict]:
        if len(self.daily_records) < 15:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_INSUFFICIENT_14_COMPLETE_DAYS",
                "inception_fallback": True,
            }
        current = self.daily_records[-1]
        close_t7 = self.daily_records[-8].close
        close_t14 = self.daily_records[-15].close
        recent = current.close / close_t7 - ONE
        previous = close_t7 / close_t14 - ONE
        acceleration = recent > ZERO and recent > previous
        prior_atr = self.daily_records[-2].atr14
        if prior_atr is None:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_PRIOR_ATR14_UNAVAILABLE",
                "inception_fallback": True,
            }
        range_expansion = current.true_range > prior_atr
        strong = acceleration and range_expansion
        return ("FULL_V2A" if strong else "PARTIAL_ELIGIBLE"), {
            "signal_day": current.day.isoformat(),
            "recent_7d_return": str(recent),
            "prior_7d_return": str(previous),
            "momentum_acceleration_pass": acceleration,
            "signal_day_true_range": str(current.true_range),
            "prior_complete_day_atr14": str(prior_atr),
            "range_expansion_pass": range_expansion,
            "conjunction_pass": strong,
        }

    def _fill_exits(self, bar: base.Bar) -> None:
        survivors: list[base.Lot] = []
        for lot in self.lots:
            if lot.exit_queued_at is None:
                survivors.append(lot)
                continue
            state = self.lot_state[lot.fill_time]
            kind = self.queue_kind_by_fill.get(lot.fill_time)
            if kind not in ("PARTIAL_24", "FULL_V2A"):
                raise base.ResearchReject("ACCOUNTING_REJECT", f"missing queue kind {kind}")
            if kind == "PARTIAL_24":
                if state["partial_done"]:
                    self.multiple_partial_violations += 1
                sell_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
                allocated_cost = PARTIAL_COST
            else:
                sell_quantity = lot.quantity
                allocated_cost = lot.cost
            net = base.estimated_net(sell_quantity, bar.open)
            pnl = base.money(net - allocated_cost)
            record = {
                "fill_time": lot.fill_time.isoformat(),
                "exit_time": bar.open_time.isoformat(),
                "kind": kind,
                "route": state["route"],
                "quantity": str(sell_quantity),
                "allocated_cost_usdt": str(base.money(allocated_cost)),
                "net_proceeds_usdt": str(net),
                "realized_net_pnl_usdt": str(pnl),
            }
            if pnl <= ZERO:
                lot.exit_queued_at = None
                self.queue_kind_by_fill.pop(lot.fill_time, None)
                self.deferred_count += 1
                record["decision"] = "DEFERRED_NOT_STRICTLY_NET_POSITIVE"
                self.deferred_fill_records.append(record)
                survivors.append(lot)
                continue
            age = (bar.open_time - lot.fill_time).total_seconds() / 3600
            self.realized += pnl
            self.total_sell_proceeds += net
            self.sell_count += 1
            self.hold_hours.append(age)
            self.exit_slice_holds.append(age)
            self.cost_weighted_holds.append((age, allocated_cost))
            state["sold_quantity"] += sell_quantity
            state["allocated_cost_exited"] += allocated_cost
            state["realized_pnl"] += pnl
            if state["first_realization_at"] is None:
                state["first_realization_at"] = bar.open_time
                self.first_realization_holds.append(age)
            record["decision"] = "STRICTLY_NET_POSITIVE_FILL"
            self.exit_fill_records.append(record)
            self.queue_kind_by_fill.pop(lot.fill_time, None)
            if kind == "PARTIAL_24":
                state["partial_done"] = True
                state["partial_fill_count"] += 1
                self.partial_fill_count += 1
                lot.quantity = state["original_quantity"] - sell_quantity
                lot.cost = REMAINDER_COST
                lot.highest_close = bar.open
                lot.ratchet_stop = None
                lot.exit_queued_at = None
                survivors.append(lot)
            else:
                if state["partial_done"]:
                    self.remainder_v2a_fill_count += 1
                else:
                    self.direct_full_v2a_fill_count += 1
                state["completed_at"] = bar.open_time
                self.original_lot_completion_count += 1
                self.final_completion_holds.append(age)
        self.lots = survivors

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            if state["route"] == "FULL_V2A" or state["partial_done"]:
                self._queue_full_v2a(lot, bar)
                continue
            current_full_pnl = base.money(
                base.estimated_net(lot.quantity, bar.close) - ORIGINAL_COST
            )
            previous_peak = state["peak_full_net_pnl"]
            peak = current_full_pnl if previous_peak is None else max(previous_peak, current_full_pnl)
            state["peak_full_net_pnl"] = peak
            if not state["armed"] and peak >= state["entry_risk_1r"]:
                state["armed"] = True
                state["armed_at"] = bar.open_time
            if not state["armed"] or self.hourly_ema5 is None or bar.close >= self.hourly_ema5:
                continue
            partial_quantity = base.quantity(state["original_quantity"] * FOUR / FIVE)
            partial_pnl = base.money(
                base.estimated_net(partial_quantity, bar.close) - PARTIAL_COST
            )
            condition_pass = (
                peak >= state["entry_risk_1r"]
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
            self._count_trigger("ARMED_1R_HOURLY_CLOSE_BELOW_EMA5_PARTIAL_24")
            self.partial_queue_records.append(
                {
                    "signal_time": lot.signal_time.isoformat(),
                    "fill_time": lot.fill_time.isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "entry_risk_1r_usdt": str(base.money(state["entry_risk_1r"])),
                    "peak_full_net_pnl_usdt": str(peak),
                    "hourly_close": str(bar.close),
                    "causal_hourly_ema5": str(self.hourly_ema5),
                    "estimated_partial_net_pnl_usdt": str(partial_pnl),
                    "condition_pass": condition_pass,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "conjunctive_partial_v3b"
        result["candidate"] = CANDIDATE
        result["factor"] = CANDIDATE
        result["conditional_partial_audit"]["partial_cost_usdt"] = "24.00"
        result["conditional_partial_audit"]["remainder_cost_usdt"] = "6.00"
        result["conditional_partial_audit"]["route_formula"] = "ACCELERATION_AND_RANGE_EXPANSION"
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v3_specification_sha256": file_sha256(V3_SPEC_PATH),
        "v3_dependency_sha256": file_sha256(Path(v3.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v3_specification_sha256": EXPECTED_V3_SPEC_SHA256,
        "v3_dependency_sha256": EXPECTED_V3_RUNNER_SHA256,
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
    engine = ConjunctivePartialEngine(cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def reproduce_checkpoints(bars: list[base.Bar]) -> dict:
    baselines = v3.reproduce_checkpoints(bars)
    v3_results: dict[str, dict] = {}
    mismatches: list[dict] = []
    for candidate in v3.CANDIDATES:
        validation = v3.simulate_candidate(bars, VALIDATION, candidate)
        folds = {name: v3.simulate_candidate(bars, window, candidate) for name, window in FOLDS.items()}
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
            f"v3_{candidate}_validation": (base.checkpoint_tuple(validation), EXPECTED_V3_VALIDATION[candidate]),
            f"v3_{candidate}_audit": (actual_audit, EXPECTED_V3_AUDIT[candidate]),
            f"v3_{candidate}_wins": ((total_wins, hold_wins), EXPECTED_V3_WINS[candidate]),
        }
        mismatches.extend(
            {"checkpoint": name, "actual": actual, "expected": expected}
            for name, (actual, expected) in checks.items()
            if actual != expected
        )
        v3_results[candidate] = {
            "validation": validation,
            "folds": folds,
            "annual_total_wins": total_wins,
            "annual_median_hold_wins": hold_wins,
        }
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["conditional_partial_v3"] = v3_results
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
        "baseline_parity": "PASS_THROUGH_CONDITIONAL_PARTIAL_V3",
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
