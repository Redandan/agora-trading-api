from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
import re
from typing import Any


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SOURCE = "server-local read-only OKX BTCUSDT complete 1h bars aggregated into complete UTC days"
PRODUCER = "agora-okx-forward-source-v1"
TRANSPORT = "SEALED_ONE_WAY_DROP_V1"
OPERATION = "CAPTURE_FORWARD_EVIDENCE"
SOURCE_ACTOR = "CODEX_CLOUD_OPS_HEARTBEAT_COMPANION"
RUN_ID = re.compile(r"^[a-f0-9]{32}$")
SHA256 = re.compile(r"^[a-f0-9]{64}$")

REQUEST_FIELDS = {
    "schema_version",
    "request_id",
    "requested_at",
    "source_actor",
    "operation",
    "trigger_id",
    "trigger_sha256",
    "trigger_fingerprint",
    "source",
    "source_contract_sha256",
    "producer",
    "transport",
    "day",
    "capture_deadline",
    "authorization",
}


class CaptureContractError(ValueError):
    """A capture request violates the frozen forward-only contract."""


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _parse_time(value: Any, field: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError) as error:
        raise CaptureContractError(f"{field} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise CaptureContractError(f"{field} must include a timezone")
    return parsed.astimezone(timezone.utc)


def _identity(value: dict[str, Any]) -> dict[str, Any]:
    return {key: value[key] for key in sorted(REQUEST_FIELDS - {"request_id", "requested_at"})}


def expected_request_id(value: dict[str, Any]) -> str:
    return canonical_sha256(_identity(value))[:32]


def build_capture_request(
    *,
    trigger: dict[str, Any],
    state: dict[str, Any],
    progress: dict[str, Any],
    requested_at: datetime,
) -> dict[str, Any]:
    if progress.get("status") != "CAPTURE_DUE":
        raise CaptureContractError("canonical evidence progress is not CAPTURE_DUE")
    source_contract = progress.get("source_contract")
    if not isinstance(source_contract, dict):
        raise CaptureContractError("canonical evidence source contract is not sealed")
    if trigger.get("source") != SOURCE:
        raise CaptureContractError("trigger source is not the authorized forward source")
    if source_contract.get("producer") != PRODUCER or source_contract.get("transport") != TRANSPORT:
        raise CaptureContractError("sealed source contract does not match the authorized producer")
    request = {
        "schema_version": "1",
        "requested_at": _iso_utc(requested_at),
        "source_actor": SOURCE_ACTOR,
        "operation": OPERATION,
        "trigger_id": trigger.get("trigger_id"),
        "trigger_sha256": state.get("trigger_sha256"),
        "trigger_fingerprint": trigger.get("fingerprint"),
        "source": SOURCE,
        "source_contract_sha256": source_contract.get("sha256"),
        "producer": PRODUCER,
        "transport": TRANSPORT,
        "day": progress.get("next_observation_day"),
        "capture_deadline": progress.get("next_capture_deadline"),
        "authorization": AUTHORIZATION,
    }
    request["request_id"] = expected_request_id(request)
    return validate_capture_request(request, now=requested_at)


def validate_capture_request(
    value: dict[str, Any],
    *,
    now: datetime | None = None,
    require_open_window: bool = True,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CaptureContractError("capture request must be an object")
    missing = sorted(REQUEST_FIELDS.difference(value))
    unknown = sorted(set(value).difference(REQUEST_FIELDS))
    if missing:
        raise CaptureContractError(f"capture request missing fields: {', '.join(missing)}")
    if unknown:
        raise CaptureContractError(f"capture request has unknown fields: {', '.join(unknown)}")
    if value.get("schema_version") != "1":
        raise CaptureContractError("capture request schema_version must be 1")
    exact = {
        "source_actor": SOURCE_ACTOR,
        "operation": OPERATION,
        "source": SOURCE,
        "producer": PRODUCER,
        "transport": TRANSPORT,
        "authorization": AUTHORIZATION,
    }
    for field, expected in exact.items():
        if value.get(field) != expected:
            raise CaptureContractError(f"capture request {field} is not authorized")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,79}", str(value.get("trigger_id", ""))):
        raise CaptureContractError("capture request trigger_id is invalid")
    for field in ("trigger_sha256", "trigger_fingerprint", "source_contract_sha256"):
        if not SHA256.fullmatch(str(value.get(field, ""))):
            raise CaptureContractError(f"capture request {field} is invalid")
    if not RUN_ID.fullmatch(str(value.get("request_id", ""))):
        raise CaptureContractError("capture request request_id is invalid")
    if value["request_id"] != expected_request_id(value):
        raise CaptureContractError("capture request deterministic id mismatch")
    try:
        day = date.fromisoformat(str(value.get("day", "")))
    except ValueError as error:
        raise CaptureContractError("capture request day must be YYYY-MM-DD") from error
    day_close = datetime.combine(day + timedelta(days=1), time.min, tzinfo=timezone.utc)
    deadline = _parse_time(value.get("capture_deadline"), "capture_deadline")
    if deadline != day_close + timedelta(hours=6):
        raise CaptureContractError("capture deadline must be exactly six hours after UTC day close")
    requested_at = _parse_time(value.get("requested_at"), "requested_at")
    if requested_at < day_close or requested_at > deadline:
        raise CaptureContractError("capture request was not created inside the forward-only window")
    if require_open_window:
        current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        if current < day_close:
            raise CaptureContractError("complete UTC day has not closed")
        if current > deadline:
            raise CaptureContractError("capture window expired; backfill is prohibited")
    return {key: value[key] for key in sorted(REQUEST_FIELDS)}
