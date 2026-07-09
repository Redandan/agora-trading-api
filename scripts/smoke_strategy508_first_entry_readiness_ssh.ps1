param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 168,
    [int]$Limit = 12
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
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

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
  echo "[strategy508-first-entry-readiness] FAIL: $*" >&2
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

read_env_optional() {
  local key="$1"
  local default_value="$2"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ]; then
    printf '%s\n' "$default_value"
    return 0
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
  db_port="${host_port##*:}"
else
  host="$host_port"
  db_port="3306"
fi
case "$db_port" in ''|*[!0-9]*) fail "database port is invalid in SPRING_DATASOURCE_URL: $db_port" ;; esac

export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
export PORT MCP_KEY SYMBOL STRATEGY_ID INTERVAL_CODE HOURS LIMIT
export MYSQL_HOST="$host" MYSQL_PORT="$db_port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database"
export TRADING_SIGNAL_SOURCE_PRIMARY="$(read_env_optional TRADING_SIGNAL_SOURCE_PRIMARY TRADINGVIEW)"
export TRADING_LEGACY_LIVE_EVALUATOR_ENABLED="$(read_env_optional TRADING_LEGACY_LIVE_EVALUATOR_ENABLED false)"
export TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED="$(read_env_optional TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED false)"
export TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS="$(read_env_optional TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS '')"
export TRADING_LEGACY_SECONDARY_MAX_NOTIONAL_USDT="$(read_env_optional TRADING_LEGACY_SECONDARY_MAX_NOTIONAL_USDT 0)"
export TRADINGVIEW_LOCAL_ENABLED="$(read_env_optional TRADINGVIEW_LOCAL_ENABLED false)"
export TRADINGVIEW_LOCAL_STRATEGY_ID="$(read_env_optional TRADINGVIEW_LOCAL_STRATEGY_ID 485)"
export TRADINGVIEW_LOCAL_EXECUTION_MODE="$(read_env_optional TRADINGVIEW_LOCAL_EXECUTION_MODE LEGACY)"
export TRADINGVIEW_LOCAL_EXECUTION_ENABLED="$(read_env_optional TRADINGVIEW_LOCAL_EXECUTION_ENABLED false)"
export TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED="$(read_env_optional TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED false)"
export TRADING_OKX_ENABLED="$(read_env_optional TRADING_OKX_ENABLED false)"
export TRADING_OKX_MAX_OPEN_POSITIONS="$(read_env_optional TRADING_OKX_MAX_OPEN_POSITIONS 3)"
export TRADING_OKX_ALLOW_CONCURRENT_ON_SAME_SYMBOL="$(read_env_optional TRADING_OKX_ALLOW_CONCURRENT_ON_SAME_SYMBOL false)"
export TRADING_OKX_POSITION_SIZING_LIVE_ENABLED="$(read_env_optional TRADING_OKX_POSITION_SIZING_LIVE_ENABLED false)"
export TRADING_OKX_POSITION_SIZING_SHADOW_ENABLED="$(read_env_optional TRADING_OKX_POSITION_SIZING_SHADOW_ENABLED true)"
export TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_USDT="$(read_env_optional TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_USDT 50)"
export TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED="$(read_env_optional TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED false)"
export TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT="$(read_env_optional TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT 6.25)"

python3 - <<'PY'
import csv
import json
import os
import re
import subprocess
import sys
import urllib.request
from collections import Counter
from datetime import datetime

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
hours = int(os.environ["HOURS"])
limit = int(os.environ["LIMIT"])
url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["MCP_KEY"]}

def esc(value):
    return str(value).replace("\\", "\\\\").replace("'", "''")

def run_query(sql):
    cmd = [
        "mysql", "--batch", "--raw", "--skip-column-names",
        "-h", os.environ["MYSQL_HOST"], "-P", os.environ["MYSQL_PORT"],
        "-u", os.environ["MYSQL_USER"], os.environ["MYSQL_DATABASE"], "-e", sql,
    ]
    try:
        proc = subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as exc:
        print(exc.stderr, file=sys.stderr)
        sys.exit(exc.returncode or 1)
    return list(csv.reader(proc.stdout.splitlines(), delimiter="\t"))

