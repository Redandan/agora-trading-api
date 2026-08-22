#!/usr/bin/env python3
"""Deterministic historical screen for the frozen daily MFI14 long/cash family."""

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
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
SUPPORT_SOURCE = REPO_ROOT / "research" / "btc_daily_chaikin_money_flow_long_cash_historical.py"
GATE_SOURCE = REPO_ROOT / "research" / "btc_daily_rsi14_midline_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-money-flow-index-14-midline-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-money-flow-index-14-midline-long-cash-v1.hypothesis.json"
ELIGIBILITY_SOURCE = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-money-flow-index-14-midline-long-cash-eligibility-erratum.v1.json"

EXPERIMENT_ID = "btc-daily-money-flow-index-14-midline-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_DAILY_MONEY_FLOW_INDEX_14_MIDLINE_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_SOURCE_HASHES = {
    SUPPORT_SOURCE: "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd",
    GATE_SOURCE: "2fd30293af8b7100da901808e462216249536f90d0e7ae05ddb976d575d7fd05",
    REFERENCE_SOURCE: "b4ad002a721c022a958c09f931361c897d283fb745adae286d1e9a74114e1534",
    PARSER_SOURCE: "ec3e2d6712f7348618e3969cfb8b8d035dcb4d01deb396d8154f83a1185b1d2a",
    PRIOR_SOURCE: "41d7e49ccdbdcc3b62e8bd4a9d4d5c239babf384443cf79c2e235dab2c49bbb1",
    HYPOTHESIS_SOURCE: "fface73c36168295592380c679b2f6049b383742b2dcaea47a242d8a24ca87aa",
    ELIGIBILITY_SOURCE: "b681056fe496f331b84a98b6f2cc363e6f46c0087285c895df4ffb3b521709b4",
}

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
VARIANTS = {
    "PRIMARY_MFI14_GT50": {"period": 14, "threshold": 50, "role": "PRIMARY"},
    "NEIGHBOR_MFI14_GT45": {"period": 14, "threshold": 45, "role": "REJECTION_ONLY_NEIGHBOR"},
    "NEIGHBOR_MFI14_GT55": {"period": 14, "threshold": 55, "role": "REJECTION_ONLY_NEIGHBOR"},
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
MFI_TO_RSI = {
    "PRIMARY_MFI14_GT50": "PRIMARY_RSI14_GT50",
    "NEIGHBOR_MFI14_GT45": "NEIGHBOR_RSI14_GT45",
    "NEIGHBOR_MFI14_GT55": "NEIGHBOR_RSI14_GT55",
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


def mfi_targets(
    daily: list[object], period: int, threshold: int
) -> tuple[dict[datetime, bool], list[D]]:
    if period != 14 or threshold not in {45, 50, 55}:
        raise ResearchReject("MANIFEST_REJECT:MFI_POLICY")
    targets: dict[datetime, bool] = {}
    values: list[D] = []
    typical = [(point.high + point.low + point.close) / D("3") for point in daily]
    raw_flow = [typical[index] * daily[index].volume for index in range(len(daily))]
    for index in range(period, len(daily)):
        positive = ZERO
        negative = ZERO
        for cursor in range(index - period + 1, index + 1):
            if typical[cursor] > typical[cursor - 1]:
                positive += raw_flow[cursor]
            elif typical[cursor] < typical[cursor - 1]:
                negative += raw_flow[cursor]
        denominator = positive + negative
        value = ZERO if denominator == ZERO else HUNDRED * positive / denominator
        if value < ZERO or value > HUNDRED:
            raise ResearchReject(f"DATA_REJECT:MFI_RANGE:{daily[index].close_time.isoformat()}")
        targets[daily[index].close_time] = value > D(threshold)
        values.append(value)
    return targets, values


def simulate_window(
    support: ModuleType,
    reference: ModuleType,
    bars: list[object],
    daily: list[object],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, object], dict[str, dict[str, dict[str, D]]]]:
    output: dict[str, object] = {}
    raw: dict[str, dict[str, dict[str, D]]] = {}
    for name, variant in VARIANTS.items():
        targets, _ = mfi_targets(daily, variant["period"], variant["threshold"])
        output[name] = {}
        raw[name] = {}
        for scenario, (fee_rate, slippage) in SCENARIOS.items():
            scenario_output, scenario_raw = support.simulate_scenario(
                reference, bars, targets, window, fee_rate, slippage
            )
            output[name][scenario] = scenario_output
            raw[name][scenario] = scenario_raw
    return output, raw


def _map_variants(value: dict[str, object]) -> dict[str, object]:
    return {MFI_TO_RSI[name]: item for name, item in value.items()}


def _map_annual(
    annual: dict[str, tuple[dict[str, object], dict[str, object]]]
) -> dict[str, tuple[dict[str, object], dict[str, object]]]:
    return {
        year: (_map_variants(output), _map_variants(raw))
        for year, (output, raw) in annual.items()
    }


def evaluate_gates(
    support: ModuleType,
    gate_support: ModuleType,
    design: dict[str, object],
    validation_output: dict[str, object],
    validation: dict[str, object],
    annual: dict[str, tuple[dict[str, object], dict[str, object]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    gates, failed, breadth = gate_support.evaluate_gates(
        support,
        _map_variants(design),
        _map_variants(validation_output),
        _map_variants(validation),
        _map_annual(annual),
    )
    rename = lambda value: value.replace("rsi14", "mfi14").replace("RSI14", "MFI14")
    renamed_gates = {rename(name): passed for name, passed in gates.items()}
    renamed_failed = [rename(name) for name in failed]
    renamed_breadth = {rename(name): item for name, item in breadth.items()}
    return renamed_gates, renamed_failed, renamed_breadth


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("primary") != {"mfi_complete_days": 14, "long_threshold": "50"}:
        raise ResearchReject("MANIFEST_REJECT:PRIMARY")
    if policy.get("rejection_only_neighbors") != [
        {"mfi_complete_days": 14, "long_threshold": "45"},
        {"mfi_complete_days": 14, "long_threshold": "55"},
    ]:
        raise ResearchReject("MANIFEST_REJECT:NEIGHBORS")
    if policy.get("variants") != 3:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")


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

    parser = load_module("frozen_mfi_h1_parser", PARSER_SOURCE)
    reference = load_module("frozen_mfi_economic_reference", REFERENCE_SOURCE)
    support = load_module("frozen_mfi_economic_support", SUPPORT_SOURCE)
    gate_support = load_module("frozen_mfi_gate_support", GATE_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    daily = support.build_daily_points(bars)
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise ResearchReject(f"DATA_REJECT:UTC_DAY_COUNT:{len(daily)}")

    design_output, design_raw = simulate_window(support, reference, bars, daily, DESIGN)
    validation_output, validation_raw = simulate_window(
        support, reference, bars, daily, VALIDATION
    )
    annual = {
        year: simulate_window(support, reference, bars, daily, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(
        support, gate_support, design_raw, validation_output, validation_raw, annual
    )
    _, primary_values = mfi_targets(daily, 14, 50)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_MONEY_FLOW_INDEX_14_MIDLINE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_DAILY_MONEY_FLOW_INDEX_14_MIDLINE_LONG_CASH_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_DAILY_MFI14_GT45_50_55_LONG_CASH_FAMILY_WITHOUT_TUNING"
        ),
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": data_sha,
            "hourly_rows": len(bars),
            "complete_utc_days": len(daily),
            "selection_cutoff": "2025-01-01T00:00:00",
            "first_complete_day_close": daily[0].close_time.isoformat(),
            "last_complete_day_close": daily[-1].close_time.isoformat(),
        },
        "source_bindings": {
            path.relative_to(REPO_ROOT).as_posix(): digest
            for path, digest in EXPECTED_SOURCE_HASHES.items()
        },
        "feature_diagnostic": {
            "definition": "100_TIMES_POSITIVE_TYPICAL_PRICE_TIMES_BASE_VOLUME_FLOW_DIVIDED_BY_POSITIVE_PLUS_NEGATIVE_FLOW",
            "complete_days": 14,
            "evaluation_count": len(primary_values),
            "zero_denominator_value": "0",
            "minimum": support.q(min(primary_values)),
            "maximum": support.q(max(primary_values)),
            "median": support.q(support.percentile(primary_values, D("0.5")) or ZERO),
        },
        "policy": {
            "primary": "MFI14_STRICTLY_GT_50_LONG_ELSE_CASH",
            "rejection_only_neighbors": [
                "MFI14_STRICTLY_GT_45_LONG_ELSE_CASH",
                "MFI14_STRICTLY_GT_55_LONG_ELSE_CASH",
            ],
            "execution": "NEXT_HOURLY_OPEN_AFTER_COMPLETE_UTC_DAY",
            "variants": 3,
        },
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset_primary": {
            year: value[0]["PRIMARY_MFI14_GT50"] for year, value in annual.items()
        },
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or permission to test another MFI tuple.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


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
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_gates": result["failed_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
