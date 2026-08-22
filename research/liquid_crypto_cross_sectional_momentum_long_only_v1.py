#!/usr/bin/env python3
"""Deterministic matched-capital backtest for one frozen crypto momentum policy."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, getcontext
import gzip
import hashlib
import io
import json
from pathlib import Path
import statistics
from typing import Any


getcontext().prec = 40
REPO_ROOT = Path(__file__).resolve().parents[1]
D = Decimal
INITIAL_CAPITAL = D("10000")
MIN_HISTORY_DAYS = 365
LIQUIDITY_DAYS = 30
LIQUID_UNIVERSE_SIZE = 20
SELECTED_COUNT = 6
MOMENTUM_START_DAYS = 12
MOMENTUM_END_DAYS = 2
STALE_ZERO_RECOVERY_DAYS = 7
Q8 = D("0.00000001")
Q6 = D("0.000001")
EXPECTED_POLICY = {
    "cohort": "ASCII_USDT_SYMBOL_WITH_2019_12_DAILY_ARCHIVE_AFTER_FROZEN_STABLE_FIAT_WRAPPED_AND_LEVERAGED_EXCLUSIONS",
    "minimum_contiguous_history_days": 365,
    "liquidity": "MEDIAN_QUOTE_VOLUME_T_MINUS_30_THROUGH_T_MINUS_1",
    "liquid_universe_size": 20,
    "momentum": "CLOSE_T_MINUS_2_DIVIDED_BY_CLOSE_T_MINUS_12_MINUS_1",
    "selected_asset_count": 6,
    "ranking_ties": "DESCENDING_SIGNAL_THEN_DESCENDING_LIQUIDITY_THEN_ASCENDING_SYMBOL",
    "rebalance": "DAILY_AT_T_OPEN",
    "insufficient_universe": "ZERO_YIELD_CASH",
    "sizing": "EQUAL_WEIGHT_NO_LEVERAGE_NO_SHORT",
    "missing_execution_bar": "DO_NOT_TRADE_KEEP_STALE_POSITION",
    "stale_sensitivity": "ZERO_RECOVERY_AFTER_SEVEN_MISSING_COMPLETE_DAYS",
    "variants": 1,
}
EXPECTED_WINDOWS = {
    "design": ["2020-01-01", "2023-01-01"],
    "validation": ["2023-01-01", "2025-01-01"],
    "annual_years": [2020, 2021, 2022, 2023, 2024],
}
EXPECTED_COSTS = {
    "NORMAL": {"fee_rate_per_side": "0.0010", "adverse_slippage_rate_per_side": "0.0005"},
    "STRESS": {"fee_rate_per_side": "0.0020", "adverse_slippage_rate_per_side": "0.0010"},
}


class BacktestReject(RuntimeError):
    pass


@dataclass(frozen=True)
class Bar:
    day: date
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    quote_volume: Decimal


@dataclass
class Position:
    quantity: Decimal
    cost_basis: Decimal
    opened_day: date
    last_mark: Decimal
    last_bar_day: date


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def q8(value: Decimal) -> str:
    return format(value.quantize(Q8), "f")


def q6(value: Decimal) -> str:
    return format(value.quantize(Q6), "f")


def percentile(values: list[int], probability: Decimal) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = probability * D(len(ordered) - 1)
    lower = int(rank)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = rank - D(lower)
    return float(D(ordered[lower]) + (D(ordered[upper]) - D(ordered[lower])) * fraction)


def verify_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise BacktestReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("research_classification") != "HISTORICAL_PREREGISTERED_DESIGN_VALIDATION_NO_OOS":
        raise BacktestReject("MANIFEST_REJECT:CLASSIFICATION")
    if manifest.get("strategy_policy") != EXPECTED_POLICY:
        raise BacktestReject("MANIFEST_REJECT:POLICY")
    if manifest.get("windows") != EXPECTED_WINDOWS:
        raise BacktestReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("cost_scenarios") != EXPECTED_COSTS:
        raise BacktestReject("MANIFEST_REJECT:COSTS")
    if manifest.get("gate_set", {}).get("id") != "LIQUID_CRYPTO_CROSS_SECTIONAL_MOMENTUM_MATCHED_CAPITAL_GATES_V1":
        raise BacktestReject("MANIFEST_REJECT:GATES")
    for binding in manifest.get("source_bindings", []):
        bound_path = REPO_ROOT / binding["path"]
        if not bound_path.is_file() or sha256(bound_path.read_bytes()) != binding["sha256"]:
            raise BacktestReject(f"BINDING_REJECT:{binding['role']}")
    return manifest


def _decimal(raw: str, *, row: int, field: str) -> Decimal:
    try:
        value = D(raw)
    except Exception as error:
        raise BacktestReject(f"CORPUS_REJECT:DECIMAL:{row}:{field}") from error
    if not value.is_finite():
        raise BacktestReject(f"CORPUS_REJECT:DECIMAL:{row}:{field}")
    return value


def load_corpus(manifest: dict[str, Any]) -> tuple[dict[str, dict[date, Bar]], list[str], dict[str, Any]]:
    bundle_binding = next(
        (binding for binding in manifest["source_bindings"] if binding["role"] == "SEALED_CHECKSUM_VERIFIED_CORPUS_BUNDLE"),
        None,
    )
    if bundle_binding is None:
        raise BacktestReject("MANIFEST_REJECT:CORPUS_BINDING")
    bundle = json.loads((REPO_ROOT / bundle_binding["path"]).read_text(encoding="utf-8"))
    if bundle.get("status") != "SEALED_CHECKSUM_VERIFIED_PRE_2025_CORPUS_NO_STRATEGY_OUTCOME":
        raise BacktestReject("CORPUS_REJECT:STATUS")
    corpus = bundle["corpus"]
    compressed_path = REPO_ROOT / corpus["normalized_gzip_path"]
    compressed = compressed_path.read_bytes()
    if sha256(compressed) != corpus["normalized_gzip_sha256"]:
        raise BacktestReject("CORPUS_REJECT:GZIP_SHA256")
    try:
        raw = gzip.decompress(compressed)
    except OSError as error:
        raise BacktestReject("CORPUS_REJECT:GZIP") from error
    if sha256(raw) != corpus["normalized_csv_sha256"]:
        raise BacktestReject("CORPUS_REJECT:CSV_SHA256")
    reader = csv.DictReader(io.StringIO(raw.decode("ascii"), newline=""))
    expected_columns = ["symbol", "date", "open", "high", "low", "close", "quote_volume"]
    if reader.fieldnames != expected_columns:
        raise BacktestReject("CORPUS_REJECT:HEADER")
    by_symbol: dict[str, dict[date, Bar]] = {}
    seen: set[tuple[str, date]] = set()
    for index, row in enumerate(reader):
        try:
            day = date.fromisoformat(row["date"])
        except ValueError as error:
            raise BacktestReject(f"CORPUS_REJECT:DATE:{index}") from error
        symbol = row["symbol"]
        identity = (symbol, day)
        if identity in seen:
            raise BacktestReject(f"CORPUS_REJECT:DUPLICATE:{symbol}:{day}")
        seen.add(identity)
        open_value = _decimal(row["open"], row=index, field="open")
        high = _decimal(row["high"], row=index, field="high")
        low = _decimal(row["low"], row=index, field="low")
        close = _decimal(row["close"], row=index, field="close")
        quote_volume = _decimal(row["quote_volume"], row=index, field="quote_volume")
        if min(open_value, high, low, close) <= 0 or quote_volume < 0:
            raise BacktestReject(f"CORPUS_REJECT:RANGE:{index}")
        if high < max(open_value, low, close) or low > min(open_value, high, close):
            raise BacktestReject(f"CORPUS_REJECT:OHLC:{index}")
        by_symbol.setdefault(symbol, {})[day] = Bar(day, open_value, high, low, close, quote_volume)
    if len(seen) != corpus["row_count"]:
        raise BacktestReject("CORPUS_REJECT:ROW_COUNT")
    cohort = bundle["cohort"]["symbols"]
    if sorted(by_symbol) != sorted(cohort):
        raise BacktestReject("CORPUS_REJECT:COHORT_SYMBOLS")
    return by_symbol, cohort, bundle


def build_streaks(by_symbol: dict[str, dict[date, Bar]]) -> dict[str, dict[date, int]]:
    result: dict[str, dict[date, int]] = {}
    for symbol, bars in by_symbol.items():
        streaks: dict[date, int] = {}
        prior: date | None = None
        streak = 0
        for day in sorted(bars):
            streak = streak + 1 if prior is not None and day - prior == timedelta(days=1) else 1
            streaks[day] = streak
            prior = day
        result[symbol] = streaks
    return result


def median_decimal(values: list[Decimal]) -> Decimal:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D(2)


def build_targets(
    by_symbol: dict[str, dict[date, Bar]], cohort: list[str], start: date, end: date
) -> dict[date, dict[str, Any]]:
    streaks = build_streaks(by_symbol)
    targets: dict[date, dict[str, Any]] = {}
    day = start
    while day < end:
        liquid: list[tuple[str, Decimal, Decimal]] = []
        for symbol in cohort:
            bars = by_symbol[symbol]
            yesterday = day - timedelta(days=1)
            if day not in bars or streaks[symbol].get(yesterday, 0) < MIN_HISTORY_DAYS:
                continue
            volume_days = [day - timedelta(days=offset) for offset in range(1, LIQUIDITY_DAYS + 1)]
            momentum_start = day - timedelta(days=MOMENTUM_START_DAYS)
            momentum_end = day - timedelta(days=MOMENTUM_END_DAYS)
            if any(required not in bars for required in volume_days + [momentum_start, momentum_end]):
                continue
            liquidity = median_decimal([bars[value].quote_volume for value in volume_days])
            if liquidity <= 0:
                continue
            momentum = bars[momentum_end].close / bars[momentum_start].close - D(1)
            liquid.append((symbol, liquidity, momentum))
        top_liquid = sorted(liquid, key=lambda item: (-item[1], item[0]))[:LIQUID_UNIVERSE_SIZE]
        if len(top_liquid) == LIQUID_UNIVERSE_SIZE:
            selected = sorted(top_liquid, key=lambda item: (-item[2], -item[1], item[0]))[:SELECTED_COUNT]
            parent_symbols = [item[0] for item in top_liquid]
            candidate_symbols = [item[0] for item in selected]
        else:
            parent_symbols = []
            candidate_symbols = []
        targets[day] = {
            "eligible_count": len(liquid),
            "parent": parent_symbols,
            "candidate": candidate_symbols,
        }
        day += timedelta(days=1)
    return targets


class PortfolioEngine:
    def __init__(self, fee: Decimal, slippage: Decimal) -> None:
        self.fee = fee
        self.slippage = slippage
        self.cash = INITIAL_CAPITAL
        self.positions: dict[str, Position] = {}
        self.realized_by_asset: dict[str, Decimal] = {}
        self.trading_cost = D(0)
        self.turnover = D(0)
        self.completed_holds: list[int] = []
        self.equity_curve: list[tuple[date, Decimal]] = []
        self.utilization: list[Decimal] = []
        self.trade_count = 0

    def _mark(self, symbol: str, day: date, bars: dict[str, dict[date, Bar]], *, close: bool) -> Decimal:
        position = self.positions[symbol]
        bar = bars[symbol].get(day)
        if bar is not None:
            price = bar.close if close else bar.open
            position.last_mark = price
            position.last_bar_day = day
            return price
        return position.last_mark

    def _sell(self, symbol: str, quantity: Decimal, day: date, open_price: Decimal) -> None:
        if quantity <= 0:
            return
        position = self.positions[symbol]
        quantity = min(quantity, position.quantity)
        fraction = quantity / position.quantity
        allocated_cost = position.cost_basis * fraction
        market_notional = quantity * open_price
        net = quantity * open_price * (D(1) - self.slippage) * (D(1) - self.fee)
        self.cash += net
        realized = net - allocated_cost
        self.realized_by_asset[symbol] = self.realized_by_asset.get(symbol, D(0)) + realized
        self.trading_cost += market_notional - net
        self.turnover += market_notional
        self.trade_count += 1
        position.quantity -= quantity
        position.cost_basis -= allocated_cost
        if position.quantity <= D("1e-24"):
            self.completed_holds.append((day - position.opened_day).days)
            del self.positions[symbol]

    def _buy(self, symbol: str, quantity: Decimal, day: date, open_price: Decimal) -> None:
        if quantity <= 0:
            return
        market_notional = quantity * open_price
        cash_cost = quantity * open_price * (D(1) + self.slippage) * (D(1) + self.fee)
        if cash_cost > self.cash:
            scale = self.cash / cash_cost if cash_cost > 0 else D(0)
            quantity *= scale
            market_notional = quantity * open_price
            cash_cost = quantity * open_price * (D(1) + self.slippage) * (D(1) + self.fee)
        if cash_cost <= 0:
            return
        self.cash -= cash_cost
        self.trading_cost += cash_cost - market_notional
        self.turnover += market_notional
        self.trade_count += 1
        if symbol in self.positions:
            position = self.positions[symbol]
            position.quantity += quantity
            position.cost_basis += cash_cost
            position.last_mark = open_price
            position.last_bar_day = day
        else:
            self.positions[symbol] = Position(quantity, cash_cost, day, open_price, day)

    def rebalance(self, day: date, targets: list[str], bars: dict[str, dict[date, Bar]]) -> None:
        open_marks = {
            symbol: self._mark(symbol, day, bars, close=False)
            for symbol in list(self.positions)
        }
        tradable_positions = {
            symbol for symbol in self.positions if day in bars[symbol]
        }
        stale_value = sum(
            self.positions[symbol].quantity * open_marks[symbol]
            for symbol in self.positions
            if symbol not in tradable_positions
        )
        equity = self.cash + sum(
            self.positions[symbol].quantity * open_marks[symbol]
            for symbol in self.positions
        )
        target_set = set(targets)
        provisional = max(equity - stale_value, D(0)) / D(len(targets)) if targets else D(0)
        for symbol in sorted(list(self.positions)):
            if symbol not in tradable_positions:
                continue
            current_value = self.positions[symbol].quantity * open_marks[symbol]
            desired = provisional if symbol in target_set else D(0)
            if current_value > desired:
                self._sell(symbol, (current_value - desired) / open_marks[symbol], day, open_marks[symbol])
        available = self.cash + sum(
            position.quantity * bars[symbol][day].open
            for symbol, position in self.positions.items()
            if symbol in target_set and day in bars[symbol]
        )
        desired = available / D(len(targets)) if targets else D(0)
        needs: list[tuple[str, Decimal, Decimal]] = []
        for symbol in targets:
            open_price = bars[symbol][day].open
            current = self.positions.get(symbol)
            current_value = D(0) if current is None else current.quantity * open_price
            if current_value < desired:
                quantity = (desired - current_value) / open_price
                cash_cost = quantity * open_price * (D(1) + self.slippage) * (D(1) + self.fee)
                needs.append((symbol, quantity, cash_cost))
        total_need = sum(item[2] for item in needs)
        scale = min(D(1), self.cash / total_need) if total_need > 0 else D(0)
        for symbol, quantity, _ in needs:
            self._buy(symbol, quantity * scale, day, bars[symbol][day].open)

    def track_close(self, day: date, bars: dict[str, dict[date, Bar]]) -> None:
        marked = sum(
            position.quantity * self._mark(symbol, day, bars, close=True)
            for symbol, position in list(self.positions.items())
        )
        equity = self.cash + marked
        self.equity_curve.append((day, equity))
        self.utilization.append(marked / equity if equity > 0 else D(0))

    def result(self, end: date) -> dict[str, Any]:
        realized = sum(self.realized_by_asset.values(), D(0))
        unrealized_by_asset = {
            symbol: position.quantity * position.last_mark - position.cost_basis
            for symbol, position in self.positions.items()
        }
        unrealized = sum(unrealized_by_asset.values(), D(0))
        total = realized + unrealized
        equity = self.cash + sum(
            position.quantity * position.last_mark for position in self.positions.values()
        )
        if abs((equity - INITIAL_CAPITAL) - total) > D("0.000001"):
            raise BacktestReject("LEDGER_REJECT:TOTAL_RECONCILIATION")
        peak = INITIAL_CAPITAL
        maximum_drawdown = D(0)
        current_underwater = 0
        maximum_underwater = 0
        for _, value in self.equity_curve:
            if value >= peak:
                peak = value
                current_underwater = 0
            else:
                current_underwater += 1
                maximum_underwater = max(maximum_underwater, current_underwater)
                maximum_drawdown = max(maximum_drawdown, (peak - value) / peak * D(100))
        zero_recovery_unrealized = D(0)
        terminal: list[dict[str, Any]] = []
        terminal_holds: list[int] = []
        for symbol, position in sorted(self.positions.items()):
            stale_days = (end - position.last_bar_day).days
            mark = D(0) if stale_days >= STALE_ZERO_RECOVERY_DAYS else position.last_mark
            zero_recovery_unrealized += position.quantity * mark - position.cost_basis
            age = (end - position.opened_day).days
            terminal_holds.append(age)
            terminal.append(
                {
                    "symbol": symbol,
                    "age_days": age,
                    "stale_days": stale_days,
                    "cost_basis_usdt": q8(position.cost_basis),
                    "last_mark_value_usdt": q8(position.quantity * position.last_mark),
                    "zero_recovery_value_usdt": q8(position.quantity * mark),
                }
            )
        asset_total = dict(self.realized_by_asset)
        for symbol, value in unrealized_by_asset.items():
            asset_total[symbol] = asset_total.get(symbol, D(0)) + value
        gross_before_cost = total + self.trading_cost
        return {
            "initial_capital_usdt": q8(INITIAL_CAPITAL),
            "ending_equity_usdt": q8(equity),
            "realized_pnl_usdt": q8(realized),
            "unrealized_pnl_usdt": q8(unrealized),
            "total_pnl_usdt": q8(total),
            "total_return_pct": q8(total / INITIAL_CAPITAL * D(100)),
            "zero_recovery_total_pnl_usdt": q8(realized + zero_recovery_unrealized),
            "max_drawdown_pct": q6(maximum_drawdown),
            "maximum_underwater_days": maximum_underwater,
            "turnover_usdt": q8(self.turnover),
            "trading_cost_usdt": q8(self.trading_cost),
            "gross_pnl_before_trading_cost_usdt": q8(gross_before_cost),
            "trading_cost_to_gross_positive_pnl_pct": None if gross_before_cost <= 0 else q6(self.trading_cost / gross_before_cost * D(100)),
            "average_utilization_pct": q6((sum(self.utilization, D(0)) / D(len(self.utilization)) * D(100)) if self.utilization else D(0)),
            "trade_count": self.trade_count,
            "completed_episode_count": len(self.completed_holds),
            "median_completed_hold_days": None if not self.completed_holds else statistics.median(self.completed_holds),
            "p90_completed_hold_days": percentile(self.completed_holds, D("0.90")),
            "terminal_position_count": len(self.positions),
            "terminal_median_hold_days": None if not terminal_holds else statistics.median(terminal_holds),
            "terminal_p90_hold_days": percentile(terminal_holds, D("0.90")),
            "terminal_inventory": terminal,
            "asset_total_pnl_usdt": {symbol: q8(value) for symbol, value in sorted(asset_total.items())},
            "ledger_reconciled": True,
        }


def simulate(
    bars: dict[str, dict[date, Bar]], targets: dict[date, dict[str, Any]],
    start: date, end: date, target_key: str, fee: Decimal, slippage: Decimal,
) -> dict[str, Any]:
    engine = PortfolioEngine(fee, slippage)
    day = start
    while day < end:
        engine.rebalance(day, targets[day][target_key], bars)
        engine.track_close(day, bars)
        day += timedelta(days=1)
    return engine.result(end)


def paired_summary(candidate: dict[str, Any], parent: dict[str, Any]) -> dict[str, Any]:
    candidate_assets = {key: D(value) for key, value in candidate["asset_total_pnl_usdt"].items()}
    parent_assets = {key: D(value) for key, value in parent["asset_total_pnl_usdt"].items()}
    increments = {
        symbol: candidate_assets.get(symbol, D(0)) - parent_assets.get(symbol, D(0))
        for symbol in sorted(set(candidate_assets) | set(parent_assets))
    }
    positives = {symbol: value for symbol, value in increments.items() if value > 0}
    positive_total = sum(positives.values(), D(0))
    top_asset_pct = D(0) if positive_total <= 0 else max(positives.values()) / positive_total * D(100)
    return {
        "total_pnl_delta_usdt": q8(D(candidate["total_pnl_usdt"]) - D(parent["total_pnl_usdt"])),
        "total_return_delta_pp": q8(D(candidate["total_return_pct"]) - D(parent["total_return_pct"])),
        "drawdown_delta_pp": q6(D(candidate["max_drawdown_pct"]) - D(parent["max_drawdown_pct"])),
        "zero_recovery_total_pnl_delta_usdt": q8(D(candidate["zero_recovery_total_pnl_usdt"]) - D(parent["zero_recovery_total_pnl_usdt"])),
        "top_asset_positive_incremental_contribution_pct": q6(top_asset_pct),
        "positive_incremental_asset_count": len(positives),
        "asset_incremental_pnl_usdt": {symbol: q8(value) for symbol, value in increments.items()},
    }


def run_window(
    bars: dict[str, dict[date, Bar]], targets: dict[date, dict[str, Any]],
    start: date, end: date, costs: dict[str, str],
) -> dict[str, Any]:
    fee = D(costs["fee_rate_per_side"])
    slippage = D(costs["adverse_slippage_rate_per_side"])
    candidate = simulate(bars, targets, start, end, "candidate", fee, slippage)
    parent = simulate(bars, targets, start, end, "parent", fee, slippage)
    return {"candidate": candidate, "parent": parent, "paired": paired_summary(candidate, parent)}


def concentration(values: list[Decimal]) -> Decimal:
    positives = [value for value in values if value > 0]
    return D(0) if not positives else max(positives) / sum(positives, D(0)) * D(100)


def execute(manifest_path: Path) -> dict[str, Any]:
    manifest = verify_manifest(manifest_path)
    bars, cohort, bundle = load_corpus(manifest)
    targets = build_targets(bars, cohort, date(2020, 1, 1), date(2025, 1, 1))
    design_days = [value for day, value in targets.items() if day < date(2023, 1, 1)]
    validation_days = [value for day, value in targets.items() if day >= date(2023, 1, 1)]
    coverage = {
        "design_days": len(design_days),
        "design_at_least_20_eligible_days": sum(value["eligible_count"] >= 20 for value in design_days),
        "design_at_least_20_eligible_pct": q6(D(sum(value["eligible_count"] >= 20 for value in design_days)) / D(len(design_days)) * D(100)),
        "validation_days": len(validation_days),
        "validation_at_least_20_eligible_days": sum(value["eligible_count"] >= 20 for value in validation_days),
        "validation_at_least_20_eligible_pct": q6(D(sum(value["eligible_count"] >= 20 for value in validation_days)) / D(len(validation_days)) * D(100)),
    }
    windows: dict[str, Any] = {}
    for scenario, costs in EXPECTED_COSTS.items():
        windows[scenario] = {
            "design": run_window(bars, targets, date(2020, 1, 1), date(2023, 1, 1), costs),
            "validation": run_window(bars, targets, date(2023, 1, 1), date(2025, 1, 1), costs),
        }
    annual: dict[str, Any] = {}
    for year in EXPECTED_WINDOWS["annual_years"]:
        annual[str(year)] = {
            scenario: run_window(bars, targets, date(year, 1, 1), date(year + 1, 1, 1), costs)
            for scenario, costs in EXPECTED_COSTS.items()
        }
    annual_normal_deltas = [D(annual[str(year)]["NORMAL"]["paired"]["total_pnl_delta_usdt"]) for year in EXPECTED_WINDOWS["annual_years"]]
    annual_total_wins = sum(value > 0 for value in annual_normal_deltas)
    annual_dd_non_worse = sum(
        D(annual[str(year)]["NORMAL"]["candidate"]["max_drawdown_pct"])
        <= D(annual[str(year)]["NORMAL"]["parent"]["max_drawdown_pct"])
        for year in EXPECTED_WINDOWS["annual_years"]
    )
    normal_design = windows["NORMAL"]["design"]
    normal_validation = windows["NORMAL"]["validation"]
    stress_design = windows["STRESS"]["design"]
    stress_validation = windows["STRESS"]["validation"]
    gates = {
        "design_eligible_coverage_at_least_80pct": D(coverage["design_at_least_20_eligible_pct"]) >= 80,
        "validation_eligible_coverage_at_least_80pct": D(coverage["validation_at_least_20_eligible_pct"]) >= 80,
        "normal_design_total_positive_and_above_parent": D(normal_design["candidate"]["total_pnl_usdt"]) > 0 and D(normal_design["paired"]["total_pnl_delta_usdt"]) > 0,
        "stress_design_total_positive_and_above_parent": D(stress_design["candidate"]["total_pnl_usdt"]) > 0 and D(stress_design["paired"]["total_pnl_delta_usdt"]) > 0,
        "normal_validation_total_positive_and_above_parent": D(normal_validation["candidate"]["total_pnl_usdt"]) > 0 and D(normal_validation["paired"]["total_pnl_delta_usdt"]) > 0,
        "stress_validation_total_positive_and_above_parent": D(stress_validation["candidate"]["total_pnl_usdt"]) > 0 and D(stress_validation["paired"]["total_pnl_delta_usdt"]) > 0,
        "normal_design_drawdown_within_parent_plus_2pp": D(normal_design["candidate"]["max_drawdown_pct"]) <= D(normal_design["parent"]["max_drawdown_pct"]) + 2,
        "normal_validation_drawdown_within_parent_plus_2pp": D(normal_validation["candidate"]["max_drawdown_pct"]) <= D(normal_validation["parent"]["max_drawdown_pct"]) + 2,
        "stress_validation_drawdown_within_normal_plus_3pp": D(stress_validation["candidate"]["max_drawdown_pct"]) <= D(normal_validation["candidate"]["max_drawdown_pct"]) + 3,
        "validation_zero_recovery_total_above_parent": D(normal_validation["paired"]["zero_recovery_total_pnl_delta_usdt"]) > 0,
        "annual_normal_total_wins_at_least_3_of_5": annual_total_wins >= 3,
        "annual_normal_drawdown_non_worse_at_least_3_of_5": annual_dd_non_worse >= 3,
        "validation_top_asset_positive_incremental_contribution_at_most_40pct": D(normal_validation["paired"]["top_asset_positive_incremental_contribution_pct"]) <= 40,
        "top_year_positive_incremental_contribution_at_most_60pct": concentration(annual_normal_deltas) <= 60,
        "validation_turnover_cost_at_most_35pct_of_gross_positive_pnl": normal_validation["candidate"]["trading_cost_to_gross_positive_pnl_pct"] is not None and D(normal_validation["candidate"]["trading_cost_to_gross_positive_pnl_pct"]) <= 35,
        "all_ledgers_reconciled": all(
            window[side]["ledger_reconciled"]
            for scenario in windows.values()
            for window in scenario.values()
            for side in ("candidate", "parent")
        ),
    }
    passed = all(gates.values())
    return {
        "schema_version": "1",
        "document_type": "LIQUID_CRYPTO_CROSS_SECTIONAL_MOMENTUM_LONG_ONLY_V1_RESULT",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "research_identity": "LIQUID_CRYPTO_CROSS_SECTIONAL_MOMENTUM_LONG_ONLY_V1",
        "status": "CANDIDATE_ELIGIBLE_PENDING_BYTE_IDENTICAL_REPLICATION" if passed else "NO_CANDIDATE_CLOSE_LIQUID_CRYPTO_CROSS_SECTIONAL_MOMENTUM_FAMILY",
        "candidate_created": False,
        "oos_opened": False,
        "bindings": {
            "manifest_path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "manifest_sha256": sha256(manifest_path.read_bytes()),
            "corpus_bundle_sha256": sha256((REPO_ROOT / next(binding["path"] for binding in manifest["source_bindings"] if binding["role"] == "SEALED_CHECKSUM_VERIFIED_CORPUS_BUNDLE")).read_bytes()),
            "normalized_gzip_sha256": bundle["corpus"]["normalized_gzip_sha256"],
        },
        "cohort": {"symbol_count": len(cohort), "symbols_sha256": bundle["cohort"]["symbols_sha256"]},
        "coverage": coverage,
        "windows": windows,
        "annual": annual,
        "breadth": {
            "annual_normal_total_wins": annual_total_wins,
            "annual_normal_drawdown_non_worse": annual_dd_non_worse,
            "top_year_positive_incremental_contribution_pct": q6(concentration(annual_normal_deltas)),
        },
        "primary_gates": gates,
        "recommended_next_action": "RUN_SECOND_BYTE_IDENTICAL_REPLICATION_AND_FREEZE_CANDIDATE_WITHOUT_OOS" if passed else "PERMANENTLY_CLOSE_EXACT_FAMILY_WITHOUT_COHORT_FORMATION_LIQUIDITY_PORTFOLIO_OR_COST_TUNING",
        "scope_note": "Historical Design and Validation only. No post-2024 OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    output_path = Path(args.output).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        output_path.relative_to(state_root)
    except ValueError as error:
        raise BacktestReject(f"PATH_REJECT:{output_path}") from error
    if output_path.exists():
        raise BacktestReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = execute(manifest_path)
    raw = canonical_bytes(result)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("xb") as target:
        target.write(raw)
    print(json.dumps({"status": result["status"], "output": output_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(raw)}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
