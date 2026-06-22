Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_exit_side_operator_experiment_packet.ps1"
$summaryScriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_review_summary.ps1"
$planPath = Join-Path $repoRoot "docs/exit-side-operator-review-plan.md"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$summaryScriptText = Get-Content -Raw -LiteralPath $summaryScriptPath
$planText = Get-Content -Raw -LiteralPath $planPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[exit-side-operator-experiment-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_profit_operator_review_summary.ps1",
        "EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW",
        "READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE",
        "trailing-stop-dry-run-experiment-review",
        "strategy485-risk-reduction-shadow-review",
        "DRY_RUN_EXIT_POLICY_REVIEW_NOT_LIVE",
        "SHADOW_RISK_REDUCTION_REVIEW_NOT_MUTATION",
        "source_matrix_freshness_status",
        "exit_side_experiment_packet_missing_requirements",
        "exit_side_experiment_proposals",
        "exit_side_operator_experiment_packet",
        "exit_side_operator_experiment_packet_status",
        "notAuthorization=read-only exit-side experiment packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "exit-side experiment packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "profit_operator_review_summary_freshness_status",
        "profit_operator_review_summary_matrix_age_minutes",
        "sourceMatrixFreshness"
    )) {
    Assert-Contains -Name "summary supports exit-side experiment packet" -Text $summaryScriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_exit_side_operator_experiment_packet.ps1",
        "EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW",
        "trailing-stop-dry-run-experiment-review",
        "strategy485-risk-reduction-shadow-review",
        "READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "exit-side plan mentions experiment packet" -Text $planText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_exit_side_operator_experiment_packet.ps1",
        "exit_side_operator_experiment_packet",
        "READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE",
        "trailing-stop-dry-run-experiment-review",
        "strategy485-risk-reduction-shadow-review"
    )) {
    Assert-Contains -Name "operator docs mention exit-side experiment packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempMatrixPath = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-matrix-" + [guid]::NewGuid().ToString("N") + ".log")
$tempReviewDir = Join-Path ([System.IO.Path]::GetTempPath()) ("exit-side-review-" + [guid]::NewGuid().ToString("N"))
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
                missingRequirements = @("governance drift and missed-opportunity review")
                nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged."
            },
            [pscustomobject]@{
                lane = "data-freshness-replay"
                priority = "P2"
                status = "PENDING_DATAFRESHNESS_CURRENT_SAMPLE"
                readyForOperatorReview = $false
                evidenceMarkers = @("profit_evidence_watch_reason=NO_CURRENT_SAMPLE")
                missingRequirements = @("fresh replayCandidateId rows", "counterfactual snapshots")
                nextAction = "Collect replay snapshots before policy review."
            }
        )
    }
    Set-Content -LiteralPath $tempMatrixPath -Encoding UTF8 -Value @(
        "profit_operator_review_matrix_status=HAS_REVIEW_READY_ITEMS_NOT_LIVE",
        ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 8 $matrixPacket)),
        "profit_operator_review_matrix_next_action=Review ready read-only items separately."
    )
    New-Item -ItemType Directory -Force -Path $tempReviewDir | Out-Null
    Set-Content -LiteralPath (Join-Path $tempReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $tempMatrixPath

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for exit-side experiment packet test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewOutputDir $tempReviewDir -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "exit-side operator experiment packet failed latest-pointer reuse:`n$text"
    }
    foreach ($marker in @(
            "source_matrix_freshness_status=FRESH",
            "exit_side_operator_experiment_packet_status=READY_FOR_EXIT_SIDE_EXPERIMENT_REVIEW_NOT_LIVE",
            '"packetType":"EXIT_SIDE_OPERATOR_EXPERIMENT_REVIEW"',
            '"proposalId":"trailing-stop-dry-run-experiment-review"',
            '"proposalId":"strategy485-risk-reduction-shadow-review"',
            '"experimentClass":"DRY_RUN_EXIT_POLICY_REVIEW_NOT_LIVE"',
            '"experimentClass":"SHADOW_RISK_REDUCTION_REVIEW_NOT_MUTATION"',
            '"forbiddenActions":["enable live trading"',
            "notAuthorization=read-only exit-side experiment packet only"
        )) {
        Assert-Contains -Name "exit-side experiment packet latest pointer reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "exit-side operator experiment packet unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempMatrixPath) {
        Remove-Item -LiteralPath $tempMatrixPath -Force
    }
    if (Test-Path -LiteralPath $tempReviewDir) {
        Remove-Item -LiteralPath $tempReviewDir -Recurse -Force
    }
}

Write-Host "[exit-side-operator-experiment-packet-test] OK"
