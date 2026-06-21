param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ShortDays = 3,
    [int]$ReviewDays = 7,
    [int]$LongDays = 14,
    [int]$Limit = 120
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

if ($ShortDays -lt 1 -or $ShortDays -gt 30) {
    throw "ShortDays must be between 1 and 30."
}

if ($ReviewDays -lt 1 -or $ReviewDays -gt 60) {
    throw "ReviewDays must be between 1 and 60."
}

if ($LongDays -lt 1 -or $LongDays -gt 90) {
    throw "LongDays must be between 1 and 90."
}

if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
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

export PORT MCP_KEY SYMBOL='__SYMBOL__' SHORT_DAYS='__SHORT_DAYS__' REVIEW_DAYS='__REVIEW_DAYS__' LONG_DAYS='__LONG_DAYS__' LIMIT='__LIMIT__'
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
short_days = int(os.environ["SHORT_DAYS"])
review_days = int(os.environ["REVIEW_DAYS"])
long_days = int(os.environ["LONG_DAYS"])
limit = int(os.environ["LIMIT"])

def call_tool(name, arguments=None, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": f"data-freshness-false-kill-{name}",
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

def compact(value, limit=800):
    text = str(value or "N/A").replace("\r", "").replace("\n", " ").strip()
    return text if len(text) <= limit else text[:limit - 3] + "..."

def regex(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else default

def integer(pattern, text, default=0):
    value = regex(pattern, text, None)
    try:
        return int(value)
    except Exception:
        return default

def parse_blocker_metrics(blocked, blocker):
    pattern = rf"{re.escape(blocker)}:.*?falseKill\s+([0-9.]+)%.*?avgRet\s+([+-]?[0-9.]+)%"
    match = re.search(pattern, blocked)
    if not match:
        return {"falseKillPct": "N/A", "avgRetPct": "N/A"}
    return {"falseKillPct": match.group(1), "avgRetPct": match.group(2)}

def top_join(counter, max_items=8):
    if not counter:
        return "none"
    return ",".join(f"{name}:{count}" for name, count in counter.most_common(max_items))

print("[data-freshness-false-kill-review] read-only production MCP check")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.")
print(f"symbol={symbol} shortDays={short_days} reviewDays={review_days} longDays={long_days} limit={limit} activePort={os.environ['PORT']}")

short_rca = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": short_days, "symbol": symbol, "limit": limit})
review_rca = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": review_days, "symbol": symbol, "limit": limit})
long_rca = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": long_days, "symbol": symbol, "limit": limit})
blocked = call_tool("analyzeBlockedSignalOutcomes", {"days": long_days, "symbol": symbol}, timeout=220)
governance = call_tool("getGovernanceDriftDashboard", {"symbol": symbol, "days": review_days, "labelHorizon": "24h"})
relaxation = call_tool("findGovernanceRelaxationCandidates", {"symbol": symbol, "days": review_days, "labelHorizon": "24h"})
missed = call_tool("getMissedOpportunityRegressionReport", {"symbol": symbol, "hours": review_days * 24})
truth = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": review_days * 24, "limit": 50})

for name, text in (("short RCA", short_rca), ("review RCA", review_rca), ("long RCA", long_rca)):
    require(f"{name} read-only boundary", r"boundary:\s*READ_ONLY", text)
    require(f"{name} explicit acceptance", r"acceptance:\s*PASS_NO_CURRENT_SAMPLE|acceptance:\s*PASS_RCA_CLASSIFIED", text)
require("blocked-signal read-only boundary", r"mode=READ_ONLY", blocked)
require("governance drift read-only boundary", r"boundary:\s*READ_ONLY", governance)
require("governance relaxation read-only boundary", r"boundary:\s*READ_ONLY", relaxation)
require("governance relaxation criteria", r"criteria:", relaxation)
require("missed opportunity read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', missed)
require("truth table read-only boundary", r'"boundary"\s*:\s*"READ_ONLY', truth)

rows = integer(r"rows=(\d+)", review_rca)
true_stale = integer(r"trueStaleKline=(\d+)", review_rca)
context_mismatch = integer(r"contextMismatchReview=(\d+)", review_rca)
legacy_missing = integer(r"legacyOrMissingContext=(\d+)", review_rca)
ready_now = integer(r"readyNowKeys=(\d+)", review_rca)
stale_now = integer(r"staleNowKeys=(\d+)", review_rca)
no_data_now = integer(r"noDataNowKeys=(\d+)", review_rca)
query_failed_now = integer(r"queryFailedNowKeys=(\d+)", review_rca)
short_rows = integer(r"rows=(\d+)", short_rca)
short_no_sample = "PASS_NO_CURRENT_SAMPLE" in short_rca
data_freshness_metrics = parse_blocker_metrics(blocked, "DataFreshnessGuard")
governance_mode = regex(r"governanceMode=([A-Z_]+)", governance)
relaxation_line = regex(r"(- blocker=DataFreshness[^\r\n]+)", relaxation)
relaxation_sample = regex(r"sampleSize=(\d+)", relaxation_line, "N/A")
relaxation_missed_alpha = regex(r"missedAlphaCount=(\d+)", relaxation_line, "N/A")
relaxation_avg_return = regex(r"avgMissedForwardReturn=([+-]?[0-9.]+%)", relaxation_line, "N/A")
relaxation_risk = regex(r"riskLevel=([A-Z_]+)", relaxation_line, "N/A")

strategy_counts = Counter()
interval_counts = Counter()
source_counts = Counter()
max_stale_by_interval = {}
for match in re.finditer(r"strategy=(\d+) interval=([0-9a-z]+) source=([A-Za-z0-9_-]+) class=TRUE_STALE_KLINE count=(\d+) maxStale=(\d+) maxThreshold=(\d+)", review_rca):
    strategy = match.group(1)
    interval = match.group(2)
    source = match.group(3)
    count = int(match.group(4))
    max_stale = int(match.group(5))
    strategy_counts[strategy] += count
    interval_counts[interval] += count
    source_counts[source] += count
    max_stale_by_interval[interval] = max(max_stale_by_interval.get(interval, 0), max_stale)

current_clean = ready_now > 0 and stale_now == 0 and no_data_now == 0 and query_failed_now == 0
historical_stale_only = rows > 0 and rows == true_stale and context_mismatch == 0 and legacy_missing == 0
recent_recovered = short_no_sample or short_rows == 0
false_kill_pressure = data_freshness_metrics["falseKillPct"] != "N/A" and float(data_freshness_metrics["falseKillPct"]) >= 50.0
positive_missed_return = data_freshness_metrics["avgRetPct"] != "N/A" and float(data_freshness_metrics["avgRetPct"]) > 0
governance_too_strict = governance_mode == "TOO_STRICT"
has_datafreshness_relaxation = relaxation_line != "N/A"

if not current_clean:
    recommendation = "FIX_CURRENT_DATA_FRESHNESS_FIRST"
elif historical_stale_only and false_kill_pressure and positive_missed_return and governance_too_strict:
    recommendation = "REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE"
elif has_datafreshness_relaxation:
    recommendation = "REVIEW_DATAFRESHNESS_RELAXATION_CANDIDATE_READ_ONLY"
else:
    recommendation = "NO_DATAFRESHNESS_PROFIT_CANDIDATE"

shadow_replay_plan = [
    {
        "step": "counterfactual",
        "requiredEvidence": [
            "DataFreshnessGuard rows classified TRUE_STALE_KLINE",
            "current snapshot readyNowKeys>0 and stale/noData/queryFailed all zero",
            "blocked outcome falseKill and avgRet stay positive over review window",
        ],
        "notAuthorization": "does not authorize bypassing DataFreshnessGuard or placing live orders",
    },
    {
        "step": "shadowOnly",
        "requiredEvidence": [
            "replay candidate decisions through shadow/tiny-live caps with hard safety gates intact",
            "ExpectedValueGate, OCO preflight, daily cap, duplicate, exposure, and event-risk gates still evaluated",
            "compare missed forward return against actual executable TP/SL/OCO feasibility",
        ],
        "notAuthorization": "does not authorize live policy relaxation, scheduler enablement, or OCO/order mutation",
    },
    {
        "step": "cadenceFix",
        "requiredEvidence": [
            "collector/source gap root cause identified for 1h/4h/1d okx stale windows",
            "post-fix RCA shows no reappearing staleNowKeys over fresh observation",
        ],
        "notAuthorization": "does not authorize external backfill/import or production env changes",
    },
]

print("")
print("Current Snapshot:")
print(f"  readyNowKeys={ready_now}")
print(f"  staleNowKeys={stale_now}")
print(f"  noDataNowKeys={no_data_now}")
print(f"  queryFailedNowKeys={query_failed_now}")
print(f"  currentDataFreshnessClean={str(current_clean).lower()}")
print("")
print("Historical DataFreshness Rows:")
print(f"  shortWindowRows={short_rows}")
print(f"  shortWindowNoCurrentSample={str(short_no_sample).lower()}")
print(f"  reviewRows={rows}")
print(f"  trueStaleKline={true_stale}")
print(f"  contextMismatchReview={context_mismatch}")
print(f"  legacyOrMissingContext={legacy_missing}")
print(f"  historicalStaleOnly={str(historical_stale_only).lower()}")
print(f"  strategies={top_join(strategy_counts)}")
print(f"  intervals={top_join(interval_counts)}")
print(f"  sources={top_join(source_counts)}")
print("  maxStaleByInterval=" + json.dumps(max_stale_by_interval, sort_keys=True, separators=(",", ":")))
print("")
print("False-Kill / Governance Evidence:")
print(f"  dataFreshnessFalseKillPct={data_freshness_metrics['falseKillPct']}")
print(f"  dataFreshnessAvgRetPct={data_freshness_metrics['avgRetPct']}")
print(f"  governanceMode={governance_mode}")
print(f"  relaxationCandidatePresent={str(has_datafreshness_relaxation).lower()}")
print(f"  relaxationSampleSize={relaxation_sample}")
print(f"  relaxationMissedAlphaCount={relaxation_missed_alpha}")
print(f"  relaxationAvgMissedForwardReturn={relaxation_avg_return}")
print(f"  relaxationRiskLevel={relaxation_risk}")
print("  relaxationCandidate=" + compact(relaxation_line, 900))
print("")
print("Evidence Excerpts:")
print("  shortRca=" + compact(short_rca, 600))
print("  reviewRca=" + compact(review_rca, 900))
print("  blockedSignalOutcomes=" + compact(blocked, 900))
print("  missedOpportunity=" + compact(missed, 700))
print("  noBuyTruthTable=" + compact(truth, 700))
print("")
print("Shadow Replay Plan:")
print("  data_freshness_shadow_replay_plan=" + json.dumps(shadow_replay_plan, separators=(",", ":")))
print("")
print("Conclusion:")
print(f"  recentRecovered={str(recent_recovered).lower()}")
print(f"  falseKillPressure={str(false_kill_pressure).lower()}")
print(f"  positiveMissedReturn={str(positive_missed_return).lower()}")
print(f"  governanceTooStrict={str(governance_too_strict).lower()}")
print(f"  data_freshness_false_kill_recommendation={recommendation}")
print("  notAuthorization=read-only evidence only; does not authorize DataFreshnessGuard relaxation, live trading, strategy activation, closing positions, OCO modification, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or production env changes")
print("")
print("[data-freshness-false-kill-review] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__SYMBOL__", $Symbol).
    Replace("__SHORT_DAYS__", [string]$ShortDays).
    Replace("__REVIEW_DAYS__", [string]$ReviewDays).
    Replace("__LONG_DAYS__", [string]$LongDays).
    Replace("__LIMIT__", [string]$Limit)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "DataFreshness false-kill review smoke failed with exit code $LASTEXITCODE"
}
