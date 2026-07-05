param(
    [string]$RuntimeSnapshotCollectorActivationRequestLogPath = "target/profit-review/entry-dedup-runtime-snapshot-collector-activation-request-latest.log",
    [string]$CandidateRuntimeSnapshotCollectorReviewLogPath = "target/profit-review/entry-dedup-candidate-runtime-snapshot-collector-review-latest.log",
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

function Test-TextListContains {
    param([object]$Value, [string]$Needle)
    foreach ($item in @($Value)) {
        if (([string]$item).Contains($Needle)) { return $true }
    }
    return $false
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @($RuntimeSnapshotCollectorActivationRequestLogPath, $CandidateRuntimeSnapshotCollectorReviewLogPath, $MutationBlockerHandoffLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$activationLog = Read-FreshLog -Name "entry-dedup-runtime-snapshot-collector-activation-request" -PathValue $RuntimeSnapshotCollectorActivationRequestLogPath -MaxAge $MaxAgeMinutes
$collectorLog = Read-FreshLog -Name "entry-dedup-candidate-runtime-snapshot-collector-review" -PathValue $CandidateRuntimeSnapshotCollectorReviewLogPath -MaxAge $MaxAgeMinutes
$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($activationLog, $collectorLog, $handoffLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$activationPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $activationLog.Text -Prefix "entry_dedup_runtime_snapshot_collector_activation_request_packet=")
$collectorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $collectorLog.Text -Prefix "entry_dedup_candidate_runtime_snapshot_collector_review_packet=")
$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
if ($null -eq $activationPacket) { Add-Missing -List $missing -Value "runtime snapshot collector activation request packet JSON present" }
if ($null -eq $collectorPacket) { Add-Missing -List $missing -Value "candidate runtime snapshot collector review packet JSON present" }
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }

$activationStatus = [string](Get-Prop -Object $activationPacket -Name "status" -Default "UNKNOWN")
$collectorStatus = [string](Get-Prop -Object $collectorPacket -Name "status" -Default "UNKNOWN")
$handoffStatus = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
if ($activationStatus -ne "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE") {
    Add-Missing -List $missing -Value "runtime snapshot collector activation request packet ready"
}
if ($collectorStatus -ne "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector review packet ready"
}
if ($handoffStatus -ne "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE") {
    Add-Missing -List $missing -Value "mutation blocker handoff packet ready"
}

$symbol = [string](Get-Prop -Object $activationPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $activationPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $activationPacket -Name "intervalCode" -Default "1h")
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "exactOpportunityCount") -Default (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default 0))
$implementationStatus = [string](Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "collectorLocalImplementationStatus") -Default (Get-Prop -Object $collectorPacket -Name "localImplementationStatus" -Default "UNKNOWN"))
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "runtimeSnapshotCoverageCleared") -Default $false)
$runtimeSnapshotBlocker = [string](Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "runtimeSnapshotBlockerReason") -Default "UNKNOWN")
$dailyCapMaxLossRuntimeStatus = [string](Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "dailyCapMaxLossRuntimeStatus") -Default (Get-NestedProp -Object $handoffPacket -Path @("routeAndRuntimeEvidence", "dailyCapMaxLossRuntimeStatus") -Default "UNKNOWN"))
$candidateRuntimeEntryPlanRows = Get-IntValue (Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "candidateRuntimeEntryPlanRows") -Default 0)
$candidateRuntimeOcoPlanRows = Get-IntValue (Get-NestedProp -Object $activationPacket -Path @("requestEvidence", "candidateRuntimeOcoPlanRows") -Default 0)
$requestReady = Get-BoolValue (Get-NestedProp -Object $activationPacket -Path @("reviewEnvelope", "requestReady") -Default $false)
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $activationPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false))
$mutationReady = Get-BoolValue (Get-NestedProp -Object $activationPacket -Path @("reviewEnvelope", "mutationReady") -Default (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false))