def call_tool(name, arguments, timeout=120):
    body = {"jsonrpc": "2.0", "id": name, "method": "tools/call",
            "params": {"name": name, "arguments": arguments}}
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                     headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8", "replace"))
    if payload.get("error"):
        raise RuntimeError(f"{name} JSON-RPC error: {payload['error']}")
    result = payload.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    text = "\n".join(item.get("text", "") for item in content if isinstance(item, dict))
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def row_dict(fields, row):
    return dict(zip(fields, row)) if row else {}

def first_row(fields, rows):
    return row_dict(fields, rows[0]) if rows else {}

def env_bool(name, default=False):
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    return str(value).strip().lower() in ("1", "true", "yes", "y", "on")

def env_float(name, default=0.0):
    try:
        return float(str(os.environ.get(name, default)).strip())
    except Exception:
        return default

def env_int(name, default=0):
    try:
        return int(float(str(os.environ.get(name, default)).strip()))
    except Exception:
        return default

def norm_primary():
    value = os.environ.get("TRADING_SIGNAL_SOURCE_PRIMARY", "TRADINGVIEW")
    return value.strip().upper().replace("-", "_") if value else "TRADINGVIEW"

def allowed_ids(csv_text):
    out = set()
    for token in (csv_text or "").split(","):
        token = token.strip()
        if not token:
            continue
        try:
            out.add(int(token))
        except Exception:
            pass
    return out

def parse_json_obj(text):
    if not text:
        return {}
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}

def first_text(*values):
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text and text.upper() not in ("NULL", "N/A", "NONE"):
            return text
    return ""

def first_float(*values):
    for value in values:
        text = first_text(value)
        if not text:
            continue
        try:
            return float(text)
        except Exception:
            pass
    return None

def parse_ts(value):
    text = first_text(value)
    if not text:
        return None
    try:
        return datetime.strptime(text, "%Y-%m-%dT%H:%M:%S")
    except Exception:
        return None

def yesno(value):
    return str(bool(value)).lower()

def money(value):
    try:
        return f"{float(value):.2f}"
    except Exception:
        return "N/A"

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

strategy_sql = f"""
SELECT
  id,
  name,
  strategy_type,
  enabled,
  COALESCE(symbols, '') AS symbols,
  COALESCE(kline_source, '') AS kline_source,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.entryDedupOpenExposureScope')), 'ALL_OPEN_ROWS') AS entry_dedup_scope,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.notifyOnly')), 'false') AS notify_only,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.preTradeExpectedValueGateEnabled')), 'true') AS ev_gate_enabled,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.preTradeMinExpectedR')), '0.20') AS min_expected_r,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.entryDedupDecisionMode')), 'BLOCK') AS entry_dedup_decision_mode,
  COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.microAddLiveEnabled')), 'false') AS micro_add_live_enabled
FROM bt_strategy
WHERE id = {strategy_id}
LIMIT 1
"""

open_sql = f"""
SELECT
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) = 1 AND exit_time IS NULL THEN 1 ELSE 0 END), 0) AS all_auto_open_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) = 1 AND exit_time IS NULL AND symbol = '{symbol_sql}' THEN 1 ELSE 0 END), 0) AS same_symbol_auto_open_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) = 1 AND exit_time IS NULL AND symbol = '{symbol_sql}' AND strategy_id = {strategy_id} AND COALESCE(interval_code, 'N/A') = '{interval_sql}' AND COALESCE(side, 'LONG') = 'LONG' THEN 1 ELSE 0 END), 0) AS same_strategy_auto_open_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND exit_time IS NULL AND symbol = '{symbol_sql}' AND strategy_id = {strategy_id} AND COALESCE(interval_code, 'N/A') = '{interval_sql}' AND COALESCE(side, 'LONG') = 'LONG' THEN 1 ELSE 0 END), 0) AS same_strategy_non_auto_open_positions,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND exit_time IS NULL AND symbol = '{symbol_sql}' AND strategy_id = {strategy_id} AND COALESCE(interval_code, 'N/A') = '{interval_sql}' AND COALESCE(side, 'LONG') = 'LONG' AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS same_strategy_non_auto_zero_qty_open_positions
FROM bt_live_signal
"""

