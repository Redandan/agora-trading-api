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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_grid10_activation_authorization_bundle.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE",
        "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_GRID10_ENV_DEPLOY_CREATEGRID_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
        "REFRESH_GRID10_HANDOFF_EVIDENCE_BEFORE_ACTIVATION_AUTHORIZATION",
        "profit_grid10_order_path_handoff_packet=",
        "GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "activationAuthorizationReviewReady",
        "activationExecutionAllowed = `$false",
        "envDeployRequestAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "orderAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "grid10_activation_authorization_review_ready=",
        "grid10_activation_execution_allowed=false",
        "read-only profit grid10 activation authorization bundle only"
    )) {
    Assert-Contains -Name "profit grid10 activation bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "profit grid10 activation bundle must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_grid10_activation_authorization_bundle.ps1",
        "PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE",
        "READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "grid10_activation_authorization_review_ready",
        "grid10_activation_authorization_text",
        "grid10_activation_execution_allowed=false",
        "grid10_env_deploy_request_allowed=false",
        "create_grid_allowed=false",
        "order_allowed=false",
        "deploy_allowed=false"
    )) {
    Assert-Contains -Name "profit grid10 activation bundle docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs profit grid10 activation bundle test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_grid10_activation_authorization_bundle.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-grid10-activation-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $handoffLog = Join-Path $tempDir "grid10-handoff.log"

    $exactTexts = @(
        "I explicitly authorize GRID10_TREND_REGIME_OVERRIDE_REVIEW for BTCUSDT with trendGate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE for the attached 2 x 5 USDT grid candidate; this is not createGrid authorization.",
        "I explicitly authorize GRID10_CAPITAL_CAP_OVERRIDE_REVIEW for BTCUSDT from effectiveReviewCapitalCapUsdt=5 to candidateCapitalUsdt=10; this is not createGrid authorization.",
        "I explicitly authorize GRID10_ENV_DIFF_REVIEW for BTCUSDT with TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true while TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false, GRID_RECOVERY_ENABLED=false, OKX_EARN_TOPUP_ENABLED=false, EVENT_SCAN_NOTIFICATION_ENABLED=false, and EXECUTION_EVENT_ENABLED=false.",
        "I explicitly authorize GRID10_DEPLOY_RESTART_AND_POST_ENV_READ_ONLY_VERIFICATION for BTCUSDT; post-env verification must pass before createGrid is reviewed.",
        "After post-env read-only verification passes, I explicitly authorize GRID10_CREATEGRID_REVIEW for BTCUSDT with gridCount=2, perLevelUsdt=5, candidateCapitalUsdt=10, stopOutPct=5, candidateHalfWidthPct=10, replayScore=80, and I accept this can lose money."
    )
    $postEnvCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -RequireVerificationReady",
        ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -RequireBoardReady",
        ".\scripts\prepare_grid_open_authorization_bundle_ssh.ps1 -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10 -AcceptAlreadyAppliedEnvDiff -RequireBundleReady",
        ".\scripts\prepare_profit_grid10_order_path_handoff.ps1 -RequireReady"
    )
    $handoffPacket = [pscustomobject]@{
        packetType = "PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET"
        status = "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION"
        decision = "REVIEW_SEPARATE_GRID10_ORDER_PATH_AUTHORIZATIONS_BUT_DO_NOT_DEPLOY"
        selectedOptionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
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
        trendLaneReady = $true
        capitalOverrideReviewReady = $true
        envDiffReviewReady = $true
        createGridPreflightEvidenceComplete = $true
        candidatePlanComplete = $true
        existingActiveGridOrderPathActivationRisk = $false
        exactOperatorAuthorizationTexts = @($exactTexts)
        remainingExecutionBlockers = @(
            "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE",
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
        grid10OrderPathHandoffReady = $true
        grid10ExecutionNowAllowed = $false
        grid10EnvDeployRequestAllowed = $false
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
        ("profit_grid10_order_path_handoff_packet=" + (ConvertTo-Json -Compress -Depth 18 $handoffPacket)),
        "profit_grid10_order_path_handoff_status=READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION"
    ) | Set-Content -LiteralPath $handoffLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -Grid10HandoffLogPath $handoffLog `
        -GridCount 2 `
        -PerLevelUsdt 5 `
        -StopOutPct 5 `
        -CandidateHalfWidthPct 10 `
        -MaxCapitalUsdt 10 `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_activation_authorization_bundle=",
            '"packetType":"PROFIT_GRID10_ACTIVATION_AUTHORIZATION_BUNDLE"',
            "profit_grid10_activation_authorization_status=READY_FOR_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "profit_grid10_activation_authorization_decision=PRESENT_EXACT_GRID10_ENV_DEPLOY_CREATEGRID_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
            "source_grid10_handoff_status=READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION",
            "grid10_activation_authorization_review_ready=true",
            "GRID10_ENV_DEPLOY_CREATEGRID_ACTIVATION_REVIEW",
            "grid10_candidate_capital_usdt=10",
            "grid10_effective_review_capital_cap_usdt=5",
            "grid10_replay_score=80",
            "grid10_trend_gate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE",
            "TRADING_OKX_ENABLED=true",
            "TRADING_GRID_ENABLED=true",
            "grid10_post_env_read_only_verification=.*prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
            "grid10_kill_switch_env_diff=.*TRADING_OKX_ENABLED=false",
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
            "notAuthorization=read-only profit grid10 activation authorization bundle only"
        )) {
        Assert-Contains -Name "ready profit grid10 activation replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready grid10 activation bundle unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_grid10_activation_authorization_bundle="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.activationAuthorizationReviewReady) {
        throw "grid10 activation authorization bundle should be review-ready for ready replay"
    }
    if ([bool]$packet.activationExecutionAllowed -or [bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.envDeployRequestAllowed -or [bool]$packet.createGridAllowed) {
        throw "grid10 activation authorization bundle must keep mutation flags false"
    }

    $blockedPacket = $handoffPacket.PSObject.Copy()
    $blockedPacket.grid10OrderPathHandoffReady = $false
    $blockedPacket.remainingExecutionBlockers = @("OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED")
    @(
        ("profit_grid10_order_path_handoff_packet=" + (ConvertTo-Json -Compress -Depth 18 $blockedPacket)),
        "profit_grid10_order_path_handoff_status=BLOCKED_PROFIT_GRID10_ORDER_PATH_HANDOFF_REQUIREMENTS_MISSING"
    ) | Set-Content -LiteralPath $handoffLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -Grid10HandoffLogPath $handoffLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_activation_authorization_status=BLOCKED_PROFIT_GRID10_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
            "grid10 order path handoff ready",
            "grid10 order path handoff ready flag true",
            "grid10_activation_authorization_review_ready=false",
            "grid10_activation_execution_allowed=false",
            "grid10_env_deploy_request_allowed=false",
            "order_allowed=false",
            "create_grid_allowed=false"
        )) {
        Assert-Contains -Name "blocked profit grid10 activation replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-grid10-activation-authorization-bundle-test] OK"
