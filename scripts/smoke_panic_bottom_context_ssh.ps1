param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireReady
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
        throw "$Name contains unsupported characters for panic-bottom context smoke invocation."
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

export PORT MCP_KEY SYMBOL='__SYMBOL__'
python3 - <<'PY'
import json
import os
import sys
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()

def call_tool(name, arguments=None, timeout=120):
    body = {
        "jsonrpc": "2.0",
        "id": f"panic-bottom-context-{name}",
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

def get_path(obj, *parts):
    cur = obj
    for part in parts:
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur

def bool_text(value):
    return str(bool(value)).lower()

text = call_tool("previewPanicBottomContext", {"symbol": symbol})
try:
    context = json.loads(text)
except Exception as exc:
    print("[panic-bottom-context] FAIL: previewPanicBottomContext did not return JSON", file=sys.stderr)
    print(str(exc), file=sys.stderr)
    print(text[:2000], file=sys.stderr)
    sys.exit(1)

required_false = [
    "orderAllowed",
    "gridMutationAllowed",
    "ocoMutationAllowed",
    "telegramSendAllowed",
    "writesRuntimeEvidence",
]
missing = []
if context.get("boundary") != "READ_ONLY":
    missing.append("boundary=READ_ONLY")
for key in required_false:
    if context.get(key) is not False:
        missing.append(f"{key}=false")

status = "READY_FOR_PANIC_BOTTOM_CONTEXT_REVIEW_NOT_LIVE" if not missing else "NOT_READY"

print("[panic-bottom-context] read-only server-local MCP smoke")
print("scope=READ_ONLY; server-local /api/mcp only; calls previewPanicBottomContext; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} activePort={os.environ['PORT']}")
print(f"panic_bottom_context_boundary={context.get('boundary', 'N/A')}")
print(f"panic_bottom_context_score={context.get('panicBottomScore', 'N/A')}")
print(f"panic_bottom_context_phase={context.get('phase', 'N/A')}")
print(f"panic_bottom_context_suggested_action={context.get('suggestedAction', 'N/A')}")
print(f"panic_bottom_context_confirmed_deploy_blocked={bool_text(context.get('confirmedDeployBlocked', False))}")
print(f"panic_bottom_context_confirmed_deploy_block_reason={context.get('confirmedDeployBlockReason', 'N/A')}")
print(f"panic_bottom_context_down_wave_count={get_path(context, 'waveStructure', 'downWaveCount')}")
print(f"panic_bottom_context_largest_drawdown_pct={get_path(context, 'waveStructure', 'largestDrawdownPct')}")
print(f"panic_bottom_context_current_leg_pct={get_path(context, 'waveStructure', 'currentLegPct')}")
print(f"panic_bottom_context_retest_low_status={get_path(context, 'waveStructure', 'retestLowStatus')}")
print(f"panic_bottom_context_fear_greed_latest_value={get_path(context, 'fearGreed', 'latestValue')}")
print(f"panic_bottom_context_fear_greed_classification={get_path(context, 'fearGreed', 'classification')}")
print(f"panic_bottom_context_fear_greed_freshness={get_path(context, 'fearGreed', 'freshness')}")
print(f"panic_bottom_context_price_vs_200wma_pct={get_path(context, 'twoHundredWma', 'priceVs200wmaPct')}")
print(f"panic_bottom_context_1h_trend_status={get_path(context, 'trendGuard', 'oneHour', 'status')}")
print(f"panic_bottom_context_4h_trend_status={get_path(context, 'trendGuard', 'fourHour', 'status')}")
print(f"panic_bottom_context_oco_guard_status={get_path(context, 'ocoGuard', 'status')}")
print(f"panic_bottom_context_oco_guard_abnormal={bool_text(get_path(context, 'ocoGuard', 'abnormal'))}")
print(f"order_allowed={bool_text(context.get('orderAllowed', True))}")
print(f"grid_mutation_allowed={bool_text(context.get('gridMutationAllowed', True))}")
print(f"oco_mutation_allowed={bool_text(context.get('ocoMutationAllowed', True))}")
print(f"telegram_send_allowed={bool_text(context.get('telegramSendAllowed', True))}")
print(f"runtime_evidence_write_allowed={bool_text(context.get('writesRuntimeEvidence', True))}")
print("panic_bottom_context_missing_requirements=" + json.dumps(missing, ensure_ascii=False))
print("panic_bottom_context_raw=" + json.dumps(context, ensure_ascii=False, separators=(",", ":")))
print(f"panic_bottom_context_status={status}")
print("notAuthorization=read-only panic-bottom context smoke only; does not authorize live trading, strategy activation, pre-position execution, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, scheduler enablement, EntryDedup/DataFreshness/live policy relaxation, or external backfill/import")
print("[panic-bottom-context] OK read-only check complete")

if missing:
    sys.exit(2)
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "Panic-bottom context smoke failed with exit code $LASTEXITCODE"
}

