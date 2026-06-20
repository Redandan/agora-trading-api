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

true_flags = [key for key in background_flags if bool_value(values, key)]
high_risk_true = [key for key in high_risk_flags if bool_value(values, key)]
missing_flags = [key for key in background_flags if not has_key(values, key)]
false_flags = [key for key in background_flags if has_key(values, key) and not bool_value(values, key)]
background_blockers = []
if high_risk_true:
    background_blockers.append("HIGH_RISK_BACKGROUND_AUTOMATION_TRUE")
if [key for key in true_flags if key not in high_risk_flags]:
    background_blockers.append("BACKGROUND_AUTOMATION_TRUE")
if missing_flags:
    background_blockers.append("MISSING_BACKGROUND_AUTOMATION_FLAG")
background_clear = not true_flags and not missing_flags

print("[live-background-automation] read-only server env smoke")
print(f"commit={git_commit()}")
print(f"port={text_file('app.port')}")
print("scope=READ_ONLY; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, or external backfill/import state changed.")
print("background_automation_flags=" + json.dumps({key: bool_value(values, key) for key in background_flags}, sort_keys=True))
print("background_automation_true=" + json.dumps(true_flags))
print("high_risk_background_automation_true=" + json.dumps(high_risk_true))
print("background_automation_false=" + json.dumps(false_flags))
print("missing_background_automation_flags=" + json.dumps(missing_flags))
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
    print("classification=BACKGROUND_AUTOMATION_CLEARED")
    print("verdict=OK_BACKGROUND_AUTOMATION_DISABLED")

print("[live-background-automation] read-only check complete")
if require_clear and not background_clear:
    raise SystemExit(2)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
