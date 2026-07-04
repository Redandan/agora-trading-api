param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

$remoteScript = @"
set -euo pipefail
APP_DIR='$AppDir'
ENV_FILE='$EnvFile'
REQUIRE_CLEAR='$($RequireClear.IsPresent)'
cd "`$APP_DIR"

export APP_DIR ENV_FILE REQUIRE_CLEAR
python3 - <<'PY'
import json
import os
import subprocess

app_dir = os.environ["APP_DIR"]
env_file = os.environ["ENV_FILE"]
require_clear = os.environ.get("REQUIRE_CLEAR", "").lower() == "true"

def read_env():
    values = {}
    with open(env_file, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = value.strip().strip(chr(34)).strip(chr(39))
    return values

def bool_value(values, key):
    return str(values.get(key, "")).lower() == "true"

def has_key(values, key):
    return key in values and str(values.get(key, "")).strip() != ""

def csv_values(value):
    return [item.strip().lower() for item in str(value or "").split(",") if item.strip()]

def text_file(path, default="UNKNOWN"):
    try:
        with open(os.path.join(app_dir, path), "r", encoding="utf-8") as handle:
            return handle.read().strip() or default
    except FileNotFoundError:
        return default

def git_commit():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=app_dir, text=True).strip()
    except Exception:
        return "UNKNOWN"

values = read_env()

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

high_risk_flags = [
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED",
]

flag_reviews = {
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED": {
        "riskCategory": "external-read",
        "concern": "Active provider probes can create network dependency noise during live review.",
        "requiredReview": "Decide whether external health probing belongs in the live-review baseline or must be disabled first.",
        "requiredEvidence": "Explicit server env false evidence plus a clean background automation smoke.",
        "nextAction": "Decide whether to keep provider probes out of the live-review baseline, then rerun the read-only smoke after any separately authorized env change.",
    },
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED": {
        "riskCategory": "external-backfill-import",
        "concern": "Backfill/import-capable MCP paths can write market-data or imported indicator rows when invoked.",
        "requiredReview": "Disable before live review unless a separate operator plan explicitly keeps external backfills available.",
        "requiredEvidence": "Explicit false evidence for external backfill/import capability before live review.",
        "nextAction": "Keep external backfill/import disabled for live review unless a separate operator plan explicitly authorizes it.",
    },
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED": {
        "riskCategory": "market-data-runtime",
        "concern": "Startup market WebSocket subscriptions can add provider/network side effects and noisy runtime warnings.",
        "requiredReview": "Confirm market WebSocket ownership and provider reachability, or disable for a quieter review baseline.",
        "requiredEvidence": "Explicit false evidence or a separate reviewed market-data ownership decision.",
        "nextAction": "Disable or separately justify startup WebSocket subscriptions before live review.",
    },
    "EVENT_SCAN_NOTIFICATION_ENABLED": {
        "riskCategory": "scheduler-notification",
        "concern": "Scheduled event-scan notification flow can produce outbound operator notifications.",
        "requiredReview": "Disable before live review unless scheduled event notifications are separately authorized.",
        "requiredEvidence": "Explicit false evidence for scheduled outbound event notifications.",
        "nextAction": "Keep scheduled event notifications disabled unless a separate Telegram/send authorization exists.",
    },
    "EXECUTION_EVENT_ENABLED": {
        "riskCategory": "scheduler-db-notification",
        "concern": "Execution-event scanning can evaluate normalized execution events and notification paths.",
        "requiredReview": "Disable before live review unless execution-event ownership and notification dry-run/send mode are approved.",
        "requiredEvidence": "Explicit false evidence or separate approval covering execution-event scheduler ownership and notification mode.",
        "nextAction": "Review execution-event DB/notification ownership before any live packet depends on this flag.",
    },
    "TRADING_DAILY_TG_REPORT_ENABLED": {
        "riskCategory": "telegram-report",
        "concern": "Daily report orchestration can send or prepare Trading-owned Telegram report work.",
        "requiredReview": "Confirm Telegram ownership and send mode, or disable before live review.",
        "requiredEvidence": "Explicit false evidence or separate approval for the daily Telegram report path.",
        "nextAction": "Keep report orchestration out of live review unless Telegram ownership is explicitly approved.",
    },
    "TRADING_AUTONOMOUS_DIGEST_ENABLED": {
        "riskCategory": "autonomous-digest",
        "concern": "Autonomous digest work can evaluate live trading context and produce operator digest output.",
        "requiredReview": "Review autonomous digest ownership before any live scope expansion.",
        "requiredEvidence": "Explicit false evidence or reviewed autonomous digest ownership.",
        "nextAction": "Disable or separately justify autonomous digest background work before live review.",
    },
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED": {
        "riskCategory": "telegram-send",
        "concern": "Autonomous digest Telegram path can send outbound messages.",
        "requiredReview": "Disable before live review unless Telegram-send behavior is separately authorized.",
        "requiredEvidence": "Explicit false evidence for autonomous digest Telegram send path.",
        "nextAction": "Keep Telegram send paths disabled until separately authorized.",
    },
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED": {
        "riskCategory": "telegram-send-db-update",
        "concern": "Live-signal retry notification can resend pending signal notifications and mark rows notified.",
        "requiredReview": "Disable before live review unless retry notification ownership and write behavior are separately authorized.",
        "requiredEvidence": "Explicit false evidence for live-signal retry notification resend and DB update behavior.",
        "nextAction": "Keep retry notifications disabled unless a separate operator plan accepts the resend/write behavior.",
    },
}

