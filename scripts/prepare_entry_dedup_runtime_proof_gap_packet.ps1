param(
    [string]$DirectOperatorLogPath = "target/profit-review/entry-dedup-semantics-direct-operator-packet-fresh.log",
    [string]$GatePreflightLogPath = "target/profit-review/entry-dedup-semantics-gate-preflight-fresh.log",
    [string]$SyntheticPreviewLogPath = "target/profit-review/entry-dedup-synthetic-ev-oco-preview-fresh.log",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

function Read-Log {
    param([string]$Path)
    $resolved = Resolve-RepoPath -Path $Path
    if (-not (Test-Path -LiteralPath $resolved)) {
        return [pscustomobject]@{ Path = $Path; ResolvedPath = $resolved; Text = ""; Freshness = "MISSING"; AgeMinutes = $null }
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    return [pscustomobject]@{
        Path = $Path
        ResolvedPath = $resolved
        Text = Get-Content -Raw -LiteralPath $resolved
        Freshness = if ($age -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        AgeMinutes = $age
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.TrimStart().StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $trimmed = $line.TrimStart()
    return $trimmed.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

$direct = Read-Log -Path $DirectOperatorLogPath
$gate = Read-Log -Path $GatePreflightLogPath
$synthetic = Read-Log -Path $SyntheticPreviewLogPath
$missing = [System.Collections.Generic.List[string]]::new()

foreach ($log in @($direct, $gate, $synthetic)) {
    if ($log.Freshness -eq "MISSING") {
        Add-MissingRequirement -List $missing -Value "missing log: $($log.Path)"
    } elseif ($log.Freshness -eq "STALE") {
        Add-MissingRequirement -List $missing -Value "stale log: $($log.Path)"
    }
}

$directJson = Get-LastPrefixedValue -Text $direct.Text -Prefix "entry_dedup_semantics_direct_operator_packet="
$gateJson = Get-LastPrefixedValue -Text $gate.Text -Prefix "entry_dedup_semantics_gate_preflight_packet="
$syntheticJson = Get-LastPrefixedValue -Text $synthetic.Text -Prefix "entry_dedup_synthetic_ev_oco_preview_packet="

$directPacket = $null
$gatePacket = $null
$syntheticPacket = $null
if (-not [string]::IsNullOrWhiteSpace($directJson)) { $directPacket = $directJson | ConvertFrom-Json -ErrorAction Stop }
if (-not [string]::IsNullOrWhiteSpace($gateJson)) { $gatePacket = $gateJson | ConvertFrom-Json -ErrorAction Stop }
if (-not [string]::IsNullOrWhiteSpace($syntheticJson)) { $syntheticPacket = $syntheticJson | ConvertFrom-Json -ErrorAction Stop }

if ($null -eq $directPacket) { Add-MissingRequirement -List $missing -Value "direct operator packet JSON present" }
if ($null -eq $gatePacket) { Add-MissingRequirement -List $missing -Value "gate preflight packet JSON present" }
if ($null -eq $syntheticPacket) { Add-MissingRequirement -List $missing -Value "synthetic EV/OCO preview packet JSON present" }

$directStatus = if ($null -ne $directPacket) { [string]$directPacket.status } else { "UNKNOWN" }
$gateStatus = if ($null -ne $gatePacket) { [string]$gatePacket.status } else { "UNKNOWN" }
$syntheticStatus = if ($null -ne $syntheticPacket) { [string]$syntheticPacket.status } else { "UNKNOWN" }

if ($directStatus -ne "READY_FOR_ENTRY_DEDUP_SEMANTICS_DIRECT_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missing -Value "direct EntryDedup operator packet ready"
}
if ($syntheticStatus -ne "SYNTHETIC_EV_OCO_PREVIEW_READY_FOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missing -Value "synthetic EV/OCO preview ready"
}
if ($gateStatus -ne "BLOCKED_GATE_EVIDENCE_INCOMPLETE_NOT_LIVE") {
    Add-MissingRequirement -List $missing -Value "gate preflight blocker packet present"
}

$summary = if ($null -ne $directPacket) { $directPacket.sourceEvidenceSummary } else { $null }
$gateStatuses = if ($null -ne $gatePacket) { $gatePacket.gateStatuses } else { $null }
$dbEvidence = if ($null -ne $gatePacket) { $gatePacket.dbEvidence } else { $null }
$candidateGateRows = if ($null -ne $dbEvidence) { $dbEvidence.candidateGateRows } else { $null }
$runtimeEvidenceRows = if ($null -ne $dbEvidence) { $dbEvidence.runtimeEvidenceRows } else { $null }

$blockerRanking = @(
    [pscustomobject]@{
        rank = 1
        blocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.ocoFeasibility } else { "UNKNOWN" }
        evidence = "missing_oco_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.missingOcoRows } else { 'UNKNOWN' })); non_auto_zero_qty_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.nonAutoZeroQtyRows } else { 'UNKNOWN' }))"
        nextEvidence = "exact OCO route proof or lower-timeframe/exchange-side dry-run proof, without modifying OCO"
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 2
        blocker = "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.runtimeEvidenceCoverage } else { "UNKNOWN" }
        evidence = "candidate_runtime_evidence_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvidenceRows } else { 'UNKNOWN' })); candidate_runtime_ev_evaluated_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvEvaluatedRows } else { 'UNKNOWN' })); candidate_runtime_oco_plan_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeOcoPlanRows } else { 'UNKNOWN' }))"
        nextEvidence = "candidate-level runtime EV, entry plan, and OCO plan snapshots for each exact opportunity"
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 3
        blocker = "EXACT_DUPLICATE_REPLAY_PROTECTION_NOT_PROVEN"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.duplicateProtection } else { "UNKNOWN" }
        evidence = "exactOpportunityCount=$($(if ($null -ne $summary) { $summary.exactOpportunityCount } else { 'UNKNOWN' })); exactDuplicateSuppressedRows=$($(if ($null -ne $summary) { $summary.exactDuplicateSuppressedRows } else { 'UNKNOWN' }))"
        nextEvidence = "exact candidate hash and same-candidate replay protection proof"
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 4
        blocker = "DAILY_CAP_MAX_LOSS_CANDIDATE_SNAPSHOT_PARTIAL"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.dailyCapMaxLossBudget } else { "UNKNOWN" }
        evidence = "global_runtime_evidence_rows=$($(if ($null -ne $runtimeEvidenceRows) { $runtimeEvidenceRows.runtime_evidence_rows } else { 'UNKNOWN' })); candidate_cap_or_loss_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.capOrLossRows } else { 'UNKNOWN' }))"
        nextEvidence = "candidate-level daily cap and max-loss budget snapshot"
        mutationBlocker = $true
    }
)

$reviewGapRanking = @(
    [pscustomobject]@{
        rank = 1
        gap = "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.runtimeEvidenceCoverage } else { "UNKNOWN" }
        evidence = "candidate_runtime_evidence_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvidenceRows } else { 'UNKNOWN' })); candidate_runtime_ev_evaluated_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvEvaluatedRows } else { 'UNKNOWN' })); candidate_runtime_oco_plan_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeOcoPlanRows } else { 'UNKNOWN' }))"
        nextReadOnlyAction = "collect candidate-level runtime EV, entry plan, TP/SL plan, and OCO plan snapshots for exact opportunities"
        reviewProgressAllowed = $true
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 2
        gap = "EXACT_DUPLICATE_REPLAY_PROTECTION_NOT_PROVEN"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.duplicateProtection } else { "UNKNOWN" }
        evidence = "exactOpportunityCount=$($(if ($null -ne $summary) { $summary.exactOpportunityCount } else { 'UNKNOWN' })); exactDuplicateSuppressedRows=$($(if ($null -ne $summary) { $summary.exactDuplicateSuppressedRows } else { 'UNKNOWN' }))"
        nextReadOnlyAction = "prove exact candidate hash and same-candidate replay protection before any staged-add execution"
        reviewProgressAllowed = $true
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 3
        gap = "DAILY_CAP_MAX_LOSS_CANDIDATE_SNAPSHOT_PARTIAL"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.dailyCapMaxLossBudget } else { "UNKNOWN" }
        evidence = "global_runtime_evidence_rows=$($(if ($null -ne $runtimeEvidenceRows) { $runtimeEvidenceRows.runtime_evidence_rows } else { 'UNKNOWN' })); candidate_cap_or_loss_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.capOrLossRows } else { 'UNKNOWN' }))"
        nextReadOnlyAction = "attach candidate-level daily cap and max-loss budget snapshot to the exact opportunity packet"
        reviewProgressAllowed = $true
        mutationBlocker = $true
    },
    [pscustomobject]@{
        rank = 4
        gap = "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.ocoFeasibility } else { "UNKNOWN" }
        evidence = "missing_oco_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.missingOcoRows } else { 'UNKNOWN' })); non_auto_zero_qty_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.nonAutoZeroQtyRows } else { 'UNKNOWN' }))"
        nextReadOnlyAction = "keep OCO as a mutation-only blocker until exact route or dry-run proof exists"
        reviewProgressAllowed = $true
        mutationBlocker = $true
    }
)

