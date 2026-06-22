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
$latestScriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_latest_action_brief.ps1"
$summaryScriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_review_summary.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$latestScriptText = Get-Content -Raw -LiteralPath $latestScriptPath
$summaryScriptText = Get-Content -Raw -LiteralPath $summaryScriptPath
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
        "ReviewOutputDir",
        "MatrixMaxAgeMinutes",
        "Get-MatrixFreshness",
        "Get-DefaultMatrixOutputPath",
        "Save-MatrixOutput",
        "target/profit-review",
        "latest-profit-operator-matrix.path",
        "matrix_reuse",
        "matrix_saved",
        "matrix_latest_pointer",
        "matrix_freshness_status",
        "source_matrix_freshness_status",
        "source_matrix_mode",
        "REUSED_OUTPUT_FILE",
        "FRESH_CHILD_RUN",
        "profit_operator_action_primary_recommendation",
        "profit_operator_decision_lanes",
        "decisionLanes",
        "exitSideActionProposals",
        "exit_side_operator_action_proposals",
        "trailing-stop-rollout-review",
        "strategy485-risk-reduction-review",
        "DRY_RUN_OR_ROLLOUT_REVIEW_NOT_LIVE",
        "RISK_REDUCTION_REVIEW_NOT_MUTATION",
        "docs/exit-side-operator-review-plan.md",
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

foreach ($marker in @(
        "[profit-operator-latest-action-brief] read-only latest brief",
        "latest-profit-operator-matrix.path",
        "latest_matrix_pointer",
        "latest_matrix_output_path",
        "prepare_profit_operator_action_brief_ssh.ps1",
        "-MatrixOutputPath",
        "profit_operator_latest_action_brief_exit_code",
        "no SSH fresh matrix",
        "does not deploy",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator latest action brief marker" -Text $latestScriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "[profit-operator-review-summary] read-only summary",
        "prepare_profit_operator_latest_action_brief.ps1",
        "profit_operator_action_brief_packet",
        "profit_operator_review_summary_packet",
        "profit_operator_review_summary_ready_lanes",
        "profit_operator_review_summary_blocked_lanes",
        "profit_operator_review_summary_required_evidence",
        "PROFIT_OPERATOR_REVIEW_SUMMARY",
        "readyLaneCount",
        "blockedLaneCount",
        "exitSideProposalCount",
        "exitSideActionProposals",
        "profit_operator_review_summary_exit_side_proposals",
        "requiredEvidence",
        "notAuthorization=read-only profit operator review summary only",
        "does not deploy",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator review summary marker" -Text $summaryScriptText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-operator-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-review-" + [guid]::NewGuid().ToString("N"))
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
            "matrix_freshness_status=FRESH",
            "source_matrix_freshness_status=FRESH",
            "profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION",
            "exit_side_operator_action_proposals=",
            "trailing-stop-rollout-review",
            "strategy485-risk-reduction-review",
            "proposal only; does not authorize live trailing",
            "proposal only; does not authorize close-position"
        )) {
        Assert-Contains -Name "profit operator action brief matrix reuse" -Text $reuseText -Pattern ([regex]::Escape($marker))
    }
    if ($reuseText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator action brief reuse path unexpectedly invoked a child or SSH:`n$reuseText"
    }

    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = (Get-Date).AddMinutes(-10)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $staleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -MatrixOutputPath $tempMatrixPath -MatrixMaxAgeMinutes 1 2>&1
        $staleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $staleText = ($staleOutput | Out-String)
    if ($staleExitCode -eq 0) {
        throw "profit operator action brief accepted stale matrix output:`n$staleText"
    }
    foreach ($marker in @(
            "matrix_freshness_status=STALE",
            "MatrixOutputPath is stale"
        )) {
        Assert-Contains -Name "profit operator action brief stale matrix guard" -Text $staleText -Pattern ([regex]::Escape($marker))
    }
    if ($staleText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator action brief stale reuse path unexpectedly invoked a child or SSH:`n$staleText"
    }

    (Get-Item -LiteralPath $tempMatrixPath).LastWriteTime = Get-Date
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    $latestPointerPath = Join-Path $tempReviewDir "latest-profit-operator-matrix.path"
    Set-Content -LiteralPath $latestPointerPath -Encoding UTF8 -Value $tempMatrixPath
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $latestOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $latestScriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
        $latestExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $latestText = ($latestOutput | Out-String)
    if ($latestExitCode -ne 0) {
        throw "profit operator latest action brief failed to reuse pointer matrix output:`n$latestText"
    }
    foreach ($marker in @(
            "[profit-operator-latest-action-brief] read-only latest brief",
            "latest_matrix_pointer=",
            "latest_matrix_output_path=$tempMatrixPath",
            "source_matrix_mode=REUSED_OUTPUT_FILE",
            "profit_operator_action_brief_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "profit_operator_latest_action_brief_exit_code=0"
        )) {
        Assert-Contains -Name "profit operator latest action brief pointer reuse" -Text $latestText -Pattern ([regex]::Escape($marker))
    }
    if ($latestText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator latest action brief unexpectedly invoked a child or SSH:`n$latestText"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $summaryOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $summaryScriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
        $summaryExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $summaryText = ($summaryOutput | Out-String)
    if ($summaryExitCode -ne 0) {
        throw "profit operator review summary failed to reuse latest pointer matrix output:`n$summaryText"
    }
    foreach ($marker in @(
            "[profit-operator-review-summary] read-only summary",
            "profit_operator_review_summary_packet=",
            '"packetType":"PROFIT_OPERATOR_REVIEW_SUMMARY"',
            '"readyLaneCount":1',
            '"blockedLaneCount":2',
            '"exitSideProposalCount":2',
            '"proposalId":"trailing-stop-rollout-review"',
            '"proposalId":"strategy485-risk-reduction-review"',
            "profit_operator_review_summary_exit_side_proposals=",
            '"lane":"exit-side"',
            '"lane":"entry-filter"',
            '"lane":"data-freshness-replay"',
            '"requirement":"DataFreshness current sample"',
            '"requirement":"replayCandidateId"',
            "profit_operator_review_summary_status=READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
            "notAuthorization=read-only profit operator review summary only"
        )) {
        Assert-Contains -Name "profit operator review summary latest pointer reuse" -Text $summaryText -Pattern ([regex]::Escape($marker))
    }
    if ($summaryText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "profit operator review summary unexpectedly invoked a child or SSH:`n$summaryText"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
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
        "prepare_profit_operator_latest_action_brief.ps1",
        "prepare_profit_operator_review_summary.ps1",
        "profit operator action brief",
        "profit_operator_action_brief_status",
        "profit_operator_review_summary_packet",
        "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE",
        "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION",
        "exit_side_operator_action_proposals",
        "trailing-stop-rollout-review",
        "strategy485-risk-reduction-review",
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

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-MatrixMaxAgeMinutes", "0") `
    -ExpectedPattern "MatrixMaxAgeMinutes must be between 1 and 1440"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewOutputDir", " ") `
    -ExpectedPattern "ReviewOutputDir is required"

Write-Host "[profit-operator-action-brief-test] OK"
