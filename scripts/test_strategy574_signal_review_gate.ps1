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

    $script = Join-Path $PSScriptRoot "prepare_strategy574_signal_review_gate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for strategy574 signal review gate test"
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
        throw "strategy574 signal review gate accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "strategy574 signal review gate did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "strategy574 signal review gate reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_strategy574_signal_review_gate_ssh.ps1"
$smokePath = Join-Path $PSScriptRoot "smoke_strategy574_signal_governance_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$smokeText = Get-Content -Raw -LiteralPath $smokePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[strategy574-signal-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "smoke_strategy574_signal_governance_ssh.ps1",
        "smoke_live_origin_delta_local.ps1",
        "origin_delta_status",
        "strategy574_near_buy",
        "governance_too_strict_7d_or_14d",
        "short_window_insufficient_data",
        "data_freshness_current_clean",
        "strategy574_terminal_reason",
        "strategy574_policy_change_recommendation",
        "deploy_required_before_strategy574_review",
        "shadow_observation_review_allowed",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "strategy574_review_missing_requirements",
        "strategy574_signal_review_gate_status",
        "READY_FOR_OBSERVATION_REVIEW_NOT_ORDER",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "BLOCKED_FIX_CURRENT_DATA_FRESHNESS",
        "WAIT_BUY_THRESHOLD_CROSS",
        "NO_EVIDENCE",
        "current BUY candidate",
        "OCO preflight pass",
        "EV pass sample",
        "post-trade OCO protection evidence",
        "notAuthorization=read-only gate only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Add-MissingRequirement"
    )) {
    Assert-Contains -Name "strategy574 signal review gate marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS",
        "WAIT_BUY_THRESHOLD_CROSS",
        "DO_NOT_RELAX_ENTRY_DEDUP_OR_DATAFRESHNESS_LIVE",
        "strategy574_near_buy",
        "notAuthorization"
    )) {
    Assert-Contains -Name "strategy574 governance smoke supports gate" -Text $smokeText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_strategy574_signal_review_gate_ssh.ps1",
        "strategy 574 signal review gate",
        "read-only",
        "shadow_observation_review_allowed",
        "deploy_required_before_strategy574_review",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention strategy574 gate" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-StrategyId", "0") `
    -ExpectedPattern "StrategyId must be between 1 and 999999999"

Write-Host "[strategy574-signal-review-gate-test] OK"
