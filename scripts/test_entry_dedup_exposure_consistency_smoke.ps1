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

    $scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_exposure_consistency_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_exposure_consistency_ssh.ps1"
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
        "bt_live_signal",
        "existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull",
        "findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull",
        "ENTRY_DEDUP_EXPOSURE_SEMANTICS_MISMATCH_REVIEW",
        "ENTRY_DEDUP_EXPOSURE_CONSISTENT_AUTO_POSITION",
        "ENTRY_DEDUP_NO_RECENT_SKIPS",
        "ENTRY_DEDUP_EXPOSURE_INCONCLUSIVE",
        "entry_dedup_exposure_consistency_recommendation",
        "entry_dedup_exposure_consistency_next_action",
        "non_auto_zero_qty_rows",
        "non_auto_eventrisk_rows",
        "auto_traded_open_rows",
        "expected_entry_dedup_reason_marker=same strategy/symbol/interval LONG exposure already exists",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "EntryDedup exposure consistency smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention EntryDedup exposure consistency smoke" -Text $doc -Pattern "smoke_entry_dedup_exposure_consistency_ssh\.ps1"
    Assert-Contains -Name "operator docs mention EntryDedup exposure consistency read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "EntryDedup exposure consistency SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Hours", "0") `
    -ExpectedPattern "Hours must be between 1 and 720" `
    -Description "EntryDedup exposure consistency hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "EntryDedup exposure consistency interval input guard"

Write-Host "[entry-dedup-exposure-consistency-smoke-test] OK"
