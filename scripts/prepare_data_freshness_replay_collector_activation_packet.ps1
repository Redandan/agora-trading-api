param(
    [string]$ReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireDecisionReady
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

if ([string]::IsNullOrWhiteSpace($ReadinessLogPath)) { throw "ReadinessLogPath is required." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for DataFreshness collector activation packet arguments."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$logPath = if ([System.IO.Path]::IsPathRooted($ReadinessLogPath)) {
    $ReadinessLogPath
} else {
    Join-Path $repoRoot $ReadinessLogPath
}
if (-not (Test-Path -LiteralPath $logPath)) {
    throw "DataFreshness replay evidence readiness log not found: $logPath"
}

$logFile = Get-Item -LiteralPath $logPath
$logAgeMinutes = [math]::Round(((Get-Date) - $logFile.LastWriteTime).TotalMinutes, 2)
$text = Get-Content -Raw -LiteralPath $logPath

$readinessStatus = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_replay_evidence_readiness_status="
$replayCandidateRecommendation = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_replay_candidate_id_recommendation="
$latestDataFreshnessRowTime = Get-LastPrefixedValue -Text $text -Prefix "latest_data_freshness_row_time="
$latestDataFreshnessRowAgeHours = Get-LastPrefixedValue -Text $text -Prefix "latest_data_freshness_row_age_hours="
$rows1d = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_rows_1d="
$rows3d = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_rows_3d="
$rows7d = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_rows_7d="
$sampleGapStatus = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_sample_gap_status="
$sampleGapRcaRecommendation = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_sample_gap_rca_recommendation="
$counterfactualRecommendation = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_counterfactual_recommendation="
$replayInputStage = Get-LastPrefixedValue -Text $text -Prefix "replay_input_stage="
$replayInputNextAction = Get-LastPrefixedValue -Text $text -Prefix "replay_input_next_action="
$collectorStatusCounts = Get-LastPrefixedValue -Text $text -Prefix "collector_status_counts="
$hardGatePreviewStatusCounts = Get-LastPrefixedValue -Text $text -Prefix "hard_gate_preview_status_counts="
$completeReplayRowsRaw = Get-LastPrefixedValue -Text $text -Prefix "complete_replayable_candidate_rows="
$missingCounterfactualFields = Get-LastPrefixedValue -Text $text -Prefix "missing_counterfactual_fields="
$blockersRaw = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_replay_evidence_blockers="
$requiredRaw = Get-LastPrefixedValue -Text $text -Prefix "data_freshness_replay_evidence_required="

$completeReplayRows = 0
[void][int]::TryParse($completeReplayRowsRaw, [ref]$completeReplayRows)
$rows1dInt = 0
[void][int]::TryParse($rows1d, [ref]$rows1dInt)
$rows3dInt = 0
[void][int]::TryParse($rows3d, [ref]$rows3dInt)

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ([string]::IsNullOrWhiteSpace($readinessStatus)) { Add-MissingRequirement -List $missingRequirements -Value "readiness status marker present" }
if ([string]::IsNullOrWhiteSpace($replayCandidateRecommendation)) { Add-MissingRequirement -List $missingRequirements -Value "replay candidate recommendation marker present" }
if ([string]::IsNullOrWhiteSpace($replayInputStage)) { Add-MissingRequirement -List $missingRequirements -Value "replay input stage marker present" }
if ($completeReplayRows -gt 0) { Add-MissingRequirement -List $missingRequirements -Value "collector activation decision is only for missing complete replayable rows" }
if ($readinessStatus -notin @("PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS", "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE", "PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS")) {
    Add-MissingRequirement -List $missingRequirements -Value "readiness status is a collector-evidence blocker"
}
if ($replayInputStage -notin @("PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE", "NO_DATAFRESHNESS_SAMPLE", "COLLECTOR_DISABLED_TRACE_ONLY", "PREVIEW_ONLY_NOT_REPLAYABLE")) {
    Add-MissingRequirement -List $missingRequirements -Value "replay input stage is a collector-evidence stage"
}

$decision = "NO_DECISION"
$decisionStatus = "NOT_READY"
$nextAction = "Refresh DataFreshness replay evidence readiness before preparing collector activation review."
if ($missingRequirements.Count -eq 0) {
    if ($replayInputStage -in @("PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE", "NO_DATAFRESHNESS_SAMPLE") -and $rows1dInt -eq 0 -and $rows3dInt -eq 0) {
        $decision = "PREPARE_EVIDENCE_ONLY_COLLECTOR_ACTIVATION_REVIEW"
        $decisionStatus = "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE"
        $nextAction = "Prepare a separate operator review for evidence-only collector activation; do not change production env or deploy without explicit authorization."
    } elseif ($replayInputStage -eq "COLLECTOR_DISABLED_TRACE_ONLY") {
        $decision = "REVIEW_DISABLED_COLLECTOR_TRACE_ONLY"
        $decisionStatus = "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE"
        $nextAction = "Review whether to keep collector disabled or authorize an evidence-only preview rollout; still no live or policy mutation."
    } elseif ($replayInputStage -eq "PREVIEW_ONLY_NOT_REPLAYABLE") {
        $decision = "COLLECT_EVALUATED_GATE_SNAPSHOTS_NEXT"
        $decisionStatus = "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE"
        $nextAction = "Collector preview rows exist; next review should focus on evaluated EV/OCO/hard-gate snapshots, not DataFreshness policy relaxation."
    }
}

$packet = [pscustomobject]@{
    packetType = "DATAFRESHNESS_REPLAY_COLLECTOR_ACTIVATION_DECISION_PACKET"
    status = $decisionStatus
    symbol = $Symbol
    sourceReadinessLogPath = $logPath
    sourceReadinessLogAgeMinutes = $logAgeMinutes
    sourceReadinessStatus = $readinessStatus
    replayCandidateRecommendation = $replayCandidateRecommendation
    latestDataFreshnessRowTime = $latestDataFreshnessRowTime
    latestDataFreshnessRowAgeHours = $latestDataFreshnessRowAgeHours
    dataFreshnessRows1d = $rows1d
    dataFreshnessRows3d = $rows3d
    dataFreshnessRows7d = $rows7d
    dataFreshnessSampleGapStatus = $sampleGapStatus
    sampleGapRcaRecommendation = $sampleGapRcaRecommendation
    counterfactualRecommendation = $counterfactualRecommendation
    replayInputStage = $replayInputStage
    replayInputNextAction = $replayInputNextAction
    collectorStatusCounts = $collectorStatusCounts
    hardGatePreviewStatusCounts = $hardGatePreviewStatusCounts
    completeReplayableCandidateRows = $completeReplayRows
    missingCounterfactualFields = $missingCounterfactualFields
    blockers = $blockersRaw
    requiredEvidence = $requiredRaw
    operatorDecision = $decision
    evidenceOnlyCollectorFlag = "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
    defaultCollectorFlag = "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false"
    requiredSeparateAuthorizations = @(
        "deploy current runtime if runtime evidence is stale",
        "production env change for TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
        "post-change read-only verification only",
        "separate operator approval before any DataFreshness policy, live, order, scheduler, or OCO mutation"
    )
    activationStopConditions = @(
        "runtime ERROR appears after collector activation",
        "collector rows miss replayCandidateId",
        "collector creates live signal, order, OCO, Telegram, exchange, DB mutation outside audit context, or policy change",
        "complete_replayable_candidate_rows remains 0 after fresh DataFreshnessGuard rows are observed",
        "hard-gate preview/evaluated snapshot status regresses or is ambiguous"
    )
    requiredPostActivationEvidence = @(
        "fresh DataFreshnessGuard terminal rows after activation",
        "replayCandidateId present",
        "candidate entry/TP/SL snapshot present",
        "EV/TQS/OCO/hard-gate snapshot or explicit preview-only status present",
        "complete_replayable_candidate_rows > 0 before any shadow candidate review",
        "missing_counterfactual_fields=[] before any DataFreshness policy review"
    )
    forbiddenActions = @(
        "deploy without explicit authorization",
        "change production env without explicit authorization",
        "relax DataFreshnessGuard",
        "enable live trading",
        "enable staged-add or tiny-live execution",
        "enable scheduler mutation",
        "place orders",
        "modify or cancel OCO",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness collector activation decision packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax DataFreshnessGuard, enable scheduler, place orders, modify OCO, send Telegram, mutate DB/grid/fund/Earn/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[data-freshness-replay-collector-activation-packet] read-only packet"
Write-Host "scope=READ_ONLY; reuses existing replay evidence readiness log only; no SSH fresh run, production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."
Write-Host "source_readiness_log_path=$logPath"
Write-Host "source_readiness_log_age_minutes=$logAgeMinutes"
Write-Host "data_freshness_replay_evidence_readiness_status=$readinessStatus"
Write-Host "data_freshness_replay_candidate_id_recommendation=$replayCandidateRecommendation"
Write-Host "latest_data_freshness_row_time=$latestDataFreshnessRowTime"
Write-Host "latest_data_freshness_row_age_hours=$latestDataFreshnessRowAgeHours"
Write-Host "data_freshness_rows_1d=$rows1d"
Write-Host "data_freshness_rows_3d=$rows3d"
Write-Host "data_freshness_sample_gap_status=$sampleGapStatus"
Write-Host "data_freshness_sample_gap_rca_recommendation=$sampleGapRcaRecommendation"
Write-Host "data_freshness_counterfactual_recommendation=$counterfactualRecommendation"
Write-Host "replay_input_stage=$replayInputStage"
Write-Host "replay_input_next_action=$replayInputNextAction"
Write-Host "collector_status_counts=$collectorStatusCounts"
Write-Host "hard_gate_preview_status_counts=$hardGatePreviewStatusCounts"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "missing_counterfactual_fields=$missingCounterfactualFields"
Write-Host "collector_activation_operator_decision=$decision"
Write-Host "evidence_only_collector_flag=TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true"
Write-Host "default_collector_flag=TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false"
Write-Host "collector_activation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "data_freshness_policy_relaxation_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host ("data_freshness_collector_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("data_freshness_collector_activation_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "data_freshness_collector_activation_status=$decisionStatus"
Write-Host "data_freshness_collector_activation_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness collector activation decision packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax DataFreshnessGuard, enable scheduler, place orders, modify OCO, send Telegram, mutate DB/grid/fund/Earn/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[data-freshness-replay-collector-activation-packet] read-only check complete"

if ($RequireDecisionReady -and $decisionStatus -ne "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE") {
    throw "DataFreshness collector activation decision packet is not ready: $decisionStatus; missing=$(@($missingRequirements) -join '; ')"
}
