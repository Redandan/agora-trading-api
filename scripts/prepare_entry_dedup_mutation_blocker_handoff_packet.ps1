param(
    [string]$ShadowBundleLogPath = "target/profit-review/entry-dedup-review-only-shadow-bundle-latest.log",
    [string]$OcoRoutePreflightLogPath = "target/profit-review/entry-dedup-oco-route-proof-preflight-latest.log",
    [string]$ExactOpportunityLogPath = "target/profit-review/entry-dedup-exact-opportunity-staged-add-review-fresh.log",
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
    param([System.Collections.Generic.List[object]]$List, [object]$Value)
    if ($null -ne $Value) { $List.Add($Value) }
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

foreach ($path in @($ShadowBundleLogPath, $OcoRoutePreflightLogPath, $ExactOpportunityLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$bundleLog = Read-FreshLog -Name "entry-dedup-review-only-shadow-bundle" -PathValue $ShadowBundleLogPath -MaxAge $MaxAgeMinutes
$ocoLog = Read-FreshLog -Name "entry-dedup-oco-route-preflight" -PathValue $OcoRoutePreflightLogPath -MaxAge $MaxAgeMinutes
$exactLog = Read-FreshLog -Name "entry-dedup-exact-opportunity" -PathValue $ExactOpportunityLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($bundleLog, $ocoLog, $exactLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$bundlePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $bundleLog.Text -Prefix "entry_dedup_review_only_shadow_bundle_packet=")
$ocoPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $ocoLog.Text -Prefix "entry_dedup_oco_route_proof_preflight_packet=")
$exactPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $exactLog.Text -Prefix "entry_dedup_exact_opportunity_staged_add_review_packet=")

if ($null -eq $bundlePacket) { Add-Missing -List $missing -Value "review-only shadow bundle packet JSON present" }
if ($null -eq $ocoPacket) { Add-Missing -List $missing -Value "OCO route preflight packet JSON present" }
if ($null -eq $exactPacket) { Add-Missing -List $missing -Value "exact opportunity packet JSON present" }

$bundleStatus = [string](Get-Prop -Object $bundlePacket -Name "status" -Default "UNKNOWN")
$ocoStatus = [string](Get-Prop -Object $ocoPacket -Name "status" -Default "UNKNOWN")
$exactStatus = [string](Get-Prop -Object $exactPacket -Name "status" -Default "UNKNOWN")

if ($bundleStatus -ne "READY_FOR_ENTRY_DEDUP_REVIEW_ONLY_SHADOW_BUNDLE_NOT_LIVE") {
    Add-Missing -List $missing -Value "review-only shadow bundle packet ready"
}
if ($ocoStatus -ne "READY_FOR_ENTRY_DEDUP_OCO_ROUTE_PROOF_PREFLIGHT_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "OCO route proof preflight packet ready"
}
if ($exactStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact opportunity staged-add review packet ready"
}

$symbol = [string](Get-Prop -Object $bundlePacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $bundlePacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $bundlePacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $bundlePacket -Path @("reviewEvidenceSummary", "exactOpportunityCount") -Default (Get-Prop -Object $exactPacket -Name "exactOpportunityCount" -Default 0))
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("reviewEnvelope", "mutationReady") -Default $false)
$remainingMutationBlockers = @((Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "remainingMutationBlockers") -Default @()))
$topMutationBlocker = [string](Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "topMutationBlocker") -Default "UNKNOWN")
$ocoRouteProofCleared = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "ocoRouteProofCleared") -Default $false)
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "runtimeSnapshotCoverageCleared") -Default $false)
$runtimeSnapshotBlocker = [string](Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "runtimeSnapshotBlockerReason") -Default "UNKNOWN")
$dailyCapStatus = [string](Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "dailyCapMaxLossRuntimeStatus") -Default "UNKNOWN")
$historicalEventRiskRowsNeedSeparateReview = Get-BoolValue (Get-NestedProp -Object $bundlePacket -Path @("mutationReadiness", "historicalEventRiskRowsNeedSeparateReview") -Default $false)

