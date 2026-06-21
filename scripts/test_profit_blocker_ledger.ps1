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

    $script = Join-Path $PSScriptRoot "prepare_profit_blocker_ledger_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit blocker ledger test"
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
        throw "profit blocker ledger accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit blocker ledger did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit blocker ledger reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_blocker_ledger_ssh.ps1"
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
        "[profit-blocker-ledger] read-only ledger",
        "scope=READ_ONLY",
        "prepare_profit_runtime_deploy_review_packet_ssh.ps1",
        "prepare_profit_shadow_experiment_packet_ssh.ps1",
        "prepare_strategy485_operator_review_packet_ssh.ps1",
        "smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        "origin_delta_status",
        "profit_runtime_deploy_packet_status",
        "profit_shadow_packet_status",
        "strategy485_operator_packet_status",
        "replay_observation_bundle_recommendation",
        "profit_blocker_ledger_missing_requirements",
        "profit_blocker_ledger_items",
        "profit_blocker_ledger_packet",
        "profit_blocker_ledger_status",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "ACTIONABLE_READ_ONLY_BLOCKERS",
        "deployed runtime current",
        "complete DataFreshness replayable candidate rows",
        "current strategy 485 OCO health",
        "RequireActionable",
        "notAuthorization=read-only profit blocker ledger only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "profit blocker ledger marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "profit blocker ledger must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_profit_blocker_ledger_ssh.ps1",
        "profit blocker ledger",
        "profit_blocker_ledger_status",
        "profit_blocker_ledger_items",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit blocker ledger" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "0") `
    -ExpectedPattern "Limit must be between 1 and 1000"

Write-Host "[profit-blocker-ledger-test] OK"
