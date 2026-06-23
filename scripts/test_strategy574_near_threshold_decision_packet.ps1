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

    $script = Join-Path $PSScriptRoot "prepare_strategy574_near_threshold_decision_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy574 near-threshold packet test" }

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
        throw "strategy574 near-threshold packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "strategy574 near-threshold packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed|child_start") {
        throw "strategy574 near-threshold packet reached SSH/child before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy574_near_threshold_decision_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$acceptancePath = Join-Path $repoRoot "docs/split-acceptance-status.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $acceptancePath
) -join "`n"

foreach ($marker in @(
        "[strategy574-near-threshold-decision-packet] read-only packet",
        "scope=READ_ONLY",
        "smoke_signal_eval_no_buy_generation_ssh.ps1",
        "STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET",
        "READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE",
        "PREPARE_STRATEGY574_NEAR_THRESHOLD_SHADOW_DECISION_REVIEW",
        "strategy574_near_threshold_decision_packet",
        "strategy574_near_threshold_decision_status",
        "strategy574_threshold_gap_row_count",
        "strategy574_min_buy_gap",
        "strategy574_shadow_observation_review_allowed",
        "strategy_threshold_change_allowed=false",
        "strategy_activation_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "notAuthorization=read-only strategy574 near-threshold decision packet only",
        "SignalEvalNoBuyLogPath",
        "RequireReady",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "strategy574 near-threshold packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "strategy574 near-threshold packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_strategy574_near_threshold_decision_packet_ssh.ps1",
        "STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET",
        "strategy574_near_threshold_decision_status",
        "READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE",
        "strategy574 near-threshold decision packet",
        "strategy_threshold_change_allowed=false",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention strategy574 near-threshold packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-NearThresholdMaxGap", "21") `
    -ExpectedPattern "NearThresholdMaxGap must be between 0 and 20"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("strategy574-near-threshold-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$signalLog = Join-Path $tempDir "signal-eval-no-buy.log"
try {
    Set-Content -LiteralPath $signalLog -Encoding UTF8 -Value @(
        "[signal-eval-no-buy-generation] read-only production DB evidence check",
        "scope=READ_ONLY",
        "Signal Eval No-Buy Generation Summary:",
        "  signal_eval_rows=2797",
        "  buy_like_signal_eval_rows=0",
        "  no_buy_signal_eval_rows=2797",
        "  strategy_decision_context_rows=2268",
        "  execution_hold_rows=2797",
        "signal_eval_threshold_gap_distribution:",
        "  - strategy=574 interval=1h indicator=market_entropy_index count=207 avg_mih_value=69.0000 avg_buy_threshold=70.0000 avg_buy_gap=1.0000 min_buy_gap=1.0000",
        "  - strategy=566 interval=1h indicator=etf_pressure_index count=207 avg_mih_value=51.0000 avg_buy_threshold=60.0000 avg_buy_gap=9.0000 min_buy_gap=9.0000",
        "Conclusion:",
        "  signal_eval_no_buy_generation_recommendation=NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT",
        "[signal-eval-no-buy-generation] OK read-only check complete"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy574 near-threshold packet test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SignalEvalNoBuyLogPath $signalLog -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "strategy574 near-threshold packet failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "source_signal_eval_no_buy=existing-log exitCode=0",
            "signal_eval_no_buy_generation_recommendation=NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT",
            "strategy574_threshold_gap_row_count=1",
            "strategy574_threshold_gap_indicator=market_entropy_index",
            "strategy574_min_buy_gap=1.0000",
            "strategy574_near_threshold=true",
            "strategy574_near_threshold_primary_decision=PREPARE_STRATEGY574_NEAR_THRESHOLD_SHADOW_DECISION_REVIEW",
            "strategy574_shadow_observation_review_allowed=true",
            "strategy_threshold_change_allowed=false",
            "strategy_activation_allowed=false",
            "tiny_live_order_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "strategy574_near_threshold_decision_status=READY_FOR_STRATEGY574_NEAR_THRESHOLD_SHADOW_REVIEW_NOT_LIVE",
            '"packetType":"STRATEGY574_NEAR_THRESHOLD_DECISION_PACKET"',
            '"strategyThresholdChangeAllowed":false',
            '"dataFreshnessPolicyChangeAllowed":false',
            "notAuthorization=read-only strategy574 near-threshold decision packet only"
        )) {
        Assert-Contains -Name "strategy574 near-threshold temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed|child_start") {
        throw "strategy574 near-threshold packet unexpectedly invoked SSH or fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) { Remove-Item -LiteralPath $tempDir -Recurse -Force }
}

Write-Host "[strategy574-near-threshold-decision-packet-test] OK"
