param(
    [string]$EntryDedupRuntimeProofGapLogPath = "target/profit-review/entry-dedup-runtime-proof-gap-current.log",
    [string]$BuyLikeLossReviewLogPath = "target/profit-review/buy-like-candidate-loss-review-latest.log",
    [string]$BuyLikeContinuityMatcherLogPath = "target/profit-review/buy-like-continuity-matcher-review-packet-latest.log",
    [string]$RuntimeEvidenceRcaLogPath = "target/profit-review/runtime-evidence-rca-current.log",
    [string]$PanicBottomRcaLogPath = "target/profit-review/panic-bottom-missed-rebound-rca-current.log",
    [string]$RuntimeEvidenceModelPath = "src/main/java/com/agora/model/RuntimeDecisionEvidence.java",
    [string]$RuntimeEvidenceServicePath = "src/main/java/com/agora/service/trading/RuntimeDecisionEvidenceService.java",
    [string]$ExposureOptimizerPath = "src/main/java/com/agora/service/trading/ExposureOptimizer.java",
    [string]$LiveSignalEvaluatorPath = "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java",
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

function Read-SourceFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name source file not found: $resolved"
    }
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
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

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([string]::IsNullOrWhiteSpace($Value)) { return 0 }
    $match = [regex]::Match($Value, "-?\d+")
    if (-not $match.Success) { return 0 }
    if ([int]::TryParse($match.Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Get-BoolValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    return $Value.Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
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

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @(
        $EntryDedupRuntimeProofGapLogPath,
        $BuyLikeLossReviewLogPath,
        $BuyLikeContinuityMatcherLogPath,
        $RuntimeEvidenceRcaLogPath,
        $PanicBottomRcaLogPath,
        $RuntimeEvidenceModelPath,
        $RuntimeEvidenceServicePath,
        $ExposureOptimizerPath,
        $LiveSignalEvaluatorPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$proofGap = Read-FreshLog -Name "entry-dedup-runtime-proof-gap" -PathValue $EntryDedupRuntimeProofGapLogPath -MaxAge $MaxAgeMinutes
$buyLikeLoss = Read-FreshLog -Name "buy-like-loss-review" -PathValue $BuyLikeLossReviewLogPath -MaxAge $MaxAgeMinutes
$continuity = Read-FreshLog -Name "buy-like-continuity-matcher" -PathValue $BuyLikeContinuityMatcherLogPath -MaxAge $MaxAgeMinutes
$runtimeRca = Read-FreshLog -Name "runtime-evidence-rca" -PathValue $RuntimeEvidenceRcaLogPath -MaxAge $MaxAgeMinutes
$panicRca = Read-FreshLog -Name "panic-bottom-rca" -PathValue $PanicBottomRcaLogPath -MaxAge $MaxAgeMinutes
$model = Read-SourceFile -Name "runtime-evidence-model" -PathValue $RuntimeEvidenceModelPath
$service = Read-SourceFile -Name "runtime-evidence-service" -PathValue $RuntimeEvidenceServicePath
$optimizer = Read-SourceFile -Name "exposure-optimizer" -PathValue $ExposureOptimizerPath
$liveEvaluator = Read-SourceFile -Name "live-signal-evaluator" -PathValue $LiveSignalEvaluatorPath

$missing = [System.Collections.Generic.List[string]]::new()
foreach ($log in @($proofGap, $buyLikeLoss, $continuity, $runtimeRca, $panicRca)) {
    if (-not $log.Fresh) {
        Add-Missing -List $missing -Value "$($log.Name) log fresh within $MaxAgeMinutes minutes"
    }
}

$proofGapStatus = Get-LastPrefixedValue -Text $proofGap.Text -Prefix "entry_dedup_runtime_proof_gap_status=" -Default "UNKNOWN"
$topReviewGap = Get-LastPrefixedValue -Text $proofGap.Text -Prefix "entry_dedup_runtime_proof_gap_top_review_evidence_gap=" -Default "UNKNOWN"
$topMutationBlocker = Get-LastPrefixedValue -Text $proofGap.Text -Prefix "entry_dedup_runtime_proof_gap_top_mutation_blocker=" -Default "UNKNOWN"
$shadowCollectorAllowed = Get-BoolValue (Get-LastPrefixedValue -Text $proofGap.Text -Prefix "entry_dedup_runtime_proof_gap_shadow_evidence_collector_allowed=" -Default "false")
$proofGapPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $proofGap.Text -Prefix "entry_dedup_runtime_proof_gap_packet=")

$symbol = if ($null -ne $proofGapPacket) { [string]$proofGapPacket.symbol } else { "BTCUSDT" }
$strategyId = if ($null -ne $proofGapPacket) { [int]$proofGapPacket.strategyId } else { 508 }
$intervalCode = if ($null -ne $proofGapPacket) { [string]$proofGapPacket.intervalCode } else { "1h" }
$exactOpportunityCount = if ($null -ne $proofGapPacket -and $null -ne $proofGapPacket.exactOpportunityEvidence) {
    Get-IntValue ([string]$proofGapPacket.exactOpportunityEvidence.exactOpportunityCount)
} else { 0 }
$tpHitOpportunities = if ($null -ne $proofGapPacket -and $null -ne $proofGapPacket.exactOpportunityEvidence) {
    Get-IntValue ([string]$proofGapPacket.exactOpportunityEvidence.tpHitOpportunities)
} else { 0 }
$slHitOpportunities = if ($null -ne $proofGapPacket -and $null -ne $proofGapPacket.exactOpportunityEvidence) {
    Get-IntValue ([string]$proofGapPacket.exactOpportunityEvidence.slHitOpportunities)
} else { 0 }
$ambiguousOpportunities = if ($null -ne $proofGapPacket -and $null -ne $proofGapPacket.exactOpportunityEvidence) {
    Get-IntValue ([string]$proofGapPacket.exactOpportunityEvidence.ambiguousOpportunities)
} else { 0 }
$avgNetReturnPct = if ($null -ne $proofGapPacket -and $null -ne $proofGapPacket.exactOpportunityEvidence) {
    [string]$proofGapPacket.exactOpportunityEvidence.avgNetReturnPct
} else { "UNKNOWN" }

$buyLikeStatus = Get-LastPrefixedValue -Text $buyLikeLoss.Text -Prefix "buy_like_candidate_loss_review_status=" -Default "UNKNOWN"
$dominantBlocker = Get-LastPrefixedValue -Text $buyLikeLoss.Text -Prefix "buy_like_candidate_loss_dominant_blocker=" -Default "UNKNOWN"
$entryDedupRows30d = Get-IntValue (Get-LastPrefixedValue -Text $buyLikeLoss.Text -Prefix "buy_like_candidate_loss_30d_entry_dedup_rows=" -Default "0")
$issue12Status = Get-LastPrefixedValue -Text $buyLikeLoss.Text -Prefix "issue12_status=" -Default "UNKNOWN"

$continuityStatus = Get-LastPrefixedValue -Text $continuity.Text -Prefix "buy_like_continuity_matcher_review_status=" -Default "UNKNOWN"
$matcherExplainedPct = Get-LastPrefixedValue -Text $continuity.Text -Prefix "matcher_artifact_explained_pct=" -Default "UNKNOWN"
$residualTrueGapRows = Get-IntValue (Get-LastPrefixedValue -Text $continuity.Text -Prefix "residual_potential_true_gap_rows=" -Default "0")
$matcherRecommendation = Get-LastPrefixedValue -Text $continuity.Text -Prefix "matcher_review_recommendation=" -Default "UNKNOWN"

$runtimeDiagnosis = Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "diagnosis=" -Default "UNKNOWN"
$runtimeEnabled = Get-BoolValue (Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "env.TRADING_RUNTIME_EVIDENCE_ENABLED=" -Default "false")
$runtimeEvidenceRows = Get-IntValue (Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "runtimeEvidenceRows=" -Default "0")
$shadowIntentCount = Get-IntValue (Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "shadowIntentCount=" -Default "0")
$orderSentEvidence = Get-IntValue (Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "orderSentEvidence=" -Default "0")
$missingRuntimeFields = Get-LastPrefixedValue -Text $runtimeRca.Text -Prefix "missing_runtime_evidence_fields=" -Default "UNKNOWN"

$panicStatus = Get-LastPrefixedValue -Text $panicRca.Text -Prefix "panic_bottom_missed_rebound_rca_status=" -Default "UNKNOWN"
$panicRootCause = Get-LastPrefixedValue -Text $panicRca.Text -Prefix "panic_bottom_missed_rebound_primary_root_cause=" -Default "UNKNOWN"
$strategyThresholdRelaxationAllowed = Get-BoolValue (Get-LastPrefixedValue -Text $panicRca.Text -Prefix "strategy574_threshold_relaxation_allowed=" -Default "false")

if ($proofGapStatus -ne "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "EntryDedup runtime proof gap packet ready"
}
if ($topReviewGap -ne "CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING") {
    Add-Missing -List $missing -Value "candidate runtime EV/OCO snapshot is top review gap"
}
if (-not $shadowCollectorAllowed) {
    Add-Missing -List $missing -Value "shadow evidence collector review allowed by proof-gap packet"
}
if ($buyLikeStatus -ne "READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "BUY-like candidate loss review ready"
}
if ($continuityStatus -ne "READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "BUY-like continuity matcher review ready"
}
if (-not $runtimeEnabled) {
    Add-Missing -List $missing -Value "TRADING_RUNTIME_EVIDENCE_ENABLED true in runtime RCA"
}
if ($runtimeEvidenceRows -lt 1) {
    Add-Missing -List $missing -Value "canonical runtime evidence rows present"
}
if ($orderSentEvidence -ne 0) {
    Add-Missing -List $missing -Value "orderSentEvidence remains zero for collector review"
}
if ($panicStatus -ne "READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "panic-bottom missed rebound RCA ready"
}
if ($strategyThresholdRelaxationAllowed) {
    Add-Missing -List $missing -Value "strategy574 threshold relaxation remains blocked"
}

$modelRequiredMarkers = @(
    "featuresSnapshotJson",
    "evResultJson",
    "riskGateResultJson",
    "executionPreviewJson",
    "intentCreated",
    "ocoPlanCreated",
    "orderSent",
    "suppressionReason"
)
$serviceRequiredMarkers = @(
    'copyIfPresent(context, ev, "expected_r")',
    'copyIfPresent(context, ev, "min_expected_r")',
    'copyIfPresent(context, risk, "eventRiskLevel")',
    'copyIfPresent(context, risk, "dailyLossGuard")',
    'firstBoolean(context, "ocoCapable")',
    'firstBoolean(context, "ocoPlanCreated")',
    'firstBooleanOrNull(context, "orderSent", "order_sent")',
    'firstText(context, "suppressionReason", "suppression_reason")',
    'firstBoolean(context, "intentCreated")',
    'firstDecimal(context, "entryPrice", "entry", "signalPrice", "currentPrice")',
    'firstDecimal(context, "tpPrice", "takeProfitPrice", "takeProfit", "tp")',
    'firstDecimal(context, "slPrice", "stopLossPrice", "stopLoss", "sl")',
    'copyIfPresent(context, exposure, "dailyCapSnapshot")',
    'copyIfPresent(context, exposure, "maxLossSnapshot")'
)
$optimizerRequiredMarkers = @(
    "candidateSnapshotCollectorStatus",
    "SHADOW_RUNTIME_SNAPSHOT_READY_NOT_LIVE",
    "EVIDENCE_ONLY_NO_ORDER_NO_POLICY_CHANGE",
    "duplicateCandidateHash",
    "replayCandidateId",
    "edsr1_",
    "entryPrice",
    "tpPrice",
    "slPrice",
    "dailyCapSnapshot",
    "maxLossSnapshot",
    "candidateContinuedToEv",
    "candidateContinuedToTqs",
    "runtimeEvidencePolicyMode",
    "runtimeEvidencePolicyReason",
    "orderAllowed",
    "gridMutationAllowed",
    "schedulerEnablementAllowed",
    "telegramSendAllowed",
    "livePolicyRelaxationAllowed"
)
$liveEvaluatorRequiredMarkers = @(
    "preTradeMinExpectedRForSnapshot",
    "entry, tp, sl, lastBar.getOpenTime(), preTradeMinExpectedRForSnapshot"
)
$sourceContractMissing = [System.Collections.Generic.List[string]]::new()
foreach ($marker in $modelRequiredMarkers) {
    if ($model.Text -notmatch [regex]::Escape($marker)) {
        Add-Missing -List $sourceContractMissing -Value "model marker missing: $marker"
    }
}
foreach ($marker in $serviceRequiredMarkers) {
    if ($service.Text -notmatch [regex]::Escape($marker)) {
        Add-Missing -List $sourceContractMissing -Value "service marker missing: $marker"
    }
}
foreach ($marker in $optimizerRequiredMarkers) {
    if ($optimizer.Text -notmatch [regex]::Escape($marker)) {
        Add-Missing -List $sourceContractMissing -Value "optimizer marker missing: $marker"
    }
}
foreach ($marker in $liveEvaluatorRequiredMarkers) {
    if ($liveEvaluator.Text -notmatch [regex]::Escape($marker)) {
        Add-Missing -List $sourceContractMissing -Value "live evaluator marker missing: $marker"
    }
}
foreach ($item in $sourceContractMissing) {
    Add-Missing -List $missing -Value $item
}

$candidateFamilies = @(
    [pscustomobject]@{
        family = "ENTRY_DEDUP_508_1H_EXACT_OPPORTUNITY"
        status = "REVIEW_COLLECT_CANDIDATE_RUNTIME_SNAPSHOTS_NOT_LIVE"
        evidence = @(
            "exactOpportunityCount=$exactOpportunityCount",
            "tpHitOpportunities=$tpHitOpportunities",
            "slHitOpportunities=$slHitOpportunities",
            "ambiguousOpportunities=$ambiguousOpportunities",
            "avgNetReturnPct=$avgNetReturnPct",
            "buyLike30dEntryDedupRows=$entryDedupRows30d"
        )
    },
    [pscustomobject]@{
        family = "STRATEGY574_1H_THRESHOLD_NEAR_MISS_PANIC_BOTTOM"
        status = "REVIEW_ONLY_THRESHOLD_GAP_SNAPSHOT_NOT_RELAXATION"
        evidence = @(
            "panicRootCause=$panicRootCause",
            "strategyThresholdRelaxationAllowed=false",
            "runtimeDiagnosis=$runtimeDiagnosis"
        )
    }
)

$collectorContextKeys = @(
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
    "intentCreated",
    "orderSent=false",
    "suppressionReason=SHADOW_MODE",
    "runtimeEvidencePolicyMode=BLOCK",
    "runtimeEvidencePolicyReason",
    "duplicateCandidateHash",
    "dailyCapSnapshot",
    "maxLossSnapshot",
    "candidateAuditId",
    "replayCandidateId"
)

$ready = $missing.Count -eq 0
$implementationReady = $sourceContractMissing.Count -eq 0
$implementationStatus = if ($implementationReady) {
    "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"
} else {
    "LOCAL_IMPLEMENTATION_CONTRACT_MISSING"
}
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"
} else {
    "NOT_READY"
}
$decision = if ($ready) {
    if ($implementationReady) {
        "REVIEW_LOCAL_SHADOW_SNAPSHOT_COLLECTOR_IMPLEMENTATION_NOT_LIVE"
    } else {
        "PREPARE_REVIEW_ONLY_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_DESIGN"
    }
} else {
    "REFRESH_SOURCE_EVIDENCE_BEFORE_COLLECTOR_REVIEW"
}

$packet = [pscustomobject]@{
    packetType = "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"
    status = $status
    decision = $decision
    symbol = $symbol
    strategyId = $strategyId
    intervalCode = $intervalCode
    sourceLogs = [pscustomobject]@{
        entryDedupRuntimeProofGap = $EntryDedupRuntimeProofGapLogPath
        buyLikeLossReview = $BuyLikeLossReviewLogPath
        buyLikeContinuityMatcher = $BuyLikeContinuityMatcherLogPath
        runtimeEvidenceRca = $RuntimeEvidenceRcaLogPath
        panicBottomRca = $PanicBottomRcaLogPath
    }
    sourceLogFreshness = @($proofGap, $buyLikeLoss, $continuity, $runtimeRca, $panicRca | ForEach-Object {
            [pscustomobject]@{ name = $_.Name; ageMinutes = $_.AgeMinutes; fresh = $_.Fresh }
        })
    sourceContract = [pscustomobject]@{
        runtimeEvidenceModel = $RuntimeEvidenceModelPath
        runtimeEvidenceService = $RuntimeEvidenceServicePath
        exposureOptimizer = $ExposureOptimizerPath
        liveSignalEvaluator = $LiveSignalEvaluatorPath
        modelRequiredMarkers = @($modelRequiredMarkers)
        serviceRequiredMarkers = @($serviceRequiredMarkers)
        optimizerRequiredMarkers = @($optimizerRequiredMarkers)
        liveEvaluatorRequiredMarkers = @($liveEvaluatorRequiredMarkers)
        missing = @($sourceContractMissing)
    }
    localImplementationStatus = $implementationStatus
    blockerEvidence = [pscustomobject]@{
        proofGapStatus = $proofGapStatus
        topReviewGap = $topReviewGap
        topMutationBlocker = $topMutationBlocker
        shadowEvidenceCollectorAllowed = $shadowCollectorAllowed
        buyLikeStatus = $buyLikeStatus
        dominantBlocker = $dominantBlocker
        issue12Status = $issue12Status
        continuityStatus = $continuityStatus
        matcherExplainedPct = $matcherExplainedPct
        residualPotentialTrueGapRows = $residualTrueGapRows
        matcherRecommendation = $matcherRecommendation
        runtimeDiagnosis = $runtimeDiagnosis
        runtimeEvidenceRows = $runtimeEvidenceRows
        shadowIntentCount = $shadowIntentCount
        orderSentEvidence = $orderSentEvidence
        missingRuntimeEvidenceFields = $missingRuntimeFields
        panicRootCause = $panicRootCause
        strategy574ThresholdRelaxationAllowed = $strategyThresholdRelaxationAllowed
    }
    candidateFamilies = @($candidateFamilies)
    proposedCollectorContextKeys = @($collectorContextKeys)
    requiredBeforeCollectorActivation = @(
        "separate operator authorization for implementation/deploy if runtime evidence writes are added",
        "collector must write only RuntimeDecisionEvidence-compatible shadow evidence rows or enrich DecisionAudit context for later runtime evidence extraction",
        "orderSent=false for every collector row",
        "suppressionReason=SHADOW_MODE or dry-run equivalent for every collector row",
        "no exchange order id, no OCO mutation, no Telegram send, no scheduler enablement",
        "candidate-level entry/TP/SL, EV, OCO plan-shape, daily cap, max-loss, duplicate hash, and event-risk snapshots",
        "post-change read-only verification with runtime RCA, EntryDedup runtime proof gap packet, and live blocker audit"
    )
    reviewEnvelope = [pscustomobject]@{
        reviewOnly = $true
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        livePolicyChangeAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        strategyThresholdChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        gridMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = if ($ready -and $implementationReady) {
        "Use this packet to review the local shadow snapshot context implementation; do not deploy, activate runtime evidence writes, relax policy, or execute trades from this packet."
    } elseif ($ready) {
        "Use this packet to review the candidate runtime snapshot collector design; do not activate writes, deploy, relax policy, or execute trades from this packet."
    } else {
        "Refresh or repair the listed read-only evidence before reviewing a candidate runtime snapshot collector."
    }
    notAuthorization = "read-only EntryDedup candidate runtime snapshot collector review packet only; does not authorize collector activation, runtime evidence writes, live trading, strategy threshold changes, EntryDedup/DataFreshness/live policy relaxation, staged-add execution, orders, OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, production env changes, scheduler enablement, or external backfill/import"
}

Write-Host "[entry-dedup-candidate-runtime-snapshot-collector-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved local evidence logs and source files only; no SSH, GitHub, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_review_status=$status"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_decision=$decision"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status=$implementationStatus"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_top_review_gap=$topReviewGap"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_top_mutation_blocker=$topMutationBlocker"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_dominant_buy_like_blocker=$dominantBlocker"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_30d_entry_dedup_rows=$entryDedupRows30d"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_matcher_artifact_explained_pct=$matcherExplainedPct"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_runtime_diagnosis=$runtimeDiagnosis"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_runtime_rows=$runtimeEvidenceRows"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_shadow_intent_count=$shadowIntentCount"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_order_sent_evidence=$orderSentEvidence"
Write-Host "entry_dedup_candidate_runtime_snapshot_collector_panic_root_cause=$panicRootCause"
Write-Host ("entry_dedup_candidate_runtime_snapshot_collector_context_keys=" + (ConvertTo-Json -Compress @($collectorContextKeys)))
Write-Host ("entry_dedup_candidate_runtime_snapshot_collector_candidate_families=" + (ConvertTo-Json -Compress -Depth 8 @($candidateFamilies)))
Write-Host ("entry_dedup_candidate_runtime_snapshot_collector_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_candidate_runtime_snapshot_collector_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "strategy_threshold_change_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[entry-dedup-candidate-runtime-snapshot-collector-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup candidate runtime snapshot collector review packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
