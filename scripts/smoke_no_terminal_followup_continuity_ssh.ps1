param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$FollowupHours = 6,
    [int]$ExtendedFollowupHours = 48,
    [int]$Limit = 20,
    [int]$MaxCandidateRows = 1000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) { throw "ReviewDays must be between 1 and 30." }
if ($FollowupHours -lt 1 -or $FollowupHours -gt 48) { throw "FollowupHours must be between 1 and 48." }
if ($ExtendedFollowupHours -lt $FollowupHours -or $ExtendedFollowupHours -gt 168) { throw "ExtendedFollowupHours must be between FollowupHours and 168." }
if ($Limit -lt 1 -or $Limit -gt 100) { throw "Limit must be between 1 and 100." }
if ($MaxCandidateRows -lt 1 -or $MaxCandidateRows -gt 2000) { throw "MaxCandidateRows must be between 1 and 2000." }

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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
REVIEW_DAYS='__REVIEW_DAYS__'
FOLLOWUP_HOURS='__FOLLOWUP_HOURS__'
EXTENDED_FOLLOWUP_HOURS='__EXTENDED_FOLLOWUP_HOURS__'
LIMIT='__LIMIT__'
MAX_CANDIDATE_ROWS='__MAX_CANDIDATE_ROWS__'

fail() {
  echo "[no-terminal-followup-continuity] FAIL: $*" >&2
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
export SYMBOL REVIEW_DAYS FOLLOWUP_HOURS EXTENDED_FOLLOWUP_HOURS LIMIT MAX_CANDIDATE_ROWS MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import datetime as dt
import json
import os
import subprocess
import sys
from collections import Counter

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
followup_hours = int(os.environ["FOLLOWUP_HOURS"])
extended_followup_hours = int(os.environ["EXTENDED_FOLLOWUP_HOURS"])
limit = int(os.environ["LIMIT"])
max_candidate_rows = int(os.environ["MAX_CANDIDATE_ROWS"])
terminal_events = {"SIGNAL_BUY", "FILTER_BLOCK", "ENTRY_SKIP", "AUTOTRADE_OK", "AUTOTRADE_FAIL"}

def run_query(sql):
    cmd = [
        "mysql", "--batch", "--raw", "--skip-column-names",
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
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def parse_time(value):
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%S")

def same_key(a, b):
    return a["strategy_id"] == b["strategy_id"] and a["interval_code"] == b["interval_code"]

def bucket_terminal(row):
    if row["event_type"] == "FILTER_BLOCK":
        return "FILTER_BLOCK:" + (row["blocker"] or "UNKNOWN")
    if row["event_type"] == "ENTRY_SKIP":
        return "ENTRY_SKIP:" + (row["blocker"] or "UNKNOWN")
    return row["event_type"] or "UNKNOWN"

symbol_sql = esc(symbol)
candidate_condition = """
(
  a.event_type = 'SIGNAL_BUY'
  OR (
    a.event_type = 'SIGNAL_EVAL'
    AND (
      UPPER(COALESCE(a.outcome,'')) LIKE '%BUY%'
      OR UPPER(COALESCE(a.reason,'')) LIKE '%BUY%'
      OR UPPER(COALESCE(a.reason,'')) LIKE '%LONG%'
      OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side')), '')) IN ('LONG','BUY')
      OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.signal')), '')) IN ('LONG','BUY')
    )
  )
)
"""

candidate_sql = f"""
SELECT a.id, DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s'), COALESCE(a.strategy_id, -1),
       COALESCE(a.interval_code, 'N/A'), a.event_type, COALESCE(a.outcome, ''),
       COALESCE(a.blocker, ''), COALESCE(a.reason, '')
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
  AND {candidate_condition}
ORDER BY a.event_time DESC
LIMIT {max_candidate_rows}
"""

audit_sql = f"""
SELECT a.id, DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s'), COALESCE(a.strategy_id, -1),
       COALESCE(a.interval_code, 'N/A'), a.event_type, COALESCE(a.outcome, ''),
       COALESCE(a.blocker, ''), COALESCE(a.reason, '')
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time ASC
"""

now_rows = run_query("SELECT DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%dT%H:%i:%s')")
now_utc = parse_time(now_rows[0][0])

fields = ["id", "event_time", "strategy_id", "interval_code", "event_type", "outcome", "blocker", "reason"]
candidates = [dict(zip(fields, row)) for row in run_query(candidate_sql)]
audits = [dict(zip(fields, row)) for row in run_query(audit_sql)]
for row in candidates + audits:
    row["dt"] = parse_time(row["event_time"])

primary_window = dt.timedelta(hours=followup_hours)
extended_window = dt.timedelta(hours=extended_followup_hours)
terminal_rows = [r for r in audits if r["event_type"] in terminal_events]

no_terminal = []
for candidate in candidates:
    if candidate["event_type"] == "SIGNAL_BUY":
        continue
    matched = None
    for row in terminal_rows:
        if row["dt"] <= candidate["dt"]:
            continue
        if row["dt"] > candidate["dt"] + primary_window:
            break
        if same_key(candidate, row):
            matched = row
            break
    if matched is None:
        no_terminal.append(candidate)

classifications = Counter()
strategy_interval = Counter()
examples = []
terminal_after_primary = Counter()

for candidate in no_terminal:
    primary_mature = now_utc >= candidate["dt"] + primary_window
    extended_mature = now_utc >= candidate["dt"] + extended_window
    same_key_late_terminal = None
    same_strategy_other_interval = None
    nearby_terminal = None
    same_key_nonterminal = None
    for row in audits:
        if row["dt"] <= candidate["dt"] or row["dt"] > candidate["dt"] + extended_window:
            continue
        if same_key(candidate, row) and row["event_type"] in terminal_events and row["dt"] > candidate["dt"] + primary_window:
            same_key_late_terminal = row
            break
        if same_key(candidate, row) and row["event_type"] not in terminal_events and same_key_nonterminal is None:
            same_key_nonterminal = row
        if candidate["strategy_id"] == row["strategy_id"] and candidate["interval_code"] != row["interval_code"] and row["event_type"] in terminal_events and same_strategy_other_interval is None:
            same_strategy_other_interval = row
        if row["event_type"] in terminal_events and nearby_terminal is None:
            nearby_terminal = row
    if not primary_mature:
        cls = "PENDING_PRIMARY_FOLLOWUP_WINDOW"
        row = None
    elif same_key_late_terminal is not None:
        cls = "TERMINAL_AFTER_PRIMARY_WINDOW"
        row = same_key_late_terminal
        terminal_after_primary[bucket_terminal(row)] += 1
    elif not extended_mature:
        cls = "PENDING_EXTENDED_FOLLOWUP_WINDOW"
        row = None
    elif same_strategy_other_interval is not None:
        cls = "SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL"
        row = same_strategy_other_interval
    elif nearby_terminal is not None:
        cls = "OTHER_TERMINAL_NEARBY"
        row = nearby_terminal
    elif same_key_nonterminal is not None:
        cls = "NON_TERMINAL_SAME_KEY_CONTINUED"
        row = same_key_nonterminal
    else:
        cls = "NO_FOLLOWUP_WITHIN_EXTENDED_WINDOW"
        row = None
    classifications[cls] += 1
    strategy_interval[f"{candidate['strategy_id']}:{candidate['interval_code']}"] += 1
    if len(examples) < limit:
        item = {
            "candidateAuditId": int(candidate["id"]),
            "time": candidate["event_time"],
            "strategy": candidate["strategy_id"],
            "interval": candidate["interval_code"],
            "classification": cls,
            "reason": candidate["reason"][:160],
        }
        if row is not None:
            item.update({
                "followupAuditId": int(row["id"]),
                "followupTime": row["event_time"],
                "followupEvent": row["event_type"],
                "followupBlocker": row["blocker"] or "",
                "followupMinutes": int((row["dt"] - candidate["dt"]).total_seconds() // 60),
            })
        examples.append(item)

status = "READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE"
next_action = "Use row-level classifications to decide whether to fix terminal audit continuity, widen review matching, or route specific strategies to EntryDedup/ShadowExecutionIntent review; do not relax live policy."
if not no_terminal:
    status = "NO_NO_TERMINAL_ROWS_IN_REVIEW_WINDOW"
    next_action = "No no-terminal rows in this window; rerun after new BUY-like flow appears."

print("[no-terminal-followup-continuity] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} followupHours={followup_hours} extendedFollowupHours={extended_followup_hours} limit={limit} maxCandidateRows={max_candidate_rows}")
print(f"no_terminal_continuity_review_status={status}")
print(f"buy_like_candidate_rows_sampled={len(candidates)}")
print(f"no_terminal_followup_rows={len(no_terminal)}")
print("no_terminal_continuity_classification=" + json.dumps([{"classification": k, "rows": v} for k, v in classifications.most_common()], separators=(",", ":")))
print("no_terminal_strategy_interval_distribution=" + json.dumps([{"strategyInterval": k, "rows": v} for k, v in strategy_interval.most_common(limit)], separators=(",", ":")))
print("terminal_after_primary_window_distribution=" + json.dumps([{"terminal": k, "rows": v} for k, v in terminal_after_primary.most_common(limit)], separators=(",", ":")))
print("no_terminal_continuity_examples=" + json.dumps(examples, separators=(",", ":")))
print(f"no_terminal_continuity_next_action={next_action}")
print("notAuthorization=read-only no-terminal continuity evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("[no-terminal-followup-continuity] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__FOLLOWUP_HOURS__", [string]$FollowupHours).
    Replace("__EXTENDED_FOLLOWUP_HOURS__", [string]$ExtendedFollowupHours).
    Replace("__LIMIT__", [string]$Limit).
    Replace("__MAX_CANDIDATE_ROWS__", [string]$MaxCandidateRows)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "No-terminal follow-up continuity smoke failed with exit code $LASTEXITCODE"
}
