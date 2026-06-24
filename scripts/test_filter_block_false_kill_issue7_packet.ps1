Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_packet.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "issue7_filter_block_false_kill_packet",
        "issue7_filter_block_false_kill_status",
        "BLOCKED_DATAFRESHNESS_REPLAY_SNAPSHOTS_MISSING",
        "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "BLOCKED_COLLECTOR_TRACE_ONLY_REPLAY_SNAPSHOTS_MISSING",
        "BLOCKED_PREVIEW_ONLY_REPLAY_SNAPSHOTS_NOT_EVALUATED",
        "READY_FOR_REPLAYABLE_CANDIDATE_REVIEW_NOT_LIVE",
        "issue7_live_relaxation_allowed=false",
        "replayInputStage",
        "dataFreshnessPreviewOnlyRows",
        "dataFreshnessGuardOptimizationVerdict",
        "dataFreshnessGraceCounterfactuals",
        "dataFreshnessSevereStaleRows",
        "complete replayable DataFreshness rows",
        "counterfactual replay removing only DataFreshnessGuard",
        "notAuthorization",
        "RequireBlocked",
        "MaxAgeMinutes"
    )) {
    Assert-Contains -Name "Issue #7 packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "Issue #7 docs mention packet" -Text $path -Pattern "prepare_filter_block_false_kill_issue7_packet\.ps1"
    Assert-Contains -Name "Issue #7 docs mention not live" -Text $path -Pattern "issue7_live_relaxation_allowed=false"
}

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("issue7-filter-block-" + [guid]::NewGuid().ToString("N") + ".log")
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
  definition=releaseRows means DataFreshnessGuard would not terminal-block under the candidate stale threshold; hard gates and replay snapshots are still required before live policy review.
  - candidate=current_2x_plus_15 releaseRows=0 falseKillReleased=0 correctBlockReleased=0 avgReleasedForward24h=N/A
  - candidate=grace_3x_plus_15 releaseRows=0 falseKillReleased=0 correctBlockReleased=0 avgReleasedForward24h=N/A
  data_freshness_guard_optimization_verdict=DO_NOT_RELAX_GRACE_FIX_COLLECTOR_OR_SOURCE_OUTAGE
Replayable Candidate Evidence:
  candidate_definition=entry and forward return are replayable proxy fields
Conclusion:
  issue7_recommendation=DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_BUT_REPLAY_SNAPSHOTS_MISSING
  live_relaxation_missing_evidence=["complete replayable DataFreshness rows","liveSignalId or replayCandidateId"]
  issue7_live_relaxation_allowed=false
  notAuthorization=read-only evidence only
"@ | Set-Content -LiteralPath $temp -Encoding UTF8

try {
    $output = & $scriptPath -SourceLog $temp -RequireBlocked *>&1
    $joined = $output -join "`n"
    Assert-Contains -Name "Issue #7 packet sample status" -Text $joined -Pattern "issue7_filter_block_false_kill_status=BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
    Assert-Contains -Name "Issue #7 packet sample live blocked" -Text $joined -Pattern "issue7_live_relaxation_allowed=false"
    Assert-Contains -Name "Issue #7 packet sample missing requirements" -Text $joined -Pattern "complete replayable DataFreshness rows"
} finally {
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
}

Write-Host "[filter-block-false-kill-issue7-packet-test] OK"
