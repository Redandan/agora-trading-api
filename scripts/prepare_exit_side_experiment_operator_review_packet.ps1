param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [decimal]$ReviewNotionalCapUsdt = 25,
    [int]$ObservationHours = 72,
    [switch]$RequireReady
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

function New-ReviewItem {
    param([object]$Plan, [decimal]$CapUsdt, [int]$Hours)

    $planId = [string]$Plan.planId
    $lane = [string]$Plan.lane
    $isTrailing = $planId -eq "trailing-stop-dry-run-readiness"
    $reviewQuestion = if ($isTrailing) {
        "Approve a review-only trailing-stop dry-run experiment design while keeping scheduler/live/OCO mutation disabled?"
    } else {
        "Approve a review-only strategy 485 shadow risk-reduction evaluation before any close-position or OCO approval path?"
    }
    $proposalId = if ($isTrailing) { "trailing-stop-dry-run-operator-review" } else { "strategy485-risk-reduction-shadow-operator-review" }

    return [pscustomobject]@{
        proposalId = $proposalId
        sourcePlanId = $planId
        lane = $lane
        mode = [string]$Plan.mode
        status = if ([string]$Plan.status -eq "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") { "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
        reviewQuestion = $reviewQuestion
        proposedEnvelope = [pscustomobject]@{
            reviewOnly = $true
            proposedMaxNotionalUsdt = $CapUsdt
            observationHours = $Hours
            liveTradingAllowed = $false
            orderAllowed = $false
            positionOrOcoMutationAllowed = $false
            deployOrEnvChangeAllowed = $false
        }
        minimumEvidence = @($Plan.minimumEvidence)
        inheritedRequiredFreshEvidence = @($Plan.inheritedRequiredFreshEvidence)
        successEvidence = @($Plan.successEvidence)
        stopCriteria = @($Plan.stopCriteria)
        requiredSeparateAuthorization = if ($isTrailing) {
            @("enable trailing scheduler", "change strategy opt-in or exit policy", "modify OCO", "deploy or production env change")
        } else {
            @("close any strategy 485 position", "modify or cancel OCO", "place orders", "deploy or production env change")
        }
        operatorDecisionChoices = @(
            "approve review-only packet",
            "request fresh read-only evidence",
            "reject or defer; keep current policy unchanged"
        )
        notAuthorization = "operator review packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for exit-side experiment operator review packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$readinessScript = Join-Path $PSScriptRoot "prepare_exit_side_verified_experiment_readiness.ps1"
if (-not (Test-Path -LiteralPath $readinessScript)) {
    throw "Missing exit-side verified experiment readiness script: $readinessScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for exit-side experiment operator review packet." }

$readinessArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $readinessOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $readinessScript @readinessArgs 2>&1
    $readinessExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$readinessText = ($readinessOutput | Out-String -Width 4096)
$readinessJson = Get-LastPrefixedValue -Text $readinessText -Prefix "exit_side_verified_experiment_readiness_packet="
$readinessPacket = $null
if (-not [string]::IsNullOrWhiteSpace($readinessJson)) {
    $readinessPacket = $readinessJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($readinessExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "exit-side verified experiment readiness completed" }
if ($null -eq $readinessPacket) { Add-MissingRequirement -List $missingRequirements -Value "exit_side_verified_experiment_readiness_packet valid JSON" }

$readinessStatus = ""
$freshnessStatus = ""
$reviewItems = @()
if ($null -ne $readinessPacket) {
    $readinessStatus = [string]$readinessPacket.status
    $freshnessStatus = [string]$readinessPacket.sourceMatrixFreshnessStatus
    foreach ($plan in @($readinessPacket.experimentPlans)) {
        $reviewItems += New-ReviewItem -Plan $plan -CapUsdt $ReviewNotionalCapUsdt -Hours $ObservationHours
    }
}

if ($readinessStatus -ne "READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "exit-side verified experiment readiness ready"
}
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
foreach ($requiredPlan in @("trailing-stop-dry-run-readiness", "strategy485-risk-reduction-shadow-readiness")) {
    if (@($reviewItems | Where-Object { [string]$_.sourcePlanId -eq $requiredPlan }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "operator review item present: $requiredPlan"
    }
}
foreach ($item in @($reviewItems)) {
    if ([string]$item.status -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
        Add-MissingRequirement -List $missingRequirements -Value "operator review item ready: $($item.sourcePlanId)"
    }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this packet to operator review; approve only review-only dry-run/shadow design unless a later separate mutation authorization is granted."
} else {
    "Refresh read-only evidence and resolve missing requirements before attaching an exit-side experiment operator review packet."
}

$packet = [pscustomobject]@{
    packetType = "EXIT_SIDE_EXPERIMENT_OPERATOR_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_exit_side_verified_experiment_readiness.ps1"
    sourcePacketStatus = $readinessStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    livePolicyChangeAllowed = $false
    positionOrOcoMutationAllowed = $false
    deployOrEnvChangeAllowed = $false
    orderAllowed = $false
    reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
    observationHours = $ObservationHours
    reviewItemCount = @($reviewItems).Count
    reviewItems = @($reviewItems)
    blockedItems = if ($null -ne $readinessPacket) { @($readinessPacket.blockedItems) } else { @() }
    missingRequirements = @($missingRequirements)
    requiredOperatorAttestations = @(
        "review is read-only",
        "live trading remains disabled",
        "scheduler remains disabled or dry-run",
        "position/OCO mutation is not authorized",
        "deploy/env change is not authorized",
        "EntryDedup/DataFreshness/live policy remains unchanged"
    )
    nextAction = $nextAction
    notAuthorization = "read-only exit-side experiment operator review packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[exit-side-experiment-operator-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_exit_side_verified_experiment_readiness.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $readinessText
Write-Host "source_packet=prepare_exit_side_verified_experiment_readiness.ps1 exitCode=$readinessExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host "small_experiment_review_cap_usdt=$ReviewNotionalCapUsdt"
Write-Host "observation_hours=$ObservationHours"
Write-Host ("exit_side_experiment_operator_review_items=" + (ConvertTo-Json -Compress -Depth 8 @($reviewItems)))
Write-Host ("exit_side_experiment_operator_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("exit_side_experiment_operator_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "exit_side_experiment_operator_review_status=$status"
Write-Host "exit_side_experiment_operator_review_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only exit-side experiment operator review packet only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[exit-side-experiment-operator-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Exit-side experiment operator review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
