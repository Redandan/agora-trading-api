from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any

from research_pipeline.evidence import seal_daily_evidence
from research_pipeline.policy import load_policy
from research_pipeline.storage import ResearchStore, sha256_file

from .contract import (
    AUTHORIZATION,
    CaptureContractError,
    canonical_bytes,
    canonical_sha256,
    validate_capture_request,
)
from .okx import (
    SourceIntegrityError,
    TemporarySourceError,
    build_day_bundle,
    fetch_okx_rows,
    probe_okx,
)


RUN_ID = re.compile(r"^[a-f0-9]{32}$")
SOURCE_REQUEST_DIR = Path(os.environ.get("AGORA_RESEARCH_SOURCE_REQUEST_DIR", "/var/lib/agora-research/source-requests"))
SOURCE_DROP_DIR = Path(os.environ.get("AGORA_RESEARCH_SOURCE_DROP_DIR", "/var/lib/agora-research/source-drop"))
STATE_DIR = Path(os.environ.get("AGORA_RESEARCH_STATE_DIR", "/var/lib/agora-research/state"))
POLICY_FILE = Path(os.environ.get("AGORA_RESEARCH_POLICY_FILE", "/opt/agora-research-worker/current/research_pipeline/policy.v3.json"))


def _now(value: datetime | None = None) -> str:
    current = value or datetime.now(timezone.utc)
    return current.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise SourceIntegrityError(f"expected JSON object: {path.name}")
    return value


def _write_json(path: Path, value: dict[str, Any], *, immutable_same: bool = False) -> None:
    encoded = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    if path.exists():
        if immutable_same and path.read_bytes() == encoded:
            return
        if immutable_same:
            raise SourceIntegrityError(f"existing artifact differs: {path.name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with temporary.open("wb") as stream:
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o640)
    os.replace(temporary, path)


def _same_pending_remove(path: Path, request_id: str) -> None:
    try:
        value = _read_json(path)
    except (FileNotFoundError, json.JSONDecodeError, OSError, SourceIntegrityError):
        return
    if value.get("request_id") == request_id:
        path.unlink(missing_ok=True)


def process_source_request(
    *,
    now: datetime | None = None,
    fetcher=fetch_okx_rows,
    request_dir: Path = SOURCE_REQUEST_DIR,
    drop_dir: Path = SOURCE_DROP_DIR,
) -> dict[str, Any]:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    pending = request_dir / "pending.json"
    request = validate_capture_request(_read_json(pending), now=current)
    request_id = str(request["request_id"])
    existing_run_path = request_dir / "runs" / f"{request_id}.json"
    existing_run = None
    if existing_run_path.exists():
        existing_run = _read_json(existing_run_path)
        if existing_run.get("status") != "COMPLETED" or existing_run.get("request") != request:
            raise SourceIntegrityError("existing source run differs from the capture request")
    raw_relative = f"raw/{request_id}.json"
    raw_path = drop_dir / raw_relative
    captured_at = _now(current)
    if raw_path.exists():
        existing_raw = _read_json(raw_path)
        captured_at = str(existing_raw.get("captured_at", captured_at))
        rows = existing_raw.get("rows")
        if not isinstance(rows, list):
            raise SourceIntegrityError("existing raw artifact rows are invalid")
    else:
        rows = fetcher()
    bundle, selected = build_day_bundle(request, rows)
    raw = {
        "schema_version": "1",
        "request_id": request_id,
        "captured_at": captured_at,
        "rows": selected,
        "rows_sha256": canonical_sha256(selected),
        "authorization": AUTHORIZATION,
    }
    _write_json(raw_path, raw, immutable_same=True)
    envelope = {
        "schema_version": "1",
        "operation": "INGEST_FORWARD_EVIDENCE",
        "request_id": request_id,
        "request_sha256": canonical_sha256(request),
        "raw_artifact": {
            "path": raw_relative,
            "file_sha256": sha256_file(raw_path),
            "rows_sha256": raw["rows_sha256"],
        },
        "bundle": bundle,
        "authorization": AUTHORIZATION,
    }
    record = {
        "schema_version": "1",
        "request": request,
        "status": "COMPLETED",
        "completed_at": existing_run.get("completed_at") if existing_run else _now(current),
        "request_sha256": envelope["request_sha256"],
        "raw_artifact": envelope["raw_artifact"],
        "bundle_sha256": canonical_sha256(bundle),
    }
    _write_json(request_dir / "runs" / f"{request_id}.json", record, immutable_same=True)
    _write_json(request_dir / "latest.json", record)
    # The path-triggering envelope is published last so canonical ingest can
    # never observe it before the durable source run and raw artifact exist.
    _write_json(drop_dir / "pending.json", envelope, immutable_same=True)
    _same_pending_remove(pending, request_id)
    return record


