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

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Convert-PlanNumber {
    param($Value, [decimal]$Default = 0)
    if ($null -eq $Value) { return $Default }
    try {
        return [decimal]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        return $Default
    }
}

function New-TrendOverrideRiskEnvelope {
    param($CandidatePlan, [string]$TrendGateStatus, [string]$EventRiskGateStatus)

    if ($null -eq $CandidatePlan) {
        return [pscustomobject]@{
            status = "NOT_REVIEWABLE_NO_CANDIDATE_PLAN"
            riskGrade = "UNKNOWN"
            missingEvidence = @("complete replayable grid candidate plan")
            notAuthorization = "read-only trend override risk envelope only; does not clear trend gate or authorize grid opening"
        }
    }

    $trend = [string]$CandidatePlan.trend
    $trendPct = Convert-PlanNumber $CandidatePlan.trendPct
    $atrPct = Convert-PlanNumber $CandidatePlan.atrPct
    $replayScore = Convert-PlanNumber $CandidatePlan.replayScore
    $stopBreakRows = [int](Convert-PlanNumber $CandidatePlan.stopBreakRows)
    $insidePct = Convert-PlanNumber $CandidatePlan.insidePct
    $capital = Convert-PlanNumber $CandidatePlan.candidateCapitalUsdt
    $stepPct = Convert-PlanNumber $CandidatePlan.stepPct

    $riskPoints = 0
    if ($trend -in @("DOWN_STRONG", "UP_STRONG")) { $riskPoints += 3 }
    elseif ($trend -in @("DOWN", "UP")) { $riskPoints += 2 }
    if ([math]::Abs([double]$trendPct) -ge 4.0) { $riskPoints += 2 }
    elseif ([math]::Abs([double]$trendPct) -ge 2.5) { $riskPoints += 1 }
    if ($eventRiskGateStatus -ne "CLEAR_EVENT_RISK_R0") { $riskPoints += 2 }
    if ($stopBreakRows -gt 0) { $riskPoints += 3 }
    if ($replayScore -lt 70) { $riskPoints += 2 }
    elseif ($replayScore -lt 80) { $riskPoints += 1 }
    if ($insidePct -lt 90) { $riskPoints += 1 }
    if ($stepPct -lt 0.5) { $riskPoints += 1 }

    $riskGrade = if ($riskPoints -ge 7) {
        "HIGH"
    } elseif ($riskPoints -ge 4) {
        "MEDIUM"
    } else {
        "LOW"
    }

    $capMultiplier = if ($riskGrade -eq "HIGH") {
        0.25
    } elseif ($riskGrade -eq "MEDIUM") {
        0.50
    } else {
        1.00
    }
    $capitalCap = [math]::Round([double]($capital * [decimal]$capMultiplier), 2)

    $overrideStatus = if ($TrendGateStatus -like "BLOCKED_*") {
        "OVERRIDE_REVIEW_REQUIRED_NOT_CLEARANCE"
    } else {
        "TREND_GATE_ALREADY_CLEAR"
    }

    return [pscustomobject]@{
        status = $overrideStatus
        riskGrade = $riskGrade
        riskPoints = $riskPoints
        trend = $trend
        trendPct = $trendPct
        atrPct = $atrPct
        replayScore = $replayScore
        insidePct = $insidePct
        stopBreakRows = $stopBreakRows
        candidateCapitalUsdt = $capital
        recommendedOverrideCapitalCapUsdt = $capitalCap
        requiredOverrideConditions = @(
            "separate written trend-regime override naming current trend and capital cap",
            "event-risk R0 or separate written event-risk override",
            "use candidate stopLow/stopHigh as hard review boundaries",
            "keep scheduler/recovery disabled unless separately authorized",
            "refresh readiness immediately before any createGrid request",
            "abort review if replay stopBreakRows becomes greater than 0"
        )
        missingEvidence = @()
        notAuthorization = "read-only trend override risk envelope only; does not clear trend gate or authorize grid opening"
    }
}

