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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "prepare_grid_open_authorization_bundle_ssh.ps1"
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
        "[grid-open-operator-authorization-request] read-only packet",
        "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET",
        "prepare_grid_open_authorization_bundle_ssh.ps1",
        "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION",
        "AWAIT_SEPARATE_OPERATOR_AUTHORIZATIONS",
        "REFRESH_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE",
        "RESOLVE_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_BLOCKERS",
        "authorizationRequestLines",
        "authorizationRequestReady",
        "remainingExecutionBlockers",
        "bundleBlockers",
        "capitalHardBlockers",
        "envReviewBlockers",
        "createReviewBlockers",
        "trendHardBlockers",
        "Add-UniqueValues",
        "Get-StringArray",
        "reviewedCreateGridInputs",
        "capitalOverrideRequest",
        "proposedSeparateEnvDiff",
        "postEnvReadOnlyVerification",
        "trend-regime override",
        "capital-cap override",
        "production env diff",
        "deploy/restart",
        "createGrid review",
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
        "notAuthorization=read-only grid open operator authorization request only",
        "RequireRequestReady"
    )) {
    Assert-Contains -Name "grid open operator authorization request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET", "gridOpenAuthorizationBundleReady", "remainingExecutionBlockers")) {
    Assert-Contains -Name "authorization bundle supports request packet" -Text $bundleText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid open operator authorization request must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_open_operator_authorization_request_ssh.ps1",
        "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET",
        "grid_open_operator_authorization_request_status",
        "grid_open_operator_authorization_request_ready",
        "grid_open_allowed=false",
        "create_grid_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid open operator authorization request" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-auth-request-key-" + [Guid]::NewGuid().ToString("N"))
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

Write-Host "[grid-open-operator-authorization-request-test] OK"
