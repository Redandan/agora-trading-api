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

    $scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_sample_gap_rca_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_data_freshness_sample_gap_rca_ssh.ps1"
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
        "FILTER_BLOCK",
        "DataFreshnessGuard",
        "SIGNAL_EVAL",
        "SIGNAL_BUY",
        "ATTENTION_HIT",
        "NO_RECENT_BUY_STYLE_CANDIDATES",
        "CANDIDATES_EXIST_BUT_NOT_DF_BLOCKED",
        "OTHER_BLOCKERS_DOMINATE_RECENT_WINDOW",
        "DATAFRESHNESS_SAMPLE_PRESENT",
        "NO_AUDIT_ROWS_IN_WINDOW",
        "RECENT_WINDOW_GAP_WITH_OLDER_DATAFRESHNESS_HISTORY",
        "data_freshness_sample_gap_rca_recommendation",
        "data_freshness_sample_gap_next_action",
        "latest_data_freshness_row_time",
        "data_freshness_rows_7d",
        "event_type_counts_",
        "top_filter_blockers_",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "DataFreshness sample gap RCA smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention sample gap RCA smoke" -Text $doc -Pattern "smoke_data_freshness_sample_gap_rca_ssh\.ps1"
    Assert-Contains -Name "operator docs mention sample gap RCA read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "DataFreshness sample gap RCA SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30" `
    -Description "DataFreshness sample gap RCA review window input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
    -ExpectedPattern "Symbol contains unsupported characters for smoke invocation" `
    -Description "DataFreshness sample gap RCA symbol input guard"

Write-Host "[data-freshness-sample-gap-rca-test] OK"
