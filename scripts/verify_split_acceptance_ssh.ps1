param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$TradingAppDir = "/home/ubuntu/agora-trading-api",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$PublicTradingHealthUrl = "https://agoratradingapi.purrtechllc.com/api/actuator/health",
    [string]$PublicTradingMcpBlockedUrl = "https://agoratradingapi.purrtechllc.com/api/mcp",
    [string]$PublicTradingContextMcpBlockedUrl = "https://agoramarketapi.purrtechllc.com/api/trading/mcp",
    [switch]$SkipSchemaCompare,
    [switch]$SkipGitCurrent,
    [switch]$SkipRuntimeLog
)

$ErrorActionPreference = "Stop"

$serverVerify = Join-Path $PSScriptRoot "verify_server_ssh.ps1"
$ownershipSmoke = Join-Path $AgoraMarketApiToolsDir "check-live-mcp-split-ownership.ps1"

if (-not (Test-Path -LiteralPath $serverVerify)) {
    throw "Server verifier not found: $serverVerify"
}

if (-not (Test-Path -LiteralPath $ownershipSmoke)) {
    throw "Cross-service MCP ownership smoke not found: $ownershipSmoke"
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-RemotePathSafe -Name "TradingAppDir" -Value $TradingAppDir

Write-Host "[split-acceptance] trading server verification"
$serverArgs = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $TradingAppDir
    PublicTradingHealthUrl = $PublicTradingHealthUrl
    PublicTradingMcpBlockedUrl = $PublicTradingMcpBlockedUrl
    PublicTradingContextMcpBlockedUrl = $PublicTradingContextMcpBlockedUrl
}
if (-not $SkipSchemaCompare) {
    $serverArgs.SchemaCompare = $true
}
if ($SkipGitCurrent) {
    $serverArgs.SkipGitCurrent = $true
}
& $serverVerify @serverArgs

if (-not $SkipRuntimeLog) {
    Write-Host ""
    Write-Host "[split-acceptance] trading runtime log smoke"
    $remoteScript = @"
set -euo pipefail
cd '$TradingAppDir'
bash scripts/check_server_runtime_log.sh
"@
    $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "runtime log smoke failed with exit code $LASTEXITCODE"
    }
}

Write-Host ""
Write-Host "[split-acceptance] cross-service live MCP ownership"
& $ownershipSmoke -HostName $SshHost -SshKey $SshKey -TradingRemoteDir $TradingAppDir

Write-Host ""
Write-Host "[split-acceptance] OK"
