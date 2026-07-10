param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG"
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

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
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
Assert-McpSmokeTokenSafe -Name "Side" -Value $Side -MaxLength 16

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' SIDE='$Side'
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
strategy_id = int(os.environ["STRATEGY_ID"])
side = os.environ["SIDE"].upper()

def call_tool(name, arguments, timeout=120):
    body = {
        "jsonrpc": "2.0",
        "id": f"strategy574-signal-governance-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
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
        sys.exit(1)

def parse_json_object(text):
    try:
        value = json.loads(text)
    except Exception:
        return {}
    return value if isinstance(value, dict) else {}

def compact(value, limit=220):
    text = str(value or "N/A").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit - 3] + "..."

def regex(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default

def find_strategy_rows(container, path):
    rows = []
    for row in container:
        if isinstance(row, dict) and str(row.get("strategyId", "")) == str(strategy_id):
            rows.append((path, row))
    return rows

print("[strategy574-signal-governance] read-only production MCP check")
print(f"scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed.")
print(f"symbol={symbol} strategyId={strategy_id} side={side} activePort={os.environ['PORT']}")

window_modes = {}
for days in (1, 3, 7, 14):
    governance = call_tool("getGovernanceDriftDashboard", {"symbol": symbol, "days": days, "labelHorizon": "24h"})
    relaxation = call_tool("findGovernanceRelaxationCandidates", {"symbol": symbol, "days": days, "labelHorizon": "24h"})
    require("governance drift read-only boundary", r"boundary:\s*READ_ONLY", governance)
    require("governance relaxation read-only boundary", r"boundary:\s*READ_ONLY", relaxation)
    require("governance relaxation criteria", r"criteria:", relaxation)
    mode = regex(r"governanceMode=([A-Z_]+)", governance)
    relaxation_lines = [line for line in relaxation.splitlines() if line.startswith("- blocker=")]
    window_modes[days] = mode
    print(f"window={days}d governanceMode={mode} relaxationCount={len(relaxation_lines)}")
    for line in relaxation_lines[:3]:
        print("  relaxationCandidate=" + compact(line, 360))

freshness = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": 7, "symbol": symbol, "limit": 50})
missed = parse_json_object(call_tool("getMissedOpportunityRegressionReport", {"symbol": symbol, "hours": 168}))
truth = parse_json_object(call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": 168, "limit": 50}))
trigger = call_tool("getTinyLiveAutoExecutionTriggerStatus", {"symbol": symbol, "strategyId": strategy_id, "side": side})
readiness = call_tool("getAutonomousReadinessDashboard", {"symbol": symbol, "strategyId": strategy_id, "side": side})

require("DataFreshnessGuard read-only boundary", r"boundary:\s*READ_ONLY", freshness)
require("missed opportunity read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', json.dumps(missed))
require("missed opportunity no order marker", r'"orderSent"\s*:\s*false', json.dumps(missed))
require("no-buy truth table read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', json.dumps(truth))
require("no-buy truth table no order marker", r'"orderSent"\s*:\s*false', json.dumps(truth))
require("TinyLive trigger read-only boundary", r"boundary:\s*READ_ONLY", trigger)
require("TinyLive trigger no order marker", r"orderSent=false", trigger)
require("autonomous readiness read-only boundary", r"boundary:\s*READ_ONLY", readiness)
require("autonomous readiness target no order marker", r"targetOrderSentEvidence=0", readiness)
require("autonomous readiness other strategy order marker", r"otherStrategyOrderSentEvidence=\d+", readiness)

strategy_rows = []
strategy_rows += find_strategy_rows(missed.get("rows") or [], "missed.rows")
strategy_rows += find_strategy_rows(missed.get("highForwardReturnNoBuyExamples") or [], "missed.highForwardReturnNoBuyExamples")
strategy_rows += find_strategy_rows(truth.get("rows") or [], "truth.rows")

print("")
print("Freshness Current Snapshot:")
stale_now = regex(r"staleNowKeys=(\d+)", freshness)
no_data_now = regex(r"noDataNowKeys=(\d+)", freshness)
query_failed_now = regex(r"queryFailedNowKeys=(\d+)", freshness)
print(f"  staleNowKeys={stale_now} noDataNowKeys={no_data_now} queryFailedNowKeys={query_failed_now}")
print("")
print("Missed Opportunity Summary:")
print("  " + json.dumps({
    "overallStatus": missed.get("overallStatus"),
    "suspiciousNoBuyCount": missed.get("suspiciousNoBuyCount"),
    "falseBlockRiskCount": missed.get("falseBlockRiskCount"),
    "highForwardReturnNoBuyCount": missed.get("highForwardReturnNoBuyCount"),
    "dedupTooCoarseSuspects": missed.get("dedupTooCoarseSuspects"),
    "genericStagedAddWouldAllowGroups": missed.get("genericStagedAddWouldAllowGroups"),
    "recommendedFix": missed.get("recommendedFix"),
}, ensure_ascii=False, separators=(",", ":")))
print("")
print("Strategy 574 No-Buy Rows:")
print(f"  strategy574RowCount={len(strategy_rows)}")
near_buy = False
data_freshness_current_clean = stale_now == "0" and no_data_now == "0" and query_failed_now == "0"
for source, row in strategy_rows[:12]:
    evidence = row.get("evidence") if isinstance(row.get("evidence"), dict) else {}
    warnings = row.get("warnings") if isinstance(row.get("warnings"), list) else []
    blockers = row.get("blockers") if isinstance(row.get("blockers"), list) else []
    if str(row.get("classification", "")) == "WATCH_SIGNAL_NEAR_BUY_THRESHOLD" or any("NEAR_BUY_THRESHOLD" in str(w) for w in warnings):
        near_buy = True
    slim = {
        "source": source,
        "strategyId": row.get("strategyId"),
        "path": row.get("path"),
        "classification": row.get("classification"),
        "selectedAction": row.get("selectedAction"),
        "terminalBlocker": row.get("terminalBlocker"),
        "blockerReason": row.get("blockerReason"),
        "forwardReturn1hPct": row.get("forwardReturn1hPct"),
        "primaryNoBuyReason": evidence.get("primaryNoBuyReason"),
        "nextRequiredAction": evidence.get("nextRequiredAction"),
        "blockers": blockers[:5],
        "warnings": warnings[:5],
    }
    print("  row=" + json.dumps({k: v for k, v in slim.items() if v not in (None, [], "")}, ensure_ascii=False, separators=(",", ":")))

print("")
print("TinyLive Trigger / Readiness:")
print("  trigger=" + compact(trigger, 700))
print("  readiness=" + compact(readiness, 700))

governance_too_strict = window_modes.get(7) == "TOO_STRICT" or window_modes.get(14) == "TOO_STRICT"
insufficient_short_window = window_modes.get(1) == "INSUFFICIENT_DATA" or window_modes.get(3) == "INSUFFICIENT_DATA"
terminal_reason = "WAIT_BUY_THRESHOLD_CROSS" if near_buy else "REVIEW_NO_BUY_ROWS"
if not data_freshness_current_clean:
    terminal_reason = "FIX_CURRENT_DATA_FRESHNESS_FIRST"
policy_action = "DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE"
if data_freshness_current_clean and near_buy and governance_too_strict:
    policy_action = "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS"

print("")
print("Conclusion:")
print(f"  strategy574_near_buy={str(near_buy).lower()}")
print(f"  governance_too_strict_7d_or_14d={str(governance_too_strict).lower()}")
print(f"  short_window_insufficient_data={str(insufficient_short_window).lower()}")
print(f"  data_freshness_current_clean={str(data_freshness_current_clean).lower()}")
print(f"  strategy574_terminal_reason={terminal_reason}")
print(f"  policy_change_recommendation={policy_action}")
print("  notAuthorization=read-only evidence only; does not authorize live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, or policy relaxation")

print("")
print("[strategy574-signal-governance] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "strategy 574 signal governance smoke failed with exit code $LASTEXITCODE"
}