$serviceMarkers = Get-NestedProp -Object $collectorPacket -Path @("sourceContract", "serviceRequiredMarkers") -Default @()
$optimizerMarkers = Get-NestedProp -Object $collectorPacket -Path @("sourceContract", "optimizerRequiredMarkers") -Default @()
$contextKeys = Get-Prop -Object $collectorPacket -Name "proposedCollectorContextKeys" -Default @()
$contractIncludesDailyCapSnapshot = (Test-TextListContains -Value $serviceMarkers -Needle "dailyCapSnapshot") -and (Test-TextListContains -Value $optimizerMarkers -Needle "dailyCapSnapshot") -and (Test-TextListContains -Value $contextKeys -Needle "dailyCapSnapshot")
$contractIncludesMaxLossSnapshot = (Test-TextListContains -Value $serviceMarkers -Needle "maxLossSnapshot") -and (Test-TextListContains -Value $optimizerMarkers -Needle "maxLossSnapshot") -and (Test-TextListContains -Value $contextKeys -Needle "maxLossSnapshot")
$budgetSnapshotRuntimeCleared = $dailyCapMaxLossRuntimeStatus -notmatch "MISSING|UNKNOWN|NOT_CLEARED" -and $dailyCapMaxLossRuntimeStatus -ne ""
$classification = if (-not $budgetSnapshotRuntimeCleared -and $contractIncludesDailyCapSnapshot -and $contractIncludesMaxLossSnapshot) {
    "PRODUCTION_DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_REQUIRED"
} elseif ($budgetSnapshotRuntimeCleared) {
    "DAILY_CAP_MAX_LOSS_RUNTIME_SNAPSHOT_CLEAR_FOR_REVIEW_NOT_AUTHORIZATION"
} else {
    "DAILY_CAP_MAX_LOSS_LOCAL_CONTRACT_OR_RUNTIME_EVIDENCE_INCOMPLETE"
}

