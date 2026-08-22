#!/usr/bin/env python3
"""Pre-economic deduplication and support probe for variable DRA lot sizing."""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SPEC_TYPE = "DRA_LAGGED_REALIZED_VARIANCE_SCALED_LOT_SIZING_PREOUTCOME_SPEC_V1"
RESULT_TYPE = "DRA_LAGGED_REALIZED_VARIANCE_SCALED_LOT_SIZING_PREOUTCOME_RESULT_V1"
FAMILY_ID = "dra-lagged-realized-variance-scaled-lot-sizing"
DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
DATA_ROWS = 52_608
COMPLETE_DAYS = 2_192
RAW_VARIANCE_ROWS = COMPLETE_DAYS - 1
NORMALIZED_ROWS = RAW_VARIANCE_ROWS - 20
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
BASE_LOT_COST = D("30")
PRIMARY_FLOOR = D("15")
NEIGHBOR_FLOORS = (D("10"), D("20"))
OPEN_COST_CAP = D("240")
OPEN_LOT_LIMIT = 8
QUANTUM = D("0.00000001")

NEW_ACTION_FINGERPRINT = (
    "ACTION_NEW_DRA_LOT_COST_ONLY|CLOCK_DRA_SIGNAL_ONLY|"
    "SIGNALS_PASS_THROUGH|SIZE_30_DIV_RV_RATIO_FLOOR_15_CAP_30|"
    "MAX_OPEN_COST_240|MAX_OPEN_LOTS_8|NO_LEVERAGE"
)
CLOSED_ADMISSION_FINGERPRINT = (
    "ACTION_DRA_ENTRY_ADMISSION|CLOCK_DRA_SIGNAL|"
    "HIGH_LAGGED_VOLATILITY_SIGNAL_COST_ZERO|THRESHOLD_1_0"
)
CLOSED_MONTHLY_TARGET_FINGERPRINT = (
    "ACTION_FULL_PORTFOLIO_TARGET_EXPOSURE|CLOCK_MONTHLY|"
    "TARGET_MIN_1_0_40_DIV_ANNUALIZED_30D_VOLATILITY"
)


class SupportReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyObservation:
    day: date
    closes: tuple[D, ...]


def sha256(path_or_bytes: Path | bytes) -> str:
    raw = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def repository_path(value: str) -> Path:
    path = (REPO_ROOT / value).resolve()
    try:
        path.relative_to(REPO_ROOT)
    except ValueError as error:
        raise SupportReject(f"PATH_REJECT:{path}") from error
    return path


def state_output_path(value: str) -> Path:
    path = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        path.relative_to(state_root)
    except ValueError as error:
        raise SupportReject(f"OUTPUT_PATH_REJECT:{path}") from error
    if path.exists():
        raise SupportReject(f"SEALED_OUTPUT_EXISTS:{path}")
    return path


