Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_push_deploy_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED",
        "REQUEST_SEPARATE_PUSH_DEPLOY_AUTHORIZATION",
        "NO_LOCAL_PUSH_REQUIRED_DEPLOY_RECHECK_NEEDED",
        "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE",
        "requiredPostDeployReadOnlyVerification",
        "smoke_filter_block_false_kill_issue7_post_deploy_read_only_bundle_ssh.ps1 -RequireBlocked",
        "verify_split_acceptance_ssh.ps1",
        "smoke_filter_block_false_kill_issue7_ssh.ps1",
        "smoke_data_freshness_replay_candidate_id_ssh.ps1",
        "prepare_filter_block_false_kill_issue7_collector_post_activation_status.ps1",
        "read-only issue #7 push/deploy handoff packet only",
        "status --short) -join",
        "RequireReady"
    )) {
    Assert-Contains -Name "Issue #7 push/deploy handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_filter_block_false_kill_issue7_push_deploy_handoff.ps1",
        "ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET",
        "issue7_push_deploy_handoff_status",
        "READY_FOR_PUSH_DEPLOY_AUTHORIZATION_NOT_DEPLOYED"
    )) {
    Assert-Contains -Name "Issue #7 push/deploy handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "Issue #7 push/deploy handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_filter_block_false_kill_issue7_push_deploy_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("issue7-push-deploy-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $postActivationLog = Join-Path $tempDir "post-activation.log"
    @"
issue7_collector_post_activation_status=BLOCKED_DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE
issue7_remaining_blocker=DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE
issue7_close_allowed=false
issue7_live_relaxation_allowed=false
deploy_or_env_change_allowed=false
"@ | Set-Content -LiteralPath $postActivationLog -Encoding UTF8

    $output = & $scriptPath -PostActivationLog $postActivationLog *>&1
    $text = $output -join "`n"
    Assert-Contains -Name "Issue #7 push/deploy packet output" -Text $text -Pattern "issue7_push_deploy_handoff_packet="
    Assert-Contains -Name "Issue #7 push/deploy packet type" -Text $text -Pattern '"packetType":"ISSUE7_PUSH_DEPLOY_HANDOFF_PACKET"'
    Assert-Contains -Name "Issue #7 push/deploy status output" -Text $text -Pattern "issue7_push_deploy_handoff_status="
    Assert-Contains -Name "Issue #7 push/deploy blocker" -Text $text -Pattern "issue7_remaining_blocker=DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_EVIDENCE"
    Assert-Contains -Name "Issue #7 push/deploy close blocked" -Text $text -Pattern "issue7_close_allowed=false"
    Assert-Contains -Name "Issue #7 push/deploy live blocked" -Text $text -Pattern "issue7_live_relaxation_allowed=false"
    Assert-Contains -Name "Issue #7 push/deploy env blocked" -Text $text -Pattern "deploy_or_env_change_allowed=false"
    Assert-Contains -Name "Issue #7 push/deploy not authorization" -Text $text -Pattern "notAuthorization=read-only issue #7 push/deploy handoff packet only"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[filter-block-false-kill-issue7-push-deploy-handoff-test] OK"
