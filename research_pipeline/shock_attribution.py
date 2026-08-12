from __future__ import annotations

import hashlib
import json
import os
import tempfile
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path
from typing import Any

from .evidence import evidence_progress
from .forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    resolve_active_forward_trigger_lineage,
)
from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .storage import (
    ResearchStore,
    read_json,
    resolve_store_reference,
    sha256_file,
    store_relative_reference,
)


R1_TRIGGER_ID = "prospective-mechanism-neutral-evidence-refresh-2026q4-r1"
R1_TRIGGER_FINGERPRINT = (
    "0e5a4675e937613202f0a4a243360a405e9ace1823c4b999edb5d479849d2589"
)
R1_SOURCE = (
    "server-local read-only OKX BTCUSDT complete 1h bars aggregated into "
    "complete UTC days"
)
R1_EVIDENCE_START = "2026-08-06T00:00:00Z"
R1_REVIEW_NOT_BEFORE = "2026-11-04T00:00:00Z"
R1_OBSERVATION_UNIT = "COMPLETE_UTC_DAY"
R1_MINIMUM_OBSERVATIONS = 90
SHOCK_THRESHOLD = Decimal("0.0300")
SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-shock-diagnostic.v1.schema.json"
)
SCHEMA_SHA256 = "4892456b848951237436538af429f083ca70c497d70cdef7ca5cf2bae1e01ef1"
DIAGNOSTIC_NAMESPACE = Path("shock-diagnostics") / "btc-utc-day-3pct-v1"
V2_SCHEMA_PATH = Path(__file__).with_name(
    "btc-utc-day-3pct-shock-diagnostic.v2.schema.json"
)
V2_SCHEMA_SHA256 = "1b4026c9dc18daba8c189a206d586518e284d8462cd7d39644fa4a8b45a3ec76"
V2_DIAGNOSTIC_NAMESPACE = Path("shock-diagnostics") / "btc-utc-day-3pct-v2"


def seal_r1_shock_diagnostics(
    store: ResearchStore,
    *,
    now: datetime,
    contract_activated_at: str,
) -> list[dict[str, Any]]:
    root_matches = [
        pair
        for pair in store.evidence_trigger_entries()
        if pair[0].get("trigger_id") == R1_TRIGGER_ID
    ]
    if not root_matches or (
        len(root_matches) == 1 and root_matches[0][1].get("status") != "CLOSED"
    ):
        return _seal_v1_shock_diagnostics(
            store,
            now=now,
            contract_activated_at=contract_activated_at,
        )
    lineage = resolve_active_forward_trigger_lineage(store)
    if lineage is None:
        return []
    if not lineage.rolled_over:
        return _seal_v1_shock_diagnostics(
            store,
            now=now,
            contract_activated_at=contract_activated_at,
        )
    return _seal_v2_shock_diagnostics(
        store,
        lineage=lineage,
        now=now,
        contract_activated_at=contract_activated_at,
    )


