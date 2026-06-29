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

    $script = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop dry-run activation packet test"
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
        throw "trailing-stop dry-run activation packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop dry-run activation packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop dry-run activation packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1"
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
        "[trailing-stop-dry-run-activation-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_trailing_stop_operator_review_packet_ssh.ps1",
        "audit_live_readiness_ssh.ps1",
        "getTrailingStopStatus",
        "getStrategyConfig",
        "http://127.0.0.1:{os.environ['PORT']}/api/mcp",
        "TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED",
        "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED",
        "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_REVIEW_ONLY",
        "REQUEST_OPERATOR_AUTHORIZATION_FOR_DRY_RUN_ENV_DIFF",
        "REQUEST_SEPARATE_STRATEGY_OPT_IN_AUTHORIZATION_BEFORE_ENV_DIFF",
        "proposedSeparateStrategyOptInReview",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "trailing_stop_dry_run_activation_review_packet",
        "trailing_stop_dry_run_activation_status",
        "trailing_stop_activation_strategy_opt_in_required",
        "trailing_stop_activation_allowed=false",
        "trailing_stop_strategy_opt_in_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "requiredSeparateAuthorization",
        "postActivationReadOnlyVerification",
        "does not call setTrailingStopOptIn",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe",
        "notAuthorization=read-only trailing-stop dry-run activation review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "trailing dry-run activation marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "trailing-stop dry-run activation packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_trailing_stop_dry_run_activation_review_packet_ssh.ps1",
        "TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET",
        "trailing_stop_dry_run_activation_review_packet",
        "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED",
        "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "read-only verification only"
    )) {
    Assert-Contains -Name "docs mention trailing dry-run activation packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Days", "999") `
    -ExpectedPattern "Days must be between 1 and 90"

Write-Host "[trailing-stop-dry-run-activation-review-packet-test] OK"
