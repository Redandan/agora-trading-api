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

    $script = Join-Path $PSScriptRoot "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for EntryDedup exact-opportunity staged-add review test"
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
        throw "EntryDedup exact-opportunity staged-add review accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "EntryDedup exact-opportunity staged-add review did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "EntryDedup exact-opportunity staged-add review reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[entry-dedup-exact-opportunity-staged-add-review] read-only production evidence check",
        "scope=READ_ONLY",
        "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET",
        "READY_FOR_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE",
        "BLOCKED_ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_NOT_LIVE",
        "exact_opportunity_count",
        "exact_duplicate_suppressed_rows",
        "staged_add_budget_proxy_allowed_opportunities",
        "staged_add_review_candidate_opportunities",
        "opportunity_hash",
        "stagedAddExecutionAllowed",
        "staged_add_execution_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "order_allowed=false",
        "read-only EntryDedup exact-opportunity staged-add review only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe"
    )) {
    Assert-Contains -Name "EntryDedup exact-opportunity staged-add review marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "EntryDedup exact-opportunity staged-add review must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "smoke_entry_dedup_exact_opportunity_staged_add_review_ssh.ps1",
        "ENTRY_DEDUP_EXACT_OPPORTUNITY_STAGED_ADD_REVIEW_PACKET",
        "exact_opportunity_count",
        "staged_add_budget_proxy_allowed_opportunities",
        "staged_add_execution_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "docs mention EntryDedup exact-opportunity staged-add review" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ForwardHours", "0") `
    -ExpectedPattern "ForwardHours must be between 1 and 168"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Symbol", "BTC/USDT") `
    -ExpectedPattern "Symbol contains unsupported characters for smoke invocation"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-CandidateAddUsdt", "0") `
    -ExpectedPattern "CandidateAddUsdt must be greater than 0 and at most 10000"

Write-Host "[entry-dedup-exact-opportunity-staged-add-review-test] OK"
