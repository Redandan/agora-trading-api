param(
    [string]$MutationBlockerHandoffLogPath = "target/profit-review/entry-dedup-mutation-blocker-handoff-latest.log",
    [string]$OpenExposureReviewLogPath = "target/profit-review/entry-dedup-open-exposure-review-latest.log",
    [string]$HistoricalEventRiskRowReviewLogPath = "target/profit-review/entry-dedup-historical-event-risk-row-review-latest.log",
    [string]$RuntimeSnapshotCollectorActivationRequestLogPath = "target/profit-review/entry-dedup-runtime-snapshot-collector-activation-request-latest.log",
    [string]$BudgetSnapshotReviewRequestLogPath = "target/profit-review/entry-dedup-budget-snapshot-review-request-latest.log",
    [string]$OcoRouteDryRunRequestLogPath = "target/profit-review/entry-dedup-oco-route-dry-run-request-latest.log",
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

foreach ($path in @(
        $MutationBlockerHandoffLogPath,
        $OpenExposureReviewLogPath,
        $HistoricalEventRiskRowReviewLogPath,
        $RuntimeSnapshotCollectorActivationRequestLogPath,
        $BudgetSnapshotReviewRequestLogPath,
        $OcoRouteDryRunRequestLogPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes
$openLog = Read-FreshLog -Name "entry-dedup-open-exposure-review" -PathValue $OpenExposureReviewLogPath -MaxAge $MaxAgeMinutes
$eventRiskLog = Read-FreshLog -Name "entry-dedup-historical-event-risk-row-review" -PathValue $HistoricalEventRiskRowReviewLogPath -MaxAge $MaxAgeMinutes
$runtimeLog = Read-FreshLog -Name "entry-dedup-runtime-snapshot-collector-activation-request" -PathValue $RuntimeSnapshotCollectorActivationRequestLogPath -MaxAge $MaxAgeMinutes
$budgetLog = Read-FreshLog -Name "entry-dedup-budget-snapshot-review-request" -PathValue $BudgetSnapshotReviewRequestLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-dry-run-request" -PathValue $OcoRouteDryRunRequestLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($handoffLog, $openLog, $eventRiskLog, $runtimeLog, $budgetLog, $ocoLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
$openPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $openLog.Text -Prefix "entry_dedup_open_exposure_review_packet=")
$eventRiskPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $eventRiskLog.Text -Prefix "entry_dedup_historical_event_risk_row_review_packet=")
$runtimePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $runtimeLog.Text -Prefix "entry_dedup_runtime_snapshot_collector_activation_request_packet=")
$budgetPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $budgetLog.Text -Prefix "entry_dedup_budget_snapshot_review_request_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_dry_run_request_packet=")
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }
if ($null -eq $openPacket) { Add-Missing -List $missing -Value "open exposure review packet JSON present" }
if ($null -eq $eventRiskPacket) { Add-Missing -List $missing -Value "historical EventRisk row review packet JSON present" }
if ($null -eq $runtimePacket) { Add-Missing -List $missing -Value "runtime snapshot collector activation request packet JSON present" }
if ($null -eq $budgetPacket) { Add-Missing -List $missing -Value "budget snapshot review request packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO route dry-run request packet JSON present" }

$expectedStatuses = [ordered]@{
    mutationBlockerHandoff = "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
    openExposureReview = "READY_FOR_ENTRY_DEDUP_OPEN_EXPOSURE_REVIEW_NOT_LIVE"
    historicalEventRiskRowReview = "READY_FOR_ENTRY_DEDUP_HISTORICAL_EVENT_RISK_ROW_REVIEW_NOT_LIVE"
    runtimeSnapshotCollectorActivationRequest = "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
    budgetSnapshotReviewRequest = "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
    ocoRouteDryRunRequest = "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_DRY_RUN_REQUEST_REVIEW_NOT_LIVE"
}
$actualStatuses = [ordered]@{
    mutationBlockerHandoff = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
    openExposureReview = [string](Get-Prop -Object $openPacket -Name "status" -Default "UNKNOWN")
    historicalEventRiskRowReview = [string](Get-Prop -Object $eventRiskPacket -Name "status" -Default "UNKNOWN")
    runtimeSnapshotCollectorActivationRequest = [string](Get-Prop -Object $runtimePacket -Name "status" -Default "UNKNOWN")
    budgetSnapshotReviewRequest = [string](Get-Prop -Object $budgetPacket -Name "status" -Default "UNKNOWN")
    ocoRouteDryRunRequest = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
}
foreach ($key in $expectedStatuses.Keys) {
    if ($actualStatuses[$key] -ne $expectedStatuses[$key]) {
        Add-Missing -List $missing -Value "$key packet ready"
    }
}

$symbol = [string](Get-Prop -Object $handoffPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $handoffPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $handoffPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default 0)
$remainingMutationBlockers = @(Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "remainingMutationBlockers") -Default @())
$openExposureReviewRequired = Get-BoolValue (Get-NestedProp -Object $openPacket -Path @("openExposureEvidence", "openExposureReviewRequiredBeforeMutation") -Default (Get-NestedProp -Object $openPacket -Path @("reviewEnvelope", "openExposureReviewRequiredBeforeMutation") -Default $false))
$historicalEventRiskReviewRequired = Get-BoolValue (Get-NestedProp -Object $eventRiskPacket -Path @("historicalEventRiskReview", "reviewRequiredBeforeMutation") -Default (Get-NestedProp -Object $eventRiskPacket -Path @("reviewEnvelope", "historicalEventRiskReviewRequiredBeforeMutation") -Default $false))
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $runtimePacket -Path @("requestEvidence", "runtimeSnapshotCoverageCleared") -Default $false)
$budgetSnapshotRuntimeCleared = Get-BoolValue (Get-NestedProp -Object $budgetPacket -Path @("budgetSnapshotEvidence", "budgetSnapshotRuntimeCleared") -Default $false)
$ocoRouteProofCleared = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("requestEvidence", "routeProofCleared") -Default $false)
$exchangeDryRunRequired = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("requestEvidence", "exchangeDryRunRequired") -Default $false)
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked" }

