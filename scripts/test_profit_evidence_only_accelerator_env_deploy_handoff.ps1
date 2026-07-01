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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
        "BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_HANDOFF_REQUIREMENTS_MISSING",
        "REQUEST_EXACT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_AUTHORIZATION",
        "EVIDENCE_ONLY_ACCELERATOR",
        "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
        "TRADING_OKX_ENABLED=false",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
        "EVENT_SCAN_NOTIFICATION_ENABLED=false",
        "EXECUTION_EVENT_ENABLED=false",
        "verify_split_acceptance_ssh.ps1",
        "smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
        "smoke_live_readiness_bundle_ssh.ps1",
        "exactOperatorAuthorizationText",
        "exactDeployCommand",
        "deploy_ssh.ps1 -Branch main",
        "killSwitchEnvDiff",
        "rollbackCommands",
        "AllowDirtyLocalWorktreeForReplay",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "read-only profit evidence-only accelerator env/deploy handoff packet only"
    )) {
    Assert-Contains -Name "profit evidence-only accelerator handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram|ssh ") {
    throw "profit evidence-only accelerator handoff must not write files or invoke SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1",
        "PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET",
        "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
        "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
        "exact operator authorization"
    )) {
    Assert-Contains -Name "profit evidence-only accelerator handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs profit evidence-only accelerator handoff test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_evidence_only_accelerator_env_deploy_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-evidence-only-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $readyLog = Join-Path $tempDir "aggressive-ready.log"
    $aggressivePacket = [pscustomobject]@{
        packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
        scope = "READ_ONLY"
        status = "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
        decision = "REVIEW_HIGH_RISK_MICRO_PROBE_OR_EVIDENCE_ACCELERATOR_SEPARATELY"
        orderAllowed = $false
        deployOrEnvChangeAllowed = $false
        livePolicyChangeAllowed = $false
        aggressiveOptions = @(
            [pscustomobject]@{
                optionId = "HIGH_RISK_MICRO_LIVE_PROBE"
                recommendedNow = $false
                status = "BLOCKED_UNTIL_EXPLICIT_OPERATOR_CONFIRMATION_AND_CURRENT_BUY_OCO_EV_GATES"
            },
            [pscustomobject]@{
                optionId = "EVIDENCE_ONLY_ACCELERATOR"
                recommendedNow = $true
                status = "RECOMMENDED_AGGRESSIVE_NON_ORDER_STEP"
                proposedEnvDiff = @(
                    "TRADING_RUNTIME_EVIDENCE_ENABLED=true",
                    "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
                    "TRADING_OKX_ENABLED=false",
                    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
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
                    "operator accepts this path cannot generate profit directly because orders remain disabled",
                    "runtime/DataFreshness evidence collection is the only approved purpose",
                    "orderSentEvidence must stay 0"
                )
                postEnvReadOnlyVerificationCommands = @(
                    ".\scripts\verify_split_acceptance_ssh.ps1",
                    ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
                    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
                    ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
                    ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
                    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
                )
                killSwitchEnvDiff = @(
                    "TRADING_RUNTIME_EVIDENCE_ENABLED=false",
                    "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false",
                    "TRADING_OKX_ENABLED=false",
                    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
                    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
                    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
                    "EXECUTION_EVENT_ENABLED=false"
                )
                rollbackCommands = @(
                    "apply the evidence-only killSwitchEnvDiff through the approved deploy runbook",
                    ".\scripts\verify_split_acceptance_ssh.ps1",
                    ".\scripts\smoke_runtime_evidence_rca_ssh.ps1",
                    ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady"
                )
            }
        )
    }
    @(
        ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 12 $aggressivePacket)),
        "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -AggressivePacketLogPath $readyLog -AllowDirtyLocalWorktreeForReplay -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_evidence_only_accelerator_env_deploy_handoff_packet=",
            '"packetType":"PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_PACKET"',
            "profit_evidence_only_accelerator_env_deploy_handoff_status=READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
            "profit_evidence_only_accelerator_env_deploy_handoff_decision=REQUEST_EXACT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_AUTHORIZATION",
            "source_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
            "profit_evidence_only_required_env_diff=.*TRADING_RUNTIME_EVIDENCE_ENABLED=true",
            "profit_evidence_only_required_env_diff=.*TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
            "profit_evidence_only_env_flags_must_remain_disabled=.*TRADING_OKX_ENABLED=false",
            "profit_evidence_only_post_env_read_only_verification=.*verify_split_acceptance_ssh.ps1",
            "profit_evidence_only_post_env_read_only_verification=.*smoke_runtime_evidence_rca_ssh.ps1",
            "profit_evidence_only_kill_switch_env_diff=.*TRADING_RUNTIME_EVIDENCE_ENABLED=false",
            "profit_evidence_only_rollback_commands=.*prepare_profit_aggressive_activation_operator_packet.ps1",
            "profit_evidence_only_exact_authorization_text=I authorize evidence-only production env diff TRADING_RUNTIME_EVIDENCE_ENABLED=true and TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only profit evidence-only accelerator env/deploy handoff packet only"
        )) {
        Assert-Contains -Name "ready profit evidence-only accelerator handoff replay" -Text $readyText -Pattern $marker
    }
    Assert-Contains `
        -Name "ready profit evidence-only accelerator deploy command" `
        -Text $readyText `
        -Pattern ([regex]::Escape("profit_evidence_only_exact_deploy_command=.\scripts\deploy_ssh.ps1 -Branch main"))
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready profit evidence-only accelerator handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_evidence_only_accelerator_env_deploy_handoff_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed) {
        throw "profit evidence-only accelerator handoff packet must keep mutation flags false"
    }
    if (@($packet.proposedEnvDiff) -notcontains "TRADING_RUNTIME_EVIDENCE_ENABLED=true") {
        throw "profit evidence-only accelerator handoff should expose runtime evidence env diff"
    }
    if (@($packet.proposedEnvDiff) -notcontains "TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=true") {
        throw "profit evidence-only accelerator handoff should expose DataFreshness collector env diff"
    }

    $blockedLog = Join-Path $tempDir "aggressive-blocked.log"
    $blockedPacket = [pscustomobject]@{
        packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
        status = "BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING"
        aggressiveOptions = @()
        orderAllowed = $false
        deployOrEnvChangeAllowed = $false
        livePolicyChangeAllowed = $false
    }
    @(
        ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 6 $blockedPacket)),
        "profit_aggressive_activation_status=BLOCKED_AGGRESSIVE_ACTIVATION_EVIDENCE_MISSING"
    ) | Set-Content -LiteralPath $blockedLog -Encoding UTF8
    $blockedOutput = & $scriptPath -AggressivePacketLogPath $blockedLog -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_evidence_only_accelerator_env_deploy_handoff_status=BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_HANDOFF_REQUIREMENTS_MISSING",
            "aggressive activation packet ready",
            "EVIDENCE_ONLY_ACCELERATOR option present",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked profit evidence-only accelerator handoff replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-evidence-only-accelerator-env-deploy-handoff-test] OK"
