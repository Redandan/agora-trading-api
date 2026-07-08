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

    $script = Join-Path $PSScriptRoot "watch_local_tradingview_buy_candidate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW BUY candidate watch test" }

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
        throw "LOCAL_TRADINGVIEW BUY candidate watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "LOCAL_TRADINGVIEW BUY candidate watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "LOCAL_TRADINGVIEW BUY candidate watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_local_tradingview_buy_candidate_ssh.ps1"
$candidatePath = Join-Path $PSScriptRoot "smoke_local_tradingview_candidate_ssh.ps1"
$readinessPath = Join-Path $PSScriptRoot "smoke_local_tradingview_only_readiness_ssh.ps1"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$webhookPath = Join-Path $repoRoot "docs/tradingview-webhook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$candidateText = Get-Content -Raw -LiteralPath $candidatePath
$readinessText = Get-Content -Raw -LiteralPath $readinessPath
$verifyText = Get-Content -Raw -LiteralPath $verifyLocalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $webhookPath
) -join "`n"

foreach ($marker in @(
        "[local-tradingview-buy-candidate-watch] read-only bounded watcher",
        "scope=READ_ONLY",
        "smoke_local_tradingview_candidate_ssh.ps1",
        "smoke_local_tradingview_only_readiness_ssh.ps1",
        "RunFullReadinessEveryAttempt",
        "RequireCurrentCandidate",
        "RequireReady",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[local-tradingview-buy-candidate-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "attempt_local_tradingview_current_candidate_status",
        "attempt_local_tradingview_candidate_readiness",
        "attempt_local_tradingview_pre_execution_readiness",
        "attempt_local_tradingview_pre_execution_blockers",
        "attempt_local_tradingview_only_status",
        "attempt_local_tradingview_only_blockers",
        "WAIT_BUY",
        "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED",
        "READY_CURRENT_BUY_CANDIDATE_BTC_BASE_LIVE_MICRO_ARMED",
        "BLOCKED_CURRENT_BUY_CANDIDATE",
        "EVIDENCE_UNAVAILABLE",
        "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE",
        "CURRENT_BUY_CANDIDATE_AND_PRE_EXECUTION_GATES_CLEAR",
        "local_tradingview_buy_candidate_watch_status",
        "local_tradingview_buy_candidate_watch_reason",
        "local_tradingview_buy_candidate_watch_current_candidate_status",
        "local_tradingview_buy_candidate_watch_candidate_blockers",
        "local_tradingview_buy_candidate_watch_pre_execution_blockers",
        "local_tradingview_buy_candidate_watch_only_status",
        "local_tradingview_buy_candidate_watch_only_blockers",
        "local_tradingview_buy_candidate_watch_effective_notional_usdt",
        "local_tradingview_buy_candidate_watch_daily_cap_available",
        "local_tradingview_buy_candidate_watch_open_position_cap_available",
        "local_tradingview_buy_candidate_watch_duplicate_bar_exists",
        "local_tradingview_buy_candidate_watch_next_action",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only LOCAL_TRADINGVIEW BUY candidate watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "LOCAL_TRADINGVIEW BUY candidate watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "LOCAL_TRADINGVIEW BUY candidate watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "localTradingViewPreExecutionReadiness",
        "local_tradingview_pre_execution_blockers",
        "localTradingViewNotionalAccepted",
        "localTradingViewDailyCapAvailable",
        "localTradingViewBtcBaseExposureCapAvailable",
        "localTradingViewDuplicateBarExists"
    )) {
    Assert-Contains -Name "candidate smoke keeps pre-execution markers for watcher" -Text $candidateText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "local_tradingview_only_status",
        "local_tradingview_pre_execution_readiness",
        "local_tradingview_pre_execution_blockers"
    )) {
    Assert-Contains -Name "readiness smoke keeps watcher markers" -Text $readinessText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "watch_local_tradingview_buy_candidate_ssh.ps1",
        "LOCAL_TRADINGVIEW BUY candidate watch",
        "local_tradingview_buy_candidate_watch_status",
        "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED",
        "READY_CURRENT_BUY_CANDIDATE_BTC_BASE_LIVE_MICRO_ARMED",
        "BLOCKED_CURRENT_BUY_CANDIDATE",
        "WAIT_BUY",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention LOCAL_TRADINGVIEW BUY candidate watch" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local invokes LOCAL_TRADINGVIEW BUY candidate watch test" -Text $verifyText -Pattern "test_local_tradingview_buy_candidate_watch\.ps1"
Assert-Contains -Name "verify local guards LOCAL_TRADINGVIEW BUY candidate watch SSH target" -Text $verifyText -Pattern "scripts/watch_local_tradingview_buy_candidate_ssh\.ps1"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MaxAttempts", "0") `
    -ExpectedPattern "MaxAttempts must be between 1 and 96"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-SleepSeconds", "-1") `
    -ExpectedPattern "SleepSeconds must be between 0 and 3600"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "10") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[local-tradingview-buy-candidate-watch-test] OK"
