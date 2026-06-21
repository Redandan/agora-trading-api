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

    $script = Join-Path $PSScriptRoot "prepare_auto_trading_review_gate_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for auto-trading review gate test"
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
        throw "auto-trading review gate accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "auto-trading review gate did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "auto-trading review gate reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_auto_trading_review_gate_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "smoke_auto_trading_review_bundle_ssh.ps1"
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
        "[auto-trading-review-gate] read-only evidence gate",
        "scope=READ_ONLY",
        "smoke_auto_trading_review_bundle_ssh.ps1",
        "origin_delta_status",
        "live_authorized_audit_verdict",
        "strategy485_position_risk_recommendation",
        "strategy574_policy_change_recommendation",
        "tiny_live_post_trade_status",
        "deploy_required_before_auto_trading_review",
        "operator_review_packet_allowed",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "auto_trading_review_missing_requirements",
        "auto_trading_review_gate_status",
        "auto_trading_review_next_action",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "READY_FOR_OPERATOR_POSITION_REVIEW_NOT_MUTATION",
        "WAIT_STRATEGY574_THRESHOLD_CROSS",
        "WAIT_TINYLIVE_POST_TRADE_EVIDENCE",
        "BLOCKED_REVIEW_LIVE_AUTHORIZED_AUDIT",
        "NO_EVIDENCE",
        "RequireReady",
        "notAuthorization=read-only gate only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Convert-JsonArrayOrEmpty",
        "Add-MissingRequirement"
    )) {
    Assert-Contains -Name "auto-trading review gate marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "review_items",
        "auto_trading_review_recommendation",
        "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK",
        "CONTINUE_TINYLIVE_MONITORING"
    )) {
    Assert-Contains -Name "auto-trading bundle supports gate" -Text $bundleText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_auto_trading_review_gate_ssh.ps1",
        "auto-trading review gate",
        "read-only",
        "operator_review_packet_allowed",
        "deploy_required_before_auto_trading_review",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention auto-trading gate" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

Write-Host "[auto-trading-review-gate-test] OK"
