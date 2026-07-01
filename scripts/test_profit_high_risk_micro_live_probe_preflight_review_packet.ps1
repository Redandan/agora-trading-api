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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REQUIREMENTS_MISSING",
        "REFRESH_MICRO_PROBE_HARD_GATE_EVIDENCE_BEFORE_AUTHORIZATION_REVIEW",
        "prepare_profit_high_risk_micro_live_probe_handoff.ps1",
        "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1",
        "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1",
        "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
        "diagnosis=CANONICAL_SHADOW_READY",
        "orderSentEvidence=0",
        "strategy574 risk posture review-only ready",
        "TinyLive canEnableProduction=true",
        "TP/SL/OCO preflight ready",
        "runtime evidence canonical shadow ready with orderSentEvidence=0",
        "exactAuthorizationReviewAllowed = `$hardGateClear",
        "envDeployRequestAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "livePolicyChangeAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "micro_probe_hard_gate_clear=",
        "micro_probe_exact_authorization_review_allowed=",
        "micro_probe_env_deploy_request_allowed=false",
        "read-only high-risk micro live probe preflight review packet only"
    )) {
    Assert-Contains -Name "high-risk micro live probe preflight script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "high-risk micro live probe preflight must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "micro_probe_hard_gate_clear",
        "micro_probe_exact_authorization_review_allowed",
        "micro_probe_env_deploy_request_allowed=false",
        "order_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "high-risk micro live probe preflight docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs high-risk micro live probe preflight test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_high_risk_micro_live_probe_preflight_review_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-micro-live-probe-preflight-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $microLog = Join-Path $tempDir "micro-handoff.log"
    $strategyLog = Join-Path $tempDir "strategy574-preflight.log"
    $tpLog = Join-Path $tempDir "tp-sl-oco-preflight.log"
    $liveLog = Join-Path $tempDir "live-review.log"
    $runtimeLog = Join-Path $tempDir "runtime-rca.log"

    $microPacket = [pscustomobject]@{
        packetType = "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_HANDOFF_PACKET"
        status = "READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION"
        optionMaxOrders = "1"
        optionMaxNotionalUsdt = "10"
        envDeployRequestAllowed = $false
        deployAllowed = $false
        orderAllowed = $false
        livePolicyChangeAllowed = $false
    }
    @(
        ("profit_high_risk_micro_live_probe_handoff_packet=" + (ConvertTo-Json -Compress -Depth 8 $microPacket)),
        "profit_high_risk_micro_live_probe_handoff_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
        "micro_probe_env_deploy_request_allowed=false",
        "order_allowed=false"
    ) | Set-Content -LiteralPath $microLog -Encoding UTF8

    $strategyPacket = [pscustomobject]@{
        packetType = "STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_PACKET"
        status = "READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE"
        sourceRiskPosture = "REVIEW_ONLY_READY_NOT_LIVE_APPROVAL"
        sourceDataFreshnessCurrentClean = "true"
        sourceTinyLiveCanEnableProduction = "true"
        sourceTinyLiveHardStopDetected = "false"
        sourceTinyLiveFalsePositiveCount = "0"
        reviewEnvelope = [pscustomobject]@{ orderAllowed = $false; livePolicyChangeAllowed = $false }
    }
    @(
        ("strategy574_tiny_live_governance_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $strategyPacket)),
        "strategy574_tiny_live_governance_preflight_status=READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE",
        "strategy574_tiny_live_preflight_risk_posture=REVIEW_ONLY_READY_NOT_LIVE_APPROVAL",
        "tiny_live_can_enable_production=true",
        "order_allowed=false"
    ) | Set-Content -LiteralPath $strategyLog -Encoding UTF8

    $tpPacket = [pscustomobject]@{
        packetType = "TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_PACKET"
        status = "READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION"
        trailingStopAcceptance = "PASS"
        strategy485OcoHealthOk = "True"
        reviewEnvelope = [pscustomobject]@{ orderAllowed = $false; positionOrOcoMutationAllowed = $false }
    }
    @(
        ("tp_sl_oco_feasibility_preflight_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $tpPacket)),
        "tp_sl_oco_feasibility_preflight_status=READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION",
        "trailing_stop_acceptance=PASS",
        "strategy485_oco_health_ok=True"
    ) | Set-Content -LiteralPath $tpLog -Encoding UTF8

    @(
        "[live-review-packet-preflight] read-only evidence gate",
        "live_review_packet_allowed=true",
        "deploy_required_before_live_review=false",
        "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
        "packet_missing_requirements=[]",
        "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    ) | Set-Content -LiteralPath $liveLog -Encoding UTF8

    @(
        "Runtime Evidence Gate:",
        "  diagnosis=CANONICAL_SHADOW_READY",
        "Recent Evidence Window:",
        "  shadowIntentCount=5",
        "  orderSentEvidence=0",
        "  missing_runtime_evidence_fields=[]"
    ) | Set-Content -LiteralPath $runtimeLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -MicroProbeHandoffLogPath $microLog `
        -Strategy574TinyLivePreflightLogPath $strategyLog `
        -TpSlOcoPreflightLogPath $tpLog `
        -LiveReviewPacketLogPath $liveLog `
        -RuntimeEvidenceRcaLogPath $runtimeLog `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_preflight_review_packet=",
            '"packetType":"PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REVIEW_PACKET"',
            "profit_high_risk_micro_live_probe_preflight_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_EXACT_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "profit_high_risk_micro_live_probe_preflight_decision=REVIEW_EXACT_MICRO_PROBE_AUTHORIZATION_TEXT_WITH_OPERATOR_DO_NOT_DEPLOY",
            "source_micro_probe_handoff_status=READY_FOR_HIGH_RISK_MICRO_LIVE_PROBE_OPERATOR_REVIEW_NOT_MUTATION",
            "source_strategy574_tiny_live_preflight_status=READY_FOR_STRATEGY574_TINY_LIVE_GOVERNANCE_PREFLIGHT_REVIEW_NOT_LIVE",
            "source_tp_sl_oco_preflight_status=READY_FOR_TP_SL_OCO_FEASIBILITY_PREFLIGHT_REVIEW_NOT_MUTATION",
            "strategy574_tiny_live_risk_posture=REVIEW_ONLY_READY_NOT_LIVE_APPROVAL",
            "strategy574_data_freshness_current_clean=true",
            "tiny_live_can_enable_production=true",
            "tiny_live_hard_stop_detected=false",
            "tp_sl_oco_trailing_acceptance=PASS",
            "tp_sl_oco_health_ok=True",
            "live_review_packet_ready=true",
            "live_readiness_bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
            "runtime_evidence_ready=true",
            "runtime_evidence_diagnosis=CANONICAL_SHADOW_READY",
            "runtime_order_sent_evidence=0",
            "micro_probe_hard_gate_clear=true",
            "micro_probe_exact_authorization_review_allowed=true",
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
            "notAuthorization=read-only high-risk micro live probe preflight review packet only"
        )) {
        Assert-Contains -Name "ready high-risk micro live probe preflight replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready high-risk micro live probe preflight unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_high_risk_micro_live_probe_preflight_review_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.hardGateClear -or -not [bool]$packet.exactAuthorizationReviewAllowed) {
        throw "high-risk micro live probe preflight should clear hard gates for ready replay"
    }
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.envDeployRequestAllowed) {
        throw "high-risk micro live probe preflight packet must keep mutation flags false"
    }

    $blockedRuntimeLog = Join-Path $tempDir "runtime-blocked.log"
    @(
        "Runtime Evidence Gate:",
        "  diagnosis=CANONICAL_SHADOW_READY",
        "Recent Evidence Window:",
        "  shadowIntentCount=5",
        "  orderSentEvidence=1",
        "  missing_runtime_evidence_fields=[]"
    ) | Set-Content -LiteralPath $blockedRuntimeLog -Encoding UTF8
    $blockedOutput = & $scriptPath `
        -MicroProbeHandoffLogPath $microLog `
        -Strategy574TinyLivePreflightLogPath $strategyLog `
        -TpSlOcoPreflightLogPath $tpLog `
        -LiveReviewPacketLogPath $liveLog `
        -RuntimeEvidenceRcaLogPath $blockedRuntimeLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_high_risk_micro_live_probe_preflight_status=BLOCKED_HIGH_RISK_MICRO_LIVE_PROBE_PREFLIGHT_REQUIREMENTS_MISSING",
            "runtime_order_sent_evidence=1",
            "runtime evidence canonical shadow ready with orderSentEvidence=0",
            "runtime orderSentEvidence=0",
            "micro_probe_hard_gate_clear=false",
            "micro_probe_env_deploy_request_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked high-risk micro live probe preflight replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-high-risk-micro-live-probe-preflight-review-packet-test] OK"
