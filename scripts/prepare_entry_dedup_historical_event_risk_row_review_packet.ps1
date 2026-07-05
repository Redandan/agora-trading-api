param(
    [string]$EventRiskControlEvidenceLogPath = "target/profit-review/entry-dedup-event-risk-control-evidence-latest.log",
    [string]$OpenExposureReviewLogPath = "target/profit-review/entry-dedup-open-exposure-review-latest.log",
    [string]$MutationBlockerHandoffLogPath = "target/profit-review/entry-dedup-mutation-blocker-handoff-latest.log",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path (Split-Path -Parent $PSScriptRoot) $PathValue
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Read-FreshLog {
    param([string]$Name, [string]$PathValue, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
        AgeMinutes = $age
        Fresh = $age -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-Prop {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    if ($null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-NestedProp {
    param([object]$Object, [string[]]$Path, [object]$Default = $null)
    $current = $Object
    foreach ($part in $Path) {
        $current = Get-Prop -Object $current -Name $part -Default $null
        if ($null -eq $current) { return $Default }
    }
    return $current
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ($Value -is [double]) { return [int]$Value }
    if ($Value -is [decimal]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($EventRiskControlEvidenceLogPath, $OpenExposureReviewLogPath, $MutationBlockerHandoffLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$eventRiskLog = Read-FreshLog -Name "entry-dedup-event-risk-control-evidence" -PathValue $EventRiskControlEvidenceLogPath -MaxAge $MaxAgeMinutes
$openExposureLog = Read-FreshLog -Name "entry-dedup-open-exposure-review" -PathValue $OpenExposureReviewLogPath -MaxAge $MaxAgeMinutes
$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($eventRiskLog, $openExposureLog, $handoffLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$eventRiskPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $eventRiskLog.Text -Prefix "entry_dedup_event_risk_control_evidence_packet=")
$openExposurePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $openExposureLog.Text -Prefix "entry_dedup_open_exposure_review_packet=")
$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
if ($null -eq $eventRiskPacket) { Add-Missing -List $missing -Value "event-risk evidence packet JSON present" }
if ($null -eq $openExposurePacket) { Add-Missing -List $missing -Value "open exposure review packet JSON present" }
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }

$eventRiskStatus = [string](Get-Prop -Object $eventRiskPacket -Name "status" -Default "UNKNOWN")
$openExposureStatus = [string](Get-Prop -Object $openExposurePacket -Name "status" -Default "UNKNOWN")
$handoffStatus = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
if ($eventRiskStatus -ne "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "event-risk evidence packet ready"
}
if ($openExposureStatus -ne "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "open exposure review packet ready"
}
if ($handoffStatus -ne "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE") {
    Add-Missing -List $missing -Value "mutation blocker handoff packet ready"
}

$symbol = [string](Get-Prop -Object $eventRiskPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $eventRiskPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $eventRiskPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "exactOpportunityCount") -Default (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default 0))

$currentR0Clear = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "currentR0Clear") -Default (Get-NestedProp -Object $eventRiskPacket -Path @("reviewEnvelope", "currentEventRiskR0Clear") -Default $false))
$candidateRowsClear = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "candidateRowsClear") -Default $false)
$historicalRowsNeedSeparateReview = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "historicalRowsNeedSeparateReview") -Default (Get-NestedProp -Object $handoffPacket -Path @("routeAndRuntimeEvidence", "historicalEventRiskRowsNeedSeparateReview") -Default $false))
$globalEventRiskBlockRows = Get-IntValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "globalEventRiskBlockRows") -Default 0)
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "nonAutoEventRiskRows") -Default 0)
$exactOpenExposureNonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "exactOpenExposureNonAutoEventRiskRows") -Default 0)
$openExposureNonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoEventRiskRows") -Default $exactOpenExposureNonAutoEventRiskRows)
$openExposureMissingOcoRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "missingOcoRows") -Default 0)
$openExposureNonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $openExposurePacket -Path @("openExposureEvidence", "nonAutoZeroQtyRows") -Default 0)
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)

