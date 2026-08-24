#!/usr/bin/env python3
"""Deterministic BTC NR7 volatility-contraction breakout historical screen."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
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
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_ROWS = 52_608
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}
WINDOWS = {
    "DESIGN": (datetime(2020, 1, 1), datetime(2023, 1, 1)),
    "VALIDATION": (datetime(2023, 1, 1), datetime(2025, 1, 1)),
}
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
LOOKBACKS = (5, 7, 10)
PRIMARY_LOOKBACK = 7
HOLD_HOURS = 168
EXPERIMENT_ID = "btc-daily-nr7-volatility-contraction-breakout-historical-v1"
MANIFEST_TYPE = "BTC_DAILY_NR7_VOLATILITY_CONTRACTION_BREAKOUT_HISTORICAL_MANIFEST_V1"
GATE_ID = "BTC_DAILY_NR7_VOLATILITY_CONTRACTION_BREAKOUT_MATCHED_CONTROL_GATES_V1"
REPO_ROOT = Path(__file__).resolve().parents[1]


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class Bar:
    open_time: datetime
    close_time: datetime
    open: D
    high: D
    low: D
    close: D
    volume: D


@dataclass(frozen=True)
class DailyBar:
    day: date
    open: D
    high: D
    low: D
    close: D
    normalized_true_range: D | None


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(Q, rounding=ROUND_HALF_UP), "f")


def nullable(value: D | None) -> str | None:
    return None if value is None else q(value)


def percentile(values: list[D], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
    position = fraction * D(len(ordered) - 1)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - D(lower)
    return ordered[lower] * (ONE - weight) + ordered[upper] * weight


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def output_path(value: str) -> Path:
    resolved = Path(value).resolve()
    root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise ResearchReject(f"PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    resolved.parent.mkdir(parents=True, exist_ok=True)
    return resolved


def expected_policy() -> dict[str, Any]:
    return {
        "policy_id": "BTC_DAILY_NR7_VOLATILITY_CONTRACTION_BREAKOUT_LONG_CASH_V1",
        "instrument": "OKX_BTC_USDT_SPOT",
        "bar_interval": "1h",
        "daily_clock": "COMPLETE_UTC_DAY_0000_TO_2359",
        "range_formula": "MAX_HIGH_MINUS_LOW_ABS_HIGH_MINUS_PREVIOUS_CLOSE_ABS_LOW_MINUS_PREVIOUS_CLOSE_DIVIDED_BY_PREVIOUS_CLOSE",
        "primary_setup": "LATEST_COMPLETE_DAY_NORMALIZED_TRUE_RANGE_STRICTLY_SMALLEST_OF_7_COMPLETE_DAYS",
        "rejection_only_neighbors": [
            "STRICTLY_SMALLEST_OF_5_COMPLETE_DAYS",
            "STRICTLY_SMALLEST_OF_10_COMPLETE_DAYS",
        ],
        "breakout": "FIRST_NEXT_DAY_COMPLETE_H1_CLOSE_STRICTLY_ABOVE_SETUP_DAY_HIGH",
        "entry": "NEXT_H1_OPEN_AFTER_BREAKOUT_CLOSE",
        "hold_hours": HOLD_HOURS,
        "exit": "H1_OPEN_EXACTLY_168_HOURS_AFTER_ENTRY",
        "same_open_reentry": "DENY",
        "overlapping_signal": "IGNORE_WHILE_LONG",
        "position": "FULL_CAPITAL_UNLEVERED_BTC_OR_ZERO_YIELD_CASH",
        "short": "DENY",
        "leverage": "DENY",
        "variants": 3,
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schema_version") != "1" or manifest.get("document_type") != MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:OOS")
    if manifest.get("strategy_policy") != expected_policy():
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        name: {
            "fee_rate_per_side": str(fee),
            "adverse_slippage_rate_per_side": str(slippage),
        }
        for name, (fee, slippage) in SCENARIOS.items()
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": ["2020-01-01T00:00:00", "2023-01-01T00:00:00"],
        "validation": ["2023-01-01T00:00:00", "2025-01-01T00:00:00"],
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
        "warmup_start": "2019-01-01T00:00:00",
        "state": "FAIR_RESET_IDENTICAL_INITIAL_EQUITY_CASH_AND_SIGNAL_STATE_EACH_WINDOW",
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set", {}).get("id") != GATE_ID:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    for binding in manifest.get("source_bindings", []):
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or file_sha256(path) != binding["sha256"]:
            prefix = "DATA_REJECT" if binding["path"].startswith(".research-state/") else "SOURCE_REJECT"
            raise ResearchReject(f"{prefix}:{binding['role']}")


def load_bars(manifest: dict[str, Any]) -> list[Bar]:
    dataset = manifest["dataset"]
    path = REPO_ROOT / dataset["path"]
    if file_sha256(path) != EXPECTED_DATA_SHA256 or dataset["sha256"] != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:SHA256")
    bars: list[Bar] = []
    for index, line in enumerate(path.read_text(encoding="ascii").splitlines()):
        row = line.split("\t")
        if len(row) != 7:
            raise ResearchReject(f"DATA_REJECT:COLUMNS:{index}")
        try:
            bar = Bar(datetime.fromisoformat(row[0]), datetime.fromisoformat(row[1]), *map(D, row[2:]))
        except (ValueError, ArithmeticError) as error:
            raise ResearchReject(f"DATA_REJECT:VALUE:{index}") from error
        if (
            bar.close_time - bar.open_time != timedelta(hours=1)
            or min(bar.open, bar.high, bar.low, bar.close) <= ZERO
            or bar.volume < ZERO
            or bar.high < max(bar.open, bar.low, bar.close)
            or bar.low > min(bar.open, bar.high, bar.close)
        ):
            raise ResearchReject(f"DATA_REJECT:BAR:{index}")
        if bars and bar.open_time != bars[-1].close_time:
            raise ResearchReject(f"DATA_REJECT:LATTICE:{index}")
        bars.append(bar)
    if (
        len(bars) != EXPECTED_ROWS
        or bars[0].open_time != datetime(2019, 1, 1)
        or bars[-1].close_time != datetime(2025, 1, 1)
    ):
        raise ResearchReject("DATA_REJECT:INVENTORY")
    return bars


def aggregate_daily(bars: list[Bar]) -> list[DailyBar]:
    grouped: dict[date, list[Bar]] = {}
    for bar in bars:
        grouped.setdefault(bar.open_time.date(), []).append(bar)
    days: list[DailyBar] = []
    previous_close: D | None = None
    for day in sorted(grouped):
        values = grouped[day]
        if (
            len(values) != 24
            or values[0].open_time != datetime.combine(day, datetime.min.time())
            or values[-1].close_time != datetime.combine(day + timedelta(days=1), datetime.min.time())
        ):
            raise ResearchReject(f"DATA_REJECT:DAILY_LATTICE:{day}")
        high = max(bar.high for bar in values)
        low = min(bar.low for bar in values)
        normalized = None
        if previous_close is not None:
            true_range = max(high - low, abs(high - previous_close), abs(low - previous_close))
            normalized = true_range / previous_close
        days.append(DailyBar(day, values[0].open, high, low, values[-1].close, normalized))
        previous_close = values[-1].close
    if len(days) != 2192:
        raise ResearchReject("DATA_REJECT:DAILY_COUNT")
    return days


def is_nr_setup(days: list[DailyBar], index: int, lookback: int) -> bool:
    if lookback not in LOOKBACKS or index < lookback - 1:
        return False
    values = [day.normalized_true_range for day in days[index - lookback + 1 : index + 1]]
    if any(value is None or value <= ZERO for value in values):
        return False
    current = values[-1]
    assert current is not None
    return all(current < value for value in values[:-1] if value is not None)


def annualized_return(total_return_fraction: D, hours: int) -> D:
    if total_return_fraction <= -ONE:
        return D("-100")
    return D(str(math.pow(float(ONE + total_return_fraction), 8766 / hours) - 1)) * HUNDRED


def path_metrics(points: list[D]) -> tuple[D, int]:
    peak = INITIAL_EQUITY
    maximum_drawdown = ZERO
    underwater = 0
    current_underwater = 0
    for equity in points:
        if equity <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NON_POSITIVE_EQUITY")
        if equity >= peak:
            peak = equity
            current_underwater = 0
        else:
            maximum_drawdown = max(maximum_drawdown, (peak - equity) / peak * HUNDRED)
            current_underwater += 1
            underwater = max(underwater, current_underwater)
    return maximum_drawdown, underwater


def simulate_breakout(
    bars: list[Bar],
    days: list[DailyBar],
    *,
    window: tuple[datetime, datetime],
    fee: D,
    slippage: D,
    lookback: int | None,
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    selected = [bar for bar in bars if start <= bar.open_time < end]
    if not selected or selected[0].open_time != start or selected[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start}:{end}")
    day_index = {value.day: index for index, value in enumerate(days)}
    daily_by_date = {value.day: value for value in days}
    cash = INITIAL_EQUITY
    quantity = ZERO
    cost_basis = ZERO
    entry_time: datetime | None = None
    exit_time: datetime | None = None
    pending_entry: datetime | None = None
    triggered_days: set[date] = set()
    setup_days: set[date] = set()
    setup_breakouts = 0
    fees = ZERO
    turnover = ZERO
    realized = ZERO
    realized_trades: list[D] = []
    holding_hours: list[D] = []
    trade_count = 0
    entry_count = 0
    exit_count = 0
    exposure_sum = ZERO
    equity_path: list[D] = []

    for bar in selected:
        exited_now = False
        if quantity > ZERO and exit_time == bar.open_time:
            fill = bar.open * (ONE - slippage)
            gross = quantity * fill
            paid_fee = gross * fee
            net = gross - paid_fee
            trade_pnl = net - cost_basis
            cash = net
            fees += paid_fee
            turnover += gross
            realized += trade_pnl
            realized_trades.append(trade_pnl)
            if entry_time is None:
                raise ResearchReject("ECONOMIC_REJECT:ENTRY_TIME")
            holding_hours.append(D(str((bar.open_time - entry_time).total_seconds() / 3600)))
            quantity = ZERO
            cost_basis = ZERO
            entry_time = None
            exit_time = None
            trade_count += 1
            exit_count += 1
            exited_now = True

        if pending_entry == bar.open_time:
            if quantity > ZERO or exited_now:
                raise ResearchReject("ECONOMIC_REJECT:PENDING_ENTRY_STATE")
            fill = bar.open * (ONE + slippage)
            quantity = cash / (fill * (ONE + fee))
            notional = quantity * fill
            paid_fee = notional * fee
            cost_basis = notional + paid_fee
            cash = ZERO
            fees += paid_fee
            turnover += notional
            entry_time = bar.open_time
            exit_time = entry_time + timedelta(hours=HOLD_HOURS)
            pending_entry = None
            trade_count += 1
            entry_count += 1

        reference_date = bar.open_time.date() - timedelta(days=1)
        reference = daily_by_date.get(reference_date)
        reference_index = day_index.get(reference_date)
        eligible = reference is not None and reference_index is not None
        if eligible and lookback is not None:
            eligible = is_nr_setup(days, reference_index, lookback)
        if eligible and reference_date not in setup_days:
            setup_days.add(reference_date)
        if (
            eligible
            and quantity == ZERO
            and pending_entry is None
            and not exited_now
            and reference_date not in triggered_days
            and bar.close > reference.high
            and bar.close_time < end
        ):
            pending_entry = bar.close_time
            triggered_days.add(reference_date)
            setup_breakouts += 1

        equity = cash + quantity * bar.close
        equity_path.append(equity)
        exposure_sum += ZERO if equity <= ZERO else quantity * bar.close / equity

    final_equity = equity_path[-1]
    total_pnl = final_equity - INITIAL_EQUITY
    unrealized = total_pnl - realized
    total_return = total_pnl / INITIAL_EQUITY * HUNDRED
    drawdown, underwater_hours = path_metrics(equity_path)
    annualized = annualized_return(total_pnl / INITIAL_EQUITY, len(selected))
    calmar = ZERO if drawdown == ZERO else annualized / drawdown
    terminal_net = cash
    if quantity > ZERO:
        terminal_net += quantity * selected[-1].close * (ONE - slippage) * (ONE - fee)
    terminal_return = (terminal_net - INITIAL_EQUITY) / INITIAL_EQUITY * HUNDRED
    positive_trades = [value for value in realized_trades if value > ZERO]
    terminal_age = ZERO if entry_time is None else D(str((end - entry_time).total_seconds() / 3600))
    metrics = {
        "total_return_pct": q(total_return),
        "annualized_return_pct": q(annualized),
        "realized_pnl_usdt": q(realized),
        "unrealized_pnl_usdt": q(unrealized),
        "maximum_drawdown_pct": q(drawdown),
        "maximum_underwater_duration_hours": underwater_hours,
        "calmar_ratio": q(calmar),
        "terminal_liquidation_adjusted_return_pct": q(terminal_return),
        "terminal_liquidation_cost_pp": q(total_return - terminal_return),
        "fees_usdt": q(fees),
        "turnover_usdt": q(turnover),
        "turnover_to_initial_equity": q(turnover / INITIAL_EQUITY),
        "average_exposure_pct": q(exposure_sum / D(len(selected)) * HUNDRED),
        "eligible_setup_day_count": len(setup_days),
        "breakout_trigger_count": setup_breakouts,
        "entry_count": entry_count,
        "exit_count": exit_count,
        "trade_count": trade_count,
        "completed_trade_count": len(realized_trades),
        "winning_trade_count": sum(value > ZERO for value in realized_trades),
        "median_holding_hours": nullable(None if not holding_hours else D(str(median(holding_hours)))),
        "p90_holding_hours": nullable(percentile(holding_hours, D("0.9"))),
        "top_positive_realized_trade_contribution_pct": nullable(
            None if not positive_trades else max(positive_trades) / sum(positive_trades, ZERO) * HUNDRED
        ),
        "terminal_inventory": {
            "open": quantity > ZERO,
            "quantity": q(quantity),
            "market_value_usdt": q(quantity * selected[-1].close),
            "cost_basis_usdt": q(cost_basis),
            "age_hours": q(terminal_age),
        },
    }
    raw = {
        "total": total_return,
        "terminal": terminal_return,
        "drawdown": drawdown,
        "calmar": calmar,
        "completed": D(len(realized_trades)),
        "setups": D(len(setup_days)),
        "breakouts": D(setup_breakouts),
        "terminal_cost": total_return - terminal_return,
        "p90_hold": ZERO if not holding_hours else percentile(holding_hours, D("0.9")) or ZERO,
        "terminal_age": terminal_age,
        "top_trade": ZERO if not positive_trades else max(positive_trades) / sum(positive_trades, ZERO) * HUNDRED,
    }
    return metrics, raw


def simulate_buy_hold(
    bars: list[Bar], *, window: tuple[datetime, datetime], fee: D, slippage: D
) -> tuple[dict[str, Any], dict[str, D]]:
    start, end = window
    selected = [bar for bar in bars if start <= bar.open_time < end]
    fill = selected[0].open * (ONE + slippage)
    quantity = INITIAL_EQUITY / (fill * (ONE + fee))
    fee_paid = quantity * fill * fee
    equities = [quantity * bar.close for bar in selected]
    final_equity = equities[-1]
    terminal = final_equity * (ONE - slippage) * (ONE - fee)
    total_return = (final_equity - INITIAL_EQUITY) / INITIAL_EQUITY * HUNDRED
    terminal_return = (terminal - INITIAL_EQUITY) / INITIAL_EQUITY * HUNDRED
    drawdown, underwater = path_metrics(equities)
    annualized = annualized_return(total_return / HUNDRED, len(selected))
    calmar = ZERO if drawdown == ZERO else annualized / drawdown
    metrics = {
        "total_return_pct": q(total_return),
        "annualized_return_pct": q(annualized),
        "maximum_drawdown_pct": q(drawdown),
        "maximum_underwater_duration_hours": underwater,
        "calmar_ratio": q(calmar),
        "terminal_liquidation_adjusted_return_pct": q(terminal_return),
        "terminal_liquidation_cost_pp": q(total_return - terminal_return),
        "fees_usdt": q(fee_paid),
        "terminal_inventory": {"quantity": q(quantity), "market_value_usdt": q(final_equity)},
    }
    return metrics, {"total": total_return, "terminal": terminal_return, "drawdown": drawdown, "calmar": calmar}


def paired_result(
    bars: list[Bar], days: list[DailyBar], *, window: tuple[datetime, datetime], fee: D, slippage: D
) -> tuple[dict[str, Any], dict[str, dict[str, D]]]:
    parent, parent_raw = simulate_breakout(
        bars, days, window=window, fee=fee, slippage=slippage, lookback=None
    )
    buy_hold, buy_hold_raw = simulate_buy_hold(bars, window=window, fee=fee, slippage=slippage)
    variants: dict[str, Any] = {}
    raw: dict[str, dict[str, D]] = {"PARENT": parent_raw, "BUY_HOLD": buy_hold_raw}
    for lookback in LOOKBACKS:
        metrics, values = simulate_breakout(
            bars, days, window=window, fee=fee, slippage=slippage, lookback=lookback
        )
        key = f"NR{lookback}"
        metrics["return_capture_vs_parent"] = q(
            ZERO if parent_raw["total"] <= ZERO else values["total"] / parent_raw["total"]
        )
        metrics["upside_capture_vs_buy_hold"] = q(
            ZERO if buy_hold_raw["total"] <= ZERO else values["total"] / buy_hold_raw["total"]
        )
        variants[key] = metrics
        raw[key] = values
    return {
        "start": window[0].isoformat(),
        "end_exclusive": window[1].isoformat(),
        "variants": variants,
        "matched_unconditional_prior_day_high_breakout_168h_parent": parent,
        "opportunity_cost_buy_and_hold": buy_hold,
    }, raw


def evaluate_gates(
    window_raw: dict[str, dict[str, dict[str, dict[str, D]]]],
    annual_raw: dict[str, dict[str, dict[str, dict[str, D]]]],
) -> tuple[list[str], list[str], dict[str, Any]]:
    passed: list[str] = []
    failed: list[str] = []

    def gate(name: str, condition: bool) -> None:
        (passed if condition else failed).append(name)

    for window_name in WINDOWS:
        minimum_completed = D("8") if window_name == "DESIGN" else D("5")
        minimum_setups = D("24") if window_name == "DESIGN" else D("16")
        for scenario in SCENARIOS:
            rows = window_raw[window_name][scenario]
            candidate = rows["NR7"]
            parent = rows["PARENT"]
            buy_hold = rows["BUY_HOLD"]
            prefix = f"{window_name.lower()}_{scenario.lower()}"
            gate(f"{prefix}_parent_total_positive", parent["total"] > ZERO)
            gate(f"{prefix}_candidate_total_positive", candidate["total"] > ZERO)
            gate(f"{prefix}_candidate_terminal_positive", candidate["terminal"] > ZERO)
            gate(f"{prefix}_candidate_completed_support", candidate["completed"] >= minimum_completed)
            gate(f"{prefix}_candidate_setup_support", candidate["setups"] >= minimum_setups)
            gate(f"{prefix}_candidate_return_at_least_60pct_parent", candidate["total"] >= D("0.60") * parent["total"])
            gate(f"{prefix}_candidate_drawdown_at_most_75pct_parent", candidate["drawdown"] <= D("0.75") * parent["drawdown"])
            gate(f"{prefix}_candidate_calmar_at_least_parent", candidate["calmar"] >= parent["calmar"])
            gate(f"{prefix}_candidate_drawdown_at_most_65pct_buy_hold", candidate["drawdown"] <= D("0.65") * buy_hold["drawdown"])
            gate(f"{prefix}_candidate_calmar_at_least_75pct_buy_hold", candidate["calmar"] >= D("0.75") * buy_hold["calmar"])
            gate(f"{prefix}_candidate_upside_capture_at_least_20pct", candidate["total"] >= D("0.20") * buy_hold["total"])

    for neighbor in ("NR5", "NR10"):
        for scenario in SCENARIOS:
            rows = window_raw["VALIDATION"][scenario]
            values = rows[neighbor]
            parent = rows["PARENT"]
            prefix = f"validation_{scenario.lower()}_{neighbor.lower()}"
            gate(f"{prefix}_positive", values["total"] > ZERO and values["terminal"] > ZERO)
            gate(f"{prefix}_completed_support", values["completed"] >= D("4"))
            gate(f"{prefix}_return_at_least_40pct_parent", values["total"] >= D("0.40") * parent["total"])
            gate(f"{prefix}_drawdown_non_worse_parent", values["drawdown"] <= parent["drawdown"])
            gate(f"{prefix}_calmar_at_least_75pct_parent", values["calmar"] >= D("0.75") * parent["calmar"])

    validation = window_raw["VALIDATION"]["NORMAL"]["NR7"]
    gate("validation_terminal_liquidation_cost_at_most_1pp", validation["terminal_cost"] <= ONE)
    gate("validation_p90_holding_at_most_168h", validation["p90_hold"] <= D(HOLD_HOURS))
    gate("validation_terminal_age_at_most_168h", validation["terminal_age"] <= D(HOLD_HOURS))
    gate("validation_top_positive_trade_contribution_at_most_60pct", validation["top_trade"] <= D("60"))

    breadth: dict[str, Any] = {}
    for scenario in SCENARIOS:
        rows = [annual_raw[year][scenario] for year in ANNUAL]
        positive = sum(row["NR7"]["total"] > ZERO for row in rows)
        return_support = sum(row["NR7"]["total"] >= D("0.60") * row["PARENT"]["total"] for row in rows)
        drawdown_support = sum(row["NR7"]["drawdown"] <= row["PARENT"]["drawdown"] for row in rows)
        calmar_support = sum(row["NR7"]["calmar"] >= row["PARENT"]["calmar"] for row in rows)
        positive_returns = [row["NR7"]["total"] for row in rows if row["NR7"]["total"] > ZERO]
        top_share = D("100") if not positive_returns else max(positive_returns) / sum(positive_returns, ZERO) * HUNDRED
        breadth[scenario] = {
            "positive_candidate_years": positive,
            "return_at_least_60pct_parent_years": return_support,
            "drawdown_non_worse_parent_years": drawdown_support,
            "calmar_non_worse_parent_years": calmar_support,
            "top_positive_year_return_contribution_pct": q(top_share),
        }
        prefix = scenario.lower()
        gate(f"annual_{prefix}_positive_at_least_4_of_5", positive >= 4)
        gate(f"annual_{prefix}_return_support_at_least_3_of_5", return_support >= 3)
        gate(f"annual_{prefix}_drawdown_support_at_least_4_of_5", drawdown_support >= 4)
        gate(f"annual_{prefix}_calmar_support_at_least_3_of_5", calmar_support >= 3)
        gate(f"annual_{prefix}_top_positive_year_at_most_60pct", top_share <= D("60"))
    return passed, failed, breadth


def build_output(manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    bars = load_bars(manifest)
    days = aggregate_daily(bars)
    window_results: dict[str, dict[str, Any]] = {}
    window_raw: dict[str, dict[str, dict[str, dict[str, D]]]] = {}
    for window_name, window in WINDOWS.items():
        window_results[window_name] = {}
        window_raw[window_name] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            result, raw = paired_result(bars, days, window=window, fee=fee, slippage=slippage)
            window_results[window_name][scenario] = result
            window_raw[window_name][scenario] = raw
    annual_results: dict[str, dict[str, Any]] = {}
    annual_raw: dict[str, dict[str, dict[str, dict[str, D]]]] = {}
    for year, window in ANNUAL.items():
        annual_results[year] = {}
        annual_raw[year] = {}
        for scenario, (fee, slippage) in SCENARIOS.items():
            result, raw = paired_result(bars, days, window=window, fee=fee, slippage=slippage)
            annual_results[year][scenario] = result
            annual_raw[year][scenario] = raw
    passed, failed, breadth = evaluate_gates(window_raw, annual_raw)
    status = (
        "CANDIDATE_ELIGIBLE_HISTORICAL_PASS_REPORTED_NOT_ACTIVATED_OOS_UNOPENED"
        if not failed
        else "NO_CANDIDATE_CLOSE_BTC_DAILY_NR7_VOLATILITY_CONTRACTION_BREAKOUT_FAMILY"
    )
    return {
        "schema_version": "1",
        "document_type": "BTC_DAILY_NR7_VOLATILITY_CONTRACTION_BREAKOUT_HISTORICAL_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "experiment_id": EXPERIMENT_ID,
        "status": status,
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": file_sha256(manifest_path)},
        "dataset": {"sha256": EXPECTED_DATA_SHA256, "hourly_rows": len(bars), "complete_utc_days": len(days), "selection_cutoff": "2025-01-01T00:00:00"},
        "policy": manifest["strategy_policy"],
        "cost_scenarios": manifest["cost_scenarios"],
        "windows": window_results,
        "annual_fair_reset": annual_results,
        "annual_breadth": breadth,
        "gate_summary": {"passed_gate_count": len(passed), "failed_gate_count": len(failed), "passed_gates": passed, "failed_gates": failed},
        "claim_boundary": "Historical preregistered Design and Validation only. A pass is not independent OOS or Trading authorization; any failure permanently closes the exact NR5/7/10 normalized-true-range contraction plus next-day prior-high breakout and 168-hour hold family without tuning.",
        "oos_opened": False,
        "candidate_activated": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    resolved_output = output_path(args.output)
    result = build_output(manifest_path)
    resolved_output.write_bytes(canonical_bytes(result))
    print(json.dumps({"status": result["status"], "output": str(resolved_output), "sha256": file_sha256(resolved_output)}, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
