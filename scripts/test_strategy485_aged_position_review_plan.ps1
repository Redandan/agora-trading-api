Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if (-not $Text.Contains($Pattern)) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$planPath = Join-Path $repoRoot "docs/strategy485-aged-position-review-plan.md"
$smokePath = Join-Path $PSScriptRoot "smoke_strategy485_position_risk_ssh.ps1"
$profitBundlePath = Join-Path $PSScriptRoot "smoke_profit_improvement_review_bundle_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$planText = Get-Content -Raw -LiteralPath $planPath
$smokeText = Get-Content -Raw -LiteralPath $smokePath
$profitBundleText = Get-Content -Raw -LiteralPath $profitBundlePath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "read-only review packet contract",
        "not authorization",
        "strategy485_position_risk_recommendation=REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
        "open strategy 485 BTCUSDT positions exist",
        "OCO health is currently OK",
        "active-position EV can be negative",
        "position timeout or aging events",
        "TP stretch may be ``WATCH``",
        "smoke_strategy485_position_risk_ssh.ps1",
        "smoke_profit_improvement_review_bundle_ssh.ps1",
        "smoke_tiny_live_post_trade_ssh.ps1",
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "positionIds=[...]",
        "ocoHealthOk=true",
        "negativeEvPositions",
        "closeOrModifySuggestions",
        "positionTimeoutEvents",
        "tpStretchWatchCount",
        "tpStretchStretchedCount",
        "monthlyPnl",
        "notAuthorization",
        "FIX_OCO_PROTECTION_FIRST",
        "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
        "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED",
        "WATCH_TP_STRETCH",
        "NO_POSITION_RISK_ACTION",
        "requires a separate exact diff",
        "Stop Conditions",
        "ocoHealthOk=false",
        "origin_delta_status=RUNTIME_DRIFT"
    )) {
    Assert-Contains -Name "strategy485 aged position review plan" -Text $planText -Pattern $marker
}

foreach ($marker in @(
        "strategy485_position_risk_recommendation",
        "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
        "FIX_OCO_PROTECTION_FIRST",
        "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED",
        "WATCH_TP_STRETCH",
        "NO_POSITION_RISK_ACTION",
        "notAuthorization=read-only evidence only"
    )) {
    Assert-Contains -Name "strategy485 smoke supports review plan" -Text $smokeText -Pattern $marker
}

foreach ($marker in @(
        "strategy485_position_risk_recommendation",
        "REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS",
        "notAuthorization"
    )) {
    Assert-Contains -Name "profit bundle supports strategy485 review routing" -Text $profitBundleText -Pattern $marker
}

foreach ($doc in @($readmeText, $runbookText, $progressText)) {
    Assert-Contains -Name "operator docs mention strategy485 plan" -Text $doc -Pattern "strategy485-aged-position-review-plan.md"
}

Write-Host "[strategy485-aged-position-review-plan-test] OK"
