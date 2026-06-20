param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h"
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
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 21

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' INTERVAL_CODE='$IntervalCode'
python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()
interval_code = os.environ["INTERVAL_CODE"]

required_tools = [
    "getMcpRegistryVersion",
    "getMcpAuthProbe",
    "listSchedulerTasks",
    "listStrategies",
    "runBacktest",
    "listGrids",
    "getOpenPositions",
    "getSystemHealth",
    "getMarketSentiment",
    "getCollectionFreshness",
    "diagnoseDataFreshnessGuardBlocks",
    "getReport",
    "getTradingManagerDigest",
    "getMlLimits",
    "listRuntimeDecisionEvidence",
    "getScoreBuyFormingDayStatus",
    "getEventRiskControlStatus",
    "analyzeSpotAntiWickPolicyCoverage",
    "verifyStrategyExecution",
    "analyzeBlockedSignalOutcomes",
    "getSignalCorrectnessDashboard",
    "getSignalAccuracyReport",
    "getEntryDedupGovernanceDashboard",
    "getMissedOpportunityRegressionReport",
    "getGovernanceDriftDashboard",
    "findGovernanceRelaxationCandidates",
    "findGovernanceTighteningCandidates",
    "listExecutionEvents",
    "getGuardianSnapshot",
    "listFundingArb",
    "getEarnBalance",
    "previewEnsembleScore",
    "analyzeTrailingStopPnlReplay",
    "listAiProviders",
    "listAiTasks",
]

def request(body, timeout=120):
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    data = json.loads(raw)
    if "error" in data:
        raise RuntimeError(f"JSON-RPC error: {data['error']}")
    return data

def call_tool(name, arguments=None, timeout=120):
    data = request({
        "jsonrpc": "2.0",
        "id": f"mcp-parity-{name}",
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments or {},
        },
    }, timeout=timeout)
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
        sys.exit(1)

print("[mcp-parity-ssh] read-only server-local MCP parity smoke")
print(f"url={url} symbol={symbol} intervalCode={interval_code}")

tools_list = request({
    "jsonrpc": "2.0",
    "id": "mcp-parity-tools-list",
    "method": "tools/list",
    "params": {},
}, timeout=120)
tools = tools_list.get("result", {}).get("tools") or []
tool_names = sorted({tool.get("name") for tool in tools if tool.get("name")})
missing = [name for name in required_tools if name not in tool_names]
print("required_tools=" + json.dumps(required_tools))
print("missing_required_tools=" + json.dumps(missing))
if missing:
    print(f"FAIL: missing required standalone trading tools: {', '.join(missing)}", file=sys.stderr)
    sys.exit(1)

registry = call_tool("getMcpRegistryVersion")
if not registry:
    print("FAIL: getMcpRegistryVersion returned no content", file=sys.stderr)
    sys.exit(1)

data_freshness = call_tool("diagnoseDataFreshnessGuardBlocks", {
    "days": 1,
    "symbol": symbol,
    "limit": 5,
})
require("DataFreshnessGuard read-only boundary", r"boundary:\s*READ_ONLY", data_freshness)
require("DataFreshnessGuard acceptance marker", r"acceptance: PASS_NO_CURRENT_SAMPLE|acceptance: PASS_RCA_CLASSIFIED", data_freshness)

event_risk = call_tool("getEventRiskControlStatus", {"symbol": symbol})
require("event-risk read-only boundary", r"boundary=READ_ONLY", event_risk)
require("event-risk config-only controls", r"operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION", event_risk)

anti_wick = call_tool("analyzeSpotAntiWickPolicyCoverage", {"symbol": symbol})
require("anti-wick read-only boundary", r"boundary:\s*READ_ONLY", anti_wick)
require("anti-wick disaster-SL policy", r"policy: live BTC spot LONG entries default to ULTRA_LOW_DISASTER SL", anti_wick)
require("anti-wick summary", r"Summary:", anti_wick)

