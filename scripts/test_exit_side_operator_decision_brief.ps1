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

    $script = Join-Path $PSScriptRoot "prepare_exit_side_operator_decision_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for exit-side decision brief test"
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
        throw "exit-side decision brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "exit-side decision brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "exit-side decision brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_exit_side_operator_decision_brief_ssh.ps1"
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
        "[exit-side-operator-decision-brief] read-only brief",
        "scope=READ_ONLY",
        "prepare_exit_side_profit_review_packet_ssh.ps1",
        "exit_side_operator_primary_recommendation",
        "exit_side_operator_decision_lanes",
        "decisionLanes",
        "trailing-stop-rollout",
        "strategy485-risk-reduction",
        "entry-filter-datafreshness-policy",
        "NOT_DECIDED_BY_EXIT_SIDE_BRIEF",
        "strategy485_position_summaries",
        "trailingStopAcceptanceRows",
        "exit_side_operator_review_recommendations",
        "exit_side_operator_decision_brief_packet",
        "exit_side_operator_decision_brief_status",
        "READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW",
        "TRAILING_STOP_EXIT_POLICY_REVIEW",
        "STRATEGY485_AGED_NEGATIVE_EV_POSITION_REVIEW",
        "separateAuthorizationsRequired",
        "doNotActions",
        "notAuthorization=read-only exit-side operator decision brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireDecisionReady"
    )) {
    Assert-Contains -Name "exit-side decision brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "exit-side decision brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_exit_side_operator_decision_brief_ssh.ps1",
        "exit-side operator decision brief",
        "exit_side_operator_decision_brief_status",
        "READY_FOR_OPERATOR_DECISION_NOT_MUTATION",
        "PREPARE_SEPARATE_EXIT_SIDE_OPERATOR_REVIEW",
        "exit_side_operator_review_recommendations",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention exit-side decision brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayLimit", "0") `
    -ExpectedPattern "ReplayLimit must be between 1 and 500"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-StrategyId", "0") `
    -ExpectedPattern "StrategyId must be between 1 and 1000000"

Write-Host "[exit-side-operator-decision-brief-test] OK"
