Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_split_acceptance_deploy_handoff_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION",
        "REQUEST_SEPARATE_DEPLOY_CURRENT_MAIN_AND_READ_ONLY_GRID_VERIFICATION",
        "DEPLOY_HANDOFF_NOT_NEEDED_RERUN_SPLIT_ACCEPTANCE_GRID_WATCH",
        "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_REVIEW_NEEDED_NOT_MUTATION",
        "watch_grid_open_readiness_ssh.ps1",
        "smoke_live_origin_delta_local.ps1",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "child_failure",
        "...[truncated]",
        "timedOut",
        "grid_open_readiness_watch_top_blocker",
        "grid_open_readiness_watch_ranked_blockers",
        "grid_expected_post_deploy_next_blockers",
        "expectedPostDeployNextBlockers",
        "SPLIT_ACCEPTANCE_NOT_PASSING",
        "origin_delta_status",
        "RUNTIME_DRIFT",
        "reviewedGridCandidateParameters",
        "grid_split_acceptance_deploy_handoff_candidate_parameters",
        "candidateLookbackHours",
        "candidateHalfWidthPct",
        "requiredPostDeployReadOnlyVerification",
        "verify_split_acceptance_ssh.ps1",
        "prepare_grid_open_blocker_priority_board_ssh.ps1",
        "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
        "read-only grid split-acceptance deploy handoff packet only",
        "deployAllowed = `$false",
        "gridOpenAllowed = `$false",
        "RequireReady",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "grid split-acceptance deploy handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git push",
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl restart",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "grid split-acceptance deploy handoff must not contain mutation marker: $forbidden"
    }
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"
foreach ($marker in @(
        "prepare_grid_split_acceptance_deploy_handoff_ssh.ps1",
        "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET",
        "grid_split_acceptance_deploy_handoff_status",
        "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION",
        "does not deploy"
    )) {
    Assert-Contains -Name "grid split-acceptance deploy handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "grid split-acceptance deploy handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_grid_split_acceptance_deploy_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("grid-split-deploy-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $watchLog = Join-Path $tempDir "grid-watch.log"
    @"
grid_open_readiness_watch_status=PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE
grid_open_readiness_watch_reason=SPLIT_ACCEPTANCE_NOT_PASSING
grid_open_readiness_watch_openable=false
grid_open_readiness_watch_score_pct=25.00
grid_open_readiness_watch_passed_gates=3/12
grid_open_readiness_watch_top_blocker=SPLIT_ACCEPTANCE_NOT_PASSING
grid_open_readiness_watch_ranked_blockers=[{"rank":1,"family":"deployment/split-acceptance","priority":"P0","blocker":"SPLIT_ACCEPTANCE_NOT_PASSING"},{"rank":2,"family":"production-env","priority":"P0","blocker":"GRID_ENV_DIFF_NOT_APPLIED"},{"rank":3,"family":"event-risk","priority":"P0","blocker":"EVENT_RISK_NOT_R0"}]
grid_open_readiness_watch_gate_checks=[{"gate":"splitAcceptance","pass":false},{"gate":"tradingOkxEnabled","pass":false}]
"@ | Set-Content -LiteralPath $watchLog -Encoding UTF8

    $originLog = Join-Path $tempDir "origin-delta.log"
    @"
server_worktree_commit=1111111111111111111111111111111111111111
origin_main_commit=2222222222222222222222222222222222222222
deployment_metadata_status=RUNTIME_DRIFT
origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN
origin_delta_status=RUNTIME_DRIFT
origin_runtime_delta_files=4
origin_runtime_delta_paths=["src/main/java/com/agora/mcp/GridMcpTools.java"]
"@ | Set-Content -LiteralPath $originLog -Encoding UTF8

    $output = & $scriptPath -GridReadinessWatchLog $watchLog -OriginDeltaLog $originLog *>&1
    $text = $output -join "`n"
    Assert-Contains -Name "grid deploy handoff packet output" -Text $text -Pattern "grid_split_acceptance_deploy_handoff_packet="
    Assert-Contains -Name "grid deploy handoff packet type" -Text $text -Pattern '"packetType":"GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET"'
    Assert-Contains -Name "grid deploy handoff status" -Text $text -Pattern "grid_split_acceptance_deploy_handoff_status="
    Assert-Contains -Name "grid deploy handoff decision" -Text $text -Pattern "grid_split_acceptance_deploy_handoff_decision="
    Assert-Contains -Name "grid deploy handoff candidate params" -Text $text -Pattern "grid_split_acceptance_deploy_handoff_candidate_parameters="
    Assert-Contains -Name "grid deploy handoff blocker" -Text $text -Pattern "grid_open_readiness_watch_top_blocker=SPLIT_ACCEPTANCE_NOT_PASSING"
    Assert-Contains -Name "grid deploy handoff ranked blockers" -Text $text -Pattern "grid_open_readiness_watch_ranked_blockers="
    Assert-Contains -Name "grid deploy handoff expected next blocker" -Text $text -Pattern "grid_expected_post_deploy_next_blockers=.*GRID_ENV_DIFF_NOT_APPLIED"
    Assert-Contains -Name "grid deploy handoff origin drift" -Text $text -Pattern "origin_delta_status=RUNTIME_DRIFT"
    Assert-Contains -Name "grid deploy handoff env blocked" -Text $text -Pattern "production_env_change_allowed=false"
    Assert-Contains -Name "grid deploy handoff deploy blocked" -Text $text -Pattern "deploy_allowed=false"
    Assert-Contains -Name "grid deploy handoff grid blocked" -Text $text -Pattern "grid_open_allowed=false"
    Assert-Contains -Name "grid deploy handoff not authorization" -Text $text -Pattern "notAuthorization=read-only grid split-acceptance deploy handoff packet only"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[grid-split-acceptance-deploy-handoff-test] OK"
