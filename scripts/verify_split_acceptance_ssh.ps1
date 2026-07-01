param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$TradingAppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$PublicTradingHealthUrl = "https://agoratradingapi.purrtechllc.com/api/actuator/health",
    [string]$PublicTradingMcpUrl = "https://agoratradingapi.purrtechllc.com/api/mcp",
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

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "TradingAppDir" -Value $TradingAppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile

Write-Host "[split-acceptance] trading server verification"
$serverArgs = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $TradingAppDir
    EnvFile = $EnvFile
    PublicTradingHealthUrl = $PublicTradingHealthUrl
    PublicTradingMcpUrl = $PublicTradingMcpUrl
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
    $runtimeLogScript = Join-Path $PSScriptRoot "check_server_runtime_log.sh"
    if (-not (Test-Path -LiteralPath $runtimeLogScript)) {
        throw "Runtime log checker not found: $runtimeLogScript"
    }
    $runtimeLogBody = Get-Content -Raw -LiteralPath $runtimeLogScript
    $remoteRuntimeCommand = "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | APP_DIR='$TradingAppDir' ALLOW_UNKNOWN_WARN=0 ALLOW_RUNTIME_ERROR=0 ALLOW_HIGH_RISK_LOG=0 bash -s"
    $runtimeLogBody | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost $remoteRuntimeCommand
    if ($LASTEXITCODE -ne 0) {
        throw "runtime log smoke failed with exit code $LASTEXITCODE"
    }
}

Write-Host ""
Write-Host "[split-acceptance] cross-service live MCP ownership"
& $ownershipSmoke -HostName $SshHost -SshKey $SshKey -TradingRemoteDir $TradingAppDir

Write-Host ""
Write-Host "[split-acceptance] OK"
