Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-ScriptFailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern,
        [string]$Description
    )

    $scriptPath = Join-Path $PSScriptRoot "smoke_buy_like_candidate_progression_ssh.ps1"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "$Description unexpectedly succeeded"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "$Description did not fail with expected pattern '$ExpectedPattern'. Output: $text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_buy_like_candidate_progression_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "bt_decision_audit",
        "ATTENTION_HIT",
        "excluded_terminal_candidate_event=ENTRY_SKIP",
        "SIGNAL_BUY",
        "FILTER_BLOCK",
        "ENTRY_SKIP",
        "AUTOTRADE_OK",
        "AUTOTRADE_FAIL",
        "NO_TERMINAL_FOLLOWUP",
        "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW",
        "BUY_LIKE_NO_TERMINAL_FOLLOWUP_DOMINATES",
        "BUY_LIKE_TO_ENTRY_SKIP_REVIEW",
        "BUY_LIKE_TO_FILTER_BLOCK_REVIEW",
        "BUY_LIKE_PIPELINE_HAS_TERMINAL_FOLLOWUP",
        "buy_like_candidate_progression_recommendation",
        "buy_like_followup_classification",
        "buy_like_followup_classification_by_strategy_interval",
        "buy_like_followup_event_types",
        "buy_like_candidate_type_distribution",
        "NoTerminalFollowupExamples",
        "classification=NO_TERMINAL_FOLLOWUP",
        "buy_like_candidate_progression_next_action",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "buy-like candidate progression smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention buy-like progression smoke" -Text $doc -Pattern "smoke_buy_like_candidate_progression_ssh\.ps1"
    Assert-Contains -Name "operator docs mention buy-like progression read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "buy-like candidate progression SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxCandidateRows", "0") `
    -ExpectedPattern "MaxCandidateRows must be between 1 and 2000" `
    -Description "buy-like candidate progression max rows input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
    -ExpectedPattern "Symbol contains unsupported characters for smoke invocation" `
    -Description "buy-like candidate progression symbol input guard"

Write-Host "[buy-like-candidate-progression-smoke-test] OK"
