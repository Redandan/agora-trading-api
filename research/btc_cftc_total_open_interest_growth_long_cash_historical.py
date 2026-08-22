#!/usr/bin/env python3
"""Deterministic historical screen for frozen CFTC total-open-interest growth."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import sys
from typing import Any


D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_cftc_dealer_net_position_change_long_cash_historical as shared


cftc_reused = shared.cftc_reused
LEDGER_SOURCE = RESEARCH_ROOT / "btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = RESEARCH_ROOT / "btc_monthly_12m_time_series_momentum_historical.py"
SHARED_ECONOMIC_SOURCE = (
    RESEARCH_ROOT / "btc_cftc_dealer_net_position_change_long_cash_historical.py"
)
REUSED_CFTC_SOURCE = (
    RESEARCH_ROOT / "btc_dra_cftc_tff_entry_admission_historical_v1.py"
)
SOURCE_FIELD_SOURCE = (
    REPO_ROOT / "research_pipeline" / "cftc_cme_bitcoin_tff_source.py"
)
DECIMAL_PARSER_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py"
)
SOURCE_CONTRACT = (
    REPO_ROOT / "research_pipeline" / "cftc-cme-bitcoin-tff-source-contract.v2.json"
)
HISTORICAL_SOURCE_MANIFEST = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "cftc-tff-dra-entry-admission-historical.v1.manifest.json"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-total-open-interest-growth-long-cash-primary-prior.v1.json"
)
HYPOTHESIS_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-cftc-total-open-interest-growth-long-cash-v1.hypothesis.json"
)

EXPERIMENT_ID = "btc-cftc-total-open-interest-growth-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_LONG_CASH_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BTC_SHA256 = (
    "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
)
EXPECTED_BTC_ROWS = 52_608
EXPECTED_SOURCE_MANIFEST_SHA256 = (
    "f56ff773e14b2ed54388d2747905e8fbc7d585b54b36126958ecbc7cd238111a"
)
EXPECTED_LEDGER_SHA256 = (
    "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
)
EXPECTED_REFERENCE_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_SHARED_ECONOMIC_SHA256 = (
    "389efeb6583d7af32981704c649288f0a9395f4031f2c299931a56e582734a3e"
)
EXPECTED_REUSED_CFTC_SHA256 = (
    "fdde75be9efe7b9dfe065c46429de75594da88c4d2360b0c7891ee467649635b"
)
EXPECTED_SOURCE_FIELD_SHA256 = (
    "b56f9ceecb6f1ed1cf10d77756c109c1cd9ad25540ede6c70d555867aaed9eb0"
)
EXPECTED_DECIMAL_PARSER_SHA256 = (
    "a0a9f3c9d1b190e1cac21dfd9255b7d19b7f0c88afa48c482744c0d5da78eb70"
)
EXPECTED_SOURCE_CONTRACT_SHA256 = (
    "726d7ebff05d1c9fb5df9399996ff9817b28025d9d696f207191ec8c62e7dde5"
)
EXPECTED_PRIOR_SHA256 = (
    "95f1e7f2741b282f21738448872e69ae9f55c05c3527434e7e02ca22fb1db191"
)
EXPECTED_HYPOTHESIS_SHA256 = (
    "faaaef97c1b5fb98bad83a1d4db0240f610415b2b0bb5fa71cf714e399ad7dcf"
)

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_RUNNER": "research/btc_cftc_total_open_interest_growth_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_SHARED_ECONOMIC_GATE_IMPLEMENTATION": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_CFTC_ARCHIVE_LOADER": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py",
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": "research_pipeline/cftc_cme_bitcoin_tff_source.py",
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py",
    "FROZEN_CFTC_SOURCE_CONTRACT": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json",
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": "research_pipeline/examples/cftc-tff-dra-entry-admission-historical.v1.manifest.json",
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-cftc-total-open-interest-growth-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-cftc-total-open-interest-growth-long-cash-v1.hypothesis.json",
}
EXPECTED_NON_RUNNER_HASHES = {
    "FROZEN_LONG_CASH_LEDGER": EXPECTED_LEDGER_SHA256,
    "FROZEN_LONG_CASH_ACCOUNTING_AND_PASSIVE_REFERENCE": EXPECTED_REFERENCE_SHA256,
    "FROZEN_SHARED_ECONOMIC_GATE_IMPLEMENTATION": EXPECTED_SHARED_ECONOMIC_SHA256,
    "FROZEN_CFTC_ARCHIVE_LOADER": EXPECTED_REUSED_CFTC_SHA256,
    "FROZEN_CFTC_ORDERED_FIELD_DEFINITION": EXPECTED_SOURCE_FIELD_SHA256,
    "FROZEN_CFTC_EXACT_DECIMAL_PARSER": EXPECTED_DECIMAL_PARSER_SHA256,
    "FROZEN_CFTC_SOURCE_CONTRACT": EXPECTED_SOURCE_CONTRACT_SHA256,
    "FROZEN_HISTORICAL_ARCHIVE_MANIFEST": EXPECTED_SOURCE_MANIFEST_SHA256,
    "SEALED_PRIMARY_ADVERSARIAL_PRIOR": EXPECTED_PRIOR_SHA256,
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": EXPECTED_HYPOTHESIS_SHA256,
}

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
AVAILABILITY_LAG_DAYS = 7
HORIZON_HOURS = 168
ION_EXCLUSION_START = date(2023, 1, 31)
ION_EXCLUSION_END = date(2023, 3, 14)
OPEN_INTEREST_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index(
    "Open_Interest_All"
)
CHANGE_OPEN_INTEREST_INDEX = cftc_reused.cftc_source.ORDERED_FIELDS.index(
    "Change_in_Open_Interest_All"
)

EXPECTED_PRE_ECONOMIC_GATES = (
    "btc_sha256_and_52608_rows_match",
    "cftc_archives_manifest_and_exact_rows_match",
    "weekly_tuesday_predecessor_ion_exclusion_day7_availability_and_change_identity_pass",
    "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match",
    "design_minimum_120_evaluable_transitions",
    "design_minimum_40_positive_growth_transitions",
    "design_minimum_20_nonpositive_growth_transitions",
    "design_positive_growth_median_next_168h_return_strictly_positive",
    "design_positive_minus_nonpositive_growth_median_next_168h_return_strictly_positive",
    "design_one_sided_mann_whitney_p_value_at_most_0_10",
    "design_top_absolute_signed_response_contribution_at_most_20pct",
    "validation_minimum_75_evaluable_transitions",
    "validation_minimum_20_positive_growth_transitions",
    "validation_minimum_10_nonpositive_growth_transitions",
    "validation_positive_growth_median_next_168h_return_strictly_positive",
    "validation_positive_minus_nonpositive_growth_median_next_168h_return_strictly_positive",
    "validation_one_sided_mann_whitney_p_value_at_most_0_10",
    "validation_top_absolute_signed_response_contribution_at_most_20pct",
)
EXPECTED_ECONOMIC_GATES = shared.EXPECTED_ECONOMIC_GATES


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class FactorPoint:
    report_date: date
    eligible_at: datetime
    open_interest_growth: D
    current_open_interest: D
    prior_open_interest: D

    @property
    def target_long(self) -> bool:
        return self.open_interest_growth > ZERO


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return shared.q(value)


def nullable(value: D | None) -> str | None:
    return None if value is None else q(value)


def parse_decimal(raw: str) -> D:
    return cftc_reused.factor_evaluator.parse_factor_decimal(raw)


def build_factor_points(
    rows: dict[date, list[str]],
    *,
    cutoff: datetime = VALIDATION[1],
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
        current = parse_decimal(rows[day][OPEN_INTEREST_INDEX])
        prior = parse_decimal(rows[prior_day][OPEN_INTEREST_INDEX])
        reported_change = parse_decimal(rows[day][CHANGE_OPEN_INTEREST_INDEX])
        if prior <= ZERO:
            raise ResearchReject("DATA_REJECT:NONPOSITIVE_OPEN_INTEREST_DENOMINATOR")
        if current - prior != reported_change:
            raise ResearchReject("DATA_REJECT:OPEN_INTEREST_CHANGE_IDENTITY_MISMATCH")
        points.append(
            FactorPoint(day, eligible_at, current / prior - ONE, current, prior)
        )
    if not points:
        raise ResearchReject("DATA_REJECT:NO_ELIGIBLE_CFTC_FACTOR_POINTS")
    return points, exclusions


def _midranks(values: list[D]) -> list[D]:
    indexed = sorted(enumerate(values), key=lambda item: item[1])
    ranks = [ZERO] * len(values)
    cursor = 0
    while cursor < len(indexed):
        end = cursor + 1
        while end < len(indexed) and indexed[end][1] == indexed[cursor][1]:
            end += 1
        rank = (D(cursor + 1) + D(end)) / D("2")
        for offset in range(cursor, end):
            ranks[indexed[offset][0]] = rank
        cursor = end
    return ranks


def one_sided_mann_whitney_greater(
    positive: list[D], nonpositive: list[D]
) -> tuple[D | None, D | None]:
    if not positive or not nonpositive:
        return None, None
    combined = positive + nonpositive
    ranks = _midranks(combined)
    n1 = D(len(positive))
    n2 = D(len(nonpositive))
    u1 = sum(ranks[: len(positive)], ZERO) - n1 * (n1 + ONE) / D("2")
    total = len(combined)
    counts: dict[D, int] = {}
    for value in combined:
        counts[value] = counts.get(value, 0) + 1
    tie_sum = sum(D(count**3 - count) for count in counts.values())
    variance = n1 * n2 / D("12") * (
        D(total + 1) - tie_sum / D(total * (total - 1))
    )
    if variance <= ZERO:
        return u1, None
    mean = n1 * n2 / D("2")
    z = (float(u1 - mean) - 0.5) / math.sqrt(float(variance))
    p_value = D(format(D("0.5") * D(str(math.erfc(z / math.sqrt(2)))), ".12f"))
    return u1, p_value


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
    *,
    label: str,
) -> dict[str, Any]:
    bars_by_open = {bar.open_time: bar for bar in bars}
    selected = [
        point for point in points if window[0] <= point.eligible_at < window[1]
    ]
    episodes: list[dict[str, Any]] = []
    missing_path_exclusions = 0
    for point in selected:
        terminal_at = point.eligible_at + timedelta(hours=HORIZON_HOURS)
        if terminal_at > window[1]:
            missing_path_exclusions += 1
            continue
        anchor = bars_by_open.get(point.eligible_at)
        terminal = bars_by_open.get(terminal_at)
        if anchor is None or terminal is None:
            missing_path_exclusions += 1
            continue
        raw_return = terminal.open / anchor.open - ONE
        positive_growth = point.open_interest_growth > ZERO
        signed_response = raw_return if positive_growth else -raw_return
        episodes.append(
            {
                "report_date": point.report_date.isoformat(),
                "eligible_at": point.eligible_at.isoformat(),
                "terminal_at": terminal_at.isoformat(),
                "open_interest_growth": q(point.open_interest_growth),
                "positive_growth": positive_growth,
                "raw_return_168h": q(raw_return),
                "signed_response_168h": q(signed_response),
            }
        )
    positive = [
        D(item["raw_return_168h"])
        for item in episodes
        if item["positive_growth"]
    ]
    nonpositive = [
        D(item["raw_return_168h"])
        for item in episodes
        if not item["positive_growth"]
    ]
    signed = [D(item["signed_response_168h"]) for item in episodes]
    positive_median = shared.percentile(positive, D("0.5"))
    nonpositive_median = shared.percentile(nonpositive, D("0.5"))
    median_difference = (
        None
        if positive_median is None or nonpositive_median is None
        else positive_median - nonpositive_median
    )
    mann_whitney_u, p_value = one_sided_mann_whitney_greater(
        positive, nonpositive
    )
    absolute = [abs(value) for value in signed]
    absolute_total = sum(absolute, ZERO)
    top_absolute_share = (
        HUNDRED
        if not absolute or absolute_total == ZERO
        else max(absolute) / absolute_total * HUNDRED
    )
    minimum_evaluable = 120 if label == "design" else 75
    minimum_positive = 40 if label == "design" else 20
    minimum_nonpositive = 20 if label == "design" else 10
    gates = {
        f"minimum_{minimum_evaluable}_evaluable_transitions": len(episodes)
        >= minimum_evaluable,
        f"minimum_{minimum_positive}_positive_growth_transitions": len(positive)
        >= minimum_positive,
        f"minimum_{minimum_nonpositive}_nonpositive_growth_transitions": len(
            nonpositive
        )
        >= minimum_nonpositive,
        "positive_growth_median_next_168h_return_strictly_positive": positive_median
        is not None
        and positive_median > ZERO,
        "positive_minus_nonpositive_growth_median_next_168h_return_strictly_positive": median_difference
        is not None
        and median_difference > ZERO,
        "one_sided_mann_whitney_p_value_at_most_0_10": p_value is not None
        and p_value <= D("0.10"),
        "top_absolute_signed_response_contribution_at_most_20pct": top_absolute_share
        <= D("20"),
    }
    return {
        "episodes": episodes,
        "exclusions": {"missing_or_out_of_window_path": missing_path_exclusions},
        "statistics": {
            "episode_count": len(episodes),
            "positive_growth_count": len(positive),
            "nonpositive_growth_count": len(nonpositive),
            "positive_growth_median_next_168h_return": nullable(positive_median),
            "nonpositive_growth_median_next_168h_return": nullable(
                nonpositive_median
            ),
            "positive_minus_nonpositive_median_next_168h_return": nullable(
                median_difference
            ),
            "mann_whitney_u": nullable(mann_whitney_u),
            "one_sided_mann_whitney_p_value": nullable(p_value),
            "top_absolute_signed_response_contribution_pct": q(
                top_absolute_share
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
        "weekly_tuesday_predecessor_ion_exclusion_day7_availability_and_change_identity_pass": True,
        "frozen_runner_sources_prior_hypothesis_and_contract_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_PRE_ECONOMIC_GATES:
        raise ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed]


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get(
        "experiment_id"
    ) != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if (
        manifest.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("oos_access") != "DENY"
    ):
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
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
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_LONG_CASH_V1",
        "factor_identity": "CFTC_TFF_TOTAL_OPEN_INTEREST_WEEKLY_GROWTH_CONTINUATION_LONG_CASH_V1",
        "current_field": "Open_Interest_All",
        "prior_field": "Open_Interest_All",
        "change_identity_field": "Change_in_Open_Interest_All",
        "exact_predecessor_calendar_days": 7,
        "factor_formula": "CURRENT_OPEN_INTEREST_ALL_DIVIDED_BY_EXACT_PRIOR_WEEK_OPEN_INTEREST_ALL_MINUS_ONE",
        "change_identity": "CURRENT_OPEN_INTEREST_ALL_MINUS_PRIOR_OPEN_INTEREST_ALL_EQUALS_REPORTED_CHANGE_IN_OPEN_INTEREST_ALL",
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
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
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
        raise ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    if manifest.get("windows") != {
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
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_sets") != {
        "pre_economic": {
            "required": list(EXPECTED_PRE_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
        },
        "economic_if_pre_economic_passes": {
            "required": list(EXPECTED_ECONOMIC_GATES),
            "failure": "PERMANENTLY_CLOSE_WITHOUT_TUNING_OR_OOS",
        },
        "decision": "PRE_ECONOMIC_ALL_PASS_THEN_ECONOMIC_ALL_PASS_OR_CLOSE",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    if {binding.get("role") for binding in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_ROLES")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        if (
            role in EXPECTED_NON_RUNNER_HASHES
            and binding.get("sha256") != EXPECTED_NON_RUNNER_HASHES[role]
        ):
            raise ResearchReject(f"BINDING_REJECT:{role}:FROZEN_SHA256")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or sha256(path) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}:CURRENT_SHA256")


def build_output(btc_input: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    source_manifest, source_manifest_raw = cftc_reused.load_manifest(
        HISTORICAL_SOURCE_MANIFEST
    )
    if hashlib.sha256(source_manifest_raw).hexdigest() != EXPECTED_SOURCE_MANIFEST_SHA256:
        raise ResearchReject("SOURCE_REJECT:HISTORICAL_SOURCE_MANIFEST_SHA256")
    cftc_reused.verify_bindings(source_manifest)
    bars = cftc_reused.load_selection(btc_input, source_manifest)
    if (
        len(bars) != EXPECTED_BTC_ROWS
        or cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256
    ):
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_SHA256")
    rows, archive_evidence = cftc_reused.load_historical_rows(source_manifest)
    points, exclusions = build_factor_points(rows)
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(
            bars, points, VALIDATION, label="validation"
        ),
    }
    pre_gates, failed_pre = evaluate_pre_economic_gates(predictive)
    base_result: dict[str, Any] = {
        "schema_version": "1",
        "document_type": "BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
        },
        "datasets": {
            "btc": {
                "path": btc_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(btc_input),
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
            "positive_growth_points": sum(
                point.open_interest_growth > ZERO for point in points
            ),
            "nonpositive_growth_points": sum(
                point.open_interest_growth <= ZERO for point in points
            ),
            "zero_growth_points": sum(
                point.open_interest_growth == ZERO for point in points
            ),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
            "exclusions": exclusions,
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "claim_boundary": "Historical present-vintage CME TFF total-open-interest and pre-2025 BTC evidence only. Total open interest is directionless participation evidence; a pass is not independent alpha, source-continuity proof, a runtime strategy or activation permission.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        base_result.update(
            {
                "status": "NO_CANDIDATE_CLOSE_BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_FAMILY_PRE_ECONOMIC",
                "decision": "PERMANENTLY_CLOSE_EXACT_TOTAL_OPEN_INTEREST_WEEKLY_GROWTH_FAMILY_WITHOUT_ECONOMIC_ACCESS_DIRECTION_INVERSION_INTERACTION_OR_TUNING",
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
    ledger = shared.load_module("oi_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = shared.load_module("oi_frozen_long_cash_reference", REFERENCE_SOURCE)
    economic_output, economic_raw = shared.simulate_all(
        ledger, reference, bars, points
    )
    economic_gates, failed_economic, breadth = shared.evaluate_economic_gates(
        economic_output, economic_raw
    )
    passed = not failed_economic
    base_result.update(
        {
            "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_FAMILY",
            "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_TOTAL_OPEN_INTEREST_WEEKLY_GROWTH_FAMILY_WITHOUT_TUNING_OR_OOS",
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
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
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
                "sha256": sha256(output_path),
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
