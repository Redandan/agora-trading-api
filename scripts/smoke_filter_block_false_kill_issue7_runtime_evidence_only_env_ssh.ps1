param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets"
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

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

ENV_FILE='__ENVFILE__'

read_bool_flag() {
  local key="$1"
  local raw
  raw="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 | sed -E "s/^[[:space:]]*${key}=//; s/[[:space:]]+#.*$//; s/^['\"]//; s/['\"]$//" || true)"
  case "$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')" in
    true) printf '%s=true\n' "$key" ;;
    false) printf '%s=false\n' "$key" ;;
    *) printf '%s=MISSING\n' "$key" ;;
  esac
}

recent_data_stale_skip_count() {
  local count="0"
  if [ -d logs ]; then
    count="$(find logs -maxdepth 2 -type f \( -name '*.log' -o -name '*.out' \) -mmin -4320 -print0 2>/dev/null \
      | xargs -0 grep -h 'DATA_STALE_SKIP\|DataFreshnessGuard' 2>/dev/null \
      | wc -l | tr -d ' ' || true)"
  fi
  if [ -z "$count" ]; then
    count="0"
  fi
  printf '%s\n' "$count"
}

echo "[issue7-runtime-evidence-only-env] read-only server env/log smoke"
echo "scope=READ_ONLY; reads git metadata, whitelisted boolean env flags, and recent app log markers only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
echo "commit=$(git rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
read_bool_flag TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED
read_bool_flag TRADING_OKX_ENABLED
read_bool_flag TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED
read_bool_flag EVENT_SCAN_NOTIFICATION_ENABLED
read_bool_flag EXECUTION_EVENT_ENABLED
read_bool_flag TRADING_OCO_POLLER_ENABLED
read_bool_flag MCP_GUARDIAN_LIVE_ACTIONS_ENABLED
read_bool_flag TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED
read_bool_flag TRADING_FUNDING_ARB_ENABLED
read_bool_flag OKX_EARN_TOPUP_ENABLED
echo "recent_data_stale_skip_count=$(recent_data_stale_skip_count)"
echo "notAuthorization=read-only issue #7 runtime evidence-only env smoke only; does not push, deploy, restart, reload nginx, change production env, close issue #7, relax DataFreshnessGuard, enable live/staged-add/TinyLive execution, enable scheduler, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir).Replace("__ENVFILE__", $EnvFile)

$output = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1
$exitCode = $LASTEXITCODE
$text = ($output | Out-String -Width 4096).Trim()
if (-not [string]::IsNullOrWhiteSpace($text)) {
    Write-Host $text
}
if ($exitCode -ne 0) {
    throw "issue #7 runtime evidence-only env smoke failed with exit code $exitCode"
}
