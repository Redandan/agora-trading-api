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

    $script = Join-Path $PSScriptRoot "prepare_profit_operator_action_brief_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator action brief test"
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
        throw "profit operator action brief accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "profit operator action brief did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator action brief reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_action_brief_ssh.ps1"
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
        "[profit-operator-action-brief] read-only brief",
        "scope=READ_ONLY",
        "prepare_profit_operator_review_matrix_ssh.ps1",
        "child_heartbeat",
        "timeoutSeconds",
        "timedOut",
        "child_error_summary",
        "MatrixOutputPath",
        "SaveMatrixOutputPath",
        "matrix_reuse",
        "matrix_saved",
        "source_matrix_mode",
        "REUSED_OUTPUT_FILE",
        "FRESH_CHILD_RUN",
        "profit_operator_action_primary_recommendation",
        "profit_operator_decision_lanes",
        "decisionLanes",
        "EXIT_SIDE_REVIEW_READY_NOT_LIVE",
        "ENTRY_FILTER_POLICY_BLOCKED",
        "DATAFRESHNESS_REPLAY_BLOCKED",
        "profit_operator_action_items",
        "profit_operator_action_blocked_items",
        "profit_operator_action_brief_packet",
        "profit_operator_action_brief_status",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION",
        "DO_NOT_RELAX_ENTRY_FILTERS_KEEP_GOVERNANCE_REVIEW",
        "COLLECT_DATAFRESHNESS_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW",
        "doNotActions",
        "notAuthorization=read-only profit operator action brief only",
        "does not deploy",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator action brief marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-operator-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $matrixPacket = [pscustomobject]@{
        reviewItems = @(
            [pscustomobject]@{
                lane = "exit-side"
                priority = "P1"
                status = "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION"
                readyForOperatorReview = $true
                evidenceMarkers = @("trailing_stop_acceptance=PASS", "exit_side_profit_review_packet_status=READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION")
                missingRequirements = @()
                nextAction = "Attach exit-side packet to a separate operator review."
            },
            [pscustomobject]@{
                lane = "entry-filter"
                priority = "P2"
                status = "BLOCKED_GOVERNANCE_MISSED_OPPORTUNITY_REVIEW"
                readyForOperatorReview = $false
                evidenceMarkers = @("signal_policy_clear=false", "data_freshness_current_status=NO_CURRENT_SAMPLE")
                missingRequirements = @("DataFreshness current sample")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE")
                missingRequirements = @("replayCandidateId", "counterfactual snapshots")
                nextAction = "Collect replay snapshots before policy review."
            }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $matrixPacket)),
        "profit_operator_review_matrix_next_action=Review ready read-only items separately."
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $reuseOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -MatrixOutputPath $tempMatrixPath -RequireReady 2>&1
        $reuseExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $reuseText = ($reuseOutput | Out-String)
    if ($reuseExitCode -ne 0) {
        throw "profit operator action brief failed to reuse matrix output:`n$reuseText"
    }
    foreach ($marker in @(
            "matrix_reuse",
            "source_matrix_mode=REUSED_OUTPUT_FILE",
            "profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION"
        )) {
        Assert-Contains -Name "profit operator action brief matrix reuse" -Text $reuseText -Pattern ([regex]::Escape($marker))
    }
    if ($reuseText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator action brief reuse path unexpectedly invoked a child or SSH:`n$reuseText"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
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
        throw "profit operator action brief must not contain mutation marker: $forbidden"
    }
}

foreach ($marker in @(
        "prepare_profit_operator_action_brief_ssh.ps1",
        "profit operator action brief",
        "profit_operator_action_brief_status",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION",
        "profit_operator_action_items",
        "does not deploy"
    )) {
    Assert-Contains -Name "operator docs mention profit operator action brief" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReplayLimit", "0") `
    -ExpectedPattern "ReplayLimit must be between 1 and 500"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ChildTimeoutSeconds", "1") `
    -ExpectedPattern "ChildTimeoutSeconds must be between 60 and 3600"

Write-Host "[profit-operator-action-brief-test] OK"
