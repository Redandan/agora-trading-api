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

    $script = Join-Path $PSScriptRoot "prepare_signal_missed_blocker_decision_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for signal/missed blocker decision brief test"
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
        throw "signal/missed blocker decision brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "signal/missed blocker decision brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "signal/missed blocker decision brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_signal_missed_blocker_decision_brief_ssh.ps1"
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
        "[signal-missed-blocker-decision-brief] read-only brief",
        "scope=READ_ONLY",
        "prepare_entry_filter_operator_review_packet_ssh.ps1",
        "prepare_no_buy_row_review_packet_ssh.ps1",
        "prepare_missed_opportunity_shadow_design_packet_ssh.ps1",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "signal_policy_clear",
        "governance_mode",
        "missed_opportunity_status",
        "data_freshness_current_status",
        "entry_filter_operator_lane_status",
        "no_buy_row_review_lane_status",
        "missed_opportunity_shadow_lane_status",
        "governance_relaxation_lane_status",
        "candidate_missed_opportunity_row_count",
        "signal_missed_blocker_missing_requirements",
        "signal_missed_blocker_decision_checklist",
        "signal_missed_blocker_decision_brief_packet",
        "signal_missed_blocker_decision_brief_status",
        "BLOCKED_SIGNAL_MISSED_GOVERNANCE_REVIEW",
        "READY_FOR_SIGNAL_MISSED_OPERATOR_REVIEW_NOT_LIVE",
        "REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        "ChildTimeoutSeconds",
        "RequireBrief",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "child_error_summary",
        "timedOut",
        "notAuthorization=read-only signal/missed blocker decision brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "signal/missed blocker decision brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        "git pull",
        "git reset",
        "bash deploy.sh",
        "systemctl reload",
        "nginx -s reload",
        "TRADING_OKX_ENABLED=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=true",
        "ENTRY_DEDUP_ENABLED=false",
        "DATA_FRESHNESS_GUARD_ENABLED=false"
    )) {
    if ($scriptText -match [regex]::Escape($forbidden)) {
        throw "signal/missed blocker decision brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_signal_missed_blocker_decision_brief_ssh.ps1",
        "signal/missed blocker decision brief",
        "signal_missed_blocker_decision_brief_status",
        "entry_filter_operator_lane_status",
        "no_buy_row_review_lane_status",
        "missed_opportunity_shadow_lane_status",
        "governance_relaxation_lane_status",
        "signal_missed_blocker_missing_requirements",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention signal/missed blocker decision brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-BlockedDays", "0") `
    -ExpectedPattern "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "1") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[signal-missed-blocker-decision-brief-test] OK"
