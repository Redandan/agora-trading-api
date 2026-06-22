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
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for exit-side experiment packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$summaryScript = Join-Path $PSScriptRoot "prepare_profit_operator_review_summary.ps1"
if (-not (Test-Path -LiteralPath $summaryScript)) {
    throw "Missing profit operator review summary script: $summaryScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for exit-side operator experiment packet."
}

$summaryArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $summaryOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $summaryScript @summaryArgs 2>&1
    $summaryExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$summaryText = ($summaryOutput | Out-String -Width 4096)
$summaryJson = Get-LastPrefixedValue -Text $summaryText -Prefix "profit_operator_review_summary_packet="
$summary = $null
if (-not [string]::IsNullOrWhiteSpace($summaryJson)) {
    $summary = $summaryJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($summaryExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "profit operator review summary completed"
}
if ($null -eq $summary) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_operator_review_summary_packet valid JSON"
}

$freshnessStatus = ""
$matrixAgeMinutes = ""
if ($null -ne $summary -and $null -ne $summary.PSObject.Properties["sourceMatrixFreshness"] -and $null -ne $summary.sourceMatrixFreshness) {
    $freshnessStatus = [string]$summary.sourceMatrixFreshness.Status
    $matrixAgeMinutes = [string]$summary.sourceMatrixFreshness.AgeMinutes
}

if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}

$readyExitSide = $false
$trailingProposal = $null
$strategy485Proposal = $null
if ($null -ne $summary) {
    foreach ($lane in @($summary.readyLanes)) {
        if ([string]$lane.lane -eq "exit-side" -and [string]$lane.decisionClass -eq "EXIT_SIDE_REVIEW_READY_NOT_LIVE") {
            $readyExitSide = $true
        }
    }
    foreach ($proposal in @($summary.exitSideActionProposals)) {
        if ([string]$proposal.proposalId -eq "trailing-stop-rollout-review") {
            $trailingProposal = $proposal
        } elseif ([string]$proposal.proposalId -eq "strategy485-risk-reduction-review") {
            $strategy485Proposal = $proposal
        }
    }
}

if (-not $readyExitSide) {
    Add-MissingRequirement -List $missingRequirements -Value "exit-side ready lane present"
}
if ($null -eq $trailingProposal) {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop-rollout-review proposal present"
}
if ($null -eq $strategy485Proposal) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485-risk-reduction-review proposal present"
}

$packetReady = $missingRequirements.Count -eq 0
$packetStatus = if ($packetReady) { "READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$packetNextAction = if ($packetReady) {
    "Attach this packet to a separate operator review; it permits only dry-run/shadow proposal review, not live execution."
} else {
    "Resolve missing read-only evidence, refresh the profit operator matrix, then rerun this packet."
}

$experimentProposals = @(
    [pscustomobject]@{
        proposalId = "trailing-stop-dry-run-experiment-review"
        sourceProposalId = "trailing-stop-rollout-review"
        experimentClass = "DRY_RUN_EXIT_POLICY_REVIEW_NOT_LIVE"
        status = if ($null -ne $trailingProposal -and $freshnessStatus -eq "FRESH") { "READY_TO_REVIEW_NOT_LIVE" } else { "NOT_READY" }
        allowedMode = "dry-run review only"
        objective = "Evaluate trailing-stop exit behavior against fresh replay evidence without scheduler, live trading, or OCO mutation."
        requiredFreshEvidence = if ($null -ne $trailingProposal) { @($trailingProposal.requiredFreshEvidence) } else { @("trailing-stop-rollout-review proposal present") }
        guardrails = @(
            "scheduler remains disabled or dry-run",
            "no live trading",
            "no OCO modification",
            "no deploy or production env change",
            "ambiguous same-bar rows remain excluded"
        )
        successEvidence = @(
            "operator accepts dry-run scope",
            "fresh matrix remains FRESH",
            "trailing_stop_acceptance remains PASS",
            "review records exact strategy opt-in scope without enabling it"
        )
        notAuthorization = "does not authorize live trailing, scheduler enablement, OCO modification, deploy, or production env changes"
    },
    [pscustomobject]@{
        proposalId = "strategy485-risk-reduction-shadow-review"
        sourceProposalId = "strategy485-risk-reduction-review"
        experimentClass = "SHADOW_RISK_REDUCTION_REVIEW_NOT_MUTATION"
        status = if ($null -ne $strategy485Proposal -and $freshnessStatus -eq "FRESH") { "READY_TO_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
        allowedMode = "shadow decision review only"
        objective = "Review aged negative-EV strategy 485 position risk with fresh OCO/EV/TP-stretch context before any later action approval."
        requiredFreshEvidence = if ($null -ne $strategy485Proposal) { @($strategy485Proposal.requiredFreshEvidence) } else { @("strategy485-risk-reduction-review proposal present") }
        guardrails = @(
            "no close-position action",
            "no OCO modification or cancellation",
            "no new order",
            "no deploy or production env change",
            "operator approval required for each later mutation"
        )
        successEvidence = @(
            "fresh matrix remains FRESH",
            "strategy485_oco_health_ok remains true",
            "negative-EV and close-or-modify suggestion counts remain reviewable",
            "monthly and recent-closed PnL context is attached"
        )
        notAuthorization = "does not authorize close-position, OCO modification, orders, deploy, or production env changes"
    }
)

$packet = [pscustomobject]@{
    packetType = "EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    strategyId = $StrategyId
    sourceSummary = "prepare_profit_operator_review_summary.ps1"
    sourceMatrixMode = if ($null -ne $summary) { [string]$summary.sourceMatrixMode } else { "" }
    sourceMatrixOutputPath = if ($null -ne $summary) { [string]$summary.sourceMatrixOutputPath } else { "" }
    sourceMatrixFreshness = if ($null -ne $summary) { $summary.sourceMatrixFreshness } else { $null }
    readyLaneCount = if ($null -ne $summary) { [int]$summary.readyLaneCount } else { 0 }
    blockedLaneCount = if ($null -ne $summary) { [int]$summary.blockedLaneCount } else { 0 }
    experimentProposals = @($experimentProposals)
    blockedLanes = if ($null -ne $summary) { @($summary.blockedLanes) } else { @() }
    missingRequirements = @($missingRequirements)
    allowedOutput = @(
        "operator-reviewable trailing dry-run proposal",
        "operator-reviewable strategy 485 shadow risk-reduction review"
    )
    forbiddenActions = @(
        "enable live trading",
        "enable trailing scheduler",
        "close positions",
        "modify or cancel OCO",
        "place orders",
        "relax EntryDedup/DataFreshness/live policy",
        "deploy or change production env",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    nextAction = $packetNextAction
    notAuthorization = "read-only exit-side experiment packet only; does not authorize live trading, scheduler enablement, position close, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[exit-side-operator-experiment-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_review_summary.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $summaryText
Write-Host "source_summary=prepare_profit_operator_review_summary.ps1 exitCode=$summaryExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host "source_matrix_age_minutes=$matrixAgeMinutes"
Write-Host ("exit_side_experiment_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("exit_side_experiment_proposals=" + (ConvertTo-Json -Compress -Depth 8 @($experimentProposals)))
Write-Host ("exit_side_operator_experiment_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "exit_side_operator_experiment_packet_status=$packetStatus"
Write-Host "exit_side_operator_experiment_packet_next_action=$packetNextAction"
Write-Host "notAuthorization=read-only exit-side experiment packet only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[exit-side-operator-experiment-packet] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Exit-side operator experiment packet is not ready: $packetStatus; missing=$(@($missingRequirements) -join '; ')"
}