def _validated_envelope(
    value: dict[str, Any],
    *,
    request_dir: Path,
    drop_dir: Path,
) -> tuple[dict[str, Any], dict[str, Any]]:
    expected = {
        "schema_version",
        "operation",
        "request_id",
        "request_sha256",
        "raw_artifact",
        "bundle",
        "authorization",
    }
    if set(value) != expected:
        raise SourceIntegrityError("source-drop envelope fields are not exact")
    if value["schema_version"] != "1" or value["operation"] != "INGEST_FORWARD_EVIDENCE":
        raise SourceIntegrityError("source-drop envelope schema or operation is invalid")
    if value["authorization"] != AUTHORIZATION:
        raise SourceIntegrityError("source-drop envelope is not research-only")
    request_id = str(value["request_id"])
    if not RUN_ID.fullmatch(request_id):
        raise SourceIntegrityError("source-drop request id is invalid")
    source_run = _read_json(request_dir / "runs" / f"{request_id}.json")
    if source_run.get("status") != "COMPLETED" or not isinstance(source_run.get("request"), dict):
        raise SourceIntegrityError("source request has no completed durable record")
    request = validate_capture_request(source_run["request"], require_open_window=False)
    if canonical_sha256(request) != value["request_sha256"]:
        raise SourceIntegrityError("source request hash mismatch")
    raw_ref = value["raw_artifact"]
    if not isinstance(raw_ref, dict) or set(raw_ref) != {"path", "file_sha256", "rows_sha256"}:
        raise SourceIntegrityError("raw artifact reference is invalid")
    if raw_ref["path"] != f"raw/{request_id}.json":
        raise SourceIntegrityError("raw artifact path is not fixed")
    raw_path = (drop_dir / raw_ref["path"]).resolve()
    try:
        raw_path.relative_to(drop_dir.resolve())
    except ValueError as error:
        raise SourceIntegrityError("raw artifact path escapes source drop") from error
    if sha256_file(raw_path) != raw_ref["file_sha256"]:
        raise SourceIntegrityError("raw artifact file hash mismatch")
    raw = _read_json(raw_path)
    if set(raw) != {"schema_version", "request_id", "captured_at", "rows", "rows_sha256", "authorization"}:
        raise SourceIntegrityError("raw artifact fields are not exact")
    if raw["schema_version"] != "1" or raw["request_id"] != request_id or raw["authorization"] != AUTHORIZATION:
        raise SourceIntegrityError("raw artifact identity is invalid")
    if canonical_sha256(raw["rows"]) != raw["rows_sha256"] or raw["rows_sha256"] != raw_ref["rows_sha256"]:
        raise SourceIntegrityError("raw selected-row hash mismatch")
    rebuilt, _ = build_day_bundle(request, raw["rows"])
    if rebuilt != value["bundle"]:
        raise SourceIntegrityError("normalized bundle does not match the raw selected rows")
    return request, rebuilt


