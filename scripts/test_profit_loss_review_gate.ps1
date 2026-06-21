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

    $script = Join-Path $PSScriptRoot "prepare_profit_loss_review_gate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit loss review gate test"
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
        throw "profit loss review gate accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit loss review gate did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit loss review gate reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_loss_review_gate_ssh.ps1"
$profitPath = Join-Path $PSScriptRoot "smoke_profit_candidate_review_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$profitText = Get-Content -Raw -LiteralPath $profitPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-loss-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "smoke_live_origin_delta_local.ps1",
        "smoke_profit_candidate_review_ssh.ps1",
        "monthlyPnlTotalUsdt",
        "profit_candidate_review_recommendation",
        "missedOpportunityStatus",
        "falseBlockRiskCount",
        "suspiciousNoBuyCount",
        "dataFreshnessFalseKillPct",
        "trailingReplayAcceptance",
        "profit_loss_candidate_items",
        "deploy_required_before_profit_loss_review",
        "loss_source_review_allowed",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false",
        "profit_loss_review_missing_requirements",
        "profit_loss_review_gate_status",
        "profit_loss_review_next_action",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE",
        "OBSERVE_CANDIDATES_NO_LOSS_PACKET",
        "NO_LOSS_SOURCE_ACTION_FROM_CURRENT_EVIDENCE",
        "NO_EVIDENCE",
        "RequireReady",
        "notAuthorization=read-only gate only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Convert-JsonArrayOrEmpty",
        "Convert-NullableDouble",
        "Add-MissingRequirement"
    )) {
    Assert-Contains -Name "profit loss review gate marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "monthlyPnlTotalUsdt",
        "profit_candidate_items",
        "profit_candidate_review_recommendation",
        "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY",
        "DO_NOT_ENABLE_TRAILING_STOP_OVERLAY"
    )) {
    Assert-Contains -Name "profit candidate smoke supports loss gate" -Text $profitText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_profit_loss_review_gate_ssh.ps1",
        "profit loss review gate",
        "read-only",
        "loss_source_review_allowed",
        "deploy_required_before_profit_loss_review",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention profit loss gate" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

Write-Host "[profit-loss-review-gate-test] OK"
