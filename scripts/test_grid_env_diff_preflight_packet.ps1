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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_env_diff_preflight_packet_ssh.ps1"
$operatorPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$trendOverridePath = Join-Path $PSScriptRoot "prepare_grid_trend_override_review_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$operatorText = Get-Content -Raw -LiteralPath $operatorPath
$trendOverrideText = Get-Content -Raw -LiteralPath $trendOverridePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-env-diff-preflight] read-only packet",
        "GRID_ENV_DIFF_PREFLIGHT_PACKET",
        "prepare_grid_open_operator_packet_ssh.ps1",
        "prepare_grid_trend_override_review_packet_ssh.ps1",
        "okxGridEnvPreflightEnvelope",
        "READY_FOR_GRID_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_ENV_DIFF_PREFLIGHT_NOT_MUTATION",
        "PREPARE_SEPARATE_GRID_ENV_DIFF_AUTHORIZATION",
        "REFRESH_GRID_ENV_PREFLIGHT_EVIDENCE",
        "RESOLVE_GRID_ENV_DIFF_PREFLIGHT_BLOCKERS",
        "envReadiness",
        "credentialsReady",
        "tradingOkxEnabled",
        "tradingGridEnabled",
        "gridAutoRebalanceSchedulerEnabled",
        "gridRecoveryEnabled",
        "okxEarnTopupEnabled",
        "proposedSeparateEnvDiff",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false",
        "postApplyReadOnlyVerification",
        "grid_env_diff_preflight_status",
        "grid_env_diff_review_ready",
        "grid_env_diff_preflight_proposed_env_diff",
        "envDiffReviewReady",
        "AcceptAlreadyAppliedEnvDiff",
        "acceptAlreadyAppliedEnvDiff",
        "envDiffAlreadyApplied",
        "alreadyAppliedEnvDiffFlags",
        "pendingSeparateEnvDiff",
        "postEnvDiffBlockers",
        "grid_env_diff_preflight_already_applied_flags",
        "grid_env_diff_preflight_pending_env_diff",
        "grid_env_diff_preflight_post_env_diff_blockers",
        "GRID_ENV_DIFF_ALREADY_APPLIED_USE_POST_ENV_REVIEW",
        "TRADING_OKX_ENABLED_NOT_TRUE_FOR_POST_ENV_REVIEW",
        "TRADING_GRID_ENABLED_NOT_TRUE_FOR_POST_ENV_REVIEW",
        "GRID_AUTO_REBALANCE_SCHEDULER_NOT_FALSE_FOR_POST_ENV_REVIEW",
        "GRID_RECOVERY_NOT_FALSE_FOR_POST_ENV_REVIEW",
        "OKX_EARN_TOPUP_NOT_FALSE_FOR_POST_ENV_REVIEW",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "production_env_change_allowed=false",
        "deploy_allowed=false",
        "grid_open_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "OKX_CREDENTIALS_NOT_READY",
        "EVENT_RISK_NOT_R0_FOR_GRID_ENV_DIFF",
        "TREND_OVERRIDE_REVIEW_NOT_READY_FOR_ENV_DIFF",
        "GRID_AUTO_REBALANCE_SCHEDULER_NOT_FALSE",
        "GRID_RECOVERY_NOT_FALSE",
        "OKX_EARN_TOPUP_NOT_FALSE",
        "notAuthorization=read-only grid env diff preflight only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid env diff preflight script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("okxGridEnvPreflightEnvelope", "requiredEnvDiff", "credentialsReady")) {
    Assert-Contains -Name "operator packet supports env preflight" -Text $operatorText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_TREND_OVERRIDE_REVIEW_PACKET", "trendOverrideReviewReady")) {
    Assert-Contains -Name "trend override packet supports env preflight" -Text $trendOverrideText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid env diff preflight packet must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_env_diff_preflight_packet_ssh.ps1",
        "GRID_ENV_DIFF_PREFLIGHT_PACKET",
        "grid_env_diff_preflight_status",
        "grid_env_diff_review_ready",
        "production_env_change_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid env diff preflight packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-env-diff-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad stop out" -Pattern "StopOutPct must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPct 50
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-env-diff-preflight-packet-test] OK"
