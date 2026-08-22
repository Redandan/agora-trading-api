#!/usr/bin/env python3
"""Declarative BTC_DRA_V1 admission runner for three Binance USD-M joint states.

This module is an offline research capability.  It has no scheduler, network,
database, canonical-state, candidate, OOS, or Trading integration.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

import btc_dra_equal_capital_capacity_v1 as capacity
from research_pipeline import binance_usdm_archive as archive


base = capacity.base
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DOCUMENT_TYPE = "BINANCE_USDM_EXTERNAL_DRA_MANIFEST_V1"
RESULT_TYPE = "BINANCE_USDM_EXTERNAL_DRA_SCREEN_V1"
RUNNER_IDENTITY = "BTC_DRA_BINANCE_USDM_EXTERNAL_ENTRY_ADMISSION_V1"
PARENT_STRATEGY = "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH"
MATCHED_COMPARATOR = "BTC_DRA_V1_BASELINE_250_USDT_RESEARCH_WITHOUT_ADMISSION_FILTER"
GATE_SET = "DRA_BINANCE_USDM_EXTERNAL_ENTRY_ADMISSION_GATES_V1"
SELECTION_CUTOFF = "2025-01-01T00:00:00Z"
SELECTION_CUTOFF_NAIVE = datetime(2025, 1, 1)
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
ROLE_ORDER = {"lower_neighbor": 0, "primary": 1, "upper_neighbor": 2}
FEATURES = {
    "dra-binance-usdm-deleveraging-flush-entry-admission": {
        "feature_family": "joint-price-open-interest-deleveraging-flush",
        "threshold_keys": {
            "oi_value_return_at_or_below",
            "price_return_at_or_below",
        },
    },
    "dra-binance-usdm-positioning-divergence-entry-admission": {
        "feature_family": "top-trader-versus-global-positioning-divergence",
        "threshold_keys": {"absolute_positioning_gap_at_or_above"},
    },
    "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission": {
        "feature_family": "joint-perpetual-taker-flow-open-interest-confirmation",
        "threshold_keys": {
            "oi_value_return_at_or_above",
            "taker_long_short_ratio_at_or_above",
        },
    },
}
REQUIRED_ECONOMIC_OUTPUTS = (
    "adverse_slippage_cost_usdt",
    "annual_breadth",
    "concentration",
    "fees_paid_usdt",
    "holding",
    "inventory_path",
    "interventions",
    "max_drawdown_pct",
    "realized_usdt",
    "total_pnl_usdt",
    "unrealized_usdt",
)

_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DECIMAL_TEXT = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")


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


def _closed(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be a closed object")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _ID.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid")
    return value


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be SHA-256")
    return value


def _decimal(value: Any, label: str) -> D:
    if not isinstance(value, str) or _DECIMAL_TEXT.fullmatch(value) is None:
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be exact decimal text")
    try:
        result = D(str(value))
    except InvalidOperation as error:
        raise ScreenReject("CONTRACT_REJECT", f"{label} is invalid") from error
    if not result.is_finite():
        raise ScreenReject("CONTRACT_REJECT", f"{label} must be finite")
    return result


def validate_manifest(value: Any) -> dict[str, Any]:
    manifest = _closed(
        value,
        {
            "authorization",
            "dataset",
            "document_type",
            "economics",
            "experiment_id",
            "external_dataset",
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
    if (
        manifest["parent_strategy"] != PARENT_STRATEGY
        or manifest["selection_cutoff"] != SELECTION_CUTOFF
        or manifest["oos_access"] != "DENY"
        or manifest["gate_set"] != GATE_SET
    ):
        raise ScreenReject("CONTRACT_REJECT", "parent, cutoff, OOS, and gates are frozen")

    dataset = _closed(manifest["dataset"], {"canonical_sha256", "rows"}, "dataset")
    _sha(dataset["canonical_sha256"], "dataset.canonical_sha256")
    if isinstance(dataset["rows"], bool) or not isinstance(dataset["rows"], int) or dataset["rows"] <= 0:
        raise ScreenReject("CONTRACT_REJECT", "dataset.rows must be positive")

    external = _closed(
        manifest["external_dataset"],
        {
            "archive_inventory_sha256",
            "complete_utc_days",
            "dataset",
            "instrument",
            "latest_permitted_observation",
            "normalized_payload_sha256",
        },
        "external_dataset",
    )
    _sha(external["archive_inventory_sha256"], "external archive inventory")
    _sha(external["normalized_payload_sha256"], "external normalized payload")
    if (
        external["dataset"] != "BINANCE_USDM_DAILY_METRICS"
        or external["instrument"] != archive.SYMBOL
        or external["latest_permitted_observation"] != "2024-12-31T23:59:59Z"
        or isinstance(external["complete_utc_days"], bool)
        or not isinstance(external["complete_utc_days"], int)
        or external["complete_utc_days"] <= 0
    ):
        raise ScreenReject("CONTRACT_REJECT", "external dataset boundary is invalid")

    economics = _closed(
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
        raise ScreenReject("CONTRACT_REJECT", "economics differ from the equal-capital parent")

    prior = _closed(manifest["prior_evidence"], {"disposition", "path", "sha256"}, "prior_evidence")
    if (
        prior["disposition"]
        != "SOURCE_READY_FOR_ONE_SHARED_OFFLINE_ADAPTER_AND_DECLARATIVE_DRA_RUNNER_NO_HYPOTHESIS_OR_CANDIDATE_YET"
        or prior["path"]
        != "research_pipeline/examples/binance-usdm-derivatives-archive-source-capability.v1.json"
    ):
        raise ScreenReject("CONTRACT_REJECT", "prior evidence does not bind the source audit")
    _sha(prior["sha256"], "prior_evidence.sha256")

    feature = _closed(manifest["feature"], {"decision_time", "family_key", "feature_family"}, "feature")
    family = FEATURES.get(feature["family_key"])
    if (
        family is None
        or feature["feature_family"] != family["feature_family"]
        or feature["decision_time"]
        != "LATEST_COMPLETE_UTC_SOURCE_DAY_BEFORE_NEXT_DRA_FILL"
    ):
        raise ScreenReject("CONTRACT_REJECT", "feature must be one supported joint family")

    variants = manifest["variants"]
    if not isinstance(variants, list) or not 1 <= len(variants) <= 3:
        raise ScreenReject("CONTRACT_REJECT", "manifest must freeze one to three variants")
    roles: set[str] = set()
    ids: set[str] = set()
    threshold_fingerprints: set[bytes] = set()
    for index, raw_variant in enumerate(variants):
        variant = _closed(raw_variant, {"role", "thresholds", "variant_id"}, f"variants[{index}]")
        role = variant["role"]
        if role not in ROLE_ORDER:
            raise ScreenReject("CONTRACT_REJECT", "variant role is unsupported")
        roles.add(role)
        ids.add(_identifier(variant["variant_id"], f"variants[{index}].variant_id"))
        thresholds = _closed(
            variant["thresholds"],
            family["threshold_keys"],
            f"variants[{index}].thresholds",
        )
        parsed = {key: _decimal(raw, key) for key, raw in thresholds.items()}
        if feature["family_key"] == "dra-binance-usdm-deleveraging-flush-entry-admission":
            if any(number >= 0 for number in parsed.values()):
                raise ScreenReject("CONTRACT_REJECT", "flush thresholds must both be negative")
        elif feature["family_key"] == "dra-binance-usdm-positioning-divergence-entry-admission":
            if parsed["absolute_positioning_gap_at_or_above"] <= 0:
                raise ScreenReject("CONTRACT_REJECT", "positioning divergence threshold must be positive")
        elif (
            parsed["taker_long_short_ratio_at_or_above"] <= 0
            or parsed["oi_value_return_at_or_above"] < 0
        ):
            raise ScreenReject("CONTRACT_REJECT", "taker/OI thresholds must preserve non-contracting OI")
        threshold_fingerprints.add(canonical_document_bytes(thresholds))
    if (
        len(roles) != len(variants)
        or len(ids) != len(variants)
        or len(threshold_fingerprints) != len(variants)
        or "primary" not in roles
    ):
        raise ScreenReject("CONTRACT_REJECT", "variant roles, ids, and thresholds must be distinct with one primary")
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


@dataclass(frozen=True)
class ExternalDay:
    day: date
    available_at: datetime
    price_return: D
    oi_value_return: D
    top_trader_long_short_ratio: D | None
    global_long_short_ratio: D | None
    taker_long_short_ratio: D | None
    source_normalized_sha256: str

    @property
    def positioning_gap(self) -> D:
        if self.top_trader_long_short_ratio is None or self.global_long_short_ratio is None:
            raise ScreenReject("DATA_REJECT", "positioning divergence inputs are unavailable")
        return self.top_trader_long_short_ratio - self.global_long_short_ratio

    def canonical(self) -> dict[str, str]:
        return {
            "available_at": self.available_at.isoformat(timespec="seconds") + "Z",
            "day": self.day.isoformat(),
            "global_long_short_ratio": (
                None if self.global_long_short_ratio is None else str(self.global_long_short_ratio)
            ),
            "oi_value_return": str(self.oi_value_return),
            "positioning_gap": str(self.positioning_gap),
            "price_return": str(self.price_return),
            "source_normalized_sha256": self.source_normalized_sha256,
            "taker_long_short_ratio": (
                None if self.taker_long_short_ratio is None else str(self.taker_long_short_ratio)
            ),
            "top_trader_long_short_ratio": (
                None
                if self.top_trader_long_short_ratio is None
                else str(self.top_trader_long_short_ratio)
            ),
        }


def _median(values: list[D]) -> D:
    if not values:
        raise ScreenReject("DATA_REJECT", "median requires observations")
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D("2")


def external_day_from_bundle(
    bundle: archive.DailyMetricsBundle,
    bars: Iterable[base.Bar],
    *,
    family_key: str,
) -> ExternalDay:
    family = FEATURES.get(family_key)
    if family is None:
        raise ScreenReject("CONTRACT_REJECT", "unsupported or closed feature family")
    if bundle.feature_family not in {archive.ALL_FIELDS, family["feature_family"]}:
        raise ScreenReject("DATA_REJECT", "archive bundle does not bind the requested feature family")
    expected_metrics_times = [
        datetime.combine(bundle.day, datetime.min.time())
        + timedelta(minutes=archive.EXPECTED_INTERVAL_MINUTES * index)
        for index in range(archive.EXPECTED_ROWS_PER_DAY)
    ]
    if (
        bundle.day > archive.SELECTION_CUTOFF_DAY
        or [item.timestamp for item in bundle.observations] != expected_metrics_times
        or any(item.symbol != archive.SYMBOL for item in bundle.observations)
    ):
        raise ScreenReject(
            "DATA_REJECT",
            "external bundle must remain one adapter-validated complete pre-2025 BTCUSDT UTC day",
        )
    _sha(bundle.archive_sha256, "external archive hash")
    _sha(bundle.checksum_sidecar_sha256, "external checksum hash")
    _sha(bundle.normalized_payload_sha256, "external normalized payload hash")
    selected = sorted(
        (bar for bar in bars if bar.open_time.date() == bundle.day),
        key=lambda bar: bar.open_time,
    )
    expected_times = [
        datetime.combine(bundle.day, datetime.min.time()) + timedelta(hours=hour)
        for hour in range(24)
    ]
    if (
        [bar.open_time for bar in selected] != expected_times
        or any(bar.close_time != bar.open_time + timedelta(hours=1) for bar in selected)
    ):
        raise ScreenReject("DATA_REJECT", "external feature requires 24 complete H1 price bars for the UTC day")
    if selected[0].open <= 0:
        raise ScreenReject("DATA_REJECT", "daily price open must be positive")
    first = bundle.observations[0]
    last = bundle.observations[-1]
    first_oi_value = first.decimal("sum_open_interest_value")
    last_oi_value = last.decimal("sum_open_interest_value")
    positioning = family_key == "dra-binance-usdm-positioning-divergence-entry-admission"
    taker_confirmation = (
        family_key
        == "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission"
    )
    return ExternalDay(
        day=bundle.day,
        available_at=datetime.combine(bundle.day + timedelta(days=1), datetime.min.time()),
        price_return=(selected[-1].close / selected[0].open) - D("1"),
        oi_value_return=(last_oi_value / first_oi_value) - D("1"),
        top_trader_long_short_ratio=(
            last.decimal("sum_toptrader_long_short_ratio") if positioning else None
        ),
        global_long_short_ratio=(
            last.decimal("count_long_short_ratio") if positioning else None
        ),
        taker_long_short_ratio=(
            _median(
                [
                    item.decimal("sum_taker_long_short_vol_ratio")
                    for item in bundle.observations
                ]
            )
            if taker_confirmation
            else None
        ),
        source_normalized_sha256=bundle.normalized_payload_sha256,
    )


def consolidate_external_days(
    bundles: Iterable[archive.DailyMetricsBundle],
    bars: Iterable[base.Bar],
    *,
    family_key: str,
) -> tuple[list[ExternalDay], dict[str, Any]]:
    ordered = sorted(bundles, key=lambda item: item.day)
    if not ordered:
        raise ScreenReject("DATA_REJECT", "external archive bundle list is empty")
    days = [bundle.day for bundle in ordered]
    if len(days) != len(set(days)):
        raise ScreenReject("DATA_REJECT", "duplicate external archive day is forbidden")
    if any(right != left + timedelta(days=1) for left, right in zip(days, days[1:])):
        raise ScreenReject("DATA_REJECT", "external archive sequence contains a UTC-day gap")
    bar_list = list(bars)
    observations = [
        external_day_from_bundle(bundle, bar_list, family_key=family_key)
        for bundle in ordered
    ]
    archive_inventory = [bundle.evidence() for bundle in ordered]
    evidence = {
        "archive_inventory_sha256": sha256_bytes(canonical_document_bytes(archive_inventory)),
        "complete_utc_days": len(observations),
        "dataset": "BINANCE_USDM_DAILY_METRICS",
        "instrument": archive.SYMBOL,
        "latest_permitted_observation": "2024-12-31T23:59:59Z",
        "normalized_payload_sha256": sha256_bytes(
            canonical_document_bytes([item.canonical() for item in observations])
        ),
    }
    return observations, evidence


def validate_decision_observation(observation: ExternalDay, signal_bar: base.Bar) -> None:
    if signal_bar.open_time.hour != 23:
        raise ScreenReject("LOOKAHEAD_REJECT", "DRA admission decisions must be made on the 23:00 UTC bar close")
    if (
        observation.day != signal_bar.open_time.date()
        or observation.available_at != signal_bar.close_time
        or observation.available_at <= signal_bar.open_time
    ):
        raise ScreenReject(
            "LOOKAHEAD_REJECT",
            "feature must be the UTC day completed immediately before the unchanged next-bar fill",
        )


def admits(family_key: str, thresholds: dict[str, Any], observation: ExternalDay) -> bool:
    if family_key == "dra-binance-usdm-deleveraging-flush-entry-admission":
        return (
            observation.price_return <= D(str(thresholds["price_return_at_or_below"]))
            and observation.oi_value_return <= D(str(thresholds["oi_value_return_at_or_below"]))
        )
    if family_key == "dra-binance-usdm-positioning-divergence-entry-admission":
        return abs(observation.positioning_gap) >= D(
            str(thresholds["absolute_positioning_gap_at_or_above"])
        )
    if family_key == "dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission":
        return (
            observation.taker_long_short_ratio
            >= D(str(thresholds["taker_long_short_ratio_at_or_above"]))
            and observation.oi_value_return
            >= D(str(thresholds["oi_value_return_at_or_above"]))
        )
    raise ScreenReject("CONTRACT_REJECT", "unsupported or closed feature family")


class ExternalEntryAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    def __init__(
        self,
        *,
        family_key: str,
        thresholds: dict[str, Any],
        external_days: Iterable[ExternalDay],
    ) -> None:
        if family_key not in FEATURES:
            raise ScreenReject("CONTRACT_REJECT", "unsupported or closed feature family")
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.family_key = family_key
        self.thresholds = dict(thresholds)
        self.external_days = {item.day: item for item in external_days}
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0
        self.feature_unavailable_signal_count = 0
        self.fees_paid = base.ZERO
        self.adverse_slippage_cost = base.ZERO

    def _fill_buy(self, bar: base.Bar) -> None:
        if self.pending_signal is not None:
            adverse_price = base.adverse_buy(bar.open)
            fee = base.money(base.LOT_COST * base.FEE)
            quantity = base.quantity((base.LOT_COST - fee) / adverse_price)
            self.fees_paid = base.money(self.fees_paid + fee)
            self.adverse_slippage_cost = base.money(
                self.adverse_slippage_cost + quantity * (adverse_price - bar.open)
            )
        super()._fill_buy(bar)

    def _fill_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is None:
                continue
            adverse_price = base.adverse_sell(bar.open)
            gross = base.money(lot.quantity * adverse_price)
            fee = base.money(gross * base.FEE)
            net = gross - fee
            if base.net_return(net, lot.cost) < base.V1_FILL_RETURN:
                continue
            self.fees_paid = base.money(self.fees_paid + fee)
            self.adverse_slippage_cost = base.money(
                self.adverse_slippage_cost + lot.quantity * (bar.open - adverse_price)
            )
        super()._fill_exits(bar)

    def _signal(self, bar: base.Bar) -> bool:
        if not super()._signal(bar):
            return False
        self.parent_signal_count += 1
        observation = self.external_days.get(bar.open_time.date())
        if observation is None:
            self.feature_unavailable_signal_count += 1
            self.vetoed_signal_count += 1
            return False
        validate_decision_observation(observation, bar)
        admitted = admits(self.family_key, self.thresholds, observation)
        if admitted:
            self.admitted_signal_count += 1
        else:
            self.vetoed_signal_count += 1
        return admitted

    def result(self, final_bar: base.Bar, start: datetime, end: datetime) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        result.update(
            {
                "adverse_slippage_cost_usdt": str(base.money(self.adverse_slippage_cost)),
                "adverse_slippage_rate": str(base.SLIPPAGE),
                "admission_feature_family": FEATURES[self.family_key]["feature_family"],
                "admission_family_key": self.family_key,
                "fee_rate": str(base.FEE),
                "fees_paid_usdt": str(base.money(self.fees_paid)),
                "holding": {
                    "median_hold_hours": result["median_hold_hours"],
                    "median_open_age_hours": result["median_open_age_hours"],
                    "p90_hold_hours": result["p90_hold_hours"],
                    "p90_open_age_hours": result["p90_open_age_hours"],
                },
                "interventions": {
                    "admitted_signal_count": self.admitted_signal_count,
                    "feature_unavailable_signal_count": self.feature_unavailable_signal_count,
                    "parent_signal_count": self.parent_signal_count,
                    "reconciles": self.parent_signal_count
                    == self.admitted_signal_count + self.vetoed_signal_count,
                    "vetoed_signal_count": self.vetoed_signal_count,
                },
                "runner_identity": RUNNER_IDENTITY,
            }
        )
        return result


def simulate_candidate(
    bars: list[base.Bar],
    window: tuple[datetime, datetime],
    *,
    family_key: str,
    thresholds: dict[str, Any],
    external_days: Iterable[ExternalDay],
) -> dict[str, Any]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ScreenReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = ExternalEntryAdmissionEngine(
        family_key=family_key,
        thresholds=thresholds,
        external_days=external_days,
    )
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def parent_baseline(bars: list[base.Bar]) -> dict[str, Any]:
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


def verify_prior_evidence(manifest: dict[str, Any]) -> dict[str, str]:
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
        value.get("authorization") != AUTHORIZATION
        or value.get("capability_decision") != binding["disposition"]
        or value.get("document_type")
        != "BINANCE_USDM_DERIVATIVES_ARCHIVE_SOURCE_CAPABILITY_V1"
    ):
        raise ScreenReject("PRIOR_REJECT", "prior evidence identity mismatch")
    return {"path": binding["path"], "sha256": binding["sha256"]}


def _non_worse(candidate: dict[str, Any], parent: dict[str, Any], field: str) -> bool:
    left = candidate.get(field)
    right = parent.get(field)
    if left is None or right is None:
        return left == right
    return D(str(left)) <= D(str(right))


def _ratio(numerator: D, denominator: D) -> str | None:
    if denominator <= 0:
        return None
    return str((numerator / denominator).quantize(D("0.00000001"), rounding=ROUND_HALF_UP))


def variant_evidence(
    bars: list[base.Bar],
    external_days: list[ExternalDay],
    baseline: dict[str, Any],
    *,
    family_key: str,
    variant: dict[str, Any],
) -> dict[str, Any]:
    candidate = {
        "design": simulate_candidate(
            bars,
            DESIGN,
            family_key=family_key,
            thresholds=variant["thresholds"],
            external_days=external_days,
        ),
        "folds": {
            name: simulate_candidate(
                bars,
                window,
                family_key=family_key,
                thresholds=variant["thresholds"],
                external_days=external_days,
            )
            for name, window in FOLDS.items()
        },
        "validation": simulate_candidate(
            bars,
            VALIDATION,
            family_key=family_key,
            thresholds=variant["thresholds"],
            external_days=external_days,
        ),
    }
    annual_deltas: dict[str, dict[str, Any]] = {}
    for year in FOLDS:
        parent = baseline["folds"][year]
        child = candidate["folds"][year]
        annual_deltas[year] = {
            "drawdown_non_worse": D(str(child["max_drawdown_pct"]))
            <= D(str(parent["max_drawdown_pct"])),
            "median_holding_non_worse": _non_worse(child, parent, "median_hold_hours"),
            "total_pnl_delta_usdt": str(
                base.money(D(str(child["total_pnl_usdt"])) - D(str(parent["total_pnl_usdt"])))
            ),
        }
    positive = sorted(
        (
            D(item["total_pnl_delta_usdt"])
            for item in annual_deltas.values()
            if D(item["total_pnl_delta_usdt"]) > 0
        ),
        reverse=True,
    )
    positive_total = sum(positive, base.ZERO)
    concentration = {
        "positive_annual_delta_total_usdt": str(base.money(positive_total)),
        "top1_positive_annual_contribution_share": _ratio(
            sum(positive[:1], base.ZERO), positive_total
        ),
        "top3_positive_annual_contribution_share": _ratio(
            sum(positive[:3], base.ZERO), positive_total
        ),
    }
    annual_breadth = {
        "drawdown_non_worse_years": sum(item["drawdown_non_worse"] for item in annual_deltas.values()),
        "median_holding_non_worse_years": sum(
            item["median_holding_non_worse"] for item in annual_deltas.values()
        ),
        "positive_total_pnl_delta_years": sum(
            D(item["total_pnl_delta_usdt"]) > 0 for item in annual_deltas.values()
        ),
        "year_count": len(annual_deltas),
    }
    return {
        "annual_breadth": annual_breadth,
        "annual_deltas": annual_deltas,
        "candidate": candidate,
        "concentration": concentration,
        "matched_comparator_id": MATCHED_COMPARATOR,
        "paired_deltas": {
            "design": capacity.equal_capital_deltas(baseline["design"], candidate["design"]),
            "validation": capacity.equal_capital_deltas(
                baseline["validation"], candidate["validation"]
            ),
        },
        "role": variant["role"],
        "thresholds": variant["thresholds"],
        "variant_id": variant["variant_id"],
    }


def run_manifest(
    manifest: dict[str, Any],
    bars: list[base.Bar],
    bundles: Iterable[archive.DailyMetricsBundle],
) -> dict[str, Any]:
    """Run a future frozen experiment; this capability task never calls it."""

    validate_manifest(manifest)
    verify_prior_evidence(manifest)
    if not bars or bars[-1].close_time > SELECTION_CUTOFF_NAIVE:
        raise ScreenReject("OOS_REJECT", "price selection crosses the frozen pre-2025 boundary")
    if len(bars) != manifest["dataset"]["rows"] or base.data_hash(bars) != manifest["dataset"]["canonical_sha256"]:
        raise ScreenReject("DATA_REJECT", "price selection does not match its frozen hash and row count")
    external_days, evidence = consolidate_external_days(
        bundles, bars, family_key=manifest["feature"]["family_key"]
    )
    if evidence != manifest["external_dataset"]:
        raise ScreenReject("DATA_REJECT", "external dataset does not match its frozen evidence hashes")
    baseline = parent_baseline(bars)
    return {
        "authorization": AUTHORIZATION,
        "document_type": RESULT_TYPE,
        "economic_output_contract": list(REQUIRED_ECONOMIC_OUTPUTS),
        "experiment_id": manifest["experiment_id"],
        "external_dataset": evidence,
        "feature": manifest["feature"],
        "immediate_capability_performance_claim": "NOT_APPLICABLE",
        "parent": baseline,
        "runner_identity": RUNNER_IDENTITY,
        "schema_version": "1",
        "status": "HISTORICAL_RESEARCH_ONLY_NO_OOS",
        "variants": [
            variant_evidence(
                bars,
                external_days,
                baseline,
                family_key=manifest["feature"]["family_key"],
                variant=variant,
            )
            for variant in manifest["variants"]
        ],
    }
