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

    $script = Join-Path $PSScriptRoot "watch_grid_open_readiness_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for grid open readiness watch test"
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
        throw "grid open readiness watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "grid open readiness watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "grid open readiness watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_grid_open_readiness_ssh.ps1"
$boardPath = Join-Path $PSScriptRoot "prepare_grid_open_blocker_priority_board_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$boardText = Get-Content -Raw -LiteralPath $boardPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-open-readiness-watch] read-only bounded watcher",
        "scope=READ_ONLY",
        "prepare_grid_open_blocker_priority_board_ssh.ps1",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[grid-open-readiness-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "attempt_grid_open_board_status",
        "attempt_grid_open_board_decision",
        "attempt_grid_openable_now",
        "attempt_grid_open_readiness_score_pct",
        "attempt_grid_open_top_blocker",
        "PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE",
        "PENDING_GRID_EVENT_RISK_R0",
        "PENDING_GRID_ENV_DIFF",
        "PENDING_GRID_OPEN_BLOCKERS",
        "GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION",
        "RequireOpenable",
        "grid_open_readiness_watch_status",
        "grid_open_readiness_watch_next_action",
        "notAuthorization=read-only grid open readiness watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "grid open readiness watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "grid open readiness watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "GRID_OPEN_BLOCKER_PRIORITY_BOARD",
        "grid_open_blocker_priority_board_packet",
        "grid_open_readiness_score_pct",
        "grid_open_blocker_priority_top_blocker"
    )) {
    Assert-Contains -Name "grid blocker priority board marker" -Text $boardText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "watch_grid_open_readiness_ssh.ps1",
        "grid open readiness watch",
        "grid_open_readiness_watch_status",
        "PENDING_GRID_DEPLOY_OR_SPLIT_ACCEPTANCE",
        "GRID_OPEN_READINESS_READY_FOR_SEPARATE_CREATEGRID_AUTHORIZATION_NOT_MUTATION",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention grid open readiness watch" -Text $docsText -Pattern ([regex]::Escape($marker))
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

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "10") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[grid-open-readiness-watch-test] OK"
