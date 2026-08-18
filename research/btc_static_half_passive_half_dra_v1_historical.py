#!/usr/bin/env python3
"""Deterministic historical screen for static passive BTC plus frozen DRA V1."""

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
from typing import Iterable


getcontext().prec = 34

D = Decimal
ZERO = D("0")
ONE = D("1")
HALF = D("0.5")
HUNDRED = D("100")
TWO_PP = D("2")
Q8 = D("0.00000001")

REPO_ROOT = Path(__file__).resolve().parents[1]
DRA_SOURCE = REPO_ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py"
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
EXPERIMENT_ID = "btc-static-half-passive-half-dra-v1-historical-v1"
EXPECTED_DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_DATA_ROWS = 52_608
EXPECTED_DRA_SHA256 = "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37"
EXPECTED_PASSIVE_REFERENCE_SHA256 = (
    "4ce8133148e691793c2d21419e11b9c2afaf70f9c2442b83d3b9c67e0fc68760"
)
EXPECTED_MANIFEST_TYPE = "BTC_STATIC_HALF_PASSIVE_HALF_DRA_HISTORICAL_MANIFEST_V1"


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def quantized(value: D) -> str:
    return str(value.quantize(Q8, rounding=ROUND_HALF_UP))


def ratio(numerator: D, denominator: D) -> D | None:
    return None if denominator == ZERO else numerator / denominator


def nullable(value: D | None) -> str | None:
    return None if value is None else quantized(value)


def load_dra_module() -> ModuleType:
    spec = importlib.util.spec_from_file_location("frozen_dra_v1", DRA_SOURCE)
    if spec is None or spec.loader is None:
        raise ResearchReject("SOURCE_REJECT:DRA_IMPORT_SPEC")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@dataclass
class PathMetrics:
    peak: D = ONE
    maximum_drawdown: D = ZERO
    current_underwater_hours: int = 0
    maximum_underwater_hours: int = 0
    passive_exposure_sum: D = ZERO
    dra_deployed_sum: D = ZERO
    total_market_exposure_sum: D = ZERO
    observations: int = 0

    def observe(
        self,
        equity: D,
        *,
        passive_market_value: D = ZERO,
        dra_deployed_fraction: D = ZERO,
        total_market_value: D = ZERO,
    ) -> None:
        if equity <= ZERO:
            raise ResearchReject("ECONOMIC_REJECT:NONPOSITIVE_EQUITY")
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
        self.passive_exposure_sum += passive_market_value / equity
        self.dra_deployed_sum += dra_deployed_fraction
        self.total_market_exposure_sum += total_market_value / equity
        self.observations += 1

    def output(self, final_equity: D) -> dict[str, object]:
        divisor = D(self.observations)
        total_return = (final_equity - ONE) * HUNDRED
        drawdown_pct = self.maximum_drawdown * HUNDRED
        return {
            "total_return_pct": quantized(total_return),
            "maximum_drawdown_pct": quantized(drawdown_pct),
            "maximum_underwater_duration_hours": self.maximum_underwater_hours,
            "calmar_ratio": nullable(ratio(total_return, drawdown_pct)),
            "average_passive_exposure_pct": quantized(
                self.passive_exposure_sum / divisor * HUNDRED
            ),
            "average_dra_deployed_capital_pct": quantized(
                self.dra_deployed_sum / divisor * HUNDRED
            ),
            "average_total_market_exposure_pct": quantized(
                self.total_market_exposure_sum / divisor * HUNDRED
            ),
        }


def dra_unrealized(module: ModuleType, engine: object, close: D) -> D:
    return module.money(
        sum(
            (
                module.estimated_net(lot.quantity, close) - lot.cost
                for lot in engine.lots
            ),
            ZERO,
        )
    )


def passive_position(module: ModuleType, first_open: D) -> tuple[D, D]:
    gross = ONE / (ONE + module.FEE)
    fee = gross * module.FEE
    fill = first_open * (ONE + module.SLIPPAGE)
    quantity = gross / fill
    cash = ONE - gross - fee
    return quantity, cash


