Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_candidate_runtime_snapshot_collector_review_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-candidate-runtime-snapshot-collector-review-packet] read-only packet",
        "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE",
        "REVIEW_LOCAL_SHADOW_SNAPSHOT_COLLECTOR_IMPLEMENTATION_NOT_LIVE",
        "LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE",
        "entry_dedup_candidate_runtime_snapshot_collector_review_packet",
        "entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status",
        "entry_dedup_candidate_runtime_snapshot_collector_context_keys",
        "ENTRY_DEDUP_508_1H_EXACT_OPPORTUNITY",
        "STRATEGY574_1H_THRESHOLD_NEAR_MISS_PANIC_BOTTOM",
        "candidateSnapshotCollectorStatus",
        "SHADOW_RUNTIME_SNAPSHOT_READY_NOT_LIVE",
        "EVIDENCE_ONLY_NO_ORDER_NO_POLICY_CHANGE",
        "duplicateCandidateHash",
        "replayCandidateId",
        "dailyCapSnapshot",
        "maxLossSnapshot",
        "edsr1_",
        "collectorActivationAllowed = `$false",
        "runtimeEvidenceWriteAllowed = `$false",
        "strategyThresholdChangeAllowed = `$false",
        "entryDedupPolicyChangeAllowed = `$false",
        "dataFreshnessPolicyChangeAllowed = `$false",
        "stagedAddExecutionAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "collector_activation_allowed=false",
        "runtime_evidence_write_allowed=false",
        "order_allowed=false",
        "read-only EntryDedup candidate runtime snapshot collector review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup candidate runtime snapshot collector script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_entry_dedup_candidate_runtime_snapshot_collector_review_packet.ps1",
        "ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET",
        "entry_dedup_candidate_runtime_snapshot_collector_review_status",
        "entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status",
        "candidate runtime snapshot collector review",
        "runtime_evidence_write_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup candidate runtime snapshot collector review packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-runtime-snapshot-collector-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $proofGapPath = Join-Path $tempDir "proof-gap.log"
    $buyLikeLossPath = Join-Path $tempDir "buy-like-loss.log"
    $continuityPath = Join-Path $tempDir "continuity.log"
    $runtimeRcaPath = Join-Path $tempDir "runtime-rca.log"
    $panicPath = Join-Path $tempDir "panic.log"
    $modelPath = Join-Path $tempDir "RuntimeDecisionEvidence.java"
    $servicePath = Join-Path $tempDir "RuntimeDecisionEvidenceService.java"
    $optimizerPath = Join-Path $tempDir "ExposureOptimizer.java"
    $liveEvaluatorPath = Join-Path $tempDir "LiveSignalEvaluator.java"

    $proofPacket = [pscustomobject]@{
        packetType = "ENTRY_DEDUP_RUNTIME_PROOF_GAP_PACKET"
        status = "READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        strategyId = 508
        intervalCode = "1h"
        exactOpportunityEvidence = [pscustomobject]@{
            exactOpportunityCount = 6
            tpHitOpportunities = 6
            slHitOpportunities = 0
            ambiguousOpportunities = 0
            avgNetReturnPct = 0.8
        }
    }
    Set-Content -LiteralPath $proofGapPath -Encoding UTF8 -Value @(
        "entry_dedup_runtime_proof_gap_top_review_evidence_gap=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
        "entry_dedup_runtime_proof_gap_top_mutation_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
        "entry_dedup_runtime_proof_gap_shadow_evidence_collector_allowed=true",
        "entry_dedup_runtime_proof_gap_status=READY_FOR_ENTRY_DEDUP_RUNTIME_PROOF_GAP_REVIEW_NOT_LIVE",
        "entry_dedup_runtime_proof_gap_packet=$((ConvertTo-Json -Compress -Depth 8 $proofPacket))"
    )
    Set-Content -LiteralPath $buyLikeLossPath -Encoding UTF8 -Value @(
        "buy_like_candidate_loss_review_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE",
        "buy_like_candidate_loss_dominant_blocker=ENTRY_SKIP:EntryDedup",
        "buy_like_candidate_loss_30d_entry_dedup_rows=394",
        "issue12_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE"
    )
    Set-Content -LiteralPath $continuityPath -Encoding UTF8 -Value @(
        "buy_like_continuity_matcher_review_status=READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE",
        "matcher_artifact_explained_pct=98.18",
        "residual_potential_true_gap_rows=2",
        "matcher_review_recommendation=EXTEND_PRIMARY_WINDOW_THEN_RECHECK_INTERVAL_LINKING"
    )
    Set-Content -LiteralPath $runtimeRcaPath -Encoding UTF8 -Value @(
        "  diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT",
        "  env.TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "  runtimeEvidenceRows=200",
        "  shadowIntentCount=0",
        "  orderSentEvidence=0",
        "  missing_runtime_evidence_fields=[]"
    )
    Set-Content -LiteralPath $panicPath -Encoding UTF8 -Value @(
        "strategy574_threshold_relaxation_allowed=false",
        "panic_bottom_missed_rebound_primary_root_cause=SIGNAL_THRESHOLD_NEAR_MISS_WITH_OCO_OR_TREND_GUARD_BLOCKER",
        "panic_bottom_missed_rebound_rca_status=READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE"
    )
    Set-Content -LiteralPath $modelPath -Encoding UTF8 -Value @(
        "class RuntimeDecisionEvidence {",
        "  String featuresSnapshotJson;",
        "  String evResultJson;",
        "  String riskGateResultJson;",
        "  String executionPreviewJson;",
        "  Boolean intentCreated;",
        "  Boolean ocoPlanCreated;",
        "  Boolean orderSent;",
        "  String suppressionReason;",
        "}"
    )
    Set-Content -LiteralPath $servicePath -Encoding UTF8 -Value @(
        'copyIfPresent(context, ev, "expected_r");',
        'copyIfPresent(context, ev, "min_expected_r");',
        'copyIfPresent(context, risk, "eventRiskLevel");',
        'copyIfPresent(context, risk, "dailyLossGuard");',
        'firstBoolean(context, "ocoCapable");',
        'firstBoolean(context, "ocoPlanCreated");',
        'firstBooleanOrNull(context, "orderSent", "order_sent");',
        'firstText(context, "suppressionReason", "suppression_reason");',
        'firstBoolean(context, "intentCreated");',
        'firstDecimal(context, "entryPrice", "entry", "signalPrice", "currentPrice");',
        'firstDecimal(context, "tpPrice", "takeProfitPrice", "takeProfit", "tp");',
        'firstDecimal(context, "slPrice", "stopLossPrice", "stopLoss", "sl");',
        'copyIfPresent(context, exposure, "dailyCapSnapshot");',
        'copyIfPresent(context, exposure, "maxLossSnapshot");'
    )
    Set-Content -LiteralPath $optimizerPath -Encoding UTF8 -Value @(
        "candidateSnapshotCollectorStatus",
        "SHADOW_RUNTIME_SNAPSHOT_READY_NOT_LIVE",
        "EVIDENCE_ONLY_NO_ORDER_NO_POLICY_CHANGE",
        "duplicateCandidateHash",
        "replayCandidateId",
        "dailyCapSnapshot",
        "maxLossSnapshot",
        "edsr1_",
        "entryPrice",
        "tpPrice",
        "slPrice",
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
    Set-Content -LiteralPath $liveEvaluatorPath -Encoding UTF8 -Value @(
        "preTradeMinExpectedRForSnapshot",
        "entry, tp, sl, lastBar.getOpenTime(), preTradeMinExpectedRForSnapshot"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for EntryDedup candidate runtime snapshot collector review packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -EntryDedupRuntimeProofGapLogPath $proofGapPath `
            -BuyLikeLossReviewLogPath $buyLikeLossPath `
            -BuyLikeContinuityMatcherLogPath $continuityPath `
            -RuntimeEvidenceRcaLogPath $runtimeRcaPath `
            -PanicBottomRcaLogPath $panicPath `
            -RuntimeEvidenceModelPath $modelPath `
            -RuntimeEvidenceServicePath $servicePath `
            -ExposureOptimizerPath $optimizerPath `
            -LiveSignalEvaluatorPath $liveEvaluatorPath `
            -MaxAgeMinutes 1440 `
            -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "EntryDedup candidate runtime snapshot collector review packet should be ready from fixture logs:`n$text"
    }

    foreach ($marker in @(
            "entry_dedup_candidate_runtime_snapshot_collector_review_status=READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE",
            "entry_dedup_candidate_runtime_snapshot_collector_decision=REVIEW_LOCAL_SHADOW_SNAPSHOT_COLLECTOR_IMPLEMENTATION_NOT_LIVE",
            "entry_dedup_candidate_runtime_snapshot_collector_local_implementation_status=LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE",
            "entry_dedup_candidate_runtime_snapshot_collector_top_review_gap=CANDIDATE_RUNTIME_EV_OCO_SNAPSHOTS_MISSING",
            "entry_dedup_candidate_runtime_snapshot_collector_top_mutation_blocker=OCO_ROUTE_NOT_PROVEN_OR_MISSING",
            "entry_dedup_candidate_runtime_snapshot_collector_dominant_buy_like_blocker=ENTRY_SKIP:EntryDedup",
            "entry_dedup_candidate_runtime_snapshot_collector_30d_entry_dedup_rows=394",
            "entry_dedup_candidate_runtime_snapshot_collector_matcher_artifact_explained_pct=98.18",
            "entry_dedup_candidate_runtime_snapshot_collector_runtime_diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT",
            "entry_dedup_candidate_runtime_snapshot_collector_runtime_rows=200",
            "entry_dedup_candidate_runtime_snapshot_collector_shadow_intent_count=0",
            "entry_dedup_candidate_runtime_snapshot_collector_order_sent_evidence=0",
            "entry_dedup_candidate_runtime_snapshot_collector_panic_root_cause=SIGNAL_THRESHOLD_NEAR_MISS_WITH_OCO_OR_TREND_GUARD_BLOCKER",
            '"packetType":"ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_PACKET"',
            '"status":"READY_FOR_ENTRY_DEDUP_CANDIDATE_RUNTIME_SNAPSHOT_COLLECTOR_REVIEW_NOT_LIVE"',
            '"localImplementationStatus":"LOCAL_IMPLEMENTED_NOT_DEPLOYED_NOT_ACTIVE"',
            '"family":"ENTRY_DEDUP_508_1H_EXACT_OPPORTUNITY"',
            '"family":"STRATEGY574_1H_THRESHOLD_NEAR_MISS_PANIC_BOTTOM"',
            '"entryPrice"',
            '"tpPrice"',
            '"slPrice"',
            '"expected_r"',
            '"orderSent=false"',
            '"collectorActivationAllowed":false',
            '"runtimeEvidenceWriteAllowed":false',
            '"strategyThresholdChangeAllowed":false',
            '"entryDedupPolicyChangeAllowed":false',
            '"orderAllowed":false',
            "collector_activation_allowed=false",
            "runtime_evidence_write_allowed=false",
            "strategy_threshold_change_allowed=false",
            "entry_dedup_policy_change_allowed=false",
            "data_freshness_policy_change_allowed=false",
            "live_policy_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only EntryDedup candidate runtime snapshot collector review packet only"
        )) {
        Assert-Contains -Name "EntryDedup candidate runtime snapshot collector ready output" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|remote command failed|deploy_ssh|gh issue|Could not resolve hostname|Permission denied") {
        throw "EntryDedup candidate runtime snapshot collector packet unexpectedly invoked external work:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-candidate-runtime-snapshot-collector-review-packet-test] OK"
