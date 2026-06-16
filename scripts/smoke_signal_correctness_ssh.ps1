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

if ($ExecutionDays -lt 1 -or $BlockedDays -lt 1 -or $AccuracyDays -lt 1) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must all be positive."
}

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
import urllib.request

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

print("[signal-correctness] read-only production MCP check")
print(f"symbol={symbol} executionDays={execution_days} blockedDays={blocked_days} accuracyDays={accuracy_days}")

execution = call_tool("verifyStrategyExecution", {"days": execution_days}, timeout=120)
blocked = call_tool("analyzeBlockedSignalOutcomes", {"days": blocked_days, "symbol": symbol}, timeout=120)
accuracy = call_tool("getSignalAccuracyReport", {"days": accuracy_days}, timeout=120)
freshness = call_tool("diagnoseDataFreshnessGuardBlocks", {"days": blocked_days, "symbol": symbol, "limit": 50}, timeout=120)
dashboard = call_tool("getSignalCorrectnessDashboard", {"symbol": symbol, "hours": 24}, timeout=120)

execution_ok = contains_any(execution, [r"未發現漏評估/漏單 Bug", r"no missing evaluation", r"no missed order"])
blocked_total = find(r"分析\s+(\d+)\s+筆", blocked)
blocked_correct = find(r"正確攔截.*?:\s+(\d+)\s+筆", blocked)
blocked_wrong = find(r"誤殺.*?:\s+(\d+)\s+筆", blocked)
blocked_correct_rate = find(r"正確攔截.*?\(([-+0-9.]+%)\)", blocked)
blocked_wrong_rate = find(r"誤殺.*?\(([-+0-9.]+%)\)", blocked)
avg_24h = find(r"平均 \+24h 報酬\s+:\s+([-+0-9.]+%)", blocked)
pass_line = find(r"(EnsembleGate \[PASS\].*)", accuracy)
stale_now = find(r"staleNowKeys=(\d+)", freshness)
no_data_now = find(r"noDataNowKeys=(\d+)", freshness)
query_failed_now = find(r"queryFailedNowKeys=(\d+)", freshness)
actionable = find(r"actionableCandidates=(\d+)", dashboard)
labeled = find(r"labeledCandidates=(\d+)", dashboard)
false_block_rate = find(r"falseBlockRate=([-+0-9.]+%)", dashboard)
governance_mode = find(r"governanceMode=([A-Z_]+)", dashboard)

print("")
print("Execution:")
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
print("Recommendations:")
if not execution_ok:
    print("  - INVESTIGATE: verifyStrategyExecution did not provide the expected no-missing-evaluation/no-missed-order marker.")
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

print("")
print("[signal-correctness] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "signal correctness smoke failed with exit code $LASTEXITCODE"
}