def _seal_v1_shock_diagnostics(
    store: ResearchStore,
    *,
    now: datetime,
    contract_activated_at: str,
) -> list[dict[str, Any]]:
    """Seal R1 shock artifacts and return at most one new forward Coach event."""
    current = now.astimezone(timezone.utc)
    activation = parse_timestamp(
        contract_activated_at, "shock contract_activated_at"
    ).astimezone(timezone.utc)
    if activation > current:
        raise ValueError("shock contract activation cannot be in the future")
    if sha256_file(SCHEMA_PATH) != SCHEMA_SHA256:
        raise ValueError("shock diagnostic schema hash mismatch")

    matches = [
        pair
        for pair in store.evidence_trigger_entries()
        if pair[0].get("trigger_id") == R1_TRIGGER_ID
    ]
    if not matches:
        return []
    if len(matches) != 1:
        raise ValueError("R1 shock diagnostic requires exactly one frozen trigger")
    trigger, trigger_state = matches[0]
    _validate_r1_identity(store, trigger, trigger_state)

    # This is the existing canonical validation path. It rehashes and
    # revalidates every accepted day, its hourly grid, strict sequence, count,
    # and cumulative chain before this module reads any pair.
    progress = evidence_progress(store, trigger, trigger_state, now=current)
    observations = trigger_state.get("evidence_observations", [])
    if not isinstance(observations, list):
        raise ValueError("R1 evidence observations must be a list")
    if observations and progress.get("source_contract") is None:
        raise ValueError("R1 accepted evidence has no verified source contract")
    if len(observations) < 2:
        return []

    new_events: list[dict[str, Any]] = []
    for index in range(1, len(observations)):
        prior_ref = _validated_reference(store, observations[index - 1])
        target_ref = _validated_reference(store, observations[index])
        prior_bundle = read_json(prior_ref["path"])
        target_bundle = read_json(target_ref["path"])
        _validate_reference_bundle_match(prior_ref, prior_bundle)
        _validate_reference_bundle_match(target_ref, target_bundle)

        target_received = parse_timestamp(
            target_ref["received_at"], "target received_at"
        ).astimezone(timezone.utc)
        if current < target_received:
            raise ValueError("shock diagnostic cannot precede accepted target evidence")
        eligibility = (
            "FORWARD_FACTOR_ELIGIBLE"
            if target_received >= activation
            else "CONTEXT_ONLY"
        )
        artifact_path = _artifact_path(store, target_ref["day"])
        existing = _load_existing_artifact(artifact_path)
        sealed_at = (
            str(existing.get("sealed_at"))
            if existing is not None
            else _iso_utc(current)
        )
        diagnostic = build_shock_diagnostic(
            prior_ref=_public_reference(prior_ref),
            prior_bundle=prior_bundle,
            target_ref=_public_reference(target_ref),
            target_bundle=target_bundle,
            contract_activated_at=_iso_utc(activation),
            sealed_at=sealed_at,
            eligibility=eligibility,
        )
        if diagnostic is None:
            if existing is not None:
                raise ValueError("non-shock day has a sealed shock diagnostic")
            continue

        canonical = _canonical_bytes(diagnostic)
        if existing is not None:
            if artifact_path.read_bytes() != canonical:
                raise ValueError("sealed shock diagnostic changed or conflicts")
            continue
        if eligibility == "FORWARD_FACTOR_ELIGIBLE" and new_events:
            break

        created = _create_only(artifact_path, canonical)
        if not created and artifact_path.read_bytes() != canonical:
            raise ValueError("concurrent shock diagnostic conflicts")
        if not created:
            continue
        if eligibility == "FORWARD_FACTOR_ELIGIBLE":
            relative = store_relative_reference(store.root, artifact_path)
            artifact_hash = sha256_file(artifact_path)
            new_events.append(
                _coach_event(
                    diagnostic,
                    artifact_path=relative,
                    artifact_sha256=artifact_hash,
                )
            )
    return new_events


