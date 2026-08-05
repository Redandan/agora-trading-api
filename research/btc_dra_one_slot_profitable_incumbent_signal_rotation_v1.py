#!/usr/bin/env python3
"""Frozen one-slot DRA profitable-incumbent fresh-signal rotation research."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
ZERO = D("0")
ONE_SLOT_CAP = D("30.00")
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
IDENTITY = "BTC_DRA_ONE_SLOT_PROFITABLE_INCUMBENT_SIGNAL_ROTATION_V1_RESEARCH"
SPEC_PATH = (
    Path(__file__).resolve().parents[1]
    / "docs"
    / "btc-dra-one-slot-profitable-incumbent-signal-rotation-v1-research.md"
)
BASE_PATH = Path(base.__file__).resolve()
EXPECTED_SOURCES = {
    "specification": (
        SPEC_PATH,
        "9d64a8d18df34a9d3551250fa38eff9b8c8749a4f9accc8b95f4a1e725cf6941",
    ),
    "python_reference_engine": (
        BASE_PATH,
        "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
    ),
}
EXPECTED_PARENT = {
    "design": (
        "51.82581283",
        "-22.31327703",
        "29.51253580",
        "40.321240",
        112.0,
        1704.8,
        29,
        28,
        1,
        273,
        "90.089551",
        "891.82581283",
    ),
    "validation": (
        "43.54302055",
        "-3.60947416",
        "39.93354639",
        "17.699055",
        146.0,
        775.6,
        26,
        25,
        1,
        117,
        "88.377793",
        "793.54302055",
    ),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources() -> dict[str, dict[str, str]]:
    evidence: dict[str, dict[str, str]] = {}
    mismatches: list[dict[str, str]] = []
    root = Path(__file__).resolve().parents[1]
    for name, (path, expected) in EXPECTED_SOURCES.items():
        if not path.is_file():
            mismatches.append({"source": name, "reason": "MISSING", "path": str(path)})
            continue
        actual = sha256(path)
        evidence[name] = {"path": str(path.relative_to(root)), "sha256": actual}
        if actual != expected:
            mismatches.append(
                {"source": name, "expected_sha256": expected, "actual_sha256": actual}
            )
    if mismatches:
        raise base.ResearchReject("BASELINE_REJECT", {"source_mismatches": mismatches})
    return evidence


class RotationEngine(base.Engine):
    def __init__(self) -> None:
        super().__init__("v1", cap=ONE_SLOT_CAP)
        self.confirmation_count = 0
        self.rotation_attempt_count = 0
        self.rotation_sale_count = 0
        self.rotation_cancel_count = 0
        self.rotation_replacement_buy_count = 0
        self.rotation_parent_queue_count = 0
        self.rotation_candidate_queue_count = 0
        self.rotation_pending = False
        self.rotation_fill_ready = False
        self.rotation_target_signal_time: datetime | None = None
        self.active_rotation_record: int | None = None
        self.rotation_records: list[dict[str, object]] = []
        self.max_lots_observed = 0
        self.max_open_cost_observed = ZERO

    def _fill_exits(self, bar: base.Bar) -> None:
        target = self.rotation_target_signal_time if self.rotation_pending else None
        target_lot = next(
            (lot for lot in self.lots if lot.signal_time == target), None
        )
        actual_return = None
        actual_pnl = None
        if target_lot is not None:
            net = base.estimated_net(target_lot.quantity, bar.open)
            actual_return = base.net_return(net, target_lot.cost)
            actual_pnl = base.money(net - target_lot.cost)

        super()._fill_exits(bar)

        if target is None:
            return
        if self.active_rotation_record is None:
            raise base.ResearchReject(
                "ACCOUNTING_REJECT", "rotation target has no active audit record"
            )
        record = self.rotation_records[self.active_rotation_record]
        record["next_open_time"] = bar.open_time.isoformat()
        record["actual_next_open_net_return"] = (
            None if actual_return is None else str(actual_return)
        )
        record["actual_next_open_net_pnl_usdt"] = (
            None if actual_pnl is None else str(actual_pnl)
        )
        target_still_open = any(lot.signal_time == target for lot in self.lots)
        if target_still_open:
            self.rotation_cancel_count += 1
            record["outcome"] = "SALE_DEFERRED_REPLACEMENT_CANCELLED"
            self.pending_signal = None
            self.pending_atr = None
            self.rotation_pending = False
            self.rotation_fill_ready = False
            self.rotation_target_signal_time = None
            self.active_rotation_record = None
        else:
            self.rotation_sale_count += 1
            record["outcome"] = "SALE_FILLED_AWAITING_REPLACEMENT"
            self.rotation_fill_ready = True

    def _fill_buy(self, bar: base.Bar) -> None:
        rotation_buy = self.rotation_pending and self.rotation_fill_ready
        prior_count = self.buy_count
        super()._fill_buy(bar)
        if not rotation_buy:
            return
        if self.buy_count != prior_count + 1 or self.active_rotation_record is None:
            raise base.ResearchReject(
                "ACCOUNTING_REJECT", "successful rotation sale did not produce one buy"
            )
        record = self.rotation_records[self.active_rotation_record]
        record["replacement_fill_time"] = bar.open_time.isoformat()
        record["outcome"] = "SALE_FILLED_REPLACED"
        self.rotation_replacement_buy_count += 1
        self.rotation_pending = False
        self.rotation_fill_ready = False
        self.rotation_target_signal_time = None
        self.active_rotation_record = None

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None

        confirmation = (
            self.armed_at is not None
            and bar.open_time > self.armed_at
            and self._signal(bar)
        )
        if confirmation:
            self.confirmation_count += 1
            open_cost = sum((lot.cost for lot in self.lots), ZERO)
            if open_cost + base.LOT_COST > self.cap:
                self._attempt_rotation_or_block(bar)
            else:
                self.pending_signal = bar.open_time
                self.pending_atr = self.atr14
                self.last_entry_signal = bar.open_time
            self.armed_at = None
            self.arm_expires_at = None

        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def _attempt_rotation_or_block(self, bar: base.Bar) -> None:
        if len(self.lots) != 1 or self.rotation_pending:
            self.blocked_count += 1
            return
        incumbent = self.lots[0]
        close_net = base.estimated_net(incumbent.quantity, bar.close)
        close_return = base.net_return(close_net, incumbent.cost)
        if close_return < base.V1_FILL_RETURN:
            self.blocked_count += 1
            return

        queue_source = (
            "PARENT_EXIT_ALREADY_QUEUED"
            if incumbent.exit_queued_at is not None
            else "ROTATION_EXIT_QUEUED"
        )
        if incumbent.exit_queued_at is None:
            incumbent.exit_queued_at = bar.open_time
            self.rotation_candidate_queue_count += 1
        else:
            self.rotation_parent_queue_count += 1
        self.pending_signal = bar.open_time
        self.pending_atr = self.atr14
        self.last_entry_signal = bar.open_time
        self.rotation_pending = True
        self.rotation_fill_ready = False
        self.rotation_target_signal_time = incumbent.signal_time
        self.rotation_attempt_count += 1
        self.rotation_records.append(
            {
                "attempt_signal_time": bar.open_time.isoformat(),
                "incumbent_signal_time": incumbent.signal_time.isoformat(),
                "incumbent_fill_time": incumbent.fill_time.isoformat(),
                "signal_close_net_return": str(close_return),
                "signal_close_net_pnl_usdt": str(
                    base.money(close_net - incumbent.cost)
                ),
                "queue_source": queue_source,
                "next_open_time": None,
                "actual_next_open_net_return": None,
                "actual_next_open_net_pnl_usdt": None,
                "replacement_fill_time": None,
                "outcome": "PENDING_NEXT_OPEN",
            }
        )
        self.active_rotation_record = len(self.rotation_records) - 1

    def step(self, bar: base.Bar) -> None:
        super().step(bar)
        self.max_lots_observed = max(self.max_lots_observed, len(self.lots))
        self.max_open_cost_observed = max(
            self.max_open_cost_observed,
            sum((lot.cost for lot in self.lots), ZERO),
        )

    def audited_result(
        self,
        final_bar: base.Bar,
        start: datetime,
        end: datetime,
    ) -> dict[str, object]:
        result: dict[str, object] = self.result(final_bar, start, end)
        terminal_pending = int(self.rotation_pending)
        accounting = {
            "attempts_equal_sales_cancels_pending": self.rotation_attempt_count
            == self.rotation_sale_count + self.rotation_cancel_count + terminal_pending,
            "sales_equal_replacement_buys": self.rotation_sale_count
            == self.rotation_replacement_buy_count,
            "at_most_one_open_lot": self.max_lots_observed <= 1,
            "max_open_cost_at_most_30": self.max_open_cost_observed <= ONE_SLOT_CAP,
            "all_attempts_close_return_at_least_one_percent": all(
                D(str(row["signal_close_net_return"])) >= base.V1_FILL_RETURN
                for row in self.rotation_records
            ),
            "all_filled_sales_next_open_at_least_one_percent": all(
                row["outcome"] != "SALE_FILLED_REPLACED"
                or D(str(row["actual_next_open_net_return"])) >= base.V1_FILL_RETURN
                for row in self.rotation_records
            ),
            "replacement_fill_matches_next_open": all(
                row["outcome"] != "SALE_FILLED_REPLACED"
                or row["replacement_fill_time"] == row["next_open_time"]
                for row in self.rotation_records
            ),
            "cancelled_attempt_has_no_replacement": all(
                row["outcome"] != "SALE_DEFERRED_REPLACEMENT_CANCELLED"
                or row["replacement_fill_time"] is None
                for row in self.rotation_records
            ),
            "next_open_is_strictly_after_signal": all(
                row["next_open_time"] is None
                or datetime.fromisoformat(str(row["next_open_time"]))
                == datetime.fromisoformat(str(row["attempt_signal_time"]))
                + timedelta(hours=1)
                for row in self.rotation_records
            ),
        }
        result.update(
            {
                "confirmation_count": self.confirmation_count,
                "rotation_attempt_count": self.rotation_attempt_count,
                "rotation_sale_count": self.rotation_sale_count,
                "rotation_cancel_count": self.rotation_cancel_count,
                "rotation_replacement_buy_count": self.rotation_replacement_buy_count,
                "rotation_parent_queue_count": self.rotation_parent_queue_count,
                "rotation_candidate_queue_count": self.rotation_candidate_queue_count,
                "terminal_pending_rotation": terminal_pending,
                "max_lots_observed": self.max_lots_observed,
                "max_open_cost_observed_usdt": str(
                    base.money(self.max_open_cost_observed)
                ),
                "accounting_audit": accounting,
                "accounting_audit_pass": all(accounting.values()),
                "rotation_records": self.rotation_records,
            }
        )
        return result


def simulate_rotation(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
) -> dict[str, object]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject("DATA_REJECT", f"no bars for {start}..{end}")
    engine = RotationEngine()
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.audited_result(trading[-1], start, end)


def metric(result: dict[str, object], field: str) -> D:
    return D(str(result[field]))


def build_result() -> dict[str, object]:
    source_evidence = verify_sources()
    bars = base.parse_rows(base.fetch_rows(base.SELECTION_CUTOFF))
    selection_hash = base.data_hash(bars)
    if len(bars) != base.SELECTION_ROWS or selection_hash != base.SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": base.SELECTION_ROWS,
                "actual_rows": len(bars),
                "expected_sha256": base.SELECTION_SHA256,
                "actual_sha256": selection_hash,
            },
        )

    parent = {
        "design": base.simulate(bars, base.DESIGN, "v1", cap=ONE_SLOT_CAP),
        "validation": base.simulate(
            bars, base.VALIDATION, "v1", cap=ONE_SLOT_CAP
        ),
    }
    baseline_mismatches = []
    for name in ("design", "validation"):
        actual = base.checkpoint_tuple(parent[name])
        if actual != EXPECTED_PARENT[name]:
            baseline_mismatches.append(
                {
                    "window": name,
                    "expected": list(EXPECTED_PARENT[name]),
                    "actual": list(actual),
                }
            )
    if baseline_mismatches:
        raise base.ResearchReject(
            "BASELINE_REJECT", {"checkpoint_mismatches": baseline_mismatches}
        )

    candidate = {
        "design": simulate_rotation(bars, base.DESIGN),
        "validation": simulate_rotation(bars, base.VALIDATION),
    }
    parent_folds = {
        year: base.simulate(bars, window, "v1", cap=ONE_SLOT_CAP)
        for year, window in base.FOLDS.items()
    }
    candidate_folds = {
        year: simulate_rotation(bars, window)
        for year, window in base.FOLDS.items()
    }

    annual_total_wins = sum(
        metric(candidate_folds[year], "total_pnl_usdt")
        > metric(parent_folds[year], "total_pnl_usdt")
        for year in base.FOLDS
    )
    annual_drawdown_nonworse = sum(
        metric(candidate_folds[year], "max_drawdown_pct")
        <= metric(parent_folds[year], "max_drawdown_pct")
        for year in base.FOLDS
    )
    all_accounting_pass = all(
        bool(result["accounting_audit_pass"])
        for result in [*candidate.values(), *candidate_folds.values()]
    )
    if not all_accounting_pass:
        raise base.ResearchReject(
            "ACCOUNTING_REJECT",
            {
                "windows": {
                    name: result["accounting_audit"]
                    for name, result in candidate.items()
                },
                "folds": {
                    year: result["accounting_audit"]
                    for year, result in candidate_folds.items()
                },
            },
        )

    design_parent = parent["design"]
    design_candidate = candidate["design"]
    validation_parent = parent["validation"]
    validation_candidate = candidate["validation"]
    gates = {
        "design_total_at_least_parent": metric(
            design_candidate, "total_pnl_usdt"
        )
        >= metric(design_parent, "total_pnl_usdt"),
        "design_drawdown_no_higher_than_parent": metric(
            design_candidate, "max_drawdown_pct"
        )
        <= metric(design_parent, "max_drawdown_pct"),
        "validation_realized_strictly_greater_than_parent": metric(
            validation_candidate, "realized_usdt"
        )
        > metric(validation_parent, "realized_usdt"),
        "validation_total_strictly_greater_than_parent": metric(
            validation_candidate, "total_pnl_usdt"
        )
        > metric(validation_parent, "total_pnl_usdt"),
        "validation_unrealized_no_worse_than_parent": metric(
            validation_candidate, "unrealized_usdt"
        )
        >= metric(validation_parent, "unrealized_usdt"),
        "validation_drawdown_no_higher_than_parent": metric(
            validation_candidate, "max_drawdown_pct"
        )
        <= metric(validation_parent, "max_drawdown_pct"),
        "validation_median_no_higher_than_parent": validation_candidate[
            "median_hold_hours"
        ]
        is not None
        and validation_candidate["median_hold_hours"]
        <= validation_parent["median_hold_hours"],
        "validation_p90_no_higher_than_parent": validation_candidate[
            "p90_hold_hours"
        ]
        is not None
        and validation_candidate["p90_hold_hours"]
        <= validation_parent["p90_hold_hours"],
        "validation_blocked_confirmations_strictly_lower": int(
            validation_candidate["blocked_entries"]
        )
        < int(validation_parent["blocked_entries"]),
        "validation_successful_rotations_at_least_five": int(
            validation_candidate["rotation_replacement_buy_count"]
        )
        >= 5,
        "annual_total_wins_at_least_three_of_five": annual_total_wins >= 3,
        "annual_drawdown_nonworse_at_least_three_of_five": (
            annual_drawdown_nonworse >= 3
        ),
        "no_terminal_pending_rotation_design_validation": int(
            design_candidate["terminal_pending_rotation"]
        )
        == 0
        and int(validation_candidate["terminal_pending_rotation"]) == 0,
        "causal_and_accounting_audits_pass": all_accounting_pass,
    }
    qualified = all(gates.values())
    status = (
        "HISTORICAL_GATE_PASS_NO_CLEAN_OOS"
        if qualified
        else "NO_CANDIDATE_KEEP_ONE_SLOT_DRA_V1"
    )
    result: dict[str, object] = {
        "schema_version": "BTC_DRA_ONE_SLOT_SIGNAL_ROTATION_V1_RESULT",
        "research_identity": IDENTITY,
        "status": status,
        "authorization": AUTHORIZATION,
        "data_quality": "PASS",
        "baseline_parity": "PASS_ONE_SLOT_DRA_V1_DESIGN_VALIDATION",
        "selection_data_rows": len(bars),
        "selection_data_sha256": selection_hash,
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": "2025-01-01T00:00:00Z",
            "rows": len(bars),
            "sha256": selection_hash,
        },
        "contamination_status": "POST_HOC_HISTORICAL_NO_CLEAN_OOS",
        "oos_opened": False,
        "one_slot_cap_usdt": "30.00",
        "candidate_variants": 1,
        "qualified_count": int(qualified),
        "selected_candidate": (
            "FRESH_DRA_SIGNAL_PROFITABLE_INCUMBENT_ROTATION_V1"
            if qualified
            else None
        ),
        "parent": parent,
        "candidate": candidate,
        "annual_folds": {
            year: {
                "parent": parent_folds[year],
                "candidate": candidate_folds[year],
                "total_win": metric(
                    candidate_folds[year], "total_pnl_usdt"
                )
                > metric(parent_folds[year], "total_pnl_usdt"),
                "drawdown_nonworse": metric(
                    candidate_folds[year], "max_drawdown_pct"
                )
                <= metric(parent_folds[year], "max_drawdown_pct"),
            }
            for year in base.FOLDS
        },
        "annual_total_wins": annual_total_wins,
        "annual_drawdown_nonworse": annual_drawdown_nonworse,
        "gates": gates,
        "source_evidence": source_evidence,
        "runner_sha256": sha256(Path(__file__)),
        "next_hypothesis": (
            "PROSPECTIVE_FUTURE_CUTOFF_ONE_SLOT_SIGNAL_ROTATION"
            if qualified
            else None
        ),
    }
    return result


def write_output(output: Path, result: dict[str, object]) -> None:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        print(
            json.dumps(
                {"status": "OUTPUT_SEAL_REJECT", "detail": str(args.output)}
            )
        )
        return 2
    try:
        result = build_result()
        write_output(args.output, result)
    except base.ResearchReject as error:
        reject = {
            "schema_version": "BTC_DRA_ONE_SLOT_SIGNAL_ROTATION_V1_RESULT",
            "research_identity": IDENTITY,
            "status": error.status,
            "authorization": AUTHORIZATION,
            "detail": error.detail,
        }
        write_output(args.output, reject)
        print(json.dumps({"status": error.status, "detail": error.detail}))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "qualified_count": result["qualified_count"],
                "output": str(args.output.resolve()),
            }
        )
    )
    return 0 if result["status"] == "HISTORICAL_GATE_PASS_NO_CLEAN_OOS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
