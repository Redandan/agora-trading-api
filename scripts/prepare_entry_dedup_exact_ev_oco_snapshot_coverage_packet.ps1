param(
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
    [string]$SyntheticPreviewLogPath = "target/profit-review/entry-dedup-synthetic-ev-oco-preview-fresh.log",
    [string]$CollectorReviewLogPath = "target/profit-review/entry-dedup-candidate-runtime-snapshot-collector-review-latest.log",
    [string]$GatePreflightLogPath = "target/profit-review/entry-dedup-semantics-gate-preflight-fresh.log",
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

function Get-DecimalValue {
    param([object]$Value)
    $parsed = [decimal]0
    if ($null -eq $Value) { return [decimal]0 }
    if ($Value -is [decimal]) { return $Value }
    if ([decimal]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return [decimal]0
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

foreach ($path in @($ExactOpportunityLogPath, $SyntheticPreviewLogPath, $CollectorReviewLogPath, $GatePreflightLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes
$syntheticLog = Read-FreshLog -Name "entry-dedup-synthetic-ev-oco-preview" -PathValue $SyntheticPreviewLogPath -MaxAge $MaxAgeMinutes
$collectorLog = Read-FreshLog -Name "entry-dedup-candidate-runtime-snapshot-collector" -PathValue $CollectorReviewLogPath -MaxAge $MaxAgeMinutes
$gateLog = Read-FreshLog -Name "entry-dedup-semantics-gate-preflight" -PathValue $GatePreflightLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($exactLog, $syntheticLog, $collectorLog, $gateLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet=")
$syntheticPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $syntheticLog.Text -Prefix "entry_dedup_synthetic_ev_oco_preview_packet=")
$collectorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $collectorLog.Text -Prefix "entry_dedup_candidate_runtime_snapshot_collector_review_packet=")
$gatePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $gateLog.Text -Prefix "entry_dedup_semantics_gate_preflight_packet=")

if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact opportunity staged-add packet JSON present" }
if ($null -eq $syntheticPacket) { Add-Missing -List $missing -Value "synthetic EV/OCO preview packet JSON present" }
if ($null -eq $collectorPacket) { Add-Missing -List $missing -Value "candidate runtime snapshot collector packet JSON present" }
if ($null -eq $gatePacket) { Add-Missing -List $missing -Value "gate preflight packet JSON present" }

$exactStatus = [string](Get-Prop -Object $exactPacket -Name "status" -Default "UNKNOWN")
$syntheticStatus = [string](Get-Prop -Object $syntheticPacket -Name "status" -Default "UNKNOWN")
$collectorStatus = [string](Get-Prop -Object $collectorPacket -Name "status" -Default "UNKNOWN")
$gateStatus = [string](Get-Prop -Object $gatePacket -Name "status" -Default "UNKNOWN")
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EntryDedup staged-add review packet ready"
}
if ($syntheticStatus -ne "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "synthetic EV/OCO preview ready"
}
if ($collectorStatus -ne "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector review ready"
}
if ($gateStatus -ne "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE") {
    Add-Missing -List $missing -Value "gate preflight packet present"
}

$opportunities = if ($null -ne $exactPacket -and $null -ne (Get-Prop -Object $exactPacket -Name "opportunities")) {
    @((Get-Prop -Object $exactPacket -Name "opportunities"))
} else {
    @()
}
$exactOpportunityCount = Get-IntValue (Get-Prop -Object $exactPacket -Name "exactOpportunityCount" -Default 0)
$tpHitOpportunities = Get-IntValue (Get-Prop -Object $exactPacket -Name "tpHitOpportunities" -Default 0)
$slHitOpportunities = Get-IntValue (Get-Prop -Object $exactPacket -Name "slHitOpportunities" -Default 0)
$ambiguousOpportunities = Get-IntValue (Get-Prop -Object $exactPacket -Name "ambiguousOpportunities" -Default 0)
$exactEvProxyRows = 0
$exactPositiveEvProxyRows = 0
$exactPlanShapeRows = 0
$exactReplayKeyRows = 0
$invalidExamples = @()
foreach ($opportunity in $opportunities) {
    $entry = Get-DecimalValue (Get-Prop -Object $opportunity -Name "entry" -Default 0)
    $tp = Get-DecimalValue (Get-Prop -Object $opportunity -Name "tp" -Default 0)
    $sl = Get-DecimalValue (Get-Prop -Object $opportunity -Name "sl" -Default 0)
    $expectedRProxy = Get-DecimalValue (Get-Prop -Object $opportunity -Name "expectedRProxy" -Default 0)
    $netReturnPct = Get-Prop -Object $opportunity -Name "netReturnPct" -Default $null
    $opportunityKey = [string](Get-Prop -Object $opportunity -Name "opportunityKey" -Default "")
    if ($expectedRProxy -ne 0 -or $null -ne $netReturnPct) { $exactEvProxyRows += 1 }
    if ($expectedRProxy -gt 0) { $exactPositiveEvProxyRows += 1 }
    if ($entry -gt 0 -and $sl -gt 0 -and $tp -gt 0 -and $sl -lt $entry -and $entry -lt $tp) { $exactPlanShapeRows += 1 }
    if ($opportunityKey -match "^[0-9a-f]{16}$") { $exactReplayKeyRows += 1 }
    if ($expectedRProxy -le 0 -or -not ($entry -gt 0 -and $sl -gt 0 -and $tp -gt 0 -and $sl -lt $entry -and $entry -lt $tp) -or $opportunityKey -notmatch "^[0-9a-f]{16}$") {
        $invalidExamples += [ordered]@{
            opportunityKey = $opportunityKey
            entry = [string]$entry
            tp = [string]$tp
            sl = [string]$sl
            expectedRProxy = [string]$expectedRProxy
        }
    }
}
$allExactEvProxyPositive = $exactOpportunityCount -gt 0 -and $exactPositiveEvProxyRows -eq $exactOpportunityCount
$allExactPlanShapesValid = $exactOpportunityCount -gt 0 -and $exactPlanShapeRows -eq $exactOpportunityCount
$allExactReplayKeysValid = $exactOpportunityCount -gt 0 -and $exactReplayKeyRows -eq $exactOpportunityCount
if (-not $allExactEvProxyPositive) { Add-Missing -List $missing -Value "all exact opportunities have positive expectedRProxy evidence" }
if (-not $allExactPlanShapesValid) { Add-Missing -List $missing -Value "all exact opportunities have valid entry/TP/SL/OCO plan shape" }
if (-not $allExactReplayKeysValid) { Add-Missing -List $missing -Value "all exact opportunities have stable 16-char replay/opportunity keys" }
if ($ambiguousOpportunities -ne 0) { Add-Missing -List $missing -Value "exact opportunity ambiguous same-bar rows remain zero" }

$syntheticCandidateRows = Get-IntValue (Get-Prop -Object $syntheticPacket -Name "candidateRows" -Default 0)
$syntheticEvProxyPassRows = Get-IntValue (Get-Prop -Object $syntheticPacket -Name "syntheticEvProxyPassRows" -Default 0)
$syntheticValidOcoPlanRows = Get-IntValue (Get-Prop -Object $syntheticPacket -Name "validOcoPlanShapeRows" -Default 0)
$syntheticNotRuntimeEvidence = Get-BoolValue (Get-Prop -Object $syntheticPacket -Name "notRuntimeEvidence" -Default $false)
$syntheticEvCoverageReady = $syntheticCandidateRows -gt 0 -and $syntheticEvProxyPassRows -eq $syntheticCandidateRows
$syntheticOcoCoverageReady = $syntheticCandidateRows -gt 0 -and $syntheticValidOcoPlanRows -eq $syntheticCandidateRows
if (-not $syntheticEvCoverageReady) { Add-Missing -List $missing -Value "synthetic EV proxy pass coverage complete" }
if (-not $syntheticOcoCoverageReady) { Add-Missing -List $missing -Value "synthetic OCO plan-shape coverage complete" }
if (-not $syntheticNotRuntimeEvidence) { Add-Missing -List $missing -Value "synthetic preview is explicitly not runtime evidence" }

$collectorKeys = @((Get-Prop -Object $collectorPacket -Name "proposedCollectorContextKeys" -Default @()))
$collectorSourceContractMissing = @((Get-NestedProp -Object $collectorPacket -Path @("sourceContract", "missing") -Default @()))
$collectorLocalStatus = [string](Get-Prop -Object $collectorPacket -Name "localImplementationStatus" -Default "UNKNOWN")
$requiredCollectorKeys = @(
    "entryPrice",
    "tpPrice",
    "slPrice",
    "expected_r",
    "min_expected_r",
    "ev_reason",
    "eventRiskLevel",
    "dailyLossGuard",
    "ocoCapable",
    "ocoPlanCreated",
    "orderSent=false",
    "suppressionReason=SHADOW_MODE",
    "duplicateCandidateHash",
    "dailyCapSnapshot",
    "maxLossSnapshot",
    "replayCandidateId"
)
$missingCollectorKeys = [System.Collections.Generic.List[string]]::new()
foreach ($key in $requiredCollectorKeys) {
    if ($collectorKeys -notcontains $key) {
        Add-Missing -List $missingCollectorKeys -Value $key
    }
}
$collectorContractReady = $collectorLocalStatus -eq "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE" -and $collectorSourceContractMissing.Count -eq 0 -and $missingCollectorKeys.Count -eq 0
if (-not $collectorContractReady) {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector local contract includes EV/OCO/event-risk/budget/duplicate keys"
}

$eventRiskOk = Get-BoolValue (Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskOk") -Default $false)
$eventRiskLevel = [string](Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskLevel") -Default "UNKNOWN")
$eventRiskPolicy = [string](Get-NestedProp -Object $gatePacket -Path @("runtimeMcpEvidence", "eventRiskPolicy") -Default "UNKNOWN")
$eventRiskGateStatus = [string](Get-NestedProp -Object $gatePacket -Path @("gateStatuses", "eventRiskControl") -Default "UNKNOWN")
$candidateEventRiskBlockRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "eventRiskBlockRows") -Default 0)
$globalEventRiskBlockRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "globalGateRows", "eventrisk_block_rows") -Default 0)
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "nonAutoEventRiskRows") -Default 0)
$currentEventRiskClear = $eventRiskOk -and $eventRiskLevel -eq "R0" -and $candidateEventRiskBlockRows -eq 0
$historicalEventRiskSeparateReviewRequired = $globalEventRiskBlockRows -gt 0 -or $nonAutoEventRiskRows -gt 0 -or $eventRiskGateStatus -like "*HISTORICAL_ROWS_NEED_SEPARATE_REVIEW*"
$eventRiskEvidenceStatus = if ($currentEventRiskClear -and $historicalEventRiskSeparateReviewRequired) {
    "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS_HISTORICAL_ROWS_NEED_SEPARATE_REVIEW"
} elseif ($currentEventRiskClear) {
    "CLEARED_CURRENT_R0_CANDIDATES_NO_EVENT_RISK_BLOCKS"
} else {
    "BLOCKED_EVENT_RISK_CURRENT_OR_CANDIDATE_EVIDENCE_INCOMPLETE"
}
if (-not $currentEventRiskClear) {
    Add-Missing -List $missing -Value "current EventRiskControl R0 and candidate event-risk blocker rows zero"
}

$candidateRuntimeEvidenceRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "runtimeEvidenceRows") -Default 0)
$candidateRuntimeEvEvaluatedRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "runtimeEvEvaluatedRows") -Default 0)
$candidateRuntimeEntryPlanRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "runtimeEntryPlanRows") -Default 0)
$candidateRuntimeOcoPlanRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "runtimeOcoPlanRows") -Default 0)
$candidateRuntimeOrderSentRows = Get-IntValue (Get-NestedProp -Object $gatePacket -Path @("dbEvidence", "candidateGateRows", "runtimeOrderSentRows") -Default 0)
$runtimeSnapshotCoverageCleared = $false
$runtimeSnapshotBlockerReason = if ($candidateRuntimeEntryPlanRows -lt $exactOpportunityCount) {
    "CANDIDATE_RUNTIME_ENTRY_PLAN_ROWS_MISSING"
} elseif ($candidateRuntimeEvEvaluatedRows -lt $exactOpportunityCount) {
    "CANDIDATE_RUNTIME_EV_ROWS_INCOMPLETE"
} elseif ($candidateRuntimeOcoPlanRows -lt $exactOpportunityCount) {
    "CANDIDATE_RUNTIME_OCO_PLAN_ROWS_INCOMPLETE"
} else {
    "RUNTIME_SNAPSHOT_ACTIVATION_STILL_REQUIRES_SEPARATE_AUTHORIZATION"
}

