param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$WindowSinceUtc = "2026-06-14T15:30:00Z",
    [string]$WindowUntilUtc = "2026-06-14T15:45:00Z",
    [int]$ReviewDays = 7,
    [long]$TinyLiveStrategyId = 574,
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

if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}

if ($TinyLiveStrategyId -lt 1 -or $TinyLiveStrategyId -gt 999999999) {
    throw "TinyLiveStrategyId must be between 1 and 999999999."
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

function Assert-IsoUtcSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$") {
        throw "$Name must be ISO-8601 UTC like 2026-06-14T15:30:00Z."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "Side" -Value $Side -MaxLength 16
Assert-IsoUtcSafe -Name "WindowSinceUtc" -Value $WindowSinceUtc
Assert-IsoUtcSafe -Name "WindowUntilUtc" -Value $WindowUntilUtc

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='__SYMBOL__' WINDOW_SINCE='__WINDOW_SINCE__' WINDOW_UNTIL='__WINDOW_UNTIL__' REVIEW_DAYS='__REVIEW_DAYS__' STRATEGY_ID='__STRATEGY_ID__' SIDE='__SIDE__'
python3 - <<'PY'
import json
import os
import re
import sys
import urllib.request
from collections import Counter

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()
window_since = os.environ["WINDOW_SINCE"]
window_until = os.environ["WINDOW_UNTIL"]
review_days = int(os.environ["REVIEW_DAYS"])
strategy_id = int(os.environ["STRATEGY_ID"])
side = os.environ["SIDE"].upper()

def call_tool(name, arguments=None, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": f"data-freshness-executability-{name}",
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

def parse_json(text):
    try:
        return json.loads(text)
    except Exception:
        return {}

def regex(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default

def int_field(pattern, text, default=0):
    try:
        return int(regex(pattern, text, default))
    except Exception:
        return default

def parse_blocker_metrics(blocked, blocker):
    pattern = rf"{re.escape(blocker)}:.*?falseKill\s+([0-9.]+)%.*?avgRet\s+([+-]?[0-9.]+)%"
    match = re.search(pattern, blocked)
    if not match:
        return {"falseKillPct": "N/A", "avgRetPct": "N/A"}
    return {"falseKillPct": match.group(1), "avgRetPct": match.group(2)}

def top(counter, max_items=8):
    if not counter:
        return "none"
    return ",".join(f"{key}:{value}" for key, value in counter.most_common(max_items))

print("[data-freshness-executability-review] read-only production MCP check")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} reviewDays={review_days} window={window_since}..{window_until} strategyId={strategy_id} side={side} activePort={os.environ['PORT']}")

freshness = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": review_days, "symbol": symbol, "limit": 120})
blocked = call_tool("analyzeBlockedSignalOutcomes", {"days": max(review_days, 14), "symbol": symbol}, timeout=220)
decision_window = call_tool("listDecisionWindow", {
    "sinceUtc": window_since,
    "untilUtc": window_until,
    "symbol": symbol,
    "eventTags": "FILTER_BLOCK",
    "limit": 500,
})
runtime_dashboard = call_tool("getAutonomousReadinessDashboard", {"symbol": symbol, "minutes": review_days * 1440})
runtime_evidence = call_tool("listRuntimeDecisionEvidence", {"symbol": symbol, "minutes": review_days * 1440, "limit": 80})
auto_execution = call_tool("previewTinyLiveAutoExecution", {"symbol": symbol, "strategyId": strategy_id, "side": side})
opportunity = call_tool("validateAutonomousOpportunityReadiness", {"symbol": symbol, "strategyId": strategy_id, "side": side})

require("DataFreshness RCA read-only boundary", r"boundary:\s*READ_ONLY", freshness)
require("DataFreshness RCA acceptance marker", r"acceptance:\s*PASS_NO_CURRENT_SAMPLE|acceptance:\s*PASS_RCA_CLASSIFIED", freshness)
require("blocked-signal read-only boundary", r"mode=READ_ONLY", blocked)
require("decision window summary", r"listDecisionWindow collected", decision_window)
require("autonomous dashboard read-only boundary", r"boundary:\s*READ_ONLY", runtime_dashboard)
require("runtime evidence read-only boundary", r"boundary:\s*READ_ONLY", runtime_evidence)
require("auto execution read-only boundary", r"boundary:\s*READ_ONLY", auto_execution)
require("auto execution no-order marker", r"orderSent=false", auto_execution)
require("opportunity read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', opportunity)
require("opportunity no-order marker", r'"orderSent"\s*:\s*false', opportunity)

window_json = parse_json(decision_window)
items = window_json.get("items") if isinstance(window_json.get("items"), list) else []
metrics = window_json.get("metrics") if isinstance(window_json.get("metrics"), dict) else {}
decision_count = int(metrics.get("count", len(items)) or 0)
blocker_counts = Counter()
strategy_counts = Counter()
interval_counts = Counter()
live_signal_ids = []
other_blockers = Counter()
for item in items:
    if not isinstance(item, dict):
        continue
    blocker = str(item.get("blocker", "UNKNOWN") or "UNKNOWN")
    strategy = str(item.get("strategyId", "UNKNOWN"))
    interval = str(item.get("intervalCode", "UNKNOWN") or "UNKNOWN")
    live_signal = item.get("liveSignalId", -1)
    blocker_counts[blocker] += 1
    strategy_counts[strategy] += 1
    interval_counts[interval] += 1
    if live_signal is not None and str(live_signal) not in ("", "-1"):
        live_signal_ids.append(str(live_signal))
    if blocker != "DataFreshnessGuard":
        other_blockers[blocker] += 1

ready_now = int_field(r"readyNowKeys=(\d+)", freshness)
stale_now = int_field(r"staleNowKeys=(\d+)", freshness)
no_data_now = int_field(r"noDataNowKeys=(\d+)", freshness)
query_failed_now = int_field(r"queryFailedNowKeys=(\d+)", freshness)
true_stale = int_field(r"trueStaleKline=(\d+)", freshness)
context_mismatch = int_field(r"contextMismatchReview=(\d+)", freshness)
legacy_missing = int_field(r"legacyOrMissingContext=(\d+)", freshness)
metrics_df = parse_blocker_metrics(blocked, "DataFreshnessGuard")

runtime_rows = regex(r"runtimeEvidenceRows=(\d+)", runtime_dashboard)
ev_samples = regex(r"evSamples=(\d+)", runtime_dashboard)
tqs_samples = regex(r"tqsSamples=(\d+)", runtime_dashboard)
shadow_intents = regex(r"shadowIntentCount=(\d+)", runtime_dashboard)
oco_plans = regex(r"ocoPlansCreated=(\d+)", runtime_dashboard)
order_sent_evidence = regex(r"orderSentEvidence=(\d+)", runtime_dashboard)
freshness_terminal_blocks = regex(r"freshnessTerminalBlocks=(\d+)", runtime_dashboard)
readiness_verdict = regex(r"finalReadinessVerdict=([A-Z_]+)", runtime_dashboard)
preview_eligible = regex(r"executionEligible=([^\r\n]+)", auto_execution)
preview_would_execute = regex(r"wouldExecute=([^\r\n]+)", auto_execution)
preview_status = regex(r"previewStatus=([^\r\n]+)", auto_execution)
preview_blockers = regex(r"blockers=\[([^\]]*)\]", auto_execution)
preview_runtime_status = regex(r"runtimeEvidenceStatus=([^\r\n]+)", auto_execution)
opportunity_json = parse_json(opportunity)
opportunity_classification = str(opportunity_json.get("readinessClassification", "N/A"))
opportunity_eligible = str(opportunity_json.get("eligible", "N/A")).lower()
opportunity_blockers = opportunity_json.get("blockers") if isinstance(opportunity_json.get("blockers"), list) else []
opportunity_evidence = opportunity_json.get("evidence") if isinstance(opportunity_json.get("evidence"), dict) else {}

window_only_datafreshness = decision_count > 0 and blocker_counts.get("DataFreshnessGuard", 0) == decision_count and not other_blockers
window_has_live_signal = len(live_signal_ids) > 0
current_clean = ready_now > 0 and stale_now == 0 and no_data_now == 0 and query_failed_now == 0
historical_context_clean = true_stale > 0 and context_mismatch == 0 and legacy_missing == 0
has_ev_samples = ev_samples not in ("", "N/A", "0")
has_oco_plans = oco_plans not in ("", "N/A", "0")
has_shadow_intents = shadow_intents not in ("", "N/A", "0")
current_executable = preview_eligible.lower() == "true" and preview_would_execute.lower() == "true" and opportunity_eligible == "true"

missing_executability_evidence = []
if not window_has_live_signal:
    missing_executability_evidence.append("historical liveSignalId/entry/TP/SL evidence")
if not has_ev_samples:
    missing_executability_evidence.append("EV pass samples")
if not has_oco_plans:
    missing_executability_evidence.append("OCO plan evidence")
if not has_shadow_intents:
    missing_executability_evidence.append("shadow execution intents")
if not current_executable:
    missing_executability_evidence.append("current executable preview")

if not current_clean:
    recommendation = "FIX_CURRENT_DATA_FRESHNESS_FIRST"
elif window_only_datafreshness and missing_executability_evidence:
    recommendation = "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY"
elif current_executable and not missing_executability_evidence:
    recommendation = "REVIEW_BOUNDED_SHADOW_OR_TINY_LIVE_EXPERIMENT"
else:
    recommendation = "REVIEW_COUNTERFACTUAL_EVIDENCE"

counterfactual_requirements = [
    "historical DataFreshness rows have canonical decisionId plus liveSignalId or equivalent candidate snapshot",
    "candidate snapshot contains entryPrice, tpPrice, slPrice, expectedR, EV pass/fail, OCO-capable preflight, duplicateBar, daily cap, exposure, and event-risk fields",
    "replay compares DataFreshness-only removal while keeping ExpectedValueGate, OCO preflight, daily cap, duplicate, exposure, event-risk, and hard safety gates intact",
    "shadow/tiny-live experiment is separately authorized and capped only after replay proves executable positive expectancy",
]

print("")
print("Decision Window Evidence:")
print(f"  decisionWindowCount={decision_count}")
print(f"  windowOnlyDataFreshness={str(window_only_datafreshness).lower()}")
print(f"  windowHasLiveSignalIds={str(window_has_live_signal).lower()}")
print(f"  blockerCounts={top(blocker_counts)}")
print(f"  strategyCounts={top(strategy_counts)}")
print(f"  intervalCounts={top(interval_counts)}")
print(f"  otherBlockers={top(other_blockers)}")
print("")
print("DataFreshness / Alpha Evidence:")
print(f"  currentDataFreshnessClean={str(current_clean).lower()}")
print(f"  historicalContextClean={str(historical_context_clean).lower()}")
print(f"  readyNowKeys={ready_now} staleNowKeys={stale_now} noDataNowKeys={no_data_now} queryFailedNowKeys={query_failed_now}")
print(f"  trueStaleKline={true_stale} contextMismatchReview={context_mismatch} legacyOrMissingContext={legacy_missing}")
print(f"  dataFreshnessFalseKillPct={metrics_df['falseKillPct']}")
print(f"  dataFreshnessAvgRetPct={metrics_df['avgRetPct']}")
print("")
print("Executability Gates:")
print(f"  runtimeEvidenceRows={runtime_rows}")
print(f"  evSamples={ev_samples}")
print(f"  tqsSamples={tqs_samples}")
print(f"  shadowIntentCount={shadow_intents}")
print(f"  ocoPlansCreated={oco_plans}")
print(f"  orderSentEvidence={order_sent_evidence}")
print(f"  freshnessTerminalBlocks={freshness_terminal_blocks}")
print(f"  finalReadinessVerdict={readiness_verdict}")
print(f"  currentExecutionEligible={preview_eligible}")
print(f"  currentWouldExecute={preview_would_execute}")
print(f"  currentPreviewStatus={preview_status}")
print(f"  currentPreviewBlockers=[{preview_blockers}]")
print(f"  currentRuntimeEvidenceStatus={preview_runtime_status}")
print(f"  currentOpportunityClassification={opportunity_classification}")
print(f"  currentOpportunityEligible={opportunity_eligible}")
print(f"  currentOpportunityBlockers=" + json.dumps(opportunity_blockers, separators=(",", ":")))
print("  currentOpportunityEvidence=" + json.dumps({
    "evStatus": opportunity_evidence.get("evStatus"),
    "ocoPreflightStatus": opportunity_evidence.get("ocoPreflightStatus"),
    "duplicateBarStatus": opportunity_evidence.get("duplicateBarStatus"),
    "dailyCapStatus": opportunity_evidence.get("dailyCapStatus"),
    "runtimeEvidenceStatus": opportunity_evidence.get("runtimeEvidenceStatus"),
    "currentSignalDecision": opportunity_evidence.get("currentSignalDecision"),
    "signalProximityState": opportunity_evidence.get("signalProximityState"),
    "orderSent": opportunity_evidence.get("orderSent"),
}, separators=(",", ":")))
print("")
print("Counterfactual Proof Gap:")
print("  missing_executability_evidence=" + json.dumps(missing_executability_evidence, separators=(",", ":")))
print("  counterfactual_required_evidence=" + json.dumps(counterfactual_requirements, separators=(",", ":")))
print("")
print("Evidence Excerpts:")
print("  decisionWindow=" + compact(decision_window, 900))
print("  runtimeDashboard=" + compact(runtime_dashboard, 700))
print("  autoExecutionPreview=" + compact(auto_execution, 700))
print("  opportunityReadiness=" + compact(opportunity, 700))
print("")
print("Conclusion:")
print(f"  currentExecutable={str(current_executable).lower()}")
print(f"  data_freshness_executability_recommendation={recommendation}")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[data-freshness-executability-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__WINDOW_SINCE__", $WindowSinceUtc).
    Replace("__WINDOW_UNTIL__", $WindowUntilUtc).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__STRATEGY_ID__", [string]$TinyLiveStrategyId).
    Replace("__SIDE__", $Side)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "DataFreshness executability review smoke failed with exit code $LASTEXITCODE"
}