$mutationBlockerRanking = @(
    [pscustomobject]@{
        rank = 1
        blocker = "OCO_ROUTE_NOT_PROVEN_OR_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.ocoFeasibility } else { "UNKNOWN" }
        evidence = "missing_oco_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.missingOcoRows } else { 'UNKNOWN' })); non_auto_zero_qty_rows=$($(if ($null -ne $dbEvidence) { $dbEvidence.nonAutoZeroQtyRows } else { 'UNKNOWN' }))"
        blocks = @("orders", "staged-add execution", "OCO mutation", "live policy relaxation")
        reviewProgressAllowed = $true
    },
    [pscustomobject]@{
        rank = 2
        blocker = "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.runtimeEvidenceCoverage } else { "UNKNOWN" }
        evidence = "candidate_runtime_evidence_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvidenceRows } else { 'UNKNOWN' })); candidate_runtime_ev_evaluated_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeEvEvaluatedRows } else { 'UNKNOWN' })); candidate_runtime_oco_plan_rows=$($(if ($null -ne $candidateGateRows) { $candidateGateRows.runtimeOcoPlanRows } else { 'UNKNOWN' }))"
        blocks = @("live policy relaxation", "EntryDedup relaxation", "staged-add execution")
        reviewProgressAllowed = $true
    },
    [pscustomobject]@{
        rank = 3
        blocker = "EXACT_DUPLICATE_REPLAY_PROTECTION_NOT_PROVEN"
        status = if ($null -ne $gateStatuses) { [string]$gateStatuses.duplicateProtection } else { "UNKNOWN" }
        evidence = "exactOpportunityCount=$($(if ($null -ne $summary) { $summary.exactOpportunityCount } else { 'UNKNOWN' })); exactDuplicateSuppressedRows=$($(if ($null -ne $summary) { $summary.exactDuplicateSuppressedRows } else { 'UNKNOWN' }))"
        blocks = @("staged-add execution", "EntryDedup relaxation")
        reviewProgressAllowed = $true
    }
)

