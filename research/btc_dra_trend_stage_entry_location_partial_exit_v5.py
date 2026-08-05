#!/usr/bin/env python3
"""Causal DRA trend-stage entry-location partial-exit research with sealed OOS."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_two_factor_quality_tiered_partial_exit_v4 as v4

v3c = v4.v3c
base = v4.base
D = Decimal
ZERO = D("0")
ONE = D("1")

RESEARCH_IDENTITY = "BTC_DRA_TREND_STAGE_ENTRY_LOCATION_PARTIAL_EXIT_V5_RESEARCH"
CANDIDATES = (
    "EMA20_ABOVE_STREAK_LE_7_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6",
    "EMA20_ABOVE_STREAK_LE_20_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6",
    "EMA20_ABOVE_STREAK_LE_7_AND_EXTENSION_LE_1ATR_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6",
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-trend-stage-entry-location-partial-exit-v5-research.md"
V4_SPEC_PATH = ROOT / "docs" / "btc-dra-two-factor-quality-tiered-partial-exit-v4-research.md"
EXPECTED_SPEC_SHA256 = "103fd30dbc9022c77c2a9625ca0cb93dcce8f63d449a3560457bedc9b0c7b3fa"
EXPECTED_V4_SPEC_SHA256 = "0b16a6e98b9774b64106be77e20af15cc33a4cb33de3d2c0ef48d799f253e74e"
EXPECTED_V4_RUNNER_SHA256 = "9b7644aaf0702f982a0ef637c2a5e8604c8741353a14027c07dcabcb31bb3d3d"

DESIGN = base.DESIGN
VALIDATION = base.VALIDATION
FOLDS = base.FOLDS
DESIGN_CUTOFF = DESIGN[1]
DESIGN_ROWS = 35_064
SELECTION_CUTOFF = base.SELECTION_CUTOFF
SELECTION_ROWS = base.SELECTION_ROWS
SELECTION_SHA256 = base.SELECTION_SHA256

EXPECTED_V1_DESIGN = (
    "169.89846767", "-79.12049441", "90.77797326", "29.530448", 126.0,
    1818.6, 100, 95, 5, 3, "34.364819", "3019.89846767",
)
EXPECTED_V2A_DESIGN = (
    "277.82610201", "-101.42144167", "176.40466034", "22.420205", 371.0,
    1561.8, 99, 93, 6, 7, "42.945585", "3067.82610201",
)
EXPECTED_V4_VALIDATION = (
    "99.68390014", "-3.20820121", "96.47569893", "4.938290", 210.0,
    1315.0, 51, 82, 1, 0, "17.949932", "1599.68390014",
)
EXPECTED_V4_AUDIT = (18, 33, 32, 18, 32, 18, 19, 14)
EXPECTED_V4_WINS = (2, 0)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hash() -> str:
    return file_sha256(Path(__file__))


def json_hash(value: object) -> str:
    payload = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode()).hexdigest()


class TrendStagePartialEngine(v3c.NetPositiveEmaPartialEngine):
    def __init__(self, candidate: str, *, cap: D = base.REFERENCE_CAP) -> None:
        if candidate not in CANDIDATES:
            raise ValueError(candidate)
        super().__init__(cap=cap)
        self.candidate = candidate
        self.factor = candidate
        self.mode = "trend_stage_entry_location_partial_v5"
        self.trend_days: list[dict] = []

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        if bar.open_time.hour != 23 or self.ema20 is None or not self.daily_records:
            return
        current = self.daily_records[-1]
        previous = self.trend_days[-1] if self.trend_days else None
        above = current.close > self.ema20
        if not above:
            streak = 0
        elif previous is None or not previous["above_ema20"]:
            streak = 1
        else:
            streak = previous["above_streak_days"] + 1
        extension = None
        if current.atr14 is not None and current.atr14 > ZERO:
            extension = (current.close - self.ema20) / current.atr14
        self.trend_days.append(
            {
                "day": current.day,
                "close": current.close,
                "ema20": self.ema20,
                "atr14": current.atr14,
                "above_ema20": above,
                "above_streak_days": streak,
                "entry_extension_atr": extension,
            }
        )

    def _qualifies(self, streak: int, extension: D) -> bool:
        if self.candidate == CANDIDATES[0]:
            return 1 <= streak <= 7
        if self.candidate == CANDIDATES[1]:
            return 1 <= streak <= 20
        if self.candidate == CANDIDATES[2]:
            return 1 <= streak <= 7 and extension <= ONE
        raise ValueError(self.candidate)

    def _entry_route(self) -> tuple[str, dict]:
        if not self.trend_days:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_TREND_STAGE_UNAVAILABLE",
                "inception_fallback": True,
                "trend_qualified": False,
            }
        current = self.trend_days[-1]
        extension = current["entry_extension_atr"]
        if extension is None:
            self.entry_route_missing_count += 1
            return "FULL_V2A", {
                "reason": "HARD_DATASET_INCEPTION_DAILY_ATR14_UNAVAILABLE",
                "inception_fallback": True,
                "trend_qualified": False,
            }
        streak = current["above_streak_days"]
        qualified = self._qualifies(streak, extension)
        route = "FULL_V2A" if qualified else "PARTIAL_ELIGIBLE"
        return route, {
            "signal_day": current["day"].isoformat(),
            "daily_close": str(current["close"]),
            "causal_daily_ema20": str(current["ema20"]),
            "causal_daily_atr14": str(current["atr14"]),
            "above_ema20": current["above_ema20"],
            "above_streak_days": streak,
            "entry_extension_atr": str(extension),
            "trend_qualified": qualified,
            "inception_fallback": False,
        }

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["mode"] = "trend_stage_entry_location_partial_v5"
        result["candidate"] = self.candidate
        result["factor"] = self.candidate
        audit = result["conditional_partial_audit"]
        nonfallback = [row for row in self.entry_route_records if not row.get("inception_fallback", False)]
        formula_pass = True
        for row in nonfallback:
            extension = D(row["entry_extension_atr"])
            expected = self._qualifies(row["above_streak_days"], extension)
            formula_pass &= expected == row["trend_qualified"]
            formula_pass &= row["route"] == ("FULL_V2A" if expected else "PARTIAL_ELIGIBLE")
        audit["trend_feature_formula"] = (
            "COMPLETE_DAILY_CLOSE_ABOVE_CAUSAL_EMA20_STREAK_AND_OPTIONAL_ATR_NORMALIZED_EXTENSION"
        )
        audit["trend_stage_candidate"] = self.candidate
        audit["trend_feature_records_complete_pass"] = all(
            row.get("above_ema20") is True
            and row.get("above_streak_days", 0) >= 1
            and row.get("entry_extension_atr") is not None
            for row in nonfallback
        )
        audit["trend_route_formula_pass"] = formula_pass
        audit["inception_fallback_count"] = sum(
            row.get("inception_fallback", False) for row in self.entry_route_records
        )
        fallback_records = [
            row for row in self.entry_route_records if row.get("inception_fallback", False)
        ]
        audit["inception_fallback_valid_pass"] = all(
            row["route"] == "FULL_V2A"
            and row.get("trend_qualified") is False
            and row.get("reason", "").startswith("HARD_DATASET_INCEPTION_")
            for row in fallback_records
        )
        audit["trend_qualified_full_v2a_count"] = sum(
            row.get("trend_qualified", False) for row in self.entry_route_records
        )
        audit["nonqualified_partial_count"] = sum(
            not row.get("trend_qualified", False) and not row.get("inception_fallback", False)
            for row in self.entry_route_records
        )
        audit["no_entry_block_or_resize_by_factor_pass"] = True
        return result


def verify_preregistration_artifacts() -> dict[str, str]:
    actual = {
        "specification_sha256": file_sha256(SPEC_PATH),
        "v4_specification_sha256": file_sha256(V4_SPEC_PATH),
        "v4_dependency_sha256": file_sha256(Path(v4.__file__)),
    }
    expected = {
        "specification_sha256": EXPECTED_SPEC_SHA256,
        "v4_specification_sha256": EXPECTED_V4_SPEC_SHA256,
        "v4_dependency_sha256": EXPECTED_V4_RUNNER_SHA256,
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
    engine = TrendStagePartialEngine(candidate, cap=cap)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def design_eligibility(result: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = result["conditional_partial_audit"]
    return {
        "design_total_at_least_v1": base.dec(result, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "design_realized_at_least_v1": base.dec(result, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "design_unrealized_no_worse": base.dec(result, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "design_drawdown_within_v1_plus_2pp": base.dec(result, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct") + D("2"),
        "design_median_below_v2a": result["median_hold_hours"] < v2a["median_hold_hours"],
        "design_p90_no_worse_than_v1": result["p90_hold_hours"] <= v1["p90_hold_hours"],
        "trend_feature_records_complete": audit["trend_feature_records_complete_pass"],
        "trend_route_formula": audit["trend_route_formula_pass"],
        "all_nonfallback_routes_complete_and_inception_fallback_valid": (
            audit["trend_feature_records_complete_pass"]
            and audit["inception_fallback_valid_pass"]
        ),
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
    }


def make_design_decision(bars: list[base.Bar]) -> dict:
    v1 = base.simulate(bars, DESIGN, "v1")
    v2a = base.simulate(bars, DESIGN, "v2a")
    checkpoints = {
        "v1_design": (base.checkpoint_tuple(v1), EXPECTED_V1_DESIGN),
        "v2a_design": (base.checkpoint_tuple(v2a), EXPECTED_V2A_DESIGN),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checkpoints.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    candidates = []
    for candidate in CANDIDATES:
        result = simulate_candidate(bars, DESIGN, candidate)
        gates = design_eligibility(result, v1, v2a)
        candidates.append(
            {
                "candidate": candidate,
                "design": result,
                "design_eligibility": gates,
                "eligible": all(gates.values()),
            }
        )
    eligible = [row for row in candidates if row["eligible"]]
    eligible.sort(
        key=lambda row: (
            -base.dec(row["design"], "total_pnl_usdt"),
            row["design"]["median_hold_hours"],
            base.dec(row["design"], "max_drawdown_pct"),
            row["candidate"],
        )
    )
    selected = eligible[0] if eligible else None
    return {
        "baselines": {"v1_design": v1, "v2a_design": v2a},
        "candidates": candidates,
        "selected_candidate": None if selected is None else selected["candidate"],
        "eligible_count": len(eligible),
    }


def design_freeze_hash(
    data_sha: str,
    artifact_hashes: dict[str, str],
    runner_sha: str,
    decision: dict,
) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "stage": "DESIGN_ONLY",
        "design_data_sha256": data_sha,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "selected_candidate": decision["selected_candidate"],
        "eligible_count": decision["eligible_count"],
        "candidate_summaries": [
            {
                "candidate": row["candidate"],
                "checkpoint": base.checkpoint_tuple(row["design"]),
                "design_eligibility": row["design_eligibility"],
                "eligible": row["eligible"],
            }
            for row in decision["candidates"]
        ],
    }
    return json_hash(payload)


def run_design(output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", f"output already exists: {output}")
    artifact_hashes = verify_preregistration_artifacts()
    bars = base.parse_rows(base.fetch_rows(DESIGN_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != DESIGN_ROWS or bars[-1].close_time != DESIGN_CUTOFF:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": DESIGN_ROWS,
                "actual_rows": len(bars),
                "expected_last_close": DESIGN_CUTOFF.isoformat(),
                "actual_last_close": bars[-1].close_time.isoformat(),
            },
        )
    decision = make_design_decision(bars)
    runner_sha = source_hash()
    freeze = design_freeze_hash(digest, artifact_hashes, runner_sha, decision)
    selected = decision["selected_candidate"]
    result = {
        "status": "DESIGN_CANDIDATE_FROZEN" if selected is not None else "DESIGN_NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "stage": "DESIGN_ONLY_VALIDATION_UNREAD",
        "design_data_rows": len(bars),
        "design_data_first_open": bars[0].open_time.isoformat(),
        "design_data_last_close": bars[-1].close_time.isoformat(),
        "design_data_sha256": digest,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "design_freeze_sha256": freeze,
        "validation_opened": False,
        "oos_opened": False,
        **decision,
    }
    base.write_json(output, result)
    return result


def reproduce_v4_checkpoint(bars: list[base.Bar]) -> dict:
    baselines = v4.reproduce_checkpoints(bars)
    validation = v4.simulate_candidate(bars, VALIDATION)
    folds = {name: v4.simulate_candidate(bars, window) for name, window in FOLDS.items()}
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
        audit["quality_tier_counts"][v4.TIER_STRONG],
        audit["quality_tier_counts"][v4.TIER_MEDIUM],
        audit["quality_tier_counts"][v4.TIER_WEAK],
    )
    checks = {
        "v4_validation": (base.checkpoint_tuple(validation), EXPECTED_V4_VALIDATION),
        "v4_route_tier_audit": (actual_audit, EXPECTED_V4_AUDIT),
        "v4_annual_wins": ((total_wins, hold_wins), EXPECTED_V4_WINS),
    }
    mismatches = [
        {"checkpoint": name, "actual": actual, "expected": expected}
        for name, (actual, expected) in checks.items()
        if actual != expected
    ]
    if mismatches:
        raise base.ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    baselines["quality_tiered_partial_v4"] = {
        "validation": validation,
        "folds": folds,
        "annual_total_wins": total_wins,
        "annual_median_hold_wins": hold_wins,
    }
    return baselines


def verify_design_freeze(design: dict, bars: list[base.Bar], artifact_hashes: dict[str, str], runner_sha: str) -> tuple[str, dict]:
    if design.get("status") != "DESIGN_CANDIDATE_FROZEN":
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "Design froze no candidate")
    if design.get("research_identity") != RESEARCH_IDENTITY:
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "research identity mismatch")
    for field, expected in {**artifact_hashes, "runner_sha256": runner_sha}.items():
        if design.get(field) != expected:
            raise base.ResearchReject("DESIGN_FREEZE_REJECT", f"{field} mismatch")
    design_bars = [bar for bar in bars if bar.close_time <= DESIGN_CUTOFF]
    digest = base.data_hash(design_bars)
    if len(design_bars) != DESIGN_ROWS or design.get("design_data_sha256") != digest:
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "Design data hash or row count mismatch")
    decision = make_design_decision(design_bars)
    expected_freeze = design_freeze_hash(digest, artifact_hashes, runner_sha, decision)
    if design.get("design_freeze_sha256") != expected_freeze:
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "Design decision freeze mismatch")
    candidate = decision["selected_candidate"]
    if design.get("selected_candidate") != candidate or candidate not in CANDIDATES:
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "selected candidate mismatch")
    return candidate, decision


def candidate_gates(result: dict, v1: dict, v2a: dict, total_wins: int, hold_wins: int) -> dict[str, bool]:
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
        "trend_feature_records_complete": audit["trend_feature_records_complete_pass"],
        "trend_route_formula": audit["trend_route_formula_pass"],
        "all_entry_routes_complete": audit["all_entry_routes_complete_pass"],
        "cost_allocation_reconciles": audit["cost_allocation_reconciles_pass"],
        "quantity_conservation": audit["quantity_conservation_pass"],
        "all_partial_conditions": audit["all_partial_conditions_pass"],
        "all_exit_fills_strictly_net_positive": audit["all_exit_fills_strictly_net_positive_pass"],
        "at_most_one_partial_fill_per_lot": audit["at_most_one_partial_fill_per_lot_pass"],
        "no_entry_block_or_resize_by_factor": audit["no_entry_block_or_resize_by_factor_pass"],
    }


def preselection_freeze_hash(data_sha: str, artifact_hashes: dict[str, str], runner_sha: str, design_freeze: str, candidate: str) -> str:
    return json_hash(
        {
            "research_identity": RESEARCH_IDENTITY,
            "selected_candidate": candidate,
            "selection_data_sha256": data_sha,
            "design_freeze_sha256": design_freeze,
            **artifact_hashes,
            "runner_sha256": runner_sha,
        }
    )


def run_preselect(design_path: Path, output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("PRESELECTION_REJECT", f"output already exists: {output}")
    design = json.loads(design_path.read_text(encoding="utf-8"))
    if design.get("status") != "DESIGN_CANDIDATE_FROZEN":
        raise base.ResearchReject("DESIGN_FREEZE_REJECT", "Design froze no candidate; Validation remains sealed")
    artifact_hashes = verify_preregistration_artifacts()
    runner_sha = source_hash()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    digest = base.data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {"expected_rows": SELECTION_ROWS, "actual_rows": len(bars), "expected_sha256": SELECTION_SHA256, "actual_sha256": digest},
        )
    candidate, design_decision = verify_design_freeze(design, bars, artifact_hashes, runner_sha)
    baselines = reproduce_v4_checkpoint(bars)
    design_result = simulate_candidate(bars, DESIGN, candidate)
    validation = simulate_candidate(bars, VALIDATION, candidate)
    folds = {name: simulate_candidate(bars, window, candidate) for name, window in FOLDS.items()}
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
    candidate_result = {
        "candidate": candidate,
        "design": design_result,
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
        "design_freeze_sha256": design["design_freeze_sha256"],
        "design_selected_candidate": candidate,
        "design_eligible_count": design_decision["eligible_count"],
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        **artifact_hashes,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS_THROUGH_QUALITY_TIERED_PARTIAL_V4",
        "validation_opened_after_design_freeze": True,
        "oos_opened": False,
        "baselines": baselines,
        "candidate_result": candidate_result,
        "qualified_count": 1 if passed else 0,
        "one_slot_overlay": None,
    }
    if passed:
        result["frozen_candidate_key"] = candidate
        result["freeze_sha256"] = preselection_freeze_hash(
            digest, artifact_hashes, runner_sha, design["design_freeze_sha256"], candidate
        )
    base.write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    audit = candidate["conditional_partial_audit"]
    return {
        "oos_total_at_least_v1": base.dec(candidate, "total_pnl_usdt") >= base.dec(v1, "total_pnl_usdt"),
        "oos_total_retains_90pct_v2a": base.dec(candidate, "total_pnl_usdt") >= base.dec(v2a, "total_pnl_usdt") * D("0.90"),
        "oos_realized_at_least_v1": base.dec(candidate, "realized_usdt") >= base.dec(v1, "realized_usdt"),
        "oos_unrealized_no_worse": base.dec(candidate, "unrealized_usdt") >= base.dec(v1, "unrealized_usdt"),
        "oos_drawdown_within_v1_plus_2pp": base.dec(candidate, "max_drawdown_pct") <= base.dec(v1, "max_drawdown_pct") + D("2"),
        "oos_cost_weighted_median_no_worse": candidate["median_hold_hours"] <= v1["median_hold_hours"],
        "oos_cost_weighted_p90_no_worse": candidate["p90_hold_hours"] <= v1["p90_hold_hours"],
        "trend_and_accounting_audit": all(
            (
                audit["trend_feature_records_complete_pass"],
                audit["trend_route_formula_pass"],
                audit["all_entry_routes_complete_pass"],
                audit["cost_allocation_reconciles_pass"],
                audit["quantity_conservation_pass"],
                audit["all_partial_conditions_pass"],
                audit["all_exit_fills_strictly_net_positive_pass"],
                audit["at_most_one_partial_fill_per_lot_pass"],
            )
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
    candidate = preselection.get("frozen_candidate_key")
    if candidate not in CANDIDATES or preselection.get("design_selected_candidate") != candidate:
        raise base.ResearchReject("OOS_SEAL_REJECT", "candidate key mismatch")
    expected_freeze = preselection_freeze_hash(
        SELECTION_SHA256,
        artifact_hashes,
        runner_sha,
        preselection["design_freeze_sha256"],
        candidate,
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
    candidate_result = simulate_candidate(bars, window, candidate)
    gates = oos_gates(candidate_result, v1, v2a)
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
            "candidate_reference_250": candidate_result,
            "gates": gates,
        },
        "one_slot_overlay_30": {
            "design": simulate_candidate(bars, DESIGN, candidate, cap=base.LOT_COST),
            "validation": simulate_candidate(bars, VALIDATION, candidate, cap=base.LOT_COST),
            "folds": {name: simulate_candidate(bars, fold, candidate, cap=base.LOT_COST) for name, fold in FOLDS.items()},
            "oos": simulate_candidate(bars, window, candidate, cap=base.LOT_COST),
        },
    }
    base.write_json(output, result)
    return result


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidates", "candidate_result", "one_slot_overlay", "oos", "one_slot_overlay_30"}
    return {key: value for key, value in result.items() if key not in omitted}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="stage", required=True)
    design = subparsers.add_parser("design")
    design.add_argument("--output", type=Path, required=True)
    preselect = subparsers.add_parser("preselect")
    preselect.add_argument("--design-freeze", type=Path, required=True)
    preselect.add_argument("--output", type=Path, required=True)
    oos = subparsers.add_parser("oos")
    oos.add_argument("--preselect", type=Path, required=True)
    oos.add_argument("--cutoff", required=True)
    oos.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output: Path = args.output
    try:
        if args.stage == "design":
            result = run_design(output)
        elif args.stage == "preselect":
            result = run_preselect(args.design_freeze, output)
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
        if not output.exists():
            base.write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("DESIGN_CANDIDATE_FROZEN", "CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