latest_signal_sql = f"""
SELECT
  id,
  DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') AS created_at,
  DATE_FORMAT(bar_open_time, '%Y-%m-%dT%H:%i:%s') AS bar_open_time,
  COALESCE(auto_traded, 0) AS auto_traded,
  COALESCE(filter_reason, '') AS filter_reason,
  COALESCE(entry_price, '') AS entry_price,
  COALESCE(suggested_tp, '') AS suggested_tp,
  COALESCE(suggested_sl, '') AS suggested_sl,
  COALESCE(nn_output, '') AS nn_output,
  COALESCE(score, '') AS score,
  COALESCE(actual_entry_price, '') AS actual_entry_price,
  COALESCE(traded_qty, '') AS traded_qty,
  COALESCE(oco_qty, '') AS oco_qty,
  COALESCE(oco_order_list_id, '') AS oco_order_list_id
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
ORDER BY created_at DESC
LIMIT 1
"""

audit_counts_sql = f"""
SELECT
  event_type,
  COALESCE(blocker, '') AS blocker,
  COALESCE(reason, '') AS reason,
  COUNT(*) AS cnt,
  COALESCE(DATE_FORMAT(MAX(event_time), '%Y-%m-%dT%H:%i:%s'), 'NONE') AS latest_event_time
FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
GROUP BY event_type, blocker, reason
ORDER BY latest_event_time DESC, cnt DESC
LIMIT {limit}
"""

latest_audit_sql = f"""
SELECT
  id,
  DATE_FORMAT(event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  event_type,
  outcome,
  COALESCE(blocker, '') AS blocker,
  COALESCE(reason, '') AS reason,
  COALESCE(live_signal_id, '') AS live_signal_id,
  COALESCE(context_json, '') AS context_json
FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
ORDER BY event_time DESC
LIMIT 1
"""

latest_ev_sql = f"""
SELECT
  id,
  DATE_FORMAT(event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  event_type,
  outcome,
  COALESCE(blocker, '') AS blocker,
  COALESCE(reason, '') AS reason,
  COALESCE(live_signal_id, '') AS live_signal_id,
  COALESCE(context_json, '') AS context_json
FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
  AND (blocker = 'ExpectedValueGate' OR event_type = 'AUTOTRADE_OK' OR event_type = 'AUTOTRADE_FAIL')
ORDER BY event_time DESC
LIMIT 1
"""

strategy_fields = ["id", "name", "strategy_type", "enabled", "symbols", "kline_source",
                   "entry_dedup_scope", "notify_only", "ev_gate_enabled", "min_expected_r",
                   "entry_dedup_decision_mode", "micro_add_live_enabled"]
open_fields = ["all_auto_open_positions", "same_symbol_auto_open_positions",
               "same_strategy_auto_open_positions", "same_strategy_non_auto_open_positions",
               "same_strategy_non_auto_zero_qty_open_positions"]
signal_fields = ["id", "created_at", "bar_open_time", "auto_traded", "filter_reason",
                 "entry_price", "suggested_tp", "suggested_sl", "nn_output", "score",
                 "actual_entry_price", "traded_qty", "oco_qty", "oco_order_list_id"]
audit_fields = ["id", "event_time", "event_type", "outcome", "blocker", "reason",
                "live_signal_id", "context_json"]

strategy = first_row(strategy_fields, run_query(strategy_sql))
open_summary = first_row(open_fields, run_query(open_sql))
latest_signal = first_row(signal_fields, run_query(latest_signal_sql))
latest_audit = first_row(audit_fields, run_query(latest_audit_sql))
latest_ev = first_row(audit_fields, run_query(latest_ev_sql))
audit_counts = run_query(audit_counts_sql)

