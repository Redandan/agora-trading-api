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

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator consolidated review packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$exitSideScript = Join-Path $PSScriptRoot "prepare_exit_side_experiment_operator_review_packet.ps1"
if (-not (Test-Path -LiteralPath $exitSideScript)) {
    throw "Missing exit-side experiment operator review packet script: $exitSideScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit operator consolidated review packet." }

$exitArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $exitOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $exitSideScript @exitArgs 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$exitText = ($exitOutput | Out-String -Width 4096)
$exitJson = Get-LastPrefixedValue -Text $exitText -Prefix "exit_side_experiment_operator_review_packet="
$exitPacket = $null
if (-not [string]::IsNullOrWhiteSpace($exitJson)) {
    $exitPacket = $exitJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($exitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "exit-side experiment operator review packet completed" }
if ($null -eq $exitPacket) { Add-MissingRequirement -List $missingRequirements -Value "exit_side_experiment_operator_review_packet valid JSON" }

$exitStatus = ""
$freshnessStatus = ""
$readyReviewItems = @()
$blockedPolicyLanes = @()
if ($null -ne $exitPacket) {
    $exitStatus = [string]$exitPacket.status
    $freshnessStatus = [string]$exitPacket.sourceMatrixFreshnessStatus
    $readyReviewItems = @($exitPacket.reviewItems | Where-Object { [string]$_.status -eq "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" })
    $blockedPolicyLanes = @($exitPacket.blockedItems)
}

if ($exitStatus -ne "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "exit-side operator review packet ready"
}
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
foreach ($requiredReviewItem in @("trailing-stop-dry-run-operator-review", "strategy485-risk-reduction-shadow-operator-review")) {
    if (@($readyReviewItems | Where-Object { [string]$_.proposalId -eq $requiredReviewItem }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "ready review item present: $requiredReviewItem"
    }
}
foreach ($requiredBlockedLane in @("entry-filter", "data-freshness-replay")) {
    if (@($blockedPolicyLanes | Where-Object { [string]$_.lane -eq $requiredBlockedLane }).Count -lt 1) {
        Add-MissingRequirement -List $missingRequirements -Value "blocked policy lane present: $requiredBlockedLane"
    }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_OPERATOR_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Review the ready exit-side dry-run/shadow items and keep blocked policy lanes unchanged until separate evidence and authorization exist."
} else {
    "Refresh read-only evidence and resolve missing requirements before using the consolidated operator review packet."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_CONSOLIDATED_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePacket = "prepare_exit_side_experiment_operator_review_packet.ps1"
    sourcePacketStatus = $exitStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
    observationHours = $ObservationHours
    readyReviewItemCount = @($readyReviewItems).Count
    blockedPolicyLaneCount = @($blockedPolicyLanes).Count
    readyReviewItems = @($readyReviewItems)
    blockedPolicyLanes = @($blockedPolicyLanes)
    operatorReviewChecklist = @(
        "review ready exit-side items only",
        "keep entry-filter and DataFreshness policy unchanged",
        "treat blocked lanes as evidence collection work, not approval",
        "require separate authorization for any live, order, OCO, close-position, scheduler, deploy, env, or policy mutation"
    )
    allowedActions = @(
        "operator review",
        "read-only evidence refresh",
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
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only consolidated profit operator review packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[profit-operator-consolidated-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_exit_side_experiment_operator_review_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $exitText
Write-Host "source_packet=prepare_exit_side_experiment_operator_review_packet.ps1 exitCode=$exitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host ("profit_operator_consolidated_ready_items=" + (ConvertTo-Json -Compress -Depth 8 @($readyReviewItems)))
Write-Host ("profit_operator_consolidated_blocked_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($blockedPolicyLanes)))
Write-Host ("profit_operator_consolidated_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_operator_consolidated_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "profit_operator_consolidated_review_status=$status"
Write-Host "profit_operator_consolidated_review_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only consolidated profit operator review packet only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[profit-operator-consolidated-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Profit operator consolidated review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
