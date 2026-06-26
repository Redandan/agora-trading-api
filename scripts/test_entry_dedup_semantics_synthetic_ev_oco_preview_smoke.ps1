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
    $scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_synthetic_ev_oco_preview_ssh.ps1"
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
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_semantics_synthetic_ev_oco_preview_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath
$verifyLocalText = Get-Content -Raw -LiteralPath $verifyLocalPath

foreach ($marker in @(
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "no RuntimeDecisionEvidence writes",
        "bt_decision_audit",
        "bt_live_signal",
        "md_kline",
        "ENTRY_DEDUP_SYNTHETIC_EV_OCO_PREVIEW_PACKET",
        "READ_ONLY_SYNTHETIC_REPLAY_PROXY_NOT_RUNTIME_EV",
        "SYNTHETIC_EV_PROXY_PASS",
        "SYNTHETIC_EV_PROXY_NOT_PASS",
        "PLAN_SHAPE_VALID",
        "OCO_ROUTE_NOT_PROVEN_EXISTING_EXPOSURE_NON_AUTO_OR_MISSING_OCO",
        "entry_dedup_synthetic_ev_oco_preview_status",
        "runtime_evidence_write_allowed=false",
        "order_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "notAuthorization",
        "Assert-SshHostSafe",
        "refusing to query unexpected database",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "EntryDedup synthetic EV/OCO preview smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($doc in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "operator docs mention EntryDedup synthetic EV/OCO preview smoke" -Text $doc -Pattern "smoke_entry_dedup_semantics_synthetic_ev_oco_preview_ssh\.ps1"
    Assert-Contains -Name "operator docs mention synthetic EV/OCO read-only" -Text $doc -Pattern "synthetic EV/OCO"
}

Assert-Contains `
    -Name "verify_local includes EntryDedup synthetic EV/OCO preview smoke test" `
    -Text $verifyLocalText `
    -Pattern "test_entry_dedup_semantics_synthetic_ev_oco_preview_smoke\.ps1"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target" `
    -Description "EntryDedup synthetic EV/OCO preview SSH target input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TakeProfitPct", "0") `
    -ExpectedPattern "TakeProfitPct must be greater than 0 and at most 20" `
    -Description "EntryDedup synthetic EV/OCO preview TP input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ForwardHours", "200") `
    -ExpectedPattern "ForwardHours must be between 1 and 168" `
    -Description "EntryDedup synthetic EV/OCO preview forward-hours input guard"

Assert-ScriptFailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-IntervalCode", "1h';echo bad") `
    -ExpectedPattern "IntervalCode contains unsupported characters for smoke invocation" `
    -Description "EntryDedup synthetic EV/OCO preview interval input guard"

Write-Host "[entry-dedup-synthetic-ev-oco-preview-smoke-test] OK"
