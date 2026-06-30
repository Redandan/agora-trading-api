Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsBeforeSsh {
    param([string[]]$Arguments, [string]$ExpectedPattern)

    $script = Join-Path $PSScriptRoot "prepare_no_buy_attention_flow_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for no-buy attention flow review packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "No-buy attention flow review packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "No-buy attention flow review packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "No-buy attention flow review packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_no_buy_attention_flow_review_packet_ssh.ps1"
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
        "[no-buy-attention-flow-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_data_freshness_profit_blocker_brief_ssh.ps1",
        "smoke_attention_hit_progression_ssh.ps1",
        "smoke_signal_eval_no_buy_generation_ssh.ps1",
        "smoke_buy_like_candidate_progression_ssh.ps1",
        "NO_BUY_ATTENTION_FLOW_REVIEW_PACKET",
        "no_buy_attention_flow_review_packet",
        "no_buy_attention_flow_review_status",
        "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE",
        "PENDING_BUY_LIKE_CANDIDATES",
        "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW",
        "ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES",
        "SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW",
        "NO_ATTENTION_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE",
        "data_freshness_sample_gap_rca_recommendation",
        "sample_gap_buy_like_rows_",
        "sample_gap_attention_hit_rows_",
        "attention_hit_progression_recommendation",
        "signal_eval_no_buy_generation_recommendation",
        "signal_eval_rows",
        "signal_eval_v2_context_rows",
        "signal_eval_strategy_decision_context_rows",
        "SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT",
        "SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW",
        "signal_eval_threshold_gap_count",
        "signal_eval_near_threshold_gap_count",
        "signal_eval_closest_threshold_gap_strategy",
        "signal_eval_threshold_gap_distribution",
        "thresholdGapDistribution",
        "nearThresholdGapCount",
        "closestThresholdGap",
        "buy_like_candidate_progression_recommendation",
        "attention_no_terminal_followup_rows",
        "attention_macro_watch_only_rows",
        "attention_strategy_scoped_rows",
        "attention_strategy_scoped_no_terminal_followup_rows",
        "attention_strategy_scoped_entry_skip_followup_rows",
        "attention_strategy_scoped_recommendation",
        "attention_candidate_interpretation",
        "ATTENTION_HITS_ARE_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES",
        "ATTENTION_HITS_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES",
        "ATTENTION_HITS_MIXED_MACRO_AND_STRATEGY_ROWS",
        "STRATEGY_SCOPED_ATTENTION_NO_TERMINAL_FOLLOWUP_REVIEW",
        "attention_strategy_distribution",
        "buy_like_candidate_rows",
        "notAuthorization=read-only no-buy attention flow review packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "no-buy attention flow review marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "No-buy attention flow review packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_no_buy_attention_flow_review_packet_ssh.ps1",
        "NO_BUY_ATTENTION_FLOW_REVIEW_PACKET",
        "no_buy_attention_flow_review_status",
        "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE",
        "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW",
        "ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES",
        "SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW",
        "signal_eval_threshold_gap_distribution",
        "signal_eval_near_threshold_gap_count",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention no-buy attention flow packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-FollowupHours", "49") `
    -ExpectedPattern "FollowupHours must be between 1 and 48"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxRows", "0") `
    -ExpectedPattern "MaxRows must be between 1 and 2000"

Write-Host "[no-buy-attention-flow-review-packet-test] OK"
