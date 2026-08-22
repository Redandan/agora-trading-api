#!/usr/bin/env python3
"""Deterministic matched-capital audit for frozen lagged rising UMCSENT."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime
from decimal import Decimal, InvalidOperation
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"


def _load_kernel() -> Any:
    spec = importlib.util.spec_from_file_location("umcsent_frozen_economic_kernel", KERNEL_SOURCE)
    if spec is None or spec.loader is None:
        raise RuntimeError("SOURCE_REJECT:KERNEL_IMPORT_SPEC")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


kernel = _load_kernel()

LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-v1.hypothesis.json"
SOURCE_BUNDLE = REPO_ROOT / ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-source-bundle.json"
SOURCE_PROBE = REPO_ROOT / "research/fred_umcsent_source_probe.py"
SOURCE_SPEC = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-source-feasibility.v1.spec.json"
IMPORT_AMENDMENT = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-pre-execution-runner-import-amendment.v1.json"
SIGNAL_COUNT_ERRATUM = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-post-outcome-signal-count-binding-erratum.v1.json"

EXPERIMENT_ID = "btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_HISTORICAL_MANIFEST_V3"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_BTC_DAILY_ROWS = 2_192
EXPECTED_UMCSENT_SHA256 = "73f985132ce7c0afe229254e837ff032e74956ba7e59b5d970d746be83a346e8"
EXPECTED_UMCSENT_ROWS = 96
EXPECTED_KERNEL_SHA256 = "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"
EXPECTED_LEDGER_SHA256 = "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"
EXPECTED_REFERENCE_SHA256 = "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PRIOR_SHA256 = "65c9ed9f906956685f68b641839fec0ab5ebd20e7dd717c1ca0df205f9404028"
EXPECTED_HYPOTHESIS_SHA256 = "78281cdfc3dc99880b2094b81e1b4e7e101dfe3e17745e629be38fa6f4847d27"
EXPECTED_SOURCE_BUNDLE_SHA256 = "493e2a4600958fc731ca8bcb666f5f336ec5fbb1113e19420e2280e08bfc911b"
EXPECTED_SOURCE_PROBE_SHA256 = "b4464b6f2984dac5ae980252c517ba422efb0de3e731611bc0ad31949eba4594"
EXPECTED_SOURCE_SPEC_SHA256 = "f43a5e53bc57badbc2d4c4d588050fd0ac96c2ff476921b45779330049b593f1"
EXPECTED_IMPORT_AMENDMENT_SHA256 = "eb763433f426b1794a25040423869a7a51b126d6e60cf5a084091539e533250a"
EXPECTED_SIGNAL_COUNT_ERRATUM_SHA256 = "5f5f9a78ef8d4e0a60972b16817a5f78a94f1617712c97bbfa1d44f7070516e4"

DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2019, 2025)
}
EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_ECONOMIC_RUNNER": "research/btc_fred_umcsent_rising_consumer_sentiment_long_cash_historical.py",
    "FROZEN_MATCHED_CAPITAL_ECONOMIC_KERNEL": "research/btc_m2_liquidity_acceleration_long_cash_historical.py",
    "FROZEN_LONG_CASH_LEDGER": "research/btc_daily_chaikin_money_flow_long_cash_historical.py",
    "FROZEN_PATH_AND_BUY_HOLD_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "FROZEN_H1_PARSER": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-primary-prior.v1.json",
    "FROZEN_SCHEMA_VALID_HYPOTHESIS": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-v1.hypothesis.json",
    "SEALED_OFFICIAL_UMCSENT_SOURCE_BUNDLE": ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-source-bundle.json",
    "SEALED_NORMALIZED_UMCSENT_CORPUS": ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-monthly-2017-2024.csv",
    "FROZEN_FAIL_CLOSED_UMCSENT_SOURCE_PROBE": "research/fred_umcsent_source_probe.py",
    "FROZEN_PRE_FACTOR_SOURCE_FEASIBILITY_SPEC": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-source-feasibility.v1.spec.json",
    "SEALED_PRE_EXECUTION_RUNNER_IMPORT_AMENDMENT": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-pre-execution-runner-import-amendment.v1.json",
    "SEALED_POST_OUTCOME_SIGNAL_COUNT_BINDING_ERRATUM": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-post-outcome-signal-count-binding-erratum.v1.json",
}
EXPECTED_GATE_NAMES = (
    "btc_sha256_and_52608_rows_match",
    "umcsent_sha256_and_96_months_match",
    "umcsent_source_bundle_sha256_matches",
    "hourly_lattice_monthly_continuity_and_conservative_availability_pass",
    "frozen_runner_kernel_ledger_reference_parser_prior_hypothesis_probe_and_spec_sha256_match",
    "single_variant_strict_adjacent_month_increase_no_rescue_contract_pass",
    "design_normal_total_return_pct_gt_0",
    "design_stress_total_return_pct_gt_0",
    "design_normal_drawdown_at_most_90pct_of_buy_hold",
    "design_normal_upside_capture_at_least_50pct",
    "design_normal_calmar_at_least_buy_hold",
    "design_normal_position_changes_between_2_and_48",
    "validation_normal_total_return_pct_gt_0",
    "validation_stress_total_return_pct_gt_0",
    "validation_normal_drawdown_at_most_90pct_of_buy_hold",
    "validation_normal_upside_capture_at_least_60pct",
    "validation_normal_calmar_at_least_75pct_of_buy_hold",
    "validation_normal_signal_evaluations_at_least_24",
    "validation_normal_position_changes_between_2_and_24",
    "validation_stress_drawdown_no_more_than_normal_plus_3pp",
    "normal_positive_annual_total_return_at_least_5_of_6",
    "stress_positive_annual_total_return_at_least_5_of_6",
    "normal_annual_drawdown_non_worse_at_least_5_of_6",
    "normal_annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6",
    "normal_annual_upside_capture_at_least_50pct_at_least_5_of_6",
    "top_year_positive_total_return_contribution_at_most_60pct",
    "validation_top_positive_episode_contribution_at_most_60pct",
    "validation_p90_hold_at_most_8760_hours",
    "validation_terminal_holding_age_at_most_8760_hours",
    "validation_terminal_liquidation_adjusted_return_pct_gt_0",
    "validation_terminal_liquidation_cost_at_most_1pp",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def next_month(value: str) -> str:
    year, month = (int(part) for part in value.split("-"))
    return f"{year + 1:04d}-01" if month == 12 else f"{year:04d}-{month + 1:02d}"


def add_months(value: str, count: int) -> str:
    result = value
    for _ in range(count):
        result = next_month(result)
    return result


def load_umcsent(path: Path) -> list[tuple[str, D]]:
    rows: list[tuple[str, D]] = []
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["observation_date", "umcsent"]:
            raise ResearchReject(f"DATA_REJECT:UMCSENT_COLUMNS:{reader.fieldnames}")
        for row in reader:
            try:
                raw_day = row["observation_date"]
                month = raw_day[:7]
                value = D(row["umcsent"])
            except (KeyError, InvalidOperation) as error:
                raise ResearchReject("DATA_REJECT:UMCSENT_PARSE") from error
            if raw_day != f"{month}-01" or value < ZERO or value > D("200"):
                raise ResearchReject(f"DATA_REJECT:UMCSENT_VALUE_OR_DATE:{raw_day}")
            if rows and next_month(rows[-1][0]) != month:
                raise ResearchReject(f"DATA_REJECT:UMCSENT_CONTINUITY:{rows[-1][0]}:{month}")
            rows.append((month, value))
    if len(rows) != EXPECTED_UMCSENT_ROWS:
        raise ResearchReject(f"DATA_REJECT:UMCSENT_ROWS:{len(rows)}")
    if rows[0][0] != "2017-01" or rows[-1][0] != "2024-12":
        raise ResearchReject("DATA_REJECT:UMCSENT_BOUNDARY")
    return rows


def targets_by_execution_time(rows: list[tuple[str, D]]) -> tuple[dict[datetime, bool], dict[str, Any]]:
    targets: dict[datetime, bool] = {}
    changes: list[D] = []
    for index in range(1, len(rows)):
        month, current = rows[index]
        change = current - rows[index - 1][1]
        effective_time = datetime.fromisoformat(f"{add_months(month, 2)}-01T00:00:00")
        if effective_time in targets:
            raise ResearchReject(f"DATA_REJECT:DUPLICATE_EFFECTIVE_TIME:{effective_time}")
        targets[effective_time] = change > ZERO
        changes.append(change)
    if len(targets) != 95:
        raise ResearchReject(f"DATA_REJECT:UMCSENT_EVALUATIONS:{len(targets)}")
    states = list(targets.values())
    return targets, {
        "formula": "UMCSENT_t_MINUS_UMCSENT_t_minus_1",
        "threshold": "STRICTLY_GREATER_THAN_ZERO",
        "evaluation_count": len(changes),
        "rising_count": sum(states),
        "non_rising_count": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "minimum_change": kernel.q(min(changes)),
        "maximum_change": kernel.q(max(changes)),
        "median_change": kernel.q(kernel.percentile(changes, D("0.5")) or ZERO),
        "first_effective_time": min(targets).isoformat(),
        "last_effective_time": max(targets).isoformat(),
    }


def evaluate_gates(
    validation_output: dict[str, Any],
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, Any], dict[str, dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    calmar_breadth = sum(value["NORMAL"]["calmar"] >= D("0.75") * value["NORMAL"]["buy_hold_calmar"] for value in annual_raw.values())
    upside_breadth = sum(value["NORMAL"]["upside_capture"] >= D("0.50") for value in annual_raw.values())
    positive_year_returns = [max(value["NORMAL"]["total_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED if positive_sum > ZERO else HUNDRED
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "umcsent_sha256_and_96_months_match": True,
        "umcsent_source_bundle_sha256_matches": True,
        "hourly_lattice_monthly_continuity_and_conservative_availability_pass": True,
        "frozen_runner_kernel_ledger_reference_parser_prior_hypothesis_probe_and_spec_sha256_match": True,
        "single_variant_strict_adjacent_month_increase_no_rescue_contract_pass": True,
        "design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "design_normal_drawdown_at_most_90pct_of_buy_hold": dn["drawdown"] <= D("0.90") * dn["buy_hold_drawdown"],
        "design_normal_upside_capture_at_least_50pct": dn["upside_capture"] >= D("0.50"),
        "design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "design_normal_position_changes_between_2_and_48": D("2") <= dn["position_changes"] <= D("48"),
        "validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "validation_normal_drawdown_at_most_90pct_of_buy_hold": vn["drawdown"] <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_normal_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "validation_normal_calmar_at_least_75pct_of_buy_hold": vn["calmar"] >= D("0.75") * vn["buy_hold_calmar"],
        "validation_normal_signal_evaluations_at_least_24": D(validation_output["scenarios"]["NORMAL"]["candidate"]["signal_evaluation_count"]) >= D("24"),
        "validation_normal_position_changes_between_2_and_24": D("2") <= vn["position_changes"] <= D("24"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_total_return_at_least_5_of_6": normal_positive >= 5,
        "stress_positive_annual_total_return_at_least_5_of_6": stress_positive >= 5,
        "normal_annual_drawdown_non_worse_at_least_5_of_6": drawdown_nonworse >= 5,
        "normal_annual_calmar_at_least_75pct_buy_hold_at_least_4_of_6": calmar_breadth >= 4,
        "normal_annual_upside_capture_at_least_50pct_at_least_5_of_6": upside_breadth >= 5,
        "top_year_positive_total_return_contribution_at_most_60pct": top_year <= D("60"),
        "validation_top_positive_episode_contribution_at_most_60pct": vn["has_positive_episode"] == ZERO or vn["top_positive_episode_contribution"] <= D("60"),
        "validation_p90_hold_at_most_8760_hours": vn["p90_hold"] <= D("8760"),
        "validation_terminal_holding_age_at_most_8760_hours": vn["terminal_holding_age"] <= D("8760"),
        "validation_terminal_liquidation_adjusted_return_pct_gt_0": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }
    if tuple(gates) != EXPECTED_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:RUNNER_GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_6",
        "stress_positive_years": f"{stress_positive}_of_6",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_6",
        "normal_calmar_at_least_75pct_buy_hold_years": f"{calmar_breadth}_of_6",
        "normal_upside_capture_at_least_50pct_years": f"{upside_breadth}_of_6",
        "top_year_positive_total_return_contribution_pct": kernel.q(top_year),
        "validation_top_positive_episode_contribution_pct": validation_output["scenarios"]["NORMAL"]["candidate"]["top_positive_episode_contribution_pct"],
    }
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("pre_execution_amendment") != {
        "path": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-pre-execution-runner-import-amendment.v1.json",
        "sha256": EXPECTED_IMPORT_AMENDMENT_SHA256,
        "scope": "IMPORT_PATH_ONLY_NO_DATA_POLICY_COST_GATE_OR_DECISION_CHANGE",
    }:
        raise ResearchReject("MANIFEST_REJECT:PRE_EXECUTION_AMENDMENT")
    if manifest.get("post_outcome_implementation_erratum") != {
        "path": "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-post-outcome-signal-count-binding-erratum.v1.json",
        "sha256": EXPECTED_SIGNAL_COUNT_ERRATUM_SHA256,
        "scope": "SIGNAL_EVALUATION_COUNT_FIELD_BINDING_ONLY_NO_GATE_OR_THRESHOLD_CHANGE",
    }:
        raise ResearchReject("MANIFEST_REJECT:POST_OUTCOME_IMPLEMENTATION_ERRATUM")
    if manifest.get("datasets") != {
        "btc": {"path": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "expected_complete_utc_days": EXPECTED_BTC_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "umcsent": {"path": ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-monthly-2017-2024.csv", "sha256": EXPECTED_UMCSENT_SHA256, "rows": EXPECTED_UMCSENT_ROWS, "first_month": "2017-01", "last_month": "2024-12", "present_vintage": True},
        "source_bundle": {"path": ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-source-bundle.json", "sha256": EXPECTED_SOURCE_BUNDLE_SHA256},
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_V1",
        "source_series": "FRED_UMCSENT_FINAL_MONTHLY_PRESENT_VINTAGE",
        "evaluation_frequency": "MONTHLY",
        "availability": "OBSERVATION_MONTH_PLUS_TWO_MONTHS_FIRST_DAY_AT_00_00_UTC",
        "formula": "UMCSENT_t_MINUS_UMCSENT_t_minus_1",
        "long_condition": "CHANGE_STRICTLY_GREATER_THAN_ZERO",
        "cash_condition": "CHANGE_LESS_THAN_OR_EQUAL_TO_ZERO",
        "execution": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "missing_observation_rule": "FAIL_CLOSED_NO_INTERPOLATION_OR_SOURCE_REPAIR",
        "sizing": "FULL_AVAILABLE_EQUITY_WITH_NO_LEVERAGE",
        "cash_return": "0",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 1,
    }:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
        "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": {"start": "2019-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2019, 2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "id": "BTC_FRED_UMCSENT_RISING_MATCHED_CAPITAL_GATES_V1",
        "required": list(EXPECTED_GATE_NAMES),
        "decision": "ALL_GATES_PASS_OR_PERMANENTLY_CLOSE_WITHOUT_TUNING",
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
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or sha256(bound) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}")


def build_output(btc_path: Path, umcsent_path: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    expected_sources = {
        KERNEL_SOURCE: EXPECTED_KERNEL_SHA256,
        LEDGER_SOURCE: EXPECTED_LEDGER_SHA256,
        REFERENCE_SOURCE: EXPECTED_REFERENCE_SHA256,
        PARSER_SOURCE: EXPECTED_PARSER_SHA256,
        PRIOR_SOURCE: EXPECTED_PRIOR_SHA256,
        HYPOTHESIS_SOURCE: EXPECTED_HYPOTHESIS_SHA256,
        SOURCE_BUNDLE: EXPECTED_SOURCE_BUNDLE_SHA256,
        SOURCE_PROBE: EXPECTED_SOURCE_PROBE_SHA256,
        SOURCE_SPEC: EXPECTED_SOURCE_SPEC_SHA256,
        IMPORT_AMENDMENT: EXPECTED_IMPORT_AMENDMENT_SHA256,
        SIGNAL_COUNT_ERRATUM: EXPECTED_SIGNAL_COUNT_ERRATUM_SHA256,
    }
    for source, expected in expected_sources.items():
        if sha256(source) != expected:
            raise ResearchReject(f"SOURCE_REJECT:SHA256:{source.relative_to(REPO_ROOT)}")
    if sha256(btc_path) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    if sha256(umcsent_path) != EXPECTED_UMCSENT_SHA256:
        raise ResearchReject("DATA_REJECT:UMCSENT_SHA256")
    parser = kernel.load_module("umcsent_frozen_h1_parser", PARSER_SOURCE)
    ledger = kernel.load_module("umcsent_frozen_long_cash_ledger", LEDGER_SOURCE)
    reference = kernel.load_module("umcsent_frozen_path_reference", REFERENCE_SOURCE)
    bars = parser.parse_rows(btc_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    umcsent = load_umcsent(umcsent_path)
    targets, feature = targets_by_execution_time(umcsent)
    design_output, design_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {
        year: kernel.simulate_window(ledger, reference, bars, targets, feature, window)
        for year, window in ANNUAL.items()
    }
    gates, failed, breadth = evaluate_gates(validation_output, design_raw, validation_raw, annual)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_LAGGED_UMCSENT_ADJACENT_MONTH_RISE_LONG_CASH_FAMILY_WITHOUT_COMPONENT_LEVEL_THRESHOLD_LAG_OR_DIRECTION_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve())},
        "datasets": {
            "btc": {"path": btc_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(btc_path), "hourly_rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00"},
            "umcsent": {"path": umcsent_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(umcsent_path), "rows": len(umcsent), "present_vintage": True},
            "source_bundle": {"path": SOURCE_BUNDLE.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(SOURCE_BUNDLE)},
        },
        "policy": {"feature": feature, "variants": 1, "availability_lag_months": 2, "cash_return": "0"},
        "design": design_output,
        "validation": validation_output,
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "candidate_created": passed,
        "oos_opened": False,
        "claim_boundary": "Historical matched-capital present-vintage UMCSENT evidence only. A pass is not point-in-time revision proof, independent alpha, runtime implementation proof or permission to activate.",
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--umcsent-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [args.btc_input.resolve(), args.umcsent_input.resolve(), args.manifest.resolve()]
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
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(output_path), "failed_gates": result["failed_gates"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
