param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [int]$Hours = 24,
    [switch]$RequireExecution
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) { throw "StrategyId must be between 1 and 999999999." }
if ($Hours -lt 1 -or $Hours -gt 720) { throw "Hours must be between 1 and 720." }

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

PORT=`$(tr -d '[:space:]' < app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' SIDE='$Side' HOURS='$Hours' REQUIRE_EXECUTION='$($RequireExecution.IsPresent)'
python3 - <<'PY'
import json
import os
import re
import sys
from datetime import datetime, timezone, timedelta
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": f"Bearer {os.environ['MCP_KEY']}"}
symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
side = os.environ["SIDE"].upper()
hours = int(os.environ["HOURS"])
minutes = hours * 60
require_execution = os.environ.get("REQUIRE_EXECUTION", "").lower() == "true"

def request(body, timeout=180):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}") from exc
    data = json.loads(raw)
    if data.get("error"):
        raise RuntimeError(data["error"])
    return data

def call_tool(name, arguments=None, timeout=180):
    data = request({
        "jsonrpc": "2.0",
        "id": f"tiny-live-post-trade-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }, timeout=timeout)
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    text = content[0].get("text") if content and isinstance(content[0], dict) else json.dumps(result, ensure_ascii=False)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text or ""

def require(description, pattern, text):
    if not re.search(pattern, text, re.MULTILINE):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

def field(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def compact(text, limit=360):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

def latest_execution_line(executions):
    for line in executions.splitlines():
        if re.match(r"\d+\. #", line):
            return line.strip()
    return "N/A"

def parse_audit_id(line):
    match = re.search(r"#(\d+)", line)
    return match.group(1) if match else "N/A"

def parse_created_at(line):
    match = re.search(r"createdAt=([0-9T:.\-]+)", line)
    if not match:
        return None
    value = match.group(1)
    try:
        return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)
    except ValueError:
        return None

def in_window(line):
    created_at = parse_created_at(line)
    if created_at is None:
        return False
    return created_at >= datetime.now(timezone.utc) - timedelta(hours=hours)

def has_current_execution(line):
    if line == "N/A":
        return False
    if not in_window(line):
        return False
    if f"symbol={symbol}" not in line or f"strategy={strategy_id}" not in line or f"side={side}" not in line:
        return False
    return any(status in line for status in ["status=EXECUTED_OCO_ATTACHED", "status=EXECUTED"])

def has_oco_attached(line):
    return "status=EXECUTED_OCO_ATTACHED" in line or "ocoAttached=true" in line

print("[tiny-live-post-trade] read-only server-local MCP smoke")
print(f"url={url} symbol={symbol} strategyId={strategy_id} side={side} hours={hours}")

trigger = call_tool("getTinyLiveAutoExecutionTriggerStatus", {"symbol": symbol, "strategyId": strategy_id, "side": side})
executions = call_tool("listTinyLiveExecutions", {"symbol": symbol, "minutes": minutes, "limit": 20})
attribution = call_tool("getAutonomousExecutionAttribution", {"symbol": symbol, "days": max(1, min(90, (hours + 23) // 24))})
oco = call_tool("getOcoHealth")
events = call_tool("listExecutionEvents", {"symbol": symbol, "limit": 50})
tg = call_tool("getTgNotificationHistory", {"hours": hours, "source": "TinyLive", "limit": 50})
dashboard = call_tool("getAutonomousReadinessDashboard", {"symbol": symbol, "minutes": minutes})

for name, text in [
    ("getTinyLiveAutoExecutionTriggerStatus", trigger),
    ("listTinyLiveExecutions", executions),
    ("getAutonomousExecutionAttribution", attribution),
    ("getOcoHealth", oco),
    ("listExecutionEvents", events),
    ("getAutonomousReadinessDashboard", dashboard),
]:
    require(f"{name} read-only boundary", r"READ_ONLY|read-only|no order/OCO/strategy/grid/fund/Earn|OCO Health Check", text)

require("trigger hard scope", r"hardScope=BTCUSDT/574/LONG/5USDT", trigger)
require("trigger no-order marker", r"orderSent=false", trigger)
require("execution events read-only marker", r"boundary: READ_ONLY report", events)

latest = latest_execution_line(executions)
audit_id = parse_audit_id(latest)
current_execution = has_current_execution(latest)
latest_created_at = parse_created_at(latest)
oco_attached = has_oco_attached(latest)
executed_autonomous = field(r"executedAutonomousTrades=(\d+)", attribution)
oco_effectiveness = field(r"OCOProtectionEffectiveness=([^\n]+)", attribution)
order_sent_evidence = field(r"orderSentEvidence=(\d+)", dashboard)
trigger_enabled = field(r"triggerEnabled=([^\n]+)", trigger)
trigger_dry_run = field(r"triggerDryRun=([^\n]+)", trigger)
execution_eligible = field(r"executionEligible=([^\n]+)", trigger)
would_execute = field(r"wouldExecute=([^\n]+)", trigger)
terminal_blockers = field(r"terminalBlockers=([^\n]+)", trigger)
active_execution_events = "No active execution events." not in events
tg_has_tiny_live = "TinyLive" in tg or "tiny-live" in tg.lower() or "TINY_LIVE" in tg

print("")
print("Trigger:")
print(f"  triggerEnabled={trigger_enabled}")
print(f"  triggerDryRun={trigger_dry_run}")
print(f"  executionEligible={execution_eligible}")
print(f"  wouldExecute={would_execute}")
print(f"  terminalBlockers={compact(terminal_blockers)}")
print("  hardScope=BTCUSDT/574/LONG/5USDT")

print("")
print("Latest TinyLive Execution:")
print(f"  currentExecutionDetected={str(current_execution).lower()}")
print(f"  latestAuditId={audit_id}")
print(f"  latestCreatedAt={latest_created_at.isoformat() if latest_created_at else 'N/A'}")
print(f"  latestWithinWindow={str(in_window(latest)).lower() if latest != 'N/A' else 'false'}")
print(f"  latestExecutionAudit={compact(latest)}")
print(f"  ocoAttached={str(oco_attached).lower()}")
print(f"  executedAutonomousTrades={executed_autonomous}")
print(f"  OCOProtectionEffectiveness={oco_effectiveness}")

print("")
print("Evidence Chain:")
print(f"  orderSentEvidence={order_sent_evidence}")
print(f"  activeExecutionEvents={str(active_execution_events).lower()}")
print(f"  tgTinyLiveEvidence={str(tg_has_tiny_live).lower()}")
print(f"  ocoHealth={compact(oco, 420)}")
print(f"  executionEvents={compact(events, 420)}")
print(f"  tgHistory={compact(tg, 420)}")

blockers = []
if current_execution:
    if not oco_attached:
        blockers.append("LATEST_TINY_LIVE_OCO_NOT_ATTACHED")
    if oco_effectiveness != "PASS_ALL_EXECUTIONS_PROTECTED":
        blockers.append("OCO_PROTECTION_EFFECTIVENESS_NOT_PASS")
    if order_sent_evidence == "N/A":
        blockers.append("ORDER_SENT_EVIDENCE_MISSING")
    if not tg_has_tiny_live:
        blockers.append("TG_TINY_LIVE_EVIDENCE_MISSING")
    if active_execution_events and "OCO_MISSING" in events:
        blockers.append("ACTIVE_OCO_MISSING_EXECUTION_EVENT")
else:
    print("")
    print("post_trade_status=PENDING_NO_NEW_TINY_LIVE_EXECUTION")
    print("next_action=Continue live-authorized monitoring until NO_CURRENT_BUY_CANDIDATE clears and a new TinyLive execution appears inside the requested Hours window.")
    if require_execution:
        raise SystemExit(2)

print("")
print("blockers=" + json.dumps(blockers))
if blockers:
    print("post_trade_status=POST_TRADE_REVIEW_FAILED")
    raise SystemExit(3)
if current_execution:
    print("post_trade_status=POST_TRADE_EVIDENCE_OK")
print("[tiny-live-post-trade] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    if ($RequireExecution.IsPresent -and $LASTEXITCODE -eq 2) {
        exit 2
    }
    throw "tiny-live post-trade smoke failed with exit code $LASTEXITCODE"
}
