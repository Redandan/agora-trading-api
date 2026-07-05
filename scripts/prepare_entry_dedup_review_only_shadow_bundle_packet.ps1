param(
    [string]$RuntimeProofGapLogPath = "target/profit-review/entry-dedup-runtime-proof-gap-packet-fresh.log",
    [string]$CollectorReviewLogPath = "target/profit-review/entry-dedup-candidate-runtime-snapshot-collector-review-latest.log",
    [string]$DuplicateHashLogPath = "target/profit-review/entry-dedup-duplicate-hash-replay-protection-latest.log",
    [string]$OcoRoutePreflightLogPath = "target/profit-review/entry-dedup-oco-route-proof-preflight-latest.log",
    [string]$ExactEvOcoCoverageLogPath = "target/profit-review/entry-dedup-exact-ev-oco-snapshot-coverage-latest.log",
    [string]$EventRiskControlLogPath = "target/profit-review/entry-dedup-event-risk-control-evidence-latest.log",
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

function Add-Item {
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

foreach ($path in @(
        $RuntimeProofGapLogPath,
        $CollectorReviewLogPath,
        $DuplicateHashLogPath,
        $OcoRoutePreflightLogPath,
        $ExactEvOcoCoverageLogPath,
        $EventRiskControlLogPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$runtimeLog = Read-FreshLog -Name "entry-dedup-runtime-proof-gap" -PathValue $RuntimeProofGapLogPath -MaxAge $MaxAgeMinutes
$collectorLog = Read-FreshLog -Name "entry-dedup-collector-review" -PathValue $CollectorReviewLogPath -MaxAge $MaxAgeMinutes
$duplicateLog = Read-FreshLog -Name "entry-dedup-duplicate-hash" -PathValue $DuplicateHashLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-preflight" -PathValue $OcoRoutePreflightLogPath -MaxAge $MaxAgeMinutes
$coverageLog = Read-FreshLog -Name "entry-dedup-exact-ev-oco-coverage" -PathValue $ExactEvOcoCoverageLogPath -MaxAge $MaxAgeMinutes
$eventRiskLog = Read-FreshLog -Name "entry-dedup-event-risk-control" -PathValue $EventRiskControlLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($runtimeLog, $collectorLog, $duplicateLog, $ocoLog, $coverageLog, $eventRiskLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$runtimePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $runtimeLog.Text -Prefix "entry_dedup_runtime_proof_gap_packet=")
$collectorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $collectorLog.Text -Prefix "entry_dedup_candidate_runtime_snapshot_collector_review_packet=")
$duplicatePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $duplicateLog.Text -Prefix "entry_dedup_duplicate_hash_replay_protection_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_proof_preflight_packet=")
$coveragePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $coverageLog.Text -Prefix "entry_dedup_exact_ev_oco_snapshot_coverage_packet=")
$eventRiskPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $eventRiskLog.Text -Prefix "entry_dedup_event_risk_control_evidence_packet=")

if ($null -eq $runtimePacket) { Add-Missing -List $missing -Value "runtime proof gap packet JSON present" }
if ($null -eq $collectorPacket) { Add-Missing -List $missing -Value "collector review packet JSON present" }
if ($null -eq $duplicatePacket) { Add-Missing -List $missing -Value "duplicate hash packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO route preflight packet JSON present" }
if ($null -eq $coveragePacket) { Add-Missing -List $missing -Value "exact EV/OCO coverage packet JSON present" }
if ($null -eq $eventRiskPacket) { Add-Missing -List $missing -Value "EventRiskControl evidence packet JSON present" }

$runtimeStatus = [string](Get-Prop -Object $runtimePacket -Name "status" -Default "UNKNOWN")
$collectorStatus = [string](Get-Prop -Object $collectorPacket -Name "status" -Default "UNKNOWN")
$duplicateStatus = [string](Get-Prop -Object $duplicatePacket -Name "status" -Default "UNKNOWN")
$ocoStatus = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
$coverageStatus = [string](Get-Prop -Object $coveragePacket -Name "status" -Default "UNKNOWN")
$eventRiskStatus = [string](Get-Prop -Object $eventRiskPacket -Name "status" -Default "UNKNOWN")

