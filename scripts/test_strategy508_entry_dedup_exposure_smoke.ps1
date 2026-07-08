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

    $scriptPath = Join-Path $PSScriptRoot "smoke_strategy508_entry_dedup_exposure_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_strategy508_entry_dedup_exposure_ssh.ps1"
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
        "bt_decision_audit",
        "bt_live_signal",
        "getEntryDedupGovernanceDashboard",
        "getStagedAddReadiness",
        "ENTRY_SKIP",
        "EntryDedup",
        "same strategy/symbol/interval LONG exposure already exists",
        "STAGED_ADD_SHADOW_CANDIDATE_REVIEW_NOT_LIVE",
        "KEEP_ENTRY_DEDUP_EXACT_DUPLICATE_BLOCK",
        "ENTRY_DEDUP_BUDGET_CAP_BLOCKED",
        "ENTRY_DEDUP_HARD_SAFETY_BLOCKED",
        "strategy508_entry_dedup_exposure_recommendation",
        "strategy508_entry_dedup_next_action",
        "wouldAllowStagedAdd",
        "remainingAddBudget",
        "open_same_strategy_positions",
        "open_same_strategy_auto_positions",
        "open_same_strategy_shadow_positions",
        "open_same_strategy_shadow_zero_notional_positions",
        "open_same_strategy_real_exposure_status",
        "SHADOW_ZERO_NOTIONAL_ROWS_ONLY_NOT_REAL_EXPOSURE",
        "target_group_blockers",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "strategy 508 EntryDedup exposure smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention strategy 508 EntryDedup smoke" -Text $doc -Pattern "smoke_strategy508_entry_dedup_exposure_ssh\.ps1"
    Assert-Contains -Name "operator docs mention strategy 508 EntryDedup read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "strategy 508 EntryDedup SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Hours", "0") `
    -ExpectedPattern "Hours must be between 1 and 720" `
    -Description "strategy 508 EntryDedup hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "strategy 508 EntryDedup interval input guard"

Write-Host "[strategy508-entry-dedup-exposure-smoke-test] OK"
