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

    $script = Join-Path $PSScriptRoot "prepare_data_freshness_replay_evidence_readiness_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness replay evidence readiness test"
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
        throw "DataFreshness replay evidence readiness accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "DataFreshness replay evidence readiness did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness replay evidence readiness reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_replay_evidence_readiness_ssh.ps1"
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
        "[data-freshness-replay-evidence-readiness] read-only packet",
        "DATAFRESHNESS_REPLAY_EVIDENCE_READINESS_PACKET",
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "smoke_data_freshness_sample_gap_rca_ssh.ps1",
        "data_freshness_replay_evidence_readiness_packet",
        "data_freshness_replay_evidence_readiness_status",
        "data_freshness_replay_evidence_blockers",
        "data_freshness_replay_evidence_required",
        "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS",
        "PENDING_COUNTERFACTUAL_REPLAY_SNAPSHOTS",
        "READY_FOR_DATAFRESHNESS_REPLAY_EVIDENCE_REVIEW_NOT_LIVE",
        "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "COUNTERFACTUAL_REPLAY_SNAPSHOTS_MISSING",
        "fresh DataFreshnessGuard terminal rows after replay-id runtime",
        "complete_replayable_candidate_rows > 0",
        "missing_counterfactual_fields=[]",
        "collector_status_counts",
        "hard_gate_preview_status_counts",
        "data_freshness_sample_gap_rca_recommendation",
        "data_freshness_sample_gap_rca_review_days",
        "data_freshness_sample_gap_rca_long_days",
        "notAuthorization=read-only DataFreshness replay evidence readiness only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireActionable"
    )) {
    Assert-Contains -Name "DataFreshness replay evidence readiness marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "data_freshness_replay_evidence_readiness_packet",
        "DATAFRESHNESS_REPLAY_EVIDENCE_READINESS_PACKET",
        "PENDING_FRESH_DATAFRESHNESS_REPLAY_ROWS",
        "BLOCKED_PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention DataFreshness replay evidence readiness" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 90"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
    -ExpectedPattern "Symbol contains unsupported characters"

Write-Host "[data-freshness-replay-evidence-readiness-test] OK"
