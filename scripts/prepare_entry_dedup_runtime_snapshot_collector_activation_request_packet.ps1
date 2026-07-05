param(
    [string]$CollectorReviewLogPath = "target/profit-review/entry-dedup-candidate-runtime-snapshot-collector-review-latest.log",
    [string]$ExactEvOcoCoverageLogPath = "target/profit-review/entry-dedup-exact-ev-oco-snapshot-coverage-latest.log",
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

foreach ($path in @($CollectorReviewLogPath, $ExactEvOcoCoverageLogPath, $MutationBlockerHandoffLogPath)) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$collectorLog = Read-FreshLog -Name "entry-dedup-candidate-runtime-snapshot-collector" -PathValue $CollectorReviewLogPath -MaxAge $MaxAgeMinutes
$coverageLog = Read-FreshLog -Name "entry-dedup-exact-ev-oco-coverage" -PathValue $ExactEvOcoCoverageLogPath -MaxAge $MaxAgeMinutes
$handoffLog = Read-FreshLog -Name "entry-dedup-mutation-blocker-handoff" -PathValue $MutationBlockerHandoffLogPath -MaxAge $MaxAgeMinutes

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($collectorLog, $coverageLog, $handoffLog)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$collectorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $collectorLog.Text -Prefix "entry_dedup_candidate_runtime_snapshot_collector_review_packet=")
$coveragePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $coverageLog.Text -Prefix "entry_dedup_exact_ev_oco_snapshot_coverage_packet=")
$handoffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $handoffLog.Text -Prefix "entry_dedup_mutation_blocker_handoff_packet=")
if ($null -eq $collectorPacket) { Add-Missing -List $missing -Value "collector review packet JSON present" }
if ($null -eq $coveragePacket) { Add-Missing -List $missing -Value "exact EV/OCO coverage packet JSON present" }
if ($null -eq $handoffPacket) { Add-Missing -List $missing -Value "mutation blocker handoff packet JSON present" }

$collectorStatus = [string](Get-Prop -Object $collectorPacket -Name "status" -Default "UNKNOWN")
$coverageStatus = [string](Get-Prop -Object $coveragePacket -Name "status" -Default "UNKNOWN")
$handoffStatus = [string](Get-Prop -Object $handoffPacket -Name "status" -Default "UNKNOWN")
if ($collectorStatus -ne "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "candidate runtime snapshot collector review packet ready"
}
if ($coverageStatus -ne "READY_FOR_ENTRY_DEDUP_EXACT_EV_OCO_SNAPSHOT_COVERAGE_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "exact EV/OCO coverage packet ready"
}
if ($handoffStatus -ne "READY_FOR_ENTRY_DEDUP_MUTATION_BLOCKER_HANDOFF_NOT_LIVE") {
    Add-Missing -List $missing -Value "mutation blocker handoff packet ready"
}

$symbol = [string](Get-Prop -Object $handoffPacket -Name "symbol" -Default "BTCUSDT")
$strategyId = Get-IntValue (Get-Prop -Object $handoffPacket -Name "strategyId" -Default 508)
$intervalCode = [string](Get-Prop -Object $handoffPacket -Name "intervalCode" -Default "1h")
$implementationStatus = [string](Get-Prop -Object $collectorPacket -Name "localImplementationStatus" -Default "UNKNOWN")
$collectorContractReady = Get-BoolValue (Get-NestedProp -Object $coveragePacket -Path @("collectorContractCoverage", "collectorContractReady") -Default $false)
$runtimeSnapshotCleared = Get-BoolValue (Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "runtimeSnapshotCoverageCleared") -Default $false)
$runtimeSnapshotBlocker = [string](Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "runtimeSnapshotBlockerReason") -Default "UNKNOWN")
$candidateRuntimeEntryPlanRows = Get-IntValue (Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "candidateRuntimeEntryPlanRows") -Default 0)
$candidateRuntimeOcoPlanRows = Get-IntValue (Get-NestedProp -Object $coveragePacket -Path @("runtimeSnapshotEvidence", "candidateRuntimeOcoPlanRows") -Default 0)
$exactOpportunityCount = Get-IntValue (Get-NestedProp -Object $coveragePacket -Path @("exactOpportunityCoverage", "exactOpportunityCount") -Default (Get-NestedProp -Object $handoffPacket -Path @("evidenceSummary", "exactOpportunityCount") -Default 0))
$dailyCapStatus = [string](Get-NestedProp -Object $handoffPacket -Path @("routeAndRuntimeEvidence", "dailyCapMaxLossRuntimeStatus") -Default "UNKNOWN")
$shadowReviewReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "shadowReviewReady") -Default $false)
$mutationReady = Get-BoolValue (Get-NestedProp -Object $handoffPacket -Path @("reviewEnvelope", "mutationReady") -Default $false)

