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

    $script = Join-Path $PSScriptRoot "watch_local_tradingview_runtime_evidence_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for LOCAL_TRADINGVIEW runtime evidence watch test" }

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
        throw "LOCAL_TRADINGVIEW runtime evidence watch accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "LOCAL_TRADINGVIEW runtime evidence watch did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "LOCAL_TRADINGVIEW runtime evidence watch reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "watch_local_tradingview_runtime_evidence_ssh.ps1"
$runtimePath = Join-Path $PSScriptRoot "smoke_runtime_evidence_rca_ssh.ps1"
$readinessPath = Join-Path $PSScriptRoot "smoke_local_tradingview_only_readiness_ssh.ps1"
$verifyLocalPath = Join-Path $PSScriptRoot "verify_local.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$webhookPath = Join-Path $repoRoot "docs/tradingview-webhook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runtimeText = Get-Content -Raw -LiteralPath $runtimePath
$readinessText = Get-Content -Raw -LiteralPath $readinessPath
$verifyText = Get-Content -Raw -LiteralPath $verifyLocalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $webhookPath
) -join "`n"

foreach ($marker in @(
        "[local-tradingview-runtime-evidence-watch] read-only bounded watcher",
        "scope=READ_ONLY",
        "active runtime log",
        "smoke_runtime_evidence_rca_ssh.ps1",
        "smoke_local_tradingview_only_readiness_ssh.ps1",
        "RunReadinessEveryAttempt",
        "RequireEvidence",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "Write-ChildFailureContext",
        "[local-tradingview-runtime-evidence-watch] child_failure",
        "...[truncated]",
        "timedOut",
        "attempt_target_interval_subscribe_count",
        "attempt_target_interval_connected_count",
        "attempt_target_interval_persisted_count",
        "attempt_target_interval_latest_persisted",
        "attempt_runtime_evidence_diagnosis",
        "attempt_target_strategy_evidence_rows",
        "attempt_target_strategy_shadow_like_rows",
        "WAIT_1D_CLOSED_K_EVENT",
        "WAIT_RUNTIME_EVIDENCE_AFTER_CLOSED_K",
        "WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED",
        "BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED",
        "EVIDENCE_UNAVAILABLE",
        "TARGET_INTERVAL_CLOSED_K_NOT_PERSISTED_SINCE_DEPLOY",
        "TARGET_STRATEGY_CANONICAL_WAIT_OR_NO_BUY_EVIDENCE_PRESENT",
        "local_tradingview_runtime_evidence_watch_status",
        "local_tradingview_runtime_evidence_watch_reason",
        "local_tradingview_runtime_evidence_watch_target_interval_persisted_count",
        "local_tradingview_runtime_evidence_watch_runtime_diagnosis",
        "local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows",
        "local_tradingview_runtime_evidence_watch_target_strategy_shadow_like_rows",
        "local_tradingview_runtime_evidence_watch_only_status",
        "local_tradingview_runtime_evidence_watch_next_action",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only LOCAL_TRADINGVIEW runtime evidence watcher only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "LOCAL_TRADINGVIEW runtime evidence watch marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "LOCAL_TRADINGVIEW runtime evidence watch must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "targetStrategyEvidenceRows",
        "targetStrategyShadowLikeRows",
        "orderSentEvidenceBlockerCount",
        "currentSignalDecision",
        "noCurrentBuyCandidateReason"
    )) {
    Assert-Contains -Name "runtime evidence RCA keeps watcher markers" -Text $runtimeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "local_tradingview_only_status",
        "local_tradingview_only_blockers",
        "next_action="
    )) {
    Assert-Contains -Name "readiness smoke keeps runtime evidence watcher markers" -Text $readinessText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "watch_local_tradingview_runtime_evidence_ssh.ps1",
        "LOCAL_TRADINGVIEW runtime evidence watcher",
        "local_tradingview_runtime_evidence_watch_status",
        "WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED",
        "BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED",
        "WAIT_1D_CLOSED_K_EVENT",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention LOCAL_TRADINGVIEW runtime evidence watch" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "verify local invokes LOCAL_TRADINGVIEW runtime evidence watch test" -Text $verifyText -Pattern "test_local_tradingview_runtime_evidence_watch\.ps1"
Assert-Contains -Name "verify local guards LOCAL_TRADINGVIEW runtime evidence watch SSH target" -Text $verifyText -Pattern "scripts/watch_local_tradingview_runtime_evidence_ssh\.ps1"

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
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-RuntimeEvidenceMinutes", "10") `
    -ExpectedPattern "RuntimeEvidenceMinutes must be between 60 and 43200"

Write-Host "[local-tradingview-runtime-evidence-watch-test] OK"
