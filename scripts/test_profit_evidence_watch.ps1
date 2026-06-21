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

    $script = Join-Path $PSScriptRoot "watch_profit_evidence_readiness_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit evidence watch test"
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
        throw "profit evidence watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit evidence watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit evidence watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_profit_evidence_readiness_ssh.ps1"
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
        "[profit-evidence-watch] read-only bounded watcher",
        "scope=READ_ONLY",
        "prepare_profit_readiness_brief_ssh.ps1",
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[profit-evidence-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "attempt_profit_readiness_brief_status",
        "attempt_signal_policy_clear",
        "attempt_data_freshness_current_status",
        "attempt_replay_candidate_id_recommendation",
        "attempt_replay_observation_bundle_recommendation",
        "PENDING_DATAFRESHNESS_CURRENT_SAMPLE",
        "PENDING_REPLAY_CANDIDATE_ID_EVIDENCE",
        "PENDING_COUNTERFACTUAL_REPLAY_EVIDENCE",
        "EVIDENCE_READY_FOR_REVIEW_NOT_LIVE",
        "RequireEvidenceReady",
        "profit_evidence_watch_status",
        "profit_evidence_watch_next_action",
        "notAuthorization=read-only evidence watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "profit evidence watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "profit evidence watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "watch_profit_evidence_readiness_ssh.ps1",
        "profit evidence watch",
        "profit_evidence_watch_status",
        "PENDING_DATAFRESHNESS_CURRENT_SAMPLE",
        "EVIDENCE_READY_FOR_REVIEW_NOT_LIVE",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit evidence watch" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxAttempts", "0") `
    -ExpectedPattern "MaxAttempts must be between 1 and 48"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-SleepSeconds", "-1") `
    -ExpectedPattern "SleepSeconds must be between 0 and 3600"

Write-Host "[profit-evidence-watch-test] OK"