$readyForGapReview = $missing.Count -eq 0
$status = if ($readyForGapReview) { "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$nextAction = if ($readyForGapReview) {
    "Review the ranked read-only evidence gaps first; treat OCO route as a mutation blocker, not as a blocker to shadow/evidence review."
} else {
    "Refresh direct EntryDedup, gate preflight, and synthetic EV/OCO logs before runtime-proof gap review."
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET"
    status = $status
    symbol = if ($null -ne $directPacket) { $directPacket.symbol } else { "UNKNOWN" }
    strategyId = if ($null -ne $directPacket) { $directPacket.entryDedupStrategyId } else { 0 }
    intervalCode = if ($null -ne $directPacket) { $directPacket.intervalCode } else { "UNKNOWN" }
    sourcePackets = [pscustomobject]@{
        directOperator = $DirectOperatorLogPath
        gatePreflight = $GatePreflightLogPath
        syntheticEvOcoPreview = $SyntheticPreviewLogPath
    }
    sourceStatuses = [pscustomobject]@{
        directOperator = $directStatus
        gatePreflight = $gateStatus
        syntheticEvOcoPreview = $syntheticStatus
    }
    exactOpportunityEvidence = $summary
    syntheticPreview = if ($null -ne $syntheticPacket) { $syntheticPacket } else { $null }
    gateStatuses = $gateStatuses
    blockerRanking = @($blockerRanking)
    reviewGapRanking = @($reviewGapRanking)
    mutationBlockerRanking = @($mutationBlockerRanking)
    blockerSemanticsVersion = "REVIEW_AND_MUTATION_SPLIT_V1"
    topReviewEvidenceGap = $reviewGapRanking[0].gap
    topMutationBlocker = $mutationBlockerRanking[0].blocker
    reviewProgressAllowed = $readyForGapReview
    shadowEvidenceCollectorAllowed = $readyForGapReview
    allowedConclusion = "EntryDedup 508/1h remains a review-only shadow opportunity; candidate runtime snapshots are the top read-only evidence gap, while OCO route proof remains a mutation-only blocker before orders or policy relaxation."
    requiredBeforeAnyMutation = @(
        "exact OCO route proof for each exact opportunity",
        "candidate-level runtime EV snapshot for each exact opportunity",
        "candidate-level entry/TP/SL plan and OCO plan snapshot",
        "exact duplicate hash and same-candidate replay protection",
        "candidate-level daily cap and max-loss budget snapshot",
        "EventRiskControl clear or separately approved for historical non-auto/EventRisk open signal",
        "fresh production rerun immediately before any operator mutation request",
        "separate explicit deploy/env and live-policy authorization"
    )
    forbiddenActions = @(
        "relax EntryDedup",
        "relax DataFreshnessGuard",
        "enable live trading",
        "enable staged-add execution",
        "enable scheduler",
        "place orders",
        "modify or cancel OCO",
        "change production env",
        "deploy",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missing)
    nextAction = $nextAction
    entryDedupPolicyChangeAllowed = $false
    dataFreshnessPolicyChangeAllowed = $false
    livePolicyChangeAllowed = $false
    stagedAddExecutionAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    deployOrEnvChangeAllowed = $false
    notAuthorization = "read-only EntryDedup runtime proof gap packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
}

