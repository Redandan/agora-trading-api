param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 7,
    [int]$LongDays = 30,
    [int]$Limit = 10
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

if ($LongDays -lt $ReviewDays -or $LongDays -gt 90) {
    throw "LongDays must be between ReviewDays and 90."
}

if ($Limit -lt 1 -or $Limit -gt 50) {
    throw "Limit must be between 1 and 50."
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
LONG_DAYS='__LONG_DAYS__'
LIMIT='__LIMIT__'

fail() {
  echo "[data-freshness-sample-gap-rca] FAIL: $*" >&2
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
export SYMBOL REVIEW_DAYS LONG_DAYS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import os
import subprocess
import sys

symbol = os.environ["SYMBOL"].upper()
review_days = int(os.environ["REVIEW_DAYS"])
long_days = int(os.environ["LONG_DAYS"])
limit = int(os.environ["LIMIT"])

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

symbol_sql = esc(symbol)

buy_like = """
(
  a.event_type IN ('SIGNAL_BUY','ATTENTION_HIT')
  OR UPPER(COALESCE(a.outcome,'')) LIKE '%BUY%'
  OR UPPER(COALESCE(a.reason,'')) LIKE '%BUY%'
  OR UPPER(COALESCE(a.reason,'')) LIKE '%LONG%'
  OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.side')), '')) IN ('LONG','BUY')
  OR UPPER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.signal')), '')) IN ('LONG','BUY')
)
"""

window_sql = f"""
SELECT
  COUNT(*) AS total_rows,
  COALESCE(SUM(CASE WHEN a.event_type = 'SIGNAL_EVAL' THEN 1 ELSE 0 END), 0) AS signal_eval_rows,
  COALESCE(SUM(CASE WHEN {buy_like} THEN 1 ELSE 0 END), 0) AS buy_like_rows,
  COALESCE(SUM(CASE WHEN a.event_type = 'FILTER_BLOCK' THEN 1 ELSE 0 END), 0) AS filter_block_rows,
  COALESCE(SUM(CASE WHEN a.event_type = 'ENTRY_SKIP' THEN 1 ELSE 0 END), 0) AS entry_skip_rows,
  COALESCE(SUM(CASE WHEN a.event_type LIKE 'AUTOTRADE%' THEN 1 ELSE 0 END), 0) AS autotrade_rows,
  COALESCE(SUM(CASE WHEN a.event_type = 'FILTER_BLOCK' AND a.blocker = 'DataFreshnessGuard' THEN 1 ELSE 0 END), 0) AS data_freshness_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY THEN 1 ELSE 0 END), 0) AS review_total_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND a.event_type = 'SIGNAL_EVAL' THEN 1 ELSE 0 END), 0) AS review_signal_eval_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND {buy_like} THEN 1 ELSE 0 END), 0) AS review_buy_like_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND a.event_type = 'FILTER_BLOCK' THEN 1 ELSE 0 END), 0) AS review_filter_block_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND a.event_type = 'ENTRY_SKIP' THEN 1 ELSE 0 END), 0) AS review_entry_skip_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND a.event_type LIKE 'AUTOTRADE%' THEN 1 ELSE 0 END), 0) AS review_autotrade_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY AND a.event_type = 'FILTER_BLOCK' AND a.blocker = 'DataFreshnessGuard' THEN 1 ELSE 0 END), 0) AS review_data_freshness_rows,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL 1 DAY THEN 1 ELSE 0 END), 0) AS rows_1d,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL 3 DAY THEN 1 ELSE 0 END), 0) AS rows_3d,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL 7 DAY THEN 1 ELSE 0 END), 0) AS rows_7d,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL 14 DAY THEN 1 ELSE 0 END), 0) AS rows_14d,
  COALESCE(SUM(CASE WHEN a.event_time >= UTC_TIMESTAMP() - INTERVAL 30 DAY THEN 1 ELSE 0 END), 0) AS rows_30d
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {long_days} DAY
"""

latest_df_sql = f"""
SELECT
  COALESCE(DATE_FORMAT(MAX(a.event_time), '%Y-%m-%dT%H:%i:%s'), 'NONE') AS latest_event_time,
  COALESCE(TIMESTAMPDIFF(HOUR, MAX(a.event_time), UTC_TIMESTAMP()), -1) AS latest_age_hours,
  COALESCE(SUM(a.event_time >= UTC_TIMESTAMP() - INTERVAL 1 DAY), 0) AS rows_1d,
  COALESCE(SUM(a.event_time >= UTC_TIMESTAMP() - INTERVAL 3 DAY), 0) AS rows_3d,
  COALESCE(SUM(a.event_time >= UTC_TIMESTAMP() - INTERVAL 7 DAY), 0) AS rows_7d,
  COALESCE(SUM(a.event_time >= UTC_TIMESTAMP() - INTERVAL 14 DAY), 0) AS rows_14d,
  COALESCE(SUM(a.event_time >= UTC_TIMESTAMP() - INTERVAL 30 DAY), 0) AS rows_30d
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.blocker = 'DataFreshnessGuard'
"""

event_counts_sql = f"""
SELECT COALESCE(NULLIF(a.event_type,''), 'UNKNOWN') AS event_type, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
GROUP BY event_type
ORDER BY cnt DESC, event_type ASC
LIMIT {limit}
"""

blocker_counts_sql = f"""
SELECT COALESCE(NULLIF(a.blocker,''), 'UNKNOWN') AS blocker, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {review_days} DAY
GROUP BY blocker
ORDER BY cnt DESC, blocker ASC
LIMIT {limit}
"""

long_blocker_counts_sql = f"""
SELECT COALESCE(NULLIF(a.blocker,''), 'UNKNOWN') AS blocker, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.event_type = 'FILTER_BLOCK'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {long_days} DAY
GROUP BY blocker
ORDER BY cnt DESC, blocker ASC
LIMIT {limit}
"""

window_rows = run_query(window_sql)
latest_rows = run_query(latest_df_sql)
event_rows = run_query(event_counts_sql)
blocker_rows = run_query(blocker_counts_sql)
long_blocker_rows = run_query(long_blocker_counts_sql)

window_fields = [
    "total_rows", "signal_eval_rows", "buy_like_rows", "filter_block_rows",
    "entry_skip_rows", "autotrade_rows", "data_freshness_rows",
    "review_total_rows", "review_signal_eval_rows", "review_buy_like_rows",
    "review_filter_block_rows", "review_entry_skip_rows", "review_autotrade_rows",
    "review_data_freshness_rows",
    "rows_1d", "rows_3d", "rows_7d", "rows_14d", "rows_30d",
]
latest_fields = [
    "latest_data_freshness_row_time", "latest_data_freshness_row_age_hours",
    "data_freshness_rows_1d", "data_freshness_rows_3d", "data_freshness_rows_7d",
    "data_freshness_rows_14d", "data_freshness_rows_30d",
]
window = dict(zip(window_fields, window_rows[0])) if window_rows else {k: "0" for k in window_fields}
latest = dict(zip(latest_fields, latest_rows[0])) if latest_rows else {k: "0" for k in latest_fields}

def intval(mapping, key):
    try:
        return int(mapping.get(key, "0") or "0")
    except ValueError:
        return 0

review_total_rows = intval(window, "review_total_rows")
review_buy_like_rows = intval(window, "review_buy_like_rows")
review_filter_block_rows = intval(window, "review_filter_block_rows")
df_review_rows = intval(latest, "data_freshness_rows_7d")
df_long_rows = intval(latest, "data_freshness_rows_30d")

if df_review_rows > 0:
    recommendation = "DATAFRESHNESS_SAMPLE_PRESENT"
elif review_total_rows == 0:
    recommendation = "NO_AUDIT_ROWS_IN_WINDOW"
elif review_buy_like_rows == 0:
    recommendation = "NO_RECENT_BUY_STYLE_CANDIDATES"
elif review_filter_block_rows > 0:
    recommendation = "OTHER_BLOCKERS_DOMINATE_RECENT_WINDOW"
else:
    recommendation = "CANDIDATES_EXIST_BUT_NOT_DF_BLOCKED"

if df_review_rows == 0 and df_long_rows > 0:
    gap_detail = "RECENT_WINDOW_GAP_WITH_OLDER_DATAFRESHNESS_HISTORY"
elif df_review_rows == 0:
    gap_detail = "NO_DATAFRESHNESS_ROWS_IN_REVIEW_WINDOW"
else:
    gap_detail = "DATAFRESHNESS_ROWS_IN_REVIEW_WINDOW"

def print_pairs(title, rows):
    print(f"{title}:")
    if not rows:
        print("  - NONE=0")
        return
    for row in rows:
        name = row[0] if len(row) > 0 and row[0] else "UNKNOWN"
        cnt = row[1] if len(row) > 1 and row[1] else "0"
        print(f"  - {name}={cnt}")

print("[data-freshness-sample-gap-rca] read-only production DB evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} longDays={long_days} limit={limit}")
print("")
print("Audit Window Distribution:")
print(f"  audit_rows_{long_days}d={window.get('total_rows', '0')}")
print(f"  signal_eval_rows_{long_days}d={window.get('signal_eval_rows', '0')}")
print(f"  buy_like_rows_{long_days}d={window.get('buy_like_rows', '0')}")
print(f"  filter_block_rows_{long_days}d={window.get('filter_block_rows', '0')}")
print(f"  entry_skip_rows_{long_days}d={window.get('entry_skip_rows', '0')}")
print(f"  autotrade_rows_{long_days}d={window.get('autotrade_rows', '0')}")
print(f"  data_freshness_rows_{long_days}d={window.get('data_freshness_rows', '0')}")
print(f"  audit_rows_{review_days}d_review={window.get('review_total_rows', '0')}")
print(f"  signal_eval_rows_{review_days}d_review={window.get('review_signal_eval_rows', '0')}")
print(f"  buy_like_rows_{review_days}d_review={window.get('review_buy_like_rows', '0')}")
print(f"  filter_block_rows_{review_days}d_review={window.get('review_filter_block_rows', '0')}")
print(f"  entry_skip_rows_{review_days}d_review={window.get('review_entry_skip_rows', '0')}")
print(f"  autotrade_rows_{review_days}d_review={window.get('review_autotrade_rows', '0')}")
print(f"  data_freshness_rows_{review_days}d_review={window.get('review_data_freshness_rows', '0')}")
print(f"  audit_rows_1d={window.get('rows_1d', '0')}")
print(f"  audit_rows_3d={window.get('rows_3d', '0')}")
print(f"  audit_rows_7d={window.get('rows_7d', '0')}")
print(f"  audit_rows_14d={window.get('rows_14d', '0')}")
print(f"  audit_rows_30d={window.get('rows_30d', '0')}")
print("")
print("DataFreshness Sample Recency:")
for key in latest_fields:
    print(f"  {key}={latest.get(key, '0')}")
print(f"  data_freshness_sample_gap_detail={gap_detail}")
print_pairs(f"event_type_counts_{review_days}d", event_rows)
print_pairs(f"top_filter_blockers_{review_days}d", blocker_rows)
print_pairs(f"top_filter_blockers_{long_days}d", long_blocker_rows)
print("")
print("Conclusion:")
print(f"  data_freshness_sample_gap_rca_recommendation={recommendation}")
print("  data_freshness_sample_gap_next_action=Use this classification to decide whether to wait for new BUY-style candidates, inspect dominant non-DataFreshness blockers, or continue replay snapshot collection; do not relax DataFreshnessGuard from this smoke alone.")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[data-freshness-sample-gap-rca] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LONG_DAYS__", [string]$LongDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "DataFreshness sample gap RCA smoke failed with exit code $LASTEXITCODE"
}
