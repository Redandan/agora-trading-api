Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_grid10_same_session_activation_review_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_PACKET",
        "READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION",
        "BLOCKED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_REQUIREMENTS_MISSING",
        "PRESENT_GRID10_SAME_SESSION_OPERATOR_CHECKLIST_DO_NOT_EXECUTE",
        "REFRESH_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE_BEFORE_SAME_SESSION_REVIEW",
        "profit_grid10_activation_authorization_bundle=",
        "GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW",
        "sameSessionOperatorChecklistReady",
        "sameSessionExecutionAllowed = `$false",
        "sameSessionEnvDeployAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "orderAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "grid10_same_session_operator_checklist_ready=",
        "grid10_same_session_execution_allowed=false",
        "grid10_same_session_env_deploy_allowed=false",
        "read-only profit grid10 same-session activation review packet only"
    )) {
    Assert-Contains -Name "profit grid10 same-session review script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "profit grid10 same-session review must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_grid10_same_session_activation_review_packet.ps1",
        "PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_PACKET",
        "READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION",
        "grid10_same_session_operator_checklist_ready",
        "grid10_same_session_exact_authorization_text",
        "grid10_same_session_execution_allowed=false",
        "grid10_same_session_env_deploy_allowed=false",
        "order_allowed=false",
        "create_grid_allowed=false",
        "deploy_allowed=false"
    )) {
    Assert-Contains -Name "profit grid10 same-session review docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs profit grid10 same-session review test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_grid10_same_session_activation_review_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-grid10-same-session-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $activationLog = Join-Path $tempDir "grid10-activation.log"
    $postEnvCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -RequireVerificationReady",
        ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -RequireBoardReady",
        ".\scripts\prepare_grid_open_authorization_bundle_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -AcceptAlreadyAppliedEnvDiff -RequireBundleReady",
        ".\scripts\prepare_profit_grid10_order_path_handoff.ps1 -RequireReady"
    )
    $activationText = "I explicitly authorize GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW for BTCUSDT with gridCount=2, perLevelUsdt=5, candidateCapitalUsdt=10, effectiveReviewCapitalCapUsdt=5, stopOutPct=5, candidateHalfWidthPct=10, replayScore=80, trendGate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE, TRADING_OKX_ENABLED=true, TRADING_GRID_ENABLED=true, grid scheduler/recovery/Earn/Telegram live action flags disabled as listed, post-env read-only verification before createGrid, immediate kill-switch rollback on abnormal evidence, and I accept this can lose money."
    $activationPacket = [pscustomobject]@{
        packetType = "PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE"
        status = "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
        decision = "PRESENT_EXACT_GRID10_ENV_DEPLOY_CREATEGRID_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
        symbol = "BTCUSDT"
        gridCount = 2
        perLevelUsdt = 5
        stopOutPct = 5
        candidateHalfWidthPct = 10
        maxCapitalUsdt = 10
        candidateCapitalUsdt = 10
        effectiveReviewCapitalCapUsdt = 5
        replayScore = 80
        trendGate = "BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE"
        activationAuthorizationReviewReady = $true
        exactActivationAuthorizationText = $activationText
        remainingExecutionBlockers = @(
            "OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED",
            "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED",
            "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED",
            "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED"
        )
        proposedEnvDiff = @(
            "TRADING_OKX_ENABLED=true",
            "TRADING_GRID_ENABLED=true",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
            "GRID_RECOVERY_ENABLED=false",
            "OKX_EARN_TOPUP_ENABLED=false",
            "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
            "EVENT_SCAN_NOTIFICATION_ENABLED=false",
            "EXECUTION_EVENT_ENABLED=false"
        )
        postEnvReadOnlyVerificationCommands = @($postEnvCommands)
        killSwitchEnvDiff = @(
            "TRADING_OKX_ENABLED=false",
            "TRADING_GRID_ENABLED=false",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
            "GRID_RECOVERY_ENABLED=false",
            "OKX_EARN_TOPUP_ENABLED=false",
            "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
            "EVENT_SCAN_NOTIFICATION_ENABLED=false",
            "EXECUTION_EVENT_ENABLED=false"
        )
        rollbackCommands = @("apply the grid10 killSwitchEnvDiff through the approved deploy runbook")
        activationExecutionAllowed = $false
        envDeployRequestAllowed = $false
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        createGridAllowed = $false
        gridMutationAllowed = $false
        telegramSendAllowed = $false
        dbGridFundEarnExchangeMutationAllowed = $false
    }
    @(
        ("profit_grid10_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 18 $activationPacket)),
        "profit_grid10_activation_authorization_status=READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
    ) | Set-Content -LiteralPath $activationLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -Grid10ActivationBundleLogPath $activationLog `
        -GridCount 2 `
        -PerLevelUsdt 5 `
        -StopOutPct 5 `
        -CandidateHalfWidthPct 10 `
        -MaxCapitalUsdt 10 `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_same_session_activation_review_packet=",
            '"packetType":"PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_PACKET"',
            "profit_grid10_same_session_activation_review_status=READY_FOR_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_NOT_MUTATION",
            "profit_grid10_same_session_activation_review_decision=PRESENT_GRID10_SAME_SESSION_OPERATOR_CHECKLIST_DO_NOT_EXECUTE",
            "source_grid10_activation_authorization_status=READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "grid10_same_session_operator_checklist_ready=true",
            "grid10_same_session_exact_authorization_text=.*GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW",
            "grid10_same_session_operator_checklist=.*Confirm the exact grid10_activation_authorization_text",
            "grid10_same_session_operator_checklist=.*Deploy/restart only after",
            "grid10_same_session_env_diff=.*TRADING_OKX_ENABLED=true",
            "grid10_same_session_env_diff=.*TRADING_GRID_ENABLED=true",
            "grid10_same_session_pre_execution_review_commands=.*prepare_profit_grid10_activation_authorization_bundle.ps1",
            "grid10_same_session_post_env_read_only_verification=.*prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
            "grid10_same_session_kill_switch_env_diff=.*TRADING_OKX_ENABLED=false",
            "grid10_candidate_capital_usdt=10",
            "grid10_effective_review_capital_cap_usdt=5",
            "grid10_replay_score=80",
            "grid10_trend_gate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE",
            "grid10_same_session_execution_allowed=false",
            "grid10_same_session_env_deploy_allowed=false",
            "grid10_activation_execution_allowed=false",
            "grid10_env_deploy_request_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "create_grid_allowed=false",
            "grid_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only profit grid10 same-session activation review packet only"
        )) {
        Assert-Contains -Name "ready profit grid10 same-session replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready grid10 same-session review unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_grid10_same_session_activation_review_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.sameSessionOperatorChecklistReady) {
        throw "grid10 same-session activation review should be checklist-ready for ready replay"
    }
    if ([bool]$packet.sameSessionExecutionAllowed -or [bool]$packet.sameSessionEnvDeployAllowed -or [bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.createGridAllowed) {
        throw "grid10 same-session activation review must keep mutation flags false"
    }

    $blockedPacket = $activationPacket.PSObject.Copy()
    $blockedPacket.activationAuthorizationReviewReady = $false
    $blockedPacket.status = "BLOCKED_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING"
    $blockedPacket.deployAllowed = $true
    @(
        ("profit_grid10_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 18 $blockedPacket)),
        "profit_grid10_activation_authorization_status=BLOCKED_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING"
    ) | Set-Content -LiteralPath $activationLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -Grid10ActivationBundleLogPath $activationLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_same_session_activation_review_status=BLOCKED_PROFIT_GRID10_SAME_SESSION_ACTIVATION_REVIEW_REQUIREMENTS_MISSING",
            "grid10 activation authorization bundle ready",
            "grid10 activationAuthorizationReviewReady=true",
            "grid10 activation bundle keeps deployAllowed=false",
            "grid10_same_session_operator_checklist_ready=false",
            "grid10_same_session_execution_allowed=false",
            "grid10_same_session_env_deploy_allowed=false",
            "order_allowed=false",
            "create_grid_allowed=false"
        )) {
        Assert-Contains -Name "blocked profit grid10 same-session replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-grid10-same-session-activation-review-test] OK"