def _seal_v2_shock_diagnostics(
    store: ResearchStore,
    *,
    lineage: ActiveForwardTriggerLineage,
    now: datetime,
    contract_activated_at: str,
) -> list[dict[str, Any]]:
    current = now.astimezone(timezone.utc)
    activation = parse_timestamp(
        contract_activated_at, "shock contract_activated_at"
    ).astimezone(timezone.utc)
    if activation > current:
        raise ValueError("shock contract activation cannot be in the future")
    if sha256_file(V2_SCHEMA_PATH) != V2_SCHEMA_SHA256:
        raise ValueError("shock diagnostic V2 schema hash mismatch")
    if not isinstance(read_json(V2_SCHEMA_PATH), dict):
        raise ValueError("shock diagnostic V2 schema must be an object")

    trigger = lineage.leaf_trigger
    trigger_state = lineage.leaf_state
    progress = evidence_progress(store, trigger, trigger_state, now=current)
    observations = trigger_state.get("evidence_observations", [])
    if not isinstance(observations, list):
        raise ValueError("rollover evidence observations must be a list")
    if observations and progress.get("source_contract") is None:
        raise ValueError("rollover accepted evidence has no verified source contract")
    if len(observations) < 2:
        return []

    new_events: list[dict[str, Any]] = []
    for index in range(1, len(observations)):
        prior_ref = _validated_reference(store, observations[index - 1])
        target_ref = _validated_reference(store, observations[index])
        prior_bundle = read_json(prior_ref["path"])
        target_bundle = read_json(target_ref["path"])
        _validate_reference_bundle_match(prior_ref, prior_bundle)
        _validate_reference_bundle_match(target_ref, target_bundle)
        _require_leaf_bundle_identity(prior_bundle, prior_ref["day"], trigger)
        _require_leaf_bundle_identity(target_bundle, target_ref["day"], trigger)

        target_received = parse_timestamp(
            target_ref["received_at"], "target received_at"
        ).astimezone(timezone.utc)
        if current < target_received:
            raise ValueError("shock diagnostic cannot precede accepted target evidence")
        eligibility = (
            "FORWARD_FACTOR_ELIGIBLE"
            if target_received >= activation
            else "CONTEXT_ONLY"
        )
        artifact_path = _artifact_path_v2(
            store, str(trigger["fingerprint"]), target_ref["day"]
        )
        existing = _load_existing_artifact(artifact_path)
        sealed_at = (
            str(existing.get("sealed_at"))
            if existing is not None
            else _iso_utc(current)
        )
        diagnostic = _build_shock_diagnostic_v2(
            lineage=lineage,
            prior_ref=_public_reference(prior_ref),
            prior_bundle=prior_bundle,
            target_ref=_public_reference(target_ref),
            target_bundle=target_bundle,
            contract_activated_at=_iso_utc(activation),
            sealed_at=sealed_at,
            eligibility=eligibility,
        )
        if diagnostic is None:
            if existing is not None:
                raise ValueError("non-shock day has a sealed shock diagnostic")
            continue
        canonical = _canonical_bytes(diagnostic)
        if existing is not None:
            if artifact_path.read_bytes() != canonical:
                raise ValueError("sealed shock diagnostic changed or conflicts")
            continue
        if eligibility == "FORWARD_FACTOR_ELIGIBLE" and new_events:
            break
        created = _create_only(artifact_path, canonical)
        if not created and artifact_path.read_bytes() != canonical:
            raise ValueError("concurrent shock diagnostic conflicts")
        if not created:
            continue
        if eligibility == "FORWARD_FACTOR_ELIGIBLE":
            new_events.append(
                _coach_event(
                    diagnostic,
                    artifact_path=store_relative_reference(store.root, artifact_path),
                    artifact_sha256=sha256_file(artifact_path),
                )
            )
    return new_events


def _build_shock_diagnostic_v2(
    *,
    lineage: ActiveForwardTriggerLineage,
    prior_ref: dict[str, str],
    prior_bundle: dict[str, Any],
    target_ref: dict[str, str],
    target_bundle: dict[str, Any],
    contract_activated_at: str,
    sealed_at: str,
    eligibility: str,
) -> dict[str, Any] | None:
    projected_prior = dict(prior_bundle)
    projected_target = dict(target_bundle)
    for bundle in (projected_prior, projected_target):
        bundle["trigger_id"] = R1_TRIGGER_ID
        bundle["trigger_fingerprint"] = R1_TRIGGER_FINGERPRINT
    result = build_shock_diagnostic(
        prior_ref=prior_ref,
        prior_bundle=projected_prior,
        target_ref=target_ref,
        target_bundle=projected_target,
        contract_activated_at=contract_activated_at,
        sealed_at=sealed_at,
        eligibility=eligibility,
    )
    if result is None:
        return None
    leaf = lineage.leaf_trigger
    result["diagnostic_type"] = "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V2"
    result["trigger_id"] = leaf["trigger_id"]
    result["trigger_fingerprint"] = leaf["fingerprint"]
    result["root_trigger_id"] = lineage.root_trigger["trigger_id"]
    result["root_trigger_fingerprint"] = lineage.root_trigger["fingerprint"]
    result["leaf_trigger_id"] = leaf["trigger_id"]
    result["leaf_trigger_fingerprint"] = leaf["fingerprint"]
    return result


