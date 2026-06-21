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

    $script = Join-Path $PSScriptRoot "prepare_data_freshness_shadow_candidate_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for DataFreshness shadow candidate packet test"
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
        throw "DataFreshness shadow candidate packet accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "DataFreshness shadow candidate packet did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "DataFreshness shadow candidate packet reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_data_freshness_shadow_candidate_packet_ssh.ps1"
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
        "[data-freshness-shadow-candidate-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "smoke_data_freshness_counterfactual_review_ssh.ps1",
        "data_freshness_shadow_candidate_packet",
        "data_freshness_shadow_candidate_packet_status",
        "data_freshness_shadow_candidate_missing_requirements",
        "READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE",
        "BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING",
        "NO_EVIDENCE",
        "complete_replayable_candidate_rows",
        "missing_counterfactual_fields",
        "replay_input_stage",
        "collector_status_counts",
        "hard_gate_preview_status_counts",
        "replay_input_next_action",
        "shadow_candidate_review_allowed",
        "data_freshness_policy_relaxation_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "notAuthorization=read-only DataFreshness shadow candidate packet only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReview"
    )) {
    Assert-Contains -Name "DataFreshness shadow candidate packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
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
        throw "DataFreshness shadow candidate packet must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_data_freshness_shadow_candidate_packet_ssh.ps1",
        "DataFreshness shadow candidate packet",
        "data_freshness_shadow_candidate_packet_status",
        "READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE",
        "BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING",
        "shadow_candidate_review_allowed",
        "data_freshness_policy_relaxation_allowed=false",
        "tiny_live_order_allowed=false",
        "live_policy_change_allowed=false",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention DataFreshness shadow candidate packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-CounterfactualReviewDays", "0") `
    -ExpectedPattern "CounterfactualReviewDays must be between 1 and 90"

Write-Host "[data-freshness-shadow-candidate-packet-test] OK"
