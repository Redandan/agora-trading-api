#!/usr/bin/env python3
"""Cooldown-preserving counterfactual for the frozen flat-inventory veto."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_flat_regime_stale_inventory_admission_veto_v2 as v2


base = v2.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = "BTC_DRA_FLAT_REGIME_STALE_INVENTORY_VETO_COOLDOWN_V2B_RESEARCH"
CANDIDATE = "FLAT_STALE_7D_AND_60USDT_VETO_RESERVES_7D_COOLDOWN"
ROUTER = "FLAT_STALE_VETO_COOLDOWN_ELSE_DRA_V1_MUTUALLY_EXCLUSIVE_ROUTER"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-flat-regime-stale-inventory-veto-cooldown-v2b-research.md"
PARENT_RUNNER_PATH = ROOT / "research" / "btc_dra_flat_regime_stale_inventory_admission_veto_v2.py"
EXPECTED_SPEC_SHA256 = "26bbb9695679db3d7e5b2cdc2cae84660bc4051489d6fa00a4497b9437fb9fab"
EXPECTED_PARENT_RUNNER_SHA256 = "41ee7d08bb459890b4e1b078fe839f31f6ce32dd4cec02cc473450d01c28dbbc"

SELECTION_CUTOFF = v2.SELECTION_CUTOFF
SELECTION_ROWS = v2.SELECTION_ROWS
SELECTION_SHA256 = v2.SELECTION_SHA256
DESIGN = v2.DESIGN
VALIDATION = v2.VALIDATION
FOLDS = v2.FOLDS


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources() -> tuple[str, str]:
    specification_sha = file_sha256(SPEC_PATH)
    if specification_sha != EXPECTED_SPEC_SHA256:
        raise base.ResearchReject(
            "PREREGISTRATION_REJECT",
            {"expected": EXPECTED_SPEC_SHA256, "actual": specification_sha},
        )
    parent_runner_sha = file_sha256(PARENT_RUNNER_PATH)
    if parent_runner_sha != EXPECTED_PARENT_RUNNER_SHA256:
        raise base.ResearchReject(
            "PARENT_SOURCE_REJECT",
            {"expected": EXPECTED_PARENT_RUNNER_SHA256, "actual": parent_runner_sha},
        )
    v2.verify_specification()
    return specification_sha, parent_runner_sha


class CooldownReservationAudit:
    def _init_cooldown_audit(self) -> None:
        self.cooldown_reservation_records: list[dict] = []

    def _reserve_rejected_signal(self, bar: base.Bar, snapshot: dict) -> None:
        self.last_entry_signal = bar.open_time
        self.cooldown_reservation_records.append(
            {
                "signal_time": bar.open_time.isoformat(),
                "route": "FLAT_SLEEVE",
                "reason": "STALE_EXPOSURE_VETO",
                "flat_open_cost_usdt": snapshot["flat_open_cost_usdt"],
                "oldest_flat_age_hours": snapshot["oldest_flat_age_hours"],
                "next_arm_eligible": (bar.open_time + timedelta(hours=168)).isoformat(),
            }
        )

    def _cooldown_result(self) -> dict:
        accepted = [
            datetime.fromisoformat(row["signal_time"])
            for row in self.admission_accept_records
        ]
        reserved = [
            datetime.fromisoformat(row["signal_time"])
            for row in self.cooldown_reservation_records
        ]
        calendar = sorted(accepted + reserved)
        gaps = [
            (later - earlier).total_seconds() / 3600
            for earlier, later in zip(calendar, calendar[1:])
        ]
        return {
            "rule": "VETO_SIGNAL_TIME_RESERVES_168H_SHARED_COOLDOWN",
            "reservation_count": len(reserved),
            "veto_count": self.admission_veto_count,
            "every_veto_reserved": len(reserved) == self.admission_veto_count,
            "accepted_or_reserved_calendar_count": len(calendar),
            "minimum_calendar_gap_hours": None if not gaps else min(gaps),
            "all_calendar_gaps_at_least_168h": all(gap >= 168 for gap in gaps),
            "records": self.cooldown_reservation_records,
        }


class CooldownPreservingFlatEngine(
    CooldownReservationAudit,
    v2.InventoryControlledFlatEngine,
):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.mode = "flat_regime_stale_inventory_veto_cooldown_v2b"
        self.factor = CANDIDATE
        self._init_cooldown_audit()

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            admitted, snapshot = self._evaluate_flat_admission(bar)
            if not admitted:
                self.blocked_count += 1
                self._reserve_rejected_signal(bar, snapshot)
            else:
                open_cost = base.LOT_COST * D(len(self.lots))
                if open_cost + base.LOT_COST > self.cap:
                    self.blocked_count += 1
                    self._record_cap_reject(snapshot)
                else:
                    self.pending_signal = bar.open_time
                    self.pending_atr = self.atr14
                    self.last_entry_signal = bar.open_time
                    self._record_admission_accept(snapshot)
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(hours=168)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = CANDIDATE
        result["cooldown_reservation_audit"] = self._cooldown_result()
        return result


class CooldownPreservingRoutedEngine(
    CooldownReservationAudit,
    v2.InventoryControlledRoutedEngine,
):
    def __init__(self, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__(cap=cap)
        self.mode = "flat_stale_veto_cooldown_else_dra_v1_router"
        self.factor = ROUTER
        self._init_cooldown_audit()

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at:
            flat, values = self._flat_state()
            if flat:
                passed = self._flat_entry_signal(bar)
                route = "FLAT_SLEEVE"
            else:
                passed = base.Engine._signal(self, bar)
                route = "DRA_V1_NONFLAT"
                if passed:
                    self.signal_meta[bar.open_time] = {
                        **values,
                        "signal_time": bar.open_time.isoformat(),
                        "reclaim_pass": None,
                        "positive_trend_required": True,
                    }
            if passed:
                snapshot = None
                admitted = True
                if route == "FLAT_SLEEVE":
                    admitted, snapshot = self._evaluate_flat_admission(bar)
                if not admitted:
                    self.blocked_count += 1
                    self._reserve_rejected_signal(bar, snapshot)
                else:
                    open_cost = base.LOT_COST * D(len(self.lots))
                    if open_cost + base.LOT_COST > self.cap:
                        self.blocked_count += 1
                        if route == "FLAT_SLEEVE":
                            self._record_cap_reject(snapshot)
                    else:
                        self.pending_signal = bar.open_time
                        self.pending_atr = self.atr14
                        self.pending_route = route
                        self.last_entry_signal = bar.open_time
                        self.route_signal_counts[route] += 1
                        if route == "FLAT_SLEEVE":
                            self._record_admission_accept(snapshot)
                self.armed_at = None
                self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(hours=168)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = ROUTER
        result["cooldown_reservation_audit"] = self._cooldown_result()
        return result


def boolean_values(values: dict) -> list[bool]:
    return [value for value in values.values() if isinstance(value, bool)]


def simulate_all(selection_bars: list[base.Bar]) -> dict:
    windows = {"design": DESIGN, "validation": VALIDATION, **FOLDS}
    return {
        "dra_v1": {
            name: base.simulate(selection_bars, window, "v1")
            for name, window in windows.items()
        },
        "flat_v1": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: v2.v1.FlatRegimeEngine())
            for name, window in windows.items()
        },
        "flat_v2": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: v2.InventoryControlledFlatEngine())
            for name, window in windows.items()
        },
        "flat_v2b": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: CooldownPreservingFlatEngine())
            for name, window in windows.items()
        },
        "router_v1": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: v2.v1.RoutedEngine())
            for name, window in windows.items()
        },
        "router_v2": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: v2.InventoryControlledRoutedEngine())
            for name, window in windows.items()
        },
        "router_v2b": {
            name: v2.v1.simulate_engine(selection_bars, window, lambda: CooldownPreservingRoutedEngine())
            for name, window in windows.items()
        },
        "one_slot_v2b": {
            name: v2.v1.simulate_engine(
                selection_bars,
                window,
                lambda: CooldownPreservingFlatEngine(cap=base.LOT_COST),
            )
            for name, window in windows.items()
        },
    }


def build_gates(results: dict) -> dict:
    standalone = v2.standalone_gates(
        results["flat_v2b"]["validation"],
        results["dra_v1"]["validation"],
        {name: results["flat_v2b"][name] for name in FOLDS},
    )
    routed = v2.router_gates(
        results["router_v2b"]["validation"],
        results["dra_v1"]["validation"],
        {name: results["router_v2b"][name] for name in FOLDS},
        {name: results["dra_v1"][name] for name in FOLDS},
        results["router_v1"]["validation"],
    )
    audit = {}
    for lane in ("flat_v2b", "router_v2b", "one_slot_v2b"):
        for window in ("design", "validation", *FOLDS):
            prefix = f"{lane}_{window}"
            admission = results[lane][window]["admission_audit"]
            cooldown = results[lane][window]["cooldown_reservation_audit"]
            audit[f"{prefix}_admission_reconciles"] = admission[
                "accepted_plus_rejected_reconciles"
            ]
            audit[f"{prefix}_every_veto_reserved"] = cooldown["every_veto_reserved"]
            audit[f"{prefix}_cooldown_gaps_valid"] = cooldown[
                "all_calendar_gaps_at_least_168h"
            ]
            audit[f"{prefix}_no_nonflat_veto"] = (
                admission["nonflat_admission_reject_count"] == 0
            )
    overlay = results["one_slot_v2b"]["validation"]
    one_slot = {
        "zero_admission_vetoes": overlay["admission_audit"][
            "stale_exposure_veto_count"
        ]
        == 0,
        "matches_parent_one_slot_checkpoint": base.checkpoint_tuple(overlay)
        == v2.EXPECTED_ONE_SLOT_VALIDATION,
    }
    candidate = results["router_v2b"]["validation"]
    parent = results["router_v2"]["validation"]
    candidate_2022 = results["router_v2b"]["2022"]
    parent_2022 = results["router_v2"]["2022"]
    mechanism = {
        "validation_total_at_least_parent_v2": D(candidate["total_pnl_usdt"])
        >= D(parent["total_pnl_usdt"]),
        "validation_drawdown_no_higher_than_parent_v2": D(candidate["max_drawdown_pct"])
        <= D(parent["max_drawdown_pct"]),
        "validation_terminal_open_cost_no_higher_than_parent_v2": D(
            candidate["ending_open_cost_usdt"]
        )
        <= D(parent["ending_open_cost_usdt"]),
        "year_2022_nonflat_entries_reduced": candidate_2022["route_audit"]["entry_counts"][
            "DRA_V1_NONFLAT"
        ]
        < parent_2022["route_audit"]["entry_counts"]["DRA_V1_NONFLAT"],
        "year_2022_terminal_open_lots_no_higher_than_parent_v2": candidate_2022[
            "open_lots"
        ]
        <= parent_2022["open_lots"],
    }
    return {
        "standalone_parent_v2_gates": standalone,
        "routed_parent_v2_gates": routed,
        "one_slot_invariant": one_slot,
        "cooldown_and_admission_audits": audit,
        "added_mechanism_gates": mechanism,
    }


def run(output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    specification_sha, parent_runner_sha = verify_sources()
    bars = base.parse_rows(base.fetch_rows(SELECTION_CUTOFF))
    selection_bars = [bar for bar in bars if bar.close_time <= SELECTION_CUTOFF]
    selection_sha = base.data_hash(selection_bars)
    if len(selection_bars) != SELECTION_ROWS or selection_sha != SELECTION_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": SELECTION_ROWS,
                "actual_rows": len(selection_bars),
                "expected_sha256": SELECTION_SHA256,
                "actual_sha256": selection_sha,
            },
        )
    results = simulate_all(selection_bars)
    checkpoints = {
        "dra_v1_validation": base.checkpoint_tuple(results["dra_v1"]["validation"]),
        "flat_v1_validation": base.checkpoint_tuple(results["flat_v1"]["validation"]),
        "router_v1_validation": base.checkpoint_tuple(results["router_v1"]["validation"]),
        "one_slot_validation": base.checkpoint_tuple(results["one_slot_v2b"]["validation"]),
    }
    expected = {
        "dra_v1_validation": v2.EXPECTED_V1_VALIDATION,
        "flat_v1_validation": v2.EXPECTED_FLAT_V1_VALIDATION,
        "router_v1_validation": v2.EXPECTED_ROUTER_V1_VALIDATION,
        "one_slot_validation": v2.EXPECTED_ONE_SLOT_VALIDATION,
    }
    if any(checkpoints[name] != expected[name] for name in checkpoints):
        raise base.ResearchReject(
            "BASELINE_PARITY_REJECT",
            {
                name: {"actual": list(checkpoints[name]), "expected": list(expected[name])}
                for name in checkpoints
                if checkpoints[name] != expected[name]
            },
        )
    gates = build_gates(results)
    passed = all(
        all(boolean_values(group))
        for group in gates.values()
    )
    status = (
        "HISTORICAL_GATE_PASS_NO_CLEAN_OOS"
        if passed
        else "NO_CANDIDATE_KEEP_DRA_V1"
    )
    result = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": CANDIDATE,
        "status": status,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "contamination_status": "POST_HOC_HISTORICAL_NO_CLEAN_OOS",
        "data_quality": "PASS",
        "baseline_parity": "PASS_DRA_V1_FLAT_V1_ROUTER_V1_ONE_SLOT",
        "qualified_count": 1 if passed else 0,
        "selected_candidate": CANDIDATE if passed else None,
        "oos_opened": False,
        "selection_data_rows": len(selection_bars),
        "selection_data_sha256": selection_sha,
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": SELECTION_CUTOFF.isoformat(),
            "rows": len(selection_bars),
            "sha256": selection_sha,
        },
        "artifacts": {
            "specification_sha256": specification_sha,
            "parent_runner_sha256": parent_runner_sha,
            "runner_sha256": file_sha256(Path(__file__)),
        },
        "checkpoints": {
            name: {"actual": list(actual), "passed": actual == expected[name]}
            for name, actual in checkpoints.items()
        },
        "gates": gates,
        "historical": results,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.output)
    except base.ResearchReject as error:
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "qualified_count": result["qualified_count"],
            },
            ensure_ascii=False,
        )
    )
    return 0 if result["status"] == "HISTORICAL_GATE_PASS_NO_CLEAN_OOS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
