#!/usr/bin/env python3
"""Deterministic historical screen for a frozen daily MACD long/cash family."""

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

REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_RUNNER_SOURCE = (
    REPO_ROOT / "research" / "btc_daily_rsi14_midline_long_cash_historical.py"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-macd12-26-9-histogram-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-daily-macd12-26-9-histogram-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-daily-macd12-26-9-histogram-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_DAILY_MACD12_26_9_HISTOGRAM_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BASE_RUNNER_SHA256 = (
    "1753cfd3a28bf7fdad8bb6a63e7de486d179491b2eab5d433830a71eca523281"
)
EXPECTED_PRIOR_SHA256 = (
    "8440d7fdac59f5055e7dc8c42d3e7b992debf24549f050ff78c186e982e37a72"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "4a25e4cf2ca38e5af6133756e902bc1a56af5e51103c621f376740b3becbb3f6"
)

VARIANTS = {
    "PRIMARY_MACD12_26_9": {
        "period": 12,
        "threshold": 26,
        "role": "PRIMARY",
    },
    "NEIGHBOR_MACD10_26_9": {
        "period": 10,
        "threshold": 26,
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
    "NEIGHBOR_MACD14_26_9": {
        "period": 14,
        "threshold": 26,
        "role": "REJECTION_ONLY_NEIGHBOR",
    },
}

BASE_VARIANT_NAMES = {
    "PRIMARY_MACD12_26_9": "PRIMARY_RSI14_GT50",
    "NEIGHBOR_MACD10_26_9": "NEIGHBOR_RSI14_GT45",
    "NEIGHBOR_MACD14_26_9": "NEIGHBOR_RSI14_GT55",
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


def ema_series(values: list[D], length: int) -> list[D | None]:
    if length <= 0:
        raise ResearchReject("MANIFEST_REJECT:EMA_LENGTH")
    output: list[D | None] = [None] * len(values)
    if len(values) < length:
        return output
    previous = sum(values[:length], ZERO) / D(length)
    output[length - 1] = previous
    alpha = D(2) / D(length + 1)
    for index in range(length, len(values)):
        previous = alpha * values[index] + (D(1) - alpha) * previous
        output[index] = previous
    return output


def target_by_execution_time(
    daily: list[object], fast_length: int, slow_length: int
) -> dict[datetime, bool]:
    if fast_length not in {10, 12, 14} or slow_length != 26:
        raise ResearchReject("MANIFEST_REJECT:MACD_POLICY")
    closes = [point.close for point in daily]
    fast = ema_series(closes, fast_length)
    slow = ema_series(closes, slow_length)
    macd_points: list[object] = []
    macd_values: list[D] = []
    for point, fast_value, slow_value in zip(daily, fast, slow, strict=True):
        if fast_value is None or slow_value is None:
            continue
        macd_points.append(point)
        macd_values.append(fast_value - slow_value)
    signal = ema_series(macd_values, 9)
    targets: dict[datetime, bool] = {}
    for point, macd, signal_value in zip(
        macd_points, macd_values, signal, strict=True
    ):
        if signal_value is not None:
            targets[point.close_time] = macd - signal_value > ZERO
    return targets


def _rename_gate(name: str) -> str:
    return name.replace(
        "neighbor_rsi14_gt45", "neighbor_macd10_26_9"
    ).replace("neighbor_rsi14_gt55", "neighbor_macd14_26_9")


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
        "fast_complete_days": 12,
        "slow_complete_days": 26,
        "signal_complete_macd_values": 9,
    }:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {
            "fast_complete_days": 10,
            "slow_complete_days": 26,
            "signal_complete_macd_values": 9,
        },
        {
            "fast_complete_days": 14,
            "slow_complete_days": 26,
            "signal_complete_macd_values": 9,
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
    base = load_module("frozen_macd_gate_and_result_support", BASE_RUNNER_SOURCE)
    _configure_base(base)
    result = base.build_output(input_path, manifest_path)
    passed = bool(result["all_gates_pass"])
    result["document_type"] = (
        "BTC_DAILY_MACD12_26_9_HISTOGRAM_LONG_CASH_HISTORICAL_RESULT_V1"
    )
    result["status"] = (
        "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
        if passed
        else "NO_CANDIDATE_CLOSE_BTC_DAILY_MACD12_26_9_HISTOGRAM_LONG_CASH_FAMILY"
    )
    result["decision"] = (
        "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
        if passed
        else "PERMANENTLY_CLOSE_EXACT_DAILY_MACD_FAST_10_12_14_SLOW_26_SIGNAL_9_HISTOGRAM_LONG_CASH_FAMILY_WITHOUT_TUNING"
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
        "price_source": "COMPLETE_UTC_DAY_CLOSE",
        "ema": "ALPHA_2_DIVIDED_BY_LENGTH_PLUS_1_INITIALIZED_BY_FIRST_LENGTH_SIMPLE_MEAN",
        "macd": "FAST_CLOSE_EMA_MINUS_SLOW_26_CLOSE_EMA",
        "signal": "EMA_9_OF_CONSECUTIVE_DEFINED_MACD_VALUES",
        "primary": "MACD_12_26_9_HISTOGRAM_STRICTLY_POSITIVE_LONG_ELSE_CASH",
        "rejection_only_neighbors": [
            "MACD_10_26_9_HISTOGRAM_STRICTLY_POSITIVE_LONG_ELSE_CASH",
            "MACD_14_26_9_HISTOGRAM_STRICTLY_POSITIVE_LONG_ELSE_CASH",
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
        "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or proof that another MACD tuple works."
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
