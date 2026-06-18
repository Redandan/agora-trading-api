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
    [switch]$RequireAcceptance
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
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode -MaxLength 21

$requireAcceptanceText = if ($RequireAcceptance.IsPresent) { "true" } else { "false" }
$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' INTERVAL_CODE='$IntervalCode' REPLAY_INTERVAL_CODE='$ReplayIntervalCode' DAYS='$Days' LIMIT='$Limit' REQUIRE_ACCEPTANCE='$requireAcceptanceText'
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
}
require_acceptance = os.environ["REQUIRE_ACCEPTANCE"].lower() == "true"

def call_tool(name, arguments, timeout=120):
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

print("[trailing-stop-pnl-replay] read-only production MCP check")
print(f"symbol={arguments['symbol']} intervalCode={arguments['intervalCode']} replayIntervalCode={arguments['replayIntervalCode']} days={arguments['days']} limit={arguments['limit']} requireAcceptance={str(require_acceptance).lower()}")

try:
    text = call_tool("analyzeTrailingStopPnlReplay", arguments, timeout=180)
except Exception as exc:
    print(f"FAIL: analyzeTrailingStopPnlReplay call failed: {exc}", file=sys.stderr)
    sys.exit(1)

print("")
print(text)
print("")

required_patterns = {
    "read-only boundary": r"boundary:\s*READ_ONLY",
    "backtest interval": r"backtestInterval:\s*" + re.escape(arguments["intervalCode"]),
    "replay interval": r"replayInterval:\s*" + re.escape(arguments["replayIntervalCode"]),
    "split interval semantics": r"replayIntervalNote=backtest interval selects normalized trades",
    "sample status": r"sampleStatus=(NO_REPLAYABLE_TRADES|REPLAYED|NO_REPLAYED_ROWS)",
    "acceptance target": r"acceptanceTarget: total trailing PnL improvement >= 5%",
    "ambiguous same-bar exclusion": r"acceptanceNote=ambiguousSameBar rows are excluded from PnL acceptance totals",
    "acceptance blocker": r"acceptanceBlocker=(NO_REPLAYABLE_TRADES|NO_REPLAYED_ROWS|ALL_REPLAYED_ROWS_AMBIGUOUS|NO_NON_AMBIGUOUS_ACCEPTANCE_ROWS|ZERO_OR_MISSING_ORIGINAL_PNL|CURRENT_PARAMETERS_NO_PNL_IMPROVEMENT|BELOW_ACCEPTANCE_TARGET|NONE)",
    "acceptance blocker detail": r"acceptanceBlockerDetail=",
}
for description, pattern in required_patterns.items():
    if not re.search(pattern, text):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

if "sampleStatus=NO_REPLAYABLE_TRADES" in text or "sampleStatus=NO_REPLAYED_ROWS" in text:
    print("[trailing-stop-pnl-replay] NOT_PROVEN: deployed runtime is reachable, but the DB sample is insufficient for 30d PnL acceptance.")
    sys.exit(0 if not require_acceptance else 1)

if "acceptance=PASS" in text:
    print("[trailing-stop-pnl-replay] PASS: 30d replay met the >=5% PnL improvement target.")
    sys.exit(0)

print("[trailing-stop-pnl-replay] NOT_PROVEN: replay ran but did not meet acceptance=PASS.")
sys.exit(1 if require_acceptance else 0)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "trailing-stop PnL replay smoke failed with exit code $LASTEXITCODE"
}
