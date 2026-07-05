param(
    [string]$ReviewOnlyShadowBundleLogPath = "target/profit-review/entry-dedup-review-only-shadow-bundle-latest.log",
    [string]$ExactEvOcoCoverageLogPath = "target/profit-review/entry-dedup-exact-ev-oco-snapshot-coverage-latest.log",
    [string]$DuplicateHashReplayProtectionLogPath = "target/profit-review/entry-dedup-duplicate-hash-replay-protection-latest.log",
    [string]$EventRiskControlEvidenceLogPath = "target/profit-review/entry-dedup-event-risk-control-evidence-latest.log",
    [string]$HistoricalEventRiskRowReviewLogPath = "target/profit-review/entry-dedup-historical-event-risk-row-review-latest.log",
    [string]$BudgetSnapshotReviewRequestLogPath = "target/profit-review/entry-dedup-budget-snapshot-review-request-latest.log",
    [string]$OcoRouteDryRunRequestLogPath = "target/profit-review/entry-dedup-oco-route-dry-run-request-latest.log",
    [string]$MutationBlockerClearanceBoardLogPath = "target/profit-review/entry-dedup-mutation-blocker-clearance-board-latest.log",
    [string]$ReportPath = "target/profit-review/profit-optimization-report-20260705.md",
    [string]$RunbookPath = "docs/deploy-runbook.md",
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

function Read-TextFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name file not found: $resolved"
    }
    return Get-Content -Raw -LiteralPath $resolved
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @(
        $ReviewOnlyShadowBundleLogPath,
        $ExactEvOcoCoverageLogPath,
        $DuplicateHashReplayProtectionLogPath,
        $EventRiskControlEvidenceLogPath,
        $HistoricalEventRiskRowReviewLogPath,
        $BudgetSnapshotReviewRequestLogPath,
        $OcoRouteDryRunRequestLogPath,
        $MutationBlockerClearanceBoardLogPath,
        $ReportPath,
        $RunbookPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$bundleLog = Read-FreshLog -Name "entry-dedup-review-only-shadow-bundle" -PathValue $ReviewOnlyShadowBundleLogPath -MaxAge $MaxAgeMinutes
$exactLog = Read-FreshLog -Name "entry-dedup-exact-ev-oco-coverage" -PathValue $ExactEvOcoCoverageLogPath -MaxAge $MaxAgeMinutes
$duplicateLog = Read-FreshLog -Name "entry-dedup-duplicate-hash" -PathValue $DuplicateHashReplayProtectionLogPath -MaxAge $MaxAgeMinutes
$eventRiskLog = Read-FreshLog -Name "entry-dedup-event-risk-control" -PathValue $EventRiskControlEvidenceLogPath -MaxAge $MaxAgeMinutes
$historicalEventRiskLog = Read-FreshLog -Name "entry-dedup-historical-event-risk-row-review" -PathValue $HistoricalEventRiskRowReviewLogPath -MaxAge $MaxAgeMinutes
$budgetLog = Read-FreshLog -Name "entry-dedup-budget-snapshot-review-request" -PathValue $BudgetSnapshotReviewRequestLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-dry-run-request" -PathValue $OcoRouteDryRunRequestLogPath -MaxAge $MaxAgeMinutes
$boardLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-clearance-board" -PathValue $MutationBlockerClearanceBoardLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($bundleLog, $exactLog, $duplicateLog, $eventRiskLog, $historicalEventRiskLog, $budgetLog, $ocoLog, $boardLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$bundlePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $bundleLog.Text -Prefix "entry_dedup_review_only_shadow_bundle_packet=")
$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_ev_oco_snapshot_coverage_packet=")
$duplicatePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $duplicateLog.Text -Prefix "entry_dedup_duplicate_hash_replay_protection_packet=")
$eventRiskPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $eventRiskLog.Text -Prefix "entry_dedup_event_risk_control_evidence_packet=")
$historicalEventRiskPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $historicalEventRiskLog.Text -Prefix "entry_dedup_historical_event_risk_row_review_packet=")
$budgetPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $budgetLog.Text -Prefix "entry_dedup_budget_snapshot_review_request_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_dry_run_request_packet=")
$boardPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $boardLog.Text -Prefix "entry_dedup_mutation_blocker_clearance_board_packet=")
if ($null -eq $bundlePacket) { Add-Missing -List $missing -Value "review-only shadow bundle packet JSON present" }
if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact EV/OCO coverage packet JSON present" }
if ($null -eq $duplicatePacket) { Add-Missing -List $missing -Value "duplicate-hash replay packet JSON present" }
if ($null -eq $eventRiskPacket) { Add-Missing -List $missing -Value "EventRiskControl packet JSON present" }
if ($null -eq $historicalEventRiskPacket) { Add-Missing -List $missing -Value "historical EventRisk row packet JSON present" }
if ($null -eq $budgetPacket) { Add-Missing -List $missing -Value "budget snapshot request packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO dry-run request packet JSON present" }
if ($null -eq $boardPacket) { Add-Missing -List $missing -Value "clearance board packet JSON present" }

