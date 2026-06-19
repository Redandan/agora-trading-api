param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14
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

if ($ExecutionDays -lt 1 -or $ExecutionDays -gt 90 `
        -or $BlockedDays -lt 1 -or $BlockedDays -gt 90 `
        -or $AccuracyDays -lt 1 -or $AccuracyDays -gt 90) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90."
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

export PORT MCP_KEY SYMBOL='$Symbol' EXECUTION_DAYS='$ExecutionDays' BLOCKED_DAYS='$BlockedDays' ACCURACY_DAYS='$AccuracyDays'
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
execution_days = int(os.environ["EXECUTION_DAYS"])
blocked_days = int(os.environ["BLOCKED_DAYS"])
accuracy_days = int(os.environ["ACCURACY_DAYS"])

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
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8", "replace")
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

def find(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default

def contains_any(text, patterns):
    return any(re.search(pattern, text, re.MULTILINE) for pattern in patterns)

def require(description, pattern, text):
    if not re.search(pattern, text, re.MULTILINE):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

def parse_json_object(text):
    try:
        data = json.loads(text)
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}

def json_field(data, name, default="N/A"):
    value = data.get(name, default)
    return default if value is None else str(value)

def list_value(data, name):
    value = data.get(name, [])
    return value if isinstance(value, list) else []

def first_non_empty(*values):
    for value in values:
        if value is not None and str(value).strip() and str(value).strip() != "N/A":
            return str(value).strip()
    return "N/A"

def short(value, limit=180):
    text = str(value or "N/A").replace("\n", " ").strip()
    return text if len(text) <= limit else text[: limit - 3] + "..."

def top_items(counter, limit=5):
    return counter.most_common(limit)

def blocker_family(blocker):
    value = str(blocker or "UNKNOWN").upper()
    if "RUNTIME_EVIDENCE" in value:
        return "RUNTIME_EVIDENCE"
    if "DATA_FRESHNESS" in value or "STALE" in value:
        return "DATA_FRESHNESS"
    if "OCO" in value:
        return "OCO"
    if "EV_" in value or "EXPECTED_VALUE" in value:
        return "EXPECTED_VALUE"
    if "TQS" in value:
        return "TQS"
    if "BUDGET" in value or "NOTIONAL" in value:
        return "BUDGET_NOTIONAL"
    if "DAILY" in value or "CAP" in value:
        return "CAPACITY"
    if ("NO_CURRENT_BUY" in value
            or "SIGNAL" in value
            or "HOLD" in value
            or "FORMING_STATE" in value
            or "PRE_POSITION" in value
            or "POST_SCOUT" in value
            or "SCORE_BUY_NOT_CONFIRMED" in value
            or "CONFIRMED_DEPLOY" in value):
        return "SIGNAL_NOT_READY"
    if "ENTRYDEDUP" in value or "DUPLICATE" in value:
        return "DEDUP"
    return value[:48]

print("[signal-correctness] read-only production MCP check")
print(f"symbol={symbol} executionDays={execution_days} blockedDays={blocked_days} accuracyDays={accuracy_days}")

execution = call_tool("verifyStrategyExecution", {"days": execution_days}, timeout=120)
blocked = call_tool("analyzeBlockedSignalOutcomes", {"days": blocked_days, "symbol": symbol}, timeout=120)
accuracy = call_tool("getSignalAccuracyReport", {"days": accuracy_days}, timeout=120)
freshness = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": blocked_days, "symbol": symbol, "limit": 50}, timeout=120)
dashboard = call_tool("getSignalCorrectnessDashboard", {"symbol": symbol, "hours": 24}, timeout=120)
governance = call_tool("getGovernanceDriftDashboard", {"symbol": symbol, "days": blocked_days, "labelHorizon": "24h"}, timeout=120)
relaxation = call_tool("findGovernanceRelaxationCandidates", {"symbol": symbol, "days": blocked_days, "labelHorizon": "24h"}, timeout=120)
tightening = call_tool("findGovernanceTighteningCandidates", {"symbol": symbol, "days": blocked_days, "labelHorizon": "24h"}, timeout=120)
entry_dedup = call_tool("getEntryDedupGovernanceDashboard", {"symbol": symbol, "hours": blocked_days * 24}, timeout=120)
missed_opportunities = call_tool("getMissedOpportunityRegressionReport", {"symbol": symbol, "hours": blocked_days * 24}, timeout=120)
truth_table = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": blocked_days * 24, "limit": 20}, timeout=120)

