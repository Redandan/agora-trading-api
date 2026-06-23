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

    $script = Join-Path $PSScriptRoot "smoke_strategy574_near_threshold_shadow_observation_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for strategy574 near-threshold shadow observation test" }

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
        throw "strategy574 near-threshold shadow observation accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "strategy574 near-threshold shadow observation did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy574 near-threshold shadow observation reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_strategy574_near_threshold_shadow_observation_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$acceptancePath = Join-Path $repoRoot "docs/split-acceptance-status.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $acceptancePath
) -join "`n"

foreach ($marker in @(
        "[strategy574-near-threshold-shadow-observation] read-only production evidence check",
        "scope=READ_ONLY",
        "bt_decision_audit",
        "md_kline",
        "near_threshold_rows",
        "reviewable_forward_rows",
        "false_positive_rows",
        "false_positive_rate_pct",
        "avg_forward_return_pct",
        "avg_forward_mfe_pct",
        "avg_forward_mae_pct",
        "TP/SL/Fee Proxy",
        "tp_hit_rows",
        "sl_hit_rows",
        "ambiguous_same_bar_rows",
        "avg_net_return_pct",
        "oco_preflight_status",
        "strategy574_near_threshold_shadow_observation_plan",
        "strategy574_near_threshold_shadow_recommendation",
        "STRATEGY574_NEAR_THRESHOLD_SHADOW_OBSERVATION_CANDIDATE_NOT_LIVE",
        "STRATEGY574_NEAR_THRESHOLD_FORWARD_ALPHA_REVIEW_OCO_REQUIRED",
        "notAuthorization=read-only evidence only",
        "does not authorize strategy threshold changes",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "strategy574 near-threshold shadow observation marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        "UPDATE ",
        "INSERT ",
        "DELETE "
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "strategy574 near-threshold shadow observation must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "smoke_strategy574_near_threshold_shadow_observation_ssh.ps1",
        "strategy574 near-threshold shadow observation",
        "strategy574_near_threshold_shadow_recommendation",
        "false_positive_rows",
        "oco_preflight_status",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention strategy574 near-threshold shadow observation" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ForwardHours", "0") `
    -ExpectedPattern "ForwardHours must be between 1 and 168"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-TakeProfitPct", "0") `
    -ExpectedPattern "TakeProfitPct must be greater than 0 and at most 20"

Write-Host "[strategy574-near-threshold-shadow-observation-smoke-test] OK"
