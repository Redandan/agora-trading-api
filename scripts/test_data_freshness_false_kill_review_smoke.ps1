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
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_false_kill_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "diagnoseDataFreshnessGuardBlocks",
        "analyzeBlockedSignalOutcomes",
        "getGovernanceDriftDashboard",
        "findGovernanceRelaxationCandidates",
        "getMissedOpportunityRegressionReport",
        "getNoBuyReasonTruthTable"
    )) {
    Assert-Contains -Name "DataFreshness smoke MCP calls" -Text $scriptText -Pattern ([regex]::Escape("call_tool(`"$toolName`""))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed",
        "Current Snapshot",
        "Historical DataFreshness Rows",
        "False-Kill / Governance Evidence",
        "Shadow Replay Plan",
        "data_freshness_shadow_replay_plan",
        "currentDataFreshnessClean",
        "historicalStaleOnly",
        "dataFreshnessFalseKillPct",
        "dataFreshnessAvgRetPct",
        "REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE",
        "FIX_CURRENT_DATA_FRESHNESS_FIRST",
        "NO_DATAFRESHNESS_PROFIT_CANDIDATE",
        "data_freshness_false_kill_recommendation",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "DataFreshness smoke markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        'for name, text in (("short RCA", short_rca), ("review RCA", review_rca), ("long RCA", long_rca))',
        'require(f"{name} read-only boundary"',
        'require(f"{name} explicit acceptance"',
        "blocked-signal read-only boundary",
        "governance drift read-only boundary",
        "governance relaxation read-only boundary",
        "missed opportunity read-only boundary",
        "truth table read-only boundary"
    )) {
    Assert-Contains -Name "DataFreshness smoke hard-fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "DataFreshness docs mention smoke" -Text $path -Pattern "smoke_data_freshness_false_kill_review_ssh\.ps1"
    Assert-Contains -Name "DataFreshness docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[data-freshness-false-kill-review-smoke-test] OK"
