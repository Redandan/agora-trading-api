#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT=/opt/agora-research-worker
CURRENT_LINK="$WORKER_ROOT/current"
BINDING_PATH=/etc/agora-research/okx-microstructure-continuous-source-v3r1.json
JAVA_BIN=/usr/bin/java
MAIN_CLASS=com.agora.research.OkxMicrostructureDiscoveryRecoverySourceCli
DIST_JAR=target/microstructure-dist/agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar

fail() {
  echo "[microstructure-source] BLOCKED: $*" >&2
  exit 2
}

require_sha256() {
  local path="$1"
  local expected="$2"
  [ -f "$path" ] && [ ! -L "$path" ] || fail "frozen V3R1 contract missing or symlinked: $path"
  [ "$(/usr/bin/sha256sum "$path" | awk '{print $1}')" = "$expected" ] \
    || fail "frozen V3R1 contract hash mismatch: $path"
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

require_sha256 "$current/research_pipeline/okx-microstructure-discovery-recovery-contract.v3r1.json" \
  6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd
require_sha256 "$current/research_pipeline/okx-microstructure-discovery-source-binding.v3r1.schema.json" \
  1d07c67e6668ba8f7f01ebcb4a71d702e855cc6d40bb2e6260dbb30f97c2e60b
require_sha256 "$current/research_pipeline/okx-microstructure-discovery-complete-envelope.v3r1.schema.json" \
  a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2
require_sha256 "$current/research_pipeline/okx-microstructure-discovery-rejection-envelope.v3r1.schema.json" \
  833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497
require_sha256 "$current/research_pipeline/okx-microstructure-discovery-intake-state.v3r1.schema.json" \
  12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d
require_sha256 "$current/research_pipeline/okx-microstructure-discovery-r2-archive-manifest.v1.schema.json" \
  050542e9c0668738cb25e60dc00343274e28d4514cd33ad8cc30daf249ce5f7e
require_sha256 "$current/research_pipeline/okx-microstructure-forward-day.v3.schema.json" \
  205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709
require_sha256 "$current/research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json" \
  7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a

[ "$(stat -c '%U:%G' "$BINDING_PATH")" = "root:agora-evidence" ] \
  || fail "binding owner must be root:agora-evidence"
[ "$(stat -c '%a' "$BINDING_PATH")" = "640" ] \
  || fail "binding mode must be 0640"

java_version="$($JAVA_BIN -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ { print $2; exit }')"
[ "$java_version" = "21" ] || fail "Java 21 is required"

/usr/bin/python3 - "$BINDING_PATH" "$provenance" "$manifest" "$current" <<'PY'
from datetime import date, timedelta
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
    "generation_id",
    "diagnostic_id",
    "recovery_contract_sha256",
    "v3_day_schema_sha256",
    "v3_diagnostic_contract_sha256",
    "complete_envelope_schema_sha256",
    "rejection_envelope_schema_sha256",
    "intake_state_schema_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "start_day",
    "end_day",
    "calendar_day_budget",
    "required_consecutive_complete_days",
    "selection_rule",
}
if set(binding) != expected_keys:
    raise SystemExit("binding keys mismatch")
canonical_binding = json.dumps(
    binding, ensure_ascii=False, separators=(",", ":"), sort_keys=True
).encode("utf-8")
if binding_path.read_bytes() != canonical_binding:
    raise SystemExit("binding bytes are not canonical")
if binding["schema_version"] != "OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1":
    raise SystemExit("binding schema mismatch")
if binding["authorization"] != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
    raise SystemExit("binding authorization mismatch")
if binding["calendar_day_budget"] != 42 or binding["required_consecutive_complete_days"] != 14:
    raise SystemExit("binding day count mismatch")
if binding["selection_rule"] != "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK":
    raise SystemExit("selection rule mismatch")
expected_hashes = {
    "recovery_contract_sha256": "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd",
    "v3_day_schema_sha256": "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709",
    "v3_diagnostic_contract_sha256": "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a",
    "complete_envelope_schema_sha256": "a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2",
    "rejection_envelope_schema_sha256": "833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497",
    "intake_state_schema_sha256": "12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d",
}
if any(binding.get(key) != value for key, value in expected_hashes.items()):
    raise SystemExit("V3R1 contract hash mismatch")
if not re.fullmatch(
    r"okx-btcusdt-microstructure-discovery-v3r1-[0-9]{8}-r[0-9]+",
    str(binding["generation_id"]),
):
    raise SystemExit("generation id is invalid")
if not re.fullmatch(
    r"okx-btcusdt-microstructure-forward-v3r1-[0-9]{8}-r[0-9]+",
    str(binding["diagnostic_id"]),
):
    raise SystemExit("diagnostic id is invalid")
try:
    start_day = date.fromisoformat(binding["start_day"])
    end_day = date.fromisoformat(binding["end_day"])
except (TypeError, ValueError) as error:
    raise SystemExit("calendar day is invalid") from error
if end_day != start_day + timedelta(days=41):
    raise SystemExit("frozen calendar is invalid")
start_token = start_day.strftime("%Y%m%d")
generation_suffix = binding["generation_id"].removeprefix(
    "okx-btcusdt-microstructure-discovery-v3r1-"
)
diagnostic_suffix = binding["diagnostic_id"].removeprefix(
    "okx-btcusdt-microstructure-forward-v3r1-"
)
if generation_suffix != diagnostic_suffix or not generation_suffix.startswith(start_token + "-"):
    raise SystemExit("binding identity is inconsistent")
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
