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

fail() { echo "[research-worker-upgrade] FAIL: $*" >&2; exit 1; }
ok() { echo "[research-worker-upgrade] OK: $*"; }

case "$STAGING_DIR" in /home/ubuntu/.cache/agora-research-upgrade/*) ;; *) fail "unsupported staging path" ;; esac
case "$RELEASE_ID" in *[!A-Za-z0-9._-]*|'') fail "invalid release id" ;; esac
case "$SOURCE_GIT_COMMIT" in *[!0-9a-f]*|'') fail "invalid source Git commit" ;; esac
[ "${#SOURCE_GIT_COMMIT}" = "40" ] || fail "source Git commit must be 40 characters"
case "$SOURCE_GIT_BRANCH" in *[!A-Za-z0-9._/-]*|'') fail "invalid source Git branch" ;; esac
case "$SOURCE_GIT_DIRTY" in true|false) ;; *) fail "source Git dirty flag must be true or false" ;; esac
[ "$WORKER_ROOT" = "/opt/agora-research-worker" ] || fail "unexpected worker root"
[ "$DATA_ROOT" = "/var/lib/agora-research" ] || fail "unexpected data root"

if ! getent group "$EVIDENCE_GROUP" >/dev/null; then
  sudo groupadd --system "$EVIDENCE_GROUP"
fi
if ! id -u "$SOURCE_USER" >/dev/null 2>&1; then
  sudo useradd --system --no-create-home --home-dir /nonexistent \
    --shell /usr/sbin/nologin --gid "$EVIDENCE_GROUP" "$SOURCE_USER"
fi
sudo usermod -a -G "$EVIDENCE_GROUP" "$WORKER_USER"

SOURCE_DIR="$STAGING_DIR/source"
SOURCE_MANIFEST="$STAGING_DIR/source.sha256"
RELEASE_DIR="$WORKER_ROOT/releases/$RELEASE_ID"
[ -d "$SOURCE_DIR/research_pipeline" ] || fail "research pipeline missing"
[ -d "$SOURCE_DIR/research_mcp" ] || fail "Research MCP missing"
[ -d "$SOURCE_DIR/research_source" ] || fail "forward evidence source missing"
[ -f "$SOURCE_DIR/research_pipeline/policy.v3.json" ] || fail "V3 policy missing"
[ -f "$SOURCE_DIR/scripts/research-worker/research-mcp-requirements.lock" ] || fail "MCP lock missing"
[ -s "$SOURCE_MANIFEST" ] || fail "source manifest missing"
(cd "$SOURCE_DIR" && sha256sum -c "$SOURCE_MANIFEST" >/dev/null) || fail "source manifest verification failed"
sudo test -f "$DATA_ROOT/state/authority.json" || fail "canonical state missing"

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

sudo install -d -o "$WORKER_USER" -g "$WORKER_GROUP" -m 0700 \
  "$DATA_ROOT/auth" "$DATA_ROOT/requests" "$DATA_ROOT/requests/runs"
sudo install -d -o root -g "$EVIDENCE_GROUP" -m 2770 \
  "$DATA_ROOT/source-requests" "$DATA_ROOT/source-requests/runs" \
  "$DATA_ROOT/source-drop" "$DATA_ROOT/source-drop/raw" "$DATA_ROOT/source-drop/runs"
if [ ! -f "$DATA_ROOT/auth/enrollment-consumed" ] && [ ! -f "$DATA_ROOT/auth/enrollment-code.hash" ]; then
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

next_link="$WORKER_ROOT/.current-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$next_link"
sudo mv -Tf "$next_link" "$WORKER_ROOT/current"

for unit in agora-research-heartbeat.service agora-research-heartbeat.timer \
  agora-research-dispatch.service agora-research-dispatch.path agora-research-mcp.service \
  agora-research-source.service agora-research-source.path \
  agora-research-evidence-ingest.service agora-research-evidence-ingest.path; do
  sudo install -o root -g root -m 0644 \
    "$RELEASE_DIR/scripts/research-worker/$unit" "/etc/systemd/system/$unit"
done
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
  /etc/systemd/system/agora-research-evidence-ingest.path
sudo systemctl disable --now agora-research-heartbeat.timer >/dev/null 2>&1 || true
sudo systemctl enable --now agora-research-mcp.service agora-research-dispatch.path \
  agora-research-source.path agora-research-evidence-ingest.path >/dev/null
sudo systemctl restart agora-research-mcp.service
SNIPPET_SOURCE="$RELEASE_DIR/scripts/research-worker/nginx-research-mcp.conf" \
  bash "$RELEASE_DIR/scripts/research-worker/install-nginx-route.sh"

ok "release installed: $RELEASE_ID"
ok "OAuth Research MCP active on loopback"
ok "main, public-source, and network-denied ingest paths active; server timer disabled"
