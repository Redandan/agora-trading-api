#!/usr/bin/env python3
"""V2 pre-economic support and nonredundancy screen for a Corwin-Schultz DRA feature."""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, ROUND_HALF_UP, localcontext
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SPEC_TYPE = "DRA_CORWIN_SCHULTZ_SPREAD_PREOUTCOME_SUPPORT_SPEC_V2"
RESULT_TYPE = "DRA_CORWIN_SCHULTZ_SPREAD_PREOUTCOME_SUPPORT_RESULT_V2"
FAMILY_ID = "dra-corwin-schultz-spread-entry-admission"
DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
DATA_ROWS = 52_608
COMPLETE_DAYS = 2_192
DESIGN = (date(2020, 1, 1), date(2023, 1, 1))
VALIDATION = (date(2023, 1, 1), date(2025, 1, 1))
QUANTUM = D("0.00000001")
PI_OVER_TWO = D("1.5707963267948966192313216916397514")


class SupportReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyObservation:
    day: date
    open: D
    high: D
    low: D
    close: D
    hourly_closes: tuple[D, ...]
    quote_volume_proxy: D


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
    dataset = value.get("dataset")
    if dataset != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": DATA_SHA256,
        "hourly_rows": DATA_ROWS,
        "complete_utc_days": COMPLETE_DAYS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise SupportReject("SPEC_REJECT:DATASET")
    runner = value.get("runner_binding")
    runner_path = Path(__file__).resolve()
    if runner != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
    }:
        raise SupportReject("SPEC_REJECT:RUNNER_BINDING")
    gates = value.get("gates")
    expected_gate_keys = {
        "all_correlations_abs_at_most",
        "annual_minimum_each_state",
        "design_minimum_normalized_rows",
        "design_minimum_positive_raw_share",
        "design_minimum_transitions",
        "maximum_single_month_high_state_share",
        "primary_state_maximum_share",
        "primary_state_minimum_share",
        "validation_minimum_normalized_rows",
        "validation_minimum_positive_raw_share",
        "validation_minimum_transitions",
    }
    if not isinstance(gates, dict) or set(gates) != expected_gate_keys:
        raise SupportReject("SPEC_REJECT:GATES")
    return value


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
            DailyObservation(
                day=day,
                open=day_bars[0].open,
                high=max(bar.high for bar in day_bars),
                low=min(bar.low for bar in day_bars),
                close=day_bars[-1].close,
                hourly_closes=tuple(bar.close for bar in day_bars),
                quote_volume_proxy=sum(
                    (bar.close * bar.volume for bar in day_bars), D("0")
                ),
            )
        )
    if len(observations) != COMPLETE_DAYS:
        raise SupportReject(f"DATA_REJECT:COMPLETE_DAYS:{len(observations)}")
    return observations


def corwin_schultz_spread(previous: DailyObservation, current: DailyObservation) -> D:
    if min(previous.high, previous.low, current.high, current.low) <= 0:
        raise SupportReject("DATA_REJECT:NONPOSITIVE_HIGH_LOW")
    with localcontext() as context:
        context.prec = 50
        previous_range = (previous.high / previous.low).ln()
        current_range = (current.high / current.low).ln()
        beta = previous_range * previous_range + current_range * current_range
        two_day_range = (
            max(previous.high, current.high) / min(previous.low, current.low)
        ).ln()
        gamma = two_day_range * two_day_range
        denominator = D("3") - D("2") * D("2").sqrt()
        alpha = (
            ((D("2") * beta).sqrt() - beta.sqrt()) / denominator
            - (gamma / denominator).sqrt()
        )
        raw_spread = D("2") * (alpha.exp() - D("1")) / (D("1") + alpha.exp())
        return max(D("0"), raw_spread)


def daily_comparators(
    previous_close: D, observation: DailyObservation
) -> dict[str, D]:
    returns: list[D] = []
    prior = previous_close
    for close in observation.hourly_closes:
        returns.append(close / prior - D("1"))
        prior = close
    realized_variance = sum((value * value for value in returns), D("0"))
    bipower_variation = PI_OVER_TWO * sum(
        (abs(returns[index]) * abs(returns[index - 1]) for index in range(1, 24)),
        D("0"),
    )
    if realized_variance <= 0 or bipower_variation <= 0:
        raise SupportReject(f"DATA_REJECT:NONPOSITIVE_VARIATION:{observation.day}")
    if observation.quote_volume_proxy <= 0:
        raise SupportReject(f"DATA_REJECT:NONPOSITIVE_VOLUME:{observation.day}")
    return {
        "daily_range": (observation.high - observation.low) / observation.open,
        "realized_volatility": realized_variance.sqrt(),
        "amihud_illiquidity": abs(observation.close / observation.open - D("1"))
        / observation.quote_volume_proxy,
        "bipower_jumpiness": realized_variance / bipower_variation,
    }


