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

    $script = Join-Path $PSScriptRoot "watch_local_tradingview_post_close_evidence_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW post-close evidence watch test" }

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
        throw "LOCAL_TRADINGVIEW post-close evidence watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "LOCAL_TRADINGVIEW post-close evidence watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "LOCAL_TRADINGVIEW post-close evidence watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_local_tradingview_post_close_evidence_ssh.ps1"
$runtimeWatchPath = Join-Path $PSScriptRoot "watch_local_tradingview_runtime_evidence_ssh.ps1"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$webhookPath = Join-Path $repoRoot "docs/tradingview-webhook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runtimeWatchText = Get-Content -Raw -LiteralPath $runtimeWatchPath
$verifyText = Get-Content -Raw -LiteralPath $verifyLocalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $webhookPath
) -join "`n"

foreach ($marker in @(
        "[local-tradingview-post-close-evidence-watch] read-only post-close watcher",
        "scope=READ_ONLY",
        "fresh LOCAL_TRADINGVIEW closed-K persist",
        "watch_local_tradingview_runtime_evidence_ssh.ps1",
        "AcceptExistingClosedK",
        "AllowMissingEvidenceAfterClosedK",
        "AllowWaitTimeout",
        "RunReadinessEveryAttempt",
        "requireEvidenceAfterClosedK",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[local-tradingview-post-close-evidence-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "poll_baseline_target_interval_persisted_count",
        "poll_target_interval_subscribe_count",
        "poll_target_interval_connected_count",
        "poll_target_interval_persisted_count",
        "poll_target_interval_latest_persisted",
        "WAIT_1D_CLOSED_K_EVENT_TIMEOUT",
        "CLOSED_K_OBSERVED_EVIDENCE_CONFIRMED",
        "CLOSED_K_OBSERVED_EVIDENCE_MISSING",
        "EVIDENCE_UNAVAILABLE",
        "ACCEPTING_EXISTING_TARGET_INTERVAL_CLOSED_K",
        "TARGET_INTERVAL_CLOSED_K_AND_TARGET_STRATEGY_EVIDENCE_PRESENT",
        "local_tradingview_post_close_evidence_watch_status",
        "local_tradingview_post_close_evidence_watch_reason",
        "local_tradingview_post_close_evidence_watch_baseline_target_interval_persisted_count",
        "local_tradingview_post_close_evidence_watch_target_interval_persisted_count",
        "local_tradingview_post_close_evidence_watch_runtime_evidence_watch_status",
        "local_tradingview_post_close_evidence_watch_target_strategy_evidence_rows",
        "local_tradingview_post_close_evidence_watch_target_strategy_shadow_like_rows",
        "local_tradingview_post_close_evidence_watch_next_action",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only LOCAL_TRADINGVIEW post-close evidence watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "LOCAL_TRADINGVIEW post-close evidence watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "deploy_ssh.ps1",
        "TRADING_OKX_ENABLED=true",
        "TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO",
        "placeMarketBuy",
        "placeOco",
        "sendAlert",
        " -Execute "
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "LOCAL_TRADINGVIEW post-close evidence watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "local_tradingview_runtime_evidence_watch_status",
        "local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows",
        "WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED",
        "BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED",
        "RequireEvidence"
    )) {
    Assert-Contains -Name "runtime evidence watcher keeps post-close dependency markers" -Text $runtimeWatchText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "watch_local_tradingview_post_close_evidence_ssh.ps1",
        "LOCAL_TRADINGVIEW post-close evidence watcher",
        "local_tradingview_post_close_evidence_watch_status",
        "CLOSED_K_OBSERVED_EVIDENCE_CONFIRMED",
        "WAIT_1D_CLOSED_K_EVENT_TIMEOUT",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention LOCAL_TRADINGVIEW post-close evidence watch" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local invokes LOCAL_TRADINGVIEW post-close evidence watch test" -Text $verifyText -Pattern "test_local_tradingview_post_close_evidence_watch\.ps1"
Assert-Contains -Name "verify local guards LOCAL_TRADINGVIEW post-close evidence watch SSH target" -Text $verifyText -Pattern "scripts/watch_local_tradingview_post_close_evidence_ssh\.ps1"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-PollSeconds", "1") `
    -ExpectedPattern "PollSeconds must be between 30 and 3600"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxWaitMinutes", "0") `
    -ExpectedPattern "MaxWaitMinutes must be between 1 and 43200"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-EvidenceAttempts", "0") `
    -ExpectedPattern "EvidenceAttempts must be between 1 and 24"

Write-Host "[local-tradingview-post-close-evidence-watch-test] OK"
