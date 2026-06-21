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

    $script = Join-Path $PSScriptRoot "prepare_strategy485_position_review_gate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy485 position review gate test"
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
        throw "strategy485 position review gate accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "strategy485 position review gate did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy485 position review gate reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy485_position_review_gate_ssh.ps1"
$smokePath = Join-Path $PSScriptRoot "smoke_strategy485_position_risk_ssh.ps1"
$planPath = Join-Path $repoRoot "docs/strategy485-aged-position-review-plan.md"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$smokeText = Get-Content -Raw -LiteralPath $smokePath
$planText = Get-Content -Raw -LiteralPath $planPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[strategy485-position-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "smoke_strategy485_position_risk_ssh.ps1",
        "smoke_live_origin_delta_local.ps1",
        "origin_delta_status",
        "openStrategy485Positions",
        "negativeEvPositions",
        "closeOrModifySuggestions",
        "positionTimeoutEvents",
        "strategy485_position_risk_recommendation",
        "deploy_required_before_strategy485_review",
        "operator_review_packet_allowed",
        "position_or_oco_mutation_allowed=false",
        "strategy485_review_missing_requirements",
        "strategy485_position_review_gate_status",
        "strategy485_position_review_next_action",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_OCO_PROTECTION_FIRST",
        "WATCH_ONLY",
        "NO_POSITION_RISK_ACTION",
        "NO_EVIDENCE",
        "fresh OCO health OK evidence",
        "separate operator approval before close/OCO mutation",
        "notAuthorization=read-only gate only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Add-MissingRequirement"
    )) {
    Assert-Contains -Name "strategy485 position review gate marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
        "FIX_OCO_PROTECTION_FIRST",
        "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED",
        "WATCH_TP_STRETCH",
        "NO_POSITION_RISK_ACTION",
        "notAuthorization=read-only evidence only"
    )) {
    Assert-Contains -Name "strategy485 position-risk smoke supports gate" -Text $smokeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "origin_delta_status=RUNTIME_DRIFT",
        "requires a separate exact diff",
        "not authorization"
    )) {
    Assert-Contains -Name "strategy485 review plan supports gate" -Text $planText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy485_position_review_gate_ssh.ps1",
        "strategy 485 position review gate",
        "read-only",
        "operator_review_packet_allowed",
        "deploy_required_before_strategy485_review",
        "position_or_oco_mutation_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention strategy485 gate" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-PositionAgeWarnDays", "0") `
    -ExpectedPattern "PositionAgeWarnDays must be between 1 and 90"

Write-Host "[strategy485-position-review-gate-test] OK"
