param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [switch]$LiveAuthorized
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

$runtimeLogScript = Join-Path $PSScriptRoot "check_server_runtime_log.sh"
if (-not (Test-Path -LiteralPath $runtimeLogScript)) {
    throw "Runtime log checker not found: $runtimeLogScript"
}
$runtimeLogBody = Get-Content -Raw -LiteralPath $runtimeLogScript
$runtimeLogBodyB64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($runtimeLogBody))

$remoteScript = @"
set -euo pipefail
APP_DIR='$AppDir'
ENV_FILE='$EnvFile'
SYMBOL='$Symbol'
RUNTIME_LOG_CHECKER_B64='$runtimeLogBodyB64'
cd "`$APP_DIR"

PORT=`$(tr -d '[:space:]' < app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' "`$ENV_FILE" | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "verdict=NOT_READY"
  echo "blocker=MCP_KEY_MISSING"
  exit 0
fi

export PORT MCP_KEY SYMBOL ENV_FILE APP_DIR RUNTIME_LOG_CHECKER_B64 LIVE_AUTHORIZED='$($LiveAuthorized.IsPresent)'
python3 - <<'PY'
import base64
import json
import os
import re
import subprocess
import tempfile
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
env_file = os.environ["ENV_FILE"]
symbol = os.environ["SYMBOL"].upper()
live_authorized = os.environ.get("LIVE_AUTHORIZED", "").lower() == "true"
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

def missing_readiness_detail_fields(details):
    required = {
        "tinyLive": ["executionEligible", "wouldExecute", "previewStatus", "runtimeEvidenceStatus"],
        "autonomousOpportunity": ["eligible", "orderSent", "reason"],
        "scoreBuyPrePosition": ["enabled", "dryRun", "orderSent", "executionEligible"],
        "scoreBuyConfirmedDeploy": ["enabled", "dryRun", "orderSent", "executionEligible"],
        "scoreBuyPostScoutAdd": ["enabled", "dryRun", "orderSent", "executionEligible"],
    }
    missing = []
    for section, fields in required.items():
        value = details.get(section)
        if not isinstance(value, dict) or not value:
            missing.append(section)
            continue
        if value.get("parseError"):
            missing.append(f"{section}.parseError")
        for field_name in fields:
            field_value = value.get(field_name)
            if field_value is None or str(field_value).strip() in ("", "N/A"):
                missing.append(f"{section}.{field_name}")
    return missing

def flatten_strings(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, bool):
        return [str(value).lower()]
    if isinstance(value, (int, float)):
        return [str(value)]
    if isinstance(value, list):
        result = []
        for item in value:
            result.extend(flatten_strings(item))
        return result
    if isinstance(value, dict):
        result = []
        for item in value.values():
            result.extend(flatten_strings(item))
        return result
    return [str(value)]

def has_any(values, needles):
    joined = "\n".join(flatten_strings(values))
    return any(needle in joined for needle in needles)

