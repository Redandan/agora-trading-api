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
$scriptPath = Join-Path $PSScriptRoot "smoke_auto_trading_review_bundle_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($scriptName in @(
        "smoke_live_origin_delta_local.ps1",
        "audit_live_readiness_ssh.ps1",
        "smoke_strategy485_position_risk_ssh.ps1",
        "smoke_strategy574_signal_governance_ssh.ps1",
        "smoke_tiny_live_post_trade_ssh.ps1"
    )) {
    Assert-Contains -Name "auto trading bundle child smoke" -Text $scriptText -Pattern ([regex]::Escape($scriptName))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "invokes existing read-only SSH/local smokes only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed",
        "origin_delta_status",
        "live_authorized_audit_verdict",
        "strategy485_position_risk_recommendation",
        "strategy485_position_review_decision",
        "strategy485_negative_ev_positions",
        "strategy485_close_or_modify_suggestions",
        "strategy485_position_timeout_events",
        "Convert-MarkerJsonOrNull",
        "negativeEvPositionCount",
        "closeOrModifySuggestionCount",
        "positionTimeoutEventCount",
        "strategy574_policy_change_recommendation",
        "tiny_live_post_trade_status",
        "review_items",
        "auto_trading_review_recommendation",
        "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK",
        "CONTINUE_TINYLIVE_MONITORING",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "auto trading bundle marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "auto trading bundle docs mention smoke" -Text $path -Pattern "smoke_auto_trading_review_bundle_ssh\.ps1"
    Assert-Contains -Name "auto trading bundle docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[auto-trading-review-bundle-test] OK"
