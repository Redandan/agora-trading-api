#!/usr/bin/env python3
"""Evidence-bound DRA V1 volume/range entry-admission research adapter."""

from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import btc_dra_reversal_confirmed_exit_v2c as base
from research_pipeline.forward_candidate import (
    FORWARD_ADAPTER_CONTRACT_ID,
    FORWARD_ADAPTER_KEY,
    FORWARD_PARENT,
    FORWARD_SELECTION_CUTOFF,
    load_diagnostic_contract,
)


D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SCHEMA_VERSION = "DRA_FORWARD_ENTRY_ADMISSION_RUNNER_V1"
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
THRESHOLD_ROLES = ("lower_neighbor", "primary", "upper_neighbor")
DD_TOLERANCE_PP = D("0.25")


class ForwardReject(RuntimeError):
    def __init__(self, status: str, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def decimal_median(values: list[D]) -> D:
    ordered = sorted(values)
    midpoint = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[midpoint]
    return (ordered[midpoint - 1] + ordered[midpoint]) / D("2")


class EntryAdmissionEngine(base.Engine):
    def __init__(self, feature: str, threshold: D, *, cap: D = base.REFERENCE_CAP) -> None:
        super().__init__("v1", cap=cap)
        self.feature = feature
        self.threshold = threshold
        self.daily_feature_history: deque[D] = deque(maxlen=20)
        self.feature_day: datetime | None = None
        self.feature_open: D | None = None
        self.feature_high: D | None = None
        self.feature_low: D | None = None
        self.feature_volume = D("0")
        self.current_feature_ratio: D | None = None
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0

    def _indicators(self, bar: base.Bar) -> None:
        if self.feature_day is None or self.feature_day.date() != bar.open_time.date():
            self.feature_day = bar.open_time
            self.feature_open = bar.open
            self.feature_high = bar.high
            self.feature_low = bar.low
            self.feature_volume = bar.volume
        else:
            self.feature_high = max(self.feature_high, bar.high)
            self.feature_low = min(self.feature_low, bar.low)
            self.feature_volume += bar.volume
        super()._indicators(bar)
        if bar.open_time.hour != 23:
            return
        if self.feature == "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN":
            current_value = self.feature_volume
        elif self.feature == "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN":
            current_value = (self.feature_high - self.feature_low) / self.feature_open
        else:
            raise ForwardReject("CONTRACT_REJECT", f"unsupported feature {self.feature}")
        if len(self.daily_feature_history) == 20:
            prior_median = decimal_median(list(self.daily_feature_history))
            self.current_feature_ratio = (
                D("0") if prior_median <= 0 else current_value / prior_median
            )
        else:
            self.current_feature_ratio = None
        self.daily_feature_history.append(current_value)

    def _signal(self, bar: base.Bar) -> bool:
        parent_signal = super()._signal(bar)
        if not parent_signal:
            return False
        self.parent_signal_count += 1
        admitted = (
            self.current_feature_ratio is not None
            and self.current_feature_ratio >= self.threshold
        )
        if admitted:
            self.admitted_signal_count += 1
        else:
            self.vetoed_signal_count += 1
        return admitted

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict:
        result = super().result(final_bar, start, end)
        result.update(
            {
                "admission_feature": self.feature,
                "admission_threshold": str(self.threshold),
                "parent_signal_count": self.parent_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    feature: str,
    threshold: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ForwardReject(
            "DATA_REJECT", f"no candidate bars for {start.isoformat()}..{end.isoformat()}"
        )
    engine = EntryAdmissionEngine(feature, threshold)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def load_manifest(path: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("adapter") != FORWARD_ADAPTER_KEY:
        raise ForwardReject("CONTRACT_REJECT", "manifest adapter mismatch")
    if value.get("parent") != FORWARD_PARENT:
        raise ForwardReject("CONTRACT_REJECT", "manifest parent mismatch")
    if value.get("selection_cutoff") != FORWARD_SELECTION_CUTOFF:
        raise ForwardReject("CONTRACT_REJECT", "manifest selection cutoff mismatch")
    if int(value.get("max_variants", 0)) != 3:
        raise ForwardReject("CONTRACT_REJECT", "manifest must freeze exactly three variants")
    if value.get("authorization") != AUTHORIZATION:
        raise ForwardReject("CONTRACT_REJECT", "manifest authorization mismatch")
    config = value.get("adapter_config")
    if not isinstance(config, dict) or config.get("contract_id") != FORWARD_ADAPTER_CONTRACT_ID:
        raise ForwardReject("CONTRACT_REJECT", "adapter_config contract mismatch")
    contract = load_diagnostic_contract()
    mechanisms = {
        str(item["key"]): item for item in contract["mechanisms"]
    }
    mechanism_key = str(config.get("mechanism_key"))
    if mechanism_key not in mechanisms:
        raise ForwardReject("CONTRACT_REJECT", "manifest mechanism is unsupported")
    return value, config, mechanisms[mechanism_key]


def load_selection(path: Path) -> list[base.Bar]:
    if not path.is_file():
        raise ForwardReject("DATA_REJECT", "canonical selection corpus is missing")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    digest = base.data_hash(bars)
    if len(bars) != base.SELECTION_ROWS or digest != base.SELECTION_SHA256:
        raise ForwardReject(
            "DATA_REJECT",
            {
                "expected_rows": base.SELECTION_ROWS,
                "actual_rows": len(bars),
                "expected_sha256": base.SELECTION_SHA256,
                "actual_sha256": digest,
            },
        )
    return bars


def verify_parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    design = base.simulate(bars, DESIGN, "v1")
    validation = base.simulate(bars, VALIDATION, "v1")
    if base.checkpoint_tuple(design) != base.EXPECTED["v1_design"]:
        raise ForwardReject("BASELINE_REJECT", "DRA V1 Design checkpoint mismatch")
    if base.checkpoint_tuple(validation) != base.EXPECTED["v1_validation"]:
        raise ForwardReject("BASELINE_REJECT", "DRA V1 Validation checkpoint mismatch")
    folds = {
        name: base.simulate(bars, window, "v1") for name, window in FOLDS.items()
    }
    return {"design": design, "validation": validation, "folds": folds}


def dec(result: dict[str, Any], field: str) -> D:
    return D(str(result[field]))


def non_worse_holding(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    candidate_value = candidate.get(field)
    parent_value = parent.get(field)
    if candidate_value is None or parent_value is None:
        return candidate_value == parent_value
    return D(str(candidate_value)) <= D(str(parent_value))


def variant_evidence(
    bars: list[base.Bar],
    baseline: dict[str, Any],
    *,
    feature: str,
    role: str,
    threshold: D,
) -> dict[str, Any]:
    design = simulate_candidate(bars, DESIGN, feature=feature, threshold=threshold)
    validation = simulate_candidate(bars, VALIDATION, feature=feature, threshold=threshold)
    folds = {
        name: simulate_candidate(bars, window, feature=feature, threshold=threshold)
        for name, window in FOLDS.items()
    }
    annual_deltas = {
        name: dec(folds[name], "total_pnl_usdt")
        - dec(baseline["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    }
    positive = [value for value in annual_deltas.values() if value > 0]
    positive_total = sum(positive, D("0"))
    top_year_concentration = (
        max(positive) / positive_total * D("100")
        if positive_total > 0
        else D("100")
    )
    annual_total_wins = sum(value > 0 for value in annual_deltas.values())
    annual_dd_non_worse = sum(
        dec(folds[name], "max_drawdown_pct")
        <= dec(baseline["folds"][name], "max_drawdown_pct") + DD_TOLERANCE_PP
        for name in FOLDS
    )
    return {
        "role": role,
        "threshold": str(threshold),
        "design": design,
        "validation": validation,
        "folds": folds,
        "annual_total_pnl_delta": {
            name: str(value) for name, value in annual_deltas.items()
        },
        "annual_total_wins": annual_total_wins,
        "annual_drawdown_non_worse": annual_dd_non_worse,
        "top_year_positive_delta_contribution_pct": str(
            top_year_concentration.quantize(D("0.000001"))
        ),
    }


def primary_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    design = variant["design"]
    validation = variant["validation"]
    parent_design = baseline["design"]
    parent_validation = baseline["validation"]
    return {
        "design_total_pnl_improves": dec(design, "total_pnl_usdt")
        > dec(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": dec(validation, "total_pnl_usdt")
        > dec(parent_validation, "total_pnl_usdt"),
        "validation_realized_non_worse": dec(validation, "realized_usdt")
        >= dec(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": dec(validation, "unrealized_usdt")
        >= dec(parent_validation, "unrealized_usdt"),
        "validation_drawdown_within_0_25pp": dec(validation, "max_drawdown_pct")
        <= dec(parent_validation, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_median_hold_non_worse": non_worse_holding(
            validation, parent_validation, "median_hold_hours"
        ),
        "validation_p90_hold_non_worse": non_worse_holding(
            validation, parent_validation, "p90_hold_hours"
        ),
        "design_interventions_at_least_8": int(design["vetoed_signal_count"]) >= 8,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
        "annual_total_wins_at_least_3_of_5": int(variant["annual_total_wins"]) >= 3,
        "annual_drawdown_non_worse_at_least_3_of_5": int(
            variant["annual_drawdown_non_worse"]
        )
        >= 3,
        "top_year_positive_delta_contribution_at_most_60pct": D(
            str(variant["top_year_positive_delta_contribution_pct"])
        )
        <= D("60"),
    }


def neighbor_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    validation = variant["validation"]
    parent = baseline["validation"]
    return {
        "validation_total_pnl_improves": dec(validation, "total_pnl_usdt")
        > dec(parent, "total_pnl_usdt"),
        "validation_drawdown_within_0_25pp": dec(validation, "max_drawdown_pct")
        <= dec(parent, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
    }


def freeze_hash(
    *,
    manifest_sha256: str,
    contract_sha256: str,
    runner_sha256: str,
    mechanism_key: str,
) -> str:
    return canonical_sha256(
        {
            "adapter": FORWARD_ADAPTER_KEY,
            "manifest_sha256": manifest_sha256,
            "diagnostic_contract_sha256": contract_sha256,
            "runner_sha256": runner_sha256,
            "selection_data_sha256": base.SELECTION_SHA256,
            "mechanism_key": mechanism_key,
            "frozen_variant": "primary",
        }
    )


def run_preselect(manifest_path: Path, input_path: Path, output: Path) -> dict[str, Any]:
    if output.exists():
        raise ForwardReject("OUTPUT_SEAL_REJECT", str(output))
    manifest, config, mechanism = load_manifest(manifest_path)
    bars = load_selection(input_path)
    baseline = verify_parent_baseline(bars)
    variants = [
        variant_evidence(
            bars,
            baseline,
            feature=str(mechanism["feature"]),
            role=role,
            threshold=D(str(mechanism["thresholds"][role])),
        )
        for role in THRESHOLD_ROLES
    ]
    primary = next(item for item in variants if item["role"] == "primary")
    primary_checks = primary_gates(primary, baseline)
    neighbors = {
        item["role"]: neighbor_gates(item, baseline)
        for item in variants
        if item["role"] != "primary"
    }
    all_pass = all(primary_checks.values()) and all(
        all(value.values()) for value in neighbors.values()
    )
    runner_hash = sha256(Path(__file__))
    contract_path = ROOT / "research_pipeline" / "forward-diagnostic-contract.v1.json"
    contract_hash = sha256(contract_path)
    manifest_hash = sha256(manifest_path)
    frozen_hash = freeze_hash(
        manifest_sha256=manifest_hash,
        contract_sha256=contract_hash,
        runner_sha256=runner_hash,
        mechanism_key=str(config["mechanism_key"]),
    )
    result = {
        "schema_version": SCHEMA_VERSION,
        "status": (
            "CANDIDATE_FROZEN"
            if all_pass
            else "NO_CANDIDATE_FORWARD_ENTRY_ADMISSION"
        ),
        "authorization": AUTHORIZATION,
        "adapter": FORWARD_ADAPTER_KEY,
        "adapter_contract_id": FORWARD_ADAPTER_CONTRACT_ID,
        "mechanism_key": config["mechanism_key"],
        "selection_data_rows": len(bars),
        "selection_data_sha256": base.data_hash(bars),
        "manifest_sha256": manifest_hash,
        "diagnostic_contract_sha256": contract_hash,
        "runner_sha256": runner_hash,
        "baseline_parity": "PASS_DESIGN_VALIDATION_EXACT",
        "oos_opened": False,
        "baseline": baseline,
        "variants": variants,
        "primary_gates": primary_checks,
        "neighbor_stability_gates": neighbors,
        "frozen_variant": "primary",
        "freeze_sha256": frozen_hash,
    }
    write_json(output, result)
    return result


def dataset_bars(path: Path) -> tuple[dict[str, Any], list[base.Bar]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("dataset_type") != "CANDIDATE_OOS_FORWARD_EVIDENCE":
        raise ForwardReject("OOS_SEAL_REJECT", "OOS dataset type is invalid")
    rows: list[str] = []
    for observation in value.get("observations", []):
        for bar in observation.get("bars", []):
            rows.append(
                "\t".join(
                    str(bar[field])
                    for field in (
                        "interval_start",
                        "interval_end",
                        "open",
                        "high",
                        "low",
                        "close",
                        "volume",
                    )
                )
            )
    return value, base.parse_rows("\n".join(rows))


def run_oos(
    manifest_path: Path,
    preselect_path: Path,
    input_path: Path,
    output: Path,
) -> dict[str, Any]:
    if output.exists():
        raise ForwardReject("OOS_SEAL_REJECT", str(output))
    manifest, config, mechanism = load_manifest(manifest_path)
    preselect = json.loads(preselect_path.read_text(encoding="utf-8"))
    if preselect.get("status") != "CANDIDATE_FROZEN":
        raise ForwardReject("OOS_SEAL_REJECT", "preselection froze no candidate")
    runner_hash = sha256(Path(__file__))
    contract_hash = sha256(
        ROOT / "research_pipeline" / "forward-diagnostic-contract.v1.json"
    )
    manifest_hash = sha256(manifest_path)
    expected_freeze = freeze_hash(
        manifest_sha256=manifest_hash,
        contract_sha256=contract_hash,
        runner_sha256=runner_hash,
        mechanism_key=str(config["mechanism_key"]),
    )
    if preselect.get("freeze_sha256") != expected_freeze:
        raise ForwardReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    dataset, bars = dataset_bars(input_path)
    binding = dataset.get("candidate_binding")
    if not isinstance(binding, dict) or binding.get("manifest_sha256") != manifest_hash:
        raise ForwardReject("OOS_SEAL_REJECT", "OOS dataset candidate binding mismatch")
    window = config["oos_window"]
    if (
        dataset.get("coverage_start") != window["start_at"]
        or dataset.get("coverage_end") != window["end_at"]
        or manifest.get("oos_cutoff") != window["end_at"]
    ):
        raise ForwardReject("OOS_SEAL_REJECT", "OOS coverage changed")
    start = datetime.fromisoformat(str(window["start_at"]).replace("Z", "+00:00"))
    end = datetime.fromisoformat(str(window["end_at"]).replace("Z", "+00:00"))
    parent = base.simulate(bars, (start, end), "v1")
    variants = [
        {
            "role": role,
            "threshold": str(mechanism["thresholds"][role]),
            "result": simulate_candidate(
                bars,
                (start, end),
                feature=str(mechanism["feature"]),
                threshold=D(str(mechanism["thresholds"][role])),
            ),
        }
        for role in THRESHOLD_ROLES
    ]
    primary = next(item["result"] for item in variants if item["role"] == "primary")
    neighbors = [item["result"] for item in variants if item["role"] != "primary"]
    gates = {
        "oos_total_pnl_improves": dec(primary, "total_pnl_usdt")
        > dec(parent, "total_pnl_usdt"),
        "oos_realized_non_worse": dec(primary, "realized_usdt")
        >= dec(parent, "realized_usdt"),
        "oos_unrealized_non_worse": dec(primary, "unrealized_usdt")
        >= dec(parent, "unrealized_usdt"),
        "oos_drawdown_within_0_25pp": dec(primary, "max_drawdown_pct")
        <= dec(parent, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "oos_median_hold_non_worse": non_worse_holding(
            primary, parent, "median_hold_hours"
        ),
        "oos_p90_hold_non_worse": non_worse_holding(
            primary, parent, "p90_hold_hours"
        ),
        "oos_interventions_at_least_2": int(primary["vetoed_signal_count"]) >= 2,
        "oos_neighbors_total_nonnegative": all(
            dec(item, "total_pnl_usdt") >= dec(parent, "total_pnl_usdt")
            for item in neighbors
        ),
        "oos_neighbors_drawdown_within_0_25pp": all(
            dec(item, "max_drawdown_pct")
            <= dec(parent, "max_drawdown_pct") + DD_TOLERANCE_PP
            for item in neighbors
        ),
    }
    result = {
        "schema_version": SCHEMA_VERSION,
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "authorization": AUTHORIZATION,
        "adapter": FORWARD_ADAPTER_KEY,
        "mechanism_key": config["mechanism_key"],
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_dataset_sha256": sha256(input_path),
        "oos_coverage_start": window["start_at"],
        "oos_coverage_end": window["end_at"],
        "parent": parent,
        "variants": variants,
        "gates": gates,
    }
    write_json(output, result)
    return result


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def write_reject(path: Path, error: ForwardReject) -> None:
    if path.exists():
        return
    write_json(
        path,
        {
            "schema_version": SCHEMA_VERSION,
            "status": error.status,
            "detail": error.detail,
            "authorization": AUTHORIZATION,
        },
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="stage", required=True)
    preselect = subparsers.add_parser("preselect")
    preselect.add_argument("--manifest", type=Path, required=True)
    preselect.add_argument("--input", type=Path, required=True)
    preselect.add_argument("--output", type=Path, required=True)
    oos = subparsers.add_parser("oos")
    oos.add_argument("--manifest", type=Path, required=True)
    oos.add_argument("--preselect", type=Path, required=True)
    oos.add_argument("--input", type=Path, required=True)
    oos.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = (
            run_preselect(args.manifest, args.input, args.output)
            if args.stage == "preselect"
            else run_oos(args.manifest, args.preselect, args.input, args.output)
        )
    except ForwardReject as error:
        write_reject(args.output, error)
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    except base.ResearchReject as error:
        reject = ForwardReject(error.status, error.detail)
        write_reject(args.output, reject)
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(json.dumps({"status": result["status"], "output": str(args.output)}, ensure_ascii=False))
    return 0 if result["status"] in {"CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
