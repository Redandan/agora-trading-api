param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [switch]$RequireReviewReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid env diff preflight packet." }

$operatorScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$trendOverrideScript = Join-Path $PSScriptRoot "prepare_grid_trend_override_review_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $operatorScript)) { throw "Missing grid open operator packet script: $operatorScript" }
if (-not (Test-Path -LiteralPath $trendOverrideScript)) { throw "Missing grid trend override review packet script: $trendOverrideScript" }

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $operatorOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $operatorScript @commonArgs 2>&1
    $operatorExitCode = $LASTEXITCODE
    $trendOverrideOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $trendOverrideScript @commonArgs 2>&1
    $trendOverrideExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$operatorText = ($operatorOutput | Out-String -Width 8192)
$trendOverrideText = ($trendOverrideOutput | Out-String -Width 8192)
$operatorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $operatorText -Prefix "grid_open_operator_packet=")
$trendOverridePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $trendOverrideText -Prefix "grid_trend_override_review_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$reviewBlockers = [System.Collections.Generic.List[string]]::new()
$operatorAuthorizationRequired = [System.Collections.Generic.List[string]]::new()
if ($operatorExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open operator packet completed" }
if ($trendOverrideExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid trend override review packet completed" }
if ($null -eq $operatorPacket) { Add-Unique -List $missingEvidence -Value "grid_open_operator_packet valid JSON" }
if ($null -eq $trendOverridePacket) { Add-Unique -List $missingEvidence -Value "grid_trend_override_review_packet valid JSON" }

$envEnvelope = if ($null -ne $operatorPacket) { $operatorPacket.okxGridEnvPreflightEnvelope } else { $null }
$gateStatuses = if ($null -ne $operatorPacket) { $operatorPacket.gateStatuses } else { $null }
$credentialsReady = if ($null -ne $envEnvelope) { [bool]$envEnvelope.credentialsReady } else { $false }
$okxEnabled = if ($null -ne $envEnvelope) { [string]$envEnvelope.tradingOkxEnabled } else { "UNKNOWN" }
$gridEnabled = if ($null -ne $envEnvelope) { [string]$envEnvelope.tradingGridEnabled } else { "UNKNOWN" }
$schedulerEnabled = if ($null -ne $envEnvelope) { [string]$envEnvelope.gridAutoRebalanceSchedulerEnabled } else { "UNKNOWN" }
$recoveryEnabled = if ($null -ne $envEnvelope) { [string]$envEnvelope.gridRecoveryEnabled } else { "UNKNOWN" }
$earnEnabled = if ($null -ne $envEnvelope) { [string]$envEnvelope.okxEarnTopupEnabled } else { "UNKNOWN" }
$eventRiskGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.eventRiskGate } else { "UNKNOWN" }
$trendGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.trendGate } else { "UNKNOWN" }
$trendOverrideReady = if ($null -ne $trendOverridePacket) { [bool]$trendOverridePacket.trendOverrideReviewReady } else { $false }

if ($null -eq $envEnvelope) {
    Add-Unique -List $missingEvidence -Value "okxGridEnvPreflightEnvelope from grid open operator packet"
}
if (-not $credentialsReady) {
    Add-Unique -List $reviewBlockers -Value "OKX_CREDENTIALS_NOT_READY"
}
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") {
    Add-Unique -List $reviewBlockers -Value "EVENT_RISK_NOT_R0_FOR_GRID_ENV_DIFF"
}
if ($trendGate -like "BLOCKED_*" -and -not $trendOverrideReady) {
    Add-Unique -List $reviewBlockers -Value "TREND_OVERRIDE_REVIEW_NOT_READY_FOR_ENV_DIFF"
}
if ($schedulerEnabled -ne "false") {
    Add-Unique -List $reviewBlockers -Value "GRID_AUTO_REBALANCE_SCHEDULER_NOT_FALSE"
}
if ($recoveryEnabled -ne "false") {
    Add-Unique -List $reviewBlockers -Value "GRID_RECOVERY_NOT_FALSE"
}
if ($earnEnabled -ne "false") {
    Add-Unique -List $reviewBlockers -Value "OKX_EARN_TOPUP_NOT_FALSE"
}
if ($okxEnabled -eq "true") {
    Add-Unique -List $reviewBlockers -Value "TRADING_OKX_ENABLED_ALREADY_TRUE"
}
if ($gridEnabled -eq "true") {
    Add-Unique -List $reviewBlockers -Value "TRADING_GRID_ENABLED_ALREADY_TRUE"
}