$routeBlockerReason = [string](Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "routeBlockerReason") -Default "UNKNOWN")
$exchangeDryRunRequired = Get-BoolValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "exchangeDryRunRequired") -Default $false)
$missingOcoRows = Get-IntValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "missingOcoRows") -Default (Get-NestedProp -Object $exactPacket -Path @("openExposure", "missing_oco_rows") -Default 0))
$nonAutoZeroQtyRows = Get-IntValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "nonAutoZeroQtyRows") -Default (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_zero_qty_rows") -Default 0))
$autoTradedOpenRows = Get-IntValue (Get-NestedProp -Object $ocoPacket -Path @("routeEvidence", "autoTradedOpenRows") -Default (Get-NestedProp -Object $exactPacket -Path @("openExposure", "auto_traded_open_rows") -Default 0))
$openSignalRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "open_signal_rows") -Default 0)
$nonAutoOpenRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_open_rows") -Default 0)
$nonAutoEventRiskRows = Get-IntValue (Get-NestedProp -Object $exactPacket -Path @("openExposure", "non_auto_eventrisk_rows") -Default 0)

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review bundle remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked for handoff review" }
if ($remainingMutationBlockers.Count -lt 1) { Add-Missing -List $missing -Value "remaining mutation blockers are present" }

$nextReviewActions = [System.Collections.Generic.List[object]]::new()
if ($missingOcoRows -gt 0 -or $nonAutoZeroQtyRows -gt 0 -or $nonAutoOpenRows -gt 0) {
    Add-Item -List $nextReviewActions -Value ([ordered]@{
            action = "SEPARATE_OPEN_EXPOSURE_REVIEW"
            blocker = "EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO"
            evidence = "open_signal_rows=$openSignalRows; auto_traded_open_rows=$autoTradedOpenRows; non_auto_open_rows=$nonAutoOpenRows; non_auto_zero_qty_rows=$nonAutoZeroQtyRows; missing_oco_rows=$missingOcoRows"
            requiredBeforeClearing = "Operator must separately classify or resolve the non-auto/missing-OCO open signal rows before any staged-add/live mutation request."
        })
}
if (-not $ocoRouteProofCleared) {
    Add-Item -List $nextReviewActions -Value ([ordered]@{
            action = "REQUEST_EXACT_OCO_ROUTE_DRY_RUN_REVIEW"
            blocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING:$routeBlockerReason"
            evidence = "exchange_dry_run_required=$($exchangeDryRunRequired.ToString().ToLowerInvariant()); valid plan shape evidence is review-ready but route proof is not cleared"
            requiredBeforeClearing = "Separate explicit exchange-side dry-run or OCO lifecycle evidence for the exact route."
        })
}
if (-not $runtimeSnapshotCleared) {
    Add-Item -List $nextReviewActions -Value ([ordered]@{
            action = "COLLECT_CANDIDATE_RUNTIME_SNAPSHOTS"
            blocker = "CANDIDATE_RUNTIME_SNAPSHOT_NOT_CLEARED:$runtimeSnapshotBlocker"
            evidence = "exact_opportunity_count=$exactOpportunityCount"
            requiredBeforeClearing = "Fresh candidate-level runtime entry, EV, TP/SL/OCO, duplicate-hash, EventRisk, daily-cap, and max-loss snapshots with orderSent=false."
        })
}
if ($dailyCapStatus -like "MISSING_*" -or $dailyCapStatus -like "PARTIAL_*") {
    Add-Item -List $nextReviewActions -Value ([ordered]@{
            action = "COLLECT_PRODUCTION_DAILY_CAP_MAX_LOSS_SNAPSHOTS"
            blocker = "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_NOT_CLEARED:$dailyCapStatus"
            evidence = "daily_cap_max_loss_runtime_status=$dailyCapStatus"
            requiredBeforeClearing = "Production candidate-level budget snapshots must be present for the exact opportunities."
        })
}
if ($historicalEventRiskRowsNeedSeparateReview -or $nonAutoEventRiskRows -gt 0) {
    Add-Item -List $nextReviewActions -Value ([ordered]@{
            action = "SEPARATE_HISTORICAL_EVENT_RISK_ROW_REVIEW"
            blocker = "HISTORICAL_EVENT_RISK_ROWS_NEED_SEPARATE_REVIEW"
            evidence = "non_auto_eventrisk_rows=$nonAutoEventRiskRows"
            requiredBeforeClearing = "Fresh R0 evidence must be rerun immediately before any later mutation request; historical/non-auto EventRisk rows are not entry authorization."
        })
}
Add-Item -List $nextReviewActions -Value ([ordered]@{
        action = "KEEP_MUTATIONS_BLOCKED"
        blocker = "OPERATOR_AUTHORIZATION_REQUIRED"
        evidence = "shadow_review_ready=$($shadowReviewReady.ToString().ToLowerInvariant()); mutation_ready=$($mutationReady.ToString().ToLowerInvariant())"
        requiredBeforeClearing = "Separate explicit operator authorization for deploy/env/runtime-evidence-write/policy/order/OCO mutation."
    })

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "REVIEW_ENTRY_DEDUP_MUTATION_BLOCKERS_KEEP_SHADOW_ONLY"
} else {
    "REFRESH_ENTRY_DEDUP_MUTATION_BLOCKER_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        reviewOnlyShadowBundle = $ShadowBundleLogPath
        ocoRoutePreflight = $OcoRoutePreflightLogPath
        exactOpportunityStagedAddReview = $ExactOpportunityLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $bundleLog.Name; ageMinutes = $bundleLog.AgeMinutes; fresh = $bundleLog.Fresh },
        [ordered]@{ name = $ocoLog.Name; ageMinutes = $ocoLog.AgeMinutes; fresh = $ocoLog.Fresh },
        [ordered]@{ name = $exactLog.Name; ageMinutes = $exactLog.AgeMinutes; fresh = $exactLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        reviewOnlyShadowBundle = $bundleStatus
        ocoRoutePreflight = $ocoStatus
        exactOpportunityStagedAddReview = $exactStatus
    }
    evidenceSummary = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        shadowReviewReady = $shadowReviewReady
        mutationReady = $mutationReady
        topMutationBlocker = $topMutationBlocker
        remainingMutationBlockers = @($remainingMutationBlockers)
    }
    openExposureEvidence = [ordered]@{
        openSignalRows = $openSignalRows
        autoTradedOpenRows = $autoTradedOpenRows
        nonAutoOpenRows = $nonAutoOpenRows
        nonAutoZeroQtyRows = $nonAutoZeroQtyRows
        nonAutoEventRiskRows = $nonAutoEventRiskRows
        missingOcoRows = $missingOcoRows
    }
    routeAndRuntimeEvidence = [ordered]@{
        ocoRouteProofCleared = $ocoRouteProofCleared
        routeBlockerReason = $routeBlockerReason
        exchangeDryRunRequired = $exchangeDryRunRequired
        runtimeSnapshotCoverageCleared = $runtimeSnapshotCleared
        runtimeSnapshotBlockerReason = $runtimeSnapshotBlocker
        dailyCapMaxLossRuntimeStatus = $dailyCapStatus
        historicalEventRiskRowsNeedSeparateReview = $historicalEventRiskRowsNeedSeparateReview
    }
    nextReviewActions = @($nextReviewActions)
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        handoffReady = $ready
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
    nextAction = if ($ready) {
        "Use this handoff to review remaining EntryDedup mutation blockers; keep the shadow lane review-only until each blocker is separately cleared and a new explicit operator authorization is granted."
    } else {
        "Refresh the missing EntryDedup blocker evidence before using this handoff."
    }
    notAuthorization = "read-only EntryDedup mutation blocker handoff only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
}

