#!/usr/bin/env python3
"""Requalify exact DRA V7 for liquidity harvesting with one sealed OOS open."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

import btc_dra_v7_trend_quality_promotion_liquidity_harvest_v8 as v8

v7 = v8.v7
base = v8.base
D = Decimal

RESEARCH_IDENTITY = "BTC_DRA_V7_LIQUIDITY_HARVEST_REQUALIFICATION_R1_RESEARCH"
CANDIDATE = v7.CANDIDATE

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-v7-liquidity-harvest-requalification-r1-research.md"
V7_SPEC_PATH = ROOT / "docs" / "btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md"
V8_SPEC_PATH = ROOT / "docs" / "btc-dra-v7-trend-quality-promotion-liquidity-harvest-v8-research.md"
EXPECTED_SPEC_SHA256 = "6c306005dc9a062fb98fae08050d0bb9852272f1c0033c3781227403a0dac533"
EXPECTED_V7_SPEC_SHA256 = "b4034444510411a5e45681f5a9b12744e072bfee0b14e94842a09e5d9ee7be79"
EXPECTED_V7_RUNNER_SHA256 = "9441ff63db551d5105082387822f7a4ccdcd01e247ad86c6db5382d6df21d532"
EXPECTED_V8_SPEC_SHA256 = "3f00e56ff19cf4809247cc9fab5bac12f6cacedb9ad8f2da8042875c36f78592"
EXPECTED_V8_RUNNER_SHA256 = "f1972d52b8cacf9d6d3801d9c31aa1ffceda5df498634104d33ee52c22fa7db2"

SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256
OOS_CUTOFF = datetime(2026, 8, 2)
DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def json_hash(value: object) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v7_specification_sha256": file_sha256(V7_SPEC_PATH),
        "v7_dependency_sha256": file_sha256(Path(v7.__file__)),
        "v8_harvest_gate_specification_sha256": file_sha256(V8_SPEC_PATH),
        "v8_harvest_metric_dependency_sha256": file_sha256(Path(v8.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v7_specification_sha256": EXPECTED_V7_SPEC_SHA256,
        "v7_dependency_sha256": EXPECTED_V7_RUNNER_SHA256,
        "v8_harvest_gate_specification_sha256": EXPECTED_V8_SPEC_SHA256,
        "v8_harvest_metric_dependency_sha256": EXPECTED_V8_RUNNER_SHA256,
    }
    problems = [
        {"artifact": key, "expected": expected[key], "actual": actual[key]}
        for key in expected
        if actual[key] != expected[key]
    ]
    if problems:
        raise base.ResearchReject("PREREGISTRATION_REJECT", problems)
    return actual


def simulate_v7(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    cap: D = base.REFERENCE_CAP,
) -> dict:
    return v8.add_harvest_metrics(v7.simulate_candidate(bars, window, cap=cap), window)


def simulate_v1(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    cap: D = base.REFERENCE_CAP,
) -> dict:
    return v8.add_harvest_metrics(base.simulate(bars, window, "v1", cap=cap), window)


def v7_audit_gates(result: dict) -> dict[str, bool]:
    audit = result["conditional_partial_audit"]
    promotion = audit["pre_partial_one_r_promotion_audit"]
    return {
        "unique_promotion_per_lot": promotion["unique_promotion_per_lot_pass"],
        "all_promotions_first_crossing": promotion["all_promotions_first_crossing_pass"],
        "all_promotions_threshold": promotion["all_promotions_threshold_pass"],
        "all_promotions_pre_partial": promotion["all_promotions_pre_partial_pass"],
        "no_promoted_lot_partial_fill": promotion["no_promoted_lot_partial_fill_pass"],
        "all_nonpromoted_partial_queues_below_1r": promotion["all_nonpromoted_partial_queues_below_1r_pass"],
        "same_hour_promotion_precedence": promotion["same_hour_promotion_precedence_pass"],
        "no_nonpromoted_one_r_wait": promotion["no_nonpromoted_one_r_wait_pass"],
        "no_quota_or_tiebreak_rejection": promotion["rejected_by_runner_quota_or_tiebreak"] == 0,
        "no_entry_block_or_resize_by_promotion": promotion["no_entry_block_or_resize_by_promotion_pass"],
        "all_entry_routes_complete": audit["all_entry_routes_complete_pass"],
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def performance_gates(candidate: dict, v1: dict) -> dict[str, bool]:
    return {
        "total_at_least_v1": base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "realized_at_least_v1": base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "unrealized_no_worse_than_v1": base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "drawdown_no_higher_than_v1": base.dec(candidate, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct"),
        "cost_weighted_median_at_most_200h": candidate["median_hold_hours"] is not None and D(str(candidate["median_hold_hours"])) <= D("200"),
        "cost_weighted_p90_at_most_1000h": candidate["p90_hold_hours"] is not None and D(str(candidate["p90_hold_hours"])) <= D("1000"),
        "turnover_at_least_v1": base.dec(candidate, "turnover_usdt") >= base.dec(v1, "turnover_usdt"),
        "harvest_efficiency_greater_than_v1": (
            D(candidate["harvest_efficiency_usdt_per_1000_capital_hours"])
            > D(v1["harvest_efficiency_usdt_per_1000_capital_hours"])
        ),
    }


def freeze_hash(data_sha: str, hashes: dict[str, str], runner_sha: str) -> str:
    return json_hash(
        {
            "research_identity": RESEARCH_IDENTITY,
            "candidate": CANDIDATE,
            "selection_data_sha256": data_sha,
            "oos_cutoff": OOS_CUTOFF.isoformat(),
            **hashes,
            "runner_sha256": runner_sha,
        }
    )


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
    baselines = v8.reproduce_v7_checkpoint(bars)
    design = simulate_v7(bars, DESIGN)
    validation = baselines["v7_pre_partial_one_r_promotion"]["validation"]
    folds = baselines["v7_pre_partial_one_r_promotion"]["folds"]
    v1_validation = baselines["v1"]["validation"]
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
    efficiency_wins = sum(
        D(folds[name]["harvest_efficiency_usdt_per_1000_capital_hours"])
        > D(baselines["v1"]["folds"][name]["harvest_efficiency_usdt_per_1000_capital_hours"])
        for name in FOLDS
    )
    gates = {
        **{f"validation_{key}": value for key, value in performance_gates(validation, v1_validation).items()},
        "annual_harvest_efficiency_wins_at_least_3_of_5": efficiency_wins >= 3,
        "annual_total_wins_at_least_2_of_5": total_wins >= 2,
        "validation_and_all_fold_audits_pass": all(
            all(v7_audit_gates(row).values())
            for row in (validation, *(folds[name] for name in FOLDS))
        ),
    }
    passed = all(gates.values())
    runner_sha = source_hash()
    candidate_result = {
        "candidate": CANDIDATE,
        "design": design,
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
        "annual_harvest_efficiency_wins": efficiency_wins,
        "validation_audit_gates": v7_audit_gates(validation),
        "gates": gates,
        "pass": passed,
    }
    result = {
        "status": "CANDIDATE_FROZEN" if passed else "NO_CANDIDATE_KEEP_DRA_V1",
        "selection_decision": "CANDIDATE_FROZEN" if passed else "NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_reuse_disclosure": "POST_HOC_REQUALIFICATION_NOT_INDEPENDENT_EVIDENCE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        "frozen_oos_cutoff": OOS_CUTOFF.isoformat(),
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_THROUGH_V7_PRE_PARTIAL_ONE_R_PROMOTION",
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "qualified_count": 1 if passed else 0,
    }
    if passed:
        result["frozen_candidate_key"] = CANDIDATE
        result["freeze_sha256"] = freeze_hash(digest, artifact_hashes, runner_sha)
    base.write_json(output, result)
    return result


def validate_manifest(preselection: dict) -> tuple[dict[str, str], str]:
    if preselection.get("status") != "CANDIDATE_FROZEN":
        raise base.ResearchReject("OOS_SEAL_REJECT", "preselection froze no candidate")
    artifact_hashes = verify_preregistration_artifacts()
    runner_sha = source_hash()
    expected_fields = {
        "selection_data_sha256": SELECTION_SHA256,
        "frozen_candidate_key": CANDIDATE,
        "frozen_oos_cutoff": OOS_CUTOFF.isoformat(),
        **artifact_hashes,
        "runner_sha256": runner_sha,
    }
    for field, expected in expected_fields.items():
        if preselection.get(field) != expected:
            raise base.ResearchReject("OOS_SEAL_REJECT", f"{field} mismatch")
    expected_freeze = freeze_hash(SELECTION_SHA256, artifact_hashes, runner_sha)
    if preselection.get("freeze_sha256") != expected_freeze:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    return artifact_hashes, expected_freeze


def one_slot_pair(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
) -> dict:
    return {
        "v1": simulate_v1(bars, window, cap=base.LOT_COST),
        "v7": simulate_v7(bars, window, cap=base.LOT_COST),
    }


def run_oos(preselect_path: Path, cutoff: datetime, output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OOS_SEAL_REJECT", f"output already exists: {output}")
    if cutoff != OOS_CUTOFF:
        raise base.ResearchReject(
            "OOS_SEAL_REJECT",
            f"cutoff must equal frozen {OOS_CUTOFF.isoformat()}",
        )
    preselection = json.loads(preselect_path.read_text(encoding="utf-8"))
    artifact_hashes, expected_freeze = validate_manifest(preselection)
    bars = base.parse_rows(base.fetch_rows(cutoff))
    if bars[-1].close_time != cutoff:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_last_complete_close": cutoff.isoformat(),
                "actual_last_complete_close": bars[-1].close_time.isoformat(),
            },
        )
    selection_prefix = [bar for bar in bars if bar.close_time <= SELECTION_CUTOFF]
    prefix_hash = base.data_hash(selection_prefix)
    if len(selection_prefix) != SELECTION_ROWS or prefix_hash != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "prefix_rows": len(selection_prefix),
                "prefix_sha256": prefix_hash,
                "expected_rows": SELECTION_ROWS,
                "expected_sha256": SELECTION_SHA256,
            },
        )
    window = (SELECTION_CUTOFF, cutoff)
    v1 = simulate_v1(bars, window)
    candidate = simulate_v7(bars, window)
    gates = {
        **{f"oos_{key}": value for key, value in performance_gates(candidate, v1).items()},
        "oos_v7_audits_pass": all(v7_audit_gates(candidate).values()),
    }
    result = {
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "frozen_candidate_key": CANDIDATE,
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_start": SELECTION_CUTOFF.isoformat(),
        "oos_cutoff": cutoff.isoformat(),
        "oos_last_complete_close": bars[-1].close_time.isoformat(),
        "full_data_rows": len(bars),
        "full_data_sha256": base.data_hash(bars),
        "selection_prefix_rows": len(selection_prefix),
        "selection_prefix_sha256": prefix_hash,
        **artifact_hashes,
        "runner_sha256": source_hash(),
        "oos": {
            "v1_reference_250": v1,
            "v7_reference_250": candidate,
            "gates": gates,
        },
        "one_slot_overlay_30": {
            "design": one_slot_pair(bars, DESIGN),
            "validation": one_slot_pair(bars, VALIDATION),
            "folds": {name: one_slot_pair(bars, fold) for name, fold in FOLDS.items()},
            "oos": one_slot_pair(bars, window),
        },
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidate_result", "oos", "one_slot_overlay_30"}
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
