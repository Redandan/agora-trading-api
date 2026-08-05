#!/usr/bin/env python3
"""Causal, cooldown-preserving frozen-upper-touch feasibility research."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_flat_range_cooldown_preserving_entry_recovery_quality_v3e as v3e

v3d = v3e.v3d
v3 = v3e.v3
v1 = v3e.v1
base = v3e.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = (
    "BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_UPPER_TOUCH_FEASIBILITY_V3G_RESEARCH"
)
BASELINE = v3e.BASELINE
FILTERS = (
    "UPPER_DISTANCE_AT_MOST_1_ATR",
    "RANGE_WIDTH_AT_MOST_6_ATR",
    "UPPER_DISTANCE_1ATR_AND_RANGE_WIDTH_6ATR",
)
MODES = (BASELINE, *FILTERS)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = (
    ROOT
    / "docs"
    / "btc-dra-flat-range-cooldown-preserving-upper-touch-feasibility-v3g-research.md"
)
EXPECTED_SPEC_SHA256 = "088284ec890431c83d6881471d03e75340f6fd936e88d8aba54d870294f7d313"

SELECTION_CUTOFF = v3e.SELECTION_CUTOFF
SELECTION_ROWS = v3e.SELECTION_ROWS
SELECTION_SHA256 = v3e.SELECTION_SHA256
DESIGN = v3e.DESIGN
VALIDATION = v3e.VALIDATION
FOLDS = v3e.FOLDS
JULY_POST_HOC = v3e.JULY_POST_HOC

EXPECTED_V1_VALIDATION = v3e.EXPECTED_V1_VALIDATION
EXPECTED_V3_UPPER_VALIDATION = v3e.EXPECTED_V3_UPPER_VALIDATION
EXPECTED_V3_FIRST_POSITIVE_VALIDATION = v3e.EXPECTED_V3_FIRST_POSITIVE_VALIDATION

COOLDOWN_HOURS = v3e.RAPID_HOURS
UPPER_TOUCH_HOURS = v3e.LONG_HOURS
MIN_MATURE_VALIDATION = v3e.MIN_MATURE_VALIDATION
VALIDATION_TOTAL_FLOOR = v3e.VALIDATION_TOTAL_FLOOR
ONE_ATR = D("1")
SIX_ATR = D("6")


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


def factor_pass_from_record(record: dict, mode: str) -> bool:
    if mode == BASELINE:
        return True
    distance_pass = record.get("upper_distance_at_most_1_atr") is True
    width_pass = record.get("range_width_at_most_6_atr") is True
    if mode == FILTERS[0]:
        return distance_pass
    if mode == FILTERS[1]:
        return width_pass
    if mode == FILTERS[2]:
        return distance_pass and width_pass
    raise ValueError(mode)


class UpperTouchFeasibilityEngine(v3d.QualificationEngine):
    def __init__(
        self,
        filter_mode: str,
        *,
        qualification_mode: str = "FROZEN_UPPER_THIRD_TOUCH",
        cap: D = base.REFERENCE_CAP,
        record_details: bool = True,
    ) -> None:
        if filter_mode not in MODES:
            raise ValueError(filter_mode)
        super().__init__(
            qualification_mode,
            cap=cap,
            record_details=record_details,
        )
        self.filter_mode = filter_mode
        self.mode = f"flat_range_upper_touch_feasibility_{filter_mode.lower()}"
        self.factor = filter_mode
        self.reservation_records: list[dict] = []

    def _range_state(self, bar: base.Bar) -> tuple[bool, dict]:
        range_ready, values = super()._range_state(bar)
        geometry_ready = range_ready and self.atr14 is not None and self.atr14 > ZERO
        if not geometry_ready:
            return range_ready, {
                **values,
                "upper_touch_geometry_ready": False,
            }
        upper = D(values["upper_third"])
        width = D(values["range_width20"])
        distance = upper - bar.close
        distance_atr = distance / self.atr14
        width_atr = width / self.atr14
        return range_ready, {
            **values,
            "upper_touch_geometry_ready": True,
            "geometry_signal_close": str(bar.close),
            "geometry_causal_atr14": str(self.atr14),
            "frozen_upper_distance": str(distance),
            "frozen_upper_distance_atr": str(distance_atr),
            "range_width_atr": str(width_atr),
            "upper_distance_at_most_1_atr": distance <= self.atr14 * ONE_ATR,
            "range_width_at_most_6_atr": width <= self.atr14 * SIX_ATR,
        }

    def _signal(self, bar: base.Bar) -> bool:
        passed = super()._signal(bar)
        if passed:
            self.signal_meta[bar.open_time]["upper_touch_filter_mode"] = (
                self.filter_mode
            )
        return passed

    def _quality_pass(self, meta: dict) -> bool:
        return factor_pass_from_record(meta, self.filter_mode)

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if (
            self.armed_at is not None
            and bar.open_time > self.armed_at
            and self._signal(bar)
        ):
            meta = self.signal_meta[bar.open_time]
            prior_reservation = self.last_entry_signal
            quality_pass = self._quality_pass(meta)
            open_cost = base.LOT_COST * D(len(self.lots))
            capacity_pass = open_cost + base.LOT_COST <= self.cap

            self.last_entry_signal = bar.open_time
            if not quality_pass:
                decision = "QUALITY_REJECT_COOLDOWN_RESERVED"
            elif not capacity_pass:
                decision = "CAPACITY_REJECT_COOLDOWN_RESERVED"
                self.blocked_count += 1
            else:
                decision = "ADMIT_TO_NEXT_OPEN"
                self.pending_signal = bar.open_time
                self.pending_atr = self.atr14

            self.reservation_records.append(
                {
                    "filter_mode": self.filter_mode,
                    "reservation_time": bar.open_time.isoformat(),
                    "prior_reservation_time": (
                        None
                        if prior_reservation is None
                        else prior_reservation.isoformat()
                    ),
                    "cooldown_hours_from_prior": (
                        None
                        if prior_reservation is None
                        else (bar.open_time - prior_reservation).total_seconds()
                        / 3600
                    ),
                    "quality_pass": quality_pass,
                    "capacity_pass": capacity_pass,
                    "decision": decision,
                    "last_entry_signal_after": self.last_entry_signal.isoformat(),
                    "cooldown_advanced": self.last_entry_signal == bar.open_time,
                    "upper_touch_geometry_ready": meta[
                        "upper_touch_geometry_ready"
                    ],
                    "signal_close": meta["geometry_signal_close"],
                    "causal_atr14": meta["geometry_causal_atr14"],
                    "frozen_upper_third": meta["upper_third"],
                    "range_width20": meta["range_width20"],
                    "frozen_upper_distance": meta["frozen_upper_distance"],
                    "frozen_upper_distance_atr": meta[
                        "frozen_upper_distance_atr"
                    ],
                    "range_width_atr": meta["range_width_atr"],
                    "upper_distance_at_most_1_atr": meta[
                        "upper_distance_at_most_1_atr"
                    ],
                    "range_width_at_most_6_atr": meta[
                        "range_width_at_most_6_atr"
                    ],
                    "current_day_excluded": meta["current_day_excluded"],
                }
            )
            self.armed_at = None
            self.arm_expires_at = None

        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    @staticmethod
    def _geometry_record_valid(row: dict) -> bool:
        close = D(row["signal_close"])
        atr = D(row["causal_atr14"])
        upper = D(row["frozen_upper_third"])
        width = D(row["range_width20"])
        distance = D(row["frozen_upper_distance"])
        distance_atr = D(row["frozen_upper_distance_atr"])
        width_atr = D(row["range_width_atr"])
        return (
            row["upper_touch_geometry_ready"] is True
            and atr > ZERO
            and distance >= ZERO
            and distance == upper - close
            and distance_atr == distance / atr
            and width_atr == width / atr
            and row["upper_distance_at_most_1_atr"]
            == (distance <= atr * ONE_ATR)
            and row["range_width_at_most_6_atr"] == (width <= atr * SIX_ATR)
            and row["current_day_excluded"] is True
        )

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = v3d.QualificationEngine.result(self, final_bar, start, end)
        decisions = [row["decision"] for row in self.reservation_records]
        admitted = decisions.count("ADMIT_TO_NEXT_OPEN")
        quality_rejected = decisions.count("QUALITY_REJECT_COOLDOWN_RESERVED")
        capacity_rejected = decisions.count("CAPACITY_REJECT_COOLDOWN_RESERVED")
        result["candidate"] = self.filter_mode
        result["upper_touch_entry_audit"] = {
            "filter_mode": self.filter_mode,
            "raw_reservation_count": len(self.reservation_records),
            "admitted_to_next_open_count": admitted,
            "quality_rejection_count": quality_rejected,
            "capacity_rejection_count": capacity_rejected,
            "filled_entry_count": len(self.entry_records),
            "gap_cancel_count": len(self.gap_cancel_records),
            "reservation_decisions_reconcile": len(self.reservation_records)
            == admitted + quality_rejected + capacity_rejected,
            "all_adjacent_reservations_respect_168h_cooldown": all(
                row["cooldown_hours_from_prior"] is None
                or row["cooldown_hours_from_prior"] >= COOLDOWN_HOURS
                for row in self.reservation_records
            ),
            "all_rejections_advance_cooldown": all(
                row["cooldown_advanced"] is True
                for row in self.reservation_records
                if row["decision"] != "ADMIT_TO_NEXT_OPEN"
            ),
            "all_decisions_match_factor_truth": all(
                (
                    row["decision"] == "QUALITY_REJECT_COOLDOWN_RESERVED"
                    and not row["quality_pass"]
                )
                or (
                    row["decision"] != "QUALITY_REJECT_COOLDOWN_RESERVED"
                    and row["quality_pass"]
                )
                for row in self.reservation_records
            ),
            "all_filled_entries_pass_factor": all(
                factor_pass_from_record(row, self.filter_mode)
                for row in self.entry_records
            ),
            "all_geometry_reconciles": all(
                self._geometry_record_valid(row) for row in self.reservation_records
            ),
        }
        if self.record_details:
            result["reservation_records"] = self.reservation_records
        return result


def label_records(upper_result: dict, end: datetime) -> list[dict]:
    touch_by_fill = {
        row["entry_fill_time"]: row for row in upper_result["qualification_records"]
    }
    labels = []
    for entry in upper_result["entry_records"]:
        fill_time = datetime.fromisoformat(entry["fill_time"])
        touch = touch_by_fill.get(entry["fill_time"])
        touch_hours = None
        if touch is not None:
            touch_time = datetime.fromisoformat(touch["qualification_time"])
            touch_hours = (touch_time - fill_time).total_seconds() / 3600
        mature = fill_time + timedelta(hours=UPPER_TOUCH_HOURS) <= end
        if touch_hours is not None and touch_hours <= UPPER_TOUCH_HOURS:
            label = "TIMELY_UPPER_TOUCH"
            failure_720h = False
        elif touch_hours is not None:
            label = "LATE_UPPER_TOUCH_AFTER_720H"
            failure_720h = True
        elif mature:
            label = "NO_UPPER_TOUCH_WITHIN_720H"
            failure_720h = True
        else:
            label = "RIGHT_CENSORED"
            failure_720h = None
        labels.append(
            {
                "signal_time": entry["signal_time"],
                "fill_time": entry["fill_time"],
                "effective_buy_price": entry["effective_buy_price"],
                "first_frozen_upper_touch_hours": touch_hours,
                "label": label,
                "upper_touch_failure_720h": failure_720h,
                "mature_720h_observation": mature,
                "frozen_upper_third": entry["frozen_upper_third"],
                "signal_close": entry["geometry_signal_close"],
                "causal_atr14": entry["geometry_causal_atr14"],
                "frozen_upper_distance": entry["frozen_upper_distance"],
                "frozen_upper_distance_atr": entry[
                    "frozen_upper_distance_atr"
                ],
                "range_width20": entry["range_width20"],
                "range_width_atr": entry["range_width_atr"],
                "upper_distance_at_most_1_atr": entry[
                    "upper_distance_at_most_1_atr"
                ],
                "range_width_at_most_6_atr": entry[
                    "range_width_at_most_6_atr"
                ],
            }
        )
    return labels


def cohort_summary(records: list[dict], mode: str) -> dict:
    selected = [row for row in records if factor_pass_from_record(row, mode)]
    timely = sum(row["label"] == "TIMELY_UPPER_TOUCH" for row in selected)
    late = sum(
        row["label"] == "LATE_UPPER_TOUCH_AFTER_720H" for row in selected
    )
    no_touch = sum(
        row["label"] == "NO_UPPER_TOUCH_WITHIN_720H" for row in selected
    )
    censored = sum(row["label"] == "RIGHT_CENSORED" for row in selected)
    failures = late + no_touch
    mature = timely + failures
    observed_touch = [
        row["first_frozen_upper_touch_hours"]
        for row in selected
        if row["first_frozen_upper_touch_hours"] is not None
    ]
    timely_touch = [value for value in observed_touch if value <= UPPER_TOUCH_HOURS]
    return {
        "filter_mode": mode,
        "filled_lot_count": len(selected),
        "mature_lot_count": mature,
        "timely_upper_touch_count": timely,
        "late_upper_touch_count": late,
        "no_upper_touch_within_720h_count": no_touch,
        "upper_touch_failure_720h_count": failures,
        "right_censored_count": censored,
        "timely_upper_touch_precision": v3e.quantized_rate(timely, mature),
        "upper_touch_failure_720h_rate": v3e.quantized_rate(failures, mature),
        "observed_touch_count": len(observed_touch),
        "median_observed_upper_touch_hours": base.percentile(observed_touch, 0.5),
        "p90_observed_upper_touch_hours": base.percentile(observed_touch, 0.9),
        "median_timely_upper_touch_hours": base.percentile(timely_touch, 0.5),
        "p90_timely_upper_touch_hours": base.percentile(timely_touch, 0.9),
    }


def confusion_table(records: list[dict], mode: str) -> dict:
    table = {
        "factor_pass_timely": 0,
        "factor_pass_failure_720h": 0,
        "factor_fail_timely": 0,
        "factor_fail_failure_720h": 0,
    }
    for row in records:
        if row["upper_touch_failure_720h"] is None:
            continue
        factor_pass = factor_pass_from_record(row, mode)
        outcome = "failure_720h" if row["upper_touch_failure_720h"] else "timely"
        key = ("factor_pass_" if factor_pass else "factor_fail_") + outcome
        table[key] += 1
    return table


QUALIFICATION_AUDIT_KEYS = v3e.QUALIFICATION_AUDIT_KEYS
GEOMETRY_AUDIT_KEYS = (
    "reservation_decisions_reconcile",
    "all_adjacent_reservations_respect_168h_cooldown",
    "all_rejections_advance_cooldown",
    "all_decisions_match_factor_truth",
    "all_filled_entries_pass_factor",
    "all_geometry_reconciles",
)


def engine_audits_pass(result: dict) -> bool:
    return all(
        result["qualification_audit"][key] for key in QUALIFICATION_AUDIT_KEYS
    ) and all(result["upper_touch_entry_audit"][key] for key in GEOMETRY_AUDIT_KEYS)


def reservation_times(result: dict) -> list[str]:
    return [row["reservation_time"] for row in result["reservation_records"]]


def signal_times(result: dict) -> set[str]:
    return {row["signal_time"] for row in result["entry_records"]}


def dec(result: dict, field: str) -> D:
    return D(result[field])


def build_audits(
    economic: dict[str, dict], overlays: dict[str, dict]
) -> tuple[dict, dict]:
    historical_audits: dict[str, dict] = {mode: {} for mode in MODES}
    overlay_audits: dict[str, dict] = {mode: {} for mode in MODES}
    for window in ("design", "validation", *FOLDS.keys()):
        baseline_reservations = reservation_times(economic[BASELINE][window])
        baseline_signals = signal_times(economic[BASELINE][window])
        overlay_baseline_reservations = reservation_times(overlays[BASELINE][window])
        for mode in MODES:
            result = economic[mode][window]
            historical_audits[mode][window] = {
                "reservation_times_equal_unfiltered_baseline": reservation_times(
                    result
                )
                == baseline_reservations,
                "filled_signal_times_subset_of_unfiltered_baseline": signal_times(
                    result
                ).issubset(baseline_signals),
                "engine_audits_pass": engine_audits_pass(result),
            }
            historical_audits[mode][window]["all_audits_pass"] = all(
                historical_audits[mode][window].values()
            )

            overlay = overlays[mode][window]
            overlay_audits[mode][window] = {
                "reservation_times_equal_unfiltered_one_slot_baseline": reservation_times(
                    overlay
                )
                == overlay_baseline_reservations,
                "filled_signal_times_subset_of_unfiltered_250usdt_baseline": signal_times(
                    overlay
                ).issubset(baseline_signals),
                "engine_audits_pass": engine_audits_pass(overlay),
            }
            overlay_audits[mode][window]["all_audits_pass"] = all(
                overlay_audits[mode][window].values()
            )
    return historical_audits, overlay_audits


def mode_screen(
    mode: str,
    economic: dict[str, dict],
    cohort: dict[str, dict[str, dict]],
    dra_v1_validation: dict,
    audits: dict[str, dict],
) -> dict:
    validation_cohort = cohort[mode]["validation"]
    design_cohort = cohort[mode]["design"]
    baseline_validation = cohort[BASELINE]["validation"]
    baseline_design = cohort[BASELINE]["design"]
    validation = economic[mode]["validation"]
    design = economic[mode]["design"]
    positive_folds = sum(
        dec(economic[mode][name], "total_pnl_usdt") > ZERO for name in FOLDS
    )
    all_audits = all(
        audits[mode][name]["all_audits_pass"]
        for name in ("design", "validation", *FOLDS.keys())
    )
    return {
        "mature_admitted_validation_lots_at_least_10": validation_cohort[
            "mature_lot_count"
        ]
        >= MIN_MATURE_VALIDATION,
        "timely_precision_higher_in_design": D(
            design_cohort["timely_upper_touch_precision"] or "-1"
        )
        > D(baseline_design["timely_upper_touch_precision"] or "-1"),
        "timely_precision_higher_in_validation": D(
            validation_cohort["timely_upper_touch_precision"] or "-1"
        )
        > D(baseline_validation["timely_upper_touch_precision"] or "-1"),
        "failure_rate_lower_in_design": D(
            design_cohort["upper_touch_failure_720h_rate"] or "2"
        )
        < D(baseline_design["upper_touch_failure_720h_rate"] or "2"),
        "failure_rate_lower_in_validation": D(
            validation_cohort["upper_touch_failure_720h_rate"] or "2"
        )
        < D(baseline_validation["upper_touch_failure_720h_rate"] or "2"),
        "design_total_higher_than_v3_upper": dec(design, "total_pnl_usdt")
        > dec(economic[BASELINE]["design"], "total_pnl_usdt"),
        "validation_total_at_least_90pct_v3_upper": dec(
            validation, "total_pnl_usdt"
        )
        >= VALIDATION_TOTAL_FLOOR,
        "validation_drawdown_no_higher_than_dra_v1": dec(
            validation, "max_drawdown_pct"
        )
        <= dec(dra_v1_validation, "max_drawdown_pct"),
        "validation_median_no_higher_than_dra_v1": validation[
            "median_hold_hours"
        ]
        is not None
        and validation["median_hold_hours"] <= dra_v1_validation["median_hold_hours"],
        "validation_p90_no_higher_than_dra_v1": validation["p90_hold_hours"]
        is not None
        and validation["p90_hold_hours"] <= dra_v1_validation["p90_hold_hours"],
        "validation_completed_sells_at_least_10": validation["sell_count"] >= 10,
        "positive_total_folds_at_least_4_of_5": positive_folds >= 4,
        "positive_total_folds": positive_folds,
        "all_cooldown_geometry_causal_accounting_audits_pass": all_audits,
    }


def simulate_geometry(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    mode: str,
    *,
    qualification_mode: str = "FROZEN_UPPER_THIRD_TOUCH",
    cap: D = base.REFERENCE_CAP,
) -> dict:
    return v1.simulate_engine(
        bars,
        window,
        lambda: UpperTouchFeasibilityEngine(
            mode,
            qualification_mode=qualification_mode,
            cap=cap,
            record_details=True,
        ),
    )


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
    economic = {
        mode: {
            name: simulate_geometry(selection_bars, window, mode)
            for name, window in windows.items()
        }
        for mode in MODES
    }
    first_positive_validation = simulate_geometry(
        selection_bars,
        VALIDATION,
        BASELINE,
        qualification_mode="FIRST_STRICT_NET_POSITIVE",
    )
    overlays = {
        mode: {
            name: simulate_geometry(
                selection_bars,
                window,
                mode,
                cap=base.LOT_COST,
            )
            for name, window in windows.items()
        }
        for mode in MODES
    }

    checkpoints = {
        "dra_v1_validation": {
            "actual": base.checkpoint_tuple(dra_v1["validation"]),
            "expected": EXPECTED_V1_VALIDATION,
        },
        "v3_upper_validation": {
            "actual": base.checkpoint_tuple(economic[BASELINE]["validation"]),
            "expected": EXPECTED_V3_UPPER_VALIDATION,
        },
        "v3_first_positive_validation": {
            "actual": base.checkpoint_tuple(first_positive_validation),
            "expected": EXPECTED_V3_FIRST_POSITIVE_VALIDATION,
        },
    }
    for name, values in checkpoints.items():
        if values["actual"] != values["expected"]:
            raise base.ResearchReject(
                "BASELINE_PARITY_REJECT",
                {
                    "checkpoint": name,
                    "actual": values["actual"],
                    "expected": values["expected"],
                },
            )

    labels = {
        name: label_records(economic[BASELINE][name], window[1])
        for name, window in windows.items()
    }
    cohort = {
        mode: {
            name: cohort_summary(labels[name], mode) for name in windows
        }
        for mode in MODES
    }
    confusion = {
        mode: {
            name: confusion_table(labels[name], mode)
            for name in ("design", "validation")
        }
        for mode in FILTERS
    }
    historical_audits, overlay_audits = build_audits(economic, overlays)
    screens = {
        mode: mode_screen(
            mode,
            economic,
            cohort,
            dra_v1["validation"],
            historical_audits,
        )
        for mode in FILTERS
    }
    eligible = [
        mode
        for mode in FILTERS
        if all(
            value
            for key, value in screens[mode].items()
            if key != "positive_total_folds"
        )
    ]
    ranked = sorted(
        eligible,
        key=lambda mode: (
            -dec(economic[mode]["validation"], "total_pnl_usdt"),
            D(cohort[mode]["validation"]["upper_touch_failure_720h_rate"] or "2"),
            economic[mode]["validation"]["median_hold_hours"],
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
        "formula": {
            "upper_distance_atr": "(FROZEN_UPPER_THIRD-SIGNAL_CLOSE)/CAUSAL_ATR14",
            "range_width_atr": "PRIOR20_COMPLETE_DAY_RANGE_WIDTH/CAUSAL_ATR14",
            "timely_touch": "FIRST_LATER_COMPLETE_HOURLY_CLOSE_GE_FROZEN_UPPER_WITHIN_720H",
        },
        "labels": {
            "upper_touch_horizon_hours": UPPER_TOUCH_HOURS,
            "records": labels,
            "cohort_summaries": cohort,
            "factor_confusion_tables": confusion,
        },
        "modes": list(MODES),
        "checkpoints": {
            name: {
                "actual": list(values["actual"]),
                "expected": list(values["expected"]),
                "passed": values["actual"] == values["expected"],
            }
            for name, values in checkpoints.items()
        },
        "historical": {
            "dra_v1": dra_v1,
            "v3_upper_geometry_modes": economic,
            "v3_first_positive_validation_checkpoint_engine": first_positive_validation,
            "fixed_calendar_one_slot_30usdt": overlays,
        },
        "audits": {
            "historical": historical_audits,
            "fixed_calendar_one_slot": overlay_audits,
        },
        "hypothesis_eligibility": screens,
        "eligible_modes": eligible,
        "next_hypothesis": next_hypothesis,
    }

    if include_posthoc_july:
        july_economic = {
            mode: simulate_geometry(bars, JULY_POST_HOC, mode) for mode in MODES
        }
        july_labels = label_records(july_economic[BASELINE], JULY_POST_HOC[1])
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": base.data_hash(bars),
            "labels": july_labels,
            "cohort_summaries": {
                mode: cohort_summary(july_labels, mode) for mode in MODES
            },
            "v3_upper_geometry_modes": july_economic,
            "fixed_calendar_one_slot_30usdt": {
                mode: simulate_geometry(
                    bars,
                    JULY_POST_HOC,
                    mode,
                    cap=base.LOT_COST,
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
