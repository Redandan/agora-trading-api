#!/usr/bin/env python3
"""Causal, read-only stale-inventory admission research for the flat DRA sleeve."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import btc_dra_flat_regime_liquidity_harvest_sleeve_v1 as v1

base = v1.base
D = Decimal
ZERO = D("0")

RESEARCH_IDENTITY = "BTC_DRA_FLAT_REGIME_STALE_INVENTORY_ADMISSION_VETO_V2_RESEARCH"
CANDIDATE = "FLAT_STALE_7D_AND_60USDT_ADMISSION_VETO"
ROUTER = "FLAT_STALE_INVENTORY_VETO_ELSE_DRA_V1_MUTUALLY_EXCLUSIVE_ROUTER"

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "docs" / "btc-dra-flat-regime-stale-inventory-admission-veto-v2-research.md"
EXPECTED_SPEC_SHA256 = "6c47810fcded8f8792b724ad39d7476d884f40e2e219902fe64d904414dd3030"

SELECTION_CUTOFF = v1.SELECTION_CUTOFF
SELECTION_ROWS = v1.SELECTION_ROWS
SELECTION_SHA256 = v1.SELECTION_SHA256
DESIGN = v1.DESIGN
VALIDATION = v1.VALIDATION
FOLDS = v1.FOLDS
JULY_POST_HOC = v1.JULY_POST_HOC

STALE_AGE = timedelta(hours=168)
STALE_AGE_HOURS = D("168")
EXPOSURE_FLOOR = D("60")

EXPECTED_V1_VALIDATION = v1.EXPECTED_V1_VALIDATION
EXPECTED_FLAT_V1_VALIDATION = (
    "35.82305220",
    "-1.56726556",
    "34.25578664",
    "5.798793",
    245.0,
    1951.2,
    24,
    23,
    1,
    0,
    "11.017100",
    "725.82305220",
)
EXPECTED_ROUTER_V1_VALIDATION = (
    "102.78528314",
    "-1.56726556",
    "101.21801758",
    "12.399848",
    226.5,
    2391.2,
    61,
    60,
    1,
    2,
    "28.813953",
    "1902.78528314",
)
EXPECTED_ONE_SLOT_VALIDATION = (
    "17.67064201",
    "-1.56726556",
    "16.10337645",
    "18.752917",
    251.0,
    2466.4,
    11,
    10,
    1,
    51,
    "47.560420",
    "317.67064201",
)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_specification() -> str:
    actual = file_sha256(SPEC_PATH)
    if actual != EXPECTED_SPEC_SHA256:
        raise base.ResearchReject(
            "PREREGISTRATION_REJECT",
            {"expected_specification_sha256": EXPECTED_SPEC_SHA256, "actual": actual},
        )
    return actual


class AdmissionAudit:
    """Shared causal admission accounting; it never changes exit behavior."""

    def _init_admission_audit(self, *, record_details: bool) -> None:
        self.admission_record_details = record_details
        self.admission_signal_count = 0
        self.admission_accept_count = 0
        self.admission_veto_count = 0
        self.admission_cap_reject_count = 0
        self.admission_rejection_records: list[dict] = []
        self.admission_accept_records: list[dict] = []
        self.nonflat_admission_reject_count = 0

    def _flat_open_lots(self) -> list[base.Lot]:
        raise NotImplementedError

    def _admission_snapshot(self, bar: base.Bar) -> dict:
        flat_lots = self._flat_open_lots()
        flat_open_cost = sum((lot.cost for lot in flat_lots), ZERO)
        oldest_age = max(
            (
                D(str((bar.open_time - lot.fill_time).total_seconds())) / D("3600")
                for lot in flat_lots
            ),
            default=ZERO,
        )
        stale_pass = oldest_age >= STALE_AGE_HOURS
        exposure_pass = flat_open_cost >= EXPOSURE_FLOOR
        return {
            "signal_time": bar.open_time.isoformat(),
            "route": "FLAT_SLEEVE",
            "flat_open_lot_count": len(flat_lots),
            "flat_open_cost_usdt": str(base.money(flat_open_cost)),
            "oldest_flat_age_hours": str(oldest_age),
            "stale_168h_pass": stale_pass,
            "exposure_60usdt_pass": exposure_pass,
            "veto": stale_pass and exposure_pass,
        }

    def _evaluate_flat_admission(self, bar: base.Bar) -> tuple[bool, dict]:
        self.admission_signal_count += 1
        snapshot = self._admission_snapshot(bar)
        if snapshot["veto"]:
            self.admission_veto_count += 1
            self.admission_rejection_records.append(snapshot)
            return False, snapshot
        return True, snapshot

    def _record_admission_accept(self, snapshot: dict) -> None:
        self.admission_accept_count += 1
        self.admission_accept_records.append(snapshot)

    def _record_cap_reject(self, snapshot: dict) -> None:
        self.admission_cap_reject_count += 1
        record = {**snapshot, "cap_reject": True}
        self.admission_rejection_records.append(record)

    def _admission_result(self) -> dict:
        veto_records = [row for row in self.admission_rejection_records if row.get("veto")]
        result = {
            "formula": "OLDEST_FLAT_AGE_HOURS>=168_AND_FLAT_OPEN_COST_USDT>=60",
            "stale_age_hours": str(STALE_AGE_HOURS),
            "exposure_floor_usdt": str(EXPOSURE_FLOOR),
            "flat_signals_evaluated": self.admission_signal_count,
            "accepted_flat_signals": self.admission_accept_count,
            "stale_exposure_veto_count": self.admission_veto_count,
            "cap_reject_count": self.admission_cap_reject_count,
            "total_rejected_signals": self.admission_veto_count + self.admission_cap_reject_count,
            "nonflat_admission_reject_count": self.nonflat_admission_reject_count,
            "all_vetoes_satisfy_both_predicates": all(
                row["stale_168h_pass"] and row["exposure_60usdt_pass"]
                for row in veto_records
            ),
            "accepted_plus_rejected_reconciles": self.admission_signal_count
            == self.admission_accept_count
            + self.admission_veto_count
            + self.admission_cap_reject_count,
        }
        if self.admission_record_details:
            result["accepted_records"] = self.admission_accept_records
            result["rejection_records"] = self.admission_rejection_records
        return result


class InventoryControlledFlatEngine(AdmissionAudit, v1.FlatRegimeEngine):
    def __init__(
        self,
        *,
        cap: D = base.REFERENCE_CAP,
        record_details: bool = False,
    ) -> None:
        super().__init__(cap=cap, record_details=record_details)
        self.mode = "flat_regime_stale_inventory_admission_v2"
        self.factor = CANDIDATE
        self._init_admission_audit(record_details=record_details)

    def _flat_open_lots(self) -> list[base.Lot]:
        return list(self.lots)

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            admitted, snapshot = self._evaluate_flat_admission(bar)
            if not admitted:
                self.blocked_count += 1
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
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = CANDIDATE
        result["admission_audit"] = self._admission_result()
        return result


class InventoryControlledRoutedEngine(AdmissionAudit, v1.RoutedEngine):
    def __init__(
        self,
        *,
        cap: D = base.REFERENCE_CAP,
        record_details: bool = False,
    ) -> None:
        super().__init__(cap=cap)
        self.mode = "flat_stale_inventory_veto_else_dra_v1_router"
        self.factor = ROUTER
        self.record_details = record_details
        self._init_admission_audit(record_details=record_details)

    def _flat_open_lots(self) -> list[base.Lot]:
        return [
            lot
            for lot in self.lots
            if self.lot_route.get(lot.fill_time) == "FLAT_SLEEVE"
        ]

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
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result["candidate"] = ROUTER
        result["admission_audit"] = self._admission_result()
        return result


def dec(result: dict, field: str) -> D:
    return D(result[field])


def boolean_values(values: dict) -> list[bool]:
    return [value for value in values.values() if isinstance(value, bool)]


def standalone_gates(candidate: dict, baseline: dict, folds: dict[str, dict]) -> dict:
    gates = v1.flat_gates(candidate, baseline, folds)
    gates["all_admission_vetoes_valid"] = candidate["admission_audit"][
        "all_vetoes_satisfy_both_predicates"
    ]
    gates["admission_accounting_reconciles"] = candidate["admission_audit"][
        "accepted_plus_rejected_reconciles"
    ]
    return gates


def router_gates(
    candidate: dict,
    baseline: dict,
    candidate_folds: dict[str, dict],
    baseline_folds: dict[str, dict],
    prior_router: dict,
) -> dict:
    gates = v1.routed_gates(candidate, baseline, candidate_folds, baseline_folds)
    gates["drawdown_strictly_lower_than_v1_router"] = dec(
        candidate, "max_drawdown_pct"
    ) < dec(prior_router, "max_drawdown_pct")
    gates["all_admission_vetoes_valid"] = candidate["admission_audit"][
        "all_vetoes_satisfy_both_predicates"
    ]
    gates["admission_accounting_reconciles"] = candidate["admission_audit"][
        "accepted_plus_rejected_reconciles"
    ]
    gates["no_nonflat_admission_rejections"] = candidate["admission_audit"][
        "nonflat_admission_reject_count"
    ] == 0
    gates["validation_admission_veto_observed"] = candidate["admission_audit"][
        "stale_exposure_veto_count"
    ] > 0
    return gates


def run(output: Path, *, include_posthoc_july: bool) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    specification_sha = verify_specification()
    cutoff = JULY_POST_HOC[1] if include_posthoc_july else SELECTION_CUTOFF
    bars = base.parse_rows(base.fetch_rows(cutoff))
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

    windows = {"design": DESIGN, "validation": VALIDATION, **FOLDS}
    baseline = {
        name: base.simulate(selection_bars, window, "v1")
        for name, window in windows.items()
    }
    prior_flat = {
        name: v1.simulate_engine(selection_bars, window, lambda: v1.FlatRegimeEngine())
        for name, window in windows.items()
    }
    candidate_flat = {
        name: v1.simulate_engine(
            selection_bars,
            window,
            lambda: InventoryControlledFlatEngine(),
        )
        for name, window in windows.items()
    }
    prior_router = {
        name: v1.simulate_engine(selection_bars, window, lambda: v1.RoutedEngine())
        for name, window in windows.items()
    }
    candidate_router = {
        name: v1.simulate_engine(
            selection_bars,
            window,
            lambda: InventoryControlledRoutedEngine(),
        )
        for name, window in windows.items()
    }
    overlay = {
        name: v1.simulate_engine(
            selection_bars,
            window,
            lambda: InventoryControlledFlatEngine(cap=base.LOT_COST),
        )
        for name, window in windows.items()
    }

    checkpoints = {
        "dra_v1_validation": base.checkpoint_tuple(baseline["validation"]),
        "flat_v1_validation": base.checkpoint_tuple(prior_flat["validation"]),
        "router_v1_validation": base.checkpoint_tuple(prior_router["validation"]),
        "one_slot_validation": base.checkpoint_tuple(overlay["validation"]),
    }
    expected_checkpoints = {
        "dra_v1_validation": EXPECTED_V1_VALIDATION,
        "flat_v1_validation": EXPECTED_FLAT_V1_VALIDATION,
        "router_v1_validation": EXPECTED_ROUTER_V1_VALIDATION,
        "one_slot_validation": EXPECTED_ONE_SLOT_VALIDATION,
    }
    for name, actual in checkpoints.items():
        if actual != expected_checkpoints[name]:
            raise base.ResearchReject(
                "BASELINE_PARITY_REJECT",
                {"checkpoint": name, "actual": actual, "expected": expected_checkpoints[name]},
            )
    if overlay["validation"]["admission_audit"]["stale_exposure_veto_count"] != 0:
        raise base.ResearchReject(
            "IMPLEMENTATION_INVARIANT_REJECT",
            {"one_slot_admission_audit": overlay["validation"]["admission_audit"]},
        )

    standalone_gate_values = standalone_gates(
        candidate_flat["validation"],
        baseline["validation"],
        {name: candidate_flat[name] for name in FOLDS},
    )
    router_gate_values = router_gates(
        candidate_router["validation"],
        baseline["validation"],
        {name: candidate_router[name] for name in FOLDS},
        {name: baseline[name] for name in FOLDS},
        prior_router["validation"],
    )
    overlay_gate_values = {
        "zero_admission_vetoes": overlay["validation"]["admission_audit"][
            "stale_exposure_veto_count"
        ]
        == 0,
        "matches_prior_one_slot_checkpoint": base.checkpoint_tuple(
            overlay["validation"]
        )
        == EXPECTED_ONE_SLOT_VALIDATION,
    }
    historical_pass = (
        all(boolean_values(standalone_gate_values))
        and all(boolean_values(router_gate_values))
        and all(boolean_values(overlay_gate_values))
    )
    status = (
        "HISTORICAL_GATE_PASS_FORWARD_PENDING"
        if historical_pass
        else "NO_CANDIDATE"
    )

    result = {
        "research_identity": RESEARCH_IDENTITY,
        "candidate": CANDIDATE,
        "status": status,
        "authorization": "RESEARCH_ONLY_NO_SHADOW_NO_LIVE",
        "contamination_status": "POST_HOC_HISTORICAL_RESEARCH_ONLY",
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": SELECTION_CUTOFF.isoformat(),
            "rows": len(selection_bars),
            "sha256": selection_sha,
        },
        "artifacts": {
            "specification_sha256": specification_sha,
            "runner_sha256": file_sha256(Path(__file__)),
        },
        "formula": {
            "flat": "ABS(EMA20_NOW-EMA20_5D_AGO)<=0.25*ATR14",
            "entry": "FLAT_RECLAIM_WITH_STALE_7D_AND_60USDT_ADMISSION_VETO",
            "exit": "PEAK_NET_GE_1R_AND_CLOSE_LT_HOURLY_EMA5_AND_CURRENT_NET_GE_0.5R",
            "admission_veto": "OLDEST_FLAT_AGE_HOURS>=168_AND_FLAT_OPEN_COST_USDT>=60",
        },
        "checkpoints": {
            name: {"actual": list(actual), "passed": actual == expected_checkpoints[name]}
            for name, actual in checkpoints.items()
        },
        "historical": {
            "dra_v1": baseline,
            "flat_sleeve_v1": prior_flat,
            "flat_sleeve_v2_inventory_control": candidate_flat,
            "mutually_exclusive_router_v1": prior_router,
            "mutually_exclusive_router_v2_inventory_control": candidate_router,
            "one_slot_30usdt_overlay_v2": overlay,
        },
        "gates": {
            "standalone_flat_sleeve_v2": standalone_gate_values,
            "mutually_exclusive_router_v2": router_gate_values,
            "one_slot_overlay_v2": overlay_gate_values,
            "historical_gate_pass": historical_pass,
        },
    }

    if include_posthoc_july:
        result["posthoc_july_2026"] = {
            "label": "POST_HOC_DIAGNOSTIC_NOT_SELECTION_NOT_OOS",
            "data_rows_through_cutoff": len(bars),
            "data_sha256": base.data_hash(bars),
            "dra_v1": base.simulate(bars, JULY_POST_HOC, "v1"),
            "flat_sleeve_v1": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: v1.FlatRegimeEngine(record_details=True),
            ),
            "flat_sleeve_v2_inventory_control": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: InventoryControlledFlatEngine(record_details=True),
            ),
            "mutually_exclusive_router_v1": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: v1.RoutedEngine(),
            ),
            "mutually_exclusive_router_v2_inventory_control": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: InventoryControlledRoutedEngine(record_details=True),
            ),
            "one_slot_30usdt_overlay_v2": v1.simulate_engine(
                bars,
                JULY_POST_HOC,
                lambda: InventoryControlledFlatEngine(
                    cap=base.LOT_COST,
                    record_details=True,
                ),
            ),
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
    parser.add_argument("--include-posthoc-july", action="store_true")
    args = parser.parse_args()
    try:
        result = run(args.output, include_posthoc_july=args.include_posthoc_july)
    except base.ResearchReject as error:
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "historical_gate_pass": result["gates"]["historical_gate_pass"],
            },
            ensure_ascii=False,
        )
    )
    return 0 if result["status"] == "HISTORICAL_GATE_PASS_FORWARD_PENDING" else 2


if __name__ == "__main__":
    raise SystemExit(main())
