#!/usr/bin/env python3
"""Deterministic historical screen for a lagged Cboe SKEW tail-risk policy."""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import sys
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 50

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")

REPO_ROOT = Path(__file__).resolve().parents[1]
BASE_RUNNER_SOURCE = (
    REPO_ROOT / "research" / "btc_monthly_12m_time_series_momentum_historical.py"
)
PARSER_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
PASSIVE_REFERENCE = (
    REPO_ROOT
    / "src"
    / "main"
    / "java"
    / "com"
    / "agora"
    / "research"
    / "BtcDonchianStandaloneHistoricalCli.java"
)
SKEW_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "cboe-skew-daily-2018-2024.v1.csv"
)
SKEW_SOURCE_METADATA = SKEW_SOURCE.with_suffix(".source.json")
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-skew-tail-risk-primary-prior.v1.json"
)

EXPERIMENT_ID = "btc-skew-tail-risk-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_SKEW_TAIL_RISK_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PASSIVE_REFERENCE_SHA256 = (
    "4ce8133148e691793c2d21419e11b9c2afaf70f9c2442b83d3b9c67e0fc68760"
)
EXPECTED_BASE_RUNNER_SHA256 = (
    "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b"
)
EXPECTED_SKEW_SHA256 = "27e8e939759342b37f1d8bcb1667ca7389f20bcf471032d5b6a45d16a35c1454"
EXPECTED_SKEW_METADATA_SHA256 = (
    "1149bbc04b6f4ade3248628e92d0ebc6bfe9544af1a1b8cca306e0bb32de1670"
)
EXPECTED_PRIOR_SHA256 = "aa81d877f4e98b57f494ffbbb9950cbd024e5f9819431e1926b777485444ecdf"
EXPECTED_SKEW_ROWS = 1_758
LOOKBACK = 252

DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
VARIANTS = (
    ("skew-percentile-p70-v1", "lower_neighbor", D("0.7")),
    ("skew-percentile-p80-v1", "primary", D("0.8")),
    ("skew-percentile-p90-v1", "upper_neighbor", D("0.9")),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class SkewRow:
    day: date
    close: D


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_skew_rows(path: Path) -> list[SkewRow]:
    with path.open(encoding="utf-8", newline="") as stream:
        records = list(csv.DictReader(stream))
    if len(records) != EXPECTED_SKEW_ROWS:
        raise ResearchReject(f"SKEW_DATA_REJECT:ROWS:{len(records)}")
    rows: list[SkewRow] = []
    for index, item in enumerate(records):
        if set(item) != {"DATE", "SKEW"}:
            raise ResearchReject("SKEW_DATA_REJECT:COLUMNS")
        try:
            row = SkewRow(
                day=datetime.strptime(item["DATE"], "%Y-%m-%d").date(),
                close=D(item["SKEW"]),
            )
        except (ValueError, KeyError, ArithmeticError) as exc:
            raise ResearchReject(f"SKEW_DATA_REJECT:PARSE:{index}") from exc
        if not row.close.is_finite() or row.close <= ZERO:
            raise ResearchReject(f"SKEW_DATA_REJECT:VALUE:{index}")
        if rows and row.day <= rows[-1].day:
            raise ResearchReject(f"SKEW_DATA_REJECT:ORDER:{index}")
        rows.append(row)
    if rows[0].day != date(2018, 1, 2) or rows[-1].day != date(2024, 12, 31):
        raise ResearchReject("SKEW_DATA_REJECT:BOUNDARY")
    return rows


def midrank_percentile(value: D, prior: list[D]) -> D:
    if len(prior) != LOOKBACK:
        raise ResearchReject(f"POLICY_REJECT:SKEW_LOOKBACK:{len(prior)}")
    below = sum(item < value for item in prior)
    equal = sum(item == value for item in prior)
    return (D(below) + D(equal) / D("2")) / D(LOOKBACK)


def build_signal_percentiles(rows: list[SkewRow]) -> dict[datetime, D]:
    signals: dict[datetime, D] = {}
    for index in range(LOOKBACK, len(rows)):
        prior = [item.close for item in rows[index - LOOKBACK : index]]
        effective_at = datetime.combine(rows[index].day + timedelta(days=1), time())
        if effective_at in signals:
            raise ResearchReject("POLICY_REJECT:DUPLICATE_EFFECTIVE_TIME")
        signals[effective_at] = midrank_percentile(rows[index].close, prior)
    return signals


def simulate_scenario(
    bars: list[object],
    signals: dict[datetime, D],
    window: tuple[datetime, datetime],
    threshold: D,
    fee_rate: D,
    slippage: D,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    trading = [bar for bar in bars if start <= bar.open_time < end]
    expected_hours = int((end - start).total_seconds() // 3600)
    if (
        len(trading) != expected_hours
        or not trading
        or trading[0].open_time != start
        or trading[-1].close_time != end
    ):
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}:{end.isoformat()}")

    cash = ONE
    quantity = ZERO
    entry_cost = ZERO
    entry_time: datetime | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    episode_pnls: list[D] = []
    hold_hours: list[D] = []
    signal_evaluations = 0
    long_targets = 0
    cash_targets = 0
    position_changes = 0
    path = base.PathAccumulator()
    final_equity = ONE

    for bar in trading:
        percentile = signals.get(bar.open_time)
        if percentile is not None:
            target_long = percentile <= threshold
            signal_evaluations += 1
            if target_long:
                long_targets += 1
            else:
                cash_targets += 1
            if target_long and quantity == ZERO:
                entry_cost = cash
                quantity, cash, fee, gross = base.buy_all(
                    cash, bar.open, fee_rate, slippage
                )
                entry_time = bar.open_time
                fees += fee
                turnover += gross
                position_changes += 1
            elif not target_long and quantity > ZERO:
                net, fee, gross = base.sell_all(
                    quantity, bar.open, fee_rate, slippage
                )
                pnl = net - entry_cost
                realized += pnl
                episode_pnls.append(pnl)
                hold_hours.append(
                    D(str((bar.open_time - entry_time).total_seconds() / 3600))
                )
                cash += net
                quantity = ZERO
                entry_cost = ZERO
                entry_time = None
                fees += fee
                turnover += gross
                position_changes += 1

        market_value = quantity * bar.close
        final_equity = cash + market_value
        path.observe(
            final_equity,
            market_value / final_equity if final_equity > ZERO else ZERO,
        )

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    terminal_liquidation_equity = final_equity
    if quantity > ZERO:
        terminal_net, _, _ = base.sell_all(
            quantity, trading[-1].close, fee_rate, slippage
        )
        terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (terminal_liquidation_equity - ONE) * HUNDRED
    positive_episodes = [value for value in episode_pnls if value > ZERO]
    candidate.update(
        {
            "realized_return_pct": base.q(realized * HUNDRED),
            "unrealized_return_pct": base.q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": base.q(
                terminal_liquidation_return
            ),
            "terminal_liquidation_cost_pp": base.q(
                raw["total_return"] - terminal_liquidation_return
            ),
            "fees_equity_units": base.q(fees),
            "turnover_equity_units": base.q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "long_target_count": long_targets,
            "cash_target_count": cash_targets,
            "position_change_count": position_changes,
            "completed_episode_count": len(episode_pnls),
            "winning_episode_count": len(positive_episodes),
            "median_hold_hours": base.nullable(base.percentile(hold_hours, D("0.5"))),
            "p90_hold_hours": base.nullable(base.percentile(hold_hours, D("0.9"))),
            "terminal_position": quantity > ZERO,
            "terminal_holding_age_hours": (
                None
                if entry_time is None
                else base.q(D(str((end - entry_time).total_seconds() / 3600)))
            ),
            "top_positive_episode_contribution_pct": (
                None
                if not positive_episodes
                else base.q(max(positive_episodes) / sum(positive_episodes, ZERO) * HUNDRED)
            ),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"] - terminal_liquidation_return,
            "position_changes": D(position_changes),
            "signal_evaluations": D(signal_evaluations),
            "p90_hold": base.percentile(hold_hours, D("0.9")) or ZERO,
            "terminal_holding_age": (
                ZERO
                if entry_time is None
                else D(str((end - entry_time).total_seconds() / 3600))
            ),
            "top_positive_episode_contribution": (
                ZERO
                if not positive_episodes
                else max(positive_episodes) / sum(positive_episodes, ZERO) * HUNDRED
            ),
            "has_positive_episode": ONE if positive_episodes else ZERO,
        }
    )
    benchmark, benchmark_raw = base.passive_benchmark(trading, fee_rate, slippage)
    upside_capture = (
        raw["total_return"] / benchmark_raw["total_return"]
        if benchmark_raw["total_return"] > ZERO
        else None
    )
    return {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate": candidate,
        "buy_and_hold": benchmark,
        "comparison": {
            "total_return_delta_pp": base.q(
                raw["total_return"] - benchmark_raw["total_return"]
            ),
            "maximum_drawdown_delta_pp": base.q(
                raw["drawdown"] - benchmark_raw["drawdown"]
            ),
            "upside_capture_ratio": base.nullable(upside_capture),
            "calmar_ratio_to_buy_hold": base.nullable(
                raw["calmar"] / benchmark_raw["calmar"]
                if benchmark_raw["calmar"] != ZERO
                else None
            ),
        },
    }, {
        **raw,
        "buy_hold_return": benchmark_raw["total_return"],
        "buy_hold_drawdown": benchmark_raw["drawdown"],
        "buy_hold_calmar": benchmark_raw["calmar"],
        "upside_capture": upside_capture if upside_capture is not None else ZERO,
    }


def simulate_window(
    bars: list[object],
    signals: dict[datetime, D],
    window: tuple[datetime, datetime],
    threshold: D,
    base: ModuleType,
) -> tuple[dict[str, object], dict[str, dict[str, D]]]:
    outputs: dict[str, object] = {}
    raws: dict[str, dict[str, D]] = {}
    for name, (fee_rate, slippage) in SCENARIOS.items():
        outputs[name], raws[name] = simulate_scenario(
            bars, signals, window, threshold, fee_rate, slippage, base
        )
    return outputs, raws


def breadth(raws: dict[str, dict[str, dict[str, D]]], base: ModuleType) -> dict[str, object]:
    normal_positive = sum(value["NORMAL"]["total_return"] > ZERO for value in raws.values())
    stress_positive = sum(value["STRESS"]["total_return"] > ZERO for value in raws.values())
    drawdown_nonworse = sum(
        value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"]
        for value in raws.values()
    )
    calmar_nonworse = sum(
        value["NORMAL"]["calmar"] >= value["NORMAL"]["buy_hold_calmar"]
        for value in raws.values()
    )
    positives = [max(value["NORMAL"]["total_return"], ZERO) for value in raws.values()]
    positive_sum = sum(positives, ZERO)
    top_year = max(positives, default=ZERO) / positive_sum * HUNDRED if positive_sum else HUNDRED
    return {
        "normal_positive_years": normal_positive,
        "stress_positive_years": stress_positive,
        "normal_drawdown_non_worse_years": drawdown_nonworse,
        "normal_calmar_non_worse_years": calmar_nonworse,
        "top_year_positive_total_return_contribution_pct": base.q(top_year),
        "top_year_raw": top_year,
    }


def primary_gates(
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual_breadth: dict[str, object],
) -> dict[str, bool]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "design_stress_total_return_positive": ds["total_return"] > ZERO,
        "design_drawdown_at_most_85pct_of_buy_hold": dn["drawdown"] <= D("0.85") * dn["buy_hold_drawdown"],
        "design_upside_capture_at_least_60pct": dn["upside_capture"] >= D("0.60"),
        "design_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_at_most_85pct_of_buy_hold": vn["drawdown"] <= D("0.85") * vn["buy_hold_drawdown"],
        "validation_upside_capture_at_least_60pct": vn["upside_capture"] >= D("0.60"),
        "validation_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "validation_signal_evaluations_at_least_400": vn["signal_evaluations"] >= D("400"),
        "validation_position_changes_at_least_2": vn["position_changes"] >= D("2"),
        "validation_stress_drawdown_no_more_than_normal_plus_3pp": vs["drawdown"] <= vn["drawdown"] + D("3"),
        "normal_positive_annual_return_at_least_4_of_5": annual_breadth["normal_positive_years"] >= 4,
        "stress_positive_annual_return_at_least_4_of_5": annual_breadth["stress_positive_years"] >= 4,
        "annual_drawdown_non_worse_5_of_5": annual_breadth["normal_drawdown_non_worse_years"] == 5,
        "annual_calmar_non_worse_at_least_3_of_5": annual_breadth["normal_calmar_non_worse_years"] >= 3,
        "top_year_positive_contribution_at_most_60pct": annual_breadth["top_year_raw"] <= D("60"),
        "validation_terminal_liquidation_adjusted_return_positive": vn["terminal_liquidation_return"] > ZERO,
        "validation_terminal_liquidation_cost_at_most_1pp": vn["terminal_liquidation_cost"] <= ONE,
    }