def classify_live_readiness(details, current_blockers, current_warnings, background_flags):
    classification = {
        "market_condition_wait": [],
        "runtime_evidence_gap": [],
        "risk_hard_stop": [],
        "execution_disabled_guard": [],
        "capacity_not_primary": [],
        "background_automation_review": [],
        "security_or_secret_gap": [],
        "runtime_health_gap": [],
    }

    if has_any(details, [
        "NO_CURRENT_BUY_CANDIDATE",
        "WAIT_BUY_THRESHOLD_CROSS",
        "DAILY_SCORE_BUY_NOT_CONFIRMED",
        "HOLD_SCOUT_MONITOR",
        "FORMING_STATE_SCOUT_ACTIVE_NOT_PRE_POSITION",
        "SCOUT_ACTIVE",
        "POST_SCOUT_ADD_NOT_ELIGIBLE",
    ]):
        classification["market_condition_wait"].append("No live buy/add candidate is currently confirmed; wait for the documented signal gate to cross.")

    if has_any(details, [
        "RUNTIME_EVIDENCE_MISSING",
        "RUNTIME_EVIDENCE_NOT_AVAILABLE",
        "runtimeEvidenceStatus=NOT_READY",
        "NOT_READY_ENABLED_FALSE",
    ]):
        classification["runtime_evidence_gap"].append("Live-runtime evidence is not available while execution flags remain disabled; collect dry-run evidence before enabling.")

    if has_any(details, [
        "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES",
        "CONSECUTIVE_TINY_LIVE_LOSSES",
    ]):
        classification["risk_hard_stop"].append("Autonomous approval is blocked by consecutive tiny-live loss protection.")

    if current_blockers:
        execution_blockers = [item for item in current_blockers if item.endswith("_NOT_EXECUTION_ELIGIBLE")]
        if execution_blockers:
            classification["execution_disabled_guard"].extend(execution_blockers)
        if any(item in current_blockers for item in ["OKX_CREDENTIALS_NOT_SET", "MCP_KEY_MISSING", "ENV_FILE_MISSING"]):
            classification["security_or_secret_gap"].append("Required server secret material is missing.")
        if any(item.startswith("RUNTIME_LOG_SMOKE") or item in ["HEALTH_NOT_UP", "EVENT_RISK_NOT_R0"] for item in current_blockers):
            classification["runtime_health_gap"].extend([item for item in current_blockers if item.startswith("RUNTIME_LOG_SMOKE") or item in ["HEALTH_NOT_UP", "EVENT_RISK_NOT_R0"]])

    if has_any(details, [
        "NO_PRE_POSITION_NOTIONAL",
        "NO_PROPOSED_PRE_POSITION_NOTIONAL",
        "MIN_ORDER",
        "capacityBlockers",
    ]):
        classification["capacity_not_primary"].append("Capacity/notional blockers are secondary until market readiness and risk hard stops clear.")

    background_markers = [item for item in background_flags if item]
    if background_markers or any("BACKGROUND_AUTOMATION_ALREADY_TRUE" in item or "BACKGROUND_AUTOMATION_MISSING_FLAG" in item for item in current_warnings):
        classification["background_automation_review"].extend(background_markers)

    return {key: value for key, value in classification.items() if value}

def live_readiness_next_actions(classification):
    actions = []
    if "risk_hard_stop" in classification:
        actions.append("Do not enable live trading; first review tiny-live loss protection root cause and require fresh dry-run proof.")
    if "market_condition_wait" in classification:
        actions.append("Keep observing read-only signal/MCP evidence until a current BUY/add candidate is present.")
    if "runtime_evidence_gap" in classification:
        actions.append("Keep execution disabled and gather runtime evidence from dry-run/autonomous readiness surfaces.")
    if "execution_disabled_guard" in classification:
        actions.append("Treat disabled execution flags as intentional guards; only change them in a separate explicitly authorized env-change plan.")
    if "background_automation_review" in classification:
        actions.append("Review already-enabled background automation before any live scope expansion.")
    if "security_or_secret_gap" in classification:
        actions.append("Fix missing server secret prerequisites before operator review.")
    if "runtime_health_gap" in classification:
        runtime_gaps = classification["runtime_health_gap"]
        gap_labels = []
        if "HEALTH_NOT_UP" in runtime_gaps:
            gap_labels.append("health")
        if any(item.startswith("RUNTIME_LOG_SMOKE") for item in runtime_gaps):
            gap_labels.append("runtime log")
        if "EVENT_RISK_NOT_R0" in runtime_gaps:
            gap_labels.append("event-risk baseline")
        actions.append("Fix " + "/".join(gap_labels or ["runtime health"]) + " evidence before any live operator review.")
    if not actions:
        actions.append("No automated blocker class found; operator review is still required before any live env change.")
    return actions

print("[live-readiness] read-only server audit")
print(f"url={url}")
print(f"symbol={symbol}")
print(f"commit={open('app.commit', encoding='utf-8').read().strip()}")
print(f"port={open('app.port', encoding='utf-8').read().strip()}")
print(f"live_authorized={str(live_authorized).lower()}")

