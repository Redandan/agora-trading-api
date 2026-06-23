param(
    [string]$ExitSideDecisionLogPath = "target/profit-review/exit-side-operator-decision-brief-refresh.log",
    [int]$MaxAgeMinutes = 360,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [decimal]$SeverePaperLossPct = -5.0,
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

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Convert-ToDecimalOrNull {
    param([object]$Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value) -or [string]$Value -eq "N/A") {
        return $null
    }
    try {
        return [decimal]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        return $null
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for strategy485 risk escalation brief arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($SeverePaperLossPct -gt 0 -or $SeverePaperLossPct -lt -100) { throw "SeverePaperLossPct must be between -100 and 0." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$resolvedLog = Resolve-RepoPath -PathValue $ExitSideDecisionLogPath
if (-not (Test-Path -LiteralPath $resolvedLog)) {
    throw "Exit-side decision log not found: $resolvedLog"
}

$item = Get-Item -LiteralPath $resolvedLog
$ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
$freshness = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
$text = Get-Content -Raw -LiteralPath $resolvedLog

$briefStatus = Get-LastPrefixedValue -Text $text -Prefix "exit_side_operator_decision_brief_status="
$exitPacketStatus = Get-LastPrefixedValue -Text $text -Prefix "exit_side_profit_review_packet_status="
$ocoHealthOk = Get-LastPrefixedValue -Text $text -Prefix "strategy485_oco_health_ok="
$negativeEvCount = Get-LastPrefixedValue -Text $text -Prefix "strategy485_negative_ev_position_count="
$closeOrModifyCount = Get-LastPrefixedValue -Text $text -Prefix "strategy485_close_or_modify_suggestion_count="
$trailingAcceptance = Get-LastPrefixedValue -Text $text -Prefix "trailing_stop_acceptance="
$packetJson = Get-LastPrefixedValue -Text $text -Prefix "exit_side_operator_decision_brief_packet="
$packet = $null
if (-not [string]::IsNullOrWhiteSpace($packetJson)) {
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
}

$positions = @()
if ($null -ne $packet -and $null -ne $packet.evidenceSummary -and $null -ne $packet.evidenceSummary.strategy485PositionSummaries) {
    $positions = @($packet.evidenceSummary.strategy485PositionSummaries)
}

$positionRiskRows = @()
foreach ($position in $positions) {
    $ev = Convert-ToDecimalOrNull -Value $position.evUsdt
    $paper = Convert-ToDecimalOrNull -Value $position.paperPct
    $riskBucket = if ($null -ne $paper -and $paper -le $SeverePaperLossPct) {
        "SEVERE_PAPER_LOSS"
    } elseif ($null -ne $ev -and $ev -lt 0) {
        "NEGATIVE_EV"
    } else {
        "WATCH"
    }
    $positionRiskRows += [pscustomobject]@{
        positionId = $position.positionId
        decision = $position.decision
        suggestion = $position.suggestion
        evUsdt = if ($null -ne $ev) { [string]$ev } else { [string]$position.evUsdt }
        paperPct = if ($null -ne $paper) { [string]$paper } else { [string]$position.paperPct }
        riskBucket = $riskBucket
    }
}

$numericEvRows = @($positionRiskRows | ForEach-Object {
        $value = Convert-ToDecimalOrNull -Value $_.evUsdt
        if ($null -ne $value) { $value }
    })
$numericPaperRows = @($positionRiskRows | ForEach-Object {
        $value = Convert-ToDecimalOrNull -Value $_.paperPct
        if ($null -ne $value) { $value }
    })

$totalEvUsdt = if ($numericEvRows.Count -gt 0) { [math]::Round(($numericEvRows | Measure-Object -Sum).Sum, 4) } else { $null }
$worstPaperPct = if ($numericPaperRows.Count -gt 0) { [math]::Round(($numericPaperRows | Measure-Object -Minimum).Minimum, 4) } else { $null }
$avgPaperPct = if ($numericPaperRows.Count -gt 0) { [math]::Round(($numericPaperRows | Measure-Object -Average).Average, 4) } else { $null }
$severePaperLossCount = @($positionRiskRows | Where-Object { [string]$_.riskBucket -eq "SEVERE_PAPER_LOSS" }).Count

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($freshness -ne "FRESH") { Add-MissingRequirement -List $missingRequirements -Value "exit-side decision log is FRESH" }
if ($briefStatus -ne "READY_FOR_OPERATOR_DECISION_NOT_MUTATION") { Add-MissingRequirement -List $missingRequirements -Value "exit-side operator decision brief ready" }
if ($exitPacketStatus -ne "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION") { Add-MissingRequirement -List $missingRequirements -Value "exit-side profit review packet ready" }
if ($ocoHealthOk -ne "True" -and $ocoHealthOk -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "strategy485 OCO health OK" }
if ([string]::IsNullOrWhiteSpace($negativeEvCount) -or $negativeEvCount -eq "0") { Add-MissingRequirement -List $missingRequirements -Value "strategy485 negative-EV positions present" }
if ([string]::IsNullOrWhiteSpace($closeOrModifyCount) -or $closeOrModifyCount -eq "0") { Add-MissingRequirement -List $missingRequirements -Value "strategy485 close-or-modify suggestions present" }
if ($positions.Count -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "strategy485 position summaries present" }

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_STRATEGY485_RISK_ESCALATION_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
$escalationClass = if ($severePaperLossCount -gt 0) {
    "CURRENT_POSITION_RISK_ESCALATED_SEVERE_PAPER_LOSS"
} elseif ($positions.Count -gt 0) {
    "CURRENT_POSITION_RISK_REVIEW_NEGATIVE_EV"
} else {
    "NO_POSITION_RISK_EVIDENCE"
}
$nextAction = if ($ready) {
    "Attach this escalation brief to strategy485 risk-reduction operator review; require separate explicit authorization before any close-position or OCO mutation."
} else {
    "Refresh exit-side read-only evidence before using the strategy485 escalation brief."
}

$brief = [pscustomobject]@{
    packetType = "STRATEGY485_RISK_ESCALATION_BRIEF"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    sourceLogPath = $resolvedLog
    sourceLogFreshness = $freshness
    sourceLogAgeMinutes = $ageMinutes
    sourceBriefStatus = $briefStatus
    sourceExitPacketStatus = $exitPacketStatus
    trailingStopAcceptance = $trailingAcceptance
    strategy485OcoHealthOk = $ocoHealthOk
    negativeEvPositionCount = $negativeEvCount
    closeOrModifySuggestionCount = $closeOrModifyCount
    severePaperLossThresholdPct = $SeverePaperLossPct
    severePaperLossCount = $severePaperLossCount
    totalEvUsdt = if ($null -ne $totalEvUsdt) { [string]$totalEvUsdt } else { "N/A" }
    worstPaperPct = if ($null -ne $worstPaperPct) { [string]$worstPaperPct } else { "N/A" }
    avgPaperPct = if ($null -ne $avgPaperPct) { [string]$avgPaperPct } else { "N/A" }
    escalationClass = $escalationClass
    positionRiskRows = @($positionRiskRows)
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        closePositionAllowed = $false
        positionOrOcoMutationAllowed = $false
        orderAllowed = $false
        schedulerEnablementAllowed = $false
        livePolicyChangeAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
    }
    requiredSeparateAuthorization = @(
        "close any strategy 485 position",
        "modify or cancel OCO",
        "place orders",
        "change production env or deploy runtime changes"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only strategy485 risk escalation brief only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env change, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[strategy485-risk-escalation-brief] read-only brief"
Write-Host "scope=READ_ONLY; reads existing exit-side decision log only; no SSH, MCP, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_exit_side_decision_log_path=$resolvedLog"
Write-Host "source_exit_side_decision_log_freshness=$freshness"
Write-Host "exit_side_operator_decision_brief_status=$briefStatus"
Write-Host "exit_side_profit_review_packet_status=$exitPacketStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "strategy485_oco_health_ok=$ocoHealthOk"
Write-Host "strategy485_negative_ev_position_count=$negativeEvCount"
Write-Host "strategy485_close_or_modify_suggestion_count=$closeOrModifyCount"
Write-Host "strategy485_severe_paper_loss_count=$severePaperLossCount"
Write-Host "strategy485_total_ev_usdt=$($brief.totalEvUsdt)"
Write-Host "strategy485_worst_paper_pct=$($brief.worstPaperPct)"
Write-Host "strategy485_avg_paper_pct=$($brief.avgPaperPct)"
Write-Host "strategy485_risk_escalation_class=$escalationClass"
Write-Host "close_position_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("strategy485_position_risk_rows=" + (ConvertTo-Json -Compress -Depth 8 @($positionRiskRows)))
Write-Host ("strategy485_risk_escalation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy485_risk_escalation_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "strategy485_risk_escalation_brief_status=$status"
Write-Host "strategy485_risk_escalation_next_action=$nextAction"
Write-Host "notAuthorization=read-only strategy485 risk escalation brief only; does not authorize close-position, OCO modification, live trading, scheduler enablement, orders, deploy, production env changes, Telegram send, EntryDedup/DataFreshness/live policy relaxation, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[strategy485-risk-escalation-brief] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Strategy485 risk escalation brief is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
