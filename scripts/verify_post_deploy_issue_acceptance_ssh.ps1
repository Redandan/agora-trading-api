param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$TradingAppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [int]$TrailingDays = 30,
    [int]$TrailingLimit = 100,
    [int]$SignalExecutionDays = 5,
    [int]$SignalBlockedDays = 7,
    [int]$SignalAccuracyDays = 14,
    [switch]$RequireTrailingAcceptance,
    [switch]$SkipSplitAcceptance
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($SkipSplitAcceptance -and $RequireTrailingAcceptance) {
    throw "-RequireTrailingAcceptance is issue-closure evidence and cannot be combined with -SkipSplitAcceptance."
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-RemotePathSafe -Name "TradingAppDir" -Value $TradingAppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 21

$splitAcceptance = Join-Path $PSScriptRoot "verify_split_acceptance_ssh.ps1"
$mcpParitySmoke = Join-Path $PSScriptRoot "smoke_mcp_parity_ssh.ps1"
$guardrailSmoke = Join-Path $PSScriptRoot "smoke_guardrail_acceptance_ssh.ps1"
$signalCorrectnessSmoke = Join-Path $PSScriptRoot "smoke_signal_correctness_ssh.ps1"
$trailingSmoke = Join-Path $PSScriptRoot "smoke_trailing_stop_pnl_replay_ssh.ps1"

foreach ($script in @($mcpParitySmoke, $guardrailSmoke, $signalCorrectnessSmoke, $trailingSmoke)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Required smoke script not found: $script"
    }
}

if (-not $SkipSplitAcceptance -and -not (Test-Path -LiteralPath $splitAcceptance)) {
    throw "Split acceptance verifier not found: $splitAcceptance"
}

Write-Host "[issue-acceptance] read-only post-deploy issue acceptance"
Write-Host "[issue-acceptance] symbol=$Symbol interval=$IntervalCode trailingDays=$TrailingDays trailingLimit=$TrailingLimit requireTrailingAcceptance=$($RequireTrailingAcceptance.IsPresent)"
Write-Host "[issue-acceptance] signalExecutionDays=$SignalExecutionDays signalBlockedDays=$SignalBlockedDays signalAccuracyDays=$SignalAccuracyDays"
if ($SkipSplitAcceptance) {
    Write-Warning "[issue-acceptance] DIAGNOSTIC_ONLY: -SkipSplitAcceptance omits full split acceptance and must not be used as #1/#2/#3 closure evidence."
}

if (-not $SkipSplitAcceptance) {
    Write-Host ""
    Write-Host "[issue-acceptance] split acceptance"
    & $splitAcceptance -SshHost $SshHost -SshKey $SshKey -TradingAppDir $TradingAppDir
}

Write-Host ""
Write-Host "[issue-acceptance] reusable MCP parity smoke"
& $mcpParitySmoke -SshHost $SshHost -SshKey $SshKey -AppDir $TradingAppDir -EnvFile $EnvFile -Symbol $Symbol -IntervalCode $IntervalCode

Write-Host ""
Write-Host "[issue-acceptance] #1/#2 guardrail MCP smoke"
& $guardrailSmoke -SshHost $SshHost -SshKey $SshKey -AppDir $TradingAppDir -EnvFile $EnvFile -Symbol $Symbol -RequireNoReviewGaps

Write-Host ""
Write-Host "[issue-acceptance] signal-correctness MCP smoke"
& $signalCorrectnessSmoke -SshHost $SshHost -SshKey $SshKey -AppDir $TradingAppDir -EnvFile $EnvFile -Symbol $Symbol -ExecutionDays $SignalExecutionDays -BlockedDays $SignalBlockedDays -AccuracyDays $SignalAccuracyDays

Write-Host ""
Write-Host "[issue-acceptance] #3 trailing-stop PnL replay MCP smoke"
$trailingArgs = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $TradingAppDir
    EnvFile = $EnvFile
    Symbol = $Symbol
    IntervalCode = $IntervalCode
    Days = $TrailingDays
    Limit = $TrailingLimit
}
if ($RequireTrailingAcceptance) {
    $trailingArgs.RequireAcceptance = $true
}
& $trailingSmoke @trailingArgs

Write-Host ""
if ($SkipSplitAcceptance) {
    Write-Warning "[issue-acceptance] DIAGNOSTIC_ONLY OK: checks completed without full split acceptance; do not use this output as #1/#2/#3 closure evidence."
} else {
    Write-Host "[issue-acceptance] OK"
}
