param(
    [ValidateSet(10, 11)]
    [int]$GridId,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedCommit,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedStateSha256,
    [Parameter(Mandatory = $true)]
    [decimal]$ExpectedTotalSellQty,
    [string]$ExpectedBuyOrderId = "",
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$SourceLogPath = "",
    [switch]$RequireReady
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

function Assert-HexSafe {
    param([string]$Name, [string]$Value, [int]$Length)
    if ($Value -notmatch "^[a-fA-F0-9]{$Length}$") {
        throw "$Name must contain exactly $Length hexadecimal characters."
    }
}

function Assert-DigitsSafe {
    param([string]$Name, [string]$Value)
    if (-not [string]::IsNullOrWhiteSpace($Value) -and $Value -notmatch "^[0-9]+$") {
        throw "$Name must contain digits only."
    }
}

Assert-HexSafe -Name "ExpectedCommit" -Value $ExpectedCommit -Length 40
Assert-HexSafe -Name "ExpectedStateSha256" -Value $ExpectedStateSha256 -Length 64
Assert-DigitsSafe -Name "ExpectedBuyOrderId" -Value $ExpectedBuyOrderId
if ($ExpectedTotalSellQty -lt 0) { throw "ExpectedTotalSellQty must not be negative." }
if ($GridId -eq 10 -and [string]::IsNullOrWhiteSpace($ExpectedBuyOrderId)) {
    throw "ExpectedBuyOrderId is required for Grid #10."
}
if ($GridId -eq 11 -and $ExpectedTotalSellQty -ne 0) {
    throw "Grid #11 ExpectedTotalSellQty must be zero."
}

function Invoke-ReadOnlyRemotePreflight {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

    $expectedQty = $ExpectedTotalSellQty.ToString([Globalization.CultureInfo]::InvariantCulture)
    $remoteScript = @'
set -euo pipefail
cd '__APPDIR__'
export GRID_ID='__GRID_ID__'
export EXPECTED_COMMIT='__EXPECTED_COMMIT__'
export EXPECTED_STATE_SHA256='__EXPECTED_STATE_SHA256__'
export EXPECTED_TOTAL_SELL_QTY='__EXPECTED_TOTAL_SELL_QTY__'
export EXPECTED_BUY_ORDER_ID='__EXPECTED_BUY_ORDER_ID__'
export ENV_FILE='__ENVFILE__'

python3 - <<'PY'
import base64
import datetime
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

def fail(message):
    print(f"[legacy-grid-retirement-preflight] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def proc_env(pid):
    result = {}
    with open(f"/proc/{pid}/environ", "rb") as handle:
        for item in handle.read().split(b"\0"):
            if b"=" not in item:
                continue
            key, value = item.split(b"=", 1)
            result[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    return result

def enabled(value):
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}

def mcp_call(port, key, name, arguments):
    body = json.dumps({
        "jsonrpc": "2.0",
        "id": f"legacy-retirement-preflight-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }, separators=(",", ":")).encode()
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/mcp",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        message = json.loads(response.read().decode("utf-8", "replace"))
    if message.get("error"):
        fail(f"{name} JSON-RPC error: {message['error']}")
    result = message.get("result") or {}
    if result.get("isError"):
        fail(f"{name} returned isError=true")
    text = "\n".join(
        item.get("text", "") for item in result.get("content") or []
        if isinstance(item, dict) and item.get("type") == "text"
    )
    for _ in range(3):
        try:
            parsed = json.loads(text)
        except Exception:
            break
        if isinstance(parsed, str):
            text = parsed
            continue
        return parsed
    return text

def signed_get(env, path):
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    signature = base64.b64encode(hmac.new(
        env["TRADING_OKX_SECRET_KEY"].encode(),
        (timestamp + "GET" + path).encode(),
        hashlib.sha256,
    ).digest()).decode()
    request = urllib.request.Request(
        "https://www.okx.com" + path,
        headers={
            "OK-ACCESS-KEY": env["TRADING_OKX_API_KEY"],
            "OK-ACCESS-SIGN": signature,
            "OK-ACCESS-TIMESTAMP": timestamp,
            "OK-ACCESS-PASSPHRASE": env["TRADING_OKX_PASSPHRASE"],
            "User-Agent": "agora-trading-api/1.0",
            "Accept": "application/json",
        },
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if data.get("code") != "0":
        fail(f"OKX read failed path={path} code={data.get('code')} msg={data.get('msg')}")
    return data.get("data") or []

def kv(text):
    result = {}
    for line in str(text or "").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result

if not os.path.isfile("app.pid") or not os.path.isfile("app.port"):
    fail("app.pid or app.port missing")
pid = open("app.pid", encoding="utf-8").read().strip()
port = open("app.port", encoding="utf-8").read().strip()
if not pid.isdigit() or not port.isdigit():
    fail("invalid app pid or port")
env = proc_env(pid)
mcp_key = env.get("MCP_OPS_KEY") or env.get("TRADING_MCP_KEY")
if not mcp_key:
    fail("running process MCP OPS key missing")
for key in ("TRADING_OKX_API_KEY", "TRADING_OKX_SECRET_KEY", "TRADING_OKX_PASSPHRASE"):
    if not env.get(key):
        fail(f"running process {key} missing")

grid_id = int(os.environ["GRID_ID"])
disposition = "MARKET_SELL_AND_CLOSE" if grid_id == 10 else "CLOSE_NO_HOLDING"
registry = mcp_call(port, mcp_key, "getMcpRegistryVersion", {})
retirement_text = mcp_call(port, mcp_key, "retireLegacyGrid", {
    "gridId": grid_id,
    "disposition": disposition,
    "execute": False,
    "confirmText": "",
})
retirement = kv(retirement_text)

native_active = signed_get(env, "/api/v5/tradingBot/grid/orders-algo-pending?algoOrdType=grid")
balances_data = signed_get(env, "/api/v5/account/balance?ccy=BTC,USDT")
balances = {}
for detail in ((balances_data[0].get("details") or []) if balances_data else []):
    ccy = detail.get("ccy")
    if ccy in {"BTC", "USDT"}:
        balances[ccy] = {
            "available": detail.get("availBal"),
            "cash": detail.get("cashBal"),
            "equityUsd": detail.get("eqUsd"),
        }

with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/actuator/health", timeout=10) as response:
    health = json.loads(response.read().decode("utf-8", "replace"))

head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
dirty = subprocess.run(["git", "status", "--porcelain"], check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
env_file = os.environ["ENV_FILE"]
if not os.path.isfile(env_file):
    fail(f"env file missing: {env_file}")
with open(env_file, "rb") as handle:
    env_sha = hashlib.sha256(handle.read()).hexdigest()

expected_commit = os.environ["EXPECTED_COMMIT"].lower()
expected_state = os.environ["EXPECTED_STATE_SHA256"].lower()
expected_qty = os.environ["EXPECTED_TOTAL_SELL_QTY"]
expected_buy = os.environ.get("EXPECTED_BUY_ORDER_ID", "")
blockers = []
if head.lower() != expected_commit:
    blockers.append("SERVER_HEAD_COMMIT_MISMATCH")
if str(registry.get("gitCommit", "")).lower() != expected_commit[:12]:
    blockers.append("RUNNING_COMMIT_MISMATCH")
if dirty:
    blockers.append("SERVER_WORKTREE_DIRTY")
if health.get("status") != "UP":
    blockers.append("HEALTH_NOT_UP")
if retirement.get("status") != "READY_FOR_SEPARATE_EXACT_RETIREMENT_AUTHORIZATION":
    blockers.append("RETIREMENT_DRY_RUN_NOT_READY")
if retirement.get("blockers") != "[]":
    blockers.append("RETIREMENT_DRY_RUN_BLOCKED")
if retirement.get("providerOrderAttempted") != "false" or retirement.get("databaseMutationAttempted") != "false":
    blockers.append("DRY_RUN_REPORTED_MUTATION_ATTEMPT")
if retirement.get("stateSha256", "").lower() != expected_state:
    blockers.append("STATE_SHA256_MISMATCH")
if retirement.get("totalSellQty") != expected_qty:
    blockers.append("TOTAL_SELL_QTY_MISMATCH")
if expected_buy and f"buyOrderId={expected_buy}" not in retirement.get("providerPlans", ""):
    blockers.append("BUY_ORDER_ID_MISMATCH")
if enabled(env.get("TRADING_GRID_LEGACY_RETIREMENT_ENABLED")):
    blockers.append("LEGACY_RETIREMENT_FEATURE_ALREADY_ENABLED")
if enabled(env.get("TRADING_GRID_LEGACY_RETIREMENT_LIVE_ACTION_ENABLED")):
    blockers.append("LEGACY_RETIREMENT_LIVE_ACTION_ALREADY_ENABLED")
if native_active:
    blockers.append("ACTIVE_NATIVE_GRID_BOT_PRESENT")
if grid_id == 10 and not enabled(env.get("TRADING_OKX_ENABLED")):
    blockers.append("OKX_MASTER_TRADING_DISABLED_FOR_REQUIRED_SELL")
if "BTC" not in balances or "USDT" not in balances:
    blockers.append("BTC_USDT_BALANCE_SNAPSHOT_INCOMPLETE")

packet = {
    "packetType": "LEGACY_GRID_RETIREMENT_EXECUTION_PREFLIGHT_V1",
    "boundary": "READ_ONLY_NO_ENV_DB_ORDER_GRID_OR_BOT_MUTATION",
    "checkedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "gridId": grid_id,
    "disposition": disposition,
    "expectedCommit": expected_commit,
    "serverHeadCommit": head,
    "runningCommit": registry.get("gitCommit"),
    "serverWorktreeDirty": bool(dirty),
    "health": health.get("status"),
    "environmentFileSha256": env_sha,
    "legacyRetirementFeatureEnabled": enabled(env.get("TRADING_GRID_LEGACY_RETIREMENT_ENABLED")),
    "legacyRetirementLiveActionEnabled": enabled(env.get("TRADING_GRID_LEGACY_RETIREMENT_LIVE_ACTION_ENABLED")),
    "okxMasterTradingEnabled": enabled(env.get("TRADING_OKX_ENABLED")),
    "activeNativeGridBotCount": len(native_active),
    "balances": balances,
    "dryRun": retirement,
    "exactConfirmation": retirement.get("requiredConfirmText"),
    "providerOrderAttempted": False,
    "databaseMutationAttempted": False,
    "productionMutationAttempted": False,
    "blockers": blockers,
    "status": "READY_FOR_EXACT_AUTHORIZATION_NOT_EXECUTION" if not blockers else "BLOCKED_NOT_READY",
}
print("legacy_grid_retirement_preflight=" + json.dumps(packet, ensure_ascii=False, separators=(",", ":")))
print("scope=READ_ONLY; no production env, restart, DB, provider order, legacy Grid, native Bot, deploy, or custom-runtime mutation")
PY
'@
    $remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
    $remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
    $remoteScript = $remoteScript.Replace("__GRID_ID__", [string]$GridId)
    $remoteScript = $remoteScript.Replace("__EXPECTED_COMMIT__", $ExpectedCommit.ToLowerInvariant())
    $remoteScript = $remoteScript.Replace("__EXPECTED_STATE_SHA256__", $ExpectedStateSha256.ToLowerInvariant())
    $remoteScript = $remoteScript.Replace("__EXPECTED_TOTAL_SELL_QTY__", $expectedQty)
    $remoteScript = $remoteScript.Replace("__EXPECTED_BUY_ORDER_ID__", $ExpectedBuyOrderId)

    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
    $output = & ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=yes $SshHost "printf '%s' '$payload' | base64 -d | bash" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Legacy Grid retirement preflight SSH failed exit=$LASTEXITCODE output=$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine)
}

$text = if (-not [string]::IsNullOrWhiteSpace($SourceLogPath)) {
    if (-not (Test-Path -LiteralPath $SourceLogPath)) { throw "SourceLogPath not found: $SourceLogPath" }
    Get-Content -LiteralPath $SourceLogPath -Raw
} else {
    Invoke-ReadOnlyRemotePreflight
}

$line = @($text -split "`r?`n" | Where-Object { $_.StartsWith("legacy_grid_retirement_preflight=") } | Select-Object -Last 1)
if (-not $line) { throw "legacy_grid_retirement_preflight output is missing" }
$packet = $line.Substring("legacy_grid_retirement_preflight=".Length) | ConvertFrom-Json -Depth 30
Write-Host $line
Write-Host "legacy_grid_retirement_preflight_status=$($packet.status)"
Write-Host "legacy_grid_retirement_exact_confirmation=$($packet.exactConfirmation)"
Write-Host "notAuthorization=read-only preflight only; does not authorize or perform env/restart/DB/order/Grid/Bot/deploy/custom-runtime mutation"
if ($RequireReady -and $packet.status -ne "READY_FOR_EXACT_AUTHORIZATION_NOT_EXECUTION") {
    throw "Legacy Grid retirement preflight is not ready: $($packet.blockers -join ', ')"
}

