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
    [decimal]$SidewaysTrendPctThreshold = 1.0,
    [switch]$RequireTrendClear
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-WatchNumber {
    param($Value, [decimal]$Default = 0)
    if ($null -eq $Value) { return $Default }
    $text = ([string]$Value).Trim().TrimEnd("%")
    try {
        return [decimal]::Parse($text, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        return $Default
    }
}

function Get-ArrayValue {
    param($Value)
    if ($null -eq $Value) { return @() }
    return @($Value)
}

if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for grid trend clearance watch arguments."
}
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($SidewaysTrendPctThreshold -lt 0.1 -or $SidewaysTrendPctThreshold -gt 3.0) {
    throw "SidewaysTrendPctThreshold must be between 0.1 and 3.0."
}

$operatorScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $operatorScript)) {
    throw "Missing grid open operator packet script: $operatorScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid trend clearance watch packet." }

$operatorArgs = @(
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
    $operatorOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $operatorScript @operatorArgs 2>&1
    $operatorExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$operatorText = ($operatorOutput | Out-String -Width 8192)
$operatorJson = Get-LastPrefixedValue -Text $operatorText -Prefix "grid_open_operator_packet="
$operatorPacket = $null
if (-not [string]::IsNullOrWhiteSpace($operatorJson)) {
    $operatorPacket = $operatorJson | ConvertFrom-Json -ErrorAction Stop
}

$missingEvidence = [System.Collections.Generic.List[string]]::new()
if ($operatorExitCode -ne 0) { $missingEvidence.Add("grid open operator packet completed") }
if ($null -eq $operatorPacket) { $missingEvidence.Add("grid_open_operator_packet valid JSON") }

$candidatePlan = $null
$gateStatuses = $null
$trendEnvelope = $null
$combinedEnvelope = $null
$okxEnvEnvelope = $null
$sourceStatus = ""
if ($null -ne $operatorPacket) {
    $candidatePlan = $operatorPacket.candidatePlan
    $gateStatuses = $operatorPacket.gateStatuses
    $trendEnvelope = $operatorPacket.trendOverrideRiskEnvelope
    $combinedEnvelope = $operatorPacket.combinedOverrideRiskEnvelope
    $okxEnvEnvelope = $operatorPacket.okxGridEnvPreflightEnvelope
    $sourceStatus = [string]$operatorPacket.status
}

$trend = if ($null -ne $candidatePlan) { [string]$candidatePlan.trend } else { "" }
$trendPct = if ($null -ne $candidatePlan) { Convert-WatchNumber $candidatePlan.trendPct } else { [decimal]0 }
$atrPct = if ($null -ne $candidatePlan) { Convert-WatchNumber $candidatePlan.atrPct } else { [decimal]0 }
$replayScore = if ($null -ne $candidatePlan) { Convert-WatchNumber $candidatePlan.replayScore } else { [decimal]0 }
$stopBreakRows = if ($null -ne $candidatePlan) { [int](Convert-WatchNumber $candidatePlan.stopBreakRows) } else { 0 }
$trendGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.trendGate } else { "" }
$eventRiskGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.eventRiskGate } else { "" }
$okxGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.okxGate } else { "" }
$absTrendPct = [math]::Abs([double]$trendPct)
$distance = [math]::Round([math]::Max(0.0, $absTrendPct - [double]$SidewaysTrendPctThreshold), 4)
$directionToClear = if ($trendPct -lt 0) { "trendPct must rise toward >= -$SidewaysTrendPctThreshold%" } elseif ($trendPct -gt 0) { "trendPct must fall toward <= $SidewaysTrendPctThreshold%" } else { "trendPct already within sideways threshold" }
$trendClear = $trendGate -notlike "BLOCKED_*" -and $missingEvidence.Count -eq 0

$watchStatus = if ($missingEvidence.Count -gt 0) {
    "NOT_REVIEWABLE_SOURCE_OPERATOR_PACKET_MISSING"
} elseif ($trendClear) {
    "READY_TREND_GATE_CLEAR_NOT_OPEN_APPROVAL"
} else {
    "WATCH_TREND_CLEARANCE_PENDING_NOT_MUTATION"
}
$decision = if ($trendClear) {
    "TREND_GATE_CLEAR_REFRESH_OPERATOR_PACKET_BEFORE_ENV_OR_CREATEGRID"
} else {
    "WAIT_FOR_SIDEWAYS_OR_SEPARATE_TREND_OVERRIDE"
}