Add-Unique -List $operatorAuthorizationRequired -Value "separate written trend override approval or fresh trend gate clearance"
Add-Unique -List $operatorAuthorizationRequired -Value "separate written production env diff authorization"
Add-Unique -List $operatorAuthorizationRequired -Value "separate deploy/restart authorization after env diff"
Add-Unique -List $operatorAuthorizationRequired -Value "separate createGrid authorization after post-env verification"

$reviewReady = (
    $missingEvidence.Count -eq 0 -and
    $reviewBlockers.Count -eq 0 -and
    $credentialsReady -and
    $eventRiskGate -eq "CLEAR_EVENT_RISK_R0" -and
    ($trendGate -notlike "BLOCKED_*" -or $trendOverrideReady)
)
$status = if ($reviewReady) {
    "READY_FOR_GRID_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_GRID_ENV_DIFF_PREFLIGHT_NOT_MUTATION"
}
$decision = if ($reviewReady) {
    "PREPARE_SEPARATE_GRID_ENV_DIFF_AUTHORIZATION"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_ENV_PREFLIGHT_EVIDENCE"
} else {
    "RESOLVE_GRID_ENV_DIFF_PREFLIGHT_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_ENV_DIFF_PREFLIGHT_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceOperatorPacket = "prepare_grid_open_operator_packet_ssh.ps1"
    sourceTrendOverridePacket = "prepare_grid_trend_override_review_packet_ssh.ps1"
    sourceOperatorStatus = if ($null -ne $operatorPacket) { $operatorPacket.status } else { "UNKNOWN" }
    sourceTrendOverrideStatus = if ($null -ne $trendOverridePacket) { $trendOverridePacket.status } else { "UNKNOWN" }
    envReadiness = [pscustomobject]@{
        credentialsReady = $credentialsReady
        tradingOkxEnabled = $okxEnabled
        tradingGridEnabled = $gridEnabled
        gridAutoRebalanceSchedulerEnabled = $schedulerEnabled
        gridRecoveryEnabled = $recoveryEnabled
        okxEarnTopupEnabled = $earnEnabled
        eventRiskGate = $eventRiskGate
        trendGate = $trendGate
        trendOverrideReviewReady = $trendOverrideReady
    }
    proposedSeparateEnvDiff = @(
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false"
    )
    preApplyRequirements = @(
        "fresh GRID_ENV_DIFF_PREFLIGHT_PACKET with status READY_FOR_GRID_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
        "separate written trend override approval or trend gate clearance",
        "separate written production env diff authorization",
        "confirm OKX credentials remain masked and present",
        "keep scheduler/recovery/Earn disabled for initial grid-open review"
    )
    postApplyReadOnlyVerification = @(
        "deploy/server verification after env diff if separately authorized",
        "verify split acceptance",
        "refresh grid open decision snapshot",
        "refresh grid trend override review packet if trend remains blocked",
        "refresh grid open operator packet",
        "confirm runtime log has no unexpected order/OCO/grid/Earn/fund mutation lines",
        "confirm grid_open_allowed=false until separate createGrid authorization"
    )
    reviewBlockers = @($reviewBlockers)
    missingEvidence = @($missingEvidence)
    operatorAuthorizationRequired = @($operatorAuthorizationRequired)
    envDiffReviewReady = $reviewReady
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceEnvEnvelope = $envEnvelope
    sourceTrendOverridePacketSummary = $trendOverridePacket
    notAuthorization = "read-only grid env diff preflight only; does not change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-env-diff-preflight] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid open operator and trend override review packets only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_operator_packet=prepare_grid_open_operator_packet_ssh.ps1 exitCode=$operatorExitCode"
Write-Host "source_trend_override_packet=prepare_grid_trend_override_review_packet_ssh.ps1 exitCode=$trendOverrideExitCode"
Write-Host "grid_env_diff_preflight_status=$status"
Write-Host "grid_env_diff_preflight_decision=$decision"
Write-Host "grid_env_diff_review_ready=$($reviewReady.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_env_diff_preflight_review_blockers=" + (ConvertTo-Json -Compress @($reviewBlockers)))
Write-Host ("grid_env_diff_preflight_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_env_diff_preflight_operator_authorization_required=" + (ConvertTo-Json -Compress @($operatorAuthorizationRequired)))
Write-Host ("grid_env_diff_preflight_proposed_env_diff=" + (ConvertTo-Json -Compress $packet.proposedSeparateEnvDiff))
Write-Host ("grid_env_diff_preflight_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid env diff preflight only; does not change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-env-diff-preflight] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "Grid env diff preflight is not ready: $status; blockers=$(@($reviewBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
