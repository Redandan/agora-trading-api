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
from datetime import datetime

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

def count_before_marker(text, marker):
    index = text.lower().find(marker.lower())
    if index < 0:
        return None
    prefix = text[:index].rstrip()
    digits = []
    for char in reversed(prefix):
        if char.isdigit():
            digits.append(char)
        elif digits:
            break
    if not digits:
        return None
    return int("".join(reversed(digits)))

def oco_health_ok(oco_text):
    sync_count = count_before_marker(oco_text, "SYNC_ERROR")
    abnormal_count = count_before_marker(oco_text, "異常")
    if abnormal_count is None:
        abnormal_count = count_before_marker(oco_text, "abnormal")
    if abnormal_count is None:
        return sync_count == 0 and "OCO active" in oco_text
    return sync_count == 0 and abnormal_count == 0

def no_open_auto_trade_position(oco_text):
    return ("無開倉中的自動交易倉位" in oco_text
            or "no open auto" in oco_text.lower()
            or "no open position" in oco_text.lower())

assert oco_health_ok("✅ 3 OK | 🔴 0 SYNC_ERROR | ⚠️ 0 異常")
assert oco_health_ok("3 OK | 0 SYNC_ERROR | 0 abnormal")
assert oco_health_ok("Position #1 BTCUSDT — OCO active (live) | 0 SYNC_ERROR")
assert no_open_auto_trade_position("✅ 無開倉中的自動交易倉位")

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

def parse_position_details(open_positions):
    now = datetime.now()
    details = {}
    pattern = re.compile(
        r"ID:\s*(?P<id>\d+).*?"
        r"入場價:\s*(?P<entry>[0-9.]+).*?"
        r"數量:\s*(?P<quantity>[0-9.]+).*?"
        r"TP:\s*(?P<tp>[0-9.]+)\s*\|\s*SL:\s*(?P<sl>[0-9.]+).*?"
        r"algoId=(?P<algoId>\d+).*?"
        r"開倉時間:\s*(?P<entryTime>[0-9]{2}-[0-9]{2}\s+[0-9]{2}:[0-9]{2})",
        re.S,
    )
    for match in pattern.finditer(open_positions):
        entry_time = match.group("entryTime")
        entry_age_days = "N/A"
        try:
            entry_dt = datetime.strptime(f"{now.year}-{entry_time}", "%Y-%m-%d %H:%M")
            if entry_dt > now:
                entry_dt = entry_dt.replace(year=now.year - 1)
            entry_age_days = round((now - entry_dt).total_seconds() / 86400, 2)
        except Exception:
            pass
        details[int(match.group("id"))] = {
            "entryPrice": match.group("entry"),
            "quantity": match.group("quantity"),
            "takeProfit": match.group("tp"),
            "stopLoss": match.group("sl"),
            "ocoAlgoId": match.group("algoId"),
            "entryTime": entry_time,
            "entryAgeDays": entry_age_days,
        }
    return details