$expectedStatuses = [ordered]@{
    reviewOnlyShadowBundle = "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE"
    exactEvOcoCoverage = "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE"
    duplicateHashReplayProtection = "READY_FOR_ENTRY_DEDUP_DUPLICATE_HASH_REPLAY_PROTECTION_REVIEW_NOT_LIVE"
    eventRiskControlEvidence = "READY_FOR_ENTRY_DEDUP_EVENT_RISK_CONTROL_EVIDENCE_REVIEW_NOT_LIVE"
    historicalEventRiskRowReview = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
    budgetSnapshotReviewRequest = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
    ocoRouteDryRunRequest = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
    clearanceBoard = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_NOT_LIVE"
}
$actualStatuses = [ordered]@{
    reviewOnlyShadowBundle = [string](Get-Prop -Object $bundlePacket -Name "status" -Default "UNKNOWN")
    exactEvOcoCoverage = [string](Get-Prop -Object $exactPacket -Name "status" -Default "UNKNOWN")
    duplicateHashReplayProtection = [string](Get-Prop -Object $duplicatePacket -Name "status" -Default "UNKNOWN")
    eventRiskControlEvidence = [string](Get-Prop -Object $eventRiskPacket -Name "status" -Default "UNKNOWN")
    historicalEventRiskRowReview = [string](Get-Prop -Object $historicalEventRiskPacket -Name "status" -Default "UNKNOWN")
    budgetSnapshotReviewRequest = [string](Get-Prop -Object $budgetPacket -Name "status" -Default "UNKNOWN")
    ocoRouteDryRunRequest = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
    clearanceBoard = [string](Get-Prop -Object $boardPacket -Name "status" -Default "UNKNOWN")
}
foreach ($key in $expectedStatuses.Keys) {
    if ($actualStatuses[$key] -ne $expectedStatuses[$key]) {
        Add-Missing -List $missing -Value "$key packet ready"
    }
}

$symbol = [string](Get-Prop -Object $bundlePacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $bundlePacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $bundlePacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $bundlePacket -Path @("reviewEvidenceSummary", "exactOpportunityCount") -Default (Get-NestedProp -Object $boardPacket -Path @("clearanceBoard", "exactOpportunityCount") -Default 0))
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)
$orderReadiness = [string](Get-NestedProp -Object $boardPacket -Path @("clearanceBoard", "orderReadiness") -Default "UNKNOWN")