def load_spec(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SupportReject("SPEC_REJECT:JSON") from error
    if value.get("document_type") != SPEC_TYPE:
        raise SupportReject("SPEC_REJECT:DOCUMENT_TYPE")
    if value.get("authorization") != AUTHORIZATION:
        raise SupportReject("SPEC_REJECT:AUTHORIZATION")
    if value.get("family_id") != FAMILY_ID:
        raise SupportReject("SPEC_REJECT:FAMILY")
    if value.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": DATA_SHA256,
        "hourly_rows": DATA_ROWS,
        "complete_utc_days": COMPLETE_DAYS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise SupportReject("SPEC_REJECT:DATASET")
    runner_path = Path(__file__).resolve()
    if value.get("runner_binding") != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
    }:
        raise SupportReject("SPEC_REJECT:RUNNER_BINDING")
    if value.get("action_fingerprints") != {
        "new_family": NEW_ACTION_FINGERPRINT,
        "closed_entry_admission": CLOSED_ADMISSION_FINGERPRINT,
        "closed_monthly_target": CLOSED_MONTHLY_TARGET_FINGERPRINT,
    }:
        raise SupportReject("SPEC_REJECT:ACTION_FINGERPRINTS")
    if value.get("sizing_policy") != {
        "feature": "LATEST_COMPLETE_UTC_DAY_REALIZED_VARIANCE_TO_PRIOR_20_COMPLETE_DAY_MEDIAN",
        "return": "H1_SIMPLE_CLOSE_TO_PREVIOUS_H1_CLOSE",
        "primary_floor_usdt": "15",
        "base_and_cap_usdt": "30",
        "rejection_only_floor_neighbors_usdt": ["10", "20"],
        "available_feature_mapping": "MAX_FLOOR_MIN_30_30_DIV_NORMALIZED_VARIANCE_RATIO_QUANTIZED_HALF_UP_1E_8",
        "unavailable_feature_mapping": "UNCHANGED_30_USDT_SIGNAL_PRESERVED",
        "maximum_open_cost_usdt": "240",
        "maximum_open_lots": 8,
        "leverage": "DENY",
        "short": "DENY",
        "variants": 3,
    }:
        raise SupportReject("SPEC_REJECT:SIZING_POLICY")
    expected_gate_keys = {
        "annual_minimum_primary_signal_interventions",
        "design_minimum_normalized_rows",
        "design_minimum_primary_signal_interventions",
        "maximum_top_year_primary_signal_intervention_share",
        "minimum_feature_available_signal_share",
        "minimum_neighbor_action_differences_each_window",
        "primary_scaled_day_share_maximum",
        "primary_scaled_day_share_minimum",
        "validation_minimum_normalized_rows",
        "validation_minimum_primary_signal_interventions",
    }
    gates = value.get("gates")
    if not isinstance(gates, dict) or set(gates) != expected_gate_keys:
        raise SupportReject("SPEC_REJECT:GATES")
    for binding in value.get("closed_family_bindings", []):
        bound = repository_path(binding["path"])
        if not bound.is_file() or sha256(bound) != binding["sha256"]:
            raise SupportReject(f"BINDING_REJECT:{binding.get('role')}")
    prior = value.get("prior_binding")
    if not isinstance(prior, dict):
        raise SupportReject("SPEC_REJECT:PRIOR_BINDING")
    prior_path = repository_path(prior["path"])
    if not prior_path.is_file() or sha256(prior_path) != prior["sha256"]:
        raise SupportReject("BINDING_REJECT:PRIOR")
    return value


def median(values: Iterable[D]) -> D:
    ordered = sorted(values)
    if not ordered:
        raise SupportReject("DATA_REJECT:EMPTY_MEDIAN")
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D("2")


def aggregate_complete_days(bars: list[base.Bar]) -> list[DailyObservation]:
    grouped: dict[date, list[base.Bar]] = defaultdict(list)
    for bar in bars:
        grouped[bar.open_time.date()].append(bar)
    observations: list[DailyObservation] = []
    previous_day: date | None = None
    for day in sorted(grouped):
        day_bars = grouped[day]
        if len(day_bars) != 24 or [bar.open_time.hour for bar in day_bars] != list(range(24)):
            raise SupportReject(f"DATA_REJECT:INCOMPLETE_DAY:{day.isoformat()}")
        if previous_day is not None and day.toordinal() != previous_day.toordinal() + 1:
            raise SupportReject(f"DATA_REJECT:DAY_GAP:{previous_day}:{day}")
        previous_day = day
        observations.append(
            DailyObservation(day=day, closes=tuple(bar.close for bar in day_bars))
        )
    if len(observations) != COMPLETE_DAYS:
        raise SupportReject(f"DATA_REJECT:COMPLETE_DAYS:{len(observations)}")
    return observations


def realized_variance(previous_close: D, closes: tuple[D, ...]) -> D:
    if previous_close <= 0 or len(closes) != 24 or any(close <= 0 for close in closes):
        raise SupportReject("DATA_REJECT:REALIZED_VARIANCE_INPUT")
    returns: list[D] = []
    prior = previous_close
    for close in closes:
        value = close / prior - D("1")
        returns.append(value)
        prior = close
    variance = sum((value * value for value in returns), D("0"))
    if variance <= 0 or not variance.is_finite():
        raise SupportReject("DATA_REJECT:NONPOSITIVE_REALIZED_VARIANCE")
    return variance


def raw_variances(observations: list[DailyObservation]) -> list[tuple[date, D]]:
    return [
        (
            observations[index].day,
            realized_variance(
                observations[index - 1].closes[-1], observations[index].closes
            ),
        )
        for index in range(1, len(observations))
    ]


def normalized_series(raw: list[tuple[date, D]], lookback: int = 20) -> dict[date, D]:
    normalized: dict[date, D] = {}
    for index in range(lookback, len(raw)):
        prior_median = median(value for _, value in raw[index - lookback : index])
        if prior_median <= 0:
            raise SupportReject("DATA_REJECT:NONPOSITIVE_PRIOR_VARIANCE_MEDIAN")
        with localcontext() as context:
            context.prec = 50
            normalized[raw[index][0]] = (raw[index][1] / prior_median).quantize(
                QUANTUM, rounding=ROUND_HALF_UP
            )
    return normalized


