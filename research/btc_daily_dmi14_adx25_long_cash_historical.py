#!/usr/bin/env python3
"""Deterministic historical screen for a frozen daily DMI/ADX long/cash family."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from collections.abc import Callable
from datetime import datetime
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 34

D = Decimal
ZERO = D("0")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_RUNNER_SOURCE = (
    REPO_ROOT / "research" / "btc_daily_supertrend10x3_long_cash_historical.py"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-dmi14-adx25-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-dmi14-adx25-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-daily-dmi14-adx25-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_DMI14_ADX25_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_BASE_RUNNER_SHA256 = (
    "46db811403ae61f70eff615e342d8c4d93e7d72c1bb8edc162c4cc6022d18213"
)
EXPECTED_PRIOR_SHA256 = (
    "746534a4aa28ac4df1d9f124e8f3e0b546d5698abbea95620faea8b80931f11f"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "464e61702960db955f3e70d27b5213a9d81bacc46c20c8a4a1bde4212e84f8e4"
)

VARIANTS = {
    "PRIMARY_DMI14_ADX25": {
        "period": 14,
        "multiplier": D("25"),
        "role": "PRIMARY",
    },
    "NEIGHBOR_DMI14_ADX20": {
        "period": 14,
        "multiplier": D("20"),
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
    "NEIGHBOR_DMI14_ADX30": {
        "period": 14,
        "multiplier": D("30"),
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
}

BASE_VARIANT_NAMES = {
    "PRIMARY_DMI14_ADX25": "PRIMARY_SUPERTREND10X3",
    "NEIGHBOR_DMI14_ADX20": "NEIGHBOR_SUPERTREND10X2_5",
    "NEIGHBOR_DMI14_ADX30": "NEIGHBOR_SUPERTREND10X3_5",
}
BASE_VARIANTS = {
    BASE_VARIANT_NAMES[name]: value for name, value in VARIANTS.items()
}
OUTPUT_VARIANT_NAMES = {value: key for key, value in BASE_VARIANT_NAMES.items()}


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def wilder_smoothed_sums(values: list[D], length: int) -> list[D | None]:
    if length <= 0:
        raise ResearchReject("MANIFEST_REJECT:WILDER_LENGTH")
    output: list[D | None] = [None] * len(values)
    if len(values) < length:
        return output
    previous = sum(values[:length], ZERO)
    output[length - 1] = previous
    divisor = D(length)
    for index in range(length, len(values)):
        previous = previous - previous / divisor + values[index]
        output[index] = previous
    return output


def wilder_average(values: list[D], length: int) -> list[D | None]:
    if length <= 0:
        raise ResearchReject("MANIFEST_REJECT:WILDER_LENGTH")
    output: list[D | None] = [None] * len(values)
    if len(values) < length:
        return output
    divisor = D(length)
    previous = sum(values[:length], ZERO) / divisor
    output[length - 1] = previous
    for index in range(length, len(values)):
        previous = (previous * D(length - 1) + values[index]) / divisor
        output[index] = previous
    return output


def target_by_execution_time(
    daily: list[object], period: int, threshold: object
) -> dict[datetime, bool]:
    threshold_value = D(str(threshold))
    if period != 14 or threshold_value not in {D("20"), D("25"), D("30")}:
        raise ResearchReject("MANIFEST_REJECT:DMI_ADX_POLICY")
    if len(daily) < 2:
        return {}

    points: list[object] = []
    true_ranges: list[D] = []
    positive_dm: list[D] = []
    negative_dm: list[D] = []
    for previous, current in zip(daily[:-1], daily[1:], strict=True):
        up_move = current.high - previous.high
        down_move = previous.low - current.low
        positive_dm.append(
            up_move if up_move > down_move and up_move > ZERO else ZERO
        )
        negative_dm.append(
            down_move if down_move > up_move and down_move > ZERO else ZERO
        )
        true_ranges.append(
            max(
                current.high - current.low,
                abs(current.high - previous.close),
                abs(current.low - previous.close),
            )
        )
        points.append(current)

    smoothed_tr = wilder_smoothed_sums(true_ranges, period)
    smoothed_positive = wilder_smoothed_sums(positive_dm, period)
    smoothed_negative = wilder_smoothed_sums(negative_dm, period)

    dmi_points: list[object] = []
    positive_di: list[D] = []
    negative_di: list[D] = []
    dx_values: list[D] = []
    for point, tr_value, positive_value, negative_value in zip(
        points,
        smoothed_tr,
        smoothed_positive,
        smoothed_negative,
        strict=True,
    ):
        if tr_value is None or positive_value is None or negative_value is None:
            continue
        if tr_value == ZERO:
            plus = ZERO
            minus = ZERO
        else:
            plus = HUNDRED * positive_value / tr_value
            minus = HUNDRED * negative_value / tr_value
        denominator = plus + minus
        dx = (
            ZERO
            if denominator == ZERO
            else HUNDRED * abs(plus - minus) / denominator
        )
        dmi_points.append(point)
        positive_di.append(plus)
        negative_di.append(minus)
        dx_values.append(dx)

    adx_values = wilder_average(dx_values, period)
    targets: dict[datetime, bool] = {}
    for point, plus, minus, adx in zip(
        dmi_points, positive_di, negative_di, adx_values, strict=True
    ):
        if adx is not None:
            targets[point.close_time] = plus > minus and adx > threshold_value
    return targets


def _rename_gate(name: str) -> str:
    return name.replace(
        "neighbor_supertrend10x2_5", "neighbor_dmi14_adx20"
    ).replace("neighbor_supertrend10x3_5", "neighbor_dmi14_adx30")


def evaluate_gates(
    frozen_evaluate: Callable[..., tuple[dict[str, bool], list[str], dict[str, object]]],
    support: ModuleType,
    design: dict[str, object],
    validation_output: dict[str, object],
    validation: dict[str, object],
    annual: dict[str, tuple[dict[str, object], dict[str, object]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    gates, failed, breadth = frozen_evaluate(
        support,
        design,
        validation_output,
        validation,
        annual,
    )
    renamed = {_rename_gate(name): passed for name, passed in gates.items()}
    return renamed, [_rename_gate(name) for name in failed], breadth


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("primary") != {
        "di_complete_days": 14,
        "adx_complete_dx_values": 14,
        "adx_threshold": "25",
    }:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {
            "di_complete_days": 14,
            "adx_complete_dx_values": 14,
            "adx_threshold": "20",
        },
        {
            "di_complete_days": 14,
            "adx_complete_dx_values": 14,
            "adx_threshold": "30",
        },
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    runner_path = Path(__file__).resolve().relative_to(REPO_ROOT).as_posix()
    runner_bindings = [
        binding
        for binding in manifest.get("source_bindings", [])
        if binding.get("path") == runner_path
    ]
    if len(runner_bindings) != 1:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    if runner_bindings[0].get("sha256") != sha256(Path(__file__).resolve()):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_SHA256")
    if runner_bindings[0].get("role") != "FROZEN_DIRECT_ECONOMIC_RUNNER":
        raise ResearchReject("MANIFEST_REJECT:RUNNER_ROLE")


def _configure_base(base: ModuleType) -> None:
    frozen_evaluate = base.evaluate_gates
    base.EXPERIMENT_ID = EXPERIMENT_ID
    base.EXPECTED_MANIFEST_TYPE = EXPECTED_MANIFEST_TYPE
    base.PRIOR_SOURCE = PRIOR_SOURCE
    base.HYPOTHESIS_SOURCE = HYPOTHESIS_SOURCE
    base.EXPECTED_PRIOR_SHA256 = EXPECTED_PRIOR_SHA256
    base.EXPECTED_HYPOTHESIS_SHA256 = EXPECTED_HYPOTHESIS_SHA256
    base.VARIANTS = BASE_VARIANTS
    base.target_by_execution_time = target_by_execution_time
    base.validate_manifest = validate_manifest

    def bound_evaluate(
        support: ModuleType,
        design: dict[str, object],
        validation_output: dict[str, object],
        validation: dict[str, object],
        annual: dict[str, tuple[dict[str, object], dict[str, object]]],
    ) -> tuple[dict[str, bool], list[str], dict[str, object]]:
        return evaluate_gates(
            frozen_evaluate,
            support,
            design,
            validation_output,
            validation,
            annual,
        )

    base.evaluate_gates = bound_evaluate


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    if sha256(BASE_RUNNER_SOURCE) != EXPECTED_BASE_RUNNER_SHA256:
        raise ResearchReject("SOURCE_REJECT:BASE_RUNNER_SHA256")
    base = load_module("frozen_dmi_adx_gate_and_result_support", BASE_RUNNER_SOURCE)
    _configure_base(base)
    result = base.build_output(input_path, manifest_path)
    passed = bool(result["all_gates_pass"])
    result["document_type"] = (
        "BTC_DAILY_DMI14_ADX25_LONG_CASH_HISTORICAL_RESULT_V1"
    )
    result["status"] = (
        "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
        if passed
        else "NO_CANDIDATE_CLOSE_BTC_DAILY_DMI14_ADX25_LONG_CASH_FAMILY"
    )
    result["decision"] = (
        "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
        if passed
        else "PERMANENTLY_CLOSE_EXACT_DAILY_DMI14_ADX_THRESHOLDS_20_25_30_LONG_CASH_FAMILY_WITHOUT_TUNING"
    )
    result["runner"] = {
        "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(Path(__file__).resolve()),
        "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
    }
    result["source_bindings"]["gate_and_result_support_sha256"] = (
        EXPECTED_BASE_RUNNER_SHA256
    )
    result["policy"] = {
        "daily_input": "COMPLETE_UTC_DAY_HIGH_LOW_CLOSE",
        "directional_movement": "WILDER_COMPETING_POSITIVE_AND_NEGATIVE_ADJACENT_DAY_EXTREMA_MOVEMENT",
        "true_range": "MAX_HIGH_LOW_ABS_HIGH_PREVIOUS_CLOSE_ABS_LOW_PREVIOUS_CLOSE",
        "di_smoothing": "FIRST_14_SUM_THEN_PRIOR_MINUS_PRIOR_DIVIDED_BY_14_PLUS_CURRENT",
        "adx_smoothing": "FIRST_14_DX_MEAN_THEN_WILDER_RECURSIVE_AVERAGE",
        "primary": "POSITIVE_DI_STRICTLY_GT_NEGATIVE_DI_AND_ADX14_STRICTLY_GT_25_LONG_ELSE_CASH",
        "rejection_only_neighbors": [
            "POSITIVE_DI_STRICTLY_GT_NEGATIVE_DI_AND_ADX14_STRICTLY_GT_20_LONG_ELSE_CASH",
            "POSITIVE_DI_STRICTLY_GT_NEGATIVE_DI_AND_ADX14_STRICTLY_GT_30_LONG_ELSE_CASH",
        ],
        "delay": "ZERO",
        "minimum_hold": "UNTIL_DIFFERENT_SIGNAL",
        "execution": "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY",
        "variants": 3,
    }
    for window in ("design", "validation"):
        result["windows"][window] = {
            OUTPUT_VARIANT_NAMES[name]: value
            for name, value in result["windows"][window].items()
        }
    result["claim_boundary"] = (
        "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or proof that another DMI or ADX tuple works."
    )
    result["scope_note"] = (
        "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
    )
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(
            result,
            stream,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_gates": result["failed_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