primary = norm_primary()
legacy_enabled = env_bool("TRADING_LEGACY_LIVE_EVALUATOR_ENABLED", False)
secondary_enabled = env_bool("TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED", False)
secondary_ids = allowed_ids(os.environ.get("TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS", ""))
legacy_primary_allowed = primary == "LEGACY" and legacy_enabled
secondary_allowed = primary != "LEGACY" and secondary_enabled and strategy_id in secondary_ids
legacy_for_strategy = legacy_primary_allowed or secondary_allowed
local_tv_strategy_id = env_int("TRADINGVIEW_LOCAL_STRATEGY_ID", 485)
local_tv_for_strategy = primary == "LOCAL_TRADINGVIEW" and env_bool("TRADINGVIEW_LOCAL_ENABLED", False) and local_tv_strategy_id == strategy_id

if legacy_for_strategy:
    signal_source_gate = "LEGACY_LIVE_EVALUATOR_ACTIVE_FOR_STRATEGY"
elif local_tv_for_strategy:
    signal_source_gate = "LOCAL_TRADINGVIEW_ACTIVE_FOR_STRATEGY"
elif primary == "TRADINGVIEW":
    signal_source_gate = "EXTERNAL_TRADINGVIEW_PRIMARY_LEGACY_508_SUPPRESSED"
else:
    signal_source_gate = "SIGNAL_SOURCE_POLICY_SUPPRESSES_STRATEGY_508"

strategy_exists = bool(strategy)
strategy_enabled = str(strategy.get("enabled", "0")).strip().lower() in ("1", "true")
notify_only = str(strategy.get("notify_only", "false")).strip().lower() in ("1", "true")
entry_scope = str(strategy.get("entry_dedup_scope", "ALL_OPEN_ROWS")).strip().upper()
same_auto = int(open_summary.get("same_strategy_auto_open_positions", "0") or "0")
same_non_auto = int(open_summary.get("same_strategy_non_auto_open_positions", "0") or "0")
same_non_auto_zero = int(open_summary.get("same_strategy_non_auto_zero_qty_open_positions", "0") or "0")
same_symbol_auto = int(open_summary.get("same_symbol_auto_open_positions", "0") or "0")
all_auto = int(open_summary.get("all_auto_open_positions", "0") or "0")
max_open = env_int("TRADING_OKX_MAX_OPEN_POSITIONS", 3)
allow_concurrent_symbol = env_bool("TRADING_OKX_ALLOW_CONCURRENT_ON_SAME_SYMBOL", False)

entry_dedup_pass = (
    same_auto == 0
    and (entry_scope == "AUTO_TRADED_OPEN_ROWS" or same_non_auto == 0)
)
first_entry_semantics = same_auto == 0
staged_add_preview_applicable = same_auto > 0
open_position_gate = "PASS"
if all_auto >= max_open:
    open_position_gate = "BLOCK_MAX_OPEN_POSITIONS"
elif same_symbol_auto > 0 and not allow_concurrent_symbol:
    open_position_gate = "BLOCK_SAME_SYMBOL_AUTO_POSITION"

latest_ctx = parse_json_obj(latest_audit.get("context_json", ""))
ev_ctx = parse_json_obj(latest_ev.get("context_json", ""))
entry = first_text(latest_signal.get("entry_price"), latest_ctx.get("entry"), latest_ctx.get("candidateEntry"), ev_ctx.get("entry"), ev_ctx.get("candidateEntry"))
tp = first_text(latest_signal.get("suggested_tp"), latest_ctx.get("tp"), latest_ctx.get("candidateTp"), ev_ctx.get("tp"), ev_ctx.get("candidateTp"))
sl = first_text(latest_signal.get("suggested_sl"), latest_ctx.get("sl"), latest_ctx.get("candidateSl"), ev_ctx.get("sl"), ev_ctx.get("candidateSl"))
nn = first_float(latest_signal.get("nn_output"), latest_ctx.get("nnOutput"), latest_ctx.get("nn"), 0.8)
expected_r = first_float(ev_ctx.get("expected_r"), latest_ctx.get("expected_r"))
min_expected_r = first_float(strategy.get("min_expected_r"), ev_ctx.get("min_expected_r"), latest_ctx.get("min_expected_r"), 0.20)

