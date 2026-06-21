param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 485,
    [int]$Days = 30,
    [int]$PositionAgeWarnDays = 5
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

if ($Days -lt 1 -or $Days -gt 180) {
    throw "Days must be between 1 and 180."
}

if ($PositionAgeWarnDays -lt 1 -or $PositionAgeWarnDays -gt 90) {
    throw "PositionAgeWarnDays must be between 1 and 90."
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

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' DAYS='$Days' AGE_WARN_DAYS='$PositionAgeWarnDays'
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
days = int(os.environ["DAYS"])
age_warn_days = int(os.environ["AGE_WARN_DAYS"])

def call_tool(name, arguments=None, timeout=160):
    body = {
        "jsonrpc": "2.0",
        "id": f"strategy485-position-risk-{name}",
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

def compact(value, limit=260):
    text = str(value or "N/A").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit - 3] + "..."

def extract_position_ids(open_positions):
    ids = []
    all_ids = []
    saw_strategy_marker = False
    current_id = None
    current_strategy = None
    for line in open_positions.splitlines():
        match_id = re.search(r"\bID:\s*(\d+)", line)
        if match_id:
            if current_id is not None and current_strategy == strategy_id:
                ids.append(current_id)
            current_id = int(match_id.group(1))
            all_ids.append(current_id)
            current_strategy = None
            continue
        match_strategy = re.search(r"strat(?:egy)?[=#:\s]+(\d+)", line, re.IGNORECASE)
        if match_strategy:
            saw_strategy_marker = True
            current_strategy = int(match_strategy.group(1))
    if current_id is not None and (current_strategy == strategy_id or current_strategy is None):
        ids.append(current_id)
    if not saw_strategy_marker:
        return all_ids
    return ids

def ev_summary(position_id):
    ev = call_tool("reassessActivePositionEv", {"positionId": position_id, "symbol": symbol, "horizonHours": 168})
    require("position EV read-only boundary", r"boundary:\s*READ_ONLY", ev)
    decision = re.search(r"Decision:\s*([A-Z_]+)", ev)
    suggestion = re.search(r"Suggestion:\s*([A-Z_]+)", ev)
    ev_value = re.search(r"EV:\s*([-+0-9.]+)\s*USDT", ev)
    current = re.search(r"Current:\s*[^0-9+-]*([0-9.]+)\s*\(([-+0-9.]+)%\)", ev)
    return {
        "positionId": position_id,
        "decision": decision.group(1) if decision else "N/A",
        "suggestion": suggestion.group(1) if suggestion else "N/A",
        "evUsdt": ev_value.group(1) if ev_value else "N/A",
        "paperPct": current.group(2) if current else "N/A",
        "raw": ev,
    }

print("[strategy485-position-risk] read-only production MCP check")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed.")
print(f"symbol={symbol} strategyId={strategy_id} days={days} ageWarnDays={age_warn_days} activePort={os.environ['PORT']}")

digest = call_tool("getTradingManagerDigest", {"deep": True, "days": days, "symbol": symbol})
open_positions = call_tool("listOpenPositions", {"symbol": symbol})
recent_closed = call_tool("listRecentClosed", {"symbol": symbol, "limit": 20})
oco = call_tool("getOcoHealth", {"symbol": symbol})
events = call_tool("listExecutionEvents", {"symbol": symbol, "limit": 50})
defense = call_tool("getPositionDefenseStatus", {"symbol": symbol})
plan = call_tool("previewPositionDefensePlan", {"symbol": symbol})
tp_stretch = call_tool("analyzeTpStretchProtection", {"symbol": symbol})
stop_sweep = call_tool("analyzeStopSweepRisk", {"symbol": symbol, "days": min(days, 14)})
monthly = call_tool("getMonthlyPnlOverview", {"symbol": symbol, "months": 3})

require("manager digest", r"Trading Manager Digest", digest)
require("OCO health OK marker", r"0 SYNC_ERROR", oco)
require("execution events read-only boundary", r"boundary:\s*READ_ONLY", events)
require("position defense read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', defense)
require("position defense no order marker", r'"orderSent"\s*:\s*false', defense)
require("position defense no OCO marker", r'"ocoModified"\s*:\s*false', defense)
require("position defense plan read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', plan)
require("position defense plan no order marker", r'"orderSent"\s*:\s*false', plan)
require("position defense plan no OCO marker", r'"ocoModified"\s*:\s*false', plan)
require("TP stretch read-only boundary", r"boundary:\s*READ_ONLY", tp_stretch)
require("stop sweep read-only boundary", r"boundary:\s*READ_ONLY", stop_sweep)

position_ids = extract_position_ids(open_positions)
if not position_ids:
    position_ids = [int(x) for x in re.findall(r"Position #(\d+).*?\\[SB_ADD\\]", tp_stretch)]
position_ids = sorted(set(position_ids))
ev_rows = [ev_summary(pid) for pid in position_ids]

aged_events = len(re.findall(r"POSITION_TIMEOUT", events))
tp_watch = len(re.findall(r"status=WATCH", tp_stretch))
tp_stretched = len(re.findall(r"status=STRETCHED|stretched=([1-9]\d*)", tp_stretch))
oco_ok = "0 SYNC_ERROR" in oco and re.search(r"⚠️\s*0", oco) is not None
negative_ev = [row for row in ev_rows if row["evUsdt"] not in ("N/A", "") and float(row["evUsdt"]) < 0]
close_or_modify = [row for row in ev_rows if row["suggestion"] in ("CLOSE", "MODIFY")]

print("")
print("Open Strategy 485 Positions:")
print(f"  positionIds={position_ids}")
print("  openPositions=" + compact(open_positions, 900))
print("")
print("OCO / Protection:")
print(f"  ocoHealthOk={str(oco_ok).lower()}")
print("  ocoHealth=" + compact(oco, 700))
print("")
print("Position EV:")
for row in ev_rows:
    print(f"  position={row['positionId']} decision={row['decision']} suggestion={row['suggestion']} evUsdt={row['evUsdt']} paperPct={row['paperPct']}")
print("")
print("TP Stretch / Aging:")
print(f"  positionTimeoutEvents={aged_events}")
print(f"  tpStretchWatchCount={tp_watch}")
print(f"  tpStretchStretchedCount={tp_stretched}")
print("  tpStretch=" + compact(tp_stretch, 900))
print("")
print("Recent Closed / PnL:")
print("  recentClosed=" + compact(recent_closed, 900))
print("  monthlyPnl=" + compact(monthly, 900))
print("")
print("Stop Sweep:")
print("  stopSweep=" + compact(stop_sweep, 900))

if not position_ids:
    recommendation = "NO_OPEN_STRATEGY485_POSITION"
elif not oco_ok:
    recommendation = "FIX_OCO_PROTECTION_FIRST"
elif close_or_modify and aged_events > 0:
    recommendation = "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY"
elif negative_ev:
    recommendation = "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED"
elif tp_watch > 0:
    recommendation = "WATCH_TP_STRETCH"
else:
    recommendation = "NO_POSITION_RISK_ACTION"

print("")
print("Conclusion:")
print(f"  openStrategy485Positions={len(position_ids)}")
print(f"  negativeEvPositions={len(negative_ev)}")
print(f"  closeOrModifySuggestions={len(close_or_modify)}")
print(f"  positionTimeoutEvents={aged_events}")
print(f"  strategy485_position_risk_recommendation={recommendation}")
print("  notAuthorization=read-only evidence only; does not authorize closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, or policy relaxation")
print("")
print("[strategy485-position-risk] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "strategy 485 position risk smoke failed with exit code $LASTEXITCODE"
}
