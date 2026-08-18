#!/usr/bin/env python3
"""Preregistered historical CFTC TFF entry-admission screen for BTC DRA V1."""

from __future__ import annotations

import argparse
from bisect import bisect_right
import csv
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import io
import json
import math
from pathlib import Path
import re
import sys
from typing import Any, Iterable
import zipfile


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_equal_capital_capacity_v1 as capacity
from research_pipeline import cftc_cme_bitcoin_tff_source as cftc_source
from research_pipeline import cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1 as factor_evaluator


base = capacity.base
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "BTC_DRA_CFTC_TFF_HISTORICAL_ENTRY_ADMISSION_MANIFEST_V1"
RESULT_TYPE = "BTC_DRA_CFTC_TFF_HISTORICAL_ENTRY_ADMISSION_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_CFTC_TFF_HISTORICAL_ENTRY_ADMISSION_RUNNER_V1"
PARENT_STRATEGY = "BTC_DRA_V1"
FACTOR_IDENTITY = "CFTC_TFF_LEV_MONEY_NET_PCT_OI_WEEKLY_DELTA_CONTINUATION_168H_V1"
FACTOR_CONTRACT_SHA256 = factor_evaluator.frozen_package()["contract_sha256"]
GATE_SET = "BTC_DRA_CFTC_TFF_HISTORICAL_ENTRY_ADMISSION_GATES_V1"
SELECTION_CUTOFF = datetime(2025, 1, 1)
DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), SELECTION_CUTOFF)
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")
DD_TOLERANCE_PP = D("0.25")
AVAILABILITY_LAG_DAYS = 14
FACTOR_VALID_HOURS = 168
ION_EXCLUSION_START = date(2023, 1, 31)
ION_EXCLUSION_END = date(2023, 3, 14)
LONG_FIELD = "Pct_of_OI_Lev_Money_Long_All"
SHORT_FIELD = "Pct_of_OI_Lev_Money_Short_All"
LONG_INDEX = cftc_source.ORDERED_FIELDS.index(LONG_FIELD)
SHORT_INDEX = cftc_source.ORDERED_FIELDS.index(SHORT_FIELD)
PCT_Q = D("0.000001")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REPOSITORY_PATH = re.compile(r"^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$")
_HTTPS_CFTC = re.compile(r"^https://www[.]cftc[.]gov/files/dea/history/fut_fin_txt_[0-9]{4}[.]zip$")


