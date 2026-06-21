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
$scriptPath = Join-Path $PSScriptRoot "smoke_profit_candidate_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "getMonthlyPnlOverview",
        "getStrategyScorecard",
        "getExpectedValueGateStats",
        "getSignalAccuracyReport",
        "analyzeBlockedSignalOutcomes",
        "getMissedOpportunityRegressionReport",
        "getNoBuyReasonTruthTable",
        "getShadowReadinessDashboard",
        "listShadowActivationCandidates",
        "analyzeTrailingStopPnlReplay"
    )) {
    Assert-Contains -Name "profit candidate MCP calls" -Text $scriptText -Pattern ([regex]::Escape("call_tool(`"$toolName`""))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed",
        "PnL / Strategy Baseline",
        "Gate / Signal Evidence",
        "Backtest / Shadow / Exit Evidence",
        "Candidate Summary",
        "profit_candidate_items",
        "profit_candidate_review_recommendation",
        "currentness_note",
        "does not run origin-delta",
        "smoke_profit_improvement_review_bundle_ssh.ps1",
        "prepare_profit_experiment_gate_ssh.ps1",
        "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY",
        "KEEP_EXPECTED_VALUE_GATE",
        "DO_NOT_ENABLE_TRAILING_STOP_OVERLAY",
        "COLLECT_MORE_SHADOW_SAMPLES_BEFORE_ACTIVATION",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "profit candidate markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "signal accuracy read-only boundary",
        "blocked-signal read-only boundary",
        "missed opportunity read-only boundary",
        "truth table read-only boundary",
        "trailing replay read-only boundary"
    )) {
    Assert-Contains -Name "profit candidate hard-fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "profit candidate docs mention smoke" -Text $path -Pattern "smoke_profit_candidate_review_ssh\.ps1"
    Assert-Contains -Name "profit candidate docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[profit-candidate-review-smoke-test] OK"
