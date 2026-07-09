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
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy508_min_notional_floor_activation_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_PACKET",
        "READY_FOR_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_DEPLOY_ENV_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
        "REFRESH_STRATEGY508_FIRST_ENTRY_READINESS_BEFORE_AUTHORIZATION",
        "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=true",
        "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT",
        "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=false",
        "activationAuthorizationReviewReady",
        "activationExecutionAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "restartAllowed = `$false",
        "orderAllowed = `$false",
        "telegramSendAllowed = `$false",
        "exchangeMutationAllowed = `$false",
        "strategy508_min_notional_floor_activation_packet=",
        "strategy508_min_notional_floor_activation_review_ready=",
        "strategy508_min_notional_floor_activation_authorization_text=",
        "activation_execution_allowed=false",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "restart_allowed=false",
        "order_allowed=false",
        "read-only Strategy 508 min-notional floor activation packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "Strategy 508 min-notional floor activation packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "Strategy 508 min-notional floor activation packet must not write files or invoke raw SSH/MCP/trading mutation calls"
}

Assert-Contains -Name "verify local runs Strategy 508 min-notional floor activation packet test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_strategy508_min_notional_floor_activation_packet.ps1"
Assert-Contains -Name "README documents Strategy 508 min-notional floor activation packet" -Text (Get-Content -Raw -LiteralPath $readmePath) -Pattern "prepare_strategy508_min_notional_floor_activation_packet.ps1"
Assert-Contains -Name "deploy runbook documents Strategy 508 min-notional floor activation packet" -Text (Get-Content -Raw -LiteralPath $runbookPath) -Pattern "prepare_strategy508_min_notional_floor_activation_packet.ps1"
Assert-Contains -Name "split progress documents Strategy 508 min-notional floor activation packet" -Text (Get-Content -Raw -LiteralPath $progressPath) -Pattern "prepare_strategy508_min_notional_floor_activation_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy508-min-notional-floor-activation-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $readyLog = Join-Path $tempDir "ready.log"
    $blockedLog = Join-Path $tempDir "blocked.log"

    @(
        "[strategy508-first-entry-readiness] read-only production evidence check",
        "  strategy508_signal_source_gate=LEGACY_LIVE_EVALUATOR_ACTIVE_FOR_STRATEGY",
        "  entry_dedup_first_entry_pass=true",
        "  auto_trade_open_position_gate=PASS",
        "  latest_signal_status=LATEST_SIGNAL_BLOCKED_POSITION_SIZING",
        "  latest_signal_filter_reason=AutoTrade: risk-sized notional 20.83 below min 50.00; skip live entry",
        "  latest_ev_gate_applies_to_latest_signal=false",
        "  latest_ev_gate_status=STALE_BLOCK_EXPECTED_R_BELOW_MIN",
        "  position_sizing_min_notional_floor_enabled=false",
        "  position_sizing_min_notional_floor_max_risk_usdt=6.25",
        "  first_entry_position_sizing_status=BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL",
        "  first_entry_position_sizing_lines=[""slDistancePct=12.00%, riskBudgetUsdt=2.50"",""reason=risk-sized notional 20.83 below min 50.00; below_min_notional_skip""]",
        "  strategy508_first_entry_blockers=[""BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL"",""LATEST_SIGNAL_BLOCKED_POSITION_SIZING""]",
        "  strategy508_first_entry_conclusion=FIRST_ENTRY_BLOCKED_REVIEW_REQUIRED"
    ) | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -EvidenceLogPath $readyLog -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "strategy508_min_notional_floor_activation_packet=",
            '"packetType":"STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_PACKET"',
            "strategy508_min_notional_floor_activation_status=READY_FOR_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
            "strategy508_min_notional_floor_activation_decision=PRESENT_EXACT_DEPLOY_ENV_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
            "strategy508_min_notional_floor_activation_review_ready=true",
            "strategy508_min_notional_floor_signal_source_gate=LEGACY_LIVE_EVALUATOR_ACTIVE_FOR_STRATEGY",
            "strategy508_min_notional_floor_entry_dedup_first_entry_pass=true",
            "strategy508_min_notional_floor_auto_trade_open_position_gate=PASS",
            "strategy508_min_notional_floor_latest_ev_gate_status=STALE_BLOCK_EXPECTED_R_BELOW_MIN",
            "strategy508_min_notional_floor_first_entry_position_sizing_status=BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL",
            "strategy508_min_notional_floor_raw_risk_sized_usdt=20.83",
            "strategy508_min_notional_floor_min_notional_usdt=50.00",
            "strategy508_min_notional_floor_sl_distance_pct=12.00",
            "strategy508_min_notional_floor_estimated_floor_risk_usdt=6.0000",
            "strategy508_min_notional_floor_max_risk_usdt=6.25",
            "strategy508_min_notional_floor_activation_env_diff=TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=true;TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT=6.25",
            "strategy508_min_notional_floor_rollback_env_diff=TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED=false",
            "strategy508_min_notional_floor_activation_missing_requirements=[]",
            "activation_execution_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "restart_allowed=false",
            "live_policy_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "exchange_mutation_allowed=false",
            "notAuthorization=read-only Strategy 508 min-notional floor activation packet only"
        )) {
        Assert-Contains -Name "ready Strategy 508 min-notional floor activation replay" -Text $readyText -Pattern ([regex]::Escape($marker))
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready min-notional floor activation packet unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "strategy508_min_notional_floor_activation_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.activationAuthorizationReviewReady) {
        throw "min-notional floor activation packet should be review-ready for ready replay"
    }
    if ([bool]$packet.activationExecutionAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.restartAllowed -or [bool]$packet.orderAllowed) {
        throw "min-notional floor activation packet must keep mutation flags false"
    }
    if ([decimal]$packet.floorSizingEvidence.estimatedFloorRiskUsdt -ne [decimal]6.0000) {
        throw "min-notional floor activation packet estimated floor risk mismatch"
    }

    @(
        "[strategy508-first-entry-readiness] read-only production evidence check",
        "  strategy508_signal_source_gate=LEGACY_LIVE_EVALUATOR_ACTIVE_FOR_STRATEGY",
        "  entry_dedup_first_entry_pass=true",
        "  auto_trade_open_position_gate=PASS",
        "  latest_signal_status=LATEST_SIGNAL_BLOCKED_POSITION_SIZING",
        "  latest_signal_filter_reason=AutoTrade: risk-sized notional 20.83 below min 50.00; skip live entry",
        "  latest_ev_gate_applies_to_latest_signal=false",
        "  latest_ev_gate_status=STALE_BLOCK_EXPECTED_R_BELOW_MIN",
        "  position_sizing_min_notional_floor_enabled=false",
        "  position_sizing_min_notional_floor_max_risk_usdt=6.25",
        "  first_entry_position_sizing_status=BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL",
        "  first_entry_position_sizing_lines=[""slDistancePct=13.00%, riskBudgetUsdt=2.50"",""reason=risk-sized notional 20.83 below min 50.00; below_min_notional_skip""]",
        "  strategy508_first_entry_blockers=[""BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL"",""LATEST_SIGNAL_BLOCKED_POSITION_SIZING""]",
        "  strategy508_first_entry_conclusion=FIRST_ENTRY_BLOCKED_REVIEW_REQUIRED"
    ) | Set-Content -LiteralPath $blockedLog -Encoding UTF8

    $blockedOutput = & $scriptPath -EvidenceLogPath $blockedLog *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "strategy508_min_notional_floor_activation_status=BLOCKED_STRATEGY508_MIN_NOTIONAL_FLOOR_ACTIVATION_REQUIREMENTS_MISSING",
            "strategy508_min_notional_floor_activation_decision=REFRESH_STRATEGY508_FIRST_ENTRY_READINESS_BEFORE_AUTHORIZATION",
            "strategy508_min_notional_floor_activation_review_ready=false",
            "strategy508_min_notional_floor_estimated_floor_risk_usdt=6.5000",
            "floor-sized SL risk <= max risk cap",
            "activation_execution_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "restart_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked Strategy 508 min-notional floor activation replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[strategy508-min-notional-floor-activation-packet-test] OK"
