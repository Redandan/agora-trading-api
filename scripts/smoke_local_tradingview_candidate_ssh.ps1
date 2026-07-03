param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [long]$StrategyId = 485,
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1d",
    [int]$Days = 90,
    [string]$Source = "okx",
    [switch]$RequireCurrentCandidate,
    [switch]$RequireDryRunArmed
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

if ($Days -lt 7 -or $Days -gt 730) {
    throw "Days must be between 7 and 730."
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
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16
Assert-McpSmokeTokenSafe -Name "Source" -Value $Source -MaxLength 32

$remoteScriptTemplate = @'
set -euo pipefail
cd '__APP_DIR__'

PORT=$(cat app.port)
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENV_FILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY ENV_FILE='__ENV_FILE__' STRATEGY_ID='__STRATEGY_ID__' SYMBOL='__SYMBOL__' INTERVAL_CODE='__INTERVAL_CODE__' DAYS='__DAYS__' SOURCE='__SOURCE__' REQUIRE_CURRENT='__REQUIRE_CURRENT__' REQUIRE_DRY_RUN_ARMED='__REQUIRE_DRY_RUN_ARMED__'
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
env_file = os.environ["ENV_FILE"]
strategy_id = int(os.environ["STRATEGY_ID"])
symbol = os.environ["SYMBOL"].upper()
interval_code = os.environ["INTERVAL_CODE"].lower()
days = int(os.environ["DAYS"])
source = os.environ["SOURCE"].lower()
require_current = os.environ.get("REQUIRE_CURRENT", "").lower() == "true"
require_dry_run_armed = os.environ.get("REQUIRE_DRY_RUN_ARMED", "").lower() == "true"

def read_env_key(key, default=""):
    try:
        with open(env_file, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == key:
                    return v.strip().strip('"').strip("'")
    except FileNotFoundError:
        return "MISSING_ENV_FILE"
    return default

def bool_env(key, default=False):
    value = read_env_key(key, "true" if default else "false").strip().lower()
    return value in ("1", "true", "yes", "y", "on")

def call_tool(name, arguments=None, timeout=180):
    body = {
        "jsonrpc": "2.0",
        "id": f"local-tv-candidate-{name}",
        "method": "tools/call",
        "params": {
            "name": name,
            "arguments": arguments or {},
        },
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    data = json.loads(raw)
    if data.get("error"):
        raise RuntimeError(data["error"])
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

def safe_search(description, pattern, text):
    try:
        return re.search(pattern, text, re.MULTILINE)
    except re.error as exc:
        print(f"FAIL: invalid regex for {description}; pattern={pattern!r}; error={exc}", file=sys.stderr)
        sys.exit(1)

def require(description, pattern, text):
    if not safe_search(description, pattern, text):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

def field(pattern, text, default="N/A"):
    match = safe_search(f"field {pattern}", pattern, text)
    return match.group(1).strip() if match else default

def to_int(value):
    try:
        return int(str(value).strip())
    except Exception:
        return None

def compact(text, limit=900):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

print("[local-tradingview-candidate] read-only server-local MCP smoke")
print("scope=READ_ONLY; calls previewScoreBuyTradingViewOrders and runScoreBuyTradingViewParityBacktest only; no DB write, env change, order, OCO, grid, fund, Earn, Telegram, scheduler, or exchange mutation.")
print(f"url={url} strategyId={strategy_id} symbol={symbol} intervalCode={interval_code} days={days} source={source}")

args = {
    "strategyId": strategy_id,
    "symbol": symbol,
    "intervalCode": interval_code,
    "days": days,
    "source": source,
    "limit": 20,
}
preview = call_tool("previewScoreBuyTradingViewOrders", args)
backtest_args = dict(args)
backtest_args["feeRate"] = 0.001
backtest_args["limit"] = 10
backtest = call_tool("runScoreBuyTradingViewParityBacktest", backtest_args)

require("preview heading", r"SCORE_BUY TradingView order-intent preview", preview)
require("backtest heading", r"SCORE_BUY TradingView parity backtest", backtest)
require("backtest non-persistence marker", r"bt_backtest_result", backtest)

data_end = field(r"dataEnd=([0-9T:\-]+)", preview)
coverage = field(r"coverage=([^ \r\n]+)", preview)
coverage_warning = field(r"coverageWarning=([^ \r\n]+)", preview)
trailing_gap_hours = field(r"trailingGapHours=([0-9]+)", preview)
order_bars = field(r"orderBars=([0-9]+)", preview)
order_intents = field(r"orderIntents=([0-9]+)", preview)
first_order_at = field(r"firstOrderAt=([^ \r\n]+)", preview)
last_order_at = field(r"lastOrderAt=([^ \r\n]+)", preview)
final_mark = field(r"finalMark=([0-9T:\-]+)", backtest)
net_pnl = field(r"netPnl:\s*([-+0-9.]+) USDT", backtest)
total_return = field(r"totalReturn:\s*([-+0-9.]+%)", backtest)

order_intent_count = to_int(order_intents) or 0
if order_intent_count <= 0:
    current_status = "NO_TRADINGVIEW_ORDER_INTENTS"
elif last_order_at == data_end and data_end != "N/A":
    current_status = "HAS_CURRENT_BUY_CANDIDATE"
else:
    current_status = "NO_CURRENT_BUY_CANDIDATE_RECENT_INTENTS"

primary = read_env_key("TRADING_SIGNAL_SOURCE_PRIMARY", "TRADINGVIEW").strip().upper().replace("-", "_")
local_enabled = bool_env("TRADINGVIEW_LOCAL_ENABLED", False)
max_signal_age_hours = read_env_key("TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS", "72")
mode = read_env_key("TRADINGVIEW_LOCAL_EXECUTION_MODE", "LEGACY").strip().upper().replace("-", "_")
legacy_execution_enabled = bool_env("TRADINGVIEW_LOCAL_EXECUTION_ENABLED", False)
legacy_execution_dry_run = bool_env("TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN", True)
legacy_live_order_enabled = bool_env("TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED", False)

if mode == "OFF":
    effective_execution_enabled = False
    effective_execution_dry_run = True
    effective_live_order_enabled = False
elif mode == "DRY_RUN":
    effective_execution_enabled = True
    effective_execution_dry_run = True
    effective_live_order_enabled = False
elif mode == "LIVE_MICRO":
    effective_execution_enabled = True
    effective_execution_dry_run = False
    effective_live_order_enabled = True
else:
    mode = "LEGACY"
    effective_execution_enabled = legacy_execution_enabled
    effective_execution_dry_run = legacy_execution_dry_run
    effective_live_order_enabled = legacy_live_order_enabled

local_evaluator_active = primary == "LOCAL_TRADINGVIEW" and local_enabled
dry_run_armed = local_evaluator_active and effective_execution_enabled and effective_execution_dry_run and not effective_live_order_enabled

blockers = []
if current_status != "HAS_CURRENT_BUY_CANDIDATE":
    blockers.append("LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
if not local_evaluator_active:
    blockers.append("LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE")
if not dry_run_armed:
    blockers.append("LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED")
if coverage not in ("OK", "WARN"):
    blockers.append("LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK")

print("")
print("Current Candidate:")
print(f"  currentCandidateStatus={current_status}")
print(f"  dataEnd={data_end}")
print(f"  lastOrderAt={last_order_at}")
print(f"  firstOrderAt={first_order_at}")
print(f"  orderBars={order_bars}")
print(f"  orderIntents={order_intents}")
print(f"  coverage={coverage}")
print(f"  trailingGapHours={trailing_gap_hours}")
print(f"  coverageWarning={coverage_warning}")

print("")
print("Local TradingView Execution Guards:")
print(f"  primary={primary}")
print(f"  localEnabled={str(local_enabled).lower()}")
print(f"  executionMode={mode}")
print(f"  maxSignalAgeHours={max_signal_age_hours}")
print(f"  effectiveExecutionEnabled={str(effective_execution_enabled).lower()}")
print(f"  effectiveExecutionDryRun={str(effective_execution_dry_run).lower()}")
print(f"  effectiveLiveOrderEnabled={str(effective_live_order_enabled).lower()}")
print(f"  localTradingViewEvaluatorActive={str(local_evaluator_active).lower()}")
print(f"  localTradingViewExecutionDryRunArmed={str(dry_run_armed).lower()}")
print("  orderSentAllowed=false")
print("  liveOrderMutationAllowed=false")

print("")
print("Parity Backtest Summary:")
print(f"  finalMark={final_mark}")
print(f"  netPnlUsdt={net_pnl}")
print(f"  totalReturn={total_return}")

print("")
print("Blocker Classification:")
print("  local_tradingview_blockers=" + json.dumps(blockers))
if current_status == "HAS_CURRENT_BUY_CANDIDATE" and dry_run_armed:
    readiness = "READY_FOR_LOCAL_TRADINGVIEW_DRY_RUN_OBSERVATION_NOT_LIVE"
elif current_status == "HAS_CURRENT_BUY_CANDIDATE":
    readiness = "BLOCKED_LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED"
else:
    readiness = "WAIT_CURRENT_LOCAL_TRADINGVIEW_BUY_CANDIDATE"
print(f"  localTradingViewReadiness={readiness}")
print("  nextAction=" + ("Wait for the latest closed bar to emit a TradingView parity BUY intent, then rerun this smoke before any live plan." if current_status != "HAS_CURRENT_BUY_CANDIDATE" else "Review DRY_RUN evidence only; this smoke is not live approval."))

print("")
print("Preview Sample:")
print("  " + compact(preview))

if require_current and current_status != "HAS_CURRENT_BUY_CANDIDATE":
    print("FAIL: current TradingView parity BUY candidate is required but not present.", file=sys.stderr)
    sys.exit(2)
if require_dry_run_armed and not dry_run_armed:
    print("FAIL: Local TradingView dry-run execution receipt path is required but not armed.", file=sys.stderr)
    sys.exit(3)

print("[local-tradingview-candidate] OK read-only check complete")
PY
'@

$remoteScript = $remoteScriptTemplate
$remoteScript = $remoteScript.Replace("__APP_DIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENV_FILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__STRATEGY_ID__", [string]$StrategyId)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__INTERVAL_CODE__", $IntervalCode)
$remoteScript = $remoteScript.Replace("__DAYS__", [string]$Days)
$remoteScript = $remoteScript.Replace("__SOURCE__", $Source)
$remoteScript = $remoteScript.Replace("__REQUIRE_CURRENT__", $RequireCurrentCandidate.IsPresent.ToString())
$remoteScript = $remoteScript.Replace("__REQUIRE_DRY_RUN_ARMED__", $RequireDryRunArmed.IsPresent.ToString())

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "local TradingView candidate SSH smoke failed with exit code $LASTEXITCODE"
}
