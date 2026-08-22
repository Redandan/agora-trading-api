#!/usr/bin/env python3
"""Evaluate one frozen BTCUSDT fixed-maturity delivery cash-and-carry policy."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal as D, InvalidOperation
import gzip
import hashlib
import io
import json
from pathlib import Path
from statistics import median
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
INITIAL_EQUITY = D("10000")
SPOT_FRACTION = D("0.25")
MAINTENANCE_RATE = D("0.10")
MINIMUM_MARGIN_BUFFER = D("2")
RAW_ENTRY_BASIS_FLOOR_BPS = D("55")
ZERO = D("0")
ONE = D("1")
HOUR_MS = 3_600_000
HEADER = [
    "contract_symbol",
    "open_time_ms",
    "entry_time_ms",
    "delivery_time_ms",
    "delivery_price",
    "delivery_spot_open",
    "spot_open",
    "spot_high",
    "spot_low",
    "spot_close",
    "future_open",
    "future_high",
    "future_low",
    "future_close",
    "mark_open",
    "mark_high",
    "mark_low",
    "mark_close",
    "index_open",
    "index_high",
    "index_low",
    "index_close",
]
COSTS = {
    "NORMAL": {
        "spot_fee": D("0.0010"),
        "spot_slippage": D("0.0005"),
        "future_fee": D("0.0010"),
        "future_slippage": D("0.0005"),
    },
    "STRESS": {
        "spot_fee": D("0.0020"),
        "spot_slippage": D("0.0010"),
        "future_fee": D("0.0020"),
        "future_slippage": D("0.0010"),
    },
}
WINDOWS = {
    "Design": ("2021-06-01T00:00:00Z", "2023-01-01T00:00:00Z", 7),
    "Validation": ("2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z", 8),
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class Row:
    symbol: str
    open_time_ms: int
    entry_time_ms: int
    delivery_time_ms: int
    delivery_price: D
    delivery_spot_open: D
    spot_open: D
    spot_close: D
    future_open: D
    mark_close: D


def sha256(path_or_raw: Path | bytes) -> str:
    raw = path_or_raw.read_bytes() if isinstance(path_or_raw, Path) else path_or_raw
    return hashlib.sha256(raw).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def decimal(raw: str, *, context: str) -> D:
    try:
        value = D(raw)
    except InvalidOperation as error:
        raise ResearchReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}") from error
    if not value.is_finite():
        raise ResearchReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    return value


def parse_rows(gzip_raw: bytes) -> tuple[list[Row], bytes]:
    try:
        csv_raw = gzip.decompress(gzip_raw)
    except (OSError, EOFError) as error:
        raise ResearchReject("DATA_REJECT:GZIP") from error
    try:
        rows = list(csv.reader(io.StringIO(csv_raw.decode("ascii"), newline="")))
    except UnicodeDecodeError as error:
        raise ResearchReject("DATA_REJECT:ASCII") from error
    if not rows or rows[0] != HEADER:
        raise ResearchReject("DATA_REJECT:HEADER")
    parsed: list[Row] = []
    identities: set[tuple[str, int]] = set()
    for index, raw in enumerate(rows[1:], start=1):
        if len(raw) != len(HEADER):
            raise ResearchReject(f"DATA_REJECT:ROW_WIDTH:{index}")
        try:
            open_time = int(raw[1])
            entry_time = int(raw[2])
            delivery_time = int(raw[3])
        except ValueError as error:
            raise ResearchReject(f"DATA_REJECT:INTEGER:{index}") from error
        identity = (raw[0], open_time)
        if (
            identity in identities
            or open_time % HOUR_MS
            or entry_time % HOUR_MS
            or delivery_time % HOUR_MS
            or not entry_time <= open_time < delivery_time
        ):
            raise ResearchReject(f"DATA_REJECT:ROW_IDENTITY:{index}")
        identities.add(identity)
        prices = [decimal(value, context=f"row:{index}:{column}") for column, value in enumerate(raw[4:], start=4)]
        if min(prices) <= ZERO:
            raise ResearchReject(f"DATA_REJECT:NONPOSITIVE:{index}")
        parsed.append(
            Row(
                raw[0],
                open_time,
                entry_time,
                delivery_time,
                prices[0],
                prices[1],
                prices[2],
                prices[5],
                prices[6],
                prices[13],
            )
        )
    if not parsed:
        raise ResearchReject("DATA_REJECT:NO_ROWS")
    return parsed, csv_raw


def group_cycles(rows: list[Row]) -> list[list[Row]]:
    groups: dict[str, list[Row]] = {}
    for row in rows:
        groups.setdefault(row.symbol, []).append(row)
    ordered: list[list[Row]] = []
    prior_delivery = 0
    for symbol in sorted(groups):
        cycle = groups[symbol]
        if cycle != sorted(cycle, key=lambda row: row.open_time_ms):
            raise ResearchReject(f"DATA_REJECT:ORDER:{symbol}")
        first = cycle[0]
        if first.open_time_ms != first.entry_time_ms:
            raise ResearchReject(f"DATA_REJECT:ENTRY_ROW:{symbol}")
        expected = list(range(first.entry_time_ms, first.delivery_time_ms, HOUR_MS))
        if [row.open_time_ms for row in cycle] != expected:
            raise ResearchReject(f"DATA_REJECT:LATTICE:{symbol}")
        repeated = {
            (row.entry_time_ms, row.delivery_time_ms, row.delivery_price, row.delivery_spot_open)
            for row in cycle
        }
        if len(repeated) != 1 or first.delivery_time_ms <= prior_delivery:
            raise ResearchReject(f"DATA_REJECT:CYCLE_IDENTITY:{symbol}")
        prior_delivery = first.delivery_time_ms
        ordered.append(cycle)
    if len(ordered) != 15:
        raise ResearchReject(f"DATA_REJECT:CYCLE_COUNT:{len(ordered)}")
    return ordered


def verify_manifest(path: Path) -> tuple[dict[str, Any], dict[str, Any], bytes]:
    manifest_raw = path.read_bytes()
    try:
        manifest = json.loads(manifest_raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ResearchReject("MANIFEST_REJECT:JSON") from error
    if (
        manifest.get("document_type")
        != "BTC_BINANCE_FIXED_MATURITY_DELIVERY_CARRY_HISTORICAL_MANIFEST_V1"
        or manifest.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("oos_access") != "DENY"
    ):
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    by_role = {binding["role"]: binding for binding in manifest["source_bindings"]}
    required_roles = {
        "FROZEN_PRE_OUTCOME_SOURCE_ACCOUNTING_AND_GATE_SPEC",
        "FROZEN_DIRECT_ECONOMIC_RUNNER",
        "SEALED_CHECKSUM_VERIFIED_CORPUS_BUNDLE",
    }
    if not required_roles.issubset(by_role):
        raise ResearchReject("MANIFEST_REJECT:BINDING_ROLES")
    for role, binding in by_role.items():
        source = (REPO_ROOT / binding["path"]).resolve()
        if not source.is_file() or source.is_symlink() or sha256(source) != binding["sha256"]:
            raise ResearchReject(f"MANIFEST_REJECT:BINDING:{role}")
    if by_role["FROZEN_DIRECT_ECONOMIC_RUNNER"]["sha256"] != sha256(Path(__file__).resolve()):
        raise ResearchReject("MANIFEST_REJECT:RUNNER_SHA256")
    bundle_path = REPO_ROOT / by_role["SEALED_CHECKSUM_VERIFIED_CORPUS_BUNDLE"]["path"]
    bundle = json.loads(bundle_path.read_bytes())
    gzip_path = REPO_ROOT / bundle["normalized"]["gzip_path"]
    gzip_raw = gzip_path.read_bytes()
    if sha256(gzip_raw) != bundle["normalized"]["gzip_sha256"]:
        raise ResearchReject("DATA_REJECT:NORMALIZED_GZIP_SHA256")
    return manifest, bundle, gzip_raw


def max_drawdown(path: list[D]) -> D:
    peak = path[0]
    drawdown = ZERO
    for equity in path:
        peak = max(peak, equity)
        if peak > ZERO:
            drawdown = max(drawdown, (peak - equity) / peak * D("100"))
    return drawdown


def percentile(values: list[D], probability: D) -> D:
    if not values:
        return ZERO
    ordered = sorted(values)
    rank = (D(len(ordered)) - ONE) * probability
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    weight = rank - D(low)
    return ordered[low] * (ONE - weight) + ordered[high] * weight


def simulate_cycle(
    rows: list[Row], equity: D, costs: dict[str, D]
) -> dict[str, Any]:
    entry = rows[0]
    raw_basis_bps = (entry.future_open / entry.spot_open - ONE) * D("10000")
    hold_hours = D(len(rows))
    if raw_basis_bps < RAW_ENTRY_BASIS_FLOOR_BPS:
        return {
            "symbol": entry.symbol,
            "entry_time_ms": entry.entry_time_ms,
            "delivery_time_ms": entry.delivery_time_ms,
            "entered": False,
            "raw_entry_basis_bps": raw_basis_bps,
            "initial_equity": equity,
            "final_equity": equity,
            "pnl": ZERO,
            "fees": ZERO,
            "slippage": ZERO,
            "gross_pre_cost_pnl": ZERO,
            "initial_basis_pnl": ZERO,
            "settlement_reference_mismatch_pnl": ZERO,
            "maximum_drawdown_pct": ZERO,
            "minimum_margin_buffer": ZERO,
            "liquidated": False,
            "hold_hours": ZERO,
            "capital_utilization_pct": ZERO,
            "equity_path": [equity],
        }
    spot_budget = equity * SPOT_FRACTION
    spot_entry_exec = entry.spot_open * (ONE + costs["spot_slippage"])
    quantity = spot_budget / (spot_entry_exec * (ONE + costs["spot_fee"]))
    spot_notional = quantity * spot_entry_exec
    spot_entry_fee = spot_notional * costs["spot_fee"]
    spot_cash = spot_budget - spot_notional - spot_entry_fee
    future_entry_exec = entry.future_open * (ONE - costs["future_slippage"])
    future_entry_fee = quantity * future_entry_exec * costs["future_fee"]
    futures_cash = equity - spot_budget - future_entry_fee
    initial_future_notional = quantity * future_entry_exec
    capital_utilization = (
        spot_notional + spot_entry_fee + futures_cash + future_entry_fee
    ) / equity * D("100")
    path = [equity]
    minimum_buffer: D | None = None
    liquidated = False
    for row in rows:
        futures_equity = futures_cash + quantity * (
            future_entry_exec - row.mark_close
        )
        maintenance = quantity * row.mark_close * MAINTENANCE_RATE
        buffer = futures_equity / maintenance
        minimum_buffer = buffer if minimum_buffer is None else min(minimum_buffer, buffer)
        if futures_equity <= maintenance:
            liquidated = True
        path.append(spot_cash + quantity * row.spot_close + futures_equity)
    spot_exit_exec = entry.delivery_spot_open * (ONE - costs["spot_slippage"])
    spot_exit_proceeds = quantity * spot_exit_exec
    spot_exit_fee = spot_exit_proceeds * costs["spot_fee"]
    settlement_fee = quantity * entry.delivery_price * costs["future_fee"]
    future_pnl = quantity * (future_entry_exec - entry.delivery_price)
    final_equity = (
        spot_cash
        + spot_exit_proceeds
        - spot_exit_fee
        + futures_cash
        + future_pnl
        - settlement_fee
    )
    path.append(final_equity)
    fees = spot_entry_fee + future_entry_fee + spot_exit_fee + settlement_fee
    slippage = quantity * (
        (spot_entry_exec - entry.spot_open)
        + (entry.future_open - future_entry_exec)
        + (entry.delivery_spot_open - spot_exit_exec)
    )
    initial_basis_pnl = quantity * (entry.future_open - entry.spot_open)
    settlement_mismatch = quantity * (
        entry.delivery_spot_open - entry.delivery_price
    )
    gross_pre_cost = initial_basis_pnl + settlement_mismatch
    pnl = final_equity - equity
    if abs(pnl - (gross_pre_cost - fees - slippage)) > D("0.000000000001"):
        raise ResearchReject(f"ACCOUNTING_REJECT:PNL_IDENTITY:{entry.symbol}")
    return {
        "symbol": entry.symbol,
        "entry_time_ms": entry.entry_time_ms,
        "delivery_time_ms": entry.delivery_time_ms,
        "entered": True,
        "raw_entry_basis_bps": raw_basis_bps,
        "initial_equity": equity,
        "final_equity": final_equity,
        "pnl": pnl,
        "fees": fees,
        "slippage": slippage,
        "gross_pre_cost_pnl": gross_pre_cost,
        "initial_basis_pnl": initial_basis_pnl,
        "settlement_reference_mismatch_pnl": settlement_mismatch,
        "maximum_drawdown_pct": max_drawdown(path),
        "minimum_margin_buffer": minimum_buffer or ZERO,
        "liquidated": liquidated,
        "hold_hours": hold_hours,
        "capital_utilization_pct": capital_utilization,
        "equity_path": path,
    }


def simulate(
    cycles: list[list[Row]], start: datetime, end: datetime, costs: dict[str, D]
) -> dict[str, Any]:
    selected = [
        cycle
        for cycle in cycles
        if start
        <= datetime.fromtimestamp(cycle[0].delivery_time_ms / 1000, tz=timezone.utc)
        < end
    ]
    if not selected:
        raise ResearchReject(f"WINDOW_REJECT:EMPTY:{start}:{end}")
    equity = INITIAL_EQUITY
    path = [equity]
    results: list[dict[str, Any]] = []
    for cycle in selected:
        result = simulate_cycle(cycle, equity, costs)
        results.append(result)
        equity = result["final_equity"]
        path.extend(result["equity_path"][1:])
    entered = [result for result in results if result["entered"]]
    holds = [result["hold_hours"] for result in entered]
    total_return = (equity - INITIAL_EQUITY) / INITIAL_EQUITY * D("100")
    days = D(str((end - start).total_seconds())) / D("86400")
    annualized = total_return * D("365.25") / days
    drawdown = max_drawdown(path)
    return {
        "initial_equity": INITIAL_EQUITY,
        "final_equity": equity,
        "realized_pnl": equity - INITIAL_EQUITY,
        "unrealized_pnl": ZERO,
        "total_pnl": equity - INITIAL_EQUITY,
        "total_return_pct": total_return,
        "annualized_return_pct": annualized,
        "maximum_drawdown_pct": drawdown,
        "calmar": annualized / drawdown if drawdown > ZERO else D("999999"),
        "gross_pre_cost_pnl": sum((result["gross_pre_cost_pnl"] for result in entered), ZERO),
        "initial_basis_pnl": sum((result["initial_basis_pnl"] for result in entered), ZERO),
        "settlement_reference_mismatch_pnl": sum((result["settlement_reference_mismatch_pnl"] for result in entered), ZERO),
        "fees": sum((result["fees"] for result in entered), ZERO),
        "adverse_slippage_cost": sum((result["slippage"] for result in entered), ZERO),
        "eligible_cycles": len(results),
        "entered_cycles": len(entered),
        "cost_floor_blocked_cycles": len(results) - len(entered),
        "positive_cycles": len([result for result in entered if result["pnl"] > ZERO]),
        "minimum_conservative_margin_buffer": min((result["minimum_margin_buffer"] for result in entered), default=ZERO),
        "liquidated": any(result["liquidated"] for result in entered),
        "median_hold_hours": D(str(median(holds))) if holds else ZERO,
        "p90_hold_hours": percentile(holds, D("0.90")),
        "maximum_hold_hours": max(holds, default=ZERO),
        "maximum_capital_utilization_pct": max((result["capital_utilization_pct"] for result in entered), default=ZERO),
        "terminal_position_quantity": ZERO,
        "terminal_unrealized_pnl": ZERO,
        "cycles": results,
    }


def window_gates(label: str, scenario: str, metrics: dict[str, Any]) -> dict[str, bool]:
    expected = WINDOWS[label][2]
    minimum_entered = 5 if label == "Design" else 6
    minimum_positive = (
        (5 if label == "Design" else 6)
        if scenario == "NORMAL"
        else (4 if label == "Design" else 5)
    )
    return {
        "eligible_cycle_count_exact": metrics["eligible_cycles"] == expected,
        "entered_cycle_breadth": metrics["entered_cycles"] >= minimum_entered,
        "positive_cycle_breadth": metrics["positive_cycles"] >= minimum_positive,
        "total_return_positive": metrics["total_return_pct"] > ZERO,
        "annualized_return_floor": metrics["annualized_return_pct"]
        >= (ONE if scenario == "NORMAL" else D("0.5")),
        "maximum_drawdown_ceiling": metrics["maximum_drawdown_pct"]
        <= (D("5") if scenario == "NORMAL" else D("7.5")),
        "calmar_floor": metrics["calmar"] >= D("0.5"),
        "no_liquidation": not metrics["liquidated"],
        "minimum_margin_buffer": metrics["minimum_conservative_margin_buffer"]
        >= MINIMUM_MARGIN_BUFFER,
        "terminal_zero": metrics["terminal_position_quantity"] == ZERO
        and metrics["terminal_unrealized_pnl"] == ZERO,
    }


def positive_concentration(values: list[D]) -> D:
    positive = [value for value in values if value > ZERO]
    return max(positive) / sum(positive, ZERO) * D("100") if positive else D("100")


def serialize(value: Any) -> Any:
    if isinstance(value, D):
        return q(value)
    if isinstance(value, list):
        return [serialize(item) for item in value]
    if isinstance(value, dict):
        return {key: serialize(item) for key, item in value.items() if key != "equity_path"}
    return value


def build_output(manifest_path: Path) -> dict[str, Any]:
    manifest, bundle, gzip_raw = verify_manifest(manifest_path)
    rows, csv_raw = parse_rows(gzip_raw)
    if sha256(csv_raw) != bundle["normalized"]["csv_sha256"]:
        raise ResearchReject("DATA_REJECT:NORMALIZED_CSV_SHA256")
    cycles = group_cycles(rows)
    windows: dict[str, Any] = {}
    all_gates: list[bool] = []
    for label, (start_raw, end_raw, _) in WINDOWS.items():
        start = datetime.fromisoformat(start_raw.replace("Z", "+00:00"))
        end = datetime.fromisoformat(end_raw.replace("Z", "+00:00"))
        windows[label] = {}
        for scenario, costs in COSTS.items():
            metrics = simulate(cycles, start, end, costs)
            gates = window_gates(label, scenario, metrics)
            windows[label][scenario] = {
                "metrics": serialize(metrics),
                "gates": gates,
            }
            all_gates.extend(gates.values())

    annual: dict[str, Any] = {}
    for year in range(2021, 2025):
        start = datetime(year, 1, 1, tzinfo=timezone.utc)
        end = datetime(year + 1, 1, 1, tzinfo=timezone.utc)
        annual[str(year)] = {
            scenario: serialize(simulate(cycles, start, end, costs))
            for scenario, costs in COSTS.items()
        }
    normal_year_pnl = [D(annual[str(year)]["NORMAL"]["total_pnl"]) for year in range(2021, 2025)]
    stress_year_pnl = [D(annual[str(year)]["STRESS"]["total_pnl"]) for year in range(2021, 2025)]

    fair_cycles: dict[str, list[dict[str, Any]]] = {}
    for scenario, costs in COSTS.items():
        fair_cycles[scenario] = [serialize(simulate_cycle(cycle, INITIAL_EQUITY, costs)) for cycle in cycles]
    normal_cycle_pnl = [D(result["pnl"]) for result in fair_cycles["NORMAL"] if result["entered"]]
    stress_cycle_pnl = [D(result["pnl"]) for result in fair_cycles["STRESS"] if result["entered"]]
    breadth = {
        "normal_positive_years": len([value for value in normal_year_pnl if value > ZERO]),
        "stress_positive_years": len([value for value in stress_year_pnl if value > ZERO]),
        "normal_top_positive_year_contribution_pct": q(positive_concentration(normal_year_pnl)),
        "normal_entered_cycles": len(normal_cycle_pnl),
        "stress_entered_cycles": len(stress_cycle_pnl),
        "normal_positive_cycles": len([value for value in normal_cycle_pnl if value > ZERO]),
        "stress_positive_cycles": len([value for value in stress_cycle_pnl if value > ZERO]),
        "normal_top_positive_cycle_contribution_pct": q(positive_concentration(normal_cycle_pnl)),
    }
    breadth_gates = {
        "normal_positive_years_at_least_3_of_4": breadth["normal_positive_years"] >= 3,
        "stress_positive_years_at_least_3_of_4": breadth["stress_positive_years"] >= 3,
        "normal_top_positive_year_contribution_at_most_45pct": D(breadth["normal_top_positive_year_contribution_pct"]) <= D("45"),
        "normal_entered_cycles_at_least_11_of_15": breadth["normal_entered_cycles"] >= 11,
        "stress_entered_cycles_at_least_11_of_15": breadth["stress_entered_cycles"] >= 11,
        "normal_positive_cycles_at_least_11_of_15": breadth["normal_positive_cycles"] >= 11,
        "stress_positive_cycles_at_least_9_of_15": breadth["stress_positive_cycles"] >= 9,
        "normal_top_positive_cycle_contribution_at_most_25pct": D(breadth["normal_top_positive_cycle_contribution_pct"]) <= D("25"),
    }
    all_gates.extend(breadth_gates.values())
    passed = all(all_gates)
    return {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_FIXED_MATURITY_DELIVERY_CARRY_HISTORICAL_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "experiment_id": manifest["experiment_id"],
        "status": (
            "HISTORICAL_CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_FIXED_MATURITY_DELIVERY_CASH_AND_CARRY_FAMILY"
        ),
        "all_gates_pass": passed,
        "manifest_sha256": sha256(manifest_path),
        "corpus_bundle_sha256": sha256(
            REPO_ROOT
            / next(
                binding["path"]
                for binding in manifest["source_bindings"]
                if binding["role"] == "SEALED_CHECKSUM_VERIFIED_CORPUS_BUNDLE"
            )
        ),
        "policy": {
            "variants": 1,
            "initial_equity": q(INITIAL_EQUITY),
            "spot_fraction": q(SPOT_FRACTION),
            "futures_collateral_fraction": q(ONE - SPOT_FRACTION),
            "maintenance_margin_rate": q(MAINTENANCE_RATE),
            "raw_entry_basis_floor_bps": q(RAW_ENTRY_BASIS_FLOOR_BPS),
            "entry_clock": "00:00_UTC_DAY_AFTER_PREVIOUS_CONTRACT_EXPIRY_CODE_DATE",
            "exit": "OFFICIAL_DELIVERY_PRICE_AND_SAME_TIMESTAMP_SPOT_OPEN",
            "terminal_position": "ZERO",
        },
        "windows": windows,
        "annual_fair_reset": annual,
        "fair_reset_cycles": fair_cycles,
        "breadth": breadth,
        "breadth_gates": breadth_gates,
        "gate_summary": {
            "passed": len([value for value in all_gates if value]),
            "failed": len([value for value in all_gates if not value]),
            "all_pass": passed,
        },
        "oos": {
            "status": "UNOPENED",
            "access": "DENY",
        },
        "scope_note": "Historical Design and Validation only. No OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not output.is_relative_to(state_root) or output.exists():
        raise ResearchReject(f"OUTPUT_REJECT:{output}")
    result = build_output(args.manifest.resolve())
    raw = (
        json.dumps(result, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("ascii")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as target:
        target.write(raw)
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(raw),
                "passed_gates": result["gate_summary"]["passed"],
                "failed_gates": result["gate_summary"]["failed"],
                "oos": result["oos"]["status"],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
