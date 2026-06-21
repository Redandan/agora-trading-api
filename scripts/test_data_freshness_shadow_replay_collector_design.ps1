Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if (-not $Text.Contains($Pattern)) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$designPath = Join-Path $repoRoot "docs/data-freshness-shadow-replay-collector-design.md"
$inputPlanPath = Join-Path $repoRoot "docs/data-freshness-shadow-replay-input-plan.md"
$liveSignalEvaluatorPath = Join-Path $repoRoot "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java"
$collectorPath = Join-Path $repoRoot "src/main/java/com/agora/service/backtest/DataFreshnessShadowReplayCollector.java"
$runtimeEvidencePath = Join-Path $repoRoot "src/main/java/com/agora/service/trading/RuntimeDecisionEvidenceService.java"
$envTemplatePath = Join-Path $repoRoot ".env.trading.secrets.example"
$applicationPath = Join-Path $repoRoot "src/main/resources/application.yml"
$localSmokePath = Join-Path $repoRoot "scripts/smoke_local_health.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$designText = Get-Content -Raw -LiteralPath $designPath
$inputPlanText = Get-Content -Raw -LiteralPath $inputPlanPath
$liveSignalEvaluatorText = Get-Content -Raw -LiteralPath $liveSignalEvaluatorPath
$collectorText = Get-Content -Raw -LiteralPath $collectorPath
$runtimeEvidenceText = Get-Content -Raw -LiteralPath $runtimeEvidencePath
$envTemplateText = Get-Content -Raw -LiteralPath $envTemplatePath
$applicationText = Get-Content -Raw -LiteralPath $applicationPath
$localSmokeText = Get-Content -Raw -LiteralPath $localSmokePath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "design contract",
        "authorization to edit production env",
        "DataFreshnessGuard",
        "dataFreshnessContext",
        "candidateTradePlanContext",
        "shadowExecutionIntentContext",
        "auditExpectedValueGateDryRun",
        "RuntimeDecisionEvidenceService.writeFromDecisionAudit",
        "trading.runtime-evidence.enabled:false",
        "freshnessState=BLOCKED_BY_DATA_FRESHNESS_GUARD",
        "evResultJson",
        "status=NOT_EVALUATED",
        "not an executable replay sample",
        "keep the L0 DataFreshnessGuard outcome unchanged",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
        "trading.data-freshness.shadow-replay.collector.enabled=false",
        "replayCandidateId",
        "liveSignalId=null",
        "entry, TP, SL",
        "OCO dry-run/preflight",
        "orderSent=false",
        "intentCreated=false",
        "ocoPlanCreated=false",
        "must not",
        "create ``BtLiveSignal`` just for replay",
        "send Telegram",
        "place or amend exchange orders",
        "create or modify OCO algo orders",
        "run grid, fund, or Earn operations",
        "change EntryDedup",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]",
        "data_freshness_counterfactual_recommendation=REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "at least 30 mature replay candidates",
        "not approval to relax DataFreshnessGuard",
        "Stop Conditions"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay collector design" -Text $designText -Pattern $marker
}

foreach ($marker in @(
        "DataFreshnessShadowReplayCollector",
        '@Value("${trading.data-freshness.shadow-replay.collector.enabled:false}")',
        "SNAPSHOT_ONLY_NOT_REPLAYABLE",
        "MISSING_REPLAY_FIELDS",
        "shadowReplayKeepsHardBlock",
        "shadowReplayCreatesLiveSignal",
        "shadowReplaySendsTelegram",
        "shadowReplayPlacesOrder",
        "shadowReplayCreatesOco",
        "shadowReplayMutatesPolicy"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay collector code boundary" -Text $collectorText -Pattern $marker
}

foreach ($marker in @(
        "Replay Input Contract",
        "stable replay candidate id",
        "do not create a live signal",
        "orderSent=false",
        "missing_counterfactual_fields=[]"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay input plan remains linked" -Text $inputPlanText -Pattern $marker
}

foreach ($marker in @(
        "DATA_STALE_SKIP",
        "DataFreshnessGuard",
        "dataFreshnessContext",
        "dataFreshnessShadowReplayCollector.enrichAfterHardBlock",
        "return;",
        "candidateTradePlanContext",
        "shadowExecutionIntentContext",
        "auditExpectedValueGateDryRun",
        "liveSignalRepository.save",
        "notificationPort.broadcast"
    )) {
    Assert-Contains -Name "LiveSignalEvaluator code inventory anchor" -Text $liveSignalEvaluatorText -Pattern $marker
}

foreach ($marker in @(
        '@Value("${trading.runtime-evidence.enabled:false}")',
        "writeFromDecisionAudit",
        "resolveFreshnessState",
        "BLOCKED_BY_DATA_FRESHNESS_GUARD",
        "resolveEvResultJson",
        "NOT_EVALUATED",
        "resolveExecutionPreviewJson",
        "orderSent"
    )) {
    Assert-Contains -Name "RuntimeDecisionEvidenceService code inventory anchor" -Text $runtimeEvidenceText -Pattern $marker
}

foreach ($marker in @(
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay collector env template default" -Text $envTemplateText -Pattern $marker
}

foreach ($marker in @(
        "data-freshness:",
        "shadow-replay:",
        "collector:",
        "enabled:",
        '${TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED:false}'
    )) {
    Assert-Contains -Name "DataFreshness shadow replay collector application default" -Text $applicationText -Pattern $marker
}

foreach ($marker in @(
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED",
        "trading.data-freshness.shadow-replay.collector.enabled=false"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay collector local smoke default" -Text $localSmokeText -Pattern $marker
}

foreach ($doc in @($readmeText, $runbookText, $progressText)) {
    Assert-Contains -Name "operator docs mention DataFreshness collector design" -Text $doc -Pattern "data-freshness-shadow-replay-collector-design.md"
}

Write-Host "[data-freshness-shadow-replay-collector-design-test] OK"
