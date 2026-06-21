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

    $script = Join-Path $PSScriptRoot "prepare_profit_runtime_deploy_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit runtime deploy packet test"
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
        throw "profit runtime deploy packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit runtime deploy packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit runtime deploy packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_runtime_deploy_review_packet_ssh.ps1"
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
        "[profit-runtime-deploy-review-packet] read-only packet",
        "scope=READ_ONLY",
        "smoke_live_origin_delta_local.ps1",
        "smoke_post_deploy_profit_validation_ssh.ps1",
        "origin_delta_status",
        "origin_runtime_delta_paths",
        "origin_runtime_delta_impact",
        "monthlyPnlTotalUsdt",
        "top_profit_improvement_candidate",
        "deploy_required_before_post_deploy_profit_validation",
        "post_deploy_profit_validation_status",
        "profit_runtime_deploy_packet_missing_requirements",
        "profit_runtime_deploy_review_packet",
        "profit_runtime_deploy_packet_status",
        "READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED",
        "NO_RUNTIME_DEPLOY_REQUIRED_FROM_CURRENTNESS",
        "NO_EVIDENCE",
        "requiredPostDeployReadOnlyCommands",
        "prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady",
        "prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady",
        "notAuthorization=read-only deploy review packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit runtime deploy packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "profit runtime deploy packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_profit_runtime_deploy_review_packet_ssh.ps1",
        "profit runtime deploy review packet",
        "profit_runtime_deploy_packet_status",
        "READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit runtime deploy packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

Write-Host "[profit-runtime-deploy-review-packet-test] OK"
