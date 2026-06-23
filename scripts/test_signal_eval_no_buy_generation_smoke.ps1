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

    $script = Join-Path $PSScriptRoot "smoke_signal_eval_no_buy_generation_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for signal-eval no-buy generation smoke test"
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
        throw "Signal-eval no-buy generation smoke accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "Signal-eval no-buy generation smoke did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Signal-eval no-buy generation smoke reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_signal_eval_no_buy_generation_ssh.ps1"
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
        "[signal-eval-no-buy-generation] read-only production DB evidence check",
        "scope=READ_ONLY",
        "direct MySQL SELECTs only",
        "Signal Eval No-Buy Generation Summary",
        "signal_eval_rows",
        "buy_like_signal_eval_rows",
        "no_buy_signal_eval_rows",
        "hold_reason_rows",
        "macro_or_unknown_strategy_rows",
        "signal_eval_reason_family_distribution",
        "signal_eval_strategy_distribution",
        "signal_eval_context_side_distribution",
        "signal_eval_no_buy_generation_recommendation",
        "NO_SIGNAL_EVAL_IN_REVIEW_WINDOW",
        "NO_BUY_LIKE_SIGNAL_EVAL_HOLD_OR_WAIT_DOMINATES",
        "NO_BUY_LIKE_SIGNAL_EVAL_MACRO_OR_UNKNOWN_DOMINATES",
        "NO_BUY_LIKE_SIGNAL_EVAL_MIXED_REVIEW",
        "BUY_LIKE_SIGNAL_EVAL_PRESENT_REVIEW_PROGRESS_PATH",
        "notAuthorization=read-only evidence only",
        "does not authorize live trading",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "signal-eval no-buy generation smoke marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "UPDATE ",
        "INSERT ",
        "DELETE ",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "Signal-eval no-buy generation smoke must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "smoke_signal_eval_no_buy_generation_ssh.ps1",
        "signal_eval_no_buy_generation_recommendation",
        "NO_BUY_LIKE_SIGNAL_EVAL_HOLD_OR_WAIT_DOMINATES",
        "SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW",
        "does not authorize live trading"
    )) {
    Assert-Contains -Name "operator docs mention signal-eval no-buy generation smoke" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 30"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-Limit", "0") `
    -ExpectedPattern "Limit must be between 1 and 50"

Write-Host "[signal-eval-no-buy-generation-smoke-test] OK"
