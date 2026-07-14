param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$ExpectedMode = "SHADOW",
    [string]$ExpectedLocalTradingViewMode = "BTC_BASE_DRY_RUN"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or
        $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Z0-9_]+$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey)) { throw "A valid SshKey is required." }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "ExpectedMode" -Value $ExpectedMode
Assert-McpSmokeTokenSafe -Name "ExpectedLocalTradingViewMode" -Value $ExpectedLocalTradingViewMode

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

PORT=$(cat app.port)
export PORT ENV_FILE='__ENVFILE__' EXPECTED_MODE='__EXPECTED_MODE__' EXPECTED_LOCAL_TV_MODE='__EXPECTED_LOCAL_TV_MODE__'
python3 - <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

def read_env(path):
    values = {}
    with open(path, "r", encoding="utf-8-sig") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            values[key.strip()] = value
    return values

env = read_env(os.environ["ENV_FILE"])
mcp_key = env.get("TRADING_MCP_KEY", "")
if not mcp_key:
    print("FAIL: TRADING_MCP_KEY missing in env file", file=sys.stderr)
    sys.exit(1)

expected = {
    "TRADING_508_TIME_EXIT_MODE": os.environ["EXPECTED_MODE"],
    "TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED": "false",
    "TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED": "false",
    "TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS": "",
    "TRADING_LEGACY_SECONDARY_MAX_NOTIONAL_USDT": "0",
    "TRADINGVIEW_LOCAL_EXECUTION_MODE": os.environ["EXPECTED_LOCAL_TV_MODE"],
    "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED": "false",
}
for key, expected_value in expected.items():
    actual = env.get(key, "")
    if actual.strip().upper() != expected_value.strip().upper():
        print(f"FAIL: {key} expected={expected_value!r} actual={actual!r}", file=sys.stderr)
        sys.exit(1)

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {"Content-Type": "application/json", "Authorization": f"Bearer {mcp_key}"}

def call_tool(name, arguments, timeout=300):
    payload = {"jsonrpc": "2.0", "id": name, "method": "tools/call",
               "params": {"name": name, "arguments": arguments}}
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        print(f"FAIL: {name} HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}", file=sys.stderr)
        sys.exit(1)
    if body.get("error"):
        print(f"FAIL: {name} MCP error: {body['error']}", file=sys.stderr)
        sys.exit(1)
    result = body.get("result") or {}
    if result.get("isError"):
        print(f"FAIL: {name} returned isError=true: {result}", file=sys.stderr)
        sys.exit(1)
    content = result.get("content") or []
    text = content[0].get("text", "") if content and isinstance(content[0], dict) else json.dumps(result)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                text = decoded
        except Exception:
            pass
    try:
        return json.loads(text)
    except Exception as exc:
        print(f"FAIL: {name} result is not JSON: {exc}; text={text[:1000]}", file=sys.stderr)
        sys.exit(1)

def require_equal(path, actual, expected_value):
    if actual != expected_value:
        print(f"FAIL: {path} expected={expected_value!r} actual={actual!r}", file=sys.stderr)
        sys.exit(1)

candidate = call_tool("analyzeStrategy508TimeExitCandidate", {"symbol": "BTCUSDT", "detailLimit": 10})
require_equal("candidate.tool", candidate.get("tool"), "analyzeStrategy508TimeExitCandidate")
require_equal("candidate.boundary", candidate.get("boundary"), "READ_ONLY")
require_equal("candidate.policyMode", candidate.get("policyMode"), "STRATEGY_508_4H_24H_V1")
require_equal("candidate.strategyId", candidate.get("strategyId"), 508)
require_equal("candidate.livePromotionAllowed", candidate.get("livePromotionAllowed"), False)
require_equal("candidate.outcomeReconciled", candidate.get("outcomeReconciled"), True)
require_equal("candidate.minuteReplaySemantics", candidate.get("minuteReplaySemantics"),
              "DETERMINISTIC_1M_OHLC_NOT_EXACT_EXCHANGE_FILL")
require_equal("candidate.minuteLatticeValidation", candidate.get("minuteLatticeValidation"),
              "EXACT_UTC_MINUTE_GRID_DISTINCT_TIMESTAMP_AND_OHLC_INVARIANTS")
if not candidate.get("effectivePolicyConfigSha256"):
    print("FAIL: candidate effectivePolicyConfigSha256 missing", file=sys.stderr)
    sys.exit(1)
require_equal("candidate.safety.orderSent", (candidate.get("safety") or {}).get("orderSent"), False)
require_equal("candidate.safety.ocoModified", (candidate.get("safety") or {}).get("ocoModified"), False)
require_equal("candidate.safety.databaseMutated", (candidate.get("safety") or {}).get("databaseMutated"), False)
if candidate.get("sampleStatus") not in (
        "INSUFFICIENT_EXACT_1M_SAMPLE",
        "HISTORICAL_SAMPLE_READY"):
    print(f"FAIL: unexpected candidate sampleStatus={candidate.get('sampleStatus')}", file=sys.stderr)
    sys.exit(1)