def scaled_lot_cost(normalized_variance_ratio: D, floor: D) -> D:
    if floor <= 0 or floor > BASE_LOT_COST:
        raise SupportReject("POLICY_REJECT:FLOOR")
    if normalized_variance_ratio <= 0 or not normalized_variance_ratio.is_finite():
        raise SupportReject("DATA_REJECT:NORMALIZED_VARIANCE_RATIO")
    with localcontext() as context:
        context.prec = 50
        raw = BASE_LOT_COST / normalized_variance_ratio
        return min(BASE_LOT_COST, max(floor, raw)).quantize(
            QUANTUM, rounding=ROUND_HALF_UP
        )


def action_lot_cost(normalized_variance_ratio: D | None, floor: D) -> D:
    return (
        BASE_LOT_COST
        if normalized_variance_ratio is None
        else scaled_lot_cost(normalized_variance_ratio, floor)
    )


class SignalOnlyDraEngine(base.Engine):
    """Reproduce the DRA decision clock without creating lots or reading outcomes."""

    def __init__(self) -> None:
        super().__init__("v1", cap=OPEN_COST_CAP)
        self.signal_times: list[datetime] = []

    def step_signal_only(self, bar: base.Bar) -> None:
        self._indicators(bar)
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            self.signal_times.append(bar.open_time)
            self.last_entry_signal = bar.open_time
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)


def signal_times_for_window(
    bars: list[base.Bar], window: tuple[datetime, datetime]
) -> list[datetime]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    engine = SignalOnlyDraEngine()
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step_signal_only(bar)
    return engine.signal_times


def _hash_rows(rows: list[dict[str, Any]]) -> str:
    return sha256(canonical_bytes(rows))


def _decimal_summary(values: list[D]) -> dict[str, str]:
    if not values:
        raise SupportReject("DATA_REJECT:EMPTY_ACTIONS")
    return {
        "minimum_usdt": str(min(values)),
        "median_usdt": str(median(values).quantize(QUANTUM, rounding=ROUND_HALF_UP)),
        "mean_usdt": str(
            (sum(values, D("0")) / D(len(values))).quantize(
                QUANTUM, rounding=ROUND_HALF_UP
            )
        ),
        "maximum_usdt": str(max(values)),
    }


def window_signal_actions(
    signal_times: list[datetime], normalized: dict[date, D]
) -> dict[str, Any]:
    signal_rows = [{"signal_time": value.isoformat()} for value in signal_times]
    variants: dict[str, Any] = {}
    for role, floor in (
        ("lower_floor_neighbor", NEIGHBOR_FLOORS[0]),
        ("primary", PRIMARY_FLOOR),
        ("upper_floor_neighbor", NEIGHBOR_FLOORS[1]),
    ):
        rows: list[dict[str, Any]] = []
        for signal_time in signal_times:
            ratio = normalized.get(signal_time.date())
            cost = action_lot_cost(ratio, floor)
            rows.append(
                {
                    "signal_time": signal_time.isoformat(),
                    "feature_available": ratio is not None,
                    "lot_cost_usdt": str(cost),
                }
            )
        costs = [D(row["lot_cost_usdt"]) for row in rows]
        variants[role] = {
            "floor_usdt": str(floor),
            "action_count": len(rows),
            "feature_available_signal_count": sum(
                row["feature_available"] for row in rows
            ),
            "feature_unavailable_signal_count": sum(
                not row["feature_available"] for row in rows
            ),
            "scaled_signal_count": sum(value < BASE_LOT_COST for value in costs),
            "floor_hit_count": sum(value == floor for value in costs),
            "unchanged_30_usdt_signal_count": sum(
                value == BASE_LOT_COST for value in costs
            ),
            "lot_cost_summary": _decimal_summary(costs),
            "action_sha256": _hash_rows(rows),
            "all_signals_preserved": len(rows) == len(signal_times)
            and all(value > 0 for value in costs),
        }
    return {
        "signal_count": len(signal_times),
        "signal_timestamp_sha256": _hash_rows(signal_rows),
        "variants": variants,
    }