$exactEvOcoPreflightReady = Get-BoolValue (Get-NestedProp -Object $exactPacket -Path @("reviewEnvelope", "exactEvOcoCoverageReady") -Default (Get-NestedProp -Object $bundlePacket -Path @("reviewEvidenceSummary", "exactEvOcoCoverageReady") -Default $false))
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $exactPacket -Path @("runtimeSnapshotEvidence", "runtimeSnapshotCoverageCleared") -Default $false)
$duplicateHashReady = (Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("writePathSourceEvidence", "sourceMarkersPresent") -Default $false)) `
    -and (Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("collectorContractEvidence", "hasDuplicateCandidateHash") -Default $false)) `
    -and (Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("collectorContractEvidence", "hasReplayCandidateId") -Default $false)) `
    -and (Get-BoolValue (Get-NestedProp -Object $duplicatePacket -Path @("exactOpportunityEvidence", "duplicateSuppressionCountConsistent") -Default $false))
$eventRiskCurrentR0Clear = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("reviewEnvelope", "currentEventRiskR0Clear") -Default (Get-NestedProp -Object $eventRiskPacket -Path @("eventRiskEvidence", "currentR0Clear") -Default $false))
$historicalEventRiskBlocked = Get-BoolValue (Get-NestedProp -Object $historicalEventRiskPacket -Path @("historicalEventRiskReview", "reviewRequiredBeforeMutation") -Default (Get-NestedProp -Object $boardPacket -Path @("clearanceBoard", "historicalEventRiskClearanceBlocked") -Default $false))
$budgetContractReady = (Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "contractIncludesDailyCapSnapshot") -Default $false)) `
    -and (Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "contractIncludesMaxLossSnapshot") -Default $false))
$budgetRuntimeCleared = Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "budgetSnapshotRuntimeCleared") -Default $false)
$ocoRouteBlocked = Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("clearanceBoard", "ocoRouteClearanceBlocked") -Default $false)
$anyBlockerRemaining = Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("clearanceBoard", "anyClearanceBlockerRemaining") -Default $false)

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Mutation Blocker Clearance Board") -and $reportText.Contains("BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE") -and $reportText.Contains("Non-Authorization")
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_mutation_blocker_clearance_board_packet.ps1") -and $runbookNormalizedText.Contains("request-ready packets are not order readiness")

