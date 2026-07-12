param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$Hours = 720,
    [int]$DetailLimit = 50,
    [switch]$RequireInsufficientData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) {
    throw "A valid SshKey is required."
}
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}
if ($Hours -lt 24 -or $Hours -gt 2160) {
    throw "Hours must be between 24 and 2160."
}
if ($DetailLimit -lt 1 -or $DetailLimit -gt 200) {
    throw "DetailLimit must be between 1 and 200."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or
        $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or
        $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
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
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='__SYMBOL__' HOURS='__HOURS__' DETAIL_LIMIT='__DETAIL_LIMIT__' REQUIRE_INSUFFICIENT='__REQUIRE_INSUFFICIENT__'
python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}

payload = {
    "jsonrpc": "2.0",
    "id": "strategy508-hold-counterfactual",
    "method": "tools/call",
    "params": {
        "name": "analyzeStrategy508HoldCounterfactual",
        "arguments": {
            "symbol": os.environ["SYMBOL"].upper(),
            "hours": int(os.environ["HOURS"]),
            "detailLimit": int(os.environ["DETAIL_LIMIT"]),
        },
    },
}

request = urllib.request.Request(
    url,
    data=json.dumps(payload).encode("utf-8"),
    headers=headers,
    method="POST",
)
try:
    with urllib.request.urlopen(request, timeout=240) as response:
        body = json.loads(response.read().decode("utf-8", "replace"))
except urllib.error.HTTPError as exc:
    print(f"FAIL: HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}", file=sys.stderr)
    sys.exit(1)

if body.get("error"):
    print(f"FAIL: MCP error: {body['error']}", file=sys.stderr)
    sys.exit(1)
result = body.get("result") or {}
if result.get("isError"):
    print(f"FAIL: tool returned isError=true: {result}", file=sys.stderr)
    sys.exit(1)
content = result.get("content") or []
text = content[0].get("text", "") if content and isinstance(content[0], dict) else json.dumps(result)
if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
    try:
        decoded = json.loads(text)
        if isinstance(decoded, str):
            text = decoded
    except Exception:
        pass
try:
    report = json.loads(text)
except Exception as exc:
    print(f"FAIL: report is not JSON: {exc}; text={text[:1000]}", file=sys.stderr)
    sys.exit(1)

def require_equal(path, actual, expected):
    if actual != expected:
        print(f"FAIL: {path} expected={expected!r} actual={actual!r}", file=sys.stderr)
        sys.exit(1)

require_equal("tool", report.get("tool"), "analyzeStrategy508HoldCounterfactual")
require_equal("boundary", report.get("boundary"), "READ_ONLY")
require_equal("strategyId", report.get("strategyId"), 508)
require_equal("sampleGateMinFinalizedEvents", report.get("sampleGateMinFinalizedEvents"), 30)
require_equal("liveRelaxationAllowed", report.get("liveRelaxationAllowed"), False)
safety = report.get("safety") or {}
require_equal("safety.writesRuntimeEvidence", safety.get("writesRuntimeEvidence"), False)
require_equal("safety.orderSent", safety.get("orderSent"), False)
require_equal("safety.ocoModified", safety.get("ocoModified"), False)
require_equal("safety.productionStateChanged", safety.get("productionStateChanged"), False)
require_equal("safety.hardSafetyEventsEligible", safety.get("hardSafetyEventsEligible"), 0)
assumptions = report.get("simulationAssumptions") or {}
require_equal("simulationAssumptions.notionalUsdt", assumptions.get("notionalUsdt"), 10.0)
require_equal("simulationAssumptions.entryFeeRate", assumptions.get("entryFeeRate"), 0.001)
require_equal("simulationAssumptions.exitFeeRate", assumptions.get("exitFeeRate"), 0.001)
require_equal("simulationAssumptions.takeProfitPct", assumptions.get("takeProfitPct"), 0.06)
require_equal("simulationAssumptions.stopLossPct", assumptions.get("stopLossPct"), 0.12)

sample_status = report.get("sampleStatus")
if sample_status == "DATA_QUERY_FAILED":
    print("FAIL: production read-only data query failed", file=sys.stderr)
    sys.exit(1)
if os.environ.get("REQUIRE_INSUFFICIENT", "").lower() == "true" and sample_status != "INSUFFICIENT_DATA":
    print(f"FAIL: expected INSUFFICIENT_DATA, got {sample_status}", file=sys.stderr)
    sys.exit(1)

counts = report.get("counts") or {}
metrics = report.get("metrics") or {}
print("[strategy508-hold-counterfactual] read-only server-local MCP smoke")
print("scope=READ_ONLY; no production env, DB, order, OCO, strategy flags, grid, fund, Earn, Telegram, scheduler, exchange, deploy, or restart state changed.")
print(f"url={url} symbol={report.get('symbol')} hours={report.get('hours')} activePort={os.environ['PORT']}")
print(f"sampleStatus={sample_status}")
print(f"rawEvidenceRows={counts.get('rawEvidenceRows')}")
print(f"uniqueMarketEvents={counts.get('uniqueMarketEvents')}")
print(f"eligibleUniqueEvents={counts.get('eligibleUniqueEvents')}")
print(f"finalizedUniqueEvents={counts.get('finalizedUniqueEvents')}")
print(f"eventChainRowsCollapsed={counts.get('eventChainRowsCollapsed')}")
print(f"hardSafetyEventsEligible={safety.get('hardSafetyEventsEligible')}")
print(f"totalPnlUsdt={metrics.get('totalPnlUsdt')}")
print(f"maxCumulativePnlDrawdownUsdt={metrics.get('maxCumulativePnlDrawdownUsdt')}")
print("classificationBreakdown=" + json.dumps(report.get("classificationBreakdown") or {}, ensure_ascii=False, sort_keys=True))
print("blockerBreakdown=" + json.dumps(report.get("blockerBreakdown") or {}, ensure_ascii=False, sort_keys=True))
print("counterfactual_report=" + json.dumps(report, ensure_ascii=False, sort_keys=True))
print("notAuthorization=INSUFFICIENT_DATA or shadow review evidence never authorizes live gate relaxation, order placement, OCO changes, or production mutation.")
print("[strategy508-hold-counterfactual] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__HOURS__", [string]$Hours).
    Replace("__DETAIL_LIMIT__", [string]$DetailLimit).
    Replace("__REQUIRE_INSUFFICIENT__", [string]$RequireInsufficientData.IsPresent)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "strategy 508 HOLD counterfactual smoke failed with exit code $LASTEXITCODE"
}