class ScreenReject(RuntimeError):
    def __init__(self, status: str, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def canonical_document_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


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


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be lowercase SHA-256")
    return value


def _repo_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or _REPOSITORY_PATH.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be a repository-relative path")
    return value


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be a positive integer")
    return value


def _positive_decimal(value: Any, label: str) -> D:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be an exact decimal")
    try:
        result = D(str(value))
    except Exception as error:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid") from error
    if not result.is_finite() or result <= 0:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be positive")
    return result


def _parse_timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be an ISO timestamp")
    try:
        result = datetime.fromisoformat(value)
    except ValueError as error:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid") from error
    if result.tzinfo is not None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be naive UTC")
    return result


def _validate_binding(binding: Any, expected_path: str, label: str) -> dict[str, Any]:
    value = _exact_keys(binding, {"path", "sha256"}, label)
    if _repo_path(value["path"], f"{label}.path") != expected_path:
        raise ScreenReject("CONTRACT_REJECT", f"{label} path drift")
    _sha256(value["sha256"], f"{label}.sha256")
    return value


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = _exact_keys(
        value,
        {
            "archives", "authorization", "availability", "bindings", "dataset",
            "document_type", "economics", "experiment_id", "factor", "gate_set",
            "oos_access", "parent_strategy", "schema_version", "selection_cutoff", "windows",
        },
        "manifest",
    )
    if manifest["schema_version"] != "1" or manifest["document_type"] != DOCUMENT_TYPE:
        raise ScreenReject("CONTRACT_REJECT", "manifest identity drift")
    if manifest["authorization"] != AUTHORIZATION or manifest["parent_strategy"] != PARENT_STRATEGY:
        raise ScreenReject("CONTRACT_REJECT", "authorization or parent strategy drift")
    if manifest["experiment_id"] != "cftc-tff-dra-entry-admission-historical-v1":
        raise ScreenReject("CONTRACT_REJECT", "experiment identity drift")
    if manifest["gate_set"] != GATE_SET or manifest["oos_access"] != "DENY":
        raise ScreenReject("CONTRACT_REJECT", "gate set or OOS boundary drift")
    if _parse_timestamp(manifest["selection_cutoff"], "selection_cutoff") != SELECTION_CUTOFF:
        raise ScreenReject("CONTRACT_REJECT", "selection cutoff drift")

    dataset = _exact_keys(manifest["dataset"], {"canonical_sha256", "rows"}, "dataset")
    _sha256(dataset["canonical_sha256"], "dataset.canonical_sha256")
    _positive_int(dataset["rows"], "dataset.rows")
    economics = _exact_keys(
        manifest["economics"],
        {"fee_rate", "initial_equity_usdt", "slippage_rate", "slot_capacity_usdt"},
        "economics",
    )
    if (
        _positive_decimal(economics["initial_equity_usdt"], "initial_equity_usdt") != INITIAL_EQUITY_USDT
        or _positive_decimal(economics["slot_capacity_usdt"], "slot_capacity_usdt") != SLOT_CAPACITY_USDT
        or _positive_decimal(economics["fee_rate"], "fee_rate") != base.FEE
        or _positive_decimal(economics["slippage_rate"], "slippage_rate") != base.SLIPPAGE
    ):
        raise ScreenReject("CONTRACT_REJECT", "economic assumptions differ from the parent")

    factor = _exact_keys(
        manifest["factor"],
        {"admission_rule", "factor_identity", "formula", "negative_action", "positive_action", "zero_action"},
        "factor",
    )
    if factor != {
        "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_FACTOR_DELTA_GT_ZERO",
        "factor_identity": FACTOR_IDENTITY,
        "formula": "(current_long_pct-current_short_pct)-(prior_long_pct-prior_short_pct)",
        "negative_action": "HOLD_CASH",
        "positive_action": "ADMIT",
        "zero_action": "HOLD_CASH",
    }:
        raise ScreenReject("CONTRACT_REJECT", "factor semantics drift")

    availability = _exact_keys(
        manifest["availability"],
        {"eligible_time", "exact_predecessor_days", "factor_valid_hours", "ion_exclusion", "non_tuesday_action", "report_lag_calendar_days"},
        "availability",
    )
    ion = _exact_keys(availability["ion_exclusion"], {"end_inclusive", "reason", "start_inclusive"}, "availability.ion_exclusion")
    if availability != {
        "eligible_time": "REPORT_DATE_PLUS_14_CALENDAR_DAYS_AT_00_00_UTC",
        "exact_predecessor_days": 7,
        "factor_valid_hours": FACTOR_VALID_HOURS,
        "ion_exclusion": {
            "end_inclusive": ION_EXCLUSION_END.isoformat(),
            "reason": "CFTC_2023_ION_DELAYED_PUBLICATION",
            "start_inclusive": ION_EXCLUSION_START.isoformat(),
        },
        "non_tuesday_action": "EXCLUDE",
        "report_lag_calendar_days": AVAILABILITY_LAG_DAYS,
    } or ion["reason"] != "CFTC_2023_ION_DELAYED_PUBLICATION":
        raise ScreenReject("CONTRACT_REJECT", "availability policy drift")

    windows = _exact_keys(manifest["windows"], {"annual_folds", "design", "outcome_horizon_hours", "validation"}, "windows")
    if windows != {
        "annual_folds": [str(year) for year in range(2020, 2025)],
        "design": {"end_exclusive": DESIGN[1].isoformat(), "start_inclusive": DESIGN[0].isoformat()},
        "outcome_horizon_hours": 168,
        "validation": {"end_exclusive": VALIDATION[1].isoformat(), "start_inclusive": VALIDATION[0].isoformat()},
    }:
        raise ScreenReject("CONTRACT_REJECT", "research windows drift")

    bindings = _exact_keys(
        manifest["bindings"],
        {
            "base_runner", "capacity_runner", "factor_contract", "factor_evaluator",
            "manifest_schema", "runner", "source_contract", "source_field_definition",
        },
        "bindings",
    )
    _validate_binding(bindings["runner"], "research/btc_dra_cftc_tff_entry_admission_historical_v1.py", "bindings.runner")
    _validate_binding(bindings["manifest_schema"], "research_pipeline/btc-dra-cftc-tff-historical-entry-admission-manifest.v1.schema.json", "bindings.manifest_schema")
    _validate_binding(bindings["capacity_runner"], "research/btc_dra_equal_capital_capacity_v1.py", "bindings.capacity_runner")
    _validate_binding(bindings["base_runner"], "research/btc_dra_reversal_confirmed_exit_v2c.py", "bindings.base_runner")
    _validate_binding(bindings["factor_evaluator"], "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py", "bindings.factor_evaluator")
    _validate_binding(bindings["source_field_definition"], "research_pipeline/cftc_cme_bitcoin_tff_source.py", "bindings.source_field_definition")
    factor_binding = _validate_binding(bindings["factor_contract"], "research_pipeline/cftc-tff-lev-money-net-pct-oi-delta-factor-contract.v1.json", "bindings.factor_contract")
    source_binding = _validate_binding(bindings["source_contract"], "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json", "bindings.source_contract")
    if factor_binding["sha256"] != FACTOR_CONTRACT_SHA256 or source_binding["sha256"] != factor_evaluator.SOURCE_CONTRACT_SHA256:
        raise ScreenReject("CONTRACT_REJECT", "frozen CFTC contract hash drift")

    archives = manifest["archives"]
    if not isinstance(archives, list) or len(archives) != 6:
        raise ScreenReject("CONTRACT_REJECT", "exactly six annual archives are required")
    expected_years = list(range(2019, 2025))
    for expected_year, raw in zip(expected_years, archives, strict=True):
        archive = _exact_keys(
            raw,
            {"archive_bytes", "archive_sha256", "entry_bytes", "entry_name", "entry_sha256", "exact_contract_rows", "path", "source_url", "year"},
            f"archives[{expected_year}]",
        )
        if archive["year"] != expected_year:
            raise ScreenReject("CONTRACT_REJECT", "archive years must be ordered 2019 through 2024")
        expected_name = f"fut_fin_txt_{expected_year}.zip"
        if archive["source_url"] != f"https://www.cftc.gov/files/dea/history/{expected_name}" or _HTTPS_CFTC.fullmatch(archive["source_url"]) is None:
            raise ScreenReject("CONTRACT_REJECT", "archive source URL drift")
        if _repo_path(archive["path"], "archive.path") != f".research-state/experiments/cftc-tff-dra-entry-admission-historical-v1/inputs/{expected_name}":
            raise ScreenReject("CONTRACT_REJECT", "archive path drift")
        if archive["entry_name"] != "FinFutYY.txt":
            raise ScreenReject("CONTRACT_REJECT", "archive entry name drift")
        for key in ("archive_bytes", "entry_bytes", "exact_contract_rows"):
            _positive_int(archive[key], f"archive.{key}")
        _sha256(archive["archive_sha256"], "archive.archive_sha256")
        _sha256(archive["entry_sha256"], "archive.entry_sha256")
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


def verify_bindings(manifest: dict[str, Any]) -> dict[str, Any]:
    verified: dict[str, Any] = {}
    for name, binding in manifest["bindings"].items():
        path = REPOSITORY_ROOT.joinpath(*binding["path"].split("/"))
        resolved = path.resolve(strict=True)
        try:
            resolved.relative_to(REPOSITORY_ROOT)
        except ValueError as error:
            raise ScreenReject("BINDING_REJECT", f"{name} escapes repository") from error
        if not resolved.is_file() or resolved.is_symlink() or sha256_path(resolved) != binding["sha256"]:
            raise ScreenReject("BINDING_REJECT", f"{name} hash mismatch")
        verified[name] = dict(binding)
    return verified


def load_selection(path: Path, manifest: dict[str, Any]) -> list[base.Bar]:
    if not path.is_file():
        raise ScreenReject("DATA_REJECT", "selection corpus is missing")
    bars = base.parse_rows(path.read_text(encoding="utf-8"))
    expected = manifest["dataset"]
    if len(bars) != expected["rows"] or base.data_hash(bars) != expected["canonical_sha256"]:
        raise ScreenReject("DATA_REJECT", "selection corpus identity mismatch")
    if bars[-1].close_time > SELECTION_CUTOFF:
        raise ScreenReject("OOS_REJECT", "selection corpus crosses the frozen cutoff")
    return bars


def _target_rows(raw: bytes, expected_year: int) -> list[list[str]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ScreenReject("DATA_REJECT", f"{expected_year} archive entry is not UTF-8") from error
    rows: list[list[str]] = []
    for row in csv.reader(io.StringIO(text, newline="")):
        if len(row) <= 3 or row[3] != cftc_source.CONTRACT_CODE:
            continue
        if len(row) != 87 or row[0] != cftc_source.MARKET_NAME or row[86] != cftc_source.REPORT_FAMILY_MARKER:
            raise ScreenReject("DATA_REJECT", f"{expected_year} target row identity drift")
        try:
            compact = datetime.strptime(row[1], "%y%m%d").date()
            dashed = datetime.strptime(row[2], "%Y-%m-%d").date()
        except ValueError as error:
            raise ScreenReject("DATA_REJECT", f"{expected_year} target row date malformed") from error
        if compact != dashed or dashed.year != expected_year:
            raise ScreenReject("DATA_REJECT", f"{expected_year} target row date mismatch")
        rows.append(row)
    return rows


def load_historical_rows(manifest: dict[str, Any]) -> tuple[dict[date, list[str]], list[dict[str, Any]]]:
    by_date: dict[date, list[str]] = {}
    evidence: list[dict[str, Any]] = []
    for archive in manifest["archives"]:
        path = REPOSITORY_ROOT.joinpath(*archive["path"].split("/"))
        resolved = path.resolve(strict=True)
        try:
            resolved.relative_to(REPOSITORY_ROOT)
        except ValueError as error:
            raise ScreenReject("DATA_REJECT", "archive path escapes repository") from error
        if not resolved.is_file() or resolved.is_symlink():
            raise ScreenReject("DATA_REJECT", f"archive is not a regular file: {archive['path']}")
        if resolved.stat().st_size != archive["archive_bytes"] or sha256_path(resolved) != archive["archive_sha256"]:
            raise ScreenReject("DATA_REJECT", f"archive seal mismatch: {archive['year']}")
        try:
            with zipfile.ZipFile(resolved) as package:
                if package.namelist() != [archive["entry_name"]]:
                    raise ScreenReject("DATA_REJECT", f"archive member drift: {archive['year']}")
                info = package.getinfo(archive["entry_name"])
                raw = package.read(info)
        except (zipfile.BadZipFile, KeyError, OSError) as error:
            raise ScreenReject("DATA_REJECT", f"archive cannot be read: {archive['year']}") from error
        if info.file_size != archive["entry_bytes"] or len(raw) != archive["entry_bytes"] or sha256_bytes(raw) != archive["entry_sha256"]:
            raise ScreenReject("DATA_REJECT", f"archive entry seal mismatch: {archive['year']}")
        rows = _target_rows(raw, archive["year"])
        if len(rows) != archive["exact_contract_rows"]:
            raise ScreenReject("DATA_REJECT", f"exact contract row count mismatch: {archive['year']}")
        for row in rows:
            report_date = datetime.strptime(row[2], "%Y-%m-%d").date()
            if report_date in by_date:
                raise ScreenReject("DATA_REJECT", f"duplicate report date: {report_date}")
            by_date[report_date] = row
        evidence.append({key: archive[key] for key in ("year", "path", "archive_bytes", "archive_sha256", "entry_name", "entry_bytes", "entry_sha256", "exact_contract_rows")})
    return by_date, evidence


def _factor_level(row: list[str]) -> D:
    long_pct = factor_evaluator.parse_factor_decimal(row[LONG_INDEX])
    short_pct = factor_evaluator.parse_factor_decimal(row[SHORT_INDEX])
    return long_pct - short_pct


def build_factor_points(rows: dict[date, list[str]]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    points: list[dict[str, Any]] = []
    exclusions = {"NON_TUESDAY": 0, "ION_DELAY": 0, "MISSING_EXACT_PREDECESSOR": 0, "DECISION_AT_OR_AFTER_CUTOFF": 0}
    excluded_dates = {
        day for day in rows
        if day.weekday() != 1 or ION_EXCLUSION_START <= day <= ION_EXCLUSION_END
    }
    for day in sorted(rows):
        if day.weekday() != 1:
            exclusions["NON_TUESDAY"] += 1
            continue
        if ION_EXCLUSION_START <= day <= ION_EXCLUSION_END:
            exclusions["ION_DELAY"] += 1
            continue
        prior_day = day - timedelta(days=7)
        if prior_day not in rows or prior_day in excluded_dates:
            exclusions["MISSING_EXACT_PREDECESSOR"] += 1
            continue
        eligible_at = datetime.combine(day + timedelta(days=AVAILABILITY_LAG_DAYS), datetime.min.time())
        if eligible_at >= SELECTION_CUTOFF:
            exclusions["DECISION_AT_OR_AFTER_CUTOFF"] += 1
            continue
        delta = _factor_level(rows[day]) - _factor_level(rows[prior_day])
        points.append({
            "report_date": day.isoformat(),
            "prior_report_date": prior_day.isoformat(),
            "eligible_at": eligible_at.isoformat(),
            "factor_delta": str(delta),
            "factor_sign": 1 if delta > 0 else -1 if delta < 0 else 0,
        })
    return points, exclusions


class CftcEntryAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    def __init__(self, factor_points: list[dict[str, Any]]) -> None:
        super().__init__(slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT)
        self.factor_points = factor_points
        self.factor_times = [datetime.fromisoformat(point["eligible_at"]) for point in factor_points]
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0
        self.factor_unavailable_signal_count = 0
        self.negative_or_zero_signal_count = 0

    def _latest_factor(self, at: datetime) -> dict[str, Any] | None:
        index = bisect_right(self.factor_times, at) - 1
        if index < 0:
            return None
        point = self.factor_points[index]
        return (
            point
            if at < self.factor_times[index] + timedelta(hours=FACTOR_VALID_HOURS)
            else None
        )

    def _signal(self, bar: base.Bar) -> bool:
        if not super()._signal(bar):
            return False
        self.parent_signal_count += 1
        point = self._latest_factor(bar.open_time)
        if point is None:
            self.factor_unavailable_signal_count += 1
            self.vetoed_signal_count += 1
            return False
        if int(point["factor_sign"]) > 0:
            self.admitted_signal_count += 1
            return True
        self.negative_or_zero_signal_count += 1
        self.vetoed_signal_count += 1
        return False

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        result.update({
            "runner_identity": RUNNER_IDENTITY,
            "factor_identity": FACTOR_IDENTITY,
            "parent_signal_count": self.parent_signal_count,
            "admitted_signal_count": self.admitted_signal_count,
            "vetoed_signal_count": self.vetoed_signal_count,
            "factor_unavailable_signal_count": self.factor_unavailable_signal_count,
            "negative_or_zero_signal_count": self.negative_or_zero_signal_count,
            "admission_accounting_reconciles": self.parent_signal_count == self.admitted_signal_count + self.vetoed_signal_count,
        })
        return result


def simulate_candidate(bars: list[base.Bar], window: tuple[datetime, datetime], factor_points: list[dict[str, Any]]) -> dict[str, Any]:
    start, end = window
    selected = [bar for bar in bars if start - timedelta(days=90) <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", "no bars inside candidate window")
    engine = CftcEntryAdmissionEngine(factor_points)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    def run(window: tuple[datetime, datetime]) -> dict[str, Any]:
        return capacity.simulate_capacity(
            bars, window, slot_capacity_usdt=SLOT_CAPACITY_USDT, initial_equity_usdt=INITIAL_EQUITY_USDT
        )
    return {"design": run(DESIGN), "validation": run(VALIDATION), "folds": {name: run(window) for name, window in FOLDS.items()}}


def _value(result: dict[str, Any], field: str) -> D:
    return D(str(result[field]))


def _non_worse_holding(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    candidate_value, parent_value = candidate.get(field), parent.get(field)
    if candidate_value is None or parent_value is None:
        return candidate_value == parent_value
    return D(str(candidate_value)) <= D(str(parent_value))


def economic_evidence(bars: list[base.Bar], baseline: dict[str, Any], factor_points: list[dict[str, Any]]) -> dict[str, Any]:
    design = simulate_candidate(bars, DESIGN, factor_points)
    validation = simulate_candidate(bars, VALIDATION, factor_points)
    folds = {name: simulate_candidate(bars, window, factor_points) for name, window in FOLDS.items()}
    annual_deltas = {name: _value(folds[name], "total_pnl_usdt") - _value(baseline["folds"][name], "total_pnl_usdt") for name in FOLDS}
    positive = [value for value in annual_deltas.values() if value > 0]
    positive_total = sum(positive, D("0"))
    top_year_share = max(positive) / positive_total * D("100") if positive_total > 0 else D("100")
    realized_delta = _value(validation, "realized_usdt") - _value(baseline["validation"], "realized_usdt")
    total_delta = _value(validation, "total_pnl_usdt") - _value(baseline["validation"], "total_pnl_usdt")
    realized_contribution = realized_delta / total_delta * D("100") if total_delta > 0 else D("0")
    return {
        "design": design,
        "validation": validation,
        "folds": folds,
        "paired_equal_capital": {
            "design": capacity.equal_capital_deltas(baseline["design"], design),
            "validation": capacity.equal_capital_deltas(baseline["validation"], validation),
            "folds": {name: capacity.equal_capital_deltas(baseline["folds"][name], folds[name]) for name in FOLDS},
        },
        "annual_total_pnl_delta": {name: str(value) for name, value in annual_deltas.items()},
        "annual_total_wins": sum(value > 0 for value in annual_deltas.values()),
        "annual_drawdown_non_worse": sum(
            _value(folds[name], "max_drawdown_pct") <= _value(baseline["folds"][name], "max_drawdown_pct") + DD_TOLERANCE_PP
            for name in FOLDS
        ),
        "top_year_positive_delta_contribution_pct": str(top_year_share.quantize(PCT_Q, rounding=ROUND_HALF_UP)),
        "validation_realized_delta_contribution_pct": str(realized_contribution.quantize(PCT_Q, rounding=ROUND_HALF_UP)),
    }


def economic_gates(evidence: dict[str, Any], baseline: dict[str, Any]) -> dict[str, bool]:
    design, validation = evidence["design"], evidence["validation"]
    parent_design, parent_validation = baseline["design"], baseline["validation"]
    return {
        "design_total_pnl_improves": _value(design, "total_pnl_usdt") > _value(parent_design, "total_pnl_usdt"),
        "validation_total_pnl_improves": _value(validation, "total_pnl_usdt") > _value(parent_validation, "total_pnl_usdt"),
        "validation_realized_pnl_improves": _value(validation, "realized_usdt") > _value(parent_validation, "realized_usdt"),
        "validation_unrealized_non_worse": _value(validation, "unrealized_usdt") >= _value(parent_validation, "unrealized_usdt"),
        "validation_realized_contribution_at_least_50pct": D(evidence["validation_realized_delta_contribution_pct"]) >= D("50"),
        "validation_drawdown_within_0_25pp": _value(validation, "max_drawdown_pct") <= _value(parent_validation, "max_drawdown_pct") + DD_TOLERANCE_PP,
        "validation_underwater_duration_non_worse": int(validation["inventory_path"]["maximum_underwater_duration_hours"]) <= int(parent_validation["inventory_path"]["maximum_underwater_duration_hours"]),
        "validation_terminal_inventory_count_non_worse": len(validation["terminal_inventory"]) <= len(parent_validation["terminal_inventory"]),
        "validation_median_hold_non_worse": _non_worse_holding(validation, parent_validation, "median_hold_hours"),
        "validation_p90_hold_non_worse": _non_worse_holding(validation, parent_validation, "p90_hold_hours"),
        "design_interventions_at_least_8": int(design["vetoed_signal_count"]) >= 8,
        "validation_interventions_at_least_4": int(validation["vetoed_signal_count"]) >= 4,
        "annual_total_wins_at_least_3_of_5": int(evidence["annual_total_wins"]) >= 3,
        "annual_drawdown_non_worse_at_least_4_of_5": int(evidence["annual_drawdown_non_worse"]) >= 4,
        "top_year_positive_delta_contribution_at_most_60pct": D(evidence["top_year_positive_delta_contribution_pct"]) <= D("60"),
    }


def _median(values: list[D]) -> D | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / D("2")


def _sign_test(successes: int, failures: int) -> D | None:
    total = successes + failures
    if total == 0:
        return None
    return min(D("1"), D(sum(math.comb(total, k) for k in range(successes, total + 1))) / (D(2) ** total))


def build_predictive_episodes(
    bars: list[base.Bar], factor_points: list[dict[str, Any]], window: tuple[datetime, datetime]
) -> list[dict[str, Any]]:
    start, end = window
    close_times = [bar.close_time for bar in bars]
    episodes: list[dict[str, Any]] = []
    for point in factor_points:
        sign = int(point["factor_sign"])
        decision = datetime.fromisoformat(point["eligible_at"])
        if sign == 0 or decision < start or decision >= end:
            continue
        anchor_index = bisect_right(close_times, decision)
        terminal_index = anchor_index + 168
        if anchor_index >= len(bars) or terminal_index >= len(bars):
            continue
        anchor, terminal = bars[anchor_index], bars[terminal_index]
        if terminal.close_time > end:
            continue
        path = bars[anchor_index + 1 : terminal_index + 1]
        raw_return = terminal.close / anchor.close - D("1")
        signed_response = D(sign) * raw_return
        adverse = max((max(D("0"), -(D(sign) * (bar.close / anchor.close - D("1")))) for bar in path), default=D("0"))
        episodes.append({
            "report_date": point["report_date"],
            "eligible_at": point["eligible_at"],
            "anchor_at": anchor.close_time.isoformat(),
            "terminal_at": terminal.close_time.isoformat(),
            "factor_delta": point["factor_delta"],
            "factor_sign": sign,
            "raw_return_168h": str(raw_return),
            "signed_response_168h": str(signed_response),
            "sign_adjusted_mae_168h": str(adverse),
        })
    return episodes


def predictive_evidence(episodes: list[dict[str, Any]]) -> dict[str, Any]:
    signed = [D(item["signed_response_168h"]) for item in episodes]
    positive = [D(item["raw_return_168h"]) for item in episodes if item["factor_sign"] > 0]
    negative = [D(item["raw_return_168h"]) for item in episodes if item["factor_sign"] < 0]
    successes = sum(value > 0 for value in signed)
    failures = sum(value < 0 for value in signed)
    positive_signed = [value for value in signed if value > 0]
    positive_total = sum(positive_signed, D("0"))
    top_episode_share = max(positive_signed) / positive_total if positive_total > 0 else D("1")
    month_counts: dict[str, int] = {}
    for item in episodes:
        month = item["anchor_at"][:7]
        month_counts[month] = month_counts.get(month, 0) + 1
    maximum_month_share = D(max(month_counts.values())) / D(len(episodes)) if episodes else D("1")
    quartiles = [0, 0, 0, 0]
    for index in range(len(episodes)):
        quartiles[min(3, index * 4 // len(episodes))] += 1
    p_value = _sign_test(successes, failures)
    statistics = {
        "episode_count": len(episodes),
        "positive_factor_count": len(positive),
        "negative_factor_count": len(negative),
        "quartile_counts": quartiles,
        "anchor_month_count": len(month_counts),
        "maximum_month_share": str(maximum_month_share),
        "median_signed_response": None if _median(signed) is None else str(_median(signed)),
        "positive_factor_median_raw_return": None if _median(positive) is None else str(_median(positive)),
        "negative_factor_median_raw_return": None if _median(negative) is None else str(_median(negative)),
        "sign_test_successes": successes,
        "sign_test_failures": failures,
        "one_sided_sign_test_p_value": None if p_value is None else str(p_value),
        "maximum_episode_positive_signed_response_share": str(top_episode_share),
        "median_sign_adjusted_mae": None if not episodes else str(_median([D(item["sign_adjusted_mae_168h"]) for item in episodes])),
    }
    gates = {
        "minimum_26_episodes": len(episodes) >= 26,
        "minimum_8_positive_factors": len(positive) >= 8,
        "minimum_8_negative_factors": len(negative) >= 8,
        "quartile_breadth_at_least_4_each": all(count >= 4 for count in quartiles),
        "month_breadth_at_least_6": len(month_counts) >= 6,
        "maximum_month_share_at_most_25pct": maximum_month_share <= D("0.25"),
        "median_signed_response_positive": _median(signed) is not None and _median(signed) > 0,
        "positive_factor_median_raw_return_positive": _median(positive) is not None and _median(positive) > 0,
        "negative_factor_median_raw_return_negative": _median(negative) is not None and _median(negative) < 0,
        "one_sided_sign_test_at_most_0_10": p_value is not None and p_value <= D("0.10"),
        "maximum_episode_positive_share_at_most_25pct": top_episode_share <= D("0.25"),
    }
    return {"episodes": episodes, "statistics": statistics, "gates": gates}


def run_screen(manifest_path: Path, input_path: Path, output_path: Path) -> dict[str, Any]:
    if output_path.exists():
        raise ScreenReject("OUTPUT_SEAL_REJECT", "output already exists")
    manifest, manifest_raw = load_manifest(manifest_path)
    bindings = verify_bindings(manifest)
    bars = load_selection(input_path, manifest)
    rows, archive_evidence = load_historical_rows(manifest)
    factor_points, exclusions = build_factor_points(rows)
    baseline = parent_baseline(bars)
    economics = economic_evidence(bars, baseline, factor_points)
    economic_checks = economic_gates(economics, baseline)
    predictive = {
        "design": predictive_evidence(build_predictive_episodes(bars, factor_points, DESIGN)),
        "validation": predictive_evidence(build_predictive_episodes(bars, factor_points, VALIDATION)),
    }
    passed = all(economic_checks.values()) and all(
        all(window["gates"].values()) for window in predictive.values()
    )
    result = {
        "authorization": AUTHORIZATION,
        "archive_evidence": archive_evidence,
        "baseline": baseline,
        "bindings": bindings,
        "dataset": {"canonical_sha256": base.data_hash(bars), "rows": len(bars), "selection_cutoff": SELECTION_CUTOFF.isoformat()},
        "document_type": RESULT_TYPE,
        "economic_evidence": economics,
        "economic_gates": economic_checks,
        "experiment_id": manifest["experiment_id"],
        "factor_identity": FACTOR_IDENTITY,
        "factor_points": factor_points,
        "factor_point_exclusions": exclusions,
        "gate_set": GATE_SET,
        "manifest_sha256": sha256_bytes(manifest_raw),
        "oos_opened": False,
        "parent_strategy": PARENT_STRATEGY,
        "predictive_evidence": predictive,
        "recommended_next_action": "REGISTER_ONE_FORMAL_CANDIDATE_FOR_INDEPENDENT_OOS" if passed else "PERMANENTLY_CLOSE_CFTC_TFF_FACTOR_FAMILY_WITHOUT_TUNING",
        "runner_identity": RUNNER_IDENTITY,
        "runner_sha256": sha256_path(Path(__file__)),
        "schema_version": "1",
        "status": "DESIGN_VALIDATION_PASS_READY_FOR_ONE_CANDIDATE" if passed else "NO_CANDIDATE_CLOSE_CFTC_TFF_FACTOR_FAMILY",
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
    except (ScreenReject, base.ResearchReject, ValueError) as error:
        status = getattr(error, "status", "DATA_REJECT")
        detail = getattr(error, "detail", str(error))
        print(json.dumps({"detail": detail, "status": status}, ensure_ascii=False))
        return 2
    print(json.dumps({"output": str(args.output), "status": result["status"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
