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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for EntryDedup semantics preflight arguments."
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

$decisionScript = Join-Path $PSScriptRoot "prepare_entry_dedup_semantics_operator_decision_packet.ps1"
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing EntryDedup semantics operator decision packet script: $decisionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for EntryDedup semantics preflight." }

$decisionArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-PriorityStrategyId", "$PriorityStrategyId",
    "-EntryDedupStrategyId", "$EntryDedupStrategyId",
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
    $decisionOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $decisionScript @decisionArgs 2>&1
    $decisionExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$decisionText = ($decisionOutput | Out-String -Width 4096)
$decisionJson = Get-LastPrefixedValue -Text $decisionText -Prefix "entry_dedup_semantics_operator_decision_packet="
$decisionPacket = $null
if (-not [string]::IsNullOrWhiteSpace($decisionJson)) {
    $decisionPacket = $decisionJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($decisionExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics operator decision packet completed" }
if ($null -eq $decisionPacket) { Add-MissingRequirement -List $missingRequirements -Value "entry_dedup_semantics_operator_decision_packet valid JSON" }

$sourceStatus = ""
$sourceMatrixFreshness = ""
$sourceEntryDedupPacketStatus = ""
$sourcePriorityRank = 0
if ($null -ne $decisionPacket) {
    $sourceStatus = [string]$decisionPacket.status
    $sourceMatrixFreshness = [string]$decisionPacket.sourceMatrixFreshnessStatus
    $sourceEntryDedupPacketStatus = [string]$decisionPacket.sourceEntryDedupPacketStatus
    $sourcePriorityRank = [int]$decisionPacket.priorityRank
}
if ($sourceStatus -ne "READY_FOR_ENTRY_DEDUP_SEMANTICS_OPERATOR_DECISION_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "EntryDedup semantics operator decision packet ready"
}
if ($sourceMatrixFreshness -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($sourceEntryDedupPacketStatus -ne "READY_FOR_ENTRY_DEDUP_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "source EntryDedup shadow experiment packet ready"
}
if ($sourcePriorityRank -ne 3) {
    Add-MissingRequirement -List $missingRequirements -Value "source EntryDedup priority rank is 3"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_ENTRY_DEDUP_SEMANTICS_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to an EntryDedup semantics shadow operator review; require separate explicit approval before any EntryDedup/DataFreshness/live policy, staged-add, order, OCO, deploy, or env change."
} else {
    "Refresh the EntryDedup semantics operator decision packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_SEMANTICS_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    priorityStrategyId = $PriorityStrategyId
    entryDedupStrategyId = $EntryDedupStrategyId
    intervalCode = $IntervalCode
    sourceDecisionPacket = "prepare_entry_dedup_semantics_operator_decision_packet.ps1"
    sourceDecisionPacketStatus = $sourceStatus
    sourceEntryDedupPacketStatus = $sourceEntryDedupPacketStatus
    sourceMatrixFreshnessStatus = $sourceMatrixFreshness
    sourcePriorityRank = $sourcePriorityRank
    preflightDecision = if ($ready) { "PREPARE_REVIEW_ONLY_ENTRY_DEDUP_SEMANTICS_SHADOW_REVIEW" } else { "REFRESH_SOURCE_DECISION_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        liveTradingAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source EntryDedup semantics operator decision packet is ready",
        "source matrix freshness is FRESH",
        "source EntryDedup shadow experiment packet is ready",
        "source priority rank remains 3",
        "review scope is shadow-only and does not change EntryDedup/DataFreshness/live policy",
        "operator separately approves any future EntryDedup relaxation, staged-add, order, OCO, deploy, or env change"
    )
    requiredBeforeAnyFutureMutation = @(
        "separate explicit EntryDedup/DataFreshness/live policy authorization",
        "fresh production SSH evidence rerun",
        "ExpectedValueGate, EventRiskControl, duplicate-hash, daily-cap, max-loss, and OCO feasibility evidence",
        "fresh runtime health and server-local MCP parity",
        "rollback criteria and post-mutation read-only verification plan"
    )
    explicitNonAuthorizations = @(
        "does not relax EntryDedup",
        "does not relax DataFreshnessGuard",
        "does not enable live trading",
        "does not enable staged-add execution",
        "does not enable scheduler",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not deploy",
        "does not change production env",
        "does not send Telegram"
    )
    sourceDecisionPacketSummary = $decisionPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only EntryDedup semantics preflight review packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[entry-dedup-semantics-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_entry_dedup_semantics_operator_decision_packet.ps1 only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $decisionText
Write-Host "source_decision_packet=prepare_entry_dedup_semantics_operator_decision_packet.ps1 exitCode=$decisionExitCode"
Write-Host "source_decision_packet_status=$sourceStatus"
Write-Host "source_entry_dedup_packet_status=$sourceEntryDedupPacketStatus"
Write-Host "source_matrix_freshness_status=$sourceMatrixFreshness"
Write-Host "entry_dedup_semantics_preflight_priority_rank=$sourcePriorityRank"
Write-Host "entry_dedup_semantics_preflight_decision=$($packet.preflightDecision)"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("entry_dedup_semantics_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("entry_dedup_semantics_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "entry_dedup_semantics_preflight_status=$status"
Write-Host "entry_dedup_semantics_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only EntryDedup semantics preflight review packet only; does not authorize EntryDedup relaxation, DataFreshness/live policy change, live trading, staged-add execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[entry-dedup-semantics-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup semantics preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
