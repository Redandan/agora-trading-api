param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [string]$ReplayIntervalCode = "1m",
    [int]$Days = 30,
    [int]$Limit = 500,
    [int]$TopN = 8,
    [string]$BreakevenMultiples = "",
    [string]$TrailingTriggerMultiples = "",
    [string]$TrailingDistanceMultiples = "",
    [switch]$RequireBetterCandidate
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

if ($Days -lt 1 -or $Days -gt 90) {
    throw "Days must be between 1 and 90."
}

if ($Limit -lt 1 -or $Limit -gt 500) {
    throw "Limit must be between 1 and 500."
}

if ($TopN -lt 1 -or $TopN -gt 20) {
    throw "TopN must be between 1 and 20."
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

function Assert-McpSmokeListSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($Value.Length -gt $MaxLength -or $Value -notmatch "^[0-9][0-9.,]*$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 21
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode -MaxLength 21
Assert-McpSmokeListSafe -Name "BreakevenMultiples" -Value $BreakevenMultiples -MaxLength 80
Assert-McpSmokeListSafe -Name "TrailingTriggerMultiples" -Value $TrailingTriggerMultiples -MaxLength 100
Assert-McpSmokeListSafe -Name "TrailingDistanceMultiples" -Value $TrailingDistanceMultiples -MaxLength 100

$requireBetterCandidateText = if ($RequireBetterCandidate.IsPresent) { "true" } else { "false" }
$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' INTERVAL_CODE='$IntervalCode' REPLAY_INTERVAL_CODE='$ReplayIntervalCode' DAYS='$Days' LIMIT='$Limit' TOP_N='$TopN' BREAKEVEN_MULTIPLES='$BreakevenMultiples' TRAILING_TRIGGER_MULTIPLES='$TrailingTriggerMultiples' TRAILING_DISTANCE_MULTIPLES='$TrailingDistanceMultiples' REQUIRE_BETTER_CANDIDATE='$requireBetterCandidateText'
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
arguments = {
    "symbol": os.environ["SYMBOL"].upper(),
    "intervalCode": os.environ["INTERVAL_CODE"],
    "replayIntervalCode": os.environ["REPLAY_INTERVAL_CODE"],
    "days": int(os.environ["DAYS"]),
    "limit": int(os.environ["LIMIT"]),
    "topN": int(os.environ["TOP_N"]),
}
if os.environ.get("BREAKEVEN_MULTIPLES"):
    arguments["breakevenMultiples"] = os.environ["BREAKEVEN_MULTIPLES"]
if os.environ.get("TRAILING_TRIGGER_MULTIPLES"):
    arguments["trailingTriggerMultiples"] = os.environ["TRAILING_TRIGGER_MULTIPLES"]
if os.environ.get("TRAILING_DISTANCE_MULTIPLES"):
    arguments["trailingDistanceMultiples"] = os.environ["TRAILING_DISTANCE_MULTIPLES"]
require_better_candidate = os.environ["REQUIRE_BETTER_CANDIDATE"].lower() == "true"

def call_tool(name, arguments, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments,
        },
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    data = json.loads(raw)
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

print("[trailing-stop-parameter-sweep] read-only production MCP check")
print("scope=READ_ONLY; no order/OCO/strategy/scheduler/grid/fund/Earn/Telegram/DB behavior changed")
print(
    "symbol={symbol} intervalCode={intervalCode} replayIntervalCode={replayIntervalCode} "
    "days={days} limit={limit} topN={topN} requireBetterCandidate={requireBetterCandidate}".format(
        requireBetterCandidate=str(require_better_candidate).lower(),
        **arguments,
    )
)

try:
    text = call_tool("analyzeTrailingStopParameterSweep", arguments, timeout=240)
except Exception as exc:
    print(f"FAIL: analyzeTrailingStopParameterSweep call failed: {exc}", file=sys.stderr)
    sys.exit(1)

print("")
print(text)
print("")

required_patterns = {
    "read-only boundary": r"boundary:\s*READ_ONLY",
    "backtest interval": r"backtestInterval:\s*" + re.escape(arguments["intervalCode"]),
    "replay interval": r"replayInterval:\s*" + re.escape(arguments["replayIntervalCode"]),
    "sample status": r"sampleStatus=(NO_REPLAYABLE_TRADES|REPLAYED|NO_REPLAYED_ROWS)",
    "acceptance target": r"acceptanceTarget: total trailing PnL improvement >= 5%",
    "ambiguous same-bar exclusion": r"acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals",
    "current policy marker": r"currentPolicy=breakevenAtr=0\.5 trailingTriggerAtr=1\.0 trailingDistanceAtr=1\.0",
    "parameter grid": r"parameterGrid=breakevenAtr",
    "operator action": r"operatorAction:\s*(REVIEW_PARAMETER_CANDIDATE_NOT_LIVE|NO_BETTER_PARAMETER_FOUND_IN_SWEEP|run or locate recent normalized backtests)",
}
for description, pattern in required_patterns.items():
    if not re.search(pattern, text):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

if "sampleStatus=NO_REPLAYABLE_TRADES" in text or "sampleStatus=NO_REPLAYED_ROWS" in text:
    print("[trailing-stop-parameter-sweep] NOT_PROVEN: deployed runtime is reachable, but the DB sample is insufficient for parameter sweep evidence.")
    sys.exit(0 if not require_better_candidate else 1)

for description, pattern in {
    "current policy summary": r"currentPolicySummary=policy=",
    "best policy summary": r"bestPolicySummary=policy=",
    "best versus current delta": r"bestVsCurrentDeltaPnl=",
    "top candidates": r"topCandidates:",
}.items():
    if not re.search(pattern, text):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

if "operatorAction: REVIEW_PARAMETER_CANDIDATE_NOT_LIVE" in text:
    print("[trailing-stop-parameter-sweep] REVIEW: a better read-only parameter candidate exists; do not apply it without separate design/deploy/live approval.")
    sys.exit(0)

print("[trailing-stop-parameter-sweep] NO_BETTER_PARAMETER_FOUND: sweep completed without a better candidate.")
sys.exit(1 if require_better_candidate else 0)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "trailing-stop parameter sweep smoke failed with exit code $LASTEXITCODE"
}
