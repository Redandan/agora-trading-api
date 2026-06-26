Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-ScriptFailsBeforeSsh {
    param([string[]]$Arguments, [string]$ExpectedPattern, [string]$Description)
    $scriptPath = Join-Path $PSScriptRoot "smoke_no_terminal_followup_continuity_ssh.ps1"
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
    if ($text -match "Could not resolve hostname|Permission denied|Connection timed out") {
        throw "$Description reached SSH unexpectedly. Output: $text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_no_terminal_followup_continuity_ssh.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "[no-terminal-followup-continuity] read-only production DB evidence check",
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "bt_decision_audit",
        "NO_TERMINAL_FOLLOWUP",
        "PENDING_PRIMARY_FOLLOWUP_WINDOW",
        "PENDING_EXTENDED_FOLLOWUP_WINDOW",
        "TERMINAL_AFTER_PRIMARY_WINDOW",
        "SAME_STRATEGY_DIFFERENT_INTERVAL_TERMINAL",
        "OTHER_TERMINAL_NEARBY",
        "NON_TERMINAL_SAME_KEY_CONTINUED",
        "NO_FOLLOWUP_WITHIN_EXTENDED_WINDOW",
        "no_terminal_continuity_review_status",
        "READY_FOR_NO_TERMINAL_CONTINUITY_REVIEW_NOT_LIVE",
        "no_terminal_continuity_classification",
        "no_terminal_strategy_interval_distribution",
        "terminal_after_primary_window_distribution",
        "no_terminal_continuity_examples",
        "no_terminal_continuity_next_action",
        "notAuthorization=read-only no-terminal continuity evidence only",
        "no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange",
        "OK read-only check complete",
        "Assert-SshHostSafe",
        "refusing to query unexpected database"
    )) {
    Assert-Contains -Name "no-terminal continuity smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$runbookText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "docs/deploy-runbook.md")
Assert-Contains -Name "runbook existing BUY-like progression section" -Text $runbookText -Pattern "smoke_buy_like_candidate_progression_ssh\.ps1"
Assert-Contains -Name "runbook mentions no-terminal continuity smoke" -Text $runbookText -Pattern "smoke_no_terminal_followup_continuity_ssh\.ps1"
Assert-Contains -Name "runbook mentions no-terminal continuity classifications" -Text $runbookText -Pattern "terminal-after-window"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "no-terminal continuity SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ExtendedFollowupHours", "1", "-FollowupHours", "6") `
    -ExpectedPattern "ExtendedFollowupHours must be between FollowupHours and 168" `
    -Description "no-terminal continuity extended window input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
    -ExpectedPattern "Symbol contains unsupported characters for smoke invocation" `
    -Description "no-terminal continuity symbol input guard"

Write-Host "[no-terminal-followup-continuity-smoke-test] OK"
