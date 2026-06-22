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
$planPath = Join-Path $repoRoot "docs/exit-side-operator-review-plan.md"
$decisionBriefPath = Join-Path $PSScriptRoot "prepare_exit_side_operator_decision_brief_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$planText = Get-Content -Raw -LiteralPath $planPath
$decisionBriefText = Get-Content -Raw -LiteralPath $decisionBriefPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "read-only operator review contract",
        "not authorization",
        "exit_side_operator_decision_brief_status=READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "exit_side_operator_primary_recommendation=PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW",
        "trailing_stop_acceptance=PASS",
        "strategy485_oco_health_ok=True",
        "strategy485_negative_ev_position_count=3",
        "strategy485_close_or_modify_suggestion_count=3",
        "positionId=148",
        "positionId=149",
        "positionId=150",
        "prepare_exit_side_operator_decision_brief_ssh.ps1 -RequireDecisionReady",
        "prepare_profit_operator_action_brief_ssh.ps1 -RequireReady",
        "exit_side_operator_decision_lanes",
        "exit_side_operator_decision_checklist",
        "exit_side_operator_action_proposals",
        "strategy485_position_summaries",
        "Draft Review Packet",
        "proposalStatus=READY_TO_DRAFT_REVIEW_NOT_LIVE",
        "proposalClass=DRY_RUN_OR_ROLLOUT_REVIEW_NOT_LIVE",
        "proposalStatus=READY_TO_DRAFT_REVIEW_NOT_MUTATION",
        "proposalClass=RISK_REDUCTION_REVIEW_NOT_MUTATION",
        "Proposal Expiration",
        "the latest matrix is older than 180 minutes",
        "Trailing-Stop Rollout",
        "Strategy 485 Risk Reduction",
        "Entry Filter and DataFreshness",
        "Stop Conditions",
        "It must not produce executable commands"
    )) {
    Assert-Contains -Name "exit-side operator review plan" -Text $planText -Pattern $marker
}

foreach ($marker in @(
        "trailing-stop-rollout",
        "strategy485-risk-reduction",
        "entry-filter-datafreshness-policy",
        "decisionChecklist",
        "READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW",
        "notAuthorization=read-only exit-side operator decision brief only"
    )) {
    Assert-Contains -Name "exit-side decision brief supports review plan" -Text $decisionBriefText -Pattern $marker
}

foreach ($doc in @($readmeText, $runbookText, $progressText)) {
    Assert-Contains -Name "operator docs mention exit-side review plan" -Text $doc -Pattern "exit-side-operator-review-plan.md"
}

Write-Host "[exit-side-operator-review-plan-test] OK"
