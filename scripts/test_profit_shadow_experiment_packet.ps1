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

    $script = Join-Path $PSScriptRoot "prepare_profit_shadow_experiment_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit shadow experiment packet test"
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
        throw "profit shadow experiment packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit shadow experiment packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit shadow experiment packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_shadow_experiment_packet_ssh.ps1"
$gatePath = Join-Path $PSScriptRoot "prepare_profit_experiment_gate_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$gateText = Get-Content -Raw -LiteralPath $gatePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-shadow-experiment-packet] read-only packet preflight",
        "scope=READ_ONLY",
        "prepare_profit_experiment_gate_ssh.ps1",
        "profit_improvement_review_decision",
        "deploy_required_before_profit_shadow_packet",
        "shadow_experiment_review_allowed",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "profit_shadow_packet_missing_requirements",
        "profit_shadow_experiment_packet",
        "profit_shadow_packet_status",
        "READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE",
        "BLOCKED_WAIT_REPLAY_EVIDENCE",
        "OPERATOR_REVIEW_REQUIRED_READ_ONLY",
        "NO_EVIDENCE",
        "RequireReady",
        "canDraftShadowExperimentReview",
        "profit review decision missing field",
        "Profit shadow experiment packet is not ready",
        "notAuthorization=read-only packet preflight only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "profit shadow packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "shadow_experiment_review_allowed",
        "live_policy_change_allowed=false",
        "profit_improvement_review_decision",
        "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "profit experiment gate supports packet" -Text $gateText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_shadow_experiment_packet_ssh.ps1",
        "profit shadow experiment packet",
        "profit_shadow_packet_status",
        "READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention profit shadow packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

Write-Host "[profit-shadow-experiment-packet-test] OK"