Write-Host "[entry-dedup-runtime-proof-gap-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved direct EntryDedup, gate preflight, and synthetic EV/OCO logs only; no SSH, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_direct_operator_status=$directStatus"
Write-Host "source_gate_preflight_status=$gateStatus"
Write-Host "source_synthetic_ev_oco_status=$syntheticStatus"
Write-Host "entry_dedup_runtime_proof_gap_missing_requirements=$(ConvertTo-Json -Compress @($missing))"
Write-Host "entry_dedup_runtime_proof_gap_top_blocker=$($blockerRanking[0].blocker)"
Write-Host "entry_dedup_runtime_proof_gap_second_blocker=$($blockerRanking[1].blocker)"
Write-Host "entry_dedup_runtime_proof_gap_top_review_evidence_gap=$($reviewGapRanking[0].gap)"
Write-Host "entry_dedup_runtime_proof_gap_top_mutation_blocker=$($mutationBlockerRanking[0].blocker)"
Write-Host "entry_dedup_runtime_proof_gap_blocker_semantics=REVIEW_AND_MUTATION_SPLIT_V1"
Write-Host "entry_dedup_runtime_proof_gap_review_progress_allowed=$($readyForGapReview.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_runtime_proof_gap_shadow_evidence_collector_allowed=$($readyForGapReview.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_runtime_proof_gap_status=$status"
Write-Host "entry_dedup_runtime_proof_gap_next_action=$nextAction"
Write-Host ("entry_dedup_runtime_proof_gap_packet=" + (ConvertTo-Json -Compress -Depth 14 $packet))
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup runtime proof gap packet only; does not authorize EntryDedup/DataFreshness/live policy relaxation, staged-add/live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, DB/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
Write-Host "[entry-dedup-runtime-proof-gap-packet] read-only check complete"

if ($RequireReady -and -not $readyForGapReview) {
    throw "EntryDedup runtime proof gap packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
