from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from typing import Any, Sequence

from research_pipeline.microstructure_diagnostic import (
    AUTHORIZATION,
    CANONICALIZATION as DIAGNOSTIC_CANONICALIZATION,
    CONTRACT_ID as DIAGNOSTIC_CONTRACT_ID,
    TIER_KEYS,
    payload_sha256 as diagnostic_payload_sha256,
)
from research_pipeline.microstructure_intake import load_canonical_v3_state_bytes
from research_pipeline.microstructure_source_contract import (
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    V3_DROP_ENVELOPE_SCHEMA_SHA256,
    V3_INTAKE_STATE_SCHEMA_SHA256,
    V3_SOURCE_CONTRACT_SHA256,
    canonical_json_bytes,
    load_json_bytes_strict,
    validate_v3_day_bundle,
    validate_v3_drop_envelope,
    validate_v3_frozen_contract_files,
)


MANIFEST_TYPE = "MICROSTRUCTURE_V3_CREATE_ONLY_HANDOFF_MANIFEST"
RESULT_TYPE = "MICROSTRUCTURE_V3_CREATE_ONLY_HANDOFF_RESULT"
SCHEMA_VERSION = "1"
MANIFEST_NAME = "handoff-manifest.json"
RESULT_NAME = "diagnostic-result.json"
REQUIRED_DAYS = 14
PACKAGE_FILE_COUNT = 30
HANDOFF_CANONICALIZATION = (
    "UTF-8 compact JSON excluding seal; object keys sorted lexicographically"
)
INFERENCE_BOUNDARIES = {
    "canonical_state_write_authorized": False,
    "candidate_authorized": False,
    "discovery_only": True,
    "oos_authorized": False,
    "pnl_inference_authorized": False,
    "trading_action_authorized": False,
}

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DIAGNOSTIC_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
_DECIMAL = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")
_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")
_REPARSE_POINT = 0x400

_MANIFEST_KEYS = {
    "schema_version",
    "manifest_type",
    "authorization",
    "task_id",
    "task_sha256",
    "canonical_state",
    "source_release",
    "days",
    "inference_boundaries",
    "seal",
}
_STATE_BINDING_KEYS = {
    "relative_name",
    "sha256",
    "intake_state_schema_sha256",
    "state_type",
    "state_authority",
    "diagnostic_id",
    "status",
    "start_day",
    "last_day",
    "required_day_count",
    "accepted_day_count",
    "chain_head_sha256",
    "source_contract_sha256",
    "drop_envelope_schema_sha256",
    "day_schema_sha256",
    "diagnostic_contract_sha256",
}
_SOURCE_RELEASE_KEYS = {
    "producer_identity",
    "producer_release_id",
    "producer_manifest_sha256",
}
_DAY_BINDING_KEYS = {
    "day",
    "bundle_relative_name",
    "bundle_sha256",
    "envelope_relative_name",
    "envelope_sha256",
    "predecessor_day",
    "predecessor_bundle_sha256",
    "accepted_at",
    "cumulative_chain_sha256",
}
_SEAL_KEYS = {"algorithm", "payload_sha256", "canonicalization"}
_RESULT_KEYS = {
    "schema_version",
    "result_type",
    "authorization",
    "task_id",
    "task_sha256",
    "input_manifest",
    "canonical_state",
    "diagnostic_contract",
    "diagnostic_payload_hashes",
    "diagnostic_result",
    "inference_boundaries",
    "seal",
}
_RESULT_MANIFEST_KEYS = {"relative_name", "sha256", "payload_sha256"}
_RESULT_STATE_KEYS = {
    "relative_name",
    "sha256",
    "diagnostic_id",
    "chain_head_sha256",
}
_RESULT_CONTRACT_KEYS = {"contract_id", "sha256"}
_RESULT_PAYLOAD_KEYS = {"payload_sha256", "canonical_document_sha256"}


class HandoffContractError(ValueError):
    pass


@dataclass(frozen=True)
class HandoffContext:
    task_id: str
    task_sha256: str
    manifest_sha256: str
    manifest_payload_sha256: str
    state_relative_name: str
    state_sha256: str
    diagnostic_id: str
    chain_head_sha256: str
    days: tuple[dict[str, Any], ...]


