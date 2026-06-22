param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 168,
    [int]$Limit = 20
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

if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

if ($Hours -lt 1 -or $Hours -gt 720) {
    throw "Hours must be between 1 and 720."
}

if ($Limit -lt 1 -or $Limit -gt 100) {
    throw "Limit must be between 1 and 100."
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
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'
SYMBOL='__SYMBOL__'
STRATEGY_ID='__STRATEGY_ID__'
INTERVAL_CODE='__INTERVAL_CODE__'
HOURS='__HOURS__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-exposure-consistency] FAIL: $*" >&2
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
export SYMBOL STRATEGY_ID INTERVAL_CODE HOURS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import os
import subprocess
import sys

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
hours = int(os.environ["HOURS"])
limit = int(os.environ["LIMIT"])

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

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

def row_dict(fields, row):
    return dict(zip(fields, row))

def as_int(value):
    try:
        return int(float(str(value or "0")))
    except Exception:
        return 0

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

entry_skip_sql = f"""
SELECT
  COUNT(*) AS entry_dedup_skip_rows,
  COALESCE(MAX(DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s')), 'NONE') AS latest_entry_dedup_skip_time,
  COALESCE(SUM(a.reason = 'same strategy/symbol/interval LONG exposure already exists'), 0) AS same_exposure_reason_rows
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.strategy_id = {strategy_id}
  AND COALESCE(a.interval_code, 'N/A') = '{interval_sql}'
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
"""

exposure_sql = f"""
SELECT
  COUNT(*) AS open_signal_rows,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS auto_traded_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 THEN 1 ELSE 0 END), 0) AS non_auto_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS non_auto_zero_qty_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(filter_reason, '') LIKE 'EventRiskControl:%' THEN 1 ELSE 0 END), 0) AS non_auto_eventrisk_rows,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL OR oco_order_list_id = '' THEN 1 ELSE 0 END), 0) AS missing_oco_rows,
  COALESCE(SUM(COALESCE(actual_entry_price, entry_price, 0) * COALESCE(oco_qty, traded_qty, 0)), 0) AS open_notional
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
"""

example_sql = f"""
SELECT
  id,
  DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS created_at,
  COALESCE(auto_traded, 0) AS auto_traded,
  COALESCE(traded_qty, 0) AS traded_qty,
  COALESCE(oco_qty, 0) AS oco_qty,
  COALESCE(oco_order_list_id, 0) AS oco_order_list_id,
  COALESCE(filter_reason, '') AS filter_reason
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
ORDER BY created_at DESC
LIMIT {limit}
"""

skip_fields = ["entry_dedup_skip_rows", "latest_entry_dedup_skip_time", "same_exposure_reason_rows"]
exposure_fields = [
    "open_signal_rows",
    "auto_traded_open_rows",
    "non_auto_open_rows",
    "non_auto_zero_qty_rows",
    "non_auto_eventrisk_rows",
    "missing_oco_rows",
    "open_notional",
]
example_fields = ["id", "created_at", "auto_traded", "traded_qty", "oco_qty", "oco_order_list_id", "filter_reason"]

skip = row_dict(skip_fields, run_query(entry_skip_sql)[0])
exposure = row_dict(exposure_fields, run_query(exposure_sql)[0])
examples = [row_dict(example_fields, row) for row in run_query(example_sql)]

skip_rows = as_int(skip.get("entry_dedup_skip_rows"))
auto_rows = as_int(exposure.get("auto_traded_open_rows"))
non_auto_rows = as_int(exposure.get("non_auto_open_rows"))
eventrisk_rows = as_int(exposure.get("non_auto_eventrisk_rows"))
zero_qty_rows = as_int(exposure.get("non_auto_zero_qty_rows"))

if skip_rows == 0:
    recommendation = "ENTRY_DEDUP_NO_RECENT_SKIPS"
elif auto_rows > 0:
    recommendation = "ENTRY_DEDUP_EXPOSURE_CONSISTENT_AUTO_POSITION"
elif non_auto_rows > 0 and eventrisk_rows > 0 and zero_qty_rows > 0:
    recommendation = "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW"
else:
    recommendation = "ENTRY_DEDUP_EXPOSURE_INCONCLUSIVE"

print("[entry-dedup-exposure-consistency] read-only production evidence check")
print("scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} limit={limit}")
print("")
print("EntryDedup Audit:")
for key in skip_fields:
    print(f"  {key}={skip.get(key, '0')}")
print("  expected_entry_dedup_reason_marker=same strategy/symbol/interval LONG exposure already exists")
print("")
print("Open Exposure Consistency:")
for key in exposure_fields:
    print(f"  {key}={exposure.get(key, '0')}")
print("  entry_dedup_exposure_definition=existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull includes any open signal row")
print("  staged_add_existing_position_definition=findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull includes only autoTraded open rows")
print("Open same-strategy examples:")
if not examples:
    print("  - NONE")
else:
    for item in examples:
        print(f"  - id={item.get('id')} created={item.get('created_at')} autoTraded={item.get('auto_traded')} tradedQty={item.get('traded_qty')} ocoQty={item.get('oco_qty')} ocoListId={item.get('oco_order_list_id')} filterReason={item.get('filter_reason') or 'NONE'}")
print("")
print("Conclusion:")
print(f"  entry_dedup_exposure_consistency_recommendation={recommendation}")
print("  entry_dedup_exposure_consistency_next_action=If mismatch review is returned, inspect EntryDedup/exposure semantics with replay or shadow evidence; do not relax EntryDedup, place/add orders, or mutate production from this smoke.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, staged-add execution, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[entry-dedup-exposure-consistency] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__STRATEGY_ID__", [string]$StrategyId).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__HOURS__", [string]$Hours).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup exposure consistency smoke failed with exit code $LASTEXITCODE"
}
