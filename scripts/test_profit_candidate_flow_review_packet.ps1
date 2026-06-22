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

    $script = Join-Path $PSScriptRoot "prepare_profit_candidate_flow_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit candidate-flow packet test"
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
        throw "profit candidate-flow packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit candidate-flow packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit candidate-flow packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_candidate_flow_review_packet_ssh.ps1"
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
        "[profit-candidate-flow-review-packet] read-only packet",
        "PROFIT_CANDIDATE_FLOW_REVIEW_PACKET",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "smoke_buy_like_candidate_progression_ssh.ps1",
        "profit_candidate_flow_review_packet",
        "profit_candidate_flow_review_status",
        "profit_candidate_flow_review_items",
        "profit_candidate_flow_blockers",
        "profit_candidate_flow_required_evidence",
        "READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE",
        "READY_FOR_NO_TERMINAL_FOLLOWUP_REVIEW_NOT_LIVE",
        "READY_FOR_FILTER_BLOCK_CANDIDATE_FLOW_REVIEW_NOT_LIVE",
        "PENDING_BUY_LIKE_CANDIDATES",
        "ENTRY_SKIP_DOMINATES_BUY_LIKE_CANDIDATE_FLOW",
        "NO_BUY_LIKE_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE",
        "EntryDedup/ShadowExecutionIntent row-level review before any entry-policy experiment",
        "do not relax EntryDedup/DataFreshness/live policy",
        "notAuthorization=read-only profit candidate-flow review packet only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireActionable"
    )) {
    Assert-Contains -Name "profit candidate-flow packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_candidate_flow_review_packet_ssh.ps1",
        "profit_candidate_flow_review_packet",
        "PROFIT_CANDIDATE_FLOW_REVIEW_PACKET",
        "READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE",
        "EntryDedup/ShadowExecutionIntent",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit candidate-flow packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTCUSDT';echo bad") `
    -ExpectedPattern "Symbol contains unsupported characters for profit candidate-flow packet arguments"

Write-Host "[profit-candidate-flow-review-packet-test] OK"