function New-EventRiskOverrideRiskEnvelope {
    param([string]$RiskLevel, $CandidatePlan, [string]$TrendGateStatus)

    $normalizedRiskLevel = if ([string]::IsNullOrWhiteSpace($RiskLevel)) { "UNKNOWN" } else { $RiskLevel }
    $capital = if ($null -ne $CandidatePlan) { Convert-PlanNumber $CandidatePlan.candidateCapitalUsdt } else { [decimal]0 }
    $stopBreakRows = if ($null -ne $CandidatePlan) { [int](Convert-PlanNumber $CandidatePlan.stopBreakRows) } else { 0 }
    $replayScore = if ($null -ne $CandidatePlan) { Convert-PlanNumber $CandidatePlan.replayScore } else { [decimal]0 }

    $riskPoints = switch ($normalizedRiskLevel) {
        "R0" { 0 }
        "R1" { 2 }
        "R2" { 5 }
        "R3" { 8 }
        default { 6 }
    }
    if ($TrendGateStatus -like "BLOCKED_*") { $riskPoints += 2 }
    if ($stopBreakRows -gt 0) { $riskPoints += 3 }
    if ($replayScore -lt 70 -and $replayScore -gt 0) { $riskPoints += 1 }

    $riskGrade = if ($riskPoints -ge 8) {
        "HIGH"
    } elseif ($riskPoints -ge 4) {
        "MEDIUM"
    } else {
        "LOW"
    }

    $capMultiplier = switch ($normalizedRiskLevel) {
        "R0" { 1.00 }
        "R1" { 0.50 }
        "R2" { 0.25 }
        "R3" { 0.00 }
        default { 0.00 }
    }
    if ($riskGrade -eq "HIGH" -and $capMultiplier -gt 0.25) {
        $capMultiplier = 0.25
    }
    $capitalCap = [math]::Round([double]($capital * [decimal]$capMultiplier), 2)

    $status = if ($normalizedRiskLevel -eq "R0") {
        "EVENT_RISK_GATE_ALREADY_CLEAR"
    } elseif ($normalizedRiskLevel -eq "R3") {
        "OVERRIDE_NOT_RECOMMENDED_R3"
    } else {
        "OVERRIDE_REVIEW_REQUIRED_NOT_CLEARANCE"
    }

    $missingEvidence = if ($normalizedRiskLevel -eq "UNKNOWN") {
        @("fresh getEventRiskControlStatus output")
    } else {
        @()
    }

    return [pscustomobject]@{
        status = $status
        riskGrade = $riskGrade
        riskPoints = $riskPoints
        riskLevel = $normalizedRiskLevel
        replayScore = $replayScore
        stopBreakRows = $stopBreakRows
        candidateCapitalUsdt = $capital
        recommendedOverrideCapitalCapUsdt = $capitalCap
        requiredOverrideConditions = @(
            "separate written event-risk override naming current riskLevel and capital cap",
            "R3 must not be overridden for grid open review",
            "refresh getEventRiskControlStatus immediately before any createGrid request",
            "keep TinyLive/live/scheduler/recovery disabled unless separately authorized",
            "abort review if event-risk escalates above the approved level",
            "abort review if replay stopBreakRows becomes greater than 0"
        )
        missingEvidence = @($missingEvidence)
        notAuthorization = "read-only event-risk override risk envelope only; does not clear event-risk gate or authorize grid opening"
    }
}

function Get-RiskGradeRank {
    param([string]$RiskGrade)
    switch ($RiskGrade) {
        "LOW" { return 1 }
        "MEDIUM" { return 2 }
        "HIGH" { return 3 }
        default { return 0 }
    }
}

