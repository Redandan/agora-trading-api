#!/usr/bin/env python3
"""Causal, cooldown-preserving flat-range floor-integrity research."""

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
    "BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_RANGE_FLOOR_INTEGRITY_V3F_RESEARCH"
)
BASELINE = v3e.BASELINE
FILTERS = (
    "PRIOR20_FLOOR_AGE_AT_LEAST_7_COMPLETE_DAYS",
    "PRIOR20_FLOOR_NOT_BELOW_PRECEDING20_FLOOR",
    "PRIOR20_FLOOR_AGE_7D_AND_NOT_BELOW_PRECEDING20",
)
MODES = (BASELINE, *FILTERS)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = (
    ROOT
    / "docs"
    / "btc-dra-flat-range-cooldown-preserving-range-floor-integrity-v3f-research.md"
)
EXPECTED_SPEC_SHA256 = "a2b32b75a38522bcf096954ec5197ce73b659f6d4e84862301ce3a3f81101088"

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

RAPID_HOURS = v3e.RAPID_HOURS
LONG_HOURS = v3e.LONG_HOURS
FLOOR_AGE_DAYS = 7
PRIOR_RANGE_DAYS = 20


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
    age_pass = record.get("prior20_floor_age_at_least_7_complete_days") is True
    stability_pass = (
        record.get("prior20_floor_not_below_preceding20_floor") is True
    )
    if mode == FILTERS[0]:
        return age_pass
    if mode == FILTERS[1]:
        return stability_pass
    if mode == FILTERS[2]:
        return age_pass and stability_pass
    raise ValueError(mode)


