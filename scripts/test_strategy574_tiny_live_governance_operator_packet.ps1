Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy574_tiny_live_governance_operator_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[strategy574-tiny-live-governance-operator-packet] read-only packet",
        "scope=READ_ONLY",
        "STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET",
        "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE",
        "PREPARE_STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW",
        "BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
        "BLOCKED_TINY_LIVE_ROLLOUT_NOT_READY",
        "strategy574_tiny_live_governance_operator_packet",
        "strategy574_tiny_live_governance_status",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "notAuthorization=read-only strategy574/TinyLive governance operator packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "strategy574 TinyLive governance packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy574_tiny_live_governance_operator_packet.ps1",
        "STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET",
        "strategy574_tiny_live_governance_operator_packet",
        "strategy574/TinyLive governance operator packet",
        "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention strategy574 TinyLive governance packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy574-tiny-live-packet-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$strategyLog = Join-Path $tempDir "strategy574-gate.log"
$tinyLog = Join-Path $tempDir "tiny-live-loss-rca.log"
try {
    Set-Content -LiteralPath $strategyLog -Encoding UTF8 -Value @(
        "[strategy574-signal-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "origin_delta_status=DOCS_TOOLING_ONLY_DRIFT",
        "strategy574_near_buy=true",
        "governance_too_strict_7d_or_14d=false",
        "short_window_insufficient_data=true",
        "data_freshness_current_clean=false",
        "strategy574_terminal_reason=FIX_CURRENT_DATA_FRESHNESS_FIRST",
        "strategy574_policy_change_recommendation=DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
        "deploy_required_before_strategy574_review=false",
        "shadow_observation_review_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        'strategy574_review_missing_requirements=["current DataFreshness clean","wait for threshold-cross evidence","OCO preflight pass"]',
        "strategy574_signal_review_gate_status=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
        "strategy574_signal_review_next_action=Fix current DataFreshness before reviewing strategy 574 exploration.",
        "[strategy574-signal-review-gate] read-only check complete"
    )
    Set-Content -LiteralPath $tinyLog -Encoding UTF8 -Value @(
        "[tiny-live-loss-rca] read-only server-local MCP smoke",
        "  hardStopDetected=false",
        "  autoApprovalEligible=false",
        "  autoApprovalMode=BLOCKED",
        "  autoApprovalBlockers=[PREVIEW_NOT_READY:[NO_CURRENT_BUY_CANDIDATE, OCO_PREFLIGHT_FAILED], NO_CURRENT_BUY_CANDIDATE]",
        "  triggerEnabled=true triggerDryRun=false",
        "  executionEligible=false wouldExecute=false",
        "  terminalBlockers=[NO_CURRENT_BUY_CANDIDATE]",
        "  executedAutonomousTrades=1",
        "  successfulOcoAttachRate=+100.00%",
        "  OCOProtectionEffectiveness=PASS_ALL_EXECUTIONS_PROTECTED",
        "  completedTinyLiveSamples=2",
        "  falsePositiveCount=2",
        "  dailyLossBudgetBreached=false",
        "  canEnableProduction=false",
        "  canIncreaseDailyCap=false",
        "  rolloutBlockers=[LOOP_NOT_READY:WAIT_SIGNAL_BUY, READY_TICKS_LT_3, NO_CURRENT_BUY_CANDIDATE, COMPLETED_TINY_LIVE_SAMPLES_LT_3, FALSE_POSITIVE_COUNT_GT_1]",
        "  missing_tiny_live_fields=[]",
        "  missedOverallStatus=WARN",
        "  suspiciousNoBuyCount=20",
        "  falseBlockRiskCount=20",
        "  recommendedFix=Review near-threshold/no-buy candidates with high 1h forward return.",
        "[tiny-live-loss-rca] OK read-only check complete"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy574 TinyLive governance packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Strategy574GateLogPath $strategyLog -TinyLiveLossRcaLogPath $tinyLog -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "strategy574 TinyLive governance packet failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "source_strategy574_gate_log_freshness=FRESH",
            "source_tiny_live_loss_rca_log_freshness=FRESH",
            "strategy574_signal_review_gate_status=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
            "strategy574_policy_change_recommendation=DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
            "strategy574_data_freshness_current_clean=false",
            "tiny_live_hard_stop_detected=false",
            "tiny_live_hard_stop_clear=true",
            "tiny_live_auto_approval_eligible=false",
            "tiny_live_can_enable_production=false",
            "tiny_live_completed_samples=2",
            "tiny_live_false_positive_count=2",
            "missed_opportunity_status=WARN",
            "strategy574_tiny_live_primary_decision=PREPARE_STRATEGY574_TINY_LIVE_GOVERNANCE_REVIEW",
            "strategy574_tiny_live_risk_posture=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
            "strategy574_tiny_live_governance_review_allowed=true",
            "tiny_live_order_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "deploy_or_env_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "strategy574_tiny_live_governance_status=READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_REVIEW_NOT_LIVE",
            '"packetType":"STRATEGY574_TINY_LIVE_GOVERNANCE_OPERATOR_PACKET"',
            '"riskPosture":"BLOCKED_FIX_CURRENT_DATA_FRESHNESS"',
            '"canEnableProduction":"false"',
            "notAuthorization=read-only strategy574/TinyLive governance operator packet only"
        )) {
        Assert-Contains -Name "strategy574 TinyLive temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy574 TinyLive governance packet unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[strategy574-tiny-live-governance-operator-packet-test] OK"
