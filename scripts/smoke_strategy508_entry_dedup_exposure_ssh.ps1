param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 168,
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

if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

if ($Hours -lt 1 -or $Hours -gt 720) {
    throw "Hours must be between 1 and 720."
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
  echo "[strategy508-entry-dedup-exposure] FAIL: $*" >&2
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
[ -s app.port ] || fail "app.port missing"
PORT="$(cat app.port | tr -d '[:space:]')"
case "$PORT" in ''|*[!0-9]*) fail "app.port is invalid: $PORT" ;; esac

MCP_KEY="$(read_env_key TRADING_MCP_KEY)"
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
export PORT MCP_KEY SYMBOL STRATEGY_ID INTERVAL_CODE HOURS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"

python3 - <<'PY'
import csv
import json
import os
import subprocess
import sys
import urllib.request
from collections import Counter

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
hours = int(os.environ["HOURS"])
limit = int(os.environ["LIMIT"])
url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}

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

def call_tool(name, arguments, timeout=120):
    body = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if "error" in data:
        raise RuntimeError(f"{name} JSON-RPC error: {data['error']}")
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        text = content[0].get("text") or ""
    else:
        text = json.dumps(result, ensure_ascii=False)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def parse_json(text):
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}

def val(data, key, default="N/A"):
    value = data.get(key, default)
    return default if value is None else str(value)

def list_val(data, key):
    value = data.get(key, [])
    return value if isinstance(value, list) else []

def row_dict(fields, row):
    return dict(zip(fields, row))

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

strategy_scope_sql = f"""
SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.entryDedupOpenExposureScope')), 'ALL_OPEN_ROWS') AS entry_dedup_open_exposure_scope
FROM bt_strategy
WHERE id = {strategy_id}
LIMIT 1
"""

audit_sql = f"""
SELECT
  COALESCE(SUM(a.event_type = 'SIGNAL_EVAL' AND UPPER(COALESCE(a.reason,'')) LIKE '%BUY%'), 0) AS buy_eval_rows,
  COALESCE(SUM(a.event_type = 'ENTRY_SKIP' AND a.blocker = 'EntryDedup'), 0) AS entry_dedup_skip_rows,
  COALESCE(SUM(a.event_type = 'FILTER_BLOCK'), 0) AS filter_block_rows,
  COALESCE(SUM(a.event_type LIKE 'AUTOTRADE%'), 0) AS autotrade_rows,
  COALESCE(MAX(CASE WHEN a.event_type = 'ENTRY_SKIP' AND a.blocker = 'EntryDedup' THEN DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') END), 'NONE') AS latest_entry_dedup_skip_time
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.strategy_id = {strategy_id}
  AND COALESCE(a.interval_code, 'N/A') = '{interval_sql}'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
"""

skip_reason_sql = f"""
SELECT COALESCE(NULLIF(a.reason,''), 'UNKNOWN') AS reason, COUNT(*) AS cnt
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.strategy_id = {strategy_id}
  AND COALESCE(a.interval_code, 'N/A') = '{interval_sql}'
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
GROUP BY reason
ORDER BY cnt DESC, reason ASC
LIMIT {limit}
"""

open_position_sql = f"""
SELECT
  COUNT(*) AS open_same_strategy_positions,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS open_same_strategy_auto_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 THEN 1 ELSE 0 END), 0) AS open_same_strategy_shadow_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1
    AND COALESCE(traded_qty, 0) = 0
    AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS open_same_strategy_shadow_zero_notional_positions,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL THEN 1 ELSE 0 END), 0) AS open_same_strategy_missing_oco,
  COALESCE(SUM(COALESCE(actual_entry_price, entry_price, 0) * COALESCE(oco_qty, traded_qty, 0)), 0) AS open_same_strategy_notional,
  COALESCE(SUM(CASE WHEN suggested_sl IS NOT NULL THEN ABS(COALESCE(actual_entry_price, entry_price, 0) - suggested_sl) * COALESCE(oco_qty, traded_qty, 0) ELSE 0 END), 0) AS open_same_strategy_max_loss
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
"""

