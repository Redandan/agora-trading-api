Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "issue7-runtime-evidence-only-env",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED",
        "TRADING_OKX_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED",
        "EVENT_SCAN_NOTIFICATION_ENABLED",
        "EXECUTION_EVENT_ENABLED",
        "TRADING_OCO_POLLER_ENABLED",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
        "TRADING_FUNDING_ARB_ENABLED",
        "OKX_EARN_TOPUP_ENABLED",
        "recent_data_stale_skip_count",
        "read-only issue #7 runtime evidence-only env smoke only"
    )) {
    Assert-Contains -Name "Issue #7 runtime evidence-only env smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$bundleText = Get-Content -Raw -LiteralPath $bundlePath
foreach ($marker in @(
        "runtime-evidence-only-env",
        "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1",
        "issue7-runtime-evidence-only-env-current.log",
        "-RuntimeEvidenceLog"
    )) {
    Assert-Contains -Name "Issue #7 post-deploy bundle runtime env marker" -Text $bundleText -Pattern ([regex]::Escape($marker))
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1",
        "issue7-runtime-evidence-only-env-current.log",
        "RuntimeEvidenceLog"
    )) {
    Assert-Contains -Name "Issue #7 runtime evidence-only env docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "Issue #7 runtime evidence-only env verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_filter_block_false_kill_issue7_runtime_evidence_only_env_smoke.ps1"

Write-Host "[filter-block-false-kill-issue7-runtime-evidence-only-env-smoke-test] OK"
