param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$TradingAppDir = "/home/ubuntu/agora-trading-api",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$PublicTradingHealthUrl = "https://agoratradingapi.purrtechllc.com/api/actuator/health",
    [string]$PublicTradingMcpUrl = "https://agoratradingapi.purrtechllc.com/api/mcp",
    [switch]$SkipSchemaCompare,
    [switch]$SkipGitCurrent
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

Write-Host "[split-acceptance] trading server verification"
$serverArgs = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $TradingAppDir
    PublicTradingHealthUrl = $PublicTradingHealthUrl
    PublicTradingMcpUrl = $PublicTradingMcpUrl
}
if (-not $SkipSchemaCompare) {
    $serverArgs.SchemaCompare = $true
}
if ($SkipGitCurrent) {
    $serverArgs.SkipGitCurrent = $true
}
& $serverVerify @serverArgs

Write-Host ""
Write-Host "[split-acceptance] cross-service live MCP ownership"
& $ownershipSmoke -HostName $SshHost -SshKey $SshKey -TradingRemoteDir $TradingAppDir

Write-Host ""
Write-Host "[split-acceptance] OK"
