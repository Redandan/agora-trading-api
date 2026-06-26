param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [int]$GridId = 10,
    [string]$Symbol = "BTCUSDT"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for smoke arguments."
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($GridId -le 0) { throw "GridId must be positive." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

env_value() {
  local key="`$1"
  grep -E "^`$key=" '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//'
}

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^(MCP_OPS_KEY|TRADING_MCP_OPS_KEY|TRADING_OPS_MCP_KEY)=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "[grid-post-open-smoke] FAIL: OPS MCP key missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY GRID_ID='$GridId' SYMBOL='$Symbol'
export TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=`$(env_value TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED)
export GRID_RECOVERY_ENABLED=`$(env_value GRID_RECOVERY_ENABLED)
export OKX_EARN_TOPUP_ENABLED=`$(env_value OKX_EARN_TOPUP_ENABLED)

python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

port = os.environ["PORT"]
grid_id = int(os.environ["GRID_ID"])
symbol = os.environ["SYMBOL"].upper()
url = f"http://127.0.0.1:{port}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}

def fail(message):
    print(f"[grid-post-open-smoke] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def call_tool(name, arguments=None):
    body = {
        "jsonrpc": "2.0",
        "id": f"grid-post-open-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        fail(f"{name} HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}")
    if "error" in data:
        fail(f"{name} JSON-RPC error: {data['error']}")
    result = data.get("result") or {}
    if result.get("isError"):
        fail(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        return content[0].get("text") or ""
    return json.dumps(result, ensure_ascii=False)

def env_flag(name):
    value = (os.environ.get(name) or "").strip().lower()
    if value != "false":
        fail(f"{name} expected false, got {value or 'MISSING'}")
    return value

for key in (
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
    "GRID_RECOVERY_ENABLED",
    "OKX_EARN_TOPUP_ENABLED",
):
    env_flag(key)

grid_stats = call_tool("gridStats", {"gridId": grid_id})
list_grids = call_tool("listGrids")
alignment = call_tool("getGridPriceAlignment")
exposure = call_tool("getCurrentExposure")
scheduled = call_tool("listSchedulerTasks")

if f"Grid #{grid_id} {symbol}" not in grid_stats:
    fail(f"gridStats missing Grid #{grid_id} {symbol}")
if "狀態: ACTIVE" not in grid_stats:
    fail(f"Grid #{grid_id} is not ACTIVE")
if "Hint-gated: true" not in grid_stats:
    fail(f"Grid #{grid_id} hint gate is not true")

block_match = re.search(
    rf"#{grid_id}\s+{re.escape(symbol)}\s+✅ ACTIVE.*?Level:\s+PENDING=(\d+)\s+HOLDING=(\d+)\s+CLOSED=(\d+)\s+SELL_FAILED=(\d+)\s+SELL_PARTIAL=(\d+)\s+BUY_FAILED=(\d+)",
    list_grids,
    re.S,
)
if not block_match:
    fail(f"listGrids missing active level counts for Grid #{grid_id}")
pending, holding, closed, sell_failed, sell_partial, buy_failed = [int(x) for x in block_match.groups()]
if pending < 1:
    fail(f"Grid #{grid_id} has no pending levels")
if sell_failed or sell_partial or buy_failed:
    fail(
        f"Grid #{grid_id} has failed/partial levels: "
        f"SELL_FAILED={sell_failed} SELL_PARTIAL={sell_partial} BUY_FAILED={buy_failed}"
    )

if f"Grid #{grid_id} {symbol}" not in alignment or "IN RANGE" not in alignment:
    fail(f"Grid #{grid_id} is not aligned in range")
if f"Grid #{grid_id} {symbol}" not in exposure:
    fail(f"getCurrentExposure missing Grid #{grid_id}")
if "GridManagerScheduler.checkAllGrids()" not in scheduled:
    fail("GridManagerScheduler.checkAllGrids() missing from scheduler registry")

packet = {
    "gridId": grid_id,
    "symbol": symbol,
    "status": "ACTIVE",
    "pending": pending,
    "holding": holding,
    "closed": closed,
    "sellFailed": sell_failed,
    "sellPartial": sell_partial,
    "buyFailed": buy_failed,
    "priceAlignment": "IN_RANGE",
    "schedulerRegistered": True,
    "env": {
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED": os.environ["TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED"],
        "GRID_RECOVERY_ENABLED": os.environ["GRID_RECOVERY_ENABLED"],
        "OKX_EARN_TOPUP_ENABLED": os.environ["OKX_EARN_TOPUP_ENABLED"],
    },
}
print("[grid-post-open-smoke] read-only MCP checks passed")
print("grid_post_open_smoke_packet=" + json.dumps(packet, sort_keys=True))
print("grid_post_open_grid_stats_excerpt=" + grid_stats[:1200].replace("\n", "\\n"))
print("grid_post_open_alignment_excerpt=" + alignment[:800].replace("\n", "\\n"))
print("grid_post_open_exposure_excerpt=" + exposure[:1200].replace("\n", "\\n"))
PY

ALLOW_UNKNOWN_WARN=0 ALLOW_RUNTIME_ERROR=0 ALLOW_HIGH_RISK_LOG=0 bash scripts/check_server_runtime_log.sh
echo "[grid-post-open-smoke] OK"
"@

Write-Host "[grid-post-open-smoke] read-only verification"
Write-Host "scope=READ_ONLY; checks Grid #$GridId via server-local OPS MCP plus runtime log; no production env, DB, order, OCO, grid mutation, fund, Earn, Telegram, scheduler, exchange, deploy, restart, or nginx state changed."

$output = ssh -i $SshKey $SshHost $remoteScript
$output | ForEach-Object { Write-Host $_ }

Write-Host "notAuthorization=read-only grid post-open smoke only; does not create, pause, resume, close, rebalance, place orders, modify OCO, send Telegram, change env, deploy, restart, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-post-open-smoke] complete"