$allMutationFlagsFalse = -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "collectorActivationAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "runtimeEvidenceWriteAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "entryDedupPolicyChangeAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "livePolicyChangeAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "positionOrOcoMutationAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "deployOrEnvChangeAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "dbMutationAllowed") -Default $true)) `
    -and -not (Get-BoolValue (Get-NestedProp -Object $boardPacket -Path @("reviewEnvelope", "exchangeMutationAllowed") -Default $true))

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "review-only shadow bundle remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked" }
if (-not $exactEvOcoPreflightReady) { Add-Missing -List $missing -Value "exact EV/OCO preflight evidence ready" }
if (-not $duplicateHashReady) { Add-Missing -List $missing -Value "duplicate-hash replay protection ready" }
if (-not $eventRiskCurrentR0Clear) { Add-Missing -List $missing -Value "current EventRiskControl R0 clear" }
if (-not $budgetContractReady) { Add-Missing -List $missing -Value "daily-cap/max-loss local collector contract ready" }
if (-not $ocoRouteBlocked) { Add-Missing -List $missing -Value "OCO route blocker remains visible before any order readiness" }
if (-not $anyBlockerRemaining) { Add-Missing -List $missing -Value "clearance board keeps blocker remaining visible" }
if ($orderReadiness -ne "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE") { Add-Missing -List $missing -Value "order readiness remains blocked while review requests are packaged" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes clearance board and non-authorization" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes clearance board instructions" }
if (-not $allMutationFlagsFalse) { Add-Missing -List $missing -Value "all mutation flags remain false" }

$traceabilityReady = $missing.Count -eq 0
$status = if ($traceabilityReady) {
    "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_INCOMPLETE_NOT_LIVE"
}
$reviewScopeStatus = if ($traceabilityReady -and $anyBlockerRemaining) {
    "REVIEW_ONLY_EVIDENCE_PACKAGED_LIVE_CLEARANCE_BLOCKED"
} elseif ($traceabilityReady) {
    "REVIEW_ONLY_EVIDENCE_PACKAGED_RECHECK_REQUIRED_NOT_AUTHORIZATION"
} else {
    "REVIEW_ONLY_EVIDENCE_INCOMPLETE"
}
$decision = if ($traceabilityReady) {
    "KEEP_ENTRY_DEDUP_REVIEW_ONLY_SCOPE_READY_AND_LIVE_MUTATIONS_BLOCKED"
} else {
    "REFRESH_ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_REVIEW_ONLY_OBJECTIVE_TRACEABILITY_PACKET"
    status = $status
    decision = $decision
    reviewScopeStatus = $reviewScopeStatus
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceStatuses = $actualStatuses
    requirementTraceability = [ordered]@{
        directReviewOnlyShadowExperiment = [ordered]@{ ready = $shadowReviewReady; evidence = $ReviewOnlyShadowBundleLogPath }
        evOcoPreflightSnapshot = [ordered]@{ ready = $exactEvOcoPreflightReady; runtimeSnapshotCleared = $runtimeSnapshotCleared; evidence = $ExactEvOcoCoverageLogPath }
        eventRiskControl = [ordered]@{ currentR0Clear = $eventRiskCurrentR0Clear; historicalRowReviewRequiredBeforeMutation = $historicalEventRiskBlocked; evidence = @($EventRiskControlEvidenceLogPath, $HistoricalEventRiskRowReviewLogPath) }
        duplicateHashReplayProtection = [ordered]@{ ready = $duplicateHashReady; evidence = $DuplicateHashReplayProtectionLogPath }
        dailyCapMaxLoss = [ordered]@{ localCollectorContractReady = $budgetContractReady; runtimeSnapshotCleared = $budgetRuntimeCleared; evidence = $BudgetSnapshotReviewRequestLogPath }
        ocoRouteProof = [ordered]@{ routeBlocked = $ocoRouteBlocked; requestPackaged = $true; evidence = $OcoRouteDryRunRequestLogPath }
        nonLiveGuardrails = [ordered]@{ allMutationFlagsFalse = $allMutationFlagsFalse; orderReadiness = $orderReadiness; evidence = $MutationBlockerClearanceBoardLogPath }
        reportAndRunbook = [ordered]@{ reportUpdated = $reportUpdated; runbookUpdated = $runbookUpdated; report = $ReportPath; runbook = $RunbookPath }
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        traceabilityReady = $traceabilityReady
        reviewOnlyToolingReady = $traceabilityReady
        liveExecutionReady = $false
        mutationReady = $false
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
    nextAction = "Use this traceability packet to prove review-only evidence coverage and to keep live execution blocked until every clearance blocker is separately resolved and rechecked."
    notAuthorization = "read-only EntryDedup review-only objective traceability packet only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-review-only-objective-traceability-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup review-only evidence packets plus local report/runbook only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_review_only_objective_traceability_status=$status"
Write-Host "entry_dedup_review_only_objective_traceability_decision=$decision"
Write-Host "entry_dedup_review_only_objective_traceability_review_scope_status=$reviewScopeStatus"
Write-Host "entry_dedup_review_only_objective_traceability_order_readiness=$orderReadiness"
Write-Host "entry_dedup_review_only_objective_traceability_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_review_only_objective_traceability_exact_ev_oco_preflight_ready=$($exactEvOcoPreflightReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_runtime_snapshot_cleared=$($runtimeSnapshotCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_duplicate_hash_ready=$($duplicateHashReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_event_risk_current_r0_clear=$($eventRiskCurrentR0Clear.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_historical_event_risk_blocked=$($historicalEventRiskBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_budget_contract_ready=$($budgetContractReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_budget_runtime_cleared=$($budgetRuntimeCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_oco_route_blocked=$($ocoRouteBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_any_blocker_remaining=$($anyBlockerRemaining.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_review_only_objective_traceability_all_mutation_flags_false=$($allMutationFlagsFalse.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_review_only_objective_traceability_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_review_only_objective_traceability_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "traceability_ready=$($traceabilityReady.ToString().ToLowerInvariant())"
Write-Host "review_only_tooling_ready=$($traceabilityReady.ToString().ToLowerInvariant())"
Write-Host "live_execution_ready=false"
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
Write-Host "notAuthorization=read-only EntryDedup review-only objective traceability packet only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-review-only-objective-traceability-packet] read-only check complete"

if ($RequireReady -and -not $traceabilityReady) {
    throw "EntryDedup review-only objective traceability packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
