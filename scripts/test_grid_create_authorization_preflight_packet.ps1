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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_create_authorization_preflight_packet_ssh.ps1"
$operatorPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$envDiffPath = Join-Path $PSScriptRoot "prepare_grid_env_diff_preflight_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$operatorText = Get-Content -Raw -LiteralPath $operatorPath
$envDiffText = Get-Content -Raw -LiteralPath $envDiffPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-create-authorization-preflight] read-only packet",
        "GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET",
        "prepare_grid_open_operator_packet_ssh.ps1",
        "prepare_grid_env_diff_preflight_packet_ssh.ps1",
        "READY_FOR_GRID_CREATE_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_CREATE_AUTHORIZATION_PREFLIGHT_NOT_MUTATION",
        "PREPARE_SEPARATE_CREATEGRID_AUTHORIZATION_AFTER_ENV_DIFF",
        "ADJUST_CREATEGRID_INPUTS_OR_SEPARATE_CAP_OVERRIDE",
        "REFRESH_GRID_CREATE_AUTHORIZATION_PREFLIGHT_EVIDENCE",
        "RESOLVE_GRID_CREATE_AUTHORIZATION_PREFLIGHT_BLOCKERS",
        "reviewedCreateGridInputs",
        "capitalCapCheck",
        "effectiveReviewCapitalCapUsdt",
        "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP",
        "replayCheck",
        "REPLAY_SCORE_BELOW_CREATEGRID_REVIEW_FLOOR",
        "requiredBeforeCreateGrid",
        "postCreateReadOnlyVerification",
        "createAuthorizationReviewReady",
        "AcceptAlreadyAppliedEnvDiff",
        "createGridAllowed = `$false",
        "gridOpenAllowed = `$false",
        "gridMutationAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid create authorization preflight only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid create authorization preflight script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("createGridReviewInputs", "combinedOverrideRiskEnvelope", "effectiveReviewCapitalCapUsdt")) {
    Assert-Contains -Name "operator packet supports create authorization preflight" -Text $operatorText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_ENV_DIFF_PREFLIGHT_PACKET", "envDiffReviewReady", "postApplyReadOnlyVerification")) {
    Assert-Contains -Name "env diff packet supports create authorization preflight" -Text $envDiffText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid create authorization preflight packet must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_create_authorization_preflight_packet_ssh.ps1",
        "GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET",
        "grid_create_authorization_preflight_status",
        "grid_create_authorization_review_ready",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid create authorization preflight packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-create-auth-key-" + [Guid]::NewGuid().ToString("N"))
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

Write-Host "[grid-create-authorization-preflight-packet-test] OK"
