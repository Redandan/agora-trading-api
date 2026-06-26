Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_buy_like_continuity_matcher_review_packet.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "[buy-like-continuity-matcher-review-packet] read-only packet",
        "BUY_LIKE_CONTINUITY_MATCHER_REVIEW_PACKET",
        "READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE",
        "matcher_artifact_explained_rows",
        "matcher_artifact_explained_pct",
        "residual_potential_true_gap_rows",
        "EXTEND_PRIMARY_WINDOW_THEN_RECHECK_INTERVAL_LINKING",
        "matcher_policy_change_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "read-only BUY-like continuity matcher review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "matcher review packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("matcher-review-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    $lossLog = Join-Path $tempDir "loss.log"
    $continuityLog = Join-Path $tempDir "continuity.log"

    Set-Content -LiteralPath $lossLog -Encoding UTF8 -Value @(
        "[buy-like-candidate-loss-review-packet] read-only packet",
        "buy_like_candidate_loss_review_status=READY_FOR_BUY_LIKE_CANDIDATE_LOSS_OPERATOR_REVIEW_NOT_LIVE",
        "issue8_status=BLOCKED_NO_FRESH_DATAFRESHNESS_TERMINAL_ROWS",
        "issue12_next_evidence_target=Review longer-window and interval-aware BUY-like continuity matching before treating NO_TERMINAL_FOLLOWUP as a real pipeline gap; keep EntryDedup/DataFreshness/live policy unchanged."
    )
    Set-Content -LiteralPath $continuityLog -Encoding UTF8 -Value @(
        "[no-terminal-followup-continuity] read-only production DB evidence check",
        "no_terminal_continuity_review_status=READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE",
        "no_terminal_followup_rows=120",
        'no_terminal_continuity_classification=[{"classification":"TERMINAL_AFTER_PRIMARY_WINDOW","rows":79},{"classification":"SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL","rows":24},{"classification":"OTHER_TERMINAL_NEARBY","rows":15},{"classification":"PENDING_PRIMARY_FOLLOWUP_WINDOW","rows":1},{"classification":"NON_TERMINAL_SAME_KEY_CONTINUED","rows":1}]',
        'terminal_after_primary_window_distribution=[{"terminal":"ENTRY_SKIP:EntryDedup","rows":26},{"terminal":"FILTER_BLOCK:ExpectedValueGate","rows":22}]',
        "notAuthorization=read-only no-terminal continuity evidence only"
    )

    $output = & $scriptPath `
        -BuyLikeLossReviewLogPath $lossLog `
        -NoTerminalContinuityLogPath $continuityLog `
        -MaxAgeMinutes 1440 `
        -RequireReady 6>&1 2>&1
    $exitCode = if ($?) { 0 } else { 1 }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "BUY-like continuity matcher review packet failed temp evidence reuse:`n$text"
    }

    foreach ($marker in @(
            "buy_like_continuity_matcher_review_status=READY_FOR_BUY_LIKE_CONTINUITY_MATCHER_REVIEW_NOT_LIVE",
            "no_terminal_followup_rows=120",
            "matcher_artifact_explained_rows=118",
            "matcher_artifact_explained_pct=98.33",
            "residual_potential_true_gap_rows=2",
            "residual_potential_true_gap_pct=1.67",
            "terminal_after_primary_window_rows=79",
            "same_strategy_different_interval_rows=24",
            "other_terminal_nearby_rows=15",
            "pending_primary_window_rows=1",
            "non_terminal_same_key_continued_rows=1",
            "matcher_review_recommendation=EXTEND_PRIMARY_WINDOW_THEN_RECHECK_INTERVAL_LINKING",
            '"packetType":"BUY_LIKE_CONTINUITY_MATCHER_REVIEW_PACKET"',
            '"explainedAsMatcherArtifactRows":118',
            '"residualPotentialTrueGapRows":2',
            '"closeIssue8Allowed":false',
            '"matcherPolicyChangeAllowed":false',
            "close_issue8_allowed=false",
            "order_allowed=false",
            "notAuthorization=read-only BUY-like continuity matcher review packet only"
        )) {
        Assert-Contains -Name "matcher review packet temp evidence reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }

    if ($text -match "child_start|remote command failed|deploy_ssh|gh issue|Could not resolve hostname|Permission denied") {
        throw "Matcher review packet unexpectedly invoked external work:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[buy-like-continuity-matcher-review-packet-test] OK"
