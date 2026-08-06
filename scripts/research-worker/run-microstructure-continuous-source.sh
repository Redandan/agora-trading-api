#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT=/opt/agora-research-worker
CURRENT_LINK="$WORKER_ROOT/current"
BINDING_PATH=/etc/agora-research/okx-microstructure-continuous-source-v1.json
JAVA_BIN=/usr/bin/java
MAIN_CLASS=com.agora.research.OkxMicrostructureContinuousSourceCli
DIST_JAR=target/microstructure-dist/agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar

fail() {
  echo "[microstructure-source] BLOCKED: $*" >&2
  exit 2
}

[ "$#" -eq 0 ] || fail "caller arguments are forbidden"
[ -x "$JAVA_BIN" ] || fail "fixed Java executable is unavailable"
[ -L "$CURRENT_LINK" ] || fail "current release link is missing"
current="$(readlink -f "$CURRENT_LINK")"
case "$current" in
  "$WORKER_ROOT"/releases/*) ;;
  *) fail "current release escapes worker root" ;;
esac

manifest="$current/.release/source.sha256"
provenance="$current/.release/provenance.json"
jar_path="$current/$DIST_JAR"
lib_dir="$current/target/microstructure-dist/lib"
for path in "$BINDING_PATH" "$manifest" "$provenance" "$jar_path"; do
  [ -f "$path" ] && [ ! -L "$path" ] || fail "required regular file missing or symlinked: $path"
done
[ -d "$lib_dir" ] && [ ! -L "$lib_dir" ] || fail "runtime dependency directory missing or symlinked"

[ "$(stat -c '%U:%G' "$BINDING_PATH")" = "root:agora-evidence" ] \
  || fail "binding owner must be root:agora-evidence"
[ "$(stat -c '%a' "$BINDING_PATH")" = "640" ] \
  || fail "binding mode must be 0640"

java_version="$($JAVA_BIN -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ { print $2; exit }')"
[ "$java_version" = "21" ] || fail "Java 21 is required"

/usr/bin/python3 - "$BINDING_PATH" "$provenance" "$manifest" "$current" <<'PY'
from datetime import date, datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

binding_path, provenance_path, manifest_path, current_path = map(Path, sys.argv[1:])

def load(path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream, object_pairs_hook=reject_duplicates)

def reject_duplicates(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise SystemExit(f"duplicate JSON key: {key}")
        value[key] = item
    return value

binding = load(binding_path)
provenance = load(provenance_path)
expected_keys = {
    "schema_version",
    "authorization",
    "forward_start_day",
    "required_complete_utc_days",
    "diagnostic_id",
    "source_contract_sha256",
    "day_schema_sha256",
    "diagnostic_contract_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
}
if set(binding) != expected_keys:
    raise SystemExit("binding keys mismatch")
if binding["schema_version"] != "1":
    raise SystemExit("binding schema mismatch")
if binding["authorization"] != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
    raise SystemExit("binding authorization mismatch")
if binding["required_complete_utc_days"] != 14:
    raise SystemExit("binding day count mismatch")
if binding["source_contract_sha256"] != "f2b353fc211d86755488bb7d9ee63057c6def8b9cd5353b86f7514981cc3e51e":
    raise SystemExit("source contract hash mismatch")
if binding["day_schema_sha256"] != "916525b47fcd7f8862522ca740bf987cbb5d5082237d94d8814087b8b3853fc1":
    raise SystemExit("day schema hash mismatch")
if binding["diagnostic_contract_sha256"] != "b58ae60f76bcdb7c60114c0b076730225056e11ca5cfe604fe7415b4e41ffe6c":
    raise SystemExit("diagnostic contract hash mismatch")
if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,79}", str(binding["diagnostic_id"])):
    raise SystemExit("diagnostic id is invalid")
try:
    start_day = date.fromisoformat(binding["forward_start_day"])
except (TypeError, ValueError) as error:
    raise SystemExit("forward start day is invalid") from error
if start_day <= datetime.now(timezone.utc).date():
    raise SystemExit("forward start day is not strictly future")

manifest_bytes = manifest_path.read_bytes()
manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
release_id = current_path.name
if provenance.get("release_id") != release_id:
    raise SystemExit("installed provenance release id mismatch")
if provenance.get("source_manifest_sha256") != manifest_hash:
    raise SystemExit("installed provenance manifest hash mismatch")
if binding["producer_release_id"] != release_id:
    raise SystemExit("binding release id mismatch")
if binding["producer_manifest_sha256"] != manifest_hash:
    raise SystemExit("binding manifest hash mismatch")

entries = {}
for line in manifest_bytes.decode("utf-8").splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
    if not match or match.group(2) in entries:
        raise SystemExit("installed source manifest is malformed")
    entries[match.group(2)] = match.group(1)

dist_root = current_path / "target" / "microstructure-dist"
actual = set()
for path in dist_root.rglob("*"):
    if path.is_symlink():
        raise SystemExit("microstructure distribution contains a symlink")
    if path.is_file():
        actual.add(path.relative_to(current_path).as_posix())
expected_jar = "target/microstructure-dist/agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar"
if expected_jar not in actual:
    raise SystemExit("narrow producer jar is missing")
libraries = sorted(path for path in actual if path.startswith("target/microstructure-dist/lib/"))
expected_prefixes = ("jackson-annotations-", "jackson-core-", "jackson-databind-")
if len(libraries) != 3 or any(
    not Path(path).name.startswith(prefix)
    for path, prefix in zip(libraries, expected_prefixes)
):
    raise SystemExit("Jackson runtime distribution is not exact")
manifest_dist = {path for path in entries if path.startswith("target/microstructure-dist/")}
if manifest_dist != actual:
    raise SystemExit("distribution inventory is not exactly covered by installed manifest")
PY

(
  cd "$current"
  /usr/bin/sha256sum -c .release/source.sha256 >/dev/null
) || fail "installed release differs from its sealed manifest"

exec "$JAVA_BIN" -cp "$jar_path:$lib_dir/*" "$MAIN_CLASS"
