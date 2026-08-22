#!/usr/bin/env python3
"""Pre-economic support probe for signal-day occupancy above complete-day VWAP."""

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
SPEC_TYPE = "DRA_NATIVE_SIGNAL_DAY_VWAP_OCCUPANCY_PREOUTCOME_SPEC_V1"
RESULT_TYPE = "DRA_NATIVE_SIGNAL_DAY_VWAP_OCCUPANCY_PREOUTCOME_RESULT_V1"
FAMILY_ID = "dra-native-signal-day-vwap-occupancy-entry-admission"
RUNNER_IDENTITY = "DRA_NATIVE_SIGNAL_DAY_VWAP_OCCUPANCY_SUPPORT_V1"
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
SIGNAL_DAY_HOURS = 24
VARIANTS = (
    ("lower_threshold_neighbor", 8, "vwap-occupancy-at-least-8-of-24-v1"),
    ("primary", 12, "vwap-occupancy-at-least-12-of-24-v1"),
    ("upper_threshold_neighbor", 16, "vwap-occupancy-at-least-16-of-24-v1"),
)


class SupportReject(RuntimeError):
    pass


def sha256(path_or_bytes: Path | bytes) -> str:
    raw = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def repository_path(relative: str) -> Path:
    candidate = (REPO_ROOT / relative).resolve()
    try:
        candidate.relative_to(REPO_ROOT.resolve())
    except ValueError as exc:
        raise SupportReject(f"PATH_REJECT:{relative}") from exc
    return candidate