function New-CombinedGridOverrideRiskEnvelope {
    param($TrendEnvelope, $EventRiskEnvelope)

    $trendCap = Convert-PlanNumber $TrendEnvelope.recommendedOverrideCapitalCapUsdt
    $eventCap = Convert-PlanNumber $EventRiskEnvelope.recommendedOverrideCapitalCapUsdt
    $caps = @($trendCap, $eventCap) | Where-Object { $_ -ge 0 }
    $effectiveCap = if ($caps.Count -gt 0) {
        [math]::Round([double](@($caps | Sort-Object)[0]), 2)
    } else {
        0
    }

    $trendRank = Get-RiskGradeRank ([string]$TrendEnvelope.riskGrade)
    $eventRank = Get-RiskGradeRank ([string]$EventRiskEnvelope.riskGrade)
    $combinedGrade = if ([math]::Max($trendRank, $eventRank) -ge 3) {
        "HIGH"
    } elseif ([math]::Max($trendRank, $eventRank) -eq 2) {
        "MEDIUM"
    } elseif ([math]::Max($trendRank, $eventRank) -eq 1) {
        "LOW"
    } else {
        "UNKNOWN"
    }

    return [pscustomobject]@{
        status = "COMBINED_OVERRIDE_REVIEW_NOT_CLEARANCE"
        riskGrade = $combinedGrade
        effectiveReviewCapitalCapUsdt = $effectiveCap
        trendRiskGrade = $TrendEnvelope.riskGrade
        eventRiskGrade = $EventRiskEnvelope.riskGrade
        requiredOverrideDocuments = @(
            "separate written trend-regime override if trend gate is blocked",
            "separate written event-risk override if event-risk gate is blocked",
            "separate written production env diff authorization",
            "separate written createGrid authorization using reviewed inputs"
        )
        notAuthorization = "read-only combined override risk envelope only; does not clear gates or authorize grid opening"
    }
}

function New-OkxGridEnvPreflightEnvelope {
    param($GridRuntimeFlags, $CombinedOverrideEnvelope)

    if ($null -eq $GridRuntimeFlags) {
        return [pscustomobject]@{
            status = "NOT_REVIEWABLE_GRID_RUNTIME_FLAGS_MISSING"
            credentialsReady = $false
            missingEvidence = @("gridRuntimeFlags from grid open readiness packet")
            notAuthorization = "read-only OKX/grid env preflight only; does not change production env or authorize grid opening"
        }
    }

    $okxEnabled = [string]$GridRuntimeFlags.TRADING_OKX_ENABLED
    $gridEnabled = [string]$GridRuntimeFlags.TRADING_GRID_ENABLED
    $schedulerEnabled = [string]$GridRuntimeFlags.TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED
    $recoveryEnabled = [string]$GridRuntimeFlags.GRID_RECOVERY_ENABLED
    $earnEnabled = [string]$GridRuntimeFlags.OKX_EARN_TOPUP_ENABLED
    $apiKey = [string]$GridRuntimeFlags.TRADING_OKX_API_KEY
    $secretKey = [string]$GridRuntimeFlags.TRADING_OKX_SECRET_KEY
    $passphrase = [string]$GridRuntimeFlags.TRADING_OKX_PASSPHRASE

    $credentialsReady = $apiKey -eq "SET" -and $secretKey -eq "SET" -and $passphrase -eq "SET"
    $missingRequirements = [System.Collections.Generic.List[string]]::new()
    if (-not $credentialsReady) { Add-MissingRequirement -List $missingRequirements -Value "TRADING_OKX_API_KEY/SECRET_KEY/PASSPHRASE present" }
    if ($okxEnabled -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "separate production env authorization for TRADING_OKX_ENABLED=true" }
    if ($gridEnabled -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "separate production env authorization for TRADING_GRID_ENABLED=true" }
    if ($schedulerEnabled -ne "false") { Add-MissingRequirement -List $missingRequirements -Value "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false for initial open review" }
    if ($recoveryEnabled -ne "false") { Add-MissingRequirement -List $missingRequirements -Value "GRID_RECOVERY_ENABLED=false for initial open review" }
    if ($earnEnabled -ne "false") { Add-MissingRequirement -List $missingRequirements -Value "OKX_EARN_TOPUP_ENABLED=false for initial open review" }

    $status = if ($missingRequirements.Count -eq 0) {
        "ENV_PREFLIGHT_READY_NOT_GRID_APPROVAL"
    } else {
        "ENV_AUTHORIZATION_REQUIRED_NOT_CLEARANCE"
    }

    return [pscustomobject]@{
        status = $status
        credentialsReady = $credentialsReady
        tradingOkxEnabled = $okxEnabled
        tradingGridEnabled = $gridEnabled
        gridAutoRebalanceSchedulerEnabled = $schedulerEnabled
        gridRecoveryEnabled = $recoveryEnabled
        okxEarnTopupEnabled = $earnEnabled
        effectiveReviewCapitalCapUsdt = $CombinedOverrideEnvelope.effectiveReviewCapitalCapUsdt
        requiredEnvDiff = @(
            "TRADING_OKX_ENABLED=true",
            "TRADING_GRID_ENABLED=true",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
            "GRID_RECOVERY_ENABLED=false",
            "OKX_EARN_TOPUP_ENABLED=false"
        )
        postEnvVerification = @(
            "deploy/server verification if env diff is applied",
            "verify split acceptance",
            "refresh grid open operator packet",
            "confirm OKX credentials remain masked and present",
            "confirm scheduler/recovery/Earn remain disabled unless separately authorized",
            "confirm runtime log has no unexpected order/OCO/grid/Earn/fund mutation lines"
        )
        missingRequirements = @($missingRequirements)
        missingEvidence = @()
        notAuthorization = "read-only OKX/grid env preflight only; does not change production env, enable OKX/grid, call createGrid, or authorize grid opening"
    }
}

