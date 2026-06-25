Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-FailsWith {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [string]$Pattern
    )

    $failed = $false
    try {
        & $Action
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Name failed with unexpected message: $($_.Exception.Message)"
        }
    }

    if (-not $failed) {
        throw "$Name did not fail"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1"
$planPath = Join-Path $PSScriptRoot "prepare_grid_post_env_verification_plan_ssh.ps1"
$splitPath = Join-Path $PSScriptRoot "verify_split_acceptance_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$planText = Get-Content -Raw -LiteralPath $planPath
$splitText = Get-Content -Raw -LiteralPath $splitPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-post-env-read-only-verification-bundle] read-only packet",
        "GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE",
        "prepare_grid_post_env_verification_plan_ssh.ps1",
        "verify_split_acceptance_ssh.ps1",
        "prepare_grid_open_decision_snapshot_ssh.ps1",
        "prepare_grid_trend_override_review_packet_ssh.ps1",
        "prepare_grid_env_diff_preflight_packet_ssh.ps1",
        "prepare_grid_create_authorization_preflight_packet_ssh.ps1",
        "prepare_grid_open_authorization_bundle_ssh.ps1",
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "READY_FOR_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION",
        "BLOCKED_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION",
        "AWAIT_SEPARATE_CREATEGRID_AUTHORIZATION",
        "APPLY_SEPARATELY_AUTHORIZED_ENV_DIFF_AND_DEPLOY_THEN_RERUN",
        "FIX_SPLIT_ACCEPTANCE_BEFORE_CREATEGRID_REVIEW",
        "REFRESH_GRID_POST_ENV_READ_ONLY_VERIFICATION_EVIDENCE",
        "RESOLVE_GRID_POST_ENV_READ_ONLY_VERIFICATION_BLOCKERS",
        "POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE",
        "POST_ENV_TRADING_GRID_ENABLED_NOT_TRUE",
        "POST_ENV_GRID_AUTO_REBALANCE_SCHEDULER_NOT_FALSE",
        "POST_ENV_GRID_RECOVERY_NOT_FALSE",
        "POST_ENV_OKX_EARN_TOPUP_NOT_FALSE",
        "SPLIT_ACCEPTANCE_OK_MARKER_MISSING",
        "splitAcceptanceFailureSummary",
        "grid_post_env_read_only_verification_split_failure_summary",
        "does not match",
        "grid_post_env_read_only_verification_status",
        "grid_post_env_read_only_verification_ready",
        "grid_post_env_read_only_verification_env_readiness",
        "grid_post_env_read_only_verification_blockers",
        "grid_post_env_read_only_verification_missing_evidence",
        "split_acceptance_ok",
        "postEnvVerificationReady",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "gridOpenAllowed = `$false",
        "gridMutationAllowed = `$false",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid post-env verification bundle only",
        "RequireVerificationReady"
    )) {
    Assert-Contains -Name "grid post-env read-only verification bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_POST_ENV_VERIFICATION_PLAN_PACKET", "postEnvVerificationPlanReady", "requiredPostEnvCommands")) {
    Assert-Contains -Name "post-env plan supports verification bundle" -Text $planText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("[split-acceptance] OK", "trading runtime log smoke", "cross-service live MCP ownership")) {
    Assert-Contains -Name "split acceptance supports post-env verification bundle" -Text $splitText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid post-env read-only verification bundle must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
        "GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE",
        "grid_post_env_read_only_verification_status",
        "grid_post_env_read_only_verification_ready",
        "POST_ENV_TRADING_OKX_ENABLED_NOT_TRUE",
        "deploy_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid post-env read-only verification bundle" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-post-env-bundle-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad grid count" -Pattern "GridCount must be between 4 and 24" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCount 2
    }
    Assert-FailsWith -Name "bad stop out" -Pattern "StopOutPct must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPct 50
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-post-env-read-only-verification-bundle-test] OK"
