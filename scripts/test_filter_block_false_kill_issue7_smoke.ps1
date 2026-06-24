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
        "False-Kill Source Ranking",
        "DataFreshnessGuard RCA",
        "data_freshness_false_kill_rows",
        "data_freshness_stale_class_counts",
        "data_freshness_complete_replayable_candidate_rows",
        "Replayable Candidate Evidence",
        "shouldHavePassedProxy",
        "missingReplayFields",
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