health = urllib.request.urlopen(f"http://127.0.0.1:{os.environ['PORT']}/api/actuator/health", timeout=10).read().decode("utf-8", "replace")
print(f"health={health}")
if '"UP"' not in health:
    blockers.append("HEALTH_NOT_UP")

local_tv_execution_mode = (read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MODE") or "LEGACY").strip().upper().replace("-", "_")
if local_tv_execution_mode not in ("LEGACY", "OFF", "DRY_RUN", "LIVE_MICRO"):
    blockers.append("LOCAL_TRADINGVIEW_EXECUTION_MODE_INVALID")

local_tv_execution_enabled = (
    local_tv_execution_mode in ("DRY_RUN", "LIVE_MICRO")
    or (local_tv_execution_mode == "LEGACY" and bool_env("TRADINGVIEW_LOCAL_EXECUTION_ENABLED", False))
)
local_tv_execution_dry_run = (
    False if local_tv_execution_mode == "LIVE_MICRO"
    else True if local_tv_execution_mode in ("OFF", "DRY_RUN")
    else bool_env("TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN", True)
)
local_tv_live_order_enabled = (
    local_tv_execution_mode == "LIVE_MICRO"
    or (local_tv_execution_mode == "LEGACY" and bool_env("TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED", False))
)

order_flags = {
    "TRADING_OKX_ENABLED": bool_env("TRADING_OKX_ENABLED", False),
    "TRADING_OCO_POLLER_ENABLED": bool_env("TRADING_OCO_POLLER_ENABLED", False),
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED": bool_env("TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED", False),
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED": bool_env("TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED", False),
    "TRADINGVIEW_LOCAL_EXECUTION_ENABLED": local_tv_execution_enabled,
    "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED": local_tv_live_order_enabled,
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
    "TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN": local_tv_execution_dry_run,
    "TRAILING_STOP_DRY_RUN": bool_env("TRAILING_STOP_DRY_RUN", True),
    "POSITION_EXIT_MANAGER_DRY_RUN": bool_env("POSITION_EXIT_MANAGER_DRY_RUN", True),
}
local_tradingview_flags = {
    "TRADING_SIGNAL_SOURCE_PRIMARY": read_env_key("TRADING_SIGNAL_SOURCE_PRIMARY") or "TRADINGVIEW",
    "TRADINGVIEW_LOCAL_ENABLED": bool_env("TRADINGVIEW_LOCAL_ENABLED", False),
    "TRADINGVIEW_LOCAL_EXECUTION_MODE": local_tv_execution_mode,
    "TRADINGVIEW_LOCAL_CATCH_UP_BARS": read_env_key("TRADINGVIEW_LOCAL_CATCH_UP_BARS") or "3",
    "TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS": read_env_key("TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS") or "72",
    "TRADINGVIEW_LOCAL_EXECUTION_ENABLED": local_tv_execution_enabled,
    "TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN": local_tv_execution_dry_run,
    "TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED": local_tv_live_order_enabled,
    "TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_BAR": read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_BAR") or "3",
    "TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_DAY": read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_DAY") or "1",
    "TRADINGVIEW_LOCAL_EXECUTION_MAX_OPEN_POSITIONS": read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MAX_OPEN_POSITIONS") or "1",
}
background_flags = [
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_DAILY_TG_REPORT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED",
]
background_true = []
background_missing = []
for key in background_flags:
    if bool_env(key, False):
        background_true.append(key)
    if not read_env_key(key):
        background_missing.append(key)

true_order_flags = [key for key, value in order_flags.items() if value]
live_authorized_order_flags = ["TRADING_OKX_ENABLED", "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED"]

print("order_capable_flags=" + json.dumps(order_flags, sort_keys=True))
print("order_capable_flags_true=" + json.dumps(true_order_flags))
print("dry_run_flags=" + json.dumps(dry_run_flags, sort_keys=True))
print("local_tradingview_flags=" + json.dumps(local_tradingview_flags, sort_keys=True))
print("background_automation_true=" + json.dumps(background_true))
print("missing_background_automation_flags=" + json.dumps(background_missing))
print("secret_presence=" + json.dumps({
    "TRADING_OKX_API_KEY": secret_presence("TRADING_OKX_API_KEY"),
    "TRADING_OKX_SECRET_KEY": secret_presence("TRADING_OKX_SECRET_KEY"),
    "TRADING_OKX_PASSPHRASE": secret_presence("TRADING_OKX_PASSPHRASE"),
    "TELEGRAM_BOT_TOKEN": secret_presence("TELEGRAM_BOT_TOKEN"),
    "MCP_GUARDIAN_KEY": secret_presence("MCP_GUARDIAN_KEY"),
}, sort_keys=True))

if secret_presence("TRADING_OKX_API_KEY") != "SET" or secret_presence("TRADING_OKX_SECRET_KEY") != "SET" or secret_presence("TRADING_OKX_PASSPHRASE") != "SET":
    blockers.append("OKX_CREDENTIALS_NOT_SET")

if true_order_flags:
    unexpected_order_flags = true_order_flags
    if live_authorized:
        unexpected_order_flags = [key for key in true_order_flags if key not in live_authorized_order_flags]
        authorized_true = [key for key in true_order_flags if key in live_authorized_order_flags]
        if authorized_true:
            warnings.append("LIVE_AUTHORIZED_ORDER_FLAGS_TRUE:" + ",".join(authorized_true))
    if unexpected_order_flags:
        blockers.append("ORDER_CAPABLE_FLAGS_ALREADY_TRUE:" + ",".join(unexpected_order_flags))
        warnings.append("ORDER_CAPABLE_FLAGS_ALREADY_TRUE_REVIEW_BEFORE_LIVE")

if background_true:
    warnings.append("BACKGROUND_AUTOMATION_ALREADY_TRUE_REVIEW_BEFORE_LIVE")
if background_missing:
    warnings.append("BACKGROUND_AUTOMATION_MISSING_FLAG_REVIEW_BEFORE_LIVE:" + ",".join(background_missing))

tiny = call_tool("getTinyLiveAutoExecutionTriggerStatus", {"symbol": symbol})
readiness_details["tinyLive"] = tiny_details(tiny)
opportunity = call_tool("validateAutonomousOpportunityReadiness", {"symbol": symbol})
readiness_details["autonomousOpportunity"] = opportunity_details(opportunity)
require_contains("tiny", tiny, r"boundary:\s*READ_ONLY", "TINY_STATUS_BOUNDARY_MISSING")
require_contains("tiny", tiny, r"orderSent=false", "TINY_ORDER_SENT_MARKER_MISSING")
if live_authorized:
    require_contains("tiny", tiny, r"triggerEnabled=true", "TINY_TRIGGER_NOT_ENABLED_IN_LIVE_AUTHORIZED_MODE")
    require_contains("tiny", tiny, r"triggerDryRun=false", "TINY_DRY_RUN_NOT_FALSE_IN_LIVE_AUTHORIZED_MODE")
    require_contains("tiny", tiny, r"hardScope=BTCUSDT/574/LONG/5USDT", "TINY_HARD_SCOPE_MARKER_MISSING")
else:
    require_contains("tiny", tiny, r"triggerEnabled=false", "TINY_TRIGGER_ALREADY_ENABLED_OR_MARKER_MISSING")
    require_contains("tiny", tiny, r"triggerDryRun=true", "TINY_DRY_RUN_NOT_TRUE")
if "executionEligible=true" not in tiny and not (live_authorized and "NO_CURRENT_BUY_CANDIDATE" in tiny):
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
    if not live_authorized and '"executionEligible" : true' not in text and '"executionEligible":true' not in text:
        blockers.append(f"{name}_NOT_EXECUTION_ELIGIBLE")

trailing = call_tool("getTrailingStopStatus")
require_contains("trailing", trailing, r"global\.enabled:\s*false", "TRAILING_ALREADY_ENABLED_OR_MARKER_MISSING")
require_contains("trailing", trailing, r"global\.dryRun:\s*true", "TRAILING_DRY_RUN_NOT_TRUE")
if "open_oco_positions: 0" in trailing:
    warnings.append("NO_OPEN_OCO_POSITIONS_FOR_TRAILING_DRY_RUN_OBSERVATION")

event = call_tool("getEventRiskControlStatus", {"symbol": symbol})
require_contains("event risk", event, r"boundary=READ_ONLY", "EVENT_RISK_BOUNDARY_MISSING")
require_contains("event risk", event, r"operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION", "EVENT_RISK_OPERATOR_CONTROL_MARKER_MISSING")
event_risk_level = regex_value(event, r"riskLevel=([A-Z0-9_]+)", "MISSING")
print("riskLevel=" + event_risk_level)
if "riskLevel=R0" not in event:
    blockers.append("EVENT_RISK_NOT_R0")

guardian = call_tool("getGuardianSnapshot", {"symbol": symbol})
require_contains("guardian", guardian, r'"writeMode"\s*:\s*false', "GUARDIAN_WRITE_MODE_NOT_FALSE")
require_contains("guardian", guardian, r'"liveActionsExecuted"\s*:\s*false', "GUARDIAN_LIVE_ACTIONS_EXECUTED")

try:
    strict_log_env = dict(os.environ)
    strict_log_env["ALLOW_UNKNOWN_WARN"] = "0"
    strict_log_env["ALLOW_RUNTIME_ERROR"] = "0"
    strict_log_env["ALLOW_HIGH_RISK_LOG"] = "1" if live_authorized else "0"
    checker_body = base64.b64decode(os.environ["RUNTIME_LOG_CHECKER_B64"]).decode("utf-8")
    checker_path = ""
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, prefix="runtime-log-checker-", suffix=".sh") as handle:
            handle.write(checker_body)
            checker_path = handle.name
        log = subprocess.run(["bash", checker_path], cwd=os.environ["APP_DIR"], env=strict_log_env, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    finally:
        if checker_path:
            try:
                os.unlink(checker_path)
            except OSError:
                pass
    print("runtime_log_status=PASS" if log.returncode == 0 else "runtime_log_status=FAIL")
    for line in log.stdout.splitlines()[-12:]:
        print("runtime_log: " + line)
    if log.returncode != 0:
        blockers.append("RUNTIME_LOG_SMOKE_FAILED")
except Exception as exc:
    blockers.append("RUNTIME_LOG_SMOKE_EXCEPTION")
    print(f"runtime_log_exception={type(exc).__name__}:{exc}")

missing_readiness_fields = missing_readiness_detail_fields(readiness_details)
if missing_readiness_fields:
    blockers.append("READINESS_DETAILS_MISSING_FIELDS")
background_review_flags = background_true + [f"MISSING:{key}" for key in background_missing]
blocker_classification = classify_live_readiness(readiness_details, blockers, warnings, background_review_flags)
next_actions = live_readiness_next_actions(blocker_classification)
print("missing_readiness_detail_fields=" + json.dumps(missing_readiness_fields))
print("readiness_details=" + json.dumps(readiness_details, ensure_ascii=False, sort_keys=True))
print("blocker_classification=" + json.dumps(blocker_classification, ensure_ascii=False, sort_keys=True))
print("next_actions=" + json.dumps(next_actions, ensure_ascii=False))
print("warnings=" + json.dumps(warnings))
print("blockers=" + json.dumps(blockers))
if blockers:
    print("verdict=NOT_READY")
elif live_authorized:
    print("verdict=LIVE_AUTHORIZED_MONITORING")
else:
    print("verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED")
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "live readiness audit failed with exit code $LASTEXITCODE"
}
