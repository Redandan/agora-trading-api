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

    $scriptPath = Join-Path $PSScriptRoot "smoke_strategy508_first_entry_readiness_ssh.ps1"
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
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "$Description reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_strategy508_first_entry_readiness_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local MCP read-only tools plus direct MySQL SELECTs only",
        "TRADING_SIGNAL_SOURCE_PRIMARY",
        "TRADING_LEGACY_LIVE_EVALUATOR_ENABLED",
        "TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS",
        "TRADINGVIEW_LOCAL_STRATEGY_ID",
        "TRADING_OKX_ENABLED",
        "TRADING_OKX_MAX_OPEN_POSITIONS",
        "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_ENABLED",
        "TRADING_OKX_POSITION_SIZING_MIN_NOTIONAL_FLOOR_MAX_RISK_USDT",
        "previewPositionSizing",
        "bt_decision_audit",
        "bt_live_signal",
        "strategy508_signal_source_gate",
        "strategy508_first_entry_blockers",
        "strategy508_first_entry_conclusion",
        "first_entry_semantics",
        "staged_add_preview_applicable",
        "entry_dedup_first_entry_pass",
        "auto_trade_open_position_gate",
        "latest_ev_gate_applies_to_latest_signal",
        "latest_ev_gate_status",
        "first_entry_position_sizing_status",
        "recommendedSlRiskUsdt",
        "NO_RECENT_EV_CONTEXT",
        "STALE_",
        "BLOCK_RISK_SIZED_BELOW_MIN_NOTIONAL",
        "PASS_MIN_NOTIONAL_FLOOR_APPLIED",
        "LATEST_SIGNAL_BLOCKED_POSITION_SIZING",
        "below_min_notional_skip",
        "min_notional_floor_applied",
        "EXTERNAL_TRADINGVIEW_PRIMARY_LEGACY_508_SUPPRESSED",
        "LOCAL_TRADINGVIEW_ACTIVE_FOR_STRATEGY",
        "LEGACY_LIVE_EVALUATOR_ACTIVE_FOR_STRATEGY",
        "FIRST_ENTRY_BLOCKED_REVIEW_REQUIRED",
        "FIRST_ENTRY_GATES_CLEAR_BUT_NO_RECENT_SIGNAL",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "strategy 508 first-entry readiness smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention strategy 508 first-entry smoke" -Text $doc -Pattern "smoke_strategy508_first_entry_readiness_ssh\.ps1"
    Assert-Contains -Name "operator docs mention strategy 508 first-entry read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "strategy 508 first-entry SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Hours", "0") `
    -ExpectedPattern "Hours must be between 1 and 720" `
    -Description "strategy 508 first-entry hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "strategy 508 first-entry interval input guard"

Write-Host "[strategy508-first-entry-readiness-smoke-test] OK"
