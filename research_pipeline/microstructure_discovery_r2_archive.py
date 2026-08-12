from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
from typing import Any, Callable, Sequence

from jsonschema import Draft202012Validator, FormatChecker

from .microstructure_intake_cli import (
    RuntimePaths as V3RuntimePaths,
    _load_matching_v3_state,
    _load_v3_binding,
    _state_path,
)
from .microstructure_source_contract import canonical_json_bytes, load_json_bytes_strict


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
DIAGNOSTIC_ID = "okx-btcusdt-microstructure-forward-v3-20260811-r2"
DISPOSITION = "NO_EVIDENCE_CLOSE_INTERRUPTED_GENERATION"
SOURCE_UNIT = "agora-research-microstructure-source.service"
SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DISCOVERY_R2_ARCHIVE_MANIFEST_V1"
SCHEMA_SHA256 = "050542e9c0668738cb25e60dc00343274e28d4514cd33ad8cc30daf249ce5f7e"
ARCHIVE_PATH = Path(
    "/var/lib/agora-research/state/microstructure-archive/"
    "okx-btcusdt-microstructure-forward-v3-20260811-r2"
)
BINDING_PATH = Path("/etc/agora-research/okx-microstructure-continuous-source-v3.json")
DROP_ROOT = Path("/var/lib/agora-evidence-source/microstructure-drop")
STAGING_ROOT = Path("/var/lib/agora-evidence-source/microstructure-private-staging")
STATE_ROOT = Path("/var/lib/agora-research/state/microstructure-v3")
CURRENT_RELEASE = Path("/opt/agora-research-worker/current")
SCHEMA_PATH = Path(__file__).with_name(
    "okx-microstructure-discovery-r2-archive-manifest.v1.schema.json"
)
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class R2ArchiveBlocked(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class RuntimePaths:
    binding: Path
    drop_root: Path
    staging_root: Path
    state_root: Path
    release: Path
    archive: Path
    schema: Path


Runner = Callable[[Sequence[str]], bytes]
Clock = Callable[[], datetime]


def fixed_runtime_paths() -> RuntimePaths:
    try:
        release = CURRENT_RELEASE.resolve(strict=True)
    except OSError as error:
        raise R2ArchiveBlocked("R2_ARCHIVE_RELEASE_MISSING") from error
    if (
        release.parent != Path("/opt/agora-research-worker/releases")
        or release.is_symlink()
        or not release.is_dir()
    ):
        raise R2ArchiveBlocked("R2_ARCHIVE_RELEASE_INVALID")
    return RuntimePaths(
        binding=BINDING_PATH,
        drop_root=DROP_ROOT,
        staging_root=STAGING_ROOT,
        state_root=STATE_ROOT,
        release=release,
        archive=ARCHIVE_PATH,
        schema=SCHEMA_PATH,
    )


def create_or_verify(
    *,
    paths: RuntimePaths,
    runner: Runner | None = None,
    clock: Clock | None = None,
) -> str:
    if os.path.lexists(paths.archive):
        return verify(paths=paths)
    properties = (runner or _run)(
        (
            "systemctl",
            "show",
            SOURCE_UNIT,
            "--no-pager",
            "--property=LoadState",
            "--property=ActiveState",
            "--property=SubState",
            "--property=UnitFileState",
            "--property=MainPID",
            "--property=Result",
            "--property=ExecMainCode",
            "--property=ExecMainStatus",
            "--property=FragmentPath",
        )
    )
    _validate_unit_properties(properties)
    journal = (runner or _run)(
        (
            "journalctl",
            "-u",
            SOURCE_UNIT,
            "--no-pager",
            "--output=short-iso-precise",
            "--since=2026-08-11T00:00:00Z",
            "--until=2026-08-13T00:00:00Z",
        )
    )
    created_at = (clock or (lambda: datetime.now(timezone.utc)))()
    if created_at.tzinfo is None or created_at.utcoffset() is None:
        raise R2ArchiveBlocked("R2_ARCHIVE_CLOCK_INVALID")
    created_at = created_at.astimezone(timezone.utc)
    return _create(paths, properties, journal, created_at)


def verify(*, paths: RuntimePaths) -> str:
    manifest_path = paths.archive / "archive-manifest.json"
    manifest_bytes = _stable_file_bytes(manifest_path, "R2_ARCHIVE_MANIFEST_INVALID")
    try:
        manifest = load_json_bytes_strict(manifest_bytes, "R2 archive manifest")
    except Exception as error:
        raise R2ArchiveBlocked("R2_ARCHIVE_MANIFEST_INVALID") from error
    if manifest_bytes != canonical_json_bytes(manifest):
        raise R2ArchiveBlocked("R2_ARCHIVE_MANIFEST_NOT_CANONICAL")
    _validate_manifest(paths.schema, manifest)
    expected_archive_paths: set[str] = {"archive-manifest.json"}
    for entry in manifest["entries"]:
        relative = _safe_relative(entry["archive_path"])
        expected_archive_paths.add(relative.as_posix())
        payload = _stable_file_bytes(
            paths.archive / relative, "R2_ARCHIVE_ENTRY_INVALID"
        )
        if (
            len(payload) != entry["size_bytes"]
            or hashlib.sha256(payload).hexdigest() != entry["sha256"]
        ):
            raise R2ArchiveBlocked("R2_ARCHIVE_ENTRY_HASH_MISMATCH")
        if entry["kind"] == "ORIGINAL_FILE_COPY":
            original = Path(entry["source_locator"])
            if _stable_file_bytes(
                original, "R2_ARCHIVE_ORIGINAL_MISSING"
            ) != payload:
                raise R2ArchiveBlocked("R2_ARCHIVE_ORIGINAL_DRIFT")
        elif entry["kind"] == "ORIGINAL_DIRECTORY_INVENTORY":
            current = canonical_json_bytes(
                _directory_inventory(Path(entry["source_locator"]))
            )
            if current != payload:
                raise R2ArchiveBlocked("R2_ARCHIVE_ORIGINAL_DRIFT")
    actual = {
        path.relative_to(paths.archive).as_posix()
        for path in paths.archive.rglob("*")
        if path.is_file() and not path.is_symlink()
    }
    if actual != expected_archive_paths or any(
        path.is_symlink() for path in paths.archive.rglob("*")
    ):
        raise R2ArchiveBlocked("R2_ARCHIVE_INVENTORY_MISMATCH")
    return hashlib.sha256(manifest_bytes).hexdigest()


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments not in (["create"], ["verify"]):
        print(
            "usage: python -m research_pipeline.microstructure_discovery_r2_archive {create|verify}",
            file=sys.stderr,
        )
        return 2
    try:
        paths = fixed_runtime_paths()
        manifest_hash = (
            create_or_verify(paths=paths) if arguments[0] == "create" else verify(paths=paths)
        )
    except (OSError, ValueError, R2ArchiveBlocked) as error:
        print(str(error) or error.__class__.__name__, file=sys.stderr)
        return 2
    print(
        json.dumps(
            {"archive_manifest_sha256": manifest_hash, "status": "R2_ARCHIVE_VERIFIED"},
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


def _create(
    paths: RuntimePaths,
    properties: bytes,
    journal: bytes,
    created_at: datetime,
) -> str:
    if not journal or len(journal) > 16 * 1024 * 1024:
        raise R2ArchiveBlocked("R2_ARCHIVE_JOURNAL_INVALID")
    _validate_schema_file(paths.schema)
    old_paths = V3RuntimePaths(
        binding=paths.binding,
        drop_root=paths.drop_root,
        staging_root=paths.staging_root,
        state_root=paths.state_root,
        release=paths.release,
    )
    binding = _load_v3_binding(
        old_paths, require_future=False, today=created_at.date()
    )
    if binding.diagnostic_id != DIAGNOSTIC_ID:
        raise R2ArchiveBlocked("R2_ARCHIVE_DIAGNOSTIC_MISMATCH")
    state_path = _state_path(paths.state_root, binding.diagnostic_id)
    state = _load_matching_v3_state(state_path, binding)
    if state["accepted_days"] != [] or state["status"] != "WAITING_FOR_DAY":
        raise R2ArchiveBlocked("R2_ARCHIVE_STATE_DISPOSITION_MISMATCH")
    originals = {
        "original/binding.json": paths.binding,
        f"original/state/{state_path.name}": state_path,
        "original/release/provenance.json": paths.release / ".release" / "provenance.json",
        "original/release/source.sha256": paths.release / ".release" / "source.sha256",
    }
    original_bytes = {
        archive_name: _stable_file_bytes(path, "R2_ARCHIVE_ORIGINAL_INVALID")
        for archive_name, path in originals.items()
    }
    directory_payloads = {
        "original/drop.inventory.json": canonical_json_bytes(
            _directory_inventory(paths.drop_root)
        ),
        "original/staging.inventory.json": canonical_json_bytes(
            _directory_inventory(paths.staging_root)
        ),
    }
    failure_evidence = canonical_json_bytes(
        {
            "accepted_day_count": 0,
            "diagnostic_id": DIAGNOSTIC_ID,
            "exact_control_event": "MISSING_PROOF",
            "source_failure": "UNEXPECTED_EXCHANGE_EVENT",
            "state_sha256": hashlib.sha256(original_bytes[f"original/state/{state_path.name}"]).hexdigest(),
            "status": DISPOSITION,
        }
    )
    payloads: dict[str, tuple[bytes, str, str]] = {}
    for archive_name, source in originals.items():
        payloads[archive_name] = (
            original_bytes[archive_name],
            str(source),
            "ORIGINAL_FILE_COPY",
        )
    payloads.update(
        {
            "original/drop.inventory.json": (
                directory_payloads["original/drop.inventory.json"],
                str(paths.drop_root),
                "ORIGINAL_DIRECTORY_INVENTORY",
            ),
            "original/staging.inventory.json": (
                directory_payloads["original/staging.inventory.json"],
                str(paths.staging_root),
                "ORIGINAL_DIRECTORY_INVENTORY",
            ),
            "captures/source-unit-properties.txt": (
                properties,
                f"systemctl show {SOURCE_UNIT}",
                "SYSTEMD_PROPERTIES_CAPTURE",
            ),
            "captures/source-unit-journal.txt": (
                journal,
                f"journalctl -u {SOURCE_UNIT}",
                "SYSTEMD_JOURNAL_CAPTURE",
            ),
            "captures/failure-evidence.json": (
                failure_evidence,
                "frozen R2 disposition plus canonical state",
                "FAILURE_EVIDENCE_CAPTURE",
            ),
        }
    )
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "authorization": AUTHORIZATION,
        "diagnostic_id": DIAGNOSTIC_ID,
        "disposition": DISPOSITION,
        "source_unit": SOURCE_UNIT,
        "original_bytes_policy": "PRESERVE_AT_ORIGINAL_PATH_AND_VERIFY_SHA256",
        "created_at": _timestamp(created_at),
        "entries": [
            {
                "archive_path": archive_name,
                "source_locator": source,
                "kind": kind,
                "size_bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            }
            for archive_name, (payload, source, kind) in sorted(payloads.items())
        ],
    }
    _validate_manifest(paths.schema, manifest)
    manifest_bytes = canonical_json_bytes(manifest)
    paths.archive.parent.mkdir(parents=True, exist_ok=True)
    if paths.archive.parent.is_symlink():
        raise R2ArchiveBlocked("R2_ARCHIVE_PARENT_INVALID")
    temporary = paths.archive.with_name(f".{paths.archive.name}.tmp-{os.getpid()}")
    if os.path.lexists(temporary):
        raise R2ArchiveBlocked("R2_ARCHIVE_STALE_TEMP")
    temporary.mkdir(mode=0o700)
    try:
        for relative, (payload, _, _) in payloads.items():
            destination = temporary / _safe_relative(relative)
            destination.parent.mkdir(parents=True, exist_ok=True)
            _create_file(destination, payload)
        _create_file(temporary / "archive-manifest.json", manifest_bytes)
        for path in sorted(temporary.rglob("*"), reverse=True):
            os.chmod(path, 0o500 if path.is_dir() else 0o400)
        os.rename(temporary, paths.archive)
    except Exception:
        if temporary.exists():
            shutil.rmtree(temporary)
        raise
    for archive_name, source in originals.items():
        if _stable_file_bytes(source, "R2_ARCHIVE_ORIGINAL_DRIFT") != original_bytes[archive_name]:
            raise R2ArchiveBlocked("R2_ARCHIVE_ORIGINAL_DRIFT")
    for archive_name, source in (
        ("original/drop.inventory.json", paths.drop_root),
        ("original/staging.inventory.json", paths.staging_root),
    ):
        if canonical_json_bytes(_directory_inventory(source)) != directory_payloads[archive_name]:
            raise R2ArchiveBlocked("R2_ARCHIVE_ORIGINAL_DRIFT")
    return verify(paths=paths)


def _validate_unit_properties(payload: bytes) -> None:
    try:
        lines = payload.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise R2ArchiveBlocked("R2_ARCHIVE_UNIT_PROPERTIES_INVALID") from error
    properties: dict[str, str] = {}
    for line in lines:
        if "=" not in line:
            raise R2ArchiveBlocked("R2_ARCHIVE_UNIT_PROPERTIES_INVALID")
        key, value = line.split("=", 1)
        if key in properties:
            raise R2ArchiveBlocked("R2_ARCHIVE_UNIT_PROPERTIES_INVALID")
        properties[key] = value
    expected_keys = {
        "LoadState", "ActiveState", "SubState", "UnitFileState", "MainPID",
        "Result", "ExecMainCode", "ExecMainStatus", "FragmentPath",
    }
    if (
        set(properties) != expected_keys
        or properties["LoadState"] != "loaded"
        or properties["ActiveState"] != "inactive"
        or properties["SubState"] != "dead"
        or properties["MainPID"] != "0"
        or properties["UnitFileState"] in {"enabled", "enabled-runtime", "linked", "linked-runtime", "alias"}
    ):
        raise R2ArchiveBlocked("R2_ARCHIVE_SOURCE_NOT_QUIESCENT")


def _directory_inventory(root: Path) -> dict[str, Any]:
    if root.is_symlink() or not root.is_dir():
        raise R2ArchiveBlocked("R2_ARCHIVE_DIRECTORY_INVALID")
    entries: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        details = path.lstat()
        relative = path.relative_to(root).as_posix()
        if stat.S_ISLNK(details.st_mode):
            raise R2ArchiveBlocked("R2_ARCHIVE_SYMLINK_REJECT")
        if stat.S_ISDIR(details.st_mode):
            entries.append({"path": relative, "type": "DIRECTORY", "mode": stat.S_IMODE(details.st_mode)})
        elif stat.S_ISREG(details.st_mode):
            payload = _stable_file_bytes(path, "R2_ARCHIVE_DIRECTORY_FILE_INVALID")
            entries.append(
                {
                    "path": relative,
                    "type": "FILE",
                    "mode": stat.S_IMODE(details.st_mode),
                    "size_bytes": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                }
            )
        else:
            raise R2ArchiveBlocked("R2_ARCHIVE_SPECIAL_FILE_REJECT")
    return {"root": str(root), "entries": entries}


def _validate_schema_file(path: Path) -> None:
    payload = _stable_file_bytes(path, "R2_ARCHIVE_SCHEMA_INVALID")
    if hashlib.sha256(payload).hexdigest() != SCHEMA_SHA256:
        raise R2ArchiveBlocked("R2_ARCHIVE_SCHEMA_HASH_MISMATCH")
    try:
        Draft202012Validator.check_schema(json.loads(payload))
    except Exception as error:
        raise R2ArchiveBlocked("R2_ARCHIVE_SCHEMA_INVALID") from error


def _validate_manifest(schema_path: Path, manifest: dict[str, Any]) -> None:
    _validate_schema_file(schema_path)
    schema = json.loads(schema_path.read_bytes())
    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(manifest),
        key=lambda error: list(error.path),
    )
    if errors:
        raise R2ArchiveBlocked("R2_ARCHIVE_MANIFEST_SCHEMA_MISMATCH")
    archive_paths = [entry["archive_path"] for entry in manifest["entries"]]
    if archive_paths != sorted(archive_paths) or len(archive_paths) != len(set(archive_paths)):
        raise R2ArchiveBlocked("R2_ARCHIVE_MANIFEST_ORDER_INVALID")
    if any(not _SHA256.fullmatch(entry["sha256"]) for entry in manifest["entries"]):
        raise R2ArchiveBlocked("R2_ARCHIVE_MANIFEST_HASH_INVALID")


def _stable_file_bytes(path: Path, code: str) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise R2ArchiveBlocked(code)
    before = path.stat()
    payload = path.read_bytes()
    after = path.stat()
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns
    ):
        raise R2ArchiveBlocked(code)
    return payload


def _safe_relative(value: str) -> Path:
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or "\\" in value or not value:
        raise R2ArchiveBlocked("R2_ARCHIVE_PATH_INVALID")
    return path


def _create_file(path: Path, payload: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())


def _timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _run(arguments: Sequence[str]) -> bytes:
    try:
        completed = subprocess.run(
            list(arguments), check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except subprocess.CalledProcessError as error:
        raise R2ArchiveBlocked("R2_ARCHIVE_SYSTEM_CAPTURE_FAILED") from error
    return completed.stdout


if __name__ == "__main__":
    raise SystemExit(main())
