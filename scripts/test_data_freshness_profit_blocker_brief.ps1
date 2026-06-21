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

    $script = Join-Path $PSScriptRoot "prepare_data_freshness_profit_blocker_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness profit blocker brief test"
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
        throw "DataFreshness profit blocker brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "DataFreshness profit blocker brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness profit blocker brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_profit_blocker_brief_ssh.ps1"
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
        "[data-freshness-profit-blocker-brief] read-only brief",
        "scope=READ_ONLY",
        "smoke_signal_correctness_ssh.ps1",
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "data_freshness_current_status",
        "data_freshness_replay_candidate_id_recommendation",
        "data_freshness_counterfactual_recommendation",
        "complete_replayable_candidate_rows",
        "missing_counterfactual_fields",
        "data_freshness_profit_blockers",
        "data_freshness_profit_blocker_brief_packet",
        "data_freshness_profit_blocker_status",
        "PENDING_DATAFRESHNESS_CURRENT_SAMPLE",
        "PENDING_REPLAY_CANDIDATE_ID_EVIDENCE",
        "PENDING_COUNTERFACTUAL_REPLAY_EVIDENCE",
        "READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE",
        "notAuthorization=read-only DataFreshness profit blocker brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireActionable"
    )) {
    Assert-Contains -Name "DataFreshness profit blocker brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "DataFreshness profit blocker brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_data_freshness_profit_blocker_brief_ssh.ps1",
        "DataFreshness profit blocker brief",
        "data_freshness_profit_blocker_status",
        "PENDING_DATAFRESHNESS_CURRENT_SAMPLE",
        "READY_FOR_DATAFRESHNESS_REPLAY_REVIEW_NOT_LIVE",
        "data_freshness_profit_blocker_brief_packet",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention DataFreshness profit blocker brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "0") `
    -ExpectedPattern "Limit must be between 1 and 1000"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayIdDays", "31") `
    -ExpectedPattern "ReplayIdDays must be between 1 and 30"

Write-Host "[data-freshness-profit-blocker-brief-test] OK"
