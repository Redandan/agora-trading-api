param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [int]$Minutes = 43200
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

if ($Minutes -lt 60 -or $Minutes -gt 43200) {
    throw "Minutes must be between 60 and 43200."
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

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' SIDE='$Side' MINUTES='$Minutes' ENV_FILE='$EnvFile'
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
minutes = int(os.environ["MINUTES"])
env_file = os.environ["ENV_FILE"]

def read_env_key(key):
    value = ""
    try:
        with open(env_file, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == key:
                    value = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        return "MISSING_ENV_FILE"
    return value or "EMPTY"

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
        "id": f"runtime-evidence-rca-{name}",
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

def field(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def compact(text, limit=320):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

def count_lines(pattern, text):
    return sum(1 for line in text.splitlines() if re.search(pattern, line))

print("[runtime-evidence-rca] read-only server-local MCP smoke")
print(f"url={url} symbol={symbol} strategyId={strategy_id} side={side} minutes={minutes}")

dashboard = call_tool("getAutonomousReadinessDashboard", {"symbol": symbol, "minutes": minutes})
evidence = call_tool("listRuntimeDecisionEvidence", {"symbol": symbol, "minutes": minutes, "limit": 50})
preview = call_tool("previewTinyLiveMinimumOrder", {"symbol": symbol, "strategyId": strategy_id, "side": side})
auto_execution = call_tool("previewTinyLiveAutoExecution", {"symbol": symbol, "strategyId": strategy_id, "side": side})
opportunity = call_tool("validateAutonomousOpportunityReadiness", {"symbol": symbol, "strategyId": strategy_id, "side": side})
truth = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": min(720, max(1, minutes // 60)), "limit": 20})

for name, text in [
    ("getAutonomousReadinessDashboard", dashboard),
    ("listRuntimeDecisionEvidence", evidence),
    ("previewTinyLiveMinimumOrder", preview),
    ("previewTinyLiveAutoExecution", auto_execution),
    ("validateAutonomousOpportunityReadiness", opportunity),
]:
    require(f"{name} read-only boundary", r"READ_ONLY|no order/OCO/strategy/grid/fund/Earn", text)

require("preview no-order marker", r"orderSent=false", preview)
require("auto-execution no-order marker", r"orderSent=false", auto_execution)
require("truth table read-only marker", r"READ_ONLY|orderSent", truth)

runtime_flag = read_env_key("TRADING_RUNTIME_EVIDENCE_ENABLED")
dashboard_enabled = field(r"symbol=.* enabled=([^\n]+)", dashboard)
runtime_status = field(r"runtimeEvidenceStatus=([^\n]+)", preview)
preview_status = field(r"status=([^\n]+)", preview)
runtime_rows = field(r"runtimeEvidenceRows=(\d+)", dashboard)
sample_count = field(r"sampleCount=(\d+)", dashboard)
readiness_level = field(r"readinessLevel=([^\n]+)", dashboard)
evidence_mode = field(r"evidenceMode=([^\n]+)", dashboard)
final_verdict = field(r"finalReadinessVerdict=([^\n]+)", dashboard)
shadow_intents = field(r"shadowExecutionIntents=(\d+)", dashboard)
shadow_intent_count = field(r"shadowIntentCount=(\d+)", dashboard)
order_sent_evidence = field(r"orderSentEvidence=(\d+)", dashboard)
freshness_blocks = field(r"freshnessTerminalBlocks=(\d+)", dashboard)
evidence_row_lines = count_lines(r"^\d+\. #", evidence)
shadow_line_count = count_lines(r"SHADOW_MODE|intentCreated=True|intentCreated=true|fearGreedWarning", evidence)
required_fields = {
    "dashboardEnabled": dashboard_enabled,
    "runtimeEvidenceStatus": runtime_status,
    "runtimeEvidenceRows": runtime_rows,
    "sampleCount": sample_count,
    "readinessLevel": readiness_level,
    "evidenceMode": evidence_mode,
    "finalReadinessVerdict": final_verdict,
    "shadowExecutionIntents": shadow_intents,
    "shadowIntentCount": shadow_intent_count,
    "orderSentEvidence": order_sent_evidence,
    "freshnessTerminalBlocks": freshness_blocks,
}
missing_fields = [key for key, value in required_fields.items() if value in ("", "N/A")]

if missing_fields:
    diagnosis = "REVIEW_RUNTIME_EVIDENCE_STATUS"
elif runtime_status == "NOT_READY_ENABLED_FALSE" or dashboard_enabled.lower() == "false":
    diagnosis = "CONFIG_DISABLED"
elif runtime_status == "NOT_READY_NO_CANONICAL_ROWS" or runtime_rows == "0":
    diagnosis = "NO_CANONICAL_ROWS"
elif runtime_status == "AVAILABLE_CANONICAL_ROWS" and shadow_intent_count == "0":
    diagnosis = "CANONICAL_ROWS_NO_SHADOW_INTENT"
elif runtime_status == "AVAILABLE_CANONICAL_SHADOW_EVIDENCE":
    diagnosis = "CANONICAL_SHADOW_READY"
else:
    diagnosis = "REVIEW_RUNTIME_EVIDENCE_STATUS"

print("")
print("Runtime Evidence Gate:")
print(f"  diagnosis={diagnosis}")
print(f"  env.TRADING_RUNTIME_EVIDENCE_ENABLED={runtime_flag}")
print(f"  dashboardEnabled={dashboard_enabled}")
print(f"  runtimeEvidenceStatus={runtime_status}")
print(f"  previewStatus={preview_status}")
print(f"  evidenceMode={evidence_mode}")
print(f"  readinessLevel={readiness_level}")
print(f"  finalReadinessVerdict={final_verdict}")

print("")
print("Recent Evidence Window:")
print(f"  minutes={minutes}")
print(f"  runtimeEvidenceRows={runtime_rows}")
print(f"  sampleCount={sample_count}")
print(f"  listedRows={evidence_row_lines}")
print(f"  shadowExecutionIntents={shadow_intents}")
print(f"  shadowIntentCount={shadow_intent_count}")
print(f"  shadowLikeListedRows={shadow_line_count}")
print(f"  orderSentEvidence={order_sent_evidence}")
print(f"  freshnessTerminalBlocks={freshness_blocks}")
print(f"  missing_runtime_evidence_fields={json.dumps(missing_fields)}")

print("")
print("Candidate Context:")
print(f"  noCurrentBuyCandidateReason={field(r'noCurrentBuyCandidateReason=([^\n]+)', preview)}")
print(f"  currentSignalDecision={field(r'currentSignalDecision=([^\n]+)', preview)}")
print(f"  currentSignalSource={field(r'currentSignalSource=([^\n]+)', preview)}")
print(f"  currentSignalAgeMinutes={field(r'currentSignalAgeMinutes=([^\n]+)', preview)}")
print(f"  opportunity={compact(opportunity, 520)}")
print(f"  noBuyTruthTable={compact(truth, 520)}")

print("")
print("Recommendations:")
if diagnosis == "CONFIG_DISABLED":
    print("  - KEEP_DISABLED_FOR_NOW: runtime evidence collection is disabled; do not enable live until an explicit dry-run/evidence plan is approved.")
    print("  - PLAN: if authorized later, enable runtime-evidence collection before any live execution flag, then rerun this RCA for canonical shadow rows.")
elif diagnosis == "NO_CANONICAL_ROWS":
    print("  - COLLECT_EVIDENCE_FIRST: runtime evidence is enabled but no canonical rows were found in the selected window.")
elif diagnosis == "CANONICAL_ROWS_NO_SHADOW_INTENT":
    print("  - REQUIRE_SHADOW_INTENT: canonical rows exist but do not yet prove shadow/tiny-live intent coverage.")
elif diagnosis == "CANONICAL_SHADOW_READY":
    print("  - REVIEW_WITH_FULL_AUDIT: runtime evidence gate appears satisfied; live-readiness still requires all other blockers to clear.")
else:
    print("  - REVIEW: runtime evidence status needs operator interpretation before any live env plan.")
print("  - SCOPE: this smoke is read-only and must not write RuntimeDecisionEvidence, place orders, send Telegram, or enable flags.")
print("[runtime-evidence-rca] OK read-only check complete")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "runtime evidence RCA smoke failed with exit code $LASTEXITCODE"
}
