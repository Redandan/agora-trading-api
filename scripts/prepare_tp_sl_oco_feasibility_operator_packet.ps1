param(
    [string]$ExitSideDecisionLogPath = "target/profit-review/exit-side-operator-decision-brief-refresh.log",
    [int]$MaxAgeMinutes = 180,
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

if ([string]::IsNullOrWhiteSpace($ExitSideDecisionLogPath)) { throw "ExitSideDecisionLogPath is required." }
if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for TP/SL/OCO feasibility packet arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$logPath = if ([System.IO.Path]::IsPathRooted($ExitSideDecisionLogPath)) {
    $ExitSideDecisionLogPath
} else {
    Join-Path $repoRoot $ExitSideDecisionLogPath
}
if (-not (Test-Path -LiteralPath $logPath)) {
    throw "Exit-side decision brief log not found: $logPath"
}

$logFile = Get-Item -LiteralPath $logPath
$logAgeMinutes = [math]::Round(((Get-Date) - $logFile.LastWriteTime).TotalMinutes, 2)
$sourceFreshness = if ($logAgeMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
$text = Get-Content -Raw -LiteralPath $logPath

$briefStatus = Get-LastPrefixedValue -Text $text -Prefix "exit_side_operator_decision_brief_status="
$exitPacketStatus = Get-LastPrefixedValue -Text $text -Prefix "exit_side_profit_review_packet_status="
$trailingAcceptance = Get-LastPrefixedValue -Text $text -Prefix "trailing_stop_acceptance="
$trailingImprovementPct = Get-LastPrefixedValue -Text $text -Prefix "trailing_stop_improvement_pct="
$strategy485OcoHealthOk = Get-LastPrefixedValue -Text $text -Prefix "strategy485_oco_health_ok="
$strategy485NegativeEvCountRaw = Get-LastPrefixedValue -Text $text -Prefix "strategy485_negative_ev_position_count="
$strategy485PositionSummariesRaw = Get-LastPrefixedValue -Text $text -Prefix "strategy485_position_summaries="
$briefJson = Get-LastPrefixedValue -Text $text -Prefix "exit_side_operator_decision_brief_packet="

$briefPacket = $null
if (-not [string]::IsNullOrWhiteSpace($briefJson)) {
    $briefPacket = $briefJson | ConvertFrom-Json -ErrorAction Stop
}

$negativeEvCount = 0
[void][int]::TryParse($strategy485NegativeEvCountRaw, [ref]$negativeEvCount)
$positionSummaries = @()
if (-not [string]::IsNullOrWhiteSpace($strategy485PositionSummariesRaw)) {
    try {
        $parsedPositions = $strategy485PositionSummariesRaw | ConvertFrom-Json -ErrorAction Stop
        $positionSummaries = @($parsedPositions)
    } catch {
        $positionSummaries = @()
    }
}
if (@($positionSummaries).Count -eq 0 -and $null -ne $briefPacket -and
    $null -ne $briefPacket.evidenceSummary -and $null -ne $briefPacket.evidenceSummary.strategy485PositionSummaries) {
    $positionSummaries = @($briefPacket.evidenceSummary.strategy485PositionSummaries)
}
$positionSummaries = @($positionSummaries | ForEach-Object {
        $propertyNames = @($_.PSObject.Properties.Name)
        if ($propertyNames -contains "value" -and $propertyNames -contains "Count" -and $null -ne $_.value) {
            @($_.value)
        } else {
            $_
        }
    } | Where-Object { $null -ne $_.positionId } | ForEach-Object {
        [pscustomobject]@{
            positionId = $_.positionId
            decision = $_.decision
            suggestion = $_.suggestion
            evUsdt = $_.evUsdt
            paperPct = $_.paperPct
        }
    })

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($sourceFreshness -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "source exit-side decision log is FRESH" }
if ($briefStatus -ne "READY_FOR_OPERATOR_DECISION_NOT_MUTATION") { Add-MissingRequirement -List $missingRequirements -Value "exit-side operator decision brief ready" }
if ($exitPacketStatus -ne "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION") { Add-MissingRequirement -List $missingRequirements -Value "exit-side profit review packet ready" }
if ($trailingAcceptance -ne "PASS") { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop acceptance PASS" }
if ($strategy485OcoHealthOk -notin @("True", "true", "TRUE")) { Add-MissingRequirement -List $missingRequirements -Value "strategy485 OCO health OK" }
if ($null -eq $briefPacket) { Add-MissingRequirement -List $missingRequirements -Value "exit_side_operator_decision_brief_packet valid JSON" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_TP_SL_OCO_FEASIBILITY_OPERATOR_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
$primaryDecision = if ($ready) { "PREPARE_SEPARATE_TP_SL_OCO_FEASIBILITY_REVIEW" } else { "REFRESH_EXIT_SIDE_EVIDENCE" }
$nextAction = if ($ready) {
    "Attach this TP/SL/OCO feasibility packet to operator review; require separate explicit authorization before trailing enablement, close-position, or OCO mutation."
} else {
    "Refresh the read-only exit-side decision brief before using this TP/SL/OCO feasibility packet."
}
$strategy485FeasibilityClass = if ($negativeEvCount -gt 0) {
    "OCO_PROTECTED_POSITION_RISK_REVIEW_READY_NOT_MUTATION"
} elseif (@($positionSummaries).Count -gt 0) {
    "OCO_PROTECTED_POSITION_RISK_WATCH_ONLY_NOT_MUTATION"
} else {
    "NO_POSITION_RISK_ACTION"
}

$packet = [pscustomobject]@{
    packetType = "TP_SL_OCO_FEASIBILITY_OPERATOR_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourceExitSideDecisionLogPath = $logPath
    sourceExitSideDecisionLogAgeMinutes = $logAgeMinutes
    sourceExitSideDecisionLogFreshness = $sourceFreshness
    sourceBriefStatus = $briefStatus
    sourceExitPacketStatus = $exitPacketStatus
    primaryDecision = $primaryDecision
    trailingStopFeasibility = [pscustomobject]@{
        proposalId = "trailing-stop-rollout-review"
        acceptance = $trailingAcceptance
        improvementPct = $trailingImprovementPct
        reviewReady = $trailingAcceptance -eq "PASS"
        feasibilityClass = "TP_SL_RISK_REDUCTION_REVIEW_READY_NOT_LIVE"
        mutationAllowed = $false
    }
    strategy485OcoFeasibility = [pscustomobject]@{
        proposalId = "strategy485-risk-reduction-review"
        ocoHealthOk = $strategy485OcoHealthOk
        negativeEvPositionCount = $negativeEvCount
        positionSummaries = @($positionSummaries)
        feasibilityClass = $strategy485FeasibilityClass
        closePositionAllowed = $false
        ocoMutationAllowed = $false
    }
    tpSlOcoReviewQuestions = @(
        "Should trailing-stop dry-run/live rollout be reviewed separately from scheduler enablement?",
        "Should aged negative-EV strategy 485 positions be reviewed for a separately authorized close-or-modify decision?",
        "Is current OCO protection healthy enough to keep risk-reduction review non-urgent and non-mutating?",
        "Do TP/SL asymmetry and trailing replay evidence justify a separate exit-policy review without changing entry policy?"
    )
    requiredSeparateAuthorizations = @(
        "enable trailing scheduler or live trailing mode",
        "modify or cancel OCO",
        "close any strategy 485 position",
        "deploy runtime changes",
        "change production env",
        "change exit policy or strategy opt-in"
    )
    requiredBeforeMutation = @(
        "fresh OCO health remains OK",
        "fresh active-position EV reassessment",
        "explicit operator approval for each position or OCO change",
        "runtime deploy/env authorization when required",
        "post-change read-only verification plan"
    )
    forbiddenActions = @(
        "enable live trading",
        "enable scheduler mutation",
        "place orders",
        "modify or cancel OCO",
        "close positions",
        "change production env",
        "deploy",
        "relax EntryDedup/DataFreshness/live policy",
        "mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state"
    )
    exitSideDecisionLanes = if ($null -ne $briefPacket) { @($briefPacket.decisionLanes) } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only TP/SL/OCO feasibility operator packet only; does not authorize live trading, scheduler enablement, orders, close-position, OCO modification/cancelation, deploy, production env change, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[tp-sl-oco-feasibility-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; reuses existing exit-side decision brief log only; no SSH fresh run, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_exit_side_decision_log_path=$logPath"
Write-Host "source_exit_side_decision_log_freshness=$sourceFreshness"
Write-Host "exit_side_operator_decision_brief_status=$briefStatus"
Write-Host "exit_side_profit_review_packet_status=$exitPacketStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "strategy485_oco_health_ok=$strategy485OcoHealthOk"
Write-Host "strategy485_negative_ev_position_count=$negativeEvCount"
Write-Host "strategy485_oco_feasibility_class=$strategy485FeasibilityClass"
Write-Host ("strategy485_position_summaries=" + (ConvertTo-Json -Compress -Depth 6 @($positionSummaries)))
Write-Host "tp_sl_oco_feasibility_primary_decision=$primaryDecision"
Write-Host "tp_sl_oco_feasibility_review_allowed=$($ready.ToString().ToLowerInvariant())"
Write-Host "close_position_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("tp_sl_oco_feasibility_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("tp_sl_oco_feasibility_operator_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "tp_sl_oco_feasibility_status=$status"
Write-Host "tp_sl_oco_feasibility_next_action=$nextAction"
Write-Host "notAuthorization=read-only TP/SL/OCO feasibility operator packet only; does not authorize live trading, scheduler enablement, orders, close-position, OCO modification/cancelation, deploy, production env changes, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[tp-sl-oco-feasibility-operator-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "TP/SL/OCO feasibility operator packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
