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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_post_env_verification_plan_ssh.ps1"
$requestPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$requestText = Get-Content -Raw -LiteralPath $requestPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-post-env-verification-plan] read-only packet",
        "GRID_POST_ENV_VERIFICATION_PLAN_PACKET",
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "READY_FOR_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION",
        "BLOCKED_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION",
        "AWAIT_ENV_DIFF_AUTHORIZATION_AND_DEPLOY_BEFORE_RUNNING_VERIFICATION",
        "REFRESH_GRID_POST_ENV_VERIFICATION_PLAN_EVIDENCE",
        "RESOLVE_GRID_POST_ENV_VERIFICATION_PLAN_BLOCKERS",
        "requiredPostEnvCommands",
        "postEnvPassCriteria",
        "postEnvAbortCriteria",
        "refreshedCreateGridInputsMustMatch",
        "authorizationRequestBlockers",
        "requestBlockers",
        "Add-UniqueValues",
        "Get-StringArray",
        "verify_split_acceptance_ssh.ps1",
        "prepare_grid_open_decision_snapshot_ssh.ps1",
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "postEnvVerificationPlanReady",
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
        "notAuthorization=read-only grid post-env verification plan only",
        "RequirePlanReady"
    )) {
    Assert-Contains -Name "grid post-env verification plan script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET", "authorizationRequestReady", "postEnvReadOnlyVerification")) {
    Assert-Contains -Name "operator authorization request supports post-env plan" -Text $requestText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid post-env verification plan must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_post_env_verification_plan_ssh.ps1",
        "GRID_POST_ENV_VERIFICATION_PLAN_PACKET",
        "grid_post_env_verification_plan_status",
        "grid_post_env_verification_plan_ready",
        "deploy_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid post-env verification plan" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-post-env-plan-key-" + [Guid]::NewGuid().ToString("N"))
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

Write-Host "[grid-post-env-verification-plan-test] OK"