def process_ingest_request(
    *,
    now: datetime | None = None,
    request_dir: Path = SOURCE_REQUEST_DIR,
    drop_dir: Path = SOURCE_DROP_DIR,
    state_dir: Path = STATE_DIR,
    policy_file: Path = POLICY_FILE,
) -> dict[str, Any]:
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    pending = drop_dir / "pending.json"
    envelope = _read_json(pending)
    request, bundle = _validated_envelope(envelope, request_dir=request_dir, drop_dir=drop_dir)
    policy = load_policy(policy_file)
    store = ResearchStore(state_dir, lock_stale_seconds=int(policy["budget"]["lock_stale_seconds"]))
    with store.lock():
        trigger = store.load_evidence_trigger(str(request["trigger_id"]))
        state = store.load_evidence_trigger_state(str(request["trigger_id"]))
        trigger_path = store.evidence_trigger_dir(str(request["trigger_id"])) / "trigger.json"
        if sha256_file(trigger_path) != request["trigger_sha256"] or state.get("trigger_sha256") != request["trigger_sha256"]:
            raise SourceIntegrityError("canonical trigger hash does not match capture request")
        source_ref = state.get("evidence_source_contract")
        if not isinstance(source_ref, dict) or source_ref.get("sha256") != request["source_contract_sha256"]:
            raise SourceIntegrityError("canonical source contract hash does not match capture request")
        result = seal_daily_evidence(
            store,
            trigger,
            state,
            bundle,
            received_at=current,
            capture_max_lag_seconds=int(policy["evidence"]["capture_max_lag_seconds"]),
        )
    request_id = str(request["request_id"])
    record = {
        "schema_version": "1",
        "request_id": request_id,
        "status": "COMPLETED",
        "completed_at": _now(current),
        "request_sha256": envelope["request_sha256"],
        "bundle_sha256": canonical_sha256(bundle),
        "result": result,
    }
    _write_json(drop_dir / "runs" / f"{request_id}.json", record, immutable_same=True)
    _write_json(drop_dir / "latest.json", record)
    _same_pending_remove(pending, request_id)
    return record


def _failure_record(*, phase: str, error: Exception, pending: Path, latest: Path) -> dict[str, Any]:
    request_id = "unknown"
    try:
        value = _read_json(pending)
        request_id = str(value.get("request_id", "unknown"))
    except Exception:
        pass
    record = {
        "schema_version": "1",
        "request_id": request_id,
        "status": "RETRYING" if isinstance(error, TemporarySourceError) else "FAILED",
        "phase": phase,
        "recorded_at": _now(),
        "error_type": type(error).__name__,
        "detail": str(error)[:1000],
    }
    _write_json(latest, record)
    return record


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Fixed isolated public forward-evidence source")
    parser.add_argument("operation", choices=("source", "ingest", "probe"))
    args = parser.parse_args()
    if args.operation == "probe":
        print(json.dumps(probe_okx(), ensure_ascii=False, sort_keys=True))
        return 0
    if args.operation == "source":
        pending = SOURCE_REQUEST_DIR / "pending.json"
        try:
            result = process_source_request()
        except TemporarySourceError as error:
            print(json.dumps(_failure_record(phase="SOURCE_CAPTURE", error=error, pending=pending, latest=SOURCE_REQUEST_DIR / "latest.json"), ensure_ascii=False), flush=True)
            return 75
        except Exception as error:
            record = _failure_record(phase="SOURCE_CAPTURE", error=error, pending=pending, latest=SOURCE_REQUEST_DIR / "latest.json")
            if RUN_ID.fullmatch(record["request_id"]):
                _write_json(SOURCE_REQUEST_DIR / "runs" / f"{record['request_id']}.json", record)
                _same_pending_remove(pending, record["request_id"])
            print(json.dumps(record, ensure_ascii=False), flush=True)
            return 65
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    pending = SOURCE_DROP_DIR / "pending.json"
    try:
        result = process_ingest_request()
    except RuntimeError as error:
        if "pipeline is locked" in str(error):
            transient = TemporarySourceError(str(error))
            print(json.dumps(_failure_record(phase="CANONICAL_INGEST", error=transient, pending=pending, latest=SOURCE_DROP_DIR / "latest.json"), ensure_ascii=False), flush=True)
            return 75
        raise
    except Exception as error:
        record = _failure_record(phase="CANONICAL_INGEST", error=error, pending=pending, latest=SOURCE_DROP_DIR / "latest.json")
        if RUN_ID.fullmatch(record["request_id"]):
            _write_json(SOURCE_DROP_DIR / "runs" / f"{record['request_id']}.json", record)
            _same_pending_remove(pending, record["request_id"])
        print(json.dumps(record, ensure_ascii=False), flush=True)
        return 65
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
