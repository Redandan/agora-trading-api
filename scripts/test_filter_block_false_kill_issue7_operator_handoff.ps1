Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_operator_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "ISSUE7_OPERATOR_HANDOFF_PACKET",
        "READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE",
        "PREPARE_SEPARATE_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW",
        "issue7_operator_handoff_status",
        "issue7_close_allowed",
        "evidence_only_collector_review_allowed",
        "collector_activation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "Issue #7 operator handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_filter_block_false_kill_issue7_operator_handoff.ps1",
        "issue7_operator_handoff_status",
        "ISSUE7_OPERATOR_HANDOFF_PACKET"
    )) {
    Assert-Contains -Name "Issue #7 operator handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "Issue #7 operator handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_filter_block_false_kill_issue7_operator_handoff.ps1"

function New-Issue7SourceLog {
    param([string]$Path)
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
  - blocker=DataFreshnessGuard rows=27 falseKillRows=27 correctBlockRows=0 falseKillPct=+100.00% avgForward24h=+4.90% avgMfe24h=+5.12% avgMae24h=-0.53% replayableCandidateRows=0
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
  - blocker=EventRiskControl rows=2 falseKillRows=1 correctBlockRows=1 falseKillPct=+50.00% avgForward24h=+0.48% avgMfe24h=+0.50% avgMae24h=-0.10% replayableCandidateRows=0
DataFreshnessGuard RCA:
  data_freshness_rows=27
  data_freshness_false_kill_rows=27
  data_freshness_correct_block_rows=0
  data_freshness_false_kill_pct=+100.00%
  data_freshness_avg_forward_24h_pct=+4.90%
  data_freshness_complete_replayable_candidate_rows=0
  data_freshness_preview_only_input_rows=0
  data_freshness_trace_only_rows=0
  replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE
  data_freshness_stale_minutes_min=2252.00
  data_freshness_stale_minutes_avg=2255.11
  data_freshness_stale_minutes_max=2258.00
  data_freshness_threshold_minutes_avg=135.00
  data_freshness_near_miss_rows=0
  data_freshness_recoverable_grace_rows=0
  data_freshness_severe_stale_rows=27
  data_freshness_proxy_actionable_rows=0
  collector_status_counts=N/A:27
  hard_gate_preview_status_counts=N/A:27
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
    param([string]$Path)
    @"
scope=READ_ONLY; invokes existing read-only SSH/local smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.
  data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS
  replay_candidate_id_rows=0
  complete_replayable_candidate_rows=0
  missing_counterfactual_fields=["liveSignalId","replayCandidateId","explicit entry/TP/SL candidate plan","EV snapshot","OCO plan","complete replayable candidate rows"]
  notAuthorization=read-only review evidence only; does not authorize DataFreshnessGuard relaxation
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-ReadinessLog {
    param([string]$Path)
    @"
[data-freshness-replay-evidence-readiness] read-only packet
scope=READ_ONLY; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed.
data_freshness_replay_evidence_readiness_status=PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS
data_freshness_replay_candidate_id_recommendation=PENDING_NO_NEW_DATAFRESHNESS_ROWS
latest_data_freshness_row_time=2026-06-14T15:38:16
latest_data_freshness_row_age_hours=231
data_freshness_rows_1d=0
data_freshness_rows_3d=0
data_freshness_rows_7d=0
data_freshness_sample_gap_status=NO_ROWS_IN_REVIEW_WINDOW
data_freshness_sample_gap_rca_recommendation=NO_RECENT_BUY_STYLE_CANDIDATES
data_freshness_counterfactual_recommendation=COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING
replay_input_stage=PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE
replay_input_next_action=wait_for_new_replay_id_rows_before_shadow_review
collector_status_counts=N/A:74
hard_gate_preview_status_counts=N/A:74
complete_replayable_candidate_rows=0
missing_counterfactual_fields=["liveSignalId","replayCandidateId","explicit entry/TP/SL candidate plan","EV snapshot","OCO plan","complete replayable candidate rows"]
data_freshness_replay_evidence_blockers=["FRESH_DATAFRESHNESS_REPLAY_ROWS_MISSING","PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE","COUNTERFACTUAL_REPLAY_SNAPSHOTS_MISSING"]
data_freshness_replay_evidence_required=["fresh DataFreshnessGuard terminal rows after replay-id runtime","new replay-id rows that postdate replay-id/collector runtime","complete_replayable_candidate_rows > 0","missing_counterfactual_fields=[]"]
"@ | Set-Content -LiteralPath $Path -Encoding UTF8
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("issue7-operator-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $sourceLog = Join-Path $tempDir "source.log"
    $observationLog = Join-Path $tempDir "observation.log"
    $readinessLog = Join-Path $tempDir "readiness.log"
    New-Issue7SourceLog -Path $sourceLog
    New-ObservationLog -Path $observationLog
    New-ReadinessLog -Path $readinessLog

    $output = & $scriptPath -SourceLog $sourceLog -ObservationLog $observationLog -ReadinessLogPath $readinessLog -RequireActionable *>&1
    $text = $output -join "`n"
    Assert-Contains -Name "Issue #7 operator handoff status" -Text $text -Pattern "issue7_operator_handoff_status=READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE"
    Assert-Contains -Name "Issue #7 operator handoff decision" -Text $text -Pattern "issue7_operator_handoff_decision=PREPARE_SEPARATE_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW"
    Assert-Contains -Name "Issue #7 operator handoff close blocked" -Text $text -Pattern "issue7_close_allowed=false"
    Assert-Contains -Name "Issue #7 operator handoff collector review" -Text $text -Pattern "evidence_only_collector_review_allowed=true"
    Assert-Contains -Name "Issue #7 operator handoff no env" -Text $text -Pattern "deploy_or_env_change_allowed=false"
    Assert-Contains -Name "Issue #7 operator handoff packet" -Text $text -Pattern '"packetType":"ISSUE7_OPERATOR_HANDOFF_PACKET"'
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[filter-block-false-kill-issue7-operator-handoff-test] OK"
