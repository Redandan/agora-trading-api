param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 508,
    [string]$IntervalCode = "1h",
    [int]$Hours = 720,
    [int]$McpDays = 14,
    [int]$Limit = 50
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

if ($Hours -lt 1 -or $Hours -gt 2160) {
    throw "Hours must be between 1 and 2160."
}

if ($McpDays -lt 1 -or $McpDays -gt 90) {
    throw "McpDays must be between 1 and 90."
}

if ($Limit -lt 1 -or $Limit -gt 200) {
    throw "Limit must be between 1 and 200."
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
MCP_DAYS='__MCP_DAYS__'
LIMIT='__LIMIT__'

fail() {
  echo "[entry-dedup-semantics-gate-preflight] FAIL: $*" >&2
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
command -v curl >/dev/null 2>&1 || fail "curl is not available on server"
command -v python3 >/dev/null 2>&1 || fail "python3 is not available on server"

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

APP_PID=""
APP_PORT=""
MCP_KEY=""
if [ -f app.pid ]; then
  APP_PID="$(cat app.pid || true)"
  if [ -n "$APP_PID" ] && [ -r "/proc/$APP_PID/environ" ]; then
    APP_PORT="$(lsof -Pan -p "$APP_PID" -i 2>/dev/null | awk '/LISTEN/ {print $9}' | sed 's/.*://' | head -1 || true)"
    MCP_KEY="$(tr '\0' '\n' <"/proc/$APP_PID/environ" | awk -F= '/^(MCP_OPS_KEY|TRADING_MCP_KEY)=/{print $2; exit}' || true)"
  fi
fi

export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
export SYMBOL STRATEGY_ID INTERVAL_CODE HOURS MCP_DAYS LIMIT MYSQL_HOST="$host" MYSQL_PORT="$port" MYSQL_USER="$SPRING_DATASOURCE_USERNAME" MYSQL_DATABASE="$database" APP_PORT MCP_KEY

python3 - <<'PY'
import csv
import json
import os
import re
import subprocess
import sys
import tempfile

symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
interval_code = os.environ["INTERVAL_CODE"]
hours = int(os.environ["HOURS"])
mcp_days = int(os.environ["MCP_DAYS"])
limit = int(os.environ["LIMIT"])
app_port = os.environ.get("APP_PORT", "")
mcp_key = os.environ.get("MCP_KEY", "")

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

def mcp_call(name, arguments):
    if not app_port or not mcp_key:
        return {"ok": False, "text": "MCP_UNAVAILABLE_NO_PORT_OR_KEY"}
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
        json.dump(payload, handle, separators=(",", ":"))
        path = handle.name
    try:
        proc = subprocess.run(
            [
                "curl",
                "-fsS",
                "-H", "Content-Type: application/json",
                "-H", f"Authorization: Bearer {mcp_key}",
                "--data-binary", f"@{path}",
                f"http://127.0.0.1:{app_port}/api/mcp",
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    if proc.returncode != 0:
        return {"ok": False, "text": proc.stderr.strip() or f"curl_exit_{proc.returncode}"}
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {"ok": False, "text": "MCP_INVALID_JSON"}
    if "error" in data:
        return {"ok": False, "text": json.dumps(data["error"], separators=(",", ":"))}
    content = data.get("result", {}).get("content", [])
    text = "\n".join(str(item.get("text", "")) for item in content)
    return {"ok": True, "text": text}

def extract(pattern, text, default=""):
    match = re.search(pattern, text)
    return match.group(1) if match else default

symbol_sql = esc(symbol)
interval_sql = esc(interval_code)

candidate_sql = f"""
SELECT
  a.id AS audit_id,
  DATE_FORMAT(a.event_time, '%Y-%m-%dT%H:%i:%s') AS event_time,
  DATE_FORMAT(COALESCE(a.bar_open_time, a.event_time), '%Y-%m-%dT%H:%i:%s') AS anchor_time,
  COALESCE(a.live_signal_id, '') AS live_signal_id,
  COALESCE(a.reason, '') AS reason
FROM bt_decision_audit a FORCE INDEX (idx_audit_symbol_time)
WHERE a.symbol = '{symbol_sql}'
  AND a.strategy_id = {strategy_id}
  AND COALESCE(a.interval_code, 'N/A') = '{interval_sql}'
  AND a.event_type = 'ENTRY_SKIP'
  AND a.blocker = 'EntryDedup'
  AND a.reason = 'same strategy/symbol/interval LONG exposure already exists'
  AND a.event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
ORDER BY a.event_time ASC
LIMIT {limit}
"""

exposure_sql = f"""
SELECT
  COUNT(*) AS open_signal_rows,
  COALESCE(SUM(CASE WHEN auto_traded = 1 THEN 1 ELSE 0 END), 0) AS auto_traded_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 THEN 1 ELSE 0 END), 0) AS non_auto_open_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(traded_qty, 0) = 0 AND COALESCE(oco_qty, 0) = 0 THEN 1 ELSE 0 END), 0) AS non_auto_zero_qty_rows,
  COALESCE(SUM(CASE WHEN COALESCE(auto_traded, 0) <> 1 AND COALESCE(filter_reason, '') LIKE 'EventRiskControl:%' THEN 1 ELSE 0 END), 0) AS non_auto_eventrisk_rows,
  COALESCE(SUM(CASE WHEN oco_order_list_id IS NULL OR oco_order_list_id = '' THEN 1 ELSE 0 END), 0) AS missing_oco_rows
FROM bt_live_signal
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND COALESCE(side, 'LONG') = 'LONG'
  AND exit_time IS NULL
"""

runtime_evidence_sql = f"""
SELECT
  COUNT(*) AS runtime_evidence_rows,
  COALESCE(SUM(CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ev_result_json, '$.status')), '') <> 'NOT_EVALUATED' THEN 1 ELSE 0 END), 0) AS runtime_ev_evaluated_rows,
  COALESCE(SUM(CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.entryPlan.status')), '') <> '' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.entryPlan.status')), '') <> 'NOT_CREATED' THEN 1 ELSE 0 END), 0) AS runtime_entry_plan_rows,
  COALESCE(SUM(CASE WHEN COALESCE(oco_plan_created, 0) = 1 OR (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.ocoPlan.status')), '') <> '' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.ocoPlan.status')), '') <> 'NOT_CREATED') THEN 1 ELSE 0 END), 0) AS runtime_oco_plan_rows,
  COALESCE(SUM(CASE WHEN COALESCE(order_sent, 0) = 1 THEN 1 ELSE 0 END), 0) AS runtime_order_sent_rows,
  COALESCE(SUM(CASE WHEN (COALESCE(exposure_snapshot_json, '') LIKE '%dailyCapSnapshot%' OR COALESCE(exposure_snapshot_json, '') LIKE '%maxLossSnapshot%' OR COALESCE(exposure_snapshot_json, '') LIKE '%dailyCapLimit%' OR COALESCE(exposure_snapshot_json, '') LIKE '%candidateMaxLossUsdt%') THEN 1 ELSE 0 END), 0) AS runtime_budget_snapshot_rows
FROM bt_runtime_decision_evidence
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND evidence_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
"""

global_gate_sql = f"""
SELECT
  COALESCE(SUM(CASE WHEN event_type = 'ATTENTION_HIT' AND (blocker = 'ExpectedValueGate' OR reason LIKE '%ExpectedValueGate%' OR context_json LIKE '%expected%') THEN 1 ELSE 0 END), 0) AS ev_pass_like_rows,
  COALESCE(SUM(CASE WHEN event_type = 'FILTER_BLOCK' AND blocker = 'ExpectedValueGate' THEN 1 ELSE 0 END), 0) AS ev_block_rows,
  COALESCE(SUM(CASE WHEN event_type = 'FILTER_BLOCK' AND blocker = 'EventRiskControl' THEN 1 ELSE 0 END), 0) AS eventrisk_block_rows,
  COALESCE(SUM(CASE WHEN event_type = 'ENTRY_SKIP' AND blocker = 'DuplicateBar' THEN 1 ELSE 0 END), 0) AS duplicate_bar_rows,
  COALESCE(SUM(CASE WHEN (blocker LIKE '%Daily%' OR blocker LIKE '%Loss%' OR reason LIKE '%daily learning cap%' OR reason LIKE '%cap reached%' OR reason LIKE '%max loss%') THEN 1 ELSE 0 END), 0) AS cap_or_loss_rows,
  COALESCE(SUM(CASE WHEN (context_json LIKE '%dailyCapSnapshot%' OR context_json LIKE '%maxLossSnapshot%' OR context_json LIKE '%dailyCapLimit%' OR context_json LIKE '%candidateMaxLossUsdt%') THEN 1 ELSE 0 END), 0) AS budget_snapshot_rows
FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND event_time >= UTC_TIMESTAMP() - INTERVAL {hours} HOUR
"""

candidate_fields = ["audit_id", "event_time", "anchor_time", "live_signal_id", "reason"]
candidates = [row_dict(candidate_fields, row) for row in run_query(candidate_sql)]
exposure_fields = [
    "open_signal_rows",
    "auto_traded_open_rows",
    "non_auto_open_rows",
    "non_auto_zero_qty_rows",
    "non_auto_eventrisk_rows",
    "missing_oco_rows",
]
exposure = row_dict(exposure_fields, run_query(exposure_sql)[0])
runtime_fields = [
    "runtime_evidence_rows",
    "runtime_ev_evaluated_rows",
    "runtime_entry_plan_rows",
    "runtime_oco_plan_rows",
    "runtime_order_sent_rows",
    "runtime_budget_snapshot_rows",
]
runtime_evidence = row_dict(runtime_fields, run_query(runtime_evidence_sql)[0])
gate_fields = ["ev_pass_like_rows", "ev_block_rows", "eventrisk_block_rows", "duplicate_bar_rows", "cap_or_loss_rows", "budget_snapshot_rows"]
global_gates = row_dict(gate_fields, run_query(global_gate_sql)[0])

def nearby_counts(candidate):
    anchor = esc(candidate["anchor_time"])
    sql = f"""
SELECT
  COALESCE(SUM(CASE WHEN event_type = 'ATTENTION_HIT' AND (blocker = 'ExpectedValueGate' OR reason LIKE '%ExpectedValueGate%' OR context_json LIKE '%expected%') THEN 1 ELSE 0 END), 0) AS ev_pass_like_rows,
  COALESCE(SUM(CASE WHEN event_type = 'FILTER_BLOCK' AND blocker = 'ExpectedValueGate' THEN 1 ELSE 0 END), 0) AS ev_block_rows,
  COALESCE(SUM(CASE WHEN event_type = 'FILTER_BLOCK' AND blocker = 'EventRiskControl' THEN 1 ELSE 0 END), 0) AS eventrisk_block_rows,
  COALESCE(SUM(CASE WHEN event_type = 'ENTRY_SKIP' AND blocker = 'DuplicateBar' THEN 1 ELSE 0 END), 0) AS duplicate_bar_rows,
  COALESCE(SUM(CASE WHEN (blocker LIKE '%Daily%' OR blocker LIKE '%Loss%' OR reason LIKE '%daily learning cap%' OR reason LIKE '%cap reached%' OR reason LIKE '%max loss%') THEN 1 ELSE 0 END), 0) AS cap_or_loss_rows,
  COALESCE(SUM(CASE WHEN (context_json LIKE '%dailyCapSnapshot%' OR context_json LIKE '%maxLossSnapshot%' OR context_json LIKE '%dailyCapLimit%' OR context_json LIKE '%candidateMaxLossUsdt%') THEN 1 ELSE 0 END), 0) AS budget_snapshot_rows,
  COALESCE(SUM(CASE WHEN event_type = 'ENTRY_SKIP' AND blocker = 'EntryDedup' THEN 1 ELSE 0 END), 0) AS entry_dedup_rows
FROM bt_decision_audit FORCE INDEX (idx_audit_symbol_time)
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND event_time BETWEEN DATE_SUB('{anchor}', INTERVAL 2 HOUR) AND DATE_ADD('{anchor}', INTERVAL 2 HOUR)
"""
    fields = ["ev_pass_like_rows", "ev_block_rows", "eventrisk_block_rows", "duplicate_bar_rows", "cap_or_loss_rows", "budget_snapshot_rows", "entry_dedup_rows"]
    return row_dict(fields, run_query(sql)[0])

def nearby_runtime_counts(candidate):
    anchor = esc(candidate["anchor_time"])
    sql = f"""
SELECT
  COUNT(*) AS runtime_evidence_rows,
  COALESCE(SUM(CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(ev_result_json, '$.status')), '') <> 'NOT_EVALUATED' THEN 1 ELSE 0 END), 0) AS runtime_ev_evaluated_rows,
  COALESCE(SUM(CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.entryPlan.status')), '') <> '' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.entryPlan.status')), '') <> 'NOT_CREATED' THEN 1 ELSE 0 END), 0) AS runtime_entry_plan_rows,
  COALESCE(SUM(CASE WHEN COALESCE(oco_plan_created, 0) = 1 OR (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.ocoPlan.status')), '') <> '' AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(execution_preview_json, '$.ocoPlan.status')), '') <> 'NOT_CREATED') THEN 1 ELSE 0 END), 0) AS runtime_oco_plan_rows,
  COALESCE(SUM(CASE WHEN COALESCE(order_sent, 0) = 1 THEN 1 ELSE 0 END), 0) AS runtime_order_sent_rows,
  COALESCE(SUM(CASE WHEN (COALESCE(exposure_snapshot_json, '') LIKE '%dailyCapSnapshot%' OR COALESCE(exposure_snapshot_json, '') LIKE '%maxLossSnapshot%' OR COALESCE(exposure_snapshot_json, '') LIKE '%dailyCapLimit%' OR COALESCE(exposure_snapshot_json, '') LIKE '%candidateMaxLossUsdt%') THEN 1 ELSE 0 END), 0) AS runtime_budget_snapshot_rows
FROM bt_runtime_decision_evidence
WHERE symbol = '{symbol_sql}'
  AND strategy_id = {strategy_id}
  AND COALESCE(interval_code, 'N/A') = '{interval_sql}'
  AND evidence_time BETWEEN DATE_SUB('{anchor}', INTERVAL 2 HOUR) AND DATE_ADD('{anchor}', INTERVAL 2 HOUR)
"""
    return row_dict(runtime_fields, run_query(sql)[0])

candidate_previews = []
for candidate in candidates:
    counts = nearby_counts(candidate)
    runtime_counts = nearby_runtime_counts(candidate)
    candidate_previews.append({
        **candidate,
        **{k: as_int(v) for k, v in counts.items()},
        **{k: as_int(v) for k, v in runtime_counts.items()},
    })

eventrisk_mcp = mcp_call("getEventRiskControlStatus", {"symbol": symbol})
ev_mcp = mcp_call("getExpectedValueGateStats", {"symbol": symbol, "days": mcp_days})

eventrisk_text = eventrisk_mcp["text"]
ev_text = ev_mcp["text"]
eventrisk_level = extract(r"riskLevel=([A-Z0-9_]+)", eventrisk_text, "UNKNOWN")
eventrisk_policy = extract(r"policy=([^\n\\\"]+)", eventrisk_text, "UNKNOWN")
ev_acceptance = extract(r"acceptance:\s*([A-Z_]+)", ev_text, "UNKNOWN")
ev_passed = extract(r"passedExpectedValueGate=([0-9]+)", ev_text, "0")
ev_blocked = extract(r"blockedByExpectedValueGate=([0-9]+)", ev_text, "0")

ev_candidate_pass_rows = sum(1 for item in candidate_previews if item["ev_pass_like_rows"] > 0)
ev_candidate_block_rows = sum(1 for item in candidate_previews if item["ev_block_rows"] > 0)
eventrisk_candidate_block_rows = sum(1 for item in candidate_previews if item["eventrisk_block_rows"] > 0)
duplicate_candidate_rows = sum(1 for item in candidate_previews if item["duplicate_bar_rows"] > 0)
cap_loss_candidate_rows = sum(1 for item in candidate_previews if item["cap_or_loss_rows"] > 0)
budget_snapshot_candidate_rows = sum(1 for item in candidate_previews if item["budget_snapshot_rows"] > 0 or item["runtime_budget_snapshot_rows"] > 0)
runtime_candidate_rows = sum(1 for item in candidate_previews if item["runtime_evidence_rows"] > 0)
runtime_ev_candidate_rows = sum(1 for item in candidate_previews if item["runtime_ev_evaluated_rows"] > 0)
runtime_entry_plan_candidate_rows = sum(1 for item in candidate_previews if item["runtime_entry_plan_rows"] > 0)
runtime_oco_plan_candidate_rows = sum(1 for item in candidate_previews if item["runtime_oco_plan_rows"] > 0)
runtime_order_sent_candidate_rows = sum(1 for item in candidate_previews if item["runtime_order_sent_rows"] > 0)

non_auto_eventrisk_rows = as_int(exposure.get("non_auto_eventrisk_rows"))
missing_oco_rows = as_int(exposure.get("missing_oco_rows"))
auto_rows = as_int(exposure.get("auto_traded_open_rows"))
non_auto_zero_rows = as_int(exposure.get("non_auto_zero_qty_rows"))

if ev_mcp["ok"] and ev_acceptance.startswith("PASS"):
    ev_gate_status = "PARTIAL_RUNTIME_PASS_CANDIDATE_SNAPSHOT_MISSING" if runtime_ev_candidate_rows < len(candidates) else "CLEARED_RUNTIME_AND_CANDIDATE_ROWS"
else:
    ev_gate_status = "MISSING_OR_FAILED_EXPECTED_VALUE_RUNTIME_EVIDENCE"

if eventrisk_mcp["ok"] and eventrisk_level == "R0" and "allowed" in eventrisk_policy.lower():
    eventrisk_gate_status = "CLEARED_CURRENT_R0_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW" if (non_auto_eventrisk_rows or eventrisk_candidate_block_rows) else "CLEARED_CURRENT_R0"
else:
    eventrisk_gate_status = "BLOCKED_OR_UNKNOWN_CURRENT_EVENTRISK"

duplicate_gate_status = "PARTIAL_ENTRY_DEDUP_CANDIDATES_SEEN_EXACT_HASH_NOT_PROVEN" if candidates else "NO_RECENT_ENTRY_DEDUP_CANDIDATES"
if cap_loss_candidate_rows > 0:
    budget_gate_status = "BLOCKED_CANDIDATE_CAP_OR_LOSS_ROWS_PRESENT"
elif budget_snapshot_candidate_rows > 0:
    budget_gate_status = "CLEARED_CANDIDATE_BUDGET_SNAPSHOT_PRESENT"
elif as_int(global_gates.get("budget_snapshot_rows")) > 0 or as_int(runtime_evidence.get("runtime_budget_snapshot_rows")) > 0:
    budget_gate_status = "PARTIAL_GLOBAL_BUDGET_SNAPSHOT_NOT_CANDIDATE"
elif as_int(global_gates.get("cap_or_loss_rows")) > 0:
    budget_gate_status = "PARTIAL_GLOBAL_CAP_OR_LOSS_ROWS_NOT_CANDIDATE_BLOCKER"
else:
    budget_gate_status = "MISSING_BUDGET_SNAPSHOT_NO_CAP_LOSS_ROWS_OBSERVED"
oco_gate_status = "BLOCKED_MISSING_OCO_ROUTE_OR_NON_AUTO_ZERO_QTY" if (missing_oco_rows > 0 or auto_rows == 0 or non_auto_zero_rows > 0) else "PARTIAL_OPEN_SIGNAL_OCO_PRESENT"
runtime_coverage_status = "MISSING_CANDIDATE_RUNTIME_EVIDENCE_SNAPSHOTS"
if candidates and runtime_candidate_rows == len(candidates):
    runtime_coverage_status = "PARTIAL_RUNTIME_EVIDENCE_PRESENT_EV_OR_OCO_MISSING"
    if runtime_ev_candidate_rows == len(candidates) and runtime_oco_plan_candidate_rows == len(candidates):
        runtime_coverage_status = "CLEARED_CANDIDATE_RUNTIME_EVIDENCE_COVERAGE"
elif runtime_candidate_rows > 0:
    runtime_coverage_status = "PARTIAL_RUNTIME_EVIDENCE_CANDIDATE_COVERAGE"

blocking_statuses = [ev_gate_status, eventrisk_gate_status, duplicate_gate_status, budget_gate_status, oco_gate_status, runtime_coverage_status]
if not candidates:
    overall_status = "NO_RECENT_ENTRY_DEDUP_CANDIDATES_NOT_LIVE"
elif all(status.startswith("CLEARED") for status in blocking_statuses):
    overall_status = "READY_FOR_SEPARATE_REVIEW_NOT_LIVE"
else:
    overall_status = "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE"

packet = {
    "packetType": "ENTRY_DEDUP_SEMANTICS_GATE_PREFLIGHT_PACKET",
    "status": overall_status,
    "symbol": symbol,
    "strategyId": strategy_id,
    "intervalCode": interval_code,
    "scope": "READ_ONLY",
    "candidateRows": len(candidates),
    "gateStatuses": {
        "expectedValueGate": ev_gate_status,
        "eventRiskControl": eventrisk_gate_status,
        "duplicateProtection": duplicate_gate_status,
        "dailyCapMaxLossBudget": budget_gate_status,
        "ocoFeasibility": oco_gate_status,
        "runtimeEvidenceCoverage": runtime_coverage_status,
    },
    "runtimeMcpEvidence": {
        "eventRiskOk": eventrisk_mcp["ok"],
        "eventRiskLevel": eventrisk_level,
        "eventRiskPolicy": eventrisk_policy,
        "expectedValueOk": ev_mcp["ok"],
        "expectedValueAcceptance": ev_acceptance,
        "passedExpectedValueGate": ev_passed,
        "blockedByExpectedValueGate": ev_blocked,
    },
    "dbEvidence": {
        "openSignalRows": as_int(exposure.get("open_signal_rows")),
        "autoTradedOpenRows": auto_rows,
        "nonAutoZeroQtyRows": non_auto_zero_rows,
        "nonAutoEventRiskRows": non_auto_eventrisk_rows,
        "missingOcoRows": missing_oco_rows,
        "globalGateRows": {k: as_int(v) for k, v in global_gates.items()},
        "runtimeEvidenceRows": {k: as_int(v) for k, v in runtime_evidence.items()},
        "candidateGateRows": {
            "evPassLikeRows": ev_candidate_pass_rows,
            "evBlockRows": ev_candidate_block_rows,
            "eventRiskBlockRows": eventrisk_candidate_block_rows,
            "duplicateBarRows": duplicate_candidate_rows,
            "capOrLossRows": cap_loss_candidate_rows,
            "budgetSnapshotRows": budget_snapshot_candidate_rows,
            "runtimeEvidenceRows": runtime_candidate_rows,
            "runtimeEvEvaluatedRows": runtime_ev_candidate_rows,
            "runtimeEntryPlanRows": runtime_entry_plan_candidate_rows,
            "runtimeOcoPlanRows": runtime_oco_plan_candidate_rows,
            "runtimeOrderSentRows": runtime_order_sent_candidate_rows,
        },
    },
    "requiredBeforeAnyMutation": [
        "candidate-level ExpectedValueGate snapshot for each proposed EntryDedup bypass",
        "EventRiskControl clear or separately approved for each candidate",
        "exact duplicate hash and same-candidate replay protection",
        "daily cap and max-loss budget snapshot",
        "OCO exact route and lower-timeframe or exchange-side proof",
        "explicit operator approval and separate deploy/env authorization",
    ],
    "orderAllowed": False,
    "entryDedupPolicyChangeAllowed": False,
    "livePolicyChangeAllowed": False,
    "positionOrOcoMutationAllowed": False,
    "deployOrEnvChangeAllowed": False,
}

print("[entry-dedup-semantics-gate-preflight] read-only production gate preflight")
print("scope=READ_ONLY; direct MySQL SELECTs and server-local read-only MCP calls only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} strategyId={strategy_id} intervalCode={interval_code} hours={hours} mcpDays={mcp_days} limit={limit}")
print("")
print("Gate Summary:")
print(f"  entry_dedup_gate_preflight_status={overall_status}")
print(f"  candidate_rows={len(candidates)}")
print(f"  expected_value_gate_status={ev_gate_status}")
print(f"  event_risk_control_status={eventrisk_gate_status}")
print(f"  duplicate_protection_status={duplicate_gate_status}")
print(f"  daily_cap_max_loss_budget_status={budget_gate_status}")
print(f"  oco_feasibility_status={oco_gate_status}")
print(f"  runtime_evidence_coverage_status={runtime_coverage_status}")
print("")
print("Runtime MCP Evidence:")
print(f"  event_risk_mcp_ok={str(eventrisk_mcp['ok']).lower()}")
print(f"  event_risk_level={eventrisk_level}")
print(f"  event_risk_policy={eventrisk_policy}")
print(f"  expected_value_mcp_ok={str(ev_mcp['ok']).lower()}")
print(f"  expected_value_acceptance={ev_acceptance}")
print(f"  passed_expected_value_gate={ev_passed}")
print(f"  blocked_by_expected_value_gate={ev_blocked}")
print("")
print("DB Gate Evidence:")
for key in exposure_fields:
    print(f"  {key}={exposure.get(key, '0')}")
for key in gate_fields:
    print(f"  global_{key}={global_gates.get(key, '0')}")
for key in runtime_fields:
    print(f"  global_{key}={runtime_evidence.get(key, '0')}")
print(f"  candidate_ev_pass_like_rows={ev_candidate_pass_rows}")
print(f"  candidate_ev_block_rows={ev_candidate_block_rows}")
print(f"  candidate_eventrisk_block_rows={eventrisk_candidate_block_rows}")
print(f"  candidate_duplicate_bar_rows={duplicate_candidate_rows}")
print(f"  candidate_cap_or_loss_rows={cap_loss_candidate_rows}")
print(f"  candidate_budget_snapshot_rows={budget_snapshot_candidate_rows}")
print(f"  candidate_runtime_evidence_rows={runtime_candidate_rows}")
print(f"  candidate_runtime_ev_evaluated_rows={runtime_ev_candidate_rows}")
print(f"  candidate_runtime_entry_plan_rows={runtime_entry_plan_candidate_rows}")
print(f"  candidate_runtime_oco_plan_rows={runtime_oco_plan_candidate_rows}")
print(f"  candidate_runtime_order_sent_rows={runtime_order_sent_candidate_rows}")
print("")
print("Candidate Examples:")
if not candidate_previews:
    print("  - NONE")
else:
    for item in candidate_previews[: min(20, len(candidate_previews))]:
        print(
            "  - auditId={audit_id} event={event_time} anchor={anchor_time} liveSignalId={live_signal_id} "
            "evPassLike={ev_pass_like_rows} evBlock={ev_block_rows} eventRiskBlock={eventrisk_block_rows} "
            "duplicateBar={duplicate_bar_rows} capOrLoss={cap_or_loss_rows} budgetSnapshot={budget_snapshot_rows}/{runtime_budget_snapshot_rows} runtimeRows={runtime_evidence_rows} "
            "runtimeEv={runtime_ev_evaluated_rows} runtimeEntryPlan={runtime_entry_plan_rows} "
            "runtimeOcoPlan={runtime_oco_plan_rows} entryDedupNearby={entry_dedup_rows} reason={reason}".format(
                **{**item, "reason": (item.get("reason") or "NONE")[:120]}
            )
        )
print("")
print("Conclusion:")
print("  entry_dedup_semantics_gate_preflight_packet=" + json.dumps(packet, separators=(",", ":")))
print(f"  entry_dedup_semantics_gate_preflight_status={overall_status}")
print("  order_allowed=false")
print("  entry_dedup_policy_change_allowed=false")
print("  live_policy_change_allowed=false")
print("  position_or_oco_mutation_allowed=false")
print("  deploy_or_env_change_allowed=false")
print("  notAuthorization=read-only EntryDedup gate preflight only; does not authorize EntryDedup relaxation, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import")
print("")
print("[entry-dedup-semantics-gate-preflight] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__STRATEGY_ID__", [string]$StrategyId).
    Replace("__INTERVAL_CODE__", $IntervalCode).
    Replace("__HOURS__", [string]$Hours).
    Replace("__MCP_DAYS__", [string]$McpDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "EntryDedup semantics gate preflight smoke failed with exit code $LASTEXITCODE"
}
