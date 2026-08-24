#!/usr/bin/env python3
"""Deterministic source, independence and economic audit of one Puell risk veto."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from types import ModuleType
from typing import Any


getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-coinmetrics-puell-multiple-overheat-risk-veto-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_COINMETRICS_PUELL_MULTIPLE_OVERHEAT_RISK_VETO_HISTORICAL_MANIFEST_V1"
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_DAILY_ROWS = 2_192
EXPECTED_ISSUANCE_SHA256 = "7e443a284b5df553b5bb0c442126a1a00d752e6cadf5afd847de9b3c039f2ee6"
EXPECTED_RAW_SHA256 = "1a1a61ad8dcbb4460756bed2dd18da88f30c5effd0889916952ca5d366b29ee5"
EXPECTED_BUNDLE_SHA256 = "05964036ded83834019078b7ea631fc4d7ffd9a746942bde4a545bb557c01a7e"
EXPECTED_ISSUANCE_ROWS = 2_922
PRIOR_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-puell-multiple-overheat-risk-veto-primary-prior.v1.json"
HYPOTHESIS_SOURCE = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-puell-multiple-overheat-risk-veto-v1.hypothesis.json"
FORMULA_TEST_SOURCE = REPO_ROOT / "research_pipeline/tests/test_btc_coinmetrics_puell_multiple_overheat_risk_veto_historical.py"
KERNEL_SOURCE = REPO_ROOT / "research/btc_m2_liquidity_acceleration_long_cash_historical.py"
LEDGER_SOURCE = REPO_ROOT / "research/btc_daily_chaikin_money_flow_long_cash_historical.py"
REFERENCE_SOURCE = REPO_ROOT / "research/btc_monthly_12m_time_series_momentum_historical.py"
PARSER_SOURCE = REPO_ROOT / "research/btc_dra_reversal_confirmed_exit_v2c.py"
GATE_SOURCE = REPO_ROOT / "research/btc_coinmetrics_hash_ribbon_health_state_long_cash_historical.py"
EXPECTED_BINDINGS = {
    "economic_kernel": (KERNEL_SOURCE, "eb059aed19f839f9b6c1f443df45e6611e7170b431904c6a28e35d7c2dc2eb09"),
    "long_cash_ledger": (LEDGER_SOURCE, "5c43069168824670dcda0c6ec0c4f7d08389e8dcc718246ff57390ed872927bd"),
    "path_reference": (REFERENCE_SOURCE, "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"),
    "h1_parser": (PARSER_SOURCE, "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"),
    "economic_gate_reference": (GATE_SOURCE, "0584e001818e10fa1957f0eef7cae3297e08d6cc5446a4f33f163197a33e18af"),
    "primary_prior": (PRIOR_SOURCE, "85d6ba3a455a18111952ff9ccc21a3fe34ae32cc3d34762b505b7b4f08d61be6"),
    "hypothesis": (HYPOTHESIS_SOURCE, "569f9451edd6ba78796ac300a814b0b88d55e3e0439c250c68d539ba572ca917"),
    "formula_tests": (FORMULA_TEST_SOURCE, "b566c0b7cd73d47153834e9861565d0c2eff1091c99aedc3a962462e66c20d64"),
}
DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
PRE_ECONOMIC_GATE_NAMES = (
    "btc_sha256_52608_rows_and_2192_complete_days_match",
    "issuance_normalized_raw_and_bundle_sha256_match",
    "issuance_2922_nonnegative_contiguous_daily_rows_2017_2024_pass",
    "frozen_runner_support_prior_hypothesis_and_formula_test_sha256_match",
    "single_variant_inclusive_365d_threshold4_next_open_contract_pass",
    "design_evaluations_equal_1096",
    "design_overheat_days_at_least_5",
    "design_state_transitions_at_least_2",
    "design_both_states_in_at_least_2_years",
    "design_top_year_overheat_share_at_most_75pct",
    "validation_evaluations_equal_731",
    "validation_overheat_days_at_least_2",
    "validation_state_transitions_at_least_2",
    "validation_both_states_in_both_years",
    "validation_top_year_overheat_share_at_most_75pct",
    "design_abs_pearson_to_btc_price_365d_multiple_at_most_0_90",
    "validation_abs_pearson_to_btc_price_365d_multiple_at_most_0_90",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(Q8, rounding=ROUND_HALF_UP), "f")


def median(values: list[D]) -> D:
    if not values:
        raise ResearchReject("METRIC_REJECT:EMPTY_MEDIAN")
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D("2")


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def load_issuance(path: Path) -> dict[date, D]:
    rows: dict[date, D] = {}
    with path.open("r", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["date", "issuance_total_native_units"]:
            raise ResearchReject("DATA_REJECT:ISSUANCE_COLUMNS")
        for item in reader:
            observed = datetime.strptime(item["date"], "%Y-%m-%d").date()
            value = D(item["issuance_total_native_units"])
            if observed in rows or not value.is_finite() or value < ZERO:
                raise ResearchReject(f"DATA_REJECT:ISSUANCE_ROW:{observed.isoformat()}")
            rows[observed] = value
    if len(rows) != EXPECTED_ISSUANCE_ROWS:
        raise ResearchReject(f"DATA_REJECT:ISSUANCE_ROWS:{len(rows)}")
    ordered = sorted(rows)
    if ordered[0] != date(2017, 1, 1) or ordered[-1] != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:ISSUANCE_BOUNDARIES")
    for prior, current in zip(ordered, ordered[1:], strict=False):
        if current - prior != timedelta(days=1):
            raise ResearchReject(f"DATA_REJECT:ISSUANCE_GAP:{prior}:{current}")
    return rows


def build_puell_targets(
    issuance: dict[date, D], daily: list[Any]
) -> tuple[dict[datetime, bool], dict[str, Any], list[tuple[datetime, D, D, bool]]]:
    usd_issuance: list[D] = []
    closes: list[D] = []
    for point in daily:
        observed = point.close_time.date() - timedelta(days=1)
        if observed not in issuance or point.close <= ZERO:
            raise ResearchReject(f"DATA_REJECT:PUELL_SOURCE_ALIGNMENT:{observed}")
        usd_issuance.append(issuance[observed] * point.close)
        closes.append(point.close)
    targets: dict[datetime, bool] = {}
    observations: list[tuple[datetime, D, D, bool]] = []
    for index in range(364, len(daily)):
        revenue_window = usd_issuance[index - 364 : index + 1]
        price_window = closes[index - 364 : index + 1]
        revenue_mean = sum(revenue_window, ZERO) / D("365")
        price_mean = sum(price_window, ZERO) / D("365")
        if revenue_mean <= ZERO or price_mean <= ZERO:
            raise ResearchReject(f"DATA_REJECT:PUELL_DENOMINATOR:{daily[index].close_time}")
        puell = usd_issuance[index] / revenue_mean
        price_multiple = closes[index] / price_mean
        long_state = puell <= D("4")
        clock = daily[index].close_time
        targets[clock] = long_state
        observations.append((clock, puell, price_multiple, long_state))
    if not observations:
        raise ResearchReject("FEATURE_REJECT:NO_PUELL_OBSERVATIONS")
    values = [item[1] for item in observations]
    states = [item[3] for item in observations]
    return targets, {
        "factor_identity": "COIN_METRICS_ISS_TOT_NTV_TIMES_BTC_CLOSE_INCLUSIVE_365D_PUELL_GT4_RISK_VETO_V1",
        "formula_days": 365,
        "overheat_threshold": "4.00000000",
        "evaluation_count": len(observations),
        "long_days": sum(states),
        "overheat_days": sum(not state for state in states),
        "state_transition_count": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "minimum_puell_multiple": q(min(values)),
        "median_puell_multiple": q(median(values)),
        "maximum_puell_multiple": q(max(values)),
        "first_effective_time": observations[0][0].isoformat(),
        "last_effective_time": observations[-1][0].isoformat(),
    }, observations


def pearson(left: list[D], right: list[D]) -> D:
    if len(left) != len(right) or len(left) < 2:
        raise ResearchReject("METRIC_REJECT:PEARSON_SUPPORT")
    count = D(len(left))
    left_mean = sum(left, ZERO) / count
    right_mean = sum(right, ZERO) / count
    covariance = sum(((a - left_mean) * (b - right_mean) for a, b in zip(left, right, strict=True)), ZERO)
    left_ss = sum(((value - left_mean) ** 2 for value in left), ZERO)
    right_ss = sum(((value - right_mean) ** 2 for value in right), ZERO)
    if left_ss == ZERO or right_ss == ZERO:
        raise ResearchReject("METRIC_REJECT:PEARSON_DEGENERATE")
    return covariance / (left_ss * right_ss).sqrt()


def window_support(
    observations: list[tuple[datetime, D, D, bool]],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    selected = [item for item in observations if start <= item[0] < end]
    states = [item[3] for item in selected]
    overheat_by_year: dict[int, int] = {}
    long_years: set[int] = set()
    overheat_years: set[int] = set()
    for clock, _, _, state in selected:
        if state:
            long_years.add(clock.year)
        else:
            overheat_years.add(clock.year)
            overheat_by_year[clock.year] = overheat_by_year.get(clock.year, 0) + 1
    overheat_days = sum(not state for state in states)
    top_share = (
        D(max(overheat_by_year.values(), default=0)) / D(overheat_days) * HUNDRED
        if overheat_days
        else HUNDRED
    )
    correlation = pearson([item[1] for item in selected], [item[2] for item in selected])
    output = {
        "evaluations": len(selected),
        "long_days": sum(states),
        "overheat_days": overheat_days,
        "transitions": sum(current != prior for prior, current in zip(states, states[1:], strict=False)),
        "long_state_years": sorted(long_years),
        "overheat_state_years": sorted(overheat_years),
        "top_year_overheat_share_pct": q(top_share),
        "pearson_puell_to_btc_price_365d_multiple": q(correlation),
    }
    return output, {
        "evaluations": D(len(selected)),
        "overheat_days": D(overheat_days),
        "transitions": D(output["transitions"]),
        "long_years": D(len(long_years)),
        "overheat_years": D(len(overheat_years)),
        "top_share": top_share,
        "correlation": correlation,
    }


def evaluate_pre_economic_gates(
    design: dict[str, D], validation: dict[str, D]
) -> tuple[dict[str, bool], list[str]]:
    gates = {
        "btc_sha256_52608_rows_and_2192_complete_days_match": True,
        "issuance_normalized_raw_and_bundle_sha256_match": True,
        "issuance_2922_nonnegative_contiguous_daily_rows_2017_2024_pass": True,
        "frozen_runner_support_prior_hypothesis_and_formula_test_sha256_match": True,
        "single_variant_inclusive_365d_threshold4_next_open_contract_pass": True,
        "design_evaluations_equal_1096": design["evaluations"] == D("1096"),
        "design_overheat_days_at_least_5": design["overheat_days"] >= D("5"),
        "design_state_transitions_at_least_2": design["transitions"] >= D("2"),
        "design_both_states_in_at_least_2_years": min(design["long_years"], design["overheat_years"]) >= D("2"),
        "design_top_year_overheat_share_at_most_75pct": design["top_share"] <= D("75"),
        "validation_evaluations_equal_731": validation["evaluations"] == D("731"),
        "validation_overheat_days_at_least_2": validation["overheat_days"] >= D("2"),
        "validation_state_transitions_at_least_2": validation["transitions"] >= D("2"),
        "validation_both_states_in_both_years": min(validation["long_years"], validation["overheat_years"]) == D("2"),
        "validation_top_year_overheat_share_at_most_75pct": validation["top_share"] <= D("75"),
        "design_abs_pearson_to_btc_price_365d_multiple_at_most_0_90": abs(design["correlation"]) <= D("0.90"),
        "validation_abs_pearson_to_btc_price_365d_multiple_at_most_0_90": abs(validation["correlation"]) <= D("0.90"),
    }
    if tuple(gates) != PRE_ECONOMIC_GATE_NAMES:
        raise ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed]


def validate_manifest(manifest: dict[str, Any], runner_path: Path) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE or manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE" or manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_OOS")
    if manifest.get("datasets") != {
        "btc": {"locator": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "hourly_rows": EXPECTED_BTC_ROWS, "complete_utc_days": EXPECTED_DAILY_ROWS, "selection_cutoff": "2025-01-01T00:00:00"},
        "issuance": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_NORMALIZED_ARTIFACT", "sha256": EXPECTED_ISSUANCE_SHA256, "rows": EXPECTED_ISSUANCE_ROWS, "first_date": "2017-01-01", "last_date": "2024-12-31", "present_vintage": True},
        "raw_issuance": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_RAW_ARTIFACT", "sha256": EXPECTED_RAW_SHA256},
        "source_bundle": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_SOURCE_BUNDLE", "sha256": EXPECTED_BUNDLE_SHA256},
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("strategy_policy") != {
        "policy_id": "BTC_COINMETRICS_PUELL_MULTIPLE_OVERHEAT_RISK_VETO_V1",
        "source_metric": "COIN_METRICS_BTC_ISS_TOT_NTV_DAILY_REVIEWED_PRESENT_VINTAGE",
        "usd_issuance": "ISS_TOT_NTV_TIMES_COMPLETE_UTC_DAY_BTC_CLOSE",
        "moving_average_complete_days": 365,
        "moving_average_includes_current_day": True,
        "long_condition": "PUELL_MULTIPLE_LESS_THAN_OR_EQUAL_TO_4",
        "cash_condition": "PUELL_MULTIPLE_STRICTLY_GREATER_THAN_4",
        "availability": "COMPLETE_UTC_DAY_CLOSE_BEFORE_SAME_TIMESTAMP_NEXT_H1_OPEN",
        "execution": "SAME_TIMESTAMP_NEXT_BTC_H1_OPEN_ONLY_WHEN_TARGET_CHANGES",
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
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00"},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00"},
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set", {}).get("pre_economic_required") != list(PRE_ECONOMIC_GATE_NAMES):
        raise ResearchReject("MANIFEST_REJECT:PRE_ECONOMIC_GATES")
    if manifest.get("gate_set", {}).get("economic_reference") != "BTC_COINMETRICS_HASH_RIBBON_HEALTH_STATE_MATCHED_CAPITAL_GATES_V1":
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_GATES")
    runner = manifest.get("runner_binding", {})
    if runner.get("path") != runner_path.relative_to(REPO_ROOT).as_posix() or runner.get("sha256") != sha256(runner_path):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_BINDING")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or len(bindings) != len(EXPECTED_BINDINGS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDING_COUNT")
    actual = {item.get("key"): (item.get("path"), item.get("sha256")) for item in bindings}
    expected = {key: (path.relative_to(REPO_ROOT).as_posix(), digest) for key, (path, digest) in EXPECTED_BINDINGS.items()}
    if actual != expected:
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")


def build_output(
    btc_path: Path,
    issuance_path: Path,
    raw_path: Path,
    bundle_path: Path,
    manifest_path: Path,
) -> dict[str, Any]:
    if sha256(btc_path) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    external = {
        "issuance": (issuance_path, EXPECTED_ISSUANCE_SHA256),
        "raw_issuance": (raw_path, EXPECTED_RAW_SHA256),
        "source_bundle": (bundle_path, EXPECTED_BUNDLE_SHA256),
    }
    for label, (path, expected) in {**EXPECTED_BINDINGS, **external}.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if bundle.get("normalized_subset", {}).get("sha256") != EXPECTED_ISSUANCE_SHA256 or bundle.get("raw_response", {}).get("sha256") != EXPECTED_RAW_SHA256:
        raise ResearchReject("SOURCE_REJECT:BUNDLE_INTERNAL_HASHES")
    runner_path = Path(__file__).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, runner_path)
    parser = load_module("puell_h1_parser", PARSER_SOURCE)
    ledger = load_module("puell_long_cash_ledger", LEDGER_SOURCE)
    bars = parser.parse_rows(btc_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    daily = ledger.build_daily_points(bars)
    if len(daily) != EXPECTED_DAILY_ROWS:
        raise ResearchReject("DATA_REJECT:BTC_DAILY_ROWS")
    issuance = load_issuance(issuance_path)
    targets, feature, observations = build_puell_targets(issuance, daily)
    design_support_output, design_support_raw = window_support(observations, DESIGN)
    validation_support_output, validation_support_raw = window_support(observations, VALIDATION)
    pre_gates, failed_pre = evaluate_pre_economic_gates(design_support_raw, validation_support_raw)
    base = {
        "schema_version": "1",
        "document_type": "BTC_COINMETRICS_PUELL_MULTIPLE_OVERHEAT_RISK_VETO_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": runner_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(runner_path), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "datasets": {
            "btc": {"locator": ".research-state/java-parity/selection-2019-2024.tsv", "sha256": EXPECTED_BTC_SHA256, "rows": len(bars)},
            "issuance": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_NORMALIZED_ARTIFACT", "sha256": EXPECTED_ISSUANCE_SHA256, "rows": len(issuance)},
            "raw_issuance": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_RAW_ARTIFACT", "sha256": EXPECTED_RAW_SHA256},
            "source_bundle": {"locator": "SEALED_LOCAL_COIN_METRICS_ISS_TOT_NTV_SOURCE_BUNDLE", "sha256": EXPECTED_BUNDLE_SHA256},
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "feature": feature,
        "window_support_and_independence": {"design": design_support_output, "validation": validation_support_output},
        "pre_economic_gates": pre_gates,
        "failed_pre_economic_gates": failed_pre,
        "oos_opened": False,
        "candidate_created": False,
        "scope_note": "No paid API, second timer, second writer, external backfill, canonical state write, post-2024 outcome, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }
    if failed_pre:
        return {
            **base,
            "status": "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_PUELL_MULTIPLE_OVERHEAT_RISK_VETO_FAMILY_PRE_ECONOMIC",
            "decision": "PERMANENTLY_CLOSE_EXACT_ISSUANCE_ONLY_INCLUSIVE_365D_PUELL_GT4_RISK_VETO_AT_FROZEN_SUPPORT_OR_PRICE_INDEPENDENCE_GATE_WITHOUT_ECONOMIC_OPENING_RERUN_OR_RESCUE",
            "economics_opened": False,
            "failed_economic_gates": [],
            "claim_boundary": "Source, state-support and price-independence evidence only. Strategy economics were not opened because at least one frozen pre-economic gate failed.",
        }
    kernel = load_module("puell_economic_kernel", KERNEL_SOURCE)
    reference = load_module("puell_path_reference", REFERENCE_SOURCE)
    gates = load_module("puell_economic_gates", GATE_SOURCE)
    design_output, design_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, DESIGN)
    validation_output, validation_raw = kernel.simulate_window(ledger, reference, bars, targets, feature, VALIDATION)
    annual = {year: kernel.simulate_window(ledger, reference, bars, targets, feature, window) for year, window in ANNUAL.items()}
    economic_gates, failed_economic, breadth = gates.evaluate_economic_gates(
        design_output, design_raw, validation_output, validation_raw, annual
    )
    passed = not failed_economic
    return {
        **base,
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_PUELL_MULTIPLE_OVERHEAT_RISK_VETO_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_EXACT_ISSUANCE_ONLY_INCLUSIVE_365D_PUELL_GT4_RISK_VETO_WITHOUT_THRESHOLD_WINDOW_DIRECTION_WEIGHT_OR_GATE_TUNING",
        "economics_opened": True,
        "windows": {"design": design_output, "validation": validation_output},
        "annual_fair_reset": {year: value[0] for year, value in annual.items()},
        "breadth_and_concentration": breadth,
        "economic_gates": economic_gates,
        "failed_economic_gates": failed_economic,
        "candidate_created": passed,
        "claim_boundary": "Historical present-vintage reused-source evidence only. A pass remains REPORTED_NOT_ACTIVATED, requires one independent sealed OOS and never authorizes Trading.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--issuance", type=Path, required=True)
    parser.add_argument("--raw-issuance", type=Path, required=True)
    parser.add_argument("--source-bundle", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    btc_path = args.btc_input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    if not btc_path.is_relative_to(REPO_ROOT) or not manifest_path.is_relative_to(REPO_ROOT):
        raise ResearchReject("PATH_REJECT:BTC_OR_MANIFEST_OUTSIDE_REPOSITORY")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state") or output_path.exists():
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_output(
        btc_path,
        args.issuance.resolve(),
        args.raw_issuance.resolve(),
        args.source_bundle.resolve(),
        manifest_path,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_pre_economic_gates": result["failed_pre_economic_gates"],
        "failed_economic_gates": result["failed_economic_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