ev_gate_status = "NO_RECENT_EV_CONTEXT"
if latest_ev:
    if latest_ev.get("blocker") == "ExpectedValueGate":
        ev_gate_status = "BLOCK_" + (latest_ev.get("reason") or "ExpectedValueGate").replace(" ", "_")
    elif latest_ev.get("event_type") == "AUTOTRADE_OK":
        ev_gate_status = "PASS_AUTOTRADE_OK"
    elif latest_ev.get("event_type") == "AUTOTRADE_FAIL":
        ev_gate_status = "PASS_THEN_AUTOTRADE_FAIL"
if expected_r is not None:
    if expected_r <= 0:
        ev_gate_status = "BLOCK_EXPECTED_R_LE_ZERO"
    elif min_expected_r is not None and expected_r < min_expected_r:
        ev_gate_status = "BLOCK_EXPECTED_R_BELOW_MIN"
    elif ev_gate_status == "NO_RECENT_EV_CONTEXT":
        ev_gate_status = "PASS_EXPECTED_R_CONTEXT"

sizing_status = "NO_LATEST_SIGNAL_FOR_SIZING_PREVIEW"
sizing_lines = []
if entry and tp and sl:
    try:
        sizing_text = call_tool("previewPositionSizing", {
            "symbol": symbol,
            "strategyId": strategy_id,
            "entry": float(entry),
            "tp": float(tp),
            "sl": float(sl),
            "nnOutput": nn if nn is not None else 0.8,
            "legacyAmountUsdt": 100.0,
        }, timeout=60)
        sizing_lines = [line.strip() for line in sizing_text.splitlines()
                        if any(key in line for key in (
                            "finalAmountUsdt", "recommendedAmountUsdt", "legacyAmountUsdt",
                            "reason=", "slDistancePct", "riskBudgetUsdt",
                            "recommendedSlRiskUsdt", "explain="))]
        lower = sizing_text.lower()
        if "min_notional_floor_applied" in lower:
            sizing_status = "PASS_MIN_NOTIONAL_FLOOR_APPLIED"
        elif "below_min_notional_skip" in lower or "risk-sized notional" in lower or "below min" in lower:
            sizing_status = "BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL"
        elif "mode=live" in lower:
            sizing_status = "PASS_LIVE_SIZING_PREVIEW"
        elif "mode=shadow" in lower:
            sizing_status = "SHADOW_SIZING_PREVIEW_ONLY"
        else:
            sizing_status = "SIZING_PREVIEW_AVAILABLE"
    except Exception as exc:
        sizing_status = "SIZING_PREVIEW_ERROR"
        sizing_lines = [str(exc)]

latest_filter = latest_signal.get("filter_reason", "") if latest_signal else ""
latest_auto = str(latest_signal.get("auto_traded", "0")).lower() in ("1", "true")
latest_signal_status = "NO_LATEST_LIVE_SIGNAL"
if latest_signal:
    if latest_auto:
        latest_signal_status = "LATEST_SIGNAL_AUTO_TRADED"
    elif latest_filter:
        if "risk-sized notional" in latest_filter:
            latest_signal_status = "LATEST_SIGNAL_BLOCKED_POSITION_SIZING"
        elif "ExpectedValueGate" in latest_filter:
            latest_signal_status = "LATEST_SIGNAL_BLOCKED_EXPECTED_VALUE"
        elif "AutoTrade:" in latest_filter:
            latest_signal_status = "LATEST_SIGNAL_BLOCKED_AUTOTRADE_GUARD"
        else:
            latest_signal_status = "LATEST_SIGNAL_FILTERED_OR_SHADOW"
    else:
        latest_signal_status = "LATEST_SIGNAL_NOT_AUTO_TRADED_NO_FILTER_REASON"

