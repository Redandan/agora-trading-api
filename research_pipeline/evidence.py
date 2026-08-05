from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any

from .forward_candidate import DIAGNOSTIC_CONTRACT_PATH, load_diagnostic_contract
from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .storage import ResearchStore, atomic_write_json, read_json, sha256_file
from .waiting import DETERMINISTIC_COMPLETE_DAY_CHECKS, build_evidence_review


SHA256 = re.compile(r"^[0-9a-f]{64}$")
MANIFEST_TYPE = "FORWARD_EVIDENCE_MANIFEST"
DAILY_BUNDLE_TYPE = "FORWARD_EVIDENCE_DAY"
SOURCE_CONTRACT_TYPE = "FORWARD_EVIDENCE_SOURCE_CONTRACT"
EMPTY_CHAIN_HEAD = "0" * 64


def register_evidence_source_contract(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    value: dict[str, Any],
    *,
    registered_at: datetime | None = None,
) -> dict[str, Any]:
    current = (registered_at or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if state.get("evidence_source_contract"):
        raise ValueError("evidence source contract is already sealed")
    contract = validate_evidence_source_contract(value, trigger, registered_at=current)
    directory = store.evidence_trigger_dir(str(trigger["trigger_id"]))
    path = directory / "source-contract.json"
    if path.exists():
        raise ValueError("sealed evidence source contract already exists")
    atomic_write_json(path, contract)
    reference = {
        "path": _relative_path(store, path),
        "sha256": sha256_file(path),
        "producer": contract["producer"],
        "registered_at": contract["registered_at"],
    }
    state["evidence_source_contract"] = reference
    store.save_evidence_trigger_state(state)
    store.append_evidence_trigger_event(
        str(trigger["trigger_id"]),
        "EVIDENCE_SOURCE_CONTRACT_REGISTERED",
        reference,
    )
    return reference


def validate_evidence_source_contract(
    value: dict[str, Any],
    trigger: dict[str, Any],
    *,
    registered_at: datetime,
) -> dict[str, Any]:
    required = {
        "schema_version",
        "contract_type",
        "trigger_id",
        "trigger_fingerprint",
        "source",
        "producer",
        "transport",
        "artifact_format",
        "worker_network_access",
        "worker_database_access",
        "backfill",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"evidence source contract missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1" or value["contract_type"] != SOURCE_CONTRACT_TYPE:
        raise ValueError("evidence source contract schema/type is invalid")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("evidence source contract must remain research-only")
    if value["trigger_id"] != trigger["trigger_id"]:
        raise ValueError("evidence source contract trigger_id mismatch")
    if value["trigger_fingerprint"] != trigger["fingerprint"]:
        raise ValueError("evidence source contract trigger fingerprint mismatch")
    if value["source"] != trigger["source"]:
        raise ValueError("evidence source contract source mismatch")
    producer = str(value["producer"]).strip()
    transport = str(value["transport"]).strip()
    if not producer or not transport:
        raise ValueError("evidence source contract producer and transport are required")
    if value["artifact_format"] != "FORWARD_EVIDENCE_DAY_V1":
        raise ValueError("evidence source contract artifact_format is invalid")
    if value["worker_network_access"] != "DENY":
        raise ValueError("Research Worker network access must remain denied")
    if value["worker_database_access"] != "DENY":
        raise ValueError("Research Worker database access must remain denied")
    if value["backfill"] != "DENY":
        raise ValueError("evidence source contract must deny backfill")
    evidence_start = parse_timestamp(str(trigger["evidence_start"]), "evidence_start").astimezone(
        timezone.utc
    )
    current = registered_at.astimezone(timezone.utc)
    if current >= evidence_start:
        raise ValueError("evidence source contract must be sealed before evidence_start")
    return {
        "schema_version": "1",
        "contract_type": SOURCE_CONTRACT_TYPE,
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "producer": producer,
        "transport": transport,
        "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
        "worker_network_access": "DENY",
        "worker_database_access": "DENY",
        "backfill": "DENY",
        "registered_at": _iso_utc(current),
        "authorization": RESEARCH_AUTHORIZATION,
    }


def seal_daily_evidence(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    value: dict[str, Any],
    *,
    received_at: datetime | None = None,
    capture_max_lag_seconds: int = 21600,
) -> dict[str, Any]:
    current = (received_at or datetime.now(timezone.utc)).astimezone(timezone.utc)
    source_contract = verify_evidence_source_contract(store, trigger, state)
    if source_contract is None:
        raise ValueError("evidence source contract is not registered")
    progress = evidence_progress(
        store,
        trigger,
        state,
        now=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    if progress["status"] == "COMPLETE":
        raise ValueError("evidence trigger already has the required observations")
    observations = state.setdefault("evidence_observations", [])
    if not isinstance(observations, list):
        raise ValueError("evidence trigger state observations must be a list")
    existing_ref = next(
        (item for item in observations if item.get("day") == str(value.get("day", ""))),
        None,
    )
    if existing_ref is not None:
        existing_path = (store.root / str(existing_ref.get("path", ""))).resolve()
        existing_value = read_json(existing_path) if existing_path.is_file() else None
        candidate = {**value, "received_at": existing_value.get("received_at")} if existing_value else value
        normalized = validate_daily_evidence_bundle(
            candidate,
            trigger,
            received_at=current,
            capture_max_lag_seconds=capture_max_lag_seconds,
            verify_stored=True,
        )
        if (
            not existing_path.is_file()
            or sha256_file(existing_path) != existing_ref.get("sha256")
            or existing_value != normalized
        ):
            raise ValueError("sealed evidence day idempotency check failed")
        progress, review_result = _finalize_review_if_due(
            store,
            trigger,
            state,
            progress=progress,
            current=current,
            capture_max_lag_seconds=capture_max_lag_seconds,
        )
        return {
            "status": "EVIDENCE_DAY_ALREADY_SEALED",
            "observation": existing_ref,
            "progress": progress,
            "review": review_result,
        }
    normalized = validate_daily_evidence_bundle(
        value,
        trigger,
        received_at=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    if normalized["source_provenance"]["producer"] != source_contract["producer"]:
        raise ValueError("daily evidence producer does not match the sealed source contract")
    expected_day = str(progress["next_observation_day"])
    if normalized["day"] != expected_day:
        raise ValueError(
            f"evidence day must be the next untouched day {expected_day}; got {normalized['day']}"
        )

    directory = store.evidence_trigger_dir(str(trigger["trigger_id"])) / "observations"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{normalized['day']}.json"
    if path.exists():
        existing = read_json(path)
        if existing != normalized:
            raise ValueError(f"sealed evidence day already exists with different content: {path}")
    else:
        atomic_write_json(path, normalized)
    artifact_hash = sha256_file(path)
    relative = _relative_path(store, path)
    previous_chain = str(state.get("evidence_chain_head") or EMPTY_CHAIN_HEAD)
    if not SHA256.fullmatch(previous_chain):
        raise ValueError("evidence chain head is invalid")
    chain_head = hashlib.sha256(
        f"{previous_chain}:{normalized['day']}:{artifact_hash}".encode("utf-8")
    ).hexdigest()
    reference = {
        "day": normalized["day"],
        "path": relative,
        "sha256": artifact_hash,
        "chain_head": chain_head,
        "received_at": normalized["received_at"],
    }
    observations.append(reference)
    state["evidence_chain_head"] = chain_head
    state["evidence_observation_count"] = len(observations)
    store.save_evidence_trigger_state(state)
    store.append_evidence_trigger_event(
        str(trigger["trigger_id"]),
        "EVIDENCE_DAY_SEALED",
        reference,
    )
    updated_progress = evidence_progress(
        store,
        trigger,
        state,
        now=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    updated_progress, review_result = _finalize_review_if_due(
        store,
        trigger,
        state,
        progress=updated_progress,
        current=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    return {
        "status": "EVIDENCE_DAY_SEALED",
        "observation": reference,
        "progress": updated_progress,
        "review": review_result,
    }


def _finalize_review_if_due(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    *,
    progress: dict[str, Any],
    current: datetime,
    capture_max_lag_seconds: int,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    review_not_before = parse_timestamp(
        str(trigger["review_not_before"]), "review_not_before"
    ).astimezone(timezone.utc)
    if progress["status"] != "COMPLETE" or current < review_not_before:
        return progress, None
    review = seal_deterministic_evidence_review(
        store,
        trigger,
        state,
        now=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    return (
        evidence_progress(
            store,
            trigger,
            state,
            now=current,
            capture_max_lag_seconds=capture_max_lag_seconds,
        ),
        review,
    )


def validate_daily_evidence_bundle(
    value: dict[str, Any],
    trigger: dict[str, Any],
    *,
    received_at: datetime,
    capture_max_lag_seconds: int = 21600,
    verify_stored: bool = False,
) -> dict[str, Any]:
    required = {
        "schema_version",
        "bundle_type",
        "trigger_id",
        "trigger_fingerprint",
        "source",
        "day",
        "bars",
        "source_provenance",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"daily evidence bundle missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1" or value["bundle_type"] != DAILY_BUNDLE_TYPE:
        raise ValueError("daily evidence bundle schema/type is invalid")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("daily evidence authorization must remain research-only")
    if value["trigger_id"] != trigger["trigger_id"]:
        raise ValueError("daily evidence trigger_id mismatch")
    if value["trigger_fingerprint"] != trigger["fingerprint"]:
        raise ValueError("daily evidence trigger fingerprint mismatch")
    if value["source"] != trigger["source"]:
        raise ValueError("daily evidence source mismatch")
    if trigger["observation_unit"] != "COMPLETE_UTC_DAY":
        raise ValueError("daily evidence ingestion requires COMPLETE_UTC_DAY")
    if capture_max_lag_seconds < 1:
        raise ValueError("capture_max_lag_seconds must be positive")

    try:
        day = date.fromisoformat(str(value["day"]))
    except ValueError as error:
        raise ValueError("daily evidence day must be YYYY-MM-DD") from error
    day_start = datetime.combine(day, time.min, tzinfo=timezone.utc)
    day_end = day_start + timedelta(days=1)
    evidence_start = parse_timestamp(str(trigger["evidence_start"]), "evidence_start").astimezone(
        timezone.utc
    )
    review_not_before = parse_timestamp(
        str(trigger["review_not_before"]), "review_not_before"
    ).astimezone(timezone.utc)
    if evidence_start.time() != time.min:
        raise ValueError("COMPLETE_UTC_DAY evidence_start must be UTC midnight")
    if day_start < evidence_start or day_end > review_not_before:
        raise ValueError("daily evidence day falls outside the frozen discovery window")
    received = received_at.astimezone(timezone.utc)
    stored_received = value.get("received_at")
    if verify_stored:
        if stored_received is None:
            raise ValueError("stored daily evidence is missing received_at")
        received = parse_timestamp(str(stored_received), "received_at").astimezone(timezone.utc)
    elif stored_received is not None:
        raise ValueError("received_at is assigned by the canonical server writer")
    if received < day_end:
        raise ValueError("daily evidence cannot be sealed before the complete UTC day closes")
    if received > day_end + timedelta(seconds=capture_max_lag_seconds):
        raise ValueError("daily evidence capture window expired; backfill is prohibited")

    bars = value["bars"]
    if not isinstance(bars, list) or len(bars) != 24:
        raise ValueError("daily evidence requires exactly 24 complete hourly bars")
    normalized_bars: list[dict[str, str]] = []
    for index, item in enumerate(bars):
        if not isinstance(item, dict):
            raise ValueError(f"bars[{index}] must be an object")
        start = parse_timestamp(str(item.get("interval_start", "")), f"bars[{index}].interval_start")
        end = parse_timestamp(str(item.get("interval_end", "")), f"bars[{index}].interval_end")
        expected_start = day_start + timedelta(hours=index)
        if start.astimezone(timezone.utc) != expected_start:
            raise ValueError(f"bars[{index}] is off the frozen hourly grid")
        if end.astimezone(timezone.utc) != expected_start + timedelta(hours=1):
            raise ValueError(f"bars[{index}] is not one complete hour")
        open_price = _decimal(item, "open", index, positive=True)
        high_price = _decimal(item, "high", index, positive=True)
        low_price = _decimal(item, "low", index, positive=True)
        close_price = _decimal(item, "close", index, positive=True)
        volume = _decimal(item, "volume", index, positive=False)
        if low_price > min(open_price, close_price) or high_price < max(open_price, close_price):
            raise ValueError(f"bars[{index}] OHLC bounds are invalid")
        if high_price < low_price:
            raise ValueError(f"bars[{index}] high is below low")
        normalized_bar = {
            "interval_start": _iso_utc(expected_start),
            "interval_end": _iso_utc(expected_start + timedelta(hours=1)),
            "open": _decimal_text(open_price),
            "high": _decimal_text(high_price),
            "low": _decimal_text(low_price),
            "close": _decimal_text(close_price),
            "volume": _decimal_text(volume),
        }
        canonical = json.dumps(
            normalized_bar, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        normalized_bar["row_sha256"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        normalized_bars.append(normalized_bar)

    provenance = value["source_provenance"]
    if not isinstance(provenance, dict):
        raise ValueError("source_provenance must be an object")
    producer = str(provenance.get("producer", "")).strip()
    artifact_id = str(provenance.get("artifact_id", "")).strip()
    artifact_hash = str(provenance.get("sha256", "")).strip().lower()
    if not producer or not artifact_id or not SHA256.fullmatch(artifact_hash):
        raise ValueError("source_provenance requires producer, artifact_id, and sha256")
    return {
        "schema_version": "1",
        "bundle_type": DAILY_BUNDLE_TYPE,
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "day": day.isoformat(),
        "bars": normalized_bars,
        "source_provenance": {
            "producer": producer,
            "artifact_id": artifact_id,
            "sha256": artifact_hash,
        },
        "received_at": _iso_utc(received),
        "authorization": RESEARCH_AUTHORIZATION,
    }


def evidence_progress(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    *,
    now: datetime | None = None,
    capture_max_lag_seconds: int = 21600,
) -> dict[str, Any]:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    evidence_start = parse_timestamp(str(trigger["evidence_start"]), "evidence_start").astimezone(
        timezone.utc
    )
    review_not_before = parse_timestamp(
        str(trigger["review_not_before"]), "review_not_before"
    ).astimezone(timezone.utc)
    minimum = int(trigger["minimum_observations"])
    target = minimum
    if trigger.get("observation_unit") == "COMPLETE_UTC_DAY":
        frozen_days = int((review_not_before - evidence_start).total_seconds() // 86400)
        target = max(minimum, frozen_days)
    observations = state.get("evidence_observations", [])
    if not isinstance(observations, list):
        raise ValueError("evidence trigger state observations must be a list")
    previous_chain = EMPTY_CHAIN_HEAD
    for index, reference in enumerate(observations):
        if not isinstance(reference, dict):
            raise ValueError(f"evidence observation reference {index} must be an object")
        expected_day = (evidence_start + timedelta(days=index)).date().isoformat()
        if reference.get("day") != expected_day:
            raise ValueError(f"evidence observation {index} breaks the frozen day sequence")
        path = (store.root / str(reference.get("path", ""))).resolve()
        try:
            path.relative_to(store.root)
        except ValueError as error:
            raise ValueError("evidence observation path escapes research state") from error
        if not path.is_file() or sha256_file(path) != reference.get("sha256"):
            raise ValueError(f"sealed evidence observation changed or disappeared: {expected_day}")
        stored = read_json(path)
        normalized = validate_daily_evidence_bundle(
            stored,
            trigger,
            received_at=current,
            capture_max_lag_seconds=capture_max_lag_seconds,
            verify_stored=True,
        )
        if stored != normalized:
            raise ValueError(f"sealed evidence observation is not canonical: {expected_day}")
        chain_head = hashlib.sha256(
            f"{previous_chain}:{expected_day}:{reference['sha256']}".encode("utf-8")
        ).hexdigest()
        if reference.get("chain_head") != chain_head:
            raise ValueError(f"evidence observation chain mismatch: {expected_day}")
        previous_chain = chain_head
    if observations and state.get("evidence_chain_head") != previous_chain:
        raise ValueError("evidence trigger state chain head mismatch")
    if int(state.get("evidence_observation_count", len(observations))) != len(observations):
        raise ValueError("evidence trigger observation count mismatch")

    source_contract = verify_evidence_source_contract(store, trigger, state)

    count = len(observations)
    next_start = evidence_start + timedelta(days=count)
    next_close = next_start + timedelta(days=1)
    deadline = next_close + timedelta(seconds=capture_max_lag_seconds)
    lifecycle_status = str(state.get("status"))
    if lifecycle_status == "CLOSED":
        status = "CLOSED"
    elif lifecycle_status in {"READY_FOR_HYPOTHESIS", "READY_FOR_OOS"}:
        status = lifecycle_status
    elif source_contract is None:
        status = "SOURCE_UNBOUND"
    elif count >= target:
        status = "COMPLETE"
    elif current < evidence_start:
        status = "NOT_STARTED"
    elif current < next_close:
        status = "AWAITING_DAY_CLOSE"
    elif current <= deadline:
        status = "CAPTURE_DUE"
    else:
        status = "MISSED_CAPTURE_WINDOW"
    complete_days = max(0, int((current - evidence_start).total_seconds() // 86400))
    expected = min(target, complete_days)
    accepting = lifecycle_status not in {
        "CLOSED",
        "READY_FOR_HYPOTHESIS",
        "READY_FOR_OOS",
    } and count < target
    return {
        "status": status,
        "observation_count": count,
        "minimum_observations": minimum,
        "required_window_observations": target,
        "expected_observations": expected,
        "lag_observations": max(0, expected - count),
        "chain_head": previous_chain,
        "next_observation_day": next_start.date().isoformat() if accepting else None,
        "next_capture_deadline": _iso_utc(deadline) if accepting else None,
        "coverage_start": _iso_utc(evidence_start) if count else None,
        "coverage_end": _iso_utc(next_start) if count else None,
        "source_contract": (
            None
            if source_contract is None
            else {
                "producer": source_contract["producer"],
                "transport": source_contract["transport"],
                "sha256": state["evidence_source_contract"]["sha256"],
            }
        ),
    }


def seal_deterministic_evidence_review(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
    *,
    now: datetime | None = None,
    capture_max_lag_seconds: int = 21600,
) -> dict[str, Any]:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    lifecycle_status = str(state.get("status"))
    if lifecycle_status in {"READY_FOR_HYPOTHESIS", "READY_FOR_OOS"}:
        return {
            "status": (
                "CANDIDATE_OOS_REVIEW_ALREADY_READY"
                if lifecycle_status == "READY_FOR_OOS"
                else "EVIDENCE_REVIEW_ALREADY_READY"
            ),
            "trigger_id": trigger["trigger_id"],
            "review_count": state.get("review_count", 0),
            "evidence_ready_at": state.get("evidence_ready_at")
            or state.get("oos_ready_at"),
            "diagnostic_summary": (state.get("detail") or {}).get("diagnostic_summary"),
        }
    if lifecycle_status == "CLOSED":
        raise ValueError("closed evidence trigger cannot be reviewed")
    progress = evidence_progress(
        store,
        trigger,
        state,
        now=current,
        capture_max_lag_seconds=capture_max_lag_seconds,
    )
    if progress["status"] != "COMPLETE":
        raise ValueError(
            "deterministic evidence review requires the complete frozen observation window"
        )
    review_not_before = parse_timestamp(
        str(trigger["review_not_before"]), "review_not_before"
    ).astimezone(timezone.utc)
    if current < review_not_before:
        raise ValueError("deterministic evidence review is not due")
    required_checks = [str(item) for item in trigger["required_integrity_checks"]]
    unsupported = sorted(set(required_checks).difference(DETERMINISTIC_COMPLETE_DAY_CHECKS))
    if unsupported:
        raise ValueError(
            "deterministic evidence review has unsupported integrity checks: "
            + ", ".join(unsupported)
        )

    references = state.get("evidence_observations")
    if not isinstance(references, list) or not references:
        raise ValueError("deterministic evidence review requires sealed observations")
    received_times = [
        parse_timestamp(str(reference.get("received_at")), "observation received_at").astimezone(
            timezone.utc
        )
        for reference in references
    ]
    evidence_ready_at = max(review_not_before, max(received_times))
    ready_text = _iso_utc(evidence_ready_at)
    sequence = int(state.get("review_count", 0)) + 1
    artifact_dir = (
        store.evidence_trigger_dir(str(trigger["trigger_id"]))
        / "review-artifacts"
        / f"{sequence:03d}"
    )

    dataset_observations: list[dict[str, Any]] = []
    manifest_observations: list[dict[str, Any]] = []
    for reference in references:
        day = str(reference["day"])
        path = (store.root / str(reference["path"])).resolve()
        stored = read_json(path)
        day_start = datetime.combine(date.fromisoformat(day), time.min, tzinfo=timezone.utc)
        day_end = day_start + timedelta(days=1)
        dataset_observations.append(
            {
                "observation_id": day,
                "start_at": _iso_utc(day_start),
                "end_at": _iso_utc(day_end),
                "source_row_count": len(stored["bars"]),
                "artifact_path": str(reference["path"]),
                "artifact_sha256": str(reference["sha256"]),
                "chain_head": str(reference["chain_head"]),
                "received_at": str(reference["received_at"]),
                "bars": stored["bars"],
            }
        )
        manifest_observations.append(
            {
                "observation_id": day,
                "start_at": _iso_utc(day_start),
                "end_at": _iso_utc(day_end),
                "source_row_count": len(stored["bars"]),
            }
        )

    coverage_start = str(progress["coverage_start"])
    coverage_end = str(progress["coverage_end"])
    purpose = str(trigger.get("purpose", "HYPOTHESIS_DISCOVERY"))
    is_candidate_oos = purpose == "CANDIDATE_OOS"
    if is_candidate_oos:
        binding = trigger.get("candidate_binding")
        if not isinstance(binding, dict):
            raise ValueError("candidate OOS trigger is missing its candidate binding")
        experiment_id = str(binding["experiment_id"])
        candidate_manifest_path = store.experiment_dir(experiment_id) / "manifest.json"
        if (
            not candidate_manifest_path.is_file()
            or sha256_file(candidate_manifest_path) != binding["manifest_sha256"]
        ):
            raise ValueError("candidate OOS manifest changed or disappeared")
        candidate_manifest = read_json(candidate_manifest_path)
        adapter_config = candidate_manifest.get("adapter_config")
        if (
            candidate_manifest.get("adapter") != binding["adapter"]
            or not isinstance(adapter_config, dict)
            or adapter_config.get("mechanism_key") != binding["mechanism_key"]
        ):
            raise ValueError("candidate OOS binding no longer matches the frozen manifest")

    dataset = {
        "schema_version": "1",
        "dataset_type": (
            "CANDIDATE_OOS_FORWARD_EVIDENCE"
            if is_candidate_oos
            else "MECHANISM_NEUTRAL_FORWARD_EVIDENCE"
        ),
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "observation_unit": trigger["observation_unit"],
        "coverage_start": coverage_start,
        "coverage_end": coverage_end,
        "observation_count": len(dataset_observations),
        "source_row_count": sum(
            int(item["source_row_count"]) for item in dataset_observations
        ),
        "chain_head": progress["chain_head"],
        "observations": dataset_observations,
        "created_at": ready_text,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    if is_candidate_oos:
        dataset["candidate_binding"] = trigger["candidate_binding"]
    dataset_path = artifact_dir / "dataset.json"
    _seal_or_verify_json(dataset_path, dataset)
    diagnostic = (
        {
            "schema_version": "1",
            "diagnostic_type": "CANDIDATE_OOS_SEAL_ONLY",
            "trigger_id": trigger["trigger_id"],
            "trigger_fingerprint": trigger["fingerprint"],
            "coverage_start": coverage_start,
            "coverage_end": coverage_end,
            "observation_count": len(dataset_observations),
            "candidate_binding": trigger["candidate_binding"],
            "guardrails": {
                "strategy_performance_evaluated": False,
                "market_path_summary_exposed": False,
                "candidate_threshold_changed": False,
                "oos_opened": False,
            },
            "created_at": ready_text,
            "authorization": RESEARCH_AUTHORIZATION,
        }
        if is_candidate_oos
        else _mechanism_neutral_diagnostic(dataset, trigger)
    )
    diagnostic_path = artifact_dir / "diagnostic.json"
    _seal_or_verify_json(diagnostic_path, diagnostic)

    integrity_evidence = {
        "closed_bar_causality": (
            f"All {len(dataset_observations)} observations contain only complete UTC hours "
            f"sealed after day close and within {capture_max_lag_seconds} seconds."
        ),
        "no_gap_or_duplicate_complete_hours": (
            f"{len(dataset_observations)} contiguous UTC days and "
            f"{dataset['source_row_count']} unique hourly positions were revalidated."
        ),
        "no_gap_or_duplicate": (
            f"{len(dataset_observations)} contiguous UTC days and "
            f"{dataset['source_row_count']} unique hourly positions were revalidated."
        ),
        "immutable_row_count_and_sha256": (
            f"Every sealed day artifact was rehashed and the cumulative chain head is "
            f"{progress['chain_head']}."
        ),
        "mechanism_neutral_diagnostic_before_strategy_mapping": (
            "The sealed diagnostic contains market-path and distribution statistics only; "
            "strategy mapping and strategy PnL were not evaluated."
        ),
        "new_hypothesis_fingerprint_not_in_closed_tree": (
            "No hypothesis was selected by this review; later candidate registration "
            "enforces fingerprint deduplication against the canonical hypothesis tree."
        ),
        "candidate_manifest_frozen_before_oos_start": (
            "The candidate manifest hash and mechanism binding were reverified before "
            "the sealed OOS dataset was made runnable."
        ),
    }
    manifest = {
        "schema_version": "1",
        "manifest_type": MANIFEST_TYPE,
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "observation_unit": trigger["observation_unit"],
        "coverage_start": coverage_start,
        "coverage_end": coverage_end,
        "observations": manifest_observations,
        "dataset_artifact": {
            "path": _relative_path(store, dataset_path),
            "sha256": sha256_file(dataset_path),
        },
        "diagnostic_artifact": {
            "path": _relative_path(store, diagnostic_path),
            "sha256": sha256_file(diagnostic_path),
        },
        "integrity_checks": [
            {"name": name, "status": "PASS", "evidence": integrity_evidence[name]}
            for name in required_checks
        ],
        "created_at": ready_text,
        "authorization": RESEARCH_AUTHORIZATION,
    }
    manifest_path = artifact_dir / "evidence-manifest.json"
    _seal_or_verify_json(manifest_path, manifest)
    verified_manifest = validate_evidence_manifest(manifest, trigger, store)

    summary = diagnostic.get("summary")
    eligible_mechanisms = diagnostic.get("eligible_mechanisms", [])
    diagnostic_summary = summary
    if (
        not is_candidate_oos
        and int(trigger["minimum_observations"])
        >= int(load_diagnostic_contract()["minimum_observations"])
    ):
        diagnostic_summary = {
            **(summary if isinstance(summary, dict) else {}),
            "diagnostic_contract_id": diagnostic.get("diagnostic_contract_id"),
            "diagnostic_contract_sha256": diagnostic.get(
                "diagnostic_contract_sha256"
            ),
            "mechanism_results": diagnostic.get("mechanism_results", []),
            "eligible_mechanisms": eligible_mechanisms,
        }
    if is_candidate_oos:
        outcome = "READY_FOR_OOS"
        conclusion = (
            f"{len(dataset_observations)} untouched candidate OOS days passed the frozen "
            "integrity review. No market-path summary or strategy performance was exposed."
        )
    elif int(trigger["minimum_observations"]) >= int(
        load_diagnostic_contract()["minimum_observations"]
    ) and not eligible_mechanisms:
        outcome = "CLOSE"
        conclusion = (
            f"{len(dataset_observations)} prospective complete UTC days passed integrity, "
            "but no preregistered market mechanism passed every independent predictive "
            "gate. No strategy hypothesis is authorized from this window."
        )
    else:
        outcome = "READY_FOR_HYPOTHESIS"
        conclusion = (
            f"{len(dataset_observations)} prospective complete UTC days passed the frozen "
            f"integrity review. Market close-path return was "
            f"{summary['market_close_path_return_pct']}%, maximum close-path drawdown was "
            f"{summary['maximum_close_path_drawdown_pct']}%, and no strategy PnL or "
            "hypothesis mapping was evaluated."
        )
    review = build_evidence_review(
        {
            "schema_version": "1",
            "trigger_id": trigger["trigger_id"],
            "reviewed_at": ready_text,
            "outcome": outcome,
            "conclusion": conclusion,
            "evidence_artifacts": [
                {
                    "path": _relative_path(store, manifest_path),
                    "sha256": sha256_file(manifest_path),
                    "artifact_type": MANIFEST_TYPE,
                }
            ],
            "authorization": RESEARCH_AUTHORIZATION,
        },
        now=evidence_ready_at,
    )
    review_path = store.evidence_review_dir(str(trigger["trigger_id"])) / f"{sequence:03d}.json"
    _seal_or_verify_json(review_path, review)
    state["review_count"] = sequence
    state.setdefault("reviews", []).append(
        {
            "path": _relative_path(store, review_path),
            "sha256": sha256_file(review_path),
            "outcome": outcome,
        }
    )
    state["detail"] = {
        "conclusion": conclusion,
        "verified_evidence": [verified_manifest],
        "diagnostic_summary": diagnostic_summary,
        "eligible_mechanisms": eligible_mechanisms,
    }
    state["status"] = (
        "READY_FOR_OOS"
        if outcome == "READY_FOR_OOS"
        else ("READY_FOR_HYPOTHESIS" if outcome == "READY_FOR_HYPOTHESIS" else "CLOSED")
    )
    state["next_review_at"] = None
    if outcome == "READY_FOR_OOS":
        state["oos_ready_at"] = ready_text
    elif outcome == "READY_FOR_HYPOTHESIS":
        state["evidence_ready_at"] = ready_text
    store.save_evidence_trigger_state(state)
    store.append_evidence_trigger_event(
        str(trigger["trigger_id"]),
        "DETERMINISTIC_EVIDENCE_REVIEW_RECORDED",
        {
            "sequence": sequence,
            "outcome": outcome,
            "review_path": _relative_path(store, review_path),
            "review_sha256": sha256_file(review_path),
            "evidence_ready_at": ready_text if outcome == "READY_FOR_HYPOTHESIS" else None,
            "oos_ready_at": ready_text if outcome == "READY_FOR_OOS" else None,
        },
    )
    return {
        "status": (
            "CANDIDATE_OOS_READY"
            if outcome == "READY_FOR_OOS"
            else (
                "EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS"
                if outcome == "READY_FOR_HYPOTHESIS"
                else "NO_CANDIDATE_FORWARD_DIAGNOSTIC"
            )
        ),
        "trigger_id": trigger["trigger_id"],
        "review_count": sequence,
        "artifact_path": _relative_path(store, review_path),
        "sha256": sha256_file(review_path),
        "evidence_ready_at": ready_text if outcome == "READY_FOR_HYPOTHESIS" else None,
        "oos_ready_at": ready_text if outcome == "READY_FOR_OOS" else None,
        "diagnostic_summary": diagnostic_summary,
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _mechanism_neutral_diagnostic(
    dataset: dict[str, Any], trigger: dict[str, Any]
) -> dict[str, Any]:
    daily: list[dict[str, Any]] = []
    for observation in dataset["observations"]:
        bars = observation["bars"]
        daily.append(
            {
                "day": str(observation["observation_id"]),
                "open": Decimal(str(bars[0]["open"])),
                "high": max(Decimal(str(bar["high"])) for bar in bars),
                "low": min(Decimal(str(bar["low"])) for bar in bars),
                "close": Decimal(str(bars[-1]["close"])),
                "volume": sum((Decimal(str(bar["volume"])) for bar in bars), Decimal("0")),
            }
        )
    closes = [item["close"] for item in daily]
    close_returns = [
        (closes[index] / closes[index - 1] - Decimal("1")) * Decimal("100")
        for index in range(1, len(closes))
    ]
    if not close_returns:
        close_returns = [Decimal("0")]
    ranges = [
        (item["high"] - item["low"]) / item["open"] * Decimal("100")
        for item in daily
    ]
    volumes = [item["volume"] for item in daily]
    peak = closes[0]
    maximum_drawdown = Decimal("0")
    underwater = 0
    maximum_underwater = 0
    for close in closes:
        if close >= peak:
            peak = close
            underwater = 0
        else:
            underwater += 1
            maximum_underwater = max(maximum_underwater, underwater)
            maximum_drawdown = max(
                maximum_drawdown,
                (peak - close) / peak * Decimal("100"),
            )
    path_return = (closes[-1] / closes[0] - Decimal("1")) * Decimal("100")
    summary = {
        "market_close_path_return_pct": _metric_text(path_return),
        "maximum_close_path_drawdown_pct": _metric_text(maximum_drawdown),
        "maximum_underwater_days": maximum_underwater,
        "positive_close_return_days": sum(value > 0 for value in close_returns),
        "negative_close_return_days": sum(value < 0 for value in close_returns),
        "flat_close_return_days": sum(value == 0 for value in close_returns),
        "median_close_to_close_return_pct": _metric_text(_percentile(close_returns, 50)),
        "p10_close_to_close_return_pct": _metric_text(_percentile(close_returns, 10)),
        "p90_close_to_close_return_pct": _metric_text(_percentile(close_returns, 90)),
        "median_intraday_range_pct": _metric_text(_percentile(ranges, 50)),
        "p90_intraday_range_pct": _metric_text(_percentile(ranges, 90)),
        "median_daily_volume": _metric_text(_percentile(volumes, 50)),
        "p90_daily_volume": _metric_text(_percentile(volumes, 90)),
    }
    contract = load_diagnostic_contract()
    mechanism_results = _forward_mechanism_results(daily, contract)
    eligible = [
        item for item in mechanism_results if item["all_predictive_gates_pass"]
    ]
    eligible.sort(
        key=lambda item: (
            -Decimal(str(item["statistics"]["median_next_day_return_delta_pct"])),
            -int(item["statistics"]["labeled_event_count"]),
            str(item["mechanism_key"]),
        )
    )
    eligible = eligible[: int(contract["selection_rule"]["maximum_selected_mechanisms"])]
    return {
        "schema_version": "1",
        "diagnostic_type": "MECHANISM_NEUTRAL_FORWARD_MARKET_PATH",
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "coverage_start": dataset["coverage_start"],
        "coverage_end": dataset["coverage_end"],
        "observation_count": dataset["observation_count"],
        "source_row_count": dataset["source_row_count"],
        "chain_head": dataset["chain_head"],
        "diagnostic_contract_id": contract["contract_id"],
        "diagnostic_contract_sha256": sha256_file(DIAGNOSTIC_CONTRACT_PATH),
        "summary": summary,
        "mechanism_results": mechanism_results,
        "eligible_mechanisms": eligible,
        "guardrails": {
            "mechanism_neutral": True,
            "strategy_performance_evaluated": False,
            "hypothesis_selected": False,
            "discovery_window_is_clean_oos": False,
            "prohibited_inferences": trigger["prohibited_inferences"],
            "excluded_branches": trigger["excluded_branches"],
        },
        "created_at": dataset["created_at"],
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _forward_mechanism_results(
    daily: list[dict[str, Any]],
    contract: dict[str, Any],
) -> list[dict[str, Any]]:
    gates_contract = contract["predictive_gates"]
    results: list[dict[str, Any]] = []
    for mechanism in contract["mechanisms"]:
        lookback = int(mechanism["lookback_days"])
        threshold = Decimal(str(mechanism["thresholds"]["primary"]))
        labeled: list[dict[str, Any]] = []
        for index in range(lookback, len(daily) - 1):
            current = daily[index]
            history = daily[index - lookback : index]
            if mechanism["feature"] == "DAILY_VOLUME_TO_PRIOR_20D_MEDIAN":
                denominator = _decimal_median([Decimal(str(item["volume"])) for item in history])
                numerator = Decimal(str(current["volume"]))
            elif mechanism["feature"] == "DAILY_RANGE_PCT_TO_PRIOR_20D_MEDIAN":
                historical_ranges = [
                    (Decimal(str(item["high"])) - Decimal(str(item["low"])))
                    / Decimal(str(item["open"]))
                    for item in history
                ]
                denominator = _decimal_median(historical_ranges)
                numerator = (
                    Decimal(str(current["high"])) - Decimal(str(current["low"]))
                ) / Decimal(str(current["open"]))
            else:
                raise ValueError(f"unsupported forward diagnostic feature: {mechanism['feature']}")
            ratio = Decimal("0") if denominator <= 0 else numerator / denominator
            next_return = (
                Decimal(str(daily[index + 1]["close"]))
                / Decimal(str(current["close"]))
                - Decimal("1")
            ) * Decimal("100")
            labeled.append(
                {
                    "event": ratio >= threshold,
                    "ratio": ratio,
                    "next_return": next_return,
                    "month": str(daily[index + 1]["day"])[:7],
                }
            )
        events = [item for item in labeled if item["event"]]
        non_events = [item for item in labeled if not item["event"]]
        midpoint = len(labeled) // 2
        first_events = [item for item in labeled[:midpoint] if item["event"]]
        first_non_events = [item for item in labeled[:midpoint] if not item["event"]]
        second_events = [item for item in labeled[midpoint:] if item["event"]]
        second_non_events = [item for item in labeled[midpoint:] if not item["event"]]

        event_median = _optional_median([item["next_return"] for item in events])
        non_event_median = _optional_median([item["next_return"] for item in non_events])
        delta = (
            event_median - non_event_median
            if event_median is not None and non_event_median is not None
            else None
        )
        first_delta = _median_delta(first_events, first_non_events)
        second_delta = _median_delta(second_events, second_non_events)
        coverage = (
            Decimal(len(events)) / Decimal(len(labeled)) * Decimal("100")
            if labeled
            else Decimal("0")
        )
        positive_share = (
            Decimal(sum(item["next_return"] > 0 for item in events))
            / Decimal(len(events))
            * Decimal("100")
            if events
            else Decimal("0")
        )
        month_positive: dict[str, Decimal] = {}
        for item in events:
            if item["next_return"] > 0:
                month_positive[item["month"]] = month_positive.get(
                    item["month"], Decimal("0")
                ) + item["next_return"]
        positive_total = sum(month_positive.values(), Decimal("0"))
        top_month = (
            max(month_positive.values()) / positive_total * Decimal("100")
            if positive_total > 0
            else Decimal("100")
        )
        gates = {
            "minimum_observations": len(daily) >= int(contract["minimum_observations"]),
            "minimum_labeled_events": len(events)
            >= int(gates_contract["minimum_labeled_events"]),
            "minimum_events_per_half": len(first_events)
            >= int(gates_contract["minimum_events_per_half"])
            and len(second_events) >= int(gates_contract["minimum_events_per_half"]),
            "event_coverage_within_bounds": coverage
            >= Decimal(str(gates_contract["minimum_event_coverage_pct"]))
            and coverage <= Decimal(str(gates_contract["maximum_event_coverage_pct"])),
            "median_next_day_return_delta": delta is not None
            and delta
            >= Decimal(str(gates_contract["minimum_median_next_day_return_delta_pct"])),
            "positive_next_day_share": positive_share
            >= Decimal(str(gates_contract["minimum_positive_next_day_share_pct"])),
            "top_month_positive_contribution": top_month
            <= Decimal(str(gates_contract["maximum_top_month_positive_contribution_pct"])),
            "positive_first_half_delta": first_delta is not None and first_delta > 0,
            "positive_second_half_delta": second_delta is not None and second_delta > 0,
        }
        statistics = {
            "labeled_day_count": len(labeled),
            "labeled_event_count": len(events),
            "first_half_event_count": len(first_events),
            "second_half_event_count": len(second_events),
            "event_coverage_pct": _metric_text(coverage),
            "median_event_next_day_return_pct": _optional_metric(event_median),
            "median_non_event_next_day_return_pct": _optional_metric(non_event_median),
            "median_next_day_return_delta_pct": _optional_metric(delta),
            "first_half_median_delta_pct": _optional_metric(first_delta),
            "second_half_median_delta_pct": _optional_metric(second_delta),
            "positive_event_next_day_share_pct": _metric_text(positive_share),
            "top_month_positive_contribution_pct": _metric_text(top_month),
        }
        results.append(
            {
                "mechanism_key": mechanism["key"],
                "feature": mechanism["feature"],
                "lookback_days": lookback,
                "thresholds": mechanism["thresholds"],
                "statistics": statistics,
                "predictive_gates": gates,
                "all_predictive_gates_pass": all(gates.values()),
            }
        )
    return results


def _decimal_median(values: list[Decimal]) -> Decimal:
    ordered = sorted(values)
    midpoint = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[midpoint]
    return (ordered[midpoint - 1] + ordered[midpoint]) / Decimal("2")


def _optional_median(values: list[Decimal]) -> Decimal | None:
    return _decimal_median(values) if values else None


def _median_delta(
    events: list[dict[str, Any]],
    non_events: list[dict[str, Any]],
) -> Decimal | None:
    event = _optional_median([item["next_return"] for item in events])
    non_event = _optional_median([item["next_return"] for item in non_events])
    return event - non_event if event is not None and non_event is not None else None


def _optional_metric(value: Decimal | None) -> str | None:
    return None if value is None else _metric_text(value)


def _percentile(values: list[Decimal], percentile: int) -> Decimal:
    ordered = sorted(values)
    index = (len(ordered) - 1) * percentile // 100
    return ordered[index]


def _metric_text(value: Decimal) -> str:
    return _decimal_text(value.quantize(Decimal("0.00000001")))


def _seal_or_verify_json(path: Path, value: dict[str, Any]) -> None:
    if path.exists():
        if read_json(path) != value:
            raise ValueError(f"sealed deterministic review artifact differs: {path}")
        return
    atomic_write_json(path, value)


def _relative_path(store: ResearchStore, path: Path) -> str:
    return str(path.resolve().relative_to(store.root)).replace("\\", "/")


def verify_evidence_source_contract(
    store: ResearchStore,
    trigger: dict[str, Any],
    state: dict[str, Any],
) -> dict[str, Any] | None:
    reference = state.get("evidence_source_contract")
    if reference is None:
        return None
    if not isinstance(reference, dict):
        raise ValueError("evidence source contract state reference must be an object")
    path = (store.root / str(reference.get("path", ""))).resolve()
    try:
        path.relative_to(store.root)
    except ValueError as error:
        raise ValueError("evidence source contract path escapes research state") from error
    if not path.is_file() or sha256_file(path) != reference.get("sha256"):
        raise ValueError("sealed evidence source contract changed or disappeared")
    contract = read_json(path)
    registered_at = parse_timestamp(str(contract.get("registered_at")), "registered_at")
    normalized = validate_evidence_source_contract(
        {key: value for key, value in contract.items() if key != "registered_at"},
        trigger,
        registered_at=registered_at,
    )
    if normalized != contract:
        raise ValueError("sealed evidence source contract is not canonical")
    if reference.get("producer") != contract["producer"]:
        raise ValueError("evidence source contract producer reference mismatch")
    return contract


def validate_evidence_manifest(
    value: dict[str, Any],
    trigger: dict[str, Any],
    store: ResearchStore,
) -> dict[str, Any]:
    required = {
        "schema_version",
        "manifest_type",
        "trigger_id",
        "trigger_fingerprint",
        "source",
        "observation_unit",
        "coverage_start",
        "coverage_end",
        "observations",
        "dataset_artifact",
        "diagnostic_artifact",
        "integrity_checks",
        "created_at",
        "authorization",
    }
    missing = sorted(required.difference(value))
    if missing:
        raise ValueError(f"evidence manifest missing fields: {', '.join(missing)}")
    if value["schema_version"] != "1":
        raise ValueError("evidence manifest schema_version must be 1")
    if value["manifest_type"] != MANIFEST_TYPE:
        raise ValueError(f"evidence manifest manifest_type must be {MANIFEST_TYPE}")
    if value["authorization"] != RESEARCH_AUTHORIZATION:
        raise ValueError("evidence manifest authorization must remain research-only")
    if value["trigger_id"] != trigger["trigger_id"]:
        raise ValueError("evidence manifest trigger_id mismatch")
    if value["trigger_fingerprint"] != trigger["fingerprint"]:
        raise ValueError("evidence manifest trigger fingerprint mismatch")
    if value["source"] != trigger["source"]:
        raise ValueError("evidence manifest source mismatch")
    if value["observation_unit"] != trigger["observation_unit"]:
        raise ValueError("evidence manifest observation_unit mismatch")

    coverage_start = parse_timestamp(str(value["coverage_start"]), "coverage_start")
    coverage_end = parse_timestamp(str(value["coverage_end"]), "coverage_end")
    evidence_start = parse_timestamp(str(trigger["evidence_start"]), "evidence_start")
    review_not_before = parse_timestamp(
        str(trigger["review_not_before"]), "review_not_before"
    )
    created_at = parse_timestamp(str(value["created_at"]), "created_at")
    if coverage_start != evidence_start:
        raise ValueError("evidence manifest coverage_start must equal trigger evidence_start")
    if coverage_end < review_not_before:
        raise ValueError("evidence manifest does not reach trigger review_not_before")
    if coverage_end <= coverage_start:
        raise ValueError("evidence manifest coverage_end must follow coverage_start")
    if created_at < coverage_end:
        raise ValueError("evidence manifest created_at must not precede coverage_end")
    if created_at > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise ValueError("evidence manifest created_at must not be in the future")

    observations = value["observations"]
    if not isinstance(observations, list):
        raise ValueError("evidence manifest observations must be a list")
    minimum = int(trigger["minimum_observations"])
    if len(observations) < minimum:
        raise ValueError(
            f"evidence manifest has {len(observations)} observations; {minimum} required"
        )
    normalized_observations = _validate_observations(
        observations,
        observation_unit=str(trigger["observation_unit"]),
        coverage_start=coverage_start,
        coverage_end=coverage_end,
    )

    dataset_artifact = _validate_artifact(
        value["dataset_artifact"], "dataset_artifact", store
    )
    diagnostic_artifact = _validate_artifact(
        value["diagnostic_artifact"], "diagnostic_artifact", store
    )
    integrity_checks = _validate_integrity_checks(
        value["integrity_checks"], trigger["required_integrity_checks"]
    )
    return {
        "schema_version": "1",
        "manifest_type": MANIFEST_TYPE,
        "trigger_id": trigger["trigger_id"],
        "trigger_fingerprint": trigger["fingerprint"],
        "source": trigger["source"],
        "observation_unit": trigger["observation_unit"],
        "coverage_start": str(value["coverage_start"]),
        "coverage_end": str(value["coverage_end"]),
        "observation_count": len(normalized_observations),
        "minimum_observations": minimum,
        "dataset_artifact": dataset_artifact,
        "diagnostic_artifact": diagnostic_artifact,
        "integrity_checks": integrity_checks,
        "created_at": str(value["created_at"]),
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _validate_observations(
    observations: list[Any],
    *,
    observation_unit: str,
    coverage_start: datetime,
    coverage_end: datetime,
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    previous_end: datetime | None = None
    for index, item in enumerate(observations):
        if not isinstance(item, dict):
            raise ValueError(f"observations[{index}] must be an object")
        observation_id = str(item.get("observation_id", "")).strip()
        if not observation_id:
            raise ValueError(f"observations[{index}].observation_id must not be blank")
        if observation_id in seen:
            raise ValueError(f"duplicate observation_id: {observation_id}")
        seen.add(observation_id)
        start = parse_timestamp(str(item.get("start_at", "")), f"observations[{index}].start_at")
        end = parse_timestamp(str(item.get("end_at", "")), f"observations[{index}].end_at")
        try:
            source_row_count = int(item["source_row_count"])
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(
                f"observations[{index}].source_row_count must be a positive integer"
            ) from error
        if source_row_count < 1:
            raise ValueError(f"observations[{index}].source_row_count must be positive")
        if start < coverage_start or end > coverage_end or end <= start:
            raise ValueError(f"observations[{index}] falls outside manifest coverage")
        if previous_end is not None and start != previous_end:
            raise ValueError(f"observations[{index}] is not contiguous with its predecessor")
        if observation_unit == "COMPLETE_UTC_DAY":
            if start.tzinfo != timezone.utc or end.tzinfo != timezone.utc:
                raise ValueError(f"observations[{index}] must use UTC timestamps")
            if start.time() != datetime.min.time() or end - start != timedelta(days=1):
                raise ValueError(f"observations[{index}] is not one complete UTC day")
            if source_row_count != 24:
                raise ValueError(
                    f"observations[{index}] must contain 24 complete source hours"
                )
        normalized.append(
            {
                "observation_id": observation_id,
                "start_at": str(item.get("start_at")),
                "end_at": str(item.get("end_at")),
                "source_row_count": source_row_count,
            }
        )
        previous_end = end
    if normalized:
        first = parse_timestamp(normalized[0]["start_at"], "first observation start")
        last = parse_timestamp(normalized[-1]["end_at"], "last observation end")
        if first != coverage_start or last != coverage_end:
            raise ValueError("observations must exactly cover the declared coverage window")
    return normalized


def _validate_artifact(value: Any, field: str, store: ResearchStore) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    relative = str(value.get("path", "")).strip()
    expected_hash = str(value.get("sha256", "")).strip().lower()
    if not relative or not SHA256.fullmatch(expected_hash):
        raise ValueError(f"{field} requires path and lowercase sha256")
    path = (store.root / relative).resolve()
    try:
        path.relative_to(store.root)
    except ValueError as error:
        raise ValueError(f"{field} must stay inside research state") from error
    if not path.is_file():
        raise ValueError(f"{field} does not exist: {relative}")
    if sha256_file(path) != expected_hash:
        raise ValueError(f"{field} hash mismatch: {relative}")
    return {"path": relative, "sha256": expected_hash}


def _validate_integrity_checks(
    value: Any, required_checks: list[Any]
) -> list[dict[str, str]]:
    if not isinstance(value, list):
        raise ValueError("integrity_checks must be a list")
    normalized: list[dict[str, str]] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise ValueError(f"integrity_checks[{index}] must be an object")
        name = str(item.get("name", "")).strip()
        status = str(item.get("status", "")).strip()
        evidence = str(item.get("evidence", "")).strip()
        if not name or status != "PASS" or not evidence:
            raise ValueError(
                f"integrity_checks[{index}] requires name, PASS status, and evidence"
            )
        normalized.append({"name": name, "status": "PASS", "evidence": evidence})
    names = [item["name"] for item in normalized]
    required = [str(item) for item in required_checks]
    if len(names) != len(set(names)):
        raise ValueError("integrity_checks must not contain duplicate names")
    if set(names) != set(required):
        missing = sorted(set(required).difference(names))
        extra = sorted(set(names).difference(required))
        raise ValueError(
            f"integrity_checks must exactly match trigger requirements; missing={missing} extra={extra}"
        )
    return normalized


def _decimal(
    item: dict[str, Any],
    field: str,
    index: int,
    *,
    positive: bool,
) -> Decimal:
    try:
        value = Decimal(str(item[field]))
    except (KeyError, InvalidOperation, ValueError) as error:
        raise ValueError(f"bars[{index}].{field} must be a finite decimal") from error
    if not value.is_finite():
        raise ValueError(f"bars[{index}].{field} must be finite")
    if positive and value <= 0:
        raise ValueError(f"bars[{index}].{field} must be positive")
    if not positive and value < 0:
        raise ValueError(f"bars[{index}].{field} must not be negative")
    return value


def _decimal_text(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
