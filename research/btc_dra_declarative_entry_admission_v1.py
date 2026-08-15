#!/usr/bin/env python3
"""Declarative, equal-capital DRA V1 entry-admission economic screen."""

from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import re
from typing import Any

import btc_dra_equal_capital_capacity_v1 as capacity


base = capacity.base
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "DRA_DECLARATIVE_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "DRA_DECLARATIVE_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_DECLARATIVE_ENTRY_ADMISSION_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1"
GATE_SET = "DRA_DECLARATIVE_ENTRY_ADMISSION_GATES_V1"
SELECTION_CUTOFF = "2025-01-01T00:00:00"
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")
DD_TOLERANCE_PP = D("0.25")
RATIO_QUANTUM = D("0.00000001")
FEATURES = {
    "LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN": "AT_OR_BELOW",
    "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN": "AT_OR_ABOVE",
    "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN": "AT_OR_ABOVE",
}
ROLE_ORDER = {"lower_neighbor": 0, "primary": 1, "upper_neighbor": 2}
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REPOSITORY_PATH = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")


class ScreenReject(RuntimeError):
    def __init__(self, status: str, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def canonical_document_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be a closed object")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _ID.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid")
    return value


def _decimal(value: Any, label: str) -> D:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be an exact decimal")
    try:
        result = D(str(value))
    except Exception as error:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid") from error
    if not result.is_finite() or result <= 0:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be positive")
    return result


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = _exact_keys(
        value,
        {
            "authorization",
            "dataset",
            "document_type",
            "economics",
            "experiment_id",
            "feature",
            "gate_set",
            "oos_access",
            "parent_strategy",
            "prior_evidence",
            "schema_version",
            "selection_cutoff",
            "variants",
        },
        "manifest",
    )
    if manifest["schema_version"] != "1" or manifest["document_type"] != DOCUMENT_TYPE:
        raise ScreenReject("CONTRACT_REJECT", "manifest identity is unsupported")
    if manifest["authorization"] != AUTHORIZATION:
        raise ScreenReject("CONTRACT_REJECT", "manifest authorization is unsupported")
    _identifier(manifest["experiment_id"], "experiment_id")
    if manifest["parent_strategy"] != PARENT_STRATEGY:
        raise ScreenReject("CONTRACT_REJECT", "parent strategy must be BTC_DRA_V1")
    if manifest["selection_cutoff"] != SELECTION_CUTOFF or manifest["oos_access"] != "DENY":
        raise ScreenReject("CONTRACT_REJECT", "selection cutoff and OOS boundary are frozen")
    if manifest["gate_set"] != GATE_SET:
        raise ScreenReject("CONTRACT_REJECT", "gate set is unsupported")

    prior = _exact_keys(
        manifest["prior_evidence"],
        {"disposition", "path", "sha256"},
        "prior_evidence",
    )
    if (
        prior["disposition"]
        != "PRIOR_SUPPORTS_ONE_VOLATILITY_MANAGEMENT_DESIGN_AUDIT"
    ):
        raise ScreenReject("CONTRACT_REJECT", "prior disposition is unsupported")
    if not isinstance(prior["path"], str) or _REPOSITORY_PATH.fullmatch(prior["path"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "prior evidence path is invalid")
    if not isinstance(prior["sha256"], str) or _SHA256.fullmatch(prior["sha256"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "prior evidence sha256 is invalid")

    dataset = _exact_keys(manifest["dataset"], {"canonical_sha256", "rows"}, "dataset")
    if not isinstance(dataset["rows"], int) or isinstance(dataset["rows"], bool) or dataset["rows"] <= 0:
        raise ScreenReject("CONTRACT_REJECT", "dataset rows must be positive")
    if not isinstance(dataset["canonical_sha256"], str) or _SHA256.fullmatch(dataset["canonical_sha256"]) is None:
        raise ScreenReject("CONTRACT_REJECT", "dataset canonical_sha256 is invalid")

    economics = _exact_keys(
        manifest["economics"],
        {"fee_rate", "initial_equity_usdt", "slippage_rate", "slot_capacity_usdt"},
        "economics",
    )
    if (
        _decimal(economics["initial_equity_usdt"], "initial_equity_usdt")
        != INITIAL_EQUITY_USDT
        or _decimal(economics["slot_capacity_usdt"], "slot_capacity_usdt")
        != SLOT_CAPACITY_USDT
        or _decimal(economics["fee_rate"], "fee_rate") != base.FEE
        or _decimal(economics["slippage_rate"], "slippage_rate") != base.SLIPPAGE
    ):
        raise ScreenReject("CONTRACT_REJECT", "economic assumptions differ from the frozen parent")

    feature = _exact_keys(
        manifest["feature"],
        {"decision_time", "key", "lookback_complete_days", "relation"},
        "feature",
    )
    feature_key = feature["key"]
    if feature_key not in FEATURES or feature["relation"] != FEATURES[feature_key]:
        raise ScreenReject("CONTRACT_REJECT", "feature or causal relation is unsupported")
    if feature["lookback_complete_days"] != 20:
        raise ScreenReject("CONTRACT_REJECT", "feature lookback must be 20 complete UTC days")
    if feature["decision_time"] != "LATEST_COMPLETE_UTC_DAY_BEFORE_NEXT_BAR_FILL":
        raise ScreenReject("CONTRACT_REJECT", "feature must be known before the next-bar fill")

    variants = manifest["variants"]
    if not isinstance(variants, list) or not 1 <= len(variants) <= 3:
        raise ScreenReject("CONTRACT_REJECT", "manifest must freeze one to three variants")
    roles: set[str] = set()
    ids: set[str] = set()
    thresholds: set[D] = set()
    for index, raw_variant in enumerate(variants):
        variant = _exact_keys(raw_variant, {"role", "threshold", "variant_id"}, f"variants[{index}]")
        variant_id = _identifier(variant["variant_id"], f"variants[{index}].variant_id")
        role = variant["role"]
        if role not in ROLE_ORDER:
            raise ScreenReject("CONTRACT_REJECT", "variant role is unsupported")
        threshold = _decimal(variant["threshold"], f"variants[{index}].threshold")
        ids.add(variant_id)
        roles.add(role)
        thresholds.add(threshold)
    if len(ids) != len(variants) or len(roles) != len(variants) or len(thresholds) != len(variants):
        raise ScreenReject("CONTRACT_REJECT", "variant ids, roles, and thresholds must be distinct")
    if "primary" not in roles:
        raise ScreenReject("CONTRACT_REJECT", "exactly one primary variant is required")
    ordered = sorted(variants, key=lambda item: ROLE_ORDER[item["role"]])
    ordered_thresholds = [D(str(item["threshold"])) for item in ordered]
    if ordered_thresholds != sorted(ordered_thresholds):
        raise ScreenReject("CONTRACT_REJECT", "neighbor thresholds must be monotonic by role")
    return manifest


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScreenReject("CONTRACT_REJECT", "manifest must be strict UTF-8 JSON") from error
    if raw != canonical_document_bytes(value):
        raise ScreenReject("CONTRACT_REJECT", "manifest must use canonical JSON document bytes")
    return validate_manifest(value), raw


def median(values: list[D]) -> D:
    ordered = sorted(values)
    middle = len(ordered) // 2
    return (
        ordered[middle]
        if len(ordered) % 2
        else (ordered[middle - 1] + ordered[middle]) / D("2")
    )


class DeclarativeEntryAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    def __init__(self, *, feature_key: str, relation: str, threshold: D) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.feature_key = feature_key
        self.relation = relation
        self.threshold = threshold
        self.daily_history: deque[D] = deque(maxlen=20)
        self.feature_day: datetime | None = None
        self.feature_open: D | None = None
        self.feature_high: D | None = None
        self.feature_low: D | None = None
        self.feature_volume = base.ZERO
        self.daily_squared_return_sum = base.ZERO
        self.daily_bar_count = 0
        self.previous_hour_close: D | None = None
        self.current_feature_ratio: D | None = None
        self.complete_feature_days = 0
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0
        self.feature_unavailable_signal_count = 0

    def _daily_value(self) -> D:
        if self.feature_key == "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN":
            return self.feature_volume
        if self.feature_key == "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN":
            assert self.feature_open is not None
            assert self.feature_high is not None
            assert self.feature_low is not None
            return (self.feature_high - self.feature_low) / self.feature_open
        if self.feature_key == "LAGGED_DAILY_REALIZED_VOLATILITY_TO_PRIOR_20D_MEDIAN":
            return self.daily_squared_return_sum.sqrt()
        raise ScreenReject("CONTRACT_REJECT", f"unsupported feature {self.feature_key}")

    def _update_feature(self, bar: base.Bar) -> None:
        if self.feature_day is None or self.feature_day.date() != bar.open_time.date():
            self.feature_day = bar.open_time
            self.feature_open = bar.open
            self.feature_high = bar.high
            self.feature_low = bar.low
            self.feature_volume = base.ZERO
            self.daily_squared_return_sum = base.ZERO
            self.daily_bar_count = 0
        assert self.feature_high is not None and self.feature_low is not None
        self.feature_high = max(self.feature_high, bar.high)
        self.feature_low = min(self.feature_low, bar.low)
        self.feature_volume += bar.volume
        if self.previous_hour_close is not None:
            hourly_return = (bar.close / self.previous_hour_close) - D("1")
            self.daily_squared_return_sum += hourly_return * hourly_return
        self.previous_hour_close = bar.close
        self.daily_bar_count += 1
        if bar.open_time.hour != 23 or self.daily_bar_count != 24:
            return
        current = self._daily_value()
        if len(self.daily_history) == 20:
            prior_median = median(list(self.daily_history))
            self.current_feature_ratio = (
                None
                if prior_median <= 0
                else (current / prior_median).quantize(RATIO_QUANTUM, rounding=ROUND_HALF_UP)
            )
        else:
            self.current_feature_ratio = None
        self.daily_history.append(current)
        self.complete_feature_days += 1

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        self._update_feature(bar)

    def _signal(self, bar: base.Bar) -> bool:
        parent_signal = super()._signal(bar)
        if not parent_signal:
            return False
        self.parent_signal_count += 1
        ratio = self.current_feature_ratio
        if ratio is None:
            self.feature_unavailable_signal_count += 1
            self.vetoed_signal_count += 1
            return False
        admitted = (
            ratio <= self.threshold
            if self.relation == "AT_OR_BELOW"
            else ratio >= self.threshold
        )
        if admitted:
            self.admitted_signal_count += 1
        else:
            self.vetoed_signal_count += 1
        return admitted

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        result.update(
            {
                "runner_identity": RUNNER_IDENTITY,
                "admission_feature": self.feature_key,
                "admission_relation": self.relation,
                "admission_threshold": str(self.threshold),
                "complete_feature_days": self.complete_feature_days,
                "parent_signal_count": self.parent_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
                "feature_unavailable_signal_count": self.feature_unavailable_signal_count,
                "admission_accounting_reconciles": self.parent_signal_count
                == self.admitted_signal_count + self.vetoed_signal_count,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    feature_key: str,
    relation: str,
    threshold: D,
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = DeclarativeEntryAdmissionEngine(
        feature_key=feature_key,
        relation=relation,
        threshold=threshold,
    )
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def load_selection(path: Path, manifest: dict[str, Any]) -> list[base.Bar]:
    if not path.is_file():
        raise ScreenReject("DATA_REJECT", "selection corpus is missing")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    digest = base.data_hash(bars)
    expected = manifest["dataset"]
    if len(bars) != expected["rows"] or digest != expected["canonical_sha256"]:
        raise ScreenReject(
            "DATA_REJECT",
            {"actual_rows": len(bars), "actual_sha256": digest, "expected": expected},
        )
    if bars[-1].close_time > datetime.fromisoformat(SELECTION_CUTOFF):
        raise ScreenReject("OOS_REJECT", "selection corpus crosses the frozen cutoff")
    return bars


def verify_prior_evidence(manifest: dict[str, Any]) -> dict[str, Any]:
    binding = manifest["prior_evidence"]
    candidate = REPOSITORY_ROOT.joinpath(*binding["path"].split("/"))
    resolved = candidate.resolve(strict=True)
    try:
        resolved.relative_to(REPOSITORY_ROOT)
    except ValueError as error:
        raise ScreenReject("PRIOR_REJECT", "prior evidence escapes the repository") from error
    if not resolved.is_file() or resolved.is_symlink():
        raise ScreenReject("PRIOR_REJECT", "prior evidence must be a regular non-link file")
    raw = resolved.read_bytes()
    if sha256_bytes(raw) != binding["sha256"]:
        raise ScreenReject("PRIOR_REJECT", "prior evidence hash mismatch")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ScreenReject("PRIOR_REJECT", "prior evidence must be strict JSON") from error
    if (
        value.get("disposition") != binding["disposition"]
        or value.get("authorization") != AUTHORIZATION
        or value.get("document_type")
        != "DRA_VOLATILITY_MANAGEMENT_PRIMARY_PRIOR_AUDIT_V4"
    ):
        raise ScreenReject("PRIOR_REJECT", "prior evidence identity mismatch")
    return {"path": binding["path"], "sha256": binding["sha256"]}


def parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    return {
        "design": capacity.simulate_capacity(
            bars, DESIGN, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
        ),
        "validation": capacity.simulate_capacity(
            bars, VALIDATION, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
        ),
        "folds": {
            name: capacity.simulate_capacity(
                bars, window, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
            )
            for name, window in FOLDS.items()
        },
    }


def _value(result: dict[str, Any], field: str) -> D:
    return D(str(result[field]))


def _non_worse_holding(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    candidate_value = candidate.get(field)
    parent_value = parent.get(field)
    if candidate_value is None or parent_value is None:
        return candidate_value == parent_value
    return D(str(candidate_value)) <= D(str(parent_value))


def variant_evidence(
    bars: list[base.Bar],
    baseline: dict[str, Any],
    *,
    feature_key: str,
    relation: str,
    variant: dict[str, Any],
) -> dict[str, Any]:
    threshold = D(str(variant["threshold"]))
    design = simulate_candidate(
        bars, DESIGN, feature_key=feature_key, relation=relation, threshold=threshold
    )
    validation = simulate_candidate(
        bars, VALIDATION, feature_key=feature_key, relation=relation, threshold=threshold
    )
    folds = {
        name: simulate_candidate(
            bars, window, feature_key=feature_key, relation=relation, threshold=threshold
        )
        for name, window in FOLDS.items()
    }
    annual_deltas = {
        name: _value(folds[name], "total_pnl_usdt")
        - _value(baseline["folds"][name], "total_pnl_usdt")
        for name in FOLDS
    }
    positive = [value for value in annual_deltas.values() if value > 0]
    positive_total = sum(positive, D("0"))
    concentration = (
        max(positive) / positive_total * D("100") if positive_total > 0 else D("100")
    )
    return {
        "variant_id": variant["variant_id"],
        "role": variant["role"],
        "threshold": str(threshold),
        "design": design,
        "validation": validation,
        "folds": folds,
        "paired_equal_capital": {
            "design": capacity.equal_capital_deltas(baseline["design"], design),
            "validation": capacity.equal_capital_deltas(baseline["validation"], validation),
            "folds": {
                name: capacity.equal_capital_deltas(baseline["folds"][name], folds[name])
                for name in FOLDS
            },
        },
        "annual_total_pnl_delta": {name: str(value) for name, value in annual_deltas.items()},
        "annual_total_wins": sum(value > 0 for value in annual_deltas.values()),
        "annual_drawdown_non_worse": sum(
            _value(folds[name], "max_drawdown_pct")
            <= _value(baseline["folds"][name], "max_drawdown_pct") + DD_TOLERANCE_PP
            for name in FOLDS
        ),
        "top_year_positive_delta_contribution_pct": str(
            concentration.quantize(D("0.000001"), rounding=ROUND_HALF_UP)
        ),
    }


def primary_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    design = variant["design"]
    validation = variant["validation"]
    parent_design = baseline["design"]
    parent_validation = baseline["validation"]
    return {
        "design_total_pnl_improves": _value(design, "total_pnl_usdt") > _value(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": _value(validation, "total_pnl_usdt") > _value(parent_validation, "total_pnl_usdt"),
        "validation_realized_non_worse": _value(validation, "realized_usdt") >= _value(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt") >= _value(parent_validation, "unrealized_usdt"),
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct") <= _value(parent_validation, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_median_hold_non_worse": _non_worse_holding(validation, parent_validation, "median_hold_hours"),
        "validation_p90_hold_non_worse": _non_worse_holding(validation, parent_validation, "p90_hold_hours"),
        "design_interventions_at_least_8": int(design["vetoed_signal_count"]) >= 8,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
        "annual_total_wins_at_least_3_of_5": int(variant["annual_total_wins"]) >= 3,
        "annual_drawdown_non_worse_at_least_4_of_5": int(variant["annual_drawdown_non_worse"]) >= 4,
        "top_year_positive_delta_contribution_at_most_60pct": D(str(variant["top_year_positive_delta_contribution_pct"])) <= D("60"),
    }


def neighbor_gates(variant: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    validation = variant["validation"]
    parent = baseline["validation"]
    return {
        "validation_total_pnl_non_worse": _value(validation, "total_pnl_usdt") >= _value(parent, "total_pnl_usdt"),
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct") <= _value(parent, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
    }


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    prior_evidence = verify_prior_evidence(manifest)
    bars = load_selection(input_path, manifest)
    baseline = parent_baseline(bars)
    feature = manifest["feature"]
    variants = [
        variant_evidence(
            bars,
            baseline,
            feature_key=feature["key"],
            relation=feature["relation"],
            variant=variant,
        )
        for variant in manifest["variants"]
    ]
    primary = next(item for item in variants if item["role"] == "primary")
    primary_checks = primary_gates(primary, baseline)
    neighbor_checks = {
        item["variant_id"]: neighbor_gates(item, baseline)
        for item in variants
        if item["role"] != "primary"
    }
    passed = all(primary_checks.values()) and all(
        all(checks.values()) for checks in neighbor_checks.values()
    )
    result = {
        "authorization": AUTHORIZATION,
        "baseline": baseline,
        "dataset": {
            "canonical_sha256": base.data_hash(bars),
            "rows": len(bars),
            "selection_cutoff": SELECTION_CUTOFF,
        },
        "document_type": RESULT_TYPE,
        "economic_assumptions": manifest["economics"],
        "experiment_id": manifest["experiment_id"],
        "feature": feature,
        "gate_set": GATE_SET,
        "manifest_sha256": sha256_bytes(manifest_raw),
        "neighbor_stability_gates": neighbor_checks,
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "prior_evidence": prior_evidence,
        "primary_gates": primary_checks,
        "recommended_next_action": (
            "FREEZE_ONE_HYPOTHESIS_MANIFEST"
            if passed
            else "CLOSE_FEATURE_FAMILY_WITHOUT_TUNING"
        ),
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256_path(Path(__file__)),
        "schema_version": "1",
        "status": (
            "ECONOMIC_SCREEN_PASS_READY_FOR_FROZEN_HYPOTHESIS"
            if passed
            else "NO_MECHANISM_CLOSE_FEATURE_FAMILY"
        ),
        "variants": variants,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(canonical_document_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_screen(args.manifest, args.input, args.output)
    except (ScreenReject, base.ResearchReject) as error:
        status = getattr(error, "status", "DATA_REJECT")
        detail = getattr(error, "detail", str(error))
        print(json.dumps({"detail": detail, "status": status}, ensure_ascii=False))
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
