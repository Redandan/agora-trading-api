param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$PriorityStrategyId = 485,
    [int]$EntryDedupStrategyId = 508,
    [string]$IntervalCode = "1h",
    [decimal]$TakeProfitPct = 1.00,
    [decimal]$StopLossPct = 1.00,
    [decimal]$RoundTripFeePct = 0.20,
    [decimal]$ReviewNotionalCapUsdt = 10,
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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for EntryDedup semantics operator decision packet arguments."
    }
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) { throw "ReviewOutputDir is required." }
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) { throw "MatrixMaxAgeMinutes must be between 1 and 1440." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
if ($PriorityStrategyId -lt 1 -or $PriorityStrategyId -gt 1000000) { throw "PriorityStrategyId must be between 1 and 1000000." }
if ($EntryDedupStrategyId -lt 1 -or $EntryDedupStrategyId -gt 1000000) { throw "EntryDedupStrategyId must be between 1 and 1000000." }
if ($TakeProfitPct -le 0 -or $TakeProfitPct -gt 20) { throw "TakeProfitPct must be greater than 0 and at most 20." }
if ($StopLossPct -le 0 -or $StopLossPct -gt 20) { throw "StopLossPct must be greater than 0 and at most 20." }
if ($RoundTripFeePct -lt 0 -or $RoundTripFeePct -gt 2) { throw "RoundTripFeePct must be between 0 and 2." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$priorityScript = Join-Path $PSScriptRoot "prepare_profit_operator_priority_decision_brief.ps1"
$entryDedupScript = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
foreach ($script in @($priorityScript, $entryDedupScript)) {
    if (-not (Test-Path -LiteralPath $script)) { throw "Missing read-only source script: $script" }
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for EntryDedup semantics operator decision packet." }

$priorityArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$PriorityStrategyId",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$entryDedupArgs = @(
    "-Symbol", $Symbol,
    "-StrategyId", "$EntryDedupStrategyId",
    "-IntervalCode", $IntervalCode,
    "-TakeProfitPct", "$TakeProfitPct",
    "-StopLossPct", "$StopLossPct",
    "-RoundTripFeePct", "$RoundTripFeePct",
    "-ReviewNotionalCapUsdt", "$ReviewNotionalCapUsdt",
    "-ObservationHours", "$ObservationHours",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $priorityOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $priorityScript @priorityArgs 2>&1
    $priorityExitCode = $LASTEXITCODE
    $entryDedupOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $entryDedupScript @entryDedupArgs 2>&1
    $entryDedupExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$priorityText = ($priorityOutput | Out-String -Width 4096)
$entryDedupText = ($entryDedupOutput | Out-String -Width 4096)
$priorityJson = Get-LastPrefixedValue -Text $priorityText -Prefix "profit_operator_priority_decision_brief_packet="
$entryDedupJson = Get-LastPrefixedValue -Text $entryDedupText -Prefix "entry_dedup_semantics_shadow_experiment_packet="

$priorityPacket = $null
$entryDedupPacket = $null
if (-not [string]::IsNullOrWhiteSpace($priorityJson)) {
    $priorityPacket = $priorityJson | ConvertFrom-Json -ErrorAction Stop
}
if (-not [string]::IsNullOrWhiteSpace($entryDedupJson)) {
    $entryDedupPacket = $entryDedupJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($priorityExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "priority decision brief completed" }
if ($entryDedupExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics shadow experiment packet completed" }
if ($null -eq $priorityPacket) { Add-MissingRequirement -List $missingRequirements -Value "profit_operator_priority_decision_brief_packet valid JSON" }
if ($null -eq $entryDedupPacket) { Add-MissingRequirement -List $missingRequirements -Value "entry_dedup_semantics_shadow_experiment_packet valid JSON" }

$priorityStatus = ""
$freshnessStatus = ""
$entryDedupPriorityItem = $null
if ($null -ne $priorityPacket) {
    $priorityStatus = [string]$priorityPacket.status
    $freshnessStatus = [string]$priorityPacket.sourceMatrixFreshnessStatus
    $entryDedupPriorityItem = Get-ReviewItem -Items @($priorityPacket.rankedReviewItems) -ProposalId "entry-dedup-semantics-shadow-operator-review"
}

$entryDedupStatus = ""
if ($null -ne $entryDedupPacket) {
    $entryDedupStatus = [string]$entryDedupPacket.status
}

if ($priorityStatus -ne "READY_FOR_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "priority decision brief ready"
}
if ($entryDedupStatus -ne "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics shadow experiment packet ready"
}
if ($freshnessStatus -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($null -eq $entryDedupPriorityItem) {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup priority item present"
} else {
    if ([int]$entryDedupPriorityItem.rank -ne 3) {
        Add-MissingRequirement -List $missingRequirements -Value "EntryDedup priority item rank is 3"
    }
}

$summary = if ($null -ne $entryDedupPacket -and $null -ne $entryDedupPacket.sourceEvidenceSummary) { $entryDedupPacket.sourceEvidenceSummary } else { $null }
$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this EntryDedup semantics shadow decision packet to operator review; keep EntryDedup/DataFreshness/live policy and orders unchanged unless a later separate mutation authorization exists."
} else {
    "Refresh read-only profit evidence and resolve missing requirements before using the EntryDedup semantics operator decision packet."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_PACKET"
    status = $status
    symbol = $Symbol
    priorityStrategyId = $PriorityStrategyId
    entryDedupStrategyId = $EntryDedupStrategyId
    intervalCode = $IntervalCode
    sourcePriorityPacket = "prepare_profit_operator_priority_decision_brief.ps1"
    sourcePriorityPacketStatus = $priorityStatus
    sourceEntryDedupPacket = "prepare_entry_dedup_semantics_shadow_experiment_packet.ps1"
    sourceEntryDedupPacketStatus = $entryDedupStatus
    sourceMatrixFreshnessStatus = $freshnessStatus
    proposalId = "entry-dedup-semantics-shadow-operator-review"
    priorityRank = if ($null -ne $entryDedupPriorityItem) { [int]$entryDedupPriorityItem.rank } else { 0 }
    reviewQuestion = "Approve review-only EntryDedup semantics shadow experiment packet?"
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        liveTradingAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        deployOrEnvChangeAllowed = $false
        policyRelaxationAllowed = $false
    }
    evidenceChecklist = @(
        "source matrix freshness is FRESH",
        "EntryDedup priority rank is 3",
        "EntryDedup semantics shadow packet is READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "EntryDedup skip rows, positive/negative 24h rows, TP/SL feasibility, and same-bar ambiguity evidence are attached",
        "ExpectedValueGate, EventRiskControl, duplicate-hash, daily cap, max-loss, and OCO feasibility remain required before any future mutation request",
        "EntryDedup/DataFreshness/live policy remains unchanged"
    )
    operatorDecisionChoices = @(
        "approve review-only EntryDedup semantics shadow packet",
        "request fresh read-only SSH rerun",
        "reject or defer and keep current EntryDedup policy unchanged"
    )
    requiredSeparateAuthorization = @(
        "relax EntryDedup semantics",
        "enable staged-add or live entry execution",
        "change DataFreshness/live policy",
        "place orders or attach/modify OCO",
        "deploy or production env change"
    )
    forbiddenActions = @(
        "relax EntryDedup",
        "relax DataFreshnessGuard",
        "enable live trading",
        "enable staged-add execution",
        "enable scheduler",
        "place orders",
        "modify or cancel OCO",
        "change production env",
        "deploy",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    entryDedupPriorityItem = $entryDedupPriorityItem
    sourceEvidenceSummary = $summary
    blockedPolicyLanes = if ($null -ne $priorityPacket) { @($priorityPacket.blockedPolicyLanes) } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup semantics operator decision packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[entry-dedup-semantics-operator-decision-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_priority_decision_brief.ps1 and prepare_entry_dedup_semantics_shadow_experiment_packet.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $priorityText
Write-Host $entryDedupText
Write-Host "source_priority_packet=prepare_profit_operator_priority_decision_brief.ps1 exitCode=$priorityExitCode"
Write-Host "source_entry_dedup_packet=prepare_entry_dedup_semantics_shadow_experiment_packet.ps1 exitCode=$entryDedupExitCode"
Write-Host "source_matrix_freshness_status=$freshnessStatus"
Write-Host "entry_dedup_semantics_priority_rank=$($packet.priorityRank)"
Write-Host ("entry_dedup_semantics_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_semantics_operator_decision_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "entry_dedup_semantics_operator_decision_status=$status"
Write-Host "entry_dedup_semantics_next_action=$nextAction"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup semantics operator decision packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[entry-dedup-semantics-operator-decision-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup semantics operator decision packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
