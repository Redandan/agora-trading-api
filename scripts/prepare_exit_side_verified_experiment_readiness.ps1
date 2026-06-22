param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
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

function Get-RecommendationById {
    param([object[]]$Recommendations, [string]$RecommendationId)
    return @($Recommendations | Where-Object { [string]$_.recommendationId -eq $RecommendationId } | Select-Object -First 1)
}

function New-ExperimentPlan {
    param(
        [object]$Recommendation,
        [string]$PlanId,
        [string]$Lane,
        [string]$Mode,
        [string]$Objective,
        [string[]]$MinimumEvidence,
        [string[]]$SuccessEvidence,
        [string[]]$StopCriteria
    )

    $recommendationId = if ($null -ne $Recommendation) { [string]$Recommendation.recommendationId } else { "" }
    $recommendationStatus = if ($null -ne $Recommendation) { [string]$Recommendation.status } else { "MISSING" }
    $ready = $null -ne $Recommendation -and $recommendationStatus -like "READY*"

    return [pscustomobject]@{
        planId = $PlanId
        lane = $Lane
        sourceRecommendationId = $recommendationId
        mode = $Mode
        status = if ($ready) { "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
        objective = $Objective
        minimumEvidence = @($MinimumEvidence)
        inheritedRequiredFreshEvidence = if ($null -ne $Recommendation) { @($Recommendation.requiredFreshEvidence) } else { @() }
        successEvidence = @($SuccessEvidence)
        stopCriteria = @($StopCriteria)
        allowedActions = @(
            "operator review",
            "read-only replay/evidence refresh",
            "shadow or dry-run design discussion"
        )
        forbiddenActions = @(
            "enable live trading",
            "enable scheduler",
            "place orders",
            "modify or cancel OCO",
            "close positions",
            "change production env",
            "deploy",
            "relax EntryDedup/DataFreshness/live policy",
            "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
        )
        notAuthorization = "review-only experiment readiness; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for exit-side verified experiment readiness arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }

$recommendationsScript = Join-Path $PSScriptRoot "prepare_profit_verified_recommendations.ps1"
if (-not (Test-Path -LiteralPath $recommendationsScript)) {
    throw "Missing profit verified recommendations script: $recommendationsScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for exit-side verified experiment readiness." }

$recommendationArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $recommendationOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $recommendationsScript @recommendationArgs 2>&1
    $recommendationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$recommendationText = ($recommendationOutput | Out-String -Width 4096)
$recommendationJson = Get-LastPrefixedValue -Text $recommendationText -Prefix "profit_verified_recommendations_packet="
$recommendationPacket = $null
if (-not [string]::IsNullOrWhiteSpace($recommendationJson)) {
    $recommendationPacket = $recommendationJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($recommendationExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "profit verified recommendations completed" }
if ($null -eq $recommendationPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_verified_recommendations_packet valid JSON" }

$recommendationsStatus = ""
$freshnessStatus = ""
$readyRecommendations = @()
if ($null -ne $recommendationPacket) {
    $recommendationsStatus = [string]$recommendationPacket.status
    if ($null -ne $recommendationPacket.PSObject.Properties["sourceMatrixFreshness"] -and $null -ne $recommendationPacket.sourceMatrixFreshness) {
        $freshnessStatus = [string]$recommendationPacket.sourceMatrixFreshness.Status
    }
    $readyRecommendations = @($recommendationPacket.readyRecommendations)
}

if ($recommendationsStatus -ne "READY_WITH_REVIEW_ONLY_RECOMMENDATIONS") {
    Add-MissingRequirement -List $missingRequirements -Value "profit verified recommendations ready"
}
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}

$trailingRecommendationMatch = Get-RecommendationById -Recommendations @($readyRecommendations) -RecommendationId "trailing-stop-dry-run-experiment-review"
$strategy485RecommendationMatch = Get-RecommendationById -Recommendations @($readyRecommendations) -RecommendationId "strategy485-risk-reduction-shadow-review"
$trailingRecommendation = if (@($trailingRecommendationMatch).Count -gt 0) { $trailingRecommendationMatch[0] } else { $null }
$strategy485Recommendation = if (@($strategy485RecommendationMatch).Count -gt 0) { $strategy485RecommendationMatch[0] } else { $null }
if ($null -eq $trailingRecommendation) { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop dry-run recommendation present" }
if ($null -eq $strategy485Recommendation) { Add-MissingRequirement -List $missingRequirements -Value "strategy485 shadow recommendation present" }

$experimentPlans = @(
    (New-ExperimentPlan `
        -Recommendation $trailingRecommendation `
        -PlanId "trailing-stop-dry-run-readiness" `
        -Lane "trailing-stop-dry-run" `
        -Mode "DRY_RUN_REVIEW_ONLY" `
        -Objective "Review whether trailing-stop dry-run should be proposed from fresh replay evidence without enabling the scheduler or modifying OCO." `
        -MinimumEvidence @("fresh profit operator matrix", "trailing_stop_acceptance=PASS", "accepted non-ambiguous replay rows", "ambiguous same-bar rows excluded", "operator records exact strategy/symbol scope before any later rollout") `
        -SuccessEvidence @("dry-run scope accepted by operator", "scheduler remains disabled or dry-run", "no live OCO/order action occurs", "post-review packet still prints live_policy_change_allowed=false") `
        -StopCriteria @("trailing_stop_acceptance not PASS", "fresh matrix becomes stale", "same-bar ambiguity dominates accepted sample", "any request attempts live scheduler/OCO/order mutation")),
    (New-ExperimentPlan `
        -Recommendation $strategy485Recommendation `
        -PlanId "strategy485-risk-reduction-shadow-readiness" `
        -Lane "strategy485-risk-reduction-shadow" `
        -Mode "SHADOW_REVIEW_ONLY" `
        -Objective "Review strategy 485 aged negative-EV position risk as a shadow decision before any close-position or OCO approval path." `
        -MinimumEvidence @("fresh profit operator matrix", "current OCO health attached", "negative-EV position count attached", "close-or-modify suggestion count attached", "monthly and recent-closed PnL context attached") `
        -SuccessEvidence @("operator can inspect per-position risk without mutation", "close-position and OCO modification remain separate approvals", "shadow recommendation records exact position scope", "post-review packet still prints position_or_oco_mutation_allowed=false") `
        -StopCriteria @("OCO health evidence missing", "fresh matrix becomes stale", "negative-EV position evidence is unavailable", "any request attempts close-position, OCO, or order mutation"))
)

foreach ($plan in @($experimentPlans)) {
    if ([string]$plan.status -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
        Add-MissingRequirement -List $missingRequirements -Value "experiment plan ready: $($plan.planId)"
    }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_EXIT_SIDE_DRY_RUN_AND_SHADOW_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this readiness packet to an operator review; any live, scheduler, OCO, order, deploy, env, or policy action still requires a separate authorization."
} else {
    "Refresh read-only evidence and resolve missing requirements before drafting exit-side dry-run or shadow experiment review."
}

$readinessPacket = [pscustomobject]@{
    packetType = "EXIT_SIDE_VERIFIED_EXPERIMENT_READINESS"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_profit_verified_recommendations.ps1"
    sourcePacketStatus = $recommendationsStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    livePolicyChangeAllowed = $false
    positionOrOcoMutationAllowed = $false
    deployOrEnvChangeAllowed = $false
    experimentPlanCount = @($experimentPlans).Count
    experimentPlans = @($experimentPlans)
    blockedItems = if ($null -ne $recommendationPacket) { @($recommendationPacket.blockedItems) } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only exit-side verified experiment readiness only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[exit-side-verified-experiment-readiness] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_profit_verified_recommendations.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $recommendationText
Write-Host "source_packet=prepare_profit_verified_recommendations.ps1 exitCode=$recommendationExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host ("exit_side_verified_experiment_plans=" + (ConvertTo-Json -Compress -Depth 8 @($experimentPlans)))
Write-Host ("exit_side_verified_experiment_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("exit_side_verified_experiment_readiness_packet=" + (ConvertTo-Json -Compress -Depth 10 $readinessPacket))
Write-Host "exit_side_verified_experiment_readiness_status=$status"
Write-Host "exit_side_verified_experiment_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "notAuthorization=read-only exit-side verified experiment readiness only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[exit-side-verified-experiment-readiness] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Exit-side verified experiment readiness is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
