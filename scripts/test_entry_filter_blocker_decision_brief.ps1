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

    $script = Join-Path $PSScriptRoot "prepare_entry_filter_blocker_decision_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for entry-filter blocker decision brief test"
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
        throw "entry-filter blocker decision brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "entry-filter blocker decision brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "entry-filter blocker decision brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_entry_filter_blocker_decision_brief_ssh.ps1"
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
        "[entry-filter-blocker-decision-brief] read-only brief",
        "scope=READ_ONLY",
        "smoke_signal_correctness_ssh.ps1",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "prepare_entry_dedup_operator_decision_brief_ssh.ps1",
        "signal_policy_clear",
        "governance_mode",
        "missed_opportunity_status",
        "data_freshness_current_status",
        "data_freshness_replay_status",
        "entry_dedup_operator_decision_brief_status",
        "entry_dedup_shadow_lane_status",
        "entry_filter_policy_lane_status",
        "data_freshness_replay_lane_status",
        "entry_filter_blocker_missing_requirements",
        "entry_filter_blocker_decision_checklist",
        "entry_filter_blocker_decision_brief_packet",
        "entry_filter_blocker_decision_brief_status",
        "BLOCKED_SIGNAL_POLICY_OR_MISSED_OPPORTUNITY_REVIEW",
        "BLOCKED_DATAFRESHNESS_REPLAY_EVIDENCE",
        "READY_FOR_ENTRY_FILTER_OPERATOR_REVIEW_NOT_LIVE",
        "RequireBrief",
        "ChildTimeoutSeconds",
        "child_start",
        "child_heartbeat",
        "child_complete",
        "child_error_summary",
        "timedOut",
        "notAuthorization=read-only entry-filter blocker decision brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe"
    )) {
    Assert-Contains -Name "entry-filter blocker decision brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "entry-filter blocker decision brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_entry_filter_blocker_decision_brief_ssh.ps1",
        "entry-filter blocker decision brief",
        "entry_filter_blocker_decision_brief_status",
        "entry_filter_policy_lane_status",
        "data_freshness_replay_lane_status",
        "entry_dedup_shadow_lane_status",
        "entry_filter_blocker_missing_requirements",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention entry-filter blocker decision brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayLimit", "0") `
    -ExpectedPattern "ReplayLimit must be between 1 and 1000"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "1") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[entry-filter-blocker-decision-brief-test] OK"
