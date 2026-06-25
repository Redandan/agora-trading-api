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

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for BUY-like candidate loss packet test." }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -BuyLike14dLogPath $buyLike14dLog `
        -BuyLike30dLogPath $buyLike30dLog `
        -DataFreshnessReadinessLogPath $dfLog `
        -SignalCorrectnessLogPath $signalLog `
        -MaxAgeMinutes 1440 `
        -RequireReady 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
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
            '"classification":"ENTRY_SKIP:EntryDedup","category":"ENTRY_SKIP","family":"EntryDedup","rows":444',
            '"classification":"NO_TERMINAL_FOLLOWUP","category":"NO_TERMINAL_FOLLOWUP","family":"NO_TERMINAL_FOLLOWUP","rows":119',
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
