#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT=/opt/agora-research-worker
CURRENT_LINK="$WORKER_ROOT/current"
BINDING_PATH=/etc/agora-research/okx-dra-crypto-carry-source-v2.json
REQUEST_ROOT=/var/lib/agora-research/dra-crypto-carry-source-request-v2
JAVA_BIN=/usr/bin/java
MAIN_CLASS=com.agora.research.OkxDraCryptoCarryPhaseCli
DIST_ROOT=target/dra-crypto-carry-dist
DIST_JAR="$DIST_ROOT/agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"

fail() {
  echo "[dra-crypto-carry-source] BLOCKED: $*" >&2
  exit 2
}

require_sha256() {
  local path="$1"
  local expected="$2"
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "frozen V2 contract missing or symlinked: $path"
  [ "$(/usr/bin/sha256sum "$path" | awk '{print $1}')" = "$expected" ] \
    || fail "frozen V2 contract hash mismatch: $path"
}

[ "$#" -eq 0 ] || fail "caller arguments are forbidden"
[ -x "$JAVA_BIN" ] || fail "fixed Java executable is unavailable"
[ -L "$CURRENT_LINK" ] || fail "current release link is missing"
current="$(readlink -f "$CURRENT_LINK")"
case "$current" in
  "$WORKER_ROOT"/releases/*) ;;
  *) fail "current release escapes worker root" ;;
esac
[ -d "$current" ] && [ ! -L "$current" ] \
  || fail "resolved current release is not an immutable directory"

manifest="$current/.release/source.sha256"
provenance="$current/.release/provenance.json"
jar_path="$current/$DIST_JAR"
lib_dir="$current/$DIST_ROOT/lib"
for path in "$BINDING_PATH" "$manifest" "$provenance" "$jar_path"; do
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "required regular file missing or symlinked: $path"
done
[ -d "$REQUEST_ROOT" ] && [ ! -L "$REQUEST_ROOT" ] \
  || fail "fixed source request root is missing or symlinked"
[ -d "$lib_dir" ] && [ ! -L "$lib_dir" ] \
  || fail "runtime dependency directory is missing or symlinked"

require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-expiry-futures-source-contract.v2.json" \
  183eeb35dc4729ff91970e4b892f141f58452abfa350591888587ce01035e4ad
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-producer-envelope.v2.schema.json" \
  814fbef9722dcdd2a6dac8c56e159c1a34e7c2db559c306709c0c393e05230ee
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-inventory-drop-envelope.v2.schema.json" \
  59e85d80aa4d2188af57872b7a2731881c85fd949fd7378c8a75cbff4dcdb196
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-drop-envelope.v2.schema.json" \
  a438ba041e0ac80e3757f842659f2afa14b701c9a94034b1f59dffa5e2aa0563
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-intake-state.v2.schema.json" \
  2c8af00a076616ffc25b95a2709bde1d4b6b7efb5899240e50d7c9f9322060d8

java_version="$($JAVA_BIN -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ { print $2; exit }')"
[ "$java_version" = 21 ] || fail "Java 21 is required"

/usr/bin/python3 - "$current" "$manifest" "$provenance" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import zipfile

current, manifest, provenance = map(Path, sys.argv[1:])
dist = current / "target" / "dra-crypto-carry-dist"
jar = dist / "agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"
lib = dist / "lib"
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


def fail(message):
    raise SystemExit(message)


with provenance.open(encoding="utf-8") as stream:
    provenance_value = json.load(stream)
manifest_bytes = manifest.read_bytes()
manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
if provenance_value.get("release_id") != current.name:
    fail("release provenance id mismatch")
if provenance_value.get("source_manifest_sha256") != manifest_hash:
    fail("release provenance manifest hash mismatch")
if provenance_value.get("source_git_dirty") is not False:
    fail("release provenance is dirty")

entries = {}
for line in manifest_bytes.decode("utf-8").splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
    if match is None or match.group(2) in entries:
        fail("installed source manifest is malformed")
    entries[match.group(2)] = match.group(1)

if set(os.listdir(dist)) != {jar.name, "lib"}:
    fail("carry distribution root inventory is not exact")
for path in (dist, jar, lib):
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode):
        fail("carry distribution contains a symlink")
if not stat.S_ISREG(jar.lstat().st_mode) or not stat.S_ISDIR(lib.lstat().st_mode):
    fail("carry distribution entry types are invalid")
libraries = {path.name: path for path in lib.iterdir()}
if set(libraries) != set(expected_libraries):
    fail("carry Jackson inventory is not exact")
for name, path in libraries.items():
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode) or not stat.S_ISREG(details.st_mode):
        fail("carry Jackson library is not a regular file")
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected_libraries[name]:
        fail("carry Jackson hash mismatch")

with zipfile.ZipFile(jar) as archive:
    classes = {name for name in archive.namelist() if name.endswith(".class")}
if any(name.startswith("BOOT-INF/") for name in classes):
    fail("carry classifier contains BOOT-INF")
for name in classes:
    if not any(
        name == f"com/agora/research/{family}.class"
        or name.startswith(f"com/agora/research/{family}$")
        for family in families
    ):
        fail(f"unexpected carry class: {name}")
for family in families:
    if f"com/agora/research/{family}.class" not in classes:
        fail(f"missing carry class family: {family}")

actual = set()
for path in dist.rglob("*"):
    details = path.lstat()
    if stat.S_ISLNK(details.st_mode):
        fail("carry distribution contains a symlink")
    if stat.S_ISREG(details.st_mode):
        actual.add(path.relative_to(current).as_posix())
listed = {path for path in entries if path.startswith("target/dra-crypto-carry-dist/")}
if actual != listed:
    fail("carry distribution is not exactly covered by installed manifest")
for relative in actual:
    if hashlib.sha256((current / relative).read_bytes()).hexdigest() != entries[relative]:
        fail(f"installed manifest hash mismatch: {relative}")
PY

(
  cd "$current"
  /usr/bin/sha256sum -c .release/source.sha256 >/dev/null
) || fail "installed release differs from its sealed manifest"

exec "$JAVA_BIN" -cp "$jar_path:$lib_dir/*" "$MAIN_CLASS"