if ($runtimeStatus -ne "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "runtime proof gap packet ready"
}
if ($collectorStatus -ne "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector review packet ready"
}
if ($duplicateStatus -ne "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "duplicate hash replay protection packet ready"
}
if ($ocoStatus -ne "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "OCO route proof preflight packet ready"
}
if ($coverageStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EV/OCO coverage packet ready"
}
if ($eventRiskStatus -ne "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "EventRiskControl evidence packet ready"
}

$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $runtimePacket -Path @("exactOpportunityEvidence", "exactOpportunityCount") -Default (Get-NestedProp -Object $coveragePacket -Path @("exactOpportunityCoverage", "exactOpportunityCount") -Default 0))
$tpHitOpportunities = Get-IntValue (Get-NestedProp -Object $runtimePacket -Path @("exactOpportunityEvidence", "tpHitOpportunities") -Default 0)
$slHitOpportunities = Get-IntValue (Get-NestedProp -Object $runtimePacket -Path @("exactOpportunityEvidence", "slHitOpportunities") -Default 0)
$ambiguousOpportunities = Get-IntValue (Get-NestedProp -Object $runtimePacket -Path @("exactOpportunityEvidence", "ambiguousOpportunities") -Default 0)
$exactEvOcoReady = Get-BoolValue (Get-NestedProp -Object $coveragePacket -Path @("reviewEnvelope", "exactEvOcoCoverageReady") -Default $false)
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "runtimeSnapshotCoverageCleared") -Default $false)
$runtimeSnapshotBlocker = [string](Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "runtimeSnapshotBlockerReason") -Default "UNKNOWN")
$collectorContractReady = Get-BoolValue (Get-NestedProp -Object $coveragePacket -Path @("collectorContractCoverage", "collectorContractReady") -Default $false)
$collectorLocalStatus = [string](Get-Prop -Object $collectorPacket -Name "localImplementationStatus" -Default "UNKNOWN")
$duplicateReady = Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("reviewEnvelope", "reviewOnly") -Default $false) -and (Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("exactOpportunityEvidence", "opportunityKeysUnique") -Default $false))
$sourceMarkersPresent = Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("writePathSourceEvidence", "sourceMarkersPresent") -Default $false)
$ocoRouteCleared = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "routeProofCleared") -Default $false)
$ocoRouteBlocker = [string](Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "routeBlockerReason") -Default "UNKNOWN")
$eventRiskEvidenceStatus = [string](Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "eventRiskEvidenceStatus") -Default "UNKNOWN")
$currentEventRiskR0Clear = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("reviewEnvelope", "currentEventRiskR0Clear") -Default $false)
$historicalEventRiskRowsNeedSeparateReview = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("reviewEnvelope", "historicalEventRiskRowsNeedSeparateReview") -Default $false)
$topReviewEvidenceGap = [string](Get-Prop -Object $runtimePacket -Name "topReviewEvidenceGap" -Default "UNKNOWN")
$topMutationBlocker = [string](Get-Prop -Object $runtimePacket -Name "topMutationBlocker" -Default "UNKNOWN")
$shadowEvidenceCollectorAllowed = Get-BoolValue (Get-Prop -Object $runtimePacket -Name "shadowEvidenceCollectorAllowed" -Default $false)
$reviewProgressAllowed = Get-BoolValue (Get-Prop -Object $runtimePacket -Name "reviewProgressAllowed" -Default $false)

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $exactEvOcoReady) { Add-Missing -List $missing -Value "exact EV/OCO preflight coverage ready" }
if (-not $collectorContractReady) { Add-Missing -List $missing -Value "collector contract coverage ready" }
if ($collectorLocalStatus -ne "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE") { Add-Missing -List $missing -Value "collector local implementation is present but inactive" }
if (-not $duplicateReady -or -not $sourceMarkersPresent) { Add-Missing -List $missing -Value "duplicate hash and replay source evidence ready" }
if (-not $currentEventRiskR0Clear) { Add-Missing -List $missing -Value "current EventRiskControl R0 clear" }
if (-not $reviewProgressAllowed -or -not $shadowEvidenceCollectorAllowed) { Add-Missing -List $missing -Value "runtime proof gap permits review-only shadow progress" }