entry_dedup = call_tool("getEntryDedupGovernanceDashboard", {
    "symbol": symbol,
    "hours": 24,
})
require("EntryDedup governance tool marker", r"getEntryDedupGovernanceDashboard", entry_dedup)
require("EntryDedup governance read-only boundary", r"READ_ONLY", entry_dedup)
require("EntryDedup governance no order send marker", r"orderSent", entry_dedup)
require("EntryDedup governance no OCO modification marker", r"ocoModified", entry_dedup)
require("EntryDedup governance no runtime evidence writes marker", r"writesRuntimeEvidence", entry_dedup)

missed_opportunity = call_tool("getMissedOpportunityRegressionReport", {
    "symbol": symbol,
    "hours": 24,
})
require("missed-opportunity regression tool marker", r"getMissedOpportunityRegressionReport", missed_opportunity)
require("missed-opportunity regression read-only boundary", r"READ_ONLY", missed_opportunity)
require("missed-opportunity regression overall status", r"overallStatus", missed_opportunity)
require("missed-opportunity regression no order send marker", r"orderSent", missed_opportunity)
require("missed-opportunity regression no OCO modification marker", r"ocoModified", missed_opportunity)
require("missed-opportunity regression no runtime evidence writes marker", r"writesRuntimeEvidence", missed_opportunity)

governance_drift = call_tool("getGovernanceDriftDashboard", {
    "symbol": symbol,
    "days": 1,
    "labelHorizon": "1h",
})
require("governance drift heading", r"Governance Drift Dashboard", governance_drift)
require("governance drift read-only boundary", r"boundary:\s*READ_ONLY", governance_drift)
require("governance drift mode", r"governanceMode=", governance_drift)

governance_relaxation = call_tool("findGovernanceRelaxationCandidates", {
    "symbol": symbol,
    "days": 1,
    "labelHorizon": "1h",
})
require("governance relaxation heading", r"Governance Relaxation Candidates", governance_relaxation)
require("governance relaxation read-only boundary", r"boundary:\s*READ_ONLY", governance_relaxation)
require("governance relaxation criteria", r"criteria:", governance_relaxation)

governance_tightening = call_tool("findGovernanceTighteningCandidates", {
    "symbol": symbol,
    "days": 1,
    "labelHorizon": "1h",
})
require("governance tightening heading", r"Governance Tightening Candidates", governance_tightening)
require("governance tightening read-only boundary", r"boundary:\s*READ_ONLY", governance_tightening)
require("governance tightening criteria", r"criteria:", governance_tightening)

trailing = call_tool("analyzeTrailingStopPnlReplay", {
    "symbol": symbol,
    "intervalCode": interval_code,
    "replayIntervalCode": "1m",
    "days": 30,
    "limit": 10,
}, timeout=180)
require("trailing replay read-only boundary", r"boundary:\s*READ_ONLY", trailing)
require("trailing replay backtest interval", r"backtestInterval:\s*1h", trailing)
require("trailing replay interval", r"replayInterval:\s*1m", trailing)
require("trailing split interval semantics", r"replayIntervalNote=backtest interval selects normalized trades", trailing)
require("trailing replay sample status", r"sampleStatus=NO_REPLAYABLE_TRADES|sampleStatus=REPLAYED|sampleStatus=NO_REPLAYED_ROWS", trailing)
require("trailing replay acceptance target", r"acceptanceTarget: total trailing PnL improvement >= 5%", trailing)
require("trailing ambiguous same-bar exclusion", r"acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals", trailing)
require("trailing replay acceptance blocker", r"acceptanceBlocker=(NO_REPLAYABLE_TRADES|NO_REPLAYED_ROWS|ALL_REPLAYED_ROWS_AMBIGUOUS|NO_NON_AMBIGUOUS_ACCEPTANCE_ROWS|ZERO_OR_MISSING_ORIGINAL_PNL|CURRENT_PARAMETERS_NO_PNL_IMPROVEMENT|BELOW_ACCEPTANCE_TARGET|NONE)", trailing)
require("trailing replay acceptance blocker detail", r"acceptanceBlockerDetail=", trailing)

print(f"[mcp-parity-ssh] OK toolCount={len(tool_names)} required={len(required_tools)}")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "server-local MCP parity smoke failed with exit code $LASTEXITCODE"
}
