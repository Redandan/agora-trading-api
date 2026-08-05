#!/usr/bin/env python3
"""Causal, cooldown-preserving flat-range entry-recovery quality research."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import btc_dra_flat_range_qualification_timing_diagnostic_v3d as v3d

v3 = v3d.v3
v1 = v3.v1
base = v3d.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = (
    "BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_ENTRY_RECOVERY_QUALITY_V3E_RESEARCH"
)
BASELINE = "UNFILTERED_V3_UPPER"
FILTERS = (
    "SIGNAL_CLOSE_ABOVE_CAUSAL_HOURLY_EMA5",
    "SIGNAL_CLOSE_ABOVE_PRIOR_COMPLETE_UTC_DAY_CLOSE",
    "SIGNAL_CLOSE_ABOVE_EMA5_AND_PRIOR_DAY_CLOSE",
)
MODES = (BASELINE, *FILTERS)

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = (
    ROOT
    / "docs"
    / "btc-dra-flat-range-cooldown-preserving-entry-recovery-quality-v3e-research.md"
)
EXPECTED_SPEC_SHA256 = "b66e40f4414a595f94a6a07f9036c3333ceb1c65c0a198dbcabb1d3ff8b04de9"

SELECTION_CUTOFF = v3d.SELECTION_CUTOFF
SELECTION_ROWS = v3d.SELECTION_ROWS
SELECTION_SHA256 = v3d.SELECTION_SHA256
DESIGN = v3d.DESIGN
VALIDATION = v3d.VALIDATION
FOLDS = v3d.FOLDS
JULY_POST_HOC = v3d.JULY_POST_HOC

EXPECTED_V1_VALIDATION = v3d.EXPECTED_V1_VALIDATION
EXPECTED_V3_UPPER_VALIDATION = v3d.EXPECTED_V3_UPPER_VALIDATION
EXPECTED_V3_FIRST_POSITIVE_VALIDATION = (
    "6.16298366",
    "0E-8",
    "6.16298366",
    "3.892803",
    29.0,
    1171.6,
    18,
    18,
    0,
    0,
    "3.955540",
    "546.16298366",
)

RAPID_HOURS = 168
LONG_HOURS = 720
MIN_MATURE_VALIDATION = 10
VALIDATION_TOTAL_FLOOR = D("28.72999185")


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


def quantized_rate(numerator: int, denominator: int) -> str | None:
    if denominator == 0:
        return None
    return str(
        (D(numerator) / D(denominator)).quantize(
            D("0.000001"), rounding=ROUND_HALF_UP
        )
    )


def factor_pass_from_record(record: dict, mode: str) -> bool:
    if mode == BASELINE:
        return True
    ema5_pass = record.get("signal_close_above_causal_hourly_ema5") is True
    prior_day_pass = (
        record.get("signal_close_above_prior_complete_utc_day_close") is True
    )
    if mode == FILTERS[0]:
        return ema5_pass
    if mode == FILTERS[1]:
        return prior_day_pass
    if mode == FILTERS[2]:
        return ema5_pass and prior_day_pass
    raise ValueError(mode)


class RecoveryQualityEngine(v3d.QualificationEngine):
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
        self.mode = f"flat_range_entry_recovery_quality_{filter_mode.lower()}"
        self.factor = filter_mode
        self.prior_complete_day_close_for_bar: D | None = None
        self.reservation_records: list[dict] = []
        self.quality_rejection_count = 0

    def _indicators(self, bar: base.Bar) -> None:
        # Capture this before the 23:00 UTC bar completes the current day.
        self.prior_complete_day_close_for_bar = self.previous_daily_close
        super()._indicators(bar)

    def _signal(self, bar: base.Bar) -> bool:
        passed = super()._signal(bar)
        if not passed:
            return False
        meta = self.signal_meta[bar.open_time]
        ema5_pass = self.hourly_ema5 is not None and bar.close > self.hourly_ema5
        prior_day = self.prior_complete_day_close_for_bar
        prior_day_pass = prior_day is not None and bar.close > prior_day
        meta.update(
            {
                "causal_hourly_ema5": (
                    None if self.hourly_ema5 is None else str(self.hourly_ema5)
                ),
                "prior_complete_utc_day_close": (
                    None if prior_day is None else str(prior_day)
                ),
                "signal_close_above_causal_hourly_ema5": ema5_pass,
                "signal_close_above_prior_complete_utc_day_close": prior_day_pass,
                "entry_quality_filter_mode": self.filter_mode,
            }
        )
        return True

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

            # Reservation is the clock authority and is deliberately advanced
            # before the factor and capacity decisions.
            self.last_entry_signal = bar.open_time
            if not quality_pass:
                decision = "QUALITY_REJECT_COOLDOWN_RESERVED"
                self.quality_rejection_count += 1
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
                    "signal_close": meta["current_hourly_close"],
                    "causal_hourly_ema5": meta["causal_hourly_ema5"],
                    "prior_complete_utc_day_close": meta[
                        "prior_complete_utc_day_close"
                    ],
                    "signal_close_above_causal_hourly_ema5": meta[
                        "signal_close_above_causal_hourly_ema5"
                    ],
                    "signal_close_above_prior_complete_utc_day_close": meta[
                        "signal_close_above_prior_complete_utc_day_close"
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
        result = super().result(final_bar, start, end)
        decisions = [row["decision"] for row in self.reservation_records]
        admitted = decisions.count("ADMIT_TO_NEXT_OPEN")
        quality_rejected = decisions.count("QUALITY_REJECT_COOLDOWN_RESERVED")
        capacity_rejected = decisions.count("CAPACITY_REJECT_COOLDOWN_RESERVED")
        result["candidate"] = self.filter_mode
        result["entry_quality_audit"] = {
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
            "all_quality_rejections_advance_cooldown": all(
                row["cooldown_advanced"] is True
                for row in self.reservation_records
                if row["decision"] == "QUALITY_REJECT_COOLDOWN_RESERVED"
            ),
            "all_capacity_rejections_advance_cooldown": all(
                row["cooldown_advanced"] is True
                for row in self.reservation_records
                if row["decision"] == "CAPACITY_REJECT_COOLDOWN_RESERVED"
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
            "prior_day_reference_available_for_all_reservations": all(
                row["prior_complete_utc_day_close"] is not None
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
                "signal_close_above_causal_hourly_ema5": entry[
                    "signal_close_above_causal_hourly_ema5"
                ],
                "signal_close_above_prior_complete_utc_day_close": entry[
                    "signal_close_above_prior_complete_utc_day_close"
                ],
                "causal_hourly_ema5": entry["causal_hourly_ema5"],
                "prior_complete_utc_day_close": entry[
                    "prior_complete_utc_day_close"
                ],
                "signal_close": entry["current_hourly_close"],
                "signal_range_position": entry["signal_range_position"],
                "flat_ratio_to_atr14": entry["flat_ratio_to_atr14"],
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
    return {
        "filter_mode": mode,
        "filled_lot_count": len(selected),
        "mature_lot_count": mature,
        "rapid_recovery_count": counts["RAPID_RECOVERY"],
        "intermediate_recovery_count": counts["INTERMEDIATE_RECOVERY"],
        "long_underwater_count": counts["LONG_UNDERWATER"],
        "right_censored_count": counts["RIGHT_CENSORED"],
        "rapid_recovery_precision": quantized_rate(
            counts["RAPID_RECOVERY"], mature
        ),
        "long_underwater_rate": quantized_rate(counts["LONG_UNDERWATER"], mature),
        "observed_first_positive_count": len(observed_positive),
        "median_first_strict_net_positive_hours": base.percentile(
            observed_positive, 0.5
        ),
        "p90_first_strict_net_positive_hours": base.percentile(
            observed_positive, 0.9
        ),
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


QUALIFICATION_AUDIT_KEYS = (
    "all_entries_causal_and_lower_located",
    "all_gap_cancels_valid",
    "all_qualifications_strictly_after_fill",
    "all_qualifications_match_mode",
    "all_queues_after_qualification",
    "all_queues_reversal_and_positive",
    "all_exit_fills_positive",
)
ENTRY_AUDIT_KEYS = (
    "reservation_decisions_reconcile",
    "all_adjacent_reservations_respect_168h_cooldown",
    "all_quality_rejections_advance_cooldown",
    "all_capacity_rejections_advance_cooldown",
    "all_decisions_match_factor_truth",
    "all_filled_entries_pass_factor",
    "prior_day_reference_available_for_all_reservations",
)


def engine_audits_pass(result: dict) -> bool:
    return all(result["qualification_audit"][key] for key in QUALIFICATION_AUDIT_KEYS) and all(
        result["entry_quality_audit"][key] for key in ENTRY_AUDIT_KEYS
    )


def reservation_times(result: dict) -> list[str]:
    return [row["reservation_time"] for row in result["reservation_records"]]


def signal_times(result: dict) -> set[str]:
    return {row["signal_time"] for row in result["entry_records"]}


def dec(result: dict, field: str) -> D:
    return D(result[field])


def mode_screen(
    mode: str,
    economic: dict[str, dict],
    cohort: dict[str, dict[str, dict]],
    baseline_economic: dict[str, dict],
    baseline_cohort: dict[str, dict[str, dict]],
    dra_v1_validation: dict,
    mode_audits: dict[str, dict],
) -> dict:
    validation_cohort = cohort[mode]["validation"]
    design_cohort = cohort[mode]["design"]
    baseline_validation_cohort = baseline_cohort[BASELINE]["validation"]
    baseline_design_cohort = baseline_cohort[BASELINE]["design"]
    validation = economic[mode]["validation"]
    design = economic[mode]["design"]
    positive_folds = sum(
        dec(economic[mode][name], "total_pnl_usdt") > ZERO for name in FOLDS
    )
    all_audits = all(
        mode_audits[mode][name]["all_audits_pass"]
        for name in ("design", "validation", *FOLDS.keys())
    )
    return {
        "mature_admitted_validation_lots_at_least_10": validation_cohort[
            "mature_lot_count"
        ]
        >= MIN_MATURE_VALIDATION,
        "rapid_precision_higher_in_design": D(
            design_cohort["rapid_recovery_precision"] or "-1"
        )
        > D(baseline_design_cohort["rapid_recovery_precision"] or "-1"),
        "rapid_precision_higher_in_validation": D(
            validation_cohort["rapid_recovery_precision"] or "-1"
        )
        > D(baseline_validation_cohort["rapid_recovery_precision"] or "-1"),
        "long_underwater_rate_lower_in_design": D(
            design_cohort["long_underwater_rate"] or "2"
        )
        < D(baseline_design_cohort["long_underwater_rate"] or "2"),
        "long_underwater_rate_lower_in_validation": D(
            validation_cohort["long_underwater_rate"] or "2"
        )
        < D(baseline_validation_cohort["long_underwater_rate"] or "2"),
        "design_total_higher_than_v3_upper": dec(design, "total_pnl_usdt")
        > dec(baseline_economic[BASELINE]["design"], "total_pnl_usdt"),
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
        "all_cooldown_factor_causal_accounting_audits_pass": all_audits,
    }


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


def simulate_quality(
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
        lambda: RecoveryQualityEngine(
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
            name: simulate_quality(selection_bars, window, mode)
            for name, window in windows.items()
        }
        for mode in MODES
    }
    first_positive = {
        name: simulate_quality(
            selection_bars,
            window,
            BASELINE,
            qualification_mode="FIRST_STRICT_NET_POSITIVE",
        )
        for name, window in windows.items()
    }
    overlays = {
        mode: {
            name: simulate_quality(
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
        mode: mode_screen(
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
        if all(value for key, value in screens[mode].items() if key != "positive_total_folds")
    ]
    ranked = sorted(
        eligible,
        key=lambda mode: (
            -dec(economic[mode]["validation"], "total_pnl_usdt"),
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
            "v3_upper_entry_quality_modes": economic,
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
        july_first_positive = simulate_quality(
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
            "v3_upper_entry_quality_modes": {
                mode: simulate_quality(bars, JULY_POST_HOC, mode) for mode in MODES
            },
            "fixed_calendar_one_slot_30usdt": {
                mode: simulate_quality(
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
