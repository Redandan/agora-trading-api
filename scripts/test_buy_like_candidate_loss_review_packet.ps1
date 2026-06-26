Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_buy_like_candidate_loss_review_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "[buy-like-candidate-loss-review-packet] read-only packet",
        "BUY_LIKE_CANDIDATE_LOSS_REVIEW_PACKET",
        "READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE",
        "buy_like_candidate_loss_review_packet",
        "buy_like_candidate_loss_14d_ranking",
        "buy_like_candidate_loss_30d_ranking",
        "buy_like_candidate_loss_14d_no_terminal_examples",
        "buy_like_candidate_loss_30d_no_terminal_examples",
        "noTerminalExamples",
        "NoTerminalContinuityLogPath",
        "no_terminal_continuity_status",
        "no_terminal_continuity_terminal_after_primary_rows",
        "noTerminalContinuity",
        "Review longer-window and interval-aware BUY-like continuity matching",
        "issue8_status",
        "issue12_status",
        "close_issue8_allowed=false",
        "close_issue6_or_7_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "read-only BUY-like candidate loss review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "BUY-like candidate loss packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("buy-like-loss-review-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    $buyLike14dLog = Join-Path $tempDir "buy-like-14d.log"
    $buyLike30dLog = Join-Path $tempDir "buy-like-30d.log"
    $dfLog = Join-Path $tempDir "df-readiness.log"
    $signalLog = Join-Path $tempDir "signal.log"
    $continuityLog = Join-Path $tempDir "no-terminal-continuity.log"

    Set-Content -LiteralPath $buyLike14dLog -Encoding UTF8 -Value @(
        "[buy-like-candidate-progression] read-only production DB evidence check",
        "symbol=BTCUSDT reviewDays=14 followupHours=6 limit=50 maxCandidateRows=1000",
        "  buy_like_candidate_rows=84",
        "  sampled_buy_like_candidate_rows=84",
        "  followup_terminal_event_rows=160",
        "  no_terminal_followup_rows=20",
        "  filter_block_followup_rows=0",
        "  entry_skip_followup_rows=64",
        "  signal_buy_rows=0",
        "  autotrade_followup_rows=0",
        "buy_like_followup_classification:",
        "  - ENTRY_SKIP:EntryDedup=49",
        "  - NO_TERMINAL_FOLLOWUP=20",
        "  - ENTRY_SKIP:DuplicateBar=10",
        "  - ENTRY_SKIP:ShadowExecutionIntent=5",
        "buy_like_candidate_type_distribution:",
        "  - event=SIGNAL_EVAL strategy=574 interval=1h count=46",
        "  - event=SIGNAL_EVAL strategy=508 interval=1h count=13",
        "NoTerminalFollowupExamples:",
        "  - candidateAuditId=14001 time=2026-06-20T01:00:00 strategy=508 interval=1h event=SIGNAL_EVAL classification=NO_TERMINAL_FOLLOWUP reason=BUY",
        "  - candidateAuditId=14002 time=2026-06-20T02:00:00 strategy=574 interval=1h event=SIGNAL_EVAL classification=NO_TERMINAL_FOLLOWUP reason=BUY",
        "  buy_like_candidate_progression_recommendation=BUY_LIKE_TO_ENTRY_SKIP_REVIEW"
    )
    Set-Content -LiteralPath $buyLike30dLog -Encoding UTF8 -Value @(
        "[buy-like-candidate-progression] read-only production DB evidence check",
        "symbol=BTCUSDT reviewDays=30 followupHours=6 limit=50 maxCandidateRows=1000",
        "  buy_like_candidate_rows=738",
        "  sampled_buy_like_candidate_rows=738",
        "  followup_terminal_event_rows=2028",
        "  no_terminal_followup_rows=119",
        "  filter_block_followup_rows=37",
        "  entry_skip_followup_rows=582",
        "  signal_buy_rows=0",
        "  autotrade_followup_rows=0",
        "buy_like_followup_classification:",
        "  - ENTRY_SKIP:EntryDedup=444",
        "  - NO_TERMINAL_FOLLOWUP=119",
        "  - ENTRY_SKIP:DuplicateBar=98",
        "  - ENTRY_SKIP:ShadowExecutionIntent=35",
        "  - FILTER_BLOCK:ExpectedValueGate=19",
        "  - FILTER_BLOCK:RegimeFilter=9",
        "  - FILTER_BLOCK:DataFreshnessGuard=5",
        "  - FILTER_BLOCK:EventRiskControl=4",
        "buy_like_candidate_type_distribution:",
        "  - event=SIGNAL_EVAL strategy=574 interval=1h count=213",
        "  - event=SIGNAL_EVAL strategy=575 interval=1d count=96",
        "NoTerminalFollowupExamples:",
        "  - candidateAuditId=30001 time=2026-06-19T01:00:00 strategy=575 interval=1d event=SIGNAL_EVAL classification=NO_TERMINAL_FOLLOWUP reason=BUY",
        "  - candidateAuditId=30002 time=2026-06-19T02:00:00 strategy=576 interval=1h event=SIGNAL_EVAL classification=NO_TERMINAL_FOLLOWUP reason=BUY",
        "  buy_like_candidate_progression_recommendation=BUY_LIKE_TO_ENTRY_SKIP_REVIEW"
    )
    Set-Content -LiteralPath $dfLog -Encoding UTF8 -Value @(
        "[data-freshness-replay-candidate-id] read-only packet",
        "data_freshness_rows_7d=0",
        "replay_candidate_id_rows=0",
        "latest_data_freshness_row_time=2026-06-14T15:38:16"
    )
    Set-Content -LiteralPath $signalLog -Encoding UTF8 -Value @(
        "DataFreshnessGuard Current Snapshot:",
        "  dataFreshnessCurrentStatus=NO_CURRENT_SAMPLE acceptance=PASS_NO_CURRENT_SAMPLE"
    )
    Set-Content -LiteralPath $continuityLog -Encoding UTF8 -Value @(
        "[no-terminal-followup-continuity] read-only production DB evidence check",
        "no_terminal_continuity_review_status=READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE",
        "buy_like_candidate_rows_sampled=738",
        "no_terminal_followup_rows=119",
        'no_terminal_continuity_classification=[{"classification":"TERMINAL_AFTER_PRIMARY_WINDOW","rows":79},{"classification":"SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL","rows":24},{"classification":"OTHER_TERMINAL_NEARBY","rows":15},{"classification":"PENDING_PRIMARY_FOLLOWUP_WINDOW","rows":1}]',
        'terminal_after_primary_window_distribution=[{"terminal":"ENTRY_SKIP:EntryDedup","rows":26},{"terminal":"FILTER_BLOCK:ExpectedValueGate","rows":22}]',
        "notAuthorization=read-only no-terminal continuity evidence only"
    )

    $output = & $scriptPath `
        -BuyLike14dLogPath $buyLike14dLog `
        -BuyLike30dLogPath $buyLike30dLog `
        -DataFreshnessReadinessLogPath $dfLog `
        -SignalCorrectnessLogPath $signalLog `
        -NoTerminalContinuityLogPath $continuityLog `
        -MaxAgeMinutes 1440 `
        -RequireReady 6>&1 2>&1
    $exitCode = if ($?) { 0 } else { 1 }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "BUY-like candidate loss packet failed temp evidence reuse:`n$text"
    }

    foreach ($marker in @(
            "buy_like_candidate_loss_review_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE",
            "buy_like_candidate_loss_dominant_blocker=ENTRY_SKIP:EntryDedup",
            "buy_like_candidate_loss_14d_rows=84",
            "buy_like_candidate_loss_14d_entry_dedup_rows=49",
            "buy_like_candidate_loss_14d_no_terminal_rows=20",
            "buy_like_candidate_loss_30d_rows=738",
            "buy_like_candidate_loss_30d_entry_dedup_rows=444",
            "buy_like_candidate_loss_30d_no_terminal_rows=119",
            "buy_like_candidate_loss_30d_filter_block_rows=37",
            "no_terminal_continuity_status=READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE",
            "no_terminal_continuity_terminal_after_primary_rows=79",
            "no_terminal_continuity_different_interval_rows=24",
            "no_terminal_continuity_other_nearby_terminal_rows=15",
            '"classification":"ENTRY_SKIP:EntryDedup","category":"ENTRY_SKIP","family":"EntryDedup","rows":444',
            '"classification":"NO_TERMINAL_FOLLOWUP","category":"NO_TERMINAL_FOLLOWUP","family":"NO_TERMINAL_FOLLOWUP","rows":119',
            '"noTerminalContinuity":{"status":"READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE"',
            '"terminalAfterPrimaryWindowDistribution":[{"terminal":"ENTRY_SKIP:EntryDedup","rows":26}',
            "issue12_next_evidence_target=Review longer-window and interval-aware BUY-like continuity matching before treating NO_TERMINAL_FOLLOWUP as a real pipeline gap; keep EntryDedup/DataFreshness/live policy unchanged.",
            'buy_like_candidate_loss_14d_no_terminal_examples=[{"candidateAuditId":14001',
            'buy_like_candidate_loss_30d_no_terminal_examples=[{"candidateAuditId":30001',
            '"noTerminalExamples":[{"candidateAuditId":30001',
            '"packetType":"BUY_LIKE_CANDIDATE_LOSS_REVIEW_PACKET"',
            '"status":"READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE"',
            '"status":"BLOCKED_NO_FRESH_DATAFRESHNESS_TERMINAL_ROWS"',
            '"closeIssue8Allowed":false',
            '"closeIssue12Allowed":true',
            '"closeIssue6Or7Allowed":false',
            '"livePolicyChangeAllowed":false',
            '"telegramSendAllowed":false',
            "close_issue12_allowed=true",
            "close_issue6_or_7_allowed=false",
            "notAuthorization=read-only BUY-like candidate loss review packet only"
        )) {
        Assert-Contains -Name "BUY-like candidate loss packet temp evidence reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }

    if ($text -match "child_start|remote command failed|deploy_ssh|gh issue|Could not resolve hostname|Permission denied") {
        throw "BUY-like candidate loss packet unexpectedly invoked external work:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[buy-like-candidate-loss-review-packet-test] OK"
