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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_blocker_priority_board_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$bundleText = Get-Content -Raw -LiteralPath $bundlePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-open-blocker-priority-board] read-only board",
        "GRID_OPEN_BLOCKER_PRIORITY_BOARD",
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
        "smoke_live_origin_delta_local.ps1",
        "READY_FOR_GRID_OPEN_BLOCKER_PRIORITY_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_BLOCKER_PRIORITY_BOARD_NOT_MUTATION",
        "DEPLOY_CURRENT_MAIN_AND_RERUN_READ_ONLY_VERIFICATION_AFTER_SEPARATE_AUTHORIZATION",
        "CONTINUE_GRID_OPEN_REVIEW_WITH_RUNTIME_CURRENT_TOOLING_DRIFT",
        "WAIT_EVENT_RISK_R0_BEFORE_ENV_OR_CREATEGRID_REVIEW",
        "RESOLVE_TOP_GRID_OPEN_BLOCKER_AND_RERUN",
        "SPLIT_ACCEPTANCE_NOT_PASSING",
        "GRID_ENV_DIFF_NOT_APPLIED",
        "EVENT_RISK_NOT_R0",
        "REPLAY_SCORE_BELOW_GRID_REVIEW_FLOOR",
        "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP",
        "GRID_BACKGROUND_MUTATION_FLAGS_NOT_DISABLED",
        "GRID_OPERATOR_AUTHORIZATION_CHAIN_NOT_READY",
        "openReadinessScorePct",
        "authorizationReadinessPhase",
        "preEnvAuthorizationBundleReady",
        "preEnvAuthorizationRequestReady",
        "postEnvAuthorizationBundleReady",
        "postEnvAuthorizationRequestReady",
        "splitRuntimeCurrentForGridOpen",
        "splitToolingOnlyCurrentnessFollowUp",
        "source_origin_delta",
        "origin_delta_status",
        "origin_runtime_delta_files",
        "grid_split_runtime_current_for_grid_open",
        "grid_split_tooling_only_currentness_follow_up",
        "grid_authorization_readiness_phase",
        "grid_pre_env_authorization_request_ready",
        "grid_post_env_authorization_request_ready",
        "grid_open_readiness_score_pct",
        "grid_open_readiness_passed_gates",
        "grid_open_blocker_priority_top_blocker",
        "grid_open_blocker_priority_ranked_blockers",
        "grid_open_blocker_priority_gate_checks",
        "grid_open_blocker_priority_board_packet",
        "gridOpenableNow",
        "grid_openable_now",
        "requiredBeforeOpen",
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
        "notAuthorization=read-only grid open blocker priority board only",
        "RequireBoardReady"
    )) {
    Assert-Contains -Name "grid open blocker priority board script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "Get-PropertyOrNull",
        "Get-FirstPresentValue",
        "Get-FirstPresentValue -Values",
        "sourceAuthorizationRequestPacketSummary",
        "sourcePreEnvAuthorizationRequestPacketSummary",
        "sourceAuthorizationBundlePacketSummary",
        "sourceCapitalOverridePacketSummary",
        "sourceCreateAuthorizationPreflightPacketSummary",
        "refreshedCreateGridInputsMustMatch",
        "reviewedCreateGridInputs"
    )) {
    Assert-Contains -Name "grid blocker board nested createGrid input fallback" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_POST_ENV_READ_ONLY_VERIFICATION_BUNDLE", "grid_post_env_read_only_verification_packet", "verificationBlockers", "splitAcceptanceFailureSummary")) {
    Assert-Contains -Name "post-env verification bundle supports blocker board" -Text $bundleText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid open blocker priority board must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_open_blocker_priority_board_ssh.ps1",
        "GRID_OPEN_BLOCKER_PRIORITY_BOARD",
        "grid_open_readiness_score_pct",
        "grid_open_blocker_priority_ranked_blockers",
        "SPLIT_ACCEPTANCE_NOT_PASSING",
        "EVENT_RISK_NOT_R0",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid open blocker priority board" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-blocker-board-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad grid count" -Pattern "GridCount must be between 2 and 24" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCount 1
    }
    Assert-FailsWith -Name "bad stop out" -Pattern "StopOutPct must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPct 50
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-open-blocker-priority-board-test] OK"
