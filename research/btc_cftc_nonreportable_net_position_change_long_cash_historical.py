#!/usr/bin/env python3
"""Deterministic historical screen for frozen CFTC nonreportable positioning."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_cftc_dealer_net_position_change_long_cash_historical as shared


D = shared.D
ZERO = shared.ZERO
DESIGN = shared.DESIGN
VALIDATION = shared.VALIDATION
AVAILABILITY_LAG_DAYS = shared.AVAILABILITY_LAG_DAYS
ION_EXCLUSION_START = shared.ION_EXCLUSION_START
ION_EXCLUSION_END = shared.ION_EXCLUSION_END
REDUNDANCY_LIMIT = shared.REDUNDANCY_LIMIT

LEDGER_SOURCE = shared.LEDGER_SOURCE
REFERENCE_SOURCE = shared.REFERENCE_SOURCE
REUSED_CFTC_SOURCE = shared.REUSED_CFTC_SOURCE
SOURCE_FIELD_SOURCE = shared.SOURCE_FIELD_SOURCE
DECIMAL_PARSER_SOURCE = shared.DECIMAL_PARSER_SOURCE
SOURCE_CONTRACT = shared.SOURCE_CONTRACT
HISTORICAL_SOURCE_MANIFEST = shared.HISTORICAL_SOURCE_MANIFEST
SHARED_IMPLEMENTATION_SOURCE = (
    RESEARCH_ROOT / "btc_cftc_dealer_net_position_change_long_cash_historical.py"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-nonreportable-net-position-change-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-nonreportable-net-position-change-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-cftc-nonreportable-net-position-change-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BTC_SHA256 = shared.EXPECTED_BTC_SHA256
EXPECTED_BTC_ROWS = shared.EXPECTED_BTC_ROWS
EXPECTED_SOURCE_MANIFEST_SHA256 = shared.EXPECTED_SOURCE_MANIFEST_SHA256
EXPECTED_SHARED_IMPLEMENTATION_SHA256 = (
    "389efeb6583d7af32981704c649288f0a9395f4031f2c299931a56e582734a3e"
)
EXPECTED_PRIOR_SHA256 = (
    "4e23a478a8f3cc8c59c6a238f4ee3b64b16024f3808c3a9ce6517239e4196729"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "ffc20e150d2efb8ce48baa34502ac4a3b45a20bc86b8c3fbc830a80e5a3522c7"
)

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_RUNNER": "research/btc_cftc_nonreportable_net_position_change_long_cash_historical.py",
    "FROZEN_SHARED_CFTC_LONG_CASH_IMPLEMENTATION": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_CFTC_ARCHIVE_LOADER": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py",
    "FROZEN_CFTC_SOURCE_CONTRACT": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json",
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-cftc-nonreportable-net-position-change-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-cftc-nonreportable-net-position-change-long-cash-v1.hypothesis.json",
}
EXPECTED_NON_RUNNER_HASHES = {
    "FROZEN_SHARED_CFTC_LONG_CASH_IMPLEMENTATION": EXPECTED_SHARED_IMPLEMENTATION_SHA256,
    "FROZEN_LONG_CASH_LEDGER": shared.EXPECTED_LEDGER_SHA256,
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": shared.EXPECTED_REFERENCE_SHA256,
    "FROZEN_CFTC_ARCHIVE_LOADER": shared.EXPECTED_REUSED_CFTC_SHA256,
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": shared.EXPECTED_SOURCE_FIELD_SHA256,
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": shared.EXPECTED_DECIMAL_PARSER_SHA256,
    "FROZEN_CFTC_SOURCE_CONTRACT": shared.EXPECTED_SOURCE_CONTRACT_SHA256,
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": EXPECTED_SOURCE_MANIFEST_SHA256,
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": EXPECTED_PRIOR_SHA256,
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": EXPECTED_HYPOTHESIS_SHA256,
}

FIELDS = shared.cftc_reused.cftc_source.ORDERED_FIELDS
NONREPORTABLE_LONG_INDEX = FIELDS.index("Pct_of_OI_NonRept_Long_All")
NONREPORTABLE_SHORT_INDEX = FIELDS.index("Pct_of_OI_NonRept_Short_All")
DEALER_LONG_INDEX = FIELDS.index("Pct_of_OI_Dealer_Long_All")
DEALER_SHORT_INDEX = FIELDS.index("Pct_of_OI_Dealer_Short_All")
LEVERAGED_LONG_INDEX = FIELDS.index("Pct_of_OI_Lev_Money_Long_All")
LEVERAGED_SHORT_INDEX = FIELDS.index("Pct_of_OI_Lev_Money_Short_All")
ASSET_LONG_INDEX = FIELDS.index("Pct_of_OI_Asset_Mgr_Long_All")
ASSET_SHORT_INDEX = FIELDS.index("Pct_of_OI_Asset_Mgr_Short_All")

EXPECTED_PRE_ECONOMIC_GATES = (
    "btc_sha256_and_52608_rows_match",
    "cftc_archives_manifest_and_exact_rows_match",
    "weekly_tuesday_predecessor_ion_exclusion_and_day7_availability_pass",
    "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match",
    "design_nonreportable_delta_abs_spearman_to_dealer_delta_at_most_0_80",
    "design_nonreportable_delta_abs_spearman_to_leveraged_money_delta_at_most_0_80",
    "design_nonreportable_delta_abs_spearman_to_asset_manager_delta_at_most_0_80",
    "validation_nonreportable_delta_abs_spearman_to_dealer_delta_at_most_0_80",
    "validation_nonreportable_delta_abs_spearman_to_leveraged_money_delta_at_most_0_80",
    "validation_nonreportable_delta_abs_spearman_to_asset_manager_delta_at_most_0_80",
    *tuple(
        f"{window}_{gate}"
        for window in ("design", "validation")
        for gate in tuple(
            name.removeprefix("design_")
            for name in shared.EXPECTED_PRE_ECONOMIC_GATES[8:19]
        )
    ),
)


@dataclass(frozen=True)
class FactorPoint:
    report_date: date
    eligible_at: datetime
    dealer_delta: D
    leveraged_money_delta: D
    asset_manager_delta: D
    dealer_reference_delta: D

    @property
    def target_long(self) -> bool:
        return self.dealer_delta > ZERO


def build_factor_points(
    rows: dict[date, list[str]], *, cutoff: datetime = VALIDATION[1]
) -> tuple[list[FactorPoint], dict[str, int]]:
    points: list[FactorPoint] = []
    exclusions = {
        "non_tuesday": 0,
        "ion_delay": 0,
        "missing_exact_predecessor": 0,
        "eligible_at_or_after_cutoff": 0,
    }
    excluded = {
        day
        for day in rows
        if day.weekday() != 1 or ION_EXCLUSION_START <= day <= ION_EXCLUSION_END
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["non_tuesday"] += 1
            continue
        if ION_EXCLUSION_START <= day <= ION_EXCLUSION_END:
            exclusions["ion_delay"] += 1
            continue
        prior_day = day - timedelta(days=7)
        if prior_day not in rows or prior_day in excluded:
            exclusions["missing_exact_predecessor"] += 1
            continue
        eligible_at = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= cutoff:
            exclusions["eligible_at_or_after_cutoff"] += 1
            continue
        current = rows[day]
        prior = rows[prior_day]

        def delta(long_index: int, short_index: int) -> D:
            return shared._level(current, long_index, short_index) - shared._level(
                prior, long_index, short_index
            )

        points.append(
            FactorPoint(
                report_date=day,
                eligible_at=eligible_at,
                dealer_delta=delta(NONREPORTABLE_LONG_INDEX, NONREPORTABLE_SHORT_INDEX),
                leveraged_money_delta=delta(LEVERAGED_LONG_INDEX, LEVERAGED_SHORT_INDEX),
                asset_manager_delta=delta(ASSET_LONG_INDEX, ASSET_SHORT_INDEX),
                dealer_reference_delta=delta(DEALER_LONG_INDEX, DEALER_SHORT_INDEX),
            )
        )
    if not points:
        raise shared.ResearchReject("DATA_REJECT:NO_ELIGIBLE_CFTC_FACTOR_POINTS")
    return points, exclusions


def correlation_evidence(
    points: list[FactorPoint], window: tuple[datetime, datetime]
) -> dict[str, Any]:
    selected = [point for point in points if window[0] <= point.eligible_at < window[1]]
    target = [point.dealer_delta for point in selected]

    def one(values: list[D]) -> dict[str, Any]:
        correlation = shared.spearman_correlation(target, values)
        return {
            "spearman": shared.q(correlation),
            "absolute_spearman": shared.q(abs(correlation)),
            "pass": abs(correlation) <= REDUNDANCY_LIMIT,
        }

    return {
        "observation_count": len(selected),
        "absolute_spearman_limit": shared.q(REDUNDANCY_LIMIT),
        "nonreportable_to_dealer": one(
            [point.dealer_reference_delta for point in selected]
        ),
        "nonreportable_to_leveraged_money": one(
            [point.leveraged_money_delta for point in selected]
        ),
        "nonreportable_to_asset_manager": one(
            [point.asset_manager_delta for point in selected]
        ),
    }


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
) -> dict[str, Any]:
    evidence = shared.predictive_evidence(bars, points, window)
    for episode in evidence["episodes"]:
        episode["nonreportable_delta_pct_oi"] = episode.pop("dealer_delta_pct_oi")
    return evidence


def evaluate_pre_economic_gates(
    correlations: dict[str, dict[str, Any]],
    predictive: dict[str, dict[str, Any]],
) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "cftc_archives_manifest_and_exact_rows_match": True,
        "weekly_tuesday_predecessor_ion_exclusion_and_day7_availability_pass": True,
        "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match": True,
    }
    for label in ("design", "validation"):
        correlation = correlations[label]
        for peer in ("dealer", "leveraged_money", "asset_manager"):
            gates[
                f"{label}_nonreportable_delta_abs_spearman_to_{peer}_delta_at_most_0_80"
            ] = correlation[f"nonreportable_to_{peer}"]["pass"]
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_PRE_ECONOMIC_GATES:
        raise shared.ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def expected_policy() -> dict[str, Any]:
    return {
        "policy_id": "BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_LONG_CASH_V1",
        "factor_identity": "CFTC_TFF_NONREPORTABLE_NET_PCT_OI_WEEKLY_DELTA_CONTINUATION_LONG_CASH_V1",
        "current_fields": [
            "Pct_of_OI_NonRept_Long_All",
            "Pct_of_OI_NonRept_Short_All",
        ],
        "prior_fields": [
            "Pct_of_OI_NonRept_Long_All",
            "Pct_of_OI_NonRept_Short_All",
        ],
        "exact_predecessor_calendar_days": 7,
        "factor_formula": "CURRENT_NONREPORTABLE_NET_PCT_OI_MINUS_EXACT_PRIOR_WEEK_NONREPORTABLE_NET_PCT_OI",
        "long_condition": "FACTOR_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "FACTOR_LESS_THAN_OR_EQUAL_TO_ZERO",
        "availability": "REPORT_DATE_PLUS_7_CALENDAR_DAYS_AT_00_00_UTC",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "ion_exclusion": {
            "start_inclusive": "2023-01-31",
            "end_inclusive": "2023-03-14",
        },
        "position_sizing": "FULL_AVAILABLE_EQUITY_NO_LEVERAGE_NO_SHORT",
        "variants": 1,
    }


def expected_datasets() -> dict[str, Any]:
    return {
        "btc": {
            "path": ".research-state/java-parity/selection-2019-2024.tsv",
            "sha256": EXPECTED_BTC_SHA256,
            "hourly_rows": EXPECTED_BTC_ROWS,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "cftc": {
            "historical_source_manifest_path": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
            "historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256,
            "archive_years": [2019, 2020, 2021, 2022, 2023, 2024],
            "exact_contract_rows": 313,
        },
    }


def expected_windows() -> dict[str, Any]:
    return {
        "design": {
            "start": "2020-01-01T00:00:00",
            "end_exclusive": "2023-01-01T00:00:00",
        },
        "validation": {
            "start": "2023-01-01T00:00:00",
            "end_exclusive": "2025-01-01T00:00:00",
        },
        "predictive_horizon_hours": 168,
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get(
        "experiment_id"
    ) != EXPERIMENT_ID:
        raise shared.ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get(
        "oos_access"
    ) != "DENY":
        raise shared.ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != expected_datasets():
        raise shared.ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != expected_policy():
        raise shared.ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {
            "fee_rate_per_side": "0.0010",
            "adverse_slippage_rate_per_side": "0.0005",
        },
        "STRESS": {
            "fee_rate_per_side": "0.0020",
            "adverse_slippage_rate_per_side": "0.0010",
        },
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    if manifest.get("windows") != expected_windows():
        raise shared.ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_sets") != {
        "pre_economic": {
            "required": list(EXPECTED_PRE_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
        },
        "economic_if_pre_economic_passes": {
            "required": list(shared.EXPECTED_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_TUNING_OR_OOS",
        },
        "decision": "PRE_ECONOMIC_ALL_PASS_THEN_ECONOMIC_ALL_PASS_OR_CLOSE",
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise shared.ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    if {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise shared.ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:PATH")
        if role in EXPECTED_NON_RUNNER_HASHES and binding.get("sha256") != EXPECTED_NON_RUNNER_HASHES[role]:
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:FROZEN_SHA256")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or shared.sha256(path) != binding.get("sha256"):
            raise shared.ResearchReject(f"BINDING_REJECT:{role}:CURRENT_SHA256")


def build_output(btc_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    source_manifest, source_manifest_raw = shared.cftc_reused.load_manifest(
        HISTORICAL_SOURCE_MANIFEST
    )
    if hashlib.sha256(source_manifest_raw).hexdigest() != EXPECTED_SOURCE_MANIFEST_SHA256:
        raise shared.ResearchReject("SOURCE_REJECT:HISTORICAL_SOURCE_MANIFEST_SHA256")
    shared.cftc_reused.verify_bindings(source_manifest)
    bars = shared.cftc_reused.load_selection(btc_input, source_manifest)
    if len(bars) != EXPECTED_BTC_ROWS or shared.cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise shared.ResearchReject("DATA_REJECT:BTC_ROWS_OR_SHA256")
    rows, archive_evidence = shared.cftc_reused.load_historical_rows(source_manifest)
    points, exclusions = build_factor_points(rows)
    correlations = {
        "design": correlation_evidence(points, DESIGN),
        "validation": correlation_evidence(points, VALIDATION),
    }
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN),
        "validation": predictive_evidence(bars, points, VALIDATION),
    }
    pre_gates, failed_pre = evaluate_pre_economic_gates(correlations, predictive)
    base_result: dict[str, Any] = {
        "schema_version": "1",
        "document_type": "BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": shared.sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": shared.sha256(Path(__file__).resolve()),
        },
        "datasets": {
            "btc": {
                "path": btc_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": shared.sha256(btc_input),
                "hourly_rows": len(bars),
            },
            "cftc": {
                "historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256,
                "archive_evidence": archive_evidence,
                "exact_contract_rows": len(rows),
            },
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points": len(points),
            "positive_points": sum(point.dealer_delta > ZERO for point in points),
            "negative_points": sum(point.dealer_delta < ZERO for point in points),
            "zero_points": sum(point.dealer_delta == ZERO for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
            "exclusions": exclusions,
        },
        "nonredundancy": correlations,
        "predictive_evidence": predictive,
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage CME TFF nonreportable-positioning and pre-2025 BTC evidence only; a pass is not independent alpha, source-continuity proof, a runtime strategy or activation permission.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        duplicate = any("spearman" in name for name in failed_pre)
        base_result.update(
            {
                "status": "DUPLICATE_REJECT_CLOSE_BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_FAMILY"
                if duplicate
                else "NO_CANDIDATE_CLOSE_BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_FAMILY_PRE_ECONOMIC",
                "decision": "PERMANENTLY_CLOSE_EXACT_NONREPORTABLE_WEEKLY_DELTA_FAMILY_WITHOUT_ECONOMIC_ACCESS_DIRECTION_INVERSION_OR_TUNING",
                "candidate_created": False,
                "economic_evidence_accessed": False,
                "economic_evidence": {
                    metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_STOP"
                    for metric in (
                        "fees",
                        "adverse_slippage",
                        "realized_pnl",
                        "unrealized_pnl",
                        "total_pnl",
                        "maximum_drawdown",
                        "holding_age",
                        "terminal_inventory",
                        "breadth_and_path_risk",
                    )
                },
                "economic_gates": "NOT_ACCESSED",
                "failed_economic_gates": [],
            }
        )
        return base_result
    ledger = shared.load_module("nonreportable_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = shared.load_module(
        "nonreportable_frozen_long_cash_reference", REFERENCE_SOURCE
    )
    economic_output, economic_raw = shared.simulate_all(ledger, reference, bars, points)
    economic_gates, failed_economic, breadth = shared.evaluate_economic_gates(
        economic_output, economic_raw
    )
    passed = not failed_economic
    base_result.update(
        {
            "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_FAMILY",
            "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_NONREPORTABLE_WEEKLY_DELTA_FAMILY_WITHOUT_TUNING_OR_OOS",
            "candidate_created": passed,
            "economic_evidence_accessed": True,
            "economic_evidence": economic_output,
            "breadth_and_concentration": breadth,
            "economic_gates": economic_gates,
            "failed_economic_gates": failed_economic,
        }
    )
    return base_result


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
        raise shared.ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise shared.ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise shared.ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
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
                "sha256": shared.sha256(output_path),
                "failed_pre_economic_gates": result["failed_pre_economic_gates"],
                "failed_economic_gates": result["failed_economic_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