$historicalRowEvidenceCount = [math]::Max($openExposureNonAutoEventRiskRows, [math]::Max($nonAutoEventRiskRows, $exactOpenExposureNonAutoEventRiskRows))
$reviewRequiredBeforeMutation = $historicalRowsNeedSeparateReview -or $historicalRowEvidenceCount -gt 0
$classification = if ($currentR0Clear -and $candidateRowsClear -and $reviewRequiredBeforeMutation) {
    "HISTORICAL_EVENT_RISK_REVIEW_REQUIRED_BEFORE_MUTATION"
} elseif ($currentR0Clear -and $candidateRowsClear) {
    "EVENT_RISK_CURRENT_R0_CLEAR_NO_HISTORICAL_REVIEW_REQUIRED"
} else {
    "EVENT_RISK_CURRENT_OR_CANDIDATE_EVIDENCE_INCOMPLETE"
}

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $currentR0Clear) { Add-Missing -List $missing -Value "current EventRiskControl R0 evidence is clear" }
if (-not $candidateRowsClear) { Add-Missing -List $missing -Value "candidate EventRisk rows are clear" }
if (-not $reviewRequiredBeforeMutation) { Add-Missing -List $missing -Value "historical EventRisk row review is required by source evidence" }
if ($historicalRowEvidenceCount -lt 1) { Add-Missing -List $missing -Value "historical/non-auto EventRisk row count is present" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked while historical EventRisk row is reviewed" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "REVIEW_HISTORICAL_NON_AUTO_EVENT_RISK_ROWS_NOT_EXECUTION"
} else {
    "REFRESH_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        eventRiskControlEvidence = $EventRiskControlEvidenceLogPath
        openExposureReview = $OpenExposureReviewLogPath
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $eventRiskLog.Name; ageMinutes = $eventRiskLog.AgeMinutes; fresh = $eventRiskLog.Fresh },
        [ordered]@{ name = $openExposureLog.Name; ageMinutes = $openExposureLog.AgeMinutes; fresh = $openExposureLog.Fresh },
        [ordered]@{ name = $handoffLog.Name; ageMinutes = $handoffLog.AgeMinutes; fresh = $handoffLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        eventRiskControlEvidence = $eventRiskStatus
        openExposureReview = $openExposureStatus
        mutationBlockerHandoff = $handoffStatus
    }
    historicalEventRiskReview = [ordered]@{
        classification = $classification
        exactOpportunityCount = $exactOpportunityCount
        currentR0Clear = $currentR0Clear
        candidateRowsClear = $candidateRowsClear
        historicalRowsNeedSeparateReview = $historicalRowsNeedSeparateReview
        globalEventRiskBlockRows = $globalEventRiskBlockRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        exactOpenExposureNonAutoEventRiskRows = $exactOpenExposureNonAutoEventRiskRows
        openExposureNonAutoEventRiskRows = $openExposureNonAutoEventRiskRows
        openExposureMissingOcoRows = $openExposureMissingOcoRows
        openExposureNonAutoZeroQtyRows = $openExposureNonAutoZeroQtyRows
        reviewRequiredBeforeMutation = $reviewRequiredBeforeMutation
    }
    requiredBeforeClearing = @(
        "fresh read-only row identity/source review for the historical or non-auto EventRisk signal row",
        "operator decision to resolve, classify as stale, or intentionally exclude the row from staged-add exposure semantics",
        "fresh EventRiskControl R0 evidence immediately before any later mutation request",
        "fresh open-exposure review proving missing-OCO/non-auto row handling is complete",
        "separate explicit authorization before any DB row update, deploy/env change, runtime evidence write, policy change, order, or OCO mutation"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        historicalEventRiskReviewReady = $ready
        historicalEventRiskReviewRequiredBeforeMutation = $reviewRequiredBeforeMutation
        currentEventRiskR0Clear = $currentR0Clear
        candidateEventRiskRowsClear = $candidateRowsClear
        shadowReviewReady = $shadowReviewReady
        mutationReady = $false
        eventRiskOverrideAllowed = $false
        historicalRowMutationAllowed = $false
        openExposureMutationAllowed = $false
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        livePolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this packet to review historical/non-auto EventRisk rows separately; current R0 evidence is not authorization to clear historical rows or place orders."
    notAuthorization = "read-only EntryDedup historical EventRisk row review packet only; does not authorize EventRisk override, historical row mutation, open-exposure mutation, collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-historical-event-risk-row-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup EventRiskControl, open-exposure, and mutation-blocker handoff logs only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_historical_event_risk_row_review_status=$status"
Write-Host "entry_dedup_historical_event_risk_row_review_decision=$decision"
Write-Host "entry_dedup_historical_event_risk_row_review_classification=$classification"
Write-Host "entry_dedup_historical_event_risk_row_review_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_historical_event_risk_row_review_current_r0_clear=$($currentR0Clear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_historical_event_risk_row_review_candidate_rows_clear=$($candidateRowsClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_historical_event_risk_row_review_historical_rows_need_separate_review=$($historicalRowsNeedSeparateReview.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_historical_event_risk_row_review_global_event_risk_block_rows=$globalEventRiskBlockRows"
Write-Host "entry_dedup_historical_event_risk_row_review_non_auto_event_risk_rows=$nonAutoEventRiskRows"
Write-Host "entry_dedup_historical_event_risk_row_review_exact_open_exposure_non_auto_event_risk_rows=$exactOpenExposureNonAutoEventRiskRows"
Write-Host "entry_dedup_historical_event_risk_row_review_open_exposure_non_auto_eventrisk_rows=$openExposureNonAutoEventRiskRows"
Write-Host "entry_dedup_historical_event_risk_row_review_open_exposure_missing_oco_rows=$openExposureMissingOcoRows"
Write-Host "entry_dedup_historical_event_risk_row_review_open_exposure_non_auto_zero_qty_rows=$openExposureNonAutoZeroQtyRows"
Write-Host "entry_dedup_historical_event_risk_row_review_required_before_mutation=$($reviewRequiredBeforeMutation.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_historical_event_risk_row_review_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_historical_event_risk_row_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "historical_event_risk_review_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "event_risk_override_allowed=false"
Write-Host "historical_row_mutation_allowed=false"
Write-Host "open_exposure_mutation_allowed=false"
Write-Host "mutation_ready=false"
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup historical EventRisk row review packet only; does not authorize EventRisk override, historical row mutation, open-exposure mutation, collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-historical-event-risk-row-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup historical EventRisk row review packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
