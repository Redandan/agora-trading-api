param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

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
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) {
    throw "LookbackHours must be between 24 and 720."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
MCP_URL="http://127.0.0.1:${PORT}/api/mcp"
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export MCP_URL MCP_KEY SYMBOL='__SYMBOL__' LOOKBACK_HOURS='__LOOKBACK_HOURS__' REQUIRE_READY='__REQUIRE_READY__' ENV_FILE='__ENVFILE__'

python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

mcp_url = os.environ["MCP_URL"]
mcp_key = os.environ["MCP_KEY"]
symbol = os.environ["SYMBOL"]
lookback_hours = int(os.environ["LOOKBACK_HOURS"])
require_ready = os.environ.get("REQUIRE_READY", "false").lower() == "true"
env_file = os.environ["ENV_FILE"]

def call_tool(name, arguments=None):
    payload = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments or {}
        }
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        mcp_url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + mcp_key
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{name} HTTP {exc.code}: {body[:500]}")
    message = json.loads(body)
    if "error" in message:
        raise RuntimeError(f"{name} JSON-RPC error: {message['error']}")
    result = message.get("result", {})
    content = result.get("content", [])
    texts = []
    for item in content:
        if isinstance(item, dict) and item.get("type") == "text":
            texts.append(str(item.get("text", "")))
    text = "\n".join(texts)
    stripped = text.strip()
    if stripped.startswith('"') and stripped.endswith('"'):
        try:
            decoded = json.loads(stripped)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def read_env():
    values = {}
    with open(env_file, "r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip().strip('"').strip("'")
            values[key.strip()] = value
    return values

def env_bool(values, key):
    value = values.get(key, "")
    if value == "":
        return "MISSING"
    return "true" if value.lower() == "true" else "false"

def env_present_any(values, keys):
    for key in keys:
        if values.get(key, ""):
            return "SET"
    return "EMPTY"

def extract_first(pattern, text, default=""):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def unique_append(items, value):
    if value and value not in items:
        items.append(value)

env_values = read_env()

grid_trend = call_tool("getGridTrendAdjustmentReview", {"symbol": symbol, "lookbackHours": lookback_hours})
grid_list = call_tool("listGrids", {})
alignment = call_tool("getGridPriceAlignment", {})
exposure = call_tool("getCurrentExposure", {})
event_risk = call_tool("getEventRiskControlStatus", {"symbol": symbol})

required_markers = [
    "boundary=READ_ONLY",
    "mutationAllowed=false",
    "orderAllowed=false",
    "gridMutationAllowed=false",
    "schedulerChangeAllowed=false",
    "telegramSendAllowed=false",
]
missing_markers = [marker for marker in required_markers if marker not in grid_trend]

recommendation = extract_first(r"recommendation=([A-Z0-9_:-]+)", grid_trend, "UNKNOWN")
trend = extract_first(r"trend=([A-Z0-9_:-]+)", grid_trend, "UNKNOWN")
trend_pct = extract_first(r"trendPct=([-+0-9.]+%?)", grid_trend, "UNKNOWN")
atr_pct = extract_first(r"atrPct=([-+0-9.]+%?)", grid_trend, "UNKNOWN")
event_risk_level = extract_first(r"riskLevel=([A-Z0-9_:-]+)", event_risk, "UNKNOWN")

def count_unique_grid_status(text, status):
    ids = set()
    for line in text.splitlines():
        if status not in line:
            continue
        match = re.search(r"#(\d+)", line)
        if match:
            ids.add(match.group(1))
    return len(ids)

active_grid_count = count_unique_grid_status(grid_list, "ACTIVE")
paused_grid_count = count_unique_grid_status(grid_list, "PAUSED")
closed_grid_count = count_unique_grid_status(grid_list, "CLOSED")

grid_flags = {
    "TRADING_GRID_ENABLED": env_bool(env_values, "TRADING_GRID_ENABLED"),
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED": env_bool(env_values, "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED"),
    "GRID_RECOVERY_ENABLED": env_bool(env_values, "GRID_RECOVERY_ENABLED"),
    "TRADING_OKX_ENABLED": env_bool(env_values, "TRADING_OKX_ENABLED"),
    "OKX_EARN_TOPUP_ENABLED": env_bool(env_values, "OKX_EARN_TOPUP_ENABLED"),
    "TRADING_OKX_API_KEY": env_present_any(env_values, ["TRADING_OKX_API_KEY", "OKX_API_KEY"]),
    "TRADING_OKX_SECRET_KEY": env_present_any(env_values, ["TRADING_OKX_SECRET_KEY", "OKX_SECRET_KEY"]),
    "TRADING_OKX_PASSPHRASE": env_present_any(env_values, ["TRADING_OKX_PASSPHRASE", "OKX_PASSPHRASE"]),
    "TELEGRAM_BOT_TOKEN": env_present_any(env_values, ["TELEGRAM_BOT_TOKEN"]),
}

blockers = []
warnings = []
required_evidence = []

for marker in missing_markers:
    unique_append(blockers, "GRID_TREND_READ_ONLY_MARKER_MISSING")
    unique_append(required_evidence, marker)

if active_grid_count > 0:
    unique_append(blockers, "ACTIVE_GRID_EXISTS_REVIEW_BEFORE_OPENING_NEW_GRID")
if paused_grid_count > 0:
    unique_append(warnings, "PAUSED_GRID_EXISTS_REVIEW_BEFORE_OPENING_NEW_GRID")
if "SELL_FAILED" in grid_list:
    unique_append(blockers, "HISTORICAL_GRID_SELL_FAILED_RECONCILIATION_REQUIRED")
if recommendation == "NO_ACTION_NO_ACTIVE_GRID":
    unique_append(blockers, "NO_REPLAYABLE_GRID_CANDIDATE_PLAN")
    unique_append(required_evidence, "explicit grid candidate range, spacing, capital, stop, and trend-regime rationale")
if trend in ("DOWN", "DOWN_STRONG", "UP_STRONG"):
    unique_append(blockers, "GRID_UNFAVORABLE_TREND_REGIME_" + trend)
    unique_append(required_evidence, "sideways or explicitly reviewed trend-regime evidence")
if event_risk_level != "R0":
    unique_append(blockers, "EVENT_RISK_NOT_R0")
    unique_append(required_evidence, "event-risk R0 or separate operator risk override")
if grid_flags["TRADING_GRID_ENABLED"] == "true":
    unique_append(blockers, "TRADING_GRID_ENABLED_ALREADY_TRUE")
if grid_flags["TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED"] == "true":
    unique_append(blockers, "GRID_AUTO_REBALANCE_SCHEDULER_ALREADY_TRUE")
if grid_flags["GRID_RECOVERY_ENABLED"] == "true":
    unique_append(warnings, "GRID_RECOVERY_ENABLED_REVIEW_SEPARATELY")
if grid_flags["TRADING_OKX_ENABLED"] != "true":
    unique_append(blockers, "TRADING_OKX_ENABLED_FALSE")
    unique_append(required_evidence, "separate operator authorization for TRADING_OKX_ENABLED=true before any grid order path")
for key in ("TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"):
    if grid_flags[key] != "SET":
        unique_append(blockers, "OKX_KEYS_MISSING")
if grid_flags["OKX_EARN_TOPUP_ENABLED"] == "true":
    unique_append(blockers, "OKX_EARN_TOPUP_ENABLED_MUST_REMAIN_FALSE_FOR_GRID_OPEN_REVIEW")
if grid_flags["TELEGRAM_BOT_TOKEN"] != "SET":
    unique_append(warnings, "TELEGRAM_ALERTING_UNAVAILABLE")

if "Grid: $0.00" not in exposure and "Grid $0.00" not in exposure and "active grid: 0" not in exposure.lower():
    unique_append(warnings, "GRID_EXPOSURE_REVIEW_REQUIRED")
if "無 ACTIVE Grid" not in alignment and "No ACTIVE Grid" not in alignment:
    unique_append(warnings, "GRID_ALIGNMENT_REVIEW_REQUIRED")

status = "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION" if not blockers else "BLOCKED_GRID_OPEN_READINESS_NOT_MUTATION"
next_action = "Prepare separate operator env/order authorization packet only after blockers are empty." if not blockers else "Resolve listed blockers with read-only evidence before any grid enablement request."
packet = {
    "packetType": "GRID_OPEN_READINESS_PACKET",
    "scope": "READ_ONLY",
    "symbol": symbol,
    "lookbackHours": lookback_hours,
    "mcpUrl": mcp_url,
    "activeGridCount": active_grid_count,
    "pausedGridCount": paused_grid_count,
    "closedGridCount": closed_grid_count,
    "recommendation": recommendation,
    "trend": trend,
    "trendPct": trend_pct,
    "atrPct": atr_pct,
    "eventRiskLevel": event_risk_level,
    "gridRuntimeFlags": grid_flags,
    "blockers": blockers,
    "requiredEvidence": required_evidence,
    "warnings": warnings,
    "status": status,
    "notAuthorization": "read-only grid open readiness only; does not create/pause/resume/close/rebalance grid, enable scheduler, place orders, send Telegram, mutate DB/OCO/grid/fund/Earn/exchange, deploy, restart, or change production env"
}

print("[grid-open-readiness] read-only packet")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol}")
print(f"lookbackHours={lookback_hours}")
print(f"active_grid_count={active_grid_count}")
print(f"paused_grid_count={paused_grid_count}")
print(f"closed_grid_count={closed_grid_count}")
print(f"grid_trend_recommendation={recommendation}")
print(f"trend={trend}")
print(f"trendPct={trend_pct}")
print(f"atrPct={atr_pct}")
print(f"event_risk_level={event_risk_level}")
print("grid_runtime_flags=" + json.dumps(grid_flags, sort_keys=True, separators=(",", ":")))
print("grid_open_readiness_blockers=" + json.dumps(blockers, separators=(",", ":")))
print("grid_open_readiness_required_evidence=" + json.dumps(required_evidence, separators=(",", ":")))
print("grid_open_readiness_warnings=" + json.dumps(warnings, separators=(",", ":")))
print("grid_open_readiness_packet=" + json.dumps(packet, sort_keys=True, separators=(",", ":")))
print(f"grid_open_readiness_status={status}")
print(f"grid_open_readiness_next_action={next_action}")
print("notAuthorization=read-only grid open readiness only; does not create/pause/resume/close/rebalance grid, enable scheduler, place orders, send Telegram, mutate DB/OCO/grid/fund/Earn/exchange, deploy, restart, or change production env")
print("[grid-open-readiness] read-only check complete")

if require_ready and status != "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION":
    raise SystemExit(f"Grid open readiness is not ready: {status}; blockers={blockers}")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__LOOKBACK_HOURS__", [string]$LookbackHours)
$remoteScript = $remoteScript.Replace("__REQUIRE_READY__", $RequireReady.ToString().ToLowerInvariant())

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid open readiness packet failed with exit code $LASTEXITCODE"
}