if ($implementationStatus -ne "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE") {
    Add-Missing -List $missing -Value "collector implementation is local and inactive"
}
if (-not $collectorContractReady) { Add-Missing -List $missing -Value "collector contract coverage ready" }
if ($runtimeSnapshotCleared) { Add-Missing -List $missing -Value "runtime snapshot gap remains the requested activation target" }
if ($exactOpportunityCount -lt 1) { Add-Missing -List $missing -Value "exact opportunity count is positive" }
if (-not $shadowReviewReady) { Add-Missing -List $missing -Value "shadow review remains ready" }
if ($mutationReady) { Add-Missing -List $missing -Value "mutation remains blocked before collector activation request review" }

$requestReady = $missing.Count -eq 0
$status = if ($requestReady) {
    "READY_FOR_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_INCOMPLETE_NOT_LIVE"
}
$decision = if ($requestReady) {
    "PREPARE_OPERATOR_REVIEW_FOR_SHADOW_COLLECTOR_ACTIVATION_NOT_DEPLOYMENT"
} else {
    "REFRESH_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_EVIDENCE"
}
$confirmText = "AUTHORIZE_ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_ONLY"

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_ACTIVATION_REQUEST_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [ordered]@{
        collectorReview = $CollectorReviewLogPath
        exactEvOcoCoverage = $ExactEvOcoCoverageLogPath
        mutationBlockerHandoff = $MutationBlockerHandoffLogPath
    }
    sourceLogFreshness = @(
        [ordered]@{ name = $collectorLog.Name; ageMinutes = $collectorLog.AgeMinutes; fresh = $collectorLog.Fresh },
        [ordered]@{ name = $coverageLog.Name; ageMinutes = $coverageLog.AgeMinutes; fresh = $coverageLog.Fresh },
        [ordered]@{ name = $handoffLog.Name; ageMinutes = $handoffLog.AgeMinutes; fresh = $handoffLog.Fresh }
    )
    sourceStatuses = [ordered]@{
        collectorReview = $collectorStatus
        exactEvOcoCoverage = $coverageStatus
        mutationBlockerHandoff = $handoffStatus
    }
    requestEvidence = [ordered]@{
        exactOpportunityCount = $exactOpportunityCount
        collectorLocalImplementationStatus = $implementationStatus
        collectorContractReady = $collectorContractReady
        runtimeSnapshotCoverageCleared = $runtimeSnapshotCleared
        runtimeSnapshotBlockerReason = $runtimeSnapshotBlocker
        candidateRuntimeEntryPlanRows = $candidateRuntimeEntryPlanRows
        candidateRuntimeOcoPlanRows = $candidateRuntimeOcoPlanRows
        dailyCapMaxLossRuntimeStatus = $dailyCapStatus
    }
    proposedOperatorRequest = [ordered]@{
        requestName = "ENTRY_DEDUP_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW"
        confirmText = $confirmText
        requestedScope = "review-only authorization template for later shadow collector activation; this packet itself deploys nothing and writes no runtime evidence"
        requiredBeforeAnyActivation = @(
            "separate explicit operator authorization for deploy/env/runtime-evidence-write",
            "fresh read-only bundle, open exposure review, EventRisk R0, and OCO route preflight immediately before activation",
            "collector must write shadow rows only with orderSent=false and no OCO/order/Telegram/exchange mutation",
            "post-activation read-only verification must prove candidate runtime entry/EV/OCO/daily-cap/max-loss snapshots exist for exact opportunities"
        )
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        requestReady = $requestReady
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
    nextAction = if ($requestReady) {
        "Use this as an operator-review request template only. Do not activate the collector, deploy, change env, or write runtime evidence without separate explicit authorization."
    } else {
        "Refresh the missing EntryDedup collector activation request evidence before operator review."
    }
    notAuthorization = "read-only EntryDedup runtime snapshot collector activation request packet only; does not activate collectors, write runtime evidence, deploy, change production env, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-runtime-snapshot-collector-activation-request-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved EntryDedup collector review, exact EV/OCO coverage, and mutation-blocker handoff logs only; no SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_status=$status"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_decision=$decision"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_exact_opportunity_count=$exactOpportunityCount"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_local_implementation_status=$implementationStatus"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_contract_ready=$($collectorContractReady.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_runtime_snapshot_cleared=$($runtimeSnapshotCleared.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_runtime_snapshot_blocker=$runtimeSnapshotBlocker"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_candidate_entry_plan_rows=$candidateRuntimeEntryPlanRows"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_candidate_oco_plan_rows=$candidateRuntimeOcoPlanRows"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_daily_cap_max_loss_runtime_status=$dailyCapStatus"
Write-Host "entry_dedup_runtime_snapshot_collector_activation_request_confirm_text=$confirmText"
Write-Host ("entry_dedup_runtime_snapshot_collector_activation_request_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_runtime_snapshot_collector_activation_request_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "request_ready=$($requestReady.ToString().ToLowerInvariant())"
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
Write-Host "notAuthorization=read-only EntryDedup runtime snapshot collector activation request packet only; does not activate collectors, write runtime evidence, deploy, change production env, relax EntryDedup/DataFreshness/live policy, enable staged-add/live execution, enable scheduler, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-runtime-snapshot-collector-activation-request-packet] read-only check complete"

if ($RequireReady -and -not $requestReady) {
    throw "EntryDedup runtime snapshot collector activation request packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
