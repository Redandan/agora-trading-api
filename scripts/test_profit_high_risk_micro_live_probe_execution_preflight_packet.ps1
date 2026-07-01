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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION",
        "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_MICRO_PROBE_AUTHORIZATION_AND_EXECUTION_ORDER_TO_OPERATOR_DO_NOT_EXECUTE",
        "REFRESH_MICRO_PROBE_SOURCE_CHAIN_BEFORE_ENV_DEPLOY_OR_ORDER_PREFLIGHT",
        "profit_micro_probe_activation_source_refresh_packet=",
        "profit_high_risk_micro_live_probe_activation_authorization_bundle=",
        "MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION",
        "deployCommandAfterExplicitAuthorization",
        "microProbeExecutionPreflightReady",
        "microProbeEnvDeployExecutionAllowed = `$false",
        "microProbeOrderExecutionAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "orderAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "micro_probe_execution_preflight_ready=",
        "micro_probe_env_deploy_execution_allowed=false",
        "micro_probe_order_execution_allowed=false",
        "read-only high-risk micro live probe execution preflight only"
    )) {
    Assert-Contains -Name "micro live probe execution preflight script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}
if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "micro live probe execution preflight must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION",
        "micro_probe_execution_preflight_ready",
        "micro_probe_execution_exact_authorization_text",
        "micro_probe_env_deploy_execution_allowed=false",
        "micro_probe_order_execution_allowed=false",
        "order_allowed=false",
        "deploy_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "micro live probe execution preflight docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs micro live probe execution preflight test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_high_risk_micro_live_probe_execution_preflight_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("micro-probe-execution-preflight-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $sourceLog = Join-Path $tempDir "micro-source-refresh.log"
    $activationLog = Join-Path $tempDir "micro-activation.log"
    $postEnvCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol BTCUSDT",
        ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
        ".\scripts\smoke_tiny_live_loss_rca_ssh.ps1",
        ".\scripts\smoke_tiny_live_post_trade_ssh.ps1 -Symbol BTCUSDT -StrategyId 574 -Side LONG",
        ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
        ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
    )
    $authorizationText = "I explicitly authorize MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION for BTCUSDT with maxNotionalUsdt=10, maxOrders=1, env diff TRADING_RUNTIME_EVIDENCE_ENABLED=true; TRADING_OKX_ENABLED=true; TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true; ScoreBuy/Grid/Earn/Telegram mutation flags disabled as listed, immediate kill-switch rollback on abnormal post-env evidence, and I accept that this can lose money."
    $activationPacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE"
        status = "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
        decision = "PRESENT_EXACT_MICRO_PROBE_ENV_DEPLOY_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET"
        symbol = "BTCUSDT"
        maxProbeNotionalUsdt = 10
        hardGateClear = $true
        exactAuthorizationReviewAllowed = $true
        runtimeOrderSentEvidence = "0"
        activationAuthorizationReviewReady = $true
        exactActivationAuthorizationText = $authorizationText
        exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
        proposedEnvDiff = @(
            "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
            "TRADING_OKX_ENABLED=true",
            "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
            "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
            "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
            "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
            "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
            "EVENT_SCAN_NOTIFICATION_ENABLED=false",
            "EXECUTION_EVENT_ENABLED=false",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
            "GRID_RECOVERY_ENABLED=false",
            "OKX_EARN_TOPUP_ENABLED=false"
        )
        riskAcceptanceConditions = @("max one order", "max 10 USDT", "can lose money")
        postEnvReadOnlyVerificationCommands = @($postEnvCommands)
        killSwitchEnvDiff = @(
            "TRADING_OKX_ENABLED=false",
            "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
            "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
            "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
            "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
            "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
            "EVENT_SCAN_NOTIFICATION_ENABLED=false",
            "EXECUTION_EVENT_ENABLED=false"
        )
        rollbackCommands = @("apply the micro-probe killSwitchEnvDiff through the approved deploy runbook")
        activationExecutionAllowed = $false
        envDeployRequestAllowed = $false
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        livePolicyChangeAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        gridMutationAllowed = $false
        telegramSendAllowed = $false
        dbGridFundEarnExchangeMutationAllowed = $false
    }
    $sourcePacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PACKET"
        status = "READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
        decision = "REVIEW_EXACT_MICRO_PROBE_ACTIVATION_AUTHORIZATION_TEXT_WITH_OPERATOR_DO_NOT_EXECUTE_FROM_REFRESH"
        ready = $true
        symbol = "BTCUSDT"
        maxProbeNotionalUsdt = 10
        activationBundleLogPath = $activationLog
        failedStepCount = 0
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        orderAllowed = $false
        gridMutationAllowed = $false
    }
    @(
        ("profit_high_risk_micro_live_probe_activation_authorization_bundle=" + (ConvertTo-Json -Compress -Depth 18 $activationPacket)),
        "profit_high_risk_micro_live_probe_activation_authorization_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION"
    ) | Set-Content -LiteralPath $activationLog -Encoding UTF8
    @(
        ("profit_micro_probe_activation_source_refresh_packet=" + (ConvertTo-Json -Compress -Depth 18 $sourcePacket)),
        "profit_micro_probe_activation_source_refresh_status=READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "profit_micro_probe_activation_source_refresh_activation_ready=true"
    ) | Set-Content -LiteralPath $sourceLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -MicroProbeSourceRefreshLogPath $sourceLog `
        -ActivationAuthorizationBundleLogPath $activationLog `
        -MaxProbeNotionalUsdt 10 `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_execution_preflight_packet=",
            '"packetType":"PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_PACKET"',
            "profit_high_risk_micro_live_probe_execution_preflight_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_NOT_MUTATION",
            "profit_high_risk_micro_live_probe_execution_preflight_decision=PRESENT_EXACT_MICRO_PROBE_AUTHORIZATION_AND_EXECUTION_ORDER_TO_OPERATOR_DO_NOT_EXECUTE",
            "source_micro_probe_activation_source_refresh_status=READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "source_micro_probe_activation_authorization_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "micro_probe_execution_preflight_ready=true",
            "micro_probe_execution_exact_authorization_text=.*MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION",
            "micro_probe_execution_order=.*Operator confirms exactActivationAuthorizationText",
            "micro_probe_execution_deploy_command_after_explicit_authorization=.*deploy_ssh.ps1 -Branch main",
            "micro_probe_execution_env_diff=.*TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
            "micro_probe_execution_risk_acceptance_conditions=.*max one order",
            "micro_probe_execution_post_env_read_only_verification=.*smoke_tiny_live_post_trade_ssh.ps1",
            "micro_probe_execution_kill_switch_env_diff=.*TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
            "micro_probe_max_notional_usdt=10",
            "runtime_order_sent_evidence=0",
            "micro_probe_hard_gate_clear=true",
            "micro_probe_env_deploy_execution_allowed=false",
            "micro_probe_order_execution_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only high-risk micro live probe execution preflight only"
        )) {
        Assert-Contains -Name "ready micro live probe execution preflight replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready micro live probe execution preflight unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_high_risk_micro_live_probe_execution_preflight_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.microProbeExecutionPreflightReady) {
        throw "micro live probe execution preflight should be ready for complete replay logs"
    }
    if ([bool]$packet.deployAllowed -or [bool]$packet.orderAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.microProbeEnvDeployExecutionAllowed -or [bool]$packet.microProbeOrderExecutionAllowed) {
        throw "micro live probe execution preflight packet must keep mutation flags false"
    }

    $blockedSourcePacket = $sourcePacket.PSObject.Copy()
    $blockedSourcePacket.ready = $false
    $blockedSourcePacket.failedStepCount = 1
    $blockedSourcePacket.status = "INCOMPLETE_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS"
    @(
        ("profit_micro_probe_activation_source_refresh_packet=" + (ConvertTo-Json -Compress -Depth 18 $blockedSourcePacket)),
        "profit_micro_probe_activation_source_refresh_status=INCOMPLETE_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS",
        "profit_micro_probe_activation_source_refresh_activation_ready=false"
    ) | Set-Content -LiteralPath $sourceLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -MicroProbeSourceRefreshLogPath $sourceLog `
        -ActivationAuthorizationBundleLogPath $activationLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_execution_preflight_status=BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_EXECUTION_PREFLIGHT_REQUIREMENTS_MISSING",
            "micro probe source refresh ready",
            "micro probe source refresh activation ready flag true",
            "micro probe source refresh failedStepCount=0",
            "micro_probe_execution_preflight_ready=false",
            "micro_probe_env_deploy_execution_allowed=false",
            "micro_probe_order_execution_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked micro live probe execution preflight replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-high-risk-micro-live-probe-execution-preflight-test] OK"
