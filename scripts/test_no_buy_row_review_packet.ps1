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

    $script = Join-Path $PSScriptRoot "prepare_no_buy_row_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for no-buy row packet test"
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
        throw "no-buy row packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "no-buy row packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "no-buy row packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_no_buy_row_review_packet_ssh.ps1"
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
        "[no-buy-row-review-packet] read-only packet",
        "scope=READ_ONLY",
        "smoke_signal_correctness_ssh.ps1",
        "executionMachineStatus",
        "missingEvalOrOrderBug",
        "signalPolicyClear",
        "governanceMode",
        "missedOpportunityStatus",
        "suspiciousNoBuyCount",
        "falseBlockRiskCount",
        "highForwardReturnNoBuyCount",
        "dataFreshnessCurrentStatus",
        "noBuyClassifications",
        "noBuyBlockerFamilies",
        "highReturnStrategies",
        "truthTableClassifications",
        "rowActionFamilyCounts",
        "Out-String -Width 4096",
        "RegexOptions]::Multiline",
        "no_buy_row_action_family_counts",
        "no_buy_row_review_packet_missing_requirements",
        "no_buy_row_review_packet",
        "no_buy_row_review_packet_status",
        "REVIEW_REQUIRED_NOT_EXPERIMENT",
        "READY_FOR_SHADOW_DESIGN_NOT_LIVE",
        "WAIT_FOR_SIGNAL_CONFIRMATION",
        "MISSED_OPPORTUNITY_REVIEW",
        "BUDGET_CAPACITY_REVIEW",
        "KEEP_HARD_SAFETY",
        "EntryDedup/DataFreshness/live policy remains unchanged",
        "notAuthorization=read-only no-buy row review packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReview"
    )) {
    Assert-Contains -Name "no-buy row packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        "ENTRY_DEDUP_ENABLED=false",
        "DATA_FRESHNESS_GUARD_ENABLED=false"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "no-buy row packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_no_buy_row_review_packet_ssh.ps1",
        "no-buy row review packet",
        "no_buy_row_review_packet_status",
        "REVIEW_REQUIRED_NOT_EXPERIMENT",
        "READY_FOR_SHADOW_DESIGN_NOT_LIVE",
        "rowActionFamilyCounts",
        "signalPolicyClear",
        "governanceMode",
        "missedOpportunityStatus",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention no-buy row packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-AccuracyDays", "0") `
    -ExpectedPattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90"

Write-Host "[no-buy-row-review-packet-test] OK"
