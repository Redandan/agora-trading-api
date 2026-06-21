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

    $script = Join-Path $PSScriptRoot "prepare_profit_operator_review_matrix_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator matrix test"
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
        throw "profit operator matrix accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit operator matrix did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator matrix reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_review_matrix_ssh.ps1"
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
        "[profit-operator-review-matrix] read-only matrix",
        "scope=READ_ONLY",
        "prepare_profit_readiness_brief_ssh.ps1",
        "watch_profit_evidence_readiness_ssh.ps1",
        "prepare_exit_side_profit_review_packet_ssh.ps1",
        "profit_operator_review_items",
        "profit_operator_review_matrix_packet",
        "profit_operator_review_matrix_status",
        "HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        "exit-side",
        "entry-filter",
        "data-freshness-replay",
        "readyForOperatorReview",
        'readyForOperatorReview = ($entryLaneStatus -eq "CLEAR")',
        "notAuthorization=read-only profit operator review matrix only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReviewItems"
    )) {
    Assert-Contains -Name "profit operator matrix marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "profit operator matrix must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_profit_operator_review_matrix_ssh.ps1",
        "profit operator review matrix",
        "profit_operator_review_matrix_status",
        "HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        "exit-side",
        "data-freshness-replay",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit operator matrix" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayLimit", "0") `
    -ExpectedPattern "ReplayLimit must be between 1 and 500"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "1") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[profit-operator-review-matrix-test] OK"