$preflightReady = $missing.Count -eq 0
$status = if ($preflightReady) {
    "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_INCOMPLETE_NOT_LIVE"
}
$decision = if ($preflightReady) {
    "REVIEW_EXACT_EV_OCO_COVERAGE_RUNTIME_SNAPSHOT_STILL_REQUIRED_NOT_LIVE"
} else {
    "COLLECT_ENTRY_DEDUP_EXACT_EV_OCO_COVERAGE_EVIDENCE_NOT_LIVE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_PACKET"
    status = $status
    decision = $decision
    symbol = [string](Get-Prop -Object $exactPacket -Name "symbol" -Default "BTCUSDT")
    strategyId = Get-IntValue (Get-Prop -Object $exactPacket -Name "strategyId" -Default 508)
    intervalCode = [string](Get-Prop -Object $exactPacket -Name "intervalCode" -Default "1h")
    sourceLogs = [ordered]@{
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
        syntheticEvOcoPreview = $SyntheticPreviewLogPath
        candidateRuntimeSnapshotCollectorReview = $CollectorReviewLogPath
        gatePreflight = $GatePreflightLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh },
        [ordered]@{ name = $syntheticLog.Name; ageMinutes = $syntheticLog.AgeMinutes; fresh = $syntheticLog.Fresh },
        [ordered]@{ name = $collectorLog.Name; ageMinutes = $collectorLog.AgeMinutes; fresh = $collectorLog.Fresh },
        [ordered]@{ name = $gateLog.Name; ageMinutes = $gateLog.AgeMinutes; fresh = $gateLog.Fresh }
    )
    exactOpportunityCoverage = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        exactEvProxyRows = $exactEvProxyRows
        exactPositiveEvProxyRows = $exactPositiveEvProxyRows
        exactPlanShapeRows = $exactPlanShapeRows
        exactReplayKeyRows = $exactReplayKeyRows
        allExactEvProxyPositive = $allExactEvProxyPositive
        allExactPlanShapesValid = $allExactPlanShapesValid
        allExactReplayKeysValid = $allExactReplayKeysValid
        tpHitOpportunities = $tpHitOpportunities
        slHitOpportunities = $slHitOpportunities
        ambiguousOpportunities = $ambiguousOpportunities
        invalidExamples = @($invalidExamples)
    }
    syntheticCoverage = [ordered]@{
        syntheticCandidateRows = $syntheticCandidateRows
        syntheticEvProxyPassRows = $syntheticEvProxyPassRows
        syntheticValidOcoPlanShapeRows = $syntheticValidOcoPlanRows
        syntheticEvCoverageReady = $syntheticEvCoverageReady
        syntheticOcoCoverageReady = $syntheticOcoCoverageReady
        syntheticNotRuntimeEvidence = $syntheticNotRuntimeEvidence
    }
    collectorContractCoverage = [ordered]@{
        collectorLocalImplementationStatus = $collectorLocalStatus
        collectorContractReady = $collectorContractReady
        requiredCollectorKeys = @($requiredCollectorKeys)
        missingCollectorKeys = @($missingCollectorKeys)
        sourceContractMissing = @($collectorSourceContractMissing)
    }
    eventRiskEvidence = [ordered]@{
        eventRiskEvidenceStatus = $eventRiskEvidenceStatus
        eventRiskGateStatus = $eventRiskGateStatus
        eventRiskOk = $eventRiskOk
        eventRiskLevel = $eventRiskLevel
        eventRiskPolicy = $eventRiskPolicy
        candidateEventRiskBlockRows = $candidateEventRiskBlockRows
        globalEventRiskBlockRows = $globalEventRiskBlockRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        historicalEventRiskSeparateReviewRequired = $historicalEventRiskSeparateReviewRequired
    }
    runtimeSnapshotEvidence = [ordered]@{
        runtimeSnapshotCoverageCleared = $runtimeSnapshotCoverageCleared
        runtimeSnapshotBlockerReason = $runtimeSnapshotBlockerReason
        candidateRuntimeEvidenceRows = $candidateRuntimeEvidenceRows
        candidateRuntimeEvEvaluatedRows = $candidateRuntimeEvEvaluatedRows
        candidateRuntimeEntryPlanRows = $candidateRuntimeEntryPlanRows
        candidateRuntimeOcoPlanRows = $candidateRuntimeOcoPlanRows
        candidateRuntimeOrderSentRows = $candidateRuntimeOrderSentRows
    }
    requiredBeforeRuntimeSnapshotCleared = @(
        "separately authorized deployment or activation of the shadow snapshot collector",
        "fresh candidate-level runtime rows for each exact opportunity with entry/TP/SL, expected_r, min_expected_r, EV reason, event risk, daily cap, max-loss, duplicate hash, and OCO plan fields",
        "orderSent=false and suppressionReason=SHADOW_MODE for every runtime snapshot row",
        "OCO route proof remains separately required before any staged-add or live mutation request",
        "fresh read-only rerun before any later operator mutation request"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        exactEvOcoCoverageReady = $preflightReady
        runtimeSnapshotCoverageCleared = $false
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
    nextAction = "Use this packet to review exact EV/OCO preflight coverage and EventRiskControl R0 evidence; runtime snapshot coverage remains uncleared until separately authorized collector activation produces candidate-level runtime rows."
    notAuthorization = "read-only EntryDedup exact EV/OCO snapshot coverage packet only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-exact-ev-oco-snapshot-coverage-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup exact-opportunity, synthetic EV/OCO, collector, and gate preflight logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_status=$status"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_decision=$decision"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_exact_count=$exactOpportunityCount"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_exact_positive_ev_proxy_rows=$exactPositiveEvProxyRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_exact_plan_shape_rows=$exactPlanShapeRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_all_exact_ev_proxy_positive=$($allExactEvProxyPositive.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_all_exact_plan_shapes_valid=$($allExactPlanShapesValid.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_candidate_rows=$syntheticCandidateRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_ev_proxy_pass_rows=$syntheticEvProxyPassRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_synthetic_valid_oco_plan_shape_rows=$syntheticValidOcoPlanRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_collector_contract_ready=$($collectorContractReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_event_risk_status=$eventRiskEvidenceStatus"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_event_risk_level=$eventRiskLevel"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_candidate_event_risk_block_rows=$candidateEventRiskBlockRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_runtime_snapshot_cleared=$($runtimeSnapshotCoverageCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_runtime_snapshot_blocker_reason=$runtimeSnapshotBlockerReason"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_candidate_runtime_entry_plan_rows=$candidateRuntimeEntryPlanRows"
Write-Host "entry_dedup_exact_ev_oco_snapshot_coverage_candidate_runtime_oco_plan_rows=$candidateRuntimeOcoPlanRows"
Write-Host ("entry_dedup_exact_ev_oco_snapshot_coverage_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_exact_ev_oco_snapshot_coverage_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "exact_ev_oco_coverage_ready=$($preflightReady.ToString().ToLowerInvariant())"
Write-Host "runtime_snapshot_coverage_cleared=false"
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
Write-Host "notAuthorization=read-only EntryDedup exact EV/OCO snapshot coverage packet only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-exact-ev-oco-snapshot-coverage-packet] read-only check complete"

if ($RequireReady -and -not $preflightReady) {
    throw "EntryDedup exact EV/OCO snapshot coverage packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
