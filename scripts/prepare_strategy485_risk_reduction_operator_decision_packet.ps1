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
    throw "Symbol contains unsupported characters for strategy485 risk-reduction operator decision packet arguments."
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
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy485 risk-reduction operator decision packet." }

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
$strategy485PriorityItem = $null
$strategy485ReviewItem = $null
if ($null -ne $priorityPacket) {
    $priorityStatus = [string]$priorityPacket.status
    $freshnessStatus = [string]$priorityPacket.sourceMatrixFreshnessStatus
    $strategy485PriorityItem = Get-ReviewItem -Items @($priorityPacket.rankedReviewItems) -ProposalId "strategy485-risk-reduction-shadow-operator-review"
}
if ($null -ne $exitSidePacket) {
    $exitSideStatus = [string]$exitSidePacket.status
    $strategy485ReviewItem = Get-ReviewItem -Items @($exitSidePacket.reviewItems) -ProposalId "strategy485-risk-reduction-shadow-operator-review"
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
if ($null -eq $strategy485PriorityItem) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485 priority item present"
} else {
    if ([int]$strategy485PriorityItem.rank -ne 2) {
        Add-MissingRequirement -List $missingRequirements -Value "strategy485 priority item rank is 2"
    }
}
if ($null -eq $strategy485ReviewItem) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485 review item present"
} elseif ([string]$strategy485ReviewItem.status -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485 review item ready"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this strategy485 risk-reduction shadow decision packet to operator review; keep close-position/OCO/order/deploy disabled unless a later separate mutation authorization exists."
} else {
    "Refresh read-only profit evidence and resolve missing requirements before using the strategy485 risk-reduction decision packet."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourcePriorityPacket = "prepare_profit_operator_priority_decision_brief.ps1"
    sourcePriorityPacketStatus = $priorityStatus
    sourceExitSidePacket = "prepare_exit_side_experiment_operator_review_packet.ps1"
    sourceExitSidePacketStatus = $exitSideStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    proposalId = "strategy485-risk-reduction-shadow-operator-review"
    priorityRank = if ($null -ne $strategy485PriorityItem) { [int]$strategy485PriorityItem.rank } else { 0 }
    reviewQuestion = if ($null -ne $strategy485ReviewItem) { [string]$strategy485ReviewItem.reviewQuestion } else { "Approve review-only strategy485 risk-reduction shadow review?" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        liveTradingAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        closePositionAllowed = $false
        deployOrEnvChangeAllowed = $false
        policyRelaxationAllowed = $false
    }
    evidenceChecklist = @(
        "source matrix freshness is FRESH",
        "strategy485 priority rank is 2",
        "strategy485 review item is READY_FOR_OPERATOR_REVIEW_NOT_LIVE",
        "strategy485_oco_health_ok evidence is attached through exit-side evidence",
        "negative-EV position count and close-or-modify suggestion count are attached through exit-side evidence",
        "close-position and OCO mutation remain forbidden until separately authorized"
    )
    operatorDecisionChoices = @(
        "approve review-only strategy485 risk shadow packet",
        "request fresh read-only production evidence",
        "reject or defer and keep current position/OCO state unchanged"
    )
    requiredSeparateAuthorization = @(
        "close any strategy 485 position",
        "modify or cancel OCO",
        "place orders",
        "change risk-reduction policy",
        "deploy or production env change"
    )
    forbiddenActions = @(
        "close positions",
        "modify or cancel OCO",
        "place orders",
        "enable live trading",
        "enable scheduler",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    strategy485PriorityItem = $strategy485PriorityItem
    strategy485ReviewItem = $strategy485ReviewItem
    blockedPolicyLanes = if ($null -ne $priorityPacket) { @($priorityPacket.blockedPolicyLanes) } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy485 risk-reduction operator decision packet only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[strategy485-risk-reduction-operator-decision-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_priority_decision_brief.ps1 and prepare_exit_side_experiment_operator_review_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $priorityText
Write-Host $exitSideText
Write-Host "source_priority_packet=prepare_profit_operator_priority_decision_brief.ps1 exitCode=$priorityExitCode"
Write-Host "source_exit_side_packet=prepare_exit_side_experiment_operator_review_packet.ps1 exitCode=$exitSideExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host "strategy485_risk_reduction_priority_rank=$($packet.priorityRank)"
Write-Host ("strategy485_risk_reduction_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy485_risk_reduction_operator_decision_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "strategy485_risk_reduction_operator_decision_status=$status"
Write-Host "strategy485_risk_reduction_next_action=$nextAction"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "close_position_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only strategy485 risk-reduction operator decision packet only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env changes, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[strategy485-risk-reduction-operator-decision-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy485 risk-reduction operator decision packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
