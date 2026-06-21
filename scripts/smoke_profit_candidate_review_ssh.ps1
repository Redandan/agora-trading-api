param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$BlockedDays = 14,
    [int]$MissedHours = 168,
    [int]$TrailingLimit = 100
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

if ($ReviewDays -lt 1 -or $ReviewDays -gt 180) {
    throw "ReviewDays must be between 1 and 180."
}

if ($BlockedDays -lt 1 -or $BlockedDays -gt 60) {
    throw "BlockedDays must be between 1 and 60."
}

if ($MissedHours -lt 1 -or $MissedHours -gt 1440) {
    throw "MissedHours must be between 1 and 1440."
}

if ($TrailingLimit -lt 1 -or $TrailingLimit -gt 1000) {
    throw "TrailingLimit must be between 1 and 1000."
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

export PORT MCP_KEY SYMBOL='__SYMBOL__' REVIEW_DAYS='__REVIEW_DAYS__' BLOCKED_DAYS='__BLOCKED_DAYS__' MISSED_HOURS='__MISSED_HOURS__' TRAILING_LIMIT='__TRAILING_LIMIT__'
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
review_days = int(os.environ["REVIEW_DAYS"])
blocked_days = int(os.environ["BLOCKED_DAYS"])
missed_hours = int(os.environ["MISSED_HOURS"])
trailing_limit = int(os.environ["TRAILING_LIMIT"])

def call_tool(name, arguments=None, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": f"profit-candidate-review-{name}",
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
        sys.exit(1)

def compact(value, limit=700):
    text = str(value or "N/A").replace("\r", "").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit - 3] + "..."

def extract_total_pnl(monthly):
    totals = []
    for line in monthly.splitlines():
        if not re.match(r"^\d{4}-\d{2}\s+\|", line):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) >= 5:
            try:
                totals.append(float(parts[4]))
            except Exception:
                pass
    return round(sum(totals), 2) if totals else None

def extract_blocker_false_kill(blocked, blocker):
    pattern = rf"{re.escape(blocker)}:.*?falseKill\s+([0-9.]+)%.*?avgRet\s+([+-]?[0-9.]+)%"
    match = re.search(pattern, blocked)
    if not match:
        return None
    return {"falseKillPct": float(match.group(1)), "avgRetPct": float(match.group(2))}

def extract_json_object(text):
    try:
        return json.loads(text)
    except Exception:
        return {}

print("[profit-candidate-review] read-only production MCP check")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} blockedDays={blocked_days} missedHours={missed_hours} trailingLimit={trailing_limit} activePort={os.environ['PORT']}")

monthly = call_tool("getMonthlyPnlOverview", {"months": 3})
scorecard = call_tool("getStrategyScorecard", {"enabledOnly": True})
ev_gate = call_tool("getExpectedValueGateStats", {"days": review_days, "symbol": symbol})
accuracy = call_tool("getSignalAccuracyReport", {"days": min(blocked_days, 30)})
blocked = call_tool("analyzeBlockedSignalOutcomes", {"days": blocked_days, "symbol": symbol}, timeout=220)
missed = call_tool("getMissedOpportunityRegressionReport", {"symbol": symbol, "hours": missed_hours}, timeout=180)
truth_table = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": missed_hours, "limit": 50}, timeout=180)
shadow = call_tool("getShadowReadinessDashboard", {"days": review_days, "activationThreshold": 0.33})
shadow_candidates = call_tool("listShadowActivationCandidates", {})
trailing = call_tool("analyzeTrailingStopPnlReplay", {
    "symbol": symbol,
    "intervalCode": "1h",
    "replayIntervalCode": "1m",
    "days": review_days,
    "limit": trailing_limit,
}, timeout=240)