def simulate_window(
    module: ModuleType,
    bars: list[object],
    window: tuple[datetime, datetime],
) -> tuple[dict[str, object], dict[str, D]]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar
        for bar in bars
        if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading or trading[0].open_time != start or trading[-1].close_time != end:
        raise ResearchReject(f"DATA_REJECT:WINDOW:{start.isoformat()}->{end.isoformat()}")

    engine = module.Engine("v1", cap=module.REFERENCE_CAP)
    passive_quantity, passive_cash = passive_position(module, trading[0].open)
    candidate_path = PathMetrics()
    primary_path = PathMetrics()
    dra_path = PathMetrics()
    passive_path = PathMetrics()
    candidate_equity = primary_equity = dra_equity = passive_equity = ONE

    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
            continue

        engine.step(bar)
        unrealized = dra_unrealized(module, engine, bar.close)
        dra_equity = (module.REFERENCE_CAP + engine.realized + unrealized) / module.REFERENCE_CAP
        passive_market_value = passive_quantity * bar.close
        passive_equity = passive_cash + passive_market_value
        candidate_equity = HALF * passive_equity + HALF * dra_equity
        primary_equity = HALF * passive_equity + HALF

        dra_market_value = sum((lot.quantity * bar.close for lot in engine.lots), ZERO)
        dra_deployed_fraction = HALF * (
            module.LOT_COST * D(len(engine.lots)) / module.REFERENCE_CAP
        )
        candidate_passive_market = HALF * passive_market_value
        candidate_dra_market = HALF * dra_market_value / module.REFERENCE_CAP
        candidate_path.observe(
            candidate_equity,
            passive_market_value=candidate_passive_market,
            dra_deployed_fraction=dra_deployed_fraction,
            total_market_value=candidate_passive_market + candidate_dra_market,
        )
        primary_path.observe(
            primary_equity,
            passive_market_value=HALF * passive_market_value,
            total_market_value=HALF * passive_market_value,
        )
        dra_path.observe(
            dra_equity,
            dra_deployed_fraction=module.LOT_COST
            * D(len(engine.lots))
            / module.REFERENCE_CAP,
            total_market_value=dra_market_value / module.REFERENCE_CAP,
        )
        passive_path.observe(
            passive_equity,
            passive_market_value=passive_market_value,
            total_market_value=passive_market_value,
        )

    final_bar = trading[-1]
    dra_result = engine.result(final_bar, start, end)
    candidate = candidate_path.output(candidate_equity)
    primary = primary_path.output(primary_equity)
    dra = dra_path.output(dra_equity)
    passive = passive_path.output(passive_equity)

    dra_realized_return = D(dra_result["realized_usdt"]) / module.REFERENCE_CAP * HUNDRED
    dra_unrealized_return = D(dra_result["unrealized_usdt"]) / module.REFERENCE_CAP * HUNDRED
    dra_total_return = D(dra_result["total_pnl_usdt"]) / module.REFERENCE_CAP * HUNDRED
    candidate_return = D(candidate["total_return_pct"])
    primary_return = D(primary["total_return_pct"])
    candidate_drawdown = D(candidate["maximum_drawdown_pct"])
    primary_drawdown = D(primary["maximum_drawdown_pct"])
    candidate_calmar = D(candidate["calmar_ratio"])
    primary_calmar = D(primary["calmar_ratio"])
    passive_return = D(passive["total_return_pct"])
    incremental_return = candidate_return - primary_return

    output = {
        "start": start.isoformat(),
        "end_exclusive": end.isoformat(),
        "candidate": candidate,
        "primary_50pct_passive_50pct_cash": primary,
        "frozen_dra_v1": {
            **dra,
            "checkpoint": list(module.checkpoint_tuple(dra_result)),
            "realized_return_pct": quantized(dra_realized_return),
            "unrealized_return_pct": quantized(dra_unrealized_return),
            "completed_lot_count": dra_result["sell_count"],
            "median_hold_hours": dra_result["median_hold_hours"],
            "p90_hold_hours": dra_result["p90_hold_hours"],
            "terminal_open_lots": dra_result["open_lots"],
            "terminal_open_cost_usdt": dra_result["ending_open_cost_usdt"],
        },
        "full_passive_btc": passive,
        "comparison": {
            "candidate_minus_primary_total_return_pp": quantized(incremental_return),
            "candidate_minus_primary_maximum_drawdown_pp": quantized(
                candidate_drawdown - primary_drawdown
            ),
            "candidate_minus_primary_calmar_ratio": quantized(
                candidate_calmar - primary_calmar
            ),
            "candidate_upside_capture_vs_passive": nullable(
                ratio(candidate_return, passive_return) if passive_return > ZERO else None
            ),
            "terminal_dra_unrealized_positive_incremental_contribution_pct": quantized(
                max(dra_unrealized_return, ZERO) / dra_total_return * HUNDRED
                if dra_total_return > ZERO
                else ZERO
            ),
        },
    }
    raw = {
        "candidate_return": candidate_return,
        "candidate_drawdown": candidate_drawdown,
        "candidate_calmar": candidate_calmar,
        "primary_return": primary_return,
        "primary_drawdown": primary_drawdown,
        "primary_calmar": primary_calmar,
        "dra_return": dra_total_return,
        "dra_unrealized_return": dra_unrealized_return,
        "passive_return": passive_return,
        "passive_drawdown": D(passive["maximum_drawdown_pct"]),
        "upside_capture": candidate_return / passive_return
        if passive_return > ZERO
        else ZERO,
    }
    return output, raw