if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if ($implementationStatus -ne "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE") { Add-Missing -List $missing -Value "collector implementation is local and inactive" }
if (-not $requestReady) { Add-Missing -List $missing -Value "runtime snapshot collector activation request remains ready" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked before budget snapshot review" }
if ($runtimeSnapshotCleared) { Add-Missing -List $missing -Value "runtime snapshot gap remains the requested budget snapshot target" }
if ($budgetSnapshotRuntimeCleared) { Add-Missing -List $missing -Value "daily cap/max-loss runtime snapshot gap remains uncleared" }
if (-not $contractIncludesDailyCapSnapshot) { Add-Missing -List $missing -Value "local collector contract includes dailyCapSnapshot" }
if (-not $contractIncludesMaxLossSnapshot) { Add-Missing -List $missing -Value "local collector contract includes maxLossSnapshot" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "PREPARE_OPERATOR_REVIEW_FOR_DAILY_CAP_MAX_LOSS_SHADOW_SNAPSHOT_COLLECTION_NOT_DEPLOYMENT"
} else {
    "REFRESH_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_EVIDENCE"
}
$confirmText = "AUTHORIZE_ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_ONLY"

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SNAPSHOT_REVIEW_REQUEST_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        runtimeSnapshotCollectorActivationRequest = $RuntimeSnapshotCollectorActivationRequestLogPath
        candidateRuntimeSnapshotCollectorReview = $CandidateRuntimeSnapshotCollectorReviewLogPath
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $activationLog.Name; ageMinutes = $activationLog.AgeMinutes; fresh = $activationLog.Fresh },
        [ordered]@{ name = $collectorLog.Name; ageMinutes = $collectorLog.AgeMinutes; fresh = $collectorLog.Fresh },
        [ordered]@{ name = $handoffLog.Name; ageMinutes = $handoffLog.AgeMinutes; fresh = $handoffLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        runtimeSnapshotCollectorActivationRequest = $activationStatus
        candidateRuntimeSnapshotCollectorReview = $collectorStatus
        mutationBlockerHandoff = $handoffStatus
    }
    budgetSnapshotEvidence = [ordered]@{
        classification = $classification
        exactOpportunityCount = $exactOpportunityCount
        collectorLocalImplementationStatus = $implementationStatus
        contractIncludesDailyCapSnapshot = $contractIncludesDailyCapSnapshot
        contractIncludesMaxLossSnapshot = $contractIncludesMaxLossSnapshot
        runtimeSnapshotCoverageCleared = $runtimeSnapshotCleared
        runtimeSnapshotBlockerReason = $runtimeSnapshotBlocker
        dailyCapMaxLossRuntimeStatus = $dailyCapMaxLossRuntimeStatus
        budgetSnapshotRuntimeCleared = $budgetSnapshotRuntimeCleared
        candidateRuntimeEntryPlanRows = $candidateRuntimeEntryPlanRows
        candidateRuntimeOcoPlanRows = $candidateRuntimeOcoPlanRows
    }
    proposedOperatorRequest = [ordered]@{
        requestName = "ENTRY_DEDUP_DAILY_CAP_MAX_LOSS_SHADOW_SNAPSHOT_REVIEW"
        confirmText = $confirmText
        requestedScope = "review-only authorization template for later shadow collection of daily-cap and max-loss snapshot evidence; this packet itself deploys nothing and writes no runtime evidence"
        requiredBeforeAnyActivation = @(
            "separate explicit operator authorization for deploy/env/runtime-evidence-write",
            "collector must remain shadow-only with orderSent=false and no OCO/order/Telegram/exchange mutation",
            "post-activation read-only verification must prove dailyCapSnapshot and maxLossSnapshot are present for exact opportunities",
            "fresh OCO route, open-exposure, EventRisk, and runtime snapshot blockers must be rechecked before any mutation request"
        )
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        budgetSnapshotReviewRequestReady = $ready
        shadowReviewReady = $shadowReviewReady
        mutationReady = $false
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        deployOrEnvChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        livePolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this as a review request for daily-cap/max-loss shadow snapshot evidence only. Do not activate collectors, deploy, change env, or write runtime evidence without separate explicit authorization."
    notAuthorization = "read-only EntryDedup daily-cap/max-loss snapshot review request packet only; does not activate collectors, write runtime evidence, deploy, change production env, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-budget-snapshot-review-request-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup runtime collector activation request, collector contract review, and mutation-blocker handoff logs only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_budget_snapshot_review_request_status=$status"
Write-Host "entry_dedup_budget_snapshot_review_request_decision=$decision"
Write-Host "entry_dedup_budget_snapshot_review_request_classification=$classification"
Write-Host "entry_dedup_budget_snapshot_review_request_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_budget_snapshot_review_request_local_implementation_status=$implementationStatus"
Write-Host "entry_dedup_budget_snapshot_review_request_contract_includes_daily_cap_snapshot=$($contractIncludesDailyCapSnapshot.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_budget_snapshot_review_request_contract_includes_max_loss_snapshot=$($contractIncludesMaxLossSnapshot.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_budget_snapshot_review_request_runtime_snapshot_cleared=$($runtimeSnapshotCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_budget_snapshot_review_request_runtime_snapshot_blocker=$runtimeSnapshotBlocker"
Write-Host "entry_dedup_budget_snapshot_review_request_daily_cap_max_loss_runtime_status=$dailyCapMaxLossRuntimeStatus"
Write-Host "entry_dedup_budget_snapshot_review_request_budget_snapshot_runtime_cleared=$($budgetSnapshotRuntimeCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_budget_snapshot_review_request_candidate_entry_plan_rows=$candidateRuntimeEntryPlanRows"
Write-Host "entry_dedup_budget_snapshot_review_request_candidate_oco_plan_rows=$candidateRuntimeOcoPlanRows"
Write-Host "entry_dedup_budget_snapshot_review_request_confirm_text=$confirmText"
Write-Host ("entry_dedup_budget_snapshot_review_request_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_budget_snapshot_review_request_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "budget_snapshot_review_request_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "mutation_ready=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup daily-cap/max-loss snapshot review request packet only; does not activate collectors, write runtime evidence, deploy, change production env, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-budget-snapshot-review-request-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup daily-cap/max-loss snapshot review request packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
