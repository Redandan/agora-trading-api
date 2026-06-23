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
    throw "Symbol contains unsupported characters for strategy485 risk-reduction preflight arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$decisionScript = Join-Path $PSScriptRoot "prepare_strategy485_risk_reduction_operator_decision_packet.ps1"
if (-not (Test-Path -LiteralPath $decisionScript)) {
    throw "Missing strategy485 risk-reduction decision packet script: $decisionScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy485 risk-reduction preflight." }

$decisionArgs = @(
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
    $decisionOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $decisionScript @decisionArgs 2>&1
    $decisionExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$decisionText = ($decisionOutput | Out-String -Width 4096)
$decisionJson = Get-LastPrefixedValue -Text $decisionText -Prefix "strategy485_risk_reduction_operator_decision_packet="
$decisionPacket = $null
if (-not [string]::IsNullOrWhiteSpace($decisionJson)) {
    $decisionPacket = $decisionJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($decisionExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "strategy485 risk-reduction operator decision packet completed" }
if ($null -eq $decisionPacket) { Add-MissingRequirement -List $missingRequirements -Value "strategy485_risk_reduction_operator_decision_packet valid JSON" }

$sourceStatus = ""
$sourceMatrixFreshness = ""
$sourcePriorityRank = 0
if ($null -ne $decisionPacket) {
    $sourceStatus = [string]$decisionPacket.status
    $sourceMatrixFreshness = [string]$decisionPacket.sourceMatrixFreshnessStatus
    $sourcePriorityRank = [int]$decisionPacket.priorityRank
}
if ($sourceStatus -ne "READY_FOR_STRATEGY485_RISK_REDUCTION_OPERATOR_DECISION_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485 risk-reduction decision packet ready"
}
if ($sourceMatrixFreshness -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source matrix freshness is FRESH"
}
if ($sourcePriorityRank -ne 2) {
    Add-MissingRequirement -List $missingRequirements -Value "source priority rank is 2"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to a strategy485 risk-reduction operator review; require separate explicit approval before any close-position, OCO, order, deploy, or env change."
} else {
    "Refresh the strategy485 risk-reduction decision packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY485_RISK_REDUCTION_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourceDecisionPacket = "prepare_strategy485_risk_reduction_operator_decision_packet.ps1"
    sourceDecisionPacketStatus = $sourceStatus
    sourceMatrixFreshnessStatus = $sourceMatrixFreshness
    sourcePriorityRank = $sourcePriorityRank
    preflightDecision = if ($ready) { "PREPARE_REVIEW_ONLY_RISK_REDUCTION_OPERATOR_REVIEW" } else { "REFRESH_SOURCE_DECISION_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        closePositionAllowed = $false
        positionOrOcoMutationAllowed = $false
        orderAllowed = $false
        schedulerEnablementAllowed = $false
        liveTradingAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source strategy485 risk-reduction decision packet is ready",
        "source matrix freshness is FRESH",
        "source priority rank remains 2",
        "review scope is non-mutating and does not change position/OCO/runtime state",
        "operator names symbol, strategy id, observation window, and notional cap",
        "operator separately approves any future close-position/OCO/order/env/runtime change"
    )
    requiredBeforeAnyFutureMutation = @(
        "separate explicit close-position or OCO authorization",
        "fresh active-position EV reassessment",
        "fresh OCO health evidence",
        "fresh position summaries with ids and suggested action",
        "runtime health and server-local MCP parity",
        "rollback criteria and post-mutation read-only verification plan"
    )
    explicitNonAuthorizations = @(
        "does not close positions",
        "does not modify or cancel OCO",
        "does not place orders",
        "does not enable live trading",
        "does not enable scheduler",
        "does not deploy",
        "does not change production env",
        "does not send Telegram",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    sourceDecisionPacketSummary = $decisionPacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy485 risk-reduction preflight review packet only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[strategy485-risk-reduction-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_strategy485_risk_reduction_operator_decision_packet.ps1 only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $decisionText
Write-Host "source_decision_packet=prepare_strategy485_risk_reduction_operator_decision_packet.ps1 exitCode=$decisionExitCode"
Write-Host "source_decision_packet_status=$sourceStatus"
Write-Host "source_matrix_freshness_status=$sourceMatrixFreshness"
Write-Host "strategy485_risk_reduction_preflight_priority_rank=$sourcePriorityRank"
Write-Host "strategy485_risk_reduction_preflight_decision=$($packet.preflightDecision)"
Write-Host "close_position_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("strategy485_risk_reduction_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy485_risk_reduction_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "strategy485_risk_reduction_preflight_status=$status"
Write-Host "strategy485_risk_reduction_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only strategy485 risk-reduction preflight review packet only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env changes, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[strategy485-risk-reduction-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy485 risk-reduction preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
