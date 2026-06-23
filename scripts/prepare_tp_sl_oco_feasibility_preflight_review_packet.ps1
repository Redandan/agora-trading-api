param(
    [string]$ExitSideDecisionLogPath = "target/profit-review/exit-side-operator-decision-brief-refresh.log",
    [int]$MaxAgeMinutes = 180,
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

if ([string]::IsNullOrWhiteSpace($ExitSideDecisionLogPath)) { throw "ExitSideDecisionLogPath is required." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for TP/SL/OCO feasibility preflight arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($ReviewNotionalCapUsdt -lt 1 -or $ReviewNotionalCapUsdt -gt 100) { throw "ReviewNotionalCapUsdt must be between 1 and 100." }
if ($ObservationHours -lt 1 -or $ObservationHours -gt 720) { throw "ObservationHours must be between 1 and 720." }

$sourceScript = Join-Path $PSScriptRoot "prepare_tp_sl_oco_feasibility_operator_packet.ps1"
if (-not (Test-Path -LiteralPath $sourceScript)) {
    throw "Missing TP/SL/OCO feasibility operator packet script: $sourceScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for TP/SL/OCO feasibility preflight." }

$sourceArgs = @(
    "-ExitSideDecisionLogPath", $ExitSideDecisionLogPath,
    "-MaxAgeMinutes", "$MaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-RequireReady"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $sourceOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $sourceScript @sourceArgs 2>&1
    $sourceExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$sourceText = ($sourceOutput | Out-String -Width 4096)
$sourceJson = Get-LastPrefixedValue -Text $sourceText -Prefix "tp_sl_oco_feasibility_operator_packet="
$sourcePacket = $null
if (-not [string]::IsNullOrWhiteSpace($sourceJson)) {
    $sourcePacket = $sourceJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($sourceExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "TP/SL/OCO feasibility operator packet completed" }
if ($null -eq $sourcePacket) { Add-MissingRequirement -List $missingRequirements -Value "tp_sl_oco_feasibility_operator_packet valid JSON" }

$sourceStatus = ""
$sourceFreshness = ""
$sourceDecision = ""
$trailingAcceptance = ""
$strategy485OcoHealthOk = ""
$strategy485NegativeEvCount = 0
if ($null -ne $sourcePacket) {
    $sourceStatus = [string]$sourcePacket.status
    $sourceFreshness = [string]$sourcePacket.sourceExitSideDecisionLogFreshness
    $sourceDecision = [string]$sourcePacket.primaryDecision
    if ($null -ne $sourcePacket.trailingStopFeasibility) {
        $trailingAcceptance = [string]$sourcePacket.trailingStopFeasibility.acceptance
    }
    if ($null -ne $sourcePacket.strategy485OcoFeasibility) {
        $strategy485OcoHealthOk = [string]$sourcePacket.strategy485OcoFeasibility.ocoHealthOk
        $strategy485NegativeEvCount = [int]$sourcePacket.strategy485OcoFeasibility.negativeEvPositionCount
    }
}
if ($sourceStatus -ne "READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "TP/SL/OCO feasibility operator packet ready"
}
if ($sourceFreshness -ne "FRESH") {
    Add-MissingRequirement -List $missingRequirements -Value "source exit-side decision log freshness is FRESH"
}
if ($sourceDecision -ne "PREPARE_SEPARATE_TP_SL_OCO_FEASIBILITY_REVIEW") {
    Add-MissingRequirement -List $missingRequirements -Value "source TP/SL/OCO feasibility decision is review-ready"
}
if ($trailingAcceptance -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "source trailing-stop acceptance PASS"
}
if ($strategy485OcoHealthOk -notin @("True", "true", "TRUE")) {
    Add-MissingRequirement -List $missingRequirements -Value "source strategy485 OCO health OK"
}
if ($strategy485NegativeEvCount -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "source strategy485 negative-EV positions present for review"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
$nextAction = if ($ready) {
    "Attach this preflight packet to a TP/SL/OCO feasibility operator review; require separate explicit approval before any order, OCO, close-position, scheduler, deploy, or env change."
} else {
    "Refresh the TP/SL/OCO feasibility operator packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourceOperatorPacket = "prepare_tp_sl_oco_feasibility_operator_packet.ps1"
    sourceOperatorPacketStatus = $sourceStatus
    sourceExitSideDecisionLogFreshness = $sourceFreshness
    sourcePrimaryDecision = $sourceDecision
    trailingStopAcceptance = $trailingAcceptance
    strategy485OcoHealthOk = $strategy485OcoHealthOk
    strategy485NegativeEvPositionCount = $strategy485NegativeEvCount
    preflightDecision = if ($ready) { "PREPARE_REVIEW_ONLY_TP_SL_OCO_FEASIBILITY_REVIEW" } else { "REFRESH_SOURCE_FEASIBILITY_PACKET" }
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        reviewNotionalCapUsdt = $ReviewNotionalCapUsdt
        observationHours = $ObservationHours
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        closePositionAllowed = $false
        schedulerEnablementAllowed = $false
        liveTradingAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source TP/SL/OCO feasibility operator packet is ready",
        "source exit-side decision log freshness is FRESH",
        "trailing acceptance remains PASS",
        "strategy 485 OCO health remains OK",
        "strategy 485 negative-EV positions remain present for review",
        "operator separately approves any future order, close-position, OCO, scheduler, deploy, or env change"
    )
    requiredBeforeAnyFutureMutation = @(
        "separate explicit order/OCO/close-position authorization",
        "fresh active-position EV reassessment",
        "fresh OCO health evidence",
        "runtime health and server-local MCP parity",
        "deploy/env authorization when runtime changes are required",
        "rollback criteria and post-mutation read-only verification plan"
    )
    explicitNonAuthorizations = @(
        "does not place orders",
        "does not modify or cancel OCO",
        "does not close positions",
        "does not enable live trading",
        "does not enable scheduler",
        "does not deploy",
        "does not change production env",
        "does not send Telegram",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    sourceOperatorPacketSummary = $sourcePacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only TP/SL/OCO feasibility preflight review packet only; does not authorize orders, close-position, OCO modification/cancelation, live trading, scheduler enablement, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[tp-sl-oco-feasibility-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_tp_sl_oco_feasibility_operator_packet.ps1 only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host $sourceText
Write-Host "source_operator_packet=prepare_tp_sl_oco_feasibility_operator_packet.ps1 exitCode=$sourceExitCode"
Write-Host "source_operator_packet_status=$sourceStatus"
Write-Host "source_exit_side_decision_log_freshness=$sourceFreshness"
Write-Host "source_tp_sl_oco_feasibility_decision=$sourceDecision"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "strategy485_oco_health_ok=$strategy485OcoHealthOk"
Write-Host "strategy485_negative_ev_position_count=$strategy485NegativeEvCount"
Write-Host "tp_sl_oco_feasibility_preflight_decision=$($packet.preflightDecision)"
Write-Host "close_position_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("tp_sl_oco_feasibility_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("tp_sl_oco_feasibility_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "tp_sl_oco_feasibility_preflight_status=$status"
Write-Host "tp_sl_oco_feasibility_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only TP/SL/OCO feasibility preflight review packet only; does not authorize orders, close-position, OCO modification/cancelation, live trading, scheduler enablement, deploy, production env changes, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[tp-sl-oco-feasibility-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "TP/SL/OCO feasibility preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
