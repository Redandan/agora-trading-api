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

    $script = Join-Path $PSScriptRoot "prepare_post_fix_strategy_monitoring_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for post-fix strategy monitoring packet test" }

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
        throw "post-fix strategy monitoring packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "post-fix strategy monitoring packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "post-fix strategy monitoring packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_post_fix_strategy_monitoring_packet_ssh.ps1"
$watchPath = Join-Path $PSScriptRoot "watch_local_tradingview_buy_candidate_ssh.ps1"
$dedupPath = Join-Path $PSScriptRoot "smoke_strategy508_entry_dedup_exposure_ssh.ps1"
$signalPath = Join-Path $PSScriptRoot "smoke_signal_correctness_ssh.ps1"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$watchText = Get-Content -Raw -LiteralPath $watchPath
$dedupText = Get-Content -Raw -LiteralPath $dedupPath
$signalText = Get-Content -Raw -LiteralPath $signalPath
$verifyText = Get-Content -Raw -LiteralPath $verifyLocalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
) -join "`n"

foreach ($marker in @(
        "[post-fix-strategy-monitoring] read-only 485/508 post-fix monitoring packet",
        "scope=READ_ONLY",
        "watch_local_tradingview_buy_candidate_ssh.ps1",
        "smoke_strategy508_entry_dedup_exposure_ssh.ps1",
        "smoke_signal_correctness_ssh.ps1",
        "RunFullReadinessEveryAttempt",
        "RequireNoMissedOrder",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "CURRENT_BUY_READY_MONITOR_ORDER_AND_OCO",
        "CURRENT_BUY_BLOCKED_REVIEW_REQUIRED",
        "MISSED_ORDER_REVIEW_REQUIRED",
        "WATCH_FALSE_BLOCK_RISK",
        "WATCHING_NO_CURRENT_ACTION",
        "EVIDENCE_UNAVAILABLE",
        "STRATEGY508_STAGED_ADD_SHADOW_REVIEW",
        "post_fix_strategy_monitoring_status",
        "post_fix_strategy_monitoring_local_tradingview_watch_status",
        "post_fix_strategy_monitoring_local_tradingview_candidate_status",
        "post_fix_strategy_monitoring_local_tradingview_pre_execution_blockers",
        "post_fix_strategy_monitoring_strategy508_recommendation",
        "post_fix_strategy_monitoring_strategy508_blocker_count",
        "post_fix_strategy_monitoring_verify_machine_status",
        "post_fix_strategy_monitoring_signal_source_policy_primary",
        "post_fix_strategy_monitoring_suspicious_no_buy_count",
        "post_fix_strategy_monitoring_false_block_count",
        "post_fix_strategy_monitoring_entry_dedup_skip_count",
        "post_fix_strategy_monitoring_summary_json",
        "post_fix_strategy_monitoring_next_action",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only post-fix strategy monitoring packet only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-MonitorTokenSafe",
        "Post-fix strategy monitoring evidence unavailable"
    )) {
    Assert-Contains -Name "post-fix strategy monitoring packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        "setStrategyFlags",
        "createGrid(",
        "pauseGrid(",
        "resumeGrid(",
        "sendAlert",
        " -Execute "
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "post-fix strategy monitoring packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "local_tradingview_buy_candidate_watch_status",
        "local_tradingview_buy_candidate_watch_pre_execution_blockers",
        "local_tradingview_buy_candidate_watch_effective_notional_usdt"
    )) {
    Assert-Contains -Name "LOCAL_TRADINGVIEW watch dependency marker" -Text $watchText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "strategy508_entry_dedup_exposure_recommendation",
        "strategy508_entry_dedup_blocker_count",
        "notAuthorization=read-only evidence only"
    )) {
    Assert-Contains -Name "strategy 508 EntryDedup dependency marker" -Text $dedupText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "executionMachineStatus",
        "executionSignalSourcePolicyPrimary",
        "suspiciousNoBuyCount",
        "falseBlockRiskCount",
        "[signal-correctness] OK read-only check complete"
    )) {
    Assert-Contains -Name "signal correctness dependency marker" -Text $signalText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_post_fix_strategy_monitoring_packet_ssh.ps1",
        "post_fix_strategy_monitoring_status",
        "post_fix_strategy_monitoring_local_tradingview_watch_status",
        "post_fix_strategy_monitoring_strategy508_recommendation",
        "post_fix_strategy_monitoring_verify_machine_status",
        "CURRENT_BUY_READY_MONITOR_ORDER_AND_OCO",
        "CURRENT_BUY_BLOCKED_REVIEW_REQUIRED",
        "MISSED_ORDER_REVIEW_REQUIRED",
        "notAuthorization=read-only post-fix strategy monitoring packet only",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention post-fix strategy monitoring packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local invokes post-fix strategy monitoring packet test" -Text $verifyText -Pattern "test_post_fix_strategy_monitoring_packet\.ps1"
Assert-Contains -Name "verify local guards post-fix strategy monitoring packet SSH target" -Text $verifyText -Pattern "scripts/prepare_post_fix_strategy_monitoring_packet_ssh\.ps1"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-LocalTradingViewWatchMaxAttempts", "0") `
    -ExpectedPattern "LocalTradingViewWatchMaxAttempts must be between 1 and 96"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Hours", "0") `
    -ExpectedPattern "Hours must be between 1 and 720"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Strategy508IntervalCode", "1h';echo bad") `
    -ExpectedPattern "Strategy508IntervalCode contains unsupported characters for post-fix strategy monitoring arguments"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "10") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 7200"

Write-Host "[post-fix-strategy-monitoring-packet-test] OK"
