from __future__ import annotations

from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.microstructure_handoff import (
    HANDOFF_CANONICALIZATION,
    INFERENCE_BOUNDARIES,
    MANIFEST_NAME,
    PACKAGE_FILE_COUNT,
    HandoffContext,
    HandoffContractError,
    _check_inventory_entry,
    _exact_keys,
    _load_canonical_object,
    _object,
    _safe_relative_name,
    _validate_seal,
    create_result_once as _create_result_once,
    validate_handoff_result_bytes as _validate_handoff_result_bytes,
)
from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    AUTHORIZATION,
    BINDING_SCHEMA_VERSION,
    CALENDAR_DAY_BUDGET,
    COMPLETE_SCHEMA_SHA256,
    CONTRACT_SHA256,
    REJECTION_SCHEMA_SHA256,
    REQUIRED_STREAK_DAYS,
    SELECTION_RULE,
    STATE_SCHEMA_SHA256,
    load_canonical_intake_state_bytes,
    validate_complete_envelope,
    validate_frozen_files,
    validate_source_binding,
)
from research_pipeline.microstructure_source_contract import (
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    canonical_json_bytes,
    validate_v3_day_bundle,
    validate_v3_frozen_contract_files,
)


MANIFEST_TYPE = "MICROSTRUCTURE_DISCOVERY_V3R1_CREATE_ONLY_HANDOFF_MANIFEST"
MANIFEST_SCHEMA_VERSION = "3R1"
MANIFEST_SCHEMA_NAME = "microstructure-discovery-handoff-manifest.v3r1.schema.json"
MANIFEST_SCHEMA_SHA256 = (
    "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617"
)
RESULT_SCHEMA_NAME = "microstructure-handoff-result.v3r1.schema.json"
RESULT_SCHEMA_SHA256 = (
    "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9"
)
REQUIRED_DAYS = 14

_PACKAGE_ROOT = Path(__file__).resolve().parent
_MANIFEST_KEYS = {
    "schema_version",
    "manifest_type",
    "authorization",
    "task_id",
    "task_sha256",
    "canonical_state",
    "source_release",
    "days",
    "missingness",
    "inference_boundaries",
    "seal",
}
_STATE_KEYS = {
    "relative_name",
    "sha256",
    "intake_state_schema_sha256",
    "state_type",
    "state_authority",
    "generation_id",
    "diagnostic_id",
    "status",
    "calendar_start_day",
    "calendar_end_day",
    "selected_start_day",
    "selected_last_day",
    "required_day_count",
    "selected_day_count",
    "calendar_disposition_count",
    "calendar_chain_head_sha256",
    "selected_streak_chain_head_sha256",
    "recovery_contract_sha256",
    "day_schema_sha256",
    "diagnostic_contract_sha256",
}
_SOURCE_KEYS = {
    "producer_identity",
    "producer_release_id",
    "producer_manifest_sha256",
}
_DAY_KEYS = {
    "day",
    "bundle_relative_name",
    "bundle_sha256",
    "envelope_relative_name",
    "envelope_sha256",
    "accepted_at",
}
_MISSINGNESS_KEYS = {
    "selection_rule",
    "calendar_disposition_count",
    "rejected_day_count",
    "rejected_reason_counts",
    "nonselected_complete_day_count",
    "rejected_days_as_market_input",
    "nonselected_prefixes_as_market_input",
}


def _fail(message: str) -> None:
    raise HandoffContractError(message)


def _schema() -> dict[str, Any]:
    path = _PACKAGE_ROOT / MANIFEST_SCHEMA_NAME
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != MANIFEST_SCHEMA_SHA256:
        _fail("V3R1 manifest schema bytes changed")
    try:
        value = json.loads(raw)
        Draft202012Validator.check_schema(value)
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise HandoffContractError("V3R1 manifest schema is invalid") from error
    return value


def _result_schema_bytes() -> bytes:
    raw = (_PACKAGE_ROOT / RESULT_SCHEMA_NAME).read_bytes()
    if hashlib.sha256(raw).hexdigest() != RESULT_SCHEMA_SHA256:
        _fail("V3R1 result schema bytes changed")
    return raw


def _fixed_names(day_text: str) -> tuple[str, str]:
    base = f"okx-btc-usdt-microstructure-{day_text}"
    return (
        f"days/{day_text}/{base}.json",
        f"days/{day_text}/{base}.complete.envelope.json",
    )


def load_manifest_bytes(
    raw: bytes, *, expected_task_id: str, expected_task_sha256: str
) -> dict[str, Any]:
    manifest = _load_canonical_object(raw, "V3R1 handoff manifest")
    _exact_keys(manifest, _MANIFEST_KEYS, "V3R1 handoff manifest")
    errors = sorted(
        Draft202012Validator(
            _schema(), format_checker=FormatChecker()
        ).iter_errors(manifest),
        key=lambda error: list(error.path),
    )
    if errors:
        _fail(f"V3R1 manifest schema failure: {errors[0].message}")
    if (
        manifest["schema_version"] != MANIFEST_SCHEMA_VERSION
        or manifest["manifest_type"] != MANIFEST_TYPE
        or manifest["authorization"] != AUTHORIZATION
        or manifest["task_id"] != expected_task_id
        or manifest["task_sha256"] != expected_task_sha256
        or manifest["inference_boundaries"] != INFERENCE_BOUNDARIES
    ):
        _fail("V3R1 manifest authority changed")
    if manifest["seal"]["canonicalization"] != HANDOFF_CANONICALIZATION:
        _fail("V3R1 manifest canonicalization changed")
    _validate_seal(manifest, "V3R1 handoff manifest")
    return manifest


