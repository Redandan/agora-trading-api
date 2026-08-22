#!/usr/bin/env python3
"""Deterministic matched-capital BTC/PAXG monthly relative-momentum screen."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
from statistics import median
from typing import Any


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
INITIAL_EQUITY = D("10000")
Q = D("0.00000001")
SYMBOLS = ("BTCUSDT", "PAXGUSDT")
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
WINDOWS = {
    "DESIGN": (date(2021, 3, 1), date(2023, 1, 1)),
    "VALIDATION": (date(2023, 1, 1), date(2025, 1, 1)),
}
ANNUAL = {
    str(year): (date(year, 1, 1), date(year + 1, 1, 1))
    for year in range(2022, 2025)
}
EXPECTED_SIGNAL_COUNTS = {"DESIGN": 22, "VALIDATION": 24}
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-paxg-monthly-relative-momentum-rotation-historical-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_ROTATION_HISTORICAL_MANIFEST_V1"
)
EXPECTED_POLICY = {
    "policy_id": "BTC_PAXG_MONTHLY_SIX_COMPLETE_MONTH_RELATIVE_MOMENTUM_ROTATION_V1",
    "symbols": list(SYMBOLS),
    "bar_interval": "1d",
    "initial_equity_usdt": "10000",
    "formation": "EACH_SYMBOL_LAST_COMPLETE_DAY_CLOSE_DIVIDED_BY_FIRST_NATIVE_DAILY_OPEN_SIX_COMPLETE_CALENDAR_MONTHS_EARLIER_MINUS_ONE",
    "decision_clock": "FIRST_NATIVE_DAILY_OPEN_OF_EACH_UTC_MONTH",
    "selection": "HOLD_ONE_HUNDRED_PERCENT_OF_THE_SYMBOL_WITH_THE_HIGHER_LAGGED_FORMATION_RETURN",
    "exact_tie": "BTCUSDT",
    "switch_execution": "SELL_CURRENT_ASSET_THEN_BUY_SELECTED_ASSET_AT_THE_SAME_NATIVE_DAILY_OPEN_WITH_ADVERSE_COSTS",
    "cash_return": "0",
    "leverage": "DENY",
    "short": "DENY",
    "external_cash_flow": "DENY",
    "variants": 1,
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class Bar:
    symbol: str
    day: date
    open: D
    high: D
    low: D
    close: D


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def raw_sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def q(value: D) -> str:
    return format(value.quantize(Q, rounding=ROUND_HALF_UP), "f")


def percentile(values: list[int], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(D(value) for value in values)
    position = fraction * D(len(ordered) - 1)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - D(lower)
    return ordered[lower] * (ONE - weight) + ordered[upper] * weight


def state_output_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise ResearchReject(f"PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def validate_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ResearchReject("MANIFEST_REJECT:JSON") from error
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("strategy_policy") != EXPECTED_POLICY:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        name: {
            "fee_rate_per_side": str(fee),
            "adverse_slippage_rate_per_side": str(slip),
        }
        for name, (fee, slip) in SCENARIOS.items()
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": ["2021-03-01", "2023-01-01"],
        "validation": ["2023-01-01", "2025-01-01"],
        "annual_fair_reset_years": [2022, 2023, 2024],
        "formation_bootstrap_start": "2020-09-01",
        "state": "FAIR_RESET_IDENTICAL_INITIAL_EQUITY_EACH_WINDOW",
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set", {}).get("id") != (
        "BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_MATCHED_CAPITAL_GATES_V1"
    ):
        raise ResearchReject("MANIFEST_REJECT:GATES")
    for binding in manifest.get("source_bindings", []):
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or file_sha256(bound) != binding["sha256"]:
            prefix = (
                "DATA_REJECT"
                if str(binding["path"]).startswith(".research-state/")
                else "SOURCE_REJECT"
            )
            raise ResearchReject(f"{prefix}:{binding['role']}")
    return manifest


def load_bars(manifest: dict[str, Any]) -> dict[date, dict[str, Bar]]:
    dataset = manifest["dataset"]
    bundle_path = REPO_ROOT / dataset["bundle_path"]
    gzip_path = REPO_ROOT / dataset["normalized_gzip_path"]
    if file_sha256(bundle_path) != dataset["bundle_sha256"]:
        raise ResearchReject("DATA_REJECT:SOURCE_BUNDLE_SHA256")
    if file_sha256(gzip_path) != dataset["normalized_gzip_sha256"]:
        raise ResearchReject("DATA_REJECT:SOURCE_GZIP_SHA256")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if (
        bundle.get("status")
        != "SEALED_CHECKSUM_VERIFIED_COMPLETE_BTC_PAXG_2020_09_2024_SOURCE_NO_OUTCOME"
    ):
        raise ResearchReject("DATA_REJECT:SOURCE_STATUS")
    raw = gzip.decompress(gzip_path.read_bytes())
    if raw_sha256(raw) != bundle["corpus"]["normalized_csv_sha256"]:
        raise ResearchReject("DATA_REJECT:NORMALIZED_CSV_SHA256")
    try:
        rows = list(csv.DictReader(io.StringIO(raw.decode("ascii"), newline="")))
    except (UnicodeDecodeError, csv.Error) as error:
        raise ResearchReject("DATA_REJECT:CSV") from error
    if len(rows) != 3166:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(rows)}")
    by_day: dict[date, dict[str, Bar]] = {}
    for index, row in enumerate(rows):
        if list(row) != [
            "symbol",
            "date",
            "open",
            "high",
            "low",
            "close",
            "quote_volume",
        ]:
            raise ResearchReject("DATA_REJECT:COLUMNS")
        symbol = row["symbol"]
        if symbol not in SYMBOLS:
            raise ResearchReject(f"DATA_REJECT:SYMBOL:{symbol}")
        try:
            day = date.fromisoformat(row["date"])
            values = [D(row[field]) for field in ("open", "high", "low", "close")]
            quote_volume = D(row["quote_volume"])
        except (ValueError, ArithmeticError) as error:
            raise ResearchReject(f"DATA_REJECT:VALUE:{index}") from error
        open_value, high, low, close = values
        if (
            min(values) <= ZERO
            or quote_volume < ZERO
            or high < max(open_value, low, close)
            or low > min(open_value, high, close)
        ):
            raise ResearchReject(f"DATA_REJECT:OHLCV:{index}")
        if symbol in by_day.setdefault(day, {}):
            raise ResearchReject(f"DATA_REJECT:DUPLICATE:{day}:{symbol}")
        by_day[day][symbol] = Bar(symbol, day, open_value, high, low, close)
    days = sorted(by_day)
    if (
        len(days) != 1583
        or days[0] != date(2020, 9, 1)
        or days[-1] != date(2024, 12, 31)
    ):
        raise ResearchReject("DATA_REJECT:DAY_RANGE")
    if any(set(by_day[day]) != set(SYMBOLS) for day in days):
        raise ResearchReject("DATA_REJECT:INTERSECTION")
    return by_day


def shift_month_start(day: date, months: int) -> date:
    total = day.year * 12 + (day.month - 1) + months
    return date(total // 12, total % 12 + 1, 1)


def formation_returns(
    by_day: dict[date, dict[str, Bar]], decision_day: date
) -> dict[str, D]:
    if decision_day.day != 1:
        raise ResearchReject("CLOCK_REJECT:DECISION_DAY")
    start = shift_month_start(decision_day, -6)
    end = decision_day - timedelta(days=1)
    if start not in by_day or end not in by_day:
        raise ResearchReject(f"DATA_REJECT:FORMATION_WINDOW:{decision_day}")
    return {
        symbol: by_day[end][symbol].close / by_day[start][symbol].open - ONE
        for symbol in SYMBOLS
    }


def selected_symbol(by_day: dict[date, dict[str, Bar]], decision_day: date) -> str:
    returns = formation_returns(by_day, decision_day)
    if returns["BTCUSDT"] >= returns["PAXGUSDT"]:
        return "BTCUSDT"
    return "PAXGUSDT"


def annualized_return(total_return_fraction: D, days: int) -> D:
    if total_return_fraction <= -ONE:
        return D("-100")
    return D(str(math.pow(float(ONE + total_return_fraction), 365.25 / days) - 1)) * HUNDRED


def path_metrics(equities: list[tuple[date, D]]) -> tuple[D, int]:
    peak = INITIAL_EQUITY
    max_drawdown = ZERO
    underwater_start: date | None = None
    maximum_underwater_days = 0
    for day, equity in equities:
        if equity <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NON_POSITIVE_EQUITY")
        if equity >= peak:
            peak = equity
            underwater_start = None
        else:
            max_drawdown = max(max_drawdown, (peak - equity) / peak * HUNDRED)
            if underwater_start is None:
                underwater_start = day
            maximum_underwater_days = max(
                maximum_underwater_days, (day - underwater_start).days + 1
            )
    return max_drawdown, maximum_underwater_days


def summary_metrics(
    *,
    final_equity: D,
    terminal_proceeds: D,
    equities: list[tuple[date, D]],
    fees: D,
    turnover: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    total_pnl = final_equity - INITIAL_EQUITY
    total_return = total_pnl / INITIAL_EQUITY * HUNDRED
    terminal_return = (terminal_proceeds - INITIAL_EQUITY) / INITIAL_EQUITY * HUNDRED
    drawdown, underwater = path_metrics(equities)
    annualized = annualized_return(total_pnl / INITIAL_EQUITY, len(equities))
    calmar = ZERO if drawdown == ZERO else annualized / drawdown
    return {
        "total_return_pct": q(total_return),
        "annualized_return_pct": q(annualized),
        "maximum_drawdown_pct": q(drawdown),
        "maximum_underwater_duration_days": underwater,
        "calmar_ratio": q(calmar),
        "terminal_liquidation_adjusted_return_pct": q(terminal_return),
        "terminal_liquidation_cost_pct": q(total_return - terminal_return),
        "fees_usdt": q(fees),
        "turnover_usdt": q(turnover),
        "turnover_to_initial_equity": q(turnover / INITIAL_EQUITY),
    }, {
        "total": total_return,
        "terminal": terminal_return,
        "drawdown": drawdown,
        "calmar": calmar,
        "annualized": annualized,
    }


def simulate_rotation(
    by_day: dict[date, dict[str, Bar]],
    *,
    window: tuple[date, date],
    fee: D,
    slippage: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    days = [day for day in sorted(by_day) if start <= day < end]
    if not days or days[0] != start or days[-1] + timedelta(days=1) != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start}:{end}")
    cash = INITIAL_EQUITY
    current: str | None = None
    quantity = ZERO
    cost_basis = ZERO
    entry_day: date | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_count = 0
    switch_count = 0
    trade_count = 0
    selection_months = {symbol: 0 for symbol in SYMBOLS}
    hold_days: list[int] = []
    equities: list[tuple[date, D]] = []

    for day in days:
        if day.day == 1:
            selected = selected_symbol(by_day, day)
            selection_months[selected] += 1
            signal_count += 1
            if selected != current:
                if current is not None:
                    sell_price = by_day[day][current].open * (ONE - slippage)
                    gross = quantity * sell_price
                    sell_fee = gross * fee
                    cash = gross - sell_fee
                    realized += cash - cost_basis
                    fees += sell_fee
                    turnover += gross
                    trade_count += 1
                    if entry_day is None:
                        raise ResearchReject("ECONOMIC_REJECT:ENTRY_DAY")
                    hold_days.append((day - entry_day).days)
                    switch_count += 1
                buy_price = by_day[day][selected].open * (ONE + slippage)
                quantity = cash / (buy_price * (ONE + fee))
                notional = quantity * buy_price
                buy_fee = notional * fee
                cost_basis = notional + buy_fee
                cash = ZERO
                fees += buy_fee
                turnover += notional
                trade_count += 1
                current = selected
                entry_day = day
        if current is None:
            raise ResearchReject("ECONOMIC_REJECT:NO_POSITION")
        equities.append((day, quantity * by_day[day][current].close + cash))

    final_day, final_equity = equities[-1]
    if current is None or entry_day is None:
        raise ResearchReject("ECONOMIC_REJECT:NO_TERMINAL_POSITION")
    final_mark = by_day[final_day][current].close
    terminal_proceeds = quantity * final_mark * (ONE - slippage) * (ONE - fee) + cash
    base_metrics, raw = summary_metrics(
        final_equity=final_equity,
        terminal_proceeds=terminal_proceeds,
        equities=equities,
        fees=fees,
        turnover=turnover,
    )
    total_pnl = final_equity - INITIAL_EQUITY
    median_hold = None if not hold_days else D(str(median(hold_days)))
    p90_hold = percentile(hold_days, D("0.9"))
    base_metrics.update(
        {
            "realized_pnl_usdt": q(realized),
            "unrealized_pnl_usdt": q(total_pnl - realized),
            "monthly_signal_count": signal_count,
            "switch_count": switch_count,
            "trade_count": trade_count,
            "selection_months": selection_months,
            "median_realized_holding_days": None if median_hold is None else q(median_hold),
            "p90_realized_holding_days": None if p90_hold is None else q(p90_hold),
            "terminal_inventory": {
                "symbol": current,
                "quantity": q(quantity),
                "market_value_usdt": q(quantity * final_mark),
                "cost_basis_usdt": q(cost_basis),
                "age_days": (end - entry_day).days,
            },
        }
    )
    raw.update(
        {
            "signal_count": D(signal_count),
            "switch_count": D(switch_count),
            "btc_months": D(selection_months["BTCUSDT"]),
            "paxg_months": D(selection_months["PAXGUSDT"]),
        }
    )
    return base_metrics, raw


def simulate_static(
    by_day: dict[date, dict[str, Bar]],
    *,
    window: tuple[date, date],
    fee: D,
    slippage: D,
    weights: dict[str, D],
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    days = [day for day in sorted(by_day) if start <= day < end]
    if set(weights) != set(SYMBOLS) or sum(weights.values(), ZERO) != ONE:
        raise ResearchReject("MANIFEST_REJECT:STATIC_WEIGHTS")
    quantities: dict[str, D] = {}
    fees = ZERO
    turnover = ZERO
    for symbol, weight in weights.items():
        price = by_day[start][symbol].open * (ONE + slippage)
        budget = INITIAL_EQUITY * weight
        quantity = budget / (price * (ONE + fee))
        notional = quantity * price
        quantities[symbol] = quantity
        fees += notional * fee
        turnover += notional
    equities = [
        (
            day,
            sum(
                quantities[symbol] * by_day[day][symbol].close for symbol in SYMBOLS
            ),
        )
        for day in days
    ]
    final_day, final_equity = equities[-1]
    terminal_proceeds = sum(
        quantities[symbol]
        * by_day[final_day][symbol].close
        * (ONE - slippage)
        * (ONE - fee)
        for symbol in SYMBOLS
    )
    metrics, raw = summary_metrics(
        final_equity=final_equity,
        terminal_proceeds=terminal_proceeds,
        equities=equities,
        fees=fees,
        turnover=turnover,
    )
    metrics["terminal_inventory"] = {
        symbol: {
            "quantity": q(quantities[symbol]),
            "market_value_usdt": q(
                quantities[symbol] * by_day[final_day][symbol].close
            ),
        }
        for symbol in SYMBOLS
        if quantities[symbol] > ZERO
    }
    return metrics, raw


def paired_result(
    by_day: dict[date, dict[str, Bar]],
    *,
    window: tuple[date, date],
    fee: D,
    slippage: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    candidate, candidate_raw = simulate_rotation(
        by_day, window=window, fee=fee, slippage=slippage
    )
    static, static_raw = simulate_static(
        by_day,
        window=window,
        fee=fee,
        slippage=slippage,
        weights={"BTCUSDT": D("0.5"), "PAXGUSDT": D("0.5")},
    )
    btc, btc_raw = simulate_static(
        by_day,
        window=window,
        fee=fee,
        slippage=slippage,
        weights={"BTCUSDT": ONE, "PAXGUSDT": ZERO},
    )
    comparison = {
        "candidate_minus_static_total_return_pp": q(
            candidate_raw["total"] - static_raw["total"]
        ),
        "candidate_minus_static_terminal_return_pp": q(
            candidate_raw["terminal"] - static_raw["terminal"]
        ),
        "candidate_minus_static_maximum_drawdown_pp": q(
            candidate_raw["drawdown"] - static_raw["drawdown"]
        ),
        "candidate_minus_static_calmar_ratio": q(
            candidate_raw["calmar"] - static_raw["calmar"]
        ),
        "candidate_minus_btc_total_return_pp": q(
            candidate_raw["total"] - btc_raw["total"]
        ),
        "candidate_minus_btc_maximum_drawdown_pp": q(
            candidate_raw["drawdown"] - btc_raw["drawdown"]
        ),
        "candidate_minus_btc_calmar_ratio": q(
            candidate_raw["calmar"] - btc_raw["calmar"]
        ),
    }
    raw = {
        "candidate_total": candidate_raw["total"],
        "candidate_terminal": candidate_raw["terminal"],
        "candidate_drawdown": candidate_raw["drawdown"],
        "candidate_calmar": candidate_raw["calmar"],
        "static_total": static_raw["total"],
        "static_terminal": static_raw["terminal"],
        "static_drawdown": static_raw["drawdown"],
        "static_calmar": static_raw["calmar"],
        "btc_total": btc_raw["total"],
        "btc_drawdown": btc_raw["drawdown"],
        "btc_calmar": btc_raw["calmar"],
        "signal_count": candidate_raw["signal_count"],
        "switch_count": candidate_raw["switch_count"],
        "btc_months": candidate_raw["btc_months"],
        "paxg_months": candidate_raw["paxg_months"],
    }
    return {
        "start": window[0].isoformat(),
        "end_exclusive": window[1].isoformat(),
        "candidate_relative_momentum": candidate,
        "parent_static_50_50": static,
        "opportunity_cost_btc_only": btc,
        "comparison": comparison,
    }, raw


def evaluate_gates(
    window_raw: dict[str, dict[str, dict[str, D]]],
    annual_raw: dict[str, dict[str, dict[str, D]]],
) -> tuple[list[str], list[str], dict[str, Any]]:
    passed: list[str] = []
    failed: list[str] = []

    def gate(name: str, condition: bool) -> None:
        (passed if condition else failed).append(name)

    for window_name in WINDOWS:
        for scenario in SCENARIOS:
            raw = window_raw[window_name][scenario]
            prefix = f"{window_name.lower()}_{scenario.lower()}"
            gate(f"{prefix}_candidate_total_positive", raw["candidate_total"] > ZERO)
            gate(
                f"{prefix}_candidate_beats_static_total",
                raw["candidate_total"] > raw["static_total"],
            )
            gate(
                f"{prefix}_candidate_beats_static_terminal",
                raw["candidate_terminal"] > raw["static_terminal"],
            )
            gate(
                f"{prefix}_drawdown_non_worse_than_static",
                raw["candidate_drawdown"] <= raw["static_drawdown"],
            )
            gate(
                f"{prefix}_calmar_non_worse_than_static",
                raw["candidate_calmar"] >= raw["static_calmar"],
            )
            gate(
                f"{prefix}_drawdown_non_worse_than_btc",
                raw["candidate_drawdown"] <= raw["btc_drawdown"],
            )
            gate(
                f"{prefix}_calmar_non_worse_than_btc",
                raw["candidate_calmar"] >= raw["btc_calmar"],
            )
        normal = window_raw[window_name]["NORMAL"]
        gate(
            f"{window_name.lower()}_monthly_signal_count_exact",
            normal["signal_count"] == D(EXPECTED_SIGNAL_COUNTS[window_name]),
        )
        gate(f"{window_name.lower()}_switch_count_at_least_2", normal["switch_count"] >= 2)
        gate(f"{window_name.lower()}_btc_selected_at_least_3_months", normal["btc_months"] >= 3)
        gate(f"{window_name.lower()}_paxg_selected_at_least_3_months", normal["paxg_months"] >= 3)

    breadth: dict[str, Any] = {}
    for scenario in SCENARIOS:
        values = [annual_raw[year][scenario] for year in ANNUAL]
        candidate_positive = sum(raw["candidate_total"] > ZERO for raw in values)
        static_increment_positive = sum(
            raw["candidate_total"] > raw["static_total"] for raw in values
        )
        static_drawdown_non_worse = sum(
            raw["candidate_drawdown"] <= raw["static_drawdown"] for raw in values
        )
        static_calmar_non_worse = sum(
            raw["candidate_calmar"] >= raw["static_calmar"] for raw in values
        )
        btc_calmar_non_worse = sum(
            raw["candidate_calmar"] >= raw["btc_calmar"] for raw in values
        )
        positive_increments = [
            raw["candidate_total"] - raw["static_total"]
            for raw in values
            if raw["candidate_total"] > raw["static_total"]
        ]
        top_share = (
            D("100")
            if not positive_increments
            else max(positive_increments) / sum(positive_increments, ZERO) * HUNDRED
        )
        breadth[scenario] = {
            "candidate_positive_years": candidate_positive,
            "candidate_beats_static_years": static_increment_positive,
            "drawdown_non_worse_than_static_years": static_drawdown_non_worse,
            "calmar_non_worse_than_static_years": static_calmar_non_worse,
            "calmar_non_worse_than_btc_years": btc_calmar_non_worse,
            "top_positive_incremental_year_contribution_pct": q(top_share),
        }
        prefix = scenario.lower()
        gate(f"annual_{prefix}_candidate_positive_at_least_2_of_3", candidate_positive >= 2)
        gate(f"annual_{prefix}_beats_static_at_least_2_of_3", static_increment_positive >= 2)
        gate(f"annual_{prefix}_drawdown_non_worse_static_at_least_2_of_3", static_drawdown_non_worse >= 2)
        gate(f"annual_{prefix}_calmar_non_worse_static_at_least_2_of_3", static_calmar_non_worse >= 2)
        gate(f"annual_{prefix}_calmar_non_worse_btc_at_least_2_of_3", btc_calmar_non_worse >= 2)
        gate(f"annual_{prefix}_top_year_contribution_at_most_65pct", top_share <= D("65"))
    return passed, failed, breadth


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    output_path = state_output_path(args.output)
    manifest = validate_manifest(manifest_path)
    by_day = load_bars(manifest)

    window_results: dict[str, dict[str, dict[str, Any]]] = {}
    window_raw: dict[str, dict[str, dict[str, D]]] = {}
    for window_name, window in WINDOWS.items():
        window_results[window_name] = {}
        window_raw[window_name] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            result, raw = paired_result(by_day, window=window, fee=fee, slippage=slippage)
            window_results[window_name][scenario] = result
            window_raw[window_name][scenario] = raw

    annual_results: dict[str, dict[str, dict[str, Any]]] = {}
    annual_raw: dict[str, dict[str, dict[str, D]]] = {}
    for year, window in ANNUAL.items():
        annual_results[year] = {}
        annual_raw[year] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            result, raw = paired_result(by_day, window=window, fee=fee, slippage=slippage)
            annual_results[year][scenario] = result
            annual_raw[year][scenario] = raw

    passed, failed, breadth = evaluate_gates(window_raw, annual_raw)
    status = (
        "CANDIDATE_ELIGIBLE_HISTORICAL_PASS_REPORTED_NOT_ACTIVATED_OOS_UNOPENED"
        if not failed
        else "NO_CANDIDATE_CLOSE_BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_ROTATION_FAMILY"
    )
    result = {
        "schema_version": "1",
        "document_type": "BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_ROTATION_HISTORICAL_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "experiment_id": EXPERIMENT_ID,
        "status": status,
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": file_sha256(manifest_path),
        },
        "dataset": manifest["dataset"],
        "policy": manifest["strategy_policy"],
        "cost_scenarios": manifest["cost_scenarios"],
        "windows": window_results,
        "annual_fair_reset": annual_results,
        "annual_breadth": breadth,
        "gate_summary": {
            "passed_gate_count": len(passed),
            "failed_gate_count": len(failed),
            "passed_gates": passed,
            "failed_gates": failed,
            "decision": manifest["gate_set"]["decision"],
        },
        "candidate_created": not failed,
        "oos_opened": False,
        "scope_note": "Offline historical Design and Validation only. No paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER, LIVE or OOS action occurred.",
    }
    raw = canonical_bytes(result)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("xb") as target:
        target.write(raw)
    print(
        json.dumps(
            {
                "status": status,
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": raw_sha256(raw),
                "passed_gate_count": len(passed),
                "failed_gate_count": len(failed),
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