def build_shock_diagnostic(
    *,
    prior_ref: dict[str, str],
    prior_bundle: dict[str, Any],
    target_ref: dict[str, str],
    target_bundle: dict[str, Any],
    contract_activated_at: str,
    sealed_at: str,
    eligibility: str,
) -> dict[str, Any] | None:
    if eligibility not in {"CONTEXT_ONLY", "FORWARD_FACTOR_ELIGIBLE"}:
        raise ValueError("shock diagnostic eligibility is invalid")
    prior_day = date.fromisoformat(prior_ref["day"])
    target_day = date.fromisoformat(target_ref["day"])
    if target_day != prior_day + timedelta(days=1):
        raise ValueError("shock diagnostic requires two adjacent UTC days")
    _require_bundle_identity(prior_bundle, prior_ref["day"])
    _require_bundle_identity(target_bundle, target_ref["day"])

    prior_bars = _bars(prior_bundle, "prior")
    target_bars = _bars(target_bundle, "target")
    prior_close = _number(prior_bars[-1], "close")
    closes = [_number(bar, "close") for bar in target_bars]
    opens = [_number(bar, "open") for bar in target_bars]
    highs = [_number(bar, "high") for bar in target_bars]
    lows = [_number(bar, "low") for bar in target_bars]
    volumes = [_number(bar, "volume", positive=False) for bar in target_bars]

    with localcontext() as context:
        context.prec = 50
        simple_return = closes[-1] / prior_close - Decimal("1")
        absolute_return = abs(simple_return)
        if absolute_return < SHOCK_THRESHOLD:
            return None
        incremental: list[Decimal] = []
        cumulative: list[Decimal] = []
        previous_close = prior_close
        for close in closes:
            incremental.append(close / previous_close - Decimal("1"))
            cumulative.append(close / prior_close - Decimal("1"))
            previous_close = close
        target_open = opens[0]
        target_high = max(highs)
        target_low = min(lows)
        target_volume = sum(volumes, Decimal("0"))
        open_gap = target_open / prior_close - Decimal("1")
        high_excursion = target_high / prior_close - Decimal("1")
        low_excursion = target_low / prior_close - Decimal("1")
        first_12h = closes[11] / prior_close - Decimal("1")
        last_12h = closes[-1] / closes[11] - Decimal("1")
        close_in_range = (
            None
            if target_high == target_low
            else (closes[-1] - target_low) / (target_high - target_low)
        )
        largest_volume_share = (
            None if target_volume == 0 else max(volumes) / target_volume
        )

    crossing_index = next(
        (index for index, value in enumerate(cumulative) if abs(value) >= SHOCK_THRESHOLD),
        None,
    )
    if crossing_index is None:
        raise ValueError("qualifying shock has no hourly close threshold crossing")
    peak_index = max(range(24), key=lambda index: highs[index])
    trough_index = min(range(24), key=lambda index: lows[index])
    largest_return_index = max(range(24), key=lambda index: abs(incremental[index]))
    largest_volume_index = (
        None if target_volume == 0 else max(range(24), key=lambda index: volumes[index])
    )

    return {
        "schema_version": "1",
        "diagnostic_type": "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V1",
        "trigger_id": R1_TRIGGER_ID,
        "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
        "source": R1_SOURCE,
        "observation_unit": R1_OBSERVATION_UNIT,
        "threshold_return": "0.0300",
        "prior_day": dict(prior_ref),
        "target_day": dict(target_ref),
        "contract_activated_at": contract_activated_at,
        "sealed_at": sealed_at,
        "eligibility": eligibility,
        "path": {
            "qualifies": True,
            "direction": "UP" if simple_return > 0 else "DOWN",
            "prior_close": _decimal_text(prior_close),
            "target_open": _decimal_text(target_open),
            "target_high": _decimal_text(target_high),
            "target_low": _decimal_text(target_low),
            "target_close": _decimal_text(closes[-1]),
            "target_volume": _decimal_text(target_volume),
            "simple_return": _decimal_text(simple_return),
            "absolute_simple_return": _decimal_text(absolute_return),
            "open_gap_return": _decimal_text(open_gap),
            "high_excursion_return": _decimal_text(high_excursion),
            "low_excursion_return": _decimal_text(low_excursion),
            "hourly_incremental_returns": _hourly_returns(target_bars, incremental),
            "hourly_cumulative_returns": _hourly_returns(target_bars, cumulative),
            "earliest_threshold_crossing_interval_end": str(
                target_bars[crossing_index]["interval_end"]
            ),
            "peak_interval_start": str(target_bars[peak_index]["interval_start"]),
            "trough_interval_start": str(target_bars[trough_index]["interval_start"]),
            "largest_absolute_hourly_return_interval_start": str(
                target_bars[largest_return_index]["interval_start"]
            ),
            "largest_absolute_hourly_return": _decimal_text(
                abs(incremental[largest_return_index])
            ),
            "first_12h_return": _decimal_text(first_12h),
            "last_12h_return": _decimal_text(last_12h),
            "positive_hour_count": sum(value > 0 for value in incremental),
            "negative_hour_count": sum(value < 0 for value in incremental),
            "flat_hour_count": sum(value == 0 for value in incremental),
            "close_in_range": (
                None if close_in_range is None else _decimal_text(close_in_range)
            ),
            "largest_hour_volume_interval_start": (
                None
                if largest_volume_index is None
                else str(target_bars[largest_volume_index]["interval_start"])
            ),
            "largest_hour_volume_share": (
                None
                if largest_volume_share is None
                else _decimal_text(largest_volume_share)
            ),
        },
        "guardrails": {
            "causal_explanation": "UNKNOWN",
            "prediction_evaluated": False,
            "strategy_mapping_evaluated": False,
            "pnl_evaluated": False,
            "drawdown_evaluated": False,
            "oos_opened": False,
            "news_or_llm_used": False,
        },
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _validate_r1_identity(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
) -> None:
    expected = {
        "trigger_id": R1_TRIGGER_ID,
        "fingerprint": R1_TRIGGER_FINGERPRINT,
        "source": R1_SOURCE,
        "evidence_start": R1_EVIDENCE_START,
        "review_not_before": R1_REVIEW_NOT_BEFORE,
        "observation_unit": R1_OBSERVATION_UNIT,
        "minimum_observations": R1_MINIMUM_OBSERVATIONS,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if trigger.get(key) != value:
            raise ValueError(f"R1 shock trigger {key} mismatch")
    if trigger.get("purpose", "HYPOTHESIS_DISCOVERY") != "HYPOTHESIS_DISCOVERY":
        raise ValueError("R1 shock trigger purpose mismatch")
    if trigger.get("candidate_binding") is not None:
        raise ValueError("R1 shock trigger candidate_binding mismatch")
    if state.get("trigger_id") != R1_TRIGGER_ID:
        raise ValueError("R1 shock trigger state identity mismatch")
    trigger_path = store.evidence_trigger_dir(R1_TRIGGER_ID) / "trigger.json"
    if trigger_path.is_symlink() or not trigger_path.is_file():
        raise ValueError("R1 trigger artifact is missing or unsafe")
    if state.get("trigger_sha256") != sha256_file(trigger_path):
        raise ValueError("R1 trigger artifact hash mismatch")


def _validated_reference(store: ResearchStore, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("R1 observation reference must be an object")
    required = {"day", "path", "sha256", "chain_head", "received_at"}
    if not required.issubset(value):
        raise ValueError("R1 observation reference is incomplete")
    path = resolve_store_reference(store.root, value["path"])
    if path.is_symlink() or not path.is_file():
        raise ValueError("R1 observation artifact is missing or unsafe")
    if sha256_file(path) != value["sha256"]:
        raise ValueError("R1 observation artifact hash mismatch")
    return {
        "day": str(value["day"]),
        "path": path,
        "artifact_path": store_relative_reference(store.root, path),
        "artifact_sha256": str(value["sha256"]),
        "chain_head": str(value["chain_head"]),
        "received_at": str(value["received_at"]),
    }


def _validate_reference_bundle_match(
    reference: dict[str, Any], bundle: dict[str, Any]
) -> None:
    if bundle.get("day") != reference["day"]:
        raise ValueError("R1 observation day and bundle disagree")
    if bundle.get("received_at") != reference["received_at"]:
        raise ValueError("R1 observation received_at and bundle disagree")


def _public_reference(reference: dict[str, Any]) -> dict[str, str]:
    return {
        "day": reference["day"],
        "artifact_path": reference["artifact_path"],
        "artifact_sha256": reference["artifact_sha256"],
        "chain_head": reference["chain_head"],
        "received_at": reference["received_at"],
    }


def _require_bundle_identity(bundle: dict[str, Any], day: str) -> None:
    expected = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": R1_TRIGGER_ID,
        "trigger_fingerprint": R1_TRIGGER_FINGERPRINT,
        "source": R1_SOURCE,
        "day": day,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if bundle.get(key) != value:
            raise ValueError(f"R1 day bundle {key} mismatch")


def _require_leaf_bundle_identity(
    bundle: dict[str, Any], day: str, trigger: dict[str, Any]
) -> None:
    expected = {
        "schema_version": "1",
        "bundle_type": "FORWARD_EVIDENCE_DAY",
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "day": day,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    for key, value in expected.items():
        if bundle.get(key) != value:
            raise ValueError(f"rollover day bundle {key} mismatch")


def _bars(bundle: dict[str, Any], label: str) -> list[dict[str, Any]]:
    value = bundle.get("bars")
    if not isinstance(value, list) or len(value) != 24:
        raise ValueError(f"{label} R1 day must contain exactly 24 bars")
    if not all(isinstance(item, dict) for item in value):
        raise ValueError(f"{label} R1 day bars must be objects")
    return value


def _number(
    value: dict[str, Any], key: str, *, positive: bool = True
) -> Decimal:
    try:
        number = Decimal(str(value[key]))
    except (InvalidOperation, KeyError) as error:
        raise ValueError(f"shock bar {key} is invalid") from error
    if not number.is_finite() or (number <= 0 if positive else number < 0):
        raise ValueError(f"shock bar {key} is outside its valid domain")
    return number


def _hourly_returns(
    bars: list[dict[str, Any]], values: list[Decimal]
) -> list[dict[str, str]]:
    return [
        {
            "interval_start": str(bar["interval_start"]),
            "interval_end": str(bar["interval_end"]),
            "return": _decimal_text(value),
        }
        for bar, value in zip(bars, values, strict=True)
    ]


def _decimal_text(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in {"", "-0"} else text


def _artifact_path(store: ResearchStore, day: str) -> Path:
    date.fromisoformat(day)
    root = store.root.resolve()
    namespace = root / DIAGNOSTIC_NAMESPACE
    namespace.mkdir(parents=True, exist_ok=True)
    resolved_namespace = namespace.resolve()
    try:
        resolved_namespace.relative_to(root)
    except ValueError as error:
        raise ValueError("shock diagnostic namespace escapes research state") from error
    if resolved_namespace != namespace:
        raise ValueError("shock diagnostic namespace must not traverse a link")
    return namespace / f"{day}.json"


def _artifact_path_v2(store: ResearchStore, fingerprint: str, day: str) -> Path:
    if len(fingerprint) != 64 or any(
        character not in "0123456789abcdef" for character in fingerprint
    ):
        raise ValueError("shock diagnostic V2 fingerprint is invalid")
    date.fromisoformat(day)
    root = store.root.resolve()
    namespace = root / V2_DIAGNOSTIC_NAMESPACE / fingerprint
    namespace.mkdir(parents=True, exist_ok=True)
    resolved_namespace = namespace.resolve()
    try:
        resolved_namespace.relative_to(root)
    except ValueError as error:
        raise ValueError("shock diagnostic V2 namespace escapes research state") from error
    if resolved_namespace != namespace:
        raise ValueError("shock diagnostic V2 namespace must not traverse a link")
    return namespace / f"{day}.json"


def _load_existing_artifact(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError("shock diagnostic artifact is unsafe")
    value = read_json(path)
    if not isinstance(value, dict):
        raise ValueError("shock diagnostic artifact must be an object")
    return value


def _create_only(path: Path, content: bytes) -> bool:
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as stream:
            temporary_name = stream.name
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary_name, path)
            return True
        except FileExistsError:
            return False
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def _canonical_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _coach_event(
    diagnostic: dict[str, Any], *, artifact_path: str, artifact_sha256: str
) -> dict[str, Any]:
    path = diagnostic["path"]
    return {
        "event_type": "MATERIAL_LEARNING",
        "artifact_path": artifact_path,
        "sha256": artifact_sha256,
        "research_status": "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_READY",
        "material_conclusion": (
            f"BTC completed UTC day {diagnostic['target_day']['day']} with a "
            f"{path['direction']} absolute simple-return shock of "
            f"{path['absolute_simple_return']}; deterministic path anatomy was "
            "sealed and causal explanation remains UNKNOWN."
        ),
        "pnl_drawdown_evidence": {
            "immediate_effect": "ZERO",
            "economic_value": "MISSING_PROOF",
        },
        "evidence_diagnostic": {
            "diagnostic_type": diagnostic["diagnostic_type"],
            "target_day": diagnostic["target_day"]["day"],
            "direction": path["direction"],
            "simple_return": path["simple_return"],
            "causal_explanation": "UNKNOWN",
        },
        "uncertainty": (
            "Prediction, causal why, sample sufficiency, fees, slippage, PnL, "
            "drawdown, candidate readiness, and OOS value remain MISSING_PROOF."
        ),
        "next_action": (
            "ACCUMULATE_FORWARD_SHOCK_EPISODES_WITHOUT_THRESHOLD_OR_WINDOW_RELAXATION"
        ),
        "concept_to_teach": (
            "Complete-day OHLCV can describe when and how a shock unfolded, not why."
        ),
    }


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