require("monthly PnL overview", r"SPOT\(USDT\)|Grid\(USDT\)|SWAP\(USDT\)", monthly)
require("strategy scorecard", r"Strategy Scorecard", scorecard)
require("EV gate runtime stats", r"ExpectedValueGate Runtime Stats", ev_gate)
require("signal accuracy read-only boundary", r"mode=READ_ONLY", accuracy)
require("blocked-signal read-only boundary", r"mode=READ_ONLY", blocked)
require("missed opportunity read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', missed)
require("truth table read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', truth_table)
require("shadow readiness dashboard", r"Shadow Readiness Dashboard", shadow)
require("shadow activation candidates", r"assessActivationRisk|Shadow", shadow_candidates)
require("trailing replay read-only boundary", r"boundary:\s*READ_ONLY", trailing)

total_pnl = extract_total_pnl(monthly)
data_freshness = extract_blocker_false_kill(blocked, "DataFreshnessGuard")
expected_value_block = extract_blocker_false_kill(blocked, "ExpectedValueGate")
missed_json = extract_json_object(missed)
missed_status = missed_json.get("overallStatus", "UNKNOWN")
false_block_risk_count = int(missed_json.get("falseBlockRiskCount", 0) or 0)
suspicious_no_buy_count = int(missed_json.get("suspiciousNoBuyCount", 0) or 0)

ev_acceptance = "PASS" if re.search(r"acceptance:\s*PASS", ev_gate) else "REVIEW"
ev_blocked_produced_order = "NO" if re.search(r"blockedCandidateProducedOrder=NO", ev_gate) else "UNKNOWN"
trailing_acceptance = (re.search(r"acceptance=([A-Z_]+)", trailing) or [None, "UNKNOWN"])[1]
trailing_delta = (re.search(r"acceptanceDeltaPnl=([-+0-9.]+)", trailing) or [None, "N/A"])[1]
low_sample_count = len(re.findall(r"SHADOW_READY_LOW_SAMPLE", shadow))
ready_count = len(re.findall(r"TINY_LIVE_READY_RESTRICTED_RISK|AUTONOMOUS_READY_CANONICAL", shadow))
near_buy_rows = len(re.findall(r"WATCH_SIGNAL_NEAR_BUY_THRESHOLD", truth_table))

candidate_items = []
if total_pnl is not None and total_pnl < 0:
    candidate_items.append("PRIORITIZE_LOSS_SOURCE_REDUCTION")
if data_freshness and data_freshness["falseKillPct"] >= 50 and data_freshness["avgRetPct"] > 0:
    candidate_items.append("REVIEW_DATAFRESHNESS_FALSE_KILL_IN_SHADOW")
if expected_value_block and expected_value_block["falseKillPct"] <= 20 and ev_acceptance == "PASS":
    candidate_items.append("KEEP_EXPECTED_VALUE_GATE")
if false_block_risk_count > 0 or suspicious_no_buy_count > 0:
    candidate_items.append("REVIEW_MISSED_OPPORTUNITY_HOLD_ROWS")
if trailing_acceptance != "PASS":
    candidate_items.append("DO_NOT_ENABLE_TRAILING_STOP_OVERLAY")
if ready_count == 0 and low_sample_count > 0:
    candidate_items.append("COLLECT_MORE_SHADOW_SAMPLES_BEFORE_ACTIVATION")
if near_buy_rows > 0:
    candidate_items.append("OBSERVE_NEAR_BUY_THRESHOLD_NO_POLICY_RELAXATION")

if "REVIEW_DATAFRESHNESS_FALSE_KILL_IN_SHADOW" in candidate_items:
    recommendation = "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY"
elif "REVIEW_MISSED_OPPORTUNITY_HOLD_ROWS" in candidate_items:
    recommendation = "REVIEW_MISSED_OPPORTUNITY_HOLD_ROWS"
elif "PRIORITIZE_LOSS_SOURCE_REDUCTION" in candidate_items:
    recommendation = "PRIORITIZE_LOSS_SOURCE_REDUCTION"
else:
    recommendation = "NO_PROFIT_CANDIDATE_FROM_CURRENT_EVIDENCE"

print("")
print("PnL / Strategy Baseline:")
print(f"  monthlyPnlTotalUsdt={total_pnl if total_pnl is not None else 'N/A'}")
print("  monthlyPnl=" + compact(monthly, 900))
print("  strategyScorecard=" + compact(scorecard, 900))
print("")
print("Gate / Signal Evidence:")
print(f"  expectedValueGateAcceptance={ev_acceptance}")
print(f"  expectedValueBlockedProducedOrder={ev_blocked_produced_order}")
print(f"  dataFreshnessFalseKillPct={data_freshness['falseKillPct'] if data_freshness else 'N/A'}")
print(f"  dataFreshnessAvgRetPct={data_freshness['avgRetPct'] if data_freshness else 'N/A'}")
print(f"  expectedValueFalseKillPct={expected_value_block['falseKillPct'] if expected_value_block else 'N/A'}")
print(f"  expectedValueAvgRetPct={expected_value_block['avgRetPct'] if expected_value_block else 'N/A'}")
print(f"  missedOpportunityStatus={missed_status}")
print(f"  falseBlockRiskCount={false_block_risk_count}")
print(f"  suspiciousNoBuyCount={suspicious_no_buy_count}")
print(f"  nearBuyTruthTableRows={near_buy_rows}")
print("  blockedSignalOutcomes=" + compact(blocked, 900))
print("  missedOpportunity=" + compact(missed, 900))
print("  noBuyTruthTable=" + compact(truth_table, 900))
print("")
print("Backtest / Shadow / Exit Evidence:")
print(f"  trailingReplayAcceptance={trailing_acceptance}")
print(f"  trailingReplayDeltaPnl={trailing_delta}")
print(f"  shadowReadyLowSampleCount={low_sample_count}")
print(f"  shadowReadyActivationCount={ready_count}")
print("  shadowReadiness=" + compact(shadow, 900))
print("  shadowActivationCandidates=" + compact(shadow_candidates, 900))
print("  trailingReplay=" + compact(trailing, 900))
print("")
print("Candidate Summary:")
print("  profit_candidate_items=" + json.dumps(candidate_items, ensure_ascii=False))
print(f"  profit_candidate_review_recommendation={recommendation}")
print("  notAuthorization=read-only evidence only; does not authorize live trading, closing positions, OCO modification, strategy activation, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, or policy relaxation")
print("")
print("[profit-candidate-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__BLOCKED_DAYS__", [string]$BlockedDays).
    Replace("__MISSED_HOURS__", [string]$MissedHours).
    Replace("__TRAILING_LIMIT__", [string]$TrailingLimit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "profit candidate review smoke failed with exit code $LASTEXITCODE"
}
