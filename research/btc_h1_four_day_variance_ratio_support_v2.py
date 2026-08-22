#!/usr/bin/env python3
"""Import-order-independent V2 support probe for the frozen variance-ratio state."""

from __future__ import annotations

import argparse
from decimal import localcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_PROBE = REPO_ROOT / "research/btc_h1_four_day_variance_ratio_support.py"
V2_SPEC = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome.v2.spec.json"
INVALIDATION = REPO_ROOT / "research_pipeline/examples/btc-h1-four-day-variance-ratio-positive-persistence-preoutcome-v1-invalidation.json"
EXPECTED_V1_SPEC_SHA256 = "bc0f2d39d8239ec828b2406aa92648c4982f3a4434e90df84d17fc16c337f781"
EXPECTED_V1_SUPPORT_SHA256 = "520c5f87b9187f535982d140b075e7e2a12f0c65fc543b3a8dfdfc2f11cad6de"
EXPECTED_PRECISION_50_LATTICE_SHA256 = "067fa12477292207e843fe31f40c2a4dd10b08a7d860c8f62a358ec4becbffc8"


class SupportReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SupportReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


base = load_module("variance_ratio_support_v1_frozen_base", BASE_PROBE)
BASE_CALCULATE_STATE = base.calculate_state


def calculate_state(window: list[Any]) -> tuple[Any, bool]:
    with localcontext() as context:
        context.prec = 50
        return BASE_CALCULATE_STATE(window)


def build_feature_states(daily: list[Any], expected_evaluations: int = base.EXPECTED_TOTAL_EVALUATIONS) -> list[Any]:
    original = base.calculate_state
    base.calculate_state = calculate_state
    try:
        return base.build_feature_states(daily, expected_evaluations)
    finally:
        base.calculate_state = original


def validate_v2_spec() -> dict[str, Any]:
    document = json.loads(V2_SPEC.read_text(encoding="utf-8"))
    if document.get("document_type") != "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_PREOUTCOME_SPEC_V2":
        raise SupportReject("SPEC_REJECT:DOCUMENT_TYPE")
    if document.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise SupportReject("SPEC_REJECT:AUTHORIZATION")
    supersedes = document.get("supersedes", {})
    if supersedes.get("v1_spec_sha256") != EXPECTED_V1_SPEC_SHA256 or supersedes.get("v1_support_sha256") != EXPECTED_V1_SUPPORT_SHA256:
        raise SupportReject("SPEC_REJECT:SUPERSESSION")
    if document.get("support_gates") != {
        "inherit_exactly_from_v1_spec_sha256": EXPECTED_V1_SPEC_SHA256,
        "no_gate_change": True,
        "no_threshold_change": True,
        "no_window_change": True,
    }:
        raise SupportReject("SPEC_REJECT:GATE_INHERITANCE")
    if not INVALIDATION.is_file():
        raise SupportReject("SPEC_REJECT:INVALIDATION_MISSING")
    return document


def build_output() -> dict[str, Any]:
    validate_v2_spec()
    original = base.calculate_state
    base.calculate_state = calculate_state
    try:
        result = base.build_output()
    finally:
        base.calculate_state = original
    actual_lattice = result["feature_lattice"]["sha256"]
    if actual_lattice != EXPECTED_PRECISION_50_LATTICE_SHA256:
        raise SupportReject(f"FEATURE_REJECT:V2_LATTICE:{actual_lattice}")
    result["schema_version"] = "2"
    result["document_type"] = "BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_PREOUTCOME_RESULT_V2"
    result["status"] = "PASS_PREOUTCOME_SUPPORT_V2_ALLOW_EXISTING_SINGLE_HYPOTHESIS" if result["support_pass"] else "DATA_REJECT_CLOSE_PREOUTCOME_SUPPORT_V2"
    result["supersedes"] = {
        "invalid_v1_support_path": ".research-state/experiments/btc-h1-four-day-variance-ratio-positive-persistence-long-cash-historical-v1/inputs/feature-support.json",
        "invalid_v1_support_sha256": EXPECTED_V1_SUPPORT_SHA256,
        "invalidation_path": INVALIDATION.relative_to(REPO_ROOT).as_posix(),
        "invalidation_sha256": sha256(INVALIDATION),
        "reason": "PROCESS_GLOBAL_DECIMAL_PRECISION_DRIFT_BEFORE_STRATEGY_ECONOMICS",
    }
    result["source_bindings"]["preoutcome_spec"] = {
        "path": V2_SPEC.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(V2_SPEC),
    }
    result["source_bindings"]["support_probe"] = {
        "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(Path(__file__).resolve()),
    }
    result["arithmetic"] = "LOCAL_PYTHON_DECIMAL_CONTEXT_PRECISION_50_PER_VARIANCE_RATIO_CALCULATION"
    result["scope_note"] = "V2 corrected only import-order Decimal context drift found before strategy economics. No forward return, PnL, drawdown, Calmar, holding, inventory, candidate, OOS, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(REPO_ROOT / ".research-state"):
        raise SupportReject(f"OUTPUT_PATH_REJECT:{output}")
    if output.exists():
        raise SupportReject(f"SEALED_OUTPUT_EXISTS:{output}")
    result = build_output()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output), "failed_support_gates": result["failed_support_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