def median(values: Iterable[D]) -> D:
    ordered = sorted(values)
    if not ordered:
        raise SupportReject("DATA_REJECT:EMPTY_MEDIAN")
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D("2")


def normalized_series(raw: list[tuple[date, D]], lookback: int = 20) -> dict[date, D]:
    normalized: dict[date, D] = {}
    for index in range(lookback, len(raw)):
        prior = median(value for _, value in raw[index - lookback : index])
        if prior > 0:
            normalized[raw[index][0]] = raw[index][1] / prior
    return normalized


def _midranks(values: list[D]) -> list[D]:
    order = sorted(range(len(values)), key=lambda index: (values[index], index))
    ranks = [D("0")] * len(values)
    cursor = 0
    while cursor < len(order):
        end = cursor + 1
        while end < len(order) and values[order[end]] == values[order[cursor]]:
            end += 1
        rank = (D(cursor + 1) + D(end)) / D("2")
        for offset in range(cursor, end):
            ranks[order[offset]] = rank
        cursor = end
    return ranks


def spearman(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 3:
        raise SupportReject("DATA_REJECT:CORRELATION_SAMPLE")
    left_ranks = _midranks(left)
    right_ranks = _midranks(right)
    left_mean = sum(left_ranks, D("0")) / D(len(left_ranks))
    right_mean = sum(right_ranks, D("0")) / D(len(right_ranks))
    covariance = sum(
        (
            (left_ranks[index] - left_mean) * (right_ranks[index] - right_mean)
            for index in range(len(left_ranks))
        ),
        D("0"),
    )
    left_variance = sum(
        ((value - left_mean) * (value - left_mean) for value in left_ranks), D("0")
    )
    right_variance = sum(
        ((value - right_mean) * (value - right_mean) for value in right_ranks),
        D("0"),
    )
    if left_variance <= 0 or right_variance <= 0:
        raise SupportReject("DATA_REJECT:CONSTANT_CORRELATION_AXIS")
    return covariance / (left_variance * right_variance).sqrt()


def _window_summary(
    start: date,
    end: date,
    raw_spreads: dict[date, D],
    normalized_spreads: dict[date, D],
    normalized_comparators: dict[str, dict[date, D]],
) -> dict[str, Any]:
    raw = [(day, value) for day, value in raw_spreads.items() if start <= day < end]
    normalized = [
        (day, value)
        for day, value in normalized_spreads.items()
        if start <= day < end
    ]
    states = [(day, value <= D("1")) for day, value in normalized]
    transitions = sum(
        states[index][1] != states[index - 1][1] for index in range(1, len(states))
    )
    correlations: dict[str, str] = {}
    for name, comparator in sorted(normalized_comparators.items()):
        common = [day for day, _ in normalized if day in comparator]
        correlation = spearman(
            [normalized_spreads[day] for day in common],
            [comparator[day] for day in common],
        )
        correlations[name] = str(correlation.quantize(QUANTUM, rounding=ROUND_HALF_UP))
    low_state = sum(state for _, state in states)
    monthly_high_state: dict[str, int] = defaultdict(int)
    for day, state in states:
        if not state:
            monthly_high_state[day.strftime("%Y-%m")] += 1
    high_state = len(states) - low_state
    top_month_high_share = (
        D(max(monthly_high_state.values(), default=0)) / D(high_state)
        if high_state
        else D("1")
    )
    return {
        "raw_spread_rows": len(raw),
        "raw_positive_rows": sum(value > 0 for _, value in raw),
        "raw_positive_share": str(
            (D(sum(value > 0 for _, value in raw)) / D(len(raw))).quantize(
                QUANTUM, rounding=ROUND_HALF_UP
            )
        ),
        "normalized_rows": len(normalized),
        "low_spread_state_rows": low_state,
        "high_spread_state_rows": high_state,
        "low_spread_state_share": str(
            (D(low_state) / D(len(states))).quantize(QUANTUM, rounding=ROUND_HALF_UP)
        ),
        "state_transitions": transitions,
        "top_month_high_state_share": str(
            top_month_high_share.quantize(QUANTUM, rounding=ROUND_HALF_UP)
        ),
        "spearman_correlations": correlations,
    }


def analyze(observations: list[DailyObservation], spec: dict[str, Any]) -> dict[str, Any]:
    raw_spread_rows: list[tuple[date, D]] = []
    comparator_rows: dict[str, list[tuple[date, D]]] = defaultdict(list)
    for index in range(1, len(observations)):
        current = observations[index]
        previous = observations[index - 1]
        raw_spread_rows.append((current.day, corwin_schultz_spread(previous, current)))
        for name, value in daily_comparators(previous.close, current).items():
            comparator_rows[name].append((current.day, value))
    raw_spreads = dict(raw_spread_rows)
    normalized_spreads = normalized_series(raw_spread_rows)
    normalized_comparators = {
        name: normalized_series(rows) for name, rows in comparator_rows.items()
    }
    design = _window_summary(
        *DESIGN, raw_spreads, normalized_spreads, normalized_comparators
    )
    validation = _window_summary(
        *VALIDATION, raw_spreads, normalized_spreads, normalized_comparators
    )
    annual_states: dict[str, dict[str, int]] = {}
    for year in range(2020, 2025):
        values = [
            value for day, value in normalized_spreads.items() if day.year == year
        ]
        low = sum(value <= D("1") for value in values)
        annual_states[str(year)] = {
            "normalized_rows": len(values),
            "low_spread_state_rows": low,
            "high_spread_state_rows": len(values) - low,
        }

    gates = spec["gates"]
    correlation_limit = D(str(gates["all_correlations_abs_at_most"]))
    gate_results = {
        "dataset_integrity": len(observations) == COMPLETE_DAYS,
        "two_day_raw_spread_coverage": len(raw_spread_rows) == COMPLETE_DAYS - 1,
        "design_normalized_rows": design["normalized_rows"]
        >= gates["design_minimum_normalized_rows"],
        "validation_normalized_rows": validation["normalized_rows"]
        >= gates["validation_minimum_normalized_rows"],
        "design_positive_raw_share": D(design["raw_positive_share"])
        >= D(str(gates["design_minimum_positive_raw_share"])),
        "validation_positive_raw_share": D(validation["raw_positive_share"])
        >= D(str(gates["validation_minimum_positive_raw_share"])),
        "design_primary_state_coverage": D(str(gates["primary_state_minimum_share"]))
        <= D(design["low_spread_state_share"])
        <= D(str(gates["primary_state_maximum_share"])),
        "validation_primary_state_coverage": D(
            str(gates["primary_state_minimum_share"])
        )
        <= D(validation["low_spread_state_share"])
        <= D(str(gates["primary_state_maximum_share"])),
        "design_transitions": design["state_transitions"]
        >= gates["design_minimum_transitions"],
        "validation_transitions": validation["state_transitions"]
        >= gates["validation_minimum_transitions"],
        "annual_state_breadth": all(
            min(summary["low_spread_state_rows"], summary["high_spread_state_rows"])
            >= gates["annual_minimum_each_state"]
            for summary in annual_states.values()
        ),
        "high_state_month_concentration": max(
            D(design["top_month_high_state_share"]),
            D(validation["top_month_high_state_share"]),
        )
        <= D(str(gates["maximum_single_month_high_state_share"])),
        "nonredundancy": all(
            abs(D(value)) <= correlation_limit
            for summary in (design, validation)
            for value in summary["spearman_correlations"].values()
        ),
    }
    support_pass = all(gate_results.values())
    return {
        "formula_version": "CORWIN_SCHULTZ_TWO_DAY_HIGH_LOW_SPREAD_ZERO_FLOOR_NO_OVERNIGHT_ADJUSTMENT_V1",
        "raw_spread_rows": len(raw_spread_rows),
        "raw_spread_zero_rows": sum(value == 0 for _, value in raw_spread_rows),
        "raw_spread_positive_rows": sum(value > 0 for _, value in raw_spread_rows),
        "normalized_spread_rows": len(normalized_spreads),
        "design": design,
        "validation": validation,
        "annual_states": annual_states,
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
    diagnostics = analyze(observations, spec)
    prior = spec["prior_binding"]
    prior_path = repository_path(prior["path"])
    if not prior_path.is_file() or sha256(prior_path) != prior["sha256"]:
        raise SupportReject("BINDING_REJECT:PRIOR")
    runner_path = Path(__file__).resolve()
    return {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "family_id": FAMILY_ID,
        "status": (
            "PASS_PREOUTCOME_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS"
            if diagnostics["support_pass"]
            else "DATA_REJECT_CLOSE_PREOUTCOME_SUPPORT"
        ),
        "support_pass": diagnostics["support_pass"],
        "spec_binding": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(spec_path),
        },
        "prior_binding": prior,
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
        "candidate_created": False,
        "oos_opened": False,
        "scope_note": "Pre-economic feature support and nonredundancy only. No strategy return, PnL, drawdown, fee, slippage, holding, inventory, candidate, OOS, external download, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
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