def ev_summary(position_id, position_details):
    ev = call_tool("reassessActivePositionEv", {"positionId": position_id, "symbol": symbol, "horizonHours": 168})
    require("position EV read-only boundary", r"boundary:\s*READ_ONLY", ev)
    decision = re.search(r"Decision:\s*([A-Z_]+)", ev)
    suggestion = re.search(r"Suggestion:\s*([A-Z_]+)", ev)
    ev_value = re.search(r"EV:\s*([-+0-9.]+)\s*USDT", ev)
    current = re.search(r"Current:\s*[^0-9+-]*([0-9.]+)\s*\(([-+0-9.]+)%\)", ev)
    details = position_details.get(position_id, {})
    return {
        "positionId": position_id,
        "decision": decision.group(1) if decision else "N/A",
        "suggestion": suggestion.group(1) if suggestion else "N/A",
        "evUsdt": ev_value.group(1) if ev_value else "N/A",
        "paperPct": current.group(2) if current else "N/A",
        "entryTime": details.get("entryTime", "N/A"),
        "entryAgeDays": details.get("entryAgeDays", "N/A"),
        "entryPrice": details.get("entryPrice", "N/A"),
        "takeProfit": details.get("takeProfit", "N/A"),
        "stopLoss": details.get("stopLoss", "N/A"),
        "ocoAlgoId": details.get("ocoAlgoId", "N/A"),
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
if not (oco_health_ok(oco) or no_open_auto_trade_position(oco)):
    print("FAIL: missing healthy OCO marker; expected 0 SYNC_ERROR or no open auto-trade position", file=sys.stderr)
    sys.exit(1)
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
position_details = parse_position_details(open_positions)
ev_rows = [ev_summary(pid, position_details) for pid in position_ids]

aged_events = len(re.findall(r"POSITION_TIMEOUT", events))
tp_watch = len(re.findall(r"status=WATCH", tp_stretch))
tp_stretched = len(re.findall(r"status=STRETCHED|stretched=([1-9]\d*)", tp_stretch))
oco_ok = oco_health_ok(oco) or no_open_auto_trade_position(oco)
negative_ev = [row for row in ev_rows if row["evUsdt"] not in ("N/A", "") and float(row["evUsdt"]) < 0]
close_or_modify = [row for row in ev_rows if row["suggestion"] in ("CLOSE", "MODIFY")]
paper_loss_review = [row for row in ev_rows if row["paperPct"] not in ("N/A", "") and float(row["paperPct"]) <= -8.0]
ev_loss_review = [row for row in ev_rows if row["evUsdt"] not in ("N/A", "") and float(row["evUsdt"]) <= -0.50]
risk_triggers = []
if aged_events > 0:
    risk_triggers.append("positionTimeoutEvents>0")
if tp_watch > 0:
    risk_triggers.append("tpStretchWatchCount>0")
if tp_stretched > 0:
    risk_triggers.append("tpStretchStretchedCount>0")
if paper_loss_review:
    risk_triggers.append("paperPct<=-8")
if ev_loss_review:
    risk_triggers.append("evUsdt<=-0.50")
review_ready_by_risk = bool(close_or_modify and negative_ev and oco_ok and risk_triggers)

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
    print(f"  position={row['positionId']} entryTime={row['entryTime']} entryAgeDays={row['entryAgeDays']} entry={row['entryPrice']} tp={row['takeProfit']} sl={row['stopLoss']} ocoAlgoId={row['ocoAlgoId']} decision={row['decision']} suggestion={row['suggestion']} evUsdt={row['evUsdt']} paperPct={row['paperPct']}")
print("")
print("TP Stretch / Aging:")
print(f"  positionTimeoutEvents={aged_events}")
print(f"  tpStretchWatchCount={tp_watch}")
print(f"  tpStretchStretchedCount={tp_stretched}")
print(f"  reviewRiskTriggers={risk_triggers}")
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
elif review_ready_by_risk:
    recommendation = "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY"
elif negative_ev:
    recommendation = "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED"
elif tp_watch > 0:
    recommendation = "WATCH_TP_STRETCH"
else:
    recommendation = "NO_POSITION_RISK_ACTION"

required_evidence = [
    "fresh OCO health",
    "active-position EV reassessment",
    "TP stretch and aging evidence",
    "stop-sweep policy review",
    "recent-closed PnL context",
    "monthly PnL context",
    "separate operator approval before any close-position or OCO mutation",
]
if recommendation == "FIX_OCO_PROTECTION_FIRST":
    next_action = "Review OCO protection evidence first; do not close positions or modify OCO from this smoke."
elif recommendation == "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY":
    next_action = "Draft a read-only operator review packet for aged negative-EV strategy 485 positions; separate explicit authorization is required before any close-position or OCO mutation."
elif recommendation == "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED":
    next_action = "Keep read-only monitoring of negative EV while OCO is protected; collect fresh EV and TP-stretch evidence before any operator packet."
elif recommendation == "WATCH_TP_STRETCH":
    next_action = "Continue read-only TP-stretch observation; no position or OCO action is authorized."
elif recommendation == "NO_OPEN_STRATEGY485_POSITION":
    next_action = "No strategy 485 open-position review packet is needed from current evidence."
else:
    next_action = "No strategy 485 position-risk action is recommended from current evidence."

review_decision = {
    "decision": recommendation,
    "symbol": symbol,
    "strategyId": strategy_id,
    "canDraftOperatorReviewPacket": recommendation == "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
    "positionOrOcoMutationAllowed": False,
    "closePositionAllowed": False,
    "orderAllowed": False,
    "telegramSendAllowed": False,
    "schedulerEnablementAllowed": False,
    "ocoHealthOk": oco_ok,
    "openPositionCount": len(position_ids),
    "negativeEvPositionCount": len(negative_ev),
    "closeOrModifySuggestionCount": len(close_or_modify),
    "positionTimeoutEventCount": aged_events,
    "tpStretchWatchCount": tp_watch,
    "tpStretchStretchedCount": tp_stretched,
    "reviewRiskTriggerCount": len(risk_triggers),
    "reviewRiskTriggers": risk_triggers,
    "positions": [
        {
            "positionId": row["positionId"],
            "decision": row["decision"],
            "suggestion": row["suggestion"],
            "evUsdt": row["evUsdt"],
            "paperPct": row["paperPct"],
            "entryTime": row["entryTime"],
            "entryAgeDays": row["entryAgeDays"],
            "entryPrice": row["entryPrice"],
            "takeProfit": row["takeProfit"],
            "stopLoss": row["stopLoss"],
            "ocoAlgoId": row["ocoAlgoId"],
        }
        for row in ev_rows
    ],
    "requiredEvidence": required_evidence,
    "nextAction": next_action,
    "notAuthorization": "read-only strategy 485 routing decision only; does not authorize close-position, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutation, DB changes, deploy, restart, production env mutation, external backfill/import, or policy relaxation",
}

print("")
print("Conclusion:")
print(f"  openStrategy485Positions={len(position_ids)}")
print(f"  negativeEvPositions={len(negative_ev)}")
print(f"  closeOrModifySuggestions={len(close_or_modify)}")
print(f"  positionTimeoutEvents={aged_events}")
print(f"  reviewRiskTriggerCount={len(risk_triggers)}")
print("  reviewRiskTriggers=" + json.dumps(risk_triggers, ensure_ascii=False, separators=(",", ":")))
print("  close_position_allowed=false")
print("  position_or_oco_mutation_allowed=false")
print("  order_allowed=false")
print("  telegram_send_allowed=false")
print("  scheduler_enablement_allowed=false")
print(f"  strategy485_position_risk_recommendation={recommendation}")
print("  strategy485_position_review_decision=" + json.dumps(review_decision, ensure_ascii=False, separators=(",", ":")))
print("  notAuthorization=read-only evidence only; does not authorize closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, or policy relaxation")
print("")
print("[strategy485-position-risk] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "strategy 485 position risk smoke failed with exit code $LASTEXITCODE"
}
