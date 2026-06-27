Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_panic_bottom_missed_rebound_rca_packet.ps1"
$smokePath = Join-Path $PSScriptRoot "smoke_panic_bottom_context_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$smokeText = Get-Content -Raw -LiteralPath $smokePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[panic-bottom-missed-rebound-rca-packet] read-only packet",
        "PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET",
        "READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE",
        "panic_bottom_missed_rebound_rca_packet",
        "panic_bottom_missed_rebound_rca_status",
        "blockerLayerClassification",
        "signal_threshold",
        "buy_like_continuity",
        "entry_dedup_data_freshness_filter",
        "oco_preflight_and_trend_guard",
        "execution_live_boundary",
        "REVIEW_PANIC_BOTTOM_THRESHOLD_GAP_NOT_LIVE",
        "REVIEW_SCOUT_PRE_POSITION_EVIDENCE_NOT_LIVE",
        "WAIT_FOR_BUY_LIKE_CANDIDATE",
        "BLOCKED_OCO_PREFLIGHT",
        "KEEP_STRATEGY574_THRESHOLD_RELAXATION_BLOCKED",
        "strategy574_threshold_relaxation_allowed=false",
        "order_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "grid_mutation_allowed=false",
        "telegram_send_allowed=false",
        "scheduler_enablement_allowed=false",
        "notAuthorization=read-only panic-bottom missed rebound RCA packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "panic-bottom RCA packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "[panic-bottom-context] read-only server-local MCP smoke",
        "previewPanicBottomContext",
        "server-local /api/mcp only",
        "http://127.0.0.1:{os.environ['PORT']}/api/mcp",
        "panic_bottom_context_score",
        "panic_bottom_context_suggested_action",
        "panic_bottom_context_price_vs_200wma_pct",
        "panic_bottom_context_status=",
        "READY_FOR_PANIC_BOTTOM_CONTEXT_REVIEW_NOT_LIVE",
        "order_allowed=",
        "grid_mutation_allowed=",
        "oco_mutation_allowed=",
        "telegram_send_allowed=",
        "runtime_evidence_write_allowed=",
        "notAuthorization=read-only panic-bottom context smoke only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe"
    )) {
    Assert-Contains -Name "panic-bottom context smoke marker" -Text $smokeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_panic_bottom_missed_rebound_rca_packet.ps1",
        "smoke_panic_bottom_context_ssh.ps1",
        "PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET",
        "panic_bottom_missed_rebound_rca_status",
        "READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE",
        "blocker-layer classification",
        "does not authorize live trading"
    )) {
    Assert-Contains -Name "operator docs mention panic-bottom RCA packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("panic-bottom-rca-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$profitLog = Join-Path $tempDir "profit.log"
$signalLog = Join-Path $tempDir "signal.log"
$buyLikeLog = Join-Path $tempDir "buy-like.log"
$panicLog = Join-Path $tempDir "panic.log"
$nearLog = Join-Path $tempDir "near-threshold.log"
try {
    Set-Content -LiteralPath $profitLog -Encoding UTF8 -Value @(
        "[profit-candidate-review] read-only production MCP check",
        "scope=READ_ONLY",
        "  monthlyPnlTotalUsdt=-28.08",
        "  expectedValueGateAcceptance=REVIEW",
        "  missedOpportunityStatus=WARN",
        "  falseBlockRiskCount=134",
        "  suspiciousNoBuyCount=134",
        '  missedOpportunity={"overallStatus":"WARN","highForwardReturnNoBuyCount":134}',
        "  nearBuyTruthTableRows=1",
        "  noBuyTruthTable=WATCH_SIGNAL_NEAR_BUY_THRESHOLD blockers=[NO_CURRENT_BUY_CANDIDATE, OCO_PREFLIGHT_FAILED]",
        "  profit_candidate_review_recommendation=REVIEW_MISSED_OPPORTUNITY_HOLD_ROWS",
        "  notAuthorization=read-only evidence only"
    )
    Set-Content -LiteralPath $signalLog -Encoding UTF8 -Value @(
        "[signal-eval-no-buy-generation] read-only production DB evidence check",
        "scope=READ_ONLY",
        "Signal Eval No-Buy Generation Summary:",
        "  signal_eval_rows=561",
        "  buy_like_signal_eval_rows=3",
        "  no_buy_signal_eval_rows=558",
        "  execution_hold_rows=558",
        "signal_eval_hold_reason_distribution:",
        "  - no_threshold_hit=347",
        "signal_eval_threshold_gap_distribution:",
        "  - strategy=574 interval=1h indicator=market_entropy_index count=35 avg_mih_value=69.0000 avg_buy_threshold=70.0000 avg_buy_gap=1.0000 min_buy_gap=1.0000",
        "Conclusion:",
        "  signal_eval_no_buy_generation_recommendation=BUY_LIKE_SIGNAL_EVAL_PRESENT_REVIEW_PROGRESS_PATH",
        "  notAuthorization=read-only evidence only"
    )
    Set-Content -LiteralPath $buyLikeLog -Encoding UTF8 -Value @(
        "[buy-like-candidate-progression] read-only production DB evidence check",
        "scope=READ_ONLY",
        "Buy-like Candidate Progression Summary:",
        "  buy_like_candidate_rows=3",
        "  no_terminal_followup_rows=2",
        "  filter_block_followup_rows=0",
        "  entry_skip_followup_rows=1",
        "  signal_buy_rows=0",
        "  autotrade_followup_rows=0",
        "buy_like_followup_classification:",
        "  - NO_TERMINAL_FOLLOWUP=2",
        "  - ENTRY_SKIP:EntryDedup=1",
        "Conclusion:",
        "  buy_like_candidate_progression_recommendation=BUY_LIKE_PIPELINE_MIXED_REVIEW",
        "  notAuthorization=read-only evidence only"
    )
    Set-Content -LiteralPath $panicLog -Encoding UTF8 -Value @(
        "[panic-bottom-context] read-only server-local MCP smoke",
        "scope=READ_ONLY",
        "panic_bottom_context_boundary=READ_ONLY",
        "panic_bottom_context_score=76",
        "panic_bottom_context_phase=PANIC_BOTTOM_CANDIDATE",
        "panic_bottom_context_suggested_action=SCOUT_PRE_POSITION",
        "panic_bottom_context_confirmed_deploy_blocked=true",
        "panic_bottom_context_confirmed_deploy_block_reason=OCO_ABNORMAL_OR_1H_4H_TRENDING_BEARISH",
        "panic_bottom_context_down_wave_count=3",
        "panic_bottom_context_largest_drawdown_pct=-28.5",
        "panic_bottom_context_retest_low_status=RETESTING_LOW",
        "panic_bottom_context_fear_greed_latest_value=18",
        "panic_bottom_context_fear_greed_classification=EXTREME_FEAR",
        "panic_bottom_context_fear_greed_freshness=FRESH",
        "panic_bottom_context_price_vs_200wma_pct=8.2",
        "panic_bottom_context_1h_trend_status=TRENDING_BEARISH",
        "panic_bottom_context_4h_trend_status=NOT_TRENDING_BEARISH",
        "panic_bottom_context_oco_guard_status=ABNORMAL",
        "order_allowed=false",
        "grid_mutation_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "runtime_evidence_write_allowed=false"
    )
    Set-Content -LiteralPath $nearLog -Encoding UTF8 -Value @(
        "[strategy574-near-threshold-shadow-observation] read-only production DB smoke",
        "scope=READ_ONLY",
        "near_threshold_rows=30",
        "false_positive_rate_pct=93.33",
        "avg_forward_return_pct=-2.0671",
        "avg_net_return_pct=-0.6667",
        "strategy574_near_threshold_shadow_recommendation=STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH",
        "notAuthorization=read-only evidence only"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for panic-bottom RCA packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -ProfitCandidateLogPath $profitLog `
            -SignalEvalNoBuyLogPath $signalLog `
            -BuyLikeProgressionLogPath $buyLikeLog `
            -PanicBottomContextLogPath $panicLog `
            -Strategy574NearThresholdShadowLogPath $nearLog `
            -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "panic-bottom missed rebound RCA packet failed fixture run:`n$text"
    }
    foreach ($marker in @(
            "source_profit_candidate_log_freshness=FRESH",
            "source_signal_eval_no_buy_log_freshness=FRESH",
            "source_buy_like_progression_log_freshness=FRESH",
            "source_panic_bottom_context_log_freshness=FRESH",
            "panic_bottom_context_boundary=READ_ONLY",
            "panic_bottom_score=76",
            "panic_bottom_suggested_action=SCOUT_PRE_POSITION",
            "missed_opportunity_status=WARN",
            "high_forward_return_no_buy_count=134",
            "strategy574_min_buy_gap=1.0000",
            "strategy574_threshold_near_miss=true",
            "strategy574_near_threshold_shadow_recommendation=STRATEGY574_NEAR_THRESHOLD_FALSE_POSITIVE_RISK_HIGH",
            "strategy574_threshold_relaxation_allowed=false",
            "buy_like_candidate_rows=3",
            "no_terminal_followup_rows=2",
            "entry_skip_followup_rows=1",
            "entry_dedup_followup=true",
            "oco_preflight_blocked=true",
            "panic_bottom_missed_rebound_primary_root_cause=SIGNAL_THRESHOLD_NEAR_MISS_WITH_OCO_OR_TREND_GUARD_BLOCKER",
            "REVIEW_PANIC_BOTTOM_THRESHOLD_GAP_NOT_LIVE",
            "REVIEW_SCOUT_PRE_POSITION_EVIDENCE_NOT_LIVE",
            "BLOCKED_OCO_PREFLIGHT",
            "KEEP_STRATEGY574_THRESHOLD_RELAXATION_BLOCKED",
            '"packetType":"PANIC_BOTTOM_MISSED_REBOUND_RCA_PACKET"',
            '"status":"READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE"',
            '"blockerLayerClassification"',
            '"orderAllowed":false',
            '"strategyThresholdChangeAllowed":false',
            "panic_bottom_missed_rebound_rca_status=READY_FOR_PANIC_BOTTOM_MISSED_REBOUND_RCA_REVIEW_NOT_LIVE",
            "notAuthorization=read-only panic-bottom missed rebound RCA packet only"
        )) {
        Assert-Contains -Name "panic-bottom RCA temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "panic-bottom RCA packet unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[panic-bottom-missed-rebound-rca-packet-test] OK"
