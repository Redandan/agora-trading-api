param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [int]$Days = 30,
    [switch]$RequireClear
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

if ($Days -lt 1 -or $Days -gt 90) {
    throw "Days must be between 1 and 90."
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

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' SIDE='$Side' DAYS='$Days' REQUIRE_CLEAR='$($RequireClear.IsPresent)'
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
strategy_id = int(os.environ["STRATEGY_ID"])
side = os.environ["SIDE"].upper()
days = int(os.environ["DAYS"])
minutes = days * 24 * 60
require_clear = os.environ.get("REQUIRE_CLEAR", "").lower() == "true"

def request(body, timeout=160):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    data = json.loads(raw)
    if data.get("error"):
        raise RuntimeError(data["error"])
    return data

def call_tool(name, arguments=None, timeout=160):
    data = request({
        "jsonrpc": "2.0",
        "id": f"tiny-live-loss-rca-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
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

def parse_json_object(text):
    try:
        obj = json.loads(text)
    except Exception:
        return {}
    return obj if isinstance(obj, dict) else {}

def field(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def first_json_or_regex(obj, key, pattern, text, default="N/A"):
    value = obj.get(key, default)
    if value is None or value == default:
        return field(pattern, text, default)
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)

def compact(text, limit=260):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

print("[tiny-live-loss-rca] read-only server-local MCP smoke")
print(f"url={url} symbol={symbol} strategyId={strategy_id} side={side} days={days}")

readiness = call_tool("listTinyLiveExecutionReadiness", {"symbol": symbol, "strategyId": strategy_id, "side": side})
trigger = call_tool("getTinyLiveAutoExecutionTriggerStatus", {"symbol": symbol, "strategyId": strategy_id, "side": side})
auto_approval = call_tool("previewTinyLiveAutoApproval", {"symbol": symbol, "strategyId": strategy_id, "side": side})
auto_execution = call_tool("previewTinyLiveAutoExecution", {"symbol": symbol, "strategyId": strategy_id, "side": side})
executions = call_tool("listTinyLiveExecutions", {"symbol": symbol, "minutes": minutes, "limit": 50})
attribution = call_tool("getAutonomousExecutionAttribution", {"symbol": symbol, "days": days})
monitor = call_tool("getAutonomousExplorationMonitorStatus", {"symbol": symbol, "strategyId": strategy_id, "side": side})
rollout = call_tool("getExplorationRolloutStatus", {"symbol": symbol, "strategyId": strategy_id, "side": side})
missed = call_tool("getMissedOpportunityRegressionReport", {"symbol": symbol, "hours": days * 24})
truth = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": days * 24, "limit": 20})

for name, text in [
    ("listTinyLiveExecutionReadiness", readiness),
    ("getTinyLiveAutoExecutionTriggerStatus", trigger),
    ("previewTinyLiveAutoApproval", auto_approval),
    ("previewTinyLiveAutoExecution", auto_execution),
    ("listTinyLiveExecutions", executions),
    ("getAutonomousExecutionAttribution", attribution),
    ("getAutonomousExplorationMonitorStatus", monitor),
    ("getExplorationRolloutStatus", rollout),
]:
    require(f"{name} read-only boundary", r"READ_ONLY|no order/OCO/strategy/grid/fund/Earn", text)

require("readiness no-order marker", r"orderSent=false", readiness)
require("trigger no-order marker", r"orderSent=false", trigger)
require("auto-execution no-order marker", r"orderSent=false", auto_execution)
require("missed opportunity read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', missed)
require("missed opportunity no-order marker", r'"orderSent"\s*:\s*false', missed)
require("truth table read-only marker", r"READ_ONLY|orderSent", truth)

missed_json = parse_json_object(missed)
hard_stop_detected = "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES" in "\n".join([
    readiness, trigger, auto_approval, auto_execution, executions, attribution, monitor, rollout, missed, truth
])
tiny_hard_stop_fields = {
    "autoApprovalEligible": field(r'autoApprovalEligible=([^\n]+)', readiness),
    "triggerEnabled": field(r'triggerEnabled=([^\n]+)', trigger),
    "triggerDryRun": field(r'triggerDryRun=([^\n]+)', trigger),
    "executionEligible": field(r'executionEligible=([^\n]+)', trigger),
    "wouldExecute": field(r'wouldExecute=([^\n]+)', trigger),
}
tiny_rollout_fields = {
    "completedTinyLiveSamples": field(r'completedTinyLiveSamples=([^\n]+)', rollout),
    "falsePositiveCount": field(r'falsePositiveCount=([^\n]+)', rollout),
    "dailyLossBudgetBreached": field(r'dailyLossBudgetBreached=([^\n]+)', rollout),
    "canEnableProduction": field(r'canEnableProduction=([^\n]+)', rollout),
    "canIncreaseDailyCap": field(r'canIncreaseDailyCap=([^\n]+)', rollout),
}
missing_tiny_live_hard_stop_fields = [
    name for name, value in tiny_hard_stop_fields.items()
    if value is None or str(value).strip() in ("", "N/A")
]
missing_tiny_live_rollout_fields = [
    name for name, value in tiny_rollout_fields.items()
    if value is None or str(value).strip() in ("", "N/A")
]
missing_tiny_live_fields = missing_tiny_live_hard_stop_fields + missing_tiny_live_rollout_fields

print("")
print("Hard Stop:")
print(f"  hardStopDetected={str(hard_stop_detected).lower()}")
print(f"  autoApprovalEligible={tiny_hard_stop_fields['autoApprovalEligible']}")
print(f"  autoApprovalMode={field(r'autoApprovalMode=([^\n]+)', readiness)}")
print(f"  autoApprovalBlockers={compact(field(r'autoApprovalBlockers=([^\n]+)', readiness))}")
print(f"  missedAlphaBudgetRemaining={field(r'missedAlphaBudgetRemaining=([^\n]+)', auto_approval)}")
print(f"  maxLossIfWrongUsdt={field(r'maxLossIfWrongUsdt=([^\n]+)', auto_approval)}")
print(f"  allowedMistakeBudgetUsed={field(r'allowedMistakeBudgetUsed=([^\n]+)', auto_approval)}")
print(f"  triggerEnabled={tiny_hard_stop_fields['triggerEnabled']} triggerDryRun={tiny_hard_stop_fields['triggerDryRun']}")
print(f"  executionEligible={tiny_hard_stop_fields['executionEligible']} wouldExecute={tiny_hard_stop_fields['wouldExecute']}")
print(f"  terminalBlockers={compact(field(r'terminalBlockers=([^\n]+)', trigger))}")
print(f"  missing_tiny_live_hard_stop_fields={json.dumps(missing_tiny_live_hard_stop_fields)}")
print("  hardStopClearCriteria=maxConsecutiveTinyLiveLosses<2, current BUY candidate present, runtime evidence available, execution flags separately authorized")

print("")
print("Recent Tiny-Live Audit:")
print(f"  executedAutonomousTrades={field(r'executedAutonomousTrades=(\d+)', attribution)}")
print(f"  blockedAutonomousTrades={field(r'blockedAutonomousTrades=(\d+)', attribution)}")
print(f"  successfulOcoAttachRate={field(r'successfulOcoAttachRate=([^\n]+)', attribution)}")
print(f"  OCOProtectionEffectiveness={field(r'OCOProtectionEffectiveness=([^\n]+)', attribution)}")
latest_line = next((line for line in executions.splitlines() if re.match(r'\d+\. #', line)), "N/A")
print(f"  latestExecutionAudit={compact(latest_line)}")

print("")
print("Rollout Gates:")
print(f"  loopState={field(r'loopState=([^\n]+)', rollout)}")
print(f"  consecutiveReadyTicks={field(r'consecutiveReadyTicks=([^\n]+)', rollout)}")
print(f"  completedTinyLiveSamples={tiny_rollout_fields['completedTinyLiveSamples']}")
print(f"  falsePositiveCount={tiny_rollout_fields['falsePositiveCount']}")
print(f"  dailyLossBudgetBreached={tiny_rollout_fields['dailyLossBudgetBreached']}")
print(f"  canEnableProduction={tiny_rollout_fields['canEnableProduction']}")
print(f"  canIncreaseDailyCap={tiny_rollout_fields['canIncreaseDailyCap']}")
print(f"  rolloutBlockers={compact(field(r'blockers=([^\n]+)', rollout), 360)}")
print(f"  missing_tiny_live_rollout_fields={json.dumps(missing_tiny_live_rollout_fields)}")
print(f"  missing_tiny_live_fields={json.dumps(missing_tiny_live_fields)}")

print("")
print("Opportunity/No-Buy Context:")
print(f"  missedOverallStatus={first_json_or_regex(missed_json, 'overallStatus', r'overallStatus=([^\n]+)', missed)}")
print(f"  suspiciousNoBuyCount={first_json_or_regex(missed_json, 'suspiciousNoBuyCount', r'suspiciousNoBuyCount=(\d+)', missed)}")
print(f"  falseBlockRiskCount={first_json_or_regex(missed_json, 'falseBlockRiskCount', r'falseBlockRiskCount=(\d+)', missed)}")
print(f"  recommendedFix={compact(first_json_or_regex(missed_json, 'recommendedFix', r'recommendedFix=([^\n]+)', missed))}")
print(f"  noBuyTruthTable={compact(truth, 500)}")

print("")
print("Monitor/Rollout:")
print(f"  monitor={compact(monitor, 500)}")
print(f"  rollout={compact(rollout, 500)}")

print("")
print("Recommendations:")
if hard_stop_detected:
    print("  - KEEP_DISABLED: do not enable live; consecutive tiny-live loss protection is still present.")
else:
    print("  - REVIEW: consecutive tiny-live loss hard stop was not detected in current read-only outputs; continue full live-readiness audit before any env plan.")
print("  - REQUIRE: fresh dry-run/runtime evidence and a current BUY candidate before considering any separately authorized live env change.")
print("  - SCOPE: this smoke is read-only and must not be used to place orders or enable scheduler/live flags.")
print("[tiny-live-loss-rca] OK read-only check complete")
rollout_clear = str(tiny_rollout_fields["canEnableProduction"]).lower() == "true"
if require_clear and (hard_stop_detected or missing_tiny_live_fields or not rollout_clear):
    raise SystemExit(2)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    if ($RequireClear.IsPresent -and $LASTEXITCODE -eq 2) {
        exit 2
    }
    throw "tiny-live loss RCA smoke failed with exit code $LASTEXITCODE"
}