def _normalized_window_summary(
    normalized: dict[date, D], start: datetime, end: datetime
) -> dict[str, Any]:
    selected = [value for day, value in normalized.items() if start.date() <= day < end.date()]
    primary_costs = [scaled_lot_cost(value, PRIMARY_FLOOR) for value in selected]
    scaled = sum(value < BASE_LOT_COST for value in primary_costs)
    return {
        "normalized_rows": len(selected),
        "primary_scaled_day_count": scaled,
        "primary_scaled_day_share": str(
            (D(scaled) / D(len(selected))).quantize(QUANTUM, rounding=ROUND_HALF_UP)
        ),
        "primary_floor_hit_day_count": sum(value == PRIMARY_FLOOR for value in primary_costs),
        "primary_lot_cost_summary": _decimal_summary(primary_costs),
    }


def analyze(
    bars: list[base.Bar], observations: list[DailyObservation], spec: dict[str, Any]
) -> dict[str, Any]:
    raw = raw_variances(observations)
    normalized = normalized_series(raw)
    normalized_windows = {
        "design": _normalized_window_summary(normalized, *DESIGN),
        "validation": _normalized_window_summary(normalized, *VALIDATION),
    }
    signal_windows = {
        "design": window_signal_actions(signal_times_for_window(bars, DESIGN), normalized),
        "validation": window_signal_actions(
            signal_times_for_window(bars, VALIDATION), normalized
        ),
    }
    annual_actions = {
        year: window_signal_actions(signal_times_for_window(bars, window), normalized)
        for year, window in ANNUAL.items()
    }
    primary_annual_interventions = {
        year: summary["variants"]["primary"]["scaled_signal_count"]
        for year, summary in annual_actions.items()
    }
    total_annual_interventions = sum(primary_annual_interventions.values())
    top_year_share = (
        D(max(primary_annual_interventions.values())) / D(total_annual_interventions)
        if total_annual_interventions
        else D("1")
    )
    gates = spec["gates"]
    feature_available_limit = D(str(gates["minimum_feature_available_signal_share"]))

    def feature_available_share(summary: dict[str, Any]) -> D:
        primary = summary["variants"]["primary"]
        return D(primary["feature_available_signal_count"]) / D(summary["signal_count"])

    def neighbor_action_differences(summary: dict[str, Any], role: str) -> int:
        primary_hash = summary["variants"]["primary"]["action_sha256"]
        return int(summary["variants"][role]["action_sha256"] != primary_hash)

    fingerprints = spec["action_fingerprints"]
    gate_results = {
        "dataset_integrity": len(observations) == COMPLETE_DAYS,
        "raw_variance_coverage": len(raw) == RAW_VARIANCE_ROWS,
        "normalized_variance_coverage": len(normalized) == NORMALIZED_ROWS,
        "design_normalized_rows": normalized_windows["design"]["normalized_rows"]
        >= gates["design_minimum_normalized_rows"],
        "validation_normalized_rows": normalized_windows["validation"]["normalized_rows"]
        >= gates["validation_minimum_normalized_rows"],
        "design_primary_scaled_day_share": D(
            normalized_windows["design"]["primary_scaled_day_share"]
        )
        >= D(str(gates["primary_scaled_day_share_minimum"]))
        and D(normalized_windows["design"]["primary_scaled_day_share"])
        <= D(str(gates["primary_scaled_day_share_maximum"])),
        "validation_primary_scaled_day_share": D(
            normalized_windows["validation"]["primary_scaled_day_share"]
        )
        >= D(str(gates["primary_scaled_day_share_minimum"]))
        and D(normalized_windows["validation"]["primary_scaled_day_share"])
        <= D(str(gates["primary_scaled_day_share_maximum"])),
        "signals_preserved_all_variants": all(
            variant["all_signals_preserved"]
            for summary in signal_windows.values()
            for variant in summary["variants"].values()
        ),
        "signal_feature_availability": all(
            feature_available_share(summary) >= feature_available_limit
            for summary in signal_windows.values()
        ),
        "design_primary_signal_interventions": signal_windows["design"]["variants"]
        ["primary"]["scaled_signal_count"]
        >= gates["design_minimum_primary_signal_interventions"],
        "validation_primary_signal_interventions": signal_windows["validation"]
        ["variants"]["primary"]["scaled_signal_count"]
        >= gates["validation_minimum_primary_signal_interventions"],
        "annual_primary_signal_interventions": all(
            count >= gates["annual_minimum_primary_signal_interventions"]
            for count in primary_annual_interventions.values()
        ),
        "top_year_signal_intervention_concentration": top_year_share
        <= D(str(gates["maximum_top_year_primary_signal_intervention_share"])),
        "neighbor_action_distinction": all(
            neighbor_action_differences(summary, role)
            >= gates["minimum_neighbor_action_differences_each_window"]
            for summary in signal_windows.values()
            for role in ("lower_floor_neighbor", "upper_floor_neighbor")
        ),
        "action_level_deduplication": len(set(fingerprints.values())) == 3
        and fingerprints["new_family"].startswith("ACTION_NEW_DRA_LOT_COST_ONLY")
        and "SIGNALS_PASS_THROUGH" in fingerprints["new_family"],
        "theoretical_open_cost_cap": BASE_LOT_COST * D(OPEN_LOT_LIMIT)
        == OPEN_COST_CAP,
        "primary_size_bounds": all(
            PRIMARY_FLOOR <= scaled_lot_cost(value, PRIMARY_FLOOR) <= BASE_LOT_COST
            for value in normalized.values()
        ),
    }
    support_pass = all(gate_results.values())
    return {
        "feature_formula": "SUM_24_SQUARED_H1_SIMPLE_CLOSE_TO_PREVIOUS_CLOSE_RETURNS_DIV_PRIOR_20_COMPLETE_DAY_MEDIAN_V1",
        "raw_variance_rows": len(raw),
        "normalized_variance_rows": len(normalized),
        "first_normalized_day": min(normalized).isoformat(),
        "last_normalized_day": max(normalized).isoformat(),
        "normalized_windows": normalized_windows,
        "signal_windows": signal_windows,
        "annual_primary_signal_interventions": primary_annual_interventions,
        "top_year_primary_signal_intervention_share": str(
            top_year_share.quantize(QUANTUM, rounding=ROUND_HALF_UP)
        ),
        "action_fingerprints": fingerprints,
        "gate_results": gate_results,
        "failed_gates": [name for name, passed in gate_results.items() if not passed],
        "support_pass": support_pass,
    }


