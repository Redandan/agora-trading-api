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

    $script = Join-Path $PSScriptRoot "prepare_trailing_stop_operator_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop operator packet test"
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
        throw "trailing-stop operator packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop operator packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop operator packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_operator_review_packet_ssh.ps1"
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
        "[trailing-stop-operator-review-packet] read-only packet",
        "scope=READ_ONLY",
        "smoke_trailing_stop_pnl_replay_ssh.ps1",
        "-RequireAcceptance",
        "sampleStatus",
        "acceptanceRows",
        "acceptanceDeltaPnl",
        "improvementPct",
        "acceptance=PASS",
        "acceptanceBlocker=NONE",
        "ambiguous same-bar exclusion marker",
        "trailing_stop_operator_packet_missing_requirements",
        "trailing_stop_operator_review_packet",
        "trailing_stop_operator_packet_status",
        "READY_FOR_OPERATOR_PACKET_NOT_LIVE",
        "requiredOperatorChecks",
        "enable trailing scheduler",
        "modify OCO",
        "notAuthorization=read-only trailing-stop operator review packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReady"
    )) {
    Assert-Contains -Name "trailing-stop operator packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRAILING_STOP_ENABLED=true",
        "trailing-stop.enabled=true",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing-stop operator packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_trailing_stop_operator_review_packet_ssh.ps1",
        "trailing-stop operator review packet",
        "trailing_stop_operator_packet_status",
        "READY_FOR_OPERATOR_PACKET_NOT_LIVE",
        "trailing_stop_acceptance",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention trailing-stop packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "0") `
    -ExpectedPattern "Limit must be between 1 and 500"

Write-Host "[trailing-stop-operator-review-packet-test] OK"