if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for grid open operator packet arguments."
}
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }

$readinessScript = Join-Path $PSScriptRoot "prepare_grid_open_readiness_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $readinessScript)) {
    throw "Missing grid open readiness packet script: $readinessScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open operator packet." }

$readinessArgs = @(
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
    $readinessOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $readinessScript @readinessArgs 2>&1
    $readinessExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$readinessText = ($readinessOutput | Out-String -Width 8192)
$readinessJson = Get-LastPrefixedValue -Text $readinessText -Prefix "grid_open_readiness_packet="
$readinessPacket = $null
if (-not [string]::IsNullOrWhiteSpace($readinessJson)) {
    $readinessPacket = $readinessJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($readinessExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "grid open readiness packet completed" }
if ($null -eq $readinessPacket) { Add-MissingRequirement -List $missingRequirements -Value "grid_open_readiness_packet valid JSON" }

$readinessStatus = ""
$candidatePlanStatus = ""
$candidatePlanComplete = $false
$trendGateStatus = ""
$eventRiskGateStatus = ""
$okxGateStatus = ""
$eventRiskLevel = ""
$blockers = @()
$warnings = @()
$operatorAuthorizationRequired = @()
$candidatePlan = $null
$gridRuntimeFlags = $null
if ($null -ne $readinessPacket) {
    $readinessStatus = [string]$readinessPacket.status
    $blockers = @($readinessPacket.blockers)
    $warnings = @($readinessPacket.warnings)
    $operatorAuthorizationRequired = @($readinessPacket.operatorAuthorizationRequired)
    $candidatePlan = $readinessPacket.candidatePlan
    if ($null -ne $candidatePlan) {
        $candidatePlanStatus = [string]$candidatePlan.candidatePlanStatus
        $candidatePlanComplete = [bool]$candidatePlan.candidatePlanComplete
    }
    if ($null -ne $readinessPacket.gateReview) {
        $trendGateStatus = [string]$readinessPacket.gateReview.trendGate.status
        $eventRiskGateStatus = [string]$readinessPacket.gateReview.eventRiskGate.status
        $okxGateStatus = [string]$readinessPacket.gateReview.okxGate.status
    }
    $eventRiskLevel = [string]$readinessPacket.eventRiskLevel
    $gridRuntimeFlags = $readinessPacket.gridRuntimeFlags
}

if (-not $candidatePlanComplete) {
    Add-MissingRequirement -List $missingRequirements -Value "complete replayable grid candidate plan"
}
if ($readinessStatus -ne "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid open readiness has no blockers or has separate override evidence"
}
if ($trendGateStatus -like "BLOCKED_*") {
    Add-MissingRequirement -List $missingRequirements -Value "trend gate cleared by SIDEWAYS evidence or separate operator trend override"
}
if ($eventRiskGateStatus -like "BLOCKED_*") {
    Add-MissingRequirement -List $missingRequirements -Value "event-risk gate cleared by R0 evidence or separate operator risk override"
}
if ($okxGateStatus -like "BLOCKED_*") {
    Add-MissingRequirement -List $missingRequirements -Value "separate operator authorization for TRADING_OKX_ENABLED=true"
}

$trendOverrideRiskEnvelope = New-TrendOverrideRiskEnvelope -CandidatePlan $candidatePlan -TrendGateStatus $trendGateStatus -EventRiskGateStatus $eventRiskGateStatus
$eventRiskOverrideRiskEnvelope = New-EventRiskOverrideRiskEnvelope -RiskLevel $eventRiskLevel -CandidatePlan $candidatePlan -TrendGateStatus $trendGateStatus
$combinedOverrideRiskEnvelope = New-CombinedGridOverrideRiskEnvelope -TrendEnvelope $trendOverrideRiskEnvelope -EventRiskEnvelope $eventRiskOverrideRiskEnvelope
$okxGridEnvPreflightEnvelope = New-OkxGridEnvPreflightEnvelope -GridRuntimeFlags $gridRuntimeFlags -CombinedOverrideEnvelope $combinedOverrideRiskEnvelope
$reviewReady = $missingRequirements.Count -eq 0
$status = if ($reviewReady) { "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION" } else { "BLOCKED_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION" }
$decision = if ($reviewReady) { "PREPARE_SEPARATE_GRID_OPEN_OPERATOR_REVIEW" } else { "WAIT_FOR_GATE_CLEARANCE_OR_SEPARATE_OPERATOR_OVERRIDES" }
$nextAction = if ($reviewReady) {
    "Attach this packet to a separate grid-open operator review; require explicit env/createGrid authorization before any mutation."
} else {
    "Refresh readiness after trend/event-risk changes, or collect separate operator override evidence before any grid-open request."
}

$packet = [pscustomobject]@{
    packetType = "GRID_OPEN_OPERATOR_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceReadinessPacket = "prepare_grid_open_readiness_packet_ssh.ps1"
    sourceReadinessStatus = $readinessStatus
    candidatePlanComplete = $candidatePlanComplete
    candidatePlanStatus = $candidatePlanStatus
    candidatePlan = $candidatePlan
    gateStatuses = [pscustomobject]@{
        trendGate = $trendGateStatus
        eventRiskGate = $eventRiskGateStatus
        okxGate = $okxGateStatus
    }
    trendOverrideRiskEnvelope = $trendOverrideRiskEnvelope
    eventRiskOverrideRiskEnvelope = $eventRiskOverrideRiskEnvelope
    combinedOverrideRiskEnvelope = $combinedOverrideRiskEnvelope
    okxGridEnvPreflightEnvelope = $okxGridEnvPreflightEnvelope
    blockers = @($blockers)
    warnings = @($warnings)
    missingRequirements = @($missingRequirements)
    operatorAuthorizationRequired = @($operatorAuthorizationRequired)
    proposedSeparateEnvDiff = @(
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false unless separately authorized",
        "GRID_RECOVERY_ENABLED=false unless separately authorized",
        "OKX_EARN_TOPUP_ENABLED=false"
    )
    createGridReviewInputs = if ($null -ne $candidatePlan) {
        [pscustomobject]@{
            symbol = $candidatePlan.candidateSymbol
            priceLower = $candidatePlan.candidateLower
            priceUpper = $candidatePlan.candidateUpper
            gridCount = $candidatePlan.gridCount
            perLevelUsdt = $candidatePlan.perLevelUsdt
            candidateCapitalUsdt = $candidatePlan.candidateCapitalUsdt
            stopLow = $candidatePlan.stopLow
            stopHigh = $candidatePlan.stopHigh
            stopOutPct = $candidatePlan.stopOutPct
            replayScore = $candidatePlan.replayScore
            replayRows = $candidatePlan.replayRows
        }
    } else {
        $null
    }
    postAuthorizationVerificationPlan = @(
        "deploy/server verification if env diff is applied",
        "verify split acceptance",
        "refresh grid open readiness packet",
        "verify active grid count and exposure",
        "verify grid price alignment",
        "check runtime log for high-risk unexpected order/OCO/grid/Earn/fund lines",
        "verify public dedicated MCP and server-local MCP auth route"
    )
    explicitNonAuthorizations = @(
        "does not change production env",
        "does not enable TRADING_OKX_ENABLED",
        "does not enable TRADING_GRID_ENABLED",
        "does not call createGrid",
        "does not enable scheduler or recovery",
        "does not place orders",
        "does not modify OCO",
        "does not send Telegram",
        "does not mutate DB/grid/fund/Earn/exchange state"
    )
    sourceReadinessPacketSummary = $readinessPacket
    nextAction = $nextAction
    notAuthorization = "read-only grid open operator packet only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
}

Write-Host "[grid-open-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_grid_open_readiness_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host $readinessText
Write-Host "source_readiness_packet=prepare_grid_open_readiness_packet_ssh.ps1 exitCode=$readinessExitCode"
Write-Host "source_readiness_status=$readinessStatus"
Write-Host "grid_open_operator_candidate_plan_complete=$candidatePlanComplete"
Write-Host "grid_open_operator_candidate_plan_status=$candidatePlanStatus"
Write-Host "grid_open_operator_trend_gate_status=$trendGateStatus"
Write-Host "grid_open_operator_event_risk_gate_status=$eventRiskGateStatus"
Write-Host "grid_open_operator_okx_gate_status=$okxGateStatus"
Write-Host ("grid_open_operator_trend_override_risk_envelope=" + (ConvertTo-Json -Compress -Depth 8 $trendOverrideRiskEnvelope))
Write-Host ("grid_open_operator_event_risk_override_risk_envelope=" + (ConvertTo-Json -Compress -Depth 8 $eventRiskOverrideRiskEnvelope))
Write-Host ("grid_open_operator_combined_override_risk_envelope=" + (ConvertTo-Json -Compress -Depth 8 $combinedOverrideRiskEnvelope))
Write-Host ("grid_open_operator_okx_grid_env_preflight_envelope=" + (ConvertTo-Json -Compress -Depth 8 $okxGridEnvPreflightEnvelope))
Write-Host "production_env_change_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_open_operator_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("grid_open_operator_authorization_required=" + (ConvertTo-Json -Compress @($operatorAuthorizationRequired)))
Write-Host ("grid_open_operator_packet=" + (ConvertTo-Json -Compress -Depth 16 $packet))
Write-Host "grid_open_operator_status=$status"
Write-Host "grid_open_operator_decision=$decision"
Write-Host "grid_open_operator_next_action=$nextAction"
Write-Host "notAuthorization=read-only grid open operator packet only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
Write-Host "[grid-open-operator-packet] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "Grid open operator packet is not review-ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
