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

function Get-ReviewItem {
    param([object[]]$Items, [string]$ProposalId)
    $match = @($Items | Where-Object { [string]$_.proposalId -eq $ProposalId } | Select-Object -First 1)
    if ($match.Count -lt 1) { return $null }
    return $match[0]
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for trailing-stop dry-run operator decision packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$priorityScript = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$exitSideScript = Join-Path $PSScriptRoot "prepare_exit_side_experiment_operator_review_packet.ps1"
foreach ($script in @($priorityScript, $exitSideScript)) {
    if (-not (Test-Path -LiteralPath $script)) { throw "Missing read-only source script: $script" }
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for trailing-stop dry-run operator decision packet." }

$commonArgs = @(
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
    $priorityOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $priorityScript @commonArgs 2>&1
    $priorityExitCode = $LASTEXITCODE
    $exitSideOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $exitSideScript @commonArgs 2>&1
    $exitSideExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$priorityText = ($priorityOutput | Out-String -Width 4096)
$exitSideText = ($exitSideOutput | Out-String -Width 4096)
$priorityJson = Get-LastPrefixedValue -Text $priorityText -Prefix "profit_operator_priority_decision_brief_packet="
$exitSideJson = Get-LastPrefixedValue -Text $exitSideText -Prefix "exit_side_experiment_operator_review_packet="

$priorityPacket = $null
$exitSidePacket = $null
if (-not [string]::IsNullOrWhiteSpace($priorityJson)) {
    $priorityPacket = $priorityJson | ConvertFrom-Json -ErrorAction Stop
}
if (-not [string]::IsNullOrWhiteSpace($exitSideJson)) {
    $exitSidePacket = $exitSideJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($priorityExitCode -ne 0 -and $null -eq $priorityPacket) { Add-MissingRequirement -List $missingRequirements -Value "priority decision brief completed" }
if ($exitSideExitCode -ne 0 -and $null -eq $exitSidePacket) { Add-MissingRequirement -List $missingRequirements -Value "exit-side experiment operator review packet completed" }
if ($null -eq $priorityPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_priority_decision_brief_packet valid JSON" }
if ($null -eq $exitSidePacket) { Add-MissingRequirement -List $missingRequirements -Value "exit_side_experiment_operator_review_packet valid JSON" }

$priorityStatus = ""
$exitSideStatus = ""
$freshnessStatus = ""
$primaryFocus = ""
$trailingPriorityItem = $null
$trailingReviewItem = $null
if ($null -ne $priorityPacket) {
    $priorityStatus = [string]$priorityPacket.status
    $freshnessStatus = [string]$priorityPacket.sourceMatrixFreshnessStatus
    $primaryFocus = [string]$priorityPacket.primaryFocus
    $trailingPriorityItem = Get-ReviewItem -Items @($priorityPacket.rankedReviewItems) -ProposalId "trailing-stop-dry-run-operator-review"
}
if ($null -ne $exitSidePacket) {
    $exitSideStatus = [string]$exitSidePacket.status
    $trailingReviewItem = Get-ReviewItem -Items @($exitSidePacket.reviewItems) -ProposalId "trailing-stop-dry-run-operator-review"
}

if ($priorityStatus -ne "READY_FOR_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "priority decision brief ready"
}
if ($exitSideStatus -ne "READY_FOR_OPERATOR_REVIEW_PACKET_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "exit-side operator review packet ready"
}
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($primaryFocus -ne "trailing-stop-dry-run-operator-review") {
    Add-MissingRequirement -List $missingRequirements -Value "priority primary focus is trailing-stop-dry-run-operator-review"
}
if ($null -eq $trailingPriorityItem) {
    Add-MissingRequirement -List $missingRequirements -Value "trailing priority item present"
}
if ($null -eq $trailingReviewItem) {
    Add-MissingRequirement -List $missingRequirements -Value "trailing review item present"
} elseif ([string]$trailingReviewItem.status -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing review item ready"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this trailing-stop dry-run decision packet to operator review; keep scheduler/live/OCO/order/deploy disabled unless a later separate authorization exists."
} else {
    "Refresh read-only profit evidence and resolve missing requirements before using the trailing-stop dry-run decision packet."
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_DRY_RUN_OPERATOR_DECISION_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePriorityPacket = "prepare_profit_operator_priority_decision_brief.ps1"
    sourcePriorityPacketStatus = $priorityStatus
    sourceExitSidePacket = "prepare_exit_side_experiment_operator_review_packet.ps1"
    sourceExitSidePacketStatus = $exitSideStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    primaryFocus = $primaryFocus
    proposalId = "trailing-stop-dry-run-operator-review"
    reviewQuestion = if ($null -ne $trailingReviewItem) { [string]$trailingReviewItem.reviewQuestion } else { "Approve review-only trailing-stop dry-run design?" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        liveTradingAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
        policyRelaxationAllowed = $false
    }
    evidenceChecklist = @(
        "source matrix freshness is FRESH",
        "priority primary focus is trailing-stop-dry-run-operator-review",
        "trailing review item is READY_FOR_OPERATOR_REVIEW_NOT_LIVE",
        "trailing_stop_acceptance=PASS attached through exit-side evidence",
        "accepted rows, improvement pct, delta PnL, and ambiguous same-bar exclusion are attached through exit-side evidence",
        "scheduler remains disabled or dry-run until separately authorized"
    )
    operatorDecisionChoices = @(
        "approve review-only trailing dry-run packet",
        "request fresh read-only production evidence",
        "reject or defer and keep current exit policy unchanged"
    )
    requiredSeparateAuthorization = @(
        "enable trailing scheduler or live trailing mode",
        "change strategy opt-in or exit policy",
        "place orders",
        "modify or cancel OCO",
        "deploy or production env change"
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
    trailingPriorityItem = $trailingPriorityItem
    trailingReviewItem = $trailingReviewItem
    blockedPolicyLanes = if ($null -ne $priorityPacket) { @($priorityPacket.blockedPolicyLanes) } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only trailing-stop dry-run operator decision packet only; does not authorize live trading, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[trailing-stop-dry-run-operator-decision-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_priority_decision_brief.ps1 and prepare_exit_side_experiment_operator_review_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $priorityText
Write-Host $exitSideText
Write-Host "source_priority_packet=prepare_profit_operator_priority_decision_brief.ps1 exitCode=$priorityExitCode"
Write-Host "source_exit_side_packet=prepare_exit_side_experiment_operator_review_packet.ps1 exitCode=$exitSideExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host "trailing_stop_dry_run_primary_focus=$primaryFocus"
Write-Host ("trailing_stop_dry_run_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("trailing_stop_dry_run_operator_decision_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_dry_run_operator_decision_status=$status"
Write-Host "trailing_stop_dry_run_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only trailing-stop dry-run operator decision packet only; does not authorize live trading, scheduler enablement, closing positions, OCO modification, orders, EntryDedup/DataFreshness/live policy relaxation, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[trailing-stop-dry-run-operator-decision-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop dry-run operator decision packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
