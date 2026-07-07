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

    $script = Join-Path $PSScriptRoot "watch_profit_next_execution_readiness_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit next execution watch test" }

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
        throw "profit next execution watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit next execution watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit next execution watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_profit_next_execution_readiness_ssh.ps1"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$verifyText = Get-Content -Raw -LiteralPath $verifyLocalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-next-execution-watch] read-only bounded watcher",
        "scope=READ_ONLY",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "prepare_profit_next_execution_blocker_packet.ps1",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[profit-next-execution-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "profit-next-execution-watch-data-freshness-attempt-",
        "profit-next-execution-watch-next-blocker-attempt-",
        "profit-next-execution-blocker-packet-latest.log",
        "attempt_profit_next_execution_latest_log_path",
        "attempt_profit_next_execution_route",
        "attempt_profit_next_execution_blocker_status",
        "attempt_profit_next_execution_unique_blocker",
        "attempt_profit_next_execution_observation_sample_ready",
        "attempt_profit_next_execution_sample_collection_blocked_by",
        "attempt_profit_next_execution_replay_candidate_id_rows",
        "PENDING_OPEN_OCO_SAMPLE",
        "PENDING_TRAILING_OPT_IN_EVIDENCE",
        "PENDING_DATAFRESHNESS_REPLAY_EVIDENCE",
        "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE",
        "RequireSampleReady",
        "profit_next_execution_watch_status",
        "profit_next_execution_watch_reason",
        "profit_next_execution_watch_next_action",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only next-execution watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "profit next execution watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "deploy_ssh.ps1",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        " -Execute "
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "profit next execution watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "watch_profit_next_execution_readiness_ssh.ps1",
        "profit next execution watch",
        "profit_next_execution_watch_status",
        "PENDING_OPEN_OCO_SAMPLE",
        "PENDING_DATAFRESHNESS_REPLAY_EVIDENCE",
        "EVIDENCE_READY_FOR_OPERATOR_REVIEW_NOT_LIVE",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit next execution watch" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local invokes profit next execution watch test" -Text $verifyText -Pattern "test_profit_next_execution_readiness_watch\.ps1"
Assert-Contains -Name "verify local guards profit next execution watch SSH target" -Text $verifyText -Pattern "scripts/watch_profit_next_execution_readiness_ssh\.ps1"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxAttempts", "0") `
    -ExpectedPattern "MaxAttempts must be between 1 and 48"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-SleepSeconds", "-1") `
    -ExpectedPattern "SleepSeconds must be between 0 and 3600"

Write-Host "[profit-next-execution-watch-test] OK"
