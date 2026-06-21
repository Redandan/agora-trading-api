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

    $script = Join-Path $PSScriptRoot "prepare_entry_filter_operator_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for entry/filter operator packet test"
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
        throw "entry/filter operator packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "entry/filter operator packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "entry/filter operator packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_filter_operator_review_packet_ssh.ps1"
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
        "[entry-filter-operator-review-packet] read-only packet",
        "scope=READ_ONLY",
        "smoke_signal_correctness_ssh.ps1",
        "executionMachineStatus",
        "missingEvalOrOrderBug",
        "signalPolicyClear",
        "governanceMode",
        "missedOpportunityStatus",
        "suspiciousNoBuyCount",
        "falseBlockRiskCount",
        "dataFreshnessCurrentStatus",
        "dataFreshnessCurrentClean",
        "noBuyClassifications",
        "noBuyBlockerFamilies",
        "entry_filter_operator_packet_missing_requirements",
        "entry_filter_operator_review_packet",
        "entry_filter_operator_packet_status",
        "REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        "READY_FOR_OPERATOR_PACKET_NOT_LIVE",
        "requiredOperatorChecks",
        "EntryDedup/DataFreshness/live policy unchanged",
        "notAuthorization=read-only entry/filter operator review packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReview"
    )) {
    Assert-Contains -Name "entry/filter operator packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "entry/filter operator packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_entry_filter_operator_review_packet_ssh.ps1",
        "entry/filter operator review packet",
        "entry_filter_operator_packet_status",
        "REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        "signalPolicyClear",
        "governanceMode",
        "missedOpportunityStatus",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention entry/filter packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-BlockedDays", "0") `
    -ExpectedPattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90"

Write-Host "[entry-filter-operator-review-packet-test] OK"
