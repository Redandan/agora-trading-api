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
$planPath = Join-Path $repoRoot "docs/data-freshness-shadow-replay-input-plan.md"
$counterfactualSmokePath = Join-Path $PSScriptRoot "smoke_data_freshness_counterfactual_review_ssh.ps1"
$executabilitySmokePath = Join-Path $PSScriptRoot "smoke_data_freshness_executability_review_ssh.ps1"
$profitBundlePath = Join-Path $PSScriptRoot "smoke_profit_improvement_review_bundle_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$planText = Get-Content -Raw -LiteralPath $planPath
$counterfactualSmokeText = Get-Content -Raw -LiteralPath $counterfactualSmokePath
$executabilitySmokeText = Get-Content -Raw -LiteralPath $executabilitySmokePath
$profitBundleText = Get-Content -Raw -LiteralPath $profitBundlePath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "review-only plan",
        "not authorization",
        "data_freshness_counterfactual_rows=74",
        "complete_replayable_candidate_rows=0",
        "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING",
        "Replay Input Contract",
        "stable replay candidate id",
        "do not create a live signal",
        "Data freshness snapshot",
        "candidate plan",
        "EV snapshot",
        "TQS snapshot",
        "OCO preflight snapshot",
        "hard-gate snapshot",
        "orderSent=false",
        "no exchange order id",
        "no OCO algo id created",
        "Collector Boundary",
        "It must not change the DataFreshnessGuard decision outcome",
        "TRADING_OKX_ENABLED",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED",
        "smoke_data_freshness_false_kill_review_ssh.ps1",
        "smoke_data_freshness_executability_review_ssh.ps1",
        "smoke_data_freshness_counterfactual_review_ssh.ps1",
        "smoke_profit_improvement_review_bundle_ssh.ps1",
        "currentDataFreshnessClean=true",
        "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "complete_replayable_candidate_rows > 0",
        "ev_snapshot_rows > 0",
        "oco_plan_snapshot_rows > 0",
        "missing_counterfactual_fields=[]",
        "at least 30 mature rows",
        "orderSentEvidence=0",
        "It is not permission to relax DataFreshnessGuard",
        "Stop Conditions"
    )) {
    Assert-Contains -Name "DataFreshness shadow replay input plan" -Text $planText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "data_freshness_counterfactual_recommendation",
        "complete_replayable_candidate_rows",
        "missing_counterfactual_fields",
        "counterfactual_required_evidence",
        "notAuthorization"
    )) {
    Assert-Contains -Name "counterfactual smoke supports plan marker" -Text $counterfactualSmokeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "data_freshness_executability_recommendation",
        "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY",
        "counterfactual_required_evidence",
        "orderSent=false"
    )) {
    Assert-Contains -Name "executability smoke supports plan marker" -Text $executabilitySmokeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE",
        "profit_improvement_bundle_recommendation",
        "notAuthorization"
    )) {
    Assert-Contains -Name "profit improvement bundle supports plan marker" -Text $profitBundleText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($readmeText, $runbookText, $progressText)) {
    Assert-Contains -Name "operator docs mention DataFreshness shadow replay plan" -Text $doc -Pattern "data-freshness-shadow-replay-input-plan\.md"
}

Write-Host "[data-freshness-shadow-replay-input-plan-test] OK"
