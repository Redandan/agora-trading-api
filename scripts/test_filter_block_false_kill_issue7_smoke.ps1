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
$scriptPath = Join-Path $PSScriptRoot "smoke_filter_block_false_kill_issue7_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "issue=7",
        "FILTER_BLOCK",
        "filter_block_total_rows",
        "filter_block_false_kill_rows",
        "filter_block_correct_block_rows",
        "filter_block_avg_forward_24h_pct",
        "Actionable False-Kill Summary",
        "severe_stale_outage_rows_excluded",
        "severe_stale_outage_incidents",
        "actionable_filter_block_false_kill_pct",
        "TP/SL Proxy Actionable Summary",
        "tp_sl_proxy_clean_tp_false_kill_pct",
        "tp_sl_proxy_verdict",
        "issue7_tp_sl_proxy_verdict",
        "False-Kill Source Ranking",
        "Actionable False-Kill Source Ranking",
        "DataFreshnessGuard RCA",
        "data_freshness_false_kill_rows",
        "data_freshness_stale_class_counts",
        "data_freshness_complete_replayable_candidate_rows",
        "data_freshness_preview_only_input_rows",
        "data_freshness_trace_only_rows",
        "replay_input_stage",
        "data_freshness_stale_minutes_min",
        "data_freshness_near_miss_rows",
        "data_freshness_recoverable_grace_rows",
        "data_freshness_severe_stale_rows",
        "DataFreshness Guard Optimization Counterfactual",
        "data_freshness_guard_optimization_verdict",
        "DO_NOT_RELAX_GRACE_FIX_COLLECTOR_OR_SOURCE_OUTAGE",
        "ExpectedValueGate Optimization Counterfactual",
        "expected_value_false_kill_pct",
        "expected_value_gate_optimization_verdict",
        "expected_value_projected_actionable_false_kill_pct_after_review",
        "expected_value_projected_next_blocker_after_review",
        "issue7_expected_value_gate_verdict",
        "collector_status_counts",
        "hard_gate_preview_status_counts",
        "Replayable Candidate Evidence",
        "Actionable Candidate Evidence",
        "issue7_actionable_next_blocker",
        "shouldHavePassedProxy",
        "missingReplayFields",
        "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "COLLECTOR_TRACE_ONLY_NOT_REPLAYABLE",
        "PREVIEW_ONLY_NOT_REPLAYABLE",
        "DATAFRESHNESS_FALSE_KILL_PROXY_HIGH_BUT_REPLAY_SNAPSHOTS_MISSING",
        "safe_guard_optimization_candidates",
        "live_relaxation_missing_evidence",
        "issue7_live_relaxation_allowed=false",
        "notAuthorization",
        "OK read-only check complete",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe",
        "refusing to query unexpected database"
    )) {
    Assert-Contains -Name "Issue #7 filter-block smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "Issue #7 docs mention smoke" -Text $path -Pattern "smoke_filter_block_false_kill_issue7_ssh\.ps1"
    Assert-Contains -Name "Issue #7 docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[filter-block-false-kill-issue7-smoke-test] OK"