def build_result(input_path: Path, spec_path: Path) -> dict[str, Any]:
    spec = load_spec(spec_path)
    if sha256(input_path) != DATA_SHA256:
        raise SupportReject("DATA_REJECT:SHA256")
    bars = base.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != DATA_ROWS or base.data_hash(bars) != DATA_SHA256:
        raise SupportReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    observations = aggregate_complete_days(bars)
    diagnostics = analyze(bars, observations, spec)
    runner_path = Path(__file__).resolve()
    return {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "family_id": FAMILY_ID,
        "status": (
            "PASS_PREOUTCOME_DEDUP_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS"
            if diagnostics["support_pass"]
            else "DATA_REJECT_PERMANENTLY_CLOSE_ACTION_FAMILY_BEFORE_HYPOTHESIS_OR_ECONOMICS"
        ),
        "support_pass": diagnostics["support_pass"],
        "spec_binding": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(spec_path),
        },
        "prior_binding": spec["prior_binding"],
        "closed_family_bindings": spec["closed_family_bindings"],
        "runner_binding": {
            "path": runner_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(runner_path),
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": DATA_SHA256,
            "hourly_rows": len(bars),
            "complete_utc_days": len(observations),
            "first_hour": bars[0].open_time.isoformat(),
            "selection_cutoff": bars[-1].close_time.isoformat(),
        },
        "diagnostics": diagnostics,
        "outcome_access": "DENY_NOT_ACCESSED",
        "economic_evidence": "MISSING_PROOF_NOT_ACCESSED",
        "hypothesis_created": False,
        "candidate_created": False,
        "oos_opened": False,
        "scope_note": "Pre-economic action deduplication, feature-to-size lattice, signal preservation, accounting bounds and support only. No strategy return, PnL, drawdown, fee, slippage, holding, inventory valuation, candidate, OOS, external download, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    input_path = args.input.resolve()
    spec_path = args.spec.resolve()
    for path in (input_path, spec_path):
        try:
            path.relative_to(REPO_ROOT)
        except ValueError as error:
            raise SupportReject(f"PATH_REJECT:{path}") from error
    output = state_output_path(args.output)
    result = build_result(input_path, spec_path)
    raw = canonical_bytes(result)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as target:
        target.write(raw)
    print(
        json.dumps(
            {
                "status": result["status"],
                "support_pass": result["support_pass"],
                "failed_gates": result["diagnostics"]["failed_gates"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(raw),
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
