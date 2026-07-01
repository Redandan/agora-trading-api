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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_MICRO_PROBE_ENV_DEPLOY_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
        "REFRESH_MICRO_PROBE_HANDOFF_AND_PREFLIGHT_EVIDENCE_BEFORE_ACTIVATION_AUTHORIZATION",
        "profit_high_risk_micro_live_probe_handoff_packet=",
        "profit_high_risk_micro_live_probe_preflight_review_packet=",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "smoke_tiny_live_post_trade_ssh.ps1",
        "activationAuthorizationReviewReady",
        "activationExecutionAllowed = `$false",
        "envDeployRequestAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "micro_probe_activation_authorization_review_ready=",
        "micro_probe_activation_execution_allowed=false",
        "read-only high-risk micro live probe activation authorization bundle only"
    )) {
    Assert-Contains -Name "high-risk micro live probe activation bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "high-risk micro live probe activation bundle must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "micro_probe_activation_authorization_review_ready",
        "micro_probe_activation_authorization_text",
        "micro_probe_activation_execution_allowed=false",
        "micro_probe_env_deploy_request_allowed=false",
        "order_allowed=false",
        "deploy_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "high-risk micro live probe activation bundle docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs high-risk micro live probe activation bundle test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-micro-live-probe-activation-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $microLog = Join-Path $tempDir "micro-handoff.log"
    $preflightLog = Join-Path $tempDir "preflight.log"

    $proposedEnvDiff = @(
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
    $killSwitchEnvDiff = @(
        "TRADING_OKX_ENABLED=false",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false"
    )
    $microPacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET"
        status = "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION"
        selectedOptionId = "HIGH_RISK_MICRO_LIVE_PROBE"
        symbol = "BTCUSDT"
        optionMaxOrders = "1"
        optionMaxNotionalUsdt = "10"
        exactOperatorAuthorizationText = "I explicitly authorize HIGH_RISK_MICRO_LIVE_PROBE for BTCUSDT with maxNotionalUsdt=10, maxOrders=1, no policy relaxation, no grid/fund/Earn actions, immediate rollback on any unexpected order/OCO/Telegram/exchange/DB mutation, and I accept that this can lose money."
        exactDeployCommand = ".\scripts\deploy_ssh.ps1 -Branch main"
        proposedEnvDiff = @($proposedEnvDiff)
        riskAcceptanceConditions = @("operator accepts real-money loss risk up to the full maxNotionalUsdt probe")
        postEnvReadOnlyVerificationCommands = @($postEnvCommands)
        killSwitchEnvDiff = @($killSwitchEnvDiff)
        rollbackCommands = @("apply the micro-probe killSwitchEnvDiff through the approved deploy runbook")
        envDeployRequestAllowed = $false
        deployAllowed = $false
        orderAllowed = $false
        livePolicyChangeAllowed = $false
    }
    @(
        ("profit_high_risk_micro_live_probe_handoff_packet=" + (ConvertTo-Json -Compress -Depth 10 $microPacket)),
        "profit_high_risk_micro_live_probe_handoff_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION"
    ) | Set-Content -LiteralPath $microLog -Encoding UTF8

    $preflightPacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET"
        status = "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION"
        hardGateClear = $true
        exactAuthorizationReviewAllowed = $true
        runtimeOrderSentEvidence = "0"
        envDeployRequestAllowed = $false
        deployAllowed = $false
        orderAllowed = $false
        livePolicyChangeAllowed = $false
    }
    @(
        ("profit_high_risk_micro_live_probe_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $preflightPacket)),
        "profit_high_risk_micro_live_probe_preflight_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "micro_probe_hard_gate_clear=true",
        "runtime_order_sent_evidence=0"
    ) | Set-Content -LiteralPath $preflightLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -MicroProbeHandoffLogPath $microLog `
        -PreflightReviewLogPath $preflightLog `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_activation_authorization_bundle=",
            '"packetType":"PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_BUNDLE"',
            "profit_high_risk_micro_live_probe_activation_authorization_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "profit_high_risk_micro_live_probe_activation_authorization_decision=PRESENT_EXACT_MICRO_PROBE_ENV_DEPLOY_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
            "source_micro_probe_handoff_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
            "source_micro_probe_preflight_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "micro_probe_hard_gate_clear=true",
            "micro_probe_exact_authorization_review_allowed=true",
            "runtime_order_sent_evidence=0",
            "micro_probe_activation_authorization_review_ready=true",
            "MICRO_LIVE_PROBE_ENV_DEPLOY_ACTIVATION",
            "TRADING_OKX_ENABLED=true",
            "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
            "micro_probe_activation_execution_allowed=false",
            "micro_probe_env_deploy_request_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only high-risk micro live probe activation authorization bundle only"
        )) {
        Assert-Contains -Name "ready high-risk micro live probe activation replay" -Text $readyText -Pattern ([regex]::Escape($marker))
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready activation bundle unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_high_risk_micro_live_probe_activation_authorization_bundle="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.activationAuthorizationReviewReady) {
        throw "activation authorization bundle should be review-ready for ready replay"
    }
    if ([bool]$packet.activationExecutionAllowed -or [bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.envDeployRequestAllowed) {
        throw "activation authorization bundle must keep mutation flags false"
    }

    $blockedPreflightPacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET"
        status = "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REQUIREMENTS_MISSING"
        hardGateClear = $false
        exactAuthorizationReviewAllowed = $false
        runtimeOrderSentEvidence = "1"
    }
    @(
        ("profit_high_risk_micro_live_probe_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $blockedPreflightPacket)),
        "profit_high_risk_micro_live_probe_preflight_status=BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REQUIREMENTS_MISSING",
        "micro_probe_hard_gate_clear=false",
        "runtime_order_sent_evidence=1"
    ) | Set-Content -LiteralPath $preflightLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -MicroProbeHandoffLogPath $microLog `
        -PreflightReviewLogPath $preflightLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_activation_authorization_status=BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REQUIREMENTS_MISSING",
            "micro_probe_hard_gate_clear=false",
            "micro_probe_exact_authorization_review_allowed=false",
            "runtime_order_sent_evidence=1",
            "runtime orderSentEvidence=0 before activation",
            "micro_probe_activation_authorization_review_ready=false",
            "micro_probe_activation_execution_allowed=false",
            "micro_probe_env_deploy_request_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked high-risk micro live probe activation replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-high-risk-micro-live-probe-activation-authorization-bundle-test] OK"
