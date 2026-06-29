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

    $script = Join-Path $PSScriptRoot "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop strategy opt-in packet test"
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
        throw "trailing-stop strategy opt-in packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop strategy opt-in packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop strategy opt-in packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1"
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
        "[trailing-stop-strategy-opt-in-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1",
        "TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET",
        "READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION",
        "NOT_NEEDED_STRATEGY_TRAILING_OPT_IN_ALREADY_PRESENT",
        "REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
        "setTrailingStopOptIn",
        "rollbackMcpWrite",
        "postOptInReadOnlyVerification",
        "trailing_stop_strategy_opt_in_review_packet",
        "trailing_stop_strategy_opt_in_review_status",
        "trailing_stop_strategy_opt_in_review_decision",
        "trailing_stop_strategy_opt_in_review_allowed=false",
        "trailing_stop_strategy_opt_in_change_allowed=false",
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
        "notAuthorization=read-only trailing-stop strategy opt-in review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "trailing strategy opt-in marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "createGrid",
        "placeOrder",
        "modifyOco",
        "closePosition"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing-stop strategy opt-in packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1",
        "TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET",
        "trailing_stop_strategy_opt_in_review_packet",
        "READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION",
        "REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
        "setTrailingStopOptIn",
        "post-opt-in read-only verification",
        "rollback"
    )) {
    Assert-Contains -Name "docs mention trailing strategy opt-in packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLog = Join-Path ([System.IO.Path]::GetTempPath()) ("trailing-strategy-opt-in-activation-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $activationPacket = [pscustomobject]@{
        packetType = "TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET"
        status = "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED"
        symbol = "BTCUSDT"
        strategyIds = @(485, 574)
        trailingAcceptance = "PASS"
        trailingImprovementPct = "52.753%"
        trailingDeltaPnl = "13391.79229093"
        currentGlobalEnabled = "false"
        currentGlobalDryRun = "true"
        currentOpenOcoPositions = "0"
        reviewedStrategyOptInCount = 0
        reviewedStrategyOptIn = @(
            [pscustomobject]@{ strategyId = 485; trailingStopEnabled = "false" },
            [pscustomobject]@{ strategyId = 574; trailingStopEnabled = "false" }
        )
        activationDecision = "REQUEST_SEPARATE_STRATEGY_OPT_IN_AUTHORIZATION_BEFORE_ENV_DIFF"
        missingRequirements = @("separate strategy trailingStopEnabled opt-in authorization for at least one reviewed strategy")
        nextAction = "Request separate operator authorization to set trailingStopEnabled=true for at least one reviewed strategy."
    }
    Set-Content -LiteralPath $tempLog -Encoding UTF8 -Value @(
        "trailing_stop_dry_run_activation_status=BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED",
        ("trailing_stop_dry_run_activation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $activationPacket))
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing strategy opt-in source-log replay test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SourceLog $tempLog -PreferredStrategyId 574 -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "trailing strategy opt-in packet failed source-log replay:`n$text"
    }
    foreach ($marker in @(
            "source_activation_packet_status=BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED",
            "trailing_stop_acceptance=PASS",
            "trailing_stop_improvement_pct=52.753%",
            "trailing_stop_strategy_opt_in_review_current_global_enabled=false",
            "trailing_stop_strategy_opt_in_review_current_global_dry_run=true",
            "trailing_stop_strategy_opt_in_review_current_strategy_opt_in_count=0",
            "trailing_stop_strategy_opt_in_review_recommended_strategy_id=574",
            "trailing_stop_strategy_opt_in_review_proposed_mcp_write=setTrailingStopOptIn(strategyId=574, enabled=true, notes='trailing dry-run observation only; no order or OCO mutation')",
            "trailing_stop_strategy_opt_in_review_rollback_mcp_write=setTrailingStopOptIn(strategyId=574, enabled=false, notes='rollback trailing dry-run opt-in observation')",
            "trailing_stop_strategy_opt_in_review_status=READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION",
            "trailing_stop_strategy_opt_in_review_decision=REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION",
            '"packetType":"TRAILING_STOP_STRATEGY_OPT_IN_REVIEW_PACKET"',
            '"status":"READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION"',
            '"recommendedStrategyId":574',
            '"strategyOptInChangeAllowedByThisPacket":false',
            '"orderAllowed":false',
            '"ocoMutationAllowed":false',
            "notAuthorization=read-only trailing-stop strategy opt-in review packet only"
        )) {
        Assert-Contains -Name "trailing strategy opt-in source-log replay" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing strategy opt-in source-log replay unexpectedly invoked SSH:`n$text"
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

Write-Host "[trailing-stop-strategy-opt-in-review-packet-test] OK"