def _binding_from_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    state = _object(manifest["canonical_state"], "canonical_state")
    source = _object(manifest["source_release"], "source_release")
    _exact_keys(state, _STATE_KEYS, "canonical_state")
    _exact_keys(source, _SOURCE_KEYS, "source_release")
    return validate_source_binding(
        {
            "schema_version": BINDING_SCHEMA_VERSION,
            "authorization": AUTHORIZATION,
            "generation_id": state["generation_id"],
            "diagnostic_id": state["diagnostic_id"],
            "recovery_contract_sha256": CONTRACT_SHA256,
            "v3_day_schema_sha256": V3_DAY_SCHEMA_SHA256,
            "v3_diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
            "complete_envelope_schema_sha256": COMPLETE_SCHEMA_SHA256,
            "rejection_envelope_schema_sha256": REJECTION_SCHEMA_SHA256,
            "intake_state_schema_sha256": STATE_SCHEMA_SHA256,
            "producer_release_id": source["producer_release_id"],
            "producer_manifest_sha256": source["producer_manifest_sha256"],
            "start_day": state["calendar_start_day"],
            "end_day": state["calendar_end_day"],
            "calendar_day_budget": CALENDAR_DAY_BUDGET,
            "required_consecutive_complete_days": REQUIRED_STREAK_DAYS,
            "selection_rule": SELECTION_RULE,
        }
    )


def _missingness_from_state(state: dict[str, Any]) -> dict[str, Any]:
    rejected = [
        item
        for item in state["calendar_dispositions"]
        if item["disposition"] == "SOURCE_LIVENESS_REJECTED"
    ]
    reasons: dict[str, int] = {}
    for item in rejected:
        reason = str(item["reason"])
        reasons[reason] = reasons.get(reason, 0) + 1
    nonselected = sum(len(prefix) for prefix in state["nonselected_complete_prefixes"])
    nonselected += len(state["current_streak"])
    return {
        "selection_rule": SELECTION_RULE,
        "calendar_disposition_count": len(state["calendar_dispositions"]),
        "rejected_day_count": len(rejected),
        "rejected_reason_counts": dict(sorted(reasons.items())),
        "nonselected_complete_day_count": nonselected,
        "rejected_days_as_market_input": False,
        "nonselected_prefixes_as_market_input": False,
    }


def expected_file_names(manifest: dict[str, Any]) -> tuple[str, ...]:
    names = [MANIFEST_NAME, manifest["canonical_state"]["relative_name"]]
    for record in manifest["days"]:
        names.extend((record["bundle_relative_name"], record["envelope_relative_name"]))
    if len(names) != PACKAGE_FILE_COUNT or len(set(names)) != PACKAGE_FILE_COUNT:
        _fail("V3R1 package names are not an exact 30-file inventory")
    return tuple(names)


