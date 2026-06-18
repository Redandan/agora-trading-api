param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT"
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
APP_DIR='$AppDir'
ENV_FILE='$EnvFile'
SYMBOL='$Symbol'
cd "`$APP_DIR"

PORT=`$(tr -d '[:space:]' < app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' "`$ENV_FILE" | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "verdict=NOT_READY"
  echo "blocker=MCP_KEY_MISSING"
  exit 0
fi

export PORT MCP_KEY SYMBOL ENV_FILE APP_DIR
python3 - <<'PY'
import json
import os
import re
import subprocess
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
env_file = os.environ["ENV_FILE"]
symbol = os.environ["SYMBOL"].upper()
blockers = []
warnings = []
readiness_details = {}

def read_env_key(key):
    try:
        with open(env_file, "r", encoding="utf-8") as handle:
            value = ""
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == key:
                    value = v.strip().strip('"').strip("'")
            return value
    except FileNotFoundError:
        blockers.append("ENV_FILE_MISSING")
        return ""

def bool_env(key, default=None):
    value = read_env_key(key)
    if not value:
        return default
    return value.lower() == "true"

def secret_presence(key):
    return "SET" if read_env_key(key) else "EMPTY"

def request(body, timeout=180):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    if data.get("error"):
        raise RuntimeError(data["error"])
    return data

