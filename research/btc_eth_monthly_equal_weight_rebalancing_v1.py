#!/usr/bin/env python3
"""Deterministic matched-capital BTC/ETH monthly equal-weight economic screen."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date
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
SYMBOLS = ("BTCUSDT", "ETHUSDT")
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
WINDOWS = {
    "DESIGN": (date(2020, 1, 1), date(2023, 1, 1)),
    "VALIDATION": (date(2023, 1, 1), date(2025, 1, 1)),
}
ANNUAL = {
    str(year): (date(year, 1, 1), date(year + 1, 1, 1))
    for year in range(2020, 2025)
}
EXPECTED_SIGNAL_COUNTS = {"DESIGN": 35, "VALIDATION": 23}
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-eth-monthly-equal-weight-rebalancing-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_ETH_MONTHLY_EQUAL_WEIGHT_REBALANCING_HISTORICAL_MANIFEST_V1"
EXPECTED_SOURCE_BUNDLE_SHA256 = "944f07771b5f743463f6614efd6b08164691aadd091b0808c309e4cbbbbf3769"
EXPECTED_SOURCE_GZIP_SHA256 = "1896e9af2c01ce61555905cfc6a830e5e84c0a68c69c963be15a0b5ef26fd591"
EXPECTED_ROWS = 3654
Q = D("0.00000001")


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


@dataclass
class Lot:
    quantity: D
    cost_basis: D
    entry_day: date


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


def percentile(values: list[D], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
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
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    policy = manifest.get("strategy_policy", {})
    if policy.get("symbols") != list(SYMBOLS):
        raise ResearchReject("MANIFEST_REJECT:SYMBOLS")
    if policy.get("target_weights") != {"BTCUSDT": "0.50", "ETHUSDT": "0.50"}:
        raise ResearchReject("MANIFEST_REJECT:TARGET_WEIGHTS")
    if policy.get("variants") != 1:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    if policy.get("rebalance") != "FIRST_NATIVE_DAILY_OPEN_OF_EACH_UTC_MONTH":
        raise ResearchReject("MANIFEST_REJECT:REBALANCE")
    if manifest.get("cost_scenarios") != {
        name: {"fee_rate_per_side": str(fee), "adverse_slippage_rate_per_side": str(slip)}
        for name, (fee, slip) in SCENARIOS.items()
    }:
        raise ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")
    for binding in manifest.get("source_bindings", []):
        bound = REPO_ROOT / binding["path"]
        if not bound.is_file() or file_sha256(bound) != binding["sha256"]:
            prefix = "DATA_REJECT" if str(binding["path"]).startswith(".research-state/") else "SOURCE_REJECT"
            raise ResearchReject(f"{prefix}:{binding['role']}")
    return manifest


def load_bars(manifest: dict[str, Any]) -> dict[date, dict[str, Bar]]:
    dataset = manifest["dataset"]
    bundle_path = REPO_ROOT / dataset["bundle_path"]
    gzip_path = REPO_ROOT / dataset["normalized_gzip_path"]
    if file_sha256(bundle_path) != EXPECTED_SOURCE_BUNDLE_SHA256:
        raise ResearchReject("DATA_REJECT:SOURCE_BUNDLE_SHA256")
    if file_sha256(gzip_path) != EXPECTED_SOURCE_GZIP_SHA256:
        raise ResearchReject("DATA_REJECT:SOURCE_GZIP_SHA256")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    compressed = gzip_path.read_bytes()
    raw = gzip.decompress(compressed)
    if raw_sha256(raw) != bundle["corpus"]["normalized_csv_sha256"]:
        raise ResearchReject("DATA_REJECT:NORMALIZED_CSV_SHA256")
    try:
        rows = list(csv.DictReader(io.StringIO(raw.decode("ascii"), newline="")))
    except (UnicodeDecodeError, csv.Error) as error:
        raise ResearchReject("DATA_REJECT:CSV") from error
    if len(rows) != EXPECTED_ROWS:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(rows)}")
    by_day: dict[date, dict[str, Bar]] = {}
    for index, row in enumerate(rows):
        if list(row) != ["symbol", "date", "open", "high", "low", "close", "quote_volume"]:
            raise ResearchReject("DATA_REJECT:COLUMNS")
        symbol = row["symbol"]
        if symbol not in SYMBOLS:
            raise ResearchReject(f"DATA_REJECT:SYMBOL:{symbol}")
        try:
            day = date.fromisoformat(row["date"])
            values = [D(row[field]) for field in ("open", "high", "low", "close")]
        except (ValueError, ArithmeticError) as error:
            raise ResearchReject(f"DATA_REJECT:VALUE:{index}") from error
        open_value, high, low, close = values
        if min(values) <= ZERO or high < max(open_value, low, close) or low > min(open_value, high, close):
            raise ResearchReject(f"DATA_REJECT:OHLC:{index}")
        if symbol in by_day.setdefault(day, {}):
            raise ResearchReject(f"DATA_REJECT:DUPLICATE:{day}:{symbol}")
        by_day[day][symbol] = Bar(symbol, day, open_value, high, low, close)
    days = sorted(by_day)
    if len(days) != 1827 or days[0] != date(2020, 1, 1) or days[-1] != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:DAY_RANGE")
    if any(set(by_day[day]) != set(SYMBOLS) for day in days):
        raise ResearchReject("DATA_REJECT:INTERSECTION")
    return by_day


def buy_with_budget(
    lots: list[Lot], *, budget: D, mark: D, fee: D, slippage: D, day: date
) -> tuple[D, D, D]:
    execution_price = mark * (ONE + slippage)
    quantity = budget / (execution_price * (ONE + fee))
    notional = quantity * execution_price
    fee_paid = notional * fee
    lots.append(Lot(quantity, notional + fee_paid, day))
    return quantity, fee_paid, notional


def sell_mark_value(
    lots: list[Lot], *, mark_value: D, mark: D, fee: D, slippage: D, day: date
) -> tuple[D, D, D, D, list[D]]:
    quantity_to_sell = mark_value / mark
    available = sum((lot.quantity for lot in lots), ZERO)
    if quantity_to_sell > available:
        raise ResearchReject("ECONOMIC_REJECT:SELL_EXCEEDS_POSITION")
    execution_price = mark * (ONE - slippage)
    gross = quantity_to_sell * execution_price
    fee_paid = gross * fee
    net = gross - fee_paid
    remaining = quantity_to_sell
    allocated_cost = ZERO
    hold_days: list[D] = []
    while remaining > ZERO:
        lot = lots[0]
        take = min(remaining, lot.quantity)
        fraction = take / lot.quantity
        cost = lot.cost_basis * fraction
        allocated_cost += cost
        hold_days.append(D((day - lot.entry_day).days))
        lot.quantity -= take
        lot.cost_basis -= cost
        remaining -= take
        if lot.quantity == ZERO:
            lots.pop(0)
    realized = net - allocated_cost
    return net, realized, fee_paid, gross, hold_days


def annualized_return(total_return_fraction: D, days: int) -> D:
    if total_return_fraction <= -ONE:
        return D("-100")
    value = math.pow(float(ONE + total_return_fraction), 365.25 / days) - 1.0
    return D(str(value)) * HUNDRED


def simulate(
    by_day: dict[date, dict[str, Bar]],
    *,
    window: tuple[date, date],
    fee: D,
    slippage: D,
    rebalance: bool,
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    days = [day for day in sorted(by_day) if start <= day < end]
    if not days or days[0] != start or days[-1].toordinal() + 1 != end.toordinal():
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start}:{end}")
    lots = {symbol: [] for symbol in SYMBOLS}
    quantities = {symbol: ZERO for symbol in SYMBOLS}
    fees = ZERO
    turnover = ZERO
    realized = ZERO
    realized_slices: list[D] = []
    holding_days: list[D] = []
    signal_count = 0
    trade_count = 0

    first = by_day[days[0]]
    for symbol in SYMBOLS:
        quantity, paid_fee, notional = buy_with_budget(
            lots[symbol],
            budget=INITIAL_EQUITY / D("2"),
            mark=first[symbol].open,
            fee=fee,
            slippage=slippage,
            day=days[0],
        )
        quantities[symbol] += quantity
        fees += paid_fee
        turnover += notional
        trade_count += 1

    peak = ZERO
    max_drawdown = ZERO
    underwater_start: date | None = None
    maximum_underwater_days = 0
    weight_error_sum = ZERO
    maximum_weight_error = ZERO
    observations = 0

    for index, day in enumerate(days):
        bars = by_day[day]
        if rebalance and index > 0 and day.day == 1:
            values = {symbol: quantities[symbol] * bars[symbol].open for symbol in SYMBOLS}
            high_symbol = max(SYMBOLS, key=lambda symbol: values[symbol])
            low_symbol = min(SYMBOLS, key=lambda symbol: values[symbol])
            difference = values[high_symbol] - values[low_symbol]
            if difference <= ZERO:
                raise ResearchReject(f"ECONOMIC_REJECT:NO_REBALANCE_DIFFERENCE:{day}")
            transfer_ratio = (
                (ONE - slippage) * (ONE - fee) / ((ONE + slippage) * (ONE + fee))
            )
            sell_mark = difference / (ONE + transfer_ratio)
            net, trade_realized, sell_fee, sell_notional, holds = sell_mark_value(
                lots[high_symbol],
                mark_value=sell_mark,
                mark=bars[high_symbol].open,
                fee=fee,
                slippage=slippage,
                day=day,
            )
            quantities[high_symbol] -= sell_mark / bars[high_symbol].open
            bought, buy_fee, buy_notional = buy_with_budget(
                lots[low_symbol],
                budget=net,
                mark=bars[low_symbol].open,
                fee=fee,
                slippage=slippage,
                day=day,
            )
            quantities[low_symbol] += bought
            realized += trade_realized
            realized_slices.append(trade_realized)
            holding_days.extend(holds)
            fees += sell_fee + buy_fee
            turnover += sell_notional + buy_notional
            signal_count += 1
            trade_count += 2

        close_values = {
            symbol: quantities[symbol] * bars[symbol].close for symbol in SYMBOLS
        }
        equity = sum(close_values.values(), ZERO)
        if equity <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NON_POSITIVE_EQUITY")
        weight = close_values["BTCUSDT"] / equity
        error = abs(weight - D("0.5"))
        weight_error_sum += error
        maximum_weight_error = max(maximum_weight_error, error)
        observations += 1
        if equity >= peak:
            peak = equity
            underwater_start = None
        else:
            drawdown = (peak - equity) / peak * HUNDRED
            max_drawdown = max(max_drawdown, drawdown)
            if underwater_start is None:
                underwater_start = day
            maximum_underwater_days = max(
                maximum_underwater_days, (day - underwater_start).days + 1
            )

    final_day = days[-1]
    final_bars = by_day[final_day]
    final_values = {
        symbol: quantities[symbol] * final_bars[symbol].close for symbol in SYMBOLS
    }
    final_equity = sum(final_values.values(), ZERO)
    total_pnl = final_equity - INITIAL_EQUITY
    unrealized = total_pnl - realized
    total_return = total_pnl / INITIAL_EQUITY * HUNDRED
    annualized = annualized_return(total_pnl / INITIAL_EQUITY, len(days))
    calmar = ZERO if max_drawdown == ZERO else annualized / max_drawdown
    terminal_proceeds = sum(
        value * (ONE - slippage) * (ONE - fee) for value in final_values.values()
    )
    terminal_return = (terminal_proceeds - INITIAL_EQUITY) / INITIAL_EQUITY * HUNDRED
    positive_realized = [value for value in realized_slices if value > ZERO]
    median_hold = None if not holding_days else D(str(median(holding_days)))
    p90_hold = percentile(holding_days, D("0.9"))
    oldest_inventory = max(
        D((end - lot.entry_day).days)
        for symbol in SYMBOLS
        for lot in lots[symbol]
    )
    metrics = {
        "total_return_pct": q(total_return),
        "annualized_return_pct": q(annualized),
        "realized_pnl_usdt": q(realized),
        "unrealized_pnl_usdt": q(unrealized),
        "maximum_drawdown_pct": q(max_drawdown),
        "maximum_underwater_duration_days": maximum_underwater_days,
        "calmar_ratio": q(calmar),
        "terminal_liquidation_adjusted_return_pct": q(terminal_return),
        "terminal_liquidation_cost_pct": q(total_return - terminal_return),
        "fees_usdt": q(fees),
        "turnover_usdt": q(turnover),
        "turnover_to_initial_equity": q(turnover / INITIAL_EQUITY),
        "monthly_rebalance_signal_count": signal_count,
        "trade_count": trade_count,
        "average_absolute_btc_weight_error_pp": q(weight_error_sum / D(observations) * HUNDRED),
        "maximum_absolute_btc_weight_error_pp": q(maximum_weight_error * HUNDRED),
        "terminal_btc_weight_pct": q(final_values["BTCUSDT"] / final_equity * HUNDRED),
        "terminal_eth_weight_pct": q(final_values["ETHUSDT"] / final_equity * HUNDRED),
        "median_realized_lot_hold_days": None if median_hold is None else q(median_hold),
        "p90_realized_lot_hold_days": None if p90_hold is None else q(p90_hold),
        "terminal_oldest_lot_age_days": q(oldest_inventory),
        "top_positive_realized_slice_contribution_pct": (
            None
            if not positive_realized
            else q(max(positive_realized) / sum(positive_realized, ZERO) * HUNDRED)
        ),
        "terminal_inventory": {
            symbol: {
                "quantity": q(quantities[symbol]),
                "market_value_usdt": q(final_values[symbol]),
                "fifo_cost_basis_usdt": q(sum((lot.cost_basis for lot in lots[symbol]), ZERO)),
                "lot_count": len(lots[symbol]),
            }
            for symbol in SYMBOLS
        },
    }
    raw_metrics = {
        "total_return": total_return,
        "terminal_return": terminal_return,
        "drawdown": max_drawdown,
        "calmar": calmar,
        "annualized": annualized,
    }
    return metrics, raw_metrics


def paired_result(
    by_day: dict[date, dict[str, Bar]],
    *,
    window: tuple[date, date],
    fee: D,
    slippage: D,
) -> tuple[dict[str, Any], dict[str, D]]:
    candidate, candidate_raw = simulate(
        by_day, window=window, fee=fee, slippage=slippage, rebalance=True
    )
    parent, parent_raw = simulate(
        by_day, window=window, fee=fee, slippage=slippage, rebalance=False
    )
    incremental = candidate_raw["total_return"] - parent_raw["total_return"]
    terminal_incremental = candidate_raw["terminal_return"] - parent_raw["terminal_return"]
    comparison = {
        "candidate_minus_parent_total_return_pp": q(incremental),
        "candidate_minus_parent_terminal_liquidation_return_pp": q(terminal_incremental),
        "candidate_minus_parent_maximum_drawdown_pp": q(
            candidate_raw["drawdown"] - parent_raw["drawdown"]
        ),
        "candidate_minus_parent_calmar_ratio": q(
            candidate_raw["calmar"] - parent_raw["calmar"]
        ),
    }
    return {
        "start": window[0].isoformat(),
        "end_exclusive": window[1].isoformat(),
        "candidate_monthly_equal_weight": candidate,
        "parent_initial_equal_value_no_rebalance": parent,
        "comparison": comparison,
    }, {
        "candidate_total": candidate_raw["total_return"],
        "candidate_terminal": candidate_raw["terminal_return"],
        "candidate_drawdown": candidate_raw["drawdown"],
        "candidate_calmar": candidate_raw["calmar"],
        "parent_total": parent_raw["total_return"],
        "parent_terminal": parent_raw["terminal_return"],
        "parent_drawdown": parent_raw["drawdown"],
        "parent_calmar": parent_raw["calmar"],
        "incremental": incremental,
        "terminal_incremental": terminal_incremental,
    }


def evaluate_gates(
    window_raw: dict[str, dict[str, dict[str, D]]],
    annual_raw: dict[str, dict[str, dict[str, D]]],
    window_results: dict[str, dict[str, dict[str, Any]]],
) -> tuple[list[str], list[str], dict[str, Any]]:
    passed: list[str] = []
    failed: list[str] = []

    def gate(name: str, condition: bool) -> None:
        (passed if condition else failed).append(name)

    for window_name in WINDOWS:
        for scenario in SCENARIOS:
            raw = window_raw[window_name][scenario]
            prefix = f"{window_name.lower()}_{scenario.lower()}"
            gate(f"{prefix}_candidate_total_return_positive", raw["candidate_total"] > ZERO)
            gate(f"{prefix}_incremental_total_return_positive", raw["incremental"] > ZERO)
            gate(f"{prefix}_terminal_liquidation_incremental_positive", raw["terminal_incremental"] > ZERO)
            gate(f"{prefix}_drawdown_non_worse", raw["candidate_drawdown"] <= raw["parent_drawdown"])
            gate(f"{prefix}_calmar_non_worse", raw["candidate_calmar"] >= raw["parent_calmar"])
        normal_metrics = window_results[window_name]["NORMAL"]["candidate_monthly_equal_weight"]
        gate(
            f"{window_name.lower()}_expected_monthly_signal_count",
            normal_metrics["monthly_rebalance_signal_count"] == EXPECTED_SIGNAL_COUNTS[window_name],
        )

    breadth: dict[str, Any] = {}
    for scenario in SCENARIOS:
        raw_by_year = {year: annual_raw[year][scenario] for year in ANNUAL}
        candidate_positive = sum(raw["candidate_total"] > ZERO for raw in raw_by_year.values())
        incremental_positive = sum(raw["incremental"] > ZERO for raw in raw_by_year.values())
        drawdown_non_worse = sum(
            raw["candidate_drawdown"] <= raw["parent_drawdown"] for raw in raw_by_year.values()
        )
        calmar_non_worse = sum(
            raw["candidate_calmar"] >= raw["parent_calmar"] for raw in raw_by_year.values()
        )
        positive_increments = [raw["incremental"] for raw in raw_by_year.values() if raw["incremental"] > ZERO]
        top_year_share = (
            D("100")
            if not positive_increments
            else max(positive_increments) / sum(positive_increments, ZERO) * HUNDRED
        )
        breadth[scenario] = {
            "candidate_positive_years": candidate_positive,
            "incremental_positive_years": incremental_positive,
            "drawdown_non_worse_years": drawdown_non_worse,
            "calmar_non_worse_years": calmar_non_worse,
            "top_year_positive_incremental_contribution_pct": q(top_year_share),
        }
        prefix = scenario.lower()
        gate(f"annual_{prefix}_candidate_positive_at_least_4_of_5", candidate_positive >= 4)
        gate(f"annual_{prefix}_incremental_positive_at_least_4_of_5", incremental_positive >= 4)
        gate(f"annual_{prefix}_drawdown_non_worse_at_least_4_of_5", drawdown_non_worse >= 4)
        gate(f"annual_{prefix}_calmar_non_worse_at_least_4_of_5", calmar_non_worse >= 4)
        gate(f"annual_{prefix}_top_year_contribution_at_most_60pct", top_year_share <= D("60"))
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
            result, raw = paired_result(
                by_day, window=window, fee=fee, slippage=slippage
            )
            window_results[window_name][scenario] = result
            window_raw[window_name][scenario] = raw

    annual_results: dict[str, dict[str, dict[str, Any]]] = {}
    annual_raw: dict[str, dict[str, dict[str, D]]] = {}
    for year, window in ANNUAL.items():
        annual_results[year] = {}
        annual_raw[year] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            result, raw = paired_result(
                by_day, window=window, fee=fee, slippage=slippage
            )
            annual_results[year][scenario] = result
            annual_raw[year][scenario] = raw

    passed, failed, breadth = evaluate_gates(window_raw, annual_raw, window_results)
    status = (
        "CANDIDATE_ELIGIBLE_HISTORICAL_PASS_REPORTED_NOT_ACTIVATED_OOS_UNOPENED"
        if not failed
        else "NO_CANDIDATE_CLOSE_BTC_ETH_MONTHLY_EQUAL_WEIGHT_REBALANCING_FAMILY"
    )
    result = {
        "schema_version": "1",
        "document_type": "BTC_ETH_MONTHLY_EQUAL_WEIGHT_REBALANCING_HISTORICAL_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "experiment_id": EXPERIMENT_ID,
        "status": status,
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": file_sha256(manifest_path),
        },
        "dataset": {
            "bundle_sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
            "normalized_gzip_sha256": EXPECTED_SOURCE_GZIP_SHA256,
            "rows": EXPECTED_ROWS,
            "first_day": "2020-01-01",
            "last_day": "2024-12-31",
        },
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
