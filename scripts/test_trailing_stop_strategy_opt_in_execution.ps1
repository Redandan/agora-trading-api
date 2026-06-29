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

    $script = Join-Path $PSScriptRoot "execute_trailing_stop_strategy_opt_in_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop strategy opt-in execution test"
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
        throw "trailing-stop strategy opt-in execution accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop strategy opt-in execution did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop strategy opt-in execution reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "execute_trailing_stop_strategy_opt_in_ssh.ps1"
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
        "[trailing-stop-strategy-opt-in-execution] operator wrapper",
        "CONTROLLED_STRATEGY_CONFIG_WRITE_ONLY",
        "execute_trailing_stop_strategy_opt_in_ssh.ps1",
        "TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET",
        "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION",
        "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW",
        "AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
        "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "EXECUTE_TRAILING_STOP_OPT_IN_",
        "setTrailingStopOptIn",
        "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1",
        "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1",
        "trailing_stop_strategy_opt_in_execution_packet",
        "trailing_stop_strategy_opt_in_execution_status",
        "trailing_stop_strategy_opt_in_execution_decision",
        "trailing_stop_strategy_opt_in_execution_write_performed",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe",
        "TRADING_MCP_KEY",
        "http://127.0.0.1:{os.environ['PORT']}/api/mcp",
        '"method": "tools/call"',
        '*>&1'
    )) {
    Assert-Contains -Name "trailing strategy opt-in execution marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "createGrid",
        "placeOrder",
        "modifyOco",
        "closePosition",
        "sendTelegram"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing-stop strategy opt-in execution wrapper must not contain unrelated mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "execute_trailing_stop_strategy_opt_in_ssh.ps1",
        "TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET",
        "trailing_stop_strategy_opt_in_execution_packet",
        "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION",
        "EXECUTE_TRAILING_STOP_OPT_IN_574",
        "setTrailingStopOptIn",
        "controlled strategy-config write",
        "does not change production env",
        "post-opt-in readiness"
    )) {
    Assert-Contains -Name "docs mention trailing strategy opt-in execution wrapper" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempReviewLog = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-opt-in-execution-review-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $reviewPacket = [pscustomobject]@{
        packetType = "TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET"
        status = "READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION"
        symbol = "BTCUSDT"
        sourceActivationStatus = "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED"
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = "false"
        currentGlobalDryRun = "true"
        recommendedStrategyId = 574
        proposedSeparateMcpWrite = "setTrailingStopOptIn(strategyId=574, enabled=true, notes='trailing dry-run observation only; no order or OCO mutation')"
        rollbackMcpWrite = "setTrailingStopOptIn(strategyId=574, enabled=false, notes='rollback trailing dry-run opt-in observation')"
        decision = "REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION"
        missingRequirements = @()
    }
    Set-Content -LiteralPath $tempReviewLog -Encoding UTF8 -Value @(
        "trailing_stop_strategy_opt_in_review_status=READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION",
        "trailing_stop_strategy_opt_in_review_decision=REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
        ("trailing_stop_strategy_opt_in_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $reviewPacket))
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing strategy opt-in execution source-log replay test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceReviewLog $tempReviewLog -StrategyId 574 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "trailing strategy opt-in execution dry-run replay failed:`n$text"
    }
    foreach ($marker in @(
            "source_review_status=READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION",
            "source_review_decision=REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
            "trailing_stop_acceptance=PASS",
            "trailing_stop_improvement_pct=52.753%",
            "trailing_stop_strategy_opt_in_execution_strategy_id=574",
            "trailing_stop_strategy_opt_in_execution_required_confirm_text=EXECUTE_TRAILING_STOP_OPT_IN_574",
            "trailing_stop_strategy_opt_in_execution_execute_requested=false",
            "trailing_stop_strategy_opt_in_execution_write_performed=false",
            "trailing_stop_strategy_opt_in_execution_mcp_write_status=NOT_EXECUTED_DRY_RUN",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "trailing_stop_strategy_opt_in_execution_status=DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION",
            "trailing_stop_strategy_opt_in_execution_decision=AWAIT_EXPLICIT_EXECUTE_CONFIRMATION",
            '"packetType":"TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET"',
            '"strategyOptInWritePerformed":false',
            '"orderAllowed":false',
            '"ocoMutationAllowed":false'
        )) {
        Assert-Contains -Name "trailing strategy opt-in execution dry-run replay" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "mcp_write_status=OK|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing strategy opt-in execution dry-run replay unexpectedly invoked remote write/SSH:`n$text"
    }

    Assert-FailsBeforeSsh `
        -Arguments @("-SourceReviewLog", $tempReviewLog, "-StrategyId", "574", "-Execute", "-ConfirmText", "WRONG") `
        -ExpectedPattern "ConfirmText must equal EXECUTE_TRAILING_STOP_OPT_IN_574"
} finally {
    if (Test-Path -LiteralPath $tempReviewLog) {
        Remove-Item -LiteralPath $tempReviewLog -Force
    }
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "999") `
    -ExpectedPattern "Limit must be between 1 and 500"

Assert-FailsBeforeSsh `
    -Arguments @("-SourceReviewLog", ".\README.md", "-Notes", "bad'quote") `
    -ExpectedPattern "Notes contains unsupported characters"

Write-Host "[trailing-stop-strategy-opt-in-execution-test] OK"
