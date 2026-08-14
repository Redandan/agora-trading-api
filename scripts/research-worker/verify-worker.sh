#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT="${WORKER_ROOT:-/opt/agora-research-worker}"
DATA_ROOT="${DATA_ROOT:-/var/lib/agora-research}"
WORKER_USER="${WORKER_USER:-agora-research}"
SOURCE_USER="${SOURCE_USER:-agora-evidence-source}"
EVIDENCE_GROUP="${EVIDENCE_GROUP:-agora-evidence}"
EXPECT_TIMER="${EXPECT_TIMER:-disabled}"
RUN_HEARTBEAT="${RUN_HEARTBEAT:-0}"
RUN_SOURCE_PROBE="${RUN_SOURCE_PROBE:-0}"
EXPECT_MICROSTRUCTURE_SOURCE="${EXPECT_MICROSTRUCTURE_SOURCE:-disabled}"
EXPECT_CARRY_SOURCE="${EXPECT_CARRY_SOURCE:-auto}"
MICROSTRUCTURE_INTAKE_PREFLIGHT="${MICROSTRUCTURE_INTAKE_PREFLIGHT:-0}"
EXPECTED_CONTROL_RELEASE_ID="${EXPECTED_CONTROL_RELEASE_ID:?EXPECTED_CONTROL_RELEASE_ID is required}"
EXPECTED_DATA_RELEASE_ID="${EXPECTED_DATA_RELEASE_ID:?EXPECTED_DATA_RELEASE_ID is required}"
DISPATCH_UNIT=agora-research-dispatch.service
HEARTBEAT_UNIT=agora-research-heartbeat.service
MICROSTRUCTURE_UNIT=agora-research-microstructure-source.service
MICROSTRUCTURE_INTAKE_UNIT=agora-research-microstructure-intake.service
MICROSTRUCTURE_INTAKE_PATH=agora-research-microstructure-intake.path
MICROSTRUCTURE_EXPORT_UNIT=agora-research-microstructure-handoff-export.service
MICROSTRUCTURE_HOST_CONTEXT_DROPIN=/etc/systemd/system/agora-research-microstructure-source.service.d/10-host-context.conf
MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v3r1.json
MICROSTRUCTURE_DROP=/var/lib/agora-evidence-source/microstructure-v3r1-drop
MICROSTRUCTURE_STAGING=/var/lib/agora-evidence-source/microstructure-v3r1-private-staging
MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure-v3r1"
R2_MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v3.json
R2_MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure-v3"
R2_MICROSTRUCTURE_ARCHIVE="$DATA_ROOT/state/microstructure-archive/okx-btcusdt-microstructure-forward-v3-20260811-r2"
MICROSTRUCTURE_LOCAL_TASK=/etc/agora-research/local-tasks/microstructure-v3r1-evidence-diagnostic.v1.json
MICROSTRUCTURE_HANDOFF_STAGING="$DATA_ROOT/microstructure-v3r1-handoff-staging"
MICROSTRUCTURE_HANDOFF_FINAL="$DATA_ROOT/microstructure-v3r1-handoff-export"
LEGACY_MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v1.json
LEGACY_MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure"
LEGACY_MICROSTRUCTURE_PRESERVATION="$DATA_ROOT/microstructure-v3-cutover/legacy-v2.sha256"
MICROSTRUCTURE_DIST=target/microstructure-dist
MICROSTRUCTURE_JAR="$MICROSTRUCTURE_DIST/agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar"
CARRY_USER=agora-dra-carry-source
CARRY_GROUP=agora-dra-carry-publish
CARRY_UNIT=agora-research-dra-crypto-carry-source.service
CARRY_BINDING=/etc/agora-research/okx-dra-crypto-carry-source-v2.json
CARRY_REQUEST_ROOT="$DATA_ROOT/dra-crypto-carry-source-request-v2"
CARRY_ROOT=/var/lib/agora-dra-carry-source
CARRY_PRIVATE="$CARRY_ROOT/dra-crypto-carry-v2-private"
CARRY_INVENTORY_STAGING="$CARRY_ROOT/dra-crypto-carry-v2-inventory-staging"
CARRY_INVENTORY_DROP="$CARRY_ROOT/dra-crypto-carry-v2-inventory-drop"
CARRY_DAY_STAGING="$CARRY_ROOT/dra-crypto-carry-v2-day-staging"
CARRY_DAY_DROP="$CARRY_ROOT/dra-crypto-carry-v2-day-drop"
CARRY_DIST=target/dra-crypto-carry-dist
CARRY_JAR="$CARRY_DIST/agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"

fail() {
  echo "[research-worker-verify] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[research-worker-verify] OK: $*"
}

require_sha256() {
  local path="$1"
  local expected="$2"
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "frozen research contract missing or symlinked: $path"
  [ "$(sha256sum "$path" | awk '{print $1}')" = "$expected" ] \
    || fail "frozen research contract hash mismatch: $path"
}

snapshot_legacy_microstructure() {
  sudo python3 - "$LEGACY_MICROSTRUCTURE_BINDING" "$LEGACY_MICROSTRUCTURE_STATE" <<'PY'
import hashlib
import os
from pathlib import Path
import re
import stat
import sys

binding, state_root = map(Path, sys.argv[1:])
lines = []
if os.path.lexists(binding):
    details = binding.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise SystemExit("legacy V1 binding is not a regular non-symlink file")
    lines.append(f"binding {hashlib.sha256(binding.read_bytes()).hexdigest()}")
else:
    lines.append("binding ABSENT")
if os.path.lexists(state_root):
    details = state_root.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISDIR(details.st_mode):
        raise SystemExit("legacy V2 state root is not a regular non-symlink directory")
    entries = sorted(os.scandir(state_root), key=lambda entry: entry.name)
    if len(entries) > 1:
        raise SystemExit("legacy V2 state inventory is not singular")
    if not entries:
        lines.append("state EMPTY")
    for entry in entries:
        details = entry.stat(follow_symlinks=False)
        if (
            entry.is_symlink()
            or not stat.S_ISREG(details.st_mode)
            or re.fullmatch(r"[a-z0-9][a-z0-9-]{2,79}\.json", entry.name) is None
        ):
            raise SystemExit("legacy V2 state inventory contains a symlink, lock, temp, or noncanonical entry")
        path = Path(entry.path)
        lines.append(f"state/{entry.name} {hashlib.sha256(path.read_bytes()).hexdigest()}")
else:
    lines.append("state ABSENT")
print("\n".join(lines))
PY
}

case "$EXPECTED_CONTROL_RELEASE_ID" in *[!A-Za-z0-9._-]*|'') fail "invalid expected control release id" ;; esac
case "$EXPECTED_DATA_RELEASE_ID" in *[!A-Za-z0-9._-]*|'') fail "invalid expected data release id" ;; esac
case "$EXPECT_CARRY_SOURCE" in
  auto|absent|inactive) ;;
  *) fail "unsupported EXPECT_CARRY_SOURCE: $EXPECT_CARRY_SOURCE" ;;
