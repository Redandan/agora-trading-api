#!/usr/bin/env python3
"""Frozen endpoint-only Binance USD-M OI confirmation screen for BTC DRA V1.

This is offline historical research. It has no network, scheduler, database,
canonical-state, OOS, candidate-promotion, or Trading authority.
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_binance_usdm_external_entry_admission_v1 as shared
from research_pipeline import binance_usdm_archive as archive


base = shared.base
capacity = shared.capacity
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SOURCE_DOCUMENT_TYPE = "BINANCE_USDM_ENDPOINT_OI_CORPUS_V2"
MANIFEST_DOCUMENT_TYPE = "DRA_BINANCE_USDM_ENDPOINT_OI_CONFIRMATION_V2_MANIFEST"
RESULT_DOCUMENT_TYPE = "DRA_BINANCE_USDM_ENDPOINT_OI_CONFIRMATION_V2_RESULT"
RUNNER_IDENTITY = "BTC_DRA_BINANCE_USDM_ENDPOINT_OI_CONFIRMATION_V2"
FAMILY_ID = "dra-binance-usdm-endpoint-oi-confirmation-v2"
PARENT_STRATEGY = "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH"
MATCHED_COMPARATOR = "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH_WITHOUT_OI_CONFIRMATION"
SELECTION_CUTOFF = "2025-01-01T00:00:00Z"
SELECTION_CUTOFF_NAIVE = datetime(2025, 1, 1)
RAW_FIRST_DAY = date(2020, 9, 1)
RAW_LAST_DAY = date(2024, 12, 31)
ENDPOINT_TIME = (23, 55)
DESIGN = (datetime(2020, 9, 2), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2021, 2025)
}
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")
MIN_ENDPOINT_PAIR_COVERAGE = D("0.95")
MIN_SIGNAL_AVAILABILITY = D("0.95")
MIN_VETOES_PER_WINDOW = 5
MIN_INTERVENTION_YEARS = 3
MIN_ANNUAL_BREADTH = 3
MAX_TOP1_POSITIVE_ANNUAL_CONTRIBUTION = D("0.60")

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class ResearchReject(RuntimeError):
    def __init__(self, status: str, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def canonical_bytes(value: Any) -> bytes:
    return shared.canonical_document_bytes(value)


def sha256_bytes(raw: bytes) -> str:
    return shared.sha256_bytes(raw)


def require_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise ResearchReject("CONTRACT_REJECT", f"{label} must be SHA-256")
    return value


def require_closed(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ResearchReject("CONTRACT_REJECT", f"{label} must be a closed object")
    return value


def write_create_once(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = canonical_bytes(value)
    try:
        with path.open("xb") as target:
            target.write(raw)
    except FileExistsError as error:
        raise ResearchReject("ARTIFACT_EXISTS", f"refusing to overwrite {path}") from error


def repository_relative(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPOSITORY_ROOT.resolve()).as_posix()
    except ValueError as error:
        raise ResearchReject(
            "CONTRACT_REJECT", f"path is outside repository: {path}"
        ) from error


@dataclass(frozen=True)
class EndpointAudit:
    day: date
    archive_name: str
    archive_sha256: str
    checksum_sidecar_sha256: str
    endpoint_status: str
    endpoint_row_sha256: str | None
    sum_open_interest: str | None

    def inventory_record(self) -> dict[str, Any]:
        return {
            "archive_name": self.archive_name,
            "archive_sha256": self.archive_sha256,
            "checksum_sidecar_sha256": self.checksum_sidecar_sha256,
            "day": self.day.isoformat(),
            "endpoint_row_sha256": self.endpoint_row_sha256,
            "endpoint_status": self.endpoint_status,
        }


def _read_endpoint_archive(
    archive_name: str,
    archive_bytes: bytes,
    checksum_bytes: bytes,
) -> EndpointAudit:
    """Verify a whole archive but select only the exact 23:55 OI endpoint."""

    active_limits = archive.ArchiveLimits()
    active_limits.validate()
    day = archive._archive_day(archive_name)
    archive_sha = archive.verify_official_checksum(
        archive_name, archive_bytes, checksum_bytes
    )
    opened, member = archive._safe_csv_member(
        archive_name, archive_bytes, active_limits
    )
    try:
        with opened.open(member, "r") as source:
            payload = source.read(active_limits.max_uncompressed_bytes + 1)
    finally:
        opened.close()
    if len(payload) > active_limits.max_uncompressed_bytes:
        raise ResearchReject("DATA_REJECT", "endpoint archive exceeds bounded read")
    lines = payload.splitlines()
    if not lines:
        raise ResearchReject("DATA_REJECT", "endpoint metrics CSV is empty")
    try:
        header = tuple(next(csv.reader([lines[0].decode("utf-8")], strict=True)))
    except Exception as error:
        raise ResearchReject("DATA_REJECT", "endpoint metrics header is invalid") from error
    if header != archive.EXPECTED_HEADER:
        raise ResearchReject("DATA_REJECT", "endpoint metrics header changed")
    if len(lines) - 1 > active_limits.max_rows:
        raise ResearchReject("DATA_REJECT", "endpoint metrics CSV exceeds row bound")

    target_time = datetime.combine(day, datetime.min.time()).replace(
        hour=ENDPOINT_TIME[0], minute=ENDPOINT_TIME[1]
    )
    selected: tuple[str | None, bytes] | None = None
    for row_number, raw_line in enumerate(lines[1:], start=2):
        if not raw_line:
            raise ResearchReject("DATA_REJECT", f"endpoint row {row_number} is blank")
        try:
            text = raw_line.decode("utf-8")
            values = next(csv.reader([text], strict=True))
        except (UnicodeDecodeError, csv.Error, StopIteration) as error:
            raise ResearchReject(
                "DATA_REJECT", f"endpoint row {row_number} is not strict UTF-8 CSV"
            ) from error
        if len(values) != len(archive.EXPECTED_HEADER):
            raise ResearchReject(
                "DATA_REJECT", f"endpoint row {row_number} has the wrong column count"
            )
        try:
            timestamp = datetime.strptime(values[0], "%Y-%m-%d %H:%M:%S")
        except ValueError as error:
            raise ResearchReject(
                "DATA_REJECT", f"endpoint row {row_number} has an invalid UTC timestamp"
            ) from error
        if values[1] != archive.SYMBOL:
            raise ResearchReject(
                "DATA_REJECT", f"endpoint row {row_number} is not BTCUSDT"
            )
        if timestamp != target_time:
            continue
        endpoint_oi: str | None = None
        try:
            endpoint_oi = archive._decimal_text(
                values[2], "sum_open_interest", allow_zero=False
            )
        except archive.ArchiveReject:
            endpoint_oi = None
        if selected is not None:
            if selected[1] != raw_line:
                raise ResearchReject(
                    "DATA_REJECT", "endpoint archive has a conflicting duplicate timestamp"
                )
            continue
        selected = (endpoint_oi, raw_line)
    if selected is None:
        return EndpointAudit(
            day=day,
            archive_name=archive_name,
            archive_sha256=archive_sha,
            checksum_sidecar_sha256=sha256_bytes(checksum_bytes),
            endpoint_status="MISSING_23_55_PASS_THROUGH",
            endpoint_row_sha256=None,
            sum_open_interest=None,
        )
    oi, exact_row = selected
    if oi is None:
        return EndpointAudit(
            day=day,
            archive_name=archive_name,
            archive_sha256=archive_sha,
            checksum_sidecar_sha256=sha256_bytes(checksum_bytes),
            endpoint_status="UNUSABLE_23_55_PASS_THROUGH",
            endpoint_row_sha256=sha256_bytes(exact_row),
            sum_open_interest=None,
        )
    return EndpointAudit(
        day=day,
        archive_name=archive_name,
        archive_sha256=archive_sha,
        checksum_sidecar_sha256=sha256_bytes(checksum_bytes),
        endpoint_status="AVAILABLE_23_55",
        endpoint_row_sha256=sha256_bytes(exact_row),
        sum_open_interest=oi,
    )


def _day_range(first: date, last: date) -> list[date]:
    return [
        first + timedelta(days=offset)
        for offset in range((last - first).days + 1)
    ]


def build_source_corpus(raw_root: Path, prior_path: Path, prior_sha256: str) -> dict[str, Any]:
    prior_raw = prior_path.read_bytes()
    if sha256_bytes(prior_raw) != require_sha(prior_sha256, "prior_sha256"):
        raise ResearchReject("PRIOR_REJECT", "frozen prior hash mismatch")
    prior = json.loads(prior_raw.decode("utf-8"))
    if (
        prior.get("document_type")
        != "DRA_BINANCE_USDM_ENDPOINT_OI_CONFIRMATION_V2_PRIMARY_PRIOR"
        or prior.get("family_id") != FAMILY_ID
        or prior.get("status")
        != "PREREGISTERED_SOURCE_FIRST_NO_FACTOR_OR_OUTCOME_ACCESS"
    ):
        raise ResearchReject("PRIOR_REJECT", "frozen prior identity mismatch")

    inventory: list[dict[str, Any]] = []
    endpoints: dict[date, EndpointAudit] = {}
    for day in _day_range(RAW_FIRST_DAY, RAW_LAST_DAY):
        name = f"BTCUSDT-metrics-{day.isoformat()}.zip"
        archive_path = raw_root / name
        checksum_path = raw_root / f"{name}.CHECKSUM"
        if not archive_path.is_file() or not checksum_path.is_file():
            raise ResearchReject("DATA_REJECT", f"missing exact archive pair for {day}")
        try:
            audit = _read_endpoint_archive(
                name, archive_path.read_bytes(), checksum_path.read_bytes()
            )
        except (ResearchReject, archive.ArchiveReject) as error:
            raise ResearchReject(
                getattr(error, "status", "DATA_REJECT"),
                f"{day.isoformat()}: {getattr(error, 'detail', str(error))}",
            ) from error
        inventory.append(audit.inventory_record())
        endpoints[day] = audit

    expected_count = (RAW_LAST_DAY - RAW_FIRST_DAY).days + 1
    if len(inventory) != expected_count or expected_count != 1583:
        raise ResearchReject("DATA_REJECT", "archive inventory count changed")

    pairs: list[dict[str, str]] = []
    for day in _day_range(RAW_FIRST_DAY + timedelta(days=1), RAW_LAST_DAY):
        current = endpoints[day]
        previous = endpoints[day - timedelta(days=1)]
        if current.sum_open_interest is None or previous.sum_open_interest is None:
            continue
        current_oi = D(current.sum_open_interest)
        previous_oi = D(previous.sum_open_interest)
        oi_return = (current_oi / previous_oi) - D("1")
        pairs.append(
            {
                "available_at": datetime.combine(
                    day + timedelta(days=1), datetime.min.time()
                ).isoformat() + "Z",
                "current_endpoint_row_sha256": str(current.endpoint_row_sha256),
                "day": day.isoformat(),
                "oi_contract_return": str(oi_return),
                "previous_endpoint_row_sha256": str(previous.endpoint_row_sha256),
            }
        )

    available = sum(
        item["endpoint_status"] == "AVAILABLE_23_55" for item in inventory
    )
    possible_pairs = expected_count - 1
    pair_coverage = D(len(pairs)) / D(possible_pairs)
    status = (
        "SOURCE_READY_ENDPOINT_ONLY_V2"
        if pair_coverage >= MIN_ENDPOINT_PAIR_COVERAGE
        else "DATA_REJECT_ENDPOINT_PAIR_COVERAGE"
    )
    evidence = {
        "archive_inventory_sha256": sha256_bytes(canonical_bytes(inventory)),
        "archive_pair_count": expected_count,
        "endpoint_available_count": available,
        "endpoint_missing_count": expected_count - available,
        "endpoint_pair_count": len(pairs),
        "endpoint_pair_coverage": str(pair_coverage.quantize(D("0.00000001"))),
        "first_day": RAW_FIRST_DAY.isoformat(),
        "last_day": RAW_LAST_DAY.isoformat(),
        "normalized_pairs_sha256": sha256_bytes(canonical_bytes(pairs)),
    }
    return {
        "authorization": AUTHORIZATION,
        "document_type": SOURCE_DOCUMENT_TYPE,
        "evidence": evidence,
        "family_id": FAMILY_ID,
        "pairs": pairs,
        "prior": {
            "path": repository_relative(prior_path),
            "sha256": prior_sha256,
        },
        "schema_version": "1",
        "status": status,
    }


def endpoint_days(source: dict[str, Any]) -> list[shared.ExternalDay]:
    if source.get("status") != "SOURCE_READY_ENDPOINT_ONLY_V2":
        raise ResearchReject("DATA_REJECT", "endpoint source did not pass its frozen gate")
    result: list[shared.ExternalDay] = []
    for item in source["pairs"]:
        day = date.fromisoformat(item["day"])
        available_at = datetime.fromisoformat(item["available_at"].removesuffix("Z"))
        result.append(
            shared.ExternalDay(
                day=day,
                available_at=available_at,
                price_return=D("0"),
                oi_value_return=D(item["oi_contract_return"]),
                top_trader_long_short_ratio=None,
                global_long_short_ratio=None,
                taker_long_short_ratio=None,
                source_normalized_sha256=item["current_endpoint_row_sha256"],
            )
        )
    return result


class EndpointOiConfirmationEngine(shared.ExternalEntryAdmissionEngine):
    """Reuse the accepted DRA economic engine with a frozen V2 predicate."""

    def __init__(self, external_days: Iterable[shared.ExternalDay]) -> None:
        capacity.EqualCapitalCapacityEngine.__init__(
            self,
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.family_key = FAMILY_ID
        self.thresholds = {"oi_contract_return_at_or_above": "0"}
        self.external_days = {item.day: item for item in external_days}
        self.parent_signal_count = 0
        self.feature_available_signal_count = 0
        self.feature_unavailable_signal_count = 0
        self.confirmed_signal_count = 0
        self.vetoed_signal_count = 0
        self.fees_paid = base.ZERO
        self.adverse_slippage_cost = base.ZERO

    def _signal(self, bar: base.Bar) -> bool:
        if not capacity.EqualCapitalCapacityEngine._signal(self, bar):
            return False
        self.parent_signal_count += 1
        observation = self.external_days.get(bar.open_time.date())
        if observation is None:
            self.feature_unavailable_signal_count += 1
            return True
        shared.validate_decision_observation(observation, bar)
        self.feature_available_signal_count += 1
        if observation.oi_value_return >= D("0"):
            self.confirmed_signal_count += 1
            return True
        self.vetoed_signal_count += 1
        return False

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = capacity.EqualCapitalCapacityEngine.result(self, final_bar, start, end)
        result.update(
            {
                "admission_family_key": FAMILY_ID,
                "admission_feature_family": "endpoint-open-interest-nondecreasing-confirmation",
                "adverse_slippage_cost_usdt": str(base.money(self.adverse_slippage_cost)),
                "adverse_slippage_rate": str(base.SLIPPAGE),
                "fee_rate": str(base.FEE),
                "fees_paid_usdt": str(base.money(self.fees_paid)),
                "holding": {
                    "median_hold_hours": result["median_hold_hours"],
                    "median_open_age_hours": result["median_open_age_hours"],
                    "p90_hold_hours": result["p90_hold_hours"],
                    "p90_open_age_hours": result["p90_open_age_hours"],
                },
                "interventions": {
                    "confirmed_signal_count": self.confirmed_signal_count,
                    "feature_available_signal_count": self.feature_available_signal_count,
                    "feature_unavailable_pass_through_signal_count": self.feature_unavailable_signal_count,
                    "parent_signal_count": self.parent_signal_count,
                    "reconciles": self.parent_signal_count
                    == self.confirmed_signal_count
                    + self.vetoed_signal_count
                    + self.feature_unavailable_signal_count,
                    "vetoed_signal_count": self.vetoed_signal_count,
                },
                "runner_identity": RUNNER_IDENTITY,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    external_days_value: Iterable[shared.ExternalDay],
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ResearchReject("DATA_REJECT", "candidate window has no bars")
    engine = EndpointOiConfirmationEngine(external_days_value)
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def _load_json(path: Path, label: str) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ResearchReject("DATA_REJECT", f"{label} must be strict UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ResearchReject("DATA_REJECT", f"{label} must be a JSON object")
    return value, raw


def validate_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    require_closed(
        manifest,
        {
            "authorization",
            "bindings",
            "dataset",
            "document_type",
            "economics",
            "experiment_id",
            "external_corpus",
            "feature",
            "gate_set",
            "oos_access",
            "parent_strategy",
            "schema_version",
            "selection_cutoff",
            "windows",
        },
        "manifest",
    )
    if (
        manifest["schema_version"] != "1"
        or manifest["document_type"] != MANIFEST_DOCUMENT_TYPE
        or manifest["authorization"] != AUTHORIZATION
        or manifest["parent_strategy"] != PARENT_STRATEGY
        or manifest["selection_cutoff"] != SELECTION_CUTOFF
        or manifest["oos_access"] != "DENY"
    ):
        raise ResearchReject("CONTRACT_REJECT", "manifest identity or boundary changed")
    if not isinstance(manifest["experiment_id"], str) or not manifest["experiment_id"]:
        raise ResearchReject("CONTRACT_REJECT", "experiment_id is required")
    dataset = require_closed(manifest["dataset"], {"canonical_sha256", "rows"}, "dataset")
    require_sha(dataset["canonical_sha256"], "dataset.canonical_sha256")
    if dataset["rows"] != 52608:
        raise ResearchReject("CONTRACT_REJECT", "selection rows are frozen")
    external = require_closed(
        manifest["external_corpus"],
        {
            "archive_inventory_sha256",
            "archive_pair_count",
            "endpoint_available_count",
            "endpoint_missing_count",
            "endpoint_pair_count",
            "endpoint_pair_coverage",
            "first_day",
            "last_day",
            "normalized_pairs_sha256",
            "path",
            "sha256",
        },
        "external_corpus",
    )
    require_sha(external["archive_inventory_sha256"], "archive inventory")
    require_sha(external["normalized_pairs_sha256"], "normalized pairs")
    require_sha(external["sha256"], "external corpus")
    if (
        external["archive_pair_count"] != 1583
        or external["first_day"] != RAW_FIRST_DAY.isoformat()
        or external["last_day"] != RAW_LAST_DAY.isoformat()
        or D(str(external["endpoint_pair_coverage"])) < MIN_ENDPOINT_PAIR_COVERAGE
    ):
        raise ResearchReject("CONTRACT_REJECT", "endpoint source gate is not frozen-ready")
    feature = require_closed(
        manifest["feature"],
        {
            "admit_when",
            "decision_endpoint",
            "family_id",
            "field",
            "missing_endpoint_policy",
            "threshold",
            "variant_count",
            "veto_when",
        },
        "feature",
    )
    if feature != {
        "admit_when": "OI_CONTRACT_RETURN_GREATER_THAN_OR_EQUAL_TO_ZERO",
        "decision_endpoint": "COMPLETE_UTC_DAY_23_55_AVAILABLE_AT_00_00",
        "family_id": FAMILY_ID,
        "field": "sum_open_interest",
        "missing_endpoint_policy": "PARENT_PASS_THROUGH_COUNT_UNAVAILABLE",
        "threshold": "0",
        "variant_count": 1,
        "veto_when": "OI_CONTRACT_RETURN_STRICTLY_LESS_THAN_ZERO",
    }:
        raise ResearchReject("CONTRACT_REJECT", "feature contract changed")
    economics = require_closed(
        manifest["economics"],
        {"fee_rate", "initial_equity_usdt", "slippage_rate", "slot_capacity_usdt"},
        "economics",
    )
    if economics != {
        "fee_rate": "0.0010",
        "initial_equity_usdt": "250",
        "slippage_rate": "0.0005",
        "slot_capacity_usdt": "240",
    }:
        raise ResearchReject("CONTRACT_REJECT", "economic accounting changed")
    windows = require_closed(
        manifest["windows"], {"annual_folds", "design", "validation"}, "windows"
    )
    if windows != {
        "annual_folds": ["2021", "2022", "2023", "2024"],
        "design": ["2020-09-02T00:00:00Z", "2023-01-01T00:00:00Z"],
        "validation": ["2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z"],
    }:
        raise ResearchReject("CONTRACT_REJECT", "research windows changed")
    if manifest["gate_set"] != {
        "all_gates_required": True,
        "annual_breadth_at_least": 3,
        "endpoint_pair_coverage_at_least": "0.95",
        "intervention_years_at_least": 3,
        "signal_feature_availability_at_least": "0.95",
        "top1_positive_annual_contribution_at_most": "0.60",
        "vetoes_per_design_and_validation_at_least": 5,
    }:
        raise ResearchReject("CONTRACT_REJECT", "gate set changed")
    bindings = manifest["bindings"]
    if not isinstance(bindings, list) or len(bindings) != 3:
        raise ResearchReject("CONTRACT_REJECT", "exactly three frozen bindings are required")
    for binding in bindings:
        require_closed(binding, {"path", "role", "sha256"}, "binding")
        require_sha(binding["sha256"], "binding.sha256")
    return manifest


def verify_bindings(manifest: dict[str, Any]) -> None:
    expected_roles = {"FROZEN_HYPOTHESIS", "FROZEN_PRIMARY_PRIOR", "FROZEN_RUNNER"}
    observed_roles: set[str] = set()
    for binding in manifest["bindings"]:
        candidate = REPOSITORY_ROOT.joinpath(*binding["path"].split("/"))
        resolved = candidate.resolve(strict=True)
        try:
            resolved.relative_to(REPOSITORY_ROOT)
        except ValueError as error:
            raise ResearchReject("CONTRACT_REJECT", "binding escapes repository") from error
        if not resolved.is_file() or resolved.is_symlink():
            raise ResearchReject("CONTRACT_REJECT", "binding must be a regular file")
        if sha256_bytes(resolved.read_bytes()) != binding["sha256"]:
            raise ResearchReject("CONTRACT_REJECT", f"binding hash changed: {binding['path']}")
        observed_roles.add(binding["role"])
    if observed_roles != expected_roles:
        raise ResearchReject("CONTRACT_REJECT", "binding roles changed")


def _parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
    def run(window: tuple[datetime, datetime]) -> dict[str, Any]:
        return capacity.simulate_capacity(
            bars,
            window,
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )

    return {
        "design": run(DESIGN),
        "folds": {name: run(window) for name, window in FOLDS.items()},
        "validation": run(VALIDATION),
    }


def _candidate_ledgers(
    bars: list[base.Bar], external_days_value: list[shared.ExternalDay]
) -> dict[str, Any]:
    return {
        "design": simulate_candidate(bars, DESIGN, external_days_value),
        "folds": {
            name: simulate_candidate(bars, window, external_days_value)
            for name, window in FOLDS.items()
        },
        "validation": simulate_candidate(bars, VALIDATION, external_days_value),
    }


def _optional_non_worse(candidate: Any, parent: Any) -> bool:
    if candidate is None or parent is None:
        return candidate == parent
    return D(str(candidate)) <= D(str(parent))


def _terminal_summary(ledger: dict[str, Any]) -> dict[str, Any]:
    inventory = ledger["terminal_inventory"]
    return {
        "oldest_age_hours": max((D(str(item["age_hours"])) for item in inventory), default=D("0")),
        "open_cost_usdt": sum((D(str(item["cost_usdt"])) for item in inventory), D("0")),
        "open_lot_count": len(inventory),
        "unrealized_usdt": D(str(ledger["unrealized_usdt"])),
    }


def _window_gates(
    parent: dict[str, Any], candidate: dict[str, Any], *, label: str
) -> dict[str, bool]:
    delta = capacity.equal_capital_deltas(parent, candidate)["deltas"]
    p_terminal = _terminal_summary(parent)
    c_terminal = _terminal_summary(candidate)
    interventions = candidate["interventions"]
    available = D(interventions["feature_available_signal_count"])
    parent_signals = D(interventions["parent_signal_count"])
    availability = D("1") if parent_signals == 0 else available / parent_signals
    return {
        f"{label}_adverse_slippage_visible": "adverse_slippage_cost_usdt" in candidate,
        f"{label}_drawdown_non_worse": D(delta["max_drawdown_pct_delta"]) <= 0,
        f"{label}_feature_availability": availability >= MIN_SIGNAL_AVAILABILITY,
        f"{label}_median_holding_non_worse": _optional_non_worse(
            candidate["median_hold_hours"], parent["median_hold_hours"]
        ),
        f"{label}_p90_holding_non_worse": _optional_non_worse(
            candidate["p90_hold_hours"], parent["p90_hold_hours"]
        ),
        f"{label}_realized_pnl_nonnegative_delta": D(delta["realized_usdt_delta"]) >= 0,
        f"{label}_terminal_oldest_age_non_worse": c_terminal["oldest_age_hours"]
        <= p_terminal["oldest_age_hours"],
        f"{label}_terminal_open_cost_non_worse": c_terminal["open_cost_usdt"]
        <= p_terminal["open_cost_usdt"],
        f"{label}_terminal_open_lots_non_worse": c_terminal["open_lot_count"]
        <= p_terminal["open_lot_count"],
        f"{label}_terminal_unrealized_non_worse": c_terminal["unrealized_usdt"]
        >= p_terminal["unrealized_usdt"],
        f"{label}_total_pnl_strictly_positive_delta": D(delta["total_pnl_usdt_delta"]) > 0,
        f"{label}_veto_support": interventions["vetoed_signal_count"]
        >= MIN_VETOES_PER_WINDOW,
    }


def _ratio(value: D, total: D) -> str | None:
    if total <= 0:
        return None
    return str((value / total).quantize(D("0.00000001"), rounding=ROUND_HALF_UP))


def evaluate_gates(
    source: dict[str, Any], parent: dict[str, Any], candidate: dict[str, Any]
) -> tuple[dict[str, bool], dict[str, Any]]:
    gates: dict[str, bool] = {
        "source_endpoint_pair_coverage": D(source["evidence"]["endpoint_pair_coverage"])
        >= MIN_ENDPOINT_PAIR_COVERAGE
    }
    gates.update(_window_gates(parent["design"], candidate["design"], label="design"))
    gates.update(
        _window_gates(parent["validation"], candidate["validation"], label="validation")
    )

    annual: dict[str, Any] = {}
    positive_deltas: list[D] = []
    intervention_years = 0
    pnl_wins = 0
    drawdown_non_worse = 0
    median_non_worse = 0
    p90_non_worse = 0
    for year in FOLDS:
        parent_year = parent["folds"][year]
        candidate_year = candidate["folds"][year]
        deltas = capacity.equal_capital_deltas(parent_year, candidate_year)["deltas"]
        total_delta = D(deltas["total_pnl_usdt_delta"])
        if total_delta > 0:
            pnl_wins += 1
            positive_deltas.append(total_delta)
        dd_ok = D(deltas["max_drawdown_pct_delta"]) <= 0
        med_ok = _optional_non_worse(
            candidate_year["median_hold_hours"], parent_year["median_hold_hours"]
        )
        p90_ok = _optional_non_worse(
            candidate_year["p90_hold_hours"], parent_year["p90_hold_hours"]
        )
        drawdown_non_worse += int(dd_ok)
        median_non_worse += int(med_ok)
        p90_non_worse += int(p90_ok)
        intervened = candidate_year["interventions"]["vetoed_signal_count"] > 0
        intervention_years += int(intervened)
        annual[year] = {
            "drawdown_non_worse": dd_ok,
            "intervened": intervened,
            "median_holding_non_worse": med_ok,
            "p90_holding_non_worse": p90_ok,
            "total_pnl_delta_usdt": deltas["total_pnl_usdt_delta"],
        }
    positive_deltas.sort(reverse=True)
    positive_total = sum(positive_deltas, D("0"))
    top1_share = _ratio(sum(positive_deltas[:1], D("0")), positive_total)
    gates.update(
        {
            "annual_drawdown_non_worse_breadth": drawdown_non_worse >= MIN_ANNUAL_BREADTH,
            "annual_intervention_breadth": intervention_years >= MIN_INTERVENTION_YEARS,
            "annual_median_holding_non_worse_breadth": median_non_worse >= MIN_ANNUAL_BREADTH,
            "annual_p90_holding_non_worse_breadth": p90_non_worse >= MIN_ANNUAL_BREADTH,
            "annual_total_pnl_positive_delta_breadth": pnl_wins >= MIN_ANNUAL_BREADTH,
            "positive_annual_delta_exists": positive_total > 0,
            "top1_positive_annual_contribution": top1_share is not None
            and D(top1_share) <= MAX_TOP1_POSITIVE_ANNUAL_CONTRIBUTION,
        }
    )
    diagnostics = {
        "annual": annual,
        "annual_breadth": {
            "drawdown_non_worse_years": drawdown_non_worse,
            "intervention_years": intervention_years,
            "median_holding_non_worse_years": median_non_worse,
            "p90_holding_non_worse_years": p90_non_worse,
            "positive_total_pnl_delta_years": pnl_wins,
            "year_count": len(FOLDS),
        },
        "concentration": {
            "positive_annual_delta_total_usdt": str(base.money(positive_total)),
            "top1_positive_annual_contribution_share": top1_share,
        },
    }
    return gates, diagnostics


def run_economic(
    manifest: dict[str, Any], source: dict[str, Any], bars: list[base.Bar]
) -> dict[str, Any]:
    validate_manifest(manifest)
    verify_bindings(manifest)
    if len(bars) != 52608 or base.data_hash(bars) != manifest["dataset"]["canonical_sha256"]:
        raise ResearchReject("DATA_REJECT", "H1 selection corpus hash or rows changed")
    if not bars or bars[-1].close_time > SELECTION_CUTOFF_NAIVE:
        raise ResearchReject("OOS_REJECT", "H1 selection crosses the pre-2025 cutoff")
    if source.get("document_type") != SOURCE_DOCUMENT_TYPE or source.get("family_id") != FAMILY_ID:
        raise ResearchReject("DATA_REJECT", "endpoint source identity mismatch")
    if source.get("status") != "SOURCE_READY_ENDPOINT_ONLY_V2":
        raise ResearchReject("DATA_REJECT", "endpoint source is not ready")
    if source["evidence"] != {
        key: manifest["external_corpus"][key]
        for key in (
            "archive_inventory_sha256",
            "archive_pair_count",
            "endpoint_available_count",
            "endpoint_missing_count",
            "endpoint_pair_count",
            "endpoint_pair_coverage",
            "first_day",
            "last_day",
            "normalized_pairs_sha256",
        )
    }:
        raise ResearchReject("DATA_REJECT", "endpoint source evidence does not match manifest")

    external_days_value = endpoint_days(source)
    parent = _parent_baseline(bars)
    candidate = _candidate_ledgers(bars, external_days_value)
    gates, diagnostics = evaluate_gates(source, parent, candidate)
    all_pass = all(gates.values())
    return {
        "authorization": AUTHORIZATION,
        "candidate_created": all_pass,
        "candidate_status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if all_pass
            else "NO_CANDIDATE_CLOSE_ENDPOINT_OI_CONFIRMATION_V2"
        ),
        "candidate": candidate,
        "diagnostics": diagnostics,
        "document_type": RESULT_DOCUMENT_TYPE,
        "experiment_id": manifest["experiment_id"],
        "family_id": FAMILY_ID,
        "gates": gates,
        "manifest_sha256": sha256_bytes(canonical_bytes(manifest)),
        "matched_comparator_id": MATCHED_COMPARATOR,
        "oos_opened": False,
        "parent": parent,
        "runner_identity": RUNNER_IDENTITY,
        "schema_version": "1",
        "source_evidence": source["evidence"],
        "status": (
            "HISTORICAL_CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if all_pass
            else "NO_CANDIDATE_CLOSE_ENDPOINT_OI_CONFIRMATION_V2"
        ),
    }


def _source_command(args: argparse.Namespace) -> None:
    value = build_source_corpus(args.raw_root, args.prior, args.prior_sha256)
    write_create_once(args.output, value)
    print(
        json.dumps(
            {
                "output": str(args.output),
                "sha256": sha256_bytes(args.output.read_bytes()),
                "status": value["status"],
                **value["evidence"],
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )


def _run_command(args: argparse.Namespace) -> None:
    manifest, manifest_raw = _load_json(args.manifest, "manifest")
    validate_manifest(manifest)
    source, source_raw = _load_json(args.source, "endpoint source")
    if sha256_bytes(source_raw) != manifest["external_corpus"]["sha256"]:
        raise ResearchReject("DATA_REJECT", "endpoint source file hash mismatch")
    if repository_relative(args.source) != manifest["external_corpus"]["path"]:
        raise ResearchReject("DATA_REJECT", "endpoint source path mismatch")
    bars = base.parse_rows(args.data.read_text(encoding="utf-8"))
    result = run_economic(manifest, source, bars)
    result["manifest_sha256"] = sha256_bytes(manifest_raw)
    write_create_once(args.output, result)
    print(
        json.dumps(
            {
                "failed_gate_count": sum(not passed for passed in result["gates"].values()),
                "output": str(args.output),
                "sha256": sha256_bytes(args.output.read_bytes()),
                "status": result["status"],
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("source")
    source.add_argument("--raw-root", type=Path, required=True)
    source.add_argument("--prior", type=Path, required=True)
    source.add_argument("--prior-sha256", required=True)
    source.add_argument("--output", type=Path, required=True)
    source.set_defaults(handler=_source_command)
    run = commands.add_parser("run")
    run.add_argument("--manifest", type=Path, required=True)
    run.add_argument("--source", type=Path, required=True)
    run.add_argument("--data", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    run.set_defaults(handler=_run_command)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        args.handler(args)
    except (ResearchReject, archive.ArchiveReject, shared.ScreenReject, base.ResearchReject) as error:
        status = getattr(error, "status", "EVIDENCE_INSUFFICIENT")
        detail = getattr(error, "detail", str(error))
        print(f"{status}:{detail}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
