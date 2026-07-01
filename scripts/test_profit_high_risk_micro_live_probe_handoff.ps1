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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_high_risk_micro_live_probe_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_REQUIREMENTS_MISSING",
        "REVIEW_EXACT_HIGH_RISK_MICRO_LIVE_PROBE_AUTHORIZATION_BUT_DO_NOT_DEPLOY",
        "HIGH_RISK_MICRO_LIVE_PROBE",
        "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "verify_split_acceptance_ssh.ps1",
        "smoke_live_background_automation_ssh.ps1 -RequireClear",
        "smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
        "audit_live_readiness_ssh.ps1 -Symbol",
        "smoke_live_readiness_bundle_ssh.ps1",
        "smoke_tiny_live_loss_rca_ssh.ps1",
        "smoke_tiny_live_post_trade_ssh.ps1",
        "prepare_profit_live_blocker_source_refresh.ps1",
        "exact operator confirmation text",
        "current BUY/scout candidate",
        "OCO preflight pass or explicit no-OCO risk acceptance",
        "EV snapshot pass",
        "event risk R0/R1",
        "executionHardGateClear = `$false",
        "envDeployRequestAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "livePolicyChangeAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "micro_probe_execution_hard_gate_clear=false",
        "micro_probe_env_deploy_request_allowed=false",
        "read-only high-risk micro live probe handoff packet only"
    )) {
    Assert-Contains -Name "high-risk micro live probe handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "high-risk micro live probe handoff must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_high_risk_micro_live_probe_handoff.ps1",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
        "micro_probe_exact_authorization_text",
        "micro_probe_env_deploy_request_allowed=false",
        "order_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "high-risk micro live probe handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs high-risk micro live probe handoff test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_high_risk_micro_live_probe_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-micro-live-probe-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $readyLog = Join-Path $tempDir "aggressive-ready.log"
    $confirmationText = "I explicitly authorize HIGH_RISK_MICRO_LIVE_PROBE for BTCUSDT with maxNotionalUsdt=10, maxOrders=1, no policy relaxation, no grid/fund/Earn actions, immediate rollback on any unexpected order/OCO/Telegram/exchange/DB mutation, and I accept that this can lose money."
    $microOption = [pscustomobject]@{
        optionId = "HIGH_RISK_MICRO_LIVE_PROBE"
        priority = 1
        risk = "HIGH"
        recommendedNow = $false
        status = "BLOCKED_UNTIL_EXPLICIT_OPERATOR_CONFIRMATION_AND_CURRENT_BUY_OCO_EV_GATES"
        maxNotionalUsdt = 10
        maxOrders = 1
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
        riskAcceptanceConditions = @(
            "operator accepts real-money loss risk up to the full maxNotionalUsdt probe",
            "single probe order only; maxOrders=1 and maxNotionalUsdt=10",
            "current BUY/scout candidate, OCO/EV/event-risk gates, and live-readiness bundle must be fresh"
        )
        requiredBeforeExecution = @(
            "exact operator confirmation text",
            "fresh live-readiness bundle",
            "current BUY/scout candidate",
            "OCO preflight pass or explicit no-OCO risk acceptance",
            "EV snapshot pass",
            "event risk R0/R1",
            "runtime evidence enabled and orderSentEvidence=0 before probe",
            "kill switch and rollback env diff prepared"
        )
        postEnvReadOnlyVerificationCommands = @(
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
        rollbackCommands = @(
            "apply the micro-probe killSwitchEnvDiff through the approved deploy runbook",
            ".\scripts\verify_split_acceptance_ssh.ps1",
            ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
            ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
            ".\scripts\smoke_live_readiness_bundle_ssh.ps1"
        )
        confirmationText = $confirmationText
    }
    $aggressivePacket = [pscustomobject]@{
        packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
        scope = "READ_ONLY"
        status = "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
        decision = "REVIEW_HIGH_RISK_MICRO_PROBE_OR_EVIDENCE_ACCELERATOR_SEPARATELY"
        symbol = "BTCUSDT"
        maxProbeNotionalUsdt = 10
        orderAllowed = $false
        deployOrEnvChangeAllowed = $false
        livePolicyChangeAllowed = $false
        aggressiveOptions = @($microOption)
    }
    @(
        ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 12 $aggressivePacket)),
        "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -AggressivePacketLogPath $readyLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_handoff_packet=",
            '"packetType":"PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET"',
            "profit_high_risk_micro_live_probe_handoff_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
            "profit_high_risk_micro_live_probe_handoff_decision=REVIEW_EXACT_HIGH_RISK_MICRO_LIVE_PROBE_AUTHORIZATION_BUT_DO_NOT_DEPLOY",
            "source_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
            "micro_probe_max_notional_usdt=10",
            "micro_probe_max_orders=1",
            "micro_probe_required_env_diff=.*TRADING_OKX_ENABLED=true",
            "micro_probe_required_env_diff=.*TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
            "micro_probe_env_flags_must_remain_disabled=.*TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
            "micro_probe_post_env_read_only_verification=.*smoke_tiny_live_post_trade_ssh.ps1",
            "micro_probe_kill_switch_env_diff=.*TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
            "micro_probe_exact_authorization_text=I explicitly authorize HIGH_RISK_MICRO_LIVE_PROBE",
            "micro_probe_execution_hard_gate_clear=false",
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
            "notAuthorization=read-only high-risk micro live probe handoff packet only"
        )) {
        Assert-Contains -Name "ready high-risk micro live probe handoff replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready high-risk micro live probe handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_high_risk_micro_live_probe_handoff_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.envDeployRequestAllowed) {
        throw "high-risk micro live probe handoff packet must keep execution/deploy flags false"
    }
    if ([string]$packet.optionMaxOrders -ne "1") {
        throw "high-risk micro live probe handoff must preserve maxOrders=1"
    }
    if (@($packet.hardGateChecklist) -notcontains "current BUY/scout candidate") {
        throw "high-risk micro live probe handoff should expose current BUY/scout hard gate"
    }

    $blockedLog = Join-Path $tempDir "aggressive-blocked.log"
    $blockedPacket = [pscustomobject]@{
        packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
        status = "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
        aggressiveOptions = @()
        orderAllowed = $false
        deployOrEnvChangeAllowed = $false
        livePolicyChangeAllowed = $false
    }
    @(
        ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 6 $blockedPacket)),
        "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $blockedLog -Encoding UTF8
    $blockedOutput = & $scriptPath -AggressivePacketLogPath $blockedLog -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_handoff_status=BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_REQUIREMENTS_MISSING",
            "HIGH_RISK_MICRO_LIVE_PROBE option present",
            "micro_probe_env_deploy_request_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked high-risk micro live probe handoff replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-high-risk-micro-live-probe-handoff-test] OK"
