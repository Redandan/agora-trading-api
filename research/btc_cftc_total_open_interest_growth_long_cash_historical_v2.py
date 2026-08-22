#!/usr/bin/env python3
"""V2 capability correction for the frozen CFTC open-interest experiment."""

from __future__ import annotations

import argparse
from decimal import Decimal
import json
from pathlib import Path
import re
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_cftc_total_open_interest_growth_long_cash_historical as v1


EXPERIMENT_ID = "btc-cftc-total-open-interest-growth-long-cash-historical-v2"
EXPECTED_MANIFEST_TYPE = (
    "BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_LONG_CASH_HISTORICAL_MANIFEST_V2"
)
V1_MANIFEST_PATH = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-total-open-interest-growth-long-cash-historical.v1.manifest.json"
)
V1_MANIFEST_SHA256 = (
    "8fc77b2da697115c76c6617c0020be41abcd1a11a1f5c00c818ada1bec264c13"
)
V1_RUNNER_SHA256 = (
    "c6047414a27472e1cef2a27694ca5609837e116443342a37d32d3e6e086e58fe"
)
ERRATUM_SHA256 = (
    "32bbc622225a65caddb86aa3cd8fc91460ecdfbdcb60db618de1cb8447922841"
)
SIGNED_INTEGER_PATTERN = re.compile(r"^-?(?:0|[1-9][0-9]*)$")


def parse_contract_count(value: Any) -> Decimal:
    if not isinstance(value, str):
        raise ValueError("contract count must be a string")
    trimmed = value.strip(" ")
    if SIGNED_INTEGER_PATTERN.fullmatch(trimmed) is None:
        raise ValueError("contract count violates the frozen signed-integer grammar")
    parsed = Decimal(trimmed)
    if not parsed.is_finite():
        raise ValueError("contract count must be finite")
    return parsed


v1.parse_decimal = parse_contract_count


def validate_v2_manifest(manifest: dict[str, Any], manifest_path: Path) -> None:
    expected = {
        "schema_version": "1",
        "document_type": EXPECTED_MANIFEST_TYPE,
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "oos_access": "DENY",
        "v1_scientific_manifest": {
            "path": "research_pipeline/examples/btc-cftc-total-open-interest-growth-long-cash-historical.v1.manifest.json",
            "sha256": V1_MANIFEST_SHA256,
        },
        "v1_scientific_runner": {
            "path": "research/btc_cftc_total_open_interest_growth_long_cash_historical.py",
            "sha256": V1_RUNNER_SHA256,
        },
        "v1_capability_erratum": {
            "path": "research_pipeline/examples/btc-cftc-total-open-interest-growth-long-cash-v1-source-field-parser-erratum.json",
            "sha256": ERRATUM_SHA256,
        },
        "v2_runner": {
            "path": "research/btc_cftc_total_open_interest_growth_long_cash_historical_v2.py",
            "sha256": v1.sha256(Path(__file__).resolve()),
        },
        "permitted_change": "STRICT_SIGNED_INTEGER_PARSER_FOR_OPEN_INTEREST_CONTRACT_COUNT_FIELDS_ONLY",
        "scientific_design_change": False,
        "gate_change": False,
        "direction_change": False,
        "threshold_change": False,
        "lag_or_window_change": False,
        "cost_or_accounting_change": False,
        "scope_note": "V2 capability correction only. No candidate, OOS, paid API, second timer, second writer, external backfill, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if manifest != expected:
        raise v1.ResearchReject("MANIFEST_REJECT:V2_CAPABILITY_AMENDMENT_DRIFT")
    if v1.sha256(V1_MANIFEST_PATH) != V1_MANIFEST_SHA256:
        raise v1.ResearchReject("BINDING_REJECT:V1_MANIFEST_SHA256")
    if v1.sha256(RESEARCH_ROOT / expected["v1_scientific_runner"]["path"].split("/", 1)[1]) != V1_RUNNER_SHA256:
        raise v1.ResearchReject("BINDING_REJECT:V1_RUNNER_SHA256")
    erratum_path = REPO_ROOT / expected["v1_capability_erratum"]["path"]
    if v1.sha256(erratum_path) != ERRATUM_SHA256:
        raise v1.ResearchReject("BINDING_REJECT:ERRATUM_SHA256")
    if manifest_path != manifest_path.resolve() or not manifest_path.is_relative_to(REPO_ROOT):
        raise v1.ResearchReject("PATH_REJECT:V2_MANIFEST")


def build_output(btc_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_v2_manifest(manifest, manifest_path)
    result = v1.build_output(btc_input, V1_MANIFEST_PATH)
    result["document_type"] = (
        "BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_LONG_CASH_HISTORICAL_RESULT_V2"
    )
    result["experiment_id"] = EXPERIMENT_ID
    result["runner"] = {
        "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
        "sha256": v1.sha256(Path(__file__).resolve()),
    }
    result["manifest"] = {
        "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": v1.sha256(manifest_path),
    }
    result["v1_scientific_manifest"] = {
        "path": V1_MANIFEST_PATH.relative_to(REPO_ROOT).as_posix(),
        "sha256": V1_MANIFEST_SHA256,
    }
    result["v1_capability_erratum"] = {
        "path": "research_pipeline/examples/btc-cftc-total-open-interest-growth-long-cash-v1-source-field-parser-erratum.json",
        "sha256": ERRATUM_SHA256,
        "v1_run_artifact_created": False,
        "scientific_design_change_in_v2": False,
    }
    result["claim_boundary"] = (
        "Historical present-vintage CME TFF total-open-interest and pre-2025 BTC "
        "evidence only. V2 changes only the contract-count parser documented by the "
        "sealed erratum. Total open interest is directionless participation evidence; "
        "a pass is not independent alpha, source-continuity proof, a runtime strategy "
        "or activation permission."
    )
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.manifest.resolve()]
    output_path = args.output.resolve()
    if not all(path.is_relative_to(REPO_ROOT) for path in inputs):
        raise v1.ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise v1.ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise v1.ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(*inputs)
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
                "sha256": v1.sha256(output_path),
                "failed_pre_economic_gates": result[
                    "failed_pre_economic_gates"
                ],
                "failed_economic_gates": result["failed_economic_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
