param(
    [string]$GatePreflightLogPath = "target/profit-review/entry-dedup-semantics-gate-preflight-fresh.log",
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
    [string]$ExactEvOcoCoverageLogPath = "",
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

foreach ($path in @($GatePreflightLogPath, $ExactOpportunityLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}
if (-not [string]::IsNullOrWhiteSpace($ExactEvOcoCoverageLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $ExactEvOcoCoverageLogPath
}

$gateLog = Read-FreshLog -Name "entry-dedup-semantics-gate-preflight" -PathValue $GatePreflightLogPath -MaxAge $MaxAgeMinutes
$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes
$coverageLog = if (-not [string]::IsNullOrWhiteSpace($ExactEvOcoCoverageLogPath)) {
    Read-FreshLog -Name "entry-dedup-exact-ev-oco-coverage" -PathValue $ExactEvOcoCoverageLogPath -MaxAge $MaxAgeMinutes
} else {
    $null
}

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($gateLog, $exactLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}
if ($null -ne $coverageLog -and -not $coverageLog.Fresh) {
    Add-Missing -List $missing -Value "$($coverageLog.Name) log fresh within $MaxAgeMinutes minutes"
}

$gatePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $gateLog.Text -Prefix "entry_dedup_semantics_gate_preflight_packet=")
$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet=")
$coveragePacket = if ($null -ne $coverageLog) {
    Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $coverageLog.Text -Prefix "entry_dedup_exact_ev_oco_snapshot_coverage_packet=")
} else {
    $null
}
if ($null -eq $gatePacket) { Add-Missing -List $missing -Value "gate preflight packet JSON present" }
if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact opportunity packet JSON present" }
if ($null -ne $coverageLog -and $null -eq $coveragePacket) { Add-Missing -List $missing -Value "exact EV/OCO coverage packet JSON present" }

$gateStatus = [string](Get-Prop -Object $gatePacket -Name "status" -Default "UNKNOWN")
$exactStatus = [string](Get-Prop -Object $exactPacket -Name "status" -Default "UNKNOWN")
$coverageStatus = [string](Get-Prop -Object $coveragePacket -Name "status" -Default "NOT_PROVIDED_OPTIONAL")
if ($gateStatus -ne "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE") {
    Add-Missing -List $missing -Value "gate preflight packet present"
}
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact opportunity packet ready"
}
if ($null -ne $coveragePacket -and $coverageStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EV/OCO coverage packet ready"
}

$eventRiskOk = Get-BoolValue (Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskOk") -Default $false)
$eventRiskLevel = [string](Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskLevel") -Default "UNKNOWN")
$eventRiskPolicy = [string](Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskPolicy") -Default "UNKNOWN")
$eventRiskGateStatus = [string](Get-NestedProp -Object $gatePacket -Path @("gateStatuses", "eventRiskControl") -Default "UNKNOWN")
$coverageEventRiskStatus = [string](Get-NestedProp -Object $coveragePacket -Path @("eventRiskEvidence", "eventRiskEvidenceStatus") -Default "NOT_PROVIDED_OPTIONAL")
$candidateEventRiskBlockRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "eventRiskBlockRows") -Default 0)
$globalEventRiskBlockRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "globalGateRows", "eventrisk_block_rows") -Default 0)
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "nonAutoEventRiskRows") -Default 0)
$exactOpenExposureNonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_eventrisk_rows") -Default $nonAutoEventRiskRows)
$exactOpportunityCount = Get-IntValue (Get-Prop -Object $exactPacket -Name "exactOpportunityCount" -Default 0)

$currentR0Clear = $eventRiskOk -and $eventRiskLevel -eq "R0" -and $eventRiskPolicy -like "*new entries allowed*"
$candidateRowsClear = $candidateEventRiskBlockRows -eq 0
$historicalRowsNeedSeparateReview = $globalEventRiskBlockRows -gt 0 -or $nonAutoEventRiskRows -gt 0 -or $exactOpenExposureNonAutoEventRiskRows -gt 0 -or $eventRiskGateStatus -like "*HISTORICAL_ROWS_NEED_SEPARATE_REVIEW*"
$eventRiskEvidenceStatus = if ($currentR0Clear -and $candidateRowsClear -and $historicalRowsNeedSeparateReview) {
    "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
} elseif ($currentR0Clear -and $candidateRowsClear) {
    "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS"
} else {
    "BLOCKED_EVENT_RISK_CURRENT_OR_CANDIDATE_EVIDENCE_INCOMPLETE"
}
if (-not $currentR0Clear) {
    Add-Missing -List $missing -Value "current EventRiskControl MCP evidence is R0/new entries allowed"
}
if (-not $candidateRowsClear) {
    Add-Missing -List $missing -Value "candidate EventRiskControl blocker rows are zero"
}
if ($null -ne $coveragePacket -and $coverageEventRiskStatus -ne $eventRiskEvidenceStatus) {
    Add-Missing -List $missing -Value "exact EV/OCO coverage packet event-risk status matches gate evidence"
}

