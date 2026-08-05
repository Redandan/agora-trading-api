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

fail() {
  echo "[research-worker-verify] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[research-worker-verify] OK: $*"
}

[ -L "$WORKER_ROOT/current" ] || fail "current release symlink missing"
current="$(readlink -f "$WORKER_ROOT/current")"
case "$current" in
  "$WORKER_ROOT"/releases/*) ;;
  *) fail "current release escapes worker root: $current" ;;
esac
[ -f "$current/research_pipeline/policy.v3.json" ] || fail "V3 policy missing"
[ -d "$current/research_source" ] || fail "forward evidence source missing"
[ -s "$current/.release/source.sha256" ] || fail "release source manifest missing"
[ -s "$current/.release/provenance.json" ] || fail "release provenance missing"
(cd "$current" && sha256sum -c .release/source.sha256 >/dev/null) \
  || fail "installed release differs from its source manifest"
python3 - "$current/.release/provenance.json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    value = json.load(stream)
if value.get("schema_version") != "1":
    raise SystemExit("invalid release provenance schema")
if not re.fullmatch(r"[0-9a-f]{40}", str(value.get("source_git_commit", ""))):
    raise SystemExit("invalid release source commit")
if not re.fullmatch(r"[0-9a-f]{64}", str(value.get("source_manifest_sha256", ""))):
    raise SystemExit("invalid release source manifest hash")
PY
ok "release source manifest and provenance verified"
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
ok "public source identity cannot read Trading secrets or canonical state"

(
  cd "$current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    "$WORKER_ROOT/venv/bin/python" -m research_pipeline \
    --state-dir "$DATA_ROOT/state" \
    --policy "$current/research_pipeline/policy.v3.json" \
    status --json >/dev/null
)
ok "canonical research registry is readable"

(
  cd "$current"
  sudo -u "$WORKER_USER" env \
    PYTHONDONTWRITEBYTECODE=1 \
    "$WORKER_ROOT/venv/bin/python" -m unittest \
    research_pipeline.tests.test_evidence \
    research_mcp.tests.test_queue \
    research_mcp.tests.test_server_contract \
    research_source.tests.test_forward_source >/dev/null
)
ok "deployed research contracts verified in isolated temporary state"

systemctl cat agora-research-heartbeat.service >/dev/null
systemctl cat agora-research-heartbeat.timer >/dev/null
systemctl cat agora-research-dispatch.service >/dev/null
systemctl cat agora-research-dispatch.path >/dev/null
systemctl cat agora-research-mcp.service >/dev/null
systemctl cat agora-research-source.service >/dev/null
systemctl cat agora-research-source.path >/dev/null
systemctl cat agora-research-evidence-ingest.service >/dev/null
systemctl cat agora-research-evidence-ingest.path >/dev/null
systemd-analyze verify \
  /etc/systemd/system/agora-research-heartbeat.service \
  /etc/systemd/system/agora-research-heartbeat.timer \
  /etc/systemd/system/agora-research-dispatch.service \
  /etc/systemd/system/agora-research-dispatch.path \
  /etc/systemd/system/agora-research-mcp.service \
  /etc/systemd/system/agora-research-source.service \
  /etc/systemd/system/agora-research-source.path \
  /etc/systemd/system/agora-research-evidence-ingest.service \
  /etc/systemd/system/agora-research-evidence-ingest.path
ok "systemd units verified"

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
    cd "$current"
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
