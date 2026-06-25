Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_entry_dedup_shadow_operator_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "[entry-dedup-shadow-operator-packet] read-only packet",
        "scope=READ_ONLY",
        "ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_PACKET",
        "READY_FOR_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_REVIEW_NOT_LIVE",
        "entry_dedup_shadow_operator_packet",
        "entry_dedup_shadow_operator_packet_status",
        "strategy574_signal_review_gate_status",
        "data_freshness_current_status",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only EntryDedup/ShadowExecutionIntent operator packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "EntryDedup ShadowExecutionIntent packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("entry-dedup-shadow-operator-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

$candidateFlowLog = Join-Path $tempDir "candidate-flow.log"
$buyLikeLog = Join-Path $tempDir "buy-like.log"
$entryDedupLog = Join-Path $tempDir "entry-dedup.log"
$strategy574Log = Join-Path $tempDir "strategy574.log"
$signalCorrectnessLog = Join-Path $tempDir "signal-correctness.log"

try {
    Set-Content -LiteralPath $candidateFlowLog -Encoding UTF8 -Value @(
        "[profit-candidate-flow-review-packet] read-only packet",
        "profit_candidate_flow_review_status=READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE",
        "profit_candidate_flow_next_action=Run EntryDedup/ShadowExecutionIntent row-level review and TP/SL/OCO shadow feasibility; do not relax EntryDedup/DataFreshness/live policy."
    )
    Set-Content -LiteralPath $buyLikeLog -Encoding UTF8 -Value @(
        "[buy-like-candidate-progression] read-only production DB evidence check",
        "  buy_like_candidate_rows=84",
        "  entry_skip_followup_rows=64",
        "  filter_block_followup_rows=0",
        "  signal_buy_rows=0",
        "  autotrade_followup_rows=0",
        "buy_like_followup_classification:",
        "  - ENTRY_SKIP:EntryDedup=49",
        "  - NO_TERMINAL_FOLLOWUP=20",
        "  - ENTRY_SKIP:DuplicateBar=10",
        "  - ENTRY_SKIP:ShadowExecutionIntent=5",
        "  buy_like_candidate_progression_recommendation=BUY_LIKE_TO_ENTRY_SKIP_REVIEW"
    )
    Set-Content -LiteralPath $entryDedupLog -Encoding UTF8 -Value @(
        "[entry-dedup-operator-decision-brief] read-only brief",
        "entry_dedup_operator_primary_recommendation=PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_REVIEW",
        "entry_dedup_skip_rows=11",
        "positive_24h_rows=10",
        "negative_24h_rows=1",
        "avg_24h_return_pct=1.0173",
        "tp_hit_rows=11",
        "sl_hit_rows=0",
        "ambiguous_same_bar_rows=0",
        "avg_net_return_pct=0.8",
        "entry_dedup_operator_decision_brief_status=READY_FOR_ENTRY_DEDUP_OPERATOR_DECISION_NOT_LIVE"
    )
    Set-Content -LiteralPath $strategy574Log -Encoding UTF8 -Value @(
        "[strategy574-signal-review-gate] read-only evidence gate",
        "strategy574_near_buy=true",
        "data_freshness_current_clean=false",
        "strategy574_policy_change_recommendation=DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
        'strategy574_review_missing_requirements=["current DataFreshness clean","current BUY candidate","OCO preflight pass"]',
        "strategy574_signal_review_gate_status=BLOCKED_FIX_CURRENT_DATA_FRESHNESS"
    )
    Set-Content -LiteralPath $signalCorrectnessLog -Encoding UTF8 -Value @(
        "DataFreshnessGuard Current Snapshot:",
        "  dataFreshnessCurrentStatus=NO_CURRENT_SAMPLE acceptance=PASS_NO_CURRENT_SAMPLE readyNowKeys=N/A staleNowKeys=N/A noDataNowKeys=N/A queryFailedNowKeys=N/A"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for EntryDedup shadow operator packet test." }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -CandidateFlowLogPath $candidateFlowLog `
        -BuyLikeProgressionLogPath $buyLikeLog `
        -EntryDedupDecisionLogPath $entryDedupLog `
        -Strategy574GateLogPath $strategy574Log `
        -SignalCorrectnessLogPath $signalCorrectnessLog `
        -MaxAgeMinutes 1440 `
        -RequireReady 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "EntryDedup shadow operator packet failed temp evidence reuse:`n$text"
    }

    foreach ($marker in @(
            "entry_dedup_shadow_operator_packet_status=READY_FOR_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_REVIEW_NOT_LIVE",
            "buy_like_candidate_rows=84",
            "entry_skip_followup_rows=64",
            '"blockerFamily":"ENTRY_SKIP:EntryDedup","rows":49',
            '"blockerFamily":"ENTRY_SKIP:ShadowExecutionIntent","rows":5',
            "entry_dedup_skip_rows=11",
            "entry_dedup_positive_24h_rows=10",
            "entry_dedup_tp_hit_rows=11",
            "entry_dedup_sl_hit_rows=0",
            "strategy574_signal_review_gate_status=BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
            "strategy574_policy_change_recommendation=DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
            "data_freshness_current_status=NO_CURRENT_SAMPLE",
            '"packetType":"ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_PACKET"',
            '"status":"READY_FOR_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_OPERATOR_REVIEW_NOT_LIVE"',
            '"primaryDecision":"PREPARE_SEPARATE_ENTRY_DEDUP_SHADOW_EXECUTION_INTENT_REVIEW"',
            '"entryDedupPolicyChangeAllowed":false',
            '"dataFreshnessPolicyChangeAllowed":false',
            '"schedulerEnablementAllowed":false',
            '"telegramSendAllowed":false',
            "entry_dedup_policy_change_allowed=false",
            "data_freshness_policy_change_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only EntryDedup/ShadowExecutionIntent operator packet only"
        )) {
        Assert-Contains -Name "EntryDedup shadow operator packet temp evidence reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }

    if ($text -match "child_start|remote command failed|deploy_ssh|gh issue|Could not resolve hostname|Permission denied") {
        throw "EntryDedup shadow operator packet unexpectedly invoked external work:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[entry-dedup-shadow-operator-packet-test] OK"