def checkpoint_tuple(module: ModuleType, window: dict[str, object]) -> tuple[object, ...]:
    return tuple(window["frozen_dra_v1"]["checkpoint"])


def evaluate_gates(
    module: ModuleType,
    design_output: dict[str, object],
    validation_output: dict[str, object],
    design: dict[str, D],
    validation: dict[str, D],
    annual: dict[str, tuple[dict[str, object], dict[str, D]]],
) -> tuple[dict[str, bool], list[str], dict[str, object]]:
    gates: dict[str, bool] = {
        "dataset_sha256_and_52608_rows_match": True,
        "hourly_lattice_and_ohlcv_invariants_pass": True,
        "frozen_dra_source_and_passive_reference_sha256_match": True,
        "dra_design_and_validation_checkpoints_exact": (
            checkpoint_tuple(module, design_output) == module.EXPECTED["v1_design"]
            and checkpoint_tuple(module, validation_output)
            == module.EXPECTED["v1_validation"]
        ),
        "design_candidate_total_return_gt_primary": design["candidate_return"]
        > design["primary_return"],
        "design_candidate_total_return_gt_dra": design["candidate_return"]
        > design["dra_return"],
        "design_candidate_maximum_drawdown_at_most_primary_plus_2pp": design[
            "candidate_drawdown"
        ]
        <= design["primary_drawdown"] + TWO_PP,
        "design_candidate_calmar_at_least_primary": design["candidate_calmar"]
        >= design["primary_calmar"],
        "design_candidate_maximum_drawdown_at_most_75pct_of_passive": design[
            "candidate_drawdown"
        ]
        <= D("0.75") * design["passive_drawdown"],
        "validation_candidate_total_return_gt_primary": validation[
            "candidate_return"
        ]
        > validation["primary_return"],
        "validation_candidate_total_return_gt_dra": validation["candidate_return"]
        > validation["dra_return"],
        "validation_candidate_maximum_drawdown_at_most_primary_plus_2pp": validation[
            "candidate_drawdown"
        ]
        <= validation["primary_drawdown"] + TWO_PP,
        "validation_candidate_calmar_at_least_primary": validation[
            "candidate_calmar"
        ]
        >= validation["primary_calmar"],
        "validation_candidate_maximum_drawdown_at_most_75pct_of_passive": validation[
            "candidate_drawdown"
        ]
        <= D("0.75") * validation["passive_drawdown"],
        "validation_candidate_upside_capture_at_least_45pct": validation[
            "upside_capture"
        ]
        >= D("0.45"),
    }

    annual_raw = {year: value[1] for year, value in annual.items()}
    positive_years = sum(
        values["candidate_return"] > ZERO for values in annual_raw.values()
    )
    primary_wins = sum(
        values["candidate_return"] > values["primary_return"]
        for values in annual_raw.values()
    )
    primary_drawdown_nonworse = sum(
        values["candidate_drawdown"] <= values["primary_drawdown"] + TWO_PP
        for values in annual_raw.values()
    )
    passive_drawdown_nonworse = sum(
        values["candidate_drawdown"] <= values["passive_drawdown"]
        for values in annual_raw.values()
    )
    positive_incremental = [
        max(values["candidate_return"] - values["primary_return"], ZERO)
        for values in annual_raw.values()
    ]
    positive_incremental_total = sum(positive_incremental, ZERO)
    top_year_contribution = (
        max(positive_incremental, default=ZERO) / positive_incremental_total * HUNDRED
        if positive_incremental_total > ZERO
        else HUNDRED
    )
    validation_terminal = D(
        validation_output["comparison"][
            "terminal_dra_unrealized_positive_incremental_contribution_pct"
        ]
    )
    gates.update(
        {
            "candidate_positive_annual_total_return_at_least_4_of_5": positive_years
            >= 4,
            "candidate_total_return_gt_primary_at_least_4_of_5": primary_wins >= 4,
            "candidate_annual_drawdown_at_most_primary_plus_2pp_at_least_4_of_5": primary_drawdown_nonworse
            >= 4,
            "candidate_annual_drawdown_at_most_passive_at_least_4_of_5": passive_drawdown_nonworse
            >= 4,
            "top_year_positive_incremental_return_contribution_at_most_60pct": top_year_contribution
            <= D("60"),
            "validation_terminal_dra_unrealized_positive_incremental_contribution_at_most_40pct": validation_terminal
            <= D("40"),
        }
    )
    breadth = {
        "positive_candidate_years": f"{positive_years}_of_5",
        "candidate_total_return_gt_primary_years": f"{primary_wins}_of_5",
        "candidate_drawdown_at_most_primary_plus_2pp_years": f"{primary_drawdown_nonworse}_of_5",
        "candidate_drawdown_at_most_passive_years": f"{passive_drawdown_nonworse}_of_5",
        "top_year_positive_incremental_return_contribution_pct": quantized(
            top_year_contribution
        ),
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
    if manifest.get("portfolio_policy", {}).get("variants") != 1:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    data_sha = sha256(input_path)
    dra_sha = sha256(DRA_SOURCE)
    passive_reference_sha = sha256(PASSIVE_REFERENCE)
    if data_sha != EXPECTED_DATA_SHA256:
        raise ResearchReject(f"DATA_REJECT:SHA256:{data_sha}")
    if dra_sha != EXPECTED_DRA_SHA256:
        raise ResearchReject(f"SOURCE_REJECT:DRA_SHA256:{dra_sha}")
    if passive_reference_sha != EXPECTED_PASSIVE_REFERENCE_SHA256:
        raise ResearchReject(
            f"SOURCE_REJECT:PASSIVE_REFERENCE_SHA256:{passive_reference_sha}"
        )

    module = load_dra_module()
    bars = module.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_DATA_ROWS or module.data_hash(bars) != EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")

    design_output, design_raw = simulate_window(module, bars, module.DESIGN)
    validation_output, validation_raw = simulate_window(module, bars, module.VALIDATION)
    annual = {
        year: simulate_window(module, bars, window)
        for year, window in module.FOLDS.items()
    }
    gates, failed, breadth = evaluate_gates(
        module,
        design_output,
        validation_output,
        design_raw,
        validation_raw,
        annual,
    )
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_STATIC_HALF_PASSIVE_HALF_DRA_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if passed
            else "NO_CANDIDATE_CLOSE_BTC_STATIC_HALF_PASSIVE_HALF_DRA_V1_FAMILY"
        ),
        "decision": (
            "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED"
            if passed
            else "PERMANENTLY_CLOSE_EXACT_STATIC_50_50_FAMILY_WITHOUT_TUNING"
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
        },
        "source_bindings": {
            "frozen_dra_v1_sha256": dra_sha,
            "passive_btc_valuation_reference_sha256": passive_reference_sha,
        },
        "policy": {
            "passive_btc_initial_weight": "0.50",
            "dra_v1_initial_weight": "0.50",
            "rebalance": "NONE",
            "variants": 1,
            "primary_comparator": "STATIC_50PCT_PASSIVE_BTC_50PCT_CASH",
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
            "Historical matched-risk-budget portfolio evidence only; a pass is not independent alpha or permission to activate."
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
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "failed_gates": result["failed_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