require("verifyStrategyExecution read-only marker", r"READ_ONLY|no external import/backfill", execution)
require("verifyStrategyExecution machine status marker", r"MACHINE_STATUS\s+(no missing evaluation;\s*no missed order|missing evaluation or missed order suspected)", execution)
require("blocked signal outcomes read-only marker", r"mode=READ_ONLY", blocked)
require("signal accuracy read-only marker", r"mode=READ_ONLY", accuracy)
require("DataFreshnessGuard read-only boundary", r"boundary:\s*READ_ONLY", freshness)
require("signal correctness dashboard read-only boundary", r"boundary:\s*READ_ONLY", dashboard)
require("governance drift read-only boundary", r"boundary:\s*READ_ONLY", governance)
require("governance relaxation read-only boundary", r"boundary:\s*READ_ONLY", relaxation)
require("governance relaxation criteria", r"criteria:", relaxation)
require("governance tightening read-only boundary", r"boundary:\s*READ_ONLY", tightening)
require("governance tightening criteria", r"criteria:", tightening)
require("EntryDedup governance read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', entry_dedup)
require("EntryDedup governance no order send marker", r'"orderSent"\s*:\s*false', entry_dedup)
require("EntryDedup governance no OCO modification marker", r'"ocoModified"\s*:\s*false', entry_dedup)
require("EntryDedup governance no runtime evidence writes marker", r'"writesRuntimeEvidence"\s*:\s*false', entry_dedup)
require("missed opportunity regression read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', missed_opportunities)
require("missed opportunity regression no order send marker", r'"orderSent"\s*:\s*false', missed_opportunities)
require("missed opportunity regression no OCO modification marker", r'"ocoModified"\s*:\s*false', missed_opportunities)
require("missed opportunity regression no runtime evidence writes marker", r'"writesRuntimeEvidence"\s*:\s*false', missed_opportunities)
require("no-buy reason truth table read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', truth_table)
require("no-buy reason truth table no order send marker", r'"orderSent"\s*:\s*false', truth_table)

entry_dedup_json = parse_json_object(entry_dedup)
missed_json = parse_json_object(missed_opportunities)
truth_json = parse_json_object(truth_table)

execution_machine_status = find(r"MACHINE_STATUS\s+([^\r\n]+)", execution)
execution_ok = re.search(r"MACHINE_STATUS\s+no missing evaluation;\s*no missed order", execution) is not None
blocked_total = find(r"(?:total|analyzed|sampleCount)[^0-9]*(\d+)", blocked)
blocked_correct = find(r"(?:correct|trueBlock)[^0-9]*(\d+)", blocked)
blocked_wrong = find(r"(?:wrong|falseBlock|falseKill)[^0-9]*(\d+)", blocked)
blocked_correct_rate = find(r"(?:correctRate|trueBlockRate)[^0-9+\-]*([-+0-9.]+%)", blocked)
blocked_wrong_rate = find(r"(?:wrongRate|falseBlockRate|falseKillRate)[^0-9+\-]*([-+0-9.]+%)", blocked)
avg_24h = find(r"(?:avg24h|avgForwardReturn24h|average24hReturn)[^0-9+\-]*([-+0-9.]+%)", blocked)
pass_line = find(r"(EnsembleGate \[PASS\].*)", accuracy)
stale_now = find(r"staleNowKeys=(\d+)", freshness)
no_data_now = find(r"noDataNowKeys=(\d+)", freshness)
query_failed_now = find(r"queryFailedNowKeys=(\d+)", freshness)
actionable = find(r"actionableCandidates=(\d+)", dashboard)
labeled = find(r"labeledCandidates=(\d+)", dashboard)
false_block_rate = find(r"falseBlockRate=([-+0-9.]+%)", dashboard)
governance_mode = find(r"governanceMode=([A-Z_]+)", dashboard)
governance_mode_7d = find(r"governanceMode=([A-Z_]+)", governance)
relaxation_lines = [line for line in relaxation.splitlines() if line.startswith("- blocker=")]
tightening_lines = [line for line in tightening.splitlines() if line.startswith("- source=") or line.startswith("- blocker=")]
entry_dedup_skips = json_field(entry_dedup_json, "entryDedupSkipCount", find(r"entryDedupSkipCount=(\d+)", entry_dedup))
entry_dedup_would_allow = json_field(entry_dedup_json, "wouldAllowStagedAddGroups", find(r"wouldAllowStagedAddGroups=(\d+)", entry_dedup))
entry_dedup_exact = json_field(entry_dedup_json, "exactDuplicateGroups", find(r"exactDuplicateGroups=(\d+)", entry_dedup))
entry_dedup_budget = json_field(entry_dedup_json, "budgetBlockedGroups", find(r"budgetBlockedGroups=(\d+)", entry_dedup))
entry_dedup_hard_safety = json_field(entry_dedup_json, "hardSafetyBlockedGroups", find(r"hardSafetyBlockedGroups=(\d+)", entry_dedup))
missed_status = json_field(missed_json, "overallStatus", find(r"overallStatus=([A-Z_]+)", missed_opportunities))
missed_suspicious = json_field(missed_json, "suspiciousNoBuyCount", find(r"suspiciousNoBuyCount=(\d+)", missed_opportunities))
missed_false_block = json_field(missed_json, "falseBlockRiskCount", find(r"falseBlockRiskCount=(\d+)", missed_opportunities))
missed_dedup_too_coarse = json_field(missed_json, "dedupTooCoarseSuspects", find(r"dedupTooCoarseSuspects=(\d+)", missed_opportunities))
missed_staged_allow = json_field(missed_json, "genericStagedAddWouldAllowGroups", find(r"genericStagedAddWouldAllowGroups=(\d+)", missed_opportunities))
missed_high_return = json_field(missed_json, "highForwardReturnNoBuyCount", find(r"highForwardReturnNoBuyCount=(\d+)", missed_opportunities))
missed_recommended_fix = json_field(missed_json, "recommendedFix", find(r"recommendedFix=(.*)", missed_opportunities))
missed_rows = list_value(missed_json, "rows")
truth_rows = list_value(truth_json, "rows")
high_return_examples = list_value(missed_json, "highForwardReturnNoBuyExamples")
entry_groups = list_value(entry_dedup_json, "groups")

row_class_counts = Counter()
row_blocker_families = Counter()
row_actions = []
for row in missed_rows:
    if not isinstance(row, dict):
        continue
    classification = str(row.get("classification", "UNKNOWN"))
    row_class_counts[classification] += 1
    blockers = row.get("blockers") if isinstance(row.get("blockers"), list) else []
    warnings = row.get("warnings") if isinstance(row.get("warnings"), list) else []
    evidence = row.get("evidence") if isinstance(row.get("evidence"), dict) else {}
    top_blocker = first_non_empty(blockers[0] if blockers else None, evidence.get("primaryNoBuyReason"))
    row_blocker_families[blocker_family(top_blocker)] += 1
    action = first_non_empty(
        evidence.get("nextRequiredAction"),
        evidence.get("blockingInterpretation"),
        evidence.get("primaryNoBuyReason"),
        row.get("reason"),
    )
    row_actions.append((row.get("path", "UNKNOWN"), classification, top_blocker, action, warnings[:2]))

high_return_strategy_counts = Counter()
high_return_blocker_counts = Counter()
truth_class_counts = Counter()
truth_blocker_families = Counter()
for example in high_return_examples:
    if not isinstance(example, dict):
        continue
    high_return_strategy_counts[str(example.get("strategyId", "UNKNOWN"))] += 1
    reason = first_non_empty(example.get("terminalBlocker"), example.get("blockerReason"), example.get("selectedAction"))
    high_return_blocker_counts[blocker_family(reason)] += 1

for row in truth_rows:
    if not isinstance(row, dict):
        continue
    truth_class_counts[str(row.get("classification", "UNKNOWN"))] += 1
    blockers = row.get("blockers") if isinstance(row.get("blockers"), list) else []
    evidence = row.get("evidence") if isinstance(row.get("evidence"), dict) else {}
    top_blocker = first_non_empty(blockers[0] if blockers else None, evidence.get("primaryNoBuyReason"))
    truth_blocker_families[blocker_family(top_blocker)] += 1

entry_group_blockers = Counter()
for group in entry_groups:
    if not isinstance(group, dict):
        continue
    for blocker in group.get("blockers", []):
        entry_group_blockers[blocker_family(blocker)] += 1

required_signal_fields = {
    "governanceMode7d": governance_mode_7d,
    "missedOpportunityOverallStatus": missed_status,
    "staleNowKeys": stale_now,
    "noDataNowKeys": no_data_now,
    "queryFailedNowKeys": query_failed_now,
    "entryDedupWouldAllowStagedAddGroups": entry_dedup_would_allow,
    "missedDedupTooCoarseSuspects": missed_dedup_too_coarse,
}
missing_signal_policy_fields = [
    name for name, value in required_signal_fields.items()
    if value is None or str(value).strip() in ("", "N/A")
]

print("")
print("Execution:")
print(f"  executionMachineStatus={execution_machine_status}")
print(f"  missingEvalOrOrderBug={'no' if execution_ok else 'unknown_or_present'}")
print("")
print("Blocked Signal Outcomes:")
print(f"  total={blocked_total} correct={blocked_correct} wrong={blocked_wrong} correctRate={blocked_correct_rate} wrongRate={blocked_wrong_rate} avg24hIfAllowed={avg_24h}")
print("")
print("Signal Accuracy:")
print(f"  passSummary={pass_line}")
print("")
print("DataFreshnessGuard Current Snapshot:")
print(f"  staleNowKeys={stale_now} noDataNowKeys={no_data_now} queryFailedNowKeys={query_failed_now}")
print("")
print("24h Correctness Dashboard:")
print(f"  governanceMode={governance_mode} actionableCandidates={actionable} labeledCandidates={labeled} falseBlockRate={false_block_rate}")
print("")
print("7d Governance Drift:")
print(f"  governanceMode={governance_mode_7d}")
if relaxation_lines:
    print("  relaxationCandidates:")
    for line in relaxation_lines[:5]:
        print(f"    {line}")
else:
    print("  relaxationCandidates=none")
if tightening_lines:
    print("  tighteningCandidates:")
    for line in tightening_lines[:5]:
        print(f"    {line}")
else:
    print("  tighteningCandidates=none")
print("")
print("EntryDedup Live-Readiness Cross-Check:")
print(f"  entryDedupSkipCount={entry_dedup_skips} wouldAllowStagedAddGroups={entry_dedup_would_allow} exactDuplicateGroups={entry_dedup_exact} budgetBlockedGroups={entry_dedup_budget} hardSafetyBlockedGroups={entry_dedup_hard_safety}")
print("")
print("Missed Opportunity Regression:")
print(f"  overallStatus={missed_status} suspiciousNoBuyCount={missed_suspicious} falseBlockRiskCount={missed_false_block} dedupTooCoarseSuspects={missed_dedup_too_coarse} genericStagedAddWouldAllowGroups={missed_staged_allow} highForwardReturnNoBuyCount={missed_high_return}")
if missed_recommended_fix != "N/A":
    print(f"  recommendedFix={missed_recommended_fix}")
print(f"  missing_signal_policy_fields={json.dumps(missing_signal_policy_fields)}")
print("")
print("No-Buy Row Classification:")
if row_class_counts:
    print("  classifications=" + ", ".join(f"{name}:{count}" for name, count in top_items(row_class_counts)))
    print("  blockerFamilies=" + ", ".join(f"{name}:{count}" for name, count in top_items(row_blocker_families)))
    print("  rowActions:")
    for path, classification, blocker, action, warnings in row_actions[:6]:
        warn = (" warnings=" + short("|".join(map(str, warnings)), 140)) if warnings else ""
        print(f"    - path={path} classification={classification} topBlocker={short(blocker, 80)} action={short(action)}{warn}")
else:
    print("  rows=none")
print("")
print("High-Return No-Buy Breakdown:")
if high_return_examples:
    print("  strategies=" + ", ".join(f"{name}:{count}" for name, count in top_items(high_return_strategy_counts)))
    print("  blockerFamilies=" + ", ".join(f"{name}:{count}" for name, count in top_items(high_return_blocker_counts)))
else:
    print("  examples=none")
print("")
print("No-Buy Reason Truth Table:")
if truth_rows:
    print("  classifications=" + ", ".join(f"{name}:{count}" for name, count in top_items(truth_class_counts)))
    print("  blockerFamilies=" + ", ".join(f"{name}:{count}" for name, count in top_items(truth_blocker_families)))
else:
    print("  rows=none")
print("")
print("EntryDedup Group Blockers:")
if entry_group_blockers:
    print("  blockerFamilies=" + ", ".join(f"{name}:{count}" for name, count in top_items(entry_group_blockers)))
else:
    print("  blockerFamilies=none")
print("")
print("Recommendations:")
if not execution_ok:
    print("  - FAIL: verifyStrategyExecution did not provide the expected no-missing-evaluation/no-missed-order marker.")
else:
    print("  - KEEP: strategy scheduler/evaluation path appears to be running; no missed-evaluation/order marker found.")

try:
    wrong_num = int(blocked_wrong) if blocked_wrong != "N/A" else 0
    total_num = int(blocked_total) if blocked_total != "N/A" else 0
except ValueError:
    wrong_num = 0
    total_num = 0

if total_num >= 30 and wrong_num * 100.0 / total_num >= 40.0:
    print("  - WATCH/REVIEW: blocker governance is over-conservative by post-outcome evidence; review relaxation candidates before enabling more live automation.")
else:
    print("  - WATCH: blocker evidence is either limited or not strongly over-conservative.")

if stale_now != "0" or no_data_now != "0" or query_failed_now != "0":
    print("  - FIX DATA FIRST: current DataFreshnessGuard snapshot is not clean; do not relax freshness rules.")
else:
    print("  - KEEP STRICT: DataFreshnessGuard current snapshot is clean; historical false kills should be handled as collector-cadence evidence, not relaxed blindly.")

if relaxation_lines:
    print("  - PRIORITIZE: review the listed relaxation candidates in shadow/tiny-live caps before changing any live execution policy.")

try:
    would_allow_num = int(entry_dedup_would_allow) if entry_dedup_would_allow != "N/A" else 0
    dedup_too_coarse_num = int(missed_dedup_too_coarse) if missed_dedup_too_coarse != "N/A" else 0
except ValueError:
    would_allow_num = 0
    dedup_too_coarse_num = 0

if would_allow_num <= 0 or dedup_too_coarse_num <= 0:
    print("  - DO NOT RELAX ENTRY DEDUP LIVE: governance false-block evidence is not enough; staged-add readiness did not find live-ready dedup relaxation candidates.")
else:
    print("  - REVIEW ENTRY DEDUP: staged-add readiness found candidate groups; require explicit operator approval before any live policy change.")

print("")
if not execution_ok:
    print("[signal-correctness] FAIL: missing no-missed-evaluation/no-missed-order marker from verifyStrategyExecution.", file=sys.stderr)
    sys.exit(1)

print("[signal-correctness] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "signal correctness smoke failed with exit code $LASTEXITCODE"
}
