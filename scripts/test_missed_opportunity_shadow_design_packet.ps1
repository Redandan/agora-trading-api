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

    $script = Join-Path $PSScriptRoot "prepare_missed_opportunity_shadow_design_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for missed-opportunity shadow packet test"
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
        throw "missed-opportunity shadow packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "missed-opportunity shadow packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "missed-opportunity shadow packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_missed_opportunity_shadow_design_packet_ssh.ps1"
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
        "[missed-opportunity-shadow-design-packet] read-only preflight",
        "scope=READ_ONLY",
        "prepare_no_buy_row_review_packet_ssh.ps1",
        "no_buy_row_review_packet",
        "MISSED_OPPORTUNITY_REVIEW",
        "candidateMissedOpportunityRows",
        "candidateMissedOpportunityRowCount",
        "waitForSignalConfirmationRowCount",
        "hardSafetyRowCount",
        "shadow_design_review_allowed",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "missed_opportunity_shadow_design_missing_requirements",
        "missed_opportunity_shadow_design_packet",
        "missed_opportunity_shadow_design_packet_status",
        "READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE",
        "BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED",
        "NO_EVIDENCE",
        "RequireReview",
        "notAuthorization=read-only missed-opportunity shadow design preflight only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "missed-opportunity shadow packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "missed-opportunity shadow packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_missed_opportunity_shadow_design_packet_ssh.ps1",
        "missed-opportunity shadow design packet",
        "missed_opportunity_shadow_design_packet_status",
        "BLOCKED_SIGNAL_POLICY_REVIEW_REQUIRED",
        "READY_FOR_MISSED_OPPORTUNITY_SHADOW_DESIGN_NOT_LIVE",
        "shadow_design_review_allowed",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention missed-opportunity shadow packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ExecutionDays", "0") `
    -ExpectedPattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90"

Write-Host "[missed-opportunity-shadow-design-packet-test] OK"
