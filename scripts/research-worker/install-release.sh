#!/usr/bin/env bash
set -euo pipefail

STAGING_DIR="${STAGING_DIR:?STAGING_DIR is required}"
RELEASE_ID="${RELEASE_ID:?RELEASE_ID is required}"
WORKER_ROOT="${WORKER_ROOT:-/opt/agora-research-worker}"
DATA_ROOT="${DATA_ROOT:-/var/lib/agora-research}"
WORKER_USER="${WORKER_USER:-agora-research}"
WORKER_GROUP="${WORKER_GROUP:-agora-research}"

fail() {
  echo "[research-worker-install] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[research-worker-install] OK: $*"
}

case "$STAGING_DIR" in
  /home/ubuntu/.cache/agora-research-deploy/*) ;;
  *) fail "unsupported staging path: $STAGING_DIR" ;;
esac
case "$RELEASE_ID" in
  *[!A-Za-z0-9._-]*|'') fail "invalid release id: $RELEASE_ID" ;;
esac
[ "$WORKER_ROOT" = "/opt/agora-research-worker" ] || fail "unexpected worker root"
[ "$DATA_ROOT" = "/var/lib/agora-research" ] || fail "unexpected data root"

SOURCE_DIR="$STAGING_DIR/source"
STATE_DIR="$STAGING_DIR/state"
SOURCE_MANIFEST="$STAGING_DIR/source.sha256"
STATE_MANIFEST="$STAGING_DIR/state.sha256"
RELEASE_DIR="$WORKER_ROOT/releases/$RELEASE_ID"
INCOMING_STATE="$DATA_ROOT/state-$RELEASE_ID.incoming"

[ -d "$SOURCE_DIR/research_pipeline" ] || fail "source pipeline missing"
[ -f "$SOURCE_DIR/research_pipeline/policy.v2.json" ] || fail "V2 policy missing"
[ -f "$SOURCE_DIR/scripts/research-worker/run-heartbeat.sh" ] || fail "heartbeat launcher is missing"
[ -d "$STATE_DIR" ] || fail "migrated state directory missing"
[ ! -e "$STATE_DIR/pipeline.lock" ] || fail "state snapshot contains pipeline.lock"
[ -s "$SOURCE_MANIFEST" ] || fail "source manifest missing"
[ -s "$STATE_MANIFEST" ] || fail "state manifest missing"

(cd "$SOURCE_DIR" && sha256sum -c "$SOURCE_MANIFEST" >/dev/null) \
  || fail "source manifest verification failed"
(cd "$STATE_DIR" && sha256sum -c "$STATE_MANIFEST" >/dev/null) \
  || fail "state manifest verification failed"
ok "uploaded source and state manifests verified"

if ! getent group "$WORKER_GROUP" >/dev/null; then
  sudo groupadd --system "$WORKER_GROUP"
fi
if ! id "$WORKER_USER" >/dev/null 2>&1; then
  sudo useradd \
    --system \
    --gid "$WORKER_GROUP" \
    --home-dir "$DATA_ROOT" \
    --no-create-home \
    --shell /usr/sbin/nologin \
    "$WORKER_USER"
fi

sudo install -d -o root -g root -m 0755 "$WORKER_ROOT" "$WORKER_ROOT/releases"
[ ! -e "$RELEASE_DIR" ] || fail "release already exists: $RELEASE_DIR"
sudo install -d -o root -g root -m 0755 "$RELEASE_DIR"
sudo cp -a "$SOURCE_DIR/." "$RELEASE_DIR/"
sudo chown -R root:root "$RELEASE_DIR"
sudo find "$RELEASE_DIR" -type d -exec chmod 0755 {} +
sudo find "$RELEASE_DIR" -type f -exec chmod 0644 {} +
sudo chmod 0755 "$RELEASE_DIR/scripts/research-worker/"*.sh

sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 "$DATA_ROOT"
sudo install -d -o root -g root -m 0700 "$DATA_ROOT/migrations"
sudo install -o root -g root -m 0600 "$STAGING_DIR/state.tar.gz" \
  "$DATA_ROOT/migrations/$RELEASE_ID-state.tar.gz"
sudo install -o root -g root -m 0600 "$STATE_MANIFEST" \
  "$DATA_ROOT/migrations/$RELEASE_ID-state.sha256"
[ ! -e "$DATA_ROOT/state" ] || fail "canonical state already exists; refusing overwrite"
[ ! -e "$INCOMING_STATE" ] || fail "incoming state already exists: $INCOMING_STATE"
sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 "$INCOMING_STATE"
sudo cp -a "$STATE_DIR/." "$INCOMING_STATE/"
sudo chown -R "$WORKER_USER:$WORKER_GROUP" "$INCOMING_STATE"
sudo find "$INCOMING_STATE" -type d -exec chmod 0700 {} +
sudo find "$INCOMING_STATE" -type f -exec chmod 0600 {} +

authority_tmp="$STAGING_DIR/authority.json"
printf '%s\n' \
  '{' \
  '  "schema_version": "1",' \
  '  "mode": "SERVER_CANONICAL",' \
  '  "canonical_state": "/var/lib/agora-research/state"' \
  '}' >"$authority_tmp"
sudo install -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0600 \
  "$authority_tmp" "$INCOMING_STATE/authority.json"
sudo mv "$INCOMING_STATE" "$DATA_ROOT/state"
sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 "$DATA_ROOT/inbox"

current_link="$WORKER_ROOT/current"
next_link="$WORKER_ROOT/.current-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$next_link"
sudo mv -Tf "$next_link" "$current_link"

sudo install -o root -g root -m 0644 \
  "$RELEASE_DIR/scripts/research-worker/agora-research-heartbeat.service" \
  /etc/systemd/system/agora-research-heartbeat.service
sudo install -o root -g root -m 0644 \
  "$RELEASE_DIR/scripts/research-worker/agora-research-heartbeat.timer" \
  /etc/systemd/system/agora-research-heartbeat.timer
sudo systemctl daemon-reload
sudo systemctl disable --now agora-research-heartbeat.timer >/dev/null 2>&1 || true
sudo systemctl stop agora-research-heartbeat.service >/dev/null 2>&1 || true
sudo systemd-analyze verify \
  /etc/systemd/system/agora-research-heartbeat.service \
  /etc/systemd/system/agora-research-heartbeat.timer

ok "release installed: $RELEASE_DIR"
ok "canonical state installed: $DATA_ROOT/state"
ok "pre-cutover state archive preserved under $DATA_ROOT/migrations"
ok "timer installed but intentionally disabled for single-writer cutover"
