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

    $scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_shadow_review_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_shadow_review_ssh.ps1"
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
        "md_kline",
        "coarse_entry_dedup_definition",
        "staged_add_position_definition",
        "entry_dedup_semantics_shadow_recommendation",
        "ENTRY_DEDUP_SEMANTICS_SHADOW_EXPERIMENT_CANDIDATE_NOT_LIVE",
        "ENTRY_DEDUP_SEMANTICS_TP_PATH_REPLAY_REVIEW",
        "ENTRY_DEDUP_SEMANTICS_NO_POSITIVE_ALPHA_EVIDENCE",
        "ENTRY_DEDUP_SHADOW_REVIEW_LOW_SAMPLE",
        "entry_dedup_semantics_shadow_review_plan",
        "entry_dedup_semantics_next_action",
        "positive_24h_rows",
        "avg_24h_return_pct",
        "avg_mfe_24h_pct",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "EntryDedup semantics shadow review smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention EntryDedup semantics shadow smoke" -Text $doc -Pattern "smoke_entry_dedup_semantics_shadow_review_ssh\.ps1"
    Assert-Contains -Name "operator docs mention EntryDedup semantics read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "EntryDedup semantics SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ForwardHours", "0") `
    -ExpectedPattern "ForwardHours must be between 1 and 168" `
    -Description "EntryDedup semantics forward-hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ShortForwardHours", "73") `
    -ExpectedPattern "ShortForwardHours must be between 1 and 72" `
    -Description "EntryDedup semantics short-forward-hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "EntryDedup semantics interval input guard"

Write-Host "[entry-dedup-semantics-shadow-review-smoke-test] OK"
