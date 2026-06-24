Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1"
$handoffPath = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_push_deploy_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "issue7_post_deploy_read_only_bundle_plan",
        "PLAN_READY_NOT_EXECUTED",
        "verify_split_acceptance_ssh.ps1",
        "smoke_filter_block_false_kill_issue7_ssh.ps1",
        "smoke_data_freshness_replay_candidate_id_ssh.ps1",
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1",
        "issue7-runtime-evidence-only-env-current.log",
        "-RuntimeEvidenceLog",
        "prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1",
        "issue7_post_deploy_read_only_bundle_status",
        "read-only issue #7 post-deploy verification bundle only",
        "PlanOnly"
    )) {
    Assert-Contains -Name "Issue #7 post-deploy bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$handoffText = Get-Content -Raw -LiteralPath $handoffPath
foreach ($marker in @(
        "smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1 -RequireBlocked",
        "smoke_filter_block_false_kill_issue7_ssh.ps1",
        "requiredPostDeployReadOnlyVerification"
    )) {
    Assert-Contains -Name "Issue #7 handoff post-deploy bundle marker" -Text $handoffText -Pattern ([regex]::Escape($marker))
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1",
        "issue7_post_deploy_read_only_bundle_status",
        "PLAN_READY_NOT_EXECUTED"
    )) {
    Assert-Contains -Name "Issue #7 post-deploy bundle docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "Issue #7 post-deploy bundle verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_filter_block_false_kill_issue7_post_deploy_read_only_bundle.ps1"

$output = & $scriptPath -PlanOnly -ExpectedCommit "8c3b652" *>&1
$text = $output -join "`n"
Assert-Contains -Name "Issue #7 post-deploy bundle plan output" -Text $text -Pattern "issue7_post_deploy_read_only_bundle_status=PLAN_READY_NOT_EXECUTED"
Assert-Contains -Name "Issue #7 post-deploy bundle close blocked" -Text $text -Pattern "issue7_close_allowed=false"
Assert-Contains -Name "Issue #7 post-deploy bundle live blocked" -Text $text -Pattern "issue7_live_relaxation_allowed=false"
Assert-Contains -Name "Issue #7 post-deploy bundle no deploy" -Text $text -Pattern "deploy_or_env_change_allowed=false"
Assert-Contains -Name "Issue #7 post-deploy bundle source refresh" -Text $text -Pattern "smoke_filter_block_false_kill_issue7_ssh.ps1"
Assert-Contains -Name "Issue #7 post-deploy bundle runtime env" -Text $text -Pattern "smoke_filter_block_false_kill_issue7_runtime_evidence_only_env_ssh.ps1"
Assert-Contains -Name "Issue #7 post-deploy bundle not authorization" -Text $text -Pattern "notAuthorization=read-only issue #7 post-deploy verification bundle plan only"

Write-Host "[filter-block-false-kill-issue7-post-deploy-read-only-bundle-test] OK"
