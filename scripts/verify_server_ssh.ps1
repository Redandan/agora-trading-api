param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$PublicTradingHealthUrl = "https://agoratradingapi.purrtechllc.com/api/actuator/health",
    [string]$PublicTradingMcpBlockedUrl = "https://agoratradingapi.purrtechllc.com/api/mcp",
    [string]$PublicTradingContextMcpBlockedUrl = "https://agoramarketapi.purrtechllc.com/api/trading/mcp",
    [switch]$SchemaCompare,
    [switch]$SkipGitCurrent
)

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

function Assert-PublicHttpsUrlSafe {
    param([string]$Name, [string]$Value)
    [System.Uri]$uri = $null
    if ([string]::IsNullOrWhiteSpace($Value) `
            -or -not [System.Uri]::TryCreate($Value, [System.UriKind]::Absolute, [ref]$uri) `
            -or $uri.Scheme -ne "https" `
            -or $uri.Host -notin @("agoratradingapi.purrtechllc.com", "agoramarketapi.purrtechllc.com") `
            -or $Value.IndexOf("'") -ge 0 `
            -or $Value -match "\s|``|\\") {
        throw "$Name must be a safe purrtechllc HTTPS URL for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-PublicHttpsUrlSafe -Name "PublicTradingHealthUrl" -Value $PublicTradingHealthUrl
Assert-PublicHttpsUrlSafe -Name "PublicTradingMcpBlockedUrl" -Value $PublicTradingMcpBlockedUrl
Assert-PublicHttpsUrlSafe -Name "PublicTradingContextMcpBlockedUrl" -Value $PublicTradingContextMcpBlockedUrl

$schemaFlag = if ($SchemaCompare) { "1" } else { "0" }
$gitFlag = if ($SkipGitCurrent) { "0" } else { "1" }

$remoteScript = @"
set -euo pipefail
cd '$AppDir'
ENV_FILE='$EnvFile' \
PUBLIC_TRADING_HEALTH_URL='$PublicTradingHealthUrl' \
PUBLIC_TRADING_MCP_BLOCKED_URL='$PublicTradingMcpBlockedUrl' \
PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL='$PublicTradingContextMcpBlockedUrl' \
RUN_SCHEMA_BASELINE_COMPARE='$schemaFlag' \
VERIFY_GIT_CURRENT='$gitFlag' \
bash scripts/verify_server.sh
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"

if ($LASTEXITCODE -ne 0) {
    throw "server verification failed with exit code $LASTEXITCODE"
}
