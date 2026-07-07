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

    $script = Join-Path $PSScriptRoot "smoke_trailing_stop_parameter_sweep_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for trailing-stop parameter sweep smoke test"
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
        throw "trailing-stop parameter sweep smoke accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "trailing-stop parameter sweep smoke did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "trailing-stop parameter sweep smoke reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_trailing_stop_parameter_sweep_ssh.ps1"
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
        "\[trailing-stop-parameter-sweep\] read-only production MCP check",
        "scope=READ_ONLY",
        "read-only boundary",
        "analyzeTrailingStopParameterSweep",
        "currentPolicy=breakevenAtr",
        "parameterGrid=breakevenAtr",
        "currentPolicySummary=policy=",
        "bestPolicySummary=policy=",
        "bestVsCurrentDeltaPnl=",
        "topCandidates:",
        "REVIEW_PARAMETER_CANDIDATE_NOT_LIVE",
        "NO_BETTER_PARAMETER_FOUND_IN_SWEEP",
        "not apply it without separate design/deploy/live approval",
        "http://127.0.0.1:{os.environ\['PORT'\]}/api/mcp",
        "TRADING_MCP_KEY",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-McpSmokeTokenSafe",
        "Assert-McpSmokeListSafe",
        "sed '1s/\^\\xEF\\xBB\\xBF//'",
        "bash -s"
    )) {
    Assert-Contains -Name "trailing-stop parameter sweep smoke marker" -Text $scriptText -Pattern $marker
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRAILING_STOP_ENABLED=true",
        "trailing-stop.enabled=true",
        "setTrailingStopOptIn",
        "modifyOco",
        "createGrid",
        "placeOrder",
        "TRADING_OKX_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "trailing-stop parameter sweep smoke must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "smoke_trailing_stop_parameter_sweep_ssh.ps1",
        "trailing-stop parameter sweep",
        "analyzeTrailingStopParameterSweep",
        "READ_ONLY",
        "REVIEW_PARAMETER_CANDIDATE_NOT_LIVE",
        "does not change scheduler constants"
    )) {
    Assert-Contains -Name "docs mention trailing-stop parameter sweep" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TopN", "99") `
    -ExpectedPattern "TopN must be between 1 and 20"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TrailingDistanceMultiples", "0.5;echo bad") `
    -ExpectedPattern "TrailingDistanceMultiples contains unsupported characters"

Write-Host "[trailing-stop-parameter-sweep-smoke-test] OK"
