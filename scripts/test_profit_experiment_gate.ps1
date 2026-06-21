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

function Assert-FailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern
    )

    $script = Join-Path $PSScriptRoot "prepare_profit_experiment_gate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit experiment gate test"
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
        throw "profit experiment gate accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit experiment gate did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit experiment gate reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_experiment_gate_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "smoke_profit_improvement_review_bundle_ssh.ps1"
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
        "[profit-experiment-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "smoke_profit_improvement_review_bundle_ssh.ps1",
        "profit_improvement_candidate_scorecard",
        "profit_improvement_review_decision",
        "top_profit_improvement_candidate",
        "top_profit_improvement_candidate_status",
        "deploy_required_before_profit_experiment",
        "shadow_experiment_review_allowed",
        "live_policy_change_allowed=false",
        "profit_experiment_missing_requirements",
        "profit_experiment_gate_status",
        "profit_experiment_next_action",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE",
        "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
        "OPERATOR_REVIEW_REQUIRED_READ_ONLY",
        "NO_EVIDENCE",
        "RequireReady",
        "notAuthorization=read-only gate only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Convert-JsonArrayOrEmpty",
        "Convert-JsonObjectOrNull",
        "Add-DecisionMissingRequirements",
        "missingRequirements",
        "Add-MissingRequirement"
    )) {
    Assert-Contains -Name "profit experiment gate marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "profit_improvement_candidate_scorecard",
        "profit_improvement_review_decision",
        "top_profit_improvement_candidate",
        "BLOCKED_WAIT_DEPLOY_AND_REPLAY_EVIDENCE",
        "READY_FOR_COUNTERFACTUAL_POLICY_REVIEW",
        "canDraftShadowExperimentReview"
    )) {
    Assert-Contains -Name "profit bundle supports experiment gate" -Text $bundleText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_experiment_gate_ssh.ps1",
        "profit experiment gate",
        "read-only",
        "shadow_experiment_review_allowed",
        "deploy_required_before_profit_experiment",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention profit experiment gate" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

Write-Host "[profit-experiment-gate-test] OK"
