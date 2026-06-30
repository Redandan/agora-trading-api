Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
        "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED",
        "REQUEST_EXACT_TRAILING_DRY_RUN_ENV_DEPLOY_AUTHORIZATION",
        "RUN_ACTIVE_DRY_RUN_READ_ONLY_VERIFICATION",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "TRAILING_STOP_DRY_RUN=false",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "TRADING_OCO_POLLER_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "exactOperatorAuthorizationText",
        "exactDeployCommand",
        "deploy_ssh.ps1 -Branch main",
        "requiredPostDeployReadOnlyVerification",
        "verify_split_acceptance_ssh.ps1",
        "smoke_trailing_stop_pnl_replay_ssh.ps1",
        "audit_live_readiness_ssh.ps1",
        "prepare_profit_next_execution_blocker_packet.ps1",
        "rollbackPlan",
        "set TRAILING_STOP_ENABLED=false",
        "keep TRAILING_STOP_DRY_RUN=true",
        "AllowDirtyLocalWorktreeForReplay",
        "read-only trailing-stop dry-run env/deploy handoff packet only",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "orderAllowed = `$false",
        "telegramSendAllowed = `$false",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe"
    )) {
    Assert-Contains -Name "trailing dry-run env deploy handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "bash deploy.sh",
        "git reset --hard",
        "systemctl restart",
        "systemctl reload",
        "nginx -s reload",
        "setTrailingStopOptIn(",
        "modifyOco(",
        "forceClosePosition"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing dry-run env deploy handoff must not contain mutation marker: $forbidden"
    }
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_trailing_stop_dry_run_env_deploy_handoff_ssh.ps1",
        "TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "exact operator authorization"
    )) {
    Assert-Contains -Name "trailing dry-run env deploy handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "trailing dry-run env deploy handoff verify marker" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_trailing_stop_dry_run_env_deploy_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-env-deploy-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $readyLog = Join-Path $tempDir "post-opt-in-ready.log"
    $readyPacket = [ordered]@{
        packetType = "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET"
        status = "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION"
        symbol = "BTCUSDT"
        sourceActivationDecision = "REQUEST_OPERATOR_AUTHORIZATION_FOR_DRY_RUN_ENV_DIFF"
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = "false"
        currentGlobalDryRun = "true"
        currentOpenOcoPositions = "0"
        expectedOptInStrategyId = 574
        expectedStrategyOptIn = $true
        envDiffReviewReady = $true
        alreadyActiveDryRun = $false
        missingRequirements = @()
    }
    @"
trailing_stop_post_opt_in_readiness_packet=$($readyPacket | ConvertTo-Json -Compress -Depth 8)
trailing_stop_post_opt_in_readiness_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION
trailing_stop_post_opt_in_readiness_decision=REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION
"@ | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -SourceLog $readyLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "trailing_stop_dry_run_env_deploy_handoff_packet=",
            '"packetType":"TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_PACKET"',
            "trailing_stop_dry_run_env_deploy_handoff_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
            "trailing_stop_dry_run_env_deploy_handoff_decision=REQUEST_EXACT_TRAILING_DRY_RUN_ENV_DEPLOY_AUTHORIZATION",
            "source_post_opt_in_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
            "trailing_stop_required_env_diff=.*TRAILING_STOP_ENABLED=true",
            "trailing_stop_required_env_diff=.*TRAILING_STOP_DRY_RUN=true",
            "trailing_stop_env_flags_must_remain_disabled=.*POSITION_EXIT_MANAGER_ENABLED=false",
            "trailing_stop_env_flags_must_remain_disabled=.*TRADING_OCO_POLLER_ENABLED=false",
            "trailing_stop_post_deploy_read_only_verification=.*verify_split_acceptance_ssh.ps1",
            "trailing_stop_post_deploy_read_only_verification=.*prepare_profit_next_execution_blocker_packet.ps1",
            "trailing_stop_rollback_plan=.*set TRAILING_STOP_ENABLED=false",
            "trailing_stop_exact_authorization_text=I authorize production env diff TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only trailing-stop dry-run env/deploy handoff packet only"
        )) {
        Assert-Contains -Name "ready trailing dry-run env deploy handoff replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready trailing dry-run env deploy handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $activeLog = Join-Path $tempDir "post-opt-in-active.log"
    $activePacket = [ordered]@{
        packetType = "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET"
        status = "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY"
        symbol = "BTCUSDT"
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = "true"
        currentGlobalDryRun = "true"
        currentOpenOcoPositions = "1"
        expectedOptInStrategyId = 574
        expectedStrategyOptIn = $true
        envDiffReviewReady = $false
        alreadyActiveDryRun = $true
        missingRequirements = @()
    }
    @"
trailing_stop_post_opt_in_readiness_packet=$($activePacket | ConvertTo-Json -Compress -Depth 8)
trailing_stop_post_opt_in_readiness_status=TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY
trailing_stop_post_opt_in_readiness_decision=VERIFY_ACTIVE_DRY_RUN_OBSERVATION_ONLY
"@ | Set-Content -LiteralPath $activeLog -Encoding UTF8

    $activeOutput = & $scriptPath -SourceLog $activeLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $activeText = $activeOutput -join "`n"
    Assert-Contains -Name "active trailing dry-run handoff not needed" -Text $activeText -Pattern "trailing_stop_dry_run_env_deploy_handoff_status=TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_DEPLOY_HANDOFF_NOT_NEEDED"
    Assert-Contains -Name "active trailing dry-run verification decision" -Text $activeText -Pattern "trailing_stop_dry_run_env_deploy_handoff_decision=RUN_ACTIVE_DRY_RUN_READ_ONLY_VERIFICATION"
    Assert-Contains -Name "active trailing dry-run global enabled" -Text $activeText -Pattern "trailing_stop_current_global_enabled=true"
    Assert-Contains -Name "active trailing dry-run oco sample" -Text $activeText -Pattern "trailing_stop_current_open_oco_positions=1"
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[trailing-stop-dry-run-env-deploy-handoff-test] OK"