Write-Host "[entry-dedup-mutation-blocker-handoff-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup review-only bundle, OCO route preflight, and exact-opportunity logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_mutation_blocker_handoff_status=$status"
Write-Host "entry_dedup_mutation_blocker_handoff_decision=$decision"
Write-Host "entry_dedup_mutation_blocker_handoff_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_mutation_blocker_handoff_blocker_count=$($remainingMutationBlockers.Count)"
Write-Host "entry_dedup_mutation_blocker_handoff_top_mutation_blocker=$topMutationBlocker"
Write-Host "entry_dedup_mutation_blocker_handoff_open_exposure_missing_oco_rows=$missingOcoRows"
Write-Host "entry_dedup_mutation_blocker_handoff_open_exposure_non_auto_zero_qty_rows=$nonAutoZeroQtyRows"
Write-Host "entry_dedup_mutation_blocker_handoff_oco_route_proof_cleared=$($ocoRouteProofCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_handoff_runtime_snapshot_cleared=$($runtimeSnapshotCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_mutation_blocker_handoff_daily_cap_max_loss_runtime_status=$dailyCapStatus"
Write-Host "entry_dedup_mutation_blocker_handoff_historical_event_risk_review_required=$($historicalEventRiskRowsNeedSeparateReview.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_mutation_blocker_handoff_remaining_mutation_blockers=" + (ConvertTo-Json -Compress @($remainingMutationBlockers)))
Write-Host ("entry_dedup_mutation_blocker_handoff_next_review_actions=" + (ConvertTo-Json -Compress -Depth 8 @($nextReviewActions)))
Write-Host ("entry_dedup_mutation_blocker_handoff_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_mutation_blocker_handoff_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "shadow_review_ready=$($shadowReviewReady.ToString().ToLowerInvariant())"
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
Write-Host "notAuthorization=read-only EntryDedup mutation blocker handoff only; does not authorize collector activation, runtime evidence writes, EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, or external backfill/import"
Write-Host "[entry-dedup-mutation-blocker-handoff-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup mutation blocker handoff packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
