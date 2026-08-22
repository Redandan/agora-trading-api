#!/usr/bin/env python3
"""Seal pre-outcome support for one BTC four-day variance-ratio state."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
Q12 = D("0.000000000001")
REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = REPO_ROOT / ".research-state/java-parity/selection-2019-2024.tsv"
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-primary-prior.v1.json"
PARSER_PATH = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "d692e290fead48f5ff09ad8d39375cee4cececc85b01328fd34eb748fe2d6111"
EXPECTED_TOTAL_EVALUATIONS = 2_164
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
EXPECTED_WINDOW_EVALUATIONS = {"design": 1_432, "validation": 731}
GATES = {
    "design": {
        "direction_positive": 300,
        "direction_nonpositive": 300,
        "variance_ratio_above_one": 300,
        "variance_ratio_at_or_below_one": 300,
        "joint_long": 200,
        "joint_cash": 300,
        "joint_transitions": 60,
        "trend_parent_vetoes": 100,
    },
    "validation": {
        "direction_positive": 150,
        "direction_nonpositive": 150,
        "variance_ratio_above_one": 150,
        "variance_ratio_at_or_below_one": 150,
        "joint_long": 100,
        "joint_cash": 150,
        "joint_transitions": 30,
        "trend_parent_vetoes": 50,
    },
}
ANNUAL_GATES = {"joint_long": 20, "joint_cash": 20, "joint_transitions": 6}


class SupportReject(RuntimeError):
    pass


@dataclass(frozen=True)
class DailyClose:
    close_time: datetime
    close: D


@dataclass(frozen=True)
class FeatureState:
    effective_time: datetime
    variance_ratio: D
    direction_positive: bool
    persistence: bool
    joint_long: bool


def sha256(value: bytes | Path) -> str:
    raw = value.read_bytes() if isinstance(value, Path) else value
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SupportReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SupportReject("SPEC_REJECT:READ_OR_JSON") from error
    family = "btc-h1-four-day-variance-ratio-positive-persistence-long-cash"
    if (
        spec.get("document_type") != "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_PREOUTCOME_SPEC_V1"
        or prior.get("document_type") != "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_LONG_CASH_PRIMARY_PRIOR_V1"
        or spec.get("family_id") != family
        or prior.get("family_id") != family
        or spec.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or prior.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise SupportReject("SPEC_REJECT:IDENTITY_OR_AUTHORIZATION")
    source = spec.get("source_contract", {})
    expected_source = {
        "path": DATA_PATH.relative_to(REPO_ROOT).as_posix(),
        "sha256": EXPECTED_DATA_SHA256,
        "hourly_rows": EXPECTED_DATA_ROWS,
        "complete_utc_days": EXPECTED_DAILY_ROWS,
        "selection_cutoff": "2025-01-01T00:00:00",
        "parser_path": PARSER_PATH.relative_to(REPO_ROOT).as_posix(),
        "parser_sha256": EXPECTED_PARSER_SHA256,
        "primary_prior_path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(),
        "primary_prior_sha256": EXPECTED_PRIOR_SHA256,
    }
    if source != expected_source:
        raise SupportReject("SPEC_REJECT:SOURCE_CONTRACT")
    factor = spec.get("factor_contract", {})
    expected_factor = {
        "factor_identity": "PRIOR_28_COMPLETE_UTC_DAY_POSITIVE_RETURN_AND_OVERLAPPING_FOUR_DAY_VARIANCE_RATIO_ABOVE_ONE_V1",
        "decision_clock": "AFTER_LATEST_COMPLETE_UTC_DAY_BEFORE_SAME_TIMESTAMP_NEXT_H1_OPEN_FILL",
        "window": "EXACTLY_29_CONSECUTIVE_COMPLETE_UTC_DAY_CLOSES_PRODUCING_28_LOG_RETURNS",
        "one_day_variance": "UNBIASED_SAMPLE_VARIANCE_OF_28_ONE_DAY_LOG_RETURNS",
        "four_day_variance": "UNBIASED_SAMPLE_VARIANCE_OF_25_OVERLAPPING_SUMS_OF_FOUR_CONSECUTIVE_ONE_DAY_LOG_RETURNS",
        "variance_ratio": "FOUR_DAY_VARIANCE_DIVIDED_BY_FOUR_TIMES_ONE_DAY_VARIANCE",
        "persistence_condition": "VARIANCE_RATIO_STRICTLY_GREATER_THAN_ONE",
        "direction_condition": "LATEST_CLOSE_STRICTLY_GREATER_THAN_CLOSE_28_COMPLETE_DAYS_EARLIER",
        "candidate_long_condition": "PERSISTENCE_AND_DIRECTION_BOTH_TRUE",
        "trend_parent_long_condition": "DIRECTION_TRUE_WITHOUT_VARIANCE_RATIO_FILTER",
        "cash_condition": "OTHERWISE",
        "arithmetic": "PYTHON_DECIMAL_PRECISION_50_WITH_DECIMAL_LN_NO_QUANTIZATION_BEFORE_COMPARISON",
        "expected_total_feature_evaluations": EXPECTED_TOTAL_EVALUATIONS,
        "expected_design_evaluations": EXPECTED_WINDOW_EVALUATIONS["design"],
        "expected_validation_evaluations": EXPECTED_WINDOW_EVALUATIONS["validation"],
    }
    if factor != expected_factor:
        raise SupportReject("SPEC_REJECT:FACTOR_CONTRACT")
    frozen = spec.get("pre_outcome_support_gates", {})
    for label, gates in GATES.items():
        for key, value in gates.items():
            if frozen.get(f"minimum_{label}_{key}") != value:
                raise SupportReject(f"SPEC_REJECT:SUPPORT_GATE:{label}:{key}")
    for key, value in ANNUAL_GATES.items():
        if frozen.get(f"minimum_each_year_{key}") != value:
            raise SupportReject(f"SPEC_REJECT:ANNUAL_GATE:{key}")
    execution = spec.get("execution_contract", {})
    if execution.get("maximum_support_runs") != 1 or execution.get("output_mode") != "CREATE_ONCE_UNDER_RESEARCH_STATE" or execution.get("oos") != "DENY":
        raise SupportReject("SPEC_REJECT:EXECUTION_CONTRACT")
    return spec


def build_daily_closes(bars: list[Any]) -> list[DailyClose]:
    daily: list[DailyClose] = []
    bucket: list[Any] = []
    for bar in bars:
        bucket.append(bar)
        if bar.close_time.hour != 0:
            continue
        if len(bucket) != 24:
            raise SupportReject(f"DATA_REJECT:UTC_DAY_BAR_COUNT:{bar.close_time.isoformat()}:{len(bucket)}")
        if bucket[0].open_time.hour != 0 or bucket[0].open_time.date() == bar.close_time.date():
            raise SupportReject(f"DATA_REJECT:UTC_DAY_BOUNDARY:{bucket[0].open_time.isoformat()}:{bar.close_time.isoformat()}")
        close = bucket[-1].close
        if not close.is_finite() or close <= ZERO:
            raise SupportReject(f"DATA_REJECT:NONPOSITIVE_DAILY_CLOSE:{bar.close_time.isoformat()}")
        daily.append(DailyClose(bar.close_time, close))
        bucket = []
    if bucket:
        raise SupportReject(f"DATA_REJECT:INCOMPLETE_FINAL_UTC_DAY:{len(bucket)}")
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise SupportReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(daily)}")
    for previous, current in zip(daily, daily[1:], strict=False):
        if (current.close_time - previous.close_time).total_seconds() != 86_400:
            raise SupportReject(f"DATA_REJECT:UTC_DAY_GAP:{previous.close_time}:{current.close_time}")
    return daily


def sample_variance(values: list[D]) -> D:
    if len(values) < 2:
        raise SupportReject("FEATURE_REJECT:VARIANCE_INVENTORY")
    mean = sum(values, ZERO) / D(len(values))
    return sum(((value - mean) ** 2 for value in values), ZERO) / D(len(values) - 1)


def calculate_state(window: list[DailyClose]) -> tuple[D, bool]:
    if len(window) != 29:
        raise SupportReject("FEATURE_REJECT:WINDOW_SIZE")
    returns = [(window[index].close / window[index - 1].close).ln() for index in range(1, 29)]
    variance_one = sample_variance(returns)
    if variance_one <= ZERO:
        raise SupportReject(f"FEATURE_REJECT:ZERO_ONE_DAY_VARIANCE:{window[-1].close_time.isoformat()}")
    four_day = [sum(returns[index : index + 4], ZERO) for index in range(25)]
    ratio = sample_variance(four_day) / (D("4") * variance_one)
    if not ratio.is_finite() or ratio < ZERO:
        raise SupportReject(f"FEATURE_REJECT:VARIANCE_RATIO:{window[-1].close_time.isoformat()}")
    return ratio, window[-1].close > window[0].close


def build_feature_states(
    daily: list[DailyClose], expected_evaluations: int = EXPECTED_TOTAL_EVALUATIONS
) -> list[FeatureState]:
    states: list[FeatureState] = []
    for index in range(28, len(daily)):
        ratio, direction = calculate_state(daily[index - 28 : index + 1])
        persistence = ratio > ONE
        states.append(FeatureState(daily[index].close_time, ratio, direction, persistence, direction and persistence))
    if len(states) != expected_evaluations:
        raise SupportReject(f"FEATURE_REJECT:EVALUATION_COUNT:{len(states)}")
    return states


def window_summary(states: list[FeatureState], start: datetime, end: datetime) -> dict[str, Any]:
    selected = [state for state in states if start <= state.effective_time < end]
    ratios = [state.variance_ratio for state in selected]
    joint = [state.joint_long for state in selected]
    return {
        "evaluations": len(selected),
        "direction_positive": sum(state.direction_positive for state in selected),
        "direction_nonpositive": sum(not state.direction_positive for state in selected),
        "variance_ratio_above_one": sum(state.persistence for state in selected),
        "variance_ratio_at_or_below_one": sum(not state.persistence for state in selected),
        "joint_long": sum(joint),
        "joint_cash": sum(not value for value in joint),
        "joint_transitions": sum(a != b for a, b in zip(joint, joint[1:], strict=False)),
        "trend_parent_vetoes": sum(state.direction_positive and not state.persistence for state in selected),
        "minimum_variance_ratio": str(min(ratios).quantize(Q12)),
        "maximum_variance_ratio": str(max(ratios).quantize(Q12)),
        "first_effective_time": selected[0].effective_time.isoformat(),
        "last_effective_time": selected[-1].effective_time.isoformat(),
    }


def support_pass(label: str, summary: dict[str, Any]) -> tuple[bool, list[str]]:
    failed: list[str] = []
    if summary["evaluations"] != EXPECTED_WINDOW_EVALUATIONS[label]:
        failed.append("evaluations")
    for key, minimum in GATES[label].items():
        if summary[key] < minimum:
            failed.append(key)
    return not failed, failed


def build_output() -> dict[str, Any]:
    load_and_validate_spec()
    if sha256(DATA_PATH) != EXPECTED_DATA_SHA256 or sha256(PARSER_PATH) != EXPECTED_PARSER_SHA256 or sha256(PRIOR_PATH) != EXPECTED_PRIOR_SHA256:
        raise SupportReject("SOURCE_REJECT:FROZEN_SHA256")
    parser = load_module("variance_ratio_h1_parser", PARSER_PATH)
    bars = parser.parse_rows(DATA_PATH.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise SupportReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = build_daily_closes(bars)
    states = build_feature_states(daily)
    design = window_summary(states, *DESIGN)
    validation = window_summary(states, *VALIDATION)
    annual = {
        str(year): window_summary(states, datetime(year, 1, 1), datetime(year + 1, 1, 1))
        for year in range(2019, 2025)
    }
    design_ok, design_failed = support_pass("design", design)
    validation_ok, validation_failed = support_pass("validation", validation)
    annual_failed = {
        year: [key for key, minimum in ANNUAL_GATES.items() if summary[key] < minimum]
        for year, summary in annual.items()
    }
    annual_failed = {year: failed for year, failed in annual_failed.items() if failed}
    passed = design_ok and validation_ok and not annual_failed
    lattice = [
        {
            "effective_time": state.effective_time.isoformat(),
            "variance_ratio": str(state.variance_ratio),
            "direction_positive": state.direction_positive,
            "persistence": state.persistence,
            "joint_long": state.joint_long,
        }
        for state in states
    ]
    return {
        "schema_version": "1",
        "document_type": "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_PREOUTCOME_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "family_id": "btc-h1-four-day-variance-ratio-positive-persistence-long-cash",
        "status": "PASS_PREOUTCOME_SUPPORT_ALLOW_ONE_HYPOTHESIS" if passed else "DATA_REJECT_CLOSE_PREOUTCOME_SUPPORT",
        "source_bindings": {
            "dataset": {"path": DATA_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_DATA_SHA256, "rows": len(bars)},
            "parser": {"path": PARSER_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_PARSER_SHA256},
            "primary_prior": {"path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_PRIOR_SHA256},
            "preoutcome_spec": {"path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(SPEC_PATH)},
            "support_probe": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        },
        "feature_lattice": {
            "evaluations": len(states),
            "first_effective_time": states[0].effective_time.isoformat(),
            "last_effective_time": states[-1].effective_time.isoformat(),
            "sha256": sha256(canonical_bytes(lattice)),
        },
        "support": {"design": design, "validation": validation, "annual": annual},
        "failed_support_gates": {"design": design_failed, "validation": validation_failed, "annual": annual_failed},
        "support_pass": passed,
        "forward_outcome_accessed": False,
        "strategy_economics_accessed": False,
        "candidate_created": False,
        "oos_opened": False,
        "scope_note": "Only causal decision-time feature states were computed from the sealed pre-2025 H1 corpus. No forward return, PnL, drawdown, Calmar, holding, inventory, OOS, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not output.is_relative_to(state_root) or output.exists():
        raise SupportReject("OUTPUT_PATH_REJECT")
    result = build_output()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as stream:
        stream.write(canonical_bytes(result))
    print(json.dumps({"status": result["status"], "output": output.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output), "failed_support_gates": result["failed_support_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
