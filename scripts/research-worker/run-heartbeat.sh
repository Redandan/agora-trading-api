#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/agora-research-worker/current}"
STATE_DIR="${AGORA_RESEARCH_STATE_DIR:-/var/lib/agora-research/state}"
INBOX_DIR="${AGORA_RESEARCH_INBOX_DIR:-/var/lib/agora-research/inbox}"
POLICY_FILE="${AGORA_RESEARCH_POLICY_FILE:-$APP_DIR/research_pipeline/policy.v3.json}"
PYTHON_BIN="${PYTHON_BIN:-/usr/bin/python3}"
LOCK_FILE="${AGORA_RESEARCH_HEARTBEAT_LOCK:-/run/agora-research/heartbeat.lock}"

fail() {
  echo "[research-heartbeat] FAIL: $*" >&2
  exit 1
}

[ -d "$APP_DIR/research_pipeline" ] || fail "pipeline missing: $APP_DIR/research_pipeline"
[ -f "$POLICY_FILE" ] || fail "policy missing: $POLICY_FILE"
[ -x "$PYTHON_BIN" ] || fail "python is not executable: $PYTHON_BIN"

umask 077
mkdir -p "$STATE_DIR" "$INBOX_DIR" "$(dirname "$LOCK_FILE")"

exec 9>"$LOCK_FILE"
flock -n 9 || fail "another server heartbeat owns the worker lock"

stamp="$(date -u +%Y%m%dT%H%M%S%NZ)"
payload_tmp="$INBOX_DIR/.heartbeat-$stamp-$$.json.tmp"
stderr_tmp="$INBOX_DIR/.heartbeat-$stamp-$$.stderr.tmp"
payload="$INBOX_DIR/heartbeat-$stamp.json"
stderr_log="$INBOX_DIR/heartbeat-$stamp.stderr.log"
latest_tmp="$INBOX_DIR/.latest-$$.json.tmp"

cleanup() {
  rm -f -- "$payload_tmp" "$stderr_tmp" "$latest_tmp"
}
trap cleanup EXIT

set +e
(
  cd "$APP_DIR"
  "$PYTHON_BIN" -m research_pipeline \
    --state-dir "$STATE_DIR" \
    --policy "$POLICY_FILE" \
    heartbeat
) >"$payload_tmp" 2>"$stderr_tmp"
exit_code=$?
set -e

if ! "$PYTHON_BIN" -m json.tool "$payload_tmp" >/dev/null 2>&1; then
  mv -- "$payload_tmp" "$payload.invalid"
  if [ -s "$stderr_tmp" ]; then
    mv -- "$stderr_tmp" "$stderr_log"
  fi
  fail "heartbeat did not produce a valid JSON envelope (exit=$exit_code)"
fi

mv -- "$payload_tmp" "$payload"
if [ -s "$stderr_tmp" ]; then
  mv -- "$stderr_tmp" "$stderr_log"
else
  rm -f -- "$stderr_tmp"
fi
cp -- "$payload" "$latest_tmp"
mv -- "$latest_tmp" "$INBOX_DIR/latest.json"

echo "[research-heartbeat] sealed=$payload exit=$exit_code"
exit "$exit_code"
