#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/agora-research-worker/current}"
REQUEST_DIR="${AGORA_RESEARCH_REQUEST_DIR:-/var/lib/agora-research/requests}"
INBOX_DIR="${AGORA_RESEARCH_INBOX_DIR:-/var/lib/agora-research/inbox}"
RUNTIME_DIR="${AGORA_RESEARCH_RUNTIME_DIR:-/run/agora-research}"
PENDING="$REQUEST_DIR/pending.json"
RUNNING="$REQUEST_DIR/running.json"

umask 077
mkdir -p "$REQUEST_DIR/runs"

finalized=0
payload_file=""
result_file=""
finalize_failed_request() {
  exit_code=$?
  trap - EXIT
  if [ "$finalized" = "0" ] && [ -f "$RUNNING" ]; then
    python3 - "$RUNNING" "$REQUEST_DIR/runs" "$exit_code" <<'PY'
import datetime
import json
import os
import re
import sys
import uuid

running, runs, exit_code = sys.argv[1:]
try:
    with open(running, encoding="utf-8") as stream:
        request = json.load(stream)
    if not isinstance(request, dict):
        raise ValueError("request is not an object")
except (OSError, json.JSONDecodeError, ValueError) as error:
    request = {"detail": f"invalid durable request: {type(error).__name__}"}
request_id = str(request.get("request_id", ""))
if not re.fullmatch(r"[a-f0-9]{32}", request_id):
    request_id = uuid.uuid4().hex
request.update({
    "request_id": request_id,
    "status": "FAILED",
    "completed_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "exit_code": int(exit_code),
    "failure_phase": "REQUEST_DISPATCH",
})
path = os.path.join(runs, request_id + ".json")
existing = None
try:
    with open(path, encoding="utf-8") as stream:
        existing = json.load(stream)
except (FileNotFoundError, json.JSONDecodeError, OSError):
    pass
if not isinstance(existing, dict) or existing.get("status") not in {"COMPLETED", "FAILED", "STALE_RECOVERED"}:
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as stream:
        json.dump(request, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
    os.replace(temporary, path)
PY
    rm -f -- "$RUNNING"
  fi
  [ -z "$payload_file" ] || rm -f -- "$payload_file"
  [ -z "$result_file" ] || rm -f -- "$result_file"
  exit "$exit_code"
}
trap finalize_failed_request EXIT

[ -f "$PENDING" ] || exit 0
mv -- "$PENDING" "$RUNNING"

request_metadata="$(python3 - "$RUNNING" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
request_id = str(value.get("request_id", ""))
if not re.fullmatch(r"[a-f0-9]{32}", request_id):
    raise SystemExit("invalid research request id")
operation = str(value.get("operation", ""))
if operation not in {"RESEARCH_HEARTBEAT", "REGISTER_CANDIDATE_BUNDLE"}:
    raise SystemExit("unsupported research request operation")
print(f"{request_id}\t{operation}")
PY
)"
IFS=$'\t' read -r request_id operation <<<"$request_metadata"
status_file="$REQUEST_DIR/runs/$request_id.json"
log_file="$REQUEST_DIR/runs/$request_id.log"

if [ "$operation" = "REGISTER_CANDIDATE_BUNDLE" ]; then
  payload_file="$RUNTIME_DIR/candidate-$request_id.json"
  result_file="$RUNTIME_DIR/result-$request_id.json"
  python3 - "$RUNNING" "$payload_file" <<'PY'
import hashlib
import json
import os
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    request = json.load(stream)
payload = request.get("payload")
if not isinstance(payload, dict):
    raise SystemExit("candidate request payload must be an object")
encoded = json.dumps(
    payload,
    ensure_ascii=False,
    allow_nan=False,
    separators=(",", ":"),
    sort_keys=True,
).encode("utf-8")
expected = str(request.get("payload_sha256", ""))
if not re.fullmatch(r"[a-f0-9]{64}", expected):
    raise SystemExit("candidate request payload hash is invalid")
if hashlib.sha256(encoded).hexdigest() != expected:
    raise SystemExit("candidate request payload hash mismatch")
temporary = sys.argv[2] + ".tmp"
with open(temporary, "w", encoding="utf-8") as stream:
    json.dump(payload, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")
os.chmod(temporary, 0o600)
os.replace(temporary, sys.argv[2])
PY
fi

python3 - "$RUNNING" "$status_file" <<'PY'
import datetime
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
value["status"] = "RUNNING"
value["started_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
temporary = sys.argv[2] + ".tmp"
with open(temporary, "w", encoding="utf-8") as stream:
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")
os.replace(temporary, sys.argv[2])
PY

set +e
if [ "$operation" = "RESEARCH_HEARTBEAT" ]; then
  APP_DIR="$APP_DIR" "$APP_DIR/scripts/research-worker/run-heartbeat.sh" >"$log_file" 2>&1
else
  python3 -m research_pipeline \
    --state-dir "${AGORA_RESEARCH_STATE_DIR:-/var/lib/agora-research/state}" \
    --policy "${AGORA_RESEARCH_POLICY_FILE:-$APP_DIR/research_pipeline/policy.v3.json}" \
    register-candidate-bundle "$payload_file" >"$result_file" 2>"$log_file"
fi
exit_code=$?
set -e

if [ "$operation" = "REGISTER_CANDIDATE_BUNDLE" ] && [ "$exit_code" = "0" ]; then
  if ! python3 - "$result_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
if not isinstance(value, dict):
    raise SystemExit("candidate registration result must be an object")
PY
  then
    exit_code=65
  fi
fi

python3 - "$RUNNING" "$status_file" "$INBOX_DIR/latest.json" "$result_file" "$operation" "$exit_code" <<'PY'
import datetime
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    request = json.load(stream)
heartbeat = None
result = None
operation = sys.argv[5]
if operation == "RESEARCH_HEARTBEAT":
    try:
        with open(sys.argv[3], encoding="utf-8") as stream:
            heartbeat = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass
elif operation == "REGISTER_CANDIDATE_BUNDLE":
    try:
        with open(sys.argv[4], encoding="utf-8") as stream:
            result = json.load(stream)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass
exit_code = int(sys.argv[6])
request.update({
    "status": "COMPLETED" if exit_code == 0 else "FAILED",
    "completed_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    "exit_code": exit_code,
})
if operation == "RESEARCH_HEARTBEAT":
    request["heartbeat"] = heartbeat
else:
    request["result"] = result
temporary = sys.argv[2] + ".tmp"
with open(temporary, "w", encoding="utf-8") as stream:
    json.dump(request, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")
os.replace(temporary, sys.argv[2])
PY

rm -f -- "$RUNNING"
[ -z "$payload_file" ] || rm -f -- "$payload_file"
[ -z "$result_file" ] || rm -f -- "$result_file"
finalized=1
exit "$exit_code"