def _fail(message: str) -> None:
    raise HandoffContractError(message)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        _fail(f"{label} keys mismatch: missing={missing} extra={extra}")


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        _fail(f"{label} must be a lowercase SHA-256")
    return value


def _iso_day(value: Any, label: str) -> date:
    if not isinstance(value, str):
        _fail(f"{label} must be YYYY-MM-DD")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise HandoffContractError(f"{label} must be YYYY-MM-DD") from error
    if parsed.isoformat() != value:
        _fail(f"{label} must be canonical YYYY-MM-DD")
    return parsed


def _canonical_payload_sha256(value: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json_bytes(value, exclude_key="seal")).hexdigest()


def _load_canonical_object(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = load_json_bytes_strict(raw, label)
    except ValueError as error:
        raise HandoffContractError(str(error)) from error
    if raw != canonical_json_bytes(value):
        _fail(f"{label} bytes must be compact sorted-key canonical UTF-8 JSON")
    return value


def _validate_seal(value: dict[str, Any], label: str) -> str:
    seal = _object(value.get("seal"), f"{label}.seal")
    _exact_keys(seal, _SEAL_KEYS, f"{label}.seal")
    if seal["algorithm"] != "SHA-256":
        _fail(f"{label}.seal.algorithm changed")
    if seal["canonicalization"] != HANDOFF_CANONICALIZATION:
        _fail(f"{label}.seal.canonicalization changed")
    expected = _canonical_payload_sha256(value)
    if _sha256(seal["payload_sha256"], f"{label}.seal.payload_sha256") != expected:
        _fail(f"{label}.seal.payload_sha256 does not match")
    return expected


def _safe_relative_name(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        _fail(f"{label} is not a fixed POSIX relative name")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in value.split("/")):
        _fail(f"{label} is not a fixed POSIX relative name")
    if path.as_posix() != value:
        _fail(f"{label} is not canonical")
    return value


def _has_reparse_point(path: Path, info: os.stat_result | None = None) -> bool:
    info = info or path.lstat()
    return bool(getattr(info, "st_file_attributes", 0) & _REPARSE_POINT)


def _check_existing_path(path: Path, *, directory: bool, label: str) -> None:
    try:
        info = path.lstat()
    except OSError as error:
        raise HandoffContractError(f"{label} is not accessible") from error
    if stat.S_ISLNK(info.st_mode) or _has_reparse_point(path, info):
        _fail(f"{label} must not be a link or reparse point")
    expected = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)
    if not expected:
        _fail(f"{label} has the wrong filesystem type")


def _root_and_expected_path(root: Path, relative_name: str) -> tuple[Path, Path]:
    if not isinstance(root, Path):
        _fail("task_owned_root must be a Path")
    root = Path(os.path.abspath(root))
    _check_existing_path(root, directory=True, label="task-owned root")
    parts = PurePosixPath(relative_name).parts
    target = root.joinpath(*parts)
    return root, target


def _check_inventory_entry(root: Path, relative_name: str, supplied: Path) -> Path:
    _safe_relative_name(relative_name, "inventory relative_name")
    root, expected = _root_and_expected_path(root, relative_name)
    if not isinstance(supplied, Path):
        _fail("inventory paths must be Path objects")
    supplied_absolute = Path(os.path.abspath(supplied))
    if os.path.normcase(str(supplied_absolute)) != os.path.normcase(str(expected)):
        _fail(f"inventory path does not match fixed relative name: {relative_name}")
    current = root
    for part in PurePosixPath(relative_name).parts[:-1]:
        current = current / part
        _check_existing_path(current, directory=True, label=f"parent of {relative_name}")
    _check_existing_path(expected, directory=False, label=relative_name)
    return expected


def _fixed_names(day_text: str) -> tuple[str, str]:
    base = f"okx-btc-usdt-microstructure-{day_text}"
    return f"days/{day_text}/{base}.json", f"days/{day_text}/{base}.envelope.json"


