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

    $script = Join-Path $PSScriptRoot "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop post-opt-in readiness packet test"
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
        throw "trailing-stop post-opt-in readiness packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop post-opt-in readiness packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop post-opt-in readiness packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1"
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
        "[trailing-stop-post-opt-in-readiness-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1",
        "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
        "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY",
        "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED",
        "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "WAIT_FOR_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "trailing_stop_post_opt_in_readiness_packet",
        "trailing_stop_post_opt_in_readiness_status",
        "trailing_stop_post_opt_in_readiness_decision",
        "trailing_stop_post_opt_in_expected_strategy_opt_in",
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
        'StrategyIds = $StrategyIds',
        '*>&1',
        "notAuthorization=read-only trailing-stop post-opt-in readiness packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "trailing post-opt-in readiness marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "setTrailingStopOptIn(",
        "createGrid",
        "placeOrder",
        "modifyOco",
        "closePosition"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing-stop post-opt-in readiness packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1",
        "TRAILING_STOP_POST_OPT_IN_READINESS_PACKET",
        "trailing_stop_post_opt_in_readiness_packet",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
        "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "post-opt-in readiness",
        "read-only verification"
    )) {
    Assert-Contains -Name "docs mention trailing post-opt-in readiness packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLog = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-post-opt-in-activation-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $activationPacket = [pscustomobject]@{
        packetType = "TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET"
        status = "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED"
        symbol = "BTCUSDT"
        strategyIds = @(485, 574)
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = "false"
        currentGlobalDryRun = "true"
        currentOpenOcoPositions = "0"
        reviewedStrategyOptInCount = 2
        reviewedStrategyOptIn = @(
            [pscustomobject]@{ strategyId = 485; trailingStopEnabled = "false" },
            [pscustomobject]@{ strategyId = 574; trailingStopEnabled = "true" }
        )
        activationDecision = "REQUEST_OPERATOR_AUTHORIZATION_FOR_DRY_RUN_ENV_DIFF"
        missingRequirements = @()
        nextAction = "Request separate operator authorization for dry-run env diff."
    }
    Set-Content -LiteralPath $tempLog -Encoding UTF8 -Value @(
        "trailing_stop_dry_run_activation_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED",
        ("trailing_stop_dry_run_activation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $activationPacket))
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing post-opt-in source-log replay test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLog $tempLog -ExpectedOptInStrategyId 574 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "trailing post-opt-in readiness packet failed source-log replay:`n$text"
    }
    foreach ($marker in @(
            "source_activation_packet_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED",
            "trailing_stop_acceptance=PASS",
            "trailing_stop_improvement_pct=52.753%",
            "trailing_stop_post_opt_in_current_global_enabled=false",
            "trailing_stop_post_opt_in_current_global_dry_run=true",
            "trailing_stop_post_opt_in_expected_strategy_id=574",
            "trailing_stop_post_opt_in_expected_strategy_opt_in=true",
            "trailing_stop_post_opt_in_env_diff_review_ready=true",
            "trailing_stop_post_opt_in_already_active_dry_run=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "trailing_stop_post_opt_in_readiness_status=READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
            "trailing_stop_post_opt_in_readiness_decision=REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION",
            '"packetType":"TRAILING_STOP_POST_OPT_IN_READINESS_PACKET"',
            '"status":"READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION"',
            '"expectedOptInStrategyId":574',
            '"expectedStrategyOptIn":true',
            '"TRAILING_STOP_ENABLED=true"',
            '"TRAILING_STOP_DRY_RUN=true"',
            '"orderAllowed":false',
            '"ocoMutationAllowed":false',
            "notAuthorization=read-only trailing-stop post-opt-in readiness packet only"
        )) {
        Assert-Contains -Name "trailing post-opt-in source-log replay" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing post-opt-in source-log replay unexpectedly invoked SSH:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLog) {
        Remove-Item -LiteralPath $tempLog -Force
    }
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "999") `
    -ExpectedPattern "Limit must be between 1 and 500"

Write-Host "[trailing-stop-post-opt-in-readiness-packet-test] OK"
