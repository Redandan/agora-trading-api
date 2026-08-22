#!/usr/bin/env python3
"""Deterministic historical screen for frozen CFTC top-four concentration."""

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
ONE = shared.ONE
HUNDRED = shared.HUNDRED
DESIGN = shared.DESIGN
VALIDATION = shared.VALIDATION
AVAILABILITY_LAG_DAYS = shared.AVAILABILITY_LAG_DAYS
ION_EXCLUSION_START = shared.ION_EXCLUSION_START
ION_EXCLUSION_END = shared.ION_EXCLUSION_END
HORIZON_HOURS = 672

LEDGER_SOURCE = shared.LEDGER_SOURCE
REFERENCE_SOURCE = shared.REFERENCE_SOURCE
HISTORICAL_SOURCE_MANIFEST = shared.HISTORICAL_SOURCE_MANIFEST
SHARED_IMPLEMENTATION_SOURCE = (
    RESEARCH_ROOT / "btc_cftc_dealer_net_position_change_long_cash_historical.py"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-top4-net-concentration-level-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-top4-net-concentration-level-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-cftc-top4-net-concentration-level-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BTC_SHA256 = shared.EXPECTED_BTC_SHA256
EXPECTED_BTC_ROWS = shared.EXPECTED_BTC_ROWS
EXPECTED_SOURCE_MANIFEST_SHA256 = shared.EXPECTED_SOURCE_MANIFEST_SHA256
EXPECTED_SHARED_IMPLEMENTATION_SHA256 = (
    "389efeb6583d7af32981704c649288f0a9395f4031f2c299931a56e582734a3e"
)
EXPECTED_PRIOR_SHA256 = (
    "a25fc3e8dd8eb968694573e737744b42d79d8443022241f5d1d8b7ca5e458b21"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "800f63704dc713a4058b9dd278aa8eeaa52b410791d9d46c996762ca92bb8508"
)

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_RUNNER": "research/btc_cftc_top4_net_concentration_level_long_cash_historical.py",
    "FROZEN_SHARED_CFTC_LONG_CASH_IMPLEMENTATION": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_CFTC_ARCHIVE_LOADER": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py",
    "FROZEN_CFTC_SOURCE_CONTRACT": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json",
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-cftc-top4-net-concentration-level-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-cftc-top4-net-concentration-level-long-cash-v1.hypothesis.json",
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
NET4_LONG_INDEX = FIELDS.index("Conc_Net_LE_4_TDR_Long_All")
NET4_SHORT_INDEX = FIELDS.index("Conc_Net_LE_4_TDR_Short_All")

PREDICTIVE_GATE_NAMES = (
    "minimum_required_nonzero_evaluable_transitions",
    "minimum_required_positive_factor_transitions",
    "minimum_required_nonpositive_factor_transitions",
    "chronological_quartile_breadth",
    "anchor_month_breadth",
    "maximum_anchor_month_share",
    "median_signed_response_strictly_positive",
    "positive_factor_median_raw_return_strictly_positive",
    "nonpositive_factor_median_raw_return_strictly_negative",
    "one_sided_sign_test_p_value_at_most_0_10",
    "top_absolute_signed_response_contribution_at_most_20pct",
)
EXPECTED_PRE_ECONOMIC_GATES = (
    "btc_sha256_and_52608_rows_match",
    "cftc_archives_manifest_and_exact_rows_match",
    "weekly_tuesday_ion_exclusion_and_day7_availability_pass",
    "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match",
    *(f"design_{name}" for name in PREDICTIVE_GATE_NAMES),
    *(f"validation_{name}" for name in PREDICTIVE_GATE_NAMES),
)


@dataclass(frozen=True)
class FactorPoint:
    report_date: date
    eligible_at: datetime
    dealer_delta: D

    @property
    def target_long(self) -> bool:
        return self.dealer_delta > ZERO


def concentration_level(row: list[str]) -> D | None:
    parser = shared.cftc_reused.factor_evaluator.parse_factor_decimal
    long_value = parser(row[NET4_LONG_INDEX])
    short_value = parser(row[NET4_SHORT_INDEX])
    if long_value < ZERO or short_value < ZERO:
        raise shared.ResearchReject("DATA_REJECT:NEGATIVE_CONCENTRATION")
    denominator = long_value + short_value
    if denominator == ZERO:
        return None
    return (long_value - short_value) / denominator


def build_factor_points(
    rows: dict[date, list[str]], *, cutoff: datetime = VALIDATION[1]
) -> tuple[list[FactorPoint], dict[str, int]]:
    points: list[FactorPoint] = []
    exclusions = {
        "non_tuesday": 0,
        "ion_delay": 0,
        "zero_denominator": 0,
        "eligible_at_or_after_cutoff": 0,
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["non_tuesday"] += 1
            continue
        if ION_EXCLUSION_START <= day <= ION_EXCLUSION_END:
            exclusions["ion_delay"] += 1
            continue
        eligible_at = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time()
        )
        if eligible_at >= cutoff:
            exclusions["eligible_at_or_after_cutoff"] += 1
            continue
        factor = concentration_level(rows[day])
        if factor is None:
            exclusions["zero_denominator"] += 1
            continue
        points.append(FactorPoint(day, eligible_at, factor))
    if not points:
        raise shared.ResearchReject("DATA_REJECT:NO_ELIGIBLE_CFTC_FACTOR_POINTS")
    return points, exclusions


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
    *,
    label: str,
) -> dict[str, Any]:
    bars_by_open = {bar.open_time: bar for bar in bars}
    selected = [
        point
        for point in points
        if window[0] <= point.eligible_at < window[1] and point.dealer_delta != ZERO
    ]
    episodes: list[dict[str, Any]] = []
    last_terminal: datetime | None = None
    overlap_exclusions = 0
    missing_path_exclusions = 0
    for point in selected:
        anchor_at = point.eligible_at
        terminal_at = anchor_at + timedelta(hours=HORIZON_HOURS)
        if terminal_at > window[1]:
            missing_path_exclusions += 1
            continue
        if last_terminal is not None and anchor_at < last_terminal:
            overlap_exclusions += 1
            continue
        anchor = bars_by_open.get(anchor_at)
        terminal = bars_by_open.get(terminal_at)
        path = [
            bars_by_open.get(anchor_at + timedelta(hours=offset))
            for offset in range(HORIZON_HOURS)
        ]
        if anchor is None or terminal is None or any(bar is None for bar in path):
            missing_path_exclusions += 1
            continue
        sign = ONE if point.dealer_delta > ZERO else -ONE
        raw_return = terminal.open / anchor.open - ONE
        signed_response = sign * raw_return
        adverse = max(
            (
                max(ZERO, -(sign * (bar.close / anchor.open - ONE)))
                for bar in path
                if bar is not None
            ),
            default=ZERO,
        )
        episodes.append(
            {
                "report_date": point.report_date.isoformat(),
                "eligible_at": anchor_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "top4_net_concentration_level": shared.q(point.dealer_delta),
                "factor_sign": 1 if sign > ZERO else -1,
                "raw_return_672h": shared.q(raw_return),
                "signed_response_672h": shared.q(signed_response),
                "sign_adjusted_mae_672h": shared.q(adverse),
            }
        )
        last_terminal = terminal_at
    signed = [D(item["signed_response_672h"]) for item in episodes]
    positive = [
        D(item["raw_return_672h"])
        for item in episodes
        if item["factor_sign"] > 0
    ]
    nonpositive = [
        D(item["raw_return_672h"])
        for item in episodes
        if item["factor_sign"] < 0
    ]
    successes = sum(value > ZERO for value in signed)
    failures = sum(value < ZERO for value in signed)
    p_value = shared._sign_test(successes, failures)
    absolute = [abs(value) for value in signed]
    absolute_total = sum(absolute, ZERO)
    top_absolute_share = (
        HUNDRED if absolute_total == ZERO else max(absolute) / absolute_total * HUNDRED
    )
    month_counts: dict[str, int] = {}
    for item in episodes:
        month = item["eligible_at"][:7]
        month_counts[month] = month_counts.get(month, 0) + 1
    maximum_month_share = (
        HUNDRED
        if not episodes
        else D(max(month_counts.values())) / D(len(episodes)) * HUNDRED
    )
    quartiles = [0, 0, 0, 0]
    for index in range(len(episodes)):
        quartiles[min(3, index * 4 // len(episodes))] += 1
    minimum_total = 30 if label == "design" else 20
    minimum_sign = 8 if label == "design" else 6
    minimum_quartile = 5 if label == "design" else 3
    minimum_months = 12 if label == "design" else 8
    maximum_month_pct = D("10") if label == "design" else D("15")
    median_signed = shared.percentile(signed, D("0.5"))
    positive_median = shared.percentile(positive, D("0.5"))
    nonpositive_median = shared.percentile(nonpositive, D("0.5"))
    gates = {
        "minimum_required_nonzero_evaluable_transitions": len(episodes) >= minimum_total,
        "minimum_required_positive_factor_transitions": len(positive) >= minimum_sign,
        "minimum_required_nonpositive_factor_transitions": len(nonpositive) >= minimum_sign,
        "chronological_quartile_breadth": all(
            count >= minimum_quartile for count in quartiles
        ),
        "anchor_month_breadth": len(month_counts) >= minimum_months,
        "maximum_anchor_month_share": maximum_month_share <= maximum_month_pct,
        "median_signed_response_strictly_positive": median_signed is not None
        and median_signed > ZERO,
        "positive_factor_median_raw_return_strictly_positive": positive_median
        is not None
        and positive_median > ZERO,
        "nonpositive_factor_median_raw_return_strictly_negative": nonpositive_median
        is not None
        and nonpositive_median < ZERO,
        "one_sided_sign_test_p_value_at_most_0_10": p_value is not None
        and p_value <= D("0.10"),
        "top_absolute_signed_response_contribution_at_most_20pct": top_absolute_share
        <= D("20"),
    }
    if tuple(gates) != PREDICTIVE_GATE_NAMES:
        raise shared.ResearchReject("MANIFEST_REJECT:PREDICTIVE_GATE_DRIFT")
    return {
        "episodes": episodes,
        "exclusions": {
            "overlapping_window": overlap_exclusions,
            "missing_or_out_of_window_path": missing_path_exclusions,
        },
        "thresholds": {
            "minimum_nonzero_evaluable_transitions": minimum_total,
            "minimum_each_factor_sign": minimum_sign,
            "minimum_each_chronological_quartile": minimum_quartile,
            "minimum_anchor_months": minimum_months,
            "maximum_anchor_month_share_pct": shared.q(maximum_month_pct),
        },
        "statistics": {
            "episode_count": len(episodes),
            "positive_factor_count": len(positive),
            "nonpositive_factor_count": len(nonpositive),
            "quartile_counts": quartiles,
            "anchor_month_count": len(month_counts),
            "maximum_anchor_month_share_pct": shared.q(maximum_month_share),
            "median_signed_response_672h": shared.nullable(median_signed),
            "positive_factor_median_raw_return_672h": shared.nullable(positive_median),
            "nonpositive_factor_median_raw_return_672h": shared.nullable(nonpositive_median),
            "sign_test_successes": successes,
            "sign_test_failures": failures,
            "one_sided_sign_test_p_value": shared.nullable(p_value),
            "top_absolute_signed_response_contribution_pct": shared.q(top_absolute_share),
            "median_absolute_return_672h": shared.nullable(
                shared.percentile(absolute, D("0.5"))
            ),
            "p90_absolute_return_672h": shared.nullable(
                shared.percentile(absolute, D("0.9"))
            ),
            "median_sign_adjusted_mae_672h": shared.nullable(
                shared.percentile(
                    [D(item["sign_adjusted_mae_672h"]) for item in episodes],
                    D("0.5"),
                )
            ),
        },
        "gates": gates,
    }


def evaluate_pre_economic_gates(
    predictive: dict[str, dict[str, Any]],
) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "cftc_archives_manifest_and_exact_rows_match": True,
        "weekly_tuesday_ion_exclusion_and_day7_availability_pass": True,
        "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_PRE_ECONOMIC_GATES:
        raise shared.ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def expected_policy() -> dict[str, Any]:
    return {
        "policy_id": "BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_LONG_CASH_V1",
        "factor_identity": "CFTC_TFF_TOP4_NET_CONCENTRATION_NORMALIZED_LEVEL_CONTINUATION_LONG_CASH_V1",
        "long_field": "Conc_Net_LE_4_TDR_Long_All",
        "short_field": "Conc_Net_LE_4_TDR_Short_All",
        "factor_formula": "(CURRENT_TOP4_NET_LONG_CONCENTRATION_MINUS_CURRENT_TOP4_NET_SHORT_CONCENTRATION)_DIVIDED_BY_(CURRENT_TOP4_NET_LONG_CONCENTRATION_PLUS_CURRENT_TOP4_NET_SHORT_CONCENTRATION)",
        "zero_denominator_rule": "UNEVALUABLE_NO_IMPUTATION",
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


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get(
        "experiment_id"
    ) != EXPERIMENT_ID:
        raise shared.ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get(
        "oos_access"
    ) != "DENY":
        raise shared.ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("datasets") != {
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
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != expected_policy():
        raise shared.ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
        "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
    }:
        raise shared.ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    if manifest.get("windows") != {
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "predictive_horizon_hours": HORIZON_HOURS,
        "predictive_overlap_rule": "KEEP_FIRST_THEN_SKIP_ANCHORS_BEFORE_672H_TERMINAL",
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
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
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(bars, points, VALIDATION, label="validation"),
    }
    pre_gates, failed_pre = evaluate_pre_economic_gates(predictive)
    base_result: dict[str, Any] = {
        "schema_version": "1",
        "document_type": "BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_input.relative_to(REPO_ROOT).as_posix(), "sha256": shared.sha256(btc_input), "hourly_rows": len(bars)},
            "cftc": {"historical_source_manifest_sha256": EXPECTED_SOURCE_MANIFEST_SHA256, "archive_evidence": archive_evidence, "exact_contract_rows": len(rows)},
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points": len(points),
            "positive_points": sum(point.dealer_delta > ZERO for point in points),
            "nonpositive_points": sum(point.dealer_delta <= ZERO for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
            "exclusions": exclusions,
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage CME TFF top-four concentration and pre-2025 BTC evidence only; the external paper excludes Bitcoin and a pass is not independent alpha, source-continuity proof, a runtime strategy or activation permission.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        base_result.update(
            {
                "status": "NO_CANDIDATE_CLOSE_BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_FAMILY_PRE_ECONOMIC",
                "decision": "PERMANENTLY_CLOSE_EXACT_TOP4_NET_CONCENTRATION_LEVEL_FAMILY_WITHOUT_ECONOMIC_ACCESS_DIRECTION_INVERSION_OR_TUNING",
                "candidate_created": False,
                "economic_evidence_accessed": False,
                "economic_evidence": {
                    metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_STOP"
                    for metric in ("fees", "adverse_slippage", "realized_pnl", "unrealized_pnl", "total_pnl", "maximum_drawdown", "holding_age", "terminal_inventory", "breadth_and_path_risk")
                },
                "economic_gates": "NOT_ACCESSED",
                "failed_economic_gates": [],
            }
        )
        return base_result
    ledger = shared.load_module("top4_concentration_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = shared.load_module("top4_concentration_frozen_long_cash_reference", REFERENCE_SOURCE)
    economic_output, economic_raw = shared.simulate_all(ledger, reference, bars, points)
    economic_gates, failed_economic, breadth = shared.evaluate_economic_gates(economic_output, economic_raw)
    passed = not failed_economic
    base_result.update(
        {
            "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_FAMILY",
            "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_TOP4_NET_CONCENTRATION_LEVEL_FAMILY_WITHOUT_TUNING_OR_OOS",
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
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": shared.sha256(output_path),
        "failed_pre_economic_gates": result["failed_pre_economic_gates"],
        "failed_economic_gates": result["failed_economic_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
