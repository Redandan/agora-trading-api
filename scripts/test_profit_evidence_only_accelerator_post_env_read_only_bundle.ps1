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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_evidence_only_accelerator_post_env_read_only_bundle_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE",
        "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE",
        "BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_REQUIREMENTS_MISSING",
        "CONTINUE_EVIDENCE_COLLECTION_NO_LIVE_RELAXATION",
        "REFRESH_POST_ENV_EVIDENCE_BEFORE_PROFIT_REVIEW",
        "verify_split_acceptance_ssh.ps1",
        "smoke_live_background_automation_ssh.ps1",
        "smoke_runtime_evidence_rca_ssh.ps1",
        "smoke_live_readiness_bundle_ssh.ps1",
        "prepare_profit_live_blocker_source_refresh.ps1",
        "prepare_profit_aggressive_activation_operator_packet.ps1",
        "prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1",
        "AllowDirtyLocalWorktreeForReplay",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "diagnosis=CANONICAL_SHADOW_READY",
        "shadowIntentCount=([1-9][0-9]*)",
        "orderSentEvidence=0",
        "backgroundAutomationClear=true",
        "bundle_verdict=(READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED|NOT_READY)",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "livePolicyChangeAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "grid_mutation_allowed=false",
        "telegram_send_allowed=false",
        "read-only profit evidence-only accelerator post-env bundle only"
    )) {
    Assert-Contains -Name "profit evidence-only accelerator post-env bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "profit evidence-only accelerator post-env bundle must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_evidence_only_accelerator_post_env_read_only_bundle_ssh.ps1",
        "PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE",
        "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE",
        "profit_evidence_only_post_env_bundle_status",
        "runtime_shadow_intent_count",
        "runtime_order_sent_evidence",
        "live_policy_change_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "profit evidence-only accelerator post-env bundle docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs profit evidence-only accelerator post-env bundle test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_evidence_only_accelerator_post_env_read_only_bundle.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-evidence-post-env-bundle-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $splitLog = Join-Path $tempDir "split.log"
    $backgroundLog = Join-Path $tempDir "background.log"
    $runtimeLog = Join-Path $tempDir "runtime.log"
    $liveBundleLog = Join-Path $tempDir "live-bundle.log"
    $sourceRefreshLog = Join-Path $tempDir "source-refresh.log"
    $aggressiveLog = Join-Path $tempDir "aggressive.log"
    $handoffLog = Join-Path $tempDir "handoff.log"

    @(
        "[split-acceptance] trading server verification",
        "[split-acceptance] OK"
    ) | Set-Content -LiteralPath $splitLog -Encoding UTF8
    @(
        "[live-background-automation] read-only server env smoke",
        "scope=READ_ONLY; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, or external backfill/import state changed.",
        "background_automation_blockers=[]",
        "backgroundAutomationClear=true",
        "verdict=OK_BACKGROUND_AUTOMATION_DISABLED"
    ) | Set-Content -LiteralPath $backgroundLog -Encoding UTF8
    @(
        "Runtime Evidence Gate:",
        "  diagnosis=CANONICAL_SHADOW_READY",
        "  env.TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "Recent Evidence Window:",
        "  shadowIntentCount=4",
        "  orderSentEvidence=0",
        "  missing_runtime_evidence_fields=[]",
        "[runtime-evidence-rca] OK read-only check complete"
    ) | Set-Content -LiteralPath $runtimeLog -Encoding UTF8
    @(
        "[live-readiness-bundle] read-only SSH smoke bundle",
        "scope=READ_ONLY; invokes existing read-only SSH smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed.",
        "orderSentEvidence=0",
        "live_review_packet_allowed=false",
        "bundle_verdict=NOT_READY",
        "[live-readiness-bundle] read-only check complete"
    ) | Set-Content -LiteralPath $liveBundleLog -Encoding UTF8
    @(
        "[profit-live-blocker-source-refresh] read-only refresh orchestration",
        "scope=READ_ONLY; invokes existing read-only SSH/MCP/SELECT evidence scripts and local packet assembly only; no deploy, production env, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, restart, or nginx state changed.",
        "profit_live_blocker_source_refresh_status=COMPLETE_REFRESHED_SOURCES_NOT_LIVE_READY"
    ) | Set-Content -LiteralPath $sourceRefreshLog -Encoding UTF8
    @(
        "[profit-aggressive-activation-operator-packet] read-only packet",
        "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
        "deploy_or_env_change_allowed=false",
        "live_policy_change_allowed=false",
        "order_allowed=false"
    ) | Set-Content -LiteralPath $aggressiveLog -Encoding UTF8
    @(
        "[profit-evidence-only-accelerator-env-deploy-handoff] read-only packet",
        "profit_evidence_only_accelerator_env_deploy_handoff_status=READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "order_allowed=false"
    ) | Set-Content -LiteralPath $handoffLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -SplitAcceptanceLogPath $splitLog `
        -BackgroundAutomationLogPath $backgroundLog `
        -RuntimeEvidenceRcaLogPath $runtimeLog `
        -LiveReadinessBundleLogPath $liveBundleLog `
        -ProfitSourceRefreshLogPath $sourceRefreshLog `
        -AggressivePacketLogPath $aggressiveLog `
        -HandoffLogPath $handoffLog `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_evidence_only_accelerator_post_env_bundle_packet=",
            '"packetType":"PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE"',
            "profit_evidence_only_post_env_bundle_status=READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE",
            "profit_evidence_only_post_env_bundle_decision=CONTINUE_EVIDENCE_COLLECTION_NO_LIVE_RELAXATION",
            "profit_evidence_only_post_env_bundle_ready=true",
            "runtime_evidence_diagnosis=CANONICAL_SHADOW_READY",
            "runtime_shadow_intent_count=4",
            "runtime_order_sent_evidence=0",
            "live_readiness_bundle_verdict=NOT_READY",
            "profit_live_blocker_source_refresh_status=COMPLETE_REFRESHED_SOURCES_NOT_LIVE_READY",
            "source_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
            "source_evidence_only_handoff_status=READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "grid_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only profit evidence-only accelerator post-env bundle only"
        )) {
        Assert-Contains -Name "ready profit evidence-only accelerator post-env replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready profit evidence-only post-env replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_evidence_only_accelerator_post_env_bundle_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.ready) {
        throw "profit evidence-only post-env bundle packet should be ready for complete replay logs"
    }
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.gridMutationAllowed) {
        throw "profit evidence-only post-env bundle packet must keep mutation flags false"
    }
    if ([string]$packet.runtimeOrderSentEvidence -ne "0") {
        throw "profit evidence-only post-env bundle should preserve orderSentEvidence=0"
    }

    $blockedRuntimeLog = Join-Path $tempDir "runtime-blocked.log"
    @(
        "Runtime Evidence Gate:",
        "  diagnosis=CANONICAL_SHADOW_READY",
        "  env.TRADING_RUNTIME_EVIDENCE_ENABLED=true",
        "Recent Evidence Window:",
        "  shadowIntentCount=4",
        "  orderSentEvidence=1",
        "  missing_runtime_evidence_fields=[]"
    ) | Set-Content -LiteralPath $blockedRuntimeLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -SplitAcceptanceLogPath $splitLog `
        -BackgroundAutomationLogPath $backgroundLog `
        -RuntimeEvidenceRcaLogPath $blockedRuntimeLog `
        -LiveReadinessBundleLogPath $liveBundleLog `
        -ProfitSourceRefreshLogPath $sourceRefreshLog `
        -AggressivePacketLogPath $aggressiveLog `
        -HandoffLogPath $handoffLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_evidence_only_post_env_bundle_status=BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_REQUIREMENTS_MISSING",
            "runtime_order_sent_evidence=1",
            "canonical runtime evidence with shadowIntentCount>0 and orderSentEvidence=0",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked profit evidence-only accelerator post-env replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-evidence-only-accelerator-post-env-bundle-test] OK"