def neighbor_gates(
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual_breadth: dict[str, object],
) -> dict[str, bool]:
    dn, vn, vs = design["NORMAL"], validation["NORMAL"], validation["STRESS"]
    return {
        "design_normal_total_return_positive": dn["total_return"] > ZERO,
        "validation_normal_total_return_positive": vn["total_return"] > ZERO,
        "validation_stress_total_return_positive": vs["total_return"] > ZERO,
        "validation_drawdown_non_worse_than_buy_hold": vn["drawdown"] <= vn["buy_hold_drawdown"],
        "validation_calmar_at_least_75pct_of_buy_hold": vn["calmar"] >= D("0.75") * vn["buy_hold_calmar"],
        "normal_positive_annual_return_at_least_3_of_5": annual_breadth["normal_positive_years"] >= 3,
    }


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    expected = [
        {"variant_id": variant_id, "role": role, "threshold": str(threshold)}
        for variant_id, role, threshold in VARIANTS
    ]
    strategy_policy = manifest.get("strategy_policy", {})
    expected_policy = {
        "policy_id": "BTC_SKEW_PRIOR_252_BUSINESS_DAY_PERCENTILE_LONG_CASH_V1",
        "decision_clock": "NEXT_CALENDAR_DAY_0000_UTC_AFTER_EACH_CBOE_SKEW_CLOSE",
        "decision_feature": "LATEST_SKEW_CLOSE_PRIOR_252_BUSINESS_DAY_MIDRANK_PERCENTILE",
        "relation": "AT_OR_BELOW",
        "long_target": "BTC_100_PERCENT",
        "risk_off_target": "CASH_100_PERCENT",
    }
    if any(strategy_policy.get(key) != value for key, value in expected_policy.items()):
        raise ResearchReject("MANIFEST_REJECT:STRATEGY_POLICY")
    if strategy_policy.get("variants") != expected:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    expected_bindings = {
        SKEW_SOURCE.relative_to(REPO_ROOT).as_posix(): EXPECTED_SKEW_SHA256,
        SKEW_SOURCE_METADATA.relative_to(REPO_ROOT).as_posix(): EXPECTED_SKEW_METADATA_SHA256,
        PRIOR_SOURCE.relative_to(REPO_ROOT).as_posix(): EXPECTED_PRIOR_SHA256,
        PARSER_SOURCE.relative_to(REPO_ROOT).as_posix(): EXPECTED_PARSER_SHA256,
        BASE_RUNNER_SOURCE.relative_to(REPO_ROOT).as_posix(): EXPECTED_BASE_RUNNER_SHA256,
        PASSIVE_REFERENCE.relative_to(REPO_ROOT).as_posix(): EXPECTED_PASSIVE_REFERENCE_SHA256,
        Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(): sha256(Path(__file__).resolve()),
    }
    source_bindings = manifest.get("source_bindings", [])
    actual_bindings = {
        item.get("path"): item.get("sha256")
        for item in source_bindings
        if isinstance(item, dict)
    }
    if actual_bindings != expected_bindings:
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    if manifest.get("gate_set", {}).get("id") != "BTC_SKEW_TAIL_RISK_LONG_CASH_ECONOMIC_GATES_V1":
        raise ResearchReject("MANIFEST_REJECT:GATE_SET")
    if manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:OOS_ACCESS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    bindings = {
        "dataset": (input_path, EXPECTED_DATA_SHA256),
        "parser": (PARSER_SOURCE, EXPECTED_PARSER_SHA256),
        "passive_reference": (PASSIVE_REFERENCE, EXPECTED_PASSIVE_REFERENCE_SHA256),
        "base_runner": (BASE_RUNNER_SOURCE, EXPECTED_BASE_RUNNER_SHA256),
        "skew_source": (SKEW_SOURCE, EXPECTED_SKEW_SHA256),
        "skew_source_metadata": (SKEW_SOURCE_METADATA, EXPECTED_SKEW_METADATA_SHA256),
        "prior": (PRIOR_SOURCE, EXPECTED_PRIOR_SHA256),
    }
    for label, (path, expected) in bindings.items():
        actual = sha256(path)
        if actual != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{actual}")

    parser = load_module("skew_frozen_h1_parser", PARSER_SOURCE)
    base = load_module("skew_frozen_long_cash_base", BASE_RUNNER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    skew_rows = parse_skew_rows(SKEW_SOURCE)
    signals = build_signal_percentiles(skew_rows)

    variants: list[dict[str, object]] = []
    primary: dict[str, object] | None = None
    neighbor_results: dict[str, dict[str, bool]] = {}
    for variant_id, role, threshold in VARIANTS:
        design_output, design_raw = simulate_window(bars, signals, DESIGN, threshold, base)
        validation_output, validation_raw = simulate_window(bars, signals, VALIDATION, threshold, base)
        annual = {
            year: simulate_window(bars, signals, window, threshold, base)
            for year, window in ANNUAL.items()
        }
        annual_breadth = breadth({year: value[1] for year, value in annual.items()}, base)
        annual_breadth.pop("top_year_raw")
        variant = {
            "variant_id": variant_id,
            "role": role,
            "threshold": str(threshold),
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {year: value[0] for year, value in annual.items()},
            "breadth_and_concentration": annual_breadth,
        }
        gate_breadth = breadth({year: value[1] for year, value in annual.items()}, base)
        if role == "primary":
            gates = primary_gates(design_raw, validation_raw, gate_breadth)
            variant["primary_gates"] = gates
            primary = variant
        else:
            gates = neighbor_gates(design_raw, validation_raw, gate_breadth)
            variant["neighbor_gates"] = gates
            neighbor_results[variant_id] = gates
        variants.append(variant)

    if primary is None:
        raise ResearchReject("POLICY_REJECT:NO_PRIMARY")
    primary_gate_values = primary["primary_gates"]
    all_pass = all(primary_gate_values.values()) and all(
        all(gates.values()) for gates in neighbor_results.values()
    )
    failed_primary = [name for name, passed in primary_gate_values.items() if not passed]
    failed_neighbors = {
        key: [name for name, passed in gates.items() if not passed]
        for key, gates in neighbor_results.items()
        if not all(gates.values())
    }
    return {
        "schema_version": "1",
        "document_type": "BTC_SKEW_TAIL_RISK_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if all_pass
            else "NO_CANDIDATE_CLOSE_BTC_SKEW_TAIL_RISK_LONG_CASH_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_NEIGHBOR_GATES_PASS_SEALED_OOS_REQUIRED"
            if all_pass
            else "PERMANENTLY_CLOSE_EXACT_SKEW_252D_PERCENTILE_LONG_CASH_FAMILY_WITHOUT_TUNING"
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
            "sha256": EXPECTED_DATA_SHA256,
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "skew_source": {
            "path": SKEW_SOURCE.relative_to(REPO_ROOT).as_posix(),
            "sha256": EXPECTED_SKEW_SHA256,
            "rows": len(skew_rows),
            "signal_count_after_252_day_warmup": len(signals),
            "first_date": skew_rows[0].day.isoformat(),
            "last_date": skew_rows[-1].day.isoformat(),
        },
        "source_bindings": {
            label: expected for label, (_, expected) in bindings.items()
        },
        "policy": {
            "feature": "LATEST_SKEW_CLOSE_PRIOR_252_BUSINESS_DAY_MIDRANK_PERCENTILE",
            "effective_time": "NEXT_CALENDAR_DAY_0000_UTC",
            "long_relation": "AT_OR_BELOW",
            "long_target": "BTC_100_PERCENT",
            "risk_off_target": "CASH_100_PERCENT",
            "variants": 3,
        },
        "variants": variants,
        "primary_gates": primary_gate_values,
        "failed_primary_gates": failed_primary,
        "failed_neighbor_gates": failed_neighbors,
        "all_gates_pass": all_pass,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; a pass requires a separately sealed independent OOS and never authorizes activation.",
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
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_primary_gates": result["failed_primary_gates"],
                "failed_neighbor_gates": result["failed_neighbor_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

