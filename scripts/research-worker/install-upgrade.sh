#!/usr/bin/env bash
set -euo pipefail

STAGING_DIR="${STAGING_DIR:?STAGING_DIR is required}"
RELEASE_ID="${RELEASE_ID:?RELEASE_ID is required}"
SOURCE_GIT_COMMIT="${SOURCE_GIT_COMMIT:?SOURCE_GIT_COMMIT is required}"
SOURCE_GIT_BRANCH="${SOURCE_GIT_BRANCH:?SOURCE_GIT_BRANCH is required}"
SOURCE_GIT_DIRTY="${SOURCE_GIT_DIRTY:?SOURCE_GIT_DIRTY is required}"
WORKER_ROOT="${WORKER_ROOT:-/opt/agora-research-worker}"
DATA_ROOT="${DATA_ROOT:-/var/lib/agora-research}"
WORKER_USER="${WORKER_USER:-agora-research}"
WORKER_GROUP="${WORKER_GROUP:-agora-research}"
SOURCE_USER="${SOURCE_USER:-agora-evidence-source}"
EVIDENCE_GROUP="${EVIDENCE_GROUP:-agora-evidence}"
MICROSTRUCTURE_FORWARD_START_DAY="${MICROSTRUCTURE_FORWARD_START_DAY:-}"
MICROSTRUCTURE_DIAGNOSTIC_ID="${MICROSTRUCTURE_DIAGNOSTIC_ID:-}"
PRESERVE_BOUND_DATA_PLANE="${PRESERVE_BOUND_DATA_PLANE:-0}"
MICROSTRUCTURE_UNIT=agora-research-microstructure-source.service
MICROSTRUCTURE_INTAKE_UNIT=agora-research-microstructure-intake.service
MICROSTRUCTURE_INTAKE_PATH=agora-research-microstructure-intake.path
MICROSTRUCTURE_HOST_CONTEXT_DROPIN=/etc/systemd/system/agora-research-microstructure-source.service.d/10-host-context.conf
MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v3r1.json
MICROSTRUCTURE_DROP=/var/lib/agora-evidence-source/microstructure-v3r1-drop
MICROSTRUCTURE_STAGING=/var/lib/agora-evidence-source/microstructure-v3r1-private-staging
MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure-v3r1"
MICROSTRUCTURE_LOCAL_TASK=/etc/agora-research/local-tasks/microstructure-v3r1-evidence-diagnostic.v1.json
MICROSTRUCTURE_HANDOFF_STAGING="$DATA_ROOT/microstructure-v3r1-handoff-staging"
MICROSTRUCTURE_HANDOFF_FINAL="$DATA_ROOT/microstructure-v3r1-handoff-export"
R2_MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v3.json
R2_MICROSTRUCTURE_DROP=/var/lib/agora-evidence-source/microstructure-drop
R2_MICROSTRUCTURE_STAGING=/var/lib/agora-evidence-source/microstructure-private-staging
R2_MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure-v3"
R2_MICROSTRUCTURE_ARCHIVE="$DATA_ROOT/state/microstructure-archive/okx-btcusdt-microstructure-forward-v3-20260811-r2"
LEGACY_MICROSTRUCTURE_BINDING=/etc/agora-research/okx-microstructure-continuous-source-v1.json
LEGACY_MICROSTRUCTURE_STATE="$DATA_ROOT/state/microstructure"
LEGACY_MICROSTRUCTURE_PRESERVATION="$DATA_ROOT/microstructure-v3-cutover/legacy-v2.sha256"
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

fail() { echo "[research-worker-upgrade] FAIL: $*" >&2; exit 1; }
ok() { echo "[research-worker-upgrade] OK: $*"; }
require_sha256() {
  local path="$1"
  local expected="$2"
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "frozen V3R1 contract missing or symlinked: $path"
  [ "$(sha256sum "$path" | awk '{print $1}')" = "$expected" ] \
    || fail "frozen V3R1 contract hash mismatch: $path"
}