def _validate_manifest(
    manifest: dict[str, Any], *, expected_task_id: str, expected_task_sha256: str
) -> tuple[str, str]:
    _exact_keys(manifest, _MANIFEST_KEYS, "handoff manifest")
    if manifest["schema_version"] != SCHEMA_VERSION:
        _fail("manifest schema_version changed")
    if manifest["manifest_type"] != MANIFEST_TYPE:
        _fail("manifest_type changed")
    if manifest["authorization"] != AUTHORIZATION:
        _fail("manifest authorization changed")
    if manifest["task_id"] != expected_task_id:
        _fail("manifest task_id mismatch")
    if manifest["task_sha256"] != expected_task_sha256:
        _fail("manifest task_sha256 mismatch")
    _sha256(manifest["task_sha256"], "manifest.task_sha256")
    if manifest["inference_boundaries"] != INFERENCE_BOUNDARIES:
        _fail("manifest inference boundaries exceed discovery-only authority")
    return _validate_seal(manifest, "handoff manifest"), hashlib.sha256(
        canonical_json_bytes(manifest)
    ).hexdigest()


def validate_handoff_package(
    task_owned_root: Path,
    inventory: Sequence[tuple[str, Path]],
    *,
    expected_task_id: str,
    expected_task_sha256: str,
) -> HandoffContext:
    if len(inventory) != PACKAGE_FILE_COUNT:
        _fail("handoff package must declare exactly 30 inventory entries")
    by_name: dict[str, Path] = {}
    for name, path in inventory:
        safe_name = _safe_relative_name(name, "inventory relative_name")
        if safe_name in by_name:
            _fail(f"duplicate inventory identity: {safe_name}")
        by_name[safe_name] = _check_inventory_entry(task_owned_root, safe_name, path)
    if MANIFEST_NAME not in by_name:
        _fail("handoff-manifest.json is missing")

    manifest_raw = by_name[MANIFEST_NAME].read_bytes()
    manifest = _load_canonical_object(manifest_raw, "handoff manifest")
    manifest_payload_hash, manifest_hash = _validate_manifest(
        manifest,
        expected_task_id=expected_task_id,
        expected_task_sha256=expected_task_sha256,
    )
    state_binding = _object(manifest["canonical_state"], "canonical_state")
    _exact_keys(state_binding, _STATE_BINDING_KEYS, "canonical_state")
    source_release = _object(manifest["source_release"], "source_release")
    _exact_keys(source_release, _SOURCE_RELEASE_KEYS, "source_release")
    if source_release["producer_identity"] != "agora-evidence-source":
        _fail("source release producer identity changed")
    if not isinstance(source_release["producer_release_id"], str) or not (
        1 <= len(source_release["producer_release_id"]) <= 128
    ):
        _fail("source release id is invalid")
    _sha256(source_release["producer_manifest_sha256"], "producer manifest hash")

    diagnostic_id = state_binding["diagnostic_id"]
    if not isinstance(diagnostic_id, str) or _DIAGNOSTIC_ID.fullmatch(diagnostic_id) is None:
        _fail("canonical state diagnostic_id is invalid")
    expected_state_name = f"canonical/{diagnostic_id}.json"
    if state_binding["relative_name"] != expected_state_name:
        _fail("canonical state relative name mismatch")

    manifest_days = manifest["days"]
    if not isinstance(manifest_days, list) or len(manifest_days) != REQUIRED_DAYS:
        _fail("manifest must bind exactly 14 days")
    expected_names = {MANIFEST_NAME, expected_state_name}
    for raw_day in manifest_days:
        day_binding = _object(raw_day, "manifest day")
        _exact_keys(day_binding, _DAY_BINDING_KEYS, "manifest day")
        bundle_name, envelope_name = _fixed_names(str(day_binding["day"]))
        if day_binding["bundle_relative_name"] != bundle_name:
            _fail("manifest bundle relative name mismatch")
        if day_binding["envelope_relative_name"] != envelope_name:
            _fail("manifest envelope relative name mismatch")
        expected_names.update({bundle_name, envelope_name})
    if set(by_name) != expected_names or len(expected_names) != PACKAGE_FILE_COUNT:
        _fail("handoff package closure has extras, missing files, or duplicate identities")

    try:
        validate_v3_frozen_contract_files()
    except ValueError as error:
        raise HandoffContractError(str(error)) from error
    state_raw = by_name[expected_state_name].read_bytes()
    try:
        state = load_canonical_v3_state_bytes(state_raw)
    except ValueError as error:
        raise HandoffContractError(str(error)) from error
    state_hash = hashlib.sha256(state_raw).hexdigest()
    start_day = _iso_day(state["start_day"], "state.start_day")
    last_day = start_day + timedelta(days=REQUIRED_DAYS - 1)
    accepted = state["accepted_days"]
    expected_state_binding = {
        "relative_name": expected_state_name,
        "sha256": state_hash,
        "intake_state_schema_sha256": V3_INTAKE_STATE_SCHEMA_SHA256,
        "state_type": "SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE",
        "state_authority": "SERVER_CANONICAL",
        "diagnostic_id": diagnostic_id,
        "status": "DIAGNOSTIC_READY",
        "start_day": start_day.isoformat(),
        "last_day": last_day.isoformat(),
        "required_day_count": REQUIRED_DAYS,
        "accepted_day_count": REQUIRED_DAYS,
        "chain_head_sha256": state["chain_head_sha256"],
        "source_contract_sha256": V3_SOURCE_CONTRACT_SHA256,
        "drop_envelope_schema_sha256": V3_DROP_ENVELOPE_SCHEMA_SHA256,
        "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
        "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
    }
    if state_binding != expected_state_binding or len(accepted) != REQUIRED_DAYS:
        _fail("canonical state binding is not the exact DIAGNOSTIC_READY V3 state")

    previous_day: date | None = None
    previous_bundle_hash: str | None = None
    validated_days: list[dict[str, Any]] = []
    for index, (day_binding, state_record) in enumerate(zip(manifest_days, accepted)):
        expected_day = start_day + timedelta(days=index)
        bundle_name, envelope_name = _fixed_names(expected_day.isoformat())
        bundle_raw = by_name[bundle_name].read_bytes()
        envelope_raw = by_name[envelope_name].read_bytes()
        bundle = _load_canonical_object(bundle_raw, f"day bundle {expected_day}")
        envelope = _load_canonical_object(envelope_raw, f"envelope {expected_day}")
        try:
            bundle_result = validate_v3_day_bundle(bundle, raw_bytes=bundle_raw)
            envelope_result = validate_v3_drop_envelope(
                envelope,
                bundle,
                raw_envelope_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                expected_diagnostic_id=diagnostic_id,
                expected_day=expected_day,
                expected_predecessor_day=previous_day,
                expected_predecessor_bundle_sha256=previous_bundle_hash,
                observed_producer_identity="agora-evidence-source",
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
            )
        except ValueError as error:
            raise HandoffContractError(str(error)) from error
        if (
            envelope["producer_identity"] != source_release["producer_identity"]
            or envelope["producer_release_id"] != source_release["producer_release_id"]
            or envelope["producer_manifest_sha256"]
            != source_release["producer_manifest_sha256"]
        ):
            _fail("all envelopes must bind the identical frozen source release")
        expected_day_binding = {
            "day": expected_day.isoformat(),
            "bundle_relative_name": bundle_name,
            "bundle_sha256": bundle_result["bundle_sha256"],
            "envelope_relative_name": envelope_name,
            "envelope_sha256": envelope_result["envelope_sha256"],
            "predecessor_day": None if previous_day is None else previous_day.isoformat(),
            "predecessor_bundle_sha256": previous_bundle_hash,
            "accepted_at": state_record["accepted_at"],
            "cumulative_chain_sha256": state_record["cumulative_chain_sha256"],
        }
        if day_binding != expected_day_binding:
            _fail(f"manifest day binding mismatch for {expected_day}")
        if (
            state_record["day"] != expected_day.isoformat()
            or state_record["bundle_sha256"] != bundle_result["bundle_sha256"]
            or state_record["envelope_sha256"] != envelope_result["envelope_sha256"]
            or state_record["predecessor_bundle_sha256"] != previous_bundle_hash
        ):
            _fail(f"canonical state record mismatch for {expected_day}")
        validated_day = dict(day_binding)
        validated_day["payload_sha256"] = bundle["seal"]["payload_sha256"]
        validated_days.append(validated_day)
        previous_day = expected_day
        previous_bundle_hash = bundle_result["bundle_sha256"]

    return HandoffContext(
        task_id=expected_task_id,
        task_sha256=expected_task_sha256,
        manifest_sha256=manifest_hash,
        manifest_payload_sha256=manifest_payload_hash,
        state_relative_name=expected_state_name,
        state_sha256=state_hash,
        diagnostic_id=diagnostic_id,
        chain_head_sha256=state["chain_head_sha256"],
        days=tuple(validated_days),
    )


