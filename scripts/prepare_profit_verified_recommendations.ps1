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
    throw "Symbol contains unsupported characters for verified recommendations arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$packetScript = Join-Path $PSScriptRoot "prepare_exit_side_operator_experiment_packet.ps1"
if (-not (Test-Path -LiteralPath $packetScript)) {
    throw "Missing exit-side operator experiment packet script: $packetScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for profit verified recommendations."
}

$packetArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $packetOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $packetScript @packetArgs 2>&1
    $packetExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$packetText = ($packetOutput | Out-String -Width 4096)
$packetJson = Get-LastPrefixedValue -Text $packetText -Prefix "exit_side_operator_experiment_packet="
$packet = $null
if (-not [string]::IsNullOrWhiteSpace($packetJson)) {
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($packetExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "exit-side operator experiment packet completed"
}
if ($null -eq $packet) {
    Add-MissingRequirement -List $missingRequirements -Value "exit_side_operator_experiment_packet valid JSON"
}

$readyRecommendations = @()
$blockedItems = @()
$freshnessStatus = ""
$expectedRecommendationIds = @(
    "trailing-stop-dry-run-experiment-review",
    "strategy485-risk-reduction-shadow-review"
)
if ($null -ne $packet) {
    if ($null -ne $packet.PSObject.Properties["sourceMatrixFreshness"] -and $null -ne $packet.sourceMatrixFreshness) {
        $freshnessStatus = [string]$packet.sourceMatrixFreshness.Status
    }
    foreach ($proposal in @($packet.experimentProposals)) {
        $readyRecommendations += [pscustomobject]@{
            recommendationId = [string]$proposal.proposalId
            sourceProposalId = [string]$proposal.sourceProposalId
            class = [string]$proposal.experimentClass
            status = [string]$proposal.status
            allowedMode = [string]$proposal.allowedMode
            objective = [string]$proposal.objective
            requiredFreshEvidence = @($proposal.requiredFreshEvidence)
            successEvidence = @($proposal.successEvidence)
            guardrails = @($proposal.guardrails)
            notAuthorization = [string]$proposal.notAuthorization
        }
    }
    foreach ($lane in @($packet.blockedLanes)) {
        $blockedItems += [pscustomobject]@{
            lane = [string]$lane.lane
            priority = [string]$lane.priority
            status = [string]$lane.status
            decisionClass = [string]$lane.decisionClass
            missingRequirements = @($lane.missingRequirements)
            nextAction = [string]$lane.nextAction
        }
    }
}

if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if (@($readyRecommendations).Count -lt 1) {
    Add-MissingRequirement -List $missingRequirements -Value "at least one ready recommendation"
}
foreach ($expectedRecommendationId in $expectedRecommendationIds) {
    if (@($readyRecommendations | Where-Object { $_.recommendationId -eq $expectedRecommendationId }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "ready recommendation present: $expectedRecommendationId"
    }
}

$packetReady = $missingRequirements.Count -eq 0
$status = if ($packetReady) { "READY_WITH_REVIEW_ONLY_RECOMMENDATIONS" } else { "NOT_READY" }
$nextAction = if ($packetReady) {
    "Review the ready recommendations with an operator; keep blocked lanes unchanged and require separate authorization for any live, order, OCO, scheduler, deploy, or env action."
} else {
    "Refresh read-only evidence and resolve missing requirements before using these recommendations."
}

$recommendations = [pscustomobject]@{
    packetType = "PROFIT_VERIFIED_RECOMMENDATIONS"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_exit_side_operator_experiment_packet.ps1"
    sourcePacketStatus = if ($null -ne $packet) { [string]$packet.status } else { "" }
    sourceMatrixMode = if ($null -ne $packet) { [string]$packet.sourceMatrixMode } else { "" }
    sourceMatrixOutputPath = if ($null -ne $packet) { [string]$packet.sourceMatrixOutputPath } else { "" }
    sourceMatrixFreshness = if ($null -ne $packet) { $packet.sourceMatrixFreshness } else { $null }
    readyRecommendationCount = @($readyRecommendations).Count
    blockedItemCount = @($blockedItems).Count
    expectedRecommendationIds = @($expectedRecommendationIds)
    readyRecommendations = @($readyRecommendations)
    blockedItems = @($blockedItems)
    missingRequirements = @($missingRequirements)
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
    nextAction = $nextAction
    notAuthorization = "read-only verified recommendations only; does not authorize live trading, scheduler enablement, position close, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[profit-verified-recommendations] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_exit_side_operator_experiment_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $packetText
Write-Host "source_packet=prepare_exit_side_operator_experiment_packet.ps1 exitCode=$packetExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host ("profit_verified_ready_recommendations=" + (ConvertTo-Json -Compress -Depth 8 @($readyRecommendations)))
Write-Host ("profit_verified_blocked_items=" + (ConvertTo-Json -Compress -Depth 8 @($blockedItems)))
Write-Host ("profit_verified_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_verified_recommendations_packet=" + (ConvertTo-Json -Compress -Depth 10 $recommendations))
Write-Host "profit_verified_recommendations_status=$status"
Write-Host "profit_verified_recommendations_next_action=$nextAction"
Write-Host "notAuthorization=read-only verified recommendations only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[profit-verified-recommendations] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Profit verified recommendations are not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