$sourcePacketsReady = $missing.Count -eq 0
$openExposureClearanceBlocked = $openExposureReviewRequired
$historicalEventRiskClearanceBlocked = $historicalEventRiskReviewRequired
$runtimeSnapshotClearanceBlocked = -not $runtimeSnapshotCleared
$budgetSnapshotClearanceBlocked = -not $budgetSnapshotRuntimeCleared
$ocoRouteClearanceBlocked = -not $ocoRouteProofCleared
$anyClearanceBlockerRemaining = $openExposureClearanceBlocked -or $historicalEventRiskClearanceBlocked -or $runtimeSnapshotClearanceBlocked -or $budgetSnapshotClearanceBlocked -or $ocoRouteClearanceBlocked
$orderReadiness = if ($sourcePacketsReady -and $anyClearanceBlockerRemaining) {
    "BLOCKED_REVIEW_REQUESTS_PACKAGED_NOT_LIVE"
} elseif ($sourcePacketsReady) {
    "REVIEW_REQUESTS_PACKAGED_BUT_RECHECK_REQUIRED_NOT_AUTHORIZATION"
} else {
    "BLOCKED_SOURCE_PACKETS_INCOMPLETE_NOT_LIVE"
}
$status = if ($sourcePacketsReady) {
    "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_INCOMPLETE_NOT_LIVE"
}
$decision = if ($sourcePacketsReady) {
    "KEEP_ENTRY_DEDUP_MUTATIONS_BLOCKED_REVIEW_REQUESTS_PACKAGED"
} else {
    "REFRESH_ENTRY_DEDUP_BLOCKER_CLEARANCE_SOURCE_PACKETS"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_CLEARANCE_BOARD_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
        openExposureReview = $OpenExposureReviewLogPath
        historicalEventRiskRowReview = $HistoricalEventRiskRowReviewLogPath
        runtimeSnapshotCollectorActivationRequest = $RuntimeSnapshotCollectorActivationRequestLogPath
        budgetSnapshotReviewRequest = $BudgetSnapshotReviewRequestLogPath
        ocoRouteDryRunRequest = $OcoRouteDryRunRequestLogPath
    }
    sourceStatuses = $actualStatuses
    sourcePacketsReady = $sourcePacketsReady
    clearanceBoard = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        remainingMutationBlockerCount = @($remainingMutationBlockers).Count
        orderReadiness = $orderReadiness
        openExposureClearanceBlocked = $openExposureClearanceBlocked
        historicalEventRiskClearanceBlocked = $historicalEventRiskClearanceBlocked
        runtimeSnapshotClearanceBlocked = $runtimeSnapshotClearanceBlocked
        budgetSnapshotClearanceBlocked = $budgetSnapshotClearanceBlocked
        ocoRouteClearanceBlocked = $ocoRouteClearanceBlocked
        exchangeDryRunRequired = $exchangeDryRunRequired
        anyClearanceBlockerRemaining = $anyClearanceBlockerRemaining
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        clearanceBoardReady = $sourcePacketsReady
        shadowReviewReady = $shadowReviewReady
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
    nextAction = "Use this board to see that review requests are packaged but live mutations remain blocked until every clearance blocker is separately resolved and rechecked."
    notAuthorization = "read-only EntryDedup mutation blocker clearance board only; request-ready packets are not order readiness and do not authorize collector activation, runtime evidence writes, policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-mutation-blocker-clearance-board-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup blocker request packets only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_mutation_blocker_clearance_board_status=$status"
Write-Host "entry_dedup_mutation_blocker_clearance_board_decision=$decision"
Write-Host "entry_dedup_mutation_blocker_clearance_board_order_readiness=$orderReadiness"
Write-Host "entry_dedup_mutation_blocker_clearance_board_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_mutation_blocker_clearance_board_remaining_mutation_blocker_count=$(@($remainingMutationBlockers).Count)"
Write-Host "entry_dedup_mutation_blocker_clearance_board_open_exposure_blocked=$($openExposureClearanceBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_clearance_board_historical_event_risk_blocked=$($historicalEventRiskClearanceBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_clearance_board_runtime_snapshot_blocked=$($runtimeSnapshotClearanceBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_clearance_board_budget_snapshot_blocked=$($budgetSnapshotClearanceBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_clearance_board_oco_route_blocked=$($ocoRouteClearanceBlocked.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_clearance_board_any_blocker_remaining=$($anyClearanceBlockerRemaining.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_mutation_blocker_clearance_board_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_mutation_blocker_clearance_board_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "clearance_board_ready=$($sourcePacketsReady.ToString().ToLowerInvariant())"
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
Write-Host "notAuthorization=read-only EntryDedup mutation blocker clearance board only; request-ready packets are not order readiness and do not authorize collector activation, runtime evidence writes, policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-mutation-blocker-clearance-board-packet] read-only check complete"

if ($RequireReady -and -not $sourcePacketsReady) {
    throw "EntryDedup mutation blocker clearance board is not ready: $status; missing=$(@($missing) -join '; ')"
}