latest_signal_id = first_text(latest_signal.get("id")) if latest_signal else ""
latest_signal_created_at = parse_ts(latest_signal.get("created_at")) if latest_signal else None
latest_ev_live_signal_id = first_text(latest_ev.get("live_signal_id")) if latest_ev else ""
latest_ev_event_time = parse_ts(latest_ev.get("event_time")) if latest_ev else None
ev_gate_applies_to_latest_signal = False
if latest_ev:
    if not latest_signal:
        ev_gate_applies_to_latest_signal = True
    elif latest_ev_live_signal_id and latest_signal_id and latest_ev_live_signal_id == latest_signal_id:
        ev_gate_applies_to_latest_signal = True
    elif latest_ev_event_time and latest_signal_created_at and latest_ev_event_time >= latest_signal_created_at:
        ev_gate_applies_to_latest_signal = True

if ev_gate_status.startswith("BLOCK_") and not ev_gate_applies_to_latest_signal:
    ev_gate_status = "STALE_" + ev_gate_status

blockers = []
if not strategy_exists:
    blockers.append("STRATEGY_NOT_FOUND")
elif not strategy_enabled:
    blockers.append("STRATEGY_DISABLED")
if not (legacy_for_strategy or local_tv_for_strategy or primary == "TRADINGVIEW"):
    blockers.append("SIGNAL_SOURCE_POLICY_UNKNOWN")
if signal_source_gate.endswith("SUPPRESSED") or signal_source_gate.startswith("SIGNAL_SOURCE_POLICY_SUPPRESSES"):
    blockers.append(signal_source_gate)
if notify_only:
    blockers.append("STRATEGY_NOTIFY_ONLY")
if not entry_dedup_pass:
    blockers.append("ENTRY_DEDUP_FIRST_ENTRY_NOT_CLEAR")
if open_position_gate != "PASS":
    blockers.append(open_position_gate)
if ev_gate_status.startswith("BLOCK_"):
    blockers.append(ev_gate_status)
if sizing_status == "BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL":
    blockers.append(sizing_status)
if latest_signal_status.startswith("LATEST_SIGNAL_BLOCKED") and latest_signal_status not in blockers:
    blockers.append(latest_signal_status)

if latest_signal_status == "LATEST_SIGNAL_AUTO_TRADED":
    conclusion = "FIRST_ENTRY_EXECUTED_RECENTLY"
elif blockers:
    conclusion = "FIRST_ENTRY_BLOCKED_REVIEW_REQUIRED"
elif latest_signal_status == "NO_LATEST_LIVE_SIGNAL":
    conclusion = "FIRST_ENTRY_GATES_CLEAR_BUT_NO_RECENT_SIGNAL"
elif latest_signal_status.startswith("LATEST_SIGNAL_BLOCKED"):
    conclusion = latest_signal_status
else:
    conclusion = "FIRST_ENTRY_REVIEW_INCONCLUSIVE"

print("[strategy508-first-entry-readiness] read-only production evidence check")
print("scope=READ_ONLY; server-local MCP read-only tools plus direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} limit={limit}")
print("")
print("Signal Source Gate:")
print(f"  trading_signal_source_primary={primary}")
print(f"  legacy_live_evaluator_enabled={yesno(legacy_enabled)}")
print(f"  legacy_secondary_evaluator_enabled={yesno(secondary_enabled)}")
print(f"  legacy_secondary_allowed_strategy_ids={sorted(secondary_ids)}")
print(f"  local_tradingview_enabled={os.environ.get('TRADINGVIEW_LOCAL_ENABLED', '')}")
print(f"  local_tradingview_strategy_id={local_tv_strategy_id}")
print(f"  local_tradingview_execution_mode={os.environ.get('TRADINGVIEW_LOCAL_EXECUTION_MODE', '')}")
print(f"  strategy508_signal_source_gate={signal_source_gate}")
print("")
print("Strategy Config:")
print(f"  strategy_exists={yesno(strategy_exists)}")
print(f"  strategy_enabled={yesno(strategy_enabled)}")
print(f"  strategy_name={strategy.get('name', 'N/A')}")
print(f"  strategy_type={strategy.get('strategy_type', 'N/A')}")
print(f"  strategy_symbols={strategy.get('symbols', 'N/A')}")
print(f"  strategy_kline_source={strategy.get('kline_source', 'N/A')}")
print(f"  strategy_notify_only={yesno(notify_only)}")
print(f"  strategy_entry_dedup_open_exposure_scope={entry_scope}")
print(f"  strategy_pre_trade_ev_gate_enabled={strategy.get('ev_gate_enabled', 'true')}")
print(f"  strategy_pre_trade_min_expected_r={strategy.get('min_expected_r', '0.20')}")
print(f"  strategy_entry_dedup_decision_mode={strategy.get('entry_dedup_decision_mode', 'BLOCK')}")
print("")
print("First-Entry Exposure Gates:")
print(f"  first_entry_semantics={yesno(first_entry_semantics)}")
print(f"  staged_add_preview_applicable={yesno(staged_add_preview_applicable)}")
print(f"  entry_dedup_first_entry_pass={yesno(entry_dedup_pass)}")
for key in open_fields:
    print(f"  {key}={open_summary.get(key, '0')}")
