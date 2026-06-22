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

    $scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_feasibility_review_ssh.ps1"
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
        "takeProfitPct",
        "stopLossPct",
        "roundTripFeePct",
        "ambiguous_same_bar_rows",
        "avg_net_return_pct",
        "ENTRY_DEDUP_FEASIBILITY_SHADOW_EXPERIMENT_READY_NOT_LIVE",
        "ENTRY_DEDUP_FEASIBILITY_AMBIGUOUS_REPLAY_REVIEW",
        "ENTRY_DEDUP_FEASIBILITY_POSITIVE_TIMEOUT_REVIEW",
        "ENTRY_DEDUP_FEASIBILITY_NOT_PROVEN",
        "entry_dedup_semantics_feasibility_recommendation",
        "entry_dedup_semantics_feasibility_plan",
        "entry_dedup_semantics_feasibility_next_action",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "EntryDedup semantics feasibility review smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention EntryDedup semantics feasibility smoke" -Text $doc -Pattern "smoke_entry_dedup_semantics_feasibility_review_ssh\.ps1"
    Assert-Contains -Name "operator docs mention EntryDedup feasibility read-only" -Text $doc -Pattern "read-only"
}

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "EntryDedup feasibility SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TakeProfitPct", "0") `
    -ExpectedPattern "TakeProfitPct must be greater than 0 and at most 20" `
    -Description "EntryDedup feasibility TP input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-RoundTripFeePct", "3") `
    -ExpectedPattern "RoundTripFeePct must be between 0 and 2" `
    -Description "EntryDedup feasibility fee input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "EntryDedup feasibility interval input guard"

Write-Host "[entry-dedup-semantics-feasibility-review-smoke-test] OK"
