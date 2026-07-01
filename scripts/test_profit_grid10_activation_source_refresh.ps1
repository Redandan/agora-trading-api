Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_grid10_activation_source_refresh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PLAN",
        "PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PACKET",
        "READY_REFRESHED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION",
        "BLOCKED_REFRESHED_PROFIT_GRID10_ACTIVATION_REQUIREMENTS_MISSING",
        "INCOMPLETE_PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS",
        "prepare_profit_aggressive_activation_operator_packet.ps1",
        "prepare_profit_grid10_order_path_handoff.ps1",
        "prepare_profit_grid10_activation_authorization_bundle.ps1",
        "prepare_profit_grid10_same_session_activation_review_packet.ps1",
        "grid-open-authorization-bundle-microgrid-current.log",
        "grid-open-blocker-priority-board-latest.log",
        "profit-grid10-order-path-handoff-latest.log",
        "profit-grid10-activation-authorization-bundle-latest.log",
        "profit-grid10-same-session-activation-review-latest.log",
        "PlanOnly",
        "ContinueOnStepFailure",
        "AllowDirtyLocalWorktreeForReplay",
        "Set-Content -LiteralPath `$outputPath",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "livePolicyChangeAllowed = `$false",
        "orderAllowed = `$false",
        "createGridAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "read-only grid10 activation source refresh only"
    )) {
    Assert-Contains -Name "grid10 activation source refresh script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}
if ($scriptText -match "tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "grid10 activation source refresh must not invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_grid10_activation_source_refresh.ps1",
        "PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PACKET",
        "profit_grid10_activation_source_refresh_status",
        "profit-grid10-order-path-handoff-latest.log",
        "profit-grid10-activation-authorization-bundle-latest.log",
        "profit-grid10-same-session-activation-review-latest.log",
        "order_allowed=false",
        "deploy_allowed=false",
        "create_grid_allowed=false"
    )) {
    Assert-Contains -Name "grid10 activation source refresh docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs grid10 activation source refresh test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_grid10_activation_source_refresh.ps1"

$reviewTestDir = Join-Path $repoRoot "target/profit-review-grid10-test"
New-Item -ItemType Directory -Force -Path $reviewTestDir | Out-Null
$planOutput = & $scriptPath -PlanOnly -ReviewOutputDir "target/profit-review-grid10-test" *>&1
$planText = $planOutput -join "`n"
foreach ($marker in @(
        "[profit-grid10-activation-source-refresh] read-only refresh orchestration",
        "profit_grid10_activation_source_refresh_step_count=4",
        "profit_grid10_activation_source_refresh_local_step_count=4",
        "profit_grid10_activation_source_refresh_grid_authorization_bundle_log=target/profit-review/grid-open-authorization-bundle-microgrid-current.log",
        "profit_grid10_activation_source_refresh_aggressive_log=target\profit-review-grid10-test\profit-aggressive-activation-operator-packet-latest.log",
        "profit_grid10_activation_source_refresh_handoff_log=target\profit-review-grid10-test\profit-grid10-order-path-handoff-latest.log",
        "profit_grid10_activation_source_refresh_activation_log=target\profit-review-grid10-test\profit-grid10-activation-authorization-bundle-latest.log",
        "profit_grid10_activation_source_refresh_same_session_log=target\profit-review-grid10-test\profit-grid10-same-session-activation-review-latest.log",
        "profit_grid10_activation_source_refresh_plan=",
        '"packetType":"PROFIT_GRID10_ACTIVATION_SOURCE_REFRESH_PLAN"',
        "profit_grid10_activation_source_refresh_status=PLAN_ONLY_NOT_EXECUTED",
        "notAuthorization=read-only grid10 activation source refresh only"
    )) {
    Assert-Contains -Name "grid10 activation source refresh plan replay" -Text $planText -Pattern ([regex]::Escape($marker))
}
if ($planText -match "step_start|Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
    throw "PlanOnly should not execute grid10 refresh steps:`n$planText"
}

Write-Host "[profit-grid10-activation-source-refresh-test] OK"
