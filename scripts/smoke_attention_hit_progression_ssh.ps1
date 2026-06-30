param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 7,
    [int]$FollowupHours = 6,
    [int]$Limit = 10,
    [int]$MaxAttentionRows = 500
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

if ($FollowupHours -lt 1 -or $FollowupHours -gt 48) {
    throw "FollowupHours must be between 1 and 48."
}

if ($Limit -lt 1 -or $Limit -gt 50) {
    throw "Limit must be between 1 and 50."
}

if ($MaxAttentionRows -lt 1 -or $MaxAttentionRows -gt 2000) {
    throw "MaxAttentionRows must be between 1 and 2000."
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

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
REVIEW_DAYS='__REVIEW_DAYS__'
FOLLOWUP_HOURS='__FOLLOWUP_HOURS__'
LIMIT='__LIMIT__'
MAX_ATTENTION_ROWS='__MAX_ATTENTION_ROWS__'

fail() {
  echo "[attention-hit-progression] FAIL: $*" >&2
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
export SYMBOL REVIEW_DAYS FOLLOWUP_HOURS LIMIT MAX_ATTENTION_ROWS MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import datetime as dt
import os
import subprocess
import sys
from collections import Counter

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
followup_hours = int(os.environ["FOLLOWUP_HOURS"])
limit = int(os.environ["LIMIT"])
max_attention_rows = int(os.environ["MAX_ATTENTION_ROWS"])

def run_query(sql):
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
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def parse_time(value):
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%S")

def bucket_followup(row):
    event_type = row.get("event_type", "")
    blocker = row.get("blocker", "") or "UNKNOWN"
    if event_type == "FILTER_BLOCK":
        return f"FILTER_BLOCK:{blocker}"
    if event_type == "ENTRY_SKIP":
        return f"ENTRY_SKIP:{blocker}"
    if event_type in ("AUTOTRADE_OK", "AUTOTRADE_FAIL"):
        return event_type
    if event_type == "SIGNAL_BUY":
        return "SIGNAL_BUY"
    return event_type or "UNKNOWN"

def same_key(att, row):
    return (
        att.get("strategy_id") == row.get("strategy_id")
        and att.get("interval_code") == row.get("interval_code")
    )

symbol_sql = esc(symbol)
terminal_events = "'SIGNAL_BUY','FILTER_BLOCK','ENTRY_SKIP','AUTOTRADE_OK','AUTOTRADE_FAIL'"

attention_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'ATTENTION_HIT'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time DESC
LIMIT {max_attention_rows}
"""

attention_count_sql = f"""
SELECT COUNT(*)
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'ATTENTION_HIT'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
"""

followup_sql = f"""
SELECT
  a.id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  COALESCE(a.strategy_id, -1) AS strategy_id,
  COALESCE(a.interval_code, 'N/A') AS interval_code,
  a.event_type,
  COALESCE(a.outcome, '') AS outcome,
  COALESCE(a.blocker, '') AS blocker,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type IN ({terminal_events})
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
ORDER BY a.event_time ASC
"""

strategy_sql = f"""
SELECT COALESCE(a.strategy_id, -1) AS strategy_id, COALESCE(a.interval_code, 'N/A') AS interval_code, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'ATTENTION_HIT'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
GROUP BY strategy_id, interval_code
ORDER BY cnt DESC, strategy_id ASC, interval_code ASC
LIMIT {limit}
"""

attention_rows = run_query(attention_sql)
attention_total_rows = run_query(attention_count_sql)
followup_rows = run_query(followup_sql)
strategy_rows = run_query(strategy_sql)

attention_fields = ["id", "event_time", "strategy_id", "interval_code", "reason"]
followup_fields = ["id", "event_time", "strategy_id", "interval_code", "event_type", "outcome", "blocker", "reason"]
attentions = [dict(zip(attention_fields, row)) for row in attention_rows]
followups = [dict(zip(followup_fields, row)) for row in followup_rows]

for row in attentions:
    row["dt"] = parse_time(row["event_time"])
for row in followups:
    row["dt"] = parse_time(row["event_time"])

window = dt.timedelta(hours=followup_hours)
classification = Counter()
event_classification = Counter()
strategy_scoped_classification = Counter()
strategy_scoped_event_classification = Counter()
examples = []
macro_watch_only_attention = 0
strategy_scoped_attention = 0

for att in attentions:
    matched = None
    is_macro_watch_only = att.get("strategy_id") == "-1" and att.get("interval_code") == "N/A"
    if is_macro_watch_only:
        macro_watch_only_attention += 1
    else:
        strategy_scoped_attention += 1
    for row in followups:
        if row["dt"] <= att["dt"]:
            continue
        if row["dt"] > att["dt"] + window:
            break
        if same_key(att, row):
            matched = row
            break
    if matched is None:
        classification["NO_TERMINAL_FOLLOWUP"] += 1
        event_classification["NO_TERMINAL_FOLLOWUP"] += 1
        if not is_macro_watch_only:
            strategy_scoped_classification["NO_TERMINAL_FOLLOWUP"] += 1
            strategy_scoped_event_classification["NO_TERMINAL_FOLLOWUP"] += 1
        if len(examples) < limit:
            examples.append((att, None, "NO_TERMINAL_FOLLOWUP"))
    else:
        bucket = bucket_followup(matched)
        classification[bucket] += 1
        event_classification[matched.get("event_type", "UNKNOWN") or "UNKNOWN"] += 1
        if not is_macro_watch_only:
            strategy_scoped_classification[bucket] += 1
            strategy_scoped_event_classification[matched.get("event_type", "UNKNOWN") or "UNKNOWN"] += 1
        if len(examples) < limit:
            examples.append((att, matched, bucket))

attention_total = int(attention_total_rows[0][0]) if attention_total_rows and attention_total_rows[0] else 0
sampled_attention = len(attentions)
no_followup = classification["NO_TERMINAL_FOLLOWUP"]
filter_followup = sum(v for k, v in classification.items() if k.startswith("FILTER_BLOCK:"))
entry_skip_followup = sum(v for k, v in classification.items() if k.startswith("ENTRY_SKIP:"))
autotrade_followup = classification["AUTOTRADE_OK"] + classification["AUTOTRADE_FAIL"]
signal_buy_followup = classification["SIGNAL_BUY"]
strategy_scoped_no_followup = strategy_scoped_classification["NO_TERMINAL_FOLLOWUP"]
strategy_scoped_filter_followup = sum(v for k, v in strategy_scoped_classification.items() if k.startswith("FILTER_BLOCK:"))
strategy_scoped_entry_skip_followup = sum(v for k, v in strategy_scoped_classification.items() if k.startswith("ENTRY_SKIP:"))
strategy_scoped_autotrade_followup = strategy_scoped_classification["AUTOTRADE_OK"] + strategy_scoped_classification["AUTOTRADE_FAIL"]
strategy_scoped_signal_buy_followup = strategy_scoped_classification["SIGNAL_BUY"]
strategy_scoped_terminal_followup = strategy_scoped_filter_followup + strategy_scoped_entry_skip_followup + strategy_scoped_autotrade_followup + strategy_scoped_signal_buy_followup

if attention_total == 0:
    recommendation = "NO_ATTENTION_HITS_IN_REVIEW_WINDOW"
elif no_followup * 100 >= max(1, sampled_attention) * 80:
    recommendation = "ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES"
elif entry_skip_followup >= max(filter_followup, autotrade_followup, signal_buy_followup, no_followup):
    recommendation = "ATTENTION_TO_ENTRY_SKIP_REVIEW"
elif filter_followup >= max(entry_skip_followup, autotrade_followup, signal_buy_followup, no_followup):
    recommendation = "ATTENTION_TO_FILTER_BLOCK_REVIEW"
elif signal_buy_followup + autotrade_followup > 0:
    recommendation = "ATTENTION_PIPELINE_HAS_TERMINAL_FOLLOWUP"
else:
    recommendation = "ATTENTION_PIPELINE_MIXED_REVIEW"

if strategy_scoped_attention == 0:
    strategy_scoped_recommendation = "NO_STRATEGY_SCOPED_ATTENTION_HITS"
elif strategy_scoped_no_followup * 100 >= max(1, strategy_scoped_attention) * 80:
    strategy_scoped_recommendation = "STRATEGY_SCOPED_ATTENTION_NO_TERMINAL_FOLLOWUP_DOMINATES"
elif strategy_scoped_entry_skip_followup >= max(strategy_scoped_filter_followup, strategy_scoped_autotrade_followup, strategy_scoped_signal_buy_followup, strategy_scoped_no_followup):
    strategy_scoped_recommendation = "STRATEGY_SCOPED_ATTENTION_TO_ENTRY_SKIP_REVIEW"
elif strategy_scoped_filter_followup >= max(strategy_scoped_entry_skip_followup, strategy_scoped_autotrade_followup, strategy_scoped_signal_buy_followup, strategy_scoped_no_followup):
    strategy_scoped_recommendation = "STRATEGY_SCOPED_ATTENTION_TO_FILTER_BLOCK_REVIEW"
elif strategy_scoped_signal_buy_followup + strategy_scoped_autotrade_followup > 0:
    strategy_scoped_recommendation = "STRATEGY_SCOPED_ATTENTION_PIPELINE_HAS_TERMINAL_FOLLOWUP"
else:
    strategy_scoped_recommendation = "STRATEGY_SCOPED_ATTENTION_PIPELINE_MIXED_REVIEW"

print("[attention-hit-progression] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} followupHours={followup_hours} limit={limit} maxAttentionRows={max_attention_rows}")
print("")
print("Attention Hit Progression Summary:")
print(f"  attention_hit_rows={attention_total}")
print(f"  sampled_attention_hit_rows={sampled_attention}")
print(f"  followup_terminal_event_rows={len(followups)}")
print(f"  no_terminal_followup_rows={no_followup}")
print(f"  macro_watch_only_attention_rows={macro_watch_only_attention}")
print(f"  strategy_scoped_attention_rows={strategy_scoped_attention}")
print(f"  strategy_scoped_terminal_followup_rows={strategy_scoped_terminal_followup}")
print(f"  strategy_scoped_no_terminal_followup_rows={strategy_scoped_no_followup}")
print(f"  strategy_scoped_filter_block_followup_rows={strategy_scoped_filter_followup}")
print(f"  strategy_scoped_entry_skip_followup_rows={strategy_scoped_entry_skip_followup}")
print(f"  strategy_scoped_signal_buy_followup_rows={strategy_scoped_signal_buy_followup}")
print(f"  strategy_scoped_autotrade_followup_rows={strategy_scoped_autotrade_followup}")
print(f"  filter_block_followup_rows={filter_followup}")
print(f"  entry_skip_followup_rows={entry_skip_followup}")
print(f"  signal_buy_followup_rows={signal_buy_followup}")
print(f"  autotrade_followup_rows={autotrade_followup}")
print("")
print("attention_followup_classification:")
if not classification:
    print("  - NONE=0")
else:
    for name, count in classification.most_common(limit):
        print(f"  - {name}={count}")
print("attention_followup_event_types:")
if not event_classification:
    print("  - NONE=0")
else:
    for name, count in event_classification.most_common(limit):
        print(f"  - {name}={count}")
print("strategy_scoped_followup_classification:")
if not strategy_scoped_classification:
    print("  - NONE=0")
else:
    for name, count in strategy_scoped_classification.most_common(limit):
        print(f"  - {name}={count}")
print("strategy_scoped_followup_event_types:")
if not strategy_scoped_event_classification:
    print("  - NONE=0")
else:
    for name, count in strategy_scoped_event_classification.most_common(limit):
        print(f"  - {name}={count}")
print("attention_hit_strategy_distribution:")
if not strategy_rows:
    print("  - NONE=0")
else:
    for row in strategy_rows:
        strategy = row[0] if len(row) > 0 else "UNKNOWN"
        interval = row[1] if len(row) > 1 else "N/A"
        count = row[2] if len(row) > 2 else "0"
        print(f"  - strategy={strategy} interval={interval} count={count}")
print("Examples:")
for att, follow, bucket in examples:
    if follow is None:
        print(f"  - attentionAuditId={att['id']} time={att['event_time']} strategy={att['strategy_id']} interval={att['interval_code']} classification={bucket} reason={att['reason'][:120]}")
    else:
        delta_minutes = int((follow["dt"] - att["dt"]).total_seconds() // 60)
        print(f"  - attentionAuditId={att['id']} time={att['event_time']} strategy={att['strategy_id']} interval={att['interval_code']} classification={bucket} followupAuditId={follow['id']} followupEvent={follow['event_type']} followupMinutes={delta_minutes} blocker={follow['blocker'] or 'NONE'} reason={follow['reason'][:120]}")
print("")
print("Conclusion:")
print(f"  attention_hit_progression_recommendation={recommendation}")
print(f"  strategy_scoped_attention_progression_recommendation={strategy_scoped_recommendation}")
print("  attention_hit_progression_next_action=Use this classification to inspect the dominant ATTENTION_HIT follow-up path before changing entry filters, DataFreshnessGuard, EntryDedup, strategy activation, or live execution.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[attention-hit-progression] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__FOLLOWUP_HOURS__", [string]$FollowupHours).
    Replace("__LIMIT__", [string]$Limit).
    Replace("__MAX_ATTENTION_ROWS__", [string]$MaxAttentionRows)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Attention hit progression smoke failed with exit code $LASTEXITCODE"
}