$remainingMutationBlockers = [System.Collections.Generic.List[string]]::new()
if (-not $ocoRouteCleared) { Add-Item -List $remainingMutationBlockers -Value "OCO_ROUTE_NOT_PROVEN_OR_MISSING:$ocoRouteBlocker" }
if (-not $runtimeSnapshotCleared) { Add-Item -List $remainingMutationBlockers -Value "CANDIDATE_RUNTIME_SNAPSHOT_NOT_CLEARED:$runtimeSnapshotBlocker" }
if ($historicalEventRiskRowsNeedSeparateReview) { Add-Item -List $remainingMutationBlockers -Value "HISTORICAL_EVENT_RISK_ROWS_NEED_SEPARATE_REVIEW" }
$dailyCapStatus = [string](Get-NestedProp -Object $runtimePacket -Path @("gateStatuses", "dailyCapMaxLossBudget") -Default "UNKNOWN")
if ($dailyCapStatus -like "MISSING_*" -or $dailyCapStatus -like "PARTIAL_*") {
    Add-Item -List $remainingMutationBlockers -Value "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_NOT_CLEARED:$dailyCapStatus"
}

$bundleReady = $missing.Count -eq 0
$mutationReady = $bundleReady -and $remainingMutationBlockers.Count -eq 0
$status = if ($bundleReady) {
    "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_INCOMPLETE_NOT_LIVE"
}
$decision = if ($bundleReady) {
    "REVIEW_ENTRY_DEDUP_SHADOW_EXPERIMENT_EVIDENCE_KEEP_MUTATIONS_BLOCKED"
} else {
    "REFRESH_ENTRY_DEDUP_REVIEW_ONLY_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_PACKET"
    status = $status
    decision = $decision
    symbol = [string](Get-Prop -Object $runtimePacket -Name "symbol" -Default "BTCUSDT")
    strategyId = Get-IntValue (Get-Prop -Object $runtimePacket -Name "strategyId" -Default 508)
    intervalCode = [string](Get-Prop -Object $runtimePacket -Name "intervalCode" -Default "1h")
    sourceLogs = [ordered]@{
        runtimeProofGap = $RuntimeProofGapLogPath
        collectorReview = $CollectorReviewLogPath
        duplicateHashReplayProtection = $DuplicateHashLogPath
        ocoRouteProofPreflight = $OcoRoutePreflightLogPath
        exactEvOcoSnapshotCoverage = $ExactEvOcoCoverageLogPath
        eventRiskControlEvidence = $EventRiskControlLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $runtimeLog.Name; ageMinutes = $runtimeLog.AgeMinutes; fresh = $runtimeLog.Fresh },
        [ordered]@{ name = $collectorLog.Name; ageMinutes = $collectorLog.AgeMinutes; fresh = $collectorLog.Fresh },
        [ordered]@{ name = $duplicateLog.Name; ageMinutes = $duplicateLog.AgeMinutes; fresh = $duplicateLog.Fresh },
        [ordered]@{ name = $ocoLog.Name; ageMinutes = $ocoLog.AgeMinutes; fresh = $ocoLog.Fresh },
        [ordered]@{ name = $coverageLog.Name; ageMinutes = $coverageLog.AgeMinutes; fresh = $coverageLog.Fresh },
        [ordered]@{ name = $eventRiskLog.Name; ageMinutes = $eventRiskLog.AgeMinutes; fresh = $eventRiskLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        runtimeProofGap = $runtimeStatus
        collectorReview = $collectorStatus
        duplicateHashReplayProtection = $duplicateStatus
        ocoRouteProofPreflight = $ocoStatus
        exactEvOcoSnapshotCoverage = $coverageStatus
        eventRiskControlEvidence = $eventRiskStatus
    }
    reviewEvidenceSummary = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        tpHitOpportunities = $tpHitOpportunities
        slHitOpportunities = $slHitOpportunities
        ambiguousOpportunities = $ambiguousOpportunities
        exactEvOcoCoverageReady = $exactEvOcoReady
        collectorContractReady = $collectorContractReady
        collectorLocalImplementationStatus = $collectorLocalStatus
        duplicateHashReplayProtectionReady = ($duplicateReady -and $sourceMarkersPresent)
        currentEventRiskR0Clear = $currentEventRiskR0Clear
        eventRiskEvidenceStatus = $eventRiskEvidenceStatus
        topReviewEvidenceGap = $topReviewEvidenceGap
        reviewProgressAllowed = $reviewProgressAllowed
        shadowEvidenceCollectorAllowed = $shadowEvidenceCollectorAllowed
    }
    mutationReadiness = [ordered]@{
        mutationReady = $mutationReady
        topMutationBlocker = $topMutationBlocker
        ocoRouteProofCleared = $ocoRouteCleared
        ocoRouteBlockerReason = $ocoRouteBlocker
        runtimeSnapshotCoverageCleared = $runtimeSnapshotCleared
        runtimeSnapshotBlockerReason = $runtimeSnapshotBlocker
        dailyCapMaxLossRuntimeStatus = $dailyCapStatus
        historicalEventRiskRowsNeedSeparateReview = $historicalEventRiskRowsNeedSeparateReview
        remainingMutationBlockers = @($remainingMutationBlockers)
    }
    requiredBeforeAnyMutation = @(
        "fresh candidate-level runtime entry/EV/OCO/daily-cap/max-loss/duplicate-hash snapshots for each exact opportunity",
        "exact OCO route proof or separately authorized exchange-side dry-run/lifecycle evidence",
        "separate handling of non-auto or missing-OCO open exposure before staged-add/live mutation",
        "fresh EventRiskControl R0 evidence immediately before any later mutation request",
        "separate explicit operator authorization for deploy/env/runtime-evidence-write/policy/order/OCO mutation"
    )
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        shadowReviewReady = $bundleReady
        mutationReady = $mutationReady
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
    nextAction = if ($bundleReady) {
        "Use this bundle as the EntryDedup review-only shadow experiment evidence summary; keep all mutation paths blocked until remaining mutation blockers are separately cleared and authorized."
    } else {
        "Refresh the missing EntryDedup review-only evidence packets before using this bundle."
    }
    notAuthorization = "read-only EntryDedup review-only shadow bundle only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-review-only-shadow-bundle-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup review packets only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_review_only_shadow_bundle_status=$status"