def _require_decimal_or_null(value: Any, label: str) -> None:
    if value is not None and (
        not isinstance(value, str) or _DECIMAL.fullmatch(value) is None
    ):
        _fail(f"{label} must be a canonical decimal string or null")


def _validate_response(value: Any, label: str) -> None:
    response = _object(value, label)
    _exact_keys(response, {"5", "15", "60", "240", "1440"}, label)
    for horizon, raw_metrics in response.items():
        metrics = _object(raw_metrics, f"{label}.{horizon}")
        _exact_keys(metrics, {"return_bps", "mfe_bps", "mae_bps"}, f"{label}.{horizon}")
        for key, metric in metrics.items():
            if not isinstance(metric, str) or _DECIMAL.fullmatch(metric) is None:
                _fail(f"{label}.{horizon}.{key} is not a canonical decimal")


def _validate_diagnostic_result(value: Any, context: HandoffContext) -> dict[str, Any]:
    result = _object(value, "diagnostic_result")
    _exact_keys(
        result,
        {
            "schema_version",
            "contract_id",
            "contract_file_sha256",
            "authorization",
            "status",
            "input",
            "entry_reference",
            "fees_and_slippage",
            "tiers",
            "inference_boundary",
            "seal",
        },
        "diagnostic_result",
    )
    if result["schema_version"] != "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_RESULT_V3":
        _fail("diagnostic result schema changed")
    if result["contract_id"] != DIAGNOSTIC_CONTRACT_ID:
        _fail("diagnostic result contract id changed")
    if result["contract_file_sha256"] != V3_DIAGNOSTIC_CONTRACT_SHA256:
        _fail("diagnostic result contract hash changed")
    if result["authorization"] != AUTHORIZATION:
        _fail("diagnostic result authorization changed")
    if result["status"] not in {
        "FORWARD_DIAGNOSTIC_READY_FOR_INTERPRETATION",
        "INSUFFICIENT_FORWARD_EVIDENCE",
    }:
        _fail("diagnostic result status is invalid")
    if result["entry_reference"] != "NEXT_COMPLETE_MINUTE_OPEN":
        _fail("diagnostic result entry reference changed")
    if result["fees_and_slippage"] != "NOT_APPLIED_DIAGNOSTIC_NOT_PNL":
        _fail("diagnostic result must not claim PnL")
    if result["inference_boundary"] != [
        "result_is_hypothesis_discovery_only",
        "result_is_not_candidate_or_oos_evidence",
        "result_is_not_a_trading_strategy_or_order_instruction",
        "no_tier_selection_or_threshold_change_after_outcome_access",
        "insufficient_evidence_is_a_valid_result",
    ]:
        _fail("embedded diagnostic inference boundary changed")

    input_value = _object(result["input"], "diagnostic_result.input")
    _exact_keys(
        input_value,
        {"first_day", "last_day", "complete_utc_days", "complete_minutes", "files"},
        "diagnostic_result.input",
    )
    if (
        input_value["first_day"] != context.days[0]["day"]
        or input_value["last_day"] != context.days[-1]["day"]
        or input_value["complete_utc_days"] != REQUIRED_DAYS
        or input_value["complete_minutes"] != REQUIRED_DAYS * 1440
    ):
        _fail("diagnostic result input window changed")
    files = input_value["files"]
    if not isinstance(files, list) or len(files) != REQUIRED_DAYS:
        _fail("diagnostic result must bind exactly 14 input files")
    for raw_file, day_binding in zip(files, context.days):
        item = _object(raw_file, "diagnostic input file")
        _exact_keys(item, {"path", "day", "payload_sha256", "file_sha256"}, "diagnostic input file")
        if (
            item["path"] != day_binding["bundle_relative_name"]
            or item["day"] != day_binding["day"]
            or item["file_sha256"] != day_binding["bundle_sha256"]
            or item["payload_sha256"] != day_binding["payload_sha256"]
        ):
            _fail("diagnostic result input file identity changed")
        _sha256(item["payload_sha256"], "diagnostic input payload hash")

    tiers = _object(result["tiers"], "diagnostic_result.tiers")
    _exact_keys(tiers, set(TIER_KEYS), "diagnostic_result.tiers")
    for tier_name, raw_tier in tiers.items():
        tier = _object(raw_tier, f"tier {tier_name}")
        _exact_keys(
            tier,
            {
                "event_count",
                "first_seven_day_event_count",
                "second_seven_day_event_count",
                "matched_control_count",
                "matched_control_coverage_pct",
                "overlapping_1440m_event_pair_count",
                "gates",
                "gate_status",
                "metrics_by_horizon_minutes",
                "events",
            },
            f"tier {tier_name}",
        )
        for key in (
            "event_count",
            "first_seven_day_event_count",
            "second_seven_day_event_count",
            "matched_control_count",
            "overlapping_1440m_event_pair_count",
        ):
            if isinstance(tier[key], bool) or not isinstance(tier[key], int) or tier[key] < 0:
                _fail(f"tier {tier_name}.{key} is invalid")
        if not isinstance(tier["matched_control_coverage_pct"], str) or _DECIMAL.fullmatch(
            tier["matched_control_coverage_pct"]
        ) is None:
            _fail(f"tier {tier_name} coverage is invalid")
        gates = _object(tier["gates"], f"tier {tier_name}.gates")
        _exact_keys(
            gates,
            {
                "minimum_30_events",
                "minimum_10_events_first_seven_days",
                "minimum_10_events_second_seven_days",
                "minimum_80_pct_matched_controls",
            },
            f"tier {tier_name}.gates",
        )
        if any(not isinstance(item, bool) for item in gates.values()):
            _fail(f"tier {tier_name} gates must be booleans")
        expected_gate = "PASS" if all(gates.values()) else "INSUFFICIENT_FORWARD_EVIDENCE"
        if tier["gate_status"] != expected_gate:
            _fail(f"tier {tier_name} gate status is inconsistent")
        metrics = _object(tier["metrics_by_horizon_minutes"], f"tier {tier_name}.metrics")
        _exact_keys(metrics, {"5", "15", "60", "240", "1440"}, f"tier {tier_name}.metrics")
        for horizon, raw_metrics in metrics.items():
            metric = _object(raw_metrics, f"tier {tier_name}.metrics.{horizon}")
            _exact_keys(
                metric,
                {
                    "median_return_bps",
                    "median_mfe_bps",
                    "median_mae_bps",
                    "positive_return_share_pct",
                    "matched_median_return_delta_bps",
                },
                f"tier {tier_name}.metrics.{horizon}",
            )
            for key, item in metric.items():
                _require_decimal_or_null(item, f"tier {tier_name}.metrics.{horizon}.{key}")
        if not isinstance(tier["events"], list) or len(tier["events"]) != tier["event_count"]:
            _fail(f"tier {tier_name} event count is inconsistent")
        for raw_event in tier["events"]:
            event = _object(raw_event, f"tier {tier_name}.event")
            _exact_keys(
                event,
                {
                    "signal_at",
                    "entry_at",
                    "entry_open_price",
                    "midline_buy_sell_ratio",
                    "response",
                    "matched_control",
                },
                f"tier {tier_name}.event",
            )
            for key in ("signal_at", "entry_at"):
                if not isinstance(event[key], str) or _TIMESTAMP.fullmatch(event[key]) is None:
                    _fail(f"tier {tier_name}.event.{key} is invalid")
            for key in ("entry_open_price", "midline_buy_sell_ratio"):
                if not isinstance(event[key], str) or _DECIMAL.fullmatch(event[key]) is None:
                    _fail(f"tier {tier_name}.event.{key} is invalid")
            _validate_response(event["response"], f"tier {tier_name}.event.response")
            if event["matched_control"] is not None:
                control = _object(event["matched_control"], f"tier {tier_name}.matched_control")
                _exact_keys(
                    control,
                    {"signal_at", "entry_at", "midline_buy_sell_ratio", "response"},
                    f"tier {tier_name}.matched_control",
                )
                for key in ("signal_at", "entry_at"):
                    if not isinstance(control[key], str) or _TIMESTAMP.fullmatch(control[key]) is None:
                        _fail(f"tier {tier_name}.matched_control.{key} is invalid")
                if not isinstance(control["midline_buy_sell_ratio"], str) or _DECIMAL.fullmatch(
                    control["midline_buy_sell_ratio"]
                ) is None:
                    _fail(f"tier {tier_name}.matched_control ratio is invalid")
                _validate_response(control["response"], f"tier {tier_name}.matched_control.response")

    seal = _object(result["seal"], "diagnostic_result.seal")
    _exact_keys(seal, _SEAL_KEYS, "diagnostic_result.seal")
    if seal["algorithm"] != "SHA-256" or seal["canonicalization"] != DIAGNOSTIC_CANONICALIZATION:
        _fail("diagnostic result seal contract changed")
    if _sha256(seal["payload_sha256"], "diagnostic result payload hash") != diagnostic_payload_sha256(result):
        _fail("diagnostic result payload seal does not match")
    return result