open_examples_sql = f"""
SELECT
  id,
  DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS created_at,
  COALESCE(auto_traded, 0) AS auto_traded,
  COALESCE(entry_price, 0) AS entry_price,
  COALESCE(actual_entry_price, 0) AS actual_entry_price,
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

audit_rows = run_query(audit_sql)
reason_rows = run_query(skip_reason_sql)
open_rows = run_query(open_position_sql)
open_examples = run_query(open_examples_sql)
strategy_scope_rows = run_query(strategy_scope_sql)

audit_fields = ["buy_eval_rows", "entry_dedup_skip_rows", "filter_block_rows", "autotrade_rows", "latest_entry_dedup_skip_time"]
open_fields = [
    "open_same_strategy_positions",
    "open_same_strategy_auto_positions",
    "open_same_strategy_shadow_positions",
    "open_same_strategy_shadow_zero_notional_positions",
    "open_same_strategy_missing_oco",
    "open_same_strategy_notional",
    "open_same_strategy_max_loss",
]
audit = row_dict(audit_fields, audit_rows[0]) if audit_rows else {}
open_summary = row_dict(open_fields, open_rows[0]) if open_rows else {}
entry_dedup_scope = (strategy_scope_rows[0][0] if strategy_scope_rows and strategy_scope_rows[0] else "ALL_OPEN_ROWS").upper()

governance_text = call_tool("getEntryDedupGovernanceDashboard", {"symbol": symbol, "hours": hours}, timeout=120)
readiness_text = call_tool("getStagedAddReadiness", {
    "symbol": symbol,
    "strategyId": strategy_id,
    "side": "LONG",
    "intervalCode": interval_code,
}, timeout=120)
governance = parse_json(governance_text)
readiness = parse_json(readiness_text)

groups = list_val(governance, "groups")
target_group = {}
for group in groups:
    if str(group.get("strategyId")) == str(strategy_id) and str(group.get("intervalCode")) == interval_code:
        target_group = group
        break

blockers = list_val(readiness, "blockers")
warnings = list_val(readiness, "warnings")
blocker_counts = Counter(str(v) for v in blockers)

would_allow = val(readiness, "wouldAllowStagedAdd", "false").lower() == "true"
exact_duplicate = val(readiness, "exactDuplicate", "false").lower() == "true"
remaining = val(readiness, "remainingAddBudget", "N/A")
same_used = val(readiness, "sameStrategyExposureUsed", "N/A")
same_limit = val(readiness, "sameStrategyExposureLimit", "N/A")
auto_open_rows = int(open_summary.get("open_same_strategy_auto_positions", "0") or "0")
shadow_open_rows = int(open_summary.get("open_same_strategy_shadow_positions", "0") or "0")
shadow_zero_rows = int(open_summary.get("open_same_strategy_shadow_zero_notional_positions", "0") or "0")
open_notional = float(open_summary.get("open_same_strategy_notional", "0") or "0")
scope_auto_traded_only = entry_dedup_scope == "AUTO_TRADED_OPEN_ROWS"
if auto_open_rows > 0:
    open_semantic_status = "REAL_AUTO_TRADED_EXPOSURE_PRESENT"
elif shadow_open_rows > 0 and shadow_zero_rows == shadow_open_rows and open_notional == 0.0:
    open_semantic_status = "SHADOW_ZERO_NOTIONAL_ROWS_ONLY_NOT_REAL_EXPOSURE"
elif shadow_open_rows > 0:
    open_semantic_status = "NON_AUTO_OPEN_ROWS_REQUIRE_REVIEW"
else:
    open_semantic_status = "NO_OPEN_ROWS"

shadow_zero_ignored_by_scope = (
    scope_auto_traded_only
    and open_semantic_status == "SHADOW_ZERO_NOTIONAL_ROWS_ONLY_NOT_REAL_EXPOSURE"
)
live_entry_dedup_shadow_zero_blocker = not shadow_zero_ignored_by_scope

if would_allow:
    recommendation = "STAGED_ADD_SHADOW_CANDIDATE_REVIEW_NOT_LIVE"
elif exact_duplicate:
    recommendation = "KEEP_ENTRY_DEDUP_EXACT_DUPLICATE_BLOCK"
elif any("BUDGET" in str(v) or "NOTIONAL" in str(v) for v in blockers):
    recommendation = "ENTRY_DEDUP_BUDGET_CAP_BLOCKED"
elif shadow_zero_ignored_by_scope:
    recommendation = "ENTRY_DEDUP_SCOPE_ALREADY_AUTO_TRADED_REVIEW_NON_DEDUP_GATES"
elif blockers:
    recommendation = "ENTRY_DEDUP_HARD_SAFETY_BLOCKED"
else:
    recommendation = "ENTRY_DEDUP_REVIEW_INCONCLUSIVE"

print("[strategy508-entry-dedup-exposure] read-only production evidence check")
print("scope=READ_ONLY; server-local MCP read-only tools plus direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} limit={limit}")
print("")
print("Audit Window:")
print("  expected_entry_dedup_reason_marker=same strategy/symbol/interval LONG exposure already exists")
for key in audit_fields:
    print(f"  {key}={audit.get(key, '0')}")
print("EntryDedup skip reasons:")
if not reason_rows:
    print("  - NONE=0")
else:
    for row in reason_rows:
        reason = row[0] if len(row) > 0 else "UNKNOWN"
        count = row[1] if len(row) > 1 else "0"
        print(f"  - {reason}={count}")
print("")
print("Open Exposure:")
print(f"  strategy_entry_dedup_open_exposure_scope={entry_dedup_scope}")
print(f"  entry_dedup_scope_auto_traded_only={str(scope_auto_traded_only).lower()}")
for key in open_fields:
    print(f"  {key}={open_summary.get(key, '0')}")
print(f"  open_same_strategy_real_exposure_status={open_semantic_status}")
print(f"  shadow_zero_open_rows_ignored_by_entry_dedup_scope={str(shadow_zero_ignored_by_scope).lower()}")
print(f"  live_entry_dedup_shadow_zero_blocker={str(live_entry_dedup_shadow_zero_blocker).lower()}")
print("Open same-strategy examples:")
if not open_examples:
    print("  - NONE")
else:
    for row in open_examples:
        fields = ["id", "created_at", "auto_traded", "entry_price", "actual_entry_price", "traded_qty", "oco_qty", "oco_order_list_id", "filter_reason"]
        item = row_dict(fields, row)
        print(f"  - id={item.get('id')} created={item.get('created_at')} autoTraded={item.get('auto_traded')} entry={item.get('entry_price')} actualEntry={item.get('actual_entry_price')} qty={item.get('traded_qty')} ocoQty={item.get('oco_qty')} ocoListId={item.get('oco_order_list_id')} filterReason={item.get('filter_reason') or 'NONE'}")
print("")
print("MCP Staged-Add Readiness:")
print(f"  staged_add_decision={val(readiness, 'decision')}")
print(f"  wouldAllowStagedAdd={val(readiness, 'wouldAllowStagedAdd', 'false')}")
print(f"  exactDuplicate={val(readiness, 'exactDuplicate', 'false')}")
print(f"  sameStrategyExposureUsed={same_used}")
print(f"  sameStrategyExposureLimit={same_limit}")
print(f"  remainingAddBudget={remaining}")
print(f"  recommendedAddNotional={val(readiness, 'recommendedAddNotional')}")
print(f"  maxLossIfWrong={val(readiness, 'maxLossIfWrong')}")
print(f"  currentOpportunityHash={val(readiness, 'currentOpportunityHash')}")
print(f"  lastPreviewHash={val(readiness, 'lastPreviewHash')}")
print("  staged_add_blockers=" + json.dumps(blockers, ensure_ascii=False))
print("  staged_add_warnings=" + json.dumps(warnings, ensure_ascii=False))
print("MCP EntryDedup Governance Target Group:")
if target_group:
    print(f"  target_group_decision={target_group.get('decision', 'N/A')}")
    print(f"  target_group_wouldAllowStagedAdd={target_group.get('wouldAllowStagedAdd', 'N/A')}")
    print(f"  target_group_exactDuplicate={target_group.get('exactDuplicate', 'N/A')}")
    print(f"  target_group_entryDedupSkips={target_group.get('entryDedupSkips', 'N/A')}")
    print(f"  target_group_remainingAddBudget={target_group.get('remainingAddBudget', 'N/A')}")
    print("  target_group_blockers=" + json.dumps(target_group.get("blockers", []), ensure_ascii=False))
else:
    print("  target_group_missing=true")
print("")
print("Conclusion:")
print(f"  strategy508_entry_dedup_exposure_recommendation={recommendation}")
print(f"  strategy508_entry_dedup_blocker_count={sum(blocker_counts.values())}")
print("  strategy508_entry_dedup_next_action=Use this evidence to decide whether strategy 508 needs an EntryDedup/staged-add shadow review or should keep exact-duplicate/exposure protection unchanged; do not relax EntryDedup or place/add orders from this smoke.")
print("  notAuthorization=read-only evidence only; does not authorize live trading, strategy activation, DataFreshnessGuard or EntryDedup relaxation, staged-add execution, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[strategy508-entry-dedup-exposure] OK read-only check complete")
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
    throw "Strategy 508 EntryDedup exposure smoke failed with exit code $LASTEXITCODE"
}