class FloorIntegrityEngine(v3d.QualificationEngine):
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
        self.mode = f"flat_range_floor_integrity_{filter_mode.lower()}"
        self.factor = filter_mode
        self.reservation_records: list[dict] = []

    def _range_state(self, bar: base.Bar) -> tuple[bool, dict]:
        range_ready, values = super()._range_state(bar)
        completed = list(self.completed_ranges)
        if completed and completed[-1]["day"].date() == bar.open_time.date():
            completed = completed[:-1]
        current = completed[-PRIOR_RANGE_DAYS:]
        preceding = completed[-(PRIOR_RANGE_DAYS * 2) : -PRIOR_RANGE_DAYS]
        floor_ready = (
            range_ready
            and len(current) == PRIOR_RANGE_DAYS
            and len(preceding) == PRIOR_RANGE_DAYS
        )
        if not floor_ready:
            return range_ready, {
                **values,
                "range_floor_integrity_ready": False,
                "current_floor_window_count": len(current),
                "preceding_floor_window_count": len(preceding),
            }

        current_floor = min(row["low"] for row in current)
        preceding_floor = min(row["low"] for row in preceding)
        floor_rows = [row for row in current if row["low"] == current_floor]
        floor_day = max(row["day"] for row in floor_rows)
        floor_age_days = (bar.open_time.date() - floor_day.date()).days
        current_day_excluded = all(
            row["day"].date() < bar.open_time.date()
            for row in (*preceding, *current)
        )
        nonoverlap = preceding[-1]["day"] < current[0]["day"]
        return range_ready, {
            **values,
            "range_floor_integrity_ready": True,
            "current_floor_window_count": len(current),
            "preceding_floor_window_count": len(preceding),
            "current_floor_first_day": current[0]["day"].isoformat(),
            "current_floor_last_day": current[-1]["day"].isoformat(),
            "preceding_floor_first_day": preceding[0]["day"].isoformat(),
            "preceding_floor_last_day": preceding[-1]["day"].isoformat(),
            "current_prior20_floor": str(current_floor),
            "preceding_nonoverlap_prior20_floor": str(preceding_floor),
            "current_prior20_floor_latest_day": floor_day.isoformat(),
            "current_prior20_floor_match_count": len(floor_rows),
            "current_prior20_floor_age_complete_days": floor_age_days,
            "prior20_floor_age_at_least_7_complete_days": floor_age_days
            >= FLOOR_AGE_DAYS,
            "prior20_floor_not_below_preceding20_floor": current_floor
            >= preceding_floor,
            "floor_windows_current_day_excluded": current_day_excluded,
            "floor_windows_nonoverlapping": nonoverlap,
        }

    def _signal(self, bar: base.Bar) -> bool:
        passed = super()._signal(bar)
        if passed:
            self.signal_meta[bar.open_time]["range_floor_filter_mode"] = (
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
                    "range_floor_integrity_ready": meta[
                        "range_floor_integrity_ready"
                    ],
                    "current_floor_window_count": meta[
                        "current_floor_window_count"
                    ],
                    "preceding_floor_window_count": meta[
                        "preceding_floor_window_count"
                    ],
                    "current_prior20_floor": meta["current_prior20_floor"],
                    "preceding_nonoverlap_prior20_floor": meta[
                        "preceding_nonoverlap_prior20_floor"
                    ],
                    "current_prior20_floor_latest_day": meta[
                        "current_prior20_floor_latest_day"
                    ],
                    "current_prior20_floor_age_complete_days": meta[
                        "current_prior20_floor_age_complete_days"
                    ],
                    "prior20_floor_age_at_least_7_complete_days": meta[
                        "prior20_floor_age_at_least_7_complete_days"
                    ],
                    "prior20_floor_not_below_preceding20_floor": meta[
                        "prior20_floor_not_below_preceding20_floor"
                    ],
                    "floor_windows_current_day_excluded": meta[
                        "floor_windows_current_day_excluded"
                    ],
                    "floor_windows_nonoverlapping": meta[
                        "floor_windows_nonoverlapping"
                    ],
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

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = v3d.QualificationEngine.result(self, final_bar, start, end)
        decisions = [row["decision"] for row in self.reservation_records]
        admitted = decisions.count("ADMIT_TO_NEXT_OPEN")
        quality_rejected = decisions.count("QUALITY_REJECT_COOLDOWN_RESERVED")
        capacity_rejected = decisions.count("CAPACITY_REJECT_COOLDOWN_RESERVED")
        result["candidate"] = self.filter_mode
        result["range_floor_entry_audit"] = {
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
                or row["cooldown_hours_from_prior"] >= RAPID_HOURS
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
            "all_floor_windows_ready": all(
                row["range_floor_integrity_ready"] is True
                for row in self.reservation_records
            ),
            "all_floor_windows_have_20_plus_20_days": all(
                row["current_floor_window_count"] == PRIOR_RANGE_DAYS
                and row["preceding_floor_window_count"] == PRIOR_RANGE_DAYS
                for row in self.reservation_records
            ),
            "all_floor_windows_causal_and_nonoverlapping": all(
                row["floor_windows_current_day_excluded"] is True
                and row["floor_windows_nonoverlapping"] is True
                for row in self.reservation_records
            ),
        }
        if self.record_details:
            result["reservation_records"] = self.reservation_records
        return result


def label_records(first_positive_result: dict, end: datetime) -> list[dict]:
    qualification_by_fill = {
        row["entry_fill_time"]: row for row in first_positive_result["qualification_records"]
    }
    labels = []
    for entry in first_positive_result["entry_records"]:
        fill_time = datetime.fromisoformat(entry["fill_time"])
        qualification = qualification_by_fill.get(entry["fill_time"])
        first_positive_hours = None
        if qualification is not None:
            qualification_time = datetime.fromisoformat(
                qualification["qualification_time"]
            )
            first_positive_hours = (
                qualification_time - fill_time
            ).total_seconds() / 3600
        mature = fill_time + timedelta(hours=LONG_HOURS) <= end
        if first_positive_hours is not None and first_positive_hours <= RAPID_HOURS:
            label = "RAPID_RECOVERY"
        elif first_positive_hours is not None and first_positive_hours <= LONG_HOURS:
            label = "INTERMEDIATE_RECOVERY"
        elif mature:
            label = "LONG_UNDERWATER"
        else:
            label = "RIGHT_CENSORED"
        labels.append(
            {
                "signal_time": entry["signal_time"],
                "fill_time": entry["fill_time"],
                "effective_buy_price": entry["effective_buy_price"],
                "first_strict_net_positive_hours": first_positive_hours,
                "label": label,
                "mature_720h_observation": mature,
                "current_prior20_floor": entry["current_prior20_floor"],
                "preceding_nonoverlap_prior20_floor": entry[
                    "preceding_nonoverlap_prior20_floor"
                ],
                "current_prior20_floor_latest_day": entry[
                    "current_prior20_floor_latest_day"
                ],
                "current_prior20_floor_age_complete_days": entry[
                    "current_prior20_floor_age_complete_days"
                ],
                "prior20_floor_age_at_least_7_complete_days": entry[
                    "prior20_floor_age_at_least_7_complete_days"
                ],
                "prior20_floor_not_below_preceding20_floor": entry[
                    "prior20_floor_not_below_preceding20_floor"
                ],
                "signal_range_position": entry["signal_range_position"],
            }
        )
    return labels


def cohort_summary(records: list[dict], mode: str) -> dict:
    selected = [row for row in records if factor_pass_from_record(row, mode)]
    counts = {
        label: sum(row["label"] == label for row in selected)
        for label in (
            "RAPID_RECOVERY",
            "INTERMEDIATE_RECOVERY",
            "LONG_UNDERWATER",
            "RIGHT_CENSORED",
        )
    }
    mature = counts["RAPID_RECOVERY"] + counts["INTERMEDIATE_RECOVERY"] + counts[
        "LONG_UNDERWATER"
    ]
    observed_positive = [
        row["first_strict_net_positive_hours"]
        for row in selected
        if row["first_strict_net_positive_hours"] is not None
    ]
    floor_ages = [
        row["current_prior20_floor_age_complete_days"] for row in selected
    ]
    return {
        "filter_mode": mode,
        "filled_lot_count": len(selected),
        "mature_lot_count": mature,
        "rapid_recovery_count": counts["RAPID_RECOVERY"],
        "intermediate_recovery_count": counts["INTERMEDIATE_RECOVERY"],
        "long_underwater_count": counts["LONG_UNDERWATER"],
        "right_censored_count": counts["RIGHT_CENSORED"],
        "rapid_recovery_precision": v3e.quantized_rate(
            counts["RAPID_RECOVERY"], mature
        ),
        "long_underwater_rate": v3e.quantized_rate(
            counts["LONG_UNDERWATER"], mature
        ),
        "observed_first_positive_count": len(observed_positive),
        "median_first_strict_net_positive_hours": base.percentile(
            observed_positive, 0.5
        ),
        "p90_first_strict_net_positive_hours": base.percentile(
            observed_positive, 0.9
        ),
        "median_floor_age_complete_days": base.percentile(floor_ages, 0.5),
        "p90_floor_age_complete_days": base.percentile(floor_ages, 0.9),
    }


def confusion_table(records: list[dict], mode: str) -> dict:
    table = {
        "factor_pass_rapid": 0,
        "factor_pass_long": 0,
        "factor_fail_rapid": 0,
        "factor_fail_long": 0,
    }
    for row in records:
        if row["label"] not in ("RAPID_RECOVERY", "LONG_UNDERWATER"):
            continue
        factor_pass = factor_pass_from_record(row, mode)
        key = (
            ("factor_pass_" if factor_pass else "factor_fail_")
            + ("rapid" if row["label"] == "RAPID_RECOVERY" else "long")
        )
        table[key] += 1
    return table


QUALIFICATION_AUDIT_KEYS = v3e.QUALIFICATION_AUDIT_KEYS
FLOOR_AUDIT_KEYS = (
    "reservation_decisions_reconcile",
    "all_adjacent_reservations_respect_168h_cooldown",
    "all_rejections_advance_cooldown",
    "all_decisions_match_factor_truth",
    "all_filled_entries_pass_factor",
    "all_floor_windows_ready",
    "all_floor_windows_have_20_plus_20_days",
    "all_floor_windows_causal_and_nonoverlapping",
)


def engine_audits_pass(result: dict) -> bool:
    return all(
        result["qualification_audit"][key] for key in QUALIFICATION_AUDIT_KEYS
    ) and all(result["range_floor_entry_audit"][key] for key in FLOOR_AUDIT_KEYS)


def reservation_times(result: dict) -> list[str]:
    return [row["reservation_time"] for row in result["reservation_records"]]


def signal_times(result: dict) -> set[str]:
    return {row["signal_time"] for row in result["entry_records"]}


def build_audits(
    economic: dict[str, dict],
    overlays: dict[str, dict],
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


def simulate_floor(
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
        lambda: FloorIntegrityEngine(
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
            name: simulate_floor(selection_bars, window, mode)
            for name, window in windows.items()
        }
        for mode in MODES
    }
    first_positive = {
        name: simulate_floor(
            selection_bars,
            window,
            BASELINE,
            qualification_mode="FIRST_STRICT_NET_POSITIVE",
        )
        for name, window in windows.items()
    }
    overlays = {
        mode: {
            name: simulate_floor(
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
            "actual": base.checkpoint_tuple(first_positive["validation"]),
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
        name: label_records(first_positive[name], window[1])
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
        mode: v3e.mode_screen(
            mode,
            economic,
            cohort,
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
            -v3e.dec(economic[mode]["validation"], "total_pnl_usdt"),
            D(cohort[mode]["validation"]["long_underwater_rate"] or "2"),
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
            "current_floor": "MIN_LOW_PRIOR_20_COMPLETE_UTC_DAYS",
            "preceding_floor": "MIN_LOW_PRECEDING_NONOVERLAP_20_COMPLETE_UTC_DAYS",
            "floor_day": "LATEST_DAY_MATCHING_CURRENT_FLOOR",
            "floor_age": "SIGNAL_UTC_DATE_MINUS_FLOOR_DAY_UTC_DATE",
        },
        "labels": {
            "rapid_recovery_hours": RAPID_HOURS,
            "long_underwater_hours": LONG_HOURS,
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
            "v3_upper_range_floor_modes": economic,
            "unfiltered_first_positive_label_engine": first_positive,
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
        july_first_positive = simulate_floor(
            bars,
            JULY_POST_HOC,
            BASELINE,
            qualification_mode="FIRST_STRICT_NET_POSITIVE",
        )
        july_labels = label_records(july_first_positive, JULY_POST_HOC[1])
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": base.data_hash(bars),
            "labels": july_labels,
            "cohort_summaries": {
                mode: cohort_summary(july_labels, mode) for mode in MODES
            },
            "v3_upper_range_floor_modes": {
                mode: simulate_floor(bars, JULY_POST_HOC, mode) for mode in MODES
            },
            "fixed_calendar_one_slot_30usdt": {
                mode: simulate_floor(
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
