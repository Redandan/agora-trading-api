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
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy508_trade_plan_quality_gate_review_packet.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_PACKET",
        "READY_FOR_STRATEGY508_TRADE_PLAN_QUALITY_GATE_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_REQUIREMENTS_MISSING",
        "PRESENT_EXACT_SET_STRATEGY_FLAGS_AUTHORIZATION_TEXT_TO_OPERATOR_DO_NOT_EXECUTE_FROM_PACKET",
        "tradePlanQualityGateEnabled=true",
        "tradePlanMinRiskReward",
        "tradePlanMaxStopLossPct",
        "setStrategyFlags(strategyId=",
        "operatorReviewReady",
        "strategyConfigMutationAllowed = `$false",
        "mcpWriteAllowed = `$false",
        "orderAllowed = `$false",
        "telegramSendAllowed = `$false",
        "strategy508_trade_plan_quality_gate_review_packet=",
        "strategy508_trade_plan_quality_gate_exact_mcp_call=",
        "strategy_config_mutation_allowed=false",
        "mcp_write_allowed=false",
        "read-only Strategy 508 TradePlanQualityGate review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "Strategy 508 TradePlanQualityGate packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "Strategy 508 TradePlanQualityGate review packet must not write files or invoke raw SSH/MCP/trading mutation calls"
}

Assert-Contains -Name "verify local runs Strategy 508 TradePlanQualityGate packet test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_strategy508_trade_plan_quality_gate_review_packet.ps1"
Assert-Contains -Name "README documents Strategy 508 TradePlanQualityGate packet" -Text (Get-Content -Raw -LiteralPath $readmePath) -Pattern "prepare_strategy508_trade_plan_quality_gate_review_packet.ps1"
Assert-Contains -Name "deploy runbook documents Strategy 508 TradePlanQualityGate packet" -Text (Get-Content -Raw -LiteralPath $runbookPath) -Pattern "prepare_strategy508_trade_plan_quality_gate_review_packet.ps1"
Assert-Contains -Name "split progress documents Strategy 508 TradePlanQualityGate packet" -Text (Get-Content -Raw -LiteralPath $progressPath) -Pattern "prepare_strategy508_trade_plan_quality_gate_review_packet.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy508-tradeplan-quality-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $readyLog = Join-Path $tempDir "ready.tsv"
    $blockedLog = Join-Path $tempDir "blocked.tsv"

    @(
        "section",
        "STRATEGY508_TRADEPLAN_ROWS",
        "audit_id`tevent_time_utc`tentry`ttp`tsl`ttp_pct`tsl_pct_actual`trr`tmin_rr`tmax_sl_pct`tforward_bars`tforward_max_up_pct`tforward_max_down_pct`tlatest_forward_close_ret_pct`ttp_touch`tsl_touch`treason",
        "77350`t2026-07-09T09:00:07`t62897.80000000`t66671.67000000`t55350.06000000`t6.0000`t12.0000`t0.49999975`t1.00000000`t0.08000000`t0`tNULL`tNULL`tNULL`tTP_NOT_TOUCHED`tSL_NOT_TOUCHED`trisk_reward_below_min+stop_loss_above_max: riskReward=0.50 < min 1.00 or stopLoss=12.00% > max 8.00%; wickAware=true/ULTRA_LOW_DISASTER",
        "77324`t2026-07-08T14:00:04`t61948.60000000`t65665.52000000`t54514.77000000`t6.0000`t12.0000`t0.50000013`t1.00000000`t0.08000000`t18`t2.1569`t-0.6460`t1.5322`tTP_NOT_TOUCHED`tSL_NOT_TOUCHED`trisk_reward_below_min+stop_loss_above_max: riskReward=0.50 < min 1.00 or stopLoss=12.00% > max 8.00%; wickAware=true/ULTRA_LOW_DISASTER"
    ) | Set-Content -LiteralPath $readyLog -Encoding UTF8

    $readyOutput = & $scriptPath -EvidenceLogPath $readyLog -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "strategy508_trade_plan_quality_gate_review_packet=",
            '"packetType":"STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_PACKET"',
            "strategy508_trade_plan_quality_gate_review_status=READY_FOR_STRATEGY508_TRADE_PLAN_QUALITY_GATE_OPERATOR_REVIEW_NOT_MUTATION",
            "strategy508_trade_plan_quality_gate_operator_review_ready=true",
            "strategy508_trade_plan_quality_gate_reviewed_rows=2",
            "strategy508_trade_plan_quality_gate_reviewable_rows=2",
            "strategy508_trade_plan_quality_gate_observed_min_rr=0.49999975",
            "strategy508_trade_plan_quality_gate_observed_max_sl_pct=0.1200",
            "strategy508_trade_plan_quality_gate_proposed_min_rr=0.49",
            "strategy508_trade_plan_quality_gate_proposed_max_sl_pct=0.121",
            "setStrategyFlags(strategyId=508, tradePlanQualityGateEnabled=true, tradePlanMinRiskReward=0.49, tradePlanMaxStopLossPct=0.121",
            "strategy508_trade_plan_quality_gate_missing_requirements=[]",
            "strategy_config_mutation_allowed=false",
            "mcp_write_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "restart_allowed=false",
            "live_policy_change_allowed=false",
            "entry_dedup_policy_change_allowed=false",
            "ev_policy_change_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "exchange_mutation_allowed=false",
            "notAuthorization=read-only Strategy 508 TradePlanQualityGate review packet only"
        )) {
        Assert-Contains -Name "ready Strategy 508 TradePlanQualityGate packet replay" -Text $readyText -Pattern ([regex]::Escape($marker))
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "strategy508_trade_plan_quality_gate_review_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.operatorReviewReady) {
        throw "TradePlanQualityGate packet should be operator-review-ready for ready replay"
    }
    if ([bool]$packet.strategyConfigMutationAllowed -or [bool]$packet.mcpWriteAllowed -or [bool]$packet.orderAllowed) {
        throw "TradePlanQualityGate packet must keep mutation flags false"
    }

    @(
        "audit_id`tevent_time_utc`tentry`ttp`tsl`ttp_pct`tsl_pct_actual`trr`tmin_rr`tmax_sl_pct`tforward_bars`tforward_max_up_pct`tforward_max_down_pct`tlatest_forward_close_ret_pct`ttp_touch`tsl_touch`treason",
        "77350`t2026-07-09T09:00:07`t62897.80000000`t66671.67000000`t55350.06000000`t6.0000`t16.0000`t0.37500000`t1.00000000`t0.08000000`t0`tNULL`tNULL`tNULL`tTP_NOT_TOUCHED`tSL_NOT_TOUCHED`trisk_reward_below_min+stop_loss_above_max"
    ) | Set-Content -LiteralPath $blockedLog -Encoding UTF8

    $blockedOutput = & $scriptPath -EvidenceLogPath $blockedLog *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "strategy508_trade_plan_quality_gate_review_status=BLOCKED_STRATEGY508_TRADE_PLAN_QUALITY_GATE_REVIEW_REQUIREMENTS_MISSING",
            "strategy508_trade_plan_quality_gate_operator_review_ready=false",
            "proposed min R:R allows the reviewed Strategy 508 +6/-12 samples",
            "proposed max SL allows the reviewed Strategy 508 disaster-stop samples",
            "strategy_config_mutation_allowed=false",
            "mcp_write_allowed=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked Strategy 508 TradePlanQualityGate packet replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[strategy508-trade-plan-quality-gate-review-packet-test] OK"
