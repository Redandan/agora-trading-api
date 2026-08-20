#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT=/opt/agora-research-worker
CURRENT_LINK="$WORKER_ROOT/carry-current"
BINDING_PATH=/etc/agora-research/okx-dra-crypto-carry-source-v2.json
REQUEST_ROOT=/var/lib/agora-research/dra-crypto-carry-source-request-v2
V3R1_REQUEST_ROOT=/var/lib/agora-dra-carry-source/dra-crypto-carry-v3r1-request
V3R1_REQUEST_PATH="$V3R1_REQUEST_ROOT/schema-probe-request.v3r1.json"
V3R1_PROBE_DROP=/var/lib/agora-dra-carry-source/dra-crypto-carry-v3r1-probe-drop
V3R1_PROBE_OUTPUT="$V3R1_PROBE_DROP/okx-dra-crypto-carry-public-axes-schema-probe.v3r1.json"
PYTHON_BIN=/opt/agora-research-worker/venv/bin/python
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
    || fail "frozen file missing or symlinked: $path"
  [ "$(/usr/bin/sha256sum "$path" | awk '{print $1}')" = "$expected" ] \
    || fail "frozen file hash mismatch: $path"
}

[ "$#" -eq 0 ] || fail "caller arguments are forbidden"
[ -x "$JAVA_BIN" ] || fail "fixed Java executable is unavailable"
v3r1_probe_requested=false
if [ -e "$V3R1_REQUEST_PATH" ] || [ -L "$V3R1_REQUEST_PATH" ]; then
  v3r1_probe_requested=true
fi
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
for path in "$manifest" "$provenance" "$jar_path"; do
  [ -f "$path" ] && [ ! -L "$path" ] \
    || fail "required regular file missing or symlinked: $path"
done
[ -d "$lib_dir" ] && [ ! -L "$lib_dir" ] \
  || fail "runtime dependency directory is missing or symlinked"
if [ "$v3r1_probe_requested" = true ]; then
  [ -d "$V3R1_REQUEST_ROOT" ] && [ ! -L "$V3R1_REQUEST_ROOT" ] \
    || fail "V3R1 probe request root is missing or symlinked"
  [ -f "$V3R1_REQUEST_PATH" ] && [ ! -L "$V3R1_REQUEST_PATH" ] \
    || fail "V3R1 probe request is missing or symlinked"
  [ ! -e "$BINDING_PATH" ] && [ ! -L "$BINDING_PATH" ] \
    || fail "V3R1 probe refuses a pre-existing V2 source binding"
  [ ! -e "$REQUEST_ROOT" ] && [ ! -L "$REQUEST_ROOT" ] \
    || fail "V3R1 probe refuses a simultaneous V2 request root"
else
  [ -f "$BINDING_PATH" ] && [ ! -L "$BINDING_PATH" ] \
    || fail "fixed V2 source binding is missing or symlinked"
  [ -d "$REQUEST_ROOT" ] && [ ! -L "$REQUEST_ROOT" ] \
    || fail "fixed V2 source request root is missing or symlinked"
fi

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
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-public-axes-source-contract.v3r1.json" \
  2e44b85a5a3998bf7285adbaba62095f80f0c7fce3fec9c75a0ec26369d90bcd
require_sha256 "$current/research_pipeline/okx-dra-crypto-carry-public-axes-schema-probe.v3r1.schema.json" \
  137eda117cdcecaaccdd5ca03c54f26be5f718d28e5718553dbbd46421f6787a
require_sha256 "$current/research_pipeline/dra_crypto_carry_public_axes_v3r1.py" \
  b1855510810e19f919d22e671bfa6f06da7b2c32b43c4b8bc2c2f7a0ced87d79
require_sha256 "$current/research_pipeline/dra_crypto_carry_public_axes_v3r1_producer.py" \
  b5bfb85c2bb1b3fcbcaf454220c89adfbd1dfd9abed336af7274ccfc089c702e
require_sha256 "$current/research_pipeline/examples/okx-dra-crypto-carry-public-axes-schema-probe-request.v3r1.json" \
  f0bd0d148cfdc6e5164e51f370300edcb03022879d6c04f1cfbc4c1dc99f0f9e

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

if [ "$v3r1_probe_requested" = true ]; then
  [ -x "$PYTHON_BIN" ] || fail "fixed research Python is unavailable"
  require_sha256 "$V3R1_REQUEST_PATH" \
    f0bd0d148cfdc6e5164e51f370300edcb03022879d6c04f1cfbc4c1dc99f0f9e
  mapfile -t v3r1_request_entries < <(
    find "$V3R1_REQUEST_ROOT" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort
  )
  [ "${#v3r1_request_entries[@]}" = 1 ] \
    && [ "${v3r1_request_entries[0]}" = schema-probe-request.v3r1.json ] \
    || fail "V3R1 probe request root inventory is not exact"
  [ -d "$V3R1_PROBE_DROP" ] && [ ! -L "$V3R1_PROBE_DROP" ] \
    || fail "V3R1 probe drop is missing or symlinked"
  [ ! -e "$V3R1_PROBE_OUTPUT" ] && [ ! -L "$V3R1_PROBE_OUTPUT" ] \
    || fail "V3R1 probe output already exists"
  exec "$PYTHON_BIN" -m research_pipeline.dra_crypto_carry_public_axes_v3r1_producer \
    "$V3R1_PROBE_OUTPUT"
fi

exec "$JAVA_BIN" -cp "$jar_path:$lib_dir/*" "$MAIN_CLASS"
