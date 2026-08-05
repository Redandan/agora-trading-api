#!/usr/bin/env python3
"""Causal, read-only qualification timing diagnostic for V3 flat-range lots."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime
from decimal import Decimal
from pathlib import Path

import btc_dra_flat_range_lower_third_upper_third_reversal_harvest_v3 as v3

v1 = v3.v1
base = v3.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = "BTC_DRA_FLAT_RANGE_QUALIFICATION_TIMING_DIAGNOSTIC_V3D_RESEARCH"
MODES = (
    "FIRST_STRICT_NET_POSITIVE",
    "FROZEN_MIDPOINT_TOUCH",
    "FROZEN_UPPER_THIRD_TOUCH",
)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-flat-range-qualification-timing-diagnostic-v3d-research.md"
EXPECTED_SPEC_SHA256 = "e0553993302fd1b13b26ea26142b09d7d66742bb6f69c39f87c58f4f48a70b72"

SELECTION_CUTOFF = v3.SELECTION_CUTOFF
SELECTION_ROWS = v3.SELECTION_ROWS
SELECTION_SHA256 = v3.SELECTION_SHA256
DESIGN = v3.DESIGN
VALIDATION = v3.VALIDATION
FOLDS = v3.FOLDS
JULY_POST_HOC = v3.JULY_POST_HOC

EXPECTED_V1_VALIDATION = v3.EXPECTED_V1_VALIDATION
EXPECTED_V3_UPPER_VALIDATION = (
    "32.75201476",
    "-0.82980159",
    "31.92221317",
    "5.762120",
    512.0,
    2134.6,
    18,
    17,
    1,
    0,
    "9.837893",
    "542.75201476",
)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_specification() -> str:
    actual = file_sha256(SPEC_PATH)
    if actual != EXPECTED_SPEC_SHA256:
        raise base.ResearchReject(
            "PREREGISTRATION_REJECT",
            {"expected_specification_sha256": EXPECTED_SPEC_SHA256, "actual": actual},
        )
    return actual


class QualificationEngine(v3.RangeHarvestEngine):
    def __init__(
        self,
        qualification_mode: str,
        *,
        cap: D = base.REFERENCE_CAP,
        record_details: bool = True,
    ) -> None:
        if qualification_mode not in MODES:
            raise ValueError(qualification_mode)
        super().__init__(cap=cap, record_details=record_details)
        self.qualification_mode = qualification_mode
        self.mode = f"flat_range_qualification_diagnostic_{qualification_mode.lower()}"
        self.factor = qualification_mode
        self.qualification_records: list[dict] = []

    def _qualification_pass(
        self,
        state: dict,
        bar: base.Bar,
        current_pnl: D,
    ) -> tuple[bool, str | None]:
        if self.qualification_mode == "FIRST_STRICT_NET_POSITIVE":
            return current_pnl > ZERO, None
        if self.qualification_mode == "FROZEN_MIDPOINT_TOUCH":
            return bar.close >= state["frozen_midpoint"], str(state["frozen_midpoint"])
        return bar.close >= state["frozen_upper_third"], str(
            state["frozen_upper_third"]
        )

    def _queue_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            state = self.lot_state[lot.fill_time]
            current_pnl = base.money(base.estimated_net(lot.quantity, bar.close) - lot.cost)
            if state["upper_touch_time"] is None:
                if bar.open_time <= lot.fill_time:
                    continue
                qualified, threshold = self._qualification_pass(state, bar, current_pnl)
                if qualified:
                    state["upper_touch_time"] = bar.open_time
                    self.qualification_records.append(
                        {
                            "qualification_mode": self.qualification_mode,
                            "entry_fill_time": lot.fill_time.isoformat(),
                            "qualification_time": bar.open_time.isoformat(),
                            "hourly_close": str(bar.close),
                            "current_net_pnl_usdt": str(current_pnl),
                            "frozen_threshold": threshold,
                            "strictly_after_fill": bar.open_time > lot.fill_time,
                            "qualification_pass": True,
                        }
                    )
                continue
            passed = (
                bar.open_time > state["upper_touch_time"]
                and self.hourly_ema5 is not None
                and bar.close < self.hourly_ema5
                and current_pnl > ZERO
            )
            if not passed:
                continue
            lot.exit_queued_at = bar.open_time
            self._count_trigger(f"{self.qualification_mode}_THEN_EMA5_REVERSAL")
            self.queue_records.append(
                {
                    "qualification_mode": self.qualification_mode,
                    "route": state["route"],
                    "entry_fill_time": lot.fill_time.isoformat(),
                    "qualification_time": state["upper_touch_time"].isoformat(),
                    "queue_time": bar.open_time.isoformat(),
                    "hourly_close": str(bar.close),
                    "hourly_ema5": str(self.hourly_ema5),
                    "current_net_pnl_usdt": str(current_pnl),
                    "queue_strictly_after_qualification": bar.open_time
                    > state["upper_touch_time"],
                    "ema5_reversal_pass": bar.close < self.hourly_ema5,
                    "estimated_net_positive_pass": current_pnl > ZERO,
                }
            )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = base.Engine.result(self, final_bar, start, end)
        entry_to_qualification = [
            (
                datetime.fromisoformat(row["qualification_time"])
                - datetime.fromisoformat(row["entry_fill_time"])
            ).total_seconds()
            / 3600
            for row in self.qualification_records
        ]
        qualification_to_queue = [
            (
                datetime.fromisoformat(row["queue_time"])
                - datetime.fromisoformat(row["qualification_time"])
            ).total_seconds()
            / 3600
            for row in self.queue_records
        ]
        result["candidate"] = self.qualification_mode
        result["qualification_audit"] = {
            "qualification_mode": self.qualification_mode,
            "entry_formula": "FROZEN_V3_FLAT_LOWER_THIRD_RECLAIM",
            "exit_formula": "STRICTLY_LATER_CLOSE_LT_EMA5_AND_ESTIMATED_NET_PNL_GT_0",
            "fixed_numeric_profit_target_used": False,
            "entry_count": len(self.entry_records),
            "gap_cancel_count": len(self.gap_cancel_records),
            "qualification_count": len(self.qualification_records),
            "queue_count": len(self.queue_records),
            "positive_exit_count": len(self.exit_records),
            "deferred_exit_count": len(self.deferred_exit_records),
            "median_entry_to_qualification_hours": base.percentile(
                entry_to_qualification, 0.5
            ),
            "p90_entry_to_qualification_hours": base.percentile(
                entry_to_qualification, 0.9
            ),
            "median_qualification_to_queue_hours": base.percentile(
                qualification_to_queue, 0.5
            ),
            "p90_qualification_to_queue_hours": base.percentile(
                qualification_to_queue, 0.9
            ),
            "all_entries_causal_and_lower_located": all(
                row.get("flat") is True
                and row.get("current_day_excluded") is True
                and row.get("lower_third_reclaim_pass") is True
                and row.get("current_close_at_or_below_midpoint") is True
                and row.get("fill_at_or_below_signal_midpoint") is True
                for row in self.entry_records
            ),
            "all_gap_cancels_valid": all(
                D(row["effective_adverse_buy_price"]) > D(row["signal_midpoint"])
                for row in self.gap_cancel_records
            ),
            "all_qualifications_strictly_after_fill": all(
                row.get("strictly_after_fill") is True
                for row in self.qualification_records
            ),
            "all_qualifications_match_mode": all(
                self._qualification_record_valid(row)
                for row in self.qualification_records
            ),
            "all_queues_after_qualification": all(
                row.get("queue_strictly_after_qualification") is True
                for row in self.queue_records
            ),
            "all_queues_reversal_and_positive": all(
                row.get("ema5_reversal_pass") is True
                and row.get("estimated_net_positive_pass") is True
                for row in self.queue_records
            ),
            "all_exit_fills_positive": all(
                D(row["realized_net_pnl_usdt"]) > ZERO
                for row in self.exit_records
            ),
        }
        if self.record_details:
            result["entry_records"] = self.entry_records
            result["gap_cancel_records"] = self.gap_cancel_records
            result["qualification_records"] = self.qualification_records
            result["queue_records"] = self.queue_records
            result["exit_records"] = self.exit_records
            result["deferred_exit_records"] = self.deferred_exit_records
        return result

    def _qualification_record_valid(self, row: dict) -> bool:
        if row.get("qualification_pass") is not True:
            return False
        close = D(row["hourly_close"])
        pnl = D(row["current_net_pnl_usdt"])
        if self.qualification_mode == "FIRST_STRICT_NET_POSITIVE":
            return row["frozen_threshold"] is None and pnl > ZERO
        threshold = D(row["frozen_threshold"])
        return close >= threshold


def dec(result: dict, field: str) -> D:
    return D(result[field])


def mode_screen(
    mode_result: dict,
    design_result: dict,
    dra_v1: dict,
    v3_upper: dict,
    folds: dict[str, dict],
) -> dict:
    audit = mode_result["qualification_audit"]
    audit_keys = (
        "all_entries_causal_and_lower_located",
        "all_gap_cancels_valid",
        "all_qualifications_strictly_after_fill",
        "all_qualifications_match_mode",
        "all_queues_after_qualification",
        "all_queues_reversal_and_positive",
        "all_exit_fills_positive",
    )
    positive_folds = sum(
        dec(folds[name], "total_pnl_usdt") > ZERO for name in FOLDS
    )
    return {
        "design_total_positive": dec(design_result, "total_pnl_usdt") > ZERO,
        "validation_total_at_least_v3_upper": dec(mode_result, "total_pnl_usdt")
        >= dec(v3_upper, "total_pnl_usdt"),
        "validation_drawdown_no_higher_than_dra_v1": dec(
            mode_result, "max_drawdown_pct"
        )
        <= dec(dra_v1, "max_drawdown_pct"),
        "validation_median_no_higher_than_dra_v1": mode_result[
            "median_hold_hours"
        ]
        is not None
        and mode_result["median_hold_hours"] <= dra_v1["median_hold_hours"],
        "validation_p90_no_higher_than_dra_v1": mode_result["p90_hold_hours"]
        is not None
        and mode_result["p90_hold_hours"] <= dra_v1["p90_hold_hours"],
        "validation_completed_sells_at_least_10": mode_result["sell_count"] >= 10,
        "positive_total_folds_at_least_4_of_5": positive_folds >= 4,
        "all_causal_audits_pass": all(audit[key] for key in audit_keys),
        "positive_total_folds": positive_folds,
    }


def signal_overlap(mode_results: dict[str, dict]) -> dict:
    signals = {
        mode: {row["signal_time"] for row in result["entry_records"]}
        for mode, result in mode_results.items()
    }
    pairs = {}
    for left_index, left in enumerate(MODES):
        for right in MODES[left_index + 1 :]:
            pairs[f"{left}__VS__{right}"] = {
                "left_count": len(signals[left]),
                "right_count": len(signals[right]),
                "intersection_count": len(signals[left] & signals[right]),
                "left_only_count": len(signals[left] - signals[right]),
                "right_only_count": len(signals[right] - signals[left]),
            }
    return pairs


def run(output: Path, *, include_posthoc_july: bool) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    specification_sha = verify_specification()
    cutoff = JULY_POST_HOC[1] if include_posthoc_july else SELECTION_CUTOFF
    bars = base.parse_rows(base.fetch_rows(cutoff))
    selection_bars = [bar for bar in bars if bar.close_time <= SELECTION_CUTOFF]
    selection_sha = base.data_hash(selection_bars)
    if len(selection_bars) != SELECTION_ROWS or selection_sha != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": SELECTION_ROWS,
                "actual_rows": len(selection_bars),
                "expected_sha256": SELECTION_SHA256,
                "actual_sha256": selection_sha,
            },
        )

    windows = {"design": DESIGN, "validation": VALIDATION, **FOLDS}
    dra_v1 = {
        name: base.simulate(selection_bars, window, "v1")
        for name, window in windows.items()
    }
    mode_results = {
        mode: {
            name: v1.simulate_engine(
                selection_bars,
                window,
                lambda mode=mode: QualificationEngine(mode),
            )
            for name, window in windows.items()
        }
        for mode in MODES
    }
    overlays = {
        mode: {
            name: v1.simulate_engine(
                selection_bars,
                window,
                lambda mode=mode: QualificationEngine(mode, cap=base.LOT_COST),
            )
            for name, window in windows.items()
        }
        for mode in MODES
    }

    if base.checkpoint_tuple(dra_v1["validation"]) != EXPECTED_V1_VALIDATION:
        raise base.ResearchReject(
            "BASELINE_PARITY_REJECT",
            {
                "checkpoint": "DRA_V1_VALIDATION",
                "actual": base.checkpoint_tuple(dra_v1["validation"]),
                "expected": EXPECTED_V1_VALIDATION,
            },
        )
    upper_validation = mode_results["FROZEN_UPPER_THIRD_TOUCH"]["validation"]
    if base.checkpoint_tuple(upper_validation) != EXPECTED_V3_UPPER_VALIDATION:
        raise base.ResearchReject(
            "BASELINE_PARITY_REJECT",
            {
                "checkpoint": "V3_UPPER_VALIDATION",
                "actual": base.checkpoint_tuple(upper_validation),
                "expected": EXPECTED_V3_UPPER_VALIDATION,
            },
        )

    screens = {
        mode: mode_screen(
            mode_results[mode]["validation"],
            mode_results[mode]["design"],
            dra_v1["validation"],
            upper_validation,
            {name: mode_results[mode][name] for name in FOLDS},
        )
        for mode in MODES
    }
    eligible = [
        mode
        for mode in MODES
        if all(
            value
            for key, value in screens[mode].items()
            if key != "positive_total_folds"
        )
    ]
    ranked = sorted(
        eligible,
        key=lambda mode: (
            -dec(mode_results[mode]["validation"], "total_pnl_usdt"),
            dec(mode_results[mode]["validation"], "max_drawdown_pct"),
            mode_results[mode]["validation"]["median_hold_hours"],
        ),
    )
    next_hypothesis = ranked[0] if ranked else None
    status = (
        "NEXT_HYPOTHESIS_IDENTIFIED_POST_HOC"
        if next_hypothesis is not None
        else "NO_NEXT_HYPOTHESIS"
    )

    result = {
        "research_identity": RESEARCH_IDENTITY,
        "status": status,
        "authorization": "DIAGNOSTIC_ONLY_NO_CANDIDATE_NO_OOS_NO_SHADOW_NO_LIVE",
        "contamination_status": "POST_HOC_HISTORICAL_DIAGNOSTIC_ONLY",
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": SELECTION_CUTOFF.isoformat(),
            "rows": len(selection_bars),
            "sha256": selection_sha,
        },
        "artifacts": {
            "specification_sha256": specification_sha,
            "runner_sha256": file_sha256(Path(__file__)),
        },
        "modes": list(MODES),
        "checkpoints": {
            "dra_v1_validation": {
                "actual": list(base.checkpoint_tuple(dra_v1["validation"])),
                "expected": list(EXPECTED_V1_VALIDATION),
                "passed": True,
            },
            "v3_upper_validation": {
                "actual": list(base.checkpoint_tuple(upper_validation)),
                "expected": list(EXPECTED_V3_UPPER_VALIDATION),
                "passed": True,
            },
        },
        "historical": {
            "dra_v1": dra_v1,
            "modes": mode_results,
            "one_slot_30usdt_overlays": overlays,
        },
        "validation_entry_signal_overlap": signal_overlap(
            {mode: mode_results[mode]["validation"] for mode in MODES}
        ),
        "hypothesis_eligibility": screens,
        "eligible_modes": eligible,
        "next_hypothesis": next_hypothesis,
    }

    if include_posthoc_july:
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": base.data_hash(bars),
            "dra_v1": base.simulate(bars, JULY_POST_HOC, "v1"),
            "modes": {
                mode: v1.simulate_engine(
                    bars,
                    JULY_POST_HOC,
                    lambda mode=mode: QualificationEngine(mode),
                )
                for mode in MODES
            },
            "one_slot_30usdt_overlays": {
                mode: v1.simulate_engine(
                    bars,
                    JULY_POST_HOC,
                    lambda mode=mode: QualificationEngine(mode, cap=base.LOT_COST),
                )
                for mode in MODES
            },
        }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-posthoc-july", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.output, include_posthoc_july=args.include_posthoc_july)
    except base.ResearchReject as error:
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "next_hypothesis": result["next_hypothesis"],
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
