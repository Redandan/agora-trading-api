param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72
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

if ($LookbackHours -lt 24 -or $LookbackHours -gt 336) {
    throw "LookbackHours must be between 24 and 336."
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

PORT=$(cat app.port)
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='__SYMBOL__' LOOKBACK_HOURS='__LOOKBACK_HOURS__'
python3 - <<'PY'
import json
import os
import re
import sys
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()
lookback_hours = int(os.environ["LOOKBACK_HOURS"])

def call_tool(name, arguments=None, timeout=90):
    body = {
        "jsonrpc": "2.0",
        "id": f"grid-trend-adjustment-review-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if data.get("error"):
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

def require(description, pattern, text):
    if not re.search(pattern, text, re.MULTILINE):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        print(text[:2500], file=sys.stderr)
        sys.exit(1)

def extract(pattern, text, default="UNKNOWN"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default

print("[grid-trend-adjustment-review] read-only production MCP check")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} lookbackHours={lookback_hours} activePort={os.environ['PORT']}")

review = call_tool("getGridTrendAdjustmentReview", {
    "symbol": symbol,
    "lookbackHours": lookback_hours,
})

require("Grid trend review heading", r"Grid Trend Adjustment Review", review)
require("read-only boundary", r"boundary=READ_ONLY", review)
require("mutation disabled marker", r"mutationAllowed=false", review)
require("order disabled marker", r"orderAllowed=false", review)
require("grid mutation disabled marker", r"gridMutationAllowed=false", review)
require("scheduler disabled marker", r"schedulerChangeAllowed=false", review)
require("telegram disabled marker", r"telegramSendAllowed=false", review)
require("operator-review-only marker", r"operator review only", review)
require("market evidence line", r"market symbol=", review)
require("recommendation marker", r"recommendation=", review)

recommendation = extract(r"recommendation=([A-Z0-9_]+)", review)
active_grid_count = extract(r"activeGridCount=([0-9]+)", review, "N/A")
trend = extract(r"trend=([A-Z_]+)", review)
trend_pct = extract(r"trendPct=([-+0-9.NA/%]+)", review, "N/A")
atr_pct = extract(r"atrPct=([-+0-9.NA/%]+)", review, "N/A")

if not re.match(r"^(KEEP_MONITOR|NO_ACTION_[A-Z0-9_]+|PAUSE_[A-Z0-9_]+_REVIEW|PAUSE_OR_WAIT_REVIEW|REBUILD_[A-Z0-9_]+_REVIEW|WIDEN_RANGE_REVIEW|NARROW_RANGE_REVIEW|CLOSE_REVIEW_FAILURE_FIRST)$", recommendation):
    print(f"FAIL: unexpected recommendation={recommendation}", file=sys.stderr)
    sys.exit(1)

status = "READY_GRID_TREND_REVIEW_NOT_MUTATION"
if recommendation == "NO_ACTION_NO_ACTIVE_GRID":
    status = "NO_ACTIVE_GRID_NOT_MUTATION"
elif recommendation == "NO_ACTION_INSUFFICIENT_EVIDENCE":
    status = "BLOCKED_INSUFFICIENT_GRID_TREND_EVIDENCE"

print("grid_trend_adjustment_review_packet=GRID_TREND_ADJUSTMENT_REVIEW_PACKET")
print(f"grid_trend_adjustment_review_status={status}")
print(f"grid_trend_adjustment_recommendation={recommendation}")
print(f"active_grid_count={active_grid_count}")
print(f"trend={trend}")
print(f"trendPct={trend_pct}")
print(f"atrPct={atr_pct}")
print("requiredEvidence=market trend, ATR, current price alignment, grid level state, and read-only recommendation markers")
print("notAuthorization=true")
print("nextAction=operator may review the packet; any closeGrid/createGrid/pause/resume or scheduler integration requires separate explicit approval.")
print("OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__LOOKBACK_HOURS__", [string]$LookbackHours)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid trend adjustment review smoke failed with exit code $LASTEXITCODE"
}