readiness = call_tool("getStrategy508TimeExitReadiness", {"symbol": "BTCUSDT"})
require_equal("readiness.tool", readiness.get("tool"), "getStrategy508TimeExitReadiness")
require_equal("readiness.boundary", readiness.get("boundary"), "READ_ONLY")
require_equal("readiness.configuredMode", readiness.get("configuredMode"), os.environ["EXPECTED_MODE"])
require_equal("readiness.liveOrderFlag", readiness.get("liveOrderFlag"), False)
require_equal("readiness.liveMicroArmed", readiness.get("liveMicroArmed"), False)
require_equal("readiness.liveOrderAllowed", readiness.get("liveOrderAllowed"), False)
require_equal("readiness.exactLiveFillEvidenceImplemented",
              readiness.get("exactLiveFillEvidenceImplemented"), False)
require_equal("readiness.effectivePolicyConfigSha256",
              readiness.get("effectivePolicyConfigSha256"),
              candidate.get("effectivePolicyConfigSha256"))
require_equal("readiness.forwardShadow.cohortSemantics",
              (readiness.get("forwardShadow") or {}).get("cohortSemantics"),
              "EXPLICIT_EXECUTABLE_SHADOW_ONLY_UNIQUE_EVENT_CONFIG_BOUND_FAIL_CLOSED")
require_equal("readiness.forwardShadow.promotionCohort",
              (readiness.get("forwardShadow") or {}).get("promotionCohort"),
              "EXECUTABLE_SHADOW")
require_equal("readiness.forwardShadow.hardGateBlockedEventsAffectPromotion",
              (readiness.get("forwardShadow") or {}).get("hardGateBlockedEventsAffectPromotion"),
              False)
require_equal("readiness.rawSignalCounterfactual.livePromotionEligible",
              (readiness.get("rawSignalCounterfactual") or {}).get("livePromotionEligible"),
              False)
require_equal("readiness.rawSignalCounterfactual.cohortSchemaVersion",
              (readiness.get("rawSignalCounterfactual") or {}).get("cohortSchemaVersion"),
              "STRATEGY_508_TIME_EXIT_COHORT_V1")
require_equal("readiness.safety.orderSent", (readiness.get("safety") or {}).get("orderSent"), False)
require_equal("readiness.safety.ocoModified", (readiness.get("safety") or {}).get("ocoModified"), False)

pnl = call_tool("getStrategyNetPnlAttribution", {"strategyId": 508, "symbol": "BTCUSDT", "days": 365})
require_equal("pnl.tool", pnl.get("tool"), "getStrategyNetPnlAttribution")
require_equal("pnl.boundary", pnl.get("boundary"), "READ_ONLY")
require_equal("pnl.strategyId", pnl.get("strategyId"), 508)
require_equal("pnl.livePromotionAllowed", pnl.get("livePromotionAllowed"), False)
require_equal("pnl.cohortScope", pnl.get("cohortScope"), "ALL_AUTO_TRADED_STRATEGY_POSITIONS")
require_equal("pnl.cohortFiltering", pnl.get("cohortFiltering"),
              "NONE_GENERIC_TOOL_NEVER_HIDES_MATCHING_POSITIONS")
require_equal("pnl.feeAmountSemantics", pnl.get("feeAmountSemantics"),
              "POSITIVE_COST_NEGATIVE_REBATE")
require_equal("pnl.exactFillEvidenceProducerStatus", pnl.get("exactFillEvidenceProducerStatus"),
              "BLOCKED_NO_IMMUTABLE_ALL_FILL_SIGNED_FEE_LEDGER")

print("[strategy508-time-exit] production SHADOW read-only smoke")
print(f"activePort={os.environ['PORT']} mode={readiness.get('configuredMode')} liveOrderFlag={readiness.get('liveOrderFlag')}")
print(f"historicalSampleStatus={candidate.get('sampleStatus')} historicalVerdict={candidate.get('verdict')}")
print(f"historicalFinalizedEvents={candidate.get('finalizedEvents')} insufficientCoverageEvents={candidate.get('insufficientCoverageEvents')}")
print(f"readinessVerdict={readiness.get('verdict')} blockers={json.dumps(readiness.get('blockers') or [])}")
print(f"pnlStatus={pnl.get('status')} pnlSummary={json.dumps(pnl.get('summary') or {}, sort_keys=True)}")
print("orderAllowed=false; live promotion remains not authorized")
print("[strategy508-time-exit] OK read-only check complete")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).
    Replace("__ENVFILE__", $EnvFile).
    Replace("__EXPECTED_MODE__", $ExpectedMode).
    Replace("__EXPECTED_LOCAL_TV_MODE__", $ExpectedLocalTradingViewMode)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "strategy 508 time-exit SHADOW smoke failed with exit code $LASTEXITCODE"
}