esac
[ -L "$WORKER_ROOT/control-current" ] || fail "control-current release symlink missing"
[ -L "$WORKER_ROOT/current" ] || fail "data-current release symlink missing"
control_current="$(readlink -f "$WORKER_ROOT/control-current")"
data_current="$(readlink -f "$WORKER_ROOT/current")"
case "$control_current" in
  "$WORKER_ROOT"/releases/*) ;;
  *) fail "control-current release escapes worker root: $control_current" ;;
esac
case "$data_current" in
  "$WORKER_ROOT"/releases/*) ;;
  *) fail "data-current release escapes worker root: $data_current" ;;
esac
[ "$(basename "$control_current")" = "$EXPECTED_CONTROL_RELEASE_ID" ] \
  || fail "control-current release id does not match the exact expectation"
[ "$(basename "$data_current")" = "$EXPECTED_DATA_RELEASE_ID" ] \
  || fail "data-current release id does not match the exact expectation"
[ -f "$control_current/research_pipeline/policy.v3.json" ] || fail "control V3 policy missing"
[ -s "$control_current/.release/source.sha256" ] || fail "control release source manifest missing"
[ -s "$control_current/.release/provenance.json" ] || fail "control release provenance missing"
[ -d "$data_current/research_source" ] || fail "data-plane forward evidence source missing"
[ -s "$data_current/.release/source.sha256" ] || fail "data release source manifest missing"
[ -s "$data_current/.release/provenance.json" ] || fail "data release provenance missing"
legacy_microstructure_inventory="$(snapshot_legacy_microstructure)" \
  || fail "legacy V1/V2 inventory is ambiguous"
if sudo test -e "$LEGACY_MICROSTRUCTURE_PRESERVATION" \
    || sudo test -L "$LEGACY_MICROSTRUCTURE_PRESERVATION"; then
  sudo test -f "$LEGACY_MICROSTRUCTURE_PRESERVATION" \
    && ! sudo test -L "$LEGACY_MICROSTRUCTURE_PRESERVATION" \
    || fail "legacy V1/V2 preservation seal is not a regular file"
  [ "$(sudo stat -c '%U:%G:%a' "$LEGACY_MICROSTRUCTURE_PRESERVATION")" = "root:root:400" ] \
    || fail "legacy V1/V2 preservation seal metadata changed"
  [ "$(sudo cat "$LEGACY_MICROSTRUCTURE_PRESERVATION")" = "$legacy_microstructure_inventory" ] \
    || fail "legacy V1/V2 bytes no longer match their cutover seal"
elif sudo test -e "$MICROSTRUCTURE_BINDING" \
    || sudo test -L "$MICROSTRUCTURE_BINDING" \
    || sudo test -e "$LEGACY_MICROSTRUCTURE_BINDING" \
    || sudo test -L "$LEGACY_MICROSTRUCTURE_BINDING" \
    || sudo test -e "$LEGACY_MICROSTRUCTURE_STATE" \
    || sudo test -L "$LEGACY_MICROSTRUCTURE_STATE"; then
  fail "legacy V1/V2 preservation seal is missing"
fi
while IFS= read -r legacy_line; do
  ok "legacy V1/V2 preserved: $legacy_line"
done <<< "$legacy_microstructure_inventory"
python3 - "$control_current" <<'PY'
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import zipfile

root = Path(sys.argv[1])
source_roots = {"research_pipeline", "research_mcp", "research_source", "research"}
expected_top = source_roots | {"docs", "scripts", "target", ".release"}
dist_jar = "agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar"
carry_dist_jar = "agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"
carry_libraries = {
    "jackson-annotations-2.18.3.jar": "8aa5740d80b5a5025508b41bbadbaa1fb3772267c628b2e30681a4f45f8b8931",
    "jackson-core-2.18.3.jar": "056bc4d3e5e53ce821450fa97b3f9e0f8dde125cf6da6884353bb1f09582e1d9",
    "jackson-databind-2.18.3.jar": "510bdda75a7a6186c5bf33b851239488a1450906ae5757121f2e1cc48a7e108f",
}
carry_families = {
    "OkxDraCryptoCarryForwardSource",
    "OkxDraCryptoCarryProducerEnvelopeV2",
    "OkxDraCryptoCarryCanonicalDropV2",
    "OkxDraCryptoCarryNetworkDeniedIntakeV2",
    "OkxDraCryptoCarryPhaseCli",
}


def fail(message: str) -> None:
    raise SystemExit(message)


def forbidden(relative: str) -> bool:
    path = PurePosixPath(relative)
    if any(ord(character) < 32 or ord(character) == 127 for character in relative):
        return True
    if path.is_absolute() or ".." in path.parts:
        return True
    if any(part in {".git", ".research-state", "__pycache__"} for part in path.parts):
        return True
    name = path.name
    return bool(
        re.search(r"(?i)\.(pyc|pyo|pyd|pem|p12|pfx|key)$", name)
        or re.search(r"(?i)^\.env(?:\.|$)", name)
        or re.search(
            r"(?i)(?:^|[._-])(secret|secrets|credential|credentials)(?:[._-]|$)",
            name,
        )
    )


def allowed_runtime(relative: str) -> bool:
    parts = PurePosixPath(relative).parts
    if not parts:
        return False
    if parts[0] in source_roots:
        return True
    if parts in {
        ("docs",),
        ("docs", "autonomous-research-charter.md"),
        ("scripts",),
        ("target",),
    }:
        return True
    if parts[:2] == ("scripts", "research-worker"):
        return True
    return parts[:2] in {
        ("target", "microstructure-dist"),
        ("target", "dra-crypto-carry-dist"),
    }


for required_path in (
    "scripts",
    "scripts/research-worker/x",
    "target",
    "target/microstructure-dist/x",
    "target/dra-crypto-carry-dist/x",
    "docs",
    "docs/autonomous-research-charter.md",
):
    if forbidden(required_path) or not allowed_runtime(required_path):
        fail(f"closure predicate rejected required path: {required_path}")
for rejected_path in (
    "docs/other.md",
    "docs/autonomous-research-charter.md/other",
    "src",
    "pom.xml",
    "scripts/other",
    "target/other",
    ".research-state",
    "research_pipeline/.env",
    "research_pipeline/secret.json",
    "research_pipeline/__pycache__",
    "research_pipeline/module.pyc",
):
    if allowed_runtime(rejected_path) and not forbidden(rejected_path):
        fail(f"closure predicate accepted forbidden path: {rejected_path}")


def exact_directory(path: Path, names: set[str], label: str) -> None:
    entries = list(os.scandir(path))
    if {entry.name for entry in entries} != names:
        fail(f"{label} differs from the frozen closure")


top_entries = list(os.scandir(root))
if {entry.name for entry in top_entries} != expected_top:
    fail("installed release top-level inventory differs from the frozen closure")
if any(entry.is_symlink() or not entry.is_dir(follow_symlinks=False) for entry in top_entries):
    fail("installed release top level must contain regular directories only")
exact_directory(root / "scripts", {"research-worker"}, "installed scripts inventory")
target_names = {entry.name for entry in os.scandir(root / "target")}
if target_names == {"microstructure-dist"}:
    dual_package = False
elif target_names == {"microstructure-dist", "dra-crypto-carry-dist"}:
    dual_package = True
else:
    fail("installed target inventory is neither the frozen micro-only nor dual closure")
exact_directory(
    root / "docs",
    {"autonomous-research-charter.md"},
    "installed documentation inventory",
)
exact_directory(
    root / ".release",
    {"source.sha256", "provenance.json"},
    "installed release metadata inventory",
)
required_directories = [
    root / "scripts" / "research-worker",
    root / "target" / "microstructure-dist",
    root / ".release",
]
if dual_package:
    required_directories.append(root / "target" / "dra-crypto-carry-dist")
for required_directory in required_directories:
    details = required_directory.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISDIR(details.st_mode):
        fail(f"installed path is not a regular directory: {required_directory.name}")
charter = root / "docs" / "autonomous-research-charter.md"
charter_details = charter.lstat()
if stat.S_ISLNK(charter_details.st_mode) or not stat.S_ISREG(charter_details.st_mode):
    fail("installed research charter is not a regular non-symlink file")

dist = root / "target" / "microstructure-dist"
exact_directory(dist, {dist_jar, "lib"}, "installed microstructure distribution root")
jar_entry = next(entry for entry in os.scandir(dist) if entry.name == dist_jar)
lib_entry = next(entry for entry in os.scandir(dist) if entry.name == "lib")
if jar_entry.is_symlink() or not jar_entry.is_file(follow_symlinks=False):
    fail("installed microstructure producer jar is not a regular file")
if lib_entry.is_symlink() or not lib_entry.is_dir(follow_symlinks=False):
    fail("installed microstructure library root is not a regular directory")
libraries = list(os.scandir(dist / "lib"))
library_names = sorted(entry.name for entry in libraries)
if len(libraries) != 3 or any(
    entry.is_symlink() or not entry.is_file(follow_symlinks=False)
    for entry in libraries
):
    fail("installed distribution must contain exactly three library files")
patterns = (
    r"jackson-annotations-.+\.jar",
    r"jackson-core-.+\.jar",
    r"jackson-databind-.+\.jar",
)
if any(re.fullmatch(pattern, name) is None for pattern, name in zip(patterns, library_names)):
    fail("installed distribution contains unexpected libraries")

if dual_package:
    carry_dist = root / "target" / "dra-crypto-carry-dist"
    exact_directory(carry_dist, {carry_dist_jar, "lib"}, "installed carry distribution root")
    carry_jar = carry_dist / carry_dist_jar
    carry_lib = carry_dist / "lib"
    for path, expected_type in ((carry_jar, stat.S_ISREG), (carry_lib, stat.S_ISDIR)):
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not expected_type(details.st_mode):
            fail("installed carry distribution entry type is invalid")
    carry_library_paths = {path.name: path for path in carry_lib.iterdir()}
    if set(carry_library_paths) != set(carry_libraries):
        fail("installed carry Jackson inventory is not exact")
    for name, path in carry_library_paths.items():
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
            fail("installed carry Jackson dependency is not a regular file")
        if hashlib.sha256(path.read_bytes()).hexdigest() != carry_libraries[name]:
            fail("installed carry Jackson dependency hash mismatch")
    with zipfile.ZipFile(carry_jar) as archive:
        carry_classes = {name for name in archive.namelist() if name.endswith(".class")}
    if any(name.startswith("BOOT-INF/") for name in carry_classes):
        fail("installed carry classifier contains BOOT-INF")
    for name in carry_classes:
        if not any(
            name == f"com/agora/research/{family}.class"
            or name.startswith(f"com/agora/research/{family}$")
            for family in carry_families
        ):
            fail(f"unexpected installed carry class: {name}")
    for family in carry_families:
        if f"com/agora/research/{family}.class" not in carry_classes:
            fail(f"missing installed carry class family: {family}")

runtime_files: dict[str, Path] = {}
release_files: dict[str, Path] = {}


def walk(directory: Path, prefix: str = "") -> None:
    for entry in os.scandir(directory):
        relative = f"{prefix}/{entry.name}" if prefix else entry.name
        details = entry.stat(follow_symlinks=False)
        if entry.is_symlink() or stat.S_ISLNK(details.st_mode):
            fail(f"installed release contains a symlink: {relative}")
        is_release = relative == ".release" or relative.startswith(".release/")
        if forbidden(relative) or (not is_release and not allowed_runtime(relative)):
            fail(f"installed release contains a forbidden path: {relative}")
        if stat.S_ISDIR(details.st_mode):
            walk(Path(entry.path), relative)
        elif stat.S_ISREG(details.st_mode):
            (release_files if is_release else runtime_files)[relative] = Path(entry.path)
        else:
            fail(f"installed release contains a non-regular entry: {relative}")


walk(root)
if set(release_files) != {".release/source.sha256", ".release/provenance.json"}:
    fail("installed release metadata closure is invalid")
manifest = release_files[".release/source.sha256"]
raw_manifest = manifest.read_bytes()
if b"\r" in raw_manifest or not raw_manifest.endswith(b"\n"):
    fail("installed source manifest must be canonical LF-terminated UTF-8")
try:
    manifest_lines = raw_manifest.decode("utf-8").splitlines()
except UnicodeDecodeError as error:
    fail(f"installed source manifest is not UTF-8: {error}")
if not manifest_lines or manifest_lines != sorted(manifest_lines):
    fail("installed source manifest must be nonempty and sorted")
listed: dict[str, str] = {}
for line in manifest_lines:
    match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
    if match is None:
        fail("installed source manifest contains a malformed line")
    digest, relative = match.groups()
    if relative in listed or forbidden(relative) or not allowed_runtime(relative):
        fail("installed source manifest contains a duplicate or forbidden path")
    listed[relative] = digest
if set(listed) != set(runtime_files):
    fail("installed source manifest has omissions or extra paths")
for relative, expected in listed.items():
    if hashlib.sha256(runtime_files[relative].read_bytes()).hexdigest() != expected:
        fail(f"installed source hash mismatch: {relative}")

with release_files[".release/provenance.json"].open(encoding="utf-8") as stream:
    provenance = json.load(stream)
expected_provenance_keys = {
    "schema_version",
    "release_id",
    "source_git_commit",
    "source_git_branch",
    "source_git_dirty",
    "source_manifest_sha256",
    "installed_at",
}
if set(provenance) != expected_provenance_keys or provenance["schema_version"] != "1":
    fail("invalid release provenance schema")
if provenance["release_id"] != root.name:
    fail("release provenance id does not match installed directory")
if not re.fullmatch(r"[0-9a-f]{40}", str(provenance["source_git_commit"])):
    fail("invalid release source commit")
if provenance["source_git_dirty"] is not False:
    fail("installed release provenance is dirty")
manifest_hash = hashlib.sha256(raw_manifest).hexdigest()
if provenance["source_manifest_sha256"] != manifest_hash:
    fail("release provenance manifest hash does not match installed bytes")
PY
carry_package="$(python3 - "$data_current" "$EXPECTED_DATA_RELEASE_ID" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

root = Path(sys.argv[1])
expected_release_id = sys.argv[2]
manifest = root / ".release" / "source.sha256"
provenance_path = root / ".release" / "provenance.json"
for path in (manifest, provenance_path):
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        raise SystemExit("data release metadata is missing or symlinked")
raw_manifest = manifest.read_bytes()
manifest_hash = hashlib.sha256(raw_manifest).hexdigest()
with provenance_path.open(encoding="utf-8") as stream:
    provenance = json.load(stream)
expected_keys = {
    "schema_version",
    "release_id",
    "source_git_commit",
    "source_git_branch",
    "source_git_dirty",
    "source_manifest_sha256",
    "installed_at",
}
if set(provenance) != expected_keys or provenance.get("schema_version") != "1":
    raise SystemExit("data release provenance schema is invalid")
if provenance.get("release_id") != expected_release_id or root.name != expected_release_id:
    raise SystemExit("data release provenance id does not match exact expectation")
if provenance.get("source_manifest_sha256") != manifest_hash:
    raise SystemExit("data release provenance manifest hash does not match bytes")
if provenance.get("source_git_dirty") is not False:
    raise SystemExit("data release provenance is dirty")
if re.fullmatch(r"[0-9a-f]{40}", str(provenance.get("source_git_commit"))) is None:
    raise SystemExit("data release source commit is invalid")

target = root / "target"
target_entries = list(os.scandir(target))
if any(entry.is_symlink() or not entry.is_dir(follow_symlinks=False) for entry in target_entries):
    raise SystemExit("data release target contains a non-directory or symlink")
target_names = {entry.name for entry in target_entries}
if target_names == {"microstructure-dist"}:
    dual_package = False
elif target_names == {"microstructure-dist", "dra-crypto-carry-dist"}:
    dual_package = True
else:
    raise SystemExit("data release target is neither the frozen micro-only nor dual closure")

if dual_package:
    expected_libraries = {
        "jackson-annotations-2.18.3.jar": "8aa5740d80b5a5025508b41bbadbaa1fb3772267c628b2e30681a4f45f8b8931",
        "jackson-core-2.18.3.jar": "056bc4d3e5e53ce821450fa97b3f9e0f8dde125cf6da6884353bb1f09582e1d9",
        "jackson-databind-2.18.3.jar": "510bdda75a7a6186c5bf33b851239488a1450906ae5757121f2e1cc48a7e108f",
    }
    families = {
        "OkxDraCryptoCarryForwardSource",
        "OkxDraCryptoCarryProducerEnvelopeV2",
        "OkxDraCryptoCarryCanonicalDropV2",
        "OkxDraCryptoCarryNetworkDeniedIntakeV2",
        "OkxDraCryptoCarryPhaseCli",
    }
    dist = target / "dra-crypto-carry-dist"
    jar = dist / "agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"
    lib = dist / "lib"
    if {entry.name for entry in os.scandir(dist)} != {jar.name, "lib"}:
        raise SystemExit("data carry distribution root inventory is not exact")
    for path, expected_type in ((dist, stat.S_ISDIR), (jar, stat.S_ISREG), (lib, stat.S_ISDIR)):
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not expected_type(details.st_mode):
            raise SystemExit("data carry distribution entry type is invalid")
    libraries = {path.name: path for path in lib.iterdir()}
    if set(libraries) != set(expected_libraries):
        raise SystemExit("data carry Jackson inventory is not exact")
    for name, path in libraries.items():
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
            raise SystemExit("data carry Jackson dependency is not a regular file")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected_libraries[name]:
            raise SystemExit("data carry Jackson dependency hash mismatch")
    with zipfile.ZipFile(jar) as archive:
        classes = {name for name in archive.namelist() if name.endswith(".class")}
    if any(name.startswith("BOOT-INF/") for name in classes):
        raise SystemExit("data carry classifier contains BOOT-INF")
    for name in classes:
        if not any(
            name == f"com/agora/research/{family}.class"
            or name.startswith(f"com/agora/research/{family}$")
            for family in families
        ):
            raise SystemExit(f"unexpected data carry class: {name}")
    for family in families:
        if f"com/agora/research/{family}.class" not in classes:
            raise SystemExit(f"missing data carry class family: {family}")
    entries = {}
    for line in raw_manifest.decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None or match.group(2) in entries:
            raise SystemExit("data release source manifest is malformed")
        entries[match.group(2)] = match.group(1)
    actual = set()
    for path in dist.rglob("*"):
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode):
            raise SystemExit("data carry distribution contains a symlink")
        if stat.S_ISREG(details.st_mode):
            actual.add(path.relative_to(root).as_posix())
    listed = {path for path in entries if path.startswith("target/dra-crypto-carry-dist/")}
    if actual != listed:
        raise SystemExit("data carry distribution is not exactly covered by manifest")
    for relative in actual:
        if hashlib.sha256((root / relative).read_bytes()).hexdigest() != entries[relative]:
            raise SystemExit(f"data carry manifest hash mismatch: {relative}")

print("true" if dual_package else "false")
PY
)"
ok "hermetic control release and exact data release provenance verified"

require_sha256 "$data_current/research_pipeline/okx-microstructure-continuous-source-contract.v3.json" \
  8a581cc03eb9381af4bfecddb8f40c7d23759ce239647447bc37351e4f293422
require_sha256 "$data_current/research_pipeline/okx-microstructure-drop-envelope.v3.schema.json" \
  ad6e23797240a9e4a86affff40e801d7d659a8a408ffad65270a42dec2b46418
require_sha256 "$data_current/research_pipeline/okx-microstructure-intake-state.v3.schema.json" \
  935da25d8f5e66bb4ec13625ff2e8eb7480e503f8c4d580abd41514ee90aa7fc
require_sha256 "$data_current/research_pipeline/okx-microstructure-forward-day.v3.schema.json" \
  205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709
require_sha256 "$data_current/research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json" \
  7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-recovery-contract.v3r1.json" \
  6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-source-binding.v3r1.schema.json" \
  1d07c67e6668ba8f7f01ebcb4a71d702e855cc6d40bb2e6260dbb30f97c2e60b
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-complete-envelope.v3r1.schema.json" \
  a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-rejection-envelope.v3r1.schema.json" \
  833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-intake-state.v3r1.schema.json" \
  12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d
require_sha256 "$data_current/research_pipeline/okx-microstructure-discovery-r2-archive-manifest.v1.schema.json" \
  050542e9c0668738cb25e60dc00343274e28d4514cd33ad8cc30daf249ce5f7e
require_sha256 "$data_current/research_pipeline/microstructure-discovery-handoff-manifest.v3r1.schema.json" \
  eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617
ok "frozen historical V3 and recovery V3R1 contract hashes verified"

[ -f "$data_current/$MICROSTRUCTURE_JAR" ] && [ ! -L "$data_current/$MICROSTRUCTURE_JAR" ] \
  || fail "narrow microstructure producer jar missing or symlinked"
[ -d "$data_current/$MICROSTRUCTURE_DIST/lib" ] \
  && [ ! -L "$data_current/$MICROSTRUCTURE_DIST/lib" ] \
  || fail "microstructure runtime dependency directory missing or symlinked"
if find "$data_current/$MICROSTRUCTURE_DIST" -type l -print -quit | grep -q .; then
  fail "microstructure distribution contains a symlink"
fi
mapfile -t microstructure_libraries < <(
  find "$data_current/$MICROSTRUCTURE_DIST/lib" -maxdepth 1 -type f -printf '%f\n' | sort
)
[ "${#microstructure_libraries[@]}" = 3 ] \
  || fail "microstructure distribution must contain exactly three runtime libraries"
[[ "${microstructure_libraries[0]}" == jackson-annotations-*.jar ]] \
  && [[ "${microstructure_libraries[1]}" == jackson-core-*.jar ]] \
  && [[ "${microstructure_libraries[2]}" == jackson-databind-*.jar ]] \
  || fail "microstructure distribution contains unexpected runtime libraries"

java_version="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ { print $2; exit }')"
[ "$java_version" = "21" ] || fail "Java 21 is required for microstructure source"
microstructure_inventory="$(mktemp)"
cleanup_microstructure_inventory() {
  rm -f -- "$microstructure_inventory"
}
trap cleanup_microstructure_inventory EXIT
jar tf "$data_current/$MICROSTRUCTURE_JAR" > "$microstructure_inventory"
if grep -Evq '^(META-INF(/.*)?|com/|com/agora/|com/agora/research/|com/agora/research/OkxMicrostructure[^/]*\.class)$' \
    "$microstructure_inventory"; then
  fail "microstructure jar contains a class outside the frozen package prefix"
fi
grep -Fxq 'com/agora/research/OkxMicrostructureDiscoveryRecoverySourceCli.class' \
  "$microstructure_inventory" \
  || fail "V3R1 recovery source main class missing"
cleanup_microstructure_inventory
trap - EXIT

python3 - "$data_current" <<'PY'
from pathlib import Path
import re
import sys

current = Path(sys.argv[1])
manifest = current / ".release" / "source.sha256"
entries = {}
for line in manifest.read_text(encoding="utf-8").splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
    if not match or match.group(2) in entries:
        raise SystemExit("installed source manifest is malformed")
    entries[match.group(2)] = match.group(1)
dist = current / "target" / "microstructure-dist"
actual = {
    path.relative_to(current).as_posix()
    for path in dist.rglob("*")
    if path.is_file() and not path.is_symlink()
}
listed = {path for path in entries if path.startswith("target/microstructure-dist/")}
if actual != listed:
    raise SystemExit("microstructure distribution is not exactly covered by manifest")
PY
ok "sealed direct-Java-21 microstructure distribution verified"

if [ "$carry_package" = true ]; then
  require_sha256 "$data_current/research_pipeline/okx-dra-crypto-carry-expiry-futures-source-contract.v2.json" \
    183eeb35dc4729ff91970e4b892f141f58452abfa350591888587ce01035e4ad
  require_sha256 "$data_current/research_pipeline/okx-dra-crypto-carry-producer-envelope.v2.schema.json" \
    814fbef9722dcdd2a6dac8c56e159c1a34e7c2db559c306709c0c393e05230ee
  require_sha256 "$data_current/research_pipeline/okx-dra-crypto-carry-inventory-drop-envelope.v2.schema.json" \
    59e85d80aa4d2188af57872b7a2731881c85fd949fd7378c8a75cbff4dcdb196
  require_sha256 "$data_current/research_pipeline/okx-dra-crypto-carry-drop-envelope.v2.schema.json" \
    a438ba041e0ac80e3757f842659f2afa14b701c9a94034b1f59dffa5e2aa0563
  require_sha256 "$data_current/research_pipeline/okx-dra-crypto-carry-intake-state.v2.schema.json" \
    2c8af00a076616ffc25b95a2709bde1d4b6b7efb5899240e50d7c9f9322060d8
  ok "sealed direct-Java-21 carry distribution and frozen V2 hashes verified"
else
  ok "carry distribution absent; no carry readiness claim"
fi

if [ -e "$MICROSTRUCTURE_BINDING" ] || [ -L "$MICROSTRUCTURE_BINDING" ]; then
  [ -f "$MICROSTRUCTURE_BINDING" ] && [ ! -L "$MICROSTRUCTURE_BINDING" ] \
    || fail "microstructure binding is missing, non-regular, or symlinked"
  [ "$(stat -c '%U:%G' "$MICROSTRUCTURE_BINDING")" = "root:$EVIDENCE_GROUP" ] \
    || fail "microstructure binding ownership is incorrect"
  [ "$(stat -c '%a' "$MICROSTRUCTURE_BINDING")" = "640" ] \
    || fail "microstructure binding mode is incorrect"
  (
    cd "$data_current"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" - \
      "$MICROSTRUCTURE_BINDING" \
      "$data_current" \
      "$EXPECT_MICROSTRUCTURE_SOURCE" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import sys

binding_path = Path(sys.argv[1])
current = Path(sys.argv[2])
expected_source = sys.argv[3]
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    RuntimePaths,
    _load_binding,
)

paths = RuntimePaths(
    binding=binding_path,
    drop_root=Path("/var/lib/agora-evidence-source/microstructure-v3r1-drop"),
    staging_root=Path("/var/lib/agora-evidence-source/microstructure-v3r1-private-staging"),
    state_root=Path("/var/lib/agora-research/state/microstructure-v3r1"),
    release=current,
)
binding = _load_binding(
    paths, require_future=False, today=datetime.now(timezone.utc).date()
)
if expected_source != "active" and binding["start_day"] <= datetime.now(timezone.utc).date().isoformat():
    raise SystemExit("binding forward start day is not strictly future for an inactive source")
PY
  )
  ok "V3R1 microstructure binding matches installed release"
fi
(
  cd "$data_current"
  sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" \
    -m research_pipeline.microstructure_discovery_r2_archive verify >/dev/null
) || fail "R2 create-only archive or original bytes failed hash verification"
ok "R2 binding, state, drop metadata, release provenance, journal, and failure evidence remain hash verified"
[ -d "$DATA_ROOT" ] && [ ! -L "$DATA_ROOT" ] \
  || fail "canonical data root is missing, non-directory, or symlinked"
[ "$(sudo stat -c '%U:%G:%a' "$DATA_ROOT")" = "$WORKER_USER:$EVIDENCE_GROUP:710" ] \
  || fail "canonical data root ownership or traversal mode is incorrect"
sudo -u "$SOURCE_USER" test -x "$DATA_ROOT" \
  || fail "public source identity cannot traverse the canonical data root"
sudo test -f "$DATA_ROOT/state/authority.json" || fail "state authority missing"
[ "$(sudo stat -c '%U:%G' "$DATA_ROOT/state")" = "$WORKER_USER:$WORKER_USER" ] \
  || fail "canonical state owner is incorrect"

sudo python3 - "$DATA_ROOT/state/authority.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
if value.get("mode") != "SERVER_CANONICAL":
    raise SystemExit("authority is not SERVER_CANONICAL")
PY
ok "canonical state authority verified"

if sudo -u "$WORKER_USER" test -r /home/ubuntu/.env.trading.secrets; then
  fail "worker can read Trading secrets"
fi
ok "Trading secret file is not readable by worker"
if sudo -u "$SOURCE_USER" test -r /home/ubuntu/.env.trading.secrets; then
  fail "public source identity can read Trading secrets"
fi
if sudo -u "$SOURCE_USER" test -r "$DATA_ROOT/state/authority.json"; then
  fail "public source identity can read canonical research state"
fi
if sudo -u "$SOURCE_USER" test -w "$DATA_ROOT/state"; then
  fail "public source identity can write canonical research state"
fi
if id -nG "$WORKER_USER" | tr ' ' '\n' | grep -Fxq "$EVIDENCE_GROUP"; then
  fail "canonical worker account inherits the publisher group"
fi
for evidence_reader_unit in agora-research-mcp.service agora-research-evidence-ingest.service; do
  systemctl show "$evidence_reader_unit" --property=SupplementaryGroups --value \
    | tr ' ' '\n' | grep -Fxq "$EVIDENCE_GROUP" \
    || fail "$evidence_reader_unit lost its explicit evidence-group access"
done
ok "public source identity can traverse only the shared root and cannot read Trading secrets or canonical state"

(
  cd "$control_current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    AGORA_RESEARCH_APP_DIR="$control_current" \
    "$WORKER_ROOT/venv/bin/python" -m research_pipeline \
    --state-dir "$DATA_ROOT/state" \
    --policy "$control_current/research_pipeline/policy.v3.json" \
    status --json >/dev/null
)
ok "canonical research registry is readable"

(
  cd "$control_current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    AGORA_RESEARCH_APP_DIR="$control_current" \
    "$WORKER_ROOT/venv/bin/python" - <<'PY'
from research_mcp.queue import _worker_release_summary

release = _worker_release_summary()
if release.get("status") != "READY":
    raise SystemExit(f"worker release provenance is not ready: {release.get('status')}")
if release.get("source_git_dirty") is not False:
    raise SystemExit("worker release provenance is dirty")
if release.get("source_tree_verified") is not True:
    raise SystemExit("worker release source tree was not verified")
if (
    not isinstance(release.get("source_file_count"), int)
    or release["source_file_count"] < 1
):
    raise SystemExit("worker release source file count is invalid")
PY
)
ok "worker identity can verify clean release provenance"

(
  cd "$control_current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    AGORA_RESEARCH_APP_DIR="$data_current" \
    "$WORKER_ROOT/venv/bin/python" - <<'PY'
from research_mcp.queue import _worker_release_summary

release = _worker_release_summary()
if release.get("status") != "READY":
    raise SystemExit(f"data release provenance is not ready: {release.get('status')}")
if release.get("source_git_dirty") is not False:
    raise SystemExit("data release provenance is dirty")
if release.get("source_tree_verified") is not True:
    raise SystemExit("data release source tree was not verified")
if (
    not isinstance(release.get("source_file_count"), int)
    or release["source_file_count"] < 1
):
    raise SystemExit("data release source file count is invalid")
PY
)
ok "trusted control code verified the complete data release source inventory"

(
  cd "$control_current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    "$WORKER_ROOT/venv/bin/python" -m unittest \
    research_pipeline.tests.test_corpus \
    research_pipeline.tests.test_evidence \
    research_pipeline.tests.test_forward_candidate \
    research_pipeline.tests.test_storage \
    research_mcp.tests.test_queue \
    research_mcp.tests.test_server_contract >/dev/null
)
(
  cd "$data_current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    "$WORKER_ROOT/venv/bin/python" -m unittest \
    research_source.tests.test_forward_source >/dev/null
)
ok "control and data-plane research contracts verified in isolated temporary state"

resume_probe="$(mktemp -d)"
case "$resume_probe" in
  /tmp/*) ;;
  *) fail "resume probe escaped /tmp: $resume_probe" ;;
esac
resume_app="$resume_probe/app"
resume_requests="$resume_probe/requests"
resume_inbox="$resume_probe/inbox"
resume_runtime="$resume_probe/runtime"
mkdir -p "$resume_app/scripts/research-worker" "$resume_requests" "$resume_inbox" "$resume_runtime"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'mkdir -p "$AGORA_RESEARCH_INBOX_DIR"' \
  'printf '\''%s\n'\'' '\''{"status":"HEARTBEAT_OK","research_status":"WAITING_FOR_EVIDENCE"}'\'' > "$AGORA_RESEARCH_INBOX_DIR/latest.json"' \
  > "$resume_app/scripts/research-worker/run-heartbeat.sh"
chmod 0700 "$resume_app/scripts/research-worker/run-heartbeat.sh"
python3 - "$resume_requests/running.json" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as stream:
    json.dump(
        {
            "schema_version": "1",
            "request_id": "a" * 32,
            "requested_at": "2026-08-05T00:00:00Z",
            "source": "CODEX_CLOUD_OPS",
            "operation": "RESEARCH_HEARTBEAT",
            "payload": {
                "schema_version": "1",
                "coach_delivery_receipts": [],
            },
            "payload_sha256": "844e4eb3a0ea121e02459ca1fd683b480756e9155b07b8490aa8467a0ac8461b",
            "status": "RUNNING",
            "started_at": "2026-08-05T00:00:01Z",
            "resume_count": 0,
        },
        stream,
        sort_keys=True,
    )
    stream.write("\n")
PY
APP_DIR="$resume_app" \
AGORA_RESEARCH_REQUEST_DIR="$resume_requests" \
AGORA_RESEARCH_INBOX_DIR="$resume_inbox" \
AGORA_RESEARCH_RUNTIME_DIR="$resume_runtime" \
  bash "$control_current/scripts/research-worker/run-request.sh"
python3 - "$resume_requests" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
if (root / "running.json").exists() or (root / "pending.json").exists():
    raise SystemExit("resumed request was not finalized")
with (root / "runs" / ("a" * 32 + ".json")).open(encoding="utf-8") as stream:
    result = json.load(stream)
if result.get("status") != "COMPLETED":
    raise SystemExit(f"resumed request did not complete: {result.get('status')}")
if result.get("started_at") != "2026-08-05T00:00:01Z":
    raise SystemExit("resumed request lost its original started_at")
if result.get("resume_count") != 1 or not result.get("last_resumed_at"):
    raise SystemExit("resumed request did not record its recovery")
PY
rm -rf -- "$resume_probe"
ok "hard-stop durable request resumes idempotently in isolated state"

systemctl cat agora-research-heartbeat.service >/dev/null
systemctl cat agora-research-heartbeat.timer >/dev/null
systemctl cat agora-research-dispatch.service >/dev/null
systemctl cat agora-research-dispatch.path >/dev/null
systemctl cat agora-research-mcp.service >/dev/null
systemctl cat agora-research-source.service >/dev/null
systemctl cat agora-research-source.path >/dev/null
systemctl cat agora-research-evidence-ingest.service >/dev/null
systemctl cat agora-research-evidence-ingest.path >/dev/null
systemctl cat "$MICROSTRUCTURE_UNIT" >/dev/null
systemctl cat "$MICROSTRUCTURE_INTAKE_UNIT" >/dev/null
systemctl cat "$MICROSTRUCTURE_INTAKE_PATH" >/dev/null
systemctl cat "$MICROSTRUCTURE_EXPORT_UNIT" >/dev/null
carry_unit_present=false
if systemctl cat "$CARRY_UNIT" >/dev/null 2>&1; then
  carry_unit_present=true
fi
case "$EXPECT_CARRY_SOURCE" in
  auto)
    ;;
  absent)
    [ "$carry_package" = false ] \
      || fail "carry distribution is present when absence was required"
    [ "$carry_unit_present" = false ] \
      || fail "carry source unit is present when absence was required"
    if id -u "$CARRY_USER" >/dev/null 2>&1; then
      fail "carry source identity is present when absence was required"
    fi
    if getent group "$CARRY_GROUP" >/dev/null 2>&1; then
      fail "carry publisher group is present when absence was required"
    fi
    for absent_path in "$CARRY_ROOT" "$CARRY_BINDING" "$CARRY_REQUEST_ROOT"; do
      [ ! -e "$absent_path" ] && [ ! -L "$absent_path" ] \
        || fail "carry path is present when absence was required: $absent_path"
    done
    if systemctl list-unit-files 'agora-research-dra-crypto-carry*.timer' --no-legend | grep -q . \
        || systemctl list-unit-files 'agora-research-dra-crypto-carry*.path' --no-legend | grep -q .; then
      fail "carry timer or path is present when absence was required"
    fi
    ok "explicit carry absence expectation verified"
    ;;
  inactive)
    [ "$carry_package" = true ] \
      || fail "carry distribution is absent when inactive installation was required"
    [ "$carry_unit_present" = true ] \
      || fail "carry source unit is absent when inactive installation was required"
    ;;
esac
if [ "$carry_package" = true ] && [ "$carry_unit_present" = false ]; then
  fail "carry distribution is installed without the carry source unit"
fi
if [ "$carry_unit_present" = true ]; then
  [ -f "$data_current/scripts/research-worker/run-dra-crypto-carry-phase.sh" ] \
    && [ ! -L "$data_current/scripts/research-worker/run-dra-crypto-carry-phase.sh" ] \
    && [ -x "$data_current/scripts/research-worker/run-dra-crypto-carry-phase.sh" ] \
    || fail "fixed carry launcher is missing, symlinked, or non-executable"
fi
for control_unit in \
  agora-research-mcp.service \
  agora-research-dispatch.service \
  agora-research-heartbeat.service; do
  control_unit_text="$(systemctl cat "$control_unit")"
  echo "$control_unit_text" | grep -Fq '/opt/agora-research-worker/control-current' \
    || fail "$control_unit does not use the fixed control-current lane"
  if echo "$control_unit_text" | grep -Fq '/opt/agora-research-worker/current'; then
    fail "$control_unit still references the data-current lane"
  fi
done
export_unit_text="$(systemctl cat "$MICROSTRUCTURE_EXPORT_UNIT")"
echo "$export_unit_text" \
  | grep -Fxq 'Documentation=file:/opt/agora-research-worker/control-current/docs/server-research-worker-v2.md' \
  || fail "microstructure handoff exporter documentation does not use control-current"
echo "$export_unit_text" \
  | grep -Fxq 'WorkingDirectory=/opt/agora-research-worker/control-current' \
  || fail "microstructure handoff exporter working directory does not use control-current"
echo "$export_unit_text" \
  | grep -Fxq 'ExecStart=/opt/agora-research-worker/venv/bin/python -m research_pipeline.microstructure_handoff_export' \
  || fail "microstructure handoff exporter does not execute the fixed zero-argument module"
data_units=(
  agora-research-source.service
  agora-research-evidence-ingest.service
  agora-research-microstructure-source.service
  agora-research-microstructure-intake.service
)
if [ "$carry_unit_present" = true ]; then
  data_units+=("$CARRY_UNIT")
fi
for data_unit in "${data_units[@]}"; do
  data_unit_text="$(systemctl cat "$data_unit")"
  echo "$data_unit_text" | grep -Fq '/opt/agora-research-worker/current' \
    || fail "$data_unit does not use the fixed data-current lane"
  if echo "$data_unit_text" | grep -Fq '/opt/agora-research-worker/control-current'; then
    fail "$data_unit references the control-current lane"
  fi
done
ok "control and data-plane systemd release lanes are byte-separated"

for binding_reader_unit in "$DISPATCH_UNIT" "$HEARTBEAT_UNIT"; do
  [ "$(systemctl show "$binding_reader_unit" --property=User --value)" = "$WORKER_USER" ] \
    || fail "$binding_reader_unit identity is incorrect"
  [ "$(systemctl show "$binding_reader_unit" --property=Group --value)" = "$WORKER_USER" ] \
    || fail "$binding_reader_unit primary group is incorrect"
  [ "$(systemctl show "$binding_reader_unit" --property=SupplementaryGroups --value)" = "$EVIDENCE_GROUP" ] \
    || fail "$binding_reader_unit binding-reader supplementary group is not exact"
  case "$(systemctl show "$binding_reader_unit" --property=IPAddressDeny --value)" in
    any|'::/0 0.0.0.0/0'|'0.0.0.0/0 ::/0') ;;
    *) fail "$binding_reader_unit is not network denied" ;;
  esac
  binding_reader_read_only="$(systemctl show "$binding_reader_unit" --property=ReadOnlyPaths --value)"
  [ "$binding_reader_read_only" = "$MICROSTRUCTURE_BINDING" ] \
    || fail "$binding_reader_unit read-only binding path is not exact"
  binding_reader_inaccessible="$(systemctl show "$binding_reader_unit" --property=InaccessiblePaths --value)"
  python3 - "$binding_reader_unit" "$binding_reader_inaccessible" \
    "-$LEGACY_MICROSTRUCTURE_BINDING" \
    "-$R2_MICROSTRUCTURE_BINDING" \
    -/var/lib/agora-evidence-source \
    -/home/ubuntu/.env.trading.secrets <<'PY'
import sys

if set(sys.argv[2].split()) != set(sys.argv[3:]):
    raise SystemExit(f"{sys.argv[1]} inaccessible paths are not exact")
PY
done
ok "durable dispatch and heartbeat can read only the V3R1 binding through their evidence group"

systemd-analyze verify \
  /etc/systemd/system/agora-research-heartbeat.service \
  /etc/systemd/system/agora-research-heartbeat.timer \
  /etc/systemd/system/agora-research-dispatch.service \
  /etc/systemd/system/agora-research-dispatch.path \
  /etc/systemd/system/agora-research-mcp.service \
  /etc/systemd/system/agora-research-source.service \
  /etc/systemd/system/agora-research-source.path \
  /etc/systemd/system/agora-research-evidence-ingest.service \
  /etc/systemd/system/agora-research-evidence-ingest.path \
  "/etc/systemd/system/$MICROSTRUCTURE_UNIT" \
  "/etc/systemd/system/$MICROSTRUCTURE_INTAKE_UNIT" \
  "/etc/systemd/system/$MICROSTRUCTURE_INTAKE_PATH" \
  "/etc/systemd/system/$MICROSTRUCTURE_EXPORT_UNIT"
if [ "$carry_unit_present" = true ]; then
  systemd-analyze verify "/etc/systemd/system/$CARRY_UNIT"
fi
ok "systemd units verified"

[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=User --value)" = "$WORKER_USER" ] \
  || fail "microstructure handoff exporter identity is incorrect"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=Group --value)" = "$WORKER_USER" ] \
  || fail "microstructure handoff exporter primary group is incorrect"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=SupplementaryGroups --value)" = "$EVIDENCE_GROUP" ] \
  || fail "microstructure handoff exporter supplementary group is not exact"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=Restart --value)" = "no" ] \
  || fail "microstructure handoff exporter restart policy is not fail-closed"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=Type --value)" = "oneshot" ] \
  || fail "microstructure handoff exporter is not oneshot"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=NoNewPrivileges --value)" = "yes" ] \
  || fail "microstructure handoff exporter permits privilege gain"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=PrivateDevices --value)" = "yes" ] \
  || fail "microstructure handoff exporter can access host devices"
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=ProtectHome --value)" = "yes" ] \
  || fail "microstructure handoff exporter can access home directories"
case "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=IPAddressDeny --value)" in
  any|'::/0 0.0.0.0/0'|'0.0.0.0/0 ::/0') ;;
  *) fail "microstructure handoff exporter is not network denied" ;;
esac
[ "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=RestrictAddressFamilies --value)" = "AF_UNIX" ] \
  || fail "microstructure handoff exporter address families exceed AF_UNIX"
if systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=EnvironmentFiles --value | grep -q .; then
  fail "microstructure handoff exporter must not load an environment file"
fi
[ -z "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=Environment --value)" ] \
  || fail "microstructure handoff exporter accepts environment selection"
for capability_property in CapabilityBoundingSet AmbientCapabilities; do
  [ -z "$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" \
    --property="$capability_property" --value)" ] \
    || fail "microstructure handoff exporter retains $capability_property"
done
export_read_only="$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=ReadOnlyPaths --value)"
python3 - "$export_read_only" \
  "$MICROSTRUCTURE_BINDING" \
  "$MICROSTRUCTURE_STATE" \
  "$MICROSTRUCTURE_DROP" \
  "$MICROSTRUCTURE_LOCAL_TASK" \
  "$WORKER_ROOT/current" \
  "$WORKER_ROOT/control-current" <<'PY'
import sys

actual = set(sys.argv[1].split())
expected = set(sys.argv[2:])
if actual != expected:
    raise SystemExit(f"exporter read-only paths are not exact: {sorted(actual)}")
PY
export_writes="$(systemctl show "$MICROSTRUCTURE_EXPORT_UNIT" --property=ReadWritePaths --value)"
python3 - "$export_writes" "$MICROSTRUCTURE_HANDOFF_STAGING" "$MICROSTRUCTURE_HANDOFF_FINAL" <<'PY'
import sys

actual = set(sys.argv[1].split())
expected = set(sys.argv[2:])
if actual != expected:
    raise SystemExit(f"exporter writable paths are not exact: {sorted(actual)}")
PY
[ "$(sudo stat -c '%U:%G:%a' "$MICROSTRUCTURE_LOCAL_TASK")" = "root:root:444" ] \
  || fail "V3R1 local diagnostic task metadata is not frozen"
sudo cmp -s \
  "$MICROSTRUCTURE_LOCAL_TASK" \
  "$data_current/research_pipeline/examples/local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json" \
  || fail "V3R1 local diagnostic task differs from the installed release"
for handoff_root in "$MICROSTRUCTURE_HANDOFF_STAGING" "$MICROSTRUCTURE_HANDOFF_FINAL"; do
  [ "$(sudo stat -c '%U:%G:%a' "$handoff_root")" = "$WORKER_USER:$WORKER_USER:700" ] \
    || fail "V3R1 handoff root metadata is incorrect: $handoff_root"
done
[ "$(sudo stat -c '%d' "$MICROSTRUCTURE_HANDOFF_STAGING")" = "$(sudo stat -c '%d' "$MICROSTRUCTURE_HANDOFF_FINAL")" ] \
  || fail "V3R1 handoff staging and final roots are not on one filesystem"
export_active="$(systemctl is-active "$MICROSTRUCTURE_EXPORT_UNIT" 2>/dev/null || true)"
[ "$export_active" = inactive ] \
  || fail "microstructure handoff exporter is not cleanly inactive outside an explicit handoff request"
case "$(systemctl is-enabled "$MICROSTRUCTURE_EXPORT_UNIT" 2>/dev/null || true)" in
  enabled|enabled-runtime|linked|linked-runtime|alias)
    fail "microstructure handoff exporter is enabled"
    ;;
esac
ok "microstructure handoff exporter identity, confinement, and inactive state verified"

if [ "$carry_unit_present" = true ]; then
  id -u "$CARRY_USER" >/dev/null 2>&1 || fail "carry source identity is missing"
  getent group "$CARRY_GROUP" >/dev/null || fail "carry publisher group is missing"
  [ "$(id -gn "$CARRY_USER")" = "$CARRY_GROUP" ] \
    || fail "carry source primary group is incorrect"
  mapfile -t carry_identity_groups < <(id -nG "$CARRY_USER" | tr ' ' '\n' | sort -u)
  [ "${#carry_identity_groups[@]}" = 1 ] \
    && [ "${carry_identity_groups[0]}" = "$CARRY_GROUP" ] \
    || fail "carry source identity has unexpected supplementary groups"

  carry_unit_text="$(systemctl cat "$CARRY_UNIT")"
  echo "$carry_unit_text" \
    | grep -Fxq 'WorkingDirectory=/opt/agora-research-worker/current' \
    || fail "carry source working directory is not fixed"
  echo "$carry_unit_text" \
    | grep -Fxq 'ExecStart=/opt/agora-research-worker/current/scripts/research-worker/run-dra-crypto-carry-phase.sh' \
    || fail "carry source does not execute the fixed zero-argument launcher"
  echo "$carry_unit_text" \
    | grep -Fxq 'TimeoutStartSec=30m' \
    || fail "carry source unit text does not freeze the 30-minute start timeout"
  if echo "$carry_unit_text" | grep -Eq '^RuntimeMaxSec='; then
    fail "carry source unit retains an ineffective oneshot runtime maximum"
  fi
  if echo "$carry_unit_text" | grep -Fxq '[Install]'; then
    fail "carry source unit has an Install section"
  fi
  [ "$(systemctl show "$CARRY_UNIT" --property=User --value)" = "$CARRY_USER" ] \
    || fail "carry source identity is incorrect"
  [ "$(systemctl show "$CARRY_UNIT" --property=Group --value)" = "$CARRY_GROUP" ] \
    || fail "carry source group is incorrect"
  [ -z "$(systemctl show "$CARRY_UNIT" --property=SupplementaryGroups --value)" ] \
    || fail "carry source inherits a supplementary group"
  [ "$(systemctl show "$CARRY_UNIT" --property=Type --value)" = oneshot ] \
    || fail "carry source is not oneshot"
  [ "$(systemctl show "$CARRY_UNIT" --property=Restart --value)" = no ] \
    || fail "carry source restart policy is not fail-closed"
  [ "$(systemctl show "$CARRY_UNIT" --property=TimeoutStartUSec --value)" = 30min ] \
    || fail "carry source effective start timeout is not exactly 30 minutes"
  [ "$(systemctl show "$CARRY_UNIT" --property=RuntimeMaxUSec --value)" = infinity ] \
    || fail "carry source retains an effective runtime maximum"
  [ "$(systemctl show "$CARRY_UNIT" --property=NoNewPrivileges --value)" = yes ] \
    || fail "carry source permits privilege gain"
  [ "$(systemctl show "$CARRY_UNIT" --property=PrivateDevices --value)" = yes ] \
    || fail "carry source can access host devices"
  [ "$(systemctl show "$CARRY_UNIT" --property=ProtectHome --value)" = yes ] \
    || fail "carry source can access home directories"
  if systemctl show "$CARRY_UNIT" --property=EnvironmentFiles --value | grep -q .; then
    fail "carry source must not load an environment file"
  fi
  [ -z "$(systemctl show "$CARRY_UNIT" --property=Environment --value)" ] \
    || fail "carry source accepts environment selection"
  for capability_property in CapabilityBoundingSet AmbientCapabilities; do
    [ -z "$(systemctl show "$CARRY_UNIT" --property="$capability_property" --value)" ] \
      || fail "carry source retains $capability_property"
  done
  carry_families="$(systemctl show "$CARRY_UNIT" --property=RestrictAddressFamilies --value)"
  python3 - "$carry_families" <<'PY'
import sys

if set(sys.argv[1].split()) != {"AF_UNIX", "AF_INET", "AF_INET6"}:
    raise SystemExit("carry source address families are not exact")
PY
  carry_read_only="$(systemctl show "$CARRY_UNIT" --property=ReadOnlyPaths --value)"
  python3 - "$carry_read_only" "-$CARRY_BINDING" "-$CARRY_REQUEST_ROOT" <<'PY'
import sys

if set(sys.argv[1].split()) != set(sys.argv[2:]):
    raise SystemExit("carry source read-only paths are not exact")
PY
  carry_writes="$(systemctl show "$CARRY_UNIT" --property=ReadWritePaths --value)"
  python3 - "$carry_writes" \
    "$CARRY_PRIVATE" "$CARRY_INVENTORY_STAGING" "$CARRY_INVENTORY_DROP" \
    "$CARRY_DAY_STAGING" "$CARRY_DAY_DROP" <<'PY'
import sys

if set(sys.argv[1].split()) != set(sys.argv[2:]):
    raise SystemExit("carry source writable paths are not exact")
PY
  carry_inaccessible="$(systemctl show "$CARRY_UNIT" --property=InaccessiblePaths --value)"
  python3 - "$carry_inaccessible" \
    -/var/lib/agora-research/state \
    -/var/lib/agora-research/inbox \
    -/var/lib/agora-research/requests \
    -/var/lib/agora-research/source-requests \
    -/var/lib/agora-research/source-drop \
    -/var/lib/agora-evidence-source \
    -/home/ubuntu/.env.trading.secrets <<'PY'
import sys

if set(sys.argv[1].split()) != set(sys.argv[2:]):
    raise SystemExit("carry source inaccessible paths are not exact")
PY
  case "$(systemctl is-enabled "$CARRY_UNIT" 2>/dev/null || true)" in
    enabled|enabled-runtime|linked|linked-runtime|alias)
      fail "carry source unit is enabled"
      ;;
  esac
  [ "$(systemctl is-active "$CARRY_UNIT" 2>/dev/null || true)" = inactive ] \
    || fail "carry source unit is not inactive"
  if systemctl is-failed --quiet "$CARRY_UNIT" 2>/dev/null; then
    fail "carry source unit retains a failed state"
  fi
  [ "$(systemctl show "$CARRY_UNIT" --property=MainPID --value)" = 0 ] \
    || fail "carry source unit has a MainPID"
  if systemctl list-unit-files 'agora-research-dra-crypto-carry*.timer' --no-legend | grep -q . \
      || systemctl list-unit-files 'agora-research-dra-crypto-carry*.path' --no-legend | grep -q .; then
    fail "carry source has a timer or path companion"
  fi
  [ ! -e "$CARRY_BINDING" ] && [ ! -L "$CARRY_BINDING" ] \
    || fail "carry binding exists before a separately authorized registration"
  [ ! -e "$CARRY_REQUEST_ROOT" ] && [ ! -L "$CARRY_REQUEST_ROOT" ] \
    || fail "carry request root exists before a separately authorized registration"
  [ "$(sudo stat -c '%U:%G:%a' "$CARRY_ROOT")" = "root:$CARRY_GROUP:710" ] \
    || fail "carry root metadata is incorrect"
  for source_root in "$CARRY_PRIVATE" "$CARRY_INVENTORY_STAGING" "$CARRY_DAY_STAGING"; do
    [ "$(sudo stat -c '%U:%G:%a' "$source_root")" = "$CARRY_USER:$CARRY_GROUP:700" ] \
      || fail "carry private/staging metadata is incorrect: $source_root"
  done
  for drop_root in "$CARRY_INVENTORY_DROP" "$CARRY_DAY_DROP"; do
    [ "$(sudo stat -c '%U:%G:%a' "$drop_root")" = "root:$CARRY_GROUP:1770" ] \
      || fail "carry sticky drop metadata is incorrect: $drop_root"
  done
  [ "$(sudo stat -c '%d' "$CARRY_INVENTORY_STAGING")" = "$(sudo stat -c '%d' "$CARRY_INVENTORY_DROP")" ] \
    || fail "carry inventory staging and drop are not on one filesystem"
  [ "$(sudo stat -c '%d' "$CARRY_DAY_STAGING")" = "$(sudo stat -c '%d' "$CARRY_DAY_DROP")" ] \
    || fail "carry day staging and drop are not on one filesystem"
  if [ "$carry_package" = true ]; then
    ok "carry distribution, dedicated identity, fixed paths, confinement, and inactive unit verified"
  else
    ok "pre-existing carry unit remains safely inactive; carry bytes absent and no readiness claimed"
  fi
fi
if [ "$EXPECT_CARRY_SOURCE" = inactive ]; then
  ok "explicit carry inactive expectation verified through the full isolation gate"
fi

[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=User --value)" = "$SOURCE_USER" ] \
  || fail "microstructure source identity is incorrect"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=Group --value)" = "$EVIDENCE_GROUP" ] \
  || fail "microstructure source group is incorrect"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=Restart --value)" = "on-failure" ] \
  || fail "microstructure source restart policy does not preserve V3R1 recovery"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=RestartPreventExitStatus --value)" = "2" ] \
  || fail "microstructure source does not prevent restart after a fail-closed exit"
source_unit_text="$(systemctl cat "$MICROSTRUCTURE_UNIT")"
echo "$source_unit_text" \
  | grep -Fxq 'ExecStart=/opt/agora-research-worker/current/scripts/research-worker/run-microstructure-continuous-source.sh' \
  || fail "microstructure source does not execute the fixed V3R1 wrapper"
echo "$source_unit_text" | grep -Fxq 'RuntimeMaxSec=45d' \
  || fail "microstructure source does not retain the bounded 42-day recovery lifetime"
[ -f "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN" ] \
  && [ ! -L "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN" ] \
  && [ "$(stat -c '%U:%G:%a' "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN")" = "root:root:644" ] \
  || fail "microstructure host-context drop-in metadata is invalid"
cmp -s \
  "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN" \
  "$control_current/scripts/research-worker/agora-research-microstructure-host-context.conf" \
  || fail "microstructure host-context drop-in differs from the control release"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=DropInPaths --value)" = "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN" ] \
  || fail "microstructure source drop-in inventory is not exact"
echo "$source_unit_text" | grep -Fxq 'ProcSubset=pid' \
  || fail "microstructure source base unit no longer defaults to PID-only proc"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=ProcSubset --value)" = "all" ] \
  || fail "microstructure source cannot read the frozen host boot context"
[ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=ProtectProc --value)" = "invisible" ] \
  || fail "microstructure source no longer hides unrelated processes"
if systemctl show "$MICROSTRUCTURE_UNIT" --property=EnvironmentFiles --value | grep -q .; then
  fail "microstructure source must not load an environment file"
fi
source_read_only="$(systemctl show "$MICROSTRUCTURE_UNIT" --property=ReadOnlyPaths --value)"
echo "$source_read_only" | grep -Fq "$MICROSTRUCTURE_BINDING" \
  || fail "microstructure source does not read only the fixed V3R1 binding"
if echo "$source_read_only" | grep -Fq "$R2_MICROSTRUCTURE_BINDING" \
    || echo "$source_read_only" | grep -Fq "$LEGACY_MICROSTRUCTURE_BINDING"; then
  fail "microstructure source can select a historical binding"
fi
if systemctl list-unit-files 'agora-research-microstructure*.timer' --no-legend | grep -q .; then
  fail "a microstructure timer exists"
fi
mapfile -t microstructure_paths < <(
  systemctl list-unit-files 'agora-research-microstructure*.path' --no-legend \
    | awk '{print $1}'
)
[ "${#microstructure_paths[@]}" = 1 ] \
  && [ "${microstructure_paths[0]}" = "$MICROSTRUCTURE_INTAKE_PATH" ] \
  || fail "microstructure lifecycle has more than the one existing intake path"
microstructure_writes="$(systemctl show "$MICROSTRUCTURE_UNIT" --property=ReadWritePaths --value)"
if echo "$microstructure_writes" | grep -Fq '/var/lib/agora-research/source-drop'; then
  fail "microstructure source can write the candle drop"
fi
[ "$microstructure_writes" = "/var/lib/agora-evidence-source" ] \
  || fail "microstructure source writable paths are not the exact atomic-publication parent"

[ "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=User --value)" = "$WORKER_USER" ] \
  || fail "microstructure intake identity is incorrect"
[ "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=Group --value)" = "$WORKER_USER" ] \
  || fail "microstructure intake primary group is incorrect"
[ -z "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=SupplementaryGroups --value)" ] \
  || fail "microstructure intake inherits a supplementary group"
[ "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=Restart --value)" = "no" ] \
  || fail "microstructure intake restart policy is not fail-closed"
case "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=IPAddressDeny --value)" in
  any|'::/0 0.0.0.0/0'|'0.0.0.0/0 ::/0') ;;
  *) fail "microstructure intake is not network denied" ;;
esac
[ "$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=RestrictAddressFamilies --value)" = "AF_UNIX" ] \
  || fail "microstructure intake address families exceed AF_UNIX"
if systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=EnvironmentFiles --value | grep -q .; then
  fail "microstructure intake must not load an environment file"
fi
for capability_property in CapabilityBoundingSet AmbientCapabilities; do
  capability_value="$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" \
    --property="$capability_property" --value)"
  python3 - "$capability_property" "$capability_value" <<'PY'
import sys

name, raw = sys.argv[1:]
actual = {item.upper() for item in raw.split()}
expected = {"CAP_DAC_READ_SEARCH", "CAP_CHOWN", "CAP_FOWNER"}
if actual != expected:
    raise SystemExit(f"{name} is not the exact bounded intake set: {sorted(actual)}")
PY
done
intake_read_only="$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=ReadOnlyPaths --value)"
echo "$intake_read_only" | grep -Fq "$MICROSTRUCTURE_BINDING" \
  || fail "microstructure binding is not read-only to intake"
if echo "$intake_read_only" | grep -Fq "$R2_MICROSTRUCTURE_BINDING" \
    || echo "$intake_read_only" | grep -Fq "$LEGACY_MICROSTRUCTURE_BINDING"; then
  fail "microstructure intake can select a historical binding"
fi
systemctl cat "$MICROSTRUCTURE_INTAKE_UNIT" \
  | grep -Fxq 'ExecStart=/opt/agora-research-worker/venv/bin/python -m research_pipeline.microstructure_discovery_recovery_intake_cli ingest' \
  || fail "microstructure intake does not execute the fixed V3R1 ingest command"
intake_writes="$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=ReadWritePaths --value)"
python3 - "$intake_writes" "$MICROSTRUCTURE_DROP" "$MICROSTRUCTURE_STATE" <<'PY'
import sys

actual = set(sys.argv[1].split())
expected = set(sys.argv[2:])
if actual != expected:
    raise SystemExit(f"intake writable paths are not exact: {sorted(actual)}")
PY
intake_inaccessible="$(systemctl show "$MICROSTRUCTURE_INTAKE_UNIT" --property=InaccessiblePaths --value)"
echo "$intake_inaccessible" | grep -Fq "$LEGACY_MICROSTRUCTURE_STATE" \
  || fail "microstructure intake can access the legacy V2 state namespace"
echo "$intake_inaccessible" | grep -Fq "$R2_MICROSTRUCTURE_STATE" \
  || fail "microstructure intake can access the historical R2 state namespace"
systemctl cat "$MICROSTRUCTURE_INTAKE_PATH" \
  | grep -Fxq "PathChanged=$MICROSTRUCTURE_DROP" \
  || fail "microstructure path does not watch the fixed drop root"
[ "$(systemctl cat "$MICROSTRUCTURE_INTAKE_PATH" | grep -Ec '^Path(Changed|Exists|Modified|DirectoryNotEmpty)=')" = 1 ] \
  || fail "microstructure path has more than one trigger"
systemctl is-enabled --quiet "$MICROSTRUCTURE_INTAKE_PATH" \
  || fail "microstructure intake path is not enabled"
systemctl is-active --quiet "$MICROSTRUCTURE_INTAKE_PATH" \
  || fail "microstructure intake path is not active"

[ "$(sudo stat -c '%U:%G:%a' "$MICROSTRUCTURE_STAGING")" = "$SOURCE_USER:$EVIDENCE_GROUP:700" ] \
  || fail "microstructure staging metadata is incorrect"
[ "$(sudo stat -c '%U:%G:%a' "$MICROSTRUCTURE_DROP")" = "root:$EVIDENCE_GROUP:1770" ] \
  || fail "microstructure sticky drop-parent metadata is incorrect"
[ "$(sudo stat -c '%U:%G:%a' "$MICROSTRUCTURE_STATE")" = "$WORKER_USER:$WORKER_USER:700" ] \
  || fail "microstructure state-root metadata is incorrect"
[ "$(sudo stat -c '%d' "$MICROSTRUCTURE_STAGING")" = "$(sudo stat -c '%d' "$MICROSTRUCTURE_DROP")" ] \
  || fail "microstructure staging and drop are not on the same filesystem"
microstructure_free_bytes="$(df -B1 --output=avail "$MICROSTRUCTURE_DROP" | tail -n 1 | tr -d ' ')"
[[ "$microstructure_free_bytes" =~ ^[0-9]+$ ]] \
  && [ "$microstructure_free_bytes" -ge 2147483648 ] \
  || fail "microstructure drop has less than 2 GiB free"

sudo python3 - "$MICROSTRUCTURE_STAGING" "$SOURCE_USER" "$EVIDENCE_GROUP" <<'PY'
import grp
from pathlib import Path
import pwd
import stat
import sys

root = Path(sys.argv[1])
source_uid = pwd.getpwnam(sys.argv[2]).pw_uid
evidence_gid = grp.getgrnam(sys.argv[3]).gr_gid
allowed_top = {".source-state", ".source-publication-prepared"}
for path in root.rglob("*"):
    details = path.lstat()
    relative = path.relative_to(root)
    if relative.parts[0] not in allowed_top:
        raise SystemExit(f"unexpected V3R1 private staging entry: {relative}")
    if stat.S_ISLNK(details.st_mode) or not (
        stat.S_ISREG(details.st_mode) or stat.S_ISDIR(details.st_mode)
    ):
        raise SystemExit(f"unsafe V3R1 private staging entry: {relative}")
    if details.st_uid != source_uid or details.st_gid != evidence_gid:
        raise SystemExit(f"V3R1 private staging ownership changed: {relative}")
    if stat.S_IMODE(details.st_mode) & 0o007:
        raise SystemExit(f"V3R1 private staging is world accessible: {relative}")
PY

sudo python3 - "$MICROSTRUCTURE_DROP" "$WORKER_USER" <<'PY'
from datetime import date
import grp
import os
from pathlib import Path
import pwd
import re
import stat
import sys

root = Path(sys.argv[1])
research_uid = pwd.getpwnam(sys.argv[2]).pw_uid
research_gid = grp.getgrnam(sys.argv[2]).gr_gid
days = {}
reservations = {}
for entry in os.scandir(root):
    if entry.is_symlink():
        raise SystemExit("microstructure drop contains a symlink")
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", entry.name) and entry.is_dir(follow_symlinks=False):
        day = date.fromisoformat(entry.name)
        if day.isoformat() != entry.name:
            raise SystemExit("noncanonical microstructure day name")
        days[day] = Path(entry.path)
        continue
    match = re.fullmatch(r"\.(\d{4}-\d{2}-\d{2})\.publish-reserved", entry.name)
    if match and entry.is_file(follow_symlinks=False):
        day = date.fromisoformat(match.group(1))
        details = entry.stat(follow_symlinks=False)
        if details.st_size != 0:
            raise SystemExit("microstructure reservation is not empty")
        reservations[day] = Path(entry.path)
        continue
    raise SystemExit(f"unexpected microstructure drop entry: {entry.name}")
if len(days) > 42 or len(reservations) > 42 or set(days) != set(reservations):
    raise SystemExit("microstructure drop retention/reservation bound failed")
for day, directory in days.items():
    details = directory.lstat()
    if details.st_uid != 0 or details.st_gid != research_gid or stat.S_IMODE(details.st_mode) != 0o550:
        raise SystemExit(f"published day metadata is not frozen: {day}")
    children = list(os.scandir(directory))
    complete = {
        f"okx-btc-usdt-microstructure-{day}.json",
        f"okx-btc-usdt-microstructure-{day}.complete.envelope.json",
    }
    rejected = {
        f"okx-btc-usdt-microstructure-{day}.rejection.envelope.json",
    }
    if {child.name for child in children} not in (complete, rejected):
        raise SystemExit(f"published day shape changed: {day}")
    for child in children:
        child_details = child.stat(follow_symlinks=False)
        if child.is_symlink() or not child.is_file(follow_symlinks=False):
            raise SystemExit(f"published evidence file is ambiguous: {child.name}")
        if child_details.st_uid != 0 or child_details.st_gid != research_gid or stat.S_IMODE(child_details.st_mode) != 0o440:
            raise SystemExit(f"published evidence metadata is not frozen: {child.name}")
    reservation_details = reservations[day].lstat()
    if reservation_details.st_uid != 0 or reservation_details.st_gid != research_gid or stat.S_IMODE(reservation_details.st_mode) != 0o440:
        raise SystemExit(f"reservation metadata is not frozen: {day}")
PY

microstructure_probe=""
microstructure_probe_renamed=""
cleanup_microstructure_probe() {
  for candidate in "$microstructure_probe" "$microstructure_probe_renamed"; do
    case "$candidate" in
      "$MICROSTRUCTURE_DROP"/.verify-intake-boundary.*)
        sudo rm -rf -- "$candidate"
        ;;
      '') ;;
      *) fail "microstructure verifier probe escaped fixed drop root" ;;
    esac
  done
}
trap cleanup_microstructure_probe EXIT
microstructure_probe="$(sudo -u "$SOURCE_USER" \
  mktemp -d "$MICROSTRUCTURE_DROP/.verify-intake-boundary.XXXXXX")"
microstructure_probe_renamed="${microstructure_probe}.renamed"
sudo -u "$SOURCE_USER" sh -c 'umask 027; printf "%s\n" probe > "$1/probe"' \
  -- "$microstructure_probe"
sudo chown root:"$WORKER_USER" "$microstructure_probe" "$microstructure_probe/probe"
sudo chmod 0550 "$microstructure_probe"
sudo chmod 0440 "$microstructure_probe/probe"
if sudo -u "$SOURCE_USER" test -r "$microstructure_probe/probe"; then
  fail "source can read a frozen microstructure probe"
fi
if sudo -u "$SOURCE_USER" sh -c 'printf x >> "$1"' -- "$microstructure_probe/probe" 2>/dev/null; then
  fail "source can modify a frozen microstructure probe"
fi
if sudo -u "$SOURCE_USER" mv -- "$microstructure_probe" "$microstructure_probe_renamed" 2>/dev/null; then
  fail "source can rename a frozen microstructure probe"
fi
if sudo -u "$SOURCE_USER" rm -rf -- "$microstructure_probe" 2>/dev/null; then
  fail "source can delete a frozen microstructure probe"
fi
sudo test -d "$microstructure_probe" || fail "frozen verifier probe disappeared"
cleanup_microstructure_probe
microstructure_probe=""
microstructure_probe_renamed=""
trap - EXIT
ok "microstructure sticky publication and immutable freeze boundary verified"

if [ -e "$MICROSTRUCTURE_BINDING" ] || [ -L "$MICROSTRUCTURE_BINDING" ]; then
  (
    cd "$data_current"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" - <<'PY'
from datetime import datetime, timezone
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    _load_binding,
    _load_state,
    _scan_drop,
    _state_path,
    fixed_runtime_paths,
)

paths = fixed_runtime_paths()
binding = _load_binding(
    paths, require_future=False, today=datetime.now(timezone.utc).date()
)
_load_state(_state_path(paths.state_root, binding["generation_id"]), binding)
_scan_drop(paths.drop_root, binding)
PY
  )
  microstructure_state_file="$(sudo find "$MICROSTRUCTURE_STATE" -mindepth 1 -maxdepth 1 -type f -name '*.json' -print)"
  [ -n "$microstructure_state_file" ] \
    && [ "$(printf '%s\n' "$microstructure_state_file" | wc -l)" = 1 ] \
    || fail "microstructure state file is not singular"
  [ "$(sudo stat -c '%U:%G:%a' "$microstructure_state_file")" = "$WORKER_USER:$WORKER_USER:600" ] \
    || fail "microstructure state file metadata is incorrect"
else
  [ -z "$(sudo find "$MICROSTRUCTURE_STATE" -mindepth 1 -maxdepth 1 -print -quit)" ] \
    || fail "microstructure state exists without a binding"
fi
ok "microstructure intake state namespace and static gates verified"

case "$EXPECT_MICROSTRUCTURE_SOURCE" in
  disabled)
    if systemctl is-active --quiet "$MICROSTRUCTURE_UNIT"; then
      fail "microstructure source is active before intake readiness"
    fi
    case "$(systemctl is-enabled "$MICROSTRUCTURE_UNIT" 2>/dev/null || true)" in
      enabled|enabled-runtime|linked|linked-runtime|alias)
        fail "microstructure source is enabled before intake readiness"
        ;;
    esac
    if systemctl is-failed --quiet "$MICROSTRUCTURE_UNIT" 2>/dev/null; then
      fail "microstructure source retains a failed status; explicit read-only review and reset-failed are required"
    fi
    ok "microstructure source is disabled and inactive"
    ;;
  active)
    [ "$MICROSTRUCTURE_INTAKE_PREFLIGHT" = 1 ] \
      || fail "active source expectation requires explicit intake preflight"
    systemctl is-active --quiet agora-research-microstructure-intake.path \
      || fail "microstructure intake path is not active"
    systemctl is-active --quiet "$MICROSTRUCTURE_UNIT" \
      || fail "microstructure source is not active"
    ok "microstructure source active only with explicit intake preflight"
    ;;
  *) fail "unsupported EXPECT_MICROSTRUCTURE_SOURCE: $EXPECT_MICROSTRUCTURE_SOURCE" ;;
esac

[ "$(systemctl show agora-research-dispatch.service --property=Restart --value)" = "on-abnormal" ] \
  || fail "dispatch service does not restart after an abnormal stop"
systemctl show agora-research-dispatch.path --property=Paths --value \
  | grep -Fq '/var/lib/agora-research/requests/running.json' \
  || fail "dispatch path does not watch an interrupted running request"
ok "dispatch hard-stop and reboot recovery contract verified"

systemctl is-active --quiet agora-research-mcp.service || fail "Research MCP is not active"
systemctl is-active --quiet agora-research-dispatch.path || fail "dispatch path is not active"
systemctl is-active --quiet agora-research-source.path || fail "public source path is not active"
systemctl is-active --quiet agora-research-evidence-ingest.path || fail "evidence ingest path is not active"
ok "Research MCP and all event-driven dispatch paths are active"

[ "$(systemctl show agora-research-source.service --property=User --value)" = "$SOURCE_USER" ] \
  || fail "public source service identity is incorrect"
case "$(systemctl show agora-research-evidence-ingest.service --property=IPAddressDeny --value)" in
  any|'::/0 0.0.0.0/0'|'0.0.0.0/0 ::/0') ;;
  *) fail "evidence ingest is not network denied" ;;
esac
case "$(systemctl show agora-research-mcp.service --property=IPAddressDeny --value)" in
  any|'::/0 0.0.0.0/0'|'0.0.0.0/0 ::/0') ;;
  *) fail "Research MCP is not network denied except explicit allow list" ;;
esac
if systemctl show agora-research-source.service --property=EnvironmentFiles --value | grep -q .; then
  fail "public source service must not load an environment or credential file"
fi
if systemctl list-unit-files 'agora-research-source*.timer' --no-legend | grep -q .; then
  fail "a public source timer exists"
fi
ok "source has no credentials or timer; canonical ingest remains network denied"

[ "$(sudo stat -c '%G:%a' "$DATA_ROOT/source-requests")" = "$EVIDENCE_GROUP:2770" ] \
  || fail "source request directory ownership or mode is incorrect"
[ "$(sudo stat -c '%G:%a' "$DATA_ROOT/source-drop")" = "$EVIDENCE_GROUP:2770" ] \
  || fail "source drop directory ownership or mode is incorrect"
ok "one-way source queue directories verified"

case "$EXPECT_TIMER" in
  disabled)
    if systemctl is-enabled --quiet agora-research-heartbeat.timer; then
      fail "timer is enabled before cutover"
    fi
    ;;
  active)
    systemctl is-enabled --quiet agora-research-heartbeat.timer \
      || fail "timer is not enabled"
    systemctl is-active --quiet agora-research-heartbeat.timer \
      || fail "timer is not active"
    next="$(systemctl show agora-research-heartbeat.timer --property=NextElapseUSecRealtime --value)"
    [ -n "$next" ] && [ "$next" != "n/a" ] || fail "timer has no next run"
    ok "timer next run: $next"
    ;;
  *) fail "unsupported EXPECT_TIMER: $EXPECT_TIMER" ;;
esac

if [ "$RUN_SOURCE_PROBE" = "1" ]; then
  (
    cd "$data_current"
    sudo -u "$SOURCE_USER" env \
      PYTHONDONTWRITEBYTECODE=1 \
      "$WORKER_ROOT/venv/bin/python" -m research_source probe
  )
  ok "fixed public OKX source readiness probe passed"
fi

if [ "$RUN_HEARTBEAT" = "1" ]; then
  sudo systemctl start agora-research-heartbeat.service
  sudo test -s "$DATA_ROOT/inbox/latest.json" || fail "latest heartbeat envelope missing"
  sudo python3 - "$DATA_ROOT/inbox/latest.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
if value.get("status") != "HEARTBEAT_OK":
    raise SystemExit(f"unexpected heartbeat status: {value.get('status')}")
print(
    "[research-worker-verify] OK: heartbeat "
    f"status={value.get('status')} research_status={value.get('research_status')} "
    f"notify={value.get('should_notify_coach')} next_due={value.get('next_due')}"
)
PY
fi

[ "$(systemctl show agora-research-heartbeat.service --property=MainPID --value)" = "0" ] \
  || fail "oneshot worker still has a running process"
ok "worker is oneshot and has no resident process"