$packet = [pscustomobject]@{
    packetType = "GRID_TREND_CLEARANCE_WATCH_PACKET"
    scope = "READ_ONLY"
    symbol = $Symbol
    sourceOperatorPacket = "prepare_grid_open_operator_packet_ssh.ps1"
    sourceOperatorStatus = $sourceStatus
    status = $watchStatus
    decision = $decision
    trendGateStatus = $trendGate
    eventRiskGateStatus = $eventRiskGate
    okxGateStatus = $okxGate
    trend = $trend
    trendPct = $trendPct
    atrPct = $atrPct
    sidewaysTrendPctThreshold = $SidewaysTrendPctThreshold
    trendDistanceToSidewaysPct = $distance
    directionToClear = $directionToClear
    replayScore = $replayScore
    stopBreakRows = $stopBreakRows
    overrideRiskGrade = if ($null -ne $trendEnvelope) { $trendEnvelope.riskGrade } else { "UNKNOWN" }
    recommendedOverrideCapitalCapUsdt = if ($null -ne $trendEnvelope) { $trendEnvelope.recommendedOverrideCapitalCapUsdt } else { $null }
    effectiveReviewCapitalCapUsdt = if ($null -ne $combinedEnvelope) { $combinedEnvelope.effectiveReviewCapitalCapUsdt } else { $null }
    okxGridEnvPreflightStatus = if ($null -ne $okxEnvEnvelope) { $okxEnvEnvelope.status } else { "UNKNOWN" }
    clearanceCriteria = @(
        "trend gate clear in fresh grid open operator packet",
        "absolute trendPct <= SidewaysTrendPctThreshold, or separate written trend override",
        "candidatePlanComplete=true",
        "stopBreakRows=0",
        "eventRiskGateStatus=CLEAR_EVENT_RISK_R0"
    )
    abortCriteria = @(
        "stopBreakRows becomes greater than 0",
        "event risk escalates above R0 without separate written override",
        "trend override risk grade becomes HIGH",
        "candidate plan becomes incomplete or replay rows are insufficient",
        "OKX/grid env flags change without separate operator authorization"
    )
    nextVerification = @(
        "rerun prepare_grid_trend_clearance_watch_packet_ssh.ps1 before any grid-open review",
        "rerun prepare_grid_open_operator_packet_ssh.ps1 immediately before any env diff or createGrid request",
        "run deploy/server verification only if a separately authorized env diff is applied"
    )
    blockers = Get-ArrayValue $(if ($null -ne $operatorPacket) { $operatorPacket.blockers } else { @() })
    missingEvidence = @($missingEvidence)
    notAuthorization = "read-only trend clearance watch only; does not clear trend gate, change production env, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, deploy, restart, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-trend-clearance-watch] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_grid_open_operator_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host $operatorText
Write-Host "source_operator_packet=prepare_grid_open_operator_packet_ssh.ps1 exitCode=$operatorExitCode"
Write-Host "source_operator_status=$sourceStatus"
Write-Host "grid_trend_clearance_watch_status=$watchStatus"
Write-Host "grid_trend_clearance_watch_decision=$decision"
Write-Host "grid_trend_clearance_watch_trend=$trend"
Write-Host "grid_trend_clearance_watch_trend_pct=$trendPct"
Write-Host "grid_trend_clearance_watch_sideways_threshold_pct=$SidewaysTrendPctThreshold"
Write-Host "grid_trend_clearance_watch_distance_to_sideways_pct=$distance"
Write-Host "grid_trend_clearance_watch_direction_to_clear=$directionToClear"
Write-Host "grid_trend_clearance_watch_override_cap_usdt=$($packet.recommendedOverrideCapitalCapUsdt)"
Write-Host "grid_trend_clearance_watch_effective_cap_usdt=$($packet.effectiveReviewCapitalCapUsdt)"
Write-Host "trend_gate_clear_allowed=$($trendClear.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_trend_clearance_watch_packet=" + (ConvertTo-Json -Compress -Depth 16 $packet))
Write-Host "notAuthorization=read-only trend clearance watch only; does not clear trend gate, change production env, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, deploy, restart, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-trend-clearance-watch] read-only check complete"

if ($RequireTrendClear -and -not $trendClear) {
    throw "Grid trend gate is not clear: $watchStatus; trend=$trend trendPct=$trendPct distanceToSidewaysPct=$distance"
}