def call_tool(name, arguments=None, timeout=180):
    data = request({
        "jsonrpc": "2.0",
        "id": f"live-readiness-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }, timeout=timeout)
    result = data.get("result") or {}
    if result.get("isError"):
        blockers.append(f"MCP_TOOL_ERROR:{name}")
        return ""
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

def require_contains(name, text, pattern, blocker):
    if not re.search(pattern, text, re.MULTILINE):
        blockers.append(blocker)

def parse_json_text(text):
    try:
        decoded = json.loads(text)
        return decoded if isinstance(decoded, dict) else None
    except Exception:
        return None

def regex_value(text, pattern, default=""):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def tiny_details(text):
    return {
        "executionEligible": regex_value(text, r"executionEligible=(\w+)"),
        "wouldExecute": regex_value(text, r"wouldExecute=(\w+)"),
        "previewStatus": regex_value(text, r"previewStatus=([^\n]+)"),
        "runtimeEvidenceStatus": regex_value(text, r"runtimeEvidenceStatus=([^\n]+)"),
        "approvalReason": regex_value(text, r"approvalReason=([^\n]+)"),
        "blockers": regex_value(text, r"blockers=(\[[^\n]+)"),
        "terminalBlockers": regex_value(text, r"terminalBlockers=(\[[^\n]+)"),
    }

def score_buy_details(text):
    obj = parse_json_text(text)
    if not obj:
        return {"parseError": "non_json_response"}
    keys = [
        "enabled", "dryRun", "orderSent", "ocoAttached", "executionEligible",
        "wouldExecute", "executionPolicy", "confirmedDeployPolicy",
        "postScoutManagementState", "recommendedAction", "reason",
        "primaryNoBuyReason", "blockingInterpretation", "eventRiskLevel",
        "proposedNotionalUsdt", "firstTrancheNotionalUsdt",
        "suggestedAddNotionalUsdt", "maxNotionalUsdt", "scoreBuyFormingState",
        "scoreBuyHoldingState", "dailyScoreBuyConfirmed", "postScoutDuplicateReason",
        "primaryBlockers", "blockers", "capacityBlockers", "warnings",
    ]
    return {key: obj.get(key) for key in keys if key in obj}

def opportunity_details(text):
    obj = parse_json_text(text)
    if not obj:
        return {"parseError": "non_json_response"}
    keys = [
        "symbol", "strategyId", "side", "eligible", "orderSent", "reason",
        "executionMode", "explorationMode", "expectedLearningValue",
        "explorationBudgetRemaining", "ordersToday", "openTinyLivePositions",
        "blockers", "warnings",
    ]
    return {key: obj.get(key) for key in keys if key in obj}

print("[live-readiness] read-only server audit")
print(f"url={url}")
print(f"symbol={symbol}")
print(f"commit={open('app.commit', encoding='utf-8').read().strip()}")
print(f"port={open('app.port', encoding='utf-8').read().strip()}")

health = urllib.request.urlopen(f"http://127.0.0.1:{os.environ['PORT']}/api/actuator/health", timeout=10).read().decode("utf-8", "replace")
print(f"health={health}")
if '"UP"' not in health:
    blockers.append("HEALTH_NOT_UP")

order_flags = {
    "TRADING_OKX_ENABLED": bool_env("TRADING_OKX_ENABLED", False),
    "TRADING_OCO_POLLER_ENABLED": bool_env("TRADING_OCO_POLLER_ENABLED", False),
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED": bool_env("TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED", False),
    "TRAILING_STOP_ENABLED": bool_env("TRAILING_STOP_ENABLED", False),
    "POSITION_EXIT_MANAGER_ENABLED": bool_env("POSITION_EXIT_MANAGER_ENABLED", False),
    "TRADING_GRID_ENABLED": bool_env("TRADING_GRID_ENABLED", False),
    "TRADING_FUNDING_ARB_ENABLED": bool_env("TRADING_FUNDING_ARB_ENABLED", False),
    "OKX_EARN_TOPUP_ENABLED": bool_env("OKX_EARN_TOPUP_ENABLED", False),
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED": bool_env("MCP_GUARDIAN_LIVE_ACTIONS_ENABLED", False),
}
dry_run_flags = {
    "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN": bool_env("TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN", True),
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN": bool_env("TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN", True),
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN": bool_env("TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN", True),
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN": bool_env("TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN", True),
    "TRAILING_STOP_DRY_RUN": bool_env("TRAILING_STOP_DRY_RUN", True),
    "POSITION_EXIT_MANAGER_DRY_RUN": bool_env("POSITION_EXIT_MANAGER_DRY_RUN", True),
}
background_true = []
for key in [
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_DAILY_TG_REPORT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED",
]:
    if bool_env(key, False):
        background_true.append(key)

print("order_capable_flags=" + json.dumps(order_flags, sort_keys=True))
print("dry_run_flags=" + json.dumps(dry_run_flags, sort_keys=True))
print("background_automation_true=" + json.dumps(background_true))
print("secret_presence=" + json.dumps({
    "TRADING_OKX_API_KEY": secret_presence("TRADING_OKX_API_KEY"),
    "TRADING_OKX_SECRET_KEY": secret_presence("TRADING_OKX_SECRET_KEY"),
    "TRADING_OKX_PASSPHRASE": secret_presence("TRADING_OKX_PASSPHRASE"),
    "TELEGRAM_BOT_TOKEN": secret_presence("TELEGRAM_BOT_TOKEN"),
    "MCP_GUARDIAN_KEY": secret_presence("MCP_GUARDIAN_KEY"),
}, sort_keys=True))

if secret_presence("TRADING_OKX_API_KEY") != "SET" or secret_presence("TRADING_OKX_SECRET_KEY") != "SET" or secret_presence("TRADING_OKX_PASSPHRASE") != "SET":
    blockers.append("OKX_CREDENTIALS_NOT_SET")

if order_flags["TRADING_OKX_ENABLED"]:
    warnings.append("TRADING_OKX_ALREADY_ENABLED")

if background_true:
    warnings.append("BACKGROUND_AUTOMATION_ALREADY_TRUE_REVIEW_BEFORE_LIVE")

tiny = call_tool("getTinyLiveAutoExecutionTriggerStatus", {"symbol": symbol})
readiness_details["tinyLive"] = tiny_details(tiny)
opportunity = call_tool("validateAutonomousOpportunityReadiness", {"symbol": symbol})
readiness_details["autonomousOpportunity"] = opportunity_details(opportunity)
require_contains("tiny", tiny, r"boundary:\s*READ_ONLY", "TINY_STATUS_BOUNDARY_MISSING")
require_contains("tiny", tiny, r"orderSent=false", "TINY_ORDER_SENT_MARKER_MISSING")
require_contains("tiny", tiny, r"triggerEnabled=false", "TINY_TRIGGER_ALREADY_ENABLED_OR_MARKER_MISSING")
require_contains("tiny", tiny, r"triggerDryRun=true", "TINY_DRY_RUN_NOT_TRUE")
if "executionEligible=true" not in tiny:
    blockers.append("TINY_LIVE_NOT_EXECUTION_ELIGIBLE")

pre = call_tool("getScoreBuyPrePositionAutoExecutionStatus", {"symbol": symbol})
confirmed = call_tool("getScoreBuyConfirmedDeployAutoExecutionStatus", {"symbol": symbol})
post = call_tool("getScoreBuyPostScoutAutoAddStatus", {"symbol": symbol})
readiness_details["scoreBuyPrePosition"] = score_buy_details(pre)
readiness_details["scoreBuyConfirmedDeploy"] = score_buy_details(confirmed)
readiness_details["scoreBuyPostScoutAdd"] = score_buy_details(post)
for name, text in [("PRE_POSITION", pre), ("CONFIRMED_DEPLOY", confirmed), ("POST_SCOUT_ADD", post)]:
    require_contains(name, text, r'"enabled"\s*:\s*false', f"{name}_ENABLED_NOT_FALSE")
    require_contains(name, text, r'"dryRun"\s*:\s*true', f"{name}_DRY_RUN_NOT_TRUE")
    require_contains(name, text, r'"orderSent"\s*:\s*false', f"{name}_ORDER_SENT_MARKER_MISSING")
    if '"executionEligible" : true' not in text and '"executionEligible":true' not in text:
        blockers.append(f"{name}_NOT_EXECUTION_ELIGIBLE")

trailing = call_tool("getTrailingStopStatus")
require_contains("trailing", trailing, r"global\.enabled:\s*false", "TRAILING_ALREADY_ENABLED_OR_MARKER_MISSING")
require_contains("trailing", trailing, r"global\.dryRun:\s*true", "TRAILING_DRY_RUN_NOT_TRUE")
if "open_oco_positions: 0" in trailing:
    warnings.append("NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_OBSERVATION")

event = call_tool("getEventRiskControlStatus", {"symbol": symbol})
require_contains("event risk", event, r"boundary=READ_ONLY", "EVENT_RISK_BOUNDARY_MISSING")
require_contains("event risk", event, r"operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION", "EVENT_RISK_OPERATOR_CONTROL_MARKER_MISSING")
if "riskLevel=R0" not in event:
    blockers.append("EVENT_RISK_NOT_R0")

guardian = call_tool("getGuardianSnapshot", {"symbol": symbol})
require_contains("guardian", guardian, r'"writeMode"\s*:\s*false', "GUARDIAN_WRITE_MODE_NOT_FALSE")
require_contains("guardian", guardian, r'"liveActionsExecuted"\s*:\s*false', "GUARDIAN_LIVE_ACTIONS_EXECUTED")

try:
    log = subprocess.run(["bash", "scripts/check_server_runtime_log.sh"], cwd=os.environ["APP_DIR"], check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    print("runtime_log_status=PASS" if log.returncode == 0 else "runtime_log_status=FAIL")
    for line in log.stdout.splitlines()[-12:]:
        print("runtime_log: " + line)
    if log.returncode != 0:
        blockers.append("RUNTIME_LOG_SMOKE_FAILED")
except Exception as exc:
    blockers.append("RUNTIME_LOG_SMOKE_EXCEPTION")
    print(f"runtime_log_exception={type(exc).__name__}:{exc}")

print("readiness_details=" + json.dumps(readiness_details, ensure_ascii=False, sort_keys=True))
print("warnings=" + json.dumps(warnings))
print("blockers=" + json.dumps(blockers))
if blockers:
    print("verdict=NOT_READY")
else:
    print("verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "live readiness audit failed with exit code $LASTEXITCODE"
}
