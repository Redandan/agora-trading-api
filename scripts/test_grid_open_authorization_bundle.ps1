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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_authorization_bundle_ssh.ps1"
$capitalPath = Join-Path $PSScriptRoot "prepare_grid_capital_override_review_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$capitalText = Get-Content -Raw -LiteralPath $capitalPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-open-authorization-bundle] read-only packet",
        "GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET",
        "prepare_grid_capital_override_review_packet_ssh.ps1",
        "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_MUTATION",
        "PREPARE_SEPARATE_GRID_OPEN_OPERATOR_AUTHORIZATIONS",
        "REFRESH_GRID_OPEN_AUTHORIZATION_BUNDLE_EVIDENCE",
        "RESOLVE_GRID_OPEN_AUTHORIZATION_BUNDLE_BLOCKERS",
        "authorizationLanes",
        "trendGateClearanceAccepted",
        "trendLaneReady",
        "CLEAR_BY_FRESH_TREND_GATE_NOT_MUTATION",
        "fresh trend gate clearance accepted",
        "separate trend override not required",
        "remainingExecutionBlockers",
        "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE",
        "OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED",
        "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED",
        "OPERATOR_EXISTING_ACTIVE_GRID_OKX_ORDER_PATH_ACTIVATION_AUTHORIZATION_REQUIRED",
        "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED",
        "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED",
        "existingActiveGridActivationReview",
        "existing ACTIVE grid OKX order-path activation",
        "requiredOperatorAuthorizationSequence",
        "postEnvReadOnlyVerification",
        "gridOpenAuthorizationBundleReady",
        "trendOverrideAllowed = `$false",
        "capitalOverrideAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "gridOpenAllowed = `$false",
        "gridMutationAllowed = `$false",
        "trend_override_allowed=false",
        "capital_override_allowed=false",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid open authorization bundle only",
        "RequireBundleReady"
    )) {
    Assert-Contains -Name "grid open authorization bundle script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_CAPITAL_OVERRIDE_REVIEW_PACKET", "capitalOverrideReviewReady", "capitalOverrideRequest")) {
    Assert-Contains -Name "capital override packet supports authorization bundle" -Text $capitalText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid open authorization bundle must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_open_authorization_bundle_ssh.ps1",
        "GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET",
        "grid_open_authorization_bundle_status",
        "grid_open_authorization_bundle_ready",
        "grid_open_allowed=false",
        "create_grid_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid open authorization bundle" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-auth-bundle-key-" + [Guid]::NewGuid().ToString("N"))
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

Write-Host "[grid-open-authorization-bundle-test] OK"
