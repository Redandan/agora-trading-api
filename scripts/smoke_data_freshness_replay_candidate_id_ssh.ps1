param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 3,
    [int]$Limit = 100,
    [switch]$RequireObserved
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) {
    throw "ReviewDays must be between 1 and 30."
}

if ($Limit -lt 1 -or $Limit -gt 500) {
    throw "Limit must be between 1 and 500."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$requireObservedValue = if ($RequireObserved) { "1" } else { "0" }

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
REVIEW_DAYS='__REVIEW_DAYS__'
LIMIT='__LIMIT__'
REQUIRE_OBSERVED='__REQUIRE_OBSERVED__'

fail() {
  echo "[data-freshness-replay-candidate-id] FAIL: $*" >&2
  exit 1
}

read_env_key() {
  local key="$1"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
  printf '%s\n' "${line#*=}" | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

command -v mysql >/dev/null 2>&1 || fail "mysql is not available on server"

SPRING_DATASOURCE_URL="$(read_env_key SPRING_DATASOURCE_URL)"
SPRING_DATASOURCE_USERNAME="$(read_env_key SPRING_DATASOURCE_USERNAME)"
SPRING_DATASOURCE_PASSWORD="$(read_env_key SPRING_DATASOURCE_PASSWORD)"

case "$SPRING_DATASOURCE_URL" in
  jdbc:mysql://*) ;;
  *) fail "SPRING_DATASOURCE_URL must be a jdbc:mysql URL" ;;
esac

jdbc_without_prefix="${SPRING_DATASOURCE_URL#jdbc:mysql://}"
jdbc_without_query="${jdbc_without_prefix%%\?*}"
host_port="${jdbc_without_query%%/*}"
database="${jdbc_without_query#*/}"

[ -n "$database" ] && [ "$database" != "$jdbc_without_query" ] || fail "database name missing in SPRING_DATASOURCE_URL"
if [ "$database" != "agora_market" ]; then
  fail "refusing to query unexpected database: $database"
fi

if printf '%s\n' "$host_port" | grep -q ':'; then
  host="${host_port%%:*}"
  port="${host_port##*:}"
else
  host="$host_port"
  port="3306"
fi

case "$port" in
  ''|*[!0-9]*) fail "database port is invalid in SPRING_DATASOURCE_URL: $port" ;;
esac

export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
export SYMBOL REVIEW_DAYS LIMIT REQUIRE_OBSERVED MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import os
import re
import subprocess
import sys

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
limit = int(os.environ["LIMIT"])
require_observed = os.environ["REQUIRE_OBSERVED"] == "1"

sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.replayCandidateId')), '') AS replay_candidate_id,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.replayCandidateVersion')), '') AS replay_candidate_version,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.replayCandidateStatus')), '') AS replay_candidate_status,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.orderSent')), '') AS order_sent,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.intentCreated')), '') AS intent_created,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.ocoPlanCreated')), '') AS oco_plan_created
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.blocker = 'DataFreshnessGuard'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time DESC
LIMIT {limit}
"""

cmd = [
    "mysql",
    "--batch",
    "--raw",
    "--skip-column-names",
    "-h", os.environ["MYSQL_HOST"],
    "-P", os.environ["MYSQL_PORT"],
    "-u", os.environ["MYSQL_USER"],
    os.environ["MYSQL_DATABASE"],
    "-e", sql,
]
try:
    proc = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
except subprocess.CalledProcessError as exc:
    print(exc.stderr, file=sys.stderr)
    sys.exit(exc.returncode or 1)

fields = [
    "auditId", "eventTime", "strategyId", "intervalCode",
    "replayCandidateId", "replayCandidateVersion", "replayCandidateStatus",
    "orderSent", "intentCreated", "ocoPlanCreated",
]
rows = [dict(zip(fields, row)) for row in csv.reader(proc.stdout.splitlines(), delimiter="\t")]

def has_replay_id(row):
    return re.fullmatch(r"dfsr1_[0-9a-f]{24}", row.get("replayCandidateId", "")) is not None

def is_false(row, field):
    return str(row.get(field, "")).lower() == "false"

total = len(rows)
with_replay_id = [row for row in rows if has_replay_id(row)]
missing_replay_id = total - len(with_replay_id)
version_ok = [row for row in rows if row.get("replayCandidateVersion") == "dfsr1"]
status_ok = [row for row in rows if row.get("replayCandidateStatus") == "L0_DATA_FRESHNESS_BLOCKED_NO_CANDIDATE_PLAN"]
no_order = [row for row in rows if is_false(row, "orderSent")]
no_intent = [row for row in rows if is_false(row, "intentCreated")]
no_oco = [row for row in rows if is_false(row, "ocoPlanCreated")]

if total == 0:
    status = "PENDING_NO_NEW_DATAFRESHNESS_ROWS"
elif len(with_replay_id) == total and len(version_ok) == total and len(status_ok) == total and len(no_order) == total and len(no_intent) == total and len(no_oco) == total:
    status = "REPLAY_CANDIDATE_ID_EVIDENCE_OK"
else:
    status = "REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE"

print("[data-freshness-replay-candidate-id] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} limit={limit} requireObserved={str(require_observed).lower()}")
print("")
print("Replay Candidate Id Coverage:")
print(f"  data_freshness_rows={total}")
print(f"  replay_candidate_id_rows={len(with_replay_id)}")
print(f"  replay_candidate_id_missing_rows={missing_replay_id}")
print(f"  replay_candidate_version_rows={len(version_ok)}")
print(f"  replay_candidate_status_rows={len(status_ok)}")
print(f"  order_sent_false_rows={len(no_order)}")
print(f"  intent_created_false_rows={len(no_intent)}")
print(f"  oco_plan_created_false_rows={len(no_oco)}")
print(f"  replay_candidate_id_status={status}")
print("")
print("Examples:")
for row in rows[:5]:
    print(
        "  - "
        f"auditId={row.get('auditId')} time={row.get('eventTime')} strategy={row.get('strategyId')} "
        f"interval={row.get('intervalCode')} replayCandidateId={row.get('replayCandidateId') or 'MISSING'} "
        f"version={row.get('replayCandidateVersion') or 'MISSING'} status={row.get('replayCandidateStatus') or 'MISSING'} "
        f"orderSent={row.get('orderSent') or 'MISSING'} intentCreated={row.get('intentCreated') or 'MISSING'} "
        f"ocoPlanCreated={row.get('ocoPlanCreated') or 'MISSING'}"
    )
print("")
print("Conclusion:")
print(f"  data_freshness_replay_candidate_id_recommendation={status}")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")

if require_observed and status != "REPLAY_CANDIDATE_ID_EVIDENCE_OK":
    print(f"FAIL: RequireObserved needs REPLAY_CANDIDATE_ID_EVIDENCE_OK, got {status}", file=sys.stderr)
    sys.exit(1)

if status == "REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE":
    sys.exit(1)

print("")
print("[data-freshness-replay-candidate-id] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LIMIT__", [string]$Limit).
    Replace("__REQUIRE_OBSERVED__", $requireObservedValue)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "DataFreshness replay candidate id smoke failed with exit code $LASTEXITCODE"
}