case "$STAGING_DIR" in /home/ubuntu/.cache/agora-research-upgrade/*) ;; *) fail "unsupported staging path" ;; esac
case "$RELEASE_ID" in *[!A-Za-z0-9._-]*|'') fail "invalid release id" ;; esac
case "$SOURCE_GIT_COMMIT" in *[!0-9a-f]*|'') fail "invalid source Git commit" ;; esac
[ "${#SOURCE_GIT_COMMIT}" = "40" ] || fail "source Git commit must be 40 characters"
case "$SOURCE_GIT_BRANCH" in *[!A-Za-z0-9._/-]*|'') fail "invalid source Git branch" ;; esac
case "$SOURCE_GIT_DIRTY" in true|false) ;; *) fail "source Git dirty flag must be true or false" ;; esac
[ "$WORKER_ROOT" = "/opt/agora-research-worker" ] || fail "unexpected worker root"
[ "$DATA_ROOT" = "/var/lib/agora-research" ] || fail "unexpected data root"
case "$PRESERVE_BOUND_DATA_PLANE" in 0|1) ;; *) fail "preserve-bound-data-plane attestation must be exactly 0 or 1" ;; esac

binding_requested=false
if [ -n "$MICROSTRUCTURE_FORWARD_START_DAY" ] || [ -n "$MICROSTRUCTURE_DIAGNOSTIC_ID" ]; then
  [ -n "$MICROSTRUCTURE_FORWARD_START_DAY" ] \
    && [ -n "$MICROSTRUCTURE_DIAGNOSTIC_ID" ] \
    || fail "microstructure binding parameters must be supplied together"
  [[ "$MICROSTRUCTURE_FORWARD_START_DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail "invalid microstructure forward start day"
  [ "$MICROSTRUCTURE_FORWARD_START_DAY" > "$(date -u +%F)" ] \
    || fail "microstructure forward start day must be strictly future"
  date -u -d "$MICROSTRUCTURE_FORWARD_START_DAY" +%F 2>/dev/null \
    | grep -Fxq "$MICROSTRUCTURE_FORWARD_START_DAY" \
    || fail "microstructure forward start day is not a valid UTC date"
  [[ "$MICROSTRUCTURE_DIAGNOSTIC_ID" =~ ^okx-btcusdt-microstructure-forward-v3r1-[0-9]{8}-r[0-9]+$ ]] \
    || fail "invalid V3R1 microstructure diagnostic id"
  diagnostic_suffix="${MICROSTRUCTURE_DIAGNOSTIC_ID#okx-btcusdt-microstructure-forward-v3r1-}"
  [ "$diagnostic_suffix" = "${MICROSTRUCTURE_FORWARD_START_DAY//-/}"-"${diagnostic_suffix##*-}" ] \
    || fail "V3R1 diagnostic id does not match the frozen start day"
  binding_requested=true
fi
[ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] || [ "$binding_requested" = false ] \
  || fail "preserve mode rejects binding creation or replacement parameters"

microstructure_enabled="$(systemctl is-enabled "$MICROSTRUCTURE_UNIT" 2>/dev/null || true)"
case "$microstructure_enabled" in
  enabled|enabled-runtime|linked|linked-runtime|alias)
    fail "microstructure source unit must be disabled before upgrade"
    ;;
esac
microstructure_active="$(systemctl is-active "$MICROSTRUCTURE_UNIT" 2>/dev/null || true)"
case "$microstructure_active" in
  active|inactive) ;;
  failed) [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ] \
    || fail "failed microstructure source may only be retained by preserve mode" ;;
  *) fail "microstructure source unit state is unsupported: $microstructure_active" ;;
esac
if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] && [ "$microstructure_active" = active ]; then
  fail "microstructure source unit must be inactive before upgrade"
fi
if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] \
    && systemctl is-failed --quiet "$MICROSTRUCTURE_UNIT" 2>/dev/null; then
  fail "microstructure source has a lingering failed state; explicit read-only review and reset-failed are required before upgrade"
fi
carry_unit_preexisting=false
if systemctl cat "$CARRY_UNIT" >/dev/null 2>&1; then
  carry_unit_preexisting=true
  case "$(systemctl is-enabled "$CARRY_UNIT" 2>/dev/null || true)" in
    enabled|enabled-runtime|linked|linked-runtime|alias)
      fail "carry source unit must be disabled before upgrade"
      ;;
  esac
  [ "$(systemctl is-active "$CARRY_UNIT" 2>/dev/null || true)" = inactive ] \
    || fail "carry source unit must be inactive before upgrade"
  if systemctl is-failed --quiet "$CARRY_UNIT" 2>/dev/null; then
    fail "carry source has a lingering failed state; explicit review is required before upgrade"
  fi
  [ "$(systemctl show "$CARRY_UNIT" --property=MainPID --value)" = 0 ] \
    || fail "carry source unit must not have a MainPID before upgrade"
fi
binding_present=false
if [ -e "$MICROSTRUCTURE_BINDING" ] || [ -L "$MICROSTRUCTURE_BINDING" ]; then
  [ -f "$MICROSTRUCTURE_BINDING" ] && [ ! -L "$MICROSTRUCTURE_BINDING" ] \
    || fail "microstructure binding must be a regular non-symlink file"
  binding_present=true
fi

preserve_data_current=""
preserve_data_current_link=""
preserve_binding_sha256=""
preserve_binding_size=""
preserve_binding_bytes=""
preserve_state_file=""
preserve_state_sha256=""
preserve_state_size=""
preserve_state_bytes=""
preserve_manifest_sha256=""
preserve_provenance_sha256=""
preserve_source_main_pid=""
preserve_source_properties=""
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  [ -d "$DATA_ROOT" ] && [ ! -L "$DATA_ROOT" ] \
    || fail "preserve mode requires a regular canonical data root"
  [ "$(sudo stat -c '%U:%G:%a' "$DATA_ROOT")" = "$WORKER_USER:$EVIDENCE_GROUP:710" ] \
    || fail "canonical data root metadata does not permit source traversal"
  sudo -u "$SOURCE_USER" test -x "$DATA_ROOT" \
    || fail "public source identity cannot traverse the canonical data root"
  [ "$binding_present" = true ] || fail "preserve mode requires the existing V3R1 binding"
  [ -L "$WORKER_ROOT/current" ] || fail "preserve mode requires the data-current symlink"
  preserve_data_current_link="$(readlink "$WORKER_ROOT/current")" \
    || fail "data-current link cannot be read"
  preserve_data_current="$(readlink -f "$WORKER_ROOT/current")" \
    || fail "data-current link cannot be resolved"
  case "$preserve_data_current" in
    "$WORKER_ROOT"/releases/*) ;;
    *) fail "data-current escaped immutable releases" ;;
  esac
  [ -d "$preserve_data_current" ] && [ ! -L "$preserve_data_current" ] \
    || fail "resolved data-current is not an immutable release directory"
  IFS=$'\t' read -r preserve_generation_id preserve_bound_release preserve_bound_manifest < <(
    sudo python3 - \
      "$MICROSTRUCTURE_BINDING" \
      "$preserve_data_current" \
      "$EVIDENCE_GROUP" <<'PY'
from datetime import date, timedelta
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

binding_path = Path(sys.argv[1])
release = Path(sys.argv[2])
evidence_gid = grp.getgrnam(sys.argv[3]).gr_gid


def fail(message: str) -> None:
    raise SystemExit(message)


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate binding key: {key}")
        result[key] = value
    return result


details = binding_path.lstat()
if (
    stat.S_ISLNK(details.st_mode)
    or not stat.S_ISREG(details.st_mode)
    or details.st_uid != 0
    or details.st_gid != evidence_gid
    or stat.S_IMODE(details.st_mode) != 0o640
):
    fail("preserve binding type or metadata is invalid")
raw_binding = binding_path.read_bytes()
binding = json.loads(raw_binding, object_pairs_hook=reject_duplicates)
canonical = json.dumps(binding, separators=(",", ":"), sort_keys=True).encode("utf-8")
if raw_binding != canonical:
    fail("preserve binding bytes are not canonical")
fixed = {
    "schema_version": "OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1",
    "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    "recovery_contract_sha256": "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd",
    "v3_day_schema_sha256": "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709",
    "v3_diagnostic_contract_sha256": "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
    "complete_envelope_schema_sha256": "a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2",
    "rejection_envelope_schema_sha256": "833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497",
    "intake_state_schema_sha256": "12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d",
    "calendar_day_budget": 42,
    "required_consecutive_complete_days": 14,
    "selection_rule": "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK",
}
expected_keys = set(fixed) | {
    "generation_id",
    "diagnostic_id",
    "producer_release_id",
    "producer_manifest_sha256",
    "start_day",
    "end_day",
}
if set(binding) != expected_keys or any(binding.get(key) != value for key, value in fixed.items()):
    fail("preserve binding keys or fixed values changed")
generation_id = binding.get("generation_id")
diagnostic_id = binding.get("diagnostic_id")
if not isinstance(generation_id, str) or re.fullmatch(
    r"okx-btcusdt-microstructure-discovery-v3r1-[0-9]{8}-r[0-9]+", generation_id
) is None:
    fail("preserve binding generation id is invalid")
if not isinstance(diagnostic_id, str) or re.fullmatch(
    r"okx-btcusdt-microstructure-forward-v3r1-[0-9]{8}-r[0-9]+", diagnostic_id
) is None:
    fail("preserve binding diagnostic id is invalid")
manifest = release / ".release" / "source.sha256"
provenance_path = release / ".release" / "provenance.json"
for path in (manifest, provenance_path):
    path_details = path.lstat()
    if stat.S_ISLNK(path_details.st_mode) or not stat.S_ISREG(path_details.st_mode):
        fail("bound release metadata is missing or symlinked")
manifest_hash = hashlib.sha256(manifest.read_bytes()).hexdigest()
with provenance_path.open(encoding="utf-8") as stream:
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
if set(provenance) != expected_provenance_keys or provenance.get("schema_version") != "1":
    fail("bound release provenance schema is invalid")
if provenance.get("release_id") != release.name or provenance.get("source_manifest_sha256") != manifest_hash:
    fail("bound release provenance does not match manifest bytes")
if provenance.get("source_git_dirty") is not False:
    fail("bound release provenance is dirty")
if binding.get("producer_release_id") != release.name:
    fail("binding release id does not match data-current")
if binding.get("producer_manifest_sha256") != manifest_hash:
    fail("binding manifest hash does not match data-current")
print(f"{generation_id}\t{release.name}\t{manifest_hash}")
PY
  ) || fail "preserve binding, manifest, and provenance preflight failed"
  [ -n "$preserve_generation_id" ] \
    && [ "$preserve_bound_release" = "$(basename "$preserve_data_current")" ] \
    || fail "preserve binding release identity could not be sealed"
  preserve_state_file="$MICROSTRUCTURE_STATE/$preserve_generation_id.json"
  sudo python3 - "$MICROSTRUCTURE_STATE" "$preserve_state_file" <<'PY'
import os
from pathlib import Path
import stat
import sys

root, state = map(Path, sys.argv[1:])
root_details = root.lstat()
if stat.S_ISLNK(root_details.st_mode) or not stat.S_ISDIR(root_details.st_mode):
    raise SystemExit("microstructure V3R1 state root is invalid")
entries = list(os.scandir(root))
if len(entries) != 1 or Path(entries[0].path) != state:
    raise SystemExit("microstructure V3R1 state inventory is not exact")
details = state.lstat()
if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
    raise SystemExit("microstructure V3R1 state is missing or symlinked")
PY
  (
    cd "$preserve_data_current"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" - <<'PY'
from datetime import datetime, timezone
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    _load_binding,
    _load_state,
    _state_path,
    fixed_runtime_paths,
)

paths = fixed_runtime_paths()
binding = _load_binding(
    paths, require_future=False, today=datetime.now(timezone.utc).date()
)
_load_state(_state_path(paths.state_root, binding["generation_id"]), binding)
PY
  ) || fail "bound microstructure V3R1 state does not match its binding"
  preserve_binding_sha256="$(sudo sha256sum "$MICROSTRUCTURE_BINDING" | awk '{print $1}')"
  preserve_binding_size="$(sudo stat -c '%s' "$MICROSTRUCTURE_BINDING")"
  preserve_binding_bytes="$(sudo base64 -w0 "$MICROSTRUCTURE_BINDING")"
  preserve_state_sha256="$(sudo sha256sum "$preserve_state_file" | awk '{print $1}')"
  preserve_state_size="$(sudo stat -c '%s' "$preserve_state_file")"
  preserve_state_bytes="$(sudo base64 -w0 "$preserve_state_file")"
  preserve_manifest_sha256="$(sudo sha256sum "$preserve_data_current/.release/source.sha256" | awk '{print $1}')"
  preserve_provenance_sha256="$(sudo sha256sum "$preserve_data_current/.release/provenance.json" | awk '{print $1}')"
  [ "$preserve_manifest_sha256" = "$preserve_bound_manifest" ] \
    || fail "sealed manifest hash does not match binding preflight"
  preserve_source_main_pid="$(systemctl show "$MICROSTRUCTURE_UNIT" --property=MainPID --value)"
  case "$microstructure_active" in
    active) [[ "$preserve_source_main_pid" =~ ^[1-9][0-9]*$ ]] || fail "active source has no valid MainPID" ;;
    inactive|failed) [ "$preserve_source_main_pid" = 0 ] || fail "non-running source retains a MainPID" ;;
  esac
  preserve_source_properties="$(systemctl show "$MICROSTRUCTURE_UNIT" --no-pager \
    --property=LoadState --property=ActiveState --property=SubState \
    --property=UnitFileState --property=MainPID --property=Result \
    --property=FragmentPath --property=ExecMainStartTimestampMonotonic)"
  [ -n "$preserve_source_properties" ] || fail "source unit properties could not be sealed"
  ok "bound data-plane release, binding, state, and source lifecycle sealed before installation"
fi

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

legacy_microstructure_before="$(snapshot_legacy_microstructure)" \
  || fail "legacy V1/V2 inventory could not be sealed"
while IFS= read -r legacy_line; do
  ok "legacy V1/V2 before: $legacy_line"
done <<< "$legacy_microstructure_before"

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  if ! getent group "$EVIDENCE_GROUP" >/dev/null; then
    sudo groupadd --system "$EVIDENCE_GROUP"
  fi
  if ! id -u "$SOURCE_USER" >/dev/null 2>&1; then
    sudo useradd --system --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin --gid "$EVIDENCE_GROUP" "$SOURCE_USER"
  fi
  if id -nG "$WORKER_USER" | tr ' ' '\n' | grep -Fxq "$EVIDENCE_GROUP"; then
    sudo gpasswd -d "$WORKER_USER" "$EVIDENCE_GROUP" >/dev/null 2>&1 \
      || fail "could not remove worker from publisher group"
  fi
fi

SOURCE_DIR="$STAGING_DIR/source"
SOURCE_MANIFEST="$STAGING_DIR/source.sha256"
RELEASE_DIR="$WORKER_ROOT/releases/$RELEASE_ID"
[ -d "$SOURCE_DIR" ] && [ ! -L "$SOURCE_DIR" ] \
  || fail "staged source must be a regular directory"
[ -f "$SOURCE_MANIFEST" ] && [ ! -L "$SOURCE_MANIFEST" ] \
  || fail "source manifest must be a regular non-symlink file"
python3 - "$SOURCE_DIR" "$SOURCE_MANIFEST" <<'PY'
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import zipfile

root = Path(sys.argv[1])
manifest = Path(sys.argv[2])
source_roots = {"research_pipeline", "research_mcp", "research_source", "research"}
expected_top = source_roots | {"docs", "scripts", "target"}
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


def allowed(relative: str) -> bool:
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
    if forbidden(required_path) or not allowed(required_path):
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
    if allowed(rejected_path) and not forbidden(rejected_path):
        fail(f"closure predicate accepted forbidden path: {rejected_path}")


def exact_directory(path: Path, names: set[str], label: str) -> None:
    entries = list(os.scandir(path))
    if {entry.name for entry in entries} != names:
        fail(f"{label} differs from the frozen closure")


top_entries = list(os.scandir(root))
if {entry.name for entry in top_entries} != expected_top:
    fail("staged package top-level inventory differs from the frozen closure")
if any(entry.is_symlink() or not entry.is_dir(follow_symlinks=False) for entry in top_entries):
    fail("staged package top level must contain regular directories only")
exact_directory(root / "scripts", {"research-worker"}, "scripts inventory")
target_names = {entry.name for entry in os.scandir(root / "target")}
if target_names == {"microstructure-dist"}:
    dual_package = False
elif target_names == {"microstructure-dist", "dra-crypto-carry-dist"}:
    dual_package = True
else:
    fail("target inventory is neither the frozen micro-only nor dual closure")
exact_directory(
    root / "docs",
    {"autonomous-research-charter.md"},
    "documentation inventory",
)
required_directories = [
    root / "scripts" / "research-worker",
    root / "target" / "microstructure-dist",
]
if dual_package:
    required_directories.append(root / "target" / "dra-crypto-carry-dist")
for required_directory in required_directories:
    details = required_directory.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISDIR(details.st_mode):
        fail(f"staged package path is not a regular directory: {required_directory.name}")
charter = root / "docs" / "autonomous-research-charter.md"
charter_details = charter.lstat()
if stat.S_ISLNK(charter_details.st_mode) or not stat.S_ISREG(charter_details.st_mode):
    fail("research charter is not a regular non-symlink file")

dist = root / "target" / "microstructure-dist"
exact_directory(dist, {dist_jar, "lib"}, "microstructure distribution root")
jar_entry = next(entry for entry in os.scandir(dist) if entry.name == dist_jar)
lib_entry = next(entry for entry in os.scandir(dist) if entry.name == "lib")
if jar_entry.is_symlink() or not jar_entry.is_file(follow_symlinks=False):
    fail("microstructure producer jar is not a regular file")
if lib_entry.is_symlink() or not lib_entry.is_dir(follow_symlinks=False):
    fail("microstructure library root is not a regular directory")
libraries = list(os.scandir(dist / "lib"))
library_names = sorted(entry.name for entry in libraries)
if len(libraries) != 3 or any(
    entry.is_symlink() or not entry.is_file(follow_symlinks=False)
    for entry in libraries
):
    fail("microstructure distribution must contain exactly three library files")
patterns = (
    r"jackson-annotations-.+\.jar",
    r"jackson-core-.+\.jar",
    r"jackson-databind-.+\.jar",
)
if any(re.fullmatch(pattern, name) is None for pattern, name in zip(patterns, library_names)):
    fail("microstructure distribution contains unexpected libraries")

if dual_package:
    carry_dist = root / "target" / "dra-crypto-carry-dist"
    exact_directory(carry_dist, {carry_dist_jar, "lib"}, "carry distribution root")
    carry_jar = carry_dist / carry_dist_jar
    carry_lib = carry_dist / "lib"
    for path, expected_type in ((carry_jar, stat.S_ISREG), (carry_lib, stat.S_ISDIR)):
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not expected_type(details.st_mode):
            fail("carry distribution entry type is invalid")
    carry_library_paths = {path.name: path for path in carry_lib.iterdir()}
    if set(carry_library_paths) != set(carry_libraries):
        fail("carry Jackson inventory is not exact")
    for name, path in carry_library_paths.items():
        details = path.lstat()
        if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
            fail("carry Jackson dependency is not a regular file")
        if hashlib.sha256(path.read_bytes()).hexdigest() != carry_libraries[name]:
            fail("carry Jackson dependency hash mismatch")
    with zipfile.ZipFile(carry_jar) as archive:
        carry_classes = {name for name in archive.namelist() if name.endswith(".class")}
    if any(name.startswith("BOOT-INF/") for name in carry_classes):
        fail("carry classifier contains BOOT-INF")
    for name in carry_classes:
        if not any(
            name == f"com/agora/research/{family}.class"
            or name.startswith(f"com/agora/research/{family}$")
            for family in carry_families
        ):
            fail(f"unexpected carry class: {name}")
    for family in carry_families:
        if f"com/agora/research/{family}.class" not in carry_classes:
            fail(f"missing carry class family: {family}")

files: dict[str, Path] = {}
directories: set[str] = set()


def walk(directory: Path, prefix: str = "") -> None:
    for entry in os.scandir(directory):
        relative = f"{prefix}/{entry.name}" if prefix else entry.name
        details = entry.stat(follow_symlinks=False)
        if entry.is_symlink() or stat.S_ISLNK(details.st_mode):
            fail(f"staged package contains a symlink: {relative}")
        if forbidden(relative) or not allowed(relative):
            fail(f"staged package contains a forbidden path: {relative}")
        if stat.S_ISDIR(details.st_mode):
            directories.add(relative)
            walk(Path(entry.path), relative)
        elif stat.S_ISREG(details.st_mode):
            files[relative] = Path(entry.path)
        else:
            fail(f"staged package contains a non-regular entry: {relative}")


walk(root)
raw_manifest = manifest.read_bytes()
if b"\r" in raw_manifest or not raw_manifest.endswith(b"\n"):
    fail("source manifest must be canonical LF-terminated UTF-8")
try:
    manifest_lines = raw_manifest.decode("utf-8").splitlines()
except UnicodeDecodeError as error:
    fail(f"source manifest is not UTF-8: {error}")
if not manifest_lines or manifest_lines != sorted(manifest_lines):
    fail("source manifest must be nonempty and sorted")
listed: dict[str, str] = {}
for line in manifest_lines:
    match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
    if match is None:
        fail("source manifest contains a malformed line")
    digest, relative = match.groups()
    if relative in listed or forbidden(relative) or not allowed(relative):
        fail("source manifest contains a duplicate or forbidden path")
    listed[relative] = digest
if set(listed) != set(files):
    fail("source manifest does not exactly cover the staged package")
for relative, expected in listed.items():
    if hashlib.sha256(files[relative].read_bytes()).hexdigest() != expected:
        fail(f"source manifest hash mismatch: {relative}")
PY
carry_package=false
if [ -d "$SOURCE_DIR/target/dra-crypto-carry-dist" ]; then
  carry_package=true
  [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] \
    || fail "dual carry package is forbidden in preserve-bound-data-plane mode"
fi
[ -f "$SOURCE_DIR/research_pipeline/policy.v3.json" ] || fail "V3 policy missing"
[ -f "$SOURCE_DIR/scripts/research-worker/research-mcp-requirements.lock" ] || fail "MCP lock missing"
[ -s "$SOURCE_MANIFEST" ] || fail "source manifest missing"
(cd "$SOURCE_DIR" && sha256sum -c "$SOURCE_MANIFEST" >/dev/null) || fail "source manifest verification failed"
sudo test -f "$DATA_ROOT/state/authority.json" || fail "canonical state missing"

require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-discovery-recovery-contract.v3r1.json" \
  6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-discovery-source-binding.v3r1.schema.json" \
  1d07c67e6668ba8f7f01ebcb4a71d702e855cc6d40bb2e6260dbb30f97c2e60b
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-discovery-complete-envelope.v3r1.schema.json" \
  a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-discovery-rejection-envelope.v3r1.schema.json" \
  833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-discovery-intake-state.v3r1.schema.json" \
  12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-forward-day.v3.schema.json" \
  205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709
require_sha256 "$SOURCE_DIR/research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json" \
  7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a

if [ "$carry_package" = true ]; then
  require_sha256 "$SOURCE_DIR/research_pipeline/okx-dra-crypto-carry-expiry-futures-source-contract.v2.json" \
    183eeb35dc4729ff91970e4b892f141f58452abfa350591888587ce01035e4ad
  require_sha256 "$SOURCE_DIR/research_pipeline/okx-dra-crypto-carry-producer-envelope.v2.schema.json" \
    814fbef9722dcdd2a6dac8c56e159c1a34e7c2db559c306709c0c393e05230ee
  require_sha256 "$SOURCE_DIR/research_pipeline/okx-dra-crypto-carry-inventory-drop-envelope.v2.schema.json" \
    59e85d80aa4d2188af57872b7a2731881c85fd949fd7378c8a75cbff4dcdb196
  require_sha256 "$SOURCE_DIR/research_pipeline/okx-dra-crypto-carry-drop-envelope.v2.schema.json" \
    a438ba041e0ac80e3757f842659f2afa14b701c9a94034b1f59dffa5e2aa0563
  require_sha256 "$SOURCE_DIR/research_pipeline/okx-dra-crypto-carry-intake-state.v2.schema.json" \
    2c8af00a076616ffc25b95a2709bde1d4b6b7efb5899240e50d7c9f9322060d8

  if ! getent group "$CARRY_GROUP" >/dev/null; then
    sudo groupadd --system "$CARRY_GROUP"
  fi
  if ! id -u "$CARRY_USER" >/dev/null 2>&1; then
    sudo useradd --system --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin --gid "$CARRY_GROUP" "$CARRY_USER"
  fi
fi
if [ "$carry_package" = true ] || [ "$carry_unit_preexisting" = true ]; then
  id -u "$CARRY_USER" >/dev/null 2>&1 \
    || fail "carry source identity is missing"
  getent group "$CARRY_GROUP" >/dev/null \
    || fail "carry publisher group is missing"
  [ "$(id -gn "$CARRY_USER")" = "$CARRY_GROUP" ] \
    || fail "carry source identity primary group is not exact"
  mapfile -t carry_identity_groups < <(id -nG "$CARRY_USER" | tr ' ' '\n' | sort -u)
  [ "${#carry_identity_groups[@]}" = 1 ] \
    && [ "${carry_identity_groups[0]}" = "$CARRY_GROUP" ] \
    || fail "carry source identity has unexpected supplementary groups"
fi

MICROSTRUCTURE_DIST="$SOURCE_DIR/target/microstructure-dist"
MICROSTRUCTURE_JAR="$MICROSTRUCTURE_DIST/agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar"
[ -f "$MICROSTRUCTURE_JAR" ] && [ ! -L "$MICROSTRUCTURE_JAR" ] \
  || fail "narrow microstructure producer jar missing or symlinked"
[ -d "$MICROSTRUCTURE_DIST/lib" ] && [ ! -L "$MICROSTRUCTURE_DIST/lib" ] \
  || fail "microstructure runtime dependency directory missing or symlinked"
mapfile -t microstructure_libraries < <(find "$MICROSTRUCTURE_DIST/lib" -maxdepth 1 -type f -printf '%f\n' | sort)
[ "${#microstructure_libraries[@]}" = 3 ] \
  || fail "microstructure distribution must contain exactly three runtime libraries"
[[ "${microstructure_libraries[0]}" == jackson-annotations-*.jar ]] \
  && [[ "${microstructure_libraries[1]}" == jackson-core-*.jar ]] \
  && [[ "${microstructure_libraries[2]}" == jackson-databind-*.jar ]] \
  || fail "microstructure distribution contains unexpected runtime libraries"
if find "$MICROSTRUCTURE_DIST" -type l -print -quit | grep -q .; then
  fail "microstructure distribution contains a symlink"
fi

sudo install -d -o root -g root -m 0755 "$WORKER_ROOT" "$WORKER_ROOT/releases"
[ ! -e "$RELEASE_DIR" ] || fail "release already exists"
sudo install -d -o root -g root -m 0755 "$RELEASE_DIR"
sudo cp -a "$SOURCE_DIR/." "$RELEASE_DIR/"
sudo chown -R root:root "$RELEASE_DIR"
sudo find "$RELEASE_DIR" -type d -exec chmod 0755 {} +
sudo find "$RELEASE_DIR" -type f -exec chmod 0644 {} +
sudo chmod 0755 "$RELEASE_DIR/scripts/research-worker/"*.sh
sudo install -d -o root -g root -m 0755 "$RELEASE_DIR/.release"
sudo install -o root -g root -m 0644 "$SOURCE_MANIFEST" "$RELEASE_DIR/.release/source.sha256"
source_manifest_sha256="$(sha256sum "$SOURCE_MANIFEST" | awk '{print $1}')"
installed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sudo python3 - \
  "$RELEASE_DIR/.release/provenance.json" \
  "$RELEASE_ID" \
  "$SOURCE_GIT_COMMIT" \
  "$SOURCE_GIT_BRANCH" \
  "$SOURCE_GIT_DIRTY" \
  "$source_manifest_sha256" \
  "$installed_at" <<'PY'
import json
import os
import sys

path, release_id, commit, branch, dirty, manifest_hash, installed_at = sys.argv[1:]
temporary = path + ".tmp"
with open(temporary, "w", encoding="utf-8") as stream:
    json.dump(
        {
            "schema_version": "1",
            "release_id": release_id,
            "source_git_commit": commit,
            "source_git_branch": branch,
            "source_git_dirty": dirty == "true",
            "source_manifest_sha256": manifest_hash,
            "installed_at": installed_at,
        },
        stream,
        indent=2,
        sort_keys=True,
    )
    stream.write("\n")
os.replace(temporary, path)
PY
sudo chown root:root "$RELEASE_DIR/.release/provenance.json"
sudo chmod 0644 "$RELEASE_DIR/.release/provenance.json"

if [ ! -x "$WORKER_ROOT/venv/bin/python" ]; then
  sudo python3 -m venv "$WORKER_ROOT/venv"
fi
sudo "$WORKER_ROOT/venv/bin/python" -m pip install \
  --disable-pip-version-check --no-cache-dir \
  -r "$RELEASE_DIR/scripts/research-worker/research-mcp-requirements.lock" >/dev/null
sudo chown -R root:root "$WORKER_ROOT/venv"
sudo find "$WORKER_ROOT/venv" -type d -exec chmod go-w {} +
sudo find "$WORKER_ROOT/venv" -type f -exec chmod go-w {} +

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  (
    cd "$RELEASE_DIR"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" \
      -m research_pipeline.microstructure_discovery_r2_archive create
  )
  sudo test -f "$R2_MICROSTRUCTURE_ARCHIVE/archive-manifest.json" \
    && ! sudo test -L "$R2_MICROSTRUCTURE_ARCHIVE/archive-manifest.json" \
    || fail "R2 create-only archive manifest is missing or symlinked"
  ok "R2 binding, state, drop metadata, release provenance, journal, and failure evidence archived and hash verified"
fi

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  sudo install -d -o "$WORKER_USER" -g "$EVIDENCE_GROUP" -m 0710 "$DATA_ROOT"
  sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 \
    "$DATA_ROOT/auth" "$DATA_ROOT/requests" "$DATA_ROOT/requests/runs"
  sudo install -d -o root -g "$EVIDENCE_GROUP" -m 2770 \
    "$DATA_ROOT/source-requests" "$DATA_ROOT/source-requests/runs" \
    "$DATA_ROOT/source-drop" "$DATA_ROOT/source-drop/raw" "$DATA_ROOT/source-drop/runs"
  sudo install -d -o root -g root -m 0755 /var/lib/agora-evidence-source
  sudo install -d -o "$SOURCE_USER" -g "$EVIDENCE_GROUP" -m 0700 \
    "$MICROSTRUCTURE_STAGING"
  sudo install -d -o root -g "$EVIDENCE_GROUP" -m 1770 "$MICROSTRUCTURE_DROP"
  sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 \
    "$MICROSTRUCTURE_STATE" "$MICROSTRUCTURE_HANDOFF_STAGING" \
    "$MICROSTRUCTURE_HANDOFF_FINAL"
  if [ "$carry_package" = true ]; then
    sudo install -d -o root -g "$CARRY_GROUP" -m 0710 "$CARRY_ROOT"
    sudo install -d -o "$CARRY_USER" -g "$CARRY_GROUP" -m 0700 \
      "$CARRY_PRIVATE" "$CARRY_INVENTORY_STAGING" "$CARRY_DAY_STAGING"
    sudo install -d -o root -g "$CARRY_GROUP" -m 1770 \
      "$CARRY_INVENTORY_DROP" "$CARRY_DAY_DROP"
  fi
fi
if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] \
    && [ ! -f "$DATA_ROOT/auth/enrollment-consumed" ] \
    && [ ! -f "$DATA_ROOT/auth/enrollment-code.hash" ]; then
  sudo python3 - "$DATA_ROOT/auth" "$WORKER_USER" "$WORKER_GROUP" <<'PY'
import hashlib
import os
import pwd
import grp
import secrets
import sys
from pathlib import Path

root = Path(sys.argv[1])
uid = pwd.getpwnam(sys.argv[2]).pw_uid
gid = grp.getgrnam(sys.argv[3]).gr_gid
code = secrets.token_urlsafe(32)
salt = secrets.token_bytes(24)
iterations = 600_000
digest = hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"), salt, iterations)
hash_path = root / "enrollment-code.hash"
plain_path = root / "enrollment-code.once"
hash_path.write_text(f"pbkdf2_sha256${iterations}${salt.hex()}${digest.hex()}\n", encoding="utf-8")
plain_path.write_text(code + "\n", encoding="utf-8")
os.chown(hash_path, uid, gid)
os.chmod(hash_path, 0o600)
os.chown(plain_path, 0, 0)
os.chmod(plain_path, 0o400)
PY
fi

next_control_link="$WORKER_ROOT/.control-current-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$next_control_link"
sudo mv -Tf "$next_control_link" "$WORKER_ROOT/control-current"
if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  next_data_link="$WORKER_ROOT/.current-$RELEASE_ID"
  sudo ln -s "$RELEASE_DIR" "$next_data_link"
  sudo mv -Tf "$next_data_link" "$WORKER_ROOT/current"
fi

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  sudo install -d -o root -g root -m 0755 /etc/agora-research/local-tasks
  (
    cd "$RELEASE_DIR"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" - \
      "$RELEASE_DIR/research_pipeline/examples/local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json" \
      "$MICROSTRUCTURE_LOCAL_TASK" <<'PY'
import os
from pathlib import Path
import stat
import sys

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_source_contract import load_json_bytes_strict

source, destination = map(Path, sys.argv[1:])
payload = source.read_bytes()
validate_local_research_task(load_json_bytes_strict(payload, "V3R1 local task"))
if os.path.lexists(destination):
    details = destination.lstat()
    if (
        stat.S_ISLNK(details.st_mode)
        or not stat.S_ISREG(details.st_mode)
        or details.st_uid != 0
        or details.st_gid != 0
        or stat.S_IMODE(details.st_mode) != 0o444
        or destination.read_bytes() != payload
    ):
        raise SystemExit("existing V3R1 local task conflicts with frozen bytes")
    raise SystemExit(0)
temporary = destination.with_name(f".{destination.name}.tmp-{os.getpid()}")
descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
try:
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.chown(temporary, 0, 0)
    os.chmod(temporary, 0o444)
    os.link(temporary, destination, follow_symlinks=False)
    temporary.unlink()
finally:
    if temporary.exists():
        temporary.unlink()
PY
  )
  ok "V3R1 local diagnostic task installed create-only"
fi

if [ "$binding_requested" = true ]; then
  sudo install -d -o root -g root -m 0755 /etc/agora-research
  installed_manifest_sha256="$(sha256sum "$RELEASE_DIR/.release/source.sha256" | awk '{print $1}')"
  sudo python3 - \
    "$MICROSTRUCTURE_BINDING" \
    "$MICROSTRUCTURE_FORWARD_START_DAY" \
    "$MICROSTRUCTURE_DIAGNOSTIC_ID" \
    "$RELEASE_ID" \
    "$installed_manifest_sha256" \
    "$EVIDENCE_GROUP" <<'PY'
from datetime import date, timedelta
import grp
import json
import os
from pathlib import Path
import sys

path = Path(sys.argv[1])
start_day, diagnostic_id, release_id, manifest_hash, group_name = sys.argv[2:]
temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
parsed_start = date.fromisoformat(start_day)
suffix = diagnostic_id.removeprefix(
    "okx-btcusdt-microstructure-forward-v3r1-"
)
generation_id = "okx-btcusdt-microstructure-discovery-v3r1-" + suffix
payload = {
    "schema_version": "OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1",
    "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    "generation_id": generation_id,
    "diagnostic_id": diagnostic_id,
    "recovery_contract_sha256": "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd",
    "v3_day_schema_sha256": "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709",
    "v3_diagnostic_contract_sha256": "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
    "complete_envelope_schema_sha256": "a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2",
    "rejection_envelope_schema_sha256": "833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497",
    "intake_state_schema_sha256": "12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d",
    "producer_release_id": release_id,
    "producer_manifest_sha256": manifest_hash,
    "start_day": start_day,
    "end_day": (parsed_start + timedelta(days=41)).isoformat(),
    "calendar_day_budget": 42,
    "required_consecutive_complete_days": 14,
    "selection_rule": "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK",
}
data = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
if os.path.lexists(path):
    details = path.lstat()
    expected_gid = grp.getgrnam(group_name).gr_gid
    if (
        not path.is_file()
        or path.is_symlink()
        or path.read_bytes() != data
        or details.st_uid != 0
        or details.st_gid != expected_gid
        or (details.st_mode & 0o7777) != 0o640
    ):
        raise SystemExit("existing V3R1 binding conflicts with requested canonical binding")
    raise SystemExit(0)
descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
try:
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chown(temporary, 0, grp.getgrnam(group_name).gr_gid)
    os.chmod(temporary, 0o640)
    try:
        os.link(temporary, path, follow_symlinks=False)
    except FileExistsError:
        details = path.lstat()
        if (
            path.is_symlink()
            or not path.is_file()
            or path.read_bytes() != data
            or details.st_uid != 0
            or details.st_gid != grp.getgrnam(group_name).gr_gid
            or (details.st_mode & 0o7777) != 0o640
        ):
            raise SystemExit("concurrent V3R1 binding conflicts with requested bytes")
    temporary.unlink()
    directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
finally:
    if temporary.exists():
        temporary.unlink()
PY
fi

if [ "$binding_requested" = true ]; then
  (
    cd "$RELEASE_DIR"
    sudo bash -c \
      'umask 077; cd "$1"; exec env PYTHONDONTWRITEBYTECODE=1 "$2" -m research_pipeline.microstructure_discovery_recovery_intake_cli initialize' \
      -- "$RELEASE_DIR" "$WORKER_ROOT/venv/bin/python"
  )
  ok "microstructure intake state initialized or exactly validated"
fi

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ] \
    && { [ "$binding_requested" = true ] || [ "$binding_present" = true ]; }; then
  validated_state_file="$(
    cd "$RELEASE_DIR"
    sudo env PYTHONDONTWRITEBYTECODE=1 "$WORKER_ROOT/venv/bin/python" - <<'PY'
from datetime import datetime, timezone
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    _load_binding,
    _load_state,
    _state_path,
    fixed_runtime_paths,
)

paths = fixed_runtime_paths()
binding = _load_binding(
    paths,
    require_future=False,
    today=datetime.now(timezone.utc).date(),
)
state_path = _state_path(paths.state_root, binding["generation_id"])
_load_state(state_path, binding)
print(state_path)
PY
  )"
  case "$validated_state_file" in
    "$MICROSTRUCTURE_STATE"/[a-z0-9]*.json) ;;
    *) fail "validated microstructure state escaped dedicated namespace" ;;
  esac
  sudo test -f "$validated_state_file" && ! sudo test -L "$validated_state_file" \
    || fail "validated microstructure state is missing, non-regular, or symlinked"
  state_name="$(basename "$validated_state_file")"
  state_lock="$MICROSTRUCTURE_STATE/.${state_name}.lock"
  state_temp="$MICROSTRUCTURE_STATE/.${state_name}.tmp"
  ! sudo test -e "$state_lock" && ! sudo test -L "$state_lock" \
    || fail "microstructure state lock requires manual recovery"
  ! sudo test -e "$state_temp" && ! sudo test -L "$state_temp" \
    || fail "microstructure state temp requires manual recovery"
  sudo chown "$WORKER_USER:$WORKER_GROUP" "$validated_state_file"
  sudo chmod 0600 "$validated_state_file"
  ok "existing microstructure intake state exactly validated without overwrite"
fi

install_carry_unit=false
if [ "$carry_package" = true ] || [ "$carry_unit_preexisting" = true ]; then
  install_carry_unit=true
fi
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  units_to_install=(
    agora-research-heartbeat.service
    agora-research-dispatch.service
    agora-research-mcp.service
    agora-research-microstructure-handoff-export.service
  )
else
  units_to_install=(
    agora-research-heartbeat.service agora-research-heartbeat.timer
    agora-research-dispatch.service agora-research-dispatch.path agora-research-mcp.service
    agora-research-source.service agora-research-source.path
    agora-research-evidence-ingest.service agora-research-evidence-ingest.path
    agora-research-microstructure-source.service
    agora-research-microstructure-intake.service
    agora-research-microstructure-intake.path
    agora-research-microstructure-handoff-export.service
  )
fi
if [ "$install_carry_unit" = true ]; then
  units_to_install+=("$CARRY_UNIT")
fi
for unit in "${units_to_install[@]}"; do
  sudo install -o root -g root -m 0644 \
    "$RELEASE_DIR/scripts/research-worker/$unit" "/etc/systemd/system/$unit"
done
sudo install -d -o root -g root -m 0755 "$(dirname "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN")"
sudo install -o root -g root -m 0644 \
  "$RELEASE_DIR/scripts/research-worker/agora-research-microstructure-host-context.conf" \
  "$MICROSTRUCTURE_HOST_CONTEXT_DROPIN"
sudo systemctl daemon-reload
sudo systemd-analyze verify \
  /etc/systemd/system/agora-research-heartbeat.service \
  /etc/systemd/system/agora-research-heartbeat.timer \
  /etc/systemd/system/agora-research-dispatch.service \
  /etc/systemd/system/agora-research-dispatch.path \
  /etc/systemd/system/agora-research-mcp.service \
  /etc/systemd/system/agora-research-source.service \
  /etc/systemd/system/agora-research-source.path \
  /etc/systemd/system/agora-research-evidence-ingest.service \
  /etc/systemd/system/agora-research-evidence-ingest.path \
  /etc/systemd/system/agora-research-microstructure-source.service \
  /etc/systemd/system/agora-research-microstructure-intake.service \
  /etc/systemd/system/agora-research-microstructure-intake.path \
  /etc/systemd/system/agora-research-microstructure-handoff-export.service
if [ "$install_carry_unit" = true ]; then
  sudo systemd-analyze verify "/etc/systemd/system/$CARRY_UNIT"
fi
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  sudo systemctl enable --now agora-research-mcp.service agora-research-dispatch.path >/dev/null
else
  sudo systemctl disable --now agora-research-heartbeat.timer >/dev/null 2>&1 || true
  sudo systemctl enable --now "$MICROSTRUCTURE_INTAKE_PATH" >/dev/null
  sudo systemctl enable --now agora-research-mcp.service agora-research-dispatch.path \
    agora-research-source.path agora-research-evidence-ingest.path >/dev/null
fi
sudo systemctl restart agora-research-mcp.service
case "$(systemctl is-enabled "$MICROSTRUCTURE_UNIT" 2>/dev/null || true)" in
  enabled|enabled-runtime|linked|linked-runtime|alias)
    fail "microstructure source unit became enabled"
    ;;
esac
if systemctl is-failed --quiet "$MICROSTRUCTURE_UNIT" 2>/dev/null; then
  [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ] && [ "$microstructure_active" = failed ] \
    || fail "microstructure source newly entered a failed state during upgrade"
fi
if [ "$install_carry_unit" = true ]; then
  case "$(systemctl is-enabled "$CARRY_UNIT" 2>/dev/null || true)" in
    enabled|enabled-runtime|linked|linked-runtime|alias)
      fail "carry source unit became enabled"
      ;;
  esac
  [ "$(systemctl is-active "$CARRY_UNIT" 2>/dev/null || true)" = inactive ] \
    || fail "carry source unit did not remain inactive"
  if systemctl is-failed --quiet "$CARRY_UNIT" 2>/dev/null; then
    fail "carry source unit ended upgrade in a failed state"
  fi
  [ "$(systemctl show "$CARRY_UNIT" --property=MainPID --value)" = 0 ] \
    || fail "carry source unit ended upgrade with a MainPID"
  [ "$(systemctl show "$CARRY_UNIT" --property=TimeoutStartUSec --value)" = 30min ] \
    || fail "carry source effective start timeout is not exactly 30 minutes"
  [ "$(systemctl show "$CARRY_UNIT" --property=RuntimeMaxUSec --value)" = infinity ] \
    || fail "carry source retains an effective runtime maximum"
fi
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  [ "$(readlink "$WORKER_ROOT/current")" = "$preserve_data_current_link" ] \
    || fail "data-current link bytes changed during preserve upgrade"
  [ "$(readlink -f "$WORKER_ROOT/current")" = "$preserve_data_current" ] \
    || fail "data-current resolved release changed during preserve upgrade"
  [ "$(sudo sha256sum "$MICROSTRUCTURE_BINDING" | awk '{print $1}')" = "$preserve_binding_sha256" ] \
    && [ "$(sudo stat -c '%s' "$MICROSTRUCTURE_BINDING")" = "$preserve_binding_size" ] \
    && [ "$(sudo base64 -w0 "$MICROSTRUCTURE_BINDING")" = "$preserve_binding_bytes" ] \
    || fail "binding bytes or SHA-256 changed during preserve upgrade"
  [ "$(sudo sha256sum "$preserve_state_file" | awk '{print $1}')" = "$preserve_state_sha256" ] \
    && [ "$(sudo stat -c '%s' "$preserve_state_file")" = "$preserve_state_size" ] \
    && [ "$(sudo base64 -w0 "$preserve_state_file")" = "$preserve_state_bytes" ] \
    || fail "microstructure state bytes or SHA-256 changed during preserve upgrade"
  [ "$(sudo sha256sum "$preserve_data_current/.release/source.sha256" | awk '{print $1}')" = "$preserve_manifest_sha256" ] \
    && [ "$(sudo sha256sum "$preserve_data_current/.release/provenance.json" | awk '{print $1}')" = "$preserve_provenance_sha256" ] \
    || fail "bound release metadata changed during preserve upgrade"
  [ "$(systemctl is-active "$MICROSTRUCTURE_UNIT" 2>/dev/null || true)" = "$microstructure_active" ] \
    || fail "microstructure source active state changed during preserve upgrade"
  [ "$(systemctl show "$MICROSTRUCTURE_UNIT" --property=MainPID --value)" = "$preserve_source_main_pid" ] \
    || fail "microstructure source MainPID changed during preserve upgrade"
  source_properties_after="$(systemctl show "$MICROSTRUCTURE_UNIT" --no-pager \
    --property=LoadState --property=ActiveState --property=SubState \
    --property=UnitFileState --property=MainPID --property=Result \
    --property=FragmentPath --property=ExecMainStartTimestampMonotonic)"
  [ "$source_properties_after" = "$preserve_source_properties" ] \
    || fail "microstructure source unit properties changed during preserve upgrade"
  [ "$(readlink -f "$WORKER_ROOT/control-current")" = "$RELEASE_DIR" ] \
    || fail "control-current does not resolve to the new release"
else
  if systemctl is-active --quiet "$MICROSTRUCTURE_UNIT"; then
    fail "microstructure source unit became active"
  fi
  [ "$(readlink -f "$WORKER_ROOT/current")" = "$RELEASE_DIR" ] \
    || fail "ordinary upgrade did not switch data-current to the new release"
  [ "$(readlink -f "$WORKER_ROOT/control-current")" = "$RELEASE_DIR" ] \
    || fail "ordinary upgrade did not switch control-current to the new release"
fi

legacy_microstructure_after="$(snapshot_legacy_microstructure)" \
  || fail "legacy V1/V2 inventory could not be reverified"
[ "$legacy_microstructure_after" = "$legacy_microstructure_before" ] \
  || fail "legacy V1/V2 path, type, bytes, or SHA-256 changed during V3 cutover"
while IFS= read -r legacy_line; do
  ok "legacy V1/V2 preserved: $legacy_line"
done <<< "$legacy_microstructure_after"

if [ "$PRESERVE_BOUND_DATA_PLANE" = 0 ]; then
  sudo install -d -o root -g root -m 0700 \
    "$(dirname "$LEGACY_MICROSTRUCTURE_PRESERVATION")"
  sudo python3 - \
    "$LEGACY_MICROSTRUCTURE_PRESERVATION" \
    "$legacy_microstructure_after" <<'PY'
import os
from pathlib import Path
import stat
import sys

path = Path(sys.argv[1])
data = (sys.argv[2] + "\n").encode("utf-8")
if os.path.lexists(path):
    details = path.lstat()
    if (
        stat.S_ISLNK(details.st_mode)
        or not stat.S_ISREG(details.st_mode)
        or details.st_uid != 0
        or details.st_gid != 0
        or (details.st_mode & 0o7777) != 0o400
        or path.read_bytes() != data
    ):
        raise SystemExit("legacy V1/V2 preservation seal conflicts")
    raise SystemExit(0)
temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
try:
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chown(temporary, 0, 0)
    os.chmod(temporary, 0o400)
    os.link(temporary, path, follow_symlinks=False)
    temporary.unlink()
    directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
finally:
    if temporary.exists():
        temporary.unlink()
PY
  SNIPPET_SOURCE="$RELEASE_DIR/scripts/research-worker/nginx-research-mcp.conf" \
    bash "$RELEASE_DIR/scripts/research-worker/install-nginx-route.sh"
fi

ok "control release installed: $RELEASE_ID"
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  ok "bound data-plane release preserved: $(basename "$preserve_data_current")"
else
  ok "data-plane release installed: $RELEASE_ID"
fi
ok "OAuth Research MCP active on loopback"
if [ "$PRESERVE_BOUND_DATA_PLANE" = 1 ]; then
  ok "bound microstructure source lifecycle, binding, state, and release remained unchanged"
else
  ok "main, public-source, and network-denied ingest paths active; server timer disabled"
  ok "microstructure intake path active; producer source unit disabled and inactive"
  if [ "$carry_package" = true ]; then
    ok "carry capability installed with dedicated identity; source unit is disabled, inactive, unfailed, unbound, and unregistered"
  elif [ "$carry_unit_preexisting" = true ]; then
    ok "previously installed carry unit refreshed without activation; no carry readiness claim"
  fi
fi
