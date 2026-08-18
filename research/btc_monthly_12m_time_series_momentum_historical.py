#!/usr/bin/env python3
"""Deterministic historical screen for monthly twelve-month BTC momentum."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 34

D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
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
EXPERIMENT_ID = "btc-monthly-12m-time-series-momentum-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_MONTHLY_12M_TIME_SERIES_MOMENTUM_HISTORICAL_MANIFEST_V1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_PARSER_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PASSIVE_REFERENCE_SHA256 = (
    "4ce8133148e691793c2d21419e11b9c2afaf70f9c2442b83d3b9c67e0fc68760"
)
DESIGN = (datetime(2020, 2, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    "2020": (datetime(2020, 2, 1), datetime(2021, 1, 1)),
    "2021": (datetime(2021, 1, 1), datetime(2022, 1, 1)),
    "2022": (datetime(2022, 1, 1), datetime(2023, 1, 1)),
    "2023": (datetime(2023, 1, 1), datetime(2024, 1, 1)),
    "2024": (datetime(2024, 1, 1), datetime(2025, 1, 1)),
}
SCENARIOS = {
    "NORMAL": (D("0.0010"), D("0.0005")),
    "STRESS": (D("0.0020"), D("0.0010")),
}


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


def nullable(value: D | None) -> str | None:
    return None if value is None else q(value)


def percentile(values: list[D], fraction: D) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = D(len(ordered) - 1) * fraction
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - D(low))


def load_parser() -> ModuleType:
    spec = importlib.util.spec_from_file_location("frozen_h1_parser", PARSER_SOURCE)
    if spec is None or spec.loader is None:
        raise ResearchReject("SOURCE_REJECT:PARSER_IMPORT_SPEC")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def is_month_end_bar(bar: object) -> bool:
    return (
        bar.open_time.month != bar.close_time.month
        and bar.close_time.day == 1
        and bar.close_time.hour == 0
        and bar.close_time.minute == 0
        and bar.close_time.second == 0
    )


def validate_month_ends(bars: list[object]) -> list[tuple[datetime, D]]:
    values = [(bar.close_time, bar.close) for bar in bars if is_month_end_bar(bar)]
    expected: list[tuple[int, int]] = []
    year, month = 2019, 1
    while (year, month) <= (2024, 12):
        expected.append((year, month))
        month += 1
        if month == 13:
            year += 1
            month = 1
    actual = []
    for close_time, _ in values:
        previous = close_time - timedelta(hours=1)
        actual.append((previous.year, previous.month))
    if actual != expected or len(values) != 72:
        raise ResearchReject(
            f"DATA_REJECT:MONTH_END_INVENTORY:{len(values)}:{actual[:1]}:{actual[-1:]}"
        )
    return values


@dataclass
class PathAccumulator:
    peak: D = ONE
    maximum_drawdown: D = ZERO
    current_underwater_hours: int = 0
    maximum_underwater_hours: int = 0
    exposure_sum: D = ZERO
    observations: int = 0

    def observe(self, equity: D, exposure: D) -> None:
        if equity <= ZERO or exposure < ZERO:
            raise ResearchReject("ECONOMIC_REJECT:INVALID_PATH")
        if equity > self.peak:
            self.peak = equity
            self.current_underwater_hours = 0
        elif equity < self.peak:
            self.current_underwater_hours += 1
            self.maximum_underwater_hours = max(
                self.maximum_underwater_hours, self.current_underwater_hours
            )
            self.maximum_drawdown = max(
                self.maximum_drawdown, (self.peak - equity) / self.peak
            )
        else:
            self.current_underwater_hours = 0
        self.exposure_sum += exposure
        self.observations += 1

    def metrics(self, final_equity: D) -> tuple[dict[str, object], dict[str, D]]:
        total_return = (final_equity - ONE) * HUNDRED
        drawdown = self.maximum_drawdown * HUNDRED
        calmar = total_return / drawdown if drawdown > ZERO else None
        output = {
            "total_return_pct": q(total_return),
            "maximum_drawdown_pct": q(drawdown),
            "maximum_underwater_duration_hours": self.maximum_underwater_hours,
            "average_exposure_pct": q(
                self.exposure_sum / D(self.observations) * HUNDRED
            ),
            "calmar_ratio": nullable(calmar),
        }
        raw = {
            "total_return": total_return,
            "drawdown": drawdown,
            "calmar": calmar if calmar is not None else ZERO,
        }
        return output, raw


def buy_all(cash: D, open_price: D, fee_rate: D, slippage: D) -> tuple[D, D, D, D]:
    gross = cash / (ONE + fee_rate)
    fee = gross * fee_rate
    fill = open_price * (ONE + slippage)
    quantity = gross / fill
    residual_cash = cash - gross - fee
    return quantity, residual_cash, fee, gross


def sell_all(quantity: D, price: D, fee_rate: D, slippage: D) -> tuple[D, D, D]:
    fill = price * (ONE - slippage)
    gross = quantity * fill
    fee = gross * fee_rate
    return gross - fee, fee, gross


def passive_benchmark(
    trading: list[object], fee_rate: D, slippage: D
) -> tuple[dict[str, object], dict[str, D]]:
    quantity, cash, fee, gross = buy_all(ONE, trading[0].open, fee_rate, slippage)
    path = PathAccumulator()
    equity = ONE
    for bar in trading:
        market_value = quantity * bar.close
        equity = cash + market_value
        path.observe(equity, market_value / equity)
    output, raw = path.metrics(equity)
    output.update(
        {
            "fees_equity_units": q(fee),
            "turnover_equity_units": q(gross),
        }
    )
    return output, raw


def simulate_scenario(
    bars: list[object],
    window: tuple[datetime, datetime],
    fee_rate: D,
    slippage: D,
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    warmup_start = start - timedelta(days=400)
    selected = [
        bar
        for bar in bars
        if bar.close_time > warmup_start and bar.close_time <= end
    ]
    trading = [bar for bar in selected if start <= bar.open_time < end]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    month_closes: list[D] = []
    pending_target: bool | None = None
    cash = ONE
    quantity = ZERO
    entry_cost: D | None = None
    entry_time: datetime | None = None
    realized = ZERO
    fees = ZERO
    turnover = ZERO
    signal_evaluations = 0
    long_targets = 0
    cash_targets = 0
    position_changes = 0
    episode_pnls: list[D] = []
    hold_hours: list[D] = []
    path = PathAccumulator()
    final_equity = ONE

    for bar in selected:
        in_window = start <= bar.open_time < end
        if in_window and pending_target is not None:
            if pending_target and quantity == ZERO:
                entry_cost = cash
                quantity, cash, fee, gross = buy_all(
                    cash, bar.open, fee_rate, slippage
                )
                entry_time = bar.open_time
                fees += fee
                turnover += gross
                position_changes += 1
            elif not pending_target and quantity > ZERO:
                net, fee, gross = sell_all(
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
                entry_cost = None
                entry_time = None
                fees += fee
                turnover += gross
                position_changes += 1
            pending_target = None

        if in_window:
            market_value = quantity * bar.close
            final_equity = cash + market_value
            path.observe(
                final_equity,
                market_value / final_equity if final_equity > ZERO else ZERO,
            )

        if is_month_end_bar(bar):
            month_closes.append(bar.close)
            if len(month_closes) >= 13 and start <= bar.close_time < end:
                momentum = month_closes[-1] / month_closes[-13] - ONE
                pending_target = momentum > ZERO
                signal_evaluations += 1
                if pending_target:
                    long_targets += 1
                else:
                    cash_targets += 1

    candidate, raw = path.metrics(final_equity)
    total_pnl = final_equity - ONE
    unrealized = total_pnl - realized
    terminal_liquidation_equity = final_equity
    if quantity > ZERO:
        terminal_net, _, _ = sell_all(
            quantity, trading[-1].close, fee_rate, slippage
        )
        terminal_liquidation_equity = cash + terminal_net
    terminal_liquidation_return = (terminal_liquidation_equity - ONE) * HUNDRED
    candidate.update(
        {
            "realized_return_pct": q(realized * HUNDRED),
            "unrealized_return_pct": q(unrealized * HUNDRED),
            "terminal_liquidation_adjusted_return_pct": q(
                terminal_liquidation_return
            ),
            "terminal_liquidation_cost_pp": q(
                raw["total_return"] - terminal_liquidation_return
            ),
            "fees_equity_units": q(fees),
            "turnover_equity_units": q(turnover),
            "signal_evaluation_count": signal_evaluations,
            "long_target_count": long_targets,
            "cash_target_count": cash_targets,
            "position_change_count": position_changes,
            "completed_episode_count": len(episode_pnls),
            "winning_episode_count": sum(pnl > ZERO for pnl in episode_pnls),
            "median_hold_hours": nullable(percentile(hold_hours, D("0.5"))),
            "p90_hold_hours": nullable(percentile(hold_hours, D("0.9"))),
            "terminal_position": quantity > ZERO,
            "terminal_holding_age_hours": (
                None
                if entry_time is None
                else q(D(str((end - entry_time).total_seconds() / 3600)))
            ),
            "top_positive_episode_contribution_pct": (
                None
                if not any(pnl > ZERO for pnl in episode_pnls)
                else q(
                    max(pnl for pnl in episode_pnls if pnl > ZERO)
                    / sum((pnl for pnl in episode_pnls if pnl > ZERO), ZERO)
                    * HUNDRED
                )
            ),
        }
    )
    raw.update(
        {
            "terminal_liquidation_return": terminal_liquidation_return,
            "terminal_liquidation_cost": raw["total_return"]
            - terminal_liquidation_return,
            "position_changes": D(position_changes),
            "signal_evaluations": D(signal_evaluations),
            "p90_hold": percentile(hold_hours, D("0.9")) or ZERO,
            "terminal_holding_age": (
                ZERO
                if entry_time is None
                else D(str((end - entry_time).total_seconds() / 3600))
            ),
            "top_positive_episode_contribution": (
                ZERO
                if not any(pnl > ZERO for pnl in episode_pnls)
                else max(pnl for pnl in episode_pnls if pnl > ZERO)
                / sum((pnl for pnl in episode_pnls if pnl > ZERO), ZERO)
                * HUNDRED
            ),
            "has_positive_episode": ONE
            if any(pnl > ZERO for pnl in episode_pnls)
            else ZERO,
        }
    )
    benchmark, benchmark_raw = passive_benchmark(trading, fee_rate, slippage)
    upside_capture = (
        raw["total_return"] / benchmark_raw["total_return"]
        if benchmark_raw["total_return"] > ZERO
        else None
    )
    candidate_calmar = raw["calmar"]
    benchmark_calmar = benchmark_raw["calmar"]
    return {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate": candidate,
        "buy_and_hold": benchmark,
        "comparison": {
            "total_return_delta_pp": q(
                raw["total_return"] - benchmark_raw["total_return"]
            ),
            "maximum_drawdown_delta_pp": q(
                raw["drawdown"] - benchmark_raw["drawdown"]
            ),
            "upside_capture_ratio": nullable(upside_capture),
            "calmar_ratio_to_buy_hold": nullable(
                candidate_calmar / benchmark_calmar
                if benchmark_calmar != ZERO
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
    bars: list[object], window: tuple[datetime, datetime]
) -> tuple[dict[str, object], dict[str, dict[str, D]]]:
    outputs: dict[str, object] = {}
    raws: dict[str, dict[str, D]] = {}
    for name, (fee_rate, slippage) in SCENARIOS.items():
        outputs[name], raws[name] = simulate_scenario(
            bars, window, fee_rate, slippage
        )
    return outputs, raws


def evaluate_gates(
    design_output: dict[str, object],
    validation_output: dict[str, object],
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, object], dict[str, dict[str, D]]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    dn = design["NORMAL"]
    ds = design["STRESS"]
    vn = validation["NORMAL"]
    vs = validation["STRESS"]
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_ohlcv_invariants_pass": True,
        "frozen_parser_and_passive_reference_sha256_match": True,
        "month_end_inventory_is_complete_and_strictly_ordered": True,
        "design_normal_total_return_pct_gt_0": dn["total_return"] > ZERO,
        "design_stress_total_return_pct_gt_0": ds["total_return"] > ZERO,
        "design_normal_maximum_drawdown_at_most_75pct_of_buy_hold": dn[
            "drawdown"
        ]
        <= D("0.75") * dn["buy_hold_drawdown"],
        "design_normal_upside_capture_at_least_50pct": dn["upside_capture"]
        >= D("0.50"),
        "design_normal_calmar_at_least_buy_hold": dn["calmar"]
        >= dn["buy_hold_calmar"],
        "design_normal_position_changes_at_least_2": dn["position_changes"]
        >= D("2"),
        "validation_normal_total_return_pct_gt_0": vn["total_return"] > ZERO,
        "validation_stress_total_return_pct_gt_0": vs["total_return"] > ZERO,
        "validation_normal_maximum_drawdown_at_most_90pct_of_buy_hold": vn[
            "drawdown"
        ]
        <= D("0.90") * vn["buy_hold_drawdown"],
        "validation_normal_upside_capture_at_least_60pct": vn["upside_capture"]
        >= D("0.60"),
        "validation_normal_calmar_at_least_75pct_of_buy_hold": vn["calmar"]
        >= D("0.75") * vn["buy_hold_calmar"],
        "validation_normal_signal_evaluations_at_least_24": vn[
            "signal_evaluations"
        ]
        >= D("24"),
        "validation_normal_position_changes_at_least_1": vn["position_changes"]
        >= ONE,
        "validation_stress_maximum_drawdown_no_more_than_normal_plus_3pp": vs[
            "drawdown"
        ]
        <= vn["drawdown"] + D("3"),
    }

    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(
        value["NORMAL"]["total_return"] > ZERO for value in annual_raw.values()
    )
    stress_positive = sum(
        value["STRESS"]["total_return"] > ZERO for value in annual_raw.values()
    )
    drawdown_nonworse = sum(
        value["NORMAL"]["drawdown"]
        <= value["NORMAL"]["buy_hold_drawdown"]
        for value in annual_raw.values()
    )
    calmar_breadth = sum(
        value["NORMAL"]["calmar"]
        >= D("0.75") * value["NORMAL"]["buy_hold_calmar"]
        for value in annual_raw.values()
    )
    positive_year_returns = [
        max(value["NORMAL"]["total_return"], ZERO)
        for value in annual_raw.values()
    ]
    positive_sum = sum(positive_year_returns, ZERO)
    top_year = (
        max(positive_year_returns, default=ZERO) / positive_sum * HUNDRED
        if positive_sum > ZERO
        else HUNDRED
    )
    top_episode = vn["top_positive_episode_contribution"]
    gates.update(
        {
            "normal_positive_annual_total_return_at_least_4_of_5": normal_positive
            >= 4,
            "stress_positive_annual_total_return_at_least_4_of_5": stress_positive
            >= 4,
            "normal_annual_drawdown_non_worse_than_buy_hold_at_least_4_of_5": drawdown_nonworse
            >= 4,
            "normal_annual_calmar_at_least_75pct_of_buy_hold_at_least_3_of_5": calmar_breadth
            >= 3,
            "top_year_positive_total_return_contribution_at_most_60pct": top_year
            <= D("60"),
            "top_episode_positive_realized_contribution_at_most_60pct_when_completed_positive_episodes_exist": (
                vn["has_positive_episode"] == ZERO or top_episode <= D("60")
            ),
            "validation_normal_p90_completed_episode_hold_at_most_17520_hours_when_completed_episodes_exist": vn[
                "p90_hold"
            ]
            <= D("17520"),
            "validation_normal_terminal_holding_age_at_most_17520_hours_when_position_open": vn[
                "terminal_holding_age"
            ]
            <= D("17520"),
            "validation_normal_terminal_liquidation_adjusted_return_pct_gt_0": vn[
                "terminal_liquidation_return"
            ]
            > ZERO,
            "validation_normal_terminal_liquidation_cost_at_most_1pp_of_mark_to_market_return": vn[
                "terminal_liquidation_cost"
            ]
            <= ONE,
        }
    )
    breadth = {
        "normal_positive_years": f"{normal_positive}_of_5",
        "stress_positive_years": f"{stress_positive}_of_5",
        "normal_drawdown_non_worse_than_buy_hold_years": f"{drawdown_nonworse}_of_5",
        "normal_calmar_at_least_75pct_of_buy_hold_years": f"{calmar_breadth}_of_5",
        "top_year_positive_total_return_contribution_pct": q(top_year),
        "validation_top_positive_episode_contribution_pct": validation_output[
            "NORMAL"
        ]["candidate"]["top_positive_episode_contribution_pct"],
    }
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed, breadth


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("strategy_policy", {}).get("variants") != 1:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    data_sha = sha256(input_path)
    parser_sha = sha256(PARSER_SOURCE)
    passive_reference_sha = sha256(PASSIVE_REFERENCE)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")
    if parser_sha != EXPECTED_PARSER_SHA256:
        raise ResearchReject(f"SOURCE_REJECT:PARSER_SHA256:{parser_sha}")
    if passive_reference_sha != EXPECTED_PASSIVE_REFERENCE_SHA256:
        raise ResearchReject(
            f"SOURCE_REJECT:PASSIVE_REFERENCE_SHA256:{passive_reference_sha}"
        )

    parser = load_parser()
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or parser.data_hash(bars) != data_sha:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    month_ends = validate_month_ends(bars)

    design_output, design_raw = simulate_window(bars, DESIGN)
    validation_output, validation_raw = simulate_window(bars, VALIDATION)
    annual = {year: simulate_window(bars, window) for year, window in ANNUAL.items()}
    gates, failed, breadth = evaluate_gates(
        design_output,
        validation_output,
        design_raw,
        validation_raw,
        annual,
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_MONTHLY_12M_TIME_SERIES_MOMENTUM_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_MONTHLY_12M_TIME_SERIES_MOMENTUM_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_MONTHLY_12M_SIGN_FAMILY_WITHOUT_TUNING"
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
            "sha256": data_sha,
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
            "month_end_count": len(month_ends),
            "first_month_end": month_ends[0][0].isoformat(),
            "last_month_end": month_ends[-1][0].isoformat(),
        },
        "source_bindings": {
            "frozen_h1_parser_sha256": parser_sha,
            "passive_btc_valuation_reference_sha256": passive_reference_sha,
        },
        "policy": {
            "lookback_complete_month_ends": 12,
            "positive_target": "LONG_100_PERCENT",
            "nonpositive_target": "CASH_100_PERCENT",
            "execution": "NEXT_HOURLY_OPEN",
            "variants": 1,
        },
        "windows": {
            "design": design_output,
            "validation": validation_output,
        },
        "annual_fair_reset": {
            year: value[0] for year, value in annual.items()
        },
        "breadth_and_concentration": breadth,
        "gates": gates,
        "failed_gates": failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": (
            "Historical preregistered Design and Validation only; a pass is not independent OOS, activation authority or proof that another trend horizon works."
        ),
        "scope_note": (
            "No paid API, second timer, second writer, backfill, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
        ),
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
                "failed_gates": result["failed_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
