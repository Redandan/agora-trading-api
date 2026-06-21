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
$scriptPath = Join-Path $PSScriptRoot "smoke_strategy574_signal_governance_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "getGovernanceDriftDashboard",
        "findGovernanceRelaxationCandidates",
        "diagnoseDataFreshnessGuardBlocks",
        "getMissedOpportunityRegressionReport",
        "getNoBuyReasonTruthTable",
        "getTinyLiveAutoExecutionTriggerStatus",
        "getAutonomousReadinessDashboard"
    )) {
    Assert-Contains -Name "strategy574 smoke MCP calls" -Text $scriptText -Pattern ([regex]::Escape("call_tool(`"$toolName`""))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed",
        "for days in (1, 3, 7, 14)",
        "window={days}d governanceMode",
        "Strategy 574 No-Buy Rows",
        "strategy574RowCount",
        "WATCH_SIGNAL_NEAR_BUY_THRESHOLD",
        "WAIT_BUY_THRESHOLD_CROSS",
        "DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
        "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS",
        "strategy574_near_buy",
        "governance_too_strict_7d_or_14d",
        "short_window_insufficient_data",
        "data_freshness_current_clean",
        "policy_change_recommendation",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "strategy574 smoke markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "governance drift read-only boundary",
        "governance relaxation read-only boundary",
        "DataFreshnessGuard read-only boundary",
        "missed opportunity no order marker",
        "no-buy truth table no order marker",
        "TinyLive trigger no order marker",
        "autonomous readiness no order marker"
    )) {
    Assert-Contains -Name "strategy574 smoke hard-fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "strategy574 docs mention smoke" -Text $path -Pattern "smoke_strategy574_signal_governance_ssh\.ps1"
    Assert-Contains -Name "strategy574 docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[strategy574-signal-governance-smoke-test] OK"
