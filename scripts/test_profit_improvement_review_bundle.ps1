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
$scriptPath = Join-Path $PSScriptRoot "smoke_profit_improvement_review_bundle_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($scriptName in @(
        "smoke_live_origin_delta_local.ps1",
        "smoke_profit_candidate_review_ssh.ps1",
        "smoke_data_freshness_false_kill_review_ssh.ps1",
        "smoke_data_freshness_executability_review_ssh.ps1",
        "smoke_data_freshness_counterfactual_review_ssh.ps1",
        "smoke_strategy485_position_risk_ssh.ps1",
        "smoke_strategy574_signal_governance_ssh.ps1",
        "smoke_tiny_live_post_trade_ssh.ps1"
    )) {
    Assert-Contains -Name "profit improvement bundle child smoke" -Text $scriptText -Pattern ([regex]::Escape($scriptName))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "invokes existing read-only SSH/local smokes only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed",
        "profit_candidate_review_recommendation",
        "data_freshness_false_kill_recommendation",
        "data_freshness_executability_recommendation",
        "data_freshness_counterfactual_recommendation",
        "data_freshness_counterfactual_rows",
        "complete_replayable_candidate_rows",
        "missing_counterfactual_fields",
        "strategy485_position_risk_recommendation",
        "strategy485_position_review_decision",
        "strategy574_policy_change_recommendation",
        "tiny_live_post_trade_status",
        "profit_improvement_review_items",
        "profit_improvement_candidate_scorecard",
        "profit_improvement_review_decision",
        "deploy_required_before_profit_improvement_review",
        "profit_improvement_missing_requirement_count",
        "profit_improvement_missing_requirements",
        "top_profit_improvement_candidate",
        "DataFreshness false-kill counterfactual",
        "Strategy 485 aged negative-EV open positions",
        "Strategy 574 TinyLive near-BUY governance",
        "BLOCKED_WAIT_DEPLOY_AND_REPLAY_EVIDENCE",
        "BLOCKED_WAIT_REPLAY_EVIDENCE",
        "OPERATOR_REVIEW_REQUIRED_READ_ONLY",
        "WAIT_THRESHOLD_CROSS_KEEP_HARD_GATES",
        "dataFreshnessRequiredEvidence",
        "dataFreshnessScorecardRequired",
        '$originDelta -eq "RUNTIME_DRIFT"',
        "deployed runtime current",
        "operator-approved risk-reducing action before any mutation",
        "Convert-MarkerJsonOrNull",
        "AllowFailure",
        "New-BlockedStrategy485SmokeText",
        "FIX_OCO_PROTECTION_FIRST",
        "OCO_HEALTH_READ_ONLY_SMOKE_FAILED",
        "strategy485-position-risk child smoke failed before conclusion",
        "reviewDecision = `$strategy485Decision",
        "negativeEvPositionCount",
        "closeOrModifySuggestionCount",
        "positionTimeoutEventCount",
        "ConvertTo-Json -Compress -Depth 8",
        "candidateScorecard.ToArray()",
        "New-ProfitImprovementReviewDecision",
        "Assert-ProfitImprovementReviewDecisionShape",
        "canDraftShadowExperimentReview",
        "blockedTopCandidate",
        "allowedReviewTypes",
        "rankedEvidenceRefs",
        "strategy485ReviewDecision",
        "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE",
        "profit improvement review decision missing field",
        "profit improvement review decision ranked evidence ref missing field",
        "profit improvement review decision missingRequirementCount mismatch",
        "profit improvement review decision must preserve no-live authorization text",
        "profit_improvement_bundle_recommendation",
        "COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE",
        "COLLECT_DATAFRESHNESS_REPLAYABLE_CANDIDATE_SNAPSHOTS",
        "REVIEW_DATAFRESHNESS_COUNTERFACTUAL_REPLAY_CANDIDATES",
        "COLLECT_EXECUTABILITY_COUNTERFACTUAL_BEFORE_POLICY_CHANGE",
        "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "profit improvement bundle marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "profit improvement bundle docs mention smoke" -Text $path -Pattern "smoke_profit_improvement_review_bundle_ssh\.ps1"
    Assert-Contains -Name "profit improvement bundle docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[profit-improvement-review-bundle-test] OK"
