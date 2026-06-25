param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72
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

if ($LookbackHours -lt 24 -or $LookbackHours -gt 336) {
    throw "LookbackHours must be between 24 and 336."
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
MCP_URL="http://127.0.0.1:${PORT}/api/mcp"
# Java runner uses the same server-local URL shape as the legacy Python smoke:
# url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
MCP_KEY=$(grep -E '^TRADING_MCP_KEY=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi
export MCP_KEY

mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.agora.smoke.McpSmokeCli \
  -Dexec.args="--suite grid-trend --mcp-url ${MCP_URL} --mcp-key-env MCP_KEY --symbol __SYMBOL__ --lookback-hours __LOOKBACK_HOURS__"
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__LOOKBACK_HOURS__", [string]$LookbackHours)

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid trend adjustment review smoke failed with exit code $LASTEXITCODE"
}
