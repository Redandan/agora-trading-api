#!/usr/bin/env python3
"""Pre-economic support probe for oldest-open-lot age DRA admission."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
from typing import Any

import btc_dra_equal_capital_capacity_v1 as capacity
import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SPEC_TYPE = "DRA_STALE_INVENTORY_AGE_PREOUTCOME_SPEC_V1"
RESULT_TYPE = "DRA_STALE_INVENTORY_AGE_PREOUTCOME_RESULT_V1"
FAMILY_ID = "dra-stale-inventory-age-entry-admission"
RUNNER_IDENTITY = "DRA_STALE_INVENTORY_AGE_SUPPORT_V1"
DATA_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
DATA_ROWS = 52_608
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
INITIAL_EQUITY_USDT = D("250")
SLOT_CAPACITY_USDT = D("240")
LOT_COST_USDT = D("30")
QUANTUM = D("0.00000001")
VARIANTS = (
    ("lower_threshold_neighbor", D("336"), "oldest-open-lot-age-at-least-336h-v1"),
    ("primary", D("720"), "oldest-open-lot-age-at-least-720h-v1"),
    ("upper_threshold_neighbor", D("1440"), "oldest-open-lot-age-at-least-1440h-v1"),
)

NEW_ACTION_FINGERPRINT = (
    "ACTION_DRA_ENTRY_ADMISSION|CLOCK_CAPACITY_ADMISSIBLE_DRA_SIGNAL|"
    "STATE_OLDEST_OPEN_PARENT_LOT_AGE_GTE_720H|NO_OPEN_LOT_ADMITTED|"
    "VETO_RESERVES_PARENT_7D_COOLDOWN|LOT_30|CAP_240|NO_SELL_OR_RESIZE"
)
CLOSED_UNDERWATER_CONGESTION_FINGERPRINT = (
    "ACTION_DRA_ENTRY_ADMISSION|CLOCK_CAPACITY_ADMISSIBLE_DRA_SIGNAL|"
    "STATE_EXISTING_OPEN_COST_GTE_120_AND_AGGREGATE_ESTIMATED_NET_PNL_LTE_0|"
    "VETO_RESERVES_PARENT_7D_COOLDOWN|LOT_30|CAP_240"
)
CLOSED_ONE_SLOT_ROTATION_FINGERPRINT = (
    "ACTION_SELL_PROFITABLE_ONE_SLOT_INCUMBENT_THEN_REPLACE|CLOCK_FRESH_DRA_SIGNAL|"
    "CAP_30|SELL_BEFORE_BUY|NO_UNDERWATER_SALE"
)
CLOSED_CAPACITY_FINGERPRINT = (
    "ACTION_CHANGE_DRA_OPEN_SLOT_CAPACITY|CLOCK_ALL_DRA_SIGNALS|FIXED_30_USDT_LOTS"
)
CLOSED_PARTIAL_EXIT_FINGERPRINT = (
    "ACTION_SPLIT_EACH_PARENT_LOT_INTO_PROFIT_ONLY_CORE_AND_RESIDUAL_RUNNER|"
    "CLOCK_PARENT_EXIT|SPLITS_40_60_50_50_60_40"
)
CLOSED_FLAT_VETO_FINGERPRINT = (
    "ACTION_DRA_ENTRY_ADMISSION|CLOCK_DRA_SIGNAL|STATE_FLAT_REGIME_STALE_INVENTORY|"
    "VETO_RESERVES_PARENT_7D_COOLDOWN"
)


class SupportReject(RuntimeError):
    pass


def sha256(path_or_bytes: Path | bytes) -> str:
    raw = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def repository_path(value: str) -> Path:
    path = (REPO_ROOT / value).resolve()
    try:
        path.relative_to(REPO_ROOT)
    except ValueError as error:
        raise SupportReject(f"PATH_REJECT:{path}") from error
    return path


def state_output_path(value: str) -> Path:
    path = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        path.relative_to(state_root)
    except ValueError as error:
        raise SupportReject(f"OUTPUT_PATH_REJECT:{path}") from error
    if path.exists():
        raise SupportReject(f"SEALED_OUTPUT_EXISTS:{path}")
    return path


def load_spec(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_bytes())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SupportReject("SPEC_REJECT:JSON") from error
    if value.get("document_type") != SPEC_TYPE:
        raise SupportReject("SPEC_REJECT:DOCUMENT_TYPE")
    if value.get("authorization") != AUTHORIZATION:
        raise SupportReject("SPEC_REJECT:AUTHORIZATION")
    if value.get("family_id") != FAMILY_ID:
        raise SupportReject("SPEC_REJECT:FAMILY")
    if value.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": DATA_SHA256,
        "hourly_rows": DATA_ROWS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise SupportReject("SPEC_REJECT:DATASET")
    runner_path = Path(__file__).resolve()
    if value.get("runner_binding") != {
        "path": runner_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(runner_path),
    }:
        raise SupportReject("SPEC_REJECT:RUNNER_BINDING")
    expected_fingerprints = {
        "new_family": NEW_ACTION_FINGERPRINT,
        "closed_underwater_congestion": CLOSED_UNDERWATER_CONGESTION_FINGERPRINT,
        "closed_one_slot_rotation": CLOSED_ONE_SLOT_ROTATION_FINGERPRINT,
        "closed_capacity_change": CLOSED_CAPACITY_FINGERPRINT,
        "closed_partial_exit": CLOSED_PARTIAL_EXIT_FINGERPRINT,
        "closed_flat_veto": CLOSED_FLAT_VETO_FINGERPRINT,
    }
    if value.get("action_fingerprints") != expected_fingerprints:
        raise SupportReject("SPEC_REJECT:ACTION_FINGERPRINTS")
    if value.get("admission_policy") != {
        "evaluate_only_after_parent_signal_and_before_next_h1_open_fill": True,
        "evaluate_only_when_parent_capacity_can_accept_30_usdt": True,
        "age_clock": "DECISION_TIME_MINUS_OLDEST_OPEN_PARENT_LOT_FILL_TIME_IN_HOURS",
        "no_open_inventory_behavior": "ADMIT",
        "relation": "VETO_AT_OR_ABOVE_THRESHOLD_HOURS",
        "primary_threshold_hours": "720",
        "rejection_only_threshold_neighbors_hours": ["336", "1440"],
        "vetoed_signal_reserves_parent_cooldown": "SEVEN_DAYS_FROM_ORIGINAL_SIGNAL",
        "capacity_block_behavior": "UNCHANGED_PARENT_BLOCK_WITHOUT_COOLDOWN_RESERVATION",
        "lot_cost_usdt": "30",
        "maximum_open_cost_usdt": "240",
        "maximum_open_lots": 8,
        "read_inventory_pnl": "DENY",
        "sell_or_resize_existing_lots": "DENY",
        "variants": 3,
    }:
        raise SupportReject("SPEC_REJECT:ADMISSION_POLICY")
    gate_keys = {
        "design_minimum_parent_signals",
        "validation_minimum_parent_signals",
        "design_minimum_primary_actionable_vetoes",
        "validation_minimum_primary_actionable_vetoes",
        "validation_minimum_each_neighbor_actionable_vetoes",
        "minimum_active_annual_folds",
        "minimum_primary_vetoes_per_active_annual_fold",
        "maximum_top_year_primary_veto_share",
        "maximum_validation_top_month_primary_veto_share",
        "primary_action_share_minimum",
        "primary_action_share_maximum",
        "minimum_neighbor_action_hash_differences_each_window",
    }
    gates = value.get("gates")
    if not isinstance(gates, dict) or set(gates) != gate_keys:
        raise SupportReject("SPEC_REJECT:GATES")
    bindings = value.get("closed_family_bindings")
    if not isinstance(bindings, list) or len(bindings) != 5:
        raise SupportReject("SPEC_REJECT:CLOSED_BINDINGS")
    for binding in bindings:
        bound = repository_path(binding["path"])
        if not bound.is_file() or bound.is_symlink() or sha256(bound) != binding["sha256"]:
            raise SupportReject(f"BINDING_REJECT:{binding.get('role')}")
    prior = value.get("prior_binding")
    if not isinstance(prior, dict):
        raise SupportReject("SPEC_REJECT:PRIOR_BINDING")
    prior_path = repository_path(prior["path"])
    if not prior_path.is_file() or prior_path.is_symlink() or sha256(prior_path) != prior["sha256"]:
        raise SupportReject("BINDING_REJECT:PRIOR")
    return value


def load_bars(path: Path) -> list[base.Bar]:
    if not path.is_file() or path.is_symlink() or sha256(path) != DATA_SHA256:
        raise SupportReject("DATA_REJECT:SELECTION_HASH")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    if len(bars) != DATA_ROWS or base.data_hash(bars) != DATA_SHA256:
        raise SupportReject("DATA_REJECT:ROWS_OR_CANONICAL_HASH")
    if bars[-1].close_time > datetime(2025, 1, 1):
        raise SupportReject("OOS_REJECT:SELECTION_CUTOFF")
    return bars


def inventory_age_state(lots: list[base.Lot], decision_time: datetime) -> dict[str, Any]:
    open_cost = sum((lot.cost for lot in lots), D("0"))
    oldest_fill = min((lot.fill_time for lot in lots), default=None)
    if oldest_fill is not None and oldest_fill > decision_time:
        raise SupportReject("STATE_REJECT:FUTURE_LOT_FILL")
    age_hours = (
        D("0")
        if oldest_fill is None
        else D(str((decision_time - oldest_fill).total_seconds())) / D("3600")
    ).quantize(QUANTUM, rounding=ROUND_HALF_UP)
    return {
        "open_lot_count": len(lots),
        "open_cost_usdt": str(base.money(open_cost)),
        "has_open_inventory": bool(lots),
        "oldest_open_lot_fill_time": None if oldest_fill is None else oldest_fill.isoformat(),
        "oldest_open_lot_age_hours": str(age_hours),
        "capacity_admissible": open_cost + LOT_COST_USDT <= SLOT_CAPACITY_USDT,
    }


def actionable_veto(snapshot: dict[str, Any], threshold_hours: D) -> bool:
    return (
        bool(snapshot["capacity_admissible"])
        and bool(snapshot["has_open_inventory"])
        and D(snapshot["oldest_open_lot_age_hours"]) >= threshold_hours
    )


class ParentInventoryAgeObserver(capacity.EqualCapitalCapacityEngine):
    def __init__(self) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.signal_snapshots: list[dict[str, Any]] = []

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            snapshot = inventory_age_state(self.lots, bar.open_time)
            snapshot["signal_time"] = bar.open_time.isoformat()
            self.signal_snapshots.append(snapshot)
            open_cost = LOT_COST_USDT * D(len(self.lots))
            if open_cost + LOT_COST_USDT > self.cap:
                self.blocked_count += 1
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


def simulate_parent_state(
    bars: list[base.Bar], window: tuple[datetime, datetime]
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise SupportReject(f"DATA_REJECT:EMPTY_WINDOW:{start.isoformat()}")
    engine = ParentInventoryAgeObserver()
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end), engine.signal_snapshots


def _share(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        raise SupportReject("DATA_REJECT:SHARE_DENOMINATOR")
    return str((D(numerator) / D(denominator)).quantize(QUANTUM, rounding=ROUND_HALF_UP))


def _median(values: list[D]) -> D:
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / D("2")


def summarize_window(
    bars: list[base.Bar], window: tuple[datetime, datetime]
) -> dict[str, Any]:
    observed, snapshots = simulate_parent_state(bars, window)
    baseline = capacity.simulate_capacity(
        bars,
        window,
        slot_capacity_usdt=SLOT_CAPACITY_USDT,
        initial_equity_usdt=INITIAL_EQUITY_USDT,
    )
    observed_hash = sha256(canonical_bytes(observed))
    baseline_hash = sha256(canonical_bytes(baseline))
    admissible = [row for row in snapshots if row["capacity_admissible"]]
    open_inventory = [row for row in snapshots if row["has_open_inventory"]]
    ages = [D(row["oldest_open_lot_age_hours"]) for row in open_inventory]
    variants: dict[str, Any] = {}
    for role, threshold, variant_id in VARIANTS:
        actions = [row for row in snapshots if actionable_veto(row, threshold)]
        months = Counter(row["signal_time"][:7] for row in actions)
        action_rows = [
            {
                "signal_time": row["signal_time"],
                "open_lot_count": row["open_lot_count"],
                "oldest_open_lot_fill_time": row["oldest_open_lot_fill_time"],
                "oldest_open_lot_age_hours": row["oldest_open_lot_age_hours"],
            }
            for row in actions
        ]
        variants[role] = {
            "variant_id": variant_id,
            "veto_at_or_above_threshold_hours": str(threshold),
            "actionable_veto_count": len(actions),
            "action_share_of_capacity_admissible_signals": _share(len(actions), len(admissible)),
            "distinct_action_months": len(months),
            "top_month_action_share": _share(max(months.values()), len(actions)) if actions else "1.00000000",
            "action_sha256": sha256(canonical_bytes(action_rows)),
        }
    return {
        "start": window[0].isoformat(),
        "end_exclusive": window[1].isoformat(),
        "parent_signal_count": len(snapshots),
        "capacity_admissible_signal_count": len(admissible),
        "capacity_blocked_signal_count": len(snapshots) - len(admissible),
        "signal_with_open_inventory_count": len(open_inventory),
        "signal_without_open_inventory_count": len(snapshots) - len(open_inventory),
        "oldest_open_lot_age_hours_summary": None if not ages else {
            "minimum": str(min(ages)),
            "median": str(_median(ages).quantize(QUANTUM, rounding=ROUND_HALF_UP)),
            "maximum": str(max(ages)),
        },
        "parent_signal_timestamp_sha256": sha256(canonical_bytes([row["signal_time"] for row in snapshots])),
        "parent_path_sha256": observed_hash,
        "independent_parent_path_sha256": baseline_hash,
        "exact_parent_path_parity": observed_hash == baseline_hash,
        "variants": variants,
    }


def analyze(bars: list[base.Bar], spec: dict[str, Any]) -> dict[str, Any]:
    design = summarize_window(bars, DESIGN)
    validation = summarize_window(bars, VALIDATION)
    annual = {year: summarize_window(bars, window) for year, window in ANNUAL.items()}
    primary_annual_counts = {
        year: summary["variants"]["primary"]["actionable_veto_count"]
        for year, summary in annual.items()
    }
    active_counts = [count for count in primary_annual_counts.values() if count > 0]
    total_actions = sum(active_counts)
    top_year_share = _share(max(active_counts), total_actions) if active_counts else "1.00000000"
    gates = spec["gates"]
    primary_design = design["variants"]["primary"]
    primary_validation = validation["variants"]["primary"]
    neighbor_roles = ("lower_threshold_neighbor", "upper_threshold_neighbor")
    fingerprints = spec["action_fingerprints"]
    gate_results = {
        "exact_parent_path_parity_all_windows": all(
            summary["exact_parent_path_parity"] for summary in [design, validation, *annual.values()]
        ),
        "action_fingerprints_distinct_from_closed_families": len(set(fingerprints.values())) == len(fingerprints),
        "design_parent_signal_support": design["parent_signal_count"] >= gates["design_minimum_parent_signals"],
        "validation_parent_signal_support": validation["parent_signal_count"] >= gates["validation_minimum_parent_signals"],
        "design_primary_action_support": primary_design["actionable_veto_count"] >= gates["design_minimum_primary_actionable_vetoes"],
        "validation_primary_action_support": primary_validation["actionable_veto_count"] >= gates["validation_minimum_primary_actionable_vetoes"],
        "validation_neighbor_action_support": all(
            validation["variants"][role]["actionable_veto_count"] >= gates["validation_minimum_each_neighbor_actionable_vetoes"]
            for role in neighbor_roles
        ),
        "annual_active_fold_breadth": len(active_counts) >= gates["minimum_active_annual_folds"],
        "annual_active_fold_minimum_support": all(
            count >= gates["minimum_primary_vetoes_per_active_annual_fold"] for count in active_counts
        ),
        "top_year_action_concentration": D(top_year_share) <= D(str(gates["maximum_top_year_primary_veto_share"])),
        "validation_top_month_action_concentration": D(primary_validation["top_month_action_share"]) <= D(str(gates["maximum_validation_top_month_primary_veto_share"])),
        "primary_action_share_design_validation": all(
            D(summary["variants"]["primary"]["action_share_of_capacity_admissible_signals"]) >= D(str(gates["primary_action_share_minimum"]))
            and D(summary["variants"]["primary"]["action_share_of_capacity_admissible_signals"]) <= D(str(gates["primary_action_share_maximum"]))
            for summary in (design, validation)
        ),
        "neighbor_action_hash_differences_design_validation": all(
            sum(
                summary["variants"][role]["action_sha256"] != summary["variants"]["primary"]["action_sha256"]
                for role in neighbor_roles
            ) >= gates["minimum_neighbor_action_hash_differences_each_window"]
            for summary in (design, validation)
        ),
    }
    failed = sorted(name for name, passed in gate_results.items() if not passed)
    return {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "family_id": FAMILY_ID,
        "status": "PASS_PREOUTCOME_DEDUP_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS" if not failed else "NO_HYPOTHESIS_CLOSE_STALE_INVENTORY_AGE_AT_SUPPORT_GATE",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256(Path(__file__).resolve()),
        "dataset": {
            "canonical_sha256": base.data_hash(bars),
            "hourly_rows": len(bars),
            "selection_cutoff": spec["dataset"]["selection_cutoff"],
        },
        "action_fingerprints": fingerprints,
        "admission_policy": spec["admission_policy"],
        "windows": {"design": design, "validation": validation},
        "annual": annual,
        "annual_primary_actionable_vetoes": primary_annual_counts,
        "active_annual_fold_count": len(active_counts),
        "top_year_primary_veto_share": top_year_share,
        "gate_results": gate_results,
        "failed_gates": failed,
        "hypothesis_created": False,
        "candidate_created": False,
        "oos_opened": False,
        "outcome_accessed": False,
        "scope_note": "Causal parent-state age, action deduplication, intervention breadth and exact parent-path parity only. No inventory PnL, candidate strategy economics, paid API, external download, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def run(spec_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    output = state_output_path(str(output_path))
    spec = load_spec(spec_path)
    bars = load_bars(input_path)
    result = analyze(bars, spec)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as target:
        target.write(canonical_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.spec.resolve(), args.input.resolve(), args.output.resolve())
    except (SupportReject, base.ResearchReject, OSError, ValueError) as error:
        print(str(error))
        return 2
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
