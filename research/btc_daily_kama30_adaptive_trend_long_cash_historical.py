#!/usr/bin/env python3
"""Deterministic historical screen for the frozen TA-Lib-default KAMA family."""

from __future__ import annotations

import argparse
from datetime import datetime
from decimal import Decimal, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
FAST_CONSTANT = D("2") / D("3")
SLOW_CONSTANT = D("2") / D("31")
CONSTANT_DIFFERENCE = FAST_CONSTANT - SLOW_CONSTANT

REPO_ROOT = Path(__file__).resolve().parents[1]
SUPPORT_SOURCE = REPO_ROOT / "research" / "btc_daily_chaikin_money_flow_long_cash_historical.py"
GATE_SOURCE = REPO_ROOT / "research" / "btc_daily_rsi14_midline_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-kama30-adaptive-trend-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-kama30-adaptive-trend-long-cash-v1.hypothesis.json"

EXPERIMENT_ID = "btc-daily-kama30-adaptive-trend-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_KAMA30_ADAPTIVE_TREND_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_SOURCE_HASHES = {
    SUPPORT_SOURCE: "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd",
    GATE_SOURCE: "1753cfd3a28bf7fdad8bb6a63e7de486d179491b2eab5d433830a71eca523281",
    REFERENCE_SOURCE: "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b",
    PARSER_SOURCE: "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
    PRIOR_SOURCE: "a79239651177a8fe283360a10073efd10ee6c878ad22d46367dc604dd63fc156",
    HYPOTHESIS_SOURCE: "35fbb4bbe6a7849256787936b6414d5238c51b8b18420d5dc9432618058a5c13",
}

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {str(y): (datetime(y, 1, 1), datetime(y + 1, 1, 1)) for y in range(2020, 2025)}
VARIANTS = {
    "PRIMARY_KAMA30": {"efficiency_period": 30, "role": "PRIMARY"},
    "NEIGHBOR_KAMA20": {"efficiency_period": 20, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_KAMA40": {"efficiency_period": 40, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
KAMA_TO_RSI = {
    "PRIMARY_KAMA30": "PRIMARY_RSI14_GT50",
    "NEIGHBOR_KAMA20": "NEIGHBOR_RSI14_GT45",
    "NEIGHBOR_KAMA40": "NEIGHBOR_RSI14_GT55",
}


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


def kama_targets(
    daily: list[object], efficiency_period: int, fast_period: int = 2,
    slow_period: int = 30,
) -> tuple[dict[datetime, bool], list[D]]:
    if efficiency_period not in {20, 30, 40} or (fast_period, slow_period) != (2, 30):
        raise ResearchReject("MANIFEST_REJECT:KAMA_POLICY")
    if len(daily) <= efficiency_period:
        raise ResearchReject("DATA_REJECT:KAMA_WARMUP")
    closes = [point.close for point in daily]
    sum_roc1 = sum(
        (abs(closes[index] - closes[index + 1]) for index in range(efficiency_period)),
        ZERO,
    )
    today = efficiency_period
    trailing_index = 0
    previous_kama = closes[today - 1]
    trailing_value = closes[trailing_index]
    targets: dict[datetime, bool] = {}
    gaps_pct: list[D] = []
    while today < len(closes):
        current = closes[today]
        current_trailing = closes[trailing_index]
        trailing_index += 1
        period_roc = current - current_trailing
        if today > efficiency_period:
            sum_roc1 -= abs(trailing_value - current_trailing)
            sum_roc1 += abs(current - closes[today - 1])
        trailing_value = current_trailing
        efficiency_ratio = (
            ONE if sum_roc1 <= period_roc or sum_roc1 == ZERO
            else abs(period_roc / sum_roc1)
        )
        if efficiency_ratio < ZERO or efficiency_ratio > ONE:
            raise ResearchReject(f"DATA_REJECT:KAMA_EFFICIENCY_RATIO:{daily[today].close_time.isoformat()}")
        smoothing_constant = SLOW_CONSTANT + efficiency_ratio * CONSTANT_DIFFERENCE
        smoothing_constant *= smoothing_constant
        previous_kama += (current - previous_kama) * smoothing_constant
        if previous_kama <= ZERO:
            raise ResearchReject(f"DATA_REJECT:KAMA_NON_POSITIVE:{daily[today].close_time.isoformat()}")
        targets[daily[today].close_time] = current > previous_kama
        gaps_pct.append(HUNDRED * (current - previous_kama) / previous_kama)
        today += 1
    return targets, gaps_pct


def simulate_window(support: ModuleType, reference: ModuleType, bars: list[object],
                    daily: list[object], window: tuple[datetime, datetime]):
    output: dict[str, object] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for name, variant in VARIANTS.items():
        targets, _ = kama_targets(daily, variant["efficiency_period"])
        output[name], raw[name] = {}, {}
        for scenario, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = support.simulate_scenario(
                reference, bars, targets, window, fee_rate, slippage
            )
            output[name][scenario] = scenario_output
            raw[name][scenario] = scenario_raw
    return output, raw


def _map_variants(value: dict[str, object]) -> dict[str, object]:
    return {KAMA_TO_RSI[name]: item for name, item in value.items()}


def _map_annual(annual):
    return {year: (_map_variants(output), _map_variants(raw)) for year, (output, raw) in annual.items()}


def evaluate_gates(support: ModuleType, gate_support: ModuleType, design,
                   validation_output, validation, annual):
    gates, failed, breadth = gate_support.evaluate_gates(
        support, _map_variants(design), _map_variants(validation_output),
        _map_variants(validation), _map_annual(annual),
    )

    def rename(value: str) -> str:
        return (
            value
            .replace("neighbor_rsi14_gt45", "neighbor_kama20")
            .replace("neighbor_rsi14_gt55", "neighbor_kama40")
            .replace("NEIGHBOR_RSI14_GT45", "NEIGHBOR_KAMA20")
            .replace("NEIGHBOR_RSI14_GT55", "NEIGHBOR_KAMA40")
            .replace("primary_rsi14", "primary_kama30")
            .replace("PRIMARY_RSI14", "PRIMARY_KAMA30")
            .replace("rsi14", "kama30")
            .replace("RSI14", "KAMA30")
        )

    return (
        {rename(name): passed for name, passed in gates.items()},
        [rename(name) for name in failed],
        {rename(name): item for name, item in breadth.items()},
    )


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("primary") != {"efficiency_period": 30, "fast_period": 2, "slow_period": 30, "relation": "CLOSE_STRICTLY_GREATER_THAN_KAMA"}:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"efficiency_period": 20, "fast_period": 2, "slow_period": 30, "relation": "CLOSE_STRICTLY_GREATER_THAN_KAMA"},
        {"efficiency_period": 40, "fast_period": 2, "slow_period": 30, "relation": "CLOSE_STRICTLY_GREATER_THAN_KAMA"},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("initialization") != "EXACT_TALIB_CORE_KAMA_UNSTABLE_PERIOD_ZERO" or policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:INITIALIZATION_OR_VARIANTS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    for path, expected in EXPECTED_SOURCE_HASHES.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{path}:{actual}")
    data_sha = sha256(input_path)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")
    parser = load_module("frozen_kama_h1_parser", PARSER_SOURCE)
    reference = load_module("frozen_kama_economic_reference", REFERENCE_SOURCE)
    support = load_module("frozen_kama_economic_support", SUPPORT_SOURCE)
    gate_support = load_module("frozen_kama_gate_support", GATE_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = support.build_daily_points(bars)
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise ResearchReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(daily)}")
    design_output, design_raw = simulate_window(support, reference, bars, daily, DESIGN)
    validation_output, validation_raw = simulate_window(support, reference, bars, daily, VALIDATION)
    annual = {year: simulate_window(support, reference, bars, daily, window) for year, window in ANNUAL.items()}
    gates, failed, breadth = evaluate_gates(support, gate_support, design_raw, validation_output, validation_raw, annual)
    _, primary_gaps = kama_targets(daily, 30)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_KAMA30_ADAPTIVE_TREND_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_DAILY_KAMA30_ADAPTIVE_TREND_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_DAILY_KAMA20_30_40_CLOSE_ABOVE_ADAPTIVE_LINE_LONG_CASH_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve()), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": data_sha, "hourly_rows": len(bars), "complete_utc_days": len(daily), "selection_cutoff": "2025-01-01T00:00:00", "first_complete_day_close": daily[0].close_time.isoformat(), "last_complete_day_close": daily[-1].close_time.isoformat()},
        "source_bindings": {path.relative_to(REPO_ROOT).as_posix(): digest for path, digest in EXPECTED_SOURCE_HASHES.items()},
        "feature_diagnostic": {"definition": "TALIB_DEFAULT_KAMA30_COMPLETE_DAY_CLOSE_MINUS_KAMA_PERCENT", "efficiency_period": 30, "fast_period": 2, "slow_period": 30, "unstable_period": 0, "evaluation_count": len(primary_gaps), "minimum": support.q(min(primary_gaps)), "maximum": support.q(max(primary_gaps)), "median": support.q(support.percentile(primary_gaps, D("0.5")) or ZERO)},
        "policy": {"primary": "COMPLETE_DAY_CLOSE_STRICTLY_GT_KAMA30_LONG_ELSE_CASH", "rejection_only_neighbors": ["COMPLETE_DAY_CLOSE_STRICTLY_GT_KAMA20_LONG_ELSE_CASH", "COMPLETE_DAY_CLOSE_STRICTLY_GT_KAMA40_LONG_ELSE_CASH"], "execution": "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY", "variants": 3},
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset_primary": {year: value[0]["PRIMARY_KAMA30"] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or permission to test another KAMA configuration.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    input_path, manifest_path, output_path = args.input.resolve(), args.manifest.resolve(), args.output.resolve()
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
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