def load_spec(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise SupportReject("SPEC_REJECT:PATH")
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("document_type") != SPEC_TYPE or value.get("family_id") != FAMILY_ID:
        raise SupportReject("SPEC_REJECT:IDENTITY")
    if value.get("authorization") != AUTHORIZATION:
        raise SupportReject("SPEC_REJECT:AUTHORIZATION")
    runner = value.get("runner_binding")
    if not isinstance(runner, dict) or runner.get("path") != "research/dra_native_signal_day_vwap_occupancy_support_v1.py":
        raise SupportReject("SPEC_REJECT:RUNNER_BINDING")
    if runner.get("sha256") != sha256(Path(__file__).resolve()):
        raise SupportReject("BINDING_REJECT:RUNNER")
    bindings = value.get("evidence_bindings")
    if not isinstance(bindings, list) or len(bindings) != 7:
        raise SupportReject("SPEC_REJECT:EVIDENCE_BINDINGS")
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


def vwap_occupancy(
    bars: list[tuple[D, D, D, D]],
) -> tuple[D, int, D] | None:
    if len(bars) != SIGNAL_DAY_HOURS:
        return None
    total_volume = sum((volume for _, _, _, volume in bars), D("0"))
    if total_volume <= 0:
        return None
    weighted_typical_price = sum(
        (((high + low + close) / D("3")) * volume for high, low, close, volume in bars),
        D("0"),
    )
    vwap = weighted_typical_price / total_volume
    above_count = sum(close > vwap for _, _, close, _ in bars)
    share = (D(above_count) / D(SIGNAL_DAY_HOURS)).quantize(QUANTUM, rounding=ROUND_HALF_UP)
    return vwap, above_count, share


def actionable_veto(snapshot: dict[str, Any], minimum_above_count: int) -> bool:
    if not snapshot["capacity_admissible"]:
        return False
    value = snapshot["h1_closes_above_vwap"]
    return value is None or value < minimum_above_count


def _expected_action_fingerprints() -> dict[str, str]:
    return {
        "new_family": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_CAPACITY_ADMISSIBLE_DRA_SIGNAL|FEATURE_24_SIGNAL_DAY_H1_CLOSES_ABOVE_COMPLETE_DAY_TYPICAL_PRICE_BASE_VOLUME_VWAP_COUNT_GTE_12",
        "ema20_occupancy": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_CAPACITY_ADMISSIBLE_DRA_SIGNAL|FEATURE_24_SIGNAL_DAY_H1_CLOSES_ABOVE_PRIOR_DAY_EMA20_SHARE_GTE_0_75",
        "h1_volume_weighted_close_location": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_COMPLETE_UTC_DAY|FEATURE_DAILY_CLOSE_TO_H1_VOLUME_WEIGHTED_CLOSE_TO_PRIOR_20D_MEDIAN_GTE_1",
        "directional_volume_participation": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_COMPLETE_UTC_DAY|FEATURE_POSITIVE_RETURN_QUOTE_VOLUME_SHARE_TO_PRIOR_20D_MEDIAN_GTE_1",
        "price_path_efficiency": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_COMPLETE_UTC_DAY|FEATURE_ABS_NET_H1_MOVE_DIV_SUM_ABS_H1_MOVES_PRIOR_20D_PERCENTILE_GTE_0_5",
        "close_location": "ACTION_DRA_ENTRY_ADMISSION|CLOCK_COMPLETE_UTC_DAY|FEATURE_CLOSE_LOCATION_VALUE_TO_PRIOR_20D_MEDIAN_GTE_1",
    }


class ParentSignalVwapOccupancyObserver(capacity.EqualCapitalCapacityEngine):
    def __init__(self) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.signal_day_bars: list[tuple[D, D, D, D]] = []
        self.signal_snapshots: list[dict[str, Any]] = []

    def _indicators(self, bar: base.Bar) -> None:
        if bar.open_time.hour == 0:
            self.signal_day_bars = []
        self.signal_day_bars.append((bar.high, bar.low, bar.close, bar.volume))
        super()._indicators(bar)

    def _signal(self, bar: base.Bar) -> bool:
        parent_signal = super()._signal(bar)
        if not parent_signal:
            return False
        open_cost = LOT_COST_USDT * D(len(self.lots))
        occupancy = vwap_occupancy(self.signal_day_bars)
        self.signal_snapshots.append(
            {
                "signal_time": bar.open_time.isoformat(),
                "signal_day_complete_h1_count": len(self.signal_day_bars),
                "signal_day_vwap_usdt": None if occupancy is None else str(occupancy[0]),
                "h1_closes_above_vwap": None if occupancy is None else occupancy[1],
                "vwap_occupancy_share": None if occupancy is None else str(occupancy[2]),
                "capacity_admissible": open_cost + LOT_COST_USDT <= self.cap,
            }
        )
        return True


def simulate_parent_signals(
    bars: list[base.Bar], window: tuple[datetime, datetime]
) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise SupportReject(f"DATA_REJECT:EMPTY_WINDOW:{start.isoformat()}")
    parent = capacity.EqualCapitalCapacityEngine(
        slot_capacity_usdt=SLOT_CAPACITY_USDT,
        initial_equity_usdt=INITIAL_EQUITY_USDT,
    )
    observer = ParentSignalVwapOccupancyObserver()
    for bar in selected:
        if bar.open_time < start:
            parent.warmup(bar)
            observer.warmup(bar)
        else:
            parent.step(bar)
            observer.step(bar)
    parent_result = parent.result(trading[-1], start, end)
    observer_result = observer.result(trading[-1], start, end)
    return observer_result, observer.signal_snapshots, parent_result == observer_result


def _share(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        raise SupportReject("DATA_REJECT:SHARE_DENOMINATOR")
    return str((D(numerator) / D(denominator)).quantize(QUANTUM, rounding=ROUND_HALF_UP))


def _median(values: list[D]) -> D:
    ordered = sorted(values)
    midpoint = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[midpoint]
    return (ordered[midpoint - 1] + ordered[midpoint]) / D("2")


def _action_hash(signal_times: list[str]) -> str:
    return sha256("".join(f"{value}\n" for value in signal_times).encode("utf-8"))


def summarize_window(
    result: dict[str, Any], snapshots: list[dict[str, Any]], parent_path_parity: bool
) -> dict[str, Any]:
    capacity_events = [row for row in snapshots if row["capacity_admissible"]]
    available = [row for row in capacity_events if row["h1_closes_above_vwap"] is not None]
    counts = [row["h1_closes_above_vwap"] for row in available]
    shares = [D(row["vwap_occupancy_share"]) for row in available]
    variants: dict[str, Any] = {}
    for role, minimum_count, variant_id in VARIANTS:
        veto_times = [row["signal_time"] for row in snapshots if actionable_veto(row, minimum_count)]
        variants[role] = {
            "variant_id": variant_id,
            "minimum_closes_above_vwap": minimum_count,
            "threshold_share": str((D(minimum_count) / D(SIGNAL_DAY_HOURS)).quantize(QUANTUM)),
            "actionable_veto_count": len(veto_times),
            "action_share": _share(len(veto_times), len(capacity_events)),
            "action_sha256": _action_hash(veto_times),
            "veto_signal_times": veto_times,
        }
    return {
        "parent_path_parity": parent_path_parity,
        "parent_result": result,
        "parent_signal_count": len(snapshots),
        "capacity_admissible_signal_count": len(capacity_events),
        "feature_available_count": len(available),
        "feature_available_share": _share(len(available), len(capacity_events)),
        "vwap_occupancy_summary": {
            "minimum_above_count": min(counts),
            "median_above_count": str(_median([D(value) for value in counts])),
            "maximum_above_count": max(counts),
            "minimum_share": str(min(shares)),
            "median_share": str(_median(shares)),
            "maximum_share": str(max(shares)),
        },
        "variants": variants,
    }


def build_result(spec: dict[str, Any], spec_path: Path, bars: list[base.Bar]) -> dict[str, Any]:
    windows: dict[str, Any] = {}
    for name, window in (("design", DESIGN), ("validation", VALIDATION)):
        parent_result, snapshots, parity = simulate_parent_signals(bars, window)
        windows[name] = summarize_window(parent_result, snapshots, parity)

    annual: dict[str, Any] = {}
    for year, window in ANNUAL.items():
        parent_result, snapshots, parity = simulate_parent_signals(bars, window)
        summary = summarize_window(parent_result, snapshots, parity)
        annual[year] = {
            "parent_path_parity": parity,
            "capacity_admissible_signal_count": summary["capacity_admissible_signal_count"],
            "primary_actionable_veto_count": summary["variants"]["primary"]["actionable_veto_count"],
            "primary_action_sha256": summary["variants"]["primary"]["action_sha256"],
        }

    primary_year_counts = {year: value["primary_actionable_veto_count"] for year, value in annual.items()}
    total_primary_year_vetoes = sum(primary_year_counts.values())
    active_years = sum(count > 0 for count in primary_year_counts.values())
    top_year_share = (
        _share(max(primary_year_counts.values()), total_primary_year_vetoes)
        if total_primary_year_vetoes > 0
        else "1.00000000"
    )
    validation_primary_times = windows["validation"]["variants"]["primary"]["veto_signal_times"]
    month_counts = Counter(value[:7] for value in validation_primary_times)
    validation_top_month_share = (
        _share(max(month_counts.values()), len(validation_primary_times))
        if validation_primary_times
        else "1.00000000"
    )

    gates = spec["gates"]
    failed: list[str] = []
    if not all(windows[name]["parent_path_parity"] for name in windows) or not all(
        value["parent_path_parity"] for value in annual.values()
    ):
        failed.append("exact_parent_path_parity")
    if windows["design"]["parent_signal_count"] < gates["design_minimum_parent_signals"]:
        failed.append("design_minimum_parent_signals")
    if windows["validation"]["parent_signal_count"] < gates["validation_minimum_parent_signals"]:
        failed.append("validation_minimum_parent_signals")
    if any(
        D(windows[name]["feature_available_share"]) < D(gates["minimum_feature_available_signal_share"])
        for name in windows
    ):
        failed.append("minimum_feature_available_signal_share")
    if windows["design"]["variants"]["primary"]["actionable_veto_count"] < gates["design_minimum_primary_actionable_vetoes"]:
        failed.append("design_minimum_primary_actionable_vetoes")
    if windows["validation"]["variants"]["primary"]["actionable_veto_count"] < gates["validation_minimum_primary_actionable_vetoes"]:
        failed.append("validation_minimum_primary_actionable_vetoes")
    for name in windows:
        share = D(windows[name]["variants"]["primary"]["action_share"])
        if share < D(gates["primary_action_share_minimum"]) or share > D(gates["primary_action_share_maximum"]):
            failed.append(f"primary_action_share_{name}")
    if any(
        windows["validation"]["variants"][role]["actionable_veto_count"]
        < gates["validation_minimum_each_neighbor_actionable_vetoes"]
        for role in ("lower_threshold_neighbor", "upper_threshold_neighbor")
    ):
        failed.append("validation_minimum_each_neighbor_actionable_vetoes")
    for name in windows:
        hashes = [windows[name]["variants"][role]["action_sha256"] for role, _, _ in VARIANTS]
        if len(set(hashes)) != len(hashes):
            failed.append(f"neighbor_action_hash_differences_{name}")
    if active_years < gates["minimum_active_annual_folds"]:
        failed.append("minimum_active_annual_folds")
    if any(
        0 < count < gates["minimum_primary_vetoes_per_active_annual_fold"]
        for count in primary_year_counts.values()
    ):
        failed.append("minimum_primary_vetoes_per_active_annual_fold")
    if D(top_year_share) > D(gates["maximum_top_year_primary_veto_share"]):
        failed.append("maximum_top_year_primary_veto_share")
    if D(validation_top_month_share) > D(gates["maximum_validation_top_month_primary_veto_share"]):
        failed.append("maximum_validation_top_month_primary_veto_share")

    status = (
        "PASS_PREOUTCOME_DEDUP_SUPPORT_ALLOW_ONE_FROZEN_HYPOTHESIS"
        if not failed
        else "NO_HYPOTHESIS_CLOSE_SIGNAL_DAY_VWAP_OCCUPANCY_AT_SUPPORT_GATE"
    )
    return {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "family_id": FAMILY_ID,
        "authorization": AUTHORIZATION,
        "status": status,
        "runner_identity": RUNNER_IDENTITY,
        "spec": {"path": str(spec_path.relative_to(REPO_ROOT)).replace("\\", "/"), "sha256": sha256(spec_path)},
        "dataset": spec["dataset"],
        "action_fingerprints": _expected_action_fingerprints(),
        "feature_policy": spec["feature_policy"],
        "windows": windows,
        "annual_support": {
            "primary_actionable_vetoes": primary_year_counts,
            "active_annual_folds": active_years,
            "top_year_primary_veto_share": top_year_share,
            "validation_top_month_primary_veto_share": validation_top_month_share,
        },
        "failed_gates": sorted(set(failed)),
        "outcome_access": "DENY_CANDIDATE_ECONOMICS_AND_OOS",
        "scope_note": "Pre-economic causal deduplication and action support only; no PnL, OOS, canonical write, paid API, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred."
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise SupportReject(f"SEALED_OUTPUT_EXISTS:{output}")
    spec_path = args.spec.resolve()
    spec = load_spec(spec_path)
    bars = load_bars(args.data.resolve())
    result = build_result(spec, spec_path, bars)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical_bytes(result) + b"\n")
    print(json.dumps({"status": result["status"], "output": str(output), "sha256": sha256(output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
