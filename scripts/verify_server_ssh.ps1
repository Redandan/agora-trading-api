param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
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

$schemaFlag = if ($SchemaCompare) { "1" } else { "0" }
$gitFlag = if ($SkipGitCurrent) { "0" } else { "1" }

$remoteScript = @"
set -euo pipefail
cd '$AppDir'
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
