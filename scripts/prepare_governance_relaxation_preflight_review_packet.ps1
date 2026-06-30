param(
    [string]$ReviewLogPath = "target/profit-review/governance-relaxation-review-packet-latest.log",
    [string]$NoBuyAttentionLogPath = "target/profit-review/no-buy-attention-flow-review-packet-latest.log",
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

if ([string]::IsNullOrWhiteSpace($ReviewLogPath)) { throw "ReviewLogPath is required." }
if ([string]::IsNullOrWhiteSpace($NoBuyAttentionLogPath)) { throw "NoBuyAttentionLogPath is required." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for governance relaxation preflight arguments."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$logPath = if ([System.IO.Path]::IsPathRooted($ReviewLogPath)) {
    $ReviewLogPath
} else {
    Join-Path $repoRoot $ReviewLogPath
}
if (-not (Test-Path -LiteralPath $logPath)) {
    throw "Governance relaxation review log not found: $logPath"
}

$logFile = Get-Item -LiteralPath $logPath
$logAgeMinutes = [math]::Round(((Get-Date) - $logFile.LastWriteTime).TotalMinutes, 2)
$text = Get-Content -Raw -LiteralPath $logPath

$noBuyLogPath = if ([System.IO.Path]::IsPathRooted($NoBuyAttentionLogPath)) {
    $NoBuyAttentionLogPath
} else {
    Join-Path $repoRoot $NoBuyAttentionLogPath
}
$noBuyLogExists = Test-Path -LiteralPath $noBuyLogPath
$noBuyLogAgeMinutes = $null
$noBuyStatus = ""
$noBuyJson = ""
$noBuyPacket = $null
if ($noBuyLogExists) {
    $noBuyFile = Get-Item -LiteralPath $noBuyLogPath
    $noBuyLogAgeMinutes = [math]::Round(((Get-Date) - $noBuyFile.LastWriteTime).TotalMinutes, 2)
    $noBuyText = Get-Content -Raw -LiteralPath $noBuyLogPath
    $noBuyStatus = Get-LastPrefixedValue -Text $noBuyText -Prefix "no_buy_attention_flow_review_status="
    $noBuyJson = Get-LastPrefixedValue -Text $noBuyText -Prefix "no_buy_attention_flow_review_packet="
    if (-not [string]::IsNullOrWhiteSpace($noBuyJson)) {
        $noBuyPacket = $noBuyJson | ConvertFrom-Json -ErrorAction Stop
    }
}

$sourceStatus = Get-LastPrefixedValue -Text $text -Prefix "governance_relaxation_review_packet_status="
$sourceJson = Get-LastPrefixedValue -Text $text -Prefix "governance_relaxation_review_packet="
$sourcePacket = $null
if (-not [string]::IsNullOrWhiteSpace($sourceJson)) {
    $sourcePacket = $sourceJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ([string]::IsNullOrWhiteSpace($sourceStatus)) { Add-MissingRequirement -List $missingRequirements -Value "governance_relaxation_review_packet_status present" }
if ([string]::IsNullOrWhiteSpace($sourceJson)) { Add-MissingRequirement -List $missingRequirements -Value "governance_relaxation_review_packet present" }
if ($null -eq $sourcePacket) { Add-MissingRequirement -List $missingRequirements -Value "governance_relaxation_review_packet valid JSON" }

$shadowReviewAllowed = $false
$livePolicyChangeAllowed = $true
$tinyLiveOrderAllowed = $true
$relaxationCandidateCount = 0
$sourceSignalPolicyClear = ""
$sourceGovernanceMode = ""
$sourceMissedOpportunityStatus = ""
$sourceNextAction = ""
$sourceMissingRequirements = @()
if ($null -ne $sourcePacket) {
    $shadowReviewAllowed = [bool]$sourcePacket.shadowGovernanceReviewAllowed
    $livePolicyChangeAllowed = [bool]$sourcePacket.livePolicyChangeAllowed
    $tinyLiveOrderAllowed = [bool]$sourcePacket.tinyLiveOrderAllowed
    $relaxationCandidateCount = [int]$sourcePacket.relaxationCandidateCount
    $sourceSignalPolicyClear = [string]$sourcePacket.signalPolicyClear
    $sourceGovernanceMode = [string]$sourcePacket.governanceMode
    $sourceMissedOpportunityStatus = [string]$sourcePacket.missedOpportunityStatus
    $sourceNextAction = [string]$sourcePacket.nextAction
    $sourceMissingRequirements = @($sourcePacket.missingRequirements)
}

$readyNoBuyAttentionStatuses = @(
    "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE",
    "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE"
)
$noBuyReady = ($readyNoBuyAttentionStatuses -contains $noBuyStatus -and $null -ne $noBuyPacket)
$noBuyNextAction = ""
$noBuyReviewItems = @()
$noBuyBlockers = @()
$noBuyNearThresholdGapCount = $null
$noBuyClosestThresholdGap = $null
$noBuyAttentionCandidateInterpretation = ""
$noBuySignalEvalRecommendation = ""
if ($null -ne $noBuyPacket) {
    $noBuyNextAction = [string]$noBuyPacket.nextAction
    $noBuyReviewItems = @($noBuyPacket.reviewItems)
    $noBuyBlockers = @($noBuyPacket.blockers)
    if ($null -ne $noBuyPacket.signalEvalNoBuyGeneration) {
        $noBuySignalEvalRecommendation = [string]$noBuyPacket.signalEvalNoBuyGeneration.recommendation
        $noBuyNearThresholdGapCount = $noBuyPacket.signalEvalNoBuyGeneration.nearThresholdGapCount
        $noBuyClosestThresholdGap = $noBuyPacket.signalEvalNoBuyGeneration.closestThresholdGap
    }
    if ($null -ne $noBuyPacket.attentionFlow) {
        $noBuyAttentionCandidateInterpretation = [string]$noBuyPacket.attentionFlow.candidateInterpretation
    }
}

if ($sourceStatus -notin @("REVIEW_REQUIRED_NOT_POLICY_CHANGE", "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE")) {
    Add-MissingRequirement -List $missingRequirements -Value "source governance relaxation status is reviewable"
}
if ($livePolicyChangeAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "source live policy change remains disallowed"
}
if ($tinyLiveOrderAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "source tiny-live order remains disallowed"
}
if ($relaxationCandidateCount -lt 1) {
    Add-MissingRequirement -List $missingRequirements -Value "source relaxation candidates present"
}
if ($sourceStatus -eq "REVIEW_REQUIRED_NOT_POLICY_CHANGE" -and $shadowReviewAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "blocked review source does not allow shadow governance review"
}

$ready = $missingRequirements.Count -eq 0
$status = if ($ready) { "READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE" } else { "NOT_READY" }
$hasParsedSourcePacket = ($null -ne $sourcePacket -and -not [string]::IsNullOrWhiteSpace($sourceStatus))
$preflightDecision = if (-not $ready -and $sourceStatus -eq "NO_EVIDENCE" -and $hasParsedSourcePacket) {
    "BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE"
} elseif (-not $ready) {
    "REFRESH_SOURCE_GOVERNANCE_RELAXATION_PACKET"
} elseif ($sourceStatus -eq "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE") {
    "PREPARE_REVIEW_ONLY_GOVERNANCE_SHADOW_REVIEW"
} else {
    "PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW"
}
$nextAction = if ($ready) {
    "Attach this preflight packet to a governance relaxation operator review; require separate explicit authorization before any policy, live, order, deploy, or env change."
} elseif ($sourceStatus -eq "NO_EVIDENCE" -and $hasParsedSourcePacket -and $noBuyReady -and -not [string]::IsNullOrWhiteSpace($noBuyNextAction)) {
    $noBuyNextAction
} elseif ($sourceStatus -eq "NO_EVIDENCE" -and $hasParsedSourcePacket -and -not [string]::IsNullOrWhiteSpace($sourceNextAction)) {
    $sourceNextAction
} elseif ($sourceStatus -eq "NO_EVIDENCE" -and $hasParsedSourcePacket) {
    "Resolve source governance relaxation evidence blockers before using this preflight review packet."
} else {
    "Refresh the governance relaxation review packet before using this preflight review packet."
}

$packet = [pscustomobject]@{
    packetType = "GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    sourceReviewLogPath = $logPath
    sourceReviewLogAgeMinutes = $logAgeMinutes
    noBuyAttentionLogPath = $noBuyLogPath
    noBuyAttentionLogExists = $noBuyLogExists
    noBuyAttentionLogAgeMinutes = $noBuyLogAgeMinutes
    noBuyAttentionStatus = $noBuyStatus
    noBuyAttentionReady = $noBuyReady
    sourceReviewPacket = "prepare_governance_relaxation_review_packet_ssh.ps1"
    sourceReviewPacketStatus = $sourceStatus
    sourceSignalPolicyClear = $sourceSignalPolicyClear
    sourceGovernanceMode = $sourceGovernanceMode
    sourceMissedOpportunityStatus = $sourceMissedOpportunityStatus
    sourceRelaxationCandidateCount = $relaxationCandidateCount
    sourceShadowGovernanceReviewAllowed = $shadowReviewAllowed
    sourceMissingRequirements = @($sourceMissingRequirements)
    sourceNextAction = $sourceNextAction
    noBuyAttentionNextAction = $noBuyNextAction
    noBuyAttentionReviewItems = @($noBuyReviewItems)
    noBuyAttentionBlockers = @($noBuyBlockers)
    noBuySignalEvalRecommendation = $noBuySignalEvalRecommendation
    noBuySignalEvalNearThresholdGapCount = $noBuyNearThresholdGapCount
    noBuyClosestThresholdGap = $noBuyClosestThresholdGap
    noBuyAttentionCandidateInterpretation = $noBuyAttentionCandidateInterpretation
    preflightDecision = $preflightDecision
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        governanceRelaxationReviewAllowed = $true
        shadowGovernanceReviewAllowed = $shadowReviewAllowed
        livePolicyChangeAllowed = $false
        tinyLiveOrderAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        deployOrEnvChangeAllowed = $false
        telegramSendAllowed = $false
        externalBackfillOrImportAllowed = $false
    }
    operatorPreflightChecklist = @(
        "source governance relaxation packet is reviewable",
        "relaxation candidates are present",
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "EntryDedup/DataFreshness/live policy remains unchanged",
        "operator separately approves any future shadow, policy, order, deploy, or env change"
    )
    requiredBeforeAnyFutureMutation = @(
        "fresh production read-only governance relaxation rerun",
        "signalPolicyClear=true and missedOpportunityStatus=PASS before shadow-ready claims",
        "hard-safety/no-buy rows reviewed",
        "ExpectedValueGate, EventRiskControl, duplicate-hash, daily-cap, max-loss, and OCO feasibility evidence",
        "separate explicit operator approval for any policy/live/order/deploy/env change",
        "rollback criteria and post-change read-only verification plan"
    )
    explicitNonAuthorizations = @(
        "does not relax governance policy",
        "does not relax EntryDedup",
        "does not relax DataFreshnessGuard",
        "does not enable live trading",
        "does not enable staged-add or TinyLive execution",
        "does not enable scheduler",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not deploy",
        "does not change production env",
        "does not send Telegram"
    )
    sourceReviewPacketSummary = $sourcePacket
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only governance relaxation preflight review packet only; does not authorize governance/EntryDedup/DataFreshness/live policy relaxation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env change, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[governance-relaxation-preflight-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads existing governance relaxation review log only; no SSH fresh run, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_review_log_path=$logPath"
Write-Host "source_review_log_age_minutes=$logAgeMinutes"
Write-Host "no_buy_attention_log_path=$noBuyLogPath"
Write-Host "no_buy_attention_log_exists=$($noBuyLogExists.ToString().ToLowerInvariant())"
Write-Host "no_buy_attention_log_age_minutes=$noBuyLogAgeMinutes"
Write-Host "no_buy_attention_status=$noBuyStatus"
Write-Host "no_buy_attention_ready=$($noBuyReady.ToString().ToLowerInvariant())"
Write-Host "source_review_packet_status=$sourceStatus"
Write-Host "source_signal_policy_clear=$sourceSignalPolicyClear"
Write-Host "source_governance_mode=$sourceGovernanceMode"
Write-Host "source_missed_opportunity_status=$sourceMissedOpportunityStatus"
Write-Host "source_relaxation_candidate_count=$relaxationCandidateCount"
Write-Host "source_shadow_governance_review_allowed=$($shadowReviewAllowed.ToString().ToLowerInvariant())"
Write-Host ("source_missing_requirements=" + (ConvertTo-Json -Compress @($sourceMissingRequirements)))
Write-Host "source_next_action=$sourceNextAction"
Write-Host "no_buy_attention_next_action=$noBuyNextAction"
Write-Host ("no_buy_attention_review_items=" + (ConvertTo-Json -Compress @($noBuyReviewItems)))
Write-Host ("no_buy_attention_blockers=" + (ConvertTo-Json -Compress @($noBuyBlockers)))
Write-Host "no_buy_signal_eval_recommendation=$noBuySignalEvalRecommendation"
Write-Host "no_buy_signal_eval_near_threshold_gap_count=$noBuyNearThresholdGapCount"
if ($null -ne $noBuyClosestThresholdGap) {
    Write-Host ("no_buy_signal_eval_closest_threshold_gap=" + (ConvertTo-Json -Compress $noBuyClosestThresholdGap))
}
Write-Host "no_buy_attention_candidate_interpretation=$noBuyAttentionCandidateInterpretation"
Write-Host "governance_relaxation_preflight_decision=$preflightDecision"
Write-Host "governance_relaxation_review_allowed=true"
Write-Host "live_policy_change_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("governance_relaxation_preflight_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("governance_relaxation_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "governance_relaxation_preflight_status=$status"
Write-Host "governance_relaxation_preflight_next_action=$nextAction"
Write-Host "notAuthorization=read-only governance relaxation preflight review packet only; does not authorize governance/EntryDedup/DataFreshness/live policy relaxation, live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, deploy, production env changes, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
Write-Host "[governance-relaxation-preflight-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Governance relaxation preflight review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
