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
$blockers = @()
$warnings = @()
$operatorAuthorizationRequired = @()
$candidatePlan = $null
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