$preflightReady = $missing.Count -eq 0
$status = if ($preflightReady) {
    "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_INCOMPLETE_NOT_LIVE"
}
$decision = if ($preflightReady -and $historicalRowsNeedSeparateReview) {
    "REVIEW_CURRENT_R0_EVENT_RISK_WITH_HISTORICAL_NON_AUTO_ROWS_NOT_LIVE"
} elseif ($preflightReady) {
    "REVIEW_CURRENT_R0_EVENT_RISK_CLEAR_NOT_LIVE"
} else {
    "COLLECT_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_NOT_LIVE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_PACKET"
    status = $status
    decision = $decision
    symbol = [string](Get-Prop -Object $exactPacket -Name "symbol" -Default "BTCUSDT")
    strategyId = Get-IntValue (Get-Prop -Object $exactPacket -Name "strategyId" -Default 508)
    intervalCode = [string](Get-Prop -Object $exactPacket -Name "intervalCode" -Default "1h")
    sourceLogs = [ordered]@{
        gatePreflight = $GatePreflightLogPath
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
        exactEvOcoCoverage = if ([string]::IsNullOrWhiteSpace($ExactEvOcoCoverageLogPath)) { "not-provided-optional" } else { $ExactEvOcoCoverageLogPath }
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $gateLog.Name; ageMinutes = $gateLog.AgeMinutes; fresh = $gateLog.Fresh },
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh },
        [ordered]@{
            name = "entry-dedup-exact-ev-oco-coverage"
            ageMinutes = if ($null -ne $coverageLog) { $coverageLog.AgeMinutes } else { $null }
            fresh = if ($null -ne $coverageLog) { $coverageLog.Fresh } else { $true }
            optional = $true
        }
    )
    eventRiskEvidence = [ordered]@{
        eventRiskEvidenceStatus = $eventRiskEvidenceStatus
        gateEventRiskStatus = $eventRiskGateStatus
        coverageEventRiskStatus = $coverageEventRiskStatus
        currentR0Clear = $currentR0Clear
        eventRiskOk = $eventRiskOk
        eventRiskLevel = $eventRiskLevel
        eventRiskPolicy = $eventRiskPolicy
        candidateRowsClear = $candidateRowsClear
        candidateEventRiskBlockRows = $candidateEventRiskBlockRows
        globalEventRiskBlockRows = $globalEventRiskBlockRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        exactOpenExposureNonAutoEventRiskRows = $exactOpenExposureNonAutoEventRiskRows
        historicalRowsNeedSeparateReview = $historicalRowsNeedSeparateReview
        exactOpportunityCount = $exactOpportunityCount
    }
    requiredBeforeEventRiskOverride = @(
        "fresh getEventRiskControlStatus or gate-preflight R0 evidence immediately before any later mutation request",
        "separate written operator review if current event risk is not R0",
        "separate handling of historical non-auto/EventRisk open signal rows before treating them as live-entry authorization",
        "candidate-level runtime snapshot rows must still include eventRiskLevel and dailyLossGuard before collector activation is useful"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        currentEventRiskR0Clear = $currentR0Clear
        historicalEventRiskRowsNeedSeparateReview = $historicalRowsNeedSeparateReview
        eventRiskOverrideAllowed = $false
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        entryDedupPolicyChangeAllowed = $false
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
    nextAction = "Use this packet only to review EventRiskControl evidence for EntryDedup exact-opportunity review; current R0 evidence is not authorization to bypass EntryDedup, activate collectors, or place orders."
    notAuthorization = "read-only EntryDedup EventRiskControl evidence packet only; does not authorize EventRisk override, collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-event-risk-control-evidence-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup gate and exact-opportunity logs only; optional exact EV/OCO coverage log is cross-checked when provided; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_event_risk_control_evidence_status=$status"
Write-Host "entry_dedup_event_risk_control_evidence_decision=$decision"
Write-Host "entry_dedup_event_risk_control_evidence_event_risk_status=$eventRiskEvidenceStatus"
Write-Host "entry_dedup_event_risk_control_evidence_gate_status=$eventRiskGateStatus"
Write-Host "entry_dedup_event_risk_control_evidence_current_r0_clear=$($currentR0Clear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_event_risk_control_evidence_event_risk_level=$eventRiskLevel"
Write-Host "entry_dedup_event_risk_control_evidence_event_risk_policy=$eventRiskPolicy"
Write-Host "entry_dedup_event_risk_control_evidence_candidate_rows_clear=$($candidateRowsClear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_event_risk_control_evidence_candidate_event_risk_block_rows=$candidateEventRiskBlockRows"
Write-Host "entry_dedup_event_risk_control_evidence_global_event_risk_block_rows=$globalEventRiskBlockRows"
Write-Host "entry_dedup_event_risk_control_evidence_non_auto_event_risk_rows=$nonAutoEventRiskRows"
Write-Host "entry_dedup_event_risk_control_evidence_historical_rows_need_separate_review=$($historicalRowsNeedSeparateReview.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_event_risk_control_evidence_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_event_risk_control_evidence_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "event_risk_override_allowed=false"
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup EventRiskControl evidence packet only; does not authorize EventRisk override, collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-event-risk-control-evidence-packet] read-only check complete"

if ($RequireReady -and -not $preflightReady) {
    throw "EntryDedup EventRiskControl evidence packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
