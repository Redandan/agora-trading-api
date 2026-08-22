#!/usr/bin/env python3
"""Identity-corrected V2 runner for the frozen BTC native-fee-pressure DRA screen."""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_bitcoin_fee_pressure_entry_admission_historical_v1 as frozen
import btc_dra_cftc_tff_entry_admission_historical_v1 as reused


AUTHORIZATION = reused.AUTHORIZATION
DOCUMENT_TYPE = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_MANIFEST_V2"
RESULT_TYPE = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_SCREEN_V2"
RUNNER_IDENTITY = "BTC_DRA_BITCOIN_FEE_PRESSURE_ENTRY_ADMISSION_RUNNER_V2"
FACTOR_IDENTITY = frozen.FACTOR_IDENTITY
EXPERIMENT_ID = "dra-bitcoin-fee-pressure-entry-admission-historical-v2"
PARENT_STRATEGY = reused.PARENT_STRATEGY
GATE_SET = reused.GATE_SET
SELECTION_CUTOFF = reused.SELECTION_CUTOFF
DESIGN = reused.DESIGN
VALIDATION = reused.VALIDATION


def validate_manifest(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise reused.ScreenReject("CONTRACT_REJECT", "manifest must be object")
    candidate = deepcopy(value)
    bindings = candidate.get("bindings")
    if not isinstance(bindings, dict):
        raise reused.ScreenReject("CONTRACT_REJECT", "bindings must be object")
    predecessor = bindings.pop("predecessor_invalid_run", None)
    reused._validate_binding(
        predecessor,
        ".research-state/experiments/dra-bitcoin-fee-pressure-entry-admission-historical-v1/artifacts/run1.json",
        "bindings.predecessor_invalid_run",
    )
    original_document_type = candidate.get("document_type")
    original_experiment_id = candidate.get("experiment_id")
    original_runner = deepcopy(bindings.get("runner"))
    original_schema = deepcopy(bindings.get("manifest_schema"))
    if original_document_type != DOCUMENT_TYPE or original_experiment_id != EXPERIMENT_ID:
        raise reused.ScreenReject("CONTRACT_REJECT", "V2 identity drift")
    reused._validate_binding(
        original_runner,
        "research/btc_dra_bitcoin_fee_pressure_entry_admission_historical_v2.py",
        "bindings.runner",
    )
    reused._validate_binding(
        original_schema,
        "research_pipeline/btc-dra-bitcoin-fee-pressure-entry-admission-manifest.v2.schema.json",
        "bindings.manifest_schema",
    )
    candidate["document_type"] = frozen.DOCUMENT_TYPE
    candidate["experiment_id"] = frozen.EXPERIMENT_ID
    candidate["schema_version"] = "1"
    bindings["runner"] = {
        "path": "research/btc_dra_bitcoin_fee_pressure_entry_admission_historical_v1.py",
        "sha256": "9c3b9ccf72bc30c1b1d4bc46d3c641543f6f2f1bdaebfa5b7f3be36af6a858b8",
    }
    bindings["manifest_schema"] = {
        "path": "research_pipeline/btc-dra-bitcoin-fee-pressure-entry-admission-manifest.v1.schema.json",
        "sha256": "428c6cbc7f7ee48bb134bed01fdcc50f5adbcf840bcce28944b5edb3c4c3f2d0",
    }
    frozen.validate_manifest(candidate)
    return value


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise reused.ScreenReject("CONTRACT_REJECT", "manifest must be strict UTF-8 JSON") from error
    if raw != reused.canonical_document_bytes(value):
        raise reused.ScreenReject("CONTRACT_REJECT", "manifest must use canonical JSON document bytes")
    return validate_manifest(value), raw


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise reused.ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = reused.verify_bindings(manifest)
    bars = reused.load_selection(input_path, manifest)
    fee_rows, source_evidence = frozen.load_fee_pressure(bindings)
    factor_points, exclusions = frozen.build_factor_points(fee_rows)
    baseline = reused.parent_baseline(bars)
    original_factor_identity = reused.FACTOR_IDENTITY
    original_runner_identity = reused.RUNNER_IDENTITY
    try:
        reused.FACTOR_IDENTITY = FACTOR_IDENTITY
        reused.RUNNER_IDENTITY = RUNNER_IDENTITY
        economics = reused.economic_evidence(bars, baseline, factor_points)
    finally:
        reused.FACTOR_IDENTITY = original_factor_identity
        reused.RUNNER_IDENTITY = original_runner_identity
    economic_checks = reused.economic_gates(economics, baseline)
    predictive = {
        "design": reused.predictive_evidence(reused.build_predictive_episodes(bars, factor_points, DESIGN)),
        "validation": reused.predictive_evidence(reused.build_predictive_episodes(bars, factor_points, VALIDATION)),
    }
    passed = all(economic_checks.values()) and all(all(window["gates"].values()) for window in predictive.values())
    result = {
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "bindings": bindings,
        "dataset": {"canonical_sha256": reused.base.data_hash(bars), "rows": len(bars), "selection_cutoff": SELECTION_CUTOFF.isoformat()},
        "document_type": RESULT_TYPE,
        "economic_evidence": economics,
        "economic_gates": economic_checks,
        "experiment_id": EXPERIMENT_ID,
        "factor_identity": FACTOR_IDENTITY,
        "factor_points": factor_points,
        "factor_point_exclusions": exclusions,
        "gate_set": GATE_SET,
        "manifest_sha256": reused.sha256_bytes(manifest_raw),
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "predictive_evidence": predictive,
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_BITCOIN_FEE_PRESSURE_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": reused.sha256_path(Path(__file__)),
        "schema_version": "2",
        "source_evidence": source_evidence,
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_BITCOIN_FEE_PRESSURE_FAMILY",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(reused.canonical_document_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_screen(args.manifest, args.input, args.output)
    except (reused.ScreenReject, reused.base.ResearchReject, ValueError) as error:
        print(json.dumps({"detail": getattr(error, "detail", str(error)), "status": getattr(error, "status", "DATA_REJECT")}, ensure_ascii=False))
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