def validate_handoff_package(
    task_owned_root: Path,
    inventory: Sequence[tuple[str, Path]],
    *,
    expected_task_id: str,
    expected_task_sha256: str,
) -> HandoffContext:
    if len(inventory) != PACKAGE_FILE_COUNT:
        _fail("V3R1 handoff package must declare exactly 30 inventory entries")
    by_name: dict[str, Path] = {}
    for raw_name, path in inventory:
        name = _safe_relative_name(raw_name, "inventory relative_name")
        if name in by_name:
            _fail(f"duplicate V3R1 inventory identity: {name}")
        by_name[name] = _check_inventory_entry(task_owned_root, name, path)
    if MANIFEST_NAME not in by_name:
        _fail("V3R1 handoff-manifest.json is missing")

    manifest_raw = by_name[MANIFEST_NAME].read_bytes()
    manifest = load_manifest_bytes(
        manifest_raw,
        expected_task_id=expected_task_id,
        expected_task_sha256=expected_task_sha256,
    )
    if set(by_name) != set(expected_file_names(manifest)):
        _fail("V3R1 package closure has extras, missing files, or duplicate identities")

    try:
        validate_frozen_files()
        validate_v3_frozen_contract_files()
    except ValueError as error:
        raise HandoffContractError(str(error)) from error
    binding = _binding_from_manifest(manifest)
    state_binding = manifest["canonical_state"]
    state_name = state_binding["relative_name"]
    state_raw = by_name[state_name].read_bytes()
    try:
        state = load_canonical_intake_state_bytes(state_raw, binding)
    except ValueError as error:
        raise HandoffContractError(str(error)) from error
    state_sha256 = hashlib.sha256(state_raw).hexdigest()
    selected = state["selected_streak"]
    if state["status"] != "DIAGNOSTIC_READY" or not isinstance(selected, list) or len(selected) != REQUIRED_DAYS:
        _fail("V3R1 canonical state is not an exact DIAGNOSTIC_READY selected streak")
    selected_start = date.fromisoformat(selected[0]["day"])
    selected_last = selected_start + timedelta(days=REQUIRED_DAYS - 1)
    expected_state_binding = {
        "relative_name": f"canonical/{binding['generation_id']}.json",
        "sha256": state_sha256,
        "intake_state_schema_sha256": STATE_SCHEMA_SHA256,
        "state_type": "SERVER_CANONICAL_MICROSTRUCTURE_DISCOVERY_V3R1_INTAKE",
        "state_authority": "SERVER_CANONICAL",
        "generation_id": binding["generation_id"],
        "diagnostic_id": binding["diagnostic_id"],
        "status": "DIAGNOSTIC_READY",
        "calendar_start_day": binding["start_day"],
        "calendar_end_day": binding["end_day"],
        "selected_start_day": selected_start.isoformat(),
        "selected_last_day": selected_last.isoformat(),
        "required_day_count": REQUIRED_DAYS,
        "selected_day_count": REQUIRED_DAYS,
        "calendar_disposition_count": len(state["calendar_dispositions"]),
        "calendar_chain_head_sha256": state["calendar_chain_head_sha256"],
        "selected_streak_chain_head_sha256": state["selected_streak_chain_head_sha256"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
        "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
    }
    if state_binding != expected_state_binding:
        _fail("V3R1 canonical-state manifest binding changed")
    missingness = _object(manifest["missingness"], "missingness")
    _exact_keys(missingness, _MISSINGNESS_KEYS, "missingness")
    if missingness != _missingness_from_state(state):
        _fail("V3R1 missingness disclosure does not match canonical state")

    source_release = manifest["source_release"]
    validated_days: list[dict[str, Any]] = []
    for index, (day_binding, state_record) in enumerate(zip(manifest["days"], selected)):
        day_binding = _object(day_binding, f"manifest days[{index}]")
        _exact_keys(day_binding, _DAY_KEYS, f"manifest days[{index}]")
        expected_day = selected_start + timedelta(days=index)
        bundle_name, envelope_name = _fixed_names(expected_day.isoformat())
        if day_binding["bundle_relative_name"] != bundle_name or day_binding["envelope_relative_name"] != envelope_name:
            _fail(f"V3R1 manifest selected-day name changed for {expected_day}")
        bundle_raw = by_name[bundle_name].read_bytes()
        envelope_raw = by_name[envelope_name].read_bytes()
        bundle = _load_canonical_object(bundle_raw, f"V3R1 bundle {expected_day}")
        envelope = _load_canonical_object(envelope_raw, f"V3R1 envelope {expected_day}")
        try:
            bundle_result = validate_v3_day_bundle(bundle, raw_bytes=bundle_raw)
            envelope_result = validate_complete_envelope(
                envelope,
                bundle_value=bundle,
                raw_envelope_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                binding_value=binding,
                expected_day=expected_day,
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
                observed_producer_identity="agora-evidence-source",
            )
        except ValueError as error:
            raise HandoffContractError(str(error)) from error
        expected_day_binding = {
            "day": expected_day.isoformat(),
            "bundle_relative_name": bundle_name,
            "bundle_sha256": bundle_result["bundle_sha256"],
            "envelope_relative_name": envelope_name,
            "envelope_sha256": envelope_result["envelope_sha256"],
            "accepted_at": state_record["accepted_at"],
        }
        if day_binding != expected_day_binding:
            _fail(f"V3R1 manifest day binding mismatch for {expected_day}")
        if (
            state_record["day"] != expected_day.isoformat()
            or state_record["bundle_sha256"] != bundle_result["bundle_sha256"]
            or state_record["envelope_sha256"] != envelope_result["envelope_sha256"]
            or envelope["producer_release_id"] != source_release["producer_release_id"]
            or envelope["producer_manifest_sha256"] != source_release["producer_manifest_sha256"]
            or envelope["producer_identity"] != source_release["producer_identity"]
        ):
            _fail(f"V3R1 selected-state source binding mismatch for {expected_day}")
        enriched = dict(day_binding)
        enriched["payload_sha256"] = bundle["seal"]["payload_sha256"]
        validated_days.append(enriched)

    _result_schema_bytes()
    return HandoffContext(
        task_id=expected_task_id,
        task_sha256=expected_task_sha256,
        manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(),
        manifest_payload_sha256=manifest["seal"]["payload_sha256"],
        state_relative_name=state_name,
        state_sha256=state_sha256,
        diagnostic_id=binding["diagnostic_id"],
        chain_head_sha256=state["selected_streak_chain_head_sha256"],
        days=tuple(validated_days),
    )


def validate_handoff_result_bytes(raw: bytes, context: HandoffContext) -> dict[str, Any]:
    _result_schema_bytes()
    return _validate_handoff_result_bytes(raw, context)


def create_result_once(
    task_owned_root: Path, raw: bytes, context: HandoffContext
) -> tuple[Path, str]:
    _result_schema_bytes()
    return _create_result_once(task_owned_root, raw, context)