true_flags = [key for key in background_flags if bool_value(values, key)]
high_risk_true = [key for key in high_risk_flags if bool_value(values, key)]
missing_flags = [key for key in background_flags if not has_key(values, key)]
false_flags = [key for key in background_flags if has_key(values, key) and not bool_value(values, key)]
market_ws_providers = csv_values(values.get("MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS", ""))
local_tradingview_market_ws_accepted = (
    bool_value(values, "MARKET_WS_AUTO_SUBSCRIBE_ENABLED")
    and str(values.get("TRADING_SIGNAL_SOURCE_PRIMARY", "")).upper() == "LOCAL_TRADINGVIEW"
    and bool_value(values, "TRADINGVIEW_LOCAL_ENABLED")
    and not bool_value(values, "MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED")
    and market_ws_providers == ["okx"]
)
accepted_true_flags = ["MARKET_WS_AUTO_SUBSCRIBE_ENABLED"] if local_tradingview_market_ws_accepted else []
unreviewed_true_flags = [key for key in true_flags if key not in accepted_true_flags]
accepted_review_plan = []
if local_tradingview_market_ws_accepted:
    accepted_review_plan.append({
        "flag": "MARKET_WS_AUTO_SUBSCRIBE_ENABLED",
        "state": "ACCEPTED_TRUE_LOCAL_TRADINGVIEW_MARKET_DATA",
        "highRisk": False,
        "riskCategory": "market-data-runtime",
        "acceptedScope": "LOCAL_TRADINGVIEW_KLINE_CLOSE_EVENTS_OKX_ONLY",
        "requiredEvidence": "TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW, TRADINGVIEW_LOCAL_ENABLED=true, MARKET_WS_AUTO_SUBSCRIBE_PROVIDERS=okx, and MARKET_WS_AUTO_SUBSCRIBE_WARM_UP_ENABLED=false.",
        "nextAction": "Keep as the reviewed kline-close ingestion path for LOCAL_TRADINGVIEW; rerun candidate smoke after each latest closed bar.",
        "notAuthorization": "does not authorize external backfills, Telegram sends, scheduler mutation beyond market-data WebSocket subscription, order/OCO/grid/fund/Earn/exchange mutation, or keeping unrelated background flags true",
    })
review_plan = []
for key in unreviewed_true_flags + missing_flags:
    item = dict(flag_reviews.get(key, {}))
    item["flag"] = key
    item["state"] = "MISSING" if key in missing_flags else "TRUE"
    item["highRisk"] = key in high_risk_flags
    if not item.get("riskCategory"):
        item["riskCategory"] = "unclassified"
    if not item.get("concern"):
        item["concern"] = "Reviewed background automation flag requires explicit live-readiness classification."
    if not item.get("requiredReview"):
        item["requiredReview"] = "Classify before live review."
    if not item.get("requiredEvidence"):
        item["requiredEvidence"] = "Explicit false evidence or separate written authorization before live review."
    if not item.get("nextAction"):
        item["nextAction"] = "Classify and clear this background automation flag before live review."
    item["notAuthorization"] = "read-only review evidence only; does not authorize production env mutation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, or keeping a flag true"
    review_plan.append(item)
review_plan = accepted_review_plan + review_plan
background_blockers = []
if high_risk_true:
    background_blockers.append("HIGH_RISK_BACKGROUND_AUTOMATION_TRUE")
if [key for key in unreviewed_true_flags if key not in high_risk_flags]:
    background_blockers.append("BACKGROUND_AUTOMATION_TRUE")
if missing_flags:
    background_blockers.append("MISSING_BACKGROUND_AUTOMATION_FLAG")
background_clear = not unreviewed_true_flags and not missing_flags

print("[live-background-automation] read-only server env smoke")
print(f"commit={git_commit()}")
print(f"port={text_file('app.port')}")
print("scope=READ_ONLY; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, or external backfill/import state changed.")
print("background_automation_flags=" + json.dumps({key: bool_value(values, key) for key in background_flags}, sort_keys=True))
print("background_automation_true=" + json.dumps(true_flags))
print("background_automation_accepted_true=" + json.dumps(accepted_true_flags))
print("background_automation_unreviewed_true=" + json.dumps(unreviewed_true_flags))
print("high_risk_background_automation_true=" + json.dumps(high_risk_true))
print("background_automation_false=" + json.dumps(false_flags))
print("missing_background_automation_flags=" + json.dumps(missing_flags))
print("background_automation_review_plan=" + json.dumps(review_plan, sort_keys=True))
print("background_automation_blockers=" + json.dumps(background_blockers))
print(f"backgroundAutomationClear={str(background_clear).lower()}")

if not background_clear:
    print("classification=BACKGROUND_AUTOMATION_REVIEW_BEFORE_LIVE")
    print("recommendation=KEEP_LIVE_DISABLED_UNTIL_FLAGS_ARE_REVIEWED_OR_SEPARATELY_AUTHORIZED")
    if high_risk_true:
        print("blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE")
    if missing_flags:
        print("blocker=MISSING_BACKGROUND_AUTOMATION_FLAG")
    print("verdict=NOT_READY_BACKGROUND_AUTOMATION_REVIEW")
else:
    if accepted_true_flags:
        print("classification=BACKGROUND_AUTOMATION_REVIEWED_FOR_LOCAL_TRADINGVIEW")
        print("verdict=OK_BACKGROUND_AUTOMATION_REVIEWED")
    else:
        print("classification=BACKGROUND_AUTOMATION_CLEARED")
        print("verdict=OK_BACKGROUND_AUTOMATION_DISABLED")

print("[live-background-automation] read-only check complete")
if require_clear and not background_clear:
    raise SystemExit(2)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