print(f"  max_open_positions_config={max_open}")
print(f"  allow_concurrent_on_same_symbol={yesno(allow_concurrent_symbol)}")
print(f"  auto_trade_open_position_gate={open_position_gate}")
print("")
print("Latest 508 Signal:")
if latest_signal:
    for key in signal_fields:
        print(f"  latest_signal_{key}={latest_signal.get(key, '')}")
else:
    print("  latest_signal_missing=true")
print(f"  latest_signal_status={latest_signal_status}")
print("")
print("EV / Sizing Gates:")
print(f"  latest_ev_audit_event_time={latest_ev.get('event_time', '') if latest_ev else ''}")
print(f"  latest_ev_live_signal_id={latest_ev_live_signal_id}")
print(f"  latest_ev_gate_applies_to_latest_signal={yesno(ev_gate_applies_to_latest_signal)}")
print(f"  latest_ev_gate_status={ev_gate_status}")
print(f"  latest_expected_r={expected_r if expected_r is not None else 'N/A'}")
print(f"  latest_min_expected_r={min_expected_r if min_expected_r is not None else 'N/A'}")
print(f"  position_sizing_min_notional_floor_enabled={os.environ.get('TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED', '')}")
print(f"  position_sizing_min_notional_floor_max_risk_usdt={os.environ.get('TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT', '')}")
print(f"  first_entry_position_sizing_status={sizing_status}")
print("  first_entry_position_sizing_lines=" + json.dumps(sizing_lines[:12], ensure_ascii=False))
print("")
print("Recent Audit Counts:")
if not audit_counts:
    print("  - NONE=0")
else:
    for row in audit_counts:
        event_type = row[0] if len(row) > 0 else ""
        blocker = row[1] if len(row) > 1 else ""
        reason = row[2] if len(row) > 2 else ""
        cnt = row[3] if len(row) > 3 else "0"
        latest = row[4] if len(row) > 4 else "NONE"
        safe_reason = reason.replace("\n", " ")[:120]
        print(f"  - eventType={event_type} blocker={blocker or 'NONE'} count={cnt} latest={latest} reason={safe_reason or 'NONE'}")
print("")
print("Conclusion:")
print("  strategy508_first_entry_blockers=" + json.dumps(blockers, ensure_ascii=False))
print(f"  strategy508_first_entry_conclusion={conclusion}")
print("  strategy508_first_entry_next_action=" + (
    "Do not use getStagedAddReadiness as first-entry evidence; it is only applicable when an existing auto-traded position exists."
    if not staged_add_preview_applicable else
    "Staged-add preview is applicable because an existing auto-traded same-strategy position is open; review add-specific blockers separately."
))
print("  notAuthorization=read-only first-entry evidence only; does not authorize live trading, strategy activation, EntryDedup/DataFreshness relaxation, staged-add execution, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[strategy508-first-entry-readiness] OK read-only check complete")
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
    throw "Strategy 508 first-entry readiness smoke failed with exit code $LASTEXITCODE"
}
