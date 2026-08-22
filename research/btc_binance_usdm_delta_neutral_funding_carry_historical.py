#!/usr/bin/env python3
"""Run the frozen Binance BTCUSDT delta-neutral funding-carry experiment."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, getcontext
import gzip
import hashlib
import io
import json
from pathlib import Path
from typing import Any


getcontext().prec = 50
D = Decimal
ZERO = D("0")
ONE = D("1")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPERIMENT_ID = "btc-binance-usdm-delta-neutral-funding-carry-historical-v2"
EXPECTED_MANIFEST_TYPE = (
    "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_HISTORICAL_MANIFEST_V2"
)
EXPECTED_SPEC_SHA256 = (
    "41745a4c173714534f4bdeb63ca594a0250c056143b356cc22b2344212dfad79"
)
EXPECTED_ERRATUM_SHA256 = (
    "63b1de3d0e89338bbccc781f1f707c9f1dc1d7b7e16f70d9299e106a6d68a722"
)
EXPECTED_PROXY_TIMES = {
    1_582_110_000_000,
    1_582_113_600_000,
    1_582_117_200_000,
    1_582_120_800_000,
    1_582_124_400_000,
    1_582_128_000_000,
}
EXPECTED_ROWS = 43_848
HOUR_MS = 3_600_000
INITIAL_EQUITY = D("10000")
SPOT_FRACTION = D("0.25")
MAINTENANCE_RATE = D("0.10")
MINIMUM_MARGIN_BUFFER = D("2")
WINDOWS = {
    "design": (datetime(2020, 1, 1, tzinfo=timezone.utc), datetime(2023, 1, 1, tzinfo=timezone.utc)),
    "validation": (datetime(2023, 1, 1, tzinfo=timezone.utc), datetime(2025, 1, 1, tzinfo=timezone.utc)),
}
ANNUAL = {
    str(year): (
        datetime(year, 1, 1, tzinfo=timezone.utc),
        datetime(year + 1, 1, 1, tzinfo=timezone.utc),
    )
    for year in range(2020, 2025)
}
QUARTERS = {
    f"{year}-Q{quarter}": (
        datetime(year, 1 + (quarter - 1) * 3, 1, tzinfo=timezone.utc),
        (
            datetime(year + 1, 1, 1, tzinfo=timezone.utc)
            if quarter == 4
            else datetime(year, 1 + quarter * 3, 1, tzinfo=timezone.utc)
        ),
    )
    for year in range(2020, 2025)
    for quarter in range(1, 5)
}
COSTS = {
    "NORMAL": {
        "spot_fee": D("0.0010"),
        "perp_fee": D("0.0010"),
        "spot_slippage": D("0.0005"),
        "perp_slippage": D("0.0005"),
    },
    "STRESS": {
        "spot_fee": D("0.0020"),
        "perp_fee": D("0.0020"),
        "spot_slippage": D("0.0010"),
        "perp_slippage": D("0.0010"),
    },
}


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class Row:
    open_time_ms: int
    spot_open: D
    spot_close: D
    spot_price_source: str
    perp_open: D
    perp_close: D
    mark_open: D
    mark_close: D
    funding_rate: D | None

    @property
    def opened_at(self) -> datetime:
        return datetime.fromtimestamp(self.open_time_ms / 1000, tz=timezone.utc)


@dataclass
class Position:
    quantity: D
    spot_cash: D
    futures_cash: D
    short_entry_price: D
    opened_at: datetime


def sha256(path_or_raw: Path | bytes) -> str:
    raw = path_or_raw.read_bytes() if isinstance(path_or_raw, Path) else path_or_raw
    return hashlib.sha256(raw).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def _decimal(raw: str, *, context: str) -> D:
    try:
        value = D(raw)
    except InvalidOperation as error:
        raise ResearchReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}") from error
    if not value.is_finite():
        raise ResearchReject(f"DATA_REJECT:DECIMAL:{context}:{raw!r}")
    return value


def parse_normalized_gzip(raw: bytes) -> tuple[list[Row], bytes]:
    try:
        csv_raw = gzip.decompress(raw)
    except (OSError, EOFError) as error:
        raise ResearchReject("DATA_REJECT:GZIP") from error
    try:
        rows = list(csv.reader(io.StringIO(csv_raw.decode("ascii"), newline="")))
    except UnicodeDecodeError as error:
        raise ResearchReject("DATA_REJECT:ASCII") from error
    expected_header = [
        "open_time_ms",
        "spot_open",
        "spot_close",
        "spot_price_source",
        "perp_open",
        "perp_close",
        "mark_open",
        "mark_close",
        "funding_rate",
    ]
    if not rows or rows[0] != expected_header:
        raise ResearchReject("DATA_REJECT:HEADER")
    parsed: list[Row] = []
    for index, values in enumerate(rows[1:], start=1):
        if len(values) != 9:
            raise ResearchReject(f"DATA_REJECT:WIDTH:{index}")
        try:
            timestamp = int(values[0])
        except ValueError as error:
            raise ResearchReject(f"DATA_REJECT:TIMESTAMP:{index}") from error
        prices = [_decimal(value, context=f"price:{index}") for value in values[1:3] + values[4:8]]
        if min(prices) <= ZERO:
            raise ResearchReject(f"DATA_REJECT:PRICE:{index}")
        source = values[3]
        if source not in {
            "BINANCE_SPOT_ARCHIVE",
            "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP",
        }:
            raise ResearchReject(f"DATA_REJECT:SPOT_SOURCE:{index}:{source}")
        funding = None if values[8] == "" else _decimal(values[8], context=f"funding:{index}")
        parsed.append(
            Row(
                timestamp,
                prices[0],
                prices[1],
                source,
                prices[2],
                prices[3],
                prices[4],
                prices[5],
                funding,
            )
        )
    if len(parsed) != EXPECTED_ROWS:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(parsed)}")
    start_ms = int(datetime(2020, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
    if any(row.open_time_ms != start_ms + index * HOUR_MS for index, row in enumerate(parsed)):
        raise ResearchReject("DATA_REJECT:HOURLY_LATTICE")
    funding_times = [row.open_time_ms for row in parsed if row.funding_rate is not None]
    if len(funding_times) != 5_481 or funding_times != [row.open_time_ms for row in parsed[::8]]:
        raise ResearchReject("DATA_REJECT:FUNDING_LATTICE")
    proxy_times = {
        row.open_time_ms
        for row in parsed
        if row.spot_price_source == "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP"
    }
    if proxy_times != EXPECTED_PROXY_TIMES:
        raise ResearchReject(f"DATA_REJECT:SPOT_PROXY_IDENTITY:{sorted(proxy_times)}")
    return parsed, csv_raw


def validate_manifest(manifest: dict[str, Any]) -> None:
    if (
        manifest.get("document_type") != EXPECTED_MANIFEST_TYPE
        or manifest.get("experiment_id") != EXPERIMENT_ID
        or manifest.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("research_classification")
        != "HISTORICAL_PREREGISTERED_DESIGN_VALIDATION_NO_OOS"
        or manifest.get("oos_access") != "DENY"
    ):
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    dataset = manifest.get("dataset", {})
    if (
        dataset.get("hourly_rows") != EXPECTED_ROWS
        or dataset.get("funding_events") != 5_481
        or dataset.get("selection_cutoff") != "2025-01-01T00:00:00Z"
        or not isinstance(dataset.get("normalized_gzip_sha256"), str)
        or not isinstance(dataset.get("normalized_csv_sha256"), str)
    ):
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    policy = manifest.get("strategy_policy", {})
    expected_policy = {
        "policy_id": "BTC_BINANCE_USDM_QUARTERLY_RESET_25_75_DELTA_NEUTRAL_FUNDING_CARRY_V1",
        "initial_equity_usdt": "10000",
        "spot_budget_fraction": "0.25",
        "futures_collateral_fraction": "0.75",
        "spot_position": "LONG_BTC",
        "perpetual_position": "SHORT_EQUAL_BTC_QUANTITY",
        "rebalance": "QUARTER_START_01_00_UTC_AFTER_00_00_FUNDING",
        "funding_cashflow_for_short": "QUANTITY_TIMES_MARK_OPEN_TIMES_SIGNED_RATE",
        "maintenance_margin_rate": "0.10",
        "minimum_margin_buffer": "2.0",
        "terminal_position": "ZERO_AFTER_FINAL_HOURLY_CLOSE_LIQUIDATION",
        "variants": 1,
    }
    if policy != expected_policy:
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("cost_scenarios") != {
        scenario: {key: format(value, "f") for key, value in costs.items()}
        for scenario, costs in COSTS.items()
    }:
        raise ResearchReject("MANIFEST_REJECT:COSTS")
    if manifest.get("windows") != {
        "design": ["2020-01-01T00:00:00Z", "2023-01-01T00:00:00Z"],
        "validation": ["2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z"],
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
        "quarterly_fair_reset": True,
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    bindings = manifest.get("source_bindings", [])
    by_role = {binding.get("role"): binding for binding in bindings}
    spec = by_role.get("FROZEN_PRE_OUTCOME_SOURCE_AND_LEDGER_SPEC", {})
    if spec.get("sha256") != EXPECTED_SPEC_SHA256:
        raise ResearchReject("MANIFEST_REJECT:SPEC_BINDING")
    erratum = by_role.get("FROZEN_PRE_OUTCOME_SOURCE_INTEGRITY_ERRATUM", {})
    if erratum.get("sha256") != EXPECTED_ERRATUM_SHA256:
        raise ResearchReject("MANIFEST_REJECT:ERRATUM_BINDING")


def is_quarter_reset(moment: datetime) -> bool:
    return (
        moment.month in {1, 4, 7, 10}
        and moment.day == 1
        and moment.hour == 1
        and moment.minute == 0
    )


def open_position(equity: D, row: Row, costs: dict[str, D]) -> tuple[Position, D, D]:
    if row.spot_price_source != "BINANCE_SPOT_ARCHIVE":
        raise ResearchReject(f"EXECUTION_REJECT:SPOT_PROXY_ENTRY:{row.open_time_ms}")
    spot_budget = equity * SPOT_FRACTION
    spot_exec = row.spot_open * (ONE + costs["spot_slippage"])
    quantity = spot_budget / (spot_exec * (ONE + costs["spot_fee"]))
    spot_notional = quantity * spot_exec
    spot_fee = spot_notional * costs["spot_fee"]
    spot_cash = spot_budget - spot_notional - spot_fee
    perp_exec = row.perp_open * (ONE - costs["perp_slippage"])
    perp_fee = quantity * perp_exec * costs["perp_fee"]
    futures_cash = equity - spot_budget - perp_fee
    slippage = quantity * (spot_exec - row.spot_open) + quantity * (row.perp_open - perp_exec)
    return (
        Position(quantity, spot_cash, futures_cash, perp_exec, row.opened_at),
        spot_fee + perp_fee,
        slippage,
    )


def close_position(
    position: Position,
    spot_reference: D,
    perp_reference: D,
    costs: dict[str, D],
) -> tuple[D, D, D]:
    spot_exec = spot_reference * (ONE - costs["spot_slippage"])
    spot_proceeds = position.quantity * spot_exec
    spot_fee = spot_proceeds * costs["spot_fee"]
    perp_exec = perp_reference * (ONE + costs["perp_slippage"])
    perp_fee = position.quantity * perp_exec * costs["perp_fee"]
    short_pnl = position.quantity * (position.short_entry_price - perp_exec)
    equity = (
        position.spot_cash
        + spot_proceeds
        - spot_fee
        + position.futures_cash
        + short_pnl
        - perp_fee
    )
    slippage = position.quantity * (spot_reference - spot_exec) + position.quantity * (perp_exec - perp_reference)
    return equity, spot_fee + perp_fee, slippage


def max_drawdown(equity_path: list[D]) -> D:
    peak = equity_path[0]
    drawdown = ZERO
    for equity in equity_path:
        peak = max(peak, equity)
        if peak > ZERO:
            drawdown = max(drawdown, (peak - equity) / peak * D("100"))
    return drawdown


def simulate(
    rows: list[Row], start: datetime, end: datetime, costs: dict[str, D]
) -> dict[str, D | int | bool]:
    selected = [row for row in rows if start <= row.opened_at < end]
    if not selected:
        raise ResearchReject(f"WINDOW_REJECT:EMPTY:{start}:{end}")
    position: Position | None = None
    equity = INITIAL_EQUITY
    equity_path = [equity]
    gross_funding = ZERO
    fees = ZERO
    slippage = ZERO
    funding_events = 0
    eligible_funding_events = 0
    completed_holds = 0
    maximum_hold_hours = ZERO
    minimum_margin_buffer: D | None = None
    maximum_abs_basis_bps = ZERO
    liquidated = False
    for row in selected:
        moment = row.opened_at
        if is_quarter_reset(moment):
            if row.spot_price_source != "BINANCE_SPOT_ARCHIVE":
                raise ResearchReject(f"EXECUTION_REJECT:SPOT_PROXY_RESET:{row.open_time_ms}")
            if position is not None:
                hold_hours = D(str((moment - position.opened_at).total_seconds())) / D("3600")
                maximum_hold_hours = max(maximum_hold_hours, hold_hours)
                equity, closing_fees, closing_slippage = close_position(
                    position, row.spot_open, row.perp_open, costs
                )
                fees += closing_fees
                slippage += closing_slippage
                completed_holds += 1
            position, opening_fees, opening_slippage = open_position(equity, row, costs)
            fees += opening_fees
            slippage += opening_slippage
        if row.funding_rate is not None and position is not None:
            eligible_funding_events += 1
            funding_cashflow = position.quantity * row.mark_open * row.funding_rate
            position.futures_cash += funding_cashflow
            gross_funding += funding_cashflow
            funding_events += 1
        if position is not None:
            futures_equity = position.futures_cash + position.quantity * (
                position.short_entry_price - row.mark_close
            )
            maintenance = position.quantity * row.mark_close * MAINTENANCE_RATE
            margin_buffer = futures_equity / maintenance
            minimum_margin_buffer = (
                margin_buffer
                if minimum_margin_buffer is None
                else min(minimum_margin_buffer, margin_buffer)
            )
            if futures_equity <= maintenance:
                liquidated = True
            marked_equity = (
                position.spot_cash
                + position.quantity * row.spot_close
                + futures_equity
            )
            equity_path.append(marked_equity)
            basis_bps = abs((row.perp_close - row.spot_close) / row.spot_close * D("10000"))
            maximum_abs_basis_bps = max(maximum_abs_basis_bps, basis_bps)
    if position is None:
        raise ResearchReject(f"WINDOW_REJECT:NO_POSITION:{start}:{end}")
    terminal = selected[-1]
    if terminal.spot_price_source != "BINANCE_SPOT_ARCHIVE":
        raise ResearchReject(f"EXECUTION_REJECT:SPOT_PROXY_TERMINAL:{terminal.open_time_ms}")
    hold_hours = D(str(((terminal.opened_at.replace(tzinfo=timezone.utc) - position.opened_at).total_seconds() + 3600))) / D("3600")
    maximum_hold_hours = max(maximum_hold_hours, hold_hours)
    equity, closing_fees, closing_slippage = close_position(
        position, terminal.spot_close, terminal.perp_close, costs
    )
    fees += closing_fees
    slippage += closing_slippage
    completed_holds += 1
    equity_path.append(equity)
    total_pnl = equity - INITIAL_EQUITY
    total_return = total_pnl / INITIAL_EQUITY * D("100")
    days = D(str((end - start).total_seconds())) / D("86400")
    annualized = total_return * D("365.25") / days
    drawdown = max_drawdown(equity_path)
    calmar = annualized / drawdown if drawdown > ZERO else D("999999")
    funding_minus_costs = gross_funding - fees - slippage
    return {
        "initial_equity": INITIAL_EQUITY,
        "final_equity": equity,
        "realized_pnl": total_pnl,
        "unrealized_pnl": ZERO,
        "total_pnl": total_pnl,
        "total_return_pct": total_return,
        "annualized_return_pct": annualized,
        "maximum_drawdown_pct": drawdown,
        "calmar": calmar,
        "gross_funding_pnl": gross_funding,
        "fees": fees,
        "adverse_slippage_cost": slippage,
        "funding_minus_all_costs": funding_minus_costs,
        "nonfunding_pnl": total_pnl - gross_funding,
        "funding_events": funding_events,
        "eligible_funding_events": eligible_funding_events,
        "completed_quarter_holds": completed_holds,
        "maximum_hold_hours": maximum_hold_hours,
        "minimum_conservative_margin_buffer": minimum_margin_buffer or ZERO,
        "maximum_absolute_basis_bps": maximum_abs_basis_bps,
        "liquidated": liquidated,
        "terminal_position_quantity": ZERO,
        "terminal_unrealized_pnl": ZERO,
    }


def serializable(metrics: dict[str, D | int | bool]) -> dict[str, str | int | bool]:
    return {
        key: q(value) if isinstance(value, D) else value
        for key, value in metrics.items()
    }


def window_gates(metrics: dict[str, D | int | bool]) -> dict[str, bool]:
    return {
        "normal_total_return_pct_gt_0": metrics["total_return_pct"] > ZERO,
        "normal_annualized_return_pct_at_least_1": metrics["annualized_return_pct"] >= ONE,
        "normal_max_drawdown_pct_at_most_3": metrics["maximum_drawdown_pct"] <= D("3"),
        "normal_calmar_at_least_0_5": metrics["calmar"] >= D("0.5"),
        "normal_gross_funding_pnl_gt_0": metrics["gross_funding_pnl"] > ZERO,
        "normal_funding_minus_all_costs_gt_0": metrics["funding_minus_all_costs"] > ZERO,
        "normal_total_pnl_not_greater_than_gross_funding_pnl": metrics["total_pnl"] <= metrics["gross_funding_pnl"],
        "normal_no_liquidation": metrics["liquidated"] is False,
        "normal_minimum_conservative_margin_buffer_at_least_2": metrics["minimum_conservative_margin_buffer"] >= MINIMUM_MARGIN_BUFFER,
        "normal_terminal_position_and_unrealized_pnl_zero": metrics["terminal_position_quantity"] == ZERO and metrics["terminal_unrealized_pnl"] == ZERO,
        "normal_funding_event_coverage_100_percent": metrics["funding_events"] == metrics["eligible_funding_events"],
    }


def stress_gates(metrics: dict[str, D | int | bool]) -> dict[str, bool]:
    return {
        "stress_total_return_pct_gt_0": metrics["total_return_pct"] > ZERO,
        "stress_annualized_return_pct_at_least_0_5": metrics["annualized_return_pct"] >= D("0.5"),
        "stress_max_drawdown_pct_at_most_5": metrics["maximum_drawdown_pct"] <= D("5"),
        "stress_funding_minus_all_costs_gt_0": metrics["funding_minus_all_costs"] > ZERO,
        "stress_no_liquidation": metrics["liquidated"] is False,
        "stress_minimum_conservative_margin_buffer_at_least_2": metrics["minimum_conservative_margin_buffer"] >= MINIMUM_MARGIN_BUFFER,
        "stress_terminal_position_and_unrealized_pnl_zero": metrics["terminal_position_quantity"] == ZERO and metrics["terminal_unrealized_pnl"] == ZERO,
        "stress_funding_event_coverage_100_percent": metrics["funding_events"] == metrics["eligible_funding_events"],
    }


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    dataset = manifest["dataset"]
    if sha256(input_path) != dataset["normalized_gzip_sha256"]:
        raise ResearchReject("DATA_REJECT:GZIP_SHA256")
    bundle_path = REPO_ROOT / dataset["bundle_path"]
    if sha256(bundle_path) != dataset["bundle_sha256"]:
        raise ResearchReject("DATA_REJECT:BUNDLE_SHA256")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if (
        bundle.get("status")
        != "SEALED_CHECKSUM_VERIFIED_PRE_2025_CORPUS_WITH_EXACT_BOUNDED_SPOT_INDEX_PROXY_NO_STRATEGY_OUTCOME"
        or bundle.get("corpus", {}).get("normalized_gzip_sha256")
        != dataset["normalized_gzip_sha256"]
        or bundle.get("corpus", {}).get("normalized_csv_sha256")
        != dataset["normalized_csv_sha256"]
        or bundle.get("predecessor_source_and_ledger_spec", {}).get("sha256")
        != EXPECTED_SPEC_SHA256
        or bundle.get("source_integrity_erratum", {}).get("sha256")
        != EXPECTED_ERRATUM_SHA256
    ):
        raise ResearchReject("DATA_REJECT:BUNDLE_BINDING")
    rows, csv_raw = parse_normalized_gzip(input_path.read_bytes())
    if sha256(csv_raw) != dataset["normalized_csv_sha256"]:
        raise ResearchReject("DATA_REJECT:CSV_SHA256")

    windows: dict[str, Any] = {}
    raw_windows: dict[str, dict[str, dict[str, D | int | bool]]] = {}
    failed_window_gates: dict[str, list[str]] = {}
    for name, (start, end) in WINDOWS.items():
        normal = simulate(rows, start, end, COSTS["NORMAL"])
        stress = simulate(rows, start, end, COSTS["STRESS"])
        gates = {**window_gates(normal), **stress_gates(stress)}
        failed = [gate for gate, passed in gates.items() if not passed]
        if failed:
            failed_window_gates[name] = failed
        raw_windows[name] = {"NORMAL": normal, "STRESS": stress}
        windows[name] = {
            "NORMAL": serializable(normal),
            "STRESS": serializable(stress),
            "gates": gates,
        }

    annual_raw = {
        year: {
            scenario: simulate(rows, start, end, costs)
            for scenario, costs in COSTS.items()
        }
        for year, (start, end) in ANNUAL.items()
    }
    annual = {
        year: {scenario: serializable(metrics) for scenario, metrics in values.items()}
        for year, values in annual_raw.items()
    }
    normal_positive_years = sum(
        values["NORMAL"]["total_return_pct"] > ZERO for values in annual_raw.values()
    )
    stress_positive_years = sum(
        values["STRESS"]["total_return_pct"] > ZERO for values in annual_raw.values()
    )
    funding_after_cost_positive_years = sum(
        values["NORMAL"]["funding_minus_all_costs"] > ZERO for values in annual_raw.values()
    )
    dd_at_most_three_years = sum(
        values["NORMAL"]["maximum_drawdown_pct"] <= D("3") for values in annual_raw.values()
    )
    no_liquidation_years = sum(
        values["NORMAL"]["liquidated"] is False for values in annual_raw.values()
    )
    positive_pnl = [
        values["NORMAL"]["total_pnl"]
        for values in annual_raw.values()
        if values["NORMAL"]["total_pnl"] > ZERO
    ]
    top_year_contribution = (
        max(positive_pnl) / sum(positive_pnl) * D("100") if positive_pnl else D("999999")
    )
    annual_gates = {
        "normal_positive_total_return_at_least_4_of_5_years": normal_positive_years >= 4,
        "stress_positive_total_return_at_least_4_of_5_years": stress_positive_years >= 4,
        "normal_funding_minus_all_costs_positive_at_least_4_of_5_years": funding_after_cost_positive_years >= 4,
        "normal_max_drawdown_at_most_3_percent_at_least_4_of_5_years": dd_at_most_three_years >= 4,
        "normal_no_liquidation_5_of_5_years": no_liquidation_years == 5,
        "top_year_positive_total_pnl_contribution_at_most_45_percent": top_year_contribution <= D("45"),
    }

    quarter_raw = {
        quarter: {
            scenario: simulate(rows, start, end, costs)
            for scenario, costs in COSTS.items()
        }
        for quarter, (start, end) in QUARTERS.items()
    }
    quarter_positive_normal = sum(
        value["NORMAL"]["total_return_pct"] > ZERO for value in quarter_raw.values()
    )
    quarter_positive_stress = sum(
        value["STRESS"]["total_return_pct"] > ZERO for value in quarter_raw.values()
    )
    quarter_gates = {
        "normal_positive_total_return_at_least_12_of_20_quarters": quarter_positive_normal >= 12,
        "stress_positive_total_return_at_least_10_of_20_quarters": quarter_positive_stress >= 10,
    }
    all_gates = (
        not failed_window_gates
        and all(annual_gates.values())
        and all(quarter_gates.values())
    )
    return {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if all_gates
            else "NO_CANDIDATE_CLOSE_BTC_BINANCE_USDM_DELTA_NEUTRAL_FUNDING_CARRY_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_BREADTH_GATES_PASS_SEALED_OOS_REQUIRED"
            if all_gates
            else "PERMANENTLY_CLOSE_EXACT_QUARTERLY_RESET_25_75_BINANCE_BTCUSDT_DELTA_NEUTRAL_FUNDING_CARRY_FAMILY_WITHOUT_TUNING"
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
            "normalized_gzip_sha256": sha256(input_path),
            "normalized_csv_sha256": sha256(csv_raw),
            "hourly_rows": len(rows),
            "funding_events": sum(row.funding_rate is not None for row in rows),
            "spot_proxy_hours": sum(
                row.spot_price_source
                == "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP"
                for row in rows
            ),
            "spot_proxy_open_times_ms": [
                str(row.open_time_ms)
                for row in rows
                if row.spot_price_source
                == "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP"
            ],
            "selection_cutoff": "2025-01-01T00:00:00Z",
        },
        "comparator": {
            "identity": "ZERO_YIELD_CASH",
            "initial_equity": q(INITIAL_EQUITY),
            "final_equity": q(INITIAL_EQUITY),
            "total_pnl": q(ZERO),
            "maximum_drawdown_pct": q(ZERO),
        },
        "windows": windows,
        "annual_fair_reset": annual,
        "annual_breadth": {
            "normal_positive_years": normal_positive_years,
            "stress_positive_years": stress_positive_years,
            "normal_funding_minus_all_costs_positive_years": funding_after_cost_positive_years,
            "normal_drawdown_at_most_3pct_years": dd_at_most_three_years,
            "normal_no_liquidation_years": no_liquidation_years,
            "top_year_positive_total_pnl_contribution_pct": q(top_year_contribution),
            "gates": annual_gates,
        },
        "quarter_breadth": {
            "normal_positive_quarters": quarter_positive_normal,
            "stress_positive_quarters": quarter_positive_stress,
            "gates": quarter_gates,
        },
        "failed_window_gates": failed_window_gates,
        "failed_annual_gates": [gate for gate, passed in annual_gates.items() if not passed],
        "failed_quarter_gates": [gate for gate, passed in quarter_gates.items() if not passed],
        "all_gates_pass": all_gates,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only. A pass still requires one separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, key, second timer, second writer, canonical write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
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
    with output_path.open("x", encoding="ascii", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_window_gates": result["failed_window_gates"],
                "failed_annual_gates": result["failed_annual_gates"],
                "failed_quarter_gates": result["failed_quarter_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