def validate_handoff_result_bytes(raw: bytes, context: HandoffContext) -> dict[str, Any]:
    result = _load_canonical_object(raw, "handoff result")
    _exact_keys(result, _RESULT_KEYS, "handoff result")
    if result["schema_version"] != SCHEMA_VERSION or result["result_type"] != RESULT_TYPE:
        _fail("handoff result contract identity changed")
    if result["authorization"] != AUTHORIZATION:
        _fail("handoff result authorization changed")
    if result["task_id"] != context.task_id or result["task_sha256"] != context.task_sha256:
        _fail("handoff result task binding changed")
    if result["inference_boundaries"] != INFERENCE_BOUNDARIES:
        _fail("handoff result inference boundaries exceed discovery-only authority")
    input_manifest = _object(result["input_manifest"], "input_manifest")
    _exact_keys(input_manifest, _RESULT_MANIFEST_KEYS, "input_manifest")
    if input_manifest != {
        "relative_name": MANIFEST_NAME,
        "sha256": context.manifest_sha256,
        "payload_sha256": context.manifest_payload_sha256,
    }:
        _fail("handoff result input manifest binding changed")
    state = _object(result["canonical_state"], "result canonical_state")
    _exact_keys(state, _RESULT_STATE_KEYS, "result canonical_state")
    if state != {
        "relative_name": context.state_relative_name,
        "sha256": context.state_sha256,
        "diagnostic_id": context.diagnostic_id,
        "chain_head_sha256": context.chain_head_sha256,
    }:
        _fail("handoff result canonical state binding changed")
    contract = _object(result["diagnostic_contract"], "diagnostic_contract")
    _exact_keys(contract, _RESULT_CONTRACT_KEYS, "diagnostic_contract")
    if contract != {
        "contract_id": DIAGNOSTIC_CONTRACT_ID,
        "sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
    }:
        _fail("handoff result diagnostic contract binding changed")
    diagnostic_result = _validate_diagnostic_result(result["diagnostic_result"], context)
    hashes = _object(result["diagnostic_payload_hashes"], "diagnostic_payload_hashes")
    _exact_keys(hashes, _RESULT_PAYLOAD_KEYS, "diagnostic_payload_hashes")
    expected_hashes = {
        "payload_sha256": diagnostic_result["seal"]["payload_sha256"],
        "canonical_document_sha256": hashlib.sha256(
            canonical_json_bytes(diagnostic_result)
        ).hexdigest(),
    }
    if hashes != expected_hashes:
        _fail("handoff result diagnostic payload hash binding changed")
    _validate_seal(result, "handoff result")
    return result


def create_result_once(
    task_owned_root: Path, raw: bytes, context: HandoffContext
) -> tuple[Path, str]:
    validate_handoff_result_bytes(raw, context)
    root, target = _root_and_expected_path(task_owned_root, RESULT_NAME)
    if target.exists() or target.is_symlink():
        _check_existing_path(target, directory=False, label=RESULT_NAME)
        existing = target.read_bytes()
        if existing != raw:
            _fail("conflicting or partial handoff result already exists")
        validate_handoff_result_bytes(existing, context)
        return target, "IDEMPOTENT_IDENTICAL"
    try:
        with target.open("xb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except FileExistsError:
        _check_existing_path(target, directory=False, label=RESULT_NAME)
        existing = target.read_bytes()
        if existing != raw:
            _fail("conflicting handoff result won the exclusive-create race")
        validate_handoff_result_bytes(existing, context)
        return target, "IDEMPOTENT_IDENTICAL"
    _check_existing_path(root, directory=True, label="task-owned root")
    _check_existing_path(target, directory=False, label=RESULT_NAME)
    if target.read_bytes() != raw:
        _fail("created handoff result bytes changed")
    return target, "CREATED"
