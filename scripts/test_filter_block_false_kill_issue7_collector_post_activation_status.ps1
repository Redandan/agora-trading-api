Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "ISSUE7_COLLECTOR_POST_ACTIVATION_STATUS_PACKET",
        "BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS",
        "NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS",
        "issue7_collector_post_activation_status",
        "collector_runtime_state",
        "fresh_post_collector_data_freshness_rows_observed",
        "read-only issue #7 collector post-activation status packet only"
    )) {
    Assert-Contains -Name "Issue #7 collector post-activation script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1",
        "ISSUE7_COLLECTOR_POST_ACTIVATION_STATUS_PACKET",
        "issue7_remaining_blocker"
    )) {
    Assert-Contains -Name "Issue #7 collector post-activation docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}

function New-Issue7SourceLog {
    param([string]$Path, [bool]$Replayable)
    $replayRows = if ($Replayable) { 2 } else { 0 }
    $stage = if ($Replayable) { "REPLAYABLE_CANDIDATE_REVIEW_SAMPLE" } else { "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE" }
    $collector = if ($Replayable) { "CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE:2" } else { "N/A:27" }
    $hardGate = if ($Replayable) { "NOT_EVALUATED_REPLAY_INPUT_ONLY:2" } else { "N/A:27" }
    @"
[issue7-filter-block-false-kill] read-only production DB evidence check
scope=READ_ONLY; direct MySQL SELECTs only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.
Filter Block False-Kill Summary:
  filter_block_total_rows=35
  filter_block_matured_rows=35
  filter_block_false_kill_rows=32
  filter_block_correct_block_rows=3
  filter_block_false_kill_pct=+91.43%
  filter_block_avg_forward_24h_pct=+4.02%
False-Kill Source Ranking:
  - blocker=DataFreshnessGuard rows=27 falseKillRows=27 correctBlockRows=0 falseKillPct=+100.00% avgForward24h=+4.90% avgMfe24h=+5.12% avgMae24h=-0.53% replayableCandidateRows=$replayRows
Actionable False-Kill Summary:
  raw_filter_block_false_kill_pct=+91.43%
  severe_stale_outage_rows_excluded=27
  severe_stale_outage_incidents=1
  actionable_filter_block_matured_rows=8
  actionable_filter_block_false_kill_rows=5
  actionable_filter_block_correct_block_rows=3
  actionable_filter_block_false_kill_pct=+62.50%
  actionable_filter_block_avg_forward_24h_pct=+1.06%
TP/SL Proxy Actionable Summary:
  tp_sl_proxy_evaluable_rows=6
  tp_sl_proxy_tp_hit_rows=6
  tp_sl_proxy_sl_hit_rows=0
  tp_sl_proxy_clean_tp_rows=6
  tp_sl_proxy_clean_sl_rows=0
  tp_sl_proxy_ambiguous_rows=0
  tp_sl_proxy_clean_tp_false_kill_pct=+100.00%
  tp_sl_proxy_verdict=REVIEW_CLEAN_TP_FALSE_KILLS
Actionable False-Kill Source Ranking:
  - blocker=ExpectedValueGate rows=6 falseKillRows=4 correctBlockRows=2 falseKillPct=+66.67% avgForward24h=+1.25% avgMfe24h=+1.80% avgMae24h=-1.02% replayableCandidateRows=6
DataFreshnessGuard RCA:
  data_freshness_rows=27
  data_freshness_false_kill_rows=27
  data_freshness_correct_block_rows=0
  data_freshness_false_kill_pct=+100.00%
  data_freshness_avg_forward_24h_pct=+4.90%
  data_freshness_complete_replayable_candidate_rows=$replayRows
  data_freshness_preview_only_input_rows=0
  data_freshness_trace_only_rows=0
  replay_input_stage=$stage
  data_freshness_stale_minutes_min=2252.00
  data_freshness_stale_minutes_avg=2255.11
  data_freshness_stale_minutes_max=2258.00
  data_freshness_threshold_minutes_avg=135.00
  data_freshness_near_miss_rows=0
  data_freshness_recoverable_grace_rows=0
  data_freshness_severe_stale_rows=27
  data_freshness_proxy_actionable_rows=0
  collector_status_counts=$collector
  hard_gate_preview_status_counts=$hardGate
  replay_required_next_action_counts=N/A:27
DataFreshness Guard Optimization Counterfactual:
  - candidate=current_2x_plus_15 releaseRows=0 falseKillReleased=0 correctBlockReleased=0 avgReleasedForward24h=N/A
  data_freshness_guard_optimization_verdict=DO_NOT_RELAX_GRACE_FIX_COLLECTOR_OR_SOURCE_OUTAGE
ExpectedValueGate Optimization Counterfactual:
  expected_value_rows=6
  expected_value_false_kill_rows=4
  expected_value_correct_block_rows=2
  expected_value_false_kill_pct=+66.67%
  expected_value_avg_forward_24h_pct=+1.25%
  expected_value_expected_r_min=0.04
  expected_value_expected_r_avg=0.04
  expected_value_expected_r_max=0.04
  expected_value_min_expected_r_avg=0.20
  - candidate=minExpectedR_0.00 releaseRows=6 falseKillReleased=4 correctBlockReleased=2 tpSlCleanTpReleased=6 tpSlNonCleanReleased=0 avgReleasedForward24h=+1.25%
  expected_value_gate_optimization_verdict=REVIEW_MIN_EXPECTED_R_0.00_TP_SL_SHADOW_ONLY
  expected_value_projected_actionable_rows_after_review=2
  expected_value_projected_actionable_false_kill_rows_after_review=1
  expected_value_projected_actionable_correct_block_rows_after_review=1
  expected_value_projected_actionable_false_kill_pct_after_review=+50.00%
  expected_value_projected_next_blocker_after_review=EventRiskControl
Replayable Candidate Evidence:
  candidate_definition=entry and forward return are replayable proxy fields
Actionable Candidate Evidence:
  - auditId=70001 time=2026-06-14T01:00:00 strategy=574 blocker=ExpectedValueGate entry=65000 closeAfter24h=66000 forward24h=+1.54% shouldHavePassedProxy=true replayableCandidate=true missingReplayFields=[] expectedR=0.04 minExpectedR=0.20 evReason=expectedR<minExpectedR candidateEntry=65000 candidateTp=71500 candidateSl=61750 tpHit=true slHit=false staleClass=CONTEXT_MISSING_REVIEW blockReason=expectedR below threshold
Conclusion:
  issue7_recommendation=DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_PRE_REPLAY_COLLECTOR
  issue7_actionable_next_blocker=ExpectedValueGate
  issue7_expected_value_gate_verdict=REVIEW_MIN_EXPECTED_R_0.00_TP_SL_SHADOW_ONLY
  issue7_tp_sl_proxy_verdict=REVIEW_CLEAN_TP_FALSE_KILLS
  live_relaxation_missing_evidence=["complete replayable DataFreshness rows","liveSignalId or replayCandidateId"]
  issue7_live_relaxation_allowed=false
  notAuthorization=read-only evidence only
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-ObservationLog {
    param([string]$Path, [bool]$Replayable)
    $recommendation = if ($Replayable) { "REPLAY_CANDIDATE_ID_EVIDENCE_OK" } else { "PENDING_NO_NEW_DATAFRESHNESS_ROWS" }
    $rows = if ($Replayable) { 2 } else { 0 }
    $missing = if ($Replayable) { "[]" } else { '["liveSignalId","replayCandidateId","explicit entry/TP/SL candidate plan","EV snapshot","OCO plan","complete replayable candidate rows"]' }
    @"
scope=READ_ONLY; invokes existing read-only SSH/local smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.
  data_freshness_replay_candidate_id_recommendation=$recommendation
  replay_candidate_id_rows=$rows
  complete_replayable_candidate_rows=$rows
  missing_counterfactual_fields=$missing
  notAuthorization=read-only review evidence only; does not authorize DataFreshnessGuard relaxation
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-ReadinessLog {
    param([string]$Path, [bool]$Replayable, [bool]$RuntimeCurrent = $true)
    $status = if (-not $RuntimeCurrent) { "BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE" } elseif ($Replayable) { "READY_FOR_DATAFRESHNESS_SHADOW_REVIEW_NOT_LIVE" } else { "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS" }
    $recommendation = if (-not $RuntimeCurrent) { "DEPLOYED_RUNTIME_NOT_CURRENT" } elseif ($Replayable) { "REPLAY_CANDIDATE_ID_EVIDENCE_OK" } else { "PENDING_NO_NEW_DATAFRESHNESS_ROWS" }
    $rows = if ($Replayable) { 2 } else { 0 }
    $rowCounts = if ($Replayable) { "2" } else { "0" }
    $missing = if ($Replayable) { "[]" } else { '["liveSignalId","replayCandidateId","explicit entry/TP/SL candidate plan","EV snapshot","OCO plan","complete replayable candidate rows"]' }
    @"
[data-freshness-replay-evidence-readiness] read-only packet
scope=READ_ONLY; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.
data_freshness_replay_evidence_readiness_status=$status
data_freshness_replay_candidate_id_recommendation=$recommendation
replay_candidate_id_rows=$rows
latest_data_freshness_row_time=2026-06-24T08:00:00
data_freshness_rows_1d=$rowCounts
data_freshness_rows_3d=$rowCounts
data_freshness_rows_7d=$rowCounts
complete_replayable_candidate_rows=$rows
missing_counterfactual_fields=$missing
notAuthorization=read-only DataFreshness replay evidence readiness only
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-RuntimeLog {
    param([string]$Path, [int]$StaleSkipCount)
    @"
commit=07ae667e137750635787032c94b70e6350cd64e3
port=8084
TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true
TRADING_OKX_ENABLED=false
TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false
EVENT_SCAN_NOTIFICATION_ENABLED=false
EXECUTION_EVENT_ENABLED=false
TRADING_OCO_POLLER_ENABLED=false
MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false
TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false
TRADING_FUNDING_ARB_ENABLED=false
OKX_EARN_TOPUP_ENABLED=false
recent_data_stale_skip_count=$StaleSkipCount
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("issue7-post-activation-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $blockedSource = Join-Path $tempDir "blocked-source.log"
    $blockedObservation = Join-Path $tempDir "blocked-observation.log"
    $blockedReadiness = Join-Path $tempDir "blocked-readiness.log"
    $runtime = Join-Path $tempDir "runtime.log"
    New-Issue7SourceLog -Path $blockedSource -Replayable $false
    New-ObservationLog -Path $blockedObservation -Replayable $false
    New-ReadinessLog -Path $blockedReadiness -Replayable $false
    New-RuntimeLog -Path $runtime -StaleSkipCount 0
    $blocked = & $scriptPath -SourceLog $blockedSource -ObservationLog $blockedObservation -ReadinessLogPath $blockedReadiness -RuntimeEvidenceLog $runtime -RequireBlocked *>&1
    $blockedText = $blocked -join "`n"
    Assert-Contains -Name "Issue #7 post-activation blocked status" -Text $blockedText -Pattern "issue7_collector_post_activation_status=BLOCKED_WAITING_FOR_FRESH_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "Issue #7 post-activation blocker" -Text $blockedText -Pattern "issue7_remaining_blocker=NO_FRESH_POST_COLLECTOR_DATAFRESHNESS_ROWS"
    Assert-Contains -Name "Issue #7 post-activation env state" -Text $blockedText -Pattern "collector_runtime_state=EVIDENCE_ONLY_COLLECTOR_ACTIVE"
    Assert-Contains -Name "Issue #7 post-activation no close" -Text $blockedText -Pattern "issue7_close_allowed=false"

    $staleRuntimeReadiness = Join-Path $tempDir "stale-runtime-readiness.log"
    New-ReadinessLog -Path $staleRuntimeReadiness -Replayable $false -RuntimeCurrent $false
    $staleRuntime = & $scriptPath -SourceLog $blockedSource -ObservationLog $blockedObservation -ReadinessLogPath $staleRuntimeReadiness -RuntimeEvidenceLog $runtime -RequireBlocked *>&1
    $staleRuntimeText = $staleRuntime -join "`n"
    Assert-Contains -Name "Issue #7 post-activation deploy-current status" -Text $staleRuntimeText -Pattern "issue7_collector_post_activation_status=BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
    Assert-Contains -Name "Issue #7 post-activation deploy-current blocker" -Text $staleRuntimeText -Pattern "issue7_remaining_blocker=DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
    Assert-Contains -Name "Issue #7 post-activation deployment current marker" -Text $staleRuntimeText -Pattern "deployment_runtime_current_for_replay_id=false"

    $readySource = Join-Path $tempDir "ready-source.log"
    $readyObservation = Join-Path $tempDir "ready-observation.log"
    $readyReadiness = Join-Path $tempDir "ready-readiness.log"
    New-Issue7SourceLog -Path $readySource -Replayable $true
    New-ObservationLog -Path $readyObservation -Replayable $true
    New-ReadinessLog -Path $readyReadiness -Replayable $true
    $ready = & $scriptPath -SourceLog $readySource -ObservationLog $readyObservation -ReadinessLogPath $readyReadiness -RuntimeEvidenceLog $runtime *>&1
    $readyText = $ready -join "`n"
    Assert-Contains -Name "Issue #7 post-activation ready status" -Text $readyText -Pattern "issue7_collector_post_activation_status=READY_TO_CLOSE_NOT_LIVE_RELAXATION"
    Assert-Contains -Name "Issue #7 post-activation ready close" -Text $readyText -Pattern "issue7_close_allowed=true"
    Assert-Contains -Name "Issue #7 post-activation ready still not live" -Text $readyText -Pattern "issue7_live_relaxation_allowed=false"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[filter-block-false-kill-issue7-collector-post-activation-status-test] OK"