Write-Host "entry_dedup_review_only_shadow_bundle_decision=$decision"
Write-Host "entry_dedup_review_only_shadow_bundle_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_review_only_shadow_bundle_tp_hit_opportunities=$tpHitOpportunities"
Write-Host "entry_dedup_review_only_shadow_bundle_sl_hit_opportunities=$slHitOpportunities"
Write-Host "entry_dedup_review_only_shadow_bundle_exact_ev_oco_ready=$($exactEvOcoReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_collector_contract_ready=$($collectorContractReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_duplicate_hash_ready=$(($duplicateReady -and $sourceMarkersPresent).ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_event_risk_r0_clear=$($currentEventRiskR0Clear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_shadow_review_ready=$($bundleReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_mutation_ready=$($mutationReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_shadow_bundle_top_review_gap=$topReviewEvidenceGap"
Write-Host "entry_dedup_review_only_shadow_bundle_top_mutation_blocker=$topMutationBlocker"
Write-Host ("entry_dedup_review_only_shadow_bundle_remaining_mutation_blockers=" + (ConvertTo-Json -Compress @($remainingMutationBlockers)))
Write-Host ("entry_dedup_review_only_shadow_bundle_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_review_only_shadow_bundle_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "shadow_review_ready=$($bundleReady.ToString().ToLowerInvariant())"
Write-Host "mutation_ready=$($mutationReady.ToString().ToLowerInvariant())"
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
Write-Host "notAuthorization=read-only EntryDedup review-only shadow bundle only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-review-only-shadow-bundle-packet] read-only check complete"

if ($RequireReady -and -not $bundleReady) {
    throw "EntryDedup review-only shadow bundle packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
