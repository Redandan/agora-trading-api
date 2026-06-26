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

    $script = Join-Path $PSScriptRoot "smoke_entry_dedup_blocker_decomposition_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for EntryDedup blocker decomposition test"
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
        throw "EntryDedup blocker decomposition accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "EntryDedup blocker decomposition did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "EntryDedup blocker decomposition reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_blocker_decomposition_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-blocker-decomposition] read-only production evidence check",
        "scope=READ_ONLY",
        "ENTRY_DEDUP_BLOCKER_DECOMPOSITION_PACKET",
        "READY_FOR_ENTRY_DEDUP_BLOCKER_DECOMPOSITION_REVIEW_NOT_LIVE",
        "classification_ranking",
        "reason_ranking",
        "possible_coarse_semantics_rows",
        "protective_rows",
        "COARSE_NON_AUTO_EVENTRISK_ZERO_QTY_OPEN_SIGNAL",
        "PROTECTIVE_AUTO_TRADED_OPEN_EXPOSURE",
        "entry_dedup_policy_change_allowed=false",
        "order_allowed=false",
        "read-only EntryDedup blocker decomposition only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "EntryDedup blocker decomposition marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "EntryDedup blocker decomposition must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "smoke_entry_dedup_blocker_decomposition_ssh.ps1",
        "ENTRY_DEDUP_BLOCKER_DECOMPOSITION_PACKET",
        "READY_FOR_ENTRY_DEDUP_BLOCKER_DECOMPOSITION_REVIEW_NOT_LIVE",
        "possible_coarse_semantics_rows",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup blocker decomposition" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Hours", "0") `
    -ExpectedPattern "Hours must be between 1 and 720"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTC/USDT") `
    -ExpectedPattern "Symbol contains unsupported characters for smoke invocation"

Write-Host "[entry-dedup-blocker-decomposition-test] OK"
